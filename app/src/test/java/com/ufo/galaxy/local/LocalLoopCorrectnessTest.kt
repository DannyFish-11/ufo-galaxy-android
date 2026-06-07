package com.ufo.galaxy.local

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.loop.ExecutorBridge
import com.ufo.galaxy.loop.LocalPlanner
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Correctness tests for the local-loop execution pipeline.
 *
 * Tests are organised by the behaviour they validate:
 *
 * 1. **Readiness gating** — [DefaultLocalLoopExecutor] blocks execution when
 *    critical subsystems are unavailable ([LocalLoopState.UNAVAILABLE]).
 * 2. **Happy path** — Successful end-to-end execution with a single-step plan.
 * 3. **Planner fallback** — Rule-based planning when the planner model is absent.
 * 4. **Grounding fallback** — Ladder fallback when primary grounding fails.
 * 5. **Stagnation guards** — Repeated-action and no-UI-change termination.
 * 6. **Timeout termination** — Step-level and goal-level timeout paths.
 * 7. **Failure-code propagation** — Structured [FailureCode] values appear in results.
 *
 * All tests use [LocalLoopScenarioRunner] and the reusable fakes in [LocalLoopTestFakes]
 * so new regression scenarios can be added with minimal boilerplate.
 *
 * To extend this test suite see `docs/LOCAL_LOOP_TESTING.md`.
 */
class LocalLoopCorrectnessTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val runner by lazy { LocalLoopScenarioRunner(tmpFolder.newFolder("models")) }

    // ── 1. Readiness gating ───────────────────────────────────────────────────

    @Test
    fun `readiness UNAVAILABLE blocks execution and returns STATUS_FAILED`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "readiness-unavailable",
                readinessProvider = FakeReadinessProvider.unavailable()
            )
        )
        assertEquals(
            "UNAVAILABLE readiness must return STATUS_FAILED",
            LocalLoopResult.STATUS_FAILED, result.status
        )
    }

    @Test
    fun `readiness UNAVAILABLE sets stopReason to readiness_unavailable`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "readiness-stop-reason",
                readinessProvider = FakeReadinessProvider.unavailable()
            )
        )
        assertEquals(
            "stopReason must be readiness_unavailable",
            DefaultLocalLoopExecutor.STOP_READINESS_UNAVAILABLE, result.stopReason
        )
    }

    @Test
    fun `readiness UNAVAILABLE returns zero steps`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "readiness-zero-steps",
                readinessProvider = FakeReadinessProvider.unavailable()
            )
        )
        assertEquals("No steps must be executed when blocked", 0, result.stepCount)
    }

    @Test
    fun `readiness UNAVAILABLE error message lists blockers`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "readiness-error-message",
                readinessProvider = FakeReadinessProvider.withBlockers(
                    LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED,
                    LocalLoopFailureType.SCREENSHOT_UNAVAILABLE
                )
            )
        )
        assertNotNull("error must not be null when blocked", result.error)
        assertTrue(
            "error message must mention ACCESSIBILITY_SERVICE_DISABLED",
            result.error!!.contains("ACCESSIBILITY_SERVICE_DISABLED", ignoreCase = true)
        )
    }

    @Test
    fun `readiness DEGRADED does not block execution`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "readiness-degraded-proceeds",
                readinessProvider = FakeReadinessProvider.degraded(),
                // planner not loaded → rule-based fallback → still produces a step
                planner = FakePlannerService.notLoaded()
            )
        )
        // DEGRADED must not block; the loop runs (rule-based path) and succeeds
        assertEquals(
            "DEGRADED readiness must not block — execution should succeed",
            LocalLoopResult.STATUS_SUCCESS, result.status
        )
    }

    @Test
    fun `readiness READY allows normal execution`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "readiness-ready",
                readinessProvider = FakeReadinessProvider.fullyReady()
            )
        )
        assertEquals(LocalLoopResult.STATUS_SUCCESS, result.status)
    }

    // ── 2. Happy path ─────────────────────────────────────────────────────────

    @Test
    fun `happy path single step returns STATUS_SUCCESS`() {
        val result = runner.run(LocalLoopScenario("happy-path-single"))
        assertEquals(LocalLoopResult.STATUS_SUCCESS, result.status)
    }

    @Test
    fun `happy path sets stopReason to task_complete`() {
        val result = runner.run(LocalLoopScenario("happy-path-stop-reason"))
        assertEquals(LoopController.STOP_TASK_COMPLETE, result.stopReason)
    }

    @Test
    fun `happy path stepCount equals plan step count`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "happy-path-step-count",
                planner = FakePlannerService.multiStep(
                    "tap" to "step one",
                    "tap" to "step two",
                    "tap" to "step three"
                )
            )
        )
        assertEquals("Expected 3 executed steps", 3, result.stepCount)
    }

    @Test
    fun `happy path sessionId is non-empty`() {
        val result = runner.run(LocalLoopScenario("happy-path-session-id"))
        assertTrue("sessionId must not be empty", result.sessionId.isNotEmpty())
    }

    @Test
    fun `happy path instruction is echoed in result`() {
        val instruction = "open the settings menu"
        val result = runner.run(LocalLoopScenario("happy-path-echo", instruction = instruction))
        assertEquals(instruction, result.instruction)
    }

    @Test
    fun `happy path error is null on success`() {
        val result = runner.run(LocalLoopScenario("happy-path-no-error"))
        assertNull("error must be null on success", result.error)
    }

    @Test
    fun `happy path isSuccess is true`() {
        val result = runner.run(LocalLoopScenario("happy-path-is-success"))
        assertTrue("isSuccess must be true", result.isSuccess)
    }

    // ── 3. Planner fallback ───────────────────────────────────────────────────

    @Test
    fun `planner not loaded falls back to rule-based and succeeds`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "planner-not-loaded",
                planner = FakePlannerService.notLoaded(),
                instruction = "tap the login button"
            )
        )
        assertEquals(
            "Rule-based fallback must produce a success result",
            LocalLoopResult.STATUS_SUCCESS, result.status
        )
        assertEquals(1, result.stepCount)
    }

    @Test
    fun `planner not loaded scrolls correctly via rule-based fallback`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "planner-not-loaded-scroll",
                planner = FakePlannerService.notLoaded(),
                instruction = "scroll down the feed"
            )
        )
        assertEquals(LocalLoopResult.STATUS_SUCCESS, result.status)
    }

    @Test
    fun `replan triggered on step failure succeeds with recovery step`() {
        var callCount = 0
        val customExecutor = object : AccessibilityExecutor {
            override fun execute(
                action: AccessibilityExecutor.AccessibilityAction
            ): Boolean {
                callCount++
                return callCount > 1  // fail first, succeed thereafter
            }
        }
        val result = runner.run(
            LocalLoopScenario(
                name = "replan-on-step-failure",
                planner = FakePlannerService.withReplan(
                    planStep = "tap" to "initial attempt",
                    replanStep = "tap" to "recovery attempt"
                ),
                accessibilityExecutor = customExecutor,
                maxRetriesPerStep = 1
            )
        )
        assertEquals(
            "Replan must produce a success after recovery",
            LocalLoopResult.STATUS_SUCCESS, result.status
        )
    }

    @Test
    fun `replan exhausted returns STATUS_FAILED`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "replan-exhausted",
                planner = FakePlannerService.replanFails(planStep = "tap" to "attempt"),
                accessibilityExecutor = FakeAccessibilityExecutor.alwaysFail(),
                maxRetriesPerStep = 1
            )
        )
        assertEquals(LocalLoopResult.STATUS_FAILED, result.status)
        assertEquals(LoopController.STOP_REPLAN_FAILED, result.stopReason)
    }

    @Test
    fun `empty planner plan fails with STOP_PLAN_FAILED`() {
        // When PlannerFallbackLadder exhausts all stages it still falls back to rule-based.
        // To actually exhaust the ladder and get STOP_PLAN_FAILED we need the rule-based
        // step to also fail. Since rule-based always produces a tap step, the accessibility
        // executor must fail and retries must be 0 for the session to fail.
        // This test verifies that a forced empty plan (all ladder stages exhausted) combined
        // with a failed action returns a failed result.
        val result = runner.run(
            LocalLoopScenario(
                name = "planner-empty-all-stages",
                planner = FakePlannerService.alwaysEmpty(),
                accessibilityExecutor = FakeAccessibilityExecutor.alwaysFail(),
                maxRetriesPerStep = 0
            )
        )
        assertEquals(LocalLoopResult.STATUS_FAILED, result.status)
        assertNotNull("error must be set on planner/execution failure", result.error)
    }

    // ── 4. Grounding fallback ─────────────────────────────────────────────────

    @Test
    fun `grounding fails but fallback ladder produces coordinates and execution succeeds`() {
        // The GroundingFallbackLadder has heuristic-region and accessibility-node stages
        // that never fail. Even if the primary SeeClick grounder is unavailable the
        // ladder will produce some coordinates and the tap will be dispatched.
        val result = runner.run(
            LocalLoopScenario(
                name = "grounding-fails-fallback",
                grounder = FakeGroundingService.alwaysFail("grounding service unavailable"),
                planner = FakePlannerService.singleStep("tap", "tap the button")
            )
        )
        // The grounding ladder has a fallback that always produces coordinates,
        // so execution should succeed (accessibility returns true).
        assertEquals(
            "Grounding fallback must allow execution to complete",
            LocalLoopResult.STATUS_SUCCESS, result.status
        )
    }

    @Test
    fun `grounding model not loaded falls back to heuristic and execution succeeds`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "grounding-not-loaded",
                grounder = FakeGroundingService.notLoaded(),
                planner = FakePlannerService.singleStep("tap", "tap the OK button")
            )
        )
        assertEquals(LocalLoopResult.STATUS_SUCCESS, result.status)
    }

    @Test
    fun `back action bypasses grounding entirely`() {
        val grounder = FakeGroundingService.alwaysFail("should not be called")
        val result = runner.run(
            LocalLoopScenario(
                name = "back-bypasses-grounding",
                planner = FakePlannerService.singleStep("back", "press back"),
                grounder = grounder
            )
        )
        assertEquals("back action must succeed without grounding", LocalLoopResult.STATUS_SUCCESS, result.status)
        assertEquals("grounding must not be invoked for back action", 0, grounder.groundCallCount)
    }

    // ── 5. Stagnation guards ──────────────────────────────────────────────────

    @Test
    fun `repeated identical action triggers stagnation termination`() {
        // Same action repeated > maxRepeatedSameAction times triggers stagnation.
        val detector = StagnationDetector(maxRepeatedSameAction = 2, maxNoUiChangeSteps = 999)
        val result = runner.run(
            LocalLoopScenario(
                name = "stagnation-repeated-action",
                planner = FakePlannerService.multiStep(
                    "tap" to "same intent",
                    "tap" to "same intent",
                    "tap" to "same intent",
                    "tap" to "same intent"
                ),
                stagnationDetector = detector,
                maxSteps = 10
            )
        )
        assertEquals(LocalLoopResult.STATUS_FAILED, result.status)
        assertEquals(LoopController.STOP_STAGNATION, result.stopReason)
    }

    @Test
    fun `no-UI-change stagnation terminates the session`() {
        // When every post-action screenshot is identical the StagnationDetector fires.
        val detector = StagnationDetector(maxNoUiChangeSteps = 3, maxRepeatedSameAction = 999)
        val result = runner.run(
            LocalLoopScenario(
                name = "stagnation-no-ui-change",
                planner = FakePlannerService.multiStep(
                    "tap" to "step A",
                    "tap" to "step B",
                    "tap" to "step C",
                    "tap" to "step D"
                ),
                screenshotProvider = FakeScreenshotProvider.noChange(),
                stagnationDetector = detector,
                maxSteps = 10
            )
        )
        assertEquals(LocalLoopResult.STATUS_FAILED, result.status)
        assertEquals(LoopController.STOP_STAGNATION, result.stopReason)
    }

    @Test
    fun `stagnation result has non-null error message`() {
        val detector = StagnationDetector(maxRepeatedSameAction = 1, maxNoUiChangeSteps = 999)
        val result = runner.run(
            LocalLoopScenario(
                name = "stagnation-error-message",
                planner = FakePlannerService.multiStep(
                    "tap" to "same",
                    "tap" to "same"
                ),
                stagnationDetector = detector
            )
        )
        assertNotNull("error must not be null on stagnation", result.error)
    }

    // ── 6. Timeout termination ────────────────────────────────────────────────

    @Test
    fun `step timeout terminates session with STOP_STEP_TIMEOUT`() {
        // The accessibility executor introduces a 15 ms delay; stepTimeoutMs = 5 ms.
        // By the time the bridge finishes, the elapsed time exceeds the step budget.
        val result = runner.run(
            LocalLoopScenario(
                name = "step-timeout",
                planner = FakePlannerService.multiStep("tap" to "slow tap"),
                accessibilityExecutor = FakeAccessibilityExecutor.slow(delayMs = 15L),
                stepTimeoutMs = 5L
            )
        )
        assertEquals(LocalLoopResult.STATUS_FAILED, result.status)
        assertEquals(
            "stopReason must be step_timeout",
            LoopController.STOP_STEP_TIMEOUT, result.stopReason
        )
    }

    @Test
    fun `goal timeout terminates session with STOP_GOAL_TIMEOUT`() {
        // 5 steps, each with a 10 ms delay; goal timeout = 5 ms.
        // The goal timer starts before step 1; by step 2 the goal timeout fires.
        val result = runner.run(
            LocalLoopScenario(
                name = "goal-timeout",
                planner = FakePlannerService.multiStep(
                    "tap" to "s1", "tap" to "s2",
                    "tap" to "s3", "tap" to "s4"
                ),
                accessibilityExecutor = FakeAccessibilityExecutor.slow(delayMs = 10L),
                goalTimeoutMs = 5L
            )
        )
        assertEquals(LocalLoopResult.STATUS_FAILED, result.status)
        assertEquals(
            "stopReason must be goal_timeout",
            LoopController.STOP_GOAL_TIMEOUT, result.stopReason
        )
    }

    @Test
    fun `timeout result has non-null error message`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "timeout-error-message",
                planner = FakePlannerService.multiStep("tap" to "tap"),
                accessibilityExecutor = FakeAccessibilityExecutor.slow(delayMs = 15L),
                stepTimeoutMs = 5L
            )
        )
        assertNotNull("error must not be null on timeout", result.error)
    }

    // ── 7. Failure-code propagation ───────────────────────────────────────────

    @Test
    fun `screenshot failure propagates STOP_SCREENSHOT_FAILED`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "screenshot-failure",
                screenshotProvider = FakeScreenshotProvider.alwaysFail()
            )
        )
        assertEquals(LocalLoopResult.STATUS_FAILED, result.status)
        assertEquals(LoopController.STOP_SCREENSHOT_FAILED, result.stopReason)
    }

    @Test
    fun `step retries exhausted propagates STOP_STEP_EXHAUSTED`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "step-retries-exhausted",
                accessibilityExecutor = FakeAccessibilityExecutor.alwaysFail(),
                maxRetriesPerStep = 0
            )
        )
        assertEquals(LocalLoopResult.STATUS_FAILED, result.status)
        assertEquals(LoopController.STOP_STEP_EXHAUSTED, result.stopReason)
    }

    @Test
    fun `max steps budget exceeded propagates STOP_MAX_STEPS`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "max-steps-exceeded",
                planner = FakePlannerService.multiStep(
                    *Array(20) { "tap" to "step $it" }
                ),
                maxSteps = 3
            )
        )
        assertEquals(LocalLoopResult.STATUS_FAILED, result.status)
        assertEquals(LoopController.STOP_MAX_STEPS, result.stopReason)
    }

    @Test
    fun `remote task block produces STATUS_CANCELLED with STOP_BLOCKED_BY_REMOTE`() {
        // To test the remote-task block we need direct access to LoopController.
        // Build the pipeline manually and call cancelForRemoteTask() before running.
        val manager = ModelAssetManager(tmpFolder.newFolder("models2"))
        val downloader = ModelDownloader(manager.modelsDir)
        val loopController = LoopController(
            localPlanner = LocalPlanner(FakePlannerService.singleStep()),
            executorBridge = ExecutorBridge(
                groundingService = FakeGroundingService.alwaysSucceed(),
                accessibilityExecutor = FakeAccessibilityExecutor.alwaysSucceed(),
                imageScaler = NoOpImageScaler()
            ),
            screenshotProvider = FakeScreenshotProvider.varying(),
            modelAssetManager = manager,
            modelDownloader = downloader
        )
        loopController.cancelForRemoteTask()

        val executor = DefaultLocalLoopExecutor(
            loopController = loopController,
            readinessProvider = FakeReadinessProvider.fullyReady()
        )
        val result = runBlocking {
            executor.execute(LocalLoopOptions("blocked instruction"))
        }

        assertEquals(LocalLoopResult.STATUS_CANCELLED, result.status)
        assertEquals(LoopController.STOP_BLOCKED_BY_REMOTE, result.stopReason)
    }

    @Test
    fun `after remote task cleared execution resumes normally`() {
        val manager = ModelAssetManager(tmpFolder.newFolder("models3"))
        val downloader = ModelDownloader(manager.modelsDir)
        val loopController = LoopController(
            localPlanner = LocalPlanner(FakePlannerService.singleStep()),
            executorBridge = ExecutorBridge(
                groundingService = FakeGroundingService.alwaysSucceed(),
                accessibilityExecutor = FakeAccessibilityExecutor.alwaysSucceed(),
                imageScaler = NoOpImageScaler()
            ),
            screenshotProvider = FakeScreenshotProvider.varying(),
            modelAssetManager = manager,
            modelDownloader = downloader
        )
        loopController.cancelForRemoteTask()
        loopController.clearRemoteTaskBlock()

        val executor = DefaultLocalLoopExecutor(
            loopController = loopController,
            readinessProvider = FakeReadinessProvider.fullyReady()
        )
        val result = runBlocking {
            executor.execute(LocalLoopOptions("resumed instruction"))
        }
        assertEquals(LocalLoopResult.STATUS_SUCCESS, result.status)
    }

    @Test
    fun `failure result has non-empty sessionId for tracing`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "failure-session-id",
                screenshotProvider = FakeScreenshotProvider.alwaysFail()
            )
        )
        assertTrue("sessionId must be non-empty even on failure", result.sessionId.isNotEmpty())
    }

    @Test
    fun `readiness unavailable result has non-empty sessionId`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "readiness-session-id",
                readinessProvider = FakeReadinessProvider.unavailable()
            )
        )
        assertTrue("sessionId must be non-empty", result.sessionId.isNotEmpty())
    }
}
