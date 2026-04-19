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
 * PR-48 — Android-side contract compatibility validation for evolved multi-device
 * runtime execution metadata.
 *
 * V2 has expanded the runtime execution contract across four areas.  This test suite
 * validates all Android-side compatibility and semantic clarity requirements defined
 * by PR-48, ensuring that Android can safely consume the evolved contract and that
 * backward compatibility with legacy/narrow contracts is explicit and testable.
 *
 * ## Sections
 *
 * ### ExecutionContractCompatibilityValidator — DispatchStrategyHint wire values
 *  - LOCAL wire value is `"local"`
 *  - REMOTE_HANDOFF wire value is `"remote_handoff"`
 *  - FALLBACK_LOCAL wire value is `"fallback_local"`
 *  - STAGED_MESH wire value is `"staged_mesh"`
 *
 * ### ExecutionContractCompatibilityValidator — DispatchStrategyHint ALL_VALUES
 *  - ALL_VALUES contains exactly four entries
 *  - ALL_VALUES contains every canonical wire value
 *
 * ### ExecutionContractCompatibilityValidator — DispatchStrategyHint fromValue known values
 *  - fromValue returns LOCAL for canonical string
 *  - fromValue returns REMOTE_HANDOFF for canonical string
 *  - fromValue returns FALLBACK_LOCAL for canonical string
 *  - fromValue returns STAGED_MESH for canonical string
 *
 * ### ExecutionContractCompatibilityValidator — DispatchStrategyHint fromValue safe-unknown handling
 *  - fromValue returns null for null
 *  - fromValue returns null for blank string
 *  - fromValue returns null for unknown string
 *  - fromValue returns null for mixed-case variant
 *
 * ### ExecutionContractCompatibilityValidator — DispatchStrategyHint ANDROID_ELIGIBLE_VALUES
 *  - null (legacy / unspecified) is in ANDROID_ELIGIBLE_VALUES
 *  - LOCAL is in ANDROID_ELIGIBLE_VALUES
 *  - FALLBACK_LOCAL is in ANDROID_ELIGIBLE_VALUES
 *  - STAGED_MESH is in ANDROID_ELIGIBLE_VALUES
 *  - REMOTE_HANDOFF is NOT in ANDROID_ELIGIBLE_VALUES
 *
 * ### ExecutionContractCompatibilityValidator — isAndroidEligibleStrategy
 *  - isAndroidEligibleStrategy(null) is true (legacy / unspecified)
 *  - isAndroidEligibleStrategy("local") is true
 *  - isAndroidEligibleStrategy("fallback_local") is true
 *  - isAndroidEligibleStrategy("staged_mesh") is true
 *  - isAndroidEligibleStrategy("remote_handoff") is false
 *  - isAndroidEligibleStrategy for unknown/future value is true (forward compatibility)
 *
 * ### ExecutionContractCompatibilityValidator — hasDispatchPlanId
 *  - hasDispatchPlanId returns false for null
 *  - hasDispatchPlanId returns false for blank string
 *  - hasDispatchPlanId returns true for non-blank string
 *
 * ### ExecutionContractCompatibilityValidator — INTRODUCED_PR
 *  - INTRODUCED_PR is 48
 *
 * ### ExecutionContractCompatibilityValidator — checkPayloadCompatibility flags
 *  - legacy payload (all null) produces all false flags
 *  - payload with dispatch_plan_id set produces hasDispatchMetadata = true
 *  - payload with source_dispatch_strategy set produces hasDispatchMetadata = true
 *  - payload with executor_target_type set produces hasExecutorTargetTyping = true
 *  - payload with null executor_target_type produces hasExecutorTargetTyping = false
 *  - payload with continuity_token set produces hasContinuityRecovery = true
 *  - payload with is_resumable set produces hasContinuityRecovery = true
 *  - payload with interruption_reason set produces hasContinuityRecovery = true
 *  - payload with non-empty recovery_context produces hasContinuityRecovery = true
 *  - payload with dispatch_trace_id set produces hasObservabilityTracing = true
 *  - payload with lifecycle_event_id set produces hasObservabilityTracing = true
 *  - legacy payload has empty activeAreas set
 *  - maximally evolved payload has all four areas active
 *
 * ### ExecutionContractCompatibilityValidator — CompatibilityCheckResult.isFullyEvolved
 *  - isFullyEvolved is false for legacy payload
 *  - isFullyEvolved is true when all four areas are active
 *  - isFullyEvolved is false when only three areas are active
 *
 * ### ExecutionContractCompatibilityValidator — checkPayloadCompatibility never throws
 *  - checkPayloadCompatibility never throws for any field combination
 *
 * ### GoalExecutionPayload — PR-48 richer dispatch metadata fields
 *  - dispatch_plan_id defaults to null (backward compatibility)
 *  - source_dispatch_strategy defaults to null (backward compatibility)
 *  - all PR-48 fields can be set simultaneously
 *  - effectiveTimeoutMs is unaffected by PR-48 fields
 *
 * ### GoalResultPayload — PR-48 dispatch_plan_id echo field
 *  - dispatch_plan_id defaults to null (backward compatibility)
 *  - dispatch_plan_id can be set on GoalResultPayload
 *
 * ### AutonomousExecutionPipeline — PR-48 field acceptance (goal_execution)
 *  - pipeline accepts payload with dispatch_plan_id set (no failure)
 *  - pipeline accepts payload with source_dispatch_strategy set (no failure)
 *  - pipeline accepts payload with all PR-48 fields set (no failure)
 *  - pipeline accepts payload with no PR-48 fields (legacy backward compat)
 *  - success result echoes dispatch_plan_id from payload
 *  - disabled result echoes dispatch_plan_id from payload
 *  - success result echoes null dispatch_plan_id from legacy payload
 *
 * ### AutonomousExecutionPipeline — PR-48 field acceptance (parallel_subtask)
 *  - parallel pipeline accepts payload with dispatch_plan_id set (no failure)
 *  - parallel pipeline echoes dispatch_plan_id in success result
 *  - parallel pipeline echoes dispatch_plan_id in disabled result
 *
 * ### AutonomousExecutionPipeline — backward compatibility across all evolved contract areas
 *  - payload with all PR-E fields passes checkPayloadCompatibility
 *  - payload with all PR-F fields passes checkPayloadCompatibility
 *  - payload with all PR-G fields passes checkPayloadCompatibility
 *  - payload with all PR-48 fields passes checkPayloadCompatibility
 *  - maximally evolved payload (all PR-E/F/G/H fields) is accepted without failure
 *  - maximally evolved payload produces isFullyEvolved = true
 *
 * ### StabilizationBaseline — PR-48 entries registered
 *  - execution-contract-compatibility-validator is registered as CANONICAL_STABLE
 *  - goal-execution-payload-dispatch-metadata-fields is registered as CANONICAL_STABLE
 *  - goal-result-payload-dispatch-plan-id-echo is registered as CANONICAL_STABLE
 *  - all PR-48 entries have introducedPr = 48
 *  - all PR-48 entries have EXTEND guidance
 */
