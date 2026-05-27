package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.DevicePerceptionEmissionPayload

data class CanonicalContinuousIngressEntry(
    val family: ContinuousIngressFamily,
    val entryId: String,
    val cognitionRole: CognitionRole,
    val isActive: Boolean,
    val sessionId: String? = null,
    val routeMode: String? = null
) {
    enum class ContinuousIngressFamily(val wireValue: String) {
        DEVICE_STREAM_SESSION("device_stream_session"),
        CONTINUOUS_PERCEPTION_SESSION_INPUT("continuous_perception_session_input")
    }

    enum class CognitionRole(val wireValue: String) {
        CANONICAL_COGNITION_CARRIER("canonical_cognition_carrier"),
        CANONICAL_PERCEPTION_INPUT("canonical_perception_input")
    }
}

data class CanonicalContinuousIngressBackbone(
    val entries: List<CanonicalContinuousIngressEntry>
) {
    val activeEntries: List<CanonicalContinuousIngressEntry>
        get() = entries.filter { it.isActive }

    val activeFamilies: Set<CanonicalContinuousIngressEntry.ContinuousIngressFamily>
        get() = activeEntries.mapTo(linkedSetOf()) { it.family }

    val hasUnifiedContinuousIngress: Boolean
        get() = activeFamilies.size >= MINIMUM_UNIFIED_FAMILY_COUNT

    val hasCanonicalPerceptionInput: Boolean
        get() = activeEntries.any {
            it.cognitionRole == CanonicalContinuousIngressEntry.CognitionRole.CANONICAL_PERCEPTION_INPUT
        }

    val promotesDeviceStreamIntoCanonicalCognition: Boolean
        get() = activeEntries.any {
            it.family == CanonicalContinuousIngressEntry.ContinuousIngressFamily.DEVICE_STREAM_SESSION &&
                it.cognitionRole ==
                CanonicalContinuousIngressEntry.CognitionRole.CANONICAL_COGNITION_CARRIER
        }

    val supportsCanonicalCognitionBackbone: Boolean
        get() = hasUnifiedContinuousIngress &&
            hasCanonicalPerceptionInput &&
            promotesDeviceStreamIntoCanonicalCognition

    companion object {
        private const val MINIMUM_UNIFIED_FAMILY_COUNT = 2
        private const val DEVICE_STREAM_SESSION_FALLBACK_PREFIX = "device_stream_session"
        val EMPTY = CanonicalContinuousIngressBackbone(emptyList())
    }
}

object CanonicalContinuousIngressBackboneBuilder {
    fun deviceStreamSession(
        deviceId: String,
        runtimeSessionId: String?,
        routeMode: String?,
        connected: Boolean
    ): CanonicalContinuousIngressEntry =
        CanonicalContinuousIngressEntry(
            family = CanonicalContinuousIngressEntry.ContinuousIngressFamily.DEVICE_STREAM_SESSION,
            entryId = runtimeSessionId?.takeIf { it.isNotBlank() }
                ?: deviceId.takeIf { it.isNotBlank() }
                ?: "$DEVICE_STREAM_SESSION_FALLBACK_PREFIX:${routeMode ?: "unknown"}",
            cognitionRole = CanonicalContinuousIngressEntry.CognitionRole.CANONICAL_COGNITION_CARRIER,
            isActive = connected,
            sessionId = runtimeSessionId,
            routeMode = routeMode
        )

    fun continuousPerceptionSession(
        payload: DevicePerceptionEmissionPayload
    ): CanonicalContinuousIngressEntry {
        val canonicalPayload = payload.toCanonicalIngressPayload()
        return CanonicalContinuousIngressEntry(
            family = CanonicalContinuousIngressEntry.ContinuousIngressFamily
                .CONTINUOUS_PERCEPTION_SESSION_INPUT,
            entryId = canonicalPayload.emission_id,
            cognitionRole = CanonicalContinuousIngressEntry.CognitionRole.CANONICAL_PERCEPTION_INPUT,
            isActive = canonicalPayload.canonical_main_chain_eligible == true,
            sessionId = canonicalPayload.runtime_session_id ?: canonicalPayload.attached_session_id,
            routeMode = canonicalPayload.mode_state
        )
    }

    fun assemble(
        vararg entries: CanonicalContinuousIngressEntry?
    ): CanonicalContinuousIngressBackbone =
        CanonicalContinuousIngressBackbone(
            entries.mapNotNull { entry -> entry?.takeIf { it.isActive } }
        )
}
