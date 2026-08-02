package com.glance.content

class IdleTimeoutTracker(timeoutMs: Long) {
    private val timeoutMs = timeoutMs.coerceAtLeast(1L)
    private var lastActivityElapsedMs = 0L

    fun recordActivity(nowElapsedMs: Long) {
        lastActivityElapsedMs = nowElapsedMs
    }

    fun remainingMs(nowElapsedMs: Long): Long {
        val elapsed = (nowElapsedMs - lastActivityElapsedMs).coerceAtLeast(0L)
        return (timeoutMs - elapsed).coerceAtLeast(0L)
    }

    fun isExpired(nowElapsedMs: Long): Boolean = remainingMs(nowElapsedMs) == 0L
}
