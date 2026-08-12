package br.com.pulsefitness.kiosk.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.com.pulsefitness.kiosk.data.ApiClient
import br.com.pulsefitness.kiosk.data.SetEntry
import br.com.pulsefitness.kiosk.data.WorkoutSubmitRequest
import br.com.pulsefitness.kiosk.data.db.KioskDatabase
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Drains the offline queue whenever there is network. Server is idempotent
 * by client_uuid, so resending after an ambiguous failure is always safe.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = KioskDatabase.get(applicationContext).pendingWorkoutDao()
        var transientFailure = false

        for (pending in dao.syncable()) {
            try {
                ApiClient.api.submitWorkout(
                    pending.sessionToken,
                    WorkoutSubmitRequest(
                        clientUuid = pending.clientUuid,
                        exerciseId = pending.exerciseId,
                        sets = Json.decodeFromString<List<SetEntry>>(pending.setsJson),
                        loggedAt = pending.loggedAt,
                    ),
                )
                // Accepted (or recognised as a duplicate) by the server, which
                // owns the Hevy push from here. Only now is it safe to drop.
                dao.delete(pending)
            } catch (e: HttpException) {
                val body = "HTTP ${e.code()}"
                if (e.code() == 410) {
                    // Explicitly too old to resend; the server will never take it.
                    dao.block(pending.clientUuid, "$body: registro antigo demais")
                } else if (e.code() in 400..499) {
                    // A 4xx here means the payload is being refused (exercise
                    // deactivated, session too old, validation). Retrying will
                    // not help, but this row is the ONLY copy of the student's
                    // sets, so park it for inspection instead of deleting it.
                    dao.block(pending.clientUuid, body)
                } else {
                    dao.recordFailure(pending.clientUuid, body)
                    transientFailure = true
                }
            } catch (e: IOException) {
                dao.recordFailure(pending.clientUuid, e.message ?: "sem conexão")
                transientFailure = true
            }
        }
        return if (transientFailure) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "sync_pending_workouts"

        /** Enqueue a drain; safe to call often (existing work is kept). */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
