package com.ufo.galaxy.loop

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [LoopController] covering happy-path, screenshot-failed,
 * plan-failed, step-failure + replan, and max-steps budget enforcement.
 *
 * All dependencies are replaced by lightweight in-memory fakes that require no Android
 * framework classes; all tests run on the JVM via [runBlocking].
 */
class LoopControllerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies ─────────────────────────────────────────────────────

    /**
     * Screenshot provider that returns a fixed 3-byte JPEG marker or throws on demand.
     */
    private class FakeScreenshotProvider(
        private val jpegBytes: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
        private val width: Int = 1080,
        private val height: Int = 2340,
        private val shouldThrow: Boolean = false
    ) : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg(): ByteArray {
            if (shouldThrow) throw IllegalStateException("capture unavailable")
            return jpegBytes
        }
        override fun screenWidth() = width
        override fun screenHeight() = height
    }

    /**
     * Planner that always returns a single tap step when loaded.
     */
    private class SingleStepPlannerService(
        private val loaded: Boolean = true,
        private val actionType: String = "tap",
        private val intent: String = "tap the button"
    ) : LocalPlannerService {
        override fun loadModel() = loaded
        override fun unloadModel() {}
        override fun isModelLoaded() = loaded
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep(actionType, intent))
            )
        override fun replan(
            goal: String, constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String, screenshotBase64: String?
        ) = LocalPlannerService.PlanResult(steps = emptyList(), error = "replan not supported")
    }

    /**
     * Planner that produces multi-step plans and supports replan (returns a single recovery step).
     */
    private class MultiStepPlannerService(
        private val steps: List<Pair<String, String>> = listOf("tap" to "step A", "tap" to "step B"),
        private val replanStep: Pair<String, String>? = "tap" to "recovery step"
    ) : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = steps.map { (action, intent) ->
                    LocalPlannerService.PlanStep(action, intent)
                }
            )
        override fun replan(
            goal: String, constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String, screenshotBase64: String?
        ): LocalPlannerService.PlanResult {
            val s = replanStep
                ?: return LocalPlannerService.PlanResult(steps = emptyList(), error = "no replan")
            return LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep(s.first, s.second))
            )
        }
    }

    /** Grounder that always returns centre-screen coordinates. */
    private class FakeGrounder : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(
            intent: String, screenshotBase64: String, width: Int, height: Int
        ) = LocalGroundingService.GroundingResult(
            x = width / 2,
            y = height / 2,
            confidence = 0.9f,
            element_description = "target element"
        )
    }

    /** Accessibility executor that records the number of executions and returns a fixed boolean. */
    private class FakeAccessibilityExecutor(
        private val returns: Boolean = true
    ) : AccessibilityExecutor {
        var callCount = 0
        var lastAction: AccessibilityExecutor.AccessibilityAction? = null
        override fun execute(action: AccessibilityExecutor.AccessibilityAction): Boolean {
            callCount++
            lastAction = action
            return returns
        }
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private fun buildController(
        plannerService: LocalPlannerService = SingleStepPlannerService(),
        grounder: LocalGroundingService = FakeGrounder(),
        accessibility: AccessibilityExecutor = FakeAccessibilityExecutor(),
        screenshotProvider: EdgeExecutor.ScreenshotProvider = FakeScreenshotProvider(),
        maxSteps: Int = 10,
        maxRetriesPerStep: Int = 2
    ): LoopController {
        val modelsDir = tmpFolder.newFolder("models")
        val manager = ModelAssetManager(modelsDir)
        val downloader = ModelDownloader(modelsDir)
        val planner = LocalPlanner(plannerService)
        val bridge = ExecutorBridge(
            groundingService = grounder,
            accessibilityExecutor = accessibility,
            imageScaler = NoOpImageScaler()
        )
        return LoopController(
            localPlanner = planner,
            executorBridge = bridge,
            screenshotProvider = screenshotProvider,
            modelAssetManager = manager,
            modelDownloader = downloader,
            maxSteps = maxSteps,
            maxRetriesPerStep = maxRetriesPerStep
        )
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    fun `happy path - single step succeeds returns success status`() = runBlocking {
        val ctrl = buildController()

        val result = ctrl.execute("tap the login button")

        assertEquals(LoopController.STATUS_SUCCESS, result.status)
        assertNull(result.error)
        assertEquals(LoopController.STOP_TASK_COMPLETE, result.stopReason)
        assertEquals(1, result.steps.size)
        assertEquals(StepStatus.SUCCESS, result.steps[0].status)
    }

    @Test
    fun `happy path - sessionId is non-empty`() = runBlocking {
        val result = buildController().execute("open settings")
        assertTrue(result.sessionId.isNotEmpty())
    }

    @Test
    fun `happy path - instruction is echoed in result`() = runBlocking {
        val result = buildController().execute("scroll the feed")
        assertEquals("scroll the feed", result.instruction)
    }

    @Test
    fun `happy path - accessibility executor is called once per step`() = runBlocking {
        val accessibility = FakeAccessibilityExecutor(returns = true)
        val ctrl = buildController(accessibility = accessibility)
        ctrl.execute("tap the button")
        assertEquals(1, accessibility.callCount)
    }

    @Test
    fun `happy path - multi-step plan executes all steps`() = runBlocking {
        val accessibility = FakeAccessibilityExecutor(returns = true)
        val ctrl = buildController(
            plannerService = MultiStepPlannerService(
                steps = listOf("tap" to "A", "tap" to "B", "tap" to "C")
            ),
            accessibility = accessibility
        )
        val result = ctrl.execute("three-step task")
        assertEquals(LoopController.STATUS_SUCCESS, result.status)
        assertEquals(3, result.steps.size)
        assertEquals(3, accessibility.callCount)
    }

    @Test
    fun `happy path - loop status is Done after success`() = runBlocking {
        val ctrl = buildController()
        ctrl.execute("do something")
        val status = ctrl.status.value
        assertTrue("Expected Done but got $status", status is LoopStatus.Done)
    }

    // ── Screenshot failures ───────────────────────────────────────────────────

    @Test
    fun `initial screenshot failure returns failed result`() = runBlocking {
        val ctrl = buildController(
            screenshotProvider = FakeScreenshotProvider(shouldThrow = true)
        )
        val result = ctrl.execute("tap the button")
        assertEquals(LoopController.STATUS_FAILED, result.status)
        assertEquals(LoopController.STOP_SCREENSHOT_FAILED, result.stopReason)
    }

    @Test
    fun `initial screenshot failure sets status to Failed`() = runBlocking {
        val ctrl = buildController(
            screenshotProvider = FakeScreenshotProvider(shouldThrow = true)
        )
        ctrl.execute("tap")
        val status = ctrl.status.value
        assertTrue("Expected Failed but got $status", status is LoopStatus.Failed)
    }

    // ── Plan failures ─────────────────────────────────────────────────────────

    @Test
    fun `planner not loaded falls back to rule-based and succeeds`() = runBlocking {
        // When the planner is not loaded, LocalPlanner.ruleBased() produces a single step.
        val ctrl = buildController(plannerService = SingleStepPlannerService(loaded = false))
        val result = ctrl.execute("tap the button")
        // ruleBased produces a tap step which the executor handles successfully.
        assertEquals(LoopController.STATUS_SUCCESS, result.status)
        assertEquals(1, result.steps.size)
    }

    // ── Step failure and retry ────────────────────────────────────────────────

    @Test
    fun `step failure with retries remaining triggers replan`() = runBlocking {
        // First execution fails; replan returns a recovery step that succeeds.
        var callCount = 0
        val accessibility = object : AccessibilityExecutor {
            override fun execute(action: AccessibilityExecutor.AccessibilityAction): Boolean {
                callCount++
                // Fail on first call, succeed thereafter.
                return callCount > 1
            }
        }
        val ctrl = buildController(
            plannerService = MultiStepPlannerService(
                steps = listOf("tap" to "first attempt"),
                replanStep = "tap" to "recovery"
            ),
            accessibility = accessibility,
            maxRetriesPerStep = 1
        )
        val result = ctrl.execute("task with replan")
        assertEquals(LoopController.STATUS_SUCCESS, result.status)
        // First step fails (retry 0), replan called, recovery step succeeds.
        assertEquals(2, result.steps.size)
    }

    @Test
    fun `step failure with no retries remaining returns failed`() = runBlocking {
        val ctrl = buildController(
            plannerService = SingleStepPlannerService(),
            accessibility = FakeAccessibilityExecutor(returns = false),
            maxRetriesPerStep = 0
        )
        val result = ctrl.execute("tap the button")
        assertEquals(LoopController.STATUS_FAILED, result.status)
        assertEquals(LoopController.STOP_STEP_EXHAUSTED, result.stopReason)
    }

    @Test
    fun `replan produces no steps returns replan_failed`() = runBlocking {
        val accessibility = FakeAccessibilityExecutor(returns = false)
        val ctrl = buildController(
            plannerService = MultiStepPlannerService(
                steps = listOf("tap" to "first"),
                replanStep = null  // replan fails
            ),
            accessibility = accessibility,
            maxRetriesPerStep = 1
        )
        val result = ctrl.execute("task")
        assertEquals(LoopController.STATUS_FAILED, result.status)
        assertEquals(LoopController.STOP_REPLAN_FAILED, result.stopReason)
    }

    // ── Max-steps budget ──────────────────────────────────────────────────────

    @Test
    fun `max steps budget is respected`() = runBlocking {
        // Produce a plan with more steps than maxSteps.
        val manySteps = (1..20).map { "tap" to "step $it" }
        val ctrl = buildController(
            plannerService = MultiStepPlannerService(steps = manySteps),
            maxSteps = 3
        )
        val result = ctrl.execute("long task")
        assertEquals(LoopController.STATUS_FAILED, result.status)
        assertEquals(LoopController.STOP_MAX_STEPS, result.stopReason)
        // Exactly maxSteps executed.
        assertEquals(3, result.steps.size)
    }

    @Test
    fun `max steps 1 limits to single step`() = runBlocking {
        val manySteps = (1..5).map { "tap" to "step $it" }
        val ctrl = buildController(
            plannerService = MultiStepPlannerService(steps = manySteps),
            maxSteps = 1
        )
        val result = ctrl.execute("short budget")
        assertEquals(1, result.steps.size)
    }

    // ── LoopController default constants ─────────────────────────────────────

    @Test
    fun `default maxSteps is 10`() {
        val modelsDir = tmpFolder.newFolder("models2")
        val ctrl = LoopController(
            localPlanner = LocalPlanner(SingleStepPlannerService()),
            executorBridge = ExecutorBridge(
                groundingService = FakeGrounder(),
                accessibilityExecutor = FakeAccessibilityExecutor(),
                imageScaler = NoOpImageScaler()
            ),
            screenshotProvider = FakeScreenshotProvider(),
            modelAssetManager = ModelAssetManager(modelsDir),
            modelDownloader = ModelDownloader(modelsDir)
        )
        assertEquals(LoopController.DEFAULT_MAX_STEPS, ctrl.maxSteps)
    }

    @Test
    fun `default maxRetriesPerStep is 2`() {
        val modelsDir = tmpFolder.newFolder("models3")
        val ctrl = LoopController(
            localPlanner = LocalPlanner(SingleStepPlannerService()),
            executorBridge = ExecutorBridge(
                groundingService = FakeGrounder(),
                accessibilityExecutor = FakeAccessibilityExecutor(),
                imageScaler = NoOpImageScaler()
            ),
            screenshotProvider = FakeScreenshotProvider(),
            modelAssetManager = ModelAssetManager(modelsDir),
            modelDownloader = ModelDownloader(modelsDir)
        )
        assertEquals(LoopController.DEFAULT_MAX_RETRIES, ctrl.maxRetriesPerStep)
    }

    // ── Rule-based LocalPlanner ───────────────────────────────────────────────

    @Test
    fun `rule-based planner maps type keyword to type action`() {
        val planner = LocalPlanner(SingleStepPlannerService(loaded = false))
        val seq = planner.plan("sess", "type hello in the search box", null)
        assertEquals(1, seq.steps.size)
        assertEquals("type", seq.steps[0].actionType)
    }

    @Test
    fun `rule-based planner maps scroll keyword to scroll action`() {
        val planner = LocalPlanner(SingleStepPlannerService(loaded = false))
        val seq = planner.plan("sess", "scroll down the feed", null)
        assertEquals("scroll", seq.steps[0].actionType)
        assertEquals("down", seq.steps[0].parameters["direction"])
    }

    @Test
    fun `rule-based planner maps back keyword to back action`() {
        val planner = LocalPlanner(SingleStepPlannerService(loaded = false))
        val seq = planner.plan("sess", "press back button", null)
        assertEquals("back", seq.steps[0].actionType)
    }

    @Test
    fun `rule-based planner maps home keyword to home action`() {
        val planner = LocalPlanner(SingleStepPlannerService(loaded = false))
        val seq = planner.plan("sess", "go home", null)
        assertEquals("home", seq.steps[0].actionType)
    }

    @Test
    fun `rule-based planner defaults to tap for unknown instruction`() {
        val planner = LocalPlanner(SingleStepPlannerService(loaded = false))
        val seq = planner.plan("sess", "open the camera", null)
        assertEquals("tap", seq.steps[0].actionType)
    }

    // ── ExecutorBridge direct-dispatch actions ────────────────────────────────

    @Test
    fun `ExecutorBridge dispatches back action directly without grounding`() {
        val accessibility = FakeAccessibilityExecutor(returns = true)
        val bridge = ExecutorBridge(
            groundingService = FakeGrounder(),
            accessibilityExecutor = accessibility,
            imageScaler = NoOpImageScaler()
        )
        val step = ActionStep(id = "1", actionType = "back", intent = "press back")
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val result = bridge.execute(step, jpeg, 1080, 2340)
        assertEquals(StepStatus.SUCCESS, result.status)
        assertTrue(accessibility.lastAction is AccessibilityExecutor.AccessibilityAction.Back)
    }

    @Test
    fun `ExecutorBridge dispatches home action directly without grounding`() {
        val accessibility = FakeAccessibilityExecutor(returns = true)
        val bridge = ExecutorBridge(
            groundingService = FakeGrounder(),
            accessibilityExecutor = accessibility,
            imageScaler = NoOpImageScaler()
        )
        val step = ActionStep(id = "1", actionType = "home", intent = "go home")
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val result = bridge.execute(step, jpeg, 1080, 2340)
        assertEquals(StepStatus.SUCCESS, result.status)
        assertTrue(accessibility.lastAction is AccessibilityExecutor.AccessibilityAction.Home)
    }

    @Test
    fun `ExecutorBridge returns FAILED when accessibility executor returns false`() {
        val bridge = ExecutorBridge(
            groundingService = FakeGrounder(),
            accessibilityExecutor = FakeAccessibilityExecutor(returns = false),
            imageScaler = NoOpImageScaler()
        )
        val step = ActionStep(id = "1", actionType = "back", intent = "back")
        val result = bridge.execute(step, byteArrayOf(1), 100, 200)
        assertEquals(StepStatus.FAILED, result.status)
        assertNotNull(result.failureReason)
    }
}
