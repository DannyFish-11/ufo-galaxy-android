package com.ufo.galaxy.runtime

/**
 * PR-63 — Android participant progress and execution feedback surface.
 *
 * This object is the **canonical reviewer reference** for tracing Android participant
 * execution progress and richer feedback through the local runtime pipeline.  It answers
 * the four PR-63 acceptance criteria in one place:
 *
 *  1. **Which richer Android runtime/progress states are now emitted outward?** —
 *     See [PROGRESS_FEEDBACK_SURFACES].
 *
 *  2. **How Android local execution is represented beyond ack/result/error?** —
 *     See [OUTBOUND_EXECUTION_MODELING].
 *
 *  3. **Whether Android participant runtime activity is now more reviewable from outside?** —
 *     See [OBSERVABILITY_SURFACES].
 *
 *  4. **Whether the added feedback remains bounded under participant-local authority?** —
 *     See [AUTHORITY_BOUNDARY_DECLARATIONS].
 *
 * ## Problem addressed
 *
 * Prior PRs established the core Android→V2 signal surfaces:
 *  - PR-51: [AndroidParticipantRuntimeTruth], [ReconciliationSignal], [ActiveTaskStatus]
 *  - PR-52: Signal emission points in [RuntimeController]
 *  - PR-61: [ParticipantRuntimeSemanticsBoundary] — structured boundary declaration
 *  - PR-62: [ParticipantLiveExecutionSurface] — live execution wiring documentation
 *
 * However, intermediate execution progress still left a gap between Android's local
 * AI execution pipeline (planning → grounding → action → result) and what V2 could
 * observe.  The only intermediate signal was [ReconciliationSignal.Kind.TASK_STATUS_UPDATE]
 * with an optional free-form [progressDetail] string — effectively still compressing
 * rich local execution state into a narrow, unstructured surface.
 *
 * PR-63 addresses this by introducing:
 *  - [ParticipantProgressCheckpoint] — named, structured execution-stage markers.
 *  - [SubtaskProgressReport] — typed subtask progress with status, index, and label.
 *  - Richer [ReconciliationSignal] payload key constants for structured progress fields.
 *  - This surface ([ParticipantProgressFeedbackSurface]) as the canonical reviewer reference.
 *
 * ## Responsibility boundary
 *
 * Android owns all content in this surface.  Android decides:
 *  - Which execution stages are checkpointable ([ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS]).
 *  - What a subtask is and when to emit a [SubtaskProgressReport].
 *  - The ordering and identity of all progress signals.
 *
 * V2 receives these signals via [ReconciliationSignal.Kind.TASK_STATUS_UPDATE] with
 * structured payload keys, and may use them for monitoring, display, and observability.
 * V2 **must not** make dispatch or orchestration decisions based on subtask-level or
 * checkpoint-level state.
 *
 * @see ParticipantProgressCheckpoint
 * @see SubtaskProgressReport
 * @see ReconciliationSignal.Kind.TASK_STATUS_UPDATE
 * @see ParticipantLiveExecutionSurface
 * @see ParticipantRuntimeSemanticsBoundary
 */
object ParticipantProgressFeedbackSurface {

    // ── PR-63 acceptance criteria maps ────────────────────────────────────────

