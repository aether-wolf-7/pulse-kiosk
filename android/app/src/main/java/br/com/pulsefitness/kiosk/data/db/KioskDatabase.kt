package br.com.pulsefitness.kiosk.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Offline queue. Every finished machine visit is written here FIRST, then
 * synced to the backend (which pushes to Hevy). Gym wifi dropping is the
 * expected case, not the exception: the student never waits on the network.
 */
@Entity(tableName = "pending_workouts")
data class PendingWorkout(
    @PrimaryKey val clientUuid: String,
    val sessionToken: String,
    val exerciseId: Long,
    val setsJson: String, // JSON list of {weight_kg, reps}
    val loggedAt: String, // ISO-8601
    val attempts: Int = 0,
)

@Dao
interface PendingWorkoutDao {
    @Insert
    suspend fun insert(workout: PendingWorkout)

    @Query("SELECT * FROM pending_workouts ORDER BY loggedAt")
    suspend fun all(): List<PendingWorkout>

    @Delete
    suspend fun delete(workout: PendingWorkout)

    @Query("UPDATE pending_workouts SET attempts = attempts + 1 WHERE clientUuid = :clientUuid")
    suspend fun bumpAttempts(clientUuid: String)

    @Query("SELECT COUNT(*) FROM pending_workouts")
    suspend fun count(): Int
}

@Database(entities = [PendingWorkout::class], version = 1, exportSchema = false)
abstract class KioskDatabase : RoomDatabase() {
    abstract fun pendingWorkoutDao(): PendingWorkoutDao

    companion object {
        @Volatile private var instance: KioskDatabase? = null

        fun get(context: Context): KioskDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, KioskDatabase::class.java, "kiosk.db"
                ).build().also { instance = it }
            }
    }
}
