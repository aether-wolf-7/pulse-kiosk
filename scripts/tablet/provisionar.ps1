# Configura um tablet Samsung como terminal Pulse Kiosk.
#
# Feito para ser executado por alguem da academia, sem conhecimento tecnico:
# so plugar o tablet no cabo USB e dar duplo clique no provisionar.bat.
#
# O script se recusa a continuar se alguma condicao nao estiver certa, em vez
# de deixar o tablet num estado meio configurado.

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Pacote  = "br.com.pulsefitness.kiosk"
$Admin   = "$Pacote/.kiosk.KioskDeviceAdminReceiver"
$ApkUrl  = "https://kiosk.pulsefitness.com.br/baixar/pulse-kiosk.apk"
$Base    = $PSScriptRoot
$Apk     = Join-Path $Base "pulse-kiosk.apk"

function Titulo($t) {
    Write-Host ""
    Write-Host "=============================================" -ForegroundColor Cyan
    Write-Host " $t" -ForegroundColor Cyan
    Write-Host "=============================================" -ForegroundColor Cyan
}
function Ok($t)    { Write-Host "  [OK] $t" -ForegroundColor Green }
function Aviso($t) { Write-Host "  [!]  $t" -ForegroundColor Yellow }
function Parar($t) {
    Write-Host ""
    Write-Host "  PAROU AQUI: $t" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Nada foi quebrado no tablet. Resolva o item acima e rode de novo." -ForegroundColor Red
    Write-Host "  Se tiver duvida, tire um print desta tela e mande pro Joao." -ForegroundColor Red
    Write-Host ""
    Read-Host "  Pressione ENTER para fechar"
    exit 1
}

Titulo "PULSE KIOSK - Configuracao do tablet"
Write-Host "  Este script prepara UM tablet. Rode uma vez para cada um."
Write-Host ""

# Running straight from inside the .zip is the most likely mistake: Windows
# opens a zip like a folder, extracts to a temporary place on double click,
# and the script then cannot find its own files or keep what it downloads.
if ($Base -like "*\AppData\Local\Temp\*" -or $Base -like "*.zip*") {
    Write-Host ""
    Write-Host "  Parece que voce abriu o arquivo de dentro do ZIP." -ForegroundColor Red
    Write-Host ""
    Write-Host "  Faca assim:" -ForegroundColor Yellow
    Write-Host "    1. Feche esta janela"
    Write-Host "    2. Clique com o botao direito no ZIP e escolha Extrair tudo"
    Write-Host "    3. Extraia para C:\pulse-kiosk"
    Write-Host "    4. Abra a pasta C:\pulse-kiosk e de duplo clique no provisionar.bat"
    Write-Host ""
    Read-Host "  Pressione ENTER para fechar"
    exit 1
}

# Both files have to travel together.
if (-not (Test-Path (Join-Path $Base "provisionar.bat"))) {
    Write-Host ""
    Write-Host "  Falta o arquivo provisionar.bat nesta pasta." -ForegroundColor Red
    Write-Host "  Os dois arquivos precisam ficar juntos na mesma pasta." -ForegroundColor Red
    Write-Host "  Pasta atual: $Base"
    Write-Host ""
    Read-Host "  Pressione ENTER para fechar"
    exit 1
}

Write-Host "  Pasta de trabalho: $Base"
Write-Host ""

Titulo "1 de 6 - Preparando as ferramentas"
$Adb = Join-Path $Base "platform-tools\adb.exe"
if (-not (Test-Path $Adb)) {
    Write-Host "  Baixando as ferramentas do Google (uma vez so, cerca de 10 MB)..."
    try {
        $zip = Join-Path $Base "platform-tools.zip"
        Invoke-WebRequest -Uri "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
        Expand-Archive -Path $zip -DestinationPath $Base -Force
        Remove-Item $zip -Force
    } catch {
        Parar "nao consegui baixar as ferramentas. Verifique a internet deste computador."
    }
}
if (-not (Test-Path $Adb)) { Parar "as ferramentas nao foram instaladas corretamente." }
Ok "ferramentas prontas"

if (-not (Test-Path $Apk)) {
    Write-Host "  Baixando o aplicativo..."
    try { Invoke-WebRequest -Uri $ApkUrl -OutFile $Apk }
    catch { Parar "nao consegui baixar o aplicativo. Verifique a internet." }
}
Ok "aplicativo pronto"

Titulo "2 de 6 - Procurando o tablet"
Write-Host "  Confira antes de continuar:"
Write-Host "    - o tablet esta ligado e desbloqueado"
Write-Host "    - esta conectado no cabo USB neste computador"
Write-Host "    - a Depuracao USB esta ligada (Ajustes, Opcoes do desenvolvedor)"
Write-Host "    - se aparecer um aviso no tablet pedindo permissao, marque"
Write-Host "      Sempre permitir e toque em Permitir"
Write-Host ""
Read-Host "  Quando estiver tudo isso, pressione ENTER"

