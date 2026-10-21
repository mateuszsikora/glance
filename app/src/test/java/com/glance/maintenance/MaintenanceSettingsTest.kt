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
    fun nightlyRestartDefaultsToOnAtTheQuietTimeAndPersists() {
        val defaults = MaintenanceSettings(context)
        assertTrue(defaults.nightlyRestartEnabled)
        assertEquals("03:30", defaults.quietTime)

        defaults.nightlyRestartEnabled = false
        defaults.quietTime = "04:45"

        val reloaded = MaintenanceSettings(context)
        assertFalse(reloaded.nightlyRestartEnabled)
        assertEquals("04:45", reloaded.quietTime)
    }

    @Test
    fun idleRenderingDefaultsToTheOriginalGpuLayerAndPersists() {
        assertEquals(IdleRendering.GPU_LAYER, MaintenanceSettings(context).idleRendering)

        MaintenanceSettings(context).idleRendering = IdleRendering.SOFTWARE

        assertEquals(IdleRendering.SOFTWARE, MaintenanceSettings(context).idleRendering)
    }

    @Test
    fun anUnknownStoredRenderingFallsBackToTheDefault() {
        assertEquals(IdleRendering.GPU_LAYER, IdleRendering.fromKey("vulkan"))
        assertEquals(IdleRendering.GPU_LAYER, IdleRendering.fromKey(null))
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
