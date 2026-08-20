package com.glance.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/** Charge level of the tablet and whether that charge is currently rising. */
data class BatteryStatus(
    val levelPercent: Int,
    val charging: Boolean
)

/**
 * Watches the battery so a smart plug can keep a permanently mounted tablet inside a healthy
 * charge window instead of holding it at 100% for years.
 *
 * `ACTION_BATTERY_CHANGED` cannot be declared in the manifest and fires far more often than the
 * charge level or charging state changes, so this monitor lives with the foreground kiosk service
 * and only reports transitions that Home Assistant would actually see.
 */
class BatteryMonitor(
    private val context: Context,
    private val onChanged: (BatteryStatus) -> Unit
) {

    @Volatile
    var status: BatteryStatus? = null
        private set

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(::handleBatteryIntent)
        }
    }

    fun start() {
        if (registered) return
        try {
            // The sticky broadcast returns the current state immediately, so the first MQTT
            // publication does not have to wait for the next battery event.
            val sticky = context.registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            registered = true
            sticky?.let(::handleBatteryIntent)
            Log.i(TAG, "Battery monitor started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start battery monitor", e)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "Battery receiver was already unregistered") }
    }

    private fun handleBatteryIntent(intent: Intent) {
        val reading = BatteryReading.from(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            status = intent.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN
            )
        ) ?: return

        if (reading == status) return
        status = reading
        onChanged(reading)
    }

    companion object {
        private const val TAG = "BatteryMonitor"
    }
}

/** Pure translation of the platform's battery extras, kept separate so it can be unit tested. */
object BatteryReading {

    fun from(level: Int, scale: Int, status: Int): BatteryStatus? {
        if (level < 0 || scale <= 0) return null
        return BatteryStatus(
            levelPercent = (level * 100 / scale).coerceIn(0, 100),
            // Home Assistant's battery_charging class means the charge is actually progressing,
            // so only the platform's charging status qualifies. A connected charger that has been
            // paused by temperature or a vendor charge limit reports NOT_CHARGING, and reporting
            // that as charging would hide exactly the situation worth seeing.
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        )
    }
}
