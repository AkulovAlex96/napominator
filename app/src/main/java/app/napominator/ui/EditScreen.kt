package app.napominator.ui

import app.napominator.data.fmt
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.napominator.data.Condition
import app.napominator.data.Trigger
import app.napominator.data.conds
import app.napominator.data.Kind
import app.napominator.data.MeasureConfig
import app.napominator.data.MeasureField
import app.napominator.data.Reminder
import app.napominator.data.Repo
import app.napominator.data.Schedule
import app.napominator.data.ScheduleType
import app.napominator.data.Status
import app.napominator.data.measureConfig
import app.napominator.data.sched
import app.napominator.data.toJson
import app.napominator.data.today
import kotlinx.coroutines.launch

private enum class Mode(val label: String) { WEEKDAYS("По дням недели"), ONCE("Один раз"), EVERY("Каждые N…"), MONTH("Дни месяца"), NTH("N-й день недели") }
private enum class Per(val label: String) { MINUTES("минут"), HOURS("часов"), DAYS("дней"), WEEKS("недель"), MONTHS("месяцев") }

private class EditState(val orig: Reminder?, presetDay: Long? = null) {
    private val s: Schedule = orig?.sched ?: Schedule(startEpochDay = today().toEpochDay(), onceEpochDay = today().toEpochDay())
    private val cfg: MeasureConfig = orig?.measureConfig ?: MeasureConfig()

    var title by mutableStateOf(orig?.title ?: "")
    var category by mutableStateOf(orig?.category ?: "")
    var tags by mutableStateOf(orig?.tags ?: "")
    var checklist by mutableStateOf(orig?.checklist ?: "")
    var trigger by mutableStateOf(orig?.triggerMode ?: Trigger.TIME)
    val conds = mutableStateListOf<Condition>().apply { addAll(orig?.conds ?: emptyList()) }
    var note by mutableStateOf(orig?.note ?: "")
    var kind by mutableStateOf(orig?.kind ?: Kind.TASK)
    var priority by mutableIntStateOf(orig?.priority ?: 0)
    var repeatOn by mutableStateOf((orig?.repeatEveryMin ?: 0) > 0)
    var repeatEvery by mutableStateOf((orig?.repeatEveryMin?.takeIf { it > 0 } ?: 10).toString())
    var repeatMax by mutableStateOf((orig?.repeatMax?.takeIf { it > 0 } ?: 6).toString())

    val fields = mutableStateListOf<MeasureField>().apply { addAll(cfg.fields) }
    var accumulative by mutableStateOf(cfg.accumulative)
    var goal by mutableStateOf(cfg.goal?.let { fmt(it) } ?: "")

    var mode by mutableStateOf(
        if (orig == null && presetDay != null) Mode.ONCE else when (s.type) {
            ScheduleType.DAYS_OF_WEEK -> Mode.WEEKDAYS
            ScheduleType.ONCE -> Mode.ONCE
            ScheduleType.MONTH_DAYS -> Mode.MONTH
            ScheduleType.MONTH_NTH_WEEKDAY -> Mode.NTH
            else -> Mode.EVERY
        }
    )
    var unit by mutableStateOf(
        when (s.type) {
            ScheduleType.INTERVAL -> if (s.intervalMinutes % 60 == 0) Per.HOURS else Per.MINUTES
            ScheduleType.EVERY_N_WEEKS -> Per.WEEKS
            ScheduleType.EVERY_N_MONTHS -> Per.MONTHS
            else -> Per.DAYS
        }
    )
    var every by mutableStateOf(
        (if (s.type == ScheduleType.INTERVAL) (if (s.intervalMinutes % 60 == 0) s.intervalMinutes / 60 else s.intervalMinutes) else s.every).toString()
    )
    val times = mutableStateListOf<Int>().apply { addAll(s.times) }
    var days by mutableStateOf(s.daysOfWeek)
    var monthDays by mutableStateOf(s.monthDays.toSet())
    var nth by mutableIntStateOf(s.nth)
    var nthDow by mutableIntStateOf(s.nthDow)
    var onceDay by mutableLongStateOf(if (orig == null && presetDay != null) presetDay else s.onceEpochDay.takeIf { it > 0 } ?: today().toEpochDay())
    var windowStart by mutableIntStateOf(s.windowStart)
    var windowEnd by mutableIntStateOf(s.windowEnd)
    var untilOn by mutableStateOf(s.untilEpochDay != null)
    var untilDay by mutableLongStateOf(s.untilEpochDay ?: today().plusMonths(1).toEpochDay())

