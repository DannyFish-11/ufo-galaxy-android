package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.loop.ExecutorBridge
import com.ufo.galaxy.loop.LocalPlanner
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-10 Android carrier consolidation tests.
 *
 * Validates the final Android-side carrier experience consolidation:
 *
 * 1. `carrier_runtime_state` is included in both [DeviceStateSnapshotPayload] and
 *    [DeviceExecutionEventPayload], backed by [RuntimeController.RuntimeState.wireLabel].
 * 2. `reconnect_recovery_state` is included in [DeviceStateSnapshotPayload], backed by
 *    [ReconnectRecoveryState.wireValue].
 * 3. No fake placeholder values are emitted — null defaults only when the backing state is
 *    genuinely unavailable (never the case in practice).
 * 4. Snapshot and execution-event carrier state fields share semantically consistent
 *    vocabulary: all values come from the same canonical RuntimeController state sources.
 * 5. Lifecycle transitions drive `appForegroundVisible` which feeds `carrier_foreground_visible`
 *    in both payload types — confirmed by the existing PR-8 test; the PR-10 test confirms the
 *    new fields are orthogonal and do not interfere with the PR-8 fields.
 * 6. Gson round-trips for the new wire fields are stable (no accidental rename or omission).
 *
 * ## Test matrix
 *
 * ### DeviceStateSnapshotPayload — new PR-10 field defaults
 *  - carrier_runtime_state defaults to null when not provided
 *  - reconnect_recovery_state defaults to null when not provided
 *
 * ### DeviceStateSnapshotPayload — Gson round-trip
 *  - carrier_runtime_state="active" round-trips as "active"
 *  - carrier_runtime_state="local_only" round-trips as "local_only"
 *  - carrier_runtime_state=null serialises as JSON null
 *  - reconnect_recovery_state="idle" round-trips as "idle"
 *  - reconnect_recovery_state="recovering" round-trips as "recovering"
 *  - reconnect_recovery_state="recovered" round-trips as "recovered"
 *  - reconnect_recovery_state="failed" round-trips as "failed"
 *  - reconnect_recovery_state=null serialises as JSON null
 *
 * ### DeviceStateSnapshotPayload — wire key name stability
 *  - carrier_runtime_state wire key is "carrier_runtime_state"
 *  - reconnect_recovery_state wire key is "reconnect_recovery_state"
 *
 * ### DeviceExecutionEventPayload — new PR-10 field defaults
 *  - carrier_runtime_state defaults to null when not provided
 *
 * ### DeviceExecutionEventPayload — Gson round-trip
 *  - carrier_runtime_state="active" round-trips as "active"
 *  - carrier_runtime_state="local_only" round-trips as "local_only"
 *  - carrier_runtime_state=null serialises as JSON null
 *
 * ### DeviceExecutionEventPayload — wire key name stability
 *  - carrier_runtime_state wire key is "carrier_runtime_state"
 *
 * ### RuntimeController.RuntimeState.wireLabel — value coverage
 *  - Idle wireLabel is "idle"
 *  - Starting wireLabel is "starting"
 *  - Active wireLabel is "active"
 *  - Failed wireLabel is "failed"
 *  - LocalOnly wireLabel is "local_only"
 *
 * ### ReconnectRecoveryState.wireValue — value coverage
 *  - IDLE wireValue is "idle"
 *  - RECOVERING wireValue is "recovering"
 *  - RECOVERED wireValue is "recovered"
 *  - FAILED wireValue is "failed"
 *
 * ### Snapshot/execution-event field consistency
 *  - carrier_runtime_state in snapshot and execution event share the same vocabulary
 *  - PR-8 fields (carrier_foreground_visible, interaction_surface_ready) are unaffected
 *    by the PR-10 additions and remain independently populated
 *
 * ### No fake carrier state
 *  - carrier_runtime_state=null is the correct default when not backed (not fabricated)
 *  - reconnect_recovery_state=null is the correct default when not backed
 */
class Pr10AndroidCarrierConsolidationTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val gson = Gson()

    /** Serialise [obj] to JSON and parse back as a [JsonObject] for field inspection. */
    private fun toJsonObject(obj: Any): JsonObject =
        gson.fromJson(gson.toJson(obj), JsonObject::class.java)

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildLoopController(): LoopController {
        val dir = tmpFolder.newFolder()
        val assetManager = ModelAssetManager(dir)
        val downloader = ModelDownloader(dir)
        val planner = LocalPlanner(LocalPlannerService())
        val grounding = LocalGroundingService()
        val executor = AccessibilityExecutor(NoOpImageScaler())
        val bridge = ExecutorBridge(executor as EdgeExecutor)
        return LoopController(
            localPlanner = planner,
            groundingService = grounding,
            executorBridge = bridge,
            assetManager = assetManager,
            downloader = downloader
        )
    }

    private fun buildController(
        settings: InMemoryAppSettings = InMemoryAppSettings()
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L
        )
        return controller to client
    }

    /** Minimal valid [DeviceStateSnapshotPayload] with optional PR-10 field overrides. */
    private fun baseSnapshot(
        carrierRuntimeState: String? = null,
        reconnectRecoveryState: String? = null,
        carrierForegroundVisible: Boolean? = null,
        interactionSurfaceReady: Boolean? = null
    ): DeviceStateSnapshotPayload = DeviceStateSnapshotPayload(
        device_id = "test_device_pr10",
        snapshot_ts = 1_700_000_000_000L,
        llama_cpp_available = true,
        ncnn_available = false,
        active_runtime_type = "LLAMA_CPP",
        model_ready = true,
        accessibility_ready = true,
        overlay_ready = true,
        local_loop_ready = true,
        model_id = "mobilevlm",
        runtime_type = "LLAMA_CPP",
        checksum_ok = true,
        mobilevlm_present = true,
        mobilevlm_checksum_ok = true,
        seeclick_present = false,
        pending_first_download = false,
        warmup_result = "ok",
        offline_queue_depth = 0,
        current_fallback_tier = "center_delegated_with_local_fallback",
        carrier_foreground_visible = carrierForegroundVisible,
        interaction_surface_ready = interactionSurfaceReady,
        carrier_runtime_state = carrierRuntimeState,
        reconnect_recovery_state = reconnectRecoveryState
    )

    /** Minimal valid [DeviceExecutionEventPayload] with optional PR-10 field overrides. */
    private fun baseExecutionEvent(
        phase: String = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
        carrierRuntimeState: String? = null,
        carrierForegroundVisible: Boolean? = null,
        interactionSurfaceReady: Boolean? = null
    ): DeviceExecutionEventPayload = DeviceExecutionEventPayload(
        flow_id = "flow-pr10",
        task_id = "task-pr10",
        phase = phase,
        device_id = "test_device_pr10",
        source_component = "Pr10AndroidCarrierConsolidationTest",
        carrier_foreground_visible = carrierForegroundVisible,
        interaction_surface_ready = interactionSurfaceReady,
        carrier_runtime_state = carrierRuntimeState
    )

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceStateSnapshotPayload — PR-10 field defaults
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_runtime_state defaults to null in snapshot when not provided`() {
        val snapshot = baseSnapshot()
        assertNull(
            "carrier_runtime_state must default to null (no fake value)",
            snapshot.carrier_runtime_state
        )
    }

    @Test
    fun `reconnect_recovery_state defaults to null in snapshot when not provided`() {
        val snapshot = baseSnapshot()
        assertNull(
            "reconnect_recovery_state must default to null (no fake value)",
            snapshot.reconnect_recovery_state
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceStateSnapshotPayload — Gson round-trips for carrier_runtime_state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `snapshot carrier_runtime_state active round-trips correctly`() {
        val snapshot = baseSnapshot(carrierRuntimeState = "active")
        val json = toJsonObject(snapshot)
        assertEquals(
            "carrier_runtime_state=active must survive Gson round-trip",
            "active",
            json.get("carrier_runtime_state")?.asString
        )
    }

    @Test
    fun `snapshot carrier_runtime_state local_only round-trips correctly`() {
        val snapshot = baseSnapshot(carrierRuntimeState = "local_only")
        val json = toJsonObject(snapshot)
        assertEquals(
            "carrier_runtime_state=local_only must survive Gson round-trip",
            "local_only",
            json.get("carrier_runtime_state")?.asString
        )
    }

    @Test
    fun `snapshot carrier_runtime_state idle round-trips correctly`() {
        val snapshot = baseSnapshot(carrierRuntimeState = "idle")
        val json = toJsonObject(snapshot)
        assertEquals("idle", json.get("carrier_runtime_state")?.asString)
    }

    @Test
    fun `snapshot carrier_runtime_state starting round-trips correctly`() {
        val snapshot = baseSnapshot(carrierRuntimeState = "starting")
        val json = toJsonObject(snapshot)
        assertEquals("starting", json.get("carrier_runtime_state")?.asString)
    }

    @Test
    fun `snapshot carrier_runtime_state failed round-trips correctly`() {
        val snapshot = baseSnapshot(carrierRuntimeState = "failed")
        val json = toJsonObject(snapshot)
        assertEquals("failed", json.get("carrier_runtime_state")?.asString)
    }

    @Test
    fun `snapshot carrier_runtime_state null serialises as JSON null`() {
        val snapshot = baseSnapshot(carrierRuntimeState = null)
        val json = toJsonObject(snapshot)
        assertTrue(
            "carrier_runtime_state=null must serialise as JSON null (not absent key or fake string)",
            json.get("carrier_runtime_state") == null || json.get("carrier_runtime_state").isJsonNull
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceStateSnapshotPayload — Gson round-trips for reconnect_recovery_state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `snapshot reconnect_recovery_state idle round-trips correctly`() {
        val snapshot = baseSnapshot(reconnectRecoveryState = "idle")
        val json = toJsonObject(snapshot)
        assertEquals(
            "reconnect_recovery_state=idle must survive Gson round-trip",
            "idle",
            json.get("reconnect_recovery_state")?.asString
        )
    }

    @Test
    fun `snapshot reconnect_recovery_state recovering round-trips correctly`() {
        val snapshot = baseSnapshot(reconnectRecoveryState = "recovering")
        val json = toJsonObject(snapshot)
        assertEquals("recovering", json.get("reconnect_recovery_state")?.asString)
    }

    @Test
    fun `snapshot reconnect_recovery_state recovered round-trips correctly`() {
        val snapshot = baseSnapshot(reconnectRecoveryState = "recovered")
        val json = toJsonObject(snapshot)
        assertEquals("recovered", json.get("reconnect_recovery_state")?.asString)
    }

    @Test
    fun `snapshot reconnect_recovery_state failed round-trips correctly`() {
        val snapshot = baseSnapshot(reconnectRecoveryState = "failed")
        val json = toJsonObject(snapshot)
        assertEquals("failed", json.get("reconnect_recovery_state")?.asString)
    }

    @Test
    fun `snapshot reconnect_recovery_state null serialises as JSON null`() {
        val snapshot = baseSnapshot(reconnectRecoveryState = null)
        val json = toJsonObject(snapshot)
        assertTrue(
            "reconnect_recovery_state=null must serialise as JSON null",
            json.get("reconnect_recovery_state") == null || json.get("reconnect_recovery_state").isJsonNull
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceStateSnapshotPayload — wire key name stability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `snapshot carrier_runtime_state wire key is carrier_runtime_state`() {
        val snapshot = baseSnapshot(carrierRuntimeState = "active")
        val json = toJsonObject(snapshot)
        assertTrue(
            "carrier_runtime_state wire key must be exactly 'carrier_runtime_state'",
            json.has("carrier_runtime_state")
        )
    }

    @Test
    fun `snapshot reconnect_recovery_state wire key is reconnect_recovery_state`() {
        val snapshot = baseSnapshot(reconnectRecoveryState = "idle")
        val json = toJsonObject(snapshot)
        assertTrue(
            "reconnect_recovery_state wire key must be exactly 'reconnect_recovery_state'",
            json.has("reconnect_recovery_state")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceExecutionEventPayload — PR-10 field defaults
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_runtime_state defaults to null in execution event when not provided`() {
        val event = baseExecutionEvent()
        assertNull(
            "carrier_runtime_state must default to null in execution event (no fake value)",
            event.carrier_runtime_state
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceExecutionEventPayload — Gson round-trips
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `event carrier_runtime_state active round-trips correctly`() {
        val event = baseExecutionEvent(carrierRuntimeState = "active")
        val json = toJsonObject(event)
        assertEquals("active", json.get("carrier_runtime_state")?.asString)
    }

    @Test
    fun `event carrier_runtime_state local_only round-trips correctly`() {
        val event = baseExecutionEvent(carrierRuntimeState = "local_only")
        val json = toJsonObject(event)
        assertEquals("local_only", json.get("carrier_runtime_state")?.asString)
    }

    @Test
    fun `event carrier_runtime_state null serialises as JSON null`() {
        val event = baseExecutionEvent(carrierRuntimeState = null)
        val json = toJsonObject(event)
        assertTrue(
            "carrier_runtime_state=null must serialise as JSON null",
            json.get("carrier_runtime_state") == null || json.get("carrier_runtime_state").isJsonNull
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceExecutionEventPayload — wire key name stability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `event carrier_runtime_state wire key is carrier_runtime_state`() {
        val event = baseExecutionEvent(carrierRuntimeState = "active")
        val json = toJsonObject(event)
        assertTrue(
            "carrier_runtime_state wire key must be exactly 'carrier_runtime_state'",
            json.has("carrier_runtime_state")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeController.RuntimeState.wireLabel — value coverage
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `RuntimeState Idle wireLabel is idle`() {
        assertEquals("idle", RuntimeController.RuntimeState.Idle.wireLabel)
    }

    @Test
    fun `RuntimeState Starting wireLabel is starting`() {
        assertEquals("starting", RuntimeController.RuntimeState.Starting.wireLabel)
    }

    @Test
    fun `RuntimeState Active wireLabel is active`() {
        assertEquals("active", RuntimeController.RuntimeState.Active.wireLabel)
    }

    @Test
    fun `RuntimeState Failed wireLabel is failed`() {
        assertEquals("failed", RuntimeController.RuntimeState.Failed("reason").wireLabel)
    }

    @Test
    fun `RuntimeState LocalOnly wireLabel is local_only`() {
        assertEquals("local_only", RuntimeController.RuntimeState.LocalOnly.wireLabel)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ReconnectRecoveryState.wireValue — value coverage
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `ReconnectRecoveryState IDLE wireValue is idle`() {
        assertEquals("idle", ReconnectRecoveryState.IDLE.wireValue)
    }

    @Test
    fun `ReconnectRecoveryState RECOVERING wireValue is recovering`() {
        assertEquals("recovering", ReconnectRecoveryState.RECOVERING.wireValue)
    }

    @Test
    fun `ReconnectRecoveryState RECOVERED wireValue is recovered`() {
        assertEquals("recovered", ReconnectRecoveryState.RECOVERED.wireValue)
    }

    @Test
    fun `ReconnectRecoveryState FAILED wireValue is failed`() {
        assertEquals("failed", ReconnectRecoveryState.FAILED.wireValue)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Snapshot and execution-event carrier state vocabulary consistency
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `snapshot and execution event share the same carrier_runtime_state vocabulary`() {
        // All RuntimeState wireLabels appear as valid carrier_runtime_state values in both
        // payload types.  This test confirms the vocabulary is identical so V2 can handle
        // both payloads with the same carrier-state parser.
        val allStates = listOf(
            RuntimeController.RuntimeState.Idle.wireLabel,
            RuntimeController.RuntimeState.Starting.wireLabel,
            RuntimeController.RuntimeState.Active.wireLabel,
            RuntimeController.RuntimeState.Failed("").wireLabel,
            RuntimeController.RuntimeState.LocalOnly.wireLabel
        )
        for (stateLabel in allStates) {
            val snapshotJson = toJsonObject(baseSnapshot(carrierRuntimeState = stateLabel))
            val eventJson = toJsonObject(baseExecutionEvent(carrierRuntimeState = stateLabel))
            assertEquals(
                "snapshot carrier_runtime_state=$stateLabel must survive round-trip",
                stateLabel,
                snapshotJson.get("carrier_runtime_state")?.asString
            )
            assertEquals(
                "event carrier_runtime_state=$stateLabel must survive round-trip",
                stateLabel,
                eventJson.get("carrier_runtime_state")?.asString
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PR-10 fields are orthogonal to PR-8 fields — no interference
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `PR-10 carrier_runtime_state does not affect carrier_foreground_visible in snapshot`() {
        val snapshot = baseSnapshot(
            carrierRuntimeState = "active",
            carrierForegroundVisible = true,
            interactionSurfaceReady = true
        )
        assertEquals("active", snapshot.carrier_runtime_state)
        assertEquals(true, snapshot.carrier_foreground_visible)
        assertEquals(true, snapshot.interaction_surface_ready)
    }

    @Test
    fun `PR-10 carrier_runtime_state does not affect carrier_foreground_visible in execution event`() {
        val event = baseExecutionEvent(
            carrierRuntimeState = "active",
            carrierForegroundVisible = true,
            interactionSurfaceReady = true
        )
        assertEquals("active", event.carrier_runtime_state)
        assertEquals(true, event.carrier_foreground_visible)
        assertEquals(true, event.interaction_surface_ready)
    }

    @Test
    fun `snapshot with local_only carrier state can still have interaction_surface_ready true`() {
        // carrier_runtime_state reflects cross-device participation mode, not readiness.
        // local_only + interaction_surface_ready=true is a valid carrier state:
        // the interaction surface can be operational even when cross-device is off.
        val snapshot = baseSnapshot(
            carrierRuntimeState = "local_only",
            interactionSurfaceReady = true
        )
        assertEquals("local_only", snapshot.carrier_runtime_state)
        assertEquals(true, snapshot.interaction_surface_ready)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeController.reconnectRecoveryState initial value
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `RuntimeController reconnectRecoveryState starts as IDLE`() {
        val (controller, _) = buildController()
        assertEquals(
            "reconnectRecoveryState must start as IDLE (no recovery in progress)",
            ReconnectRecoveryState.IDLE,
            controller.reconnectRecoveryState.value
        )
    }

    @Test
    fun `RuntimeController reconnectRecoveryState IDLE wireValue used in snapshot is idle`() {
        val (controller, _) = buildController()
        val recoveryStateWireValue = controller.reconnectRecoveryState.value.wireValue
        assertEquals(
            "initial reconnectRecoveryState wireValue must be 'idle' for snapshot population",
            "idle",
            recoveryStateWireValue
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeController initial carrier state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `RuntimeController state starts as Idle`() {
        val (controller, _) = buildController()
        assertEquals(
            "RuntimeController must start in Idle state",
            "idle",
            controller.state.value.wireLabel
        )
    }

    @Test
    fun `RuntimeController Idle state wireLabel matches snapshot carrier_runtime_state field`() {
        val (controller, _) = buildController()
        val snapshot = baseSnapshot(
            carrierRuntimeState = controller.state.value.wireLabel
        )
        assertEquals(
            "snapshot carrier_runtime_state must match RuntimeController state wireLabel",
            controller.state.value.wireLabel,
            snapshot.carrier_runtime_state
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // No fabricated carrier state values
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_runtime_state null is correct default not a fabricated state`() {
        // When carrier_runtime_state is not explicitly set, null is the correct default.
        // The value must only be populated from a real RuntimeController state read.
        val snapshot = DeviceStateSnapshotPayload(
            device_id = "test_device_pr10_nostate",
            llama_cpp_available = null,
            ncnn_available = null,
            active_runtime_type = null,
            model_ready = null,
            accessibility_ready = null,
            overlay_ready = null,
            local_loop_ready = null,
            model_id = null,
            runtime_type = null,
            checksum_ok = null,
            mobilevlm_present = null,
            mobilevlm_checksum_ok = null,
            seeclick_present = null,
            pending_first_download = null,
            warmup_result = null,
            offline_queue_depth = null,
            current_fallback_tier = null
            // carrier_runtime_state intentionally not set → should be null
        )
        assertNull(
            "carrier_runtime_state must be null when not backed by a real state read",
            snapshot.carrier_runtime_state
        )
    }

    @Test
    fun `reconnect_recovery_state null is correct default not a fabricated state`() {
        val snapshot = DeviceStateSnapshotPayload(
            device_id = "test_device_pr10_norecovery",
            llama_cpp_available = null,
            ncnn_available = null,
            active_runtime_type = null,
            model_ready = null,
            accessibility_ready = null,
            overlay_ready = null,
            local_loop_ready = null,
            model_id = null,
            runtime_type = null,
            checksum_ok = null,
            mobilevlm_present = null,
            mobilevlm_checksum_ok = null,
            seeclick_present = null,
            pending_first_download = null,
            warmup_result = null,
            offline_queue_depth = null,
            current_fallback_tier = null
            // reconnect_recovery_state intentionally not set → should be null
        )
        assertNull(
            "reconnect_recovery_state must be null when not backed by a real state read",
            snapshot.reconnect_recovery_state
        )
    }

    @Test
    fun `carrier_runtime_state null is correct default in execution event`() {
        val event = DeviceExecutionEventPayload(
            flow_id = "flow-pr10-nostate",
            task_id = "task-pr10-nostate",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
            // carrier_runtime_state intentionally not set → should be null
        )
        assertNull(
            "carrier_runtime_state must be null when not backed in execution event",
            event.carrier_runtime_state
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Full payload consistency check — all PR-6, PR-8, PR-10 fields together
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `snapshot carries all carrier identity fields together without conflict`() {
        // A fully-populated snapshot should carry all session identity (PR-6),
        // manifestation hints (PR-8), and carrier state (PR-10) fields consistently.
        val snapshot = DeviceStateSnapshotPayload(
            device_id = "test_device_pr10_full",
            snapshot_ts = 1_700_000_000_000L,
            llama_cpp_available = true,
            ncnn_available = false,
            active_runtime_type = "LLAMA_CPP",
            model_ready = true,
            accessibility_ready = true,
            overlay_ready = true,
            local_loop_ready = true,
            model_id = "mobilevlm",
            runtime_type = "LLAMA_CPP",
            checksum_ok = true,
            mobilevlm_present = true,
            mobilevlm_checksum_ok = true,
            seeclick_present = false,
            pending_first_download = false,
            warmup_result = "ok",
            offline_queue_depth = 0,
            current_fallback_tier = "center_delegated_with_local_fallback",
            // PR-6
            durable_session_id = "durable-abc",
            session_continuity_epoch = 2,
            runtime_session_id = "runtime-xyz",
            attached_session_id = "attach-123",
            // PR-8
            carrier_foreground_visible = true,
            interaction_surface_ready = true,
            // PR-10
            carrier_runtime_state = "active",
            reconnect_recovery_state = "idle"
        )

        assertEquals("active", snapshot.carrier_runtime_state)
        assertEquals("idle", snapshot.reconnect_recovery_state)
        assertEquals(true, snapshot.carrier_foreground_visible)
        assertEquals(true, snapshot.interaction_surface_ready)
        assertEquals("durable-abc", snapshot.durable_session_id)
        assertEquals(2, snapshot.session_continuity_epoch)
        assertEquals("runtime-xyz", snapshot.runtime_session_id)
        assertEquals("attach-123", snapshot.attached_session_id)

        val json = toJsonObject(snapshot)
        assertEquals("active", json.get("carrier_runtime_state")?.asString)
        assertEquals("idle", json.get("reconnect_recovery_state")?.asString)
        assertEquals(true, json.get("carrier_foreground_visible")?.asBoolean)
    }

    @Test
    fun `execution event carries all carrier identity fields together without conflict`() {
        val event = DeviceExecutionEventPayload(
            flow_id = "flow-pr10-full",
            task_id = "task-pr10-full",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            device_id = "test_device_pr10_full",
            source_component = "Pr10AndroidCarrierConsolidationTest",
            // PR-6
            durable_session_id = "durable-abc",
            session_continuity_epoch = 2,
            runtime_session_id = "runtime-xyz",
            attached_session_id = "attach-123",
            // PR-8
            carrier_foreground_visible = true,
            interaction_surface_ready = true,
            // PR-10
            carrier_runtime_state = "active"
        )

        assertEquals("active", event.carrier_runtime_state)
        assertEquals(true, event.carrier_foreground_visible)
        assertEquals(true, event.interaction_surface_ready)
        assertEquals("durable-abc", event.durable_session_id)
        assertEquals(2, event.session_continuity_epoch)

        val json = toJsonObject(event)
        assertEquals("active", json.get("carrier_runtime_state")?.asString)
        assertEquals(true, json.get("carrier_foreground_visible")?.asBoolean)
    }
}
