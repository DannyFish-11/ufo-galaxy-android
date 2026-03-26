package com.ufo.galaxy.local

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService

/**
 * Reusable fake components for local-loop scenario tests.
 *
 * All fakes are pure Kotlin data/object types that require no Android framework.
 * They are designed for composability: use the companion factory methods or
 * configure via constructor parameters for custom scenarios.
 *
 * ## Usage in tests
 * ```kotlin
 * val scenario = LocalLoopScenario(
 *     name = "my-test",
 *     planner = FakePlannerService.alwaysEmpty(),
 *     readinessProvider = FakeReadinessProvider.fullyReady()
 * )
 * val result = runner.run(scenario)
 * ```
 *
 * To add a new regression scenario, create a [LocalLoopScenario] using these fakes
 * and add a test assertion in [LocalLoopCorrectnessTest] or a new test class.
 * See `docs/LOCAL_LOOP_TESTING.md` for detailed guidance.
 */

// ────────────────────────────────────────────────────────────────────────────
// FakePlannerService
// ────────────────────────────────────────────────────────────────────────────

/**
 * Configurable fake [LocalPlannerService].
 *
 * @param loaded       Whether [isModelLoaded] returns `true`.
 * @param planSteps    Steps returned by [plan]; each pair is (action_type, intent).
 * @param replanSteps  Steps returned by [replan]; empty means replan fails.
 * @param planError    When non-null, [plan] returns this error with empty steps.
 */
