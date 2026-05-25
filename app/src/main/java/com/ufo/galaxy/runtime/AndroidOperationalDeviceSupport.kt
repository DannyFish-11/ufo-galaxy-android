package com.ufo.galaxy.runtime

/**
 * Canonical Android device-role support classifier for operational runtime truth.
 *
 * Android currently exposes a small logical device-role vocabulary (`phone`, `tablet`, `hub`),
 * but not every declared role should be treated as an operational near-peer execution surface.
 * This classifier keeps cross-device support claims honest by distinguishing:
 *  - real near-peer execution roles,
 *  - control-plane-only roles,
 *  - unsupported / undeclared roles.
 */
object AndroidOperationalDeviceSupport {

    enum class SupportClass(val wireValue: String) {
        EXECUTION_NEAR_PEER("execution_near_peer"),
        CONTROL_PLANE_ONLY("control_plane_only"),
        UNSUPPORTED_DECLARED_ROLE("unsupported_declared_role")
    }

    data class SupportTruth(
        val normalizedDeviceRole: String,
        val supportClass: SupportClass,
        val supportsCrossDeviceExecutionNearPeer: Boolean,
        val supportsControlPlaneParticipation: Boolean,
        val demotionReason: String?
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            KEY_NORMALIZED_DEVICE_ROLE to normalizedDeviceRole,
            KEY_SUPPORT_CLASS to supportClass.wireValue,
            KEY_SUPPORTS_CROSS_DEVICE_EXECUTION_NEAR_PEER to supportsCrossDeviceExecutionNearPeer,
            KEY_SUPPORTS_CONTROL_PLANE_PARTICIPATION to supportsControlPlaneParticipation,
            KEY_DEMOTION_REASON to demotionReason
        )
    }

    fun classify(deviceRole: String?): SupportTruth {
        val normalized = deviceRole?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "phone", "tablet" -> SupportTruth(
                normalizedDeviceRole = normalized,
                supportClass = SupportClass.EXECUTION_NEAR_PEER,
                supportsCrossDeviceExecutionNearPeer = true,
                supportsControlPlaneParticipation = true,
                demotionReason = null
            )
            "hub" -> SupportTruth(
                normalizedDeviceRole = normalized,
                supportClass = SupportClass.CONTROL_PLANE_ONLY,
                supportsCrossDeviceExecutionNearPeer = false,
                supportsControlPlaneParticipation = true,
                demotionReason = "hub_role_not_supported_as_android_execution_near_peer"
            )
            else -> SupportTruth(
                normalizedDeviceRole = normalized.ifBlank { "unknown" },
                supportClass = SupportClass.UNSUPPORTED_DECLARED_ROLE,
                supportsCrossDeviceExecutionNearPeer = false,
                supportsControlPlaneParticipation = false,
                demotionReason = "device_role_not_recognized_for_operational_android_support"
            )
        }
    }

    const val KEY_NORMALIZED_DEVICE_ROLE = "normalized_device_role"
    const val KEY_SUPPORT_CLASS = "support_class"
    const val KEY_SUPPORTS_CROSS_DEVICE_EXECUTION_NEAR_PEER =
        "supports_cross_device_execution_near_peer"
    const val KEY_SUPPORTS_CONTROL_PLANE_PARTICIPATION =
        "supports_control_plane_participation"
    const val KEY_DEMOTION_REASON = "demotion_reason"
}
