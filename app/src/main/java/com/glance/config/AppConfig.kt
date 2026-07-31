package com.glance.config

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Centralized configuration backed by SharedPreferences.
 * Glance is a generic dashboard kiosk — URLs can point to any web dashboard.
 */
class AppConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secretStore = SecretStore(prefs)

    // --- Dashboard URLs (any web URL — HA, Grafana, etc.) ---

    var dashboardUrls: List<String>
        get() {
            val raw = prefs.getString(KEY_DASHBOARD_URLS, DEFAULT_DASHBOARD_URL) ?: DEFAULT_DASHBOARD_URL
            return raw.split(SEPARATOR).filter { it.isNotBlank() }
        }
        set(value) {
            prefs.edit().putString(KEY_DASHBOARD_URLS, value.joinToString(SEPARATOR)).apply()
        }

    // --- Home Assistant MQTT integration ---

    var mqttEnabled: Boolean
        get() = prefs.getBoolean(KEY_MQTT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_MQTT_ENABLED, value).apply()

    var mqttBrokerHost: String
        get() = prefs.getString(KEY_MQTT_BROKER_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_BROKER_HOST, value).apply()

    var mqttBrokerPort: Int
        get() = prefs.getInt(KEY_MQTT_BROKER_PORT, 1883)
        set(value) = prefs.edit().putInt(KEY_MQTT_BROKER_PORT, value).apply()

    var mqttUsername: String
        get() = prefs.getString(KEY_MQTT_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_USERNAME, value).apply()

    var mqttPassword: String
        get() = secretStore.get(KEY_MQTT_PASSWORD_ENCRYPTED, KEY_MQTT_PASSWORD)
        set(value) = secretStore.put(KEY_MQTT_PASSWORD_ENCRYPTED, KEY_MQTT_PASSWORD, value)

    var mqttDeviceName: String
        get() = prefs.getString(KEY_MQTT_DEVICE_NAME, "Glance Tablet") ?: "Glance Tablet"
        set(value) = prefs.edit().putString(KEY_MQTT_DEVICE_NAME, value).apply()

    var mqttDiscoveryPrefix: String
        get() = prefs.getString(KEY_MQTT_DISCOVERY_PREFIX, "homeassistant") ?: "homeassistant"
        set(value) = prefs.edit().putString(KEY_MQTT_DISCOVERY_PREFIX, value).apply()

    val mqttDeviceId: String
        get() {
            val existing = prefs.getString(KEY_MQTT_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_MQTT_DEVICE_ID, generated).apply()
            return generated
        }

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

    var lastKnownBrightness: Int
        get() = prefs.getInt(KEY_LAST_KNOWN_BRIGHTNESS, minBrightness).coerceIn(0, 255)
        set(value) = prefs.edit().putInt(KEY_LAST_KNOWN_BRIGHTNESS, value.coerceIn(0, 255)).apply()

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
        private const val KEY_MQTT_ENABLED = "mqtt_enabled"
        private const val KEY_MQTT_BROKER_HOST = "mqtt_broker_host"
        private const val KEY_MQTT_BROKER_PORT = "mqtt_broker_port"
        private const val KEY_MQTT_USERNAME = "mqtt_username"
        private const val KEY_MQTT_PASSWORD = "mqtt_password"
        private const val KEY_MQTT_PASSWORD_ENCRYPTED = "mqtt_password_encrypted"
        private const val KEY_MQTT_DEVICE_NAME = "mqtt_device_name"
        private const val KEY_MQTT_DISCOVERY_PREFIX = "mqtt_discovery_prefix"
        private const val KEY_MQTT_DEVICE_ID = "mqtt_device_id"
        private const val KEY_MIN_BRIGHTNESS = "min_brightness"
        private const val KEY_MAX_BRIGHTNESS = "max_brightness"
        private const val KEY_AUTO_BRIGHTNESS = "auto_brightness"
        private const val KEY_LAST_KNOWN_BRIGHTNESS = "last_known_brightness"
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
