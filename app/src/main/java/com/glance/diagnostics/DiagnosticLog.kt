package com.glance.diagnostics

import android.util.Log
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Opt-in event journal for diagnosing reloads and memory pressure on the tablet itself.
 *
 * Logcat is not enough on a wall-mounted kiosk: it needs adb, its ring buffer holds only hours,
 * and some vendor builds (Huawei EMUI among them) drop application output altogether unless a
 * hidden service menu is used. This journal survives for days and is read from the remote panel.
 *
 * Nothing is written while [enabled] is false, so normal operation causes no storage writes.
 * Entries never contain URLs, addresses, or credentials. The journal is bounded to two files:
 * the current one and its predecessor.
 */
class DiagnosticLog(
    private val directory: File,
    private val enabled: () -> Boolean,
    private val sampleMemory: () -> String,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val now: () -> ZonedDateTime = ZonedDateTime::now
) {
    private val current get() = File(directory, FILE_NAME)
    private val previous get() = File(directory, "$FILE_NAME.old")

    /**
     * Queues one entry. The timestamp is taken immediately; sampling memory and writing happen on
     * the journal's own thread, so callers on the main thread never wait for storage.
     */
    fun record(event: String, includeMemory: Boolean = false) {
        if (!enabled()) return
        val timestamp = TIMESTAMP_FORMAT.format(now())
        executor.execute {
            val memory = if (includeMemory) runCatching(sampleMemory).getOrNull() else null
            append(
                buildString {
                    append(timestamp).append(' ').append(event)
                    if (memory != null) append(" | ").append(memory)
                    append('\n')
                }
            )
        }
    }

    /** Both files, oldest entry first. */
    @Synchronized
    fun read(): String = listOf(previous, current)
        .filter(File::exists)
        .joinToString("") { it.readText() }

    @Synchronized
    fun clear() {
        current.delete()
        previous.delete()
    }

    @Synchronized
    private fun append(line: String) {
        try {
            val file = current
            if (file.exists() && file.length() + line.length > maxFileBytes) {
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
        /** Around a week of entries with the 15-minute memory sample and a busy day of events. */
        const val DEFAULT_MAX_FILE_BYTES = 256L * 1024L
        private val TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
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
