package com.glance.diagnostics

import android.util.Log
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Opt-in event journal for diagnosing reloads and memory pressure on the tablet itself.
 *
 * Logcat is not enough on a wall-mounted kiosk: it needs adb, its ring buffer holds only hours,
 * and some vendor builds (Huawei EMUI among them) drop application output altogether unless a
 * hidden service menu is used. This journal survives for days and is read from the remote panel.
 *
 * Every entry can carry the allocated native heap at the moment it was recorded, so the cost of
 * an event shows up as the step between consecutive entries without a full memory sample.
 *
 * Nothing is written while [enabled] is false, so normal operation causes no storage writes.
 * Entries never contain URLs, addresses, or credentials. The journal is bounded to two files:
 * the current one and its predecessor. Every file operation runs on the journal's own thread, so
 * reads and clears are ordered after every entry recorded before them.
 */
class DiagnosticLog(
    private val directory: File,
    private val enabled: () -> Boolean,
    private val sampleMemory: () -> String,
    private val sampleNativeBytes: (() -> Long)? = null,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, THREAD_NAME) },
    private val now: () -> ZonedDateTime = ZonedDateTime::now
) {
    private val current get() = File(directory, FILE_NAME)
    private val previous get() = File(directory, "$FILE_NAME.old")

    /** Until something is queued, files can be read directly and no thread is ever started. */
    @Volatile private var queued = false

    /**
     * Queues one entry. The timestamp is taken immediately; sampling memory and writing happen on
     * the journal's own thread, so callers on the main thread never wait for storage.
     */
    fun record(event: String, includeMemory: Boolean = false) {
        if (!enabled()) return
        val timestamp = TIMESTAMP_FORMAT.format(now())
        // Read here, not on the journal thread: the step between entries must belong to the event.
        val nativeBytes = sampleNativeBytes?.let { runCatching(it).getOrNull() }
        queued = true
        executor.execute {
            val memory = if (includeMemory) runCatching(sampleMemory).getOrNull() else null
            append(
                buildString {
                    append(timestamp).append(' ').append(event)
                    if (nativeBytes != null) {
                        append(String.format(Locale.US, " [native=%.1fMB]", nativeBytes / MIB))
                    }
                    if (memory != null) append(" | ").append(memory)
                    append('\n')
                }
            )
        }
    }

    /** Both files, oldest entry first. */
    fun read(): String = onJournalThread {
        listOf(previous, current)
            .filter(File::exists)
            .joinToString("") { it.readText() }
    }

    /** Waits for queued entries first, so none of them can recreate the file afterwards. */
    fun clear() {
        onJournalThread {
            current.delete()
            previous.delete()
        }
    }

    private fun <T> onJournalThread(task: () -> T): T {
        if (!queued) return task()
        return executor.submit(Callable(task)).get(FILE_OPERATION_TIMEOUT_S, TimeUnit.SECONDS)
    }

    private fun append(line: String) {
        try {
            val file = current
            val lineBytes = line.toByteArray(Charsets.UTF_8).size
            if (file.exists() && file.length() + lineBytes > maxFileBytes) {
                previous.delete()
                file.renameTo(previous)
            }
            file.appendText(line)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write the diagnostic log", e)
        }
    }

    companion object {
        private const val TAG = "DiagnosticLog"
        const val FILE_NAME = "glance_diagnostics.log"
        /** About a week of entries with a 5-minute memory sample and a busy day of events. */
        const val DEFAULT_MAX_FILE_BYTES = 1024L * 1024L
        private const val MIB = 1024.0 * 1024.0
        private const val THREAD_NAME = "glance-diagnostics"
        private const val FILE_OPERATION_TIMEOUT_S = 5L
        // The UTC offset keeps entries unambiguous across daylight-saving changes.
        private val TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxxx", Locale.US)
    }
}

/** Process-wide access to the journal; a no-op until [install] runs at application start. */
object Diagnostics {
    @Volatile private var log: DiagnosticLog? = null

    fun install(log: DiagnosticLog) {
        this.log = log
    }

    fun record(event: String, includeMemory: Boolean = false) {
        log?.record(event, includeMemory)
    }

    fun read(): String = log?.read().orEmpty()

    fun clear() {
        log?.clear()
    }
}
