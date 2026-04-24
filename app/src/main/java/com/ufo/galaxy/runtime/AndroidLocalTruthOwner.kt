package com.ufo.galaxy.runtime

/**
 * PR-5 (Android) — Android local truth ownership coordinator and central alignment
 * participation skeleton.
 *
 * [AndroidLocalTruthOwner] is the unified Android-side entry point for **local truth
 * ownership** — the semantic layer that answers, for every execution fact the delegated
 * runtime wants to emit:
 *
 *  1. What [LocalTruthOwnershipClassification] tier does this fact belong to?
 *  2. Should it be emitted, suppressed, or held pending central alignment?
 *  3. Is the current posture still valid for this truth?
 *  4. Has a local terminal state already been reached, making further emission a duplicate?
 *  5. Is the central alignment gate open, or should this truth wait for V2 confirmation?
 *
 * ## Background and motivation
 *
 * Before PR-5, Android's execution truth was scattered across multiple modules:
 *  - [com.ufo.galaxy.agent.DelegatedTakeoverExecutor] emitted ACK / PROGRESS / RESULT
 *    signals without a shared semantic classification layer.
 *  - [com.ufo.galaxy.agent.AutonomousExecutionPipeline] produced result payloads without
 *    a posture-sensitive emit gate.
 *  - [com.ufo.galaxy.runtime.DelegatedActivationRecord] lifecycle transitions were not
 *    cross-referenced with a terminal-state suppression registry.
 *  - Reconnect / rebind / resume paths could trigger re-emission of already-sent truth
 *    without gating logic.
 *
 * [AndroidLocalTruthOwner] closes all of these gaps by providing:
 *
 *  - A central [classifyAndGate] entry point that every outbound truth path must call.
 *  - A per-unit terminal-state registry that suppresses duplicate post-terminal emissions.
 *  - A posture-conflict detector that suppresses posture-bound truth when posture changes.
 *  - A central alignment gate that holds truth during reconnect / rebind / resume until
 *    V2 canonical alignment is confirmed.
 *
 * ## Truth semantic layers
 *
 * Android-side execution facts map to the following
 * [LocalTruthOwnershipClassification] tiers:
 *
 * | Signal / fact                                           | Classification tier                                          |
 * |---------------------------------------------------------|--------------------------------------------------------------|
 * | RESULT (COMPLETED / FAILED / CANCELLED / TIMEOUT)      | [LocalTruthOwnershipClassification.LOCAL_AUTHORITATIVE_ASSERTION] |
 * | ACK (unit accepted for local execution)                 | [LocalTruthOwnershipClassification.EXECUTION_EVIDENCE]       |
 * | PROGRESS (mid-execution step update)                    | [LocalTruthOwnershipClassification.EXECUTION_EVIDENCE]       |
 * | TASK_PHASE transition signal                            | [LocalTruthOwnershipClassification.EXECUTION_EVIDENCE]       |
 * | RESULT (terminal — completed / failed / cancelled)      | [LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM]     |
 * | Partial result (intermediate content)                   | [LocalTruthOwnershipClassification.PARTIAL_RESULT_TRUTH]     |
 * | Posture-bound eligibility / capability assertion        | [LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH]      |
 * | Recovery context summary forwarded to V2                | [LocalTruthOwnershipClassification.ADVISORY_LOCAL_TRUTH]     |
 *
 * ## Emit gating rules
 *
 * 1. **Central alignment gate** — when the gate is closed via [closeCentralAlignmentGate]
 *    (e.g. at the start of a reconnect / rebind / resume sequence), all truth is held
 *    as [LocalTruthEmitDecision.HoldForCentralAlignment] until [openCentralAlignmentGate]
 *    is called.  Exception: [LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM] is
 *    never held — a terminal fact must be reported immediately regardless of gate state.
 *
 * 2. **Local terminal suppression** — once [recordLocalTerminal] is called for a unit ID,
 *    [classifyAndGate] returns [LocalTruthEmitDecision.SuppressDueToLocalTerminal] for any
 *    subsequent call with the same unit ID.
 *
 * 3. **Posture conflict** — when a
 *    [LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH] signal is classified and the
 *    [producedUnderPosture] differs from the [currentPosture] supplied to [classifyAndGate],
 *    the decision is [LocalTruthEmitDecision.SuppressDueToPostureConflict].
 *
 * ## Integration with existing modules
 *
 * [AndroidLocalTruthOwner] establishes clear integration boundaries with the modules
 * named in [LocalTruthEmitDecision] integration point constants:
 *
 * | Integration point                            | Module                                                              | Role                                                              |
 * |----------------------------------------------|---------------------------------------------------------------------|-------------------------------------------------------------------|
 * | [LocalTruthEmitDecision.INTEGRATION_RECEIVER]            | [com.ufo.galaxy.agent.DelegatedRuntimeReceiver]    | Classify acceptance before outbound ACK.                          |
 * | [LocalTruthEmitDecision.INTEGRATION_UNIT]                | [com.ufo.galaxy.agent.DelegatedRuntimeUnit]        | Unit metadata informs posture and session-binding classification. |
 * | [LocalTruthEmitDecision.INTEGRATION_ACTIVATION_RECORD]   | [com.ufo.galaxy.runtime.DelegatedActivationRecord] | Terminal status transitions trigger [recordLocalTerminal].        |
 * | [LocalTruthEmitDecision.INTEGRATION_PIPELINE]            | [com.ufo.galaxy.agent.AutonomousExecutionPipeline] | Pipeline result emissions must pass through [classifyAndGate].    |
 * | [LocalTruthEmitDecision.INTEGRATION_LOOP_CONTROLLER]     | [com.ufo.galaxy.loop.LoopController]               | Loop truth signals classified before outbound dispatch.           |
 * | [LocalTruthEmitDecision.INTEGRATION_TAKEOVER_EXECUTOR]   | [com.ufo.galaxy.agent.DelegatedTakeoverExecutor]   | Every ACK / PROGRESS / RESULT classified before signal sink.      |
 * | [LocalTruthEmitDecision.INTEGRATION_COLLABORATION_AGENT] | [com.ufo.galaxy.agent.LocalCollaborationAgent]     | Subtask partial / final signals classified before dispatch.       |
 *
 * ## Central alignment participation skeleton
 *
 * [AndroidLocalTruthOwner] provides the foundational hooks for future V2 central canonical
 * truth alignment:
 *
 *  - [closeCentralAlignmentGate] / [openCentralAlignmentGate] — allows the recovery
 *    layer (e.g. [AndroidRecoveryParticipationOwner]) to gate truth emission during a
 *    reconnect / rebind / resume sequence until V2 confirms alignment.
 *  - [recordLocalTerminal] — marks an execution unit as locally terminal so V2 need not
 *    worry about post-terminal truth from that unit.
 *  - [currentPosture] — mutable; callers update it via [updateCurrentPosture] when
 *    [SourceRuntimePosture] changes, causing subsequent posture-bound classifications to
 *    detect the conflict automatically.
 *
 * ## Thread safety
 *
 * [_terminalUnitIds] is guarded by a [synchronized] block on the set itself.
 * [_centralAlignmentGateClosed] and [_currentPosture] use `@Volatile` for safe
 * cross-thread visibility without locking.  All other methods are pure functions.
 *
 * @see LocalTruthOwnershipClassification
 * @see LocalTruthEmitDecision
 * @see AndroidRecoveryParticipationOwner
 */
