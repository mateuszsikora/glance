package com.glance.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

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
            return raw.split(SEPARATOR)
                .map(String::trim)
                .filter(String::isNotBlank)
                .ifEmpty { listOf(DEFAULT_DASHBOARD_URL) }
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
        get() = prefs.getInt(KEY_MQTT_BROKER_PORT, DEFAULT_MQTT_PORT)
            .takeIf { it in 1..65535 }
            ?: DEFAULT_MQTT_PORT
        set(value) = prefs.edit().putInt(KEY_MQTT_BROKER_PORT, value.coerceIn(1, 65535)).apply()

    var mqttUsername: String
        get() = prefs.getString(KEY_MQTT_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_USERNAME, value).apply()

    var mqttPassword: String
        get() = readMqttPassword().value
        set(value) = secretStore.put(KEY_MQTT_PASSWORD_ENCRYPTED, KEY_MQTT_PASSWORD, value)

    internal fun readMqttPassword(): SecretValue {
        return secretStore.get(KEY_MQTT_PASSWORD_ENCRYPTED, KEY_MQTT_PASSWORD)
    }

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

    fun queueDiscoveryCleanup(serverUri: String, topic: String) {
        val entries = prefs.getStringSet(KEY_MQTT_DISCOVERY_CLEANUP, emptySet())
            .orEmpty()
            .toMutableSet()
        entries += "$serverUri$DISCOVERY_CLEANUP_SEPARATOR$topic"
        prefs.edit().putStringSet(KEY_MQTT_DISCOVERY_CLEANUP, entries).apply()
    }

    fun pendingDiscoveryCleanupTopics(serverUri: String): Set<String> {
        val prefix = "$serverUri$DISCOVERY_CLEANUP_SEPARATOR"
        return prefs.getStringSet(KEY_MQTT_DISCOVERY_CLEANUP, emptySet())
            .orEmpty()
            .filterTo(mutableSetOf()) { it.startsWith(prefix) }
            .mapTo(mutableSetOf()) { it.removePrefix(prefix) }
    }

    fun markDiscoveryCleanupComplete(serverUri: String, topic: String) {
        val target = "$serverUri$DISCOVERY_CLEANUP_SEPARATOR$topic"
        val entries = prefs.getStringSet(KEY_MQTT_DISCOVERY_CLEANUP, emptySet())
            .orEmpty()
            .toMutableSet()
        if (entries.remove(target)) {
            prefs.edit().putStringSet(KEY_MQTT_DISCOVERY_CLEANUP, entries).apply()
        }
    }

    // --- Brightness ---

    var minBrightness: Int
        get() = prefs.getInt(KEY_MIN_BRIGHTNESS, 5).coerceIn(0, 255)
        set(value) = prefs.edit().putInt(KEY_MIN_BRIGHTNESS, value.coerceIn(0, 255)).apply()

    var maxBrightness: Int
        get() = prefs.getInt(KEY_MAX_BRIGHTNESS, 255).coerceIn(0, 255)
        set(value) = prefs.edit().putInt(KEY_MAX_BRIGHTNESS, value.coerceIn(0, 255)).apply()

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
        get() = prefs.getInt(KEY_RELOAD_INTERVAL, 6).coerceIn(1, 168)
        set(value) = prefs.edit().putInt(KEY_RELOAD_INTERVAL, value.coerceIn(1, 168)).apply()

    var healthCheckIntervalSeconds: Int
        get() = prefs.getInt(KEY_HEALTH_CHECK_INTERVAL, 30).coerceIn(10, 3600)
        set(value) = prefs.edit().putInt(KEY_HEALTH_CHECK_INTERVAL, value.coerceIn(10, 3600)).apply()

    // --- Kiosk lifecycle ---

    var isKioskSuspended: Boolean
        get() = prefs.getBoolean(KEY_KIOSK_SUSPENDED, false)
        set(value) {
            check(prefs.edit().putBoolean(KEY_KIOSK_SUSPENDED, value).commit()) {
                "Unable to persist kiosk lifecycle state"
            }
        }

    // --- Settings PIN ---

    val hasSettingsPin: Boolean
        get() = prefs.contains(KEY_SETTINGS_PIN_HASH) || prefs.contains(KEY_SETTINGS_PIN)

    val needsLegacyPinUpgrade: Boolean
        get() = !prefs.contains(KEY_SETTINGS_PIN_HASH) && prefs.contains(KEY_SETTINGS_PIN)

    fun verifySettingsPin(candidate: String): Boolean {
        val encodedHash = prefs.getString(KEY_SETTINGS_PIN_HASH, null)
        val encodedSalt = prefs.getString(KEY_SETTINGS_PIN_SALT, null)
        if (!encodedHash.isNullOrBlank() && !encodedSalt.isNullOrBlank()) {
            return runCatching {
                val salt = Base64.decode(encodedSalt, Base64.NO_WRAP)
                val expected = Base64.decode(encodedHash, Base64.NO_WRAP)
                MessageDigest.isEqual(expected, derivePinHash(candidate, salt))
            }.getOrDefault(false)
        }

        val legacy = prefs.getString(KEY_SETTINGS_PIN, DEFAULT_SETTINGS_PIN)
            ?: DEFAULT_SETTINGS_PIN
        return MessageDigest.isEqual(
            legacy.toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8)
        )
    }

    fun setSettingsPin(pin: String) {
        require(pin.length in 4..12 && pin.all(Char::isDigit)) { "PIN must contain 4-12 digits" }
        require(pin != DEFAULT_SETTINGS_PIN) { "Choose a PIN other than the legacy default" }
        val salt = ByteArray(PIN_SALT_BYTES).also(SecureRandom()::nextBytes)
        val hash = derivePinHash(pin, salt)
        prefs.edit()
            .putString(KEY_SETTINGS_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_SETTINGS_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .remove(KEY_SETTINGS_PIN)
            .apply()
    }

    fun pinLockRemainingMs(nowEpochMs: Long = System.currentTimeMillis()): Long {
        return (prefs.getLong(KEY_PIN_LOCK_UNTIL, 0L) - nowEpochMs).coerceAtLeast(0L)
    }

    fun recordFailedPinAttempt(nowEpochMs: Long = System.currentTimeMillis()): Long {
        val failures = prefs.getInt(KEY_PIN_FAILED_ATTEMPTS, 0) + 1
        if (failures < MAX_PIN_ATTEMPTS) {
            prefs.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, failures).apply()
            return 0L
        }

        val lockUntil = nowEpochMs + PIN_LOCK_DURATION_MS
        prefs.edit()
            .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
            .putLong(KEY_PIN_LOCK_UNTIL, lockUntil)
            .apply()
        return PIN_LOCK_DURATION_MS
    }

    fun clearPinFailures() {
        prefs.edit()
            .remove(KEY_PIN_FAILED_ATTEMPTS)
            .remove(KEY_PIN_LOCK_UNTIL)
            .apply()
    }

    private fun derivePinHash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PIN_HASH_ITERATIONS, PIN_HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    // --- Auto-rotate views ---

    var autoRotateEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ROTATE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ROTATE, value).apply()

    var autoRotateIntervalSeconds: Int
        get() = prefs.getInt(KEY_AUTO_ROTATE_INTERVAL, 30).coerceIn(5, 86_400)
        set(value) = prefs.edit()
            .putInt(KEY_AUTO_ROTATE_INTERVAL, value.coerceIn(5, 86_400))
            .apply()

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
        private const val KEY_MQTT_DISCOVERY_CLEANUP = "mqtt_discovery_cleanup"
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
        private const val KEY_SETTINGS_PIN_HASH = "settings_pin_hash"
        private const val KEY_SETTINGS_PIN_SALT = "settings_pin_salt"
        private const val KEY_PIN_FAILED_ATTEMPTS = "pin_failed_attempts"
        private const val KEY_PIN_LOCK_UNTIL = "pin_lock_until"
        private const val KEY_KIOSK_SUSPENDED = "kiosk_suspended"
        private const val KEY_AUTO_ROTATE = "auto_rotate"
        private const val KEY_AUTO_ROTATE_INTERVAL = "auto_rotate_interval"

        private const val DEFAULT_DASHBOARD_URL = "https://example.com"
        private const val DEFAULT_MQTT_PORT = 1883
        private const val DISCOVERY_CLEANUP_SEPARATOR = "\t"
        private const val DEFAULT_SETTINGS_PIN = "1234"
        private const val PIN_SALT_BYTES = 16
        private const val PIN_HASH_ITERATIONS = 120_000
        private const val PIN_HASH_BITS = 256
        private const val MAX_PIN_ATTEMPTS = 5
        private const val PIN_LOCK_DURATION_MS = 30_000L
    }
}
