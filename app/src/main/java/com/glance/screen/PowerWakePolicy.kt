package com.glance.screen

/**
 * Decides what to do about a display wake that Android performs when the charger is connected or
 * removed. A smart plug that maintains the battery of a wall-mounted tablet switches the charger
 * at any hour, and none of those switches is a request to show the dashboard.
 */
object PowerWakePolicy {

    /**
     * @param requestedScreenOn what the schedule or the last explicit command asked for.
     * @param displayOn whether the display is actually lit right now.
     */
    fun shouldRestoreScreenOff(requestedScreenOn: Boolean, displayOn: Boolean): Boolean {
        return !requestedScreenOn && displayOn
    }
}
