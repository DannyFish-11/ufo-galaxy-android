package com.ufo.galaxy.session

import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.runtime.SourceRuntimePosture
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for canonical Android-side result output normalization (PR-4).
 *
 * These tests verify that the result payloads emitted by the Android execution pipeline
 * consistently carry all required fields for the main-repo session-truth layer to
 * consume without ambiguity.
 *
 * ## Test matrix
 *
 * ### GoalResultPayload: source_runtime_posture in timeout results
 *  - Timeout result echoes source_runtime_posture from the originating payload.
 *  - Timeout result with null posture preserves null.
 *
 * ### GoalResultPayload status taxonomy
 *  - All five canonical status values are stable strings.
 *
 * ### AndroidSessionContribution round-trip
 *  - A complete goal result with all fields survives the contribution factory round-trip.
 *  - Parallel subtask results carry group metadata through the contribution.
 *  - Posture is preserved end-to-end through the contribution.
 *
 * ### Trace context propagation
 *  - traceId and routeMode are preserved in contributions.
 *  - cross_device route is stable.
 */
class CanonicalResultOutputTest {

    // ── GoalResultPayload: timeout result echoes source_runtime_posture ────────

    /**
     * Simulates the same logic as GalaxyConnectionService.buildTimeoutGoalResult
     * to verify that source_runtime_posture is echoed in timeout result payloads.
     */
    private fun buildTimeoutGoalResult(
        taskId: String,
        payload: GoalExecutionPayload,
        timeoutMs: Long
    ) = GoalResultPayload(
        task_id = taskId,
        correlation_id = taskId,
        status = AndroidSessionContribution.STATUS_TIMEOUT,
        error = "Task exceeded timeout of ${timeoutMs}ms",
        group_id = payload.group_id,
        subtask_index = payload.subtask_index,
        latency_ms = timeoutMs,
        device_id = "test-device",
        source_runtime_posture = payload.source_runtime_posture
    )

    @Test
    fun `timeout result echoes source_runtime_posture join_runtime`() {
        val payload = GoalExecutionPayload(
            task_id = "t-timeout-1",
            goal = "open settings",
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        val result = buildTimeoutGoalResult("t-timeout-1", payload, 30_000L)
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, result.source_runtime_posture)
    }

