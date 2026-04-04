package com.ufo.galaxy.session

import com.ufo.galaxy.protocol.CancelResultPayload
import com.ufo.galaxy.protocol.GoalResultPayload

/**
 * Canonical Android-side session contribution envelope (PR-4).
 *
 * Normalizes the various Android result output shapes (goal execution results,
 * cancel results, takeover continuation) into a single stable contribution format
 * that the main repository can consume as a session-truth input stream without
 * inspecting raw wire-level status strings.
 *
 * Each contribution has a [kind] discriminator that cleanly separates:
 * - [Kind.FINAL_COMPLETION]: task executed to a final success state.
 * - [Kind.FAILURE]: task failed (error, timeout, or pipeline fault).
 * - [Kind.CANCELLATION]: task was cancelled by an explicit request or preemption.
 * - [Kind.TAKEOVER_CONTINUATION]: Android accepted a takeover and will continue the task.
 * - [Kind.DISABLED]: the pipeline was administratively disabled (posture gate, feature flag).
 *
 * ## Scope note
 * This class represents Android's *contribution* to the session truth — a stable,
 * single-format output that the main repo can depend on.  It does **not** own the
 * global authoritative session truth; that responsibility belongs to the main repo.
 *
 * @property kind                  Discriminator identifying the contribution type.
 * @property taskId                Task this contribution belongs to.
 * @property correlationId         Correlation identifier for reply routing (echoed from request).
 * @property status                Wire-level status string from the result payload.
 * @property traceId               End-to-end trace identifier propagated from the inbound request.
 * @property routeMode             Routing path: [ROUTE_LOCAL] or [ROUTE_CROSS_DEVICE].
 * @property sourceRuntimePosture  Echoed from the originating request; null for legacy paths.
 * @property groupId               Parallel-group identifier; non-null for subtask contributions.
 * @property subtaskIndex          Zero-based subtask index; non-null for subtask contributions.
 * @property stepCount             Number of action steps executed.
 * @property latencyMs             Wall-clock execution time in milliseconds.
 * @property error                 Human-readable error description; null on [Kind.FINAL_COMPLETION].
 * @property deviceId              Identifier of the Android device that produced this contribution.
 * @property deviceRole            Logical device role from settings (e.g. "phone", "tablet").
 */
