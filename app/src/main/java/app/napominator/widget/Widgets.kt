package app.napominator.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Widgets {
    /** Перерисовать все виджеты (после любого изменения данных). */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { UpcomingWidget().updateAll(app) }
            runCatching { HabitsWidget().updateAll(app) }
            runCatching { MeasureWidget().updateAll(app) }
        }
    }
}
