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
            status = BatteryManager.BATTERY_STATUS_DISCHARGING
        )

        assertEquals(47, reading!!.levelPercent)
        assertFalse(reading.charging)
    }

    @Test
    fun scalesNonPercentageScales() {
        val reading = BatteryReading.from(
            level = 50,
            scale = 200,
            status = BatteryManager.BATTERY_STATUS_CHARGING
        )

        assertEquals(25, reading!!.levelPercent)
        assertTrue(reading.charging)
    }

    @Test
    fun onlyARisingChargeCountsAsCharging() {
        // Home Assistant's battery_charging class means the charge is progressing. A full battery
        // and a charger paused by a vendor charge limit are both plugged in, and neither charges.
        assertFalse(
            BatteryReading.from(100, 100, BatteryManager.BATTERY_STATUS_FULL)!!.charging
        )
        assertFalse(
            BatteryReading.from(80, 100, BatteryManager.BATTERY_STATUS_NOT_CHARGING)!!.charging
        )
        assertFalse(
            BatteryReading.from(80, 100, BatteryManager.BATTERY_STATUS_UNKNOWN)!!.charging
        )
        assertTrue(
            BatteryReading.from(80, 100, BatteryManager.BATTERY_STATUS_CHARGING)!!.charging
        )
    }

    @Test
    fun missingExtrasProduceNoReading() {
        assertNull(BatteryReading.from(-1, 100, BatteryManager.BATTERY_STATUS_FULL))
        assertNull(BatteryReading.from(50, 0, BatteryManager.BATTERY_STATUS_FULL))
    }
}
