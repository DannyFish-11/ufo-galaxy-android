package com.ufo.galaxy.runtime

/**
 * PR-5 (Android) — Android local truth ownership coordinator and central alignment
 * participation skeleton.
 *
 * [AndroidLocalTruthOwnershipCoordinator] is the unified Android-side entry point for
 * local truth classification, emit gating, duplicate suppression, and central alignment
 * participation.  It extends the recovery participation semantics established by
 * [AndroidRecoveryParticipationOwner] (PR-4) into a full **truth ownership layer** that
 * answers the following questions for every outbound execution fact:
 *
 *  - Which [TruthClass] does this fact belong to?
 *  - What [LocalTruthEmitDecision] should govern how it is forwarded?
 *  - Should emission be suppressed because the unit is already locally terminal?
 *  - Should emission be held because a reconnect / rebind / resume is in progress?
 *  - Should emission be suppressed because the posture has changed?
 *
 * ## Background and motivation
 *
 * Before PR-5, Android truth output was distributed across multiple modules
 * ([DelegatedTakeoverExecutor], [DelegatedRuntimeReceiver], [RuntimeController],
 * [LocalCollaborationAgent], etc.) with no unified classification or gating layer.
 * This meant:
 *
 *  - Partial, final, failure, cancel, task_phase, and signal emissions could flow out
 *    without a coherent truth precedence model.
 *  - Posture changes did not trigger suppression of stale posture-bound truth.
 *  - After a local terminal, subsequent truth emissions for the same unit were not gated.
 *  - Reconnect / rebind / resume scenarios had no unified truth emit gate to coordinate
 *    against.
 *  - V2 had no reliable way to distinguish authoritative Android assertions from
 *    advisory or evidential signals.
 *
 * [AndroidLocalTruthOwnershipCoordinator] closes these gaps by providing a composable,
 * testable API that every truth-emitting code path can query before forwarding.
 *
 * ## Truth classification model
 *
 * Six truth classes are defined ([TruthClass]):
 *
 * | Class                             | Description                                                        |
 * |-----------------------------------|--------------------------------------------------------------------|
 * | [TruthClass.LOCAL_AUTHORITATIVE_ASSERTION] | Android owns this truth authoritatively; may be sent as canonical. |
 * | [TruthClass.EXECUTION_EVIDENCE]   | Factual execution data; not a canonical ownership claim.           |
 * | [TruthClass.ADVISORY_LOCAL_TRUTH] | Advisory/informational; V2 may override without coordination.      |
 * | [TruthClass.LOCAL_TERMINAL_CLAIM] | Android contributes a terminal outcome signal; emitted as execution evidence for center interpretation (A4). |
 * | [TruthClass.PARTIAL_RESULT_TRUTH] | Partial / intermediate result; not yet final.                      |
 * | [TruthClass.POSTURE_BOUND_TRUTH]  | Truth is valid only while the current posture is unchanged.        |
 *
 * ## Emit gating model
 *
 * Three gate mechanisms are layered in the [evaluateEmit] decision path:
 *
 * 1. **Local terminal suppression** — if the unit has already reached a terminal state,
 *    any further emission is suppressed as [LocalTruthEmitDecision.SuppressDueToLocalTerminal].
 * 2. **Central alignment hold** — if the truth emit gate for the unit is closed (e.g.
 *    during reconnect / rebind / resume), emission is held as
 *    [LocalTruthEmitDecision.HoldForCentralAlignment].
 * 3. **Posture conflict suppression** — if the truth is posture-bound and the posture has
 *    changed since the truth was generated, it is suppressed as
 *    [LocalTruthEmitDecision.SuppressDueToPostureConflict].
 *
 * ## Integration points
 *
 * [AndroidLocalTruthOwnershipCoordinator] establishes clear integration boundaries with
 * the following existing modules:
 *
 * | Integration point constant                    | Module                                                              | Role                                                              |
 * |-----------------------------------------------|---------------------------------------------------------------------|-------------------------------------------------------------------|
 * | [INTEGRATION_RECEIVER]                        | [com.ufo.galaxy.agent.DelegatedRuntimeReceiver]                     | Receipt acceptance yields LOCAL_AUTHORITATIVE_ASSERTION for ACK truth. |
 * | [INTEGRATION_UNIT]                            | [com.ufo.galaxy.agent.DelegatedRuntimeUnit]                         | Unit identity binds all truth to a stable unitId.                 |
 * | [INTEGRATION_ACTIVATION_RECORD]               | [com.ufo.galaxy.runtime.DelegatedActivationRecord]                  | Activation record status drives local terminal claim registration. |
 * | [INTEGRATION_PIPELINE]                        | [com.ufo.galaxy.agent.AutonomousExecutionPipeline]                  | Pipeline truth is classified; EXECUTION_EVIDENCE unless terminal. |
 * | [INTEGRATION_LOOP_CONTROLLER]                 | [com.ufo.galaxy.loop.LoopController]                                | Loop-phase truth classified as EXECUTION_EVIDENCE.                |
 * | [INTEGRATION_TAKEOVER_EXECUTOR]               | [com.ufo.galaxy.agent.DelegatedTakeoverExecutor]                    | Executor result signals classified; terminal results as LOCAL_TERMINAL_CLAIM. |
 * | [INTEGRATION_COLLABORATION_AGENT]             | [com.ufo.galaxy.agent.LocalCollaborationAgent]                      | Subtask truth classified as ADVISORY_LOCAL_TRUTH unless terminal. |
 *
 * ## Truth kind to class mapping
 *
 * | Signal / result kind   | Default truth class                           |
 * |------------------------|-----------------------------------------------|
 * | ACK                    | [TruthClass.LOCAL_AUTHORITATIVE_ASSERTION]    |
 * | PROGRESS               | [TruthClass.EXECUTION_EVIDENCE]               |
 * | RESULT (completed)     | [TruthClass.LOCAL_TERMINAL_CLAIM] → [LocalTruthEmitDecision.EmitAsExecutionEvidence] (A4) |
 * | RESULT (failed)        | [TruthClass.LOCAL_TERMINAL_CLAIM] → [LocalTruthEmitDecision.EmitAsExecutionEvidence] (A4) |
 * | RESULT (cancelled)     | [TruthClass.LOCAL_TERMINAL_CLAIM] → [LocalTruthEmitDecision.EmitAsExecutionEvidence] (A4) |
 * | RESULT (timed_out)     | [TruthClass.LOCAL_TERMINAL_CLAIM] → [LocalTruthEmitDecision.EmitAsExecutionEvidence] (A4) |
 * | PARTIAL_RESULT         | [TruthClass.PARTIAL_RESULT_TRUTH]             |
 * | TASK_PHASE             | [TruthClass.EXECUTION_EVIDENCE]               |
 * | POSTURE_CHANGE         | [TruthClass.POSTURE_BOUND_TRUTH]              |
 * | ADVISORY               | [TruthClass.ADVISORY_LOCAL_TRUTH]             |
 *
 * ## Thread safety
 *
 * [markLocalTerminal], [clearLocalTerminal], [isLocalTerminal], [localTerminalCount],
 * [clearAllLocalTerminals], [closeTruthEmitGate], [openTruthEmitGate], and
 * [clearAllTruthEmitGates] all use [synchronized] blocks or `@Volatile` flags for safe
 * cross-thread access.  [evaluateEmit] and [classifyTruth] are pure functions with no
 * shared mutable state.
 *
 * @see AndroidRecoveryParticipationOwner
 * @see LocalTruthEmitDecision
 * @see DelegatedExecutionSignal
 */
