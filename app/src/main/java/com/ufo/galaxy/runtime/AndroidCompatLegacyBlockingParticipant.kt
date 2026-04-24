package com.ufo.galaxy.runtime

/**
 * PR-8 (Android) — Android compat / legacy path blocking participation owner / policy /
 * enforcement layer.
 *
 * [AndroidCompatLegacyBlockingParticipant] is the unified Android-side entry point for
 * compat / legacy influence classification, blocking, quarantine, and observation-only
 * enforcement.  It answers the following questions for every path context that touches
 * legacy or compat surfaces on the Android delegated runtime:
 *
 *  - Which [CompatLegacyInfluenceClass] does this path context belong to?
 *  - What [CompatLegacyBlockingDecision] governs whether execution may proceed?
 *  - Should a legacy emit be suppressed because a compat contract is influencing emission?
 *  - Should a runtime transition be blocked because a non-canonical path was detected?
 *  - Should a legacy execution state be quarantined pending V2 center-side alignment?
 *  - Should a path be restricted to observation only?
 *  - Has the path been confirmed as canonical, permitting normal execution?
 *
 * ## Background and motivation
 *
 * Before PR-8, Android compat / legacy paths coexisted with the canonical runtime without
 * an explicit blocking or quarantine layer.  This meant:
 *
 *  - legacy emit semantics could reach V2 alongside canonical truth, corrupting the
 *    canonical result / operator model.
 *  - non-canonical runtime transitions (old executor, old receiver contract, old pipeline
 *    activation) could silently fork execution away from V2 canonical flow controls.
 *  - ambiguous legacy execution state from prior reconnect eras or incomplete recovery
 *    sequences could pass through silently, corrupting canonical truth.
 *  - reconnect / replay / recovery paths could bypass new canonical flow controls via
 *    legacy routing.
 *  - compatibility validators, legacy contract adapters, and fallback handlers had no
 *    unified blocking or observation-only enforcement policy.
 *
 * [AndroidCompatLegacyBlockingParticipant] closes these gaps by providing a composable,
 * testable API that every compat / legacy path integration point can query before allowing
 * further execution.  The result is a device-side compat blocking / quarantine skeleton
 * that V2 canonicalization diagnostics and readiness gates can reliably consume.
 *
 * ## Influence classification model
 *
 * Five influence classes are defined ([CompatLegacyInfluenceClass]):
 *
 * | Class                                      | Description                                                                                     |
 * |--------------------------------------------|-------------------------------------------------------------------------------------------------|
 * | [CompatLegacyInfluenceClass.ALLOW_FOR_OBSERVATION_ONLY]               | Legacy path retained for passive observation only; no canonical state writes permitted. |
 * | [CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH]         | Non-canonical runtime path detected; runtime transition must be blocked.                |
 * | [CompatLegacyInfluenceClass.BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE]   | Legacy compat contract influencing emission or result semantics; emit must be suppressed.|
 * | [CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE] | Ambiguous legacy execution state; must quarantine pending V2 coordination.              |
 * | [CompatLegacyInfluenceClass.CANONICAL_RUNTIME_PATH_CONFIRMED]         | Path confirmed canonical; no blocking restrictions apply.                               |
 *
 * ## Blocking decision model
 *
 * Five blocking decisions are produced ([CompatLegacyBlockingDecision]):
 *
 * 1. **Canonical path confirmation** — if the influence class is
 *    [CompatLegacyInfluenceClass.CANONICAL_RUNTIME_PATH_CONFIRMED] →
 *    [CompatLegacyBlockingDecision.ConfirmCanonicalRuntimeTransition].
 *
 * 2. **Quarantine** — if the influence class is
 *    [CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE] →
 *    [CompatLegacyBlockingDecision.QuarantineLegacyExecutionState].
 *
 * 3. **Block legacy runtime path** — if the influence class is
 *    [CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH] →
 *    [CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition].
 *
 * 4. **Suppress legacy emit** — if the influence class is
 *    [CompatLegacyInfluenceClass.BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE] →
 *    [CompatLegacyBlockingDecision.SuppressLegacyEmit].
 *
 * 5. **Observation only** — if the influence class is
 *    [CompatLegacyInfluenceClass.ALLOW_FOR_OBSERVATION_ONLY] →
 *    [CompatLegacyBlockingDecision.AllowObservationOnly].
 *
 * ## Integration points
 *
 * [AndroidCompatLegacyBlockingParticipant] establishes clear integration boundaries with
 * the following existing runtime modules:
 *
 * | Integration point constant                  | Module                                                                       | Role                                                                        |
 * |---------------------------------------------|------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
 * | [INTEGRATION_RECEIVER]                      | [com.ufo.galaxy.agent.DelegatedRuntimeReceiver]                              | Receipt path: block legacy receiver contracts from silently influencing ACK truth. |
 * | [INTEGRATION_PIPELINE]                      | [com.ufo.galaxy.agent.AutonomousExecutionPipeline]                           | Pipeline activation: block legacy pipeline paths from forking canonical execution. |
 * | [INTEGRATION_ACTIVATION_RECORD]             | [com.ufo.galaxy.runtime.DelegatedActivationRecord]                           | Activation record transitions: quarantine ambiguous legacy activation states. |
 * | [INTEGRATION_TRUTH_OWNER]                   | [com.ufo.galaxy.runtime.AndroidLocalTruthOwnershipCoordinator]               | Truth emission: suppress legacy emit semantics that bypass canonical truth gating. |
 * | [INTEGRATION_RESULT_CONVERGENCE]            | [com.ufo.galaxy.runtime.AndroidFlowAwareResultConvergenceParticipant]        | Result convergence: suppress compat-contract-influenced result artifacts. |
 * | [INTEGRATION_COMPAT_VALIDATOR]              | [com.ufo.galaxy.runtime.ExecutionContractCompatibilityValidator]             | Compat validator: block legacy validator acceptance that bypasses canonical checks. |
 * | [INTEGRATION_RECONNECT_RECOVERY]            | [com.ufo.galaxy.runtime.AndroidRecoveryParticipationOwner]                   | Reconnect / recovery / replay: block legacy paths from bypassing canonical recovery flow. |
 * | [INTEGRATION_EXECUTION_EVENT_OWNER]         | [com.ufo.galaxy.runtime.AndroidCanonicalExecutionEventOwner]                 | Canonical event emission: suppress legacy-influenced execution events. |
 *
 * ## Quarantined unit registry
 *
 * [AndroidCompatLegacyBlockingParticipant] maintains an internal quarantine registry
 * ([quarantinedUnitCount], [isUnitQuarantined], [registerQuarantinedUnit],
 * [releaseQuarantinedUnit], [clearAllQuarantinedUnits]).  When a
 * [CompatLegacyBlockingDecision.QuarantineLegacyExecutionState] decision is produced,
 * the affected unit ID is automatically registered.  Quarantined units are held until
 * explicitly released after V2 center-side alignment confirms the state is resolved.
 *
 * ## Blocked path registry
 *
 * [AndroidCompatLegacyBlockingParticipant] maintains an internal blocked path registry
 * ([blockedPathCount], [isPathBlocked], [registerBlockedPath], [clearAllBlockedPaths]).
 * When a [CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition] decision is
 * produced, the [BlockingEvalContext.legacyPathTag] is automatically registered.
 *
 * ## Thread safety
 *
 * All registry operations ([registerQuarantinedUnit], [releaseQuarantinedUnit],
 * [isUnitQuarantined], [quarantinedUnitCount], [clearAllQuarantinedUnits],
 * [registerBlockedPath], [isPathBlocked], [blockedPathCount], [clearAllBlockedPaths])
 * use [synchronized] blocks for safe cross-thread access.
 * [evaluateBlockingDecision] and [classifyInfluence] are pure functions that rely only
 * on the synchronized accessors above.
 *
 * ## Relationship to sibling PR layers
 *
 * [AndroidCompatLegacyBlockingParticipant] is the *compat / legacy blocking* layer; it
 * governs which legacy paths are suppressed, blocked, quarantined, or restricted to
 * observation on Android.
 * [AndroidFlowAwareResultConvergenceParticipant] (PR-6) governs result convergence.
 * [AndroidCanonicalExecutionEventOwner] (PR-7) governs canonical execution event emission.
 * The three layers are designed to be composed side-by-side in the delegated runtime;
 * they share the same flow / unit identity model but operate on different artifact types.
 *
 * @see CompatLegacyInfluenceClass
 * @see CompatLegacyBlockingDecision
 * @see AndroidFlowAwareResultConvergenceParticipant
 * @see AndroidCanonicalExecutionEventOwner
 * @see AndroidLocalTruthOwnershipCoordinator
 */
