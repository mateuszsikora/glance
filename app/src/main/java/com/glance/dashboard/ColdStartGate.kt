package com.glance.dashboard

/**
 * Holds back the first WebView of a new process until creating it no longer risks an ANR.
 *
 * The first WebView in a process starts Chromium on the main thread. On a 3 GB tablet swapping
 * hard after a low-memory kill, that took longer than Android's service and broadcast timeouts.
 * The service start commands and the lock task broadcast that the same launch had queued behind
 * it timed out, Android killed the new process as well, and the next relaunch repeated it.
 *
 * The gate opens once both hold:
 * - the work with a deadline that the launch queued on the main thread has run: the start
 *   commands of both services and, when entering lock task mode, the admin receiver's broadcast.
 *   Each normally takes milliseconds; after [START_WORK_TIMEOUT_MS] the gate stops waiting.
 * - the system is not short of memory ([MemoryPressure]). While it is, the gate checks again
 *   with a growing delay, and after [MAX_MEMORY_WAIT_MS] it opens anyway: the dashboard comes
 *   back late rather than never.
 *
 * Elapsed-realtime milliseconds. Main-thread API.
 */
class ColdStartGate(private val createdAtMs: Long) {

    sealed class Decision {
        /** @param gaveUp a condition was still unmet when its time limit ran out. */
        data class Open(val gaveUp: Boolean) : Decision()
        data class WaitForStartWork(val delayMs: Long) : Decision()
        data class WaitForMemory(val delayMs: Long) : Decision()
    }

    private var startWorkTimedOut = false
    private var memoryWaits = 0

    /** @param memoryPressure asked only once the start work has settled. */
    fun check(nowMs: Long, startWorkSettled: Boolean, memoryPressure: () -> Boolean): Decision {
        val waitedMs = nowMs - createdAtMs
        if (!startWorkSettled && !startWorkTimedOut) {
            if (waitedMs < START_WORK_TIMEOUT_MS) return Decision.WaitForStartWork(START_WORK_POLL_MS)
            startWorkTimedOut = true
        }
        val pressure = memoryPressure()
        if (pressure && waitedMs < MAX_MEMORY_WAIT_MS) {
            val backoffMs = (MEMORY_FIRST_DELAY_MS shl memoryWaits.coerceAtMost(MAX_DOUBLINGS))
                .coerceAtMost(MEMORY_MAX_DELAY_MS)
            memoryWaits++
            return Decision.WaitForMemory(minOf(backoffMs, MAX_MEMORY_WAIT_MS - waitedMs))
        }
        return Decision.Open(gaveUp = startWorkTimedOut || pressure)
    }

    companion object {
        /**
         * Set once the first dashboard WebView of this process is about to be created; Chromium
         * then stays initialised, so later Activities, such as a recreation, skip the gate.
         */
        @Volatile
        var webViewStarted = false
            internal set

        const val START_WORK_POLL_MS = 200L
        const val START_WORK_TIMEOUT_MS = 10_000L
        const val MEMORY_FIRST_DELAY_MS = 2_000L
        const val MEMORY_MAX_DELAY_MS = 30_000L
        const val MAX_MEMORY_WAIT_MS = 3 * 60_000L
        private const val MAX_DOUBLINGS = 4
    }
}
