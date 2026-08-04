package com.glance.watchdog

/**
 * Decides when a dashboard that stayed loaded through an outage has become stale.
 *
 * A dashboard whose backend restarted, or whose network dropped, keeps rendering its last
 * frame — Home Assistant, for example, only overlays its own "connection lost" banner. Such a
 * page still reports `readyState` "complete" on the configured origin, so
 * [WebViewHealthChecker] cannot tell it apart from a healthy one and the page survives until
 * the next periodic reload, hours later. Watching the dashboard host and the screen-off
 * window supplies the missing signal.
 *
 * All timestamps are elapsed-realtime milliseconds, so wall-clock changes cannot skew them.
 */
class StaleDashboardPolicy(
    private val minUnreachableMs: Long = DEFAULT_MIN_UNREACHABLE_MS,
    private val staleScreenOffMs: Long = DEFAULT_STALE_SCREEN_OFF_MS
) {

    private var unreachableSinceMs: Long? = null
    private var screenOffSinceMs: Long? = null

    /**
     * Records the result of one dashboard reachability probe.
     *
     * @return true when the host recovered from an outage long enough to have dropped the
     * dashboard's live connection.
     */
    fun onProbeResult(nowMs: Long, reachable: Boolean): Boolean {
        if (!reachable) {
            if (unreachableSinceMs == null) unreachableSinceMs = nowMs
            return false
        }
        val unreachableSince = unreachableSinceMs ?: return false
        unreachableSinceMs = null
        return nowMs - unreachableSince >= minUnreachableMs
    }

    /** Records that the screen went dark. Probing is suspended for as long as it stays dark. */
    fun onScreenOff(nowMs: Long) {
        if (screenOffSinceMs == null) screenOffSinceMs = nowMs
    }

    /**
     * @return true when the screen stayed off long enough that the dashboard behind it has to
     * be treated as disconnected.
     */
    fun onScreenOn(nowMs: Long): Boolean {
        val screenOffSince = screenOffSinceMs ?: return false
        screenOffSinceMs = null
        val stale = nowMs - screenOffSince >= staleScreenOffMs
        // A reload settles any outage that was still open when the screen went dark, so the
        // recovering probe must not ask for a second one.
        if (stale) unreachableSinceMs = null
        return stale
    }

    companion object {
        /** Slightly below the default health-check interval, so a single missed probe counts. */
        private const val DEFAULT_MIN_UNREACHABLE_MS = 15_000L
        private const val DEFAULT_STALE_SCREEN_OFF_MS = 5 * 60_000L
    }
}
