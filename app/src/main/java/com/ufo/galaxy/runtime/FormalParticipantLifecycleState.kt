package com.ufo.galaxy.runtime

/**
 * Formal unified participant lifecycle state model for the Android runtime.
 *
 * Collapses the multi-dimensional sub-state space (health × reconnect × readiness ×
 * participation) into a single authoritative five-state enumeration that the control
 * plane and capability subsystems can act on.  This is the **canonical bridge** between
 * the internal state machinery and the outward-facing capability advertisement / protocol
 * consistency contract.
 *
 * ## The five formal states
 *
 * | [FormalState]         | Description                                                       | Capability advertisement |
 * |-----------------------|-------------------------------------------------------------------|-----------------------------|
 * | [STARTING]            | Runtime is actively starting up; warmup in progress.             | Blocked                     |
 * | [READY]               | Runtime is fully operational; all systems healthy.               | Full (inference if available)|
 * | [DEGRADED]            | Runtime is operational but at reduced capability.                 | Base capabilities only       |
 * | [RECOVERING]          | Runtime detected a failure and is executing a recovery cycle.    | Blocked                     |
 * | [UNAVAILABLE_FAILED]  | Runtime has failed or is stopped; no execution possible.         | Blocked                     |
 *
 * ## Derivation
 *
 * Use [fromManagerState] to derive a [FormalState] from a
 * [LocalInferenceRuntimeManager.ManagerState], or [fromHealthState] to derive from a
 * [ParticipantHealthState].
 *
 * ## Capability gating
 *
 * [capabilityAdvertisementAllowed] and [inferenceCapabilityAllowed] provide the
 * authoritative capability gates used by [RecoveryCapabilityAlignmentGuard] and
 * [CapabilityReadvertiseContract].  Capability claims must always be validated against
 * the current [FormalState] before being emitted to the control plane.
 *
 * @see RecoveryCapabilityAlignmentGuard
 * @see CapabilityReadvertiseContract
 * @see CapabilityHonestyGuard
 */
