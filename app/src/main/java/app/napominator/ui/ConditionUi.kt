package app.napominator.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.napominator.alarm.Conditions
import app.napominator.alarm.Notifier
import app.napominator.data.CondKind
import app.napominator.data.Condition
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/** Статус разрешений, нужных условиям, с кнопками выдачи. */
@Composable
fun ConditionPermissions(conds: List<Condition>) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val needLoc = conds.any { it.kind == CondKind.PLACE || it.kind == CondKind.WIFI }
    val needBg = conds.any { it.kind == CondKind.PLACE || it.kind == CondKind.WIFI }
    val needBt = conds.any { it.kind == CondKind.BLUETOOTH }
    val rows = remember(tick, conds.size) {
        buildList<Triple<String, Boolean, () -> Unit>> {
            if (!Notifier.canNotify(ctx)) add(Triple("Уведомления", false) { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) })
            if (needLoc) add(Triple("Геолокация" + if (conds.any { it.kind == CondKind.WIFI }) " (нужна, чтобы читать имя Wi-Fi)" else "", Conditions.hasLocation(ctx)) {
                locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            })
            if (needBg) add(Triple("Геолокация «всегда» (иначе после перезагрузки имя Wi-Fi и места недоступны)", Conditions.hasBackgroundLocation(ctx)) {
                if (!Conditions.hasLocation(ctx)) locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else if (Build.VERSION.SDK_INT >= 30) ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                else bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            })
            if (needBt) add(Triple("Bluetooth", Conditions.hasBtConnect(ctx)) { if (Build.VERSION.SDK_INT >= 31) btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) })
        }
    }
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ext.warnSoft).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Нужны разрешения", fontWeight = FontWeight.Bold, color = ext.warn)
        rows.forEach { (name, ok, act) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Filled.Check else Icons.Filled.Warning, null, tint = if (ok) ext.ok else ext.warn, modifier = Modifier.size(20.dp))
                Text(" $name", Modifier.weight(1f), color = ext.warn, fontSize = 14.sp)
                if (!ok) TextButton(act) { Text("Разрешить") }
            }
        }
        if (needBg && Build.VERSION.SDK_INT >= 30) Text("Для «всегда»: Разрешения → Геолокация → Разрешить всегда.", fontSize = 12.sp, color = ext.warn)
    }
}

@SuppressLint("MissingPermission")
private fun currentSsid(ctx: Context): String? {
    @Suppress("DEPRECATION")
    val s = ctx.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid ?: return null
    val t = s.removeSurrounding("\"")
    return if (t.isBlank() || t == "<unknown ssid>") null else t
}

@SuppressLint("MissingPermission")
private fun bondedDevices(ctx: Context): List<Pair<String, String>> = runCatching {
    ctx.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty().map { (it.name ?: it.address) to it.address }
}.getOrDefault(emptyList())

