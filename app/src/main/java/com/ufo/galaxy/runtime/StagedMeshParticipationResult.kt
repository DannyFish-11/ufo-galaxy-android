package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.UgcpSharedSchemaAlignment
import com.ufo.galaxy.session.AndroidSessionContribution

/**
 * PR-32 — Canonical result envelope for a staged-mesh target execution.
 *
 * Captures the execution outcome of a single staged-mesh subtask executed on this
 * Android device as a V2-directed participation target.  This class is the Android
 * target-side counterpart to the V2 staged mesh result aggregation contract.
 *
 * ## Identity chain
 *
 * Each result carries a full identity chain: [meshId] → [subtaskId] → [taskId] → [deviceId].
 * This chain allows the V2 coordinator to correlate the result back to its originating
 * mesh session, staged subtask slot, and task without inspecting raw status strings.
 *
 * ## Semantics compatibility
 *
 * [toSessionContribution] converts this result to an [AndroidSessionContribution] with
 * [AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK], preserving full identity across
 * the staged-mesh execution boundary so session-truth consumers can apply the same
 * single-system contracts they use for delegated execution results.
 *
 * ## Wire keys
 *
 * Each identity field has a [KEY_*] companion constant.  Use [toMetadataMap] to produce
 * a stable metadata [Map] for telemetry and diagnostics payloads.
 *
 * @property meshId           Stable staged-mesh session identifier shared by all participants.
 * @property subtaskId        Unique subtask identifier assigned by the V2 coordinator.
 * @property taskId           Top-level task identifier from the originating request.
 * @property deviceId         Android device identifier that produced this result.
 * @property executionStatus  Terminal execution status of this subtask.
 * @property output           Optional execution output summary string.
 * @property error            Human-readable error description; null when [executionStatus]
 *                            is [ExecutionStatus.SUCCESS].
 * @property stepCount        Number of action steps executed locally.
 * @property latencyMs        Wall-clock execution time in milliseconds.
 */