class AndroidCompatLegacyBlockingParticipant {

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Registry of unit IDs that have been quarantined due to ambiguous legacy execution state.
     *
     * Map key is [unitId]; value is the legacy state tag recorded when the unit was
     * quarantined.  Added by [registerQuarantinedUnit]; removed by [releaseQuarantinedUnit]
     * or [clearAllQuarantinedUnits].
     */
    private val _quarantinedUnits: MutableMap<String, String> = mutableMapOf()

    /**
     * Registry of legacy path tags that have been actively blocked.
     *
     * Each entry records a [BlockingEvalContext.legacyPathTag] that triggered a
     * [CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition] decision.
     * Added by [registerBlockedPath]; cleared by [clearAllBlockedPaths].
     */
    private val _blockedPaths: MutableSet<String> = mutableSetOf()

    // ── Core API — influence classification ───────────────────────────────────

    /**
     * Classifies a compat / legacy influence class wire string into a
     * [CompatLegacyInfluenceClass].
     *
     * Maps canonical influence class wire strings to the appropriate
     * [CompatLegacyInfluenceClass] using [CompatLegacyInfluenceClass.fromValue].
     *
     * Unknown wire strings default to
     * [CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH] to enforce a
     * safe-by-default blocking posture for unrecognised path contexts.
     *
     * @param value  The influence class wire string to classify.
     * @return The [CompatLegacyInfluenceClass] for the given string.
     */
    fun classifyInfluence(value: String): CompatLegacyInfluenceClass =
        CompatLegacyInfluenceClass.fromValue(value)
            ?: CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH

