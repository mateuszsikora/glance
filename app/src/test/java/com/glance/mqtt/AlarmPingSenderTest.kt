package com.glance.mqtt

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AlarmPingSenderTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarmManager: ShadowAlarmManager get() = shadowOf(alarmManager)

    @Before
    fun grantSelfPermissions() {
        // Robolectric grants nothing by default, including the permission ContextCompat uses to
        // emulate RECEIVER_NOT_EXPORTED below API 33.
        shadowOf(context).grantPermissions("${context.packageName}.$NOT_EXPORTED_PERMISSION")
    }

    @Test
    fun startSchedulesAWakeupAlarmThatSurvivesIdle() {
        val sender = AlarmPingSender(context, "glance_tablet")

        sender.start()

        val alarm = shadowAlarmManager.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType())
        assertTrue(alarm.isAllowWhileIdle)
        sender.stop()
    }

    @Test
    fun firedAlarmTriggersAPing() {
        val sender = AlarmPingSender(context, "glance_tablet")
        var pings = 0
        sender.pingHandler = {
            pings++
            null
        }
        sender.start()

        shadowAlarmManager.fireAlarm(shadowAlarmManager.scheduledAlarms.single())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, pings)
        sender.stop()
    }

    @Test
    fun stopCancelsTheAlarm() {
        val sender = AlarmPingSender(context, "glance_tablet")
        sender.start()

        sender.stop()

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    private companion object {
        const val NOT_EXPORTED_PERMISSION = "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    }
}
