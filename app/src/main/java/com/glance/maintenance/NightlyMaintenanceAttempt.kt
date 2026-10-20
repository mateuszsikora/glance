package com.glance.maintenance

import com.glance.maintenance.NightlyMaintenancePolicy.Decision

/**
 * One nightly maintenance attempt on the dashboard. The kiosk service repeats it while the tablet
 * stays in use, so a postponed or abandoned attempt reports nothing back.
 *
 * Restarting the process is the only way to release the WebView's native memory, but it needs
 * Device Owner to bring the dashboard back. Without it every page is reloaded, the idle page
 * included even on screen, so it is at least refreshed once a day.
 */
class NightlyMaintenanceAttempt(
    private val deviceOwner: Boolean,
    private val restart: (displayOn: Boolean) -> Unit,
    private val reloadEveryPage: () -> Unit,
    private val reportDone: () -> Unit
) {

    fun run(
        screenVisible: Boolean,
        dashboardResumed: Boolean,
        idleScreenActive: Boolean,
        sinceLastActivityMs: Long,
        postponedForMs: Long
    ): Decision {
        val decision = NightlyMaintenancePolicy.decide(
            screenVisible = screenVisible,
            dashboardResumed = dashboardResumed,
            idleScreenActive = idleScreenActive,
            sinceLastActivityMs = sinceLastActivityMs,
            postponedForMs = postponedForMs
        )
        if (decision == Decision.RUN) {
            if (deviceOwner) {
                restart(screenVisible)
            } else {
                reloadEveryPage()
                reportDone()
            }
        }
        return decision
    }
}