data class StagedMeshParticipationResult(
    val meshId: String,
    val subtaskId: String,
    val taskId: String,
    val deviceId: String,
    val executionStatus: ExecutionStatus,
    val output: String? = null,
    val error: String? = null,
    val stepCount: Int = 0,
    val latencyMs: Long = 0L
) {

    /**
     * Terminal execution status of a staged-mesh subtask.
     *
     * @property wireValue Stable lowercase string used in diagnostics and telemetry payloads.
     */
    enum class ExecutionStatus(val wireValue: String) {
        /** Subtask executed and reached a successful terminal state. */
        SUCCESS("success"),

        /** Subtask failed (error, model fault, or pipeline failure). */
        FAILURE("failure"),

        /** Subtask was cancelled before or during execution. */
        CANCELLED("cancelled"),

        /**
         * Execution was blocked by a rollout-control gate (cross-device disabled,
         * kill-switch active, etc.) before any work was started.
         */
        BLOCKED("blocked")
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    /**
     * Serialises identity and status fields to a stable key→value [Map] suitable for
     * inclusion in diagnostics payloads and [com.ufo.galaxy.observability.GalaxyLogger]
     * structured log entries.
     *
     * All fields are always present; conditional string fields ([output], [error]) are
     * omitted from the map when they are `null` to keep the wire format lean.
     */
    fun toMetadataMap(): Map<String, Any> = buildMap {
        put(KEY_MESH_ID, meshId)
        put(KEY_SUBTASK_ID, subtaskId)
        put(KEY_TASK_ID, taskId)
        put(KEY_DEVICE_ID, deviceId)
        put(KEY_EXECUTION_STATUS, executionStatus.wireValue)
        put(KEY_STEP_COUNT, stepCount)
        put(KEY_LATENCY_MS, latencyMs)
        output?.let { put(KEY_OUTPUT, it) }
        error?.let { put(KEY_ERROR, it) }
    }

    // ── Session-contribution conversion ───────────────────────────────────────

    /**
     * Converts this result to an [AndroidSessionContribution] with
     * [AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK] so that session-truth
     * consumers can apply the same single-system contracts used for other result types.
     *
     * The conversion rules are:
     * - [ExecutionStatus.SUCCESS]   → [AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK]
     * - [ExecutionStatus.FAILURE]   → [AndroidSessionContribution.Kind.FAILURE]
     * - [ExecutionStatus.CANCELLED] → [AndroidSessionContribution.Kind.CANCELLATION]
     * - [ExecutionStatus.BLOCKED]   → [AndroidSessionContribution.Kind.DISABLED]
     *
     * [AndroidSessionContribution.traceId] is set to [meshId] so the host can correlate
     * the session contribution back to the originating mesh session without a separate
     * lookup.  [AndroidSessionContribution.groupId] is also set to [meshId] for the same
     * reason; [AndroidSessionContribution.correlationId] carries [subtaskId].
     *
     * @param deviceRole Logical device role (e.g. `"phone"`, `"tablet"`).
     */
    fun toSessionContribution(deviceRole: String = ""): AndroidSessionContribution {
        val status = when (executionStatus) {
            ExecutionStatus.SUCCESS   -> AndroidSessionContribution.STATUS_SUCCESS
            ExecutionStatus.FAILURE   -> AndroidSessionContribution.STATUS_ERROR
            ExecutionStatus.CANCELLED -> AndroidSessionContribution.STATUS_CANCELLED
            ExecutionStatus.BLOCKED   -> AndroidSessionContribution.STATUS_DISABLED
        }
        val kind = when (executionStatus) {
            ExecutionStatus.SUCCESS   -> AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK
            ExecutionStatus.CANCELLED -> AndroidSessionContribution.Kind.CANCELLATION
            ExecutionStatus.BLOCKED   -> AndroidSessionContribution.Kind.DISABLED
            ExecutionStatus.FAILURE   -> AndroidSessionContribution.Kind.FAILURE
        }
        return AndroidSessionContribution(
            kind                 = kind,
            taskId               = taskId,
            correlationId        = subtaskId,
            status               = status,
            traceId              = meshId,
            routeMode            = AndroidSessionContribution.ROUTE_CROSS_DEVICE,
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
            groupId              = meshId,
            subtaskIndex         = null,
            stepCount            = stepCount,
            latencyMs            = latencyMs,
            error                = error,
            deviceId             = deviceId,
            deviceRole           = deviceRole
        )
    }

    // ── Companion / factory ───────────────────────────────────────────────────

    companion object {

        // ── Wire-key constants ────────────────────────────────────────────────

        /** Wire key for [meshId]. */
        const val KEY_MESH_ID = "staged_mesh_id"

        /** Wire key for [subtaskId]. */
        const val KEY_SUBTASK_ID = "staged_mesh_subtask_id"

        /** Wire key for [taskId]. */
        const val KEY_TASK_ID = "staged_mesh_task_id"

        /** Wire key for [deviceId]. */
        const val KEY_DEVICE_ID = "staged_mesh_device_id"

        /** Wire key for [executionStatus] ([ExecutionStatus.wireValue]). */
        const val KEY_EXECUTION_STATUS = "staged_mesh_execution_status"

        /** Wire key for [stepCount]. */
        const val KEY_STEP_COUNT = "staged_mesh_step_count"

        /** Wire key for [latencyMs]. */
        const val KEY_LATENCY_MS = "staged_mesh_latency_ms"

        /** Wire key for [output]. */
        const val KEY_OUTPUT = "staged_mesh_output"

        /** Wire key for [error]. */
        const val KEY_ERROR = "staged_mesh_error"

        // ── Factory: GoalResultPayload → StagedMeshParticipationResult ────────

        /**
         * Creates a [StagedMeshParticipationResult] from a [GoalResultPayload] produced
         * by the local goal-execution pipeline.
         *
         * [ExecutionStatus] is inferred from [GoalResultPayload.status]:
         * - `"success"`   → [ExecutionStatus.SUCCESS]
         * - `"cancelled"` → [ExecutionStatus.CANCELLED]
         * - `"disabled"`  → [ExecutionStatus.BLOCKED]
         * - anything else → [ExecutionStatus.FAILURE]
         *
         * @param meshId    Staged-mesh session identifier to attach to the result.
         * @param subtaskId Subtask identifier assigned by the V2 coordinator.
         * @param result    The [GoalResultPayload] produced by the local execution pipeline.
         */
        fun fromGoalResult(
            meshId: String,
            subtaskId: String,
            result: GoalResultPayload
        ): StagedMeshParticipationResult {
            val normalizedStatus = UgcpSharedSchemaAlignment.normalizeLifecycleStatus(result.status)
            val executionStatus = when (normalizedStatus) {
                AndroidSessionContribution.STATUS_SUCCESS -> ExecutionStatus.SUCCESS
                AndroidSessionContribution.STATUS_CANCELLED -> ExecutionStatus.CANCELLED
                AndroidSessionContribution.STATUS_DISABLED -> ExecutionStatus.BLOCKED
                else -> ExecutionStatus.FAILURE
            }
            return StagedMeshParticipationResult(
                meshId          = meshId,
                subtaskId       = subtaskId,
                taskId          = result.task_id,
                deviceId        = result.device_id,
                executionStatus = executionStatus,
                output          = result.result,
                error           = result.error,
                stepCount       = result.steps.size,
                latencyMs       = result.latency_ms
            )
        }
    }
}
