package com.glance.dashboard

import com.glance.diagnostics.MemorySample

/**
 * Whether the system is short of memory: Android's low-memory flag is set, or pages are coming
 * back from swap faster than [SWAP_IN_LIMIT_PAGES_PER_S]. The flag follows free memory only; a
 * tablet swapping to zram can thrash, with kswapd taking half a CPU, while it is still clear.
 *
 * The swap-in rate is the average since the last [mark] or [read], or since construction. A
 * caller marks [WINDOW_MS] before it reads, so that a long wait between two checks neither
 * holds on to a burst that ended long ago nor dilutes one that has just begun. The rate is
 * unknown when the `pswpin` counter cannot be read.
 */
class MemoryPressure(
    private val lowMemory: () -> Boolean,
    /** The `pswpin` counter of `/proc/vmstat`: pages swapped in since boot. */
    private val swapIns: () -> Long?,
    nowMs: Long
) {

    data class Reading(val lowMemory: Boolean, val swapInPagesPerSecond: Long?) {
        val high: Boolean
            get() = lowMemory || (swapInPagesPerSecond ?: 0L) >= SWAP_IN_LIMIT_PAGES_PER_S

        override fun toString(): String =
            "low=${if (lowMemory) "yes" else "no"} swapIn=${swapInPagesPerSecond ?: "?"}/s"
    }

    private var baseline: Long? = swapIns()
    private var baselineAtMs = nowMs

    /** Starts the window the next [read] measures. */
    fun mark(nowMs: Long) {
        baseline = swapIns()
        baselineAtMs = nowMs
    }

    fun read(nowMs: Long): Reading {
        val swapIns = swapIns()
        val rate = MemorySample.swapInRate(baseline, swapIns, nowMs - baselineAtMs)
        baseline = swapIns
        baselineAtMs = nowMs
        return Reading(lowMemory = lowMemory(), swapInPagesPerSecond = rate)
    }

    companion object {
        /** About 4 MB/s with 4 KiB pages: far more than a settled tablet moves back from swap. */
        const val SWAP_IN_LIMIT_PAGES_PER_S = 1_000L
        const val WINDOW_MS = 1_000L
    }
}
