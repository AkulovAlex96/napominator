package app.napominator.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import app.napominator.App
import app.napominator.data.Kind
import app.napominator.data.OccState
import app.napominator.data.Occurrence
import app.napominator.data.epochDayOf
import app.napominator.data.measureConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val result = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try { block() } finally { result.finish() }
    }
}

/** Срабатывание будильника: фиксируем срабатывание, показываем уведомление, планируем следующее. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = async {
        val repo = App.get(ctx).repo
        val rid = intent.getLongExtra(Alarms.EXTRA_ID, -1)
        val due = intent.getLongExtra(Alarms.EXTRA_DUE, 0)
        val attempt = intent.getIntExtra(Alarms.EXTRA_ATTEMPT, 0)
        val r = repo.get(rid) ?: return@async
        if (r.status != app.napominator.data.Status.ACTIVE) return@async

        val dao = repo.db.occurrences()
        val existing = dao.find(rid, due)
        if (existing == null) {
            dao.insertIgnore(Occurrence(reminderId = rid, dueAt = due, epochDay = epochDayOf(due), state = OccState.PENDING))
        } else if (existing.state != OccState.PENDING) {
            // Уже отмечено (например, отложенное и выполненное раньше): ничего не показываем.
            if (attempt == 0) Alarms.scheduleNext(ctx, r, maxOf(due, System.currentTimeMillis()))
            return@async
        }
        Notifier.show(ctx, r, due, attempt)

        if (r.repeatEveryMin > 0 && r.repeatMax > 0 && attempt < r.repeatMax) {
            Alarms.scheduleFollowUp(ctx, rid, due, System.currentTimeMillis() + r.repeatEveryMin * 60_000L, attempt + 1)
        }
        if (attempt == 0) Alarms.scheduleNext(ctx, r, maxOf(due, System.currentTimeMillis()))
    }
}

/** Кнопки уведомления. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = async {
        val repo = App.get(ctx).repo
        val rid = intent.getLongExtra(Alarms.EXTRA_ID, -1)
        val due = intent.getLongExtra(Alarms.EXTRA_DUE, 0)
        val r = repo.get(rid) ?: return@async
        when (intent.getStringExtra(Notifier.EXTRA_ACT)) {
            Notifier.ACT_DONE -> repo.setState(rid, due, OccState.DONE)
            Notifier.ACT_SKIP -> repo.setState(rid, due, OccState.SKIPPED)
            Notifier.ACT_SNOOZE -> {
                Notifier.cancel(ctx, rid)
                val mins = intent.getIntExtra(Notifier.EXTRA_MINUTES, App.get(ctx).prefs.defaultSnooze)
                Alarms.scheduleFollowUp(ctx, rid, due, System.currentTimeMillis() + mins * 60_000L, 0)
            }
            Notifier.ACT_VALUE -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_VALUE)?.toString()
                val v = text?.replace(',', '.')?.trim()?.toDoubleOrNull()
                val field = r.measureConfig?.fields?.firstOrNull()?.name
                if (v != null && field != null && r.kind == Kind.MEASURE) {
                    repo.addMeasurements(rid, System.currentTimeMillis(), mapOf(field to v))
                    repo.setState(rid, due, OccState.DONE)
                }
            }
        }
    }
}

/** Перезагрузка, смена времени и пояса, обновление приложения, ежесуточное закрытие дня. */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = async {
        val repo = App.get(ctx).repo
        if (intent.action == Alarms.ACTION_CLOSE_DAY) {
            repo.closeDays()
            Alarms.scheduleDayClose(ctx)
            return@async
        }
        repo.closeDays()
        val active = repo.db.reminders().active()
        Alarms.rescheduleAll(ctx, active)
        Conditions.sync(ctx, active)
    }
}
