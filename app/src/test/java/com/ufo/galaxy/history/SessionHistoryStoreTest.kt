package com.ufo.galaxy.history

import com.ufo.galaxy.trace.TerminalResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SessionHistoryStore].
 *
 * All tests run on the JVM without any Android framework dependency:
 * [SessionHistoryStore] is constructed with `prefs = null` (in-memory mode).
 */
class SessionHistoryStoreTest {

    private lateinit var store: SessionHistoryStore

    @Before
    fun setUp() {
        store = SessionHistoryStore(prefs = null)
    }

    // ── Basic save / all ──────────────────────────────────────────────────────

    @Test
    fun `empty store has size 0`() {
        assertEquals(0, store.size())
    }

    @Test
    fun `save increases size`() {
        store.save(makeSummary("s1"))
        assertEquals(1, store.size())
    }

    @Test
    fun `all returns entries newest-first`() {
        store.save(makeSummary("s1", savedAt = 1000L))
        store.save(makeSummary("s2", savedAt = 2000L))
        store.save(makeSummary("s3", savedAt = 3000L))
        val all = store.all()
        assertEquals(3, all.size)
        assertEquals("s3", all[0].sessionId)
        assertEquals("s2", all[1].sessionId)
        assertEquals("s1", all[2].sessionId)
    }

    @Test
    fun `save replaces existing entry with same sessionId`() {
        store.save(makeSummary("dup", status = TerminalResult.STATUS_SUCCESS))
        store.save(makeSummary("dup", status = TerminalResult.STATUS_FAILED))
        assertEquals(1, store.size())
        assertEquals(TerminalResult.STATUS_FAILED, store.all().first().status)
    }

    // ── recent ────────────────────────────────────────────────────────────────

    @Test
    fun `recent returns at most limit entries`() {
        repeat(5) { i -> store.save(makeSummary("s$i")) }
        val recent = store.recent(3)
        assertEquals(3, recent.size)
    }

    @Test
    fun `recent returns all when store has fewer than limit`() {
        store.save(makeSummary("only"))
        val recent = store.recent(10)
        assertEquals(1, recent.size)
    }

    // ── byStatus ──────────────────────────────────────────────────────────────

    @Test
    fun `byStatus filters correctly`() {
        store.save(makeSummary("s1", status = TerminalResult.STATUS_SUCCESS))
        store.save(makeSummary("s2", status = TerminalResult.STATUS_FAILED))
        store.save(makeSummary("s3", status = TerminalResult.STATUS_SUCCESS))
        val successes = store.byStatus(TerminalResult.STATUS_SUCCESS)
        assertEquals(2, successes.size)
        assertTrue(successes.all { it.status == TerminalResult.STATUS_SUCCESS })
    }

    @Test
    fun `byStatus returns empty list when no matches`() {
        store.save(makeSummary("s1", status = TerminalResult.STATUS_SUCCESS))
        val cancelled = store.byStatus(TerminalResult.STATUS_CANCELLED)
        assertTrue(cancelled.isEmpty())
    }

    // ── overflow eviction ─────────────────────────────────────────────────────

    @Test
    fun `oldest entries are evicted when maxEntries exceeded`() {
        val smallStore = SessionHistoryStore(prefs = null, maxEntries = 3)
        repeat(5) { i -> smallStore.save(makeSummary("s$i", savedAt = i * 1000L)) }
        assertEquals(3, smallStore.size())
        // Newest three should be retained
        val ids = smallStore.all().map { it.sessionId }.toSet()
        assertFalse("s0 should have been evicted", ids.contains("s0"))
        assertFalse("s1 should have been evicted", ids.contains("s1"))
        assertTrue(ids.contains("s4"))
    }

    // ── TTL eviction ──────────────────────────────────────────────────────────

    @Test
    fun `stale entries are evicted on save`() {
        val ttlStore = SessionHistoryStore(prefs = null, maxAgeMs = 500L)
        // Add an entry with savedAtMs well in the past
        ttlStore.save(makeSummary("stale", savedAt = System.currentTimeMillis() - 1000L))
        // Trigger eviction by saving a fresh entry
        ttlStore.save(makeSummary("fresh"))
        val ids = ttlStore.all().map { it.sessionId }
        assertFalse("stale entry should have been evicted", ids.contains("stale"))
        assertTrue(ids.contains("fresh"))
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    fun `clear empties the store`() {
        store.save(makeSummary("s1"))
        store.save(makeSummary("s2"))
        store.clear()
        assertEquals(0, store.size())
        assertTrue(store.all().isEmpty())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeSummary(
        sessionId: String,
        status: String = TerminalResult.STATUS_SUCCESS,
        savedAt: Long = System.currentTimeMillis()
    ) = SessionHistorySummary(
        sessionId = sessionId,
        originalGoal = "goal for $sessionId",
        startTimeMs = savedAt - 500L,
        endTimeMs = savedAt,
        durationMs = 500L,
        stepCount = 2,
        status = status,
        stopReason = null,
        error = null,
        planCount = 1,
        actionCount = 2,
        savedAtMs = savedAt
    )
}
