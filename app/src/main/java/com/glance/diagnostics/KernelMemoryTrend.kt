package com.glance.diagnostics

/**
 * Tells how fast the kernel's unreclaimable memory grows and when a tablet reboot is due.
 *
 * Some vendor kernels keep slab memory they never free. On a 3 GB Huawei tablet with Android 8,
 * `SUnreclaim` in `/proc/meminfo` reached 1.47 GB, over half the RAM, after 236 days without a
 * reboot. From then on any ordinary burst, such as a charger connection, made the low-memory killer
 * close Glance while it was on screen, although Glance's own memory was stable. No app can get
 * this memory back and restarting Glance does not touch it; only a reboot releases it.
 *
 * The leak builds up over months at a few megabytes a day, so the trend is read from daily
 * minimums over up to a week rather than from one process's day: each day since boot is the lowest
 * of its hourly minimums, like the native heap in [MemoryTrend], and the first hour after boot,
 * while drivers and services settle, is left out. Days with only a few hours of samples, because
 * the display was off, are skipped, and so is the current day, whose minimum is still falling.
 */
object KernelMemoryTrend {

    /** The lowest reading during one hour since boot. */
    data class Floor(val hour: Long, val kb: Long)

    /** @param spanDays days from the first to the last day the pace was measured on. */
    data class Growth(val bytesPerDay: Long, val spanDays: Int)

    data class Status(
        val unreclaimableBytes: Long,
        val totalBytes: Long,
        val uptimeMs: Long,
        /** Null until [MIN_DAYS] days have been measured since the tablet started. */
        val growth: Growth?
    ) {
        val share: Double get() = if (totalBytes > 0) unreclaimableBytes.toDouble() / totalBytes else 0.0
        val warningBytes: Long get() = (totalBytes * WARNING_SHARE).toLong()
        val advice: Advice get() = advise(this)
    }

    sealed class Advice {
        /** The kernel holds [WARNING_SHARE] of RAM or more. */
        object RebootNow : Advice()

        /** At the measured pace the warning level is [days] or fewer away; at least 1. */
        data class RebootSoon(val days: Int) : Advice()

        /** @param daysToWarning at the measured pace; null when it is not growing or unmeasured. */
        data class None(val daysToWarning: Int?) : Advice()
    }

    /** Drivers and services settle after boot. */
    const val WARM_UP_MS = 60 * 60_000L

    /**
     * On the 3 GB test tablet Android began closing Glance in the foreground once the kernel held
     * more than half of the RAM. This warns months before that at the pace measured there.
     */
    const val WARNING_SHARE = 0.4

    /** Fewer days are too few to tell a pace of a few megabytes a day from day-to-day noise. */
    const val MIN_DAYS = 3

    /** A week shows the current pace; the history kept is no longer than that. */
    const val MAX_DAYS = 7

    /** A reboot is suggested once the warning level is this many days away or fewer. */
    const val SOON_DAYS = 30

    /** A day needs this many hourly minimums to have a minimum that means something. */
    const val MIN_HOURS_PER_DAY = 6

    const val HOUR_MS = 60 * 60_000L
    const val DAY_MS = 24 * HOUR_MS

    /**
     * @param floors hourly minimums of the current boot, in any order.
     * @param nowElapsedMs [android.os.SystemClock.elapsedRealtime] now; its day is still running.
     * @return the median daily change between every pair of days, which a single odd day does not
     * move; null while fewer than [MIN_DAYS] days qualify.
     */
    fun dailyGrowth(floors: List<Floor>, nowElapsedMs: Long): Growth? {
        val today = nowElapsedMs / DAY_MS
        val days = floors
            .filter { it.hour * HOUR_MS >= WARM_UP_MS }
            .groupBy { it.hour / 24 }
            .filter { (day, hours) -> day < today && hours.distinctBy(Floor::hour).size >= MIN_HOURS_PER_DAY }
            .map { (day, hours) -> day to hours.minOf(Floor::kb) }
            .sortedBy { it.first }
            .takeLast(MAX_DAYS)
        if (days.size < MIN_DAYS) return null
        val slopes = buildList {
            for (i in days.indices) for (j in i + 1 until days.size) {
                add((days[j].second - days[i].second).toDouble() / (days[j].first - days[i].first))
            }
        }.sorted()
        return Growth(
            bytesPerDay = (slopes[slopes.size / 2] * KIB).toLong(),
            spanDays = (days.last().first - days.first().first).toInt()
        )
    }

    fun advise(status: Status): Advice {
        if (status.totalBytes <= 0) return Advice.None(null)
        if (status.unreclaimableBytes >= status.warningBytes) return Advice.RebootNow
        val perDay = status.growth?.bytesPerDay?.takeIf { it > 0 } ?: return Advice.None(null)
        val days = ((status.warningBytes - status.unreclaimableBytes) / perDay)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // Less than a day's growth away still reads as about a day.
        return if (days <= SOON_DAYS) Advice.RebootSoon(days.coerceAtLeast(1)) else Advice.None(days)
    }

    private const val KIB = 1024L
}
