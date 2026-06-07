package com.ufo.galaxy.runtime

/**
 * PR-04 — Android collaboration participation lifecycle state.
 *
 * Models the lifecycle of a [com.ufo.galaxy.agent.LocalCollaborationAgent] execution
 * cycle from assignment through completion or failure.  This enum makes the collaboration
 * participation lifecycle explicit and machine-observable, filling the gap between
 * [LocalCollaborationAgent] having an assigned subtask and V2 knowing whether Android is
 * idle, actively executing, or has finished.
 *
 * ## Motivation
 *
 * [LocalCollaborationAgent.handleParallelSubtask] is a synchronous function that returns
 * a result — it does not emit lifecycle events in its current form.  V2 receives only the
 * final result ([GoalResultPayload]) but has no structured signal for:
 *  - When Android received and acknowledged the subtask assignment.
 *  - When Android began executing (pipeline invoked).
 *  - Whether Android completed, failed, or had the assignment cancelled.
 *
 * [CollaborationLifecycleState] provides the canonical vocabulary for these states.
 * It is included in [DeviceStateSnapshotPayload] so V2 can observe the current collaboration
 * state of the Android participant at any snapshot point.
 *
 * ## State definitions
 *
 * | [CollaborationLifecycleState] | Wire value           | Description |
 * |-------------------------------|----------------------|-------------|
 * | [IDLE]                        | `idle`               | No active collaboration; awaiting assignment |
 * | [SUBTASK_ASSIGNED]            | `subtask_assigned`   | Subtask received; execution pipeline not yet started |
 * | [EXECUTING]                   | `executing`          | Subtask execution pipeline is running |
 * | [COMPLETED]                   | `completed`          | Subtask completed successfully |
 * | [FAILED]                      | `failed`             | Subtask execution failed |
 * | [CANCELLED]                   | `cancelled`          | Subtask was cancelled before or during execution |
 *
 * ## Transition rules
 *
 * Normal path:
 * ```
 * IDLE → SUBTASK_ASSIGNED → EXECUTING → COMPLETED → IDLE
 * ```
 *
 * Error paths:
 * ```
 * EXECUTING → FAILED → IDLE
 * EXECUTING → CANCELLED → IDLE
 * SUBTASK_ASSIGNED → CANCELLED → IDLE
 * ```
 *
 * @property wireValue Stable lowercase wire value.
 * @property description Human-readable description.
 */
enum class CollaborationLifecycleState(
    val wireValue: String,
    val description: String
) {

    /**
     * No active collaboration; the device is not currently assigned a subtask.
     * This is the initial state and the state after a terminal result is emitted.
     */
    IDLE(
        wireValue = "idle",
        description = "No active collaboration; awaiting subtask assignment"
    ),

    /**
     * A subtask has been received and acknowledged.  The execution pipeline has not yet
     * been invoked (for example, the pipeline is queued or awaiting a resource lock).
     */
    SUBTASK_ASSIGNED(
        wireValue = "subtask_assigned",
        description = "Subtask received; awaiting execution pipeline start"
    ),

    /**
     * The execution pipeline is actively running for the assigned subtask.
     * [LocalCollaborationAgent.handleParallelSubtask] is in progress.
     */
    EXECUTING(
        wireValue = "executing",
        description = "Subtask execution pipeline is actively running"
    ),

    /**
     * The subtask execution pipeline completed successfully and a result was emitted.
     * The device will return to [IDLE] after the result has been sent to V2.
     */
    COMPLETED(
        wireValue = "completed",
        description = "Subtask execution completed successfully"
    ),

    /**
     * The subtask execution pipeline encountered an error or failure condition.
     * A failure result was emitted.  The device will return to [IDLE] after the
     * failure result has been sent to V2.
     */
    FAILED(
        wireValue = "failed",
        description = "Subtask execution failed; failure result emitted"
    ),

    /**
     * The subtask was cancelled before or during execution.  No result will be emitted.
     * The device will return to [IDLE] after reporting cancellation.
     */
    CANCELLED(
        wireValue = "cancelled",
        description = "Subtask cancelled before or during execution"
    );

    companion object {

        /**
         * Returns the [CollaborationLifecycleState] with the given [wireValue], or `null`
         * if no match is found.
         */
        fun fromWireValue(value: String?): CollaborationLifecycleState? =
            entries.firstOrNull { it.wireValue == value }

        /**
         * Returns `true` when [state] indicates Android is actively processing a
         * collaboration assignment (not idle and not in a terminal state).
         */
        fun isActivelyProcessing(state: CollaborationLifecycleState): Boolean =
            state == SUBTASK_ASSIGNED || state == EXECUTING

        /**
         * Returns `true` when [state] is a terminal state (after which the device
         * returns to [IDLE]).
         */
        fun isTerminal(state: CollaborationLifecycleState): Boolean =
            state == COMPLETED || state == FAILED || state == CANCELLED

        /**
         * Maps a [StagedMeshParticipationResult.ExecutionStatus] to the corresponding
         * terminal [CollaborationLifecycleState].
         *
         * This ensures consistent reporting between the staged-mesh execution path
         * and the collaboration lifecycle state surface.
         */
        fun fromExecutionStatus(
            status: StagedMeshParticipationResult.ExecutionStatus
        ): CollaborationLifecycleState = when (status) {
            StagedMeshParticipationResult.ExecutionStatus.SUCCESS -> COMPLETED
            StagedMeshParticipationResult.ExecutionStatus.FAILURE -> FAILED
            StagedMeshParticipationResult.ExecutionStatus.CANCELLED -> CANCELLED
            StagedMeshParticipationResult.ExecutionStatus.BLOCKED -> CANCELLED
        }

        /** All stable wire values for schema validation. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}
