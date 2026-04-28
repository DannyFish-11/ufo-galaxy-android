package com.ufo.galaxy.runtime

/**
 * PR-69 — Formal expanded participant lifecycle truth state for Android runtime.
 *
 * Defines the complete set of lifecycle states that a participant transitions through from
 * first-time registration to active execution, capability degradation, recovery, and
 * re-registration.  This enum extends [FormalParticipantLifecycleState] (which collapses
 * the internal sub-state space for capability gating) with states that are meaningful to
 * **cross-repo lifecycle auditing** and V2 truth surface consumption.
 *
 * ## Why a separate enum?
 *
 * [FormalParticipantLifecycleState] is optimised for **capability gate decisions** (five
 * states; maps directly from [LocalInferenceRuntimeManager.ManagerState]).  This enum is
 * optimised for **lifecycle truth reporting**: it expresses the full participant journey
 * including registration phases, reconnect recovery outcomes, and re-alignment states that
 * the capability gate collapses into a single RECOVERING/READY state.
 *
 * ## The nine lifecycle truth states
 *
 * | [ParticipantLifecycleTruthState] | Wire value            | Description                                                                |
 * |----------------------------------|-----------------------|----------------------------------------------------------------------------|
 * | [UNREGISTERED]                   | `unregistered`        | Never registered or registration was cleared/revoked.                      |
 * | [REGISTERING]                    | `registering`         | Registration attempt in progress; not yet confirmed.                       |
 * | [ACTIVE]                         | `active`              | Registered, connected, and fully operational.                              |
 * | [DEGRADED]                       | `degraded`            | Registered but runtime health is reduced; limited capability only.         |
 * | [RECOVERING]                     | `recovering`          | Was active or degraded; now executing a recovery cycle.                    |
 * | [RECOVERED]                      | `recovered`           | Recovery cycle completed; participant has re-established connectivity.     |
 * | [UNAVAILABLE]                    | `unavailable`         | Not reachable, failed, or safe-mode; no participation possible.            |
 * | [RE_REGISTERING]                 | `re_registering`      | Re-registration in progress after recovery.                                |
 * | [CAPABILITY_RE_ALIGNED]          | `capability_re_aligned` | Capability re-alignment completed after recovery/re-registration.        |
 *
 * ## Lifecycle state transitions
 *
 * Normal happy-path transitions:
 *
 * ```
 * UNREGISTERED → REGISTERING → ACTIVE → DEGRADED → RECOVERING → RECOVERED
 *                                                               → RE_REGISTERING → CAPABILITY_RE_ALIGNED → ACTIVE
 *                                                                               → DEGRADED
 *                                         → UNAVAILABLE
 * ```
 *
 * ## Relationship to [FormalParticipantLifecycleState]
 *
 * [fromFormal] provides a conservative mapping from the five formal states to this
 * nine-state model.  Because [FormalParticipantLifecycleState] lacks re-registration and
 * re-alignment context, callers must supply additional recovery context when constructing
 * a [ParticipantLifecycleTruthReport] to distinguish [RECOVERED] / [RE_REGISTERING] /
 * [CAPABILITY_RE_ALIGNED] from the general [RECOVERING] state.
 *
 * ## Recovery intermediate states
 *
 * The following states represent recovery intermediate stages that must not be simplified
 * to a single boolean:
 *  - [RECOVERING]: the participant has entered a recovery cycle but has not yet succeeded.
 *  - [RECOVERED]: reconnect succeeded; session epoch was incremented; but capability
 *    re-alignment may not yet be complete.
 *  - [RE_REGISTERING]: re-registration is in progress after a RECOVERED transition.
 *  - [CAPABILITY_RE_ALIGNED]: capability re-advertisement succeeded; participant may
 *    now return to [ACTIVE] or remain in [DEGRADED] if runtime health is impaired.
 *
 * @see ParticipantLifecycleTruthReport
 * @see ParticipantLifecycleTruthReportBuilder
 * @see FormalParticipantLifecycleState
 */
