package com.ufo.galaxy.runtime

/**
 * Guard that validates Android participant capability claims are aligned with the
 * actual local runtime truth after recovery transitions.
 *
 * Closes the "claim vs. real state" gap that can occur when:
 *  1. A participant was advertising capabilities, then failed / degraded / entered recovery.
 *  2. Recovery completes but the outward capability advertisement is not re-aligned.
 *  3. The control plane continues routing based on a stale (too-high or too-low) capability set.
 *
 * ## Responsibilities
 *
 * | Responsibility | API |
 * |----------------|-----|
 * | Validate that advertised capabilities are consistent with [FormalParticipantLifecycleState] | [checkAlignment] |
 * | Determine if a capability advertisement is blocked for the current state | [isAdvertisementBlocked] |
 * | Determine if inference capability may be included | [inferenceCapabilityPermitted] |
 * | Produce structured [AlignmentViolation] records for protocol-level auditing | [checkAlignment] |
 * | Determine if re-registration is required after a state transition | [requiresPostRecoveryReRegistration] |
 *
 * ## Usage pattern
 *
 * ```kotlin
 * val guard = RecoveryCapabilityAlignmentGuard
 * val result = guard.checkAlignment(
 *     lifecycleState = FormalParticipantLifecycleState.READY,
 *     advertisedCapabilities = setOf("autonomous_goal_execution", "local_model_inference"),
 *     inferenceAvailable = true
 * )
 * if (!result.isAligned) {
 *     result.violations.forEach { /* log / block emission */ }
 * }
 * ```
 *
 * @see FormalParticipantLifecycleState
 * @see CapabilityReadvertiseContract
 * @see CapabilityHonestyGuard
 */
object RecoveryCapabilityAlignmentGuard {

    // ── Alignment check result ────────────────────────────────────────────────

    /**
     * Result of an [checkAlignment] call.
     *
     * @property isAligned              `true` when all advertised capabilities are permitted.
     * @property lifecycleState         The [FormalParticipantLifecycleState] at check time.
     * @property advertisedCapabilities The capability set that was checked.
     * @property allowedCapabilities    The capability set that is permitted for this state.
     * @property violations             List of capabilities that are NOT permitted.  Empty on success.
     */
    data class AlignmentResult(
        val isAligned: Boolean,
        val lifecycleState: FormalParticipantLifecycleState,
        val advertisedCapabilities: Set<String>,
        val allowedCapabilities: Set<String>,
        val violations: List<AlignmentViolation>
    )

    // ── Alignment violation ───────────────────────────────────────────────────

    /**
     * Describes a single capability alignment violation.
     *
     * @property violatingCapability  The capability that should not have been advertised.
     * @property lifecycleState       The [FormalParticipantLifecycleState] under which it was claimed.
     * @property reason               Human-readable explanation of the violation.
     */
    data class AlignmentViolation(
        val violatingCapability: String,
        val lifecycleState: FormalParticipantLifecycleState,
        val reason: String
    )

    // ── Re-registration event ─────────────────────────────────────────────────

    /**
     * Describes a post-recovery re-registration requirement.
     *
     * @property previousState          The state before the recovery transition.
     * @property newState               The state after the recovery transition.
     * @property requiredCapabilities   The capability set to include in the new registration.
     * @property rationale              Why re-registration is required.
     */
    data class ReRegistrationRequirement(
        val previousState: FormalParticipantLifecycleState,
        val newState: FormalParticipantLifecycleState,
        val requiredCapabilities: Set<String>,
        val rationale: String
    )

    // ── Core alignment check ──────────────────────────────────────────────────

    /**
     * Validates that [advertisedCapabilities] is honest and consistent with [lifecycleState].
     *
     * Returns an [AlignmentResult] where [AlignmentResult.isAligned] is `true` only if
     * no disallowed capabilities are present.  All disallowed capabilities are reported
     * as [AlignmentViolation] records.
     *
     * @param lifecycleState          The current [FormalParticipantLifecycleState].
     * @param advertisedCapabilities  The capability set being advertised to the control plane.
     * @param inferenceAvailable      `true` when [LocalIntelligenceCapabilityStatus] is ACTIVE.
     * @return [AlignmentResult] describing whether the advertisement is aligned with runtime truth.
     */
    fun checkAlignment(
        lifecycleState: FormalParticipantLifecycleState,
        advertisedCapabilities: Set<String>,
        inferenceAvailable: Boolean
    ): AlignmentResult {
        val allowed = computeAllowedCapabilities(lifecycleState, inferenceAvailable)
        val violations = advertisedCapabilities
            .filter { cap -> cap !in allowed }
            .map { cap ->
                AlignmentViolation(
                    violatingCapability = cap,
                    lifecycleState = lifecycleState,
                    reason = buildViolationReason(cap, lifecycleState)
                )
            }
        return AlignmentResult(
            isAligned = violations.isEmpty(),
            lifecycleState = lifecycleState,
            advertisedCapabilities = advertisedCapabilities,
            allowedCapabilities = allowed,
            violations = violations
        )
    }

    // ── Allowed capability computation ────────────────────────────────────────

    /**
     * Computes the allowed capability set for the given [lifecycleState] and [inferenceAvailable].
     *
     * | FormalState         | inferenceAvailable | Allowed capabilities            |
     * |---------------------|--------------------|---------------------------------|
     * | READY               | true               | Base + local_model_inference    |
     * | READY               | false              | Base only                       |
     * | DEGRADED            | any                | Base only                       |
     * | STARTING            | any                | Empty set                       |
     * | RECOVERING          | any                | Empty set                       |
     * | UNAVAILABLE_FAILED  | any                | Empty set                       |
     */
    fun computeAllowedCapabilities(
        lifecycleState: FormalParticipantLifecycleState,
        inferenceAvailable: Boolean
    ): Set<String> = when (lifecycleState) {
        FormalParticipantLifecycleState.READY ->
            if (inferenceAvailable) {
                CapabilityHonestyGuard.BASE_CAPABILITIES +
                    CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE
            } else {
                CapabilityHonestyGuard.BASE_CAPABILITIES
            }

        FormalParticipantLifecycleState.DEGRADED ->
            CapabilityHonestyGuard.BASE_CAPABILITIES

        FormalParticipantLifecycleState.STARTING,
        FormalParticipantLifecycleState.RECOVERING,
        FormalParticipantLifecycleState.UNAVAILABLE_FAILED ->
            emptySet()
    }

    // ── Predicate helpers ─────────────────────────────────────────────────────

    /**
     * Returns `true` when the [lifecycleState] blocks all capability advertisement.
     * The control plane must not receive capability claims from a participant in a blocked state.
     */
    fun isAdvertisementBlocked(lifecycleState: FormalParticipantLifecycleState): Boolean =
        !FormalParticipantLifecycleState.capabilityAdvertisementAllowed(lifecycleState)

    /**
     * Returns `true` when inference capability (`local_model_inference`) may be included
     * in the capability advertisement for this [lifecycleState] and [inferenceAvailable].
     */
    fun inferenceCapabilityPermitted(
        lifecycleState: FormalParticipantLifecycleState,
        inferenceAvailable: Boolean
    ): Boolean = FormalParticipantLifecycleState.inferenceCapabilityAllowed(
        lifecycleState, inferenceAvailable
    )

    // ── Post-recovery re-registration ────────────────────────────────────────

    /**
     * Returns a [ReRegistrationRequirement] when transitioning from [previousState] to
     * [newState] requires post-recovery re-registration with the control plane, or `null`
     * when no re-registration is required.
     *
     * Re-registration is required when:
     *  - [previousState] blocked capability advertisement, AND
     *  - [newState] permits capability advertisement.
     *
     * @param previousState      The [FormalParticipantLifecycleState] before the transition.
     * @param newState           The [FormalParticipantLifecycleState] after the transition.
     * @param inferenceAvailable `true` when inference capability is active in [newState].
     * @return [ReRegistrationRequirement] if re-registration is needed, `null` otherwise.
     */
    fun requiresPostRecoveryReRegistration(
        previousState: FormalParticipantLifecycleState,
        newState: FormalParticipantLifecycleState,
        inferenceAvailable: Boolean
    ): ReRegistrationRequirement? {
        if (!FormalParticipantLifecycleState.requiresCapabilityReAdvertise(previousState, newState)) {
            return null
        }
        val capabilities = computeAllowedCapabilities(newState, inferenceAvailable)
        return ReRegistrationRequirement(
            previousState = previousState,
            newState = newState,
            requiredCapabilities = capabilities,
            rationale = "Participant recovered from $previousState to $newState; " +
                "control plane must receive updated capability_report with: $capabilities"
        )
    }

    // ── Violation reason builder ──────────────────────────────────────────────

    private fun buildViolationReason(
        capability: String,
        lifecycleState: FormalParticipantLifecycleState
    ): String = when (lifecycleState) {
        FormalParticipantLifecycleState.STARTING ->
            "Capability '$capability' must not be advertised in STARTING state; " +
                "runtime startup is in progress and no capability is yet verified."
        FormalParticipantLifecycleState.RECOVERING ->
            "Capability '$capability' must not be advertised in RECOVERING state; " +
                "health restoration is in progress and runtime state is unconfirmed."
        FormalParticipantLifecycleState.UNAVAILABLE_FAILED ->
            "Capability '$capability' must not be advertised in UNAVAILABLE_FAILED state; " +
                "the runtime is unavailable or has failed — no execution is possible."
        FormalParticipantLifecycleState.DEGRADED ->
            "Capability '$capability' is not available in DEGRADED state; " +
                "only base capabilities are permitted (inference subsystem is impaired)."
        FormalParticipantLifecycleState.READY ->
            "Capability '$capability' is not in the allowed capability set for READY state."
    }

    // ── Invariant constants ───────────────────────────────────────────────────

    /** PR that introduced this surface. */
    const val INTRODUCED_PR: Int = 14

    /** Human-readable surface name. */
    const val SURFACE_NAME: String =
        "RecoveryCapabilityAlignmentGuard: post-recovery capability honesty enforcement"
}
