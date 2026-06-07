package com.ufo.galaxy.runtime

/**
 * PR-75 (Android PR-A3) — Participant Execution Signal Contract.
 *
 * The **single canonical execution signal contract** that unifies all Android-originated
 * delegated, takeover, and reconciliation signals under one model.
 *
 * ## Problem addressed
 *
 * Before PR-75, Android maintained two parallel signal representations for the same
 * delegated execution lifecycle:
 *
 *  - [DelegatedExecutionSignal] — emitted by [com.ufo.galaxy.agent.DelegatedTakeoverExecutor]
 *    via [DelegatedExecutionSignalSink] → wire.  Covers ACK / PROGRESS / RESULT.
 *  - [ReconciliationSignal] — emitted by [RuntimeController] via the reconciliation
 *    SharedFlow → wire.  Covers TASK_ACCEPTED / TASK_STATUS_UPDATE / TASK_RESULT /
 *    TASK_CANCELLED / TASK_FAILED / PARTICIPANT_STATE / RUNTIME_TRUTH_SNAPSHOT.
 *
 * Additionally, [com.ufo.galaxy.agent.TakeoverResponseEnvelope] expressed acceptance /
 * rejection of a takeover request without a shared classification model.
 *
 * Each type used different lifecycle naming, which allowed Android-side handlers,
 * coordinators, and reducers to assign **independent local meanings** to what are
 * semantically the same lifecycle phases — a parallel delegated lifecycle reality
 * outside the center-side canonical execution chain.
 *
 * ## Solution
 *
 * [ParticipantExecutionSignalContract] defines a single [ExecSignalClass] classification
 * enum whose four values precisely capture the participant-truth role of every signal:
 *
 * | [ExecSignalClass]      | What it means                                               |
 * |------------------------|-------------------------------------------------------------|
 * | [ExecSignalClass.ACK]  | Receipt acknowledgment — Android accepted the request;      |
 * |                        | execution has not yet started.  Non-terminal.               |
 * | [ExecSignalClass.NON_TERMINAL] | Execution is actively running; task is not closed. |
 * | [ExecSignalClass.TERMINAL]     | Execution has reached a terminal state; task is closed.|
 * | [ExecSignalClass.RECONCILE_ONLY] | State-sync only; not scoped to a specific execution  |
 * |                        | lifecycle transition and does not open or close a task.     |
 *
 * Both [DelegatedExecutionSignal] and [ReconciliationSignal] expose a
 * `participantExecSignalClass: ExecSignalClass` computed property whose value is derived
 * from the contract table below.  [com.ufo.galaxy.agent.TakeoverResponseEnvelope] also
 * exposes the same property for the takeover acceptance / rejection decision.
 *
 * ## Canonical classification table
 *
 * ### DelegatedExecutionSignal
 *
 * | [DelegatedExecutionSignal.Kind] | [ExecSignalClass]           |
 * |---------------------------------|-----------------------------|
 * | ACK                             | ACK                         |
 * | PROGRESS                        | NON_TERMINAL                |
 * | RESULT                          | TERMINAL                    |
 *
 * All [DelegatedExecutionSignal.ResultKind] values (COMPLETED, FAILED, TIMEOUT,
 * CANCELLED, REJECTED) map to [ExecSignalClass.TERMINAL] without exception.
 *
 * ### ReconciliationSignal
 *
 * | [ReconciliationSignal.Kind]     | [ExecSignalClass]           |
 * |---------------------------------|-----------------------------|
 * | TASK_ACCEPTED                   | ACK                         |
 * | TASK_STATUS_UPDATE              | NON_TERMINAL                |
 * | TASK_RESULT                     | TERMINAL                    |
 * | TASK_CANCELLED                  | TERMINAL                    |
 * | TASK_FAILED                     | TERMINAL                    |
 * | PARTICIPANT_STATE               | RECONCILE_ONLY              |
 * | RUNTIME_TRUTH_SNAPSHOT          | RECONCILE_ONLY              |
 *
 * ### TakeoverResponseEnvelope
 *
 * | [com.ufo.galaxy.agent.TakeoverResponseEnvelope.accepted] | [ExecSignalClass] |
 * |-----------------------------------------------------------|-------------------|
 * | `true`                                                    | ACK               |
 * | `false`                                                   | TERMINAL          |
 *
 * An accepted takeover response is classified as [ExecSignalClass.ACK] because it
 * acknowledges that Android has agreed to host the execution — the execution itself
 * has not yet started.  A rejected takeover response is [ExecSignalClass.TERMINAL]
 * because no execution will ever occur for that request.
 *
 * ## Mandate
 *
 * Signals classified [ExecSignalClass.TERMINAL] are the **only** signals that may
 * close a V2 task or participant execution slot.  Signals classified
 * [ExecSignalClass.ACK] or [ExecSignalClass.NON_TERMINAL] MUST NOT be used as
 * terminal completion evidence.  Signals classified [ExecSignalClass.RECONCILE_ONLY]
 * carry no execution lifecycle semantics at all and MUST NOT be treated as task
 * openers or closers.
 *
 * Android MUST NOT derive independent system-completion or system-lifecycle meaning
 * from signals in any class other than [ExecSignalClass.TERMINAL].
 *
 * @see DelegatedExecutionSignal
 * @see ReconciliationSignal
 * @see com.ufo.galaxy.agent.TakeoverResponseEnvelope
 */
