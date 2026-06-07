package com.ufo.galaxy.runtime

/**
 * **Canonical Android-side delegated-runtime execution-tracking state** (PR-10,
 * post-#533 dual-repo runtime unification master plan — Android-Side Delegated-Runtime
 * Execution-Tracking and Acknowledgment Basis, Android side).
 *
 * [DelegatedExecutionTracker] is the authoritative local representation for what is
 * happening to a delegated work unit **after** [DelegatedRuntimeReceiver] has accepted
 * the inbound envelope and produced the initial [DelegatedActivationRecord].
 *
 * ## Design intent
 *
 * Before PR-10 Android had a clean activation record ([DelegatedActivationRecord]) that
 * carries lifecycle status (PENDING → ACTIVATING → ACTIVE → COMPLETED / FAILED) and a
 * handoff contract ([DelegatedHandoffContract]) that carries the delegation metadata.
 * However, there was no single Android-side object that:
 *
 *  - Accumulates **execution-progress state** (step count, execution-start time, last-step
 *    time) as execution proceeds.
 *  - Tracks the **continuity** between the lifecycle status transitions, progress events,
 *    and the session / handoff identity that should be echoed on every outbound signal.
 *  - Serves as the stable, typed source from which [DelegatedExecutionSignal] instances
 *    (ACK / PROGRESS / RESULT) are derived, ensuring that every signal carries consistent
 *    identity metadata.
 *
 * [DelegatedExecutionTracker] fills this gap.  It wraps the current
 * [DelegatedActivationRecord] snapshot and adds the progress-tracking fields that are
 * absent from the immutable record.  Like [DelegatedActivationRecord] it is itself
 * **immutable** — callers advance it by calling [advance], [recordStep], and
 * [markExecutionStarted], each of which returns a new instance.
 *
 * ## Lifecycle alignment
 *
 * The tracker mirrors the activation-record lifecycle:
 *
 * ```
 *   create(record)                    (record must be PENDING)
 *       │
 *       ▼
 *   ┌─────────┐  markExecutionStarted()  ┌────────────┐  advance(ACTIVE)  ┌────────┐
 *   │ PENDING  │ ──────────────────────▶ │ ACTIVATING │ ────────────────▶ │ ACTIVE │
 *   └─────────┘                          └────────────┘                    └────────┘
 *                                                                               │
 *                            recordStep() [0..n]                                │
 *                            ◄─────────────────────────────────────────────────┘
 *                                                                               │
 *                        advance(COMPLETED) or advance(FAILED)                  │
 *                            └─────────────────────────────────────────────────▶ terminal
 * ```
 *
 * ## Obtaining an instance
 *
 * Use [DelegatedExecutionTracker.create] with the initial [DelegatedActivationRecord]
 * produced by [DelegatedRuntimeReceiver]:
 *
 * ```kotlin
 * val tracker = DelegatedExecutionTracker.create(record = receiptResult.record)
 * val activating = tracker.markExecutionStarted()
 *                         .advance(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
 * val withStep   = activating.advance(DelegatedActivationRecord.ActivationStatus.ACTIVE)
 *                            .recordStep()
 * ```
 *
 * @property record              Current [DelegatedActivationRecord] snapshot; transitions
 *                               immutably via [advance].
 * @property stepCount           Number of execution steps recorded so far via [recordStep].
 * @property executionStartedAtMs Epoch-ms timestamp when [markExecutionStarted] was first
 *                               called; `null` before execution starts.
 * @property lastStepAtMs        Epoch-ms timestamp of the most recently recorded step;
 *                               `null` when [stepCount] is zero.
 * @property handoffContractVersion Contract schema version echoed from
 *                               [com.ufo.galaxy.agent.DelegatedHandoffContract.CURRENT_CONTRACT_VERSION]
 *                               at tracker-creation time; stable across transitions.
 */
