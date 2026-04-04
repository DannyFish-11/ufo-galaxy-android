package com.ufo.galaxy.agent

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.protocol.GoalExecutionPayload
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AutonomousExecutionPipeline].
 *
 * Tests cover:
 *  - goal_execution returns STATUS_DISABLED when goalExecutionEnabled=false
 *  - goal_execution executes and returns success when goalExecutionEnabled=true
 *  - parallel_subtask returns STATUS_DISABLED when parallelExecutionEnabled=false
 *  - parallel_subtask executes and returns success when parallelExecutionEnabled=true
 *  - disabled result echoes task_id, group_id, subtask_index for gateway aggregation
 *  - disabled result includes device_id and device_role
 *  - enabled result includes device_role from settings
 *  - error from inner executor is propagated with device_role attached
 *
 * All fakes mirror the shape of those in [LocalGoalExecutorTest] to keep tests
 * self-contained.
 */
class AutonomousExecutionPipelineTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeScreenshot(
        private val bytes: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
        private val w: Int = 1080,
        private val h: Int = 2340,
        private val shouldThrow: Boolean = false
    ) : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg(): ByteArray {
            if (shouldThrow) throw IllegalStateException("no capture")
            return bytes
        }
        override fun screenWidth() = w
        override fun screenHeight() = h
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
            LocalGroundingService.GroundingResult(
                x = 540, y = 960, confidence = 0.9f,
                element_description = "button"
            )
    }

    private class OkAccessibility : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    private fun buildEdgeExecutor() = EdgeExecutor(
        screenshotProvider = FakeScreenshot(),
        plannerService = OkPlanner(),
        groundingService = OkGrounder(),
        accessibilityExecutor = OkAccessibility()
    )

    private fun buildPipeline(
        goalExecutionEnabled: Boolean = true,
        parallelExecutionEnabled: Boolean = true,
        crossDeviceEnabled: Boolean = true,
        deviceId: String = "test-device",
        deviceRole: String = "phone"
    ): AutonomousExecutionPipeline {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = crossDeviceEnabled,
            goalExecutionEnabled = goalExecutionEnabled,
            parallelExecutionEnabled = parallelExecutionEnabled,
            deviceRole = deviceRole
        )
        val edge = buildEdgeExecutor()
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

    private fun buildGoalPayload(
        taskId: String = "t-001",
        goal: String = "open WeChat",
        groupId: String? = null,
        subtaskIndex: Int? = null,
        sourceRuntimePosture: String? = null
    ) = GoalExecutionPayload(
        task_id = taskId,
        goal = goal,
        group_id = groupId,
        subtask_index = subtaskIndex,
        max_steps = 5,
        source_runtime_posture = sourceRuntimePosture
    )

    // ── goal_execution disabled ───────────────────────────────────────────────

    @Test
    fun `goal_execution returns STATUS_DISABLED when goalExecutionEnabled is false`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(buildGoalPayload())
        assertEquals(AutonomousExecutionPipeline.STATUS_DISABLED, result.status)
    }

    @Test
    fun `goal_execution disabled result echoes task_id`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(buildGoalPayload(taskId = "g-disabled"))
        assertEquals("g-disabled", result.task_id)
        assertEquals("g-disabled", result.correlation_id)
    }

    @Test
    fun `goal_execution disabled result echoes null group_id and subtask_index`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(buildGoalPayload())
        assertNull(result.group_id)
        assertNull(result.subtask_index)
    }

    @Test
    fun `goal_execution disabled result echoes group_id and subtask_index when provided`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(
            buildGoalPayload(groupId = "grp-dis", subtaskIndex = 3)
        )
        assertEquals("grp-dis", result.group_id)
        assertEquals(3, result.subtask_index)
    }

    @Test
    fun `goal_execution disabled result includes device_id and device_role`() {
        val pipeline = buildPipeline(
            goalExecutionEnabled = false,
            deviceId = "dev-abc",
            deviceRole = "tablet"
        )
        val result = pipeline.handleGoalExecution(buildGoalPayload())
        assertEquals("dev-abc", result.device_id)
        assertEquals("tablet", result.device_role)
    }

    @Test
    fun `goal_execution disabled result has non-null error`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(buildGoalPayload())
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("goal_execution_enabled"))
    }

    @Test
    fun `goal_execution disabled latency_ms is zero`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(buildGoalPayload())
        assertEquals(0L, result.latency_ms)
    }

    // ── goal_execution enabled ────────────────────────────────────────────────

    @Test
    fun `goal_execution executes and returns success when enabled`() {
        val pipeline = buildPipeline(goalExecutionEnabled = true)
        val result = pipeline.handleGoalExecution(buildGoalPayload())
        assertEquals(EdgeExecutor.STATUS_SUCCESS, result.status)
    }

    @Test
    fun `goal_execution enabled result includes device_role`() {
        val pipeline = buildPipeline(goalExecutionEnabled = true, deviceRole = "hub")
        val result = pipeline.handleGoalExecution(buildGoalPayload())
        assertEquals("hub", result.device_role)
    }

    @Test
    fun `goal_execution enabled result has positive latency_ms`() {
        val pipeline = buildPipeline(goalExecutionEnabled = true)
        val result = pipeline.handleGoalExecution(buildGoalPayload())
        assertTrue("latency_ms must be non-negative", result.latency_ms >= 0)
    }

    // ── parallel_subtask disabled ─────────────────────────────────────────────

    @Test
    fun `parallel_subtask returns STATUS_DISABLED when parallelExecutionEnabled is false`() {
        val pipeline = buildPipeline(parallelExecutionEnabled = false)
        val result = pipeline.handleParallelSubtask(
            buildGoalPayload(groupId = "grp", subtaskIndex = 0)
        )
        assertEquals(AutonomousExecutionPipeline.STATUS_DISABLED, result.status)
    }

    @Test
    fun `parallel_subtask disabled result echoes group_id and subtask_index`() {
        val pipeline = buildPipeline(parallelExecutionEnabled = false)
        val result = pipeline.handleParallelSubtask(
            buildGoalPayload(taskId = "sub-dis", groupId = "grp-xyz", subtaskIndex = 2)
        )
        assertEquals("sub-dis", result.task_id)
        assertEquals("grp-xyz", result.group_id)
        assertEquals(2, result.subtask_index)
    }

    @Test
    fun `parallel_subtask disabled result includes device_id and device_role`() {
        val pipeline = buildPipeline(
            parallelExecutionEnabled = false,
            deviceId = "phone-001",
            deviceRole = "phone"
        )
        val result = pipeline.handleParallelSubtask(buildGoalPayload())
        assertEquals("phone-001", result.device_id)
        assertEquals("phone", result.device_role)
    }

    @Test
    fun `parallel_subtask disabled result has non-null error`() {
        val pipeline = buildPipeline(parallelExecutionEnabled = false)
        val result = pipeline.handleParallelSubtask(buildGoalPayload())
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("parallel_execution_enabled"))
    }

    // ── parallel_subtask enabled ──────────────────────────────────────────────

    @Test
    fun `parallel_subtask executes and returns success when enabled`() {
        val pipeline = buildPipeline(parallelExecutionEnabled = true)
        val result = pipeline.handleParallelSubtask(
            buildGoalPayload(groupId = "grp-active", subtaskIndex = 1)
        )
        assertEquals(EdgeExecutor.STATUS_SUCCESS, result.status)
        assertEquals("grp-active", result.group_id)
        assertEquals(1, result.subtask_index)
    }

    @Test
    fun `parallel_subtask enabled result includes device_role`() {
        val pipeline = buildPipeline(parallelExecutionEnabled = true, deviceRole = "tablet")
        val result = pipeline.handleParallelSubtask(buildGoalPayload())
        assertEquals("tablet", result.device_role)
    }

    // ── mixed flag states ─────────────────────────────────────────────────────

    @Test
    fun `goal_execution enabled does not affect parallel_subtask when parallel disabled`() {
        val pipeline = buildPipeline(goalExecutionEnabled = true, parallelExecutionEnabled = false)

        val goalResult = pipeline.handleGoalExecution(buildGoalPayload(taskId = "g-on"))
        assertEquals(EdgeExecutor.STATUS_SUCCESS, goalResult.status)

        val subtaskResult = pipeline.handleParallelSubtask(
            buildGoalPayload(taskId = "p-off", groupId = "g", subtaskIndex = 0)
        )
        assertEquals(AutonomousExecutionPipeline.STATUS_DISABLED, subtaskResult.status)
    }

    @Test
    fun `parallel_subtask enabled does not affect goal_execution when goal disabled`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false, parallelExecutionEnabled = true)

        val goalResult = pipeline.handleGoalExecution(buildGoalPayload(taskId = "g-off"))
        assertEquals(AutonomousExecutionPipeline.STATUS_DISABLED, goalResult.status)

        val subtaskResult = pipeline.handleParallelSubtask(
            buildGoalPayload(taskId = "p-on", groupId = "g", subtaskIndex = 0)
        )
        assertEquals(EdgeExecutor.STATUS_SUCCESS, subtaskResult.status)
    }

    // ── GoalResultPayload schema fields ───────────────────────────────────────

    @Test
    fun `GoalResultPayload has device_role field`() {
        val pipeline = buildPipeline(goalExecutionEnabled = true, deviceRole = "phone")
        val result = pipeline.handleGoalExecution(buildGoalPayload())
        // Verifies that the device_role field exists and is populated
        assertNotNull(result.device_role)
        assertEquals("phone", result.device_role)
    }

    @Test
    fun `GoalResultPayload disabled result carries all aggregation fields`() {
        val pipeline = buildPipeline(
            goalExecutionEnabled = false,
            deviceId = "agg-device",
            deviceRole = "tablet"
        )
        val result = pipeline.handleGoalExecution(
            buildGoalPayload(taskId = "agg-001", groupId = "grp-agg", subtaskIndex = 4)
        )
        assertEquals("agg-001", result.task_id)
        assertEquals("agg-001", result.correlation_id)
        assertEquals(AutonomousExecutionPipeline.STATUS_DISABLED, result.status)
        assertEquals("grp-agg", result.group_id)
        assertEquals(4, result.subtask_index)
        assertEquals(0L, result.latency_ms)
        assertEquals("agg-device", result.device_id)
        assertEquals("tablet", result.device_role)
    }

    // ── Posture-aware execution (PR-2A) ───────────────────────────────────────

    @Test
    fun `goal_execution with control_only posture echoes posture in result`() {
        val pipeline = buildPipeline(goalExecutionEnabled = true)
        val result = pipeline.handleGoalExecution(
            buildGoalPayload(sourceRuntimePosture = com.ufo.galaxy.runtime.SourceRuntimePosture.CONTROL_ONLY)
        )
        assertEquals(
            "Result must echo control_only posture from inbound payload",
            com.ufo.galaxy.runtime.SourceRuntimePosture.CONTROL_ONLY,
            result.source_runtime_posture
        )
    }

    @Test
    fun `goal_execution with join_runtime posture echoes posture in result`() {
        val pipeline = buildPipeline(goalExecutionEnabled = true)
        val result = pipeline.handleGoalExecution(
            buildGoalPayload(sourceRuntimePosture = com.ufo.galaxy.runtime.SourceRuntimePosture.JOIN_RUNTIME)
        )
        assertEquals(
            "Result must echo join_runtime posture from inbound payload",
            com.ufo.galaxy.runtime.SourceRuntimePosture.JOIN_RUNTIME,
            result.source_runtime_posture
        )
    }

    @Test
    fun `goal_execution control_only and join_runtime both succeed — posture does not block gateway tasks`() {
        // Android (as TARGET of a gateway task) must execute regardless of source posture.
        // The distinction is expressed in the echoed posture, not in whether execution proceeds.
        val pipeline = buildPipeline(goalExecutionEnabled = true)

        val controlOnlyResult = pipeline.handleGoalExecution(
            buildGoalPayload(taskId = "co-001",
                sourceRuntimePosture = com.ufo.galaxy.runtime.SourceRuntimePosture.CONTROL_ONLY)
        )
        val joinRuntimeResult = pipeline.handleGoalExecution(
            buildGoalPayload(taskId = "jr-001",
                sourceRuntimePosture = com.ufo.galaxy.runtime.SourceRuntimePosture.JOIN_RUNTIME)
        )

        assertEquals(
            "control_only gateway task must succeed (Android is sole executor)",
            EdgeExecutor.STATUS_SUCCESS,
            controlOnlyResult.status
        )
        assertEquals(
            "join_runtime gateway task must succeed (Android is co-executor)",
            EdgeExecutor.STATUS_SUCCESS,
            joinRuntimeResult.status
        )
    }

    @Test
    fun `goal_execution with unknown posture normalises to control_only in result`() {
        val pipeline = buildPipeline(goalExecutionEnabled = true)
        val result = pipeline.handleGoalExecution(
            buildGoalPayload(sourceRuntimePosture = "future_unknown_value")
        )
        assertEquals(
            "Unknown posture must be normalised to control_only in result",
            com.ufo.galaxy.runtime.SourceRuntimePosture.CONTROL_ONLY,
            result.source_runtime_posture
        )
    }

    @Test
    fun `goal_execution with null posture normalises to control_only in result`() {
        val pipeline = buildPipeline(goalExecutionEnabled = true)
        val result = pipeline.handleGoalExecution(buildGoalPayload(sourceRuntimePosture = null))
        assertEquals(
            "Null posture must be normalised to control_only in result",
            com.ufo.galaxy.runtime.SourceRuntimePosture.CONTROL_ONLY,
            result.source_runtime_posture
        )
    }

    @Test
    fun `disabled result echoes control_only posture`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(
            buildGoalPayload(
                sourceRuntimePosture = com.ufo.galaxy.runtime.SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertEquals(
            "Disabled result must echo posture for gateway result attribution",
            com.ufo.galaxy.runtime.SourceRuntimePosture.CONTROL_ONLY,
            result.source_runtime_posture
        )
    }

    @Test
    fun `disabled result echoes join_runtime posture`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(
            buildGoalPayload(
                sourceRuntimePosture = com.ufo.galaxy.runtime.SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertEquals(
            "Disabled result must echo join_runtime posture for gateway result attribution",
            com.ufo.galaxy.runtime.SourceRuntimePosture.JOIN_RUNTIME,
            result.source_runtime_posture
        )
    }

    @Test
    fun `parallel_subtask with control_only posture echoes posture in result`() {
        val pipeline = buildPipeline(parallelExecutionEnabled = true)
        val result = pipeline.handleParallelSubtask(
            buildGoalPayload(
                groupId = "grp-co",
                subtaskIndex = 0,
                sourceRuntimePosture = com.ufo.galaxy.runtime.SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertEquals(
            "Parallel subtask result must echo control_only posture",
            com.ufo.galaxy.runtime.SourceRuntimePosture.CONTROL_ONLY,
            result.source_runtime_posture
        )
    }

    @Test
    fun `parallel_subtask with join_runtime posture echoes posture in result`() {
        val pipeline = buildPipeline(parallelExecutionEnabled = true)
        val result = pipeline.handleParallelSubtask(
            buildGoalPayload(
                groupId = "grp-jr",
                subtaskIndex = 1,
                sourceRuntimePosture = com.ufo.galaxy.runtime.SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertEquals(
            "Parallel subtask result must echo join_runtime posture",
            com.ufo.galaxy.runtime.SourceRuntimePosture.JOIN_RUNTIME,
            result.source_runtime_posture
        )
    }

    @Test
    fun `parallel_subtask disabled result echoes posture`() {
        val pipeline = buildPipeline(parallelExecutionEnabled = false)
        val result = pipeline.handleParallelSubtask(
            buildGoalPayload(
                sourceRuntimePosture = com.ufo.galaxy.runtime.SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertEquals(AutonomousExecutionPipeline.STATUS_DISABLED, result.status)
        assertEquals(
            "Disabled parallel subtask result must echo join_runtime posture",
            com.ufo.galaxy.runtime.SourceRuntimePosture.JOIN_RUNTIME,
            result.source_runtime_posture
        )
    }
}

