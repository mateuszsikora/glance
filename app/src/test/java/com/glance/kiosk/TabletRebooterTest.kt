package com.glance.kiosk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TabletRebooterTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val restarts = mutableListOf<Pair<String, Boolean>>()

    @Before
    fun grantReceiverPermission() {
        // ContextCompat's API 26 stand-in for RECEIVER_NOT_EXPORTED; merged into the real manifest.
        shadowOf(context as Application)
            .grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    @After
    fun tearDown() {
        // Process-wide; Robolectric keeps it across tests.
        TabletRebooter.rebootInProgress = false
    }

    @Test
    fun theNextBootRecognisesTheScheduledRebootExactlyOnce() {
        TabletRebooter.markReboot(context, nowWallMs = MARKED_AT, bootCount = 5)

        assertTrue(nextBoot())
        assertFalse(nextBoot())
    }

    @Test
    fun onlyTheBootRightAfterTheMarkerCounts() {
        // The boot counter is the same: the tablet never went down.
        assertFalse(TabletRebooter.isNextBoot(MARKED_AT, 5, MARKED_AT + 90_000L, 3 * DAY, 5))
        assertTrue(TabletRebooter.isNextBoot(MARKED_AT, 5, MARKED_AT + 90_000L, 40_000L, 6))
        // Without a boot counter: up for less time than has passed since the marker.
        assertTrue(TabletRebooter.isNextBoot(MARKED_AT, -1, MARKED_AT + 90_000L, 40_000L, -1))
        assertFalse(TabletRebooter.isNextBoot(MARKED_AT, -1, MARKED_AT + 90_000L, 3 * DAY, -1))
        // Back more than half an hour later, or with a clock that went backwards.
        assertFalse(TabletRebooter.isNextBoot(MARKED_AT, 5, MARKED_AT + 31 * 60_000L, 40_000L, 6))
        assertFalse(TabletRebooter.isNextBoot(MARKED_AT, 5, MARKED_AT - 60_000L, 40_000L, 6))
    }

    @Test
    fun aRefusedRebootRestartsGlanceInstead() {
        TabletRebooter.reboot(
            context,
            "nightly maintenance",
            displayOn = true,
            rebootDevice = { throw IllegalStateException("A call is in progress") },
            restartApp = { _, reason, displayOn -> restarts += reason to displayOn }
        )
        assertTrue(TabletRebooter.rebootInProgress)
        assertTrue(restarts.isEmpty())
        assertFalse(markerState().all.isEmpty())

        advance(TabletRebooter.REBOOT_DELAY_MS)

        assertEquals(listOf("nightly maintenance, the tablet refused to reboot" to true), restarts)
        assertFalse(TabletRebooter.rebootInProgress)
        // The marker is gone, so the next boot applies the screen schedule.
        assertTrue(markerState().all.isEmpty())
    }

    @Test
    fun aSlowShutdownIsLeftToFinish() {
        TabletRebooter.reboot(
            context,
            "nightly maintenance",
            displayOn = false,
            rebootDevice = {},
            restartApp = { _, reason, displayOn -> restarts += reason to displayOn }
        )
        advance(TabletRebooter.REBOOT_DELAY_MS)

        context.sendBroadcast(Intent(Intent.ACTION_SHUTDOWN))
        advance(2 * TabletRebooter.REBOOT_TIMEOUT_MS)

        assertTrue(restarts.isEmpty())
        assertFalse(markerState().all.isEmpty())
    }

    @Test
    fun aRebootThatDoesNotBeginInTimeRestartsGlanceInstead() {
        var reboots = 0
        TabletRebooter.reboot(
            context,
            "nightly maintenance, display off",
            displayOn = false,
            rebootDevice = { reboots++ },
            restartApp = { _, reason, displayOn -> restarts += reason to displayOn }
        )

        advance(TabletRebooter.REBOOT_DELAY_MS)
        assertEquals(1, reboots)
        assertTrue(restarts.isEmpty())
        assertTrue(TabletRebooter.rebootInProgress)

        advance(TabletRebooter.REBOOT_TIMEOUT_MS)
        assertEquals(listOf("nightly maintenance, display off, the tablet did not reboot" to false), restarts)
        assertFalse(TabletRebooter.rebootInProgress)
    }

    private fun nextBoot() = TabletRebooter.consumePendingReboot(
        context,
        nowWallMs = MARKED_AT + 90_000L,
        nowElapsedMs = 40_000L,
        bootCount = 6
    )

    private fun markerState() =
        context.getSharedPreferences("glance_reboot_state", Context.MODE_PRIVATE)

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private companion object {
        const val MARKED_AT = 1_793_000_000_000L
        const val DAY = 24 * 60 * 60_000L
    }
}
