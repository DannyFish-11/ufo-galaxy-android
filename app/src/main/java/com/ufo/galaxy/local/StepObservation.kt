package com.ufo.galaxy.local

/**
 * Structured record of what was observed after a single automation step.
 *
 * Every step in the local loop produces one [StepObservation] that captures the
 * outcome from the perspective of the loop controller — not just whether the
 * accessibility action returned true, but also whether the UI visibly reacted.
 *
 * This model is intentionally separate from [com.ufo.galaxy.loop.ActionStep] so that
 * resilience logic (stagnation detection, fallback decisions) can be expressed against
 * this dedicated vocabulary without coupling to the planner/executor data models.
 *
 * @param stepId              Step identifier (e.g. "step_1"), matches [ActionStep.id].
 * @param actionType          Symbolic action type ("tap", "scroll", "type", …).
 * @param intent              Natural-language intent that drove this step.
 * @param screenshotCaptured  Whether a post-action screenshot was successfully captured.
 * @param uiChanged           Whether a detectable UI change was observed after the action.
 *                            `null` when no post-action screenshot was available to compare.
 * @param targetMatched       Whether the grounding engine found a plausible target.
 *                            `null` when grounding was bypassed (non-coordinate actions).
 * @param confidence          Grounding confidence [0.0, 1.0]; 0f when grounding was skipped.
 * @param failureCode         Structured reason code when the step failed; null on success.
 * @param summary             Human-readable summary of the step outcome.
 */
data class StepObservation(
    val stepId: String,
    val actionType: String,
    val intent: String,
    val screenshotCaptured: Boolean,
    val uiChanged: Boolean?,
    val targetMatched: Boolean?,
    val confidence: Float,
    val failureCode: FailureCode?,
    val summary: String
) {
    /** True when the step is considered a success (no failure code set). */
    val succeeded: Boolean get() = failureCode == null

    companion object {
        /**
         * Builds a successful [StepObservation].
         *
         * @param stepId    Step identifier.
         * @param actionType Action type token.
         * @param intent    Step intent.
         * @param uiChanged Whether a UI change was detected after the action.
         * @param targetMatched Whether the grounding target was found.
         * @param confidence Grounding confidence score.
         */
        fun success(
            stepId: String,
            actionType: String,
            intent: String,
            uiChanged: Boolean? = null,
            targetMatched: Boolean? = null,
            confidence: Float = 0f
        ): StepObservation = StepObservation(
            stepId = stepId,
            actionType = actionType,
            intent = intent,
            screenshotCaptured = true,
            uiChanged = uiChanged,
            targetMatched = targetMatched,
            confidence = confidence,
            failureCode = null,
            summary = "Step succeeded"
        )

        /**
         * Builds a failed [StepObservation].
         *
         * @param stepId      Step identifier.
         * @param actionType  Action type token.
         * @param intent      Step intent.
         * @param failureCode Structured reason code.
         * @param summary     Human-readable failure description.
         * @param confidence  Grounding confidence (if available before failure).
         */
        fun failure(
            stepId: String,
            actionType: String,
            intent: String,
            failureCode: FailureCode,
            summary: String,
            confidence: Float = 0f,
            screenshotCaptured: Boolean = true,
            uiChanged: Boolean? = null,
            targetMatched: Boolean? = null
        ): StepObservation = StepObservation(
            stepId = stepId,
            actionType = actionType,
            intent = intent,
            screenshotCaptured = screenshotCaptured,
            uiChanged = uiChanged,
            targetMatched = targetMatched,
            confidence = confidence,
            failureCode = failureCode,
            summary = summary
        )
    }
}
