package app.napominator.ui

import app.napominator.data.cellFor
import app.napominator.data.Cell
import app.napominator.data.CellKind
import app.napominator.data.slotsFor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.napominator.data.HabitSet
import app.napominator.data.Kind
import app.napominator.data.slotsOf
import app.napominator.data.OccState
import app.napominator.data.Reminder
import app.napominator.data.Repo
import app.napominator.data.Schedule
import app.napominator.data.Status
import app.napominator.data.dueMillis
import app.napominator.data.sched
import app.napominator.data.today
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

private val monthNames = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WeekScreen(repo: Repo, reminders: List<Reminder>, onOpen: (Long) -> Unit, onMeasure: (MeasureTarget) -> Unit) {
    var month by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(today()) }
    val sets by repo.sets.collectAsState(emptyList())
    var setId by remember { mutableLongStateOf(0L) }
    var setDialog by remember { mutableStateOf<HabitSet?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val curSet = sets.firstOrNull { it.id == setId }
    val rows = reminders.filter { it.status == Status.ACTIVE && (curSet == null || it.id in curSet.reminderIds.split(',').mapNotNull { x -> x.toLongOrNull() } || (curSet.category.isNotBlank() && it.category == curSet.category)) }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Pair<Reminder, LocalDate>?>(null) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val f = if (month) anchor.withDayOfMonth(1) else anchor.with(TemporalAdjusters.previousOrSame(if (repo.prefs.weekStartsSunday) DayOfWeek.SUNDAY else DayOfWeek.MONDAY))
            val l = if (month) f.plusMonths(1).minusDays(1) else f.plusDays(6)
            runCatching { app.napominator.data.writeText(ctx, uri, app.napominator.data.csvWithBom(repo.habitsCsv(rows, f, l))) }
                .onSuccess { android.widget.Toast.makeText(ctx, "Файл сохранён", android.widget.Toast.LENGTH_SHORT).show() }
                .onFailure { android.widget.Toast.makeText(ctx, "Ошибка: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    val first: LocalDate; val last: LocalDate
    if (month) { first = anchor.withDayOfMonth(1); last = first.plusMonths(1).minusDays(1) }
    else { first = anchor.with(TemporalAdjusters.previousOrSame(if (repo.prefs.weekStartsSunday) DayOfWeek.SUNDAY else DayOfWeek.MONDAY)); last = first.plusDays(6) }
    val occs by remember(first, last) { repo.occurrences(first.toEpochDay(), last.toEpochDay()) }.collectAsState(emptyList())
    val ms by remember(first, last) { repo.measurements(first.toEpochDay(), last.toEpochDay()) }.collectAsState(emptyList())
    val now = System.currentTimeMillis()
    val td = today()

    fun toggle(r: Reminder, day: LocalDate) {
        if (day.isAfter(td)) return
        if (r.kind == Kind.MEASURE) {
            val ts = if (day == td) System.currentTimeMillis() else dueMillis(day, 12 * 60)
            onMeasure(MeasureTarget(r, null, ts)); return
        }
        val slots = slotsFor(listOf(r), occs, day)
        val allDone = slots.isNotEmpty() && slots.all { it.state == OccState.DONE }
        scope.launch { slots.forEach { repo.setState(r.id, it.dueAt, if (allDone) (if (day < td) OccState.MISSED else OccState.PENDING) else OccState.DONE) } }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
            Text("Привычки", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        }
        Seg(listOf(false to "Неделя", true to "Месяц"), month, { month = it; anchor = today() }, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(curSet == null, { setId = 0 }, { Text("Все") })
            sets.forEach { st -> FilterChip(setId == st.id, { setId = st.id }, { Text(st.name) }) }
            IconButton({ setDialog = HabitSet(name = "") }) { Icon(Icons.Filled.Add, "Новый набор") }
            if (curSet != null) IconButton({ setDialog = curSet }) { Icon(Icons.Filled.Edit, "Изменить набор") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            IconButton({ anchor = if (month) anchor.minusMonths(1) else anchor.minusWeeks(1) }) { Icon(Icons.Filled.ChevronLeft, "Назад") }
            Text(
                if (month) "${monthNames[first.monthValue - 1]} ${first.year}" else "${first.dayOfMonth}–${last.dayOfMonth} ${Schedule.monthGen(last.monthValue)}",
                fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { anchor = today() },
            )
            IconButton({ anchor = if (month) anchor.plusMonths(1) else anchor.plusWeeks(1) }) { Icon(Icons.Filled.ChevronRight, "Вперёд") }
        }

        if (rows.isEmpty()) {
            Text("Пока нет активных напоминаний. Добавьте на вкладке «Сегодня».", color = ext.muted, modifier = Modifier.padding(16.dp))
        } else if (!month) {
            Card(shape = cardShape, border = BorderStroke(1.dp, ext.line), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(NAME_W))
                        (0..6).forEach { i ->
                            val d = first.plusDays(i.toLong())
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (d == td) ext.accentSoft else Color.Transparent).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(Schedule.DOW_SHORT[d.dayOfWeek.value - 1], fontSize = 11.sp, color = ext.muted, fontWeight = FontWeight.Bold)
                                Text("${d.dayOfMonth}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (d == td) ext.accentInk else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    rows.forEach { r ->
                        val cells = (0..6).map { cellFor(r, first.plusDays(it.toLong()), occs, ms, now) }
                        val pastDays = (0..6).map { first.plusDays(it.toLong()) }.filter { !it.isAfter(td) }
                        val slots = pastDays.flatMap { slotsOf(r, occs, it) }
                        val withData = cells.indices.count { cells[it].kind == CellKind.VALUE }
                        val subtitle = when {
                            r.kind == Kind.MEASURE -> if (withData == 0) "—" else "$withData дн. с данными"
                            slots.isEmpty() -> "—"
                            else -> "${slots.count { it.state == OccState.DONE }} из ${slots.size}"
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.width(NAME_W).clickable { onOpen(r.id) }.padding(end = 4.dp)) {
                                Text(r.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(subtitle, fontSize = 11.sp, color = ext.muted)
                            }
                            cells.forEachIndexed { i, c ->
                                val d = first.plusDays(i.toLong())
                                Box(Modifier.weight(1f).height(54.dp).background(if (d == td) ext.accentSoft.copy(alpha = 0.5f) else Color.Transparent), contentAlignment = Alignment.Center) {
                                    CellView(c, Modifier.combinedClickable(onClick = { toggle(r, d) }, onLongClick = { if (!d.isAfter(td)) dialog = r to d }))
                                }
                            }
                        }
                    }
                }
            }
            CellLegend()
        } else {
            val days = first.lengthOfMonth()
            Card(shape = cardShape, border = BorderStroke(1.dp, ext.line), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.width(72.dp))
                        Row(Modifier.weight(1f)) { (1..days).forEach { d -> Text(if (d == 1 || d % 5 == 0) "$d" else "", Modifier.weight(1f), fontSize = 8.sp, color = ext.muted, textAlign = TextAlign.Center, maxLines = 1, softWrap = false) } }
                    }
                    rows.forEach { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.title, Modifier.width(72.dp).clickable { onOpen(r.id) }, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                (1..days).forEach { d ->
                                    val day = first.withDayOfMonth(d)
                                    val c = cellFor(r, day, occs, ms, now)
                                    val (bg, bd) = heatColors(c.kind)
                                    Box(Modifier.weight(1f).aspectRatio(0.6f).clip(RoundedCornerShape(2.dp)).background(bg).then(if (day == td) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(2.dp)) else if (bd != null) Modifier.border(1.dp, bd, RoundedCornerShape(2.dp)) else Modifier))
                                }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(CellKind.DONE to "выполнено", CellKind.PART to "частично", CellKind.MISS to "пропущено", CellKind.SHIFT to "перенесено").forEach { (k, l) ->
                    val (bg, bd) = heatColors(k)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(bg).then(if (bd != null) Modifier.border(1.dp, bd, RoundedCornerShape(3.dp)) else Modifier))
                        Text(l, fontSize = 12.sp, color = ext.muted)
                    }
                }
            }
        }
        if (rows.isNotEmpty()) androidx.compose.material3.OutlinedButton({ exportLauncher.launch("привычки-${first}.csv") }, Modifier.fillMaxWidth()) { Text("Экспорт таблицы в CSV") }
        Spacer(Modifier.height(16.dp))
    }

    setDialog?.let { sd ->
        SetDialog(sd, reminders.filter { it.status != Status.ARCHIVED }, { setDialog = null }, { setDialog = null; setId = 0 }.let { onDeleted -> { sid: Long -> scope.launch { repo.deleteSet(sid) }; onDeleted() } }) { saved ->
            scope.launch { val nid = repo.saveSet(saved); setId = if (saved.id == 0L) nid else saved.id }; setDialog = null
        }
    }

    dialog?.let { (r, day) ->
        val slots = slotsFor(listOf(r), occs, day)
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("${r.title}, ${day.dayOfMonth} ${Schedule.monthGen(day.monthValue)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (slots.isEmpty()) Text("В этот день срабатываний нет.")
                    slots.forEach { s ->
                        Column {
                            Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.dueAt)) + " · " + stateLabel(s.state), fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton({ scope.launch { repo.setState(r.id, s.dueAt, OccState.DONE) } }) { Text("Выполнено") }
                                TextButton({ scope.launch { repo.setState(r.id, s.dueAt, OccState.SKIPPED) } }) { Text("Пропуск") }
                                TextButton({ scope.launch { repo.setState(r.id, s.dueAt, OccState.SHIFTED) } }) { Text("Перенос") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ dialog = null }) { Text("Готово") } },
        )
    }
}

