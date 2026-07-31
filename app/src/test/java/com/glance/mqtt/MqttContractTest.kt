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
