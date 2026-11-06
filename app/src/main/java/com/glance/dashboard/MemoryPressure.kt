package com.glance.dashboard

import com.glance.diagnostics.MemorySample

/**
 * Whether the system is short of memory: Android's low-memory flag is set, or pages are coming
 * back from swap faster than [SWAP_IN_LIMIT_PAGES_PER_S] while less than
 * [AVAILABLE_LIMIT_BYTES] is available. The flag alone is not enough: on a 3 GB tablet it stayed
 * clear in all 5,026 journal samples over 18 days, including every low-memory kill, and through
 * a forced thrash with kswapd above half a CPU. There the swap-in rate is what tells the two
 * states apart; binder latency, main-thread lateness and the trim level did not. A fast swap-in
 * rate alone is not enough either: waking the display swaps hard for seconds even when memory is
 * plentiful.
 *
 * The swap-in rate is measured from the newest mark at least [WINDOW_MS] before the reading,
 * or the oldest one when none is that old, so that a long wait between two checks neither holds
 * on to a burst that ended long ago nor dilutes one that has just begun. A caller that reads after
 * a long pause calls [startWindow] [WINDOW_MS] before the reading; one that polls calls [mark] on
 * every poll. The rate is unknown when the swap-in counter cannot be read. Available memory is
 * read with the flag, at the end of the window.
 */
class MemoryPressure(
    private val systemMemory: () -> SystemMemory,
    /** Pages swapped in since boot; see [MemorySample.readSwapIns]. */
    private val swapIns: () -> Long?,
    nowMs: Long
) {

    /** Android's low-memory flag and available memory, from one `ActivityManager.MemoryInfo`. */
    data class SystemMemory(val lowMemory: Boolean, val availableBytes: Long)

    data class Reading(
        val lowMemory: Boolean,
        val availableBytes: Long,
        val swapInPagesPerSecond: Long?
    ) {
        val high: Boolean
            get() = lowMemory || (
                availableBytes < AVAILABLE_LIMIT_BYTES &&
                    (swapInPagesPerSecond ?: 0L) >= SWAP_IN_LIMIT_PAGES_PER_S
                )

        override fun toString(): String =
            "low=${if (lowMemory) "yes" else "no"} available=${availableBytes / MIB}MB " +
                "swapIn=${swapInPagesPerSecond ?: "?"}/s"
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
        val system = systemMemory()
        return Reading(
            lowMemory = system.lowMemory,
            availableBytes = system.availableBytes,
            swapInPagesPerSecond = rate
        )
    }

    /** Keeps the newest mark at least [WINDOW_MS] old and everything after it. */
    private fun dropOutdatedMarks(nowMs: Long) {
        while (marks.size > 1 && nowMs - marks[1].first >= WINDOW_MS) marks.removeFirst()
    }

    companion object {
        private const val MIB = 1024L * 1024L

        /**
         * About 4 MB/s with 4 KiB pages. On the 3 GB test tablet the five-minute average stayed
         * under 100 pages a second on most of a normal day and reached 9,000-25,000 during a forced
         * thrash. Normal use can pass the limit too: waking the display moved about 35,000 for a
         * second or two, and one night's burst averaged 5,375 with over 1.5 GB available. With
         * that much memory available it does not count; see [AVAILABLE_LIMIT_BYTES].
         */
        const val SWAP_IN_LIMIT_PAGES_PER_S = 1_000L

        /**
         * Below this, a fast swap-in rate counts as pressure. On the 3 GB test tablet every
         * reading of 1,000 pages a second or more during a forced thrash came with at most 422 MB
         * available, and every one without a thrash, such as waking the display after a nightly
         * restart, with at least 1,424 MB. The low-memory kills before the swap-in rate was
         * recorded happened with 286-396 MB available. Before the reboot that released the
         * kernel's unreclaimable memory, available memory never passed 519 MB in 4,164 samples
         * over 13 days, so a thrash in that state is still caught.
         *
         * The figure suits a 3 GB device. With much less RAM, available memory stays below it and
         * the swap-in rate decides alone, as before; with much more, the low-memory killer's
         * thresholds grow and a thrash could start above it.
         */
        const val AVAILABLE_LIMIT_BYTES = 800L * MIB

        const val WINDOW_MS = 1_000L
    }
}
