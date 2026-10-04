package com.glance.remote

import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.glance.BuildConfig
import com.glance.R
import com.glance.config.AppConfig
import com.glance.content.ContentProfile
import com.glance.settings.DebugInfoProvider
import com.glance.settings.DebugInfoSnapshot
import com.glance.settings.SettingsActivity
import com.glance.update.UpdateCheckState
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URLEncoder
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

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

    @Test
    fun reportsTheInstalledVersionAndWhetherTheUpdateServerAnswered() {
        config.updateUrl = "http://192.168.1.10:8080/glance-update.json"
        config.updateCheck = UpdateCheckState(
            checkedAt = System.currentTimeMillis(),
            serverReachable = false
        )
        val handler = handler()
        val cookie = login(handler)

        val page = handler.handle(get("/", cookie)).text()

        assertTrue(page.contains("<dt>Installed version</dt>"))
        assertTrue(
            page.contains("<dd>${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})</dd>")
        )
        assertTrue(page.contains("<dd>Unreachable, last tried just now</dd>"))
    }

    @Test
    fun offersAManualInstallOnlyWhileAutomaticUpdatesAreOff() {
        config.updateUrl = "http://192.168.1.10:8080/glance-update.json"
        config.updateCheck = UpdateCheckState(
            checkedAt = System.currentTimeMillis(),
            serverReachable = true,
            availableVersionCode = BuildConfig.VERSION_CODE + 1,
            availableVersionName = "9.9"
        )
        val handler = handler()
        val cookie = login(handler)

        config.autoUpdateEnabled = true
        assertFalse(handler.handle(get("/", cookie)).text().contains("/update-install"))

        config.autoUpdateEnabled = false
        val manual = handler.handle(get("/", cookie)).text()
        assertTrue(manual.contains("formaction=\"/update-install\""))
        assertTrue(manual.contains("Install 9.9 (build ${BuildConfig.VERSION_CODE + 1})"))
    }

    @Test
    fun separatesCheckingFromInstalling() {
        config.updateUrl = "http://192.168.1.10:8080/glance-update.json"
        val requests = mutableListOf<Boolean>()
        val handler = RemoteConfigHttpHandler(config, { changes++ }, { requests += it })
        val cookie = login(handler)
        val csrf = csrf(handler, cookie)

        assertEquals(
            303,
            handler.handle(post("/update-check", mapOf("csrf" to csrf), cookie)).status
        )
        assertEquals(
            303,
            handler.handle(post("/update-install", mapOf("csrf" to csrf), cookie)).status
        )

        assertEquals(listOf(false, true), requests)
        val notice = handler.handle(get("/", cookie)).text()
        assertTrue(notice.contains("Install request received."))
        assertFalse(notice.contains("Installing. The tablet restarts"))
    }

    @Test
    fun installingRequiresTheFormToken() {
        config.updateUrl = "http://192.168.1.10:8080/glance-update.json"
        val requests = mutableListOf<Boolean>()
        val handler = RemoteConfigHttpHandler(config, { changes++ }, { requests += it })
        val cookie = login(handler)

        val response = handler.handle(post("/update-install", mapOf("csrf" to "wrong"), cookie))

        assertEquals(403, response.status)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun theAutomaticUpdateSwitchRoundTrips() {
        val handler = handler()
        val cookie = login(handler)
        config.autoUpdateEnabled = false

        val enabled = validSettings(csrf(handler, cookie)) + mapOf("autoUpdateEnabled" to "on")
        assertEquals(303, handler.handle(post("/save", enabled, cookie)).status)
        assertTrue(config.autoUpdateEnabled)

        // An unchecked box posts nothing at all, which is how the panel turns the switch off.
        val disabled = validSettings(csrf(handler, cookie))
        assertEquals(303, handler.handle(post("/save", disabled, cookie)).status)
        assertFalse(config.autoUpdateEnabled)
    }

    @Test
    fun tabletAndRemoteViewsRenderTheSameSharedDiagnostics() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val custom = dialog.findViewById<ViewGroup>(androidx.appcompat.R.id.custom)!!
            (custom.getChildAt(0) as EditText).setText(PIN)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val tablet = controller.get().findViewById<TextView>(R.id.textDebugInfo).text.toString()
            val handler = handler()
            val page = handler.handle(get("/", login(handler))).text()
            val remote = page.substringAfter("<pre class=\"debug-summary\">").substringBefore("</pre>")
            // Runtime measurements are sampled independently for each view.
            fun stableLines(text: String) = text.lines().filterNot {
                it.startsWith("App Java heap used:") || it.startsWith("Device RAM available:") ||
                    it.startsWith("App data volume available:")
            }
            assertEquals(stableLines(tablet), stableLines(remote))
            assertTrue(tablet.contains("App Java heap used: "))
            assertTrue(remote.contains("App Java heap used: "))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun diagnosticsAreSampledOnlyForAuthenticatedSettingsPages() {
        var samples = 0
        var now = 1_000L
        val handler = RemoteConfigHttpHandler(config, { changes++ }, now = { now }, debugInfo = {
            samples++
            DebugInfoSnapshot(listOf("Private diagnostic marker" to "sample $samples"))
        })
        fun assertHidden(request: RemoteHttpRequest) {
            assertFalse(handler.handle(request).text().contains("Private diagnostic marker"))
        }
        assertHidden(get("/"))
        assertHidden(get("/", "glance_session=invalid"))
        assertHidden(get("/debug-info"))
        assertHidden(post("/login", mapOf("pin" to "0000")))
        assertEquals(0, samples)

        val cookie = login(handler)
        assertEquals(0, samples)
        assertTrue(handler.handle(get("/", cookie)).text().contains("sample 1"))
        assertEquals(1, samples)
        now += 31 * 60_000
        assertHidden(get("/", cookie))
        val renewedCookie = login(handler)
        config.setSettingsPin("987654")
        assertHidden(get("/", renewedCookie))
        assertEquals(1, samples)

        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        assertHidden(get("/", renewedCookie))
        assertEquals(1, samples)
    }

    @Test
    fun reloadSamplesFreshDiagnosticsWithoutSavingOrRunningCallbacks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var uptime = 60_000L
        var used = 10L * 1024 * 1024
        val provider = DebugInfoProvider(context, config,
            heapBytes = { used to 256L * 1024 * 1024 }, elapsedRealtime = { uptime })
        var updates = 0
        val handler = RemoteConfigHttpHandler(config, { changes++ }, { updates++ },
            debugInfo = provider::snapshot)
        val cookie = login(handler)
        val before = prefs.all.toMap()
        val first = handler.handle(get("/", cookie))
        uptime += 60_000
        used *= 2
        val second = handler.handle(get("/", cookie))
        assertTrue(first.text().contains("App Java heap used: 10 MiB"))
        assertTrue(first.text().contains("Device uptime (including sleep): 0h 1m"))
        assertTrue(second.text().contains("App Java heap used: 20 MiB"))
        assertTrue(second.text().contains("Device uptime (including sleep): 0h 2m"))
        assertEquals(null, first.afterSend)
        assertEquals(null, second.afterSend)
        assertEquals(0, changes)
        assertEquals(0, updates)
        assertEquals(before, prefs.all)
        val wire = ByteArrayOutputStream()
        RemoteHttpCodec.writeResponse(BufferedOutputStream(wire), second)
        assertTrue(wire.toString("UTF-8").contains("Cache-Control: no-store"))
    }

    @Test
    fun rendersSharedSummaryWithHtmlEscapingAndUnavailableValues() {
        val provider = DebugInfoProvider(ApplicationProvider.getApplicationContext(), config,
            webViewVersion = { "<script>example & \"version\"</script>" },
            deviceOwner = { null }, exactAlarms = { null })
        val handler = RemoteConfigHttpHandler(config, { changes++ }, debugInfo = provider::snapshot)
        val page = handler.handle(get("/", login(handler))).text()
        val section = page.substringAfter("<section id=\"debug-info\"").substringBefore("</section>")
        assertTrue(page.contains("href=\"#debug-info\""))
        assertTrue(section.contains("&lt;script&gt;example &amp; &quot;version&quot;&lt;/script&gt;"))
        assertFalse(section.contains("<script>"))
        assertFalse(section.contains("<input"))
        assertFalse(section.contains("<button"))
        assertTrue(section.contains("Device Owner: Unavailable"))
        assertTrue(section.contains("Exact-alarm capability: Unavailable"))
        assertTrue(page.contains("white-space:pre-wrap; overflow-wrap:anywhere"))
    }

    @Test
    fun recoveryRequiresAuthenticationCsrfAndExactConfirmation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("glance_restore", 0).edit().clear().commit()
        val state = com.glance.update.RestoreState(context)
        val digest = "a".repeat(64)
        state.offer("""{"versionCode":1000201,"codeIdentity":"code30","dataContract":"${"b".repeat(64)}","kind":"restore","url":"https://host/a.apk","sha256":"$digest"}""")
        config.updateUrl = "https://host/glance-update.json"
        val calls = mutableListOf<String>()
        val handler = RemoteConfigHttpHandler(config, {}, recoveryState = state,
            onRecoveryRequested = { action, _, _ -> calls += action })
        for (path in listOf("/restore-check", "/restore-install", "/restore-resume", "/restore-cancel")) {
            assertEquals(401, handler.handle(post(path, emptyMap())).status)
        }
        val cookie = login(handler)
        for (path in listOf("/restore-check", "/restore-install", "/restore-resume", "/restore-cancel")) {
            assertEquals(403, handler.handle(post(path, mapOf("csrf" to "wrong"), cookie)).status)
            assertEquals(404, handler.handle(get(path, cookie)).status)
        }
        assertTrue(calls.isEmpty())
        val csrf = csrf(handler, cookie)
        val params = mapOf("csrf" to csrf, "restoreVersion" to "1000201", "restoreHash" to digest, "confirmRestore" to "yes")
        assertEquals(409, handler.handle(post("/restore-install", params + ("restoreHash" to "stale"), cookie)).status)
        assertEquals(409, handler.handle(post("/restore-install", params - "confirmRestore", cookie)).status)
        assertEquals(303, handler.handle(post("/restore-install", params, cookie)).status)
        assertEquals(303, handler.handle(post("/restore-resume", mapOf("csrf" to csrf), cookie)).status)
        assertEquals(listOf("restore", "resume"), calls)
        val html = handler.handle(get("/", cookie)).text()
        assertTrue(html.contains("code30 / installation 1000201"))
        assertTrue(html.contains("Android installation number"))
    }

    @Test
    fun pendingInstallationCanBeCancelledWithoutUpdateUrlAndOnlyForPreviewedSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("glance_restore", 0).edit().clear().commit()
        val state = com.glance.update.RestoreState(context)
        state.begin(com.glance.update.UpdateManifest(1000201, "target", "https://host/a.apk", "a".repeat(64)),
            "current", sessionId = 123)
        val calls = mutableListOf<Triple<String, Int, String>>()
        val handler = RemoteConfigHttpHandler(config, {}, recoveryState = state,
            onRecoveryRequested = { action, version, session -> calls += Triple(action, version, session) })
        val cookie = login(handler)
        val params = mapOf("csrf" to csrf(handler, cookie), "pendingVersion" to "1000201", "pendingSession" to "123")
        assertTrue(handler.handle(get("/", cookie)).text().contains("Cancel pending installation"))
        assertEquals(401, handler.handle(post("/restore-cancel", params)).status)
        assertEquals(403, handler.handle(post("/restore-cancel", params - "csrf", cookie)).status)
        assertEquals(409, handler.handle(post("/restore-cancel", params + ("pendingSession" to "122"), cookie)).status)
        assertTrue(calls.isEmpty())
        assertEquals(303, handler.handle(post("/restore-cancel", params, cookie)).status)
        assertEquals(listOf(Triple("cancel", 1000201, "123")), calls)
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
