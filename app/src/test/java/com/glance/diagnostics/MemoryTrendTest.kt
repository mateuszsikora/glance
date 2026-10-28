package com.glance.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTrendTest {

    @Test
    fun aDayOfSlideshowLeakIsGrowing() {
        // 2 MB an hour, as an idle slideshow on the BAH2-L09 added.
        val verdict = evaluate(hours = 20) { hour -> BASE + (hour * 2 * MIB).toLong() }!!

        assertTrue(verdict.growing)
        assertEquals(19 * HOUR, verdict.windowMs)
        // From the median of the first three hourly floors to the median of the last three.
        assertEquals(34 * MIB.toDouble(), verdict.growthBytes.toDouble(), 1.0 * MIB)
        assertEquals(NOW, verdict.evaluatedAtEpochMs)
    }

    @Test
    fun aSteadyHeapWithDrawingSpikesIsStable() {
        // Every third sample catches a frame being drawn, a few megabytes above the floor.
        val verdict = evaluate(hours = 20, spikes = true) { BASE }!!

        assertFalse(verdict.growing)
        assertEquals(0L, verdict.growthBytes)
    }

    @Test
    fun theWarmUpAfterAProcessStartIsNotATrend() {
        // Loading the pages fills caches within the first hour, then the heap stays put.
        val verdict = evaluate(hours = 20) { hour ->
            if (hour < 1.0) BASE + (hour * 8 * MIB).toLong() else BASE + 8 * MIB
        }!!

        assertFalse(verdict.growing)
        assertEquals(0L, verdict.growthBytes)
    }

    @Test
    fun lessThanHalfADayAfterTheWarmUpGivesNoVerdict() {
        assertNull(evaluate(hours = 12) { hour -> BASE + (hour * 10 * MIB).toLong() })
        assertNull(MemoryTrend.evaluate(emptyList(), START, NOW))
    }

    @Test
    fun onlyTheNewestDayCounts() {
        // Leaking for a day, then stable for a whole day: the latest day shows no growth.
        val verdict = evaluate(hours = 50) { hour ->
            BASE + (minOf(hour, 25.0) * 2 * MIB).toLong()
        }!!

        assertEquals(MemoryTrend.MAX_WINDOW_MS, verdict.windowMs)
        assertFalse(verdict.growing)
    }

    @Test
    fun slowGrowthBelowTheThresholdIsStable() {
        val verdict = evaluate(hours = 20) { hour -> BASE + (hour * 0.5 * MIB).toLong() }!!

        assertFalse(verdict.growing)
        assertTrue(verdict.growthBytes in 1..MemoryTrend.GROWTH_THRESHOLD_BYTES)
    }

    @Test
    fun theSuggestedIntervalKeepsADaysGrowthUnderTheBudget() {
        fun suggest(megabytesPerDay: Long) = MemoryTrend.suggestedRestartIntervalDays(
            MemoryTrend.Verdict(NOW, 24 * HOUR, megabytesPerDay * MIB, growing = true)
        )
        assertEquals(1, suggest(80))
        assertEquals(2, suggest(45))
        assertEquals(3, suggest(30))
        assertEquals(7, suggest(14))
        // Faster than the budget allows even daily: daily is still the most that is offered.
        assertEquals(1, suggest(300))
        assertNull(
            MemoryTrend.suggestedRestartIntervalDays(MemoryTrend.Verdict(NOW, 24 * HOUR, 2 * MIB, false))
        )
    }

    /** Samples every 5 minutes from the process start for [hours]. */
    private fun evaluate(
        hours: Int,
        spikes: Boolean = false,
        nativeBytes: (hour: Double) -> Long
    ): MemoryTrend.Verdict? {
        val samples = (0..hours * 12).map { i ->
            val elapsed = START + i * 5 * 60_000L
            val spike = if (spikes && i % 3 == 2) 3 * MIB else 0L
            MemoryTrend.Sample(elapsed, nativeBytes((elapsed - START) / HOUR.toDouble()) + spike)
        }
        return MemoryTrend.evaluate(samples, START, NOW)
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val HOUR = 60 * 60_000L
        const val BASE = 20 * MIB
        const val START = 1_000_000L
        const val NOW = 1_792_800_000_000L
    }
}
