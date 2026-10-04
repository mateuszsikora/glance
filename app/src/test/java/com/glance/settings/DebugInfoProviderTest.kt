package com.glance.settings

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glance.BuildConfig
import com.glance.config.AppConfig
import com.glance.content.ContentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DebugInfoProviderTest {
    private lateinit var context: Context
    private lateinit var config: AppConfig

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("glance_config", Context.MODE_PRIVATE).edit().clear().commit()
        config = AppConfig(context)
    }

    @Test
    fun includesAllDiagnosticsWithExplicitMeaningsAndNoPrivateConfiguration() {
        config.dashboardUrls = listOf("https://dashboard.example.invalid/private", "https://second.example.invalid")
        config.dashboardAllowedOrigins = listOf("https://login.example.invalid")
        config.contentScheduleEnabled = true
        config.contentProfiles = listOf(ContentProfile("06:00", listOf("https://profile.example.invalid")))
        config.idleScreenEnabled = true
        config.idleScreenUrl = "https://idle.example.invalid"
        config.idleTimeoutMinutes = 7
        config.scheduleEnabled = true
        config.screenOnTime = "07:15"
        config.screenOffTime = "22:30"
        config.autoBrightnessEnabled = true
        config.mqttEnabled = true
        config.mqttBrokerHost = "broker.example.invalid"
        config.mqttUsername = "private-user"
        config.mqttDiscoveryPrefix = "private-discovery"
        config.mqttDeviceName = "private-device-name"
        config.setSettingsPin("583902")
        config.remoteConfigEnabled = true
        val prefs = context.getSharedPreferences("glance_config", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("mqtt_password", "private-password")
            .putString("mqtt_device_id", "private-device-id")
            .commit()
        val before = prefs.all.toMap()

        val snapshot = DebugInfoProvider(
            context, config,
            webViewVersion = { "123.4" }, deviceOwner = { true }, exactAlarms = { false },
            heapBytes = { 65L * 1024 * 1024 to 256L * 1024 * 1024 },
            elapsedRealtime = { (25 * 60 + 7) * 60_000L }
        ).snapshot()

        val expected = linkedMapOf(
            "Application package" to context.packageName,
            "Version" to "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            "Device Owner" to "Yes",
            "Android System WebView version" to "123.4",
            "App Java heap used" to "65 MiB",
            "App Java heap limit" to "256 MiB",
            "Device uptime (including sleep)" to "25h 7m",
            "Dashboard count" to "2",
            "Scheduled content" to "Enabled (profiles: 1)",
            "Idle screen" to "Enabled (timeout: 7 min)",
            "Allowed login-origin count" to "1",
            "Screen schedule" to "Enabled (07:15–22:30)",
            "Exact-alarm capability" to "Not allowed",
            "Automatic brightness" to "Enabled",
            "MQTT configuration" to "Enabled",
            "Remote configuration" to "Enabled"
        )
        assertEquals(expected, snapshot.fields.toMap().filterKeys { it in expected })
        assertEquals(before, prefs.all)
        listOf("example.invalid", "private-", "583902").forEach {
            assertFalse(snapshot.asText().contains(it))
        }
    }

    @Test
    fun samplesMemoryUptimeAndSavedConfigurationOnEveryRequest() {
        var used = 10L * 1024 * 1024
        var uptime = 60_000L
        val provider = DebugInfoProvider(context, config,
            heapBytes = { used to 256L * 1024 * 1024 }, elapsedRealtime = { uptime })
        val first = provider.snapshot()
        used *= 2
        uptime += 3_600_000
        config.mqttEnabled = true
        val second = provider.snapshot()
        assertEquals("10 MiB", first.fields.toMap()["App Java heap used"])
        assertEquals("20 MiB", second.fields.toMap()["App Java heap used"])
        assertEquals("0h 1m", first.fields.toMap()["Device uptime (including sleep)"])
        assertEquals("1h 1m", second.fields.toMap()["Device uptime (including sleep)"])
        assertEquals("Enabled", second.fields.toMap()["MQTT configuration"])
    }

    @Test
    fun missingOrFailingCapabilitiesRemainReadableWithoutExceptionDetails() {
        for (version in listOf<String?>(null, "", "   ")) {
            assertEquals("Unavailable", DebugInfoProvider(context, config,
                webViewVersion = { version }).snapshot().fields.toMap()["Android System WebView version"])
        }
        val snapshot = DebugInfoProvider(context, config,
            webViewVersion = { throw IllegalStateException("private-platform-details") },
            deviceOwner = { null }, exactAlarms = { throw SecurityException("private-denial") }
        ).snapshot()
        val fields = snapshot.fields.toMap()
        assertEquals("Unavailable", fields["Android System WebView version"])
        assertEquals("Unavailable", fields["Device Owner"])
        assertEquals("Unavailable", fields["Exact-alarm capability"])
        assertFalse(snapshot.asText().contains("private-"))
        assertTrue(fields.containsKey("App Java heap used"))
    }

    @Test
    fun disabledFeaturesStillReportProfileCountAndIdleTimeout() {
        config.contentScheduleEnabled = false
        config.contentProfiles = listOf(ContentProfile("06:00", listOf("https://example.invalid")))
        config.idleScreenEnabled = false
        config.idleTimeoutMinutes = 9
        config.scheduleEnabled = false
        config.mqttEnabled = false
        config.remoteConfigEnabled = false
        val fields = DebugInfoProvider(context, config).snapshot().fields.toMap()
        assertEquals("Disabled (profiles: 1)", fields["Scheduled content"])
        assertEquals("Disabled (timeout: 9 min)", fields["Idle screen"])
        assertEquals("Disabled", fields["Screen schedule"])
        assertEquals("Disabled", fields["MQTT configuration"])
        assertEquals("Disabled", fields["Remote configuration"])
    }

    @Test
    fun android8SupportsRegularAppAndDeviceOwnerWithoutExactAlarmPermission() {
        val provider = DebugInfoProvider(context, config)
        assertEquals("No (regular app)", provider.snapshot().fields.toMap()["Device Owner"])
        assertEquals("Allowed", provider.snapshot().fields.toMap()["Exact-alarm capability"])
        shadowOf(context.getSystemService(DevicePolicyManager::class.java))
            .setDeviceOwner(com.glance.AdminReceiver.getComponentName(context))
        assertEquals("Yes", provider.snapshot().fields.toMap()["Device Owner"])
    }

    @Test
    @Config(sdk = [31])
    fun android12ReportsExactAlarmPermission() {
        val provider = DebugInfoProvider(context, config)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals("Not allowed", provider.snapshot().fields.toMap()["Exact-alarm capability"])
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertEquals("Allowed", provider.snapshot().fields.toMap()["Exact-alarm capability"])
    }
}
