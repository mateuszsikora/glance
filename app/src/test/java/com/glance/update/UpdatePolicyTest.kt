package com.glance.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatePolicyTest {

    @Test
    fun installsANewerBuildOnceTheRunningOneHasSettled() {
        assertEquals(
            UpdateDecision.Install,
            decide(offered = 10, installed = 9, uptimeMs = UpdatePolicy.MIN_UPTIME_MS)
        )
    }

    @Test
    fun ignoresSameOrOlderBuilds() {
        assertEquals(UpdateDecision.UpToDate, decide(offered = 9, installed = 9))
        assertEquals(UpdateDecision.UpToDate, decide(offered = 8, installed = 9))
    }

    @Test
    fun defersWhileTheRunningBuildIsStillYoung() {
        assertEquals(
            UpdateDecision.NotSettled,
            decide(offered = 10, installed = 9, uptimeMs = UpdatePolicy.MIN_UPTIME_MS - 1)
        )
    }

    @Test
    fun abandonsAVersionThatKeepsFailing() {
        val attempts = UpdateAttempts(versionCode = 10, count = UpdatePolicy.MAX_ATTEMPTS)

        assertEquals(
            UpdateDecision.Abandoned,
            decide(offered = 10, installed = 9, attempts = attempts)
        )
    }

    @Test
    fun anAbandonedVersionDoesNotBlockTheNextOne() {
        val attempts = UpdateAttempts(versionCode = 10, count = UpdatePolicy.MAX_ATTEMPTS)

        assertEquals(
            UpdateDecision.Install,
            decide(offered = 11, installed = 9, attempts = attempts)
        )
    }

    @Test
    fun upToDateWinsOverAnExhaustedAttemptCounter() {
        // The build finally installed: its counter is stale, not a reason to report a failure.
        val attempts = UpdateAttempts(versionCode = 10, count = UpdatePolicy.MAX_ATTEMPTS)

        assertEquals(
            UpdateDecision.UpToDate,
            decide(offered = 10, installed = 10, attempts = attempts)
        )
    }

    @Test
    fun failuresAccumulatePerVersionAndResetOnANewOne() {
        val first = UpdatePolicy.recordFailure(UpdateAttempts(0, 0), versionCode = 10)
        val second = UpdatePolicy.recordFailure(first, versionCode = 10)
        val other = UpdatePolicy.recordFailure(second, versionCode = 11)

        assertEquals(UpdateAttempts(10, 1), first)
        assertEquals(UpdateAttempts(10, 2), second)
        assertEquals(UpdateAttempts(11, 1), other)
    }

    private fun decide(
        offered: Int,
        installed: Int,
        attempts: UpdateAttempts = UpdateAttempts(0, 0),
        uptimeMs: Long = UpdatePolicy.MIN_UPTIME_MS
    ): UpdateDecision {
        return UpdatePolicy.decide(
            manifest = UpdateManifest(offered, offered.toString(), "https://host/a.apk", DIGEST),
            installedVersionCode = installed,
            attempts = attempts,
            uptimeMs = uptimeMs
        )
    }

    private companion object {
        const val DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
