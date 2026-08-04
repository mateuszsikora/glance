package com.glance.content

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

object ContentSchedulePolicy {
    fun activeUrls(
        now: LocalDateTime,
        defaultUrls: List<String>,
        scheduleEnabled: Boolean,
        profiles: List<ContentProfile>
    ): List<String> {
        if (!scheduleEnabled) return defaultUrls

        val validProfiles = profiles.mapNotNull { profile ->
            val time = runCatching { LocalTime.parse(profile.startTime) }.getOrNull()
            if (time == null || profile.urls.isEmpty()) null else time to profile
        }

        if (validProfiles.isEmpty()) return defaultUrls

        // A profile stays active until the next one starts, so when nothing has started yet today
        // the most recent earlier day still owns the screen.
        urlsStartedBy(validProfiles, now.dayOfWeek, now.toLocalTime())?.let { return it }
        for (daysBack in 1..WeekDays.ALL.size) {
            urlsStartedBy(validProfiles, now.dayOfWeek.minus(daysBack.toLong()), LocalTime.MAX)
                ?.let { return it }
        }
        return defaultUrls
    }

    private fun urlsStartedBy(
        profiles: List<Pair<LocalTime, ContentProfile>>,
        day: DayOfWeek,
        latestStart: LocalTime
    ): List<String>? {
        val started = profiles.filter { (time, profile) ->
            profile.appliesOn(day) && time <= latestStart
        }
        val startTime = started.maxOfOrNull { it.first } ?: return null

        val candidates = started.filter { it.first == startTime }
        // A profile listing explicit days beats an everyday profile that starts the same minute.
        val dayCount = candidates.minOf { it.second.dayCount }
        return candidates
            .filter { it.second.dayCount == dayCount }
            .flatMap { it.second.urls }
            .distinct()
    }
}
