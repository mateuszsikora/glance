package com.glance.dashboard

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Main-thread work with an ANR deadline that one dashboard launch queues: the start commands it
 * sends both services and, when it enters lock task mode, the admin receiver's broadcast.
 *
 * Each start command carries the launch's [token] in [EXTRA_LAUNCH_TOKEN], and each service
 * records the newest token it has run, so an earlier command still queued for the same service,
 * such as the boot receiver's, does not count. Tokens follow the time since boot, so a command
 * that Android delivers again after the previous process died mid-start, as in the ANR loop this
 * guards against, always carries a smaller token than any launch in the new process. The lock
 * task broadcast carries no token; it is counted from when this launch began.
 */
class LaunchWork(
    val token: Long,
    private val kioskTokenHandled: () -> Long,
    private val watchdogTokenHandled: () -> Long,
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

        private val lastToken = AtomicLong()

        /** @return a token above every earlier one since boot, in this process or a previous one. */
        fun nextToken(nowNanos: Long = SystemClock.elapsedRealtimeNanos()): Long =
            lastToken.updateAndGet { maxOf(it + 1, nowNanos) }

        /** For a service, first thing in each start command; 0 means the start had no token. */
        fun recordHandled(newestHandled: AtomicLong, token: Long) {
            if (token > 0) newestHandled.accumulateAndGet(token, ::maxOf)
        }
    }
}
