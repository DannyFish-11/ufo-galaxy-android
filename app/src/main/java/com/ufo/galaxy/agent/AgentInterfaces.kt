package com.ufo.galaxy.agent

import android.graphics.Bitmap

// ── Legacy agent-package interfaces ─────────────────────────────────────────
//
// All types below (PlannerContext, StepResult, ScreenshotCapture, LocalPlanner,
// GUIGrounding, AgentRuntime) are LEGACY and are only referenced by the already-
// deprecated [EdgeOrchestrator].  They are NOT part of the canonical Android
// runtime pipeline.
//
// Canonical replacements:
//  - Screenshot capture  → [com.ufo.galaxy.inference.LocalGroundingService] /
//                          [com.ufo.galaxy.service.AccessibilityScreenshotProvider]
//  - Task planning       → [com.ufo.galaxy.inference.LocalPlannerService] (base64-based,
//                          JVM-testable) used by [com.ufo.galaxy.loop.LoopController]
//  - GUI grounding       → [com.ufo.galaxy.inference.LocalGroundingService] used by
//                          [com.ufo.galaxy.loop.ExecutorBridge]
//  - Step execution      → [com.ufo.galaxy.agent.EdgeExecutor] (canonical task executor)
//                          via [com.ufo.galaxy.agent.LocalGoalExecutor] →
//                          [com.ufo.galaxy.agent.AutonomousExecutionPipeline]
//
// Note: [AccessibilityExecutor] is NOT deprecated — it remains the canonical interface
// for accessibility-based device actions used by [com.ufo.galaxy.loop.ExecutorBridge].
// ────────────────────────────────────────────────────────────────────────────

/**
 * **LEGACY** — only used by [EdgeOrchestrator], which is deprecated.
 *
 * Context passed to the legacy bitmap-based [LocalPlanner] interface for every
 * planning / replanning call.
 *
 * Canonical replacement: [com.ufo.galaxy.inference.LocalPlannerService] accepts
 * goal, constraints, and an optional Base64 screenshot string directly without
 * a wrapper context object.
 */
@Deprecated(
    message = "Only used by the deprecated EdgeOrchestrator. " +
        "Use LocalPlannerService for task planning in the canonical pipeline.",
    level = DeprecationLevel.WARNING
)
data class PlannerContext(
    val task_id: String,
    val constraints: List<String>,
    val max_steps: Int,
    val history: List<String> = emptyList()
)

/**
 * **LEGACY** — only used by [AgentRuntime], which is deprecated.
 *
 * Result produced by a single action step executed through the legacy [AgentRuntime]
 * interface.
 *
 * Canonical replacement: [com.ufo.galaxy.loop.ActionStep] (with [com.ufo.galaxy.loop.StepStatus])
 * is the structured step result used by [com.ufo.galaxy.loop.LoopController] and
 * [com.ufo.galaxy.loop.ExecutorBridge] in the canonical pipeline.
 */
@Deprecated(
    message = "Only used by the deprecated AgentRuntime interface. " +
        "Use loop.ActionStep with StepStatus in the canonical pipeline.",
    level = DeprecationLevel.WARNING
)
data class StepResult(
    val success: Boolean,
    val action_taken: String,
    val error: String? = null,
    val snapshot: String? = null
)

/**
 * **LEGACY** — only referenced by [EdgeOrchestrator], which is deprecated.
 *
 * Bitmap-based screenshot capture interface. Not part of the canonical pipeline.
 *
 * Canonical replacement: [com.ufo.galaxy.service.AccessibilityScreenshotProvider]
 * implements [com.ufo.galaxy.agent.EdgeExecutor.ScreenshotProvider] and returns
 * JPEG-encoded byte arrays consumed directly by [com.ufo.galaxy.loop.LoopController].
 */
@Deprecated(
    message = "Not part of the canonical pipeline. " +
        "Use AccessibilityScreenshotProvider (implements EdgeExecutor.ScreenshotProvider) instead.",
    replaceWith = ReplaceWith(
        "AccessibilityScreenshotProvider()",
        "com.ufo.galaxy.service.AccessibilityScreenshotProvider"
    ),
    level = DeprecationLevel.WARNING
)
interface ScreenshotCapture {
    /**
     * Returns a [Bitmap] of the current screen.
     * @throws SecurityException if screen-capture permission is absent.
     * @throws IllegalStateException if the capture service is unavailable.
     */
    fun capture(): Bitmap
}

/**
 * **LEGACY** — bitmap-based planning interface only used by [EdgeOrchestrator], which is
 * deprecated.  Not part of the canonical Android runtime pipeline.
 *
 * Canonical replacement: [com.ufo.galaxy.inference.LocalPlannerService] — accepts
 * Base64-encoded screenshots, is JVM-testable, and is used by
 * [com.ufo.galaxy.loop.LoopController] via [com.ufo.galaxy.loop.LocalPlanner].
 *
 * Note: this interface shares the short name `LocalPlanner` with the
 * [com.ufo.galaxy.loop.LocalPlanner] **class** (which wraps [LocalPlannerService]
 * for the canonical loop pipeline). They are distinct — this interface belongs to
 * the legacy agent package; the canonical class is in the loop package.
 */
@Deprecated(
    message = "Bitmap-based planning interface used only by the deprecated EdgeOrchestrator. " +
        "Use LocalPlannerService (inference package) via loop.LocalPlanner in the canonical pipeline.",
    level = DeprecationLevel.WARNING
)
interface LocalPlanner {

