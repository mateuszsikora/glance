package com.glance.content

import java.time.LocalTime

object ContentSchedulePolicy {
    fun activeUrls(
        now: LocalTime,
        defaultUrls: List<String>,
        scheduleEnabled: Boolean,
        profiles: List<ContentProfile>
    ): List<String> {
        if (!scheduleEnabled) return defaultUrls

        val validProfiles = profiles.mapNotNull { profile ->
            val time = runCatching { LocalTime.parse(profile.startTime) }.getOrNull()
            if (time == null || profile.urls.isEmpty()) null else time to profile
        }.sortedBy { it.first }

        if (validProfiles.isEmpty()) return defaultUrls

        return validProfiles.lastOrNull { (time, _) -> time <= now }
            ?.second
            ?.urls
            ?: validProfiles.last().second.urls
    }
}
