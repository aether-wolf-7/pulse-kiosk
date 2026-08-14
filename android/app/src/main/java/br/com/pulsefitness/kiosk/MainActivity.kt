package br.com.pulsefitness.kiosk

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import br.com.pulsefitness.kiosk.data.AcademiaDto
import br.com.pulsefitness.kiosk.data.MachineConfigResponse
import br.com.pulsefitness.kiosk.data.MachineDto
import br.com.pulsefitness.kiosk.kiosk.KioskManager
import br.com.pulsefitness.kiosk.ui.AdminScreen
import br.com.pulsefitness.kiosk.ui.ExercisePickScreen
import br.com.pulsefitness.kiosk.ui.HevyLinkScreen
import br.com.pulsefitness.kiosk.ui.LoggingScreen
import br.com.pulsefitness.kiosk.ui.LoginScreen
import br.com.pulsefitness.kiosk.ui.SetupScreen

/** Taps in the corner hotspot that open the staff maintenance screen. */
private const val ADMIN_GESTURE_TAPS = 7
private const val ADMIN_TAP_WINDOW_MS = 3_000L

/** How often the foreground app re-checks that it is still pinned. */
private const val KIOSK_RECHECK_MS = 10_000L

/** Compose gives us a Context; the kiosk APIs need the Activity behind it. */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

object Routes {
    const val LOGIN = "login"
    const val HEVY_LINK = "hevy_link"       // Stage 1: first-access key linking
    const val EXERCISE_PICK = "exercise_pick" // multifunctional machines
    const val LOGGING = "logging"           // Stage 2: sets/reps/load
}

class MainActivity : ComponentActivity() {

    /** Whether staff have unlocked the tablet for maintenance. Persisted in
     *  KioskManager rather than held here, because unlocking restarts this
     *  Activity and an in-memory flag would be lost exactly when it matters. */
    private val inMaintenance: Boolean
        get() = KioskManager.isMaintenanceActive(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KioskManager.applyPolicy(this)
        // Wall-powered terminal: the screen should never sleep on a student
        // mid-set, and the tablet should never show a lock screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        hideSystemBars()

        setContent {
            MaterialTheme {
                KioskRoot(
                    onEnterMaintenance = { KioskManager.beginMaintenance(this) },
                    onLeaveMaintenance = {
                        KioskManager.endMaintenance(this)
                        KioskManager.applyPolicy(this)
                        KioskManager.enterKiosk(this)
                        hideSystemBars()
                    },
                )
            }
        }
    }

