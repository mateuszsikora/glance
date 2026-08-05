package com.glance.update

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSummaryTest {

    @Test
    fun reportsTheRunningBuildEvenWithoutAnUpdateUrl() {
        val summary = summarize(updateUrl = "")

        assertFalse(summary.enabled)
        assertEquals("1.5 (build 5)", summary.installedVersion)
        assertNull(summary.serverState)
        assertNull(summary.lastOutcome)
        assertNull(summary.pendingVersion)
    }

    @Test
    fun sayingNothingIsBetterThanSayingStaleThingsBeforeTheFirstCheck() {
        val summary = summarize(state = UpdateCheckState())

        assertEquals("Not contacted yet", summary.serverState)
    }

    @Test
    fun distinguishesAReachableServerFromAnUnreachableOne() {
        val reachable = summarize(state = checked(minutesAgo = 3, reachable = true))
        val unreachable = summarize(state = checked(minutesAgo = 3, reachable = false))

        assertEquals("Reachable, checked 3 minutes ago", reachable.serverState)
        assertEquals("Unreachable, last tried 3 minutes ago", unreachable.serverState)
    }

    @Test
    fun agesReadAsMinutesThenHoursThenDays() {
        assertEquals("Reachable, checked just now", ageOf(seconds = 20))
        assertEquals("Reachable, checked 1 minute ago", ageOf(seconds = 90))
        assertEquals("Reachable, checked 59 minutes ago", ageOf(seconds = 59 * 60))
        assertEquals("Reachable, checked 1 hour ago", ageOf(seconds = 60 * 60))
        assertEquals("Reachable, checked 23 hours ago", ageOf(seconds = 23 * 3600))
        assertEquals("Reachable, checked 1 day ago", ageOf(seconds = 24 * 3600))
        assertEquals("Reachable, checked 9 days ago", ageOf(seconds = 9 * 24 * 3600))
    }

    @Test
    fun aClockThatJumpedBackwardsReadsAsJustNowRatherThanAsANegativeAge() {
        assertEquals("Reachable, checked just now", ageOf(seconds = -5_000))
    }

    @Test
    fun offersAPendingInstallOnlyWhileAutomaticUpdatesAreOff() {
        val offered = checked(minutesAgo = 1, reachable = true).copy(
            availableVersionCode = 6,
            availableVersionName = "1.6"
        )

        assertEquals("1.6 (build 6)", summarize(autoUpdate = false, state = offered).pendingVersion)
        // With the switch on, an offered build installs itself; anything still sitting here failed
        // rather than waiting, and advertising it as a one-click install would be a lie.
        assertNull(summarize(autoUpdate = true, state = offered).pendingVersion)
    }

    @Test
    fun doesNotOfferAnAlreadyInstalledBuild() {
        // The recorded offer outlives the installation that acted on it.
        val offered = checked(minutesAgo = 1, reachable = true).copy(
            availableVersionCode = 5,
            availableVersionName = "1.5"
        )

        assertNull(summarize(autoUpdate = false, state = offered).pendingVersion)
    }

    @Test
    fun namesABuildByItsNumberWhenTheManifestOmitsAVersionName() {
        val offered = checked(minutesAgo = 1, reachable = true).copy(availableVersionCode = 6)

        assertEquals("build 6", summarize(autoUpdate = false, state = offered).pendingVersion)
    }

    @Test
    fun keepsTheLastOutcomeOnlyWhileUpdatesAreConfigured() {
        assertEquals("Up to date (build 5)", summarize(outcome = "Up to date (build 5)").lastOutcome)
        assertNull(summarize(updateUrl = "", outcome = "Up to date (build 5)").lastOutcome)
        assertTrue(summarize(outcome = "").lastOutcome == null)
    }

    private fun ageOf(seconds: Int): String? =
        summarize(state = checked(minutesAgo = 0, reachable = true).copy(checkedAt = NOW - seconds * 1000L))
            .serverState

    private fun checked(minutesAgo: Int, reachable: Boolean) = UpdateCheckState(
        checkedAt = NOW - TimeUnit.MINUTES.toMillis(minutesAgo.toLong()),
        serverReachable = reachable
    )

    private fun summarize(
        updateUrl: String = "http://192.168.1.10:8080/glance-update.json",
        autoUpdate: Boolean = true,
        outcome: String = "",
        state: UpdateCheckState = UpdateCheckState()
    ) = UpdateSummary.of(
        updateUrl = updateUrl,
        autoUpdateEnabled = autoUpdate,
        lastOutcome = outcome,
        state = state,
        installedVersionName = "1.5",
        installedVersionCode = 5,
        now = NOW
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
