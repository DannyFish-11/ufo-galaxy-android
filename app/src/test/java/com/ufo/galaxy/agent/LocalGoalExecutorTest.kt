package com.ufo.galaxy.agent

import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.protocol.GoalExecutionPayload
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [LocalGoalExecutor] and [LocalCollaborationAgent].
 *
 * Tests cover:
 *  - goal_execution happy path → success result with latency and device_id
 *  - goal_execution error path (planner not loaded) → error result
 *  - goal_execution exception inside EdgeExecutor → error result
 *  - parallel_subtask returns group_id and subtask_index echoed from payload
 *  - legacy task_assign still handled by EdgeExecutor (unchanged behaviour verified)
 *
 * All fakes are identical in shape to those in [EdgeExecutorTest]; they are not shared
 * to keep each test class self-contained and avoid coupling.
 */
class LocalGoalExecutorTest {

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
        override fun replan(goal: String, constraints: List<String>,
                            failedStep: LocalPlannerService.PlanStep,
                            error: String, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(steps = emptyList(), error = "replan not supported")
    }

    private class NotLoadedPlanner : LocalPlannerService {
        override fun loadModel() = false
        override fun unloadModel() {}
        override fun isModelLoaded() = false
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(steps = emptyList(), error = "model not loaded")
        override fun replan(goal: String, constraints: List<String>,
                            failedStep: LocalPlannerService.PlanStep,
                            error: String, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(steps = emptyList(), error = "model not loaded")
    }

    private class OkGrounder : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(x = 540, y = 960, confidence = 0.9f,
                element_description = "button")
    }

    private class NotLoadedGrounder : LocalGroundingService {
        override fun loadModel() = false
        override fun unloadModel() {}
        override fun isModelLoaded() = false
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(x = 0, y = 0, confidence = 0f,
                element_description = "", error = "not loaded")
    }

