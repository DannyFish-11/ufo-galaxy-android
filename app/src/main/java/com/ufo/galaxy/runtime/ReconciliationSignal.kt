package com.ufo.galaxy.runtime

/**
 * PR-51 — Android Reconciliation Signal.
 *
 * A structured, protocol-safe wrapper for any Android-originated signal that V2 must
 * reconcile into its canonical orchestration truth.  Provides a single, typed channel
 * for all cancel, status, failure, and result signals — distinct from
 * [AndroidSessionContribution] (which is the terminal result envelope) in that
 * [ReconciliationSignal] covers **all phases** of a task's lifecycle, including
 * in-progress status updates and pre-terminal cancellation/failure notifications.
 *
 * ## Problem addressed
 *
 * [AndroidSessionContribution] models Android's **terminal** task contribution to session
 * truth.  However, V2's reconciliation loop also needs:
 *  - **In-progress status** — V2 should know a task is actively executing before the result.
 *  - **Pre-terminal cancellation** — V2 must receive a cancel signal as soon as Android
 *    begins stopping a task, not only after execution fully terminates.
 *  - **Pre-terminal failure** — V2 must be notified of failure conditions before Android
 *    produces the final [AndroidSessionContribution.Kind.FAILURE] contribution.
 *  - **Participant state change** — when participant state changes (health, readiness,
 *    posture) V2 needs a structured signal to update its canonical view immediately.
 *
 * [ReconciliationSignal] fills this gap.  It is the first-class protocol vehicle for
 * all Android→V2 signals that must update V2's canonical orchestration truth.
 *
 * ## Kind taxonomy
 *
 * | [Kind]                        | When emitted                                                         | V2 action                        |
 * |-------------------------------|----------------------------------------------------------------------|----------------------------------|
 * | [Kind.TASK_ACCEPTED]          | Android accepted a delegated task and started execution              | Mark task as in-progress         |
 * | [Kind.TASK_STATUS_UPDATE]     | Android reports an intermediate execution status for a running task  | Update task progress tracking    |
 * | [Kind.TASK_RESULT]            | Android completed a task successfully                                | Close task as success            |
 * | [Kind.TASK_CANCELLED]         | Android cancelled a running task (explicit or preempted)             | Close task as cancelled          |
 * | [Kind.TASK_FAILED]            | Android failed a task (error, timeout, pipeline fault)               | Close task as failed             |
 * | [Kind.PARTICIPANT_STATE]      | Android participant state changed (health, readiness, posture)        | Update participant truth         |
 * | [Kind.RUNTIME_TRUTH_SNAPSHOT] | Android publishes a consolidated [AndroidParticipantRuntimeTruth]    | Full reconciliation pass         |
 *
 * ## Reconciliation protocol
 *
 * V2 should consume [ReconciliationSignal] events as a **structured input stream** to
 * its participant truth reconciliation loop.  The protocol is:
 *
 * 1. [Kind.TASK_ACCEPTED] — V2 marks the task as active under this participant.
 * 2. [Kind.TASK_STATUS_UPDATE] — V2 updates its in-flight view without closing the task.
 * 3. [Kind.TASK_RESULT] / [Kind.TASK_CANCELLED] / [Kind.TASK_FAILED] — V2 closes the task
 *    with the specified outcome and updates the participant's contribution record.
 * 4. [Kind.PARTICIPANT_STATE] — V2 updates its canonical participant view independently of
 *    any in-flight task.
 * 5. [Kind.RUNTIME_TRUTH_SNAPSHOT] — V2 reconciles its full canonical participant view
 *    against the snapshot; any V2-held state that conflicts with the snapshot must be
 *    resolved in favour of the snapshot (Android owns its local truth).
 *
 * ## Responsibility boundary
 *
 * [ReconciliationSignal] travels from Android to V2.  Android owns the content of each
 * signal; V2 owns the canonical reconciliation decision (what to do with the signal).
 * Android must not rely on V2's reconciliation state — only V2 holds the global orchestration
 * truth.
 *
 * @property kind             Signal taxonomy discriminator.
 * @property participantId    Stable participant node identifier for routing by V2.
 * @property taskId           Task this signal belongs to; null for participant-state signals.
 * @property correlationId    Optional correlation identifier echoed from the originating request.
 * @property status           Wire-level status string; matches [AndroidSessionContribution] status constants.
 * @property payload          Optional free-form payload map carrying signal-specific fields.
 * @property runtimeTruth     Populated only for [Kind.RUNTIME_TRUTH_SNAPSHOT] signals.
 * @property signalId         Unique, stable identifier for this signal; used by V2 for deduplication.
 * @property emittedAtMs      Epoch-millisecond timestamp when this signal was emitted.
 * @property reconciliationEpoch Snapshot epoch from the participant's runtime truth clock.
 */