    /**
     * Represents a single planned action.
     *
     * @param action_type Symbolic action name: "tap" | "scroll" | "type" |
     *                    "open_app" | "back" | "home".
     * @param intent      Natural-language intent string forwarded to [GUIGrounding].
     * @param parameters  Optional action-specific key-value pairs
     *                    (e.g. "text" for type, "package" for open_app,
     *                    "direction" for scroll).
     */
    data class PlanStep(
        val action_type: String,
        val intent: String,
        val parameters: Map<String, String> = emptyMap()
    )

    /**
     * Produces an initial ordered list of [PlanStep]s for [goal].
     * @throws OutOfMemoryError if the on-device model cannot be loaded.
     */
    fun plan(goal: String, context: PlannerContext): List<PlanStep>

    /**
     * Produces a revised plan after [failedStep] raised [error].
     * @throws OutOfMemoryError if the on-device model cannot be loaded.
     */
    fun replan(
        goal: String,
        context: PlannerContext,
        failedStep: PlanStep,
        error: String
    ): List<PlanStep>
}

/**
 * **LEGACY** — bitmap-based grounding interface only used by [EdgeOrchestrator], which is
 * deprecated.  Not part of the canonical Android runtime pipeline.
 *
 * Canonical replacement: [com.ufo.galaxy.inference.LocalGroundingService] — accepts
 * Base64-encoded screenshots, is JVM-testable, and is used by
 * [com.ufo.galaxy.loop.ExecutorBridge] via [com.ufo.galaxy.local.GroundingFallbackLadder].
 */
@Deprecated(
    message = "Bitmap-based grounding interface used only by the deprecated EdgeOrchestrator. " +
        "Use LocalGroundingService (inference package) via loop.ExecutorBridge in the canonical pipeline.",
    level = DeprecationLevel.WARNING
)
interface GUIGrounding {

    /**
     * Resolved screen location for an intent.
     *
     * @param x                   Horizontal pixel coordinate within the screenshot.
     * @param y                   Vertical pixel coordinate within the screenshot.
     * @param confidence          Model confidence score in the range [0.0, 1.0].
     * @param element_description Human-readable label of the matched UI element.
     */
    data class GroundingResult(
        val x: Int,
        val y: Int,
        val confidence: Float,
        val element_description: String
    )

    /**
     * Grounds [intent] against [screenshot] and returns the target coordinates.
     * @throws OutOfMemoryError if the on-device vision model cannot be loaded.
     */
    fun ground(intent: String, screenshot: Bitmap): GroundingResult
}

/**
 * Accessibility action executor — the canonical interface for accessibility-based
 * device actions.
 *
 * This interface is **not deprecated** and remains in active use by
 * [com.ufo.galaxy.loop.ExecutorBridge] (canonical execution path) and
 * [com.ufo.galaxy.service.AccessibilityActionExecutor] (canonical implementation).
 * All physical interactions with the device UI are routed through this interface.
 */
interface AccessibilityExecutor {

    /** Sealed hierarchy of all supported device actions. */
    sealed class AccessibilityAction {
        /** Tap the pixel at ([x], [y]). */
        data class Tap(val x: Int, val y: Int) : AccessibilityAction()

        /** Scroll from ([x], [y]) in [direction] ("up" | "down" | "left" | "right"). */
        data class Scroll(val x: Int, val y: Int, val direction: String) : AccessibilityAction()

        /** Type [text] into the focused input field. */
        data class TypeText(val text: String) : AccessibilityAction()

        /** Launch the application identified by [packageName]. */
        data class OpenApp(val packageName: String) : AccessibilityAction()

        /** Press the system Back button. */
        object Back : AccessibilityAction()

        /** Press the system Home button. */
        object Home : AccessibilityAction()
    }

    /**
     * Executes [action] via the Android AccessibilityService.
     * @return `true` if the action was dispatched successfully.
     */
    fun execute(action: AccessibilityAction): Boolean
}

/**
 * **LEGACY** — not implemented by any active class; only referenced in [EdgeOrchestrator],
 * which is deprecated.  Not part of the canonical Android runtime pipeline.
 *
 * Canonical replacement: [com.ufo.galaxy.agent.EdgeExecutor] handles the full
 * `task_assign` lifecycle (screenshot → plan → ground → execute) and is the sole
 * canonical task executor wired through [com.ufo.galaxy.service.GalaxyConnectionService]
 * → [com.ufo.galaxy.agent.LocalGoalExecutor] → [com.ufo.galaxy.agent.AutonomousExecutionPipeline].
 */
@Deprecated(
    message = "Not implemented by any active class. " +
        "Use EdgeExecutor (via LocalGoalExecutor / AutonomousExecutionPipeline) for the canonical pipeline.",
    replaceWith = ReplaceWith(
        "EdgeExecutor",
        "com.ufo.galaxy.agent.EdgeExecutor"
    ),
    level = DeprecationLevel.WARNING
)
interface AgentRuntime {
    /**
     * Executes [step] using [screenshot] as the current UI state.
     * Returns a [StepResult] describing the outcome.
     */
    // @Suppress: both parameter type LocalPlanner.PlanStep and return type StepResult
    // are deprecated (legacy agent-package types used only by EdgeOrchestrator).
    // The suppression keeps this deprecated interface self-consistent without
    // cascading warnings onto each implementation site.
    @Suppress("DEPRECATION")
    fun executeStep(step: LocalPlanner.PlanStep, screenshot: Bitmap): StepResult
}