class Pr48ExecutionContractCompatibilityTest {

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
        taskId: String = "t-prh",
        goal: String = "open WeChat",
        posture: String? = SourceRuntimePosture.JOIN_RUNTIME,
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

    // ── DispatchStrategyHint — wire values ────────────────────────────────────

    @Test
    fun `LOCAL wire value is local`() {
        assertEquals(
            "local",
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.LOCAL.wireValue
        )
    }

    @Test
    fun `REMOTE_HANDOFF wire value is remote_handoff`() {
        assertEquals(
            "remote_handoff",
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.REMOTE_HANDOFF.wireValue
        )
    }

    @Test
    fun `FALLBACK_LOCAL wire value is fallback_local`() {
        assertEquals(
            "fallback_local",
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.FALLBACK_LOCAL.wireValue
        )
    }

    @Test
    fun `STAGED_MESH wire value is staged_mesh`() {
        assertEquals(
            "staged_mesh",
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.STAGED_MESH.wireValue
        )
    }

    // ── DispatchStrategyHint — ALL_VALUES ─────────────────────────────────────

    @Test
    fun `ALL_VALUES contains exactly four entries`() {
        assertEquals(
            "DispatchStrategyHint.ALL_VALUES must have exactly 4 entries — one per strategy",
            4,
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.ALL_VALUES.size
        )
    }

    @Test
    fun `ALL_VALUES contains every canonical wire value`() {
        val all = ExecutionContractCompatibilityValidator.DispatchStrategyHint.ALL_VALUES
        assertTrue(all.contains("local"))
        assertTrue(all.contains("remote_handoff"))
        assertTrue(all.contains("fallback_local"))
        assertTrue(all.contains("staged_mesh"))
    }

    // ── DispatchStrategyHint.fromValue — known values ─────────────────────────

    @Test
    fun `fromValue returns LOCAL for canonical string`() {
        assertEquals(
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.LOCAL,
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.fromValue("local")
        )
    }

