package com.ufo.galaxy.agent

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.ufo.galaxy.protocol.TaskAssignPayload
import com.ufo.galaxy.protocol.TaskExecutionStatus
import com.ufo.galaxy.protocol.TaskResultPayload
import java.io.ByteArrayOutputStream

/**
 * **LEGACY — not part of the canonical Android runtime pipeline.**
 *
 * This class uses the bitmap-based [LocalPlanner] / [GUIGrounding] / [ScreenshotCapture]
 * agent-package interfaces and is **not wired into any active execution path**.
 * It is retained only as a migration boundary; do not add new features here.
 *
 * Canonical replacement: [EdgeExecutor], which uses the injectable
 * [com.ufo.galaxy.inference.LocalPlannerService] and
 * [com.ufo.galaxy.inference.LocalGroundingService] interfaces (base64-based, JVM-testable)
 * and is the sole task execution engine wired through [com.ufo.galaxy.service.GalaxyConnectionService]
 * → [com.ufo.galaxy.agent.LocalGoalExecutor] → [com.ufo.galaxy.agent.AutonomousExecutionPipeline].
 *
 * Original doc:
 * Cloud-edge execution orchestrator for AIP v3.
 *
 * Receives a [TaskAssignPayload] (step [6]) and drives the full local pipeline:
 *   screenshot → Planner (Qwen2.5-7B) → GUIGrounding (GUI-Owl-7B) → AccessibilityExecutor
 *
 * Produces a [TaskResultPayload] (step [8]) including an optional Base64 JPEG snapshot
 * so the gateway can perform cloud-side correction in step [9] if required.
 */
