package com.glance.remote

import java.net.Inet4Address
import java.net.NetworkInterface

object RemoteConfigAddress {
    const val PORT = 8080

    fun localUrls(port: Int = PORT): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { network ->
                network.inetAddresses.toList().asSequence().map { network.name to it }
            }
            .filter { (_, address) ->
                address is Inet4Address &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress &&
                    address.isSiteLocalAddress
            }
            .sortedWith(
                compareBy<Pair<String, java.net.InetAddress>>(
                    { interfacePriority(it.first) },
                    { it.second.hostAddress.orEmpty() }
                )
            )
            .mapNotNull { (_, address) -> address.hostAddress }
            .distinct()
            .map { "http://$it:$port/" }
            .toList()
    }.getOrDefault(emptyList())

    private fun interfacePriority(name: String): Int = when {
        name.startsWith("wlan", ignoreCase = true) -> 0
        name.startsWith("wifi", ignoreCase = true) -> 0
        name.startsWith("eth", ignoreCase = true) -> 1
        else -> 2
    }
}
