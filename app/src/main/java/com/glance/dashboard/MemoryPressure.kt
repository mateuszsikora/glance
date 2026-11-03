package com.glance.dashboard

/**
 * Whether the system is short of memory: Android's low-memory flag is set, or pages are coming
 * back from swap faster than [SWAP_IN_LIMIT_PAGES_PER_S]. The flag follows free memory only; a
 * tablet swapping to zram can thrash, with kswapd taking half a CPU, while it is still clear.
 *
 * The swap-in rate is measured between two readings, so the first one after construction covers
 * the time since then. It is unknown when `/proc/vmstat` cannot be read.
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

    private var lastSwapIns: Long? = swapIns()
    private var lastAtMs = nowMs

    fun read(nowMs: Long): Reading {
        val swapIns = swapIns()
        val previous = lastSwapIns
        val elapsedMs = nowMs - lastAtMs
        val rate = if (swapIns != null && previous != null && swapIns >= previous && elapsedMs > 0) {
            (swapIns - previous) * 1000L / elapsedMs
        } else {
            null
        }
        lastSwapIns = swapIns
        lastAtMs = nowMs
        return Reading(lowMemory = lowMemory(), swapInPagesPerSecond = rate)
    }

    companion object {
        /** About 4 MB/s with 4 KiB pages: far more than a settled tablet moves back from swap. */
        const val SWAP_IN_LIMIT_PAGES_PER_S = 1_000L

        /** @return the `pswpin` counter, or null when [vmstat] lacks it. */
        fun swapIns(vmstat: String): Long? = vmstat.lineSequence()
            .firstOrNull { it.startsWith("pswpin ") }
            ?.substringAfter(' ')
            ?.trim()
            ?.toLongOrNull()
    }
}
