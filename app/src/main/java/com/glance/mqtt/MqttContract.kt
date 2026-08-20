package com.glance.mqtt

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class MqttTopics(
    val discovery: String,
    val command: String,
    val state: String,
    val availability: String,
    val homeAssistantStatus: String,
    val batteryDiscovery: String,
    val chargingDiscovery: String,
    val batteryState: String
) {
    /** Every retained discovery entry Glance owns, in the order it publishes and removes them. */
    val discoveryTopics: List<String>
        get() = listOf(discovery, batteryDiscovery, chargingDiscovery)
}

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
            homeAssistantStatus = "$prefix/status",
            batteryDiscovery = "$prefix/sensor/glance_$deviceId/battery/config",
            chargingDiscovery = "$prefix/binary_sensor/glance_$deviceId/charging/config",
            batteryState = "glance/$deviceId/battery/state"
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
            putDeviceAndOrigin(deviceId, deviceName, model, appVersion)
        }.toString()
    }

    /**
     * Battery percentage sensor. A tablet left on a charger degrades its battery, so the charge
     * level has to reach Home Assistant for a smart plug to hold it inside a healthy window.
     */
    fun batteryDiscoveryPayload(
        topics: MqttTopics,
        rawDeviceId: String,
        deviceName: String,
        model: String,
        appVersion: String
    ): String {
        val deviceId = sanitizeId(rawDeviceId)
        return JSONObject().apply {
            put("name", "Battery")
            put("unique_id", "glance_${deviceId}_battery")
            put("default_entity_id", "sensor.glance_tablet_battery")
            put("state_topic", topics.batteryState)
            put("value_template", "{{ value_json.level }}")
            put("availability_topic", topics.availability)
            put("payload_available", "online")
            put("payload_not_available", "offline")
            put("device_class", "battery")
            put("state_class", "measurement")
            put("unit_of_measurement", "%")
            put("qos", 1)
            putDeviceAndOrigin(deviceId, deviceName, model, appVersion)
        }.toString()
    }

    /** Companion of the battery sensor: whether the charge is currently rising. */
    fun chargingDiscoveryPayload(
        topics: MqttTopics,
        rawDeviceId: String,
        deviceName: String,
        model: String,
        appVersion: String
    ): String {
        val deviceId = sanitizeId(rawDeviceId)
        return JSONObject().apply {
            put("name", "Charging")
            put("unique_id", "glance_${deviceId}_charging")
            put("default_entity_id", "binary_sensor.glance_tablet_charging")
            put("state_topic", topics.batteryState)
            // A JSON boolean renders as Python's True/False, so the template decides the payload.
            put("value_template", "{{ 'ON' if value_json.charging else 'OFF' }}")
            put("payload_on", "ON")
            put("payload_off", "OFF")
            put("availability_topic", topics.availability)
            put("payload_available", "online")
            put("payload_not_available", "offline")
            put("device_class", "battery_charging")
            put("qos", 1)
            putDeviceAndOrigin(deviceId, deviceName, model, appVersion)
        }.toString()
    }

    fun statePayload(screenOn: Boolean, brightness: Int): String {
        return JSONObject().apply {
            put("state", if (screenOn) "ON" else "OFF")
            put("brightness", brightness.coerceIn(0, 255))
        }.toString()
    }

    fun batteryStatePayload(levelPercent: Int, charging: Boolean): String {
        return JSONObject().apply {
            put("level", levelPercent.coerceIn(0, 100))
            put("charging", charging)
        }.toString()
    }

    private fun JSONObject.putDeviceAndOrigin(
        deviceId: String,
        deviceName: String,
        model: String,
        appVersion: String
    ) {
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
