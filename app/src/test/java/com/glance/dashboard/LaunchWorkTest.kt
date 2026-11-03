package com.glance.dashboard

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchWorkTest {

    private val kiosk = AtomicInteger()
    private val watchdog = AtomicInteger()
    private val lockTask = AtomicInteger()

    private fun launch(token: Int) = LaunchWork(
        token = token,
        kioskTokenHandled = kiosk::get,
        watchdogTokenHandled = watchdog::get,
        lockTaskEntries = lockTask::get
    )

    @Test
    fun settlesOnceBothServicesRanThisLaunchsStart() {
        val work = launch(token = 2)

        LaunchWork.recordHandled(kiosk, 2)
        assertFalse(work.settled)
        LaunchWork.recordHandled(watchdog, 2)
        assertTrue(work.settled)
    }

    @Test
    fun earlierStartStillQueuedForAServiceDoesNotCount() {
        val work = launch(token = 2)

        // The boot receiver's start, or the service's own sticky restart.
        LaunchWork.recordHandled(kiosk, 0)
        LaunchWork.recordHandled(kiosk, 1)
        LaunchWork.recordHandled(watchdog, 2)

        assertFalse(work.settled)
    }

    @Test
    fun aLaterLaunchsStartAlsoCoversThisOne() {
        val work = launch(token = 2)

        LaunchWork.recordHandled(kiosk, 3)
        LaunchWork.recordHandled(kiosk, 2)
        LaunchWork.recordHandled(watchdog, 3)

        assertEquals(3, kiosk.get())
        assertTrue(work.settled)
    }

    @Test
    fun waitsForTheLockTaskBroadcastOnlyWhenEnteringLockTask() {
        lockTask.set(4)
        val work = launch(token = 1)
        LaunchWork.recordHandled(kiosk, 1)
        LaunchWork.recordHandled(watchdog, 1)

        work.lockTaskBroadcastExpected = true
        assertFalse(work.settled)
        lockTask.incrementAndGet()
        assertTrue(work.settled)
    }

    @Test
    fun tokensIncrease() {
        assertTrue(LaunchWork.nextToken() < LaunchWork.nextToken())
    }
}
