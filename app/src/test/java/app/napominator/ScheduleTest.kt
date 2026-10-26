package app.napominator

import app.napominator.data.Schedule
import app.napominator.data.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleTest {
    private val d = { y: Int, m: Int, day: Int -> LocalDate.of(y, m, day) }

    @Test fun weekdaysOnlyAndSortedTimes() {
        val s = Schedule(ScheduleType.DAYS_OF_WEEK, times = listOf(21 * 60, 8 * 60), daysOfWeek = setOf(1, 2, 3, 4, 5))
        assertEquals(listOf(480, 1260), s.minutesOn(d(2026, 10, 26))) // понедельник
        assertTrue(s.minutesOn(d(2026, 10, 31)).isEmpty()) // суббота
    }

    @Test fun onceFiresOnlyOnItsDay() {
        val day = d(2026, 11, 3)
        val s = Schedule(ScheduleType.ONCE, times = listOf(600), onceEpochDay = day.toEpochDay())
        assertEquals(listOf(600), s.minutesOn(day))
        assertTrue(s.minutesOn(day.plusDays(1)).isEmpty())
        assertTrue(s.minutesOn(day.minusDays(1)).isEmpty())
    }

    @Test fun everyNDaysCountsFromStart() {
        val start = d(2026, 10, 1)
        val s = Schedule(ScheduleType.EVERY_N_DAYS, every = 3, startEpochDay = start.toEpochDay(), times = listOf(60))
        assertEquals(listOf(60), s.minutesOn(start))
        assertTrue(s.minutesOn(start.plusDays(1)).isEmpty())
        assertEquals(listOf(60), s.minutesOn(start.plusDays(6)))
        assertTrue("до даты начала не срабатывает", s.minutesOn(start.minusDays(3)).isEmpty())
    }

    @Test fun everyTwoWeeksAlternatesWeeks() {
        val start = d(2026, 10, 26) // понедельник
        val s = Schedule(ScheduleType.EVERY_N_WEEKS, every = 2, startEpochDay = start.toEpochDay(), daysOfWeek = setOf(1, 3), times = listOf(540))
        assertEquals(listOf(540), s.minutesOn(d(2026, 10, 26)))
        assertEquals(listOf(540), s.minutesOn(d(2026, 10, 28)))
        assertTrue(s.minutesOn(d(2026, 11, 2)).isEmpty())
        assertEquals(listOf(540), s.minutesOn(d(2026, 11, 9)))
    }

    @Test fun everyMonthClampsToShortMonths() {
        val start = d(2026, 1, 31)
        val s = Schedule(ScheduleType.EVERY_N_MONTHS, every = 1, startEpochDay = start.toEpochDay(), times = listOf(0))
        assertEquals(listOf(0), s.minutesOn(d(2026, 2, 28)))
        assertTrue(s.minutesOn(d(2026, 2, 27)).isEmpty())
        assertEquals(listOf(0), s.minutesOn(d(2026, 3, 31)))
        val q = Schedule(ScheduleType.EVERY_N_MONTHS, every = 3, startEpochDay = start.toEpochDay(), times = listOf(0))
        assertTrue(q.minutesOn(d(2026, 3, 31)).isEmpty())
        assertEquals(listOf(0), q.minutesOn(d(2026, 4, 30)))
    }

    @Test fun monthDaysIncludingLast() {
        val s = Schedule(ScheduleType.MONTH_DAYS, monthDays = listOf(15, -1), times = listOf(100))
        assertEquals(listOf(100), s.minutesOn(d(2026, 10, 15)))
        assertEquals(listOf(100), s.minutesOn(d(2026, 10, 31)))
        assertEquals(listOf(100), s.minutesOn(d(2028, 2, 29))) // високосный
        assertTrue(s.minutesOn(d(2028, 2, 28)).isEmpty())
    }

    @Test fun nthWeekdayOfMonth() {
        val second = Schedule(ScheduleType.MONTH_NTH_WEEKDAY, nth = 2, nthDow = 2, times = listOf(1)) // 2-й вторник
        assertEquals(listOf(1), second.minutesOn(d(2026, 10, 13)))
        assertTrue(second.minutesOn(d(2026, 10, 6)).isEmpty())
        assertTrue(second.minutesOn(d(2026, 10, 20)).isEmpty())
        val last = Schedule(ScheduleType.MONTH_NTH_WEEKDAY, nth = -1, nthDow = 5, times = listOf(1)) // последняя пятница
        assertEquals(listOf(1), last.minutesOn(d(2026, 10, 30)))
        assertTrue(last.minutesOn(d(2026, 10, 23)).isEmpty())
        assertEquals(listOf(1), last.minutesOn(d(2026, 11, 27)))
    }

    @Test fun intervalWithinWindow() {
        val s = Schedule(ScheduleType.INTERVAL, intervalMinutes = 120, windowStart = 8 * 60, windowEnd = 14 * 60)
        assertEquals(listOf(480, 600, 720, 840), s.minutesOn(d(2026, 10, 26)))
        val m = Schedule(ScheduleType.INTERVAL, intervalMinutes = 30, windowStart = 23 * 60, windowEnd = 24 * 60)
        assertEquals(listOf(1380, 1410), m.minutesOn(d(2026, 10, 26))) // не выходит за сутки
    }

    @Test fun untilDateStopsSchedule() {
        val last = d(2026, 11, 5)
        val s = Schedule(ScheduleType.DAYS_OF_WEEK, times = listOf(60), untilEpochDay = last.toEpochDay())
        assertEquals(listOf(60), s.minutesOn(last))
        assertTrue(s.minutesOn(last.plusDays(1)).isEmpty())
    }

    @Test fun emptyTimesGiveNothing() {
        assertTrue(Schedule(ScheduleType.DAYS_OF_WEEK, times = emptyList()).minutesOn(d(2026, 10, 26)).isEmpty())
    }

    @Test fun describeIsReadable() {
        assertEquals("каждый день, 08:00, 21:00", Schedule(times = listOf(480, 1260)).describe())
        assertEquals("пн, ср, пт, 09:00", Schedule(daysOfWeek = setOf(1, 3, 5)).describe())
        assertEquals("каждые 2 ч, 08:00–22:00", Schedule(ScheduleType.INTERVAL, intervalMinutes = 120).describe())
        assertEquals("последний день, 09:00", Schedule(ScheduleType.MONTH_DAYS, monthDays = listOf(-1)).describe())
    }
}
