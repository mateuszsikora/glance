package com.glance.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
            .commit()
        val config = AppConfig(context)

        assertEquals(listOf("https://example.com"), config.dashboardUrls)
        assertEquals(5, config.autoRotateIntervalSeconds)
        assertEquals(1, config.webviewReloadIntervalHours)
        assertEquals(10, config.healthCheckIntervalSeconds)
        assertEquals(1883, config.mqttBrokerPort)
    }

    @Test
    fun kioskSuspensionIsPersistedSynchronously() {
        AppConfig(context).isKioskSuspended = true

        assertTrue(AppConfig(context).isKioskSuspended)
    }

    companion object {
        private const val PREFS_NAME = "glance_config"
    }
}
