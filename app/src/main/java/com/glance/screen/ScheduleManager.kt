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
import java.time.LocalTime
import java.util.Calendar

/**
 * Persistent daily screen schedule.
 *
 * Alarms target the manifest-declared [ScheduleReceiver], so they remain actionable even when
 * MainActivity has been destroyed. The receiver delegates hardware work to [KioskService].
 */
class ScheduleManager(
    private val context: Context,
    private val config: AppConfig
) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun start(applyCurrentState: Boolean = true) {
        if (!config.scheduleEnabled) {
            stop()
            Log.i(TAG, "Schedule disabled in config")
            return
        }

        scheduleNextAlarm(ACTION_SCREEN_ON, config.screenOnTime)
        scheduleNextAlarm(ACTION_SCREEN_OFF, config.screenOffTime)
        if (applyCurrentState) {
            dispatchScreenCommand(shouldScreenBeOnNow())
        }
        Log.i(TAG, "Schedule started: ON=${config.screenOnTime}, OFF=${config.screenOffTime}")
    }

    fun stop() {
        cancelAlarm(ACTION_SCREEN_ON)
        cancelAlarm(ACTION_SCREEN_OFF)
        Log.i(TAG, "Schedule stopped")
    }

    fun handleAlarm(action: String) {
        if (!config.scheduleEnabled) {
            stop()
            return
        }

        when (action) {
            ACTION_SCREEN_ON -> {
                Log.i(TAG, "Schedule: screen ON alarm fired")
                dispatchScreenCommand(true)
                scheduleNextAlarm(ACTION_SCREEN_ON, config.screenOnTime)
            }
            ACTION_SCREEN_OFF -> {
                Log.i(TAG, "Schedule: screen OFF alarm fired")
                dispatchScreenCommand(false)
                scheduleNextAlarm(ACTION_SCREEN_OFF, config.screenOffTime)
            }
        }
    }

    private fun shouldScreenBeOnNow(): Boolean {
        val now = LocalTime.now()
        val onTime = parseTime(config.screenOnTime, DEFAULT_ON_TIME)
        val offTime = parseTime(config.screenOffTime, DEFAULT_OFF_TIME)
        return SchedulePolicy.shouldBeOn(now, onTime, offTime)
    }

    private fun scheduleNextAlarm(action: String, timeString: String) {
        val fallback = if (action == ACTION_SCREEN_ON) DEFAULT_ON_TIME else DEFAULT_OFF_TIME
        val time = parseTime(timeString, fallback)
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, time.hour)
            set(Calendar.MINUTE, time.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        try {
            if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    getPendingIntent(action)
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
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
        val requestCode = if (action == ACTION_SCREEN_ON) REQUEST_CODE_ON else REQUEST_CODE_OFF
        val intent = Intent(context, ScheduleReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun dispatchScreenCommand(screenOn: Boolean) {
        val intent = Intent(context, KioskService::class.java)
            .setAction(KioskService.ACTION_COMMAND_SCREEN)
            .putExtra(KioskService.EXTRA_SCREEN_ON, screenOn)
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
        private const val REQUEST_CODE_ON = 2001
        private const val REQUEST_CODE_OFF = 2002
        private val DEFAULT_ON_TIME = LocalTime.of(6, 0)
        private val DEFAULT_OFF_TIME = LocalTime.of(23, 0)
    }
}

object SchedulePolicy {
    fun shouldBeOn(now: LocalTime, onTime: LocalTime, offTime: LocalTime): Boolean {
        return if (onTime < offTime) {
            !now.isBefore(onTime) && now.isBefore(offTime)
        } else {
            !now.isBefore(onTime) || now.isBefore(offTime)
        }
    }
}
