package com.glance.watchdog

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks the dashboard host whether it is still answering, without involving the WebView.
 *
 * Any HTTP status counts as reachable: an authentication challenge or an error page still
 * proves the server is up. Anything that is not a transport failure — a malformed URL, a
 * non-HTTP scheme — is reported as reachable too, because an absent signal must never be
 * mistaken for an outage and trigger a reload.
 *
 * Blocking network call: never invoke this from the main thread.
 */
object DashboardReachabilityProbe {

    fun isReachable(
        url: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): Boolean {
        val target = runCatching { URL(url) }.getOrNull() ?: return true
        val connection = runCatching { target.openConnection() }.getOrNull()
            as? HttpURLConnection ?: return true

        return try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.responseCode > 0
        } catch (e: IOException) {
            Log.d(TAG, "Dashboard host is unreachable (${e.javaClass.simpleName})")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Dashboard probe could not run", e)
            true
        } finally {
            connection.disconnect()
        }
    }

    private const val TAG = "DashboardProbe"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
}
