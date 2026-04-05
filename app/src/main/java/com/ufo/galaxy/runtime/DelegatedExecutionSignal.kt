package com.ufo.galaxy.runtime

/**
 * **Canonical Android-side delegated-runtime acknowledgment/progress/result signal** (PR-10,
 * post-#533 dual-repo runtime unification master plan — Android-Side Delegated-Runtime
 * Execution-Tracking and Acknowledgment Basis, Android side).
 *
 * [DelegatedExecutionSignal] is the authoritative Android-side typed representation of
 * a signal emitted by the local execution pipeline toward the main-repo host during and
 * after delegated work is executed.  Three kinds of signal are defined:
 *
 *  - [Kind.ACK]      — emitted once after [DelegatedRuntimeReceiver] accepts the inbound
 *                      envelope, before execution begins.  Informs the host that Android
 *                      has received and is preparing to run the delegated unit.
 *  - [Kind.PROGRESS] — emitted periodically during execution (after each step or at
 *                      significant execution milestones) to inform the host that work is
 *                      progressing.  Carries the current [stepCount].
 *  - [Kind.RESULT]   — emitted exactly once when execution reaches a terminal state
 *                      (completed, failed, or rejected).  Carries a [ResultKind]
 *                      discriminator so the host can update its session-truth state without
 *                      inspecting raw status strings.
 *
 * ## Design intent
 *
 * Before PR-10 Android had no canonical, typed model for the signals emitted back to the
 * host during delegated execution.  Acknowledgment, progress, and result information were
 * sent as ad-hoc metadata maps or implied by wire-level status strings in
 * [com.ufo.galaxy.session.AndroidSessionContribution].
 *
 * [DelegatedExecutionSignal] closes this gap for the *delegated* path specifically.  It:
 *
 *  1. **Normalises** acknowledgment, progress, and result events into a single typed model.
 *  2. **Anchors every signal** to the delegated unit's identity ([unitId], [taskId],
 *     [traceId]), session ([attachedSessionId]), and handoff contract ([handoffContractVersion]),
 *     so the host can correlate signals across the lifecycle without inspecting raw payloads.
 *  3. **Produces** a canonical [toMetadataMap] output suitable for merging into AIP v3
 *     payloads or structured telemetry.
 *
 * ## Relationship to [DelegatedExecutionTracker]
 *
 * [DelegatedExecutionTracker] maintains the **local** execution-tracking state.
 * [DelegatedExecutionSignal] is the **outbound** signal derived from that state.  Use the
 * companion factory methods ([ack], [progress], [result]) to produce a signal from the
 * current tracker snapshot — this guarantees that identity fields are always consistent.
 *
 * ## Typical call site
 *
 * ```kotlin
 * // After receipt/activation:
 * val ackSignal = DelegatedExecutionSignal.ack(tracker)
 *
 * // After each execution step:
 * val progressSignal = DelegatedExecutionSignal.progress(updatedTracker)
 *
 * // After execution terminates:
 * val resultSignal = DelegatedExecutionSignal.result(
 *     tracker   = finalTracker,
 *     resultKind = DelegatedExecutionSignal.ResultKind.COMPLETED
 * )
 * ```
 *
 * @property kind                 Discriminator: [Kind.ACK], [Kind.PROGRESS], or [Kind.RESULT].
 * @property unitId               Stable delegated-unit identifier; echoed on every signal.
 * @property taskId               Task identifier; echoed on every signal.
 * @property traceId              End-to-end trace identifier; echoed on every signal.
 * @property attachedSessionId    Session identifier this signal is scoped to.
 * @property handoffContractVersion Handoff contract schema version from [DelegatedExecutionTracker].
 * @property stepCount            Number of execution steps completed at the moment this
 *                                signal was produced.
 * @property activationStatusHint Wire value of the current [DelegatedActivationRecord.ActivationStatus]
 *                                at the moment this signal was produced.
 * @property resultKind           For [Kind.RESULT] signals: the terminal outcome discriminator.
 *                                `null` for [Kind.ACK] and [Kind.PROGRESS] signals.
 * @property timestampMs          Epoch-ms timestamp when this signal was produced.
 */