    /**
     * Re-asserts the lock while the app is in the foreground.
     *
     * onResume alone is not enough. If lock task is ever dropped while the
     * app stays resumed, nothing else would notice and the tablet would sit
     * unlocked in the gym until someone happened to restart it. Observed
     * exactly once, after an emulator snapshot restore, which is not a real
     * scenario but proves the hole is reachable.
     */
    private val kioskWatchdog = Handler(Looper.getMainLooper())
    private val reassertLock = object : Runnable {
        override fun run() {
            if (!inMaintenance) KioskManager.enterKiosk(this@MainActivity)
            kioskWatchdog.postDelayed(this, KIOSK_RECHECK_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-pin on every resume: if anything ever manages to break out (a
        // system dialog, an OTA prompt), the tablet locks itself back down
        // without anyone visiting the gym.
        if (!inMaintenance) {
            KioskManager.enterKiosk(this)
            hideSystemBars()
        }
        kioskWatchdog.removeCallbacks(reassertLock)
        kioskWatchdog.postDelayed(reassertLock, KIOSK_RECHECK_MS)
    }

    override fun onPause() {
        super.onPause()
        kioskWatchdog.removeCallbacks(reassertLock)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !inMaintenance) {
            KioskManager.enterKiosk(this)
            hideSystemBars()
        }
    }

    /** The back gesture must not leave the kiosk; navigation is handled
     *  entirely by the app's own flow. */
    @Deprecated("Kiosk intentionally swallows system back")
    override fun onBackPressed() {
        if (inMaintenance) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
        // Otherwise: ignored on purpose.
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
fun KioskRoot(
    viewModel: KioskViewModel = viewModel(),
    onEnterMaintenance: () -> Unit = {},
    onLeaveMaintenance: () -> Unit = {},
) {
    val boot by viewModel.boot.collectAsState()
    var showAdmin by remember { mutableStateOf(false) }

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
        if (showAdmin) {
            AdminGate(viewModel, boot, onClose = { showAdmin = false },
                onEnterMaintenance = onEnterMaintenance,
                onLeaveMaintenance = onLeaveMaintenance)
        } else {
            BootContent(viewModel, boot, onEnterMaintenance, onLeaveMaintenance)
            // Staff hotspot, deliberately invisible and deliberately at the
            // root: maintenance is needed most when the app is stuck on
            // "sem conexão" or waiting to be provisioned, and on a pinned
            // tablet those screens have no other way out.
            AdminHotspot(onTriggered = { showAdmin = true })
        }
    }
}

/** Seven taps in the top-left corner, within a few seconds of each other. */
@Composable
private fun BoxScope.AdminHotspot(onTriggered: () -> Unit) {
    var taps by remember { mutableStateOf(0) }
    var lastTapMs by remember { mutableStateOf(0L) }
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .size(72.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) {
                val now = SystemClock.elapsedRealtime()
                taps = if (now - lastTapMs > ADMIN_TAP_WINDOW_MS) 1 else taps + 1
                lastTapMs = now
                if (taps >= ADMIN_GESTURE_TAPS) {
                    taps = 0
                    onTriggered()
                }
            }
    )
}

/** The maintenance screen needs a config to show; fall back to a stub so it
 *  still opens on an unprovisioned or offline tablet. */
@Composable
private fun AdminGate(
    viewModel: KioskViewModel,
    boot: KioskViewModel.BootState,
    onClose: () -> Unit,
    onEnterMaintenance: () -> Unit,
    onLeaveMaintenance: () -> Unit,
) {
    val activity = LocalContext.current.findActivity()
    val config = (boot as? KioskViewModel.BootState.Ready)?.config ?: MachineConfigResponse(
        academia = AcademiaDto(slug = "", name = "(sem conexão)"),
        machine = MachineDto(id = 0, number = 0, name = "(tablet não configurado)", isMultifunctional = false),
        exercises = emptyList(),
    )
    var locked by remember { mutableStateOf(activity?.let { KioskManager.isLocked(it) } ?: false) }
    var reprovisionError by remember { mutableStateOf<String?>(null) }
    val pending by produceState(0) { value = viewModel.pendingQueueCount() }

    AdminScreen(
        viewModel = viewModel,
        config = config,
        pendingCount = pending,
        isDeviceOwner = activity?.let { KioskManager.isDeviceOwner(it) } ?: false,
        isLocked = locked,
        onUnlock = {
            activity?.let {
                onEnterMaintenance()
                KioskManager.exitKiosk(it)
                locked = KioskManager.isLocked(it)
            }
        },
        onRelock = {
            activity?.let {
                onLeaveMaintenance()
                locked = KioskManager.isLocked(it)
            }
        },
        onReleaseDeviceOwner = {
            activity?.let {
                onEnterMaintenance()
                KioskManager.releaseDeviceOwner(it)
                locked = KioskManager.isLocked(it)
            }
        },
        onReprovision = {
            viewModel.reprovision { error ->
                if (error == null) onClose() else reprovisionError = error
            }
        },
        reprovisionError = reprovisionError,
        onClose = {
            activity?.let { if (!KioskManager.isLocked(it)) onLeaveMaintenance() }
            onClose()
        },
    )
}

@Composable
private fun BootContent(
    viewModel: KioskViewModel,
    boot: KioskViewModel.BootState,
    onEnterMaintenance: () -> Unit,
    onLeaveMaintenance: () -> Unit,
) {
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
        is KioskViewModel.BootState.Ready ->
            KioskNavHost(viewModel, state.config, onEnterMaintenance, onLeaveMaintenance)
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
fun KioskNavHost(
    viewModel: KioskViewModel,
    config: MachineConfigResponse,
    onEnterMaintenance: () -> Unit = {},
    onLeaveMaintenance: () -> Unit = {},
) {
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