    // ── Core API — blocking decision ──────────────────────────────────────────

    /**
     * Evaluates the compat / legacy blocking decision for the given [context].
     *
     * ## Decision logic
     *
     * 1. **Canonical path confirmation** — if [context.influenceClass] is
     *    [CompatLegacyInfluenceClass.CANONICAL_RUNTIME_PATH_CONFIRMED] →
     *    [CompatLegacyBlockingDecision.ConfirmCanonicalRuntimeTransition].
     *
     * 2. **Quarantine** — if [context.influenceClass] is
     *    [CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE] →
     *    registers [context.unitId] via [registerQuarantinedUnit] and returns
     *    [CompatLegacyBlockingDecision.QuarantineLegacyExecutionState].
     *
     * 3. **Block legacy runtime path** — if [context.influenceClass] is
     *    [CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH] →
     *    registers [context.legacyPathTag] via [registerBlockedPath] and returns
     *    [CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition].
     *
     * 4. **Suppress legacy emit** — if [context.influenceClass] is
     *    [CompatLegacyInfluenceClass.BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE] →
     *    [CompatLegacyBlockingDecision.SuppressLegacyEmit].
     *
     * 5. **Observation only** — if [context.influenceClass] is
     *    [CompatLegacyInfluenceClass.ALLOW_FOR_OBSERVATION_ONLY] →
     *    [CompatLegacyBlockingDecision.AllowObservationOnly].
     *
     * @param context  The [BlockingEvalContext] describing the compat / legacy path context.
     * @return The [CompatLegacyBlockingDecision] governing the path.
     */
    fun evaluateBlockingDecision(context: BlockingEvalContext): CompatLegacyBlockingDecision {

        // ── 1. Canonical path confirmation ─────────────────────────────────────
        if (context.influenceClass == CompatLegacyInfluenceClass.CANONICAL_RUNTIME_PATH_CONFIRMED) {
            return CompatLegacyBlockingDecision.ConfirmCanonicalRuntimeTransition(
                unitId = context.unitId
            )
        }

        // ── 2. Quarantine ──────────────────────────────────────────────────────
        if (context.influenceClass == CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE) {
            registerQuarantinedUnit(context.unitId, context.legacyStateTag ?: LEGACY_STATE_TAG_UNKNOWN)
            return CompatLegacyBlockingDecision.QuarantineLegacyExecutionState(
                unitId = context.unitId,
                legacyStateTag = context.legacyStateTag ?: LEGACY_STATE_TAG_UNKNOWN,
                reason = context.reason ?: REASON_QUARANTINE_AMBIGUOUS_LEGACY_STATE
            )
        }

        // ── 3. Block legacy runtime path ───────────────────────────────────────
        if (context.influenceClass == CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH) {
            val pathTag = context.legacyPathTag ?: LEGACY_PATH_TAG_UNKNOWN
            registerBlockedPath(pathTag)
            return CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition(
                unitId = context.unitId,
                legacyPathTag = pathTag,
                reason = context.reason ?: REASON_BLOCK_NONCANONICAL_RUNTIME_PATH
            )
        }

        // ── 4. Suppress legacy emit ────────────────────────────────────────────
        if (context.influenceClass == CompatLegacyInfluenceClass.BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE) {
            return CompatLegacyBlockingDecision.SuppressLegacyEmit(
                unitId = context.unitId,
                reason = context.reason ?: REASON_SUPPRESS_COMPAT_CONTRACT_INFLUENCE
            )
        }

        // ── 5. Observation only ────────────────────────────────────────────────
        return CompatLegacyBlockingDecision.AllowObservationOnly(
            unitId = context.unitId,
            note = context.reason
        )
    }

