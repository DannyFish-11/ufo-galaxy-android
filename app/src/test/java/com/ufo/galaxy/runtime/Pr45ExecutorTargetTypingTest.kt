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
import org.junit.Assert.*
import org.junit.Test

/**
 * PR-45 — Align Android execution contract handling with explicit executor target typing in V2.
 *
 * V2 introduces an [GoalExecutionPayload.executor_target_type] field that unambiguously identifies
 * which execution surface a command is addressed to.  This test suite validates all aspects of
 * the Android-side compatibility layer defined in [ExecutorTargetType]:
 *
 * ## Sections
 *
 * ### ExecutorTargetType — wire value constants
 *  - ANDROID_DEVICE wire value is `"android_device"`
 *  - NODE_SERVICE wire value is `"node_service"`
 *  - WORKER wire value is `"worker"`
 *  - LOCAL wire value is `"local"`
 *
 * ### ExecutorTargetType — ALL_VALUES
 *  - ALL_VALUES contains exactly four entries
 *  - ALL_VALUES contains every canonical wire value
 *
 * ### ExecutorTargetType — ANDROID_ELIGIBLE_VALUES
 *  - null (legacy / unspecified) is Android-eligible
 *  - ANDROID_DEVICE is Android-eligible
 *  - LOCAL is Android-eligible
 *  - NODE_SERVICE is NOT Android-eligible
 *  - WORKER is NOT Android-eligible
 *
 * ### ExecutorTargetType.fromValue — known values
 *  - fromValue returns ANDROID_DEVICE for canonical string
 *  - fromValue returns NODE_SERVICE for canonical string
 *  - fromValue returns WORKER for canonical string
 *  - fromValue returns LOCAL for canonical string
 *
 * ### ExecutorTargetType.fromValue — safe-unknown handling
 *  - fromValue returns null for null
 *  - fromValue returns null for blank string
 *  - fromValue returns null for unknown string
 *  - fromValue returns null for mixed-case variant (wire format is strictly lower-snake-case)
 *
 * ### ExecutorTargetType.isAndroidEligible
 *  - isAndroidEligible(null) is true
 *  - isAndroidEligible("android_device") is true
 *  - isAndroidEligible("local") is true
 *  - isAndroidEligible("node_service") is false
 *  - isAndroidEligible("worker") is false
 *  - isAndroidEligible("unknown_value") is false
 *
 * ### GoalExecutionPayload — executor_target_type field
 *  - executor_target_type defaults to null (backward compatibility)
 *  - executor_target_type is preserved when explicitly set
 *
 * ### AutonomousExecutionPipeline — target type acceptance (goal_execution)
 *  - pipeline accepts payload with null executor_target_type (legacy backward compat)
 *  - pipeline accepts payload with ANDROID_DEVICE executor_target_type
 *  - pipeline accepts payload with LOCAL executor_target_type
 *  - pipeline accepts (tolerates) payload with NODE_SERVICE executor_target_type
 *  - pipeline accepts (tolerates) payload with unknown executor_target_type
 *  - disabled result echoes executor_target_type from payload
 *  - success result echoes executor_target_type from payload
 *
 * ### AutonomousExecutionPipeline — target type acceptance (parallel_subtask)
 *  - parallel pipeline echoes executor_target_type in disabled result
 *  - parallel pipeline echoes executor_target_type in success result
 */
