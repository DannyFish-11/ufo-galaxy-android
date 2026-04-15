package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalDeviceModelTest {

    private fun descriptor(): RuntimeHostDescriptor = RuntimeHostDescriptor(
        hostId = "host-1",
        deviceId = "device-1",
        deviceRole = "phone",
        formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    )

    @Test
    fun `device category enum keeps architecture-neutral non-android options explicit`() {
        val categories = DeviceCategory.entries.map { it.wireValue }.toSet()

        assertTrue("android_full_runtime_host" in categories)
        assertTrue("aerial_drone" in categories)
        assertTrue("fabrication_3d_printer" in categories)
        assertTrue("embedded_controller" in categories)
        assertTrue("smart_home_endpoint" in categories)
        assertTrue("specialized_endpoint" in categories)
    }

    @Test
    fun `maps Android runtime host as strong device category with separated participant identity`() {
        val device = AndroidDeviceModelMapper.fromRuntimeHostDescriptor(descriptor())

        assertEquals("device-1", device.deviceId)
        assertEquals(DeviceCategory.ANDROID_FULL_RUNTIME_HOST, device.deviceCategory)
        assertEquals("host-1", device.runtimeHostId)
        assertEquals("device-1:host-1", device.linkedParticipantId)
        assertEquals(DeviceRuntimeHostStrength.FULL_RUNTIME_HOST, device.runtimeHostSemantics.runtimeStrength)
        assertTrue(device.runtimeHostSemantics.supportsRuntimeHostExecution)
        assertFalse(device.runtimeHostSemantics.isDefaultAssumptionForAllDevices)
    }
}