    fun buildSchedule(): Schedule? {
        val n = every.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val start = if (orig != null) s.startEpochDay else today().toEpochDay()
        val until = if (untilOn) untilDay else null
        val base = Schedule(times = times.sorted(), startEpochDay = start, untilEpochDay = until)
        if (trigger == Trigger.CONDITION) return if (conds.isEmpty()) null else base.copy(type = ScheduleType.DAYS_OF_WEEK, times = emptyList())
        if (trigger == Trigger.BOTH) return if (conds.isEmpty() || days.isEmpty() || windowEnd <= windowStart) null
            else base.copy(type = ScheduleType.DAYS_OF_WEEK, times = emptyList(), daysOfWeek = days, windowStart = windowStart, windowEnd = windowEnd)
        return when (mode) {
            Mode.WEEKDAYS -> if (days.isEmpty() || times.isEmpty()) null else base.copy(type = ScheduleType.DAYS_OF_WEEK, daysOfWeek = days)
            Mode.ONCE -> if (times.isEmpty()) null else base.copy(type = ScheduleType.ONCE, onceEpochDay = onceDay)
            Mode.MONTH -> if (monthDays.isEmpty() || times.isEmpty()) null else base.copy(type = ScheduleType.MONTH_DAYS, monthDays = monthDays.toList())
            Mode.NTH -> if (times.isEmpty()) null else base.copy(type = ScheduleType.MONTH_NTH_WEEKDAY, nth = nth, nthDow = nthDow)
            Mode.EVERY -> when (unit) {
                Per.MINUTES, Per.HOURS -> if (windowEnd <= windowStart) null else
                    base.copy(type = ScheduleType.INTERVAL, intervalMinutes = if (unit == Per.HOURS) n * 60 else n, windowStart = windowStart, windowEnd = windowEnd)
                Per.DAYS -> if (times.isEmpty()) null else base.copy(type = ScheduleType.EVERY_N_DAYS, every = n)
                Per.WEEKS -> if (times.isEmpty() || days.isEmpty()) null else base.copy(type = ScheduleType.EVERY_N_WEEKS, every = n, daysOfWeek = days)
                Per.MONTHS -> if (times.isEmpty()) null else base.copy(type = ScheduleType.EVERY_N_MONTHS, every = n)
            }
        }
    }