enum class FormalParticipantLifecycleState(
    val wireValue: String,
    val description: String
) {

    /**
     * The runtime is actively starting up.  Warmup for one or more components is in
     * progress; no capability advertisement is permitted until the state resolves to
     * [READY] or [DEGRADED].
     *
     * Corresponds to [LocalInferenceRuntimeManager.ManagerState.Starting] and
     * [LocalInferenceRuntimeManager.ManagerState.PartialReady].
     */
    STARTING(
        wireValue = "starting",
        description = "Runtime actively starting; capability advertisement blocked"
    ),

    /**
     * The runtime is fully operational.  Both planner and grounding runtimes are healthy.
     * Full capability advertisement is permitted (including inference if available).
     *
     * Corresponds to [LocalInferenceRuntimeManager.ManagerState.Running].
     */
    READY(
        wireValue = "ready",
        description = "Runtime fully operational; all capability advertisement permitted"
    ),

    /**
     * The runtime is operational but at reduced capability.  At least one subsystem is
     * impaired.  Only base capabilities (no inference) are advertised.
     *
     * Corresponds to [LocalInferenceRuntimeManager.ManagerState.Degraded].
     */
    DEGRADED(
        wireValue = "degraded",
        description = "Runtime degraded; base capabilities only, inference blocked"
    ),

    /**
     * The runtime detected a failure and is executing a stop-then-start recovery cycle.
     * No capability advertisement is permitted until recovery succeeds.
     *
     * Corresponds to [LocalInferenceRuntimeManager.ManagerState.Recovering] and
     * [LocalInferenceRuntimeManager.ManagerState.Unavailable].
     */
    RECOVERING(
        wireValue = "recovering",
        description = "Runtime in recovery cycle; capability advertisement blocked"
    ),

    /**
     * The runtime has failed, stopped, or entered safe mode.  No execution is possible.
     * No capability advertisement is permitted.
     *
     * Corresponds to [LocalInferenceRuntimeManager.ManagerState.Failed],
     * [LocalInferenceRuntimeManager.ManagerState.FailedStartup],
     * [LocalInferenceRuntimeManager.ManagerState.Stopped], and
     * [LocalInferenceRuntimeManager.ManagerState.SafeMode].
     */
    UNAVAILABLE_FAILED(
        wireValue = "unavailable_failed",
        description = "Runtime unavailable or failed; no capability advertisement permitted"
    );

    companion object {

        /**
         * Derives a [FormalParticipantLifecycleState] from the current
         * [LocalInferenceRuntimeManager.ManagerState].
         *
         * | ManagerState       | FormalState          |
         * |--------------------|----------------------|
         * | `Starting`         | [STARTING]           |
         * | `PartialReady`     | [STARTING]           |
         * | `Running`          | [READY]              |
         * | `Degraded`         | [DEGRADED]           |
         * | `Recovering`       | [RECOVERING]         |
         * | `Unavailable`      | [RECOVERING]         |
         * | `Failed`           | [UNAVAILABLE_FAILED] |
         * | `FailedStartup`    | [UNAVAILABLE_FAILED] |
         * | `Stopped`          | [UNAVAILABLE_FAILED] |
         * | `SafeMode`         | [UNAVAILABLE_FAILED] |
         */
        fun fromManagerState(state: LocalInferenceRuntimeManager.ManagerState): FormalParticipantLifecycleState =
            when (state) {
                is LocalInferenceRuntimeManager.ManagerState.Starting       -> STARTING
                is LocalInferenceRuntimeManager.ManagerState.PartialReady   -> STARTING
                is LocalInferenceRuntimeManager.ManagerState.Running        -> READY
                is LocalInferenceRuntimeManager.ManagerState.Degraded       -> DEGRADED
                is LocalInferenceRuntimeManager.ManagerState.Recovering     -> RECOVERING
                is LocalInferenceRuntimeManager.ManagerState.Unavailable    -> RECOVERING
                is LocalInferenceRuntimeManager.ManagerState.Failed         -> UNAVAILABLE_FAILED
                is LocalInferenceRuntimeManager.ManagerState.FailedStartup  -> UNAVAILABLE_FAILED
                is LocalInferenceRuntimeManager.ManagerState.Stopped        -> UNAVAILABLE_FAILED
                is LocalInferenceRuntimeManager.ManagerState.SafeMode       -> UNAVAILABLE_FAILED
            }

        /**
         * Derives a [FormalParticipantLifecycleState] from a [ParticipantHealthState].
         *
         * | ParticipantHealthState | FormalState          |
         * |------------------------|----------------------|
         * | `STARTING`             | [STARTING]           |
         * | `HEALTHY`              | [READY]              |
         * | `DEGRADED`             | [DEGRADED]           |
         * | `RECOVERING`           | [RECOVERING]         |
         * | `FAILED`               | [UNAVAILABLE_FAILED] |
         * | `UNKNOWN`              | [UNAVAILABLE_FAILED] |
         */
        fun fromHealthState(healthState: ParticipantHealthState): FormalParticipantLifecycleState =
            when (healthState) {
                ParticipantHealthState.STARTING  -> STARTING
                ParticipantHealthState.HEALTHY   -> READY
                ParticipantHealthState.DEGRADED  -> DEGRADED
                ParticipantHealthState.RECOVERING -> RECOVERING
                ParticipantHealthState.FAILED    -> UNAVAILABLE_FAILED
                ParticipantHealthState.UNKNOWN   -> UNAVAILABLE_FAILED
            }

        /**
         * Returns `true` when the [formalState] allows any capability advertisement.
         *
         * Only [READY] and [DEGRADED] permit advertising capabilities.
         * [STARTING], [RECOVERING], and [UNAVAILABLE_FAILED] must suppress all capability claims.
         */
        fun capabilityAdvertisementAllowed(formalState: FormalParticipantLifecycleState): Boolean =
            formalState == READY || formalState == DEGRADED

        /**
         * Returns `true` when the [formalState] allows advertisement of the inference
         * capability (`local_model_inference`).
         *
         * Inference capability is only allowed in [READY] when [inferenceStatusActive] is true.
         * All other states must suppress the inference capability.
         *
         * @param formalState       The current formal lifecycle state.
         * @param inferenceStatusActive `true` when [LocalIntelligenceCapabilityStatus] is [LocalIntelligenceCapabilityStatus.ACTIVE].
         */
        fun inferenceCapabilityAllowed(
            formalState: FormalParticipantLifecycleState,
            inferenceStatusActive: Boolean
        ): Boolean = formalState == READY && inferenceStatusActive

        /**
         * Returns `true` when transitioning from [previousState] to [newState] requires the
         * participant to re-register with and re-advertise capabilities to the control plane.
         *
         * Re-registration is required any time the participant returns to [READY] or [DEGRADED]
         * from a state that blocked capability advertisement ([STARTING], [RECOVERING],
         * [UNAVAILABLE_FAILED]).  The control plane must see an up-to-date capability report
         * consistent with the new runtime truth.
         */
        fun requiresCapabilityReAdvertise(
            previousState: FormalParticipantLifecycleState,
            newState: FormalParticipantLifecycleState
        ): Boolean {
            val wasBlocked = !capabilityAdvertisementAllowed(previousState)
            val isNowAllowed = capabilityAdvertisementAllowed(newState)
            return wasBlocked && isNowAllowed
        }

        /**
         * Parses a [wireValue] string, returning `null` for unrecognised values.
         */
        fun fromWireValue(value: String?): FormalParticipantLifecycleState? =
            entries.firstOrNull { it.wireValue == value }

        /** All stable wire values for this enum. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}
