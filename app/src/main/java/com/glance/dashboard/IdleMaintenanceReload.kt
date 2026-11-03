package com.glance.dashboard

/**
 * Keeps maintenance reloads (periodic refresh, memory pressure, waking after a long screen-off)
 * from restarting the idle page while it is on screen. Restarting it there interrupts playback
 * with a blank page, so its reload is deferred until the idle screen is dismissed and the page
 * is out of sight. The deferral itself has no time limit; the nightly maintenance restart
 * ([com.glance.maintenance.NightlyMaintenancePolicy]) is what bounds it for an idle page that
 * stays on screen.
 *
 * A touch dismissal puts the deferred reload off for another [TOUCH_DISMISS_DELAY_MS], so the
 * user's first seconds on the dashboard do not compete with reloading the slideshow. The caller
 * runs it when [onDelayElapsed] says so; showing the idle screen again first keeps it deferred.
 * Main-thread API.
 */
class IdleMaintenanceReload {

    enum class Action { NONE, RELOAD_NOW, RELOAD_AFTER_DELAY }

    private enum class State { NONE, DEFERRED, DELAYED }

    private var state = State.NONE

    /**
     * Records a maintenance reload request.
     *
     * @return true when the idle page may be reloaded right away, false when the reload has been
     * deferred until [onIdleHidden] (and, after a touch dismissal, [onDelayElapsed]).
     */
    fun onMaintenanceReload(idleOnScreen: Boolean): Boolean {
        state = if (idleOnScreen) State.DEFERRED else State.NONE
        return !idleOnScreen
    }

    /** Playback resumes; a delayed reload that has not run yet waits for dismissal again. */
    fun onIdleShown() {
        if (state == State.DELAYED) state = State.DEFERRED
    }

    /**
     * @param byTouch true when the user dismissed the idle screen to use the dashboard, false when
     * the screen turned off or the Activity paused and nobody is looking at it.
     * @return what to do with a reload deferred during playback. Called on every dismissal path,
     * also after the idle screen is already hidden, so an unattended dismissal runs a reload that
     * is still waiting out its delay.
     */
    fun onIdleHidden(byTouch: Boolean): Action = when {
        state == State.NONE -> Action.NONE
        !byTouch -> {
            state = State.NONE
            Action.RELOAD_NOW
        }
        state == State.DEFERRED -> {
            state = State.DELAYED
            Action.RELOAD_AFTER_DELAY
        }
        // Already counting down; another touch does not postpone it further.
        else -> Action.NONE
    }

    /** @return true when the delayed reload is still due and has to run now. */
    fun onDelayElapsed(): Boolean {
        val reload = state == State.DELAYED
        if (reload) state = State.NONE
        return reload
    }

    companion object {
        const val TOUCH_DISMISS_DELAY_MS = 30_000L
    }
}
