package com.glance.watchdog

import android.content.ComponentCallbacks2

/**
 * Decides which memory trim signals justify reloading the WebViews.
 *
 * [ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW] is deliberately ignored. Android sends it to the
 * foreground app whenever the device as a whole runs short, which on a small tablet happens every
 * time the charger is connected and the system starts a burst of power-event receivers. It says
 * nothing about the WebViews themselves, and reloading every page at once only adds allocation
 * pressure while interrupting what is on screen.
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
