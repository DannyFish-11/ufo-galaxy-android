package com.ufo.galaxy.runtime

/**
 * **Canonical Android-side delegated-runtime acknowledgment/progress/result signal** (PR-10
 * / PR-13 / PR-15, post-#533 dual-repo runtime unification master plan — Android-Side
 * Delegated-Runtime Execution-Tracking and Signal Emission Path, Android side).
 *
 * [DelegatedExecutionSignal] is the authoritative Android-side typed representation of
 * a signal emitted by the local execution pipeline toward the main-repo host during and
 * after delegated work is executed.  Three kinds of signal are defined:
 *
 *  - [Kind.ACK]      — emitted once after [DelegatedRuntimeReceiver] accepts the inbound
 *                      envelope, before execution begins.  Informs the host that Android
 *                      has received and is preparing to run the delegated unit.
 *  - [Kind.PROGRESS] — emitted when execution reaches the ACTIVE state (at least once
 *                      per execution lifecycle) to inform the host that work is actively
 *                      running.  Carries the current [stepCount].
 *  - [Kind.RESULT]   — emitted exactly once when execution reaches a terminal state
 *                      (completed, failed, timed out, cancelled, or rejected).  Carries a
 *                      [ResultKind] discriminator so the host can update its session-truth
 *                      state without inspecting raw status strings.  See [ResultKind] for
 *                      the full set of terminal outcomes including [ResultKind.TIMEOUT] and
 *                      [ResultKind.CANCELLED] added in PR-13.
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
 * ## Recovery-readiness / idempotency metadata (PR-15)
 *
 * Intermittent network conditions can cause signals to be delivered more than once or
 * arrive out of order.  To allow the host to detect duplicates and reconcile out-of-order
 * delivery safely, PR-15 adds two lightweight, additive fields:
 *
 *  - [signalId]     — a UUID that uniquely identifies this specific signal emission.
 *                     The host can use it as an idempotency key: two signals with the same
 *                     [signalId] represent the same logical event and the second can be
 *                     discarded without side-effects.  Callers may supply an explicit value
 *                     (e.g. when replaying a persisted signal); otherwise a fresh UUID is
 *                     generated automatically.
 *
 *  - [emissionSeq]  — a monotonically increasing integer that reflects the signal's
 *                     canonical position in the per-execution emission sequence:
 *                     ACK = [EMISSION_SEQ_ACK] (1), PROGRESS = [EMISSION_SEQ_PROGRESS] (2),
 *                     RESULT = [EMISSION_SEQ_RESULT] (3).  The host can use this field to
 *                     detect out-of-order delivery and to reject late duplicates whose
 *                     [emissionSeq] is lower than the highest sequence already processed.
 *
 * Both fields are always present in [toMetadataMap] and are additive — existing consumers
 * that do not inspect them are unaffected.
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
 * @property signalId             Stable UUID identifying this specific emission.  Serves as
 *                                an idempotency key so the host can discard duplicate
 *                                deliveries of the same logical event.  Generated
 *                                automatically via [java.util.UUID.randomUUID] if not
 *                                supplied explicitly to the factory methods.
 * @property emissionSeq          Monotonically increasing position in the canonical
 *                                per-execution emission sequence:
 *                                [EMISSION_SEQ_ACK] (1) → [EMISSION_SEQ_PROGRESS] (2) →
 *                                [EMISSION_SEQ_RESULT] (3).  Allows the host to detect
 *                                out-of-order delivery and reject stale duplicates.
 * @property delegatedFlowId      (PR-bridge) Stable identifier for the V2 canonical delegated
 *                                flow entity this signal belongs to.  Sourced from
 *                                [com.ufo.galaxy.runtime.AndroidDelegatedFlowBridge.delegatedFlowId]
 *                                when available; `null` for pre-bridge signals.  The host can use
 *                                this to correlate the signal with its canonical flow entity.
 * @property flowLineageId        (PR-bridge) Lineage identity of the V2 canonical delegated flow
 *                                entity.  Sourced from
 *                                [com.ufo.galaxy.runtime.AndroidDelegatedFlowBridge.flowLineageId]
 *                                when available; `null` for pre-bridge signals.
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
    val timestampMs: Long,
    val signalId: String,
    val emissionSeq: Int,
    // ── PR-bridge: Delegated flow bridge identity (optional; null for pre-bridge signals) ──
    val delegatedFlowId: String? = null,
    val flowLineageId: String? = null
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
         * The delegated unit's execution timed out before reaching a terminal state.
         *
         * Emitted when the execution pipeline or the surrounding coroutine scope signals
         * a timeout condition (e.g. a [kotlinx.coroutines.TimeoutCancellationException]).
         * The activation record is advanced to [DelegatedActivationRecord.ActivationStatus.FAILED]
         * before this signal is produced.
         */
        TIMEOUT("timeout"),

        /**
         * The delegated unit was cancelled by the runtime or an external actor before
         * reaching a terminal state.
         *
         * Emitted when the execution is interrupted by a [kotlinx.coroutines.CancellationException]
         * that is not a timeout (e.g. explicit task cancellation via [TaskCancelRegistry]).
         * The activation record is advanced to [DelegatedActivationRecord.ActivationStatus.FAILED]
         * before this signal is produced.
         */
        CANCELLED("cancelled"),

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

    /**
     * Canonical participant execution signal class under the unified contract (PR-75).
     *
     * Maps this signal's [kind] to the single shared [ParticipantExecutionSignalContract.ExecSignalClass]
     * that applies uniformly across [DelegatedExecutionSignal], [ReconciliationSignal], and
     * [com.ufo.galaxy.agent.TakeoverResponseEnvelope].
     *
     *  - [Kind.ACK]      → [ParticipantExecutionSignalContract.ExecSignalClass.ACK]
     *  - [Kind.PROGRESS] → [ParticipantExecutionSignalContract.ExecSignalClass.NON_TERMINAL]
     *  - [Kind.RESULT]   → [ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL]
     *
     * Callers MUST use this property — not the raw [kind] discriminator — when routing or
     * reducing signals at the execution lifecycle level, so that delegated signals are
     * handled identically to the corresponding reconciliation signals.
     */
    val participantExecSignalClass: ParticipantExecutionSignalContract.ExecSignalClass
        get() = ParticipantExecutionSignalContract.classifyDelegated(kind)

    /**
     * Alias that makes transfer-layer semantics explicit for convergence work.
     *
     * Delegated transfer continuity is bound to the attached runtime session context and
     * should not be treated as a generic session identifier.
     */
    val delegationTransferSessionId: String
        get() = attachedSessionId

    // ── Replay helpers ────────────────────────────────────────────────────────

    /**
     * Returns a replay copy of this signal with an updated [timestampMs] but with the
     * **same [signalId] and [emissionSeq]**.
     *
     * Use this method whenever the same logical signal needs to be re-emitted (e.g. after
     * a send failure, a transport reconnect, or a recovery attempt).  Preserving [signalId]
     * and [emissionSeq] ensures the host can detect the re-delivery as a duplicate of the
     * original emission — not as a brand-new signal — and can safely discard it without
     * side-effects.
     *
     * Calling the factory methods ([ack], [progress], [result]) instead would generate a
     * fresh [signalId], which would cause the host to treat the replay as a new, distinct
     * emission, breaking idempotency and potentially corrupting host-side ordering state.
     *
     * @param replayTimestampMs  Epoch-ms timestamp to stamp on the replay copy; defaults to
     *                           the current wall clock so the host can distinguish "when was
     *                           the signal first emitted" ([timestampMs] of the original)
     *                           from "when was it last re-sent" (this field).
     * @return A copy of this signal with [timestampMs] replaced by [replayTimestampMs] and
     *         all other fields — critically [signalId] and [emissionSeq] — preserved verbatim.
     */
    fun replayAt(
        replayTimestampMs: Long = System.currentTimeMillis()
    ): DelegatedExecutionSignal = copy(timestampMs = replayTimestampMs)

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
     *  - [KEY_SIGNAL_ID]                — stable UUID idempotency key for this emission.
     *  - [KEY_EMISSION_SEQ]             — monotonic position in the canonical emission sequence.
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
        put(KEY_SIGNAL_ID, signalId)
        put(KEY_EMISSION_SEQ, emissionSeq)
        resultKind?.let { put(KEY_RESULT_KIND, it.wireValue) }
        // ── PR-bridge: Delegated flow bridge identity ─────────────────────────
        delegatedFlowId?.let { put(KEY_DELEGATED_FLOW_ID, it) }
        flowLineageId?.let { put(KEY_FLOW_LINEAGE_ID, it) }
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

        /**
         * Metadata key for the [signalId] UUID idempotency key.
         *
         * Always present in [toMetadataMap].  The host uses this value to deduplicate
         * re-deliveries of the same logical signal emission (PR-15).
         */
        const val KEY_SIGNAL_ID = "exec_signal_id"

        /**
         * Metadata key for the [emissionSeq] monotonic sequence number.
         *
         * Always present in [toMetadataMap].  The host uses this value to detect
         * out-of-order delivery: [EMISSION_SEQ_ACK] (1) → [EMISSION_SEQ_PROGRESS] (2) →
         * [EMISSION_SEQ_RESULT] (3) (PR-15).
         */
        const val KEY_EMISSION_SEQ = "exec_signal_emission_seq"

        // ── PR-bridge: Delegated flow bridge identity key constants ───────────

        /**
         * Metadata key for the V2 canonical delegated flow entity identifier.
         *
         * Present in [toMetadataMap] when [delegatedFlowId] is non-null.
         * Absent for signals emitted before PR-bridge.
         * Value type: String — sourced from [AndroidDelegatedFlowBridge.delegatedFlowId].
         */
        const val KEY_DELEGATED_FLOW_ID = "exec_signal_delegated_flow_id"

        /**
         * Metadata key for the V2 canonical delegated flow lineage identity.
         *
         * Present in [toMetadataMap] when [flowLineageId] is non-null.
         * Absent for signals emitted before PR-bridge.
         * Value type: String — sourced from [AndroidDelegatedFlowBridge.flowLineageId].
         */
        const val KEY_FLOW_LINEAGE_ID = "exec_signal_flow_lineage_id"

        /**
         * Canonical emission-sequence position for [Kind.ACK] signals.
         *
         * The ACK signal is always the first emitted in a delegated execution lifecycle.
         */
        const val EMISSION_SEQ_ACK = 1

        /**
         * Canonical emission-sequence position for [Kind.PROGRESS] signals.
         *
         * The PROGRESS signal is emitted second, once the execution pipeline enters the
         * ACTIVE state.
         */
        const val EMISSION_SEQ_PROGRESS = 2

        /**
         * Canonical emission-sequence position for [Kind.RESULT] signals.
         *
         * The RESULT signal is always the last emitted, carrying the terminal outcome.
         */
        const val EMISSION_SEQ_RESULT = 3

        // ── Factory methods ───────────────────────────────────────────────────

        /**
         * Produces an [Kind.ACK] signal from the current [tracker] snapshot.
         *
         * Emit this signal immediately after [DelegatedRuntimeReceiver] accepts the inbound
         * unit and the initial tracker has been created (i.e. record is
         * [DelegatedActivationRecord.ActivationStatus.PENDING]).
         *
         * @param tracker     The current [DelegatedExecutionTracker].
         * @param timestampMs Epoch-ms timestamp; defaults to the current time.
         * @param signalId    Stable UUID idempotency key for this emission; defaults to a
         *                    freshly generated [java.util.UUID.randomUUID].  Supply an
         *                    explicit value when replaying a previously generated signal.
         * @return A [Kind.ACK] signal anchored to the tracker's identity.
         */
        fun ack(
            tracker: DelegatedExecutionTracker,
            timestampMs: Long = System.currentTimeMillis(),
            signalId: String = java.util.UUID.randomUUID().toString()
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
            timestampMs = timestampMs,
            signalId = signalId,
            emissionSeq = EMISSION_SEQ_ACK,
            // ── PR-bridge: propagate flow identity from tracker ───────────────
            delegatedFlowId = tracker.delegatedFlowId.ifEmpty { null },
            flowLineageId = tracker.flowLineageId.ifEmpty { null }
        )

        /**
         * Produces a [Kind.PROGRESS] signal from the current [tracker] snapshot.
         *
         * Emit this signal after each execution step or at other significant milestones
         * during active execution.  The signal carries the current [DelegatedExecutionTracker.stepCount]
         * so the host can detect forward progress.
         *
         * @param tracker     The current [DelegatedExecutionTracker].
         * @param timestampMs Epoch-ms timestamp; defaults to the current time.
         * @param signalId    Stable UUID idempotency key for this emission; defaults to a
         *                    freshly generated [java.util.UUID.randomUUID].  Supply an
         *                    explicit value when replaying a previously generated signal.
         * @return A [Kind.PROGRESS] signal anchored to the tracker's identity.
         */
        fun progress(
            tracker: DelegatedExecutionTracker,
            timestampMs: Long = System.currentTimeMillis(),
            signalId: String = java.util.UUID.randomUUID().toString()
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
            timestampMs = timestampMs,
            signalId = signalId,
            emissionSeq = EMISSION_SEQ_PROGRESS,
            // ── PR-bridge: propagate flow identity from tracker ───────────────
            delegatedFlowId = tracker.delegatedFlowId.ifEmpty { null },
            flowLineageId = tracker.flowLineageId.ifEmpty { null }
        )

        /**
         * Produces a [Kind.RESULT] signal from the current [tracker] snapshot.
         *
         * Emit this signal exactly once when execution reaches a terminal state.  The
         * [resultKind] discriminator carries the outcome so the host can update its
         * session truth without inspecting raw status strings.
         *
         * @param tracker     The current [DelegatedExecutionTracker] (should be terminal).
         * @param resultKind  The terminal outcome: [ResultKind.COMPLETED], [ResultKind.FAILED],
         *                    [ResultKind.TIMEOUT], [ResultKind.CANCELLED], or [ResultKind.REJECTED].
         * @param timestampMs Epoch-ms timestamp; defaults to the current time.
         * @param signalId    Stable UUID idempotency key for this emission; defaults to a
         *                    freshly generated [java.util.UUID.randomUUID].  Supply an
         *                    explicit value when replaying a previously generated signal.
         * @return A [Kind.RESULT] signal anchored to the tracker's identity.
         */
        fun result(
            tracker: DelegatedExecutionTracker,
            resultKind: ResultKind,
            timestampMs: Long = System.currentTimeMillis(),
            signalId: String = java.util.UUID.randomUUID().toString()
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
            timestampMs = timestampMs,
            signalId = signalId,
            emissionSeq = EMISSION_SEQ_RESULT,
            // ── PR-bridge: propagate flow identity from tracker ───────────────
            delegatedFlowId = tracker.delegatedFlowId.ifEmpty { null },
            flowLineageId = tracker.flowLineageId.ifEmpty { null }
        )

        /**
         * Produces a [Kind.RESULT] signal with [ResultKind.TIMEOUT] from [tracker].
         *
         * Convenience factory; equivalent to `result(tracker, ResultKind.TIMEOUT, timestampMs, signalId)`.
         *
         * Emit when the execution pipeline or surrounding coroutine scope signals a timeout
         * (e.g. [kotlinx.coroutines.TimeoutCancellationException]).
         *
         * @param tracker     The current [DelegatedExecutionTracker] (should be in FAILED state).
         * @param timestampMs Epoch-ms timestamp; defaults to the current time.
         * @param signalId    Stable UUID idempotency key; defaults to a fresh UUID.
         * @return A [Kind.RESULT] / [ResultKind.TIMEOUT] signal anchored to the tracker's identity.
         */
        fun timeout(
            tracker: DelegatedExecutionTracker,
            timestampMs: Long = System.currentTimeMillis(),
            signalId: String = java.util.UUID.randomUUID().toString()
        ): DelegatedExecutionSignal = result(tracker, ResultKind.TIMEOUT, timestampMs, signalId)

        /**
         * Produces a [Kind.RESULT] signal with [ResultKind.CANCELLED] from [tracker].
         *
         * Convenience factory; equivalent to `result(tracker, ResultKind.CANCELLED, timestampMs, signalId)`.
         *
         * Emit when execution is interrupted by an external cancellation request
         * (e.g. [kotlinx.coroutines.CancellationException] that is not a timeout).
         *
         * @param tracker     The current [DelegatedExecutionTracker] (should be in FAILED state).
         * @param timestampMs Epoch-ms timestamp; defaults to the current time.
         * @param signalId    Stable UUID idempotency key; defaults to a fresh UUID.
         * @return A [Kind.RESULT] / [ResultKind.CANCELLED] signal anchored to the tracker's identity.
         */
        fun cancelled(
            tracker: DelegatedExecutionTracker,
            timestampMs: Long = System.currentTimeMillis(),
            signalId: String = java.util.UUID.randomUUID().toString()
        ): DelegatedExecutionSignal = result(tracker, ResultKind.CANCELLED, timestampMs, signalId)
    }
}

