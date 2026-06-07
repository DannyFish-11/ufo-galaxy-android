package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.DelegatedRuntimeUnit

/**
 * **Canonical Android-side activation record for a delegated runtime work unit** (PR-8,
 * post-#533 dual-repo runtime unification master plan — Delegated Runtime
 * Receipt/Activation Foundations, Android side).
 *
 * [DelegatedActivationRecord] is the immutable record created at the moment Android
 * decides to locally activate a [DelegatedRuntimeUnit] under an active
 * [AttachedRuntimeSession].  It binds:
 *
 *  - The delegated unit's activation inputs ([unit]).
 *  - The [attachedSessionId] of the [AttachedRuntimeSession] under which the unit is
 *    being executed, ensuring activation is always tied to explicit session state and
 *    not ad-hoc transport conditions.
 *  - The [activationStatus] lifecycle of the local execution.
 *  - A stable [activatedAtMs] timestamp for latency measurement and audit.
 *
 * ## Lifecycle
 *
 * ```
 *   receive() accepted
 *        │
 *        ▼
 *   ┌─────────┐   begin local execution   ┌────────────┐   complete   ┌───────────┐
 *   │ PENDING  │ ──────────────────────▶  │ ACTIVATING │ ───────────▶ │ COMPLETED │
 *   └─────────┘                           └────────────┘   fail       └───────────┘
 *                                               │                     ┌─────────┐
 *                                               └───────────────────▶ │ FAILED  │
 *                                                                      └─────────┘
 *   (REJECTED: produced directly when session is absent/detached — no PENDING)
 * ```
 *
 * ## Immutability
 * [DelegatedActivationRecord] is immutable.  Use the [transition] helper to produce
 * updated copies without mutating the original.
 *
 * ## Obtaining an instance
 * Use [DelegatedRuntimeReceiver.receive] to produce the initial record; the record
 * starts in [ActivationStatus.PENDING].  Callers advance it by calling [transition].
 *
 * @property unit              The [DelegatedRuntimeUnit] being activated.
 * @property attachedSessionId The [AttachedRuntimeSession.sessionId] under which this
 *                             activation is running.  Stable across status transitions.
 * @property activationStatus  Current lifecycle status of this local activation.
 * @property activatedAtMs     Epoch-millisecond timestamp when this record was created
 *                             (i.e. when the delegated unit was accepted for local activation).
 * @property rejectionReason   Human-readable reason; non-null only when
 *                             [activationStatus] is [ActivationStatus.REJECTED].
 */
