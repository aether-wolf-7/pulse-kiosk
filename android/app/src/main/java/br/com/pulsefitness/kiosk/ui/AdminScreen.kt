package br.com.pulsefitness.kiosk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.pulsefitness.kiosk.KioskViewModel
import br.com.pulsefitness.kiosk.data.MachineConfigResponse
import kotlinx.coroutines.launch

/**
 * Staff-only maintenance screen, reached by a hidden gesture on the login
 * screen and gated by the gym's maintenance code (checked offline).
 *
 * This exists so a locked tablet is never a dead end: someone has to be able
 * to reach wifi settings, install an update, or hand the tablet back to a
 * normal state without a factory reset.
 */
@Composable
fun AdminScreen(
    viewModel: KioskViewModel,
    config: MachineConfigResponse,
    pendingCount: Int,
    blockedCount: Int = 0,
    onRetryBlocked: () -> Unit = {},
    isDeviceOwner: Boolean,
    isLocked: Boolean,
    onUnlock: () -> Unit,
    onRelock: () -> Unit,
    onReleaseDeviceOwner: () -> Unit,
    onReprovision: () -> Unit,
    reprovisionError: String? = null,
    onClose: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var authenticated by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingRelease by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (!authenticated) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Manutenção", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("Digite o código de manutenção da academia.")
            Spacer(Modifier.height(24.dp))
            Text(
                if (pin.isEmpty()) " " else "•".repeat(pin.length),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(16.dp))
            NumericKeypad(
                onDigit = { d -> if (pin.length < 12) { pin += d; error = null } },
                onBackspace = { pin = pin.dropLast(1) },
                onConfirm = {
                    scope.launch {
                        if (viewModel.isAdminPin(pin)) {
                            authenticated = true
                        } else {
                            error = "Código incorreto"
                            pin = ""
                        }
                    }
                },
                confirmEnabled = pin.isNotEmpty(),
            )
            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onClose) { Text("Voltar") }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Manutenção", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        Text("Academia: ${config.academia.name}")
        Text("Máquina ${config.machine.number}: ${config.machine.name}")
        Text("Modo quiosque: ${if (isDeviceOwner) "ativo" else "não configurado"}")
        Text("Tela travada: ${if (isLocked) "sim" else "não"}")
        Text("Treinos aguardando envio: $pendingCount")
        if (blockedCount > 0) {
            // Parked because the server refused them. Kept rather than
            // dropped, but nothing retries them on its own, so they must be
            // visible or a student's sets vanish silently.
            Text(
                "Treinos parados (recusados pelo servidor): $blockedCount",
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetryBlocked, modifier = Modifier.width(360.dp)) {
                Text("Tentar enviar os treinos parados de novo")
            }
        }
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (isLocked) {
                Button(onClick = onUnlock, modifier = Modifier.width(240.dp)) {
                    Text("Liberar tablet")
                }
            } else {
                Button(onClick = onRelock, modifier = Modifier.width(240.dp)) {
                    Text("Travar de novo")
                }
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.width(240.dp)) {
                Text("Voltar pro login")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Liberar destrava a barra de status e o botão home pra manutenção. " +
                "Lembre de travar de novo antes de devolver o tablet pros alunos.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onReprovision, modifier = Modifier.width(360.dp)) {
            Text("Trocar este tablet de máquina")
        }
        Text(
            "Desvincula da máquina atual e volta pra tela de configuração.",
            style = MaterialTheme.typography.bodySmall,
        )
        reprovisionError?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }

        if (isDeviceOwner) {
            Spacer(Modifier.height(40.dp))
            if (!confirmingRelease) {
                OutlinedButton(onClick = { confirmingRelease = true }) {
                    Text("Remover modo quiosque deste tablet")
                }
            } else {
                Text(
                    "Isso devolve o tablet ao estado normal de fábrica de uso. " +
                        "Pra voltar a ser quiosque, o tablet precisa ser resetado e " +
                        "configurado de novo por cabo. Tem certeza?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { confirmingRelease = false }) { Text("Cancelar") }
                    Button(onClick = {
                        confirmingRelease = false
                        onReleaseDeviceOwner()
                    }) {
                        Text("Sim, remover")
                    }
                }
            }
        }
    }
}
