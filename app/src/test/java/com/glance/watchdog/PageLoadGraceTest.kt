package com.glance.watchdog

import org.junit.Assert.assertEquals
import org.junit.Test

class PageLoadGraceTest {
    @Test
    fun pageWithinItsFirstLoadIsStillLoading() {
        assertEquals(
            HealthCheckResult.STILL_LOADING,
            PageLoadGrace.resultWhileLoading(
                mainFrameError = false,
                loadStartedAtMs = 100_000L,
                nowMs = 101_000L
            )
        )
    }

    @Test
    fun loadThatOutlivesTheGracePeriodFails() {
        assertEquals(
            HealthCheckResult.UNHEALTHY,
            PageLoadGrace.resultWhileLoading(
                mainFrameError = false,
                loadStartedAtMs = 100_000L,
                nowMs = 100_000L + PageLoadGrace.DEFAULT_GRACE_MS
            )
        )
    }

    @Test
    fun mainFrameErrorFailsEvenWithinTheGracePeriod() {
        assertEquals(
            HealthCheckResult.UNHEALTHY,
            PageLoadGrace.resultWhileLoading(
                mainFrameError = true,
                loadStartedAtMs = 100_000L,
                nowMs = 101_000L
            )
        )
    }
}
