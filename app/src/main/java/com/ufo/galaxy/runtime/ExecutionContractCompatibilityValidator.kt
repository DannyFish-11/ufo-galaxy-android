package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.HandoffEnvelopeV2
import com.ufo.galaxy.agent.TakeoverRequestEnvelope
import com.ufo.galaxy.protocol.GoalExecutionPayload

/**
 * PR-48 — Android-side contract compatibility validation for evolved multi-device
 * runtime execution metadata.
 *
 * V2 has expanded the runtime execution contract across four areas:
 *
 * 1. **Richer source dispatch metadata** — dispatch plan identifiers and source dispatch
 *    strategy hints that describe how V2 orchestrated this execution (PR-D alignment + PR-48).
 * 2. **Explicit executor target typing** — the [executor_target_type] field that
 *    unambiguously names the intended execution surface ([ExecutorTargetType], PR-45/PR-E).
 * 3. **Continuity/recovery context** — durable tokens, resumability flags, and
 *    interruption reasons for reconnect/handoff flows ([ContinuityRecoveryContext], PR-46/PR-F).
 * 4. **Observability/tracing identifiers** — dispatch trace IDs, lifecycle event IDs,
 *    and session correlation IDs ([RuntimeObservabilityMetadata], PR-47/PR-G).
 *
 * [ExecutionContractCompatibilityValidator] provides a unified, machine-readable
 * compatibility check across all four areas so that Android-side handlers can:
 *
 * - Safely accept inbound execution contracts regardless of which fields are present.
 * - Identify which evolved contract areas are active in a given payload.
 * - Remain backward-compatible with legacy/narrow contracts that predate V2's evolution.
 * - Encode compatibility expectations explicitly rather than relying on informal tolerance.
 *
 * ## Three dispatch entry paths
 *
 * The validator covers all three dispatch entry paths uniformly:
 * - **goal_execution** — [GoalExecutionPayload]: carries `dispatch_plan_id`,
 *   `source_dispatch_strategy`, `executor_target_type`, and all continuity/observability fields.
 * - **handoff** — [HandoffEnvelopeV2]: carries PR-D dispatch metadata plus `executor_target_type`,
 *   `dispatch_plan_id`, and `source_dispatch_strategy`.
 * - **takeover** — [TakeoverRequestEnvelope]: carries `executor_target_type`, `dispatch_plan_id`,
 *   and `source_dispatch_strategy` alongside continuity/recovery fields.
 *
 * All three paths use the same [CompatibilityCheckResult] structure for uniform interpretation.
 * Observability/tracing fields ([RuntimeObservabilityMetadata]) are only present on
 * [GoalExecutionPayload]; [HandoffEnvelopeV2] and [TakeoverRequestEnvelope] will always
 * produce `hasObservabilityTracing = false` and `hasPolicyRouting = false`.
 *
 * ## Compatibility contract
 *
 * All evolved fields are optional and default to `null` / empty.  A legacy payload
 * (all fields absent) MUST be accepted without error.  A maximally evolved payload
 * (all four areas populated) MUST also be accepted without error.  The result of
 * [checkPayloadCompatibility] is always non-null and never throws.
 *
 * ## Richer dispatch metadata fields (PR-48)
 *
 * V2 now attaches dispatch plan metadata to Android-targeted execution envelopes across
 * all three entry paths, allowing Android to correlate an inbound execution with the V2
 * orchestration plan that triggered it.  The fields are:
 *
 * | Field                       | Purpose                                                      |
 * |-----------------------------|--------------------------------------------------------------|
 * | `dispatch_plan_id`          | Stable identifier for the V2 source dispatch plan that       |
 * |                             | produced this command.  Echoed in results for full-chain     |
 * |                             | correlation.  `null` for legacy/pre-V2 senders.             |
 * | `source_dispatch_strategy`  | Strategy hint from the V2 source dispatch orchestrator.      |
 * |                             | Values defined in [DispatchStrategyHint].                    |
 * |                             | `null` for legacy/pre-V2 senders; unknown values tolerated.  |
 *
 * ## DispatchStrategyHint
 *
 * [DispatchStrategyHint] is the Android-side classification of the source dispatch
 * strategy used by V2 when routing this execution to Android.  The values mirror
 * the V2 orchestrator's strategy vocabulary:
 *
 * | Constant            | Wire value        | Meaning                                       |
 * |---------------------|-------------------|-----------------------------------------------|
 * | [DispatchStrategyHint.LOCAL]          | `"local"`         | Execution stays on this device. |
 * | [DispatchStrategyHint.REMOTE_HANDOFF] | `"remote_handoff"`| Originated remotely; handed off.|
 * | [DispatchStrategyHint.FALLBACK_LOCAL] | `"fallback_local"`| Fallback to local after remote  |
 * |                     |                   | path was unavailable.                         |
 * | [DispatchStrategyHint.STAGED_MESH]    | `"staged_mesh"`   | Part of a staged multi-device   |
 * |                     |                   | mesh execution plan.                          |
 *
 * Android devices MUST treat `null` and unknown values as equivalent to legacy
 * (unspecified) dispatch — they must not reject or block execution.
 *
 * @see ExecutorTargetType
 * @see ContinuityRecoveryContext
 * @see RuntimeObservabilityMetadata
 * @see GoalExecutionPayload
 * @see HandoffEnvelopeV2
 * @see TakeoverRequestEnvelope
 */
