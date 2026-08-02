package com.glance.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glance.content.ContentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AppConfigTest {
    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun legacyPinAlwaysRequiresReplacementWithoutCrashing() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("settings_pin", "111")
            .commit()
        val config = AppConfig(context)

        assertTrue(config.verifySettingsPin("111"))
        assertTrue(config.needsLegacyPinUpgrade)

        config.setSettingsPin("583902")
        assertFalse(config.needsLegacyPinUpgrade)
        assertTrue(config.verifySettingsPin("583902"))
    }

    @Test
    fun normalizesValuesWrittenByOlderVersions() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("dashboard_urls", "")
            .putInt("auto_rotate_interval", 0)
            .putInt("reload_interval_hours", -4)
            .putInt("health_check_interval_seconds", 0)
            .putInt("mqtt_broker_port", 90_000)
            .putInt("idle_timeout_minutes", 0)
            .commit()
        val config = AppConfig(context)

        assertEquals(listOf("https://example.com"), config.dashboardUrls)
        assertEquals(5, config.autoRotateIntervalSeconds)
        assertEquals(1, config.webviewReloadIntervalHours)
        assertEquals(10, config.healthCheckIntervalSeconds)
        assertEquals(1883, config.mqttBrokerPort)
        assertEquals(1, config.idleTimeoutMinutes)
    }

    @Test
    fun kioskSuspensionIsPersistedSynchronously() {
        AppConfig(context).isKioskSuspended = true

        assertTrue(AppConfig(context).isKioskSuspended)
    }

    @Test
    fun requestedScreenStateSurvivesControllerRecreation() {
        val config = AppConfig(context)
        assertTrue(config.requestedScreenOn)

        config.requestedScreenOn = false

        assertFalse(AppConfig(context).requestedScreenOn)
    }

    @Test
    fun storesAdditionalDashboardOrigins() {
        val origins = listOf(
            "https://login.example.test",
            "https://identity.example.test:8443/oauth"
        )

        AppConfig(context).dashboardAllowedOrigins = origins

        assertEquals(origins, AppConfig(context).dashboardAllowedOrigins)
    }

    @Test
    fun storesContentProfilesAndIdleScreenSettings() {
        val profiles = listOf(
            ContentProfile(
                "06:00",
                listOf("https://morning.example.test", "https://weather.example.test")
            ),
            ContentProfile("18:00", listOf("https://evening.example.test"))
        )
        AppConfig(context).apply {
            contentScheduleEnabled = true
            contentProfiles = profiles
            idleScreenEnabled = true
            idleScreenUrl = "https://photos.example.test"
            idleTimeoutMinutes = 15
        }

        val restored = AppConfig(context)
        assertTrue(restored.contentScheduleEnabled)
        assertEquals(profiles, restored.contentProfiles)
        assertTrue(restored.idleScreenEnabled)
        assertEquals("https://photos.example.test", restored.idleScreenUrl)
        assertEquals(15, restored.idleTimeoutMinutes)
    }

    companion object {
        private const val PREFS_NAME = "glance_config"
    }
}
