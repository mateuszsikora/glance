package com.glance.content

import java.time.DayOfWeek
import java.time.LocalDateTime
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
            ContentSchedulePolicy.activeUrls(monday(12, 0), defaults, false, profiles)
        )
        assertEquals(
            defaults,
            ContentSchedulePolicy.activeUrls(monday(12, 0), defaults, true, emptyList())
        )
    }

    @Test
    fun selectsMostRecentProfileIncludingExactStartTime() {
        assertEquals(
            listOf("https://morning.example.test"),
            ContentSchedulePolicy.activeUrls(monday(6, 0), defaults, true, profiles)
        )
        assertEquals(
            listOf("https://evening.example.test", "https://weather.example.test"),
            ContentSchedulePolicy.activeUrls(monday(23, 59), defaults, true, profiles)
        )
    }

    @Test
    fun lastProfileWrapsAcrossMidnight() {
        assertEquals(
            listOf("https://evening.example.test", "https://weather.example.test"),
            ContentSchedulePolicy.activeUrls(monday(2, 30), defaults, true, profiles)
        )
    }

    @Test
    fun weekdayProfileStaysActiveUntilTheWeekendProfileStarts() {
        val weekly = listOf(
            ContentProfile("06:00", listOf("https://work.example.test"), WEEKDAYS),
            ContentProfile("09:00", listOf("https://weekend.example.test"), WEEKEND)
        )

        // Saturday before 09:00 still belongs to Friday's weekday profile.
        assertEquals(
            listOf("https://work.example.test"),
            ContentSchedulePolicy.activeUrls(saturday(8, 0), defaults, true, weekly)
        )
        assertEquals(
            listOf("https://weekend.example.test"),
            ContentSchedulePolicy.activeUrls(saturday(9, 0), defaults, true, weekly)
        )
        // Monday before 06:00 is still covered by Sunday.
        assertEquals(
            listOf("https://weekend.example.test"),
            ContentSchedulePolicy.activeUrls(monday(5, 59), defaults, true, weekly)
        )
        assertEquals(
            listOf("https://work.example.test"),
            ContentSchedulePolicy.activeUrls(monday(6, 0), defaults, true, weekly)
        )
    }

    @Test
    fun profileWithExplicitDaysWinsOverDailyProfileAtTheSameTime() {
        val mixed = listOf(
            ContentProfile("18:00", listOf("https://evening.example.test")),
            ContentProfile("18:00", listOf("https://party.example.test"), setOf(DayOfWeek.SATURDAY))
        )

        assertEquals(
            listOf("https://party.example.test"),
            ContentSchedulePolicy.activeUrls(saturday(19, 0), defaults, true, mixed)
        )
        assertEquals(
            listOf("https://evening.example.test"),
            ContentSchedulePolicy.activeUrls(monday(19, 0), defaults, true, mixed)
        )
    }

    @Test
    fun singleWeeklyProfileStaysActiveAllWeek() {
        val weekly = listOf(
            ContentProfile("18:00", listOf("https://gym.example.test"), setOf(DayOfWeek.MONDAY))
        )

        assertEquals(
            listOf("https://gym.example.test"),
            ContentSchedulePolicy.activeUrls(saturday(12, 0), defaults, true, weekly)
        )
        // Monday morning is owned by the previous Monday evening, not by today's later start.
        assertEquals(
            listOf("https://gym.example.test"),
            ContentSchedulePolicy.activeUrls(monday(9, 0), defaults, true, weekly)
        )
    }

    /** 5 January 2026 is a Monday. */
    private fun monday(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(2026, 1, 5, hour, minute)

    private fun saturday(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(2026, 1, 10, hour, minute)

    private companion object {
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val WEEKDAYS = DayOfWeek.values().toSet() - WEEKEND
    }
}
