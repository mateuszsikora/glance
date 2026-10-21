package com.glance.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.io.File

/**
 * One-line memory summary for the diagnostic journal: what the system has left, how much swap is
 * in use, and Glance's own footprint.
 *
 * The WebView renderer runs in an isolated process that an app cannot measure. Its growth shows
 * up indirectly, as falling available memory and rising swap use.
 *
 * The PSS figures count only what is resident. On a tablet that swaps to zram they fall when
 * memory pressure pushes pages out and rise when a reload touches them again, which looks like
 * growth tied to reloads. `nativeAlloc`, the bytes the native heap has handed out, does not move
 * with swapping and is the figure to follow for a leak.
 */
internal object MemorySample {

    fun capture(context: Context): String {
        val system = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        val app = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val swap = runCatching { swapKb(File("/proc/meminfo").readText()) }.getOrNull()
        return format(
            availableBytes = system.availMem,
            totalBytes = system.totalMem,
            thresholdBytes = system.threshold,
            lowMemory = system.lowMemory,
            swapTotalKb = swap?.first,
            swapFreeKb = swap?.second,
            appPssKb = app.totalPss.toLong(),
            javaHeapKb = app.getMemoryStat("summary.java-heap")?.toLongOrNull(),
            nativeHeapKb = app.getMemoryStat("summary.native-heap")?.toLongOrNull(),
            graphicsKb = app.getMemoryStat("summary.graphics")?.toLongOrNull(),
            nativeAllocatedBytes = Debug.getNativeHeapAllocatedSize()
        )
    }

    fun format(
        availableBytes: Long,
        totalBytes: Long,
        thresholdBytes: Long,
        lowMemory: Boolean,
        swapTotalKb: Long?,
        swapFreeKb: Long?,
        appPssKb: Long,
        javaHeapKb: Long?,
        nativeHeapKb: Long?,
        graphicsKb: Long?,
        nativeAllocatedBytes: Long? = null
    ): String = buildString {
        append("system available=${availableBytes / MIB}/${totalBytes / MIB}MB")
        append(" threshold=${thresholdBytes / MIB}MB")
        append(" low=${if (lowMemory) "yes" else "no"}")
        if (swapTotalKb != null && swapFreeKb != null && swapTotalKb > 0) {
            append(" swapUsed=${(swapTotalKb - swapFreeKb) / KIB}/${swapTotalKb / KIB}MB")
        }
        append("; app pss=${appPssKb / KIB}MB")
        javaHeapKb?.let { append(" java=${it / KIB}MB") }
        nativeHeapKb?.let { append(" native=${it / KIB}MB") }
        graphicsKb?.let { append(" graphics=${it / KIB}MB") }
        nativeAllocatedBytes?.let { append(" nativeAlloc=${it / MIB}MB") }
    }

    /** @return SwapTotal and SwapFree in KiB, or null when `/proc/meminfo` lacks them. */
    fun swapKb(meminfo: String): Pair<Long, Long>? {
        fun field(name: String): Long? = meminfo.lineSequence()
            .firstOrNull { it.startsWith("$name:") }
            ?.substringAfter(':')
            ?.trim()
            ?.substringBefore(' ')
            ?.toLongOrNull()
        val total = field("SwapTotal") ?: return null
        val free = field("SwapFree") ?: return null
        return total to free
    }

    private const val KIB = 1024L
    private const val MIB = 1024L * 1024L
}
