package com.glance.remote

import com.glance.config.AppConfig
import com.glance.settings.ConfigValidator

internal data class RemoteConfigSnapshot(
    val dashboardUrls: String,
    val dashboardAllowedOrigins: String,
    val autoRotateEnabled: Boolean,
    val autoRotateIntervalSeconds: Int,
    val contentScheduleEnabled: Boolean,
    val contentProfiles: String,
    val idleScreenEnabled: Boolean,
    val idleScreenUrl: String,
    val idleTimeoutMinutes: Int,
    val autoBrightnessEnabled: Boolean,
    val minBrightness: Int,
    val maxBrightness: Int,
    val scheduleEnabled: Boolean,
    val screenOnTime: String,
    val screenOffTime: String,
    val mqttEnabled: Boolean,
    val mqttBrokerHost: String,
    val mqttBrokerPort: Int,
    val mqttUsername: String,
    val mqttPasswordConfigured: Boolean,
    val mqttPasswordUnreadable: Boolean,
    val mqttDeviceName: String,
    val mqttDiscoveryPrefix: String,
    val remoteConfigEnabled: Boolean
)

internal sealed class RemoteConfigUpdateResult {
    data class Success(val pinChanged: Boolean) : RemoteConfigUpdateResult()
    data class Error(val message: String) : RemoteConfigUpdateResult()
}

/** Validates and atomically orders writes shared by the remote settings page. */
internal class RemoteConfigUpdater(private val config: AppConfig) {

    fun snapshot(): RemoteConfigSnapshot {
        val password = config.readMqttPassword()
        return RemoteConfigSnapshot(
            dashboardUrls = config.dashboardUrls.joinToString("\n"),
            dashboardAllowedOrigins = config.dashboardAllowedOrigins.joinToString("\n"),
            autoRotateEnabled = config.autoRotateEnabled,
            autoRotateIntervalSeconds = config.autoRotateIntervalSeconds,
            contentScheduleEnabled = config.contentScheduleEnabled,
            contentProfiles = ConfigValidator.formatContentProfiles(config.contentProfiles),
            idleScreenEnabled = config.idleScreenEnabled,
            idleScreenUrl = config.idleScreenUrl,
            idleTimeoutMinutes = config.idleTimeoutMinutes,
            autoBrightnessEnabled = config.autoBrightnessEnabled,
            minBrightness = config.minBrightness,
            maxBrightness = config.maxBrightness,
            scheduleEnabled = config.scheduleEnabled,
            screenOnTime = config.screenOnTime,
            screenOffTime = config.screenOffTime,
            mqttEnabled = config.mqttEnabled,
            mqttBrokerHost = config.mqttBrokerHost,
            mqttBrokerPort = config.mqttBrokerPort,
            mqttUsername = config.mqttUsername,
            mqttPasswordConfigured = password.value.isNotBlank(),
            mqttPasswordUnreadable = password.decryptionFailed,
            mqttDeviceName = config.mqttDeviceName,
            mqttDiscoveryPrefix = config.mqttDiscoveryPrefix,
            remoteConfigEnabled = config.remoteConfigEnabled
        )
    }

