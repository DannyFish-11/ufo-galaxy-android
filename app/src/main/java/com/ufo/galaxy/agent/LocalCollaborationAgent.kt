package com.ufo.galaxy.agent

import android.util.Log
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload

/**
 * Handles incoming [com.ufo.galaxy.protocol.MsgType.PARALLEL_SUBTASK] messages by
 * delegating to [LocalGoalExecutor] and ensuring the [GoalResultPayload.group_id]
 * and [GoalResultPayload.subtask_index] are echoed back so the server can converge
 * parallel-group results.
 *
 * This is a thin coordinator: all heavy execution logic lives in [LocalGoalExecutor].
 * Having a dedicated class lets tests verify subtask-specific behaviour (group_id /
 * subtask_index echoing) independently from single-goal tests.
 *
 * @param goalExecutor Underlying goal executor responsible for local pipeline execution.
 */
class LocalCollaborationAgent(private val goalExecutor: LocalGoalExecutor) {

    companion object {
        private const val TAG = "LocalCollaborationAgent"
    }

    /**
     * Handles a parallel subtask by executing it locally and returning the structured
     * result with [GoalResultPayload.group_id] and [GoalResultPayload.subtask_index]
     * set so the server can converge the parallel group.
     *
     * @param payload Incoming parallel_subtask payload. [payload.group_id] and
     *                [payload.subtask_index] should be non-null; if they are null
     *                the result will carry null values and the gateway must handle them.
     * @return [GoalResultPayload] with group_id/subtask_index echoed.
     */
    fun handleParallelSubtask(payload: GoalExecutionPayload): GoalResultPayload {
        Log.i(
            TAG,
            "handleParallelSubtask task_id=${payload.task_id} " +
                "group_id=${payload.group_id} subtask_index=${payload.subtask_index} " +
                "goal='${payload.goal.take(80)}'"
        )
        val result = goalExecutor.executeGoal(payload)
        Log.i(
            TAG,
            "handleParallelSubtask done task_id=${payload.task_id} status=${result.status} " +
                "group_id=${result.group_id} subtask_index=${result.subtask_index}"
        )
        return result
    }
}
