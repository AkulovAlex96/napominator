package app.napominator.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.napominator.App
import app.napominator.data.Kind
import app.napominator.data.Reminder
import app.napominator.data.Status
import app.napominator.ui.NapominatorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Окно выбора величины при добавлении виджета «Измерение». */
class MeasureWidgetConfig : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val app = App.get(this)
        setContent {
            NapominatorTheme(app.themeMode.value) {
                var list by remember { mutableStateOf(emptyList<Reminder>()) }
                LaunchedEffect(Unit) { list = app.repo.db.reminders().all().filter { it.kind == Kind.MEASURE && it.status != Status.ARCHIVED } }
                Surface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Что показывать в виджете?", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                        if (list.isEmpty()) Text("Сначала создайте напоминание типа «Измерение».", modifier = Modifier.padding(vertical = 8.dp))
                        list.forEach { r ->
                            Text(r.title, fontSize = 18.sp, modifier = Modifier.fillMaxWidth().clickable {
                                getSharedPreferences("widgets", Context.MODE_PRIVATE).edit().putLong("measure:$widgetId", r.id).apply()
                                CoroutineScope(Dispatchers.Default).launch { MeasureWidget().update(this@MeasureWidgetConfig, androidx.glance.appwidget.GlanceAppWidgetManager(this@MeasureWidgetConfig).getGlanceIdBy(widgetId)); }
                                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            }.padding(vertical = 14.dp))
                        }
                    }
                }
            }
        }
    }
}