@Deprecated(
    message = "Not part of the canonical Android runtime pipeline. " +
        "Use EdgeExecutor (via LocalGoalExecutor / AutonomousExecutionPipeline) instead.",
    level = DeprecationLevel.WARNING
)
class EdgeOrchestrator(
    private val screenshotCapture: ScreenshotCapture,
    private val localPlanner: LocalPlanner,
    private val guiGrounding: GUIGrounding,
    private val accessibilityExecutor: AccessibilityExecutor
) {

    companion object {
        private const val TAG = "EdgeOrchestrator"
        /** JPEG compression quality for snapshot payloads (0–100).
         *  70 balances visual fidelity for cloud-side grounding correction (step [9])
         *  against WebSocket payload size. */
        private const val SNAPSHOT_JPEG_QUALITY = 70
    }

    /**
     * Executes the task described by [taskAssign] and returns a [TaskResultPayload].
     *
     * The method never throws; all exceptional conditions are mapped to an ERROR result.
     */
    fun execute(taskAssign: TaskAssignPayload): TaskResultPayload {
        if (!taskAssign.require_local_agent) {
            Log.w(TAG, "[${taskAssign.task_id}] require_local_agent=false; skipping local execution")
            return buildResult(
                taskId = taskAssign.task_id,
                stepId = "0",
                status = TaskExecutionStatus.CANCELLED,
                error = "require_local_agent is false"
            )
        }

        Log.i(TAG, "[${taskAssign.task_id}] Starting local execution — goal: ${taskAssign.goal}")

        val plannerContext = PlannerContext(
            task_id = taskAssign.task_id,
            constraints = taskAssign.constraints,
            max_steps = taskAssign.max_steps
        )

        var planSteps: List<LocalPlanner.PlanStep>
        try {
            planSteps = localPlanner.plan(taskAssign.goal, plannerContext)
            Log.i(TAG, "[${taskAssign.task_id}] Initial plan: ${planSteps.size} step(s)")
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "[${taskAssign.task_id}] OOM in planner", oom)
            return buildResult(taskAssign.task_id, "0", TaskExecutionStatus.ERROR,
                "OOM during planning: ${oom.message}")
        } catch (e: Exception) {
            Log.e(TAG, "[${taskAssign.task_id}] Planner failed", e)
            return buildResult(taskAssign.task_id, "0", TaskExecutionStatus.ERROR,
                "Planning failed: ${e.message}")
        }

        var stepIndex = 0
        var stepsConsumed = 0
        var lastSnapshot: String? = null
        val history = mutableListOf<String>()

        while (stepIndex < planSteps.size && stepsConsumed < taskAssign.max_steps) {
            val step = planSteps[stepIndex]
            val stepId = (stepsConsumed + 1).toString()

            Log.i(TAG, "[${taskAssign.task_id}] Step $stepId — action=${step.action_type} intent=\"${step.intent}\"")

            // 1. Capture screenshot
            val screenshot: Bitmap
            try {
                screenshot = screenshotCapture.capture()
                Log.d(TAG, "[${taskAssign.task_id}] Screenshot captured for step $stepId")
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "[${taskAssign.task_id}] OOM capturing screenshot at step $stepId", oom)
                return buildResult(taskAssign.task_id, stepId, TaskExecutionStatus.ERROR,
                    "OOM during screenshot: ${oom.message}", lastSnapshot)
            } catch (e: Exception) {
                Log.e(TAG, "[${taskAssign.task_id}] Screenshot failed at step $stepId", e)
                return buildResult(taskAssign.task_id, stepId, TaskExecutionStatus.ERROR,
                    "Screenshot failed: ${e.message}", lastSnapshot)
            }

            // 2. GUI grounding — intent + screenshot → physical coordinates (local only)
            val groundingResult: GUIGrounding.GroundingResult
            try {
                groundingResult = guiGrounding.ground(step.intent, screenshot)
                Log.d(TAG, "[${taskAssign.task_id}] Grounding: x=${groundingResult.x} y=${groundingResult.y} conf=${groundingResult.confidence} elem=\"${groundingResult.element_description}\"")
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "[${taskAssign.task_id}] OOM in GUI grounding at step $stepId", oom)
                return buildResult(taskAssign.task_id, stepId, TaskExecutionStatus.ERROR,
                    "OOM during GUI grounding: ${oom.message}", lastSnapshot)
            } catch (e: Exception) {
                Log.e(TAG, "[${taskAssign.task_id}] GUI grounding failed at step $stepId", e)
                return buildResult(taskAssign.task_id, stepId, TaskExecutionStatus.ERROR,
                    "GUI grounding failed: ${e.message}", lastSnapshot)
            }

            // 3. Resolve to an AccessibilityAction and execute via AccessibilityService
            val actionSuccess: Boolean
            try {
                val action = resolveAction(step, groundingResult)
                actionSuccess = accessibilityExecutor.execute(action)
                Log.i(TAG, "[${taskAssign.task_id}] Step $stepId executed — success=$actionSuccess")
            } catch (e: Exception) {
                Log.e(TAG, "[${taskAssign.task_id}] Accessibility execution failed at step $stepId", e)
                return buildResult(taskAssign.task_id, stepId, TaskExecutionStatus.ERROR,
                    "Execution failed: ${e.message}", lastSnapshot)
            }

            lastSnapshot = encodeBitmap(screenshot)
            history.add("step=$stepId action=${step.action_type} success=$actionSuccess")
            stepsConsumed++

            if (!actionSuccess) {
                Log.w(TAG, "[${taskAssign.task_id}] Step $stepId failed — attempting replan (consumed $stepsConsumed/${taskAssign.max_steps})")
                val replanContext = plannerContext.copy(
                    max_steps = taskAssign.max_steps - stepsConsumed,
                    history = history.toList()
                )
                try {
                    val replannedSteps = localPlanner.replan(
                        taskAssign.goal, replanContext, step, "action returned false"
                    )
                    if (replannedSteps.isEmpty()) {
                        Log.e(TAG, "[${taskAssign.task_id}] Replan produced no steps — aborting")
                        return buildResult(taskAssign.task_id, stepId, TaskExecutionStatus.ERROR,
                            "Replan failed: no steps produced", lastSnapshot)
                    }
                    Log.i(TAG, "[${taskAssign.task_id}] Replan: ${replannedSteps.size} step(s)")
                    planSteps = replannedSteps
                    stepIndex = 0
                    continue
                } catch (oom: OutOfMemoryError) {
                    Log.e(TAG, "[${taskAssign.task_id}] OOM during replan at step $stepId", oom)
                    return buildResult(taskAssign.task_id, stepId, TaskExecutionStatus.ERROR,
                        "OOM during replan: ${oom.message}", lastSnapshot)
                } catch (e: Exception) {
                    Log.e(TAG, "[${taskAssign.task_id}] Replan failed at step $stepId", e)
                    return buildResult(taskAssign.task_id, stepId, TaskExecutionStatus.ERROR,
                        "Replan failed: ${e.message}", lastSnapshot)
                }
            }

            stepIndex++
        }

        Log.i(TAG, "[${taskAssign.task_id}] Task completed in $stepsConsumed step(s)")
        return buildResult(taskAssign.task_id, stepsConsumed.toString(), TaskExecutionStatus.SUCCESS,
            snapshot = lastSnapshot)
    }

    /**
     * Maps a [LocalPlanner.PlanStep] and its resolved [GUIGrounding.GroundingResult]
     * to a concrete [AccessibilityExecutor.AccessibilityAction].
     * The [groundingResult] coordinates are used for pointer-based actions;
     * non-pointer actions (type/open_app/back/home) derive their parameters from
     * [step.parameters] exclusively, keeping the gateway free of coordinate knowledge.
     */
    private fun resolveAction(
        step: LocalPlanner.PlanStep,
        groundingResult: GUIGrounding.GroundingResult
    ): AccessibilityExecutor.AccessibilityAction {
        return when (step.action_type) {
            "tap" -> AccessibilityExecutor.AccessibilityAction.Tap(groundingResult.x, groundingResult.y)
            "scroll" -> AccessibilityExecutor.AccessibilityAction.Scroll(
                groundingResult.x,
                groundingResult.y,
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
            else -> {
                Log.w(TAG, "[${step.action_type}] Unrecognised action_type; falling back to Tap at grounded coordinates")
                AccessibilityExecutor.AccessibilityAction.Tap(groundingResult.x, groundingResult.y)
            }
        }
    }

    /**
     * Encodes [bitmap] to a Base64 JPEG string (no-wrap, quality 70)
     * suitable for embedding in a [TaskResultPayload.snapshot] field.
     */
    private fun encodeBitmap(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, SNAPSHOT_JPEG_QUALITY, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun buildResult(
        taskId: String,
        stepId: String,
        status: TaskExecutionStatus,
        error: String? = null,
        snapshot: String? = null
    ) = TaskResultPayload(
        task_id = taskId,
        step_id = stepId,
        status = status,
        error = error,
        snapshot = snapshot
    )
}
