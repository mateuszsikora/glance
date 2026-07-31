package com.glance.dashboard

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
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
    private var mainFrameError = false
    private var rendererGone = false

    private val retryRunnable = Runnable {
        if (_binding == null || rendererGone || !isAdded || url.isBlank()) return@Runnable
        Log.i(TAG, "Retrying load: $url")
        loadUrl(url)
    }

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
                    view?.removeCallbacks(retryRunnable)
                    isLoaded = false
                    mainFrameError = false
                    binding.errorContainer.visibility = View.GONE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isLoaded = !mainFrameError
                    Log.d(TAG, "Page loaded: $url")
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        mainFrameError = true
                        Log.e(TAG, "WebView error: ${error?.description}")
                        showError()
                        scheduleRetry()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame == true) {
                        mainFrameError = true
                        isLoaded = false
                        Log.e(TAG, "Dashboard HTTP error: ${errorResponse?.statusCode}")
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
                    mainFrameError = true
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
                    rendererGone = true
                    isLoaded = false
                    view?.removeCallbacks(retryRunnable)
                    view?.let { deadWebView ->
                        (deadWebView.parent as? ViewGroup)?.removeView(deadWebView)
                        deadWebView.stopLoading()
                        deadWebView.destroy()
                    }
                    activity?.recreate()
                    return true
                }
            }

            webChromeClient = WebChromeClient()
        }
    }

    fun loadUrl(newUrl: String) {
        if (rendererGone) {
            activity?.recreate()
            return
        }
        url = newUrl
        isLoaded = false
        binding.webview.loadUrl(url)
    }

    fun reload() {
        if (rendererGone) {
            activity?.recreate()
            return
        }
        isLoaded = false
        binding.webview.reload()
    }

    fun performHealthCheck() {
        if (rendererGone || !isLoaded || _binding == null) {
            onHealthCheckCallback?.invoke(false)
            return
        }

        binding.webview.evaluateJavascript(
            "(function() { return document.readyState; })()"
        ) { result ->
            val ready = result?.trim('"') in setOf("interactive", "complete")
            val configuredHost = runCatching { Uri.parse(url).host }.getOrNull()
            val currentUrl = _binding?.webview?.url
            val currentHost = runCatching { Uri.parse(currentUrl).host }.getOrNull()
            val healthy = ready && !mainFrameError && configuredHost == currentHost
            onHealthCheckCallback?.invoke(healthy)
        }
    }

    private fun showError() {
        binding.errorContainer.visibility = View.VISIBLE
    }

    private fun scheduleRetry() {
        binding.webview.removeCallbacks(retryRunnable)
        binding.webview.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    override fun onDestroyView() {
        _binding?.webview?.apply {
            removeCallbacks(retryRunnable)
            if (!rendererGone) {
                stopLoading()
                clearHistory()
                removeAllViews()
                destroy()
            }
        }
        onHealthCheckCallback = null
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
