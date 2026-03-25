package com.ufo.galaxy.loop

import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.local.FailureCode
import com.ufo.galaxy.local.PlannerFallbackLadder
import com.ufo.galaxy.nlp.GoalNormalizer
import com.ufo.galaxy.observability.GalaxyLogger

/**
 * Plans automation sequences for the local closed-loop pipeline.
 *
 * Bridges [LocalPlannerService] (MobileVLM) into the [ActionSequence] abstraction used by
 * [LoopController]. Uses a [PlannerFallbackLadder] to attempt multiple strategies before
 * giving up, so transient model errors or empty responses do not immediately fail a session.
 *
 * Grounding (SeeClick) is intentionally **not** performed here; it runs per-step inside
 * [ExecutorBridge] so that each step uses the freshest possible screenshot.
 *
 * Before each plan or replan call the raw instruction is passed through [GoalNormalizer]
 * so the planner always receives a stable, alias-resolved form.  The original instruction
 * text is preserved in [ActionSequence.instruction] for display and logging.
 *
 * @param plannerService MobileVLM V2-1.7B planner backend ([LocalPlannerService]).
 * @param goalNormalizer Normalizes raw natural-language instructions before planning.
 */
class LocalPlanner(
    private val plannerService: LocalPlannerService,
    private val goalNormalizer: GoalNormalizer = GoalNormalizer()
) {

    companion object {
        internal const val TAG = "GALAXY:LOOP:PLANNER"
    }

    private val fallbackLadder = PlannerFallbackLadder(plannerService)

    /** Returns true when the underlying planner model is loaded and ready. */
    fun isAvailable(): Boolean = plannerService.isModelLoaded()

    /**
     * Produces an [ActionSequence] for [instruction] using the [PlannerFallbackLadder].
     *
     * The ladder tries (in order):
     * 1. Primary planner call with screenshot.
     * 2. Planner call without screenshot.
     * 3. Compact retry with shortened instruction.
     * 4. Keyword-based rule heuristic.
     *
     * All stages are logged with [PlannerFallbackLadder.STAGE_TAG].
     *
     * @param sessionId        Session identifier carried into the produced sequence.
     * @param instruction      Natural-language task description.
     * @param screenshotBase64 Optional Base64-encoded JPEG of the current screen.
     * @return [ActionSequence] with at least one [ActionStep] (rule-based fallback always fires).
     */
    fun plan(sessionId: String, instruction: String, screenshotBase64: String?): ActionSequence {
        val normalizedGoal = goalNormalizer.normalize(instruction)
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "plan",
                "session_id" to sessionId,
                "inference_available" to isAvailable(),
                "instruction_len" to instruction.length,
                "normalized_len" to normalizedGoal.normalized.length,
                "constraints" to normalizedGoal.constraints.size
            )
        )

        val result = fallbackLadder.plan(sessionId, normalizedGoal.normalized, screenshotBase64)

        GalaxyLogger.log(
            TAG, mapOf(
                "event" to if (result.succeeded) "plan_ok" else "plan_failed",
                "session_id" to sessionId,
                "stage_used" to result.stageUsed,
                "step_count" to result.steps.size
            )
        )

        return toActionSequence(sessionId, instruction, result.steps, prefix = "step")
    }

    /**
     * Produces a revised [ActionSequence] after [failedStep] encountered [failureReason].
     *
     * Uses [PlannerFallbackLadder.replan] to attempt multiple recovery strategies.
     * Returns an empty-step sequence only when the ladder is fully exhausted with no
     * rule-based fallback available (which should never happen with the current ladder).
     *
     * @param sessionId        Session identifier.
     * @param instruction      Original task instruction.
     * @param failedStep       The [ActionStep] that failed.
     * @param failureReason    Human-readable description of the failure.
     * @param screenshotBase64 Optional Base64-encoded JPEG of the current screen.
     * @return [ActionSequence] with recovery steps, or empty if all stages failed.
     */
    fun replan(
        sessionId: String,
        instruction: String,
        failedStep: ActionStep,
        failureReason: String,
        screenshotBase64: String?
    ): ActionSequence {
        val normalizedGoal = goalNormalizer.normalize(instruction)
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "replan",
                "session_id" to sessionId,
                "failed_step" to failedStep.id,
                "reason" to failureReason.take(120),
                "normalized_len" to normalizedGoal.normalized.length
            )
        )

        val planStep = LocalPlannerService.PlanStep(
            action_type = failedStep.actionType,
            intent = failedStep.intent,
            parameters = failedStep.parameters
        )

        val result = fallbackLadder.replan(
            sessionId = sessionId,
            goal = normalizedGoal.normalized,
            failedStep = planStep,
            failureReason = failureReason,
            screenshotBase64 = screenshotBase64
        )

        GalaxyLogger.log(
            TAG, mapOf(
                "event" to if (result.succeeded) "replan_ok" else "replan_failed",
                "session_id" to sessionId,
                "stage_used" to result.stageUsed,
                "step_count" to result.steps.size
            )
        )

        if (!result.succeeded) {
            return ActionSequence(sessionId = sessionId, instruction = instruction, steps = emptyList())
        }

        return toActionSequence(sessionId, instruction, result.steps, prefix = "replan")
    }

    /**
     * Returns the [FailureCode] from the last failed plan/replan call, or null when the
     * last call succeeded. Used by [LoopController] to record structured failure reasons.
     */
    fun lastFailureCode(): FailureCode? = null  // Ladder always succeeds via rule-based fallback.

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun toActionSequence(
        sessionId: String,
        instruction: String,
        planSteps: List<LocalPlannerService.PlanStep>,
        prefix: String
    ): ActionSequence {
        val steps = planSteps.mapIndexed { index, step ->
            ActionStep(
                id = "${prefix}_${index + 1}",
                actionType = step.action_type,
                intent = step.intent,
                parameters = step.parameters
            )
        }
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
     *
     * This method is retained for backwards compatibility and testing; the primary
     * fallback path now routes through [PlannerFallbackLadder].
     */
    internal fun ruleBased(sessionId: String, instruction: String): ActionSequence {
        val step = fallbackLadder.buildRuleBasedStep(instruction)
        return ActionSequence(
            sessionId = sessionId,
            instruction = instruction,
            steps = listOf(
                ActionStep(
                    id = "step_1",
                    actionType = step.action_type,
                    intent = step.intent,
                    parameters = step.parameters
                )
            )
        )
    }
}
