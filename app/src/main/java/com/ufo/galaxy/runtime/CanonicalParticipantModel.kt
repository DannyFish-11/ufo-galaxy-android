package com.ufo.galaxy.runtime

/**
 * Canonical Android-side participant abstraction for cross-repo participant-model convergence.
 *
 * This model is additive and non-breaking. It does not replace existing runtime/device/session
 * models, and introduces no runtime behavior changes. Instead, it provides a single explicit
 * participant view so Android runtime-host participation is not treated as an implicit default.
 */
enum class ParticipantKind(val wireValue: String) {
    ANDROID_RUNTIME_HOST("android_runtime_host")
}

enum class ParticipantRuntimeTier(val wireValue: String) {
    /**
     * Strongest participant tier. Android maps to this tier today.
     */
    FULL_RUNTIME_HOST(RuntimeTierContracts.FULL_RUNTIME_HOST),

    /**
     * Shared-model lighter tier for nodes that host only part of runtime execution semantics.
     */
    PARTIAL_RUNTIME_NODE(RuntimeTierContracts.PARTIAL_RUNTIME_NODE),

    /**
     * Shared-model lighter tier for endpoints that can execute commands but do not host full runtime.
     */
    COMMAND_ENDPOINT(RuntimeTierContracts.COMMAND_ENDPOINT),

    /**
     * Shared-model lighter tier for observer-style participants with no execution ownership.
     */
    OBSERVER(RuntimeTierContracts.OBSERVER)
}

enum class ParticipantAutonomyLevel(val wireValue: String) {
    HIGH_AUTONOMY("high_autonomy")
}

enum class ParticipantCoordinationRole(val wireValue: String) {
    COORDINATOR("coordinator"),
    PARTICIPANT("participant")
}

enum class ParticipantReadinessState(val wireValue: String) {
    READY("ready"),
    READY_WITH_FALLBACK("ready_with_fallback"),
    NOT_READY("not_ready"),
    UNKNOWN("unknown")
}

data class ParticipantDeviceLinkage(
    val deviceId: String,
    val runtimeHostId: String
)

data class ParticipantCapabilityLinkage(
    val capabilityRefs: Set<String>
)

data class ParticipantSessionLinkage(
    val attachedSessionId: String?,
    val runtimeSessionId: String?,
    val sourceRuntimePosture: String?
)

data class ParticipantTruthBoundary(
    val ownsRuntimeAttachmentTruth: Boolean = false,
    val ownsDelegatedSelectionTruth: Boolean = false,
    val ownsReconnectLifecycleTruth: Boolean = false
)

data class CanonicalParticipantModel(
    val participantId: String,
    val participantKind: ParticipantKind,
    val runtimeTier: ParticipantRuntimeTier,
    val autonomyLevel: ParticipantAutonomyLevel,
    val coordinationRole: ParticipantCoordinationRole,
    val readinessState: ParticipantReadinessState,
    val participationState: RuntimeHostDescriptor.HostParticipationState,
    val supportsDelegation: Boolean,
    val supportsAttachedSession: Boolean,
    val supportsLocalExecution: Boolean,
    val supportsCommandExecution: Boolean,
    val deviceLinkage: ParticipantDeviceLinkage,
    val capabilityLinkage: ParticipantCapabilityLinkage,
    val sessionLinkage: ParticipantSessionLinkage,
    val truthBoundary: ParticipantTruthBoundary = ParticipantTruthBoundary()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "participant_id" to participantId,
        "participant_kind" to participantKind.wireValue,
        "runtime_tier" to runtimeTier.wireValue,
        "autonomy_level" to autonomyLevel.wireValue,
        "coordination_role" to coordinationRole.wireValue,
        "readiness_state" to readinessState.wireValue,
        "participation_state" to participationState.wireValue,
        "supports_delegation" to supportsDelegation,
        "supports_attached_session" to supportsAttachedSession,
        "supports_local_execution" to supportsLocalExecution,
        "supports_command_execution" to supportsCommandExecution,
        "device_linkage" to mapOf(
            "device_id" to deviceLinkage.deviceId,
            "runtime_host_id" to deviceLinkage.runtimeHostId
        ),
        "capability_linkage" to mapOf(
            "capability_refs" to capabilityLinkage.capabilityRefs.toList()
        ),
        "session_linkage" to mapOf(
            "attached_session_id" to sessionLinkage.attachedSessionId,
            "runtime_session_id" to sessionLinkage.runtimeSessionId,
            "source_runtime_posture" to sessionLinkage.sourceRuntimePosture
        ),
        "truth_boundary" to mapOf(
            "owns_runtime_attachment_truth" to truthBoundary.ownsRuntimeAttachmentTruth,
            "owns_delegated_selection_truth" to truthBoundary.ownsDelegatedSelectionTruth,
            "owns_reconnect_lifecycle_truth" to truthBoundary.ownsReconnectLifecycleTruth
        )
    )
}

