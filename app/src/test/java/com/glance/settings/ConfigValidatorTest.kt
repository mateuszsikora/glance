package com.glance.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValidatorTest {
    @Test
    fun validatesDashboardUrlsAndMqttHosts() {
        assertTrue(ConfigValidator.isValidDashboardUrl("http://192.168.21.254:8123/"))
        assertTrue(ConfigValidator.isValidDashboardUrl("HTTPS://ha.example.test/dashboard"))
        assertFalse(ConfigValidator.isValidDashboardUrl("example.com"))

        assertTrue(ConfigValidator.isValidMqttHost("192.168.21.254"))
        assertTrue(ConfigValidator.isValidMqttHost("ssl://broker.example.test:8883"))
        assertFalse(ConfigValidator.isValidMqttHost("https://broker.example.test"))
    }

    @Test
    fun validatesTimesAndNumericRanges() {
        assertTrue(ConfigValidator.isValidTime("06:00"))
        assertFalse(ConfigValidator.isValidTime("6:00"))
        assertFalse(ConfigValidator.isValidTime("25:00"))
        assertTrue(ConfigValidator.isValidBrightnessRange(5, 255))
        assertFalse(ConfigValidator.isValidBrightnessRange(200, 100))
        assertTrue(ConfigValidator.isValidRotateInterval(30))
        assertFalse(ConfigValidator.isValidRotateInterval(0))
    }
}
