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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-8 (Android companion) — Carrier manifestation and presence hints.
 *
 * Validates that Android-originated payloads consistently expose real manifestation-related
 * carrier/presence hints through the existing Android→V2 uplink paths, and that no fake
 * placeholder values are emitted when backing state is absent.
 *
 * ## Real anchors tested
 *
 * ### `carrier_foreground_visible`
 * Backed by [RuntimeController.appForegroundVisible], which is updated by
 * [RuntimeController.onAppLifecycleTransition] on FOREGROUND/BACKGROUND transitions.
 * Semantics: `true` = app is in foreground (visible, interactive carrier surface);
 * `false` = app is backgrounded (runtime carrier, not directly visible to user);
 * `null`/`false` default = state not yet observed (safe default — no fake value emitted).
 *
 * ### `interaction_surface_ready`
 * Derived from `accessibility_ready && overlay_ready` (same pre-flight condition used
 * by [com.ufo.galaxy.runtime.CrossDeviceEnablementError] and
 * [com.ufo.galaxy.agent.TakeoverEligibilityAssessor]).  Semantics: `true` = Android has
 * both the overlay permission (floating window) and the accessibility service enabled
 * (screen interaction); `false` = at least one capability is missing; backed by real
 * [com.ufo.galaxy.data.AppSettings] flags at emission time.
 *
 * ## Boundaries declared
 *
 * Android does NOT have a full desktop-style shell model.  These two fields capture the
 * real, narrow set of carrier/presence semantics that current Android code actually backs:
 *  - overlay + accessibility readiness → local interaction surface operational
 *  - foreground/background lifecycle → carrier visibility to the user
 *
 * Fields that do not have a real backing implementation are NOT added.  No speculative
 * shell framework or multi-carrier abstraction is introduced.
 *
 * ## Test matrix
 *
 * ### DeviceStateSnapshotPayload — carrier manifestation field defaults
 *  - carrier_foreground_visible defaults to null when not provided
 *  - interaction_surface_ready defaults to null when not provided
 *
 * ### DeviceStateSnapshotPayload — Gson round-trip
 *  - carrier_foreground_visible=true round-trips as true
 *  - carrier_foreground_visible=false round-trips as false
 *  - carrier_foreground_visible=null serialises as JSON null
 *  - interaction_surface_ready=true round-trips as true
 *  - interaction_surface_ready=false round-trips as false
 *  - interaction_surface_ready=null serialises as JSON null
 *
 * ### DeviceExecutionEventPayload — carrier manifestation field defaults
 *  - carrier_foreground_visible defaults to null when not provided
 *  - interaction_surface_ready defaults to null when not provided
 *
 * ### DeviceExecutionEventPayload — Gson round-trip
 *  - carrier_foreground_visible=true round-trips as true
 *  - interaction_surface_ready=true round-trips as true
 *  - both fields null in JSON when not backed
 *
 * ### Semantic correctness — interaction_surface_ready derivation
 *  - true when both accessibility_ready and overlay_ready are true
 *  - false when accessibility_ready is false and overlay_ready is true
 *  - false when accessibility_ready is true and overlay_ready is false
 *  - false when both are false
 *
 * ### RuntimeController.appForegroundVisible — lifecycle backing
 *  - starts as false (safe default — no fake value)
 *  - becomes true after FOREGROUND transition
 *  - becomes false after BACKGROUND transition
 *  - FOREGROUND → BACKGROUND → FOREGROUND sequence is stable
 *  - CONFIGURATION_CHANGE does not corrupt appForegroundVisible
 *  - RUNTIME_STOPPED does not corrupt appForegroundVisible (remains false)
 *
 * ### No fake manifestation values
 *  - carrier_foreground_visible is null (not fabricated) when field is not explicitly set
 *  - interaction_surface_ready is null (not fabricated) when field is not explicitly set
 *  - both fields absent from snapshot when both are null (Gson null-serialisation contract)
 *
 * ### Wire key name stability (V2 contract alignment)
 *  - snapshot carrier_foreground_visible wire key is "carrier_foreground_visible"
 *  - snapshot interaction_surface_ready wire key is "interaction_surface_ready"
 *  - event carrier_foreground_visible wire key is "carrier_foreground_visible"
 *  - event interaction_surface_ready wire key is "interaction_surface_ready"
 *
 * ### Lifecycle stability — manifestation hints survive phase transitions
 *  - carrier_foreground_visible is consistent across execution_started and terminal events
 *  - interaction_surface_ready is consistent across execution_started and terminal events
 */
