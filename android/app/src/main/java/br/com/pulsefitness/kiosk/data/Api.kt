package br.com.pulsefitness.kiosk.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

// DTOs mirror the Django backend (/api/v1). Field names match the JSON.

@Serializable
data class MachineConfigResponse(
    val academia: AcademiaDto,
    val machine: MachineDto,
    val exercises: List<ExerciseDto>,
)

@Serializable
data class AcademiaDto(val slug: String, val name: String)

@Serializable
data class MachineDto(
    val id: Long,
    val number: Int,
    val name: String,
    @SerialName("is_multifunctional") val isMultifunctional: Boolean,
)

@Serializable
data class ExerciseDto(
    val id: Long,
    val name: String,
    @SerialName("hevy_exercise_template_id") val hevyExerciseTemplateId: String,
)

@Serializable
data class LoginRequest(
    @SerialName("student_id") val studentId: String,
    val pin: String,
)

@Serializable
data class LoginResponse(
    @SerialName("session_token") val sessionToken: String,
    @SerialName("expires_at") val expiresAt: String,
    val student: StudentDto,
)

@Serializable
data class StudentDto(
    val name: String,
    @SerialName("hevy_linked") val hevyLinked: Boolean,
)

@Serializable
data class HevyLinkRequest(@SerialName("hevy_api_key") val hevyApiKey: String)

@Serializable
data class SetEntry(@SerialName("weight_kg") val weightKg: Double, val reps: Int)

@Serializable
data class WorkoutSubmitRequest(
    @SerialName("client_uuid") val clientUuid: String,
    @SerialName("exercise_id") val exerciseId: Long,
    val sets: List<SetEntry>,
    @SerialName("logged_at") val loggedAt: String,
)

@Serializable
data class WorkoutSubmitResponse(
    @SerialName("client_uuid") val clientUuid: String,
    val status: String,
    @SerialName("hevy_workout_id") val hevyWorkoutId: String,
    val duplicate: Boolean,
)

interface KioskApi {
    @GET("machine/config/")
    suspend fun machineConfig(@Header("X-Device-Token") deviceToken: String): MachineConfigResponse

    @POST("auth/login/")
    suspend fun login(
        @Header("X-Device-Token") deviceToken: String,
        @Body body: LoginRequest,
    ): LoginResponse

    @POST("auth/logout/")
    suspend fun logout(@Header("X-Session-Token") sessionToken: String)

    @POST("hevy/link/")
    suspend fun linkHevy(
        @Header("X-Session-Token") sessionToken: String,
        @Body body: HevyLinkRequest,
    )

    @POST("workouts/")
    suspend fun submitWorkout(
        @Header("X-Session-Token") sessionToken: String,
        @Body body: WorkoutSubmitRequest,
    ): WorkoutSubmitResponse
}
