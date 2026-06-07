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
 * PR-49 (PR-I) — Android-side policy-driven routing outcome compatibility.
 *
 * V2 is introducing a policy layer for target selection and failure handling across
 * Android device, node service, go worker, and local execution paths.  This test suite
 * validates all Android-side compatibility and semantic clarity requirements defined by
 * PR-49, ensuring that Android can safely consume the evolved policy routing contract
 * and that backward compatibility with legacy/narrow contracts is explicit and testable.
 *
 * ## Sections
 *
 * ### PolicyRoutingContext — RoutingOutcome wire values
 *  - ACCEPTED wire value is `"accepted"`
 *  - DEGRADED_READINESS wire value is `"degraded_readiness"`
 *  - TEMPORARILY_UNAVAILABLE wire value is `"temporarily_unavailable"`
 *  - RESUMED wire value is `"resumed"`
 *  - REJECTED wire value is `"rejected"`
 *
 * ### PolicyRoutingContext — RoutingOutcome ALL_VALUES
 *  - ALL_VALUES contains exactly five entries
 *  - ALL_VALUES contains every canonical wire value
 *
 * ### PolicyRoutingContext — RoutingOutcome fromValue known values
 *  - fromValue returns ACCEPTED for canonical string
 *  - fromValue returns DEGRADED_READINESS for canonical string
 *  - fromValue returns TEMPORARILY_UNAVAILABLE for canonical string
 *  - fromValue returns RESUMED for canonical string
 *  - fromValue returns REJECTED for canonical string
 *
 * ### PolicyRoutingContext — RoutingOutcome fromValue safe-unknown handling
 *  - fromValue returns null for null
 *  - fromValue returns null for blank string
 *  - fromValue returns null for unknown string
 *  - fromValue returns null for mixed-case variant (wire format is strictly lower-snake-case)
 *
 * ### PolicyRoutingContext — RoutingOutcome PROCEED_OUTCOMES
 *  - ACCEPTED is in PROCEED_OUTCOMES
 *  - DEGRADED_READINESS is in PROCEED_OUTCOMES
 *  - RESUMED is in PROCEED_OUTCOMES
 *  - TEMPORARILY_UNAVAILABLE is NOT in PROCEED_OUTCOMES
 *  - REJECTED is NOT in PROCEED_OUTCOMES
 *
 * ### PolicyRoutingContext — degradation reason constants
 *  - DEGRADATION_REASON_MODEL_DEGRADED is "model_degraded"
 *  - DEGRADATION_REASON_ACCESSIBILITY_UNRELIABLE is "accessibility_unreliable"
 *  - DEGRADATION_REASON_TRANSPORT_QUALITY is "transport_quality"
 *  - DEGRADATION_REASON_SUBSYSTEM_FALLBACK is "subsystem_fallback"
 *  - KNOWN_DEGRADATION_REASONS contains all four constants
 *
 * ### PolicyRoutingContext — unavailability / failure reason constants
 *  - UNAVAILABILITY_REASON_RECONNECTING is "reconnecting"
 *  - UNAVAILABILITY_REASON_HEALTH_RECOVERING is "health_recovering"
 *  - UNAVAILABILITY_REASON_NOT_ATTACHED is "not_attached"
 *  - UNAVAILABILITY_REASON_TARGET_REJECTED is "target_rejected"
 *  - KNOWN_FAILURE_REASONS contains all four constants
 *
 * ### PolicyRoutingContext — shouldProceed
 *  - shouldProceed(null) is true (legacy / unspecified)
 *  - shouldProceed("accepted") is true
 *  - shouldProceed("degraded_readiness") is true
 *  - shouldProceed("resumed") is true
 *  - shouldProceed("temporarily_unavailable") is false
 *  - shouldProceed("rejected") is false
 *  - shouldProceed for unknown/future value is true (forward compatibility)
 *
 * ### PolicyRoutingContext — isTemporaryHold
 *  - isTemporaryHold("temporarily_unavailable") is true
 *  - isTemporaryHold("accepted") is false
 *  - isTemporaryHold(null) is false (legacy)
 *
 * ### PolicyRoutingContext — isPolicyRejection
 *  - isPolicyRejection("rejected") is true
 *  - isPolicyRejection("accepted") is false
 *  - isPolicyRejection(null) is false (legacy)
 *  - isPolicyRejection for unknown value is false
 *
 * ### PolicyRoutingContext — isResumedExecution
 *  - isResumedExecution("resumed") is true
 *  - isResumedExecution("accepted") is false
 *  - isResumedExecution(null) is false (legacy)
 *
 * ### PolicyRoutingContext — isDegradedReadiness
 *  - isDegradedReadiness("degraded_readiness") is true
 *  - isDegradedReadiness("accepted") is false
 *  - isDegradedReadiness(null) is false (legacy)
 *
 * ### PolicyRoutingContext — isKnownDegradationReason
 *  - isKnownDegradationReason("model_degraded") is true
 *  - isKnownDegradationReason(null) is false
 *  - isKnownDegradationReason("unknown_future_hint") is false
 *
 * ### PolicyRoutingContext — isKnownFailureReason
 *  - isKnownFailureReason("reconnecting") is true
 *  - isKnownFailureReason(null) is false
 *  - isKnownFailureReason("unknown_future_reason") is false
 *
 * ### PolicyRoutingContext — INTRODUCED_PR
 *  - INTRODUCED_PR is 49
 *
 * ### GoalExecutionPayload — PR-49 policy routing fields
 *  - policy_routing_outcome defaults to null (backward compatibility)
 *  - policy_failure_reason defaults to null (backward compatibility)
 *  - readiness_degradation_hint defaults to null (backward compatibility)
 *  - all PR-49 fields can be set simultaneously
 *  - effectiveTimeoutMs is unaffected by PR-49 fields
 *
 * ### GoalResultPayload — PR-49 policy_routing_outcome echo field
 *  - policy_routing_outcome defaults to null (backward compatibility)
 *  - policy_routing_outcome can be set on GoalResultPayload
 *
 * ### AutonomousExecutionPipeline — PR-49 field acceptance (goal_execution)
 *  - pipeline accepts payload with policy_routing_outcome=accepted (no failure)
 *  - pipeline accepts payload with policy_routing_outcome=degraded_readiness (no failure)
 *  - pipeline accepts payload with policy_routing_outcome=temporarily_unavailable (no failure)
 *  - pipeline accepts payload with policy_routing_outcome=resumed (no failure)
 *  - pipeline returns disabled result for policy_routing_outcome=rejected
 *  - pipeline disabled result for rejected outcome has reason policy_routing_rejected
 *  - pipeline accepts payload with null policy_routing_outcome (legacy backward compat)
 *  - pipeline accepts payload with unknown future policy_routing_outcome (forward compat)
 *  - success result echoes policy_routing_outcome from payload
 *  - disabled (cross-device off) result echoes policy_routing_outcome from payload
 *  - policy-rejected disabled result echoes policy_routing_outcome from payload
 *  - success result echoes null policy_routing_outcome from legacy payload
 *
 * ### AutonomousExecutionPipeline — PR-49 field acceptance (parallel_subtask)
 *  - parallel pipeline accepts payload with policy_routing_outcome=accepted (no failure)
 *  - parallel pipeline returns disabled result for policy_routing_outcome=rejected
 *  - parallel pipeline echoes policy_routing_outcome in success result
 *  - parallel pipeline echoes policy_routing_outcome in disabled result
 *
 * ### ExecutionContractCompatibilityValidator — PR-49 POLICY_ROUTING area
 *  - POLICY_ROUTING CompatibilityArea exists
 *  - payload with policy_routing_outcome set produces hasPolicyRouting = true
 *  - payload with policy_failure_reason set produces hasPolicyRouting = true
 *  - payload with readiness_degradation_hint set produces hasPolicyRouting = true
 *  - legacy payload (all null) produces hasPolicyRouting = false
 *  - legacy payload has empty activeAreas set (includes POLICY_ROUTING = false)
 *  - maximally evolved payload has all five areas active
 *  - isFullyEvolved requires all five evolved areas
 *
 * ### StabilizationBaseline — PR-49 entries registered
 *  - policy-routing-context is registered as CANONICAL_STABLE
 *  - goal-execution-payload-policy-routing-fields is registered as CANONICAL_STABLE
 *  - goal-result-payload-policy-routing-outcome-echo is registered as CANONICAL_STABLE
 *  - all PR-49 entries have introducedPr = 49
 *  - all PR-49 entries have EXTEND guidance
 */
