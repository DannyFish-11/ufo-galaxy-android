package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.AutonomousExecutionPipeline
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.LocalCollaborationAgent
import com.ufo.galaxy.agent.LocalGoalExecutor
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-51 (PR-5B) — Android policy routing outcome structured behavioral semantics.
 *
 * Validates that policy routing outcomes now carry true behavioral semantics beyond
 * classification:
 * - REJECTED produces a structured result with [GoalResultPayload.policy_rejection_reason]
 *   echoed from [GoalExecutionPayload.policy_failure_reason].
 * - TEMPORARILY_UNAVAILABLE produces a [AutonomousExecutionPipeline.STATUS_PENDING] hold
 *   result with [GoalResultPayload.is_hold_pending] set to `true`.
 * - RESUMED produces a success result with [GoalResultPayload.is_continuation] set to `true`.
 * - Legacy paths (null outcome) are unaffected and remain forward compatible.
 *
 * ## Sections
 *
 * ### GoalResultPayload — PR-51 behavioral result fields
 *  - policy_rejection_reason defaults to null (backward compatibility)
 *  - is_hold_pending defaults to null (backward compatibility)
 *  - is_continuation defaults to null (backward compatibility)
 *  - all PR-51 fields can be set simultaneously
 *
 * ### AutonomousExecutionPipeline — REJECTED structured result
 *  - REJECTED result status is STATUS_DISABLED
 *  - REJECTED result error is REASON_POLICY_REJECTED
 *  - REJECTED policy_rejection_reason echoes policy_failure_reason from payload
 *  - REJECTED policy_rejection_reason is null when policy_failure_reason is null
 *  - REJECTED policy_rejection_reason echoes known failure reason
 *  - REJECTED policy_rejection_reason echoes unknown future failure reason (forward compat)
 *  - REJECTED result echoes policy_routing_outcome
 *
 * ### AutonomousExecutionPipeline — TEMPORARILY_UNAVAILABLE hold semantics
 *  - TEMPORARILY_UNAVAILABLE result status is STATUS_PENDING (not STATUS_DISABLED)
 *  - TEMPORARILY_UNAVAILABLE result error is REASON_POLICY_HOLD
 *  - TEMPORARILY_UNAVAILABLE result is_hold_pending is true
 *  - TEMPORARILY_UNAVAILABLE result echoes policy_routing_outcome
 *  - TEMPORARILY_UNAVAILABLE result echoes task_id
 *  - TEMPORARILY_UNAVAILABLE result echoes group_id and subtask_index
 *  - TEMPORARILY_UNAVAILABLE result echoes continuity_token
 *  - TEMPORARILY_UNAVAILABLE result echoes dispatch_trace_id
 *  - TEMPORARILY_UNAVAILABLE result latency_ms is zero (hold is not an execution)
 *  - TEMPORARILY_UNAVAILABLE result policy_rejection_reason is null
 *  - TEMPORARILY_UNAVAILABLE result is_continuation is null
 *
 * ### AutonomousExecutionPipeline — RESUMED continuation-aware behavior
 *  - RESUMED result is_continuation is true
 *  - RESUMED result is NOT STATUS_DISABLED (execution proceeds)
 *  - RESUMED result is NOT STATUS_PENDING (not a hold)
 *  - RESUMED result echoes continuity_token
 *  - RESUMED result echoes is_resumable
 *  - RESUMED result echoes policy_routing_outcome
 *  - RESUMED is_continuation is null for non-resumed outcome (ACCEPTED)
 *  - RESUMED is_continuation is null for null/legacy outcome
 *
 * ### AutonomousExecutionPipeline — parallel_subtask REJECTED structured result
 *  - parallel REJECTED result status is STATUS_DISABLED
 *  - parallel REJECTED policy_rejection_reason echoes policy_failure_reason
 *
 * ### AutonomousExecutionPipeline — parallel_subtask TEMPORARILY_UNAVAILABLE hold
 *  - parallel TEMPORARILY_UNAVAILABLE result status is STATUS_PENDING
 *  - parallel TEMPORARILY_UNAVAILABLE result is_hold_pending is true
 *
 * ### AutonomousExecutionPipeline — parallel_subtask RESUMED continuation
 *  - parallel RESUMED result is_continuation is true
 *
 * ### AutonomousExecutionPipeline — legacy path (null outcome) unaffected
 *  - null outcome result is not STATUS_PENDING
 *  - null outcome result is_hold_pending is null
 *  - null outcome result is_continuation is null
 *  - null outcome result policy_rejection_reason is null
 *
 * ### StabilizationBaseline — PR-51 entries registered
 *  - goal-result-payload-policy-rejection-reason is CANONICAL_STABLE
 *  - goal-result-payload-is-hold-pending is CANONICAL_STABLE
 *  - goal-result-payload-is-continuation is CANONICAL_STABLE
 *  - autonomous-execution-pipeline-status-pending is CANONICAL_STABLE
 *  - all PR-51 entries have introducedPr = 51
 *  - all PR-51 entries have EXTEND guidance
 */
