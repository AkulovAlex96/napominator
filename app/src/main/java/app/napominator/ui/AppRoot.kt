package app.napominator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import app.napominator.App
import app.napominator.alarm.Alarms

private enum class Tab(val title: String, val icon: ImageVector) {
    TODAY("Сегодня", Icons.Filled.Checklist),
    WEEK("Привычки", Icons.Filled.TableChart),
    CAL("Календарь", Icons.Filled.CalendarMonth),
    CHART("Графики", Icons.Filled.ShowChart),
    MORE("Ещё", Icons.Filled.MoreHoriz),
}

@Composable
fun AppRoot(app: App, pendingMeasure: MutableState<Pair<Long, Long>?>) {
    val repo = app.repo
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) } // 0 = новое
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var chartFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var presetDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var measure by remember { mutableStateOf<MeasureTarget?>(null) }
    val reminders by repo.reminders.collectAsState(emptyList())

    LaunchedEffect(Unit) {
        repo.closeDays()
        Alarms.rescheduleAll(ctx, repo.db.reminders().active())
    }
    LaunchedEffect(pendingMeasure.value, reminders) {
        val (rid, due) = pendingMeasure.value ?: return@LaunchedEffect
        reminders.firstOrNull { it.id == rid }?.let { measure = MeasureTarget(it, due.takeIf { d -> d > 0 }); pendingMeasure.value = null }
    }

    val editing = editId
    val detail = detailId
    // Surface задаёт цвет текста по умолчанию: без него в тёмной теме текст вне Scaffold получается чёрным.
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    if (editing != null) {
        BackHandler { editId = null }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { EditScreen(repo, editing, presetDay) { editId = null; presetDay = null } }
    } else if (detail != null) {
        BackHandler { detailId = null }
        DetailScreen(repo, detail, onEdit = { editId = detail }, onChart = { chartFor = detail; tab = Tab.CHART; detailId = null }, onClose = { detailId = null })
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(tab == t, { tab = t }, { Icon(t.icon, null) }, label = { Text(t.title) })
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    Tab.TODAY -> TodayScreen(repo, reminders, { editId = 0 }, { detailId = it }, { r, due -> measure = MeasureTarget(r, due) })
                    Tab.WEEK -> WeekScreen(repo, reminders, { detailId = it }, { measure = it })
                    Tab.CAL -> CalendarScreen(repo, reminders, { detailId = it }, { d -> presetDay = d.toEpochDay(); editId = 0 })
                    Tab.CHART -> ChartsScreen(repo, reminders, chartFor, { measure = it })
                    Tab.MORE -> MoreScreen(app, reminders, { detailId = it })
                }
            }
        }
    }
    }
    measure?.let { MeasureSheet(it, repo) { measure = null } }
}