    fun build(): Reminder? {
        val sch = buildSchedule() ?: return null
        if (title.isBlank()) return null
        val measure = if (kind == Kind.MEASURE) {
            val fs = fields.filter { it.name.isNotBlank() }
            if (fs.isEmpty()) return null
            MeasureConfig(fs, accumulative, goal.replace(',', '.').toDoubleOrNull()).toJson()
        } else null
        val rep = if (repeatOn) repeatEvery.toIntOrNull()?.coerceAtLeast(1) ?: 10 else 0
        return (orig ?: Reminder(title = "", schedule = "")).copy(
            title = title.trim(), note = note.trim(), kind = kind, priority = priority,
            category = category.trim(), tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", "),
            checklist = checklist.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"),
            triggerMode = trigger, conditions = if (trigger == Trigger.TIME || conds.isEmpty()) null else conds.toList().toJson(),
            schedule = sch.toJson(), measure = measure,
            repeatEveryMin = rep, repeatMax = if (rep > 0) repeatMax.toIntOrNull()?.coerceIn(1, 60) ?: 6 else 0,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(repo: Repo, id: Long, presetDay: Long? = null, onClose: () -> Unit) {
    var st by remember { mutableStateOf<EditState?>(null) }
    LaunchedEffect(id) { st = EditState(if (id > 0) repo.get(id) else null, presetDay) }
    val e = st ?: return
    val scope = rememberCoroutineScope()
    var timeEdit by remember { mutableStateOf<Pair<String, Int>?>(null) } // target to current minutes
    var datePick by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    var condEdit by remember { mutableStateOf<Pair<Int, Condition?>?>(null) }
    var categories by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) { categories = repo.db.reminders().all().map { it.category }.filter { it.isNotBlank() }.distinct().sorted() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Filled.Close, "Закрыть") }
            Text(if (id > 0) "Редактирование" else "Новое напоминание", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(e.title, { e.title = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true, isError = tried && e.title.isBlank())
            OutlinedTextField(e.note, { e.note = it }, Modifier.fillMaxWidth(), label = { Text("Заметка") })
            OutlinedTextField(e.category, { e.category = it }, Modifier.fillMaxWidth(), label = { Text("Категория") }, singleLine = true)
            if (categories.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                categories.forEach { c -> FilterChip(e.category == c, { e.category = if (e.category == c) "" else c }, { Text(c, fontSize = 12.sp) }) }
            }
            OutlinedTextField(e.tags, { e.tags = it }, Modifier.fillMaxWidth(), label = { Text("Теги через запятую") }, singleLine = true)
            OutlinedTextField(e.checklist, { e.checklist = it }, Modifier.fillMaxWidth(), label = { Text("Чек-лист (по пункту в строке)") })
            Seg(listOf(Kind.TASK to "Задача", Kind.MEASURE to "Измерение"), e.kind, { e.kind = it }, Modifier.fillMaxWidth())

            if (e.kind == Kind.MEASURE) {
                SectionLabel("Что измеряем")
                e.fields.forEachIndexed { i, f ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(f.name, { e.fields[i] = f.copy(name = it) }, Modifier.weight(1.3f), label = { Text("Величина") }, singleLine = true)
                        OutlinedTextField(f.unit, { e.fields[i] = f.copy(unit = it) }, Modifier.weight(1f), label = { Text("Единица") }, singleLine = true)
                        if (e.fields.size > 1) IconButton({ e.fields.removeAt(i) }) { Icon(Icons.Filled.Close, "Убрать поле") }
                    }
                }
                TextButton({ e.fields.add(MeasureField("")) }) { Icon(Icons.Filled.Add, null); Text(" Ещё поле (например, пульс)") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Накопительная величина", fontWeight = FontWeight.SemiBold); Text("значения за день суммируются (вода, шаги)", fontSize = 12.sp, color = ext.muted) }
                    Switch(e.accumulative, { e.accumulative = it })
                }
                if (e.accumulative) OutlinedTextField(e.goal, { e.goal = it }, Modifier.fillMaxWidth(), label = { Text("Цель на день") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }

            SectionLabel("Когда")
            Seg(listOf(Trigger.TIME to "Время", Trigger.CONDITION to "Условие", Trigger.BOTH to "Усл. + время"), e.trigger, { e.trigger = it }, Modifier.fillMaxWidth())
            if (e.trigger == Trigger.TIME) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mode.entries.forEach { m -> FilterChip(e.mode == m, { e.mode = m }, { Text(m.label) }) }
                }
                ScheduleFields(e, onTime = { target, v -> timeEdit = target to v }, onDate = { datePick = it })
            } else {
                if (e.trigger == Trigger.BOTH) {
                    Text("Срабатывает по условию, только в эти дни и часы:", fontSize = 13.sp, color = ext.muted)
                    DaysRow(e)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("С", color = ext.muted)
                        OutlinedButton({ timeEdit = "start" to e.windowStart }) { Text(Schedule.fmtMinutes(e.windowStart)) }
                        Text("до", color = ext.muted)
                        OutlinedButton({ timeEdit = "end" to e.windowEnd }) { Text(Schedule.fmtMinutes(e.windowEnd)) }
                    }
                }
                e.conds.forEachIndexed { i, c ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).clickable { condEdit = i to c }.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.describe(), Modifier.weight(1f), fontSize = 14.sp)
                        IconButton({ e.conds.removeAt(i) }) { Icon(Icons.Filled.Close, "Убрать условие") }
                    }
                    if (i < e.conds.lastIndex) Text("или", fontSize = 12.sp, color = ext.muted, modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton({ condEdit = -1 to null }) { Icon(Icons.Filled.Add, null); Text(" условие") }
                ConditionPermissions(e.conds.toList())
            }

            SectionLabel("Уведомление")
            Seg(listOf(0 to "Обычный", 1 to "Важный", 2 to "Критичный"), e.priority, { e.priority = it }, Modifier.fillMaxWidth())
            if (e.priority == 2) Text("Откроется на весь экран поверх блокировки, со звуком будильника.", fontSize = 12.sp, color = ext.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Настойчивый повтор", fontWeight = FontWeight.SemiBold); Text("повторять, пока не отметите", fontSize = 12.sp, color = ext.muted) }
                Switch(e.repeatOn, { e.repeatOn = it })
            }
            if (e.repeatOn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(e.repeatEvery, { e.repeatEvery = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Каждые, мин") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(e.repeatMax, { e.repeatMax = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Не более раз") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            if (tried && e.build() == null) Text(if (e.title.isBlank()) "Введите название." else "Проверьте расписание и условия: чего-то не хватает.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            if (id > 0) {
                SectionLabel("Управление")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val paused = e.orig?.status == Status.PAUSED
                    OutlinedButton({ scope.launch { e.orig?.let { repo.setStatus(it, if (paused) Status.ACTIVE else Status.PAUSED) }; onClose() } }) { Text(if (paused) "Возобновить" else "Пауза") }
                    OutlinedButton({ scope.launch { e.orig?.let { repo.setStatus(it, Status.ARCHIVED) }; onClose() } }) { Text("В архив") }
                    OutlinedButton({ scope.launch { e.build()?.let { repo.save(it.copy(id = 0, title = it.title + " (копия)", createdAt = System.currentTimeMillis())) }; onClose() } }) { Text("Копия") }
                    TextButton({ confirmDelete = true }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.padding(16.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClose) { Text("Отмена") }
            Button({
                tried = true
                val r = e.build()
                if (r != null) scope.launch { repo.save(r); onClose() }
            }, Modifier.weight(1f)) { Text("Сохранить") }
        }
    }

    condEdit?.let { (idx, init) ->
        ConditionDialog(init, { condEdit = null }) { c -> if (idx >= 0) e.conds[idx] = c else e.conds.add(c); condEdit = null }
    }
    timeEdit?.let { (target, cur) ->
        TimeDialog(cur, { timeEdit = null }) { m ->
            when {
                target == "start" -> e.windowStart = m
                target == "end" -> e.windowEnd = m
                target == "new" -> if (m !in e.times) e.times.add(m)
                target.startsWith("edit:") -> { val old = target.removePrefix("edit:").toInt(); e.times.remove(old); if (m !in e.times) e.times.add(m) }
            }
            timeEdit = null
        }
    }
    datePick?.let { which ->
        val init = if (which == "once") e.onceDay else e.untilDay
        val ps = rememberDatePickerState(initialSelectedDateMillis = init * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { datePick = null },
            confirmButton = { TextButton({ ps.selectedDateMillis?.let { ms -> val d = ms / 86_400_000L; if (which == "once") e.onceDay = d else e.untilDay = d }; datePick = null }) { Text("OK") } },
            dismissButton = { TextButton({ datePick = null }) { Text("Отмена") } },
        ) { DatePicker(ps) }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить напоминание?") }, text = { Text("Вместе с историей срабатываний. Измерения останутся в экспорте только если вы его уже сделали.") },
        confirmButton = { TextButton({ scope.launch { repo.delete(id); onClose() } }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleFields(e: EditState, onTime: (String, Int) -> Unit, onDate: (String) -> Unit) {
    val showTimes = !(e.mode == Mode.EVERY && (e.unit == Per.MINUTES || e.unit == Per.HOURS))
    when (e.mode) {
        Mode.WEEKDAYS -> DaysRow(e)
        Mode.ONCE -> OutlinedButton({ onDate("once") }) { Text(dateLabel(e.onceDay)) }
        Mode.MONTH -> {
            Text("Числа месяца", fontSize = 12.sp, color = ext.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..31).forEach { d -> FilterChip(d in e.monthDays, { e.monthDays = if (d in e.monthDays) e.monthDays - d else e.monthDays + d }, { Text("$d") }) }
                FilterChip(-1 in e.monthDays, { e.monthDays = if (-1 in e.monthDays) e.monthDays - -1 else e.monthDays + -1 }, { Text("последний") })
            }
        }
        Mode.NTH -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "1-й", 2 to "2-й", 3 to "3-й", 4 to "4-й", -1 to "последний").forEach { (v, l) -> FilterChip(e.nth == v, { e.nth = v }, { Text(l) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Schedule.DOW_SHORT.forEachIndexed { i, l -> FilterChip(e.nthDow == i + 1, { e.nthDow = i + 1 }, { Text(l) }) }
            }
        }
        Mode.EVERY -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(e.every, { e.every = it.filter(Char::isDigit) }, Modifier.weight(0.6f), label = { Text("Каждые") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                FlowRow(Modifier.weight(1.6f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Per.entries.forEach { u -> FilterChip(e.unit == u, { e.unit = u }, { Text(u.label, fontSize = 12.sp) }) }
                }
            }
            if (e.unit == Per.WEEKS) DaysRow(e)
            if (e.unit == Per.MINUTES || e.unit == Per.HOURS) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("С", color = ext.muted)
                    OutlinedButton({ onTime("start", e.windowStart) }) { Text(Schedule.fmtMinutes(e.windowStart)) }
                    Text("до", color = ext.muted)
                    OutlinedButton({ onTime("end", e.windowEnd) }) { Text(Schedule.fmtMinutes(e.windowEnd)) }
                }
            }
        }
    }
    if (showTimes) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            e.times.sorted().forEach { m ->
                InputChip(false, { onTime("edit:$m", m) }, { Text(Schedule.fmtMinutes(m), fontWeight = FontWeight.Bold) },
                    trailingIcon = { Icon(Icons.Filled.Close, "Убрать время", Modifier.clickableNoRipple { e.times.remove(m) }) })
            }
            OutlinedButton({ onTime("new", 9 * 60) }) { Icon(Icons.Filled.Add, null); Text(" время") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Закончить повтор", Modifier.weight(1f))
        if (e.untilOn) TextButton({ onDate("until") }) { Text(dateLabel(e.untilDay)) }
        Switch(e.untilOn, { e.untilOn = it })
    }
}

@Composable
private fun DaysRow(e: EditState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Schedule.DOW_SHORT.forEachIndexed { i, l ->
            val d = i + 1
            FilterChip(d in e.days, { e.days = if (d in e.days) e.days - d else e.days + d }, { Text(l, fontSize = 12.sp, maxLines = 1) }, Modifier.weight(1f))
        }
    }
}

private fun dateLabel(epochDay: Long): String {
    val d = java.time.LocalDate.ofEpochDay(epochDay)
    return "${d.dayOfMonth} ${Schedule.monthGen(d.monthValue)} ${d.year}"
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initialMinutes: Int, onDismiss: () -> Unit, onOk: (Int) -> Unit) {
    val ts = rememberTimePickerState(initialMinutes / 60, initialMinutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton({ onOk(ts.hour * 60 + ts.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
        text = { TimePicker(ts) },
    )
}
