package com.glance.mqtt

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glance.BuildConfig
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

data class MqttReportedState(
    val screenOn: Boolean,
    val brightness: Int
)

/**
 * Lifecycle-independent Home Assistant MQTT integration.
 *
 * The foreground kiosk service owns this class. UI and hardware concerns are supplied through
 * [stateProvider] and [commandHandler], so MQTT stays connected while MainActivity is recreated.
 */
class MqttStateManager(
    private val config: AppConfig,
    private val stateProvider: () -> MqttReportedState,
    private val commandHandler: (MqttLightCommand) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceId = config.mqttDeviceId
    private val topics = MqttContract.topics(config.mqttDiscoveryPrefix, deviceId)
    private val serverUri by lazy {
        MqttEndpoint.serverUri(config.mqttBrokerHost, config.mqttBrokerPort)
    }

    private var client: MqttAsyncClient? = null
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
            val mqttClient = MqttAsyncClient(
                serverUri,
                "glance_${MqttContract.sanitizeId(deviceId)}",
                MemoryPersistence()
            )
            mqttClient.setCallback(createCallback(mqttClient))
            client = mqttClient
            connectOptions = buildConnectOptions()
            running = true
            connectWithInitialRetry()
        } catch (e: Exception) {
            running = false
            Log.e(TAG, "Failed to start MQTT", e)
        }
    }

    fun stop() {
        running = false
        connecting = false
        mainHandler.removeCallbacksAndMessages(null)

        val mqttClient = client
        client = null
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
                Log.w(TAG, "MQTT shutdown did not complete cleanly", e)
            } finally {
                try {
                    // A configuration reload can race an asynchronous connect. Forced close is
                    // required here; otherwise the abandoned client keeps reconnecting with the
                    // same client ID and the broker continuously evicts the replacement client.
                    mqttClient.close(true)
                } catch (e: Exception) {
                    Log.w(TAG, "MQTT client could not be closed", e)
                }
            }
        }
        Log.i(TAG, "MQTT manager stopped")
    }

    /**
     * Removes the retained discovery entry before switching prefix/device configuration.
     */
    fun removeDiscovery() {
        val cleanupServerUri = runCatching { serverUri }.getOrNull() ?: return
        if (!publish(topics.discovery, "", retained = true)) {
            config.queueDiscoveryCleanup(cleanupServerUri, topics.discovery)
            Log.w(TAG, "Discovery cleanup queued until broker reconnects: ${topics.discovery}")
        }
    }

    fun publishCurrentState() {
        publishState()
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
                    Log.w(TAG, "Initial MQTT connection failed: ${exception?.message}")
                    scheduleInitialReconnect()
                }
            })
            Log.i(TAG, "Connecting to MQTT $serverUri")
        } catch (e: Exception) {
            connecting = false
            Log.w(TAG, "MQTT connect attempt failed synchronously", e)
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
        return MqttConnectOptions().apply {
            // Paho handles reconnects after a previously successful connection. The explicit
            // retry loop above covers failures before the first successful connection.
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            maxInflight = 20
            if (config.mqttUsername.isNotBlank()) {
                userName = config.mqttUsername
            }
            if (config.mqttPassword.isNotBlank()) {
                password = config.mqttPassword.toCharArray()
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
                publishState()
            } catch (e: MqttException) {
                Log.e(TAG, "Failed to initialize MQTT subscriptions", e)
            }
        }

        override fun connectionLost(cause: Throwable?) {
            connecting = false
            if (running && client === callbackClient) {
                Log.w(TAG, "MQTT connection lost: ${cause?.message}")
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
                        publishState()
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
            Log.e(TAG, "Invalid MQTT light command: $payload", e)
        }
    }

    private fun publishDiscovery() {
        val payload = MqttContract.discoveryPayload(
            topics = topics,
            rawDeviceId = deviceId,
            deviceName = config.mqttDeviceName,
            model = Build.MODEL,
            appVersion = BuildConfig.VERSION_NAME
        )
        publish(topics.discovery, payload, retained = true)
        Log.i(TAG, "MQTT discovery published: ${topics.discovery}")
    }

    private fun cleanupQueuedDiscovery() {
        config.pendingDiscoveryCleanupTopics(serverUri).forEach { topic ->
            if (publish(topic, "", retained = true)) {
                config.markDiscoveryCleanupComplete(serverUri, topic)
                Log.i(TAG, "Removed queued MQTT discovery entry: $topic")
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

    private fun publish(topic: String, payload: String, retained: Boolean): Boolean {
        val mqttClient = client
        if (mqttClient?.isConnected != true) return false
        return try {
            mqttClient.publish(topic, message(payload, retained))
            true
        } catch (e: MqttException) {
            Log.w(TAG, "MQTT publish failed for $topic", e)
            false
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
        private const val DISCONNECT_TIMEOUT_MS = 1_000L
    }
}