    private class OkAccessibility(private val returns: Boolean = true) : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = returns
    }

    private fun buildGoalPayload(
        taskId: String = "g-001",
        goal: String = "open WeChat",
        groupId: String? = null,
        subtaskIndex: Int? = null,
        maxSteps: Int = 5
    ) = GoalExecutionPayload(
        task_id = taskId,
        goal = goal,
        group_id = groupId,
        subtask_index = subtaskIndex,
        max_steps = maxSteps
    )

    private fun buildExecutor(
        planner: LocalPlannerService = OkPlanner(),
        grounder: LocalGroundingService = OkGrounder(),
        accessibility: AccessibilityExecutor = OkAccessibility(),
        screenshotThrows: Boolean = false
    ) = EdgeExecutor(
        screenshotProvider = FakeScreenshot(shouldThrow = screenshotThrows),
        plannerService = planner,
        groundingService = grounder,
        accessibilityExecutor = accessibility
    )

    // ── goal_execution happy path ─────────────────────────────────────────────

    @Test
    fun `goal execution happy path returns success status`() {
        val edge = buildExecutor()
        val executor = LocalGoalExecutor(edgeExecutor = edge, deviceId = "device-test")

        val result = executor.executeGoal(buildGoalPayload())

        assertEquals(EdgeExecutor.STATUS_SUCCESS, result.status)
        assertNull(result.error)
        assertEquals("g-001", result.task_id)
        assertEquals("g-001", result.correlation_id)
        assertEquals("device-test", result.device_id)
        assertTrue("latency_ms should be non-negative", result.latency_ms >= 0)
        assertNotNull(result.result)
    }

    @Test
    fun `goal execution result echoes task_id`() {
        val executor = LocalGoalExecutor(buildExecutor(), "dev-001")
        val result = executor.executeGoal(buildGoalPayload(taskId = "g-xyz"))
        assertEquals("g-xyz", result.task_id)
        assertEquals("g-xyz", result.correlation_id)
    }

    // ── goal_execution error paths ────────────────────────────────────────────

    @Test
    fun `goal execution returns error when planner not loaded`() {
        val edge = buildExecutor(planner = NotLoadedPlanner())
        val executor = LocalGoalExecutor(edge, "dev-002")

        val result = executor.executeGoal(buildGoalPayload())

        assertEquals(EdgeExecutor.STATUS_ERROR, result.status)
        assertNotNull(result.error)
    }

    @Test
    fun `goal execution returns error when grounding not loaded`() {
        val edge = buildExecutor(grounder = NotLoadedGrounder())
        val executor = LocalGoalExecutor(edge, "dev-003")

        val result = executor.executeGoal(buildGoalPayload())

        assertEquals(EdgeExecutor.STATUS_ERROR, result.status)
        assertNotNull(result.error)
    }

    @Test
    fun `goal execution returns error on screenshot capture failure`() {
        val edge = buildExecutor(screenshotThrows = true)
        val executor = LocalGoalExecutor(edge, "dev-004")

        val result = executor.executeGoal(buildGoalPayload())

        assertEquals(EdgeExecutor.STATUS_ERROR, result.status)
        assertNotNull(result.error)
    }

    // ── group_id / subtask_index echoing ──────────────────────────────────────

    @Test
    fun `goal execution echoes null group_id and subtask_index for standalone goals`() {
        val executor = LocalGoalExecutor(buildExecutor(), "dev-005")
        val result = executor.executeGoal(buildGoalPayload(groupId = null, subtaskIndex = null))

        assertNull(result.group_id)
        assertNull(result.subtask_index)
    }

    @Test
    fun `goal execution echoes group_id and subtask_index when provided`() {
        val executor = LocalGoalExecutor(buildExecutor(), "dev-006")
        val result = executor.executeGoal(
            buildGoalPayload(groupId = "group-abc", subtaskIndex = 2)
        )

        assertEquals("group-abc", result.group_id)
        assertEquals(2, result.subtask_index)
    }

    // ── parallel_subtask via LocalCollaborationAgent ──────────────────────────

    @Test
    fun `parallel subtask returns group_id and subtask_index`() {
        val goalExec = LocalGoalExecutor(buildExecutor(), "dev-007")
        val agent = LocalCollaborationAgent(goalExecutor = goalExec)

        val result = agent.handleParallelSubtask(
            buildGoalPayload(
                taskId = "sub-001",
                goal = "send WeChat message",
                groupId = "grp-parallel",
                subtaskIndex = 1
            )
        )

        assertEquals("sub-001", result.task_id)
        assertEquals("grp-parallel", result.group_id)
        assertEquals(1, result.subtask_index)
        assertEquals(EdgeExecutor.STATUS_SUCCESS, result.status)
    }

    @Test
    fun `parallel subtask error still echoes group_id and subtask_index`() {
        val goalExec = LocalGoalExecutor(buildExecutor(planner = NotLoadedPlanner()), "dev-008")
        val agent = LocalCollaborationAgent(goalExecutor = goalExec)

        val result = agent.handleParallelSubtask(
            buildGoalPayload(
                taskId = "sub-002",
                goal = "open Maps",
                groupId = "grp-parallel",
                subtaskIndex = 0
            )
        )

        assertEquals(EdgeExecutor.STATUS_ERROR, result.status)
        assertEquals("grp-parallel", result.group_id)
        assertEquals(0, result.subtask_index)
    }

    // ── legacy task_assign still works ───────────────────────────────────────

    @Test
    fun `legacy task_assign handled by EdgeExecutor is unchanged`() {
        val edge = buildExecutor()
        val payload = com.ufo.galaxy.protocol.TaskAssignPayload(
            task_id = "legacy-01",
            goal = "tap OK button",
            max_steps = 5,
            require_local_agent = true
        )

        val result = edge.handleTaskAssign(payload)

        assertEquals(EdgeExecutor.STATUS_SUCCESS, result.status)
        assertEquals("legacy-01", result.task_id)
        assertEquals("legacy-01", result.correlation_id)
    }

    @Test
    fun `legacy task_assign with require_local_agent false returns cancelled`() {
        val edge = buildExecutor()
        val payload = com.ufo.galaxy.protocol.TaskAssignPayload(
            task_id = "legacy-02",
            goal = "some goal",
            max_steps = 5,
            require_local_agent = false
        )

        val result = edge.handleTaskAssign(payload)

        assertEquals(EdgeExecutor.STATUS_CANCELLED, result.status)
    }

    // ── GoalResultPayload schema fields ───────────────────────────────────────

    @Test
    fun `GoalResultPayload contains required result schema fields`() {
        val executor = LocalGoalExecutor(buildExecutor(), "device-schema-test")
        val result = executor.executeGoal(
            buildGoalPayload(taskId = "schema-001", groupId = "grp", subtaskIndex = 3)
        )

        // Required schema: group_id, subtask_index, status, result/details, latency_ms, device_id
        assertNotNull("status required", result.status)
        assertEquals("grp", result.group_id)
        assertEquals(3, result.subtask_index)
        assertTrue("latency_ms must be non-negative", result.latency_ms >= 0)
        assertEquals("device-schema-test", result.device_id)
    }
}
