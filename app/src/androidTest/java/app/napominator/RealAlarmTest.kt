package app.napominator

import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.napominator.TestUtil.activeNotification
import app.napominator.TestUtil.ctx
import app.napominator.TestUtil.reminder
import app.napominator.TestUtil.repo
import app.napominator.TestUtil.waitFor
import app.napominator.alarm.AlarmReceiver
import app.napominator.alarm.Alarms
import app.napominator.data.OccState
import app.napominator.data.Schedule
import app.napominator.data.ScheduleType
import app.napominator.data.dueMillis
import app.napominator.data.toJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/** Настоящий путь: AlarmManager сам будит приложение в назначенную минуту, без ручных broadcast. */
@RunWith(AndroidJUnit4::class)
class RealAlarmTest {
    @Before fun setUp() = TestUtil.reset()

    private fun pi(rid: Long, kind: Int) = PendingIntent.getBroadcast(
        ctx, (rid * 2 + kind).toInt(), Intent(ctx, AlarmReceiver::class.java).setAction(Alarms.ACTION_FIRE), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    @Test fun onceReminderFiresAtItsMinuteAndRecurringOneReschedules() {
        val target = LocalDateTime.now().plusMinutes(2).withSecond(0).withNano(0)
        val minute = target.hour * 60 + target.minute
        val day = target.toLocalDate()
        val once = runBlocking {
            repo.save(reminder("Разовое", Schedule(ScheduleType.ONCE, times = listOf(minute), onceEpochDay = day.toEpochDay(), startEpochDay = day.toEpochDay())))
        }
        val daily = runBlocking {
            repo.save(reminder("Ежедневное", Schedule(ScheduleType.DAYS_OF_WEEK, times = listOf(minute), startEpochDay = day.minusDays(30).toEpochDay())))
        }
        assertNotNull(pi(once, 0)); assertNotNull(pi(daily, 0))
        val due = dueMillis(day, minute)

        waitFor(timeoutMs = 4 * 60_000L, what = "уведомления по будильнику") { activeNotification(once) != null && activeNotification(daily) != null }
        val firedAt = System.currentTimeMillis()
        assert(firedAt - due in -1_000L..90_000L) { "сработало не вовремя: отклонение ${firedAt - due} мс" }

        assertEquals(OccState.PENDING, runBlocking { repo.db.occurrences().find(once, due) }?.state)
        waitFor(what = "пересчёт") { pi(daily, 0) != null }
        // ежедневное планируется на завтра, разовое больше не планируется
        val next = Alarms.nextDue(runBlocking { repo.get(daily)!! }, due)!!
        assertEquals(dueMillis(day.plusDays(1), minute), next)
        waitFor(what = "разовое снято") { pi(once, 0) == null }
        assertNull(pi(once, 0))
    }
}
