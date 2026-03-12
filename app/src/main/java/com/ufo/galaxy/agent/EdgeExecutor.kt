package com.ufo.galaxy.agent

import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.CommandResultPayload
import com.ufo.galaxy.protocol.Snapshot
import com.ufo.galaxy.protocol.StepResult
import com.ufo.galaxy.protocol.TaskAssignPayload
import com.ufo.galaxy.protocol.TaskResultPayload
import java.util.Base64

/**
 * EdgeExecutor orchestrates the full on-device AIP v3 task execution pipeline:
 *   screenshot → MobileVLM 1.7B planner → SeeClick grounding → AccessibilityService
 *
 * Uses [LocalPlannerService] and [LocalGroundingService] as pluggable inference
 * backends (llama.cpp/MLC-LLM for planning; NCNN/MNN for grounding).
 *
 * Architecture constraints:
 *  - All screen coordinates are generated on-device in the grounding stage.
 *    The gateway never supplies x/y values.
 *  - [require_local_agent] == false immediately returns CANCELLED.
 *  - Model readiness is checked before execution; a structured ERROR is returned
 *    when [plannerService] or [groundingService] is not loaded (model gating).
 *  - [max_steps] from [TaskAssignPayload] caps the step budget at all times,
 *    including after replanning.
 *  - Screenshots are optionally downscaled by [imageScaler] before passing to the
 *    grounding engine; returned coordinates are remapped to full-resolution before
 *    dispatching to [AccessibilityExecutor]. Snapshots always use full-resolution.
 *  - All exceptional conditions are mapped to ERROR results; this method never throws.
 *  - [correlation_id] in [TaskResultPayload] is always set to [TaskAssignPayload.task_id].
 *
 * @param screenshotProvider    Captures the current device screen as JPEG bytes.
 * @param plannerService        MobileVLM 1.7B local planner (llama.cpp / MLC-LLM).
 * @param groundingService      SeeClick local grounding engine (NCNN / MNN).
 * @param accessibilityExecutor Executes resolved actions via Android AccessibilityService.
 * @param imageScaler           Optional scaler for grounding input; defaults to [NoOpImageScaler].
 * @param scaledMaxEdge         Maximum longest edge (px) for grounding input image.
 *                              0 = disabled (full-resolution passed to grounding engine).
 */
