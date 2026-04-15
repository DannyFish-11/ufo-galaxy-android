package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalCapabilityProviderModelTest {

    private fun descriptor(): RuntimeHostDescriptor = RuntimeHostDescriptor(
        hostId = "host-1",
        deviceId = "device-1",
        deviceRole = "phone",
        formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    )

    private fun snapshotFor(): AttachedRuntimeHostSessionSnapshot {
        val session = AttachedRuntimeSession.create(
            hostId = "host-1",
            deviceId = "device-1",
            sessionId = "attached-1"
        )
        return AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = "runtime-1",
            hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
        )
    }

    @Test
    fun `maps runtime host to additive capability provider contract`() {
        val provider = AndroidCapabilityProviderModelMapper.fromRuntimeHostDescriptor(
            descriptor = descriptor(),
            hostSessionSnapshot = snapshotFor(),
            supportedActions = setOf("screen_capture", "app_control"),
            capabilityRefs = setOf("autonomous_goal_execution"),
            metadataKeys = setOf("model_ready", "overlay_ready")
        )

        assertEquals(
            RuntimeIdentityContracts.capabilityProviderRef("device-1", "host-1"),
            provider.providerId
        )
        assertEquals(CapabilityProviderKind.ANDROID_RUNTIME_HOST_PROVIDER, provider.providerKind)
        assertEquals("device-1", provider.identityLinkage.deviceId)
        assertEquals("host-1", provider.identityLinkage.runtimeHostId)
        assertEquals("attached-1", provider.sessionLinkage.attachedSessionId)
        assertEquals("runtime-1", provider.sessionLinkage.runtimeSessionId)
        assertTrue(provider.publicationSurface.supportedActions.contains("screen_capture"))
        assertTrue(provider.publicationSurface.capabilityRefs.contains("autonomous_goal_execution"))
        assertTrue(provider.publicationSurface.metadataKeys.contains("model_ready"))
    }

    @Test
    fun `capability provider model keeps truth authority outside capability plane`() {
        val participant = AndroidParticipantModelMapper.fromRuntimeHostDescriptor(descriptor = descriptor())
        val provider = AndroidCapabilityProviderModelMapper.fromRuntimeHostDescriptor(descriptor = descriptor())

        assertNotEquals(participant.participantId, provider.providerId)
        assertFalse(provider.truthBoundary.ownsRuntimeIdentityTruth)
        assertFalse(provider.truthBoundary.ownsParticipantTruth)
        assertFalse(provider.truthBoundary.ownsAttachmentTruth)
        assertFalse(provider.truthBoundary.ownsReconnectTruth)
    }
}
