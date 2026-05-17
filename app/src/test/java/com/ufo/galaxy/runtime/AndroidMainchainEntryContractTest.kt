package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.AgentRuntimeBridge
import com.ufo.galaxy.input.InputRouter
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.service.BootReceiver
import com.ufo.galaxy.service.FloatingWindowService
import com.ufo.galaxy.service.GalaxyConnectionService
import com.ufo.galaxy.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMainchainEntryContractTest {

    private val contract = AndroidMainchainEntryContract

    @Test
    fun `sole main entry remains GalaxyConnectionService`() {
        val mainEntry = contract.soleMainEntry

        assertEquals(AndroidMainchainEntryContract.MAIN_ENTRY_ID, mainEntry.id)
        assertEquals("com.ufo.galaxy.service.GalaxyConnectionService", mainEntry.owner)
        assertEquals("onStartCommand", mainEntry.method)
        assertEquals(GalaxyConnectionService.ENTRYPOINT_ROLE, mainEntry.role.wireValue)
    }

    @Test
    fun `sub entries stay subordinate to canonical main entry or transport stage`() {
        val subEntries = contract.entriesForRole(AndroidMainchainEntryContract.EntryRole.SUB_ENTRY)

        assertEquals(3, subEntries.size)
        assertTrue(subEntries.any {
            it.owner == "com.ufo.galaxy.ui.MainActivity" &&
                it.delegatesTo == AndroidMainchainEntryContract.MAIN_ENTRY_ID &&
                MainActivity.ENTRYPOINT_ROLE == it.role.wireValue
        })
        assertTrue(subEntries.any {
            it.owner == "com.ufo.galaxy.service.BootReceiver" &&
                it.delegatesTo == AndroidMainchainEntryContract.MAIN_ENTRY_ID &&
                BootReceiver.ENTRYPOINT_ROLE == it.role.wireValue
        })
        assertTrue(subEntries.any {
            it.owner == "com.ufo.galaxy.input.InputRouter" &&
                it.delegatesTo == AndroidMainchainEntryContract.WS_TRANSPORT_INTERNAL_ID &&
                InputRouter.ENTRYPOINT_ROLE == it.role.wireValue
        })
    }

    @Test
    fun `stage and internal entries keep lifecycle dispatch and uplink roles separated`() {
        val lifecycleStage = contract.surface(AndroidMainchainEntryContract.LIFECYCLE_STAGE_ID)
        val inboundStage = contract.surface(AndroidMainchainEntryContract.INBOUND_DISPATCH_STAGE_ID)
        val uplinkStage = contract.surface(AndroidMainchainEntryContract.UPLINK_STAGE_ID)
        val wsInternal = contract.surface(AndroidMainchainEntryContract.WS_TRANSPORT_INTERNAL_ID)

        assertNotNull(lifecycleStage)
        assertNotNull(inboundStage)
        assertNotNull(uplinkStage)
        assertNotNull(wsInternal)

        assertEquals(RuntimeController.ENTRYPOINT_ROLE, lifecycleStage!!.role.wireValue)
        assertEquals("galaxy_gateway.device_router.DeviceRouter.route_task", inboundStage!!.v2Counterpart)
        assertEquals(
            "galaxy_gateway.cross_device_coordinator.CrossDeviceCoordinator.execute_cross_device_task",
            uplinkStage!!.v2Counterpart
        )
        assertEquals(GalaxyWebSocketClient.ENTRYPOINT_ROLE, wsInternal!!.role.wireValue)
    }

    @Test
    fun `compat and legacy surfaces stay visible but cannot claim main entry`() {
        val compat = contract.entriesForRole(AndroidMainchainEntryContract.EntryRole.COMPAT_FALLBACK)
        val legacy = contract.entriesForRole(AndroidMainchainEntryContract.EntryRole.LEGACY_ENTRY)

        assertEquals(1, compat.size)
        assertEquals(
            "core.capability_orchestrator.CapabilityOrchestrator._execute_builtin",
            compat.single().v2Counterpart
        )
        assertEquals(AgentRuntimeBridge.ENTRYPOINT_ROLE, compat.single().role.wireValue)

        assertEquals(2, legacy.size)
        assertTrue(legacy.any {
            it.owner == "com.ufo.galaxy.runtime.RuntimeController" && it.method == "registrationError"
        })
        assertTrue(legacy.any {
            it.owner == "com.ufo.galaxy.service.FloatingWindowService" &&
                FloatingWindowService.ENTRYPOINT_ROLE == it.role.wireValue
        })
        assertTrue(legacy.none { it.id == AndroidMainchainEntryContract.MAIN_ENTRY_ID })
    }

    @Test
    fun `operability stages stay bound to the same canonical runtime chain`() {
        val bindings = contract.operabilityStageBindings

        assertEquals(
            AndroidMainchainEntryContract.LIFECYCLE_STAGE_ID,
            bindings[AndroidMinimalOperabilityContract.OperabilityPathStep.ESTABLISH_WS_CONNECTION]
        )
        assertEquals(
            AndroidMainchainEntryContract.UPLINK_STAGE_ID,
            bindings[AndroidMinimalOperabilityContract.OperabilityPathStep.REPORT_DEVICE_TRUTH]
        )
        assertEquals(
            AndroidMainchainEntryContract.INBOUND_DISPATCH_STAGE_ID,
            bindings[AndroidMinimalOperabilityContract.OperabilityPathStep.RECEIVE_DELEGATED_TASK]
        )
        assertEquals(
            AndroidMainchainEntryContract.UPLINK_STAGE_ID,
            bindings[AndroidMinimalOperabilityContract.OperabilityPathStep.UPLINK_RESULT]
        )
    }
}
