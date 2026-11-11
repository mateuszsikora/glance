package com.glance.screen

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glance.config.AppConfig
import com.glance.kiosk.KioskService
import com.glance.maintenance.MaintenanceSettings
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class ScheduleManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val config = AppConfig(context)
    private val maintenance = MaintenanceSettings(context)
    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Before
    fun scheduleRestarts() {
        // Off by default; these tests are about the restart's alarm.
        maintenance.nightlyRestartEnabled = true
    }

    @Test
    fun nightlyMaintenanceRunsAtTheQuietTimeWithoutAScreenSchedule() {
        config.scheduleEnabled = false

        ScheduleManager(context, config, maintenance).start()

        val alarm = alarms.scheduledAlarms.single()
        assertEquals(ScheduleManager.ACTION_NIGHTLY_MAINTENANCE, alarm.action())
        assertEquals(LocalTime.of(3, 30), alarm.localTime())
    }

    @Test
    fun nightlyMaintenanceRunsInsideTheScreenOffWindow() {
        config.scheduleEnabled = true
        config.screenOnTime = "06:00"
        config.screenOffTime = "22:30"

        ScheduleManager(context, config, maintenance).start(applyCurrentState = false)

        val alarm = alarms.scheduledAlarms
            .single { it.action() == ScheduleManager.ACTION_NIGHTLY_MAINTENANCE }
        assertEquals(LocalTime.of(22, 45), alarm.localTime())
        assertEquals(3, alarms.scheduledAlarms.size)
    }

    @Test
    fun switchingTheRestartOffCancelsItsAlarm() {
        ScheduleManager(context, config, maintenance).start()
        maintenance.nightlyRestartEnabled = false

        ScheduleManager(context, config, maintenance).start()

        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun aScheduledRebootAloneKeepsTheMaintenanceAlarm() {
        maintenance.nightlyRestartEnabled = false
        maintenance.tabletRebootEnabled = true
        config.scheduleEnabled = false

        ScheduleManager(context, config, maintenance).start()
        assertEquals(ScheduleManager.ACTION_NIGHTLY_MAINTENANCE, alarms.scheduledAlarms.single().action())

        ScheduleManager(context, config, maintenance).handleAlarm(ScheduleManager.ACTION_NIGHTLY_MAINTENANCE)
        assertEquals(
            KioskService.ACTION_NIGHTLY_MAINTENANCE,
            shadowOf(context as android.app.Application).nextStartedService?.action
        )
    }

    @Test
    fun stoppingTheScheduleCancelsTheRestartToo() {
        val manager = ScheduleManager(context, config, maintenance).also { it.start() }

        manager.stop()

        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun theMaintenanceAlarmReschedulesItselfAndAsksTheService() {
        val manager = ScheduleManager(context, config, maintenance)

        manager.handleAlarm(ScheduleManager.ACTION_NIGHTLY_MAINTENANCE)

        assertEquals(
            ScheduleManager.ACTION_NIGHTLY_MAINTENANCE,
            alarms.scheduledAlarms.single().action()
        )
        assertEquals(
            KioskService.ACTION_NIGHTLY_MAINTENANCE,
            shadowOf(context as android.app.Application).nextStartedService?.action
        )
    }

    @Test
    fun aStaleMaintenanceAlarmDoesNothingOnceTheRestartIsOff() {
        maintenance.nightlyRestartEnabled = false

        ScheduleManager(context, config, maintenance)
            .handleAlarm(ScheduleManager.ACTION_NIGHTLY_MAINTENANCE)

        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun aSuspendedKioskIgnoresTheMaintenanceAlarm() {
        ScheduleManager(context, config, maintenance).start()
        config.isKioskSuspended = true

        ScheduleManager(context, config, maintenance)
            .handleAlarm(ScheduleManager.ACTION_NIGHTLY_MAINTENANCE)

        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun aStaleScreenAlarmKeepsTheMaintenanceAlarm() {
        config.scheduleEnabled = false
        ScheduleManager(context, config, maintenance).start()

        ScheduleManager(context, config, maintenance).handleAlarm(ScheduleManager.ACTION_SCREEN_ON)

        assertEquals(
            ScheduleManager.ACTION_NIGHTLY_MAINTENANCE,
            alarms.scheduledAlarms.single().action()
        )
    }

    @Test
    fun theScreenTurnsOnByAnAlarmClock() {
        config.scheduleEnabled = true
        config.screenOnTime = "06:25"
        config.screenOffTime = "23:20"

        ScheduleManager(context, config, maintenance).start(applyCurrentState = false)

        val alarmClock = context.getSystemService(AlarmManager::class.java).nextAlarmClock
        assertEquals(
            LocalTime.of(6, 25),
            Instant.ofEpochMilli(alarmClock.triggerTime).atZone(ZoneId.systemDefault()).toLocalTime()
        )
        val on = alarms.scheduledAlarms.single { it.action() == ScheduleManager.ACTION_SCREEN_ON }
        assertEquals(
            on.triggerAtMs,
            shadowOf(on.operation).savedIntent.getLongExtra(ScheduleManager.EXTRA_SCHEDULED_AT, 0L)
        )
        val off = alarms.scheduledAlarms.single { it.action() == ScheduleManager.ACTION_SCREEN_OFF }
        assertNull(off.showIntent)
    }

    @Test
    fun onlyAnAlarmHeldBackBeyondBatchingCountsAsLate() {
        val due = 1_000_000_000L

        assertNull(ScheduleManager.lateMinutes(due, due + 5 * 60_000L))
        assertEquals(10L, ScheduleManager.lateMinutes(due, due + 10 * 60_000L))
        assertEquals(79L, ScheduleManager.lateMinutes(due, due + 79 * 60_000L + 14_000L))
    }

    private fun ShadowAlarmManager.ScheduledAlarm.action(): String? =
        shadowOf(operation).savedIntent.action

    private fun ShadowAlarmManager.ScheduledAlarm.localTime(): LocalTime =
        Instant.ofEpochMilli(triggerAtMs).atZone(ZoneId.systemDefault()).toLocalTime()
}