    /**
     * **AC-1: Which richer Android runtime/progress states are now emitted outward.**
     *
     * Key: surface name.
     * Value: description of what the surface exposes and how it enriches V2's view.
     */
    val PROGRESS_FEEDBACK_SURFACES: Map<String, String> = mapOf(

        "execution_checkpoint" to
            "ParticipantProgressCheckpoint: named, structured execution-stage markers emitted " +
            "via ReconciliationSignal.Kind.TASK_STATUS_UPDATE payload. Eight canonical stage " +
            "identifiers cover the full Android AI execution pipeline: planning_started, " +
            "planning_complete, grounding_started, grounding_complete, step_executing, " +
            "step_complete, replanning, finalizing. Each checkpoint carries checkpointId, " +
            "stepIndex, optional totalSteps, and optional detail. V2 can distinguish planning " +
            "from grounding from execution stages without parsing unstructured strings.",

        "subtask_progress" to
            "SubtaskProgressReport: typed subtask-level progress report emitted via " +
            "ReconciliationSignal.Kind.TASK_STATUS_UPDATE payload. Each report carries subtaskIndex, " +
            "optional subtaskTotal, subtaskLabel (human-readable step description), and " +
            "subtaskStatus enum (PENDING / EXECUTING / COMPLETE / FAILED / SKIPPED). " +
            "V2 can now track step N-of-M execution progress with typed status rather than " +
            "inferred progress from unstructured strings.",

        "task_status_update_structured_payload" to
            "ReconciliationSignal.Kind.TASK_STATUS_UPDATE payload is now enriched with " +
            "stable KEY_CHECKPOINT_ID, KEY_SUBTASK_INDEX, KEY_SUBTASK_STATUS, KEY_SUBTASK_LABEL, " +
            "KEY_SUBTASK_TOTAL, KEY_STEP_INDEX, KEY_TOTAL_STEPS constants defined on " +
            "ReconciliationSignal. Before PR-63 the only structured key was 'progress_detail' " +
            "(free-form string). V2 now has typed, stable key names for structured progress fields.",

        "checkpoint_known_ids_registry" to
            "ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS: a stable, versioned set of " +
            "all canonical checkpoint identifiers. V2 can use this set to validate received " +
            "checkpoint_id values and distinguish structured known-stage checkpoints from " +
            "arbitrary custom labels. New checkpoint IDs require a PR to be added here, " +
            "ensuring that stage vocabulary is reviewed and stable.",

        "subtask_status_wire_vocabulary" to
            "SubtaskProgressReport.SubtaskStatus enum with stable wireValues: pending, executing, " +
            "complete, failed, skipped. SubtaskStatus.ALL_WIRE_VALUES provides a stable set " +
            "for V2-side validation. SubtaskStatus.fromValue(string) provides safe parsing. " +
            "This replaces the implicit 'in_progress' / 'success' / 'failed' inference from " +
            "TASK_STATUS_UPDATE status strings with explicit subtask-level status discrimination."
    )

    /**
     * **AC-2: How Android local execution is represented beyond ack/result/error.**
     *
     * Key: execution dimension.
     * Value: description of the richer representation and how it is encoded.
     */
    val OUTBOUND_EXECUTION_MODELING: Map<String, String> = mapOf(

        "pre_terminal_stage_progression" to
            "Before PR-63: Android emitted TASK_ACCEPTED → (optional TASK_STATUS_UPDATE with string) " +
            "→ TASK_RESULT / TASK_CANCELLED / TASK_FAILED. The intermediate space was either empty " +
            "or compressed into a single 'in_progress' status with a free-form progressDetail string. " +
            "After PR-63: Android can emit structured TASK_STATUS_UPDATE signals at each " +
            "ParticipantProgressCheckpoint boundary (planning_started, planning_complete, " +
            "grounding_started, grounding_complete, step_executing, step_complete, replanning, " +
            "finalizing), each carrying a typed payload with checkpoint_id and step context. " +
            "The terminal signals (TASK_RESULT / TASK_CANCELLED / TASK_FAILED) are unchanged.",

        "step_level_progress_tracking" to
            "SubtaskProgressReport models individual plan steps with typed status (PENDING / " +
            "EXECUTING / COMPLETE / FAILED / SKIPPED), subtaskIndex (zero-based), subtaskTotal " +
            "(optional plan length), and subtaskLabel (step description). V2 now has a structured " +
            "representation of Android's plan execution as an ordered sequence of typed subtask " +
            "states rather than an opaque execution duration or unstructured status string.",

        "replanning_visibility" to
            "ParticipantProgressCheckpoint.CHECKPOINT_REPLANNING is emitted when Android's local " +
            "AI loop determines a plan revision is needed (e.g. a step failed or new context " +
            "became available). Before PR-63 V2 had no visibility into replanning — it only " +
            "received a TASK_STATUS_UPDATE with an arbitrary string or a TASK_FAILED if replanning " +
            "failed entirely. The CHECKPOINT_REPLANNING checkpoint now surfaces replanning-in-progress " +
            "as a structured, observable Android-local execution event.",

        "finalization_pre_terminal_signal" to
            "ParticipantProgressCheckpoint.CHECKPOINT_FINALIZING is emitted when all plan steps " +
            "have been executed and Android is assembling the final result. This is the last " +
            "pre-terminal progress checkpoint — it occurs immediately before TASK_RESULT. " +
            "V2 can use this checkpoint to distinguish 'task executing steps' from 'task assembling " +
            "result' without waiting for the terminal TASK_RESULT signal, enabling better " +
            "UI progress tracking.",

        "structured_authority_declaration" to
            "ParticipantProgressFeedbackSurface.AUTHORITY_BOUNDARY_DECLARATIONS provides an " +
            "explicit, structured reviewer-facing declaration of which decisions Android makes " +
            "locally (checkpoint and subtask identity, signal ordering, execution stage content) " +
            "versus which decisions V2 must not delegate to Android (retry, rebalance, " +
            "task assignment, orchestration). This keeps the richer feedback bounded under " +
            "Android participant-local authority."
    )

