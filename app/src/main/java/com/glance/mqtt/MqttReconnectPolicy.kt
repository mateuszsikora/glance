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

    /**
     * Delay before the [attempt]th retry alarm of [MqttReconnectAlarm]: 1, 2, 4 and 8 minutes, then
     * every 15. The alarm backs up the faster retries, which stop while the processor sleeps, so it
     * can be slow; each one wakes the tablet, and a broker that stays down all night costs about
     * 30 of them.
     */
    fun alarmDelayForAttempt(attempt: Int): Long {
        val exponent = min(attempt.coerceAtLeast(0), MAX_ALARM_EXPONENT)
        return min(
            INITIAL_ALARM_DELAY_MS * (1L shl exponent),
            MAX_ALARM_DELAY_MS
        )
    }

    private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
    private const val MAX_RECONNECT_DELAY_MS = 30_000L
    private const val MAX_RECONNECT_EXPONENT = 5

    private const val INITIAL_ALARM_DELAY_MS = 60_000L
    internal const val MAX_ALARM_DELAY_MS = 900_000L
    private const val MAX_ALARM_EXPONENT = 4
}
