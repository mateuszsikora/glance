package com.glance.mqtt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MqttContractTest {

    @Test
    fun topicsAreStableAndSanitized() {
        val topics = MqttContract.topics("homeassistant/", "AB:CD 12")

        assertEquals("homeassistant/light/glance_ab_cd_12/config", topics.discovery)
        assertEquals("glance/ab_cd_12/light/set", topics.command)
        assertEquals("glance/ab_cd_12/light/state", topics.state)
        assertEquals("glance/ab_cd_12/availability", topics.availability)
        assertEquals("homeassistant/status", topics.homeAssistantStatus)
        assertEquals("homeassistant/sensor/glance_ab_cd_12/battery/config", topics.batteryDiscovery)
        assertEquals(
            "homeassistant/binary_sensor/glance_ab_cd_12/charging/config",
            topics.chargingDiscovery
        )
        assertEquals("glance/ab_cd_12/battery/state", topics.batteryState)
    }

    @Test
    fun everyRetainedDiscoveryEntryIsRemovable() {
        val topics = MqttContract.topics("homeassistant", "tablet-1")

        assertEquals(
            listOf(topics.discovery, topics.batteryDiscovery, topics.chargingDiscovery),
            topics.discoveryTopics
        )
    }

    @Test
    fun discoveryCreatesBrightnessLightWithAvailabilityAndDevice() {
        val topics = MqttContract.topics("homeassistant", "tablet-1")
        val json = JSONObject(
            MqttContract.discoveryPayload(
                topics = topics,
                rawDeviceId = "tablet-1",
                deviceName = "Kitchen Tablet",
                model = "BAH2-L09",
                appVersion = "1.1"
            )
        )

        assertEquals("json", json.getString("schema"))
        assertEquals(topics.command, json.getString("command_topic"))
        assertEquals(topics.state, json.getString("state_topic"))
        assertEquals(topics.availability, json.getString("availability_topic"))
        assertTrue(json.getBoolean("brightness"))
        assertFalse(json.getBoolean("transition"))
        assertEquals(255, json.getInt("brightness_scale"))
        assertEquals("Kitchen Tablet", json.getJSONObject("device").getString("name"))
        assertEquals("Glance", json.getJSONObject("origin").getString("name"))
    }

    @Test
    fun batteryEntitiesShareTheDeviceAndReadTheBatteryTopic() {
        val topics = MqttContract.topics("homeassistant", "tablet-1")
        val battery = JSONObject(
            MqttContract.batteryDiscoveryPayload(
                topics = topics,
                rawDeviceId = "tablet-1",
                deviceName = "Kitchen Tablet",
                model = "BAH2-L09",
                appVersion = "1.1"
            )
        )
        val charging = JSONObject(
            MqttContract.chargingDiscoveryPayload(
                topics = topics,
                rawDeviceId = "tablet-1",
                deviceName = "Kitchen Tablet",
                model = "BAH2-L09",
                appVersion = "1.1"
            )
        )

        assertEquals("battery", battery.getString("device_class"))
        assertEquals("%", battery.getString("unit_of_measurement"))
        assertEquals(topics.batteryState, battery.getString("state_topic"))
        assertEquals(topics.availability, battery.getString("availability_topic"))
        assertEquals("glance_tablet-1_battery", battery.getString("unique_id"))

        assertEquals("battery_charging", charging.getString("device_class"))
        assertEquals(topics.batteryState, charging.getString("state_topic"))
        assertEquals("ON", charging.getString("payload_on"))
        assertEquals("OFF", charging.getString("payload_off"))

        // Both entities must land on the same Home Assistant device as the screen light.
        assertEquals(
            battery.getJSONObject("device").getJSONArray("identifiers").getString(0),
            charging.getJSONObject("device").getJSONArray("identifiers").getString(0)
        )
    }

    @Test
    fun batteryStateCarriesLevelAndChargingFlag() {
        val json = JSONObject(MqttContract.batteryStatePayload(63, charging = true))

        assertEquals(63, json.getInt("level"))
        assertTrue(json.getBoolean("charging"))
        assertEquals(100, JSONObject(MqttContract.batteryStatePayload(140, false)).getInt("level"))
    }

    @Test
    fun parsesFullAndBrightnessOnlyCommands() {
        val off = MqttContract.parseCommand("""{"state":"OFF","brightness":12}""")
        assertFalse(off.screenOn!!)
        assertEquals(12, off.brightness)

        val brightnessOnly = MqttContract.parseCommand("""{"brightness":300}""")
        assertNull(brightnessOnly.screenOn)
        assertEquals(255, brightnessOnly.brightness)
    }

    @Test
    fun rejectsNonNumericBrightness() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttContract.parseCommand("""{"brightness":"very bright"}""")
        }
    }
}
