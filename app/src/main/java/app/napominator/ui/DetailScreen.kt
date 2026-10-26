package app.napominator.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.napominator.data.Kind
import app.napominator.data.OccState
import app.napominator.data.Reminder
import app.napominator.data.Repo
import app.napominator.data.Status
import app.napominator.data.Trigger
import app.napominator.data.describe
import app.napominator.data.tagList
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Карточка напоминания: расписание, серия, история срабатываний. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(repo: Repo, id: Long, onEdit: () -> Unit, onChart: () -> Unit, onClose: () -> Unit) {
    var r by remember { mutableStateOf<Reminder?>(null) }
    var streak by remember { mutableIntStateOf(0) }
    val history by remember(id) { repo.historyFor(id) }.collectAsState(emptyList())
    LaunchedEffect(id, history) { r = repo.get(id); r?.let { streak = repo.streak(it) } }
    val rem = r ?: return
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (confirmDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить «${rem.title}»?") },
        text = { Text("Напоминание и его история срабатываний будут удалены. Записанные измерения останутся в базе и в резервных копиях.") },
        confirmButton = { androidx.compose.material3.TextButton({ scope.launch { repo.delete(id); onClose() } }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { androidx.compose.material3.TextButton({ confirmDelete = false }) { Text("Отмена") } },
    )

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text(rem.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onEdit) { Icon(Icons.Filled.Edit, "Изменить") }
            IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Удалить", tint = MaterialTheme.colorScheme.error) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.fillMaxWidth(), shape = cardShape, border = BorderStroke(1.dp, ext.line), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(rem.describe(), fontWeight = FontWeight.SemiBold)
                    if (rem.note.isNotBlank()) Text(rem.note, color = ext.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (rem.status != Status.ACTIVE) MiniChip(if (rem.status == Status.PAUSED) "на паузе" else "в архиве", null, ext.warnSoft, ext.warn)
                        if (rem.category.isNotBlank()) MiniChip(rem.category, null, ext.accentSoft, ext.accentInk)
                        rem.tagList.forEach { MiniChip("#$it") }
                        MiniChip(when (rem.priority) { 2 -> "критичное"; 1 -> "важное"; else -> "обычное" })
                        if (rem.repeatEveryMin > 0) MiniChip("повтор каждые ${rem.repeatEveryMin} мин")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Серия", "$streak", Modifier.weight(1f))
                StatCard("Выполнено", "${history.count { it.state == OccState.DONE }}", Modifier.weight(1f))
                StatCard("Пропущено", "${history.count { it.state == OccState.MISSED || it.state == OccState.SKIPPED }}", Modifier.weight(1f))
            }
            if (rem.kind == Kind.MEASURE) Button(onChart, Modifier.fillMaxWidth()) { Text("Открыть график") }
            SectionLabel("История")
            if (history.isEmpty()) Text("Срабатываний пока не было.", color = ext.muted, modifier = Modifier.padding(4.dp))
            history.forEach { o ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(o.dueAt)), Modifier.weight(1f))
                    when (o.state) {
                        OccState.DONE -> MiniChip("выполнено", null, ext.okSoft, ext.ok)
                        OccState.SKIPPED -> MiniChip("пропущено", null, ext.missSoft, ext.miss)
                        OccState.MISSED -> MiniChip("не отмечено", null, ext.missSoft, ext.miss)
                        OccState.SHIFTED -> MiniChip("перенесено", null, ext.warnSoft, ext.warn)
                        OccState.PENDING -> MiniChip("ждёт")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), border = BorderStroke(1.dp, ext.line), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(label, fontSize = 11.sp, color = ext.muted)
        }
    }
}
