package com.ufo.galaxy.runtime

/**
 * PR-07 (Android) — Execution Result and State Uplink Discipline.
 *
 * Defines the canonical rules for when Android MUST emit result signals and publish
 * state snapshots during execution lifecycle transitions, in alignment with center-side
 * (V2) canonical execution governance.
 *
 * ## Problem addressed
 *
 * Prior to PR-07, result uplinks and state uplinks were emitted on a best-effort basis
 * with no single contract specifying when they were required vs optional. This caused:
 *  1. V2 receiving inconsistent execution state signals, leading to stale cached state.
 *  2. Terminal outcomes (FAILED, TIMEOUT, CANCELLED) sometimes not emitting result signals
 *     on interruption/reconnect paths, leaving V2 unable to close the task.
 *  3. Degraded/fallback execution not reporting its reduced capability in state uplinks,
 *     allowing V2 to dispatch full-capability tasks to a degraded participant.
 *  4. INTERRUPTED paths being silently mapped to FAILED, preventing V2 from applying
 *     its retry/fallback policy for genuinely retriable interruptions.
 *
 * [ExecutionUplinkDiscipline] closes these gaps by defining:
 *  1. [ResultUplinkRule] — when a result signal is required vs optional vs prohibited.
 *  2. [StateUplinkRule] — when a state snapshot is required vs optional.
 *  3. [UplinkDecision] — the computed uplink decision for a given lifecycle phase entry.
 *  4. [classify] — the canonical decision function used by production code and tests.
 *
 * ## Relationship to [AndroidExecutionLifecycleContract]
 *
 * [AndroidExecutionLifecycleContract] defines the phase state machine and transition table.
 * [ExecutionUplinkDiscipline] defines the uplink semantics for each phase entry.
 * Together they form the complete PR-07 lifecycle hardening contract.
 *
 * @see AndroidExecutionLifecycleContract
 * @see DelegatedExecutionSignal
 * @see ReconciliationSignal
 */
object ExecutionUplinkDiscipline {

    // ── Result uplink rules ───────────────────────────────────────────────────

    /**
     * Classification of result uplink requirement for a lifecycle phase entry.
     *
     * @property wireValue    Stable wire value for logging and metadata.
     * @property description  Human-readable rule description.
     */
    enum class ResultUplinkRule(val wireValue: String, val description: String) {

        /**
         * A result signal MUST be emitted when entering this phase.
         *
         * Failure to emit is a lifecycle governance violation. V2 will treat the missing
         * result signal as a timeout and apply its own stale-task cleanup policy, which
         * may differ from the intended outcome.
         */
        REQUIRED(
            wireValue = "required",
            description = "Result signal MUST be emitted when entering this phase"
        ),

        /**
         * A result signal MAY be emitted (e.g. for diagnostics) but is not required.
         *
         * V2 does not depend on this signal for lifecycle management at this phase.
         */
        OPTIONAL(
            wireValue = "optional",
            description = "Result signal may be emitted but is not required"
        ),

        /**
         * A result signal MUST NOT be emitted when entering this phase.
         *
         * Emitting a result at a prohibited phase would corrupt V2's lifecycle state by
         * prematurely closing the execution flow. This applies to all non-terminal,
         * non-interruption phases (PENDING, ACTIVATING, ACTIVE, RETRYING).
         */
        PROHIBITED(
            wireValue = "prohibited",
            description = "Result signal MUST NOT be emitted when entering this phase"
        )
    }

    // ── State uplink rules ────────────────────────────────────────────────────

    /**
     * Classification of state uplink requirement for a lifecycle phase entry.
     *
     * @property wireValue    Stable wire value for logging and metadata.
     * @property description  Human-readable rule description.
     */
    enum class StateUplinkRule(val wireValue: String, val description: String) {

        /**
         * A state snapshot MUST be published when entering this phase.
         *
         * V2 relies on this snapshot to maintain consistent participant state. Failure to
         * publish leaves V2 with stale cached state that may cause incorrect dispatch
         * decisions or task assignment to a participant that is no longer eligible.
         */
        REQUIRED(
            wireValue = "required",
            description = "State snapshot MUST be published when entering this phase"
        ),