class AndroidLocalTruthOwnershipCoordinator {

    // ── Truth class enumeration ───────────────────────────────────────────────

    /**
     * Enumeration of the six local truth classes Android recognises.
     *
     * Each class governs which [LocalTruthEmitDecision] is returned by [evaluateEmit]
     * in the absence of suppression conditions.
     *
     * | Class                             | Default emit decision                        |
     * |-----------------------------------|----------------------------------------------|
     * | [LOCAL_AUTHORITATIVE_ASSERTION]   | [LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth] |
     * | [EXECUTION_EVIDENCE]              | [LocalTruthEmitDecision.EmitAsExecutionEvidence] |
     * | [ADVISORY_LOCAL_TRUTH]            | [LocalTruthEmitDecision.EmitAsAdvisoryOnly]  |
     * | [LOCAL_TERMINAL_CLAIM]            | [LocalTruthEmitDecision.EmitAsExecutionEvidence] (A4: terminal signals are execution contributions, not Android-owned completion truth) |
     * | [PARTIAL_RESULT_TRUTH]            | [LocalTruthEmitDecision.EmitAsExecutionEvidence] |
     * | [POSTURE_BOUND_TRUTH]             | [LocalTruthEmitDecision.EmitAsAdvisoryOnly] (posture-matched) or [LocalTruthEmitDecision.SuppressDueToPostureConflict] (posture-changed) |
     */
    enum class TruthClass(val wireValue: String) {
        /**
         * Android is the primary source of this truth and may emit it as a canonical
         * local assertion.  V2 must treat this as authoritative Android-side truth for the
         * given execution fact.
         */
        LOCAL_AUTHORITATIVE_ASSERTION("local_authoritative_assertion"),

