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
 * PR-5B — Android policy routing outcome behavioral semantics.
 *
 * Validates that each [PolicyRoutingContext.RoutingOutcome] is now backed by a real
 * behavioral contract in [AutonomousExecutionPipeline] and [GoalResultPayload], so
 * V2 can stably consume structured results rather than relying on generic error strings.
 *
 * ## Coverage
 *
 * ### PolicyRoutingContext — structured result constants (PR-5B)
 *  - RESULT_STATUS_HOLD is "hold"
 *  - RESULT_HOLD_REASON_TEMPORARILY_UNAVAILABLE is "policy_temporarily_unavailable"
 *  - isHoldStatus("hold") is true
 *  - isHoldStatus("disabled") is false
 *  - isHoldStatus(null) is false
 *
 * ### GoalResultPayload — PR-5B structured fields
 *  - policy_rejection_detail defaults to null (backward compatibility)
 *  - hold_reason defaults to null (backward compatibility)
 *  - is_continuation defaults to null (backward compatibility)
 *  - policy_rejection_detail can be set
 *  - hold_reason can be set
 *  - is_continuation can be set to true
 *
 * ### AutonomousExecutionPipeline — STATUS_HOLD constant
 *  - STATUS_HOLD is "hold"
 *  - REASON_TEMPORARILY_UNAVAILABLE is "policy_temporarily_unavailable"
 *
 * ### AutonomousExecutionPipeline — REJECTED structured result
 *  - REJECTED result status is STATUS_DISABLED (backward-compatible)
 *  - REJECTED result error is REASON_POLICY_REJECTED (backward-compatible)
 *  - REJECTED result has non-null policy_rejection_detail
 *  - REJECTED result policy_rejection_detail echoes policy_failure_reason from payload
 *  - REJECTED result policy_rejection_detail defaults to REASON_POLICY_REJECTED when policy_failure_reason is null
 *  - REJECTED result is_continuation is null (not a continuation)
 *  - REJECTED result hold_reason is null (not a hold)
 *  - REJECTED result echoes policy_routing_outcome
 *
 * ### AutonomousExecutionPipeline — TEMPORARILY_UNAVAILABLE hold semantics
 *  - TEMPORARILY_UNAVAILABLE result status is STATUS_HOLD (non-terminal hold)
 *  - TEMPORARILY_UNAVAILABLE result status is distinct from STATUS_DISABLED
 *  - TEMPORARILY_UNAVAILABLE result status is distinct from STATUS_ERROR
 *  - TEMPORARILY_UNAVAILABLE result hold_reason is REASON_TEMPORARILY_UNAVAILABLE
 *  - TEMPORARILY_UNAVAILABLE result error is null (not a failure)
 *  - TEMPORARILY_UNAVAILABLE result echoes task_id
 *  - TEMPORARILY_UNAVAILABLE result echoes group_id and subtask_index
 *  - TEMPORARILY_UNAVAILABLE result echoes device_id and device_role
 *  - TEMPORARILY_UNAVAILABLE result echoes policy_routing_outcome
 *  - TEMPORARILY_UNAVAILABLE result echoes continuity_token and is_resumable
 *  - TEMPORARILY_UNAVAILABLE result is_continuation is null (not a continuation)
 *  - TEMPORARILY_UNAVAILABLE result policy_rejection_detail is null
 *  - PolicyRoutingContext.isHoldStatus matches TEMPORARILY_UNAVAILABLE result status
 *
 * ### AutonomousExecutionPipeline — RESUMED continuation-aware behavior
 *  - RESUMED result is_continuation is true
 *  - RESUMED result echoes continuity_token
 *  - RESUMED result is NOT STATUS_DISABLED or STATUS_HOLD (proceeds to execution)
 *  - non-RESUMED (ACCEPTED) result is_continuation is null
 *  - null outcome result is_continuation is null (legacy backward compat)
 *  - RESUMED result echoes policy_routing_outcome
 *
 * ### AutonomousExecutionPipeline — parallel_subtask REJECTED, TEMPORARILY_UNAVAILABLE, RESUMED
 *  - parallel REJECTED result has non-null policy_rejection_detail
 *  - parallel TEMPORARILY_UNAVAILABLE result is STATUS_HOLD
 *  - parallel TEMPORARILY_UNAVAILABLE hold_reason is REASON_TEMPORARILY_UNAVAILABLE
 *  - parallel RESUMED result is_continuation is true
 *
 * ### StabilizationBaseline — PR-5B entries registered
 *  - policy-outcome-hold-result is registered
 *  - policy-outcome-rejection-detail is registered
 *  - policy-outcome-continuation-marker is registered
 *  - autonomous-execution-pipeline-hold-status is registered
 *  - all PR-5B entries have introducedPr = 5
 *  - all PR-5B entries have CANONICAL_STABLE stability
 */
