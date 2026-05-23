package com.ufo.galaxy.runtime

/**
 * PR-124 — Android V2 Failure Recovery Compatibility Contract.
 *
 * Classifies Android-side failure and interrupted execution conditions into
 * machine-actionable [FailureRecoveryClass] values so V2 can apply stricter
 * distributed recovery semantics without over-reading ambiguous failure output.
 *
 * ## Problem addressed
 *
 * V2's stricter distributed recovery semantics require Android to distinguish between:
 *  - Clean timeouts (execution exceeded wall-clock budget without partial output)
 *  - Session-interrupted failures (WS disconnect during an active task)
 *  - Partial-then-interrupted outcomes (task had partial progress before interruption)
 *  - Internal execution failures (task failed due to an internal execution error)
 *  - Non-failure terminations (cancelled, completed, or non-terminal)
 *
 * Without this classification, V2 must infer failure kind from loosely coupled
 * string fields, risking incorrect retry, recovery, or abandonment decisions once
 * distributed recovery semantics become stricter.
 *
 * This contract formalises failure recovery classification as a machine-actionable
 * [FailureRecoveryClass] and exposes stable wire keys and an alignment map so V2
 * can route failure signals to the correct distributed recovery handling path.
 *
 * ## Failure recovery classes
 *
 * | [FailureRecoveryClass]         | Cause                                                    | V2 action                                               |
 * |-------------------------------|----------------------------------------------------------|--------------------------------------------------------|
 * | [CLEAN_TIMEOUT]               | Execution exceeded wall-clock budget; no partial output  | Mark timed-out; retry if policy allows                 |
 * | [SESSION_INTERRUPTED]         | WS session disconnected during active task               | Await reconnect; resume or retry after reconnect       |
 * | [PARTIAL_THEN_INTERRUPTED]    | Partial progress observed before interruption/timeout    | Mark degraded; reconcile partial state before retry    |
 * | [EXECUTION_FAILED]            | Terminal failure from internal execution error           | Mark failed; trigger retry or escalation per policy    |
 * | [NOT_A_FAILURE]               | Signal is not a failure or interruption                  | No failure recovery action required                    |
 *
 * ## Wire integration
 *
 * [KEY_FAILURE_RECOVERY_CLASS] is embedded in [ReconciliationSignal] payload for
 * failure-bearing signal kinds ([ReconciliationSignal.Kind.TASK_FAILED]) so that
 * V2 receives a structured failure recovery class alongside the raw terminal outcome.
 *
 * ## Boundary constraint
 *
 * Android MUST NOT escalate a [NOT_A_FAILURE] classification to a failure class merely
 * to trigger V2 recovery logic.  The classification MUST accurately reflect the
 * Android-side observation.  V2 owns all canonical recovery decisions; Android only
 * supplies the structured failure evidence.
 *
 * @see ReconciliationSignal
 * @see RuntimeController
 */
object AndroidV2FailureRecoveryCompatibilityContract {

    /** Android PR that introduced this contract. */
    const val INTRODUCED_PR = 124

    /** Wire schema version for this contract's failure recovery fields. */
    const val SCHEMA_VERSION = "1"

    // ── Wire key constants ─────────────────────────────────────────────────────

    /**
     * Wire key for the [FailureRecoveryClass.wireValue] in failure-bearing signal payloads.
     *
     * V2 MUST read this key to route failure signals to the correct distributed recovery path.
     * When absent, V2 SHOULD treat the failure as [FailureRecoveryClass.EXECUTION_FAILED] for
     * backward compatibility with pre-PR-124 signals.
     */
    const val KEY_FAILURE_RECOVERY_CLASS = "failure_recovery_class"

    /**
     * Wire key indicating whether this failure involved a prior partial execution output.
     *
     * Value: `"true"` or `"false"`.  When `"true"`, V2 MUST reconcile any partial state
     * that Android may have produced before the interruption before closing the task record.
     */
    const val KEY_HAD_PARTIAL_PROGRESS = "failure_had_partial_progress"