class AndroidLocalTruthOwner {

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Registry of execution unit IDs for which a local terminal claim has been emitted.
     *
     * Once an entry is present, [classifyAndGate] returns
     * [LocalTruthEmitDecision.SuppressDueToLocalTerminal] for any subsequent call
     * with the same unit ID.  Cleared by [clearTerminalRegistry].
     */
    private val _terminalUnitIds: MutableSet<String> = mutableSetOf()

    /**
     * Central alignment gate flag.
     *
     * `true` when the gate is closed (emissions are held); `false` when the gate is open
     * (emissions are permitted).  Set to `true` by [closeCentralAlignmentGate] at the
     * start of a reconnect / resume sequence, and reset to `false` by
     * [openCentralAlignmentGate] when alignment is confirmed.
     *
     * Terminal claims ([LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM]) bypass
     * the gate and are always emitted immediately.
     */
    @Volatile
    private var _centralAlignmentGateClosed: Boolean = false

    /**
     * Current [SourceRuntimePosture] value.
     *
     * Updated via [updateCurrentPosture].  Used to detect posture conflicts when
     * a [LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH] signal was produced
     * under a different posture.
     */
    @Volatile
    private var _currentPosture: String = SourceRuntimePosture.DEFAULT

    // ── TruthInput ────────────────────────────────────────────────────────────