class Pr45ExecutorTargetTypingTest {

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
        taskId: String = "t-pr45",
        goal: String = "open WeChat",
        posture: String? = SourceRuntimePosture.JOIN_RUNTIME,
        executorTargetType: String? = null,
        groupId: String? = null,
        subtaskIndex: Int? = null
    ) = GoalExecutionPayload(
        task_id = taskId,
        goal = goal,
        group_id = groupId,
        subtask_index = subtaskIndex,
        max_steps = 5,
        source_runtime_posture = posture,
        executor_target_type = executorTargetType
    )

    // ── ExecutorTargetType — wire value constants ─────────────────────────────

    @Test
    fun `ANDROID_DEVICE wire value is android_device`() {
        assertEquals("android_device", ExecutorTargetType.ANDROID_DEVICE)
    }

    @Test
    fun `NODE_SERVICE wire value is node_service`() {
        assertEquals("node_service", ExecutorTargetType.NODE_SERVICE)
    }

    @Test
    fun `WORKER wire value is worker`() {
        assertEquals("worker", ExecutorTargetType.WORKER)
    }

    @Test
    fun `LOCAL wire value is local`() {
        assertEquals("local", ExecutorTargetType.LOCAL)
    }

    // ── ExecutorTargetType — ALL_VALUES ───────────────────────────────────────

    @Test
    fun `ALL_VALUES contains exactly four entries`() {
        assertEquals("ALL_VALUES must have exactly 4 entries", 4, ExecutorTargetType.ALL_VALUES.size)
    }

    @Test
    fun `ALL_VALUES contains all canonical wire values`() {
        assertTrue(ExecutorTargetType.ALL_VALUES.contains(ExecutorTargetType.ANDROID_DEVICE))
        assertTrue(ExecutorTargetType.ALL_VALUES.contains(ExecutorTargetType.NODE_SERVICE))
        assertTrue(ExecutorTargetType.ALL_VALUES.contains(ExecutorTargetType.WORKER))
        assertTrue(ExecutorTargetType.ALL_VALUES.contains(ExecutorTargetType.LOCAL))
    }

    // ── ExecutorTargetType — ANDROID_ELIGIBLE_VALUES ──────────────────────────

    @Test
    fun `ANDROID_ELIGIBLE_VALUES contains null`() {
        assertTrue(
            "null (unspecified / legacy) must be Android-eligible",
            ExecutorTargetType.ANDROID_ELIGIBLE_VALUES.contains(null)
        )
    }

    @Test
    fun `ANDROID_ELIGIBLE_VALUES contains ANDROID_DEVICE`() {
        assertTrue(ExecutorTargetType.ANDROID_ELIGIBLE_VALUES.contains(ExecutorTargetType.ANDROID_DEVICE))
    }

    @Test
    fun `ANDROID_ELIGIBLE_VALUES contains LOCAL`() {
        assertTrue(ExecutorTargetType.ANDROID_ELIGIBLE_VALUES.contains(ExecutorTargetType.LOCAL))
    }

    @Test
    fun `ANDROID_ELIGIBLE_VALUES does not contain NODE_SERVICE`() {
        assertFalse(ExecutorTargetType.ANDROID_ELIGIBLE_VALUES.contains(ExecutorTargetType.NODE_SERVICE))
    }

    @Test
    fun `ANDROID_ELIGIBLE_VALUES does not contain WORKER`() {
        assertFalse(ExecutorTargetType.ANDROID_ELIGIBLE_VALUES.contains(ExecutorTargetType.WORKER))
    }

    // ── ExecutorTargetType.fromValue — known values ───────────────────────────

    @Test
    fun `fromValue returns ANDROID_DEVICE for canonical string`() {
        assertEquals(ExecutorTargetType.ANDROID_DEVICE, ExecutorTargetType.fromValue("android_device"))
    }

    @Test
    fun `fromValue returns NODE_SERVICE for canonical string`() {
        assertEquals(ExecutorTargetType.NODE_SERVICE, ExecutorTargetType.fromValue("node_service"))
    }

    @Test
    fun `fromValue returns WORKER for canonical string`() {
        assertEquals(ExecutorTargetType.WORKER, ExecutorTargetType.fromValue("worker"))
    }

    @Test
    fun `fromValue returns LOCAL for canonical string`() {
        assertEquals(ExecutorTargetType.LOCAL, ExecutorTargetType.fromValue("local"))
    }

    // ── ExecutorTargetType.fromValue — safe-unknown handling ──────────────────

    @Test
    fun `fromValue returns null for null`() {
        assertNull("null input must return null (unspecified)", ExecutorTargetType.fromValue(null))
    }

    @Test
    fun `fromValue returns null for blank string`() {
        assertNull("blank string is not a recognised target type", ExecutorTargetType.fromValue(""))
    }

    @Test
    fun `fromValue returns null for unknown string`() {
        assertNull(
            "Unknown string must return null; callers must tolerate without rejection",
            ExecutorTargetType.fromValue("future_target_type")
        )
    }

    @Test
    fun `fromValue returns null for mixed-case variant`() {
        // Wire format is strictly lower-snake-case; mixed-case must not be recognised.
        assertNull(ExecutorTargetType.fromValue("Android_Device"))
        assertNull(ExecutorTargetType.fromValue("ANDROID_DEVICE"))
    }

    // ── ExecutorTargetType.isAndroidEligible ──────────────────────────────────

    @Test
    fun `isAndroidEligible returns true for null (legacy unspecified)`() {
        assertTrue(
            "null executor_target_type (legacy payload) must be Android-eligible",
            ExecutorTargetType.isAndroidEligible(null)
        )
    }

    @Test
    fun `isAndroidEligible returns true for android_device`() {
        assertTrue(ExecutorTargetType.isAndroidEligible(ExecutorTargetType.ANDROID_DEVICE))
    }

    @Test
    fun `isAndroidEligible returns true for local`() {
        assertTrue(ExecutorTargetType.isAndroidEligible(ExecutorTargetType.LOCAL))
    }

    @Test
    fun `isAndroidEligible returns false for node_service`() {
        assertFalse(ExecutorTargetType.isAndroidEligible(ExecutorTargetType.NODE_SERVICE))
    }

    @Test
    fun `isAndroidEligible returns false for worker`() {
        assertFalse(ExecutorTargetType.isAndroidEligible(ExecutorTargetType.WORKER))
    }

    @Test
    fun `isAndroidEligible returns false for unknown value`() {
        assertFalse(ExecutorTargetType.isAndroidEligible("future_target_type"))
    }

    // ── GoalExecutionPayload — executor_target_type field ─────────────────────

    @Test
    fun `GoalExecutionPayload executor_target_type defaults to null`() {
        val payload = GoalExecutionPayload(task_id = "t-001", goal = "test goal")
        assertNull(
            "executor_target_type must default to null for backward compatibility",
            payload.executor_target_type
        )
    }

    @Test
    fun `GoalExecutionPayload executor_target_type is preserved when set`() {
        val payload = GoalExecutionPayload(
            task_id = "t-002",
            goal = "test goal",
            executor_target_type = ExecutorTargetType.ANDROID_DEVICE
        )
        assertEquals(ExecutorTargetType.ANDROID_DEVICE, payload.executor_target_type)
    }

    @Test
    fun `GoalExecutionPayload accepts any string value for executor_target_type without failure`() {
        // Forward-compat: unknown future values must not cause constructor failure.
        val payload = GoalExecutionPayload(
            task_id = "t-003",
            goal = "test goal",
            executor_target_type = "future_unknown_target"
        )
        assertEquals("future_unknown_target", payload.executor_target_type)
    }

    // ── AutonomousExecutionPipeline — target type acceptance (goal_execution) ──

    @Test
    fun `goal_execution with null executor_target_type executes (legacy backward compat)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(executorTargetType = null))
        assertNotEquals(
            "null executor_target_type must not block execution",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }

    @Test
    fun `goal_execution with android_device executor_target_type executes`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(executorTargetType = ExecutorTargetType.ANDROID_DEVICE)
        )
        assertNotEquals(
            "android_device executor_target_type must not block execution",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }

    @Test
    fun `goal_execution with local executor_target_type executes`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(executorTargetType = ExecutorTargetType.LOCAL)
        )
        assertNotEquals(
            "local executor_target_type must not block execution",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }

    @Test
    fun `goal_execution with node_service executor_target_type is tolerated without rejection`() {
        // Android tolerates non-Android-targeted commands for forward compatibility;
        // it does NOT gate on executor_target_type — that is a routing concern in V2.
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(executorTargetType = ExecutorTargetType.NODE_SERVICE)
        )
        // Result must be non-null; no exception thrown.
        assertNotNull("node_service executor_target_type must not cause an exception", result)
    }

    @Test
    fun `goal_execution with unknown executor_target_type is tolerated without rejection`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(executorTargetType = "future_unknown_target_type")
        )
        assertNotNull("Unknown executor_target_type must not cause an exception", result)
    }

    @Test
    fun `goal_execution disabled result echoes executor_target_type from payload`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(
            goalPayload(executorTargetType = ExecutorTargetType.ANDROID_DEVICE)
        )
        assertEquals(
            "Disabled result must echo executor_target_type from payload for full-chain correlation",
            ExecutorTargetType.ANDROID_DEVICE,
            result.executor_target_type
        )
    }

    @Test
    fun `goal_execution disabled result echoes null executor_target_type from legacy payload`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(goalPayload(executorTargetType = null))
        assertNull(
            "Null executor_target_type must be echoed as null in disabled result",
            result.executor_target_type
        )
    }

    @Test
    fun `goal_execution success result echoes executor_target_type from payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(executorTargetType = ExecutorTargetType.ANDROID_DEVICE)
        )
        assertEquals(
            "Success result must echo executor_target_type from payload",
            ExecutorTargetType.ANDROID_DEVICE,
            result.executor_target_type
        )
    }

    @Test
    fun `goal_execution success result with null target type echoes null`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(executorTargetType = null))
        assertNull(
            "Null executor_target_type must remain null in success result (backward compat)",
            result.executor_target_type
        )
    }

    // ── AutonomousExecutionPipeline — target type (parallel_subtask) ──────────

    @Test
    fun `parallel_subtask disabled result echoes executor_target_type from payload`() {
        val pipeline = buildPipeline(parallelExecutionEnabled = false)
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                executorTargetType = ExecutorTargetType.ANDROID_DEVICE,
                groupId = "grp-pr45",
                subtaskIndex = 0
            )
        )
        assertEquals(
            "Parallel disabled result must echo executor_target_type for full-chain correlation",
            ExecutorTargetType.ANDROID_DEVICE,
            result.executor_target_type
        )
    }

    @Test
    fun `parallel_subtask success result echoes executor_target_type from payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                executorTargetType = ExecutorTargetType.ANDROID_DEVICE,
                groupId = "grp-pr45",
                subtaskIndex = 1
            )
        )
        assertEquals(
            "Parallel success result must echo executor_target_type from payload",
            ExecutorTargetType.ANDROID_DEVICE,
            result.executor_target_type
        )
    }
}