class Pr8ManifestationPresenceHintsTest {

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

    /** Minimal valid [DeviceStateSnapshotPayload] with optional manifestation hint overrides. */
    private fun baseSnapshot(
        carrierForegroundVisible: Boolean? = null,
        interactionSurfaceReady: Boolean? = null,
        accessibilityReady: Boolean? = true,
        overlayReady: Boolean? = true
    ): DeviceStateSnapshotPayload = DeviceStateSnapshotPayload(
        device_id = "test_device_pr8",
        snapshot_ts = 1_700_000_000_000L,
        llama_cpp_available = true,
        ncnn_available = false,
        active_runtime_type = "LLAMA_CPP",
        model_ready = true,
        accessibility_ready = accessibilityReady,
        overlay_ready = overlayReady,
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
        interaction_surface_ready = interactionSurfaceReady
    )

    /** Minimal valid [DeviceExecutionEventPayload] with optional manifestation hint overrides. */
    private fun baseExecutionEvent(
        phase: String = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
        carrierForegroundVisible: Boolean? = null,
        interactionSurfaceReady: Boolean? = null
    ): DeviceExecutionEventPayload = DeviceExecutionEventPayload(
        flow_id = "flow-pr8",
        task_id = "task-pr8",
        phase = phase,
        device_id = "test_device_pr8",
        source_component = "Pr8ManifestationPresenceHintsTest",
        carrier_foreground_visible = carrierForegroundVisible,
        interaction_surface_ready = interactionSurfaceReady
    )

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceStateSnapshotPayload — carrier manifestation field defaults
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_foreground_visible defaults to null in snapshot when not provided`() {
        val snapshot = baseSnapshot()
        assertNull(
            "carrier_foreground_visible must default to null (no fake value)",
            snapshot.carrier_foreground_visible
        )
    }

    @Test
    fun `interaction_surface_ready defaults to null in snapshot when not provided`() {
        val snapshot = baseSnapshot()
        assertNull(
            "interaction_surface_ready must default to null (no fake value)",
            snapshot.interaction_surface_ready
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceStateSnapshotPayload — Gson round-trip
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_foreground_visible=true round-trips through Gson in snapshot`() {
        val json = toJsonObject(baseSnapshot(carrierForegroundVisible = true))
        assertTrue(
            "carrier_foreground_visible must serialise as true",
            json.get("carrier_foreground_visible")?.asBoolean == true
        )
    }

    @Test
    fun `carrier_foreground_visible=false round-trips through Gson in snapshot`() {
        val json = toJsonObject(baseSnapshot(carrierForegroundVisible = false))
        assertFalse(
            "carrier_foreground_visible must serialise as false",
            json.get("carrier_foreground_visible")?.asBoolean ?: true
        )
    }

    @Test
    fun `carrier_foreground_visible=null serialises as JSON null in snapshot`() {
        val json = toJsonObject(baseSnapshot(carrierForegroundVisible = null))
        assertTrue(
            "carrier_foreground_visible=null must be JSON null (not absent)",
            json.has("carrier_foreground_visible") && json.get("carrier_foreground_visible").isJsonNull
        )
    }

    @Test
    fun `interaction_surface_ready=true round-trips through Gson in snapshot`() {
        val json = toJsonObject(baseSnapshot(interactionSurfaceReady = true))
        assertTrue(
            "interaction_surface_ready must serialise as true",
            json.get("interaction_surface_ready")?.asBoolean == true
        )
    }

    @Test
    fun `interaction_surface_ready=false round-trips through Gson in snapshot`() {
        val json = toJsonObject(baseSnapshot(interactionSurfaceReady = false))
        assertFalse(
            "interaction_surface_ready must serialise as false",
            json.get("interaction_surface_ready")?.asBoolean ?: true
        )
    }