        /**
         * A state snapshot MAY be published for diagnostics or proactive state sync but
         * is not strictly required.
         *
         * Applies to CAPABILITY_IDLE and UNKNOWN phases where V2 state is managed by
         * the capability report pathway rather than execution lifecycle uplinks.
         */
        OPTIONAL(
            wireValue = "optional",
            description = "State snapshot may be published but is not required"
        )
    }

    // ── Uplink decision model ─────────────────────────────────────────────────

    /**
     * Computed uplink decision for a lifecycle phase entry.
     *
     * @property resultUplinkRule   Whether a result signal is required, optional, or prohibited.
     * @property stateUplinkRule    Whether a state snapshot is required or optional.
     * @property interruptionAware  True when the phase is an interruption path. V2 must handle
     *                             this distinctly from a definitive failure (apply retry policy).
     * @property degradedAware      True when the phase involves degraded/fallback execution.
     *                             The state uplink MUST include [AndroidExecutionLifecycleContract.KEY_FALLBACK_TIER]
     *                             and [AndroidExecutionLifecycleContract.KEY_IS_DEGRADED_EXECUTION]=true.
     * @property retryAware         True when the phase is a retry path. The state uplink MUST
     *                             include [AndroidExecutionLifecycleContract.KEY_RETRY_ATTEMPT_COUNT] ≥ 1.
     * @property uplinkNote         Human-readable description of this decision.
     */
    data class UplinkDecision(
        val resultUplinkRule: ResultUplinkRule,
        val stateUplinkRule: StateUplinkRule,
        val interruptionAware: Boolean = false,
        val degradedAware: Boolean = false,
        val retryAware: Boolean = false,
        val uplinkNote: String
    ) {
        /** True when a result uplink is required for this phase entry. */
        val resultRequired: Boolean get() = resultUplinkRule == ResultUplinkRule.REQUIRED

        /** True when a result uplink is prohibited for this phase entry. */
        val resultProhibited: Boolean get() = resultUplinkRule == ResultUplinkRule.PROHIBITED

        /** True when a state uplink is required for this phase entry. */
        val stateRequired: Boolean get() = stateUplinkRule == StateUplinkRule.REQUIRED
    }

    // ── Uplink decision table ─────────────────────────────────────────────────

