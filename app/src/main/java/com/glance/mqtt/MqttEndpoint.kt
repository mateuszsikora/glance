package com.glance.mqtt

import java.net.URI
import java.util.Locale

/** Normalizes the broker field and the separate port field into a Paho server URI. */
object MqttEndpoint {
    fun serverUri(value: String, configuredPort: Int): String {
        require(configuredPort in 1..65535) { "MQTT port must be between 1 and 65535" }
        val raw = value.trim().trimEnd('/')
        require(raw.isNotBlank() && raw.none(Char::isWhitespace)) { "MQTT host is empty or invalid" }

        val schemeSeparator = raw.indexOf("://")
        if (schemeSeparator >= 0) {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            require(scheme == "tcp" || scheme == "ssl") { "MQTT scheme must be tcp or ssl" }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "MQTT URI cannot contain credentials, query, or fragment"
            }
            require(uri.path.isNullOrEmpty()) { "MQTT URI cannot contain a path" }
            val host = uri.host?.removeSurrounding("[", "]")
            require(!host.isNullOrBlank()) { "MQTT URI must contain a host" }
            val port = uri.port.takeIf { it != -1 } ?: configuredPort
            require(port in 1..65535) { "MQTT URI port must be between 1 and 65535" }
            return "$scheme://${formatHost(host)}:$port"
        }

        require(!raw.contains('/') && !raw.contains('?') && !raw.contains('#')) {
            "MQTT host cannot contain a path, query, or fragment"
        }
        val host = raw.removeSurrounding("[", "]")
        val candidate = URI("tcp://${formatHost(host)}:$configuredPort")
        require(!candidate.host.isNullOrBlank()) { "Invalid MQTT host" }
        return "tcp://${formatHost(host)}:$configuredPort"
    }

    private fun formatHost(host: String): String {
        return if (host.contains(':')) "[$host]" else host
    }
}
