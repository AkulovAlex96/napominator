package app.napominator.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.glance.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider
import app.napominator.App
import app.napominator.alarm.Alarms
import app.napominator.alarm.Notifier
import app.napominator.data.CellKind
import app.napominator.data.Kind
import app.napominator.data.OccState
import app.napominator.data.Reminder
import app.napominator.data.Slot
import app.napominator.data.Status
import app.napominator.data.cellOf
import app.napominator.data.fmt
import app.napominator.data.measureConfig
import app.napominator.data.slotsFor
import app.napominator.data.today
import app.napominator.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RID = ActionParameters.Key<Long>("rid")
private val DUE = ActionParameters.Key<Long>("due")

private fun openApp(ctx: Context, rid: Long? = null, measure: Boolean = false): Intent =
    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply {
        if (rid != null) { putExtra(Alarms.EXTRA_ID, rid); putExtra(Alarms.EXTRA_DUE, 0L); putExtra(Notifier.EXTRA_OPEN_MEASURE, measure) }
    }

/** Отметка выполнения прямо из виджета. */
class ToggleDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val rid = parameters[RID] ?: return
        val due = parameters[DUE] ?: return
        val repo = App.get(context).repo
        val r = repo.get(rid) ?: return
        val occ = repo.db.occurrences().find(rid, due)
        if (r.kind == Kind.MEASURE && occ?.state != OccState.DONE) {
            context.startActivity(openApp(context, rid, true).putExtra(Alarms.EXTRA_DUE, due))
            return
        }
        repo.setState(rid, due, if (occ?.state == OccState.DONE) OccState.PENDING else OccState.DONE)
    }
}

/** Тап по привычке на сегодня: отметить все её срабатывания. */
class ToggleHabitAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val rid = parameters[RID] ?: return
        val repo = App.get(context).repo
        val r = repo.get(rid) ?: return
        if (r.kind == Kind.MEASURE) { context.startActivity(openApp(context, rid, true)); return }
        val day = today()
        val slots = slotsFor(listOf(r), repo.db.occurrences().range(day.toEpochDay(), day.toEpochDay()), day)
        val allDone = slots.isNotEmpty() && slots.all { it.state == OccState.DONE }
        slots.forEach { repo.setState(rid, it.dueAt, if (allDone) OccState.PENDING else OccState.DONE) }
    }
}

private suspend fun todaySlots(ctx: Context): List<Slot> {
    val repo = App.get(ctx).repo
    val day = today()
    val rems = repo.db.reminders().active()
    val occs = repo.db.occurrences().range(day.toEpochDay(), day.toEpochDay())
    val all = slotsFor(rems, occs, day)
    val now = System.currentTimeMillis()
    val open = all.filter { it.state != OccState.DONE && it.state != OccState.SKIPPED }
    return open.sortedBy { it.dueAt }.let { l -> l.filter { it.dueAt <= now } + l.filter { it.dueAt > now } }
}

@Composable
private fun WTitle(text: String, trailing: String? = null) {
    Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, GlanceModifier.defaultWeight(), style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = GlanceTheme.colors.onSurface))
        if (trailing != null) Text(trailing, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
    }
}

class UpcomingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val all = todaySlots(context)
        val fmtT = SimpleDateFormat("HH:mm", Locale.getDefault())
        provideContent {
            GlanceTheme {
                val rows = ((LocalSize.current.height.value - 56f) / 38f).toInt().coerceIn(1, 6)
                val slots = all.take(rows)
                Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(14.dp)) {
                    WTitle("Ближайшие", "+ добавить")
                    if (slots.isEmpty()) Text("На сегодня всё готово", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
                    slots.forEach { s ->
                        Row(GlanceModifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtT.format(Date(s.dueAt)), GlanceModifier.width(46.dp), style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GlanceTheme.colors.onSurface))
                            Text(s.reminder.title, GlanceModifier.defaultWeight().clickable(actionStartActivity(openApp(context))), maxLines = 1, style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurface))
                            Box(
                                GlanceModifier.size(32.dp).cornerRadius(16.dp).background(GlanceTheme.colors.secondaryContainer)
                                    .clickable(actionRunCallback<ToggleDoneAction>(actionParametersOf(RID to s.reminder.id, DUE to s.dueAt))),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (s.reminder.kind == Kind.MEASURE) "✎" else "✓", style = TextStyle(fontSize = 16.sp, color = GlanceTheme.colors.onSecondaryContainer)) }
                        }
                    }
                }
            }
        }
    }
}

class UpcomingWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = UpcomingWidget() }

class HabitsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = App.get(context).repo
        val day = today()
        val rems = repo.db.reminders().active()
        val occs = repo.db.occurrences().range(day.toEpochDay(), day.toEpochDay())
        val ms = repo.db.measurements().all()
        val now = System.currentTimeMillis()
        val all = rems.map { it to cellOf(it, day, occs, ms, now) }.filter { it.second.kind != CellKind.NONE }
        provideContent {
            GlanceTheme {
                val rowCount = ((LocalSize.current.height.value - 48f) / 70f).toInt().coerceIn(1, 3)
                val items = all.take(rowCount * 3)
                val done = all.count { it.second.kind == CellKind.DONE || it.second.kind == CellKind.VALUE }
                Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(12.dp)) {
                    WTitle("Привычки сегодня", if (all.isEmpty()) null else "$done из ${all.size}")
                    if (items.isEmpty()) Text("Нет запланированных", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
                    items.chunked(3).forEach { row ->
                        Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            row.forEach { (r, c) ->
                                val ok = c.kind == CellKind.DONE || c.kind == CellKind.VALUE
                                Box(GlanceModifier.defaultWeight().padding(horizontal = 3.dp)) {
                                    Column(
                                        GlanceModifier.fillMaxWidth().height(64.dp).cornerRadius(16.dp)
                                            .background(if (ok) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.secondaryContainer)
                                            .clickable(actionRunCallback<ToggleHabitAction>(actionParametersOf(RID to r.id))),
                                        verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(if (ok) (if (c.kind == CellKind.VALUE) c.text else "✓") else if (c.text.isNotEmpty()) c.text else "○", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
                                        Text(r.title, maxLines = 1, style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurface))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class HabitsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = HabitsWidget() }

private fun sparkline(vals: List<Double>, w: Int, h: Int, color: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    if (vals.size < 2) return bmp
    val c = Canvas(bmp)
    val lo = vals.min(); val hi = vals.max().let { if (it - lo < 1e-9) lo + 1 else it }
    val pad = h * 0.12f
    val path = Path()
    vals.forEachIndexed { i, v ->
        val x = pad + (w - 2 * pad) * i / (vals.size - 1)
        val y = pad + (h - 2 * pad) * (1f - ((v - lo) / (hi - lo)).toFloat())
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = h * 0.06f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    c.drawPath(path, p)
    return bmp
}

/** Выбор величины для конкретного экземпляра виджета «Измерение» хранится в настройках по номеру виджета. */
class MeasureWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = App.get(context)
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val rid = context.getSharedPreferences("widgets", Context.MODE_PRIVATE).getLong("measure:$widgetId", -1)
        val r: Reminder? = app.repo.get(rid) ?: app.repo.db.reminders().active().firstOrNull { it.kind == Kind.MEASURE }
        val cfg = r?.measureConfig
        val vals = if (r != null && cfg != null) app.repo.db.measurements().all().filter { it.reminderId == r.id && it.field == cfg.fields.first().name }.takeLast(14) else emptyList()
        val bmp = sparkline(vals.map { it.value }, 600, 140, 0xFF1E9C90.toInt())
        provideContent {
            GlanceTheme {
                Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(14.dp)) {
                    if (r == null || cfg == null) {
                        Text("Создайте напоминание «Измерение»", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
                    } else {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(GlanceModifier.defaultWeight().clickable(actionStartActivity(openApp(context)))) {
                                Text(r.title, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GlanceTheme.colors.onSurface))
                                Text(vals.lastOrNull()?.let { fmt(it.value) + " " + cfg.fields.first().unit } ?: "нет данных", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, color = GlanceTheme.colors.onSurface))
                            }
                            Box(
                                GlanceModifier.size(44.dp).cornerRadius(22.dp).background(GlanceTheme.colors.primary)
                                    .clickable(actionStartActivity(openApp(context, r.id, true))),
                                contentAlignment = Alignment.Center,
                            ) { Text("+", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onPrimary)) }
                        }
                        Spacer(GlanceModifier.height(6.dp))
                        Image(ImageProvider(bmp), "График за 14 значений", GlanceModifier.fillMaxWidth().defaultWeight())
                    }
                }
            }
        }
    }
}

class MeasureWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MeasureWidget() }
