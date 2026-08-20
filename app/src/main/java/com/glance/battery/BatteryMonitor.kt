package com.glance.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/** Charge level and power source of the tablet, as reported to Home Assistant. */
data class BatteryStatus(
    val levelPercent: Int,
    val charging: Boolean
)

/**
 * Watches the battery so a smart plug can keep a permanently mounted tablet inside a healthy
 * charge window instead of holding it at 100% for years.
 *
 * `ACTION_BATTERY_CHANGED` cannot be declared in the manifest and fires far more often than the
 * charge level changes, so this monitor lives with the foreground kiosk service and only reports
 * transitions that Home Assistant would actually see.
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
            ),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
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

    fun from(level: Int, scale: Int, status: Int, plugged: Int): BatteryStatus? {
        if (level < 0 || scale <= 0) return null
        return BatteryStatus(
            levelPercent = (level * 100 / scale).coerceIn(0, 100),
            // "Charging" here means the tablet is running on external power: a charge controller
            // needs to know whether its plug is delivering, and a full battery still is.
            charging = plugged != 0 && status != BatteryManager.BATTERY_STATUS_DISCHARGING
        )
    }
}
