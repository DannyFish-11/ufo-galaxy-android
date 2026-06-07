package com.ufo.galaxy.runtime

/**
 * PR-8 (Android) — Classification of a compat / legacy influence encountered on the Android
 * delegated runtime path.
 *
 * [CompatLegacyInfluenceClass] is the typed classification input to
 * [AndroidCompatLegacyBlockingParticipant.evaluateBlockingDecision].  Every compat or legacy
 * path context that reaches a blocking evaluation point must first be assigned one of these
 * five classes to express what kind of influence the path exerts on the canonical runtime.
 *
 * ## Influence classes
 *
 * | Class                                       | Wire value                                      | Description                                                                                      |
 * |---------------------------------------------|-------------------------------------------------|--------------------------------------------------------------------------------------------------|
 * | [ALLOW_FOR_OBSERVATION_ONLY]                | `allow_for_observation_only`                    | Legacy path is retained solely for passive observation; must not influence canonical state.      |
 * | [BLOCK_DUE_TO_LEGACY_RUNTIME_PATH]          | `block_due_to_legacy_runtime_path`              | A non-canonical runtime path was detected; runtime transition must be blocked.                   |
 * | [BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE]    | `block_due_to_compat_contract_influence`        | A legacy compat contract is actively influencing emission or result semantics; must be blocked.  |
 * | [QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE]  | `quarantine_due_to_ambiguous_legacy_state`      | Legacy execution state is ambiguous and cannot be resolved locally; must be quarantined.         |
 * | [CANONICAL_RUNTIME_PATH_CONFIRMED]          | `canonical_runtime_path_confirmed`              | The path has been confirmed as canonical; blocking is not required.                              |
 *
 * @see AndroidCompatLegacyBlockingParticipant
 * @see CompatLegacyBlockingDecision
 */
enum class CompatLegacyInfluenceClass(val wireValue: String) {

    /**
     * The legacy or compat path is retained solely for passive observation purposes.
     *
     * This path must not write to canonical execution state, emit authoritative results,
     * influence signal semantics, or alter runtime transitions.  Its presence is permitted
     * only as a read-only diagnostic surface.
     *
     * Produces: [CompatLegacyBlockingDecision.AllowObservationOnly].
     */
    ALLOW_FOR_OBSERVATION_ONLY("allow_for_observation_only"),

    /**
     * A non-canonical runtime path was detected.
     *
     * The path routes through legacy runtime infrastructure (old executor, old delegated
     * receiver contract, old pipeline activation) that is no longer the canonical flow
     * control layer.  Allowing this path to continue would produce an invisible execution
     * fork that bypasses V2 canonical flow controls.
     *
     * Produces: [CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition].
     */
    BLOCK_DUE_TO_LEGACY_RUNTIME_PATH("block_due_to_legacy_runtime_path"),

    /**
     * A legacy compat contract is actively influencing emission or result semantics.
     *
     * Examples include: old validator accepting payloads that the canonical validator
     * rejects; old emit semantics forwarding results that the canonical truth gate would
     * suppress; old contract adapters remapping signals to non-canonical result shapes.
     *
     * Produces: [CompatLegacyBlockingDecision.SuppressLegacyEmit].
     */
    BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE("block_due_to_compat_contract_influence"),

    /**
     * Legacy execution state is ambiguous and cannot be resolved locally.
     *
     * This applies when the runtime has a historical execution state (e.g. from a prior
     * reconnect era, an interrupted recovery, or an incomplete replay sequence) that
     * cannot be mapped to a determinate canonical state without V2 center-side
     * coordination.  Silent pass-through of such state would corrupt canonical truth.
     *
     * Produces: [CompatLegacyBlockingDecision.QuarantineLegacyExecutionState].
     */
    QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE("quarantine_due_to_ambiguous_legacy_state"),

    /**
     * The runtime path has been confirmed as canonical.
     *
     * All dispatch, emission, result, and signal semantics for this context have been
     * verified to route through V2 canonical flow controls.  No blocking, quarantine, or
     * observation-only restrictions apply.
     *
     * Produces: [CompatLegacyBlockingDecision.ConfirmCanonicalRuntimeTransition].
     */
    CANONICAL_RUNTIME_PATH_CONFIRMED("canonical_runtime_path_confirmed");

    companion object {
        /**
         * Returns the [CompatLegacyInfluenceClass] whose [wireValue] matches [value],
         * or `null` if no match is found.
         *
         * @param value  The wire string to look up.
         */
        fun fromValue(value: String): CompatLegacyInfluenceClass? =
            entries.firstOrNull { it.wireValue == value }
    }
}
