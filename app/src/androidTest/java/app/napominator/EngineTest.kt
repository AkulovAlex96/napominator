package app.napominator

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.napominator.TestUtil.actionIntent
import app.napominator.TestUtil.activeNotification
import app.napominator.TestUtil.ctx
import app.napominator.TestUtil.daily
import app.napominator.TestUtil.dueToday
import app.napominator.TestUtil.fireIntent
import app.napominator.TestUtil.onceAt
import app.napominator.TestUtil.reminder
import app.napominator.TestUtil.repo
import app.napominator.TestUtil.waitFor
import app.napominator.alarm.AlarmReceiver
import app.napominator.alarm.Alarms
import app.napominator.alarm.Conditions
import app.napominator.alarm.Notifier
import app.napominator.data.CondKind
import app.napominator.data.Condition
import app.napominator.data.Kind
import app.napominator.data.MeasureConfig
import app.napominator.data.MeasureField
import app.napominator.data.OccState
import app.napominator.data.Occurrence
import app.napominator.data.Status
import app.napominator.data.Trigger
import app.napominator.data.Schedule
import app.napominator.data.ScheduleType
import app.napominator.data.checkItems
import app.napominator.data.dueMillis
import app.napominator.data.epochDayOf
import app.napominator.data.toJson
import app.napominator.data.today
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class EngineTest {
    @Before fun setUp() = TestUtil.reset()

    private fun pi(rid: Long, kind: Int) = PendingIntent.getBroadcast(
        ctx, (rid * 2 + kind).toInt(), Intent(ctx, AlarmReceiver::class.java).setAction(Alarms.ACTION_FIRE), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    private fun occ(rid: Long, due: Long) = runBlocking { repo.db.occurrences().find(rid, due) }
    private fun fire(rid: Long, due: Long, attempt: Int = 0) = ctx.sendBroadcast(fireIntent(rid, due, attempt))
    private fun act(rid: Long, due: Long, a: String) = ctx.sendBroadcast(actionIntent(rid, due, a))
    private fun save(r: app.napominator.data.Reminder) = runBlocking { repo.save(r) }

    @Test fun saveSchedulesAlarmAndPauseCancelsIt() {
        val id = save(reminder("Утро", daily(23 * 60 + 59)))
        assertNotNull("основной будильник поставлен", pi(id, 0))
        val r = runBlocking { repo.get(id)!! }
        runBlocking { repo.setStatus(r, Status.PAUSED) }
        assertNull("на паузе будильника нет", pi(id, 0))
        runBlocking { repo.setStatus(r, Status.ACTIVE) }
        assertNotNull("после возобновления снова есть", pi(id, 0))
    }

    @Test fun conditionOnlyReminderHasNoTimeAlarm() {
        val c = listOf(Condition(CondKind.WIFI, ssid = "Дом")).toJson()
        val id = save(reminder("Цветы", Schedule(times = emptyList())) { it.copy(triggerMode = Trigger.CONDITION, conditions = c) })
        assertNull(pi(id, 0))
    }

    @Test fun fireCreatesPendingOccurrenceAndNotificationWithActions() {
        val due = dueToday(8 * 60)
        val id = save(reminder("Таблетки", daily(8 * 60)) { it.copy(note = "после еды", checklist = "вода\nхлеб") })
        fire(id, due)
        waitFor(what = "уведомление") { activeNotification(id) != null }
        assertEquals(OccState.PENDING, occ(id, due)?.state)
        val n = activeNotification(id)!!.notification
        assertEquals("Таблетки", n.extras.getString("android.title"))
        assertTrue(n.extras.getCharSequence("android.text").toString().contains("• вода"))
        assertEquals(listOf("Выполнено", "Отложить 10 мин", "Пропустить"), n.actions.map { it.title.toString() })
        assertEquals("p0", n.channelId)
    }

    @Test fun doneSkipSnoozeActions() {
        val id = save(reminder("A", daily(8 * 60)))
        val due = dueToday(8 * 60)
        fire(id, due); waitFor { activeNotification(id) != null }
        act(id, due, Notifier.ACT_SNOOZE)
        waitFor(what = "снуз") { activeNotification(id) == null }
        assertNotNull("повторный сигнал запланирован", pi(id, 1))
        assertEquals(OccState.PENDING, occ(id, due)?.state)
        act(id, due, Notifier.ACT_DONE)
        waitFor(what = "готово") { occ(id, due)?.state == OccState.DONE }
        assertNull("после выполнения отложенный сигнал снят", pi(id, 1))

        val due2 = dueToday(9 * 60)
        fire(id, due2); waitFor { activeNotification(id) != null }
        act(id, due2, Notifier.ACT_SKIP)
        waitFor { occ(id, due2)?.state == OccState.SKIPPED }
        assertNull(activeNotification(id))
    }

    @Test fun measurementFromNotificationInput() {
        val cfg = MeasureConfig(listOf(MeasureField("Вес", "кг")))
        val id = save(reminder("Вес", daily(8 * 60), Kind.MEASURE, cfg.toJson()))
        val due = dueToday(8 * 60)
        fire(id, due); waitFor { activeNotification(id) != null }
        val n = activeNotification(id)!!.notification
        assertEquals("Ввести", n.actions[0].title.toString())
        assertNotNull("в шторке есть поле ввода", n.actions[0].remoteInputs?.firstOrNull())
        val i = actionIntent(id, due, Notifier.ACT_VALUE)
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(Notifier.KEY_VALUE).build()), i, Bundle().apply { putCharSequence(Notifier.KEY_VALUE, "82,4") })
        ctx.sendBroadcast(i)
        waitFor(what = "значение сохранено") { runBlocking { repo.db.measurements().last(id) } != null }
        assertEquals(82.4, runBlocking { repo.db.measurements().last(id)!!.value }, 1e-9)
        waitFor { occ(id, due)?.state == OccState.DONE }
        // мусор вместо числа ничего не ломает
        val bad = actionIntent(id, due, Notifier.ACT_VALUE)
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(Notifier.KEY_VALUE).build()), bad, Bundle().apply { putCharSequence(Notifier.KEY_VALUE, "abc") })
        ctx.sendBroadcast(bad); Thread.sleep(500)
        assertEquals(1, runBlocking { repo.db.measurements().all().size })
    }

    @Test fun multiFieldMeasurementOpensApp() {
        val cfg = MeasureConfig(listOf(MeasureField("Верхнее"), MeasureField("Нижнее")))
        val id = save(reminder("Давление", daily(8 * 60), Kind.MEASURE, cfg.toJson()))
        val due = dueToday(8 * 60)
        fire(id, due); waitFor { activeNotification(id) != null }
        val a = activeNotification(id)!!.notification.actions[0]
        assertNull("для нескольких полей ввод в шторке не используется", a.remoteInputs)
    }

    @Test fun persistentRepeatSchedulesFollowUpUntilDone() {
        val id = save(reminder("Настойчивое", daily(8 * 60)) { it.copy(repeatEveryMin = 1, repeatMax = 3) })
        val due = dueToday(8 * 60)
        fire(id, due); waitFor { activeNotification(id) != null }
        assertNotNull(pi(id, 1))
        assertTrue(activeNotification(id)!!.notification.actions.isNotEmpty())
        act(id, due, Notifier.ACT_DONE)
        waitFor { occ(id, due)?.state == OccState.DONE }
        assertNull(pi(id, 1))
        // последняя попытка не планирует следующую
        val due2 = dueToday(9 * 60)
        fire(id, due2, attempt = 3); waitFor { activeNotification(id) != null }
        assertNull("лимит повторов исчерпан", pi(id, 1))
    }

    @Test fun alreadyDoneOccurrenceIsNotNotifiedAgain() {
        val id = save(reminder("A", daily(8 * 60)))
        val due = dueToday(8 * 60)
        runBlocking { repo.setState(id, due, OccState.DONE) }
        fire(id, due); Thread.sleep(1000)
        assertNull(activeNotification(id))
    }

    @Test fun criticalAndImportantChannels() {
        val a = save(reminder("Важное", daily(8 * 60)) { it.copy(priority = 1) })
        val b = save(reminder("Критичное", daily(8 * 60)) { it.copy(priority = 2) })
        fire(a, dueToday(8 * 60)); fire(b, dueToday(8 * 60))
        waitFor { activeNotification(a) != null && activeNotification(b) != null }
        assertEquals("p1", activeNotification(a)!!.notification.channelId)
        val crit = activeNotification(b)!!.notification
        assertEquals("p2", crit.channelId)
        assertNotNull("полноэкранный показ", crit.fullScreenIntent)
        assertNull(activeNotification(a)!!.notification.fullScreenIntent)
    }

    @Test fun categorySoundUsesOwnChannel() {
        TestUtil.app.prefs.setCategorySound("Здоровье", "content://settings/system/notification_sound")
        val id = save(reminder("Z", daily(8 * 60)) { it.copy(category = "Здоровье") })
        fire(id, dueToday(8 * 60)); waitFor { activeNotification(id) != null }
        assertTrue(activeNotification(id)!!.notification.channelId.startsWith("c"))
    }

    @Test fun closeDaysMarksMissedButNotToday() {
        val td = today()
        val id = save(reminder("M", daily(8 * 60, 20 * 60)))
        ctx.getSharedPreferences("state", 0).edit().putLong("lastClosed", td.minusDays(3).toEpochDay()).apply()
        // вчерашнее висящее срабатывание
        runBlocking { repo.db.occurrences().insertIgnore(Occurrence(reminderId = id, dueAt = dueMillis(td.minusDays(1), 20 * 60), epochDay = td.minusDays(1).toEpochDay())) }
        runBlocking { repo.setState(id, dueMillis(td.minusDays(2), 8 * 60), OccState.DONE) }
        runBlocking { repo.closeDays() }
        val all = runBlocking { repo.db.occurrences().all() }
        assertEquals("2 дня * 2 времени + позавчера-вечер... без сегодняшних", 0, all.count { it.epochDay == td.toEpochDay() })
        assertEquals(OccState.DONE, all.first { it.dueAt == dueMillis(td.minusDays(2), 8 * 60) }.state)
        assertEquals(OccState.MISSED, all.first { it.dueAt == dueMillis(td.minusDays(2), 20 * 60) }.state)
        assertEquals(OccState.MISSED, all.first { it.dueAt == dueMillis(td.minusDays(1), 20 * 60) }.state)
        assertEquals(OccState.MISSED, all.first { it.dueAt == dueMillis(td.minusDays(3), 8 * 60) }.state)
        runBlocking { repo.closeDays() }
        assertEquals("повторный вызов ничего не дублирует", all.size, runBlocking { repo.db.occurrences().all().size })
    }

    @Test fun closeDaysDoesNotInventMissesBeforeCreation() {
        val td = today()
        val id = save(reminder("New", daily(8 * 60), created = System.currentTimeMillis()))
        ctx.getSharedPreferences("state", 0).edit().putLong("lastClosed", td.minusDays(5).toEpochDay()).apply()
        runBlocking { repo.closeDays() }
        assertTrue(runBlocking { repo.db.occurrences().all() }.none { it.reminderId == id })
    }

    @Test fun exportImportRoundTripAndBrokenFile() {
        val cfg = MeasureConfig(listOf(MeasureField("Вес", "кг")))
        val a = save(reminder("Вес", daily(8 * 60), Kind.MEASURE, cfg.toJson()) { it.copy(category = "Здоровье", tags = "утро", checklist = "x") })
        val b = save(reminder("Зарядка", daily(9 * 60)))
        runBlocking {
            repo.addMeasurements(a, System.currentTimeMillis(), mapOf("Вес" to 81.9))
            repo.setState(b, dueToday(9 * 60), OccState.DONE)
            repo.saveSet(app.napominator.data.HabitSet(name = "Здоровье", reminderIds = "$a,$b"))
        }
        val json = runBlocking { repo.exportJson() }
        TestUtil.reset()
        assertTrue(runBlocking { repo.db.reminders().all() }.isEmpty())
        runBlocking { repo.importJson(json) }
        val rs = runBlocking { repo.db.reminders().all() }
        assertEquals(setOf("Вес", "Зарядка"), rs.map { it.title }.toSet())
        assertEquals("Здоровье", rs.first { it.title == "Вес" }.category)
        assertEquals(1, runBlocking { repo.db.measurements().all().size })
        assertEquals(OccState.DONE, runBlocking { repo.db.occurrences().all() }.first { it.reminderId == b }.state)
        assertEquals(1, runBlocking { repo.db.sets().all().size })
        assertNotNull("после импорта будильники пересчитаны", pi(rs.first { it.title == "Вес" }.id, 0))
        // битый файл не трогает данные
        assertThrows(Exception::class.java) { runBlocking { repo.importJson("{ это не json") } }
        assertThrows(Exception::class.java) { runBlocking { repo.importJson("""{"version":99}""") } }
        assertEquals(2, runBlocking { repo.db.reminders().all().size })
    }

    @Test fun deleteRemovesEverythingOfReminder() {
        val id = save(reminder("Del", daily(8 * 60)))
        val due = dueToday(8 * 60)
        fire(id, due); waitFor { activeNotification(id) != null }
        runBlocking { repo.delete(id) }
        assertNull(pi(id, 0)); assertNull(activeNotification(id))
        assertTrue(runBlocking { repo.db.occurrences().all() }.none { it.reminderId == id })
    }

    @Test fun checklistToggleKeepsIndexes() {
        val id = save(reminder("C", daily(8 * 60)) { it.copy(checklist = "a\nb\nc") })
        val due = dueToday(8 * 60)
        runBlocking { repo.toggleChecked(id, due, 2); repo.toggleChecked(id, due, 0) }
        assertEquals("0,2", occ(id, due)!!.checked)
        runBlocking { repo.toggleChecked(id, due, 0) }
        assertEquals("2", occ(id, due)!!.checked)
        assertEquals(3, runBlocking { repo.get(id)!! }.checkItems.size)
    }

    @Test fun streakCountsConsecutiveDoneDaysAndBreaksOnMiss() {
        val td = today()
        val id = save(reminder("S", daily(8 * 60)))
        val r = runBlocking { repo.get(id)!! }
        runBlocking {
            for (k in 1..3) repo.setState(id, dueMillis(td.minusDays(k.toLong()), 8 * 60), OccState.DONE)
            repo.setState(id, dueMillis(td.minusDays(4), 8 * 60), OccState.MISSED)
            repo.setState(id, dueMillis(td.minusDays(5), 8 * 60), OccState.DONE)
        }
        assertEquals("сегодня ещё не отмечено, серия идёт по прошлым дням", 3, runBlocking { repo.streak(r) })
        runBlocking { repo.setState(id, dueToday(8 * 60), OccState.DONE) }
        assertEquals(4, runBlocking { repo.streak(r) })
    }

    @Test fun nextDueLogic() {
        val r = reminder("N", daily(0 * 60 + 1))
        val next = Alarms.nextDue(r, System.currentTimeMillis())!!
        assertTrue(next > System.currentTimeMillis())
        assertTrue("не дальше суток", next - System.currentTimeMillis() <= 24 * 3600_000L + 60_000)
        val past = reminder("P", onceAt(today().minusDays(1), 600))
        assertNull("разовое в прошлом больше не сработает", Alarms.nextDue(past, System.currentTimeMillis()))
        val future = reminder("F", onceAt(today().plusDays(10), 600))
        assertEquals(dueMillis(today().plusDays(10), 600), Alarms.nextDue(future, System.currentTimeMillis()))
    }

    // ---- условия ----

    private fun condReminder(c: Condition, trigger: Trigger = Trigger.CONDITION, sched: Schedule = Schedule(times = emptyList())) =
        save(reminder("Усл", sched) { it.copy(triggerMode = trigger, conditions = listOf(c).toJson()) })

    @Test fun conditionFiresRespectingMinInterval() {
        val id = condReminder(Condition(CondKind.WIFI, ssid = "Дом", minIntervalMin = 60))
        val r = runBlocking { repo.get(id)!! }
        assertTrue(runBlocking { Conditions.fire(ctx, r, 0) })
        waitFor { activeNotification(id) != null }
        assertEquals(1, runBlocking { repo.db.occurrences().all().size })
        assertFalse("слишком рано", runBlocking { Conditions.fire(ctx, r, 0) })
        assertTrue("через 61 минуту можно", runBlocking { Conditions.fire(ctx, r, 0, System.currentTimeMillis() + 61 * 60_000L) })
        assertFalse("несуществующее условие", runBlocking { Conditions.fire(ctx, r, 5) })
    }

    @Test fun conditionOnceFiresOnlyOnce() {
        val id = condReminder(Condition(CondKind.PLACE, lat = 1.0, lon = 1.0, once = true))
        val r = runBlocking { repo.get(id)!! }
        assertTrue(runBlocking { Conditions.fire(ctx, r, 0) })
        assertFalse(runBlocking { Conditions.fire(ctx, r, 0, System.currentTimeMillis() + 10 * 86_400_000L) })
        runBlocking { repo.save(r) } // правка сбрасывает «один раз»
        assertTrue(runBlocking { Conditions.fire(ctx, r, 0) })
    }

    @Test fun conditionPlusWindow() {
        val now = LocalDateTime.now()
        val m = now.hour * 60 + now.minute
        val inside = Schedule(ScheduleType.DAYS_OF_WEEK, times = emptyList(), windowStart = 0, windowEnd = 24 * 60 - 1)
        val outside = if (m < 720) Schedule(ScheduleType.DAYS_OF_WEEK, times = emptyList(), windowStart = 1000, windowEnd = 1100)
        else Schedule(ScheduleType.DAYS_OF_WEEK, times = emptyList(), windowStart = 0, windowEnd = 60)
        val wrongDay = Schedule(ScheduleType.DAYS_OF_WEEK, times = emptyList(), daysOfWeek = setOf((now.dayOfWeek.value % 7) + 1), windowStart = 0, windowEnd = 1439)
        val c = Condition(CondKind.WIFI, ssid = "x", minIntervalMin = 0)
        val ok = runBlocking { repo.get(condReminder(c, Trigger.BOTH, inside))!! }
        val late = runBlocking { repo.get(condReminder(c, Trigger.BOTH, outside))!! }
        val day = runBlocking { repo.get(condReminder(c, Trigger.BOTH, wrongDay))!! }
        assertTrue(runBlocking { Conditions.fire(ctx, ok, 0) })
        assertFalse("вне окна", runBlocking { Conditions.fire(ctx, late, 0) })
        assertFalse("не в тот день недели", runBlocking { Conditions.fire(ctx, day, 0) })
    }

    @Test fun onMatchChecksKindDirectionAndName() {
        val a = condReminder(Condition(CondKind.WIFI, enter = true, ssid = "Дом-5G", minIntervalMin = 0))
        val b = condReminder(Condition(CondKind.WIFI, enter = false, ssid = "Дом-5G", minIntervalMin = 0))
        val c = condReminder(Condition(CondKind.BLUETOOTH, enter = true, btAddress = "AA:BB:CC", minIntervalMin = 0))
        runBlocking { Conditions.onMatch(ctx) { it.kind == CondKind.WIFI && it.enter && it.ssid.equals("дом-5g", true) } }
        assertEquals(setOf(a), runBlocking { repo.db.occurrences().all() }.map { it.reminderId }.toSet())
        runBlocking { Conditions.onMatch(ctx) { it.kind == CondKind.BLUETOOTH && it.enter && it.btAddress.equals("aa:bb:cc", true) } }
        assertEquals(setOf(a, c), runBlocking { repo.db.occurrences().all() }.map { it.reminderId }.toSet())
        runBlocking { Conditions.onMatch(ctx) { it.kind == CondKind.WIFI && it.ssid == "Чужая" } }
        assertFalse(runBlocking { repo.db.occurrences().all() }.any { it.reminderId == b })
    }

    @Test fun pausedConditionalReminderDoesNotFire() {
        val id = condReminder(Condition(CondKind.WIFI, ssid = "Дом", minIntervalMin = 0))
        val r = runBlocking { repo.get(id)!! }
        runBlocking { repo.setStatus(r, Status.PAUSED) }
        runBlocking { Conditions.onMatch(ctx) { true } }
        assertTrue(runBlocking { repo.db.occurrences().all() }.isEmpty())
    }

    @Test fun csvExportQuotesAndFormats() {
        val cfg = MeasureConfig(listOf(MeasureField("Вес; кг", "кг")))
        val id = save(reminder("С \"кавычками\"", daily(8 * 60), Kind.MEASURE, cfg.toJson()))
        runBlocking { repo.addMeasurements(id, System.currentTimeMillis(), mapOf("Вес; кг" to 81.5)) }
        val csv = runBlocking { repo.measurementsCsv() }
        val line = csv.lines()[1]
        assertTrue(line, line.startsWith("\"С \"\"кавычками\"\"\";\"Вес; кг\";"))
        assertTrue(line, line.endsWith(";81,5"))
        val habits = runBlocking { repo.habitsCsv(listOf(runBlocking { repo.get(id)!! }), today().minusDays(1), today()) }
        assertEquals(2, habits.lines().filter { it.isNotBlank() }.size)
    }

    @Test fun widgetsRefreshDoesNotCrash() {
        save(reminder("W", daily(8 * 60)))
        app.napominator.widget.Widgets.refresh(ctx)
        Thread.sleep(1500)
    }
}
