package com.glance.screen

/**
 * Keeps the dashboard on screen for a moment after a boot inside the OFF window, before the display
 * goes dark.
 *
 * After a scheduled reboot at night the dashboard went dark a second after the boot, never having
 * been visible, and EMUI then held back every ordinary alarm and timer of Glance until the
 * morning: no MQTT keep-alive, no journal samples. On nights without a reboot, after the dashboard
 * was on screen until the OFF time, it did not. The likely link, still a hypothesis from two
 * nights on one EMUI 8 tablet, is that EMUI treats an app never seen in front since the boot as a
 * background app; a few seconds in front after the boot are meant to give Glance that standing.
 * The journal's memory samples during the night tell whether it worked.
 *
 * One grace per process, started by its first request to turn the display off, and only when that
 * request comes right after the boot; later requests within it wait for its end. Any source counts,
 * so an OFF from Home Assistant in the first minutes after a daytime boot waits too. A process
 * that started after a daytime boot keeps the display on until the OFF time, which needs no grace.
 */
class BootScreenGrace(private val nowElapsedMs: () -> Long) {
    private var endsAtElapsedMs: Long? = null

    /** @return how long a display-off request should still wait; 0 to turn the display off now. */
    @Synchronized
    fun remainingMs(): Long {
        val now = nowElapsedMs()
        val endsAt = endsAtElapsedMs
            ?: (if (now <= BOOT_WINDOW_MS) now + VISIBLE_MS else 0L)
                .also { endsAtElapsedMs = it }
        return (endsAt - now).coerceAtLeast(0L)
    }

    /**
     * Whichever of the dashboard and KioskService holds its OFF back, Home Assistant has heard
     * OFF and keeps hearing it: a display lit only for the grace is not reported as ON.
     *
     * @return true to drop a report that the display is on; never starts a grace.
     */
    @Synchronized
    fun hidesOnReport(isOn: Boolean, requestedScreenOn: Boolean): Boolean {
        val endsAt = endsAtElapsedMs ?: return false
        return isOn && !requestedScreenOn && nowElapsedMs() < endsAt
    }

    companion object {
        /** A display-off request this soon after the boot is the one that follows it. */
        const val BOOT_WINDOW_MS = 5 * 60_000L
        const val VISIBLE_MS = 15_000L
    }
}
