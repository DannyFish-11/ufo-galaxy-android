package com.ufo.galaxy.local

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService

/**
 * Descriptor for a single local-loop execution scenario.
 *
 * A [LocalLoopScenario] bundles every parameter needed for one deterministic
 * end-to-end execution of the local loop through [DefaultLocalLoopExecutor] →
 * [com.ufo.galaxy.loop.LoopController].  All dependencies are provided as
 * simple Kotlin objects (no Android framework required) so scenarios can run
 * on the JVM without any device or emulator.
 *
 * Default values produce a minimal happy-path scenario:
 * - fully-ready readiness
 * - single-step plan (tap)
 * - grounding succeeds with centre-screen coordinates
 * - accessibility execution succeeds
 * - varying screenshot output (UI change detected each step)
 *
 * Compose scenarios using [FakePlannerService], [FakeGroundingService],
 * [FakeScreenshotProvider], [FakeAccessibilityExecutor], and [FakeReadinessProvider].
 *
 * ## Examples
 *
 * **Happy path:**
 * ```kotlin
 * LocalLoopScenario("happy-path")
 * ```
 *
 * **Readiness unavailable:**
 * ```kotlin
 * LocalLoopScenario(
 *     name = "readiness-blocked",
 *     readinessProvider = FakeReadinessProvider.unavailable()
 * )
 * ```
 *
 * **Planner model not loaded (rule-based fallback):**
 * ```kotlin
 * LocalLoopScenario(
 *     name = "planner-not-loaded",
 *     planner = FakePlannerService.notLoaded()
 * )
 * ```
 *
 * **Grounding fails (ladder fallback to heuristic):**
 * ```kotlin
 * LocalLoopScenario(
 *     name = "grounding-fails",
 *     grounder = FakeGroundingService.alwaysFail()
 * )
 * ```
 *
 * **No-UI-change stagnation:**
 * ```kotlin
 * LocalLoopScenario(
 *     name = "stagnation-no-ui-change",
 *     planner = FakePlannerService.multiStep(*Array(10) { "tap" to "tap step $it" }),
 *     screenshotProvider = FakeScreenshotProvider.noChange(),
 *     stagnationDetector = StagnationDetector(maxNoUiChangeSteps = 3)
 * )
 * ```
 *
 * **Goal timeout:**
 * ```kotlin
 * LocalLoopScenario(
 *     name = "goal-timeout",
 *     planner = FakePlannerService.multiStep(*Array(5) { "tap" to "step $it" }),
 *     accessibilityExecutor = FakeAccessibilityExecutor.slow(delayMs = 10),
 *     goalTimeoutMs = 5L
 * )
 * ```
 *
 * @property name              Human-readable name for this scenario (used in assertion messages).
 * @property instruction       Natural-language task instruction forwarded to the local loop.
 * @property sourceRuntimePosture Canonical source-device participation posture for this scenario.
 *                             Defaults to [com.ufo.galaxy.runtime.SourceRuntimePosture.JOIN_RUNTIME]
 *                             because scenario-based tests represent Android acting as the local
 *                             runtime executor, which requires an explicit join_runtime posture
 *                             (PR-2A posture gate).  Set to
 *                             [com.ufo.galaxy.runtime.SourceRuntimePosture.CONTROL_ONLY] to test
 *                             the posture-blocked path.
 * @property planner           Planner service fake.
 * @property grounder          Grounding service fake.
 * @property screenshotProvider Screenshot capture fake.
 * @property accessibilityExecutor Accessibility execution fake.
 * @property readinessProvider Readiness gate fake.
 * @property maxSteps          Step budget forwarded to [com.ufo.galaxy.loop.LoopController].
 * @property maxRetriesPerStep Per-step retry limit forwarded to [com.ufo.galaxy.loop.LoopController].
 * @property stepTimeoutMs     Per-step wall-clock timeout (0 = disabled).
 * @property goalTimeoutMs     Total-goal wall-clock timeout (0 = disabled).
 * @property stagnationDetector Stagnation detector; override for custom thresholds.
 */
data class LocalLoopScenario(
    val name: String,
    val instruction: String = "test the scenario",
    val sourceRuntimePosture: String = com.ufo.galaxy.runtime.SourceRuntimePosture.JOIN_RUNTIME,
    val planner: LocalPlannerService = FakePlannerService.singleStep(),
    val grounder: LocalGroundingService = FakeGroundingService.alwaysSucceed(),
    val screenshotProvider: EdgeExecutor.ScreenshotProvider = FakeScreenshotProvider.varying(),
    val accessibilityExecutor: AccessibilityExecutor = FakeAccessibilityExecutor.alwaysSucceed(),
    val readinessProvider: LocalLoopReadinessProvider = FakeReadinessProvider.fullyReady(),
    val maxSteps: Int = 10,
    val maxRetriesPerStep: Int = 2,
    val stepTimeoutMs: Long = 0L,
    val goalTimeoutMs: Long = 0L,
    val stagnationDetector: StagnationDetector = StagnationDetector()
)
