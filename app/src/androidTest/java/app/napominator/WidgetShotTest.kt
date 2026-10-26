package app.napominator

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.napominator.TestUtil.ctx
import app.napominator.TestUtil.daily
import app.napominator.TestUtil.reminder
import app.napominator.TestUtil.repo
import app.napominator.data.Kind
import app.napominator.data.MeasureConfig
import app.napominator.data.MeasureField
import app.napominator.data.OccState
import app.napominator.data.dueMillis
import app.napominator.data.toJson
import app.napominator.data.today
import app.napominator.widget.HabitsWidget
import app.napominator.widget.HabitsWidgetReceiver
import app.napominator.widget.MeasureWidget
import app.napominator.widget.MeasureWidgetReceiver
import app.napominator.widget.UpcomingWidget
import app.napominator.widget.UpcomingWidgetReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Рисует виджеты через AppWidgetHost и сохраняет снимки в /sdcard/Download для проверки глазами. */
@RunWith(AndroidJUnit4::class)
class WidgetShotTest {
    @Before fun setUp() = TestUtil.reset()

    private fun seed() = runBlocking {
        val now = System.currentTimeMillis()
        val a = repo.save(reminder("Зарядка", daily(7 * 60 + 30)))
        repo.setState(a, dueMillis(today(), 7 * 60 + 30), OccState.DONE)
        repo.save(reminder("Таблетки", daily(13 * 60, 20 * 60)))
        repo.save(reminder("Чтение 20 минут", daily(21 * 60)))
        repo.save(reminder("Позвонить маме", daily(15 * 60 + 30)))
        val cfg = MeasureConfig(listOf(MeasureField("Вес", "кг")))
        val w = repo.save(reminder("Вес", daily(8 * 60), Kind.MEASURE, cfg.toJson()))
        val vals = listOf(83.1, 82.9, 82.8, 82.9, 82.5, 82.6, 82.4, 82.2, 82.3, 82.1, 82.0, 82.2, 81.9, 81.9)
        vals.forEachIndexed { i, v -> repo.addMeasurements(w, now - (vals.size - 1 - i) * 86_400_000L, mapOf("Вес" to v)) }
    }

    @Test fun renderAllWidgets() {
        seed()
        // Самый надёжный способ получить RemoteViews: обновить Glance и взять их у менеджера.
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            val awm = AppWidgetManager.getInstance(ctx)
            val host = AppWidgetHost(ctx, 4243)
            val dir = File("/sdcard/Download").also { it.mkdirs() }
            for ((cls, widget, name) in listOf(
                Triple(UpcomingWidgetReceiver::class.java, UpcomingWidget() as androidx.glance.appwidget.GlanceAppWidget, "upcoming"),
                Triple(HabitsWidgetReceiver::class.java, HabitsWidget() as androidx.glance.appwidget.GlanceAppWidget, "habits"),
                Triple(MeasureWidgetReceiver::class.java, MeasureWidget() as androidx.glance.appwidget.GlanceAppWidget, "measure"),
            )) {
                val id = host.allocateAppWidgetId()
                check(awm.bindAppWidgetIdIfAllowed(id, ComponentName(ctx, cls)))
                getSharedPrefs().edit().putLong("measure:$id", runBlocking { repo.db.reminders().all().first { it.kind == Kind.MEASURE }.id }).apply()
                runBlocking { widget.update(ctx, GlanceAppWidgetManager(ctx).getGlanceIdBy(id)) }
                Thread.sleep(2000)
                val w = 1000; val h = 480
                var bmp: Bitmap? = null
                host.startListening()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val view = host.createView(ctx, id, awm.getAppWidgetInfo(id))
                    view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, w, h)
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> Canvas(b).apply { drawColor(0xFF2B3A3F.toInt()); view.draw(this) } }
                }
                // Содержимое подтягивается асинхронно: даём хосту время и рисуем ещё раз.
                Thread.sleep(2000)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val view = host.createView(ctx, id, awm.getAppWidgetInfo(id))
                    view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, w, h)
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> Canvas(b).apply { drawColor(0xFF2B3A3F.toInt()); view.draw(this) } }
                }
                FileOutputStream(File(dir, "widget-$name.png")).use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
                host.deleteAppWidgetId(id)
            }
            host.stopListening()
        } finally { ui.dropShellPermissionIdentity() }
    }

    private fun getSharedPrefs() = ctx.getSharedPreferences("widgets", android.content.Context.MODE_PRIVATE)
}