    /**
     * **AC-3: Whether Android participant runtime activity is now more reviewable from outside.**
     *
     * Key: observability improvement name.
     * Value: description of what is now observable and how it can be traced.
     */
    val OBSERVABILITY_SURFACES: Map<String, String> = mapOf(

        "checkpoint_id_stable_vocabulary" to
            "ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS provides a versioned, static " +
            "set of all canonical stage identifiers. A reviewer can enumerate all known " +
            "execution stages Android may cross during a task. The set is programmatically " +
            "verifiable in tests (size invariant checked by Pr8ParticipantProgressFeedbackTest).",

        "subtask_status_wire_values" to
            "SubtaskProgressReport.SubtaskStatus.ALL_WIRE_VALUES exposes all stable subtask " +
            "status wire values. Any tooling consuming Android→V2 signals can validate " +
            "subtask_status fields against this set. fromValue() provides safe parsing. " +
            "Both sets are stable across minor Android updates.",

        "payload_key_constants_on_reconciliation_signal" to
            "ReconciliationSignal.KEY_CHECKPOINT_ID, KEY_SUBTASK_INDEX, KEY_SUBTASK_STATUS, " +
            "KEY_SUBTASK_LABEL, KEY_SUBTASK_TOTAL, KEY_STEP_INDEX, KEY_TOTAL_STEPS constants " +
            "make the structured progress payload keys publicly named and testable. Any tooling " +
            "that reads ReconciliationSignal.payload can use these constants rather than " +
            "hard-coded strings.",

        "toPayloadMap_canonical_wire_output" to
            "ParticipantProgressCheckpoint.toPayloadMap() and SubtaskProgressReport.toPayloadMap() " +
            "produce canonical, immutable maps with all keys under the KEY_* constants. " +
            "The maps are suitable for direct assertion in unit tests and for direct merge " +
            "into ReconciliationSignal payloads. Reviewers can inspect toPayloadMap() outputs " +
            "to verify the complete wire format without reading serialization logic."
    )

    /**
     * **AC-4: Whether the added feedback remains bounded under participant-local authority.**
     *
     * Each entry declares one Android-owned authority and one V2-only authority, making the
     * boundary explicit and testable.
     */
    val AUTHORITY_BOUNDARY_DECLARATIONS: Map<String, String> = mapOf(

        "android_owns_checkpoint_identity" to
            "Android exclusively decides which execution stages are checkpointable. " +
            "ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS is the versioned Android-side " +
            "vocabulary. V2 may observe and display checkpoint events but must not infer " +
            "orchestration decisions from checkpoint identity (e.g. must not retry a task " +
            "because it is at CHECKPOINT_REPLANNING — that remains V2's failure-detection logic).",

        "android_owns_subtask_labeling" to
            "Android defines what a subtask is (a plan step produced by the local AI planner), " +
            "how many subtasks a plan has, and how to label each step. " +
            "V2 receives subtask reports as participant-local truth and may use them for display " +
            "and monitoring only. V2 must not attempt to coordinate Android's plan steps or " +
            "inject external subtask boundaries.",

        "android_owns_progress_signal_ordering" to
            "Android controls when and how many TASK_STATUS_UPDATE signals it emits. " +
            "V2 must tolerate zero, one, or many TASK_STATUS_UPDATE signals between TASK_ACCEPTED " +
            "and any terminal signal. V2 must not require a specific number or sequence of " +
            "progress checkpoints as a precondition for terminal-signal acceptance.",

        "v2_owns_task_lifecycle_outcomes" to
            "Despite the richer progress surface, V2 retains exclusive authority over task " +
            "lifecycle outcomes. V2 decides whether a failed subtask (SubtaskStatus.FAILED) " +
            "triggers task-level retry, fallback, or failure. V2 decides whether a CHECKPOINT_REPLANNING " +
            "signal warrants intervention. Android's TASK_FAILED terminal signal remains the " +
            "authoritative end of the task — intermediate progress is advisory.",

        "v2_owns_dispatch_and_rebalance" to
            "Richer progress feedback does not give Android dispatch authority. " +
            "Android does not decide whether to accept additional tasks based on subtask progress. " +
            "Android does not initiate formation rebalance based on its internal plan execution state. " +
            "All dispatch, rebalance, and cross-participant coordination remains with V2."
    )

