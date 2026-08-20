package com.glance.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerWakePolicyTest {

    @Test
    fun aChargerWakeInsideAnOffWindowIsUndone() {
        assertTrue(
            PowerWakePolicy.shouldRestoreScreenOff(requestedScreenOn = false, displayOn = true)
        )
    }

    @Test
    fun aChargerEventThatWokeNothingChangesNothing() {
        assertFalse(
            PowerWakePolicy.shouldRestoreScreenOff(requestedScreenOn = false, displayOn = false)
        )
    }

    @Test
    fun aChargerEventDuringTheOnWindowLeavesTheDashboardAlone() {
        assertTrue(PowerWakePolicy.shouldRestoreScreenOff(false, displayOn = true))
        assertFalse(PowerWakePolicy.shouldRestoreScreenOff(true, displayOn = true))
        assertFalse(PowerWakePolicy.shouldRestoreScreenOff(true, displayOn = false))
    }
}
