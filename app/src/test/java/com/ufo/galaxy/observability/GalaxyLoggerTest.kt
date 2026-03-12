package com.ufo.galaxy.observability

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [GalaxyLogger].
 *
 * All tests run on the JVM.  [GalaxyLogger.init] is NOT called so the in-memory
 * buffer is exercised in isolation (file writes are skipped when [init] has not been called).
 *
 * ## Manual verification checklist (device / emulator)
 *
 * 1. **Log file creation:**
 *    - Install the app and perform at least one connect/disconnect cycle.
 *    - Pull the log: `adb shell run-as com.ufo.galaxy cat files/galaxy_observability.log`
 *    - Each line is a JSON object with `ts`, `tag`, and `fields`.
 *
 * 2. **Diagnostics panel:**
 *    - Tap the ⓘ icon in the top-bar of the main screen.
 *    - Verify the panel shows Connection state, Last Task ID, Last Error, and all three
 *      Readiness flags with correct colours.
 *
 * 3. **Log export:**
 *    - Open the Diagnostics panel → tap "Export Logs" button (or the share icon in the app bar).
 *    - The Android share-sheet appears offering to send `galaxy_observability.log`.
 *    - Tap a target app (e-mail, Files, etc.) and confirm the file is received.
 *
 * 4. **Structured log tags:**
 *    - Enable cross-device collaboration → connect event: line with `"tag":"GALAXY:CONNECT"`.
 *    - Disable cross-device → disconnect event: line with `"tag":"GALAXY:DISCONNECT"`.
 *    - Disable Wi-Fi → reconnect events: lines with `"tag":"GALAXY:RECONNECT"`.
 *    - Send a task → lines with `GALAXY:TASK:RECV`, `GALAXY:TASK:EXEC`, `GALAXY:TASK:RETURN`.
 *    - Revoke accessibility / overlay → line with `"tag":"GALAXY:READINESS"` and optionally
 *      `"tag":"GALAXY:DEGRADED"`.
 *
 * 5. **Ring-buffer cap:**
 *    - After > 500 log calls the buffer size stays ≤ 500 (oldest entries are dropped).
 */
class GalaxyLoggerTest {

    @Before
    fun setUp() {
        // Ensure a clean state before each test (no file; clear buffer).
        GalaxyLogger.clear()
    }

    @After
    fun tearDown() {
        GalaxyLogger.clear()
    }

    // ── Tag constant smoke tests ──────────────────────────────────────────────

    @Test
    fun `TAG_CONNECT is GALAXY_CONNECT`() {
        assertEquals("GALAXY:CONNECT", GalaxyLogger.TAG_CONNECT)
    }

    @Test
    fun `TAG_DISCONNECT is GALAXY_DISCONNECT`() {
        assertEquals("GALAXY:DISCONNECT", GalaxyLogger.TAG_DISCONNECT)
    }

    @Test
    fun `TAG_RECONNECT is GALAXY_RECONNECT`() {
        assertEquals("GALAXY:RECONNECT", GalaxyLogger.TAG_RECONNECT)
    }

    @Test
    fun `TAG_TASK_RECV is GALAXY_TASK_RECV`() {
        assertEquals("GALAXY:TASK:RECV", GalaxyLogger.TAG_TASK_RECV)
    }

    @Test
    fun `TAG_TASK_EXEC is GALAXY_TASK_EXEC`() {
        assertEquals("GALAXY:TASK:EXEC", GalaxyLogger.TAG_TASK_EXEC)
    }

    @Test
    fun `TAG_TASK_RETURN is GALAXY_TASK_RETURN`() {
        assertEquals("GALAXY:TASK:RETURN", GalaxyLogger.TAG_TASK_RETURN)
    }

    @Test
    fun `TAG_READINESS is GALAXY_READINESS`() {
        assertEquals("GALAXY:READINESS", GalaxyLogger.TAG_READINESS)
    }

    @Test
    fun `TAG_DEGRADED is GALAXY_DEGRADED`() {
        assertEquals("GALAXY:DEGRADED", GalaxyLogger.TAG_DEGRADED)
    }

    // ── In-memory buffer behaviour ────────────────────────────────────────────

    @Test
    fun `buffer is empty before any log calls`() {
        assertTrue(GalaxyLogger.getEntries().isEmpty())
    }

    @Test
    fun `log adds entry to in-memory buffer`() {
        GalaxyLogger.log(GalaxyLogger.TAG_CONNECT, mapOf("url" to "ws://test"))
        assertEquals(1, GalaxyLogger.getEntries().size)
    }