        /**
         * This truth is factual evidence of something that happened during execution, but
         * does not carry an ownership claim.  V2 may incorporate the evidence without
         * treating it as a binding assertion.
         */
        EXECUTION_EVIDENCE("execution_evidence"),

        /**
         * This truth is advisory / informational.  V2 may disregard it without breaking
         * system invariants.  Advisory truth is typically soft state or intermediate
         * observations that do not need to be canonically committed.
         */
        ADVISORY_LOCAL_TRUTH("advisory_local_truth"),

        /**
         * Android is contributing a terminal execution outcome signal for the associated unit.
         *
         * This truth is emitted as [LocalTruthEmitDecision.EmitAsExecutionEvidence] — Android
         * contributes the terminal signal into the center-side execution chain, but the center
         * retains final authority over whether the system-level execution era is closed.
         *
         * Android MUST NOT treat this class as a device-local declaration of system completion
         * truth.  The center is the sole authority for final completion interpretation.
         *
         * @see AndroidAuthorityBoundaryClosure.CenterAuthorityDomain.FINAL_COMPLETION_TRUTH
         */
        LOCAL_TERMINAL_CLAIM("local_terminal_claim"),

        /**
         * This truth represents a partial / intermediate result that has not yet been
         * finalised.  It may be incorporated into V2's canonical partial result store but
         * must not be treated as a final canonical result.
         */
        PARTIAL_RESULT_TRUTH("partial_result_truth"),

        /**
         * This truth is valid only while the posture recorded at generation time matches
         * the current posture.  If the posture has changed, this truth must be suppressed.
         */
        POSTURE_BOUND_TRUTH("posture_bound_truth")
    }

    // ── EmitContext input model ───────────────────────────────────────────────

    /**
     * Input context for [evaluateEmit].
     *
     * @property unitId            The delegated unit ID whose truth is being evaluated.
     * @property truthClass        The [TruthClass] classification for this truth.
     * @property generatedPosture  The [SourceRuntimePosture] at the time the truth was
     *                             generated.  Required only for
     *                             [TruthClass.POSTURE_BOUND_TRUTH]; may be `null`
     *                             for other classes (posture conflict check is skipped).
     * @property currentPosture    The current [SourceRuntimePosture] at evaluation time.
     *                             Required only for [TruthClass.POSTURE_BOUND_TRUTH].
     * @property reason            Optional human-readable description of why this truth is
     *                             being emitted; used in [LocalTruthEmitDecision] annotations.
     */
    data class EmitContext(
        val unitId: String,
        val truthClass: TruthClass,
        val generatedPosture: SourceRuntimePosture? = null,
        val currentPosture: SourceRuntimePosture? = null,
        val reason: String = ""
    )

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Registry of unit IDs that have reached a local terminal state.
     *
     * Map key is [unitId]; value is the [terminalKind] string (e.g. "completed",
     * "failed", "cancelled", "timed_out").  Added by [markLocalTerminal]; removed by
     * [clearLocalTerminal] or [clearAllLocalTerminals].
     */
    private val _localTerminalRegistry: MutableMap<String, String> = mutableMapOf()

    /**
     * Set of unit IDs for which the truth emit gate is currently closed.
     *
     * A gated unit must not emit any truth until [openTruthEmitGate] is called.
     * Typically gated during reconnect / rebind / resume sequences.
     */
    private val _gatedUnitIds: MutableSet<String> = mutableSetOf()

    // ── Core API — truth classification ──────────────────────────────────────