    /**
     * Wire key indicating whether V2 retry is structurally safe for this failure class.
     *
     * Value: `"true"` or `"false"`.  When `"false"`, V2 MUST NOT automatically retry
     * without explicit human or policy-based escalation.
     */
    const val KEY_IS_RETRY_ELIGIBLE = "failure_is_retry_eligible"

    /**
     * Wire key for this contract's [SCHEMA_VERSION].
     */
    const val KEY_FAILURE_RECOVERY_SCHEMA_VERSION = "failure_recovery_schema_version"

    // ── FailureRecoveryClass ───────────────────────────────────────────────────

    /**
     * Structured failure recovery class for an Android-side failure or interrupted execution.
     *
     * @property wireValue             Stable lowercase string for wire transport.
     * @property isRetryEligible       True when V2 may attempt a retry without escalation.
     * @property requiresPartialReconciliation True when V2 must reconcile partial execution
     *                                         state before closing or retrying the task.
     * @property isInterruption        True when the failure was caused by a session or
     *                                 runtime interruption rather than task-level error.
     */
    enum class FailureRecoveryClass(
        val wireValue: String,
        val isRetryEligible: Boolean,
        val requiresPartialReconciliation: Boolean,
        val isInterruption: Boolean
    ) {

        /**
         * Execution exceeded its wall-clock budget without producing partial output.
         *
         * Android determined the task timed out before reaching a terminal result.  No
         * partial output was produced; V2 may retry the task according to its policy without
         * needing to reconcile intermediate state.
         *
         * Corresponds to [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT].
         */
        CLEAN_TIMEOUT(
            wireValue = "clean_timeout",
            isRetryEligible = true,
            requiresPartialReconciliation = false,
            isInterruption = false
        ),

        /**
         * The Android WS session disconnected while the task was actively executing.
         *
         * Android's connection to V2 was interrupted mid-execution.  V2 SHOULD wait for
         * reconnect evidence before making canonical closure decisions, and MAY attempt to
         * resume or retry the task once a clean session is re-established.
         *
         * Corresponds to [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION]
         * when the interruption cause is a session disconnect or session invalidation.
         */
        SESSION_INTERRUPTED(
            wireValue = "session_interrupted",
            isRetryEligible = true,
            requiresPartialReconciliation = false,
            isInterruption = true
        ),

        /**
         * The task produced partial execution progress before being interrupted or timing out.
         *
         * Android observed some partial output or progress before the interruption or timeout
         * occurred.  V2 MUST reconcile any partial state before closing or retrying the task
         * to avoid double-processing or silent partial-completion misreads.
         *
         * Corresponds to [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION]
         * or INTERRUPTION/TIMEOUT where [hadPartialProgress] is true.
         */
        PARTIAL_THEN_INTERRUPTED(
            wireValue = "partial_then_interrupted",
            isRetryEligible = false,
            requiresPartialReconciliation = true,
            isInterruption = true
        ),

        /**
         * The task failed due to an internal execution error.
         *
         * Android's execution pipeline returned a terminal error result without timeout or
         * session-level interruption.  V2 MAY trigger a retry or escalation according to its
         * policy; no partial state reconciliation is required unless [KEY_HAD_PARTIAL_PROGRESS]
         * is `"true"`.
         *
         * Corresponds to [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FAILURE]
         * or [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.ABORT].
         */
        EXECUTION_FAILED(
            wireValue = "execution_failed",
            isRetryEligible = true,
            requiresPartialReconciliation = false,
            isInterruption = false
        ),

        /**
         * The signal does not represent a failure or interrupted execution condition.
         *
         * Used for non-failure terminal outcomes (completion, recovery) and non-terminal
         * signals, so V2 can safely skip failure recovery routing for these classes.
         *
         * Corresponds to [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION],
         * [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.RECOVERY], or
         * [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.NON_TERMINAL].
         */
        NOT_A_FAILURE(
            wireValue = "not_a_failure",
            isRetryEligible = false,
            requiresPartialReconciliation = false,
            isInterruption = false
        );

        companion object {
            /** All stable wire values. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()

            fun fromWireValue(value: String?): FailureRecoveryClass? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── classify ──────────────────────────────────────────────────────────────

    /**
     * Returns the [FailureRecoveryClass] for the given terminal outcome context.
     *
     * This is the canonical entry point for classifying Android-side failure/interrupted
     * execution before emitting a failure [ReconciliationSignal].  Callers MUST use this
     * method rather than constructing failure classifications ad hoc.
     *
     * Priority order (most specific wins):
     * 1. [hadPartialProgress] true with any failure/interruption kind → [PARTIAL_THEN_INTERRUPTED]
     * 2. [terminalOutcomeKind] == TIMEOUT (and no partial progress) → [CLEAN_TIMEOUT]
     * 3. [terminalOutcomeKind] == INTERRUPTION with session cause → [SESSION_INTERRUPTED]
     * 4. [terminalOutcomeKind] == INTERRUPTION without session cause → [SESSION_INTERRUPTED]
     * 5. [terminalOutcomeKind] == FAILURE or ABORT → [EXECUTION_FAILED]
     * 6. [terminalOutcomeKind] == PARTIAL_COMPLETION → [PARTIAL_THEN_INTERRUPTED]
     * 7. Everything else (COMPLETION, RECOVERY, NON_TERMINAL, FALLBACK) → [NOT_A_FAILURE]
     *
     * @param terminalOutcomeKind The [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind]
     *                            already classified for this signal.
     * @param hadPartialProgress  True if Android observed any partial execution output before
     *                            the terminal condition.  When true the result is always
     *                            [FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED] for failure
     *                            and interruption outcome kinds.
     * @param sessionCause        Optional raw session-cause string (e.g. `"disconnect"`,
     *                            `"invalidation"`) for finer INTERRUPTION sub-classification.
     *                            Does not currently change the returned class but is available
     *                            for future extension.
     */
    fun classify(
        terminalOutcomeKind: AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind,
        hadPartialProgress: Boolean = false,
        sessionCause: String? = null
    ): FailureRecoveryClass {
        val isFailureKind = terminalOutcomeKind in setOf(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FAILURE,
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT,
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION,
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.ABORT,
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION
        )
        // Partial-progress with any failure/interruption kind always maps to PARTIAL_THEN_INTERRUPTED
        if (hadPartialProgress && isFailureKind) return FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED

        return when (terminalOutcomeKind) {
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT ->
                FailureRecoveryClass.CLEAN_TIMEOUT

            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION ->
                FailureRecoveryClass.SESSION_INTERRUPTED

            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FAILURE,
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.ABORT ->
                FailureRecoveryClass.EXECUTION_FAILED

            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION ->
                FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED

            else -> FailureRecoveryClass.NOT_A_FAILURE
        }
    }

    // ── toWireMap ─────────────────────────────────────────────────────────────

    /**
     * Returns a wire-safe map representation of the [failureRecoveryClass] and associated
     * metadata for embedding in [ReconciliationSignal] payload.
     *
     * @param failureRecoveryClass The classified failure recovery class.
     * @param hadPartialProgress   True if partial progress was observed before the failure.
     */
    fun toWireMap(
        failureRecoveryClass: FailureRecoveryClass,
        hadPartialProgress: Boolean = false
    ): Map<String, String> = mapOf(
        KEY_FAILURE_RECOVERY_CLASS to failureRecoveryClass.wireValue,
        KEY_HAD_PARTIAL_PROGRESS to hadPartialProgress.toString(),
        KEY_IS_RETRY_ELIGIBLE to failureRecoveryClass.isRetryEligible.toString(),
        KEY_FAILURE_RECOVERY_SCHEMA_VERSION to SCHEMA_VERSION
    )

    // ── V2_FAILURE_RECOVERY_ALIGNMENT_MAP ─────────────────────────────────────

    /**
     * Human-readable alignment map from [FailureRecoveryClass.wireValue] to the V2
     * distributed recovery handling path that should be invoked.
     *
     * This map is consumed at audit/observability time; runtime code must use [classify]
     * and [toWireMap] directly.
     */
    val V2_FAILURE_RECOVERY_ALIGNMENT_MAP: Map<String, String> = mapOf(
        FailureRecoveryClass.CLEAN_TIMEOUT.wireValue to
            "v2/recovery/timeout — mark timed-out; apply retry policy",
        FailureRecoveryClass.SESSION_INTERRUPTED.wireValue to
            "v2/recovery/session-interrupted — defer closure pending reconnect; resume or retry after clean session",
        FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED.wireValue to
            "v2/recovery/partial — reconcile partial state; do not auto-retry without reconciliation",
        FailureRecoveryClass.EXECUTION_FAILED.wireValue to
            "v2/recovery/execution-failed — mark failed; apply retry/escalation policy",
        FailureRecoveryClass.NOT_A_FAILURE.wireValue to
            "v2/recovery/skip — not a failure; no recovery action required"
    )

    // ── FAILURE_RECOVERY_INVARIANTS ────────────────────────────────────────────

    /**
     * Machine-verifiable invariants for the Stage 8 failure recovery compatibility contract.
     * All values must be `true`; any `false` indicates a regression.
     */
    val FAILURE_RECOVERY_INVARIANTS: Map<String, Boolean> = mapOf(

        // CLEAN_TIMEOUT is retry-eligible and not an interruption
        "clean_timeout_is_retry_eligible" to
            FailureRecoveryClass.CLEAN_TIMEOUT.isRetryEligible,

        "clean_timeout_is_not_interruption" to
            !FailureRecoveryClass.CLEAN_TIMEOUT.isInterruption,

        // SESSION_INTERRUPTED is an interruption
        "session_interrupted_is_interruption" to
            FailureRecoveryClass.SESSION_INTERRUPTED.isInterruption,

        // PARTIAL_THEN_INTERRUPTED requires partial reconciliation
        "partial_then_interrupted_requires_partial_reconciliation" to
            FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED.requiresPartialReconciliation,

        "partial_then_interrupted_is_not_retry_eligible" to
            !FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED.isRetryEligible,

        // NOT_A_FAILURE is not an interruption and not retry-eligible
        "not_a_failure_is_not_interruption" to
            !FailureRecoveryClass.NOT_A_FAILURE.isInterruption,

        "not_a_failure_is_not_retry_eligible" to
            !FailureRecoveryClass.NOT_A_FAILURE.isRetryEligible,

        // TIMEOUT outcome classifies as CLEAN_TIMEOUT (no partial progress)
        "timeout_classifies_as_clean_timeout" to
            (classify(
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT
            ) == FailureRecoveryClass.CLEAN_TIMEOUT),

        // INTERRUPTION outcome classifies as SESSION_INTERRUPTED (no partial progress)
        "interruption_classifies_as_session_interrupted" to
            (classify(
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION
            ) == FailureRecoveryClass.SESSION_INTERRUPTED),

        // INTERRUPTION with partial progress classifies as PARTIAL_THEN_INTERRUPTED
        "interruption_with_partial_progress_classifies_as_partial_then_interrupted" to
            (classify(
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION,
                hadPartialProgress = true
            ) == FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED),

        // TIMEOUT with partial progress classifies as PARTIAL_THEN_INTERRUPTED
        "timeout_with_partial_progress_classifies_as_partial_then_interrupted" to
            (classify(
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT,
                hadPartialProgress = true
            ) == FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED),

        // FAILURE classifies as EXECUTION_FAILED
        "failure_classifies_as_execution_failed" to
            (classify(
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FAILURE
            ) == FailureRecoveryClass.EXECUTION_FAILED),

        // PARTIAL_COMPLETION classifies as PARTIAL_THEN_INTERRUPTED
        "partial_completion_classifies_as_partial_then_interrupted" to
            (classify(
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION
            ) == FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED),

        // COMPLETION classifies as NOT_A_FAILURE
        "completion_classifies_as_not_a_failure" to
            (classify(
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION
            ) == FailureRecoveryClass.NOT_A_FAILURE),

        // All wire values are distinct
        "all_wire_values_are_distinct" to
            (FailureRecoveryClass.entries.map { it.wireValue }.toSet().size ==
                FailureRecoveryClass.entries.size),

        // V2 alignment map covers all failure recovery classes
        "v2_alignment_map_covers_all_classes" to
            FailureRecoveryClass.entries.all {
                V2_FAILURE_RECOVERY_ALIGNMENT_MAP.containsKey(it.wireValue)
            }
    )
}