    fun apply(parameters: Map<String, String>): RemoteConfigUpdateResult {
        val urls = lines(parameters["dashboardUrls"])
        if (urls.isEmpty()) return error("At least one dashboard URL is required.")
        if (urls.any { !ConfigValidator.isValidDashboardUrl(it) }) {
            return error("Dashboard URLs must use http:// or https://.")
        }

        val allowedOrigins = lines(parameters["dashboardAllowedOrigins"]).distinct()
        if (allowedOrigins.any { !ConfigValidator.isValidDashboardUrl(it) }) {
            return error("Allowed login origins must use http:// or https://.")
        }

        val rotateInterval = parameters["autoRotateIntervalSeconds"]?.toIntOrNull()
        if (!ConfigValidator.isValidRotateInterval(rotateInterval)) {
            return error(
                "Rotate interval must be ${ConfigValidator.MIN_ROTATE_SECONDS}-" +
                    "${ConfigValidator.MAX_ROTATE_SECONDS} seconds."
            )
        }

        val contentProfilesResult = ConfigValidator.parseContentProfiles(
            parameters["contentProfiles"].orEmpty()
        )
        if (contentProfilesResult.error != null) {
            return error(contentProfilesResult.error)
        }
        val contentScheduleEnabled = parameters.hasCheckbox("contentScheduleEnabled")
        if (contentScheduleEnabled && contentProfilesResult.profiles.isEmpty()) {
            return error("Add at least one scheduled content profile.")
        }

        val idleScreenEnabled = parameters.hasCheckbox("idleScreenEnabled")
        val idleScreenUrl = parameters["idleScreenUrl"].orEmpty().trim()
        if (idleScreenEnabled && !ConfigValidator.isValidDashboardUrl(idleScreenUrl)) {
            return error("Idle screen URL must use http:// or https://.")
        }
        val idleTimeoutMinutes = parameters["idleTimeoutMinutes"]?.toIntOrNull()
        if (!ConfigValidator.isValidIdleTimeout(idleTimeoutMinutes)) {
            return error(
                "Idle timeout must be ${AppConfig.MIN_IDLE_TIMEOUT_MINUTES}-" +
                    "${AppConfig.MAX_IDLE_TIMEOUT_MINUTES} minutes."
            )
        }

        val minBrightness = parameters["minBrightness"]?.toIntOrNull()
        val maxBrightness = parameters["maxBrightness"]?.toIntOrNull()
        if (!ConfigValidator.isValidBrightnessRange(minBrightness, maxBrightness)) {
            return error("Brightness must satisfy 0 ≤ min ≤ max ≤ 255.")
        }

        val screenOnTime = parameters["screenOnTime"].orEmpty().ifBlank { "06:00" }
        val screenOffTime = parameters["screenOffTime"].orEmpty().ifBlank { "23:00" }
        if (!ConfigValidator.isValidTime(screenOnTime) ||
            !ConfigValidator.isValidTime(screenOffTime)
        ) {
            return error("Schedule times must use HH:mm (00:00-23:59).")
        }
        val scheduleEnabled = parameters.hasCheckbox("scheduleEnabled")
        if (scheduleEnabled && screenOnTime == screenOffTime) {
            return error("Wake and turn-off times must be different.")
        }

        val mqttEnabled = parameters.hasCheckbox("mqttEnabled")
        val mqttHost = parameters["mqttBrokerHost"].orEmpty().trim()
        val mqttPort = parameters["mqttBrokerPort"]?.toIntOrNull()
        if (mqttEnabled && mqttHost.isBlank()) {
            return error("MQTT broker host is required when MQTT is enabled.")
        }
        if (mqttEnabled && !ConfigValidator.isValidMqttHost(mqttHost)) {
            return error("Use a host/IP, tcp://host[:port], or ssl://host[:port].")
        }
        if (mqttPort == null || mqttPort !in 1..65535) {
            return error("MQTT port must be between 1 and 65535.")
        }

        val discoveryPrefix = parameters["mqttDiscoveryPrefix"].orEmpty()
            .trim()
            .trim('/')
            .ifBlank { "homeassistant" }
        if (discoveryPrefix.any(Char::isWhitespace) ||
            discoveryPrefix.contains('#') ||
            discoveryPrefix.contains('+')
        ) {
            return error("MQTT discovery prefix contains invalid characters.")
        }

        val newPin = parameters["newPin"].orEmpty().trim()
        val confirmPin = parameters["confirmPin"].orEmpty().trim()
        if (newPin.isNotEmpty() && !ConfigValidator.isValidSettingsPin(newPin)) {
            return error("The new PIN must contain 4-12 digits and cannot be 1234.")
        }
        if (newPin != confirmPin) return error("The new PIN and confirmation do not match.")

        val passwordState = config.readMqttPassword()
        val newMqttPassword = parameters["mqttPassword"].orEmpty()
        val clearMqttPassword = parameters.hasCheckbox("clearMqttPassword")
        if (clearMqttPassword && newMqttPassword.isNotBlank()) {
            return error("Enter a new MQTT password or select clear, but not both.")
        }
        if (mqttEnabled && passwordState.decryptionFailed &&
            newMqttPassword.isBlank() && !clearMqttPassword
        ) {
            return error("The stored MQTT password is unreadable. Replace or clear it.")
        }

        // Encrypt a replacement before modifying ordinary preferences so encryption failure
        // cannot leave the rest of the form half-applied.
        if (clearMqttPassword || newMqttPassword.isNotBlank()) {
            try {
                config.mqttPassword = if (clearMqttPassword) "" else newMqttPassword
            } catch (_: Exception) {
                return error("Unable to encrypt the MQTT password.")
            }
        }

        config.dashboardUrls = urls
        config.dashboardAllowedOrigins = allowedOrigins
        config.autoRotateEnabled = parameters.hasCheckbox("autoRotateEnabled")
        config.autoRotateIntervalSeconds = requireNotNull(rotateInterval)
        config.contentScheduleEnabled = contentScheduleEnabled
        config.contentProfiles = contentProfilesResult.profiles
        config.idleScreenEnabled = idleScreenEnabled
        config.idleScreenUrl = idleScreenUrl
        config.idleTimeoutMinutes = requireNotNull(idleTimeoutMinutes)
        config.autoBrightnessEnabled = parameters.hasCheckbox("autoBrightnessEnabled")
        config.minBrightness = requireNotNull(minBrightness)
        config.maxBrightness = requireNotNull(maxBrightness)
        config.scheduleEnabled = scheduleEnabled
        config.screenOnTime = screenOnTime
        config.screenOffTime = screenOffTime
        config.mqttEnabled = mqttEnabled
        config.mqttBrokerHost = mqttHost
        config.mqttBrokerPort = mqttPort
        config.mqttUsername = parameters["mqttUsername"].orEmpty().trim()
        config.mqttDeviceName = parameters["mqttDeviceName"].orEmpty()
            .trim()
            .ifBlank { "Glance Tablet" }
        config.mqttDiscoveryPrefix = discoveryPrefix
        config.remoteConfigEnabled = parameters.hasCheckbox("remoteConfigEnabled")

        val pinChanged = newPin.isNotEmpty()
        if (pinChanged) config.setSettingsPin(newPin)

        return RemoteConfigUpdateResult.Success(pinChanged)
    }

    private fun lines(raw: String?): List<String> = raw.orEmpty()
        .lines()
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun Map<String, String>.hasCheckbox(name: String): Boolean =
        this[name] == "on"

    private fun error(message: String) = RemoteConfigUpdateResult.Error(message)
}