@Composable
fun ConditionDialog(initial: Condition?, onDismiss: () -> Unit, onSave: (Condition) -> Unit) {
    val ctx = LocalContext.current
    var c by remember { mutableStateOf(initial ?: Condition(CondKind.WIFI)) }
    var map by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    // Разрешения запрашиваем прямо здесь, при выборе вида условия, а не только в списке «Нужны разрешения».
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) {
            msg = null
            if (c.kind == CondKind.WIFI && c.ssid.isBlank()) currentSsid(ctx)?.let { c = c.copy(ssid = it) }
        } else msg = "Без разрешения на геолокацию система не отдаёт имя Wi-Fi. Введите имя сети вручную."
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { devices = bondedDevices(ctx); msg = if (devices.isNullOrEmpty()) "Нет сопряжённых устройств. Сначала подключите устройство в настройках Bluetooth." else null }
        else msg = "Без разрешения Bluetooth нельзя выбрать устройство из списка."
    }
    LaunchedEffect(c.kind) {
        when (c.kind) {
            CondKind.WIFI -> if (!Conditions.hasLocation(ctx)) locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            CondKind.BLUETOOTH -> if (Build.VERSION.SDK_INT >= 31 && !Conditions.hasBtConnect(ctx)) btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            CondKind.PLACE -> {}
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новое условие" else "Условие") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Seg(listOf(CondKind.PLACE to "Место", CondKind.WIFI to "Wi-Fi", CondKind.BLUETOOTH to "Bluetooth"), c.kind, { c = c.copy(kind = it) }, Modifier.fillMaxWidth())
                val (a, b) = when (c.kind) { CondKind.PLACE -> "Прихожу" to "Ухожу"; CondKind.WIFI, CondKind.BLUETOOTH -> "Подключаюсь" to "Отключаюсь" }
                Seg(listOf(true to a, false to b), c.enter, { c = c.copy(enter = it) }, Modifier.fillMaxWidth())
                when (c.kind) {
                    CondKind.PLACE -> {
                        OutlinedButton({ map = true }, Modifier.fillMaxWidth()) { Text(if (c.lat == 0.0 && c.lon == 0.0) "Выбрать место на карте" else "%.5f, %.5f — изменить".format(c.lat, c.lon)) }
                        Text("Радиус: ${c.radiusM} м", fontSize = 13.sp, color = ext.muted)
                        Slider(c.radiusM.toFloat(), { c = c.copy(radiusM = (it / 50).toInt() * 50) }, valueRange = 100f..1000f)
                    }
                    CondKind.WIFI -> {
                        OutlinedTextField(c.ssid, { c = c.copy(ssid = it) }, Modifier.fillMaxWidth(), label = { Text("Имя сети (SSID)") }, singleLine = true)
                        OutlinedButton({
                            if (!Conditions.hasLocation(ctx)) locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            else currentSsid(ctx)?.let { c = c.copy(ssid = it); msg = null } ?: run { msg = "Сейчас нет подключения к Wi-Fi или геолокация выключена в системе." }
                        }) { Text("Подставить текущую сеть") }
                    }
                    CondKind.BLUETOOTH -> {
                        Text(if (c.btAddress.isBlank()) "Устройство не выбрано" else "«${c.btName.ifBlank { c.btAddress }}» (${c.btAddress})", fontWeight = FontWeight.SemiBold)
                        OutlinedButton({
                            if (Build.VERSION.SDK_INT >= 31 && !Conditions.hasBtConnect(ctx)) btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            else { devices = bondedDevices(ctx); msg = if (devices.isNullOrEmpty()) "Нет сопряжённых устройств. Сначала подключите устройство в настройках Bluetooth." else null }
                        }) { Text("Выбрать из сопряжённых") }
                    }
                }
                msg?.let { Text(it, fontSize = 12.sp, color = ext.warn) }
                OutlinedTextField(
                    c.minIntervalMin.toString(), { c = c.copy(minIntervalMin = it.filter(Char::isDigit).toIntOrNull() ?: 0) }, Modifier.fillMaxWidth(),
                    label = { Text("Не чаще, чем раз в … мин") }, singleLine = true, enabled = !c.once, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Сработать только один раз", Modifier.weight(1f)); Switch(c.once, { c = c.copy(once = it) }) }
            }
        },
        confirmButton = {
            val ok = when (c.kind) { CondKind.PLACE -> !(c.lat == 0.0 && c.lon == 0.0); CondKind.WIFI -> c.ssid.isNotBlank(); CondKind.BLUETOOTH -> c.btAddress.isNotBlank() }
            TextButton({ onSave(c) }, enabled = ok) { Text("Готово") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
    if (map) MapPickerDialog(if (c.lat == 0.0 && c.lon == 0.0) null else GeoPoint(c.lat, c.lon), c.radiusM, { c = c.copy(lat = it.latitude, lon = it.longitude); map = false }, { map = false })
    devices?.let { list ->
        AlertDialog(
            onDismissRequest = { devices = null }, title = { Text("Bluetooth-устройство") },
            text = { Column { list.forEach { (n, addr) -> Text("$n\n$addr", Modifier.fillMaxWidth().clickable { c = c.copy(btName = n, btAddress = addr); devices = null }.padding(vertical = 10.dp)) } } },
            confirmButton = { TextButton({ devices = null }) { Text("Закрыть") } },
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
fun MapPickerDialog(initial: GeoPoint?, radiusM: Int, onPick: (GeoPoint) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var picked by remember { mutableStateOf(initial) }
    val start = remember {
        initial ?: runCatching {
            if (Conditions.hasLocation(ctx)) ctx.getSystemService(LocationManager::class.java).getProviders(true)
                .mapNotNull { ctx.getSystemService(LocationManager::class.java).getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { GeoPoint(it.latitude, it.longitude) } else null
        }.getOrNull() ?: GeoPoint(55.0302, 82.9204)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Text("Коснитесь карты, чтобы выбрать центр зоны", Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                var view by remember { mutableStateOf<MapView?>(null) }
                fun redraw(mv: MapView, p: GeoPoint?) {
                    mv.overlays.removeAll { it is Marker || it is Polygon }
                    if (p != null) {
                        mv.overlays.add(Polygon(mv).apply { points = Polygon.pointsAsCircle(p, radiusM.toDouble()); fillPaint.color = 0x330F6E66; outlinePaint.color = 0xFF0F6E66.toInt() })
                        mv.overlays.add(Marker(mv).apply { position = p; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
                    }
                    mv.invalidate()
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    AndroidView(
                        factory = { c ->
                            MapView(c).apply {
                                setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
                                controller.setZoom(15.0); controller.setCenter(start)
                                overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
                                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { picked = GeoPoint(p.latitude, p.longitude); redraw(this@apply, picked); return true }
                                    override fun longPressHelper(p: GeoPoint) = false
                                }))
                                redraw(this, picked); view = this
                            }
                        },
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                    )
                }
                DisposableEffect(Unit) { onDispose { view?.onDetach() } }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onDismiss) { Text("Отмена") }
                    Button({ picked?.let(onPick) }, Modifier.weight(1f), enabled = picked != null) { Text("Выбрать это место") }
                }
                Text("Карта © участники OpenStreetMap", Modifier.padding(start = 16.dp, bottom = 8.dp), fontSize = 11.sp, color = ext.muted)
            }
        }
    }
}
