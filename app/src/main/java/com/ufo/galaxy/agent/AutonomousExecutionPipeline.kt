package com.ufo.galaxy.agent

import android.util.Log
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload

/**
 * Autonomous execution pipeline that gates [goal_execution] and [parallel_subtask]
 * handling behind the canonical cross-device runtime and per-feature flags.
 *
 * Both [handleGoalExecution] and [handleParallelSubtask] are **subordinate to the
 * canonical runtime pipeline**: they require [AppSettings.crossDeviceEnabled] to be
 * `true` before any per-feature check is evaluated.  These message types are
 * exclusively delivered by the Gateway over the cross-device WebSocket channel, so
 * they must not execute independently of that runtime.  When the cross-device runtime
 * is not active both handlers return [STATUS_DISABLED] immediately, ensuring the
 * gateway can still perform correct aggregation without crashing.
 *
 * Feature-level gates are evaluated after the runtime gate:
 * - [handleGoalExecution]   additionally requires [AppSettings.goalExecutionEnabled].
 * - [handleParallelSubtask] additionally requires [AppSettings.parallelExecutionEnabled].
 *
 * When any gate is not satisfied the pipeline immediately returns a structured
 * [STATUS_DISABLED] result with the original [task_id], [group_id], and
 * [subtask_index] echoed so the gateway can still perform correct aggregation
 * without crashing.
 *
 * This class is pure Kotlin (no Android framework dependencies) so that it can
 * be fully exercised by JVM unit tests without robolectric or instrumentation.
 *
 * @param settings            Source-of-truth for execution-enablement flags.
 * @param goalExecutor        Handles single goal_execution pipelines locally.
 * @param collaborationAgent  Coordinates parallel_subtask execution via [goalExecutor].
 * @param deviceId            Stable device identifier included in every result payload.
 * @param deviceRole          Logical device role (e.g., "phone", "tablet") from settings.
 */
class AutonomousExecutionPipeline(
    private val settings: AppSettings,
    private val goalExecutor: LocalGoalExecutor,
    private val collaborationAgent: LocalCollaborationAgent,
    private val deviceId: String,
    private val deviceRole: String
) {

    companion object {
        private const val TAG = "AutonomousExecPipeline"

        /**
         * Status value returned when the execution pipeline is administratively
         * disabled via [AppSettings]. Distinct from [EdgeExecutor.STATUS_ERROR] so
         * the gateway can differentiate "device refused" from "device tried and failed".
         */
        const val STATUS_DISABLED = "disabled"

        /**
         * Status value returned when a task exceeds its configured timeout budget.
         * Mirrors [EdgeExecutor.STATUS_TIMEOUT] for consistent server-side handling.
         */
        const val STATUS_TIMEOUT = EdgeExecutor.STATUS_TIMEOUT
    }

    /**
     * Handles a [goal_execution] message.
     *
     * Two gates are evaluated in order:
     * 1. **Runtime gate**: returns [STATUS_DISABLED] when [AppSettings.crossDeviceEnabled]
     *    is `false`.  [goal_execution] is a cross-device-only message type and must not
     *    execute outside the canonical runtime pipeline.
     * 2. **Feature gate**: returns [STATUS_DISABLED] when [AppSettings.goalExecutionEnabled]
     *    is `false`.
     *
     * When both gates pass, delegates to [LocalGoalExecutor.executeGoal] and enriches
     * the result with [device_role].
     */
    fun handleGoalExecution(payload: GoalExecutionPayload): GoalResultPayload {
        if (!settings.crossDeviceEnabled) {
            Log.i(TAG, "goal_execution blocked: cross-device runtime inactive; task_id=${payload.task_id}")
            return buildDisabledResult(payload, "cross_device_enabled is false")
        }
        if (!settings.goalExecutionEnabled) {
            Log.i(TAG, "goal_execution disabled by settings; task_id=${payload.task_id}")
            return buildDisabledResult(payload, "goal_execution_enabled is false")
        }
        Log.i(TAG, "goal_execution executing locally; task_id=${payload.task_id}")
        return goalExecutor.executeGoal(payload).copy(device_role = deviceRole)
    }

    /**
     * Handles a [parallel_subtask] message.
     *
     * Two gates are evaluated in order:
     * 1. **Runtime gate**: returns [STATUS_DISABLED] when [AppSettings.crossDeviceEnabled]
     *    is `false`.  [parallel_subtask] is a cross-device-only message type and must not
     *    execute outside the canonical runtime pipeline.
     * 2. **Feature gate**: returns [STATUS_DISABLED] when [AppSettings.parallelExecutionEnabled]
     *    is `false`.
     *
     * When both gates pass, delegates to [LocalCollaborationAgent.handleParallelSubtask]
     * and enriches the result with [device_role].
     */
    fun handleParallelSubtask(payload: GoalExecutionPayload): GoalResultPayload {
        if (!settings.crossDeviceEnabled) {
            Log.i(TAG, "parallel_subtask blocked: cross-device runtime inactive; task_id=${payload.task_id}")
            return buildDisabledResult(payload, "cross_device_enabled is false")
        }
        if (!settings.parallelExecutionEnabled) {
            Log.i(TAG, "parallel_subtask disabled by settings; task_id=${payload.task_id}")
            return buildDisabledResult(payload, "parallel_execution_enabled is false")
        }
        Log.i(TAG, "parallel_subtask executing locally; task_id=${payload.task_id}")
        return collaborationAgent.handleParallelSubtask(payload).copy(device_role = deviceRole)
    }

    private fun buildDisabledResult(
        payload: GoalExecutionPayload,
        reason: String
    ) = GoalResultPayload(
        task_id = payload.task_id,
        correlation_id = payload.task_id,
        status = STATUS_DISABLED,
        error = reason,
        group_id = payload.group_id,
        subtask_index = payload.subtask_index,
        latency_ms = 0L,
        device_id = deviceId,
        device_role = deviceRole
    )
}
