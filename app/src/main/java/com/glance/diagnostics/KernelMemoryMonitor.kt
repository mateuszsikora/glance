package com.glance.diagnostics

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Follows the kernel's unreclaimable memory through the tablet's uptime for [KernelMemoryTrend].
 *
 * Unlike Glance's native heap, this memory does not belong to the process, so a restart of Glance
 * must not lose its history: each finished hour's minimum is stored, at most one write an hour,
 * and the history starts over when the tablet boots again. The hour still running stays in
 * memory; a restart loses at most that hour. Runs whether the diagnostic journal is on or off.
 *
 * Deliberately kept out of [com.glance.config.AppConfig], like [DiagnosticSettings].
 */
class KernelMemoryMonitor(
    context: Context,
    private val readMeminfo: () -> String = { File(MemorySample.MEMINFO_PATH).readText() },
    private val bootCount: () -> Int = {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The hour still running; stored once a sample from a later hour arrives. */
    private var current: KernelMemoryTrend.Floor? = null

    @Synchronized
    fun onSample(elapsedMs: Long) {
        if (elapsedMs < KernelMemoryTrend.WARM_UP_MS) return
        val kb = runCatching { MemorySample.meminfoKb(readMeminfo(), "SUnreclaim") }
            .onFailure { Log.w(TAG, "Cannot read ${MemorySample.MEMINFO_PATH}", it) }
            .getOrNull() ?: return
        val hour = elapsedMs / KernelMemoryTrend.HOUR_MS
        val running = current
        current = when {
            running == null || running.hour != hour -> {
                running?.let(::store)
                KernelMemoryTrend.Floor(hour, kb)
            }
            kb < running.kb -> running.copy(kb = kb)
            else -> running
        }
    }

    /**
     * Reads the current level now, so it is fresh even between samples.
     *
     * @return null when `/proc/meminfo` cannot be read or lacks the figures.
     */
    fun status(): KernelMemoryTrend.Status? {
        val meminfo = runCatching(readMeminfo).getOrNull() ?: return null
        val unreclaimableKb = MemorySample.meminfoKb(meminfo, "SUnreclaim") ?: return null
        val totalKb = MemorySample.meminfoKb(meminfo, "MemTotal") ?: return null
        val now = elapsedRealtime()
        val floors = synchronized(this) {
            storedFloors(now / KernelMemoryTrend.HOUR_MS) + listOfNotNull(current)
        }
        return KernelMemoryTrend.Status(
            unreclaimableBytes = unreclaimableKb * KIB,
            totalBytes = totalKb * KIB,
            uptimeMs = now,
            growth = KernelMemoryTrend.dailyGrowth(floors, now)
        )
    }

    private fun store(floor: KernelMemoryTrend.Floor) {
        val kept = (storedFloors(floor.hour) + floor)
            .filter { it.hour > floor.hour - HISTORY_HOURS }
        prefs.edit()
            .putInt(KEY_BOOT_COUNT, boot())
            .putString(KEY_FLOORS, kept.joinToString(";") { "${it.hour}:${it.kb}" })
            .apply()
    }

    /**
     * @param hour an hour of the current boot.
     * @return the stored minimums of the current boot; none when they come from an earlier one,
     * which [Settings.Global.BOOT_COUNT] tells, or failing that an hour later than [hour].
     */
    private fun storedFloors(hour: Long): List<KernelMemoryTrend.Floor> {
        val stored = prefs.getString(KEY_FLOORS, null).orEmpty()
            .split(';')
            .mapNotNull { entry ->
                val fields = entry.split(':').mapNotNull(String::toLongOrNull)
                if (fields.size == 2) KernelMemoryTrend.Floor(fields[0], fields[1]) else null
            }
        val storedBoot = prefs.getInt(KEY_BOOT_COUNT, -1)
        val boot = boot()
        val rebooted = (storedBoot >= 0 && boot >= 0 && storedBoot != boot) ||
            stored.any { it.hour > hour }
        return if (rebooted) emptyList() else stored
    }

    /** -1 when the platform does not say. */
    private fun boot(): Int = runCatching(bootCount).getOrDefault(-1)

    companion object {
        /** Enough for [KernelMemoryTrend.MAX_DAYS] full days and the day running. */
        private const val HISTORY_HOURS = (KernelMemoryTrend.MAX_DAYS + 1) * 24L
        private const val PREFS_NAME = "glance_kernel_memory"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_FLOORS = "hourly_floors"
        private const val TAG = "KernelMemoryMonitor"
        private const val KIB = 1024L
    }
}
