package com.glance.kiosk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
}
