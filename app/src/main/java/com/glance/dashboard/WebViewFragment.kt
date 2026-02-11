package com.glance.dashboard

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.fragment.app.Fragment
import com.glance.databinding.FragmentWebviewBinding

/**
 * Fragment wrapping a WebView that loads a single dashboard URL.
 * Handles errors gracefully and supports health checks from the watchdog.
 */
class WebViewFragment : Fragment() {

    private var _binding: FragmentWebviewBinding? = null
    private val binding get() = _binding!!

    private var url: String = ""
    private var isLoaded = false

    var onHealthCheckCallback: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = arguments?.getString(ARG_URL) ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWebviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupWebView()
        if (url.isNotBlank()) {
            loadUrl(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webview.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                @Suppress("DEPRECATION")
                setRenderPriority(WebSettings.RenderPriority.HIGH)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    binding.errorContainer.visibility = View.GONE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isLoaded = true
                    Log.d(TAG, "Page loaded: $url")
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        Log.e(TAG, "WebView error: ${error?.description}")
                        showError()
                        scheduleRetry()
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    // Accept self-signed certs for local dashboards
                    Log.w(TAG, "SSL error (accepting): ${error?.primaryError}")
                    handler?.proceed()
                }
            }

            webChromeClient = WebChromeClient()

            addJavascriptInterface(HealthCheckBridge(), "GlanceBridge")
        }
    }

    fun loadUrl(newUrl: String) {
        url = newUrl
        isLoaded = false
        binding.webview.loadUrl(url)
    }

    fun reload() {
        isLoaded = false
        binding.webview.reload()
    }

    fun performHealthCheck() {
        if (!isLoaded) {
            onHealthCheckCallback?.invoke(false)
            return
        }

        binding.webview.evaluateJavascript(
            "(function() { GlanceBridge.healthPong(); return 'ok'; })()"
        ) { result ->
            onHealthCheckCallback?.invoke(result != null)
        }
    }

    private fun showError() {
        binding.errorContainer.visibility = View.VISIBLE
    }

    private fun scheduleRetry() {
        binding.webview.postDelayed({
            if (isAdded && url.isNotBlank()) {
                Log.i(TAG, "Retrying load: $url")
                loadUrl(url)
            }
        }, RETRY_DELAY_MS)
    }

    override fun onDestroyView() {
        binding.webview.apply {
            stopLoading()
            clearHistory()
            removeAllViews()
            destroy()
        }
        _binding = null
        super.onDestroyView()
    }

    inner class HealthCheckBridge {
        @JavascriptInterface
        fun healthPong() {
            onHealthCheckCallback?.invoke(true)
        }
    }

    companion object {
        private const val TAG = "WebViewFragment"
        private const val ARG_URL = "url"
        private const val RETRY_DELAY_MS = 10_000L

        fun newInstance(url: String): WebViewFragment {
            return WebViewFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                }
            }
        }
    }
}