object ExecutionContractCompatibilityValidator {

    // ── DispatchStrategyHint — wire-value enum ─────────────────────────────────

    /**
     * Source dispatch strategy hint classification.
     *
     * Each value corresponds to a V2 source dispatch orchestrator strategy.  Android
     * handlers use this to understand how the V2 orchestrator decided to route this
     * execution to Android; the value does not alter Android's execution behaviour but
     * is preserved for observability and correlation.
     *
     * @property wireValue Stable lowercase-snake-case string used in execution payloads.
     */
    enum class DispatchStrategyHint(val wireValue: String) {

        /**
         * Execution is routed to the local device because it is the natural executor.
         * V2 selected this device as the primary target without requiring handoff
         * or fallback.
         */
        LOCAL("local"),

        /**
         * Execution originated on a remote device/service and was handed off to this
         * Android device as the target executor.  May be combined with continuity/
         * recovery metadata when the handoff is durable.
         */
        REMOTE_HANDOFF("remote_handoff"),

        /**
         * Execution falls back to this local device because the original remote target
         * was unavailable.  Android receives this execution as the fallback executor.
         */
        FALLBACK_LOCAL("fallback_local"),

        /**
         * Execution is part of a V2 staged mesh dispatch plan.  This device is one
         * participant in a multi-device coordinated execution.  Typically accompanied
         * by [GoalExecutionPayload.staged_mesh_id] and
         * [GoalExecutionPayload.staged_subtask_id].
         */
        STAGED_MESH("staged_mesh");

        companion object {

            /**
             * Returns the [DispatchStrategyHint] whose [wireValue] equals [value], or
             * `null` for unknown / absent values.
             *
             * Unknown values MUST be tolerated; callers must not reject payloads that
             * carry future strategy values.
             *
             * @param value Raw [source_dispatch_strategy] string from an inbound payload.
             */
            fun fromValue(value: String?): DispatchStrategyHint? =
                entries.firstOrNull { it.wireValue == value }

            /**
             * Set of all canonical [DispatchStrategyHint.wireValue] strings.
             *
             * Useful for test-time validation and schema registries.
             */
            val ALL_VALUES: Set<String> = entries.map { it.wireValue }.toSet()

            /**
             * Strategy hint values under which Android acts as an eligible executor.
             *
             * Includes `null` (unspecified / legacy) and all strategy values that route
             * execution to an Android device.  [REMOTE_HANDOFF] is excluded because it
             * is used when execution is being redirected _away from_ this device to a
             * remote service; Android may still receive such commands (forward-compat),
             * but the strategy classification itself signals non-Android routing.
             *
             * Note: at the compatibility layer Android always accepts the payload; this
             * set is provided for observability/logging purposes only.
             */
            val ANDROID_ELIGIBLE_VALUES: Set<String?> =
                setOf(null, LOCAL.wireValue, FALLBACK_LOCAL.wireValue, STAGED_MESH.wireValue)
        }
    }