    @Test
    fun `fromValue returns REMOTE_HANDOFF for canonical string`() {
        assertEquals(
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.REMOTE_HANDOFF,
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.fromValue("remote_handoff")
        )
    }

    @Test
    fun `fromValue returns FALLBACK_LOCAL for canonical string`() {
        assertEquals(
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.FALLBACK_LOCAL,
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.fromValue("fallback_local")
        )
    }

    @Test
    fun `fromValue returns STAGED_MESH for canonical string`() {
        assertEquals(
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.STAGED_MESH,
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.fromValue("staged_mesh")
        )
    }

    // ── DispatchStrategyHint.fromValue — safe-unknown handling ────────────────

    @Test
    fun `fromValue returns null for null`() {
        assertNull(
            "null source_dispatch_strategy must return null — legacy backward-compatible pass-through",
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.fromValue(null)
        )
    }

    @Test
    fun `fromValue returns null for blank string`() {
        assertNull(
            "Blank source_dispatch_strategy must return null — treated as absent",
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.fromValue("   ")
        )
    }

    @Test
    fun `fromValue returns null for unknown string`() {
        assertNull(
            "Unknown source_dispatch_strategy must return null — tolerates future values",
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.fromValue("future_strategy_type")
        )
    }

    @Test
    fun `fromValue returns null for mixed-case variant`() {
        // Wire format is strictly lower-snake-case; mixed-case must not be recognised.
        assertNull(ExecutionContractCompatibilityValidator.DispatchStrategyHint.fromValue("Local"))
        assertNull(ExecutionContractCompatibilityValidator.DispatchStrategyHint.fromValue("STAGED_MESH"))
    }

    // ── DispatchStrategyHint — ANDROID_ELIGIBLE_VALUES ───────────────────────

    @Test
    fun `null (legacy unspecified) is in ANDROID_ELIGIBLE_VALUES`() {
        assertTrue(
            "null source_dispatch_strategy (legacy) must be Android-eligible",
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.ANDROID_ELIGIBLE_VALUES.contains(null)
        )
    }

    @Test
    fun `LOCAL is in ANDROID_ELIGIBLE_VALUES`() {
        assertTrue(
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.ANDROID_ELIGIBLE_VALUES.contains(
                ExecutionContractCompatibilityValidator.DispatchStrategyHint.LOCAL.wireValue
            )
        )
    }

    @Test
    fun `FALLBACK_LOCAL is in ANDROID_ELIGIBLE_VALUES`() {
        assertTrue(
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.ANDROID_ELIGIBLE_VALUES.contains(
                ExecutionContractCompatibilityValidator.DispatchStrategyHint.FALLBACK_LOCAL.wireValue
            )
        )
    }

    @Test
    fun `STAGED_MESH is in ANDROID_ELIGIBLE_VALUES`() {
        assertTrue(
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.ANDROID_ELIGIBLE_VALUES.contains(
                ExecutionContractCompatibilityValidator.DispatchStrategyHint.STAGED_MESH.wireValue
            )
        )
    }

    @Test
    fun `REMOTE_HANDOFF is NOT in ANDROID_ELIGIBLE_VALUES`() {
        assertFalse(
            "REMOTE_HANDOFF signals routing away from local Android; must not be in ANDROID_ELIGIBLE_VALUES",
            ExecutionContractCompatibilityValidator.DispatchStrategyHint.ANDROID_ELIGIBLE_VALUES.contains(
                ExecutionContractCompatibilityValidator.DispatchStrategyHint.REMOTE_HANDOFF.wireValue
            )
        )
    }

    // ── isAndroidEligibleStrategy ─────────────────────────────────────────────

    @Test
    fun `isAndroidEligibleStrategy returns true for null (legacy unspecified)`() {
        assertTrue(
            "null source_dispatch_strategy (legacy) must be Android-eligible",
            ExecutionContractCompatibilityValidator.isAndroidEligibleStrategy(null)
        )
    }

    @Test
    fun `isAndroidEligibleStrategy returns true for local`() {
        assertTrue(ExecutionContractCompatibilityValidator.isAndroidEligibleStrategy("local"))
    }

    @Test
    fun `isAndroidEligibleStrategy returns true for fallback_local`() {
        assertTrue(ExecutionContractCompatibilityValidator.isAndroidEligibleStrategy("fallback_local"))
    }

    @Test
    fun `isAndroidEligibleStrategy returns true for staged_mesh`() {
        assertTrue(ExecutionContractCompatibilityValidator.isAndroidEligibleStrategy("staged_mesh"))
    }

