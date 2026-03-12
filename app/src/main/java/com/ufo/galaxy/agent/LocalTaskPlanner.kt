package com.ufo.galaxy.agent

import android.util.Log
import com.ufo.galaxy.inference.LocalPlannerService

/**
 * Thin wrapper around [LocalPlannerService] that converts a high-level goal into a
 * sequence of [LocalPlannerService.PlanStep] objects without requiring a live screenshot.
 *
 * Used by [LocalGoalExecutor] to produce a minimal local plan when the device receives
 * a [com.ufo.galaxy.protocol.GoalExecutionPayload]. The wrapper exists so that
 * goal-level planning can be unit-tested independently from screenshot capture and
 * grounding.
 *
 * When the underlying planner model is not loaded, a single "noop" fallback step is
 * returned so that the caller always gets a non-empty plan and can surface the model
 * readiness issue through the result's error field.
 *
 * @param plannerService Local MobileVLM planner service.
 */
class LocalTaskPlanner(private val plannerService: LocalPlannerService) {

    companion object {
        private const val TAG = "LocalTaskPlanner"
        private const val NOOP_ACTION = "noop"
        /** Maximum characters of goal text included in log messages. */
        private const val MAX_GOAL_LOG_LENGTH = LocalGoalExecutor.MAX_GOAL_LOG_LENGTH
    }

    /**
     * Produces a minimal action plan for [goal].
     *
     * If [plannerService] is loaded the plan is generated via its normal planning
     * endpoint (no screenshot context). If not loaded a single no-op step is returned
     * and the caller's [LocalGoalExecutor] will surface a meaningful error.
     *
     * @param goal        Natural-language objective.
     * @param constraints Optional natural-language constraint strings.
     * @return [LocalPlannerService.PlanResult] with steps or an error message.
     */
    fun planGoal(
        goal: String,
        constraints: List<String> = emptyList()
    ): LocalPlannerService.PlanResult {
        Log.d(TAG, "planGoal goal='${goal.take(MAX_GOAL_LOG_LENGTH)}'")

        if (!plannerService.isModelLoaded()) {
            Log.w(TAG, "Planner model not loaded; returning fallback noop plan")
            return LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep(NOOP_ACTION, goal)),
                error = "Model not loaded: planner unavailable for goal '$goal'"
            )
        }

        return try {
            val result = plannerService.plan(
                goal = goal,
                constraints = constraints,
                screenshotBase64 = null
            )
            Log.d(TAG, "planGoal produced ${result.steps.size} steps, error=${result.error}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "planGoal threw: ${e.message}", e)
            LocalPlannerService.PlanResult(
                steps = emptyList(),
                error = "Plan error: ${e.message}"
            )
        }
    }
}
