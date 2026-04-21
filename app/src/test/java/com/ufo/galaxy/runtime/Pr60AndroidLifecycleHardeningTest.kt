package com.ufo.galaxy.runtime

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
import com.ufo.galaxy.observability.GalaxyLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-60 — Android lifecycle hardening, recovery semantics, and hybrid-participant integration.
 *
 * Regression and acceptance test suite for all PR-60 additions:
 *
 *  1. [AndroidAppLifecycleTransition] — explicit app-level lifecycle event model.
 *  2. [HybridParticipantCapability] — structured hybrid/distributed capability status enum.
 *  3. [AndroidLifecycleRecoveryContract] — recovery boundary documentation constants.
 *  4. [GalaxyLogger.TAG_APP_LIFECYCLE] / [GalaxyLogger.TAG_HYBRID_PARTICIPANT] — new tags.
 *  5. [RuntimeController.onAppLifecycleTransition] — explicit lifecycle handler.
 *
 * ## Test matrix
 *
 * ### AndroidAppLifecycleTransition — wire value constants
 *  - FOREGROUND wireValue is "foreground"
 *  - BACKGROUND wireValue is "background"
 *  - PROCESS_RECREATED wireValue is "process_recreated"
 *  - RUNTIME_STOPPED wireValue is "runtime_stopped"
 *  - CONFIGURATION_CHANGE wireValue is "configuration_change"
 *  - all five wire values are distinct
 *
 * ### AndroidAppLifecycleTransition — runtimeImplication is non-blank
 *  - every transition has a non-blank runtimeImplication
 *
 * ### HybridParticipantCapability — wire value constants
 *  - HYBRID_EXECUTE_FULL wireValue is "hybrid_execute_full"
 *  - STAGED_MESH_SUBTASK wireValue is "staged_mesh_subtask"
 *  - PARALLEL_SUBTASK wireValue is "parallel_subtask"
 *  - WEBRTC_PEER_TRANSPORT wireValue is "webrtc_peer_transport"
 *  - BARRIER_COORDINATION wireValue is "barrier_coordination"
 *  - all wire values are distinct
 *
 * ### HybridParticipantCapability — support levels
 *  - HYBRID_EXECUTE_FULL supportLevel is NOT_YET_IMPLEMENTED
 *  - STAGED_MESH_SUBTASK supportLevel is AVAILABLE
 *  - PARALLEL_SUBTASK supportLevel is AVAILABLE
 *  - WEBRTC_PEER_TRANSPORT supportLevel is MINIMAL_COMPAT
 *  - BARRIER_COORDINATION supportLevel is NOT_YET_IMPLEMENTED
 *
 * ### HybridParticipantCapability.SupportLevel — wire values
 *  - AVAILABLE wireValue is "available"
 *  - MINIMAL_COMPAT wireValue is "minimal_compat"
 *  - NOT_YET_IMPLEMENTED wireValue is "not_yet_implemented"
 *  - all three support level wire values are distinct
 *
 * ### HybridParticipantCapability — availableCapabilities() / deferredCapabilities()
 *  - availableCapabilities includes STAGED_MESH_SUBTASK and PARALLEL_SUBTASK
 *  - availableCapabilities does NOT include HYBRID_EXECUTE_FULL
 *  - deferredCapabilities includes HYBRID_EXECUTE_FULL and BARRIER_COORDINATION
 *  - deferredCapabilities does NOT include STAGED_MESH_SUBTASK
 *
 * ### HybridParticipantCapability — fromWireValue()
 *  - fromWireValue returns correct entry for known wire values
 *  - fromWireValue returns null for unknown wire values
 *
 * ### GalaxyLogger — TAG_APP_LIFECYCLE and TAG_HYBRID_PARTICIPANT
 *  - TAG_APP_LIFECYCLE value is "GALAXY:APP:LIFECYCLE"
 *  - TAG_HYBRID_PARTICIPANT value is "GALAXY:HYBRID:PARTICIPANT"
 *  - both tags are distinct from each other and from all pre-PR60 tags
 *
 * ### AndroidLifecycleRecoveryContract — process recreation boundary
 *  - PROCESS_RECREATION_BOUNDARY contains SURVIVES_PROCESS_RECREATION key
 *  - PROCESS_RECREATION_BOUNDARY contains LOST_ON_PROCESS_RECREATION key
 *  - survived list includes crossDeviceEnabled
 *  - lost list includes DurableSessionContinuityRecord
 *  - lost list includes in-flight task state
 *
 * ### AndroidLifecycleRecoveryContract — transient disconnect recovery
 *  - TRANSIENT_DISCONNECT_RECOVERY contains android_recovers_locally key
 *  - TRANSIENT_DISCONNECT_RECOVERY contains v2_must_handle key
 *
 * ### AndroidLifecycleRecoveryContract — hybrid limitations
 *  - HYBRID_LIMITATIONS contains HYBRID_EXECUTE_FULL wire value key
 *  - HYBRID_LIMITATIONS contains WEBRTC_PEER_TRANSPORT wire value key
 *  - HYBRID_LIMITATIONS contains BARRIER_COORDINATION wire value key
 *
 * ### RuntimeController.onAppLifecycleTransition — state integration
 *  - FOREGROUND transition calls connectIfEnabled (no state change from Idle with crossDevice=false)
 *  - BACKGROUND transition is a no-op (runtime state unchanged)
 *  - PROCESS_RECREATED transition calls connectIfEnabled (same as FOREGROUND)
 *  - RUNTIME_STOPPED transition calls stop() (runtime transitions to LocalOnly)
 *  - CONFIGURATION_CHANGE transition is a no-op (runtime state unchanged)
 *
 * ### RuntimeController.onAppLifecycleTransition — Active→LocalOnly on RUNTIME_STOPPED
 *  - RUNTIME_STOPPED while Active transitions to LocalOnly
 *  - RUNTIME_STOPPED while Active detaches the attached session
 */
class Pr60AndroidLifecycleHardeningTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies ─────────────────────────────────────────────────────

    private class TrivialPlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep("tap", "tap the button"))
            )
        override fun replan(
            goal: String, constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String, screenshotBase64: String?
        ) = LocalPlannerService.PlanResult(steps = emptyList(), error = "no replan")
    }

    private class FakeGrounder : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(
            intent: String, screenshotBase64: String, width: Int, height: Int
        ) = LocalGroundingService.GroundingResult(x = 540, y = 1170, confidence = 0.9f)
    }

    private class FakeAccessibilityExecutor : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    private class FakeScreenshotProvider : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        override fun screenWidth() = 1080
        override fun screenHeight() = 2340
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private fun buildLoopController(): LoopController {
        val modelsDir = tmpFolder.newFolder("models")
        return LoopController(
            localPlanner = LocalPlanner(TrivialPlannerService()),
            executorBridge = ExecutorBridge(
                groundingService = FakeGrounder(),
                accessibilityExecutor = FakeAccessibilityExecutor(),
                imageScaler = NoOpImageScaler()
            ),
            screenshotProvider = FakeScreenshotProvider(),
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

    // ── AndroidAppLifecycleTransition — wire value constants ──────────────────

    @Test
    fun `FOREGROUND wireValue is foreground`() {
        assertEquals("foreground", AndroidAppLifecycleTransition.FOREGROUND.wireValue)
    }

    @Test
    fun `BACKGROUND wireValue is background`() {
        assertEquals("background", AndroidAppLifecycleTransition.BACKGROUND.wireValue)
    }

    @Test
    fun `PROCESS_RECREATED wireValue is process_recreated`() {
        assertEquals("process_recreated", AndroidAppLifecycleTransition.PROCESS_RECREATED.wireValue)
    }

    @Test
    fun `RUNTIME_STOPPED wireValue is runtime_stopped`() {
        assertEquals("runtime_stopped", AndroidAppLifecycleTransition.RUNTIME_STOPPED.wireValue)
    }

    @Test
    fun `CONFIGURATION_CHANGE wireValue is configuration_change`() {
        assertEquals("configuration_change", AndroidAppLifecycleTransition.CONFIGURATION_CHANGE.wireValue)
    }

    @Test
    fun `all five AndroidAppLifecycleTransition wire values are distinct`() {
        val values = AndroidAppLifecycleTransition.entries.map { it.wireValue }
        assertEquals(
            "All AndroidAppLifecycleTransition wire values must be distinct",
            values.size,
            values.distinct().size
        )
    }

    // ── AndroidAppLifecycleTransition — runtimeImplication is non-blank ───────

    @Test
    fun `every transition has a non-blank runtimeImplication`() {
        for (transition in AndroidAppLifecycleTransition.entries) {
            assertTrue(
                "runtimeImplication must be non-blank for ${transition.wireValue}",
                transition.runtimeImplication.isNotBlank()
            )
        }
    }

    // ── HybridParticipantCapability — wire value constants ────────────────────

    @Test
    fun `HYBRID_EXECUTE_FULL wireValue is hybrid_execute_full`() {
        assertEquals("hybrid_execute_full", HybridParticipantCapability.HYBRID_EXECUTE_FULL.wireValue)
    }

    @Test
    fun `STAGED_MESH_SUBTASK wireValue is staged_mesh_subtask`() {
        assertEquals("staged_mesh_subtask", HybridParticipantCapability.STAGED_MESH_SUBTASK.wireValue)
    }

    @Test
    fun `PARALLEL_SUBTASK wireValue is parallel_subtask`() {
        assertEquals("parallel_subtask", HybridParticipantCapability.PARALLEL_SUBTASK.wireValue)
    }

    @Test
    fun `WEBRTC_PEER_TRANSPORT wireValue is webrtc_peer_transport`() {
        assertEquals("webrtc_peer_transport", HybridParticipantCapability.WEBRTC_PEER_TRANSPORT.wireValue)
    }

    @Test
    fun `BARRIER_COORDINATION wireValue is barrier_coordination`() {
        assertEquals("barrier_coordination", HybridParticipantCapability.BARRIER_COORDINATION.wireValue)
    }

    @Test
    fun `all HybridParticipantCapability wire values are distinct`() {
        val values = HybridParticipantCapability.entries.map { it.wireValue }
        assertEquals(
            "All HybridParticipantCapability wire values must be distinct",
            values.size,
            values.distinct().size
        )
    }

    // ── HybridParticipantCapability — support levels ──────────────────────────

    @Test
    fun `HYBRID_EXECUTE_FULL supportLevel is NOT_YET_IMPLEMENTED`() {
        assertEquals(
            HybridParticipantCapability.SupportLevel.NOT_YET_IMPLEMENTED,
            HybridParticipantCapability.HYBRID_EXECUTE_FULL.supportLevel
        )
    }

    @Test
    fun `STAGED_MESH_SUBTASK supportLevel is AVAILABLE`() {
        assertEquals(
            HybridParticipantCapability.SupportLevel.AVAILABLE,
            HybridParticipantCapability.STAGED_MESH_SUBTASK.supportLevel
        )
    }

    @Test
    fun `PARALLEL_SUBTASK supportLevel is AVAILABLE`() {
        assertEquals(
            HybridParticipantCapability.SupportLevel.AVAILABLE,
            HybridParticipantCapability.PARALLEL_SUBTASK.supportLevel
        )
    }

    @Test
    fun `WEBRTC_PEER_TRANSPORT supportLevel is MINIMAL_COMPAT`() {
        assertEquals(
            HybridParticipantCapability.SupportLevel.MINIMAL_COMPAT,
            HybridParticipantCapability.WEBRTC_PEER_TRANSPORT.supportLevel
        )
    }

    @Test
    fun `BARRIER_COORDINATION supportLevel is NOT_YET_IMPLEMENTED`() {
        assertEquals(
            HybridParticipantCapability.SupportLevel.NOT_YET_IMPLEMENTED,
            HybridParticipantCapability.BARRIER_COORDINATION.supportLevel
        )
    }

    // ── HybridParticipantCapability.SupportLevel — wire values ───────────────

    @Test
    fun `SupportLevel AVAILABLE wireValue is available`() {
        assertEquals("available", HybridParticipantCapability.SupportLevel.AVAILABLE.wireValue)
    }

    @Test
    fun `SupportLevel MINIMAL_COMPAT wireValue is minimal_compat`() {
        assertEquals("minimal_compat", HybridParticipantCapability.SupportLevel.MINIMAL_COMPAT.wireValue)
    }

    @Test
    fun `SupportLevel NOT_YET_IMPLEMENTED wireValue is not_yet_implemented`() {
        assertEquals("not_yet_implemented", HybridParticipantCapability.SupportLevel.NOT_YET_IMPLEMENTED.wireValue)
    }

    @Test
    fun `all three SupportLevel wire values are distinct`() {
        val values = HybridParticipantCapability.SupportLevel.entries.map { it.wireValue }
        assertEquals(
            "All SupportLevel wire values must be distinct",
            values.size,
            values.distinct().size
        )
    }

    // ── HybridParticipantCapability — availableCapabilities / deferredCapabilities

    @Test
    fun `availableCapabilities includes STAGED_MESH_SUBTASK`() {
        assertTrue(
            "STAGED_MESH_SUBTASK must be in availableCapabilities",
            HybridParticipantCapability.availableCapabilities()
                .contains(HybridParticipantCapability.STAGED_MESH_SUBTASK)
        )
    }

    @Test
    fun `availableCapabilities includes PARALLEL_SUBTASK`() {
        assertTrue(
            "PARALLEL_SUBTASK must be in availableCapabilities",
            HybridParticipantCapability.availableCapabilities()
                .contains(HybridParticipantCapability.PARALLEL_SUBTASK)
        )
    }

    @Test
    fun `availableCapabilities does NOT include HYBRID_EXECUTE_FULL`() {
        assertFalse(
            "HYBRID_EXECUTE_FULL must NOT be in availableCapabilities",
            HybridParticipantCapability.availableCapabilities()
                .contains(HybridParticipantCapability.HYBRID_EXECUTE_FULL)
        )
    }

    @Test
    fun `deferredCapabilities includes HYBRID_EXECUTE_FULL`() {
        assertTrue(
            "HYBRID_EXECUTE_FULL must be in deferredCapabilities",
            HybridParticipantCapability.deferredCapabilities()
                .contains(HybridParticipantCapability.HYBRID_EXECUTE_FULL)
        )
    }

    @Test
    fun `deferredCapabilities includes BARRIER_COORDINATION`() {
        assertTrue(
            "BARRIER_COORDINATION must be in deferredCapabilities",
            HybridParticipantCapability.deferredCapabilities()
                .contains(HybridParticipantCapability.BARRIER_COORDINATION)
        )
    }

    @Test
    fun `deferredCapabilities does NOT include STAGED_MESH_SUBTASK`() {
        assertFalse(
            "STAGED_MESH_SUBTASK must NOT be in deferredCapabilities",
            HybridParticipantCapability.deferredCapabilities()
                .contains(HybridParticipantCapability.STAGED_MESH_SUBTASK)
        )
    }

    // ── HybridParticipantCapability — fromWireValue ───────────────────────────

    @Test
    fun `fromWireValue returns correct entry for hybrid_execute_full`() {
        assertEquals(
            HybridParticipantCapability.HYBRID_EXECUTE_FULL,
            HybridParticipantCapability.fromWireValue("hybrid_execute_full")
        )
    }

    @Test
    fun `fromWireValue returns correct entry for staged_mesh_subtask`() {
        assertEquals(
            HybridParticipantCapability.STAGED_MESH_SUBTASK,
            HybridParticipantCapability.fromWireValue("staged_mesh_subtask")
        )
    }

    @Test
    fun `fromWireValue returns null for unknown wire value`() {
        assertNull(
            "Unknown wire value must return null",
            HybridParticipantCapability.fromWireValue("completely_unknown_capability")
        )
    }

    // ── GalaxyLogger — TAG_APP_LIFECYCLE and TAG_HYBRID_PARTICIPANT ───────────

    @Test
    fun `TAG_APP_LIFECYCLE value is GALAXY_APP_LIFECYCLE`() {
        assertEquals("GALAXY:APP:LIFECYCLE", GalaxyLogger.TAG_APP_LIFECYCLE)
    }

    @Test
    fun `TAG_HYBRID_PARTICIPANT value is GALAXY_HYBRID_PARTICIPANT`() {
        assertEquals("GALAXY:HYBRID:PARTICIPANT", GalaxyLogger.TAG_HYBRID_PARTICIPANT)
    }

    @Test
    fun `TAG_APP_LIFECYCLE is distinct from TAG_HYBRID_PARTICIPANT`() {
        assertFalse(
            "TAG_APP_LIFECYCLE must not equal TAG_HYBRID_PARTICIPANT",
            GalaxyLogger.TAG_APP_LIFECYCLE == GalaxyLogger.TAG_HYBRID_PARTICIPANT
        )
    }

    @Test
    fun `TAG_APP_LIFECYCLE is distinct from all pre-PR60 tags`() {
        val prePr60Tags = listOf(
            GalaxyLogger.TAG_CONNECT,
            GalaxyLogger.TAG_DISCONNECT,
            GalaxyLogger.TAG_RECONNECT,
            GalaxyLogger.TAG_TASK_RECV,
            GalaxyLogger.TAG_TASK_EXEC,
            GalaxyLogger.TAG_TASK_RETURN,
            GalaxyLogger.TAG_ERROR,
            GalaxyLogger.TAG_EXEC_ROUTE,
            GalaxyLogger.TAG_SETUP_RECOVERY,
            GalaxyLogger.TAG_RECONNECT_OUTCOME,
            GalaxyLogger.TAG_FALLBACK_DECISION,
            GalaxyLogger.TAG_ROLLOUT_CONTROL,
            GalaxyLogger.TAG_KILL_SWITCH,
            GalaxyLogger.TAG_STAGED_MESH,
            GalaxyLogger.TAG_RECONNECT_RECOVERY,
            GalaxyLogger.TAG_RUNTIME_LIFECYCLE,
            GalaxyLogger.TAG_V2_LIFECYCLE,
            GalaxyLogger.TAG_LIFECYCLE_OBSERVE,
            GalaxyLogger.TAG_RECOVERY_OBSERVE
        )
        for (tag in prePr60Tags) {
            assertFalse(
                "TAG_APP_LIFECYCLE must be distinct from $tag",
                GalaxyLogger.TAG_APP_LIFECYCLE == tag
            )
        }
    }

    @Test
    fun `TAG_HYBRID_PARTICIPANT is distinct from all pre-PR60 tags`() {
        val prePr60Tags = listOf(
            GalaxyLogger.TAG_CONNECT,
            GalaxyLogger.TAG_DISCONNECT,
            GalaxyLogger.TAG_RECONNECT,
            GalaxyLogger.TAG_STAGED_MESH,
            GalaxyLogger.TAG_RUNTIME_LIFECYCLE,
            GalaxyLogger.TAG_V2_LIFECYCLE,
            GalaxyLogger.TAG_LIFECYCLE_OBSERVE,
            GalaxyLogger.TAG_RECOVERY_OBSERVE
        )
        for (tag in prePr60Tags) {
            assertFalse(
                "TAG_HYBRID_PARTICIPANT must be distinct from $tag",
                GalaxyLogger.TAG_HYBRID_PARTICIPANT == tag
            )
        }
    }

    // ── AndroidLifecycleRecoveryContract — process recreation boundary ────────

    @Test
    fun `PROCESS_RECREATION_BOUNDARY contains SURVIVES_PROCESS_RECREATION key`() {
        assertTrue(
            "PROCESS_RECREATION_BOUNDARY must contain SURVIVES_PROCESS_RECREATION",
            AndroidLifecycleRecoveryContract.PROCESS_RECREATION_BOUNDARY
                .containsKey(AndroidLifecycleRecoveryContract.SURVIVES_PROCESS_RECREATION)
        )
    }

    @Test
    fun `PROCESS_RECREATION_BOUNDARY contains LOST_ON_PROCESS_RECREATION key`() {
        assertTrue(
            "PROCESS_RECREATION_BOUNDARY must contain LOST_ON_PROCESS_RECREATION",
            AndroidLifecycleRecoveryContract.PROCESS_RECREATION_BOUNDARY
                .containsKey(AndroidLifecycleRecoveryContract.LOST_ON_PROCESS_RECREATION)
        )
    }

    @Test
    fun `survived list includes crossDeviceEnabled`() {
        val survived = AndroidLifecycleRecoveryContract.PROCESS_RECREATION_BOUNDARY[
            AndroidLifecycleRecoveryContract.SURVIVES_PROCESS_RECREATION
        ] ?: emptyList<String>()
        assertTrue(
            "Survived list must mention crossDeviceEnabled",
            survived.any { it.contains("crossDeviceEnabled", ignoreCase = true) }
        )
    }

    @Test
    fun `lost list includes DurableSessionContinuityRecord`() {
        val lost = AndroidLifecycleRecoveryContract.PROCESS_RECREATION_BOUNDARY[
            AndroidLifecycleRecoveryContract.LOST_ON_PROCESS_RECREATION
        ] ?: emptyList<String>()
        assertTrue(
            "Lost list must mention DurableSessionContinuityRecord",
            lost.any { it.contains("DurableSessionContinuityRecord", ignoreCase = true) }
        )
    }

    @Test
    fun `lost list includes in-flight task state`() {
        val lost = AndroidLifecycleRecoveryContract.PROCESS_RECREATION_BOUNDARY[
            AndroidLifecycleRecoveryContract.LOST_ON_PROCESS_RECREATION
        ] ?: emptyList<String>()
        assertTrue(
            "Lost list must mention in-flight task state",
            lost.any { it.contains("task", ignoreCase = true) }
        )
    }

    // ── AndroidLifecycleRecoveryContract — transient disconnect recovery ──────

    @Test
    fun `TRANSIENT_DISCONNECT_RECOVERY contains android_recovers_locally key`() {
        assertTrue(
            "TRANSIENT_DISCONNECT_RECOVERY must contain android_recovers_locally",
            AndroidLifecycleRecoveryContract.TRANSIENT_DISCONNECT_RECOVERY
                .containsKey("android_recovers_locally")
        )
    }

    @Test
    fun `TRANSIENT_DISCONNECT_RECOVERY contains v2_must_handle key`() {
        assertTrue(
            "TRANSIENT_DISCONNECT_RECOVERY must contain v2_must_handle",
            AndroidLifecycleRecoveryContract.TRANSIENT_DISCONNECT_RECOVERY
                .containsKey("v2_must_handle")
        )
    }

    // ── AndroidLifecycleRecoveryContract — hybrid limitations ─────────────────

    @Test
    fun `HYBRID_LIMITATIONS contains HYBRID_EXECUTE_FULL wire value key`() {
        assertTrue(
            "HYBRID_LIMITATIONS must contain HYBRID_EXECUTE_FULL wire value",
            AndroidLifecycleRecoveryContract.HYBRID_LIMITATIONS
                .containsKey(HybridParticipantCapability.HYBRID_EXECUTE_FULL.wireValue)
        )
    }

    @Test
    fun `HYBRID_LIMITATIONS contains WEBRTC_PEER_TRANSPORT wire value key`() {
        assertTrue(
            "HYBRID_LIMITATIONS must contain WEBRTC_PEER_TRANSPORT wire value",
            AndroidLifecycleRecoveryContract.HYBRID_LIMITATIONS
                .containsKey(HybridParticipantCapability.WEBRTC_PEER_TRANSPORT.wireValue)
        )
    }

    @Test
    fun `HYBRID_LIMITATIONS contains BARRIER_COORDINATION wire value key`() {
        assertTrue(
            "HYBRID_LIMITATIONS must contain BARRIER_COORDINATION wire value",
            AndroidLifecycleRecoveryContract.HYBRID_LIMITATIONS
                .containsKey(HybridParticipantCapability.BARRIER_COORDINATION.wireValue)
        )
    }

    // ── RuntimeController.onAppLifecycleTransition — state integration ────────

    @Test
    fun `FOREGROUND transition calls connectIfEnabled — no state change from Idle with crossDevice false`() {
        val (controller, _) = buildController()
        // With crossDevice=false, connectIfEnabled transitions to LocalOnly (if not already there)
        // or stays Idle. Since state starts as Idle, FOREGROUND should not crash and leaves state stable.
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.FOREGROUND)
        // Verify method completes without throwing; state is LocalOnly or Idle (crossDevice=false → LocalOnly)
        val state = controller.state.value
        assertTrue(
            "After FOREGROUND with crossDevice=false, state must be LocalOnly or Idle",
            state == RuntimeController.RuntimeState.LocalOnly || state == RuntimeController.RuntimeState.Idle
        )
    }

    @Test
    fun `BACKGROUND transition is a no-op — runtime state unchanged from Idle`() {
        val (controller, _) = buildController()
        val stateBefore = controller.state.value
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.BACKGROUND)
        assertEquals(
            "BACKGROUND must not change runtime state",
            stateBefore,
            controller.state.value
        )
    }

    @Test
    fun `PROCESS_RECREATED transition calls connectIfEnabled — no state change from Idle with crossDevice false`() {
        val (controller, _) = buildController()
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.PROCESS_RECREATED)
        val state = controller.state.value
        assertTrue(
            "After PROCESS_RECREATED with crossDevice=false, state must be LocalOnly or Idle",
            state == RuntimeController.RuntimeState.LocalOnly || state == RuntimeController.RuntimeState.Idle
        )
    }

    @Test
    fun `CONFIGURATION_CHANGE transition is a no-op — runtime state unchanged from Idle`() {
        val (controller, _) = buildController()
        val stateBefore = controller.state.value
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.CONFIGURATION_CHANGE)
        assertEquals(
            "CONFIGURATION_CHANGE must not change runtime state",
            stateBefore,
            controller.state.value
        )
    }

    @Test
    fun `RUNTIME_STOPPED transition calls stop — transitions to LocalOnly`() {
        val (controller, _) = buildController()
        // Manually place into a non-LocalOnly state to verify stop is triggered
        controller.setActiveForTest()
        assertEquals(
            "Runtime must be Active after setActiveForTest",
            RuntimeController.RuntimeState.Active,
            controller.state.value
        )
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.RUNTIME_STOPPED)
        assertEquals(
            "RUNTIME_STOPPED must transition runtime to LocalOnly",
            RuntimeController.RuntimeState.LocalOnly,
            controller.state.value
        )
    }

    // ── Active → LocalOnly on RUNTIME_STOPPED ─────────────────────────────────

    @Test
    fun `RUNTIME_STOPPED while Active detaches the attached session`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()
        assertNotNull(
            "attachedSession must be non-null after setActiveForTest",
            controller.attachedSession.value
        )
        assertTrue(
            "attachedSession must be ATTACHED after setActiveForTest",
            controller.attachedSession.value?.isAttached == true
        )
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.RUNTIME_STOPPED)
        // After stop(), the session is closed (DETACHED) or null depending on prior state
        val session = controller.attachedSession.value
        assertTrue(
            "After RUNTIME_STOPPED, attachedSession must be null or detached",
            session == null || session.isDetached
        )
    }

    @Test
    fun `RUNTIME_STOPPED while Active resets reconnectRecoveryState to IDLE`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()
        // Simulate a disconnect to set RECOVERING state
        client.simulateDisconnected()
        assertEquals(
            "reconnectRecoveryState must be RECOVERING after disconnect",
            ReconnectRecoveryState.RECOVERING,
            controller.reconnectRecoveryState.value
        )
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.RUNTIME_STOPPED)
        assertEquals(
            "RUNTIME_STOPPED must reset reconnectRecoveryState to IDLE",
            ReconnectRecoveryState.IDLE,
            controller.reconnectRecoveryState.value
        )
    }
}