class Pr51PolicyRoutingSemanticBehaviorTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeScreenshot : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        override fun screenWidth() = 1080
        override fun screenHeight() = 2340
    }

    private class OkPlanner : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep("tap", "tap the button"))
            )
        override fun replan(
            goal: String, constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String, screenshotBase64: String?
        ) = LocalPlannerService.PlanResult(steps = emptyList(), error = "replan not supported")
    }

    private class OkGrounder : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(x = 540, y = 960, confidence = 0.9f, element_description = "button")
    }

    private class OkAccessibility : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    private fun buildPipeline(
        goalExecutionEnabled: Boolean = true,
        parallelExecutionEnabled: Boolean = true,
        crossDeviceEnabled: Boolean = true,
        deviceId: String = "test-device",
        deviceRole: String = "phone"
    ): AutonomousExecutionPipeline {
        val settings = InMemoryAppSettings(
            goalExecutionEnabled = goalExecutionEnabled,
            parallelExecutionEnabled = parallelExecutionEnabled,
            crossDeviceEnabled = crossDeviceEnabled,
            deviceRole = deviceRole
        )
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshot(),
            plannerService = OkPlanner(),
            groundingService = OkGrounder(),
            accessibilityExecutor = OkAccessibility()
        )
        val goalExec = LocalGoalExecutor(edge, deviceId)
        val collab = LocalCollaborationAgent(goalExec)
        return AutonomousExecutionPipeline(
            settings = settings,
            goalExecutor = goalExec,
            collaborationAgent = collab,
            deviceId = deviceId,
            deviceRole = deviceRole
        )
    }

    private fun goalPayload(
        taskId: String = "t-5b",
        goal: String = "open WeChat",
        posture: String? = SourceRuntimePosture.JOIN_RUNTIME,
        policyRoutingOutcome: String? = null,
        policyFailureReason: String? = null,
        continuityToken: String? = null,
        isResumable: Boolean? = null,
        dispatchTraceId: String? = null,
        groupId: String? = null,
        subtaskIndex: Int? = null
    ) = GoalExecutionPayload(
        task_id = taskId,
        goal = goal,
        source_runtime_posture = posture,
        policy_routing_outcome = policyRoutingOutcome,
        policy_failure_reason = policyFailureReason,
        continuity_token = continuityToken,
        is_resumable = isResumable,
        dispatch_trace_id = dispatchTraceId,
        group_id = groupId,
        subtask_index = subtaskIndex,
        max_steps = 5
    )

    // ── GoalResultPayload — PR-51 behavioral result fields ────────────────────

    @Test
    fun `GoalResultPayload policy_rejection_reason defaults to null`() {
        val result = GoalResultPayload(task_id = "t-r1", status = "success")
        assertNull(
            "policy_rejection_reason must default to null for backward compatibility",
            result.policy_rejection_reason
        )
    }

    @Test
    fun `GoalResultPayload is_hold_pending defaults to null`() {
        val result = GoalResultPayload(task_id = "t-r2", status = "success")
        assertNull(
            "is_hold_pending must default to null for backward compatibility",
            result.is_hold_pending
        )
    }

    @Test
    fun `GoalResultPayload is_continuation defaults to null`() {
        val result = GoalResultPayload(task_id = "t-r3", status = "success")
        assertNull(
            "is_continuation must default to null for backward compatibility",
            result.is_continuation
        )
    }

    @Test
    fun `GoalResultPayload all PR-51 fields can be set simultaneously`() {
        val result = GoalResultPayload(
            task_id = "t-r4",
            status = "disabled",
            policy_rejection_reason = "target_rejected",
            is_hold_pending = true,
            is_continuation = true
        )
        assertEquals("target_rejected", result.policy_rejection_reason)
        assertEquals(true, result.is_hold_pending)
        assertEquals(true, result.is_continuation)
    }

    // ── AutonomousExecutionPipeline — REJECTED structured result ──────────────

    @Test
    fun `REJECTED result status is STATUS_DISABLED`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
        )
        assertEquals(
            "REJECTED outcome must produce STATUS_DISABLED — Android must not execute policy-rejected task",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }

    @Test
    fun `REJECTED result error is REASON_POLICY_REJECTED`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
        )
        assertEquals(
            "REJECTED outcome must carry REASON_POLICY_REJECTED error so V2 distinguishes it",
            AutonomousExecutionPipeline.REASON_POLICY_REJECTED,
            result.error
        )
    }

    @Test
    fun `REJECTED policy_rejection_reason echoes policy_failure_reason from payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue,
                policyFailureReason = PolicyRoutingContext.UNAVAILABILITY_REASON_TARGET_REJECTED
            )
        )
        assertEquals(
            "REJECTED result must carry policy_rejection_reason echoed from policy_failure_reason " +
                "so V2 can re-route with a structured machine-readable reason",
            PolicyRoutingContext.UNAVAILABILITY_REASON_TARGET_REJECTED,
            result.policy_rejection_reason
        )
    }

    @Test
    fun `REJECTED policy_rejection_reason is null when policy_failure_reason is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue,
                policyFailureReason = null
            )
        )
        assertNull(
            "REJECTED result policy_rejection_reason must be null when no policy_failure_reason is present",
            result.policy_rejection_reason
        )
    }

    @Test
    fun `REJECTED policy_rejection_reason echoes known failure reason reconnecting`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue,
                policyFailureReason = PolicyRoutingContext.UNAVAILABILITY_REASON_RECONNECTING
            )
        )
        assertEquals(
            "REJECTED policy_rejection_reason must echo the canonical reconnecting reason",
            PolicyRoutingContext.UNAVAILABILITY_REASON_RECONNECTING,
            result.policy_rejection_reason
        )
    }

    @Test
    fun `REJECTED policy_rejection_reason echoes unknown future failure reason (forward compat)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue,
                policyFailureReason = "future_rejection_reason_v3"
            )
        )
        assertEquals(
            "REJECTED policy_rejection_reason must echo unknown future reasons (forward compatibility)",
            "future_rejection_reason_v3",
            result.policy_rejection_reason
        )
    }

    @Test
    fun `REJECTED result echoes policy_routing_outcome`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
        )
        assertEquals(
            "REJECTED result must echo policy_routing_outcome for full-chain policy correlation",
            PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue,
            result.policy_routing_outcome
        )
    }

    @Test
    fun `REJECTED result is_hold_pending is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
        )
        assertNull(
            "REJECTED result must not set is_hold_pending — hold semantics are for TEMPORARILY_UNAVAILABLE",
            result.is_hold_pending
        )
    }

    @Test
    fun `REJECTED result is_continuation is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
        )
        assertNull(
            "REJECTED result must not set is_continuation — continuation semantics are for RESUMED",
            result.is_continuation
        )
    }

    // ── AutonomousExecutionPipeline — TEMPORARILY_UNAVAILABLE hold semantics ──

    @Test
    fun `TEMPORARILY_UNAVAILABLE result status is STATUS_PENDING`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE outcome must produce STATUS_PENDING — this is a non-terminal hold, " +
                "not a terminal failure; V2 must retry when readiness is restored",
            AutonomousExecutionPipeline.STATUS_PENDING,
            result.status
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result status is NOT STATUS_DISABLED`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertFalse(
            "TEMPORARILY_UNAVAILABLE must not produce STATUS_DISABLED — it has independent hold semantics",
            result.status == AutonomousExecutionPipeline.STATUS_DISABLED
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result error is REASON_POLICY_HOLD`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE result must carry REASON_POLICY_HOLD error reason",
            AutonomousExecutionPipeline.REASON_POLICY_HOLD,
            result.error
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result is_hold_pending is true`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE result must carry is_hold_pending=true so V2 can unambiguously " +
                "distinguish a temporary hold from a terminal failure",
            true,
            result.is_hold_pending
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result echoes policy_routing_outcome`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE result must echo policy_routing_outcome for full-chain correlation",
            PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue,
            result.policy_routing_outcome
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result echoes task_id`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                taskId = "t-hold-taskid",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue
            )
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE result must echo task_id for gateway aggregation",
            "t-hold-taskid",
            result.task_id
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result echoes group_id and subtask_index`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue,
                groupId = "grp-hold",
                subtaskIndex = 2
            )
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE result must echo group_id for gateway aggregation",
            "grp-hold",
            result.group_id
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE result must echo subtask_index for gateway aggregation",
            2,
            result.subtask_index
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result echoes continuity_token`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue,
                continuityToken = "ct-hold-001"
            )
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE result must echo continuity_token for V2 context correlation",
            "ct-hold-001",
            result.continuity_token
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result echoes dispatch_trace_id`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue,
                dispatchTraceId = "dt-hold-001"
            )
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE result must echo dispatch_trace_id for full-chain observability",
            "dt-hold-001",
            result.dispatch_trace_id
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result latency_ms is zero`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE hold result must have latency_ms=0 — no execution was performed",
            0L,
            result.latency_ms
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result policy_rejection_reason is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertNull(
            "TEMPORARILY_UNAVAILABLE result must not set policy_rejection_reason — only REJECTED sets this",
            result.policy_rejection_reason
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result is_continuation is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertNull(
            "TEMPORARILY_UNAVAILABLE result must not set is_continuation — only RESUMED sets this",
            result.is_continuation
        )
    }

    // ── AutonomousExecutionPipeline — RESUMED continuation-aware behavior ─────

    @Test
    fun `RESUMED result is_continuation is true`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                continuityToken = "ct-resume-001",
                isResumable = true
            )
        )
        assertEquals(
            "RESUMED outcome must set is_continuation=true in result so V2 knows Android did not " +
                "reset accumulated task state and treated this as a continuation",
            true,
            result.is_continuation
        )
    }

    @Test
    fun `RESUMED result is NOT STATUS_DISABLED`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                continuityToken = "ct-resume-002",
                isResumable = true
            )
        )
        assertFalse(
            "RESUMED execution must not be blocked — Android must proceed with the continuation",
            result.status == AutonomousExecutionPipeline.STATUS_DISABLED
        )
    }

    @Test
    fun `RESUMED result is NOT STATUS_PENDING`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                continuityToken = "ct-resume-003",
                isResumable = true
            )
        )
        assertFalse(
            "RESUMED execution must not be a hold — it is an active continuation",
            result.status == AutonomousExecutionPipeline.STATUS_PENDING
        )
    }

    @Test
    fun `RESUMED result echoes continuity_token`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                continuityToken = "ct-resume-004"
            )
        )
        assertEquals(
            "RESUMED result must echo continuity_token so V2 can correlate continuation",
            "ct-resume-004",
            result.continuity_token
        )
    }

    @Test
    fun `RESUMED result echoes is_resumable`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                isResumable = true
            )
        )
        assertEquals(
            "RESUMED result must echo is_resumable from the originating payload",
            true,
            result.is_resumable
        )
    }

    @Test
    fun `RESUMED result echoes policy_routing_outcome`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                continuityToken = "ct-resume-005"
            )
        )
        assertEquals(
            "RESUMED result must echo policy_routing_outcome for full-chain policy correlation",
            PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
            result.policy_routing_outcome
        )
    }

    @Test
    fun `is_continuation is null for ACCEPTED outcome (not a resumed execution)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.ACCEPTED.wireValue)
        )
        assertNull(
            "ACCEPTED outcome must not set is_continuation — only RESUMED executions are continuations",
            result.is_continuation
        )
    }

    @Test
    fun `is_continuation is null for null legacy outcome`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = null)
        )
        assertNull(
            "Legacy null outcome must not set is_continuation — backward compatibility with legacy senders",
            result.is_continuation
        )
    }

    @Test
    fun `RESUMED result policy_rejection_reason is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                continuityToken = "ct-resume-006"
            )
        )
        assertNull(
            "RESUMED result must not set policy_rejection_reason — only REJECTED sets this",
            result.policy_rejection_reason
        )
    }

    @Test
    fun `RESUMED result is_hold_pending is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                continuityToken = "ct-resume-007"
            )
        )
        assertNull(
            "RESUMED result must not set is_hold_pending — only TEMPORARILY_UNAVAILABLE sets this",
            result.is_hold_pending
        )
    }

    // ── AutonomousExecutionPipeline — parallel_subtask REJECTED ───────────────

    @Test
    fun `parallel REJECTED result status is STATUS_DISABLED`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-rej",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue,
                groupId = "grp-5b",
                subtaskIndex = 0
            )
        )
        assertEquals(
            "Parallel REJECTED outcome must produce STATUS_DISABLED",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }

    @Test
    fun `parallel REJECTED policy_rejection_reason echoes policy_failure_reason`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-rej-reason",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue,
                policyFailureReason = PolicyRoutingContext.UNAVAILABILITY_REASON_NOT_ATTACHED,
                groupId = "grp-5b",
                subtaskIndex = 0
            )
        )
        assertEquals(
            "Parallel REJECTED result must echo policy_failure_reason as policy_rejection_reason",
            PolicyRoutingContext.UNAVAILABILITY_REASON_NOT_ATTACHED,
            result.policy_rejection_reason
        )
    }

    // ── AutonomousExecutionPipeline — parallel_subtask TEMPORARILY_UNAVAILABLE ──

    @Test
    fun `parallel TEMPORARILY_UNAVAILABLE result status is STATUS_PENDING`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-hold",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue,
                groupId = "grp-5b",
                subtaskIndex = 1
            )
        )
        assertEquals(
            "Parallel TEMPORARILY_UNAVAILABLE outcome must produce STATUS_PENDING",
            AutonomousExecutionPipeline.STATUS_PENDING,
            result.status
        )
    }

    @Test
    fun `parallel TEMPORARILY_UNAVAILABLE result is_hold_pending is true`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-hold-2",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue,
                groupId = "grp-5b",
                subtaskIndex = 1
            )
        )
        assertEquals(
            "Parallel TEMPORARILY_UNAVAILABLE result must carry is_hold_pending=true",
            true,
            result.is_hold_pending
        )
    }

    // ── AutonomousExecutionPipeline — parallel_subtask RESUMED ────────────────

    @Test
    fun `parallel RESUMED result is_continuation is true`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-resumed",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                continuityToken = "ct-par-resume",
                isResumable = true,
                groupId = "grp-5b",
                subtaskIndex = 2
            )
        )
        assertEquals(
            "Parallel RESUMED result must carry is_continuation=true",
            true,
            result.is_continuation
        )
    }

    // ── AutonomousExecutionPipeline — legacy path (null outcome) unaffected ───

    @Test
    fun `null outcome result is not STATUS_PENDING`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(policyRoutingOutcome = null))
        assertFalse(
            "Legacy null outcome must not produce STATUS_PENDING — backward compatibility must be maintained",
            result.status == AutonomousExecutionPipeline.STATUS_PENDING
        )
    }

    @Test
    fun `null outcome result is_hold_pending is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(policyRoutingOutcome = null))
        assertNull(
            "Legacy null outcome must not set is_hold_pending",
            result.is_hold_pending
        )
    }

    @Test
    fun `null outcome result is_continuation is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(policyRoutingOutcome = null))
        assertNull(
            "Legacy null outcome must not set is_continuation",
            result.is_continuation
        )
    }

    @Test
    fun `null outcome result policy_rejection_reason is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(policyRoutingOutcome = null))
        assertNull(
            "Legacy null outcome must not set policy_rejection_reason",
            result.policy_rejection_reason
        )
    }

    // ── AutonomousExecutionPipeline — STATUS_PENDING and REASON_POLICY_HOLD constants ──

    @Test
    fun `STATUS_PENDING constant value is pending`() {
        assertEquals(
            "STATUS_PENDING wire value must be \"pending\" for stable V2 contract",
            "pending",
            AutonomousExecutionPipeline.STATUS_PENDING
        )
    }

    @Test
    fun `REASON_POLICY_HOLD constant value is policy_routing_hold`() {
        assertEquals(
            "REASON_POLICY_HOLD wire value must be \"policy_routing_hold\" for stable V2 contract",
            "policy_routing_hold",
            AutonomousExecutionPipeline.REASON_POLICY_HOLD
        )
    }

    // ── StabilizationBaseline — PR-51 entries registered ─────────────────────

    @Test
    fun `goal-result-payload-policy-rejection-reason is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("goal-result-payload-policy-rejection-reason")
        assertNotNull(
            "goal-result-payload-policy-rejection-reason must be registered in StabilizationBaseline",
            entry
        )
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `goal-result-payload-is-hold-pending is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("goal-result-payload-is-hold-pending")
        assertNotNull(
            "goal-result-payload-is-hold-pending must be registered in StabilizationBaseline",
            entry
        )
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `goal-result-payload-is-continuation is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("goal-result-payload-is-continuation")
        assertNotNull(
            "goal-result-payload-is-continuation must be registered in StabilizationBaseline",
            entry
        )
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `autonomous-execution-pipeline-status-pending is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("autonomous-execution-pipeline-status-pending")
        assertNotNull(
            "autonomous-execution-pipeline-status-pending must be registered in StabilizationBaseline",
            entry
        )
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `all PR-51 entries have introducedPr equal to 51`() {
        val pr51Ids = listOf(
            "goal-result-payload-policy-rejection-reason",
            "goal-result-payload-is-hold-pending",
            "goal-result-payload-is-continuation",
            "autonomous-execution-pipeline-status-pending"
        )
        pr51Ids.forEach { id ->
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Entry '$id' must be registered in StabilizationBaseline", entry)
            assertEquals(
                "Entry '$id' must have introducedPr = 51",
                51,
                entry!!.introducedPr
            )
        }
    }

    @Test
    fun `all PR-51 entries have EXTEND guidance`() {
        val pr51Ids = listOf(
            "goal-result-payload-policy-rejection-reason",
            "goal-result-payload-is-hold-pending",
            "goal-result-payload-is-continuation",
            "autonomous-execution-pipeline-status-pending"
        )
        pr51Ids.forEach { id ->
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Entry '$id' must be registered in StabilizationBaseline", entry)
            assertEquals(
                "Entry '$id' must have EXTEND guidance",
                StabilizationBaseline.ExtensionGuidance.EXTEND,
                entry!!.extensionGuidance
            )
        }
    }
}
