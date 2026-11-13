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
    fun startService() {
        journal = DiagnosticLog(directory = folder.root, enabled = { journalEnabled }, sampleMemory = { "" })
        Diagnostics.install(journal)
        // ContextCompat's API 26 stand-in for RECEIVER_NOT_EXPORTED; merged into the real manifest.
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        controller = Robolectric.buildService(KioskService::class.java).create()
        controller!!.withIntent(Intent(app, KioskService::class.java)).startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        controller?.destroy()
        // Diagnostics is process-wide; leave behind a journal that records nothing.
        journalEnabled = false
    }

    @Test
    fun theScreenOnAndroidSendsBeforeTheFirstSleepAfterABootIsIgnored() {
        power.setIsInteractive(false)

        broadcast(Intent.ACTION_SCREEN_ON)
        broadcast(Intent.ACTION_SCREEN_OFF)

        val entries = journal.read()
        assertFalse(entries.contains("Display turned on"))
        assertTrue(entries.contains("Display turned off"))
    }

    @Test
    fun aRealWakeIsRecorded() {
        power.setIsInteractive(true)

        broadcast(Intent.ACTION_SCREEN_ON)

        assertTrue(journal.read().contains("Display turned on"))
    }

    private fun broadcast(action: String) {
        app.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }
}
