package com.glance.kiosk

import android.content.Context
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

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val intent = Intent(context, KioskService::class.java)
    private val calls = mutableListOf<String>()

    @Test
    fun foregroundActivityStartsWithoutAForegroundDeadline() {
        ServiceStarter.startFromForeground(
            context,
            intent,
            startService = { calls += "startService" },
            startForegroundService = { calls += "startForegroundService" }
        )

        assertEquals(listOf("startService"), calls)
    }

    @Test
    fun fallsBackWhenAndroidAlreadyTreatsTheAppAsBackground() {
        ServiceStarter.startFromForeground(
            context,
            intent,
            startService = {
                calls += "startService"
                throw IllegalStateException("Not allowed to start service: app is in background")
            },
            startForegroundService = { calls += "startForegroundService" }
        )

        assertEquals(listOf("startService", "startForegroundService"), calls)
    }
}
