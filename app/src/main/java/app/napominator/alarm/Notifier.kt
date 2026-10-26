package app.napominator.alarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import app.napominator.App
import app.napominator.R
import app.napominator.data.Kind
import app.napominator.data.Reminder
import app.napominator.data.measureConfig
import app.napominator.data.checkItems
import app.napominator.data.describe
import app.napominator.data.sched
import app.napominator.ui.AlarmActivity
import app.napominator.ui.MainActivity

object Notifier {
    const val ACT_DONE = "done"
    const val ACT_SNOOZE = "snooze"
    const val ACT_SKIP = "skip"
    const val ACT_VALUE = "value"
    const val KEY_VALUE = "value"
    const val EXTRA_ACT = "act"
    const val EXTRA_OPEN_MEASURE = "openMeasure"
    const val EXTRA_MINUTES = "minutes"

    private fun channelId(priority: Int) = "p$priority"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alarm = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(channelId(0), "Обычные", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(channelId(1), "Важные", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 300, 150, 300)
        })
        nm.createNotificationChannel(NotificationChannel(channelId(2), "Критичные", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 600, 250, 600, 250, 600)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarm)
            setBypassDnd(true)
        })
    }

    /** Канал по приоритету; для категории с собственным звуком создаётся отдельный канал (звук канала менять нельзя). */
    private fun channelFor(ctx: Context, r: Reminder): String {
        val prefs = App.get(ctx).prefs
        val sound = if (r.category.isBlank()) null else prefs.categorySound(r.category)
        if (sound == null) return channelId(r.priority)
        val id = "c${r.category.hashCode()}_${prefs.categorySoundVersion(r.category)}_p${r.priority}"
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            val attrs = AudioAttributes.Builder().setUsage(if (r.priority == 2) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION).build()
            nm.createNotificationChannel(NotificationChannel(id, "Категория: ${r.category}", if (r.priority == 0) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(android.net.Uri.parse(sound), attrs)
                if (r.priority > 0) { enableVibration(true); vibrationPattern = longArrayOf(0, 300, 150, 300) }
            })
        }
        return id
    }

    fun canNotify(ctx: Context) =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun actionPi(ctx: Context, r: Reminder, due: Long, act: String, idx: Int, mutable: Boolean = false): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction("app.napominator.ACT.$act")
            .putExtra(Alarms.EXTRA_ID, r.id).putExtra(Alarms.EXTRA_DUE, due).putExtra(EXTRA_ACT, act)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, (r.id * 10 + idx).toInt(), i, flags)
    }

    private fun openPi(ctx: Context, r: Reminder, due: Long, measure: Boolean): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(Alarms.EXTRA_ID, r.id).putExtra(Alarms.EXTRA_DUE, due).putExtra(EXTRA_OPEN_MEASURE, measure)
        return PendingIntent.getActivity(ctx, (r.id * 10 + 5).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(ctx: Context, r: Reminder, due: Long, attempt: Int) {
        if (!canNotify(ctx)) return
        val measure = r.kind == Kind.MEASURE
        val singleField = measure && (r.measureConfig?.fields?.size ?: 1) == 1
        val unit = r.measureConfig?.fields?.firstOrNull()?.unit.orEmpty()
        val text = buildString {
            append(r.note.ifBlank { r.describe() })
            if (r.repeatEveryMin > 0 && attempt > 0) append(" · повтор $attempt из ${r.repeatMax}")
            r.checkItems.forEach { append("\n• ").append(it) }
        }
        val b = NotificationCompat.Builder(ctx, channelFor(ctx, r))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(r.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(if (r.priority == 2) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (r.priority == 0) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(r.priority == 2)
            .setContentIntent(openPi(ctx, r, due, measure))
            .setWhen(due).setShowWhen(true)

        if (measure) {
            if (singleField) {
                val ri = RemoteInput.Builder(KEY_VALUE).setLabel("Введите значение" + if (unit.isNotBlank()) ", $unit" else "").build()
                b.addAction(NotificationCompat.Action.Builder(0, "Ввести", actionPi(ctx, r, due, ACT_VALUE, 0, mutable = true)).addRemoteInput(ri).build())
            } else {
                b.addAction(0, "Ввести", openPi(ctx, r, due, true))
            }
        } else {
            b.addAction(0, "Выполнено", actionPi(ctx, r, due, ACT_DONE, 1))
        }
        b.addAction(0, "Отложить ${App.get(ctx).prefs.defaultSnooze} мин", actionPi(ctx, r, due, ACT_SNOOZE, 2))
        b.addAction(0, "Пропустить", actionPi(ctx, r, due, ACT_SKIP, 3))

        if (r.priority == 2) {
            val fs = Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Alarms.EXTRA_ID, r.id).putExtra(Alarms.EXTRA_DUE, due).putExtra(Alarms.EXTRA_ATTEMPT, attempt)
            b.setFullScreenIntent(PendingIntent.getActivity(ctx, (r.id * 10 + 6).toInt(), fs, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE), true)
        }
        // Повтор критичного должен снова поднять полноэкранный показ, а он срабатывает только для нового уведомления.
        if (r.priority == 2) NotificationManagerCompat.from(ctx).cancel(r.id.toInt())
        NotificationManagerCompat.from(ctx).notify(r.id.toInt(), b.build())
    }

    fun cancel(ctx: Context, rid: Long) = NotificationManagerCompat.from(ctx).cancel(rid.toInt())
}
