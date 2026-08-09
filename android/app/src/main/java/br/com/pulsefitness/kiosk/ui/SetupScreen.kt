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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.pulsefitness.kiosk.KioskViewModel

/**
 * One-time provisioning, done by us during install (never by students):
 * paste the machine's device token printed by `manage.py seed_pilot` /
 * shown in the admin. Token is validated against the backend, then the
 * tablet is permanently bound to its machine.
 */
@Composable
fun SetupScreen(viewModel: KioskViewModel) {
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
        Text(
            "Cole o token da máquina (painel admin) para vincular este tablet.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Device token") },
            singleLine = true,
            modifier = Modifier.width(480.dp),
        )
        Spacer(Modifier.height(24.dp))
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
                        // On success boot state flips to Ready and nav leaves this screen.
                    }
                },
                enabled = token.isNotBlank(),
            ) {
                Text("Vincular tablet")
            }
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
