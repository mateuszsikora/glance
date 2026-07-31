package com.glance.screen

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.glance.brightness.BrightnessController

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
class ScreenController(
    private val context: Context,
    private val brightnessController: BrightnessController? = null,
    private val overlayHost: ViewGroup? = null
) {

    interface Listener {
        fun onScreenStateChanged(isOn: Boolean)
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var listener: Listener? = null
    private var softScreenOff = false
    private var softOffOverlay: View? = null

    /**
     * Returns current screen state, queried from the actual hardware.
     */
    val isScreenOn: Boolean
        get() = !softScreenOff && powerManager.isInteractive

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Turn the screen on by acquiring a wake lock.
     * The wake lock only bridges the physical wake-up. MainActivity's keep-screen-on flag keeps
     * the display awake afterwards, while the timeout guarantees cleanup after an exception.
     */
    fun screenOn() {
        if (softScreenOff) {
            softScreenOff = false
            mainHandler.post {
                softOffOverlay?.let { overlayHost?.removeView(it) }
                softOffOverlay = null
                brightnessController?.exitScreenOffMode()
            }
            Log.i(TAG, "Screen turned ON from soft-off")
            listener?.onScreenStateChanged(true)
            return
        }

        if (powerManager.isInteractive) return

        try {
            releaseWakeLock()
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
                WAKE_LOCK_TAG
            ).apply {
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            mainHandler.postDelayed(::releaseWakeLock, WAKE_LOCK_RELEASE_DELAY_MS)
            Log.i(TAG, "Screen turned ON")
            listener?.onScreenStateChanged(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn screen on", e)
        }
    }

    /**
     * Turn the screen off. Prefers DevicePolicyManager.lockNow() (requires device owner).
     * Falls back to a reversible soft-off: black touch-to-wake overlay plus window brightness 0.
     */
    fun screenOff() {
        if (!isScreenOn) return

        try {
            releaseWakeLock()

            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.lockNow()
                Log.i(TAG, "Screen turned OFF via DevicePolicyManager")
            } else {
                enterSoftScreenOff()
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
        softScreenOff = false
        softOffOverlay?.let { overlayHost?.removeView(it) }
        softOffOverlay = null
        brightnessController?.exitScreenOffMode()
    }

    private fun enterSoftScreenOff() {
        if (softScreenOff) return
        softScreenOff = true
        brightnessController?.enterScreenOffMode()

        mainHandler.post {
            if (softOffOverlay != null) return@post
            softOffOverlay = View(context).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                contentDescription = "Screen off. Tap to wake."
                setOnClickListener { screenOn() }
            }
            overlayHost?.addView(
                softOffOverlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        Log.i(TAG, "Screen soft-OFF applied (tap or remote command to wake)")
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
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
        private const val WAKE_LOCK_RELEASE_DELAY_MS = 3_000L
    }
}
