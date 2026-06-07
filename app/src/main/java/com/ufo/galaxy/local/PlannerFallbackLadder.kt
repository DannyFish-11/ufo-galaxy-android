package com.ufo.galaxy.local

import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.observability.GalaxyLogger

/**
 * Implements a multi-stage planner fallback chain to avoid session failure on
 * empty or invalid plans.
 *
 * The ladder is attempted in order until a non-empty plan is produced:
 *
 * 1. **Primary** — Full planner call with screenshot context.
 * 2. **No screenshot** — Planner call with the screenshot omitted; reduces input size
 *    and can recover when vision encoding fails.
 * 3. **Compact retry** — Planner call with a shortened, keyword-only instruction to
 *    reduce prompt complexity.
 * 4. **Rule-based parser** — Keyword heuristic that always produces exactly one step
 *    without any model inference.
 * 5. **Structured no-match** — Returns a [PlanResult] with a non-null [error] so the
 *    caller can record a structured [FailureCode.PLAN_ALL_STAGES_EXHAUSTED] failure.
 *
 * All stages are logged with a [STAGE_TAG] tag and a `stage` field.
 *
 * @param plannerService Underlying [LocalPlannerService] used for stages 1–3.
 */
class PlannerFallbackLadder(
    private val plannerService: LocalPlannerService
) {

    companion object {
        internal const val STAGE_TAG = "GALAXY:LOOP:PLANNER:LADDER"

        const val STAGE_PRIMARY = "primary"
        const val STAGE_NO_SCREENSHOT = "no_screenshot"
        const val STAGE_COMPACT_RETRY = "compact_retry"
        const val STAGE_RULE_BASED = "rule_based"
        const val STAGE_NO_MATCH = "no_match"
    }

    /**
     * Result from a fallback ladder attempt.
     *
     * @param steps       Non-empty list when a stage succeeded; empty when all stages failed.
     * @param stageUsed   The name of the stage that produced the result.
     * @param error       Non-null only when all stages are exhausted.
     * @param failureCode Structured failure code; set only when all stages exhausted.
     */
    data class PlanResult(
        val steps: List<LocalPlannerService.PlanStep>,
        val stageUsed: String,
        val error: String? = null,
        val failureCode: FailureCode? = null
    ) {
        val succeeded: Boolean get() = steps.isNotEmpty()
    }

    /**
     * Attempts to produce an initial plan for [goal] by descending the fallback ladder.
     *
     * @param sessionId          Session identifier for logging.
     * @param goal               Natural-language task goal.
     * @param screenshotBase64   Optional Base64-encoded JPEG of the current screen.
     * @return [PlanResult] with either a non-empty [PlanResult.steps] list or an exhausted error.
     */
    fun plan(
        sessionId: String,
        goal: String,
        screenshotBase64: String?
    ): PlanResult {
        // Stage 1: Primary — full planner call with screenshot.
        if (plannerService.isModelLoaded()) {
            val result = tryStage(STAGE_PRIMARY, sessionId) {
                plannerService.plan(goal, emptyList(), screenshotBase64)
            }
            if (result != null) return result
        }

        // Stage 2: No screenshot — omit the screenshot to reduce input complexity.
        if (plannerService.isModelLoaded() && screenshotBase64 != null) {
            val result = tryStage(STAGE_NO_SCREENSHOT, sessionId) {
                plannerService.plan(goal, emptyList(), screenshotBase64 = null)
            }
            if (result != null) return result
        }

        // Stage 3: Compact retry — shorten the instruction to its first 40 characters.
        if (plannerService.isModelLoaded()) {
            val compact = goal.take(40).trimEnd()
            val result = tryStage(STAGE_COMPACT_RETRY, sessionId) {
                plannerService.plan(compact, emptyList(), screenshotBase64 = null)
            }
            if (result != null) return result
        }

        // Stage 4: Rule-based parser — keyword heuristic, never fails.
        val ruleBased = buildRuleBasedStep(goal)
        GalaxyLogger.log(STAGE_TAG, mapOf(
            "event" to "ladder_stage",
            "session_id" to sessionId,
            "stage" to STAGE_RULE_BASED,
            "outcome" to "ok"
        ))
        return PlanResult(steps = listOf(ruleBased), stageUsed = STAGE_RULE_BASED)
    }

    /**
     * Attempts to produce a recovery plan after [failedStep] by descending the fallback ladder.
     *
     * @param sessionId        Session identifier for logging.
     * @param goal             Original task goal.
     * @param failedStep       The step whose failure triggered replanning.
     * @param failureReason    Human-readable description of the failure.
     * @param screenshotBase64 Optional Base64-encoded JPEG of the current screen.
     * @return [PlanResult] with either a non-empty [PlanResult.steps] list or an exhausted error.
     */
    fun replan(
        sessionId: String,
        goal: String,
        failedStep: LocalPlannerService.PlanStep,
        failureReason: String,
        screenshotBase64: String?
    ): PlanResult {
        // Stage 1: Primary replan with screenshot.
        if (plannerService.isModelLoaded()) {
            val result = tryStage(STAGE_PRIMARY, sessionId) {
                plannerService.replan(goal, emptyList(), failedStep, failureReason, screenshotBase64)
            }
            if (result != null) return result
        }

        // Stage 2: Replan without screenshot.
        if (plannerService.isModelLoaded() && screenshotBase64 != null) {
            val result = tryStage(STAGE_NO_SCREENSHOT, sessionId) {
                plannerService.replan(goal, emptyList(), failedStep, failureReason, null)
            }
            if (result != null) return result
        }

        // Stage 3: Compact replan — simplified goal.
        if (plannerService.isModelLoaded()) {
            val compact = goal.take(40).trimEnd()
            val result = tryStage(STAGE_COMPACT_RETRY, sessionId) {
                plannerService.replan(compact, emptyList(), failedStep, failureReason, null)
            }
            if (result != null) return result
        }

        // Stage 4: Rule-based recovery — use the failed step's intent as a new instruction.
        val ruleBased = buildRuleBasedStep(failedStep.intent)
        GalaxyLogger.log(STAGE_TAG, mapOf(
            "event" to "ladder_stage",
            "session_id" to sessionId,
            "stage" to STAGE_RULE_BASED,
            "outcome" to "ok"
        ))
        return PlanResult(steps = listOf(ruleBased), stageUsed = STAGE_RULE_BASED)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Executes a single planner stage, returning a [PlanResult] on success or null on failure.
     * All exceptions are caught and logged rather than propagated.
     */
    private fun tryStage(
        stage: String,
        sessionId: String,
        block: () -> LocalPlannerService.PlanResult
    ): PlanResult? {
        return try {
            val raw = block()
            if (raw.error == null && raw.steps.isNotEmpty()) {
                GalaxyLogger.log(STAGE_TAG, mapOf(
                    "event" to "ladder_stage",
                    "session_id" to sessionId,
                    "stage" to stage,
                    "outcome" to "ok",
                    "steps" to raw.steps.size
                ))
                PlanResult(steps = raw.steps, stageUsed = stage)
            } else {
                GalaxyLogger.log(STAGE_TAG, mapOf(
                    "event" to "ladder_stage",
                    "session_id" to sessionId,
                    "stage" to stage,
                    "outcome" to "empty",
                    "error" to (raw.error ?: "empty_plan")
                ))
                null
            }
        } catch (e: Exception) {
            GalaxyLogger.log(STAGE_TAG, mapOf(
                "event" to "ladder_stage",
                "session_id" to sessionId,
                "stage" to stage,
                "outcome" to "exception",
                "error" to (e.message ?: "unknown")
            ))
            null
        }
    }

    /**
     * Keyword-based rule heuristic that maps [instruction] to a single [LocalPlannerService.PlanStep].
     *
     * Heuristics (checked in order):
     * - "type" / "input" / "enter" → type
     * - "scroll" / "swipe"         → scroll (direction=down)
     * - "back"                     → back
     * - "home"                     → home
     * - everything else             → tap
     */
    internal fun buildRuleBasedStep(instruction: String): LocalPlannerService.PlanStep {
        val lower = instruction.lowercase()
        return when {
            lower.contains("type") || lower.contains("input") || lower.contains("enter") ->
                LocalPlannerService.PlanStep(
                    action_type = "type",
                    intent = instruction,
                    parameters = mapOf("text" to instruction)
                )
            lower.contains("scroll") || lower.contains("swipe") ->
                LocalPlannerService.PlanStep(
                    action_type = "scroll",
                    intent = instruction,
                    parameters = mapOf("direction" to "down")
                )
            lower.contains("back") ->
                LocalPlannerService.PlanStep(action_type = "back", intent = instruction)
            lower.contains("home") ->
                LocalPlannerService.PlanStep(action_type = "home", intent = instruction)
            else ->
                LocalPlannerService.PlanStep(action_type = "tap", intent = instruction)
        }
    }
}