data class AndroidSessionContribution(
    val kind: Kind,
    val taskId: String,
    val correlationId: String?,
    val status: String,
    val traceId: String?,
    val routeMode: String?,
    val sourceRuntimePosture: String?,
    val groupId: String?,
    val subtaskIndex: Int?,
    val stepCount: Int,
    val latencyMs: Long,
    val error: String?,
    val deviceId: String,
    val deviceRole: String
) {
    /**
     * Discriminator that cleanly separates Android-side session contribution types.
     *
     * Mirrors the result/outcome taxonomy expected by the main-repo canonical session-truth
     * layer so it can consume Android output without inspecting raw status strings.
     */
    enum class Kind {
        /** Task executed successfully and reached a final success state. */
        FINAL_COMPLETION,

        /** Task failed: error, timeout, model failure, or any non-success terminal state. */
        FAILURE,

        /** Task was cancelled by an explicit [com.ufo.galaxy.protocol.MsgType.TASK_CANCEL]
         *  request or runtime preemption. */
        CANCELLATION,

        /**
         * Android accepted a TAKEOVER_REQUEST and will continue executing the in-flight task.
         * This kind indicates a session continuation rather than a terminal result.
         */
        TAKEOVER_CONTINUATION,

        /**
         * The pipeline was administratively disabled: cross-device OFF, posture gate
         * ([com.ufo.galaxy.runtime.SourceRuntimePosture.CONTROL_ONLY]), or a feature flag
         * blocked execution.  Distinct from [FAILURE] so callers can differentiate
         * "device refused by policy" from "device tried and failed".
         */
        DISABLED
    }

    companion object {
        // ── Wire-level status constants shared with the AIP v3 protocol ─────────

        /** Task completed successfully. */
        const val STATUS_SUCCESS = "success"
        /** Task encountered an error. */
        const val STATUS_ERROR = "error"
        /** Task was cancelled. */
        const val STATUS_CANCELLED = "cancelled"
        /** Task was aborted by a timeout. */
        const val STATUS_TIMEOUT = "timeout"
        /** Execution was disabled by policy/gate. */
        const val STATUS_DISABLED = "disabled"

        /** Route mode for gateway-delivered tasks. */
        const val ROUTE_CROSS_DEVICE = "cross_device"
        /** Route mode for locally-originated tasks. */
        const val ROUTE_LOCAL = "local"

        // ── Factory: GoalResultPayload → AndroidSessionContribution ─────────────

        /**
         * Creates an [AndroidSessionContribution] from a [GoalResultPayload].
         *
         * [Kind] is inferred from [GoalResultPayload.status]:
         * - `"success"` → [Kind.FINAL_COMPLETION]
         * - `"cancelled"` → [Kind.CANCELLATION]
         * - `"disabled"` → [Kind.DISABLED]
         * - anything else (`"error"`, `"timeout"`, etc.) → [Kind.FAILURE]
         *
         * [traceId] and [routeMode] should be propagated from the inbound AIP v3 envelope
         * to maintain full-chain correlation.
         *
         * @param result    The [GoalResultPayload] to wrap.
         * @param traceId   End-to-end trace identifier from the inbound envelope.
         * @param routeMode Routing path used for this task.
         */
        fun fromGoalResult(
            result: GoalResultPayload,
            traceId: String? = null,
            routeMode: String? = null
        ): AndroidSessionContribution {
            val kind = when (result.status) {
                STATUS_SUCCESS -> Kind.FINAL_COMPLETION
                STATUS_CANCELLED -> Kind.CANCELLATION
                STATUS_DISABLED -> Kind.DISABLED
                else -> Kind.FAILURE
            }
            return AndroidSessionContribution(
                kind = kind,
                taskId = result.task_id,
                correlationId = result.correlation_id,
                status = result.status,
                traceId = traceId,
                routeMode = routeMode,
                sourceRuntimePosture = result.source_runtime_posture,
                groupId = result.group_id,
                subtaskIndex = result.subtask_index,
                stepCount = result.steps.size,
                latencyMs = result.latency_ms,
                error = result.error,
                deviceId = result.device_id,
                deviceRole = result.device_role
            )
        }

        // ── Factory: CancelResultPayload → AndroidSessionContribution ───────────

        /**
         * Creates an [AndroidSessionContribution] from a [CancelResultPayload].
         *
         * [Kind] is determined by [CancelResultPayload.was_running]:
         * - `true`  → [Kind.CANCELLATION]: a running task was actively interrupted.
         * - `false` → [Kind.DISABLED]: the cancel was a no-op because the task was not
         *   found (already completed or never started).  From the main-repo session-truth
         *   perspective, no active cancellation event occurred; the contribution is treated
         *   as [Kind.DISABLED] to signal "no state change" rather than conflating it with
         *   an actual cancellation.  Callers that need to distinguish these sub-cases can
         *   inspect [AndroidSessionContribution.status] (`"cancelled"` vs `"no_op"`).
         *
         * @param result    The [CancelResultPayload] to wrap.
         * @param traceId   Trace identifier for correlation.
         * @param routeMode Routing path.
         */
        fun fromCancelResult(
            result: CancelResultPayload,
            traceId: String? = null,
            routeMode: String? = null
        ): AndroidSessionContribution {
            val kind = if (result.was_running) Kind.CANCELLATION else Kind.DISABLED
            return AndroidSessionContribution(
                kind = kind,
                taskId = result.task_id,
                correlationId = result.correlation_id,
                status = result.status,
                traceId = traceId,
                routeMode = routeMode,
                sourceRuntimePosture = null, // CancelResultPayload does not carry posture
                groupId = result.group_id,
                subtaskIndex = result.subtask_index,
                stepCount = 0,
                latencyMs = 0L,
                error = result.error,
                deviceId = result.device_id,
                deviceRole = ""
            )
        }
    }
}
