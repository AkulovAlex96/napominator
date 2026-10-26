package app.napominator

import app.napominator.data.AppJson
import app.napominator.data.Backup
import app.napominator.data.Kind
import app.napominator.data.OccState
import app.napominator.data.ScheduleType
import app.napominator.data.Status
import app.napominator.data.Trigger
import app.napominator.data.conds
import app.napominator.data.measureConfig
import app.napominator.data.sched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Проверяет файл testdata/testdata.json (его делает tools/make_testdata.py):
 * корректный формат, целостность связей и совпадение с правилами расписаний из приложения.
 */
class TestDataTest {
    private val file = File("../testdata/testdata.json")
    private val data: Backup by lazy { AppJson.decodeFromString<Backup>(file.readText()) }
    private val dayMs = 86_400_000L

    private fun need() = assumeTrue("нет файла ${file.absolutePath}; создайте: python3 tools/make_testdata.py", file.exists())

    @Test fun coversAllFeatures() {
        need()
        val rs = data.reminders
        assertTrue("напоминаний ${rs.size}", rs.size >= 15)
        assertEquals("id уникальны", rs.size, rs.map { it.id }.toSet().size)
        assertEquals("все виды расписаний", ScheduleType.entries.toSet(), rs.map { it.sched.type }.toSet())
        assertEquals(Trigger.entries.toSet(), rs.map { it.triggerMode }.toSet())
        assertEquals(Status.entries.toSet(), rs.map { it.status }.toSet())
        assertEquals(setOf(0, 1, 2), rs.map { it.priority }.toSet())
        assertTrue(rs.any { it.kind == Kind.MEASURE && it.measureConfig!!.accumulative })
        assertTrue(rs.any { it.kind == Kind.MEASURE && it.measureConfig!!.fields.size > 1 })
        assertEquals(setOf("PLACE", "WIFI", "BLUETOOTH"), rs.flatMap { it.conds }.map { it.kind.name }.toSet())
        assertTrue(rs.any { it.checklist.isNotBlank() } && rs.any { it.tags.isNotBlank() } && rs.any { it.repeatEveryMin > 0 })
        assertEquals(OccState.entries.toSet(), data.occurrences.map { it.state }.toSet())
        assertTrue(data.sets.size >= 3)
    }

    @Test fun historyCoversSeveralMonths() {
        need()
        val days = data.occurrences.map { it.epochDay }.toSet()
        assertTrue("дней с историей: ${days.size}", days.size >= 150)
        assertTrue("период: ${days.max() - days.min()} дн.", days.max() - days.min() >= 170)
        val mDays = data.measurements.map { it.epochDay }.toSet()
        assertTrue("дней с измерениями: ${mDays.size}", mDays.size >= 150)
    }

    @Test fun referencesAreConsistent() {
        need()
        val ids = data.reminders.map { it.id }.toSet()
        assertTrue(data.occurrences.all { it.reminderId in ids })
        assertTrue(data.measurements.all { it.reminderId in ids })
        assertEquals("(напоминание, время) уникальны", data.occurrences.size, data.occurrences.map { it.reminderId to it.dueAt }.toSet().size)
        assertEquals(data.occurrences.size, data.occurrences.map { it.id }.toSet().size)
        val byId = data.reminders.associateBy { it.id }
        for (m in data.measurements) {
            val r = byId[m.reminderId]!!
            assertEquals("${r.title} — измерение не для типа «Измерение»", Kind.MEASURE, r.kind)
            assertTrue("поле ${m.field} есть в настройках «${r.title}»", r.measureConfig!!.fields.any { it.name == m.field })
        }
        for (s in data.sets) s.reminderIds.split(',').filter { it.isNotBlank() }.forEach { assertTrue(it.toLong() in ids) }
    }

    /** Срабатывания по времени лежат ровно в тех минутах, которые даёт Schedule.minutesOn, независимо от часового пояса файла. */
    @Test fun occurrencesMatchScheduleRules() {
        need()
        val first = data.occurrences.first { data.reminders.first { r -> r.id == it.reminderId }.title == "Зарядка" }
        val offsetMs = 450 * 60_000L - (first.dueAt - first.epochDay * dayMs) // смещение пояса, в котором создан файл
        val byId = data.reminders.associateBy { it.id }
        var checked = 0
        for (o in data.occurrences) {
            val r = byId[o.reminderId]!!
            if (r.triggerMode != Trigger.TIME) continue
            val minute = ((o.dueAt - o.epochDay * dayMs + offsetMs) / 60_000L).toInt()
            val allowed = r.sched.minutesOn(LocalDate.ofEpochDay(o.epochDay))
            assertTrue("«${r.title}» ${LocalDate.ofEpochDay(o.epochDay)} в $minute мин; по правилам: $allowed", minute in allowed)
            checked++
        }
        assertTrue("проверено срабатываний: $checked", checked > 1500)
    }

    @Test fun conditionalOccurrencesRespectWindows() {
        need()
        val r = data.reminders.first { it.triggerMode == Trigger.BOTH }
        val s = r.sched
        val first = data.occurrences.first { data.reminders.first { x -> x.id == it.reminderId }.title == "Зарядка" }
        val offsetMs = 450 * 60_000L - (first.dueAt - first.epochDay * dayMs)
        val events = data.occurrences.filter { it.reminderId == r.id }
        assertTrue(events.isNotEmpty())
        for (o in events) {
            val d = LocalDate.ofEpochDay(o.epochDay)
            val minute = ((o.dueAt - o.epochDay * dayMs + offsetMs) / 60_000L).toInt()
            assertTrue("день недели ${d.dayOfWeek}", d.dayOfWeek.value in s.daysOfWeek)
            assertTrue("время $minute вне окна ${s.windowStart}–${s.windowEnd}", minute in s.windowStart..s.windowEnd)
        }
        assertNotNull(data.measurements.firstOrNull { it.field == "Шаги" })
    }
}
