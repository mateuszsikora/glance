package com.glance.maintenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MaintenanceSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun scheduledRestartsDefaultToOffDailyAtTheQuietTimeAndPersist() {
        val defaults = MaintenanceSettings(context)
        assertFalse(defaults.nightlyRestartEnabled)
        assertEquals(1, defaults.restartIntervalDays)
        assertEquals("03:30", defaults.quietTime)

        defaults.nightlyRestartEnabled = true
        defaults.restartIntervalDays = 3
        defaults.quietTime = "04:45"

        val reloaded = MaintenanceSettings(context)
        assertTrue(reloaded.nightlyRestartEnabled)
        assertEquals(3, reloaded.restartIntervalDays)
        assertEquals("04:45", reloaded.quietTime)
    }

    @Test
    fun anIntervalThatIsNotOfferedFallsBackToDaily() {
        MaintenanceSettings(context).restartIntervalDays = 5

        assertEquals(1, MaintenanceSettings(context).restartIntervalDays)
    }

    @Test
    fun settingsAreNotStoredInTheMainConfiguration() {
        MaintenanceSettings(context).apply {
            nightlyRestartEnabled = false
            quietTime = "04:45"
        }

        val configuration = context.getSharedPreferences("glance_config", Context.MODE_PRIVATE)
        assertTrue(configuration.all.keys.none { it.contains("restart") || it.contains("quiet") })
    }
}