    // ── Quarantined unit registry ─────────────────────────────────────────────

    /**
     * Registers [unitId] as quarantined with the given [legacyStateTag].
     *
     * After this call, [isUnitQuarantined] returns `true` for [unitId].  Callers must
     * not allow further canonical state writes or result emissions for a quarantined unit
     * until it is explicitly released via [releaseQuarantinedUnit].
     *
     * @param unitId         The delegated unit or flow ID to quarantine.
     * @param legacyStateTag Stable tag describing the ambiguous legacy state.
     */
    fun registerQuarantinedUnit(unitId: String, legacyStateTag: String) {
        synchronized(_quarantinedUnits) { _quarantinedUnits[unitId] = legacyStateTag }
    }

    /**
     * Returns `true` when [unitId] is currently registered as quarantined.
     *
     * @param unitId  The delegated unit or flow ID to check.
     */
    fun isUnitQuarantined(unitId: String): Boolean =
        synchronized(_quarantinedUnits) { _quarantinedUnits.containsKey(unitId) }

    /**
     * Returns the legacy state tag registered when [unitId] was quarantined, or `null`
     * if the unit is not currently quarantined.
     *
     * @param unitId  The delegated unit or flow ID to query.
     */
    fun quarantinedStateTag(unitId: String): String? =
        synchronized(_quarantinedUnits) { _quarantinedUnits[unitId] }

    /**
     * Releases the quarantine on [unitId].
     *
     * Typically called after V2 center-side alignment confirms that the ambiguous legacy
     * state has been resolved and the unit may resume canonical execution.
     *
     * @param unitId  The delegated unit or flow ID to release.
     */
    fun releaseQuarantinedUnit(unitId: String) {
        synchronized(_quarantinedUnits) { _quarantinedUnits.remove(unitId) }
    }

    /**
     * Returns the count of currently quarantined unit IDs.
     */
    val quarantinedUnitCount: Int
        get() = synchronized(_quarantinedUnits) { _quarantinedUnits.size }

    /**
     * Clears the entire quarantine registry.
     *
     * Use only when the full execution era is being reset (e.g. session close / process
     * recreation) and all prior quarantine decisions are no longer applicable.
     */
    fun clearAllQuarantinedUnits() {
        synchronized(_quarantinedUnits) { _quarantinedUnits.clear() }
    }

    // ── Blocked path registry ─────────────────────────────────────────────────

    /**
     * Records [pathTag] as a blocked legacy runtime path.
     *
     * After this call, [isPathBlocked] returns `true` for [pathTag].
     *
     * @param pathTag  The stable legacy path tag to register as blocked.
     */
    fun registerBlockedPath(pathTag: String) {
        synchronized(_blockedPaths) { _blockedPaths.add(pathTag) }
    }

    /**
     * Returns `true` when [pathTag] is registered as a blocked legacy runtime path.
     *
     * @param pathTag  The legacy path tag to check.
     */
    fun isPathBlocked(pathTag: String): Boolean =
        synchronized(_blockedPaths) { _blockedPaths.contains(pathTag) }

    /**
     * Returns the count of currently registered blocked legacy path tags.
     */
    val blockedPathCount: Int
        get() = synchronized(_blockedPaths) { _blockedPaths.size }

