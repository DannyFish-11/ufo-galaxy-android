package com.ufo.galaxy.runtime

import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.HybridExecutePayload
import com.ufo.galaxy.protocol.HybridResultPayload

/**
 * PR-A1 (Android) — Full hybrid-execute coordinator for Android-side mesh runtime.
 *
 * Accepts inbound `hybrid_execute` commands from the V2 gateway and executes the local
 * portion of a hybrid task through the existing goal-execution pipeline.  This closes
 * the [HybridParticipantCapability.HYBRID_EXECUTE_FULL] gap that previously kept
 * [AndroidMeshParticipationContract.ParticipationReport.fullMeshRuntimeExecutable] always
 * `false`.
 *
 * ## Design
 *
 *  - Android owns execution of [HybridExecutePayload.local_steps] only.
 *  - The remote portion ([HybridExecutePayload.remote_steps]) is coordinated by V2;
 *    Android does NOT attempt to execute remote steps and does NOT act as a V2 proxy.
 *  - [acceptHybridExecute] is gated by [RolloutControlSnapshot.crossDeviceAllowed]:
 *    when closed the call returns [HybridExecutionResult.Status.BLOCKED].
 *  - All execution outcomes are returned as [HybridExecutionResult]; the caller is
 *    responsible for serialising to [HybridResultPayload] and sending upstream.
 *  - Emits [GalaxyLogger.TAG_HYBRID_PARTICIPANT] log entries for accept, block, and
 *    result events so operators can trace the hybrid-execute path.
 *
 * ## Capability promotion
 *
 * The presence of this class is what permits
 * [HybridParticipantCapability.HYBRID_EXECUTE_FULL] to carry
 * [HybridParticipantCapability.SupportLevel.AVAILABLE].  Without a real executor,
 * the capability would remain [HybridParticipantCapability.SupportLevel.NOT_YET_IMPLEMENTED]
 * and [fullMeshRuntimeExecutable] would remain structurally unreachable.
 *
 * @param localExecutor  The [LocalStepExecutor] that executes the local portion of the
 *                       hybrid task.  In production this delegates to the autonomous
 *                       execution pipeline.  In tests a fake can be supplied.
 * @param deviceId       Stable device identifier included in every [HybridExecutionResult].
 *
 * @see HybridParticipantCapability.HYBRID_EXECUTE_FULL
 * @see AndroidMeshParticipationContract
 * @see StagedMeshExecutionTarget
 */
