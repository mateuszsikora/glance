package com.glance.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glance.config.AppConfig
import com.glance.content.ContentProfile
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DayOfWeek
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class RemoteConfigHttpHandlerTest {
    private lateinit var config: AppConfig
    private var changes = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        config = AppConfig(context).apply {
            setSettingsPin(PIN)
            remoteConfigEnabled = true
        }
        changes = 0
    }

    @Test
    fun requiresPinAndDoesNotRenderSecrets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("mqtt_password", "never-render-this")
            .commit()
        config.mqttUsername = "<script>alert(1)</script>"
        val handler = handler()

        val anonymous = handler.handle(get("/"))
        assertEquals(200, anonymous.status)
        assertTrue(anonymous.text().contains("Enter the settings PIN"))

        val sessionCookie = login(handler)
        val settings = handler.handle(get("/", sessionCookie))
        val html = settings.text()
        assertEquals(200, settings.status)
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(html.contains("never-render-this"))
        assertTrue(html.contains("A password is stored"))
    }

    @Test
    fun rejectsBadPinAndInvalidCsrfToken() {
        val handler = handler()
        val badLogin = handler.handle(post("/login", mapOf("pin" to "999999")))
        assertEquals(401, badLogin.status)

        val sessionCookie = login(handler)
        val rejected = handler.handle(
            post("/save", validSettings("not-the-token"), sessionCookie)
        )
        assertEquals(403, rejected.status)
        assertEquals(0, changes)
        assertEquals(listOf("https://example.com"), config.dashboardUrls)
    }

    @Test
    fun validatesSavesAndAppliesSettings() {
        val handler = handler()
        val sessionCookie = login(handler)
        val page = handler.handle(get("/", sessionCookie)).text()
        val csrf = requireNotNull(
            Regex("name=\"csrf\" value=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
        )
        val parameters = validSettings(csrf).toMutableMap().apply {
            put("dashboardUrls", "https://one.example\nhttp://192.168.1.20/dashboard")
            put("autoRotateEnabled", "on")
            put("autoRotateIntervalSeconds", "45")
            put("contentScheduleEnabled", "on")
            put(
                "contentProfiles",
                "06:00 | https://one.example/morning\n18:00 | https://one.example/evening"
            )
            put("idleScreenEnabled", "on")
            put("idleScreenUrl", "https://one.example/idle")
            put("idleTimeoutMinutes", "10")
            put("minBrightness", "12")
            put("maxBrightness", "220")
            put("scheduleEnabled", "on")
            put("screenOnTime", "07:30")
            put("screenOffTime", "22:15")
            put("mqttDeviceName", "Kitchen tablet")
        }

        val response = handler.handle(post("/save", parameters, sessionCookie))

        assertEquals(303, response.status)
        assertEquals("/", response.headers["Location"])
        assertEquals(0, changes)
        requireNotNull(response.afterSend).invoke()
        assertEquals(1, changes)
        val confirmation = handler.handle(get("/", sessionCookie))
        assertTrue(confirmation.text().contains("Settings saved and applied"))
        assertFalse(handler.handle(get("/", sessionCookie)).text().contains("Settings saved and applied"))
        assertEquals(
            listOf("https://one.example", "http://192.168.1.20/dashboard"),
            config.dashboardUrls
        )
        assertTrue(config.autoRotateEnabled)
        assertEquals(45, config.autoRotateIntervalSeconds)
        assertTrue(config.contentScheduleEnabled)
        assertEquals(2, config.contentProfiles.size)
        assertEquals("06:00", config.contentProfiles.first().startTime)
        assertTrue(config.idleScreenEnabled)
        assertEquals("https://one.example/idle", config.idleScreenUrl)
        assertEquals(10, config.idleTimeoutMinutes)
        assertEquals(12, config.minBrightness)
        assertEquals(220, config.maxBrightness)
        assertTrue(config.scheduleEnabled)
        assertEquals("07:30", config.screenOnTime)
        assertEquals("22:15", config.screenOffTime)
        assertEquals("Kitchen tablet", config.mqttDeviceName)
        assertTrue(config.remoteConfigEnabled)
    }

    @Test
    fun pinChangeInvalidatesExistingSession() {
        val handler = handler()
        val sessionCookie = login(handler)
        val page = handler.handle(get("/", sessionCookie)).text()
        val csrf = requireNotNull(
            Regex("name=\"csrf\" value=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
        )
        val parameters = validSettings(csrf).toMutableMap().apply {
            put("newPin", "847261")
            put("confirmPin", "847261")
        }

        val response = handler.handle(post("/save", parameters, sessionCookie))

        assertEquals(200, response.status)
        assertTrue(response.text().contains("Sign in with the new PIN"))
        assertEquals(0, changes)
        requireNotNull(response.afterSend).invoke()
        assertEquals(1, changes)
        assertTrue(config.verifySettingsPin("847261"))
        assertTrue(handler.handle(get("/", sessionCookie)).text().contains("Enter the settings PIN"))
    }

    @Test
    fun disablingRemoteAccessSendsConfirmationBeforeReloadingServer() {
        val handler = handler()
        val sessionCookie = login(handler)
        val page = handler.handle(get("/", sessionCookie)).text()
        val csrf = requireNotNull(
            Regex("name=\"csrf\" value=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
        )
        val parameters = validSettings(csrf).toMutableMap().apply {
            remove("remoteConfigEnabled")
        }

        val response = handler.handle(post("/save", parameters, sessionCookie))

        assertEquals(200, response.status)
        assertTrue(response.text().contains("Remote access is off"))
        assertTrue(response.text().contains("You can close this tab"))
        assertFalse(response.text().contains("remote-settings-form"))
        assertFalse(config.remoteConfigEnabled)
        assertEquals(0, changes)

        requireNotNull(response.afterSend).invoke()
        assertEquals(1, changes)
    }

    @Test
    fun validationErrorKeepsSubmittedValuesAndShowsAccessibleBanner() {
        val handler = handler()
        val sessionCookie = login(handler)
        val page = handler.handle(get("/", sessionCookie)).text()
        val csrf = requireNotNull(
            Regex("name=\"csrf\" value=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
        )
        val parameters = validSettings(csrf).toMutableMap().apply {
            put("dashboardUrls", "not a dashboard URL")
            put("mqttDeviceName", "Unsaved living room tablet")
        }

        val response = handler.handle(post("/save", parameters, sessionCookie))
        val html = response.text()

        assertEquals(400, response.status)
        assertTrue(html.contains("role=\"alert\""))
        assertTrue(html.contains("not a dashboard URL"))
        assertTrue(html.contains("Unsaved living room tablet"))
        assertEquals(0, changes)
        assertEquals(listOf("https://example.com"), config.dashboardUrls)
    }

    @Test
    fun settingsPageHasResponsiveNavigationAndAccessibleControls() {
        val handler = handler()
        val sessionCookie = login(handler)

        val html = handler.handle(get("/", sessionCookie)).text()

        assertTrue(html.contains("id=\"remote-settings-form\""))
        assertTrue(html.contains("aria-label=\"Settings sections\""))
        assertTrue(html.contains("class=\"save-bar\""))
        assertTrue(html.contains("@media (max-width:540px)"))
        assertTrue(html.contains("accept-charset=\"UTF-8\""))
    }

    @Test
    fun pinChangeOnTabletAlsoInvalidatesExistingSession() {
        val handler = handler()
        val sessionCookie = login(handler)

        config.setSettingsPin("847261")

        assertTrue(handler.handle(get("/", sessionCookie)).text().contains("Enter the settings PIN"))
    }

    @Test
    fun embeddedServerAnswersNothingWhenTheClientNeverSendsARequest() {
        // Browsers preconnect and often send nothing on the extra socket. Answering it with an
        // error page used to surface as a spurious "Internal server error" in the browser, because
        // the connection could be taken from the pool for a later, real request.
        val server = RemoteConfigServer(config, port = 0) { changes++ }
        try {
            server.start()
            val response = Socket("127.0.0.1", server.listeningPort).use { socket ->
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readText()
            }

            assertEquals("", response)
        } finally {
            server.stop()
        }
    }

    @Test
    fun embeddedServerServesTheLoginPageOverLoopback() {
        val server = RemoteConfigServer(config, port = 0) { changes++ }
        try {
            server.start()
            val response = Socket("127.0.0.1", server.listeningPort).use { socket ->
                socket.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                        .toByteArray(StandardCharsets.US_ASCII)
                )
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readText()
            }

            assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(response.contains("Enter the settings PIN"))
            assertTrue(response.contains("Content-Security-Policy:"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun embeddedServerFinishesDisabledConfirmationBeforeStopping() {
        val callbacks = AtomicInteger()
        lateinit var server: RemoteConfigServer
        server = RemoteConfigServer(config, port = 0) {
            callbacks.incrementAndGet()
            server.stop()
        }
        try {
            server.start()
            val port = server.listeningPort
            val loginBody = "pin=${encode(PIN)}"
            val loginResponse = socketRequest(
                port,
                "POST /login HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Type: application/x-www-form-urlencoded\r\n" +
                    "Content-Length: ${loginBody.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                    "Connection: close\r\n\r\n$loginBody"
            )
            val cookie = requireNotNull(
                Regex("(?im)^Set-Cookie: ([^;]+)").find(loginResponse)?.groupValues?.get(1)
            )
            val settingsResponse = socketRequest(
                port,
                "GET / HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nConnection: close\r\n\r\n"
            )
            val csrf = requireNotNull(
                Regex("name=\"csrf\" value=\"([^\"]+)\"")
                    .find(settingsResponse)?.groupValues?.get(1)
            )
            val saveBody = validSettings(csrf)
                .filterKeys { it != "remoteConfigEnabled" }
                .entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

            val saveResponse = socketRequest(
                port,
                "POST /save HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Cookie: $cookie\r\n" +
                    "Content-Type: application/x-www-form-urlencoded\r\n" +
                    "Content-Length: ${saveBody.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                    "Connection: close\r\n\r\n$saveBody"
            )

            assertTrue(saveResponse.startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(saveResponse.contains("Remote access is off"))
            assertTrue(saveResponse.endsWith("</html>"))
            assertEquals(1, callbacks.get())
            assertEquals(-1, server.listeningPort)
        } finally {
            server.stop()
        }
    }

    @Test
    fun savesContentProfileRowsWithSelectedDays() {
        val handler = handler()
        val sessionCookie = login(handler)
        val parameters = validSettings(csrf(handler, sessionCookie)).toMutableMap().apply {
            put("contentScheduleEnabled", "on")
            put("contentProfileRows", "1")
            put("profile.0.time", "06:00")
            DayOfWeek.values()
                .filter { it.value <= 5 }
                .forEach { put("profile.0.day.${it.name}", "on") }
            put("profile.0.urls", "https://one.example/work")
            put("profile.1.time", "09:00")
            put("profile.1.day.SATURDAY", "on")
            put("profile.1.day.SUNDAY", "on")
            put(
                "profile.1.urls",
                "https://one.example/weekend\nhttps://one.example/weather"
            )
        }

        val response = handler.handle(post("/save", parameters, sessionCookie))

        assertEquals(303, response.status)
        requireNotNull(response.afterSend).invoke()
        assertEquals(
            listOf(
                ContentProfile(
                    "06:00",
                    listOf("https://one.example/work"),
                    DayOfWeek.values().filter { it.value <= 5 }.toSet()
                ),
                ContentProfile(
                    "09:00",
                    listOf("https://one.example/weekend", "https://one.example/weather"),
                    setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                )
            ),
            config.contentProfiles
        )

        // Saved rows come back as editable fields with their day checkboxes selected.
        val page = handler.handle(get("/", sessionCookie)).text()
        assertTrue(page.contains("name=\"profile.0.time\" value=\"06:00\""))
        assertTrue(page.contains("name=\"profile.0.day.MONDAY\" checked"))
        assertFalse(page.contains("name=\"profile.0.day.SATURDAY\" checked"))
        assertTrue(page.contains("name=\"profile.1.day.SUNDAY\" checked"))
    }

    @Test
    fun keepsSubmittedProfileRowsWhenValidationFails() {
        val handler = handler()
        val sessionCookie = login(handler)
        val parameters = validSettings(csrf(handler, sessionCookie)).toMutableMap().apply {
            put("contentScheduleEnabled", "on")
            put("contentProfileRows", "1")
            put("profile.0.time", "06:00")
            put("profile.0.urls", "not-a-url")
        }

        val response = handler.handle(post("/save", parameters, sessionCookie))

        assertEquals(400, response.status)
        val page = response.text()
        assertTrue(page.contains("Profile 1 must only contain http:// or https:// URLs"))
        assertTrue(page.contains("not-a-url"))
        assertTrue(config.contentProfiles.isEmpty())
    }

    @Test
    fun removingEveryRowClearsTheStoredProfiles() {
        config.contentProfiles = listOf(
            ContentProfile("06:00", listOf("https://one.example/morning"))
        )
        val handler = handler()
        val sessionCookie = login(handler)
        // The row editor posts its marker even when the user deleted every row.
        val parameters = validSettings(csrf(handler, sessionCookie)).toMutableMap().apply {
            put("contentProfileRows", "1")
        }

        val response = handler.handle(post("/save", parameters, sessionCookie))

        assertEquals(303, response.status)
        requireNotNull(response.afterSend).invoke()
        assertTrue(config.contentProfiles.isEmpty())
    }

    @Test
    fun textFormStillAppliesWhenTheRequestCarriesNoRows() {
        val handler = handler()
        val sessionCookie = login(handler)
        // A scripted client may scrape the form (marker included) and send the text form instead.
        val parameters = validSettings(csrf(handler, sessionCookie)).toMutableMap().apply {
            put("contentScheduleEnabled", "on")
            put("contentProfileRows", "1")
            put("contentProfiles", "Mon-Fri 06:00 | https://one.example/work")
        }

        val response = handler.handle(post("/save", parameters, sessionCookie))

        assertEquals(303, response.status)
        requireNotNull(response.afterSend).invoke()
        assertEquals(
            listOf(
                ContentProfile(
                    "06:00",
                    listOf("https://one.example/work"),
                    DayOfWeek.values().filter { it.value <= 5 }.toSet()
                )
            ),
            config.contentProfiles
        )
    }

    @Test
    fun panelRendersOneEmptyRowWhenNoProfilesAreStored() {
        val handler = handler()
        val page = handler.handle(get("/", login(handler))).text()

        // Without this the first profile could not be created when the panel script is blocked.
        assertTrue(config.contentProfiles.isEmpty())
        assertTrue(page.contains("name=\"profile.0.time\" value=\"06:00\""))
        assertFalse(page.contains("name=\"profile.1.time\""))
    }

    @Test
    fun inlinePanelScriptMatchesTheContentSecurityPolicyHash() {
        val handler = handler()
        val sessionCookie = login(handler)
        val response = handler.handle(get("/", sessionCookie))

        val script = requireNotNull(
            Regex("<script>(.*)</script>", RegexOption.DOT_MATCHES_ALL)
                .find(response.text())?.groupValues?.get(1)
        )
        val digest = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(script.toByteArray(StandardCharsets.UTF_8))
        )

        val headers = ByteArrayOutputStream()
        RemoteHttpCodec.writeResponse(BufferedOutputStream(headers), response)
        val scriptSource = headers.toString(StandardCharsets.UTF_8.name())
            .lineSequence()
            .first { it.startsWith("Content-Security-Policy:") }
            .substringAfter("Content-Security-Policy:")
            .split(';')
            .map(String::trim)
            .first { it.startsWith("script-src") }

        // The hash is the only way the browser will run the panel script.
        assertEquals("script-src 'sha256-$digest'", scriptSource)
    }

    @Test
    fun panelExposesEveryHookTheScriptLooksUp() {
        val handler = handler()
        val page = handler.handle(get("/", login(handler))).text()

        listOf(
            "id=\"content-profiles\"",
            "id=\"content-profiles-empty\"",
            "id=\"content-profile-template\"",
            "id=\"add-content-profile\"",
            "class=\"profile-row\"",
            "class=\"ghost small-button profile-remove\"",
            "data-name=\"time\"",
            "data-name=\"urls\"",
            "data-name=\"day.MONDAY\""
        ).forEach { hook -> assertTrue(hook, page.contains(hook)) }
    }

    private fun handler() = RemoteConfigHttpHandler(config, { changes++ })

    private fun csrf(handler: RemoteConfigHttpHandler, cookie: String): String {
        val page = handler.handle(get("/", cookie)).text()
        return requireNotNull(
            Regex("name=\"csrf\" value=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
        )
    }

    private fun login(handler: RemoteConfigHttpHandler): String {
        val response = handler.handle(post("/login", mapOf("pin" to PIN)))
        assertEquals(303, response.status)
        return requireNotNull(response.headers["Set-Cookie"]).substringBefore(';')
    }

    private fun validSettings(csrf: String): Map<String, String> = linkedMapOf(
        "csrf" to csrf,
        "dashboardUrls" to "https://example.com",
        "dashboardAllowedOrigins" to "",
        "autoRotateIntervalSeconds" to "30",
        "contentProfiles" to "",
        "idleScreenUrl" to "",
        "idleTimeoutMinutes" to "5",
        "autoBrightnessEnabled" to "on",
        "minBrightness" to "5",
        "maxBrightness" to "255",
        "screenOnTime" to "06:00",
        "screenOffTime" to "23:00",
        "mqttBrokerHost" to "",
        "mqttBrokerPort" to "1883",
        "mqttUsername" to "",
        "mqttPassword" to "",
        "mqttDeviceName" to "Glance Tablet",
        "mqttDiscoveryPrefix" to "homeassistant",
        "remoteConfigEnabled" to "on",
        "newPin" to "",
        "confirmPin" to ""
    )

    private fun get(path: String, cookie: String? = null) = RemoteHttpRequest(
        method = "GET",
        path = path,
        headers = cookie?.let { mapOf("cookie" to it) }.orEmpty()
    )

    private fun post(
        path: String,
        parameters: Map<String, String>,
        cookie: String? = null
    ): RemoteHttpRequest {
        val body = parameters.entries.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }.toByteArray(StandardCharsets.UTF_8)
        val headers = mutableMapOf("content-type" to "application/x-www-form-urlencoded")
        if (cookie != null) headers["cookie"] = cookie
        return RemoteHttpRequest("POST", path, headers, body)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun socketRequest(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readText()
        }

    private fun RemoteHttpResponse.text(): String = body.toString(StandardCharsets.UTF_8)

    companion object {
        private const val PREFS_NAME = "glance_config"
        private const val PIN = "583902"

        /** Comfortably longer than the server's own idle timeout, so the close is what ends the read. */
        private const val SOCKET_READ_TIMEOUT_MS = 15_000
    }
}
