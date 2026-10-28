package app.napominator.data

import android.content.Context
import androidx.room.withTransaction
import app.napominator.alarm.Alarms
import app.napominator.alarm.Conditions
import app.napominator.alarm.Notifier
import app.napominator.widget.Widgets
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

val Reminder.sched: Schedule get() = AppJson.decodeFromString(schedule)
val Reminder.measureConfig: MeasureConfig? get() = measure?.let { AppJson.decodeFromString(it) }
val Reminder.conds: List<Condition> get() = conditions?.let { runCatching { AppJson.decodeFromString<List<Condition>>(it) }.getOrNull() } ?: emptyList()
val Reminder.checkItems: List<String> get() = checklist.lines().map { it.trim() }.filter { it.isNotEmpty() }
val Reminder.tagList: List<String> get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
/** Описание для списков: расписание или условия. */
fun Reminder.describe(): String = when (triggerMode) {
    Trigger.TIME -> sched.describe()
    Trigger.CONDITION -> conds.joinToString(" или ") { it.describe() }
    Trigger.BOTH -> conds.joinToString(" или ") { it.describe() } + " · " + sched.daysOfWeek.sorted().joinToString(",") { Schedule.DOW_SHORT[it - 1] } +
        " ${Schedule.fmtMinutes(sched.windowStart)}–${Schedule.fmtMinutes(sched.windowEnd)}"
}
fun Schedule.toJson(): String = AppJson.encodeToString(this)
fun MeasureConfig.toJson(): String = AppJson.encodeToString(this)
fun List<Condition>.toJson(): String = AppJson.encodeToString(this)

fun zone(): ZoneId = ZoneId.systemDefault()
fun dueMillis(day: LocalDate, minutes: Int): Long =
    day.atStartOfDay(zone()).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
fun epochDayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate().toEpochDay()
fun today(): LocalDate = LocalDate.now(zone())

class Repo(private val ctx: Context, val db: AppDb, val prefs: Prefs) {
    val reminders: Flow<List<Reminder>> = db.reminders().observeAll()
    val sets: Flow<List<HabitSet>> = db.sets().observeAll()

    fun occurrences(from: Long, to: Long): Flow<List<Occurrence>> = db.occurrences().observeRange(from, to)
    fun measurementsFor(rid: Long): Flow<List<Measurement>> = db.measurements().observeFor(rid)
    fun measurements(from: Long, to: Long): Flow<List<Measurement>> = db.measurements().observeRange(from, to)
    fun historyFor(rid: Long): Flow<List<Occurrence>> = db.occurrences().observeFor(rid, 60)

    suspend fun get(id: Long) = db.reminders().get(id)

    suspend fun save(r: Reminder): Long {
        val id = db.reminders().upsert(r)
        val saved = r.copy(id = id)
        db.occurrences().deleteFuturePending(id, System.currentTimeMillis())
        prefs.setLastFired(id, 0)
        if (saved.status == Status.ACTIVE && saved.triggerMode != Trigger.CONDITION && saved.triggerMode != Trigger.BOTH) Alarms.scheduleNext(ctx, saved) else Alarms.cancel(ctx, id)
        Conditions.sync(ctx, db.reminders().active())
        Widgets.refresh(ctx)
        return id
    }

    suspend fun delete(id: Long) {
        Alarms.cancel(ctx, id)
        Notifier.cancel(ctx, id)
        db.occurrences().deleteFor(id)
        db.reminders().delete(id)
        Conditions.sync(ctx, db.reminders().active())
        Widgets.refresh(ctx)
    }

    suspend fun setStatus(r: Reminder, s: Status) = save(r.copy(status = s))

    /** Поставить состояние срабатывания; если строки ещё нет (будущее или таблица), создать. */
    suspend fun setState(rid: Long, dueAt: Long, state: OccState, comment: String? = null) {
        val dao = db.occurrences()
        val now = System.currentTimeMillis()
        val resolved = if (state == OccState.PENDING) null else now
        val existing = dao.find(rid, dueAt)
        if (existing == null) {
            dao.insertIgnore(Occurrence(reminderId = rid, dueAt = dueAt, epochDay = epochDayOf(dueAt), state = state, resolvedAt = resolved, comment = comment))
        } else {
            dao.update(existing.copy(state = state, resolvedAt = resolved, comment = comment ?: existing.comment))
        }
        if (state != OccState.PENDING) {
            Alarms.cancelFollowUp(ctx, rid)
            Notifier.cancel(ctx, rid)
        }
        db.reminders().get(rid)?.let { autoArchiveOnce(it) }
        Widgets.refresh(ctx)
    }

