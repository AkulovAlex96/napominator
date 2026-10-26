package app.napominator

import app.napominator.data.AppJson
import app.napominator.data.Backup
import app.napominator.data.Cell
import app.napominator.data.CellKind
import app.napominator.data.CondKind
import app.napominator.data.Condition
import app.napominator.data.Kind
import app.napominator.data.Measurement
import app.napominator.data.MeasureConfig
import app.napominator.data.MeasureField
import app.napominator.data.OccState
import app.napominator.data.Occurrence
import app.napominator.data.Reminder
import app.napominator.data.Schedule
import app.napominator.data.ScheduleType
import app.napominator.data.Trigger
import app.napominator.data.cellOf
import app.napominator.data.checkItems
import app.napominator.data.conds
import app.napominator.data.describe
import app.napominator.data.dueMillis
import app.napominator.data.epochDayOf
import app.napominator.data.fmt
import app.napominator.data.slotsFor
import app.napominator.data.tagList
import app.napominator.data.toJson
import app.napominator.data.today
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LogicTest {
    private val day: LocalDate = LocalDate.of(2026, 10, 22)
    private val created = dueMillis(day.minusDays(30), 0)

    private fun task(times: List<Int>, id: Long = 1) = Reminder(
        id = id, title = "T", schedule = Schedule(ScheduleType.DAYS_OF_WEEK, times = times).toJson(), createdAt = created,
    )

    private fun occ(r: Reminder, minutes: Int, state: OccState, d: LocalDate = day) =
        Occurrence(reminderId = r.id, dueAt = dueMillis(d, minutes), epochDay = d.toEpochDay(), state = state)

    @Test fun cellDoneWhenAllSlotsDone() {
        val r = task(listOf(480, 1260))
        val c = cellOf(r, day, listOf(occ(r, 480, OccState.DONE), occ(r, 1260, OccState.DONE)), emptyList(), 0)
        assertEquals(CellKind.DONE, c.kind)
    }

    @Test fun cellPartialShowsFraction() {
        val r = task(listOf(480, 720, 1260))
        val c = cellOf(r, day, listOf(occ(r, 480, OccState.DONE), occ(r, 720, OccState.MISSED)), emptyList(), 0)
        assertEquals(Cell(CellKind.PART, "1/3"), c)
    }

    @Test fun cellMissedWhenNothingDone() {
        val r = task(listOf(480))
        assertEquals(CellKind.MISS, cellOf(r, day, listOf(occ(r, 480, OccState.MISSED)), emptyList(), 0).kind)
        assertEquals(CellKind.MISS, cellOf(r, day, listOf(occ(r, 480, OccState.SKIPPED)), emptyList(), 0).kind)
    }

    @Test fun cellShiftedAndPendingAndFuture() {
        val r = task(listOf(480))
        assertEquals(CellKind.SHIFT, cellOf(r, day, listOf(occ(r, 480, OccState.SHIFTED)), emptyList(), 0).kind)
        assertEquals(CellKind.PEND, cellOf(r, today(), emptyList(), emptyList(), 0).kind)
        assertEquals(CellKind.NONE, cellOf(r, today().plusDays(2), emptyList(), emptyList(), 0).kind)
    }

    @Test fun cellNoneWhenNotScheduled() {
        val r = Reminder(id = 1, title = "T", createdAt = created, schedule = Schedule(daysOfWeek = setOf(1)).toJson()) // только пн
        assertEquals(CellKind.NONE, cellOf(r, day, emptyList(), emptyList(), 0).kind) // четверг
    }

    @Test fun measureCellShowsValueAndAccumulates() {
        val cfg = MeasureConfig(listOf(MeasureField("Вес", "кг")))
        val r = task(listOf(480)).copy(kind = Kind.MEASURE, measure = cfg.toJson())
        val ms = listOf(
            Measurement(reminderId = 1, ts = dueMillis(day, 480), epochDay = day.toEpochDay(), field = "Вес", value = 82.4),
            Measurement(reminderId = 1, ts = dueMillis(day, 500), epochDay = day.toEpochDay(), field = "Вес", value = 82.1),
        )
        assertEquals(Cell(CellKind.VALUE, "82,1"), cellOf(r, day, emptyList(), ms, 0))
        val water = r.copy(measure = MeasureConfig(listOf(MeasureField("Вода", "мл")), accumulative = true).toJson())
        val wms = listOf(250.0, 250.0, 500.0).mapIndexed { i, v -> Measurement(reminderId = 1, ts = dueMillis(day, 480 + i), epochDay = day.toEpochDay(), field = "Вода", value = v) }
        assertEquals(Cell(CellKind.VALUE, "1000"), cellOf(water, day, emptyList(), wms, 0))
    }

    @Test fun conditionalReminderUsesRecordedOccurrences() {
        val r = Reminder(id = 5, title = "Цветы", triggerMode = Trigger.CONDITION, createdAt = created, schedule = Schedule(times = emptyList()).toJson())
        assertTrue(slotsFor(listOf(r), emptyList(), day).isEmpty())
        val o = Occurrence(reminderId = 5, dueAt = dueMillis(day, 1100), epochDay = day.toEpochDay(), state = OccState.DONE)
        val slots = slotsFor(listOf(r), listOf(o), day)
        assertEquals(1, slots.size)
        assertEquals(OccState.DONE, slots[0].state)
    }

    @Test fun slotsSkipTimesBeforeCreation() {
        val r = task(listOf(480, 1260)).copy(createdAt = dueMillis(day, 600))
        val slots = slotsFor(listOf(r), emptyList(), day)
        assertEquals(listOf(dueMillis(day, 1260)), slots.map { it.dueAt })
    }

    @Test fun pausedRemindersHaveNoSlots() {
        val r = task(listOf(480)).copy(status = app.napominator.data.Status.PAUSED)
        assertTrue(slotsFor(listOf(r), emptyList(), day).isEmpty())
    }

    @Test fun conditionsRoundTripAndDescribe() {
        val list = listOf(
            Condition(CondKind.WIFI, enter = true, ssid = "Дом-5G"),
            Condition(CondKind.PLACE, enter = false, lat = 55.03, lon = 82.92, radiusM = 200),
            Condition(CondKind.BLUETOOTH, btAddress = "AA:BB", btName = "Авто"),
        )
        val r = Reminder(title = "x", schedule = Schedule().toJson(), triggerMode = Trigger.CONDITION, conditions = list.toJson())
        assertEquals(list, r.conds)
        assertTrue(r.describe().contains("Дом-5G") && r.describe().contains(" или "))
        assertTrue(Reminder(title = "x", schedule = Schedule().toJson(), conditions = "мусор").conds.isEmpty())
    }

    @Test fun backupJsonRoundTrip() {
        val r = task(listOf(480)).copy(checklist = "a\n\n b ", tags = "x, y")
        val b = Backup(exportedAt = 7, reminders = listOf(r), occurrences = listOf(occ(r, 480, OccState.DONE)),
            measurements = listOf(Measurement(reminderId = 1, ts = 1, epochDay = 0, field = "f", value = 1.5)))
        val text = AppJson.encodeToString(b)
        assertEquals(b, AppJson.decodeFromString<Backup>(text))
        assertEquals(listOf("a", "b"), r.checkItems)
        assertEquals(listOf("x", "y"), r.tagList)
    }

    @Test fun importIgnoresUnknownKeys() {
        val b = AppJson.decodeFromString<Backup>("""{"version":1,"новое":"поле","reminders":[]}""")
        assertEquals(1, b.version)
        assertTrue(b.reminders.isEmpty())
    }

    @Test fun formatting() {
        assertEquals("82", fmt(82.0)); assertEquals("82,5", fmt(82.5)); assertEquals("0", fmt(0.0))
        assertEquals(day.toEpochDay(), epochDayOf(dueMillis(day, 1439)))
    }
}
