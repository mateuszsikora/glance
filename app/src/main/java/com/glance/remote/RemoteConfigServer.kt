package com.glance.remote

import android.util.Log
import com.glance.config.AppConfig
import com.glance.content.WeekDays
import com.glance.settings.ContentProfileDraft
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
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
    private val onUpdateRequested: (installNow: Boolean) -> Unit = {},
    // Kept last so that a trailing lambda still binds to the reload callback.
    private val onConfigChanged: () -> Unit
) {
    private val handler = RemoteConfigHttpHandler(config, onConfigChanged, onUpdateRequested)
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
                runCatching { response.afterSend?.invoke() }
                    .onFailure { Log.w(TAG, "Remote configuration post-response action failed", it) }
            } catch (_: RemoteHttpIdleException) {
                Log.d(TAG, "Client connected without sending a request; closing quietly")
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
    val body: ByteArray,
    /** Runs only after the complete response has been flushed to the browser. */
    val afterSend: (() -> Unit)? = null
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
    private val onUpdateRequested: (installNow: Boolean) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Session(
        val token: String,
        val csrfToken: String,
        var expiresAt: Long,
        val createdAt: Long,
        val pinRevision: Long,
        var notice: String? = null
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
            "POST" to "/update-check" -> requestUpdate(request, installNow = false)
            "POST" to "/update-install" -> requestUpdate(request, installNow = true)
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
        val notice = synchronized(sessionLock) {
            session.notice.also { session.notice = null }
        }
        return RemoteHttpResponse.html(
            200,
            settingsPage(session, updater.snapshot(), notice)
        )
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
                settingsPage(
                    session,
                    updater.snapshot(),
                    result.message,
                    isError = true,
                    submittedValues = parameters
                )
            )
            is RemoteConfigUpdateResult.Success -> {
                val response = if (result.pinChanged) {
                    clearSessions()
                    RemoteHttpResponse.html(
                        200,
                        loginPage("Settings saved. Sign in with the new PIN."),
                        mapOf("Set-Cookie" to expiredSessionCookie())
                    )
                } else if (!config.remoteConfigEnabled) {
                    RemoteHttpResponse.html(200, remoteAccessDisabledPage())
                } else {
                    synchronized(sessionLock) {
                        session.notice = "Settings saved and applied."
                    }
                    RemoteHttpResponse.redirect("/")
                }
                // Reloading can stop this server when remote access was unchecked. Deferring the
                // callback prevents the active socket from being interrupted before the browser
                // receives its confirmation page.
                response.copy(afterSend = onConfigChanged)
            }
        }
    }

    /**
     * Contacts the update server immediately instead of waiting for the watchdog's hourly timer,
     * either to refresh what is reported ([installNow] false) or to install what is on offer.
     *
     * The work runs on its own thread and the browser is answered at once: a download over a slow
     * link would otherwise hold this request open well past the server's own socket timeouts. The
     * outcome shows up in the status lines on the next page load.
     */
    private fun requestUpdate(
        request: RemoteHttpRequest,
        installNow: Boolean
    ): RemoteHttpResponse {
        val session = sessionFor(request) ?: return RemoteHttpResponse.redirect("/")
        val parameters = formParameters(request)
        if (!constantTimeEquals(session.csrfToken, parameters["csrf"].orEmpty())) {
            return RemoteHttpResponse.html(403, page("Request rejected", "Invalid form token."))
        }

        val notice = if (config.updateUrl.isBlank()) {
            "Set an update manifest URL before checking."
        } else {
            onUpdateRequested(installNow)
            if (installNow) {
                "Installing. The tablet restarts into the new build if it is accepted."
            } else {
                "Checking for updates. Reload this page in a moment for the result."
            }
        }
        synchronized(sessionLock) { session.notice = notice }
        return RemoteHttpResponse.redirect("/")
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
            <div class="auth-shell">
              <div class="brand"><span class="brand-mark" aria-hidden="true">G</span><span>Glance</span></div>
              <section class="card auth-card">
                <p class="eyebrow">Remote configuration</p>
                <h1>Welcome back</h1>
                <p>Enter the settings PIN shown on your tablet to continue.</p>
                <form method="post" action="/login" accept-charset="UTF-8">
                  <div class="field"><label for="pin">Settings PIN</label>
                    <input id="pin" name="pin" type="password" inputmode="numeric"
                           pattern="[0-9]{4,12}" minlength="4" maxlength="12"
                           autocomplete="current-password" required autofocus></div>
                  <button class="primary wide" type="submit">Sign in</button>
                </form>
              </section>
              <div class="security-note compact"><span aria-hidden="true">i</span><p>This is an unencrypted local connection. Use it only on a network you trust.</p></div>
            </div>
        """.trimIndent()
    )

    private fun setupRequiredPage(): String = page(
        title = "Setup required",
        content = """
            <div class="auth-shell">
              <div class="brand"><span class="brand-mark" aria-hidden="true">G</span><span>Glance</span></div>
              <section class="card auth-card">
                <p class="eyebrow">One more step</p>
                <h1>Finish setup on the tablet</h1>
                <p>Open Glance settings on the tablet and create a non-default settings PIN first.</p>
              </section>
            </div>
        """.trimIndent()
    )

    private fun remoteAccessDisabledPage(): String = page(
        title = "Settings saved",
        content = """
            <div class="auth-shell">
              <div class="brand"><span class="brand-mark" aria-hidden="true">G</span><span>Glance</span></div>
              <section class="card auth-card completion-card">
                <span class="completion-mark" aria-hidden="true">✓</span>
                <p class="eyebrow">Changes applied</p>
                <h1>Remote access is off</h1>
                <p>Your settings were saved and this configuration server has been disabled.</p>
                <p class="hint">You can close this tab. To connect again, enable remote configuration in Glance settings on the tablet.</p>
              </section>
            </div>
        """.trimIndent()
    )

    private fun settingsPage(
        session: Session,
        values: RemoteConfigSnapshot,
        message: String? = null,
        isError: Boolean = false,
        submittedValues: Map<String, String>? = null
    ): String {
        fun value(name: String, fallback: String): String =
            escapeHtml(submittedValues?.get(name) ?: fallback)

        fun checked(name: String, fallback: Boolean): Boolean =
            if (submittedValues == null) fallback else submittedValues[name] == "on"

        // Keep rejected input on screen instead of resetting the rows to the stored profiles, and
        // always offer one row so the first profile can be created without the panel script.
        val profileDrafts = (
            submittedValues?.let { RemoteContentProfiles.drafts(it) }
                ?: values.contentProfiles.map { profile ->
                    ContentProfileDraft(profile.startTime, profile.urls, profile.days)
                }
            ).ifEmpty { listOf(ContentProfileDraft(DEFAULT_PROFILE_TIME, emptyList())) }

        val passwordStatus = when {
            values.mqttPasswordUnreadable -> "Stored password is unreadable; replace or clear it."
            values.mqttPasswordConfigured -> "A password is stored. Leave blank to keep it."
            else -> "No password is stored."
        }
        val updateStatus = buildString {
            append(statusRow("Installed version", values.update.installedVersion))
            values.update.serverState?.let { append(statusRow("Update server", it)) }
                ?: append(statusRow("Update checks", "Disabled"))
            values.update.lastOutcome?.let { append(statusRow("Last check", it)) }
        }
        return page(
            title = "Settings",
            message = message,
            isError = isError,
            script = RemotePanelScript.SOURCE,
            content = """
                <header class="topbar">
                  <div class="brand"><span class="brand-mark" aria-hidden="true">G</span><span>Glance</span></div>
                  <div class="topbar-actions">
                    <span class="badge"><span class="status-dot"></span>Local connection</span>
                    <form method="post" action="/logout">
                      <button type="submit" class="ghost small-button">Sign out</button>
                    </form>
                  </div>
                </header>
                <div class="page-intro">
                  <p class="eyebrow">Remote configuration</p>
                  <h1>Tablet settings</h1>
                  <p>Changes are validated on the tablet and applied as soon as you save.</p>
                </div>
                <form id="remote-settings-form" method="post" action="/save" accept-charset="UTF-8">
                  <input type="hidden" name="csrf" value="${escapeHtml(session.csrfToken)}">
                  <!-- Pressing Enter in a field submits the form's first submit button. Without
                       this one that would be an update button further down, so a stray Enter would
                       contact the update server instead of saving. -->
                  <button type="submit" class="default-submit" tabindex="-1" aria-hidden="true"></button>
                  <div class="settings-layout">
                    <nav class="section-nav card" aria-label="Settings sections">
                      <p class="nav-title">On this page</p>
                      <a href="#dashboards">Dashboards</a>
                      <a href="#content">Scheduled content</a>
                      <a href="#brightness">Brightness</a>
                      <a href="#screen">Screen schedule</a>
                      <a href="#mqtt">Home Assistant MQTT</a>
                      <a href="#updates">Self-hosted updates</a>
                      <a href="#access">Remote access &amp; PIN</a>
                      <div class="nav-note"><span aria-hidden="true">⌁</span><p>Connected directly to your tablet over the local network.</p></div>
                    </nav>

                    <div class="settings-stack">
                      <section id="dashboards" class="card settings-card">
                        ${sectionHeading("01", "Dashboards", "Choose what Glance displays and how pages rotate.")}
                        <div class="section-body">
                          <div class="field"><label for="dashboardUrls">Dashboard URLs <small>one per line</small></label>
                            <textarea id="dashboardUrls" name="dashboardUrls" rows="4" spellcheck="false" required>${value("dashboardUrls", values.dashboardUrls)}</textarea></div>
                          <div class="field"><label for="dashboardAllowedOrigins">Allowed login origins <small>optional</small></label>
                            <textarea id="dashboardAllowedOrigins" name="dashboardAllowedOrigins" rows="3" spellcheck="false">${value("dashboardAllowedOrigins", values.dashboardAllowedOrigins)}</textarea>
                            <p class="hint">Only add trusted OAuth or SSO origins, one per line.</p></div>
                          ${checkbox("autoRotateEnabled", "Auto-rotate dashboards", checked("autoRotateEnabled", values.autoRotateEnabled))}
                          <div class="field compact-field"><label for="autoRotateIntervalSeconds">Rotate every</label>
                            <div class="input-with-suffix"><input id="autoRotateIntervalSeconds" name="autoRotateIntervalSeconds" type="number" inputmode="numeric"
                              min="5" max="86400" value="${value("autoRotateIntervalSeconds", values.autoRotateIntervalSeconds.toString())}" required><span>seconds</span></div></div>
                        </div>
                      </section>

                      <section id="content" class="card settings-card">
                        ${sectionHeading("02", "Scheduled content", "Adapt the dashboard to the day, the time, or inactivity.")}
                        <div class="section-body">
                          ${checkbox("contentScheduleEnabled", "Show different dashboards on a schedule", checked("contentScheduleEnabled", values.contentScheduleEnabled))}
                          <div class="field"><label id="content-profiles-label">Content profiles</label>
                            <input type="hidden" name="${RemoteContentProfiles.MARKER_FIELD}" value="1">
                            <div id="content-profiles" class="profile-list" role="group" aria-labelledby="content-profiles-label">${
        profileDrafts.mapIndexed { index, draft -> contentProfileRow(index, draft) }.joinToString("")
    }</div>
                            <p id="content-profiles-empty" class="profile-empty"${if (profileDrafts.isEmpty()) "" else " hidden"}>No profiles yet. Add one to choose what appears and when.</p>
                            <button type="button" id="add-content-profile" class="ghost small-button">+ Add profile</button>
                            <template id="content-profile-template">${contentProfileRow(null, ContentProfileDraft(DEFAULT_PROFILE_TIME, emptyList()))}</template>
                            <p class="hint">A profile starts at its time on the selected days and stays active until the next one starts, including across midnight. No day selected means every day; several URLs in one profile become swipeable.</p></div>
                          <div class="subsection">
                            ${checkbox("idleScreenEnabled", "Show a URL after inactivity", checked("idleScreenEnabled", values.idleScreenEnabled))}
                            <div class="grid two">
                              <div class="field"><label for="idleScreenUrl">Idle screen URL</label>
                                <input id="idleScreenUrl" name="idleScreenUrl" type="url" value="${value("idleScreenUrl", values.idleScreenUrl)}" placeholder="https://example.com/idle"></div>
                              <div class="field"><label for="idleTimeoutMinutes">Inactivity timeout</label>
                                <div class="input-with-suffix"><input id="idleTimeoutMinutes" name="idleTimeoutMinutes" type="number" inputmode="numeric"
                                  min="1" max="1440" value="${value("idleTimeoutMinutes", values.idleTimeoutMinutes.toString())}" required><span>minutes</span></div></div>
                            </div>
                            <p class="hint">The first touch returns to the current dashboard without activating its controls.</p>
                          </div>
                        </div>
                      </section>

                      <section id="brightness" class="card settings-card">
                        ${sectionHeading("03", "Brightness", "Set a comfortable range for the display.")}
                        <div class="section-body">
                          ${checkbox("autoBrightnessEnabled", "Use the ambient light sensor", checked("autoBrightnessEnabled", values.autoBrightnessEnabled))}
                          <div class="grid two">
                            <div class="field"><label for="minBrightness">Minimum <small>0–255</small></label>
                              <input id="minBrightness" name="minBrightness" type="number" inputmode="numeric" min="0" max="255" value="${value("minBrightness", values.minBrightness.toString())}" required></div>
                            <div class="field"><label for="maxBrightness">Maximum <small>0–255</small></label>
                              <input id="maxBrightness" name="maxBrightness" type="number" inputmode="numeric" min="0" max="255" value="${value("maxBrightness", values.maxBrightness.toString())}" required></div>
                          </div>
                        </div>
                      </section>

                      <section id="screen" class="card settings-card">
                        ${sectionHeading("04", "Screen schedule", "Automatically wake the display and turn it off overnight.")}
                        <div class="section-body">
                          ${checkbox("scheduleEnabled", "Automatically wake and turn off the screen", checked("scheduleEnabled", values.scheduleEnabled))}
                          <div class="grid two">
                            <div class="field"><label for="screenOnTime">Wake at</label>
                              <input id="screenOnTime" name="screenOnTime" type="time" value="${value("screenOnTime", values.screenOnTime)}" required></div>
                            <div class="field"><label for="screenOffTime">Turn off at</label>
                              <input id="screenOffTime" name="screenOffTime" type="time" value="${value("screenOffTime", values.screenOffTime)}" required></div>
                          </div>
                          <p class="hint">Exact timing requires exact-alarm access on the tablet.</p>
                        </div>
                      </section>

                      <section id="mqtt" class="card settings-card">
                        ${sectionHeading("05", "Home Assistant MQTT", "Connect Glance to Home Assistant discovery and controls.")}
                        <div class="section-body">
                          ${checkbox("mqttEnabled", "Enable MQTT and automatic HA discovery", checked("mqttEnabled", values.mqttEnabled))}
                          <div class="grid two">
                            <div class="field"><label for="mqttBrokerHost">Broker host</label>
                              <input id="mqttBrokerHost" name="mqttBrokerHost" value="${value("mqttBrokerHost", values.mqttBrokerHost)}" placeholder="192.168.1.10" spellcheck="false"></div>
                            <div class="field"><label for="mqttBrokerPort">Broker port</label>
                              <input id="mqttBrokerPort" name="mqttBrokerPort" type="number" inputmode="numeric" min="1" max="65535" value="${value("mqttBrokerPort", values.mqttBrokerPort.toString())}" required></div>
                            <div class="field"><label for="mqttUsername">Username</label>
                              <input id="mqttUsername" name="mqttUsername" value="${value("mqttUsername", values.mqttUsername)}" autocomplete="username"></div>
                            <div class="field"><label for="mqttPassword">New password <small>optional</small></label>
                              <input id="mqttPassword" name="mqttPassword" type="password" autocomplete="new-password">
                              <p class="hint">${escapeHtml(passwordStatus)}</p></div>
                          </div>
                          ${checkbox("clearMqttPassword", "Clear the stored MQTT password", checked("clearMqttPassword", false), subtle = true)}
                          <div class="grid two">
                            <div class="field"><label for="mqttDeviceName">Device name</label>
                              <input id="mqttDeviceName" name="mqttDeviceName" value="${value("mqttDeviceName", values.mqttDeviceName)}"></div>
                            <div class="field"><label for="mqttDiscoveryPrefix">Discovery prefix</label>
                              <input id="mqttDiscoveryPrefix" name="mqttDiscoveryPrefix" value="${value("mqttDiscoveryPrefix", values.mqttDiscoveryPrefix)}" spellcheck="false"></div>
                          </div>
                        </div>
                      </section>

                      <section id="updates" class="card settings-card">
                        ${sectionHeading("06", "Self-hosted updates", "Point Glance at an update manifest you publish yourself.")}
                        <div class="section-body">
                          <dl class="status-list">$updateStatus</dl>
                          <div class="field"><label for="updateUrl">Update manifest URL <small>blank disables updates</small></label>
                            <input id="updateUrl" name="updateUrl" value="${value("updateUrl", values.updateUrl)}" placeholder="http://192.168.1.10:8080/glance-update.json" spellcheck="false"></div>
                          ${checkbox("autoUpdateEnabled", "Install newer builds automatically", checked("autoUpdateEnabled", values.autoUpdateEnabled))}
                          <p class="hint">Glance contacts the server hourly either way. With this off it only reports what is on offer and waits for the install button below.</p>
                          <p class="hint">Use the buttons right after publishing a build; they also retry a version that was abandoned after repeated failures. Save the URL first — both read the stored value, not the field above.</p>
                          <div class="inline-warning"><span aria-hidden="true">!</span><p>Updates install silently and require Device Owner. Only an APK signed with the certificate of the installed build is accepted.</p></div>
                          <!-- formaction retargets the surrounding settings form, so these need no
                               nested form (invalid HTML) and no script (blocked by the CSP). -->
                          <div class="button-row">
                            <button class="ghost" type="submit" formaction="/update-check">Check for updates now</button>${
        values.update.pendingVersion?.let {
            "\n                            <button class=\"primary\" type=\"submit\" formaction=\"/update-install\">" +
                "Install ${escapeHtml(it)}</button>"
        }.orEmpty()
    }
                          </div>
                        </div>
                      </section>

                      <section id="access" class="card settings-card">
                        ${sectionHeading("07", "Remote access &amp; PIN", "Control access to this page and rotate its PIN.")}
                        <div class="section-body">
                          ${checkbox("remoteConfigEnabled", "Keep remote configuration enabled", checked("remoteConfigEnabled", values.remoteConfigEnabled))}
                          <div class="inline-warning"><span aria-hidden="true">!</span><p>Turning this off closes the panel after saving. It can only be re-enabled on the tablet.</p></div>
                          <div class="grid two">
                            <div class="field"><label for="newPin">New settings PIN <small>blank keeps current</small></label>
                              <input id="newPin" name="newPin" type="password" inputmode="numeric" minlength="4" maxlength="12" pattern="[0-9]{4,12}" autocomplete="new-password"></div>
                            <div class="field"><label for="confirmPin">Confirm new PIN</label>
                              <input id="confirmPin" name="confirmPin" type="password" inputmode="numeric" minlength="4" maxlength="12" pattern="[0-9]{4,12}" autocomplete="new-password"></div>
                          </div>
                        </div>
                      </section>
                    </div>
                  </div>

                  <div class="save-bar">
                    <div><strong>Ready to apply?</strong><span>Glance validates every field before changing the tablet.</span></div>
                    <button class="primary" type="submit">Save and apply</button>
                  </div>
                </form>
                <div class="security-note"><span aria-hidden="true">i</span><p><strong>Local HTTP connection.</strong> Traffic, including submitted credentials, is not encrypted. Use this panel only on a trusted LAN.</p></div>
            """.trimIndent()
        )
    }

    /**
     * One scheduled content row. A null [index] renders the template the panel script clones:
     * it carries no field names until the script assigns them.
     */
    private fun contentProfileRow(index: Int?, draft: ContentProfileDraft): String {
        fun nameAttribute(suffix: String): String =
            if (index == null) "" else " name=\"${RemoteContentProfiles.fieldName(index, suffix)}\""

        val dayChips = WeekDays.ALL.joinToString("") { day ->
            val suffix = RemoteContentProfiles.daySuffix(day)
            "<label class=\"day-chip\"><input type=\"checkbox\" data-name=\"$suffix\"" +
                nameAttribute(suffix) + (if (day in draft.days) " checked" else "") +
                "><span>${escapeHtml(WeekDays.shortName(day))}</span></label>"
        }

        return "<div class=\"profile-row\">" +
            "<div class=\"profile-head\">" +
            "<input type=\"time\" data-name=\"time\"${nameAttribute("time")} " +
            "value=\"${escapeHtml(draft.startTime)}\" aria-label=\"Start time\">" +
            "<button type=\"button\" class=\"ghost small-button profile-remove\">Remove</button>" +
            "</div>" +
            "<div class=\"day-picker\">$dayChips</div>" +
            "<textarea data-name=\"urls\"${nameAttribute("urls")} rows=\"2\" spellcheck=\"false\" " +
            "placeholder=\"https://example.com/morning\" aria-label=\"Dashboard URLs, one per line\">" +
            escapeHtml(draft.urls.joinToString("\n")) +
            "</textarea>" +
            "</div>"
    }

    /** One read-only fact inside a `.status-list`. */
    private fun statusRow(label: String, value: String): String =
        "<div><dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd></div>"

    private fun sectionHeading(number: String, title: String, description: String): String = """
        <div class="section-heading">
          <span class="section-number" aria-hidden="true">$number</span>
          <div><h2>$title</h2><p>$description</p></div>
        </div>
    """.trimIndent()

    private fun checkbox(
        name: String,
        label: String,
        checked: Boolean,
        subtle: Boolean = false
    ): String =
        "<label class=\"toggle-row${if (subtle) " subtle" else ""}\">" +
            "<input type=\"checkbox\" name=\"$name\"" +
            (if (checked) " checked" else "") +
            "><span class=\"toggle-track\" aria-hidden=\"true\"></span>" +
            "<span class=\"toggle-label\">${escapeHtml(label)}</span></label>"

    private fun page(
        title: String,
        message: String? = null,
        isError: Boolean = false,
        content: String = "<div class=\"card narrow\"><h1>${escapeHtml(title)}</h1></div>",
        script: String? = null
    ): String {
        val banner = message?.let {
            "<div class=\"banner ${if (isError) "error" else "success"}\" " +
                "role=\"${if (isError) "alert" else "status"}\" aria-live=\"polite\">" +
                "<span aria-hidden=\"true\">${if (isError) "!" else "✓"}</span>" +
                "<p>${escapeHtml(it)}</p></div>"
        }.orEmpty()
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>${escapeHtml(title)} · Glance</title>
              <style>
                :root {
                  color-scheme:dark;
                  font:15px/1.55 Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;
                  --bg:#080c11; --surface:#10171f; --surface-raised:#151e28; --field:#0b1118;
                  --border:#273341; --border-strong:#394858; --text:#f1f5f9; --muted:#91a0af;
                  --accent:#4ade80; --accent-strong:#22c55e; --accent-ink:#052e16;
                  --blue:#60a5fa; --warning:#fbbf24; --danger:#fb7185;
                  background:var(--bg); color:var(--text);
                }
                * { box-sizing:border-box; }
                html { scroll-behavior:smooth; background:var(--bg); }
                body {
                  min-height:100vh; margin:0; padding:28px 22px 64px;
                  background:
                    radial-gradient(circle at 15% -10%,rgba(34,197,94,.12),transparent 30rem),
                    radial-gradient(circle at 95% 10%,rgba(59,130,246,.08),transparent 28rem),var(--bg);
                }
                main { width:min(1080px,100%); margin:auto; }
                h1,h2,p { margin-top:0; }
                h1 { margin-bottom:10px; font-size:clamp(1.8rem,4vw,2.45rem); line-height:1.12; letter-spacing:-.035em; }
                h2 { margin:0 0 3px; font-size:1.18rem; line-height:1.3; letter-spacing:-.015em; }
                a { color:inherit; }
                .topbar { display:flex; align-items:center; justify-content:space-between; gap:18px; min-height:44px; margin-bottom:56px; }
                .brand { display:flex; align-items:center; gap:10px; font-size:1.05rem; font-weight:750; letter-spacing:-.02em; }
                .brand-mark {
                  display:grid; place-items:center; width:34px; height:34px; border-radius:11px;
                  color:var(--accent-ink); background:linear-gradient(145deg,#86efac,var(--accent-strong));
                  box-shadow:0 0 0 1px rgba(134,239,172,.25),0 8px 24px rgba(34,197,94,.14);
                  font-size:.95rem; font-weight:850;
                }
                .topbar-actions { display:flex; align-items:center; gap:10px; }
                .badge {
                  display:inline-flex; align-items:center; gap:8px; white-space:nowrap; padding:7px 11px;
                  border:1px solid var(--border); border-radius:999px; color:#b9c4ce; background:rgba(16,23,31,.76);
                  font-size:.77rem; font-weight:650;
                }
                .status-dot { width:7px; height:7px; border-radius:50%; background:var(--accent); box-shadow:0 0 0 4px rgba(74,222,128,.1); }
                .page-intro { max-width:650px; margin-bottom:30px; }
                .page-intro > p:last-child,.auth-card > p:not(.eyebrow),.completion-card > p { color:var(--muted); margin-bottom:0; font-size:1.02rem; }
                .eyebrow { margin:0 0 9px; color:var(--accent); font-size:.75rem; font-weight:800; letter-spacing:.12em; text-transform:uppercase; }
                .card { border:1px solid var(--border); border-radius:18px; background:rgba(16,23,31,.94); box-shadow:0 16px 50px rgba(0,0,0,.12); }
                .settings-layout { display:grid; grid-template-columns:220px minmax(0,1fr); gap:22px; align-items:start; }
                .section-nav { position:sticky; top:22px; padding:16px 12px; }
                .nav-title { margin:2px 10px 8px; color:var(--muted); font-size:.72rem; font-weight:800; letter-spacing:.1em; text-transform:uppercase; }
                .section-nav > a { display:block; padding:8px 10px; border-radius:9px; color:#b8c3cd; text-decoration:none; font-size:.87rem; font-weight:600; }
                .section-nav > a:hover,.section-nav > a:focus-visible { color:var(--text); background:var(--surface-raised); outline:none; }
                .nav-note { display:flex; gap:9px; margin:14px 4px 0; padding:13px 8px 3px; border-top:1px solid var(--border); color:var(--muted); }
                .nav-note > span { color:var(--accent); font-size:1.1rem; }
                .nav-note p { margin:0; font-size:.74rem; line-height:1.45; }
                .settings-stack { min-width:0; }
                .settings-card { margin:0 0 18px; scroll-margin-top:22px; overflow:hidden; }
                .section-heading { display:flex; gap:14px; padding:22px 24px 19px; border-bottom:1px solid var(--border); background:linear-gradient(180deg,rgba(255,255,255,.018),transparent); }
                .section-heading p { margin:0; color:var(--muted); font-size:.86rem; }
                .section-number { display:grid; place-items:center; flex:0 0 31px; height:31px; border:1px solid #315341; border-radius:9px; color:var(--accent); background:rgba(34,197,94,.07); font-size:.69rem; font-weight:800; }
                .section-body { padding:24px; }
                .field { min-width:0; margin-bottom:18px; }
                .field:last-child { margin-bottom:0; }
                label:not(.toggle-row) { display:block; margin:0 0 7px; color:#dce4ea; font-size:.86rem; font-weight:680; }
                small,.hint { color:var(--muted); font-weight:450; font-size:.78rem; }
                .hint { margin:7px 1px 0; line-height:1.5; }
                input,textarea {
                  width:100%; min-height:44px; border:1px solid var(--border-strong); border-radius:10px;
                  background:var(--field); color:var(--text); padding:10px 12px; font:inherit;
                  box-shadow:inset 0 1px 0 rgba(255,255,255,.02); transition:border-color .15s,box-shadow .15s;
                }
                textarea { min-height:96px; resize:vertical; font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:.85rem; line-height:1.55; }
                input::placeholder,textarea::placeholder { color:#627181; }
                input:hover,textarea:hover { border-color:#4b5c6d; }
                input:focus,textarea:focus { outline:none; border-color:var(--blue); box-shadow:0 0 0 3px rgba(96,165,250,.14); }
                input:invalid:not(:focus):not(:placeholder-shown) { border-color:var(--danger); }
                .grid { display:grid; gap:18px; }
                .two { grid-template-columns:repeat(2,minmax(0,1fr)); }
                .compact-field { max-width:260px; margin-top:18px; }
                .input-with-suffix { display:flex; align-items:stretch; }
                .input-with-suffix input { border-radius:10px 0 0 10px; }
                .input-with-suffix > span { display:flex; align-items:center; padding:0 12px; border:1px solid var(--border-strong); border-left:0; border-radius:0 10px 10px 0; color:var(--muted); background:var(--surface-raised); font-size:.78rem; }
                .profile-list { display:flex; flex-direction:column; gap:12px; }
                .profile-list:not(:empty) { margin-bottom:14px; }
                .profile-row { padding:14px; border:1px solid var(--border); border-radius:12px; background:var(--surface-raised); }
                .profile-head { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:12px; }
                .profile-head input[type=time] { width:auto; min-width:136px; }
                .profile-row textarea { min-height:64px; }
                .day-picker { display:flex; flex-wrap:wrap; gap:7px; margin-bottom:12px; }
                label.day-chip { position:relative; display:inline-flex; margin:0; cursor:pointer; }
                .day-chip input { position:absolute; width:1px; height:1px; opacity:0; }
                .day-chip span { padding:7px 12px; border:1px solid var(--border-strong); border-radius:999px; color:#b8c3cd; background:var(--field); font-size:.79rem; font-weight:650; transition:background .15s,color .15s,border-color .15s; }
                .day-chip input:checked + span { color:var(--accent-ink); border-color:var(--accent-strong); background:var(--accent); }
                .day-chip input:focus-visible + span { outline:2px solid var(--blue); outline-offset:2px; }
                .profile-empty { margin:0 0 14px; color:var(--muted); font-size:.82rem; }
                .subsection { margin-top:22px; padding-top:22px; border-top:1px solid var(--border); }
                .toggle-row { position:relative; display:flex; align-items:center; gap:12px; margin:0; padding:13px 14px; border:1px solid var(--border); border-radius:12px; background:var(--surface-raised); cursor:pointer; }
                .toggle-row + .field,.toggle-row + .grid { margin-top:20px; }
                .toggle-row.subtle { margin:-2px 0 20px; padding:10px 0; border:0; background:transparent; }
                .toggle-row input { position:absolute; width:1px; height:1px; opacity:0; }
                .toggle-track { position:relative; flex:0 0 40px; width:40px; height:23px; border:1px solid #526170; border-radius:999px; background:#303b46; transition:.18s; }
                .toggle-track::after { content:""; position:absolute; top:3px; left:3px; width:15px; height:15px; border-radius:50%; background:#c5ced6; box-shadow:0 1px 3px rgba(0,0,0,.4); transition:.18s; }
                .toggle-row input:checked + .toggle-track { border-color:var(--accent-strong); background:var(--accent-strong); }
                .toggle-row input:checked + .toggle-track::after { left:20px; background:white; }
                .toggle-row input:focus-visible + .toggle-track { outline:2px solid var(--blue); outline-offset:3px; }
                .toggle-label { color:#e1e8ed; font-size:.88rem; font-weight:650; }
                .status-list { display:grid; gap:1px; margin:0 0 20px; border:1px solid var(--border); border-radius:12px; background:var(--border); overflow:hidden; }
                .status-list > div { display:flex; flex-wrap:wrap; align-items:baseline; justify-content:space-between; gap:10px; padding:11px 14px; background:var(--surface-raised); }
                .status-list dt { color:var(--muted); font-size:.78rem; font-weight:650; }
                .status-list dd { margin:0; font-size:.84rem; font-weight:650; }
                .inline-warning { display:flex; align-items:flex-start; gap:10px; margin:14px 0 20px; color:#e8c66a; }
                .inline-warning span,.security-note > span { display:grid; place-items:center; flex:0 0 22px; height:22px; border:1px solid rgba(251,191,36,.35); border-radius:50%; font-size:.72rem; font-weight:800; }
                .inline-warning p { margin:1px 0 0; font-size:.78rem; line-height:1.5; }
                button { min-height:40px; border:0; border-radius:10px; padding:10px 18px; font:700 .88rem system-ui,-apple-system,sans-serif; cursor:pointer; transition:transform .12s,background .12s,box-shadow .12s; }
                button:active { transform:translateY(1px); }
                button:focus-visible { outline:2px solid var(--blue); outline-offset:3px; }
                .primary { min-width:154px; color:var(--accent-ink); background:linear-gradient(180deg,#63e995,var(--accent-strong)); box-shadow:0 7px 22px rgba(34,197,94,.14); }
                .primary:hover { background:linear-gradient(180deg,#7bedaa,#2dd269); }
                .ghost { color:#c8d2da; background:var(--surface); border:1px solid var(--border); }
                .ghost:hover { color:white; border-color:var(--border-strong); background:var(--surface-raised); }
                .small-button { min-height:34px; padding:6px 11px; font-size:.78rem; }
                .button-row { display:flex; flex-wrap:wrap; gap:10px; }
                .default-submit { position:absolute; width:1px; height:1px; padding:0; opacity:0; pointer-events:none; }
                .wide { width:100%; margin-top:4px; }
                .save-bar { position:sticky; bottom:16px; z-index:4; display:flex; align-items:center; justify-content:space-between; gap:18px; margin:8px 0 24px 242px; padding:14px 16px 14px 18px; border:1px solid #34503e; border-radius:15px; background:rgba(16,27,21,.94); box-shadow:0 15px 45px rgba(0,0,0,.35); backdrop-filter:blur(14px); }
                .save-bar div { display:flex; flex-direction:column; }
                .save-bar strong { font-size:.86rem; }
                .save-bar span { color:var(--muted); font-size:.73rem; }
                .security-note { display:flex; align-items:flex-start; gap:10px; max-width:760px; margin:0 0 0 242px; color:#b8a369; }
                .security-note p { margin:0; font-size:.77rem; line-height:1.5; }
                .security-note.compact { margin:18px 0 0; color:var(--muted); }
                .banner { display:flex; align-items:flex-start; gap:10px; margin:0 0 20px; padding:13px 15px; border-radius:12px; }
                .banner > span { font-weight:850; }
                .banner p { margin:0; font-size:.87rem; font-weight:650; }
                .success { color:#bff5ce; background:#0d2b18; border:1px solid #28623b; }
                .error { color:#fecdd3; background:#35151b; border:1px solid #793442; }
                .auth-shell { width:min(430px,100%); margin:8vh auto 0; }
                .auth-shell > .brand { justify-content:center; margin-bottom:20px; }
                .auth-card { padding:30px; }
                .auth-card h1 { font-size:1.75rem; }
                .auth-card > p:not(.eyebrow) { margin-bottom:24px; }
                .completion-card { text-align:center; }
                .completion-mark { display:grid; place-items:center; width:54px; height:54px; margin:0 auto 20px; border-radius:17px; color:var(--accent-ink); background:var(--accent); font-size:1.45rem; font-weight:900; }
                .completion-card .hint { margin-top:18px; font-size:.82rem; }
                @media (max-width:780px) {
                  body { padding:22px 16px 56px; }
                  .topbar { margin-bottom:40px; }
                  .settings-layout { grid-template-columns:1fr; }
                  .section-nav { display:none; }
                  .save-bar { margin-left:0; }
                  .security-note { margin-left:0; }
                }
                @media (max-width:540px) {
                  body { padding:16px 12px 42px; }
                  .topbar { margin-bottom:32px; }
                  .topbar .badge { display:none; }
                  .page-intro { margin-bottom:24px; }
                  .page-intro h1 { font-size:2rem; }
                  .section-heading { padding:19px 17px 17px; }
                  .section-body { padding:19px 17px; }
                  .two { grid-template-columns:1fr; gap:0; }
                  .save-bar { align-items:stretch; flex-direction:column; bottom:8px; }
                  .save-bar button { width:100%; }
                  .auth-shell { margin-top:5vh; }
                  .auth-card { padding:24px 20px; }
                }
                @media (prefers-reduced-motion:reduce) { *,*::before,*::after { scroll-behavior:auto!important; transition:none!important; } }
              </style>
            </head>
            <body><main>$banner$content</main>${script?.let { "<script>$it</script>" }.orEmpty()}</body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val DEFAULT_PROFILE_TIME = "06:00"
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
            val value = try {
                input.read()
            } catch (e: SocketTimeoutException) {
                // Browsers routinely preconnect and then send nothing. Such a socket carries no
                // request, so it gets no response: the browser may later take it from its pool for
                // a real request and would read whatever was left on it as the answer to that.
                if (headerBytes.size() == 0) throw RemoteHttpIdleException()
                throw RemoteHttpException(408, "Request timed out")
            }
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

        val contentLengthHeader = headers["content-length"]
        val contentLength = contentLengthHeader?.toIntOrNull()
            ?: if (contentLengthHeader == null) 0
            else throw RemoteHttpException(400, "Invalid Content-Length")
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
                "default-src 'none'; style-src 'unsafe-inline'; " +
                "script-src ${RemotePanelScript.CSP_SOURCE}; form-action 'self'; " +
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

/** A client connected but never began a request, so there is nothing to answer. */
internal class RemoteHttpIdleException : IOException()

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
