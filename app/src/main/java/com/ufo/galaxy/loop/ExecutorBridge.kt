package com.ufo.galaxy.loop

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.ImageScaler
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.local.FailureCode
import com.ufo.galaxy.local.GroundingFallbackLadder
import com.ufo.galaxy.observability.GalaxyLogger

/**
 * Maps [ActionStep]s to concrete [AccessibilityExecutor] actions and dispatches them.
 *
 * For actions that require screen-coordinate grounding (tap, scroll), a
 * [GroundingFallbackLadder] resolves intent → (x, y) through up to six stages before
 * returning a structured failure. Actions that do not need coordinates (type, back,
 * home, open_app) are dispatched directly without a grounding call.
 *
 * @param groundingService      SeeClick NCNN grounding engine.
 * @param accessibilityExecutor Dispatches device actions via AccessibilityService.
 * @param imageScaler           Optional downscaler for grounding input;
 *                              use [NoOpImageScaler] in JVM tests.
 * @param scaledMaxEdge         Max longest edge (px) for primary grounding input; 0 = full resolution.
 */
class ExecutorBridge(
    private val groundingService: LocalGroundingService,
    private val accessibilityExecutor: AccessibilityExecutor,
    private val imageScaler: ImageScaler = NoOpImageScaler(),
    private val scaledMaxEdge: Int = 720
) {

    companion object {
        internal const val TAG = "GALAXY:LOOP:EXECUTOR"

        /**
         * Action types that bypass coordinate grounding and are dispatched directly.
         * All others (tap, scroll) go through [GroundingFallbackLadder].
         */
        private val NO_GROUNDING_ACTIONS = setOf("type", "back", "home", "open_app")
    }

    private val groundingLadder = GroundingFallbackLadder(
        groundingService = groundingService,
        imageScaler = imageScaler,
        primaryMaxEdge = if (scaledMaxEdge > 0) scaledMaxEdge else GroundingFallbackLadder.DEFAULT_PRIMARY_MAX_EDGE,
        resizedMaxEdge = if (scaledMaxEdge > 0) scaledMaxEdge / 2 else GroundingFallbackLadder.DEFAULT_RESIZED_MAX_EDGE
    )

    /**
     * Executes [step] against the device and returns an updated [ActionStep] with the result.
     *
     * Grounding now uses [GroundingFallbackLadder] so transient SeeClick failures are
     * retried at a smaller resolution before falling back to accessibility-node or heuristic
     * region coordinates, rather than immediately failing the step.
     *
     * @param step         Action to execute.
     * @param jpegBytes    Full-resolution JPEG bytes of the current screen (used for grounding).
     * @param screenWidth  Screen width in pixels; 0 when unknown.
     * @param screenHeight Screen height in pixels; 0 when unknown.
     * @return Updated [ActionStep] with [StepStatus.SUCCESS] or [StepStatus.FAILED].
     */
    fun execute(
        step: ActionStep,
        jpegBytes: ByteArray,
        screenWidth: Int,
        screenHeight: Int
    ): ActionStep {
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "execute",
                "step_id" to step.id,
                "action_type" to step.actionType,
                "intent" to step.intent.take(80)
            )
        )

        return try {
            val (action, confidence, groundingStage) = resolveAction(
                step, jpegBytes, screenWidth, screenHeight
            )
            val success = accessibilityExecutor.execute(action)
            val status = if (success) StepStatus.SUCCESS else StepStatus.FAILED
            val failureCode = if (!success) FailureCode.EXEC_ACCESSIBILITY_RETURNED_FALSE else null
            val failureReason = if (!success) failureCode!!.description else null

            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "execute_result",
                    "step_id" to step.id,
                    "action_type" to step.actionType,
                    "success" to success,
                    "confidence" to confidence,
                    "grounding_stage" to groundingStage
                )
            )

            step.copy(
                status = status,
                confidence = confidence,
                failureReason = failureReason,
                failureCode = failureCode
            )
        } catch (e: Exception) {
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "execute_error",
                    "step_id" to step.id,
                    "error" to (e.message ?: "unknown")
                )
            )
            step.copy(
                status = StepStatus.FAILED,
                failureReason = e.message ?: "Execution exception",
                failureCode = FailureCode.EXEC_EXCEPTION
            )
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Returns a Triple of (action, confidence, groundingStage).
     * groundingStage is the name of the grounding ladder stage used (for logging).
     */
    private fun resolveAction(
        step: ActionStep,
        jpegBytes: ByteArray,
        screenWidth: Int,
        screenHeight: Int
    ): Triple<AccessibilityExecutor.AccessibilityAction, Float, String> {

        // Actions that never need grounding are dispatched directly.
        if (step.actionType in NO_GROUNDING_ACTIONS) {
            return Triple(buildDirectAction(step), 1f, "direct")
        }

        // Use the grounding fallback ladder for coordinate-based actions.
        val grounding = groundingLadder.ground(
            sessionId = "",
            stepId = step.id,
            intent = step.intent,
            jpegBytes = jpegBytes,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        if (!grounding.succeeded) {
            throw IllegalStateException(
                grounding.error ?: FailureCode.GROUND_ALL_STAGES_EXHAUSTED.description
            )
        }

        val action: AccessibilityExecutor.AccessibilityAction = when (step.actionType) {
            "tap" -> AccessibilityExecutor.AccessibilityAction.Tap(grounding.x, grounding.y)
            "scroll" -> AccessibilityExecutor.AccessibilityAction.Scroll(
                grounding.x, grounding.y,
                step.parameters.getOrDefault("direction", "down")
            )
            else -> AccessibilityExecutor.AccessibilityAction.Tap(grounding.x, grounding.y)
        }

        return Triple(action, grounding.confidence, grounding.stageUsed)
    }

    private fun buildDirectAction(
        step: ActionStep
    ): AccessibilityExecutor.AccessibilityAction = when (step.actionType) {
        "type" -> AccessibilityExecutor.AccessibilityAction.TypeText(
            step.parameters.getOrDefault("text", step.intent)
        )
        "back" -> AccessibilityExecutor.AccessibilityAction.Back
        "home" -> AccessibilityExecutor.AccessibilityAction.Home
        "open_app" -> AccessibilityExecutor.AccessibilityAction.OpenApp(
            step.parameters.getOrDefault("package", "")
        )
        "scroll" -> AccessibilityExecutor.AccessibilityAction.Scroll(
            0, 0,
            step.parameters.getOrDefault("direction", "down")
        )
        else -> AccessibilityExecutor.AccessibilityAction.Tap(0, 0)
    }
}
