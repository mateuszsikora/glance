package com.glance.dashboard

data class DashboardConfig(
    val urls: List<String>,
    val autoRotate: Boolean = false,
    val autoRotateIntervalSeconds: Int = 30
)
