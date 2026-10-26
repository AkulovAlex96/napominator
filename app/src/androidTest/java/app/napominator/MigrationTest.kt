package app.napominator

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.napominator.data.AppDb
import app.napominator.data.MIGRATION_1_2
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDb::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test
    fun migrate1To2KeepsDataAndAddsDefaults() {
        helper.createDatabase("mig", 1).apply {
            execSQL("INSERT INTO Reminder (id,title,note,category,priority,kind,schedule,measure,repeatEveryMin,repeatMax,status,createdAt) VALUES (1,'Старое','заметка','Здоровье',1,'TASK','{}',NULL,0,0,'ACTIVE',123)")
            execSQL("INSERT INTO Occurrence (id,reminderId,dueAt,epochDay,state,resolvedAt,comment) VALUES (1,1,1000,0,'DONE',1001,NULL)")
            execSQL("INSERT INTO Measurement (id,reminderId,ts,epochDay,field,value) VALUES (1,1,5,0,'Вес',81.5)")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig", 2, true, MIGRATION_1_2)
        db.query("SELECT title, category, tags, checklist, triggerMode, conditions FROM Reminder").use {
            it.moveToFirst()
            assertEquals("Старое", it.getString(0)); assertEquals("Здоровье", it.getString(1))
            assertEquals("", it.getString(2)); assertEquals("", it.getString(3)); assertEquals("TIME", it.getString(4)); assert(it.isNull(5))
        }
        db.query("SELECT state, checked FROM Occurrence").use { it.moveToFirst(); assertEquals("DONE", it.getString(0)); assertEquals("", it.getString(1)) }
        db.query("SELECT value FROM Measurement").use { it.moveToFirst(); assertEquals(81.5, it.getDouble(0), 0.0) }
        db.execSQL("INSERT INTO HabitSet (name, reminderIds, category) VALUES ('Здоровье','1','')")
    }
}
