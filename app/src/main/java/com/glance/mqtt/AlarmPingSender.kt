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
import com.glance.diagnostics.Diagnostics
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttPingSender
import org.eclipse.paho.client.mqttv3.internal.ClientComms

/**
 * Keep-alive scheduler backed by [AlarmManager].
 *
 * Paho's default sender drives keep-alive pings from a [java.util.Timer], which stops running once
 * the tablet suspends with the screen off. The broker then misses the keep-alive, publishes the
 * retained last will, and Home Assistant marks the light unavailable until something else wakes the
 * device. Wakeup alarms fire through suspend, so the session survives dark hours.
 *
 * Where vendor app hibernation holds those alarms back while the display is off, the keep-alive is
 * an alarm clock instead for as long as the display stays off; see [MqttKeepAliveSettings].
 */
class AlarmPingSender(
    appContext: Context,
    clientKey: String,
    private val alarmClockWhileDisplayOff: () -> Boolean =
        MqttKeepAliveSettings(appContext).let { settings -> { settings.alarmClockWhileDisplayOff } },
    private val journal: (String) -> Unit = { Diagnostics.record(it) }
) : MqttPingSender {

    // The receiver outlives individual service callbacks, so it is bound to the application.
    private val context = appContext.applicationContext
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val clientId = MqttContract.sanitizeId(clientKey)

    internal val action = "$ACTION_PREFIX.$clientId"

    private var comms: ClientComms? = null
    private var receiver: BroadcastReceiver? = null
    private var displayReceiver: BroadcastReceiver? = null

    /** Elapsed realtime the armed alarm is due at; 0 while none is armed. */
    @Volatile private var dueAtElapsedMs = 0L

    /** Test seam for the keep-alive round trip owned by Paho's internals. */
    internal var pingHandler: (IMqttActionListener) -> IMqttToken? = { listener ->
        comms?.checkForActivity(listener)
    }

    /** Test seam for the negotiated keep-alive, in milliseconds; 0 before Paho is attached. */
    internal var keepAliveMs: () -> Long = { comms?.keepAlive ?: 0L }

    override fun init(comms: ClientComms) {
        this.comms = comms
    }

    override fun start() {
        if (receiver == null) {
            val pingReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) = sendPing()
            }
            ContextCompat.registerReceiver(
                context,
                pingReceiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiver = pingReceiver
        }
        if (displayReceiver == null) {
            // The alarm armed while the display was on must become an alarm clock when it goes
            // off, or a hibernating vendor holds it back and no later ping is ever armed.
            val rearmReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    dueAtElapsedMs.takeIf { it > 0L }?.let(::arm)
                }
            }
            context.registerReceiver(
                rearmReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
            )
            displayReceiver = rearmReceiver
        }
        schedule(comms?.keepAlive ?: DEFAULT_KEEP_ALIVE_MS)
    }

    override fun stop() {
        alarmManager.cancel(pendingIntent())
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        displayReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        displayReceiver = null
        dueAtElapsedMs = 0L
    }

    override fun schedule(delayInMilliseconds: Long) {
        // Paho re-arms the alarm the moment it queues a ping, a few milliseconds before the packet
        // is written and becomes the last activity. An alarm delivered exactly on time would then
        // find the next ping not yet due and re-arm for those milliseconds, which alarm batching
        // or idle throttling turns into another full delay. The slack makes it land after the due
        // time. Elapsed realtime keeps the deadline on the same clock as Paho's keep-alive
        // bookkeeping, immune to wall-clock changes.
        arm(SystemClock.elapsedRealtime() + delayInMilliseconds.coerceAtLeast(0L) + PING_SLACK_MS)
    }

    private fun arm(triggerAtMs: Long) {
        dueAtElapsedMs = triggerAtMs
        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        try {
            if (canScheduleExact && !powerManager.isInteractive && alarmClockWhileDisplayOff()) {
                // Alarm clocks run on the wall clock; the deadline stays on elapsed realtime.
                val wallClockMs = System.currentTimeMillis() +
                    (triggerAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(wallClockMs, dashboardIntent()),
                    pendingIntent()
                )
            } else if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMs,
                    pendingIntent()
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMs,
                    pendingIntent()
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to schedule the MQTT keep-alive alarm", e)
        }
    }

    private fun sendPing() {
        // The alarm only guarantees the CPU is awake while onReceive runs; the ping round trip
        // continues on Paho's threads, so it needs its own wake lock.
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock.acquire(PING_TIMEOUT_MS)
        journalLateness()

        fun release() {
            if (wakeLock.isHeld) {
                runCatching { wakeLock.release() }
            }
        }

        val token = try {
            pingHandler(object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = release()

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.w(TAG, "MQTT keep-alive ping failed")
                    journal("MQTT keep-alive ping failed")
                    release()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "MQTT keep-alive ping could not be sent (${e.javaClass.simpleName})")
            null
        }

        if (token == null) release()
    }

    /**
     * The broker closes the session once a keep-alive is half a period overdue, so a ping that
     * late tells why Home Assistant showed the tablet unavailable.
     */
    private fun journalLateness() {
        val due = dueAtElapsedMs
        dueAtElapsedMs = 0L
        val keepAlive = keepAliveMs()
        if (due <= 0L || keepAlive <= 0L) return
        val lateMs = SystemClock.elapsedRealtime() - due
        if (lateMs >= keepAlive / 2) {
            journal("MQTT keep-alive alarm came ${lateMs / 60_000L} min late; the broker may have closed the session")
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
            clientId.hashCode(),
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "AlarmPingSender"
        private const val ACTION_PREFIX = "com.glance.action.MQTT_PING"
        /** Shown when someone opens Glance's next alarm; distinct from the schedule's. */
        private const val REQUEST_CODE_SHOW_DASHBOARD = 2005
        private const val WAKE_LOCK_TAG = "glance:mqtt_keep_alive"
        private const val PING_TIMEOUT_MS = 10_000L
        private const val DEFAULT_KEEP_ALIVE_MS = 60_000L
        internal const val PING_SLACK_MS = 1_000L
    }
}
