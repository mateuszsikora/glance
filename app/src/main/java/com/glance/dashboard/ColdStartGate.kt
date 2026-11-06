package com.glance.dashboard

/**
 * Holds back the first WebView of a new process until creating it no longer risks an ANR.
 *
 * The first WebView in a process starts Chromium on the main thread. On a 3 GB tablet swapping
 * hard after a low-memory kill, that took longer than Android's service and broadcast timeouts.
 * The service start commands and the lock task broadcast that the same launch had queued behind
 * it timed out, Android killed the new process as well, and the next relaunch repeated it.
 *
 * The gate opens once all of these hold:
 * - the Activity is started. A stopped one lays nothing out, so the WebView would only appear
 *   at the next start, at a moment nobody checked; the gate checks again then instead.
 * - the work with a deadline that the launch queued on the main thread has run ([LaunchWork]).
 *   It normally takes milliseconds; after [START_WORK_TIMEOUT_MS] the gate stops waiting.
 * - the system is not short of memory ([MemoryPressure]). While it is, the gate checks again
 *   with a growing delay, and after [MAX_MEMORY_WAIT_MS] it opens anyway: the dashboard comes
 *   back late rather than never. The limit bounds a black screen someone can see, so it starts
 *   over when a stopped Activity starts again, but not when a new Activity takes over the wait.
 *
 * One gate serves the whole process: a recreated Activity calls [onLaunch] on the same one.
 * Elapsed-realtime milliseconds. Main-thread API.
 */
class ColdStartGate(createdAtMs: Long) {

    sealed class Decision {
        /** @param gaveUp a condition was still unmet when its time limit ran out. */
        data class Open(val gaveUp: Boolean) : Decision()
        data class WaitForStartWork(val delayMs: Long) : Decision()
        data class WaitForMemory(val delayMs: Long) : Decision()
        /** Check again once the Activity starts. */
        object WaitForStart : Decision()
    }

    private var startWorkSinceMs = createdAtMs
    private var startWorkTimedOut = false
    private var memoryWaitSinceMs = createdAtMs
    private var memoryWaits = 0
    private var stopped = false

    /** @return the time since the gate was created or, after a stop, its first check since then. */
    fun visibleWaitMs(nowMs: Long): Long = nowMs - memoryWaitSinceMs

    /** A new Activity took over the wait; the start work it queues is awaited afresh. */
    fun onLaunch(nowMs: Long) {
        startWorkSinceMs = nowMs
        startWorkTimedOut = false
    }

    /** The Activity stopped; the memory time limit starts over at its next check while started. */
    fun onStopped() {
        stopped = true
    }

    /** @param memoryPressure asked only once the Activity is started and the start work settled. */
    fun check(
        nowMs: Long,
        started: Boolean,
        startWorkSettled: Boolean,
        memoryPressure: () -> Boolean
    ): Decision {
        if (!started) {
            onStopped()
            return Decision.WaitForStart
        }
        if (stopped) {
            stopped = false
            memoryWaitSinceMs = nowMs
            memoryWaits = 0
        }
        if (!startWorkSettled && !startWorkTimedOut) {
            if (nowMs - startWorkSinceMs < START_WORK_TIMEOUT_MS) {
                return Decision.WaitForStartWork(START_WORK_POLL_MS)
            }
            startWorkTimedOut = true
        }
        val waitedMs = nowMs - memoryWaitSinceMs
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
         * Set once the first dashboard WebView of this process is created; Chromium then stays
         * initialised, so later Activities, such as a recreation, skip the gate.
         */
        @Volatile
        var webViewStarted = false
            internal set

        const val START_WORK_POLL_MS = 200L
        const val START_WORK_TIMEOUT_MS = 10_000L
        const val MEMORY_FIRST_DELAY_MS = 2_000L
        const val MEMORY_MAX_DELAY_MS = 30_000L
        const val MAX_MEMORY_WAIT_MS = 3 * 60_000L

        /**
         * Waking the display swaps hard for seconds. With little memory available that holds the
         * page back too, which does not need an explanation.
         */
        const val MEMORY_NOTE_DELAY_MS = 15_000L
        private const val MAX_DOUBLINGS = 4
    }
}
