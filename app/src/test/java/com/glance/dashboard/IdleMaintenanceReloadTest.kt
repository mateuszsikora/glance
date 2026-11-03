package com.glance.dashboard

import com.glance.dashboard.IdleMaintenanceReload.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleMaintenanceReloadTest {

    @Test
    fun hiddenIdlePageReloadsImmediately() {
        val reload = IdleMaintenanceReload()
        assertTrue(reload.onMaintenanceReload(idleOnScreen = false))
        assertEquals(Action.NONE, reload.onIdleHidden(byTouch = true))
        assertEquals(Action.NONE, reload.onIdleHidden(byTouch = false))
    }

    @Test
    fun idlePageOnScreenIsNotReloadedDuringPlayback() {
        val reload = IdleMaintenanceReload()
        assertFalse(reload.onMaintenanceReload(idleOnScreen = true))
    }

    @Test
    fun touchDismissalRunsTheDeferredReloadAfterTheDelay() {
        val reload = IdleMaintenanceReload()
        reload.onMaintenanceReload(idleOnScreen = true)
        assertEquals(Action.RELOAD_AFTER_DELAY, reload.onIdleHidden(byTouch = true))
        assertTrue(reload.onDelayElapsed())
        assertFalse(reload.onDelayElapsed())
        assertEquals(Action.NONE, reload.onIdleHidden(byTouch = false))
    }

    @Test
    fun anotherTouchDoesNotPostponeTheDelayedReload() {
        val reload = IdleMaintenanceReload()
        reload.onMaintenanceReload(idleOnScreen = true)
        reload.onIdleHidden(byTouch = true)
        assertEquals(Action.NONE, reload.onIdleHidden(byTouch = true))
        assertTrue(reload.onDelayElapsed())
    }

    @Test
    fun idleScreenReturningBeforeTheDelayKeepsTheReloadDeferred() {
        val reload = IdleMaintenanceReload()
        reload.onMaintenanceReload(idleOnScreen = true)
        reload.onIdleHidden(byTouch = true)
        reload.onIdleShown()
        assertFalse(reload.onDelayElapsed())
        assertEquals(Action.RELOAD_AFTER_DELAY, reload.onIdleHidden(byTouch = true))
        assertTrue(reload.onDelayElapsed())
    }

    @Test
    fun screenOffOrPauseDismissalReloadsWithoutDelay() {
        val reload = IdleMaintenanceReload()
        reload.onMaintenanceReload(idleOnScreen = true)
        assertEquals(Action.RELOAD_NOW, reload.onIdleHidden(byTouch = false))
        assertEquals(Action.NONE, reload.onIdleHidden(byTouch = false))
        assertFalse(reload.onDelayElapsed())
    }

    @Test
    fun screenOffOrPauseDuringTheDelayRunsTheReloadAtOnce() {
        val reload = IdleMaintenanceReload()
        reload.onMaintenanceReload(idleOnScreen = true)
        reload.onIdleHidden(byTouch = true)
        assertEquals(Action.RELOAD_NOW, reload.onIdleHidden(byTouch = false))
        assertFalse(reload.onDelayElapsed())
    }

    @Test
    fun repeatedRequestsDuringPlaybackCollapseIntoOneReload() {
        val reload = IdleMaintenanceReload()
        reload.onMaintenanceReload(idleOnScreen = true)
        reload.onMaintenanceReload(idleOnScreen = true)
        assertEquals(Action.RELOAD_NOW, reload.onIdleHidden(byTouch = false))
        assertEquals(Action.NONE, reload.onIdleHidden(byTouch = false))
    }

    @Test
    fun reloadWhileHiddenSettlesAnEarlierDeferredRequest() {
        val reload = IdleMaintenanceReload()
        reload.onMaintenanceReload(idleOnScreen = true)
        assertTrue(reload.onMaintenanceReload(idleOnScreen = false))
        assertEquals(Action.NONE, reload.onIdleHidden(byTouch = false))
    }

    @Test
    fun reloadWhileHiddenSettlesADelayedRequest() {
        val reload = IdleMaintenanceReload()
        reload.onMaintenanceReload(idleOnScreen = true)
        reload.onIdleHidden(byTouch = true)
        assertTrue(reload.onMaintenanceReload(idleOnScreen = false))
        assertFalse(reload.onDelayElapsed())
    }
}
