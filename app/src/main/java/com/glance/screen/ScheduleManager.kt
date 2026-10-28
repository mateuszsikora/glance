package com.glance.screen

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.glance.config.AppConfig
import com.glance.kiosk.KioskService
import com.glance.maintenance.MaintenanceSettings
import com.glance.maintenance.NightlyMaintenancePolicy
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar

/**
 * Persistent daily screen schedule and the nightly maintenance restart placed inside it.
 *
 * Alarms target the manifest-declared [ScheduleReceiver], so they remain actionable even when
 * MainActivity has been destroyed. The receiver delegates hardware work to [KioskService].
 */
class ScheduleManager(
    private val context: Context,
    private val config: AppConfig,
    private val maintenance: MaintenanceSettings = MaintenanceSettings(context)
) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun start(applyCurrentState: Boolean = true) {
        scheduleNightlyMaintenance()
        if (!config.scheduleEnabled) {
            cancelScreenAlarms()
            Log.i(TAG, "Schedule disabled in config")
            return
        }

        val onTime = parseTime(config.screenOnTime, DEFAULT_ON_TIME)
        val offTime = parseTime(config.screenOffTime, DEFAULT_OFF_TIME)
        if (onTime == offTime) {
            cancelScreenAlarms()
            if (applyCurrentState) {
                dispatchScreenCommand(true)
            }
            Log.w(TAG, "Identical ON/OFF times mean always ON; no alarms scheduled")
            return
        }

        scheduleNextAlarm(ACTION_SCREEN_ON, onTime)
        scheduleNextAlarm(ACTION_SCREEN_OFF, offTime)
        if (applyCurrentState) {
            dispatchScreenCommand(shouldScreenBeOnNow())
        }
        Log.i(TAG, "Schedule started: ON=${config.screenOnTime}, OFF=${config.screenOffTime}")
    }

    fun stop() {
        cancelScreenAlarms()
        cancelAlarm(ACTION_NIGHTLY_MAINTENANCE)
        Log.i(TAG, "Schedule stopped")
    }

    fun handleAlarm(action: String) {
        if (config.isKioskSuspended) {
            stop()
            Log.i(TAG, "Ignoring schedule alarm while kiosk is suspended")
            return
        }
        if (action == ACTION_NIGHTLY_MAINTENANCE) {
            Log.i(TAG, "Nightly maintenance alarm fired")
            scheduleNightlyMaintenance()
            if (maintenance.nightlyRestartEnabled) {
                dispatchKioskCommand(
                    Intent(context, KioskService::class.java)
                        .setAction(KioskService.ACTION_NIGHTLY_MAINTENANCE)
                )
            }
            return
        }
        if (!config.scheduleEnabled) {
            cancelScreenAlarms()
            return
        }

        val onTime = parseTime(config.screenOnTime, DEFAULT_ON_TIME)
        val offTime = parseTime(config.screenOffTime, DEFAULT_OFF_TIME)
        if (onTime == offTime) {
            start()
            return
        }

        when (action) {
            ACTION_SCREEN_ON -> {
                Log.i(TAG, "Schedule: screen ON alarm fired")
                dispatchScreenCommand(true)
                scheduleNextAlarm(ACTION_SCREEN_ON, onTime)
            }
            ACTION_SCREEN_OFF -> {
                Log.i(TAG, "Schedule: screen OFF alarm fired")
                dispatchScreenCommand(false)
                scheduleNextAlarm(ACTION_SCREEN_OFF, offTime)
            }
        }
    }

    private fun cancelScreenAlarms() {
        cancelAlarm(ACTION_SCREEN_ON)
        cancelAlarm(ACTION_SCREEN_OFF)
    }

    /**
     * Independent of the screen schedule: installations without an OFF window need the restart
     * too, at their quiet time instead.
     */
    private fun scheduleNightlyMaintenance() {
        if (!maintenance.nightlyRestartEnabled) {
            cancelAlarm(ACTION_NIGHTLY_MAINTENANCE)
            return
        }
        val offWindow = if (config.scheduleEnabled) {
            NightlyMaintenancePolicy.ScreenOffWindow(
                on = parseTime(config.screenOnTime, DEFAULT_ON_TIME),
                off = parseTime(config.screenOffTime, DEFAULT_OFF_TIME)
            )
        } else {
            null
        }
        val runTime = NightlyMaintenancePolicy.runTime(
            offWindow,
            parseTime(maintenance.quietTime, DEFAULT_QUIET_TIME)
        )
        val nextRun = NightlyMaintenancePolicy.nextRun(LocalDateTime.now(), runTime)
        setAlarm(
            ACTION_NIGHTLY_MAINTENANCE,
            nextRun.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        Log.i(TAG, "Nightly maintenance scheduled for $nextRun")
    }

    private fun shouldScreenBeOnNow(): Boolean {
        val now = LocalTime.now()
        val onTime = parseTime(config.screenOnTime, DEFAULT_ON_TIME)
        val offTime = parseTime(config.screenOffTime, DEFAULT_OFF_TIME)
        return SchedulePolicy.shouldBeOn(now, onTime, offTime)
    }

    private fun scheduleNextAlarm(action: String, time: LocalTime) {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, time.hour)
            set(Calendar.MINUTE, time.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        setAlarm(action, calendar.timeInMillis)
    }

    private fun setAlarm(action: String, triggerAtMillis: Long) {
        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        try {
            if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    getPendingIntent(action)
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    getPendingIntent(action)
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to schedule alarm for $action", e)
        }
    }

    private fun cancelAlarm(action: String) {
        alarmManager.cancel(getPendingIntent(action))
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val requestCode = when (action) {
            ACTION_SCREEN_ON -> REQUEST_CODE_ON
            ACTION_SCREEN_OFF -> REQUEST_CODE_OFF
            else -> REQUEST_CODE_MAINTENANCE
        }
        val intent = Intent(action).setClass(context, ScheduleReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun dispatchScreenCommand(screenOn: Boolean) {
        dispatchKioskCommand(
            Intent(context, KioskService::class.java)
                .setAction(KioskService.ACTION_COMMAND_SCREEN)
                .putExtra(KioskService.EXTRA_SCREEN_ON, screenOn)
        )
    }

    private fun dispatchKioskCommand(intent: Intent) {
        ContextCompat.startForegroundService(context, intent)
    }

    private fun parseTime(value: String, fallback: LocalTime): LocalTime {
        return runCatching { LocalTime.parse(value) }
            .onFailure { Log.w(TAG, "Invalid schedule time '$value', using $fallback") }
            .getOrDefault(fallback)
    }

    companion object {
        private const val TAG = "ScheduleManager"
        const val ACTION_SCREEN_ON = "com.glance.action.SCHEDULE_SCREEN_ON"
        const val ACTION_SCREEN_OFF = "com.glance.action.SCHEDULE_SCREEN_OFF"
        const val ACTION_NIGHTLY_MAINTENANCE = "com.glance.action.NIGHTLY_MAINTENANCE"
        private const val REQUEST_CODE_ON = 2001
        private const val REQUEST_CODE_OFF = 2002
        private const val REQUEST_CODE_MAINTENANCE = 2003
        private val DEFAULT_ON_TIME = LocalTime.of(6, 0)
        private val DEFAULT_OFF_TIME = LocalTime.of(23, 0)
        private val DEFAULT_QUIET_TIME = LocalTime.parse(MaintenanceSettings.DEFAULT_QUIET_TIME)
    }
}

object SchedulePolicy {
    fun shouldBeOn(now: LocalTime, onTime: LocalTime, offTime: LocalTime): Boolean {
        if (onTime == offTime) return true
        return if (onTime < offTime) {
            !now.isBefore(onTime) && now.isBefore(offTime)
        } else {
            !now.isBefore(onTime) || now.isBefore(offTime)
        }
    }
}