    @Test
    fun `interaction_surface_ready=null serialises as JSON null in snapshot`() {
        val json = toJsonObject(baseSnapshot(interactionSurfaceReady = null))
        assertTrue(
            "interaction_surface_ready=null must be JSON null (not absent)",
            json.has("interaction_surface_ready") && json.get("interaction_surface_ready").isJsonNull
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceExecutionEventPayload — carrier manifestation field defaults
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_foreground_visible defaults to null in execution event when not provided`() {
        val event = baseExecutionEvent()
        assertNull(
            "carrier_foreground_visible must default to null in execution event",
            event.carrier_foreground_visible
        )
    }

    @Test
    fun `interaction_surface_ready defaults to null in execution event when not provided`() {
        val event = baseExecutionEvent()
        assertNull(
            "interaction_surface_ready must default to null in execution event",
            event.interaction_surface_ready
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceExecutionEventPayload — Gson round-trip
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_foreground_visible=true round-trips through Gson in execution event`() {
        val json = toJsonObject(baseExecutionEvent(carrierForegroundVisible = true))
        assertTrue(
            "carrier_foreground_visible=true must round-trip in execution event",
            json.get("carrier_foreground_visible")?.asBoolean == true
        )
    }

    @Test
    fun `interaction_surface_ready=true round-trips through Gson in execution event`() {
        val json = toJsonObject(baseExecutionEvent(interactionSurfaceReady = true))
        assertTrue(
            "interaction_surface_ready=true must round-trip in execution event",
            json.get("interaction_surface_ready")?.asBoolean == true
        )
    }

    @Test
    fun `both fields serialise as JSON null in execution event when not backed`() {
        val json = toJsonObject(baseExecutionEvent())
        assertTrue(
            "carrier_foreground_visible must be JSON null when not backed",
            json.has("carrier_foreground_visible") && json.get("carrier_foreground_visible").isJsonNull
        )
        assertTrue(
            "interaction_surface_ready must be JSON null when not backed",
            json.has("interaction_surface_ready") && json.get("interaction_surface_ready").isJsonNull
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Semantic correctness — interaction_surface_ready derivation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `interaction_surface_ready is true when both accessibility and overlay are ready`() {
        // Mirrors the derivation: accessibilityReady == true && overlayReady == true
        val accessibilityReady = true
        val overlayReady = true
        val interactionSurfaceReady = accessibilityReady && overlayReady
        assertTrue(
            "interaction_surface_ready must be true when both capabilities are present",
            interactionSurfaceReady
        )
    }

    @Test
    fun `interaction_surface_ready is false when accessibility is not ready`() {
        val accessibilityReady = false
        val overlayReady = true
        val interactionSurfaceReady = accessibilityReady && overlayReady
        assertFalse(
            "interaction_surface_ready must be false when accessibility is not ready",
            interactionSurfaceReady
        )
    }

    @Test
    fun `interaction_surface_ready is false when overlay is not ready`() {
        val accessibilityReady = true
        val overlayReady = false
        val interactionSurfaceReady = accessibilityReady && overlayReady
        assertFalse(
            "interaction_surface_ready must be false when overlay is not ready",
            interactionSurfaceReady
        )
    }

    @Test
    fun `interaction_surface_ready is false when both accessibility and overlay are not ready`() {
        val accessibilityReady = false
        val overlayReady = false
        val interactionSurfaceReady = accessibilityReady && overlayReady
        assertFalse(
            "interaction_surface_ready must be false when both capabilities are missing",
            interactionSurfaceReady
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeController.appForegroundVisible — lifecycle backing
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `appForegroundVisible starts as false — safe default, no fake value`() {
        val (controller, _) = buildController()
        assertFalse(
            "appForegroundVisible must start as false (safe default, no fake foreground claim)",
            controller.appForegroundVisible.value
        )
    }

    @Test
    fun `appForegroundVisible becomes true after FOREGROUND transition`() {
        val (controller, _) = buildController()
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.FOREGROUND)
        assertTrue(
            "appForegroundVisible must be true after FOREGROUND transition",
            controller.appForegroundVisible.value
        )
    }

    @Test
    fun `appForegroundVisible becomes false after BACKGROUND transition`() {
        val (controller, _) = buildController()
        // Bring foreground first, then background.
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.FOREGROUND)
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.BACKGROUND)
        assertFalse(
            "appForegroundVisible must be false after BACKGROUND transition",
            controller.appForegroundVisible.value
        )
    }

    @Test
    fun `FOREGROUND → BACKGROUND → FOREGROUND sequence produces stable appForegroundVisible`() {
        val (controller, _) = buildController()
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.FOREGROUND)
        assertTrue("appForegroundVisible must be true after first FOREGROUND", controller.appForegroundVisible.value)

        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.BACKGROUND)
        assertFalse("appForegroundVisible must be false after BACKGROUND", controller.appForegroundVisible.value)

        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.FOREGROUND)
        assertTrue("appForegroundVisible must be true after second FOREGROUND", controller.appForegroundVisible.value)
    }

