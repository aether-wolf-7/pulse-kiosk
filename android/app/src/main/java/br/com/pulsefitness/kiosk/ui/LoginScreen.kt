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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * ID + PIN login. Kiosk UX target: student walks up, types two short
 * numbers, starts logging. TODO Stage 1 polish: big numeric keypad
 * instead of text fields, auto-clear on inactivity.
 */
@Composable
fun LoginScreen(onLoggedIn: (hevyLinked: Boolean) -> Unit) {
    var studentId by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pulse Kiosk", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = studentId,
            onValueChange = { studentId = it.filter(Char::isDigit) },
            label = { Text("ID do aluno") },
            singleLine = true,
            modifier = Modifier.width(320.dp),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit) },
            label = { Text("PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(320.dp),
        )
        Spacer(Modifier.height(24.dp))
        if (loading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    scope.launch {
                        loading = true
                        error = null
                        try {
                            // TODO Stage 1: wire DeviceConfigStore token + session holder.
                            // val resp = ApiClient.api.login(deviceToken, LoginRequest(studentId, pin))
                            // onLoggedIn(resp.student.hevyLinked)
                            error = "Backend ainda não conectado (esqueleto)"
                        } catch (e: Exception) {
                            error = "ID ou PIN incorretos"
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = studentId.isNotEmpty() && pin.isNotEmpty(),
            ) {
                Text("Entrar")
            }
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
