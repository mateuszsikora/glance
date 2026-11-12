package com.glance.maintenance

import com.glance.maintenance.NightlyMaintenancePolicy.Action
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
    fun anAlarmHeldBackUntilTheScreenOnIsSkippedForTheNight() {
        val window = ScreenOffWindow(on = LocalTime.of(6, 25), off = LocalTime.of(23, 20))

        // The 23:35 alarm EMUI held back until the 06:25 screen-on.
        assertTrue(NightlyMaintenancePolicy.skipForTheNight(410 * 60_000L, window, LocalTime.of(6, 25)))
        // Late, but the room is still dark.
        assertFalse(NightlyMaintenancePolicy.skipForTheNight(60 * 60_000L, window, LocalTime.of(0, 35)))
        // Whatever made an alarm of unknown lateness come after the window, the tablet is in use.
        assertTrue(NightlyMaintenancePolicy.skipForTheNight(0L, window, LocalTime.of(7, 0)))
        assertFalse(NightlyMaintenancePolicy.skipForTheNight(0L, window, LocalTime.of(23, 35)))
    }

    @Test
    fun withoutAnOffWindowOnlyHoursOfDelaySkipTheNight() {
        val max = NightlyMaintenancePolicy.MAX_POSTPONE_MS
        val noon = LocalTime.NOON

        assertTrue(NightlyMaintenancePolicy.skipForTheNight(max, null, noon))
        assertFalse(NightlyMaintenancePolicy.skipForTheNight(max - 1, null, noon))
        // Identical ON and OFF times mean always on, which has no OFF window either.
        val alwaysOn = ScreenOffWindow(on = LocalTime.of(6, 0), off = LocalTime.of(6, 0))
        assertFalse(NightlyMaintenancePolicy.skipForTheNight(0L, alwaysOn, noon))
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

    @Test
    fun rebootIntervalsCountFromBootWithTheSameSlack() {
        val hour = 60 * 60_000L
        assertFalse(NightlyMaintenancePolicy.rebootDue(6 * 24 * hour, intervalDays = 7))
        // Rebooted by the schedule a week ago, a few minutes later in the evening.
        assertTrue(NightlyMaintenancePolicy.rebootDue(7 * 24 * hour - 5 * 60_000L, intervalDays = 7))
        assertFalse(NightlyMaintenancePolicy.rebootDue(13 * 24 * hour, intervalDays = 14))
        assertTrue(NightlyMaintenancePolicy.rebootDue(236 * 24 * hour, intervalDays = 30))
    }

    @Test
    fun aDueRebootReplacesTheRestart() {
        val day = 24 * 60 * 60_000L
        assertEquals(Action.REBOOT, action(rebootEnabled = true, deviceUptimeMs = 8 * day))
        assertEquals(
            Action.REBOOT,
            action(restartEnabled = true, rebootEnabled = true, deviceUptimeMs = 8 * day)
        )
        // Not due yet: the restart still runs on its own interval.
        assertEquals(
            Action.RESTART,
            action(restartEnabled = true, rebootEnabled = true, deviceUptimeMs = 3 * day)
        )
        assertEquals(null, action(rebootEnabled = true, deviceUptimeMs = 3 * day))
        assertEquals(null, action(deviceUptimeMs = 8 * day))
    }

    @Test
    fun withoutDeviceOwnerOnlyTheRestartRemains() {
        val day = 24 * 60 * 60_000L
        assertEquals(null, action(rebootEnabled = true, deviceUptimeMs = 30 * day, deviceOwner = false))
        assertEquals(
            Action.RESTART,
            action(restartEnabled = true, rebootEnabled = true, deviceUptimeMs = 30 * day, deviceOwner = false)
        )
    }

    private fun action(
        restartEnabled: Boolean = false,
        rebootEnabled: Boolean = false,
        deviceUptimeMs: Long,
        deviceOwner: Boolean = true
    ) = NightlyMaintenancePolicy.action(
        restartEnabled = restartEnabled,
        restartIntervalDays = 1,
        processUptimeMs = 2 * 60 * 60_000L,
        rebootEnabled = rebootEnabled,
        rebootIntervalDays = 7,
        deviceUptimeMs = deviceUptimeMs,
        deviceOwner = deviceOwner
    )

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
