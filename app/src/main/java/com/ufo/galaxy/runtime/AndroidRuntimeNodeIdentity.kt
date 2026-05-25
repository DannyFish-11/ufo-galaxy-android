package com.ufo.galaxy.runtime

enum class RuntimeNodeExecutionParticipationState(val wireValue: String) {
    ELIGIBLE("eligible"),
    DEGRADED("degraded"),
    BLOCKED("blocked")
}

enum class RuntimeNodeCollaborationParticipationState(val wireValue: String) {
    COORDINATING("coordinating"),
    PARTICIPATING("participating"),
    INACTIVE("inactive")
}

enum class RuntimeNodeManifestationParticipationState(val wireValue: String) {
    INTERACTIVE_FOREGROUND("interactive_foreground"),
    BACKGROUND_CARRIER("background_carrier"),
    CONTROL_ONLY("control_only")
}

enum class RuntimeNodeCapabilityTruthLevel(val wireValue: String) {
    CONNECTED_OBSERVABILITY_ONLY("connected_observability_only"),
    PARTICIPANT_RUNTIME_CAPABLE("participant_runtime_capable"),
    EXECUTION_CAPABLE("execution_capable"),
    EXECUTION_DEGRADED("execution_degraded"),
    UNAVAILABLE("unavailable")
}

enum class RuntimeNodeAutonomyTruthLevel(val wireValue: String) {
    OBSERVATION_ONLY("observation_only"),
    ASSISTED_PARTICIPANT("assisted_participant"),
    SEMI_AUTONOMOUS_EXECUTION("semi_autonomous_execution")
}

enum class RuntimeNodeAuthorityBoundaryClass(val wireValue: String) {
    ANDROID_PARTICIPANT_RUNTIME_ONLY("android_participant_runtime_only"),
    V2_CANONICAL_AUTHORITY_EXTERNAL("v2_canonical_authority_external")
}

enum class RuntimeNodeControlSurfaceBoundaryClass(val wireValue: String) {
    LOCAL_UI_CONTROL_SURFACE_ONLY("local_ui_control_surface_only"),
    NOT_CANONICAL_GLOBAL_CONTROL("not_canonical_global_control")
}

