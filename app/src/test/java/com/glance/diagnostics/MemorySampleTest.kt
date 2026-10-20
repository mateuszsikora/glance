package com.glance.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MemorySampleTest {

    @Test
    fun readsSwapFromProcMeminfo() {
        val meminfo = """
            MemTotal:        2814028 kB
            MemAvailable:     405504 kB
            SwapTotal:        524284 kB
            SwapFree:         450556 kB
        """.trimIndent()

        assertEquals(524284L to 450556L, MemorySample.swapKb(meminfo))
    }

    @Test
    fun missingSwapFieldsYieldNothing() {
        assertNull(MemorySample.swapKb("MemTotal:        2814028 kB\n"))
    }

    @Test
    fun formatsOneAddressFreeLine() {
        val line = MemorySample.format(
            availableBytes = 383L * MIB,
            totalBytes = 2748L * MIB,
            thresholdBytes = 216L * MIB,
            lowMemory = false,
            swapTotalKb = 512L * 1024L,
            swapFreeKb = 440L * 1024L,
            appPssKb = 125L * 1024L,
            javaHeapKb = 8L * 1024L,
            nativeHeapKb = 30L * 1024L,
            graphicsKb = 88L * 1024L
        )

        assertEquals(
            "system available=383/2748MB threshold=216MB low=no swapUsed=72/512MB; " +
                "app pss=125MB java=8MB native=30MB graphics=88MB",
            line
        )
    }

    @Test
    fun omitsSwapAndBreakdownWhenUnavailable() {
        val line = MemorySample.format(
            availableBytes = 100L * MIB,
            totalBytes = 2748L * MIB,
            thresholdBytes = 216L * MIB,
            lowMemory = true,
            swapTotalKb = 0L,
            swapFreeKb = 0L,
            appPssKb = 50L * 1024L,
            javaHeapKb = null,
            nativeHeapKb = null,
            graphicsKb = null
        )

        assertEquals("system available=100/2748MB threshold=216MB low=yes; app pss=50MB", line)
    }

    @Test
    fun capturesWithoutFailingOnTheDevice() {
        val line = MemorySample.capture(ApplicationProvider.getApplicationContext<Context>())

        assertTrue(line.startsWith("system available="))
        assertTrue(line.contains("; app pss="))
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
