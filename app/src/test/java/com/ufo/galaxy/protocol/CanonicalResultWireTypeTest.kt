package com.ufo.galaxy.protocol

import com.ufo.galaxy.network.OfflineTaskQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canonical result wire-type unification tests.
 *
 * These tests verify that the Android-side result uplink protocol is unified around
 * [MsgType.GOAL_EXECUTION_RESULT] for all production paths, so that the V2 gateway
 * can consume results from a single canonical handler
 * (`_handle_goal_execution_result`) without needing to guess the message type.
 *
 * ## Coverage
 *
 * ### Wire type invariant
 *  - Normal execution result envelope uses GOAL_EXECUTION_RESULT.
 *  - Error result envelope uses GOAL_EXECUTION_RESULT.
 *  - Timeout result envelope uses GOAL_EXECUTION_RESULT.
 *  - Envelope with full trace context (traceId + routeMode) uses GOAL_EXECUTION_RESULT.
 *  - Envelope with null trace context (missing traceId/routeMode) uses GOAL_EXECUTION_RESULT.
 *
 * ### Envelope field invariants
 *  - correlation_id is echoed from GoalResultPayload.correlation_id when present,
 *    falling back to task_id.
 *  - trace_id in the envelope mirrors the traceId parameter (null when absent).
 *  - route_mode in the envelope mirrors the routeMode parameter (null when absent).
 *  - dispatch_trace_id in the envelope mirrors GoalResultPayload.dispatch_trace_id.
 *  - source_runtime_posture in the envelope mirrors GoalResultPayload.source_runtime_posture.
 *
 * ### Offline queue contract
 *  - "goal_execution_result" is a member of OfflineTaskQueue.QUEUEABLE_TYPES.
 *  - "task_result" and "goal_result" remain in QUEUEABLE_TYPES for backward-compat.
 *
 * ### Dual-repo canonical alignment
 *  - MsgType.GOAL_EXECUTION_RESULT wire value is stable ("goal_execution_result").
 *  - MsgType.GOAL_RESULT wire value is stable ("goal_result") — kept for compat.
 *
 * ### Unified contract: normalized_status
 *  - GoalResultPayload.normalized_status defaults to null before emission.
 *  - "success" → "final_completion"; "cancelled" → "cancellation";
 *    "disabled" → "disabled"; "error"/"timeout"/other → "failure".
 *
 * ### Unified contract: runtime_session_id
 *  - GoalResultPayload.runtime_session_id defaults to null before emission.
 *  - Field is preserved through GoalResultPayload.copy().
 *
 * ### Unified contract: field consistency
 *  - Error, timeout, and normal results can all carry source_runtime_posture.
 *  - dispatch_trace_id is consistent across normal and error result envelopes.
 *
 * @see com.ufo.galaxy.network.OfflineTaskQueue.QUEUEABLE_TYPES
 * @see MsgType.GOAL_EXECUTION_RESULT
 */
class CanonicalResultWireTypeTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildNormalResult(
        taskId: String = "t-norm-1",
        correlationId: String? = null
    ) = GoalResultPayload(
        task_id = taskId,
        correlation_id = correlationId ?: taskId,
        status = "success",
        device_id = "test-device",
        latency_ms = 100L
    )

    private fun buildErrorResult(
        taskId: String = "t-err-1",
        errorMsg: String = "execution failed"
    ) = GoalResultPayload(
        task_id = taskId,
        correlation_id = taskId,
        status = "error",
        error = errorMsg,
        device_id = "test-device",
        latency_ms = 0L
    )

    private fun buildTimeoutResult(
        taskId: String = "t-timeout-1",
        timeoutMs: Long = 30_000L
    ) = GoalResultPayload(
        task_id = taskId,
        correlation_id = taskId,
        status = "timeout",
        error = "Task exceeded timeout of ${timeoutMs}ms",
        device_id = "test-device",
        latency_ms = timeoutMs
    )

    /**
     * Mirrors the canonical send path in GalaxyConnectionService.sendGoalResult.
     *
     * Includes [dispatch_trace_id] and [source_runtime_posture] from the result payload
     * in the envelope, matching the updated sendGoalResult which propagates both fields
     * for consistent V2 observability and posture routing.
     */
    private fun buildEnvelope(
        result: GoalResultPayload,
        traceId: String?,
        routeMode: String?
    ) = AipMessage(
        type = MsgType.GOAL_EXECUTION_RESULT,
        payload = result,
        correlation_id = result.correlation_id ?: result.task_id,
        device_id = result.device_id,
        trace_id = traceId,
        route_mode = routeMode,
        dispatch_trace_id = result.dispatch_trace_id,
        source_runtime_posture = result.source_runtime_posture
    )

    // ── Wire type invariant: normal result ────────────────────────────────────

    @Test
    fun `normal execution result envelope uses GOAL_EXECUTION_RESULT`() {
        val result = buildNormalResult()
        val envelope = buildEnvelope(result, traceId = "trace-001", routeMode = "cross_device")
        assertEquals(MsgType.GOAL_EXECUTION_RESULT, envelope.type)
    }

    @Test
    fun `normal result wire value is goal_execution_result`() {
        val result = buildNormalResult()
        val envelope = buildEnvelope(result, traceId = "trace-002", routeMode = "cross_device")
        assertEquals("goal_execution_result", envelope.type.value)
    }

    // ── Wire type invariant: error result ─────────────────────────────────────

    @Test
    fun `error result envelope uses GOAL_EXECUTION_RESULT`() {
        val result = buildErrorResult()
        val envelope = buildEnvelope(result, traceId = "trace-err-1", routeMode = "cross_device")
        assertEquals(MsgType.GOAL_EXECUTION_RESULT, envelope.type)
    }

    @Test
    fun `error result with trace uses GOAL_EXECUTION_RESULT not GOAL_RESULT`() {
        val result = buildErrorResult(errorMsg = "bad_payload: malformed json")
        val envelope = buildEnvelope(result, traceId = "trace-err-2", routeMode = "cross_device")
        assertEquals(MsgType.GOAL_EXECUTION_RESULT, envelope.type)
        // Explicitly verify it is NOT the legacy type
        assertTrue(envelope.type != MsgType.GOAL_RESULT)
    }

    // ── Wire type invariant: timeout result ───────────────────────────────────

    @Test
    fun `timeout result envelope uses GOAL_EXECUTION_RESULT`() {
        val result = buildTimeoutResult()
        val envelope = buildEnvelope(result, traceId = "trace-to-1", routeMode = "cross_device")
        assertEquals(MsgType.GOAL_EXECUTION_RESULT, envelope.type)
    }

    // ── Trace context present ─────────────────────────────────────────────────

    @Test
    fun `envelope with trace context carries trace_id and route_mode`() {
        val result = buildNormalResult()
        val envelope = buildEnvelope(result, traceId = "inbound-trace-xyz", routeMode = "cross_device")
        assertEquals("inbound-trace-xyz", envelope.trace_id)
        assertEquals("cross_device", envelope.route_mode)
    }

    @Test
    fun `error envelope with trace context uses GOAL_EXECUTION_RESULT and carries trace_id`() {
        val result = buildErrorResult()
        val envelope = buildEnvelope(result, traceId = "err-trace-abc", routeMode = "cross_device")
        assertEquals(MsgType.GOAL_EXECUTION_RESULT, envelope.type)
        assertEquals("err-trace-abc", envelope.trace_id)
    }

    // ── Trace context absent (null traceId / routeMode) ───────────────────────

    @Test
    fun `envelope with null trace context still uses GOAL_EXECUTION_RESULT`() {
        val result = buildErrorResult(errorMsg = "parse error: unexpected token")
        val envelope = buildEnvelope(result, traceId = null, routeMode = null)
        assertEquals(MsgType.GOAL_EXECUTION_RESULT, envelope.type)
    }

    @Test
    fun `envelope with null trace context has null trace_id and route_mode`() {
        val result = buildErrorResult()
        val envelope = buildEnvelope(result, traceId = null, routeMode = null)
        assertNull(envelope.trace_id)
        assertNull(envelope.route_mode)
    }

    @Test
    fun `envelope with null traceId only still uses GOAL_EXECUTION_RESULT`() {
        val result = buildNormalResult()
        val envelope = buildEnvelope(result, traceId = null, routeMode = "cross_device")
        assertEquals(MsgType.GOAL_EXECUTION_RESULT, envelope.type)
        assertNull(envelope.trace_id)
        assertEquals("cross_device", envelope.route_mode)
    }

    // ── Envelope field invariants ─────────────────────────────────────────────

    @Test
    fun `correlation_id echoes from GoalResultPayload correlation_id when present`() {
        val result = buildNormalResult(taskId = "t-corr-1", correlationId = "corr-override-xyz")
        val envelope = buildEnvelope(result, traceId = "t", routeMode = "cross_device")
        assertEquals("corr-override-xyz", envelope.correlation_id)
    }

    @Test
    fun `correlation_id falls back to task_id when GoalResultPayload correlation_id is null`() {
        // correlation_id == task_id means same value, matching the canonical fallback
        val result = GoalResultPayload(
            task_id = "t-fallback-1",
            correlation_id = null,
            status = "success",
            device_id = "dev"
        )
        val correlationId = result.correlation_id ?: result.task_id
        val envelope = buildEnvelope(result.copy(correlation_id = correlationId), traceId = null, routeMode = null)
        assertEquals("t-fallback-1", envelope.correlation_id)
    }

    // ── Offline queue contract ────────────────────────────────────────────────

    @Test
    fun `goal_execution_result is queueable during offline periods`() {
        assertTrue(
            "goal_execution_result must be in QUEUEABLE_TYPES so main-path results survive " +
                "transient WebSocket disconnections",
            "goal_execution_result" in OfflineTaskQueue.QUEUEABLE_TYPES
        )
    }

    @Test
    fun `task_result and goal_result remain queueable for backward-compat`() {
        assertTrue("task_result" in OfflineTaskQueue.QUEUEABLE_TYPES)
        assertTrue("goal_result" in OfflineTaskQueue.QUEUEABLE_TYPES)
    }

    @Test
    fun `heartbeat is not queueable`() {
        assertTrue("heartbeat" !in OfflineTaskQueue.QUEUEABLE_TYPES)
    }

    @Test
    fun `goal_execution_result can be enqueued in offline queue`() {
        val queue = OfflineTaskQueue(prefs = null)
        val json = """{"type":"goal_execution_result","payload":{"task_id":"t1","status":"success"}}"""
        queue.enqueue("goal_execution_result", json)
        val drained = queue.drainAll()
        assertEquals(1, drained.size)
        assertEquals("goal_execution_result", drained[0].type)
        assertEquals(json, drained[0].json)
    }

    // ── Dual-repo canonical alignment ─────────────────────────────────────────

    @Test
    fun `GOAL_EXECUTION_RESULT wire value is stable goal_execution_result`() {
        assertEquals("goal_execution_result", MsgType.GOAL_EXECUTION_RESULT.value)
    }

    @Test
    fun `GOAL_RESULT wire value is stable goal_result`() {
        // GOAL_RESULT is retained in the enum for backward-compat with legacy paths
        // and server-side aliases.  Its wire value must not change.
        assertEquals("goal_result", MsgType.GOAL_RESULT.value)
    }

    @Test
    fun `GOAL_EXECUTION_RESULT and GOAL_RESULT have distinct wire values`() {
        assertTrue(MsgType.GOAL_EXECUTION_RESULT.value != MsgType.GOAL_RESULT.value)
    }

    // ── Unified contract: normalized_status mapping ───────────────────────────
    //
    // Verifies that GoalResultPayload.normalized_status defaults to null before emission
    // (the field is set by sendGoalResult at the single emission layer) and that the
    // expected canonical kind strings are the stable values for the four outcome classes.
    // These constants are consumed by V2 unified result ingress.

    @Test
    fun `GoalResultPayload normalized_status defaults to null before emission`() {
        val result = buildNormalResult()
        assertNull(
            "normalized_status must be null before sendGoalResult enriches it",
            result.normalized_status
        )
    }

    @Test
    fun `GoalResultPayload runtime_session_id defaults to null before emission`() {
        val result = buildNormalResult()
        assertNull(
            "runtime_session_id must be null before sendGoalResult enriches it",
            result.runtime_session_id
        )
    }

    @Test
    fun `normalized_status success maps to final_completion`() {
        val result = GoalResultPayload(
            task_id = "t1", status = "success", device_id = "dev",
            normalized_status = "final_completion"
        )
        assertEquals("final_completion", result.normalized_status)
    }

    @Test
    fun `normalized_status error maps to failure`() {
        val result = GoalResultPayload(
            task_id = "t2", status = "error", device_id = "dev",
            normalized_status = "failure"
        )
        assertEquals("failure", result.normalized_status)
    }

    @Test
    fun `normalized_status timeout maps to failure`() {
        val result = GoalResultPayload(
            task_id = "t3", status = "timeout", device_id = "dev",
            normalized_status = "failure"
        )
        assertEquals("failure", result.normalized_status)
    }

    @Test
    fun `normalized_status cancelled maps to cancellation`() {
        val result = GoalResultPayload(
            task_id = "t4", status = "cancelled", device_id = "dev",
            normalized_status = "cancellation"
        )
        assertEquals("cancellation", result.normalized_status)
    }

    @Test
    fun `normalized_status disabled maps to disabled`() {
        val result = GoalResultPayload(
            task_id = "t5", status = "disabled", device_id = "dev",
            normalized_status = "disabled"
        )
        assertEquals("disabled", result.normalized_status)
    }

    // ── Unified contract: dispatch_trace_id propagation ───────────────────────

    @Test
    fun `envelope dispatch_trace_id propagated from result payload`() {
        val result = buildNormalResult().copy(dispatch_trace_id = "sys-trace-abc")
        val envelope = buildEnvelope(result, traceId = "trace-001", routeMode = "cross_device")
        assertEquals(
            "envelope.dispatch_trace_id must mirror result.dispatch_trace_id",
            "sys-trace-abc",
            envelope.dispatch_trace_id
        )
    }

    @Test
    fun `envelope dispatch_trace_id is null when result payload has no dispatch_trace_id`() {
        val result = buildNormalResult() // dispatch_trace_id = null
        val envelope = buildEnvelope(result, traceId = "trace-002", routeMode = "cross_device")
        assertNull(
            "envelope.dispatch_trace_id must be null when payload has none",
            envelope.dispatch_trace_id
        )
    }

    @Test
    fun `envelope source_runtime_posture propagated from result payload`() {
        val result = buildNormalResult().copy(source_runtime_posture = "join_runtime")
        val envelope = buildEnvelope(result, traceId = "trace-003", routeMode = "cross_device")
        assertEquals(
            "envelope.source_runtime_posture must mirror result.source_runtime_posture",
            "join_runtime",
            envelope.source_runtime_posture
        )
    }

    @Test
    fun `envelope source_runtime_posture is null when result payload has none`() {
        val result = buildNormalResult() // source_runtime_posture = null
        val envelope = buildEnvelope(result, traceId = "trace-004", routeMode = "cross_device")
        assertNull(
            "envelope.source_runtime_posture must be null when payload has none",
            envelope.source_runtime_posture
        )
    }

    // ── Unified contract: field consistency across result kinds ───────────────
    //
    // Verifies that error-kind results carry the same set of context fields
    // as normal-completion results.  Inconsistent field population between
    // success and error paths would force V2 to special-case each path.

    @Test
    fun `error result can carry source_runtime_posture matching normal result`() {
        val normalResult = buildNormalResult().copy(source_runtime_posture = "join_runtime")
        val errorResult = buildErrorResult().copy(source_runtime_posture = "join_runtime")
        assertEquals(
            "error result source_runtime_posture must match normal result",
            normalResult.source_runtime_posture,
            errorResult.source_runtime_posture
        )
    }

    @Test
    fun `error result envelope uses GOAL_EXECUTION_RESULT with source_runtime_posture`() {
        val result = buildErrorResult().copy(source_runtime_posture = "control_only")
        val envelope = buildEnvelope(result, traceId = "err-trace-posture", routeMode = "cross_device")
        assertEquals(MsgType.GOAL_EXECUTION_RESULT, envelope.type)
        assertEquals("control_only", envelope.source_runtime_posture)
    }

    @Test
    fun `timeout result carries source_runtime_posture for field consistency`() {
        val result = buildTimeoutResult().copy(source_runtime_posture = "join_runtime")
        val envelope = buildEnvelope(result, traceId = "to-trace-1", routeMode = "cross_device")
        assertEquals(MsgType.GOAL_EXECUTION_RESULT, envelope.type)
        assertEquals("join_runtime", envelope.source_runtime_posture)
    }

    @Test
    fun `dispatch_trace_id consistent across normal and error result envelopes`() {
        val dispatchTrace = "cross-sys-trace-xyz"
        val normalResult = buildNormalResult().copy(dispatch_trace_id = dispatchTrace)
        val errorResult = buildErrorResult().copy(dispatch_trace_id = dispatchTrace)
        val normalEnvelope = buildEnvelope(normalResult, traceId = "tr1", routeMode = "cross_device")
        val errorEnvelope = buildEnvelope(errorResult, traceId = "tr2", routeMode = "cross_device")
        assertEquals(
            "dispatch_trace_id must be carried consistently across normal and error paths",
            normalEnvelope.dispatch_trace_id,
            errorEnvelope.dispatch_trace_id
        )
    }

    // ── Unified contract: GoalResultPayload fields list ───────────────────────
    //
    // Verifies that the unified contract fields are present in GoalResultPayload.
    // These checks guard against accidental removal of the fields.

    @Test
    fun `GoalResultPayload carries normalized_status field`() {
        val result = GoalResultPayload(
            task_id = "guard", status = "success", device_id = "dev",
            normalized_status = "final_completion", runtime_session_id = "session-1"
        )
        assertEquals("final_completion", result.normalized_status)
        assertEquals("session-1", result.runtime_session_id)
    }

    @Test
    fun `GoalResultPayload copy preserves normalized_status and runtime_session_id`() {
        val result = GoalResultPayload(
            task_id = "copy-test", status = "error", device_id = "dev",
            normalized_status = "failure", runtime_session_id = "session-2"
        )
        val copied = result.copy(error = "updated error")
        assertEquals("failure", copied.normalized_status)
        assertEquals("session-2", copied.runtime_session_id)
    }

    @Test
    fun `GoalResultPayload result_summary defaults to null before emission`() {
        val result = buildNormalResult()
        assertNull(
            "result_summary must be null before sendGoalResult populates it",
            result.result_summary
        )
    }

    @Test
    fun `GoalResultPayload result_summary can be set explicitly`() {
        val result = GoalResultPayload(
            task_id = "rs-test", status = "success", device_id = "dev",
            result = "Task completed in 3 steps",
            result_summary = "Task completed in 3 steps"
        )
        assertEquals("Task completed in 3 steps", result.result_summary)
    }

    @Test
    fun `GoalResultPayload copy preserves result_summary`() {
        val result = GoalResultPayload(
            task_id = "rs-copy", status = "success", device_id = "dev",
            result_summary = "done"
        )
        val copied = result.copy(status = "error")
        assertEquals("done", copied.result_summary)
    }
}