enum class ParticipantLifecycleTruthState(
    val wireValue: String,
    val description: String
) {

    /**
     * The participant has never registered with the V2 gateway, or registration was
     * explicitly cleared or revoked.  No participation is possible in this state.
     *
     * This is the initial state for a fresh device installation or after an explicit
     * logout/deregistration.
     */
    UNREGISTERED(
        wireValue = "unregistered",
        description = "Participant never registered or registration cleared/revoked"
    ),

    /**
     * A registration attempt is in progress.  The participant is actively attempting to
     * establish its identity with the V2 gateway but has not yet received confirmation.
     *
     * Capability advertisement is blocked in this state.
     */
    REGISTERING(
        wireValue = "registering",
        description = "Registration in progress; awaiting V2 gateway confirmation"
    ),

    /**
     * The participant is registered, connected, and fully operational.
     *
     * This is the target steady-state for a healthy participant.  Full capability
     * advertisement is permitted.
     *
     * Corresponds to [FormalParticipantLifecycleState.READY] when
     * [RegistrationTruthStatus] is [RegistrationTruthStatus.REGISTERED].
     */
    ACTIVE(
        wireValue = "active",
        description = "Registered, connected, and fully operational; full capability"
    ),

    /**
     * The participant is registered but runtime health is impaired.  Only base
     * capabilities (no inference) are advertised.
     *
     * A DEGRADED participant can still accept work within its declared limited capability
     * set.  It must not be treated as ACTIVE for dispatch of full-capability tasks.
     *
     * Corresponds to [FormalParticipantLifecycleState.DEGRADED].
     */
    DEGRADED(
        wireValue = "degraded",
        description = "Registered but runtime degraded; base capabilities only"
    ),

    /**
     * The participant was active or degraded and has entered a recovery cycle.
     *
     * Recovery is in progress (WS reconnect, runtime restart, or both).  No capability
     * advertisement is permitted.  V2 must treat this participant as temporarily
     * unavailable for new work dispatch.
     *
     * Transitions to [RECOVERED] on success or [UNAVAILABLE] on exhaustion.
     */
    RECOVERING(
        wireValue = "recovering",
        description = "Recovery cycle in progress; capability advertisement blocked"
    ),

    /**
     * The recovery cycle completed successfully.
     *
     * The participant has re-established WS connectivity and incremented its durable
     * session continuity epoch.  However, capability re-alignment may still be pending
     * (see [RE_REGISTERING] and [CAPABILITY_RE_ALIGNED]).
     *
     * A RECOVERED participant without completed re-registration must not be treated as
     * ACTIVE.  Check [ParticipantLifecycleTruthReport.reRegistrationOutcome] and
     * [ParticipantLifecycleTruthReport.capabilityAlignmentStatus] before treating as
     * ACTIVE or DEGRADED.
     */
    RECOVERED(
        wireValue = "recovered",
        description = "Recovery cycle completed; connectivity restored but re-alignment may be pending"
    ),

    /**
     * The participant is not reachable, has entered a failed state, or is in safe mode.
     *
     * No participation is possible.  V2 must treat this participant as absent.
     * User action or an external health trigger is required to exit this state.
     *
     * Corresponds to [FormalParticipantLifecycleState.UNAVAILABLE_FAILED].
     */
    UNAVAILABLE(
        wireValue = "unavailable",
        description = "Participant unavailable, failed, or safe-mode; no participation possible"
    ),

    /**
     * Re-registration is in progress after a [RECOVERED] transition.
     *
     * The participant has re-established connectivity but the V2 gateway has not yet
     * confirmed re-registration.  Capability re-advertisement is pending.
     *
     * Transitions to [CAPABILITY_RE_ALIGNED] on successful re-registration, or
     * [UNAVAILABLE] on re-registration failure.
     */
    RE_REGISTERING(
        wireValue = "re_registering",
        description = "Re-registration in progress after recovery"
    ),

    /**
     * Capability re-alignment completed after recovery and re-registration.
     *
     * The participant has re-advertised its capabilities to V2 and the advertisement is
     * consistent with the current runtime state.  The participant may now transition to
     * [ACTIVE] (if runtime is healthy) or [DEGRADED] (if runtime health is impaired).
     *
     * Check [ParticipantLifecycleTruthReport.recoveredButDegraded] to determine whether
     * the re-aligned participant is returning to ACTIVE or DEGRADED.
     */
    CAPABILITY_RE_ALIGNED(
        wireValue = "capability_re_aligned",
        description = "Capability re-alignment completed after recovery/re-registration"
    );

    companion object {

        /**
         * Returns the [ParticipantLifecycleTruthState] with the given [wireValue], or
         * `null` if no match is found.
         *
         * @param value  The wire value string to look up.
         */
        fun fromWireValue(value: String?): ParticipantLifecycleTruthState? =
            entries.firstOrNull { it.wireValue == value }

        /**
         * Derives a conservative [ParticipantLifecycleTruthState] from a
         * [FormalParticipantLifecycleState].
         *
         * This mapping is **conservative**: it cannot distinguish [RECOVERED],
         * [RE_REGISTERING], or [CAPABILITY_RE_ALIGNED] from [RECOVERING] because
         * [FormalParticipantLifecycleState] collapses these into [RECOVERING].
         * Use [ParticipantLifecycleTruthReportBuilder] to derive the full truth state
         * from multi-dimensional runtime context.
         *
         * | [FormalParticipantLifecycleState] | [ParticipantLifecycleTruthState] |
         * |-----------------------------------|----------------------------------|
         * | STARTING                          | REGISTERING                      |
         * | READY                             | ACTIVE                           |
         * | DEGRADED                          | DEGRADED                         |
         * | RECOVERING                        | RECOVERING                       |
         * | UNAVAILABLE_FAILED                | UNAVAILABLE                      |
         *
         * @param formal  The [FormalParticipantLifecycleState] to convert.
         */
        fun fromFormal(formal: FormalParticipantLifecycleState): ParticipantLifecycleTruthState =
            when (formal) {
                FormalParticipantLifecycleState.STARTING           -> REGISTERING
                FormalParticipantLifecycleState.READY              -> ACTIVE
                FormalParticipantLifecycleState.DEGRADED           -> DEGRADED
                FormalParticipantLifecycleState.RECOVERING         -> RECOVERING
                FormalParticipantLifecycleState.UNAVAILABLE_FAILED -> UNAVAILABLE
            }

        /**
         * Returns `true` when [state] permits capability advertisement.
         *
         * Only [ACTIVE] and [DEGRADED] allow capability advertisement.
         * All other states must suppress capability claims.
         */
        fun capabilityAdvertisementAllowed(state: ParticipantLifecycleTruthState): Boolean =
            state == ACTIVE || state == DEGRADED

        /**
         * Returns `true` when [state] represents a recovery-in-progress phase that
         * blocks participation.
         *
         * Recovery phases: [RECOVERING], [RECOVERED] (pending re-alignment),
         * [RE_REGISTERING].
         */
        fun isRecoveryPhase(state: ParticipantLifecycleTruthState): Boolean =
            state == RECOVERING || state == RECOVERED || state == RE_REGISTERING

        /**
         * All stable wire values for this enum, for validation purposes.
         */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}
