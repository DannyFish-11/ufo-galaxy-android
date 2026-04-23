package com.ufo.galaxy.runtime

/**
 * PR-63 — Structured Android participant execution checkpoint.
 *
 * A named, structured progress marker emitted by the Android local execution pipeline
 * to represent a meaningful stage boundary within a running task.  Checkpoints give V2
 * a richer, structured view of Android's execution progress beyond the coarser
 * accept / status-update / result / failure terminal signals.
 *
 * ## Motivation
 *
 * Before PR-63 the only intermediate progress surface between task acceptance and terminal
 * outcome was [ReconciliationSignal.Kind.TASK_STATUS_UPDATE] with an optional free-form
 * [progressDetail] string.  This was sufficient for simple single-step tasks but left V2
 * without structured insight when Android's local AI execution pipeline crossed meaningful
 * stage boundaries:
 *
 *  - V2 could not distinguish "planning complete" from "grounding started" or "step N of M".
 *  - No structured step count or total-step hint was available in the signal payload.
 *  - Checkpoint identity was opaque; V2 had to parse unstructured strings to understand progress.
 *
 * [ParticipantProgressCheckpoint] fills this gap.  It carries:
 *  - A stable [checkpointId] identifying the execution stage ([CHECKPOINT_*] constants).
 *  - The [stepIndex] and optional [totalSteps] for ordered-progress tracking.
 *  - An optional [detail] for human-readable supplementary information.
 *  - The [taskId] and [participantId] required for V2 routing.
 *
 * ## Responsibility boundary
 *
 * [ParticipantProgressCheckpoint] is **Android-local execution truth**.  Android decides
 * which stages are checkpointable and emits a checkpoint when a stage boundary is crossed.
 * V2 receives the checkpoint payload via [ReconciliationSignal.Kind.TASK_STATUS_UPDATE]
 * with the checkpoint encoded in the signal payload under [KEY_CHECKPOINT_ID] and related
 * keys.  V2 may use checkpoint signals for progress display and monitoring but **must not**
 * make dispatch or orchestration decisions based on checkpoint identity — that authority
 * remains with V2's own orchestration logic.
 *
 * ## Wire format
 *
 * Use [toPayloadMap] to produce the canonical payload map for merging into a
 * [ReconciliationSignal.Kind.TASK_STATUS_UPDATE] payload:
 *
 * ```kotlin
 * val checkpoint = ParticipantProgressCheckpoint(
 *     checkpointId = ParticipantProgressCheckpoint.CHECKPOINT_PLANNING_COMPLETE,
 *     taskId = "task-abc",
 *     participantId = "device-1:host-1",
 *     stepIndex = 0,
 *     totalSteps = 5
 * )
 * val signal = ReconciliationSignal.taskStatusUpdate(
 *     participantId = checkpoint.participantId,
 *     taskId = checkpoint.taskId,
 *     progressDetail = checkpoint.checkpointId,
 *     checkpointPayload = checkpoint.toPayloadMap()
 * )
 * ```
 *
 * @property checkpointId   Stable stage identifier ([CHECKPOINT_*] constants).
 * @property taskId         Task this checkpoint belongs to.
 * @property participantId  Participant that emitted this checkpoint.
 * @property stepIndex      Zero-based index of the current step within the task execution.
 * @property totalSteps     Optional total number of steps expected; null when unknown.
 * @property detail         Optional human-readable supplementary description.
 * @property emittedAtMs    Epoch-ms when this checkpoint was created; defaults to current time.
 */
