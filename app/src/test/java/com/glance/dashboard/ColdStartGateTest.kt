package com.glance.dashboard

import com.glance.dashboard.ColdStartGate.Companion.MAX_MEMORY_WAIT_MS
import com.glance.dashboard.ColdStartGate.Companion.START_WORK_POLL_MS
import com.glance.dashboard.ColdStartGate.Companion.START_WORK_TIMEOUT_MS
import com.glance.dashboard.ColdStartGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ColdStartGateTest {

    private val start = 1_000_000L

    private fun ColdStartGate.at(
        offsetMs: Long,
        started: Boolean = true,
        settled: Boolean = true,
        pressure: Boolean = false
    ): Decision = check(start + offsetMs, started, settled) { pressure }

    @Test
    fun opensOnceTheStartWorkHasRunOnAnUnstrainedSystem() {
        assertEquals(Decision.Open(gaveUp = false), ColdStartGate(start).at(1_000))
    }

    @Test
    fun pollsForTheStartWorkWithoutAskingAboutMemory() {
        val gate = ColdStartGate(start)
        var memoryAsked = false

        val decision = gate.check(start + 1_000, true, false) { memoryAsked = true; false }

        assertEquals(Decision.WaitForStartWork(START_WORK_POLL_MS), decision)
        assertFalse(memoryAsked)
    }

    @Test
    fun stopsWaitingForStartWorkThatNeverRuns() {
        val gate = ColdStartGate(start)
        gate.at(START_WORK_TIMEOUT_MS - 1, settled = false)

        assertEquals(Decision.Open(gaveUp = true), gate.at(START_WORK_TIMEOUT_MS, settled = false))
    }

    @Test
    fun startWorkThatTimedOutStillWaitsForMemory() {
        assertEquals(
            Decision.WaitForMemory(2_000L),
            ColdStartGate(start).at(START_WORK_TIMEOUT_MS, settled = false, pressure = true)
        )
    }

    @Test
    fun newActivityWaitsForItsOwnStartWork() {
        val gate = ColdStartGate(start)
        gate.at(START_WORK_TIMEOUT_MS, settled = false, pressure = true)

        gate.onLaunch(start + 20_000)

        assertEquals(
            Decision.WaitForStartWork(START_WORK_POLL_MS),
            gate.at(20_200, settled = false)
        )
    }

    @Test
    fun backsOffWhileMemoryIsShort() {
        val gate = ColdStartGate(start)
        var offset = 1_000L
        val delays = List(7) {
            val decision = gate.at(offset, pressure = true) as Decision.WaitForMemory
            offset += decision.delayMs
            decision.delayMs
        }

        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            delays
        )
    }

    @Test
    fun opensWhenMemoryRecovers() {
        val gate = ColdStartGate(start)
        gate.at(1_000, pressure = true)

        assertEquals(Decision.Open(gaveUp = false), gate.at(3_000))
    }

    @Test
    fun lastMemoryWaitEndsAtTheLimit() {
        assertEquals(
            Decision.WaitForMemory(1_000L),
            ColdStartGate(start).at(MAX_MEMORY_WAIT_MS - 1_000L, pressure = true)
        )
    }

    @Test
    fun opensAnywayOnceMemoryStaysShortTooLong() {
        assertEquals(
            Decision.Open(gaveUp = true),
            ColdStartGate(start).at(MAX_MEMORY_WAIT_MS, pressure = true)
        )
    }

    @Test
    fun recreatedActivityKeepsTheTimeLimit() {
        val gate = ColdStartGate(start)
        gate.at(1_000, pressure = true)

        // MainActivity does not report the old Activity's onStop as a stop during a recreation.
        gate.onLaunch(start + MAX_MEMORY_WAIT_MS - 1_000)

        assertEquals(
            Decision.Open(gaveUp = true),
            gate.at(MAX_MEMORY_WAIT_MS, pressure = true)
        )
    }

    @Test
    fun stoppedActivityWaitsWithoutAskingAboutMemory() {
        val gate = ColdStartGate(start)
        var memoryAsked = false

        val decision = gate.check(start + 1_000, false, true) { memoryAsked = true; false }

        assertEquals(Decision.WaitForStart, decision)
        assertFalse(memoryAsked)
    }

    @Test
    fun stopBetweenTwoChecksAlsoRestartsTheTimeLimit() {
        val gate = ColdStartGate(start)
        gate.at(1_000, pressure = true)

        // Stopped and started again before any check ran.
        gate.onStopped()

        assertEquals(
            Decision.WaitForMemory(2_000L),
            gate.at(MAX_MEMORY_WAIT_MS - 500L, pressure = true)
        )
    }

    @Test
    fun timeLimitStartsOverOnceAStoppedActivityStartsAgain() {
        val gate = ColdStartGate(start)
        gate.at(1_000, pressure = true)
        gate.at(3_000, started = false)

        val later = 2 * MAX_MEMORY_WAIT_MS
        assertEquals(Decision.WaitForMemory(2_000L), gate.at(later, pressure = true))
        assertEquals(
            Decision.Open(gaveUp = true),
            gate.at(later + MAX_MEMORY_WAIT_MS, pressure = true)
        )
    }
}
