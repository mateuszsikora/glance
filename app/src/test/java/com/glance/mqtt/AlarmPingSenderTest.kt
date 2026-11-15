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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AlarmPingSenderTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarmManager: ShadowAlarmManager get() = shadowOf(alarmManager)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

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
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.getType())
        assertTrue(alarm.isAllowWhileIdle)
        sender.stop()
    }

    @Test
    fun alarmLandsAfterThePingIsDue() {
        val sender = AlarmPingSender(context, "glance_tablet")
        sender.start()
        val keepAliveMs = 900_000L

        sender.schedule(keepAliveMs)

        // Paho skips a ping that is due even a fraction of a millisecond later than the alarm.
        val alarm = shadowAlarmManager.scheduledAlarms.single()
        assertTrue(alarm.getTriggerAtMs() > SystemClock.elapsedRealtime() + keepAliveMs)
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

    @Test
    fun withTheDisplayOffTheKeepAliveIsAnAlarmClock() {
        shadowOf(powerManager).setIsInteractive(false)
        val sender = AlarmPingSender(context, "glance_tablet", alarmClockWhileDisplayOff = { true })

        sender.start()

        val alarm = shadowAlarmManager.scheduledAlarms.single()
        assertNotNull(alarm.alarmClockInfo)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType())
        // The wall-clock time keeps the deadline: the ping is due after a keep-alive plus slack.
        val dueInMs = alarm.getTriggerAtMs() - System.currentTimeMillis()
        assertTrue(dueInMs > 60_000L && dueInMs <= 60_000L + AlarmPingSender.PING_SLACK_MS)
        sender.stop()
    }

    @Test
    fun withTheSwitchOffTheDisplayKeepsTheOrdinaryAlarm() {
        shadowOf(powerManager).setIsInteractive(false)
        val sender = AlarmPingSender(context, "glance_tablet", alarmClockWhileDisplayOff = { false })

        sender.start()

        val alarm = shadowAlarmManager.scheduledAlarms.single()
        assertNull(alarm.alarmClockInfo)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.getType())
        sender.stop()
    }

    @Test
    fun theDisplayTurningOffAndOnRearmsThePendingPing() {
        val sender = AlarmPingSender(context, "glance_tablet", alarmClockWhileDisplayOff = { true })
        sender.start()
        val armed = shadowAlarmManager.scheduledAlarms.single()
        assertNull(armed.alarmClockInfo)

        shadowOf(powerManager).setIsInteractive(false)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()

        // The ping armed with the display on would otherwise be the one a hibernating vendor
        // holds back, and Paho arms the next only after it has run.
        val clock = shadowAlarmManager.scheduledAlarms.single()
        assertNotNull(clock.alarmClockInfo)
        val dueInMs = clock.getTriggerAtMs() - System.currentTimeMillis()
        assertTrue(dueInMs > 60_000L && dueInMs <= 60_000L + AlarmPingSender.PING_SLACK_MS)

        shadowOf(powerManager).setIsInteractive(true)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()

        val exact = shadowAlarmManager.scheduledAlarms.single()
        assertNull(exact.alarmClockInfo)
        assertEquals(armed.getTriggerAtMs(), exact.getTriggerAtMs())
        sender.stop()
    }

    @Test
    fun aPingHalfAKeepAliveLateIsJournaled() {
        val journal = mutableListOf<String>()
        val sender = AlarmPingSender(context, "glance_tablet", journal = { journal += it })
        sender.keepAliveMs = { 900_000L }
        sender.pingHandler = { null }
        sender.start()
        val alarm = shadowAlarmManager.scheduledAlarms.single()

        // Due a minute after the start, so this arrives just under 8 minutes late.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(9))
        shadowAlarmManager.fireAlarm(alarm)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("MQTT keep-alive alarm came 7 min late; the broker may have closed the session"), journal)
        sender.stop()
    }

    @Test
    fun aPingInsideTheBrokersGraceIsNotJournaled() {
        val journal = mutableListOf<String>()
        val sender = AlarmPingSender(context, "glance_tablet", journal = { journal += it })
        sender.keepAliveMs = { 900_000L }
        sender.pingHandler = { null }
        sender.start()
        val alarm = shadowAlarmManager.scheduledAlarms.single()

        // EMUI batches alarms to five minutes on battery even when it does not hibernate Glance.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(6))
        shadowAlarmManager.fireAlarm(alarm)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(journal.isEmpty())
        sender.stop()
    }

    private companion object {
        const val NOT_EXPORTED_PERMISSION = "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    }
}