    /**
     * Canonical uplink decision table keyed by [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase] wire value.
     *
     * Each entry specifies the uplink discipline for *entering* that phase. Production code
     * MUST use [classify] to consult this table rather than ad-hoc phase checks.
     *
     * Key: [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.wireValue].
     * Value: [UplinkDecision] governing what uplinks are required on entry to that phase.
     */
    val UPLINK_DECISION_TABLE: Map<String, UplinkDecision> = run {
        val P = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase
        mapOf(
            P.CAPABILITY_IDLE.wireValue to UplinkDecision(
                resultUplinkRule = ResultUplinkRule.PROHIBITED,
                stateUplinkRule = StateUplinkRule.OPTIONAL,
                uplinkNote = "CAPABILITY_IDLE: no result signal; optional state snapshot on return to idle; " +
                    "capability is advertised via capability_report pathway"
            ),
            P.PENDING.wireValue to UplinkDecision(
                resultUplinkRule = ResultUplinkRule.PROHIBITED,
                stateUplinkRule = StateUplinkRule.REQUIRED,
                uplinkNote = "PENDING: publish state snapshot to inform V2 of acceptance; no result signal"
            ),
            P.ACTIVATING.wireValue to UplinkDecision(
                resultUplinkRule = ResultUplinkRule.PROHIBITED,
                stateUplinkRule = StateUplinkRule.REQUIRED,
                uplinkNote = "ACTIVATING: publish state snapshot so V2 tracks in-preparation status; no result signal"
            ),
            P.ACTIVE.wireValue to UplinkDecision(
                resultUplinkRule = ResultUplinkRule.PROHIBITED,
                stateUplinkRule = StateUplinkRule.REQUIRED,
                uplinkNote = "ACTIVE: publish state snapshot with active task identity; ACK signal marks ACTIVE entry"
            ),
            P.DEGRADED.wireValue to UplinkDecision(
                resultUplinkRule = ResultUplinkRule.PROHIBITED,
                stateUplinkRule = StateUplinkRule.REQUIRED,
                degradedAware = true,
                uplinkNote = "DEGRADED: publish state with fallback_tier and is_degraded_execution=true; " +
                    "V2 must not dispatch full-capability tasks to a DEGRADED participant"
            ),
            P.INTERRUPTED.wireValue to UplinkDecision(
                resultUplinkRule = ResultUplinkRule.REQUIRED,
                stateUplinkRule = StateUplinkRule.REQUIRED,
                interruptionAware = true,
                uplinkNote = "INTERRUPTED: MUST emit RESULT(interruption) with interruption_cause and publish state; " +
                    "V2 applies its retry/fallback policy based on the interruption cause"
            ),
            P.TIMED_OUT.wireValue to UplinkDecision(
                resultUplinkRule = ResultUplinkRule.REQUIRED,
                stateUplinkRule = StateUplinkRule.REQUIRED,
                uplinkNote = "TIMED_OUT: MUST emit RESULT(TIMEOUT) and publish state; " +
                    "transition to terminal FAILED is immediate after uplinks"
            ),
            P.RETRYING.wireValue to UplinkDecision(
                resultUplinkRule = ResultUplinkRule.PROHIBITED,
                stateUplinkRule = StateUplinkRule.REQUIRED,
                interruptionAware = true,
                retryAware = true,
                uplinkNote = "RETRYING: publish state with retry_attempt_count ≥ 1; no result signal " +
                    "(retry is not a terminal outcome; V2 tracks retry count from state uplinks)"
            ),
            P.COMPLETED.wireValue to UplinkDecision(
                resultUplinkRule = ResultUplinkRule.REQUIRED,
                stateUplinkRule = StateUplinkRule.REQUIRED,
                uplinkNote = "COMPLETED: MUST emit RESULT(COMPLETED) and publish state; " +
                    "clear all in-flight task state after uplinks"
            ),
            P.FAILED.wireValue to UplinkDecision(
                resultUplinkRule = ResultUplinkRule.REQUIRED,
                stateUplinkRule = StateUplinkRule.REQUIRED,
                uplinkNote = "FAILED: MUST emit RESULT(FAILED/TIMEOUT/CANCELLED) and publish state; " +
                    "clear all in-flight task state after uplinks"
            ),
            P.REJECTED.wireValue to UplinkDecision(
                resultUplinkRule = ResultUplinkRule.REQUIRED,
                stateUplinkRule = StateUplinkRule.REQUIRED,
                uplinkNote = "REJECTED: MUST emit RESULT(REJECTED) and publish state; " +
                    "clear all in-flight task state after uplinks"
            ),
            P.UNKNOWN.wireValue to UplinkDecision(
                resultUplinkRule = ResultUplinkRule.OPTIONAL,
                stateUplinkRule = StateUplinkRule.OPTIONAL,
                uplinkNote = "UNKNOWN: forward-compat sentinel; uplinks are not governed"
            )
        )
    }

    // ── Decision function ─────────────────────────────────────────────────────

    /**
     * Returns the canonical [UplinkDecision] for entering [phase].
     *
     * This is the **canonical decision function**. Production code and tests MUST use this
     * rather than ad-hoc phase checks to ensure lifecycle discipline is enforced uniformly.
     *
     * For unknown phases (not in [UPLINK_DECISION_TABLE]), returns a permissive default
     * so forward-compatibility is not broken.
     *
     * @param phase  The [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase] being entered.
     * @return The [UplinkDecision] governing required uplinks for this phase entry.
     */
    fun classify(
        phase: AndroidExecutionLifecycleContract.ExecutionLifecyclePhase
    ): UplinkDecision = UPLINK_DECISION_TABLE[phase.wireValue] ?: UplinkDecision(
        resultUplinkRule = ResultUplinkRule.OPTIONAL,
        stateUplinkRule = StateUplinkRule.OPTIONAL,
        uplinkNote = "No uplink discipline entry for phase '${phase.wireValue}'; permissive default applied"
    )

