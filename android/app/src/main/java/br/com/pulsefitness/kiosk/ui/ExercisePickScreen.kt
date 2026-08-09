package br.com.pulsefitness.kiosk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.pulsefitness.kiosk.data.ExerciseDto

/** Multifunctional machines only: student picks the exercise first. */
@Composable
fun ExercisePickScreen(
    exercises: List<ExerciseDto>,
    onPick: (ExerciseDto) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Qual exercício?", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(48.dp))
        exercises.forEach { exercise ->
            Button(
                onClick = { onPick(exercise) },
                modifier = Modifier.width(400.dp).padding(vertical = 12.dp).height(72.dp),
            ) {
                Text(exercise.name, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