object ParticipantExecutionSignalContract {

    /** Android PR number that introduced this contract. */
    const val INTRODUCED_PR = 75

    // ── ExecSignalClass ───────────────────────────────────────────────────────

    /**
     * Canonical execution-signal classification applied uniformly to all three
     * Android signal types: [DelegatedExecutionSignal], [ReconciliationSignal], and
     * [com.ufo.galaxy.agent.TakeoverResponseEnvelope].
     *
     * @property wireValue Stable lowercase string for wire transmission and structured logging.
     * @property isAck            `true` for [ACK] only.
     * @property isNonTerminal    `true` for [NON_TERMINAL] only.
     * @property isTerminal       `true` for [TERMINAL] only.
     * @property isReconcileOnly  `true` for [RECONCILE_ONLY] only.
     * @property participatesInExecutionLifecycle `true` when this class can open or advance
     *                            a task's execution lifecycle (ACK, NON_TERMINAL, TERMINAL).
     *                            `false` for RECONCILE_ONLY, which carries no lifecycle intent.
     */
    enum class ExecSignalClass(
        val wireValue: String,
        val isAck: Boolean,
        val isNonTerminal: Boolean,
        val isTerminal: Boolean,
        val isReconcileOnly: Boolean
    ) {

        /**
         * Receipt acknowledgment — Android accepted the request; execution has not yet started.
         *
         * An ACK signal informs the center that Android took ownership of a delegated unit or
         * a takeover request.  It MUST NOT imply that any execution step has completed, and
         * MUST NOT be treated as a terminal or completion signal.
         *
         * Examples:
         *  - [DelegatedExecutionSignal.Kind.ACK]
         *  - [ReconciliationSignal.Kind.TASK_ACCEPTED]
         *  - [com.ufo.galaxy.agent.TakeoverResponseEnvelope] with `accepted = true`
         */
        ACK(
            wireValue = "ack",
            isAck = true,
            isNonTerminal = false,
            isTerminal = false,
            isReconcileOnly = false
        ),

        /**
         * Non-terminal progress — execution is actively running; the task is not closed.
         *
         * A NON_TERMINAL signal reports forward progress without closing the execution slot.
         * The center MUST NOT treat a NON_TERMINAL signal as completion evidence.
         *
         * Examples:
         *  - [DelegatedExecutionSignal.Kind.PROGRESS]
         *  - [ReconciliationSignal.Kind.TASK_STATUS_UPDATE]
         */
        NON_TERMINAL(
            wireValue = "non_terminal",
            isAck = false,
            isNonTerminal = true,
            isTerminal = false,
            isReconcileOnly = false
        ),

        /**
         * Terminal outcome — execution has reached a final state; the task is closed.
         *
         * A TERMINAL signal is the **only** class that may close a V2 task or participant
         * execution slot.  All terminal outcomes — success, failure, timeout, cancellation,
         * and rejection — share this class.  The specific outcome is carried by the
         * signal's own fields (e.g. [DelegatedExecutionSignal.resultKind] or
         * [ReconciliationSignal.kind]).
         *
         * Examples:
         *  - [DelegatedExecutionSignal.Kind.RESULT] (any [DelegatedExecutionSignal.ResultKind])
         *  - [ReconciliationSignal.Kind.TASK_RESULT]
         *  - [ReconciliationSignal.Kind.TASK_CANCELLED]
         *  - [ReconciliationSignal.Kind.TASK_FAILED]
         *  - [com.ufo.galaxy.agent.TakeoverResponseEnvelope] with `accepted = false`
         */
        TERMINAL(
            wireValue = "terminal",
            isAck = false,
            isNonTerminal = false,
            isTerminal = true,
            isReconcileOnly = false
        ),

        /**
         * Reconcile-only state sync — not scoped to a specific execution lifecycle transition.
         *
         * A RECONCILE_ONLY signal carries participant or snapshot state that V2 should use
         * for truth reconciliation without opening or closing a task.  Android MUST NOT
         * assign execution-lifecycle meaning to RECONCILE_ONLY signals, and the center MUST
         * NOT use them as task openers or closers.
         *
         * Examples:
         *  - [ReconciliationSignal.Kind.PARTICIPANT_STATE]
         *  - [ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT]
         */
        RECONCILE_ONLY(
            wireValue = "reconcile_only",
            isAck = false,
            isNonTerminal = false,
            isTerminal = false,
            isReconcileOnly = true
        );

        /**
         * `true` when this class is part of the execution lifecycle arc
         * (ACK → NON_TERMINAL → TERMINAL).
         *
         * `false` for [RECONCILE_ONLY], which is a state-sync side channel with no
         * execution lifecycle semantics.
         */
        val participatesInExecutionLifecycle: Boolean
            get() = !isReconcileOnly

        companion object {
            /**
             * Returns the [ExecSignalClass] for the given [wireValue], or `null` for unknown values.
             */
            fun fromWireValue(value: String?): ExecSignalClass? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this classification. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Classification helpers ────────────────────────────────────────────────

    /**
     * Returns the canonical [ExecSignalClass] for a given [DelegatedExecutionSignal].
     *
     * This is the single authoritative mapping from the [DelegatedExecutionSignal.Kind]
     * discriminator to the participant execution signal contract.  All [DelegatedExecutionSignal]
     * instances should surface this via their [DelegatedExecutionSignal.participantExecSignalClass]
     * computed property.
     */
    fun classifyDelegated(kind: DelegatedExecutionSignal.Kind): ExecSignalClass =
        when (kind) {
            DelegatedExecutionSignal.Kind.ACK      -> ExecSignalClass.ACK
            DelegatedExecutionSignal.Kind.PROGRESS -> ExecSignalClass.NON_TERMINAL
            DelegatedExecutionSignal.Kind.RESULT   -> ExecSignalClass.TERMINAL
        }

    /**
     * Returns the canonical [ExecSignalClass] for a given [ReconciliationSignal].
     *
     * This is the single authoritative mapping from the [ReconciliationSignal.Kind]
     * discriminator to the participant execution signal contract.  All [ReconciliationSignal]
     * instances should surface this via their [ReconciliationSignal.participantExecSignalClass]
     * computed property.
     */
    fun classifyReconciliation(kind: ReconciliationSignal.Kind): ExecSignalClass =
        when (kind) {
            ReconciliationSignal.Kind.TASK_ACCEPTED          -> ExecSignalClass.ACK
            ReconciliationSignal.Kind.TASK_STATUS_UPDATE     -> ExecSignalClass.NON_TERMINAL
            ReconciliationSignal.Kind.TASK_RESULT            -> ExecSignalClass.TERMINAL
            ReconciliationSignal.Kind.TASK_CANCELLED         -> ExecSignalClass.TERMINAL
            ReconciliationSignal.Kind.TASK_FAILED            -> ExecSignalClass.TERMINAL
            ReconciliationSignal.Kind.PARTICIPANT_STATE      -> ExecSignalClass.RECONCILE_ONLY
            ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT -> ExecSignalClass.RECONCILE_ONLY
        }

    /**
     * Returns the canonical [ExecSignalClass] for a takeover response acceptance flag.
     *
     * An accepted takeover response (`accepted = true`) is classified as [ExecSignalClass.ACK]
     * because Android has taken ownership but execution has not yet started.
     *
     * A rejected takeover response (`accepted = false`) is classified as
     * [ExecSignalClass.TERMINAL] because no execution will occur for that request.
     */
    fun classifyTakeoverResponse(accepted: Boolean): ExecSignalClass =
        if (accepted) ExecSignalClass.ACK else ExecSignalClass.TERMINAL

    // ── Contract wire map ─────────────────────────────────────────────────────

    /**
     * Produces a stable machine-consumable map of key contract assertions.
     *
     * Suitable for structured telemetry, audit trails, and cross-repo alignment checks.
     */
    fun buildContractWireMap(): Map<String, Any> = mapOf(
        "introduced_pr" to INTRODUCED_PR,
        "schema_version" to "1.0",
        "signal_classes" to ExecSignalClass.ALL_WIRE_VALUES.toList().sorted(),
        "delegated_ack_class" to ExecSignalClass.ACK.wireValue,
        "delegated_progress_class" to ExecSignalClass.NON_TERMINAL.wireValue,
        "delegated_result_class" to ExecSignalClass.TERMINAL.wireValue,
        "reconciliation_task_accepted_class" to ExecSignalClass.ACK.wireValue,
        "reconciliation_task_status_update_class" to ExecSignalClass.NON_TERMINAL.wireValue,
        "reconciliation_task_result_class" to ExecSignalClass.TERMINAL.wireValue,
        "reconciliation_task_cancelled_class" to ExecSignalClass.TERMINAL.wireValue,
        "reconciliation_task_failed_class" to ExecSignalClass.TERMINAL.wireValue,
        "reconciliation_participant_state_class" to ExecSignalClass.RECONCILE_ONLY.wireValue,
        "reconciliation_runtime_truth_snapshot_class" to ExecSignalClass.RECONCILE_ONLY.wireValue,
        "takeover_accepted_class" to ExecSignalClass.ACK.wireValue,
        "takeover_rejected_class" to ExecSignalClass.TERMINAL.wireValue,
        "terminal_is_only_task_closer" to true,
        "ack_must_not_imply_terminal_completion" to true,
        "reconcile_only_has_no_lifecycle_semantics" to true,
        "android_must_not_assign_independent_lifecycle_meaning" to true
    )
}
