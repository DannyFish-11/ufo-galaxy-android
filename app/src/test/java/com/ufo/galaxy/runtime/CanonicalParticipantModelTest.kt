package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalParticipantModelTest {

    @Test
    fun `runtime-tier enum explicitly includes lighter shared-model tiers`() {
        val availableTiers = ParticipantRuntimeTier.entries.map { it.wireValue }.toSet()
        assertTrue("full_runtime_host" in availableTiers)
        assertTrue("partial_runtime_node" in availableTiers)
        assertTrue("command_endpoint" in availableTiers)
        assertTrue("observer" in availableTiers)
    }

    private fun descriptor(
        formationRole: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState: RuntimeHostDescriptor.HostParticipationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    ): RuntimeHostDescriptor = RuntimeHostDescriptor(
        hostId = "host-1",
        deviceId = "device-1",
        deviceRole = "phone",
        formationRole = formationRole,
        participationState = participationState
    )

    private fun snapshotFor(
        hostRole: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY
    ): AttachedRuntimeHostSessionSnapshot {
        val session = AttachedRuntimeSession.create(
            hostId = "host-1",
            deviceId = "device-1",
            sessionId = "attached-1"
        )
        return AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = "runtime-1",
            hostRole = hostRole.wireValue
        )
    }

    @Test
    fun `maps Android runtime host to canonical participant semantics`() {
        val snapshot = snapshotFor()
        val readiness = DelegatedTargetReadinessProjection.from(snapshot)

        val participant = AndroidParticipantModelMapper.fromRuntimeHostDescriptor(
            descriptor = descriptor(),
            hostSessionSnapshot = snapshot,
            readinessProjection = readiness,
            capabilityRefs = setOf("autonomous_goal_execution", "cross_device_coordination")
        )

        assertEquals("device-1:host-1", participant.participantId)
        assertEquals(ParticipantKind.ANDROID_RUNTIME_HOST, participant.participantKind)
        assertEquals(ParticipantRuntimeTier.FULL_RUNTIME_HOST, participant.runtimeTier)
        assertEquals(ParticipantAutonomyLevel.HIGH_AUTONOMY, participant.autonomyLevel)
        assertEquals(ParticipantCoordinationRole.COORDINATOR, participant.coordinationRole)
        assertEquals(ParticipantReadinessState.READY, participant.readinessState)
        assertEquals(RuntimeHostDescriptor.HostParticipationState.ACTIVE, participant.participationState)
        assertTrue(participant.supportsDelegation)
        assertTrue(participant.supportsAttachedSession)
        assertTrue(participant.supportsLocalExecution)
        assertTrue(participant.supportsCommandExecution)
        assertEquals("device-1", participant.deviceLinkage.deviceId)
        assertEquals("host-1", participant.deviceLinkage.runtimeHostId)
        assertEquals(setOf("autonomous_goal_execution", "cross_device_coordination"), participant.capabilityLinkage.capabilityRefs)
        assertEquals("attached-1", participant.sessionLinkage.attachedSessionId)
        assertEquals("runtime-1", participant.sessionLinkage.runtimeSessionId)
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, participant.sessionLinkage.sourceRuntimePosture)
    }

    @Test
    fun `secondary host maps to participant coordination role and fallback readiness`() {
        val snapshot = snapshotFor(hostRole = RuntimeHostDescriptor.FormationRole.SECONDARY)
        val readiness = DelegatedTargetReadinessProjection.from(snapshot)

        val participant = AndroidParticipantModelMapper.fromRuntimeHostDescriptor(
            descriptor = descriptor(formationRole = RuntimeHostDescriptor.FormationRole.SECONDARY),
            hostSessionSnapshot = snapshot,
            readinessProjection = readiness
        )

        assertEquals(ParticipantCoordinationRole.PARTICIPANT, participant.coordinationRole)
        assertEquals(ParticipantReadinessState.READY_WITH_FALLBACK, participant.readinessState)
    }

    @Test
    fun `inactive host without readiness projection remains explicitly not ready`() {
        val participant = AndroidParticipantModelMapper.fromRuntimeHostDescriptor(
            descriptor = descriptor(
                formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
                participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
            ),
            hostSessionSnapshot = null,
            readinessProjection = null
        )

        assertEquals(ParticipantReadinessState.NOT_READY, participant.readinessState)
        assertNull(participant.sessionLinkage.attachedSessionId)
        assertNull(participant.sessionLinkage.runtimeSessionId)
        assertNull(participant.sessionLinkage.sourceRuntimePosture)
    }
}
