package com.ufo.galaxy.runtime

/**
 * PR-63 — Structured Android participant subtask progress report.
 *
 * A typed, structured progress report for a single **subtask** within a larger delegated
 * task execution.  Subtasks are ordered, named units of work that form a plan produced by
 * Android's local AI planning loop.
 *
 * ## Motivation
 *
 * [DelegatedExecutionTracker] tracks cumulative step count for the overall task but does
 * not model individual named subtasks within that task.  When Android runs a multi-step
 * plan, V2 receives only the step-count integer — it cannot distinguish step 1 ("tap the
 * menu button") from step 4 ("type search query") or determine whether the current step
 * is the first of three sub-goals or the seventh of seven.
 *
 * [SubtaskProgressReport] fills this gap for the outbound signal path.  It carries:
 *  - A [subtaskIndex] and optional [subtaskTotal] for ordered progress tracking.
 *  - A [subtaskLabel] — a brief human-readable description of what Android is doing.
 *  - A [subtaskStatus] enum discriminating execution progress states.
 *  - The owning [taskId] and [participantId] required for V2 routing.
 *
 * ## Responsibility boundary
 *
 * [SubtaskProgressReport] is **Android-local execution truth**.  Android decides what a
 * subtask is and emits reports at its own discretion.  V2 receives the report as a
 * structured payload within [ReconciliationSignal.Kind.TASK_STATUS_UPDATE] and may use it
 * for monitoring, display, and execution observability.  V2 **must not** make orchestration
 * or dispatch decisions based on subtask-level state — V2's authority is over the overall
 * task lifecycle, not Android's internal execution breakdown.
 *
 * ## Wire format
 *
 * Use [toPayloadMap] to produce the canonical payload map for merging into a
 * [ReconciliationSignal.Kind.TASK_STATUS_UPDATE] payload:
 *
 * ```kotlin
 * val report = SubtaskProgressReport(
 *     taskId = "task-abc",
 *     participantId = "device-1:host-1",
 *     subtaskIndex = 2,
 *     subtaskTotal = 5,
 *     subtaskLabel = "Tap settings icon",
 *     subtaskStatus = SubtaskProgressReport.SubtaskStatus.EXECUTING
 * )
 * val signal = ReconciliationSignal.taskStatusUpdate(
 *     participantId = report.participantId,
 *     taskId = report.taskId,
 *     progressDetail = report.subtaskLabel,
 *     subtaskPayload = report.toPayloadMap()
 * )
 * ```
 *
 * @property taskId         Owning task identifier for V2 routing.
 * @property participantId  Participant that emitted this report.
 * @property subtaskIndex   Zero-based index of the current subtask within the task.
 * @property subtaskTotal   Optional total number of subtasks; null when the plan is not yet finalized.
 * @property subtaskLabel   Brief human-readable description of what this subtask does.
 * @property subtaskStatus  Current execution status of this subtask.
 * @property emittedAtMs    Epoch-ms when this report was created; defaults to current time.
 */
data class SubtaskProgressReport(
    val taskId: String,
    val participantId: String,
    val subtaskIndex: Int,
    val subtaskTotal: Int? = null,
    val subtaskLabel: String,
    val subtaskStatus: SubtaskStatus,
    val emittedAtMs: Long = System.currentTimeMillis()
) {

    /**
     * Execution status of a single subtask within a delegated task.
     *
     * @property wireValue Stable lowercase string used in wire-format payloads.
     */
    enum class SubtaskStatus(val wireValue: String) {

        /**
         * The subtask is queued and waiting for the preceding subtask to complete.
         * Android has not yet started executing this subtask.
         */
        PENDING("pending"),

        /**
         * Android is actively executing this subtask (grounding, action dispatch, etc.).
         */
        EXECUTING("executing"),

        /**
         * The subtask completed successfully.  Android will proceed to the next subtask
         * or finalize the task if this was the last one.
         */
        COMPLETE("complete"),

        /**
         * The subtask failed.  Android will attempt re-planning or emit [ReconciliationSignal.Kind.TASK_FAILED].
         */
        FAILED("failed"),

        /**
         * The subtask was skipped (e.g. the plan was revised and this subtask became obsolete).
         */
        SKIPPED("skipped");

        companion object {
            /**
             * Parses [value] to a [SubtaskStatus], returning null for unknown values.
             */
            fun fromValue(value: String?): SubtaskStatus? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    /**
     * Produces the canonical payload map for this subtask progress report, suitable for
     * merging into a [ReconciliationSignal.Kind.TASK_STATUS_UPDATE] payload.
     *
     * Keys always present:
     *  - [KEY_TASK_ID]          — owning task identifier.
     *  - [KEY_PARTICIPANT_ID]   — participant identifier.
     *  - [KEY_SUBTASK_INDEX]    — zero-based subtask index.
     *  - [KEY_SUBTASK_LABEL]    — human-readable subtask description.
     *  - [KEY_SUBTASK_STATUS]   — current subtask wire-value status.
     *  - [KEY_EMITTED_AT_MS]    — epoch-ms emission timestamp.
     *
     * Keys present when non-null:
     *  - [KEY_SUBTASK_TOTAL]    — total subtask count.
     *
     * @return An immutable map ready for merging into a reconciliation signal payload.
     */
    fun toPayloadMap(): Map<String, Any> = buildMap {
        put(KEY_TASK_ID, taskId)
        put(KEY_PARTICIPANT_ID, participantId)
        put(KEY_SUBTASK_INDEX, subtaskIndex)
        put(KEY_SUBTASK_LABEL, subtaskLabel)
        put(KEY_SUBTASK_STATUS, subtaskStatus.wireValue)
        put(KEY_EMITTED_AT_MS, emittedAtMs)
        subtaskTotal?.let { put(KEY_SUBTASK_TOTAL, it) }
    }

    companion object {

        // ── Wire key constants ────────────────────────────────────────────────

        /** Wire key for [taskId] within a reconciliation signal payload. */
        const val KEY_TASK_ID = "subtask_task_id"

        /** Wire key for [participantId] within a reconciliation signal payload. */
        const val KEY_PARTICIPANT_ID = "subtask_participant_id"

        /** Wire key for [subtaskIndex] within a reconciliation signal payload. */
        const val KEY_SUBTASK_INDEX = "subtask_index"

        /** Wire key for [subtaskTotal] within a reconciliation signal payload; absent when null. */
        const val KEY_SUBTASK_TOTAL = "subtask_total"

        /** Wire key for [subtaskLabel] within a reconciliation signal payload. */
        const val KEY_SUBTASK_LABEL = "subtask_label"

        /** Wire key for [subtaskStatus] ([SubtaskStatus.wireValue]) within a reconciliation signal payload. */
        const val KEY_SUBTASK_STATUS = "subtask_status"

        /** Wire key for [emittedAtMs] within a reconciliation signal payload. */
        const val KEY_EMITTED_AT_MS = "subtask_emitted_at_ms"
    }
}
