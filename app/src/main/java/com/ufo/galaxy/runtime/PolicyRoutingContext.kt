package com.ufo.galaxy.runtime

/**
 * PR-I (PR-49) — Android-side policy-driven routing outcome compatibility model.
 *
 * V2 is introducing a policy layer that governs target selection and failure handling
 * across Android device, node service, go worker, and local execution paths.  This
 * object declares the Android-side vocabulary and helpers that allow the device runtime
 * to remain a predictable participant in policy-driven multi-device execution without
 * becoming a policy decision-maker itself.
 *
 * ## Scope
 *
 * [PolicyRoutingContext] is a **compatibility and semantic clarity** layer, not a
 * policy engine.  Android-side responsibilities are:
 *
 * 1. **Accept** policy routing outcome metadata in inbound execution contracts
 *    without failing.
 * 2. **Classify** the policy layer's routing decision so Android can apply the
 *    correct local response (proceed, hold, degrade gracefully, or surface
 *    resumable vs. terminal state to V2).
 * 3. **Distinguish** degraded readiness, temporary unavailability, and resumable
 *    interruption from terminal failure so that V2 does not misclassify a
 *    recoverable Android-side scenario as a permanent routing failure.
 * 4. **Remain backward compatible** — all policy routing fields are optional;
 *    null/absent values must be treated as the legacy contract (policy layer
 *    not active or not yet sending these fields).
 *
 * ## RoutingOutcome semantics
 *
 * | [RoutingOutcome]                         | Android interpretation                                           |
 * |------------------------------------------|------------------------------------------------------------------|
 * | [RoutingOutcome.ACCEPTED]                | Policy accepted this device as the target; execute normally.     |
 * | [RoutingOutcome.DEGRADED_READINESS]      | Policy selected this device despite degraded readiness; Android  |
 * |                                          | MUST NOT escalate this to terminal failure — execution should    |
 * |                                          | proceed in degraded mode where possible.                         |
 * | [RoutingOutcome.TEMPORARILY_UNAVAILABLE] | Policy could not route because this device is temporarily        |
 * |                                          | unavailable; Android must surface this as a [STATUS_PENDING]     |
 * |                                          | hold result with [GoalResultPayload.is_hold_pending] = `true`,   |
 * |                                          | not a terminal error, so V2 can retry when readiness is restored.|
 * | [RoutingOutcome.RESUMED]                 | Execution is resuming a previously interrupted task under policy |
 * |                                          | control; Android MUST NOT reset task state and must treat the    |
 * |                                          | inbound envelope as a continuation.  The result carries          |
 * |                                          | [GoalResultPayload.is_continuation] = `true` so V2 knows this   |
 * |                                          | was a continuation, not a fresh dispatch.                        |
 * | [RoutingOutcome.REJECTED]                | Policy rejected this device as a target; Android must not execute|
 * |                                          | the task and must return a structured disabled result carrying    |
 * |                                          | [GoalResultPayload.policy_rejection_reason] from the payload's   |
 * |                                          | [GoalExecutionPayload.policy_failure_reason].                    |
 *
 * ## Degradation and unavailability reason constants
 *
 * [DEGRADATION_REASON_*] and [UNAVAILABILITY_REASON_*] constants carry recognised
 * machine-readable reason strings for [GoalExecutionPayload.readiness_degradation_hint]
 * and [GoalExecutionPayload.policy_failure_reason].  Unknown values are always tolerated;
 * callers must NOT reject envelopes that carry unrecognised reasons.
 *
 * @see GoalExecutionPayload
 * @see ExecutionContractCompatibilityValidator
 * @see ContinuityRecoveryContext
 */
object PolicyRoutingContext {

    // ── RoutingOutcome — wire-value enum ──────────────────────────────────────

    /**
     * Policy-driven routing outcome classification.
     *
     * Carries the V2 policy layer's decision for routing this execution to this
     * Android device.  Android uses the outcome to apply the correct local
     * adaptation without needing to re-evaluate policy conditions itself.
     *
     * @property wireValue Stable lowercase-snake-case string used in execution payloads.
     */
    enum class RoutingOutcome(val wireValue: String) {

        /**
         * Policy accepted this Android device as the execution target.
         * Android should proceed with normal execution.
         */
        ACCEPTED("accepted"),

        /**
         * Policy selected this device despite degraded readiness (e.g. model is loaded
         * but slow, accessibility service is unreliable, or health is [ParticipantHealthState.DEGRADED]).
         *
         * Android MUST NOT escalate this to terminal failure.  Execution should proceed
         * in degraded mode; the result should reflect degraded quality where applicable.
         */
        DEGRADED_READINESS("degraded_readiness"),

