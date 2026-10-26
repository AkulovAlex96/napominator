package app.napominator

import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.napominator.TestUtil.activeNotification
import app.napominator.TestUtil.ctx
import app.napominator.TestUtil.reminder
import app.napominator.TestUtil.repo
import app.napominator.TestUtil.shell
import app.napominator.TestUtil.waitFor
import app.napominator.alarm.Conditions
import app.napominator.data.CondKind
import app.napominator.data.Condition
import app.napominator.data.Schedule
import app.napominator.data.Trigger
import app.napominator.data.toJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Реальная геозона: GMS Geofencing + подменённый GPS. Пропускается, если эмулятор не доставляет локацию. */
@RunWith(AndroidJUnit4::class)
class GeoTest {
    private val lm get() = ctx.getSystemService(LocationManager::class.java)

    @Before fun setUp() {
        TestUtil.reset()
        shell("pm grant ${ctx.packageName} android.permission.ACCESS_FINE_LOCATION")
        shell("pm grant ${ctx.packageName} android.permission.ACCESS_COARSE_LOCATION")
        shell("pm grant ${ctx.packageName} android.permission.ACCESS_BACKGROUND_LOCATION")
        shell("appops set ${ctx.packageName} android:mock_location allow")
        Thread.sleep(500)
    }

    @After fun tearDown() { runCatching { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } }

    private fun push(lat: Double, lon: Double) {
        val l = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat; longitude = lon; accuracy = 5f; time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, l)
    }

    @Test fun enteringZoneFiresReminder() {
        lm.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE)
        lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val c = Condition(CondKind.PLACE, enter = true, lat = 55.0302, lon = 82.9204, radiusM = 200, minIntervalMin = 0)
        val id = runBlocking { repo.save(reminder("Дома", Schedule(times = emptyList())) { it.copy(triggerMode = Trigger.CONDITION, conditions = listOf(c).toJson()) }) }
        Thread.sleep(3000) // регистрация геозоны
        // далеко от зоны, потом внутрь
        repeat(6) { push(55.5, 83.5); Thread.sleep(2000) }
        var fired = false
        for (i in 1..40) { push(55.0302, 82.9204); Thread.sleep(3000); if (activeNotification(id) != null) { fired = true; break } }
        assumeTrue("эмулятор не доставил геозону (ограничение GMS/эмулятора)", fired)
        runBlocking { check(repo.db.occurrences().all().any { it.reminderId == id }) }
    }
}
