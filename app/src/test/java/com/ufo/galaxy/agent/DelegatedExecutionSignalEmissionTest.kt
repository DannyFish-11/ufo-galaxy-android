package com.ufo.galaxy.agent

import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.runtime.DelegatedActivationRecord
import com.ufo.galaxy.runtime.DelegatedExecutionSignal
import com.ufo.galaxy.runtime.DelegatedExecutionSignalSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test

/**
 * Canonical signal emission path tests (PR-13, post-#533 dual-repo runtime unification
 * master plan — Canonical Android-Side Delegated Execution Signal Emission Path, Android
 * side).
 *
 * This test class exercises the complete ACK → PROGRESS → RESULT lifecycle through
 * [DelegatedTakeoverExecutor], verifying:
 *
 *  1. Signal sequence: ACK is always first, PROGRESS second, RESULT last.
 *  2. Signal count: exactly three signals are emitted on every execution path.
 *  3. ResultKind alignment: COMPLETED / FAILED / TIMEOUT / CANCELLED are emitted for the
 *     correct exception types.
 *  4. Identity metadata continuity: all three signals on a given execution carry the same
 *     unitId, taskId, traceId, attachedSessionId, and handoffContractVersion.
 *  5. Activation status hints: ACK carries "pending", PROGRESS carries "active", and
 *     each RESULT carries the expected terminal status ("completed" or "failed").
 *
 * ## Test matrix
 *
 * ### Signal sequence and count
 *  - Success path emits exactly ACK, PROGRESS, RESULT(COMPLETED) in that order.
 *  - Failure path emits exactly ACK, PROGRESS, RESULT(FAILED) in that order.
 *  - Timeout path emits exactly ACK, PROGRESS, RESULT(TIMEOUT) in that order.
 *  - Cancellation path emits exactly ACK, PROGRESS, RESULT(CANCELLED) in that order.
 *
 * ### Identity metadata continuity
 *  - All signals on success path carry the same unitId.
 *  - All signals on success path carry the same taskId.
 *  - All signals on success path carry the same traceId.
 *  - All signals on success path carry the same attachedSessionId.
 *  - All signals on success path carry the same handoffContractVersion.
 *  - All signals on failure path carry consistent identity metadata.
 *  - All signals on timeout path carry consistent identity metadata.
 *  - All signals on cancellation path carry consistent identity metadata.
 *
 * ### Activation status hints
 *  - ACK signal carries "pending" activation status hint.
 *  - PROGRESS signal carries "active" activation status hint.
 *  - RESULT(COMPLETED) carries "completed" activation status hint.
 *  - RESULT(FAILED) carries "failed" activation status hint.
 *  - RESULT(TIMEOUT) carries "failed" activation status hint.
 *  - RESULT(CANCELLED) carries "failed" activation status hint.
 *
 * ### ResultKind values
 *  - Success RESULT signal carries ResultKind.COMPLETED.
 *  - Failure RESULT signal carries ResultKind.FAILED.
 *  - Timeout RESULT signal carries ResultKind.TIMEOUT.
 *  - Cancellation RESULT signal carries ResultKind.CANCELLED.
 *
 * ### PROGRESS signal
 *  - PROGRESS signal stepCount is 0 (no steps recorded at ACTIVE entry).
 *  - PROGRESS signal is emitted before the pipeline is called.
 *
 * ### toMetadataMap on emitted signals
 *  - ACK toMetadataMap contains all required identity keys.
 *  - RESULT(TIMEOUT) toMetadataMap contains KEY_RESULT_KIND with value "timeout".
 *  - RESULT(CANCELLED) toMetadataMap contains KEY_RESULT_KIND with value "cancelled".
 */
class DelegatedExecutionSignalEmissionTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeUnit(
        unitId: String = "unit-13",
        taskId: String = "task-13",
        traceId: String = "trace-13",
        sessionId: String = "sess-pr13"
    ) = DelegatedRuntimeUnit(
        unitId = unitId,
        taskId = taskId,
        traceId = traceId,
        goal = "open settings",
        attachedSessionId = sessionId
    )

    private fun pendingRecord(unit: DelegatedRuntimeUnit = makeUnit()) =
        DelegatedActivationRecord.create(unit = unit, activatedAtMs = 1_000L)

    private fun captureSignals(): Pair<MutableList<DelegatedExecutionSignal>, DelegatedExecutionSignalSink> {
        val captured = mutableListOf<DelegatedExecutionSignal>()
        val sink = DelegatedExecutionSignalSink { signal -> captured += signal }
        return Pair(captured, sink)
    }

    private fun executor(
        pipeline: GoalExecutionPipeline,
        sink: DelegatedExecutionSignalSink
    ) = DelegatedTakeoverExecutor(pipeline = pipeline, signalSink = sink)

    private fun successPipeline(): GoalExecutionPipeline = GoalExecutionPipeline { payload ->
        GoalResultPayload(task_id = payload.task_id, status = "success")
    }

    private fun failingPipeline(msg: String = "pipeline_error"): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw RuntimeException(msg) }

    private fun timeoutPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ ->
            throw TimeoutCancellationException("timed_out")
        }

    private fun cancelPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ ->
            throw CancellationException("cancelled_externally")
        }

    // ── Signal sequence and count ─────────────────────────────────────────────

    @Test
    fun `success path emits exactly ACK PROGRESS RESULT in order`() {
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals(3, signals.size)
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
    }

    @Test
    fun `failure path emits exactly ACK PROGRESS RESULT in order`() {
        val (signals, sink) = captureSignals()
        executor(failingPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals(3, signals.size)
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
    }

    @Test
    fun `timeout path emits exactly ACK PROGRESS RESULT in order`() {
        val (signals, sink) = captureSignals()
        executor(timeoutPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals(3, signals.size)
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
    }

    @Test
    fun `cancellation path emits exactly ACK PROGRESS RESULT in order`() {
        val (signals, sink) = captureSignals()
        executor(cancelPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals(3, signals.size)
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
    }

    // ── ResultKind values ─────────────────────────────────────────────────────

    @Test
    fun `success RESULT signal carries ResultKind COMPLETED`() {
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals(DelegatedExecutionSignal.ResultKind.COMPLETED, signals[2].resultKind)
    }

    @Test
    fun `failure RESULT signal carries ResultKind FAILED`() {
        val (signals, sink) = captureSignals()
        executor(failingPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED, signals[2].resultKind)
    }

    @Test
    fun `timeout RESULT signal carries ResultKind TIMEOUT`() {
        val (signals, sink) = captureSignals()
        executor(timeoutPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals(DelegatedExecutionSignal.ResultKind.TIMEOUT, signals[2].resultKind)
    }

    @Test
    fun `cancellation RESULT signal carries ResultKind CANCELLED`() {
        val (signals, sink) = captureSignals()
        executor(cancelPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals(DelegatedExecutionSignal.ResultKind.CANCELLED, signals[2].resultKind)
    }

    // ── Activation status hints ───────────────────────────────────────────────

    @Test
    fun `ACK signal carries pending activation status hint`() {
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals("pending", signals[0].activationStatusHint)
    }

    @Test
    fun `PROGRESS signal carries active activation status hint`() {
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals("active", signals[1].activationStatusHint)
    }

    @Test
    fun `success RESULT signal carries completed activation status hint`() {
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals("completed", signals[2].activationStatusHint)
    }

    @Test
    fun `failure RESULT signal carries failed activation status hint`() {
        val (signals, sink) = captureSignals()
        executor(failingPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals("failed", signals[2].activationStatusHint)
    }

    @Test
    fun `timeout RESULT signal carries failed activation status hint`() {
        val (signals, sink) = captureSignals()
        executor(timeoutPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals("failed", signals[2].activationStatusHint)
    }

    @Test
    fun `cancellation RESULT signal carries failed activation status hint`() {
        val (signals, sink) = captureSignals()
        executor(cancelPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals("failed", signals[2].activationStatusHint)
    }

    // ── Identity metadata continuity ──────────────────────────────────────────

    @Test
    fun `all signals on success path carry same unitId`() {
        val unit = makeUnit(unitId = "u-emit-1")
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(unit, pendingRecord(unit), nowMs = 1_000L)

        assertTrue(signals.all { it.unitId == "u-emit-1" })
    }

    @Test
    fun `all signals on success path carry same taskId`() {
        val unit = makeUnit(taskId = "t-emit-1")
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(unit, pendingRecord(unit), nowMs = 1_000L)

        assertTrue(signals.all { it.taskId == "t-emit-1" })
    }

    @Test
    fun `all signals on success path carry same traceId`() {
        val unit = makeUnit(traceId = "tr-emit-1")
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(unit, pendingRecord(unit), nowMs = 1_000L)

        assertTrue(signals.all { it.traceId == "tr-emit-1" })
    }

    @Test
    fun `all signals on success path carry same attachedSessionId`() {
        val unit = makeUnit(sessionId = "sess-emit-1")
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(unit, pendingRecord(unit), nowMs = 1_000L)

        assertTrue(signals.all { it.attachedSessionId == "sess-emit-1" })
    }

    @Test
    fun `all signals on success path carry same handoffContractVersion`() {
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        val version = signals[0].handoffContractVersion
        assertTrue(signals.all { it.handoffContractVersion == version })
    }

    @Test
    fun `all signals on timeout path carry consistent identity metadata`() {
        val unit = makeUnit(unitId = "u-to", taskId = "t-to", traceId = "tr-to", sessionId = "s-to")
        val (signals, sink) = captureSignals()
        executor(timeoutPipeline(), sink).execute(unit, pendingRecord(unit), nowMs = 1_000L)

        assertTrue(signals.all { it.unitId == "u-to" })
        assertTrue(signals.all { it.taskId == "t-to" })
        assertTrue(signals.all { it.traceId == "tr-to" })
        assertTrue(signals.all { it.attachedSessionId == "s-to" })
    }

    @Test
    fun `all signals on cancellation path carry consistent identity metadata`() {
        val unit = makeUnit(unitId = "u-ca", taskId = "t-ca", traceId = "tr-ca", sessionId = "s-ca")
        val (signals, sink) = captureSignals()
        executor(cancelPipeline(), sink).execute(unit, pendingRecord(unit), nowMs = 1_000L)

        assertTrue(signals.all { it.unitId == "u-ca" })
        assertTrue(signals.all { it.taskId == "t-ca" })
        assertTrue(signals.all { it.traceId == "tr-ca" })
        assertTrue(signals.all { it.attachedSessionId == "s-ca" })
    }

    // ── PROGRESS signal specifics ──────────────────────────────────────────────

    @Test
    fun `PROGRESS signal stepCount is 0 at ACTIVE entry`() {
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertEquals(0, signals[1].stepCount)
    }

    @Test
    fun `PROGRESS signal isProgress is true`() {
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertTrue(signals[1].isProgress)
    }

    @Test
    fun `PROGRESS signal resultKind is null`() {
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertNull(signals[1].resultKind)
    }

    // ── toMetadataMap on emitted signals ──────────────────────────────────────

    @Test
    fun `ACK toMetadataMap contains all required identity keys`() {
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        val map = signals[0].toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_SIGNAL_KIND))
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_UNIT_ID))
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_TASK_ID))
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_TRACE_ID))
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_ATTACHED_SESSION_ID))
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_HANDOFF_CONTRACT_VERSION))
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_STEP_COUNT))
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_ACTIVATION_STATUS_HINT))
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_TIMESTAMP_MS))
    }

    @Test
    fun `RESULT TIMEOUT toMetadataMap contains KEY_RESULT_KIND with value timeout`() {
        val (signals, sink) = captureSignals()
        executor(timeoutPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        val map = signals[2].toMetadataMap()
        assertEquals("timeout", map[DelegatedExecutionSignal.KEY_RESULT_KIND])
    }

    @Test
    fun `RESULT CANCELLED toMetadataMap contains KEY_RESULT_KIND with value cancelled`() {
        val (signals, sink) = captureSignals()
        executor(cancelPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        val map = signals[2].toMetadataMap()
        assertEquals("cancelled", map[DelegatedExecutionSignal.KEY_RESULT_KIND])
    }

    @Test
    fun `PROGRESS toMetadataMap does not contain KEY_RESULT_KIND`() {
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertFalse(signals[1].toMetadataMap().containsKey(DelegatedExecutionSignal.KEY_RESULT_KIND))
    }

    // ── ExecutionOutcome on new paths ─────────────────────────────────────────

    @Test
    fun `timeout path returns ExecutionOutcome Failed`() {
        val (_, sink) = captureSignals()
        val outcome = executor(timeoutPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertTrue(outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed)
    }

    @Test
    fun `cancellation path returns ExecutionOutcome Failed`() {
        val (_, sink) = captureSignals()
        val outcome = executor(cancelPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertTrue(outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed)
    }

    @Test
    fun `timeout outcome error message echoes exception message`() {
        val (_, sink) = captureSignals()
        val outcome = executor(timeoutPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
                as DelegatedTakeoverExecutor.ExecutionOutcome.Failed

        assertEquals("timed_out", outcome.error)
    }

    @Test
    fun `cancellation outcome error message echoes exception message`() {
        val (_, sink) = captureSignals()
        val outcome = executor(cancelPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
                as DelegatedTakeoverExecutor.ExecutionOutcome.Failed

        assertEquals("cancelled_externally", outcome.error)
    }

    @Test
    fun `timeout outcome tracker is in FAILED state`() {
        val (_, sink) = captureSignals()
        val outcome = executor(timeoutPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
                as DelegatedTakeoverExecutor.ExecutionOutcome.Failed

        assertEquals(
            DelegatedActivationRecord.ActivationStatus.FAILED,
            outcome.tracker.record.activationStatus
        )
    }

    @Test
    fun `cancellation outcome tracker is in FAILED state`() {
        val (_, sink) = captureSignals()
        val outcome = executor(cancelPipeline(), sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
                as DelegatedTakeoverExecutor.ExecutionOutcome.Failed

        assertEquals(
            DelegatedActivationRecord.ActivationStatus.FAILED,
            outcome.tracker.record.activationStatus
        )
    }
}
