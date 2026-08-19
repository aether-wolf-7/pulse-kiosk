# Instalação dos tablets — passo a passo

Guia para quem vai instalar os tablets na academia. Não precisa saber nada de
programação. São uns 15 minutos por tablet.

## O que você precisa

- O tablet
- O cabo USB do tablet
- Um computador com Windows e internet
- A pasta `tablet` que o João enviou
- **Acesso ao painel** https://kiosk.pulsefitness.com.br/admin, ou alguém
  disponível no telefone que tenha esse acesso

> **Sobre o acesso ao painel:** no meio da instalação você precisa gerar um
> código de 6 dígitos pra cada tablet, e esse código **vale 30 minutos**. Ou
> você faz a instalação com o painel aberto do seu lado, ou combina com quem
> tem o acesso de gerar o código na hora e te passar. Não adianta gerar os
> três códigos de manhã e instalar à tarde: eles expiram.

---

## Parte 1 — Preparar o tablet (só na primeira vez)

O Android **não deixa** ativar o modo quiosque se o tablet tiver qualquer
conta cadastrada, nem Google nem Samsung. Por isso o tablet precisa estar
zerado.

1. **Restaure o tablet de fábrica**
   Ajustes > Geral > Restaurar > Restaurar dados de fábrica

2. **Na configuração inicial, PULE o wi-fi**
   Toque em Ignorar e confirme Ignorar mesmo assim.
   Sem internet nessa hora, o tablet não pede conta Google nem Samsung, que é
   exatamente o que a gente quer. Recuse tudo que for opcional.

3. **Não coloque senha nem padrão de bloqueio.** Deixe deslizar.
   Se colocar senha, o tablet vai mostrar tela de bloqueio e o aluno não passa.

4. **No computador, instale o driver USB da Samsung** (só na primeira vez)
   O Windows às vezes não enxerga o tablet sem ele, e aí o instalador diz que
   não encontrou nenhum tablet. Baixe em:
   https://developer.samsung.com/android-usb-driver
   Instale e reinicie o computador se ele pedir.

5. **Ligue a Depuração USB**
    - Ajustes > Sobre o tablet > Informações de software
    - Toque **7 vezes** seguidas em Número da versão
    - Volte, entre em Opções do desenvolvedor e ligue **Depuração USB**

6. **Agora sim conecte o wi-fi da academia.**

---

## Parte 2 — Rodar a instalação

1. Conecte o tablet no computador pelo cabo USB.

2. Na pasta `tablet`, dê **duplo clique em `provisionar.bat`**.

3. Vai abrir uma janela preta com o passo a passo. Siga o que ela pedir.
   Se aparecer um aviso no tablet pedindo permissão para o computador, marque
   **Sempre permitir** e toque em Permitir.

4. Quando a janela pedir o **código de 6 dígitos**:
    - Abra o painel, entre em **Máquinas**
    - Marque a máquina onde este tablet vai ficar
    - Em Ação, escolha **Gerar código de pareamento para o tablet** e clique em Ir
    - O código aparece na tela, com validade de 30 minutos
    - Digite os 6 números no tablet e toque em OK

5. O tablet deve mostrar o nome da máquina e pedir ID e PIN do aluno.

Se a janela parar com uma mensagem em vermelho, ela diz o que resolver. Nada
fica quebrado no tablet: resolva e rode de novo.

---

## Parte 3 — Conferir

- Reinicie o tablet. Ele tem que voltar sozinho no aplicativo, sem ninguém tocar.
- Aperte o botão home e o de recentes. Nada pode acontecer.
- Arraste o dedo de cima para baixo. A barra de notificações não pode abrir.

Se os três estiverem assim, o tablet está pronto.

---

## Parte 4 — Montagem

- Deixe o **carregador sempre ligado**. O tablet é feito para ficar na tomada.
- Monte o suporte **cobrindo os botões de liga/desliga e de volume**.
  Segurando esses botões juntos é possível chegar no menu de recuperação do
  Samsung e apagar o tablet. Nenhum ajuste de software impede isso, só o
  suporte físico.

---

## Se precisar mexer no tablet depois

Para trocar o wi-fi, atualizar o app ou qualquer manutenção:

1. Toque **7 vezes no canto superior esquerdo** da tela
2. Digite o **código de manutenção** da academia (está no painel, em Academias)
3. Toque em **Liberar tablet**

O tablet volta ao normal, com botão home e barra de status. Quando terminar,
**toque sempre em Travar de novo** e confirme que o tablet voltou pra tela de
login do aplicativo.

> **Não deixe o tablet destravado.** Ele não se tranca sozinho enquanto
> estiver fora do aplicativo. Se você sair do app e for embora, o tablet fica
> aberto pros alunos mexerem no Android. Se isso acontecer, é só abrir o
> aplicativo de novo pela lista de aplicativos: ele volta a travar sozinho.

O código de manutenção funciona mesmo sem internet, desde que o tablet já
tenha sido pareado ao menos uma vez. Num tablet recém-configurado, que ainda
não pareou com nenhuma máquina, o código ainda não está no aparelho: por isso
o pareamento é feito com o cabo conectado, ainda com você por perto.

---

## Deu problema?

Tire um print da tela e mande para o João, com o número da máquina.
Nenhum passo aqui apaga dado de aluno: os treinos ficam no servidor.
