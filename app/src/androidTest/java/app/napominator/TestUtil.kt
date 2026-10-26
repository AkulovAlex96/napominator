package app.napominator

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import app.napominator.alarm.ActionReceiver
import app.napominator.alarm.AlarmReceiver
import app.napominator.alarm.Alarms
import app.napominator.alarm.Notifier
import app.napominator.data.Kind
import app.napominator.data.Reminder
import app.napominator.data.Schedule
import app.napominator.data.ScheduleType
import app.napominator.data.dueMillis
import app.napominator.data.toJson
import app.napominator.data.today
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

object TestUtil {
    val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val app: App get() = App.get(ctx)
    val repo get() = app.repo

    fun shell(cmd: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } }
    }

    /** Чистое состояние: база, будильники, уведомления, настройки. */
    fun reset() = runBlocking {
        for (r in repo.db.reminders().all()) { Alarms.cancel(ctx, r.id); Notifier.cancel(ctx, r.id) }
        repo.db.clearAllTables()
        ctx.getSharedPreferences("state", Context.MODE_PRIVATE).edit().clear().apply()
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
        shell("appops set ${ctx.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
    }

    fun onceAt(day: LocalDate, minutes: Int) = Schedule(ScheduleType.ONCE, times = listOf(minutes), onceEpochDay = day.toEpochDay(), startEpochDay = day.toEpochDay())
    fun daily(vararg minutes: Int) = Schedule(ScheduleType.DAYS_OF_WEEK, times = minutes.toList(), startEpochDay = today().minusDays(60).toEpochDay())

    fun reminder(title: String, s: Schedule, kind: Kind = Kind.TASK, measure: String? = null, created: Long = System.currentTimeMillis() - 90L * 86_400_000L, block: (Reminder) -> Reminder = { it }) =
        block(Reminder(title = title, schedule = s.toJson(), kind = kind, measure = measure, createdAt = created))

    fun fireIntent(rid: Long, due: Long, attempt: Int = 0) = Intent(ctx, AlarmReceiver::class.java).setAction(Alarms.ACTION_FIRE)
        .putExtra(Alarms.EXTRA_ID, rid).putExtra(Alarms.EXTRA_DUE, due).putExtra(Alarms.EXTRA_ATTEMPT, attempt)

    fun actionIntent(rid: Long, due: Long, act: String) = Intent(ctx, ActionReceiver::class.java).setAction("app.napominator.ACT.$act")
        .putExtra(Alarms.EXTRA_ID, rid).putExtra(Alarms.EXTRA_DUE, due).putExtra(Notifier.EXTRA_ACT, act)

    /** Ждать условия до [timeoutMs], проверяя каждые 100 мс. */
    fun waitFor(timeoutMs: Long = 5000, what: String = "условия", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (runCatching(cond).getOrDefault(false)) return; Thread.sleep(100) }
        throw AssertionError("Не дождались: $what")
    }

    fun activeNotification(id: Long) = ctx.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == id.toInt() }

    fun dueToday(minutes: Int) = dueMillis(today(), minutes)
}
