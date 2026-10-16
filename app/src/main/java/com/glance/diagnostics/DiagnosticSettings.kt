package com.glance.diagnostics

import android.content.Context

/**
 * Switch for the diagnostic journal.
 *
 * Deliberately kept out of [com.glance.config.AppConfig]: recovery builds require the
 * configuration readers and writers to stay byte-identical to the release being restored (see
 * docs/self-hosted-updates.md). This separate file is never read by older builds, so it adds no
 * compatibility constraint, and losing it on a downgrade merely turns the journal off.
 */
class DiagnosticSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Off by default: the journal exists to investigate a problem, not to run continuously. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    private companion object {
        const val PREFS_NAME = "glance_diagnostics"
        const val KEY_ENABLED = "journal_enabled"
    }
}
