package com.glance.diagnostics

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val executors = mutableListOf<ExecutorService>()
    private var enabled = true
    private var memory: () -> String = { "system available=400/2748MB" }

    private fun log(maxFileBytes: Long = DiagnosticLog.DEFAULT_MAX_FILE_BYTES) = DiagnosticLog(
        directory = folder.root,
        enabled = { enabled },
        sampleMemory = { memory() },
        maxFileBytes = maxFileBytes,
        executor = executor(),
        now = { ZonedDateTime.of(2026, 10, 16, 13, 52, 42, 0, ZoneOffset.UTC) }
    )

    @After
    fun shutDownExecutors() {
        executors.forEach(ExecutorService::shutdownNow)
    }

    private fun executor(): ExecutorService =
        Executors.newSingleThreadExecutor().also(executors::add)

    @Test
    fun disabledJournalNeverTouchesStorage() {
        enabled = false
        log().record("Idle screen shown", includeMemory = true)

        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun everyEntryCarriesTheNativeHeapWhenASamplerIsGiven() {
        var nativeBytes = 23_400_000L
        val log = DiagnosticLog(
            directory = folder.root,
            enabled = { true },
            sampleMemory = { memory() },
            sampleNativeBytes = { nativeBytes },
            executor = executor(),
            now = { ZonedDateTime.of(2026, 10, 16, 13, 52, 42, 0, ZoneOffset.UTC) }
        )
        log.record("Reload broadcast")
        nativeBytes = 24_500_000L
        log.record("Idle screen shown", includeMemory = true)

        assertEquals(
            "2026-10-16 13:52:42+00:00 Reload broadcast [native=22.3MB]\n" +
                "2026-10-16 13:52:42+00:00 Idle screen shown [native=23.4MB] | system available=400/2748MB\n",
            log.read()
        )
    }

    @Test
    fun recordsTimestampedEventsInOrder() {
        val log = log()
        log.record("Idle screen shown")
        log.record("Idle screen hidden")

        assertEquals(
            "2026-10-16 13:52:42+00:00 Idle screen shown\n2026-10-16 13:52:42+00:00 Idle screen hidden\n",
            log.read()
        )
    }

    @Test
    fun appendsMemoryOnlyWhenAsked() {
        val log = log()
        log.record("Charger connected", includeMemory = true)
        log.record("Idle screen shown")

        val lines = log.read().lines()
        assertEquals("2026-10-16 13:52:42+00:00 Charger connected | system available=400/2748MB", lines[0])
        assertEquals("2026-10-16 13:52:42+00:00 Idle screen shown", lines[1])
    }

    @Test
    fun failedMemorySampleKeepsTheEvent() {
        memory = { throw IllegalStateException("unavailable") }
        val log = log()
        log.record("Trim memory level=10", includeMemory = true)

        assertEquals("2026-10-16 13:52:42+00:00 Trim memory level=10\n", log.read())
    }

    @Test
    fun rotatesIntoOnePredecessorAndReadsOldestFirst() {
        val log = log(maxFileBytes = 80)
        log.record("first event")
        log.record("second event")
        log.record("third event")
        log.record("fourth event")
        // Each entry is 38-39 bytes, so the third and fifth start a new file.
        log.record("fifth event")

        val entries = log.read().lines().filter(String::isNotBlank)
        assertTrue(File(folder.root, "${DiagnosticLog.FILE_NAME}.old").exists())
        assertEquals(
            listOf("third event", "fourth event", "fifth event"),
            entries.map { it.substringAfter("+00:00 ") }
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
    fun clearWaitsForQueuedEntriesSoNoneReappear() {
        memory = {
            Thread.sleep(50)
            "system available=400/2748MB"
        }
        val log = log()
        repeat(3) { log.record("Trim memory level=15", includeMemory = true) }

        log.clear()

        assertEquals("", log.read())
        assertFalse(File(folder.root, DiagnosticLog.FILE_NAME).exists())
    }

    @Test
    fun timestampsCarryTheUtcOffset() {
        val log = DiagnosticLog(
            directory = folder.root,
            enabled = { true },
            sampleMemory = { "" },
            executor = executor(),
            now = { ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, ZoneOffset.ofHours(2)) }
        )
        log.record("Memory sample")

        assertEquals("2026-10-25 02:30:00+02:00 Memory sample\n", log.read())
    }

    @Test
    fun journalThatNeverRecordedNeedsNoThread() {
        // A shut-down executor rejects every task, so any use of it would fail this test.
        val unusable = Executors.newSingleThreadExecutor().apply { shutdown() }
        val log = DiagnosticLog(
            directory = folder.root,
            enabled = { false },
            sampleMemory = { "" },
            executor = unusable
        )
        log.record("Idle screen shown")

        assertEquals("", log.read())
        log.clear()
    }

    @Test
    fun turningTheSwitchOffKeepsWhatWasRecorded() {
        val log = log()
        log.record("Idle screen shown")
        enabled = false
        log.record("Idle screen hidden")

        assertEquals("2026-10-16 13:52:42+00:00 Idle screen shown\n", log.read())
    }
}
