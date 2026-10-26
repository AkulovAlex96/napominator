package app.napominator

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.napominator.alarm.Notifier
import app.napominator.data.AppDb
import app.napominator.data.Prefs
import app.napominator.data.Repo

class App : Application() {
    lateinit var repo: Repo
        private set
    lateinit var prefs: Prefs
        private set

    /** "system" | "light" | "dark" */
    val themeMode = mutableStateOf("system")

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = Repo(this, AppDb.create(this), prefs)
        themeMode.value = getSharedPreferences("state", Context.MODE_PRIVATE).getString("theme", "system") ?: "system"
        Notifier.createChannels(this)
        if (prefs.backupOn && prefs.backupTree != null) app.napominator.data.Backups.schedule(this, true)
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        }
    }

    fun setTheme(mode: String) {
        themeMode.value = mode
        getSharedPreferences("state", Context.MODE_PRIVATE).edit().putString("theme", mode).apply()
    }

    companion object {
        fun get(ctx: Context) = ctx.applicationContext as App
    }
}