    /**
     * Clears the entire blocked path registry.
     *
     * Use only when the full execution era is being reset.
     */
    fun clearAllBlockedPaths() {
        synchronized(_blockedPaths) { _blockedPaths.clear() }
    }

    // ── Evaluation context ────────────────────────────────────────────────────

    /**
     * Input context for [evaluateBlockingDecision].
     *
     * Captures all information required to classify a compat / legacy path encounter and
     * produce a [CompatLegacyBlockingDecision].
     *
     * @property unitId          The delegated unit or flow ID affected by the legacy / compat path.
     * @property influenceClass  The [CompatLegacyInfluenceClass] classifying the path context.
     * @property legacyPathTag   Stable identifier of the legacy runtime path (required when
     *                           [influenceClass] is [CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH]).
     * @property legacyStateTag  Stable identifier of the ambiguous legacy state (required when
     *                           [influenceClass] is [CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE]).
     * @property reason          Human-readable explanation of the compat / legacy encounter.
     * @property integrationPoint The [INTEGRATION_*] constant identifying the call-site module.
     */
    data class BlockingEvalContext(
        val unitId: String,
        val influenceClass: CompatLegacyInfluenceClass,
        val legacyPathTag: String? = null,
        val legacyStateTag: String? = null,
        val reason: String? = null,
        val integrationPoint: String? = null
    )

    // ── Companion — constants ─────────────────────────────────────────────────