& $Adb start-server | Out-Null
$saidaDisp = (& $Adb devices) | Out-String
$dispositivos = $saidaDisp -split "`n" | Where-Object { $_ -match "\tdevice\s*$" }
if (-not $dispositivos) {
    if ($saidaDisp -match "unauthorized") {
        Parar "o tablet apareceu mas nao autorizou este computador. Olhe a tela do tablet e toque em Permitir."
    }
    Parar "nenhum tablet encontrado. Confira o cabo e a Depuracao USB."
}
if (($dispositivos | Measure-Object).Count -gt 1) {
    Parar "tem mais de um aparelho conectado. Deixe so o tablet que voce vai configurar."
}
$modelo = (& $Adb shell getprop ro.product.model) | Out-String
Ok ("tablet encontrado: " + $modelo.Trim())

Titulo "3 de 6 - Conferindo se o tablet esta zerado"
$contas = (& $Adb shell dumpsys account) | Out-String
if ($contas -match "Account \{") {
    Write-Host ""
    Write-Host "  O tablet tem conta cadastrada (Google ou Samsung)." -ForegroundColor Red
    Write-Host "  O Android nao permite ativar o modo quiosque nessa situacao." -ForegroundColor Red
    Write-Host ""
    Write-Host "  Como resolver:" -ForegroundColor Yellow
    Write-Host "    1. Ajustes, Contas e backup, Gerenciar contas: remova todas"
    Write-Host "    2. Se nao conseguir remover, faca a restauracao de fabrica em"
    Write-Host "       Ajustes, Geral, Restaurar, Restaurar dados de fabrica"
    Write-Host "    3. Na configuracao inicial PULE o wi-fi e nao entre em conta nenhuma"
    Parar "existe conta no tablet"
}
Ok "nenhuma conta no tablet"

$usuarios = ((& $Adb shell pm list users) | Out-String) -split "`n" | Where-Object { $_ -match "UserInfo\{" }
if (($usuarios | Measure-Object).Count -gt 1) {
    Parar "o tablet tem mais de um usuario (Pasta Segura, Convidado ou Dual Messenger). Remova em Ajustes, Contas e backup."
}
Ok "somente o usuario principal"

Titulo "4 de 6 - Instalando o aplicativo"
& $Adb install -r "$Apk" 2>&1 | Out-Null
$instalado = (& $Adb shell pm list packages) | Out-String
if ($instalado -notmatch [regex]::Escape($Pacote)) { Parar "nao consegui instalar o aplicativo no tablet." }
Ok "aplicativo instalado"

Titulo "5 de 6 - Ativando o modo quiosque"
$saida = (& $Adb shell dpm set-device-owner $Admin) 2>&1 | Out-String
if ($saida -notmatch "Success") {
    Write-Host "  Resposta do tablet:" -ForegroundColor Yellow
    Write-Host ("  " + $saida.Trim())
    if ($saida -match "already some accounts") { Parar "ainda existe conta no tablet. Remova e rode de novo." }
    if ($saida -match "several users")         { Parar "ainda existe outro usuario no tablet." }
    Parar "nao consegui ativar o modo quiosque."
}
Ok "modo quiosque ativado"

& $Adb shell dumpsys deviceidle whitelist +$Pacote | Out-Null
Ok "aplicativo liberado da economia de bateria"

Titulo "6 de 6 - Abrindo o aplicativo"
& $Adb shell am start -n "$Pacote/.MainActivity" | Out-Null
Start-Sleep -Seconds 6
Ok "aplicativo aberto no tablet"

Titulo "AGORA NO TABLET"
Write-Host "  A tela pede um codigo de 6 digitos." -ForegroundColor White
Write-Host ""
Write-Host "  1. No painel kiosk.pulsefitness.com.br/admin entre em Maquinas"
Write-Host "  2. Marque a maquina onde ESTE tablet vai ficar"
Write-Host "  3. Escolha a acao Gerar codigo de pareamento para o tablet"
Write-Host "  4. Digite os 6 numeros no tablet e toque em OK"
Write-Host ""
Write-Host "  O tablet deve mostrar o nome da maquina e pedir ID e PIN do aluno."
Write-Host ""
Read-Host "  Quando terminar isso no tablet, pressione ENTER para a conferencia final"

Titulo "Conferencia final"
$lock = (& $Adb shell dumpsys activity activities) | Out-String
if ($lock -match "mLockTaskModeState=LOCKED") {
    Ok "tablet travado no aplicativo, modo quiosque funcionando"
} else {
    Aviso "o tablet ainda nao aparece como travado."
    Aviso "Reinicie o tablet e confira. Se continuar assim, avise o Joao."
}
Write-Host ""
Write-Host "  Ultimos passos, na mao:" -ForegroundColor White
Write-Host "    - reinicie o tablet e confirme que ele volta sozinho no aplicativo"
Write-Host "    - confirme que o botao home e o de recentes nao fazem nada"
Write-Host "    - deixe o carregador sempre ligado"
Write-Host "    - monte o suporte cobrindo os botoes de liga/desliga e de volume"
Write-Host ""
Write-Host "  Tablet pronto. Rode este script de novo para o proximo." -ForegroundColor Green
Write-Host ""
Read-Host "  Pressione ENTER para fechar"
