package com.glance.watchdog

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.glance.dashboard.WebViewFragment
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Probes loaded content and reloads only the WebView whose host recovered. Main-thread API. */
class WebViewReachabilityChecker(
    // Include retained, hidden pages here so their unresolved outages survive being paused.
    private val fragments: () -> List<WebViewFragment>,
    private val canCheck: () -> Boolean,
    private val shouldCheck: (WebViewFragment) -> Boolean = { true },
    private val canReload: () -> Boolean = canCheck,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val probe: (String) -> Boolean = { DashboardReachabilityProbe.isReachable(it) },
    private val nowMs: () -> Long = SystemClock::elapsedRealtime
) {
    // URL alone is insufficient: a profile replacement can create a fresh page at the same URL.
    private data class Target(
        val fragment: WebViewFragment,
        val view: View,
        val url: String,
        val generation: Long
    )

    private val handler = Handler(Looper.getMainLooper())
    private val policies = mutableMapOf<Target, StaleDashboardPolicy>()
    private var inFlight = false
    private var destroyed = false

    private fun targets(): List<Target> = fragments().mapNotNull { fragment ->
        val view = fragment.view ?: return@mapNotNull null
        val url = fragment.configuredUrl.takeIf(String::isNotBlank) ?: return@mapNotNull null
        Target(fragment, view, url, fragment.contentGeneration)
    }

    fun check() {
        if (destroyed || !canCheck()) return
        val targets = targets()
        policies.keys.retainAll(targets.toSet())
        if (inFlight) return
        val checks = targets.filter { shouldCheck(it.fragment) }
            .map { it to policies.getOrPut(it) { StaleDashboardPolicy() } }
        if (checks.isEmpty()) return
        inFlight = true
        executor.execute {
            for ((target, policy) in checks) {
                val reachable = probe(target.url)
                handler.post {
                    if (destroyed || !canCheck() || policies[target] !== policy ||
                        target !in targets()
                    ) return@post
                    // Soft-off leaves the hardware interactive: keep observing failures, but
                    // don't consume recovery until the screen and this page are eligible again.
                    // The page may also have been hidden while its probe was in flight.
                    if (reachable && (!canReload() || !shouldCheck(target.fragment))) return@post
                    if (policy.onProbeResult(nowMs(), reachable)) {
                        Log.i(TAG, "Content host answered again, reloading its WebView")
                        target.fragment.reload()
                    }
                }
            }
            handler.post { inFlight = false }
        }
    }

    fun destroy() {
        destroyed = true
        policies.clear()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "WebViewReachability"
    }
}
