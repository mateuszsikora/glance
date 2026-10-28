package com.glance.dashboard

import android.view.View
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import com.glance.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WebViewLayerTypeTest {

    @Test
    fun pagesKeepTheirHardwareLayerUnlessToldOtherwise() {
        assertEquals(View.LAYER_TYPE_HARDWARE, WebViewFragment.newInstance(URL).requestedLayerType)
        assertEquals(View.LAYER_TYPE_HARDWARE, shownLayerType(WebViewFragment.newInstance(URL)))
    }

    @Test
    fun theChosenLayerTypeReachesTheWebView() {
        val idle = WebViewFragment.newInstance(URL, layerType = View.LAYER_TYPE_NONE)

        assertEquals(View.LAYER_TYPE_NONE, idle.requestedLayerType)
        assertEquals(View.LAYER_TYPE_NONE, shownLayerType(idle))
    }

    private fun shownLayerType(fragment: WebViewFragment): Int {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
        return fragment.requireView().findViewById<WebView>(R.id.webview).layerType
    }

    private companion object {
        const val URL = "https://idle.example.test"
    }
}
