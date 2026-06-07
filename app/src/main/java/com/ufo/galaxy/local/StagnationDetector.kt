package com.ufo.galaxy.local

/**
 * Detects stagnation patterns in the local automation loop and terminates sessions
 * that are no longer making progress.
 *
 * The detector tracks three independent stagnation signals:
 *
 * 1. **Repeated identical action** — the same (actionType, intent) pair is attempted
 *    [maxRepeatedSameAction] times in a row without succeeding.
 * 2. **No UI change** — [maxNoUiChangeSteps] consecutive steps all report
 *    [StepObservation.uiChanged] == `false`.
 * 3. **Repeated identical plan** — the planner produces the same first-step signature
 *    [maxRepeatedSamePlan] times in a row.
 *
 * The detector is stateful and must be reset via [reset] at the start of each new
 * loop session or after a plan change.
 *
 * @param maxRepeatedSameAction Maximum number of consecutive identical (actionType, intent)
 *                              pairs before a [FailureCode.LOOP_STAGNATION_REPEATED_ACTION]
 *                              is reported.
 * @param maxNoUiChangeSteps    Maximum number of consecutive steps with no UI change before a
 *                              [FailureCode.LOOP_STAGNATION_NO_UI_CHANGE] is reported.
 * @param maxRepeatedSamePlan   Maximum number of consecutive identical plan signatures before a
 *                              [FailureCode.LOOP_STAGNATION_REPEATED_PLAN] is reported.
 */
class StagnationDetector(
    val maxRepeatedSameAction: Int = DEFAULT_MAX_REPEATED_SAME_ACTION,
    val maxNoUiChangeSteps: Int = DEFAULT_MAX_NO_UI_CHANGE_STEPS,
    val maxRepeatedSamePlan: Int = DEFAULT_MAX_REPEATED_SAME_PLAN
) {

    companion object {
        const val DEFAULT_MAX_REPEATED_SAME_ACTION = 3
        const val DEFAULT_MAX_NO_UI_CHANGE_STEPS = 4
        const val DEFAULT_MAX_REPEATED_SAME_PLAN = 3
    }

    // ── Internal state ────────────────────────────────────────────────────────

    /** Signature of the last dispatched action; null when no step has been recorded. */
    private var lastActionSignature: String? = null

    /** Number of consecutive identical action signatures seen. */
    private var repeatedActionCount: Int = 0

    /** Number of consecutive steps with no detected UI change. */
    private var noUiChangeCount: Int = 0

    /** Signature of the last plan's first step; null when no plan has been recorded. */
    private var lastPlanSignature: String? = null

    /** Number of consecutive identical plan signatures seen. */
    private var repeatedPlanCount: Int = 0

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Resets all stagnation counters. Call at the start of each new loop session and
     * whenever a meaningful plan change occurs.
     */
    fun reset() {
        lastActionSignature = null
        repeatedActionCount = 0
        noUiChangeCount = 0
        lastPlanSignature = null
        repeatedPlanCount = 0
    }

    /**
     * Records the outcome of a completed step and checks for stagnation.
     *
     * @param observation [StepObservation] produced by [PostActionObserver] for this step.
     * @return A [FailureCode] if stagnation is detected; `null` if the loop can continue.
     */
    fun recordStep(observation: StepObservation): FailureCode? {
        // Track repeated identical actions.
        val sig = actionSignature(observation.actionType, observation.intent)
        if (sig == lastActionSignature) {
            repeatedActionCount++
        } else {
            lastActionSignature = sig
            repeatedActionCount = 1
        }
        if (repeatedActionCount > maxRepeatedSameAction) {
            return FailureCode.LOOP_STAGNATION_REPEATED_ACTION
        }

        // Track consecutive no-UI-change steps.
        if (observation.uiChanged == false) {
            noUiChangeCount++
        } else {
            // uiChanged == true or null (inconclusive) resets the counter.
            noUiChangeCount = 0
        }
        if (noUiChangeCount >= maxNoUiChangeSteps) {
            return FailureCode.LOOP_STAGNATION_NO_UI_CHANGE
        }

        return null
    }

    /**
     * Records the plan produced for the current iteration and checks for repeated-plan stagnation.
     *
     * @param planSignature A short signature identifying the plan (e.g., first step's actionType
     *                      and intent concatenated). Use [buildPlanSignature] to produce this.
     * @return [FailureCode.LOOP_STAGNATION_REPEATED_PLAN] if the same plan signature has been seen
     *         [maxRepeatedSamePlan] times; `null` otherwise.
     */
    fun recordPlan(planSignature: String): FailureCode? {
        if (planSignature == lastPlanSignature) {
            repeatedPlanCount++
        } else {
            lastPlanSignature = planSignature
            repeatedPlanCount = 1
        }
        return if (repeatedPlanCount > maxRepeatedSamePlan) {
            FailureCode.LOOP_STAGNATION_REPEATED_PLAN
        } else {
            null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a plan signature from a list of (actionType, intent) step descriptors.
     * Uses only the first step to keep the signature stable across minor plan variations.
     */
    fun buildPlanSignature(steps: List<Pair<String, String>>): String {
        if (steps.isEmpty()) return "<empty>"
        val (type, intent) = steps[0]
        return "$type::${intent.take(60)}"
    }

    private fun actionSignature(actionType: String, intent: String): String =
        "$actionType::${intent.take(60)}"
}
