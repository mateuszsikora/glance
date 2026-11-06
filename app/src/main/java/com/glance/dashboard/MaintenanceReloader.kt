package com.glance.dashboard

import com.glance.diagnostics.Diagnostics

/**
 * Runs the Activity's maintenance reloads: every dashboard page right away, and the idle page as
 * [IdleMaintenanceReload] allows, so a reload deferred during idle playback runs once after the
 * idle screen is dismissed. Generic over the page type so the rules can be tested without
 * WebViews. Main-thread API.
 *
 * @param pages every page the Activity holds, the idle page included.
 * @param postDelayed schedules a call to [onDelayElapsed]; [cancelDelayed] drops it.
 */
class MaintenanceReloader<P : Any>(
    private val pages: () -> List<P>,
    private val idlePage: () -> P?,
    private val idleScreenActive: () -> Boolean,
    private val reload: (P) -> Unit,
    private val postDelayed: (delayMs: Long) -> Unit,
    private val cancelDelayed: () -> Unit,
    private val log: (String) -> Unit
) {
    private val idleReload = IdleMaintenanceReload()

    /** @param includeIdleOnScreen also restarts idle playback; only for nightly maintenance. */
    fun reloadAll(includeIdleOnScreen: Boolean = false) {
        val idle = idlePage()
        val dashboards = pages().filter { it !== idle }
        Diagnostics.record("Reload broadcast: reloading ${dashboards.size} dashboard page(s)")
        dashboards.forEach(reload)
        if (idle == null) return
        val idleActive = idleScreenActive()
        if (idleReload.onMaintenanceReload(idleOnScreen = idleActive && !includeIdleOnScreen)) {
            Diagnostics.record(
                if (idleActive) {
                    "Reloading the idle page on screen"
                } else {
                    "Reloading the hidden idle page"
                }
            )
            reload(idle)
        } else {
            log("Idle page reload deferred until the idle screen is dismissed")
            Diagnostics.record("Idle page reload deferred until the idle screen is dismissed")
        }
    }

    fun onIdleShown() {
        cancelDelayed()
        idleReload.onIdleShown()
    }

    /**
     * Call on every dismissal path, also when the idle screen is already hidden, so screen-off
     * or pause runs a touch dismissal's delayed reload.
     *
     * @param byTouch false for the screen turning off or the Activity pausing.
     */
    fun onIdleHidden(byTouch: Boolean) {
        when (idleReload.onIdleHidden(byTouch)) {
            IdleMaintenanceReload.Action.NONE -> Unit
            IdleMaintenanceReload.Action.RELOAD_NOW -> {
                cancelDelayed()
                reloadDeferredIdlePage()
            }
            IdleMaintenanceReload.Action.RELOAD_AFTER_DELAY -> {
                // A stale callback left by a settled request must not cut this delay short.
                cancelDelayed()
                val delayMs = IdleMaintenanceReload.TOUCH_DISMISS_DELAY_MS
                postDelayed(delayMs)
                val message =
                    "Idle page reload deferred during playback runs in ${delayMs / 1000} s"
                log(message)
                Diagnostics.record(message)
            }
        }
    }

    fun onDelayElapsed() {
        if (idleReload.onDelayElapsed()) reloadDeferredIdlePage()
    }

    /** Dashboards always; the idle page only while it is on screen. */
    fun isReloadable(page: P): Boolean = page !== idlePage() || idleScreenActive()

    private fun reloadDeferredIdlePage() {
        log("Running the idle page reload deferred during playback")
        Diagnostics.record("Running the idle page reload deferred during playback")
        idlePage()?.let(reload)
    }
}
