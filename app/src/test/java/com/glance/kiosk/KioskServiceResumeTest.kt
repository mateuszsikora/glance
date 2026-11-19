package com.glance.kiosk

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.glance.config.AppConfig
import com.glance.screen.ScheduleManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * What MainActivity.resumeKioskMode relies on when an "Exit Kiosk Mode" failed halfway: the
 * exit cancels the screen alarms first, and an exception (for example from clearing the kiosk
 * policies) can leave KioskService running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class KioskServiceResumeTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val config = AppConfig(app)
    private val alarms: ShadowAlarmManager
        get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private var controller: ServiceController<KioskService>? = null

    @Before
    fun setUp() {
        config.scheduleEnabled = true
        config.screenOnTime = "06:25"
        config.screenOffTime = "23:20"
    }

    @After
    fun tearDown() {
        controller?.destroy()
    }

    @Test
    fun theResumeReloadReArmsTheScreenAlarmsOfAStillRunningService() {
        start(Intent(app, KioskService::class.java))
        assertEquals(SCREEN_ACTIONS, screenAlarmActions())
        // The part of the exit that ran before the exception.
        ScheduleManager(app, config).stop()
        assertTrue(screenAlarmActions().isEmpty())

        startCommand(resumeIntent())

        assertEquals(SCREEN_ACTIONS, screenAlarmActions())
    }

    @Test
    fun aPlainStartLeavesAStillRunningServiceWithoutItsScreenAlarms() {
        start(Intent(app, KioskService::class.java))
        ScheduleManager(app, config).stop()

        startCommand(Intent(app, KioskService::class.java))

        // Why the resume sends the reload instead.
        assertTrue(screenAlarmActions().isEmpty())
    }

    @Test
    fun aStoppedServiceInitializesNormallyFromTheResumeReload() {
        start(resumeIntent())

        assertEquals(SCREEN_ACTIONS, screenAlarmActions())
    }

    private fun resumeIntent() =
        Intent(app, KioskService::class.java).setAction(KioskService.ACTION_RELOAD_CONFIG)

    private fun screenAlarmActions(): Set<String?> = alarms.scheduledAlarms
        .map { shadowOf(it.operation).savedIntent.action }
        .filter { it in SCREEN_ACTIONS }
        .toSet()

    private fun start(intent: Intent) {
        // ContextCompat's API 26 stand-in for RECEIVER_NOT_EXPORTED; merged into the real manifest.
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        controller = Robolectric.buildService(KioskService::class.java).create()
        startCommand(intent)
    }

    private fun startCommand(intent: Intent) {
        controller!!.withIntent(intent).startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        val SCREEN_ACTIONS = setOf(
            ScheduleManager.ACTION_SCREEN_ON,
            ScheduleManager.ACTION_SCREEN_OFF
        )
    }
}
