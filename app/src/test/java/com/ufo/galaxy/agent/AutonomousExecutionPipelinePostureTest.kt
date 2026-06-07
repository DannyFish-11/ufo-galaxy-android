package com.ufo.galaxy.agent

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.runtime.SourceRuntimePosture
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests proving posture-aware dispatch gating in [AutonomousExecutionPipeline].
 *
 * This is part of **PR package 2 (ANDROID side)** of the post-533 dual-repo runtime
 * unification master plan: *Posture-aware Dispatch Eligibility*.
 *
 * ## What is tested
 * The posture gate in [AutonomousExecutionPipeline.handleGoalExecution] and
 * [AutonomousExecutionPipeline.handleParallelSubtask] checks
 * [GoalExecutionPayload.source_runtime_posture] **after** the runtime and feature gates
 * but **before** any execution is attempted:
 *
 * - [SourceRuntimePosture.CONTROL_ONLY] (or null / absent): execution is blocked,
 *   returning [AutonomousExecutionPipeline.STATUS_DISABLED] with error reason
 *   [AutonomousExecutionPipeline.REASON_POSTURE_CONTROL_ONLY].
 * - [SourceRuntimePosture.JOIN_RUNTIME]: execution proceeds and a real result is returned.
 *
 * ## Significance
 * Gateway-dispatched messages (`goal_execution`, `parallel_subtask`) carry the
 * `source_runtime_posture` of the originating runtime context.  When the source
 * declared `control_only`, Android must not act as a runtime executor for the task —
 * doing so would violate the canonical posture contract established by the main
 * repo's PR #533 and PR #538.
 */
