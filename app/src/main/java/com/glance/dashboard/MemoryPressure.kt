package com.glance.dashboard

import com.glance.diagnostics.MemorySample

/**
 * Whether the system is short of memory: Android's low-memory flag is set, or pages are coming
 * back from swap faster than [SWAP_IN_LIMIT_PAGES_PER_S]. The flag alone is not enough: on a 3 GB
 * tablet it stayed clear in all 5,026 journal samples over 18 days, including every low-memory
 * kill, and through a forced thrash with kswapd above half a CPU. There the swap-in rate is what
 * tells the two states apart; binder latency, main-thread lateness and the trim level did not.
 *
 * The swap-in rate is measured from the newest mark at least [WINDOW_MS] before the reading,
 * or the oldest one when none is that old, so that a long wait between two checks neither holds
 * on to a burst that ended long ago nor dilutes one that has just begun. A caller that reads after
 * a long pause calls [startWindow] [WINDOW_MS] before the reading; one that polls calls [mark] on
 * every poll. The rate is unknown when the swap-in counter cannot be read.
 */
class MemoryPressure(
    private val lowMemory: () -> Boolean,
    /** Pages swapped in since boot; see [MemorySample.readSwapIns]. */
    private val swapIns: () -> Long?,
    nowMs: Long
) {

    data class Reading(val lowMemory: Boolean, val swapInPagesPerSecond: Long?) {
        val high: Boolean
            get() = lowMemory || (swapInPagesPerSecond ?: 0L) >= SWAP_IN_LIMIT_PAGES_PER_S

        override fun toString(): String =
            "low=${if (lowMemory) "yes" else "no"} swapIn=${swapInPagesPerSecond ?: "?"}/s"
    }

    /** When each mark was taken and the swap-in counter then, oldest first. */
    private val marks = ArrayDeque<Pair<Long, Long?>>().apply { addLast(nowMs to swapIns()) }

    /** Adds a possible start for the window the next [read] measures. */
    fun mark(nowMs: Long) {
        marks.addLast(nowMs to swapIns())
        dropOutdatedMarks(nowMs)
    }

    /** Forgets earlier marks: the next [read] measures from here. */
    fun startWindow(nowMs: Long) {
        marks.clear()
        mark(nowMs)
    }

    fun read(nowMs: Long): Reading {
        dropOutdatedMarks(nowMs)
        val (baselineAtMs, baseline) = marks.first()
        val swapIns = swapIns()
        val rate = MemorySample.swapInRate(baseline, swapIns, nowMs - baselineAtMs)
        marks.clear()
        marks.addLast(nowMs to swapIns)
        return Reading(lowMemory = lowMemory(), swapInPagesPerSecond = rate)
    }

    /** Keeps the newest mark at least [WINDOW_MS] old and everything after it. */
    private fun dropOutdatedMarks(nowMs: Long) {
        while (marks.size > 1 && nowMs - marks[1].first >= WINDOW_MS) marks.removeFirst()
    }

    companion object {
        /**
         * About 4 MB/s with 4 KiB pages. The 3 GB test tablet moved up to 190 pages a second while
         * settled and 9,000-25,000 during a forced thrash.
         */
        const val SWAP_IN_LIMIT_PAGES_PER_S = 1_000L
        const val WINDOW_MS = 1_000L
    }
}
