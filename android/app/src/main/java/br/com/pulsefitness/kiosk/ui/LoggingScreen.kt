package br.com.pulsefitness.kiosk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.pulsefitness.kiosk.KioskViewModel
import br.com.pulsefitness.kiosk.data.ExerciseDto
import br.com.pulsefitness.kiosk.data.SetEntry
import kotlinx.coroutines.delay

/**
 * The core kiosk moment: student does a set, taps +/- to adjust load and
 * reps (no soft keyboard), finishes the machine, taps save, walks away.
 * Weight steps 2.5 kg (weight-stack pattern), reps step 1.
 */
@Composable
fun LoggingScreen(
    viewModel: KioskViewModel,
    exercise: ExerciseDto,
    onFinished: () -> Unit,
) {
    val sets = remember { mutableListOf(EditableSet()).toMutableStateList() }
    var saved by remember { mutableStateOf(false) }

    if (saved) {
        SavedConfirmation(onFinished)
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(exercise.name, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(sets, key = { _, set -> set.id }) { index, set ->
                SetRow(
                    index = index,
                    set = set,
                    onRemove = if (sets.size > 1) ({ sets.remove(set) }) else null,
                )
            }
            item {
                TextButton(onClick = {
                    val last = sets.last()
                    sets.add(EditableSet(weightKg = last.weightKg, reps = last.reps))
                }) {
                    Text("+ adicionar série", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                viewModel.saveWorkout(
                    exercise,
                    sets.map { SetEntry(weightKg = it.weightKg, reps = it.reps) },
                ) { saved = true }
            },
            modifier = Modifier.width(360.dp).height(64.dp),
        ) {
            Text("Salvar treino", style = MaterialTheme.typography.titleLarge)
        }
    }
}

class EditableSet(var weightKg: Double = 10.0, var reps: Int = 10) {
    val id: String = java.util.UUID.randomUUID().toString()
}

@Composable
private fun SetRow(index: Int, set: EditableSet, onRemove: (() -> Unit)?) {
    var weight by remember { mutableStateOf(set.weightKg) }
    var reps by remember { mutableStateOf(set.reps) }
    set.weightKg = weight
    set.reps = reps

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("Série ${index + 1}", style = MaterialTheme.typography.titleLarge)
        Stepper(
            label = "Carga (kg)",
            value = if (weight % 1.0 == 0.0) weight.toInt().toString() else weight.toString(),
            onMinus = { if (weight >= 2.5) weight -= 2.5 },
            onPlus = { if (weight < 500) weight += 2.5 },
        )
        Stepper(
            label = "Repetições",
            value = reps.toString(),
            onMinus = { if (reps > 1) reps -= 1 },
            onPlus = { if (reps < 100) reps += 1 },
        )
        if (onRemove != null) {
            TextButton(onClick = onRemove) { Text("remover") }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMinus) { Text("−", style = MaterialTheme.typography.titleLarge) }
            Text(value, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.width(88.dp), maxLines = 1)
            OutlinedButton(onClick = onPlus) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
    }
}

@Composable
private fun SavedConfirmation(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2500)
        onFinished()
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Treino salvo!", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Seu registro vai pro seu Hevy automaticamente. Bom treino!",
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
