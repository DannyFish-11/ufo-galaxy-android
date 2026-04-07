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
 * **Canonical delegated-execution emission-path closure tests (PR-21, post-#533
 * dual-repo runtime unification master plan — Canonical Delegated Execution
 * End-to-End Reconciliation Closure, Android side).**
 *
 * Exercises the full canonical flow:
 * ```
 * DelegatedRuntimeReceiver.receive()           // receipt gate + unit/record creation
 *   ↓
 * AttachedRuntimeSession.withExecutionAccepted() // execution-count increment (PR-14)
 *   ↓
 * DelegatedTakeoverExecutor.execute()           // ACK → PROGRESS → RESULT emission (PR-12/13/15/18)
 * ```
 *
 * ## Requirements verified
 *
 * ### Count increment at the correct point (PR-14)
 *  - Count is 0 on a freshly opened session.
 *  - withExecutionAccepted() increments count to 1 without changing sessionId.
 *  - A second withExecutionAccepted() increments count to 2; sessionId still unchanged.
 *  - Count increment has no effect on the activation record produced by receive().
 *  - Count is not incremented by DelegatedTakeoverExecutor.execute().
 *  - A session that is DETACHING does not accept a count increment via receive+record path.
 *
 * ### Signal identity and sequencing continuity (PR-12/13/15/18)
 *  - All signals emitted on the success path carry attachedSessionId matching the session
 *    that was active when receive() was called.
 *  - All signals carry taskId / traceId / unitId matching the values in the envelope.
 *  - ACK is always emitted first, PROGRESS second, RESULT last.
 *  - Exactly three signals are emitted on the success path.
 *  - emissionSeq on ACK == EMISSION_SEQ_ACK (1).
 *  - emissionSeq on PROGRESS == EMISSION_SEQ_PROGRESS (2).
 *  - emissionSeq on RESULT == EMISSION_SEQ_RESULT (3).
 *  - signalId is distinct on each of the three signals within a single execution.
 *
 * ### Failure / cancellation / timeout same emission contract
 *  - Failure path emits exactly ACK + PROGRESS + RESULT(FAILED) with consistent identity.
 *  - Cancellation path emits exactly ACK + PROGRESS + RESULT(CANCELLED) with consistent identity.
 *  - Timeout path emits exactly ACK + PROGRESS + RESULT(TIMEOUT) with consistent identity.
 *  - All signals on each abnormal path carry the same attachedSessionId, taskId, traceId.
 *
 * ### No duplicate or inconsistent state progression
 *  - execute() does not emit more than three signals on any path.
 *  - A second independent execution using the same session ID emits a fresh, independent
 *    set of three signals with stable identity.
 *  - The activation record produced by the first receive() is not mutated by a second
 *    receive() under the same session.
 *  - Calling receive() on a DETACHING session returns Rejected; execute() is never called.
 *  - Calling receive() on a DETACHED session returns Rejected; execute() is never called.
 *  - Calling receive() on a null session returns Rejected; execute() is never called.
 */
class DelegatedExecutionCanonicalPathTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val receiver = DelegatedRuntimeReceiver()

    private fun attachedSession(
        sessionId: String = "sess-canonical-1",
        hostId: String = "host-a"
    ): AttachedRuntimeSession =
        AttachedRuntimeSession.create(
            hostId = hostId,
            deviceId = "device-test",
            sessionId = sessionId
        )

    private fun envelope(
        takeoverId: String = "to-canon-1",
        taskId: String = "task-canon-1",
        traceId: String = "trace-canon-1",
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

    private fun successPipeline(): GoalExecutionPipeline = GoalExecutionPipeline { payload ->
        GoalResultPayload(task_id = payload.task_id, status = "success")
    }

    private fun failingPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw RuntimeException("pipeline_error") }

    private fun timeoutPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw TimeoutCancellationException("timed_out") }

    private fun cancelPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw CancellationException("cancelled_externally") }

    private fun executor(
        pipeline: GoalExecutionPipeline,
        sink: DelegatedExecutionSignalSink
    ) = DelegatedTakeoverExecutor(pipeline = pipeline, signalSink = sink)

    // ── Count increment at the correct point ──────────────────────────────────

    @Test
    fun `fresh session has zero delegated execution count`() {
        val session = attachedSession()
        assertEquals(
            "delegatedExecutionCount must start at 0",
            0,
            session.delegatedExecutionCount
        )
    }

    @Test
    fun `withExecutionAccepted increments count to 1 without changing sessionId`() {
        val session = attachedSession(sessionId = "sess-count-1")
        val after = session.withExecutionAccepted()
        assertEquals(
            "delegatedExecutionCount must be 1 after first withExecutionAccepted()",
            1,
            after.delegatedExecutionCount
        )
        assertEquals(
            "sessionId must not change after withExecutionAccepted()",
            "sess-count-1",
            after.sessionId
        )
    }

    @Test
    fun `two withExecutionAccepted calls increment count to 2 without changing sessionId`() {
        val session = attachedSession(sessionId = "sess-count-2")
        val after = session.withExecutionAccepted().withExecutionAccepted()
        assertEquals(
            "delegatedExecutionCount must be 2 after two withExecutionAccepted() calls",
            2,
            after.delegatedExecutionCount
        )
        assertEquals(
            "sessionId must not change after two withExecutionAccepted() calls",
            "sess-count-2",
            after.sessionId
        )
    }

    @Test
    fun `count increment does not affect the activation record produced by receive`() {
        val session = attachedSession(sessionId = "sess-count-3")
        val env = envelope(taskId = "task-cnt", traceId = "trace-cnt")

        val receiptResult = receiver.receive(env, session)
        assertTrue(receiptResult is DelegatedRuntimeReceiver.ReceiptResult.Accepted)
        val record = (receiptResult as DelegatedRuntimeReceiver.ReceiptResult.Accepted).record

        // Simulate count increment (PR-14 canonical step)
        val updatedSession = session.withExecutionAccepted()

        assertEquals(
            "delegatedExecutionCount must be 1 on the updated session",
            1,
            updatedSession.delegatedExecutionCount
        )
        assertEquals(
            "activation record's attachedSessionId must still match the original session",
            "sess-count-3",
            record.attachedSessionId
        )
        assertEquals(
            "activation record must remain in PENDING status after count increment",
            DelegatedActivationRecord.ActivationStatus.PENDING,
            record.activationStatus
        )
    }

    @Test
    fun `execute does not cause a second count increment on the session`() {
        val session = attachedSession(sessionId = "sess-noincr")
        val env = envelope()
        val receiptResult = receiver.receive(env, session)
        assertTrue(receiptResult is DelegatedRuntimeReceiver.ReceiptResult.Accepted)
        val accepted = receiptResult as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        // Simulate the canonical PR-14 increment step.
        val sessionAfterAccepted = session.withExecutionAccepted()
        assertEquals(1, sessionAfterAccepted.delegatedExecutionCount)

        // Run the executor — it must not increment the session count.
        val (_, sink) = captureSignals()
        executor(successPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        // The updated session count must still be 1 (executor does not touch the session).
        assertEquals(
            "executor must not increment the session's delegatedExecutionCount",
            1,
            sessionAfterAccepted.delegatedExecutionCount
        )
    }

    // ── Rejection paths — count/execute never triggered ───────────────────────

    @Test
    fun `receive on null session returns Rejected with NO_ATTACHED_SESSION`() {
        val env = envelope()
        val result = receiver.receive(env, session = null)
        assertTrue(
            "receive on null session must return Rejected",
            result is DelegatedRuntimeReceiver.ReceiptResult.Rejected
        )
        val rejected = result as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.NO_ATTACHED_SESSION,
            rejected.outcome
        )
    }

    @Test
    fun `receive on DETACHING session returns Rejected with SESSION_DETACHING`() {
        val session = attachedSession()
        val detaching = session.beginDetaching(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)

        val result = receiver.receive(envelope(), detaching)
        assertTrue(
            "receive on DETACHING session must return Rejected",
            result is DelegatedRuntimeReceiver.ReceiptResult.Rejected
        )
        val rejected = result as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.SESSION_DETACHING,
            rejected.outcome
        )
    }

    @Test
    fun `receive on DETACHED session returns Rejected with SESSION_DETACHED`() {
        val session = attachedSession()
        val detached = session.detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)

        val result = receiver.receive(envelope(), detached)
        assertTrue(
            "receive on DETACHED session must return Rejected",
            result is DelegatedRuntimeReceiver.ReceiptResult.Rejected
        )
        val rejected = result as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.SESSION_DETACHED,
            rejected.outcome
        )
    }

    // ── Signal identity and sequencing continuity ─────────────────────────────

    @Test
    fun `all signals carry attachedSessionId matching the session used in receive`() {
        val session = attachedSession(sessionId = "sess-identity-1")
        val env = envelope(taskId = "task-id-1", traceId = "trace-id-1")

        val receiptResult = receiver.receive(env, session)
        val accepted = receiptResult as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertEquals(3, signals.size)
        assertTrue(
            "all signals must carry attachedSessionId='sess-identity-1'",
            signals.all { it.attachedSessionId == "sess-identity-1" }
        )
    }

    @Test
    fun `all signals carry taskId matching the envelope`() {
        val session = attachedSession()
        val env = envelope(taskId = "task-id-match")

        val accepted = receiver.receive(env, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertTrue(
            "all signals must carry taskId='task-id-match'",
            signals.all { it.taskId == "task-id-match" }
        )
    }

    @Test
    fun `all signals carry traceId matching the envelope`() {
        val session = attachedSession()
        val env = envelope(traceId = "trace-match-1")

        val accepted = receiver.receive(env, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertTrue(
            "all signals must carry traceId='trace-match-1'",
            signals.all { it.traceId == "trace-match-1" }
        )
    }

    @Test
    fun `signal sequence on success path is ACK then PROGRESS then RESULT`() {
        val session = attachedSession()
        val accepted = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertEquals(3, signals.size)
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
    }

    @Test
    fun `ACK signal has emissionSeq EMISSION_SEQ_ACK`() {
        val session = attachedSession()
        val accepted = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertEquals(
            "ACK emissionSeq must equal EMISSION_SEQ_ACK",
            DelegatedExecutionSignal.EMISSION_SEQ_ACK,
            signals[0].emissionSeq
        )
    }

    @Test
    fun `PROGRESS signal has emissionSeq EMISSION_SEQ_PROGRESS`() {
        val session = attachedSession()
        val accepted = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertEquals(
            "PROGRESS emissionSeq must equal EMISSION_SEQ_PROGRESS",
            DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS,
            signals[1].emissionSeq
        )
    }

    @Test
    fun `RESULT signal has emissionSeq EMISSION_SEQ_RESULT`() {
        val session = attachedSession()
        val accepted = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertEquals(
            "RESULT emissionSeq must equal EMISSION_SEQ_RESULT",
            DelegatedExecutionSignal.EMISSION_SEQ_RESULT,
            signals[2].emissionSeq
        )
    }

    @Test
    fun `signalId is distinct across ACK PROGRESS and RESULT within one execution`() {
        val session = attachedSession()
        val accepted = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        val ids = signals.map { it.signalId }
        assertEquals(
            "all three signalIds must be distinct within a single execution",
            3,
            ids.toSet().size
        )
    }

    // ── Failure path — same emission contract ──────────────────────────────────

    @Test
    fun `failure path emits exactly ACK PROGRESS RESULT FAILED with session identity`() {
        val session = attachedSession(sessionId = "sess-fail-1")
        val env = envelope(taskId = "task-fail", traceId = "trace-fail")

        val accepted = receiver.receive(env, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals, sink) = captureSignals()
        executor(failingPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertEquals("failure path must emit exactly 3 signals", 3, signals.size)
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED, signals[2].resultKind)
        assertTrue(
            "all failure signals must carry attachedSessionId='sess-fail-1'",
            signals.all { it.attachedSessionId == "sess-fail-1" }
        )
        assertTrue(
            "all failure signals must carry taskId='task-fail'",
            signals.all { it.taskId == "task-fail" }
        )
        assertTrue(
            "all failure signals must carry traceId='trace-fail'",
            signals.all { it.traceId == "trace-fail" }
        )
    }

    @Test
    fun `cancellation path emits exactly ACK PROGRESS RESULT CANCELLED with session identity`() {
        val session = attachedSession(sessionId = "sess-cancel-1")
        val env = envelope(taskId = "task-cancel", traceId = "trace-cancel")

        val accepted = receiver.receive(env, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals, sink) = captureSignals()
        executor(cancelPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertEquals("cancellation path must emit exactly 3 signals", 3, signals.size)
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
        assertEquals(DelegatedExecutionSignal.ResultKind.CANCELLED, signals[2].resultKind)
        assertTrue(
            "all cancellation signals must carry attachedSessionId='sess-cancel-1'",
            signals.all { it.attachedSessionId == "sess-cancel-1" }
        )
        assertTrue(
            "all cancellation signals must carry taskId='task-cancel'",
            signals.all { it.taskId == "task-cancel" }
        )
    }

    @Test
    fun `timeout path emits exactly ACK PROGRESS RESULT TIMEOUT with session identity`() {
        val session = attachedSession(sessionId = "sess-timeout-1")
        val env = envelope(taskId = "task-timeout", traceId = "trace-timeout")

        val accepted = receiver.receive(env, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals, sink) = captureSignals()
        executor(timeoutPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertEquals("timeout path must emit exactly 3 signals", 3, signals.size)
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
        assertEquals(DelegatedExecutionSignal.ResultKind.TIMEOUT, signals[2].resultKind)
        assertTrue(
            "all timeout signals must carry attachedSessionId='sess-timeout-1'",
            signals.all { it.attachedSessionId == "sess-timeout-1" }
        )
        assertTrue(
            "all timeout signals must carry taskId='task-timeout'",
            signals.all { it.taskId == "task-timeout" }
        )
    }

    // ── No duplicate or inconsistent state progression ────────────────────────

    @Test
    fun `two independent executions on the same session emit independent signal sets`() {
        val session = attachedSession(sessionId = "sess-double-1")

        val env1 = envelope(taskId = "task-d1", traceId = "trace-d1")
        val env2 = envelope(taskId = "task-d2", traceId = "trace-d2")

        val accepted1 = receiver.receive(env1, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val accepted2 = receiver.receive(env2, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        val (signals1, sink1) = captureSignals()
        val (signals2, sink2) = captureSignals()

        executor(successPipeline(), sink1).execute(accepted1.unit, accepted1.record, nowMs = 1_000L)
        executor(successPipeline(), sink2).execute(accepted2.unit, accepted2.record, nowMs = 2_000L)

        assertEquals("first execution must emit exactly 3 signals", 3, signals1.size)
        assertEquals("second execution must emit exactly 3 signals", 3, signals2.size)

        // Each execution carries its own taskId — they must not bleed into each other.
        assertTrue(
            "all signals in execution 1 must carry task-d1",
            signals1.all { it.taskId == "task-d1" }
        )
        assertTrue(
            "all signals in execution 2 must carry task-d2",
            signals2.all { it.taskId == "task-d2" }
        )

        // Both executions share the same session — the attachedSessionId should match.
        assertTrue(
            "all signals in execution 1 must carry sess-double-1",
            signals1.all { it.attachedSessionId == "sess-double-1" }
        )
        assertTrue(
            "all signals in execution 2 must carry sess-double-1",
            signals2.all { it.attachedSessionId == "sess-double-1" }
        )
    }

    @Test
    fun `two sequential withExecutionAccepted calls produce count 2 while sessionId is unchanged`() {
        val session = attachedSession(sessionId = "sess-multi-cnt")
        val after1 = session.withExecutionAccepted()
        val after2 = after1.withExecutionAccepted()

        assertEquals(2, after2.delegatedExecutionCount)
        assertEquals(
            "sessionId must remain stable across multiple count increments",
            "sess-multi-cnt",
            after2.sessionId
        )
    }

    @Test
    fun `activation records from two receives on the same session are independent`() {
        val session = attachedSession(sessionId = "sess-indep-1")
        val env1 = envelope(taskId = "task-i1")
        val env2 = envelope(taskId = "task-i2")

        val accepted1 = receiver.receive(env1, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val accepted2 = receiver.receive(env2, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        // Records must be independent — task IDs must not be shared.
        assertEquals("task-i1", accepted1.record.unit.taskId)
        assertEquals("task-i2", accepted2.record.unit.taskId)

        // Both must start in PENDING.
        assertEquals(DelegatedActivationRecord.ActivationStatus.PENDING, accepted1.record.activationStatus)
        assertEquals(DelegatedActivationRecord.ActivationStatus.PENDING, accepted2.record.activationStatus)

        // Transitioning one must not affect the other.
        val (_, sink1) = captureSignals()
        executor(successPipeline(), sink1).execute(accepted1.unit, accepted1.record, nowMs = 1_000L)
        assertEquals(
            "record2 must remain PENDING after record1 completes",
            DelegatedActivationRecord.ActivationStatus.PENDING,
            accepted2.record.activationStatus
        )
    }

    @Test
    fun `ExecutionOutcome on success path is Completed with COMPLETED tracker`() {
        val session = attachedSession()
        val accepted = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (_, sink) = captureSignals()
        val outcome = executor(successPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertTrue(
            "success path must return ExecutionOutcome.Completed",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        )
        val completed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        assertEquals(
            "completed tracker must be in COMPLETED status",
            DelegatedActivationRecord.ActivationStatus.COMPLETED,
            completed.tracker.record.activationStatus
        )
    }

    @Test
    fun `ExecutionOutcome on failure path is Failed with FAILED tracker`() {
        val session = attachedSession()
        val accepted = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (_, sink) = captureSignals()
        val outcome = executor(failingPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertTrue(
            "failure path must return ExecutionOutcome.Failed",
            outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        )
        val failed = outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed
        assertEquals(
            "failed tracker must be in FAILED status",
            DelegatedActivationRecord.ActivationStatus.FAILED,
            failed.tracker.record.activationStatus
        )
    }

    @Test
    fun `outcome ledger on success path contains all three signal kinds`() {
        val session = attachedSession()
        val accepted = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (_, sink) = captureSignals()
        val outcome = executor(successPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)
                as DelegatedTakeoverExecutor.ExecutionOutcome.Completed

        assertNotNull(
            "ledger must contain ACK signal",
            outcome.ledger.getForReplay(DelegatedExecutionSignal.Kind.ACK)
        )
        assertNotNull(
            "ledger must contain PROGRESS signal",
            outcome.ledger.getForReplay(DelegatedExecutionSignal.Kind.PROGRESS)
        )
        assertNotNull(
            "ledger must contain RESULT signal",
            outcome.ledger.getForReplay(DelegatedExecutionSignal.Kind.RESULT)
        )
    }

    @Test
    fun `outcome ledger on failure path contains all three signal kinds`() {
        val session = attachedSession()
        val accepted = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (_, sink) = captureSignals()
        val outcome = executor(failingPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)
                as DelegatedTakeoverExecutor.ExecutionOutcome.Failed

        assertNotNull(
            "ledger must contain ACK signal",
            outcome.ledger.getForReplay(DelegatedExecutionSignal.Kind.ACK)
        )
        assertNotNull(
            "ledger must contain PROGRESS signal",
            outcome.ledger.getForReplay(DelegatedExecutionSignal.Kind.PROGRESS)
        )
        assertNotNull(
            "ledger must contain RESULT signal",
            outcome.ledger.getForReplay(DelegatedExecutionSignal.Kind.RESULT)
        )
    }

    // ── Canonical order invariants ────────────────────────────────────────────

    @Test
    fun `unit attachedSessionId from receive equals session sessionId`() {
        val session = attachedSession(sessionId = "sess-link-1")
        val env = envelope()
        val accepted = receiver.receive(env, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        assertEquals(
            "unit.attachedSessionId must equal the session's sessionId",
            "sess-link-1",
            accepted.unit.attachedSessionId
        )
    }

    @Test
    fun `record attachedSessionId from receive equals session sessionId`() {
        val session = attachedSession(sessionId = "sess-link-2")
        val env = envelope()
        val accepted = receiver.receive(env, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        assertEquals(
            "record.attachedSessionId must equal the session's sessionId",
            "sess-link-2",
            accepted.record.attachedSessionId
        )
    }

    @Test
    fun `RESULT signal attachedSessionId matches unit from receive on success path`() {
        val session = attachedSession(sessionId = "sess-link-3")
        val accepted = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals, sink) = captureSignals()
        executor(successPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertEquals(
            "RESULT signal attachedSessionId must equal 'sess-link-3'",
            "sess-link-3",
            signals[2].attachedSessionId
        )
    }

    @Test
    fun `RESULT signal attachedSessionId matches unit from receive on timeout path`() {
        val session = attachedSession(sessionId = "sess-link-4")
        val accepted = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals, sink) = captureSignals()
        executor(timeoutPipeline(), sink).execute(accepted.unit, accepted.record, nowMs = 1_000L)

        assertEquals(
            "RESULT(TIMEOUT) signal attachedSessionId must equal 'sess-link-4'",
            "sess-link-4",
            signals[2].attachedSessionId
        )
    }
}
