package com.glance.dashboard

import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DiagnosticLabelTest {

    private val urls = listOf("https://one.example.test", "https://two.example.test")

    @Test
    fun dashboardsAreNamedByTheirOneBasedSlot() {
        val adapter = adapter()

        assertEquals("dashboard 1", (adapter.createFragment(0) as WebViewFragment).diagnosticLabel)
        assertEquals("dashboard 2", (adapter.createFragment(1) as WebViewFragment).diagnosticLabel)
    }

    @Test
    fun slotNamesStayStableWhenTheProfileChanges() {
        val adapter = adapter()
        adapter.replaceUrls(urls.reversed())

        // The pager's item IDs change with every profile; the journal must not.
        assertEquals("dashboard 1", (adapter.createFragment(0) as WebViewFragment).diagnosticLabel)
    }

    @Test
    fun labelsNeverContainTheAddress() {
        val label = (adapter().createFragment(0) as WebViewFragment).diagnosticLabel

        assertEquals(false, label.contains("example"))
    }

    @Test
    fun pagesWithoutALabelGetAGenericName() {
        assertEquals("page", WebViewFragment.newInstance("https://one.example.test").diagnosticLabel)
        assertEquals(
            "idle",
            WebViewFragment.newInstance("https://idle.example.test", diagnosticLabel = "idle")
                .diagnosticLabel
        )
    }

    private fun adapter(): DashboardPagerAdapter {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        return DashboardPagerAdapter(activity, urls, emptyList())
    }
}
