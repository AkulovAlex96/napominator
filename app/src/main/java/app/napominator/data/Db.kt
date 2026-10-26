package app.napominator.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM Reminder ORDER BY id")
    fun observeAll(): Flow<List<Reminder>>

    @Query("SELECT * FROM Reminder WHERE status = 'ACTIVE'")
    suspend fun active(): List<Reminder>

    @Query("SELECT * FROM Reminder")
    suspend fun all(): List<Reminder>

    @Query("SELECT * FROM Reminder WHERE id = :id")
    suspend fun get(id: Long): Reminder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: Reminder): Long

    @Query("DELETE FROM Reminder WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM Reminder")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Reminder>)
}

@Dao
interface HabitSetDao {
    @Query("SELECT * FROM HabitSet ORDER BY id")
    fun observeAll(): Flow<List<HabitSet>>

    @Query("SELECT * FROM HabitSet")
    suspend fun all(): List<HabitSet>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: HabitSet): Long

    @Query("DELETE FROM HabitSet WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM HabitSet")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<HabitSet>)
}

@Dao
interface OccurrenceDao {
    @Query("SELECT * FROM Occurrence WHERE epochDay BETWEEN :from AND :to")
    fun observeRange(from: Long, to: Long): Flow<List<Occurrence>>

    @Query("SELECT * FROM Occurrence WHERE epochDay BETWEEN :from AND :to")
    suspend fun range(from: Long, to: Long): List<Occurrence>

    @Query("SELECT * FROM Occurrence WHERE reminderId = :rid AND dueAt = :dueAt")
    suspend fun find(rid: Long, dueAt: Long): Occurrence?

    @Query("SELECT * FROM Occurrence WHERE reminderId = :rid ORDER BY dueAt DESC LIMIT :limit")
    fun observeFor(rid: Long, limit: Int): Flow<List<Occurrence>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(o: Occurrence): Long

    @Update
    suspend fun update(o: Occurrence)

    @Query("SELECT * FROM Occurrence WHERE state = 'PENDING' AND epochDay < :today")
    suspend fun pendingBefore(today: Long): List<Occurrence>

    @Query("DELETE FROM Occurrence WHERE reminderId = :rid AND state = 'PENDING' AND dueAt > :after")
    suspend fun deleteFuturePending(rid: Long, after: Long)

    @Query("SELECT * FROM Occurrence")
    suspend fun all(): List<Occurrence>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Occurrence>)

    @Query("DELETE FROM Occurrence")
    suspend fun deleteAll()

    @Query("DELETE FROM Occurrence WHERE reminderId = :rid")
    suspend fun deleteFor(rid: Long)

    @Query("SELECT * FROM Occurrence WHERE reminderId = :rid AND epochDay >= :from ORDER BY dueAt")
    suspend fun since(rid: Long, from: Long): List<Occurrence>
}

@Dao
interface MeasurementDao {
    @Query("SELECT * FROM Measurement WHERE reminderId = :rid ORDER BY ts")
    fun observeFor(rid: Long): Flow<List<Measurement>>

    @Query("SELECT * FROM Measurement WHERE epochDay BETWEEN :from AND :to")
    fun observeRange(from: Long, to: Long): Flow<List<Measurement>>

    @Query("SELECT * FROM Measurement WHERE reminderId = :rid ORDER BY ts DESC LIMIT 1")
    suspend fun last(rid: Long): Measurement?

    @Query("SELECT * FROM Measurement ORDER BY ts")
    suspend fun all(): List<Measurement>

    @Query("SELECT COALESCE(SUM(value), 0) FROM Measurement WHERE reminderId = :rid AND epochDay = :day AND field = :field")
    suspend fun sum(rid: Long, day: Long, field: String): Double

    @Insert
    suspend fun insert(m: Measurement): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Measurement>)

    @Delete
    suspend fun delete(m: Measurement)

    @Query("DELETE FROM Measurement")
    suspend fun deleteAll()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Reminder ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE Reminder ADD COLUMN checklist TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE Reminder ADD COLUMN triggerMode TEXT NOT NULL DEFAULT 'TIME'")
        db.execSQL("ALTER TABLE Reminder ADD COLUMN conditions TEXT")
        db.execSQL("ALTER TABLE Occurrence ADD COLUMN checked TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS HabitSet (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, reminderIds TEXT NOT NULL, category TEXT NOT NULL)")
    }
}

@Database(entities = [Reminder::class, Occurrence::class, Measurement::class, HabitSet::class], version = 2, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun sets(): HabitSetDao
    abstract fun reminders(): ReminderDao
    abstract fun occurrences(): OccurrenceDao
    abstract fun measurements(): MeasurementDao

    companion object {
        fun create(ctx: Context): AppDb =
            Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "napominator.db").addMigrations(MIGRATION_1_2).build()
    }
}
