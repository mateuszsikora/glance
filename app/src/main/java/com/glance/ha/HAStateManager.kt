package com.glance.ha

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glance.brightness.BrightnessController
import com.glance.config.AppConfig
import com.glance.screen.ScreenController
import org.json.JSONObject

/**
 * Bridges the tablet state with Home Assistant entities.
 *
 * Bidirectional sync:
 * - Tablet → HA: publishes screen state and brightness via [ScreenController.Listener]
 *   and [BrightnessController.Listener]
 * - HA → Tablet: receives commands from subscribed entity state changes
 *
 * Feedback loop protection: uses [suppressPublishUntilMs] to avoid
 * HA command → local change → publish to HA → HA event → local change → ...
 */
class HAStateManager(
    private val context: Context,
    private val config: AppConfig,
    private val screenController: ScreenController,
    private val brightnessController: BrightnessController
) : HAWebSocketClient.Listener,
    ScreenController.Listener,
    BrightnessController.Listener {

    private var wsClient: HAWebSocketClient? = null
    private var running = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Feedback loop protection: suppress publishing for a short time after receiving a command
    private var suppressScreenPublishUntilMs = 0L
    private var suppressBrightnessPublishUntilMs = 0L

    // Entity IDs from config
    private val entityScreen: String get() = config.haEntityScreen
    private val entityBrightness: String get() = config.haEntityBrightness

    /**
     * Start the HA integration. Connects WebSocket, subscribes to entities,
     * and registers as listener on ScreenController and BrightnessController.
     */
    fun start() {
        if (!config.haIntegrationEnabled) {
            Log.i(TAG, "HA integration disabled")
            return
        }

        if (config.haBaseUrl.isBlank() || config.haAccessToken.isBlank()) {
            Log.w(TAG, "HA URL or token is empty, skipping")
            return
        }

        // Register as listener for local state changes
        screenController.setListener(this)
        brightnessController.setListener(this)

        wsClient = HAWebSocketClient(config.haBaseUrl, config.haAccessToken).apply {
            setListener(this@HAStateManager)
            connect()
        }
        running = true
        Log.i(TAG, "HAStateManager started (screen=$entityScreen, brightness=$entityBrightness)")
    }

    fun stop() {
        screenController.setListener(null)
        brightnessController.setListener(null)
        wsClient?.destroy()
        wsClient = null
        running = false
        Log.i(TAG, "HAStateManager stopped")
    }

    val isRunning: Boolean get() = running
    val isConnected: Boolean get() = wsClient?.isConnected == true

    // --- Publish state to HA ---

    private fun publishScreenState(isOn: Boolean) {
        if (System.currentTimeMillis() < suppressScreenPublishUntilMs) {
            Log.d(TAG, "Suppressing screen state publish (feedback loop protection)")
            return
        }
        wsClient?.setInputBoolean(entityScreen, isOn)
        Log.d(TAG, "Published screen state: ${if (isOn) "on" else "off"}")
    }

    private fun publishBrightness(brightness: Int) {
        if (System.currentTimeMillis() < suppressBrightnessPublishUntilMs) {
            Log.d(TAG, "Suppressing brightness publish (feedback loop protection)")
            return
        }
        wsClient?.setInputNumber(entityBrightness, brightness)
        Log.d(TAG, "Published brightness: $brightness")
    }

    // --- ScreenController.Listener ---

    override fun onScreenStateChanged(isOn: Boolean) {
        publishScreenState(isOn)
    }

    // --- BrightnessController.Listener ---

    override fun onBrightnessChanged(brightness: Int) {
        publishBrightness(brightness)
    }

    // --- HAWebSocketClient.Listener ---

    override fun onConnected() {
        Log.i(TAG, "Connected to HA, subscribing to entities")
        wsClient?.subscribeToEntity(entityScreen)
        wsClient?.subscribeToEntity(entityBrightness)

        // Publish current state on connect
        publishScreenState(screenController.isScreenOn)
        val currentBrightness = brightnessController.currentBrightness
        if (currentBrightness >= 0) {
            publishBrightness(currentBrightness)
        }
    }

    override fun onDisconnected(reason: String) {
        Log.w(TAG, "Disconnected from HA: $reason")
    }

    override fun onAuthResult(success: Boolean) {
        if (success) {
            Log.i(TAG, "HA auth successful")
        } else {
            Log.e(TAG, "HA auth failed")
        }
    }

    override fun onStateChanged(entityId: String, newState: String, attributes: JSONObject) {
        Log.d(TAG, "Entity changed: $entityId -> $newState")

        when (entityId) {
            entityScreen -> handleScreenCommand(newState)
            entityBrightness -> handleBrightnessCommand(newState)
        }
    }

    override fun onError(error: String) {
        Log.e(TAG, "HA error: $error")
    }

    // --- Handle commands from HA (dispatched to main thread) ---

    private fun handleScreenCommand(state: String) {
        // Suppress publish for a short time to prevent feedback loop
        suppressScreenPublishUntilMs = System.currentTimeMillis() + SUPPRESS_DURATION_MS

        mainHandler.post {
            when (state) {
                "on" -> {
                    if (!screenController.isScreenOn) {
                        Log.i(TAG, "HA command: screen ON")
                        screenController.screenOn()
                    }
                }
                "off" -> {
                    if (screenController.isScreenOn) {
                        Log.i(TAG, "HA command: screen OFF")
                        screenController.screenOff()
                    }
                }
            }
        }
    }

    private fun handleBrightnessCommand(state: String) {
        suppressBrightnessPublishUntilMs = System.currentTimeMillis() + SUPPRESS_DURATION_MS

        mainHandler.post {
            try {
                val brightness = state.toFloat().toInt()
                if (brightness in 0..255) {
                    Log.i(TAG, "HA command: brightness $brightness")
                    brightnessController.setBrightnessFromHA(brightness)
                }
            } catch (e: NumberFormatException) {
                Log.w(TAG, "Invalid brightness value from HA: $state")
            }
        }
    }

    companion object {
        private const val TAG = "HAStateManager"

        // How long to suppress publishing after receiving a command from HA (prevents feedback loops)
        private const val SUPPRESS_DURATION_MS = 3_000L
    }
}
