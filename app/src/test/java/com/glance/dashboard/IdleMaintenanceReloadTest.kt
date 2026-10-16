package com.glance.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleMaintenanceReloadTest {

    @Test
    fun hiddenIdlePageReloadsImmediately() {
        val reload = IdleMaintenanceReload()
        assertTrue(reload.onMaintenanceReload(idleOnScreen = false))
        assertFalse(reload.onIdleHidden())
    }

    @Test
    fun idlePageOnScreenIsNotReloadedDuringPlayback() {
        val reload = IdleMaintenanceReload()
        assertFalse(reload.onMaintenanceReload(idleOnScreen = true))
    }

    @Test
    fun deferredReloadRunsOnceWhenIdleScreenIsDismissed() {
        val reload = IdleMaintenanceReload()
        reload.onMaintenanceReload(idleOnScreen = true)
        assertTrue(reload.onIdleHidden())
        assertFalse(reload.onIdleHidden())
    }

    @Test
    fun repeatedRequestsDuringPlaybackCollapseIntoOneReload() {
        val reload = IdleMaintenanceReload()
        reload.onMaintenanceReload(idleOnScreen = true)
        reload.onMaintenanceReload(idleOnScreen = true)
        assertTrue(reload.onIdleHidden())
        assertFalse(reload.onIdleHidden())
    }

    @Test
    fun reloadWhileHiddenSettlesAnEarlierDeferredRequest() {
        val reload = IdleMaintenanceReload()
        reload.onMaintenanceReload(idleOnScreen = true)
        assertTrue(reload.onMaintenanceReload(idleOnScreen = false))
        assertFalse(reload.onIdleHidden())
    }
}