    /**
     * Одноразовое дело уходит в архив, как только все его срабатывания выполнены (или пропущены);
     * если отметку сняли в тот же день — возвращается в активные.
     */
    private suspend fun autoArchiveOnce(r: Reminder) {
        if (r.triggerMode != Trigger.TIME || r.status == Status.PAUSED) return
        val s = r.sched
        if (s.type != ScheduleType.ONCE) return
        val slots = slotsOf(r, db.occurrences().range(s.onceEpochDay, s.onceEpochDay), LocalDate.ofEpochDay(s.onceEpochDay))
        val finished = slots.isNotEmpty() && slots.all { it.state == OccState.DONE || it.state == OccState.SKIPPED }
        if (finished && r.status == Status.ACTIVE) {
            db.reminders().upsert(r.copy(status = Status.ARCHIVED))
            Alarms.cancel(ctx, r.id)
        } else if (!finished && r.status == Status.ARCHIVED && s.onceEpochDay >= today().toEpochDay()) {
            val back = r.copy(status = Status.ACTIVE)
            db.reminders().upsert(back)
            Alarms.scheduleNext(ctx, back)
        }
    }

    suspend fun toggleChecked(rid: Long, dueAt: Long, idx: Int) {
        val dao = db.occurrences()
        val o = dao.find(rid, dueAt) ?: Occurrence(reminderId = rid, dueAt = dueAt, epochDay = epochDayOf(dueAt)).also { dao.insertIgnore(it) }.let { dao.find(rid, dueAt)!! }
        val set = o.checked.split(',').mapNotNull { it.toIntOrNull() }.toMutableSet()
        if (!set.add(idx)) set.remove(idx)
        dao.update(o.copy(checked = set.sorted().joinToString(",")))
        // Все пункты отмечены — дело выполнено; сняли пункт у выполненного — снова в работе.
        val total = db.reminders().get(rid)?.checkItems?.size ?: 0
        if (total > 0) {
            if (set.size >= total && o.state != OccState.DONE) setState(rid, dueAt, OccState.DONE)
            else if (set.size < total && o.state == OccState.DONE) setState(rid, dueAt, OccState.PENDING)
        }
    }

    suspend fun addMeasurements(rid: Long, ts: Long, values: Map<String, Double>) {
        val ed = epochDayOf(ts)
        values.forEach { (f, v) -> db.measurements().insert(Measurement(reminderId = rid, ts = ts, epochDay = ed, field = f, value = v)) }
        Widgets.refresh(ctx)
    }

    suspend fun deleteMeasurement(m: Measurement) { db.measurements().delete(m); Widgets.refresh(ctx) }

    suspend fun saveSet(s: HabitSet) = db.sets().upsert(s)
    suspend fun deleteSet(id: Long) = db.sets().delete(id)

    /** Серия: сколько дней подряд (до сегодня) все запланированные срабатывания выполнены. */
    suspend fun streak(r: Reminder): Int {
        if (r.status == Status.ARCHIVED) return 0
        val td = today()
        val occs = db.occurrences().since(r.id, td.toEpochDay() - 400)
        var n = 0
        var d = td
        var guard = 0
        while (guard++ < 400) {
            val slots = slotsOf(r, occs.filter { it.epochDay == d.toEpochDay() }, d)
            if (slots.isEmpty()) { if (d == td) { d = d.minusDays(1); continue } else if (r.triggerMode == Trigger.TIME) { d = d.minusDays(1); continue } else break }
            val allDone = slots.all { it.state == OccState.DONE }
            if (allDone) n++ else if (d != td) break
            d = d.minusDays(1)
        }
        return n
    }

