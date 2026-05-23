package com.ufo.galaxy.runtime

/**
 * PR-125 — Android V2 Temporal Continuation Finality Contract.
 *
 * Classifies Android-side execution continuation and finality signals into
 * machine-actionable [ContinuationFinalityClass] values so V2 can apply stricter
 * Temporal-backed execution semantics without over-reading ambiguous completion output.
 *
 * ## Problem addressed
 *
 * V2's stricter Temporal-backed execution path requires Android to distinguish between:
 *  - Genuinely final executions (V2's Temporal workflow can close safely)
 *  - Active continuations (Android is mid-execution; V2 workflow must await more signals)
 *  - Interrupted-then-resume-pending states (Android was interrupted but may resume; V2 workflow
 *    must not close prematurely)
 *  - Signals that do not participate in a Temporal-backed execution path
 *
 * Without this classification, V2's Temporal workflow risks closing prematurely when Android
 * emits a terminal-looking signal that is actually a continuation, an interrupted-but-resumable
 * result, or a non-Temporal-path signal.  This is the "ambiguous completion output" gap that
 * Stage 9 addresses.
 *
 * This contract formalises continuation/finality classification as a machine-actionable
 * [ContinuationFinalityClass] and exposes stable wire keys and an alignment map so V2 can
 * route signals to the correct Temporal workflow handling path.
 *
 * ## Continuation finality classes
 *
 * | [ContinuationFinalityClass]          | Meaning                                                       | V2 Temporal action                                 |
 * |-------------------------------------|--------------------------------------------------------------|---------------------------------------------------|
 * | [WORKFLOW_COMPLETE_FINAL]           | Android reached a genuine final terminal state               | Close Temporal workflow; apply success/failure policy |
 * | [WORKFLOW_CONTINUATION_ACTIVE]      | Android is mid-execution; more signals will follow           | Keep Temporal workflow open; await next signal    |
 * | [WORKFLOW_INTERRUPTED_RESUME_PENDING] | Android interrupted; resume signal may still arrive        | Hold Temporal workflow open; do not close yet     |
 * | [NOT_TEMPORAL_WORKFLOW_PATH]        | Signal does not participate in a Temporal-backed execution   | No Temporal workflow action required              |
 *
 * ## Wire integration
 *
 * [KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS] is embedded in [ReconciliationSignal] payload for
 * terminal and continuation-bearing signal kinds so V2's Temporal-backed execution path receives
 * a structured finality class alongside the raw terminal outcome.
 *
 * ## Boundary constraint
 *
 * Android MUST NOT escalate a [NOT_TEMPORAL_WORKFLOW_PATH] or [WORKFLOW_CONTINUATION_ACTIVE]
 * classification to [WORKFLOW_COMPLETE_FINAL] to accelerate Temporal workflow closure.
 * The classification MUST accurately reflect Android-side execution reality.  V2 owns all
 * canonical Temporal workflow closure decisions; Android only supplies the structured evidence.
 *
 * @see ReconciliationSignal
 * @see RuntimeController
 */
object AndroidV2TemporalContinuationFinalityContract {

    /** Android PR that introduced this contract. */
    const val INTRODUCED_PR = 125

    /** Wire schema version for this contract's continuation finality fields. */
    const val SCHEMA_VERSION = "1"

    // ── Wire key constants ─────────────────────────────────────────────────────

    /**
     * Wire key for the [ContinuationFinalityClass.wireValue] in task-bearing signal payloads.
     *
     * V2 MUST read this key to determine whether the Android-side execution has reached a
     * genuinely final state before closing any associated Temporal workflow.  When absent,
     * V2 SHOULD treat the signal as [ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH]
     * for backward compatibility with pre-PR-125 signals.
     */
    const val KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS = "temporal_continuation_finality_class"

    /**
     * Wire key indicating whether V2's Temporal workflow can safely close for this signal.
     *
     * Value: `"true"` or `"false"`.  When `"false"`, V2 MUST NOT close the Temporal workflow
     * based solely on this signal — either more signals are expected or the execution was
     * interrupted and a resume signal may arrive.
     */
    const val KEY_IS_TEMPORAL_WORKFLOW_FINAL = "temporal_is_workflow_final"

    /**
     * Wire key indicating whether a pending Temporal workflow resume is possible.
     *
     * Value: `"true"` or `"false"`.  When `"true"`, V2 MUST hold the Temporal workflow open
     * and await a follow-up signal before making any canonical closure decision.  A `"true"`
     * value here is incompatible with [KEY_IS_TEMPORAL_WORKFLOW_FINAL] being `"true"`.
     */
    const val KEY_HAS_PENDING_TEMPORAL_RESUME = "temporal_has_pending_resume"

