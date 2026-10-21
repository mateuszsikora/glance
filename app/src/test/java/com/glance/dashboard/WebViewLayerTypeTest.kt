package com.glance.dashboard

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric's WebView ignores setLayerType, so the fragment's argument is what can be checked.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WebViewLayerTypeTest {

    @Test
    fun pagesKeepTheirHardwareLayerUnlessToldOtherwise() {
        assertEquals(View.LAYER_TYPE_HARDWARE, WebViewFragment.newInstance(URL).layerType)
    }

    @Test
    fun theChosenLayerTypeIsCarriedToTheFragment() {
        for (layerType in listOf(View.LAYER_TYPE_NONE, View.LAYER_TYPE_SOFTWARE)) {
            assertEquals(layerType, WebViewFragment.newInstance(URL, layerType = layerType).layerType)
        }
    }

    private companion object {
        const val URL = "https://idle.example.test"
    }
}
