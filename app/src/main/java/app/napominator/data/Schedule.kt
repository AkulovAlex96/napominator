package app.napominator.data

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class ScheduleType { ONCE, DAYS_OF_WEEK, EVERY_N_DAYS, EVERY_N_WEEKS, EVERY_N_MONTHS, MONTH_DAYS, MONTH_NTH_WEEKDAY, INTERVAL }

/**
 * Расписание. Любой вид сводится к функции «какие минуты суток срабатывают в эту дату» ([minutesOn]),
 * на ней строятся и будильники, и календарь, и таблица привычек.
 */
@Serializable
data class Schedule(
    val type: ScheduleType = ScheduleType.DAYS_OF_WEEK,
    /** Минуты от начала суток, по одному срабатыванию на каждое значение. */
    val times: List<Int> = listOf(9 * 60),
    /** ISO: 1 = понедельник … 7 = воскресенье. */
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val every: Int = 1,
    /** Дата начала отсчёта для «каждые N дней/недель/месяцев» и первая допустимая дата вообще. */
    val startEpochDay: Long = 0,
    /** Числа месяца; -1 — последний день. */
    val monthDays: List<Int> = listOf(1),
    /** Для MONTH_NTH_WEEKDAY: номер недели 1..4 или -1 (последняя) и день недели ISO. */
    val nth: Int = 1,
    val nthDow: Int = 1,
    val onceEpochDay: Long = 0,
    /** Для INTERVAL: срабатывать каждые N минут внутри окна. */
    val intervalMinutes: Int = 60,
    val windowStart: Int = 8 * 60,
    val windowEnd: Int = 22 * 60,
    val untilEpochDay: Long? = null,
) {
    fun minutesOn(d: LocalDate): List<Int> {
        val ed = d.toEpochDay()
        if (type != ScheduleType.ONCE && ed < startEpochDay) return emptyList()
        if (untilEpochDay != null && ed > untilEpochDay) return emptyList()
        val n = every.coerceAtLeast(1)
        val matches = when (type) {
            ScheduleType.ONCE -> ed == onceEpochDay
            ScheduleType.DAYS_OF_WEEK -> d.dayOfWeek.value in daysOfWeek
            ScheduleType.EVERY_N_DAYS -> (ed - startEpochDay) % n == 0L
            ScheduleType.EVERY_N_WEEKS -> {
                val start = LocalDate.ofEpochDay(startEpochDay)
                val mondayOfStart = start.with(DayOfWeek.MONDAY).toEpochDay()
                d.dayOfWeek.value in daysOfWeek && ((ed - mondayOfStart) / 7) % n == 0L
            }
            ScheduleType.EVERY_N_MONTHS -> {
                val start = LocalDate.ofEpochDay(startEpochDay)
                val months = ChronoUnit.MONTHS.between(start.withDayOfMonth(1), d.withDayOfMonth(1))
                months % n == 0L && d.dayOfMonth == minOf(start.dayOfMonth, d.lengthOfMonth())
            }
            ScheduleType.MONTH_DAYS -> monthDays.any { (it == -1 && d.dayOfMonth == d.lengthOfMonth()) || it == d.dayOfMonth }
            ScheduleType.MONTH_NTH_WEEKDAY -> d.dayOfWeek.value == nthDow &&
                if (nth == -1) d.dayOfMonth + 7 > d.lengthOfMonth() else (d.dayOfMonth - 1) / 7 + 1 == nth
            ScheduleType.INTERVAL -> true
        }
        if (!matches) return emptyList()
        if (type == ScheduleType.INTERVAL) {
            val step = intervalMinutes.coerceAtLeast(1)
            return generateSequence(windowStart) { it + step }.takeWhile { it <= windowEnd && it < 24 * 60 }.toList()
        }
        return times.distinct().sorted()
    }

    /** Человекочитаемое описание для списка и карточки. */
    fun describe(): String {
        val t = times.sorted().joinToString(", ") { fmtMinutes(it) }
        return when (type) {
            ScheduleType.ONCE -> "${LocalDate.ofEpochDay(onceEpochDay).dayOfMonth} ${monthGen(LocalDate.ofEpochDay(onceEpochDay).monthValue)} в $t"
            ScheduleType.DAYS_OF_WEEK ->
                if (daysOfWeek.size == 7) "каждый день, $t" else daysOfWeek.sorted().joinToString(", ") { DOW_SHORT[it - 1] } + ", $t"
            ScheduleType.EVERY_N_DAYS -> "каждые $every дн., $t"
            ScheduleType.EVERY_N_WEEKS -> "каждые $every нед.: " + daysOfWeek.sorted().joinToString(", ") { DOW_SHORT[it - 1] } + ", $t"
            ScheduleType.EVERY_N_MONTHS -> "каждые $every мес., $t"
            ScheduleType.MONTH_DAYS -> monthDays.sorted().joinToString(", ") { if (it == -1) "последний день" else "$it-го" } + ", $t"
            ScheduleType.MONTH_NTH_WEEKDAY -> (if (nth == -1) "последний" else "$nth-й") + " ${DOW_SHORT[nthDow - 1]} месяца, $t"
            ScheduleType.INTERVAL -> "каждые ${fmtInterval(intervalMinutes)}, ${fmtMinutes(windowStart)}–${fmtMinutes(windowEnd)}"
        }
    }

    companion object {
        val DOW_SHORT = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
        fun fmtMinutes(m: Int) = "%02d:%02d".format(m / 60, m % 60)
        private fun fmtInterval(m: Int) = if (m % 60 == 0) "${m / 60} ч" else "$m мин"
        private val MONTHS_GEN = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
        fun monthGen(m: Int) = MONTHS_GEN[m - 1]
    }
}
