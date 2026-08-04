package com.glance.settings

import com.glance.config.AppConfig
import com.glance.content.ContentProfile
import com.glance.content.WeekDays
import com.glance.mqtt.MqttEndpoint
import java.net.URI
import java.time.DayOfWeek
import java.time.LocalTime

data class ContentProfilesParseResult(
    val profiles: List<ContentProfile> = emptyList(),
    val error: String? = null
)

/** One unvalidated profile as entered in the settings screen or the remote panel. */
data class ContentProfileDraft(
    val startTime: String,
    val urls: List<String>,
    val days: Set<DayOfWeek> = emptySet()
) {
    /** Rows are pre-filled with a start time, so only the URLs decide whether one was used. */
    val isEmpty: Boolean get() = urls.isEmpty()
}

object ConfigValidator {
    fun isValidDashboardUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    /** A blank manifest URL disables update checks, so only a non-blank value has to parse. */
    fun isValidUpdateUrl(value: String): Boolean {
        return value.isBlank() || isValidDashboardUrl(value)
    }

    fun isValidMqttHost(value: String): Boolean {
        return runCatching { MqttEndpoint.serverUri(value, 1883) }.isSuccess
    }

    fun isValidTime(value: String): Boolean = TIME_REGEX.matches(value)

    fun isValidBrightnessRange(minimum: Int?, maximum: Int?): Boolean {
        return minimum != null &&
            maximum != null &&
            minimum in 0..255 &&
            maximum in 0..255 &&
            minimum <= maximum
    }

    fun isValidRotateInterval(value: Int?): Boolean {
        return value != null && value in MIN_ROTATE_SECONDS..MAX_ROTATE_SECONDS
    }

    fun isValidIdleTimeout(value: Int?): Boolean {
        return value != null &&
            value in AppConfig.MIN_IDLE_TIMEOUT_MINUTES..AppConfig.MAX_IDLE_TIMEOUT_MINUTES
    }

    /** Parses the text format, which keeps remote clients and exported settings scriptable. */
    fun parseContentProfiles(value: String): ContentProfilesParseResult {
        val drafts = mutableListOf<ContentProfileDraft>()

        value.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEachIndexed

            val parts = line.split("|", limit = 2).map(String::trim)
            if (parts.size != 2 || parts.any(String::isBlank)) {
                return ContentProfilesParseResult(
                    error = "Line ${index + 1} must use [days] HH:mm | URL"
                )
            }

            val (schedule, url) = parts
            val daySeparator = schedule.lastIndexOf(' ')
            val time = schedule.substring(daySeparator + 1)
            val days = WeekDays.parse(schedule.take(daySeparator + 1))
                ?: return ContentProfilesParseResult(
                    error = "Line ${index + 1} has an invalid day; use Mon-Fri, Sat,Sun or weekend"
                )
            if (!isValidTime(time)) {
                return ContentProfilesParseResult(
                    error = "Line ${index + 1} has an invalid time; use HH:mm"
                )
            }
            if (!isValidDashboardUrl(url)) {
                return ContentProfilesParseResult(
                    error = "Line ${index + 1} must contain an http:// or https:// URL"
                )
            }
            drafts.add(ContentProfileDraft(time, listOf(url), days))
        }

        return ContentProfilesParseResult(profiles = merge(drafts))
    }

    /** Validates profiles built from structured editors, numbering errors the way they are shown. */
    fun buildContentProfiles(drafts: List<ContentProfileDraft>): ContentProfilesParseResult {
        val validated = mutableListOf<ContentProfileDraft>()

        drafts.forEachIndexed { index, draft ->
            if (draft.isEmpty) return@forEachIndexed
            if (!isValidTime(draft.startTime)) {
                return ContentProfilesParseResult(
                    error = "Profile ${index + 1} needs a start time in HH:mm format"
                )
            }
            if (draft.urls.any { !isValidDashboardUrl(it) }) {
                return ContentProfilesParseResult(
                    error = "Profile ${index + 1} must only contain http:// or https:// URLs"
                )
            }
            validated.add(draft)
        }

        return ContentProfilesParseResult(profiles = merge(validated))
    }

    fun formatContentProfiles(profiles: List<ContentProfile>): String {
        return profiles
            .sortedWith(profileOrder)
            .flatMap { profile ->
                val prefix = WeekDays.format(profile.days).let { if (it.isEmpty()) "" else "$it " }
                profile.urls.map { url -> "$prefix${profile.startTime} | $url" }
            }
            .joinToString("\n")
    }

    /** Drafts sharing a start time and day set become one rotating profile. */
    private fun merge(drafts: List<ContentProfileDraft>): List<ContentProfile> {
        val grouped = linkedMapOf<Pair<String, Set<DayOfWeek>>, MutableList<String>>()
        drafts.forEach { draft ->
            grouped.getOrPut(draft.startTime to draft.days) { mutableListOf() }.addAll(draft.urls)
        }
        return grouped.entries
            .map { (key, urls) -> ContentProfile(key.first, urls.distinct(), key.second) }
            .sortedWith(profileOrder)
    }

    private val profileOrder = compareBy<ContentProfile>(
        { runCatching { LocalTime.parse(it.startTime) }.getOrNull() },
        { WeekDays.format(it.days) }
    )

    fun isValidSettingsPin(value: String): Boolean {
        return value.length in 4..12 && value.all(Char::isDigit) && value != LEGACY_DEFAULT_PIN
    }

    const val MIN_ROTATE_SECONDS = 5
    const val MAX_ROTATE_SECONDS = 86_400
    private const val LEGACY_DEFAULT_PIN = "1234"
    private val TIME_REGEX = Regex("""(?:[01]\d|2[0-3]):[0-5]\d""")
}
