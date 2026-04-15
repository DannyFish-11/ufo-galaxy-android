package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeIdentityContractsTest {

    @Test
    fun `participant node id uses canonical device runtime-host linkage`() {
        assertEquals(
            "device-123:runtime-host-456",
            RuntimeIdentityContracts.participantNodeId(
                deviceId = "device-123",
                runtimeHostId = "runtime-host-456"
            )
        )
    }

    @Test
    fun `capability provider ref reuses canonical participant node id`() {
        assertEquals(
            "capability_provider:device-123:runtime-host-456",
            RuntimeIdentityContracts.capabilityProviderRef(
                deviceId = "device-123",
                runtimeHostId = "runtime-host-456"
            )
        )
    }
}
