package com.ufo.galaxy.agent

import android.util.Log
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.runtime.ContinuityRecoveryContext
import com.ufo.galaxy.runtime.ExecutorTargetType
import com.ufo.galaxy.runtime.RuntimeObservabilityMetadata
import com.ufo.galaxy.runtime.ExecutionContractCompatibilityValidator
import com.ufo.galaxy.runtime.PolicyRoutingContext
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
 * without crashing.  Policy-rejected payloads ([GoalExecutionPayload.policy_routing_outcome]
 * == `"rejected"`) also return [STATUS_DISABLED] with reason [REASON_POLICY_REJECTED] and
 * the structured [GoalResultPayload.policy_rejection_reason] field populated so V2 can
 * re-route without treating the outcome as an Android-side error.
 * Temporarily-unavailable payloads (`"temporarily_unavailable"`) return [STATUS_PENDING]
 * with reason [REASON_POLICY_HOLD] and [GoalResultPayload.is_hold_pending] set to `true`
 * so V2 can retry dispatch when readiness is restored without treating the outcome as a
 * terminal failure.
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

        /**
         * Error reason used when [handleGoalExecution] or [handleParallelSubtask] is
         * blocked by a [PolicyRoutingContext.RoutingOutcome.REJECTED] policy decision
         * on the inbound payload.
         *
         * A policy-rejected payload carries an explicit [GoalExecutionPayload.policy_routing_outcome]
         * of `"rejected"`; Android must not execute the task and must return a structured
         * disabled result so V2 can re-route without treating this as an Android-side error.
         * The [GoalResultPayload.policy_rejection_reason] field is populated from the payload's
         * [GoalExecutionPayload.policy_failure_reason] to provide V2 with the structured reason.
         */
        const val REASON_POLICY_REJECTED = "policy_routing_rejected"

        /**
         * Status value returned when [handleGoalExecution] or [handleParallelSubtask] is
         * placed on hold due to a [PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE]
         * policy outcome.
         *
         * V2 must not treat this as a terminal failure; it should retry dispatch when device
         * readiness is restored.  The [GoalResultPayload.is_hold_pending] field is set to
         * `true` to provide V2 with an unambiguous signal for this state.
         */
        const val STATUS_PENDING = "pending"

        /**
         * Error reason used when [handleGoalExecution] or [handleParallelSubtask] is placed
         * on hold by a [PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE] policy
         * decision on the inbound payload.
         *
         * This is a non-terminal hold: Android is temporarily unavailable for new tasks
         * (e.g. transport is reconnecting, health is recovering, or session not yet attached).
         * V2 should retry when readiness is restored.
         */
        const val REASON_POLICY_HOLD = "policy_routing_hold"
    }

    /**
     * Handles a [goal_execution] message.
     *
     * Nine gates are evaluated in order:
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
     * 8. **Policy routing context** (PR-49/PR-I): logs [GoalExecutionPayload.policy_routing_outcome],
     *    [GoalExecutionPayload.policy_failure_reason], and
     *    [GoalExecutionPayload.readiness_degradation_hint] when present; returns
     *    [STATUS_DISABLED] with [REASON_POLICY_REJECTED] and structured
     *    [GoalResultPayload.policy_rejection_reason] when the outcome is explicitly
     *    [PolicyRoutingContext.RoutingOutcome.REJECTED]; tolerates `null` / absent values
     *    (legacy senders) without failure.
     * 9. **Temporary hold gate** (PR-51/PR-5B): returns [STATUS_PENDING] with
     *    [REASON_POLICY_HOLD] and [GoalResultPayload.is_hold_pending] set to `true`
     *    when the outcome is [PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE].
     *    This is a non-terminal hold; V2 should retry dispatch when readiness is restored.
     *
     * When all gates pass, delegates to [LocalGoalExecutor.executeGoal] and enriches
     * the result with [device_role], the echoed [GoalExecutionPayload.executor_target_type],
     * the echoed [GoalExecutionPayload.continuity_token] / [GoalExecutionPayload.is_resumable],
     * the echoed [GoalExecutionPayload.dispatch_trace_id] for full-chain V2 observability,
     * the echoed [GoalExecutionPayload.dispatch_plan_id] for dispatch plan correlation,
     * the echoed [GoalExecutionPayload.policy_routing_outcome] for policy layer correlation,
     * and [GoalResultPayload.is_continuation] set to `true` when the outcome is
     * [PolicyRoutingContext.RoutingOutcome.RESUMED] (PR-51/PR-5B continuation-aware behavior).
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
        // PR-49/PR-I: log and gate on policy routing outcome; reject only when explicitly rejected.
        logPolicyRoutingContext("goal_execution", payload)
        if (PolicyRoutingContext.isPolicyRejection(payload.policy_routing_outcome)) {
            Log.i(
                TAG,
                "goal_execution blocked: policy_routing_outcome=rejected " +
                    "policy_failure_reason=${payload.policy_failure_reason} " +
                    "task_id=${payload.task_id}"
            )
            // PR-51/PR-5B: structured rejection result — policy_rejection_reason carries
            // the machine-readable reason so V2 can re-route without treating this as an error.
            return buildDisabledResult(
                payload,
                REASON_POLICY_REJECTED,
                policyRejectionReason = payload.policy_failure_reason
            )
        }
        // PR-51/PR-5B: hold/pending semantics — TEMPORARILY_UNAVAILABLE is a non-terminal hold.
        if (PolicyRoutingContext.isTemporaryHold(payload.policy_routing_outcome)) {
            Log.i(
                TAG,
                "goal_execution hold: policy_routing_outcome=temporarily_unavailable " +
                    "policy_failure_reason=${payload.policy_failure_reason} " +
                    "task_id=${payload.task_id}"
            )
            return buildHoldResult(payload, REASON_POLICY_HOLD)
        }
        Log.i(TAG, "goal_execution executing locally; task_id=${payload.task_id}")
        // PR-51/PR-5B: continuation-aware behavior — set is_continuation when RESUMED.
        val isContinuation = PolicyRoutingContext.isResumedExecution(payload.policy_routing_outcome)
        return goalExecutor.executeGoal(payload)
            .copy(
                device_role = deviceRole,
                executor_target_type = payload.executor_target_type,
                continuity_token = payload.continuity_token,
                is_resumable = payload.is_resumable,
                dispatch_trace_id = payload.dispatch_trace_id,
                dispatch_plan_id = payload.dispatch_plan_id,
                policy_routing_outcome = payload.policy_routing_outcome,
                // PR-51/PR-5B: signal continuation so V2 knows this is a resumed execution.
                is_continuation = if (isContinuation) true else null
            )
    }

    /**
     * Handles a [parallel_subtask] message.
     *
     * Nine gates are evaluated in order:
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
     * 8. **Policy routing context** (PR-49/PR-I): logs policy routing outcome fields when
     *    present; returns [STATUS_DISABLED] with [REASON_POLICY_REJECTED] and structured
     *    [GoalResultPayload.policy_rejection_reason] when the outcome is explicitly
     *    [PolicyRoutingContext.RoutingOutcome.REJECTED]; tolerates `null` /
     *    absent values (legacy senders) without failure.
     * 9. **Temporary hold gate** (PR-51/PR-5B): returns [STATUS_PENDING] with
     *    [REASON_POLICY_HOLD] and [GoalResultPayload.is_hold_pending] set to `true`
     *    when the outcome is [PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE].
     *
     * When all gates pass, delegates to [LocalCollaborationAgent.handleParallelSubtask]
     * and enriches the result with [device_role], the echoed
     * [GoalExecutionPayload.executor_target_type], the echoed continuity/recovery fields,
     * the echoed [GoalExecutionPayload.dispatch_trace_id] for full-chain V2 observability,
     * the echoed [GoalExecutionPayload.dispatch_plan_id] for dispatch plan correlation,
     * the echoed [GoalExecutionPayload.policy_routing_outcome] for policy layer correlation,
     * and [GoalResultPayload.is_continuation] set to `true` when the outcome is
     * [PolicyRoutingContext.RoutingOutcome.RESUMED] (PR-51/PR-5B continuation-aware behavior).
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
        // PR-49/PR-I: log and gate on policy routing outcome; reject only when explicitly rejected.
        logPolicyRoutingContext("parallel_subtask", payload)
        if (PolicyRoutingContext.isPolicyRejection(payload.policy_routing_outcome)) {
            Log.i(
                TAG,
                "parallel_subtask blocked: policy_routing_outcome=rejected " +
                    "policy_failure_reason=${payload.policy_failure_reason} " +
                    "task_id=${payload.task_id}"
            )
            // PR-51/PR-5B: structured rejection result.
            return buildDisabledResult(
                payload,
                REASON_POLICY_REJECTED,
                policyRejectionReason = payload.policy_failure_reason
            )
        }
        // PR-51/PR-5B: hold/pending semantics — TEMPORARILY_UNAVAILABLE is a non-terminal hold.
        if (PolicyRoutingContext.isTemporaryHold(payload.policy_routing_outcome)) {
            Log.i(
                TAG,
                "parallel_subtask hold: policy_routing_outcome=temporarily_unavailable " +
                    "policy_failure_reason=${payload.policy_failure_reason} " +
                    "task_id=${payload.task_id}"
            )
            return buildHoldResult(payload, REASON_POLICY_HOLD)
        }
        Log.i(TAG, "parallel_subtask executing locally; task_id=${payload.task_id}")
        // PR-51/PR-5B: continuation-aware behavior — set is_continuation when RESUMED.
        val isContinuation = PolicyRoutingContext.isResumedExecution(payload.policy_routing_outcome)
        return collaborationAgent.handleParallelSubtask(payload)
            .copy(
                device_role = deviceRole,
                executor_target_type = payload.executor_target_type,
                continuity_token = payload.continuity_token,
                is_resumable = payload.is_resumable,
                dispatch_trace_id = payload.dispatch_trace_id,
                dispatch_plan_id = payload.dispatch_plan_id,
                policy_routing_outcome = payload.policy_routing_outcome,
                // PR-51/PR-5B: signal continuation so V2 knows this is a resumed execution.
                is_continuation = if (isContinuation) true else null
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

    /**
     * Logs PR-49/PR-I policy routing context fields for observability.
     *
     * A no-op when all policy routing fields are null/blank, so it imposes no
     * overhead on legacy (pre-PR-I) senders.
     */
    private fun logPolicyRoutingContext(msgType: String, payload: GoalExecutionPayload) {
        val hasPolicyRouting = !payload.policy_routing_outcome.isNullOrBlank()
            || !payload.policy_failure_reason.isNullOrBlank()
            || !payload.readiness_degradation_hint.isNullOrBlank()
        if (!hasPolicyRouting) return

        val outcome = PolicyRoutingContext.RoutingOutcome.fromValue(payload.policy_routing_outcome)
        val knownFailureReason = PolicyRoutingContext.isKnownFailureReason(payload.policy_failure_reason)
        val knownDegradationReason = PolicyRoutingContext.isKnownDegradationReason(payload.readiness_degradation_hint)
        Log.i(
            TAG,
            "$msgType policy_routing_outcome=${payload.policy_routing_outcome} " +
                "canonical_outcome=$outcome " +
                "policy_failure_reason=${payload.policy_failure_reason} " +
                "known_failure_reason=$knownFailureReason " +
                "readiness_degradation_hint=${payload.readiness_degradation_hint} " +
                "known_degradation_reason=$knownDegradationReason " +
                "task_id=${payload.task_id}"
        )
    }

    private fun buildDisabledResult(
        payload: GoalExecutionPayload,
        reason: String,
        policyRejectionReason: String? = null
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
        dispatch_plan_id = payload.dispatch_plan_id,
        // PR-49/PR-I: echo policy_routing_outcome in disabled results for policy layer correlation
        policy_routing_outcome = payload.policy_routing_outcome,
        // PR-51/PR-5B: structured rejection reason for REJECTED outcomes
        policy_rejection_reason = policyRejectionReason
    )

    /**
     * Builds a [STATUS_PENDING] hold result for [PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE].
     *
     * The result carries [GoalResultPayload.is_hold_pending] = `true` so V2 can unambiguously
     * distinguish a temporary hold from a terminal failure and retry dispatch when readiness
     * is restored.  All correlation fields (continuity_token, dispatch_trace_id, etc.) are
     * echoed for full-chain observability.
     */
    private fun buildHoldResult(
        payload: GoalExecutionPayload,
        reason: String
    ) = GoalResultPayload(
        task_id = payload.task_id,
        correlation_id = payload.task_id,
        status = STATUS_PENDING,
        error = reason,
        group_id = payload.group_id,
        subtask_index = payload.subtask_index,
        latency_ms = 0L,
        device_id = deviceId,
        device_role = deviceRole,
        executor_target_type = payload.executor_target_type,
        // Echo continuity/recovery fields so V2 can associate hold with the correct context.
        continuity_token = payload.continuity_token,
        is_resumable = payload.is_resumable,
        // Echo observability fields for full-chain tracing.
        dispatch_trace_id = payload.dispatch_trace_id,
        dispatch_plan_id = payload.dispatch_plan_id,
        policy_routing_outcome = payload.policy_routing_outcome,
        // PR-51/PR-5B: unambiguous hold signal for TEMPORARILY_UNAVAILABLE outcomes.
        is_hold_pending = true
    )
}
