package com.glance.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized configuration backed by SharedPreferences.
 * Glance is a generic dashboard kiosk — URLs can point to any web dashboard.
 */
class AppConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Dashboard URLs (any web URL — HA, Grafana, etc.) ---

    var dashboardUrls: List<String>
        get() {
            val raw = prefs.getString(KEY_DASHBOARD_URLS, DEFAULT_DASHBOARD_URL) ?: DEFAULT_DASHBOARD_URL
            return raw.split(SEPARATOR).filter { it.isNotBlank() }
        }
        set(value) {
            prefs.edit().putString(KEY_DASHBOARD_URLS, value.joinToString(SEPARATOR)).apply()
        }

    // --- Optional HA integration (for screen control via HA) ---

    var haBaseUrl: String
        get() = prefs.getString(KEY_HA_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HA_URL, value).apply()

    var haAccessToken: String
        get() = prefs.getString(KEY_HA_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HA_TOKEN, value).apply()

    var haIntegrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_HA_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HA_ENABLED, value).apply()

    // --- Brightness ---

    var minBrightness: Int
        get() = prefs.getInt(KEY_MIN_BRIGHTNESS, 5)
        set(value) = prefs.edit().putInt(KEY_MIN_BRIGHTNESS, value).apply()

    var maxBrightness: Int
        get() = prefs.getInt(KEY_MAX_BRIGHTNESS, 255)
        set(value) = prefs.edit().putInt(KEY_MAX_BRIGHTNESS, value).apply()

    var autoBrightnessEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BRIGHTNESS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BRIGHTNESS, value).apply()

    // --- Schedule ---

    var screenOnTime: String
        get() = prefs.getString(KEY_SCREEN_ON_TIME, "06:00") ?: "06:00"
        set(value) = prefs.edit().putString(KEY_SCREEN_ON_TIME, value).apply()

    var screenOffTime: String
        get() = prefs.getString(KEY_SCREEN_OFF_TIME, "23:00") ?: "23:00"
        set(value) = prefs.edit().putString(KEY_SCREEN_OFF_TIME, value).apply()

    var scheduleEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, value).apply()

    // --- Watchdog ---

    var webviewReloadIntervalHours: Int
        get() = prefs.getInt(KEY_RELOAD_INTERVAL, 6)
        set(value) = prefs.edit().putInt(KEY_RELOAD_INTERVAL, value).apply()

    var healthCheckIntervalSeconds: Int
        get() = prefs.getInt(KEY_HEALTH_CHECK_INTERVAL, 30)
        set(value) = prefs.edit().putInt(KEY_HEALTH_CHECK_INTERVAL, value).apply()

    // --- Settings PIN ---

    var settingsPin: String
        get() = prefs.getString(KEY_SETTINGS_PIN, "1234") ?: "1234"
        set(value) = prefs.edit().putString(KEY_SETTINGS_PIN, value).apply()

    // --- Auto-rotate views ---

    var autoRotateEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ROTATE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ROTATE, value).apply()

    var autoRotateIntervalSeconds: Int
        get() = prefs.getInt(KEY_AUTO_ROTATE_INTERVAL, 30)
        set(value) = prefs.edit().putInt(KEY_AUTO_ROTATE_INTERVAL, value).apply()

    companion object {
        private const val PREFS_NAME = "glance_config"
        private const val SEPARATOR = "|"

        private const val KEY_DASHBOARD_URLS = "dashboard_urls"
        private const val KEY_HA_URL = "ha_url"
        private const val KEY_HA_TOKEN = "ha_token"
        private const val KEY_HA_ENABLED = "ha_enabled"
        private const val KEY_MIN_BRIGHTNESS = "min_brightness"
        private const val KEY_MAX_BRIGHTNESS = "max_brightness"
        private const val KEY_AUTO_BRIGHTNESS = "auto_brightness"
        private const val KEY_SCREEN_ON_TIME = "screen_on_time"
        private const val KEY_SCREEN_OFF_TIME = "screen_off_time"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_RELOAD_INTERVAL = "reload_interval_hours"
        private const val KEY_HEALTH_CHECK_INTERVAL = "health_check_interval_seconds"
        private const val KEY_SETTINGS_PIN = "settings_pin"
        private const val KEY_AUTO_ROTATE = "auto_rotate"
        private const val KEY_AUTO_ROTATE_INTERVAL = "auto_rotate_interval"

        private const val DEFAULT_DASHBOARD_URL = "https://example.com"
    }
}
