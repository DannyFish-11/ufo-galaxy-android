package com.ufo.galaxy.runtime

/**
 * PR-7 (Android companion) — Capability honesty guard under multi-device orchestration.
 *
 * Enforces that Android participant capability reports are **honest and consistent** with
 * actual runtime state in every orchestration state.  During multi-device orchestrated
 * sessions (PR-7 joint harness), the V2 coordinator makes routing and dispatch decisions
 * based on capability reports; if Android claims capabilities it cannot actually deliver
 * (false positives), V2 may route work to an unavailable participant, causing joint
 * integration test failures and, in production, user-facing errors.
 *
 * ## Problem addressed
 *
 * Before this class, capability advertisement was driven by a single check at startup
 * (whether local inference started successfully).  Under multi-device orchestration:
 *  - A participant that transitions to [ParticipantHealthState.DEGRADED] may still be
 *    advertising `local_model_inference` — a capability it can no longer reliably deliver.
 *  - A participant that is [ReconnectRecoveryState.RECOVERING] may briefly re-advertise
 *    its full capability set before a new session health check completes.
 *  - A capability-limited participant ([LocalIntelligenceCapabilityStatus.DEGRADED]) may
 *    report the same capability list as a fully healthy participant.
 *
 * [CapabilityHonestyGuard] closes this gap by providing:
 *  - A [CapabilityReport] value type that includes the orchestration state at the time
 *    of the report, making the source conditions auditable.
 *  - [computeAllowedCapabilities]: derives the correct capability list for any
 *    ([OrchestrationState] × [LocalIntelligenceCapabilityStatus]) combination.
 *  - [isHonest]: validates that a given [CapabilityReport] does not claim capabilities
 *    that are not available under the reported [OrchestrationState].
 *  - [HonestyViolation]: a structured description of any dishonest capability claim,
 *    enabling the PR-7 joint harness to produce precise failure messages.
 *
 * ## Capability honesty contract
 *
 * | Orchestration state | Allowed capabilities |
 * |---------------------|----------------------|
 * | CONNECTED + inference ACTIVE    | All capabilities including `local_model_inference` |
 * | CONNECTED + inference DEGRADED  | Base capabilities only (no inference) |
 * | CONNECTED + inference DISABLED/UNAVAILABLE/RECOVERING | Base capabilities only |
 * | DEGRADED                         | Base capabilities only (no inference) |
 * | CAPABILITY_LIMITED               | Base capabilities only (no inference) |
 * | RECOVERING                       | No capabilities advertised (not ready) |
 * | RECONNECTING                     | No capabilities advertised (not ready) |
 * | DISCONNECTED                     | No capabilities advertised (offline) |
 *
 * @see MultiDeviceParticipantOrchestrationState
 * @see LocalIntelligenceCapabilityStatus
 */
object CapabilityHonestyGuard {

    // ── Base and inference capability constants ───────────────────────────────

    /**
     * Capabilities that are always available when the participant is in
     * [MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED] and the
     * base execution subsystem (accessibility + overlay) is ready.
     */
    val BASE_CAPABILITIES: Set<String> = setOf(
        "autonomous_goal_execution",
        "local_task_planning",
        "local_ui_reasoning",
        "cross_device_coordination"
    )

    /**
     * The inference capability that is conditionally available, only when
     * [LocalIntelligenceCapabilityStatus] is [LocalIntelligenceCapabilityStatus.ACTIVE]
     * **and** the orchestration state is [MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED].
     */
    const val CAPABILITY_LOCAL_MODEL_INFERENCE: String = "local_model_inference"

    // ── Capability report ─────────────────────────────────────────────────────

    /**
     * A capability advertisement report with its source orchestration context.
     *
     * @property advertisedCapabilities  The capability strings being advertised.
     * @property orchestrationState      The participant's orchestration state at advertisement time.
     * @property inferenceStatus         The local intelligence capability status at advertisement time.
     * @property reportedAtMs            Epoch-millisecond timestamp of this report.
     */
    data class CapabilityReport(
        val advertisedCapabilities: Set<String>,
        val orchestrationState: MultiDeviceParticipantOrchestrationState.OrchestrationState,
        val inferenceStatus: LocalIntelligenceCapabilityStatus,
        val reportedAtMs: Long = System.currentTimeMillis()
    )

    // ── Honesty violation ─────────────────────────────────────────────────────

    /**
     * Describes a capability honesty violation found by [isHonest].
     *
     * @property violatingCapability  The capability that should not have been advertised.
     * @property orchestrationState   The orchestration state under which it was advertised.
     * @property inferenceStatus      The inference status under which it was advertised.
     * @property reason               Human-readable explanation of why this is a violation.
     */
    data class HonestyViolation(
        val violatingCapability: String,
        val orchestrationState: MultiDeviceParticipantOrchestrationState.OrchestrationState,
        val inferenceStatus: LocalIntelligenceCapabilityStatus,
        val reason: String
    )

    // ── Core logic ────────────────────────────────────────────────────────────

