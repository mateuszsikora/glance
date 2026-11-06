package com.glance.dashboard

import android.os.Looper
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import com.glance.R
import com.glance.diagnostics.DiagnosticLog
import com.glance.diagnostics.Diagnostics
import com.glance.watchdog.HealthCheckResult
import com.glance.watchdog.PageLoadGrace
import com.glance.watchdog.WebViewHealthChecker
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WebViewFragmentHealthCheckTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var journalEnabled = true

    @After
    fun disableJournal() {
        // Diagnostics is process-wide; leave behind a journal that records nothing.
        journalEnabled = false
    }

    @Test
    fun freshlyLoadedPageIsStillLoading() {
        val fragment = shownFragment()

        assertEquals(HealthCheckResult.STILL_LOADING, healthOf(fragment))
    }

    @Test
    fun skippedCheckIsJournaled() {
        val journal = DiagnosticLog(
            directory = folder.root,
            enabled = { journalEnabled },
            sampleMemory = { "" }
        )
        Diagnostics.install(journal)
        val fragment = shownFragment()

        healthOf(fragment)

        assertTrue(
            journal.read().contains("Health check skipped; page idle still loading after 0 s")
        )
    }

    @Test
    fun loadThatOutlivesTheGracePeriodIsUnhealthy() {
        val fragment = shownFragment()

        advanceToEndOfGrace()

        assertEquals(HealthCheckResult.UNHEALTHY, healthOf(fragment))
    }

    @Test
    fun reloadStartsANewGracePeriod() {
        val fragment = shownFragment()
        advanceToEndOfGrace()

        fragment.reload()

        assertEquals(HealthCheckResult.STILL_LOADING, healthOf(fragment))
    }

    @Test
    fun navigationStartedByThePageStartsANewGracePeriod() {
        val fragment = shownFragment()
        val webView = webViewOf(fragment)
        webView.webViewClient.onPageFinished(webView, URL)
        advanceToEndOfGrace()

        webView.webViewClient.onPageStarted(webView, URL, null)

        assertEquals(HealthCheckResult.STILL_LOADING, healthOf(fragment))
    }

    @Test
    fun redirectsWithoutAFinishedLoadKeepTheOriginalGracePeriod() {
        val fragment = shownFragment()
        val webView = webViewOf(fragment)
        webView.webViewClient.onPageStarted(webView, URL, null)
        advanceToEndOfGrace()

        webView.webViewClient.onPageStarted(webView, "$URL/redirected", null)

        assertEquals(HealthCheckResult.UNHEALTHY, healthOf(fragment))
    }

    @Test
    fun mainFrameErrorIsUnhealthyWithinTheGracePeriod() {
        val fragment = shownFragment()
        val webView = webViewOf(fragment)

        webView.webViewClient.onReceivedSslError(webView, null, null)

        assertEquals(HealthCheckResult.UNHEALTHY, healthOf(fragment))
    }

    @Test
    fun checkerNeitherReloadsNorTimesOutAPageThatIsStillLoading() {
        val fragment = shownFragment()
        val checker = WebViewHealthChecker(timeoutMs = 5_000L)
        var reloads = 0
        var restarts = 0
        checker.onReloadNeeded = { reloads++ }
        checker.onRestartNeeded = { restarts++ }

        repeat(3) {
            checker.check(fragment)
            // Lets a timeout that the synchronous result failed to cancel fire.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        }

        assertEquals(0, reloads)
        assertEquals(0, restarts)
        checker.reset()
    }

    private fun shownFragment(): WebViewFragment {
        val fragment = WebViewFragment.newInstance(URL, diagnosticLabel = "idle")
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
        return fragment
    }

    private fun webViewOf(fragment: WebViewFragment): WebView =
        fragment.requireView().findViewById(R.id.webview)

    private fun healthOf(fragment: WebViewFragment): HealthCheckResult? {
        var result: HealthCheckResult? = null
        fragment.onHealthCheckCallback = { result = it }
        fragment.performHealthCheck()
        return result
    }

    private fun advanceToEndOfGrace() {
        ShadowSystemClock.advanceBy(Duration.ofMillis(PageLoadGrace.DEFAULT_GRACE_MS))
    }

    private companion object {
        const val URL = "https://idle.example.test"
    }
}