class HybridExecuteFullCoordinator(
    private val localExecutor: LocalStepExecutor,
    private val deviceId: String
) {

    /**
     * Functional interface for the goal-execution pipeline dependency.
     *
     * Executes the local portion of a hybrid task described by [GoalExecutionPayload].
     * Implementations MUST be non-throwing; any pipeline error should be captured and
     * returned as a [GoalResultPayload] with status `"error"`.
     */
    fun interface LocalStepExecutor {
        /** Executes [payload] locally and returns the [GoalResultPayload] outcome. */
        suspend fun executeLocally(payload: GoalExecutionPayload): GoalResultPayload
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Accepts and executes the local portion of a `hybrid_execute` request from V2.
     *
     * **Rollout gate** — when [RolloutControlSnapshot.crossDeviceAllowed] is `false`,
     * returns [HybridExecutionResult] with [HybridExecutionResult.Status.BLOCKED]
     * immediately without invoking the pipeline.
     *
     * **Execution** — delegates the local steps to [localExecutor], then wraps the
     * outcome in a [HybridExecutionResult].  The remote portion is not touched;
     * V2 coordinates remote execution independently.
     *
     * @param payload         [HybridExecutePayload] carrying the hybrid task details.
     * @param rolloutSnapshot Current [RolloutControlSnapshot] used to gate participation.
     * @return [HybridExecutionResult] describing the local execution outcome.
     */
    suspend fun acceptHybridExecute(
        payload: HybridExecutePayload,
        rolloutSnapshot: RolloutControlSnapshot
    ): HybridExecutionResult {

        // ── Rollout gate ──────────────────────────────────────────────────────
        if (!rolloutSnapshot.crossDeviceAllowed) {
            GalaxyLogger.log(
                GalaxyLogger.TAG_HYBRID_PARTICIPANT,
                mapOf(
                    "event"   to "hybrid_execute_full_blocked",
                    "task_id" to payload.task_id,
                    "reason"  to "cross_device_disabled"
                )
            )
            return HybridExecutionResult(
                taskId       = payload.task_id,
                deviceId     = deviceId,
                status       = HybridExecutionResult.Status.BLOCKED,
                localResult  = null,
                error        = "hybrid_execute_full_blocked: cross_device_disabled",
                localStepCount = 0,
                latencyMs    = 0L
            )
        }

        // ── Execute local steps ───────────────────────────────────────────────
        GalaxyLogger.log(
            GalaxyLogger.TAG_HYBRID_PARTICIPANT,
            mapOf(
                "event"        to "hybrid_execute_full_accept",
                "task_id"      to payload.task_id,
                "local_steps"  to payload.local_steps.size,
                "remote_steps" to payload.remote_steps.size
            )
        )

        val startMs = System.currentTimeMillis()
        val localPayload = GoalExecutionPayload(
            task_id     = payload.task_id,
            goal        = payload.goal,
            constraints = payload.local_steps,
            max_steps   = payload.local_steps.size.coerceAtLeast(1),
            timeout_ms  = payload.timeout_ms
        )
        val goalResult = localExecutor.executeLocally(localPayload)
        val latencyMs = System.currentTimeMillis() - startMs

        val enrichedDeviceId = if (goalResult.device_id.isBlank()) deviceId else goalResult.device_id

        val status = when {
            goalResult.status == "success"   -> HybridExecutionResult.Status.LOCAL_SUCCESS
            goalResult.status == "cancelled" -> HybridExecutionResult.Status.CANCELLED
            else                             -> HybridExecutionResult.Status.LOCAL_FAILURE
        }

        val result = HybridExecutionResult(
            taskId         = payload.task_id,
            deviceId       = enrichedDeviceId,
            status         = status,
            localResult    = goalResult.result,
            error          = goalResult.error,
            localStepCount = goalResult.steps.size,
            latencyMs      = latencyMs
        )

        GalaxyLogger.log(
            GalaxyLogger.TAG_HYBRID_PARTICIPANT,
            mapOf(
                "event"             to "hybrid_execute_full_result",
                "task_id"           to payload.task_id,
                "status"            to result.status.wireValue,
                "local_step_count"  to result.localStepCount,
                "latency_ms"        to result.latencyMs
            )
        )

        return result
    }

    /**
     * Converts a [HybridExecutionResult] to the wire-format [HybridResultPayload]
     * for uplink to V2.
     *
     * @param result The [HybridExecutionResult] to convert.
     */
    fun toHybridResultPayload(result: HybridExecutionResult): HybridResultPayload =
        HybridResultPayload(
            task_id        = result.taskId,
            correlation_id = result.taskId,
            status         = result.status.wireStatus,
            local_result   = result.localResult,
            remote_result  = null,
            device_id      = result.deviceId,
            error          = result.error,
            latency_ms     = result.latencyMs
        )
}

/**
 * Terminal result of a local hybrid-execute coordination step.
 *
 * @property taskId         Task identifier echoed from the originating request.
 * @property deviceId       Android device that produced this result.
 * @property status         Terminal [Status] of the local execution portion.
 * @property localResult    Summary string of local execution output; null on failure/block.
 * @property error          Human-readable error description; null on success.
 * @property localStepCount Number of local action steps executed.
 * @property latencyMs      Wall-clock execution time in milliseconds.
 */
data class HybridExecutionResult(
    val taskId: String,
    val deviceId: String,
    val status: Status,
    val localResult: String?,
    val error: String?,
    val localStepCount: Int,
    val latencyMs: Long
) {
    /**
     * Terminal status of the local portion of a hybrid-execute coordination.
     *
     * @property wireValue  Stable lowercase wire value for diagnostics and logging.
     * @property wireStatus Wire status string for [HybridResultPayload.status].
     */
    enum class Status(val wireValue: String, val wireStatus: String) {
        /** Local steps executed successfully; V2 coordinates the remote portion. */
        LOCAL_SUCCESS("local_success", "success"),

        /** Local steps failed; error details in [HybridExecutionResult.error]. */
        LOCAL_FAILURE("local_failure", "error"),

        /** Execution was cancelled before or during local steps. */
        CANCELLED("cancelled", "error"),

        /** Blocked by a rollout gate before any local work was started. */
        BLOCKED("blocked", "error")
    }

    companion object {
        /** Wire key for [taskId] in diagnostics metadata maps. */
        const val KEY_TASK_ID = "hybrid_exec_full_task_id"

        /** Wire key for [deviceId] in diagnostics metadata maps. */
        const val KEY_DEVICE_ID = "hybrid_exec_full_device_id"

        /** Wire key for [status] in diagnostics metadata maps. */
        const val KEY_STATUS = "hybrid_exec_full_status"

        /** Wire key for [localStepCount] in diagnostics metadata maps. */
        const val KEY_LOCAL_STEP_COUNT = "hybrid_exec_full_local_step_count"

        /** Wire key for [latencyMs] in diagnostics metadata maps. */
        const val KEY_LATENCY_MS = "hybrid_exec_full_latency_ms"
    }
}