    /** Прошедшие дни: всё, что не отмечено, становится «пропущено» (в конце суток). */
    suspend fun closeDays() {
        val prefs = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)
        val todayEd = today().toEpochDay()
        val last = prefs.getLong("lastClosed", todayEd - 1)
        val dao = db.occurrences()
        dao.pendingBefore(todayEd).forEach { dao.update(it.copy(state = OccState.MISSED, resolvedAt = System.currentTimeMillis())) }
        val from = maxOf(last, todayEd - 60)
        if (from < todayEd) {
            for (r in db.reminders().active()) {
                if (r.triggerMode != Trigger.TIME) continue
                val s = r.sched
                var d = from
                while (d < todayEd) {
                    val day = LocalDate.ofEpochDay(d)
                    for (m in s.minutesOn(day)) {
                        val due = dueMillis(day, m)
                        if (due >= r.createdAt) dao.insertIgnore(Occurrence(reminderId = r.id, dueAt = due, epochDay = d, state = OccState.MISSED, resolvedAt = due))
                    }
                    d++
                }
            }
        }
        for (r in db.reminders().active()) {
            if (r.triggerMode == Trigger.TIME && r.sched.let { it.type == ScheduleType.ONCE && it.onceEpochDay < todayEd }) {
                db.reminders().upsert(r.copy(status = Status.ARCHIVED))
                Alarms.cancel(ctx, r.id)
            }
        }
        prefs.edit().putLong("lastClosed", todayEd).apply()
        Widgets.refresh(this.ctx)
    }

    // ---------- Импорт / экспорт ----------

    suspend fun exportJson(): String = AppJson.encodeToString(
        Backup(
            exportedAt = System.currentTimeMillis(),
            reminders = db.reminders().all(), occurrences = db.occurrences().all(),
            measurements = db.measurements().all(), sets = db.sets().all(),
        )
    )

    /** Полностью заменяет данные содержимым резервной копии. Бросает исключение при неверном формате. */
    suspend fun importJson(text: String) {
        val b = AppJson.decodeFromString<Backup>(text)
        require(b.version == 1) { "Неизвестная версия файла: ${b.version}" }
        for (id in db.reminders().all().map { it.id }) { Alarms.cancel(ctx, id); Notifier.cancel(ctx, id) }
        db.withTransaction {
            db.occurrences().deleteAll(); db.measurements().deleteAll(); db.sets().deleteAll(); db.reminders().deleteAll()
            db.reminders().insertAll(b.reminders); db.occurrences().insertAll(b.occurrences)
            db.measurements().insertAll(b.measurements); db.sets().insertAll(b.sets)
        }
        Alarms.rescheduleAll(ctx, db.reminders().active())
        Conditions.sync(ctx, db.reminders().active())
        Widgets.refresh(ctx)
    }

    suspend fun measurementsCsv(rid: Long? = null): String {
        val rems = db.reminders().all().associateBy { it.id }
        val sb = StringBuilder("напоминание;поле;дата;время;значение\n")
        db.measurements().all().filter { rid == null || it.reminderId == rid }.forEach { m ->
            val t = Instant.ofEpochMilli(m.ts).atZone(zone())
            sb.append(csv(rems[m.reminderId]?.title ?: "?")).append(';').append(csv(m.field)).append(';')
                .append(t.toLocalDate()).append(';').append("%02d:%02d".format(t.hour, t.minute)).append(';')
                .append(m.value.toString().replace('.', ',')).append('\n')
        }
        return sb.toString()
    }

    suspend fun habitsCsv(rows: List<Reminder>, from: LocalDate, to: LocalDate): String {
        val occs = db.occurrences().range(from.toEpochDay(), to.toEpochDay())
        val ms = db.measurements().all()
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        val sb = StringBuilder("напоминание;" + days.joinToString(";") + "\n")
        val now = System.currentTimeMillis()
        rows.forEach { r ->
            sb.append(csv(r.title))
            days.forEach { d ->
                val c = cellOf(r, d, occs, ms, now)
                sb.append(';').append(when (c.kind) {
                    CellKind.DONE -> "выполнено"; CellKind.MISS -> "пропущено"; CellKind.SHIFT -> "перенесено"
                    CellKind.PART, CellKind.VALUE -> c.text; CellKind.PEND -> "ждёт"; CellKind.NONE -> ""
                })
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun csv(s: String) = if (s.any { it == ';' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
