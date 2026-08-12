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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * First access only: the student links their Hevy account by typing the
 * API key from the Hevy app (Settings > Developer). The key is validated
 * live and stored encrypted on the server; from then on ID + PIN is enough
 * on any tablet.
 */
@Composable
fun HevyLinkScreen(
    viewModel: KioskViewModel,
    onLinked: () -> Unit,
    onCancel: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Vincular conta Hevy", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Primeiro acesso: cole sua API key do Hevy (app do Hevy, Configurações, API). Só precisa fazer isso uma vez.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = apiKey,
            // Drop the previous failure as soon as they start correcting it,
            // instead of leaving "inválida" under a key they just retyped.
            onValueChange = { apiKey = it; error = null },
            label = { Text("API key do Hevy") },
            singleLine = true,
            modifier = Modifier.width(480.dp),
        )
        Spacer(Modifier.height(24.dp))
        if (loading) {
            CircularProgressIndicator()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = {
                    viewModel.endSession()
                    onCancel()
                }) {
                    Text("Cancelar")
                }
                Button(
                    onClick = {
                        loading = true
                        error = null
                        viewModel.linkHevy(apiKey.trim()) { err ->
                            loading = false
                            if (err == null) onLinked() else error = err
                        }
                    },
                    enabled = apiKey.isNotBlank(),
                ) {
                    Text("Vincular")
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
