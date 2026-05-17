package com.ufo.galaxy.runtime

/**
 * Participant-side Android entry-role contract aligned with the V2 canonical dispatch narrative.
 *
 * This contract does not introduce a new runtime framework. It records the existing Android
 * main-chain surfaces so their roles stay explicit and testable:
 * - [EntryRole.MAIN_ENTRY] — the sole Android participant main entry into the persistent service chain
 * - [EntryRole.SUB_ENTRY] — valid bootstrap or user-origin sub-entries that must delegate onward
 * - [EntryRole.STAGE_ENTRY] — lifecycle / dispatch / uplink stages inside the main chain
 * - [EntryRole.INTERNAL_ENTRY] — internal-only implementation surfaces
 * - [EntryRole.COMPAT_FALLBACK] — explicit compatibility or fallback entry points
 * - [EntryRole.LEGACY_ENTRY] — retained legacy bridges that must not present as main entry
 */
object AndroidMainchainEntryContract {

    enum class EntryRole(val wireValue: String) {
        MAIN_ENTRY("main_entry"),
        SUB_ENTRY("sub_entry"),
        STAGE_ENTRY("stage_entry"),
        INTERNAL_ENTRY("internal_entry"),
        COMPAT_FALLBACK("compat_fallback"),
        LEGACY_ENTRY("legacy_entry")
    }

    data class EntrySurface(
        val id: String,
        val role: EntryRole,
        val owner: String,
        val method: String,
        val delegatesTo: String? = null,
        val v2Counterpart: String? = null
    )

    const val MAIN_ENTRY_ID = "galaxy_connection_service_main_entry"
    const val LIFECYCLE_STAGE_ID = "runtime_controller_lifecycle_stage"
    const val INBOUND_DISPATCH_STAGE_ID = "galaxy_connection_service_inbound_dispatch_stage"
    const val UPLINK_STAGE_ID = "galaxy_connection_service_uplink_stage"
    const val WS_TRANSPORT_INTERNAL_ID = "galaxy_websocket_transport_internal_entry"

    val entries: List<EntrySurface> = listOf(
        EntrySurface(
            id = MAIN_ENTRY_ID,
            role = EntryRole.MAIN_ENTRY,
            owner = "com.ufo.galaxy.service.GalaxyConnectionService",
            method = "onStartCommand",
            delegatesTo = LIFECYCLE_STAGE_ID,
            v2Counterpart = "core.command_router.CommandRouter.route_envelope"
        ),
        EntrySurface(
            id = "main_activity_runtime_bootstrap",
            role = EntryRole.SUB_ENTRY,
            owner = "com.ufo.galaxy.ui.MainActivity",
            method = "startServices",
            delegatesTo = MAIN_ENTRY_ID
        ),
        EntrySurface(
            id = "boot_receiver_runtime_bootstrap",
            role = EntryRole.SUB_ENTRY,
            owner = "com.ufo.galaxy.service.BootReceiver",
            method = "onReceive",
            delegatesTo = MAIN_ENTRY_ID
        ),
        EntrySurface(
            id = "input_router_cross_device_submit",
            role = EntryRole.SUB_ENTRY,
            owner = "com.ufo.galaxy.input.InputRouter",
            method = "route",
            delegatesTo = WS_TRANSPORT_INTERNAL_ID,
            v2Counterpart = "core.command_router.CommandRouter.route_envelope"
        ),
        EntrySurface(
            id = LIFECYCLE_STAGE_ID,
            role = EntryRole.STAGE_ENTRY,
            owner = "com.ufo.galaxy.runtime.RuntimeController",
            method = "connectIfEnabled/startWithTimeout/stop/reconnect",
            delegatesTo = WS_TRANSPORT_INTERNAL_ID
        ),
        EntrySurface(
            id = INBOUND_DISPATCH_STAGE_ID,
            role = EntryRole.STAGE_ENTRY,
            owner = "com.ufo.galaxy.service.GalaxyConnectionService",
            method = "handleTaskAssign/handleGoalExecution/handleParallelSubtask",
            delegatesTo = "autonomous_execution_internal_entry",
            v2Counterpart = "galaxy_gateway.device_router.DeviceRouter.route_task"
        ),
        EntrySurface(
            id = UPLINK_STAGE_ID,
            role = EntryRole.STAGE_ENTRY,
            owner = "com.ufo.galaxy.service.GalaxyConnectionService",
            method = "sendDeviceStateSnapshot/sendGoalResult",
            delegatesTo = WS_TRANSPORT_INTERNAL_ID,
            v2Counterpart = "galaxy_gateway.cross_device_coordinator.CrossDeviceCoordinator.execute_cross_device_task"
        ),
        EntrySurface(
            id = WS_TRANSPORT_INTERNAL_ID,
            role = EntryRole.INTERNAL_ENTRY,
            owner = "com.ufo.galaxy.network.GalaxyWebSocketClient",
            method = "connect/disconnect/sendHandshake/sendJson"
        ),
        EntrySurface(
            id = "autonomous_execution_internal_entry",
            role = EntryRole.INTERNAL_ENTRY,
            owner = "com.ufo.galaxy.agent.AutonomousExecutionPipeline",
            method = "handleGoalExecution/handleParallelSubtask"
        ),
        EntrySurface(
            id = "agent_runtime_bridge_compat_fallback",
            role = EntryRole.COMPAT_FALLBACK,
            owner = "com.ufo.galaxy.agent.AgentRuntimeBridge",
            method = "handoff",
            delegatesTo = INBOUND_DISPATCH_STAGE_ID,
            v2Counterpart = "core.capability_orchestrator.CapabilityOrchestrator._execute_builtin"
        ),
        EntrySurface(
            id = "registration_error_legacy_bridge",
            role = EntryRole.LEGACY_ENTRY,
            owner = "com.ufo.galaxy.runtime.RuntimeController",
            method = "registrationError",
            delegatesTo = "setupError"
        ),
        EntrySurface(
            id = "floating_window_service_legacy_entry",
            role = EntryRole.LEGACY_ENTRY,
            owner = "com.ufo.galaxy.service.FloatingWindowService",
            method = "onCreate"
        )
    )

    val soleMainEntry: EntrySurface
        get() = entries.single { it.role == EntryRole.MAIN_ENTRY }

    fun entriesForRole(role: EntryRole): List<EntrySurface> =
        entries.filter { it.role == role }

    fun surface(id: String): EntrySurface? =
        entries.firstOrNull { it.id == id }

    val operabilityStageBindings:
        Map<AndroidMinimalOperabilityContract.OperabilityPathStep, String> = mapOf(
            AndroidMinimalOperabilityContract.OperabilityPathStep.ESTABLISH_WS_CONNECTION to
                LIFECYCLE_STAGE_ID,
            AndroidMinimalOperabilityContract.OperabilityPathStep.REPORT_DEVICE_TRUTH to
                UPLINK_STAGE_ID,
            AndroidMinimalOperabilityContract.OperabilityPathStep.RECEIVE_DELEGATED_TASK to
                INBOUND_DISPATCH_STAGE_ID,
            AndroidMinimalOperabilityContract.OperabilityPathStep.UPLINK_RESULT to
                UPLINK_STAGE_ID
        )
}
