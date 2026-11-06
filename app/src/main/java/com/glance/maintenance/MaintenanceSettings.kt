package com.glance.maintenance

import android.content.Context

/**
 * Settings of the scheduled restart.
 *
 * Deliberately kept out of [com.glance.config.AppConfig] for the same reason as
 * [com.glance.diagnostics.DiagnosticSettings]: recovery builds require the configuration readers
 * and writers to stay byte-identical to the release being restored. Older builds never read this
 * file, and losing it on a downgrade restores the defaults.
 */
class MaintenanceSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Off by default: most devices do not need it, and Glance does not restart itself on its own
     * judgement. The Memory section suggests turning it on when the native heap keeps growing.
     */
    var nightlyRestartEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Restart at most once in this many days; one of [INTERVAL_DAYS]. */
    var restartIntervalDays: Int
        get() = prefs.getInt(KEY_INTERVAL_DAYS, 1).takeIf { it in INTERVAL_DAYS } ?: 1
        set(value) = prefs.edit().putInt(KEY_INTERVAL_DAYS, value).apply()

    /**
     * Off by default, like the restart. Reboots the whole tablet instead of restarting Glance on the
     * nights it is due, which also releases the kernel memory a restart cannot reach (see
     * [com.glance.diagnostics.KernelMemoryTrend]). Needs Device Owner.
     */
    var tabletRebootEnabled: Boolean
        get() = prefs.getBoolean(KEY_REBOOT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_REBOOT_ENABLED, value).apply()

    /** Reboot at most once in this many days; one of [REBOOT_INTERVAL_DAYS]. */
    var rebootIntervalDays: Int
        get() = prefs.getInt(KEY_REBOOT_INTERVAL_DAYS, DEFAULT_REBOOT_INTERVAL_DAYS)
            .takeIf { it in REBOOT_INTERVAL_DAYS } ?: DEFAULT_REBOOT_INTERVAL_DAYS
        set(value) = prefs.edit().putInt(KEY_REBOOT_INTERVAL_DAYS, value).apply()

    /** Whether the nightly maintenance alarm has anything to do. */
    val anyScheduled: Boolean get() = nightlyRestartEnabled || tabletRebootEnabled

    /** Local "HH:mm" time used when no screen OFF window is configured. */
    var quietTime: String
        get() = prefs.getString(KEY_QUIET_TIME, DEFAULT_QUIET_TIME) ?: DEFAULT_QUIET_TIME
        set(value) = prefs.edit().putString(KEY_QUIET_TIME, value).apply()

    companion object {
        const val DEFAULT_QUIET_TIME = "03:30"

        /** The choices offered: every day, every 2 or 3 days, or once a week. */
        val INTERVAL_DAYS = listOf(1, 2, 3, 7)

        /**
         * The kernel leak builds up over months at a few megabytes a day, so reboots are rarer:
         * once a week, every two weeks, or once a month.
         */
        val REBOOT_INTERVAL_DAYS = listOf(7, 14, 30)
        const val DEFAULT_REBOOT_INTERVAL_DAYS = 7

        private const val PREFS_NAME = "glance_maintenance"
        private const val KEY_ENABLED = "nightly_restart_enabled"
        private const val KEY_INTERVAL_DAYS = "restart_interval_days"
        private const val KEY_REBOOT_ENABLED = "tablet_reboot_enabled"
        private const val KEY_REBOOT_INTERVAL_DAYS = "reboot_interval_days"
        private const val KEY_QUIET_TIME = "nightly_restart_quiet_time"
    }
}
