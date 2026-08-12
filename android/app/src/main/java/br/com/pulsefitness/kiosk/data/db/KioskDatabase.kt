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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Offline queue. Every finished machine visit is written here FIRST, then
 * synced to the backend (which pushes to Hevy). Gym wifi dropping is the
 * expected case, not the exception: the student never waits on the network.
 *
 * Rows are only deleted once the server has definitely accepted them. A row
 * the server keeps rejecting is parked (`blocked = 1`) rather than dropped,
 * because it is the only remaining copy of that student's sets.
 */
@Entity(tableName = "pending_workouts")
data class PendingWorkout(
    @PrimaryKey val clientUuid: String,
    val sessionToken: String,
    val exerciseId: Long,
    val setsJson: String, // JSON list of {weight_kg, reps}
    val loggedAt: String, // ISO-8601
    val attempts: Int = 0,
    val blocked: Boolean = false,
    val lastError: String = "",
)

@Dao
interface PendingWorkoutDao {
    @Insert
    suspend fun insert(workout: PendingWorkout)

    @Query("SELECT * FROM pending_workouts WHERE blocked = 0 ORDER BY loggedAt")
    suspend fun syncable(): List<PendingWorkout>

    @Query("SELECT * FROM pending_workouts ORDER BY loggedAt")
    suspend fun all(): List<PendingWorkout>

    @Delete
    suspend fun delete(workout: PendingWorkout)

    @Query("UPDATE pending_workouts SET attempts = attempts + 1, lastError = :error WHERE clientUuid = :clientUuid")
    suspend fun recordFailure(clientUuid: String, error: String)

    @Query("UPDATE pending_workouts SET blocked = 1, lastError = :error WHERE clientUuid = :clientUuid")
    suspend fun block(clientUuid: String, error: String)

    @Query("SELECT COUNT(*) FROM pending_workouts WHERE blocked = 1")
    suspend fun blockedCount(): Int
}

@Database(entities = [PendingWorkout::class], version = 2, exportSchema = false)
abstract class KioskDatabase : RoomDatabase() {
    abstract fun pendingWorkoutDao(): PendingWorkoutDao

    companion object {
        @Volatile private var instance: KioskDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_workouts ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_workouts ADD COLUMN lastError TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): KioskDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, KioskDatabase::class.java, "kiosk.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
