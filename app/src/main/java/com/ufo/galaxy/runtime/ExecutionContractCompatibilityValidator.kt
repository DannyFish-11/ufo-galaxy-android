package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.GoalExecutionPayload

/**
 * PR-48 — Android-side contract compatibility validation for evolved multi-device
 * runtime execution metadata.
 *
 * V2 has expanded the runtime execution contract across four areas:
 *
 * 1. **Richer source dispatch metadata** — dispatch plan identifiers and source dispatch
 *    strategy hints that describe how V2 orchestrated this execution (PR-D alignment + PR-H).
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
 * ## Compatibility contract
 *
 * All evolved fields are optional and default to `null` / empty.  A legacy payload
 * (all fields absent) MUST be accepted without error.  A maximally evolved payload
 * (all four areas populated) MUST also be accepted without error.  The result of
 * [checkPayloadCompatibility] is always non-null and never throws.
 *
 * ## Richer dispatch metadata fields (PR-H)
 *
 * V2 now attaches dispatch plan metadata to Android-targeted execution envelopes,
 * allowing Android to correlate an inbound execution with the V2 orchestration plan
 * that triggered it.  The new fields are:
 *
 * | Field                    | Purpose                                                         |
 * |--------------------------|----------------------------------------------------------------|
 * | [GoalExecutionPayload.dispatch_plan_id]      | Stable identifier for the V2 source   |
 * |                          | dispatch plan that produced this command.  Echoed in results   |
 * |                          | for full-chain correlation.  `null` for legacy/pre-V2 senders. |
 * | [GoalExecutionPayload.source_dispatch_strategy] | Strategy hint from the V2 source   |
 * |                          | dispatch orchestrator.  Values defined in [DispatchStrategyHint].|
 * |                          | `null` for legacy/pre-V2 senders; unknown values are tolerated. |
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
     * The four evolved contract areas that Android-side compatibility validation
     * covers.
     */
    enum class CompatibilityArea {
        /**
         * Richer source dispatch metadata — [GoalExecutionPayload.dispatch_plan_id]
         * and [GoalExecutionPayload.source_dispatch_strategy] (PR-H).
         */
        DISPATCH_METADATA,

        /**
         * Explicit executor target typing — [GoalExecutionPayload.executor_target_type]
         * classified by [ExecutorTargetType] (PR-45 / PR-E).
         */
        EXECUTOR_TARGET_TYPING,

        /**
         * Continuity/recovery context — [GoalExecutionPayload.continuity_token],
         * [GoalExecutionPayload.is_resumable], [GoalExecutionPayload.interruption_reason],
         * [GoalExecutionPayload.recovery_context] (PR-46 / PR-F).
         */
        CONTINUITY_RECOVERY,

        /**
         * Observability/tracing identifiers — [GoalExecutionPayload.dispatch_trace_id]
         * and [GoalExecutionPayload.lifecycle_event_id] (PR-47 / PR-G).
         */
        OBSERVABILITY_TRACING
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
     * @param activeAreas               Set of [CompatibilityArea] values corresponding
     *                                  to active evolved contract areas in the payload.
     */
    data class CompatibilityCheckResult(
        val hasDispatchMetadata: Boolean,
        val hasExecutorTargetTyping: Boolean,
        val hasContinuityRecovery: Boolean,
        val hasObservabilityTracing: Boolean,
        val activeAreas: Set<CompatibilityArea>
    ) {
        /**
         * True when all four evolved contract areas are active in the payload.
         *
         * A maximally evolved payload carries richer dispatch metadata, explicit
         * executor target typing, continuity/recovery context, and observability
         * tracing identifiers simultaneously.
         */
        val isFullyEvolved: Boolean
            get() = hasDispatchMetadata
                && hasExecutorTargetTyping
                && hasContinuityRecovery
                && hasObservabilityTracing
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

        val active = buildSet {
            if (hasDispatch) add(CompatibilityArea.DISPATCH_METADATA)
            if (hasTargetTyping) add(CompatibilityArea.EXECUTOR_TARGET_TYPING)
            if (hasContinuity) add(CompatibilityArea.CONTINUITY_RECOVERY)
            if (hasObservability) add(CompatibilityArea.OBSERVABILITY_TRACING)
        }

        return CompatibilityCheckResult(
            hasDispatchMetadata = hasDispatch,
            hasExecutorTargetTyping = hasTargetTyping,
            hasContinuityRecovery = hasContinuity,
            hasObservabilityTracing = hasObservability,
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
     * tracking (legacy / pre-V2 sender).
     *
     * @param dispatchPlanId The [GoalExecutionPayload.dispatch_plan_id] value from
     *                       an inbound command.
     */
    fun hasDispatchPlanId(dispatchPlanId: String?): Boolean = !dispatchPlanId.isNullOrBlank()

    // ── PR number / introduction tracking ────────────────────────────────────

    /** The PR number that introduced this unified compatibility validator. */
    const val INTRODUCED_PR: Int = 48
}
