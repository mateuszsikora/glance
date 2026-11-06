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
 * The default deliberately is not a multiple of the usual check intervals (10, 15, 20, 30 and
 * 60 s). A failed check reloads the page within the same tick, so with a grace equal to the
 * interval the next tick would land on the boundary and a few milliseconds of handler jitter
 * would decide between a skip and a second failure. With 35 s and the default 30 s interval
 * the tick after a reload is always skipped and the one after that always judges the page.
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
