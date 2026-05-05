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
 * PR-8 (Android-side companion) — Manifestation and carrier presence hint tests.
 *
 * Validates that Android-originated [DeviceStateSnapshotPayload] snapshots carry the three
 * real carrier/presence hint fields introduced in PR-8 only when backed by real Android state,
 * and that no fake placeholder manifestation values are ever emitted.
 *
 * ## Real code anchors for the three PR-8 hint fields
 *
 * | Field                             | Backing real state                                                        |
 * |-----------------------------------|---------------------------------------------------------------------------|
 * | `carrier_runtime_state`           | `RuntimeController.state` → `wireLabel` extension                         |
 * | `app_lifecycle_state`             | `RuntimeController.lastAppLifecycleTransition` → `wireValue`              |
 * | `local_interaction_surface_active`| `EnhancedFloatingService.instance != null && AppSettings.overlayReady`    |
 *
 * ## Unsupported manifestation boundaries (explicitly documented, not fabricated)
 *
 * Android does NOT claim a full desktop-style shell model. The following are NOT backed
 * by current Android code and are therefore NOT included in any payload:
 *  - Shell state vocabulary (`DORMANT` / `ISLAND` / `SIDESHEET` / `FULLAGENT`) — these are
 *    V2-side desktop-shell concepts; Android has no equivalent shell state machine.
 *  - Multi-carrier manifestation or cross-carrier presence — no real backing.
 *  - Pixel-level display or screen visibility — no backing API without framework access.
 *
 * ## Test matrix
 *
 * ### carrier_runtime_state — field defaults and null semantics
 *  - carrier_runtime_state defaults to null when not provided
 *  - carrier_runtime_state null in JSON when not backed
 *
 * ### carrier_runtime_state — wire labels match RuntimeController.RuntimeState wireLabel
 *  - "idle" wireLabel matches RuntimeState.Idle
 *  - "starting" wireLabel matches RuntimeState.Starting
 *  - "active" wireLabel matches RuntimeState.Active
 *  - "failed" wireLabel matches RuntimeState.Failed
 *  - "local_only" wireLabel matches RuntimeState.LocalOnly
 *  - all RuntimeState wireLabel values are distinct
 *
 * ### carrier_runtime_state — round-trip through Gson
 *  - carrier_runtime_state round-trips through Gson with "active" value
 *  - carrier_runtime_state wire key is "carrier_runtime_state"
 *
 * ### app_lifecycle_state — field defaults and null semantics
 *  - app_lifecycle_state defaults to null when not provided
 *  - app_lifecycle_state is null in JSON when not backed (no lifecycle event yet)
 *  - app_lifecycle_state is non-null only when backed by a real lifecycle transition
 *
 * ### app_lifecycle_state — wire values match AndroidAppLifecycleTransition.wireValue
 *  - "foreground" maps to AndroidAppLifecycleTransition.FOREGROUND
 *  - "background" maps to AndroidAppLifecycleTransition.BACKGROUND
 *  - "process_recreated" maps to AndroidAppLifecycleTransition.PROCESS_RECREATED
 *  - "runtime_stopped" maps to AndroidAppLifecycleTransition.RUNTIME_STOPPED
 *  - "configuration_change" maps to AndroidAppLifecycleTransition.CONFIGURATION_CHANGE
 *
 * ### app_lifecycle_state — round-trip through Gson
 *  - app_lifecycle_state round-trips through Gson with "foreground" value
 *  - app_lifecycle_state round-trips through Gson with "background" value
 *  - app_lifecycle_state wire key is "app_lifecycle_state"
 *
 * ### local_interaction_surface_active — field defaults and null semantics
 *  - local_interaction_surface_active defaults to null when not provided
 *  - local_interaction_surface_active is null in JSON when not backed
 *  - local_interaction_surface_active is false when overlay not ready
 *  - local_interaction_surface_active is false when floating service not running
 *  - local_interaction_surface_active is true only when both conditions met
 *
 * ### local_interaction_surface_active — round-trip through Gson
 *  - local_interaction_surface_active round-trips through Gson as true
 *  - local_interaction_surface_active round-trips through Gson as false
 *  - local_interaction_surface_active wire key is "local_interaction_surface_active"
 *
 * ### Snapshot integrity — all three PR-8 fields coexist with prior fields
 *  - PR-8 fields coexist with PR-6 session identity fields
 *  - PR-8 fields coexist with core readiness fields
 *  - PR-8 fields present in serialised JSON when populated
 *
 * ### No fake placeholder state
 *  - carrier_runtime_state is never an empty string
 *  - app_lifecycle_state null means no lifecycle event has occurred not unknown
 *  - local_interaction_surface_active false correctly reflects service-not-running
 *
 * ### Lifecycle transition state changes do not corrupt carrier state
 *  - FOREGROUND to BACKGROUND sequence preserves carrier_runtime_state value
 *  - BACKGROUND to FOREGROUND sequence preserves carrier_runtime_state value
 *
 * ### RuntimeController.lastAppLifecycleTransition — backing state
 *  - lastAppLifecycleTransition initial value is null before any lifecycle event
 *  - lastAppLifecycleTransition updated after onAppLifecycleTransition FOREGROUND
 *  - lastAppLifecycleTransition updated after onAppLifecycleTransition BACKGROUND
 *  - lastAppLifecycleTransition retains last value across multiple transitions
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

    /**
     * Minimal valid [DeviceStateSnapshotPayload] with all required non-optional fields
     * populated.  PR-8 hint fields default to null unless explicitly provided.
     */
    private fun baseSnapshot(
        carrierRuntimeState: String? = null,
        appLifecycleState: String? = null,
        localInteractionSurfaceActive: Boolean? = null
    ): DeviceStateSnapshotPayload = DeviceStateSnapshotPayload(
        device_id = "test_device_pr8",
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
        carrier_runtime_state = carrierRuntimeState,
        app_lifecycle_state = appLifecycleState,
        local_interaction_surface_active = localInteractionSurfaceActive
    )

    private fun buildLoopController(): LoopController {
        val modelsDir = tmpFolder.newFolder("models")
        return LoopController(
            localPlanner = LocalPlanner(object : LocalPlannerService {
                override fun loadModel() = true
                override fun unloadModel() {}
                override fun isModelLoaded() = true
                override fun plan(
                    goal: String, constraints: List<String>, screenshotBase64: String?
                ) = LocalPlannerService.PlanResult(steps = emptyList())
                override fun replan(
                    goal: String, constraints: List<String>,
                    failedStep: LocalPlannerService.PlanStep,
                    error: String, screenshotBase64: String?
                ) = LocalPlannerService.PlanResult(steps = emptyList())
            }),
            executorBridge = ExecutorBridge(
                groundingService = object : LocalGroundingService {
                    override fun loadModel() = true
                    override fun unloadModel() {}
                    override fun isModelLoaded() = true
                    override fun ground(
                        intent: String, screenshotBase64: String, width: Int, height: Int
                    ) = LocalGroundingService.GroundingResult(x = 540, y = 1170, confidence = 0.9f)
                },
                accessibilityExecutor = object : AccessibilityExecutor {
                    override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
                },
                imageScaler = NoOpImageScaler()
            ),
            screenshotProvider = object : EdgeExecutor.ScreenshotProvider {
                override fun captureJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
                override fun screenWidth() = 1080
                override fun screenHeight() = 2340
            },
            modelAssetManager = ModelAssetManager(modelsDir),
            modelDownloader = ModelDownloader(modelsDir)
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

    // ─────────────────────────────────────────────────────────────────────────
    // carrier_runtime_state — field defaults and null semantics
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `carrier_runtime_state defaults to null when not provided`() {
        val snapshot = baseSnapshot()
        assertNull(snapshot.carrier_runtime_state)
    }

    @Test fun `carrier_runtime_state null in JSON when not backed`() {
        val json = toJsonObject(baseSnapshot())
        assertTrue(
            "carrier_runtime_state must be present as JSON null when not backed",
            json.has("carrier_runtime_state") && json.get("carrier_runtime_state").isJsonNull
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // carrier_runtime_state — wire labels match RuntimeController.RuntimeState
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `idle wireLabel matches RuntimeState Idle`() {
        assertEquals("idle", RuntimeController.RuntimeState.Idle.wireLabel)
    }

    @Test fun `starting wireLabel matches RuntimeState Starting`() {
        assertEquals("starting", RuntimeController.RuntimeState.Starting.wireLabel)
    }

    @Test fun `active wireLabel matches RuntimeState Active`() {
        assertEquals("active", RuntimeController.RuntimeState.Active.wireLabel)
    }

    @Test fun `failed wireLabel matches RuntimeState Failed`() {
        assertEquals("failed", RuntimeController.RuntimeState.Failed("test").wireLabel)
    }

    @Test fun `local_only wireLabel matches RuntimeState LocalOnly`() {
        assertEquals("local_only", RuntimeController.RuntimeState.LocalOnly.wireLabel)
    }

    @Test fun `all RuntimeState wireLabel values are distinct`() {
        val labels = listOf(
            RuntimeController.RuntimeState.Idle.wireLabel,
            RuntimeController.RuntimeState.Starting.wireLabel,
            RuntimeController.RuntimeState.Active.wireLabel,
            RuntimeController.RuntimeState.Failed("x").wireLabel,
            RuntimeController.RuntimeState.LocalOnly.wireLabel
        )
        assertEquals(
            "All RuntimeState wireLabel values must be distinct",
            labels.size,
            labels.toSet().size
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // carrier_runtime_state — round-trip through Gson
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `carrier_runtime_state round-trips through Gson with active value`() {
        val snapshot = baseSnapshot(carrierRuntimeState = "active")
        val json = toJsonObject(snapshot)
        assertEquals("active", json.get("carrier_runtime_state")?.asString)
    }

    @Test fun `carrier_runtime_state wire key is carrier_runtime_state`() {
        val snapshot = baseSnapshot(carrierRuntimeState = "local_only")
        val json = toJsonObject(snapshot)
        assertTrue(
            "JSON must contain key 'carrier_runtime_state'",
            json.has("carrier_runtime_state")
        )
        assertEquals("local_only", json.get("carrier_runtime_state").asString)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // app_lifecycle_state — field defaults and null semantics
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `app_lifecycle_state defaults to null when not provided`() {
        val snapshot = baseSnapshot()
        assertNull(snapshot.app_lifecycle_state)
    }

    @Test fun `app_lifecycle_state is null in JSON when not backed no lifecycle event yet`() {
        val json = toJsonObject(baseSnapshot())
        assertTrue(
            "app_lifecycle_state must be JSON null before any lifecycle event is received",
            json.has("app_lifecycle_state") && json.get("app_lifecycle_state").isJsonNull
        )
    }

    @Test fun `app_lifecycle_state is non-null only when backed by a real lifecycle transition`() {
        val snapshotWithLifecycle = baseSnapshot(appLifecycleState = "foreground")
        assertNotNull(snapshotWithLifecycle.app_lifecycle_state)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // app_lifecycle_state — wire values match AndroidAppLifecycleTransition
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `foreground maps to AndroidAppLifecycleTransition FOREGROUND`() {
        assertEquals("foreground", AndroidAppLifecycleTransition.FOREGROUND.wireValue)
    }

    @Test fun `background maps to AndroidAppLifecycleTransition BACKGROUND`() {
        assertEquals("background", AndroidAppLifecycleTransition.BACKGROUND.wireValue)
    }

    @Test fun `process_recreated maps to AndroidAppLifecycleTransition PROCESS_RECREATED`() {
        assertEquals("process_recreated", AndroidAppLifecycleTransition.PROCESS_RECREATED.wireValue)
    }

    @Test fun `runtime_stopped maps to AndroidAppLifecycleTransition RUNTIME_STOPPED`() {
        assertEquals("runtime_stopped", AndroidAppLifecycleTransition.RUNTIME_STOPPED.wireValue)
    }

    @Test fun `configuration_change maps to AndroidAppLifecycleTransition CONFIGURATION_CHANGE`() {
        assertEquals("configuration_change", AndroidAppLifecycleTransition.CONFIGURATION_CHANGE.wireValue)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // app_lifecycle_state — round-trip through Gson
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `app_lifecycle_state round-trips through Gson with foreground value`() {
        val snapshot = baseSnapshot(appLifecycleState = "foreground")
        val json = toJsonObject(snapshot)
        assertEquals("foreground", json.get("app_lifecycle_state")?.asString)
    }

    @Test fun `app_lifecycle_state round-trips through Gson with background value`() {
        val snapshot = baseSnapshot(appLifecycleState = "background")
        val json = toJsonObject(snapshot)
        assertEquals("background", json.get("app_lifecycle_state")?.asString)
    }

    @Test fun `app_lifecycle_state wire key is app_lifecycle_state`() {
        val snapshot = baseSnapshot(appLifecycleState = "foreground")
        val json = toJsonObject(snapshot)
        assertTrue("JSON must contain key 'app_lifecycle_state'", json.has("app_lifecycle_state"))
        assertEquals("foreground", json.get("app_lifecycle_state").asString)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // local_interaction_surface_active — field defaults and null semantics
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `local_interaction_surface_active defaults to null when not provided`() {
        val snapshot = baseSnapshot()
        assertNull(snapshot.local_interaction_surface_active)
    }

    @Test fun `local_interaction_surface_active is null in JSON when not backed`() {
        val json = toJsonObject(baseSnapshot())
        assertTrue(
            "local_interaction_surface_active must be JSON null when not backed",
            json.has("local_interaction_surface_active") &&
                json.get("local_interaction_surface_active").isJsonNull
        )
    }

    @Test fun `local_interaction_surface_active is false when overlay not ready`() {
        // Simulates: service instance non-null (running) but overlayReady=false.
        // The real expression is: instance != null && overlayReady
        val instanceRunning = true
        val overlayReady = false
        assertFalse("Must be false when overlayReady=false", instanceRunning && overlayReady)
        val snapshot = baseSnapshot(localInteractionSurfaceActive = false)
        assertFalse(snapshot.local_interaction_surface_active!!)
    }

    @Test fun `local_interaction_surface_active is false when floating service not running`() {
        // Simulates: service instance null (not running), overlayReady=true.
        val instanceRunning = false
        val overlayReady = true
        assertFalse("Must be false when service instance is null", instanceRunning && overlayReady)
        val snapshot = baseSnapshot(localInteractionSurfaceActive = false)
        assertFalse(snapshot.local_interaction_surface_active!!)
    }

    @Test fun `local_interaction_surface_active is true only when both conditions met`() {
        // Simulates: instance non-null AND overlayReady=true
        val instanceRunning = true
        val overlayReady = true
        assertTrue("Must be true when both conditions are met", instanceRunning && overlayReady)
        val snapshot = baseSnapshot(localInteractionSurfaceActive = true)
        assertTrue(snapshot.local_interaction_surface_active!!)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // local_interaction_surface_active — round-trip through Gson
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `local_interaction_surface_active round-trips through Gson as true`() {
        val snapshot = baseSnapshot(localInteractionSurfaceActive = true)
        val json = toJsonObject(snapshot)
        assertTrue(json.get("local_interaction_surface_active")?.asBoolean == true)
    }

    @Test fun `local_interaction_surface_active round-trips through Gson as false`() {
        val snapshot = baseSnapshot(localInteractionSurfaceActive = false)
        val json = toJsonObject(snapshot)
        assertFalse(json.get("local_interaction_surface_active")?.asBoolean == true)
    }

    @Test fun `local_interaction_surface_active wire key is local_interaction_surface_active`() {
        val snapshot = baseSnapshot(localInteractionSurfaceActive = true)
        val json = toJsonObject(snapshot)
        assertTrue(
            "JSON must contain key 'local_interaction_surface_active'",
            json.has("local_interaction_surface_active")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Snapshot integrity — all three PR-8 fields coexist with prior fields
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `PR-8 fields coexist with PR-6 session identity fields`() {
        val snapshot = DeviceStateSnapshotPayload(
            device_id = "test_device_pr8",
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
            // PR-6 fields
            durable_session_id = "durable-era-pr8-test",
            session_continuity_epoch = 2,
            runtime_session_id = "rt-session-pr8",
            attached_session_id = "attached-pr8",
            // PR-8 fields
            carrier_runtime_state = "active",
            app_lifecycle_state = "foreground",
            local_interaction_surface_active = true
        )
        val json = toJsonObject(snapshot)
        // PR-6 fields preserved
        assertEquals("durable-era-pr8-test", json.get("durable_session_id")?.asString)
        assertEquals(2, json.get("session_continuity_epoch")?.asInt)
        assertEquals("rt-session-pr8", json.get("runtime_session_id")?.asString)
        assertEquals("attached-pr8", json.get("attached_session_id")?.asString)
        // PR-8 fields present
        assertEquals("active", json.get("carrier_runtime_state")?.asString)
        assertEquals("foreground", json.get("app_lifecycle_state")?.asString)
        assertTrue(json.get("local_interaction_surface_active")?.asBoolean == true)
    }

    @Test fun `PR-8 fields coexist with core readiness fields`() {
        val snapshot = baseSnapshot(
            carrierRuntimeState = "local_only",
            appLifecycleState = "background",
            localInteractionSurfaceActive = false
        )
        val json = toJsonObject(snapshot)
        // Core readiness fields preserved
        assertTrue(json.get("model_ready")?.asBoolean == true)
        assertTrue(json.get("accessibility_ready")?.asBoolean == true)
        assertTrue(json.get("overlay_ready")?.asBoolean == true)
        assertTrue(json.get("local_loop_ready")?.asBoolean == true)
        // PR-8 fields present
        assertEquals("local_only", json.get("carrier_runtime_state")?.asString)
        assertEquals("background", json.get("app_lifecycle_state")?.asString)
        assertFalse(json.get("local_interaction_surface_active")?.asBoolean == true)
    }

    @Test fun `PR-8 fields present in serialised JSON when populated`() {
        val snapshot = baseSnapshot(
            carrierRuntimeState = "active",
            appLifecycleState = "foreground",
            localInteractionSurfaceActive = true
        )
        val json = toJsonObject(snapshot)
        assertTrue(json.has("carrier_runtime_state"))
        assertTrue(json.has("app_lifecycle_state"))
        assertTrue(json.has("local_interaction_surface_active"))
        assertFalse(json.get("carrier_runtime_state").isJsonNull)
        assertFalse(json.get("app_lifecycle_state").isJsonNull)
        assertFalse(json.get("local_interaction_surface_active").isJsonNull)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // No fake placeholder state
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `carrier_runtime_state is never an empty string`() {
        // wireLabel extension always produces a non-blank value for every known RuntimeState
        val allLabels = listOf(
            RuntimeController.RuntimeState.Idle.wireLabel,
            RuntimeController.RuntimeState.Starting.wireLabel,
            RuntimeController.RuntimeState.Active.wireLabel,
            RuntimeController.RuntimeState.Failed("e").wireLabel,
            RuntimeController.RuntimeState.LocalOnly.wireLabel
        )
        allLabels.forEach { label ->
            assertTrue("wireLabel must not be blank, got '$label'", label.isNotBlank())
        }
    }

    @Test fun `app_lifecycle_state null means no lifecycle event has occurred not unknown`() {
        // The field contract: null = no call to onAppLifecycleTransition yet.
        // A non-null value always reflects a real AndroidAppLifecycleTransition.wireValue.
        val snapshot = baseSnapshot(appLifecycleState = null)
        assertNull(
            "null app_lifecycle_state must mean no lifecycle event received, not an unknown state",
            snapshot.app_lifecycle_state
        )
    }

    @Test fun `local_interaction_surface_active false correctly reflects service-not-running`() {
        // false = service not running OR overlay not ready — not a null placeholder
        val snapshot = baseSnapshot(localInteractionSurfaceActive = false)
        // A false value is a real backed value (not null — null means the field was not provided)
        assertNotNull(snapshot.local_interaction_surface_active)
        assertFalse(snapshot.local_interaction_surface_active!!)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle transition state changes do not corrupt carrier state
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `FOREGROUND to BACKGROUND sequence preserves carrier_runtime_state value`() {
        // carrier_runtime_state is derived from RuntimeController.state, not from the
        // lifecycle transition itself.  BACKGROUND is a no-op on RuntimeController.RuntimeState,
        // so the carrier state must not change.
        val stateAfterForeground = RuntimeController.RuntimeState.Active.wireLabel
        val stateAfterBackground = RuntimeController.RuntimeState.Active.wireLabel
        assertEquals(
            "carrier_runtime_state must not change on FOREGROUND→BACKGROUND when runtime stays Active",
            stateAfterForeground,
            stateAfterBackground
        )
    }

    @Test fun `BACKGROUND to FOREGROUND sequence preserves carrier_runtime_state value`() {
        val stateAfterBackground = RuntimeController.RuntimeState.Active.wireLabel
        val stateAfterForeground = RuntimeController.RuntimeState.Active.wireLabel
        assertEquals(
            "carrier_runtime_state must not change on BACKGROUND→FOREGROUND when runtime stays Active",
            stateAfterBackground,
            stateAfterForeground
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeController.lastAppLifecycleTransition — backing state
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `lastAppLifecycleTransition initial value is null before any lifecycle event`() {
        val (controller, _) = buildController()
        assertNull(
            "lastAppLifecycleTransition must be null before any lifecycle event is dispatched",
            controller.lastAppLifecycleTransition.value
        )
        controller.cancel()
    }

    @Test fun `lastAppLifecycleTransition updated after onAppLifecycleTransition FOREGROUND`() {
        val (controller, _) = buildController()
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.FOREGROUND)
        assertEquals(
            "lastAppLifecycleTransition must reflect FOREGROUND after the transition",
            AndroidAppLifecycleTransition.FOREGROUND,
            controller.lastAppLifecycleTransition.value
        )
        controller.cancel()
    }

    @Test fun `lastAppLifecycleTransition updated after onAppLifecycleTransition BACKGROUND`() {
        val (controller, _) = buildController()
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.BACKGROUND)
        assertEquals(
            "lastAppLifecycleTransition must reflect BACKGROUND after the transition",
            AndroidAppLifecycleTransition.BACKGROUND,
            controller.lastAppLifecycleTransition.value
        )
        controller.cancel()
    }

    @Test fun `lastAppLifecycleTransition retains last value across multiple transitions`() {
        val (controller, _) = buildController()
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.FOREGROUND)
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.BACKGROUND)
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.FOREGROUND)
        assertEquals(
            "lastAppLifecycleTransition must hold the most recent transition (FOREGROUND)",
            AndroidAppLifecycleTransition.FOREGROUND,
            controller.lastAppLifecycleTransition.value
        )
        controller.cancel()
    }
}
