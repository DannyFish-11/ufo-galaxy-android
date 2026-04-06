package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.DelegatedRuntimeUnit
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [EmittedSignalLedger] (PR-18, post-#533 dual-repo runtime unification
 * master plan — Replay-Safe Delegated Signal Re-Emission Foundations, Android side).
 *
 * [EmittedSignalLedger] is the lightweight in-memory store that retains the most recently
 * emitted [DelegatedExecutionSignal] per [DelegatedExecutionSignal.Kind] so that callers
 * can replay signals with **stable identity** (same [DelegatedExecutionSignal.signalId]
 * and [DelegatedExecutionSignal.emissionSeq]) rather than calling the factory methods
 * again and generating a fresh identity.
 *
 * ## Test matrix
 *
 * ### recordEmitted + lastAck / lastProgress / lastResult
 *  - Ledger is empty (all null) before any signal is recorded.
 *  - recordEmitted(ack) stores the signal and lastAck returns it.
 *  - recordEmitted(progress) stores the signal and lastProgress returns it.
 *  - recordEmitted(result) stores the signal and lastResult returns it.
 *  - Recording a second ACK replaces the first; lastAck returns the most recent.
 *
 * ### getForReplay
 *  - getForReplay(ACK) returns the recorded ACK signal.
 *  - getForReplay(PROGRESS) returns the recorded PROGRESS signal.
 *  - getForReplay(RESULT) returns the recorded RESULT signal.
 *  - getForReplay returns null for a kind that has not been recorded yet.
 *
 * ### replaySignal — identity preservation
 *  - ACK replay preserves original signalId.
 *  - ACK replay preserves original emissionSeq.
 *  - PROGRESS replay preserves original signalId.
 *  - PROGRESS replay preserves original emissionSeq.
 *  - RESULT replay preserves original signalId.
 *  - RESULT replay preserves original emissionSeq.
 *
 * ### replaySignal — timestamp update
 *  - ACK replay updates timestampMs to replayTimestampMs.
 *  - PROGRESS replay updates timestampMs to replayTimestampMs.
 *  - RESULT replay updates timestampMs to replayTimestampMs.
 *
 * ### replaySignal — other fields preserved
 *  - ACK replay preserves kind, unitId, taskId, traceId, attachedSessionId.
 *  - RESULT replay preserves resultKind.
 *
 * ### replaySignal — null guard
 *  - replaySignal returns null when no signal of that kind has been recorded.
 */
class EmittedSignalLedgerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeUnit(
        unitId: String = "unit-18",
        taskId: String = "task-18",
        traceId: String = "trace-18",
        sessionId: String = "sess-pr18"
    ) = DelegatedRuntimeUnit(
        unitId = unitId,
        taskId = taskId,
        traceId = traceId,
        goal = "replay test goal",
        attachedSessionId = sessionId
    )

    private fun freshTracker(unit: DelegatedRuntimeUnit = makeUnit()): DelegatedExecutionTracker =
        DelegatedExecutionTracker.create(
            record = DelegatedActivationRecord.create(unit = unit, activatedAtMs = 1_000L)
        )

    private fun activeTracker() =
        freshTracker().advance(DelegatedActivationRecord.ActivationStatus.ACTIVE)

    private fun completedTracker() =
        freshTracker().advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)

    private fun ackSignal(tracker: DelegatedExecutionTracker = freshTracker()) =
        DelegatedExecutionSignal.ack(tracker, timestampMs = 1_000L)

    private fun progressSignal(tracker: DelegatedExecutionTracker = activeTracker()) =
        DelegatedExecutionSignal.progress(tracker, timestampMs = 2_000L)

    private fun resultSignal(tracker: DelegatedExecutionTracker = completedTracker()) =
        DelegatedExecutionSignal.result(
            tracker, DelegatedExecutionSignal.ResultKind.COMPLETED, timestampMs = 3_000L
        )

    // ── Empty ledger ──────────────────────────────────────────────────────────

    @Test
    fun `ledger is empty before any signal is recorded`() {
        val ledger = EmittedSignalLedger()
        assertNull(ledger.lastAck)
        assertNull(ledger.lastProgress)
        assertNull(ledger.lastResult)
    }

    // ── recordEmitted + last* ─────────────────────────────────────────────────

    @Test
    fun `recordEmitted ack stores signal and lastAck returns it`() {
        val ledger = EmittedSignalLedger()
        val signal = ackSignal()
        ledger.recordEmitted(signal)
        assertSame(signal, ledger.lastAck)
    }

    @Test
    fun `recordEmitted progress stores signal and lastProgress returns it`() {
        val ledger = EmittedSignalLedger()
        val signal = progressSignal()
        ledger.recordEmitted(signal)
        assertSame(signal, ledger.lastProgress)
    }

    @Test
    fun `recordEmitted result stores signal and lastResult returns it`() {
        val ledger = EmittedSignalLedger()
        val signal = resultSignal()
        ledger.recordEmitted(signal)
        assertSame(signal, ledger.lastResult)
    }

    @Test
    fun `recording a second ACK replaces the first`() {
        val ledger = EmittedSignalLedger()
        val first = ackSignal()
        val second = ackSignal()
        ledger.recordEmitted(first)
        ledger.recordEmitted(second)
        assertSame(second, ledger.lastAck)
        assertNotSame(first, ledger.lastAck)
    }

    // ── getForReplay ──────────────────────────────────────────────────────────

    @Test
    fun `getForReplay ACK returns the recorded ACK signal`() {
        val ledger = EmittedSignalLedger()
        val signal = ackSignal()
        ledger.recordEmitted(signal)
        assertSame(signal, ledger.getForReplay(DelegatedExecutionSignal.Kind.ACK))
    }

    @Test
    fun `getForReplay PROGRESS returns the recorded PROGRESS signal`() {
        val ledger = EmittedSignalLedger()
        val signal = progressSignal()
        ledger.recordEmitted(signal)
        assertSame(signal, ledger.getForReplay(DelegatedExecutionSignal.Kind.PROGRESS))
    }

    @Test
    fun `getForReplay RESULT returns the recorded RESULT signal`() {
        val ledger = EmittedSignalLedger()
        val signal = resultSignal()
        ledger.recordEmitted(signal)
        assertSame(signal, ledger.getForReplay(DelegatedExecutionSignal.Kind.RESULT))
    }

    @Test
    fun `getForReplay returns null for kind not yet recorded`() {
        val ledger = EmittedSignalLedger()
        ledger.recordEmitted(ackSignal())
        assertNull(ledger.getForReplay(DelegatedExecutionSignal.Kind.PROGRESS))
        assertNull(ledger.getForReplay(DelegatedExecutionSignal.Kind.RESULT))
    }

    // ── replaySignal — identity preservation ──────────────────────────────────

    @Test
    fun `ACK replay preserves original signalId`() {
        val ledger = EmittedSignalLedger()
        val original = ackSignal()
        ledger.recordEmitted(original)
        val replay = ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, replayTimestampMs = 9_000L)
        assertEquals(original.signalId, replay?.signalId)
    }

    @Test
    fun `ACK replay preserves original emissionSeq`() {
        val ledger = EmittedSignalLedger()
        val original = ackSignal()
        ledger.recordEmitted(original)
        val replay = ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, replayTimestampMs = 9_000L)
        assertEquals(original.emissionSeq, replay?.emissionSeq)
    }

    @Test
    fun `PROGRESS replay preserves original signalId`() {
        val ledger = EmittedSignalLedger()
        val original = progressSignal()
        ledger.recordEmitted(original)
        val replay = ledger.replaySignal(DelegatedExecutionSignal.Kind.PROGRESS, replayTimestampMs = 9_000L)
        assertEquals(original.signalId, replay?.signalId)
    }

    @Test
    fun `PROGRESS replay preserves original emissionSeq`() {
        val ledger = EmittedSignalLedger()
        val original = progressSignal()
        ledger.recordEmitted(original)
        val replay = ledger.replaySignal(DelegatedExecutionSignal.Kind.PROGRESS, replayTimestampMs = 9_000L)
        assertEquals(original.emissionSeq, replay?.emissionSeq)
    }

    @Test
    fun `RESULT replay preserves original signalId`() {
        val ledger = EmittedSignalLedger()
        val original = resultSignal()
        ledger.recordEmitted(original)
        val replay = ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)
        assertEquals(original.signalId, replay?.signalId)
    }

    @Test
    fun `RESULT replay preserves original emissionSeq`() {
        val ledger = EmittedSignalLedger()
        val original = resultSignal()
        ledger.recordEmitted(original)
        val replay = ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)
        assertEquals(original.emissionSeq, replay?.emissionSeq)
    }

    // ── replaySignal — timestamp update ───────────────────────────────────────

    @Test
    fun `ACK replay updates timestampMs to replayTimestampMs`() {
        val ledger = EmittedSignalLedger()
        ledger.recordEmitted(ackSignal())
        val replay = ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, replayTimestampMs = 9_000L)
        assertEquals(9_000L, replay?.timestampMs)
    }

    @Test
    fun `PROGRESS replay updates timestampMs to replayTimestampMs`() {
        val ledger = EmittedSignalLedger()
        ledger.recordEmitted(progressSignal())
        val replay = ledger.replaySignal(DelegatedExecutionSignal.Kind.PROGRESS, replayTimestampMs = 9_000L)
        assertEquals(9_000L, replay?.timestampMs)
    }

    @Test
    fun `RESULT replay updates timestampMs to replayTimestampMs`() {
        val ledger = EmittedSignalLedger()
        ledger.recordEmitted(resultSignal())
        val replay = ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)
        assertEquals(9_000L, replay?.timestampMs)
    }

    // ── replaySignal — other fields preserved ─────────────────────────────────

    @Test
    fun `ACK replay preserves kind unitId taskId traceId attachedSessionId`() {
        val ledger = EmittedSignalLedger()
        val original = ackSignal()
        ledger.recordEmitted(original)
        val replay = ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, replayTimestampMs = 9_000L)
        assertNotNull(replay)
        assertEquals(original.kind, replay!!.kind)
        assertEquals(original.unitId, replay.unitId)
        assertEquals(original.taskId, replay.taskId)
        assertEquals(original.traceId, replay.traceId)
        assertEquals(original.attachedSessionId, replay.attachedSessionId)
    }

    @Test
    fun `RESULT replay preserves resultKind`() {
        val ledger = EmittedSignalLedger()
        val original = resultSignal()
        ledger.recordEmitted(original)
        val replay = ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)
        assertEquals(original.resultKind, replay?.resultKind)
    }

    // ── replaySignal — null guard ─────────────────────────────────────────────

    @Test
    fun `replaySignal returns null when no signal of that kind has been recorded`() {
        val ledger = EmittedSignalLedger()
        assertNull(ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, replayTimestampMs = 9_000L))
        assertNull(ledger.replaySignal(DelegatedExecutionSignal.Kind.PROGRESS, replayTimestampMs = 9_000L))
        assertNull(ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L))
    }
}
