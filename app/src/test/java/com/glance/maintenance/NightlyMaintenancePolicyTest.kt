package com.glance.maintenance

import com.glance.maintenance.NightlyMaintenancePolicy.Decision
import com.glance.maintenance.NightlyMaintenancePolicy.ScreenOffWindow
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightlyMaintenancePolicyTest {

    private val quietTime = LocalTime.of(3, 30)

    @Test
    fun runsShortlyAfterAnOvernightScreenOff() {
        val window = ScreenOffWindow(on = LocalTime.of(6, 0), off = LocalTime.of(23, 0))
        assertEquals(LocalTime.of(23, 15), NightlyMaintenancePolicy.runTime(window, quietTime))
    }

    @Test
    fun runsShortlyAfterADaytimeScreenOff() {
        val window = ScreenOffWindow(on = LocalTime.of(17, 0), off = LocalTime.of(8, 0))
        assertEquals(LocalTime.of(8, 15), NightlyMaintenancePolicy.runTime(window, quietTime))
    }

    @Test
    fun staysInsideAShortOffWindow() {
        val window = ScreenOffWindow(on = LocalTime.of(12, 10), off = LocalTime.of(12, 0))
        assertEquals(LocalTime.of(12, 5), NightlyMaintenancePolicy.runTime(window, quietTime))
    }

    @Test
    fun crossesMidnightWhenTheOffWindowStartsJustBeforeIt() {
        val window = ScreenOffWindow(on = LocalTime.of(6, 0), off = LocalTime.of(23, 50))
        assertEquals(LocalTime.of(0, 5), NightlyMaintenancePolicy.runTime(window, quietTime))
    }

    @Test
    fun usesTheQuietTimeWithoutAnOffWindow() {
        assertEquals(quietTime, NightlyMaintenancePolicy.runTime(null, quietTime))
        val alwaysOn = ScreenOffWindow(on = LocalTime.of(6, 0), off = LocalTime.of(6, 0))
        assertEquals(quietTime, NightlyMaintenancePolicy.runTime(alwaysOn, quietTime))
    }

    @Test
    fun nextRunIsTodayOrTomorrowButNeverNow() {
        val evening = LocalDateTime.of(2026, 10, 20, 22, 0)
        assertEquals(
            LocalDateTime.of(2026, 10, 20, 23, 15),
            NightlyMaintenancePolicy.nextRun(evening, LocalTime.of(23, 15))
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 21, 3, 30),
            NightlyMaintenancePolicy.nextRun(evening, quietTime)
        )
        val exactly = LocalDateTime.of(2026, 10, 21, 3, 30)
        assertEquals(
            LocalDateTime.of(2026, 10, 22, 3, 30),
            NightlyMaintenancePolicy.nextRun(exactly, quietTime)
        )
    }

    @Test
    fun runsWhenNobodyCanSeeTheScreen() {
        assertEquals(
            Decision.RUN,
            decide(screenVisible = false, dashboardResumed = false, sinceLastActivityMs = 0L)
        )
    }

    @Test
    fun runsDuringIdlePlayback() {
        assertEquals(Decision.RUN, decide(idleScreenActive = true, sinceLastActivityMs = 0L))
    }

    @Test
    fun runsOnADashboardNobodyTouchedForAWhile() {
        assertEquals(
            Decision.RUN,
            decide(sinceLastActivityMs = NightlyMaintenancePolicy.QUIET_MS)
        )
    }

    @Test
    fun postponesWhileTheTabletIsInUse() {
        assertEquals(Decision.POSTPONE, decide(sinceLastActivityMs = 60_000L))
        // Settings covering the dashboard means someone is at the tablet.
        assertEquals(
            Decision.POSTPONE,
            decide(dashboardResumed = false, idleScreenActive = true, sinceLastActivityMs = Long.MAX_VALUE)
        )
    }

    @Test
    fun givesUpForTheNightAfterBeingPostponedForHours() {
        assertEquals(
            Decision.GIVE_UP,
            decide(sinceLastActivityMs = 0L, postponedForMs = NightlyMaintenancePolicy.MAX_POSTPONE_MS)
        )
    }

    @Test
    fun aFreshAppIsLeftAlone() {
        assertFalse(NightlyMaintenancePolicy.worthwhile(5 * 60_000L))
        assertTrue(
            NightlyMaintenancePolicy.worthwhile(NightlyMaintenancePolicy.MIN_PROCESS_UPTIME_MS)
        )
    }

    @Test
    fun longerIntervalsCountFromTheProcessStartWithSomeSlack() {
        val hour = 60 * 60_000L
        // Restarted two nights ago by the schedule: due again on the second night.
        assertFalse(NightlyMaintenancePolicy.worthwhile(24 * hour, intervalDays = 2))
        assertTrue(NightlyMaintenancePolicy.worthwhile(48 * hour, intervalDays = 2))
        // Closed by Android in the afternoon: a few hours short of two days still counts.
        assertTrue(NightlyMaintenancePolicy.worthwhile(43 * hour, intervalDays = 2))
        assertFalse(NightlyMaintenancePolicy.worthwhile(6 * 24 * hour, intervalDays = 7))
        assertTrue(NightlyMaintenancePolicy.worthwhile(7 * 24 * hour, intervalDays = 7))
    }

    private fun decide(
        screenVisible: Boolean = true,
        dashboardResumed: Boolean = true,
        idleScreenActive: Boolean = false,
        sinceLastActivityMs: Long,
        postponedForMs: Long = 0L
    ) = NightlyMaintenancePolicy.decide(
        screenVisible = screenVisible,
        dashboardResumed = dashboardResumed,
        idleScreenActive = idleScreenActive,
        sinceLastActivityMs = sinceLastActivityMs,
        postponedForMs = postponedForMs
    )
}
