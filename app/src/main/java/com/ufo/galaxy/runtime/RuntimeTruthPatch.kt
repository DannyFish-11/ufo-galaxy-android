package com.ufo.galaxy.runtime

/**
 * PR-64 — Runtime Truth Patch.
 *
 * A typed, atomic, event-sourced truth update produced whenever an authoritative
 * Android-side event advances the local participant/runtime state.  Every
 * authoritative event — delegated execution result, handoff response, takeover
 * accept/reject, and session terminal event — is translated into a [RuntimeTruthPatch]
 * before being applied by [TruthReconciliationReducer] to the canonical
 * [AndroidParticipantRuntimeTruth] snapshot.
 *
 * ## Motivation
 *
 * Prior to PR-64, task terminal state and participant role/state were advanced
 * independently by each event handler:
 *
 *  - [com.ufo.galaxy.service.GalaxyConnectionService.handleTakeoverRequest] updated
 *    local take-over outcome directly.
 *  - [RuntimeController.publishTaskResult] / [publishTaskCancelled] / [notifyTakeoverFailed]
 *    each cleared active-task state through separate code paths.
 *  - Handoff accept/reject responses had no structured local truth update at all.
 *
 * This meant the same task terminal state could be mutated by multiple modules without
 * a single convergence point, making the local truth non-auditable and ordering-sensitive.
 *
 * [RuntimeTruthPatch] closes this gap by providing:
 *  1. **A single typed event envelope** for every kind of authoritative truth update.
 *  2. **Explicit [isAuthoritative] flag** — consumers distinguish canonical mutations
 *     from advisory/notification-only signals.
 *  3. **[reconciliationEpoch] ordering** — late or stale patches are discarded by the
 *     reducer instead of silently overwriting newer truth.
 *
 * ## Wire-key constants
 *
 * [toMap] produces a stable [Map] using [KEY_*] constants, suitable for structured
 * logging and V2 reconciliation payloads.
 *
 * @property patchId              Unique, stable identifier for this patch.  Used by
 *                                [TruthReconciliationReducer] for deduplication.
 * @property participantId        Stable participant node identifier matching
 *                                [AndroidParticipantRuntimeTruth.participantId].
 * @property taskId               Task this patch is associated with; null for participant-only
 *                                patches (e.g. participant state change, session terminal).
 * @property kind                 Patch kind discriminator — identifies the event source.
 * @property isAuthoritative      `true` when this patch represents an **authoritative** local
 *                                truth update that [TruthReconciliationReducer] must apply.
 *                                `false` when the patch is a **notification signal** only and
 *                                must not mutate the canonical snapshot.
 * @property taskTerminalOutcome  Populated for task-terminal patch kinds
 *                                ([Kind.DELEGATED_TASK_RESULT], [Kind.DELEGATED_TASK_CANCELLED],
 *                                [Kind.DELEGATED_TASK_FAILED]); null otherwise.
 * @property handoffOutcome       Populated for handoff patch kinds ([Kind.HANDOFF_ACCEPTED],
 *                                [Kind.HANDOFF_REJECTED]); null otherwise.
 * @property takeoverOutcome      Populated for takeover patch kinds ([Kind.TAKEOVER_ACCEPTED],
 *                                [Kind.TAKEOVER_REJECTED]); null otherwise.
 * @property errorDetail          Optional human-readable error/rejection reason for failure
 *                                and rejection patches; null for success paths.
 * @property patchedAtMs          Epoch-millisecond timestamp when this patch was produced.
 * @property reconciliationEpoch  Monotonically increasing epoch from the participant's runtime
 *                                truth clock.  [TruthReconciliationReducer] discards patches
 *                                whose epoch is lower than the current snapshot epoch.
 */
