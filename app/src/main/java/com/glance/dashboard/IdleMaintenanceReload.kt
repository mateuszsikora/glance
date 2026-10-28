package com.glance.dashboard

/**
 * Keeps maintenance reloads (periodic refresh, memory pressure, waking after a long screen-off)
 * from restarting the idle page while it is on screen. Restarting it there interrupts playback
 * with a blank page, so its reload is deferred until the idle screen is dismissed and the page
 * is out of sight. The deferral itself has no time limit; the nightly maintenance restart
 * ([com.glance.maintenance.NightlyMaintenancePolicy]) is what bounds it for an idle page that
 * stays on screen. Main-thread API.
 */
class IdleMaintenanceReload {

    private var pending = false

    /**
     * Records a maintenance reload request.
     *
     * @return true when the idle page may be reloaded right away, false when the reload has been
     * deferred until [onIdleHidden].
     */
    fun onMaintenanceReload(idleOnScreen: Boolean): Boolean {
        pending = idleOnScreen
        return !idleOnScreen
    }

    /** @return true when a deferred reload has to run now that the idle page is out of sight. */
    fun onIdleHidden(): Boolean {
        val reload = pending
        pending = false
        return reload
    }
}
