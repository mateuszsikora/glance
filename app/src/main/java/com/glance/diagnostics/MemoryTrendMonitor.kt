package com.glance.diagnostics

import android.content.Context
import java.util.Locale

/**
 * Follows the native heap through the process's life and keeps the latest [MemoryTrend] verdict,
 * which the remote panel shows as a warning while the heap keeps growing.
 *
 * Runs whether the diagnostic journal is on or off: the samples stay in memory and only the
 * verdict, written once a day, is stored. The first verdict comes after half a day of uptime, then
 * one a day, and one just before a planned restart, which ends the samples the next verdict would
 * need. A verdict outlives the process, so the warning stays visible after a restart or a crash
 * until a later verdict replaces it.
 *
 * Deliberately kept out of [com.glance.config.AppConfig], like [DiagnosticSettings].
 */
class MemoryTrendMonitor(
    context: Context,
    private val processStartElapsedMs: Long,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val samples = ArrayDeque<MemoryTrend.Sample>()
    private var lastEvaluationElapsedMs: Long? = null

    @Synchronized
    fun onSample(elapsedMs: Long, nativeBytes: Long) {
        samples.addLast(MemoryTrend.Sample(elapsedMs, nativeBytes))
        while (samples.first().elapsedMs < elapsedMs - HISTORY_MS) samples.removeFirst()
        val last = lastEvaluationElapsedMs
        if (last == null || elapsedMs - last >= EVALUATION_INTERVAL_MS) evaluate(elapsedMs)
    }

    /** Called before a planned restart, whose new process starts without these samples. */
    @Synchronized
    fun evaluateNow(elapsedMs: Long) {
        evaluate(elapsedMs)
    }

    /** @return the newest verdict, from this process or an earlier one; null if there is none. */
    fun latest(): MemoryTrend.Verdict? {
        if (!prefs.contains(KEY_EVALUATED_AT)) return null
        return MemoryTrend.Verdict(
            evaluatedAtEpochMs = prefs.getLong(KEY_EVALUATED_AT, 0L),
            windowMs = prefs.getLong(KEY_WINDOW_MS, 0L),
            growthBytes = prefs.getLong(KEY_GROWTH_BYTES, 0L),
            growing = prefs.getBoolean(KEY_GROWING, false)
        )
    }

    private fun evaluate(elapsedMs: Long) {
        val verdict = MemoryTrend.evaluate(samples.toList(), processStartElapsedMs, nowEpochMs())
            ?: return
        lastEvaluationElapsedMs = elapsedMs
        prefs.edit()
            .putLong(KEY_EVALUATED_AT, verdict.evaluatedAtEpochMs)
            .putLong(KEY_WINDOW_MS, verdict.windowMs)
            .putLong(KEY_GROWTH_BYTES, verdict.growthBytes)
            .putBoolean(KEY_GROWING, verdict.growing)
            .commit()
        Diagnostics.record(
            String.format(
                Locale.US,
                "Memory trend: native heap %s %.1f MB in %.1f h",
                if (verdict.growing) "keeps growing," else "stable,",
                verdict.growthBytes / MIB,
                verdict.windowMs / HOUR_MS
            )
        )
    }

    companion object {
        /** Enough for [MemoryTrend.MAX_WINDOW_MS] after the warm-up. */
        private const val HISTORY_MS = MemoryTrend.MAX_WINDOW_MS + MemoryTrend.WARM_UP_MS
        private const val EVALUATION_INTERVAL_MS = 24 * 60 * 60_000L
        private const val PREFS_NAME = "glance_memory_trend"
        private const val KEY_EVALUATED_AT = "evaluated_at"
        private const val KEY_WINDOW_MS = "window_ms"
        private const val KEY_GROWTH_BYTES = "growth_bytes"
        private const val KEY_GROWING = "growing"
        private const val MIB = 1024.0 * 1024.0
        private const val HOUR_MS = 60.0 * 60_000.0
    }
}
