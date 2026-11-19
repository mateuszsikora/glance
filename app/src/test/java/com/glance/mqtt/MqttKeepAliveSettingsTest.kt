package com.glance.mqtt

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MqttKeepAliveSettingsTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun theAlarmClockDefaultsOnOnlyForVendorsThatHibernateApps() {
        assertTrue(MqttKeepAliveSettings.defaultFor("HUAWEI"))
        assertTrue(MqttKeepAliveSettings.defaultFor("HONOR"))
        assertTrue(MqttKeepAliveSettings.defaultFor(" Huawei "))
        assertFalse(MqttKeepAliveSettings.defaultFor("samsung"))
        assertFalse(MqttKeepAliveSettings.defaultFor("Google"))
        assertFalse(MqttKeepAliveSettings.defaultFor(""))
    }

    @Test
    fun aStoredChoiceOverridesTheVendorDefault() {
        val huawei = MqttKeepAliveSettings(context, manufacturer = "HUAWEI")
        assertTrue(huawei.alarmClockWhileDisplayOff)

        huawei.alarmClockWhileDisplayOff = false

        assertFalse(MqttKeepAliveSettings(context, manufacturer = "HUAWEI").alarmClockWhileDisplayOff)
        MqttKeepAliveSettings(context, manufacturer = "samsung").alarmClockWhileDisplayOff = true
        assertTrue(MqttKeepAliveSettings(context, manufacturer = "samsung").alarmClockWhileDisplayOff)
    }
}
