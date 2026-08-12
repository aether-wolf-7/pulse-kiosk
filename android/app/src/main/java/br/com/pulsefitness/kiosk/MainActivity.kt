package br.com.pulsefitness.kiosk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import br.com.pulsefitness.kiosk.data.MachineConfigResponse
import br.com.pulsefitness.kiosk.ui.ExercisePickScreen
import br.com.pulsefitness.kiosk.ui.HevyLinkScreen
import br.com.pulsefitness.kiosk.ui.LoggingScreen
import br.com.pulsefitness.kiosk.ui.LoginScreen
import br.com.pulsefitness.kiosk.ui.SetupScreen

object Routes {
    const val LOGIN = "login"
    const val HEVY_LINK = "hevy_link"       // Stage 1: first-access key linking
    const val EXERCISE_PICK = "exercise_pick" // multifunctional machines
    const val LOGGING = "logging"           // Stage 2: sets/reps/load
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stage 3: startLockTask() here when running as Device Owner.
        setContent {
            MaterialTheme {
                KioskRoot()
            }
        }
    }
}

@Composable
fun KioskRoot(viewModel: KioskViewModel = viewModel()) {
    val boot by viewModel.boot.collectAsState()
    // Every touch anywhere postpones the idle logout. Observed in the
    // Initial pass so it never steals input from the widget underneath.
    Box(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    viewModel.touch()
                }
            }
        }
    ) {
        BootContent(viewModel, boot)
    }
}

@Composable
private fun BootContent(viewModel: KioskViewModel, boot: KioskViewModel.BootState) {
    when (val state = boot) {
        is KioskViewModel.BootState.Loading -> Centered { CircularProgressIndicator() }
        is KioskViewModel.BootState.NeedsProvisioning -> SetupScreen(viewModel)
        is KioskViewModel.BootState.Error -> Centered {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(state.message, style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { viewModel.loadConfig() }) { Text("Tentar de novo") }
            }
        }
        is KioskViewModel.BootState.Ready -> KioskNavHost(viewModel, state.config)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun KioskNavHost(viewModel: KioskViewModel, config: MachineConfigResponse) {
    val navController = rememberNavController()
    val timedOut by viewModel.timedOut.collectAsState()

    // Idle or expired session: drop everything and show the login screen so
    // the next student never lands inside someone else's session.
    LaunchedEffect(timedOut) {
        if (timedOut) {
            navController.popBackStack(Routes.LOGIN, inclusive = false)
            viewModel.clearTimedOut()
        }
    }

    fun afterAuth(navController: NavHostController) {
        val next = if (config.machine.isMultifunctional) Routes.EXERCISE_PICK else Routes.LOGGING
        navController.navigate(next) { popUpTo(Routes.LOGIN) }
    }

    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(viewModel, config) { hevyLinked ->
                if (hevyLinked) afterAuth(navController)
                else navController.navigate(Routes.HEVY_LINK)
            }
        }
        composable(Routes.HEVY_LINK) {
            HevyLinkScreen(
                viewModel,
                onLinked = { afterAuth(navController) },
                onCancel = { navController.popBackStack(Routes.LOGIN, inclusive = false) },
            )
        }
        composable(Routes.EXERCISE_PICK) {
            ExercisePickScreen(config.exercises) { exercise ->
                viewModel.selectExercise(exercise)
                navController.navigate(Routes.LOGGING)
            }
        }
        composable(Routes.LOGGING) {
            val exercise = viewModel.selectedExercise.value ?: config.exercises.first()
            LoggingScreen(
                viewModel,
                exercise,
                onFinished = { navController.popBackStack(Routes.LOGIN, inclusive = false) },
            )
        }
    }
}
