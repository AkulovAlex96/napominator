package app.napominator.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.napominator.App
import app.napominator.alarm.Alarms
import app.napominator.alarm.Notifier
import app.napominator.ui.AlarmActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Только для отладки:
 *   am broadcast -n app.napominator/.debug.SeedReceiver                      загрузить files/seed.json
 *   ... --es action fire --el rid N                                         показать уведомление напоминания N
 *   ... --es action alarm --el rid N                                        открыть полноэкранный критичный экран
 */
class SeedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val r = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = App.get(ctx)
                val rid = intent.getLongExtra("rid", -1)
                val due = System.currentTimeMillis()
                when (intent.getStringExtra("action")) {
                    "cancelall" -> ctx.getSystemService(android.app.NotificationManager::class.java).cancelAll()
                    "fire" -> app.repo.get(rid)?.let { Notifier.show(ctx, it, due, 0) }
                    "alarm" -> ctx.startActivity(Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(Alarms.EXTRA_ID, rid).putExtra(Alarms.EXTRA_DUE, due))
                    else -> {
                        val f = File(ctx.filesDir, "seed.json")
                        app.repo.importJson(f.readText())
                        Log.i("Seed", "imported ${f.length()} bytes")
                    }
                }
            } catch (e: Exception) { Log.e("Seed", "failed", e) } finally { r.finish() }
        }
    }
}
