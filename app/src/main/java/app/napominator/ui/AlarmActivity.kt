package app.napominator.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.napominator.App
import app.napominator.alarm.Alarms
import app.napominator.data.OccState
import app.napominator.data.Reminder
import app.napominator.data.describe
import app.napominator.data.sched
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Полноэкранное напоминание поверх блокировки для приоритета «критичный». Всегда тёмное. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val rid = intent.getLongExtra(Alarms.EXTRA_ID, -1)
        val due = intent.getLongExtra(Alarms.EXTRA_DUE, 0)
        val repo = App.get(this).repo
        setContent {
            var r by remember { mutableStateOf<Reminder?>(null) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(rid) { r = repo.get(rid) }
            val reminder = r ?: return@setContent
            CriticalScreen(
                reminder, due,
                onDone = { scope.launch { repo.setState(rid, due, OccState.DONE); finish() } },
                onSkip = { scope.launch { repo.setState(rid, due, OccState.SKIPPED); finish() } },
                onSnooze = { min ->
                    scope.launch {
                        app.napominator.alarm.Notifier.cancel(this@AlarmActivity, rid)
                        Alarms.scheduleFollowUp(this@AlarmActivity, rid, due, System.currentTimeMillis() + min * 60_000L, 0)
                        finish()
                    }
                },
            )
        }
    }
}

@Composable
private fun CriticalScreen(r: Reminder, due: Long, onDone: () -> Unit, onSkip: () -> Unit, onSnooze: (Int) -> Unit) {
    val bg = Color(0xFF1B0F0A); val ink = Color(0xFFFFEDE5); val soft = Color(0xFFE8B8A4); val accent = Color(0xFFFFB08A)
    Column(Modifier.fillMaxSize().background(bg).systemBarsPadding().padding(24.dp)) {
        Spacer(Modifier.height(48.dp))
        Text("КРИТИЧНОЕ", color = accent, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(due)), color = ink, fontSize = 84.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Text(r.title, color = ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(r.note.ifBlank { r.describe() }, color = soft, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onDone, Modifier.fillMaxWidth().height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF2A1004))) { Text("Выполнено", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            val sec = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B1A12), contentColor = ink)
            val presets = App.get(androidx.compose.ui.platform.LocalContext.current).prefs.snoozePresets
            Button({ onSnooze(presets.first()) }, Modifier.fillMaxWidth().height(64.dp), colors = sec) { Text("Отложить на ${presets.first()} мин", fontSize = 18.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ onSnooze(presets.getOrElse(1) { 30 }) }, Modifier.weight(1f).height(52.dp), colors = sec) { Text("${presets.getOrElse(1) { 30 }} мин") }
                Button(onSkip, Modifier.weight(1f).height(52.dp), colors = sec) { Text("Пропустить") }
            }
        }
    }
}