data class AndroidRuntimeNodeIdentity(
    val nodeId: String,
    val deviceId: String,
    val hostId: String,
    val capabilityProviderRef: String = RuntimeIdentityContracts.capabilityProviderRef(
        deviceId = deviceId,
        runtimeHostId = hostId
    ),
    val formationRole: RuntimeHostDescriptor.FormationRole,
    val participationState: RuntimeHostDescriptor.HostParticipationState,
    val lifecycleState: ParticipantLifecycleTruthState,
    val coordinationRole: ParticipantCoordinationRole,
    val sourceRuntimePosture: String,
    val attachedSessionId: String?,
    val runtimeSessionId: String?,
    val executionParticipationState: RuntimeNodeExecutionParticipationState,
    val collaborationParticipationState: RuntimeNodeCollaborationParticipationState,
    val manifestationParticipationState: RuntimeNodeManifestationParticipationState
) {
    val capabilityTruthLevel: RuntimeNodeCapabilityTruthLevel
        get() = deriveCapabilityTruthLevel(
            participationState = participationState,
            sourceRuntimePosture = sourceRuntimePosture,
            executionParticipationState = executionParticipationState,
            collaborationParticipationState = collaborationParticipationState
        )

    val autonomyTruthLevel: RuntimeNodeAutonomyTruthLevel
        get() = deriveAutonomyTruthLevel(
            sourceRuntimePosture = sourceRuntimePosture,
            executionParticipationState = executionParticipationState,
            collaborationParticipationState = collaborationParticipationState
        )

    val authorityBoundaryClass: RuntimeNodeAuthorityBoundaryClass
        get() = RuntimeNodeAuthorityBoundaryClass.ANDROID_PARTICIPANT_RUNTIME_ONLY

    val canonicalAuthorityBoundaryClass: RuntimeNodeAuthorityBoundaryClass
        get() = RuntimeNodeAuthorityBoundaryClass.V2_CANONICAL_AUTHORITY_EXTERNAL

    val controlSurfaceBoundaryClass: RuntimeNodeControlSurfaceBoundaryClass
        get() = RuntimeNodeControlSurfaceBoundaryClass.LOCAL_UI_CONTROL_SURFACE_ONLY

    val canonicalControlBoundaryClass: RuntimeNodeControlSurfaceBoundaryClass
        get() = RuntimeNodeControlSurfaceBoundaryClass.NOT_CANONICAL_GLOBAL_CONTROL

    fun toMap(): Map<String, Any?> = mapOf(
        KEY_NODE_ID to nodeId,
        KEY_DEVICE_ID to deviceId,
        KEY_HOST_ID to hostId,
        KEY_CAPABILITY_PROVIDER_REF to capabilityProviderRef,
        KEY_FORMATION_ROLE to formationRole.wireValue,
        KEY_PARTICIPATION_STATE to participationState.wireValue,
        KEY_LIFECYCLE_STATE to lifecycleState.wireValue,
        KEY_COORDINATION_ROLE to coordinationRole.wireValue,
        KEY_SOURCE_RUNTIME_POSTURE to sourceRuntimePosture,
        KEY_ATTACHED_SESSION_ID to attachedSessionId,
        KEY_RUNTIME_SESSION_ID to runtimeSessionId,
        KEY_EXECUTION_PARTICIPATION_STATE to executionParticipationState.wireValue,
        KEY_COLLABORATION_PARTICIPATION_STATE to collaborationParticipationState.wireValue,
        KEY_MANIFESTATION_PARTICIPATION_STATE to manifestationParticipationState.wireValue,
        KEY_CAPABILITY_TRUTH_LEVEL to capabilityTruthLevel.wireValue,
        KEY_AUTONOMY_TRUTH_LEVEL to autonomyTruthLevel.wireValue,
        KEY_AUTHORITY_BOUNDARY_CLASS to authorityBoundaryClass.wireValue,
        KEY_CANONICAL_AUTHORITY_BOUNDARY_CLASS to canonicalAuthorityBoundaryClass.wireValue,
        KEY_CONTROL_SURFACE_BOUNDARY_CLASS to controlSurfaceBoundaryClass.wireValue,
        KEY_CANONICAL_CONTROL_BOUNDARY_CLASS to canonicalControlBoundaryClass.wireValue
    )

    companion object {
        const val KEY_NODE_ID = "node_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_HOST_ID = "host_id"
        const val KEY_CAPABILITY_PROVIDER_REF = "capability_provider_ref"
        const val KEY_FORMATION_ROLE = "formation_role"
        const val KEY_PARTICIPATION_STATE = "participation_state"
        const val KEY_LIFECYCLE_STATE = "lifecycle_state"
        const val KEY_COORDINATION_ROLE = "coordination_role"
        const val KEY_SOURCE_RUNTIME_POSTURE = "source_runtime_posture"
        const val KEY_ATTACHED_SESSION_ID = "attached_session_id"
        const val KEY_RUNTIME_SESSION_ID = "runtime_session_id"
        const val KEY_EXECUTION_PARTICIPATION_STATE = "execution_participation_state"
        const val KEY_COLLABORATION_PARTICIPATION_STATE = "collaboration_participation_state"
        const val KEY_MANIFESTATION_PARTICIPATION_STATE = "manifestation_participation_state"
        const val KEY_CAPABILITY_TRUTH_LEVEL = "capability_truth_level"
        const val KEY_AUTONOMY_TRUTH_LEVEL = "autonomy_truth_level"
        const val KEY_AUTHORITY_BOUNDARY_CLASS = "authority_boundary_class"
        const val KEY_CANONICAL_AUTHORITY_BOUNDARY_CLASS = "canonical_authority_boundary_class"
        const val KEY_CONTROL_SURFACE_BOUNDARY_CLASS = "control_surface_boundary_class"
        const val KEY_CANONICAL_CONTROL_BOUNDARY_CLASS = "canonical_control_boundary_class"

        fun from(
            descriptor: RuntimeHostDescriptor,
            sessionSnapshot: AttachedRuntimeHostSessionSnapshot?,
            healthState: ParticipantHealthState,
            readinessState: ParticipantReadinessState,
            carrierForegroundVisible: Boolean? = null
        ): AndroidRuntimeNodeIdentity {
            val nodeId = RuntimeIdentityContracts.participantNodeId(
                deviceId = descriptor.deviceId,
                runtimeHostId = descriptor.hostId
            )
            val coordinationRole = when (descriptor.formationRole) {
                RuntimeHostDescriptor.FormationRole.PRIMARY -> ParticipantCoordinationRole.COORDINATOR
                RuntimeHostDescriptor.FormationRole.SECONDARY,
                RuntimeHostDescriptor.FormationRole.SATELLITE -> ParticipantCoordinationRole.PARTICIPANT
            }
            val lifecycleState = if (descriptor.participationState == RuntimeHostDescriptor.HostParticipationState.INACTIVE &&
                sessionSnapshot == null
            ) {
                ParticipantLifecycleTruthState.UNAVAILABLE
            } else {
                ParticipantLifecycleTruthState.fromFormal(
                    FormalParticipantLifecycleState.fromHealthState(healthState)
                )
            }
            val executionParticipationState =
                deriveExecutionParticipationState(
                    descriptor = descriptor,
                    sessionSnapshot = sessionSnapshot,
                    healthState = healthState,
                    readinessState = readinessState
                )
            val collaborationParticipationState =
                deriveCollaborationParticipationState(descriptor, sessionSnapshot)
            val manifestationParticipationState =
                deriveManifestationParticipationState(sessionSnapshot, carrierForegroundVisible)

            return AndroidRuntimeNodeIdentity(
                nodeId = nodeId,
                deviceId = descriptor.deviceId,
                hostId = descriptor.hostId,
                formationRole = descriptor.formationRole,
                participationState = descriptor.participationState,
                lifecycleState = lifecycleState,
                coordinationRole = coordinationRole,
                sourceRuntimePosture = sessionSnapshot?.posture ?: SourceRuntimePosture.CONTROL_ONLY,
                attachedSessionId = sessionSnapshot?.sessionId,
                runtimeSessionId = sessionSnapshot?.runtimeSessionId,
                executionParticipationState = executionParticipationState,
                collaborationParticipationState = collaborationParticipationState,
                manifestationParticipationState = manifestationParticipationState
            )
        }

        private fun deriveExecutionParticipationState(
            descriptor: RuntimeHostDescriptor,
            sessionSnapshot: AttachedRuntimeHostSessionSnapshot?,
            healthState: ParticipantHealthState,
            readinessState: ParticipantReadinessState
        ): RuntimeNodeExecutionParticipationState {
            if (descriptor.participationState != RuntimeHostDescriptor.HostParticipationState.ACTIVE ||
                sessionSnapshot?.attachmentState != AttachedRuntimeSession.State.ATTACHED.wireValue
            ) {
                return RuntimeNodeExecutionParticipationState.BLOCKED
            }
            if (healthState == ParticipantHealthState.DEGRADED ||
                readinessState == ParticipantReadinessState.READY_WITH_FALLBACK
            ) {
                return RuntimeNodeExecutionParticipationState.DEGRADED
            }
            return if (healthState == ParticipantHealthState.HEALTHY &&
                readinessState == ParticipantReadinessState.READY
            ) {
                RuntimeNodeExecutionParticipationState.ELIGIBLE
            } else {
                RuntimeNodeExecutionParticipationState.BLOCKED
            }
        }

        private fun deriveCollaborationParticipationState(
            descriptor: RuntimeHostDescriptor,
            sessionSnapshot: AttachedRuntimeHostSessionSnapshot?
        ): RuntimeNodeCollaborationParticipationState {
            if (descriptor.participationState == RuntimeHostDescriptor.HostParticipationState.INACTIVE ||
                sessionSnapshot == null
            ) {
                return RuntimeNodeCollaborationParticipationState.INACTIVE
            }
            return if (descriptor.formationRole == RuntimeHostDescriptor.FormationRole.PRIMARY) {
                RuntimeNodeCollaborationParticipationState.COORDINATING
            } else {
                RuntimeNodeCollaborationParticipationState.PARTICIPATING
            }
        }

        private fun deriveManifestationParticipationState(
            sessionSnapshot: AttachedRuntimeHostSessionSnapshot?,
            carrierForegroundVisible: Boolean?
        ): RuntimeNodeManifestationParticipationState {
            if (sessionSnapshot?.posture != SourceRuntimePosture.JOIN_RUNTIME) {
                return RuntimeNodeManifestationParticipationState.CONTROL_ONLY
            }
            return if (carrierForegroundVisible == true) {
                RuntimeNodeManifestationParticipationState.INTERACTIVE_FOREGROUND
            } else {
                RuntimeNodeManifestationParticipationState.BACKGROUND_CARRIER
            }
        }

        private fun deriveCapabilityTruthLevel(
            participationState: RuntimeHostDescriptor.HostParticipationState,
            sourceRuntimePosture: String,
            executionParticipationState: RuntimeNodeExecutionParticipationState,
            collaborationParticipationState: RuntimeNodeCollaborationParticipationState
        ): RuntimeNodeCapabilityTruthLevel {
            if (participationState == RuntimeHostDescriptor.HostParticipationState.INACTIVE) {
                return RuntimeNodeCapabilityTruthLevel.UNAVAILABLE
            }
            if (sourceRuntimePosture != SourceRuntimePosture.JOIN_RUNTIME) {
                return RuntimeNodeCapabilityTruthLevel.CONNECTED_OBSERVABILITY_ONLY
            }
            return when (executionParticipationState) {
                RuntimeNodeExecutionParticipationState.ELIGIBLE ->
                    RuntimeNodeCapabilityTruthLevel.EXECUTION_CAPABLE
                RuntimeNodeExecutionParticipationState.DEGRADED ->
                    RuntimeNodeCapabilityTruthLevel.EXECUTION_DEGRADED
                RuntimeNodeExecutionParticipationState.BLOCKED -> {
                    if (collaborationParticipationState == RuntimeNodeCollaborationParticipationState.INACTIVE) {
                        RuntimeNodeCapabilityTruthLevel.CONNECTED_OBSERVABILITY_ONLY
                    } else {
                        RuntimeNodeCapabilityTruthLevel.PARTICIPANT_RUNTIME_CAPABLE
                    }
                }
            }
        }

        private fun deriveAutonomyTruthLevel(
            sourceRuntimePosture: String,
            executionParticipationState: RuntimeNodeExecutionParticipationState,
            collaborationParticipationState: RuntimeNodeCollaborationParticipationState
        ): RuntimeNodeAutonomyTruthLevel {
            if (sourceRuntimePosture != SourceRuntimePosture.JOIN_RUNTIME ||
                collaborationParticipationState == RuntimeNodeCollaborationParticipationState.INACTIVE
            ) {
                return RuntimeNodeAutonomyTruthLevel.OBSERVATION_ONLY
            }
            return when (executionParticipationState) {
                RuntimeNodeExecutionParticipationState.ELIGIBLE ->
                    RuntimeNodeAutonomyTruthLevel.SEMI_AUTONOMOUS_EXECUTION
                RuntimeNodeExecutionParticipationState.DEGRADED,
                RuntimeNodeExecutionParticipationState.BLOCKED ->
                    RuntimeNodeAutonomyTruthLevel.ASSISTED_PARTICIPANT
            }
        }
    }
}
