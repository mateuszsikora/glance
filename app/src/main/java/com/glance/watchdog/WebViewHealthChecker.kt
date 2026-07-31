package com.glance.watchdog

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glance.dashboard.WebViewFragment

class WebViewHealthChecker(
    private val timeoutMs: Long = 5000L,
    private val maxConsecutiveFailures: Int = 3
) {

    private val handler = Handler(Looper.getMainLooper())
    private var consecutiveFailures = 0
    private var checkGeneration = 0
    private var pendingTimeout: Runnable? = null
    private var pendingFragment: WebViewFragment? = null
    var onReloadNeeded: (() -> Unit)? = null
    var onRestartNeeded: (() -> Unit)? = null

    fun check(fragment: WebViewFragment?) {
        if (fragment == null || !fragment.isAdded || fragment.view == null) {
            Log.d(TAG, "Fragment view is unavailable, skipping health check")
            return
        }

        val generation = ++checkGeneration
        pendingTimeout?.let(handler::removeCallbacks)
        pendingFragment?.onHealthCheckCallback = null
        pendingFragment = fragment
        val completion = CompletionGate()

        fragment.onHealthCheckCallback = { healthy ->
            if (generation == checkGeneration && completion.tryComplete()) {
                pendingTimeout?.let(handler::removeCallbacks)
                pendingTimeout = null
                fragment.onHealthCheckCallback = null
                pendingFragment = null
                if (healthy) {
                    consecutiveFailures = 0
                    Log.d(TAG, "Health check passed")
                } else {
                    handleFailure()
                }
            }
        }

        pendingTimeout = Runnable {
            if (generation == checkGeneration && completion.tryComplete()) {
                fragment.onHealthCheckCallback = null
                pendingTimeout = null
                pendingFragment = null
                Log.w(TAG, "Health check timed out")
                handleFailure()
            }
        }.also { handler.postDelayed(it, timeoutMs) }

        // Register the timeout before invoking the fragment: unavailable or not-yet-loaded
        // WebViews can report a result synchronously.
        fragment.performHealthCheck()
    }

    private fun handleFailure() {
        consecutiveFailures++
        Log.w(TAG, "Health check failed ($consecutiveFailures/$maxConsecutiveFailures)")

        if (consecutiveFailures >= maxConsecutiveFailures) {
            Log.e(TAG, "Max failures reached, requesting restart")
            consecutiveFailures = 0
            onRestartNeeded?.invoke()
        } else {
            onReloadNeeded?.invoke()
        }
    }

    fun reset() {
        checkGeneration++
        pendingFragment?.onHealthCheckCallback = null
        pendingFragment = null
        pendingTimeout = null
        handler.removeCallbacksAndMessages(null)
        consecutiveFailures = 0
    }

    companion object {
        private const val TAG = "WebViewHealthChecker"
    }
}

internal class CompletionGate {
    private var completed = false

    fun tryComplete(): Boolean {
        if (completed) return false
        completed = true
        return true
    }
}
