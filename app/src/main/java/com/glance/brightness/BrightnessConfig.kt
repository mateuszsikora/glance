package com.glance.brightness

data class BrightnessConfig(
    val minBrightness: Int = 5,
    val maxBrightness: Int = 255,
    val enabled: Boolean = true
)
