package app.napominator.ui

import app.napominator.data.fmt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.napominator.data.Kind
import app.napominator.data.Measurement
import app.napominator.data.Reminder
import app.napominator.data.Repo
import app.napominator.data.Status
import app.napominator.data.measureConfig
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Pt(val ts: Long, val v: Double)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartsScreen(repo: Repo, reminders: List<Reminder>, initial: Long?, onMeasure: (MeasureTarget) -> Unit) {
    val options = reminders.filter { it.kind == Kind.MEASURE && it.status != Status.ARCHIVED }
    var selId by remember(initial) { mutableLongStateOf(initial ?: -1L) }
    var fieldIdx by remember { mutableIntStateOf(0) }
    var period by remember { mutableIntStateOf(30) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            runCatching { app.napominator.data.writeText(ctx, uri, app.napominator.data.csvWithBom(repo.measurementsCsv(selId.takeIf { it > 0 } ?: options.firstOrNull()?.id))) }
                .onSuccess { android.widget.Toast.makeText(ctx, "Файл сохранён", android.widget.Toast.LENGTH_SHORT).show() }
                .onFailure { android.widget.Toast.makeText(ctx, "Ошибка: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Графики", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
        if (options.isEmpty()) {
            Text("Создайте напоминание типа «Измерение» (вес, давление, вода…), и здесь появятся графики.", color = ext.muted, modifier = Modifier.padding(4.dp))
            return@Column
        }
        val r = options.firstOrNull { it.id == selId } ?: options.first()
        val cfg = r.measureConfig ?: return@Column
        val fIdx = fieldIdx.coerceIn(0, cfg.fields.lastIndex)
        val field = cfg.fields[fIdx]

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { o -> FilterChip(o.id == r.id, { selId = o.id; fieldIdx = 0 }, { Text(o.title) }) } }
        if (cfg.fields.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { cfg.fields.forEachIndexed { i, f -> FilterChip(i == fIdx, { fieldIdx = i }, { Text(f.name) }) } }
        Seg(listOf(7 to "7 дн", 30 to "30 дн", 90 to "3 мес", 365 to "Год", 0 to "Всё"), period, { period = it }, Modifier.fillMaxWidth())

        val all by remember(r.id) { repo.measurementsFor(r.id) }.collectAsState(emptyList())
        val from = if (period == 0) 0L else System.currentTimeMillis() - period * 86_400_000L
        val ofField = all.filter { it.field == field.name && it.ts >= from }
        val pts: List<Pt> = if (cfg.accumulative) ofField.groupBy { it.epochDay }.map { (d, l) -> Pt(d * 86_400_000L + 43_200_000L, l.sumOf { it.v() }) }.sortedBy { it.ts }
        else ofField.map { Pt(it.ts, it.value) }

        Card(shape = cardShape, border = BorderStroke(1.dp, ext.line), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pts.isEmpty()) {
                    Text("Нет значений за этот период.", color = ext.muted)
                } else {
                    val last = pts.last().v
                    val delta = last - pts.first().v
                    Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                        Text(fmt(last), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                        Text(" ${field.unit}", fontSize = 18.sp, color = ext.muted, modifier = Modifier.padding(bottom = 6.dp).weight(1f))
                        if (pts.size > 1) Text((if (delta > 0) "+" else if (delta < 0) "−" else "") + fmt(Math.abs(Math.round(delta * 10) / 10.0)) + " за период", color = ext.muted)
                    }
                    LineChart(pts, cfg.goal.takeIf { cfg.accumulative.not() })
                }
            }
        }
        if (pts.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("мин" to pts.minOf { it.v }, "макс" to pts.maxOf { it.v }, "среднее" to pts.map { it.v }.average()).forEach { (l, v) ->
                    Card(Modifier.weight(1f), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), border = BorderStroke(1.dp, ext.line), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(8.dp).fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text(fmt(Math.round(v * 10) / 10.0), fontWeight = FontWeight.Bold, fontSize = 16.sp); Text(l, fontSize = 11.sp, color = ext.muted)
                        }
                    }
                }
            }
        }
        Button({ onMeasure(MeasureTarget(r, null)) }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Add, null); Text(" Добавить значение") }
        androidx.compose.material3.OutlinedButton({ exportLauncher.launch("${r.title}.csv") }, Modifier.fillMaxWidth()) { Text("Экспорт значений в CSV") }

        SectionLabel("Последние значения")
        all.asReversed().take(12).forEach { m ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(m.ts)), Modifier.weight(1f))
                Text("${m.field}: ${fmt(m.value)}", fontWeight = FontWeight.Bold)
                IconButton({ scope.launch { repo.deleteMeasurement(m) } }) { Icon(Icons.Filled.Close, "Удалить значение", tint = ext.muted) }
            }
        }
        Row(Modifier.height(16.dp)) {}
    }
}

private fun Measurement.v() = value

@Composable
private fun LineChart(pts: List<Pt>, goal: Double?) {
    val tm = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.primary
    val grid = ext.line
    val muted = ext.muted
    val surface = MaterialTheme.colorScheme.surface
    val warn = ext.warn
    val label = TextStyle(fontSize = 11.sp, color = muted)
    Canvas(Modifier.fillMaxWidth().height(190.dp)) {
        val padL = 38.dp.toPx(); val padB = 22.dp.toPx(); val padT = 8.dp.toPx(); val padR = 8.dp.toPx()
        var lo = minOf(pts.minOf { it.v }, goal ?: Double.MAX_VALUE); var hi = maxOf(pts.maxOf { it.v }, goal ?: -Double.MAX_VALUE)
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.1; lo -= pad; hi += pad
        val raw = (hi - lo) / 3
        val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
        val step = mag * when { raw / mag <= 1 -> 1.0; raw / mag <= 2 -> 2.0; raw / mag <= 5 -> 5.0; else -> 10.0 }
        lo = Math.floor(lo / step) * step; hi = Math.ceil(hi / step) * step
        val w = size.width - padL - padR; val h = size.height - padT - padB
        val t0 = pts.first().ts; val t1 = pts.last().ts.let { if (it == t0) t0 + 1 else it }
        fun x(t: Long) = padL + w * (t - t0).toFloat() / (t1 - t0).toFloat()
        fun y(v: Double) = padT + h * (1f - ((v - lo) / (hi - lo)).toFloat())
        var v = lo
        while (v <= hi + 1e-9) {
            drawLine(grid, Offset(padL, y(v)), Offset(size.width - padR, y(v)), 1.dp.toPx())
            drawText(tm, fmt(Math.round(v * 10) / 10.0), Offset(0f, y(v) - 7.dp.toPx()), label)
            v += step
        }
        goal?.let { drawLine(warn, Offset(padL, y(it)), Offset(size.width - padR, y(it)), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))) }
        val path = Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.ts), y(p.v)) else path.lineTo(x(p.ts), y(p.v)) }
        drawPath(path, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (pts.size <= 40) pts.forEach { drawCircle(surface, 4.dp.toPx(), Offset(x(it.ts), y(it.v))); drawCircle(line, 4.dp.toPx(), Offset(x(it.ts), y(it.v)), style = Stroke(2.dp.toPx())) }
        val df = SimpleDateFormat("d MMM", Locale("ru"))
        drawText(tm, df.format(Date(t0)), Offset(padL, size.height - padB + 4.dp.toPx()), label)
        val endTxt = df.format(Date(t1)); val m = tm.measure(endTxt, label)
        drawText(tm, endTxt, Offset(size.width - padR - m.size.width, size.height - padB + 4.dp.toPx()), label)
    }
}
