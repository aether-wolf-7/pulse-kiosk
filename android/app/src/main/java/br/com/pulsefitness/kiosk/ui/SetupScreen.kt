package br.com.pulsefitness.kiosk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pulsefitness.kiosk.KioskViewModel

private const val PAIRING_CODE_LENGTH = 6

/**
 * One-time provisioning, done during install.
 *
 * The normal path is a six digit code generated in the admin. The raw device
 * token is still accepted, behind a link, because it is the fallback when
 * support needs to bind a tablet without reaching the admin panel.
 */
@Composable
fun SetupScreen(viewModel: KioskViewModel) {
    var useToken by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Configuração do tablet", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))

        if (!useToken) {
            Text(
                "Digite o código de 6 dígitos gerado no painel administrativo, " +
                    "em Máquinas.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                if (code.isEmpty()) "------" else code.padEnd(PAIRING_CODE_LENGTH, '-'),
                style = MaterialTheme.typography.displayMedium,
                fontSize = 44.sp,
            )
            Spacer(Modifier.height(24.dp))

            if (loading) {
                CircularProgressIndicator()
            } else {
                NumericKeypad(
                    onDigit = { d ->
                        if (code.length < PAIRING_CODE_LENGTH) {
                            code += d
                            error = null
                        }
                    },
                    onBackspace = { code = code.dropLast(1) },
                    onConfirm = {
                        loading = true
                        error = null
                        viewModel.pairWithCode(code) { ok, message ->
                            loading = false
                            if (!ok) {
                                error = message
                                code = ""
                            }
                        }
                    },
                    confirmEnabled = code.length == PAIRING_CODE_LENGTH,
                )
            }
        } else {
            Text(
                "Cole o token da máquina (painel admin) para vincular este tablet.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; error = null },
                label = { Text("Device token") },
                singleLine = true,
                modifier = Modifier.width(560.dp),
            )
            Spacer(Modifier.height(20.dp))
            if (loading) {
                CircularProgressIndicator()
            } else {
                Button(
                    onClick = {
                        loading = true
                        error = null
                        viewModel.provision(token) { ok, message ->
                            loading = false
                            if (!ok) error = message
                        }
                    },
                    enabled = token.isNotBlank(),
                ) {
                    Text("Vincular tablet")
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(28.dp))
        TextButton(onClick = {
            useToken = !useToken
            error = null
            code = ""
            token = ""
        }) {
            Text(
                if (useToken) "Usar código de 6 dígitos"
                else "Não tenho o código, usar o token completo",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
