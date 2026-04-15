package com.ufo.galaxy.runtime

/**
 * Shared runtime-tier vocabulary/mapping helpers across Android device-plane and participant-plane
 * additive contracts.
 *
 * This is compatibility-safe and non-breaking: wire values are unchanged. The goal is to reduce
 * accidental drift where semantically equivalent runtime-host tier concepts are represented in
 * separate Android-side contract models.
 */
object RuntimeTierContracts {
    const val FULL_RUNTIME_HOST = "full_runtime_host"
    const val PARTIAL_RUNTIME_NODE = "partial_runtime_node"
    const val COMMAND_ENDPOINT = "command_endpoint"
    const val OBSERVER = "observer"

    fun participantTierFor(
        runtimeStrength: DeviceRuntimeHostStrength
    ): ParticipantRuntimeTier = when (runtimeStrength) {
        DeviceRuntimeHostStrength.FULL_RUNTIME_HOST -> ParticipantRuntimeTier.FULL_RUNTIME_HOST
        DeviceRuntimeHostStrength.PARTIAL_RUNTIME_NODE -> ParticipantRuntimeTier.PARTIAL_RUNTIME_NODE
        DeviceRuntimeHostStrength.COMMAND_ENDPOINT -> ParticipantRuntimeTier.COMMAND_ENDPOINT
        DeviceRuntimeHostStrength.OBSERVER -> ParticipantRuntimeTier.OBSERVER
    }

    fun deviceRuntimeStrengthFor(
        runtimeTier: ParticipantRuntimeTier
    ): DeviceRuntimeHostStrength = when (runtimeTier) {
        ParticipantRuntimeTier.FULL_RUNTIME_HOST -> DeviceRuntimeHostStrength.FULL_RUNTIME_HOST
        ParticipantRuntimeTier.PARTIAL_RUNTIME_NODE -> DeviceRuntimeHostStrength.PARTIAL_RUNTIME_NODE
        ParticipantRuntimeTier.COMMAND_ENDPOINT -> DeviceRuntimeHostStrength.COMMAND_ENDPOINT
        ParticipantRuntimeTier.OBSERVER -> DeviceRuntimeHostStrength.OBSERVER
    }
}