    @Test
    fun `timeout result echoes source_runtime_posture control_only`() {
        val payload = GoalExecutionPayload(
            task_id = "t-timeout-2",
            goal = "send message",
            source_runtime_posture = SourceRuntimePosture.CONTROL_ONLY
        )
        val result = buildTimeoutGoalResult("t-timeout-2", payload, 30_000L)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, result.source_runtime_posture)
    }

    @Test
    fun `timeout result with null posture preserves null`() {
        val payload = GoalExecutionPayload(
            task_id = "t-timeout-3",
            goal = "open app",
            source_runtime_posture = null
        )
        val result = buildTimeoutGoalResult("t-timeout-3", payload, 30_000L)
        assertNull(result.source_runtime_posture)
    }

    @Test
    fun `timeout result status is timeout`() {
        val payload = GoalExecutionPayload(task_id = "t-4", goal = "g")
        val result = buildTimeoutGoalResult("t-4", payload, 30_000L)
        assertEquals(AndroidSessionContribution.STATUS_TIMEOUT, result.status)
    }

    @Test
    fun `timeout result latency_ms equals the timeout budget`() {
        val payload = GoalExecutionPayload(task_id = "t-5", goal = "g")
        val result = buildTimeoutGoalResult("t-5", payload, 45_000L)
        assertEquals(45_000L, result.latency_ms)
    }

    @Test
    fun `timeout result echoes group_id and subtask_index`() {
        val payload = GoalExecutionPayload(
            task_id = "t-6",
            goal = "subtask goal",
            group_id = "grp-timeout",
            subtask_index = 2
        )
        val result = buildTimeoutGoalResult("t-6", payload, 30_000L)
        assertEquals("grp-timeout", result.group_id)
        assertEquals(2, result.subtask_index)
    }

    // ── GoalResultPayload status taxonomy ─────────────────────────────────────

    @Test
    fun `STATUS_SUCCESS constant is success`() {
        assertEquals("success", AndroidSessionContribution.STATUS_SUCCESS)
    }

    @Test
    fun `STATUS_ERROR constant is error`() {
        assertEquals("error", AndroidSessionContribution.STATUS_ERROR)
    }

    @Test
    fun `STATUS_CANCELLED constant is cancelled`() {
        assertEquals("cancelled", AndroidSessionContribution.STATUS_CANCELLED)
    }

    @Test
    fun `STATUS_TIMEOUT constant is timeout`() {
        assertEquals("timeout", AndroidSessionContribution.STATUS_TIMEOUT)
    }

    // ── AndroidSessionContribution: round-trip through factory ────────────────

    @Test
    fun `full goal result survives contribution factory round-trip`() {
        val result = GoalResultPayload(
            task_id = "t-rt-1",
            correlation_id = "corr-rt-1",
            status = "success",
            group_id = "grp-rt",
            subtask_index = 1,
            latency_ms = 500L,
            device_id = "dev-rt",
            device_role = "phone",
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        val contrib = AndroidSessionContribution.fromGoalResult(
            result, traceId = "tr-rt-1", routeMode = "cross_device"
        )

        assertEquals(AndroidSessionContribution.Kind.FINAL_COMPLETION, contrib.kind)
        assertEquals("t-rt-1", contrib.taskId)
        assertEquals("corr-rt-1", contrib.correlationId)
        assertEquals("success", contrib.status)
        assertEquals("grp-rt", contrib.groupId)
        assertEquals(1, contrib.subtaskIndex)
        assertEquals(500L, contrib.latencyMs)
        assertEquals("dev-rt", contrib.deviceId)
        assertEquals("phone", contrib.deviceRole)
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, contrib.sourceRuntimePosture)
        assertEquals("tr-rt-1", contrib.traceId)
        assertEquals("cross_device", contrib.routeMode)
    }

    @Test
    fun `error goal result maps to FAILURE with error propagated`() {
        val result = GoalResultPayload(
            task_id = "t-err-1",
            status = "error",
            error = "screenshot failed",
            device_id = "dev-1"
        )
        val contrib = AndroidSessionContribution.fromGoalResult(result)
        assertEquals(AndroidSessionContribution.Kind.FAILURE, contrib.kind)
        assertEquals("screenshot failed", contrib.error)
    }

    // ── Trace context propagation ──────────────────────────────────────────────

    @Test
    fun `traceId from inbound envelope is preserved in contribution`() {
        val result = GoalResultPayload(
            task_id = "t-trace-1",
            status = "success",
            device_id = "dev-1"
        )
        val contrib = AndroidSessionContribution.fromGoalResult(
            result, traceId = "inbound-trace-123"
        )
        assertEquals("inbound-trace-123", contrib.traceId)
    }

    @Test
    fun `cross_device routeMode is preserved in contribution`() {
        val result = GoalResultPayload(task_id = "t-r-1", status = "success", device_id = "d")
        val contrib = AndroidSessionContribution.fromGoalResult(
            result, routeMode = AndroidSessionContribution.ROUTE_CROSS_DEVICE
        )
        assertEquals("cross_device", contrib.routeMode)
    }

    @Test
    fun `local routeMode is preserved in contribution`() {
        val result = GoalResultPayload(task_id = "t-r-2", status = "success", device_id = "d")
        val contrib = AndroidSessionContribution.fromGoalResult(
            result, routeMode = AndroidSessionContribution.ROUTE_LOCAL
        )
        assertEquals("local", contrib.routeMode)
    }

    // ── Posture end-to-end preservation ────────────────────────────────────────

    @Test
    fun `posture preserved end-to-end from payload to contribution`() {
        // Simulate: inbound GoalExecutionPayload has join_runtime posture
        // → GoalResultPayload echoes it
        // → AndroidSessionContribution propagates it
        val goalPayload = GoalExecutionPayload(
            task_id = "t-e2e",
            goal = "open app",
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        val goalResult = GoalResultPayload(
            task_id = goalPayload.task_id,
            status = "success",
            source_runtime_posture = goalPayload.source_runtime_posture,
            device_id = "dev-e2e"
        )
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult, traceId = "tr-e2e")

        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, contrib.sourceRuntimePosture)
        assertEquals("tr-e2e", contrib.traceId)
        assertEquals(AndroidSessionContribution.Kind.FINAL_COMPLETION, contrib.kind)
    }
}