// ── PR-16: Outbound payload mapping ───────────────────────────────────────────────────────

/**
 * Maps this [DelegatedExecutionSignal] to a [com.ufo.galaxy.protocol.DelegatedExecutionSignalPayload]
 * suitable for transmission as a [com.ufo.galaxy.protocol.MsgType.DELEGATED_EXECUTION_SIGNAL]
 * AIP v3 message.
 *
 * All required identity fields ([signalId], [emissionSeq], [taskId], [traceId],
 * [attachedSessionId], [handoffContractVersion]) are echoed verbatim so the main-repo
 * tracker can correlate this signal with its own dispatch record.  [resultKind] is
 * mapped to its wire value for RESULT signals and left `null` otherwise.
 *
 * @param deviceId Stable Android device identifier to embed in the payload.
 * @return A [com.ufo.galaxy.protocol.DelegatedExecutionSignalPayload] ready for
 *         JSON serialisation and uplink via [com.ufo.galaxy.network.GalaxyWebSocketClient.sendJson].
 */
fun DelegatedExecutionSignal.toOutboundPayload(
    deviceId: String
): com.ufo.galaxy.protocol.DelegatedExecutionSignalPayload =
    com.ufo.galaxy.protocol.DelegatedExecutionSignalPayload(
        signal_id              = signalId,
        emission_seq           = emissionSeq,
        task_id                = taskId,
        trace_id               = traceId,
        attached_session_id    = attachedSessionId,
        device_id              = deviceId,
        handoff_contract_version = handoffContractVersion,
        signal_kind            = kind.wireValue,
        unit_id                = unitId,
        step_count             = stepCount,
        activation_status_hint = activationStatusHint,
        timestamp_ms           = timestampMs,
        result_kind            = resultKind?.wireValue,
        // ── PR-bridge: propagate flow bridge identity for end-to-end V2 correlation ──
        delegated_flow_id      = delegatedFlowId,
        flow_lineage_id        = flowLineageId
    )
