package com.ufo.galaxy.loop

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.ImageScaler
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.observability.GalaxyLogger

/**
 * Maps [ActionStep]s to concrete [AccessibilityExecutor] actions and dispatches them.
 *
 * For actions that require screen-coordinate grounding (tap, scroll),
 * [groundingService] resolves intent → (x, y) before dispatching.
 * Actions that do not need coordinates (type, back, home, open_app) are dispatched
 * directly without a grounding call. When [groundingService] is not loaded, all
 * actions fall back to direct dispatch with zero coordinates.
 *
 * @param groundingService      SeeClick NCNN grounding engine.
 * @param accessibilityExecutor Dispatches device actions via AccessibilityService.
 * @param imageScaler           Optional downscaler for grounding input;
 *                              use [NoOpImageScaler] in JVM tests.
 * @param scaledMaxEdge         Max longest edge (px) for grounding input; 0 = full resolution.
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
         * All others (tap, scroll) go through [groundingService].
         */
        private val NO_GROUNDING_ACTIONS = setOf("type", "back", "home", "open_app")
    }

    /**
     * Executes [step] against the device and returns an updated [ActionStep] with the result.
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
            val (action, confidence) = resolveAction(step, jpegBytes, screenWidth, screenHeight)
            val success = accessibilityExecutor.execute(action)
            val status = if (success) StepStatus.SUCCESS else StepStatus.FAILED
            val failureReason = if (!success) "AccessibilityExecutor returned false" else null

            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "execute_result",
                    "step_id" to step.id,
                    "action_type" to step.actionType,
                    "success" to success,
                    "confidence" to confidence
                )
            )

            step.copy(status = status, confidence = confidence, failureReason = failureReason)
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
                failureReason = e.message ?: "Execution exception"
            )
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun resolveAction(
        step: ActionStep,
        jpegBytes: ByteArray,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<AccessibilityExecutor.AccessibilityAction, Float> {

        // Actions that never need grounding are dispatched directly.
        if (step.actionType in NO_GROUNDING_ACTIONS || !groundingService.isModelLoaded()) {
            return Pair(buildDirectAction(step), 1f)
        }

        // Downscale screenshot for grounding efficiency.
        val scaled = imageScaler.scaleToMaxEdge(
            jpegBytes = jpegBytes,
            fullWidth = screenWidth,
            fullHeight = screenHeight,
            maxEdge = scaledMaxEdge
        )

        val grounding = groundingService.ground(
            intent = step.intent,
            screenshotBase64 = scaled.scaledJpegBase64,
            width = scaled.scaledWidth,
            height = scaled.scaledHeight
        )

        if (grounding.error != null) {
            throw IllegalStateException("Grounding failed: ${grounding.error}")
        }

        // Remap coordinates from scaled space back to full resolution.
        val fullX = remapCoord(grounding.x, scaled.scaledWidth, screenWidth)
        val fullY = remapCoord(grounding.y, scaled.scaledHeight, screenHeight)

        val action: AccessibilityExecutor.AccessibilityAction = when (step.actionType) {
            "tap" -> AccessibilityExecutor.AccessibilityAction.Tap(fullX, fullY)
            "scroll" -> AccessibilityExecutor.AccessibilityAction.Scroll(
                fullX, fullY,
                step.parameters.getOrDefault("direction", "down")
            )
            else -> AccessibilityExecutor.AccessibilityAction.Tap(fullX, fullY)
        }

        return Pair(action, grounding.confidence)
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

    private fun remapCoord(coordInScaled: Int, scaledDim: Int, fullDim: Int): Int {
        if (scaledDim <= 0 || fullDim <= 0) return coordInScaled
        return Math.round(coordInScaled.toFloat() * fullDim.toFloat() / scaledDim.toFloat())
    }
}
