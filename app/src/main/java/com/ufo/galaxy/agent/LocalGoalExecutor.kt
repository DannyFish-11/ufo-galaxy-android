package com.ufo.galaxy.agent

import android.util.Log
import com.ufo.galaxy.nlp.GoalNormalizer
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.TaskAssignPayload

/**
 * Executes a high-level goal or parallel subtask locally on the device.
 *
 * Converts a [GoalExecutionPayload] into a [TaskAssignPayload] and delegates to
 * [EdgeExecutor.handleTaskAssign] for the full pipeline
 * (screenshot → MobileVLM → SeeClick → AccessibilityService). The result is wrapped
 * in a [GoalResultPayload] that includes [GoalExecutionPayload.group_id],
 * [GoalExecutionPayload.subtask_index], [latency_ms], and [device_id] so that the
 * server can converge parallel-group results.
 *
 * @param edgeExecutor  Full on-device AIP v3 task execution orchestrator.
 * @param deviceId      Unique device identifier reported back in [GoalResultPayload].
 */
class LocalGoalExecutor(
    private val edgeExecutor: EdgeExecutor,
    private val deviceId: String
) {

    companion object {
        private const val TAG = "LocalGoalExecutor"
        /** Maximum characters of goal text included in log messages to avoid log flooding. */
        const val MAX_GOAL_LOG_LENGTH = 80
        /** Timeout for a single goal execution (30 s). Enforced by EdgeExecutor's max_steps budget. */
        private const val DEFAULT_MAX_STEPS = 10
    }

    /**
     * Executes [payload] locally and returns a structured [GoalResultPayload].
     *
     * Errors in [EdgeExecutor] are already mapped to ERROR status internally; this
     * method additionally catches any unexpected exception and wraps it as an ERROR.
     *
     * @param payload Incoming goal_execution or parallel_subtask payload.
     * @return [GoalResultPayload] with [group_id]/[subtask_index] echoed from [payload].
     */
    fun executeGoal(payload: GoalExecutionPayload): GoalResultPayload {
        val startMs = System.currentTimeMillis()
        val normalized = GoalNormalizer.normalize(payload.goal)
        Log.i(
            TAG,
            "executeGoal task_id=${payload.task_id} group_id=${payload.group_id} " +
                "subtask_index=${payload.subtask_index} goal='${payload.goal.take(MAX_GOAL_LOG_LENGTH)}'" +
                if (normalized.normalizedText != normalized.originalText)
                    " normalized_goal='${normalized.normalizedText.take(MAX_GOAL_LOG_LENGTH)}'"
                else ""
        )

        val mergedConstraints = payload.constraints + normalized.extractedConstraints

        val taskAssign = TaskAssignPayload(
            task_id = payload.task_id,
            goal = normalized.normalizedText,
            constraints = mergedConstraints,
            max_steps = payload.max_steps.coerceAtLeast(1),
            require_local_agent = true
        )

        val taskResult = try {
            edgeExecutor.handleTaskAssign(taskAssign)
        } catch (e: Exception) {
            Log.e(TAG, "executeGoal unexpected error task_id=${payload.task_id}: ${e.message}", e)
            val latencyMs = System.currentTimeMillis() - startMs
            return GoalResultPayload(
                task_id = payload.task_id,
                correlation_id = payload.task_id,
                status = EdgeExecutor.STATUS_ERROR,
                error = "Unexpected execution error: ${e.message}",
                group_id = payload.group_id,
                subtask_index = payload.subtask_index,
                latency_ms = latencyMs,
                device_id = deviceId,
                source_runtime_posture = payload.source_runtime_posture
            )
        }

        val latencyMs = System.currentTimeMillis() - startMs
        Log.i(
            TAG,
            "executeGoal done task_id=${payload.task_id} status=${taskResult.status} " +
                "steps=${taskResult.steps.size} latency=${latencyMs}ms"
        )

        return GoalResultPayload(
            task_id = taskResult.task_id,
            correlation_id = taskResult.correlation_id ?: payload.task_id,
            status = taskResult.status,
            result = if (taskResult.status == EdgeExecutor.STATUS_SUCCESS) "Goal executed successfully" else null,
            details = taskResult.error,
            group_id = payload.group_id,
            subtask_index = payload.subtask_index,
            latency_ms = latencyMs,
            device_id = deviceId,
            steps = taskResult.steps,
            error = taskResult.error,
            source_runtime_posture = payload.source_runtime_posture
        )
    }
}
