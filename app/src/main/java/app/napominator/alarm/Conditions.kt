package app.napominator.alarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.napominator.App
import app.napominator.R
import app.napominator.data.CondKind
import app.napominator.data.Condition
import app.napominator.data.OccState
import app.napominator.data.Occurrence
import app.napominator.data.Reminder
import app.napominator.data.Status
import app.napominator.data.Trigger
import app.napominator.data.conds
import app.napominator.data.epochDayOf
import app.napominator.data.sched
import app.napominator.ui.MainActivity
import app.napominator.widget.Widgets
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Условные срабатывания: место (геозоны), Wi-Fi и Bluetooth. */
object Conditions {
    fun hasLocation(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackgroundLocation(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBtConnect(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun conditional(active: List<Reminder>) = active.filter { it.status == Status.ACTIVE && it.triggerMode != Trigger.TIME }

    fun sync(ctx: Context, active: List<Reminder>) {
        syncGeofences(ctx, active)
        WatcherService.sync(ctx, active)
    }

    private fun geofencePi(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, 7001, Intent(ctx, GeofenceReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    @SuppressLint("MissingPermission")
    private fun syncGeofences(ctx: Context, active: List<Reminder>) {
        val client = LocationServices.getGeofencingClient(ctx)
        val pi = geofencePi(ctx)
        val list = conditional(active).flatMap { r ->
            r.conds.withIndex().filter { it.value.kind == CondKind.PLACE }.map { (i, c) ->
                Geofence.Builder().setRequestId("${r.id}:$i")
                    .setCircularRegion(c.lat, c.lon, c.radiusM.toFloat().coerceAtLeast(100f))
                    .setTransitionTypes(if (c.enter) Geofence.GEOFENCE_TRANSITION_ENTER else Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE).build()
            }
        }
        client.removeGeofences(pi).addOnCompleteListener {
            if (list.isNotEmpty() && hasLocation(ctx) && hasBackgroundLocation(ctx)) {
                try {
                    client.addGeofences(GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(list).build(), pi)
                        .addOnSuccessListener { Log.i("Geo", "зарегистрировано геозон: ${list.size}") }
                        .addOnFailureListener { Log.w("Geo", "не удалось зарегистрировать геозоны", it) }
                } catch (_: SecurityException) { }
            } else if (list.isNotEmpty()) Log.w("Geo", "нет разрешений на геолокацию (в фоне), геозоны не зарегистрированы")
        }
    }

    /** Подходящее ли сейчас время для срабатывания (режим «условие + окно»). */
    fun inWindow(r: Reminder, now: LocalDateTime): Boolean {
        if (r.triggerMode != Trigger.BOTH) return true
        val s = r.sched
        if (now.dayOfWeek.value !in s.daysOfWeek) return false
        if (s.untilEpochDay != null && now.toLocalDate().toEpochDay() > s.untilEpochDay) return false
        val m = now.hour * 60 + now.minute
        return m in s.windowStart..s.windowEnd
    }

    /** Срабатывание условия [idx] напоминания [r], если позволяют окно, интервал и «один раз». */
    suspend fun fire(ctx: Context, r: Reminder, idx: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        val app = App.get(ctx)
        val c = r.conds.getOrNull(idx) ?: return false
        if (r.status != Status.ACTIVE || r.triggerMode == Trigger.TIME) return false
        if (!inWindow(r, LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), java.time.ZoneId.systemDefault()))) return false
        val last = app.prefs.lastFired(r.id)
        if (c.once && last > 0) return false
        if (last > 0 && nowMs - last < c.minIntervalMin * 60_000L) return false
        app.prefs.setLastFired(r.id, nowMs)
        val dao = app.repo.db.occurrences()
        dao.insertIgnore(Occurrence(reminderId = r.id, dueAt = nowMs, epochDay = epochDayOf(nowMs), state = OccState.PENDING))
        Log.i("Cond", "условие ${r.title}#$idx сработало")
        Notifier.show(ctx, r, nowMs, 0)
        if (r.repeatEveryMin > 0 && r.repeatMax > 0) Alarms.scheduleFollowUp(ctx, r.id, nowMs, nowMs + r.repeatEveryMin * 60_000L, 1)
        Widgets.refresh(ctx)
        return true
    }

    suspend fun onMatch(ctx: Context, match: (Condition) -> Boolean) {
        val repo = App.get(ctx).repo
        for (r in conditional(repo.db.reminders().active())) {
            val idx = r.conds.indexOfFirst(match)
            if (idx >= 0) fire(ctx, r, idx)
        }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        val ids = ev.triggeringGeofences?.map { it.requestId } ?: return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = App.get(ctx).repo
                for (id in ids) {
                    val (rid, idx) = id.split(':').let { it[0].toLong() to it[1].toInt() }
                    repo.get(rid)?.let { Conditions.fire(ctx, it, idx) }
                }
            } finally { result.finish() }
        }
    }
}

/** Foreground-служба: следит за подключением к Wi-Fi и Bluetooth-устройствам. */
class WatcherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentSsid: String? = null
    private var currentNetwork: Network? = null
    /** Первые секунды после запуска службы — только запоминаем текущую сеть, чтобы не срабатывать на уже существующее подключение. */
    private var baselineUntil = 0L
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var btReceiver: BroadcastReceiver? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Слежение за Wi-Fi и Bluetooth", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Напоминатор следит за Wi-Fi и Bluetooth").setContentText("Нужно для напоминаний «при подключении»")
            .setOngoing(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_MIN).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIF_ID, n)
        baselineUntil = System.currentTimeMillis() + 4_000
        registerWifi()
        registerBluetooth()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    private fun normalize(s: String?): String? {
        val t = s?.trim()?.removeSurrounding("\"") ?: return null
        return if (t.isEmpty() || t == "<unknown ssid>" || t == WifiManager.UNKNOWN_SSID) null else t
    }

    private fun onSsid(ssid: String?) {
        val now = normalize(ssid)
        val prev = currentSsid
        Log.d("Watcher", "ssid raw=$ssid now=$now prev=$prev")
        if (now == prev) return
        currentSsid = now
        if (System.currentTimeMillis() < baselineUntil) return
        if (prev != null) scope.launch { Conditions.onMatch(this@WatcherService) { it.kind == CondKind.WIFI && !it.enter && it.ssid.equals(prev, true) } }
        if (now != null) scope.launch { Conditions.onMatch(this@WatcherService) { it.kind == CondKind.WIFI && it.enter && it.ssid.equals(now, true) } }
    }

    @Suppress("DEPRECATION")
    private fun registerWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) { currentNetwork = n; onSsid((caps.transportInfo as? WifiInfo)?.ssid) }
            override fun onLost(n: Network) { if (n == currentNetwork) { currentNetwork = null; onSsid(null) } }
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                currentNetwork = n
                onSsid(applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid)
            }
            override fun onLost(n: Network) { if (n == currentNetwork) { currentNetwork = null; onSsid(null) } }
        }
        netCallback = cb
        try { cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb) } catch (_: SecurityException) { }
    }

    @Suppress("DEPRECATION")
    private fun registerBluetooth() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val dev = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val addr = dev?.address ?: return
                val connected = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
                scope.launch { Conditions.onMatch(this@WatcherService) { it.kind == CondKind.BLUETOOTH && it.enter == connected && it.btAddress.equals(addr, true) } }
            }
        }
        btReceiver = r
        val f = IntentFilter().apply { addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED) }
        ContextCompat.registerReceiver(this, r, f, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        btReceiver?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "watcher"
        /** Не пересекается с id напоминаний (они равны id в базе). */
        private const val NOTIF_ID = 2_000_000_100

        fun sync(ctx: Context, active: List<Reminder>) {
            val need = Conditions.conditional(active).any { r -> r.conds.any { it.kind == CondKind.WIFI || it.kind == CondKind.BLUETOOTH } }
            val i = Intent(ctx, WatcherService::class.java)
            try {
                if (need) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
            } catch (_: Exception) { }
        }
    }
}
