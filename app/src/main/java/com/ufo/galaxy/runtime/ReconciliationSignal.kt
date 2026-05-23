package com.ufo.galaxy.runtime

import java.security.MessageDigest

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
 * @property dispatchPlanId   Optional V2 distributed execution dispatch plan identifier echoed from
 *                            the originating [com.ufo.galaxy.protocol.GoalExecutionPayload] or
 *                            [com.ufo.galaxy.agent.TakeoverRequestEnvelope].  When present,
 *                            V2's stricter distributed execution activation path can correlate
 *                            this signal back to the originating dispatch plan record without
 *                            inference from raw task/correlation IDs alone.  Null for legacy
 *                            senders or participant-state / snapshot signals.
 * @property status           Wire-level status string; matches [AndroidSessionContribution] status constants.
 * @property payload          Optional free-form payload map carrying signal-specific fields.
 * @property runtimeTruth     Populated only for [Kind.RUNTIME_TRUTH_SNAPSHOT] signals.
 * @property signalId         Unique, stable identifier for this signal; used by V2 for deduplication.
 * @property emittedAtMs      Epoch-millisecond timestamp when this signal was emitted.
 * @property reconciliationEpoch Snapshot epoch from the participant's runtime truth clock.
 * @property durableSessionId Stable activation-era session identifier, when available.
 * @property sessionContinuityEpoch Monotone reconnect epoch within [durableSessionId], when
 *                                  available.
 */
