package com.ufo.galaxy.runtime

/**
 * PR-5 (Android) — Typed Android-side local truth emit decision.
 *
 * [LocalTruthEmitDecision] is the sealed output produced by
 * [AndroidLocalTruthOwner.classifyAndGate] for every execution fact that a delegated
 * runtime component wants to emit toward V2.  Every call site must handle all variants
 * and must not emit anything until a decision is obtained.
 *
 * ## Decision taxonomy
 *
 * | Variant                           | Wire tag                         | Action required by caller                           |
 * |-----------------------------------|----------------------------------|-----------------------------------------------------|
 * | [EmitAsAuthoritativeLocalTruth]   | `emit_as_authoritative_local_truth` | Emit immediately as the strongest Android-side claim. |
 * | [EmitAsExecutionEvidence]         | `emit_as_execution_evidence`     | Emit as supporting non-final execution evidence.    |
 * | [EmitAsAdvisoryOnly]              | `emit_as_advisory_only`          | Emit as advisory; V2 may discard.                   |
 * | [SuppressDueToLocalTerminal]      | `suppress_due_to_local_terminal` | Do NOT emit; local terminal already reached.        |
 * | [SuppressDueToPostureConflict]    | `suppress_due_to_posture_conflict` | Do NOT emit; posture changed and this truth is stale. |
 * | [HoldForCentralAlignment]         | `hold_for_central_alignment`     | Buffer; do not emit until central alignment gate opens. |
 *
 * ## Integration points
 *
 * [LocalTruthEmitDecision] is the primary mechanism through which
 * [AndroidLocalTruthOwner] establishes clear integration boundaries with:
 *
 * | Constant                              | Module                                                              |
 * |---------------------------------------|---------------------------------------------------------------------|
 * | [INTEGRATION_RECEIVER]               | [com.ufo.galaxy.agent.DelegatedRuntimeReceiver]                    |
 * | [INTEGRATION_UNIT]                   | [com.ufo.galaxy.agent.DelegatedRuntimeUnit]                        |
 * | [INTEGRATION_ACTIVATION_RECORD]      | [com.ufo.galaxy.runtime.DelegatedActivationRecord]                 |
 * | [INTEGRATION_PIPELINE]               | [com.ufo.galaxy.agent.AutonomousExecutionPipeline]                 |
 * | [INTEGRATION_LOOP_CONTROLLER]        | [com.ufo.galaxy.loop.LoopController]                               |
 * | [INTEGRATION_TAKEOVER_EXECUTOR]      | [com.ufo.galaxy.agent.DelegatedTakeoverExecutor]                   |
 * | [INTEGRATION_COLLABORATION_AGENT]    | [com.ufo.galaxy.agent.LocalCollaborationAgent]                     |
 *
 * @see AndroidLocalTruthOwner
 * @see LocalTruthOwnershipClassification
 */
sealed class LocalTruthEmitDecision {

    /**
     * Stable wire tag identifying this decision variant for structured logs and wire maps.
     */
    abstract val wireTag: String

    /**
     * The [LocalTruthOwnershipClassification] tier that this decision was derived from.
     * Always present; callers may use it for metrics and audit trails.
     */
    abstract val classification: LocalTruthOwnershipClassification

    // ── Emit decisions ────────────────────────────────────────────────────────

    /**
     * Caller MUST emit the truth signal immediately as the strongest Android-side claim.
     *
     * Applies when [classification] is
     * [LocalTruthOwnershipClassification.LOCAL_AUTHORITATIVE_ASSERTION] or
     * [LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM], and no suppression
     * condition is active.
     *
     * @property classification Always [LocalTruthOwnershipClassification.LOCAL_AUTHORITATIVE_ASSERTION]
     *                          or [LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM].
     * @property reason         Human-readable explanation of why this emission is authoritative.
     */
    data class EmitAsAuthoritativeLocalTruth(
        override val classification: LocalTruthOwnershipClassification,
        val reason: String
    ) : LocalTruthEmitDecision() {
        override val wireTag: String = WIRE_TAG_EMIT_AUTHORITATIVE
    }

