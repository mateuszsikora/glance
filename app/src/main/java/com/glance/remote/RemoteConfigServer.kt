package com.glance.remote

import android.util.Log
import com.glance.config.AppConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Small, dependency-free HTTP/1.1 server for the LAN-only configuration page. */
class RemoteConfigServer(
    private val config: AppConfig,
    private val port: Int = RemoteConfigAddress.PORT,
    private val onConfigChanged: () -> Unit
) {
    private val handler = RemoteConfigHttpHandler(config, onConfigChanged)
    private val workerNumber = AtomicInteger()
    private val workers = ThreadPoolExecutor(
        2,
        4,
        60L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(16),
        { runnable ->
            Thread(runnable, "glance-remote-http-${workerNumber.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    internal val listeningPort: Int
        get() = serverSocket?.localPort ?: -1

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (running) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        serverSocket = socket
        running = true
        acceptThread = Thread({ acceptConnections(socket) }, "glance-remote-http-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Remote configuration listening on port $port")
    }

    @Synchronized
    fun stop() {
        val wasRunning = running
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        workers.shutdownNow()
        handler.clearSessions()
        if (wasRunning) Log.i(TAG, "Remote configuration stopped")
    }

    private fun acceptConnections(listener: ServerSocket) {
        while (running) {
            val client = try {
                listener.accept()
            } catch (e: SocketException) {
                if (running) Log.w(TAG, "Remote configuration listener failed", e)
                break
            } catch (e: IOException) {
                if (running) Log.w(TAG, "Unable to accept remote configuration connection", e)
                continue
            }

            if (!isLocalClient(client)) {
                runCatching { client.close() }
                continue
            }

            try {
                workers.execute { serve(client) }
            } catch (_: RuntimeException) {
                runCatching { client.close() }
            }
        }
    }

    private fun isLocalClient(socket: Socket): Boolean = socket.inetAddress.let { address ->
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.tcpNoDelay = true
                val request = RemoteHttpCodec.readRequest(BufferedInputStream(client.getInputStream()))
                val response = handler.handle(request)
                RemoteHttpCodec.writeResponse(
                    BufferedOutputStream(client.getOutputStream()),
                    response
                )
            } catch (e: RemoteHttpException) {
                runCatching {
                    RemoteHttpCodec.writeResponse(
                        BufferedOutputStream(client.getOutputStream()),
                        RemoteHttpResponse.html(e.status, simpleErrorPage(e.message))
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Remote configuration request failed", e)
                runCatching {
                    RemoteHttpCodec.writeResponse(
                        BufferedOutputStream(client.getOutputStream()),
                        RemoteHttpResponse.html(500, simpleErrorPage("Internal server error"))
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "RemoteConfigServer"
        private const val SOCKET_TIMEOUT_MS = 5_000

        private fun simpleErrorPage(message: String): String =
            "<!doctype html><meta charset=\"utf-8\"><title>Glance</title>" +
                "<h1>Glance</h1><p>${escapeHtml(message)}</p>"
    }
}

internal data class RemoteHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray = byteArrayOf()
)

internal data class RemoteHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    companion object {
        fun html(
            status: Int,
            html: String,
            additionalHeaders: Map<String, String> = emptyMap()
        ) = RemoteHttpResponse(
            status,
            mapOf("Content-Type" to "text/html; charset=utf-8") + additionalHeaders,
            html.toByteArray(StandardCharsets.UTF_8)
        )

        fun redirect(location: String, cookie: String? = null): RemoteHttpResponse {
            val headers = mutableMapOf("Location" to location)
            if (cookie != null) headers["Set-Cookie"] = cookie
            return html(303, "", headers)
        }
    }
}

internal class RemoteConfigHttpHandler(
    private val config: AppConfig,
    private val onConfigChanged: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Session(
        val token: String,
        val csrfToken: String,
        var expiresAt: Long,
        val createdAt: Long,
        val pinRevision: Long
    )

    private val updater = RemoteConfigUpdater(config)
    private val secureRandom = SecureRandom()
    private val loginLock = Any()
    private val updateLock = Any()
    private val sessionLock = Any()
    private val sessions = linkedMapOf<String, Session>()

    fun handle(request: RemoteHttpRequest): RemoteHttpResponse {
        if (request.path == "/favicon.ico" && request.method == "GET") {
            return RemoteHttpResponse(204, emptyMap(), byteArrayOf())
        }

        return when (request.method to request.path) {
            "GET" to "/" -> showHome(request)
            "POST" to "/login" -> login(request)
            "POST" to "/save" -> save(request)
            "POST" to "/logout" -> logout(request)
            else -> RemoteHttpResponse.html(404, page("Not found", "The requested page does not exist."))
        }
    }

    fun clearSessions() = synchronized(sessionLock) { sessions.clear() }

    private fun showHome(request: RemoteHttpRequest): RemoteHttpResponse {
        if (!config.hasSettingsPin || config.needsLegacyPinUpgrade) {
            return RemoteHttpResponse.html(403, setupRequiredPage())
        }
        val session = sessionFor(request)
            ?: return RemoteHttpResponse.html(200, loginPage())
        return RemoteHttpResponse.html(200, settingsPage(session, updater.snapshot()))
    }

    private fun login(request: RemoteHttpRequest): RemoteHttpResponse {
        if (!config.hasSettingsPin || config.needsLegacyPinUpgrade) {
            return RemoteHttpResponse.html(403, setupRequiredPage())
        }
        val parameters = formParameters(request)
        return synchronized(loginLock) {
            val lockRemaining = config.pinLockRemainingMs(now())
            if (lockRemaining > 0) {
                val seconds = (lockRemaining + 999L) / 1_000L
                return@synchronized RemoteHttpResponse.html(
                    429,
                    loginPage("Too many attempts. Try again in ${seconds}s.")
                )
            }

            if (!config.verifySettingsPin(parameters["pin"].orEmpty())) {
                val lockedFor = config.recordFailedPinAttempt(now())
                val message = if (lockedFor > 0) {
                    "Too many attempts. Settings are locked for 30 seconds."
                } else {
                    "Wrong PIN."
                }
                return@synchronized RemoteHttpResponse.html(401, loginPage(message))
            }

            config.clearPinFailures()
            val session = createSession()
            RemoteHttpResponse.redirect("/", sessionCookie(session.token))
        }
    }

    private fun save(request: RemoteHttpRequest): RemoteHttpResponse {
        val session = sessionFor(request) ?: return RemoteHttpResponse.redirect("/")
        val parameters = formParameters(request)
        if (!constantTimeEquals(session.csrfToken, parameters["csrf"].orEmpty())) {
            return RemoteHttpResponse.html(403, page("Request rejected", "Invalid form token."))
        }

        return when (val result = synchronized(updateLock) { updater.apply(parameters) }) {
            is RemoteConfigUpdateResult.Error -> RemoteHttpResponse.html(
                400,
                settingsPage(session, updater.snapshot(), result.message, isError = true)
            )
            is RemoteConfigUpdateResult.Success -> {
                onConfigChanged()
                if (result.pinChanged) {
                    clearSessions()
                    RemoteHttpResponse.html(
                        200,
                        loginPage("Settings saved. Sign in with the new PIN."),
                        mapOf("Set-Cookie" to expiredSessionCookie())
                    )
                } else {
                    val message = if (config.remoteConfigEnabled) {
                        "Settings saved and applied."
                    } else {
                        "Settings saved. Remote configuration is now disabled."
                    }
                    RemoteHttpResponse.html(
                        200,
                        settingsPage(session, updater.snapshot(), message)
                    )
                }
            }
        }
    }

    private fun logout(request: RemoteHttpRequest): RemoteHttpResponse {
        val token = sessionToken(request)
        if (token != null) synchronized(sessionLock) { sessions.remove(token) }
        return RemoteHttpResponse.redirect("/", expiredSessionCookie())
    }

    private fun createSession(): Session = synchronized(sessionLock) {
        purgeExpiredSessions()
        while (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.values.minByOrNull(Session::createdAt) ?: break
            sessions.remove(oldest.token)
        }
        val timestamp = now()
        val session = Session(
            token = randomToken(),
            csrfToken = randomToken(),
            expiresAt = timestamp + SESSION_DURATION_MS,
            createdAt = timestamp,
            pinRevision = config.settingsPinRevision
        )
        sessions[session.token] = session
        session
    }

    private fun sessionFor(request: RemoteHttpRequest): Session? {
        val token = sessionToken(request) ?: return null
        return synchronized(sessionLock) {
            purgeExpiredSessions()
            sessions[token]?.takeIf { it.pinRevision == config.settingsPinRevision }
                ?.also { it.expiresAt = now() + SESSION_DURATION_MS }
                ?: run {
                    sessions.remove(token)
                    null
                }
        }
    }

    private fun purgeExpiredSessions() {
        val timestamp = now()
        sessions.entries.removeAll { it.value.expiresAt <= timestamp }
    }

    private fun sessionToken(request: RemoteHttpRequest): String? {
        val cookieHeader = request.headers["cookie"] ?: return null
        return cookieHeader.split(';')
            .map(String::trim)
            .firstOrNull { it.startsWith("$SESSION_COOKIE=") }
            ?.substringAfter('=')
            ?.takeIf(String::isNotBlank)
    }

    private fun formParameters(request: RemoteHttpRequest): Map<String, String> {
        val contentType = request.headers["content-type"].orEmpty().lowercase()
        if (!contentType.startsWith("application/x-www-form-urlencoded")) {
            throw RemoteHttpException(415, "Expected an HTML form request")
        }
        val body = request.body.toString(StandardCharsets.UTF_8)
        if (body.isBlank()) return emptyMap()
        return body.split('&').associate { pair ->
            val name = decodeFormValue(pair.substringBefore('='))
            val value = decodeFormValue(pair.substringAfter('=', ""))
            name to value
        }
    }

    private fun decodeFormValue(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        throw RemoteHttpException(400, "Malformed form encoding")
    }

    private fun randomToken(): String = ByteArray(32)
        .also(secureRandom::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun sessionCookie(token: String): String =
        "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_DURATION_MS / 1_000}"

    private fun expiredSessionCookie(): String =
        "$SESSION_COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"

    private fun loginPage(message: String? = null): String = page(
        title = "Sign in",
        message = message,
        isError = message?.startsWith("Wrong") == true || message?.startsWith("Too many") == true,
        content = """
            <div class="card narrow">
              <h1>Glance</h1>
              <p>Enter the settings PIN configured on the tablet.</p>
              <form method="post" action="/login">
                <label for="pin">Settings PIN</label>
                <input id="pin" name="pin" type="password" inputmode="numeric"
                       pattern="[0-9]{4,12}" minlength="4" maxlength="12"
                       autocomplete="current-password" required autofocus>
                <button type="submit">Sign in</button>
              </form>
              <p class="warning">This page uses unencrypted HTTP. Use it only on a trusted local network.</p>
            </div>
        """.trimIndent()
    )

    private fun setupRequiredPage(): String = page(
        title = "Setup required",
        content = """
            <div class="card narrow">
              <h1>Finish setup on the tablet</h1>
              <p>Open Glance settings on the tablet and create a non-default settings PIN first.</p>
            </div>
        """.trimIndent()
    )

    private fun settingsPage(
        session: Session,
        values: RemoteConfigSnapshot,
        message: String? = null,
        isError: Boolean = false
    ): String {
        val passwordStatus = when {
            values.mqttPasswordUnreadable -> "Stored password is unreadable; replace or clear it."
            values.mqttPasswordConfigured -> "A password is stored. Leave blank to keep it."
            else -> "No password is stored."
        }
        return page(
            title = "Settings",
            message = message,
            isError = isError,
            content = """
                <header>
                  <div><h1>Glance settings</h1><p>Configure this tablet from your local network.</p></div>
                  <span class="badge">LAN · HTTP</span>
                </header>
                <form method="post" action="/save">
                  <input type="hidden" name="csrf" value="${escapeHtml(session.csrfToken)}">

                  <section class="card">
                    <h2>Dashboards</h2>
                    <label for="dashboardUrls">Dashboard URLs <small>one per line</small></label>
                    <textarea id="dashboardUrls" name="dashboardUrls" rows="4" required>${escapeHtml(values.dashboardUrls)}</textarea>
                    <label for="dashboardAllowedOrigins">Allowed login origins <small>optional, one per line</small></label>
                    <textarea id="dashboardAllowedOrigins" name="dashboardAllowedOrigins" rows="3">${escapeHtml(values.dashboardAllowedOrigins)}</textarea>
                    ${checkbox("autoRotateEnabled", "Auto-rotate dashboards", values.autoRotateEnabled)}
                    <label for="autoRotateIntervalSeconds">Rotate interval (seconds)</label>
                    <input id="autoRotateIntervalSeconds" name="autoRotateIntervalSeconds" type="number"
                           min="5" max="86400" value="${values.autoRotateIntervalSeconds}" required>
                  </section>

                  <section class="card">
                    <h2>Scheduled content</h2>
                    ${checkbox("contentScheduleEnabled", "Show different dashboards by time", values.contentScheduleEnabled)}
                    <label for="contentProfiles">Content profiles <small>one HH:mm | URL entry per line</small></label>
                    <textarea id="contentProfiles" name="contentProfiles" rows="5"
                              placeholder="06:00 | https://example.com/morning&#10;18:00 | https://example.com/evening">${escapeHtml(values.contentProfiles)}</textarea>
                    <p class="hint">Repeat a start time to add several swipeable URLs to one profile. The last profile wraps through midnight.</p>
                    ${checkbox("idleScreenEnabled", "Show a URL after inactivity", values.idleScreenEnabled)}
                    <label for="idleScreenUrl">Idle screen URL</label>
                    <input id="idleScreenUrl" name="idleScreenUrl" value="${escapeHtml(values.idleScreenUrl)}"
                           placeholder="https://example.com/idle">
                    <label for="idleTimeoutMinutes">Inactivity timeout (minutes)</label>
                    <input id="idleTimeoutMinutes" name="idleTimeoutMinutes" type="number"
                           min="1" max="1440" value="${values.idleTimeoutMinutes}" required>
                    <p class="hint">The first touch returns to the current dashboard and is consumed to prevent accidental actions.</p>
                  </section>

                  <section class="card">
                    <h2>Brightness</h2>
                    ${checkbox("autoBrightnessEnabled", "Use the ambient light sensor", values.autoBrightnessEnabled)}
                    <div class="grid two">
                      <div><label for="minBrightness">Minimum (0-255)</label>
                        <input id="minBrightness" name="minBrightness" type="number" min="0" max="255" value="${values.minBrightness}" required></div>
                      <div><label for="maxBrightness">Maximum (0-255)</label>
                        <input id="maxBrightness" name="maxBrightness" type="number" min="0" max="255" value="${values.maxBrightness}" required></div>
                    </div>
                  </section>

                  <section class="card">
                    <h2>Screen schedule</h2>
                    ${checkbox("scheduleEnabled", "Automatically wake and turn off the screen", values.scheduleEnabled)}
                    <div class="grid two">
                      <div><label for="screenOnTime">Wake at</label>
                        <input id="screenOnTime" name="screenOnTime" type="time" value="${escapeHtml(values.screenOnTime)}" required></div>
                      <div><label for="screenOffTime">Turn off at</label>
                        <input id="screenOffTime" name="screenOffTime" type="time" value="${escapeHtml(values.screenOffTime)}" required></div>
                    </div>
                    <p class="hint">Exact timing requires exact-alarm access granted on the tablet.</p>
                  </section>

                  <section class="card">
                    <h2>Home Assistant MQTT</h2>
                    ${checkbox("mqttEnabled", "Enable MQTT and automatic HA discovery", values.mqttEnabled)}
                    <div class="grid two">
                      <div><label for="mqttBrokerHost">Broker host</label>
                        <input id="mqttBrokerHost" name="mqttBrokerHost" value="${escapeHtml(values.mqttBrokerHost)}" placeholder="192.168.1.10"></div>
                      <div><label for="mqttBrokerPort">Broker port</label>
                        <input id="mqttBrokerPort" name="mqttBrokerPort" type="number" min="1" max="65535" value="${values.mqttBrokerPort}" required></div>
                    </div>
                    <label for="mqttUsername">Username</label>
                    <input id="mqttUsername" name="mqttUsername" value="${escapeHtml(values.mqttUsername)}" autocomplete="username">
                    <label for="mqttPassword">New password</label>
                    <input id="mqttPassword" name="mqttPassword" type="password" autocomplete="new-password">
                    <p class="hint">${escapeHtml(passwordStatus)}</p>
                    ${checkbox("clearMqttPassword", "Clear the stored MQTT password", false)}
                    <label for="mqttDeviceName">Device name</label>
                    <input id="mqttDeviceName" name="mqttDeviceName" value="${escapeHtml(values.mqttDeviceName)}">
                    <label for="mqttDiscoveryPrefix">Discovery prefix</label>
                    <input id="mqttDiscoveryPrefix" name="mqttDiscoveryPrefix" value="${escapeHtml(values.mqttDiscoveryPrefix)}">
                  </section>

                  <section class="card">
                    <h2>Remote access and PIN</h2>
                    ${checkbox("remoteConfigEnabled", "Keep remote configuration enabled", values.remoteConfigEnabled)}
                    <p class="hint">Disabling this closes the panel after saving. Re-enable it in settings on the tablet.</p>
                    <div class="grid two">
                      <div><label for="newPin">New settings PIN <small>blank keeps current</small></label>
                        <input id="newPin" name="newPin" type="password" inputmode="numeric" pattern="[0-9]{4,12}" autocomplete="new-password"></div>
                      <div><label for="confirmPin">Confirm new PIN</label>
                        <input id="confirmPin" name="confirmPin" type="password" inputmode="numeric" pattern="[0-9]{4,12}" autocomplete="new-password"></div>
                    </div>
                  </section>

                  <button class="primary" type="submit">Save and apply</button>
                </form>
                <form method="post" action="/logout" class="logout">
                  <button type="submit" class="secondary">Sign out</button>
                </form>
                <p class="warning">HTTP traffic, including submitted credentials, is not encrypted. Use this panel only on a trusted LAN.</p>
            """.trimIndent()
        )
    }

    private fun checkbox(name: String, label: String, checked: Boolean): String =
        "<label class=\"check\"><input type=\"checkbox\" name=\"$name\"" +
            (if (checked) " checked" else "") + "><span>${escapeHtml(label)}</span></label>"

    private fun page(
        title: String,
        message: String? = null,
        isError: Boolean = false,
        content: String = "<div class=\"card narrow\"><h1>${escapeHtml(title)}</h1></div>"
    ): String {
        val banner = message?.let {
            "<div class=\"banner ${if (isError) "error" else "success"}\">${escapeHtml(it)}</div>"
        }.orEmpty()
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>${escapeHtml(title)} · Glance</title>
              <style>
                :root { color-scheme: dark; font: 16px/1.45 system-ui,-apple-system,sans-serif; background:#0d1117; color:#e6edf3; }
                * { box-sizing:border-box; }
                body { margin:0; padding:32px 18px 64px; }
                main { width:min(860px,100%); margin:auto; }
                header { display:flex; align-items:center; justify-content:space-between; gap:20px; margin-bottom:20px; }
                h1,h2,p { margin-top:0; } h1 { margin-bottom:4px; } h2 { font-size:1.15rem; }
                header p { color:#8b949e; margin:0; }
                .badge { white-space:nowrap; border:1px solid #d29922; color:#e3b341; border-radius:999px; padding:5px 10px; font-size:.8rem; }
                .card { background:#161b22; border:1px solid #30363d; border-radius:12px; padding:22px; margin-bottom:18px; }
                .narrow { max-width:430px; margin:10vh auto 0; }
                label { display:block; color:#c9d1d9; font-weight:600; margin:16px 0 6px; }
                label:first-of-type { margin-top:0; } small,.hint { color:#8b949e; font-weight:400; font-size:.85rem; }
                input,textarea { width:100%; border:1px solid #484f58; border-radius:7px; background:#0d1117; color:#e6edf3; padding:10px 12px; font:inherit; }
                textarea { resize:vertical; } input:focus,textarea:focus { outline:2px solid #2f81f7; border-color:transparent; }
                .grid { display:grid; gap:16px; } .two { grid-template-columns:repeat(2,minmax(0,1fr)); }
                .check { display:flex; align-items:center; gap:10px; font-weight:500; }
                .check input { width:18px; height:18px; margin:0; }
                button { border:0; border-radius:7px; padding:11px 18px; font:600 1rem system-ui; cursor:pointer; }
                form > button, .narrow button { width:100%; margin-top:18px; }
                .primary,button { color:white; background:#238636; } .secondary { background:#30363d; }
                .logout button { margin-top:0; }
                .banner { border-radius:8px; padding:12px 15px; margin-bottom:18px; }
                .success { background:#153b24; border:1px solid #238636; } .error { background:#4c1f24; border:1px solid #da3633; }
                .warning { color:#e3b341; font-size:.85rem; margin:20px 2px 0; }
                @media (max-width:600px) { body { padding-top:18px; } .two { grid-template-columns:1fr; } .card { padding:17px; } }
              </style>
            </head>
            <body><main>$banner$content</main></body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val SESSION_COOKIE = "glance_session"
        private const val SESSION_DURATION_MS = 30L * 60L * 1_000L
        private const val MAX_SESSIONS = 8
    }
}

internal object RemoteHttpCodec {
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 64 * 1024

    fun readRequest(input: BufferedInputStream): RemoteHttpRequest {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        val delimiter = byteArrayOf(13, 10, 13, 10)
        while (matched < delimiter.size) {
            val value = input.read()
            if (value == -1) throw RemoteHttpException(400, "Incomplete HTTP request")
            headerBytes.write(value)
            if (headerBytes.size() > MAX_HEADER_BYTES) {
                throw RemoteHttpException(431, "Request headers are too large")
            }
            matched = if (value.toByte() == delimiter[matched]) matched + 1
            else if (value.toByte() == delimiter[0]) 1 else 0
        }

        val headerText = headerBytes.toString(StandardCharsets.ISO_8859_1.name())
        val lines = headerText.removeSuffix("\r\n\r\n").split("\r\n")
        val requestParts = lines.firstOrNull()?.split(' ', limit = 3).orEmpty()
        if (requestParts.size != 3 || !requestParts[2].startsWith("HTTP/1.")) {
            throw RemoteHttpException(400, "Malformed HTTP request line")
        }
        val method = requestParts[0].uppercase()
        if (method !in setOf("GET", "POST")) throw RemoteHttpException(405, "Method not allowed")
        val target = requestParts[1]
        if (!target.startsWith('/')) throw RemoteHttpException(400, "Invalid request target")

        val headers = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) throw RemoteHttpException(400, "Malformed HTTP header")
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            if (name in headers) throw RemoteHttpException(400, "Duplicate HTTP header")
            headers[name] = value
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
            throw RemoteHttpException(413, "Request body is too large")
        }
        if (method == "POST" && "content-length" !in headers) {
            throw RemoteHttpException(411, "Content-Length is required")
        }
        if (headers["transfer-encoding"] != null) {
            throw RemoteHttpException(400, "Transfer-Encoding is not supported")
        }

        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(body, offset, contentLength - offset)
            if (read == -1) throw RemoteHttpException(400, "Incomplete request body")
            offset += read
        }
        return RemoteHttpRequest(method, target.substringBefore('?'), headers, body)
    }

    fun writeResponse(output: BufferedOutputStream, response: RemoteHttpResponse) {
        val reason = when (response.status) {
            200 -> "OK"
            204 -> "No Content"
            303 -> "See Other"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            else -> "Internal Server Error"
        }
        val headers = linkedMapOf(
            "Content-Length" to response.body.size.toString(),
            "Connection" to "close",
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Content-Security-Policy" to
                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; " +
                "base-uri 'none'; frame-ancestors 'none'"
        )
        headers.putAll(response.headers)

        output.write("HTTP/1.1 ${response.status} $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
        headers.forEach { (name, value) ->
            output.write("$name: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(response.body)
        output.flush()
    }
}

internal class RemoteHttpException(val status: Int, override val message: String) : IOException(message)

private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(StandardCharsets.UTF_8),
    right.toByteArray(StandardCharsets.UTF_8)
)

private fun escapeHtml(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(character)
        }
    }
}
