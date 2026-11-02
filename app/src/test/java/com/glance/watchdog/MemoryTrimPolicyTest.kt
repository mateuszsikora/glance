package com.glance.watchdog

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class MemoryTrimPolicyTest {

    @Test
    fun deviceWideRunningLevelsDoNotReloadPages() {
        // Sent to the foreground app on every charger connection on small tablets.
        assertFalse(MemoryTrimPolicy.requiresReload(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertFalse(
            MemoryTrimPolicy.requiresReload(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        )
        assertFalse(
            MemoryTrimPolicy.requiresReload(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        )
    }

    @Test
    fun uiHiddenAndBackgroundLevelsDoNotReloadPages() {
        assertFalse(MemoryTrimPolicy.requiresReload(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(MemoryTrimPolicy.requiresReload(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
    }

    @Test
    fun processThreateningLevelsReloadPages() {
        assertTrue(MemoryTrimPolicy.requiresReload(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertTrue(MemoryTrimPolicy.requiresReload(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }
}
