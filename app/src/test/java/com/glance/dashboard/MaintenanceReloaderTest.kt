package com.glance.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceReloaderTest {

    private class Page(val name: String) {
        override fun toString() = name
    }

    private val dashboardA = Page("dashboard A")
    private val dashboardB = Page("dashboard B")
    private val idle = Page("idle")

    private var dashboards = listOf(dashboardA, dashboardB)
    private var idlePage: Page? = idle
    private var idleScreenActive = false
    private val reloads = mutableListOf<Page>()

    private var now = 0L
    private val pending = mutableListOf<Long>()

    private val reloader = MaintenanceReloader(
        pages = { dashboards + listOfNotNull(idlePage) },
        idlePage = { idlePage },
        idleScreenActive = { idleScreenActive },
        reload = { reloads += it },
        postDelayed = { pending += now + it },
        cancelDelayed = { pending.clear() },
        log = {}
    )

    /** Runs the delayed callbacks that come due, like the Activity's main-thread handler. */
    private fun advanceBy(ms: Long) {
        now += ms
        val due = pending.filter { it <= now }
        pending.removeAll(due)
        due.forEach { reloader.onDelayElapsed() }
    }

    private fun showIdle() {
        idleScreenActive = true
        reloader.onIdleShown()
    }

    private fun hideIdle(byTouch: Boolean) {
        idleScreenActive = false
        reloader.onIdleHidden(byTouch)
    }

    private fun reloadsOf(page: Page) = reloads.count { it === page }

    private fun reloadDeferredDuringPlayback() {
        showIdle()
        reloader.reloadAll()
        assertEquals(0, reloadsOf(idle))
    }

    @Test
    fun reloadsEveryDashboardAndTheHiddenIdlePageAtOnce() {
        reloader.reloadAll()
        assertEquals(listOf(dashboardA, dashboardB, idle), reloads)
    }

    @Test
    fun reloadsDashboardsWithoutAnIdlePage() {
        idlePage = null
        reloader.reloadAll()
        assertEquals(listOf(dashboardA, dashboardB), reloads)
    }

    @Test
    fun keepsTheIdlePageOnScreenButReloadsDashboardsBehindIt() {
        showIdle()
        reloader.reloadAll()
        assertEquals(listOf(dashboardA, dashboardB), reloads)
    }

    @Test
    fun dashboardsKeepReloadingWhileTheIdleReloadIsDeferred() {
        reloadDeferredDuringPlayback()
        reloader.reloadAll()
        assertEquals(2, reloadsOf(dashboardA))
        assertEquals(2, reloadsOf(dashboardB))
        assertEquals(0, reloadsOf(idle))
    }

    @Test
    fun dashboardsKeepReloadingWhileTheDeferredReloadWaitsOutItsDelay() {
        reloadDeferredDuringPlayback()
        hideIdle(byTouch = true)
        reloader.reloadAll()
        assertEquals(2, reloadsOf(dashboardA))
        assertEquals(2, reloadsOf(dashboardB))
    }

    @Test
    fun nightlyMaintenanceReloadsTheIdlePageOnScreen() {
        showIdle()
        reloader.reloadAll(includeIdleOnScreen = true)
        assertEquals(listOf(dashboardA, dashboardB, idle), reloads)
        hideIdle(byTouch = false)
        assertEquals(1, reloadsOf(idle))
    }

    @Test
    fun touchDismissalRunsTheDeferredReloadOnceAfterTheDelay() {
        reloadDeferredDuringPlayback()
        hideIdle(byTouch = true)
        advanceBy(IdleMaintenanceReload.TOUCH_DISMISS_DELAY_MS - 1)
        assertEquals(0, reloadsOf(idle))
        advanceBy(1)
        assertEquals(1, reloadsOf(idle))
        hideIdle(byTouch = false)
        advanceBy(IdleMaintenanceReload.TOUCH_DISMISS_DELAY_MS)
        assertEquals(1, reloadsOf(idle))
    }

    @Test
    fun anotherTouchDoesNotPostponeOrRepeatTheDelayedReload() {
        reloadDeferredDuringPlayback()
        hideIdle(byTouch = true)
        advanceBy(10_000)
        hideIdle(byTouch = true)
        advanceBy(IdleMaintenanceReload.TOUCH_DISMISS_DELAY_MS - 10_000)
        assertEquals(1, reloadsOf(idle))
        advanceBy(IdleMaintenanceReload.TOUCH_DISMISS_DELAY_MS)
        assertEquals(1, reloadsOf(idle))
    }

    @Test
    fun screenOffOrPauseDismissalRunsTheDeferredReloadOnceAtOnce() {
        reloadDeferredDuringPlayback()
        hideIdle(byTouch = false)
        assertEquals(1, reloadsOf(idle))
        hideIdle(byTouch = false)
        hideIdle(byTouch = true)
        advanceBy(IdleMaintenanceReload.TOUCH_DISMISS_DELAY_MS)
        assertEquals(1, reloadsOf(idle))
    }

    @Test
    fun screenOffOrPauseDuringTheDelayRunsTheReloadOnceAtOnce() {
        reloadDeferredDuringPlayback()
        hideIdle(byTouch = true)
        advanceBy(10_000)
        hideIdle(byTouch = false)
        assertEquals(1, reloadsOf(idle))
        advanceBy(IdleMaintenanceReload.TOUCH_DISMISS_DELAY_MS)
        assertEquals(1, reloadsOf(idle))
    }

    @Test
    fun idleScreenReturningDuringTheDelayKeepsTheReloadDeferred() {
        reloadDeferredDuringPlayback()
        hideIdle(byTouch = true)
        advanceBy(10_000)
        showIdle()
        advanceBy(IdleMaintenanceReload.TOUCH_DISMISS_DELAY_MS)
        assertEquals(0, reloadsOf(idle))
        // The next dismissal waits out a full delay, not what was left of the first one.
        hideIdle(byTouch = true)
        advanceBy(IdleMaintenanceReload.TOUCH_DISMISS_DELAY_MS - 1)
        assertEquals(0, reloadsOf(idle))
        advanceBy(1)
        assertEquals(1, reloadsOf(idle))
    }

    @Test
    fun repeatedRequestsDuringPlaybackRunOneDeferredReload() {
        reloadDeferredDuringPlayback()
        reloader.reloadAll()
        reloader.reloadAll()
        hideIdle(byTouch = false)
        hideIdle(byTouch = false)
        assertEquals(1, reloadsOf(idle))
    }

    @Test
    fun reloadOfTheHiddenIdlePageSettlesTheDelayedOne() {
        reloadDeferredDuringPlayback()
        hideIdle(byTouch = true)
        reloader.reloadAll()
        assertEquals(1, reloadsOf(idle))
        advanceBy(IdleMaintenanceReload.TOUCH_DISMISS_DELAY_MS)
        hideIdle(byTouch = false)
        assertEquals(1, reloadsOf(idle))
    }

    @Test
    fun dismissalWithoutADeferredReloadReloadsNothing() {
        showIdle()
        hideIdle(byTouch = true)
        hideIdle(byTouch = false)
        advanceBy(IdleMaintenanceReload.TOUCH_DISMISS_DELAY_MS)
        assertTrue(reloads.isEmpty())
    }

    @Test
    fun dashboardsAreAlwaysReloadable() {
        assertTrue(reloader.isReloadable(dashboardA))
        showIdle()
        assertTrue(reloader.isReloadable(dashboardA))
    }

    @Test
    fun idlePageIsReloadableOnlyWhileOnScreen() {
        assertFalse(reloader.isReloadable(idle))
        showIdle()
        assertTrue(reloader.isReloadable(idle))
        hideIdle(byTouch = true)
        assertFalse(reloader.isReloadable(idle))
    }
}
