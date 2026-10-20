package com.glance.kiosk

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ServiceStarterTest {

    private val calls = mutableListOf<String>()

    /** Records which start method the default arguments actually reach. */
    private inner class RecordingContext(
        private val backgroundStartRejected: Boolean = false
    ) : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun startService(service: Intent?): ComponentName? {
            calls += "startService"
            if (backgroundStartRejected) {
                throw IllegalStateException("Not allowed to start service: app is in background")
            }
            return service?.component
        }

        override fun startForegroundService(service: Intent?): ComponentName? {
            calls += "startForegroundService"
            return service?.component
        }
    }

    @Test
    fun foregroundActivityStartsWithoutAForegroundDeadline() {
        val context = RecordingContext()

        ServiceStarter.startFromForeground(context, Intent(context, KioskService::class.java))

        assertEquals(listOf("startService"), calls)
    }

    @Test
    fun fallsBackWhenAndroidAlreadyTreatsTheAppAsBackground() {
        val context = RecordingContext(backgroundStartRejected = true)

        ServiceStarter.startFromForeground(context, Intent(context, KioskService::class.java))

        assertEquals(listOf("startService", "startForegroundService"), calls)
    }
}