private val NAME_W = 96.dp

private fun stateLabel(s: OccState?) = when (s) {
    OccState.DONE -> "выполнено"; OccState.SKIPPED -> "пропущено"; OccState.MISSED -> "не отмечено"; OccState.SHIFTED -> "перенесено"; else -> "ждёт"
}

@Composable
private fun heatColors(k: CellKind): Pair<Color, Color?> = when (k) {
    CellKind.DONE, CellKind.VALUE -> ext.ok to null
    CellKind.PART -> ext.warn.copy(alpha = 0.75f) to null
    CellKind.MISS -> ext.missSoft to ext.miss
    CellKind.SHIFT -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) to null
    else -> ext.surface2 to null
}

@Composable
fun CellView(c: Cell, modifier: Modifier = Modifier) {
    val (bg, fg) = when (c.kind) {
        CellKind.DONE -> ext.okSoft to ext.ok
        CellKind.MISS -> ext.missSoft to ext.miss
        CellKind.SHIFT, CellKind.PART -> ext.warnSoft to ext.warn
        CellKind.VALUE -> ext.accentSoft to ext.accentInk
        else -> Color.Transparent to ext.muted
    }
    val base = modifier.size(width = if (c.kind == CellKind.VALUE) 40.dp else 32.dp, height = 32.dp).clip(RoundedCornerShape(10.dp))
    Box(
        base.background(bg).then(if (c.kind == CellKind.PEND) Modifier.border(2.dp, ext.muted, RoundedCornerShape(10.dp)).semantics { contentDescription = "ожидает" } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (c.kind) {
            CellKind.DONE -> Icon(Icons.Filled.Check, "выполнено", Modifier.size(18.dp), tint = fg)
            CellKind.MISS -> Icon(Icons.Filled.Remove, "пропущено", Modifier.size(18.dp), tint = fg)
            CellKind.SHIFT -> Icon(Icons.AutoMirrored.Filled.ArrowForward, "перенесено", Modifier.size(18.dp), tint = fg)
            CellKind.PART, CellKind.VALUE -> Text(c.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1, softWrap = false)
            CellKind.NONE -> Text("·", color = ext.line)
            CellKind.PEND -> {}
        }
    }
}

@Composable
private fun CellLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(Cell(CellKind.DONE) to "готово", Cell(CellKind.MISS) to "пропуск", Cell(CellKind.SHIFT) to "перенос", Cell(CellKind.PART, "2/3") to "частично", Cell(CellKind.PEND) to "ждёт").forEach { (c, l) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { CellView(c, Modifier.size(20.dp)) }
                Text(l, fontSize = 11.sp, color = ext.muted)
            }
        }
    }
    Text("Тап по ячейке — отметить день, долгий тап — выбрать состояние. Непроставленное в конце дня становится «пропуском».", fontSize = 12.sp, color = ext.muted)
}


