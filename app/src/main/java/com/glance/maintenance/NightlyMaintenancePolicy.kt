package com.glance.maintenance

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Decides when the scheduled restart, which the owner turns on, may run.
 *
 * On a 3 GB Huawei tablet with Android 8 the Mali GPU driver keeps part of the memory it takes
 * whenever the WebView rebuilds its GPU resources, for example for every photo of a slideshow
 * with a blurred backdrop. Only the end of the process gives it back; reloading pages, recreating
 * the Activity or destroying the EGL context do not. About 40 MB a day accumulate until Android
 * closes Glance for lack of memory. The restart is also what bounds how long
 * [com.glance.dashboard.IdleMaintenanceReload] can keep deferring the reload of an idle page that
 * stays on screen.
 *
 * The restart is placed where nobody is watching: shortly after the scheduled screen-off, or at a
 * configured quiet time on installations without a screen OFF window. If the screen is on at that
 * moment it still waits for the tablet to be left alone.
 */
object NightlyMaintenancePolicy {

    /** Gives the display time to go dark, and Android time to settle, after a scheduled OFF. */
    private val OFF_WINDOW_DELAY: Duration = Duration.ofMinutes(15)

    /** Without a touch or a return to the dashboard for this long, nobody is using it. */
    const val QUIET_MS = 10 * 60_000L

    /** How often a postponed restart looks again whether the tablet has been left alone. */
    const val RETRY_MS = 10 * 60_000L

    /** After this long in use the restart is skipped until the next night. */
    const val MAX_POSTPONE_MS = 3 * 60 * 60_000L

    /** A younger app has too little accumulated, and its pages are fresh. */
    const val MIN_PROCESS_UPTIME_MS = 60 * 60_000L

    /**
     * Restarts that are days apart run on the same nightly alarm; this slack keeps a process that
     * started a few hours after the previous restart from waiting a whole extra interval.
     */
    private const val INTERVAL_SLACK_MS = 6 * 60 * 60_000L

    enum class Decision { RUN, POSTPONE, GIVE_UP }

    /** The daily screen schedule; the display is off from [off] until [on]. */
    data class ScreenOffWindow(val on: LocalTime, val off: LocalTime)

    /**
     * @param offWindow the screen schedule, or null when the screen has no OFF window.
     * @return the local time of day the maintenance restart runs at.
     */
    fun runTime(offWindow: ScreenOffWindow?, quietTime: LocalTime): LocalTime {
        if (offWindow == null || offWindow.on == offWindow.off) return quietTime
        val between = Duration.between(offWindow.off, offWindow.on)
        val length = if (between.isNegative) between.plusDays(1) else between
        return offWindow.off.plus(minOf(OFF_WINDOW_DELAY, length.dividedBy(2)))
    }

    /** @return the next occurrence of [runTime] strictly after [now]. */
    fun nextRun(now: LocalDateTime, runTime: LocalTime): LocalDateTime {
        val today = now.toLocalDate().atTime(runTime)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /**
     * Decides whether the restart may interrupt what is on screen now.
     *
     * @param screenVisible false when the display is off or covered by the soft-off overlay.
     * @param dashboardResumed false while settings or another Activity covers the dashboard.
     * @param sinceLastActivityMs time since the last touch on, or return to, the dashboard.
     * @param postponedForMs time since the restart first asked to run tonight.
     */
    fun decide(
        screenVisible: Boolean,
        dashboardResumed: Boolean,
        idleScreenActive: Boolean,
        sinceLastActivityMs: Long,
        postponedForMs: Long
    ): Decision {
        if (!screenVisible) return Decision.RUN
        if (dashboardResumed && (idleScreenActive || sinceLastActivityMs >= QUIET_MS)) {
            return Decision.RUN
        }
        return if (postponedForMs >= MAX_POSTPONE_MS) Decision.GIVE_UP else Decision.POSTPONE
    }

    /**
     * The memory a restart releases has built up since the process started, whether that was the
     * previous scheduled restart, a crash or Android closing Glance; so the interval counts from
     * the process start.
     *
     * @param intervalDays restart at most once in this many days.
     */
    fun worthwhile(processUptimeMs: Long, intervalDays: Int = 1): Boolean {
        val due = if (intervalDays <= 1) MIN_PROCESS_UPTIME_MS
        else intervalDays * 24 * 60 * 60_000L - INTERVAL_SLACK_MS
        return processUptimeMs >= due
    }
}
