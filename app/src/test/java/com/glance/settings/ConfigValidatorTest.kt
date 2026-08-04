package com.glance.settings

import com.glance.content.ContentProfile
import java.time.DayOfWeek
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
        assertTrue(ConfigValidator.isValidMqttHost("2001:db8::1"))
        assertFalse(ConfigValidator.isValidMqttHost("https://broker.example.test"))
        assertFalse(ConfigValidator.isValidMqttHost("tcp://broker.example.test/path"))
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
        assertTrue(ConfigValidator.isValidIdleTimeout(5))
        assertFalse(ConfigValidator.isValidIdleTimeout(0))
        assertTrue(ConfigValidator.isValidSettingsPin("583902"))
        assertFalse(ConfigValidator.isValidSettingsPin("1234"))
    }

    @Test
    fun parsesAndFormatsScheduledContentProfiles() {
        val result = ConfigValidator.parseContentProfiles(
            """
            18:00 | https://evening.example.test
            06:00 | https://morning.example.test
            18:00 | https://weather.example.test
            18:00 | https://weather.example.test
            """.trimIndent()
        )

        assertEquals(null, result.error)
        assertEquals(listOf("06:00", "18:00"), result.profiles.map { it.startTime })
        assertEquals(2, result.profiles.last().urls.size)
        assertEquals(
            "06:00 | https://morning.example.test\n" +
                "18:00 | https://evening.example.test\n" +
                "18:00 | https://weather.example.test",
            ConfigValidator.formatContentProfiles(result.profiles)
        )
    }

    @Test
    fun reportsLineContainingInvalidScheduledContent() {
        val result = ConfigValidator.parseContentProfiles(
            "06:00 | https://morning.example.test\ninvalid"
        )

        assertEquals("Line 2 must use [days] HH:mm | URL", result.error)
        assertTrue(result.profiles.isEmpty())
    }

    @Test
    fun parsesAndFormatsDayPrefixes() {
        val result = ConfigValidator.parseContentProfiles(
            """
            Mon-Fri 06:00 | https://work.example.test
            Sat,Sun 09:00 | https://weekend.example.test
            weekend 09:00 | https://brunch.example.test
            18:00 | https://evening.example.test
            """.trimIndent()
        )

        assertEquals(null, result.error)
        assertEquals(3, result.profiles.size)
        assertEquals(WEEKDAYS, result.profiles.first().days)
        // An everyday profile keeps an empty day set so it needs no prefix.
        assertEquals(emptySet<DayOfWeek>(), result.profiles.last().days)
        assertEquals(
            "Mon-Fri 06:00 | https://work.example.test\n" +
                "Sat,Sun 09:00 | https://weekend.example.test\n" +
                "Sat,Sun 09:00 | https://brunch.example.test\n" +
                "18:00 | https://evening.example.test",
            ConfigValidator.formatContentProfiles(result.profiles)
        )
    }

    @Test
    fun treatsEveryDayListedAsDaily() {
        val result = ConfigValidator.parseContentProfiles(
            "Mon-Sun 06:00 | https://morning.example.test"
        )

        assertEquals(null, result.error)
        assertEquals(emptySet<DayOfWeek>(), result.profiles.single().days)
        assertEquals(
            "06:00 | https://morning.example.test",
            ConfigValidator.formatContentProfiles(result.profiles)
        )
    }

    @Test
    fun reportsUnknownDayPrefix() {
        val result = ConfigValidator.parseContentProfiles(
            "Funday 06:00 | https://morning.example.test"
        )

        assertEquals(
            "Line 1 has an invalid day; use Mon-Fri, Sat,Sun or weekend",
            result.error
        )
        assertTrue(result.profiles.isEmpty())
    }

    @Test
    fun buildsProfilesFromEditorRowsAndSkipsEmptyOnes() {
        val result = ConfigValidator.buildContentProfiles(
            listOf(
                ContentProfileDraft("", emptyList()),
                ContentProfileDraft(
                    "18:00",
                    listOf("https://evening.example.test"),
                    setOf(DayOfWeek.FRIDAY)
                ),
                ContentProfileDraft(
                    "18:00",
                    listOf("https://weather.example.test"),
                    setOf(DayOfWeek.FRIDAY)
                )
            )
        )

        assertEquals(null, result.error)
        // Rows that share a start time and day set rotate inside one profile.
        assertEquals(
            listOf(
                ContentProfile(
                    "18:00",
                    listOf("https://evening.example.test", "https://weather.example.test"),
                    setOf(DayOfWeek.FRIDAY)
                )
            ),
            result.profiles
        )
    }

    @Test
    fun reportsInvalidEditorRowsByPosition() {
        assertEquals(
            "Profile 2 needs a start time in HH:mm format",
            ConfigValidator.buildContentProfiles(
                listOf(
                    ContentProfileDraft("06:00", listOf("https://morning.example.test")),
                    ContentProfileDraft("", listOf("https://evening.example.test"))
                )
            ).error
        )
        // A row the user added but never filled in is ignored rather than blocking the save.
        assertEquals(
            ContentProfilesParseResult(),
            ConfigValidator.buildContentProfiles(
                listOf(ContentProfileDraft("06:00", emptyList()))
            )
        )
        assertEquals(
            "Profile 1 must only contain http:// or https:// URLs",
            ConfigValidator.buildContentProfiles(
                listOf(ContentProfileDraft("06:00", listOf("morning.example.test")))
            ).error
        )
    }

    private companion object {
        val WEEKDAYS = DayOfWeek.values().toSet() -
            setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }
}
