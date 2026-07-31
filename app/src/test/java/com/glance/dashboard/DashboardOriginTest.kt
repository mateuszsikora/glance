package com.glance.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardOriginTest {
    @Test
    fun normalizesDefaultPortsAndCase() {
        assertEquals(
            DashboardOrigin("https", "ha.example.test", 443),
            DashboardOrigin.from("HTTPS://HA.example.test/dashboard")
        )
        assertEquals(
            DashboardOrigin("http", "192.168.1.10", 80),
            DashboardOrigin.from("http://192.168.1.10/")
        )
    }

    @Test
    fun distinguishesSchemeAndPort() {
        val configured = DashboardOrigin.from("http://ha.example.test:8123")
        assertNotEquals(configured, DashboardOrigin.from("https://ha.example.test:8123"))
        assertNotEquals(configured, DashboardOrigin.from("http://ha.example.test:8124"))
    }

    @Test
    fun rejectsInvalidOrigins() {
        assertNull(DashboardOrigin.from(null))
        assertNull(DashboardOrigin.from("example.test/dashboard"))
        assertNull(DashboardOrigin.from("file:///tmp/dashboard.html"))
    }

    @Test
    fun navigationAllowsConfiguredAndExplicitAuthenticationOrigins() {
        val configured = "http://ha.example.test:8123/dashboard"
        val authenticationOrigins = listOf("https://login.example.test/oauth/start")

        assertEquals(
            true,
            DashboardNavigationPolicy.isAllowed(
                "http://ha.example.test:8123/auth/authorize",
                configured,
                authenticationOrigins
            )
        )
        assertEquals(
            true,
            DashboardNavigationPolicy.isAllowed(
                "https://login.example.test/callback",
                configured,
                authenticationOrigins
            )
        )
        assertEquals(
            false,
            DashboardNavigationPolicy.isAllowed(
                "https://untrusted.example.test/",
                configured,
                authenticationOrigins
            )
        )
    }
}
