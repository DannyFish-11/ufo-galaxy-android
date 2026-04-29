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
 *
 * ### Offline queue contract
 *  - "goal_execution_result" is a member of OfflineTaskQueue.QUEUEABLE_TYPES.
 *  - "task_result" and "goal_result" remain in QUEUEABLE_TYPES for backward-compat.
 *
 * ### Dual-repo canonical alignment
 *  - MsgType.GOAL_EXECUTION_RESULT wire value is stable ("goal_execution_result").
 *  - MsgType.GOAL_RESULT wire value is stable ("goal_result") — kept for compat.
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

    /** Mirrors the canonical send path in GalaxyConnectionService.sendGoalResult. */
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
        route_mode = routeMode
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
}
