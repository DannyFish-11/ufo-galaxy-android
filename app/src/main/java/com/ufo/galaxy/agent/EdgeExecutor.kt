package com.ufo.galaxy.agent

import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
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
 *  - [max_steps] from [TaskAssignPayload] caps the step budget at all times,
 *    including after replanning.
 *  - All exceptional conditions are mapped to ERROR results; this method never throws.
 *  - [correlation_id] in [TaskResultPayload] is always set to [TaskAssignPayload.task_id].
 *
 * @param screenshotProvider    Captures the current device screen as JPEG bytes.
 * @param plannerService        MobileVLM 1.7B local planner (llama.cpp / MLC-LLM).
 * @param groundingService      SeeClick local grounding engine (NCNN / MNN).
 * @param accessibilityExecutor Executes resolved actions via Android AccessibilityService.
 */
class EdgeExecutor(
    private val screenshotProvider: ScreenshotProvider,
    private val plannerService: LocalPlannerService,
    private val groundingService: LocalGroundingService,
    private val accessibilityExecutor: AccessibilityExecutor
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
    }

    /**
     * Handles a [TaskAssignPayload] by executing the full local pipeline and
     * returning a [TaskResultPayload]. Never throws; all errors map to ERROR status.
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

        val accumulatedSteps = mutableListOf<StepResult>()

        // Capture initial screenshot for planning context
        val initial = captureScreenshot()
            ?: return buildResult(
                taskId = taskAssign.task_id,
                status = STATUS_ERROR,
                error = "Screenshot capture failed before planning"
            )
        val (initialBase64, initW, initH) = initial

        // Initial planning
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

            // Capture screenshot for this step
            val screenshotTriple = captureScreenshot()
            if (screenshotTriple == null) {
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
            val (screenshotBase64, screenW, screenH) = screenshotTriple
            lastSnapshotBase64 = screenshotBase64
            lastW = screenW
            lastH = screenH

            // Grounding: intent + screenshot → on-device coordinates
            val grounding = groundingService.ground(
                intent = step.intent,
                screenshotBase64 = screenshotBase64,
                width = screenW,
                height = screenH
            )
            if (grounding.error != null) {
                accumulatedSteps.add(
                    StepResult(step_id = stepId, action = step.action_type, success = false,
                        error = grounding.error,
                        snapshot = makeSnapshot(screenshotBase64, screenW, screenH))
                )
                return buildResult(
                    taskId = taskAssign.task_id,
                    status = STATUS_ERROR,
                    error = "Grounding failed at step $stepId: ${grounding.error}",
                    steps = accumulatedSteps,
                    snapshot = makeSnapshot(screenshotBase64, screenW, screenH)
                )
            }

            // Execute accessibility action
            val actionSuccess = try {
                val action = resolveAction(step, grounding)
                accessibilityExecutor.execute(action)
            } catch (e: Exception) {
                accumulatedSteps.add(
                    StepResult(step_id = stepId, action = step.action_type, success = false,
                        error = "Execution error: ${e.message}",
                        snapshot = makeSnapshot(screenshotBase64, screenW, screenH))
                )
                return buildResult(
                    taskId = taskAssign.task_id,
                    status = STATUS_ERROR,
                    error = "Execution failed at step $stepId: ${e.message}",
                    steps = accumulatedSteps,
                    snapshot = makeSnapshot(screenshotBase64, screenW, screenH)
                )
            }

            accumulatedSteps.add(
                StepResult(
                    step_id = stepId,
                    action = step.action_type,
                    success = actionSuccess,
                    snapshot = makeSnapshot(screenshotBase64, screenW, screenH)
                )
            )
            stepsConsumed++

            if (!actionSuccess) {
                // Replan on action failure
                val replanResult = plannerService.replan(
                    goal = taskAssign.goal,
                    constraints = taskAssign.constraints,
                    failedStep = step,
                    error = "action returned false",
                    screenshotBase64 = screenshotBase64
                )
                if (replanResult.error != null || replanResult.steps.isEmpty()) {
                    return buildResult(
                        taskId = taskAssign.task_id,
                        status = STATUS_ERROR,
                        error = "Replan failed at step $stepId: ${replanResult.error ?: "no steps produced"}",
                        steps = accumulatedSteps,
                        snapshot = makeSnapshot(screenshotBase64, screenW, screenH)
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

    private fun captureScreenshot(): Triple<String, Int, Int>? {
        return try {
            val jpegBytes = screenshotProvider.captureJpeg()
            val base64 = Base64.getEncoder().encodeToString(jpegBytes)
            Triple(base64, screenshotProvider.screenWidth(), screenshotProvider.screenHeight())
        } catch (e: Exception) {
            null
        }
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
    ) = TaskResultPayload(
        task_id = taskId,
        correlation_id = taskId,
        status = status,
        steps = steps,
        error = error,
        snapshot = snapshot
    )
}