    /**
     * Wire key for this contract's [SCHEMA_VERSION].
     */
    const val KEY_TEMPORAL_CONTINUATION_FINALITY_SCHEMA_VERSION =
        "temporal_continuation_finality_schema_version"

    /**
     * Wire key for the V2-issued Temporal workflow run identifier, when available.
     *
     * Present in task-bearing [ReconciliationSignal] payloads when the Android-side
     * activation was initiated under a concrete Temporal workflow run.  V2 uses this
     * value to correlate Android signals back to the correct Temporal workflow run
     * when enforcing stricter workflow-backed execution semantics.
     *
     * Absence means no Temporal workflow run ID was supplied for this activation;
     * V2 SHOULD classify the signal as [ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH].
     */
    const val KEY_TEMPORAL_WORKFLOW_RUN_ID = "temporal_workflow_run_id"

    // ── ContinuationFinalityClass ──────────────────────────────────────────────

    /**
     * Structured continuation/finality class for Android-side execution signals relative
     * to V2's Temporal-backed execution path.
     *
     * @property wireValue                   Stable lowercase string for wire transport.
     * @property isWorkflowFinal             True when V2's Temporal workflow can safely close
     *                                       based on this signal class alone.
     * @property hasPendingTemporalResume    True when a follow-up resume signal may arrive;
     *                                       V2 must hold the Temporal workflow open.
     */
    enum class ContinuationFinalityClass(
        val wireValue: String,
        val isWorkflowFinal: Boolean,
        val hasPendingTemporalResume: Boolean
    ) {

        /**
         * Android execution reached a genuine final terminal state.
         *
         * Android produced a completed, failed, or definitively terminal result with no
         * expected follow-up signals for this execution era.  V2's Temporal workflow can
         * safely apply its closure policy (success, failure, or cancellation handler) without
         * risk of premature closure.
         *
         * Corresponds to [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION],
         * [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FAILURE], or
         * [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.ABORT] when the
         * execution is Temporal-workflow-participant and no pending resume is expected.
         */
        WORKFLOW_COMPLETE_FINAL(
            wireValue = "workflow_complete_final",
            isWorkflowFinal = true,
            hasPendingTemporalResume = false
        ),

        /**
         * Android execution is actively in progress; more signals will follow.
         *
         * Android has accepted the task and is mid-execution.  The current signal is an
         * intermediate progress or acceptance notification; terminal signals have not yet
         * been emitted.  V2's Temporal workflow MUST remain open and await the terminal
         * signal before making any closure decision.
         *
         * Corresponds to [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.NON_TERMINAL]
         * or any non-terminal signal kind when the activation is Temporal-workflow-participant.
         */
        WORKFLOW_CONTINUATION_ACTIVE(
            wireValue = "workflow_continuation_active",
            isWorkflowFinal = false,
            hasPendingTemporalResume = true
        ),

        /**
         * Android execution was interrupted; a resume signal may still arrive.
         *
         * Android's execution was interrupted (session disconnect, timeout, or partial
         * completion) but the task may be resumed in a subsequent activation era.  V2's
         * Temporal workflow MUST NOT close prematurely; it should await a follow-up
         * resume or recovery signal before making a canonical closure decision.
         *
         * Corresponds to [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION],
         * [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT], or
         * [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION]
         * when the activation is Temporal-workflow-participant.
         */
        WORKFLOW_INTERRUPTED_RESUME_PENDING(
            wireValue = "workflow_interrupted_resume_pending",
            isWorkflowFinal = false,
            hasPendingTemporalResume = true
        ),

        /**
         * The signal does not participate in a Temporal-backed execution path.
         *
         * Either no Temporal workflow run ID was supplied for this activation, or the
         * signal is a non-task lifecycle or participant state update that is not scoped
         * to a Temporal-backed execution era.  V2 should skip Temporal workflow routing
         * for this signal and handle it through its legacy or session-only path.
         */
        NOT_TEMPORAL_WORKFLOW_PATH(
            wireValue = "not_temporal_workflow_path",
            isWorkflowFinal = false,
            hasPendingTemporalResume = false
        );

        companion object {
            /** All stable wire values. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()

            fun fromWireValue(value: String?): ContinuationFinalityClass? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── classify ──────────────────────────────────────────────────────────────

    /**
     * Returns the [ContinuationFinalityClass] for the given execution context.
     *
     * This is the canonical entry point for classifying Android-side continuation/finality
     * before emitting any [ReconciliationSignal] task signal.  Callers MUST use this method
     * rather than constructing finality classifications ad hoc.
     *
     * Priority order (most specific wins):
     * 1. [isTemporalWorkflowParticipant] false → [NOT_TEMPORAL_WORKFLOW_PATH]
     * 2. [terminalOutcomeKind] == NON_TERMINAL or [isContinuation] true → [WORKFLOW_CONTINUATION_ACTIVE]
     * 3. [terminalOutcomeKind] in {INTERRUPTION, TIMEOUT, PARTIAL_COMPLETION} → [WORKFLOW_INTERRUPTED_RESUME_PENDING]
     * 4. [terminalOutcomeKind] in {COMPLETION, FAILURE, ABORT, RECOVERY, FALLBACK} → [WORKFLOW_COMPLETE_FINAL]
     * 5. Everything else → [WORKFLOW_COMPLETE_FINAL]
     *
     * @param terminalOutcomeKind          The [AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind]
     *                                     already classified for this signal.
     * @param isTemporalWorkflowParticipant True when this activation was initiated under a
     *                                     concrete Temporal workflow run (i.e. a non-null
     *                                     [temporalWorkflowRunId] was supplied).
     * @param isContinuation               True when this signal represents an in-progress
     *                                     continuation, not a terminal or fresh-start signal.
     */
    fun classify(
        terminalOutcomeKind: AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind,
        isTemporalWorkflowParticipant: Boolean,
        isContinuation: Boolean = false
    ): ContinuationFinalityClass {
        if (!isTemporalWorkflowParticipant) return ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH

        return when {
            terminalOutcomeKind ==
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.NON_TERMINAL ||
                isContinuation ->
                ContinuationFinalityClass.WORKFLOW_CONTINUATION_ACTIVE

            terminalOutcomeKind in setOf(
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION,
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT,
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION
            ) ->
                ContinuationFinalityClass.WORKFLOW_INTERRUPTED_RESUME_PENDING

            else ->
                ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL
        }
    }

    // ── toWireMap ─────────────────────────────────────────────────────────────

    /**
     * Returns a wire-safe map representation of the [continuationFinalityClass] and associated
     * metadata for embedding in [ReconciliationSignal] payload.
     *
     * @param continuationFinalityClass  The classified continuation/finality class.
     * @param temporalWorkflowRunId      The V2-issued Temporal workflow run identifier; null if absent.
     */
    fun toWireMap(
        continuationFinalityClass: ContinuationFinalityClass,
        temporalWorkflowRunId: String? = null
    ): Map<String, String> = buildMap {
        put(KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS, continuationFinalityClass.wireValue)
        put(KEY_IS_TEMPORAL_WORKFLOW_FINAL, continuationFinalityClass.isWorkflowFinal.toString())
        put(KEY_HAS_PENDING_TEMPORAL_RESUME, continuationFinalityClass.hasPendingTemporalResume.toString())
        put(KEY_TEMPORAL_CONTINUATION_FINALITY_SCHEMA_VERSION, SCHEMA_VERSION)
        if (temporalWorkflowRunId != null) {
            put(KEY_TEMPORAL_WORKFLOW_RUN_ID, temporalWorkflowRunId)
        }
    }

    // ── V2_TEMPORAL_CONTINUATION_ALIGNMENT_MAP ────────────────────────────────

    /**
     * Human-readable alignment map from [ContinuationFinalityClass.wireValue] to the V2
     * Temporal workflow handling path that should be invoked.
     *
     * This map is consumed at audit/observability time; runtime code must use [classify]
     * and [toWireMap] directly.
     */
    val V2_TEMPORAL_CONTINUATION_ALIGNMENT_MAP: Map<String, String> = mapOf(
        ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL.wireValue to
            "v2/temporal/close — apply Temporal workflow closure policy (success/failure/cancel)",
        ContinuationFinalityClass.WORKFLOW_CONTINUATION_ACTIVE.wireValue to
            "v2/temporal/await — keep Temporal workflow open; await next terminal signal",
        ContinuationFinalityClass.WORKFLOW_INTERRUPTED_RESUME_PENDING.wireValue to
            "v2/temporal/hold — hold Temporal workflow open; await resume or recovery signal",
        ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH.wireValue to
            "v2/temporal/skip — not a Temporal workflow path; route via legacy or session-only handling"
    )

    // ── TEMPORAL_CONTINUATION_INVARIANTS ──────────────────────────────────────

    /**
     * Machine-verifiable invariants for the Stage 9 temporal continuation finality contract.
     * All values must be `true`; any `false` indicates a regression.
     */
    val TEMPORAL_CONTINUATION_INVARIANTS: Map<String, Boolean> = mapOf(

        // WORKFLOW_COMPLETE_FINAL is the only class where isWorkflowFinal=true
        "only_workflow_complete_final_has_is_workflow_final_true" to
            (ContinuationFinalityClass.entries.count { it.isWorkflowFinal } == 1 &&
                ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL.isWorkflowFinal),

        // WORKFLOW_COMPLETE_FINAL does not have a pending resume
        "workflow_complete_final_has_no_pending_resume" to
            !ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL.hasPendingTemporalResume,

        // WORKFLOW_CONTINUATION_ACTIVE has a pending resume
        "workflow_continuation_active_has_pending_resume" to
            ContinuationFinalityClass.WORKFLOW_CONTINUATION_ACTIVE.hasPendingTemporalResume,

        // WORKFLOW_INTERRUPTED_RESUME_PENDING has a pending resume
        "workflow_interrupted_resume_pending_has_pending_resume" to
            ContinuationFinalityClass.WORKFLOW_INTERRUPTED_RESUME_PENDING.hasPendingTemporalResume,

        // NOT_TEMPORAL_WORKFLOW_PATH is not workflow-final and has no pending resume
        "not_temporal_workflow_path_is_not_final" to
            !ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH.isWorkflowFinal,

        "not_temporal_workflow_path_has_no_pending_resume" to
            !ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH.hasPendingTemporalResume,

        // isWorkflowFinal and hasPendingTemporalResume are mutually exclusive per class
        "is_workflow_final_and_has_pending_resume_are_mutually_exclusive" to
            ContinuationFinalityClass.entries.none { it.isWorkflowFinal && it.hasPendingTemporalResume },

        // Non-participant classifies as NOT_TEMPORAL_WORKFLOW_PATH regardless of outcome
        "non_participant_classifies_as_not_temporal_workflow_path" to
            (classify(
                terminalOutcomeKind =
                    AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION,
                isTemporalWorkflowParticipant = false
            ) == ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH),

        // COMPLETION outcome with participant classifies as WORKFLOW_COMPLETE_FINAL
        "completion_with_participant_classifies_as_workflow_complete_final" to
            (classify(
                terminalOutcomeKind =
                    AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION,
                isTemporalWorkflowParticipant = true
            ) == ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL),

        // INTERRUPTION outcome with participant classifies as WORKFLOW_INTERRUPTED_RESUME_PENDING
        "interruption_with_participant_classifies_as_workflow_interrupted_resume_pending" to
            (classify(
                terminalOutcomeKind =
                    AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION,
                isTemporalWorkflowParticipant = true
            ) == ContinuationFinalityClass.WORKFLOW_INTERRUPTED_RESUME_PENDING),

        // TIMEOUT outcome with participant classifies as WORKFLOW_INTERRUPTED_RESUME_PENDING
        "timeout_with_participant_classifies_as_workflow_interrupted_resume_pending" to
            (classify(
                terminalOutcomeKind =
                    AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT,
                isTemporalWorkflowParticipant = true
            ) == ContinuationFinalityClass.WORKFLOW_INTERRUPTED_RESUME_PENDING),

        // NON_TERMINAL outcome with participant classifies as WORKFLOW_CONTINUATION_ACTIVE
        "non_terminal_with_participant_classifies_as_workflow_continuation_active" to
            (classify(
                terminalOutcomeKind =
                    AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.NON_TERMINAL,
                isTemporalWorkflowParticipant = true
            ) == ContinuationFinalityClass.WORKFLOW_CONTINUATION_ACTIVE),

        // isContinuation=true with participant classifies as WORKFLOW_CONTINUATION_ACTIVE
        "continuation_flag_with_participant_classifies_as_workflow_continuation_active" to
            (classify(
                terminalOutcomeKind =
                    AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION,
                isTemporalWorkflowParticipant = true,
                isContinuation = true
            ) == ContinuationFinalityClass.WORKFLOW_CONTINUATION_ACTIVE),

        // All wire values are distinct
        "all_wire_values_are_distinct" to
            (ContinuationFinalityClass.entries.map { it.wireValue }.toSet().size ==
                ContinuationFinalityClass.entries.size),

        // V2 alignment map covers all continuation finality classes
        "v2_alignment_map_covers_all_classes" to
            ContinuationFinalityClass.entries.all {
                V2_TEMPORAL_CONTINUATION_ALIGNMENT_MAP.containsKey(it.wireValue)
            }
    )
}
