package com.glance.watchdog

import android.content.Context
import android.util.Log
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val LOG_FILE = "glance_crash.log"
    private const val MAX_FILE_SIZE = 1024 * 1024 // 1MB

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun log(context: Context, level: String, message: String, throwable: Throwable? = null) {
        try {
            val file = File(context.filesDir, LOG_FILE)

            if (file.exists() && file.length() > MAX_FILE_SIZE) {
                val backup = File(context.filesDir, "$LOG_FILE.old")
                backup.delete()
                file.renameTo(backup)
            }

            val timestamp = dateFormat.format(ZonedDateTime.now())
            val entry = buildString {
                append("[$timestamp] $level: $message")
                if (throwable != null) {
                    append("\n  ${throwable.javaClass.simpleName}: ${throwable.message}")
                    throwable.stackTrace.take(5).forEach { frame ->
                        append("\n    at $frame")
                    }
                }
                append("\n")
            }

            file.appendText(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash log", e)
        }
    }

    @Synchronized
    fun readLog(context: Context): String {
        return try {
            val file = File(context.filesDir, LOG_FILE)
            if (file.exists()) file.readText() else "(no logs)"
        } catch (e: Exception) {
            "(error reading logs: ${e.message})"
        }
    }

    @Synchronized
    fun clearLog(context: Context) {
        try {
            File(context.filesDir, LOG_FILE).delete()
            File(context.filesDir, "$LOG_FILE.old").delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }
}
