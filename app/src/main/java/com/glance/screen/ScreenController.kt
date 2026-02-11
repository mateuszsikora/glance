package com.glance.screen

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Controls screen ON/OFF state.
 *
 * - Screen OFF: uses DevicePolicyManager.lockNow() if device owner,
 *   otherwise sets brightness to 0 as fallback.
 * - Screen ON: acquires a wake lock to wake the screen.
 *   Uses FLAG_KEEP_SCREEN_ON semantics (long-lived, released on screenOff).
 *
 * Supports a [Listener] interface for state change notifications.
 */
class ScreenController(private val context: Context) {

    interface Listener {
        fun onScreenStateChanged(isOn: Boolean)
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var listener: Listener? = null

    /**
     * Returns current screen state, queried from the actual hardware.
     */
    val isScreenOn: Boolean
        get() = powerManager.isInteractive

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Turn the screen on by acquiring a wake lock.
     * The wake lock is held until [screenOff] or [release] is called
     * (no timeout — the kiosk should stay on until explicitly told to turn off).
     */
    fun screenOn() {
        if (isScreenOn) return

        try {
            releaseWakeLock()
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
                WAKE_LOCK_TAG
            ).apply {
                acquire() // No timeout — held until explicitly released
            }
            Log.i(TAG, "Screen turned ON")
            listener?.onScreenStateChanged(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn screen on", e)
        }
    }

    /**
     * Turn the screen off. Prefers DevicePolicyManager.lockNow() (requires device owner).
     * Falls back to setting brightness to 0.
     */
    fun screenOff() {
        if (!isScreenOn) return

        try {
            releaseWakeLock()

            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.lockNow()
                Log.i(TAG, "Screen turned OFF via DevicePolicyManager")
            } else {
                // Fallback: set brightness to minimum (won't actually lock the screen)
                try {
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        0
                    )
                    Log.i(TAG, "Screen dimmed to 0 (fallback, not device owner)")
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback brightness-off failed", e)
                }
            }
            listener?.onScreenStateChanged(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn screen off", e)
        }
    }

    fun toggle() {
        if (isScreenOn) screenOff() else screenOn()
    }

    fun release() {
        releaseWakeLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
    }

    companion object {
        private const val TAG = "ScreenController"
        private const val WAKE_LOCK_TAG = "glance:screen_wake"
    }
}
