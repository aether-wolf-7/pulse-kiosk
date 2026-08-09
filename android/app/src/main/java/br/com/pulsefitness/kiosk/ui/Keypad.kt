package br.com.pulsefitness.kiosk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Big touch-friendly numeric keypad, ATM style: the whole login should
 * take a sweaty-handed student a few seconds, no soft keyboard involved.
 */
@Composable
fun NumericKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", "OK"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { label ->
                    OutlinedButton(
                        onClick = {
                            when (label) {
                                "⌫" -> onBackspace()
                                "OK" -> onConfirm()
                                else -> onDigit(label.first())
                            }
                        },
                        enabled = label != "OK" || confirmEnabled,
                        modifier = Modifier.size(88.dp),
                        shape = ButtonDefaults.outlinedShape,
                    ) {
                        Text(label, fontSize = 28.sp)
                    }
                }
            }
        }
    }
}
