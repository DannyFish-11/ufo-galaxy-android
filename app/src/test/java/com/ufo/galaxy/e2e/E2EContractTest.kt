package com.ufo.galaxy.e2e

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.LocalCollaborationAgent
import com.ufo.galaxy.agent.LocalGoalExecutor
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.data.CapabilityReport
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskAssignPayload
import org.junit.Assert.*
import org.junit.Test

/**
 * E2E contract tests verifying key invariants across the Android ↔ v2 gateway flow.
 *
 * These pure-JVM tests cover:
 *  - [AppSettings.toMetadataMap] returns all 8 required capability_report metadata keys
 *  - [GoalResultPayload] from goal_execution contains required result schema fields
 *  - parallel_subtask result includes group_id and subtask_index (gateway aggregation contract)
 *  - cross_device_enabled=false keeps client local (no capability fields advertised as remote)
 *  - cross_device_enabled=true advertises remote collaboration in metadata
 *
 * For the full E2E manual test flow see docs/e2e-verification.md.
 * For the regression checklist see docs/android-regression-checklist.md.
 */
class E2EContractTest {

    // ── Fake collaborators ────────────────────────────────────────────────────

    private class FakeScreenshot : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg() =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
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
        ) = LocalPlannerService.PlanResult(steps = emptyList(), error = "no replan")
    }

    private class OkGrounder : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(x = 540, y = 960, confidence = 0.9f,
                element_description = "button")
    }

    private class OkAccessibility : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    private fun buildExecutor() = EdgeExecutor(
        screenshotProvider = FakeScreenshot(),
        plannerService = OkPlanner(),
        groundingService = OkGrounder(),
        accessibilityExecutor = OkAccessibility()
    )

    private fun buildGoalPayload(
        taskId: String = "e2e-001",
        goal: String = "open WeChat",
        groupId: String? = null,
        subtaskIndex: Int? = null
    ) = GoalExecutionPayload(
        task_id = taskId,
        goal = goal,
        group_id = groupId,
        subtask_index = subtaskIndex,
        max_steps = 5
    )

    // ── Metadata contract (capability_report) ────────────────────────────────

    /**
     * Verifies that [AppSettings.toMetadataMap] returns all 8 keys required by the
     * v2 gateway capability_report payload. Missing keys cause silent gate failures on the
     * server side.
     */
    @Test
    fun `capability report metadata contains all 8 required keys`() {
        val settings = InMemoryAppSettings().apply {
            crossDeviceEnabled = true
            goalExecutionEnabled = true
            localModelEnabled = true
            parallelExecutionEnabled = true
            deviceRole = "phone"
            modelReady = true
            accessibilityReady = true
            overlayReady = true
        }

        val meta = settings.toMetadataMap()

        assertTrue("metadata must contain goal_execution_enabled",
            meta.containsKey("goal_execution_enabled"))
        assertTrue("metadata must contain local_model_enabled",
            meta.containsKey("local_model_enabled"))
        assertTrue("metadata must contain cross_device_enabled",
            meta.containsKey("cross_device_enabled"))
        assertTrue("metadata must contain parallel_execution_enabled",
            meta.containsKey("parallel_execution_enabled"))
        assertTrue("metadata must contain device_role",
            meta.containsKey("device_role"))
        assertTrue("metadata must contain model_ready",
            meta.containsKey("model_ready"))
        assertTrue("metadata must contain accessibility_ready",
            meta.containsKey("accessibility_ready"))
        assertTrue("metadata must contain overlay_ready",
            meta.containsKey("overlay_ready"))

        assertEquals(8, meta.size)
    }

    @Test
    fun `capability report metadata reflects cross_device_enabled toggle state`() {
        val settings = InMemoryAppSettings()

        settings.crossDeviceEnabled = false
        assertEquals(false, settings.toMetadataMap()["cross_device_enabled"])

        settings.crossDeviceEnabled = true
        assertEquals(true, settings.toMetadataMap()["cross_device_enabled"])
    }

    @Test
    fun `capability report metadata reflects readiness flags`() {
        val settings = InMemoryAppSettings().apply {
            modelReady = true
            accessibilityReady = false
            overlayReady = true
        }

        val meta = settings.toMetadataMap()
        assertEquals(true, meta["model_ready"])
        assertEquals(false, meta["accessibility_ready"])
        assertEquals(true, meta["overlay_ready"])
    }

    // ── goal_execution result contract ───────────────────────────────────────

    /**
     * Validates that a standalone goal_execution returns the required result schema fields
     * for gateway routing and UI display.
     */
    @Test
    fun `goal execution result contains required schema fields`() {
        val executor = LocalGoalExecutor(buildExecutor(), deviceId = "e2e-device-001")
        val result: GoalResultPayload = executor.executeGoal(buildGoalPayload())

        // Required by v2 gateway for result routing
        assertNotNull("status required", result.status)
        assertNotNull("task_id required", result.task_id)
        assertEquals("correlation_id must equal task_id", result.task_id, result.correlation_id)
        assertFalse("device_id must not be blank", result.device_id.isBlank())
        assertTrue("latency_ms must be non-negative", result.latency_ms >= 0)

        // Standalone goal: no group fields
        assertNull("group_id must be null for standalone goal", result.group_id)
        assertNull("subtask_index must be null for standalone goal", result.subtask_index)
    }

    @Test
    fun `goal execution result status is success or error`() {
        val executor = LocalGoalExecutor(buildExecutor(), deviceId = "e2e-device-002")
        val result = executor.executeGoal(buildGoalPayload())

        assertTrue(
            "status must be success, error, or cancelled",
            result.status in setOf(
                EdgeExecutor.STATUS_SUCCESS,
                EdgeExecutor.STATUS_ERROR,
                EdgeExecutor.STATUS_CANCELLED
            )
        )
    }

    // ── parallel_subtask result contract ─────────────────────────────────────

    /**
     * Validates that parallel_subtask results include group_id and subtask_index,
     * which the v2 gateway uses to aggregate parallel_result in /api/v1/chat.
     */
    @Test
    fun `parallel subtask result includes group_id and subtask_index for gateway aggregation`() {
        val goalExec = LocalGoalExecutor(buildExecutor(), deviceId = "e2e-device-003")
        val agent = LocalCollaborationAgent(goalExecutor = goalExec)

        val result = agent.handleParallelSubtask(
            buildGoalPayload(
                taskId = "e2e-sub-001",
                goal = "send message to Alice",
                groupId = "grp-e2e-alpha",
                subtaskIndex = 1
            )
        )

        // Gateway aggregation contract: group_id and subtask_index must be echoed
        assertEquals("group_id must be echoed", "grp-e2e-alpha", result.group_id)
        assertEquals("subtask_index must be echoed", 1, result.subtask_index)
        assertEquals("task_id must be echoed", "e2e-sub-001", result.task_id)
        assertEquals("correlation_id must equal task_id", result.task_id, result.correlation_id)
    }

    @Test
    fun `parallel subtask error result still echoes group_id and subtask_index`() {
        // Even on error, group_id/subtask_index must be present for gateway convergence.
        val notLoadedPlanner = object : LocalPlannerService {
            override fun loadModel() = false
            override fun unloadModel() {}
            override fun isModelLoaded() = false
            override fun plan(g: String, c: List<String>, s: String?) =
                LocalPlannerService.PlanResult(steps = emptyList(), error = "not loaded")
            override fun replan(g: String, c: List<String>, f: LocalPlannerService.PlanStep,
                                e: String, s: String?) =
                LocalPlannerService.PlanResult(steps = emptyList(), error = "not loaded")
        }
        val edge = EdgeExecutor(FakeScreenshot(), notLoadedPlanner, OkGrounder(), OkAccessibility())
        val goalExec = LocalGoalExecutor(edge, "e2e-device-004")
        val agent = LocalCollaborationAgent(goalExec)

        val result = agent.handleParallelSubtask(
            buildGoalPayload(
                taskId = "e2e-sub-002",
                groupId = "grp-e2e-beta",
                subtaskIndex = 2
            )
        )

        assertEquals(EdgeExecutor.STATUS_ERROR, result.status)
        assertEquals("grp-e2e-beta", result.group_id)
        assertEquals(2, result.subtask_index)
    }

    // ── cross_device_enabled toggle: local-only contract ─────────────────────

    /**
     * Validates that [AppSettings.degradedMode] is true when any readiness check fails,
     * preventing cloud execution with incomplete prerequisites.
     */
    @Test
    fun `degradedMode is true when any readiness check fails`() {
        val settings = InMemoryAppSettings().apply {
            modelReady = false
            accessibilityReady = true
            overlayReady = true
        }
        assertTrue("degradedMode must be true when model not ready", settings.degradedMode)

        settings.modelReady = true
        settings.accessibilityReady = false
        assertTrue("degradedMode must be true when accessibility not ready", settings.degradedMode)

        settings.accessibilityReady = true
        settings.overlayReady = false
        assertTrue("degradedMode must be true when overlay not ready", settings.degradedMode)

        settings.overlayReady = true
        assertFalse("degradedMode must be false when all checks pass", settings.degradedMode)
    }

    // ── CapabilityReport schema contract ─────────────────────────────────────

    @Test
    fun `CapabilityReport includes autonomous_goal_execution capability`() {
        val report = CapabilityReport(
            platform = "android",
            device_id = "e2e-device-005",
            supported_actions = listOf("screen_capture", "automation"),
            capabilities = listOf(
                "autonomous_goal_execution",
                "local_task_planning",
                "local_ui_reasoning",
                "cross_device_coordination",
                "local_model_inference"
            ),
            metadata = mapOf(
                "cross_device_enabled" to true,
                "goal_execution_enabled" to true,
                "local_model_enabled" to true,
                "parallel_execution_enabled" to true,
                "device_role" to "phone",
                "model_ready" to true,
                "accessibility_ready" to true,
                "overlay_ready" to true
            )
        )

        assertTrue(report.capabilities.contains("autonomous_goal_execution"))
        assertEquals("android", report.platform)
        assertFalse("device_id must not be blank", report.device_id.isBlank())
        assertEquals("3.0", report.version)
        assertEquals(8, report.metadata.size)
    }

    // ── MsgType coverage ─────────────────────────────────────────────────────

    @Test
    fun `MsgType covers all message types used in E2E flow`() {
        // These message types must exist for the Android ↔ gateway protocol to function.
        assertEquals("goal_execution", MsgType.GOAL_EXECUTION.value)
        assertEquals("parallel_subtask", MsgType.PARALLEL_SUBTASK.value)
        assertEquals("goal_result", MsgType.GOAL_RESULT.value)
        assertEquals("capability_report", MsgType.CAPABILITY_REPORT.value)
        assertEquals("device_register", MsgType.DEVICE_REGISTER.value)
        assertEquals("task_assign", MsgType.TASK_ASSIGN.value)
        assertEquals("task_result", MsgType.TASK_RESULT.value)
    }
}