    // ── Wire-key constants (progress payload additions to ReconciliationSignal) ──

    /**
     * Payload key for the structured checkpoint identifier ([ParticipantProgressCheckpoint.checkpointId]).
     * Present in [ReconciliationSignal.payload] when a checkpoint is carried by the signal.
     */
    const val KEY_CHECKPOINT_ID = ParticipantProgressCheckpoint.KEY_CHECKPOINT_ID

    /**
     * Payload key for the checkpoint step index ([ParticipantProgressCheckpoint.stepIndex]).
     * Present in [ReconciliationSignal.payload] when a checkpoint is carried by the signal.
     */
    const val KEY_STEP_INDEX = ParticipantProgressCheckpoint.KEY_STEP_INDEX

    /**
     * Payload key for the total steps hint ([ParticipantProgressCheckpoint.totalSteps]).
     * Present in [ReconciliationSignal.payload] when non-null in the checkpoint.
     */
    const val KEY_TOTAL_STEPS = ParticipantProgressCheckpoint.KEY_TOTAL_STEPS

    /**
     * Payload key for the subtask index ([SubtaskProgressReport.subtaskIndex]).
     * Present in [ReconciliationSignal.payload] when a subtask report is carried by the signal.
     */
    const val KEY_SUBTASK_INDEX = SubtaskProgressReport.KEY_SUBTASK_INDEX

    /**
     * Payload key for the subtask total ([SubtaskProgressReport.subtaskTotal]).
     * Present in [ReconciliationSignal.payload] when non-null in the subtask report.
     */
    const val KEY_SUBTASK_TOTAL = SubtaskProgressReport.KEY_SUBTASK_TOTAL

    /**
     * Payload key for the subtask status wire value ([SubtaskProgressReport.SubtaskStatus.wireValue]).
     * Present in [ReconciliationSignal.payload] when a subtask report is carried by the signal.
     */
    const val KEY_SUBTASK_STATUS = SubtaskProgressReport.KEY_SUBTASK_STATUS

    /**
     * Payload key for the subtask label ([SubtaskProgressReport.subtaskLabel]).
     * Present in [ReconciliationSignal.payload] when a subtask report is carried by the signal.
     */
    const val KEY_SUBTASK_LABEL = SubtaskProgressReport.KEY_SUBTASK_LABEL

    // ── Count invariants ──────────────────────────────────────────────────────

    /**
     * Expected number of entries in [PROGRESS_FEEDBACK_SURFACES].
     * Checked by the test suite to detect accidental omissions.
     */
    const val PROGRESS_FEEDBACK_SURFACES_COUNT: Int = 5

    /**
     * Expected number of entries in [OUTBOUND_EXECUTION_MODELING].
     */
    const val OUTBOUND_EXECUTION_MODELING_COUNT: Int = 5

    /**
     * Expected number of entries in [OBSERVABILITY_SURFACES].
     */
    const val OBSERVABILITY_SURFACES_COUNT: Int = 4

    /**
     * Expected number of entries in [AUTHORITY_BOUNDARY_DECLARATIONS].
     */
    const val AUTHORITY_BOUNDARY_DECLARATIONS_COUNT: Int = 5

    /**
     * Expected number of well-known [ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS].
     */
    const val KNOWN_CHECKPOINT_COUNT: Int = 8

    /**
     * PR number that introduced this progress feedback surface.
     */
    const val INTRODUCED_PR: Int = 63
}
