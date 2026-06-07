package com.ufo.galaxy.history

import com.ufo.galaxy.runtime.AndroidFlowExecutionPhase
import com.ufo.galaxy.runtime.DelegatedFlowContinuityRecord
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR-2 (Android) — Unit tests for [DelegatedFlowContinuityStore].
 *
 * All tests run on the JVM without any Android framework dependency:
 * [DelegatedFlowContinuityStore] is constructed with `prefs = null` (in-memory mode).
 *
 * ## Test matrix
 *
 * ### Basic save / all
 *  - empty store has size 0
 *  - save increases size
 *  - all returns entries newest-first
 *  - save replaces existing entry with same flowId
 *
 * ### recent
 *  - returns at most limit entries
 *  - returns all when store has fewer than limit
 *
 * ### findByFlowId (recovery read path)
 *  - returns the record when present
 *  - returns null when not present
 *  - returns the most recent record after an update (idempotent save)
 *
 * ### activeCandidates
 *  - returns only non-terminal records
 *  - returns empty list when all records are terminal
 *  - includes RECEIVED, ACTIVATING, ACTIVE_* phases
 *  - excludes COMPLETED, FAILED, REJECTED phases
 *
 * ### findByDurableSessionId
 *  - returns records matching the given durableSessionId
 *  - returns empty list when no match
 *
 * ### Overflow eviction
 *  - oldest entries are evicted when maxEntries exceeded
 *
 * ### TTL eviction
 *  - stale entries are evicted on save
 *
 * ### clear
 *  - clear empties the store
 *
 * ### Store constants
 *  - PREFS_KEY is stable
 *  - DEFAULT_MAX_ENTRIES and DEFAULT_MAX_AGE_MS have expected values
 */
class DelegatedFlowContinuityStoreTest {

    private lateinit var store: DelegatedFlowContinuityStore

    @Before
    fun setUp() {
        store = DelegatedFlowContinuityStore(prefs = null)
    }

    // ── Basic save / all ──────────────────────────────────────────────────────

    @Test
    fun `empty store has size 0`() {
        assertEquals(0, store.size())
    }

    @Test
    fun `save increases size`() {
        store.save(makeRecord("flow-1"))
        assertEquals(1, store.size())
    }

    @Test
    fun `all returns entries newest-first`() {
        store.save(makeRecord("flow-1", savedAtMs = 1_000L))
        store.save(makeRecord("flow-2", savedAtMs = 2_000L))
        store.save(makeRecord("flow-3", savedAtMs = 3_000L))
        val all = store.all()
        assertEquals(3, all.size)
        assertEquals("flow-3", all[0].flowId)
        assertEquals("flow-2", all[1].flowId)
        assertEquals("flow-1", all[2].flowId)
    }

    @Test
    fun `save replaces existing entry with same flowId`() {
        store.save(makeRecord("dup", executionPhase = AndroidFlowExecutionPhase.RECEIVED.wireValue))
        store.save(makeRecord("dup", executionPhase = AndroidFlowExecutionPhase.ACTIVE_LOOP.wireValue))
        assertEquals(1, store.size())
        assertEquals("active_loop", store.all().first().executionPhase)
    }

    // ── recent ────────────────────────────────────────────────────────────────

    @Test
    fun `recent returns at most limit entries`() {
        repeat(5) { i -> store.save(makeRecord("flow-$i")) }
        val recent = store.recent(3)
        assertEquals(3, recent.size)
    }

    @Test
    fun `recent returns all when store has fewer than limit`() {
        store.save(makeRecord("only"))
        val recent = store.recent(10)
        assertEquals(1, recent.size)
    }

    // ── findByFlowId (recovery read path) ────────────────────────────────────

    @Test
    fun `findByFlowId returns record when present`() {
        store.save(makeRecord("target-flow"))
        val found = store.findByFlowId("target-flow")
        assertNotNull(found)
        assertEquals("target-flow", found!!.flowId)
    }

    @Test
    fun `findByFlowId returns null when not present`() {
        store.save(makeRecord("other-flow"))
        assertNull(store.findByFlowId("absent-flow"))
    }

    @Test
    fun `findByFlowId returns updated record after idempotent save`() {
        store.save(makeRecord("update-flow", executionPhase = AndroidFlowExecutionPhase.RECEIVED.wireValue))
        store.save(makeRecord("update-flow", executionPhase = AndroidFlowExecutionPhase.COMPLETED.wireValue))
        val found = store.findByFlowId("update-flow")
        assertNotNull(found)
        assertEquals("completed", found!!.executionPhase)
    }

    // ── activeCandidates ──────────────────────────────────────────────────────