    /**
     * Classifies a truth kind string into a [TruthClass].
     *
     * Maps canonical signal / result / phase kind strings to the appropriate [TruthClass]
     * using the standard truth kind to class mapping defined by this coordinator.
     *
     * See the class-level documentation for the full mapping table.  Unknown kind strings
     * default to [TruthClass.EXECUTION_EVIDENCE] as the most conservative classification.
     *
     * @param kind  The truth kind string to classify.  Should be one of the canonical
     *              constants defined in [KIND_*] companion constants.
     * @return The [TruthClass] for the given kind string.
     */
    fun classifyTruth(kind: String): TruthClass = when (kind) {
        KIND_ACK                          -> TruthClass.LOCAL_AUTHORITATIVE_ASSERTION
        KIND_RESULT_COMPLETED             -> TruthClass.LOCAL_TERMINAL_CLAIM
        KIND_RESULT_FAILED                -> TruthClass.LOCAL_TERMINAL_CLAIM
        KIND_RESULT_CANCELLED             -> TruthClass.LOCAL_TERMINAL_CLAIM
        KIND_RESULT_TIMED_OUT             -> TruthClass.LOCAL_TERMINAL_CLAIM
        KIND_PARTIAL_RESULT               -> TruthClass.PARTIAL_RESULT_TRUTH
        KIND_ADVISORY                     -> TruthClass.ADVISORY_LOCAL_TRUTH
        KIND_POSTURE_CHANGE               -> TruthClass.POSTURE_BOUND_TRUTH
        KIND_PROGRESS, KIND_TASK_PHASE    -> TruthClass.EXECUTION_EVIDENCE
        else                              -> TruthClass.EXECUTION_EVIDENCE
    }

    // ── Core API — emit decision ──────────────────────────────────────────────

    /**
     * Evaluates whether and how a truth emission should proceed for the given [context].
     *
     * ## Decision logic
     *
     * 1. **Local terminal suppression** — if [isLocalTerminal] is `true` for
     *    [context.unitId][EmitContext.unitId] →
     *    [LocalTruthEmitDecision.SuppressDueToLocalTerminal].
     *
     * 2. **Central alignment hold** — if [isTruthEmitGated] is `true` for
     *    [context.unitId][EmitContext.unitId] →
     *    [LocalTruthEmitDecision.HoldForCentralAlignment].
     *
     * 3. **Posture conflict suppression** — if [context.truthClass][EmitContext.truthClass]
     *    is [TruthClass.POSTURE_BOUND_TRUTH] and [context.generatedPosture] differs from
     *    [context.currentPosture] (both non-null) →
     *    [LocalTruthEmitDecision.SuppressDueToPostureConflict].
     *
     * 4. **Classification dispatch** — the default decision is derived from the
     *    [TruthClass]:
     *
     *    | TruthClass                         | Default decision                             |
     *    |------------------------------------|----------------------------------------------|
     *    | [TruthClass.LOCAL_AUTHORITATIVE_ASSERTION] | [LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth] |
     *    | [TruthClass.LOCAL_TERMINAL_CLAIM]  | [LocalTruthEmitDecision.EmitAsExecutionEvidence] (A4) |
     *    | [TruthClass.EXECUTION_EVIDENCE]    | [LocalTruthEmitDecision.EmitAsExecutionEvidence] |
     *    | [TruthClass.PARTIAL_RESULT_TRUTH]  | [LocalTruthEmitDecision.EmitAsExecutionEvidence] |
     *    | [TruthClass.ADVISORY_LOCAL_TRUTH]  | [LocalTruthEmitDecision.EmitAsAdvisoryOnly]  |
     *    | [TruthClass.POSTURE_BOUND_TRUTH]   | [LocalTruthEmitDecision.EmitAsAdvisoryOnly]  |
     *
     * @param context The [EmitContext] describing the truth and its surrounding conditions.
     * @return The [LocalTruthEmitDecision] classifying the required emit action.
     */
    fun evaluateEmit(context: EmitContext): LocalTruthEmitDecision {

        // ── 1. Local terminal suppression ─────────────────────────────────────
        val terminalKind = localTerminalKind(context.unitId)
        if (terminalKind != null) {
            return LocalTruthEmitDecision.SuppressDueToLocalTerminal(
                unitId = context.unitId,
                terminalKind = terminalKind
            )
        }

        // ── 2. Central alignment hold ─────────────────────────────────────────
        if (isTruthEmitGated(context.unitId)) {
            return LocalTruthEmitDecision.HoldForCentralAlignment(
                unitId = context.unitId,
                reason = REASON_TRUTH_EMIT_GATED_PENDING_ALIGNMENT
            )
        }

        // ── 3. Posture conflict suppression ───────────────────────────────────
        if (context.truthClass == TruthClass.POSTURE_BOUND_TRUTH) {
            val generated = context.generatedPosture
            val current = context.currentPosture
            if (generated != null && current != null && generated != current) {
                return LocalTruthEmitDecision.SuppressDueToPostureConflict(
                    unitId = context.unitId,
                    generatedPosture = generated,
                    currentPosture = current
                )
            }
        }

        // ── 4. Classification dispatch ────────────────────────────────────────
        return when (context.truthClass) {
            TruthClass.LOCAL_AUTHORITATIVE_ASSERTION ->
                LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth(
                    unitId = context.unitId,
                    truthClass = context.truthClass,
                    reason = context.reason.ifBlank { context.truthClass.wireValue }
                )

            TruthClass.EXECUTION_EVIDENCE,
            TruthClass.LOCAL_TERMINAL_CLAIM,
            TruthClass.PARTIAL_RESULT_TRUTH ->
                LocalTruthEmitDecision.EmitAsExecutionEvidence(
                    unitId = context.unitId,
                    truthClass = context.truthClass
                )

            TruthClass.ADVISORY_LOCAL_TRUTH,
            TruthClass.POSTURE_BOUND_TRUTH ->
                LocalTruthEmitDecision.EmitAsAdvisoryOnly(
                    unitId = context.unitId,
                    truthClass = context.truthClass,
                    advisoryNote = context.reason.ifBlank { null }
                )
        }
    }