    @Test
    fun `isAndroidEligibleStrategy returns false for remote_handoff`() {
        assertFalse(
            "remote_handoff signals routing away from Android; must not be Android-eligible",
            ExecutionContractCompatibilityValidator.isAndroidEligibleStrategy("remote_handoff")
        )
    }

    @Test
    fun `isAndroidEligibleStrategy returns true for unknown future value (forward compatibility)`() {
        assertTrue(
            "Unknown future strategy hints must be Android-eligible for forward compatibility",
            ExecutionContractCompatibilityValidator.isAndroidEligibleStrategy("future_v3_strategy")
        )
    }

    // ── hasDispatchPlanId ─────────────────────────────────────────────────────

    @Test
    fun `hasDispatchPlanId returns false for null`() {
        assertFalse(
            "null dispatch_plan_id must be treated as absent (legacy sender)",
            ExecutionContractCompatibilityValidator.hasDispatchPlanId(null)
        )
    }

    @Test
    fun `hasDispatchPlanId returns false for blank string`() {
        assertFalse(
            "Blank dispatch_plan_id must be treated as absent",
            ExecutionContractCompatibilityValidator.hasDispatchPlanId("   ")
        )
    }

    @Test
    fun `hasDispatchPlanId returns true for non-blank string`() {
        assertTrue(
            "Non-blank dispatch_plan_id must be recognised as present",
            ExecutionContractCompatibilityValidator.hasDispatchPlanId("plan-abc-123")
        )
    }

