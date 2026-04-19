package com.ufo.galaxy.agent

import android.util.Log
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.runtime.ContinuityRecoveryContext
import com.ufo.galaxy.runtime.ExecutorTargetType
import com.ufo.galaxy.runtime.RuntimeObservabilityMetadata
import com.ufo.galaxy.runtime.ExecutionContractCompatibilityValidator
import com.ufo.galaxy.runtime.SourceRuntimePosture

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

        /**
         * Error reason used when [handleGoalExecution] or [handleParallelSubtask] is
         * blocked by a [SourceRuntimePosture.CONTROL_ONLY] posture on the inbound payload.
         *
         * A `control_only` source declares that the originating device is acting purely
         * as a controller/initiator; Android must not treat itself as a runtime executor
         * for this task. The canonical contract (main-repo PR #533) requires this gate so
         * that Android execution is reserved for `join_runtime`-eligible tasks.
         */
        const val REASON_POSTURE_CONTROL_ONLY = "posture_control_only"
    }

    /**
     * Handles a [goal_execution] message.
     *
     * Five gates are evaluated in order:
     * 1. **Runtime gate**: returns [STATUS_DISABLED] when [AppSettings.crossDeviceEnabled]
     *    is `false`.  [goal_execution] is a cross-device-only message type and must not
     *    execute outside the canonical runtime pipeline.
     * 2. **Feature gate**: returns [STATUS_DISABLED] when [AppSettings.goalExecutionEnabled]
     *    is `false`.
     * 3. **Posture gate**: returns [STATUS_DISABLED] when [GoalExecutionPayload.source_runtime_posture]
     *    is [SourceRuntimePosture.CONTROL_ONLY].  A `control_only` source has declared that it
     *    is not joining local runtime execution; Android must honour this contract and refuse
     *    to act as an executor for the task.
     * 4. **Target type check** (PR-E): logs the [GoalExecutionPayload.executor_target_type] when
     *    present for observability; tolerates `null` (legacy) and unknown values without rejection
     *    to preserve backward compatibility.
     * 5. **Continuity/recovery check** (PR-F): logs [GoalExecutionPayload.continuity_token],
     *    [GoalExecutionPayload.is_resumable], and [GoalExecutionPayload.interruption_reason] when
     *    present for observability.  These fields are accepted without failure; `null` / absent
     *    values are treated as the legacy contract.
     * 6. **Observability metadata** (PR-G): logs [GoalExecutionPayload.dispatch_trace_id] and
     *    [GoalExecutionPayload.lifecycle_event_id] when present for cross-system tracing;
     *    tolerates `null` / absent values (legacy senders) without failure.
     * 7. **Dispatch metadata** (PR-48): logs [GoalExecutionPayload.dispatch_plan_id] and
     *    [GoalExecutionPayload.source_dispatch_strategy] when present for dispatch plan
     *    correlation; tolerates `null` / absent values (legacy senders) without failure.
     *
     * When all gates pass, delegates to [LocalGoalExecutor.executeGoal] and enriches
     * the result with [device_role], the echoed [GoalExecutionPayload.executor_target_type],
     * the echoed [GoalExecutionPayload.continuity_token] / [GoalExecutionPayload.is_resumable],
     * the echoed [GoalExecutionPayload.dispatch_trace_id] for full-chain V2 observability,
     * and the echoed [GoalExecutionPayload.dispatch_plan_id] for dispatch plan correlation.
     * so V2 can correlate the result with its originating durable continuity context.
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
        if (SourceRuntimePosture.isControlOnly(payload.source_runtime_posture)) {
            Log.i(
                TAG,
                "goal_execution blocked: source_runtime_posture=control_only; task_id=${payload.task_id}"
            )
            return buildDisabledResult(payload, REASON_POSTURE_CONTROL_ONLY)
        }
        // PR-E: log explicit executor_target_type for observability; tolerate null / unknown values
        // to preserve backward compatibility with pre-V2 senders.
        val targetType = ExecutorTargetType.fromValue(payload.executor_target_type)
        if (payload.executor_target_type != null) {
            Log.i(
                TAG,
                "goal_execution executor_target_type=${payload.executor_target_type} " +
                    "canonical=$targetType eligible=${ExecutorTargetType.isAndroidEligible(targetType)} " +
                    "task_id=${payload.task_id}"
            )
        }
        // PR-F: log continuity/recovery context for observability; accept without failure.
        logContinuityContext("goal_execution", payload)
        // PR-G: log observability tracing metadata; accept without failure.
        logObservabilityContext("goal_execution", payload)
        // PR-48: log richer dispatch metadata for observability; accept without failure.
        logDispatchMetadataContext("goal_execution", payload)
        Log.i(TAG, "goal_execution executing locally; task_id=${payload.task_id}")
        return goalExecutor.executeGoal(payload)
            .copy(
                device_role = deviceRole,
                executor_target_type = payload.executor_target_type,
                continuity_token = payload.continuity_token,
                is_resumable = payload.is_resumable,
                dispatch_trace_id = payload.dispatch_trace_id,
                dispatch_plan_id = payload.dispatch_plan_id
            )
    }

    /**
     * Handles a [parallel_subtask] message.
     *
     * Five gates are evaluated in order:
     * 1. **Runtime gate**: returns [STATUS_DISABLED] when [AppSettings.crossDeviceEnabled]
     *    is `false`.  [parallel_subtask] is a cross-device-only message type and must not
     *    execute outside the canonical runtime pipeline.
     * 2. **Feature gate**: returns [STATUS_DISABLED] when [AppSettings.parallelExecutionEnabled]
     *    is `false`.
     * 3. **Posture gate**: returns [STATUS_DISABLED] when [GoalExecutionPayload.source_runtime_posture]
     *    is [SourceRuntimePosture.CONTROL_ONLY].  Parallel subtasks are only valid for
     *    `join_runtime` participants; a `control_only` source must not contribute subtask
     *    results to the runtime execution pool.
     * 4. **Target type check** (PR-E): logs the [GoalExecutionPayload.executor_target_type] when
     *    present for observability; tolerates `null` (legacy) and unknown values without rejection
     *    to preserve backward compatibility.
     * 5. **Continuity/recovery check** (PR-F): logs continuity/recovery context for observability.
     *    Fields are accepted without failure; null / absent values are treated as legacy contract.
     * 6. **Observability metadata** (PR-G): logs [GoalExecutionPayload.dispatch_trace_id] and
     *    [GoalExecutionPayload.lifecycle_event_id] when present for cross-system tracing.
     * 7. **Dispatch metadata** (PR-48): logs [GoalExecutionPayload.dispatch_plan_id] and
     *    [GoalExecutionPayload.source_dispatch_strategy] when present for dispatch plan
     *    correlation; tolerates `null` / absent values (legacy senders) without failure.
     *
     * When all gates pass, delegates to [LocalCollaborationAgent.handleParallelSubtask]
     * and enriches the result with [device_role], the echoed
     * [GoalExecutionPayload.executor_target_type], the echoed continuity/recovery fields,
     * the echoed [GoalExecutionPayload.dispatch_trace_id] for full-chain V2 observability,
     * and the echoed [GoalExecutionPayload.dispatch_plan_id] for dispatch plan correlation.
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
        if (SourceRuntimePosture.isControlOnly(payload.source_runtime_posture)) {
            Log.i(
                TAG,
                "parallel_subtask blocked: source_runtime_posture=control_only; task_id=${payload.task_id}"
            )
            return buildDisabledResult(payload, REASON_POSTURE_CONTROL_ONLY)
        }
        // PR-E: log explicit executor_target_type for observability; tolerate null / unknown values.
        val targetType = ExecutorTargetType.fromValue(payload.executor_target_type)
        if (payload.executor_target_type != null) {
            Log.i(
                TAG,
                "parallel_subtask executor_target_type=${payload.executor_target_type} " +
                    "canonical=$targetType eligible=${ExecutorTargetType.isAndroidEligible(targetType)} " +
                    "task_id=${payload.task_id}"
            )
        }
        // PR-F: log continuity/recovery context for observability; accept without failure.
        logContinuityContext("parallel_subtask", payload)
        // PR-G: log observability tracing metadata; accept without failure.
        logObservabilityContext("parallel_subtask", payload)
        // PR-48: log richer dispatch metadata for observability; accept without failure.
        logDispatchMetadataContext("parallel_subtask", payload)
        Log.i(TAG, "parallel_subtask executing locally; task_id=${payload.task_id}")
        return collaborationAgent.handleParallelSubtask(payload)
            .copy(
                device_role = deviceRole,
                executor_target_type = payload.executor_target_type,
                continuity_token = payload.continuity_token,
                is_resumable = payload.is_resumable,
                dispatch_trace_id = payload.dispatch_trace_id,
                dispatch_plan_id = payload.dispatch_plan_id
            )
    }

    /**
     * Logs PR-F durable continuity and recovery context fields for observability.
     *
     * This method is a no-op when all continuity fields are null / empty, so it
     * imposes no overhead on legacy (pre-PR-F) senders.
     */
    private fun logContinuityContext(msgType: String, payload: GoalExecutionPayload) {
        val hasContinuity = payload.continuity_token != null
            || payload.is_resumable != null
            || payload.interruption_reason != null
            || payload.recovery_context.isNotEmpty()
        if (!hasContinuity) return

        val knownReason = ContinuityRecoveryContext.isKnownInterruptionReason(payload.interruption_reason)
        val isTransport = ContinuityRecoveryContext.isTransportInterruption(payload.interruption_reason)
        Log.i(
            TAG,
            "$msgType continuity_token=${payload.continuity_token} " +
                "is_resumable=${payload.is_resumable} " +
                "interruption_reason=${payload.interruption_reason} " +
                "known_reason=$knownReason transport_interruption=$isTransport " +
                "recovery_context_keys=${payload.recovery_context.keys} " +
                "task_id=${payload.task_id}"
        )
    }

    /**
     * Logs PR-G observability/tracing metadata fields.
     *
     * A no-op when all observability fields are null/blank, so it imposes no overhead
     * on legacy (pre-PR-G) senders.
     */
    private fun logObservabilityContext(msgType: String, payload: GoalExecutionPayload) {
        val hasObservability = RuntimeObservabilityMetadata.hasDispatchTraceId(payload.dispatch_trace_id)
            || RuntimeObservabilityMetadata.hasLifecycleEventId(payload.lifecycle_event_id)
        if (!hasObservability) return

        Log.i(
            TAG,
            "$msgType dispatch_trace_id=${payload.dispatch_trace_id} " +
                "lifecycle_event_id=${payload.lifecycle_event_id} " +
                "task_id=${payload.task_id}"
        )
    }

    /**
     * Logs PR-48 richer dispatch metadata fields for observability.
     *
     * A no-op when both dispatch metadata fields are null/blank, so it imposes no
     * overhead on legacy (pre-PR-48) senders.
     */
    private fun logDispatchMetadataContext(msgType: String, payload: GoalExecutionPayload) {
        val hasDispatch = ExecutionContractCompatibilityValidator.hasDispatchPlanId(payload.dispatch_plan_id)
            || !payload.source_dispatch_strategy.isNullOrBlank()
        if (!hasDispatch) return

        val strategyHint = ExecutionContractCompatibilityValidator.DispatchStrategyHint
            .fromValue(payload.source_dispatch_strategy)
        val androidEligible = ExecutionContractCompatibilityValidator
            .isAndroidEligibleStrategy(payload.source_dispatch_strategy)
        Log.i(
            TAG,
            "$msgType dispatch_plan_id=${payload.dispatch_plan_id} " +
                "source_dispatch_strategy=${payload.source_dispatch_strategy} " +
                "canonical_strategy=$strategyHint android_eligible=$androidEligible " +
                "task_id=${payload.task_id}"
        )
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
        device_role = deviceRole,
        executor_target_type = payload.executor_target_type,
        // PR-F: echo continuity/recovery fields in disabled results so V2 can correlate
        continuity_token = payload.continuity_token,
        is_resumable = payload.is_resumable,
        // PR-G: echo dispatch_trace_id in disabled results for full-chain observability
        dispatch_trace_id = payload.dispatch_trace_id,
        // PR-48: echo dispatch_plan_id in disabled results for dispatch plan correlation
        dispatch_plan_id = payload.dispatch_plan_id
    )
}
