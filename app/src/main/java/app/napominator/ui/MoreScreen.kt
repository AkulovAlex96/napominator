package app.napominator.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.napominator.App
import app.napominator.alarm.Alarms
import app.napominator.alarm.Notifier
import app.napominator.data.Backups
import app.napominator.data.Kind
import app.napominator.data.Reminder
import app.napominator.data.Schedule
import app.napominator.data.Status
import app.napominator.data.csvWithBom
import app.napominator.data.describe
import app.napominator.data.readText
import app.napominator.data.writeText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class Check(val name: String, val ok: Boolean, val hint: String, val fix: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoreScreen(app: App, reminders: List<Reminder>, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val repo = app.repo
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>(null) }
    var weekSun by remember(tick) { mutableStateOf(app.prefs.weekStartsSunday) }
    var snoozeText by remember { mutableStateOf(app.prefs.snoozePresets.joinToString(", ")) }
    var backupOn by remember(tick) { mutableStateOf(app.prefs.backupOn) }
    var confirmImport by remember { mutableStateOf<String?>(null) }
    var soundFor by remember { mutableStateOf<String?>(null) }
    var soundTick by remember { mutableIntStateOf(0) }

    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { runCatching { writeText(ctx, uri, repo.exportJson()) }.onSuccess { toast("Резервная копия сохранена") }.onFailure { toast("Ошибка: ${it.message}") } }
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { runCatching { readText(ctx, uri) }.onSuccess { confirmImport = it }.onFailure { toast("Ошибка: ${it.message}") } }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { runCatching { writeText(ctx, uri, csvWithBom(repo.measurementsCsv())) }.onSuccess { toast("Файл сохранён") }.onFailure { toast("Ошибка: ${it.message}") } }
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            app.prefs.backupTree = uri.toString(); app.prefs.backupOn = true; backupOn = true
            Backups.schedule(ctx, true)
            scope.launch { toast(if (Backups.run(ctx)) "Копия сделана" else "Не удалось записать в папку") }
        }
    }
    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cat = soundFor
        if (cat != null && res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val u: Uri? = res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            app.prefs.setCategorySound(cat, u?.toString()); soundTick++
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ещё", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 4.dp, top = 12.dp))

        SectionLabel("Тема")
        Seg(listOf("system" to "Система", "light" to "Светлая", "dark" to "Тёмная"), app.themeMode.value, { app.setTheme(it) }, Modifier.fillMaxWidth())

        SectionLabel("Надёжность")
        val pm = ctx.getSystemService(PowerManager::class.java)
        val checks = remember(tick) {
            val fsOk = Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            listOf(
                Check("Уведомления", Notifier.canNotify(ctx), "Разрешите показ уведомлений.") {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                },
                Check("Точные будильники", Alarms.canExact(ctx), "Без них напоминания могут опаздывать.") {
                    if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                },
                Check("Экономия батареи", pm.isIgnoringBatteryOptimizations(ctx.packageName), "Выберите «Не ограничивать» для Напоминатора.") {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                },
                Check("Полноэкранные (критичные)", fsOk, "Разрешите показ поверх блокировки.") {
                    if (Build.VERSION.SDK_INT >= 34) ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
                },
            )
        }
        checks.forEach { c ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(if (c.ok) Icons.Filled.Check else Icons.Filled.Warning, null, tint = if (c.ok) ext.ok else ext.warn, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontWeight = FontWeight.Bold)
                    Text(if (c.ok) "в порядке" else c.hint, fontSize = 13.sp, color = ext.muted)
                }
                if (!c.ok) TextButton(c.fix) { Text("Исправить") }
            }
        }
        OutlinedButton({
            scope.launch {
                val t = Reminder(id = 2_000_000_000L, title = "Проверка уведомлений", note = "Если вы это видите, напоминания приходят.", schedule = "{}", priority = 1)
                Notifier.show(ctx, t, System.currentTimeMillis(), 0)
            }
        }, Modifier.fillMaxWidth()) { Text("Отправить тестовое уведомление") }
        Text("На Xiaomi, Samsung, Huawei и др. дополнительно включите автозапуск приложения в системных настройках.", fontSize = 12.sp, color = ext.muted, modifier = Modifier.padding(horizontal = 4.dp))

        SectionLabel("Настройки")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Неделя начинается с воскресенья", Modifier.weight(1f))
            Switch(weekSun, { weekSun = it; app.prefs.weekStartsSunday = it })
        }
        OutlinedTextField(
            snoozeText, { snoozeText = it; val l = it.split(',').mapNotNull { x -> x.trim().toIntOrNull() }.filter { x -> x > 0 }; if (l.isNotEmpty()) app.prefs.snoozePresets = l },
            Modifier.fillMaxWidth(), label = { Text("Варианты «Отложить», минуты") }, singleLine = true,
            supportingText = { Text("Через запятую. Первое значение используется кнопкой в уведомлении.") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
        val cats = reminders.map { it.category }.filter { it.isNotBlank() }.distinct()
        if (cats.isNotEmpty()) {
            Text("Звук по категориям", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            cats.forEach { c ->
                val cur = remember(soundTick, c) { app.prefs.categorySound(c) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c, fontWeight = FontWeight.Bold)
                        Text(cur?.let { RingtoneManager.getRingtone(ctx, Uri.parse(it))?.getTitle(ctx) } ?: "по умолчанию", fontSize = 12.sp, color = ext.muted)
                    }
                    TextButton({
                        soundFor = c
                        pickSound.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true).putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur?.let { Uri.parse(it) }))
                    }) { Text("Выбрать") }
                    if (cur != null) TextButton({ app.prefs.setCategorySound(c, null); soundTick++ }) { Text("Сбросить") }
                }
            }
        }

        SectionLabel("Данные")
        val stamp = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
        OutlinedButton({ exportJson.launch("napominator-$stamp.json") }, Modifier.fillMaxWidth()) { Text("Экспорт всего в JSON") }
        OutlinedButton({ importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, Modifier.fillMaxWidth()) { Text("Импорт из JSON") }
        OutlinedButton({ exportCsv.launch("измерения-$stamp.csv") }, Modifier.fillMaxWidth()) { Text("Экспорт измерений в CSV") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Автокопия в папку")
                Text(if (app.prefs.backupTree == null) "папка не выбрана" else "раз в сутки" + (app.prefs.lastBackup.takeIf { it > 0 }?.let { ", последняя " + SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(it)) } ?: ""), fontSize = 12.sp, color = ext.muted)
            }
            Switch(backupOn, {
                if (it && app.prefs.backupTree == null) pickTree.launch(null)
                else { app.prefs.backupOn = it; backupOn = it; Backups.schedule(ctx, it) }
            })
        }
        if (app.prefs.backupTree != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ pickTree.launch(null) }) { Text("Другая папка") }
            OutlinedButton({ scope.launch { toast(if (Backups.run(ctx)) "Копия сделана" else "Не удалось записать в папку"); tick++ } }) { Text("Копия сейчас") }
        }

        SectionLabel("Напоминания")
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Поиск") }, singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) })
        val filters = (cats + reminders.flatMap { r -> r.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { "#$it" } }).distinct()
        if (filters.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            filters.forEach { f -> FilterChip(filter == f, { filter = if (filter == f) null else f }, { Text(f, fontSize = 12.sp) }) }
        }
        val shown = reminders.filter { matchesFilter(it, filter) && (query.isBlank() || it.title.contains(query, true) || it.note.contains(query, true) || it.tags.contains(query, true)) }
        listOf(Status.ACTIVE to "Активные", Status.PAUSED to "На паузе", Status.ARCHIVED to "Архив").forEach { (st, title) ->
            val list = shown.filter { it.status == st }
            if (list.isNotEmpty()) {
                SectionLabel(title)
                list.forEach { r ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).clickable { onOpen(r.id) }.padding(12.dp)) {
                        Text(r.title, fontWeight = FontWeight.Bold)
                        Text(r.describe(), fontSize = 13.sp, color = ext.muted)
                    }
                }
            }
        }
        if (shown.isEmpty()) Text(if (reminders.isEmpty()) "Напоминаний пока нет." else "Ничего не найдено.", color = ext.muted, modifier = Modifier.padding(4.dp))
        Text("", Modifier.padding(8.dp))
    }

    confirmImport?.let { text ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("Заменить все данные?") },
            text = { Text("Текущие напоминания, история и измерения будут удалены и заменены содержимым файла.") },
            confirmButton = {
                TextButton({
                    confirmImport = null
                    scope.launch { runCatching { repo.importJson(text) }.onSuccess { toast("Импорт выполнен") }.onFailure { toast("Не удалось импортировать: ${it.message}") } }
                }) { Text("Заменить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmImport = null }) { Text("Отмена") } },
        )
    }
}