data class ReconciliationSignal(
    val kind: Kind,
    val participantId: String,
    val taskId: String?,
    val correlationId: String?,
    val status: String,
    val payload: Map<String, Any?> = emptyMap(),
    val runtimeTruth: AndroidParticipantRuntimeTruth? = null,
    val signalId: String,
    val emittedAtMs: Long,
    val reconciliationEpoch: Int
) {

    /**
     * Signal kind taxonomy for Android→V2 reconciliation signals.
     *
     * Each kind maps to a specific V2 reconciliation action.
     *
     * @property wireValue Stable lowercase string used in wire-format payloads.
     */
    enum class Kind(val wireValue: String) {

        /**
         * Android accepted a delegated task and execution has begun.
         *
         * V2 should record the task as actively in progress under this participant,
         * blocking duplicate dispatch until a terminal signal ([TASK_RESULT],
         * [TASK_CANCELLED], or [TASK_FAILED]) arrives.
         */
        TASK_ACCEPTED("task_accepted"),

        /**
         * Android reports an intermediate execution status for a currently running task.
         *
         * V2 can use this signal to update its progress view without changing the
         * task's terminal state.  This signal does not indicate task completion.
         */
        TASK_STATUS_UPDATE("task_status_update"),

        /**
         * Android completed a task successfully.
         *
         * Equivalent to [AndroidSessionContribution.Kind.FINAL_COMPLETION] at the
         * session-contribution level.  V2 should close the task as a success and
         * update the participant's contribution record.
         */
        TASK_RESULT("task_result"),

        /**
         * Android cancelled a running task.
         *
         * Equivalent to [AndroidSessionContribution.Kind.CANCELLATION] at the
         * session-contribution level.  V2 should close the task as cancelled and
         * release any reserved execution capacity for this participant.
         *
         * Importantly, this signal is emitted **as soon as Android determines a task
         * will be cancelled** (before the full termination sequence completes), so V2
         * can begin its reconciliation pass immediately.
         */
        TASK_CANCELLED("task_cancelled"),

        /**
         * Android failed a task.
         *
         * Equivalent to [AndroidSessionContribution.Kind.FAILURE] at the session-
         * contribution level.  V2 should close the task as failed and may trigger
         * a fallback or retry according to its orchestration policy.
         *
         * Like [TASK_CANCELLED], this signal is emitted as soon as Android determines
         * a failure condition, before the full termination sequence.
         */
        TASK_FAILED("task_failed"),

        /**
         * Android participant state changed: health, readiness, or posture.
         *
         * V2 should update its canonical participant view to reflect the new participant
         * state.  This signal is independent of any in-flight task and may be emitted
         * at any time during the participant's lifecycle.
         */
        PARTICIPANT_STATE("participant_state"),

        /**
         * Android publishes a consolidated [AndroidParticipantRuntimeTruth] snapshot.
         *
         * V2 should perform a full reconciliation pass against [runtimeTruth], treating
         * the snapshot as Android's authoritative self-report.  Any V2-held state that
         * conflicts with the snapshot must be resolved in favour of the snapshot.
         *
         * This signal is emitted periodically or in response to a V2 reconciliation
         * request.
         */
        RUNTIME_TRUTH_SNAPSHOT("runtime_truth_snapshot");

        companion object {
            /**
             * Parses [value] to a [Kind], returning null for unknown values.
             *
             * @param value Wire string from a reconciliation signal; may be null.
             */
            fun fromValue(value: String?): Kind? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * Returns `true` when this signal represents a terminal task outcome that closes
     * the V2 reconciliation loop for the associated task.
     *
     * Terminal signals: [Kind.TASK_RESULT], [Kind.TASK_CANCELLED], [Kind.TASK_FAILED].
     */
    val isTerminal: Boolean
        get() = kind == Kind.TASK_RESULT ||
            kind == Kind.TASK_CANCELLED ||
            kind == Kind.TASK_FAILED

    /**
     * Returns `true` when this signal carries a full [AndroidParticipantRuntimeTruth]
     * snapshot suitable for a full V2 reconciliation pass.
     */
    val hasRuntimeTruth: Boolean
        get() = kind == Kind.RUNTIME_TRUTH_SNAPSHOT && runtimeTruth != null

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {

        // ── Wire key constants ────────────────────────────────────────────────

        /** Wire key for [kind] ([Kind.wireValue]). */
        const val KEY_KIND = "reconciliation_signal_kind"

        /** Wire key for [participantId]. */
        const val KEY_PARTICIPANT_ID = "reconciliation_participant_id"

        /** Wire key for [taskId]; absent when null. */
        const val KEY_TASK_ID = "reconciliation_task_id"

        /** Wire key for [correlationId]; absent when null. */
        const val KEY_CORRELATION_ID = "reconciliation_correlation_id"

        /** Wire key for [status]. */
        const val KEY_STATUS = "reconciliation_status"

        /** Wire key for [signalId]. */
        const val KEY_SIGNAL_ID = "reconciliation_signal_id"

        /** Wire key for [emittedAtMs]. */
        const val KEY_EMITTED_AT_MS = "reconciliation_emitted_at_ms"

        /** Wire key for [reconciliationEpoch]. */
        const val KEY_RECONCILIATION_EPOCH = "reconciliation_epoch"

        // ── Status constants (mirror AndroidSessionContribution) ──────────────

        /** Wire status for a running / accepted task. */
        const val STATUS_RUNNING = "running"

        /** Wire status for a completed, successful task. */
        const val STATUS_SUCCESS = "success"

        /** Wire status for a cancelled task. */
        const val STATUS_CANCELLED = "cancelled"

        /** Wire status for a failed task. */
        const val STATUS_FAILED = "failed"

        /** Wire status for an intermediate status update. */
        const val STATUS_IN_PROGRESS = "in_progress"

        /** Wire status for a participant state change signal (no task outcome). */
        const val STATUS_STATE_CHANGED = "state_changed"

        /** Wire status for a full snapshot signal. */
        const val STATUS_SNAPSHOT = "snapshot"

        // ── Factories ─────────────────────────────────────────────────────────

        /**
         * Creates a [Kind.TASK_ACCEPTED] signal indicating Android accepted a delegated task.
         *
         * @param participantId Stable participant node identifier.
         * @param taskId        Task identifier from the inbound dispatch envelope.
         * @param correlationId Correlation identifier from the inbound envelope.
         * @param signalId      Unique signal identifier for deduplication.
         * @param reconciliationEpoch Snapshot epoch from the participant's truth clock.
         */
        fun taskAccepted(
            participantId: String,
            taskId: String,
            correlationId: String? = null,
            signalId: String = java.util.UUID.randomUUID().toString(),
            reconciliationEpoch: Int = 0
        ): ReconciliationSignal = ReconciliationSignal(
            kind = Kind.TASK_ACCEPTED,
            participantId = participantId,
            taskId = taskId,
            correlationId = correlationId,
            status = STATUS_RUNNING,
            signalId = signalId,
            emittedAtMs = System.currentTimeMillis(),
            reconciliationEpoch = reconciliationEpoch
        )

        /**
         * Creates a [Kind.TASK_CANCELLED] signal indicating Android cancelled a running task.
         *
         * @param participantId Stable participant node identifier.
         * @param taskId        Task identifier of the cancelled task.
         * @param correlationId Correlation identifier from the originating request.
         * @param signalId      Unique signal identifier for deduplication.
         * @param reconciliationEpoch Snapshot epoch from the participant's truth clock.
         */
        fun taskCancelled(
            participantId: String,
            taskId: String,
            correlationId: String? = null,
            signalId: String = java.util.UUID.randomUUID().toString(),
            reconciliationEpoch: Int = 0
        ): ReconciliationSignal = ReconciliationSignal(
            kind = Kind.TASK_CANCELLED,
            participantId = participantId,
            taskId = taskId,
            correlationId = correlationId,
            status = STATUS_CANCELLED,
            signalId = signalId,
            emittedAtMs = System.currentTimeMillis(),
            reconciliationEpoch = reconciliationEpoch
        )

        /**
         * Creates a [Kind.TASK_FAILED] signal indicating Android failed a task.
         *
         * @param participantId Stable participant node identifier.
         * @param taskId        Task identifier of the failed task.
         * @param correlationId Correlation identifier from the originating request.
         * @param errorDetail   Optional human-readable error detail.
         * @param signalId      Unique signal identifier for deduplication.
         * @param reconciliationEpoch Snapshot epoch from the participant's truth clock.
         */
        fun taskFailed(
            participantId: String,
            taskId: String,
            correlationId: String? = null,
            errorDetail: String? = null,
            signalId: String = java.util.UUID.randomUUID().toString(),
            reconciliationEpoch: Int = 0
        ): ReconciliationSignal {
            val payload = buildMap<String, Any?> {
                errorDetail?.let { put("error_detail", it) }
            }
            return ReconciliationSignal(
                kind = Kind.TASK_FAILED,
                participantId = participantId,
                taskId = taskId,
                correlationId = correlationId,
                status = STATUS_FAILED,
                payload = payload,
                signalId = signalId,
                emittedAtMs = System.currentTimeMillis(),
                reconciliationEpoch = reconciliationEpoch
            )
        }

        /**
         * Creates a [Kind.TASK_RESULT] signal indicating Android completed a task successfully.
         *
         * @param participantId Stable participant node identifier.
         * @param taskId        Task identifier of the completed task.
         * @param correlationId Correlation identifier from the originating request.
         * @param signalId      Unique signal identifier for deduplication.
         * @param reconciliationEpoch Snapshot epoch from the participant's truth clock.
         */
        fun taskResult(
            participantId: String,
            taskId: String,
            correlationId: String? = null,
            signalId: String = java.util.UUID.randomUUID().toString(),
            reconciliationEpoch: Int = 0
        ): ReconciliationSignal = ReconciliationSignal(
            kind = Kind.TASK_RESULT,
            participantId = participantId,
            taskId = taskId,
            correlationId = correlationId,
            status = STATUS_SUCCESS,
            signalId = signalId,
            emittedAtMs = System.currentTimeMillis(),
            reconciliationEpoch = reconciliationEpoch
        )

        /**
         * Creates a [Kind.TASK_STATUS_UPDATE] signal reporting an intermediate execution
         * status for a currently running task.
         *
         * V2 should update its in-flight progress view without closing the task.
         *
         * @param participantId Stable participant node identifier.
         * @param taskId        Task identifier of the in-progress task.
         * @param correlationId Correlation identifier from the originating request.
         * @param progressDetail Optional human-readable description of the current progress step.
         * @param signalId      Unique signal identifier for deduplication.
         * @param reconciliationEpoch Snapshot epoch from the participant's truth clock.
         */
        fun taskStatusUpdate(
            participantId: String,
            taskId: String,
            correlationId: String? = null,
            progressDetail: String? = null,
            signalId: String = java.util.UUID.randomUUID().toString(),
            reconciliationEpoch: Int = 0
        ): ReconciliationSignal {
            val payload = buildMap<String, Any?> {
                progressDetail?.let { put("progress_detail", it) }
            }
            return ReconciliationSignal(
                kind = Kind.TASK_STATUS_UPDATE,
                participantId = participantId,
                taskId = taskId,
                correlationId = correlationId,
                status = STATUS_IN_PROGRESS,
                payload = payload,
                signalId = signalId,
                emittedAtMs = System.currentTimeMillis(),
                reconciliationEpoch = reconciliationEpoch
            )
        }

        /**
         * Creates a [Kind.PARTICIPANT_STATE] signal reporting an Android participant state
         * change (health, readiness, or posture).
         *
         * V2 should update its canonical participant view immediately on receipt.
         * This signal is independent of any in-flight task and may be emitted at any time.
         *
         * @param participantId  Stable participant node identifier.
         * @param healthState    Current [ParticipantHealthState] of the participant.
         * @param readinessState Current [ParticipantReadinessState] for dispatch selection.
         * @param posture        Optional posture value from [SourceRuntimePosture] constants
         *                       (e.g. [SourceRuntimePosture.CONTROL_ONLY],
         *                       [SourceRuntimePosture.JOIN_RUNTIME]); `null` when the posture
         *                       is not being reported in this signal.
         * @param signalId       Unique signal identifier for deduplication.
         * @param reconciliationEpoch Snapshot epoch from the participant's truth clock.
         */
        fun participantStateSignal(
            participantId: String,
            healthState: ParticipantHealthState,
            readinessState: ParticipantReadinessState,
            posture: String? = null,
            signalId: String = java.util.UUID.randomUUID().toString(),
            reconciliationEpoch: Int = 0
        ): ReconciliationSignal {
            val payload = buildMap<String, Any?> {
                put("health_state", healthState.wireValue)
                put("readiness_state", readinessState.wireValue)
                posture?.let { put("source_runtime_posture", it) }
            }
            return ReconciliationSignal(
                kind = Kind.PARTICIPANT_STATE,
                participantId = participantId,
                taskId = null,
                correlationId = null,
                status = STATUS_STATE_CHANGED,
                payload = payload,
                signalId = signalId,
                emittedAtMs = System.currentTimeMillis(),
                reconciliationEpoch = reconciliationEpoch
            )
        }

        /**
         * Creates a [Kind.RUNTIME_TRUTH_SNAPSHOT] signal carrying a full participant truth snapshot.
         *
         * @param truth         The [AndroidParticipantRuntimeTruth] snapshot to publish.
         * @param signalId      Unique signal identifier for deduplication.
         */
        fun runtimeTruthSnapshot(
            truth: AndroidParticipantRuntimeTruth,
            signalId: String = java.util.UUID.randomUUID().toString()
        ): ReconciliationSignal = ReconciliationSignal(
            kind = Kind.RUNTIME_TRUTH_SNAPSHOT,
            participantId = truth.participantId,
            taskId = truth.activeTaskId,
            correlationId = null,
            status = STATUS_SNAPSHOT,
            runtimeTruth = truth,
            signalId = signalId,
            emittedAtMs = System.currentTimeMillis(),
            reconciliationEpoch = truth.reconciliationEpoch
        )
    }
}
