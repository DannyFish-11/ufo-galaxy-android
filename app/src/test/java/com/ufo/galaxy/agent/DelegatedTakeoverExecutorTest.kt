package com.ufo.galaxy.agent

import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.runtime.DelegatedActivationRecord
import com.ufo.galaxy.runtime.DelegatedExecutionSignal
import com.ufo.galaxy.runtime.DelegatedExecutionSignalSink
import com.ufo.galaxy.runtime.SourceRuntimePosture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [DelegatedTakeoverExecutor] — the canonical Android-side
 * delegated receipt-to-local-takeover executor binding (PR-12 / PR-13,
 * post-#533 dual-repo runtime unification master plan — Canonical Android-Side
 * Delegated Execution Signal Emission Path, Android side).
 *
 * ## Test matrix
 *
 * ### ACK signal emission
 *  - ACK signal is the first signal emitted.
 *  - Exactly one ACK signal is emitted on success.
 *  - Exactly one ACK signal is emitted on failure.
 *  - ACK signal carries correct identity fields.
 *
 * ### PROGRESS signal emission (PR-13)
 *  - PROGRESS signal is the second signal emitted (index 1).
 *  - Exactly one PROGRESS signal is emitted on success.
 *  - Exactly one PROGRESS signal is emitted on failure.
 *
 * ### Tracker lifecycle on successful execution
 *  - Tracker is created in PENDING from the initial record.
 *  - Final tracker is in COMPLETED after success.
 *  - Tracker stepCount is 1 after execution.
 *  - executionStartedAtMs is set after execution.
 *
 * ### RESULT signal on success
 *  - RESULT signal is emitted after successful execution (index 2).
 *  - RESULT signal carries ResultKind.COMPLETED.
 *  - RESULT signal carries correct identity fields matching the unit.
 *  - Exactly three signals (ACK + PROGRESS + RESULT) are emitted on success.
 *
 * ### Tracker lifecycle on failed execution
 *  - Final tracker is in FAILED after exception.
 *  - Tracker stepCount is 0 after failure (no step recorded).
 *
 * ### RESULT signal on failure
 *  - RESULT signal is emitted after pipeline exception (index 2).
 *  - RESULT signal carries ResultKind.FAILED.
 *  - Exactly three signals (ACK + PROGRESS + RESULT) are emitted on failure.
 *
 * ### ExecutionOutcome types
 *  - Successful execution returns ExecutionOutcome.Completed.
 *  - Completed outcome carries the GoalResultPayload from the pipeline.
 *  - Completed outcome carries the final (COMPLETED) tracker.
 *  - Failed execution returns ExecutionOutcome.Failed.
 *  - Failed outcome carries the error message.
 *  - Failed outcome carries the final (FAILED) tracker.
 *
 * ### GoalExecutionPayload construction
 *  - Payload task_id echoes unit.taskId.
 *  - Payload goal echoes unit.goal.
 *  - Payload constraints echo unit.constraints.
 *  - Payload source_runtime_posture is JOIN_RUNTIME.
 *
 * ### Identity continuity
 *  - All signals carry the same unitId.
 *  - All signals carry the same taskId.
 *  - All signals carry the same traceId.
 *  - All signals carry the same attachedSessionId.
 *  - All signals carry the same handoffContractVersion.
 *
 * ### Timeout path (TimeoutCancellationException)
 *  - Three signals emitted on timeout (ACK + PROGRESS + RESULT).
 *  - RESULT signal on timeout carries ResultKind.TIMEOUT.
 *  - Outcome is ExecutionOutcome.Failed on timeout.
 *  - Final tracker is FAILED on timeout.
 *  - Error message defaults to "execution_timeout" when exception message is null.
 *
 * ### Cancellation path (CancellationException non-timeout)
 *  - Three signals emitted on cancellation (ACK + PROGRESS + RESULT).
 *  - RESULT signal on cancellation carries ResultKind.CANCELLED.
 *  - Outcome is ExecutionOutcome.Failed on cancellation.
 *  - Final tracker is FAILED on cancellation.
 *  - Error message defaults to "execution_cancelled" when exception message is null.
 *
 * ### EmittedSignalLedger — content after execution (PR-18)
 *  - Ledger lastAck is non-null after successful execution.
 *  - Ledger lastProgress is non-null after successful execution.
 *  - Ledger lastResult is non-null after successful execution.
 *  - Ledger lastResult.resultKind is COMPLETED after successful execution.
 *  - Ledger lastResult.resultKind is FAILED after pipeline exception.
 *  - Ledger lastResult.resultKind is TIMEOUT after TimeoutCancellationException.
 *  - Ledger lastResult.resultKind is CANCELLED after CancellationException.
 *  - Ledger returned in Completed outcome is populated.
 *  - Ledger returned in Failed outcome is populated.
 */
class DelegatedTakeoverExecutorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeUnit(
        unitId: String = "unit-12",
        taskId: String = "task-12",
        traceId: String = "trace-12",
        goal: String = "open settings",
        sessionId: String = "sess-pr12",
        constraints: List<String> = emptyList()
    ) = DelegatedRuntimeUnit(
        unitId = unitId,
        taskId = taskId,
        traceId = traceId,
        goal = goal,
        attachedSessionId = sessionId,
        constraints = constraints
    )

    private fun pendingRecord(unit: DelegatedRuntimeUnit = makeUnit()) =
        DelegatedActivationRecord.create(unit = unit, activatedAtMs = 1_000L)

    private fun captureSignals(): Pair<MutableList<DelegatedExecutionSignal>, DelegatedExecutionSignalSink> {
        val captured = mutableListOf<DelegatedExecutionSignal>()
        val sink = DelegatedExecutionSignalSink { signal -> captured += signal }
        return Pair(captured, sink)
    }

    private fun successPipeline(
        status: String = "success",
        taskId: String = "task-12"
    ): GoalExecutionPipeline = GoalExecutionPipeline { payload ->
        GoalResultPayload(
            task_id = payload.task_id,
            correlation_id = payload.task_id,
            status = status
        )
    }

    private fun failingPipeline(
        message: String = "simulated failure"
    ): GoalExecutionPipeline = GoalExecutionPipeline { _ ->
        throw RuntimeException(message)
    }

    private fun nullMessagePipeline(): GoalExecutionPipeline = GoalExecutionPipeline { _ ->
        throw RuntimeException()   // message is null
    }

    private fun timeoutPipeline(
        message: String = "execution_timed_out"
    ): GoalExecutionPipeline = GoalExecutionPipeline { _ ->
        throw TimeoutCancellationException(message)
    }

    private fun cancelPipeline(
        message: String = "execution_cancelled"
    ): GoalExecutionPipeline = GoalExecutionPipeline { _ ->
        throw CancellationException(message)
    }

    private fun buildExecutor(
        pipeline: GoalExecutionPipeline = successPipeline(),
        sink: DelegatedExecutionSignalSink = DelegatedExecutionSignalSink { }
    ) = DelegatedTakeoverExecutor(pipeline = pipeline, signalSink = sink)

    // ── ACK signal emission ───────────────────────────────────────────────────

    @Test
    fun `ACK signal is the first signal emitted on success`() {
        val (captured, sink) = captureSignals()
        val executor = buildExecutor(pipeline = successPipeline(), sink = sink)

        executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertTrue(
            "At least one signal must be emitted",
            captured.isNotEmpty()
        )
        assertTrue(
            "First emitted signal must be ACK, got ${captured[0].kind}",
            captured[0].isAck
        )
    }

    @Test
    fun `ACK signal is the first signal emitted on failure`() {
        val (captured, sink) = captureSignals()
        val executor = buildExecutor(pipeline = failingPipeline(), sink = sink)

        executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)

        assertTrue(captured.isNotEmpty())
        assertTrue(
            "First emitted signal must be ACK even on failure, got ${captured[0].kind}",
            captured[0].isAck
        )
    }

    @Test
    fun `ACK signal carries correct unit identity`() {
        val (captured, sink) = captureSignals()
        val unit = makeUnit(unitId = "u-ack-id", taskId = "t-ack", traceId = "tr-ack", sessionId = "sess-ack")
        val executor = buildExecutor(sink = sink)

        executor.execute(unit, pendingRecord(unit), nowMs = 2_000L)

        val ack = captured[0]
        assertEquals("u-ack-id", ack.unitId)
        assertEquals("t-ack", ack.taskId)
        assertEquals("tr-ack", ack.traceId)
        assertEquals("sess-ack", ack.attachedSessionId)
    }

    // ── Tracker lifecycle on success ──────────────────────────────────────────

    @Test
    fun `final tracker is COMPLETED after successful execution`() {
        val executor = buildExecutor()
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertTrue(outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Completed)
        val completed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.COMPLETED,
            completed.tracker.record.activationStatus
        )
    }

    @Test
    fun `tracker stepCount is 1 after successful execution`() {
        val executor = buildExecutor()
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertEquals(1, outcome.tracker.stepCount)
    }

    @Test
    fun `tracker executionStartedAtMs is set after successful execution`() {
        val executor = buildExecutor()
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 5_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertNotNull(
            "executionStartedAtMs must be non-null after execution",
            outcome.tracker.executionStartedAtMs
        )
    }

    @Test
    fun `tracker isTerminal is true after successful execution`() {
        val executor = buildExecutor()
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertTrue(outcome.tracker.isTerminal)
    }

    // ── RESULT signal on success ──────────────────────────────────────────────

    @Test
    fun `exactly three signals emitted on success (ACK + PROGRESS + RESULT)`() {
        val (captured, sink) = captureSignals()
        buildExecutor(sink = sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertEquals("Expected ACK + PROGRESS + RESULT = 3 signals", 3, captured.size)
    }

    @Test
    fun `second signal on success is PROGRESS`() {
        val (captured, sink) = captureSignals()
        buildExecutor(sink = sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertTrue(
            "Second signal must be PROGRESS, got ${captured[1].kind}",
            captured[1].isProgress
        )
    }

    @Test
    fun `third signal on success is RESULT`() {
        val (captured, sink) = captureSignals()
        buildExecutor(sink = sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertTrue(
            "Third signal must be RESULT, got ${captured[2].kind}",
            captured[2].isResult
        )
    }

    @Test
    fun `RESULT signal on success carries ResultKind COMPLETED`() {
        val (captured, sink) = captureSignals()
        buildExecutor(sink = sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        val resultSignal = captured[2]
        assertEquals(DelegatedExecutionSignal.ResultKind.COMPLETED, resultSignal.resultKind)
    }

    @Test
    fun `RESULT signal on success carries correct unit identity`() {
        val (captured, sink) = captureSignals()
        val unit = makeUnit(unitId = "u-res", taskId = "t-res", traceId = "tr-res", sessionId = "sess-res")
        buildExecutor(sink = sink).execute(unit, pendingRecord(unit), nowMs = 1_000L)
        val resultSignal = captured[2]
        assertEquals("u-res", resultSignal.unitId)
        assertEquals("t-res", resultSignal.taskId)
        assertEquals("tr-res", resultSignal.traceId)
        assertEquals("sess-res", resultSignal.attachedSessionId)
    }

    @Test
    fun `RESULT signal on success stepCount is 1`() {
        val (captured, sink) = captureSignals()
        buildExecutor(sink = sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertEquals(1, captured[2].stepCount)
    }

    // ── Tracker lifecycle on failure ──────────────────────────────────────────

    @Test
    fun `final tracker is FAILED after pipeline exception`() {
        val executor = buildExecutor(pipeline = failingPipeline())
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertTrue(outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed)
        val failed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.FAILED,
            failed.tracker.record.activationStatus
        )
    }

    @Test
    fun `tracker stepCount is 0 after failure (no step recorded on exception)`() {
        val executor = buildExecutor(pipeline = failingPipeline())
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(0, outcome.tracker.stepCount)
    }

    @Test
    fun `tracker isTerminal is true after failure`() {
        val executor = buildExecutor(pipeline = failingPipeline())
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertTrue(outcome.tracker.isTerminal)
    }

    // ── RESULT signal on failure ──────────────────────────────────────────────

    @Test
    fun `exactly three signals emitted on failure (ACK + PROGRESS + RESULT)`() {
        val (captured, sink) = captureSignals()
        buildExecutor(pipeline = failingPipeline(), sink = sink)
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertEquals("Expected ACK + PROGRESS + RESULT = 3 signals on failure", 3, captured.size)
    }

    @Test
    fun `third signal on failure is RESULT`() {
        val (captured, sink) = captureSignals()
        buildExecutor(pipeline = failingPipeline(), sink = sink)
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertTrue(
            "Third signal on failure must be RESULT, got ${captured[2].kind}",
            captured[2].isResult
        )
    }

    @Test
    fun `RESULT signal on failure carries ResultKind FAILED`() {
        val (captured, sink) = captureSignals()
        buildExecutor(pipeline = failingPipeline(), sink = sink)
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED, captured[2].resultKind)
    }

    @Test
    fun `RESULT signal on failure carries correct unit identity`() {
        val (captured, sink) = captureSignals()
        val unit = makeUnit(unitId = "u-fail", taskId = "t-fail", traceId = "tr-fail", sessionId = "sess-fail")
        buildExecutor(pipeline = failingPipeline(), sink = sink)
            .execute(unit, pendingRecord(unit), nowMs = 1_000L)
        val resultSignal = captured[2]
        assertEquals("u-fail", resultSignal.unitId)
        assertEquals("t-fail", resultSignal.taskId)
        assertEquals("tr-fail", resultSignal.traceId)
        assertEquals("sess-fail", resultSignal.attachedSessionId)
    }

    // ── ExecutionOutcome types ────────────────────────────────────────────────

    @Test
    fun `successful execution returns ExecutionOutcome Completed`() {
        val executor = buildExecutor(pipeline = successPipeline())
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertTrue(
            "Expected Completed outcome, got ${outcome::class.simpleName}",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        )
    }

    @Test
    fun `Completed outcome carries GoalResultPayload from pipeline`() {
        val pipeline = successPipeline(status = "success", taskId = "task-12")
        val executor = buildExecutor(pipeline = pipeline)
        val outcome = executor.execute(makeUnit(taskId = "task-12"), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertEquals("task-12", outcome.goalResult.task_id)
        assertEquals("success", outcome.goalResult.status)
    }

    @Test
    fun `Completed outcome carries terminal COMPLETED tracker`() {
        val executor = buildExecutor()
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.COMPLETED,
            outcome.tracker.record.activationStatus
        )
    }

    @Test
    fun `failed execution returns ExecutionOutcome Failed`() {
        val executor = buildExecutor(pipeline = failingPipeline())
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertTrue(
            "Expected Failed outcome, got ${outcome::class.simpleName}",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        )
    }

    @Test
    fun `Failed outcome error echoes pipeline exception message`() {
        val executor = buildExecutor(pipeline = failingPipeline("pipeline error detail"))
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals("pipeline error detail", outcome.error)
    }

    @Test
    fun `Failed outcome carries terminal FAILED tracker`() {
        val executor = buildExecutor(pipeline = failingPipeline())
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.FAILED,
            outcome.tracker.record.activationStatus
        )
    }

    // ── GoalExecutionPayload construction ─────────────────────────────────────

    @Test
    fun `GoalExecutionPayload task_id echoes unit taskId`() {
        var capturedPayload: GoalExecutionPayload? = null
        val capturingPipeline = GoalExecutionPipeline { payload ->
            capturedPayload = payload
            GoalResultPayload(task_id = payload.task_id, status = "success")
        }
        val unit = makeUnit(taskId = "my-task-42")
        buildExecutor(pipeline = capturingPipeline).execute(unit, pendingRecord(unit), nowMs = 1_000L)
        assertEquals("my-task-42", capturedPayload?.task_id)
    }

    @Test
    fun `GoalExecutionPayload goal echoes unit goal`() {
        var capturedPayload: GoalExecutionPayload? = null
        val capturingPipeline = GoalExecutionPipeline { payload ->
            capturedPayload = payload
            GoalResultPayload(task_id = payload.task_id, status = "success")
        }
        val unit = makeUnit(goal = "tap the home button")
        buildExecutor(pipeline = capturingPipeline).execute(unit, pendingRecord(unit), nowMs = 1_000L)
        assertEquals("tap the home button", capturedPayload?.goal)
    }

    @Test
    fun `GoalExecutionPayload constraints echo unit constraints`() {
        var capturedPayload: GoalExecutionPayload? = null
        val capturingPipeline = GoalExecutionPipeline { payload ->
            capturedPayload = payload
            GoalResultPayload(task_id = payload.task_id, status = "success")
        }
        val unit = makeUnit(constraints = listOf("step-limit:5", "no-scroll"))
        buildExecutor(pipeline = capturingPipeline).execute(unit, pendingRecord(unit), nowMs = 1_000L)
        assertEquals(listOf("step-limit:5", "no-scroll"), capturedPayload?.constraints)
    }

    @Test
    fun `GoalExecutionPayload source_runtime_posture is JOIN_RUNTIME`() {
        var capturedPayload: GoalExecutionPayload? = null
        val capturingPipeline = GoalExecutionPipeline { payload ->
            capturedPayload = payload
            GoalResultPayload(task_id = payload.task_id, status = "success")
        }
        buildExecutor(pipeline = capturingPipeline).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, capturedPayload?.source_runtime_posture)
    }

    // ── Identity continuity ───────────────────────────────────────────────────

    @Test
    fun `all signals carry the same unitId`() {
        val (captured, sink) = captureSignals()
        val unit = makeUnit(unitId = "stable-unit-id")
        buildExecutor(sink = sink).execute(unit, pendingRecord(unit), nowMs = 1_000L)
        val ids = captured.map { it.unitId }.distinct()
        assertEquals("All signals must carry the same unitId", listOf("stable-unit-id"), ids)
    }

    @Test
    fun `all signals carry the same taskId`() {
        val (captured, sink) = captureSignals()
        val unit = makeUnit(taskId = "stable-task")
        buildExecutor(sink = sink).execute(unit, pendingRecord(unit), nowMs = 1_000L)
        val ids = captured.map { it.taskId }.distinct()
        assertEquals(listOf("stable-task"), ids)
    }

    @Test
    fun `all signals carry the same traceId`() {
        val (captured, sink) = captureSignals()
        val unit = makeUnit(traceId = "stable-trace")
        buildExecutor(sink = sink).execute(unit, pendingRecord(unit), nowMs = 1_000L)
        val ids = captured.map { it.traceId }.distinct()
        assertEquals(listOf("stable-trace"), ids)
    }

    @Test
    fun `all signals carry the same attachedSessionId`() {
        val (captured, sink) = captureSignals()
        val unit = makeUnit(sessionId = "stable-session")
        buildExecutor(sink = sink).execute(unit, pendingRecord(unit), nowMs = 1_000L)
        val ids = captured.map { it.attachedSessionId }.distinct()
        assertEquals(listOf("stable-session"), ids)
    }

    @Test
    fun `all signals carry the same handoffContractVersion`() {
        val (captured, sink) = captureSignals()
        buildExecutor(sink = sink).execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        val versions = captured.map { it.handoffContractVersion }.distinct()
        assertEquals(
            "All signals must carry the same handoffContractVersion",
            1,
            versions.size
        )
        assertEquals(DelegatedHandoffContract.CURRENT_CONTRACT_VERSION, versions[0])
    }

    @Test
    fun `identity continuity holds on failed execution`() {
        val (captured, sink) = captureSignals()
        val unit = makeUnit(
            unitId = "fail-unit",
            taskId = "fail-task",
            traceId = "fail-trace",
            sessionId = "fail-sess"
        )
        buildExecutor(pipeline = failingPipeline(), sink = sink)
            .execute(unit, pendingRecord(unit), nowMs = 1_000L)

        assertEquals(3, captured.size)
        captured.forEach { signal ->
            assertEquals("fail-unit", signal.unitId)
            assertEquals("fail-task", signal.taskId)
            assertEquals("fail-trace", signal.traceId)
            assertEquals("fail-sess", signal.attachedSessionId)
        }
    }

    // ── Exception with null message ───────────────────────────────────────────

    @Test
    fun `Failed outcome error falls back to execution_error when exception message is null`() {
        val executor = buildExecutor(pipeline = nullMessagePipeline())
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals("execution_error", outcome.error)
    }

    // ── Timeout path (TimeoutCancellationException) ───────────────────────────

    @Test
    fun `exactly three signals emitted on timeout (ACK + PROGRESS + RESULT)`() {
        val (captured, sink) = captureSignals()
        buildExecutor(pipeline = timeoutPipeline(), sink = sink)
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertEquals("Expected ACK + PROGRESS + RESULT = 3 signals on timeout", 3, captured.size)
    }

    @Test
    fun `RESULT signal on timeout carries ResultKind TIMEOUT`() {
        val (captured, sink) = captureSignals()
        buildExecutor(pipeline = timeoutPipeline(), sink = sink)
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertEquals(DelegatedExecutionSignal.ResultKind.TIMEOUT, captured[2].resultKind)
    }

    @Test
    fun `timeout path returns ExecutionOutcome Failed`() {
        val outcome = buildExecutor(pipeline = timeoutPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertTrue(
            "Timeout must return ExecutionOutcome.Failed",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        )
    }

    @Test
    fun `final tracker is FAILED after timeout`() {
        val outcome = buildExecutor(pipeline = timeoutPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.FAILED,
            outcome.tracker.record.activationStatus
        )
    }

    @Test
    fun `Failed outcome error carries message from TimeoutCancellationException`() {
        val outcome = buildExecutor(pipeline = timeoutPipeline("my_timeout_detail"))
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals("my_timeout_detail", outcome.error)
    }

    // ── Cancellation path (CancellationException non-timeout) ────────────────

    @Test
    fun `exactly three signals emitted on cancellation (ACK + PROGRESS + RESULT)`() {
        val (captured, sink) = captureSignals()
        buildExecutor(pipeline = cancelPipeline(), sink = sink)
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertEquals("Expected ACK + PROGRESS + RESULT = 3 signals on cancellation", 3, captured.size)
    }

    @Test
    fun `RESULT signal on cancellation carries ResultKind CANCELLED`() {
        val (captured, sink) = captureSignals()
        buildExecutor(pipeline = cancelPipeline(), sink = sink)
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertEquals(DelegatedExecutionSignal.ResultKind.CANCELLED, captured[2].resultKind)
    }

    @Test
    fun `cancellation path returns ExecutionOutcome Failed`() {
        val outcome = buildExecutor(pipeline = cancelPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertTrue(
            "Cancellation must return ExecutionOutcome.Failed",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        )
    }

    @Test
    fun `final tracker is FAILED after cancellation`() {
        val outcome = buildExecutor(pipeline = cancelPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.FAILED,
            outcome.tracker.record.activationStatus
        )
    }

    @Test
    fun `Failed outcome error falls back to execution_cancelled when CancellationException message is null`() {
        val nullCancelPipeline = GoalExecutionPipeline { _ ->
            throw CancellationException()
        }
        val outcome = buildExecutor(pipeline = nullCancelPipeline)
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals("execution_cancelled", outcome.error)
    }

    // ── EmittedSignalLedger — content after execution (PR-18) ─────────────────

    @Test
    fun `ledger lastAck is non-null after successful execution`() {
        val outcome = buildExecutor()
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertNotNull("lastAck must be recorded in ledger after execution", outcome.ledger.lastAck)
    }

    @Test
    fun `ledger lastProgress is non-null after successful execution`() {
        val outcome = buildExecutor()
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertNotNull("lastProgress must be recorded in ledger after execution", outcome.ledger.lastProgress)
    }

    @Test
    fun `ledger lastResult is non-null after successful execution`() {
        val outcome = buildExecutor()
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertNotNull("lastResult must be recorded in ledger after execution", outcome.ledger.lastResult)
    }

    @Test
    fun `ledger lastResult resultKind is COMPLETED after successful execution`() {
        val outcome = buildExecutor()
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertEquals(DelegatedExecutionSignal.ResultKind.COMPLETED, outcome.ledger.lastResult?.resultKind)
    }

    @Test
    fun `ledger lastResult resultKind is FAILED after pipeline exception`() {
        val outcome = buildExecutor(pipeline = failingPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED, outcome.ledger.lastResult?.resultKind)
    }

    @Test
    fun `ledger lastResult resultKind is TIMEOUT after TimeoutCancellationException`() {
        val outcome = buildExecutor(pipeline = timeoutPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(DelegatedExecutionSignal.ResultKind.TIMEOUT, outcome.ledger.lastResult?.resultKind)
    }

    @Test
    fun `ledger lastResult resultKind is CANCELLED after CancellationException`() {
        val outcome = buildExecutor(pipeline = cancelPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(DelegatedExecutionSignal.ResultKind.CANCELLED, outcome.ledger.lastResult?.resultKind)
    }

    @Test
    fun `ledger in Completed outcome has all three signal kinds populated`() {
        val outcome = buildExecutor()
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertNotNull(outcome.ledger.lastAck)
        assertNotNull(outcome.ledger.lastProgress)
        assertNotNull(outcome.ledger.lastResult)
    }

    @Test
    fun `ledger in Failed outcome has all three signal kinds populated`() {
        val outcome = buildExecutor(pipeline = failingPipeline())
            .execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertNotNull(outcome.ledger.lastAck)
        assertNotNull(outcome.ledger.lastProgress)
        assertNotNull(outcome.ledger.lastResult)
    }
}
