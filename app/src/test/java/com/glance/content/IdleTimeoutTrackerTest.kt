package com.glance.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleTimeoutTrackerTest {
    @Test
    fun countsFromMostRecentActivity() {
        val tracker = IdleTimeoutTracker(timeoutMs = 300_000L)
        tracker.recordActivity(nowElapsedMs = 1_000L)

        assertEquals(200_000L, tracker.remainingMs(nowElapsedMs = 101_000L))
        assertFalse(tracker.isExpired(nowElapsedMs = 300_999L))
        assertTrue(tracker.isExpired(nowElapsedMs = 301_000L))

        tracker.recordActivity(nowElapsedMs = 301_000L)
        assertEquals(300_000L, tracker.remainingMs(nowElapsedMs = 301_000L))
    }

    @Test
    fun elapsedClockMovingBackDoesNotExpireTimer() {
        val tracker = IdleTimeoutTracker(timeoutMs = 60_000L)
        tracker.recordActivity(nowElapsedMs = 50_000L)

        assertEquals(60_000L, tracker.remainingMs(nowElapsedMs = 40_000L))
    }
}
