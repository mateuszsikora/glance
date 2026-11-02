package com.glance.watchdog

import android.content.ComponentCallbacks2

/**
 * Decides which memory trim signals justify reloading the WebViews.
 *
 * All `TRIM_MEMORY_RUNNING_*` levels describe the device as a whole, not the WebViews. On a small
 * tablet they arrive whenever the charger is connected and the system starts a burst of
 * power-event receivers, so none of them reloads, not even
 * [ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL]. On a 3 GB tablet that level arrived 33-74
 * times a day; none of its 80 reloads over 62 hours lowered the native heap, and each one restarted
 * every dashboard. Only [ComponentCallbacks2.TRIM_MEMORY_MODERATE] and above, which mean the
 * process itself is on the low-memory killer's list, still reload.
 */
object MemoryTrimPolicy {

    // These callbacks still matter on the supported Android 8-14 devices even though
    // Android 15 deprecated the legacy trim levels for newer platform behavior.
    @Suppress("DEPRECATION")
    fun requiresReload(level: Int): Boolean {
        return level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
    }
}
