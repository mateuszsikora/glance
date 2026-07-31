package com.glance.mqtt

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class MqttTopics(
    val discovery: String,
    val command: String,
    val state: String,
    val availability: String,
    val homeAssistantStatus: String
)

data class MqttLightCommand(
    val screenOn: Boolean?,
    val brightness: Int?
)

/**
 * MQTT topic and JSON contract shared by the client and unit tests.
 */
object MqttContract {

    fun sanitizeId(raw: String): String {
        return raw.lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .trim('_')
            .ifBlank { "android_tablet" }
    }

    fun topics(discoveryPrefix: String, rawDeviceId: String): MqttTopics {
        val deviceId = sanitizeId(rawDeviceId)
        val prefix = discoveryPrefix.trim().trim('/').ifBlank { "homeassistant" }
        val base = "glance/$deviceId/light"
        return MqttTopics(
            discovery = "$prefix/light/glance_$deviceId/config",
            command = "$base/set",
            state = "$base/state",
            availability = "glance/$deviceId/availability",
            homeAssistantStatus = "$prefix/status"
        )
    }

    fun discoveryPayload(
        topics: MqttTopics,
        rawDeviceId: String,
        deviceName: String,
        model: String,
        appVersion: String
    ): String {
        val deviceId = sanitizeId(rawDeviceId)
        return JSONObject().apply {
            put("name", "Screen")
            put("unique_id", "glance_${deviceId}_screen")
            put("default_entity_id", "light.glance_tablet")
            put("schema", "json")
            put("command_topic", topics.command)
            put("state_topic", topics.state)
            put("availability_topic", topics.availability)
            put("payload_available", "online")
            put("payload_not_available", "offline")
            put("brightness", true)
            put("brightness_scale", 255)
            put("transition", false)
            put("supported_color_modes", JSONArray().put("brightness"))
            put("qos", 1)
            put("device", JSONObject().apply {
                put("identifiers", JSONArray().put("glance_$deviceId"))
                put("name", deviceName.ifBlank { "Glance Tablet" })
                put("manufacturer", "Glance")
                put("model", model)
                put("sw_version", appVersion)
            })
            put("origin", JSONObject().apply {
                put("name", "Glance")
                put("sw_version", appVersion)
            })
        }.toString()
    }

    fun statePayload(screenOn: Boolean, brightness: Int): String {
        return JSONObject().apply {
            put("state", if (screenOn) "ON" else "OFF")
            put("brightness", brightness.coerceIn(0, 255))
        }.toString()
    }

    fun parseCommand(payload: String): MqttLightCommand {
        val json = JSONObject(payload)
        val state = when (json.optString("state").uppercase(Locale.ROOT)) {
            "ON" -> true
            "OFF" -> false
            else -> null
        }
        val brightness = if (json.has("brightness")) {
            val raw = json.get("brightness")
            val numeric = when (raw) {
                is Number -> raw.toInt()
                else -> raw.toString().toIntOrNull()
                    ?: throw IllegalArgumentException("brightness must be numeric")
            }
            numeric.coerceIn(0, 255)
        } else {
            null
        }
        return MqttLightCommand(state, brightness)
    }
}
