package com.glance.battery

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryReadingTest {

    @Test
    fun scalesLevelToPercent() {
        val reading = BatteryReading.from(
            level = 47,
            scale = 100,
            status = BatteryManager.BATTERY_STATUS_DISCHARGING,
            plugged = 0
        )

        assertEquals(47, reading!!.levelPercent)
        assertFalse(reading.charging)
    }

    @Test
    fun scalesNonPercentageScales() {
        val reading = BatteryReading.from(
            level = 50,
            scale = 200,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = BatteryManager.BATTERY_PLUGGED_AC
        )

        assertEquals(25, reading!!.levelPercent)
        assertTrue(reading.charging)
    }

    @Test
    fun aFullBatteryOnAPluggedChargerStillCountsAsPowered() {
        val reading = BatteryReading.from(
            level = 100,
            scale = 100,
            status = BatteryManager.BATTERY_STATUS_FULL,
            plugged = BatteryManager.BATTERY_PLUGGED_USB
        )

        assertTrue(reading!!.charging)
    }

    @Test
    fun aPluggedButDischargingBatteryIsNotCharging() {
        val reading = BatteryReading.from(
            level = 80,
            scale = 100,
            status = BatteryManager.BATTERY_STATUS_DISCHARGING,
            plugged = BatteryManager.BATTERY_PLUGGED_AC
        )

        assertFalse(reading!!.charging)
    }

    @Test
    fun missingExtrasProduceNoReading() {
        assertNull(BatteryReading.from(-1, 100, BatteryManager.BATTERY_STATUS_FULL, 0))
        assertNull(BatteryReading.from(50, 0, BatteryManager.BATTERY_STATUS_FULL, 0))
    }
}
