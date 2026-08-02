package com.glance.settings

import com.glance.config.AppConfig
import com.glance.content.ContentProfile
import com.glance.mqtt.MqttEndpoint
import java.net.URI
import java.time.LocalTime

data class ContentProfilesParseResult(
    val profiles: List<ContentProfile> = emptyList(),
    val error: String? = null
)

object ConfigValidator {
    fun isValidDashboardUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
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

    fun parseContentProfiles(value: String): ContentProfilesParseResult {
        val groupedUrls = linkedMapOf<String, MutableList<String>>()

        value.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEachIndexed

            val parts = line.split("|", limit = 2).map(String::trim)
            if (parts.size != 2 || parts.any(String::isBlank)) {
                return ContentProfilesParseResult(
                    error = "Line ${index + 1} must use HH:mm | URL"
                )
            }

            val (time, url) = parts
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
            groupedUrls.getOrPut(time) { mutableListOf() }.add(url)
        }

        val profiles = groupedUrls.entries
            .sortedBy { LocalTime.parse(it.key) }
            .map { (time, urls) -> ContentProfile(time, urls.distinct()) }
        return ContentProfilesParseResult(profiles = profiles)
    }

    fun formatContentProfiles(profiles: List<ContentProfile>): String {
        return profiles
            .sortedBy { runCatching { LocalTime.parse(it.startTime) }.getOrNull() }
            .flatMap { profile ->
                profile.urls.map { url -> "${profile.startTime} | $url" }
            }
            .joinToString("\n")
    }

    fun isValidSettingsPin(value: String): Boolean {
        return value.length in 4..12 && value.all(Char::isDigit) && value != LEGACY_DEFAULT_PIN
    }

    const val MIN_ROTATE_SECONDS = 5
    const val MAX_ROTATE_SECONDS = 86_400
    private const val LEGACY_DEFAULT_PIN = "1234"
    private val TIME_REGEX = Regex("""(?:[01]\d|2[0-3]):[0-5]\d""")
}
