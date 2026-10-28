package app.napominator.data

import java.time.LocalDate
import java.util.Locale

/** Запланированное (или фактическое, для условных напоминаний) срабатывание в конкретный день. */
data class Slot(
    val reminder: Reminder,
    val dueAt: Long,
    val state: OccState?,
    val comment: String? = null,
    val checked: Set<Int> = emptySet(),
)

private fun Occurrence.checkedSet() = checked.split(',').mapNotNull { it.toIntOrNull() }.toSet()

fun slotsOf(r: Reminder, occs: List<Occurrence>, day: LocalDate): List<Slot> {
    val ed = day.toEpochDay()
    if (r.triggerMode != Trigger.TIME) {
        return occs.filter { it.reminderId == r.id && it.epochDay == ed }.sortedBy { it.dueAt }
            .map { Slot(r, it.dueAt, it.state, it.comment, it.checkedSet()) }
    }
    return r.sched.minutesOn(day).mapNotNull { m ->
        val due = dueMillis(day, m)
        val o = occs.firstOrNull { it.reminderId == r.id && it.dueAt == due }
        if (o == null && due < r.createdAt) null else Slot(r, due, o?.state, o?.comment, o?.checkedSet() ?: emptySet())
    }
}

fun slotsFor(reminders: List<Reminder>, occs: List<Occurrence>, day: LocalDate): List<Slot> =
    reminders.filter { it.status == Status.ACTIVE || it.isArchivedOnce() }.flatMap { slotsOf(it, occs, day) }
        .filter { it.reminder.status == Status.ACTIVE || it.state != null }.sortedBy { it.dueAt }

/** Автоархивированное одноразовое дело: остаётся видно в тот день, когда его выполнили. */
private fun Reminder.isArchivedOnce() = status == Status.ARCHIVED && triggerMode == Trigger.TIME && sched.type == ScheduleType.ONCE

enum class CellKind { NONE, DONE, MISS, SHIFT, PART, PEND, VALUE }
data class Cell(val kind: CellKind, val text: String = "")

fun fmt(v: Double): String =
    if (v == Math.floor(v) && Math.abs(v) < 1e9) v.toLong().toString() else String.format(Locale("ru"), "%.1f", v)

/** Что показать в ячейке таблицы привычек для напоминания в этот день. */
fun cellOf(r: Reminder, day: LocalDate, occs: List<Occurrence>, ms: List<Measurement>, now: Long): Cell {
    val slots = slotsOf(r, occs, day)
    val cfg = r.measureConfig
    val dayMs = ms.filter { it.reminderId == r.id && it.epochDay == day.toEpochDay() }
    if (r.kind == Kind.MEASURE && dayMs.isNotEmpty()) {
        val first = cfg?.fields?.firstOrNull()?.name
        val v = if (cfg?.accumulative == true) dayMs.filter { it.field == first }.sumOf { it.value }
        else dayMs.filter { it.field == first }.maxByOrNull { it.ts }?.value ?: dayMs.last().value
        // Накопительная величина с целью: пока цель не достигнута, день считается «частично».
        if (cfg?.accumulative == true && cfg.goal != null && v < cfg.goal) return Cell(CellKind.PART, fmt(v))
        return Cell(CellKind.VALUE, fmt(v))
    }
    if (slots.isEmpty()) return Cell(CellKind.NONE)
    val done = slots.count { it.state == OccState.DONE }
    val n = slots.size
    return when {
        done == n -> Cell(CellKind.DONE)
        done > 0 -> Cell(CellKind.PART, "$done/$n")
        slots.any { it.checked.isNotEmpty() } && r.checkItems.isNotEmpty() ->
            Cell(CellKind.PART, "${slots.sumOf { it.checked.size }}/${n * r.checkItems.size}")
        slots.any { it.state == OccState.SHIFTED } -> Cell(CellKind.SHIFT)
        slots.all { it.state == OccState.SKIPPED || it.state == OccState.MISSED } -> Cell(CellKind.MISS)
        day.isAfter(today()) -> Cell(CellKind.NONE)
        else -> Cell(CellKind.PEND)
    }
}

fun cellFor(r: Reminder, day: LocalDate, occs: List<Occurrence>, ms: List<Measurement>, now: Long) = cellOf(r, day, occs, ms, now)
