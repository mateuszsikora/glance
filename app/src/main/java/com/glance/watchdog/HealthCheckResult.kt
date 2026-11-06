package com.glance.watchdog

/** What one WebView health check found. */
enum class HealthCheckResult {
    HEALTHY,
    UNHEALTHY,

    /** The page is still within its first load; the check counts as neither pass nor failure. */
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
 * Timestamps are elapsed-realtime milliseconds.
 */
object PageLoadGrace {
    const val DEFAULT_GRACE_MS = 30_000L

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
