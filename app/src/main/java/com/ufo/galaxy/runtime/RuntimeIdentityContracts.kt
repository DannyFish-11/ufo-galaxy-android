package com.ufo.galaxy.runtime

/**
 * Canonical Android runtime identity-linkage contract helpers.
 *
 * These helpers keep identity composition scan-friendly and centralized:
 * - participant/node identity (`device_id:runtime_host_id`)
 * - capability-provider reference (`capability_provider:<participant-node-id>`)
 *
 * The contract is additive and compatibility-safe: values are unchanged; only composition
 * logic is centralized so boundary semantics are easier to read and maintain.
 */
object RuntimeIdentityContracts {
    const val CAPABILITY_PROVIDER_PREFIX = "capability_provider"

    /**
     * Canonical participant/node identity linkage (`device_id:runtime_host_id`).
     */
    fun participantNodeId(
        deviceId: String,
        runtimeHostId: String
    ): String = "$deviceId:$runtimeHostId"

    /**
     * Canonical capability-provider reference
     * (`capability_provider:<device_id:runtime_host_id>`).
     */
    fun capabilityProviderRef(
        deviceId: String,
        runtimeHostId: String
    ): String = "$CAPABILITY_PROVIDER_PREFIX:${participantNodeId(deviceId, runtimeHostId)}"
}
