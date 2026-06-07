package com.ufo.galaxy.trace

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LocalLoopTraceStore].
 *
 * Covers:
 * - [LocalLoopTraceStore.beginTrace] adds a trace and evicts oldest when full.
 * - [LocalLoopTraceStore.getTrace] returns the correct trace by sessionId.
 * - [LocalLoopTraceStore.allTraces] returns newest-first ordering.
 * - [LocalLoopTraceStore.completedTraces] / [runningTraces] filtering.
 * - [LocalLoopTraceStore.size] tracks count correctly.
 * - [LocalLoopTraceStore.clear] empties the store.
 * - Eviction at capacity drops the oldest trace.
 */
class LocalLoopTraceStoreTest {

    private lateinit var store: LocalLoopTraceStore

    @Before
    fun setUp() {
        store = LocalLoopTraceStore(maxTraces = 5)
    }

    private fun makeTrace(id: String, goal: String = "goal $id"): LocalLoopTrace =
        LocalLoopTrace(sessionId = id, originalGoal = goal)

    // ── beginTrace ────────────────────────────────────────────────────────────

    @Test
    fun `beginTrace increases size`() {
        store.beginTrace(makeTrace("s1"))
        assertEquals(1, store.size())
    }

    @Test
    fun `beginTrace stores trace retrievable by sessionId`() {
        val trace = makeTrace("s2")
        store.beginTrace(trace)
        assertNotNull(store.getTrace("s2"))
        assertEquals("goal s2", store.getTrace("s2")!!.originalGoal)
    }

    @Test
    fun `beginTrace replaces existing trace with same sessionId`() {
        store.beginTrace(makeTrace("s3", "first"))
        store.beginTrace(makeTrace("s3", "second"))
        assertEquals("second", store.getTrace("s3")!!.originalGoal)
        assertEquals(1, store.size())
    }

    // ── Capacity and eviction ─────────────────────────────────────────────────

    @Test
    fun `size does not exceed maxTraces`() {
        repeat(10) { store.beginTrace(makeTrace("session-$it")) }
        assertTrue("size must not exceed maxTraces", store.size() <= 5)
    }

    @Test
    fun `oldest trace is evicted when capacity is reached`() {
        // Fill to capacity
        (1..5).forEach { store.beginTrace(makeTrace("s$it")) }
        // s1 is oldest — adding s6 should evict s1
        store.beginTrace(makeTrace("s6"))
        assertNull("s1 should have been evicted", store.getTrace("s1"))
        assertNotNull("s6 should be present", store.getTrace("s6"))
    }

    @Test
    fun `newest traces survive eviction`() {
        (1..6).forEach { store.beginTrace(makeTrace("s$it")) }
        // s2..s6 should still be present
        (2..6).forEach { i ->
            assertNotNull("s$i should survive", store.getTrace("s$i"))
        }
    }

    // ── getTrace ──────────────────────────────────────────────────────────────

    @Test
    fun `getTrace returns null for unknown sessionId`() {
        assertNull(store.getTrace("not-there"))
    }

    // ── allTraces ─────────────────────────────────────────────────────────────

    @Test
    fun `allTraces returns all stored traces`() {
        store.beginTrace(makeTrace("a"))
        store.beginTrace(makeTrace("b"))
        assertEquals(2, store.allTraces().size)
    }

    @Test
    fun `allTraces is newest-first`() {
        store.beginTrace(makeTrace("first"))
        store.beginTrace(makeTrace("second"))
        val all = store.allTraces()
        assertEquals("second", all[0].sessionId)
        assertEquals("first", all[1].sessionId)
    }

    // ── completedTraces / runningTraces ───────────────────────────────────────

    @Test
    fun `completedTraces returns only finished traces`() {
        val running = makeTrace("running")
        val done = makeTrace("done")
        done.complete(TerminalResult(
            status = TerminalResult.STATUS_SUCCESS,
            stopReason = "task_complete",
            error = null,
            totalSteps = 1
        ))
        store.beginTrace(running)
        store.beginTrace(done)

        val completed = store.completedTraces()
        assertEquals(1, completed.size)
        assertEquals("done", completed[0].sessionId)
    }

    @Test
    fun `runningTraces returns only in-flight traces`() {
        val running = makeTrace("run1")
        val done = makeTrace("done1")
        done.complete(TerminalResult(
            status = TerminalResult.STATUS_FAILED,
            stopReason = "max_steps_reached",
            error = "budget",
            totalSteps = 10
        ))
        store.beginTrace(running)
        store.beginTrace(done)

        val running2 = store.runningTraces()
        assertEquals(1, running2.size)
        assertEquals("run1", running2[0].sessionId)
    }

    // ── completeTrace ─────────────────────────────────────────────────────────

    @Test
    fun `completeTrace on unknown sessionId does not throw`() {
        // Should be a no-op, no exception
        store.completeTrace("nonexistent")
    }

    @Test
    fun `completeTrace on known trace does not throw`() {
        val trace = makeTrace("ct1")
        trace.complete(TerminalResult(
            status = TerminalResult.STATUS_SUCCESS,
            stopReason = "task_complete",
            error = null,
            totalSteps = 2
        ))
        store.beginTrace(trace)
        store.completeTrace("ct1") // should be a no-op (trace already complete)
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    fun `clear empties the store`() {
        store.beginTrace(makeTrace("x1"))
        store.beginTrace(makeTrace("x2"))
        store.clear()
        assertEquals(0, store.size())
    }

    @Test
    fun `getTrace returns null after clear`() {
        store.beginTrace(makeTrace("y1"))
        store.clear()
        assertNull(store.getTrace("y1"))
    }
}