class Pr49PolicyRoutingCompatibilityTest {

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
        taskId: String = "t-pri",
        goal: String = "open WeChat",
        posture: String? = SourceRuntimePosture.JOIN_RUNTIME,
        policyRoutingOutcome: String? = null,
        policyFailureReason: String? = null,
        readinessDegradationHint: String? = null,
        dispatchPlanId: String? = null,
        sourceDispatchStrategy: String? = null,
        executorTargetType: String? = null,
        continuityToken: String? = null,
        recoveryContext: Map<String, String> = emptyMap(),
        isResumable: Boolean? = null,
        interruptionReason: String? = null,
        dispatchTraceId: String? = null,
        lifecycleEventId: String? = null,
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
        readiness_degradation_hint = readinessDegradationHint,
        dispatch_plan_id = dispatchPlanId,
        source_dispatch_strategy = sourceDispatchStrategy,
        executor_target_type = executorTargetType,
        continuity_token = continuityToken,
        recovery_context = recoveryContext,
        is_resumable = isResumable,
        interruption_reason = interruptionReason,
        dispatch_trace_id = dispatchTraceId,
        lifecycle_event_id = lifecycleEventId
    )

    // ── RoutingOutcome — wire values ──────────────────────────────────────────

    @Test
    fun `ACCEPTED wire value is accepted`() {
        assertEquals("accepted", PolicyRoutingContext.RoutingOutcome.ACCEPTED.wireValue)
    }

    @Test
    fun `DEGRADED_READINESS wire value is degraded_readiness`() {
        assertEquals("degraded_readiness", PolicyRoutingContext.RoutingOutcome.DEGRADED_READINESS.wireValue)
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE wire value is temporarily_unavailable`() {
        assertEquals("temporarily_unavailable", PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
    }

    @Test
    fun `RESUMED wire value is resumed`() {
        assertEquals("resumed", PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue)
    }

    @Test
    fun `REJECTED wire value is rejected`() {
        assertEquals("rejected", PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
    }

    // ── RoutingOutcome — ALL_VALUES ───────────────────────────────────────────

    @Test
    fun `ALL_VALUES contains exactly five entries`() {
        assertEquals(
            "RoutingOutcome.ALL_VALUES must contain exactly five canonical wire values",
            5,
            PolicyRoutingContext.RoutingOutcome.ALL_VALUES.size
        )
    }

    @Test
    fun `ALL_VALUES contains every canonical wire value`() {
        assertTrue(PolicyRoutingContext.RoutingOutcome.ALL_VALUES.contains("accepted"))
        assertTrue(PolicyRoutingContext.RoutingOutcome.ALL_VALUES.contains("degraded_readiness"))
        assertTrue(PolicyRoutingContext.RoutingOutcome.ALL_VALUES.contains("temporarily_unavailable"))
        assertTrue(PolicyRoutingContext.RoutingOutcome.ALL_VALUES.contains("resumed"))
        assertTrue(PolicyRoutingContext.RoutingOutcome.ALL_VALUES.contains("rejected"))
    }

    // ── RoutingOutcome — fromValue known values ───────────────────────────────

    @Test
    fun `fromValue returns ACCEPTED for canonical string`() {
        assertEquals(
            PolicyRoutingContext.RoutingOutcome.ACCEPTED,
            PolicyRoutingContext.RoutingOutcome.fromValue("accepted")
        )
    }

    @Test
    fun `fromValue returns DEGRADED_READINESS for canonical string`() {
        assertEquals(
            PolicyRoutingContext.RoutingOutcome.DEGRADED_READINESS,
            PolicyRoutingContext.RoutingOutcome.fromValue("degraded_readiness")
        )
    }

    @Test
    fun `fromValue returns TEMPORARILY_UNAVAILABLE for canonical string`() {
        assertEquals(
            PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE,
            PolicyRoutingContext.RoutingOutcome.fromValue("temporarily_unavailable")
        )
    }

    @Test
    fun `fromValue returns RESUMED for canonical string`() {
        assertEquals(
            PolicyRoutingContext.RoutingOutcome.RESUMED,
            PolicyRoutingContext.RoutingOutcome.fromValue("resumed")
        )
    }

    @Test
    fun `fromValue returns REJECTED for canonical string`() {
        assertEquals(
            PolicyRoutingContext.RoutingOutcome.REJECTED,
            PolicyRoutingContext.RoutingOutcome.fromValue("rejected")
        )
    }

    // ── RoutingOutcome — fromValue safe-unknown handling ──────────────────────

    @Test
    fun `fromValue returns null for null`() {
        assertNull(
            "null policy_routing_outcome must resolve to null for backward compatibility",
            PolicyRoutingContext.RoutingOutcome.fromValue(null)
        )
    }

    @Test
    fun `fromValue returns null for blank string`() {
        assertNull(
            "Blank policy_routing_outcome must resolve to null (not a valid wire value)",
            PolicyRoutingContext.RoutingOutcome.fromValue("   ")
        )
    }

    @Test
    fun `fromValue returns null for unknown string`() {
        assertNull(
            "Unknown policy_routing_outcome must resolve to null for forward compatibility",
            PolicyRoutingContext.RoutingOutcome.fromValue("future_policy_outcome")
        )
    }

    @Test
    fun `fromValue returns null for mixed-case variant`() {
        assertNull(
            "Wire format is strictly lower-snake-case; mixed-case must not match",
            PolicyRoutingContext.RoutingOutcome.fromValue("Accepted")
        )
    }

    // ── RoutingOutcome — PROCEED_OUTCOMES ─────────────────────────────────────

    @Test
    fun `ACCEPTED is in PROCEED_OUTCOMES`() {
        assertTrue(
            "ACCEPTED must be a proceed outcome: Android should execute normally",
            PolicyRoutingContext.RoutingOutcome.PROCEED_OUTCOMES.contains("accepted")
        )
    }

    @Test
    fun `DEGRADED_READINESS is in PROCEED_OUTCOMES`() {
        assertTrue(
            "DEGRADED_READINESS must be a proceed outcome: Android should execute in degraded mode",
            PolicyRoutingContext.RoutingOutcome.PROCEED_OUTCOMES.contains("degraded_readiness")
        )
    }

    @Test
    fun `RESUMED is in PROCEED_OUTCOMES`() {
        assertTrue(
            "RESUMED must be a proceed outcome: Android should continue the interrupted execution",
            PolicyRoutingContext.RoutingOutcome.PROCEED_OUTCOMES.contains("resumed")
        )
    }

    @Test
    fun `TEMPORARILY_UNAVAILABLE is NOT in PROCEED_OUTCOMES`() {
        assertFalse(
            "TEMPORARILY_UNAVAILABLE must NOT be a proceed outcome: Android should hold, not execute",
            PolicyRoutingContext.RoutingOutcome.PROCEED_OUTCOMES.contains("temporarily_unavailable")
        )
    }

    @Test
    fun `REJECTED is NOT in PROCEED_OUTCOMES`() {
        assertFalse(
            "REJECTED must NOT be a proceed outcome: Android must return a disabled result",
            PolicyRoutingContext.RoutingOutcome.PROCEED_OUTCOMES.contains("rejected")
        )
    }

    // ── Degradation reason constants ──────────────────────────────────────────

    @Test
    fun `DEGRADATION_REASON_MODEL_DEGRADED is model_degraded`() {
        assertEquals("model_degraded", PolicyRoutingContext.DEGRADATION_REASON_MODEL_DEGRADED)
    }

    @Test
    fun `DEGRADATION_REASON_ACCESSIBILITY_UNRELIABLE is accessibility_unreliable`() {
        assertEquals("accessibility_unreliable", PolicyRoutingContext.DEGRADATION_REASON_ACCESSIBILITY_UNRELIABLE)
    }

    @Test
    fun `DEGRADATION_REASON_TRANSPORT_QUALITY is transport_quality`() {
        assertEquals("transport_quality", PolicyRoutingContext.DEGRADATION_REASON_TRANSPORT_QUALITY)
    }

    @Test
    fun `DEGRADATION_REASON_SUBSYSTEM_FALLBACK is subsystem_fallback`() {
        assertEquals("subsystem_fallback", PolicyRoutingContext.DEGRADATION_REASON_SUBSYSTEM_FALLBACK)
    }

    @Test
    fun `KNOWN_DEGRADATION_REASONS contains all four constants`() {
        assertEquals(4, PolicyRoutingContext.KNOWN_DEGRADATION_REASONS.size)
        assertTrue(PolicyRoutingContext.KNOWN_DEGRADATION_REASONS.contains("model_degraded"))
        assertTrue(PolicyRoutingContext.KNOWN_DEGRADATION_REASONS.contains("accessibility_unreliable"))
        assertTrue(PolicyRoutingContext.KNOWN_DEGRADATION_REASONS.contains("transport_quality"))
        assertTrue(PolicyRoutingContext.KNOWN_DEGRADATION_REASONS.contains("subsystem_fallback"))
    }

    // ── Unavailability / failure reason constants ─────────────────────────────

    @Test
    fun `UNAVAILABILITY_REASON_RECONNECTING is reconnecting`() {
        assertEquals("reconnecting", PolicyRoutingContext.UNAVAILABILITY_REASON_RECONNECTING)
    }

    @Test
    fun `UNAVAILABILITY_REASON_HEALTH_RECOVERING is health_recovering`() {
        assertEquals("health_recovering", PolicyRoutingContext.UNAVAILABILITY_REASON_HEALTH_RECOVERING)
    }

    @Test
    fun `UNAVAILABILITY_REASON_NOT_ATTACHED is not_attached`() {
        assertEquals("not_attached", PolicyRoutingContext.UNAVAILABILITY_REASON_NOT_ATTACHED)
    }

    @Test
    fun `UNAVAILABILITY_REASON_TARGET_REJECTED is target_rejected`() {
        assertEquals("target_rejected", PolicyRoutingContext.UNAVAILABILITY_REASON_TARGET_REJECTED)
    }

    @Test
    fun `KNOWN_FAILURE_REASONS contains all four constants`() {
        assertEquals(4, PolicyRoutingContext.KNOWN_FAILURE_REASONS.size)
        assertTrue(PolicyRoutingContext.KNOWN_FAILURE_REASONS.contains("reconnecting"))
        assertTrue(PolicyRoutingContext.KNOWN_FAILURE_REASONS.contains("health_recovering"))
        assertTrue(PolicyRoutingContext.KNOWN_FAILURE_REASONS.contains("not_attached"))
        assertTrue(PolicyRoutingContext.KNOWN_FAILURE_REASONS.contains("target_rejected"))
    }

    // ── shouldProceed ─────────────────────────────────────────────────────────

    @Test
    fun `shouldProceed returns true for null (legacy unspecified)`() {
        assertTrue(
            "null policy_routing_outcome (legacy) must allow execution to proceed",
            PolicyRoutingContext.shouldProceed(null)
        )
    }

    @Test
    fun `shouldProceed returns true for accepted`() {
        assertTrue(
            "accepted outcome must allow execution to proceed",
            PolicyRoutingContext.shouldProceed("accepted")
        )
    }

    @Test
    fun `shouldProceed returns true for degraded_readiness`() {
        assertTrue(
            "degraded_readiness outcome must allow execution to proceed in degraded mode",
            PolicyRoutingContext.shouldProceed("degraded_readiness")
        )
    }

    @Test
    fun `shouldProceed returns true for resumed`() {
        assertTrue(
            "resumed outcome must allow execution to proceed as a continuation",
            PolicyRoutingContext.shouldProceed("resumed")
        )
    }

    @Test
    fun `shouldProceed returns false for temporarily_unavailable`() {
        assertFalse(
            "temporarily_unavailable outcome must NOT allow execution to proceed; Android should hold",
            PolicyRoutingContext.shouldProceed("temporarily_unavailable")
        )
    }

    @Test
    fun `shouldProceed returns false for rejected`() {
        assertFalse(
            "rejected outcome must NOT allow execution to proceed; Android must return disabled result",
            PolicyRoutingContext.shouldProceed("rejected")
        )
    }

    @Test
    fun `shouldProceed returns true for unknown future value (forward compatibility)`() {
        assertTrue(
            "Unknown future policy routing outcomes must allow execution to proceed (forward compat)",
            PolicyRoutingContext.shouldProceed("future_policy_v3_outcome")
        )
    }

    // ── isTemporaryHold ───────────────────────────────────────────────────────

    @Test
    fun `isTemporaryHold returns true for temporarily_unavailable`() {
        assertTrue(
            "temporarily_unavailable must be identified as a temporary hold",
            PolicyRoutingContext.isTemporaryHold("temporarily_unavailable")
        )
    }

    @Test
    fun `isTemporaryHold returns false for accepted`() {
        assertFalse(
            "accepted must NOT be identified as a temporary hold",
            PolicyRoutingContext.isTemporaryHold("accepted")
        )
    }

    @Test
    fun `isTemporaryHold returns false for null (legacy)`() {
        assertFalse(
            "null (legacy) policy_routing_outcome must NOT be a temporary hold",
            PolicyRoutingContext.isTemporaryHold(null)
        )
    }

    // ── isPolicyRejection ─────────────────────────────────────────────────────

    @Test
    fun `isPolicyRejection returns true for rejected`() {
        assertTrue(
            "rejected outcome must be identified as a policy rejection",
            PolicyRoutingContext.isPolicyRejection("rejected")
        )
    }

    @Test
    fun `isPolicyRejection returns false for accepted`() {
        assertFalse(
            "accepted outcome must NOT be identified as a policy rejection",
            PolicyRoutingContext.isPolicyRejection("accepted")
        )
    }

    @Test
    fun `isPolicyRejection returns false for null (legacy)`() {
        assertFalse(
            "null (legacy) policy_routing_outcome must NOT be a policy rejection",
            PolicyRoutingContext.isPolicyRejection(null)
        )
    }

    @Test
    fun `isPolicyRejection returns false for unknown value`() {
        assertFalse(
            "Unknown policy_routing_outcome must NOT be treated as a rejection for forward compatibility",
            PolicyRoutingContext.isPolicyRejection("future_unknown_outcome")
        )
    }

    // ── isResumedExecution ────────────────────────────────────────────────────

    @Test
    fun `isResumedExecution returns true for resumed`() {
        assertTrue(
            "resumed outcome must be identified as a resumed execution",
            PolicyRoutingContext.isResumedExecution("resumed")
        )
    }

    @Test
    fun `isResumedExecution returns false for accepted`() {
        assertFalse(
            "accepted outcome must NOT be identified as a resumed execution",
            PolicyRoutingContext.isResumedExecution("accepted")
        )
    }

    @Test
    fun `isResumedExecution returns false for null (legacy)`() {
        assertFalse(
            "null (legacy) policy_routing_outcome must NOT be a resumed execution",
            PolicyRoutingContext.isResumedExecution(null)
        )
    }

    // ── isDegradedReadiness ───────────────────────────────────────────────────

    @Test
    fun `isDegradedReadiness returns true for degraded_readiness`() {
        assertTrue(
            "degraded_readiness outcome must be identified as degraded readiness",
            PolicyRoutingContext.isDegradedReadiness("degraded_readiness")
        )
    }

    @Test
    fun `isDegradedReadiness returns false for accepted`() {
        assertFalse(
            "accepted outcome must NOT be identified as degraded readiness",
            PolicyRoutingContext.isDegradedReadiness("accepted")
        )
    }

    @Test
    fun `isDegradedReadiness returns false for null (legacy)`() {
        assertFalse(
            "null (legacy) policy_routing_outcome must NOT be degraded readiness",
            PolicyRoutingContext.isDegradedReadiness(null)
        )
    }

    // ── isKnownDegradationReason ──────────────────────────────────────────────

    @Test
    fun `isKnownDegradationReason returns true for model_degraded`() {
        assertTrue(PolicyRoutingContext.isKnownDegradationReason("model_degraded"))
    }

    @Test
    fun `isKnownDegradationReason returns false for null`() {
        assertFalse(PolicyRoutingContext.isKnownDegradationReason(null))
    }

    @Test
    fun `isKnownDegradationReason returns false for unknown future hint`() {
        assertFalse(
            "Unknown degradation hints must return false but must still be tolerated",
            PolicyRoutingContext.isKnownDegradationReason("unknown_future_hint")
        )
    }

    // ── isKnownFailureReason ──────────────────────────────────────────────────

    @Test
    fun `isKnownFailureReason returns true for reconnecting`() {
        assertTrue(PolicyRoutingContext.isKnownFailureReason("reconnecting"))
    }

    @Test
    fun `isKnownFailureReason returns false for null`() {
        assertFalse(PolicyRoutingContext.isKnownFailureReason(null))
    }

    @Test
    fun `isKnownFailureReason returns false for unknown future reason`() {
        assertFalse(
            "Unknown failure reasons must return false but must still be tolerated",
            PolicyRoutingContext.isKnownFailureReason("unknown_future_reason")
        )
    }

    // ── INTRODUCED_PR ─────────────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 49`() {
        assertEquals(
            "PolicyRoutingContext must be introduced at PR-49",
            49,
            PolicyRoutingContext.INTRODUCED_PR
        )
    }

    // ── GoalExecutionPayload — PR-49 policy routing fields ───────────────────

    @Test
    fun `GoalExecutionPayload policy_routing_outcome defaults to null`() {
        val payload = GoalExecutionPayload(task_id = "t-default-1", goal = "test")
        assertNull(
            "policy_routing_outcome must default to null for backward compatibility with pre-PR-I senders",
            payload.policy_routing_outcome
        )
    }

    @Test
    fun `GoalExecutionPayload policy_failure_reason defaults to null`() {
        val payload = GoalExecutionPayload(task_id = "t-default-2", goal = "test")
        assertNull(
            "policy_failure_reason must default to null for backward compatibility with pre-PR-I senders",
            payload.policy_failure_reason
        )
    }

    @Test
    fun `GoalExecutionPayload readiness_degradation_hint defaults to null`() {
        val payload = GoalExecutionPayload(task_id = "t-default-3", goal = "test")
        assertNull(
            "readiness_degradation_hint must default to null for backward compatibility with pre-PR-I senders",
            payload.readiness_degradation_hint
        )
    }

    @Test
    fun `GoalExecutionPayload accepts all PR-49 fields simultaneously`() {
        val payload = GoalExecutionPayload(
            task_id = "t-pri-all",
            goal = "test",
            policy_routing_outcome = PolicyRoutingContext.RoutingOutcome.DEGRADED_READINESS.wireValue,
            policy_failure_reason = null,
            readiness_degradation_hint = PolicyRoutingContext.DEGRADATION_REASON_MODEL_DEGRADED
        )
        assertEquals("degraded_readiness", payload.policy_routing_outcome)
        assertNull(payload.policy_failure_reason)
        assertEquals("model_degraded", payload.readiness_degradation_hint)
    }

    @Test
    fun `GoalExecutionPayload effectiveTimeoutMs is unaffected by PR-49 fields`() {
        val legacyPayload = GoalExecutionPayload(task_id = "t-timeout-legacy", goal = "test")
        val priPayload = GoalExecutionPayload(
            task_id = "t-timeout-pri",
            goal = "test",
            policy_routing_outcome = "accepted",
            policy_failure_reason = null,
            readiness_degradation_hint = null
        )
        assertEquals(
            "PR-49 fields must not affect effectiveTimeoutMs",
            legacyPayload.effectiveTimeoutMs,
            priPayload.effectiveTimeoutMs
        )
    }

    // ── GoalResultPayload — PR-49 policy_routing_outcome echo field ───────────

    @Test
    fun `GoalResultPayload policy_routing_outcome defaults to null`() {
        val result = GoalResultPayload(task_id = "t-result-1", status = "success")
        assertNull(
            "policy_routing_outcome must default to null in GoalResultPayload for backward compatibility",
            result.policy_routing_outcome
        )
    }

    @Test
    fun `GoalResultPayload policy_routing_outcome can be set`() {
        val result = GoalResultPayload(
            task_id = "t-result-2",
            status = "success",
            policy_routing_outcome = PolicyRoutingContext.RoutingOutcome.ACCEPTED.wireValue
        )
        assertEquals(
            "policy_routing_outcome should be preserved in the result for full-chain policy correlation",
            "accepted",
            result.policy_routing_outcome
        )
    }

    // ── AutonomousExecutionPipeline — goal_execution ──────────────────────────

    @Test
    fun `pipeline accepts payload with policy_routing_outcome=accepted (no failure)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.ACCEPTED.wireValue)
        )
        assertNotNull("Result must not be null when policy_routing_outcome=accepted", result)
    }

    @Test
    fun `pipeline accepts payload with policy_routing_outcome=degraded_readiness (no failure)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.DEGRADED_READINESS.wireValue)
        )
        assertNotNull(
            "Result must not be null when policy_routing_outcome=degraded_readiness — " +
                "Android must not escalate degraded readiness to terminal failure",
            result
        )
    }

    @Test
    fun `pipeline accepts payload with policy_routing_outcome=temporarily_unavailable (no failure)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue)
        )
        assertNotNull(
            "Result must not be null when policy_routing_outcome=temporarily_unavailable — " +
                "Android must not crash; it should surface a non-terminal response",
            result
        )
    }

    @Test
    fun `pipeline accepts payload with policy_routing_outcome=resumed (no failure)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                continuityToken = "ct-resumed-001",
                isResumable = true
            )
        )
        assertNotNull(
            "Result must not be null when policy_routing_outcome=resumed — " +
                "Android must treat this as a continuation, not a fresh dispatch",
            result
        )
    }

    @Test
    fun `pipeline returns disabled result for policy_routing_outcome=rejected`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
        )
        assertEquals(
            "policy_routing_outcome=rejected must produce STATUS_DISABLED result — " +
                "Android must not execute a policy-rejected task",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }

    @Test
    fun `pipeline disabled result for rejected outcome has reason policy_routing_rejected`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
        )
        assertEquals(
            "Policy-rejected disabled result must use REASON_POLICY_REJECTED error reason",
            AutonomousExecutionPipeline.REASON_POLICY_REJECTED,
            result.error
        )
    }

    @Test
    fun `pipeline accepts payload with null policy_routing_outcome (legacy backward compat)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(policyRoutingOutcome = null))
        assertNotNull(
            "Pipeline must work correctly with legacy payloads (no policy_routing_outcome)",
            result
        )
    }

    @Test
    fun `pipeline accepts payload with unknown future policy_routing_outcome (forward compat)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = "future_v3_policy_outcome")
        )
        assertNotNull(
            "Unknown future policy_routing_outcome must not cause an exception — forward compatibility",
            result
        )
    }

    @Test
    fun `success result echoes policy_routing_outcome from payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.ACCEPTED.wireValue)
        )
        assertEquals(
            "policy_routing_outcome must be echoed in success results for full-chain policy correlation",
            "accepted",
            result.policy_routing_outcome
        )
    }

    @Test
    fun `disabled (cross-device off) result echoes policy_routing_outcome from payload`() {
        val pipeline = buildPipeline(crossDeviceEnabled = false)
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.ACCEPTED.wireValue)
        )
        assertEquals(
            "policy_routing_outcome must be echoed in cross-device-disabled results",
            "accepted",
            result.policy_routing_outcome
        )
    }

    @Test
    fun `policy-rejected disabled result echoes policy_routing_outcome from payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue)
        )
        assertEquals(
            "policy_routing_outcome must be echoed even in policy-rejected disabled results",
            "rejected",
            result.policy_routing_outcome
        )
    }

    @Test
    fun `success result echoes null policy_routing_outcome from legacy payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(policyRoutingOutcome = null))
        assertNull(
            "Legacy result must preserve null policy_routing_outcome — no value should be injected",
            result.policy_routing_outcome
        )
    }

    // ── AutonomousExecutionPipeline — parallel_subtask ────────────────────────

    @Test
    fun `parallel pipeline accepts payload with policy_routing_outcome=accepted (no failure)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-pri-accepted",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.ACCEPTED.wireValue,
                groupId = "grp-pri",
                subtaskIndex = 0
            )
        )
        assertNotNull(
            "Parallel result must not be null when policy_routing_outcome=accepted",
            result
        )
    }

    @Test
    fun `parallel pipeline returns disabled result for policy_routing_outcome=rejected`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-pri-rejected",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.REJECTED.wireValue,
                groupId = "grp-pri",
                subtaskIndex = 0
            )
        )
        assertEquals(
            "policy_routing_outcome=rejected must produce STATUS_DISABLED for parallel_subtask too",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }

    @Test
    fun `parallel pipeline echoes policy_routing_outcome in success result`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-pri-echo",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.DEGRADED_READINESS.wireValue,
                groupId = "grp-pri",
                subtaskIndex = 1
            )
        )
        assertEquals(
            "policy_routing_outcome must be echoed in parallel success results",
            "degraded_readiness",
            result.policy_routing_outcome
        )
    }

    @Test
    fun `parallel pipeline echoes policy_routing_outcome in disabled result`() {
        val pipeline = buildPipeline(crossDeviceEnabled = false)
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-pri-disabled",
                policyRoutingOutcome = PolicyRoutingContext.RoutingOutcome.ACCEPTED.wireValue,
                groupId = "grp-pri",
                subtaskIndex = 0
            )
        )
        assertEquals(
            "policy_routing_outcome must be echoed in parallel disabled results",
            "accepted",
            result.policy_routing_outcome
        )
    }

    // ── ExecutionContractCompatibilityValidator — POLICY_ROUTING area ─────────

    @Test
    fun `POLICY_ROUTING CompatibilityArea exists`() {
        assertNotNull(
            "CompatibilityArea.POLICY_ROUTING must exist for PR-49 contract area coverage",
            ExecutionContractCompatibilityValidator.CompatibilityArea.POLICY_ROUTING
        )
    }

    @Test
    fun `payload with policy_routing_outcome set produces hasPolicyRouting = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(
                task_id = "t-pr49-1",
                goal = "test",
                policy_routing_outcome = "accepted"
            )
        )
        assertTrue(
            "policy_routing_outcome being non-blank must activate POLICY_ROUTING area",
            result.hasPolicyRouting
        )
    }

    @Test
    fun `payload with policy_failure_reason set produces hasPolicyRouting = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(
                task_id = "t-pr49-2",
                goal = "test",
                policy_failure_reason = PolicyRoutingContext.UNAVAILABILITY_REASON_RECONNECTING
            )
        )
        assertTrue(
            "policy_failure_reason being non-blank must activate POLICY_ROUTING area",
            result.hasPolicyRouting
        )
    }

    @Test
    fun `payload with readiness_degradation_hint set produces hasPolicyRouting = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(
                task_id = "t-pr49-3",
                goal = "test",
                readiness_degradation_hint = PolicyRoutingContext.DEGRADATION_REASON_MODEL_DEGRADED
            )
        )
        assertTrue(
            "readiness_degradation_hint being non-blank must activate POLICY_ROUTING area",
            result.hasPolicyRouting
        )
    }

    @Test
    fun `legacy payload produces hasPolicyRouting = false`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-pr49-legacy", goal = "test")
        )
        assertFalse(
            "Legacy payload must not signal hasPolicyRouting — no policy routing fields present",
            result.hasPolicyRouting
        )
    }

    @Test
    fun `legacy payload does not include POLICY_ROUTING in activeAreas`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-pr49-areas", goal = "test")
        )
        assertFalse(
            "POLICY_ROUTING must not be in activeAreas for legacy payloads",
            result.activeAreas.contains(ExecutionContractCompatibilityValidator.CompatibilityArea.POLICY_ROUTING)
        )
    }

    @Test
    fun `maximally evolved payload has all five areas active`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(
                task_id = "t-max",
                goal = "test",
                dispatch_plan_id = "plan-abc",
                source_dispatch_strategy = ExecutionContractCompatibilityValidator.DispatchStrategyHint.LOCAL.wireValue,
                executor_target_type = ExecutorTargetType.ANDROID_DEVICE,
                continuity_token = "ct-abc",
                dispatch_trace_id = "dt-abc",
                policy_routing_outcome = PolicyRoutingContext.RoutingOutcome.ACCEPTED.wireValue
            )
        )
        assertEquals(
            "Maximally evolved payload must have all five CompatibilityArea values active",
            5,
            result.activeAreas.size
        )
        assertTrue(result.activeAreas.contains(ExecutionContractCompatibilityValidator.CompatibilityArea.DISPATCH_METADATA))
        assertTrue(result.activeAreas.contains(ExecutionContractCompatibilityValidator.CompatibilityArea.EXECUTOR_TARGET_TYPING))
        assertTrue(result.activeAreas.contains(ExecutionContractCompatibilityValidator.CompatibilityArea.CONTINUITY_RECOVERY))
        assertTrue(result.activeAreas.contains(ExecutionContractCompatibilityValidator.CompatibilityArea.OBSERVABILITY_TRACING))
        assertTrue(result.activeAreas.contains(ExecutionContractCompatibilityValidator.CompatibilityArea.POLICY_ROUTING))
    }

    @Test
    fun `isFullyEvolved requires all five evolved areas`() {
        // Four areas but no policy routing — not fully evolved
        val fourAreaResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(
                task_id = "t-four-ev",
                goal = "test",
                dispatch_plan_id = "plan-four",
                executor_target_type = ExecutorTargetType.ANDROID_DEVICE,
                continuity_token = "ct-four",
                dispatch_trace_id = "dt-four"
            )
        )
        assertFalse(
            "Payload with only four evolved contract areas must NOT be isFullyEvolved (requires five)",
            fourAreaResult.isFullyEvolved
        )

        // All five areas — fully evolved
        val fiveAreaResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(
                task_id = "t-five-ev",
                goal = "test",
                dispatch_plan_id = "plan-five",
                executor_target_type = ExecutorTargetType.ANDROID_DEVICE,
                continuity_token = "ct-five",
                dispatch_trace_id = "dt-five",
                policy_routing_outcome = PolicyRoutingContext.RoutingOutcome.ACCEPTED.wireValue
            )
        )
        assertTrue(
            "Payload with all five evolved contract areas must be isFullyEvolved",
            fiveAreaResult.isFullyEvolved
        )
    }

    // ── StabilizationBaseline — PR-49 entries registered ─────────────────────

    @Test
    fun `policy-routing-context is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("policy-routing-context")
        assertNotNull(
            "policy-routing-context must be registered in StabilizationBaseline",
            entry
        )
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `goal-execution-payload-policy-routing-fields is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("goal-execution-payload-policy-routing-fields")
        assertNotNull(
            "goal-execution-payload-policy-routing-fields must be registered in StabilizationBaseline",
            entry
        )
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `goal-result-payload-policy-routing-outcome-echo is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("goal-result-payload-policy-routing-outcome-echo")
        assertNotNull(
            "goal-result-payload-policy-routing-outcome-echo must be registered in StabilizationBaseline",
            entry
        )
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `all PR-49 entries have introducedPr equal to 49`() {
        val pr49Ids = listOf(
            "policy-routing-context",
            "goal-execution-payload-policy-routing-fields",
            "goal-result-payload-policy-routing-outcome-echo"
        )
        pr49Ids.forEach { id ->
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Entry '$id' must be registered in StabilizationBaseline", entry)
            assertEquals(
                "Entry '$id' must have introducedPr = 49",
                49,
                entry!!.introducedPr
            )
        }
    }

    @Test
    fun `all PR-49 entries have EXTEND guidance`() {
        val pr49Ids = listOf(
            "policy-routing-context",
            "goal-execution-payload-policy-routing-fields",
            "goal-result-payload-policy-routing-outcome-echo"
        )
        pr49Ids.forEach { id ->
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
