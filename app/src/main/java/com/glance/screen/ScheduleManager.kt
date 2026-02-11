package com.glance.screen

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.glance.config.AppConfig
import java.util.Calendar

/**
 * Manages screen ON/OFF schedule using AlarmManager.
 *
 * Schedules exact alarms for screen-on and screen-off times daily.
 * When an alarm fires, it delegates to [ScreenController].
 */
class ScheduleManager(
    private val context: Context,
    private val config: AppConfig,
    private val screenController: ScreenController
) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private var registered = false

    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SCREEN_ON -> {
                    Log.i(TAG, "Schedule: screen ON alarm fired")
                    screenController.screenOn()
                    scheduleNextAlarm(ACTION_SCREEN_ON, config.screenOnTime)
                }
                ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "Schedule: screen OFF alarm fired")
                    screenController.screenOff()
                    scheduleNextAlarm(ACTION_SCREEN_OFF, config.screenOffTime)
                }
            }
        }
    }

    /**
     * Start the schedule. Registers receiver and schedules next alarms.
     * Call after config changes to reschedule.
     */
    fun start() {
        if (!config.scheduleEnabled) {
            Log.i(TAG, "Schedule disabled in config")
            return
        }

        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_SCREEN_ON)
                addAction(ACTION_SCREEN_OFF)
            }
            context.registerReceiver(alarmReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registered = true
        }

        scheduleNextAlarm(ACTION_SCREEN_ON, config.screenOnTime)
        scheduleNextAlarm(ACTION_SCREEN_OFF, config.screenOffTime)

        // Apply current state immediately based on whether we're in the ON window
        applyCurrentState()

        Log.i(TAG, "Schedule started: ON=${config.screenOnTime}, OFF=${config.screenOffTime}")
    }

    fun stop() {
        cancelAlarm(ACTION_SCREEN_ON)
        cancelAlarm(ACTION_SCREEN_OFF)

        if (registered) {
            context.unregisterReceiver(alarmReceiver)
            registered = false
        }

        Log.i(TAG, "Schedule stopped")
    }

    /**
     * Reschedule (e.g. after config change).
     */
    fun reschedule() {
        stop()
        start()
    }

    /**
     * Determine if we're currently in the "screen on" window and apply immediately.
     */
    private fun applyCurrentState() {
        val now = Calendar.getInstance()
        val onTime = parseTime(config.screenOnTime)
        val offTime = parseTime(config.screenOffTime)

        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val onMinutes = onTime.first * 60 + onTime.second
        val offMinutes = offTime.first * 60 + offTime.second

        val shouldBeOn = if (onMinutes < offMinutes) {
            // Normal: ON at 06:00, OFF at 23:00 → on between 06:00 and 23:00
            nowMinutes in onMinutes until offMinutes
        } else {
            // Overnight: ON at 22:00, OFF at 06:00 → on outside 06:00-22:00
            nowMinutes >= onMinutes || nowMinutes < offMinutes
        }

        if (shouldBeOn) {
            screenController.screenOn()
        } else {
            screenController.screenOff()
        }

        Log.i(TAG, "Current state applied: shouldBeOn=$shouldBeOn (now=$nowMinutes, on=$onMinutes, off=$offMinutes)")
    }

    private fun scheduleNextAlarm(action: String, timeStr: String) {
        val (hour, minute) = parseTime(timeStr)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            // If time already passed today, schedule for tomorrow
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val pendingIntent = getPendingIntent(action)

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )

        Log.d(TAG, "Alarm scheduled: $action at $timeStr (${calendar.time})")
    }

    private fun cancelAlarm(action: String) {
        alarmManager.cancel(getPendingIntent(action))
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val requestCode = if (action == ACTION_SCREEN_ON) REQUEST_CODE_ON else REQUEST_CODE_OFF
        val intent = Intent(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Parse "HH:mm" string to Pair(hour, minute). Falls back to 06:00 / 23:00 on error.
     */
    private fun parseTime(timeStr: String): Pair<Int, Int> {
        return try {
            val parts = timeStr.split(":")
            Pair(parts[0].toInt(), parts[1].toInt())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse time '$timeStr', using default")
            Pair(6, 0)
        }
    }

    companion object {
        private const val TAG = "ScheduleManager"
        const val ACTION_SCREEN_ON = "com.glance.ACTION_SCREEN_ON"
        const val ACTION_SCREEN_OFF = "com.glance.ACTION_SCREEN_OFF"
        private const val REQUEST_CODE_ON = 2001
        private const val REQUEST_CODE_OFF = 2002
    }
}
