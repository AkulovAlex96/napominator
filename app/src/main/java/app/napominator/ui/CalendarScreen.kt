package app.napominator.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.napominator.data.OccState
import app.napominator.data.Reminder
import app.napominator.data.Repo
import app.napominator.data.Schedule
import app.napominator.data.Slot
import app.napominator.data.slotsFor
import app.napominator.data.tagList
import app.napominator.data.today
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale

private val monthTitles = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")
private val dowFull = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")

private enum class CalMode { MONTH, WEEK, DAY }

/** Фильтр «категория или #тег»: null — без фильтра. */
fun matchesFilter(r: Reminder, filter: String?): Boolean = filter == null ||
    (filter.startsWith("#") && filter.drop(1) in r.tagList) || (!filter.startsWith("#") && r.category == filter)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(repo: Repo, allReminders: List<Reminder>, onOpen: (Long) -> Unit, onAdd: (LocalDate) -> Unit) {
    var mode by remember { mutableStateOf(CalMode.MONTH) }
    var selected by remember { mutableStateOf(today()) }
    var filter by remember { mutableStateOf<String?>(null) }
    val reminders = allReminders.filter { matchesFilter(it, filter) }
    val td = today()
    val filters = (allReminders.map { it.category }.filter { it.isNotBlank() } + allReminders.flatMap { r -> r.tagList.map { "#$it" } }).distinct()

    val first: LocalDate; val last: LocalDate
    when (mode) {
        CalMode.MONTH -> { first = YearMonth.from(selected).atDay(1); last = YearMonth.from(selected).atEndOfMonth() }
        CalMode.WEEK -> { first = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); last = first.plusDays(6) }
        CalMode.DAY -> { first = selected; last = selected }
    }
    val occs by remember(first, last) { repo.occurrences(first.toEpochDay(), last.toEpochDay()) }.collectAsState(emptyList())

    fun step(dir: Long) { selected = when (mode) { CalMode.MONTH -> selected.plusMonths(dir); CalMode.WEEK -> selected.plusWeeks(dir); CalMode.DAY -> selected.plusDays(dir) } }
    val title = when (mode) {
        CalMode.MONTH -> "${monthTitles[selected.monthValue - 1]} ${selected.year}"
        CalMode.WEEK -> "${first.dayOfMonth} ${Schedule.monthGen(first.monthValue)} – ${last.dayOfMonth} ${Schedule.monthGen(last.monthValue)}"
        CalMode.DAY -> "${selected.dayOfMonth} ${Schedule.monthGen(selected.monthValue)}, ${dowFull[selected.dayOfWeek.value - 1]}"
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Календарь", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
            Seg(listOf(CalMode.MONTH to "Месяц", CalMode.WEEK to "Неделя", CalMode.DAY to "День"), mode, { mode = it }, Modifier.fillMaxWidth())
            if (filters.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                filters.forEach { f -> FilterChip(filter == f, { filter = if (filter == f) null else f }, { Text(f, fontSize = 12.sp) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                IconButton({ step(-1) }) { Icon(Icons.Filled.ChevronLeft, "Назад") }
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { selected = td })
                IconButton({ step(1) }) { Icon(Icons.Filled.ChevronRight, "Вперёд") }
            }
            when (mode) {
                CalMode.MONTH -> {
                    MonthGrid(reminders, occs, YearMonth.from(selected), selected, { selected = it }, onAdd)
                    SectionLabel("${selected.dayOfMonth} ${Schedule.monthGen(selected.monthValue)}")
                    DayList(slotsFor(reminders, occs.filter { it.epochDay == selected.toEpochDay() }, selected), onOpen)
                }
                CalMode.WEEK -> (0..6).forEach { i ->
                    val d = first.plusDays(i.toLong())
                    Text(
                        "${dowFull[i].replaceFirstChar { it.uppercase() }}, ${d.dayOfMonth} ${Schedule.monthGen(d.monthValue)}",
                        Modifier.padding(start = 4.dp, top = 6.dp).clickable { selected = d; mode = CalMode.DAY }, fontWeight = FontWeight.Bold,
                        color = if (d == td) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    DayList(slotsFor(reminders, occs.filter { it.epochDay == d.toEpochDay() }, d), onOpen)
                }
                CalMode.DAY -> DayList(slotsFor(reminders, occs.filter { it.epochDay == selected.toEpochDay() }, selected), onOpen)
            }
            Box(Modifier.height(90.dp))
        }
        FloatingActionButton({ onAdd(selected) }, Modifier.align(Alignment.BottomEnd).padding(20.dp), containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Icon(Icons.Filled.Add, "Новое напоминание на выбранную дату")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthGrid(reminders: List<Reminder>, occs: List<app.napominator.data.Occurrence>, ym: YearMonth, selected: LocalDate, onSelect: (LocalDate) -> Unit, onAdd: (LocalDate) -> Unit) {
    val td = today()
    val offset = ym.atDay(1).dayOfWeek.value - 1
    Card(Modifier.fillMaxWidth(), shape = cardShape, border = BorderStroke(1.dp, ext.line), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(8.dp)) {
            Row { Schedule.DOW_SHORT.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = ext.muted, fontWeight = FontWeight.Bold) } }
            (0 until (offset + ym.lengthOfMonth() + 6) / 7).forEach { w ->
                Row {
                    (0..6).forEach { c ->
                        val d = w * 7 + c - offset + 1
                        if (d < 1 || d > ym.lengthOfMonth()) Box(Modifier.weight(1f).height(52.dp))
                        else {
                            val day = ym.atDay(d)
                            val slots = slotsFor(reminders, occs.filter { it.epochDay == day.toEpochDay() }, day)
                            val sel = day == selected
                            Column(
                                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp))
                                    .background(if (sel) MaterialTheme.colorScheme.primary else if (day == td) ext.accentSoft else Color.Transparent)
                                    .combinedClickable(onClick = { onSelect(day) }, onLongClick = { onAdd(day) }),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Text("$d", fontWeight = FontWeight.SemiBold, color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                Row(Modifier.height(8.dp).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    slots.take(3).forEach { s ->
                                        val bad = s.state == OccState.MISSED || s.state == OccState.SKIPPED
                                        val col = when { sel -> MaterialTheme.colorScheme.onPrimary; s.state == OccState.DONE -> ext.ok; bad -> ext.miss; else -> ext.muted }
                                        Box(Modifier.size(5.dp).background(col, if (bad) RoundedCornerShape(1.dp) else CircleShape))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayList(slots: List<Slot>, onOpen: (Long) -> Unit) {
    if (slots.isEmpty()) Text("Ничего не запланировано.", color = ext.muted, modifier = Modifier.padding(4.dp), fontSize = 14.sp)
    slots.forEach { s ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).clickable { onOpen(s.reminder.id) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.dueAt)), fontWeight = FontWeight.Bold)
            Text(s.reminder.title, Modifier.weight(1f))
            when (s.state) {
                OccState.DONE -> MiniChip("выполнено", null, ext.okSoft, ext.ok)
                OccState.SKIPPED, OccState.MISSED -> MiniChip("пропущено", null, ext.missSoft, ext.miss)
                OccState.SHIFTED -> MiniChip("перенесено", null, ext.warnSoft, ext.warn)
                else -> {}
            }
        }
    }
}
