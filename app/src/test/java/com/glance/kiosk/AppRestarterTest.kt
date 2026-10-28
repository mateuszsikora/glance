package com.glance.kiosk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AppRestarterTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aPendingRelaunchIsConsumedExactlyOnce() {
        assertNull(AppRestarter.consumePendingRelaunch(context))

        AppRestarter.markRelaunch(context, wakeDisplay = true)

        assertEquals(AppRestarter.Relaunch(wakeDisplay = true), AppRestarter.consumePendingRelaunch(context))
        assertNull(AppRestarter.consumePendingRelaunch(context))
    }

    @Test
    fun aMarkerLeftBehindByARebootOrALongGapIsAnOrdinaryStart() {
        // Power lost between the marker and the new process: the clock restarted from zero.
        AppRestarter.markRelaunch(context, wakeDisplay = true, nowElapsedMs = 9_000_000L)
        assertNull(AppRestarter.consumePendingRelaunch(context, nowElapsedMs = 30_000L))
        assertNull(AppRestarter.consumePendingRelaunch(context, nowElapsedMs = 9_005_000L))

        AppRestarter.markRelaunch(context, wakeDisplay = false, nowElapsedMs = 9_000_000L)
        assertNull(AppRestarter.consumePendingRelaunch(context, nowElapsedMs = 9_000_000L + 11 * 60_000L))

        // The sticky-service fallback can come minutes late and is still the relaunch.
        AppRestarter.markRelaunch(context, wakeDisplay = true, nowElapsedMs = 9_000_000L)
        assertEquals(
            AppRestarter.Relaunch(wakeDisplay = true),
            AppRestarter.consumePendingRelaunch(context, nowElapsedMs = 9_000_000L + 4 * 60_000L)
        )
    }

    @Test
    fun aMarkerWithoutATimeIsNotTrusted() {
        context.getSharedPreferences("glance_restart_state", Context.MODE_PRIVATE).edit()
            .putBoolean("relaunch_pending", true)
            .putBoolean("wake_display", true)
            .commit()

        assertNull(AppRestarter.consumePendingRelaunch(context))
        assertFalse(
            context.getSharedPreferences("glance_restart_state", Context.MODE_PRIVATE)
                .contains("relaunch_pending")
        )
    }
}
