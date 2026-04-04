package com.ufo.galaxy.session

import com.ufo.galaxy.protocol.CancelResultPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.StepResult
import com.ufo.galaxy.runtime.SourceRuntimePosture
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [AndroidSessionContribution] — the canonical Android-side session
 * contribution envelope introduced in PR-4 (post-#533 dual-repo runtime unification).
 *
 * ## Test matrix
 *
 * ### fromGoalResult — Kind mapping
 *  - `"success"` maps to [AndroidSessionContribution.Kind.FINAL_COMPLETION].
 *  - `"cancelled"` maps to [AndroidSessionContribution.Kind.CANCELLATION].
 *  - `"disabled"` maps to [AndroidSessionContribution.Kind.DISABLED].
 *  - `"error"` maps to [AndroidSessionContribution.Kind.FAILURE].
 *  - `"timeout"` maps to [AndroidSessionContribution.Kind.FAILURE].
 *  - Unknown / custom status strings fall back to [AndroidSessionContribution.Kind.FAILURE].
 *
 * ### fromGoalResult — field propagation
 *  - taskId, correlationId, status, groupId, subtaskIndex echoed from GoalResultPayload.
 *  - stepCount derived from GoalResultPayload.steps.size.
 *  - latencyMs propagated from GoalResultPayload.latency_ms.
 *  - error propagated from GoalResultPayload.error.
 *  - deviceId / deviceRole propagated from GoalResultPayload.
 *  - source_runtime_posture propagated from GoalResultPayload.
 *  - traceId / routeMode taken from the factory call parameters.
 *
 * ### fromCancelResult — Kind mapping
 *  - was_running=true maps to [AndroidSessionContribution.Kind.CANCELLATION].
 *  - was_running=false maps to [AndroidSessionContribution.Kind.DISABLED].
 *
 * ### fromCancelResult — field propagation
 *  - taskId, correlationId, status echoed from CancelResultPayload.
 *  - groupId, subtaskIndex echoed from CancelResultPayload.
 *  - source_runtime_posture is null (cancel payloads carry no posture).
 *  - stepCount is 0, latencyMs is 0.
 *  - traceId / routeMode taken from factory call parameters.
 *
 * ### Canonical constants
 *  - STATUS_* constants match expected wire values.
 *  - ROUTE_* constants match expected routing strings.
 *
 * ### Kind.TAKEOVER_CONTINUATION is addressable
 *  - The enum entry can be constructed for future takeover result paths.
 */
class AndroidSessionContributionTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun goalResult(
        taskId: String = "t-001",
        correlationId: String? = "t-001",
        status: String = "success",
        groupId: String? = null,
        subtaskIndex: Int? = null,
        steps: List<StepResult> = emptyList(),
        latencyMs: Long = 0L,
        error: String? = null,
        deviceId: String = "dev-1",
        deviceRole: String = "phone",
        sourceRuntimePosture: String? = null
    ) = GoalResultPayload(
        task_id = taskId,
        correlation_id = correlationId,
        status = status,
        group_id = groupId,
        subtask_index = subtaskIndex,
        steps = steps,
        latency_ms = latencyMs,
        error = error,
        device_id = deviceId,
        device_role = deviceRole,
        source_runtime_posture = sourceRuntimePosture
    )

    private fun cancelResult(
        taskId: String = "c-001",
        wasRunning: Boolean = true,
        groupId: String? = null,
        subtaskIndex: Int? = null,
        deviceId: String = "dev-1"
    ) = CancelResultPayload(
        task_id = taskId,
        correlation_id = taskId,
        status = if (wasRunning) "cancelled" else "no_op",
        was_running = wasRunning,
        group_id = groupId,
        subtask_index = subtaskIndex,
        device_id = deviceId
    )

    // ── fromGoalResult: Kind mapping ──────────────────────────────────────────

    @Test
    fun `fromGoalResult success maps to FINAL_COMPLETION`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(status = "success"))
        assertEquals(AndroidSessionContribution.Kind.FINAL_COMPLETION, contrib.kind)
    }

    @Test
    fun `fromGoalResult cancelled maps to CANCELLATION`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(status = "cancelled"))
        assertEquals(AndroidSessionContribution.Kind.CANCELLATION, contrib.kind)
    }

    @Test
    fun `fromGoalResult disabled maps to DISABLED`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(status = "disabled"))
        assertEquals(AndroidSessionContribution.Kind.DISABLED, contrib.kind)
    }

    @Test
    fun `fromGoalResult error maps to FAILURE`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(status = "error"))
        assertEquals(AndroidSessionContribution.Kind.FAILURE, contrib.kind)
    }

    @Test
    fun `fromGoalResult timeout maps to FAILURE`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(status = "timeout"))
        assertEquals(AndroidSessionContribution.Kind.FAILURE, contrib.kind)
    }

    @Test
    fun `fromGoalResult unknown status maps to FAILURE`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(status = "some_future_status"))
        assertEquals(AndroidSessionContribution.Kind.FAILURE, contrib.kind)
    }

    // ── fromGoalResult: field propagation ─────────────────────────────────────

    @Test
    fun `fromGoalResult propagates taskId`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(taskId = "task-abc"))
        assertEquals("task-abc", contrib.taskId)
    }

    @Test
    fun `fromGoalResult propagates correlationId`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(correlationId = "corr-xyz"))
        assertEquals("corr-xyz", contrib.correlationId)
    }

    @Test
    fun `fromGoalResult propagates status string`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(status = "success"))
        assertEquals("success", contrib.status)
    }

    @Test
    fun `fromGoalResult propagates groupId and subtaskIndex`() {
        val contrib = AndroidSessionContribution.fromGoalResult(
            goalResult(groupId = "grp-1", subtaskIndex = 2)
        )
        assertEquals("grp-1", contrib.groupId)
        assertEquals(2, contrib.subtaskIndex)
    }

    @Test
    fun `fromGoalResult derives stepCount from steps list size`() {
        val steps = listOf(
            StepResult(step_id = "1", action = "tap", success = true),
            StepResult(step_id = "2", action = "scroll", success = true)
        )
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(steps = steps))
        assertEquals(2, contrib.stepCount)
    }

    @Test
    fun `fromGoalResult propagates latencyMs`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(latencyMs = 1234L))
        assertEquals(1234L, contrib.latencyMs)
    }

    @Test
    fun `fromGoalResult propagates error`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(error = "model failed"))
        assertEquals("model failed", contrib.error)
    }

    @Test
    fun `fromGoalResult error is null on success`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(status = "success", error = null))
        assertNull(contrib.error)
    }

    @Test
    fun `fromGoalResult propagates deviceId and deviceRole`() {
        val contrib = AndroidSessionContribution.fromGoalResult(
            goalResult(deviceId = "phone-001", deviceRole = "tablet")
        )
        assertEquals("phone-001", contrib.deviceId)
        assertEquals("tablet", contrib.deviceRole)
    }

    @Test
    fun `fromGoalResult propagates source_runtime_posture join_runtime`() {
        val contrib = AndroidSessionContribution.fromGoalResult(
            goalResult(sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME)
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, contrib.sourceRuntimePosture)
    }

    @Test
    fun `fromGoalResult propagates source_runtime_posture control_only`() {
        val contrib = AndroidSessionContribution.fromGoalResult(
            goalResult(sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY)
        )
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, contrib.sourceRuntimePosture)
    }

    @Test
    fun `fromGoalResult source_runtime_posture is null when not set`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(sourceRuntimePosture = null))
        assertNull(contrib.sourceRuntimePosture)
    }

    @Test
    fun `fromGoalResult takes traceId from parameter`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult(), traceId = "tr-abc")
        assertEquals("tr-abc", contrib.traceId)
    }

    @Test
    fun `fromGoalResult takes routeMode from parameter`() {
        val contrib = AndroidSessionContribution.fromGoalResult(
            goalResult(), routeMode = AndroidSessionContribution.ROUTE_CROSS_DEVICE
        )
        assertEquals(AndroidSessionContribution.ROUTE_CROSS_DEVICE, contrib.routeMode)
    }

    @Test
    fun `fromGoalResult traceId defaults to null`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult())
        assertNull(contrib.traceId)
    }

    @Test
    fun `fromGoalResult routeMode defaults to null`() {
        val contrib = AndroidSessionContribution.fromGoalResult(goalResult())
        assertNull(contrib.routeMode)
    }

    // ── fromCancelResult: Kind mapping ─────────────────────────────────────────

    @Test
    fun `fromCancelResult was_running true maps to CANCELLATION`() {
        val contrib = AndroidSessionContribution.fromCancelResult(cancelResult(wasRunning = true))
        assertEquals(AndroidSessionContribution.Kind.CANCELLATION, contrib.kind)
    }

    @Test
    fun `fromCancelResult was_running false maps to DISABLED`() {
        val contrib = AndroidSessionContribution.fromCancelResult(cancelResult(wasRunning = false))
        assertEquals(AndroidSessionContribution.Kind.DISABLED, contrib.kind)
    }

    // ── fromCancelResult: field propagation ────────────────────────────────────

    @Test
    fun `fromCancelResult propagates taskId`() {
        val contrib = AndroidSessionContribution.fromCancelResult(cancelResult(taskId = "cancel-99"))
        assertEquals("cancel-99", contrib.taskId)
    }

    @Test
    fun `fromCancelResult propagates groupId and subtaskIndex`() {
        val contrib = AndroidSessionContribution.fromCancelResult(
            cancelResult(groupId = "grp-cancel", subtaskIndex = 3)
        )
        assertEquals("grp-cancel", contrib.groupId)
        assertEquals(3, contrib.subtaskIndex)
    }

    @Test
    fun `fromCancelResult source_runtime_posture is always null`() {
        val contrib = AndroidSessionContribution.fromCancelResult(cancelResult())
        assertNull(contrib.sourceRuntimePosture)
    }

    @Test
    fun `fromCancelResult stepCount is zero`() {
        val contrib = AndroidSessionContribution.fromCancelResult(cancelResult())
        assertEquals(0, contrib.stepCount)
    }

    @Test
    fun `fromCancelResult latencyMs is zero`() {
        val contrib = AndroidSessionContribution.fromCancelResult(cancelResult())
        assertEquals(0L, contrib.latencyMs)
    }

    @Test
    fun `fromCancelResult propagates traceId`() {
        val contrib = AndroidSessionContribution.fromCancelResult(cancelResult(), traceId = "tr-cancel")
        assertEquals("tr-cancel", contrib.traceId)
    }

    @Test
    fun `fromCancelResult propagates routeMode`() {
        val contrib = AndroidSessionContribution.fromCancelResult(
            cancelResult(), routeMode = AndroidSessionContribution.ROUTE_CROSS_DEVICE
        )
        assertEquals(AndroidSessionContribution.ROUTE_CROSS_DEVICE, contrib.routeMode)
    }

    // ── Canonical constants ────────────────────────────────────────────────────

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

    @Test
    fun `STATUS_DISABLED constant is disabled`() {
        assertEquals("disabled", AndroidSessionContribution.STATUS_DISABLED)
    }

    @Test
    fun `ROUTE_CROSS_DEVICE constant is cross_device`() {
        assertEquals("cross_device", AndroidSessionContribution.ROUTE_CROSS_DEVICE)
    }

    @Test
    fun `ROUTE_LOCAL constant is local`() {
        assertEquals("local", AndroidSessionContribution.ROUTE_LOCAL)
    }

    // ── Kind enum completeness ─────────────────────────────────────────────────

    @Test
    fun `Kind enum has five distinct values`() {
        val kinds = AndroidSessionContribution.Kind.values()
        assertEquals(5, kinds.size)
    }

    @Test
    fun `Kind TAKEOVER_CONTINUATION is addressable`() {
        // Ensure the enum entry can be referenced for future takeover result paths.
        val kind = AndroidSessionContribution.Kind.TAKEOVER_CONTINUATION
        assertNotNull(kind)
    }

    // ── Parallel subtask contributions ─────────────────────────────────────────

    @Test
    fun `fromGoalResult subtask contribution carries group metadata`() {
        val result = goalResult(
            taskId = "sub-t-1",
            groupId = "grp-parallel",
            subtaskIndex = 0,
            status = "success"
        )
        val contrib = AndroidSessionContribution.fromGoalResult(
            result,
            traceId = "tr-parallel",
            routeMode = AndroidSessionContribution.ROUTE_CROSS_DEVICE
        )
        assertEquals(AndroidSessionContribution.Kind.FINAL_COMPLETION, contrib.kind)
        assertEquals("grp-parallel", contrib.groupId)
        assertEquals(0, contrib.subtaskIndex)
        assertEquals("tr-parallel", contrib.traceId)
    }

    // ── Disabled result from posture gate ──────────────────────────────────────

    @Test
    fun `fromGoalResult disabled result with control_only posture`() {
        val result = goalResult(
            status = "disabled",
            sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
        )
        val contrib = AndroidSessionContribution.fromGoalResult(result)
        assertEquals(AndroidSessionContribution.Kind.DISABLED, contrib.kind)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, contrib.sourceRuntimePosture)
    }
}
