package app.napominator.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.napominator.App
import app.napominator.alarm.Alarms
import app.napominator.alarm.Notifier

class MainActivity : ComponentActivity() {
    /** (reminderId, dueAt), если приложение открыли из уведомления для ввода значения. */
    private val openMeasure = mutableStateOf<Pair<Long, Long>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        val app = App.get(this)
        setContent {
            NapominatorTheme(app.themeMode.value) { AppRoot(app, openMeasure) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        if (i.getBooleanExtra(Notifier.EXTRA_OPEN_MEASURE, false)) {
            openMeasure.value = i.getLongExtra(Alarms.EXTRA_ID, -1) to i.getLongExtra(Alarms.EXTRA_DUE, 0)
        }
    }
}
