package com.glance.watchdog

import com.glance.dashboard.WebViewFragment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WebViewHealthCheckerLifecycleTest {
    @Test
    fun fragmentWithoutViewIsSkippedInsteadOfReloaded() {
        val checker = WebViewHealthChecker(timeoutMs = 1L)
        var reloads = 0
        checker.onReloadNeeded = { reloads++ }

        checker.check(WebViewFragment.newInstance("https://dashboard.example.test"))

        assertEquals(0, reloads)
        checker.reset()
    }

    @Test
    fun reloadWithoutCreatedViewIsSafe() {
        WebViewFragment.newInstance("https://dashboard.example.test").reload()
    }
}