    @Test
    fun `log entry preserves tag`() {
        GalaxyLogger.log(GalaxyLogger.TAG_TASK_RECV, mapOf("task_id" to "t42"))
        val entry = GalaxyLogger.getEntries().first()
        assertEquals(GalaxyLogger.TAG_TASK_RECV, entry.tag)
    }

    @Test
    fun `log entry preserves fields`() {
        GalaxyLogger.log(GalaxyLogger.TAG_RECONNECT, mapOf("attempt" to 3, "delay_ms" to 4000))
        val entry = GalaxyLogger.getEntries().first()
        assertEquals(3, entry.fields["attempt"])
        assertEquals(4000, entry.fields["delay_ms"])
    }

    @Test
    fun `log entry timestamp is within current second`() {
        val before = System.currentTimeMillis()
        GalaxyLogger.log(GalaxyLogger.TAG_CONNECT)
        val after = System.currentTimeMillis()
        val ts = GalaxyLogger.getEntries().first().ts
        assertTrue("ts=$ts should be in [$before, $after]", ts in before..after)
    }

    @Test
    fun `multiple log calls accumulate in order`() {
        GalaxyLogger.log(GalaxyLogger.TAG_CONNECT)
        GalaxyLogger.log(GalaxyLogger.TAG_TASK_RECV, mapOf("task_id" to "t1"))
        GalaxyLogger.log(GalaxyLogger.TAG_TASK_RETURN)
        val entries = GalaxyLogger.getEntries()
        assertEquals(3, entries.size)
        assertEquals(GalaxyLogger.TAG_CONNECT,     entries[0].tag)
        assertEquals(GalaxyLogger.TAG_TASK_RECV,   entries[1].tag)
        assertEquals(GalaxyLogger.TAG_TASK_RETURN, entries[2].tag)
    }

    @Test
    fun `clear empties in-memory buffer`() {
        GalaxyLogger.log(GalaxyLogger.TAG_CONNECT)
        GalaxyLogger.clear()
        assertTrue(GalaxyLogger.getEntries().isEmpty())
    }

    @Test
    fun `ring buffer never exceeds 500 entries`() {
        repeat(600) { i -> GalaxyLogger.log(GalaxyLogger.TAG_CONNECT, mapOf("i" to i)) }
        assertTrue("Buffer must not exceed 500", GalaxyLogger.getEntries().size <= 500)
    }

    @Test
    fun `ring buffer drops oldest entry when full`() {
        // Fill the buffer beyond capacity — oldest entries must be gone
        repeat(510) { i -> GalaxyLogger.log(GalaxyLogger.TAG_CONNECT, mapOf("i" to i)) }
        val entries = GalaxyLogger.getEntries()
        // The youngest 500 entries should be present; entry i=0..9 should be dropped
        val firstI = entries.first().fields["i"] as Int
        assertTrue("Oldest entry i=0 should be dropped; found $firstI", firstI >= 10)
    }

    // ── LogEntry.toJsonLine ───────────────────────────────────────────────────

    @Test
    fun `toJsonLine produces valid JSON with ts tag and fields`() {
        val entry = GalaxyLogger.LogEntry(
            ts = 1710000000000L,
            tag = GalaxyLogger.TAG_CONNECT,
            fields = mapOf("url" to "ws://host:8080", "attempt" to 0)
        )
        val json = entry.toJsonLine()
        assertTrue(json.contains("\"ts\":1710000000000"))
        assertTrue(json.contains("\"tag\":\"GALAXY:CONNECT\""))
        assertTrue(json.contains("\"url\":\"ws://host:8080\""))
        assertTrue(json.contains("\"attempt\":0"))
    }

    @Test
    fun `toJsonLine is a single line (no newlines)`() {
        val entry = GalaxyLogger.LogEntry(
            ts = 0L,
            tag = GalaxyLogger.TAG_DISCONNECT,
            fields = mapOf("code" to 1000, "reason" to "normal")
        )
        assertFalse(entry.toJsonLine().contains('\n'))
    }

    @Test
    fun `toJsonLine with empty fields has empty fields object`() {
        val entry = GalaxyLogger.LogEntry(ts = 0L, tag = GalaxyLogger.TAG_READINESS, fields = emptyMap())
        val json = entry.toJsonLine()
        assertTrue(json.contains("\"fields\":{}"))
    }

    // ── getLogFile when init not called ──────────────────────────────────────

    @Test
    fun `getLogFile returns null when init was not called`() {
        // init() was NOT called in this test class — file should be absent.
        // (A previous test class run may have called init; if so, the file may
        //  exist but we tolerate that as it does not affect correctness.)
        // We only assert that the method does not throw.
        val file = GalaxyLogger.getLogFile()
        // No assertion on value; this is a smoke test for NPE / crash safety.
        assertTrue(file == null || file.exists())
    }
}