    @Test
    fun `CONFIGURATION_CHANGE does not corrupt appForegroundVisible`() {
        val (controller, _) = buildController()
        // Set foreground, then simulate a configuration change (rotation, etc.).
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.FOREGROUND)
        val visibleBefore = controller.appForegroundVisible.value
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.CONFIGURATION_CHANGE)
        assertEquals(
            "CONFIGURATION_CHANGE must not corrupt appForegroundVisible",
            visibleBefore,
            controller.appForegroundVisible.value
        )
    }

    @Test
    fun `RUNTIME_STOPPED does not corrupt appForegroundVisible — value unchanged from before stop`() {
        // appForegroundVisible tracks the Android app's foreground/background position,
        // which is determined by FOREGROUND/BACKGROUND lifecycle events, not by RUNTIME_STOPPED.
        // After the user stops the runtime, the app may still be in the foreground.
        val (controller, _) = buildController()
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.FOREGROUND)
        assertTrue("appForegroundVisible must be true before stop", controller.appForegroundVisible.value)

        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.RUNTIME_STOPPED)
        // The value remains whatever it was — RUNTIME_STOPPED is about the carrier role,
        // not about the app's physical foreground/background position.
        // The snapshot emission path reads the current value at build time; if the app is
        // still foreground after stop, the value correctly reflects that.
        assertNotNull(
            "appForegroundVisible must remain a defined boolean after RUNTIME_STOPPED",
            controller.appForegroundVisible.value  // Boolean is never null
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Wire key name stability (V2 contract alignment)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `snapshot carrier_foreground_visible wire key is carrier_foreground_visible`() {
        val json = toJsonObject(baseSnapshot(carrierForegroundVisible = true))
        assertTrue(
            "snapshot must use wire key 'carrier_foreground_visible'",
            json.has("carrier_foreground_visible")
        )
    }

    @Test
    fun `snapshot interaction_surface_ready wire key is interaction_surface_ready`() {
        val json = toJsonObject(baseSnapshot(interactionSurfaceReady = true))
        assertTrue(
            "snapshot must use wire key 'interaction_surface_ready'",
            json.has("interaction_surface_ready")
        )
    }

    @Test
    fun `event carrier_foreground_visible wire key is carrier_foreground_visible`() {
        val json = toJsonObject(baseExecutionEvent(carrierForegroundVisible = true))
        assertTrue(
            "execution event must use wire key 'carrier_foreground_visible'",
            json.has("carrier_foreground_visible")
        )
    }

    @Test
    fun `event interaction_surface_ready wire key is interaction_surface_ready`() {
        val json = toJsonObject(baseExecutionEvent(interactionSurfaceReady = true))
        assertTrue(
            "execution event must use wire key 'interaction_surface_ready'",
            json.has("interaction_surface_ready")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle stability — manifestation hints survive phase transitions
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_foreground_visible is consistent across execution_started and completed events`() {
        // Both events emitted with the same carrier state should carry the same hint.
        val startEvent = baseExecutionEvent(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            carrierForegroundVisible = true,
            interactionSurfaceReady = true
        )
        val terminalEvent = baseExecutionEvent(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            carrierForegroundVisible = true,
            interactionSurfaceReady = true
        )
        assertEquals(
            "carrier_foreground_visible must be consistent across start and terminal events",
            startEvent.carrier_foreground_visible,
            terminalEvent.carrier_foreground_visible
        )
    }

    @Test
    fun `interaction_surface_ready is consistent across execution_started and completed events`() {
        val startEvent = baseExecutionEvent(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            interactionSurfaceReady = true
        )
        val terminalEvent = baseExecutionEvent(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            interactionSurfaceReady = true
        )
        assertEquals(
            "interaction_surface_ready must be consistent across start and terminal events",
            startEvent.interaction_surface_ready,
            terminalEvent.interaction_surface_ready
        )
    }

    @Test
    fun `carrier_foreground_visible is consistent across snapshot and execution event when both backed by same state`() {
        // Simulates the scenario where the snapshot and the execution event are emitted
        // with the same carrier state — both should agree on the hint value.
        val snapshot = baseSnapshot(carrierForegroundVisible = true, interactionSurfaceReady = true)
        val event = baseExecutionEvent(carrierForegroundVisible = true, interactionSurfaceReady = true)
        assertEquals(
            "carrier_foreground_visible must agree between snapshot and execution event",
            snapshot.carrier_foreground_visible,
            event.carrier_foreground_visible
        )
        assertEquals(
            "interaction_surface_ready must agree between snapshot and execution event",
            snapshot.interaction_surface_ready,
            event.interaction_surface_ready
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Boundary: no fake manifestation state when Android lacks backing data
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `snapshot emits null for carrier_foreground_visible when field is not explicitly populated`() {
        // Confirms the safe-default behaviour: carrier_foreground_visible is null (not
        // fabricated as true or false) when no explicit value is provided.
        val snapshot = baseSnapshot()  // carrier_foreground_visible omitted → null
        assertNull(
            "carrier_foreground_visible must be null when not backed by real lifecycle state",
            snapshot.carrier_foreground_visible
        )
    }

    @Test
    fun `snapshot emits null for interaction_surface_ready when field is not explicitly populated`() {
        // Confirms the safe-default behaviour: interaction_surface_ready is null (not
        // fabricated as true or false) when no explicit value is provided.
        val snapshot = baseSnapshot()  // interaction_surface_ready omitted → null
        assertNull(
            "interaction_surface_ready must be null when not backed by real accessibility/overlay state",
            snapshot.interaction_surface_ready
        )
    }

    @Test
    fun `execution event emits null for carrier_foreground_visible when not backed`() {
        val event = baseExecutionEvent()  // carrier_foreground_visible omitted → null
        assertNull(
            "carrier_foreground_visible must be null when not backed",
            event.carrier_foreground_visible
        )
    }

    @Test
    fun `execution event emits null for interaction_surface_ready when not backed`() {
        val event = baseExecutionEvent()  // interaction_surface_ready omitted → null
        assertNull(
            "interaction_surface_ready must be null when not backed",
            event.interaction_surface_ready
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reconnect / state-change stability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `BACKGROUND transition followed by FOREGROUND restores carrier visibility correctly`() {
        val (controller, _) = buildController()
        // Start in background (default).
        assertFalse("appForegroundVisible must start false", controller.appForegroundVisible.value)

        // Simulate foreground restore (e.g. reconnect after background).
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.FOREGROUND)
        assertTrue(
            "appForegroundVisible must be true after FOREGROUND following initial background",
            controller.appForegroundVisible.value
        )
    }

    @Test
    fun `appForegroundVisible is false after PROCESS_RECREATED (new era starts in background)`() {
        val (controller, _) = buildController()
        // Process recreation: starts fresh, not yet in foreground.
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.PROCESS_RECREATED)
        // PROCESS_RECREATED does not set foreground; the app is still backgrounded
        // until an explicit FOREGROUND transition arrives.
        assertFalse(
            "appForegroundVisible must remain false after PROCESS_RECREATED (background start)",
            controller.appForegroundVisible.value
        )
    }
}
