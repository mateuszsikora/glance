package com.glance.watchdog

import android.content.ComponentCallbacks2

/**
 * Decides which memory trim signals justify reloading the WebViews.
 *
 * Both `TRIM_MEMORY_RUNNING_*` levels describe the device as a whole, not the WebViews. On a small
 * tablet they arrive whenever the charger is connected and the system starts a burst of
 * power-event receivers. [ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW] is the most frequent and
 * least severe of them, so it is ignored to cut down needless reloads. This is noise reduction
 * only: [ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL] usually follows within seconds and
 * still reloads. What keeps such a reload off the idle page on screen is
 * [com.glance.dashboard.IdleMaintenanceReload].
 */
object MemoryTrimPolicy {

    // These callbacks still matter on the supported Android 8-14 devices even though
    // Android 15 deprecated the legacy trim levels for newer platform behavior.
    @Suppress("DEPRECATION")
    fun requiresReload(level: Int): Boolean {
        return level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
    }
}
