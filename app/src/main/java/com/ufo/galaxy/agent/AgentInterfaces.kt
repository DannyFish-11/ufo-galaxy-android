package com.ufo.galaxy.agent

import android.graphics.Bitmap

/**
 * Context passed to the local planner for every planning / replanning call.
 *
 * @param task_id     Task identifier echoed from [TaskAssignPayload].
 * @param constraints Natural-language constraint strings from the gateway.
 * @param max_steps   Step budget remaining.
 * @param history     Ordered log of previously executed steps for replanning context.
 */
data class PlannerContext(
    val task_id: String,
    val constraints: List<String>,
    val max_steps: Int,
    val history: List<String> = emptyList()
)

/**
 * Result produced by a single action step executed through [AgentRuntime].
 *
 * @param success       Whether the action completed without error.
 * @param action_taken  Human-readable description of the action that ran.
 * @param error         Error description when [success] is false.
 * @param snapshot      Optional Base64-encoded JPEG screenshot captured after execution.
 */
data class StepResult(
    val success: Boolean,
    val action_taken: String,
    val error: String? = null,
    val snapshot: String? = null
)

/**
 * Captures the current device screen.
 * Implementations must handle permission requirements and surface any
 * hardware or permission errors as exceptions.
 */
interface ScreenshotCapture {
    /**
     * Returns a [Bitmap] of the current screen.
     * @throws SecurityException if screen-capture permission is absent.
     * @throws IllegalStateException if the capture service is unavailable.
     */
    fun capture(): Bitmap
}

/**
 * Local task planner abstraction (Qwen2.5-7B on-device).
 * Produces ordered action steps from a high-level goal; replans when a step fails.
 */
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
 * GUI grounding abstraction (GUI-Owl-7B on-device).
 * Maps a natural-language intent and a live screenshot to physical screen coordinates.
 * Coordinates are produced exclusively on-device; the gateway never supplies x/y values.
 */
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
 * Accessibility action executor (Mobile-Agent → AccessibilityService).
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
 * High-level runtime that combines [LocalPlanner], [GUIGrounding], and
 * [AccessibilityExecutor] to execute a single planned step end-to-end.
 */
interface AgentRuntime {
    /**
     * Executes [step] using [screenshot] as the current UI state.
     * Returns a [StepResult] describing the outcome.
     */
    fun executeStep(step: LocalPlanner.PlanStep, screenshot: Bitmap): StepResult
}
