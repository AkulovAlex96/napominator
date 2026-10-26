package app.napominator.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.napominator.data.Reminder
import app.napominator.data.dueMillis
import app.napominator.data.sched
import app.napominator.data.today
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** Все будильники приложения: по одному «основному» и одному «повторному» (повтор/отложено) на напоминание. */
object Alarms {
    const val ACTION_FIRE = "app.napominator.FIRE"
    const val ACTION_CLOSE_DAY = "app.napominator.CLOSE_DAY"
    const val EXTRA_ID = "rid"
    const val EXTRA_DUE = "due"
    const val EXTRA_ATTEMPT = "attempt"

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun canExact(ctx: Context) = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    /** Ближайшее срабатывание строго позже [afterMs] либо null. */
    fun nextDue(r: Reminder, afterMs: Long): Long? {
        val s = r.sched
        var day = today()
        // Начинаем со вчера: срабатывание в 00:30 после сдвига часового пояса не должно потеряться.
        day = day.minusDays(1)
        repeat(400) {
            for (m in s.minutesOn(day)) {
                val t = dueMillis(day, m)
                if (t > afterMs) return t
            }
            day = day.plusDays(1)
        }
        return null
    }

    private fun pi(ctx: Context, rid: Long, kind: Int, due: Long, attempt: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, rid).putExtra(EXTRA_DUE, due).putExtra(EXTRA_ATTEMPT, attempt)
        return PendingIntent.getBroadcast(ctx, (rid * 2 + kind).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun set(ctx: Context, at: Long, pi: PendingIntent) {
        val am = am(ctx)
        if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun scheduleNext(ctx: Context, r: Reminder, after: Long = System.currentTimeMillis()) {
        if (r.triggerMode != app.napominator.data.Trigger.TIME) { cancelMain(ctx, r.id); return }
        val due = if (r.status == app.napominator.data.Status.ACTIVE) nextDue(r, after) else null
        if (due == null) cancelMain(ctx, r.id) else set(ctx, due, pi(ctx, r.id, 0, due, 0))
    }

    /** Повтор или «отложить»: сигнал для того же dueAt, но позже. */
    fun scheduleFollowUp(ctx: Context, rid: Long, due: Long, at: Long, attempt: Int) = set(ctx, at, pi(ctx, rid, 1, due, attempt))

    private fun cancelKind(ctx: Context, rid: Long, kind: Int) {
        val p = pi(ctx, rid, kind, 0, 0)
        am(ctx).cancel(p)
        p.cancel()
    }
    private fun cancelMain(ctx: Context, rid: Long) = cancelKind(ctx, rid, 0)
    fun cancelFollowUp(ctx: Context, rid: Long) = cancelKind(ctx, rid, 1)
    fun cancel(ctx: Context, rid: Long) { cancelMain(ctx, rid); cancelFollowUp(ctx, rid) }

    suspend fun rescheduleAll(ctx: Context, active: List<Reminder>) {
        active.forEach { scheduleNext(ctx, it) }
        scheduleDayClose(ctx)
    }

    /** Ежесуточная задача: отметить непроставленное как пропущенное. */
    fun scheduleDayClose(ctx: Context) {
        val next = LocalDate.now().plusDays(1).atTime(0, 5).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val i = Intent(ctx, SystemReceiver::class.java).setAction(ACTION_CLOSE_DAY)
        val pi = PendingIntent.getBroadcast(ctx, -1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }
}
