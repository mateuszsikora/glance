package com.glance.settings

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DeviceDebugInfoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun reportsAndroid8HardwareWithoutReadingDeviceIdentifiers() {
        ShadowBuild.setManufacturer("Example manufacturer")
        ShadowBuild.setModel("Example tablet")
        ShadowBuild.setVersionRelease("8.0.0")
        ShadowBuild.setVersionSecurityPatch("2020-01-05")
        ShadowBuild.setHardware("example-chip")
        ShadowBuild.setSupportedAbis(arrayOf("arm64-v8a", "armeabi-v7a"))
        ShadowBuild.setSerial("private-serial")
        ShadowBuild.setFingerprint("private-fingerprint")
        ShadowDisplayManager.changeDisplay(0, "w1200dp-h800dp-land-mdpi")

        val fields = DeviceDebugInfo(context).fields().toMap()

        assertEquals("Example manufacturer", fields["Manufacturer"])
        assertEquals("Example tablet", fields["Tablet model"])
        assertEquals("8.0.0 (API 26)", fields["Android version"])
        assertEquals("2020-01-05", fields["Android security patch"])
        assertEquals("example-chip", fields["Hardware platform"])
        assertEquals("arm64-v8a, armeabi-v7a", fields["Supported CPU ABIs"])
        assertEquals("Unavailable", fields["System-on-chip"])
        assertEquals("1200 × 800 px", fields["Display resolution (active mode)"])
        assertEquals("60.0 Hz", fields["Display refresh rate"])
        assertEquals("160 dpi", fields["Display density (logical)"])
        assertTrue(fields.getValue("CPU cores available to app").toInt() > 0)
        assertFalse(fields.values.any { it.contains("private-") })
    }

    @Test
    @Config(sdk = [31])
    fun reportsChipDetailsOnAndroid12() {
        ShadowBuild.setSystemOnChipManufacturer("Example chip maker")
        ShadowBuild.setSystemOnChipModel("Example SoC")
        assertEquals("Example chip maker Example SoC", DeviceDebugInfo(context).fields().toMap()["System-on-chip"])
    }

    @Test
    fun samplesMemoryBatteryAndPowerStateAgainWithoutRegisteringAListener() {
        val manager = shadowOf(context.getSystemService(ActivityManager::class.java))
        val memory = ActivityManager.MemoryInfo().apply {
            totalMem = 2048L * 1024 * 1024
            availMem = 600L * 1024 * 1024
            lowMemory = false
        }
        manager.setMemoryInfo(memory)
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIsPowerSaveMode(true)
        power.setIgnoringBatteryOptimizations(context.packageName, true)
        var receiverReads = 0
        val battery = Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 45)
            .putExtra(BatteryManager.EXTRA_SCALE, 50)
            .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_NOT_CHARGING)
            .putExtra(BatteryManager.EXTRA_TEMPERATURE, 325)
        val batteryContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent {
                assertEquals(null, receiver)
                assertTrue(filter.hasAction(Intent.ACTION_BATTERY_CHANGED))
                receiverReads++
                return battery
            }
        }
        val provider = DeviceDebugInfo(batteryContext)
        val first = provider.fields().toMap()
        assertEquals("2048 MiB", first["Device RAM total"])
        assertEquals("600 MiB", first["Device RAM available"])
        assertEquals("No", first["System low-memory pressure"])
        assertEquals("90%", first["Battery level"])
        assertEquals("Not charging", first["Battery status"])
        assertEquals("32.5 °C", first["Battery temperature"])
        assertEquals("Yes", first["Power saving mode"])
        assertEquals("Yes", first["Battery optimization exemption"])

        memory.availMem = 100L * 1024 * 1024
        memory.lowMemory = true
        manager.setMemoryInfo(memory)
        battery.putExtra(BatteryManager.EXTRA_LEVEL, 50)
        battery.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL)
        battery.putExtra(BatteryManager.EXTRA_TEMPERATURE, 330)
        power.setIsPowerSaveMode(false)
        val second = provider.fields().toMap()
        assertEquals("100 MiB", second["Device RAM available"])
        assertEquals("Yes", second["System low-memory pressure"])
        assertEquals("100%", second["Battery level"])
        assertEquals("Full", second["Battery status"])
        assertEquals("33.0 °C", second["Battery temperature"])
        assertEquals("No", second["Power saving mode"])
        assertEquals(2, receiverReads)
    }

    @Test
    fun missingServicesAndBatteryReadingsDoNotHideOtherDiagnostics() {
        ShadowBuild.setModel("")
        ShadowBuild.setVersionSecurityPatch("unknown")
        val unavailableContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = null
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
                throw SecurityException("private-platform-details")
            }
        }
        val fields = DeviceDebugInfo(unavailableContext).fields().toMap()
        listOf("Tablet model", "Android security patch", "Display resolution (active mode)",
            "Device RAM total", "Battery level", "Battery status", "Battery temperature",
            "Power saving mode", "Ambient light sensor").forEach {
            assertEquals(it, "Unavailable", fields[it])
        }
        assertEquals("8.0.0 (API 26)", fields["Android version"])
        assertFalse(fields.values.any { it.contains("private-") })
    }

    @Test
    fun emptyBatteryExtrasDoNotInventZeroPercentOrZeroTemperature() {
        val emptyBatteryContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent =
                Intent(Intent.ACTION_BATTERY_CHANGED)
        }
        val fields = DeviceDebugInfo(emptyBatteryContext).fields().toMap()
        assertEquals("Unavailable", fields["Battery level"])
        assertEquals("Unavailable", fields["Battery status"])
        assertEquals("Unavailable", fields["Battery temperature"])
    }
}
