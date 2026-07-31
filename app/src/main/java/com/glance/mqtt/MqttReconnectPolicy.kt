package com.glance.mqtt

import kotlin.math.min

object MqttReconnectPolicy {
    fun delayForAttempt(attempt: Int): Long {
        val exponent = min(attempt.coerceAtLeast(0), MAX_RECONNECT_EXPONENT)
        return min(
            INITIAL_RECONNECT_DELAY_MS * (1L shl exponent),
            MAX_RECONNECT_DELAY_MS
        )
    }

    private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
    private const val MAX_RECONNECT_DELAY_MS = 30_000L
    private const val MAX_RECONNECT_EXPONENT = 5
}
