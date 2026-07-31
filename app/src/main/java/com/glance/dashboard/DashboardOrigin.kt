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

/** Navigation policy shared by WebView routing and health checks. */
object DashboardNavigationPolicy {
    fun allowedOrigins(
        configuredUrl: String,
        additionalOriginUrls: Collection<String>
    ): Set<DashboardOrigin> {
        return (additionalOriginUrls + configuredUrl)
            .mapNotNull(DashboardOrigin::from)
            .toSet()
    }

    fun isAllowed(
        destinationUrl: String?,
        configuredUrl: String,
        additionalOriginUrls: Collection<String>
    ): Boolean {
        val destination = DashboardOrigin.from(destinationUrl) ?: return false
        return destination in allowedOrigins(configuredUrl, additionalOriginUrls)
    }
}