    // ── Local terminal registry ───────────────────────────────────────────────

    /**
     * Marks the given [unitId] as having reached a local terminal state with the given
     * [terminalKind].
     *
     * Once registered, [evaluateEmit] will return
     * [LocalTruthEmitDecision.SuppressDueToLocalTerminal] for all subsequent truth
     * evaluations for this unit.
     *
     * @param unitId       The delegated unit ID to mark as terminal.
     * @param terminalKind The kind of terminal event (e.g. "completed", "failed",
     *                     "cancelled", "timed_out").
     */
    fun markLocalTerminal(unitId: String, terminalKind: String) {
        synchronized(_localTerminalRegistry) {
            _localTerminalRegistry[unitId] = terminalKind
        }
    }

    /**
     * Returns `true` when the given [unitId] has been registered as locally terminal.
     *
     * @param unitId  The delegated unit ID to check.
     */
    fun isLocalTerminal(unitId: String): Boolean =
        synchronized(_localTerminalRegistry) { _localTerminalRegistry.containsKey(unitId) }

    /**
     * Returns the terminal kind string for [unitId], or `null` if the unit is not
     * currently registered as terminal.
     *
     * @param unitId  The delegated unit ID to query.
     */
    fun localTerminalKind(unitId: String): String? =
        synchronized(_localTerminalRegistry) { _localTerminalRegistry[unitId] }

    /**
     * Clears the terminal registration for a single [unitId].
     *
     * Typically called when a unit's execution era is fully closed (e.g. after replay /
     * re-dispatch produces a new unit) to prevent stale suppression.
     *
     * @param unitId  The delegated unit ID to clear.
     */
    fun clearLocalTerminal(unitId: String) {
        synchronized(_localTerminalRegistry) { _localTerminalRegistry.remove(unitId) }
    }

    /**
     * Returns the count of currently registered local terminal unit IDs.
     */
    val localTerminalCount: Int
        get() = synchronized(_localTerminalRegistry) { _localTerminalRegistry.size }

    /**
     * Clears the entire local terminal registry.
     *
     * Use only when the full execution era is being reset (e.g. session close / process
     * recreation).  After clearing, all units will be treated as non-terminal.
     */
    fun clearAllLocalTerminals() {
        synchronized(_localTerminalRegistry) { _localTerminalRegistry.clear() }
    }

    // ── Truth emit gate ───────────────────────────────────────────────────────

    /**
     * Closes the truth emit gate for the given [unitId].
     *
     * While the gate is closed, [evaluateEmit] returns
     * [LocalTruthEmitDecision.HoldForCentralAlignment] for all evaluations for this unit.
     * Call this at the start of a reconnect / rebind / resume sequence to prevent truth
     * from leaking before alignment is established.
     *
     * @param unitId  The delegated unit ID for which to close the gate.
     */
    fun closeTruthEmitGate(unitId: String) {
        synchronized(_gatedUnitIds) { _gatedUnitIds.add(unitId) }
    }

