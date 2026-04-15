package com.ufo.galaxy.runtime

/**
 * Canonical Android-side device-model abstraction for architecture-neutral convergence.
 *
 * This model is additive and compatibility-safe. It preserves current Android runtime behavior
 * while making Android explicit as one strong/full-runtime device category instead of a
 * universal baseline for all devices.
 */
enum class DeviceCategory(val wireValue: String) {
    ANDROID_FULL_RUNTIME_HOST("android_full_runtime_host"),
    AERIAL_DRONE("aerial_drone"),
    FABRICATION_3D_PRINTER("fabrication_3d_printer"),
    EMBEDDED_CONTROLLER("embedded_controller"),
    SMART_HOME_ENDPOINT("smart_home_endpoint"),
    SPECIALIZED_ENDPOINT("specialized_endpoint")
}

enum class DeviceRuntimeHostStrength(val wireValue: String) {
    FULL_RUNTIME_HOST("full_runtime_host"),
    PARTIAL_RUNTIME_NODE("partial_runtime_node"),
    COMMAND_ENDPOINT("command_endpoint"),
    OBSERVER("observer")
}

data class DeviceRuntimeHostSemantics(
    val runtimeStrength: DeviceRuntimeHostStrength,
    val supportsRuntimeHostExecution: Boolean,
    val appliesUniversally: Boolean = false
)

data class DeviceTruthBoundary(
    val ownsAttachedSessionTruth: Boolean = false,
    val ownsDelegatedSelectionTruth: Boolean = false,
    val ownsReconnectLifecycleTruth: Boolean = false
)

data class CanonicalDeviceModel(
    val deviceId: String,
    val deviceCategory: DeviceCategory,
    val runtimeHostId: String?,
    val linkedParticipantId: String?,
    val runtimeHostSemantics: DeviceRuntimeHostSemantics,
    val truthBoundary: DeviceTruthBoundary = DeviceTruthBoundary()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "device_id" to deviceId,
        "device_category" to deviceCategory.wireValue,
        "runtime_host_id" to runtimeHostId,
        "linked_participant_id" to linkedParticipantId,
        "runtime_host_semantics" to mapOf(
            "runtime_strength" to runtimeHostSemantics.runtimeStrength.wireValue,
            "supports_runtime_host_execution" to runtimeHostSemantics.supportsRuntimeHostExecution,
            "applies_universally" to runtimeHostSemantics.appliesUniversally
        ),
        "truth_boundary" to mapOf(
            "owns_attached_session_truth" to truthBoundary.ownsAttachedSessionTruth,
            "owns_delegated_selection_truth" to truthBoundary.ownsDelegatedSelectionTruth,
            "owns_reconnect_lifecycle_truth" to truthBoundary.ownsReconnectLifecycleTruth
        )
    )
}

object AndroidDeviceModelMapper {
    /**
     * Builds an additive device-plane **projection/read-model** for convergence.
     *
     * This contract is intentionally non-authoritative for session/readiness/reconnect truth.
     * RuntimeController remains the lifecycle truth owner on Android runtime-host surfaces.
     */
    fun fromRuntimeHostDescriptor(descriptor: RuntimeHostDescriptor): CanonicalDeviceModel = CanonicalDeviceModel(
        deviceId = descriptor.deviceId,
        deviceCategory = DeviceCategory.ANDROID_FULL_RUNTIME_HOST,
        runtimeHostId = descriptor.hostId,
        linkedParticipantId = "${descriptor.deviceId}:${descriptor.hostId}",
        runtimeHostSemantics = DeviceRuntimeHostSemantics(
            runtimeStrength = DeviceRuntimeHostStrength.FULL_RUNTIME_HOST,
            supportsRuntimeHostExecution = true,
            appliesUniversally = false
        )
    )
}