    // ── CompatibilityArea ─────────────────────────────────────────────────────

    /**
     * The five evolved contract areas that Android-side compatibility validation covers,
     * uniformly applied across all three dispatch entry paths (goal_execution, handoff,
     * takeover).
     */
    enum class CompatibilityArea {
        /**
         * Richer source dispatch metadata — `dispatch_plan_id` and
         * `source_dispatch_strategy` (PR-48).  Present on all three paths.
         */
        DISPATCH_METADATA,

        /**
         * Explicit executor target typing — `executor_target_type`
         * classified by [ExecutorTargetType] (PR-45 / PR-E).  Present on all three paths.
         */
        EXECUTOR_TARGET_TYPING,

        /**
         * Continuity/recovery context — `continuity_token`,
         * `is_resumable`, `interruption_reason`, `recovery_context` (PR-46 / PR-F).
         * Present on goal_execution and takeover paths; not on handoff.
         */
        CONTINUITY_RECOVERY,

        /**
         * Observability/tracing identifiers — [GoalExecutionPayload.dispatch_trace_id]
         * and [GoalExecutionPayload.lifecycle_event_id] (PR-47 / PR-G).
         */
        OBSERVABILITY_TRACING,

        /**
         * Policy-driven routing outcome metadata — [GoalExecutionPayload.policy_routing_outcome],
         * [GoalExecutionPayload.policy_failure_reason], and
         * [GoalExecutionPayload.readiness_degradation_hint] (PR-49 / PR-I).
         */
        POLICY_ROUTING
    }

    // ── CompatibilityCheckResult ──────────────────────────────────────────────

    /**
     * Result of a compatibility check on an inbound execution payload.
     *
     * Each boolean field reflects whether the corresponding evolved contract area
     * is active (non-null / non-empty) in the inspected payload.  All flags are
     * false for a fully legacy payload; all flags may be true for a maximally
     * evolved payload.
     *
     * A payload is always accepted regardless of which flags are set.  The flags
     * serve observability, test assertions, and structured logging — not execution
     * gating.
     *
     * @param hasDispatchMetadata       True when the payload carries richer dispatch
     *                                  metadata ([dispatch_plan_id] or
     *                                  [source_dispatch_strategy]).
     * @param hasExecutorTargetTyping   True when [executor_target_type] is non-null
     *                                  (i.e. an explicit V2 target type was provided).
     * @param hasContinuityRecovery     True when any continuity/recovery field is
     *                                  populated ([continuity_token], [is_resumable],
     *                                  [interruption_reason], or non-empty
     *                                  [recovery_context]).
     * @param hasObservabilityTracing   True when any observability tracing field is
     *                                  non-null/non-blank ([dispatch_trace_id] or
     *                                  [lifecycle_event_id]).
     * @param hasPolicyRouting          True when any policy routing field is non-null/non-blank
     *                                  ([policy_routing_outcome], [policy_failure_reason], or
     *                                  [readiness_degradation_hint]).
     * @param activeAreas               Set of [CompatibilityArea] values corresponding
     *                                  to active evolved contract areas in the payload.
     */
    data class CompatibilityCheckResult(
        val hasDispatchMetadata: Boolean,
        val hasExecutorTargetTyping: Boolean,
        val hasContinuityRecovery: Boolean,
        val hasObservabilityTracing: Boolean,
        val hasPolicyRouting: Boolean,
        val activeAreas: Set<CompatibilityArea>
    ) {
        /**
         * True when all five evolved contract areas are active in the payload.
         *
         * A maximally evolved payload carries richer dispatch metadata, explicit
         * executor target typing, continuity/recovery context, observability
         * tracing identifiers, and policy routing outcome metadata simultaneously.
         */
        val isFullyEvolved: Boolean
            get() = hasDispatchMetadata
                && hasExecutorTargetTyping
                && hasContinuityRecovery
                && hasObservabilityTracing
                && hasPolicyRouting
    }

