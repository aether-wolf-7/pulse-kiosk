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

        for (pending in dao.all()) {
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
                dao.delete(pending)
            } catch (e: HttpException) {
                // 4xx/410: the server understood and refused; retrying the
                // same payload can never succeed. Drop it (server keeps its
                // own failed-push retry for the Hevy leg).
                if (e.code() in 400..499 || e.code() == 410) {
                    dao.delete(pending)
                } else {
                    dao.bumpAttempts(pending.clientUuid)
                    transientFailure = true
                }
            } catch (e: IOException) {
                dao.bumpAttempts(pending.clientUuid)
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
