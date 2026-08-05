package com.glance.update

import com.glance.BuildConfig
import com.glance.config.AppConfig
import java.util.concurrent.TimeUnit

/**
 * The update facts both settings surfaces show, derived once so the tablet and the remote panel
 * cannot describe the same state differently.
 *
 * Values only: each surface supplies its own labels, because one reads them from `strings.xml` and
 * the other writes them into HTML.
 */
data class UpdateSummary(
    /** Whether an update URL is configured at all. Everything else is meaningless without one. */
    val enabled: Boolean,
    /** The running build, e.g. `1.5 (build 5)`. */
    val installedVersion: String,
    /** Reachability of the update server as of the last check, or null when no URL is set. */
    val serverState: String?,
    /** Free-text outcome of the last check, or null when nothing has been recorded yet. */
    val lastOutcome: String?,
    /**
     * A newer build the server is offering that is not installed yet, or null. Non-null only while
     * automatic installation is off: with it on, an offered build installs itself instead of
     * waiting, so anything left here would be a version that failed rather than one to advertise.
     */
    val pendingVersion: String?
) {
    companion object {
        fun of(config: AppConfig, now: Long = System.currentTimeMillis()): UpdateSummary = of(
            updateUrl = config.updateUrl,
            autoUpdateEnabled = config.autoUpdateEnabled,
            lastOutcome = config.updateStatus,
            state = config.updateCheck,
            installedVersionName = BuildConfig.VERSION_NAME,
            installedVersionCode = BuildConfig.VERSION_CODE,
            now = now
        )

        fun of(
            updateUrl: String,
            autoUpdateEnabled: Boolean,
            lastOutcome: String,
            state: UpdateCheckState,
            installedVersionName: String,
            installedVersionCode: Int,
            now: Long
        ): UpdateSummary {
            val enabled = updateUrl.isNotBlank()
            val serverState = when {
                !enabled -> null
                state.neverChecked -> "Not contacted yet"
                state.serverReachable -> "Reachable, checked ${age(state.checkedAt, now)}"
                else -> "Unreachable, last tried ${age(state.checkedAt, now)}"
            }
            val pending = state.availableVersionCode
                .takeIf { enabled && !autoUpdateEnabled && it > installedVersionCode }
                ?.let { version(state.availableVersionName, it) }
            return UpdateSummary(
                enabled = enabled,
                installedVersion = version(installedVersionName, installedVersionCode),
                serverState = serverState,
                lastOutcome = lastOutcome.takeIf { enabled && it.isNotBlank() },
                pendingVersion = pending
            )
        }

        /** `1.5 (build 5)`, falling back to the build number when a manifest omits the name. */
        fun version(name: String, code: Int): String =
            if (name.isBlank()) "build $code" else "$name (build $code)"

        /**
         * A wall-mounted tablet is read in passing, where an age answers "is this current?" and an
         * absolute timestamp does not. A clock that jumped backwards reads as "just now" rather
         * than as a negative age.
         */
        private fun age(timestamp: Long, now: Long): String {
            val elapsed = now - timestamp
            val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
            val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
            val days = TimeUnit.MILLISECONDS.toDays(elapsed)
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes ${plural(minutes, "minute")} ago"
                hours < 24 -> "$hours ${plural(hours, "hour")} ago"
                else -> "$days ${plural(days, "day")} ago"
            }
        }

        private fun plural(value: Long, unit: String): String =
            if (value == 1L) unit else "${unit}s"
    }
}
