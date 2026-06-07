package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.DelegatedRuntimeUnit
import org.junit.Assert.*
import org.junit.Test

/**
 * Recovery-readiness / idempotency metadata tests for [DelegatedExecutionSignal]
 * (PR-15, post-#533 dual-repo runtime unification master plan — Android-Side
 * Delegated-Execution Signal Recovery-Readiness Foundations, Android side).
 *
 * PR-15 added two additive fields to [DelegatedExecutionSignal] to allow the main-repo
 * host to detect duplicate signal deliveries and out-of-order events without Android
 * implementing full offline persistence or resend queueing:
 *
 *  - [DelegatedExecutionSignal.signalId]    — a stable UUID idempotency key unique to
 *                                             each signal emission.
 *  - [DelegatedExecutionSignal.emissionSeq] — a monotonic sequence number reflecting
 *                                             the canonical position in the per-execution
 *                                             lifecycle:
 *                                             [DelegatedExecutionSignal.EMISSION_SEQ_ACK]
 *                                             (1) → [DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS]
 *                                             (2) → [DelegatedExecutionSignal.EMISSION_SEQ_RESULT]
 *                                             (3).
 *
 * ## Test matrix
 *
 * ### [DelegatedExecutionSignal.signalId] field
 *  - ACK factory produces a non-blank signalId.
 *  - PROGRESS factory produces a non-blank signalId.
 *  - RESULT factory produces a non-blank signalId.
 *  - Two distinct ack() calls produce different signalIds.
 *  - Two distinct progress() calls produce different signalIds.
 *  - Two distinct result() calls produce different signalIds.
 *  - Explicit signalId parameter is honoured for ack().
 *  - Explicit signalId parameter is honoured for progress().
 *  - Explicit signalId parameter is honoured for result().
 *  - Explicit signalId parameter is honoured for timeout().
 *  - Explicit signalId parameter is honoured for cancelled().
 *
 * ### [DelegatedExecutionSignal.emissionSeq] field
 *  - ACK emissionSeq equals EMISSION_SEQ_ACK (1).
 *  - PROGRESS emissionSeq equals EMISSION_SEQ_PROGRESS (2).
 *  - RESULT emissionSeq equals EMISSION_SEQ_RESULT (3).
 *  - timeout() emissionSeq equals EMISSION_SEQ_RESULT (3).
 *  - cancelled() emissionSeq equals EMISSION_SEQ_RESULT (3).
 *  - emissionSeq is strictly monotonically increasing: ACK < PROGRESS < RESULT.
 *
 * ### Canonical EMISSION_SEQ_ constants
 *  - EMISSION_SEQ_ACK equals 1.
 *  - EMISSION_SEQ_PROGRESS equals 2.
 *  - EMISSION_SEQ_RESULT equals 3.
 *  - All three constants are distinct.
 *
 * ### Metadata map inclusion
 *  - ACK toMetadataMap contains KEY_SIGNAL_ID.
 *  - ACK toMetadataMap contains KEY_EMISSION_SEQ.
 *  - PROGRESS toMetadataMap contains KEY_SIGNAL_ID.
 *  - PROGRESS toMetadataMap contains KEY_EMISSION_SEQ.
 *  - RESULT toMetadataMap contains KEY_SIGNAL_ID.
 *  - RESULT toMetadataMap contains KEY_EMISSION_SEQ.
 *  - toMetadataMap KEY_SIGNAL_ID value matches signalId field.
 *  - toMetadataMap KEY_EMISSION_SEQ value matches emissionSeq field.
 *  - ACK toMetadataMap KEY_EMISSION_SEQ value is 1.
 *  - PROGRESS toMetadataMap KEY_EMISSION_SEQ value is 2.
 *  - RESULT toMetadataMap KEY_EMISSION_SEQ value is 3.
 *
 * ### KEY_ constant string values (PR-15 additions)
 *  - KEY_SIGNAL_ID is "exec_signal_id".
 *  - KEY_EMISSION_SEQ is "exec_signal_emission_seq".
 */
class DelegatedExecutionSignalIdempotencyTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeUnit(
        unitId: String = "unit-15",
        taskId: String = "task-15",
        traceId: String = "trace-15",
        sessionId: String = "sess-pr15"
    ) = DelegatedRuntimeUnit(
        unitId = unitId,
        taskId = taskId,
        traceId = traceId,
        goal = "navigate to home",
        attachedSessionId = sessionId
    )

    private fun freshTracker(unit: DelegatedRuntimeUnit = makeUnit()): DelegatedExecutionTracker =
        DelegatedExecutionTracker.create(
            record = DelegatedActivationRecord.create(unit = unit, activatedAtMs = 1_000L)
        )

    private fun completedTracker() =
        freshTracker().advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)

    private fun failedTracker() =
        freshTracker().advance(DelegatedActivationRecord.ActivationStatus.FAILED)

    // ── signalId field — non-blank ────────────────────────────────────────────

    @Test
    fun `ACK factory produces non-blank signalId`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), 1_000L)
        assertTrue(signal.signalId.isNotBlank())
    }

    @Test
    fun `PROGRESS factory produces non-blank signalId`() {
        val signal = DelegatedExecutionSignal.progress(freshTracker(), 1_000L)
        assertTrue(signal.signalId.isNotBlank())
    }

    @Test
    fun `RESULT factory produces non-blank signalId`() {
        val signal = DelegatedExecutionSignal.result(
            completedTracker(), DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L
        )
        assertTrue(signal.signalId.isNotBlank())
    }

    // ── signalId field — distinct per call ───────────────────────────────────

    @Test
    fun `two distinct ack calls produce different signalIds`() {
        val tracker = freshTracker()
        val id1 = DelegatedExecutionSignal.ack(tracker, 1_000L).signalId
        val id2 = DelegatedExecutionSignal.ack(tracker, 1_000L).signalId
        assertNotEquals(id1, id2)
    }

    @Test
    fun `two distinct progress calls produce different signalIds`() {
        val tracker = freshTracker()
        val id1 = DelegatedExecutionSignal.progress(tracker, 1_000L).signalId
        val id2 = DelegatedExecutionSignal.progress(tracker, 1_000L).signalId
        assertNotEquals(id1, id2)
    }

    @Test
    fun `two distinct result calls produce different signalIds`() {
        val tracker = completedTracker()
        val id1 = DelegatedExecutionSignal.result(tracker, DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L).signalId
        val id2 = DelegatedExecutionSignal.result(tracker, DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L).signalId
        assertNotEquals(id1, id2)
    }

    // ── signalId field — explicit parameter honoured ──────────────────────────

    @Test
    fun `explicit signalId parameter is honoured for ack`() {
        val id = "fixed-ack-id-001"
        val signal = DelegatedExecutionSignal.ack(freshTracker(), 1_000L, signalId = id)
        assertEquals(id, signal.signalId)
    }

    @Test
    fun `explicit signalId parameter is honoured for progress`() {
        val id = "fixed-progress-id-002"
        val signal = DelegatedExecutionSignal.progress(freshTracker(), 1_000L, signalId = id)
        assertEquals(id, signal.signalId)
    }

    @Test
    fun `explicit signalId parameter is honoured for result`() {
        val id = "fixed-result-id-003"
        val signal = DelegatedExecutionSignal.result(
            completedTracker(), DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L, signalId = id
        )
        assertEquals(id, signal.signalId)
    }

    @Test
    fun `explicit signalId parameter is honoured for timeout`() {
        val id = "fixed-timeout-id-004"
        val signal = DelegatedExecutionSignal.timeout(failedTracker(), 1_000L, signalId = id)
        assertEquals(id, signal.signalId)
    }

    @Test
    fun `explicit signalId parameter is honoured for cancelled`() {
        val id = "fixed-cancelled-id-005"
        val signal = DelegatedExecutionSignal.cancelled(failedTracker(), 1_000L, signalId = id)
        assertEquals(id, signal.signalId)
    }

    // ── emissionSeq field — per kind ─────────────────────────────────────────

    @Test
    fun `ACK emissionSeq equals EMISSION_SEQ_ACK`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), 1_000L)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_ACK, signal.emissionSeq)
    }

    @Test
    fun `PROGRESS emissionSeq equals EMISSION_SEQ_PROGRESS`() {
        val signal = DelegatedExecutionSignal.progress(freshTracker(), 1_000L)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS, signal.emissionSeq)
    }

    @Test
    fun `RESULT emissionSeq equals EMISSION_SEQ_RESULT`() {
        val signal = DelegatedExecutionSignal.result(
            completedTracker(), DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L
        )
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_RESULT, signal.emissionSeq)
    }

    @Test
    fun `timeout emissionSeq equals EMISSION_SEQ_RESULT`() {
        val signal = DelegatedExecutionSignal.timeout(failedTracker(), 1_000L)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_RESULT, signal.emissionSeq)
    }

    @Test
    fun `cancelled emissionSeq equals EMISSION_SEQ_RESULT`() {
        val signal = DelegatedExecutionSignal.cancelled(failedTracker(), 1_000L)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_RESULT, signal.emissionSeq)
    }

    @Test
    fun `emissionSeq is strictly monotonically increasing ACK then PROGRESS then RESULT`() {
        val tracker = freshTracker()
        val ackSeq = DelegatedExecutionSignal.ack(tracker, 1_000L).emissionSeq
        val progressSeq = DelegatedExecutionSignal.progress(tracker, 1_000L).emissionSeq
        val resultSeq = DelegatedExecutionSignal.result(
            completedTracker(), DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L
        ).emissionSeq

        assertTrue("ACK seq < PROGRESS seq", ackSeq < progressSeq)
        assertTrue("PROGRESS seq < RESULT seq", progressSeq < resultSeq)
    }

    // ── Canonical EMISSION_SEQ_ constants ────────────────────────────────────

    @Test
    fun `EMISSION_SEQ_ACK equals 1`() {
        assertEquals(1, DelegatedExecutionSignal.EMISSION_SEQ_ACK)
    }

    @Test
    fun `EMISSION_SEQ_PROGRESS equals 2`() {
        assertEquals(2, DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS)
    }

    @Test
    fun `EMISSION_SEQ_RESULT equals 3`() {
        assertEquals(3, DelegatedExecutionSignal.EMISSION_SEQ_RESULT)
    }

    @Test
    fun `all three EMISSION_SEQ_ constants are distinct`() {
        val seqs = setOf(
            DelegatedExecutionSignal.EMISSION_SEQ_ACK,
            DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS,
            DelegatedExecutionSignal.EMISSION_SEQ_RESULT
        )
        assertEquals(3, seqs.size)
    }

    // ── Metadata map inclusion ────────────────────────────────────────────────

    @Test
    fun `ACK toMetadataMap contains KEY_SIGNAL_ID`() {
        val map = DelegatedExecutionSignal.ack(freshTracker(), 1_000L).toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_SIGNAL_ID))
    }

    @Test
    fun `ACK toMetadataMap contains KEY_EMISSION_SEQ`() {
        val map = DelegatedExecutionSignal.ack(freshTracker(), 1_000L).toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_EMISSION_SEQ))
    }

    @Test
    fun `PROGRESS toMetadataMap contains KEY_SIGNAL_ID`() {
        val map = DelegatedExecutionSignal.progress(freshTracker(), 1_000L).toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_SIGNAL_ID))
    }

    @Test
    fun `PROGRESS toMetadataMap contains KEY_EMISSION_SEQ`() {
        val map = DelegatedExecutionSignal.progress(freshTracker(), 1_000L).toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_EMISSION_SEQ))
    }

    @Test
    fun `RESULT toMetadataMap contains KEY_SIGNAL_ID`() {
        val map = DelegatedExecutionSignal.result(
            completedTracker(), DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L
        ).toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_SIGNAL_ID))
    }

    @Test
    fun `RESULT toMetadataMap contains KEY_EMISSION_SEQ`() {
        val map = DelegatedExecutionSignal.result(
            completedTracker(), DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L
        ).toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_EMISSION_SEQ))
    }

    @Test
    fun `toMetadataMap KEY_SIGNAL_ID value matches signalId field`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), 1_000L, signalId = "known-id-x")
        val map = signal.toMetadataMap()
        assertEquals(signal.signalId, map[DelegatedExecutionSignal.KEY_SIGNAL_ID])
        assertEquals("known-id-x", map[DelegatedExecutionSignal.KEY_SIGNAL_ID])
    }

    @Test
    fun `toMetadataMap KEY_EMISSION_SEQ value matches emissionSeq field`() {
        val signal = DelegatedExecutionSignal.progress(freshTracker(), 1_000L)
        val map = signal.toMetadataMap()
        assertEquals(signal.emissionSeq, map[DelegatedExecutionSignal.KEY_EMISSION_SEQ])
    }

    @Test
    fun `ACK toMetadataMap KEY_EMISSION_SEQ value is 1`() {
        val map = DelegatedExecutionSignal.ack(freshTracker(), 1_000L).toMetadataMap()
        assertEquals(1, map[DelegatedExecutionSignal.KEY_EMISSION_SEQ])
    }

    @Test
    fun `PROGRESS toMetadataMap KEY_EMISSION_SEQ value is 2`() {
        val map = DelegatedExecutionSignal.progress(freshTracker(), 1_000L).toMetadataMap()
        assertEquals(2, map[DelegatedExecutionSignal.KEY_EMISSION_SEQ])
    }

    @Test
    fun `RESULT toMetadataMap KEY_EMISSION_SEQ value is 3`() {
        val map = DelegatedExecutionSignal.result(
            completedTracker(), DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L
        ).toMetadataMap()
        assertEquals(3, map[DelegatedExecutionSignal.KEY_EMISSION_SEQ])
    }

    // ── KEY_ constant string values ───────────────────────────────────────────

    @Test
    fun `KEY_SIGNAL_ID is exec_signal_id`() {
        assertEquals("exec_signal_id", DelegatedExecutionSignal.KEY_SIGNAL_ID)
    }

    @Test
    fun `KEY_EMISSION_SEQ is exec_signal_emission_seq`() {
        assertEquals("exec_signal_emission_seq", DelegatedExecutionSignal.KEY_EMISSION_SEQ)
    }
}
