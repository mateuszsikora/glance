package com.glance.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class BootScreenGraceTest {

    private var now = 0L
    private val grace = BootScreenGrace { now }

    @Test
    fun theFirstOffRightAfterTheBootWaitsAndLaterOnesShareTheSameEnd() {
        now = 70_000L
        assertEquals(BootScreenGrace.VISIBLE_MS, grace.remainingMs())

        now += 5_000L
        assertEquals(BootScreenGrace.VISIBLE_MS - 5_000L, grace.remainingMs())

        now += BootScreenGrace.VISIBLE_MS
        assertEquals(0L, grace.remainingMs())
    }

    @Test
    fun anOffLongAfterTheBootDoesNotWait() {
        now = BootScreenGrace.BOOT_WINDOW_MS + 1L
        assertEquals(0L, grace.remainingMs())

        // Once decided, the answer stays 0 for the process.
        now += 1_000L
        assertEquals(0L, grace.remainingMs())
    }
}
