package br.com.pulsefitness.kiosk

import android.app.Application
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.time.OffsetDateTime
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

    init {
        loadConfig()
    }

    fun loadConfig() {
        viewModelScope.launch {
            _boot.value = BootState.Loading
            val token = store.deviceToken.first()
            if (token.isNullOrBlank()) {
                _boot.value = BootState.NeedsProvisioning
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

    private suspend fun fetchConfig(token: String): BootState = try {
        BootState.Ready(ApiClient.api.machineConfig(token))
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
                _session.value = ApiClient.api.login(deviceToken, LoginRequest(studentId, pin))
                onResult(null)
            } catch (e: HttpException) {
                onResult(if (e.code() == 401) "ID ou PIN incorretos" else "Erro no servidor (${e.code()})")
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
        viewModelScope.launch {
            try {
                ApiClient.api.logout(current.sessionToken)
            } catch (_: Exception) {
                // Session also expires server-side by TTL; nothing to do.
            }
        }
    }
}