    /**
     * Caller MUST emit the truth signal as non-final execution evidence.
     *
     * Applies when [classification] is
     * [LocalTruthOwnershipClassification.EXECUTION_EVIDENCE].  V2 should record
     * this as supporting signal data but must not treat it as closing the execution
     * epoch.
     *
     * @property classification Always [LocalTruthOwnershipClassification.EXECUTION_EVIDENCE].
     * @property reason         Human-readable explanation.
     */
    data class EmitAsExecutionEvidence(
        override val classification: LocalTruthOwnershipClassification,
        val reason: String
    ) : LocalTruthEmitDecision() {
        override val wireTag: String = WIRE_TAG_EMIT_EVIDENCE
    }

    /**
     * Caller MAY emit the truth signal as advisory context that V2 can use or discard.
     *
     * Applies when [classification] is
     * [LocalTruthOwnershipClassification.ADVISORY_LOCAL_TRUTH] or
     * [LocalTruthOwnershipClassification.PARTIAL_RESULT_TRUTH].
     *
     * @property classification [LocalTruthOwnershipClassification.ADVISORY_LOCAL_TRUTH] or
     *                          [LocalTruthOwnershipClassification.PARTIAL_RESULT_TRUTH].
     * @property reason         Human-readable explanation.
     */
    data class EmitAsAdvisoryOnly(
        override val classification: LocalTruthOwnershipClassification,
        val reason: String
    ) : LocalTruthEmitDecision() {
        override val wireTag: String = WIRE_TAG_EMIT_ADVISORY
    }

    // ── Suppress decisions ────────────────────────────────────────────────────

    /**
     * Caller MUST NOT emit.  A local terminal claim was already emitted for this
     * execution unit, so any further emission would create a duplicate or post-terminal
     * signal that would contradict the canonical terminal truth already sent to V2.
     *
     * @property classification The classification of the suppressed signal.
     * @property unitId         The delegated unit identifier whose terminal state triggered suppression.
     * @property reason         Human-readable explanation of why this emit is suppressed.
     */
    data class SuppressDueToLocalTerminal(
        override val classification: LocalTruthOwnershipClassification,
        val unitId: String,
        val reason: String
    ) : LocalTruthEmitDecision() {
        override val wireTag: String = WIRE_TAG_SUPPRESS_TERMINAL
    }

    /**
     * Caller MUST NOT emit.  The current [SourceRuntimePosture] has changed since the
     * truth was produced, making a [LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH]
     * stale.
     *
     * @property classification Always [LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH].
     * @property producedUnderPosture The posture value that was active when the truth was produced.
     * @property currentPosture       The posture value that is active at classification time.
     * @property reason               Human-readable explanation.
     */
    data class SuppressDueToPostureConflict(
        override val classification: LocalTruthOwnershipClassification,
        val producedUnderPosture: String,
        val currentPosture: String,
        val reason: String
    ) : LocalTruthEmitDecision() {
        override val wireTag: String = WIRE_TAG_SUPPRESS_POSTURE
    }

    // ── Hold decision ─────────────────────────────────────────────────────────

    /**
     * Caller MUST buffer this truth and not emit until the central alignment gate
     * is opened.
     *
     * Applies when the post-resume / reconnect alignment gate is closed
     * ([AndroidLocalTruthOwner.isCentralAlignmentGateClosed] is `true`).  The caller
     * should hold the signal in a local buffer and re-submit it to [AndroidLocalTruthOwner]
     * once [AndroidLocalTruthOwner.openCentralAlignmentGate] is called.
     *
     * @property classification The classification of the held signal.
     * @property reason         Human-readable explanation of why the gate is closed.
     */
    data class HoldForCentralAlignment(
        override val classification: LocalTruthOwnershipClassification,
        val reason: String
    ) : LocalTruthEmitDecision() {
        override val wireTag: String = WIRE_TAG_HOLD_ALIGNMENT
    }

    // ── Companion — wire tags and integration point constants ─────────────────

