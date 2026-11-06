package com.glance.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class KernelMemoryMonitorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var unreclaimableKb: Long? = 300L * 1024L
    private var bootCount = 7
    private var nowMs = 0L

    @Before
    fun setUp() {
        context.getSharedPreferences("glance_kernel_memory", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun readsTheCurrentLevelAndUptime() {
        nowMs = 236 * DAY + 3 * HOUR
        unreclaimableKb = 1_542_224L

        val status = monitor().status()!!

        assertEquals(1_542_224L * 1024L, status.unreclaimableBytes)
        assertEquals(TOTAL_KB * 1024L, status.totalBytes)
        assertEquals(nowMs, status.uptimeMs)
        assertNull(status.growth)
        assertEquals(KernelMemoryTrend.Advice.RebootNow, status.advice)
    }

    @Test
    fun aDailyPaceAppearsAfterThreeDays() {
        val monitor = monitor()

        feed(monitor, fromHour = 0, toHour = 3 * 24) { hour -> leaking(hour) }
        assertNull(monitor.status()!!.growth)

        feed(monitor, fromHour = 3 * 24, toHour = 3 * 24 + 2) { hour -> leaking(hour) }
        val growth = monitor.status()!!.growth!!
        assertEquals(3, growth.days)
        assertEquals(6.0, growth.bytesPerDay / MIB, 0.01)
    }

    @Test
    fun theHistoryOutlivesAGlanceRestart() {
        feed(monitor(), fromHour = 0, toHour = 3 * 24 + 1) { hour -> leaking(hour) }

        val restarted = monitor()

        // Before its first sample too; only the old process's running hour is lost.
        assertEquals(3, restarted.status()!!.growth!!.days)
        feed(restarted, fromHour = 3 * 24 + 1, toHour = 4 * 24 + 1) { hour -> leaking(hour) }
        assertEquals(4, restarted.status()!!.growth!!.days)
    }

    @Test
    fun aRebootStartsTheHistoryOver() {
        feed(monitor(), fromHour = 0, toHour = 4 * 24) { hour -> leaking(hour) }

        bootCount = 8
        nowMs = 5 * HOUR
        val afterBoot = monitor()

        assertNull(afterBoot.status()!!.growth)
        feed(afterBoot, fromHour = 0, toHour = 3 * 24 + 1) { STEADY }
        assertEquals(0L, afterBoot.status()!!.growth!!.bytesPerDay)
    }

    @Test
    fun withoutABootCountTheUptimeTellsAReboot() {
        bootCount = -1
        feed(monitor(), fromHour = 0, toHour = 4 * 24) { hour -> leaking(hour) }

        nowMs = 5 * HOUR

        assertNull(monitor().status()!!.growth)
    }

    @Test
    fun aTabletWithoutTheFigureGivesNoStatus() {
        unreclaimableKb = null
        val monitor = monitor()

        feed(monitor, fromHour = 0, toHour = 4 * 24) { 0L }

        assertNull(monitor.status())
        assertNull(KernelMemoryMonitor(context, readMeminfo = { error("denied") }).status())
    }

    private fun monitor() = KernelMemoryMonitor(
        context,
        readMeminfo = {
            "MemTotal:        $TOTAL_KB kB\n" +
                (unreclaimableKb?.let { "SUnreclaim:      $it kB\n" } ?: "")
        },
        bootCount = { bootCount },
        elapsedRealtime = { nowMs }
    )

    /** Samples every 5 minutes from 6:00 to 23:00 of each day in [fromHour, toHour). */
    private fun feed(
        monitor: KernelMemoryMonitor,
        fromHour: Int,
        toHour: Int,
        kbAt: (hour: Double) -> Long
    ) {
        for (i in fromHour * 12 until toHour * 12) {
            val hour = i / 12.0
            nowMs = (hour * HOUR).toLong()
            if (hour % 24 < 6) continue
            if (unreclaimableKb != null) unreclaimableKb = kbAt(hour)
            monitor.onSample(nowMs)
        }
    }

    /** 6 MB a day above [STEADY]. */
    private fun leaking(hour: Double): Long = STEADY + (hour / 24 * 6 * 1024).toLong()

    private companion object {
        const val MIB = 1024.0 * 1024.0
        const val HOUR = 60 * 60_000L
        const val DAY = 24 * HOUR
        const val TOTAL_KB = 2_814_028L
        const val STEADY = 300L * 1024L
    }
}
