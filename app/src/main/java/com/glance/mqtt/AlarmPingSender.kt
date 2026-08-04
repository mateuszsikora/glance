package com.glance.mqtt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
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
 */
class AlarmPingSender(
    appContext: Context,
    clientKey: String
) : MqttPingSender {

    // The receiver outlives individual service callbacks, so it is bound to the application.
    private val context = appContext.applicationContext
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val clientId = MqttContract.sanitizeId(clientKey)

    internal val action = "$ACTION_PREFIX.$clientId"

    private var comms: ClientComms? = null
    private var receiver: BroadcastReceiver? = null

    /** Test seam for the keep-alive round trip owned by Paho's internals. */
    internal var pingHandler: (IMqttActionListener) -> IMqttToken? = { listener ->
        comms?.checkForActivity(listener)
    }

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
        schedule(comms?.keepAlive ?: DEFAULT_KEEP_ALIVE_MS)
    }

    override fun stop() {
        alarmManager.cancel(pendingIntent())
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    override fun schedule(delayInMilliseconds: Long) {
        val triggerAtMs = System.currentTimeMillis() + delayInMilliseconds.coerceAtLeast(0L)
        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        try {
            if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent()
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
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
                    release()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "MQTT keep-alive ping could not be sent (${e.javaClass.simpleName})")
            null
        }

        if (token == null) release()
    }

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
        private const val WAKE_LOCK_TAG = "glance:mqtt_keep_alive"
        private const val PING_TIMEOUT_MS = 10_000L
        private const val DEFAULT_KEEP_ALIVE_MS = 60_000L
    }
}
