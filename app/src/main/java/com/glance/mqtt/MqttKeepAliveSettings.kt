package com.glance.mqtt

import android.content.Context
import android.os.Build

/**
 * Whether [AlarmPingSender] sets the MQTT keep-alive as an alarm clock while the display is off.
 *
 * On battery with the display off, EMUI's app hibernation freezes Glance about eight seconds after
 * the screen goes dark, although Glance is the app on screen. It holds back Glance's broadcasts,
 * ordinary exact alarms included, and releases its wake locks, so the keep-alive stops until the
 * display wakes or a charger is connected; the broker drops the session meanwhile. Alarm clocks
 * still wake Glance on time. On a 3 GB Huawei tablet with Android 8 this kept the session up
 * through a whole night on battery, with one ping every 15 minutes, all on time.
 *
 * An alarm clock is not what the API is for. Android reports it as the next alarm, and it keeps
 * the device out of deep Doze while the display is off: Doze does not go idle while an alarm that
 * wakes the device from idle is less than an hour away, so every app runs without its limits. Other
 * vendors deliver the ordinary alarm through Doze, so the switch defaults on only for Huawei and
 * Honor devices.
 *
 * Deliberately kept out of [com.glance.config.AppConfig], like
 * [com.glance.diagnostics.DiagnosticSettings]: recovery builds require the configuration readers
 * and writers to stay byte-identical to the release being restored.
 */
class MqttKeepAliveSettings(
    context: Context,
    private val manufacturer: String = Build.MANUFACTURER.orEmpty()
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var alarmClockWhileDisplayOff: Boolean
        get() = prefs.getBoolean(KEY_ALARM_CLOCK, defaultFor(manufacturer))
        set(value) = prefs.edit().putBoolean(KEY_ALARM_CLOCK, value).apply()

    companion object {
        private const val PREFS_NAME = "glance_mqtt_keep_alive"
        private const val KEY_ALARM_CLOCK = "alarm_clock_while_display_off"

        /** Vendors whose app hibernation is known to hold the ordinary keep-alive alarm back. */
        private val HIBERNATING_VENDORS = setOf("huawei", "honor")

        fun defaultFor(manufacturer: String): Boolean =
            manufacturer.trim().lowercase() in HIBERNATING_VENDORS
    }
}