class FakePlannerService(
    private val loaded: Boolean = true,
    private val planSteps: List<Pair<String, String>> = listOf("tap" to "tap the button"),
    private val replanSteps: List<Pair<String, String>> = listOf("tap" to "recovery tap"),
    private val planError: String? = null
) : LocalPlannerService {

    /** Number of times [plan] was called. */
    var planCallCount: Int = 0
        private set

    /** Number of times [replan] was called. */
    var replanCallCount: Int = 0
        private set

    override fun loadModel(): Boolean = loaded
    override fun unloadModel() {}
    override fun isModelLoaded(): Boolean = loaded

    override fun plan(
        goal: String,
        constraints: List<String>,
        screenshotBase64: String?
    ): LocalPlannerService.PlanResult {
        planCallCount++
        if (planError != null) {
            return LocalPlannerService.PlanResult(steps = emptyList(), error = planError)
        }
        return LocalPlannerService.PlanResult(
            steps = planSteps.map { (type, intent) ->
                LocalPlannerService.PlanStep(action_type = type, intent = intent)
            }
        )
    }

    override fun replan(
        goal: String,
        constraints: List<String>,
        failedStep: LocalPlannerService.PlanStep,
        error: String,
        screenshotBase64: String?
    ): LocalPlannerService.PlanResult {
        replanCallCount++
        return LocalPlannerService.PlanResult(
            steps = replanSteps.map { (type, intent) ->
                LocalPlannerService.PlanStep(action_type = type, intent = intent)
            },
            error = if (replanSteps.isEmpty()) "replan exhausted: no recovery steps available" else null
        )
    }

    companion object {
        /** Single tap step; planner model loaded. */
        fun singleStep(
            actionType: String = "tap",
            intent: String = "tap the button"
        ): FakePlannerService = FakePlannerService(
            planSteps = listOf(actionType to intent)
        )

        /** Returns multiple steps; planner model loaded. */
        fun multiStep(vararg steps: Pair<String, String>): FakePlannerService =
            FakePlannerService(planSteps = steps.toList())

        /** Plan returns empty (failure); useful for testing plan-failure paths. */
        fun alwaysEmpty(): FakePlannerService = FakePlannerService(planSteps = emptyList())

        /** Plan returns an error; useful for testing plan-error paths. */
        fun planError(message: String = "planner error"): FakePlannerService =
            FakePlannerService(planError = message)

        /** Planner model is not loaded; triggers rule-based fallback. */
        fun notLoaded(): FakePlannerService = FakePlannerService(loaded = false)

        /**
         * Plan succeeds with [planStep]; replan returns [replanStep].
         * Use this to verify replan-triggered recovery.
         */
        fun withReplan(
            planStep: Pair<String, String> = "tap" to "first attempt",
            replanStep: Pair<String, String> = "tap" to "recovery"
        ): FakePlannerService = FakePlannerService(
            planSteps = listOf(planStep),
            replanSteps = listOf(replanStep)
        )

        /** Replan produces no steps (replan exhaustion). */
        fun replanFails(
            planStep: Pair<String, String> = "tap" to "first attempt"
        ): FakePlannerService = FakePlannerService(
            planSteps = listOf(planStep),
            replanSteps = emptyList()
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
// FakeGroundingService
// ────────────────────────────────────────────────────────────────────────────

/**
 * Configurable fake [LocalGroundingService].
 *
 * @param loaded     Whether [isModelLoaded] returns `true`.
 * @param x          Returned x coordinate.
 * @param y          Returned y coordinate.
 * @param confidence Returned confidence score.
 * @param error      When non-null, [ground] returns this error with zero coordinates.
 * @param delayMs    Optional simulated latency (milliseconds); useful for timeout tests.
 */
class FakeGroundingService(
    private val loaded: Boolean = true,
    private val x: Int = 540,
    private val y: Int = 1170,
    private val confidence: Float = 0.9f,
    private val error: String? = null,
    private val delayMs: Long = 0L
) : LocalGroundingService {

    /** Number of times [ground] was called. */
    var groundCallCount: Int = 0
        private set

    override fun loadModel(): Boolean = loaded
    override fun unloadModel() {}
    override fun isModelLoaded(): Boolean = loaded

    override fun ground(
        intent: String,
        screenshotBase64: String,
        width: Int,
        height: Int
    ): LocalGroundingService.GroundingResult {
        groundCallCount++
        if (delayMs > 0) Thread.sleep(delayMs)
        return if (error != null) {
            LocalGroundingService.GroundingResult(
                x = 0, y = 0, confidence = 0f,
                element_description = "",
                error = error
            )
        } else {
            LocalGroundingService.GroundingResult(
                x = if (width > 0) width / 2 else x,
                y = if (height > 0) height / 2 else y,
                confidence = confidence,
                element_description = "target element"
            )
        }
    }

    companion object {
        /** Grounding always succeeds with centre-screen coordinates. */
        fun alwaysSucceed(confidence: Float = 0.9f): FakeGroundingService =
            FakeGroundingService(confidence = confidence)

        /** Grounding model not loaded; forces rule-based fallback in the ladder. */
        fun notLoaded(): FakeGroundingService = FakeGroundingService(loaded = false)

        /** Grounding always returns an error; exercises the full fallback ladder. */
        fun alwaysFail(error: String = "grounding failed"): FakeGroundingService =
            FakeGroundingService(error = error)

        /**
         * Grounding takes [delayMs] per call; useful for step-timeout tests.
         * Combine with a short [stepTimeoutMs] in the scenario.
         */
        fun slow(delayMs: Long): FakeGroundingService = FakeGroundingService(delayMs = delayMs)
    }
}

// ────────────────────────────────────────────────────────────────────────────
// FakeScreenshotProvider
// ────────────────────────────────────────────────────────────────────────────

/**
 * Configurable fake [EdgeExecutor.ScreenshotProvider].
 *
 * @param jpegBytes     Bytes returned by [captureJpeg]. Unique bytes per instance by default.
 * @param width         Value returned by [screenWidth].
 * @param height        Value returned by [screenHeight].
 * @param throwOnCapture When `true`, [captureJpeg] throws an [IllegalStateException].
 * @param varyOutput    When `true`, each [captureJpeg] call returns slightly different bytes
 *                      (simulates UI change between steps).
 */
class FakeScreenshotProvider(
    private val jpegBytes: ByteArray = MINIMAL_JPEG,
    private val width: Int = 1080,
    private val height: Int = 2340,
    private val throwOnCapture: Boolean = false,
    private val varyOutput: Boolean = true
) : EdgeExecutor.ScreenshotProvider {

    /** Number of times [captureJpeg] has been called. */
    var captureCount: Int = 0
        private set

    override fun captureJpeg(): ByteArray {
        if (throwOnCapture) throw IllegalStateException("screenshot capture failed: provider unavailable")
        captureCount++
        return if (varyOutput) {
            // Vary the last byte so PostActionObserver sees a UI change each step.
            jpegBytes.copyOf().also { it[it.size - 1] = captureCount.toByte() }
        } else {
            jpegBytes.copyOf()
        }
    }

    override fun screenWidth(): Int = width
    override fun screenHeight(): Int = height

    companion object {
        /** Minimal 3-byte JPEG header marker. */
        val MINIMAL_JPEG: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)

        /** Standard provider: varying output so UI-change detection sees progress. */
        fun varying(): FakeScreenshotProvider = FakeScreenshotProvider(varyOutput = true)

        /**
         * All captures return identical bytes so [PostActionObserver] sees no UI change.
         * Use to trigger [FailureCode.LOOP_STAGNATION_NO_UI_CHANGE].
         */
        fun noChange(): FakeScreenshotProvider = FakeScreenshotProvider(varyOutput = false)

        /** Provider that always throws; triggers screenshot-failure paths. */
        fun alwaysFail(): FakeScreenshotProvider =
            FakeScreenshotProvider(throwOnCapture = true)
    }
}

// ────────────────────────────────────────────────────────────────────────────
// FakeAccessibilityExecutor
// ────────────────────────────────────────────────────────────────────────────

/**
 * Configurable fake [AccessibilityExecutor].
 *
 * @param returns  Value returned by [execute]. `true` = success; `false` = failure.
 * @param delayMs  Optional simulated latency per call (milliseconds).
 */
class FakeAccessibilityExecutor(
    private val returns: Boolean = true,
    private val delayMs: Long = 0L
) : AccessibilityExecutor {

    /** Number of times [execute] was called. */
    var callCount: Int = 0
        private set

    /** Last action dispatched to this executor; `null` before first call. */
    var lastAction: AccessibilityExecutor.AccessibilityAction? = null
        private set

    override fun execute(action: AccessibilityExecutor.AccessibilityAction): Boolean {
        callCount++
        lastAction = action
        if (delayMs > 0) Thread.sleep(delayMs)
        return returns
    }

    companion object {
        /** Executor that always returns `true` (success). */
        fun alwaysSucceed(): FakeAccessibilityExecutor = FakeAccessibilityExecutor(returns = true)

        /** Executor that always returns `false` (failure). */
        fun alwaysFail(): FakeAccessibilityExecutor = FakeAccessibilityExecutor(returns = false)

        /**
         * Executor that takes [delayMs] per call.
         * Useful for triggering step/goal timeout scenarios.
         */
        fun slow(delayMs: Long): FakeAccessibilityExecutor =
            FakeAccessibilityExecutor(delayMs = delayMs)
    }
}

// ────────────────────────────────────────────────────────────────────────────
// FakeReadinessProvider
// ────────────────────────────────────────────────────────────────────────────

/**
 * Configurable fake [LocalLoopReadinessProvider].
 *
 * @param readiness The [LocalLoopReadiness] snapshot returned by [getReadiness].
 */
class FakeReadinessProvider(
    private val readiness: LocalLoopReadiness
) : LocalLoopReadinessProvider {

    override fun getReadiness(): LocalLoopReadiness = readiness

    companion object {
        /** All subsystems ready — [LocalLoopState.READY]. */
        fun fullyReady(): FakeReadinessProvider = FakeReadinessProvider(
            LocalLoopReadiness(
                modelFilesReady = true,
                plannerLoaded = true,
                groundingLoaded = true,
                accessibilityReady = true,
                screenshotReady = true,
                actionExecutorReady = true,
                blockers = emptyList()
            )
        )

        /** Critical blockers present — [LocalLoopState.UNAVAILABLE]. */
        fun unavailable(): FakeReadinessProvider = FakeReadinessProvider(
            LocalLoopReadiness(
                modelFilesReady = false,
                plannerLoaded = false,
                groundingLoaded = false,
                accessibilityReady = false,
                screenshotReady = false,
                actionExecutorReady = false,
                blockers = listOf(
                    LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED,
                    LocalLoopFailureType.SCREENSHOT_UNAVAILABLE,
                    LocalLoopFailureType.ACTION_EXECUTOR_UNAVAILABLE
                )
            )
        )

        /**
         * Non-critical blockers only — [LocalLoopState.DEGRADED].
         * Execution proceeds but the planner/grounding models are absent.
         */
        fun degraded(): FakeReadinessProvider = FakeReadinessProvider(
            LocalLoopReadiness(
                modelFilesReady = false,
                plannerLoaded = false,
                groundingLoaded = false,
                accessibilityReady = true,
                screenshotReady = true,
                actionExecutorReady = true,
                blockers = listOf(
                    LocalLoopFailureType.MODEL_FILES_MISSING,
                    LocalLoopFailureType.PLANNER_UNAVAILABLE,
                    LocalLoopFailureType.GROUNDING_UNAVAILABLE
                )
            )
        )

        /** Readiness with specific [blockers]. */
        fun withBlockers(vararg blockers: LocalLoopFailureType): FakeReadinessProvider {
            val hasCritical = blockers.any { it.isCritical }
            return FakeReadinessProvider(
                LocalLoopReadiness(
                    modelFilesReady = LocalLoopFailureType.MODEL_FILES_MISSING !in blockers,
                    plannerLoaded = LocalLoopFailureType.PLANNER_UNAVAILABLE !in blockers,
                    groundingLoaded = LocalLoopFailureType.GROUNDING_UNAVAILABLE !in blockers,
                    accessibilityReady = LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED !in blockers,
                    screenshotReady = LocalLoopFailureType.SCREENSHOT_UNAVAILABLE !in blockers,
                    actionExecutorReady = LocalLoopFailureType.ACTION_EXECUTOR_UNAVAILABLE !in blockers,
                    blockers = blockers.toList()
                )
            )
        }
    }
}
