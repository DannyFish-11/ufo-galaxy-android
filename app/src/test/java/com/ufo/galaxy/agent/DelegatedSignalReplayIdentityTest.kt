package com.ufo.galaxy.agent

import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.runtime.DelegatedActivationRecord
import com.ufo.galaxy.runtime.DelegatedExecutionSignal
import com.ufo.galaxy.runtime.DelegatedExecutionSignalSink
import com.ufo.galaxy.runtime.DelegatedExecutionTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test

/**
 * Replay identity continuity tests for Android delegated execution signals (PR-18,
 * post-#533 dual-repo runtime unification master plan — Replay-Safe Delegated Signal
 * Re-Emission Foundations, Android side).
 *
 * This test class verifies that the [com.ufo.galaxy.runtime.EmittedSignalLedger] exposed
 * through [DelegatedTakeoverExecutor.ExecutionOutcome.ledger] allows callers to re-emit
 * ACK, PROGRESS, and RESULT signals with **stable identity** — preserving the original
 * [DelegatedExecutionSignal.signalId] and [DelegatedExecutionSignal.emissionSeq] — so the
 * host can detect re-deliveries as duplicates rather than new emissions.
 *
 * ## Why stable replay identity matters
 *
 * The host uses [DelegatedExecutionSignal.signalId] as an idempotency key and
 * [DelegatedExecutionSignal.emissionSeq] as an ordering guard.  If a replay called the
 * factory methods again (e.g. [DelegatedExecutionSignal.ack]) it would generate a fresh
 * [signalId], causing the host to treat the re-emission as a completely new signal,
 * potentially duplicating side-effects and corrupting ordering state.  Stable replay
 * identity prevents this class of bug.
 *
 * ## Test matrix
 *
 * ### Ledger availability in ExecutionOutcome
 *  - ExecutionOutcome.Completed carries a non-null ledger.
 *  - ExecutionOutcome.Failed carries a non-null ledger.
 *  - Ledger from Completed outcome contains the ACK signal.
 *  - Ledger from Completed outcome contains the PROGRESS signal.
 *  - Ledger from Completed outcome contains the RESULT signal.
 *  - Ledger from Failed outcome contains the ACK signal.
 *  - Ledger from Failed outcome contains the PROGRESS signal.
 *  - Ledger from Failed outcome contains the RESULT signal.
 *
 * ### ACK replay identity continuity
 *  - ACK replay (success path) preserves original signalId.
 *  - ACK replay (success path) preserves original emissionSeq (EMISSION_SEQ_ACK = 1).
 *  - ACK replay (failure path) preserves original signalId.
 *  - ACK replay (failure path) preserves original emissionSeq.
 *
 * ### PROGRESS replay identity continuity
 *  - PROGRESS replay (success path) preserves original signalId.
 *  - PROGRESS replay (success path) preserves original emissionSeq (EMISSION_SEQ_PROGRESS = 2).
 *  - PROGRESS replay (failure path) preserves original signalId.
 *  - PROGRESS replay (failure path) preserves original emissionSeq.
 *
 * ### RESULT replay identity continuity — success
 *  - RESULT replay (COMPLETED) preserves original signalId.
 *  - RESULT replay (COMPLETED) preserves original emissionSeq (EMISSION_SEQ_RESULT = 3).
 *  - RESULT replay (COMPLETED) preserves resultKind COMPLETED.
 *
 * ### RESULT replay identity continuity — failure variants
 *  - RESULT replay (TIMEOUT) preserves original signalId.
 *  - RESULT replay (TIMEOUT) preserves original emissionSeq.
 *  - RESULT replay (CANCELLED) preserves original signalId.
 *  - RESULT replay (CANCELLED) preserves original emissionSeq.
 *  - RESULT replay (FAILED) preserves original signalId.
 *  - RESULT replay (FAILED) preserves original emissionSeq.
 *
 * ### Replay timestamp update
 *  - replaySignal updates timestampMs to the supplied replayTimestampMs.
 *  - Original signal's timestampMs is unchanged after replay.
 *
 * ### Replay vs fresh factory — signalId must differ
 *  - A fresh ack() call produces a different signalId than the replayed ACK.
 *  - A fresh progress() call produces a different signalId than the replayed PROGRESS.
 *  - A fresh result() call produces a different signalId than the replayed RESULT.
 *
 * ### Signal identity between emitted signal and ledger entry
 *  - Ledger ACK entry has same signalId as the signal captured by the sink.
 *  - Ledger PROGRESS entry has same signalId as the signal captured by the sink.
 *  - Ledger RESULT entry has same signalId as the signal captured by the sink.
 */
