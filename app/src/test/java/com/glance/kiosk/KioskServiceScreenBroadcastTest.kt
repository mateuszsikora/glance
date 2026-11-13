package com.glance.kiosk

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.glance.diagnostics.DiagnosticLog
import com.glance.diagnostics.Diagnostics
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class KioskServiceScreenBroadcastTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val power = shadowOf(app.getSystemService(PowerManager::class.java))
    private var journalEnabled = true
    private lateinit var journal: DiagnosticLog
    private var controller: ServiceController<KioskService>? = null

    @Before
    fun installJournal() {
        journal = DiagnosticLog(directory = folder.root, enabled = { journalEnabled }, sampleMemory = { "" })
        Diagnostics.install(journal)
        // ContextCompat's API 26 stand-in for RECEIVER_NOT_EXPORTED; merged into the real manifest.
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    @After
    fun tearDown() {
        controller?.destroy()
        // Diagnostics is process-wide; leave behind a journal that records nothing.
        journalEnabled = false
    }

    @Test
    fun theScreenOnAndroidSendsBeforeTheFirstSleepAfterABootIsIgnored() {
        startServiceWithTheDisplayDark()

        broadcast(Intent.ACTION_SCREEN_ON)
        // Home Assistant would have heard ON here.
        assertFalse(reportedScreenOn())
        broadcast(Intent.ACTION_SCREEN_OFF)

        val entries = journal.read()
        assertFalse(entries.contains("Display turned on"))
        assertTrue(entries.contains("Screen-on broadcast ignored; the display is off"))
        assertTrue(entries.contains("Display turned off"))
        assertFalse(reportedScreenOn())
    }

    @Test
    fun aRealWakeIsRecordedAndReported() {
        startServiceWithTheDisplayDark()
        power.setIsInteractive(true)

        broadcast(Intent.ACTION_SCREEN_ON)

        val entries = journal.read()
        assertTrue(entries.contains("Display turned on"))
        assertFalse(entries.contains("Screen-on broadcast ignored"))
        assertTrue(reportedScreenOn())
    }

    /** As at the scheduled screen-off: the display is dark and Home Assistant was told so. */
    private fun startServiceWithTheDisplayDark() {
        power.setIsInteractive(false)
        controller = Robolectric.buildService(KioskService::class.java).create()
        controller!!.withIntent(Intent(app, KioskService::class.java)).startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(reportedScreenOn())
    }

    /** The screen state the service publishes to Home Assistant. */
    private fun reportedScreenOn(): Boolean =
        KioskService::class.java.getDeclaredField("reportedScreenOn")
            .apply { isAccessible = true }
            .getBoolean(controller!!.get())

    private fun broadcast(action: String) {
        app.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }
}