object AndroidParticipantModelMapper {
    /**
     * Builds an additive participant **projection/read-model** from runtime-host signals.
     *
     * This mapper does not become an authoritative lifecycle owner. Canonical attached-session,
     * delegated-readiness, and reconnect lifecycle truth remain owned by RuntimeController
     * projections (`hostSessionSnapshot`, `targetReadinessProjection`, `reconnectRecoveryState`).
     */
    fun fromRuntimeHostDescriptor(
        descriptor: RuntimeHostDescriptor,
        hostSessionSnapshot: AttachedRuntimeHostSessionSnapshot? = null,
        readinessProjection: DelegatedTargetReadinessProjection? = null,
        capabilityRefs: Set<String> = emptySet()
    ): CanonicalParticipantModel = CanonicalParticipantModel(
        participantId = RuntimeIdentityContracts.participantNodeId(
            deviceId = descriptor.deviceId,
            runtimeHostId = descriptor.hostId
        ),
        participantKind = ParticipantKind.ANDROID_RUNTIME_HOST,
        runtimeTier = RuntimeTierContracts.participantTierFor(
            DeviceRuntimeHostStrength.FULL_RUNTIME_HOST
        ),
        autonomyLevel = ParticipantAutonomyLevel.HIGH_AUTONOMY,
        coordinationRole = coordinationRoleFrom(descriptor.formationRole),
        readinessState = readinessStateFrom(descriptor, readinessProjection),
        participationState = descriptor.participationState,
        supportsDelegation = true,
        supportsAttachedSession = true,
        supportsLocalExecution = true,
        supportsCommandExecution = true,
        deviceLinkage = ParticipantDeviceLinkage(
            deviceId = descriptor.deviceId,
            runtimeHostId = descriptor.hostId
        ),
        capabilityLinkage = ParticipantCapabilityLinkage(capabilityRefs),
        sessionLinkage = ParticipantSessionLinkage(
            attachedSessionId = hostSessionSnapshot?.sessionId,
            runtimeSessionId = hostSessionSnapshot?.runtimeSessionId,
            sourceRuntimePosture = hostSessionSnapshot?.posture
        )
    )

    private fun coordinationRoleFrom(
        formationRole: RuntimeHostDescriptor.FormationRole
    ): ParticipantCoordinationRole = when (formationRole) {
        RuntimeHostDescriptor.FormationRole.PRIMARY -> ParticipantCoordinationRole.COORDINATOR
        RuntimeHostDescriptor.FormationRole.SECONDARY,
        RuntimeHostDescriptor.FormationRole.SATELLITE -> ParticipantCoordinationRole.PARTICIPANT
    }

    private fun readinessStateFrom(
        descriptor: RuntimeHostDescriptor,
        readinessProjection: DelegatedTargetReadinessProjection?
    ): ParticipantReadinessState {
        if (readinessProjection == null) {
            return if (descriptor.participationState == RuntimeHostDescriptor.HostParticipationState.INACTIVE) {
                ParticipantReadinessState.NOT_READY
            } else {
                ParticipantReadinessState.UNKNOWN
            }
        }
        return when (readinessProjection.selectionOutcome) {
            TargetSelectionOutcome.SELECTED -> ParticipantReadinessState.READY
            TargetSelectionOutcome.FALLBACK -> ParticipantReadinessState.READY_WITH_FALLBACK
            TargetSelectionOutcome.REJECTED -> ParticipantReadinessState.NOT_READY
        }
    }
}
