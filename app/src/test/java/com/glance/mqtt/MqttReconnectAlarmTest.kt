package com.glance.mqtt

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MqttReconnectAlarmTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarmManager: ShadowAlarmManager get() = shadowOf(alarmManager)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Before
    fun grantSelfPermissions() {
        shadowOf(context).grantPermissions("${context.packageName}.$NOT_EXPORTED_PERMISSION")
    }

    @Test
    fun startArmsAWakeupAlarmAMinuteAhead() {
        val alarm = MqttReconnectAlarm(context, "glance_tablet", { false }) {}

        alarm.start()

        val armed = shadowAlarmManager.scheduledAlarms.single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, armed.getType())
        assertTrue(armed.isAllowWhileIdle)
        assertEquals(SystemClock.elapsedRealtime() + 60_000L, armed.getTriggerAtMs())
        assertTrue(alarm.isActive)
        alarm.stop()
    }

    @Test
    fun withTheDisplayOffTheRetryIsAnAlarmClock() {
        shadowOf(powerManager).setIsInteractive(false)
        val alarm = MqttReconnectAlarm(context, "glance_tablet", { true }) {}

        alarm.start()

        val armed = shadowAlarmManager.scheduledAlarms.single()
        assertNotNull(armed.alarmClockInfo)
        assertEquals(AlarmManager.RTC_WAKEUP, armed.getType())
        alarm.stop()
    }

    @Test
    fun theDisplayGoingOffMakesThePendingRetryAnAlarmClock() {
        val alarm = MqttReconnectAlarm(context, "glance_tablet", { true }) {}
        alarm.start()
        assertNull(shadowAlarmManager.scheduledAlarms.single().alarmClockInfo)

        shadowOf(powerManager).setIsInteractive(false)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()

        // A loss noticed with the display on would otherwise leave an alarm EMUI holds back.
        assertNotNull(shadowAlarmManager.scheduledAlarms.single().alarmClockInfo)
        alarm.stop()
    }

    @Test
    fun eachRetryArmsTheNextOneFurtherAhead() {
        var retries = 0
        val alarm = MqttReconnectAlarm(context, "glance_tablet", { false }) { retries++ }
        alarm.start()

        val delays = (1..6).map {
            shadowAlarmManager.fireAlarm(shadowAlarmManager.scheduledAlarms.single())
            shadowOf(Looper.getMainLooper()).idle()
            shadowAlarmManager.scheduledAlarms.single().getTriggerAtMs() - SystemClock.elapsedRealtime()
        }

        assertEquals(6, retries)
        assertEquals(listOf(120_000L, 240_000L, 480_000L, 900_000L, 900_000L, 900_000L), delays)
        alarm.stop()
    }

    @Test
    fun aRetryHoldsAWakeLockForTheAttempt() {
        val alarm = MqttReconnectAlarm(context, "glance_tablet", { false }) {}
        alarm.start()

        shadowAlarmManager.fireAlarm(shadowAlarmManager.scheduledAlarms.single())
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        alarm.stop()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun startingAgainKeepsTheBackoff() {
        val alarm = MqttReconnectAlarm(context, "glance_tablet", { false }) {}
        alarm.start()
        shadowAlarmManager.fireAlarm(shadowAlarmManager.scheduledAlarms.single())
        shadowOf(Looper.getMainLooper()).idle()

        // Every failed initial attempt starts it again; that must not bring the retries closer.
        alarm.start()

        val delay = shadowAlarmManager.scheduledAlarms.single().getTriggerAtMs() -
            SystemClock.elapsedRealtime()
        assertEquals(120_000L, delay)
        alarm.stop()
    }

    @Test
    fun stopCancelsTheRetriesAndAStartBeginsAgainAtAMinute() {
        val alarm = MqttReconnectAlarm(context, "glance_tablet", { false }) {}
        alarm.start()
        shadowAlarmManager.fireAlarm(shadowAlarmManager.scheduledAlarms.single())
        shadowOf(Looper.getMainLooper()).idle()

        alarm.stop()
        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
        assertFalse(alarm.isActive)

        alarm.start()
        val delay = shadowAlarmManager.scheduledAlarms.single().getTriggerAtMs() -
            SystemClock.elapsedRealtime()
        assertEquals(60_000L, delay)
        alarm.stop()
    }

    @Test
    fun aStoppedAlarmIgnoresTheDisplay() {
        val alarm = MqttReconnectAlarm(context, "glance_tablet", { true }) {}
        alarm.start()
        alarm.stop()

        shadowOf(powerManager).setIsInteractive(false)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun theRetryAndTheKeepAliveAreSeparateAlarms() {
        val ping = AlarmPingSender(context, "glance_tablet", alarmClockWhileDisplayOff = { false })
        val alarm = MqttReconnectAlarm(context, "glance_tablet", { false }) {}
        ping.start()

        alarm.start()

        // Paho stops the keep-alive on a loss and starts it on a reconnect; neither may cancel
        // or replace the other's alarm.
        assertEquals(2, shadowAlarmManager.scheduledAlarms.size)
        ping.stop()
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
        alarm.stop()
    }

    private companion object {
        const val NOT_EXPORTED_PERMISSION = "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    }
}
