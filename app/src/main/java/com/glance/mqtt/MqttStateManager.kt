package com.glance.mqtt

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glance.BuildConfig
import com.glance.battery.BatteryStatus
import com.glance.config.AppConfig
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class MqttReportedState(
    val screenOn: Boolean,
    val brightness: Int,
    val battery: BatteryStatus? = null
)

/**
 * Lifecycle-independent Home Assistant MQTT integration.
 *
 * The foreground kiosk service owns this class. UI and hardware concerns are supplied through
 * [stateProvider] and [commandHandler], so MQTT stays connected while MainActivity is recreated.
 */
class MqttStateManager(
    private val context: Context,
    private val config: AppConfig,
    private val stateProvider: () -> MqttReportedState,
    private val commandHandler: (MqttLightCommand) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceId = config.mqttDeviceId
    private val clientId = "glance_${MqttContract.sanitizeId(deviceId)}"
    private val topics = MqttContract.topics(config.mqttDiscoveryPrefix, deviceId)
    private val serverUri by lazy {
        MqttEndpoint.serverUri(config.mqttBrokerHost, config.mqttBrokerPort)
    }

    private var client: MqttAsyncClient? = null
    private var pingSender: AlarmPingSender? = null
    private var connectOptions: MqttConnectOptions? = null
    @Volatile private var running = false
    @Volatile private var connecting = false
    private var initialReconnectAttempt = 0
    private var cleanupOnly = false

    fun start() {
        if (running) return
        if (config.mqttBrokerHost.isBlank()) {
            Log.w(TAG, "MQTT broker host is empty")
            return
        }

        try {
            val pendingCleanup = config.pendingDiscoveryCleanupTopics(serverUri).isNotEmpty()
            if (!config.mqttEnabled && !pendingCleanup) {
                Log.i(TAG, "MQTT integration disabled")
                return
            }
            cleanupOnly = !config.mqttEnabled
            val alarmPingSender = AlarmPingSender(context, clientId)
            val mqttClient = MqttAsyncClient(
                serverUri,
                clientId,
                MemoryPersistence(),
                alarmPingSender
            )
            mqttClient.setCallback(createCallback(mqttClient))
            client = mqttClient
            pingSender = alarmPingSender
            connectOptions = buildConnectOptions()
            running = true
            connectWithInitialRetry()
        } catch (e: Exception) {
            running = false
            Log.e(TAG, "Failed to start MQTT (${failureKind(e)})")
        }
    }

    fun stop() {
        running = false
        connecting = false
        mainHandler.removeCallbacksAndMessages(null)

        val mqttClient = client
        val alarmPingSender = pingSender
        client = null
        pingSender = null
        connectOptions = null
        if (mqttClient != null) {
            try {
                if (mqttClient.isConnected) {
                    mqttClient.publish(
                        topics.availability,
                        message("offline", retained = true)
                    ).waitForCompletion(DISCONNECT_TIMEOUT_MS)
                    mqttClient.disconnect(DISCONNECT_TIMEOUT_MS)
                        .waitForCompletion(DISCONNECT_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                Log.w(TAG, "MQTT shutdown did not complete cleanly (${failureKind(e)})")
            } finally {
                try {
                    // A configuration reload can race an asynchronous connect. Forced close is
                    // required here; otherwise the abandoned client keeps reconnecting with the
                    // same client ID and the broker continuously evicts the replacement client.
                    mqttClient.close(true)
                } catch (e: Exception) {
                    Log.w(TAG, "MQTT client could not be closed (${failureKind(e)})")
                }
            }
        }
        // A forced close skips Paho's own teardown, so the keep-alive alarm and its receiver are
        // released here instead of leaking into the next manager instance.
        alarmPingSender?.stop()
        Log.i(TAG, "MQTT manager stopped")
    }

    /**
     * Removes every retained discovery entry before switching prefix/device configuration.
     */
    fun removeDiscovery(onComplete: () -> Unit = {}) {
        val cleanupServerUri = runCatching { serverUri }.getOrNull()
        if (cleanupServerUri == null) {
            mainHandler.post(onComplete)
            return
        }

        val discoveryTopics = topics.discoveryTopics
        val mqttClient = client
        if (mqttClient?.isConnected != true) {
            discoveryTopics.forEach { config.queueDiscoveryCleanup(cleanupServerUri, it) }
            Log.w(TAG, "Discovery cleanup queued until broker reconnects")
            mainHandler.post(onComplete)
            return
        }

        val removed = ConcurrentHashMap.newKeySet<String>()
        val settled = AtomicInteger(0)
        val completed = AtomicBoolean(false)
        lateinit var timeout: Runnable

        fun finish() {
            if (!completed.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(timeout)
            val outstanding = discoveryTopics.filterNot(removed::contains)
            if (outstanding.isEmpty()) {
                Log.i(TAG, "MQTT discovery removal confirmed")
            } else {
                outstanding.forEach { config.queueDiscoveryCleanup(cleanupServerUri, it) }
                Log.w(TAG, "${outstanding.size} discovery entries queued for retry")
            }
            mainHandler.post(onComplete)
        }

        fun settle(topic: String, delivered: Boolean) {
            if (delivered) {
                removed.add(topic)
                config.markDiscoveryCleanupComplete(cleanupServerUri, topic)
            }
            if (settled.incrementAndGet() == discoveryTopics.size) finish()
        }

        timeout = Runnable { finish() }
        mainHandler.postDelayed(timeout, DISCOVERY_CLEANUP_TIMEOUT_MS)

        discoveryTopics.forEach { topic ->
            try {
                mqttClient.publish(
                    topic,
                    message("", retained = true),
                    null,
                    object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            settle(topic, delivered = true)
                        }

                        override fun onFailure(
                            asyncActionToken: IMqttToken?,
                            exception: Throwable?
                        ) {
                            Log.w(
                                TAG,
                                "MQTT discovery removal failed (${failureKind(exception)})"
                            )
                            settle(topic, delivered = false)
                        }
                    }
                )
            } catch (e: MqttException) {
                Log.w(TAG, "Unable to publish MQTT discovery removal (${failureKind(e)})")
                settle(topic, delivered = false)
            }
        }
    }

    fun publishCurrentState() {
        publishState()
        publishBatteryState()
    }

    private fun connectWithInitialRetry() {
        if (!running || connecting || client?.isConnected == true) return
        val mqttClient = client ?: return
        val options = connectOptions ?: return

        connecting = true
        try {
            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    connecting = false
                    initialReconnectAttempt = 0
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    connecting = false
                    Log.w(TAG, "Initial MQTT connection failed (${failureKind(exception)})")
                    scheduleInitialReconnect()
                }
            })
            Log.i(TAG, "Connecting to MQTT broker")
        } catch (e: Exception) {
            connecting = false
            Log.w(TAG, "MQTT connect attempt failed synchronously (${failureKind(e)})")
            scheduleInitialReconnect()
        }
    }

    private fun scheduleInitialReconnect() {
        if (!running) return
        val delayMs = MqttReconnectPolicy.delayForAttempt(initialReconnectAttempt)
        initialReconnectAttempt++
        mainHandler.postDelayed(
            { connectWithInitialRetry() },
            delayMs
        )
        Log.i(TAG, "Retrying initial MQTT connection in ${delayMs}ms")
    }

    private fun buildConnectOptions(): MqttConnectOptions {
        val storedPassword = config.readMqttPassword()
        if (storedPassword.decryptionFailed) {
            Log.e(TAG, "Stored MQTT password cannot be decrypted; credentials must be replaced")
        }
        return MqttConnectOptions().apply {
            // Paho handles reconnects after a previously successful connection. The explicit
            // retry loop above covers failures before the first successful connection.
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = KEEP_ALIVE_SECONDS
            maxInflight = 20
            if (config.mqttUsername.isNotBlank()) {
                userName = config.mqttUsername
            }
            if (storedPassword.value.isNotBlank()) {
                password = storedPassword.value.toCharArray()
            }
            setWill(
                topics.availability,
                "offline".toByteArray(Charsets.UTF_8),
                QOS,
                true
            )
        }
    }

    private fun createCallback(callbackClient: MqttAsyncClient) = object : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            if (!running || client !== callbackClient) {
                Log.i(TAG, "Ignoring connection from a stopped MQTT client")
                runCatching { callbackClient.close(true) }
                return
            }
            connecting = false
            initialReconnectAttempt = 0
            Log.i(TAG, "MQTT connected (reconnect=$reconnect)")
            try {
                callbackClient.subscribe(
                    arrayOf(topics.command, topics.homeAssistantStatus),
                    intArrayOf(QOS, QOS)
                )
                cleanupQueuedDiscovery()
                if (cleanupOnly) {
                    mainHandler.post { stop() }
                    return
                }
                publishDiscovery()
                publish(topics.availability, "online", retained = true)
                publishCurrentState()
            } catch (e: MqttException) {
                Log.e(TAG, "Failed to initialize MQTT subscriptions (${failureKind(e)})")
            }
        }

        override fun connectionLost(cause: Throwable?) {
            connecting = false
            if (running && client === callbackClient) {
                Log.w(TAG, "MQTT connection lost (${failureKind(cause)})")
            }
        }

        override fun messageArrived(topic: String, message: MqttMessage) {
            if (!running || client !== callbackClient) return
            val payload = message.toString()
            when (topic) {
                topics.command -> handleCommand(payload)
                topics.homeAssistantStatus -> {
                    if (payload.equals("online", ignoreCase = true)) {
                        publishDiscovery()
                        publishCurrentState()
                    }
                }
            }
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }

    private fun handleCommand(payload: String) {
        try {
            val command = MqttContract.parseCommand(payload)
            mainHandler.post {
                if (running) {
                    commandHandler(command)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid MQTT light command (${failureKind(e)})")
        }
    }

    private fun publishDiscovery() {
        val deviceName = config.mqttDeviceName
        val model = Build.MODEL
        val appVersion = BuildConfig.VERSION_NAME
        publish(
            topics.discovery,
            MqttContract.discoveryPayload(topics, deviceId, deviceName, model, appVersion),
            retained = true
        )
        publish(
            topics.batteryDiscovery,
            MqttContract.batteryDiscoveryPayload(topics, deviceId, deviceName, model, appVersion),
            retained = true
        )
        publish(
            topics.chargingDiscovery,
            MqttContract.chargingDiscoveryPayload(topics, deviceId, deviceName, model, appVersion),
            retained = true
        )
        Log.i(TAG, "MQTT discovery published")
    }

    private fun cleanupQueuedDiscovery() {
        config.pendingDiscoveryCleanupTopics(serverUri).forEach { topic ->
            if (publish(topic, "", retained = true)) {
                config.markDiscoveryCleanupComplete(serverUri, topic)
                Log.i(TAG, "Removed queued MQTT discovery entry")
            }
        }
    }

    private fun publishState() {
        val state = stateProvider()
        publish(
            topics.state,
            MqttContract.statePayload(state.screenOn, state.brightness),
            retained = true
        )
    }

    /** No-op until the first battery reading arrives; the entity stays unknown rather than wrong. */
    fun publishBatteryState() {
        val battery = stateProvider().battery ?: return
        publish(
            topics.batteryState,
            MqttContract.batteryStatePayload(battery.levelPercent, battery.charging),
            retained = true
        )
    }

    private fun publish(topic: String, payload: String, retained: Boolean): Boolean {
        val mqttClient = client
        if (mqttClient?.isConnected != true) return false
        return try {
            mqttClient.publish(topic, message(payload, retained))
            true
        } catch (e: MqttException) {
            Log.w(TAG, "MQTT publish failed (${failureKind(e)})")
            false
        }
    }

    /** Diagnostic classification that never includes endpoints, topics, payloads, or secrets. */
    private fun failureKind(error: Throwable?): String {
        return when (error) {
            is MqttException -> "reason=${error.reasonCode}"
            null -> "unknown"
            else -> error.javaClass.simpleName
        }
    }

    private fun message(payload: String, retained: Boolean): MqttMessage {
        return MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
            qos = QOS
            isRetained = retained
        }
    }

    companion object {
        private const val TAG = "MqttStateManager"
        private const val QOS = 1
        // One wakeup per minute keeps the broker session alive without waking the tablet
        // constantly; the broker gives up after 1.5x this window.
        private const val KEEP_ALIVE_SECONDS = 60
        private const val DISCONNECT_TIMEOUT_MS = 1_000L
        private const val DISCOVERY_CLEANUP_TIMEOUT_MS = 2_000L
    }
}
