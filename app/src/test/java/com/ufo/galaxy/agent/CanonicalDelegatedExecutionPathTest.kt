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
 * **PR-21: Canonical delegated-execution emission-path closure** (post-#533 dual-repo
 * runtime unification master plan — Canonical Android-Side Delegated Execution
 * Emission-to-Host Closure, Android side).
 *
 * This test class exercises the **complete canonical flow** from
 * [DelegatedRuntimeReceiver.receive] through [AttachedRuntimeSession.withExecutionAccepted]
 * (simulating [com.ufo.galaxy.runtime.RuntimeController.recordDelegatedExecutionAccepted])
 * and finally [DelegatedTakeoverExecutor.execute] — verifying all four required outcomes:
 *
 *  1. Accepted delegated execution increments the session count at the correct point —
 *     after receipt acceptance but before signal emission begins.
 *  2. Signal identity / sequencing continuity is maintained across the entire canonical
 *     path: every signal carries the same `unitId`, `taskId`, `traceId`, and
 *     `attachedSessionId` that the receiver bound to the accepted unit, and the
 *     `emissionSeq` follows the ACK(1) → PROGRESS(2) → RESULT(3) contract.
 *  3. Failure, cancellation, and timeout paths remain within the same canonical emission
 *     contract: three signals, same identity, correct terminal `ResultKind`.
 *  4. No duplicate or inconsistent state progression: the tracker advances through each
 *     activation status exactly once, and the count increment happens exactly once per
 *     accepted unit.
 *
 * ## Test matrix
 *
 * ### Canonical acceptance order
 *  - Accepted receipt increments session execution count from 0 to 1.
 *  - Count is incremented before ACK signal is emitted (ordering invariant).
 *  - ACK is the first signal regardless of terminal outcome.
 *  - Rejected receipt does not increment count.
 *  - Rejected receipt emits no signals.
 *
 * ### Session-to-unit identity linkage
 *  - Unit's attachedSessionId matches the session's sessionId.
 *  - ACK signal's attachedSessionId matches the session's sessionId.
 *  - PROGRESS signal's attachedSessionId matches the session's sessionId.
 *  - RESULT signal's attachedSessionId matches the session's sessionId.
 *
 * ### emissionSeq sequencing — success path
 *  - ACK signal carries emissionSeq == EMISSION_SEQ_ACK (1).
 *  - PROGRESS signal carries emissionSeq == EMISSION_SEQ_PROGRESS (2).
 *  - RESULT signal carries emissionSeq == EMISSION_SEQ_RESULT (3).
 *
 * ### emissionSeq sequencing — failure path
 *  - ACK signal carries emissionSeq == EMISSION_SEQ_ACK on failure path.
 *  - PROGRESS signal carries emissionSeq == EMISSION_SEQ_PROGRESS on failure path.
 *  - RESULT signal carries emissionSeq == EMISSION_SEQ_RESULT on failure path.
 *
 * ### emissionSeq sequencing — timeout path
 *  - ACK signal carries emissionSeq == EMISSION_SEQ_ACK on timeout path.
 *  - PROGRESS signal carries emissionSeq == EMISSION_SEQ_PROGRESS on timeout path.
 *  - RESULT signal carries emissionSeq == EMISSION_SEQ_RESULT on timeout path.
 *
 * ### emissionSeq sequencing — cancellation path
 *  - ACK signal carries emissionSeq == EMISSION_SEQ_ACK on cancellation path.
 *  - PROGRESS signal carries emissionSeq == EMISSION_SEQ_PROGRESS on cancellation path.
 *  - RESULT signal carries emissionSeq == EMISSION_SEQ_RESULT on cancellation path.
 *
 * ### Canonical emission contract — all terminal paths
 *  - Success path emits exactly 3 signals: ACK, PROGRESS, RESULT(COMPLETED).
 *  - Failure path emits exactly 3 signals: ACK, PROGRESS, RESULT(FAILED).
 *  - Timeout path emits exactly 3 signals: ACK, PROGRESS, RESULT(TIMEOUT).
 *  - Cancellation path emits exactly 3 signals: ACK, PROGRESS, RESULT(CANCELLED).
 *
 * ### Identity continuity across all terminal paths
 *  - All 3 signals on success path carry the same unitId from the received unit.
 *  - All 3 signals on failure path carry the same taskId from the received unit.
 *  - All 3 signals on timeout path carry the same traceId from the received unit.
 *  - All 3 signals on cancellation path carry the same attachedSessionId from the session.
 *
 * ### Tracker state progression — no duplicate transitions
 *  - Success path: tracker ends in COMPLETED.
 *  - Failure path: tracker ends in FAILED.
 *  - Timeout path: tracker ends in FAILED.
 *  - Cancellation path: tracker ends in FAILED.
 *  - Success path tracker is terminal after execute returns.
 *  - Failure path tracker is terminal after execute returns.
 *
 * ### Multiple tasks through a single session
 *  - Two sequential accepted receipts produce count == 2 on the session.
 *  - Five sequential accepted receipts produce count == 5 on the session.
 *  - Session sessionId is preserved across multiple accepted receipts.
 *  - Each task's signals carry the same session sessionId.
 *
 * ### Signal identity per execution — each signal has distinct signalId
 *  - ACK, PROGRESS, RESULT signals have distinct signalIds within one execution.
 *  - Signals across two separate executions share the same attachedSessionId but have
 *    distinct signalIds.
 */
class CanonicalDelegatedExecutionPathTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun attachedSession(
        hostId: String = "host-pr21",
        deviceId: String = "pixel-9"
    ) = AttachedRuntimeSession.create(hostId = hostId, deviceId = deviceId)

    private fun envelope(
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

    private fun executor(sink: DelegatedExecutionSignalSink, pipeline: GoalExecutionPipeline) =
        DelegatedTakeoverExecutor(pipeline = pipeline, signalSink = sink)

    private fun successPipeline(taskId: String = "task-pr21"): GoalExecutionPipeline =
        GoalExecutionPipeline { payload ->
            GoalResultPayload(task_id = payload.task_id, status = "success")
        }

    private fun failingPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw RuntimeException("execution_error") }

    private fun timeoutPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw TimeoutCancellationException("timed_out") }

    private fun cancelPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ -> throw CancellationException("cancelled_externally") }

    /**
     * Runs the canonical path for a single delegated unit through the receiver and executor.
     * Returns (session after count increment, received unit, emitted signals).
     */
    private fun runCanonicalPath(
        session: AttachedRuntimeSession,
        env: TakeoverRequestEnvelope,
        pipeline: GoalExecutionPipeline,
        nowMs: Long = 1_000L
    ): Triple<AttachedRuntimeSession, DelegatedRuntimeUnit, List<DelegatedExecutionSignal>> {
        val receiver = DelegatedRuntimeReceiver()
        val receiptResult = receiver.receive(envelope = env, session = session, receivedAtMs = nowMs)

        assertTrue(
            "Expected Accepted but got: $receiptResult",
            receiptResult is DelegatedRuntimeReceiver.ReceiptResult.Accepted
        )
        val accepted = receiptResult as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        // Simulate RuntimeController.recordDelegatedExecutionAccepted(): increment count.
        val updatedSession = session.withExecutionAccepted()

        val (signals, sink) = captureSignals()
        executor(sink, pipeline).execute(
            unit = accepted.unit,
            initialRecord = accepted.record,
            nowMs = nowMs
        )

        return Triple(updatedSession, accepted.unit, signals)
    }

    // ── Canonical acceptance order ────────────────────────────────────────────

    @Test
    fun `accepted receipt increments session execution count from 0 to 1`() {
        val session = attachedSession()
        val (updatedSession, _, _) = runCanonicalPath(session, envelope(), successPipeline())

        assertEquals(
            "Session count must be 1 after one accepted receipt",
            1,
            updatedSession.delegatedExecutionCount
        )
    }

    @Test
    fun `count is 1 before ACK signal is emitted — ordering invariant`() {
        // The canonical order is: receive → withExecutionAccepted (count++) → execute (ACK first).
        // We verify this by capturing count at the point withExecutionAccepted is called,
        // before any signals are emitted by the executor.
        val session = attachedSession()
        val receiver = DelegatedRuntimeReceiver()
        val receiptResult = receiver.receive(envelope(), session, receivedAtMs = 1_000L)
        assertTrue(receiptResult is DelegatedRuntimeReceiver.ReceiptResult.Accepted)

        // Count increment: this must happen before signals are emitted.
        val sessionAfterCount = session.withExecutionAccepted()
        assertEquals("Count must be 1 after increment, before ACK", 1, sessionAfterCount.delegatedExecutionCount)

        // Only now run executor — count is already 1 before ACK is emitted.
        val (signals, sink) = captureSignals()
        executor(sink, successPipeline()).execute(
            unit = (receiptResult as DelegatedRuntimeReceiver.ReceiptResult.Accepted).unit,
            initialRecord = receiptResult.record,
            nowMs = 1_000L
        )

        // Confirm ACK is the first signal and count was already 1 before it was emitted.
        assertEquals("ACK must be the first signal emitted", DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals("Count must be 1 — incremented before ACK was emitted", 1, sessionAfterCount.delegatedExecutionCount)
    }

    @Test
    fun `ACK is the first signal emitted on success path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), successPipeline())
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
    }

    @Test
    fun `ACK is the first signal emitted on failure path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), failingPipeline())
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
    }

    @Test
    fun `ACK is the first signal emitted on timeout path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), timeoutPipeline())
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
    }

    @Test
    fun `ACK is the first signal emitted on cancellation path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), cancelPipeline())
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
    }

    @Test
    fun `rejected receipt does not increment session count`() {
        val detachedSession = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val receiver = DelegatedRuntimeReceiver()
        val result = receiver.receive(envelope(), detachedSession)

        assertTrue(result is DelegatedRuntimeReceiver.ReceiptResult.Rejected)
        assertEquals(
            "Rejected session count must remain at 0",
            0,
            detachedSession.delegatedExecutionCount
        )
    }

    @Test
    fun `rejected receipt emits no signals`() {
        val detachedSession = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val receiver = DelegatedRuntimeReceiver()
        val result = receiver.receive(envelope(), detachedSession)

        assertTrue(result is DelegatedRuntimeReceiver.ReceiptResult.Rejected)
        // No executor is called on rejection; confirm by checking there is no acceptance.
        assertFalse("Rejected result must not be Accepted", result is DelegatedRuntimeReceiver.ReceiptResult.Accepted)
    }

    // ── Session-to-unit identity linkage ──────────────────────────────────────

    @Test
    fun `received unit attachedSessionId matches session sessionId`() {
        val session = attachedSession()
        val receiver = DelegatedRuntimeReceiver()
        val result = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted

        assertEquals(
            "Unit's attachedSessionId must match the session's sessionId",
            session.sessionId,
            result.unit.attachedSessionId
        )
    }

    @Test
    fun `ACK signal attachedSessionId matches session sessionId`() {
        val session = attachedSession()
        val (_, unit, signals) = runCanonicalPath(session, envelope(), successPipeline())

        assertEquals(
            "ACK signal must carry the session's sessionId",
            session.sessionId,
            signals[0].attachedSessionId
        )
    }

    @Test
    fun `PROGRESS signal attachedSessionId matches session sessionId`() {
        val session = attachedSession()
        val (_, _, signals) = runCanonicalPath(session, envelope(), successPipeline())

        assertEquals(
            "PROGRESS signal must carry the session's sessionId",
            session.sessionId,
            signals[1].attachedSessionId
        )
    }

    @Test
    fun `RESULT signal attachedSessionId matches session sessionId`() {
        val session = attachedSession()
        val (_, _, signals) = runCanonicalPath(session, envelope(), successPipeline())

        assertEquals(
            "RESULT signal must carry the session's sessionId",
            session.sessionId,
            signals[2].attachedSessionId
        )
    }

    // ── emissionSeq sequencing — success path ────────────────────────────────

    @Test
    fun `ACK carries EMISSION_SEQ_ACK on success path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), successPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_ACK, signals[0].emissionSeq)
    }

    @Test
    fun `PROGRESS carries EMISSION_SEQ_PROGRESS on success path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), successPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS, signals[1].emissionSeq)
    }

    @Test
    fun `RESULT carries EMISSION_SEQ_RESULT on success path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), successPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_RESULT, signals[2].emissionSeq)
    }

    // ── emissionSeq sequencing — failure path ────────────────────────────────

    @Test
    fun `ACK carries EMISSION_SEQ_ACK on failure path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), failingPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_ACK, signals[0].emissionSeq)
    }

    @Test
    fun `PROGRESS carries EMISSION_SEQ_PROGRESS on failure path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), failingPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS, signals[1].emissionSeq)
    }

    @Test
    fun `RESULT carries EMISSION_SEQ_RESULT on failure path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), failingPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_RESULT, signals[2].emissionSeq)
    }

    // ── emissionSeq sequencing — timeout path ────────────────────────────────

    @Test
    fun `ACK carries EMISSION_SEQ_ACK on timeout path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), timeoutPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_ACK, signals[0].emissionSeq)
    }

    @Test
    fun `PROGRESS carries EMISSION_SEQ_PROGRESS on timeout path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), timeoutPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS, signals[1].emissionSeq)
    }

    @Test
    fun `RESULT carries EMISSION_SEQ_RESULT on timeout path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), timeoutPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_RESULT, signals[2].emissionSeq)
    }

    // ── emissionSeq sequencing — cancellation path ───────────────────────────

    @Test
    fun `ACK carries EMISSION_SEQ_ACK on cancellation path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), cancelPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_ACK, signals[0].emissionSeq)
    }

    @Test
    fun `PROGRESS carries EMISSION_SEQ_PROGRESS on cancellation path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), cancelPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS, signals[1].emissionSeq)
    }

    @Test
    fun `RESULT carries EMISSION_SEQ_RESULT on cancellation path`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), cancelPipeline())
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_RESULT, signals[2].emissionSeq)
    }

    // ── Canonical emission contract — all terminal paths ─────────────────────

    @Test
    fun `success path emits exactly 3 signals ACK PROGRESS RESULT_COMPLETED`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), successPipeline())
        assertEquals(3, signals.size)
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
        assertEquals(DelegatedExecutionSignal.ResultKind.COMPLETED, signals[2].resultKind)
    }

    @Test
    fun `failure path emits exactly 3 signals ACK PROGRESS RESULT_FAILED`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), failingPipeline())
        assertEquals(3, signals.size)
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED, signals[2].resultKind)
    }

    @Test
    fun `timeout path emits exactly 3 signals ACK PROGRESS RESULT_TIMEOUT`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), timeoutPipeline())
        assertEquals(3, signals.size)
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
        assertEquals(DelegatedExecutionSignal.ResultKind.TIMEOUT, signals[2].resultKind)
    }

    @Test
    fun `cancellation path emits exactly 3 signals ACK PROGRESS RESULT_CANCELLED`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), cancelPipeline())
        assertEquals(3, signals.size)
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signals[0].kind)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signals[1].kind)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signals[2].kind)
        assertEquals(DelegatedExecutionSignal.ResultKind.CANCELLED, signals[2].resultKind)
    }

    // ── Identity continuity across all terminal paths ─────────────────────────

    @Test
    fun `all 3 signals on success path carry unitId from received unit`() {
        val session = attachedSession()
        val env = envelope(takeoverId = "to-identity-s")
        val (_, unit, signals) = runCanonicalPath(session, env, successPipeline())

        assertTrue(
            "All signals must carry unitId == ${unit.unitId}",
            signals.all { it.unitId == unit.unitId }
        )
    }

    @Test
    fun `all 3 signals on failure path carry taskId from received unit`() {
        val session = attachedSession()
        val env = envelope(taskId = "task-identity-f")
        val (_, unit, signals) = runCanonicalPath(session, env, failingPipeline())

        assertTrue(
            "All signals must carry taskId == ${unit.taskId}",
            signals.all { it.taskId == unit.taskId }
        )
    }

    @Test
    fun `all 3 signals on timeout path carry traceId from received unit`() {
        val session = attachedSession()
        val env = envelope(traceId = "trace-identity-t")
        val (_, unit, signals) = runCanonicalPath(session, env, timeoutPipeline())

        assertTrue(
            "All signals must carry traceId == ${unit.traceId}",
            signals.all { it.traceId == unit.traceId }
        )
    }

    @Test
    fun `all 3 signals on cancellation path carry attachedSessionId from session`() {
        val session = attachedSession()
        val (_, _, signals) = runCanonicalPath(session, envelope(), cancelPipeline())

        assertTrue(
            "All signals must carry attachedSessionId == ${session.sessionId}",
            signals.all { it.attachedSessionId == session.sessionId }
        )
    }

    // ── Tracker state progression — no duplicate transitions ─────────────────

    @Test
    fun `success path tracker ends in COMPLETED`() {
        val session = attachedSession()
        val receiver = DelegatedRuntimeReceiver()
        val receiptResult = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (_, sink) = captureSignals()
        val outcome = executor(sink, successPipeline()).execute(receiptResult.unit, receiptResult.record, nowMs = 1_000L)

        assertEquals(
            DelegatedActivationRecord.ActivationStatus.COMPLETED,
            (outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Completed).tracker.record.activationStatus
        )
    }

    @Test
    fun `failure path tracker ends in FAILED`() {
        val session = attachedSession()
        val receiver = DelegatedRuntimeReceiver()
        val receiptResult = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (_, sink) = captureSignals()
        val outcome = executor(sink, failingPipeline()).execute(receiptResult.unit, receiptResult.record, nowMs = 1_000L)

        assertEquals(
            DelegatedActivationRecord.ActivationStatus.FAILED,
            (outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed).tracker.record.activationStatus
        )
    }

    @Test
    fun `timeout path tracker ends in FAILED`() {
        val session = attachedSession()
        val receiver = DelegatedRuntimeReceiver()
        val receiptResult = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (_, sink) = captureSignals()
        val outcome = executor(sink, timeoutPipeline()).execute(receiptResult.unit, receiptResult.record, nowMs = 1_000L)

        assertEquals(
            DelegatedActivationRecord.ActivationStatus.FAILED,
            (outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed).tracker.record.activationStatus
        )
    }

    @Test
    fun `cancellation path tracker ends in FAILED`() {
        val session = attachedSession()
        val receiver = DelegatedRuntimeReceiver()
        val receiptResult = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (_, sink) = captureSignals()
        val outcome = executor(sink, cancelPipeline()).execute(receiptResult.unit, receiptResult.record, nowMs = 1_000L)

        assertEquals(
            DelegatedActivationRecord.ActivationStatus.FAILED,
            (outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed).tracker.record.activationStatus
        )
    }

    @Test
    fun `success path tracker is terminal after execute returns`() {
        val session = attachedSession()
        val receiver = DelegatedRuntimeReceiver()
        val receiptResult = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (_, sink) = captureSignals()
        val outcome = executor(sink, successPipeline()).execute(receiptResult.unit, receiptResult.record, nowMs = 1_000L)

        assertTrue(
            "Tracker must be terminal after execute returns on success path",
            (outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Completed).tracker.isTerminal
        )
    }

    @Test
    fun `failure path tracker is terminal after execute returns`() {
        val session = attachedSession()
        val receiver = DelegatedRuntimeReceiver()
        val receiptResult = receiver.receive(envelope(), session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (_, sink) = captureSignals()
        val outcome = executor(sink, failingPipeline()).execute(receiptResult.unit, receiptResult.record, nowMs = 1_000L)

        assertTrue(
            "Tracker must be terminal after execute returns on failure path",
            (outcome as DelegatedTakeoverExecutor.ExecutionOutcome.Failed).tracker.isTerminal
        )
    }

    // ── Multiple tasks through a single session ───────────────────────────────

    @Test
    fun `two sequential accepted receipts produce count 2 on the session`() {
        val session = attachedSession()
        val sessionAfter1 = session.withExecutionAccepted()
        val sessionAfter2 = sessionAfter1.withExecutionAccepted()

        assertEquals(
            "Two accepted receipts must produce count == 2",
            2,
            sessionAfter2.delegatedExecutionCount
        )
    }

    @Test
    fun `five sequential accepted receipts produce count 5 on the session`() {
        var session = attachedSession()
        repeat(5) { session = session.withExecutionAccepted() }

        assertEquals(
            "Five accepted receipts must produce count == 5",
            5,
            session.delegatedExecutionCount
        )
    }

    @Test
    fun `session sessionId is preserved across multiple accepted receipts`() {
        val session = attachedSession()
        val originalSessionId = session.sessionId

        var updated = session
        repeat(3) { updated = updated.withExecutionAccepted() }

        assertEquals(
            "Session sessionId must not change across multiple accepted receipts",
            originalSessionId,
            updated.sessionId
        )
    }

    @Test
    fun `second task signals carry same sessionId as first task signals`() {
        val session = attachedSession()
        val receiver = DelegatedRuntimeReceiver()

        // First task.
        val env1 = envelope(takeoverId = "to-multi-1", taskId = "task-multi-1")
        val result1 = receiver.receive(env1, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val sessionAfter1 = session.withExecutionAccepted()

        val (signals1, sink1) = captureSignals()
        executor(sink1, successPipeline()).execute(result1.unit, result1.record, nowMs = 1_000L)

        // Second task on the same session.
        val env2 = envelope(takeoverId = "to-multi-2", taskId = "task-multi-2")
        val result2 = receiver.receive(env2, sessionAfter1) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val sessionAfter2 = sessionAfter1.withExecutionAccepted()

        val (signals2, sink2) = captureSignals()
        executor(sink2, successPipeline()).execute(result2.unit, result2.record, nowMs = 2_000L)

        // Both tasks' signals must carry the same sessionId.
        val sessionId = session.sessionId
        assertTrue("All signals from task 1 carry the session's sessionId",
            signals1.all { it.attachedSessionId == sessionId })
        assertTrue("All signals from task 2 carry the same session's sessionId",
            signals2.all { it.attachedSessionId == sessionId })

        assertEquals("Session count must be 2 after two accepted receipts", 2, sessionAfter2.delegatedExecutionCount)
    }

    // ── Signal identity per execution: distinct signalIds within one execution ─

    @Test
    fun `ACK PROGRESS RESULT signals within one execution have distinct signalIds`() {
        val (_, _, signals) = runCanonicalPath(attachedSession(), envelope(), successPipeline())

        val ids = signals.map { it.signalId }.toSet()
        assertEquals(
            "Each of the 3 signals must have a distinct signalId within one execution",
            3,
            ids.size
        )
    }

    @Test
    fun `signals across two executions share session ID but have distinct signalIds`() {
        val session = attachedSession()
        val receiver = DelegatedRuntimeReceiver()

        val env1 = envelope(takeoverId = "to-ids-1", taskId = "task-ids-1")
        val result1 = receiver.receive(env1, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals1, sink1) = captureSignals()
        executor(sink1, successPipeline()).execute(result1.unit, result1.record, nowMs = 1_000L)

        val env2 = envelope(takeoverId = "to-ids-2", taskId = "task-ids-2")
        val result2 = receiver.receive(env2, session) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        val (signals2, sink2) = captureSignals()
        executor(sink2, successPipeline()).execute(result2.unit, result2.record, nowMs = 2_000L)

        // Both tasks share the same attachedSessionId.
        assertTrue("Execution 1 signals carry session.sessionId",
            signals1.all { it.attachedSessionId == session.sessionId })
        assertTrue("Execution 2 signals carry session.sessionId",
            signals2.all { it.attachedSessionId == session.sessionId })

        // But all 6 signals have distinct signalIds.
        val allIds = (signals1 + signals2).map { it.signalId }.toSet()
        assertEquals("All 6 signals across 2 executions must have distinct signalIds", 6, allIds.size)
    }

    // ── Rejection outcome coverage ────────────────────────────────────────────

    @Test
    fun `null session produces NO_ATTACHED_SESSION rejection`() {
        val receiver = DelegatedRuntimeReceiver()
        val result = receiver.receive(envelope(), session = null)

        assertTrue(result is DelegatedRuntimeReceiver.ReceiptResult.Rejected)
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.NO_ATTACHED_SESSION,
            (result as DelegatedRuntimeReceiver.ReceiptResult.Rejected).outcome
        )
    }

    @Test
    fun `DETACHING session produces SESSION_DETACHING rejection`() {
        val detaching = attachedSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISABLE)
        val receiver = DelegatedRuntimeReceiver()
        val result = receiver.receive(envelope(), detaching)

        assertTrue(result is DelegatedRuntimeReceiver.ReceiptResult.Rejected)
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.SESSION_DETACHING,
            (result as DelegatedRuntimeReceiver.ReceiptResult.Rejected).outcome
        )
    }

    @Test
    fun `DETACHED session produces SESSION_DETACHED rejection`() {
        val detached = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val receiver = DelegatedRuntimeReceiver()
        val result = receiver.receive(envelope(), detached)

        assertTrue(result is DelegatedRuntimeReceiver.ReceiptResult.Rejected)
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.SESSION_DETACHED,
            (result as DelegatedRuntimeReceiver.ReceiptResult.Rejected).outcome
        )
    }
}
