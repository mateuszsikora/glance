package com.glance.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MemoryTrendMonitorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var nowEpochMs = 1_792_800_000_000L

    @Before
    fun setUp() {
        context.getSharedPreferences("glance_memory_trend", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun theFirstVerdictComesAfterTheWarmUpAndHalfADay() {
        val monitor = monitor()

        feed(monitor, fromHour = 0, toHour = 12) { hour -> leaking(hour) }
        assertNull(monitor.latest())

        feed(monitor, fromHour = 12, toHour = 14) { hour -> leaking(hour) }
        val verdict = monitor.latest()!!
        assertTrue(verdict.growing)
        assertEquals(12 * HOUR, verdict.windowMs)
    }

    @Test
    fun theVerdictOutlivesTheProcess() {
        feed(monitor(), fromHour = 0, toHour = 14) { hour -> leaking(hour) }

        val nextProcess = monitor()

        assertTrue(nextProcess.latest()!!.growing)
    }

    @Test
    fun laterVerdictsComeDailyAndReplaceTheWarning() {
        val monitor = monitor()
        feed(monitor, fromHour = 0, toHour = 14) { hour -> leaking(hour) }
        val first = monitor.latest()!!

        // The page stops leaking; the warning stays until the next daily verdict.
        nowEpochMs += 1
        feed(monitor, fromHour = 14, toHour = 36) { leaking(14.0) }
        assertEquals(first, monitor.latest())

        feed(monitor, fromHour = 36, toHour = 40) { leaking(14.0) }
        val later = monitor.latest()!!
        assertFalse(later.growing)
        assertEquals(nowEpochMs, later.evaluatedAtEpochMs)
    }

    @Test
    fun aPlannedRestartJudgesTheSamplesItWouldLose() {
        val monitor = monitor()
        feed(monitor, fromHour = 0, toHour = 14) { STEADY }
        assertFalse(monitor.latest()!!.growing)

        feed(monitor, fromHour = 14, toHour = 30) { hour -> STEADY + ((hour - 14) * 3 * MIB).toLong() }
        assertFalse(monitor.latest()!!.growing)

        monitor.evaluateNow(START + 30 * HOUR)

        assertTrue(monitor.latest()!!.growing)
    }

    @Test
    fun aRestartTooSoonAfterTheStartLeavesTheLastVerdict() {
        feed(monitor(), fromHour = 0, toHour = 14) { hour -> leaking(hour) }
        val earlier = monitor().latest()

        val young = monitor()
        feed(young, fromHour = 0, toHour = 3) { STEADY }
        young.evaluateNow(START + 3 * HOUR)

        assertNotNull(earlier)
        assertEquals(earlier, young.latest())
    }

    private fun monitor() = MemoryTrendMonitor(context, START) { nowEpochMs }

    /** Samples every 5 minutes in [fromHour, toHour). */
    private fun feed(
        monitor: MemoryTrendMonitor,
        fromHour: Int,
        toHour: Int,
        nativeBytes: (hour: Double) -> Long
    ) {
        for (i in fromHour * 12 until toHour * 12) {
            val hour = i / 12.0
            monitor.onSample(START + i * 5 * 60_000L, nativeBytes(hour))
        }
    }

    private fun leaking(hour: Double): Long = STEADY + (hour * 3 * MIB).toLong()

    private companion object {
        const val MIB = 1024L * 1024L
        const val HOUR = 60 * 60_000L
        const val STEADY = 20 * MIB
        const val START = 5_000_000L
    }
}
