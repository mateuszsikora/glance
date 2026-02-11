package com.glance.ha

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket client for Home Assistant API.
 *
 * Handles:
 * - Connection + authentication (Long-Lived Access Token)
 * - Subscribing to state changes
 * - Calling services
 * - Auto-reconnect with exponential backoff
 * - Heartbeat ping/pong
 */
class HAWebSocketClient(
    private val baseUrl: String,
    private val accessToken: String
) {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onAuthResult(success: Boolean)
        fun onStateChanged(entityId: String, newState: String, attributes: JSONObject)
        fun onError(error: String)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WebSocket
        .pingInterval(30, TimeUnit.SECONDS)     // Keep-alive pings
        .build()

    private var webSocket: WebSocket? = null
    private var listener: Listener? = null
    private val msgId = AtomicInteger(1)
    private var authenticated = false
    private val subscriptionIds = mutableMapOf<String, Int>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var shouldReconnect = true

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /**
     * Connect to HA WebSocket API.
     */
    fun connect() {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            Log.w(TAG, "Cannot connect: URL or token is empty")
            return
        }

        shouldReconnect = true
        reconnectAttempt = 0

        val wsUrl = buildWsUrl()
        Log.i(TAG, "Connecting to $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, createWebSocketListener())
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        authenticated = false
        subscriptionIds.clear()
        Log.i(TAG, "Disconnected")
    }

    val isConnected: Boolean get() = authenticated

    // --- Subscribing to entity state changes ---

    fun subscribeToEntity(entityId: String) {
        if (!authenticated) {
            Log.w(TAG, "Cannot subscribe: not authenticated")
            return
        }

        val id = msgId.getAndIncrement()
        subscriptionIds[entityId] = id

        val msg = JSONObject().apply {
            put("id", id)
            put("type", "subscribe_trigger")
            put("trigger", JSONObject().apply {
                put("platform", "state")
                put("entity_id", entityId)
            })
        }
        send(msg)
        Log.d(TAG, "Subscribed to $entityId (msgId=$id)")
    }

    // --- Calling services ---

    fun callService(domain: String, service: String, entityId: String, data: JSONObject? = null) {
        if (!authenticated) {
            Log.w(TAG, "Cannot call service: not authenticated")
            return
        }

        val id = msgId.getAndIncrement()
        val serviceData = JSONObject().apply {
            put("entity_id", entityId)
            data?.keys()?.forEach { key ->
                put(key, data.get(key))
            }
        }

        val msg = JSONObject().apply {
            put("id", id)
            put("type", "call_service")
            put("domain", domain)
            put("service", service)
            put("service_data", serviceData)
        }
        send(msg)
        Log.d(TAG, "Called $domain.$service on $entityId (msgId=$id)")
    }

    fun setInputBoolean(entityId: String, turnOn: Boolean) {
        val service = if (turnOn) "turn_on" else "turn_off"
        callService("input_boolean", service, entityId)
    }

    fun setInputNumber(entityId: String, value: Number) {
        callService("input_number", "set_value", entityId, JSONObject().apply {
            put("value", value)
        })
    }

    // --- Internal ---

    private fun buildWsUrl(): String {
        val cleaned = baseUrl.trimEnd('/')
        val wsBase = when {
            cleaned.startsWith("ws://") || cleaned.startsWith("wss://") -> cleaned
            cleaned.startsWith("https://") -> cleaned.replace("https://", "wss://")
            cleaned.startsWith("http://") -> cleaned.replace("http://", "ws://")
            else -> "ws://$cleaned"
        }
        return "$wsBase/api/websocket"
    }

    private fun send(json: JSONObject) {
        webSocket?.send(json.toString()) ?: Log.w(TAG, "Cannot send: webSocket is null")
    }

    private fun authenticate() {
        val msg = JSONObject().apply {
            put("type", "auth")
            put("access_token", accessToken)
        }
        send(msg)
        Log.d(TAG, "Sent auth message")
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "auth_required" -> {
                    Log.d(TAG, "Auth required, sending token")
                    authenticate()
                }
                "auth_ok" -> {
                    authenticated = true
                    reconnectAttempt = 0
                    Log.i(TAG, "Authenticated successfully")
                    listener?.onAuthResult(true)
                    listener?.onConnected()
                }
                "auth_invalid" -> {
                    authenticated = false
                    val msg = json.optString("message", "Invalid auth")
                    Log.e(TAG, "Auth failed: $msg")
                    listener?.onAuthResult(false)
                    listener?.onError("Auth failed: $msg")
                }
                "event" -> handleEvent(json)
                "result" -> {
                    if (!json.optBoolean("success", false)) {
                        val error = json.optJSONObject("error")
                        Log.w(TAG, "Command failed: ${error?.optString("message")}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
        }
    }

    private fun handleEvent(json: JSONObject) {
        try {
            val event = json.optJSONObject("event") ?: return
            val variables = event.optJSONObject("variables") ?: return
            val trigger = variables.optJSONObject("trigger") ?: return

            val entityId = trigger.optString("entity_id")
            val toState = trigger.optJSONObject("to_state") ?: return
            val state = toState.optString("state")
            val attributes = toState.optJSONObject("attributes") ?: JSONObject()

            Log.d(TAG, "State change: $entityId -> $state")
            listener?.onStateChanged(entityId, state, attributes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle event", e)
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = calculateBackoff()
            Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")
            delay(delayMs)
            reconnectAttempt++

            val wsUrl = buildWsUrl()
            val request = Request.Builder().url(wsUrl).build()
            webSocket = client.newWebSocket(request, createWebSocketListener())
        }
    }

    private fun calculateBackoff(): Long {
        val base = 1000L
        val maxDelay = 30_000L
        val delay = base * (1L shl minOf(reconnectAttempt, 5))
        return minOf(delay, maxDelay)
    }

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket opened")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: $code $reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            authenticated = false
            listener?.onDisconnected("Closed: $code $reason")
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}")
            authenticated = false
            listener?.onDisconnected("Failure: ${t.message}")
            listener?.onError(t.message ?: "Unknown error")
            scheduleReconnect()
        }
    }

    fun destroy() {
        disconnect()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val TAG = "HAWebSocketClient"
    }
}