        /**
         * Policy determined that this device is temporarily unavailable for new tasks.
         *
         * Examples: the device is in [MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED],
         * health is [ParticipantHealthState.RECOVERING], or the transport is reconnecting.
         *
         * Android MUST surface this as a non-terminal hold, NOT a terminal error, so that V2
         * can retry dispatch when readiness is restored.
         */
        TEMPORARILY_UNAVAILABLE("temporarily_unavailable"),

        /**
         * Execution is resuming a previously interrupted task under policy control.
         *
         * Android MUST NOT reset accumulated task state.  The inbound envelope must be
         * treated as a continuation of the prior interrupted execution, not a fresh dispatch.
         * Typically accompanied by [GoalExecutionPayload.continuity_token] and
         * [GoalExecutionPayload.is_resumable] == `true`.
         */
        RESUMED("resumed"),

        /**
         * Policy rejected this device as a target for this execution.
         *
         * Android must not execute the task.  A structured disabled result should be
         * returned so V2 can re-route to a suitable target without treating the outcome
         * as an Android-side error.
         */
        REJECTED("rejected");

        companion object {

            /**
             * Returns the [RoutingOutcome] whose [wireValue] equals [value], or `null`
             * for unknown / absent values.
             *
             * Unknown values MUST be tolerated; callers must not reject payloads that
             * carry future policy routing outcome values.
             *
             * @param value Raw [policy_routing_outcome] string from an inbound payload.
             */
            fun fromValue(value: String?): RoutingOutcome? =
                entries.firstOrNull { it.wireValue == value }

            /**
             * Set of all canonical [RoutingOutcome.wireValue] strings.
             *
             * Useful for test-time validation and schema registries.
             */
            val ALL_VALUES: Set<String> = entries.map { it.wireValue }.toSet()

            /**
             * Routing outcome values under which Android should proceed with execution
             * (as opposed to holding or returning a disabled result).
             *
             * Includes [ACCEPTED] and [DEGRADED_READINESS] (execution continues in
             * degraded mode) and [RESUMED] (execution continues as a resumption).
             * Excludes [TEMPORARILY_UNAVAILABLE] (hold, not proceed) and [REJECTED]
             * (disabled result, not execute).
             */
            val PROCEED_OUTCOMES: Set<String> =
                setOf(ACCEPTED.wireValue, DEGRADED_READINESS.wireValue, RESUMED.wireValue)
        }
    }

    // ── Degradation hint constants ─────────────────────────────────────────────

    /**
     * [readiness_degradation_hint] value indicating that the model subsystem is
     * operating at reduced quality (e.g. slow inference, lower-confidence outputs).
     */
    const val DEGRADATION_REASON_MODEL_DEGRADED = "model_degraded"

    /**
     * [readiness_degradation_hint] value indicating that the accessibility service
     * is available but operating unreliably (e.g. intermittent failures, slow response).
     */
    const val DEGRADATION_REASON_ACCESSIBILITY_UNRELIABLE = "accessibility_unreliable"

    /**
     * [readiness_degradation_hint] value indicating transport quality has deteriorated
     * (elevated latency, error rate, or throughput reduction) but the session is still live.
     *
     * Corresponds to [MediaTransportLifecycleBridge.TransportCondition.DEGRADED].
     */
    const val DEGRADATION_REASON_TRANSPORT_QUALITY = "transport_quality"

    /**
     * [readiness_degradation_hint] value indicating that a non-critical subsystem
     * is absent or operating in fallback mode (e.g. rule-based planning instead of
     * model-based planning).
     */
    const val DEGRADATION_REASON_SUBSYSTEM_FALLBACK = "subsystem_fallback"

    /**
     * Set of all recognised [readiness_degradation_hint] wire values.
     *
     * Callers MUST tolerate values outside this set; future V2 policy versions may
     * introduce new degradation reason values.
     */
    val KNOWN_DEGRADATION_REASONS: Set<String> = setOf(
        DEGRADATION_REASON_MODEL_DEGRADED,
        DEGRADATION_REASON_ACCESSIBILITY_UNRELIABLE,
        DEGRADATION_REASON_TRANSPORT_QUALITY,
        DEGRADATION_REASON_SUBSYSTEM_FALLBACK
    )

    // ── Unavailability / failure reason constants ─────────────────────────────

    /**
     * [policy_failure_reason] value indicating that the device is in an active
     * reconnect cycle; transport is [MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED].
     */
    const val UNAVAILABILITY_REASON_RECONNECTING = "reconnecting"

    /**
     * [policy_failure_reason] value indicating that the device's execution environment
     * health is [ParticipantHealthState.RECOVERING] — a prior failure is being healed.
     */
    const val UNAVAILABILITY_REASON_HEALTH_RECOVERING = "health_recovering"

