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
    var onReloadNeeded: (() -> Unit)? = null
    var onRestartNeeded: (() -> Unit)? = null

    fun check(fragment: WebViewFragment?) {
        if (fragment == null) {
            Log.w(TAG, "Fragment is null, skipping health check")
            return
        }

        var responded = false

        fragment.onHealthCheckCallback = { healthy ->
            responded = true
            if (healthy) {
                consecutiveFailures = 0
                Log.d(TAG, "Health check passed")
            } else {
                handleFailure()
            }
        }

        fragment.performHealthCheck()

        handler.postDelayed({
            if (!responded) {
                Log.w(TAG, "Health check timed out")
                handleFailure()
            }
        }, timeoutMs)
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
        consecutiveFailures = 0
    }

    companion object {
        private const val TAG = "WebViewHealthChecker"
    }
}