class EdgeExecutor(
    private val screenshotProvider: ScreenshotProvider,
    private val plannerService: LocalPlannerService,
    private val groundingService: LocalGroundingService,
    private val accessibilityExecutor: AccessibilityExecutor,
    private val imageScaler: ImageScaler = NoOpImageScaler(),
    private val scaledMaxEdge: Int = 720
) {

    /**
     * Captures the current device screen as raw JPEG bytes.
     * Returning bytes (not Bitmap) keeps this interface JVM-testable.
     *
     * @throws SecurityException     if screen-capture permission is absent.
     * @throws IllegalStateException if the capture service is unavailable.
     */
    interface ScreenshotProvider {
        /** Returns JPEG-encoded bytes of the current device screen. */
        fun captureJpeg(): ByteArray

        /** Screen width in pixels; 0 if unknown. */
        fun screenWidth(): Int = 0

        /** Screen height in pixels; 0 if unknown. */
        fun screenHeight(): Int = 0
    }

    companion object {
        const val STATUS_SUCCESS = "success"
        const val STATUS_ERROR = "error"
        const val STATUS_CANCELLED = "cancelled"
        /** Returned when the task exceeded its configured [GoalExecutionPayload.effectiveTimeoutMs]. */
        const val STATUS_TIMEOUT = "timeout"
    }

    /**
     * Handles a [TaskAssignPayload] by executing the full local pipeline and
     * returning a [TaskResultPayload]. Never throws; all errors map to ERROR status.
     *
     * Model readiness is checked before any work is started: if [plannerService] or
     * [groundingService] reports [isModelLoaded] == false, an ERROR result is returned
     * immediately with a clear explanation so the caller can surface the issue.
     *
     * [TaskResultPayload.correlation_id] is always set to [taskAssign.task_id].
     */
    fun handleTaskAssign(taskAssign: TaskAssignPayload): TaskResultPayload {
        if (!taskAssign.require_local_agent) {
            return buildResult(
                taskId = taskAssign.task_id,
                status = STATUS_CANCELLED,
                error = "require_local_agent is false"
            )
        }

        // Model readiness gating: fail fast with a structured error if models are not loaded.
        if (!plannerService.isModelLoaded()) {
            return buildResult(
                taskId = taskAssign.task_id,
                status = STATUS_ERROR,
                error = "Model not ready: MobileVLM planner is not loaded. " +
                    "Ensure the inference server is running and loadModel() succeeded."
            )
        }
        if (!groundingService.isModelLoaded()) {
            return buildResult(
                taskId = taskAssign.task_id,
                status = STATUS_ERROR,
                error = "Model not ready: SeeClick grounding engine is not loaded. " +
                    "Ensure the inference server is running and loadModel() succeeded."
            )
        }

        val accumulatedSteps = mutableListOf<StepResult>()

        GalaxyLogger.log(GalaxyLogger.TAG_TASK_EXEC, mapOf("task_id" to taskAssign.task_id, "max_steps" to taskAssign.max_steps))

        // Capture initial screenshot for planning context.
        // Full-resolution bytes are used for planning (and stored as snapshot);
        // the grounding step will downscale separately per step.
        val initialCapture = captureScreenshot()
            ?: return buildResult(
                taskId = taskAssign.task_id,
                status = STATUS_ERROR,
                error = "Screenshot capture failed before planning"
            )
        val (initialFullBytes, initW, initH) = initialCapture
        val initialBase64 = Base64.getEncoder().encodeToString(initialFullBytes)

        // Initial planning uses the full-resolution screenshot for best context.
        val planResult = plannerService.plan(
            goal = taskAssign.goal,
            constraints = taskAssign.constraints,
            screenshotBase64 = initialBase64
        )
        if (planResult.error != null || planResult.steps.isEmpty()) {
            return buildResult(
                taskId = taskAssign.task_id,
                status = STATUS_ERROR,
                error = "Planning failed: ${planResult.error ?: "no steps produced"}",
                steps = accumulatedSteps,
                snapshot = makeSnapshot(initialBase64, initW, initH)
            )
        }

        var planSteps = planResult.steps
        var stepIndex = 0
        var stepsConsumed = 0
        var lastSnapshotBase64: String? = initialBase64
        var lastW = initW
        var lastH = initH

        // stepsConsumed tracks total steps across the initial plan and any replans.
        // max_steps caps the total budget regardless of how many times replanning occurs.

        while (stepIndex < planSteps.size && stepsConsumed < taskAssign.max_steps) {
            val step = planSteps[stepIndex]
            val stepId = (stepsConsumed + 1).toString()

            // Capture full-resolution screenshot for this step.
            // Snapshot always stores the full-resolution image.
            val stepCapture = captureScreenshot()
            if (stepCapture == null) {
                accumulatedSteps.add(
                    StepResult(step_id = stepId, action = step.action_type, success = false,
                        error = "Screenshot capture failed")
                )
                return buildResult(
                    taskId = taskAssign.task_id,
                    status = STATUS_ERROR,
                    error = "Screenshot capture failed at step $stepId",
                    steps = accumulatedSteps,
                    snapshot = makeSnapshot(lastSnapshotBase64, lastW, lastH)
                )
            }
            val (fullJpegBytes, screenW, screenH) = stepCapture
            val fullBase64 = Base64.getEncoder().encodeToString(fullJpegBytes)
            lastSnapshotBase64 = fullBase64
            lastW = screenW
            lastH = screenH

            // Downscale the screenshot for the grounding engine to reduce latency / memory.
            // Coordinates returned by the grounding engine are in the scaled coordinate space
            // and must be remapped to full-resolution before dispatch to AccessibilityService.
            val scaledForGrounding = imageScaler.scaleToMaxEdge(
                jpegBytes = fullJpegBytes,
                fullWidth = screenW,
                fullHeight = screenH,
                maxEdge = scaledMaxEdge
            )

            // Grounding: intent + (scaled) screenshot → on-device coordinates in scaled space
            val grounding = groundingService.ground(
                intent = step.intent,
                screenshotBase64 = scaledForGrounding.scaledJpegBase64,
                width = scaledForGrounding.scaledWidth,
                height = scaledForGrounding.scaledHeight
            )
            if (grounding.error != null) {
                accumulatedSteps.add(
                    StepResult(step_id = stepId, action = step.action_type, success = false,
                        error = grounding.error,
                        snapshot = makeSnapshot(fullBase64, screenW, screenH))
                )
                return buildResult(
                    taskId = taskAssign.task_id,
                    status = STATUS_ERROR,
                    error = "Grounding failed at step $stepId: ${grounding.error}",
                    steps = accumulatedSteps,
                    snapshot = makeSnapshot(fullBase64, screenW, screenH)
                )
            }

            // Remap grounding coordinates from scaled space back to full-resolution.
            // This ensures AccessibilityService receives pixel-accurate coordinates
            // regardless of what resolution was passed to the grounding engine.
            val fullResX = remapCoord(grounding.x, scaledForGrounding.scaledWidth, screenW)
            val fullResY = remapCoord(grounding.y, scaledForGrounding.scaledHeight, screenH)
            val remappedGrounding = grounding.copy(x = fullResX, y = fullResY)

            // Execute accessibility action with full-resolution coordinates
            val actionSuccess = try {
                val action = resolveAction(step, remappedGrounding)
                accessibilityExecutor.execute(action)
            } catch (e: Exception) {
                accumulatedSteps.add(
                    StepResult(step_id = stepId, action = step.action_type, success = false,
                        error = "Execution error: ${e.message}",
                        snapshot = makeSnapshot(fullBase64, screenW, screenH))
                )
                return buildResult(
                    taskId = taskAssign.task_id,
                    status = STATUS_ERROR,
                    error = "Execution failed at step $stepId: ${e.message}",
                    steps = accumulatedSteps,
                    snapshot = makeSnapshot(fullBase64, screenW, screenH)
                )
            }

            accumulatedSteps.add(
                StepResult(
                    step_id = stepId,
                    action = step.action_type,
                    success = actionSuccess,
                    snapshot = makeSnapshot(fullBase64, screenW, screenH)
                )
            )
            stepsConsumed++

            if (!actionSuccess) {
                // Replan on action failure; use full-resolution screenshot for context.
                val replanResult = plannerService.replan(
                    goal = taskAssign.goal,
                    constraints = taskAssign.constraints,
                    failedStep = step,
                    error = "action returned false",
                    screenshotBase64 = fullBase64
                )
                if (replanResult.error != null || replanResult.steps.isEmpty()) {
                    return buildResult(
                        taskId = taskAssign.task_id,
                        status = STATUS_ERROR,
                        error = "Replan failed at step $stepId: ${replanResult.error ?: "no steps produced"}",
                        steps = accumulatedSteps,
                        snapshot = makeSnapshot(fullBase64, screenW, screenH)
                    )
                }
                planSteps = replanResult.steps
                stepIndex = 0
                continue
            }

            stepIndex++
        }

        return buildResult(
            taskId = taskAssign.task_id,
            status = STATUS_SUCCESS,
            steps = accumulatedSteps,
            snapshot = makeSnapshot(lastSnapshotBase64, lastW, lastH)
        )
    }

    /**
     * Builds a [CommandResultPayload] for a single completed or failed step.
     * [correlation_id] is set to [taskId] per AIP v3 protocol.
     */
    fun buildCommandResult(
        taskId: String,
        stepId: String,
        action: String,
        success: Boolean,
        error: String? = null,
        snapshotBase64: String? = null,
        screenWidth: Int = 0,
        screenHeight: Int = 0
    ): CommandResultPayload = CommandResultPayload(
        task_id = taskId,
        step_id = stepId,
        action = action,
        status = if (success) STATUS_SUCCESS else STATUS_ERROR,
        error = error,
        snapshot = makeSnapshot(snapshotBase64, screenWidth, screenHeight)
    )

    private fun captureScreenshot(): Triple<ByteArray, Int, Int>? {
        return try {
            val jpegBytes = screenshotProvider.captureJpeg()
            Triple(jpegBytes, screenshotProvider.screenWidth(), screenshotProvider.screenHeight())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Remaps a coordinate from scaled-image space to full-resolution space.
     * Returns [coordInScaled] unchanged if [scaledDim] is zero (no valid scale available).
     */
    private fun remapCoord(coordInScaled: Int, scaledDim: Int, fullDim: Int): Int {
        if (scaledDim <= 0 || fullDim <= 0) return coordInScaled
        return Math.round(coordInScaled.toFloat() * fullDim.toFloat() / scaledDim.toFloat())
    }

    private fun resolveAction(
        step: LocalPlannerService.PlanStep,
        grounding: LocalGroundingService.GroundingResult
    ): AccessibilityExecutor.AccessibilityAction = when (step.action_type) {
        "tap" -> AccessibilityExecutor.AccessibilityAction.Tap(grounding.x, grounding.y)
        "scroll" -> AccessibilityExecutor.AccessibilityAction.Scroll(
            grounding.x, grounding.y,
            step.parameters.getOrDefault("direction", "down")
        )
        "type" -> AccessibilityExecutor.AccessibilityAction.TypeText(
            step.parameters.getOrDefault("text", "")
        )
        "open_app" -> AccessibilityExecutor.AccessibilityAction.OpenApp(
            step.parameters.getOrDefault("package", "")
        )
        "back" -> AccessibilityExecutor.AccessibilityAction.Back
        "home" -> AccessibilityExecutor.AccessibilityAction.Home
        else -> AccessibilityExecutor.AccessibilityAction.Tap(grounding.x, grounding.y)
    }

    private fun makeSnapshot(base64: String?, width: Int, height: Int): Snapshot? {
        if (base64 == null) return null
        return Snapshot(data = base64, width = width, height = height)
    }

    private fun buildResult(
        taskId: String,
        status: String,
        error: String? = null,
        steps: List<StepResult> = emptyList(),
        snapshot: Snapshot? = null
    ): TaskResultPayload {
        GalaxyLogger.log(GalaxyLogger.TAG_TASK_RETURN, mapOf(
            "task_id" to taskId,
            "status" to status,
            "steps" to steps.size,
            "error" to error
        ))
        return TaskResultPayload(
            task_id = taskId,
            correlation_id = taskId,
            status = status,
            steps = steps,
            error = error,
            snapshot = snapshot
        )
    }
}
