package com.glance.watchdog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleDashboardPolicyTest {

    private fun policy() = StaleDashboardPolicy(
        minUnreachableMs = 15_000L,
        staleScreenOffMs = 60_000L
    )

    @Test
    fun reachableHostNeverAsksForAReload() {
        val policy = policy()
        assertFalse(policy.onProbeResult(0L, reachable = true))
        assertFalse(policy.onProbeResult(30_000L, reachable = true))
    }

    @Test
    fun requestsReloadOnceWhenHostRecoversFromAnOutage() {
        val policy = policy()
        assertFalse(policy.onProbeResult(0L, reachable = false))
        assertFalse(policy.onProbeResult(30_000L, reachable = false))
        assertTrue(policy.onProbeResult(60_000L, reachable = true))
        assertFalse(policy.onProbeResult(90_000L, reachable = true))
    }

    @Test
    fun ignoresOutagesTooShortToDropTheDashboardConnection() {
        val policy = policy()
        assertFalse(policy.onProbeResult(0L, reachable = false))
        assertFalse(policy.onProbeResult(14_999L, reachable = true))
    }

    @Test
    fun measuresTheOutageFromItsFirstFailedProbe() {
        val policy = policy()
        assertFalse(policy.onProbeResult(0L, reachable = false))
        assertFalse(policy.onProbeResult(5_000L, reachable = false))
        assertTrue(policy.onProbeResult(15_000L, reachable = true))
    }

    @Test
    fun longScreenOffWindowMarksTheDashboardStale() {
        val policy = policy()
        policy.onScreenOff(0L)
        assertTrue(policy.onScreenOn(60_000L))
    }

    @Test
    fun shortScreenOffWindowKeepsTheDashboard() {
        val policy = policy()
        policy.onScreenOff(0L)
        assertFalse(policy.onScreenOn(59_999L))
    }

    @Test
    fun screenOffIsTimedFromTheFirstDarkCheck() {
        val policy = policy()
        policy.onScreenOff(0L)
        policy.onScreenOff(30_000L)
        assertTrue(policy.onScreenOn(60_000L))
    }

    @Test
    fun screenOnWithoutAPrecedingScreenOffIsNotStale() {
        val policy = policy()
        assertFalse(policy.onScreenOn(600_000L))
    }

    @Test
    fun staleScreenOffReloadSuppressesTheRecoveringProbeReload() {
        val policy = policy()
        assertFalse(policy.onProbeResult(0L, reachable = false))
        policy.onScreenOff(30_000L)
        assertTrue(policy.onScreenOn(120_000L))
        assertFalse(policy.onProbeResult(150_000L, reachable = true))
    }

    @Test
    fun outageStillOpenAfterAShortScreenOffWindowReloadsOnRecovery() {
        val policy = policy()
        assertFalse(policy.onProbeResult(0L, reachable = false))
        policy.onScreenOff(10_000L)
        assertFalse(policy.onScreenOn(30_000L))
        assertTrue(policy.onProbeResult(60_000L, reachable = true))
    }
}