@Composable
private fun SetDialog(initial: HabitSet, all: List<Reminder>, onDismiss: () -> Unit, onDelete: (Long) -> Unit, onSave: (HabitSet) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var category by remember { mutableStateOf(initial.category) }
    var ids by remember { mutableStateOf(initial.reminderIds.split(',').mapNotNull { it.toLongOrNull() }.toSet()) }
    val cats = all.map { it.category }.filter { it.isNotBlank() }.distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Новый набор" else "Набор") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (cats.isNotEmpty()) {
                    Text("Добавить всю категорию:", fontSize = 12.sp, color = ext.muted)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        cats.forEach { c -> FilterChip(category == c, { category = if (category == c) "" else c }, { Text(c) }) }
                    }
                }
                Text("Или отдельные напоминания:", fontSize = 12.sp, color = ext.muted)
                all.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { ids = if (r.id in ids) ids - r.id else ids + r.id }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(r.id in ids, null); Text(r.title, Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton({ if (name.isNotBlank()) onSave(initial.copy(name = name.trim(), category = category, reminderIds = ids.joinToString(","))) }) { Text("Сохранить") } },
        dismissButton = { Row { if (initial.id != 0L) TextButton({ onDelete(initial.id) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }; TextButton(onDismiss) { Text("Отмена") } } },
    )
}
