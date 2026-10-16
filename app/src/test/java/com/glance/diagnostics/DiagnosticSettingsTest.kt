package com.glance.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DiagnosticSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun journalDefaultsToOffAndTheSwitchPersists() {
        assertFalse(DiagnosticSettings(context).enabled)

        DiagnosticSettings(context).enabled = true

        assertTrue(DiagnosticSettings(context).enabled)
    }

    @Test
    fun switchIsNotStoredInTheMainConfiguration() {
        DiagnosticSettings(context).enabled = true

        val configuration = context.getSharedPreferences("glance_config", Context.MODE_PRIVATE)
        assertTrue(configuration.all.keys.none { it.contains("diagnostic") || it.contains("journal") })
    }
}
