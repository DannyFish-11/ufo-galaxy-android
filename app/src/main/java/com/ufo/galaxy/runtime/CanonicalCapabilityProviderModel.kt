package com.ufo.galaxy.runtime

/**
 * Canonical Android-side capability-provider abstraction for Capability Plane alignment.
 *
 * This model is additive and compatibility-safe. It makes capability publication explicit
 * as provider behavior and does not replace Android runtime/device/participant/session truth.
 */
enum class CapabilityProviderKind(val wireValue: String) {
    ANDROID_RUNTIME_HOST_PROVIDER("android_runtime_host_provider")
}

data class CapabilityProviderIdentityLinkage(
    val deviceId: String,
    val runtimeHostId: String
)

data class CapabilityProviderSessionLinkage(
    val attachedSessionId: String?,
    val runtimeSessionId: String?
)

data class CapabilityProviderPublicationSurface(
    val supportedActions: Set<String>,
    val capabilityRefs: Set<String>,
    val metadataKeys: Set<String>
)

data class CapabilityProviderTruthBoundary(
    val ownsRuntimeIdentityTruth: Boolean = false,
    val ownsParticipantTruth: Boolean = false,
    val ownsAttachmentTruth: Boolean = false,
    val ownsReconnectTruth: Boolean = false
)

data class CanonicalCapabilityProviderModel(
    val providerId: String,
    val providerKind: CapabilityProviderKind,
    val identityLinkage: CapabilityProviderIdentityLinkage,
    val sessionLinkage: CapabilityProviderSessionLinkage,
    val publicationSurface: CapabilityProviderPublicationSurface,
    val truthBoundary: CapabilityProviderTruthBoundary = CapabilityProviderTruthBoundary()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "provider_id" to providerId,
        "provider_kind" to providerKind.wireValue,
        "identity_linkage" to mapOf(
            "device_id" to identityLinkage.deviceId,
            "runtime_host_id" to identityLinkage.runtimeHostId
        ),
        "session_linkage" to mapOf(
            "attached_session_id" to sessionLinkage.attachedSessionId,
            "runtime_session_id" to sessionLinkage.runtimeSessionId
        ),
        "publication_surface" to mapOf(
            "supported_actions" to publicationSurface.supportedActions.toList(),
            "capability_refs" to publicationSurface.capabilityRefs.toList(),
            "metadata_keys" to publicationSurface.metadataKeys.toList()
        ),
        "truth_boundary" to mapOf(
            "owns_runtime_identity_truth" to truthBoundary.ownsRuntimeIdentityTruth,
            "owns_participant_truth" to truthBoundary.ownsParticipantTruth,
            "owns_attachment_truth" to truthBoundary.ownsAttachmentTruth,
            "owns_reconnect_truth" to truthBoundary.ownsReconnectTruth
        )
    )
}

object AndroidCapabilityProviderModelMapper {
    fun fromRuntimeHostDescriptor(
        descriptor: RuntimeHostDescriptor,
        hostSessionSnapshot: AttachedRuntimeHostSessionSnapshot? = null,
        supportedActions: Set<String> = emptySet(),
        capabilityRefs: Set<String> = emptySet(),
        metadataKeys: Set<String> = emptySet()
    ): CanonicalCapabilityProviderModel = CanonicalCapabilityProviderModel(
        providerId = RuntimeIdentityContracts.capabilityProviderRef(
            deviceId = descriptor.deviceId,
            runtimeHostId = descriptor.hostId
        ),
        providerKind = CapabilityProviderKind.ANDROID_RUNTIME_HOST_PROVIDER,
        identityLinkage = CapabilityProviderIdentityLinkage(
            deviceId = descriptor.deviceId,
            runtimeHostId = descriptor.hostId
        ),
        sessionLinkage = CapabilityProviderSessionLinkage(
            attachedSessionId = hostSessionSnapshot?.sessionId,
            runtimeSessionId = hostSessionSnapshot?.runtimeSessionId
        ),
        publicationSurface = CapabilityProviderPublicationSurface(
            supportedActions = supportedActions,
            capabilityRefs = capabilityRefs,
            metadataKeys = metadataKeys
        )
    )
}
