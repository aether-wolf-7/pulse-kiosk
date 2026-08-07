package br.com.pulsefitness.kiosk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import br.com.pulsefitness.kiosk.ui.LoginScreen
import br.com.pulsefitness.kiosk.ui.PlaceholderScreen

object Routes {
    const val LOGIN = "login"
    const val HEVY_LINK = "hevy_link"      // Stage 1: first-access key linking
    const val EXERCISE_PICK = "exercise_pick" // Stage 2: multifunctional machines
    const val LOGGING = "logging"          // Stage 2: sets/reps/load
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stage 3: startLockTask() here when running as Device Owner.
        setContent {
            MaterialTheme {
                KioskNavHost()
            }
        }
    }
}

@Composable
fun KioskNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = { hevyLinked ->
                    navController.navigate(if (hevyLinked) Routes.LOGGING else Routes.HEVY_LINK)
                }
            )
        }
        composable(Routes.HEVY_LINK) { PlaceholderScreen("Vincular conta Hevy (primeiro acesso)") }
        composable(Routes.EXERCISE_PICK) { PlaceholderScreen("Escolha do exercício (Etapa 2)") }
        composable(Routes.LOGGING) { PlaceholderScreen("Registro de séries (Etapa 2)") }
    }
}
