package com.glance.watchdog

import java.net.ServerSocket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DashboardReachabilityProbeTest {

    @Test
    fun reportsAnsweringHostAsReachable() {
        val server = ServerSocket(0)
        val responder = Thread {
            server.accept().use { socket ->
                socket.getInputStream().bufferedReader().readLine()
                socket.getOutputStream().write(
                    "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n".toByteArray()
                )
            }
        }.apply { start() }

        try {
            // An authentication challenge still proves the dashboard host is up.
            assertTrue(DashboardReachabilityProbe.isReachable("http://127.0.0.1:${server.localPort}/"))
        } finally {
            responder.join(TIMEOUT_MS)
            server.close()
        }
    }

    @Test
    fun reportsRefusedConnectionAsUnreachable() {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(DashboardReachabilityProbe.isReachable("http://127.0.0.1:$port/"))
    }

    @Test
    fun treatsUnusableUrlsAsReachableSoNoReloadIsTriggered() {
        assertTrue(DashboardReachabilityProbe.isReachable(""))
        assertTrue(DashboardReachabilityProbe.isReachable("not a url"))
        assertTrue(DashboardReachabilityProbe.isReachable("ftp://192.0.2.1/dashboard"))
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