    companion object {

        // ── Wire tags ─────────────────────────────────────────────────────────

        /** Wire tag for [EmitAsAuthoritativeLocalTruth]. */
        const val WIRE_TAG_EMIT_AUTHORITATIVE = "emit_as_authoritative_local_truth"

        /** Wire tag for [EmitAsExecutionEvidence]. */
        const val WIRE_TAG_EMIT_EVIDENCE = "emit_as_execution_evidence"

        /** Wire tag for [EmitAsAdvisoryOnly]. */
        const val WIRE_TAG_EMIT_ADVISORY = "emit_as_advisory_only"

        /** Wire tag for [SuppressDueToLocalTerminal]. */
        const val WIRE_TAG_SUPPRESS_TERMINAL = "suppress_due_to_local_terminal"

        /** Wire tag for [SuppressDueToPostureConflict]. */
        const val WIRE_TAG_SUPPRESS_POSTURE = "suppress_due_to_posture_conflict"

        /** Wire tag for [HoldForCentralAlignment]. */
        const val WIRE_TAG_HOLD_ALIGNMENT = "hold_for_central_alignment"

        // ── Integration point constants ───────────────────────────────────────

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedRuntimeReceiver].
         *
         * The receiver must call [AndroidLocalTruthOwner.classifyAndGate] before emitting
         * any acceptance or rejection truth outward.
         */
        const val INTEGRATION_RECEIVER = "DelegatedRuntimeReceiver"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedRuntimeUnit].
         *
         * Unit-level metadata that carries truth context (e.g. session binding, posture)
         * must flow through the owner's classification before any downstream use.
         */
        const val INTEGRATION_UNIT = "DelegatedRuntimeUnit"

        /**
         * Integration point identifier for [com.ufo.galaxy.runtime.DelegatedActivationRecord].
         *
         * Activation status transitions that are truth-relevant (COMPLETED, FAILED, REJECTED)
         * must be classified through [AndroidLocalTruthOwner.classifyAndGate].
         */
        const val INTEGRATION_ACTIVATION_RECORD = "DelegatedActivationRecord"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.AutonomousExecutionPipeline].
         *
         * Pipeline result and signal emissions must pass through the owner before outbound
         * dispatch.
         */
        const val INTEGRATION_PIPELINE = "AutonomousExecutionPipeline"

        /**
         * Integration point identifier for [com.ufo.galaxy.loop.LoopController].
         *
         * Loop-level truth signals (stagnation, replan, step completion) must be classified
         * through the owner before outbound dispatch.
         */
        const val INTEGRATION_LOOP_CONTROLLER = "LoopController"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedTakeoverExecutor].
         *
         * Every ACK, PROGRESS, and RESULT signal emitted by the takeover executor must pass
         * through [AndroidLocalTruthOwner.classifyAndGate] before being dispatched to the
         * signal sink.
         */
        const val INTEGRATION_TAKEOVER_EXECUTOR = "DelegatedTakeoverExecutor"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.LocalCollaborationAgent].
         *
         * Subtask-level partial result and completion signals must be classified through
         * the owner before being dispatched.
         */
        const val INTEGRATION_COLLABORATION_AGENT = "LocalCollaborationAgent"

        /** All defined wire tags in a stable set for validation. */
        val ALL_WIRE_TAGS: Set<String> = setOf(
            WIRE_TAG_EMIT_AUTHORITATIVE,
            WIRE_TAG_EMIT_EVIDENCE,
            WIRE_TAG_EMIT_ADVISORY,
            WIRE_TAG_SUPPRESS_TERMINAL,
            WIRE_TAG_SUPPRESS_POSTURE,
            WIRE_TAG_HOLD_ALIGNMENT
        )

        /** All integration point constants in a stable set for validation. */
        val ALL_INTEGRATION_POINTS: Set<String> = setOf(
            INTEGRATION_RECEIVER,
            INTEGRATION_UNIT,
            INTEGRATION_ACTIVATION_RECORD,
            INTEGRATION_PIPELINE,
            INTEGRATION_LOOP_CONTROLLER,
            INTEGRATION_TAKEOVER_EXECUTOR,
            INTEGRATION_COLLABORATION_AGENT
        )
    }
}