class AutonomousExecutionPipelinePostureTest {

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
        taskId: String = "t-001",
        goal: String = "open WeChat",
        posture: String? = null,
        groupId: String? = null,
        subtaskIndex: Int? = null
    ) = GoalExecutionPayload(
        task_id = taskId,
        goal = goal,
        group_id = groupId,
        subtask_index = subtaskIndex,
        max_steps = 5,
        source_runtime_posture = posture
    )

    // ── handleGoalExecution: CONTROL_ONLY posture blocks ─────────────────────

    @Test
    fun `goal_execution with control_only posture returns STATUS_DISABLED`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(posture = SourceRuntimePosture.CONTROL_ONLY)
        )
        assertEquals(
            "CONTROL_ONLY posture must block goal_execution with STATUS_DISABLED",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }

    @Test
    fun `goal_execution with control_only posture error reason is posture_control_only`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(posture = SourceRuntimePosture.CONTROL_ONLY)
        )
        assertEquals(
            "Error reason must be posture_control_only",
            AutonomousExecutionPipeline.REASON_POSTURE_CONTROL_ONLY,
            result.error
        )
    }

    @Test
    fun `goal_execution with null posture (defaults to control_only) returns STATUS_DISABLED`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(posture = null))
        assertEquals(
            "Null posture is normalised to CONTROL_ONLY and must block execution",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
        assertEquals(AutonomousExecutionPipeline.REASON_POSTURE_CONTROL_ONLY, result.error)
    }

    @Test
    fun `goal_execution with absent posture echoes task_id in blocked result`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(taskId = "posture-block-001", posture = null))
        assertEquals("posture-block-001", result.task_id)
        assertEquals("posture-block-001", result.correlation_id)
    }

    @Test
    fun `goal_execution with control_only posture blocked result has zero latency`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(posture = SourceRuntimePosture.CONTROL_ONLY)
        )
        assertEquals(0L, result.latency_ms)
    }

    @Test
    fun `goal_execution with control_only posture blocked result echoes group_id and subtask_index`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(posture = SourceRuntimePosture.CONTROL_ONLY, groupId = "grp-p", subtaskIndex = 2)
        )
        assertEquals("grp-p", result.group_id)
        assertEquals(2, result.subtask_index)
    }

    @Test
    fun `goal_execution with control_only posture blocked result includes device_id and device_role`() {
        val pipeline = buildPipeline(deviceId = "dev-posture", deviceRole = "tablet")
        val result = pipeline.handleGoalExecution(
            goalPayload(posture = SourceRuntimePosture.CONTROL_ONLY)
        )
        assertEquals("dev-posture", result.device_id)
        assertEquals("tablet", result.device_role)
    }

    // ── handleGoalExecution: JOIN_RUNTIME posture allows execution ────────────

    @Test
    fun `goal_execution with join_runtime posture proceeds and returns success`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(posture = SourceRuntimePosture.JOIN_RUNTIME)
        )
        assertEquals(
            "JOIN_RUNTIME posture must allow goal_execution to proceed",
            EdgeExecutor.STATUS_SUCCESS,
            result.status
        )
    }

    @Test
    fun `goal_execution with join_runtime posture does not return posture_control_only error`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(posture = SourceRuntimePosture.JOIN_RUNTIME)
        )
        assertNotEquals(
            "JOIN_RUNTIME must not produce posture_control_only error",
            AutonomousExecutionPipeline.REASON_POSTURE_CONTROL_ONLY,
            result.error
        )
    }

    // ── handleParallelSubtask: CONTROL_ONLY posture blocks ────────────────────

    @Test
    fun `parallel_subtask with control_only posture returns STATUS_DISABLED`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(posture = SourceRuntimePosture.CONTROL_ONLY, groupId = "grp", subtaskIndex = 0)
        )
        assertEquals(
            "CONTROL_ONLY posture must block parallel_subtask with STATUS_DISABLED",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }

    @Test
    fun `parallel_subtask with control_only posture error reason is posture_control_only`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(posture = SourceRuntimePosture.CONTROL_ONLY, groupId = "grp", subtaskIndex = 0)
        )
        assertEquals(
            "Error reason must be posture_control_only",
            AutonomousExecutionPipeline.REASON_POSTURE_CONTROL_ONLY,
            result.error
        )
    }

    @Test
    fun `parallel_subtask with null posture returns STATUS_DISABLED`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(posture = null, groupId = "grp-null", subtaskIndex = 1)
        )
        assertEquals(AutonomousExecutionPipeline.STATUS_DISABLED, result.status)
        assertEquals(AutonomousExecutionPipeline.REASON_POSTURE_CONTROL_ONLY, result.error)
    }

    @Test
    fun `parallel_subtask with control_only posture echoes group_id and subtask_index`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(taskId = "sub-p", posture = SourceRuntimePosture.CONTROL_ONLY, groupId = "grp-q", subtaskIndex = 3)
        )
        assertEquals("sub-p", result.task_id)
        assertEquals("grp-q", result.group_id)
        assertEquals(3, result.subtask_index)
    }

    // ── handleParallelSubtask: JOIN_RUNTIME posture allows execution ───────────

    @Test
    fun `parallel_subtask with join_runtime posture proceeds and returns success`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(posture = SourceRuntimePosture.JOIN_RUNTIME, groupId = "grp-ok", subtaskIndex = 0)
        )
        assertEquals(
            "JOIN_RUNTIME posture must allow parallel_subtask to proceed",
            EdgeExecutor.STATUS_SUCCESS,
            result.status
        )
    }

    @Test
    fun `parallel_subtask with join_runtime posture does not produce posture_control_only error`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(posture = SourceRuntimePosture.JOIN_RUNTIME, groupId = "grp-ok", subtaskIndex = 0)
        )
        assertNotEquals(
            AutonomousExecutionPipeline.REASON_POSTURE_CONTROL_ONLY,
            result.error
        )
    }

    // ── Posture gate order: posture checked after runtime/feature gates ────────

    @Test
    fun `goal_execution with control_only posture but disabled feature still returns STATUS_DISABLED`() {
        // Both feature gate and posture gate produce STATUS_DISABLED; posture is checked last
        // but the outcome is the same.  This confirms the gate ordering does not matter here.
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(
            goalPayload(posture = SourceRuntimePosture.CONTROL_ONLY)
        )
        assertEquals(AutonomousExecutionPipeline.STATUS_DISABLED, result.status)
    }

    @Test
    fun `goal_execution blocked by posture has distinct error reason from feature gate`() {
        val pipeline = buildPipeline(goalExecutionEnabled = true)
        val featurePipeline = buildPipeline(goalExecutionEnabled = false)

        val postureBlocked = pipeline.handleGoalExecution(
            goalPayload(posture = SourceRuntimePosture.CONTROL_ONLY)
        )
        val featureBlocked = featurePipeline.handleGoalExecution(
            goalPayload(posture = SourceRuntimePosture.JOIN_RUNTIME)
        )

        assertEquals(AutonomousExecutionPipeline.STATUS_DISABLED, postureBlocked.status)
        assertEquals(AutonomousExecutionPipeline.STATUS_DISABLED, featureBlocked.status)
        assertNotEquals(
            "Posture gate and feature gate must produce distinct error reasons",
            postureBlocked.error,
            featureBlocked.error
        )
    }

    // ── Posture does not affect runtime gate (crossDeviceEnabled=false) ────────

    @Test
    fun `goal_execution with join_runtime posture is still blocked when cross-device is disabled`() {
        val pipeline = buildPipeline(crossDeviceEnabled = false)
        val result = pipeline.handleGoalExecution(
            goalPayload(posture = SourceRuntimePosture.JOIN_RUNTIME)
        )
        assertEquals(
            "Runtime gate (crossDeviceEnabled=false) must still block even with join_runtime posture",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }
}
