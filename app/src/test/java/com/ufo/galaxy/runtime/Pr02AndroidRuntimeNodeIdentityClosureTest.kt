package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Pr02AndroidRuntimeNodeIdentityClosureTest {

    private fun descriptor(
        role: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState: RuntimeHostDescriptor.HostParticipationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    ): RuntimeHostDescriptor = RuntimeHostDescriptor(
        hostId = "host-001",
        deviceId = "Pixel_8",
        deviceRole = "phone",
        formationRole = role,
        participationState = participationState
    )

    private fun sessionSnapshot(
        deviceId: String = "Pixel_8",
        hostRole: String = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue,
        posture: String = SourceRuntimePosture.JOIN_RUNTIME
    ): AttachedRuntimeHostSessionSnapshot = AttachedRuntimeHostSessionSnapshot(
        sessionId = "sess-001",
        deviceId = deviceId,
        runtimeSessionId = "rt-001",
        attachmentState = AttachedRuntimeSession.State.ATTACHED.wireValue,
        isReuseValid = true,
        delegatedExecutionCount = 2,
        invalidationReason = null,
        hostRole = hostRole,
        posture = posture
    )

    @Test
    fun `from preserves aligned attached runtime projection`() {
        val truth = AndroidParticipantRuntimeTruth.from(
            descriptor = descriptor(),
            sessionSnapshot = sessionSnapshot(),
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            carrierForegroundVisible = true,
            reconciliationEpoch = 1
        )

        assertEquals("sess-001", truth.sessionId)
        assertEquals(AttachedRuntimeSession.State.ATTACHED, truth.sessionState)
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, truth.sourceRuntimePosture)
        assertNotNull(truth.runtimeNodeIdentity)
        assertEquals(
            RuntimeNodeExecutionParticipationState.ELIGIBLE,
            truth.runtimeNodeIdentity?.executionParticipationState
        )
        assertEquals(
            RuntimeNodeCollaborationParticipationState.COORDINATING,
            truth.runtimeNodeIdentity?.collaborationParticipationState
        )
        assertEquals(
            RuntimeNodeManifestationParticipationState.INTERACTIVE_FOREGROUND,
            truth.runtimeNodeIdentity?.manifestationParticipationState
        )
        assertEquals(
            RuntimeNodeCapabilityTruthLevel.EXECUTION_CAPABLE,
            truth.runtimeNodeIdentity?.capabilityTruthLevel
        )
        assertEquals(
            RuntimeNodeAutonomyTruthLevel.SEMI_AUTONOMOUS_EXECUTION,
            truth.runtimeNodeIdentity?.autonomyTruthLevel
        )
        assertEquals(
            RuntimeNodeAuthorityBoundaryClass.ANDROID_PARTICIPANT_RUNTIME_ONLY,
            truth.runtimeNodeIdentity?.authorityBoundaryClass
        )
        assertEquals(
            RuntimeNodeControlSurfaceBoundaryClass.LOCAL_UI_CONTROL_SURFACE_ONLY,
            truth.runtimeNodeIdentity?.controlSurfaceBoundaryClass
        )
        assertEquals(
            RuntimeIdentityContracts.capabilityProviderRef("Pixel_8", "host-001"),
            truth.runtimeNodeIdentity?.capabilityProviderRef
        )
    }

    @Test
    fun `from drops session projection when snapshot device identity drifts`() {
        val truth = AndroidParticipantRuntimeTruth.from(
            descriptor = descriptor(),
            sessionSnapshot = sessionSnapshot(deviceId = "Pixel_7"),
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            reconciliationEpoch = 2
        )

        assertNull(truth.sessionId)
        assertNull(truth.sessionState)
        assertEquals(0, truth.delegatedExecutionCount)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, truth.sourceRuntimePosture)
        assertEquals(
            RuntimeNodeManifestationParticipationState.CONTROL_ONLY,
            truth.runtimeNodeIdentity?.manifestationParticipationState
        )
    }

    @Test
    fun `from drops session projection when snapshot host role drifts`() {
        val truth = AndroidParticipantRuntimeTruth.from(
            descriptor = descriptor(role = RuntimeHostDescriptor.FormationRole.PRIMARY),
            sessionSnapshot = sessionSnapshot(hostRole = RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue),
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            reconciliationEpoch = 3
        )

        assertNull(truth.sessionId)
        assertNull(truth.sessionState)
        assertEquals(0, truth.delegatedExecutionCount)
    }

    @Test
    fun `runtime node identity transitions to inactive blocked control_only when detached`() {
        val truth = AndroidParticipantRuntimeTruth.from(
            descriptor = descriptor(
                role = RuntimeHostDescriptor.FormationRole.SECONDARY,
                participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
            ),
            sessionSnapshot = null,
            healthState = ParticipantHealthState.RECOVERING,
            readinessState = ParticipantReadinessState.NOT_READY,
            carrierForegroundVisible = false,
            reconciliationEpoch = 4
        )

        assertEquals(
            RuntimeNodeExecutionParticipationState.BLOCKED,
            truth.runtimeNodeIdentity?.executionParticipationState
        )
        assertEquals(
            RuntimeNodeCollaborationParticipationState.INACTIVE,
            truth.runtimeNodeIdentity?.collaborationParticipationState
        )
        assertEquals(
            RuntimeNodeManifestationParticipationState.CONTROL_ONLY,
            truth.runtimeNodeIdentity?.manifestationParticipationState
        )
        assertEquals(
            ParticipantLifecycleTruthState.UNAVAILABLE,
            truth.runtimeNodeIdentity?.lifecycleState
        )
        assertEquals(
            RuntimeNodeCapabilityTruthLevel.UNAVAILABLE,
            truth.runtimeNodeIdentity?.capabilityTruthLevel
        )
        assertEquals(
            RuntimeNodeAutonomyTruthLevel.OBSERVATION_ONLY,
            truth.runtimeNodeIdentity?.autonomyTruthLevel
        )
    }

    @Test
    fun `runtime node identity map exports capability autonomy and authority boundaries`() {
        val truth = AndroidParticipantRuntimeTruth.from(
            descriptor = descriptor(),
            sessionSnapshot = sessionSnapshot(),
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            carrierForegroundVisible = true,
            reconciliationEpoch = 5
        )
        val map = truth.runtimeNodeIdentity!!.toMap()
        assertEquals(
            "capability_provider:Pixel_8:host-001",
            map[AndroidRuntimeNodeIdentity.KEY_CAPABILITY_PROVIDER_REF]
        )
        assertEquals(
            RuntimeNodeCapabilityTruthLevel.EXECUTION_CAPABLE.wireValue,
            map[AndroidRuntimeNodeIdentity.KEY_CAPABILITY_TRUTH_LEVEL]
        )
        assertEquals(
            RuntimeNodeAutonomyTruthLevel.SEMI_AUTONOMOUS_EXECUTION.wireValue,
            map[AndroidRuntimeNodeIdentity.KEY_AUTONOMY_TRUTH_LEVEL]
        )
        assertEquals(
            RuntimeNodeAuthorityBoundaryClass.ANDROID_PARTICIPANT_RUNTIME_ONLY.wireValue,
            map[AndroidRuntimeNodeIdentity.KEY_AUTHORITY_BOUNDARY_CLASS]
        )
        assertEquals(
            RuntimeNodeControlSurfaceBoundaryClass.LOCAL_UI_CONTROL_SURFACE_ONLY.wireValue,
            map[AndroidRuntimeNodeIdentity.KEY_CONTROL_SURFACE_BOUNDARY_CLASS]
        )
    }
}
