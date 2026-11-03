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

    @Test
    fun opensOnceTheStartWorkHasRunOnAnUnstrainedSystem() {
        val gate = ColdStartGate(start)

        assertEquals(Decision.Open(gaveUp = false), gate.check(start + 200, true) { false })
    }

    @Test
    fun pollsForTheStartWorkWithoutAskingAboutMemory() {
        val gate = ColdStartGate(start)
        var memoryAsked = false

        val decision = gate.check(start + 200, false) { memoryAsked = true; false }

        assertEquals(Decision.WaitForStartWork(START_WORK_POLL_MS), decision)
        assertFalse(memoryAsked)
    }

    @Test
    fun stopsWaitingForStartWorkThatNeverRuns() {
        val gate = ColdStartGate(start)
        gate.check(start + START_WORK_TIMEOUT_MS - 1, false) { false }

        assertEquals(
            Decision.Open(gaveUp = true),
            gate.check(start + START_WORK_TIMEOUT_MS, false) { false }
        )
    }

    @Test
    fun startWorkThatTimedOutStillWaitsForMemory() {
        val gate = ColdStartGate(start)

        assertEquals(
            Decision.WaitForMemory(2_000L),
            gate.check(start + START_WORK_TIMEOUT_MS, false) { true }
        )
    }

    @Test
    fun backsOffWhileMemoryIsShort() {
        val gate = ColdStartGate(start)
        var now = start + 200
        val delays = List(7) {
            val decision = gate.check(now, true) { true } as Decision.WaitForMemory
            now += decision.delayMs
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
        gate.check(start + 200, true) { true }

        assertEquals(Decision.Open(gaveUp = false), gate.check(start + 2_200, true) { false })
    }

    @Test
    fun lastMemoryWaitEndsAtTheLimit() {
        val gate = ColdStartGate(start)

        assertEquals(
            Decision.WaitForMemory(1_000L),
            gate.check(start + MAX_MEMORY_WAIT_MS - 1_000L, true) { true }
        )
    }

    @Test
    fun opensAnywayOnceMemoryStaysShortTooLong() {
        val gate = ColdStartGate(start)

        assertEquals(
            Decision.Open(gaveUp = true),
            gate.check(start + MAX_MEMORY_WAIT_MS, true) { true }
        )
    }
}
