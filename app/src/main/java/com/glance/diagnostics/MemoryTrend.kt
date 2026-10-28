package com.glance.diagnostics

import com.glance.maintenance.MaintenanceSettings

/**
 * Tells whether Glance's native heap keeps growing, from samples taken during one process.
 *
 * On some tablets the GPU driver keeps a few kilobytes of native memory every time the WebView
 * rebuilds its GPU resources, for example for each photo of a slideshow on the idle screen, and
 * frees it only when the process ends. Over days that grows to hundreds of megabytes until
 * Android kills the app. The allocated native heap is the figure that shows it: it does not move
 * with swapping, unlike PSS.
 *
 * Single samples jump by a few megabytes with whatever is being drawn at that moment, so the
 * trend is read from hourly minimums. The first hour of a process is left out, because loading
 * the pages fills caches that then stay at a steady size. A leak also shows halfway through the
 * window, while a single step, such as a new dashboard filling its caches after a settings change,
 * puts the middle level next to the start or the end; only the first counts as growing.
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

    /**
     * Growth a restart should keep the heap under. On the 3 GB tablet where the leak was found,
     * Android began closing Glance once its native heap had grown by about 150 MB.
     */
    const val RESTART_BUDGET_BYTES = 100L * 1024L * 1024L

    private const val DAY_MS = 24 * 60 * 60_000L
    private const val BUCKET_MS = 60 * 60_000L
    private const val EDGE_BUCKETS = 3

    /**
     * How far from either end the middle level must be. A leak that only runs while the screen is
     * on, with nights flat, still puts the middle about a third of the way.
     */
    private const val MIDDLE_SHARE = 0.2

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
        val start = median(floors.take(EDGE_BUCKETS))
        val end = median(floors.takeLast(EDGE_BUCKETS))
        val middleFrom = (floors.size - EDGE_BUCKETS) / 2
        val middle = median(floors.subList(middleFrom, middleFrom + EDGE_BUCKETS))
        val growth = end - start
        val gradual = middle - start >= growth * MIDDLE_SHARE && end - middle >= growth * MIDDLE_SHARE
        return Verdict(
            evaluatedAtEpochMs = nowEpochMs,
            windowMs = windowMs,
            growthBytes = growth,
            growing = growth >= GROWTH_THRESHOLD_BYTES && gradual
        )
    }

    /**
     * The longest restart interval on offer that keeps the growth under [RESTART_BUDGET_BYTES]
     * at the verdict's pace; null when the heap does not keep growing and no restart is needed.
     * Glance only suggests it: the owner decides whether to schedule restarts.
     */
    fun suggestedRestartIntervalDays(verdict: Verdict): Int? {
        if (!verdict.growing || verdict.windowMs <= 0) return null
        val perDay = verdict.growthBytes.toDouble() * DAY_MS / verdict.windowMs
        val days = (RESTART_BUDGET_BYTES / perDay).toInt()
        return MaintenanceSettings.INTERVAL_DAYS.lastOrNull { it <= days }
            ?: MaintenanceSettings.INTERVAL_DAYS.first()
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
