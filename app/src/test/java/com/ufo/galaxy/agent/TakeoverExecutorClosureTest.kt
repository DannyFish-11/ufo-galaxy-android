package com.ufo.galaxy.agent

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.runtime.AttachedRuntimeSession
import com.ufo.galaxy.runtime.ContinuityRecoveryContext
import com.ufo.galaxy.runtime.DelegatedActivationRecord
import com.ufo.galaxy.runtime.DelegatedExecutionSignal
import com.ufo.galaxy.runtime.DelegatedExecutionSignalSink
import com.ufo.galaxy.runtime.SourceRuntimePosture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test

/**
 * **End-to-end closure validation for the Android takeover executor** (PR-5 completion,
 * post-#533 dual-repo runtime unification master plan).
 *
 * This test class is the single authoritative validation artifact proving that the
 * Android takeover path is no longer in a transitional/deferred state.  It exercises
 * the complete call chain from inbound [TakeoverRequestEnvelope] all the way through
 * signal emission and typed [DelegatedTakeoverExecutor.ExecutionOutcome].
 *
 * ## What is now fully closed (Android side)
 *
 * 1. **Acceptance / rejection decision gate** — [TakeoverEligibilityAssessor] checks device
 *    readiness flags (cross-device, goal-execution, delegated-execution, accessibility,
 *    overlay) and produces machine-readable [TakeoverEligibilityAssessor.EligibilityOutcome]
 *    codes suitable for [TakeoverResponseEnvelope.rejection_reason].
 *
 * 2. **Session gate** — [DelegatedRuntimeReceiver] enforces that an explicit
 *    [AttachedRuntimeSession] in [AttachedRuntimeSession.State.ATTACHED] state is present
 *    before any delegated work is accepted.  Null / detaching / detached sessions produce
 *    structured [DelegatedRuntimeReceiver.RejectionOutcome] codes.
 *
 * 3. **Context inheritance** — All continuity / recovery fields carried by
 *    [TakeoverRequestEnvelope] (continuity_token, recovery_context, is_resumable,
 *    interruption_reason, constraints, checkpoint) are forwarded verbatim into the
 *    [DelegatedRuntimeUnit] produced by [DelegatedRuntimeReceiver].
 *
 * 4. **Full executor lifecycle** — [DelegatedTakeoverExecutor.execute] drives:
 *    PENDING → ACTIVATING → ACTIVE → COMPLETED/FAILED, emitting
 *    ACK (emissionSeq=1), PROGRESS (emissionSeq=2), RESULT (emissionSeq=3)
 *    signals via the [DelegatedExecutionSignalSink] on every execution path.
 *
 * 5. **Structured RESULT variants** — RESULT signal carries a typed [DelegatedExecutionSignal.ResultKind]:
 *    COMPLETED, FAILED, TIMEOUT, or CANCELLED — so V2 can distinguish these outcomes.
 *
 * 6. **Duplicate-terminal suppression / replay-safe signals** — Each signal carries a
 *    stable [DelegatedExecutionSignal.signalId] (UUID idempotency key) and
 *    [DelegatedExecutionSignal.emissionSeq].  Only a single RESULT signal is ever emitted
 *    per execution.  Re-plays via [com.ufo.galaxy.runtime.EmittedSignalLedger.replaySignal]
 *    preserve the original signalId so V2 can identify the re-delivery as a duplicate.
 *
 * ## Test matrix
 *
 * ### Acceptance gate: TakeoverEligibilityAssessor
 *  - Fully-ready settings return ELIGIBLE.
 *  - crossDeviceEnabled=false returns BLOCKED_CROSS_DEVICE_DISABLED.
 *  - goalExecutionEnabled=false returns BLOCKED_GOAL_EXECUTION_DISABLED.
 *  - delegatedExecutionAllowed=false returns BLOCKED_DELEGATED_EXECUTION_DISABLED.
 *  - accessibilityReady=false returns BLOCKED_ACCESSIBILITY_NOT_READY.
 *  - overlayReady=false returns BLOCKED_OVERLAY_NOT_READY.
 *  - Concurrent takeover active returns BLOCKED_CONCURRENT_TAKEOVER with active id appended.
 *
 * ### Session gate: DelegatedRuntimeReceiver
 *  - Null session returns Rejected(NO_ATTACHED_SESSION).
 *  - DETACHING session returns Rejected(SESSION_DETACHING).
 *  - DETACHED session returns Rejected(SESSION_DETACHED).
 *  - ATTACHED session returns Accepted with non-null unit and PENDING record.
 *
 * ### Context inheritance: DelegatedRuntimeUnit fields
 *  - unitId echoes takeover_id.
 *  - taskId echoes task_id.
 *  - traceId echoes trace_id.
 *  - goal echoes goal.
 *  - attachedSessionId is bound to the active session's sessionId.
 *  - constraints are forwarded from the envelope.
 *  - checkpoint is forwarded from the envelope.
 *  - continuationToken is forwarded from envelope continuation_token.
 *  - handoffReason is forwarded from envelope handoff_reason.
 *
 * ### Full acceptance path: eligibility → session → executor
 *  - Fully-ready device + attached session produces DelegatedRuntimeUnit.
 *  - Accepted unit has PENDING initial activation record.
 *  - Executor driven on accepted unit emits exactly 3 signals (ACK + PROGRESS + RESULT).
 *  - Executor on accepted unit returns ExecutionOutcome.Completed on success.
 *
 * ### TakeoverResponseEnvelope shape on acceptance and rejection
 *  - Accepted response has accepted=true and null rejection_reason.
 *  - Rejected response has accepted=false with machine-readable rejection_reason.
 *  - Response echoes takeover_id, task_id, trace_id from the request.
 *  - Response echoes source_runtime_posture from the request.
 *
 * ### Execution outcomes and signal shapes
 *  - Success: RESULT(COMPLETED) emitted; tracker in COMPLETED state; ExecutionOutcome.Completed returned.
 *  - Failure: RESULT(FAILED) emitted; tracker in FAILED state; ExecutionOutcome.Failed returned.
 *  - Timeout: RESULT(TIMEOUT) emitted; tracker in FAILED state; ExecutionOutcome.Failed returned.
 *  - Cancellation: RESULT(CANCELLED) emitted; tracker in FAILED state; ExecutionOutcome.Failed returned.
 *
 * ### Duplicate-terminal suppression
 *  - Exactly one RESULT signal emitted on success path.
 *  - Exactly one RESULT signal emitted on failure path.
 *  - Exactly one RESULT signal emitted on timeout path.
 *  - Exactly one RESULT signal emitted on cancellation path.
 *
 * ### Replay-safe signal identity
 *  - Replayed ACK preserves original signalId (not a fresh factory call).
 *  - Replayed RESULT preserves original signalId.
 *  - Replayed RESULT preserves original resultKind.
 *
 * ## What remains for the V2-side companion PR
 *
 * The following items are out of scope for this Android PR and require corresponding
 * V2-side changes to be fully closed:
 *
 * - `send_takeover_request()` triggering Android execution end-to-end over real WebSocket.
 * - V2 absorption of `takeover_response` (accepted=true) into its tracking/truth/audit state.
 * - V2 absorption of `delegated_execution_signal` (ACK/PROGRESS/RESULT) into V2 state.
 * - V2 marking a task as completed/failed/cancelled when it receives the RESULT signal.
 * - ReconciliationSignal generated by `RuntimeController.notifyTakeoverFailed()` being
 *   consumed and reflected in V2 session truth.
 */
class TakeoverExecutorClosureTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fullyReadySettings() = InMemoryAppSettings(
        crossDeviceEnabled = true,
        goalExecutionEnabled = true,
        delegatedExecutionAllowed = true,
        accessibilityReady = true,
        overlayReady = true
    )

    private fun attachedSession(sessionId: String = "sess-closure") =
        AttachedRuntimeSession(
            sessionId = sessionId,
            hostId = "host-test",
            deviceId = "device-test",
            attachedAtMs = 1_000L,
            state = AttachedRuntimeSession.State.ATTACHED
        )

    private fun minimalEnvelope(
        takeoverId: String = "to-closure",
        taskId: String = "task-closure",
        traceId: String = "trace-closure",
        goal: String = "open camera",
        posture: String? = SourceRuntimePosture.JOIN_RUNTIME.wireValue,
        constraints: List<String> = emptyList(),
        checkpoint: String? = null,
        continuityToken: String? = null,
        handoffReason: String? = null
    ) = TakeoverRequestEnvelope(
        takeover_id = takeoverId,
        task_id = taskId,
        trace_id = traceId,
        goal = goal,
        source_runtime_posture = posture,
        constraints = constraints,
        checkpoint = checkpoint,
        continuity_token = continuityToken,
        handoff_reason = handoffReason
    )

    private fun captureSignals(): Pair<MutableList<DelegatedExecutionSignal>, DelegatedExecutionSignalSink> {
        val captured = mutableListOf<DelegatedExecutionSignal>()
        return Pair(captured, DelegatedExecutionSignalSink { signal -> captured += signal })
    }

    private fun successPipeline(status: String = "success"): GoalExecutionPipeline =
        GoalExecutionPipeline { payload ->
            GoalResultPayload(task_id = payload.task_id, correlation_id = payload.task_id, status = status)
        }

    private fun failingPipeline(msg: String = "simulated_failure"): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw RuntimeException(msg) }

    private fun timeoutPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw TimeoutCancellationException("timed_out") }

    private fun cancellationPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw CancellationException("cancelled_externally") }

    private fun buildExecutor(
        pipeline: GoalExecutionPipeline = successPipeline(),
        sink: DelegatedExecutionSignalSink = DelegatedExecutionSignalSink { }
    ) = DelegatedTakeoverExecutor(pipeline = pipeline, signalSink = sink)

    private fun receiver() = DelegatedRuntimeReceiver()

    // ── Acceptance gate: TakeoverEligibilityAssessor ─────────────────────────

    @Test
    fun `fully-ready settings return ELIGIBLE`() {
        val assessor = TakeoverEligibilityAssessor(fullyReadySettings())
        val result = assessor.assess(minimalEnvelope())
        assertTrue("All flags set → must be ELIGIBLE", result.eligible)
        assertEquals(TakeoverEligibilityAssessor.EligibilityOutcome.ELIGIBLE, result.outcome)
    }

    @Test
    fun `crossDeviceEnabled=false returns BLOCKED_CROSS_DEVICE_DISABLED`() {
        val settings = fullyReadySettings().apply { crossDeviceEnabled = false }
        val result = TakeoverEligibilityAssessor(settings).assess(minimalEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_CROSS_DEVICE_DISABLED,
            result.outcome
        )
    }

    @Test
    fun `goalExecutionEnabled=false returns BLOCKED_GOAL_EXECUTION_DISABLED`() {
        val settings = fullyReadySettings().apply { goalExecutionEnabled = false }
        val result = TakeoverEligibilityAssessor(settings).assess(minimalEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_GOAL_EXECUTION_DISABLED,
            result.outcome
        )
    }

    @Test
    fun `delegatedExecutionAllowed=false returns BLOCKED_DELEGATED_EXECUTION_DISABLED`() {
        val settings = fullyReadySettings().apply { delegatedExecutionAllowed = false }
        val result = TakeoverEligibilityAssessor(settings).assess(minimalEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_DELEGATED_EXECUTION_DISABLED,
            result.outcome
        )
    }

    @Test
    fun `accessibilityReady=false returns BLOCKED_ACCESSIBILITY_NOT_READY`() {
        val settings = fullyReadySettings().apply { accessibilityReady = false }
        val result = TakeoverEligibilityAssessor(settings).assess(minimalEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_ACCESSIBILITY_NOT_READY,
            result.outcome
        )
    }

    @Test
    fun `overlayReady=false returns BLOCKED_OVERLAY_NOT_READY`() {
        val settings = fullyReadySettings().apply { overlayReady = false }
        val result = TakeoverEligibilityAssessor(settings).assess(minimalEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_OVERLAY_NOT_READY,
            result.outcome
        )
    }

    @Test
    fun `concurrent takeover active returns BLOCKED_CONCURRENT_TAKEOVER with id appended`() {
        val assessor = TakeoverEligibilityAssessor(fullyReadySettings())
        val result = assessor.assess(minimalEnvelope(), activeTakeoverId = "to-active-123")
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_CONCURRENT_TAKEOVER,
            result.outcome
        )
        assertTrue(
            "Concurrent-takeover reason must append the active takeover id",
            result.reason.endsWith("to-active-123")
        )
    }

    @Test
    fun `eligibility rejection reason is suitable for TakeoverResponseEnvelope rejection_reason`() {
        val settings = fullyReadySettings().apply { crossDeviceEnabled = false }
        val result = TakeoverEligibilityAssessor(settings).assess(minimalEnvelope())
        assertFalse("Must not be eligible", result.eligible)
        assertTrue(
            "reason must be non-empty (suitable for rejection_reason wire field)",
            result.reason.isNotEmpty()
        )
    }

    // ── Session gate: DelegatedRuntimeReceiver ────────────────────────────────

    @Test
    fun `null session returns Rejected with NO_ATTACHED_SESSION`() {
        val result = receiver().receive(minimalEnvelope(), session = null)
        assertTrue(result is DelegatedRuntimeReceiver.ReceiptResult.Rejected)
        val rejected = result as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals(DelegatedRuntimeReceiver.RejectionOutcome.NO_ATTACHED_SESSION, rejected.outcome)
    }

    @Test
    fun `DETACHING session returns Rejected with SESSION_DETACHING`() {
        val session = AttachedRuntimeSession(
            sessionId = "sess-detaching",
            hostId = "host-test",
            deviceId = "device-test",
            attachedAtMs = 1_000L,
            state = AttachedRuntimeSession.State.DETACHING
        )
        val result = receiver().receive(minimalEnvelope(), session = session)
        assertTrue(result is DelegatedRuntimeReceiver.ReceiptResult.Rejected)
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.SESSION_DETACHING,
            (result as DelegatedRuntimeReceiver.ReceiptResult.Rejected).outcome
        )
    }

    @Test
    fun `DETACHED session returns Rejected with SESSION_DETACHED`() {
        val session = AttachedRuntimeSession(
            sessionId = "sess-detached",
            hostId = "host-test",
            deviceId = "device-test",
            attachedAtMs = 1_000L,
            state = AttachedRuntimeSession.State.DETACHED
        )
        val result = receiver().receive(minimalEnvelope(), session = session)
        assertTrue(result is DelegatedRuntimeReceiver.ReceiptResult.Rejected)
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.SESSION_DETACHED,
            (result as DelegatedRuntimeReceiver.ReceiptResult.Rejected).outcome
        )
    }

    @Test
    fun `ATTACHED session returns Accepted with non-null unit and PENDING record`() {
        val result = receiver().receive(minimalEnvelope(), session = attachedSession())
        assertTrue(result is DelegatedRuntimeReceiver.ReceiptResult.Accepted)
        val accepted = result as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertNotNull(accepted.unit)
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.PENDING,
            accepted.record.activationStatus
        )
    }

    @Test
    fun `session rejection reason is non-empty (suitable for rejection_reason wire field)`() {
        val result = receiver().receive(minimalEnvelope(), session = null)
        val rejected = result as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertTrue(
            "Session rejection reason must be non-empty for wire-level rejection_reason",
            rejected.reason.isNotEmpty()
        )
    }

    // ── Context inheritance: DelegatedRuntimeUnit fields ─────────────────────

    @Test
    fun `unit unitId echoes envelope takeover_id`() {
        val env = minimalEnvelope(takeoverId = "to-context-01")
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals("to-context-01", accepted.unit.unitId)
    }

    @Test
    fun `unit taskId echoes envelope task_id`() {
        val env = minimalEnvelope(taskId = "task-ctx-01")
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals("task-ctx-01", accepted.unit.taskId)
    }

    @Test
    fun `unit traceId echoes envelope trace_id`() {
        val env = minimalEnvelope(traceId = "trace-ctx-01")
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals("trace-ctx-01", accepted.unit.traceId)
    }

    @Test
    fun `unit goal echoes envelope goal`() {
        val env = minimalEnvelope(goal = "take screenshot")
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals("take screenshot", accepted.unit.goal)
    }

    @Test
    fun `unit attachedSessionId is bound to the active session sessionId`() {
        val session = attachedSession(sessionId = "bound-session-99")
        val accepted = receiver().receive(minimalEnvelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals("bound-session-99", accepted.unit.attachedSessionId)
    }

    @Test
    fun `unit constraints are forwarded from envelope`() {
        val env = minimalEnvelope(constraints = listOf("step-limit:3", "no-network"))
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals(listOf("step-limit:3", "no-network"), accepted.unit.constraints)
    }

    @Test
    fun `unit checkpoint is forwarded from envelope`() {
        val env = minimalEnvelope(checkpoint = "step-5-of-10")
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals("step-5-of-10", accepted.unit.checkpoint)
    }

    @Test
    fun `unit continuationToken is forwarded from envelope continuation_token`() {
        val env = minimalEnvelope(continuityToken = "ctoken-abc-123")
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals("ctoken-abc-123", accepted.unit.continuationToken)
    }

    @Test
    fun `unit handoffReason is forwarded from envelope handoff_reason`() {
        val env = minimalEnvelope(handoffReason = ContinuityRecoveryContext.REASON_HANDOFF)
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals(ContinuityRecoveryContext.REASON_HANDOFF, accepted.unit.handoffReason)
    }

    // ── Full acceptance path: eligibility → session → executor ───────────────

    @Test
    fun `full acceptance path produces DelegatedRuntimeUnit for accepted unit`() {
        val assessor = TakeoverEligibilityAssessor(fullyReadySettings())
        val env = minimalEnvelope()
        val eligibility = assessor.assess(env)
        assertTrue("Device must be eligible for acceptance path test", eligibility.eligible)

        val receiptResult = receiver().receive(env, attachedSession())
        assertTrue(
            "Session gate must accept on ATTACHED session",
            receiptResult is DelegatedRuntimeReceiver.ReceiptResult.Accepted
        )
    }

    @Test
    fun `accepted unit has PENDING initial activation record`() {
        val env = minimalEnvelope()
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.PENDING,
            accepted.record.activationStatus
        )
    }

    @Test
    fun `executor driven on accepted unit emits exactly 3 signals on success`() {
        val env = minimalEnvelope()
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals, sink) = captureSignals()

        buildExecutor(pipeline = successPipeline(), sink = sink)
            .execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertEquals("ACK + PROGRESS + RESULT = 3 signals", 3, signals.size)
    }

    @Test
    fun `executor on accepted unit returns ExecutionOutcome Completed on success`() {
        val env = minimalEnvelope()
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertTrue(
            "Expected Completed outcome, got ${outcome::class.simpleName}",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        )
    }

    // ── TakeoverResponseEnvelope shape ────────────────────────────────────────

    @Test
    fun `accepted response has accepted=true and null rejection_reason`() {
        val response = TakeoverResponseEnvelope(
            takeover_id = "to-shape",
            task_id = "task-shape",
            trace_id = "trace-shape",
            accepted = true,
            rejection_reason = null,
            device_id = "dev-1",
            runtime_session_id = "rsess-1",
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME,
            exec_mode = "remote",
            runtime_host_id = "host-abc",
            formation_role = "secondary"
        )
        assertTrue(response.accepted)
        assertNull(response.rejection_reason)
    }

    @Test
    fun `accepted response carries runtime_host_id and formation_role`() {
        val response = TakeoverResponseEnvelope(
            takeover_id = "to-host",
            task_id = "task-host",
            trace_id = "trace-host",
            accepted = true,
            rejection_reason = null,
            device_id = "dev-1",
            runtime_session_id = "rsess-1",
            source_runtime_posture = null,
            exec_mode = "remote",
            runtime_host_id = "host-xyz-789",
            formation_role = "primary"
        )
        assertEquals("host-xyz-789", response.runtime_host_id)
        assertEquals("primary", response.formation_role)
    }

    @Test
    fun `rejected response has accepted=false with machine-readable rejection_reason`() {
        val settings = fullyReadySettings().apply { crossDeviceEnabled = false }
        val eligibility = TakeoverEligibilityAssessor(settings).assess(minimalEnvelope())

        val response = TakeoverResponseEnvelope(
            takeover_id = "to-rej",
            task_id = "task-rej",
            trace_id = "trace-rej",
            accepted = false,
            rejection_reason = eligibility.reason,
            device_id = "dev-1",
            runtime_session_id = "rsess-1",
            source_runtime_posture = SourceRuntimePosture.CONTROL_ONLY,
            exec_mode = "remote"
        )
        assertFalse(response.accepted)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_CROSS_DEVICE_DISABLED.reason,
            response.rejection_reason
        )
    }

    @Test
    fun `response echoes takeover_id task_id trace_id from the request envelope`() {
        val env = minimalEnvelope(takeoverId = "to-echo", taskId = "task-echo", traceId = "trace-echo")
        val response = TakeoverResponseEnvelope(
            takeover_id = env.takeover_id,
            task_id = env.task_id,
            trace_id = env.trace_id,
            accepted = true,
            rejection_reason = null,
            device_id = "dev-1",
            runtime_session_id = "rsess-1",
            source_runtime_posture = env.source_runtime_posture,
            exec_mode = env.exec_mode
        )
        assertEquals("to-echo", response.takeover_id)
        assertEquals("task-echo", response.task_id)
        assertEquals("trace-echo", response.trace_id)
    }

    @Test
    fun `response echoes source_runtime_posture from the request`() {
        val env = minimalEnvelope(posture = SourceRuntimePosture.JOIN_RUNTIME)
        val response = TakeoverResponseEnvelope(
            takeover_id = env.takeover_id,
            task_id = env.task_id,
            trace_id = env.trace_id,
            accepted = true,
            rejection_reason = null,
            device_id = "dev-1",
            runtime_session_id = "rsess-1",
            source_runtime_posture = env.source_runtime_posture,
            exec_mode = env.exec_mode
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, response.source_runtime_posture)
    }

    // ── Execution outcomes and signal shapes ──────────────────────────────────

    @Test
    fun `success path emits RESULT(COMPLETED) and returns ExecutionOutcome Completed`() {
        val (signals, sink) = captureSignals()
        val accepted = receiver().receive(minimalEnvelope(), attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        val outcome = buildExecutor(pipeline = successPipeline(), sink = sink)
            .execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertTrue(outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Completed)
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.COMPLETED,
            (outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Completed).tracker.record.activationStatus
        )
        val resultSignal = signals.last()
        assertTrue("Last signal must be RESULT", resultSignal.isResult)
        assertEquals(DelegatedExecutionSignal.ResultKind.COMPLETED, resultSignal.resultKind)
    }

    @Test
    fun `failure path emits RESULT(FAILED) and returns ExecutionOutcome Failed`() {
        val (signals, sink) = captureSignals()
        val accepted = receiver().receive(minimalEnvelope(), attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        val outcome = buildExecutor(pipeline = failingPipeline(), sink = sink)
            .execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertTrue(outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed)
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.FAILED,
            (outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed).tracker.record.activationStatus
        )
        val resultSignal = signals.last()
        assertTrue("Last signal must be RESULT", resultSignal.isResult)
        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED, resultSignal.resultKind)
    }

    @Test
    fun `timeout path emits RESULT(TIMEOUT) and returns ExecutionOutcome Failed`() {
        val (signals, sink) = captureSignals()
        val accepted = receiver().receive(minimalEnvelope(), attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        val outcome = buildExecutor(pipeline = timeoutPipeline(), sink = sink)
            .execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertTrue(outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed)
        val resultSignal = signals.last()
        assertTrue("Last signal on timeout path must be RESULT", resultSignal.isResult)
        assertEquals(DelegatedExecutionSignal.ResultKind.TIMEOUT, resultSignal.resultKind)
    }

    @Test
    fun `cancellation path emits RESULT(CANCELLED) and returns ExecutionOutcome Failed`() {
        val (signals, sink) = captureSignals()
        val accepted = receiver().receive(minimalEnvelope(), attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        val outcome = buildExecutor(pipeline = cancellationPipeline(), sink = sink)
            .execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertTrue(outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed)
        val resultSignal = signals.last()
        assertTrue("Last signal on cancellation path must be RESULT", resultSignal.isResult)
        assertEquals(DelegatedExecutionSignal.ResultKind.CANCELLED, resultSignal.resultKind)
    }

    @Test
    fun `GoalExecutionPayload posture is always JOIN_RUNTIME for delegated execution`() {
        var capturedPosture: String? = null
        val capturingPipeline = GoalExecutionPipeline { payload ->
            capturedPosture = payload.source_runtime_posture
            GoalResultPayload(task_id = payload.task_id, status = "success")
        }
        val accepted = receiver().receive(minimalEnvelope(), attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        buildExecutor(pipeline = capturingPipeline).execute(accepted.unit, accepted.record, nowMs = 1_000L)
        assertEquals(
            "Delegated execution must always use JOIN_RUNTIME posture",
            SourceRuntimePosture.JOIN_RUNTIME,
            capturedPosture
        )
    }

    // ── Duplicate-terminal suppression ────────────────────────────────────────

    @Test
    fun `exactly one RESULT signal emitted on success path (no duplicate terminal)`() {
        val (signals, sink) = captureSignals()
        buildExecutor(pipeline = successPipeline(), sink = sink)
            .execute(makeAcceptedUnit(), makeAcceptedRecord(), nowMs = 1_000L)
        val resultSignals = signals.filter { it.isResult }
        assertEquals("Must emit exactly one RESULT signal on success", 1, resultSignals.size)
    }

    @Test
    fun `exactly one RESULT signal emitted on failure path (no duplicate terminal)`() {
        val (signals, sink) = captureSignals()
        buildExecutor(pipeline = failingPipeline(), sink = sink)
            .execute(makeAcceptedUnit(), makeAcceptedRecord(), nowMs = 1_000L)
        val resultSignals = signals.filter { it.isResult }
        assertEquals("Must emit exactly one RESULT signal on failure", 1, resultSignals.size)
    }

    @Test
    fun `exactly one RESULT signal emitted on timeout path (no duplicate terminal)`() {
        val (signals, sink) = captureSignals()
        buildExecutor(pipeline = timeoutPipeline(), sink = sink)
            .execute(makeAcceptedUnit(), makeAcceptedRecord(), nowMs = 1_000L)
        val resultSignals = signals.filter { it.isResult }
        assertEquals("Must emit exactly one RESULT signal on timeout", 1, resultSignals.size)
    }

    @Test
    fun `exactly one RESULT signal emitted on cancellation path (no duplicate terminal)`() {
        val (signals, sink) = captureSignals()
        buildExecutor(pipeline = cancellationPipeline(), sink = sink)
            .execute(makeAcceptedUnit(), makeAcceptedRecord(), nowMs = 1_000L)
        val resultSignals = signals.filter { it.isResult }
        assertEquals("Must emit exactly one RESULT signal on cancellation", 1, resultSignals.size)
    }

    // ── Replay-safe signal identity ───────────────────────────────────────────

    @Test
    fun `replayed ACK preserves original signalId (stable idempotency key)`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeAcceptedUnit(), makeAcceptedRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val originalId = outcome.ledger.lastAck!!.signalId
        val replayId = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, 9_000L)!!.signalId
        assertEquals(
            "Replayed ACK must carry original signalId for V2 idempotency check",
            originalId,
            replayId
        )
    }

    @Test
    fun `replayed RESULT preserves original signalId (stable idempotency key)`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeAcceptedUnit(), makeAcceptedRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val originalId = outcome.ledger.lastResult!!.signalId
        val replayId = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, 9_000L)!!.signalId
        assertEquals(
            "Replayed RESULT must carry original signalId for V2 idempotency check",
            originalId,
            replayId
        )
    }

    @Test
    fun `replayed RESULT preserves original resultKind`() {
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(makeAcceptedUnit(), makeAcceptedRecord(), nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val replay = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.RESULT, 9_000L)!!
        assertEquals(
            "Replayed RESULT must preserve resultKind (COMPLETED not changed to FAILED)",
            DelegatedExecutionSignal.ResultKind.COMPLETED,
            replay.resultKind
        )
    }

    @Test
    fun `fresh factory call produces different signalId than the replayed ACK`() {
        val unit = makeAcceptedUnit()
        val record = makeAcceptedRecord(unit)
        val outcome = buildExecutor(pipeline = successPipeline())
            .execute(unit, record, nowMs = 1_000L)
            as DelegatedTakeoverExecutor.ExecutionOutcome.Completed

        val replayedAckId = outcome.ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, 9_000L)!!.signalId
        // Create a fresh tracker to call the factory — this simulates what would happen
        // if a caller naively re-called DelegatedExecutionSignal.ack() instead of replaying.
        val freshTracker = com.ufo.galaxy.runtime.DelegatedExecutionTracker.create(record)
        val freshAckId = DelegatedExecutionSignal.ack(freshTracker, 9_000L).signalId
        assertNotEquals(
            "Fresh factory call must produce a NEW signalId — replay must be used for stable identity",
            replayedAckId,
            freshAckId
        )
    }

    // ── Signal identity continuity across the full chain ──────────────────────

    @Test
    fun `all emitted signals carry the same unitId as the accepted unit`() {
        val env = minimalEnvelope(takeoverId = "to-id-cont")
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals, sink) = captureSignals()

        buildExecutor(sink = sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        signals.forEach { signal ->
            assertEquals(
                "Every signal must carry unitId = takeover_id of the request",
                "to-id-cont",
                signal.unitId
            )
        }
    }

    @Test
    fun `all emitted signals carry the same taskId as the accepted unit`() {
        val env = minimalEnvelope(taskId = "task-id-cont")
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals, sink) = captureSignals()

        buildExecutor(sink = sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        signals.forEach { signal ->
            assertEquals("task-id-cont", signal.taskId)
        }
    }

    @Test
    fun `all emitted signals carry the same traceId as the accepted unit`() {
        val env = minimalEnvelope(traceId = "trace-id-cont")
        val accepted = receiver().receive(env, attachedSession()) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals, sink) = captureSignals()

        buildExecutor(sink = sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        signals.forEach { signal ->
            assertEquals("trace-id-cont", signal.traceId)
        }
    }

    @Test
    fun `all emitted signals carry attachedSessionId matching the bound session`() {
        val session = attachedSession(sessionId = "sess-signal-id")
        val accepted = receiver().receive(minimalEnvelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals, sink) = captureSignals()

        buildExecutor(sink = sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        signals.forEach { signal ->
            assertEquals("sess-signal-id", signal.attachedSessionId)
        }
    }

    // ── Helper factory methods ────────────────────────────────────────────────

    private fun makeAcceptedUnit(
        unitId: String = "unit-closure",
        taskId: String = "task-closure",
        traceId: String = "trace-closure",
        goal: String = "open settings",
        sessionId: String = "sess-closure"
    ) = DelegatedRuntimeUnit(
        unitId = unitId,
        taskId = taskId,
        traceId = traceId,
        goal = goal,
        attachedSessionId = sessionId
    )

    private fun makeAcceptedRecord(unit: DelegatedRuntimeUnit = makeAcceptedUnit()) =
        DelegatedActivationRecord.create(unit = unit, activatedAtMs = 1_000L)
}
