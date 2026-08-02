package com.glance.content

/** A set of dashboard URLs that becomes active at [startTime] in the device's local time. */
data class ContentProfile(
    val startTime: String,
    val urls: List<String>
)