    companion object {

        // ── Blocking decision semantic constants ──────────────────────────────

        /**
         * Canonical wire value for the "suppress legacy emit" decision.
         *
         * A legacy compat contract is influencing signal, result, or truth emission.
         * The emission must be suppressed to prevent compat-contract-influenced artifacts
         * from reaching V2 canonical surfaces.
         */
        const val DECISION_SUPPRESS_LEGACY_EMIT = "suppress_legacy_emit"

        /**
         * Canonical wire value for the "block noncanonical runtime transition" decision.
         *
         * A non-canonical runtime path was detected.  The runtime transition must be
         * blocked to prevent silent execution forking away from V2 canonical flow controls.
         */
        const val DECISION_BLOCK_NONCANONICAL_RUNTIME_TRANSITION =
            "block_noncanonical_runtime_transition"

        /**
         * Canonical wire value for the "quarantine legacy execution state" decision.
         *
         * Ambiguous legacy execution state has been detected.  The state must be
         * quarantined and held for V2 center-side alignment.  Silent pass-through would
         * corrupt canonical truth.
         */
        const val DECISION_QUARANTINE_LEGACY_EXECUTION_STATE =
            "quarantine_legacy_execution_state"

        /**
         * Canonical wire value for the "allow observation only" decision.
         *
         * The legacy or compat path is permitted for passive observation only.  It must
         * not write canonical state, emit authoritative results, or alter runtime transitions.
         */
        const val DECISION_ALLOW_OBSERVATION_ONLY = "allow_observation_only"

        /**
         * Canonical wire value for the "confirm canonical runtime transition" decision.
         *
         * The path has been confirmed as canonical.  Execution may proceed through
         * V2 canonical flow controls without restriction.
         */
        const val DECISION_CONFIRM_CANONICAL_RUNTIME_TRANSITION =
            "confirm_canonical_runtime_transition"

        // ── Integration point constants ───────────────────────────────────────

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedRuntimeReceiver].
         *
         * Receipt path: block legacy receiver contracts from silently influencing ACK truth
         * or routing delegated execution through non-canonical paths.
         */
        const val INTEGRATION_RECEIVER = "DelegatedRuntimeReceiver"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.AutonomousExecutionPipeline].
         *
         * Pipeline activation: block legacy pipeline paths from forking canonical
         * delegated execution away from V2 flow controls.
         */
        const val INTEGRATION_PIPELINE = "AutonomousExecutionPipeline"

        /**
         * Integration point identifier for [com.ufo.galaxy.runtime.DelegatedActivationRecord].
         *
         * Activation record transitions: quarantine ambiguous legacy activation states
         * from prior reconnect eras before they propagate into canonical flow state.
         */
        const val INTEGRATION_ACTIVATION_RECORD = "DelegatedActivationRecord"

        /**
         * Integration point identifier for [com.ufo.galaxy.runtime.AndroidLocalTruthOwnershipCoordinator].
         *
         * Truth emission: suppress legacy emit semantics that bypass canonical truth
         * gating and would deliver compat-contract-influenced truth to V2.
         */
        const val INTEGRATION_TRUTH_OWNER = "AndroidLocalTruthOwnershipCoordinator"

        /**
         * Integration point identifier for [com.ufo.galaxy.runtime.AndroidFlowAwareResultConvergenceParticipant].
         *
         * Result convergence: suppress compat-contract-influenced result artifacts before
         * they enter the V2 canonical convergence layer.
         */
        const val INTEGRATION_RESULT_CONVERGENCE = "AndroidFlowAwareResultConvergenceParticipant"

        /**
         * Integration point identifier for [com.ufo.galaxy.runtime.ExecutionContractCompatibilityValidator].
         *
         * Compat validator: block legacy validator acceptance of payloads that the
         * canonical validator would reject, preventing old-contract payloads from
         * silently routing through canonical execution paths.
         */
        const val INTEGRATION_COMPAT_VALIDATOR = "ExecutionContractCompatibilityValidator"

        /**
         * Integration point identifier for [com.ufo.galaxy.runtime.AndroidRecoveryParticipationOwner].
         *
         * Reconnect / recovery / replay: block legacy paths from bypassing canonical
         * recovery and reconnect flow controls during resume and replay sequences.
         */
        const val INTEGRATION_RECONNECT_RECOVERY = "AndroidRecoveryParticipationOwner"

        /**
         * Integration point identifier for [com.ufo.galaxy.runtime.AndroidCanonicalExecutionEventOwner].
         *
         * Canonical event emission: suppress legacy-influenced execution events that
         * would project non-canonical phase transitions to V2 operator surfaces.
         */
        const val INTEGRATION_EXECUTION_EVENT_OWNER = "AndroidCanonicalExecutionEventOwner"

        // ── Reason constants ──────────────────────────────────────────────────

        /**
         * Default reason used when a
         * [CompatLegacyBlockingDecision.QuarantineLegacyExecutionState] decision is produced
         * and no explicit reason was supplied in [BlockingEvalContext.reason].
         */
        const val REASON_QUARANTINE_AMBIGUOUS_LEGACY_STATE =
            "quarantine_required_due_to_ambiguous_legacy_execution_state"

        /**
         * Default reason used when a
         * [CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition] decision is
         * produced and no explicit reason was supplied in [BlockingEvalContext.reason].
         */
        const val REASON_BLOCK_NONCANONICAL_RUNTIME_PATH =
            "block_required_due_to_noncanonical_legacy_runtime_path"

        /**
         * Default reason used when a [CompatLegacyBlockingDecision.SuppressLegacyEmit]
         * decision is produced and no explicit reason was supplied in
         * [BlockingEvalContext.reason].
         */
        const val REASON_SUPPRESS_COMPAT_CONTRACT_INFLUENCE =
            "suppress_required_due_to_compat_contract_influence_on_emission"

        /**
         * Fallback legacy path tag used when no [BlockingEvalContext.legacyPathTag] was
         * supplied in a [CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH] context.
         */
        const val LEGACY_PATH_TAG_UNKNOWN = "unknown_legacy_runtime_path"

        /**
         * Fallback legacy state tag used when no [BlockingEvalContext.legacyStateTag] was
         * supplied in a [CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE]
         * context.
         */
        const val LEGACY_STATE_TAG_UNKNOWN = "unknown_legacy_execution_state"

        // ── PR designation ────────────────────────────────────────────────────

        /**
         * The PR designation for this component.
         */
        const val INTRODUCED_PR = 8

        /**
         * Human-readable description of this component's role in the compat / legacy
         * blocking model.
         */
        const val DESCRIPTION =
            "Android-side compat / legacy path blocking participation owner: classifies " +
                "legacy / compat influence on delegated runtime, receiver, pipeline, " +
                "activation record, truth emission, result convergence, compat validator, " +
                "and reconnect / recovery / replay paths; produces suppress / block / " +
                "quarantine / observation-only / canonical-confirm decisions; maintains " +
                "quarantine and blocked-path registries; provides stable device-side compat " +
                "blocking artifacts for V2 canonicalization diagnostics and readiness gates."
    }
}