data class RuntimeTruthPatch(
    val patchId: String,
    val participantId: String,
    val taskId: String?,
    val kind: Kind,
    val isAuthoritative: Boolean,
    val taskTerminalOutcome: TaskTerminalOutcome? = null,
    val handoffOutcome: HandoffOutcome? = null,
    val takeoverOutcome: TakeoverOutcome? = null,
    val errorDetail: String? = null,
    val patchedAtMs: Long = System.currentTimeMillis(),
    val reconciliationEpoch: Int
) {

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * Returns `true` when this patch carries a task terminal outcome.
     *
     * Terminal patches are the canonical signal for [TruthReconciliationReducer] to clear
     * the active task state from [AndroidParticipantRuntimeTruth].
     */
    val isTaskTerminal: Boolean
        get() = taskTerminalOutcome != null

    /**
     * Returns `true` when this patch carries an ownership transition outcome (handoff or
     * takeover), enabling [TruthReconciliationReducer] to record the ownership result in
     * the participant truth.
     */
    val isOwnershipTransition: Boolean
        get() = handoffOutcome != null || takeoverOutcome != null

    // ── Wire serialization ────────────────────────────────────────────────────

    /**
     * Serialises all patch fields to a stable key→value [Map] for structured logging and
     * diagnostic payloads.
     *
     * Always-present keys: [KEY_PATCH_ID], [KEY_PARTICIPANT_ID], [KEY_KIND],
     * [KEY_IS_AUTHORITATIVE], [KEY_PATCHED_AT_MS], [KEY_RECONCILIATION_EPOCH].
     * Conditional keys: [KEY_TASK_ID], [KEY_TASK_TERMINAL_OUTCOME], [KEY_HANDOFF_OUTCOME],
     * [KEY_TAKEOVER_OUTCOME], [KEY_ERROR_DETAIL] — omitted when null.
     */
    fun toMap(): Map<String, Any> = buildMap {
        put(KEY_PATCH_ID, patchId)
        put(KEY_PARTICIPANT_ID, participantId)
        taskId?.let { put(KEY_TASK_ID, it) }
        put(KEY_KIND, kind.wireValue)
        put(KEY_IS_AUTHORITATIVE, isAuthoritative)
        taskTerminalOutcome?.let { put(KEY_TASK_TERMINAL_OUTCOME, it.wireValue) }
        handoffOutcome?.let { put(KEY_HANDOFF_OUTCOME, it.wireValue) }
        takeoverOutcome?.let { put(KEY_TAKEOVER_OUTCOME, it.wireValue) }
        errorDetail?.let { put(KEY_ERROR_DETAIL, it) }
        put(KEY_PATCHED_AT_MS, patchedAtMs)
        put(KEY_RECONCILIATION_EPOCH, reconciliationEpoch)
    }

    // ── Enums ─────────────────────────────────────────────────────────────────

    /**
     * Truth patch kind discriminator.
     *
     * Each kind identifies the originating event source and the semantics of the patch.
     *
     * @property wireValue Stable lowercase string used in wire-format maps.
     */
    enum class Kind(val wireValue: String) {

        /**
         * Android completed a delegated task successfully.
         *
         * Authoritative terminal patch: [TruthReconciliationReducer] clears the active task
         * state and records [TaskTerminalOutcome.COMPLETED].
         */
        DELEGATED_TASK_RESULT("delegated_task_result"),

        /**
         * A delegated task was cancelled (explicit request or preemption).
         *
         * Authoritative terminal patch: [TruthReconciliationReducer] clears the active task
         * state and records [TaskTerminalOutcome.CANCELLED].
         */
        DELEGATED_TASK_CANCELLED("delegated_task_cancelled"),

        /**
         * A delegated task failed (error, timeout, pipeline fault, or session interruption).
         *
         * Authoritative terminal patch: [TruthReconciliationReducer] clears the active task
         * state and records [TaskTerminalOutcome.FAILED].
         */
        DELEGATED_TASK_FAILED("delegated_task_failed"),

        /**
         * A handoff request was accepted by the remote runtime.
         *
         * Ownership-transition patch: records [HandoffOutcome.ACCEPTED] in the participant
         * truth, indicating that execution ownership was successfully transferred.
         */
        HANDOFF_ACCEPTED("handoff_accepted"),

        /**
         * A handoff request was rejected by the remote runtime.
         *
         * Ownership-transition patch: records [HandoffOutcome.REJECTED] in the participant
         * truth with an optional [errorDetail] reason string.
         */
        HANDOFF_REJECTED("handoff_rejected"),

        /**
         * A takeover request was accepted: Android accepted delegated work from V2.
         *
         * Ownership-transition patch: records [TakeoverOutcome.ACCEPTED] in the participant
         * truth, linking it to the active task identified by [taskId].
         */
        TAKEOVER_ACCEPTED("takeover_accepted"),

        /**
         * A takeover request was rejected: Android refused delegated work from V2.
         *
         * Ownership-transition patch: records [TakeoverOutcome.REJECTED] in the participant
         * truth with an optional [errorDetail] reason string.
         */
        TAKEOVER_REJECTED("takeover_rejected"),

        /**
         * Participant state changed (health, readiness, or posture).
         *
         * Advisory notification patch: [TruthReconciliationReducer] records the state
         * change in the participant truth but this patch alone does not close a task or
         * transfer ownership.
         */
        PARTICIPANT_STATE_CHANGED("participant_state_changed"),

        /**
         * The attached runtime session reached a terminal state (disconnect, invalidation,
         * or explicit stop).
         *
         * Authoritative terminal patch: if an active task exists, the reducer applies
         * [TaskTerminalOutcome.FAILED] before clearing session truth.
         */
        SESSION_TERMINAL("session_terminal");

        companion object {
            /**
             * Parses [value] to a [Kind], returning null for unknown values.
             *
             * @param value Wire string from a patch map or log record; may be null.
             */
            fun fromValue(value: String?): Kind? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    /**
     * Terminal outcome of a delegated task execution.
     *
     * Carried by task-terminal patch kinds to inform [TruthReconciliationReducer] how
     * the active task was closed.
     *
     * @property wireValue Stable lowercase string used in wire-format maps.
     */
    enum class TaskTerminalOutcome(val wireValue: String) {

        /**
         * Task completed successfully.  Android produced a result; V2 should close the
         * task as a success in its canonical task registry.
         */
        COMPLETED("completed"),

        /**
         * Task was cancelled.  Android received an explicit cancel request or was preempted;
         * no result was produced.  V2 should close the task as cancelled.
         */
        CANCELLED("cancelled"),

        /**
         * Task failed.  An error, timeout, pipeline fault, or session interruption caused
         * Android to terminate execution without a valid result.  V2 should close the task
         * as failed and apply its retry/fallback policy.
         */
        FAILED("failed");

        companion object {
            /**
             * Parses [value] to a [TaskTerminalOutcome], returning null for unknown values.
             */
            fun fromValue(value: String?): TaskTerminalOutcome? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    /**
     * Outcome of a handoff request from Android to a remote runtime participant.
     *
     * @property wireValue Stable lowercase string used in wire-format maps.
     */
    enum class HandoffOutcome(val wireValue: String) {

        /**
         * Handoff was accepted by the remote runtime.
         *
         * Execution ownership transferred successfully; Android's local task can be
         * considered delegated and the local execution context closed.
         */
        ACCEPTED("accepted"),

        /**
         * Handoff was rejected by the remote runtime.
         *
         * Android must retain execution ownership; no ownership transfer occurred.
         * See [errorDetail] on the patch for the rejection reason.
         */
        REJECTED("rejected");

        companion object {
            /**
             * Parses [value] to a [HandoffOutcome], returning null for unknown values.
             */
            fun fromValue(value: String?): HandoffOutcome? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    /**
     * Outcome of a takeover request received by Android from V2.
     *
     * @property wireValue Stable lowercase string used in wire-format maps.
     */
    enum class TakeoverOutcome(val wireValue: String) {

        /**
         * Android accepted the takeover: delegated execution will proceed locally.
         *
         * Android takes ownership of the task identified by [RuntimeTruthPatch.taskId].
         */
        ACCEPTED("accepted"),

        /**
         * Android rejected the takeover: delegated execution was refused.
         *
         * No task ownership was acquired.  See [errorDetail] on the patch for the
         * rejection reason (eligibility failure, no active session, etc.).
         */
        REJECTED("rejected");

        companion object {
            /**
             * Parses [value] to a [TakeoverOutcome], returning null for unknown values.
             */
            fun fromValue(value: String?): TakeoverOutcome? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Wire-key constants ────────────────────────────────────────────────────

    companion object {

        /** Wire key for [patchId]. */
        const val KEY_PATCH_ID = "patch_id"

        /** Wire key for [participantId]. */
        const val KEY_PARTICIPANT_ID = "participant_id"

        /** Wire key for [taskId]; absent when [taskId] is null. */
        const val KEY_TASK_ID = "task_id"

        /** Wire key for [kind] ([Kind.wireValue]). */
        const val KEY_KIND = "kind"

        /** Wire key for [isAuthoritative]. */
        const val KEY_IS_AUTHORITATIVE = "is_authoritative"

        /** Wire key for [taskTerminalOutcome] ([TaskTerminalOutcome.wireValue]); absent when null. */
        const val KEY_TASK_TERMINAL_OUTCOME = "task_terminal_outcome"

        /** Wire key for [handoffOutcome] ([HandoffOutcome.wireValue]); absent when null. */
        const val KEY_HANDOFF_OUTCOME = "handoff_outcome"

        /** Wire key for [takeoverOutcome] ([TakeoverOutcome.wireValue]); absent when null. */
        const val KEY_TAKEOVER_OUTCOME = "takeover_outcome"

        /** Wire key for [errorDetail]; absent when null. */
        const val KEY_ERROR_DETAIL = "error_detail"

        /** Wire key for [patchedAtMs]. */
        const val KEY_PATCHED_AT_MS = "patched_at_ms"

        /** Wire key for [reconciliationEpoch]. */
        const val KEY_RECONCILIATION_EPOCH = "reconciliation_epoch"
    }
}
