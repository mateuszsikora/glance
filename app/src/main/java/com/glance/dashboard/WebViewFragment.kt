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
import com.glance.watchdog.CrashLogger

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
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
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
                    isLoaded = false
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
                    Log.e(TAG, "SSL validation failed: ${error?.primaryError}")
                    isLoaded = false
                    handler?.cancel()
                    showError()
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    val message = "WebView renderer gone (crashed=${detail?.didCrash() == true})"
                    Log.e(TAG, message)
                    context?.let { CrashLogger.log(it, "ERROR", message) }
                    view?.post { activity?.recreate() }
                    return true
                }
            }

            webChromeClient = WebChromeClient()
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
            "(function() { return document.readyState; })()"
        ) { result ->
            val healthy = result?.trim('"') in setOf("interactive", "complete")
            onHealthCheckCallback?.invoke(healthy)
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
