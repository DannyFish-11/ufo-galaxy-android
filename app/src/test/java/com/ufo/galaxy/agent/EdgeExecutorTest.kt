package com.ufo.galaxy.agent

import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.protocol.TaskAssignPayload
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [EdgeExecutor] covering the happy path, all planner/grounder/
 * execution error paths, and snapshot generation.
 *
 * All dependencies are replaced by lightweight in-memory fakes that require no
 * Android framework classes, keeping these tests runnable on the JVM.
 */
class EdgeExecutorTest {

    // ── Fake implementations ──────────────────────────────────────────────────

    /**
     * Screenshot provider that always returns a fixed 3-byte JPEG marker.
     * Produces a deterministic Base64 string in assertions.
     */
    private class FakeScreenshotProvider(
        private val jpegBytes: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
        private val width: Int = 1080,
        private val height: Int = 2340,
        private val shouldThrow: Boolean = false
    ) : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg(): ByteArray {
            if (shouldThrow) throw IllegalStateException("capture service unavailable")
            return jpegBytes
        }
        override fun screenWidth(): Int = width
        override fun screenHeight(): Int = height
    }

    /** Planner that returns a single predefined tap step. */
    private class SingleStepPlanner(
        private val actionType: String = "tap",
        private val intent: String = "click the button"
    ) : LocalPlannerService {
        override fun loadModel(): Boolean = true
        override fun unloadModel() {}
        override fun isModelLoaded(): Boolean = true
        override fun plan(
            goal: String,
            constraints: List<String>,
            screenshotBase64: String?
        ): LocalPlannerService.PlanResult = LocalPlannerService.PlanResult(
            steps = listOf(LocalPlannerService.PlanStep(actionType, intent))
        )
        override fun replan(
            goal: String,
            constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String,
            screenshotBase64: String?
        ): LocalPlannerService.PlanResult = LocalPlannerService.PlanResult(
            steps = emptyList(),
            error = "replan not supported in fake"
        )
    }

    /** Planner that always returns an error result. */
    private class FailingPlanner(private val errorMsg: String = "planner error") : LocalPlannerService {
        override fun loadModel(): Boolean = false
        override fun unloadModel() {}
        override fun isModelLoaded(): Boolean = false
        override fun plan(
            goal: String,
            constraints: List<String>,
            screenshotBase64: String?
        ): LocalPlannerService.PlanResult = LocalPlannerService.PlanResult(
            steps = emptyList(),
            error = errorMsg
        )
        override fun replan(
            goal: String,
            constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String,
            screenshotBase64: String?
        ): LocalPlannerService.PlanResult = LocalPlannerService.PlanResult(
            steps = emptyList(),
            error = errorMsg
        )
    }

    /** Grounder that returns a fixed coordinate result. */
    private class FakeGrounder(
        private val x: Int = 540,
        private val y: Int = 960,
        private val confidence: Float = 0.95f
    ) : LocalGroundingService {
        override fun loadModel(): Boolean = true
        override fun unloadModel() {}
        override fun isModelLoaded(): Boolean = true
        override fun ground(
            intent: String,
            screenshotBase64: String,
            width: Int,
            height: Int
        ): LocalGroundingService.GroundingResult = LocalGroundingService.GroundingResult(
            x = x,
            y = y,
            confidence = confidence,
            element_description = "the button"
        )
    }

    /** Grounder that always returns an error result. */
    private class FailingGrounder(private val errorMsg: String = "grounding error") : LocalGroundingService {
        override fun loadModel(): Boolean = false
        override fun unloadModel() {}
        override fun isModelLoaded(): Boolean = false
        override fun ground(
            intent: String,
            screenshotBase64: String,
            width: Int,
            height: Int
        ): LocalGroundingService.GroundingResult = LocalGroundingService.GroundingResult(
            x = 0, y = 0, confidence = 0f,
            element_description = "",
            error = errorMsg
        )
    }

    /** AccessibilityExecutor that records the last action and returns a configurable result. */
    private class FakeAccessibilityExecutor(
        private val returns: Boolean = true
    ) : AccessibilityExecutor {
        var lastAction: AccessibilityExecutor.AccessibilityAction? = null
        override fun execute(action: AccessibilityExecutor.AccessibilityAction): Boolean {
            lastAction = action
            return returns
        }
    }

    /** AccessibilityExecutor that throws an exception. */
    private class ThrowingAccessibilityExecutor : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction): Boolean {
            throw RuntimeException("accessibility service crashed")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildTaskAssign(
        taskId: String = "task-001",
        goal: String = "tap the login button",
        maxSteps: Int = 5,
        requireLocalAgent: Boolean = true,
        constraints: List<String> = emptyList()
    ) = TaskAssignPayload(
        task_id = taskId,
        goal = goal,
        constraints = constraints,
        max_steps = maxSteps,
        require_local_agent = requireLocalAgent
    )

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    fun `happy path - single tap step succeeds and returns success status`() {
        val executor = FakeAccessibilityExecutor(returns = true)
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = SingleStepPlanner(),
            groundingService = FakeGrounder(),
            accessibilityExecutor = executor
        )

        val result = edge.handleTaskAssign(buildTaskAssign())

        assertEquals(EdgeExecutor.STATUS_SUCCESS, result.status)
        assertNull(result.error)
        assertEquals("task-001", result.task_id)
        assertEquals("task-001", result.correlation_id)
        assertEquals(1, result.steps.size)
        assertTrue(result.steps[0].success)
        assertEquals("tap", result.steps[0].action)
    }

    @Test
    fun `happy path - correlation_id equals task_id`() {
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = SingleStepPlanner(),
            groundingService = FakeGrounder(),
            accessibilityExecutor = FakeAccessibilityExecutor()
        )

        val result = edge.handleTaskAssign(buildTaskAssign(taskId = "xyz-999"))

        assertEquals("xyz-999", result.task_id)
        assertEquals("xyz-999", result.correlation_id)
    }

    @Test
    fun `happy path - snapshot is attached to result`() {
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = SingleStepPlanner(),
            groundingService = FakeGrounder(),
            accessibilityExecutor = FakeAccessibilityExecutor()
        )

        val result = edge.handleTaskAssign(buildTaskAssign())

        assertNotNull("Snapshot must be present after successful execution", result.snapshot)
        assertNotNull(result.snapshot!!.data)
        assertTrue(result.snapshot!!.data.isNotEmpty())
    }

    @Test
    fun `happy path - grounded tap coordinates reach accessibility executor`() {
        val fakeAccessibility = FakeAccessibilityExecutor()
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = SingleStepPlanner(actionType = "tap"),
            groundingService = FakeGrounder(x = 300, y = 700),
            accessibilityExecutor = fakeAccessibility
        )

        edge.handleTaskAssign(buildTaskAssign())

        val action = fakeAccessibility.lastAction
        assertNotNull(action)
        assertTrue(action is AccessibilityExecutor.AccessibilityAction.Tap)
        val tap = action as AccessibilityExecutor.AccessibilityAction.Tap
        assertEquals(300, tap.x)
        assertEquals(700, tap.y)
    }

    // ── require_local_agent = false ───────────────────────────────────────────

    @Test
    fun `require_local_agent false returns cancelled without executing`() {
        val fakeAccessibility = FakeAccessibilityExecutor()
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = SingleStepPlanner(),
            groundingService = FakeGrounder(),
            accessibilityExecutor = fakeAccessibility
        )

        val result = edge.handleTaskAssign(buildTaskAssign(requireLocalAgent = false))

        assertEquals(EdgeExecutor.STATUS_CANCELLED, result.status)
        assertEquals("require_local_agent is false", result.error)
        assertNull(fakeAccessibility.lastAction)
    }

    // ── Planner failure ───────────────────────────────────────────────────────

    @Test
    fun `planner failure returns error status with error message`() {
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = FailingPlanner("model OOM"),
            groundingService = FakeGrounder(),
            accessibilityExecutor = FakeAccessibilityExecutor()
        )

        val result = edge.handleTaskAssign(buildTaskAssign())

        assertEquals(EdgeExecutor.STATUS_ERROR, result.status)
        assertNotNull(result.error)
        assertTrue("Error should mention planning failure", result.error!!.contains("Planning failed"))
    }

    @Test
    fun `planner failure attaches snapshot to result`() {
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = FailingPlanner(),
            groundingService = FakeGrounder(),
            accessibilityExecutor = FakeAccessibilityExecutor()
        )

        val result = edge.handleTaskAssign(buildTaskAssign())

        assertNotNull("Snapshot should be attached on planner failure", result.snapshot)
    }

    // ── Grounding failure ─────────────────────────────────────────────────────

    @Test
    fun `grounding failure returns error status with error message`() {
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = SingleStepPlanner(),
            groundingService = FailingGrounder("element not found"),
            accessibilityExecutor = FakeAccessibilityExecutor()
        )

        val result = edge.handleTaskAssign(buildTaskAssign())

        assertEquals(EdgeExecutor.STATUS_ERROR, result.status)
        assertNotNull(result.error)
        assertTrue("Error should mention grounding failure", result.error!!.contains("Grounding failed"))
    }

    @Test
    fun `grounding failure accumulates step result with success=false`() {
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = SingleStepPlanner(),
            groundingService = FailingGrounder("element not found"),
            accessibilityExecutor = FakeAccessibilityExecutor()
        )

        val result = edge.handleTaskAssign(buildTaskAssign())

        assertEquals(1, result.steps.size)
        assertFalse(result.steps[0].success)
        assertEquals("element not found", result.steps[0].error)
    }

    // ── Execution failure ─────────────────────────────────────────────────────

    @Test
    fun `accessibility executor exception returns error status`() {
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = SingleStepPlanner(),
            groundingService = FakeGrounder(),
            accessibilityExecutor = ThrowingAccessibilityExecutor()
        )

        val result = edge.handleTaskAssign(buildTaskAssign())

        assertEquals(EdgeExecutor.STATUS_ERROR, result.status)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Execution failed"))
    }

    // ── Screenshot provider failure ───────────────────────────────────────────

    @Test
    fun `screenshot capture failure before planning returns error status`() {
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(shouldThrow = true),
            plannerService = SingleStepPlanner(),
            groundingService = FakeGrounder(),
            accessibilityExecutor = FakeAccessibilityExecutor()
        )

        val result = edge.handleTaskAssign(buildTaskAssign())

        assertEquals(EdgeExecutor.STATUS_ERROR, result.status)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Screenshot capture failed"))
    }

    // ── max_steps budget ──────────────────────────────────────────────────────

    @Test
    fun `max_steps of 1 limits execution to a single step`() {
        val fakeAccessibility = FakeAccessibilityExecutor()

        // Planner that returns two steps
        val twostepPlanner = object : LocalPlannerService {
            override fun loadModel(): Boolean = true
            override fun unloadModel() {}
            override fun isModelLoaded(): Boolean = true
            override fun plan(
                goal: String,
                constraints: List<String>,
                screenshotBase64: String?
            ) = LocalPlannerService.PlanResult(
                steps = listOf(
                    LocalPlannerService.PlanStep("tap", "tap step 1"),
                    LocalPlannerService.PlanStep("tap", "tap step 2")
                )
            )
            override fun replan(
                goal: String,
                constraints: List<String>,
                failedStep: LocalPlannerService.PlanStep,
                error: String,
                screenshotBase64: String?
            ) = LocalPlannerService.PlanResult(steps = emptyList(), error = "no replan")
        }

        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = twostepPlanner,
            groundingService = FakeGrounder(),
            accessibilityExecutor = fakeAccessibility
        )

        val result = edge.handleTaskAssign(buildTaskAssign(maxSteps = 1))

        // Exactly 1 step should have been consumed
        assertEquals(1, result.steps.size)
    }

    // ── buildCommandResult helper ─────────────────────────────────────────────

    @Test
    fun `buildCommandResult returns success status with correct fields`() {
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = NoOpAgent(),
            groundingService = NoOpGrounder(),
            accessibilityExecutor = FakeAccessibilityExecutor()
        )

        val cmd = edge.buildCommandResult(
            taskId = "task-10",
            stepId = "3",
            action = "type",
            success = true
        )

        assertEquals("task-10", cmd.task_id)
        assertEquals("3", cmd.step_id)
        assertEquals("type", cmd.action)
        assertEquals(EdgeExecutor.STATUS_SUCCESS, cmd.status)
        assertNull(cmd.error)
    }

    @Test
    fun `buildCommandResult returns error status with error message`() {
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = NoOpAgent(),
            groundingService = NoOpGrounder(),
            accessibilityExecutor = FakeAccessibilityExecutor()
        )

        val cmd = edge.buildCommandResult(
            taskId = "task-11",
            stepId = "1",
            action = "scroll",
            success = false,
            error = "scroll target not found"
        )

        assertEquals(EdgeExecutor.STATUS_ERROR, cmd.status)
        assertEquals("scroll target not found", cmd.error)
    }

    // ── Private no-op helpers for buildCommandResult tests ────────────────────

    private class NoOpAgent : LocalPlannerService {
        override fun loadModel() = false
        override fun unloadModel() {}
        override fun isModelLoaded() = false
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(emptyList(), "noop")
        override fun replan(
            goal: String, constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep, error: String, screenshotBase64: String?
        ) = LocalPlannerService.PlanResult(emptyList(), "noop")
    }

    private class NoOpGrounder : LocalGroundingService {
        override fun loadModel() = false
        override fun unloadModel() {}
        override fun isModelLoaded() = false
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(0, 0, 0f, "", "noop")
    }
}
