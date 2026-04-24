package com.ufo.galaxy.runtime

/**
 * PR-5 (Android) — Android local truth ownership classification tiers.
 *
 * [LocalTruthOwnershipClassification] establishes the canonical Android-side truth
 * semantic taxonomy introduced by [AndroidLocalTruthOwner].  Every execution fact that
 * Android can emit — signal, result, partial result, task phase, cancellation,
 * failure — must be classified into one of these tiers before it is emitted or
 * suppressed, so that the delegated runtime's outbound truth is semantically unambiguous
 * to V2's central canonical truth system.
 *
 * ## Why truth classification matters
 *
 * Before PR-5 Android's truth output was spread across multiple modules
 * ([com.ufo.galaxy.agent.DelegatedTakeoverExecutor],
 * [com.ufo.galaxy.agent.AutonomousExecutionPipeline],
 * [com.ufo.galaxy.runtime.AndroidDelegatedFlowBridge], etc.) without a common semantic
 * contract.  V2 could not deterministically tell:
 *  - Which signal carries authoritative local execution fact versus advisory context.
 *  - Which signals may be safely discarded when local terminal state is already reached.
 *  - Which truth assertions are posture-sensitive and must be re-evaluated after a
 *    posture change.
 *
 * [LocalTruthOwnershipClassification] closes this gap by naming the tier explicitly
 * and attaching a stable [wireValue] that travels in metadata maps and structured logs.
 *
 * ## Tier overview
 *
 * | Tier                              | Wire value                        | Description                                                      |
 * |-----------------------------------|-----------------------------------|------------------------------------------------------------------|
 * | [LOCAL_AUTHORITATIVE_ASSERTION]   | `local_authoritative_assertion`   | The strongest claim Android can make; Android is certain.        |
 * | [EXECUTION_EVIDENCE]              | `execution_evidence`              | A factual signal produced during execution; not a final verdict. |
 * | [ADVISORY_LOCAL_TRUTH]            | `advisory_local_truth`            | Informational; V2 may use or discard.                            |
 * | [LOCAL_TERMINAL_CLAIM]            | `local_terminal_claim`            | Android declares the flow locally terminal.                      |
 * | [PARTIAL_RESULT_TRUTH]            | `partial_result_truth`            | Intermediate, non-final result; superseded by the final claim.   |
 * | [POSTURE_BOUND_TRUTH]             | `posture_bound_truth`             | Valid only for the current posture; must be re-evaluated on change. |
 *
 * @property wireValue   Stable lowercase wire string.  Consumers encountering an unknown
 *                       value must treat it as [ADVISORY_LOCAL_TRUTH] for forward-compatibility.
 * @property description Human-readable description of this tier for documentation and logs.
 */
enum class LocalTruthOwnershipClassification(
    val wireValue: String,
    val description: String
) {

    /**
     * The strongest Android-side claim: Android asserts this fact as authoritative within
     * its local execution domain.
     *
     * Examples: a RESULT signal after a successful delegated execution, a final failure
     * signal after an unrecoverable error.
     *
     * V2 should treat this as the canonical Android local assertion for the current
     * execution era and must not override it with advisory or evidence-tier signals.
     */
    LOCAL_AUTHORITATIVE_ASSERTION(
        wireValue = "local_authoritative_assertion",
        description = "Authoritative local execution assertion; Android is certain about this fact."
    ),

    /**
     * A factual signal produced during execution that V2 can use as supporting evidence,
     * but which does not constitute a final verdict on execution outcome.
     *
     * Examples: an ACK signal after a unit is received, a PROGRESS signal while steps
     * are running, a task_phase transition signal.
     *
     * V2 may use these to update observability surfaces but must not treat them as
     * terminal execution decisions.
     */
    EXECUTION_EVIDENCE(
        wireValue = "execution_evidence",
        description = "Non-final execution evidence produced during the execution lifecycle."
    ),

    /**
     * Informational truth that Android surfaces to V2 as advisory context.
     *
     * Examples: participation-readiness hints, recovery context summaries, local
     * inference metadata that V2 may incorporate into its canonical state if it chooses.
     *
     * V2 may discard or deprioritise advisory truth when it conflicts with its own
     * central canonical state.
     */
    ADVISORY_LOCAL_TRUTH(
        wireValue = "advisory_local_truth",
        description = "Advisory informational truth; V2 may use or discard at its discretion."
    ),

    /**
     * Android declares the flow to be locally terminal.
     *
     * Examples: a RESULT signal with COMPLETED / FAILED / CANCELLED / TIMEOUT
     * [com.ufo.galaxy.runtime.DelegatedExecutionSignal.ResultKind], a rejection notification.
     *
     * After a LOCAL_TERMINAL_CLAIM is emitted, [AndroidLocalTruthOwner] will suppress
     * any further emissions for the same execution unit unless a reconnect/rebind policy
     * explicitly allows re-emission.
     */
    LOCAL_TERMINAL_CLAIM(
        wireValue = "local_terminal_claim",
        description = "Android declares this flow locally terminal; suppresses further emissions."
    ),

    /**
     * An intermediate, non-final result that may be superseded by a subsequent final
     * [LOCAL_TERMINAL_CLAIM].
     *
     * Examples: incremental text output, intermediate grounding results, step-level
     * progress payloads that carry partial content before the goal is fully resolved.
     *
     * V2 may present partial results to operators or store them in the canonical flow
     * record, but must not treat them as closing the execution epoch.
     */
    PARTIAL_RESULT_TRUTH(
        wireValue = "partial_result_truth",
        description = "Intermediate partial result; not final — a terminal claim will follow."
    ),

    /**
     * A truth assertion that is only valid under the current [SourceRuntimePosture].
     *
     * When the posture changes (e.g. from JOIN_RUNTIME to CONTROL_ONLY) any
     * POSTURE_BOUND_TRUTH emitted under the previous posture should be treated as stale
     * by V2 and should not be re-emitted by Android unless the posture is re-established.
     *
     * Examples: posture-specific eligibility assertions, posture-scoped execution
     * capability reports, posture-sensitive task assignment responses.
     */
    POSTURE_BOUND_TRUTH(
        wireValue = "posture_bound_truth",
        description = "Valid only for the current posture; stale after a posture change."
    );

    companion object {

        /**
         * Parses [value] to a [LocalTruthOwnershipClassification].
         *
         * Returns [ADVISORY_LOCAL_TRUTH] as the safe forward-compatible fallback for
         * unknown or null inputs — advisory truth is the least-disruptive default
         * that V2 can safely ignore without breaking canonical state.
         *
         * @param value Wire string from a metadata map or JSON payload; may be null.
         */
        fun fromValue(value: String?): LocalTruthOwnershipClassification =
            entries.firstOrNull { it.wireValue == value } ?: ADVISORY_LOCAL_TRUTH

        /** All stable wire values, in declaration order. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}
