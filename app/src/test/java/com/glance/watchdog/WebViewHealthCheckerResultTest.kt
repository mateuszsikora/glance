package com.glance.watchdog

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WebViewHealthCheckerResultTest {
    private val checker = WebViewHealthChecker(maxConsecutiveFailures = 3)
    private var reloads = 0
    private var restarts = 0

    init {
        checker.onReloadNeeded = { reloads++ }
        checker.onRestartNeeded = { restarts++ }
    }

    @Test
    fun stillLoadingPageIsNeitherReloadedNorRestarted() {
        repeat(5) { checker.onResult(HealthCheckResult.STILL_LOADING) }

        assertEquals(0, reloads)
        assertEquals(0, restarts)
    }

    @Test
    fun stillLoadingDoesNotClearEarlierFailures() {
        checker.onResult(HealthCheckResult.UNHEALTHY)
        checker.onResult(HealthCheckResult.STILL_LOADING)
        checker.onResult(HealthCheckResult.UNHEALTHY)
        checker.onResult(HealthCheckResult.STILL_LOADING)
        checker.onResult(HealthCheckResult.UNHEALTHY)

        assertEquals(2, reloads)
        assertEquals(1, restarts)
    }

    @Test
    fun healthyPageClearsEarlierFailures() {
        checker.onResult(HealthCheckResult.UNHEALTHY)
        checker.onResult(HealthCheckResult.UNHEALTHY)
        checker.onResult(HealthCheckResult.HEALTHY)
        checker.onResult(HealthCheckResult.UNHEALTHY)

        assertEquals(3, reloads)
        assertEquals(0, restarts)
    }
}