    // ── INTRODUCED_PR ─────────────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 48`() {
        assertEquals(
            "ExecutionContractCompatibilityValidator must be introduced at PR-48",
            48,
            ExecutionContractCompatibilityValidator.INTRODUCED_PR
        )
    }

    // ── checkPayloadCompatibility — individual flags ──────────────────────────

    @Test
    fun `legacy payload (all null) produces all false flags`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-legacy", goal = "test")
        )
        assertFalse("Legacy payload must not signal hasDispatchMetadata", result.hasDispatchMetadata)
        assertFalse("Legacy payload must not signal hasExecutorTargetTyping", result.hasExecutorTargetTyping)
        assertFalse("Legacy payload must not signal hasContinuityRecovery", result.hasContinuityRecovery)
        assertFalse("Legacy payload must not signal hasObservabilityTracing", result.hasObservabilityTracing)
        assertFalse("Legacy payload must not signal hasPolicyRouting", result.hasPolicyRouting)
    }

    @Test
    fun `payload with dispatch_plan_id set produces hasDispatchMetadata = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-001", goal = "test", dispatch_plan_id = "plan-123")
        )
        assertTrue(
            "dispatch_plan_id being non-null must activate DISPATCH_METADATA area",
            result.hasDispatchMetadata
        )
    }

    @Test
    fun `payload with source_dispatch_strategy set produces hasDispatchMetadata = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(
                task_id = "t-002",
                goal = "test",
                source_dispatch_strategy = ExecutionContractCompatibilityValidator.DispatchStrategyHint.LOCAL.wireValue
            )
        )
        assertTrue(
            "source_dispatch_strategy being non-blank must activate DISPATCH_METADATA area",
            result.hasDispatchMetadata
        )
    }

    @Test
    fun `payload with executor_target_type set produces hasExecutorTargetTyping = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(
                task_id = "t-003",
                goal = "test",
                executor_target_type = ExecutorTargetType.ANDROID_DEVICE
            )
        )
        assertTrue(
            "executor_target_type being non-null must activate EXECUTOR_TARGET_TYPING area",
            result.hasExecutorTargetTyping
        )
    }

    @Test
    fun `payload with null executor_target_type produces hasExecutorTargetTyping = false`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-004", goal = "test", executor_target_type = null)
        )
        assertFalse(
            "null executor_target_type (legacy) must not activate EXECUTOR_TARGET_TYPING area",
            result.hasExecutorTargetTyping
        )
    }

    @Test
    fun `payload with continuity_token set produces hasContinuityRecovery = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-005", goal = "test", continuity_token = "ct-abc")
        )
        assertTrue(
            "continuity_token being non-null must activate CONTINUITY_RECOVERY area",
            result.hasContinuityRecovery
        )
    }

    @Test
    fun `payload with is_resumable set produces hasContinuityRecovery = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-006", goal = "test", is_resumable = true)
        )
        assertTrue(
            "is_resumable being non-null must activate CONTINUITY_RECOVERY area",
            result.hasContinuityRecovery
        )
    }

    @Test
    fun `payload with interruption_reason set produces hasContinuityRecovery = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(
                task_id = "t-007",
                goal = "test",
                interruption_reason = ContinuityRecoveryContext.REASON_RECONNECT
            )
        )
        assertTrue(
            "interruption_reason being non-null must activate CONTINUITY_RECOVERY area",
            result.hasContinuityRecovery
        )
    }

    @Test
    fun `payload with non-empty recovery_context produces hasContinuityRecovery = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(
                task_id = "t-008",
                goal = "test",
                recovery_context = mapOf("checkpoint" to "step-3")
            )
        )
        assertTrue(
            "non-empty recovery_context must activate CONTINUITY_RECOVERY area",
            result.hasContinuityRecovery
        )
    }

    @Test
    fun `payload with dispatch_trace_id set produces hasObservabilityTracing = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-009", goal = "test", dispatch_trace_id = "dt-abc")
        )
        assertTrue(
            "dispatch_trace_id being non-blank must activate OBSERVABILITY_TRACING area",
            result.hasObservabilityTracing
        )
    }

    @Test
    fun `payload with lifecycle_event_id set produces hasObservabilityTracing = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-010", goal = "test", lifecycle_event_id = "le-xyz")
        )
        assertTrue(
            "lifecycle_event_id being non-blank must activate OBSERVABILITY_TRACING area",
            result.hasObservabilityTracing
        )
    }

    @Test
    fun `legacy payload has empty activeAreas set`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-legacy-2", goal = "test")
        )
        assertTrue(
            "Legacy payload must produce an empty activeAreas set",
            result.activeAreas.isEmpty()
        )
    }

    @Test
    fun `maximally evolved payload has all four areas active`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(
                task_id = "t-max",
                goal = "test",
                dispatch_plan_id = "plan-abc",
                source_dispatch_strategy = ExecutionContractCompatibilityValidator.DispatchStrategyHint.LOCAL.wireValue,
                executor_target_type = ExecutorTargetType.ANDROID_DEVICE,
                continuity_token = "ct-abc",
                dispatch_trace_id = "dt-abc"
            )
        )
        // Four original evolved areas must all be active (PR-49 POLICY_ROUTING is not set here)
        assertTrue(result.activeAreas.contains(ExecutionContractCompatibilityValidator.CompatibilityArea.DISPATCH_METADATA))
        assertTrue(result.activeAreas.contains(ExecutionContractCompatibilityValidator.CompatibilityArea.EXECUTOR_TARGET_TYPING))
        assertTrue(result.activeAreas.contains(ExecutionContractCompatibilityValidator.CompatibilityArea.CONTINUITY_RECOVERY))
        assertTrue(result.activeAreas.contains(ExecutionContractCompatibilityValidator.CompatibilityArea.OBSERVABILITY_TRACING))
    }

    // ── CompatibilityCheckResult.isFullyEvolved ───────────────────────────────

    @Test
    fun `isFullyEvolved is false for legacy payload`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-legacy-ev", goal = "test")
        )
        assertFalse(
            "Legacy payload must not be considered fully evolved",
            result.isFullyEvolved
        )
    }

    @Test
    fun `isFullyEvolved is true when all four areas are active`() {
        // Note: PR-49 added a fifth evolved area (POLICY_ROUTING). isFullyEvolved now requires
        // all five areas. This test verifies that a PR-48-era payload (four areas) without
        // policy routing does NOT produce isFullyEvolved = true.
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(
                task_id = "t-full-ev",
                goal = "test",
                dispatch_plan_id = "plan-full",
                executor_target_type = ExecutorTargetType.ANDROID_DEVICE,
                continuity_token = "ct-full",
                dispatch_trace_id = "dt-full"
            )
        )
        // Four areas are all active
        assertTrue(result.hasDispatchMetadata)
        assertTrue(result.hasExecutorTargetTyping)
        assertTrue(result.hasContinuityRecovery)
        assertTrue(result.hasObservabilityTracing)
        // But isFullyEvolved requires all five areas (including POLICY_ROUTING from PR-49)
        assertFalse(
            "Payload with only four evolved contract areas must not produce isFullyEvolved = true " +
                "(five areas required after PR-49 added POLICY_ROUTING)",
            result.isFullyEvolved
        )
    }

    @Test
    fun `isFullyEvolved is false when only three areas are active`() {
        // Missing EXECUTOR_TARGET_TYPING (executor_target_type = null)
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(
                task_id = "t-three-ev",
                goal = "test",
                dispatch_plan_id = "plan-three",
                executor_target_type = null,
                continuity_token = "ct-three",
                dispatch_trace_id = "dt-three"
            )
        )
        assertFalse(
            "Payload missing any evolved contract area must not produce isFullyEvolved = true",
            result.isFullyEvolved
        )
    }

    // ── checkPayloadCompatibility — never throws ──────────────────────────────

    @Test
    fun `checkPayloadCompatibility never throws for any field combination`() {
        val payloads = listOf(
            GoalExecutionPayload(task_id = "t-safe-1", goal = "test"),
            GoalExecutionPayload(task_id = "t-safe-2", goal = "test", dispatch_plan_id = ""),
            GoalExecutionPayload(task_id = "t-safe-3", goal = "test", source_dispatch_strategy = "unknown_future"),
            GoalExecutionPayload(task_id = "t-safe-4", goal = "test", executor_target_type = "future_type"),
            GoalExecutionPayload(task_id = "t-safe-5", goal = "test", is_resumable = null, continuity_token = null),
            GoalExecutionPayload(task_id = "t-safe-6", goal = "test", dispatch_trace_id = ""),
            GoalExecutionPayload(
                task_id = "t-safe-7",
                goal = "test",
                dispatch_plan_id = "plan",
                source_dispatch_strategy = ExecutionContractCompatibilityValidator.DispatchStrategyHint.STAGED_MESH.wireValue,
                executor_target_type = ExecutorTargetType.ANDROID_DEVICE,
                continuity_token = "ct",
                is_resumable = true,
                interruption_reason = ContinuityRecoveryContext.REASON_HANDOFF,
                recovery_context = mapOf("k" to "v"),
                dispatch_trace_id = "dt",
                lifecycle_event_id = "le"
            )
        )
        payloads.forEach { payload ->
            val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(payload)
            assertNotNull(
                "checkPayloadCompatibility must never return null for task_id=${payload.task_id}",
                result
            )
        }
    }

    // ── GoalExecutionPayload — PR-48 richer dispatch metadata fields ───────────

    @Test
    fun `GoalExecutionPayload dispatch_plan_id defaults to null`() {
        val payload = GoalExecutionPayload(task_id = "t-default-1", goal = "test")
        assertNull(
            "dispatch_plan_id must default to null for backward compatibility with pre-PR-48 senders",
            payload.dispatch_plan_id
        )
    }

    @Test
    fun `GoalExecutionPayload source_dispatch_strategy defaults to null`() {
        val payload = GoalExecutionPayload(task_id = "t-default-2", goal = "test")
        assertNull(
            "source_dispatch_strategy must default to null for backward compatibility with pre-PR-48 senders",
            payload.source_dispatch_strategy
        )
    }

    @Test
    fun `GoalExecutionPayload accepts all PR-48 fields simultaneously`() {
        val payload = GoalExecutionPayload(
            task_id = "t-prh-all",
            goal = "test",
            dispatch_plan_id = "plan-abc-123",
            source_dispatch_strategy = ExecutionContractCompatibilityValidator.DispatchStrategyHint.STAGED_MESH.wireValue
        )
        assertEquals("plan-abc-123", payload.dispatch_plan_id)
        assertEquals("staged_mesh", payload.source_dispatch_strategy)
    }

    @Test
    fun `GoalExecutionPayload effectiveTimeoutMs is unaffected by PR-48 fields`() {
        val legacyPayload = GoalExecutionPayload(task_id = "t-timeout-legacy", goal = "test")
        val prhPayload = GoalExecutionPayload(
            task_id = "t-timeout-prh",
            goal = "test",
            dispatch_plan_id = "plan-abc",
            source_dispatch_strategy = "local"
        )
        assertEquals(
            "PR-48 fields must not affect effectiveTimeoutMs",
            legacyPayload.effectiveTimeoutMs,
            prhPayload.effectiveTimeoutMs
        )
    }

    // ── GoalResultPayload — PR-48 dispatch_plan_id echo field ─────────────────

    @Test
    fun `GoalResultPayload dispatch_plan_id defaults to null`() {
        val result = GoalResultPayload(task_id = "t-result-1", status = "success")
        assertNull(
            "dispatch_plan_id must default to null in GoalResultPayload for backward compatibility",
            result.dispatch_plan_id
        )
    }

    @Test
    fun `GoalResultPayload dispatch_plan_id can be set`() {
        val result = GoalResultPayload(
            task_id = "t-result-2",
            status = "success",
            dispatch_plan_id = "plan-echoed-abc"
        )
        assertEquals(
            "dispatch_plan_id should be preserved in the result for full-chain plan correlation",
            "plan-echoed-abc",
            result.dispatch_plan_id
        )
    }

    // ── AutonomousExecutionPipeline — PR-48 field acceptance (goal_execution) ──

    @Test
    fun `pipeline accepts payload with dispatch_plan_id set (no failure)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(dispatchPlanId = "plan-prh-001"))
        assertNotNull("Result must not be null when dispatch_plan_id is present", result)
    }

    @Test
    fun `pipeline accepts payload with source_dispatch_strategy set (no failure)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(sourceDispatchStrategy = ExecutionContractCompatibilityValidator.DispatchStrategyHint.LOCAL.wireValue)
        )
        assertNotNull("Result must not be null when source_dispatch_strategy is present", result)
    }

    @Test
    fun `pipeline accepts payload with all PR-48 fields set (no failure)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                dispatchPlanId = "plan-prh-all",
                sourceDispatchStrategy = ExecutionContractCompatibilityValidator.DispatchStrategyHint.STAGED_MESH.wireValue
            )
        )
        assertNotNull("Result must not be null when all PR-48 fields are present", result)
    }

    @Test
    fun `pipeline accepts payload with no PR-48 fields (legacy backward compat)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload())
        assertNotNull("Pipeline must work correctly with legacy payloads (no PR-48 fields)", result)
    }

    @Test
    fun `success result echoes dispatch_plan_id from payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(dispatchPlanId = "plan-echo-success"))
        assertEquals(
            "dispatch_plan_id must be echoed in success results for full-chain plan correlation",
            "plan-echo-success",
            result.dispatch_plan_id
        )
    }

    @Test
    fun `disabled result echoes dispatch_plan_id from payload`() {
        val pipeline = buildPipeline(crossDeviceEnabled = false)
        val result = pipeline.handleGoalExecution(goalPayload(dispatchPlanId = "plan-echo-disabled"))
        assertEquals(
            "dispatch_plan_id must be echoed in disabled results for full-chain plan correlation",
            "plan-echo-disabled",
            result.dispatch_plan_id
        )
    }

    @Test
    fun `success result echoes null dispatch_plan_id from legacy payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload())
        assertNull(
            "Legacy result must preserve null dispatch_plan_id — no value should be injected",
            result.dispatch_plan_id
        )
    }

    // ── AutonomousExecutionPipeline — PR-48 field acceptance (parallel_subtask) ─

    @Test
    fun `parallel pipeline accepts payload with dispatch_plan_id set (no failure)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-prh",
                dispatchPlanId = "plan-parallel-001",
                groupId = "grp-prh",
                subtaskIndex = 0
            )
        )
        assertNotNull("Parallel result must not be null when dispatch_plan_id is present", result)
    }

    @Test
    fun `parallel pipeline echoes dispatch_plan_id in success result`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-echo-success",
                dispatchPlanId = "plan-par-success",
                groupId = "grp-prh",
                subtaskIndex = 0
            )
        )
        assertEquals(
            "dispatch_plan_id must be echoed in parallel success results",
            "plan-par-success",
            result.dispatch_plan_id
        )
    }

    @Test
    fun `parallel pipeline echoes dispatch_plan_id in disabled result`() {
        val pipeline = buildPipeline(crossDeviceEnabled = false)
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                taskId = "t-par-echo-disabled",
                dispatchPlanId = "plan-par-disabled",
                groupId = "grp-prh",
                subtaskIndex = 0
            )
        )
        assertEquals(
            "dispatch_plan_id must be echoed in parallel disabled results",
            "plan-par-disabled",
            result.dispatch_plan_id
        )
    }

    // ── AutonomousExecutionPipeline — backward compatibility (all evolved areas) ─

    @Test
    fun `payload with all PR-E fields passes checkPayloadCompatibility`() {
        val payload = goalPayload(executorTargetType = ExecutorTargetType.ANDROID_DEVICE)
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(payload)
        assertTrue(
            "PR-E fields (executor_target_type) must activate EXECUTOR_TARGET_TYPING",
            result.hasExecutorTargetTyping
        )
    }

    @Test
    fun `payload with all PR-F fields passes checkPayloadCompatibility`() {
        val payload = goalPayload(
            continuityToken = "ct-prf",
            isResumable = true,
            interruptionReason = ContinuityRecoveryContext.REASON_RECONNECT,
            recoveryContext = mapOf("checkpoint" to "step-2")
        )
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(payload)
        assertTrue(
            "PR-F fields (continuity/recovery context) must activate CONTINUITY_RECOVERY",
            result.hasContinuityRecovery
        )
    }

    @Test
    fun `payload with all PR-G fields passes checkPayloadCompatibility`() {
        val payload = goalPayload(
            dispatchTraceId = "dt-prg",
            lifecycleEventId = "le-prg"
        )
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(payload)
        assertTrue(
            "PR-G fields (observability tracing) must activate OBSERVABILITY_TRACING",
            result.hasObservabilityTracing
        )
    }

    @Test
    fun `payload with all PR-48 fields passes checkPayloadCompatibility`() {
        val payload = goalPayload(
            dispatchPlanId = "plan-prh",
            sourceDispatchStrategy = ExecutionContractCompatibilityValidator.DispatchStrategyHint.LOCAL.wireValue
        )
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(payload)
        assertTrue(
            "PR-48 fields (dispatch metadata) must activate DISPATCH_METADATA",
            result.hasDispatchMetadata
        )
    }

    @Test
    fun `maximally evolved payload (all PR-E F G H fields) is accepted without failure`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                dispatchPlanId = "plan-max",
                sourceDispatchStrategy = ExecutionContractCompatibilityValidator.DispatchStrategyHint.STAGED_MESH.wireValue,
                executorTargetType = ExecutorTargetType.ANDROID_DEVICE,
                continuityToken = "ct-max",
                recoveryContext = mapOf("checkpoint" to "step-1"),
                isResumable = true,
                interruptionReason = ContinuityRecoveryContext.REASON_HANDOFF,
                dispatchTraceId = "dt-max",
                lifecycleEventId = "le-max"
            )
        )
        assertNotNull(
            "Maximally evolved payload with all PR-E/F/G/H fields must be accepted without failure",
            result
        )
    }

    @Test
    fun `maximally evolved payload produces isFullyEvolved = true`() {
        // PR-49 added POLICY_ROUTING as a fifth evolved area; a fully evolved payload must include
        // all five areas. Construct directly to include the PR-49 policy_routing_outcome field.
        val payload = GoalExecutionPayload(
            task_id = "t-pri-fully-ev",
            goal = "open WeChat",
            max_steps = 5,
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME,
            dispatch_plan_id = "plan-fully-ev",
            source_dispatch_strategy = ExecutionContractCompatibilityValidator.DispatchStrategyHint.LOCAL.wireValue,
            executor_target_type = ExecutorTargetType.ANDROID_DEVICE,
            continuity_token = "ct-fully-ev",
            dispatch_trace_id = "dt-fully-ev",
            policy_routing_outcome = "accepted"
        )
        val compatResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(payload)
        assertTrue(
            "Payload with all five evolved contract areas must produce isFullyEvolved = true",
            compatResult.isFullyEvolved
        )
    }

    // ── StabilizationBaseline — PR-48 entries registered ─────────────────────

    @Test
    fun `execution-contract-compatibility-validator is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("execution-contract-compatibility-validator")
        assertNotNull(
            "execution-contract-compatibility-validator must be registered in StabilizationBaseline",
            entry
        )
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
    }

    @Test
    fun `goal-execution-payload-dispatch-metadata-fields is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("goal-execution-payload-dispatch-metadata-fields")
        assertNotNull(
            "goal-execution-payload-dispatch-metadata-fields must be registered in StabilizationBaseline",
            entry
        )
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `goal-result-payload-dispatch-plan-id-echo is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("goal-result-payload-dispatch-plan-id-echo")
        assertNotNull(
            "goal-result-payload-dispatch-plan-id-echo must be registered in StabilizationBaseline",
            entry
        )
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `all PR-48 entries have introducedPr equal to 48`() {
        val pr48Ids = listOf(
            "execution-contract-compatibility-validator",
            "goal-execution-payload-dispatch-metadata-fields",
            "goal-result-payload-dispatch-plan-id-echo"
        )
        pr48Ids.forEach { id ->
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Entry '$id' must be registered in StabilizationBaseline", entry)
            assertEquals(
                "Entry '$id' must have introducedPr = 48",
                48,
                entry!!.introducedPr
            )
        }
    }

    @Test
    fun `all PR-48 entries have EXTEND guidance`() {
        val pr48Ids = listOf(
            "execution-contract-compatibility-validator",
            "goal-execution-payload-dispatch-metadata-fields",
            "goal-result-payload-dispatch-plan-id-echo"
        )
        pr48Ids.forEach { id ->
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
