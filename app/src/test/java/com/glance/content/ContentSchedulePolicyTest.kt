package com.glance.content

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentSchedulePolicyTest {
    private val defaults = listOf("https://default.example.test")
    private val profiles = listOf(
        ContentProfile("06:00", listOf("https://morning.example.test")),
        ContentProfile(
            "18:00",
            listOf("https://evening.example.test", "https://weather.example.test")
        )
    )

    @Test
    fun disabledOrEmptyScheduleUsesDefaultUrls() {
        assertEquals(
            defaults,
            ContentSchedulePolicy.activeUrls(LocalTime.NOON, defaults, false, profiles)
        )
        assertEquals(
            defaults,
            ContentSchedulePolicy.activeUrls(LocalTime.NOON, defaults, true, emptyList())
        )
    }

    @Test
    fun selectsMostRecentProfileIncludingExactStartTime() {
        assertEquals(
            listOf("https://morning.example.test"),
            ContentSchedulePolicy.activeUrls(LocalTime.of(6, 0), defaults, true, profiles)
        )
        assertEquals(
            listOf("https://evening.example.test", "https://weather.example.test"),
            ContentSchedulePolicy.activeUrls(LocalTime.of(23, 59), defaults, true, profiles)
        )
    }

    @Test
    fun lastProfileWrapsAcrossMidnight() {
        assertEquals(
            listOf("https://evening.example.test", "https://weather.example.test"),
            ContentSchedulePolicy.activeUrls(LocalTime.of(2, 30), defaults, true, profiles)
        )
    }
}
