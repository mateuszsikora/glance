package com.glance.mqtt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.glance.MainActivity

/**
 * One wakeup alarm on elapsed realtime that EMUI's app hibernation still delivers.
 *
 * While the display is off and [alarmClockWhileDisplayOff] asks for it, the alarm is an alarm
 * clock; otherwise it is an exact wakeup alarm that fires through Doze. Whenever the display turns
 * off or on, the armed alarm is installed again in the other form: one armed with the display on
 * would otherwise be held back by a hibernating vendor once the display goes dark. An alarm clock
 * runs on the wall clock, so a time change installs it again as well.
 *
 * [onFire] runs on the main thread, with the processor awake only while it runs.
 */
internal class DisplayAwareAlarm(
    appContext: Context,
    private val action: String,
    private val requestCode: Int,
    private val alarmClockWhileDisplayOff: () -> Boolean,
    private val onFire: () -> Unit
) {

    // The receivers outlive individual service callbacks, so they are bound to the application.
    private val context = appContext.applicationContext
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var receiver: BroadcastReceiver? = null
    private var displayReceiver: BroadcastReceiver? = null

    /**
     * Guards [dueAtElapsedMs] and the alarm installed for it. Callers arm and cancel from their
     * own threads while the display receiver re-arms on the main thread; both install the same
     * PendingIntent, so without it an ordinary alarm chosen just before the display went off could
     * replace the alarm clock, or a re-arm could revive an alarm that was just cancelled.
     */
    private val lock = Any()

    /** Elapsed realtime the armed alarm is due at; 0 while none is armed. */
    private var dueAtElapsedMs = 0L

    /** Registers the receivers, once; [arm] does nothing useful before. */
    fun register() {
        if (receiver == null) {
            val fireReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) = onFire()
            }
            ContextCompat.registerReceiver(
                context,
                fireReceiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiver = fireReceiver
        }
        if (displayReceiver == null) {
            val rearmReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    synchronized(lock) { dueAtElapsedMs.takeIf { it > 0L }?.let(::arm) }
                }
            }
            context.registerReceiver(
                rearmReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_TIME_CHANGED)
                }
            )
            displayReceiver = rearmReceiver
        }
    }

    /** Cancels the alarm and unregisters the receivers. */
    fun release() {
        cancel()
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        displayReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        displayReceiver = null
    }

    fun cancel() {
        synchronized(lock) {
            alarmManager.cancel(pendingIntent())
            dueAtElapsedMs = 0L
        }
    }

    /** Returns the elapsed realtime the alarm was due at, or 0, and forgets it. */
    fun takeDue(): Long = synchronized(lock) {
        dueAtElapsedMs.also { dueAtElapsedMs = 0L }
    }

    fun arm(triggerAtElapsedMs: Long) {
        // Read before taking the lock where possible, since the first read may wait for the
        // preferences file to load. The display receiver already holds it, on the main thread.
        val alarmClockAllowed = alarmClockWhileDisplayOff()
        synchronized(lock) {
            dueAtElapsedMs = triggerAtElapsedMs
            val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()

            try {
                if (canScheduleExact && !powerManager.isInteractive && alarmClockAllowed) {
                    // Alarm clocks run on the wall clock; the deadline stays on elapsed realtime.
                    val wallClockMs = System.currentTimeMillis() +
                        (triggerAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(wallClockMs, dashboardIntent()),
                        pendingIntent()
                    )
                } else if (canScheduleExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtElapsedMs,
                        pendingIntent()
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtElapsedMs,
                        pendingIntent()
                    )
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Unable to schedule an MQTT alarm", e)
            }
        }
    }

    private fun dashboardIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE_SHOW_DASHBOARD,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun pendingIntent(): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val TAG = "DisplayAwareAlarm"
        /** Shown when someone opens Glance's next alarm; distinct from the schedule's. */
        const val REQUEST_CODE_SHOW_DASHBOARD = 2005
    }
}
