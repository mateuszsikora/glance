package com.glance.kiosk

import android.app.AlarmManager
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.glance.AdminReceiver
import com.glance.MainActivity
import com.glance.config.AppConfig
import com.glance.maintenance.NightlyMaintenancePolicy
import java.time.Duration
import java.time.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class KioskServiceMaintenanceTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val config = AppConfig(app)
    private var controller: ServiceController<KioskService>? = null

    @After
    fun tearDown() {
        controller?.destroy()
        // AppRestarter is process-wide; Robolectric keeps it across tests.
        AppRestarter.restartInProgress = false
    }

    @Test
    fun theNextProcessTurnsTheDisplayBackOnOnceTheDashboardIsUp() {
        config.requestedScreenOn = true
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        AppRestarter.markRelaunch(app, wakeDisplay = true)

        start()
        assertNull(ShadowPowerManager.getLatestWakeLock())

        advance(2_000L)
        assertEquals("glance:kiosk_screen_wake", shadowOf(ShadowPowerManager.getLatestWakeLock()).tag)
    }

    @Test
    fun theRecreatedDashboardSeeingADarkDisplayDoesNotTurnTheScreenRequestOff() {
        config.requestedScreenOn = true
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        AppRestarter.markRelaunch(app, wakeDisplay = true)
        start()

        // MainActivity.onCreate reports what it sees while the display is still dark.
        reportScreen(isOn = false, deliberate = false)
        advance(2_000L)

        assertTrue(config.requestedScreenOn)
        assertEquals("glance:kiosk_screen_wake", shadowOf(ShadowPowerManager.getLatestWakeLock()).tag)
    }

    @Test
    fun aDeliberateScreenOffFromTheDashboardStillCountsAsARequest() {
        config.requestedScreenOn = true
        start()

        reportScreen(isOn = false, deliberate = true)

        assertFalse(config.requestedScreenOn)
    }

    @Test
    fun aRelaunchWithTheScreenRequestedOffLeavesTheDisplayDark() {
        config.requestedScreenOn = false
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        AppRestarter.markRelaunch(app, wakeDisplay = true)

        start()
        advance(5_000L)

        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test
    fun aMaintenanceRestartKeepsAManualScreenOverride() {
        // Inside the OFF window, after an MQTT ON command.
        val now = LocalTime.now()
        config.scheduleEnabled = true
        config.screenOffTime = now.minusHours(1).withSecond(0).withNano(0).toString().take(5)
        config.screenOnTime = now.plusHours(1).withSecond(0).withNano(0).toString().take(5)
        config.requestedScreenOn = true
        AppRestarter.markRelaunch(app, wakeDisplay = false)

        start()

        val started = startedServiceActions()
        assertFalse(started.contains(KioskService.ACTION_COMMAND_SCREEN))
        assertTrue(config.requestedScreenOn)
        assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
        assertNull(AppRestarter.consumePendingRelaunch(app))
    }

    @Test
    fun aServiceRestartedWithoutADashboardLaunchesIt() {
        start()

        assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
    }

    @Test
    fun aServiceStartedByALiveDashboardDoesNotLaunchAnother() {
        MainActivity.liveInstances.set(1)
        try {
            start()

            assertNull(shadowOf(app).nextStartedActivity)
        } finally {
            MainActivity.liveInstances.set(0)
        }
    }

    @Test
    fun anOrdinaryStartStillAppliesTheSchedule() {
        val now = LocalTime.now()
        config.scheduleEnabled = true
        config.screenOffTime = now.minusHours(1).withSecond(0).withNano(0).toString().take(5)
        config.screenOnTime = now.plusHours(1).withSecond(0).withNano(0).toString().take(5)

        start()

        assertTrue(startedServiceActions().contains(KioskService.ACTION_COMMAND_SCREEN))
    }

    @Test
    fun maintenanceIsSkippedInAFreshProcess() {
        start()
        clearBroadcasts()

        startCommand(Intent(app, KioskService::class.java).setAction(KioskService.ACTION_NIGHTLY_MAINTENANCE))

        assertTrue(maintenanceBroadcasts().isEmpty())
        assertNull(AppRestarter.consumePendingRelaunch(app))
    }

    @Test
    fun theServiceRetriesUntilTheActivityReportsCompletion() {
        start()
        ageProcess()
        clearBroadcasts()

        requestMaintenance()
        assertEquals(1, maintenanceBroadcasts().size)
        assertTrue(maintenanceBroadcasts().single().hasExtra(KioskService.EXTRA_MAINTENANCE_REQUESTED_AT))

        // A recreated Activity does not lose the request: the service asks again.
        advance(NightlyMaintenancePolicy.RETRY_MS)
        assertEquals(2, maintenanceBroadcasts().size)

        app.sendBroadcast(
            Intent(KioskService.ACTION_REPORT_NIGHTLY_MAINTENANCE_DONE).setPackage(app.packageName)
        )
        shadowOf(Looper.getMainLooper()).idle()
        advance(NightlyMaintenancePolicy.RETRY_MS)
        assertEquals(2, maintenanceBroadcasts().size)
    }

    @Test
    fun theServiceStopsAskingAfterThePostponementWindow() {
        start()
        ageProcess()
        clearBroadcasts()

        requestMaintenance()
        repeat(30) { advance(NightlyMaintenancePolicy.RETRY_MS) }

        val attempts = (NightlyMaintenancePolicy.MAX_POSTPONE_MS / NightlyMaintenancePolicy.RETRY_MS + 1).toInt()
        assertEquals(attempts, maintenanceBroadcasts().size)
    }

    @Test
    fun withTheDisplayOffADeviceOwnerRestartsWithoutAskingTheActivity() {
        shadowOf(app.getSystemService(DevicePolicyManager::class.java))
            .setDeviceOwner(AdminReceiver.getComponentName(app))
        start()
        ageProcess()
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        clearBroadcasts()

        // The kill is posted a second later; the looper is never advanced that far here.
        requestMaintenance()

        assertTrue(maintenanceBroadcasts().isEmpty())
        assertTrue(AppRestarter.restartInProgress)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        assertNotNull(alarms.scheduledAlarms.singleOrNull { shadowOf(it.operation).isActivityIntent })

        // The next process: its service launches the dashboard and drops the alarm's duplicate.
        controller!!.destroy()
        AppRestarter.restartInProgress = false
        start()
        assertNull(alarms.scheduledAlarms.singleOrNull { shadowOf(it.operation).isActivityIntent })
        assertNull(AppRestarter.consumePendingRelaunch(app))
    }

    private fun start() {
        // ContextCompat's API 26 stand-in for RECEIVER_NOT_EXPORTED; merged into the real manifest.
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        controller = Robolectric.buildService(KioskService::class.java).create()
        startCommand(Intent(app, KioskService::class.java))
    }

    private fun startCommand(intent: Intent) {
        controller!!.withIntent(intent).startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun requestMaintenance() {
        startCommand(Intent(app, KioskService::class.java).setAction(KioskService.ACTION_NIGHTLY_MAINTENANCE))
    }

    private fun reportScreen(isOn: Boolean, deliberate: Boolean) {
        app.sendBroadcast(
            Intent(KioskService.ACTION_REPORT_SCREEN_STATE)
                .setPackage(app.packageName)
                .putExtra(KioskService.EXTRA_SCREEN_ON, isOn)
                .putExtra(KioskService.EXTRA_DELIBERATE, deliberate)
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun ageProcess() {
        advance(NightlyMaintenancePolicy.MIN_PROCESS_UPTIME_MS)
    }

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun startedServiceActions(): List<String?> {
        val actions = mutableListOf<String?>()
        while (true) {
            val intent = shadowOf(app).nextStartedService ?: break
            actions += intent.action
        }
        return actions
    }

    private fun clearBroadcasts() {
        shadowOf(app).clearBroadcastIntents()
    }

    private fun maintenanceBroadcasts(): List<Intent> = shadowOf(app).broadcastIntents
        .filter { it.action == KioskService.ACTION_CONTROL_NIGHTLY_MAINTENANCE }
}
