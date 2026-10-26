package app.napominator

import android.net.wifi.WifiManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.napominator.TestUtil.activeNotification
import app.napominator.TestUtil.ctx
import app.napominator.TestUtil.reminder
import app.napominator.TestUtil.repo
import app.napominator.TestUtil.shell
import app.napominator.TestUtil.waitFor
import app.napominator.data.CondKind
import app.napominator.data.Condition
import app.napominator.data.Schedule
import app.napominator.data.Trigger
import app.napominator.data.toJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Реальная служба слежения: подключение и отключение Wi-Fi через svc wifi. */
@RunWith(AndroidJUnit4::class)
class WifiTest {
    @Before fun setUp() {
        TestUtil.reset()
        shell("pm grant ${ctx.packageName} android.permission.ACCESS_FINE_LOCATION")
        shell("pm grant ${ctx.packageName} android.permission.ACCESS_BACKGROUND_LOCATION")
        shell("svc wifi enable"); Thread.sleep(4000)
    }
    @After fun tearDown() { shell("svc wifi enable") }

    @Suppress("DEPRECATION")
    private fun ssid() = ctx.getSystemService(WifiManager::class.java).connectionInfo?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        ?: if (android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk", true) || android.os.Build.MODEL.contains("Emulator", true) || android.os.Build.MODEL.contains("Phone")) "AndroidWifi" else null

    private fun wifiReminder(title: String, ssid: String, enter: Boolean) = runBlocking {
        val c = Condition(CondKind.WIFI, enter = enter, ssid = ssid, minIntervalMin = 0)
        repo.save(reminder(title, Schedule(times = emptyList())) { it.copy(triggerMode = Trigger.CONDITION, conditions = listOf(c).toJson()) })
    }

    @Test fun connectAndDisconnectFireTheRightReminders() {
        val name = ssid()
        assumeTrue("нет подключения к Wi-Fi на устройстве", name != null)
        val onConnect = wifiReminder("Подключился", name!!, true)
        val onDisconnect = wifiReminder("Отключился", name, false)
        val other = wifiReminder("Чужая сеть", "Нет-такой-сети", true)
        Thread.sleep(8000) // служба стартовала и запомнила текущую сеть

        assertNull("на уже существующее подключение не срабатываем", activeNotification(onConnect))
        shell("svc wifi disable")
        waitFor(60_000, "срабатывание при отключении") { activeNotification(onDisconnect) != null }
        assertNull(activeNotification(onConnect))
        shell("svc wifi enable")
        waitFor(90_000, "срабатывание при подключении") { activeNotification(onConnect) != null }
        assertNull("другая сеть не срабатывает", activeNotification(other))
        assertEquals(2, runBlocking { repo.db.occurrences().all() }.size)
    }
}
