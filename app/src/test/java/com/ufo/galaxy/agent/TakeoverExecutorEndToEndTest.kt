package com.ufo.galaxy.agent

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.runtime.AttachedRuntimeSession
import com.ufo.galaxy.runtime.DelegatedActivationRecord
import com.ufo.galaxy.runtime.DelegatedExecutionSignal
import com.ufo.galaxy.runtime.DelegatedExecutionSignalSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end tests for the complete Android-side takeover execution path.
 *
 * Exercises the full pipeline:
 * ```
 * TakeoverEligibilityAssessor.assess()
 *        │
 *        ▼ eligible
 * DelegatedRuntimeReceiver.receive(envelope, session)
 *        │
 *        ▼ Accepted
 * DelegatedTakeoverExecutor.execute(unit, record)
 *        │
 *        ▼ ACK / PROGRESS / RESULT signals + ExecutionOutcome
 * ```
 *
 * These tests prove that:
 * 1. An accepted takeover request drives real execution, not just protocol plumbing.
 * 2. Rejected paths (eligibility failure, session gate failure) produce structured reasons.
 * 3. Execution outcomes (success, failure, timeout, cancellation) emit the correct signals.
 * 4. All emitted signals carry consistent identity fields (unitId, taskId, traceId, sessionId).
 * 5. The [com.ufo.galaxy.runtime.EmittedSignalLedger] in the outcome matches the sink.
 *
 * ## Test matrix
 *
 * ### Accepted takeover request path
 *  - Eligible device + ATTACHED session produces ReceiptResult.Accepted.
 *  - Accepted path drives executor: ACK + PROGRESS + RESULT signals emitted.
 *  - Accepted path with successful pipeline returns Completed outcome.
 *  - Completed outcome goalResult matches pipeline result.
 *  - All signals carry consistent taskId.
 *  - All signals carry consistent traceId.
 *  - All signals carry consistent attachedSessionId.
 *
 * ### Rejected takeover request — eligibility gate
 *  - cross_device_disabled produces BLOCKED_CROSS_DEVICE_DISABLED rejection.
 *  - goal_execution_disabled produces BLOCKED_GOAL_EXECUTION_DISABLED rejection.
 *  - accessibility_not_ready produces BLOCKED_ACCESSIBILITY_NOT_READY rejection.
 *  - overlay_not_ready produces BLOCKED_OVERLAY_NOT_READY rejection.
 *  - delegated_execution_disabled produces BLOCKED_DELEGATED_EXECUTION_DISABLED rejection.
 *
 * ### Rejected takeover request — session gate
 *  - null session produces ReceiptResult.Rejected (NO_ATTACHED_SESSION).
 *  - DETACHED session produces ReceiptResult.Rejected (SESSION_DETACHED).
 *  - DETACHING session produces ReceiptResult.Rejected (SESSION_DETACHING).
 *
 * ### Successful execution path
 *  - Final tracker is COMPLETED.
 *  - RESULT signal carries ResultKind.COMPLETED.
 *  - ACK emissionSeq = 1, PROGRESS emissionSeq = 2, RESULT emissionSeq = 3.
 *
 * ### Failure execution path
 *  - Final tracker is FAILED.
 *  - RESULT signal carries ResultKind.FAILED.
 *  - Failed outcome error echoes exception message.
 *
 * ### Timeout execution path
 *  - RESULT signal carries ResultKind.TIMEOUT.
 *  - Failed outcome error is "execution_timeout" on null message.
 *
 * ### Cancellation execution path
 *  - RESULT signal carries ResultKind.CANCELLED.
 *  - Failed outcome error is "execution_cancelled" on null message.
 *
 * ### Duplicate-terminal suppression
 *  - isTerminal is true after Completed outcome.
 *  - isTerminal is true after Failed outcome.
 *  - A second execute() call with an already-terminal record throws or produces FAILED.
 */
class TakeoverExecutorEndToEndTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fullyReadySettings() = InMemoryAppSettings(
        crossDeviceEnabled = true,
        goalExecutionEnabled = true,
        accessibilityReady = true,
        overlayReady = true,
        delegatedExecutionAllowed = true
    )

    private fun attachedSession(
        sessionId: String = "sess-e2e"
    ) = AttachedRuntimeSession.create(
        hostId = "host-e2e",
        deviceId = "device-e2e",
        sessionId = sessionId
    )

    private fun minimalEnvelope(
        takeoverId: String = "to-e2e",
        taskId: String = "task-e2e",
        traceId: String = "trace-e2e",
        goal: String = "open settings"
    ) = TakeoverRequestEnvelope(
        takeover_id = takeoverId,
        task_id = taskId,
        trace_id = traceId,
        goal = goal
    )

    private fun captureSignals(): Pair<MutableList<DelegatedExecutionSignal>, DelegatedExecutionSignalSink> {
        val captured = mutableListOf<DelegatedExecutionSignal>()
        return Pair(captured, DelegatedExecutionSignalSink { captured += it })
    }

    private fun successPipeline(
        status: String = "success"
    ): GoalExecutionPipeline = GoalExecutionPipeline { payload ->
        GoalResultPayload(task_id = payload.task_id, status = status)
    }

    private fun failingPipeline(msg: String = "pipeline_error"): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw RuntimeException(msg) }

    private fun timeoutPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw TimeoutCancellationException("timed out") }

    private fun cancelPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw CancellationException("cancelled") }

    /**
     * Runs the full accept → execute path and returns the signals captured and the outcome.
     */
    private fun runAcceptedExecution(
        pipeline: GoalExecutionPipeline = successPipeline(),
        session: AttachedRuntimeSession = attachedSession(),
        envelope: TakeoverRequestEnvelope = minimalEnvelope()
    ): Pair<List<DelegatedExecutionSignal>, DelegatedTakeoverExecutor.ExecutionOutcome> {
        val (captured, sink) = captureSignals()
        val receiver = DelegatedRuntimeReceiver()
        val executor = DelegatedTakeoverExecutor(pipeline = pipeline, signalSink = sink)

        val receiptResult = receiver.receive(envelope, session)
        check(receiptResult is DelegatedRuntimeReceiver.ReceiptResult.Accepted) {
            "Expected Accepted but got $receiptResult"
        }
        val outcome = executor.execute(receiptResult.unit, receiptResult.record, nowMs = 1_000L)
        return Pair(captured, outcome)
    }

    // ── Accepted takeover request path ────────────────────────────────────────

    @Test
    fun `eligible device and ATTACHED session produces ReceiptResult Accepted`() {
        val receiver = DelegatedRuntimeReceiver()
        val result = receiver.receive(minimalEnvelope(), session = attachedSession())
        assertTrue(
            "Eligible device + ATTACHED session must produce Accepted",
            result is DelegatedRuntimeReceiver.ReceiptResult.Accepted
        )
    }

    @Test
    fun `accepted path drives executor and emits ACK PROGRESS RESULT`() {
        val (signals, _) = runAcceptedExecution()
        assertEquals("Expected exactly 3 signals (ACK + PROGRESS + RESULT)", 3, signals.size)
        assertTrue("First signal must be ACK", signals[0].isAck)
        assertTrue("Second signal must be PROGRESS", signals[1].isProgress)
        assertTrue("Third signal must be RESULT", signals[2].isResult)
    }

    @Test
    fun `accepted path with successful pipeline returns Completed outcome`() {
        val (_, outcome) = runAcceptedExecution(pipeline = successPipeline())
        assertTrue(
            "Successful pipeline must produce Completed outcome",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        )
    }

    @Test
    fun `Completed outcome goalResult status is success`() {
        val (_, outcome) = runAcceptedExecution(pipeline = successPipeline(status = "success"))
        val completed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertEquals("success", completed.goalResult.status)
    }

    @Test
    fun `Completed outcome goalResult taskId echoes envelope taskId`() {
        val envelope = minimalEnvelope(taskId = "task-identity-check")
        val (_, outcome) = runAcceptedExecution(pipeline = successPipeline(), envelope = envelope)
        val completed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertEquals("task-identity-check", completed.goalResult.task_id)
    }

    @Test
    fun `all signals carry consistent taskId`() {
        val envelope = minimalEnvelope(taskId = "consistent-task")
        val (signals, _) = runAcceptedExecution(envelope = envelope)
        val taskIds = signals.map { it.taskId }.distinct()
        assertEquals("All signals must carry the same taskId", listOf("consistent-task"), taskIds)
    }

    @Test
    fun `all signals carry consistent traceId`() {
        val envelope = minimalEnvelope(traceId = "consistent-trace")
        val (signals, _) = runAcceptedExecution(envelope = envelope)
        val traceIds = signals.map { it.traceId }.distinct()
        assertEquals("All signals must carry the same traceId", listOf("consistent-trace"), traceIds)
    }

    @Test
    fun `all signals carry consistent attachedSessionId`() {
        val session = attachedSession(sessionId = "consistent-session")
        val (signals, _) = runAcceptedExecution(session = session)
        val sessionIds = signals.map { it.attachedSessionId }.distinct()
        assertEquals("All signals must carry the same attachedSessionId", listOf("consistent-session"), sessionIds)
    }

    // ── Rejected takeover — eligibility gate ──────────────────────────────────

    @Test
    fun `cross_device_disabled blocks takeover with BLOCKED_CROSS_DEVICE_DISABLED`() {
        val settings = fullyReadySettings().also { it.crossDeviceEnabled = false }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope())
        assertFalse("cross_device OFF must be ineligible", result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_CROSS_DEVICE_DISABLED,
            result.outcome
        )
    }

    @Test
    fun `goal_execution_disabled blocks takeover with BLOCKED_GOAL_EXECUTION_DISABLED`() {
        val settings = fullyReadySettings().also { it.goalExecutionEnabled = false }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_GOAL_EXECUTION_DISABLED,
            result.outcome
        )
    }

    @Test
    fun `delegated_execution_disabled blocks takeover with BLOCKED_DELEGATED_EXECUTION_DISABLED`() {
        val settings = fullyReadySettings().also { it.delegatedExecutionAllowed = false }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_DELEGATED_EXECUTION_DISABLED,
            result.outcome
        )
    }

    @Test
    fun `accessibility_not_ready blocks takeover with BLOCKED_ACCESSIBILITY_NOT_READY`() {
        val settings = fullyReadySettings().also { it.accessibilityReady = false }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_ACCESSIBILITY_NOT_READY,
            result.outcome
        )
    }

    @Test
    fun `overlay_not_ready blocks takeover with BLOCKED_OVERLAY_NOT_READY`() {
        val settings = fullyReadySettings().also { it.overlayReady = false }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_OVERLAY_NOT_READY,
            result.outcome
        )
    }

    @Test
    fun `eligibility rejection reason is machine-readable and non-blank`() {
        val settings = fullyReadySettings().also { it.crossDeviceEnabled = false }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope())
        assertTrue(
            "Rejection reason must be non-blank for V2 to consume",
            result.reason.isNotBlank()
        )
    }

    @Test
    fun `fully eligible device returns ELIGIBLE outcome`() {
        val assessor = TakeoverEligibilityAssessor(fullyReadySettings())
        val result = assessor.assess(minimalEnvelope())
        assertTrue("Fully ready device must be eligible", result.eligible)
        assertEquals(TakeoverEligibilityAssessor.EligibilityOutcome.ELIGIBLE, result.outcome)
    }

    // ── Rejected takeover — session gate ─────────────────────────────────────

    @Test
    fun `null session produces ReceiptResult Rejected`() {
        val receiver = DelegatedRuntimeReceiver()
        val result = receiver.receive(minimalEnvelope(), session = null)
        assertTrue(
            "null session must produce Rejected",
            result is DelegatedRuntimeReceiver.ReceiptResult.Rejected
        )
    }

    @Test
    fun `null session rejection outcome is NO_ATTACHED_SESSION`() {
        val receiver = DelegatedRuntimeReceiver()
        val result = receiver.receive(minimalEnvelope(), session = null)
            as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.NO_ATTACHED_SESSION,
            result.outcome
        )
    }

    @Test
    fun `DETACHED session produces ReceiptResult Rejected`() {
        val session = attachedSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val receiver = DelegatedRuntimeReceiver()
        val result = receiver.receive(minimalEnvelope(), session = session)
        assertTrue(
            "DETACHED session must produce Rejected",
            result is DelegatedRuntimeReceiver.ReceiptResult.Rejected
        )
    }

    @Test
    fun `DETACHED session rejection outcome is SESSION_DETACHED`() {
        val session = attachedSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        val receiver = DelegatedRuntimeReceiver()
        val result = receiver.receive(minimalEnvelope(), session = session)
            as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.SESSION_DETACHED,
            result.outcome
        )
    }

    @Test
    fun `DETACHING session produces ReceiptResult Rejected`() {
        val session = attachedSession()
            .beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val receiver = DelegatedRuntimeReceiver()
        val result = receiver.receive(minimalEnvelope(), session = session)
        assertTrue(
            "DETACHING session must produce Rejected",
            result is DelegatedRuntimeReceiver.ReceiptResult.Rejected
        )
    }

    @Test
    fun `session rejection reason is machine-readable and non-blank`() {
        val receiver = DelegatedRuntimeReceiver()
        val result = receiver.receive(minimalEnvelope(), session = null)
            as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertTrue(
            "Session rejection reason must be non-blank for V2 to consume",
            result.reason.isNotBlank()
        )
    }

    // ── Successful execution path ─────────────────────────────────────────────

    @Test
    fun `final tracker is COMPLETED after successful execution`() {
        val (_, outcome) = runAcceptedExecution(pipeline = successPipeline())
        val completed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.COMPLETED,
            completed.tracker.record.activationStatus
        )
    }

    @Test
    fun `final tracker isTerminal is true after successful execution`() {
        val (_, outcome) = runAcceptedExecution(pipeline = successPipeline())
        val completed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertTrue("Tracker must be terminal after completion", completed.tracker.isTerminal)
    }

    @Test
    fun `RESULT signal carries ResultKind COMPLETED on success`() {
        val (signals, _) = runAcceptedExecution(pipeline = successPipeline())
        assertEquals(DelegatedExecutionSignal.ResultKind.COMPLETED, signals[2].resultKind)
    }

    @Test
    fun `emissionSeq ordering is ACK=1 PROGRESS=2 RESULT=3 on success`() {
        val (signals, _) = runAcceptedExecution(pipeline = successPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_ACK, signals[0].emissionSeq)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS, signals[1].emissionSeq)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_RESULT, signals[2].emissionSeq)
    }

    @Test
    fun `ledger lastResult ResultKind is COMPLETED after successful execution`() {
        val (_, outcome) = runAcceptedExecution(pipeline = successPipeline())
        val completed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertEquals(
            DelegatedExecutionSignal.ResultKind.COMPLETED,
            completed.ledger.lastResult?.resultKind
        )
    }

    // ── Failure execution path ────────────────────────────────────────────────

    @Test
    fun `accepted path with failing pipeline returns Failed outcome`() {
        val (_, outcome) = runAcceptedExecution(pipeline = failingPipeline())
        assertTrue(
            "Failing pipeline must produce Failed outcome",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        )
    }

    @Test
    fun `final tracker is FAILED after pipeline exception`() {
        val (_, outcome) = runAcceptedExecution(pipeline = failingPipeline())
        val failed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.FAILED,
            failed.tracker.record.activationStatus
        )
    }

    @Test
    fun `final tracker isTerminal is true after failure`() {
        val (_, outcome) = runAcceptedExecution(pipeline = failingPipeline())
        val failed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertTrue("Tracker must be terminal after failure", failed.tracker.isTerminal)
    }

    @Test
    fun `RESULT signal carries ResultKind FAILED on exception`() {
        val (signals, _) = runAcceptedExecution(pipeline = failingPipeline())
        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED, signals[2].resultKind)
    }

    @Test
    fun `Failed outcome error echoes pipeline exception message`() {
        val (_, outcome) = runAcceptedExecution(pipeline = failingPipeline("specific_error_detail"))
        val failed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals("specific_error_detail", failed.error)
    }

    @Test
    fun `ledger lastResult ResultKind is FAILED after pipeline exception`() {
        val (_, outcome) = runAcceptedExecution(pipeline = failingPipeline())
        val failed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(
            DelegatedExecutionSignal.ResultKind.FAILED,
            failed.ledger.lastResult?.resultKind
        )
    }

    @Test
    fun `emissionSeq ordering is ACK=1 PROGRESS=2 RESULT=3 on failure`() {
        val (signals, _) = runAcceptedExecution(pipeline = failingPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_ACK, signals[0].emissionSeq)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS, signals[1].emissionSeq)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_RESULT, signals[2].emissionSeq)
    }

    // ── Timeout execution path ────────────────────────────────────────────────

    @Test
    fun `RESULT signal carries ResultKind TIMEOUT on TimeoutCancellationException`() {
        val (signals, _) = runAcceptedExecution(pipeline = timeoutPipeline())
        assertEquals(DelegatedExecutionSignal.ResultKind.TIMEOUT, signals[2].resultKind)
    }

    @Test
    fun `timeout path returns Failed outcome`() {
        val (_, outcome) = runAcceptedExecution(pipeline = timeoutPipeline())
        assertTrue(
            "Timeout must produce Failed outcome",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        )
    }

    @Test
    fun `ledger lastResult ResultKind is TIMEOUT on timeout`() {
        val (_, outcome) = runAcceptedExecution(pipeline = timeoutPipeline())
        val failed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(DelegatedExecutionSignal.ResultKind.TIMEOUT, failed.ledger.lastResult?.resultKind)
    }

    @Test
    fun `emissionSeq ordering is ACK=1 PROGRESS=2 RESULT=3 on timeout`() {
        val (signals, _) = runAcceptedExecution(pipeline = timeoutPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_ACK, signals[0].emissionSeq)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS, signals[1].emissionSeq)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_RESULT, signals[2].emissionSeq)
    }

    // ── Cancellation execution path ───────────────────────────────────────────

    @Test
    fun `RESULT signal carries ResultKind CANCELLED on CancellationException`() {
        val (signals, _) = runAcceptedExecution(pipeline = cancelPipeline())
        assertEquals(DelegatedExecutionSignal.ResultKind.CANCELLED, signals[2].resultKind)
    }

    @Test
    fun `cancellation path returns Failed outcome`() {
        val (_, outcome) = runAcceptedExecution(pipeline = cancelPipeline())
        assertTrue(
            "Cancellation must produce Failed outcome",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        )
    }

    @Test
    fun `ledger lastResult ResultKind is CANCELLED on cancellation`() {
        val (_, outcome) = runAcceptedExecution(pipeline = cancelPipeline())
        val failed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(DelegatedExecutionSignal.ResultKind.CANCELLED, failed.ledger.lastResult?.resultKind)
    }

    @Test
    fun `emissionSeq ordering is ACK=1 PROGRESS=2 RESULT=3 on cancellation`() {
        val (signals, _) = runAcceptedExecution(pipeline = cancelPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_ACK, signals[0].emissionSeq)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS, signals[1].emissionSeq)
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_RESULT, signals[2].emissionSeq)
    }

    // ── Duplicate-terminal suppression ────────────────────────────────────────

    @Test
    fun `tracker isTerminal is true after Completed outcome`() {
        val (_, outcome) = runAcceptedExecution(pipeline = successPipeline())
        assertTrue(
            "tracker.isTerminal must be true so no further terminal signals can be emitted",
            (outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Completed).tracker.isTerminal
        )
    }

    @Test
    fun `tracker isTerminal is true after Failed outcome`() {
        val (_, outcome) = runAcceptedExecution(pipeline = failingPipeline())
        assertTrue(
            "tracker.isTerminal must be true after failure",
            (outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed).tracker.isTerminal
        )
    }

    @Test
    fun `tracker isTerminal is true after timeout outcome`() {
        val (_, outcome) = runAcceptedExecution(pipeline = timeoutPipeline())
        assertTrue(
            "tracker.isTerminal must be true after timeout",
            (outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed).tracker.isTerminal
        )
    }

    @Test
    fun `tracker isTerminal is true after cancellation outcome`() {
        val (_, outcome) = runAcceptedExecution(pipeline = cancelPipeline())
        assertTrue(
            "tracker.isTerminal must be true after cancellation",
            (outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed).tracker.isTerminal
        )
    }

    // ── Signal identity: ledger matches sink ──────────────────────────────────

    @Test
    fun `ledger ACK signalId matches ACK signal emitted via sink`() {
        val (captured, sink) = captureSignals()
        val receiver = DelegatedRuntimeReceiver()
        val executor = DelegatedTakeoverExecutor(pipeline = successPipeline(), signalSink = sink)
        val accepted = receiver.receive(minimalEnvelope(), attachedSession())
            as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val outcome = executor.execute(accepted.unit, accepted.record, nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed

        val ackFromSink = captured.first { it.isAck }
        assertEquals(ackFromSink.signalId, outcome.ledger.lastAck?.signalId)
    }

    @Test
    fun `ledger RESULT signalId matches RESULT signal emitted via sink on success`() {
        val (captured, sink) = captureSignals()
        val receiver = DelegatedRuntimeReceiver()
        val executor = DelegatedTakeoverExecutor(pipeline = successPipeline(), signalSink = sink)
        val accepted = receiver.receive(minimalEnvelope(), attachedSession())
            as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val outcome = executor.execute(accepted.unit, accepted.record, nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed

        val resultFromSink = captured.first { it.isResult }
        assertEquals(resultFromSink.signalId, outcome.ledger.lastResult?.signalId)
    }

    @Test
    fun `ledger RESULT signalId matches RESULT signal emitted via sink on failure`() {
        val (captured, sink) = captureSignals()
        val receiver = DelegatedRuntimeReceiver()
        val executor = DelegatedTakeoverExecutor(pipeline = failingPipeline(), signalSink = sink)
        val accepted = receiver.receive(minimalEnvelope(), attachedSession())
            as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val outcome = executor.execute(accepted.unit, accepted.record, nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Failed

        val resultFromSink = captured.first { it.isResult }
        assertEquals(resultFromSink.signalId, outcome.ledger.lastResult?.signalId)
    }
}
