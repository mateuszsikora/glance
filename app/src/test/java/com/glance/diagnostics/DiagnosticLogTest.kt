package com.glance.diagnostics

import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var enabled = true
    private var memory: () -> String = { "system available=400/2748MB" }

    private fun log(maxFileBytes: Long = DiagnosticLog.DEFAULT_MAX_FILE_BYTES) = DiagnosticLog(
        directory = folder.root,
        enabled = { enabled },
        sampleMemory = { memory() },
        maxFileBytes = maxFileBytes,
        executor = { it.run() },
        now = { ZonedDateTime.of(2026, 10, 16, 13, 52, 42, 0, ZoneOffset.UTC) }
    )

    @Test
    fun disabledJournalNeverTouchesStorage() {
        enabled = false
        log().record("Idle screen shown", includeMemory = true)

        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun recordsTimestampedEventsInOrder() {
        val log = log()
        log.record("Idle screen shown")
        log.record("Idle screen hidden")

        assertEquals(
            "2026-10-16 13:52:42 Idle screen shown\n2026-10-16 13:52:42 Idle screen hidden\n",
            log.read()
        )
    }

    @Test
    fun appendsMemoryOnlyWhenAsked() {
        val log = log()
        log.record("Charger connected", includeMemory = true)
        log.record("Idle screen shown")

        val lines = log.read().lines()
        assertEquals("2026-10-16 13:52:42 Charger connected | system available=400/2748MB", lines[0])
        assertEquals("2026-10-16 13:52:42 Idle screen shown", lines[1])
    }

    @Test
    fun failedMemorySampleKeepsTheEvent() {
        memory = { throw IllegalStateException("unavailable") }
        val log = log()
        log.record("Trim memory level=10", includeMemory = true)

        assertEquals("2026-10-16 13:52:42 Trim memory level=10\n", log.read())
    }

    @Test
    fun rotatesIntoOnePredecessorAndReadsOldestFirst() {
        val log = log(maxFileBytes = 80)
        log.record("first event")
        log.record("second event")
        log.record("third event")
        log.record("fourth event")
        // Each entry is 32-33 bytes, so the third and fifth start a new file.
        log.record("fifth event")

        assertTrue(File(folder.root, "${DiagnosticLog.FILE_NAME}.old").exists())
        val entries = log.read().lines().filter(String::isNotBlank)
        assertEquals(
            listOf("third event", "fourth event", "fifth event"),
            entries.map { it.substringAfter("42 ") }
        )
        assertTrue(folder.root.listFiles().orEmpty().size <= 2)
    }

    @Test
    fun clearRemovesBothFiles() {
        val log = log(maxFileBytes = 80)
        repeat(4) { log.record("event $it") }

        log.clear()

        assertEquals("", log.read())
        assertFalse(File(folder.root, DiagnosticLog.FILE_NAME).exists())
        assertFalse(File(folder.root, "${DiagnosticLog.FILE_NAME}.old").exists())
    }

    @Test
    fun turningTheSwitchOffKeepsWhatWasRecorded() {
        val log = log()
        log.record("Idle screen shown")
        enabled = false
        log.record("Idle screen hidden")

        assertEquals("2026-10-16 13:52:42 Idle screen shown\n", log.read())
    }
}