class Pr5bPolicyOutcomeBehaviorTest {

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
        deviceId: String = "test-device-5b",
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
        groupId: String? = null,
        subtaskIndex: Int? = null
    ) = GoalExecutionPayload(
        task_id = taskId,
        goal = goal,
        group_id = groupId,
        subtask_index = subtaskIndex,
        max_steps = 5,
        source_runtime_posture = posture,
        policy_routing_outcome = policyRoutingOutcome,
        policy_failure_reason = policyFailureReason,
        continuity_token = continuityToken,
        is_resumable = isResumable
    )

    // ── PolicyRoutingContext — structured result constants ─────────────────────

    @Test
    fun `RESULT_STATUS_HOLD is hold`() {
        assertEquals(
            "PolicyRoutingContext.RESULT_STATUS_HOLD must be \"hold\" for stable V2 wire contract",
            "hold",
            PolicyRoutingContext.RESULT_STATUS_HOLD
        )
    }

    @Test
    fun `RESULT_HOLD_REASON_TEMPORARILY_UNAVAILABLE is policy_temporarily_unavailable`() {
        assertEquals(
            "RESULT_HOLD_REASON_TEMPORARILY_UNAVAILABLE must be \"policy_temporarily_unavailable\"",
            "policy_temporarily_unavailable",
            PolicyRoutingContext.RESULT_HOLD_REASON_TEMPORARILY_UNAVAILABLE
        )
    }

    @Test
    fun `isHoldStatus returns true for hold`() {
        assertTrue(
            "isHoldStatus(\"hold\") must return true — this is the canonical hold status string",
            PolicyRoutingContext.isHoldStatus("hold")
        )
    }

    @Test
    fun `isHoldStatus returns false for disabled`() {
        assertFalse(
            "isHoldStatus(\"disabled\") must return false — disabled is not a hold status",
            PolicyRoutingContext.isHoldStatus("disabled")
        )
    }

    @Test
    fun `isHoldStatus returns false for null`() {
        assertFalse(
            "isHoldStatus(null) must return false — legacy paths are not hold",
            PolicyRoutingContext.isHoldStatus(null)
        )
    }

    // ── GoalResultPayload — PR-5B structured fields defaults ──────────────────

    @Test
    fun `GoalResultPayload policy_rejection_detail defaults to null`() {
        val result = GoalResultPayload(task_id = "t-5b-default", status = "success")
        assertNull(
            "policy_rejection_detail must default to null for backward compatibility",
            result.policy_rejection_detail
        )
    }

    @Test
    fun `GoalResultPayload hold_reason defaults to null`() {
        val result = GoalResultPayload(task_id = "t-5b-default", status = "success")
        assertNull(
            "hold_reason must default to null for backward compatibility",
            result.hold_reason
        )
    }

    @Test
    fun `GoalResultPayload is_continuation defaults to null`() {
        val result = GoalResultPayload(task_id = "t-5b-default", status = "success")
        assertNull(
            "is_continuation must default to null for backward compatibility",
            result.is_continuation
        )
    }

    @Test
    fun `GoalResultPayload policy_rejection_detail can be set`() {
        val result = GoalResultPayload(
            task_id = "t-5b-rej",
            status = "disabled",
            policy_rejection_detail = "device_not_eligible"
        )
        assertEquals(
            "policy_rejection_detail must be preserved when set",
            "device_not_eligible",
            result.policy_rejection_detail
        )
    }

    @Test
    fun `GoalResultPayload hold_reason can be set`() {
        val result = GoalResultPayload(
            task_id = "t-5b-hold",
            status = "hold",
            hold_reason = PolicyRoutingContext.RESULT_HOLD_REASON_TEMPORARILY_UNAVAILABLE
        )
        assertEquals(
            "hold_reason must be preserved when set",
            "policy_temporarily_unavailable",
            result.hold_reason
        )
    }

    @Test
    fun `GoalResultPayload is_continuation can be set to true`() {
        val result = GoalResultPayload(
            task_id = "t-5b-cont",
            status = "success",
            is_continuation = true
        )
        assertTrue(
            "is_continuation must be true when explicitly set",
            result.is_continuation == true
        )
    }

    // ── AutonomousExecutionPipeline — constants ────────────────────────────────

    @Test
    fun `STATUS_HOLD is hold`() {
        assertEquals(
            "AutonomousExecutionPipeline.STATUS_HOLD must be \"hold\" for stable V2 wire contract",
            "hold",
            AutonomousExecutionPipeline.STATUS_HOLD
        )
    }

    @Test
    fun `REASON_TEMPORARILY_UNAVAILABLE is policy_temporarily_unavailable`() {
        assertEquals(
            "REASON_TEMPORARILY_UNAVAILABLE must be \"policy_temporarily_unavailable\"",
            "policy_temporarily_unavailable",
            AutonomousExecutionPipeline.REASON_TEMPORARILY_UNAVAILABLE
        )
    }

    // ── REJECTED structured result ────────────────────────────────────────────

    @Test
    fun `REJECTED result status is STATUS_DISABLED (backward compatible)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
        )
        assertEquals(
            "REJECTED result must still use STATUS_DISABLED for backward compatibility with V2 consumers",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }

    @Test
    fun `REJECTED result error is REASON_POLICY_REJECTED (backward compatible)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
        )
        assertEquals(
            "REJECTED result error must still be REASON_POLICY_REJECTED for backward compatibility",
            AutonomousExecutionPipeline.REASON_POLICY_REJECTED,
            result.error
        )
    }

    @Test
    fun `REJECTED result has non-null policy_rejection_detail`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
        )
        assertNotNull(
            "REJECTED result must carry a non-null policy_rejection_detail so V2 can access " +
                "structured rejection info instead of only the generic error string",
            result.policy_rejection_detail
        )
    }

    @Test
    fun `REJECTED result policy_rejection_detail echoes policy_failure_reason from payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue,
                policyFailureReason = PolicyRoutingContext.UNAVAILABILITY_REASON_TARGET_REJECTED
            )
        )
        assertEquals(
            "policy_rejection_detail must echo policy_failure_reason from the inbound payload " +
                "so V2 can correlate the specific rejection reason without parsing the error string",
            PolicyRoutingContext.UNAVAILABILITY_REASON_TARGET_REJECTED,
            result.policy_rejection_detail
        )
    }

    @Test
    fun `REJECTED result policy_rejection_detail defaults to REASON_POLICY_REJECTED when policy_failure_reason is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue,
                policyFailureReason = null
            )
        )
        assertEquals(
            "When policy_failure_reason is absent, policy_rejection_detail must default to " +
                "REASON_POLICY_REJECTED so V2 always receives a non-null structured reason",
            AutonomousExecutionPipeline.REASON_POLICY_REJECTED,
            result.policy_rejection_detail
        )
    }

    @Test
    fun `REJECTED result is_continuation is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
        )
        assertNull(
            "REJECTED result must NOT set is_continuation — rejection is not a continuation",
            result.is_continuation
        )
    }

    @Test
    fun `REJECTED result hold_reason is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
        )
        assertNull(
            "REJECTED result must NOT set hold_reason — rejection is not a hold",
            result.hold_reason
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
            "rejected",
            result.policy_routing_outcome
        )
    }

    // ── TEMPORARILY_UNAVAILABLE hold semantics ─────────────────────────────────

    @Test
    fun `TEMPORARILY_UNAVAILABLE result status is STATUS_HOLD`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE must produce STATUS_HOLD — a non-terminal hold, not an error",
            AutonomousExecutionPipeline.STATUS_HOLD,
            result.status
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result status is distinct from STATUS_DISABLED`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertFalse(
            "TEMPORARILY_UNAVAILABLE result status must NOT be STATUS_DISABLED — " +
                "V2 must be able to distinguish a hold from an administrative disabled result",
            result.status == AutonomousExecutionPipeline.STATUS_DISABLED
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result hold_reason is REASON_TEMPORARILY_UNAVAILABLE`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE result must carry REASON_TEMPORARILY_UNAVAILABLE as hold_reason " +
                "so V2 can identify the specific hold trigger",
            AutonomousExecutionPipeline.REASON_TEMPORARILY_UNAVAILABLE,
            result.hold_reason
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result error is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertNull(
            "TEMPORARILY_UNAVAILABLE result must NOT set error — this is not an execution failure",
            result.error
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result echoes task_id`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(taskId = "t-hold-id", policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE hold result must echo task_id for gateway aggregation",
            "t-hold-id",
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
        assertEquals("grp-hold", result.group_id)
        assertEquals(2, result.subtask_index)
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result echoes policy_routing_outcome`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertEquals(
            "TEMPORARILY_UNAVAILABLE hold result must echo policy_routing_outcome for full-chain correlation",
            "temporarily_unavailable",
            result.policy_routing_outcome
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result echoes continuity_token and is_resumable`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue,
                continuityToken = "ct-hold-001",
                isResumable = true
            )
        )
        assertEquals(
            "Continuity token must be echoed in hold results",
            "ct-hold-001",
            result.continuity_token
        )
        assertEquals(
            "is_resumable must be echoed in hold results",
            true,
            result.is_resumable
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result is_continuation is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertNull(
            "TEMPORARILY_UNAVAILABLE hold result must NOT set is_continuation — hold is not a continuation",
            result.is_continuation
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE result policy_rejection_detail is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertNull(
            "TEMPORARILY_UNAVAILABLE hold result must NOT set policy_rejection_detail — hold is not a rejection",
            result.policy_rejection_detail
        )
    }

    @Test
    fun `PolicyRoutingContext isHoldStatus matches TEMPORARILY_UNAVAILABLE result status`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertTrue(
            "PolicyRoutingContext.isHoldStatus must return true for the status of a TEMPORARILY_UNAVAILABLE result",
            PolicyRoutingContext.isHoldStatus(result.status)
        )
    }

    // ── RESUMED continuation-aware behavior ───────────────────────────────────

    @Test
    fun `RESUMED result is_continuation is true`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                continuityToken = "ct-resumed-5b",
                isResumable = true
            )
        )
        assertEquals(
            "RESUMED result must have is_continuation=true so V2 can distinguish resumed " +
                "executions from fresh dispatches without reparsing policy_routing_outcome",
            true,
            result.is_continuation
        )
    }

    @Test
    fun `RESUMED result echoes continuity_token`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                continuityToken = "ct-resumed-echo"
            )
        )
        assertEquals(
            "RESUMED result must echo continuity_token for V2 continuation correlation",
            "ct-resumed-echo",
            result.continuity_token
        )
    }

    @Test
    fun `RESUMED result proceeds to execution (not STATUS_DISABLED or STATUS_HOLD)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue)
        )
        assertFalse(
            "RESUMED result must NOT be STATUS_DISABLED — execution should proceed as continuation",
            result.status == AutonomousExecutionPipeline.STATUS_DISABLED
        )
        assertFalse(
            "RESUMED result must NOT be STATUS_HOLD — execution should proceed as continuation",
            result.status == AutonomousExecutionPipeline.STATUS_HOLD
        )
    }

    @Test
    fun `ACCEPTED result is_continuation is null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.ACCEPTED.wireValue)
        )
        assertNull(
            "Non-RESUMED (ACCEPTED) result must have is_continuation=null — not a continuation",
            result.is_continuation
        )
    }

    @Test
    fun `null outcome result is_continuation is null (legacy backward compat)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(policyRoutingOutcome = null))
        assertNull(
            "Legacy (null policy_routing_outcome) result must have is_continuation=null — " +
                "no continuation marker injected for pre-PR-5B payloads",
            result.is_continuation
        )
    }

    @Test
    fun `RESUMED result echoes policy_routing_outcome`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue)
        )
        assertEquals(
            "RESUMED result must echo policy_routing_outcome for full-chain policy correlation",
            "resumed",
            result.policy_routing_outcome
        )
    }

    // ── parallel_subtask — REJECTED, TEMPORARILY_UNAVAILABLE, RESUMED ─────────

    @Test
    fun `parallel REJECTED result has non-null policy_rejection_detail`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-5b-rej",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue,
                groupId = "grp-5b",
                subtaskIndex = 0
            )
        )
        assertNotNull(
            "Parallel REJECTED result must carry non-null policy_rejection_detail",
            result.policy_rejection_detail
        )
    }

    @Test
    fun `parallel TEMPORARILY_UNAVAILABLE result is STATUS_HOLD`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-5b-hold",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue,
                groupId = "grp-5b",
                subtaskIndex = 1
            )
        )
        assertEquals(
            "Parallel TEMPORARILY_UNAVAILABLE result must be STATUS_HOLD",
            AutonomousExecutionPipeline.STATUS_HOLD,
            result.status
        )
    }

    @Test
    fun `parallel TEMPORARILY_UNAVAILABLE hold_reason is REASON_TEMPORARILY_UNAVAILABLE`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-5b-hold-reason",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue,
                groupId = "grp-5b",
                subtaskIndex = 2
            )
        )
        assertEquals(
            "Parallel TEMPORARILY_UNAVAILABLE hold result must carry REASON_TEMPORARILY_UNAVAILABLE",
            AutonomousExecutionPipeline.REASON_TEMPORARILY_UNAVAILABLE,
            result.hold_reason
        )
    }

    @Test
    fun `parallel RESUMED result is_continuation is true`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-5b-resumed",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                groupId = "grp-5b",
                subtaskIndex = 3,
                continuityToken = "ct-par-5b"
            )
        )
        assertEquals(
            "Parallel RESUMED result must have is_continuation=true",
            true,
            result.is_continuation
        )
    }

    // ── StabilizationBaseline — PR-5B entries ─────────────────────────────────

    @Test
    fun `policy-outcome-hold-result is registered in StabilizationBaseline`() {
        assertNotNull(
            "StabilizationBaseline must include policy-outcome-hold-result entry for PR-5B",
            StabilizationBaseline.forId("policy-outcome-hold-result")
        )
    }

    @Test
    fun `policy-outcome-rejection-detail is registered in StabilizationBaseline`() {
        assertNotNull(
            "StabilizationBaseline must include policy-outcome-rejection-detail entry for PR-5B",
            StabilizationBaseline.forId("policy-outcome-rejection-detail")
        )
    }

    @Test
    fun `policy-outcome-continuation-marker is registered in StabilizationBaseline`() {
        assertNotNull(
            "StabilizationBaseline must include policy-outcome-continuation-marker entry for PR-5B",
            StabilizationBaseline.forId("policy-outcome-continuation-marker")
        )
    }

    @Test
    fun `autonomous-execution-pipeline-hold-status is registered in StabilizationBaseline`() {
        assertNotNull(
            "StabilizationBaseline must include autonomous-execution-pipeline-hold-status entry for PR-5B",
            StabilizationBaseline.forId("autonomous-execution-pipeline-hold-status")
        )
    }

    @Test
    fun `all PR-5B baseline entries have introducedPr = 5`() {
        val pr5bIds = listOf(
            "policy-outcome-hold-result",
            "policy-outcome-rejection-detail",
            "policy-outcome-continuation-marker",
            "autonomous-execution-pipeline-hold-status"
        )
        for (id in pr5bIds) {
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Entry $id must be registered", entry)
            assertEquals(
                "PR-5B entry $id must have introducedPr = 5",
                5,
                entry!!.introducedPr
            )
        }
    }

    @Test
    fun `all PR-5B baseline entries have CANONICAL_STABLE stability`() {
        val pr5bIds = listOf(
            "policy-outcome-hold-result",
            "policy-outcome-rejection-detail",
            "policy-outcome-continuation-marker",
            "autonomous-execution-pipeline-hold-status"
        )
        for (id in pr5bIds) {
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Entry $id must be registered", entry)
            assertEquals(
                "PR-5B entry $id must have CANONICAL_STABLE stability",
                StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
                entry!!.stability
            )
        }
    }
}
