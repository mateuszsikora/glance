package com.glance.settings

import java.net.URI

object ConfigValidator {
    fun isValidDashboardUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    fun isValidMqttHost(value: String): Boolean {
        if (value.isBlank() || value.any { it.isWhitespace() }) return false
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return false
        if (value.startsWith("tcp://", true) || value.startsWith("ssl://", true)) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return !uri.host.isNullOrBlank()
        }
        return !value.contains("://")
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

    const val MIN_ROTATE_SECONDS = 5
    const val MAX_ROTATE_SECONDS = 86_400
    private val TIME_REGEX = Regex("""(?:[01]\d|2[0-3]):[0-5]\d""")
}