data class DelegatedExecutionSignal(
    val kind: Kind,
    val unitId: String,
    val taskId: String,
    val traceId: String,
    val attachedSessionId: String,
    val handoffContractVersion: Int,
    val stepCount: Int,
    val activationStatusHint: String,
    val resultKind: ResultKind?,
    val timestampMs: Long
) {

    // ── Enums ─────────────────────────────────────────────────────────────────

    /**
     * Discriminator for the three categories of delegated-execution signal.
     *
     * @property wireValue Stable lowercase string used in JSON payloads and logging.
     */
    enum class Kind(val wireValue: String) {

        /**
         * Initial acknowledgment: emitted once when Android accepts the inbound delegated
         * unit for local activation, before execution begins.
         *
         * This signal allows the main-repo host to distinguish "Android acknowledged receipt"
         * from "Android is actively running steps" and from "Android has a final result".
         */
        ACK("ack"),

        /**
         * Mid-execution progress update: emitted periodically as execution steps complete.
         *
         * Carries the current [DelegatedExecutionSignal.stepCount] so the host can track
         * forward progress and detect stalls without waiting for the final result.
         */
        PROGRESS("progress"),

        /**
         * Final result signal: emitted exactly once when execution reaches a terminal state.
         *
         * The [DelegatedExecutionSignal.resultKind] field carries the terminal outcome
         * ([ResultKind.COMPLETED], [ResultKind.FAILED], or [ResultKind.REJECTED]) so the
         * host can update its session truth without inspecting raw wire status strings.
         */
        RESULT("result");

        companion object {
            /**
             * Parses [value] to a [Kind], returning [DEFAULT] for unknown or null inputs.
             *
             * @param value Wire string from a JSON payload; may be null.
             */
            fun fromValue(value: String?): Kind =
                entries.firstOrNull { it.wireValue == value } ?: DEFAULT

            /** Default kind for unknown or absent wire values. */
            val DEFAULT: Kind = ACK
        }
    }

    /**
     * Terminal outcome discriminator carried by [Kind.RESULT] signals.
     *
     * @property wireValue Stable lowercase string used in JSON payloads and logging.
     */
    enum class ResultKind(val wireValue: String) {

        /**
         * The delegated unit completed successfully.
         * Corresponds to [DelegatedActivationRecord.ActivationStatus.COMPLETED].
         */
        COMPLETED("completed"),

        /**
         * The delegated unit failed during local execution (error, timeout, posture gate).
         * Corresponds to [DelegatedActivationRecord.ActivationStatus.FAILED].
         */
        FAILED("failed"),

        /**
         * The delegated unit was rejected before execution began (session absent or terminating).
         * Corresponds to [DelegatedActivationRecord.ActivationStatus.REJECTED].
         */
        REJECTED("rejected");

        companion object {
            /**
             * Parses [value] to a [ResultKind], returning `null` for unknown or null inputs.
             *
             * Returns `null` (rather than a default) so callers can distinguish "known result
             * kind" from "signal is not a RESULT signal" without a sentinel value.
             *
             * @param value Wire string from a JSON payload; may be null.
             */
            fun fromValue(value: String?): ResultKind? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Derived helpers ───────────────────────────────────────────────────────

    /** `true` when this is an initial receipt acknowledgment signal. */
    val isAck: Boolean
        get() = kind == Kind.ACK

    /** `true` when this is a mid-execution progress signal. */
    val isProgress: Boolean
        get() = kind == Kind.PROGRESS

    /** `true` when this is a terminal result signal. */
    val isResult: Boolean
        get() = kind == Kind.RESULT

    // ── Wire serialisation ────────────────────────────────────────────────────

    /**
     * Builds the canonical metadata map for wire transmission or diagnostic logging.
     *
     * Keys always present:
     *  - [KEY_SIGNAL_KIND]              — [Kind.wireValue].
     *  - [KEY_UNIT_ID]                  — delegated-unit identifier.
     *  - [KEY_TASK_ID]                  — task identifier.
     *  - [KEY_TRACE_ID]                 — end-to-end trace identifier.
     *  - [KEY_ATTACHED_SESSION_ID]      — session identifier.
     *  - [KEY_HANDOFF_CONTRACT_VERSION] — handoff contract schema version.
     *  - [KEY_STEP_COUNT]               — number of steps recorded at signal time.
     *  - [KEY_ACTIVATION_STATUS_HINT]   — current activation status wire value.
     *  - [KEY_TIMESTAMP_MS]             — epoch-ms signal production timestamp.
     *
     * Keys present when non-null:
     *  - [KEY_RESULT_KIND]              — [ResultKind.wireValue]; present for [Kind.RESULT] only.
     *
     * @return An immutable [Map] suitable for merging into AIP v3 metadata payloads.
     */
    fun toMetadataMap(): Map<String, Any> = buildMap {
        put(KEY_SIGNAL_KIND, kind.wireValue)
        put(KEY_UNIT_ID, unitId)
        put(KEY_TASK_ID, taskId)
        put(KEY_TRACE_ID, traceId)
        put(KEY_ATTACHED_SESSION_ID, attachedSessionId)
        put(KEY_HANDOFF_CONTRACT_VERSION, handoffContractVersion)
        put(KEY_STEP_COUNT, stepCount)
        put(KEY_ACTIVATION_STATUS_HINT, activationStatusHint)
        put(KEY_TIMESTAMP_MS, timestampMs)
        resultKind?.let { put(KEY_RESULT_KIND, it.wireValue) }
    }

    // ── Companion / constants ─────────────────────────────────────────────────

    companion object {

        /** Metadata key for the [Kind.wireValue] discriminator. */
        const val KEY_SIGNAL_KIND = "exec_signal_kind"

        /** Metadata key for the delegated-unit identifier. */
        const val KEY_UNIT_ID = "exec_signal_unit_id"

        /** Metadata key for the task identifier. */
        const val KEY_TASK_ID = "exec_signal_task_id"

        /** Metadata key for the end-to-end trace identifier. */
        const val KEY_TRACE_ID = "exec_signal_trace_id"

        /** Metadata key for the session identifier. */
        const val KEY_ATTACHED_SESSION_ID = "exec_signal_attached_session_id"

        /** Metadata key for the handoff contract schema version. */
        const val KEY_HANDOFF_CONTRACT_VERSION = "exec_signal_handoff_contract_version"

        /** Metadata key for the number of execution steps at signal time. */
        const val KEY_STEP_COUNT = "exec_signal_step_count"

        /** Metadata key for the activation status wire-value hint. */
        const val KEY_ACTIVATION_STATUS_HINT = "exec_signal_activation_status_hint"

        /** Metadata key for the epoch-ms signal production timestamp. */
        const val KEY_TIMESTAMP_MS = "exec_signal_timestamp_ms"

        /**
         * Metadata key for the [ResultKind.wireValue].
         * **Absent** from [toMetadataMap] when [resultKind] is `null`
         * (i.e. for [Kind.ACK] and [Kind.PROGRESS] signals).
         */
        const val KEY_RESULT_KIND = "exec_signal_result_kind"

        // ── Factory methods ───────────────────────────────────────────────────

        /**
         * Produces an [Kind.ACK] signal from the current [tracker] snapshot.
         *
         * Emit this signal immediately after [DelegatedRuntimeReceiver] accepts the inbound
         * unit and the initial tracker has been created (i.e. record is
         * [DelegatedActivationRecord.ActivationStatus.PENDING]).
         *
         * @param tracker   The current [DelegatedExecutionTracker].
         * @param timestampMs Epoch-ms timestamp; defaults to the current time.
         * @return A [Kind.ACK] signal anchored to the tracker's identity.
         */
        fun ack(
            tracker: DelegatedExecutionTracker,
            timestampMs: Long = System.currentTimeMillis()
        ): DelegatedExecutionSignal = DelegatedExecutionSignal(
            kind = Kind.ACK,
            unitId = tracker.unitId,
            taskId = tracker.taskId,
            traceId = tracker.traceId,
            attachedSessionId = tracker.attachedSessionId,
            handoffContractVersion = tracker.handoffContractVersion,
            stepCount = tracker.stepCount,
            activationStatusHint = tracker.record.activationStatus.wireValue,
            resultKind = null,
            timestampMs = timestampMs
        )

        /**
         * Produces a [Kind.PROGRESS] signal from the current [tracker] snapshot.
         *
         * Emit this signal after each execution step or at other significant milestones
         * during active execution.  The signal carries the current [DelegatedExecutionTracker.stepCount]
         * so the host can detect forward progress.
         *
         * @param tracker   The current [DelegatedExecutionTracker].
         * @param timestampMs Epoch-ms timestamp; defaults to the current time.
         * @return A [Kind.PROGRESS] signal anchored to the tracker's identity.
         */
        fun progress(
            tracker: DelegatedExecutionTracker,
            timestampMs: Long = System.currentTimeMillis()
        ): DelegatedExecutionSignal = DelegatedExecutionSignal(
            kind = Kind.PROGRESS,
            unitId = tracker.unitId,
            taskId = tracker.taskId,
            traceId = tracker.traceId,
            attachedSessionId = tracker.attachedSessionId,
            handoffContractVersion = tracker.handoffContractVersion,
            stepCount = tracker.stepCount,
            activationStatusHint = tracker.record.activationStatus.wireValue,
            resultKind = null,
            timestampMs = timestampMs
        )

        /**
         * Produces a [Kind.RESULT] signal from the current [tracker] snapshot.
         *
         * Emit this signal exactly once when execution reaches a terminal state.  The
         * [resultKind] discriminator carries the outcome so the host can update its
         * session truth without inspecting raw status strings.
         *
         * @param tracker    The current [DelegatedExecutionTracker] (should be terminal).
         * @param resultKind The terminal outcome: [ResultKind.COMPLETED], [ResultKind.FAILED],
         *                   or [ResultKind.REJECTED].
         * @param timestampMs Epoch-ms timestamp; defaults to the current time.
         * @return A [Kind.RESULT] signal anchored to the tracker's identity.
         */
        fun result(
            tracker: DelegatedExecutionTracker,
            resultKind: ResultKind,
            timestampMs: Long = System.currentTimeMillis()
        ): DelegatedExecutionSignal = DelegatedExecutionSignal(
            kind = Kind.RESULT,
            unitId = tracker.unitId,
            taskId = tracker.taskId,
            traceId = tracker.traceId,
            attachedSessionId = tracker.attachedSessionId,
            handoffContractVersion = tracker.handoffContractVersion,
            stepCount = tracker.stepCount,
            activationStatusHint = tracker.record.activationStatus.wireValue,
            resultKind = resultKind,
            timestampMs = timestampMs
        )
    }
}
