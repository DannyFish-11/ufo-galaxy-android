package com.ufo.galaxy.agent

import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.runtime.DelegatedActivationRecord
import com.ufo.galaxy.runtime.DelegatedExecutionSignalSink
import com.ufo.galaxy.runtime.ReconciliationSignal
import com.ufo.galaxy.runtime.ReconciliationSignalSink
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-52 — Reconciliation signal emission from [DelegatedTakeoverExecutor].
 *
 * Verifies that [DelegatedTakeoverExecutor] emits structured [ReconciliationSignal] events
 * via [ReconciliationSignalSink] at each task lifecycle milestone, providing V2 with the
 * protocol-safe signals required for canonical participant-side truth reconciliation.
 *
 * ## Test matrix
 *
 * ### ReconciliationSignalSink — no-op default
 *  - Executor works without supplying reconciliationSignalSink (no-op default).
 *  - DelegatedExecutionSignal events are still emitted when no reconciliation sink is set.
 *
 * ### TASK_ACCEPTED signal — success path
 *  - TASK_ACCEPTED is emitted on success path (exactly one signal).
 *  - TASK_ACCEPTED is the first reconciliation signal emitted.
 *  - TASK_ACCEPTED carries the correct participantId.
 *  - TASK_ACCEPTED carries the correct taskId.
 *  - TASK_ACCEPTED carries the correlationId (traceId) from the unit.
 *  - TASK_ACCEPTED isTerminal is false.
 *  - TASK_ACCEPTED status is STATUS_RUNNING.
 *
 * ### TASK_RESULT signal — success path
 *  - TASK_RESULT is emitted on success path.
 *  - TASK_RESULT is the last reconciliation signal on success.
 *  - Exactly two reconciliation signals are emitted on success (TASK_ACCEPTED + TASK_RESULT).
 *  - TASK_RESULT carries the correct participantId.
 *  - TASK_RESULT carries the correct taskId.
 *  - TASK_RESULT isTerminal is true.
 *  - TASK_RESULT status is STATUS_SUCCESS.
 *
 * ### TASK_CANCELLED signal — CancellationException path
 *  - TASK_CANCELLED is emitted on CancellationException path.
 *  - TASK_CANCELLED is the second reconciliation signal emitted.
 *  - Exactly two reconciliation signals are emitted on cancellation (TASK_ACCEPTED + TASK_CANCELLED).
 *  - TASK_CANCELLED carries the correct participantId.
 *  - TASK_CANCELLED carries the correct taskId.
 *  - TASK_CANCELLED isTerminal is true.
 *  - TASK_CANCELLED status is STATUS_CANCELLED.
 *
 * ### TASK_FAILED signal — general exception path
 *  - TASK_FAILED is emitted on exception path.
 *  - TASK_FAILED is the second reconciliation signal emitted on failure.
 *  - Exactly two reconciliation signals are emitted on general failure.
 *  - TASK_FAILED carries the correct participantId.
 *  - TASK_FAILED carries the correct taskId.
 *  - TASK_FAILED isTerminal is true.
 *  - TASK_FAILED status is STATUS_FAILED.
 *  - TASK_FAILED payload contains error_detail from the exception message.
 *
 * ### participantId fallback
 *  - When participantId is empty string, attachedSessionId is used as fallback participantId.
 *  - When participantId is non-blank, it is used as the participantId.
 *
 * ### Reconciliation signal independence
 *  - Reconciliation signals do not affect DelegatedExecutionSignal emission count.
 *  - DelegatedExecutionSignal events are not affected by reconciliation sink presence.
 *  - signalId is non-null for each reconciliation signal emitted.
 */
class Pr52ReconciliationSignalEmissionTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeUnit(
        unitId: String = "unit-52",
        taskId: String = "task-52",
        traceId: String = "trace-52",
        sessionId: String = "sess-52"
    ) = DelegatedRuntimeUnit(
        unitId = unitId,
        taskId = taskId,
        traceId = traceId,
        goal = "pr-52 test goal",
        attachedSessionId = sessionId
    )

    private fun pendingRecord(unit: DelegatedRuntimeUnit = makeUnit()) =
        DelegatedActivationRecord.create(unit = unit, activatedAtMs = 1_000L)

    private fun captureReconciliationSignals(): Pair<MutableList<ReconciliationSignal>, ReconciliationSignalSink> {
        val captured = mutableListOf<ReconciliationSignal>()
        val sink = ReconciliationSignalSink { signal -> captured += signal }
        return Pair(captured, sink)
    }

    private fun successPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { payload ->
            GoalResultPayload(task_id = payload.task_id, status = "success")
        }

    private fun failingPipeline(
        message: String = "simulated failure"
    ): GoalExecutionPipeline = GoalExecutionPipeline { _ ->
        throw RuntimeException(message)
    }

    private fun cancellingPipeline(): GoalExecutionPipeline =
        GoalExecutionPipeline { _ ->
            throw CancellationException("deliberate_cancel")
        }

    private fun buildExecutor(
        pipeline: GoalExecutionPipeline = successPipeline(),
        reconciliationSink: ReconciliationSignalSink = ReconciliationSignalSink { }
    ) = DelegatedTakeoverExecutor(
        pipeline = pipeline,
        signalSink = DelegatedExecutionSignalSink { },
        reconciliationSignalSink = reconciliationSink
    )

    // ── ReconciliationSignalSink — no-op default ──────────────────────────────

    @Test
    fun `executor works without supplying reconciliationSignalSink`() {
        // Uses the two-arg constructor (backward-compat path); no reconciliation sink set.
        val executor = DelegatedTakeoverExecutor(
            pipeline = successPipeline(),
            signalSink = DelegatedExecutionSignalSink { }
        )
        val outcome = executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertTrue("Should complete successfully", outcome is DelegatedTakeoverExecutor.ExecutionOutcome.Completed)
    }

    @Test
    fun `DelegatedExecutionSignal events are still emitted when no reconciliation sink is set`() {
        val captured = mutableListOf<com.ufo.galaxy.runtime.DelegatedExecutionSignal>()
        val executor = DelegatedTakeoverExecutor(
            pipeline = successPipeline(),
            signalSink = DelegatedExecutionSignalSink { signal -> captured += signal }
        )
        executor.execute(makeUnit(), pendingRecord(), nowMs = 1_000L)
        assertEquals("ACK + PROGRESS + RESULT = 3 DelegatedExecutionSignal events", 3, captured.size)
    }

    // ── TASK_ACCEPTED signal — success path ────────────────────────────────────

    @Test
    fun `TASK_ACCEPTED is emitted on success path`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "Pixel_8:host-001", nowMs = 1_000L)
        val accepted = captured.firstOrNull { it.kind == ReconciliationSignal.Kind.TASK_ACCEPTED }
        assertNotNull("TASK_ACCEPTED must be emitted on success", accepted)
    }

    @Test
    fun `TASK_ACCEPTED is the first reconciliation signal`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "Pixel_8:host-001", nowMs = 1_000L)
        assertEquals(ReconciliationSignal.Kind.TASK_ACCEPTED, captured[0].kind)
    }

    @Test
    fun `TASK_ACCEPTED carries the correct participantId`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "device-xyz:host-abc", nowMs = 1_000L)
        val accepted = captured.first { it.kind == ReconciliationSignal.Kind.TASK_ACCEPTED }
        assertEquals("device-xyz:host-abc", accepted.participantId)
    }

    @Test
    fun `TASK_ACCEPTED carries the correct taskId`() {
        val (captured, sink) = captureReconciliationSignals()
        val unit = makeUnit(taskId = "task-accepted-id")
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(unit, pendingRecord(unit), participantId = "pid", nowMs = 1_000L)
        val accepted = captured.first { it.kind == ReconciliationSignal.Kind.TASK_ACCEPTED }
        assertEquals("task-accepted-id", accepted.taskId)
    }

    @Test
    fun `TASK_ACCEPTED carries the correlationId from the unit traceId`() {
        val (captured, sink) = captureReconciliationSignals()
        val unit = makeUnit(traceId = "trace-correlation")
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(unit, pendingRecord(unit), participantId = "pid", nowMs = 1_000L)
        val accepted = captured.first { it.kind == ReconciliationSignal.Kind.TASK_ACCEPTED }
        assertEquals("trace-correlation", accepted.correlationId)
    }

    @Test
    fun `TASK_ACCEPTED isTerminal is false`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        val accepted = captured.first { it.kind == ReconciliationSignal.Kind.TASK_ACCEPTED }
        assertFalse(accepted.isTerminal)
    }

    @Test
    fun `TASK_ACCEPTED status is STATUS_RUNNING`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        val accepted = captured.first { it.kind == ReconciliationSignal.Kind.TASK_ACCEPTED }
        assertEquals(ReconciliationSignal.STATUS_RUNNING, accepted.status)
    }

    // ── TASK_RESULT signal — success path ──────────────────────────────────────

    @Test
    fun `TASK_RESULT is emitted on success path`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        val result = captured.firstOrNull { it.kind == ReconciliationSignal.Kind.TASK_RESULT }
        assertNotNull("TASK_RESULT must be emitted on success", result)
    }

    @Test
    fun `TASK_RESULT is the last reconciliation signal on success`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        assertEquals(ReconciliationSignal.Kind.TASK_RESULT, captured.last().kind)
    }

    @Test
    fun `exactly two reconciliation signals are emitted on success (TASK_ACCEPTED + TASK_RESULT)`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        assertEquals("TASK_ACCEPTED + TASK_RESULT = 2 reconciliation signals on success", 2, captured.size)
    }

    @Test
    fun `TASK_RESULT carries the correct participantId`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "p-result-test", nowMs = 1_000L)
        val result = captured.first { it.kind == ReconciliationSignal.Kind.TASK_RESULT }
        assertEquals("p-result-test", result.participantId)
    }

    @Test
    fun `TASK_RESULT carries the correct taskId`() {
        val (captured, sink) = captureReconciliationSignals()
        val unit = makeUnit(taskId = "task-result-id")
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(unit, pendingRecord(unit), participantId = "pid", nowMs = 1_000L)
        val result = captured.first { it.kind == ReconciliationSignal.Kind.TASK_RESULT }
        assertEquals("task-result-id", result.taskId)
    }

    @Test
    fun `TASK_RESULT isTerminal is true`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        val result = captured.first { it.kind == ReconciliationSignal.Kind.TASK_RESULT }
        assertTrue(result.isTerminal)
    }

    @Test
    fun `TASK_RESULT status is STATUS_SUCCESS`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        val result = captured.first { it.kind == ReconciliationSignal.Kind.TASK_RESULT }
        assertEquals(ReconciliationSignal.STATUS_SUCCESS, result.status)
    }

    // ── TASK_CANCELLED signal — CancellationException path ────────────────────

    @Test
    fun `TASK_CANCELLED is emitted on CancellationException path`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = cancellingPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        val cancelled = captured.firstOrNull { it.kind == ReconciliationSignal.Kind.TASK_CANCELLED }
        assertNotNull("TASK_CANCELLED must be emitted on CancellationException", cancelled)
    }

    @Test
    fun `TASK_CANCELLED is the second reconciliation signal emitted`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = cancellingPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        assertEquals(ReconciliationSignal.Kind.TASK_CANCELLED, captured[1].kind)
    }

    @Test
    fun `exactly two reconciliation signals are emitted on cancellation`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = cancellingPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        assertEquals("TASK_ACCEPTED + TASK_CANCELLED = 2 reconciliation signals on cancellation", 2, captured.size)
    }

    @Test
    fun `TASK_CANCELLED carries the correct participantId`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = cancellingPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "p-cancel-test", nowMs = 1_000L)
        val cancelled = captured.first { it.kind == ReconciliationSignal.Kind.TASK_CANCELLED }
        assertEquals("p-cancel-test", cancelled.participantId)
    }

    @Test
    fun `TASK_CANCELLED carries the correct taskId`() {
        val (captured, sink) = captureReconciliationSignals()
        val unit = makeUnit(taskId = "task-cancel-id")
        buildExecutor(pipeline = cancellingPipeline(), reconciliationSink = sink)
            .execute(unit, pendingRecord(unit), participantId = "pid", nowMs = 1_000L)
        val cancelled = captured.first { it.kind == ReconciliationSignal.Kind.TASK_CANCELLED }
        assertEquals("task-cancel-id", cancelled.taskId)
    }

    @Test
    fun `TASK_CANCELLED isTerminal is true`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = cancellingPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        val cancelled = captured.first { it.kind == ReconciliationSignal.Kind.TASK_CANCELLED }
        assertTrue(cancelled.isTerminal)
    }

    @Test
    fun `TASK_CANCELLED status is STATUS_CANCELLED`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = cancellingPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        val cancelled = captured.first { it.kind == ReconciliationSignal.Kind.TASK_CANCELLED }
        assertEquals(ReconciliationSignal.STATUS_CANCELLED, cancelled.status)
    }

    // ── TASK_FAILED signal — general exception path ───────────────────────────

    @Test
    fun `TASK_FAILED is emitted on general exception path`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = failingPipeline("task_error"), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        val failed = captured.firstOrNull { it.kind == ReconciliationSignal.Kind.TASK_FAILED }
        assertNotNull("TASK_FAILED must be emitted on exception", failed)
    }

    @Test
    fun `TASK_FAILED is the second reconciliation signal emitted on failure`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = failingPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        assertEquals(ReconciliationSignal.Kind.TASK_FAILED, captured[1].kind)
    }

    @Test
    fun `exactly two reconciliation signals are emitted on general failure`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = failingPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        assertEquals("TASK_ACCEPTED + TASK_FAILED = 2 reconciliation signals on failure", 2, captured.size)
    }

    @Test
    fun `TASK_FAILED carries the correct participantId`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = failingPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "p-fail-test", nowMs = 1_000L)
        val failed = captured.first { it.kind == ReconciliationSignal.Kind.TASK_FAILED }
        assertEquals("p-fail-test", failed.participantId)
    }

    @Test
    fun `TASK_FAILED carries the correct taskId`() {
        val (captured, sink) = captureReconciliationSignals()
        val unit = makeUnit(taskId = "task-fail-id")
        buildExecutor(pipeline = failingPipeline(), reconciliationSink = sink)
            .execute(unit, pendingRecord(unit), participantId = "pid", nowMs = 1_000L)
        val failed = captured.first { it.kind == ReconciliationSignal.Kind.TASK_FAILED }
        assertEquals("task-fail-id", failed.taskId)
    }

    @Test
    fun `TASK_FAILED isTerminal is true`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = failingPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        val failed = captured.first { it.kind == ReconciliationSignal.Kind.TASK_FAILED }
        assertTrue(failed.isTerminal)
    }

    @Test
    fun `TASK_FAILED status is STATUS_FAILED`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = failingPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        val failed = captured.first { it.kind == ReconciliationSignal.Kind.TASK_FAILED }
        assertEquals(ReconciliationSignal.STATUS_FAILED, failed.status)
    }

    @Test
    fun `TASK_FAILED payload contains error_detail from the exception message`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = failingPipeline("pipeline_execution_fault"), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        val failed = captured.first { it.kind == ReconciliationSignal.Kind.TASK_FAILED }
        assertEquals("pipeline_execution_fault", failed.payload["error_detail"])
    }

    // ── participantId fallback ─────────────────────────────────────────────────

    @Test
    fun `when participantId is empty string attachedSessionId is used as fallback`() {
        val (captured, sink) = captureReconciliationSignals()
        val unit = makeUnit(sessionId = "fallback-session-id")
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(unit, pendingRecord(unit), participantId = "", nowMs = 1_000L)
        val accepted = captured.first { it.kind == ReconciliationSignal.Kind.TASK_ACCEPTED }
        assertEquals("fallback-session-id", accepted.participantId)
    }

    @Test
    fun `when participantId is non-blank it is used as the participantId`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "explicit-participant-id", nowMs = 1_000L)
        val accepted = captured.first { it.kind == ReconciliationSignal.Kind.TASK_ACCEPTED }
        assertEquals("explicit-participant-id", accepted.participantId)
    }

    // ── Reconciliation signal independence ─────────────────────────────────────

    @Test
    fun `reconciliation signals do not affect DelegatedExecutionSignal emission count`() {
        val reconciliationCaptured = mutableListOf<ReconciliationSignal>()
        val executionCaptured = mutableListOf<com.ufo.galaxy.runtime.DelegatedExecutionSignal>()
        val executor = DelegatedTakeoverExecutor(
            pipeline = successPipeline(),
            signalSink = DelegatedExecutionSignalSink { signal -> executionCaptured += signal },
            reconciliationSignalSink = ReconciliationSignalSink { signal -> reconciliationCaptured += signal }
        )
        executor.execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        assertEquals("DelegatedExecutionSignal count must be exactly 3 (ACK+PROGRESS+RESULT)", 3, executionCaptured.size)
        assertEquals("ReconciliationSignal count must be exactly 2 (TASK_ACCEPTED+TASK_RESULT)", 2, reconciliationCaptured.size)
    }

    @Test
    fun `signalId is non-null for each reconciliation signal emitted`() {
        val (captured, sink) = captureReconciliationSignals()
        buildExecutor(pipeline = successPipeline(), reconciliationSink = sink)
            .execute(makeUnit(), pendingRecord(), participantId = "pid", nowMs = 1_000L)
        captured.forEach { signal ->
            assertNotNull("signalId must be non-null for ${signal.kind}", signal.signalId)
            assertTrue("signalId must be non-blank for ${signal.kind}", signal.signalId.isNotBlank())
        }
    }
}
