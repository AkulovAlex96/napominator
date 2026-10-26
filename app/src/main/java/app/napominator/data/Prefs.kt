package app.napominator.data

import android.content.Context

/** Пользовательские настройки и мелкое состояние (не требующее Room). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)

    var weekStartsSunday: Boolean
        get() = sp.getBoolean("weekStartsSunday", false)
        set(v) = sp.edit().putBoolean("weekStartsSunday", v).apply()

    /** Варианты «Отложить», минуты. Первый — по умолчанию для кнопки в уведомлении. */
    var snoozePresets: List<Int>
        get() = (sp.getString("snooze", "10,30,60") ?: "10,30,60").split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.ifEmpty { listOf(10, 30, 60) }
        set(v) = sp.edit().putString("snooze", v.joinToString(",")).apply()

    val defaultSnooze: Int get() = snoozePresets.first()

    fun categorySound(cat: String): String? = sp.getString("sound:$cat", null)
    fun categorySoundVersion(cat: String): Int = sp.getInt("soundv:$cat", 0)
    fun setCategorySound(cat: String, uri: String?) {
        sp.edit().putString("sound:$cat", uri).putInt("soundv:$cat", categorySoundVersion(cat) + 1).apply()
    }

    fun lastFired(rid: Long): Long = sp.getLong("fired:$rid", 0)
    fun setLastFired(rid: Long, t: Long) = sp.edit().putLong("fired:$rid", t).apply()

    var backupTree: String?
        get() = sp.getString("backupTree", null)
        set(v) = sp.edit().putString("backupTree", v).apply()

    var backupOn: Boolean
        get() = sp.getBoolean("backupOn", false)
        set(v) = sp.edit().putBoolean("backupOn", v).apply()

    var lastBackup: Long
        get() = sp.getLong("lastBackup", 0)
        set(v) = sp.edit().putLong("lastBackup", v).apply()
}
