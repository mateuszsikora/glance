package com.glance.maintenance

import com.glance.maintenance.NightlyMaintenancePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class NightlyMaintenanceAttemptTest {

    private val calls = mutableListOf<String>()

    private fun attempt(deviceOwner: Boolean) = NightlyMaintenanceAttempt(
        deviceOwner = deviceOwner,
        restart = { displayOn -> calls += "restart(displayOn=$displayOn)" },
        reloadEveryPage = { calls += "reload" },
        reportDone = { calls += "done" }
    )

    @Test
    fun aDeviceOwnerRestartsAndKeepsTheDisplayStateForTheNextProcess() {
        assertEquals(Decision.RUN, attempt(deviceOwner = true).run(idleScreenActive = true))
        assertEquals(listOf("restart(displayOn=true)"), calls)
    }

    @Test
    fun aDeviceOwnerBehindTheSoftOffOverlayRestartsWithTheDisplayLeftOff() {
        attempt(deviceOwner = true).run(screenVisible = false)
        assertEquals(listOf("restart(displayOn=false)"), calls)
    }

    @Test
    fun withoutDeviceOwnerEveryPageIsReloadedAndTheServiceIsTold() {
        assertEquals(Decision.RUN, attempt(deviceOwner = false).run(idleScreenActive = true))
        assertEquals(listOf("reload", "done"), calls)
    }

    @Test
    fun aPostponedAttemptDoesNothingAndReportsNothing() {
        assertEquals(Decision.POSTPONE, attempt(deviceOwner = false).run(sinceLastActivityMs = 0L))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun anAbandonedAttemptDoesNothingAndReportsNothing() {
        val decision = attempt(deviceOwner = true).run(
            sinceLastActivityMs = 0L,
            postponedForMs = NightlyMaintenancePolicy.MAX_POSTPONE_MS
        )
        assertEquals(Decision.GIVE_UP, decision)
        assertEquals(emptyList<String>(), calls)
    }

    private fun NightlyMaintenanceAttempt.run(
        screenVisible: Boolean = true,
        idleScreenActive: Boolean = false,
        sinceLastActivityMs: Long = Long.MAX_VALUE,
        postponedForMs: Long = 0L
    ) = run(
        screenVisible = screenVisible,
        dashboardResumed = true,
        idleScreenActive = idleScreenActive,
        sinceLastActivityMs = sinceLastActivityMs,
        postponedForMs = postponedForMs
    )
}