data class ReconciliationSignal(
    val kind: Kind,
    val participantId: String,
    val taskId: String?,
    val correlationId: String?,
    val dispatchPlanId: String? = null,
    val status: String,
    val payload: Map<String, Any?> = emptyMap(),
    val runtimeTruth: AndroidParticipantRuntimeTruth? = null,
    val signalId: String,
    val emittedAtMs: Long,
    val reconciliationEpoch: Int,
    val durableSessionId: String? = null,
    val sessionContinuityEpoch: Int? = null
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

    /**
     * Canonical participant execution signal class under the unified contract (PR-75).
     *
     * Maps this signal's [kind] to the single shared [ParticipantExecutionSignalContract.ExecSignalClass]
     * that applies uniformly across [ReconciliationSignal], [DelegatedExecutionSignal], and
     * [com.ufo.galaxy.agent.TakeoverResponseEnvelope].
     *
     *  - [Kind.TASK_ACCEPTED]          → [ParticipantExecutionSignalContract.ExecSignalClass.ACK]
     *  - [Kind.TASK_STATUS_UPDATE]     → [ParticipantExecutionSignalContract.ExecSignalClass.NON_TERMINAL]
     *  - [Kind.TASK_RESULT]            → [ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL]
     *  - [Kind.TASK_CANCELLED]         → [ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL]
     *  - [Kind.TASK_FAILED]            → [ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL]
     *  - [Kind.PARTICIPANT_STATE]      → [ParticipantExecutionSignalContract.ExecSignalClass.RECONCILE_ONLY]
     *  - [Kind.RUNTIME_TRUTH_SNAPSHOT] → [ParticipantExecutionSignalContract.ExecSignalClass.RECONCILE_ONLY]
     *
     * Callers MUST use this property — not raw [kind] comparisons — when routing or reducing
     * signals at the execution lifecycle level, so that reconciliation signals are handled
     * identically to the corresponding delegated execution signals.
     */
    val participantExecSignalClass: ParticipantExecutionSignalContract.ExecSignalClass
        get() = ParticipantExecutionSignalContract.classifyReconciliation(kind)

    /**
     * Stable delivery / dedupe key for reliable reconciliation uplink.
     *
     * Unlike [signalId], this key is derived from continuity-bound task/session context so
     * retries, offline replay, and duplicate local emissions for the same logical reconciliation
     * event converge on one idempotent identity.
     */
    val stableDedupeKey: String
        get() = buildStableDedupeKey(
            kind = kind.wireValue,
            participantId = participantId,
            taskId = taskId,
            correlationId = correlationId,
            status = status,
            reconciliationEpoch = reconciliationEpoch,
            durableSessionId = durableSessionId,
            sessionContinuityEpoch = sessionContinuityEpoch,
            payload = payload,
            runtimeTruth = runtimeTruth?.toMap()
        )

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

        /**
         * Wire key for [dispatchPlanId]; absent when null.
         *
         * Present on task lifecycle signals (TASK_ACCEPTED, TASK_STATUS_UPDATE, TASK_RESULT,
         * TASK_CANCELLED, TASK_FAILED) when the originating inbound dispatch envelope carried
         * a V2 dispatch plan identifier.  V2's stricter distributed execution activation path
         * uses this key to correlate the Android task lifecycle stream back to the originating
         * dispatch plan record without relying on field-combination inference.
         */
        const val KEY_DISPATCH_PLAN_ID = "reconciliation_dispatch_plan_id"

        /** Wire key for [status]. */
        const val KEY_STATUS = "reconciliation_status"

        /**
         * Payload key: whether Android has produced a terminal result for this task.
         *
         * `true` for terminal task signals (`task_result` / `task_cancelled` / `task_failed`);
         * `false` for non-terminal lifecycle signals.
         */
        const val KEY_RESULT_RETURNED = "result_returned"

        /**
         * Payload key: whether Android has emitted a completion-side reconciliation signal.
         *
         * `true` for terminal task signals; `false` for accepted/progress/state/snapshot signals.
         */
        const val KEY_COMPLETION_SIGNALED = "completion_signaled"

        /**
         * Payload key: whether this signal alone means closure is ready for central acceptance.
         *
         * Reconciliation terminal signals are pre-acceptance observations on Android and do not
         * unilaterally close central truth, therefore this value is `false` for all task signals.
         */
        const val KEY_CLOSURE_READY_FOR_ACCEPTANCE = "closure_ready_for_acceptance"

        /** Payload key: authority runtime completion signal class. */
        const val KEY_AUTHORITY_RUNTIME_COMPLETION_SIGNAL_CLASS =
            AndroidCompletionClosureUplinkContract.KEY_AUTHORITY_RUNTIME_COMPLETION_SIGNAL_CLASS

        /** Payload key: result completion signal class. */
        const val KEY_RESULT_COMPLETION_SIGNAL_CLASS =
            AndroidCompletionClosureUplinkContract.KEY_RESULT_COMPLETION_SIGNAL_CLASS

        /** Payload key: closure/session-finalization signal class. */
        const val KEY_CLOSURE_FINALIZATION_SIGNAL_CLASS =
            AndroidCompletionClosureUplinkContract.KEY_CLOSURE_FINALIZATION_SIGNAL_CLASS

        /** Payload key: operator-visible done projection class. */
        const val KEY_OPERATOR_DONE_PROJECTION_CLASS =
            AndroidCompletionClosureUplinkContract.KEY_OPERATOR_DONE_PROJECTION_CLASS

        /** Payload key: completion/closure uplink schema version. */
        const val KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION =
            AndroidCompletionClosureUplinkContract.KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION

        /** Payload key: Android-local execution completion state. */
        const val KEY_LOCAL_EXECUTION_COMPLETED =
            AndroidCompletionClosureUplinkContract.KEY_LOCAL_EXECUTION_COMPLETED

        /** Payload key: Android emitted advisory evidence uplink. */
        const val KEY_ADVISORY_EVIDENCE_SENT =
            AndroidCompletionClosureUplinkContract.KEY_ADVISORY_EVIDENCE_SENT

        /** Payload key: V2 uplink acknowledgement state. */
        const val KEY_V2_UPLINK_ACKNOWLEDGED =
            AndroidCompletionClosureUplinkContract.KEY_V2_UPLINK_ACKNOWLEDGED

        /** Payload key: V2 reconciliation acknowledgement state. */
        const val KEY_V2_RECONCILIATION_ACKNOWLEDGED =
            AndroidCompletionClosureUplinkContract.KEY_V2_RECONCILIATION_ACKNOWLEDGED

        /** Payload key: V2 canonical truth completion state. */
        const val KEY_V2_CANONICAL_TRUTH_COMPLETED =
            AndroidCompletionClosureUplinkContract.KEY_V2_CANONICAL_TRUTH_COMPLETED

        /** Payload key: V2 mature closure state. */
        const val KEY_V2_MATURE_CLOSURE_ACHIEVED =
            AndroidCompletionClosureUplinkContract.KEY_V2_MATURE_CLOSURE_ACHIEVED

        /** Payload key: outward truth-surface authority classification. */
        const val KEY_OUTWARD_TRUTH_SURFACE_CLASS =
            AndroidCompletionClosureUplinkContract.KEY_OUTWARD_TRUTH_SURFACE_CLASS

        /** Wire key for [signalId]. */
        const val KEY_SIGNAL_ID = "reconciliation_signal_id"

        /** Wire key for [emittedAtMs]. */
        const val KEY_EMITTED_AT_MS = "reconciliation_emitted_at_ms"

        /** Wire key for [reconciliationEpoch]. */
        const val KEY_RECONCILIATION_EPOCH = "reconciliation_epoch"

        /** Wire key for [durableSessionId]. */
        const val KEY_DURABLE_SESSION_ID = DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID

        /** Wire key for [sessionContinuityEpoch]. */
        const val KEY_SESSION_CONTINUITY_EPOCH =
            DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH

        /** Payload key for [stableDedupeKey]. */
        const val KEY_STABLE_DEDUPE_KEY = "reconciliation_stable_dedupe_key"

        // ── PR-116: Continuity recovery state payload key constants ──────────────────────

        /**
         * Payload key for the unified Android-side continuity recovery phase.
         *
         * Value: one of [AndroidContinuityRecoveryStateModel.RecoveryPhase.wireValue] strings.
         * Populated in [runtimeTruthSnapshot] from the runtimeTruth's inflight continuity state.
         */
        const val KEY_CONTINUITY_RECOVERY_STATE =
            AndroidContinuityRecoveryStateModel.KEY_CONTINUITY_RECOVERY_STATE

        /**
         * Payload key for the source label of the recovery phase observation.
         *
         * Echoes [InflightContinuityRecoverySnapshot.source] for RUNTIME_TRUTH_SNAPSHOT signals.
         */
        const val KEY_CONTINUITY_RECOVERY_SOURCE =
            AndroidContinuityRecoveryStateModel.KEY_CONTINUITY_RECOVERY_SOURCE

        /**
         * Payload key for [AndroidContinuityRecoveryStateModel.SCHEMA_VERSION].
         */
        const val KEY_CONTINUITY_RECOVERY_SCHEMA_VERSION =
            AndroidContinuityRecoveryStateModel.KEY_CONTINUITY_RECOVERY_SCHEMA_VERSION

        const val KEY_EXECUTION_CONTINUITY_CLASS =
            AndroidRuntimeEmissionTruthSemantics.KEY_EXECUTION_CONTINUITY_CLASS

        const val KEY_TERMINAL_EMISSION_CLASS =
            AndroidRuntimeEmissionTruthSemantics.KEY_TERMINAL_EMISSION_CLASS

        const val KEY_TERMINAL_DELIVERY_DISPOSITION =
            AndroidRuntimeEmissionTruthSemantics.KEY_TERMINAL_DELIVERY_DISPOSITION

        const val KEY_RESULT_CONVERGENCE_DECISION =
            AndroidRuntimeEmissionTruthSemantics.KEY_RESULT_CONVERGENCE_DECISION

        const val KEY_RUNTIME_EMISSION_TRUTH_SCHEMA_VERSION =
            AndroidRuntimeEmissionTruthSemantics.KEY_RUNTIME_EMISSION_TRUTH_SCHEMA_VERSION

        // ── PR-119: Cross-repo recovery state routing payload key constants ────────

        /**
         * Payload key for the [AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.wireValue]
         * that V2 should use to route the recovery evidence.
         *
         * Present in [payload] of [Kind.RUNTIME_TRUTH_SNAPSHOT] signals when a
         * [AndroidCrossRepoRecoveryStateRoutingContract.RoutingDecision] is provided.
         */
        const val KEY_V2_ROUTING_CATEGORY =
            AndroidCrossRepoRecoveryStateRoutingContract.KEY_V2_ROUTING_CATEGORY

        /**
         * Payload key indicating whether this recovery phase requires explicit V2 canonical action.
         *
         * Value: `"true"` or `"false"`.
         */
        const val KEY_ROUTING_REQUIRES_V2_ACTION =
            AndroidCrossRepoRecoveryStateRoutingContract.KEY_ROUTING_REQUIRES_V2_ACTION

        /**
         * Payload key indicating whether this recovery phase carries only advisory evidence.
         *
         * Value: `"true"` or `"false"`.
         */
        const val KEY_ROUTING_IS_ADVISORY_ONLY =
            AndroidCrossRepoRecoveryStateRoutingContract.KEY_ROUTING_IS_ADVISORY_ONLY

        /**
         * Payload key indicating whether canonical task closure is blocked for this phase.
         *
         * Value: `"true"` or `"false"`.  When `"true"`, V2 MUST NOT close the task canonically
         * based solely on this recovery evidence.
         */
        const val KEY_ROUTING_CANONICAL_CLOSURE_BLOCKED =
            AndroidCrossRepoRecoveryStateRoutingContract.KEY_ROUTING_CANONICAL_CLOSURE_BLOCKED

        /**
         * Payload key for [AndroidCrossRepoRecoveryStateRoutingContract.SCHEMA_VERSION].
         */
        const val KEY_ROUTING_SCHEMA_VERSION =
            AndroidCrossRepoRecoveryStateRoutingContract.KEY_ROUTING_SCHEMA_VERSION

        // ── PR-63 progress / checkpoint / subtask payload key constants ────────

        /**
         * Payload key for a structured execution checkpoint identifier
         * ([ParticipantProgressCheckpoint.checkpointId]).
         *
         * Present in [payload] of a [Kind.TASK_STATUS_UPDATE] signal that carries a
         * [ParticipantProgressCheckpoint].  V2 can use this key to distinguish structured
         * checkpoint progress updates from plain [progressDetail] string updates.
         */
        const val KEY_CHECKPOINT_ID = ParticipantProgressCheckpoint.KEY_CHECKPOINT_ID

        /**
         * Payload key for the zero-based execution step index carried by a
         * [ParticipantProgressCheckpoint] ([ParticipantProgressCheckpoint.stepIndex]).
         *
         * Present in [payload] when a checkpoint is carried by this signal.
         */
        const val KEY_STEP_INDEX = ParticipantProgressCheckpoint.KEY_STEP_INDEX

        /**
         * Payload key for the optional total step count hint carried by a
         * [ParticipantProgressCheckpoint] ([ParticipantProgressCheckpoint.totalSteps]).
         *
         * Present in [payload] only when [ParticipantProgressCheckpoint.totalSteps] is non-null.
         */
        const val KEY_TOTAL_STEPS = ParticipantProgressCheckpoint.KEY_TOTAL_STEPS

        /**
         * Payload key for the zero-based subtask index carried by a
         * [SubtaskProgressReport] ([SubtaskProgressReport.subtaskIndex]).
         *
         * Present in [payload] when a subtask report is carried by this signal.
         */
        const val KEY_SUBTASK_INDEX = SubtaskProgressReport.KEY_SUBTASK_INDEX

        /**
         * Payload key for the optional subtask total count carried by a
         * [SubtaskProgressReport] ([SubtaskProgressReport.subtaskTotal]).
         *
         * Present in [payload] only when [SubtaskProgressReport.subtaskTotal] is non-null.
         */
        const val KEY_SUBTASK_TOTAL = SubtaskProgressReport.KEY_SUBTASK_TOTAL

        /**
         * Payload key for the subtask status wire value carried by a
         * [SubtaskProgressReport] ([SubtaskProgressReport.SubtaskStatus.wireValue]).
         *
         * Present in [payload] when a subtask report is carried by this signal.
         */
        const val KEY_SUBTASK_STATUS = SubtaskProgressReport.KEY_SUBTASK_STATUS

        /**
         * Payload key for the human-readable subtask label carried by a
         * [SubtaskProgressReport] ([SubtaskProgressReport.subtaskLabel]).
         *
         * Present in [payload] when a subtask report is carried by this signal.
         */
        const val KEY_SUBTASK_LABEL = SubtaskProgressReport.KEY_SUBTASK_LABEL

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

        /**
         * Returns [payload] enriched with [KEY_DISPATCH_PLAN_ID] → [dispatchPlanId] when
         * [dispatchPlanId] is non-null and non-blank, otherwise returns [payload] unchanged.
         */
        private fun withDispatchPlanId(
            payload: Map<String, Any?>,
            dispatchPlanId: String?
        ): Map<String, Any?> = if (!dispatchPlanId.isNullOrBlank()) {
            payload + mapOf(KEY_DISPATCH_PLAN_ID to dispatchPlanId)
        } else payload

        private fun closureSemanticsPayload(
            isTerminalSignal: Boolean,
            resultReturned: Boolean,
            completionSignaled: Boolean,
            closureReadyForAcceptance: Boolean,
            additionalPayload: Map<String, Any?> = emptyMap(),
            outwardTruthSurfaceClass: AndroidCompletionClosureUplinkContract.OutwardTruthSurfaceClass =
                AndroidCompletionClosureUplinkContract.OutwardTruthSurfaceClass
                    .ANDROID_ADVISORY_EVIDENCE
        ): Map<String, Any?> {
            val completionClosure = AndroidCompletionClosureUplinkContract
                .deriveForReconciliationSignal(
                    isTerminalSignal = isTerminalSignal,
                    resultReturned = resultReturned,
                    completionSignaled = completionSignaled,
                    closureReadyForAcceptance = closureReadyForAcceptance
                )
            val v2Boundary = AndroidCompletionClosureUplinkContract
                .deriveV2CanonicalBoundary(
                    localExecutionCompleted = isTerminalSignal && resultReturned && completionSignaled,
                    advisoryEvidenceSent = true,
                    outwardTruthSurfaceClass = outwardTruthSurfaceClass
                )
            return mapOf(
                AndroidCompletionClosureUplinkContract.KEY_SCHEMA_VERSION to
                    AndroidCompletionClosureUplinkContract.PAYLOAD_SCHEMA_VERSION,
                AndroidCompletionClosureUplinkContract.KEY_COMPLETION_CLOSURE_CONTRACT_VERSION to
                    AndroidCompletionClosureUplinkContract.SCHEMA_VERSION,
                KEY_RESULT_RETURNED to resultReturned,
                KEY_COMPLETION_SIGNALED to completionSignaled,
                KEY_CLOSURE_READY_FOR_ACCEPTANCE to closureReadyForAcceptance,
                KEY_AUTHORITY_RUNTIME_COMPLETION_SIGNAL_CLASS to
                    completionClosure.authorityRuntimeCompletionSignalClass.wireValue,
                KEY_RESULT_COMPLETION_SIGNAL_CLASS to
                    completionClosure.resultCompletionSignalClass.wireValue,
                KEY_CLOSURE_FINALIZATION_SIGNAL_CLASS to
                    completionClosure.closureFinalizationSignalClass.wireValue,
                KEY_OPERATOR_DONE_PROJECTION_CLASS to
                    completionClosure.operatorDoneProjectionClass.wireValue,
                KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION to
                    AndroidCompletionClosureUplinkContract.SCHEMA_VERSION,
                KEY_LOCAL_EXECUTION_COMPLETED to v2Boundary.localExecutionCompleted,
                KEY_ADVISORY_EVIDENCE_SENT to v2Boundary.advisoryEvidenceSent,
                KEY_V2_UPLINK_ACKNOWLEDGED to v2Boundary.v2UplinkAcknowledged,
                KEY_V2_RECONCILIATION_ACKNOWLEDGED to v2Boundary.v2ReconciliationAcknowledged,
                KEY_V2_CANONICAL_TRUTH_COMPLETED to v2Boundary.v2CanonicalTruthCompleted,
                KEY_V2_MATURE_CLOSURE_ACHIEVED to v2Boundary.v2MatureClosureAchieved,
                KEY_OUTWARD_TRUTH_SURFACE_CLASS to v2Boundary.outwardTruthSurfaceClass.wireValue,
                AndroidCompletionClosureUplinkContract.KEY_IS_V2_CONFIRMED_CANONICAL_TRUTH to
                    (
                        v2Boundary.outwardTruthSurfaceClass ==
                            AndroidCompletionClosureUplinkContract.OutwardTruthSurfaceClass
                                .V2_CONFIRMED_CANONICAL_TRUTH
                        )
            ) + additionalPayload
        }

        private fun buildStableDedupeKey(
            kind: String,
            participantId: String,
            taskId: String?,
            correlationId: String?,
            status: String,
            reconciliationEpoch: Int,
            durableSessionId: String?,
            sessionContinuityEpoch: Int?,
            payload: Map<String, Any?>,
            runtimeTruth: Map<String, Any>?
        ): String {
            val canonical = listOf(
                "reconciliation_signal",
                kind,
                participantId,
                taskId ?: "no_task",
                correlationId ?: "no_correlation",
                status,
                durableSessionId ?: "no_durable_session",
                sessionContinuityEpoch?.toString() ?: "no_session_epoch",
                reconciliationEpoch.toString(),
                canonicalizeForDedupe(payload),
                canonicalizeForDedupe(runtimeTruth)
            ).joinToString(":")
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(canonical.toByteArray())
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
            return "reconciliation_signal:$hash"
        }

        private fun canonicalizeForDedupe(value: Any?): String = when (value) {
            null -> "null"
            is Map<*, *> -> value.entries
                .sortedBy { it.key?.toString().orEmpty() }
                .joinToString(prefix = "{", postfix = "}") { entry ->
                    "${entry.key}=${canonicalizeForDedupe(entry.value)}"
                }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") {
                canonicalizeForDedupe(it)
            }
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") {
                canonicalizeForDedupe(it)
            }
            else -> value.toString()
        }

        // ── Factories ─────────────────────────────────────────────────────────

        /**
         * Creates a [Kind.TASK_ACCEPTED] signal indicating Android accepted a delegated task.
         *
         * @param participantId Stable participant node identifier.
         * @param taskId        Task identifier from the inbound dispatch envelope.
         * @param correlationId Correlation identifier from the inbound envelope.
         * @param dispatchPlanId Optional V2 distributed execution dispatch plan ID echoed
         *                       from the inbound dispatch envelope; enables V2's stricter
         *                       distributed activation to correlate this signal.
         * @param signalId      Unique signal identifier for deduplication.
         * @param reconciliationEpoch Snapshot epoch from the participant's truth clock.
         * @param durableSessionId Stable activation-era session identifier, when available.
         * @param sessionContinuityEpoch Monotone reconnect epoch within [durableSessionId],
         *                               when available.
         */
        fun taskAccepted(
            participantId: String,
            taskId: String,
            correlationId: String? = null,
            dispatchPlanId: String? = null,
            signalId: String = java.util.UUID.randomUUID().toString(),
            reconciliationEpoch: Int = 0,
            durableSessionId: String? = null,
            sessionContinuityEpoch: Int? = null,
            additionalPayload: Map<String, Any?> = emptyMap()
        ): ReconciliationSignal = ReconciliationSignal(
            kind = Kind.TASK_ACCEPTED,
            participantId = participantId,
            taskId = taskId,
            correlationId = correlationId,
            dispatchPlanId = dispatchPlanId,
            status = STATUS_RUNNING,
            payload = closureSemanticsPayload(
                isTerminalSignal = false,
                resultReturned = false,
                completionSignaled = false,
                closureReadyForAcceptance = false,
                additionalPayload = withDispatchPlanId(additionalPayload, dispatchPlanId)
            ),
            signalId = signalId,
            emittedAtMs = System.currentTimeMillis(),
            reconciliationEpoch = reconciliationEpoch,
            durableSessionId = durableSessionId,
            sessionContinuityEpoch = sessionContinuityEpoch
        )

        /**
         * Creates a [Kind.TASK_CANCELLED] signal indicating Android cancelled a running task.
         *
         * @param participantId Stable participant node identifier.
         * @param taskId        Task identifier of the cancelled task.
         * @param correlationId Correlation identifier from the originating request.
         * @param dispatchPlanId Optional V2 distributed execution dispatch plan ID; when present
         *                       V2 can close the dispatch plan record without field-combination inference.
         * @param signalId      Unique signal identifier for deduplication.
         * @param reconciliationEpoch Snapshot epoch from the participant's truth clock.
         * @param durableSessionId Stable activation-era session identifier, when available.
         * @param sessionContinuityEpoch Monotone reconnect epoch within [durableSessionId],
         *                               when available.
         */
        fun taskCancelled(
            participantId: String,
            taskId: String,
            correlationId: String? = null,
            dispatchPlanId: String? = null,
            signalId: String = java.util.UUID.randomUUID().toString(),
            reconciliationEpoch: Int = 0,
            durableSessionId: String? = null,
            sessionContinuityEpoch: Int? = null,
            additionalPayload: Map<String, Any?> = emptyMap()
        ): ReconciliationSignal = ReconciliationSignal(
            kind = Kind.TASK_CANCELLED,
            participantId = participantId,
            taskId = taskId,
            correlationId = correlationId,
            dispatchPlanId = dispatchPlanId,
            status = STATUS_CANCELLED,
            payload = closureSemanticsPayload(
                isTerminalSignal = true,
                resultReturned = true,
                completionSignaled = true,
                closureReadyForAcceptance = false,
                additionalPayload = withDispatchPlanId(additionalPayload, dispatchPlanId)
            ),
            signalId = signalId,
            emittedAtMs = System.currentTimeMillis(),
            reconciliationEpoch = reconciliationEpoch,
            durableSessionId = durableSessionId,
            sessionContinuityEpoch = sessionContinuityEpoch
        )

        /**
         * Creates a [Kind.TASK_FAILED] signal indicating Android failed a task.
         *
         * @param participantId Stable participant node identifier.
         * @param taskId        Task identifier of the failed task.
         * @param correlationId Correlation identifier from the originating request.
         * @param dispatchPlanId Optional V2 distributed execution dispatch plan ID; when present
         *                       V2 can close the dispatch plan record without field-combination inference.
         * @param errorDetail   Optional human-readable error detail.
         * @param signalId      Unique signal identifier for deduplication.
         * @param reconciliationEpoch Snapshot epoch from the participant's truth clock.
         * @param durableSessionId Stable activation-era session identifier, when available.
         * @param sessionContinuityEpoch Monotone reconnect epoch within [durableSessionId],
         *                               when available.
         */
        fun taskFailed(
            participantId: String,
            taskId: String,
            correlationId: String? = null,
            dispatchPlanId: String? = null,
            errorDetail: String? = null,
            signalId: String = java.util.UUID.randomUUID().toString(),
            reconciliationEpoch: Int = 0,
            durableSessionId: String? = null,
            sessionContinuityEpoch: Int? = null,
            additionalPayload: Map<String, Any?> = emptyMap()
        ): ReconciliationSignal {
            val payload = closureSemanticsPayload(
                isTerminalSignal = true,
                resultReturned = true,
                completionSignaled = true,
                closureReadyForAcceptance = false,
                additionalPayload = withDispatchPlanId(additionalPayload, dispatchPlanId)
            ).toMutableMap().apply {
                errorDetail?.let { put("error_detail", it) }
            }
            return ReconciliationSignal(
                kind = Kind.TASK_FAILED,
                participantId = participantId,
                taskId = taskId,
                correlationId = correlationId,
                dispatchPlanId = dispatchPlanId,
                status = STATUS_FAILED,
                payload = payload,
                signalId = signalId,
                emittedAtMs = System.currentTimeMillis(),
                reconciliationEpoch = reconciliationEpoch,
                durableSessionId = durableSessionId,
                sessionContinuityEpoch = sessionContinuityEpoch
            )
        }

        /**
         * Creates a [Kind.TASK_RESULT] signal indicating Android completed a task successfully.
         *
         * @param participantId Stable participant node identifier.
         * @param taskId        Task identifier of the completed task.
         * @param correlationId Correlation identifier from the originating request.
         * @param dispatchPlanId Optional V2 distributed execution dispatch plan ID; when present
         *                       V2 can close the dispatch plan record without field-combination inference.
         * @param signalId      Unique signal identifier for deduplication.
         * @param reconciliationEpoch Snapshot epoch from the participant's truth clock.
         * @param durableSessionId Stable activation-era session identifier, when available.
         * @param sessionContinuityEpoch Monotone reconnect epoch within [durableSessionId],
         *                               when available.
         */
        fun taskResult(
            participantId: String,
            taskId: String,
            correlationId: String? = null,
            dispatchPlanId: String? = null,
            signalId: String = java.util.UUID.randomUUID().toString(),
            reconciliationEpoch: Int = 0,
            durableSessionId: String? = null,
            sessionContinuityEpoch: Int? = null,
            additionalPayload: Map<String, Any?> = emptyMap()
        ): ReconciliationSignal = ReconciliationSignal(
            kind = Kind.TASK_RESULT,
            participantId = participantId,
            taskId = taskId,
            correlationId = correlationId,
            dispatchPlanId = dispatchPlanId,
            status = STATUS_SUCCESS,
            payload = closureSemanticsPayload(
                isTerminalSignal = true,
                resultReturned = true,
                completionSignaled = true,
                closureReadyForAcceptance = false,
                additionalPayload = withDispatchPlanId(additionalPayload, dispatchPlanId)
            ),
            signalId = signalId,
            emittedAtMs = System.currentTimeMillis(),
            reconciliationEpoch = reconciliationEpoch,
            durableSessionId = durableSessionId,
            sessionContinuityEpoch = sessionContinuityEpoch
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
         * @param dispatchPlanId Optional V2 distributed execution dispatch plan ID; when present
         *                       V2 can correlate this progress update to the originating dispatch plan.
         * @param progressDetail Optional human-readable description of the current progress step.
         * @param signalId      Unique signal identifier for deduplication.
         * @param reconciliationEpoch Snapshot epoch from the participant's truth clock.
         * @param durableSessionId Stable activation-era session identifier, when available.
         * @param sessionContinuityEpoch Monotone reconnect epoch within [durableSessionId],
         *                               when available.
         */
        fun taskStatusUpdate(
            participantId: String,
            taskId: String,
            correlationId: String? = null,
            dispatchPlanId: String? = null,
            progressDetail: String? = null,
            signalId: String = java.util.UUID.randomUUID().toString(),
            reconciliationEpoch: Int = 0,
            durableSessionId: String? = null,
            sessionContinuityEpoch: Int? = null,
            additionalPayload: Map<String, Any?> = emptyMap()
        ): ReconciliationSignal {
            val payload = closureSemanticsPayload(
                isTerminalSignal = false,
                resultReturned = false,
                completionSignaled = false,
                closureReadyForAcceptance = false,
                additionalPayload = withDispatchPlanId(additionalPayload, dispatchPlanId)
            ).toMutableMap().apply {
                progressDetail?.let { put("progress_detail", it) }
            }
            return ReconciliationSignal(
                kind = Kind.TASK_STATUS_UPDATE,
                participantId = participantId,
                taskId = taskId,
                correlationId = correlationId,
                dispatchPlanId = dispatchPlanId,
                status = STATUS_IN_PROGRESS,
                payload = payload,
                signalId = signalId,
                emittedAtMs = System.currentTimeMillis(),
                reconciliationEpoch = reconciliationEpoch,
                durableSessionId = durableSessionId,
                sessionContinuityEpoch = sessionContinuityEpoch
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
         * @param durableSessionId Stable activation-era session identifier, when available.
         * @param sessionContinuityEpoch Monotone reconnect epoch within [durableSessionId],
         *                               when available.
         */
        fun participantStateSignal(
            participantId: String,
            healthState: ParticipantHealthState,
            readinessState: ParticipantReadinessState,
            posture: String? = null,
            signalId: String = java.util.UUID.randomUUID().toString(),
            reconciliationEpoch: Int = 0,
            durableSessionId: String? = null,
            sessionContinuityEpoch: Int? = null,
            additionalPayload: Map<String, Any?> = emptyMap()
        ): ReconciliationSignal {
            val payload = buildMap<String, Any?> {
                putAll(
                    closureSemanticsPayload(
                        isTerminalSignal = false,
                        resultReturned = false,
                        completionSignaled = false,
                        closureReadyForAcceptance = false,
                        additionalPayload = additionalPayload
                    )
                )
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
                reconciliationEpoch = reconciliationEpoch,
                durableSessionId = durableSessionId,
                sessionContinuityEpoch = sessionContinuityEpoch
            )
        }

        /**
         * Creates a [Kind.RUNTIME_TRUTH_SNAPSHOT] signal carrying a full participant truth snapshot.
         *
         * The payload explicitly includes the unified continuity recovery state derived from
         * [AndroidParticipantRuntimeTruth.inflightContinuityState] so V2 can consume
         * recovery evidence directly from the payload without reading nested truth fields.
         *
         * When [v2RoutingDecision] is provided, the payload also includes structured routing
         * metadata from [AndroidCrossRepoRecoveryStateRoutingContract] so V2 can route the
         * recovery evidence to the correct handling path without re-inferring the category
         * from the raw phase value (PR-119, INV-ROUTING-05).
         *
         * @param truth              The [AndroidParticipantRuntimeTruth] snapshot to publish.
         * @param signalId           Unique signal identifier for deduplication.
         * @param durableSessionId   Stable activation-era session identifier, when available.
         * @param sessionContinuityEpoch Monotone reconnect epoch within [durableSessionId],
         *                               when available.
         * @param v2RoutingDecision  Optional routing decision from
         *                           [AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase];
         *                           when provided its wire map is merged into the payload.
         */
        fun runtimeTruthSnapshot(
            truth: AndroidParticipantRuntimeTruth,
            signalId: String = java.util.UUID.randomUUID().toString(),
            durableSessionId: String? = null,
            sessionContinuityEpoch: Int? = null,
            v2RoutingDecision: AndroidCrossRepoRecoveryStateRoutingContract.RoutingDecision? = null,
            additionalPayload: Map<String, Any?> = emptyMap()
        ): ReconciliationSignal {
            val normalizedRecoveryPhase = AndroidContinuityRecoveryStateModel.RecoveryPhase
                .fromWireValue(truth.inflightContinuityState)
            if (v2RoutingDecision != null) {
                requireNotNull(normalizedRecoveryPhase) {
                    "v2RoutingDecision requires a valid inflightContinuityState wire value"
                }
                require(v2RoutingDecision.phase == normalizedRecoveryPhase) {
                    "v2RoutingDecision phase (${v2RoutingDecision.phase.wireValue}) must match " +
                        "truth.inflightContinuityState (${normalizedRecoveryPhase.wireValue})"
                }
            }
            // Promote inflight continuity state into explicit payload keys so V2 can consume
            // recovery evidence directly from the reconciliation signal payload without having
            // to read nested runtimeTruth fields (INV-REC-04).
            val recoveryPayload = closureSemanticsPayload(
                isTerminalSignal = false,
                resultReturned = false,
                completionSignaled = false,
                closureReadyForAcceptance = false,
                outwardTruthSurfaceClass = AndroidCompletionClosureUplinkContract
                    .OutwardTruthSurfaceClass.ANDROID_RUNTIME_VISIBLE_STATE
            ).toMutableMap<String, Any?>().apply {
                truth.inflightContinuityState?.let {
                    put(KEY_CONTINUITY_RECOVERY_STATE, it)
                }
                truth.inflightContinuitySource?.let {
                    put(KEY_CONTINUITY_RECOVERY_SOURCE, it)
                }
                put(KEY_CONTINUITY_RECOVERY_SCHEMA_VERSION,
                    AndroidContinuityRecoveryStateModel.SCHEMA_VERSION)
                // PR-119: include structured routing metadata so V2 can consume routing
                // intent without re-deriving it from the raw phase value (INV-ROUTING-05).
                v2RoutingDecision?.let { decision ->
                    putAll(AndroidCrossRepoRecoveryStateRoutingContract.toWireMap(decision))
                }
                putAll(additionalPayload)
            }
            return ReconciliationSignal(
                kind = Kind.RUNTIME_TRUTH_SNAPSHOT,
                participantId = truth.participantId,
                taskId = truth.activeTaskId,
                correlationId = null,
                status = STATUS_SNAPSHOT,
                payload = recoveryPayload,
                runtimeTruth = truth,
                signalId = signalId,
                emittedAtMs = System.currentTimeMillis(),
                reconciliationEpoch = truth.reconciliationEpoch,
                durableSessionId = durableSessionId,
                sessionContinuityEpoch = sessionContinuityEpoch
            )
        }
    }
}
