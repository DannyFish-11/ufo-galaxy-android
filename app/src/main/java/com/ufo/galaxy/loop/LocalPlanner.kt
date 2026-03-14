package com.ufo.galaxy.loop

import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.observability.GalaxyLogger

/**
 * Plans automation sequences for the local closed-loop pipeline.
 *
 * Bridges [LocalPlannerService] (MobileVLM) into the [ActionSequence] abstraction used by
 * [LoopController]. Falls back to a keyword-based rule heuristic when the local inference
 * service is unavailable or returns an empty plan.
 *
 * Grounding (SeeClick) is intentionally **not** performed here; it runs per-step inside
 * [ExecutorBridge] so that each step uses the freshest possible screenshot.
 *
 * @param plannerService MobileVLM V2-1.7B planner backend ([LocalPlannerService]).
 */
class LocalPlanner(
    private val plannerService: LocalPlannerService
) {

    companion object {
        internal const val TAG = "GALAXY:LOOP:PLANNER"
    }

    /** Returns true when the underlying planner model is loaded and ready. */
    fun isAvailable(): Boolean = plannerService.isModelLoaded()

    /**
     * Produces an [ActionSequence] for [instruction] using the best available strategy:
     *  1. MobileVLM local inference when the model is loaded.
     *  2. Keyword-based rule heuristic when inference is unavailable or returns empty steps.
     *
     * @param sessionId      Session identifier carried into the produced sequence.
     * @param instruction    Natural-language task description.
     * @param screenshotBase64 Optional Base64-encoded JPEG of the current screen.
     * @return [ActionSequence] with at least one [ActionStep].
     */
    fun plan(sessionId: String, instruction: String, screenshotBase64: String?): ActionSequence {
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "plan",
                "session_id" to sessionId,
                "inference_available" to isAvailable(),
                "instruction_len" to instruction.length
            )
        )

        if (!plannerService.isModelLoaded()) {
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "fallback",
                    "session_id" to sessionId,
                    "reason" to "planner_not_loaded"
                )
            )
            return ruleBased(sessionId, instruction)
        }

        val planResult = plannerService.plan(
            goal = instruction,
            constraints = emptyList(),
            screenshotBase64 = screenshotBase64
        )

        if (planResult.error != null || planResult.steps.isEmpty()) {
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "fallback",
                    "session_id" to sessionId,
                    "reason" to (planResult.error ?: "empty_plan")
                )
            )
            return ruleBased(sessionId, instruction)
        }

        val steps = planResult.steps.mapIndexed { index, step ->
            ActionStep(
                id = "step_${index + 1}",
                actionType = step.action_type,
                intent = step.intent,
                parameters = step.parameters
            )
        }

        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "plan_ok",
                "session_id" to sessionId,
                "step_count" to steps.size
            )
        )
        return ActionSequence(sessionId = sessionId, instruction = instruction, steps = steps)
    }

    /**
     * Produces a revised [ActionSequence] after [failedStep] encountered [failureReason].
     * Returns an empty-step sequence when replanning fails or the model is unavailable,
     * signalling to [LoopController] that the session should terminate.
     */
    fun replan(
        sessionId: String,
        instruction: String,
        failedStep: ActionStep,
        failureReason: String,
        screenshotBase64: String?
    ): ActionSequence {
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "replan",
                "session_id" to sessionId,
                "failed_step" to failedStep.id,
                "reason" to failureReason.take(120)
            )
        )

        if (!plannerService.isModelLoaded()) {
            return ActionSequence(
                sessionId = sessionId,
                instruction = instruction,
                steps = emptyList()
            )
        }

        val planStep = LocalPlannerService.PlanStep(
            action_type = failedStep.actionType,
            intent = failedStep.intent,
            parameters = failedStep.parameters
        )

        val planResult = plannerService.replan(
            goal = instruction,
            constraints = emptyList(),
            failedStep = planStep,
            error = failureReason,
            screenshotBase64 = screenshotBase64
        )

        if (planResult.error != null || planResult.steps.isEmpty()) {
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "replan_failed",
                    "session_id" to sessionId,
                    "reason" to (planResult.error ?: "empty_replan")
                )
            )
            return ActionSequence(
                sessionId = sessionId,
                instruction = instruction,
                steps = emptyList()
            )
        }

        val steps = planResult.steps.mapIndexed { index, step ->
            ActionStep(
                id = "replan_${index + 1}",
                actionType = step.action_type,
                intent = step.intent,
                parameters = step.parameters
            )
        }

        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "replan_ok",
                "session_id" to sessionId,
                "step_count" to steps.size
            )
        )
        return ActionSequence(sessionId = sessionId, instruction = instruction, steps = steps)
    }

    /**
     * Keyword-based fallback planner used when local inference is unavailable.
     *
     * Derives a single [ActionStep] from [instruction] using these heuristics:
     *  - "type" / "input" / "enter" → type action
     *  - "scroll" / "swipe"         → scroll action (direction=down)
     *  - "back"                     → back action
     *  - "home"                     → home action
     *  - anything else              → tap action
     */
    internal fun ruleBased(sessionId: String, instruction: String): ActionSequence {
        val lower = instruction.lowercase()
        val step = when {
            lower.contains("type") || lower.contains("input") || lower.contains("enter") ->
                ActionStep(
                    id = "step_1",
                    actionType = "type",
                    intent = instruction,
                    parameters = mapOf("text" to instruction)
                )
            lower.contains("scroll") || lower.contains("swipe") ->
                ActionStep(
                    id = "step_1",
                    actionType = "scroll",
                    intent = instruction,
                    parameters = mapOf("direction" to "down")
                )
            lower.contains("back") ->
                ActionStep(id = "step_1", actionType = "back", intent = instruction)
            lower.contains("home") ->
                ActionStep(id = "step_1", actionType = "home", intent = instruction)
            else ->
                ActionStep(id = "step_1", actionType = "tap", intent = instruction)
        }
        return ActionSequence(sessionId = sessionId, instruction = instruction, steps = listOf(step))
    }
}