    // ── checkPayloadCompatibility ─────────────────────────────────────────────

    /**
     * Inspects [payload] and returns a [CompatibilityCheckResult] describing which
     * evolved contract areas are active.
     *
     * This method always returns successfully; it never throws or modifies the payload.
     * A legacy payload (all fields null / empty) returns a result where all flags are
     * `false`.
     *
     * @param payload The [GoalExecutionPayload] to inspect.
     * @return A [CompatibilityCheckResult] describing the active evolved contract areas.
     */
    fun checkPayloadCompatibility(payload: GoalExecutionPayload): CompatibilityCheckResult {
        val hasDispatch = !payload.dispatch_plan_id.isNullOrBlank()
            || !payload.source_dispatch_strategy.isNullOrBlank()
        val hasTargetTyping = payload.executor_target_type != null
        val hasContinuity = payload.continuity_token != null
            || payload.is_resumable != null
            || payload.interruption_reason != null
            || payload.recovery_context.isNotEmpty()
        val hasObservability = RuntimeObservabilityMetadata.hasDispatchTraceId(payload.dispatch_trace_id)
            || RuntimeObservabilityMetadata.hasLifecycleEventId(payload.lifecycle_event_id)
        val hasPolicyRouting = !payload.policy_routing_outcome.isNullOrBlank()
            || !payload.policy_failure_reason.isNullOrBlank()
            || !payload.readiness_degradation_hint.isNullOrBlank()

        val active = buildSet {
            if (hasDispatch) add(CompatibilityArea.DISPATCH_METADATA)
            if (hasTargetTyping) add(CompatibilityArea.EXECUTOR_TARGET_TYPING)
            if (hasContinuity) add(CompatibilityArea.CONTINUITY_RECOVERY)
            if (hasObservability) add(CompatibilityArea.OBSERVABILITY_TRACING)
            if (hasPolicyRouting) add(CompatibilityArea.POLICY_ROUTING)
        }

        return CompatibilityCheckResult(
            hasDispatchMetadata = hasDispatch,
            hasExecutorTargetTyping = hasTargetTyping,
            hasContinuityRecovery = hasContinuity,
            hasObservabilityTracing = hasObservability,
            hasPolicyRouting = hasPolicyRouting,
            activeAreas = active
        )
    }

    // ── DispatchStrategyHint helpers ──────────────────────────────────────────

    /**
     * Returns `true` when [strategyHint] identifies a dispatch strategy under which
     * this Android device is an eligible executor.
     *
     * Specifically, returns `true` for `null` (unspecified / legacy), [DispatchStrategyHint.LOCAL],
     * [DispatchStrategyHint.FALLBACK_LOCAL], and [DispatchStrategyHint.STAGED_MESH].
     * Returns `false` only for [DispatchStrategyHint.REMOTE_HANDOFF], which signals
     * that routing is directed away from a local device to a remote service.
     *
     * Unknown / future strategy hints return `true` by default for forward compatibility.
     *
     * @param strategyHint Raw [source_dispatch_strategy] string from the payload (or `null`).
     */
    fun isAndroidEligibleStrategy(strategyHint: String?): Boolean =
        strategyHint in DispatchStrategyHint.ANDROID_ELIGIBLE_VALUES
            || DispatchStrategyHint.fromValue(strategyHint) == null

    /**
     * Returns `true` when [dispatchPlanId] is a non-null, non-blank value that can
     * be used as a dispatch plan correlation identifier.
     *
     * A null or blank [dispatchPlanId] means the sender does not support dispatch plan
     * tracking (legacy / pre-V2 sender).  Applies uniformly to all three dispatch paths.
     *
     * @param dispatchPlanId The `dispatch_plan_id` value from any inbound command.
     */
    fun hasDispatchPlanId(dispatchPlanId: String?): Boolean = !dispatchPlanId.isNullOrBlank()

