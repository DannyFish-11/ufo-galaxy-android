package com.ufo.galaxy.coordination

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID

/**
 * Multi-device task coordinator (P3).
 *
 * Extends the single-device dispatch model with [dispatchParallel], which fans a task
 * out to multiple device IDs concurrently and collects results into a [ParallelGroupResult].
 *
 * Each subtask receives a unique, prefixed subtask ID derived from the group ID so that
 * gateway aggregation can match subtask results back to their originating group:
 * ```
 * <groupId>_sub_<index>
 * ```
 *
 * Usage:
 * ```kotlin
 * val coordinator = MultiDeviceCoordinator(deviceIds = listOf("phone-1", "tablet-1")) { deviceId, subtaskId, goal ->
 *     // send task to device, return SubtaskResult
 *     SubtaskResult(subtaskId = subtaskId, deviceId = deviceId, success = true)
 * }
 * val group = coordinator.dispatchParallel(goal = "open settings", groupId = "grp-abc")
 * Log.i(TAG, "succeeded=${group.succeededCount} failed=${group.failedCount}")
 * ```
 *
 * @param deviceIds    Ordered list of device identifiers to dispatch to.
 * @param dispatch     Suspending function called for each device.  Must not throw;
 *                     exceptions should be captured and returned as a failed [SubtaskResult].
 */
/**
 * @param crossDeviceEnabled When `false`, [dispatchParallel] is a no-op that returns a
 *   [ParallelGroupResult] containing a failed [SubtaskResult] for each device,
 *   with `error = "cross_device_disabled"`. This enforces the Round-4 hard constraint:
 *   parallel multi-device tasks must not be initiated when the cross-device switch is OFF.
 */
class MultiDeviceCoordinator(
    private val deviceIds: List<String>,
    private val crossDeviceEnabled: Boolean = true,
    private val dispatch: suspend (deviceId: String, subtaskId: String, goal: String) -> SubtaskResult
) {

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Dispatches [goal] to all [deviceIds] concurrently.
     *
     * Each device receives a unique subtask ID in the format `<groupId>_sub_<index>`.
     * Results are collected asynchronously via [async]/[awaitAll] and returned in
     * the same order as [deviceIds].
     *
     * **Hard constraint**: when [crossDeviceEnabled] is `false` this method returns
     * immediately with a [ParallelGroupResult] where every subtask has
     * `success=false` and `error="cross_device_disabled"`. No [dispatch] calls are
     * made and no WS/network activity is initiated.
     *
     * @param goal    Natural-language goal description to dispatch to every device.
     * @param groupId Stable group identifier for this batch; auto-generated if not supplied.
     * @return [ParallelGroupResult] aggregating all subtask outcomes.
     */
    suspend fun dispatchParallel(
        goal: String,
        groupId: String = "grp_${UUID.randomUUID()}"
    ): ParallelGroupResult = coroutineScope {
        // Hard constraint: cross-device switch OFF must block ALL multi-device dispatch.
        if (!crossDeviceEnabled) {
            val traceId = UUID.randomUUID().toString()
            val reason = "cross_device_disabled"
            Log.w(TAG, "[COORD] dispatchParallel blocked: cross_device=off trace_id=$traceId group_id=$groupId reason=$reason")
            val blockedResults = deviceIds.mapIndexed { index, deviceId ->
                SubtaskResult(
                    subtaskId = "${groupId}_sub_$index",
                    deviceId = deviceId,
                    success = false,
                    error = reason
                )
            }
            return@coroutineScope ParallelGroupResult(groupId = groupId, goal = goal, subtaskResults = blockedResults)
        }

        Log.i(TAG, "[COORD] dispatchParallel group_id=$groupId devices=${deviceIds.size} goal=${goal.take(60)}")

        val deferreds = deviceIds.mapIndexed { index, deviceId ->
            val subtaskId = "${groupId}_sub_$index"
            async {
                try {
                    val result = dispatch(deviceId, subtaskId, goal)
                    Log.i(TAG, "[COORD] subtask_done subtask_id=$subtaskId device_id=$deviceId success=${result.success}")
                    result
                } catch (e: Exception) {
                    Log.e(TAG, "[COORD] subtask_error subtask_id=$subtaskId device_id=$deviceId error=${e.message}", e)
                    SubtaskResult(
                        subtaskId = subtaskId,
                        deviceId = deviceId,
                        success = false,
                        error = e.message ?: "unknown error"
                    )
                }
            }
        }

        val results = deferreds.awaitAll()
        ParallelGroupResult(groupId = groupId, goal = goal, subtaskResults = results).also { group ->
            Log.i(TAG, "[COORD] group_done group_id=$groupId succeeded=${group.succeededCount} failed=${group.failedCount}")
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    /**
     * Result for a single subtask dispatched to one device.
     *
     * @param subtaskId  Unique subtask identifier (`<groupId>_sub_<index>`).
     * @param deviceId   Target device identifier.
     * @param success    Whether the subtask completed successfully.
     * @param output     Optional result payload from the device.
     * @param error      Error description when [success] is false.
     * @param latencyMs  Wall-clock execution time in milliseconds, or null if unknown.
     */
    data class SubtaskResult(
        val subtaskId: String,
        val deviceId: String,
        val success: Boolean,
        val output: String? = null,
        val error: String? = null,
        val latencyMs: Long? = null
    )

    /**
     * Aggregated result for a parallel dispatch group.
     *
     * @param groupId        Stable group identifier shared across all subtasks.
     * @param goal           Original goal dispatched to all devices.
     * @param subtaskResults Ordered list of per-device subtask results.
     */
    data class ParallelGroupResult(
        val groupId: String,
        val goal: String,
        val subtaskResults: List<SubtaskResult>
    ) {
        /** Number of subtasks that completed successfully. */
        val succeededCount: Int get() = subtaskResults.count { it.success }

        /** Number of subtasks that failed. */
        val failedCount: Int get() = subtaskResults.count { !it.success }

        /** Subtasks that completed successfully. */
        val succeeded: List<SubtaskResult> get() = subtaskResults.filter { it.success }

        /** Subtasks that failed. */
        val failed: List<SubtaskResult> get() = subtaskResults.filter { !it.success }

        /** True when every subtask in [subtaskResults] succeeded. */
        val allSucceeded: Boolean get() = failedCount == 0
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "MultiDeviceCoordinator"
    }
}
