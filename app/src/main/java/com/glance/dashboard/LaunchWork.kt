package com.glance.dashboard

import java.util.concurrent.atomic.AtomicInteger

/**
 * Main-thread work with an ANR deadline that one dashboard launch queues: the start commands it
 * sends both services and, when it enters lock task mode, the admin receiver's broadcast.
 *
 * Each start command carries the launch's [token] in [EXTRA_LAUNCH_TOKEN], and each service
 * records the newest token it has run, so an earlier command still queued for the same service,
 * such as the boot receiver's, does not count. The lock task broadcast carries no token; it is
 * counted from when this launch began.
 */
class LaunchWork(
    val token: Int,
    private val kioskTokenHandled: () -> Int,
    private val watchdogTokenHandled: () -> Int,
    private val lockTaskEntries: () -> Int
) {
    private val lockTaskEntriesBefore = lockTaskEntries()

    /** Set once the launch has asked to enter lock task mode from outside it. */
    var lockTaskBroadcastExpected = false

    val settled: Boolean
        get() = kioskTokenHandled() >= token &&
            watchdogTokenHandled() >= token &&
            (!lockTaskBroadcastExpected || lockTaskEntries() > lockTaskEntriesBefore)

    companion object {
        const val EXTRA_LAUNCH_TOKEN = "launch_token"

        private val tokens = AtomicInteger()

        fun nextToken(): Int = tokens.incrementAndGet()

        /** For a service, first thing in each start command; 0 means the start had no token. */
        fun recordHandled(newestHandled: AtomicInteger, token: Int) {
            if (token > 0) newestHandled.accumulateAndGet(token, ::maxOf)
        }
    }
}
