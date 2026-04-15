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
    val isDefaultAssumptionForAllDevices: Boolean = false
)

data class CanonicalDeviceModel(
    val deviceId: String,
    val deviceCategory: DeviceCategory,
    val runtimeHostId: String?,
    val linkedParticipantId: String?,
    val runtimeHostSemantics: DeviceRuntimeHostSemantics
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "device_id" to deviceId,
        "device_category" to deviceCategory.wireValue,
        "runtime_host_id" to runtimeHostId,
        "linked_participant_id" to linkedParticipantId,
        "runtime_host_semantics" to mapOf(
            "runtime_strength" to runtimeHostSemantics.runtimeStrength.wireValue,
            "supports_runtime_host_execution" to runtimeHostSemantics.supportsRuntimeHostExecution,
            "is_default_assumption_for_all_devices" to runtimeHostSemantics.isDefaultAssumptionForAllDevices
        )
    )
}

object AndroidDeviceModelMapper {
    fun fromRuntimeHostDescriptor(descriptor: RuntimeHostDescriptor): CanonicalDeviceModel = CanonicalDeviceModel(
        deviceId = descriptor.deviceId,
        deviceCategory = DeviceCategory.ANDROID_FULL_RUNTIME_HOST,
        runtimeHostId = descriptor.hostId,
        linkedParticipantId = "${descriptor.deviceId}:${descriptor.hostId}",
        runtimeHostSemantics = DeviceRuntimeHostSemantics(
            runtimeStrength = DeviceRuntimeHostStrength.FULL_RUNTIME_HOST,
            supportsRuntimeHostExecution = true,
            isDefaultAssumptionForAllDevices = false
        )
    )
}
