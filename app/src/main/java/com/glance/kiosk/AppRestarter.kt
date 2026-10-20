package com.glance.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.glance.MainActivity
import com.glance.diagnostics.Diagnostics

/**
 * Restarts the whole app process: the only way to release the native memory a long-lived WebView
 * accumulates (see [com.glance.maintenance.NightlyMaintenancePolicy]).
 *
 * Glance is usually not the device's home app, so nothing brings the dashboard back once its
 * process ends. An alarm relaunches it a few seconds later. As a fallback, Android restarts the
 * sticky [KioskService], whose first start in the new process launches the dashboard as well. The
 * new [MainActivity] enters lock task mode and starts both services as on any launch.
 *
 * Requires Device Owner: from Android 10 on, an app may start an Activity from the background
 * only with an exemption such as being Device Owner. Android 8 and 9 have no such restriction, but
 * without Device Owner there is no lock task either, and a relaunch that did not come back would
 * leave the tablet on its home screen; Glance restarts only where it can always return.
 *
 * While the display is on, it is turned off first, so the few seconds without a dashboard show a
 * dark screen rather than the home screen, and turned back on by the new process.
 */
object AppRestarter {

    private const val TAG = "AppRestarter"
    private const val PREFS_NAME = "glance_restart_state"
    private const val KEY_RELAUNCH_PENDING = "relaunch_pending"
    private const val KEY_WAKE_DISPLAY = "wake_display"
    private const val REQUEST_CODE_RELAUNCH = 2101
    /** Lets the journal thread and pending preference writes reach storage first. */
    private const val KILL_DELAY_MS = 1_000L
    /** Long enough for Android to have cleaned up the old process before the alarm fires. */
    private const val RELAUNCH_DELAY_MS = 4_000L

    /** What the first process after [restart] has to restore. */
    data class Relaunch(val wakeDisplay: Boolean)

    /** Set once this process is about to end; its display-off report is not a real state. */
    @Volatile
    var restartInProgress = false
        internal set

    /** @param displayOn whether the display is on now and has to be on again afterwards. */
    fun restart(context: Context, reason: String, displayOn: Boolean) {
        val app = context.applicationContext
        Log.i(TAG, "Restarting the app process ($reason)")
        Diagnostics.record("Restarting the app process ($reason)", includeMemory = true)
        restartInProgress = true
        markRelaunch(app, wakeDisplay = displayOn)
        scheduleRelaunch(app)
        if (displayOn) {
            try {
                app.getSystemService(DevicePolicyManager::class.java).lockNow()
            } catch (e: Exception) {
                Log.w(TAG, "Could not turn the display off before the restart", e)
            }
        }
        Handler(Looper.getMainLooper()).postDelayed(
            { Process.killProcess(Process.myPid()) },
            KILL_DELAY_MS
        )
    }

    /** @return the pending relaunch once, in the first process after [restart]; null otherwise. */
    fun consumePendingRelaunch(context: Context): Relaunch? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_RELAUNCH_PENDING, false)) return null
        val relaunch = Relaunch(wakeDisplay = prefs.getBoolean(KEY_WAKE_DISPLAY, false))
        prefs.edit().remove(KEY_RELAUNCH_PENDING).remove(KEY_WAKE_DISPLAY).commit()
        return relaunch
    }

    /** Once the dashboard is back, the alarm would only start it a second time. */
    fun cancelRelaunchAlarm(context: Context) {
        val relaunch = PendingIntent.getActivity(
            context.applicationContext,
            REQUEST_CODE_RELAUNCH,
            relaunchIntent(context.applicationContext),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(relaunch)
        relaunch.cancel()
    }

    internal fun markRelaunch(context: Context, wakeDisplay: Boolean) {
        // commit(), not apply(): the marker must be on storage before the process is killed.
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RELAUNCH_PENDING, true)
            .putBoolean(KEY_WAKE_DISPLAY, wakeDisplay)
            .commit()
    }

    /**
     * Targeting API 35 or later, an alarm-sent PendingIntent also needs the creator's explicit
     * background-activity-start opt-in (ActivityOptions). Until then the KioskService fallback
     * would still bring the dashboard back.
     */
    private fun scheduleRelaunch(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val relaunch = PendingIntent.getActivity(
            context,
            REQUEST_CODE_RELAUNCH,
            relaunchIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = SystemClock.elapsedRealtime() + RELAUNCH_DELAY_MS
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    relaunch
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    relaunch
                )
            }
        } catch (e: SecurityException) {
            // The KioskService fallback still brings the dashboard back.
            Log.e(TAG, "Failed to schedule the relaunch alarm", e)
        }
    }

    private fun relaunchIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
