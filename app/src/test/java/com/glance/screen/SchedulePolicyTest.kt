package com.glance.screen

import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePolicyTest {
    @Test
    fun handlesDaytimeWindow() {
        val on = LocalTime.of(6, 0)
        val off = LocalTime.of(23, 0)
        assertTrue(SchedulePolicy.shouldBeOn(LocalTime.of(12, 0), on, off))
        assertFalse(SchedulePolicy.shouldBeOn(LocalTime.of(2, 0), on, off))
        assertFalse(SchedulePolicy.shouldBeOn(off, on, off))
    }

    @Test
    fun handlesOvernightWindow() {
        val on = LocalTime.of(22, 0)
        val off = LocalTime.of(6, 0)
        assertTrue(SchedulePolicy.shouldBeOn(LocalTime.of(23, 30), on, off))
        assertTrue(SchedulePolicy.shouldBeOn(LocalTime.of(5, 30), on, off))
        assertFalse(SchedulePolicy.shouldBeOn(LocalTime.of(12, 0), on, off))
    }
}
