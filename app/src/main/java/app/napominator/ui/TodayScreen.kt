package app.napominator.ui

import app.napominator.data.slotsFor
import app.napominator.data.Slot
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import app.napominator.data.Trigger
import app.napominator.data.checkItems
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.napominator.alarm.Alarms
import app.napominator.alarm.Notifier
import app.napominator.data.Kind
import app.napominator.data.OccState
import app.napominator.data.Reminder
import app.napominator.data.Repo
import app.napominator.data.sched
import app.napominator.data.today
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

@Composable
fun TodayScreen(repo: Repo, reminders: List<Reminder>, onAdd: () -> Unit, onOpen: (Long) -> Unit, onMeasure: (Reminder, Long) -> Unit) {
    val day = today()
    val occs by remember(day) { repo.occurrences(day.toEpochDay(), day.toEpochDay()) }.collectAsState(emptyList())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val scope = rememberCoroutineScope()

    val slots = slotsFor(reminders, occs, day)
    val resolved = slots.filter { it.state == OccState.DONE || it.state == OccState.SKIPPED }
    val unresolved = slots - resolved.toSet()
    val attention = unresolved.filter { it.dueAt <= now }
    val upcoming = unresolved.filter { it.dueAt > now }
    val doneCount = slots.count { it.state == OccState.DONE }

    fun toggle(s: Slot) {
        if (s.reminder.kind == Kind.MEASURE && s.state != OccState.DONE) { onMeasure(s.reminder, s.dueAt); return }
        scope.launch { repo.setState(s.reminder.id, s.dueAt, if (s.state == OccState.DONE) OccState.PENDING else OccState.DONE) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)) {
                    Text("Сегодня", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru"))}, ${day.dayOfMonth} ${app.napominator.data.Schedule.monthGen(day.monthValue)}", color = ext.muted)
                }
            }
            item { PermissionBanner() }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = cardShape, border = BorderStroke(1.dp, ext.line)) {
                    Column(Modifier.padding(14.dp)) {
                        Row { Text(if (slots.isEmpty()) "На сегодня ничего нет" else "Выполнено $doneCount из ${slots.size}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); if (slots.isNotEmpty()) Text("осталось ${unresolved.size}", color = ext.muted, fontSize = 13.sp) }
                        if (slots.isNotEmpty()) {
                            Spacer(Modifier.size(10.dp))
                            LinearProgressIndicator(progress = { doneCount / slots.size.toFloat() }, Modifier.fillMaxWidth(), trackColor = ext.surface2, drawStopIndicator = {})
                        }
                    }
                }
            }
            if (attention.isNotEmpty()) { item { SectionLabel("Сейчас") }; items(attention, key = { "a${it.reminder.id}-${it.dueAt}" }) { SlotRow(it, true, repo, { onOpen(it.reminder.id) }, { toggle(it) }) } }
            if (upcoming.isNotEmpty()) { item { SectionLabel("Дальше") }; items(upcoming, key = { "u${it.reminder.id}-${it.dueAt}" }) { SlotRow(it, false, repo, { onOpen(it.reminder.id) }, { toggle(it) }) } }
            if (resolved.isNotEmpty()) { item { SectionLabel("Готово") }; items(resolved, key = { "d${it.reminder.id}-${it.dueAt}" }) { SlotRow(it, false, repo, { onOpen(it.reminder.id) }, { toggle(it) }) } }
        }
        FloatingActionButton(onClick = onAdd, Modifier.align(Alignment.BottomEnd).padding(20.dp), containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Icon(Icons.Filled.Add, "Новое напоминание")
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SlotRow(s: Slot, attention: Boolean, repo: Repo, onOpen: () -> Unit, onCheck: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val done = s.state == OccState.DONE
    val skipped = s.state == OccState.SKIPPED
    val shape = RoundedCornerShape(18.dp)
    val items = s.reminder.checkItems

    fun snooze(min: Int) {
        Notifier.cancel(ctx, s.reminder.id)
        Alarms.scheduleFollowUp(ctx, s.reminder.id, s.dueAt, System.currentTimeMillis() + min * 60_000L, 0)
        Toast.makeText(ctx, "Напомню через $min мин", Toast.LENGTH_SHORT).show()
    }

    Column(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(if (attention) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, ext.line), shape),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val timeText = if (s.reminder.triggerMode != Trigger.TIME) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.dueAt)) else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.dueAt))
            Text(timeText, fontWeight = FontWeight.Bold, color = if (done || skipped) ext.muted else MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 46.dp))
            Column(Modifier.weight(1f)) {
                Text(s.reminder.title, fontWeight = FontWeight.Bold, textDecoration = if (done || skipped) TextDecoration.LineThrough else null, color = if (done || skipped) ext.muted else MaterialTheme.colorScheme.onSurface)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.reminder.kind == Kind.MEASURE && !done) MiniChip("ввести значение", Icons.Filled.Edit, ext.accentSoft, ext.accentInk)
                    if (s.reminder.triggerMode != Trigger.TIME) MiniChip("по условию", Icons.Filled.Place, ext.accentSoft, ext.accentInk)
                    if (skipped) MiniChip("пропущено")
                    if (s.reminder.repeatEveryMin > 0 && attention) MiniChip("повтор ${s.reminder.repeatEveryMin} мин", Icons.Filled.Repeat, ext.warnSoft, ext.warn)
                    if (s.reminder.priority == 2) MiniChip("критичное", null, ext.warnSoft, ext.crit)
                }
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).then(if (done) Modifier.background(ext.ok) else Modifier.border(2.dp, ext.line, CircleShape)).clickable(onClick = onCheck)
                    .semantics { if (!done && s.reminder.kind != Kind.MEASURE) contentDescription = "Отметить" },
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Filled.Check, "Выполнено", tint = MaterialTheme.colorScheme.background)
                else if (s.reminder.kind == Kind.MEASURE) Icon(Icons.Filled.Edit, "Ввести значение", tint = ext.muted)
            }
        }
        if (!done && !skipped) items.forEachIndexed { i, text ->
            Row(Modifier.fillMaxWidth().clickable { scope.launch { repo.toggleChecked(s.reminder.id, s.dueAt, i) } }.padding(start = 58.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(i in s.checked, null)
                Text(text, Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), fontSize = 14.sp, textDecoration = if (i in s.checked) TextDecoration.LineThrough else null, color = if (i in s.checked) ext.muted else MaterialTheme.colorScheme.onSurface)
            }
        }
        if (attention) Box(Modifier.padding(start = 52.dp, bottom = 4.dp)) {
            TextButton({ menu = true }) { Icon(Icons.Filled.Snooze, null, Modifier.size(18.dp)); Text(" Отложить") }
            DropdownMenu(menu, { menu = false }) {
                repo.prefs.snoozePresets.forEach { m -> DropdownMenuItem({ Text(if (m % 60 == 0) "на ${m / 60} ч" else "на $m мин") }, { menu = false; snooze(m) }) }
                DropdownMenuItem({ Text("Своё время…") }, { menu = false; custom = true })
            }
        }
    }
    if (custom) {
        var txt by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { custom = false }, title = { Text("Отложить на сколько минут?") },
            text = { OutlinedTextField(txt, { txt = it.filter(Char::isDigit) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
            confirmButton = { TextButton({ txt.toIntOrNull()?.takeIf { it > 0 }?.let { snooze(it); custom = false } }) { Text("Отложить") } },
            dismissButton = { TextButton({ custom = false }) { Text("Отмена") } },
        )
    }
}

/** Подсказка, если уведомления или точные будильники выключены. */
@Composable
private fun PermissionBanner() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val notifOk = remember(tick) { Notifier.canNotify(ctx) }
    val exactOk = remember(tick) { Alarms.canExact(ctx) }
    if (notifOk && exactOk) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = ext.warnSoft), shape = cardShape) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (!notifOk) "Уведомления выключены" else "Нужны точные будильники", fontWeight = FontWeight.Bold, color = ext.warn)
            Text(if (!notifOk) "Без них напоминания не появятся." else "Без разрешения напоминания могут приходить с задержкой.", color = ext.warn, fontSize = 13.sp)
            Button(onClick = {
                if (!notifOk) {
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                } else if (Build.VERSION.SDK_INT >= 31) {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                }
            }) { Text("Разрешить") }
        }
    }
}
