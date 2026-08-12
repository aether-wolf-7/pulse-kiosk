package br.com.pulsefitness.kiosk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.pulsefitness.kiosk.KioskViewModel
import br.com.pulsefitness.kiosk.data.MachineConfigResponse
import kotlinx.coroutines.delay

/** Idle time after which a half-typed login is wiped from the screen. */
private const val LOGIN_IDLE_RESET_MS = 30_000L

/**
 * Two-step ATM-style login on one keypad: type the student ID, OK,
 * type the PIN, OK. Field being filled is highlighted.
 */
@Composable
fun LoginScreen(
    viewModel: KioskViewModel,
    config: MachineConfigResponse,
    onLoggedIn: (hevyLinked: Boolean) -> Unit,
) {
    var studentId by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var typingPin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun reset() {
        studentId = ""
        pin = ""
        typingPin = false
        error = null
    }

    // A half-finished login is as sensitive as a live session: a student who
    // types their PIN and walks off before pressing OK would otherwise leave
    // it on screen for the next person to submit. Wipe it after a short idle.
    LaunchedEffect(studentId, pin, typingPin) {
        if (studentId.isEmpty() && pin.isEmpty()) return@LaunchedEffect
        delay(LOGIN_IDLE_RESET_MS)
        reset()
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(config.machine.name, style = MaterialTheme.typography.headlineLarge)
            Text(config.academia.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(48.dp))

            ValueBox(label = "ID do aluno", value = studentId, masked = false, active = !typingPin)
            Spacer(Modifier.height(16.dp))
            ValueBox(label = "PIN", value = pin, masked = true, active = typingPin)

            error?.let {
                Spacer(Modifier.height(24.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
            }
        }

        if (loading) {
            CircularProgressIndicator()
        } else {
            NumericKeypad(
                onDigit = { d ->
                    error = null
                    if (typingPin) { if (pin.length < 8) pin += d }
                    else { if (studentId.length < 10) studentId += d }
                },
                onBackspace = {
                    if (typingPin) {
                        if (pin.isEmpty()) typingPin = false else pin = pin.dropLast(1)
                    } else {
                        studentId = studentId.dropLast(1)
                    }
                },
                onConfirm = {
                    if (!typingPin) {
                        typingPin = true
                    } else {
                        loading = true
                        viewModel.login(studentId, pin) { err ->
                            loading = false
                            if (err == null) {
                                val linked = viewModel.session.value?.student?.hevyLinked == true
                                reset()
                                onLoggedIn(linked)
                            } else {
                                error = err
                                pin = ""
                            }
                        }
                    }
                },
                confirmEnabled = if (typingPin) pin.isNotEmpty() else studentId.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun ValueBox(label: String, value: String, masked: Boolean, active: Boolean) {
    val display = if (masked) "•".repeat(value.length) else value
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(280.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (display.isEmpty()) " " else display,
            style = MaterialTheme.typography.displaySmall,
        )
    }
}