    /**
     * [policy_failure_reason] value indicating that the cross-device session has not
     * yet attached; [AttachedRuntimeHostSessionSnapshot.attachmentState] is not `"attached"`.
     */
    const val UNAVAILABILITY_REASON_NOT_ATTACHED = "not_attached"

    /**
     * [policy_failure_reason] value indicating that the device's readiness projection
     * carries a [DelegatedTargetReadinessProjection.TargetSelectionOutcome.REJECTED] outcome.
     */
    const val UNAVAILABILITY_REASON_TARGET_REJECTED = "target_rejected"

    /**
     * Set of all recognised [policy_failure_reason] wire values.
     *
     * Callers MUST tolerate values outside this set; future V2 policy versions may
     * introduce new failure reason values.
     */
    val KNOWN_FAILURE_REASONS: Set<String> = setOf(
        UNAVAILABILITY_REASON_RECONNECTING,
        UNAVAILABILITY_REASON_HEALTH_RECOVERING,
        UNAVAILABILITY_REASON_NOT_ATTACHED,
        UNAVAILABILITY_REASON_TARGET_REJECTED
    )

    // ── Routing outcome helpers ───────────────────────────────────────────────

    /**
     * Returns `true` when [outcome] indicates Android should proceed with execution.
     *
     * Specifically returns `true` for [RoutingOutcome.ACCEPTED], [RoutingOutcome.DEGRADED_READINESS],
     * and [RoutingOutcome.RESUMED].
     *
     * A `null` outcome (legacy / unspecified) returns `true` for forward compatibility —
     * absence of a policy routing outcome must not block execution on legacy paths.
     *
     * @param outcome Raw [policy_routing_outcome] string from an inbound payload (or `null`).
     */
    fun shouldProceed(outcome: String?): Boolean =
        outcome == null || outcome in RoutingOutcome.PROCEED_OUTCOMES

    /**
     * Returns `true` when [outcome] unambiguously signals a temporary hold —
     * i.e. [RoutingOutcome.TEMPORARILY_UNAVAILABLE].
     *
     * A `null` outcome (legacy / unspecified) does NOT count as a hold; the legacy
     * default is to proceed.
     *
     * @param outcome Raw [policy_routing_outcome] string from an inbound payload (or `null`).
     */
    fun isTemporaryHold(outcome: String?): Boolean =
        outcome == RoutingOutcome.TEMPORARILY_UNAVAILABLE.wireValue

    /**
     * Returns `true` when [outcome] signals a policy rejection — i.e.
     * [RoutingOutcome.REJECTED].
     *
     * A `null` outcome (legacy / unspecified) does NOT count as a rejection.
     *
     * @param outcome Raw [policy_routing_outcome] string from an inbound payload (or `null`).
     */
    fun isPolicyRejection(outcome: String?): Boolean =
        outcome == RoutingOutcome.REJECTED.wireValue

    /**
     * Returns `true` when [outcome] indicates a resumption continuation —
     * i.e. [RoutingOutcome.RESUMED].
     *
     * @param outcome Raw [policy_routing_outcome] string from an inbound payload (or `null`).
     */
    fun isResumedExecution(outcome: String?): Boolean =
        outcome == RoutingOutcome.RESUMED.wireValue

    /**
     * Returns `true` when [outcome] signals degraded readiness — i.e.
     * [RoutingOutcome.DEGRADED_READINESS].
     *
     * @param outcome Raw [policy_routing_outcome] string from an inbound payload (or `null`).
     */
    fun isDegradedReadiness(outcome: String?): Boolean =
        outcome == RoutingOutcome.DEGRADED_READINESS.wireValue

    /**
     * Returns `true` when [reason] is a recognised [readiness_degradation_hint] value.
     *
     * Unknown values always return `false`; they must still be accepted without failure.
     *
     * @param reason Raw [readiness_degradation_hint] string from the inbound envelope.
     */
    fun isKnownDegradationReason(reason: String?): Boolean =
        reason != null && KNOWN_DEGRADATION_REASONS.contains(reason)

    /**
     * Returns `true` when [reason] is a recognised [policy_failure_reason] value.
     *
     * Unknown values always return `false`; they must still be accepted without failure.
     *
     * @param reason Raw [policy_failure_reason] string from the inbound envelope.
     */
    fun isKnownFailureReason(reason: String?): Boolean =
        reason != null && KNOWN_FAILURE_REASONS.contains(reason)

    // ── PR number / introduction tracking ────────────────────────────────────

    /** The PR number that introduced this policy routing context vocabulary. */
    const val INTRODUCED_PR: Int = 49
}
