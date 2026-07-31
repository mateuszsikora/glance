package com.glance.dashboard

import java.net.URI
import java.util.Locale

/** Exact web origin used to keep the kiosk inside its configured dashboard. */
data class DashboardOrigin(
    val scheme: String,
    val host: String,
    val port: Int
) {
    companion object {
        fun from(url: String?): DashboardOrigin? {
            if (url.isNullOrBlank()) return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val port = when {
                uri.port >= 0 -> uri.port
                scheme == "http" -> 80
                scheme == "https" -> 443
                else -> return null
            }
            return DashboardOrigin(scheme, host, port)
        }
    }
}
