package com.glance.diagnostics

import com.glance.diagnostics.KernelMemoryTrend.Advice
import com.glance.diagnostics.KernelMemoryTrend.Floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KernelMemoryTrendTest {

    @Test
    fun aSteadyLeakShowsItsDailyPace() {
        // 6 MB a day, the pace at which the 3 GB test tablet reached 1.47 GB in 236 days.
        val growth = KernelMemoryTrend.dailyGrowth(screenOnHours(days = 7) { day -> BASE + day * 6 * MIB_KB }, now(day = 7))!!

        assertEquals(6.0, growth.bytesPerDay / MIB.toDouble(), 0.01)
        assertEquals(7, growth.days)
    }

    @Test
    fun theHoursOfADayAddUpToItsMinimum() {
        // Each day swings by 40 MB with what the system does, above a floor that rises 2 MB a day.
        val floors = screenOnHours(days = 5) { day -> BASE + day * 2 * MIB_KB }
            .map { if (it.hour % 3 == 0L) it else it.copy(kb = it.kb + 40 * MIB_KB) }

        val growth = KernelMemoryTrend.dailyGrowth(floors, now(day = 5))!!

        assertEquals(2.0, growth.bytesPerDay / MIB.toDouble(), 0.01)
    }

    @Test
    fun oneOddDayDoesNotMoveThePace() {
        val floors = screenOnHours(days = 6) { day ->
            BASE + day * 3 * MIB_KB + if (day == 2L) 80 * MIB_KB else 0L
        }

        val growth = KernelMemoryTrend.dailyGrowth(floors, now(day = 6))!!

        assertEquals(3.0, growth.bytesPerDay / MIB.toDouble(), 0.01)
    }

    @Test
    fun aStableKernelHasNoPace() {
        val growth = KernelMemoryTrend.dailyGrowth(screenOnHours(days = 4) { BASE }, now(day = 4))!!

        assertEquals(0L, growth.bytesPerDay)
        assertEquals(Advice.None(null), status(BASE * KIB, growth).advice)
    }

    @Test
    fun itNeedsThreeFullDaysSinceBoot() {
        val floors = screenOnHours(days = 3) { day -> BASE + day * 6 * MIB_KB }

        // The third day is still running, so its minimum is not final.
        assertNull(KernelMemoryTrend.dailyGrowth(floors, now(day = 2)))
        assertEquals(3, KernelMemoryTrend.dailyGrowth(floors, now(day = 3))!!.days)
        assertNull(KernelMemoryTrend.dailyGrowth(emptyList(), now(day = 3)))
    }

    @Test
    fun daysWithOnlyAFewHoursOfSamplesAreSkipped() {
        // Day 1 had the display on for 4 hours only.
        val floors = screenOnHours(days = 4) { day -> BASE + day * 6 * MIB_KB }
            .filter { it.hour / 24 != 1L || it.hour % 24 in 8L..11L }

        assertEquals(3, KernelMemoryTrend.dailyGrowth(floors, now(day = 4))!!.days)
        assertNull(KernelMemoryTrend.dailyGrowth(floors.filter { it.hour / 24 != 3L }, now(day = 4)))
    }

    @Test
    fun theFirstHourAfterBootIsLeftOut() {
        // Drivers and services still settle: hour 0 reads far lower than the rest of the day.
        val floors = screenOnHours(days = 3) { day -> BASE + day * 6 * MIB_KB } +
            Floor(hour = 0, kb = BASE - 200 * MIB_KB)

        val growth = KernelMemoryTrend.dailyGrowth(floors, now(day = 3))!!

        assertEquals(6.0, growth.bytesPerDay / MIB.toDouble(), 0.01)
    }

    @Test
    fun onlyTheNewestWeekCounts() {
        // Fast after boot, then 1 MB a day for the last week.
        val floors = screenOnHours(days = 12) { day ->
            if (day < 5) BASE + day * 20 * MIB_KB else BASE + 100 * MIB_KB + (day - 5) * MIB_KB
        }

        val growth = KernelMemoryTrend.dailyGrowth(floors, now(day = 12))!!

        assertEquals(KernelMemoryTrend.MAX_DAYS, growth.days)
        assertEquals(1.0, growth.bytesPerDay / MIB.toDouble(), 0.01)
    }

    @Test
    fun aRebootIsDueAtTheWarningLevel() {
        // The test tablet: 1.47 GB of 2.68 GB when Android closed Glance in the foreground.
        assertEquals(Advice.RebootNow, status(unreclaimable = 1_542_224 * KIB, growth = null).advice)
        val warning = (TOTAL * KernelMemoryTrend.WARNING_SHARE).toLong()
        assertEquals(Advice.RebootNow, status(warning, growth = null).advice)
        assertEquals(Advice.None(null), status(warning - 1, growth = null).advice)
    }

    @Test
    fun aRebootIsSuggestedOnceTheWarningLevelIsAMonthAway() {
        val warning = (TOTAL * KernelMemoryTrend.WARNING_SHARE).toLong()
        val sixPerDay = KernelMemoryTrend.Growth(6 * MIB, days = 7)

        assertEquals(Advice.RebootSoon(30), status(warning - 180 * MIB, sixPerDay).advice)
        assertEquals(Advice.RebootSoon(0), status(warning - MIB, sixPerDay).advice)
        assertEquals(Advice.None(100), status(warning - 600 * MIB, sixPerDay).advice)
        // Shrinking: nothing to project.
        assertEquals(
            Advice.None(null),
            status(warning - 600 * MIB, KernelMemoryTrend.Growth(-2 * MIB, days = 7)).advice
        )
    }

    /** Hourly minimums for the screen-on hours 6:00-23:00 of each day since boot. */
    private fun screenOnHours(days: Int, kbOnDay: (day: Long) -> Long): List<Floor> =
        (0L until days).flatMap { day -> (6L..22L).map { h -> Floor(day * 24 + h, kbOnDay(day)) } }

    private fun now(day: Int) = day * KernelMemoryTrend.DAY_MS + 7 * KernelMemoryTrend.HOUR_MS

    private fun status(unreclaimable: Long, growth: KernelMemoryTrend.Growth?) =
        KernelMemoryTrend.Status(unreclaimable, TOTAL, uptimeMs = 0L, growth = growth)

    private companion object {
        const val KIB = 1024L
        const val MIB = 1024L * 1024L
        const val MIB_KB = 1024L
        const val BASE = 300L * MIB_KB
        /** MemTotal of the 3 GB test tablet. */
        const val TOTAL = 2_814_028L * KIB
    }
}
