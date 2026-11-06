package com.glance.dashboard

import com.glance.dashboard.MemoryPressure.Companion.SWAP_IN_LIMIT_PAGES_PER_S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPressureTest {

    @Test
    fun measuresTheSwapInRateSinceThePreviousReading() {
        var counter = 10_000L
        val pressure = MemoryPressure(lowMemory = { false }, swapIns = { counter }, nowMs = 0L)

        counter += 3_000L
        assertEquals(1_500L, pressure.read(nowMs = 2_000L).swapInPagesPerSecond)
        counter += 100L
        assertEquals(50L, pressure.read(nowMs = 4_000L).swapInPagesPerSecond)
    }

    @Test
    fun startWindowLimitsTheRateToTheTimeBeforeTheReading() {
        var counter = 0L
        val pressure = MemoryPressure(lowMemory = { false }, swapIns = { counter }, nowMs = 0L)

        // A burst long before the check must not hold the dashboard back.
        counter += 60_000L
        pressure.startWindow(nowMs = 29_000L)
        counter += 200L

        assertEquals(200L, pressure.read(nowMs = 30_000L).swapInPagesPerSecond)
    }

    @Test
    fun pollingMeasuresFromTheNewestMarkAtLeastAWindowOld() {
        var counter = 0L
        val pressure = MemoryPressure(lowMemory = { false }, swapIns = { counter }, nowMs = 0L)

        // Polled every 200 ms while the start work runs; the burst ends before the last second.
        for (at in 200L..9_800L step 200L) {
            if (at < 8_000L) counter += 1_000L
            pressure.mark(nowMs = at)
        }

        // From the mark at 8,800 ms, 1.2 s before the reading: nothing came back since 8,000 ms.
        assertEquals(0L, pressure.read(nowMs = 10_000L).swapInPagesPerSecond)
    }

    @Test
    fun earlyReadingMeasuresFromTheOldestMark() {
        var counter = 0L
        val pressure = MemoryPressure(lowMemory = { false }, swapIns = { counter }, nowMs = 0L)
        pressure.mark(nowMs = 200L)
        counter += 400L

        assertEquals(1_000L, pressure.read(nowMs = 400L).swapInPagesPerSecond)
    }

    @Test
    fun rateIsUnknownWithoutTheCounterOrAfterItStartedOver() {
        var counter: Long? = null
        val pressure = MemoryPressure(lowMemory = { false }, swapIns = { counter }, nowMs = 0L)

        counter = 500L
        assertNull(pressure.read(nowMs = 1_000L).swapInPagesPerSecond)
        counter = 100L
        assertNull(pressure.read(nowMs = 2_000L).swapInPagesPerSecond)
    }

    @Test
    fun fastSwapInIsPressureEvenWithoutTheLowMemoryFlag() {
        assertTrue(MemoryPressure.Reading(false, SWAP_IN_LIMIT_PAGES_PER_S).high)
        assertFalse(MemoryPressure.Reading(false, SWAP_IN_LIMIT_PAGES_PER_S - 1).high)
    }

    @Test
    fun lowMemoryFlagIsPressureWhateverTheSwapRate() {
        assertTrue(MemoryPressure.Reading(true, null).high)
        assertTrue(MemoryPressure.Reading(true, 0L).high)
        assertFalse(MemoryPressure.Reading(false, null).high)
    }
}