    /**
     * Input model for [classifyAndGate].
     *
     * @property unitId               Stable delegated-unit identifier.  Used for terminal-state
     *                                suppression lookup.
     * @property classification       The caller's proposed [LocalTruthOwnershipClassification]
     *                                tier for this emission.  [classifyAndGate] may override the
     *                                effective decision based on gate and suppression state.
     * @property producedUnderPosture The [SourceRuntimePosture] value that was active when this
     *                                truth was produced.  Only used when [classification] is
     *                                [LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH]; pass
     *                                [SourceRuntimePosture.DEFAULT] for non-posture-bound truth.
     * @property signalKindHint       Optional hint describing what kind of signal this input
     *                                represents (e.g. "ack", "result", "progress"); used for
     *                                human-readable [LocalTruthEmitDecision.reason] strings.
     */
    data class TruthInput(
        val unitId: String,
        val classification: LocalTruthOwnershipClassification,
        val producedUnderPosture: String = SourceRuntimePosture.DEFAULT,
        val signalKindHint: String = ""
    )

    // ── Core API — classify and gate ──────────────────────────────────────────

    /**
     * Classifies [input] and returns the [LocalTruthEmitDecision] that governs whether
     * and how the calling component should emit this truth signal.
     *
     * ## Decision priority
     *
     * 1. **Local terminal suppression** (highest priority) — if [input.unitId] has already
     *    been recorded as locally terminal via [recordLocalTerminal], return
     *    [LocalTruthEmitDecision.SuppressDueToLocalTerminal], regardless of gate state or
     *    classification.
     *
     * 2. **Posture conflict** — if [input.classification] is
     *    [LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH] and
     *    [input.producedUnderPosture] differs from [currentPosture], return
     *    [LocalTruthEmitDecision.SuppressDueToPostureConflict].
     *
     * 3. **Central alignment gate** — if [isCentralAlignmentGateClosed] is `true` and the
     *    classification is NOT [LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM],
     *    return [LocalTruthEmitDecision.HoldForCentralAlignment].
     *    Terminal claims bypass the gate unconditionally.
     *
     * 4. **Classification-to-decision mapping** — map [input.classification] to the
     *    appropriate emit decision variant.
     *
     * @param input The [TruthInput] describing the signal to be emitted.
     * @return A [LocalTruthEmitDecision] specifying what the caller must do.
     */
    fun classifyAndGate(input: TruthInput): LocalTruthEmitDecision {

        // ── 1. Local terminal suppression ─────────────────────────────────────
        if (isLocallyTerminal(input.unitId)) {
            return LocalTruthEmitDecision.SuppressDueToLocalTerminal(
                classification = input.classification,
                unitId = input.unitId,
                reason = "unit_id=${input.unitId} has already emitted a local terminal claim; " +
                    "suppressing post-terminal ${input.signalKindHint.ifEmpty { "emission" }}"
            )
        }

        // ── 2. Posture conflict ───────────────────────────────────────────────
        if (input.classification == LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH) {
            val snapshotPosture = input.producedUnderPosture
            val live = _currentPosture
            if (snapshotPosture != live) {
                return LocalTruthEmitDecision.SuppressDueToPostureConflict(
                    classification = input.classification,
                    producedUnderPosture = snapshotPosture,
                    currentPosture = live,
                    reason = "posture_bound_truth produced under posture=$snapshotPosture " +
                        "is stale; current posture=$live"
                )
            }
        }

        // ── 3. Central alignment gate — terminal claims bypass the gate ───────
        val isTerminalClaim =
            input.classification == LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM
        if (_centralAlignmentGateClosed && !isTerminalClaim) {
            return LocalTruthEmitDecision.HoldForCentralAlignment(
                classification = input.classification,
                reason = "central alignment gate is closed; holding " +
                    "${input.classification.wireValue} for ${input.unitId} until gate opens"
            )
        }

        // ── 4. Classification-to-decision mapping ─────────────────────────────
        return when (input.classification) {

            LocalTruthOwnershipClassification.LOCAL_AUTHORITATIVE_ASSERTION ->
                LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth(
                    classification = input.classification,
                    reason = "local_authoritative_assertion for unit_id=${input.unitId}"
                )

            LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM -> {
                // Record terminal before returning so subsequent calls are suppressed.
                recordLocalTerminal(input.unitId)
                LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth(
                    classification = input.classification,
                    reason = "local_terminal_claim for unit_id=${input.unitId}; " +
                        "terminal recorded and gate bypassed"
                )
            }

            LocalTruthOwnershipClassification.EXECUTION_EVIDENCE ->
                LocalTruthEmitDecision.EmitAsExecutionEvidence(
                    classification = input.classification,
                    reason = "execution_evidence for unit_id=${input.unitId}"
                )

            LocalTruthOwnershipClassification.ADVISORY_LOCAL_TRUTH ->
                LocalTruthEmitDecision.EmitAsAdvisoryOnly(
                    classification = input.classification,
                    reason = "advisory_local_truth for unit_id=${input.unitId}"
                )

            LocalTruthOwnershipClassification.PARTIAL_RESULT_TRUTH ->
                LocalTruthEmitDecision.EmitAsAdvisoryOnly(
                    classification = input.classification,
                    reason = "partial_result_truth for unit_id=${input.unitId}; " +
                        "intermediate — final terminal claim will follow"
                )

            LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH ->
                // Posture conflict already handled above; if we reach here the posture matches.
                LocalTruthEmitDecision.EmitAsAdvisoryOnly(
                    classification = input.classification,
                    reason = "posture_bound_truth for unit_id=${input.unitId}; " +
                        "posture=${_currentPosture} is consistent"
                )
        }
    }

