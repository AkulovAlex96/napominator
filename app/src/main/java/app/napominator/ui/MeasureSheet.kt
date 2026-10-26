package app.napominator.ui

import app.napominator.data.fmt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.napominator.data.OccState
import app.napominator.data.Reminder
import app.napominator.data.Repo
import app.napominator.data.epochDayOf
import app.napominator.data.measureConfig
import kotlinx.coroutines.launch

data class MeasureTarget(val reminder: Reminder, val dueAt: Long?, val ts: Long = System.currentTimeMillis())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasureSheet(t: MeasureTarget, repo: Repo, onDismiss: () -> Unit) {
    val cfg = t.reminder.measureConfig ?: return
    val scope = rememberCoroutineScope()
    val texts = remember { mutableStateListOf(*Array(cfg.fields.size) { "" }) }
    val hints = remember { mutableStateListOf(*Array(cfg.fields.size) { "" }) }
    var total by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(t) {
        val last = repo.db.measurements().last(t.reminder.id)
        if (last != null && !cfg.accumulative) {
            cfg.fields.forEachIndexed { i, f -> if (f.name == last.field) hints[i] = "прошлое: ${fmt(last.value)}" }
        }
        if (cfg.accumulative) total = repo.db.measurements().sum(t.reminder.id, epochDayOf(t.ts), cfg.fields.first().name)
    }

    val parsed = texts.map { it.replace(',', '.').trim().toDoubleOrNull() }
    val inRange = cfg.fields.indices.all { i ->
        val v = parsed[i] ?: return@all false
        (cfg.fields[i].min == null || v >= cfg.fields[i].min!!) && (cfg.fields[i].max == null || v <= cfg.fields[i].max!!)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t.reminder.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (cfg.accumulative) {
                val unit = cfg.fields.first().unit
                Text("Сегодня: ${fmt(total ?: 0.0)} $unit" + (cfg.goal?.let { " из ${fmt(it)}" } ?: ""), color = ext.muted)
                val step = cfg.fields.first().step
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(step / 2, step, step * 2).forEach { v -> AssistChip(onClick = { texts[0] = fmt(v) }, label = { Text("+${fmt(v)}") }) }
                }
            }
            cfg.fields.forEachIndexed { i, f ->
                OutlinedTextField(
                    value = texts[i], onValueChange = { texts[i] = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(f.name + if (f.unit.isNotBlank()) ", ${f.unit}" else "") },
                    supportingText = { if (hints[i].isNotBlank()) Text(hints[i]) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (t.dueAt != null) OutlinedButton({
                    scope.launch { repo.setState(t.reminder.id, t.dueAt, OccState.SKIPPED); onDismiss() }
                }) { Text("Пропустить") }
                Button(
                    enabled = inRange,
                    onClick = {
                        scope.launch {
                            repo.addMeasurements(t.reminder.id, t.ts, cfg.fields.mapIndexed { i, f -> f.name to parsed[i]!! }.toMap())
                            if (t.dueAt != null) {
                                val done = if (cfg.accumulative && cfg.goal != null) {
                                    repo.db.measurements().sum(t.reminder.id, epochDayOf(t.ts), cfg.fields.first().name) >= cfg.goal
                                } else true
                                if (done) repo.setState(t.reminder.id, t.dueAt, OccState.DONE)
                            }
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Сохранить") }
            }
        }
    }
}
