package com.glance.diagnostics

/**
 * Tells whether Glance's native heap keeps growing, from samples taken during one process.
 *
 * On some tablets the WebView keeps a few kilobytes of native memory every time a page draws an
 * image again, for example a slideshow on the idle screen, and frees it only when the process
 * ends. Over days that grows to hundreds of megabytes until Android kills the app. The allocated
 * native heap is the figure that shows it: it does not move with swapping, unlike PSS.
 *
 * Single samples jump by a few megabytes with whatever is being drawn at that moment, so the
 * trend is read from hourly minimums. The first hour of a process is left out, because loading
 * the pages fills caches that then stay at a steady size.
 */
object MemoryTrend {

    /** @param elapsedMs [android.os.SystemClock.elapsedRealtime] when the sample was taken. */
    data class Sample(val elapsedMs: Long, val nativeBytes: Long)

    data class Verdict(
        val evaluatedAtEpochMs: Long,
        val windowMs: Long,
        val growthBytes: Long,
        val growing: Boolean
    )

    /** Pages loading after a process start; their caches grow once and then stay. */
    const val WARM_UP_MS = 60 * 60_000L

    /** Less than this tells too little about a trend that builds up over a day. */
    const val MIN_WINDOW_MS = 12 * 60 * 60_000L

    /** The newest day is enough to see a leak, and keeps the history small. */
    const val MAX_WINDOW_MS = 24 * 60 * 60_000L

    /**
     * A page without a leak stays within a few megabytes. The leak this guards against added about
     * 40 MB a day on a 3 GB tablet.
     */
    const val GROWTH_THRESHOLD_BYTES = 20L * 1024L * 1024L

    private const val BUCKET_MS = 60 * 60_000L
    private const val EDGE_BUCKETS = 3

    /**
     * @param samples one process's samples, oldest first.
     * @param processStartElapsedMs when the process started, on the samples' clock.
     * @return null while the samples after the warm-up span less than [MIN_WINDOW_MS].
     */
    fun evaluate(
        samples: List<Sample>,
        processStartElapsedMs: Long,
        nowEpochMs: Long
    ): Verdict? {
        val settled = samples.filter { it.elapsedMs >= processStartElapsedMs + WARM_UP_MS }
        if (settled.isEmpty()) return null
        val windowStart = maxOf(settled.first().elapsedMs, settled.last().elapsedMs - MAX_WINDOW_MS)
        val window = settled.filter { it.elapsedMs >= windowStart }
        val windowMs = window.last().elapsedMs - window.first().elapsedMs
        if (windowMs < MIN_WINDOW_MS) return null

        val floors = window
            .groupBy { (it.elapsedMs - windowStart) / BUCKET_MS }
            .toSortedMap()
            .values
            .map { bucket -> bucket.minOf(Sample::nativeBytes) }
        val growth = median(floors.takeLast(EDGE_BUCKETS)) - median(floors.take(EDGE_BUCKETS))
        return Verdict(
            evaluatedAtEpochMs = nowEpochMs,
            windowMs = windowMs,
            growthBytes = growth,
            growing = growth >= GROWTH_THRESHOLD_BYTES
        )
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
