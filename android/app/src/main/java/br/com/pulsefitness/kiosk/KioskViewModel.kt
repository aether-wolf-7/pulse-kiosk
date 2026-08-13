package br.com.pulsefitness.kiosk

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.pulsefitness.kiosk.data.ApiClient
import br.com.pulsefitness.kiosk.data.DeviceConfigStore
import br.com.pulsefitness.kiosk.data.ExerciseDto
import br.com.pulsefitness.kiosk.data.HevyLinkRequest
import br.com.pulsefitness.kiosk.data.LoginRequest
import br.com.pulsefitness.kiosk.data.LoginResponse
import br.com.pulsefitness.kiosk.data.MachineConfigResponse
import br.com.pulsefitness.kiosk.data.SetEntry
import br.com.pulsefitness.kiosk.data.db.KioskDatabase
import br.com.pulsefitness.kiosk.data.db.PendingWorkout
import br.com.pulsefitness.kiosk.sync.SyncWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Single source of truth for kiosk state: device provisioning, machine
 * config and the current (short-lived) student session.
 */
class KioskViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface BootState {
        data object Loading : BootState
        data object NeedsProvisioning : BootState
        data class Ready(val config: MachineConfigResponse) : BootState
        data class Error(val message: String) : BootState
    }

    private val store = DeviceConfigStore(app)

    private val _boot = MutableStateFlow<BootState>(BootState.Loading)
    val boot: StateFlow<BootState> = _boot

    private val _session = MutableStateFlow<LoginResponse?>(null)
    val session: StateFlow<LoginResponse?> = _session

    /** Set when a session ends by itself (idle or server expiry) rather than
     *  by the student saving, so the UI can bounce back to the login screen. */
    private val _timedOut = MutableStateFlow(false)
    val timedOut: StateFlow<Boolean> = _timedOut

    private var lastInteractionMs = 0L
    private var sessionExpiresAtMs = Long.MAX_VALUE
    private var watchdog: Job? = null

    init {
        loadConfig()
    }

    /** Any touch anywhere in the app postpones the idle logout. */
    fun touch() {
        lastInteractionMs = SystemClock.elapsedRealtime()
    }

    fun clearTimedOut() {
        _timedOut.value = false
    }

    /**
     * A tablet bolted to a machine is never "left logged in" safely: a
     * student who walks off mid-set would otherwise hand their Hevy account
     * to whoever sits down next. Log out on idle, and never outlive the
     * session the server issued.
     */
    private fun startWatchdog(expiresAt: String) {
        sessionExpiresAtMs = parseExpiry(expiresAt)
        touch()
        watchdog?.cancel()
        watchdog = viewModelScope.launch {
            while (isActive && _session.value != null) {
                delay(WATCHDOG_TICK_MS)
                val idleFor = SystemClock.elapsedRealtime() - lastInteractionMs
                val serverExpired = System.currentTimeMillis() >= sessionExpiresAtMs
                if (idleFor >= IDLE_TIMEOUT_MS || serverExpired) {
                    endSession()
                    _timedOut.value = true
                    return@launch
                }
            }
        }
    }

    private fun parseExpiry(raw: String): Long = try {
        Instant.parse(raw).toEpochMilli()
    } catch (e: DateTimeParseException) {
        try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (e2: DateTimeParseException) {
            Long.MAX_VALUE // server format changed; idle timeout still applies
        }
    }

    /**
     * Boots from the cached config first, then refreshes in the background.
     *
     * Once this app is the launcher and pinned, whatever is on screen at boot
     * IS the tablet: there is no Home, no Settings, no browser. A gym whose
     * access point is down on Monday morning must still get working tablets,
     * with the offline queue absorbing the workouts, so a network failure may
     * never be the difference between "usable" and "brick".
     */
    fun loadConfig() {
        viewModelScope.launch {
            _boot.value = BootState.Loading
            val token = store.deviceToken.first()
            if (token.isNullOrBlank()) {
                _boot.value = BootState.NeedsProvisioning
                return@launch
            }

            val cached = store.cachedConfig.first()?.let { json ->
                runCatching { configJson.decodeFromString<MachineConfigResponse>(json) }.getOrNull()
            }
            if (cached != null) {
                _boot.value = BootState.Ready(cached)
                // Refresh quietly; only replace what is on screen on success,
                // so a failed refresh never demotes a working tablet.
                val fresh = fetchConfig(token)
                if (fresh is BootState.Ready) _boot.value = fresh
                return@launch
            }

            _boot.value = fetchConfig(token)
        }
    }

    /** Provisioning: validates the pasted token against the backend before saving. */
    fun provision(token: String, onDone: (ok: Boolean, message: String?) -> Unit) {
        viewModelScope.launch {
            when (val result = fetchConfig(token.trim())) {
                is BootState.Ready -> {
                    store.setDeviceToken(token.trim())
                    _boot.value = result
                    onDone(true, null)
                }
                is BootState.Error -> onDone(false, result.message)
                else -> onDone(false, "Erro inesperado")
            }
        }
    }

    /** Workouts still waiting to reach the backend, shown to staff. */
    suspend fun pendingQueueCount(): Int =
        KioskDatabase.get(getApplication()).pendingWorkoutDao().all().size

    /**
     * Checks the staff maintenance code against the hash cached from the last
     * config fetch, so leaving kiosk mode works with no network.
     */
    suspend fun isAdminPin(candidate: String): Boolean {
        val expected = store.adminPinHash.first().orEmpty()
        if (expected.isBlank() || candidate.isBlank()) return false
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(candidate.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(digest.toByteArray(), expected.toByteArray())
    }

    private suspend fun fetchConfig(token: String): BootState = try {
        val config = ApiClient.api.machineConfig(token)
        if (config.academia.adminPinHash.isNotBlank()) {
            store.setAdminPinHash(config.academia.adminPinHash)
        }
        store.cacheConfig(configJson.encodeToString(config))
        BootState.Ready(config)
    } catch (e: HttpException) {
        if (e.code() == 401) BootState.Error("Tablet não registrado no servidor")
        else BootState.Error("Erro no servidor (${e.code()})")
    } catch (e: IOException) {
        BootState.Error("Sem conexão com o servidor")
    }

    fun login(studentId: String, pin: String, onResult: (error: String?) -> Unit) {
        viewModelScope.launch {
            val deviceToken = store.deviceToken.first() ?: run {
                onResult("Tablet não configurado")
                return@launch
            }
            try {
                val response = ApiClient.api.login(deviceToken, LoginRequest(studentId, pin))
                _session.value = response
                _timedOut.value = false
                startWatchdog(response.expiresAt)
                onResult(null)
            } catch (e: HttpException) {
                onResult(
                    when (e.code()) {
                        401 -> "ID ou PIN incorretos"
                        429 -> "Muitas tentativas. Procure a recepção."
                        else -> "Erro no servidor (${e.code()})"
                    }
                )
            } catch (e: IOException) {
                onResult("Sem conexão. Tente novamente.")
            }
        }
    }

    fun linkHevy(apiKey: String, onResult: (error: String?) -> Unit) {
        val current = _session.value ?: return onResult("Sessão expirada")
        viewModelScope.launch {
            try {
                ApiClient.api.linkHevy(current.sessionToken, HevyLinkRequest(apiKey))
                _session.value = current.copy(student = current.student.copy(hevyLinked = true))
                onResult(null)
            } catch (e: HttpException) {
                onResult(
                    when (e.code()) {
                        400 -> "API key inválida. Confira no app do Hevy."
                        401 -> "Sessão expirada, faça login de novo"
                        else -> "Hevy fora do ar, tente de novo em instantes"
                    }
                )
            } catch (e: IOException) {
                onResult("Sem conexão. Tente novamente.")
            }
        }
    }

    /** Multifunctional machines: exercise picked before logging. */
    val selectedExercise = MutableStateFlow<ExerciseDto?>(null)

    fun selectExercise(exercise: ExerciseDto) {
        selectedExercise.value = exercise
    }

    /**
     * Offline-first save: the visit goes to the local queue and the sync
     * worker drains it (immediately when online). The student is done the
     * moment this returns; the network never keeps them waiting.
     */
    fun saveWorkout(exercise: ExerciseDto, sets: List<SetEntry>, onDone: () -> Unit) {
        val current = _session.value ?: return
        viewModelScope.launch {
            KioskDatabase.get(getApplication()).pendingWorkoutDao().insert(
                PendingWorkout(
                    clientUuid = UUID.randomUUID().toString(),
                    sessionToken = current.sessionToken,
                    exerciseId = exercise.id,
                    setsJson = Json.encodeToString(sets),
                    loggedAt = OffsetDateTime.now().toString(),
                )
            )
            SyncWorker.enqueue(getApplication())
            endSession()
            onDone()
        }
    }

    /** Auto-logout: fire and forget server side, clear local state now. */
    fun endSession() {
        val current = _session.value ?: return
        _session.value = null
        selectedExercise.value = null
        watchdog?.cancel()
        watchdog = null
        sessionExpiresAtMs = Long.MAX_VALUE
        viewModelScope.launch {
            try {
                ApiClient.api.logout(current.sessionToken)
            } catch (_: Exception) {
                // Session also expires server-side by TTL; nothing to do.
            }
        }
    }

    companion object {
        /** No student takes this long between taps while at a machine. */
        private const val IDLE_TIMEOUT_MS = 90_000L
        private const val WATCHDOG_TICK_MS = 5_000L

        private val configJson = Json { ignoreUnknownKeys = true }
    }
}
