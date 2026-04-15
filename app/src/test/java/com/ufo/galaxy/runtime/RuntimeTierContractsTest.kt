package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeTierContractsTest {

    @Test
    fun `participant and device runtime tiers stay semantically aligned`() {
        val participantToDevice = ParticipantRuntimeTier.entries.associateWith {
            RuntimeTierContracts.deviceRuntimeStrengthFor(it)
        }
        val deviceToParticipant = DeviceRuntimeHostStrength.entries.associateWith {
            RuntimeTierContracts.participantTierFor(it)
        }

        assertEquals(
            mapOf(
                ParticipantRuntimeTier.FULL_RUNTIME_HOST to DeviceRuntimeHostStrength.FULL_RUNTIME_HOST,
                ParticipantRuntimeTier.PARTIAL_RUNTIME_NODE to DeviceRuntimeHostStrength.PARTIAL_RUNTIME_NODE,
                ParticipantRuntimeTier.COMMAND_ENDPOINT to DeviceRuntimeHostStrength.COMMAND_ENDPOINT,
                ParticipantRuntimeTier.OBSERVER to DeviceRuntimeHostStrength.OBSERVER
            ),
            participantToDevice
        )
        assertEquals(
            mapOf(
                DeviceRuntimeHostStrength.FULL_RUNTIME_HOST to ParticipantRuntimeTier.FULL_RUNTIME_HOST,
                DeviceRuntimeHostStrength.PARTIAL_RUNTIME_NODE to ParticipantRuntimeTier.PARTIAL_RUNTIME_NODE,
                DeviceRuntimeHostStrength.COMMAND_ENDPOINT to ParticipantRuntimeTier.COMMAND_ENDPOINT,
                DeviceRuntimeHostStrength.OBSERVER to ParticipantRuntimeTier.OBSERVER
            ),
            deviceToParticipant
        )
    }
}
