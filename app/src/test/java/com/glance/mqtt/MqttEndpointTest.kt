package com.glance.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MqttEndpointTest {
    @Test
    fun combinesHostWithConfiguredPort() {
        assertEquals(
            "tcp://broker.example.test:2883",
            MqttEndpoint.serverUri("broker.example.test", 2883)
        )
        assertEquals(
            "ssl://broker.example.test:8883",
            MqttEndpoint.serverUri("ssl://broker.example.test", 8883)
        )
    }

    @Test
    fun preservesExplicitPortAndSupportsIpv6() {
        assertEquals(
            "ssl://broker.example.test:9999",
            MqttEndpoint.serverUri("SSL://broker.example.test:9999", 8883)
        )
        assertEquals(
            "tcp://[2001:db8::1]:1883",
            MqttEndpoint.serverUri("2001:db8::1", 1883)
        )
    }

    @Test
    fun rejectsWebSchemesAndPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttEndpoint.serverUri("https://broker.example.test", 1883)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MqttEndpoint.serverUri("tcp://broker.example.test/path", 1883)
        }
    }
}
