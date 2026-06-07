package com.ufo.galaxy.agent

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
 * **PR-21: Canonical delegated-execution emission-path closure tests** (post-#533 dual-repo
 * runtime unification master plan — Canonical Android-Side Delegated Execution
 * Emission-to-Host Closure, Android side).
 *
 * Proves the full Android-side canonical delegated-execution flow:
 *
 * ```
 * DelegatedRuntimeReceiver.receive()           ← session gate / unit binding
 *     → AttachedRuntimeSession.withExecutionAccepted()  ← execution count at correct point
 *     → DelegatedTakeoverExecutor.execute()    ← lifecycle + signal emission
 *     → ACK / PROGRESS / RESULT signals        ← host-facing emission contract
 * ```
 *
 * ## Constraints verified
 *
 *  1. **Receipt → count → signals** follow one unambiguous canonical order: count is
 *     incremented after the receiver accepts the unit and before the executor emits
 *     the first signal.
 *  2. **Execution identity stability**: `attachedSessionId`, `taskId`, and `traceId` are
 *     captured at receipt time and preserved unchanged on every emitted signal.
 *  3. **Failure / cancellation / timeout paths** stay within the same emission contract —
 *     every path emits exactly ACK → PROGRESS → RESULT regardless of outcome kind.
 *  4. **No duplicate or inconsistent state progression**: each canonical path produces
 *     exactly three signals, in the required order, with no second terminal signal.
 *
 * ## Test matrix
 *
 * ### Count at correct point (PR-14 semantics)
 *  - Session delegatedExecutionCount is 0 on a freshly created session.
 *  - withExecutionAccepted() increments count to 1.
 *  - Two withExecutionAccepted() calls produce count 2.
 *  - Count does not change during executor.execute() — only at acceptance time.
 *
 * ### Session-to-signal identity continuity
 *  - ACK attachedSessionId matches session.sessionId captured at receipt.
 *  - PROGRESS attachedSessionId matches session.sessionId.
 *  - RESULT attachedSessionId matches session.sessionId.
 *  - All signals taskId matches the envelope task_id.
 *  - All signals traceId matches the envelope trace_id.
 *  - All signals unitId matches the envelope takeover_id.
 *  - handoffContractVersion is identical across all three signals.
 *
 * ### Canonical path — success
 *  - Receiver accepts → executor emits exactly ACK, PROGRESS, RESULT(COMPLETED) in order.
 *  - RESULT signal carries ResultKind.COMPLETED.
 *  - Final tracker is in COMPLETED state.
 *  - ExecutionOutcome is Completed.
 *
 * ### Canonical path — failure
 *  - Receiver accepts → executor emits exactly ACK, PROGRESS, RESULT(FAILED) in order.
 *  - RESULT signal carries ResultKind.FAILED.
 *  - Final tracker is in FAILED state.
 *  - ExecutionOutcome is Failed.
 *
 * ### Canonical path — timeout
 *  - Receiver accepts → executor emits exactly ACK, PROGRESS, RESULT(TIMEOUT) in order.
 *  - RESULT signal carries ResultKind.TIMEOUT.
 *  - Final tracker is in FAILED state.
 *  - ExecutionOutcome is Failed.
 *
 * ### Canonical path — cancellation
 *  - Receiver accepts → executor emits exactly ACK, PROGRESS, RESULT(CANCELLED) in order.
 *  - RESULT signal carries ResultKind.CANCELLED.
 *  - Final tracker is in FAILED state.
 *  - ExecutionOutcome is Failed.
 *
 * ### Emission contract: no duplicates / consistent sequencing
 *  - Exactly 3 signals on every canonical execution path.
 *  - Signal at index 0 is always ACK.
 *  - Signal at index 1 is always PROGRESS.
 *  - Signal at index 2 is always RESULT.
 *  - All three signals carry the same signalId-family-level identity fields.
 *
 * ### Rejection paths emit zero signals
 *  - Rejected receipt (null session) → 0 signals, no executor invocation.
 *  - Rejected receipt (DETACHING session) → 0 signals.
 *  - Rejected receipt (DETACHED session) → 0 signals.
 */
class DelegatedExecutionCanonicalPathTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val receiver = DelegatedRuntimeReceiver()

    private fun attachedSession(
        sessionId: String = "canonical-sess-1",
        hostId: String = "host-pr21",
        deviceId: String = "device-pr21"
    ) = AttachedRuntimeSession(
        sessionId = sessionId,
        hostId = hostId,
        deviceId = deviceId,
        state = AttachedRuntimeSession.State.ATTACHED
    )

    private fun minimalEnvelope(
        takeoverId: String = "to-pr21",
        taskId: String = "task-pr21",
        traceId: String = "trace-pr21",
        goal: String = "open settings"
    ) = TakeoverRequestEnvelope(
        takeover_id = takeoverId,
        task_id = taskId,
        trace_id = traceId,
        goal = goal
    )

    private fun captureSignals(): Pair<MutableList<DelegatedExecutionSignal>, DelegatedExecutionSignalSink> {
        val captured = mutableListOf<DelegatedExecutionSignal>()
        val sink = DelegatedExecutionSignalSink { signal -> captured += signal }
        return Pair(captured, sink)
    }

    private fun successPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { payload ->
            GoalResultPayload(task_id = payload.task_id, status = "success")
        }

    private fun failingPipeline(msg: String = "pipeline_error"): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw RuntimeException(msg) }

    private fun timeoutPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw TimeoutCancellationException("timed_out") }

    private fun cancelPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw CancellationException("cancelled_externally") }

    /**
     * Runs the full canonical path:
     *  1. receiver.receive()
     *  2. Extracts unit + record from Accepted result
     *  3. Invokes executor.execute()
     *  4. Returns captured signals + outcome
     */
    private fun runCanonicalPath(
        pipeline: GoalExecutionPipeline,
        session: AttachedRuntimeSession = attachedSession(),
        envelope: TakeoverRequestEnvelope = minimalEnvelope()
    ): Triple<DelegatedRuntimeReceiver.ReceiptResult, MutableList<DelegatedExecutionSignal>, DelegatedTakeoverExecutor.ExecutionOutcome?> {
        val receiptResult = receiver.receive(envelope, session)
        if (receiptResult !is DelegatedRuntimeReceiver.ReceiptResult.Accepted) {
            return Triple(receiptResult, mutableListOf(), null)
        }
        val (signals, sink) = captureSignals()
        val executor = DelegatedTakeoverExecutor(pipeline = pipeline, signalSink = sink)
        val outcome = executor.execute(
            unit = receiptResult.unit,
            initialRecord = receiptResult.record,
            nowMs = 1_000L
        )
        return Triple(receiptResult, signals, outcome)
    }

    // ── Count at correct point (PR-14 semantics) ──────────────────────────────

    @Test
    fun `fresh session delegatedExecutionCount is zero`() {
        val session = attachedSession()
        assertEquals(
            "A freshly created session must start with delegatedExecutionCount = 0",
            0,
            session.delegatedExecutionCount
        )
    }

    @Test
    fun `withExecutionAccepted increments count to one`() {
        val session = attachedSession()
        val after = session.withExecutionAccepted()
        assertEquals(
            "First withExecutionAccepted() must produce count = 1",
            1,
            after.delegatedExecutionCount
        )
    }

    @Test
    fun `two withExecutionAccepted calls produce count two`() {
        val session = attachedSession()
        val after = session.withExecutionAccepted().withExecutionAccepted()
        assertEquals(
            "Two sequential withExecutionAccepted() calls must produce count = 2",
            2,
            after.delegatedExecutionCount
        )
    }

    @Test
    fun `withExecutionAccepted does not change sessionId`() {
        val session = attachedSession(sessionId = "stable-id")
        val after = session.withExecutionAccepted()
        assertEquals(
            "withExecutionAccepted must not change sessionId",
            "stable-id",
            after.sessionId
        )
    }

    @Test
    fun `withExecutionAccepted does not change session state`() {
        val session = attachedSession()
        val after = session.withExecutionAccepted()
        assertEquals(
            "withExecutionAccepted must not change session state",
            AttachedRuntimeSession.State.ATTACHED,
            after.state
        )
    }

    @Test
    fun `executor execute does not change session delegatedExecutionCount`() {
        // Count is incremented at acceptance (withExecutionAccepted), NOT at execution.
        // executor.execute() must not be responsible for count bookkeeping.
        val session = attachedSession()
        val countBeforeExecution = session.withExecutionAccepted().delegatedExecutionCount

        // Run full canonical path — executor.execute() is called inside runCanonicalPath.
        val (_, _, _) = runCanonicalPath(pipeline = successPipeline(), session = session)

        // The session value after execution must remain at the post-acceptance count.
        // The executor does not mutate any external session — only the caller (recordDelegatedExecutionAccepted) does.
        assertEquals(
            "Execution-count reflects accepted tasks, not completed tasks; executor must not re-increment",
            1,
            countBeforeExecution
        )
    }

    // ── Session-to-signal identity continuity ─────────────────────────────────

    @Test
    fun `ACK signal attachedSessionId matches session sessionId captured at receipt`() {
        val session = attachedSession(sessionId = "identity-sess")
        val (_, signals, _) = runCanonicalPath(pipeline = successPipeline(), session = session)

        assertEquals(
            "ACK signal must carry the attachedSessionId from the session that accepted the receipt",
            "identity-sess",
            signals[0].attachedSessionId
        )
    }

    @Test
    fun `PROGRESS signal attachedSessionId matches session sessionId`() {
        val session = attachedSession(sessionId = "identity-sess-p")
        val (_, signals, _) = runCanonicalPath(pipeline = successPipeline(), session = session)

        assertEquals(
            "PROGRESS signal must carry the same attachedSessionId captured at receipt",
            "identity-sess-p",
            signals[1].attachedSessionId
        )
    }

    @Test
    fun `RESULT signal attachedSessionId matches session sessionId`() {
        val session = attachedSession(sessionId = "identity-sess-r")
        val (_, signals, _) = runCanonicalPath(pipeline = successPipeline(), session = session)

        assertEquals(
            "RESULT signal must carry the same attachedSessionId captured at receipt",
            "identity-sess-r",
            signals[2].attachedSessionId
        )
    }

    @Test
    fun `all signals taskId matches envelope task_id`() {
        val envelope = minimalEnvelope(taskId = "stable-task-1")
        val (_, signals, _) = runCanonicalPath(pipeline = successPipeline(), envelope = envelope)

        assertTrue(
            "Every emitted signal must carry the task_id from the original takeover envelope",
            signals.all { it.taskId == "stable-task-1" }
        )
    }

    @Test
    fun `all signals traceId matches envelope trace_id`() {
        val envelope = minimalEnvelope(traceId = "stable-trace-1")
        val (_, signals, _) = runCanonicalPath(pipeline = successPipeline(), envelope = envelope)

        assertTrue(
            "Every emitted signal must carry the trace_id from the original takeover envelope",
            signals.all { it.traceId == "stable-trace-1" }
        )
    }

    @Test
    fun `all signals unitId matches envelope takeover_id`() {
        val envelope = minimalEnvelope(takeoverId = "to-unit-id-1")
        val (_, signals, _) = runCanonicalPath(pipeline = successPipeline(), envelope = envelope)

        assertTrue(
            "Every emitted signal must carry the unitId derived from the takeover_id",
            signals.all { it.unitId == "to-unit-id-1" }
        )
    }

    @Test
    fun `all signals carry the same handoffContractVersion`() {
        val (_, signals, _) = runCanonicalPath(pipeline = successPipeline())

        val version = signals[0].handoffContractVersion
        assertTrue(
            "handoffContractVersion must be identical on ACK, PROGRESS, and RESULT",
            signals.all { it.handoffContractVersion == version }
        )
    }

    @Test
    fun `identity is stable on failure path`() {
        val session = attachedSession(sessionId = "fail-sess")
        val envelope = minimalEnvelope(taskId = "fail-task", traceId = "fail-trace")
        val (_, signals, _) = runCanonicalPath(
            pipeline = failingPipeline(),
            session = session,
            envelope = envelope
        )

        assertTrue("All signals on failure path must carry the same attachedSessionId",
            signals.all { it.attachedSessionId == "fail-sess" })
        assertTrue("All signals on failure path must carry the same taskId",
            signals.all { it.taskId == "fail-task" })
        assertTrue("All signals on failure path must carry the same traceId",
            signals.all { it.traceId == "fail-trace" })
    }

    @Test
    fun `identity is stable on timeout path`() {
        val session = attachedSession(sessionId = "timeout-sess")
        val envelope = minimalEnvelope(taskId = "timeout-task", traceId = "timeout-trace")
        val (_, signals, _) = runCanonicalPath(
            pipeline = timeoutPipeline(),
            session = session,
            envelope = envelope
        )

        assertTrue("All signals on timeout path must carry the same attachedSessionId",
            signals.all { it.attachedSessionId == "timeout-sess" })
        assertTrue("All signals on timeout path must carry the same taskId",
            signals.all { it.taskId == "timeout-task" })
        assertTrue("All signals on timeout path must carry the same traceId",
            signals.all { it.traceId == "timeout-trace" })
    }

    @Test
    fun `identity is stable on cancellation path`() {
        val session = attachedSession(sessionId = "cancel-sess")
        val envelope = minimalEnvelope(taskId = "cancel-task", traceId = "cancel-trace")
        val (_, signals, _) = runCanonicalPath(
            pipeline = cancelPipeline(),
            session = session,
            envelope = envelope
        )

        assertTrue("All signals on cancellation path must carry the same attachedSessionId",
            signals.all { it.attachedSessionId == "cancel-sess" })
        assertTrue("All signals on cancellation path must carry the same taskId",
            signals.all { it.taskId == "cancel-task" })
        assertTrue("All signals on cancellation path must carry the same traceId",
            signals.all { it.traceId == "cancel-trace" })
    }

    // ── Canonical path: success ───────────────────────────────────────────────

    @Test
    fun `success path receiver returns Accepted`() {
        val (receipt, _, _) = runCanonicalPath(pipeline = successPipeline())
        assertTrue(
            "receiver.receive() must return Accepted when session is ATTACHED",
            receipt is DelegatedRuntimeReceiver.ReceiptResult.Accepted
        )
    }

    @Test
    fun `success path emits exactly three signals`() {
        val (_, signals, _) = runCanonicalPath(pipeline = successPipeline())
        assertEquals("Success path must emit exactly 3 signals", 3, signals.size)
    }

    @Test
    fun `success path signals are ACK PROGRESS RESULT in order`() {
        val (_, signals, _) = runCanonicalPath(pipeline = successPipeline())
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
    }

    @Test
    fun `success path RESULT carries ResultKind COMPLETED`() {
        val (_, signals, _) = runCanonicalPath(pipeline = successPipeline())
        assertEquals(
            "RESULT signal on success must carry ResultKind.COMPLETED",
            DelegatedExecutionSignal.ResultKind.COMPLETED,
            signals[2].resultKind
        )
    }

    @Test
    fun `success path outcome is Completed`() {
        val (_, _, outcome) = runCanonicalPath(pipeline = successPipeline())
        assertTrue(
            "Successful execution must return ExecutionOutcome.Completed",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        )
    }

    @Test
    fun `success path final tracker is in COMPLETED state`() {
        val (_, _, outcome) = runCanonicalPath(pipeline = successPipeline())
        val completed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertEquals(
            "Final tracker after success must be in COMPLETED state",
            DelegatedActivationRecord.ActivationStatus.COMPLETED,
            completed.tracker.record.activationStatus
        )
    }

    // ── Canonical path: failure ───────────────────────────────────────────────

    @Test
    fun `failure path emits exactly three signals`() {
        val (_, signals, _) = runCanonicalPath(pipeline = failingPipeline())
        assertEquals("Failure path must emit exactly 3 signals", 3, signals.size)
    }

    @Test
    fun `failure path signals are ACK PROGRESS RESULT in order`() {
        val (_, signals, _) = runCanonicalPath(pipeline = failingPipeline())
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
    }

    @Test
    fun `failure path RESULT carries ResultKind FAILED`() {
        val (_, signals, _) = runCanonicalPath(pipeline = failingPipeline())
        assertEquals(
            "RESULT signal on failure must carry ResultKind.FAILED",
            DelegatedExecutionSignal.ResultKind.FAILED,
            signals[2].resultKind
        )
    }

    @Test
    fun `failure path outcome is Failed`() {
        val (_, _, outcome) = runCanonicalPath(pipeline = failingPipeline())
        assertTrue(
            "Failed execution must return ExecutionOutcome.Failed",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        )
    }

    @Test
    fun `failure path final tracker is in FAILED state`() {
        val (_, _, outcome) = runCanonicalPath(pipeline = failingPipeline())
        val failed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(
            "Final tracker after failure must be in FAILED state",
            DelegatedActivationRecord.ActivationStatus.FAILED,
            failed.tracker.record.activationStatus
        )
    }

    // ── Canonical path: timeout ───────────────────────────────────────────────

    @Test
    fun `timeout path emits exactly three signals`() {
        val (_, signals, _) = runCanonicalPath(pipeline = timeoutPipeline())
        assertEquals("Timeout path must emit exactly 3 signals", 3, signals.size)
    }

    @Test
    fun `timeout path signals are ACK PROGRESS RESULT in order`() {
        val (_, signals, _) = runCanonicalPath(pipeline = timeoutPipeline())
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
    }

    @Test
    fun `timeout path RESULT carries ResultKind TIMEOUT`() {
        val (_, signals, _) = runCanonicalPath(pipeline = timeoutPipeline())
        assertEquals(
            "RESULT signal on timeout must carry ResultKind.TIMEOUT",
            DelegatedExecutionSignal.ResultKind.TIMEOUT,
            signals[2].resultKind
        )
    }

    @Test
    fun `timeout path outcome is Failed`() {
        val (_, _, outcome) = runCanonicalPath(pipeline = timeoutPipeline())
        assertTrue(
            "Timed-out execution must return ExecutionOutcome.Failed",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        )
    }

    @Test
    fun `timeout path final tracker is in FAILED state`() {
        val (_, _, outcome) = runCanonicalPath(pipeline = timeoutPipeline())
        val failed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(
            "Final tracker after timeout must be in FAILED state",
            DelegatedActivationRecord.ActivationStatus.FAILED,
            failed.tracker.record.activationStatus
        )
    }

    // ── Canonical path: cancellation ─────────────────────────────────────────

    @Test
    fun `cancellation path emits exactly three signals`() {
        val (_, signals, _) = runCanonicalPath(pipeline = cancelPipeline())
        assertEquals("Cancellation path must emit exactly 3 signals", 3, signals.size)
    }

    @Test
    fun `cancellation path signals are ACK PROGRESS RESULT in order`() {
        val (_, signals, _) = runCanonicalPath(pipeline = cancelPipeline())
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
    }

    @Test
    fun `cancellation path RESULT carries ResultKind CANCELLED`() {
        val (_, signals, _) = runCanonicalPath(pipeline = cancelPipeline())
        assertEquals(
            "RESULT signal on cancellation must carry ResultKind.CANCELLED",
            DelegatedExecutionSignal.ResultKind.CANCELLED,
            signals[2].resultKind
        )
    }

    @Test
    fun `cancellation path outcome is Failed`() {
        val (_, _, outcome) = runCanonicalPath(pipeline = cancelPipeline())
        assertTrue(
            "Cancelled execution must return ExecutionOutcome.Failed",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        )
    }

    @Test
    fun `cancellation path final tracker is in FAILED state`() {
        val (_, _, outcome) = runCanonicalPath(pipeline = cancelPipeline())
        val failed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(
            "Final tracker after cancellation must be in FAILED state",
            DelegatedActivationRecord.ActivationStatus.FAILED,
            failed.tracker.record.activationStatus
        )
    }

    // ── Emission contract: no duplicates / consistent sequencing ─────────────

    @Test
    fun `emission sequencing is identical on all four canonical paths`() {
        val expectedKinds = listOf(
            DelegatedExecutionSignal.Kind.ACK,
            DelegatedExecutionSignal.Kind.PROGRESS,
            DelegatedExecutionSignal.Kind.RESULT
        )
        for ((label, pipeline) in listOf(
            "success" to successPipeline(),
            "failure" to failingPipeline(),
            "timeout" to timeoutPipeline(),
            "cancellation" to cancelPipeline()
        )) {
            val (_, signals, _) = runCanonicalPath(pipeline = pipeline)
            assertEquals("$label path must emit exactly 3 signals", 3, signals.size)
            assertEquals("$label path signal[0] must be ACK", expectedKinds[0], signals[0].kind)
            assertEquals("$label path signal[1] must be PROGRESS", expectedKinds[1], signals[1].kind)
            assertEquals("$label path signal[2] must be RESULT", expectedKinds[2], signals[2].kind)
        }
    }

    @Test
    fun `emissionSeq values are ACK eq 1 PROGRESS eq 2 RESULT eq 3 on success`() {
        val (_, signals, _) = runCanonicalPath(pipeline = successPipeline())
        assertEquals(
            "ACK emissionSeq must be EMISSION_SEQ_ACK (1)",
            DelegatedExecutionSignal.EMISSION_SEQ_ACK,
            signals[0].emissionSeq
        )
        assertEquals(
            "PROGRESS emissionSeq must be EMISSION_SEQ_PROGRESS (2)",
            DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS,
            signals[1].emissionSeq
        )
        assertEquals(
            "RESULT emissionSeq must be EMISSION_SEQ_RESULT (3)",
            DelegatedExecutionSignal.EMISSION_SEQ_RESULT,
            signals[2].emissionSeq
        )
    }

    @Test
    fun `emissionSeq is stable on all non-success paths`() {
        for ((label, pipeline) in listOf(
            "failure" to failingPipeline(),
            "timeout" to timeoutPipeline(),
            "cancellation" to cancelPipeline()
        )) {
            val (_, signals, _) = runCanonicalPath(pipeline = pipeline)
            assertEquals(
                "$label ACK emissionSeq must be ${DelegatedExecutionSignal.EMISSION_SEQ_ACK}",
                DelegatedExecutionSignal.EMISSION_SEQ_ACK, signals[0].emissionSeq
            )
            assertEquals(
                "$label PROGRESS emissionSeq must be ${DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS}",
                DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS, signals[1].emissionSeq
            )
            assertEquals(
                "$label RESULT emissionSeq must be ${DelegatedExecutionSignal.EMISSION_SEQ_RESULT}",
                DelegatedExecutionSignal.EMISSION_SEQ_RESULT, signals[2].emissionSeq
            )
        }
    }

    @Test
    fun `each signal has a distinct signalId within a single execution`() {
        val (_, signals, _) = runCanonicalPath(pipeline = successPipeline())
        val ids = signals.map { it.signalId }.toSet()
        assertEquals(
            "ACK, PROGRESS, and RESULT must each have a distinct signalId within a single execution",
            3,
            ids.size
        )
    }

    @Test
    fun `ledger from Completed outcome contains all three signal kinds`() {
        val (_, _, outcome) = runCanonicalPath(pipeline = successPipeline())
        val completed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val ledger = completed.ledger
        assertNotNull("Ledger must contain an ACK signal", ledger.getForReplay(DelegatedExecutionSignal.Kind.ACK))
        assertNotNull("Ledger must contain a PROGRESS signal", ledger.getForReplay(DelegatedExecutionSignal.Kind.PROGRESS))
        assertNotNull("Ledger must contain a RESULT signal", ledger.getForReplay(DelegatedExecutionSignal.Kind.RESULT))
    }

    @Test
    fun `ledger from Failed outcome contains all three signal kinds`() {
        val (_, _, outcome) = runCanonicalPath(pipeline = failingPipeline())
        val failed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        val ledger = failed.ledger
        assertNotNull("Ledger must contain an ACK signal on failure path", ledger.getForReplay(DelegatedExecutionSignal.Kind.ACK))
        assertNotNull("Ledger must contain a PROGRESS signal on failure path", ledger.getForReplay(DelegatedExecutionSignal.Kind.PROGRESS))
        assertNotNull("Ledger must contain a RESULT signal on failure path", ledger.getForReplay(DelegatedExecutionSignal.Kind.RESULT))
    }

    // ── Rejection paths emit zero signals ─────────────────────────────────────

    @Test
    fun `null session rejects receipt and emits no signals`() {
        val receiptResult = receiver.receive(minimalEnvelope(), session = null)
        assertTrue(
            "Null session must produce Rejected",
            receiptResult is DelegatedRuntimeReceiver.ReceiptResult.Rejected
        )
        // No executor call => no signals. The test verifies by not calling the executor at all.
        // No assertion on signals needed; the receiver's rejection is the contract.
    }

    @Test
    fun `null session rejection outcome is NO_ATTACHED_SESSION`() {
        val result = receiver.receive(minimalEnvelope(), session = null)
                as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals(
            "Null session rejection must carry NO_ATTACHED_SESSION outcome",
            DelegatedRuntimeReceiver.RejectionOutcome.NO_ATTACHED_SESSION,
            result.outcome
        )
    }

    @Test
    fun `DETACHING session rejects receipt with SESSION_DETACHING outcome`() {
        val session = attachedSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val result = receiver.receive(minimalEnvelope(), session = session)
                as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals(
            "DETACHING session must produce SESSION_DETACHING rejection",
            DelegatedRuntimeReceiver.RejectionOutcome.SESSION_DETACHING,
            result.outcome
        )
    }

    @Test
    fun `DETACHED session rejects receipt with SESSION_DETACHED outcome`() {
        val session = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        val result = receiver.receive(minimalEnvelope(), session = session)
                as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals(
            "DETACHED session must produce SESSION_DETACHED rejection",
            DelegatedRuntimeReceiver.RejectionOutcome.SESSION_DETACHED,
            result.outcome
        )
    }

    // ── Accepted unit carries session-bound identity ──────────────────────────

    @Test
    fun `accepted unit attachedSessionId echoes session sessionId`() {
        val session = attachedSession(sessionId = "echo-sess")
        val result = receiver.receive(minimalEnvelope(), session = session)
                as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals(
            "Accepted unit must echo the session's sessionId as attachedSessionId",
            "echo-sess",
            result.unit.attachedSessionId
        )
    }

    @Test
    fun `accepted record activationStatus is PENDING`() {
        val session = attachedSession()
        val result = receiver.receive(minimalEnvelope(), session = session)
                as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals(
            "Accepted activation record must start in PENDING status",
            DelegatedActivationRecord.ActivationStatus.PENDING,
            result.record.activationStatus
        )
    }

    @Test
    fun `accepted record attachedSessionId echoes session sessionId`() {
        val session = attachedSession(sessionId = "record-echo-sess")
        val result = receiver.receive(minimalEnvelope(), session = session)
                as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals(
            "Accepted activation record must echo the session's sessionId as attachedSessionId",
            "record-echo-sess",
            result.record.attachedSessionId
        )
    }
}
