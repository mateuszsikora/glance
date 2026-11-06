package com.glance.watchdog

/** What one WebView health check found. */
enum class HealthCheckResult {
    HEALTHY,
    UNHEALTHY,

    /** The page is still within a recent load; the check counts as neither pass nor failure. */
    STILL_LOADING
}

/**
 * Judges a page that has not finished loading.
 *
 * A health check tick can land moments after a page was created or reloaded: a new idle page,
 * a maintenance reload, or the first page after a restart. Counting such a page as failed
 * reloads it, which restarts the load already in progress. A load gets [graceMs] before an
 * unfinished page counts as failed; a main-frame error fails it at once.
 *
 * What matters is where the ticks after a reload land. A failed check reloads the page within
 * its tick, so later ticks come k·interval after the reload; a JavaScript timeout reloads it
 * 5 s into the tick, so they come k·interval − 5 s after it. A tick on the boundary leaves a
 * few milliseconds of handler jitter to decide between a skip and a failure. 35 s avoids both
 * series for the default 30 s interval (ticks at 30 and 60 s, or 25 and 55 s) and for 15 and
 * 60 s: the tick after a reload is always skipped and the one after that always judges the
 * page. At 10 and 20 s a timeout's series still hits 35 s, which can cost one extra tick.
 *
 * Timestamps are elapsed-realtime milliseconds.
 */
object PageLoadGrace {
    const val DEFAULT_GRACE_MS = 35_000L

    fun resultWhileLoading(
        mainFrameError: Boolean,
        loadStartedAtMs: Long,
        nowMs: Long,
        graceMs: Long = DEFAULT_GRACE_MS
    ): HealthCheckResult = when {
        mainFrameError -> HealthCheckResult.UNHEALTHY
        nowMs - loadStartedAtMs < graceMs -> HealthCheckResult.STILL_LOADING
        else -> HealthCheckResult.UNHEALTHY
    }
}
