package com.glance.remote

import com.glance.content.WeekDays
import com.glance.settings.ContentProfileDraft
import java.time.DayOfWeek

/**
 * Scheduled content profiles as indexed form fields (`profile.0.time`, `profile.0.day.MONDAY`,
 * `profile.0.urls`). Indexing keeps every field name unique, so the panel can add and remove rows
 * while the request parser stays a plain name-to-value map.
 */
internal object RemoteContentProfiles {
    /**
     * Marks a request as coming from the row editor. Without it, a form whose rows were all removed
     * would be indistinguishable from a scripted client that only sends the text field.
     */
    const val MARKER_FIELD = "contentProfileRows"

    fun fieldName(index: Int, suffix: String): String = "profile.$index.$suffix"

    fun daySuffix(day: DayOfWeek): String = "day.${day.name}"

    fun dayFieldName(index: Int, day: DayOfWeek): String = fieldName(index, daySuffix(day))

    /** Returns null when the request carries no rows, letting callers fall back to the text field. */
    fun drafts(parameters: Map<String, String>): List<ContentProfileDraft>? {
        if (!parameters.containsKey(MARKER_FIELD)) return null

        val indices = parameters.keys
            .mapNotNull { key -> INDEX_PATTERN.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sorted()

        return indices.map { index ->
            val days = WeekDays.ALL.filter { parameters[dayFieldName(index, it)] == "on" }.toSet()
            ContentProfileDraft(
                startTime = parameters[fieldName(index, "time")].orEmpty().trim(),
                urls = parameters[fieldName(index, "urls")].orEmpty()
                    .lines()
                    .map(String::trim)
                    .filter(String::isNotBlank),
                days = WeekDays.normalize(days)
            )
        }
    }

    private val INDEX_PATTERN = Regex("""profile\.(\d+)\..+""")
}