data class ParticipantProgressCheckpoint(
    val checkpointId: String,
    val taskId: String,
    val participantId: String,
    val stepIndex: Int,
    val totalSteps: Int? = null,
    val detail: String? = null,
    val emittedAtMs: Long = System.currentTimeMillis()
) {

    /**
     * Produces the canonical payload map for this checkpoint, suitable for merging into a
     * [ReconciliationSignal.Kind.TASK_STATUS_UPDATE] payload via [ReconciliationSignal.payload].
     *
     * Keys always present:
     *  - [KEY_CHECKPOINT_ID]   — stable stage identifier.
     *  - [KEY_TASK_ID]         — task identifier.
     *  - [KEY_PARTICIPANT_ID]  — participant identifier.
     *  - [KEY_STEP_INDEX]      — zero-based step index.
     *  - [KEY_EMITTED_AT_MS]   — epoch-ms emission timestamp.
     *
     * Keys present when non-null:
     *  - [KEY_TOTAL_STEPS]     — total step count hint.
     *  - [KEY_DETAIL]          — supplementary description.
     *
     * @return An immutable map ready for merging into a reconciliation signal payload.
     */
    fun toPayloadMap(): Map<String, Any> = buildMap {
        put(KEY_CHECKPOINT_ID, checkpointId)
        put(KEY_TASK_ID, taskId)
        put(KEY_PARTICIPANT_ID, participantId)
        put(KEY_STEP_INDEX, stepIndex)
        put(KEY_EMITTED_AT_MS, emittedAtMs)
        totalSteps?.let { put(KEY_TOTAL_STEPS, it) }
        detail?.let { put(KEY_DETAIL, it) }
    }

    companion object {

        // ── Wire key constants ────────────────────────────────────────────────

        /** Wire key for [checkpointId] within a reconciliation signal payload. */
        const val KEY_CHECKPOINT_ID = "checkpoint_id"

        /** Wire key for [taskId] within a reconciliation signal payload. */
        const val KEY_TASK_ID = "checkpoint_task_id"

        /** Wire key for [participantId] within a reconciliation signal payload. */
        const val KEY_PARTICIPANT_ID = "checkpoint_participant_id"

        /** Wire key for [stepIndex] within a reconciliation signal payload. */
        const val KEY_STEP_INDEX = "checkpoint_step_index"

        /** Wire key for [totalSteps] within a reconciliation signal payload; absent when null. */
        const val KEY_TOTAL_STEPS = "checkpoint_total_steps"

        /** Wire key for [detail] within a reconciliation signal payload; absent when null. */
        const val KEY_DETAIL = "checkpoint_detail"

        /** Wire key for [emittedAtMs] within a reconciliation signal payload. */
        const val KEY_EMITTED_AT_MS = "checkpoint_emitted_at_ms"

        // ── Canonical checkpoint stage identifiers ────────────────────────────

        /**
         * Android has accepted the delegated task and the local AI planning phase has started.
         * This is the first checkpoint — emitted immediately after task acceptance.
         */
        const val CHECKPOINT_PLANNING_STARTED = "planning_started"

        /**
         * The local planning phase has completed and a plan is available.
         * The execution pipeline will now begin step-level grounding and action.
         */
        const val CHECKPOINT_PLANNING_COMPLETE = "planning_complete"

        /**
         * Android has started the grounding phase for the current execution step.
         * Grounding maps a plan step to UI coordinates before action dispatch.
         */
        const val CHECKPOINT_GROUNDING_STARTED = "grounding_started"

        /**
         * Grounding for the current step has completed successfully.
         * The execution pipeline will now dispatch the grounded action.
         */
        const val CHECKPOINT_GROUNDING_COMPLETE = "grounding_complete"

        /**
         * Android is executing step N of the plan (see [KEY_STEP_INDEX] and [KEY_TOTAL_STEPS]).
         * This checkpoint is emitted once per plan step when execution of that step begins.
         */
        const val CHECKPOINT_STEP_EXECUTING = "step_executing"

        /**
         * Step N has completed execution (see [KEY_STEP_INDEX]).
         * The pipeline will either proceed to the next step or terminate if this was the last.
         */
        const val CHECKPOINT_STEP_COMPLETE = "step_complete"

        /**
         * Android has determined that local re-planning is required (e.g. after a failed step).
         * Execution will pause while a revised plan is produced.
         */
        const val CHECKPOINT_REPLANNING = "replanning"

        /**
         * All steps have been executed and Android is assembling the final result.
         * The task will transition to a terminal state ([ReconciliationSignal.Kind.TASK_RESULT])
         * immediately after this checkpoint.
         */
        const val CHECKPOINT_FINALIZING = "finalizing"

        /** Sorted set of all well-known [CHECKPOINT_*] identifiers. */
        val KNOWN_CHECKPOINT_IDS: Set<String> = setOf(
            CHECKPOINT_PLANNING_STARTED,
            CHECKPOINT_PLANNING_COMPLETE,
            CHECKPOINT_GROUNDING_STARTED,
            CHECKPOINT_GROUNDING_COMPLETE,
            CHECKPOINT_STEP_EXECUTING,
            CHECKPOINT_STEP_COMPLETE,
            CHECKPOINT_REPLANNING,
            CHECKPOINT_FINALIZING
        )
    }
}
