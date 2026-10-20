package com.glance.maintenance

import android.content.Context

/**
 * Settings of the nightly maintenance restart.
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
     * On by default: without it the WebView's native memory grows for as long as the process
     * lives, and an idle page that stays on screen is never refreshed.
     */
    var nightlyRestartEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Local "HH:mm" time used when no screen OFF window is configured. */
    var quietTime: String
        get() = prefs.getString(KEY_QUIET_TIME, DEFAULT_QUIET_TIME) ?: DEFAULT_QUIET_TIME
        set(value) = prefs.edit().putString(KEY_QUIET_TIME, value).apply()

    companion object {
        const val DEFAULT_QUIET_TIME = "03:30"
        private const val PREFS_NAME = "glance_maintenance"
        private const val KEY_ENABLED = "nightly_restart_enabled"
        private const val KEY_QUIET_TIME = "nightly_restart_quiet_time"
    }
}
