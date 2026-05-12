package com.ufo.galaxy.runtime

/**
 * PR-1 (Android counterpart): single Android-authoritative runtime participation truth.
 *
 * This contract collapses scattered runtime/connectivity/readiness/session signals into one
 * canonical state label that V2 can consume directly.
 */
object AndroidAuthoritativeParticipationTruth {

    enum class State(val wireValue: String) {
        LOCAL_ONLY("local_only"),
        CONTROL_ONLY("control_only"),
        CROSS_DEVICE_CAPABLE("cross_device_capable"),
        CROSS_DEVICE_ENABLED("cross_device_enabled"),
        FULLY_ATTACHED("fully_attached"),
        DISPATCH_ELIGIBLE("dispatch_eligible"),
        DISTRIBUTED_PARTICIPANT("distributed_participant")
    }

    data class DerivationInput(
        val crossDeviceEnabled: Boolean,
        val wsConnected: Boolean,
        val registrationInFlight: Boolean,
        val capabilityVisible: Boolean,
        val readinessSatisfied: Boolean,
        val runtimeSessionAvailable: Boolean,
        val fullyAttached: Boolean,
        val dispatchEligible: Boolean,
        val continuityIntact: Boolean,
        val operatorSuspendedOrIsolated: Boolean,
        val distributedRuntimeActivity: Boolean
    )

    fun derive(input: DerivationInput): State {
        if (!input.crossDeviceEnabled) return State.LOCAL_ONLY
        if (input.operatorSuspendedOrIsolated) return State.CONTROL_ONLY
        if (input.registrationInFlight || !input.wsConnected) return State.CONTROL_ONLY
        if (!input.capabilityVisible) return State.CROSS_DEVICE_CAPABLE
        if (!input.runtimeSessionAvailable || !input.continuityIntact) return State.CROSS_DEVICE_ENABLED
        if (!input.fullyAttached) return State.CROSS_DEVICE_ENABLED
        if (!input.readinessSatisfied || !input.dispatchEligible) return State.FULLY_ATTACHED
        if (input.distributedRuntimeActivity) return State.DISTRIBUTED_PARTICIPANT
        return State.DISPATCH_ELIGIBLE
    }
}
