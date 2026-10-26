package app.napominator

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.napominator.TestUtil.daily
import app.napominator.TestUtil.dueToday
import app.napominator.TestUtil.reminder
import app.napominator.TestUtil.repo
import app.napominator.data.CondKind
import app.napominator.data.Kind
import app.napominator.data.MeasureConfig
import app.napominator.data.MeasureField
import app.napominator.data.OccState
import app.napominator.data.Trigger
import app.napominator.data.conds
import app.napominator.data.toJson
import app.napominator.data.today
import app.napominator.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() = TestUtil.reset()
    @After fun tearDown() { scenario?.close() }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java); rule.waitForIdle() }
    private fun save(r: app.napominator.data.Reminder) = runBlocking { repo.save(r) }
    private fun tab(name: String) { rule.onNodeWithText(name).performClick(); rule.waitForIdle() }
    private fun input(label: String, text: String, scroll: Boolean = true) {
        val n = rule.onNode(hasText(label) and hasSetTextAction())
        if (scroll) n.performScrollTo()
        n.performTextInput(text)
    }

    @Test fun createReminderThroughUiShowsOnToday() {
        launch()
        rule.onNodeWithContentDescription("Новое напоминание").performClick()
        rule.onNodeWithText("Новое напоминание").assertIsDisplayed()
        input("Название", "Выпить воды")
        rule.onNodeWithText("Сохранить").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Сегодня"), 5000)
        val saved = runBlocking { repo.db.reminders().all() }
        assertEquals(1, saved.size)
        assertEquals(Trigger.TIME, saved[0].triggerMode)
        assertEquals("каждый день, 09:00", app.napominator.data.AppJson.decodeFromString<app.napominator.data.Schedule>(saved[0].schedule).describe())
    }

    @Test fun emptyTitleShowsErrorAndDoesNotSave() {
        launch()
        rule.onNodeWithContentDescription("Новое напоминание").performClick()
        rule.onNodeWithText("Сохранить").performClick()
        rule.onNodeWithText("Введите название.").performScrollTo().assertIsDisplayed()
        assertTrue(runBlocking { repo.db.reminders().all() }.isEmpty())
    }

    @Test fun tapCheckMarksDoneAndUntapReverts() {
        val id = save(reminder("Зарядка", daily(0 * 60 + 1)))
        launch()
        rule.onNodeWithText("Зарядка").assertIsDisplayed()
        rule.onNodeWithContentDescription("Отметить").performClick()
        rule.waitUntil(5000) { runBlocking { repo.db.occurrences().find(id, dueToday(1)) }?.state == OccState.DONE }
        rule.onNodeWithContentDescription("Выполнено").performClick()
        rule.waitUntil(5000) { runBlocking { repo.db.occurrences().find(id, dueToday(1)) }?.state == OccState.PENDING }
    }

    @Test fun measurementEnteredThroughSheetAppearsOnChart() {
        val cfg = MeasureConfig(listOf(MeasureField("Вес", "кг")))
        val mid = save(reminder("Вес утром", daily(0 * 60 + 1), Kind.MEASURE, cfg.toJson()))
        launch()
        rule.onNodeWithContentDescription("Ввести значение").performClick()
        input("Вес, кг", "82,4", scroll = false)
        rule.onNodeWithText("Сохранить").performClick()
        rule.waitUntil(5000) { runBlocking { repo.db.measurements().all() }.isNotEmpty() }
        assertEquals(82.4, runBlocking { repo.db.measurements().all() }[0].value, 1e-9)
        rule.waitUntil(5000) { runBlocking { repo.db.occurrences().find(mid, dueToday(1)) }?.state == OccState.DONE }
        tab("Графики")
        rule.waitUntilAtLeastOneExists(hasText("82,4", substring = true), 5000)
    }

    @Test fun measurementRejectsOutOfRangeAndGarbage() {
        val cfg = MeasureConfig(listOf(MeasureField("Пульс", "уд/мин", min = 30.0, max = 220.0)))
        save(reminder("Пульс", daily(0 * 60 + 1), Kind.MEASURE, cfg.toJson()))
        launch()
        rule.onNodeWithContentDescription("Ввести значение").performClick()
        input("Пульс, уд/мин", "999", scroll = false)
        rule.onNodeWithText("Сохранить").performClick() // кнопка выключена
        Thread.sleep(500)
        assertTrue(runBlocking { repo.db.measurements().all() }.isEmpty())
    }

    @Test fun habitTableCellTogglesToday() {
        val id = save(reminder("Чтение", daily(0 * 60 + 1)))
        launch()
        tab("Привычки")
        rule.onNodeWithText("Чтение").assertIsDisplayed()
        // сегодняшний столбец: кликаем по ячейке строки (ожидающая ячейка без текста) — ищем по описанию
        rule.onAllNodesWithContentDescription("ожидает").onFirst().performClick()
        rule.waitUntil(5000) { runBlocking { repo.db.occurrences().find(id, dueToday(1)) }?.state == OccState.DONE }
        rule.onAllNodesWithContentDescription("выполнено").onFirst().assertIsDisplayed()
    }

    @Test fun monthModeAndNavigationDoNotCrash() {
        save(reminder("Чтение", daily(0 * 60 + 1)))
        launch()
        tab("Привычки")
        rule.onNodeWithText("Месяц").performClick()
        rule.onNodeWithText("Октябрь 2026", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Назад").performClick()
        rule.onNodeWithText("Сентябрь 2026").assertIsDisplayed()
        rule.onNodeWithContentDescription("Вперёд").performClick()
        rule.onNodeWithText("Неделя").performClick()
        rule.onNodeWithText("Экспорт таблицы в CSV").assertIsDisplayed()
    }

    @Test fun calendarModesAndDayList() {
        save(reminder("Чтение", daily(18 * 60)) { it.copy(category = "Личное") })
        launch()
        tab("Календарь")
        rule.onNodeWithText("Чтение").assertIsDisplayed()
        rule.onNodeWithText("Неделя").performClick()
        rule.onNodeWithText("Понедельник", substring = true).assertIsDisplayed()
        rule.onNodeWithText("День").performClick()
        rule.onNodeWithText("26 октября", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Личное").performClick() // фильтр по категории
        rule.onNodeWithText("Чтение").assertIsDisplayed()
        rule.onNodeWithText("Личное").performClick()
    }

    @Test fun conditionReminderCanBeCreatedThroughUi() {
        launch()
        rule.onNodeWithContentDescription("Новое напоминание").performClick()
        input("Название", "Полить цветы")
        rule.onNode(hasText("Условие") and !hasSetTextAction()).performScrollTo().performClick()
        rule.onNodeWithText("условие", substring = true).performScrollTo().performClick()
        rule.onNode(hasText("Wi-Fi")).performClick()
        input("Имя сети (SSID)", "Дом-5G", scroll = false)
        rule.onNodeWithText("Готово").performClick()
        rule.onNodeWithText("Дом-5G", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Сохранить").performClick()
        rule.waitUntil(5000) { runBlocking { repo.db.reminders().all() }.isNotEmpty() }
        val r = runBlocking { repo.db.reminders().all() }[0]
        assertEquals(Trigger.CONDITION, r.triggerMode)
        assertEquals(CondKind.WIFI, r.conds[0].kind)
        assertEquals("Дом-5G", r.conds[0].ssid)
        assertTrue(r.conds[0].enter)
    }

    @Test fun conditionWithoutConditionsIsRejected() {
        launch()
        rule.onNodeWithContentDescription("Новое напоминание").performClick()
        input("Название", "Пустое условие")
        rule.onNode(hasText("Условие") and !hasSetTextAction()).performScrollTo().performClick()
        rule.onNodeWithText("Сохранить").performClick()
        rule.onNodeWithText("Проверьте расписание", substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(runBlocking { repo.db.reminders().all() }.isEmpty())
    }

    @Test fun detailScreenShowsHistoryAndStreak() {
        val id = save(reminder("Йога", daily(8 * 60)))
        runBlocking { for (k in 1..2) repo.setState(id, app.napominator.data.dueMillis(today().minusDays(k.toLong()), 8 * 60), OccState.DONE) }
        launch()
        rule.onNodeWithText("Йога").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Серия"), 5000)
        rule.onNodeWithText("ИСТОРИЯ").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("выполнено").onFirst().performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Изменить").performClick()
        rule.onNodeWithText("Редактирование").assertIsDisplayed()
    }

    @Test fun themeSwitchAndSettingsRender() {
        launch()
        tab("Ещё")
        rule.onNodeWithText("Тёмная").performClick()
        assertEquals("dark", TestUtil.app.themeMode.value)
        rule.onNodeWithText("Система").performClick()
        rule.onNodeWithText("НАДЁЖНОСТЬ").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Экспорт всего в JSON").performScrollTo().assertIsDisplayed()
        rule.onNode(hasText("Варианты «Отложить», минуты") and hasSetTextAction()).performScrollTo().performTextReplacement("10, 15")
        assertEquals(listOf(10, 15), TestUtil.app.prefs.snoozePresets)
    }

    @Test fun searchAndFilterInAllReminders() {
        save(reminder("Купить хлеб", daily(8 * 60)) { it.copy(category = "Дом", tags = "магазин") })
        save(reminder("Позвонить врачу", daily(9 * 60)) { it.copy(category = "Здоровье") })
        launch()
        tab("Ещё")
        input("Поиск", "хлеб")
        rule.onNodeWithText("Купить хлеб").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Позвонить врачу").assertCountEquals(0)
    }

    @Test fun habitSetCanBeCreatedAndFiltersRows() {
        save(reminder("Раз", daily(8 * 60)))
        save(reminder("Два", daily(9 * 60)))
        launch()
        tab("Привычки")
        rule.onNodeWithContentDescription("Новый набор").performClick()
        input("Название", "Набор А")
        rule.onAllNodesWithText("Раз").onLast().performClick()
        rule.onNodeWithText("Сохранить").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Набор А"), 5000)
        rule.onAllNodesWithText("Два").assertCountEquals(0)
        rule.onNodeWithText("Все").performClick()
        rule.onNodeWithText("Два").assertIsDisplayed()
    }
}
