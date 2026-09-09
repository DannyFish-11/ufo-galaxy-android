package com.ufo.galaxy.local

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.runtime.SourceRuntimePosture

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
 * @property sourceRuntimePosture Canonical source-device participation posture for this scenario,
 *                             aligned with the PR #533 / PR #106 posture contract.
 *                             Defaults to [SourceRuntimePosture.JOIN_RUNTIME] so that existing
 *                             scenario-based tests proceed through the posture gate in
 *                             [DefaultLocalLoopExecutor].  Set to [SourceRuntimePosture.CONTROL_ONLY]
 *                             explicitly to test posture-blocked behaviour.
 */
data class LocalLoopScenario(
    val name: String,
    val instruction: String = "test the scenario",
    val planner: LocalPlannerService = FakePlannerService.singleStep(),
    val grounder: LocalGroundingService = FakeGroundingService.alwaysSucceed(),
    val screenshotProvider: EdgeExecutor.ScreenshotProvider = FakeScreenshotProvider.varying(),
    val accessibilityExecutor: AccessibilityExecutor = FakeAccessibilityExecutor.alwaysSucceed(),
    val readinessProvider: LocalLoopReadinessProvider = FakeReadinessProvider.fullyReady(),
    val maxSteps: Int = 10,
    val maxRetriesPerStep: Int = 2,
    val stepTimeoutMs: Long = 0L,
    val goalTimeoutMs: Long = 0L,
    val stagnationDetector: StagnationDetector = StagnationDetector(),
    val sourceRuntimePosture: String = SourceRuntimePosture.JOIN_RUNTIME,
    /**
     * 结构化感知通道(无障碍树快照)。
     *
     * 生产接线里这一路一直在场(UFOGalaxyApplication 把 AccessibilityUiSnapshotProvider
     * 同时注入了 EdgeExecutor 与 ExecutorBridge),而这个场景运行器此前从不注入,
     * 于是整套端到端场景跑的都是"没有树"的那一半。删掉三级猜屏幕中心的兜底之后,
     * 树是**没有 VLM 权重时唯一的定位依据** —— 这条路必须能被端到端测到。
     *
     * null(默认)= 纯视觉,与旧行为一致。
     */
    val uiSnapshot: com.ufo.galaxy.perception.UiStructuredSnapshot? = null
)