    /**
     * Opens the truth emit gate for the given [unitId].
     *
     * After the gate is opened, [evaluateEmit] will no longer return
     * [LocalTruthEmitDecision.HoldForCentralAlignment] for the suppression step.  Call
     * this once the reconnect / rebind / resume sequence has been resolved and central
     * alignment is complete.
     *
     * @param unitId  The delegated unit ID for which to open the gate.
     */
    fun openTruthEmitGate(unitId: String) {
        synchronized(_gatedUnitIds) { _gatedUnitIds.remove(unitId) }
    }

    /**
     * Returns `true` when the truth emit gate for [unitId] is currently closed.
     *
     * @param unitId  The delegated unit ID to check.
     */
    fun isTruthEmitGated(unitId: String): Boolean =
        synchronized(_gatedUnitIds) { _gatedUnitIds.contains(unitId) }

    /**
     * Returns the count of unit IDs for which the truth emit gate is currently closed.
     */
    val gatedUnitCount: Int
        get() = synchronized(_gatedUnitIds) { _gatedUnitIds.size }

    /**
     * Clears all truth emit gates.
     *
     * Use only when the full execution era is being reset (e.g. session close / process
     * recreation).
     */
    fun clearAllTruthEmitGates() {
        synchronized(_gatedUnitIds) { _gatedUnitIds.clear() }
    }

    // ── Companion — constants ─────────────────────────────────────────────────