data class DelegatedExecutionTracker(
    val record: DelegatedActivationRecord,
    val stepCount: Int = 0,
    val executionStartedAtMs: Long? = null,
    val lastStepAtMs: Long? = null,
    val handoffContractVersion: Int = com.ufo.galaxy.agent.DelegatedHandoffContract.CURRENT_CONTRACT_VERSION
) {

    // ── Convenience accessors ─────────────────────────────────────────────────

    /** Stable delegated-unit identifier; echoes [DelegatedActivationRecord.unit].unitId. */
    val unitId: String
        get() = record.unit.unitId

    /** Task identifier; echoes [DelegatedActivationRecord.unit].taskId. */
    val taskId: String
        get() = record.unit.taskId

    /** End-to-end trace identifier; echoes [DelegatedActivationRecord.unit].traceId. */
    val traceId: String
        get() = record.unit.traceId

    /** Session identifier this tracking is running under. */
    val attachedSessionId: String
        get() = record.attachedSessionId

    /**
     * Stable identifier for the V2 canonical delegated flow entity this tracker belongs to.
     *
     * Sourced from [com.ufo.galaxy.agent.DelegatedRuntimeUnit.delegatedFlowId]; empty string
     * when the unit was produced by a pre-bridge sender (callers should fall back to [unitId]).
     */
    val delegatedFlowId: String
        get() = record.unit.delegatedFlowId

    /**
     * Lineage identity of the V2 canonical delegated flow entity.
     *
     * Sourced from [com.ufo.galaxy.agent.DelegatedRuntimeUnit.flowLineageId]; empty string
     * when the unit was produced by a pre-bridge sender (callers should fall back to [taskId]).
     */
    val flowLineageId: String
        get() = record.unit.flowLineageId

    /** `true` when the underlying record is in a terminal state. */
    val isTerminal: Boolean
        get() = record.isTerminal

    /** `true` when the underlying record is not yet in a terminal state. */
    val isActive: Boolean
        get() = record.isActive

    /**
     * Wall-clock elapsed time since [executionStartedAtMs] in milliseconds.
     * `null` when [executionStartedAtMs] is `null` (execution has not yet started).
     *
     * @param nowMs  Current epoch-ms timestamp; defaults to the system clock.
     */
    fun elapsedMs(nowMs: Long = System.currentTimeMillis()): Long? =
        executionStartedAtMs?.let { nowMs - it }

    // ── Mutation helpers (immutable copies) ───────────────────────────────────

    /**
     * Returns a new [DelegatedExecutionTracker] with the underlying record advanced to
     * [newStatus].
     *
     * If the underlying record is already [isTerminal], the original tracker is returned
     * unchanged (mirrors the terminal-guard in [DelegatedActivationRecord.transition]).
     *
     * @param newStatus Target [DelegatedActivationRecord.ActivationStatus].
     * @return New tracker with the record transitioned to [newStatus], or this instance
     *         unchanged when the record was already terminal.
     */
    fun advance(newStatus: DelegatedActivationRecord.ActivationStatus): DelegatedExecutionTracker {
        val newRecord = record.transition(newStatus)
        if (newRecord === record) return this  // terminal guard: record unchanged
        return copy(record = newRecord)
    }

    /**
     * Returns a new [DelegatedExecutionTracker] with [stepCount] incremented by one and
     * [lastStepAtMs] updated to [stepAtMs].
     *
     * Step recording is a no-op on terminal trackers: the original is returned unchanged.
     *
     * @param stepAtMs  Epoch-ms timestamp for this step; defaults to the current time.
     * @return New tracker with the step recorded, or this instance unchanged when terminal.
     */
    fun recordStep(stepAtMs: Long = System.currentTimeMillis()): DelegatedExecutionTracker {
        if (isTerminal) return this
        return copy(stepCount = stepCount + 1, lastStepAtMs = stepAtMs)
    }

    /**
     * Returns a new [DelegatedExecutionTracker] with [executionStartedAtMs] set to
     * [startedAtMs].
     *
     * If [executionStartedAtMs] is already set, the existing value is preserved — the start
     * timestamp should be captured only once per activation, at the moment the execution
     * coroutine is launched.
     *
     * This is a no-op on terminal trackers.
     *
     * @param startedAtMs  Epoch-ms start timestamp; defaults to the current time.
     * @return New tracker with [executionStartedAtMs] set (if not already), or this instance
     *         unchanged when terminal or when execution was already started.
     */
    fun markExecutionStarted(startedAtMs: Long = System.currentTimeMillis()): DelegatedExecutionTracker {
        if (isTerminal) return this
        if (executionStartedAtMs != null) return this  // only capture the first start
        return copy(executionStartedAtMs = startedAtMs)
    }

    // ── Wire serialisation ────────────────────────────────────────────────────

    /**
     * Builds the canonical metadata map for wire transmission or diagnostic logging.
     *
     * Keys always present:
     *  - [KEY_UNIT_ID]                 — delegated-unit identifier.
     *  - [KEY_TASK_ID]                 — task identifier.
     *  - [KEY_TRACE_ID]                — end-to-end trace identifier.
     *  - [KEY_ATTACHED_SESSION_ID]     — session this tracker runs under.
     *  - [KEY_ACTIVATION_STATUS]       — current [DelegatedActivationRecord.ActivationStatus.wireValue].
     *  - [KEY_STEP_COUNT]              — number of steps recorded so far.
     *  - [KEY_HANDOFF_CONTRACT_VERSION]— handoff contract schema version.
     *
     * Keys present when non-null:
     *  - [KEY_EXECUTION_STARTED_AT_MS] — epoch-ms execution start time.
     *  - [KEY_LAST_STEP_AT_MS]         — epoch-ms last-step time.
     *
     * @return An immutable [Map] suitable for merging into AIP v3 metadata payloads.
     */
    fun toMetadataMap(): Map<String, Any> = buildMap {
        put(KEY_UNIT_ID, unitId)
        put(KEY_TASK_ID, taskId)
        put(KEY_TRACE_ID, traceId)
        put(KEY_ATTACHED_SESSION_ID, attachedSessionId)
        put(KEY_ACTIVATION_STATUS, record.activationStatus.wireValue)
        put(KEY_STEP_COUNT, stepCount)
        put(KEY_HANDOFF_CONTRACT_VERSION, handoffContractVersion)
        executionStartedAtMs?.let { put(KEY_EXECUTION_STARTED_AT_MS, it) }
        lastStepAtMs?.let { put(KEY_LAST_STEP_AT_MS, it) }
    }

    // ── Companion / constants ─────────────────────────────────────────────────

    companion object {

        /** Metadata key for the delegated-unit identifier. */
        const val KEY_UNIT_ID = "exec_tracker_unit_id"

        /** Metadata key for the task identifier. */
        const val KEY_TASK_ID = "exec_tracker_task_id"

        /** Metadata key for the end-to-end trace identifier. */
        const val KEY_TRACE_ID = "exec_tracker_trace_id"

        /** Metadata key for the session identifier. */
        const val KEY_ATTACHED_SESSION_ID = "exec_tracker_attached_session_id"

        /** Metadata key for the current [DelegatedActivationRecord.ActivationStatus.wireValue]. */
        const val KEY_ACTIVATION_STATUS = "exec_tracker_activation_status"

        /** Metadata key for the number of execution steps recorded so far. */
        const val KEY_STEP_COUNT = "exec_tracker_step_count"

        /** Metadata key for the epoch-ms execution start timestamp. Absent until execution starts. */
        const val KEY_EXECUTION_STARTED_AT_MS = "exec_tracker_execution_started_at_ms"

        /** Metadata key for the epoch-ms last-step timestamp. Absent when no steps have been recorded. */
        const val KEY_LAST_STEP_AT_MS = "exec_tracker_last_step_at_ms"

        /** Metadata key for the handoff contract schema version integer. */
        const val KEY_HANDOFF_CONTRACT_VERSION = "exec_tracker_handoff_contract_version"

        /**
         * Creates an initial [DelegatedExecutionTracker] wrapping [record].
         *
         * The record must be in [DelegatedActivationRecord.ActivationStatus.PENDING]; the
         * tracker starts with no steps recorded and no execution-start timestamp.
         *
         * @param record The initial [DelegatedActivationRecord] produced by
         *               [com.ufo.galaxy.agent.DelegatedRuntimeReceiver].
         * @return A new [DelegatedExecutionTracker] ready to accept execution-progress events.
         */
        fun create(record: DelegatedActivationRecord): DelegatedExecutionTracker =
            DelegatedExecutionTracker(
                record = record,
                stepCount = 0,
                executionStartedAtMs = null,
                lastStepAtMs = null,
                handoffContractVersion = com.ufo.galaxy.agent.DelegatedHandoffContract.CURRENT_CONTRACT_VERSION
            )
    }
}