    // ── Terminal registry ─────────────────────────────────────────────────────

    /**
     * Records [unitId] as locally terminal.
     *
     * After this call, [classifyAndGate] will return
     * [LocalTruthEmitDecision.SuppressDueToLocalTerminal] for any input with the same
     * [unitId].
     *
     * Callers should invoke this once when the execution unit reaches a terminal
     * [DelegatedActivationRecord.ActivationStatus] (COMPLETED, FAILED, or REJECTED)
     * or when a [LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM] signal is
     * about to be emitted.  [classifyAndGate] calls this automatically when processing
     * a [LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM] input.
     *
     * @param unitId Stable delegated-unit identifier to mark as terminal.
     */
    fun recordLocalTerminal(unitId: String) {
        synchronized(_terminalUnitIds) {
            _terminalUnitIds.add(unitId)
        }
    }

    /**
     * Returns `true` when [unitId] has been recorded as locally terminal via
     * [recordLocalTerminal].
     *
     * @param unitId Stable delegated-unit identifier to check.
     */
    fun isLocallyTerminal(unitId: String): Boolean {
        synchronized(_terminalUnitIds) {
            return unitId in _terminalUnitIds
        }
    }

    /**
     * The number of unit IDs currently registered as locally terminal.
     *
     * Useful for monitoring and test assertions.
     */
    val terminalUnitCount: Int
        get() = synchronized(_terminalUnitIds) { _terminalUnitIds.size }

    /**
     * Clears the terminal unit registry.
     *
     * Intended for use in recovery / process recreation scenarios where a fresh execution
     * era begins and old terminal records are no longer relevant.  Does not reopen the
     * central alignment gate.
     */
    fun clearTerminalRegistry() {
        synchronized(_terminalUnitIds) {
            _terminalUnitIds.clear()
        }
    }

    // ── Central alignment gate ────────────────────────────────────────────────

    /**
     * Closes the central alignment gate, causing all subsequent [classifyAndGate] calls
     * (except [LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM]) to return
     * [LocalTruthEmitDecision.HoldForCentralAlignment].
     *
     * Call at the start of a reconnect / rebind / resume sequence when Android must wait
     * for V2 canonical alignment before emitting buffered truth.
     */
    fun closeCentralAlignmentGate() {
        _centralAlignmentGateClosed = true
    }

    /**
     * Opens the central alignment gate, allowing [classifyAndGate] to return emit
     * decisions again.
     *
     * Call once V2 confirms canonical alignment — e.g. when a new delegated flow
     * dispatch is received after a reconnect, or when the recovery participation owner
     * confirms resume is authorised.
     */
    fun openCentralAlignmentGate() {
        _centralAlignmentGateClosed = false
    }

    /**
     * `true` when the central alignment gate is currently closed.
     *
     * Thread-safe read via `@Volatile`.
     */
    val isCentralAlignmentGateClosed: Boolean
        get() = _centralAlignmentGateClosed

    // ── Posture management ────────────────────────────────────────────────────