class DelegatedSignalReplayIdentityTest {

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
        goal = "replay identity test goal",
        attachedSessionId = sessionId
    )

    private fun pendingRecord(unit: DelegatedRuntimeUnit = makeUnit()) =
        DelegatedActivationRecord.create(unit = unit, activatedAtMs = 1_000L)

    private fun captureSignals(): Pair<MutableList<DelegatedExecutionSignal>, DelegatedExecutionSignalSink> {
        val captured = mutableListOf<DelegatedExecutionSignal>()
        val sink = DelegatedExecutionSignalSink { signal -> captured += signal }
        return Pair(captured, sink)
    }

    private fun successPipeline(): GoalExecutionPipeline = GoalExecutionPipeline { payload ->
        GoalResultPayload(
            task_id = payload.task_id,
            correlation_id = payload.task_id,
            status = "success"
        )
    }

    private fun failingPipeline(message: String = "simulated failure"): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw RuntimeException(message) }

    private fun timeoutPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw TimeoutCancellationException("timeout") }

    private fun cancellationPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw CancellationException("cancelled") }

    private fun buildExecutor(
        pipeline: GoalExecutionPipeline = successPipeline(),
        sink: DelegatedExecutionSignalSink = DelegatedExecutionSignalSink { }
    ) = DelegatedTakeoverExecutor(pipeline = pipeline, signalSink = sink)

    // ── Ledger availability in ExecutionOutcome ───────────────────────────────

    @Test
    fun `ExecutionOutcome Completed carries a non-null ledger`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertNotNull(outcome.ledger)
    }

    @Test
    fun `ExecutionOutcome Failed carries a non-null ledger`() {
        val outcome = buildExecutor(pipeline = failingPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertNotNull(outcome.ledger)
    }

    @Test
    fun `ledger from Completed outcome contains ACK signal`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertNotNull(outcome.ledger.lastAck)
    }

    @Test
    fun `ledger from Completed outcome contains PROGRESS signal`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertNotNull(outcome.ledger.lastProgress)
    }

    @Test
    fun `ledger from Completed outcome contains RESULT signal`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertNotNull(outcome.ledger.lastResult)
    }

    @Test
    fun `ledger from Failed outcome contains ACK signal`() {
        val outcome = buildExecutor(pipeline = failingPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertNotNull(outcome.ledger.lastAck)
    }

    @Test
    fun `ledger from Failed outcome contains PROGRESS signal`() {
        val outcome = buildExecutor(pipeline = failingPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertNotNull(outcome.ledger.lastProgress)
    }

    @Test
    fun `ledger from Failed outcome contains RESULT signal`() {
        val outcome = buildExecutor(pipeline = failingPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertNotNull(outcome.ledger.lastResult)
    }

    // ── ACK replay identity continuity ────────────────────────────────────────

    @Test
    fun `ACK replay on success path preserves original signalId`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val original = outcome.ledger.lastAck!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, replayTimestampMs = 9_000L)!!
        assertEquals(original.signalId, replay.signalId)
    }

    @Test
    fun `ACK replay on success path preserves original emissionSeq`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val original = outcome.ledger.lastAck!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, replayTimestampMs = 9_000L)!!
        assertEquals(original.emissionSeq, replay.emissionSeq)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_ACK, replay.emissionSeq)
    }

    @Test
    fun `ACK replay on failure path preserves original signalId`() {
        val outcome = buildExecutor(pipeline = failingPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        val original = outcome.ledger.lastAck!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, replayTimestampMs = 9_000L)!!
        assertEquals(original.signalId, replay.signalId)
    }

    @Test
    fun `ACK replay on failure path preserves original emissionSeq`() {
        val outcome = buildExecutor(pipeline = failingPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        val original = outcome.ledger.lastAck!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, replayTimestampMs = 9_000L)!!
        assertEquals(original.emissionSeq, replay.emissionSeq)
    }

    // ── PROGRESS replay identity continuity ───────────────────────────────────

    @Test
    fun `PROGRESS replay on success path preserves original signalId`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val original = outcome.ledger.lastProgress!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.PROGRESS, replayTimestampMs = 9_000L)!!
        assertEquals(original.signalId, replay.signalId)
    }

    @Test
    fun `PROGRESS replay on success path preserves original emissionSeq`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val original = outcome.ledger.lastProgress!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.PROGRESS, replayTimestampMs = 9_000L)!!
        assertEquals(original.emissionSeq, replay.emissionSeq)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS, replay.emissionSeq)
    }

    @Test
    fun `PROGRESS replay on failure path preserves original signalId`() {
        val outcome = buildExecutor(pipeline = failingPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        val original = outcome.ledger.lastProgress!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.PROGRESS, replayTimestampMs = 9_000L)!!
        assertEquals(original.signalId, replay.signalId)
    }

    @Test
    fun `PROGRESS replay on failure path preserves original emissionSeq`() {
        val outcome = buildExecutor(pipeline = failingPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        val original = outcome.ledger.lastProgress!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.PROGRESS, replayTimestampMs = 9_000L)!!
        assertEquals(original.emissionSeq, replay.emissionSeq)
    }

    // ── RESULT replay identity continuity — COMPLETED ─────────────────────────

    @Test
    fun `RESULT replay COMPLETED preserves original signalId`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val original = outcome.ledger.lastResult!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)!!
        assertEquals(original.signalId, replay.signalId)
    }

    @Test
    fun `RESULT replay COMPLETED preserves original emissionSeq`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val original = outcome.ledger.lastResult!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)!!
        assertEquals(original.emissionSeq, replay.emissionSeq)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_RESULT, replay.emissionSeq)
    }

    @Test
    fun `RESULT replay COMPLETED preserves resultKind COMPLETED`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)!!
        assertEquals(DelegatedExecutionSignal.ResultKind.COMPLETED, replay.resultKind)
    }

    // ── RESULT replay identity continuity — TIMEOUT ───────────────────────────

    @Test
    fun `RESULT replay TIMEOUT preserves original signalId`() {
        val outcome = buildExecutor(pipeline = timeoutPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        val original = outcome.ledger.lastResult!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)!!
        assertEquals(original.signalId, replay.signalId)
    }

    @Test
    fun `RESULT replay TIMEOUT preserves original emissionSeq`() {
        val outcome = buildExecutor(pipeline = timeoutPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        val original = outcome.ledger.lastResult!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)!!
        assertEquals(original.emissionSeq, replay.emissionSeq)
    }

    // ── RESULT replay identity continuity — CANCELLED ─────────────────────────

    @Test
    fun `RESULT replay CANCELLED preserves original signalId`() {
        val outcome = buildExecutor(pipeline = cancellationPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        val original = outcome.ledger.lastResult!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)!!
        assertEquals(original.signalId, replay.signalId)
    }

    @Test
    fun `RESULT replay CANCELLED preserves original emissionSeq`() {
        val outcome = buildExecutor(pipeline = cancellationPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        val original = outcome.ledger.lastResult!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)!!
        assertEquals(original.emissionSeq, replay.emissionSeq)
    }

    // ── RESULT replay identity continuity — FAILED ────────────────────────────

    @Test
    fun `RESULT replay FAILED preserves original signalId`() {
        val outcome = buildExecutor(pipeline = failingPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        val original = outcome.ledger.lastResult!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)!!
        assertEquals(original.signalId, replay.signalId)
    }

    @Test
    fun `RESULT replay FAILED preserves original emissionSeq`() {
        val outcome = buildExecutor(pipeline = failingPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        val original = outcome.ledger.lastResult!!
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)!!
        assertEquals(original.emissionSeq, replay.emissionSeq)
    }

    // ── Replay timestamp update ───────────────────────────────────────────────

    @Test
    fun `replaySignal updates timestampMs to supplied replayTimestampMs`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, replayTimestampMs = 9_000L)!!
        assertEquals(9_000L, replay.timestampMs)
    }

    @Test
    fun `original signal timestampMs is unchanged after replay`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val original = outcome.ledger.lastAck!!
        outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, replayTimestampMs = 9_000L)
        assertEquals(1_000L, original.timestampMs)
    }

    // ── Replay vs fresh factory — signalId must differ ────────────────────────

    @Test
    fun `fresh ack factory call produces different signalId than replayed ACK`() {
        val (captured, sink) = captureSignals()
        val outcome = buildExecutor(pipeline = successPipeline(), sink = sink)
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val replayId = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, replayTimestampMs = 9_000L)!!.signalId
        val freshId = DelegatedExecutionSignal.ack(
            DelegatedExecutionTracker.create(pendingRecord()),
            timestampMs = 9_000L
        ).signalId
        assertNotEquals(
            "Fresh ack() call must generate a new signalId, not the same as the replay",
            replayId,
            freshId
        )
    }

    @Test
    fun `fresh progress factory call produces different signalId than replayed PROGRESS`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val replayId = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.PROGRESS, replayTimestampMs = 9_000L)!!.signalId
        val freshRecord = pendingRecord()
        val freshTracker = DelegatedExecutionTracker.create(freshRecord)
            .advance(DelegatedActivationRecord.ActivationStatus.ACTIVE)
        val freshId = DelegatedExecutionSignal.progress(freshTracker, timestampMs = 9_000L).signalId
        assertNotEquals(replayId, freshId)
    }

    @Test
    fun `fresh result factory call produces different signalId than replayed RESULT`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val replayId = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, replayTimestampMs = 9_000L)!!.signalId
        val freshRecord = pendingRecord()
        val freshTracker = DelegatedExecutionTracker.create(freshRecord)
            .advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        val freshId = DelegatedExecutionSignal.result(
            freshTracker, DelegatedExecutionSignal.ResultKind.COMPLETED, timestampMs = 9_000L
        ).signalId
        assertNotEquals(replayId, freshId)
    }

    // ── Signal identity between sink and ledger ───────────────────────────────

    @Test
    fun `ledger ACK signalId matches the signal captured by the sink`() {
        val (captured, sink) = captureSignals()
        val outcome = buildExecutor(pipeline = successPipeline(), sink = sink)
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val sinkAckId = captured.first { it.isAck }.signalId
        assertEquals(sinkAckId, outcome.ledger.lastAck?.signalId)
    }

    @Test
    fun `ledger PROGRESS signalId matches the signal captured by the sink`() {
        val (captured, sink) = captureSignals()
        val outcome = buildExecutor(pipeline = successPipeline(), sink = sink)
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val sinkProgressId = captured.first { it.isProgress }.signalId
        assertEquals(sinkProgressId, outcome.ledger.lastProgress?.signalId)
    }

    @Test
    fun `ledger RESULT signalId matches the signal captured by the sink`() {
        val (captured, sink) = captureSignals()
        val outcome = buildExecutor(pipeline = successPipeline(), sink = sink)
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val sinkResultId = captured.first { it.isResult }.signalId
        assertEquals(sinkResultId, outcome.ledger.lastResult?.signalId)
    }
}
