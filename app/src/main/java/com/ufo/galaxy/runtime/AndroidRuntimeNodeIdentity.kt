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

data class AndroidRuntimeNodeIdentity(
    val nodeId: String,
    val deviceId: String,
    val hostId: String,
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
    fun toMap(): Map<String, Any?> = mapOf(
        KEY_NODE_ID to nodeId,
        KEY_DEVICE_ID to deviceId,
        KEY_HOST_ID to hostId,
        KEY_FORMATION_ROLE to formationRole.wireValue,
        KEY_PARTICIPATION_STATE to participationState.wireValue,
        KEY_LIFECYCLE_STATE to lifecycleState.wireValue,
        KEY_COORDINATION_ROLE to coordinationRole.wireValue,
        KEY_SOURCE_RUNTIME_POSTURE to sourceRuntimePosture,
        KEY_ATTACHED_SESSION_ID to attachedSessionId,
        KEY_RUNTIME_SESSION_ID to runtimeSessionId,
        KEY_EXECUTION_PARTICIPATION_STATE to executionParticipationState.wireValue,
        KEY_COLLABORATION_PARTICIPATION_STATE to collaborationParticipationState.wireValue,
        KEY_MANIFESTATION_PARTICIPATION_STATE to manifestationParticipationState.wireValue
    )

    companion object {
        const val KEY_NODE_ID = "node_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_HOST_ID = "host_id"
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
    }
}
