package com.glance.mqtt

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/**
 * Retries a lost or not yet established MQTT session on a wakeup alarm until it connects.
 *
 * Paho's automatic reconnect runs on a [java.util.Timer], and the retries before the first
 * connection on a main-thread [android.os.Handler]. Both stop while the processor sleeps, and
 * while EMUI's app hibernation freezes Glance, which happens seconds after the display goes off on
 * battery. Paho also cancels the keep-alive alarm when the connection is lost. Without another
 * wakeup the session could stay down until the display wakes in the morning.
 *
 * The retry alarm is a [DisplayAwareAlarm], set like the keep-alive: an alarm clock while the
 * display is off where [MqttKeepAliveSettings] asks for it, an exact wakeup alarm otherwise. It
 * backs off along [MqttReconnectPolicy.alarmDelayForAttempt] and is re-armed before each
 * [onRetry], so it keeps coming until [stop]. [onRetry] runs on the main thread with a wake lock
 * held long enough for one connection attempt.
 */
class MqttReconnectAlarm(
    appContext: Context,
    clientKey: String,
    alarmClockWhileDisplayOff: () -> Boolean,
    private val onRetry: () -> Unit
) {

    private val context = appContext.applicationContext
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val clientId = MqttContract.sanitizeId(clientKey)

    internal val action = "$ACTION_PREFIX.$clientId"

    private val alarm = DisplayAwareAlarm(
        context,
        action,
        clientId.hashCode(),
        alarmClockWhileDisplayOff,
        onFire = ::fire
    )

    // Not reference counted, so a retry that comes while the previous one still holds it only
    // extends the timeout.
    private val wakeLock = powerManager
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        .apply { setReferenceCounted(false) }

    /** Guards [active] and [attempt]; both Paho's threads and the main thread start and stop it. */
    private val lock = Any()
    private var active = false
    private var attempt = 0

    internal val isActive: Boolean get() = synchronized(lock) { active }

    /** Arms the first retry, unless the alarm is already running; its backoff carries on then. */
    fun start() {
        synchronized(lock) {
            if (active) return
            active = true
            attempt = 0
            alarm.register()
            armNext()
        }
    }

    /** Cancels the retries, as when the session is up again or MQTT stops. */
    fun stop() {
        synchronized(lock) {
            active = false
            attempt = 0
            alarm.release()
        }
        if (wakeLock.isHeld) runCatching { wakeLock.release() }
    }

    private fun fire() {
        synchronized(lock) {
            if (!active) return
            // The next retry is armed first, so a failed attempt needs nothing more to be retried.
            armNext()
        }
        wakeLock.acquire(RETRY_WAKE_LOCK_MS)
        onRetry()
    }

    private fun armNext() {
        alarm.arm(SystemClock.elapsedRealtime() + MqttReconnectPolicy.alarmDelayForAttempt(attempt))
        attempt++
    }

    companion object {
        private const val ACTION_PREFIX = "com.glance.action.MQTT_RECONNECT"
        private const val WAKE_LOCK_TAG = "glance:mqtt_reconnect"
        /** Covers the 10-second connection timeout plus name resolution. */
        internal const val RETRY_WAKE_LOCK_MS = 20_000L
    }
}
