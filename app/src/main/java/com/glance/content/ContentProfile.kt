package com.glance.content

import java.time.DayOfWeek

/**
 * A set of dashboard URLs that becomes active at [startTime] in the device's local time.
 * An empty [days] set repeats the profile every day.
 */
data class ContentProfile(
    val startTime: String,
    val urls: List<String>,
    val days: Set<DayOfWeek> = emptySet()
) {
    fun appliesOn(day: DayOfWeek): Boolean = days.isEmpty() || day in days

    /** Number of days covered; a lower value means a more specific profile. */
    val dayCount: Int get() = if (days.isEmpty()) WeekDays.ALL.size else days.size
}