    @Test
    fun `activeCandidates returns only non-terminal records`() {
        store.save(makeRecord("active-1", executionPhase = AndroidFlowExecutionPhase.RECEIVED.wireValue))
        store.save(makeRecord("active-2", executionPhase = AndroidFlowExecutionPhase.ACTIVE_LOOP.wireValue))
        store.save(makeRecord("done-1", executionPhase = AndroidFlowExecutionPhase.COMPLETED.wireValue))
        store.save(makeRecord("done-2", executionPhase = AndroidFlowExecutionPhase.FAILED.wireValue))
        val candidates = store.activeCandidates()
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { !it.isTerminalPhase })
        val ids = candidates.map { it.flowId }.toSet()
        assertTrue(ids.contains("active-1"))
        assertTrue(ids.contains("active-2"))
        assertFalse(ids.contains("done-1"))
        assertFalse(ids.contains("done-2"))
    }

    @Test
    fun `activeCandidates returns empty list when all records are terminal`() {
        store.save(makeRecord("done-1", executionPhase = AndroidFlowExecutionPhase.COMPLETED.wireValue))
        store.save(makeRecord("done-2", executionPhase = AndroidFlowExecutionPhase.REJECTED.wireValue))
        assertTrue(store.activeCandidates().isEmpty())
    }

    @Test
    fun `activeCandidates includes ACTIVATING phase`() {
        store.save(makeRecord("act", executionPhase = AndroidFlowExecutionPhase.ACTIVATING.wireValue))
        assertEquals(1, store.activeCandidates().size)
    }

    @Test
    fun `activeCandidates includes ACTIVE_GOAL_EXECUTION phase`() {
        store.save(makeRecord("goal", executionPhase = AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION.wireValue))
        assertEquals(1, store.activeCandidates().size)
    }

    @Test
    fun `activeCandidates includes ACTIVE_COLLABORATION phase`() {
        store.save(makeRecord("collab", executionPhase = AndroidFlowExecutionPhase.ACTIVE_COLLABORATION.wireValue))
        assertEquals(1, store.activeCandidates().size)
    }

    @Test
    fun `activeCandidates includes ACTIVE_TAKEOVER phase`() {
        store.save(makeRecord("takeover", executionPhase = AndroidFlowExecutionPhase.ACTIVE_TAKEOVER.wireValue))
        assertEquals(1, store.activeCandidates().size)
    }

    // ── findByDurableSessionId ────────────────────────────────────────────────

    @Test
    fun `findByDurableSessionId returns records matching durableSessionId`() {
        store.save(makeRecord("flow-a", durableSessionId = "session-era-1"))
        store.save(makeRecord("flow-b", durableSessionId = "session-era-1"))
        store.save(makeRecord("flow-c", durableSessionId = "session-era-2"))
        val found = store.findByDurableSessionId("session-era-1")
        assertEquals(2, found.size)
        assertTrue(found.all { it.durableSessionId == "session-era-1" })
    }

    @Test
    fun `findByDurableSessionId returns empty list when no match`() {
        store.save(makeRecord("flow-x", durableSessionId = "session-abc"))
        assertTrue(store.findByDurableSessionId("no-such-session").isEmpty())
    }

    // ── Overflow eviction ─────────────────────────────────────────────────────

    @Test
    fun `oldest entries are evicted when maxEntries exceeded`() {
        val smallStore = DelegatedFlowContinuityStore(prefs = null, maxEntries = 3)
        repeat(5) { i ->
            smallStore.save(makeRecord("flow-$i", savedAtMs = i * 1_000L))
        }
        assertEquals(3, smallStore.size())
        val ids = smallStore.all().map { it.flowId }.toSet()
        assertFalse("flow-0 should have been evicted", ids.contains("flow-0"))
        assertFalse("flow-1 should have been evicted", ids.contains("flow-1"))
        assertTrue(ids.contains("flow-4"))
    }

    // ── TTL eviction ──────────────────────────────────────────────────────────

    @Test
    fun `stale entries are evicted on save`() {
        val ttlStore = DelegatedFlowContinuityStore(prefs = null, maxAgeMs = 500L)
        ttlStore.save(makeRecord("stale", savedAtMs = System.currentTimeMillis() - 1_000L))
        ttlStore.save(makeRecord("fresh"))
        val ids = ttlStore.all().map { it.flowId }
        assertFalse("stale entry should have been evicted", ids.contains("stale"))
        assertTrue(ids.contains("fresh"))
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    fun `clear empties the store`() {
        store.save(makeRecord("flow-1"))
        store.save(makeRecord("flow-2"))
        store.clear()
        assertEquals(0, store.size())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `clear allows new saves after clearing`() {
        store.save(makeRecord("before-clear"))
        store.clear()
        store.save(makeRecord("after-clear"))
        assertEquals(1, store.size())
        assertEquals("after-clear", store.all().first().flowId)
    }

    // ── Store constants ───────────────────────────────────────────────────────

    @Test
    fun `DEFAULT_MAX_ENTRIES is 20`() {
        assertEquals(20, DelegatedFlowContinuityStore.DEFAULT_MAX_ENTRIES)
    }

    @Test
    fun `DEFAULT_MAX_AGE_MS is 48 hours`() {
        val expectedMs = 48L * 60L * 60L * 1_000L
        assertEquals(expectedMs, DelegatedFlowContinuityStore.DEFAULT_MAX_AGE_MS)
    }

    // ── Private builder helper ────────────────────────────────────────────────

    private fun makeRecord(
        flowId: String,
        executionPhase: String = AndroidFlowExecutionPhase.RECEIVED.wireValue,
        durableSessionId: String = "durable-default",
        savedAtMs: Long = System.currentTimeMillis()
    ) = DelegatedFlowContinuityRecord(
        flowId = flowId,
        flowLineageId = "lineage-$flowId",
        unitId = "unit-$flowId",
        taskId = "task-$flowId",
        traceId = "trace-$flowId",
        durableSessionId = durableSessionId,
        attachedSessionId = "session-$flowId",
        executionPhase = executionPhase,
        continuityToken = null,
        continuationToken = null,
        activatedAtMs = savedAtMs - 100L,
        executionStartedAtMs = null,
        stepCount = 0,
        lastStepAtMs = null,
        savedAtMs = savedAtMs
    )
}
