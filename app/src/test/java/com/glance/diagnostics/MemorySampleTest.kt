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
    fun readsTheKernelsUnreclaimableMemoryFromProcMeminfo() {
        val meminfo = """
            MemTotal:        2814028 kB
            MemAvailable:     392148 kB
            Slab:            1612344 kB
            SReclaimable:      70120 kB
            SUnreclaim:      1542224 kB
        """.trimIndent()

        assertEquals(1542224L, MemorySample.meminfoKb(meminfo, "SUnreclaim"))
        assertEquals(2814028L, MemorySample.meminfoKb(meminfo, "MemTotal"))
    }

    @Test
    fun missingOrMalformedMeminfoFieldsYieldNothing() {
        // A prefix of another field's name must not match it.
        assertNull(MemorySample.meminfoKb("SUnreclaimable:   1234 kB\n", "SUnreclaim"))
        assertNull(MemorySample.meminfoKb("MemTotal:        2814028 kB\n", "SUnreclaim"))
        assertNull(MemorySample.meminfoKb("SUnreclaim:\n", "SUnreclaim"))
        assertNull(MemorySample.meminfoKb("SUnreclaim:      lots kB\n", "SUnreclaim"))
        assertNull(MemorySample.meminfoKb("", "SUnreclaim"))
    }

    @Test
    fun readsTheSwapInCounterFromProcVmstat() {
        val vmstat = "nr_free_pages 12345\npswpout 777\npswpin 4321\npgmajfault 99\n"

        assertEquals(4321L, MemorySample.swapIns(vmstat))
        assertNull(MemorySample.swapIns("nr_free_pages 12345\npswpout 777\n"))
    }

    @Test
    fun readsSwapInsFromZramStatistics() {
        val stat = "     123        0 23566192     4004  3779348        0 30234784    51200" +
            "        0    20500    55204\n"

        assertEquals(2945774L, MemorySample.zramSwapIns(stat))
        assertNull(MemorySample.zramSwapIns(""))
    }

    @Test
    fun swapInRateIsPagesPerSecondBetweenTwoReadings() {
        assertEquals(1_500L, MemorySample.swapInRate(10_000L, 13_000L, elapsedMs = 2_000L))
        assertNull(MemorySample.swapInRate(null, 13_000L, elapsedMs = 2_000L))
        assertNull(MemorySample.swapInRate(13_000L, 100L, elapsedMs = 2_000L))
        assertNull(MemorySample.swapInRate(100L, 200L, elapsedMs = 0L))
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
            swapInPagesPerSecond = 37L,
            kernelUnreclaimableKb = 1506L * 1024L,
            appPssKb = 125L * 1024L,
            javaHeapKb = 8L * 1024L,
            nativeHeapKb = 30L * 1024L,
            graphicsKb = 88L * 1024L,
            nativeAllocatedBytes = 160L * MIB
        )

        assertEquals(
            "system available=383/2748MB threshold=216MB low=no swapUsed=72/512MB swapIn=37/s " +
                "slabUnreclaim=1506MB; " +
                "app pss=125MB java=8MB native=30MB graphics=88MB nativeAlloc=160MB",
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
        assertTrue(line.contains(" nativeAlloc="))
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