    companion object {

        // ── Emit decision semantic constants ─────────────────────────────────

        /**
         * Canonical wire value for the "emit as authoritative local truth" decision.
         *
         * Android owns this truth and may send it as a canonical local assertion.
         * V2 must treat it as authoritative for the given execution fact.
         */
        const val DECISION_EMIT_AS_AUTHORITATIVE_LOCAL_TRUTH = "emit_as_authoritative_local_truth"

        /**
         * Canonical wire value for the "emit as execution evidence" decision.
         *
         * Truth is factual execution data but not an ownership claim.  V2 may
         * incorporate it without treating it as an authoritative assertion.
         */
        const val DECISION_EMIT_AS_EXECUTION_EVIDENCE = "emit_as_execution_evidence"

        /**
         * Canonical wire value for the "emit as advisory only" decision.
         *
         * Truth is soft / informational.  V2 may disregard it without breaking invariants.
         */
        const val DECISION_EMIT_AS_ADVISORY_ONLY = "emit_as_advisory_only"

        /**
         * Canonical wire value for the "suppress due to local terminal" decision.
         *
         * Local execution is already terminal; duplicate emission must be suppressed.
         */
        const val DECISION_SUPPRESS_DUE_TO_LOCAL_TERMINAL = "suppress_due_to_local_terminal"

        /**
         * Canonical wire value for the "suppress due to posture conflict" decision.
         *
         * The current posture differs from the posture at truth generation time.
         * Stale posture-bound truth must be suppressed.
         */
        const val DECISION_SUPPRESS_DUE_TO_POSTURE_CONFLICT = "suppress_due_to_posture_conflict"

        /**
         * Canonical wire value for the "hold for central alignment" decision.
         *
         * The truth emit gate is closed; truth must be held until V2 alignment completes.
         */
        const val DECISION_HOLD_FOR_CENTRAL_ALIGNMENT = "hold_for_central_alignment"

        // ── Truth kind constants ──────────────────────────────────────────────

        /** ACK signal kind; classifies as [TruthClass.LOCAL_AUTHORITATIVE_ASSERTION]. */
        const val KIND_ACK = "ack"

        /** PROGRESS signal kind; classifies as [TruthClass.EXECUTION_EVIDENCE]. */
        const val KIND_PROGRESS = "progress"

        /** Completed result kind; classifies as [TruthClass.LOCAL_TERMINAL_CLAIM]. */
        const val KIND_RESULT_COMPLETED = "result_completed"

        /** Failed result kind; classifies as [TruthClass.LOCAL_TERMINAL_CLAIM]. */
        const val KIND_RESULT_FAILED = "result_failed"

        /** Cancelled result kind; classifies as [TruthClass.LOCAL_TERMINAL_CLAIM]. */
        const val KIND_RESULT_CANCELLED = "result_cancelled"

        /** Timed-out result kind; classifies as [TruthClass.LOCAL_TERMINAL_CLAIM]. */
        const val KIND_RESULT_TIMED_OUT = "result_timed_out"

        /** Partial result kind; classifies as [TruthClass.PARTIAL_RESULT_TRUTH]. */
        const val KIND_PARTIAL_RESULT = "partial_result"

        /** Task phase kind; classifies as [TruthClass.EXECUTION_EVIDENCE]. */
        const val KIND_TASK_PHASE = "task_phase"

        /** Posture change kind; classifies as [TruthClass.POSTURE_BOUND_TRUTH]. */
        const val KIND_POSTURE_CHANGE = "posture_change"

        /** Advisory kind; classifies as [TruthClass.ADVISORY_LOCAL_TRUTH]. */
        const val KIND_ADVISORY = "advisory"

        // ── Integration point constants ───────────────────────────────────────

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedRuntimeReceiver].
         *
         * Receipt acceptance yields a [TruthClass.LOCAL_AUTHORITATIVE_ASSERTION] for the
         * ACK truth, since Android has locally accepted the delegated unit.
         */
        const val INTEGRATION_RECEIVER = "DelegatedRuntimeReceiver"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedRuntimeUnit].
         *
         * Unit identity ([DelegatedRuntimeUnit.unitId]) is the stable anchor for all
         * truth classification and gating in this coordinator.
         */
        const val INTEGRATION_UNIT = "DelegatedRuntimeUnit"

        /**
         * Integration point identifier for [com.ufo.galaxy.runtime.DelegatedActivationRecord].
         *
         * When [DelegatedActivationRecord.activationStatus] transitions to a terminal
         * status (COMPLETED, FAILED, REJECTED), callers should invoke [markLocalTerminal]
         * with the corresponding [unitId] to enable terminal suppression.
         */
        const val INTEGRATION_ACTIVATION_RECORD = "DelegatedActivationRecord"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.AutonomousExecutionPipeline].
         *
         * Pipeline execution truth is classified as [TruthClass.EXECUTION_EVIDENCE] unless
         * it represents a terminal outcome.
         */
        const val INTEGRATION_PIPELINE = "AutonomousExecutionPipeline"

        /**
         * Integration point identifier for [com.ufo.galaxy.loop.LoopController].
         *
         * Loop-phase truth (step progress, replan events, stagnation) is classified as
         * [TruthClass.EXECUTION_EVIDENCE].
         */
        const val INTEGRATION_LOOP_CONTROLLER = "LoopController"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedTakeoverExecutor].
         *
         * Executor result signals (completed / failed / cancelled) are classified as
         * [TruthClass.LOCAL_TERMINAL_CLAIM].  Callers must invoke [markLocalTerminal] after
         * the executor emits the RESULT signal to enable subsequent suppression.
         */
        const val INTEGRATION_TAKEOVER_EXECUTOR = "DelegatedTakeoverExecutor"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.LocalCollaborationAgent].
         *
         * Subtask dispatch and collaboration truth are classified as
         * [TruthClass.ADVISORY_LOCAL_TRUTH] unless the subtask reaches a terminal state.
         */
        const val INTEGRATION_COLLABORATION_AGENT = "LocalCollaborationAgent"

        // ── Reason constants ──────────────────────────────────────────────────

        /**
         * Human-readable reason used in [LocalTruthEmitDecision.HoldForCentralAlignment]
         * when the truth emit gate is closed pending V2 central alignment.
         */
        const val REASON_TRUTH_EMIT_GATED_PENDING_ALIGNMENT =
            "truth_emit_gated_pending_central_alignment"

        /**
         * Human-readable reason used in [LocalTruthEmitDecision.SuppressDueToLocalTerminal]
         * when a duplicate emission is blocked because the unit is already terminal.
         */
        const val REASON_DUPLICATE_EMIT_AFTER_LOCAL_TERMINAL =
            "duplicate_emit_after_local_terminal_suppressed"

        // ── PR designation ────────────────────────────────────────────────────

        /**
         * The PR designation for this coordinator.
         */
        const val INTRODUCED_PR = 5

        /**
         * Human-readable description of this component's role in the truth ownership model.
         */
        const val DESCRIPTION =
            "Android-side local truth ownership coordinator: classifies execution truth, " +
                "gates emit during reconnect/resume, suppresses post-terminal duplicates, " +
                "and participates in V2 central truth alignment."
    }
}
