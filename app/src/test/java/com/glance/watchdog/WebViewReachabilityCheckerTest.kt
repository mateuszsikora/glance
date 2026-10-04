package com.glance.watchdog

import android.os.Looper
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.glance.R
import com.glance.dashboard.WebViewFragment
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WebViewReachabilityCheckerTest {
    private val activityController = Robolectric.buildActivity(FragmentActivity::class.java)
    private val activity get() = activityController.get()
    private val executor = QueuedExecutor()
    private val fragments = mutableListOf<WebViewFragment>()
    private val unreachableUrls = mutableSetOf<String>()
    private val hiddenFragments = mutableSetOf<WebViewFragment>()
    private var interactive = true
    private var softScreenOff = false
    private var now = 0L
    private lateinit var checker: WebViewReachabilityChecker

    @Before
    fun setUp() {
        activity.setTheme(R.style.Theme_Glance)
        activityController.setup()
        checker = WebViewReachabilityChecker(
            fragments = { fragments.toList() },
            canCheck = { interactive },
            shouldCheck = { it !in hiddenFragments },
            canReload = { !softScreenOff && activity.lifecycle.currentState == Lifecycle.State.RESUMED },
            executor = executor,
            probe = { it !in unreachableUrls },
            nowMs = { now }
        )
    }

    @After
    fun tearDown() {
        checker.destroy()
        activityController.pause().stop().destroy()
    }

    private fun page(url: String): WebViewFragment {
        val fragment = WebViewFragment.newInstance(url)
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment).commitNow()
        fragments += fragment
        return fragment
    }

    private fun wasReloaded(fragment: WebViewFragment): Boolean =
        reloadCount(fragment) > 0

    private fun reloadCount(fragment: WebViewFragment): Int =
        shadowOf(fragment.requireView().findViewById<WebView>(R.id.webview)).reloadInvocations

    private fun completeProbe() {
        executor.runPending()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun check(at: Long) {
        now = at
        checker.check()
        completeProbe()
    }

    @Test
    fun dashboardRecoveryDuringIdlePlaybackReloadsOnlyDashboard() {
        val dashboard = page(DASHBOARD)
        val idle = page(IDLE)
        unreachableUrls += DASHBOARD
        check(0L)
        check(30_000L)
        unreachableUrls.clear()
        check(60_000L)
        assertTrue(wasReloaded(dashboard))
        assertFalse(wasReloaded(idle))
    }

    @Test
    fun idleHostRecoveryReloadsOnlyIdlePage() {
        val dashboard = page(DASHBOARD)
        val idle = page(IDLE)
        unreachableUrls += IDLE
        check(0L)
        unreachableUrls.clear()
        check(30_000L)
        assertFalse(wasReloaded(dashboard))
        assertTrue(wasReloaded(idle))
    }

    @Test
    fun changedUrlDoesNotInheritPreviousOutage() {
        val dashboard = page(DASHBOARD)
        unreachableUrls += DASHBOARD
        check(0L)
        dashboard.loadUrl("https://other.example.test/")
        check(30_000L)
        assertFalse(wasReloaded(dashboard))
    }

    @Test
    fun profileReplacementAtSameUrlDoesNotInheritPreviousOutage() {
        val old = page(DASHBOARD)
        unreachableUrls += DASHBOARD
        check(0L)
        fragments.remove(old)
        val replacement = page(DASHBOARD)
        unreachableUrls.clear()
        check(30_000L)
        assertFalse(wasReloaded(replacement))
        assertFalse(wasReloaded(old))
    }

    @Test
    fun inFlightRecoveryCannotReloadReplacedTarget() {
        val old = page(DASHBOARD)
        unreachableUrls += DASHBOARD
        check(0L)
        unreachableUrls.clear()
        now = 30_000L
        checker.check()
        fragments.remove(old)
        val replacement = page(DASHBOARD)
        completeProbe()
        assertFalse(wasReloaded(old))
        assertFalse(wasReloaded(replacement))
    }

    @Test
    fun inFlightRecoveryCannotReloadANewLoadEvenIfUrlReturnsToOriginal() {
        val dashboard = page(DASHBOARD)
        unreachableUrls += DASHBOARD
        check(0L)
        unreachableUrls.clear()
        now = 30_000L
        checker.check()
        dashboard.loadUrl("https://other.example.test/")
        dashboard.loadUrl(DASHBOARD)
        completeProbe()
        assertFalse(wasReloaded(dashboard))
        check(60_000L)
        assertFalse(wasReloaded(dashboard))
    }

    @Test
    fun screenOffDuringRecoveryDefersOnlyAffectedPageUntilNextCheck() {
        val dashboard = page(DASHBOARD)
        val idle = page(IDLE)
        unreachableUrls += DASHBOARD
        check(0L)
        unreachableUrls.clear()
        now = 30_000L
        checker.check()
        interactive = false
        completeProbe()
        assertFalse(wasReloaded(dashboard))
        assertFalse(wasReloaded(idle))
        interactive = true
        check(60_000L)
        assertTrue(wasReloaded(dashboard))
        assertFalse(wasReloaded(idle))
    }

    @Test
    fun generalReloadDiscardsInFlightRecovery() {
        val dashboard = page(DASHBOARD)
        unreachableUrls += DASHBOARD
        check(0L)
        unreachableUrls.clear()
        now = 30_000L
        checker.check()
        dashboard.reload()
        completeProbe()
        assertEquals(1, reloadCount(dashboard))
        check(60_000L)
        assertEquals(1, reloadCount(dashboard))
    }

    @Test
    fun softScreenOffOutageMustRecoverOnWake() {
        val dashboard = page(DASHBOARD)
        check(0L)
        // A non-Device-Owner black overlay leaves PowerManager interactive.
        softScreenOff = true
        unreachableUrls += DASHBOARD
        check(30_000L)
        unreachableUrls.clear()
        check(60_000L)
        check(90_000L)
        assertFalse(wasReloaded(dashboard))
        softScreenOff = false
        check(120_000L)
        assertEquals(1, reloadCount(dashboard))
        check(150_000L)
        assertEquals(1, reloadCount(dashboard))
    }

    @Test
    fun outageDuringSettingsMustRecoverWhenSameDashboardResumes() {
        val dashboard = page(DASHBOARD)
        check(0L)
        // Settings covers the Activity without destroying its dashboard document.
        activityController.pause().stop()
        unreachableUrls += DASHBOARD
        check(30_000L)
        check(60_000L)
        unreachableUrls.clear()
        check(90_000L)
        check(120_000L)
        assertEquals(0, reloadCount(dashboard))
        // Cancel returns without a UI recreation or URL change.
        activityController.start().resume()
        check(150_000L)
        assertEquals(1, reloadCount(dashboard))
        check(180_000L)
        assertEquals(1, reloadCount(dashboard))
    }

    @Test
    fun recoveryInFlightWhenSettingsOpensWaitsForDashboardResume() {
        val dashboard = page(DASHBOARD)
        unreachableUrls += DASHBOARD
        check(0L)
        unreachableUrls.clear()
        now = 30_000L
        checker.check()
        activityController.pause().stop()
        completeProbe()
        check(60_000L)
        assertEquals(0, reloadCount(dashboard))
        activityController.start().resume()
        check(90_000L)
        assertEquals(1, reloadCount(dashboard))
    }

    @Test
    fun hiddenIdlePageKeepsKnownOutageUntilShownAgain() {
        val dashboard = page(DASHBOARD)
        val idle = page(IDLE)
        unreachableUrls += IDLE
        check(0L)
        hiddenFragments += idle
        idle.setWebContentPaused(true)
        check(30_000L)
        unreachableUrls.clear()
        check(60_000L)
        assertFalse(wasReloaded(idle))
        hiddenFragments -= idle
        idle.setWebContentPaused(false)
        check(90_000L)
        assertEquals(1, reloadCount(idle))
        assertFalse(wasReloaded(dashboard))
    }

    @Test
    fun recoveryInFlightWhenIdleIsHiddenWaitsUntilItIsShown() {
        page(DASHBOARD)
        val idle = page(IDLE)
        unreachableUrls += IDLE
        check(0L)
        unreachableUrls.clear()
        now = 30_000L
        checker.check()
        hiddenFragments += idle
        completeProbe()
        check(60_000L)
        assertFalse(wasReloaded(idle))
        hiddenFragments -= idle
        check(90_000L)
        assertEquals(1, reloadCount(idle))
    }

    @Test
    fun generalReloadKeepsHiddenIdleOutageHistory() {
        val dashboard = page(DASHBOARD)
        val idle = page(IDLE)
        unreachableUrls += IDLE
        check(0L)
        hiddenFragments += idle
        // General maintenance reloads dashboards and only an active idle page.
        dashboard.reload()
        unreachableUrls.clear()
        check(30_000L)
        hiddenFragments -= idle
        check(60_000L)
        assertEquals(1, reloadCount(idle))
        assertEquals(1, reloadCount(dashboard))
    }

    @Test
    fun changedHiddenDocumentDoesNotInheritItsPreviousOutage() {
        page(DASHBOARD)
        val idle = page(IDLE)
        unreachableUrls += IDLE
        check(0L)
        hiddenFragments += idle
        idle.loadUrl(IDLE)
        unreachableUrls.clear()
        check(30_000L)
        hiddenFragments -= idle
        check(60_000L)
        assertFalse(wasReloaded(idle))
    }

    @Test
    fun destructionDiscardsInFlightRecovery() {
        val dashboard = page(DASHBOARD)
        unreachableUrls += DASHBOARD
        check(0L)
        unreachableUrls.clear()
        now = 30_000L
        checker.check()
        checker.destroy()
        completeProbe()
        assertFalse(wasReloaded(dashboard))
    }

    private class QueuedExecutor : AbstractExecutorService() {
        private val pending = mutableListOf<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { pending += command }
        fun runPending() {
            val tasks = pending.toList()
            pending.clear()
            tasks.forEach(Runnable::run)
        }
        override fun shutdown() { stopped = true }
        // Leave queued work available to simulate a network call that finishes after shutdown.
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }

    private companion object {
        const val DASHBOARD = "https://dashboard.example.test/"
        const val IDLE = "https://idle.example.test/"
    }
}
