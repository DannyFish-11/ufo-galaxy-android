package com.ufo.galaxy.runtime

import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload

/**
 * PR-32 — Target-side execution compatibility shim for staged-mesh coordination.
 *
 * Accepts inbound staged-mesh subtask assignments from the V2 coordinator and routes
 * them through the existing goal-execution pipeline, producing a
 * [StagedMeshParticipationResult] compatible with the staged mesh result semantics.
 *
 * ## Design constraints
 *
 *  - Reuses the existing execution pipeline ([SubtaskExecutor]) without introducing a
 *    new runtime authority or alternate execution path.
 *  - Gated by [RolloutControlSnapshot.crossDeviceAllowed]; when the gate is closed,
 *    [acceptSubtask] returns [StagedMeshParticipationResult.ExecutionStatus.BLOCKED]
 *    immediately without touching the pipeline.
 *  - Does **not** manage mesh session lifecycle (join / leave) — that responsibility
 *    belongs to the V2 coordinator.  Android only executes what is assigned.
 *  - Emits structured [GalaxyLogger.TAG_STAGED_MESH] log entries for accept, block,
 *    and result events so operators can trace the staged-mesh execution path.
 *
 * ## Usage
 *
 * ```kotlin
 * val target = StagedMeshExecutionTarget(
 *     executor = StagedMeshExecutionTarget.SubtaskExecutor { payload ->
 *         autonomousExecutionPipeline.handleGoalExecution(payload)
 *     },
 *     deviceId = localDeviceId
 * )
 * val result = target.acceptSubtask(
 *     meshId    = "staged-mesh-abc",
 *     subtaskId = "subtask-1",
 *     payload   = goalPayload,
 *     rolloutSnapshot = rolloutControlSnapshot
 * )
 * ```
 *
 * @param executor  The [SubtaskExecutor] that runs the subtask.  In production this
 *                  delegates to [com.ufo.galaxy.agent.AutonomousExecutionPipeline].
 *                  In tests a fake can be supplied.
 * @param deviceId  Stable device identifier included in every [StagedMeshParticipationResult].
 */
class StagedMeshExecutionTarget(
    private val executor: SubtaskExecutor,
    private val deviceId: String
) {

    /**
     * Functional interface for the goal-execution pipeline dependency.
     *
     * Implementations MUST be non-throwing suspend functions; any execution error
     * should be captured and returned as a [GoalResultPayload] with status `"error"`.
     */
    fun interface SubtaskExecutor {
        /** Executes [payload] and returns the [GoalResultPayload] outcome. */
        suspend fun executeSubtask(payload: GoalExecutionPayload): GoalResultPayload
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Accepts and executes a staged-mesh subtask assignment.
     *
     * **Rollout gate** — when [RolloutControlSnapshot.crossDeviceAllowed] is `false`,
     * returns a [StagedMeshParticipationResult] with
     * [StagedMeshParticipationResult.ExecutionStatus.BLOCKED] immediately, without
     * invoking the pipeline.  This mirrors the hard constraint enforced by
     * [MultiDeviceCoordinator] for the parallel multi-device dispatch path.
     *
     * **Execution** — delegates to [executor], then wraps the outcome with the
     * staged-mesh identity fields ([meshId], [subtaskId]).  The [deviceId] is injected
     * into the result when the pipeline returns an empty device_id, so the result is
     * always traceable back to this device.
     *
     * @param meshId          Staged-mesh session identifier assigned by the V2 coordinator.
     * @param subtaskId       Unique subtask identifier for this assignment.
     * @param payload         [GoalExecutionPayload] carrying the goal and execution parameters.
     * @param rolloutSnapshot Current [RolloutControlSnapshot]; used to gate participation.
     * @return [StagedMeshParticipationResult] carrying the execution outcome and full identity.
     */
    suspend fun acceptSubtask(
        meshId: String,
        subtaskId: String,
        payload: GoalExecutionPayload,
        rolloutSnapshot: RolloutControlSnapshot
    ): StagedMeshParticipationResult {

        // ── Rollout gate ──────────────────────────────────────────────────────
        if (!rolloutSnapshot.crossDeviceAllowed) {
            GalaxyLogger.log(
                GalaxyLogger.TAG_STAGED_MESH,
                mapOf(
                    "event"      to "staged_mesh_blocked",
                    "mesh_id"    to meshId,
                    "subtask_id" to subtaskId,
                    "task_id"    to payload.task_id,
                    "reason"     to "cross_device_disabled"
                )
            )
            return StagedMeshParticipationResult(
                meshId          = meshId,
                subtaskId       = subtaskId,
                taskId          = payload.task_id,
                deviceId        = deviceId,
                executionStatus = StagedMeshParticipationResult.ExecutionStatus.BLOCKED,
                error           = "staged_mesh_participation_blocked: cross_device_disabled"
            )
        }

        // ── Execute ───────────────────────────────────────────────────────────
        GalaxyLogger.log(
            GalaxyLogger.TAG_STAGED_MESH,
            mapOf(
                "event"      to "staged_mesh_accept",
                "mesh_id"    to meshId,
                "subtask_id" to subtaskId,
                "task_id"    to payload.task_id
            )
        )

        val goalResult = executor.executeSubtask(payload)

        // Inject deviceId when the pipeline returned an empty device_id so that
        // StagedMeshParticipationResult.deviceId is always non-blank.
        val enrichedResult = if (goalResult.device_id.isBlank()) {
            goalResult.copy(device_id = deviceId)
        } else {
            goalResult
        }

        val result = StagedMeshParticipationResult.fromGoalResult(
            meshId    = meshId,
            subtaskId = subtaskId,
            result    = enrichedResult
        )

        // ── Result log ────────────────────────────────────────────────────────
        GalaxyLogger.log(
            GalaxyLogger.TAG_STAGED_MESH,
            mapOf(
                "event"      to "staged_mesh_result",
                "mesh_id"    to meshId,
                "subtask_id" to subtaskId,
                "task_id"    to payload.task_id,
                "status"     to result.executionStatus.wireValue,
                "step_count" to result.stepCount,
                "latency_ms" to result.latencyMs
            )
        )

        return result
    }
}