data class DelegatedActivationRecord(
    val unit: DelegatedRuntimeUnit,
    val attachedSessionId: String,
    val activationStatus: ActivationStatus = ActivationStatus.PENDING,
    val activatedAtMs: Long = System.currentTimeMillis(),
    val rejectionReason: String? = null
) {

    // ── Enums ─────────────────────────────────────────────────────────────────

    /**
     * Lifecycle status of a local delegated-runtime activation.
     *
     * @property wireValue Stable lowercase string used in JSON payloads and logging.
     */
    enum class ActivationStatus(val wireValue: String) {

        /**
         * Accepted for local activation; pre-execution resource setup may be in progress.
         *
         * The initial status produced by [DelegatedRuntimeReceiver] on a successful receipt.
         */
        PENDING("pending"),

        /**
         * Local execution pipeline has begun processing this unit.
         *
         * Transitions from [PENDING] when the execution coroutine is launched and the
         * goal is dispatched to the execution pipeline.
         */
        ACTIVATING("activating"),

        /**
         * The execution pipeline is actively running the delegated goal.
         *
         * Transitions from [ACTIVATING] once the pipeline has accepted the goal and
         * execution steps are in progress.
         */
        ACTIVE("active"),

        /**
         * The delegated unit completed successfully.
         *
         * A goal result has been sent back to the main runtime.
         */
        COMPLETED("completed"),

        /**
         * The delegated unit failed during local execution.
         *
         * A failure result (timeout, exception, posture gate) has been sent back.
         */
        FAILED("failed"),

        /**
         * The unit was rejected before local execution began.
         *
         * Rejection can occur when:
         *  - No [AttachedRuntimeSession] is active.
         *  - The session is in [AttachedRuntimeSession.State.DETACHING] or
         *    [AttachedRuntimeSession.State.DETACHED].
         *
         * The [DelegatedActivationRecord.rejectionReason] field carries the reason.
         */
        REJECTED("rejected");

        companion object {
            /**
             * Parses [value] to an [ActivationStatus], returning [DEFAULT] for unknown
             * or null inputs.
             *
             * @param value Wire string from a JSON payload; may be null.
             */
            fun fromValue(value: String?): ActivationStatus =
                entries.firstOrNull { it.wireValue == value } ?: DEFAULT

            /** Default status for a freshly created activation record. */
            val DEFAULT: ActivationStatus = PENDING
        }
    }

    // ── Derived helpers ───────────────────────────────────────────────────────

    /** `true` when this activation is in a terminal state (COMPLETED, FAILED, or REJECTED). */
    val isTerminal: Boolean
        get() = activationStatus == ActivationStatus.COMPLETED
            || activationStatus == ActivationStatus.FAILED
            || activationStatus == ActivationStatus.REJECTED

    /** `true` when this activation is running or pending (not yet terminal). */
    val isActive: Boolean
        get() = !isTerminal

    /**
     * Produces a copy of this record with [activationStatus] advanced to [newStatus].
     *
     * Does not allow transitioning out of a terminal state: if this record is already
     * [isTerminal], the original is returned unchanged.
     *
     * @param newStatus The target [ActivationStatus].
     * @return A new [DelegatedActivationRecord] with [activationStatus] = [newStatus];
     *         the original is unchanged.
     */
    fun transition(newStatus: ActivationStatus): DelegatedActivationRecord {
        if (isTerminal) return this
        return copy(activationStatus = newStatus)
    }

    /**
     * Builds the canonical metadata map for wire transmission or diagnostic logging.
     *
     * Keys present:
     *  - [KEY_UNIT_ID]             — delegated unit identifier.
     *  - [KEY_TASK_ID]             — task identifier.
     *  - [KEY_TRACE_ID]            — end-to-end trace identifier.
     *  - [KEY_ATTACHED_SESSION_ID] — session this activation runs under.
     *  - [KEY_ACTIVATION_STATUS]   — [ActivationStatus.wireValue] of the current status.
     *  - [KEY_ACTIVATED_AT_MS]     — epoch-ms activation timestamp.
     *  - [KEY_REJECTION_REASON]    — rejection reason; **absent** when [rejectionReason] is null.
     *
     * @return An immutable [Map] suitable for merging into AIP v3 metadata payloads.
     */
    fun toMetadataMap(): Map<String, Any> = buildMap {
        put(KEY_UNIT_ID, unit.unitId)
        put(KEY_TASK_ID, unit.taskId)
        put(KEY_TRACE_ID, unit.traceId)
        put(KEY_ATTACHED_SESSION_ID, attachedSessionId)
        put(KEY_ACTIVATION_STATUS, activationStatus.wireValue)
        put(KEY_ACTIVATED_AT_MS, activatedAtMs)
        rejectionReason?.let { put(KEY_REJECTION_REASON, it) }
    }

    // ── Companion / constants ─────────────────────────────────────────────────

    companion object {

        /** Metadata key for the delegated-unit identifier. */
        const val KEY_UNIT_ID = "activation_unit_id"

        /** Metadata key for the task identifier. */
        const val KEY_TASK_ID = "activation_task_id"

        /** Metadata key for the end-to-end trace identifier. */
        const val KEY_TRACE_ID = "activation_trace_id"

        /** Metadata key for the attached session identifier. */
        const val KEY_ATTACHED_SESSION_ID = "activation_attached_session_id"

        /** Metadata key for the [ActivationStatus.wireValue] string. */
        const val KEY_ACTIVATION_STATUS = "activation_status"

        /** Metadata key for the epoch-ms activation timestamp. */
        const val KEY_ACTIVATED_AT_MS = "activation_activated_at_ms"

        /**
         * Metadata key for the rejection reason.
         * This key is **absent** from [toMetadataMap] output when [rejectionReason] is null.
         */
        const val KEY_REJECTION_REASON = "activation_rejection_reason"

        /**
         * Creates an initial [DelegatedActivationRecord] in [ActivationStatus.PENDING] for
         * an accepted [DelegatedRuntimeUnit].
         *
         * @param unit           The [DelegatedRuntimeUnit] accepted for local activation.
         * @param activatedAtMs  Epoch-ms activation timestamp; defaults to the current time.
         * @return A new [DelegatedActivationRecord] in [ActivationStatus.PENDING].
         */
        fun create(
            unit: DelegatedRuntimeUnit,
            activatedAtMs: Long = System.currentTimeMillis()
        ): DelegatedActivationRecord = DelegatedActivationRecord(
            unit = unit,
            attachedSessionId = unit.attachedSessionId,
            activationStatus = ActivationStatus.PENDING,
            activatedAtMs = activatedAtMs,
            rejectionReason = null
        )

        /**
         * Creates a [DelegatedActivationRecord] in [ActivationStatus.REJECTED] for a
         * unit that was refused because no valid [AttachedRuntimeSession] was present.
         *
         * @param unit           The [DelegatedRuntimeUnit] that was rejected.
         * @param reason         Human-readable rejection reason.
         * @param activatedAtMs  Epoch-ms rejection timestamp; defaults to the current time.
         * @return A new [DelegatedActivationRecord] in [ActivationStatus.REJECTED].
         */
        fun rejected(
            unit: DelegatedRuntimeUnit,
            reason: String,
            activatedAtMs: Long = System.currentTimeMillis()
        ): DelegatedActivationRecord = DelegatedActivationRecord(
            unit = unit,
            attachedSessionId = unit.attachedSessionId,
            activationStatus = ActivationStatus.REJECTED,
            activatedAtMs = activatedAtMs,
            rejectionReason = reason
        )
    }
}