    /**
     * Updates the owner's tracked [SourceRuntimePosture] value.
     *
     * After this call, any [LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH] input
     * to [classifyAndGate] that carries the old posture value will be classified as
     * [LocalTruthEmitDecision.SuppressDueToPostureConflict].
     *
     * @param newPosture The new [SourceRuntimePosture] canonical string
     *                   (e.g. [SourceRuntimePosture.JOIN_RUNTIME] or
     *                   [SourceRuntimePosture.CONTROL_ONLY]).  Unrecognised values are
     *                   normalised via [SourceRuntimePosture.fromValue].
     */
    fun updateCurrentPosture(newPosture: String) {
        _currentPosture = SourceRuntimePosture.fromValue(newPosture)
    }

    /**
     * The current [SourceRuntimePosture] value tracked by this owner.
     *
     * Thread-safe read via `@Volatile`.
     */
    val currentPosture: String
        get() = _currentPosture

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {

        /**
         * PR number introducing this class.  Used in log tags and documentation cross-references.
         */
        const val INTRODUCED_PR = 5

        /**
         * Human-readable description of this class for documentation and structured logs.
         */
        const val DESCRIPTION =
            "Android local truth ownership coordinator and central alignment participation skeleton. " +
                "Classifies execution truth signals into semantic tiers, gates emission via terminal " +
                "suppression and posture-conflict detection, and holds signals for central alignment " +
                "during reconnect/resume."

        // ── Signal kind → classification mapping constants ────────────────────

        /**
         * Recommended [LocalTruthOwnershipClassification] for a
         * [com.ufo.galaxy.runtime.DelegatedExecutionSignal.Kind.ACK] signal.
         */
        val CLASSIFICATION_SIGNAL_ACK: LocalTruthOwnershipClassification =
            LocalTruthOwnershipClassification.EXECUTION_EVIDENCE

        /**
         * Recommended [LocalTruthOwnershipClassification] for a
         * [com.ufo.galaxy.runtime.DelegatedExecutionSignal.Kind.PROGRESS] signal.
         */
        val CLASSIFICATION_SIGNAL_PROGRESS: LocalTruthOwnershipClassification =
            LocalTruthOwnershipClassification.EXECUTION_EVIDENCE

        /**
         * Recommended [LocalTruthOwnershipClassification] for a final
         * [com.ufo.galaxy.runtime.DelegatedExecutionSignal.Kind.RESULT] signal.
         *
         * A RESULT signal is both an authoritative assertion (Android is certain about the
         * outcome) and a terminal claim (the flow is done locally).  Use
         * [CLASSIFICATION_RESULT_TERMINAL] when submitting the terminal RESULT so that
         * the terminal registry is populated automatically by [classifyAndGate].
         */
        val CLASSIFICATION_SIGNAL_RESULT: LocalTruthOwnershipClassification =
            LocalTruthOwnershipClassification.LOCAL_AUTHORITATIVE_ASSERTION

        /**
         * Recommended [LocalTruthOwnershipClassification] to trigger terminal recording
         * via [classifyAndGate] when submitting a final RESULT signal.
         *
         * Using [LOCAL_TERMINAL_CLAIM] causes [classifyAndGate] to both record the unit
         * as terminal AND return [LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth]
         * so the signal is still emitted.
         */
        val CLASSIFICATION_RESULT_TERMINAL: LocalTruthOwnershipClassification =
            LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM

        /**
         * Recommended [LocalTruthOwnershipClassification] for a task_phase transition signal.
         */
        val CLASSIFICATION_TASK_PHASE: LocalTruthOwnershipClassification =
            LocalTruthOwnershipClassification.EXECUTION_EVIDENCE

        /**
         * Recommended [LocalTruthOwnershipClassification] for a partial result payload.
         */
        val CLASSIFICATION_PARTIAL_RESULT: LocalTruthOwnershipClassification =
            LocalTruthOwnershipClassification.PARTIAL_RESULT_TRUTH

        /**
         * Recommended [LocalTruthOwnershipClassification] for a recovery context summary
         * forwarded to V2 (e.g. via [AndroidRecoveryParticipationOwner]).
         */
        val CLASSIFICATION_RECOVERY_CONTEXT: LocalTruthOwnershipClassification =
            LocalTruthOwnershipClassification.ADVISORY_LOCAL_TRUTH

        /**
         * Recommended [LocalTruthOwnershipClassification] for posture-sensitive eligibility
         * or capability assertions.
         */
        val CLASSIFICATION_POSTURE_ASSERTION: LocalTruthOwnershipClassification =
            LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH
    }
}