    // ── checkPayloadCompatibility (HandoffEnvelopeV2) ─────────────────────────

    /**
     * Inspects [envelope] and returns a [CompatibilityCheckResult] describing which
     * evolved contract areas are active on the **handoff** dispatch path.
     *
     * [HandoffEnvelopeV2] carries PR-D dispatch metadata, PR-E executor target typing,
     * PR-48 richer dispatch metadata, and PR-F continuity/recovery context.
     * Observability/tracing ([RuntimeObservabilityMetadata]) and policy routing fields
     * are not present on the handoff path; those flags will always be `false`.
     *
     * This method always returns successfully; it never throws or modifies the envelope.
     *
     * @param envelope The [HandoffEnvelopeV2] to inspect.
     * @return A [CompatibilityCheckResult] describing the active evolved contract areas.
     */
    fun checkPayloadCompatibility(envelope: HandoffEnvelopeV2): CompatibilityCheckResult {
        val hasDispatch = !envelope.dispatch_plan_id.isNullOrBlank()
            || !envelope.source_dispatch_strategy.isNullOrBlank()
        val hasTargetTyping = envelope.executor_target_type != null
        val hasContinuity = envelope.continuity_token != null
            || envelope.is_resumable != null
            || envelope.interruption_reason != null
            || envelope.recovery_context.isNotEmpty()

        val active = buildSet {
            if (hasDispatch) add(CompatibilityArea.DISPATCH_METADATA)
            if (hasTargetTyping) add(CompatibilityArea.EXECUTOR_TARGET_TYPING)
            if (hasContinuity) add(CompatibilityArea.CONTINUITY_RECOVERY)
        }

        return CompatibilityCheckResult(
            hasDispatchMetadata = hasDispatch,
            hasExecutorTargetTyping = hasTargetTyping,
            hasContinuityRecovery = hasContinuity,
            hasObservabilityTracing = false,
            hasPolicyRouting = false,
            activeAreas = active
        )
    }

    // ── checkPayloadCompatibility (TakeoverRequestEnvelope) ───────────────────

    /**
     * Inspects [envelope] and returns a [CompatibilityCheckResult] describing which
     * evolved contract areas are active on the **takeover** dispatch path.
     *
     * [TakeoverRequestEnvelope] carries PR-E executor target typing, PR-48 richer dispatch
     * metadata, and PR-F continuity/recovery context.  Observability/tracing
     * ([RuntimeObservabilityMetadata]) and policy routing fields are not present on the
     * takeover path; those flags will always be `false`.
     *
     * This method always returns successfully; it never throws or modifies the envelope.
     *
     * @param envelope The [TakeoverRequestEnvelope] to inspect.
     * @return A [CompatibilityCheckResult] describing the active evolved contract areas.
     */
    fun checkPayloadCompatibility(envelope: TakeoverRequestEnvelope): CompatibilityCheckResult {
        val hasDispatch = !envelope.dispatch_plan_id.isNullOrBlank()
            || !envelope.source_dispatch_strategy.isNullOrBlank()
        val hasTargetTyping = envelope.executor_target_type != null
        val hasContinuity = envelope.continuity_token != null
            || envelope.is_resumable != null
            || envelope.interruption_reason != null
            || envelope.recovery_context.isNotEmpty()

        val active = buildSet {
            if (hasDispatch) add(CompatibilityArea.DISPATCH_METADATA)
            if (hasTargetTyping) add(CompatibilityArea.EXECUTOR_TARGET_TYPING)
            if (hasContinuity) add(CompatibilityArea.CONTINUITY_RECOVERY)
        }

        return CompatibilityCheckResult(
            hasDispatchMetadata = hasDispatch,
            hasExecutorTargetTyping = hasTargetTyping,
            hasContinuityRecovery = hasContinuity,
            hasObservabilityTracing = false,
            hasPolicyRouting = false,
            activeAreas = active
        )
    }

    // ── PR number / introduction tracking ────────────────────────────────────

    /** The PR number that introduced this unified compatibility validator. */
    const val INTRODUCED_PR: Int = 48
}