    /**
     * Computes the correct, honest capability set for the given orchestration state and
     * local intelligence capability status.
     *
     * This is the authoritative source for what capabilities Android may advertise to V2
     * under any multi-device orchestration condition.
     *
     * @param orchestrationState The current [MultiDeviceParticipantOrchestrationState.OrchestrationState].
     * @param inferenceStatus    The current [LocalIntelligenceCapabilityStatus].
     * @return The set of capabilities that may honestly be advertised.
     */
    fun computeAllowedCapabilities(
        orchestrationState: MultiDeviceParticipantOrchestrationState.OrchestrationState,
        inferenceStatus: LocalIntelligenceCapabilityStatus
    ): Set<String> = when (orchestrationState) {
        MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED -> {
            if (inferenceStatus == LocalIntelligenceCapabilityStatus.ACTIVE) {
                BASE_CAPABILITIES + CAPABILITY_LOCAL_MODEL_INFERENCE
            } else {
                BASE_CAPABILITIES
            }
        }
        MultiDeviceParticipantOrchestrationState.OrchestrationState.DEGRADED,
        MultiDeviceParticipantOrchestrationState.OrchestrationState.CAPABILITY_LIMITED ->
            BASE_CAPABILITIES
        MultiDeviceParticipantOrchestrationState.OrchestrationState.RECOVERING,
        MultiDeviceParticipantOrchestrationState.OrchestrationState.RECONNECTING,
        MultiDeviceParticipantOrchestrationState.OrchestrationState.DISCONNECTED ->
            emptySet()
    }

    /**
     * Validates that [report] does not advertise any capability that is not allowed under
     * its reported [CapabilityReport.orchestrationState] and [CapabilityReport.inferenceStatus].
     *
     * Returns an empty list if the report is fully honest.  Returns one [HonestyViolation]
     * per disallowed capability if the report is dishonest.
     *
     * @param report The [CapabilityReport] to validate.
     * @return List of [HonestyViolation] objects; empty when the report is honest.
     */
    fun isHonest(report: CapabilityReport): List<HonestyViolation> {
        val allowed = computeAllowedCapabilities(report.orchestrationState, report.inferenceStatus)
        return report.advertisedCapabilities
            .filter { cap -> cap !in allowed }
            .map { violating ->
                HonestyViolation(
                    violatingCapability = violating,
                    orchestrationState = report.orchestrationState,
                    inferenceStatus = report.inferenceStatus,
                    reason = buildViolationReason(violating, report.orchestrationState, report.inferenceStatus)
                )
            }
    }

    private fun buildViolationReason(
        capability: String,
        orchestrationState: MultiDeviceParticipantOrchestrationState.OrchestrationState,
        inferenceStatus: LocalIntelligenceCapabilityStatus
    ): String = when (orchestrationState) {
        MultiDeviceParticipantOrchestrationState.OrchestrationState.DISCONNECTED ->
            "Capability '$capability' must not be advertised while participant is DISCONNECTED."
        MultiDeviceParticipantOrchestrationState.OrchestrationState.RECONNECTING ->
            "Capability '$capability' must not be advertised while participant is RECONNECTING; " +
                "dispatch is blocked until WS reconnect succeeds."
        MultiDeviceParticipantOrchestrationState.OrchestrationState.RECOVERING ->
            "Capability '$capability' must not be advertised while participant is RECOVERING; " +
                "health restoration is in progress."
        MultiDeviceParticipantOrchestrationState.OrchestrationState.DEGRADED ->
            "Capability '$capability' must not be advertised in DEGRADED state; " +
                "only base capabilities are available."
        MultiDeviceParticipantOrchestrationState.OrchestrationState.CAPABILITY_LIMITED ->
            "Capability '$capability' must not be advertised in CAPABILITY_LIMITED state; " +
                "inference sub-system is not ready (status=$inferenceStatus)."
        MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED ->
            "Capability '$capability' is not allowed in CONNECTED state with " +
                "inferenceStatus=$inferenceStatus (only ACTIVE allows inference)."
    }

    // ── Capability summary helpers ────────────────────────────────────────────

    /**
     * Returns `true` when `local_model_inference` may be included in the capability
     * advertisement for the given state combination.
     */
    fun inferenceCapabilityAllowed(
        orchestrationState: MultiDeviceParticipantOrchestrationState.OrchestrationState,
        inferenceStatus: LocalIntelligenceCapabilityStatus
    ): Boolean =
        orchestrationState == MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED &&
            inferenceStatus == LocalIntelligenceCapabilityStatus.ACTIVE

    /**
     * Returns `true` when **any** capability advertisement is appropriate for the given
     * orchestration state (i.e. the participant is reachable and has at least base capability).
     */
    fun anyCapabilityAllowed(
        orchestrationState: MultiDeviceParticipantOrchestrationState.OrchestrationState
    ): Boolean = orchestrationState == MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED ||
        orchestrationState == MultiDeviceParticipantOrchestrationState.OrchestrationState.DEGRADED ||
        orchestrationState == MultiDeviceParticipantOrchestrationState.OrchestrationState.CAPABILITY_LIMITED

    // ── Invariant constants ───────────────────────────────────────────────────

    /** Number of base capabilities (excluding inference). */
    const val BASE_CAPABILITY_COUNT: Int = 4

    /** PR that introduced this surface. */
    const val INTRODUCED_PR: Int = 7

    /** Human-readable title for this surface. */
    const val INTRODUCED_PR_TITLE: String =
        "PR-7 Android companion: capability honesty guard under multi-device orchestration"
}
