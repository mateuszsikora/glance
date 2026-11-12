package com.glance.screen

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.glance.MainActivity
import com.glance.config.AppConfig
import com.glance.diagnostics.Diagnostics
import com.glance.kiosk.KioskService
import com.glance.maintenance.MaintenanceSettings
import com.glance.maintenance.NightlyMaintenancePolicy
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar

/**
 * Persistent daily screen schedule and the nightly maintenance restart or reboot placed inside it.
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

    /**
     * @param scheduledAtMs when the alarm was due, to journal one EMUI held back and to count a
     *   late night's maintenance from its time; null if unknown.
     * @param nowMs when it arrived.
     */
    fun handleAlarm(
        action: String,
        scheduledAtMs: Long? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        recordIfLate(action, scheduledAtMs, nowMs)
        if (config.isKioskSuspended) {
            stop()
            Log.i(TAG, "Ignoring schedule alarm while kiosk is suspended")
            return
        }
        if (action == ACTION_NIGHTLY_MAINTENANCE) {
            Log.i(TAG, "Nightly maintenance alarm fired")
            scheduleNightlyMaintenance()
            if (!maintenance.anyScheduled) return
            val lateMs = scheduledAtMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
            val arrivedAt = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalTime()
            if (NightlyMaintenancePolicy.skipForTheNight(lateMs, offWindow(), arrivedAt)) {
                Diagnostics.record("Nightly maintenance skipped for tonight; its alarm came too late")
                return
            }
            dispatchKioskCommand(
                Intent(context, KioskService::class.java)
                    .setAction(KioskService.ACTION_NIGHTLY_MAINTENANCE)
                    .putExtra(KioskService.EXTRA_MAINTENANCE_LATE_MS, lateMs)
            )
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

    private fun recordIfLate(action: String, scheduledAtMs: Long?, nowMs: Long) {
        val minutes = lateMinutes(scheduledAtMs ?: return, nowMs) ?: return
        val name = when (action) {
            ACTION_SCREEN_ON -> "Screen ON"
            ACTION_SCREEN_OFF -> "Screen OFF"
            ACTION_NIGHTLY_MAINTENANCE -> "Nightly maintenance"
            else -> return
        }
        Diagnostics.record("$name alarm arrived $minutes min late")
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
        if (!maintenance.anyScheduled) {
            cancelAlarm(ACTION_NIGHTLY_MAINTENANCE)
            return
        }
        val runTime = NightlyMaintenancePolicy.runTime(
            offWindow(),
            parseTime(maintenance.quietTime, DEFAULT_QUIET_TIME)
        )
        val nextRun = NightlyMaintenancePolicy.nextRun(LocalDateTime.now(), runTime)
        setAlarm(
            ACTION_NIGHTLY_MAINTENANCE,
            nextRun.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        Log.i(TAG, "Nightly maintenance scheduled for $nextRun")
    }

    private fun offWindow(): NightlyMaintenancePolicy.ScreenOffWindow? =
        if (config.scheduleEnabled) {
            NightlyMaintenancePolicy.ScreenOffWindow(
                on = parseTime(config.screenOnTime, DEFAULT_ON_TIME),
                off = parseTime(config.screenOffTime, DEFAULT_OFF_TIME)
            )
        } else {
            null
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
        // Only an exact alarm has a due time to be late against; Doze may defer the other kind.
        val operation = getPendingIntent(action, triggerAtMillis.takeIf { canScheduleExact })

        try {
            if (canScheduleExact && action != ACTION_SCREEN_OFF) {
                // With the display off EMUI may hold every ordinary alarm of Glance back until
                // morning: after a reboot inside the OFF window the screen-on came only when
                // someone woke the tablet by hand, and on another night the maintenance alarm,
                // due 15 minutes after the OFF, came at the 06:25 screen-on. An alarm clock is what
                // EMUI and Doze deliver on time. The OFF is due while the display is still on.
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAtMillis, dashboardIntent()),
                    operation
                )
            } else if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    operation
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    operation
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to schedule alarm for $action", e)
        }
    }

    private fun cancelAlarm(action: String) {
        alarmManager.cancel(getPendingIntent(action))
    }

    /** @param triggerAtMillis travels along to measure lateness; extras do not affect matching. */
    private fun getPendingIntent(action: String, triggerAtMillis: Long? = null): PendingIntent {
        val requestCode = when (action) {
            ACTION_SCREEN_ON -> REQUEST_CODE_ON
            ACTION_SCREEN_OFF -> REQUEST_CODE_OFF
            else -> REQUEST_CODE_MAINTENANCE
        }
        val intent = Intent(action).setClass(context, ScheduleReceiver::class.java)
        if (triggerAtMillis != null) intent.putExtra(EXTRA_SCHEDULED_AT, triggerAtMillis)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** What the system opens when someone taps the alarm clock it shows. */
    private fun dashboardIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE_SHOW_DASHBOARD,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

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
        const val EXTRA_SCHEDULED_AT = "com.glance.extra.SCHEDULED_AT"
        private const val REQUEST_CODE_ON = 2001
        private const val REQUEST_CODE_OFF = 2002
        private const val REQUEST_CODE_MAINTENANCE = 2003
        private const val REQUEST_CODE_SHOW_DASHBOARD = 2004
        /** Exact alarms land within seconds; EMUI's screen-off batching adds up to about 5 min. */
        private const val LATE_THRESHOLD_MS = 10 * 60_000L
        private val DEFAULT_ON_TIME = LocalTime.of(6, 0)
        private val DEFAULT_OFF_TIME = LocalTime.of(23, 0)
        private val DEFAULT_QUIET_TIME = LocalTime.parse(MaintenanceSettings.DEFAULT_QUIET_TIME)

        /** @return whole minutes late, or null while the delay is ordinary batching. */
        fun lateMinutes(scheduledAtMs: Long, nowMs: Long): Long? {
            val lateMs = nowMs - scheduledAtMs
            return if (lateMs >= LATE_THRESHOLD_MS) lateMs / 60_000L else null
        }
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