    /**
     * Returns `true` when entering [phase] requires a result uplink.
     *
     * Convenience wrapper over [classify] for call sites that only need the result-uplink decision.
     */
    fun requiresResultUplink(
        phase: AndroidExecutionLifecycleContract.ExecutionLifecyclePhase
    ): Boolean = classify(phase).resultRequired

    /**
     * Returns `true` when entering [phase] requires a state uplink.
     *
     * Convenience wrapper over [classify] for call sites that only need the state-uplink decision.
     */
    fun requiresStateUplink(
        phase: AndroidExecutionLifecycleContract.ExecutionLifecyclePhase
    ): Boolean = classify(phase).stateRequired

    /**
     * Returns `true` when emitting a result signal on entering [phase] is prohibited.
     *
     * Emitting a result signal at a prohibited phase is a lifecycle governance violation that
     * MUST be treated as a critical contract error.
     */
    fun prohibitsResultUplink(
        phase: AndroidExecutionLifecycleContract.ExecutionLifecyclePhase
    ): Boolean = classify(phase).resultProhibited

    // ── Consistency validation ────────────────────────────────────────────────

    /**
     * Validates that [ExecutionUplinkDiscipline] and [AndroidExecutionLifecycleContract]
     * are internally consistent.
     *
     * Returns a list of consistency violations found. An empty list means the two contracts
     * are in alignment. Used in [com.ufo.galaxy.runtime.Pr85AndroidExecutionLifecycleHardeningTest]
     * to catch any future drift between the two contracts.
     *
     * Checks performed:
     *  1. Every [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase] has an entry in
     *     [UPLINK_DECISION_TABLE].
     *  2. Every phase whose [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.requiresResultUplink]
     *     is `true` has a [ResultUplinkRule.REQUIRED] decision.
     *  3. Every phase whose [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.requiresStateUplink]
     *     is `true` has a [StateUplinkRule.REQUIRED] decision.
     *  4. CAPABILITY_IDLE is classified as [ResultUplinkRule.PROHIBITED].
     *  5. All terminal phases are classified as [ResultUplinkRule.REQUIRED].
     */
    fun validateConsistency(): List<String> {
        val violations = mutableListOf<String>()
        val phases = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries

        for (phase in phases) {
            val decision = UPLINK_DECISION_TABLE[phase.wireValue]
            if (decision == null) {
                violations += "Phase '${phase.wireValue}' has no entry in UPLINK_DECISION_TABLE"
                continue
            }

            // Check result uplink consistency
            if (phase.requiresResultUplink && decision.resultUplinkRule != ResultUplinkRule.REQUIRED) {
                violations += "Phase '${phase.wireValue}': ExecutionLifecyclePhase.requiresResultUplink=true " +
                    "but UPLINK_DECISION_TABLE says ${decision.resultUplinkRule}"
            }

            // Check state uplink consistency
            if (phase.requiresStateUplink && decision.stateUplinkRule != StateUplinkRule.REQUIRED) {
                violations += "Phase '${phase.wireValue}': ExecutionLifecyclePhase.requiresStateUplink=true " +
                    "but UPLINK_DECISION_TABLE says ${decision.stateUplinkRule}"
            }
        }

        // CAPABILITY_IDLE must prohibit result uplinks
        val idleDecision = UPLINK_DECISION_TABLE[
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE.wireValue
        ]
        if (idleDecision?.resultUplinkRule != ResultUplinkRule.PROHIBITED) {
            violations += "CAPABILITY_IDLE must have ResultUplinkRule.PROHIBITED " +
                "(got: ${idleDecision?.resultUplinkRule})"
        }

        // All terminal phases must require result uplinks
        for (phase in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TERMINAL_PHASES) {
            val decision = UPLINK_DECISION_TABLE[phase.wireValue]
            if (decision?.resultUplinkRule != ResultUplinkRule.REQUIRED) {
                violations += "Terminal phase '${phase.wireValue}' must have ResultUplinkRule.REQUIRED " +
                    "(got: ${decision?.resultUplinkRule})"
            }
        }

        return violations
    }
}
