package com.glance.brightness

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.glance.config.AppConfig
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads ambient light sensor and adjusts screen brightness using
 * exponential moving average (EMA) smoothing and logarithmic lux-to-brightness mapping.
 *
 * Supports:
 * - [Listener] for brightness change notifications (used by MQTT state publishing)
 * - Remote override mode pauses auto-brightness for [OVERRIDE_DURATION_MS].
 * - Soft screen-off mode forces window brightness to zero and pauses the sensor until wake-up.
 */
class BrightnessController(
    private val context: Context,
    private val config: AppConfig
) : SensorEventListener {

    interface Listener {
        fun onBrightnessChanged(brightness: Int)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var emaLux: Float = -1f
    private var running = false
    private var listener: Listener? = null
    private var lastAppliedBrightness = -1
    private var screenOffMode = false

    // HA override: when set, auto-brightness is paused until this time
    private var overrideUntilMs = 0L

    // Activity window reference for per-window brightness
    private var activityWindow: android.view.Window? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Start listening to light sensor. Pass the Activity window to control
     * per-window brightness (preferred), or null to use system settings (requires WRITE_SETTINGS).
     */
    fun start(window: android.view.Window? = null) {
        activityWindow = window

        if (!config.autoBrightnessEnabled) {
            Log.i(TAG, "Auto brightness disabled in config")
            return
        }

        if (lightSensor == null) {
            Log.w(TAG, "No light sensor available on this device")
            return
        }

        if (running) return

        emaLux = -1f
        sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        running = true
        Log.i(TAG, "BrightnessController started (min=${config.minBrightness}, max=${config.maxBrightness})")
    }

    fun stop() {
        if (running) {
            sensorManager.unregisterListener(this)
            running = false
        }
        activityWindow = null
        Log.i(TAG, "BrightnessController stopped")
    }

    val isRunning: Boolean get() = running
    val currentBrightness: Int get() = lastAppliedBrightness

    // --- SensorEventListener ---

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT) return
        if (screenOffMode) return

        // Skip if HA override is active
        if (System.currentTimeMillis() < overrideUntilMs) return

        val rawLux = event.values[0]

        // Apply EMA smoothing
        emaLux = if (emaLux < 0f) {
            rawLux
        } else {
            EMA_ALPHA * rawLux + (1f - EMA_ALPHA) * emaLux
        }

        val brightness = luxToBrightness(emaLux)

        // Only apply + notify if brightness actually changed
        if (brightness != lastAppliedBrightness) {
            applyBrightness(brightness)
            lastAppliedBrightness = brightness
            config.lastKnownBrightness = brightness
            listener?.onBrightnessChanged(brightness)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    // --- Mapping ---

    /**
     * Maps lux value to brightness (0-255) using logarithmic curve.
     */
    private fun luxToBrightness(lux: Float): Int {
        val minB = min(config.minBrightness, config.maxBrightness)
        val maxB = max(config.minBrightness, config.maxBrightness)

        if (lux <= 0f) return minB

        val clampedLux = min(lux, MAX_LUX)
        val normalized = ln(1.0 + clampedLux) / ln(1.0 + MAX_LUX)
        val brightness = minB + ((maxB - minB) * normalized).roundToInt()

        return max(minB, min(maxB, brightness))
    }

    // --- Apply ---

    private fun applyBrightness(brightness: Int) {
        val normalized = brightness / 255f

        // Prefer per-window brightness (no special permission needed)
        activityWindow?.let { window ->
            // Must run on main thread
            mainHandler.post {
                val params = window.attributes
                params.screenBrightness = normalized
                window.attributes = params
            }
            return
        }

        // Fallback: system brightness (requires WRITE_SETTINGS permission)
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set system brightness", e)
        }
    }

    /**
     * Manually set brightness (e.g. from settings or internal use).
     */
    fun setBrightness(brightness: Int) {
        val clamped = clampToConfiguredRange(brightness)
        lastAppliedBrightness = clamped
        config.lastKnownBrightness = clamped
        if (!screenOffMode) {
            applyBrightness(clamped)
        }
        listener?.onBrightnessChanged(clamped)
    }

    /**
     * Set brightness from HA command. Pauses auto-brightness for [OVERRIDE_DURATION_MS]
     * so the HA-commanded value isn't immediately overridden by the sensor.
     */
    fun setBrightnessFromRemote(brightness: Int) {
        val clamped = clampToConfiguredRange(brightness)
        overrideUntilMs = System.currentTimeMillis() + OVERRIDE_DURATION_MS
        lastAppliedBrightness = clamped
        config.lastKnownBrightness = clamped
        if (!screenOffMode) {
            applyBrightness(clamped)
        }
        listener?.onBrightnessChanged(clamped)
        Log.i(TAG, "Remote brightness override: $clamped (pausing auto for ${OVERRIDE_DURATION_MS / 1000}s)")
    }

    fun enterScreenOffMode() {
        if (screenOffMode) return
        screenOffMode = true
        applyBrightnessRaw(0)
        Log.i(TAG, "Soft screen-off brightness applied")
    }

    fun exitScreenOffMode() {
        if (!screenOffMode) return
        screenOffMode = false
        val restore = if (lastAppliedBrightness >= 0) {
            lastAppliedBrightness
        } else {
            config.minBrightness.coerceIn(1, 255)
        }
        applyBrightness(restore)
        Log.i(TAG, "Brightness restored after soft screen-off: $restore")
    }

    private fun applyBrightnessRaw(brightness: Int) {
        val normalized = brightness.coerceIn(0, 255) / 255f
        activityWindow?.let { window ->
            mainHandler.post {
                val params = window.attributes
                params.screenBrightness = normalized
                window.attributes = params
            }
            return
        }
        applyBrightness(brightness.coerceIn(0, 255))
    }

    private fun clampToConfiguredRange(brightness: Int): Int {
        val lower = min(config.minBrightness, config.maxBrightness)
        val upper = max(config.minBrightness, config.maxBrightness)
        return brightness.coerceIn(lower, upper)
    }

    companion object {
        private const val TAG = "BrightnessController"

        // EMA smoothing factor (0-1). Lower = smoother but slower response.
        private const val EMA_ALPHA = 0.15f

        // Max lux for mapping curve. Anything above this maps to maxBrightness.
        private const val MAX_LUX = 1000f

        // How long to pause auto-brightness after an HA override command (5 minutes)
        private const val OVERRIDE_DURATION_MS = 5 * 60 * 1000L
    }
}
