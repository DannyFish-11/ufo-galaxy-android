package com.ufo.galaxy.runtime

/**
 * **Discriminator for the Android-side entry path that triggered a delegated flow** (PR-bridge,
 * post-#533 dual-repo runtime unification master plan — Android-Side Delegated Flow Bridge
 * Model, Android side).
 *
 * Every inbound command from the V2 canonical orchestration network that creates delegated
 * work on Android arrives through one of four entry paths.  [DelegatedFlowEntryKind] names
 * these paths canonically so that [AndroidDelegatedFlowBridge] — and every signal / result /
 * progress output produced during the flow — carries a stable, typed indicator of which V2
 * command type initiated the local execution.
 *
 * ## Entry path summary
 *
 * | Kind                | V2 message type        | Android handler                        |
 * |---------------------|------------------------|----------------------------------------|
 * | [TASK_ASSIGN]       | `task_assign`          | [com.ufo.galaxy.agent.EdgeExecutor]    |
 * | [GOAL_EXECUTION]    | `goal_execution`       | [com.ufo.galaxy.agent.AutonomousExecutionPipeline.handleGoalExecution] |
 * | [PARALLEL_SUBTASK]  | `parallel_subtask`     | [com.ufo.galaxy.agent.AutonomousExecutionPipeline.handleParallelSubtask] |
 * | [TAKEOVER_REQUEST]  | `takeover_request`     | [com.ufo.galaxy.agent.DelegatedTakeoverExecutor.execute] |
 *
 * All four entry paths belong to the **same delegated flow family** when they share the
 * same [AndroidDelegatedFlowBridge.flowLineageId].  This enum allows callers to distinguish
 * which specific path produced the current [AndroidDelegatedFlowBridge] instance without
 * inspecting raw message-type strings.
 *
 * @property wireValue Stable lowercase string used in JSON payloads, metadata maps, and logging.
 *                     Consumers MUST treat unknown values as [UNKNOWN] (forward-compatibility).
 */
enum class DelegatedFlowEntryKind(val wireValue: String) {

    /**
     * The delegated flow was initiated by a `task_assign` downlink command.
     *
     * This is the classic single-device task dispatch path.  Android receives a
     * [com.ufo.galaxy.protocol.TaskAssignPayload] and executes it locally via
     * [com.ufo.galaxy.agent.EdgeExecutor].  The flow is scoped to a single task execution
     * and does not require an [AttachedRuntimeSession].
     */
    TASK_ASSIGN("task_assign"),

    /**
     * The delegated flow was initiated by a `goal_execution` downlink command.
     *
     * Android receives a [com.ufo.galaxy.protocol.GoalExecutionPayload] and executes
     * the goal via [com.ufo.galaxy.agent.AutonomousExecutionPipeline.handleGoalExecution].
     * Requires [com.ufo.galaxy.data.AppSettings.crossDeviceEnabled] and
     * [com.ufo.galaxy.data.AppSettings.goalExecutionEnabled] to be active.
     */
    GOAL_EXECUTION("goal_execution"),

    /**
     * The delegated flow was initiated by a `parallel_subtask` downlink command.
     *
     * Android receives a [com.ufo.galaxy.protocol.GoalExecutionPayload] (with
     * [com.ufo.galaxy.protocol.GoalExecutionPayload.group_id] set) and executes the
     * subtask via [com.ufo.galaxy.agent.AutonomousExecutionPipeline.handleParallelSubtask].
     * The subtask belongs to a parallel execution group coordinated by V2.
     */
    PARALLEL_SUBTASK("parallel_subtask"),

    /**
     * The delegated flow was initiated by a `takeover_request` downlink command.
     *
     * Android receives a [com.ufo.galaxy.agent.TakeoverRequestEnvelope], gates it through
     * [com.ufo.galaxy.agent.DelegatedRuntimeReceiver], and executes the goal via
     * [com.ufo.galaxy.agent.DelegatedTakeoverExecutor].  This path requires an active
     * [AttachedRuntimeSession] in [AttachedRuntimeSession.State.ATTACHED].
     */
    TAKEOVER_REQUEST("takeover_request"),

    /**
     * Sentinel for unknown or future entry kinds not present in this enum.
     *
     * Callers that encounter this value received a wire payload from a V2 version that
     * introduced a new entry kind after this model was compiled.  They MUST NOT treat
     * [UNKNOWN] as an error; execution should proceed with reduced observability.
     */
    UNKNOWN("unknown");

    companion object {

        /**
         * Parses [value] to a [DelegatedFlowEntryKind], returning [UNKNOWN] for unrecognised
         * or null inputs.
         *
         * Consumers MUST use this factory rather than [valueOf] to preserve forward-compatibility
         * with future V2 entry kinds.
         *
         * @param value Wire string from a JSON payload or metadata map; may be null.
         * @return The matching [DelegatedFlowEntryKind], or [UNKNOWN] when unrecognised.
         */
        fun fromValue(value: String?): DelegatedFlowEntryKind =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}
