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
import com.ufo.galaxy.ui.viewmodel.MainUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-33 — Product-grade reconnect resilience and recovery UX.
 *
 * Regression and acceptance test suite for all PR-33 additions:
 *
 *  1. [ReconnectRecoveryState] enum — four phases ([ReconnectRecoveryState.IDLE],
 *     [ReconnectRecoveryState.RECOVERING], [ReconnectRecoveryState.RECOVERED],
 *     [ReconnectRecoveryState.FAILED]) with stable [ReconnectRecoveryState.wireValue]
 *     constants suitable for structured log entries and V2 protocol fields.
 *
 *  2. [GalaxyLogger.TAG_RECONNECT_RECOVERY] — new stable structured log tag constant
 *     for recovery state transitions.
 *
 *  3. [RuntimeController.reconnectRecoveryState] — observable [kotlinx.coroutines.flow.StateFlow]
 *     that drives the recovery UX.  Starts at [ReconnectRecoveryState.IDLE] and transitions
 *     deterministically through the recovery lifecycle:
 *       - Active + WS disconnect → [ReconnectRecoveryState.RECOVERING]
 *       - [ReconnectRecoveryState.RECOVERING] + WS reconnect → [ReconnectRecoveryState.RECOVERED]
 *       - [ReconnectRecoveryState.RECOVERING] + WS error → [ReconnectRecoveryState.FAILED]
 *       - [RuntimeController.stop] → [ReconnectRecoveryState.IDLE] (always)
 *
 *  4. [RuntimeController.setActiveForTest] — internal test helper that places the runtime
 *     in [RuntimeController.RuntimeState.Active] and opens an attached session without
 *     a live WS server.
 *
 *  5. [GalaxyWebSocketClient.simulateConnected] / [GalaxyWebSocketClient.simulateDisconnected]
 *     / [GalaxyWebSocketClient.simulateError] — internal simulation methods enabling
 *     deterministic listener-event injection for unit tests.
 *
 *  6. [MainUiState.reconnectRecoveryState] — product-grade UI field defaulting to
 *     [ReconnectRecoveryState.IDLE], surfacing recovery state to surface layers.
 *
 *  7. Session truth continuity during short disconnects — after a transparent reconnect
 *     the [RuntimeController.attachedSession] is reopened (ATTACHED), verifying that
 *     execution truth is not broken by a short WS dropout.
 *
 * ## Test matrix
 *
 * ### ReconnectRecoveryState — wire value constants
 *  - IDLE wireValue is "idle"
 *  - RECOVERING wireValue is "recovering"
 *  - RECOVERED wireValue is "recovered"
 *  - FAILED wireValue is "failed"
 *  - all four wire values are distinct
 *
 * ### GalaxyLogger.TAG_RECONNECT_RECOVERY
 *  - TAG_RECONNECT_RECOVERY value is "GALAXY:RECONNECT:RECOVERY"
 *  - TAG_RECONNECT_RECOVERY is distinct from TAG_RECONNECT
 *  - TAG_RECONNECT_RECOVERY is distinct from TAG_RECONNECT_OUTCOME
 *  - TAG_RECONNECT_RECOVERY is distinct from all pre-PR33 tags
 *
 * ### RuntimeController.reconnectRecoveryState — initial state
 *  - initial reconnectRecoveryState is IDLE
 *
 * ### RuntimeController.reconnectRecoveryState — WS disconnect while Active → RECOVERING
 *  - WS disconnect while Active sets recovery state to RECOVERING
 *  - WS disconnect while NOT Active does not change recovery state to RECOVERING
 *
 * ### RuntimeController.reconnectRecoveryState — WS reconnect while RECOVERING → RECOVERED
 *  - WS reconnect while RECOVERING transitions to RECOVERED
 *  - attachedSession is re-opened (ATTACHED) after WS reconnect
 *
 * ### RuntimeController.reconnectRecoveryState — WS error while RECOVERING → FAILED
 *  - WS error while RECOVERING transitions to FAILED
 *  - WS error while IDLE does not change recovery state
 *
 * ### RuntimeController.reconnectRecoveryState — stop() resets to IDLE
 *  - stop() resets RECOVERING to IDLE
 *  - stop() resets RECOVERED to IDLE
 *  - stop() resets FAILED to IDLE
 *
 * ### Session truth continuity during short disconnect / reconnect
 *  - short disconnect does not permanently invalidate hostSessionSnapshot
 *  - after reconnect hostSessionSnapshot is non-null
 *  - reconnect produces a new runtimeSessionId (session truth refresh)
 *  - short disconnect → reconnect cycle preserves runtime state as Active
 *
 * ### MainUiState.reconnectRecoveryState
 *  - reconnectRecoveryState defaults to IDLE
 *  - can be set to RECOVERING via copy
 *  - can be set to RECOVERED via copy
 *  - can be set to FAILED via copy
 *  - setting reconnectRecoveryState does not affect other fields
 */
class Pr33ReconnectResilienceTest {

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
        settings: InMemoryAppSettings = InMemoryAppSettings(),
        timeoutMs: Long = 100L
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = timeoutMs
        )
        return controller to client
    }

    // ── ReconnectRecoveryState — wire value constants ─────────────────────────

    @Test
    fun `IDLE wireValue is 'idle'`() {
        assertEquals("idle", ReconnectRecoveryState.IDLE.wireValue)
    }

    @Test
    fun `RECOVERING wireValue is 'recovering'`() {
        assertEquals("recovering", ReconnectRecoveryState.RECOVERING.wireValue)
    }

    @Test
    fun `RECOVERED wireValue is 'recovered'`() {
        assertEquals("recovered", ReconnectRecoveryState.RECOVERED.wireValue)
    }

    @Test
    fun `FAILED wireValue is 'failed'`() {
        assertEquals("failed", ReconnectRecoveryState.FAILED.wireValue)
    }

    @Test
    fun `all four ReconnectRecoveryState wire values are distinct`() {
        val values = ReconnectRecoveryState.values().map { it.wireValue }
        assertEquals(
            "All four ReconnectRecoveryState wire values must be distinct",
            values.size,
            values.distinct().size
        )
    }

    // ── GalaxyLogger.TAG_RECONNECT_RECOVERY ───────────────────────────────────

    @Test
    fun `TAG_RECONNECT_RECOVERY value is GALAXY_RECONNECT_RECOVERY`() {
        assertEquals("GALAXY:RECONNECT:RECOVERY", GalaxyLogger.TAG_RECONNECT_RECOVERY)
    }

    @Test
    fun `TAG_RECONNECT_RECOVERY is distinct from TAG_RECONNECT`() {
        assertFalse(
            "TAG_RECONNECT_RECOVERY must not equal TAG_RECONNECT",
            GalaxyLogger.TAG_RECONNECT_RECOVERY == GalaxyLogger.TAG_RECONNECT
        )
    }

    @Test
    fun `TAG_RECONNECT_RECOVERY is distinct from TAG_RECONNECT_OUTCOME`() {
        assertFalse(
            "TAG_RECONNECT_RECOVERY must not equal TAG_RECONNECT_OUTCOME",
            GalaxyLogger.TAG_RECONNECT_RECOVERY == GalaxyLogger.TAG_RECONNECT_OUTCOME
        )
    }

    @Test
    fun `TAG_RECONNECT_RECOVERY is distinct from all pre-PR33 tags`() {
        val prePr33Tags = listOf(
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
            GalaxyLogger.TAG_STAGED_MESH
        )
        for (tag in prePr33Tags) {
            assertFalse(
                "TAG_RECONNECT_RECOVERY must be distinct from $tag",
                GalaxyLogger.TAG_RECONNECT_RECOVERY == tag
            )
        }
    }

    // ── RuntimeController.reconnectRecoveryState — initial state ──────────────

    @Test
    fun `initial reconnectRecoveryState is IDLE`() {
        val (controller, _) = buildController()
        assertEquals(
            "reconnectRecoveryState must start as IDLE",
            ReconnectRecoveryState.IDLE,
            controller.reconnectRecoveryState.value
        )
    }

    // ── WS disconnect while Active → RECOVERING ───────────────────────────────

    @Test
    fun `WS disconnect while Active sets recovery state to RECOVERING`() {
        val (controller, client) = buildController()

        // Place the runtime in Active state (bypasses real WS handshake for testing).
        controller.setActiveForTest()
        assertEquals(
            "Runtime must be Active after setActiveForTest",
            RuntimeController.RuntimeState.Active,
            controller.state.value
        )

        // Simulate a transparent WS disconnect.
        client.simulateDisconnected()

        assertEquals(
            "Recovery state must be RECOVERING after WS disconnect while Active",
            ReconnectRecoveryState.RECOVERING,
            controller.reconnectRecoveryState.value
        )
    }

    @Test
    fun `WS disconnect while NOT Active does not change recovery state to RECOVERING`() {
        val (controller, client) = buildController()

        // Runtime starts in Idle (not Active); disconnect must be a no-op for recovery state.
        assertTrue(
            "Precondition: runtime must not be Active before the test",
            controller.state.value !is RuntimeController.RuntimeState.Active
        )

        client.simulateDisconnected()

        assertEquals(
            "Recovery state must remain IDLE when disconnect happens outside Active state",
            ReconnectRecoveryState.IDLE,
            controller.reconnectRecoveryState.value
        )
    }

    // ── WS reconnect while RECOVERING → RECOVERED ────────────────────────────

    @Test
    fun `WS reconnect while RECOVERING transitions to RECOVERED`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        // Trigger the IDLE → RECOVERING transition.
        client.simulateDisconnected()
        assertEquals(
            "Precondition: must be RECOVERING after disconnect",
            ReconnectRecoveryState.RECOVERING,
            controller.reconnectRecoveryState.value
        )

        // Simulate transparent reconnect.
        client.simulateConnected()

        assertEquals(
            "Recovery state must be RECOVERED after WS reconnect while RECOVERING",
            ReconnectRecoveryState.RECOVERED,
            controller.reconnectRecoveryState.value
        )
    }

    @Test
    fun `attachedSession is re-opened after WS reconnect (session truth continuity)`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        // Record the session before the disconnect.
        val sessionBeforeDisconnect = controller.attachedSession.value
        assertNotNull(
            "Session must be non-null after setActiveForTest",
            sessionBeforeDisconnect
        )
        assertTrue(
            "Session must be ATTACHED before the disconnect",
            sessionBeforeDisconnect!!.isAttached
        )

        // Simulate disconnect — session closes.
        client.simulateDisconnected()
        val sessionAfterDisconnect = controller.attachedSession.value
        assertFalse(
            "Session must NOT be attached after WS disconnect",
            sessionAfterDisconnect?.isAttached == true
        )

        // Simulate reconnect — session must be reopened.
        client.simulateConnected()
        val sessionAfterReconnect = controller.attachedSession.value
        assertNotNull(
            "Session must be non-null after reconnect (session truth continuity)",
            sessionAfterReconnect
        )
        assertTrue(
            "Session must be ATTACHED after reconnect (truth continuity restored)",
            sessionAfterReconnect!!.isAttached
        )
    }

    @Test
    fun `short disconnect then reconnect preserves runtime state as Active`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        // Short disconnect cycle.
        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals(
            "RuntimeState must remain Active after a short disconnect/reconnect cycle",
            RuntimeController.RuntimeState.Active,
            controller.state.value
        )
    }

    @Test
    fun `reconnect after short disconnect produces non-null hostSessionSnapshot`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        // Verify non-null before.
        assertNotNull(
            "hostSessionSnapshot must be non-null after setActiveForTest",
            controller.hostSessionSnapshot.value
        )

        // Short disconnect cycle.
        client.simulateDisconnected()
        client.simulateConnected()

        assertNotNull(
            "hostSessionSnapshot must be non-null after reconnect (session truth continuity)",
            controller.hostSessionSnapshot.value
        )
    }

    // ── WS error while RECOVERING → FAILED ───────────────────────────────────

    @Test
    fun `WS error while RECOVERING transitions to FAILED`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        // Trigger RECOVERING first.
        client.simulateDisconnected()
        assertEquals(
            "Precondition: must be RECOVERING",
            ReconnectRecoveryState.RECOVERING,
            controller.reconnectRecoveryState.value
        )

        // Terminal error (e.g. max reconnect attempts exhausted).
        client.simulateError("无法连接到服务器")

        assertEquals(
            "Recovery state must be FAILED after WS error while RECOVERING",
            ReconnectRecoveryState.FAILED,
            controller.reconnectRecoveryState.value
        )
    }

    @Test
    fun `WS error while IDLE does not change recovery state`() {
        val (controller, client) = buildController()

        // Runtime is not Active; error must not affect recovery state.
        client.simulateError("some error")

        assertEquals(
            "Recovery state must remain IDLE when error occurs outside RECOVERING",
            ReconnectRecoveryState.IDLE,
            controller.reconnectRecoveryState.value
        )
    }

    // ── stop() resets recovery state to IDLE ─────────────────────────────────

    @Test
    fun `stop resets recovery state from RECOVERING to IDLE`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()
        client.simulateDisconnected()
        assertEquals(
            "Precondition: must be RECOVERING before stop",
            ReconnectRecoveryState.RECOVERING,
            controller.reconnectRecoveryState.value
        )

        controller.stop()

        assertEquals(
            "stop() must reset recovery state to IDLE from RECOVERING",
            ReconnectRecoveryState.IDLE,
            controller.reconnectRecoveryState.value
        )
    }

    @Test
    fun `stop resets recovery state from RECOVERED to IDLE`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()
        client.simulateDisconnected()
        client.simulateConnected()
        assertEquals(
            "Precondition: must be RECOVERED before stop",
            ReconnectRecoveryState.RECOVERED,
            controller.reconnectRecoveryState.value
        )

        controller.stop()

        assertEquals(
            "stop() must reset recovery state to IDLE from RECOVERED",
            ReconnectRecoveryState.IDLE,
            controller.reconnectRecoveryState.value
        )
    }

    @Test
    fun `stop resets recovery state from FAILED to IDLE`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()
        client.simulateDisconnected()
        client.simulateError("max attempts reached")
        assertEquals(
            "Precondition: must be FAILED before stop",
            ReconnectRecoveryState.FAILED,
            controller.reconnectRecoveryState.value
        )

        controller.stop()

        assertEquals(
            "stop() must reset recovery state to IDLE from FAILED",
            ReconnectRecoveryState.IDLE,
            controller.reconnectRecoveryState.value
        )
    }

    @Test
    fun `stop resets recovery state even from IDLE (no-op-safe)`() {
        val (controller, _) = buildController()
        // State is already IDLE; stop() must not throw and must keep it IDLE.
        controller.stop()
        assertEquals(
            "stop() must keep recovery state as IDLE when already IDLE",
            ReconnectRecoveryState.IDLE,
            controller.reconnectRecoveryState.value
        )
    }

    // ── reconnectRecoveryState StateFlow is observable ────────────────────────

    @Test
    fun `reconnectRecoveryState StateFlow emits IDLE initially`() = runBlocking {
        val (controller, _) = buildController()
        val initialValue = withTimeoutOrNull(500L) {
            controller.reconnectRecoveryState.first()
        }
        assertEquals(
            "reconnectRecoveryState StateFlow must emit IDLE as its first value",
            ReconnectRecoveryState.IDLE,
            initialValue
        )
    }

    // ── MainUiState.reconnectRecoveryState ────────────────────────────────────

    @Test
    fun `MainUiState reconnectRecoveryState defaults to IDLE`() {
        val state = MainUiState()
        assertEquals(
            "MainUiState.reconnectRecoveryState must default to IDLE",
            ReconnectRecoveryState.IDLE,
            state.reconnectRecoveryState
        )
    }

    @Test
    fun `MainUiState reconnectRecoveryState can be set to RECOVERING via copy`() {
        val state = MainUiState().copy(reconnectRecoveryState = ReconnectRecoveryState.RECOVERING)
        assertEquals(ReconnectRecoveryState.RECOVERING, state.reconnectRecoveryState)
    }

    @Test
    fun `MainUiState reconnectRecoveryState can be set to RECOVERED via copy`() {
        val state = MainUiState().copy(reconnectRecoveryState = ReconnectRecoveryState.RECOVERED)
        assertEquals(ReconnectRecoveryState.RECOVERED, state.reconnectRecoveryState)
    }

    @Test
    fun `MainUiState reconnectRecoveryState can be set to FAILED via copy`() {
        val state = MainUiState().copy(reconnectRecoveryState = ReconnectRecoveryState.FAILED)
        assertEquals(ReconnectRecoveryState.FAILED, state.reconnectRecoveryState)
    }

    @Test
    fun `setting MainUiState reconnectRecoveryState does not affect isConnected`() {
        val base = MainUiState(isConnected = true)
        val updated = base.copy(reconnectRecoveryState = ReconnectRecoveryState.RECOVERING)
        assertTrue(
            "isConnected must be unchanged after setting reconnectRecoveryState",
            updated.isConnected
        )
    }

    @Test
    fun `setting MainUiState reconnectRecoveryState does not affect error field`() {
        val base = MainUiState(error = "some error")
        val updated = base.copy(reconnectRecoveryState = ReconnectRecoveryState.RECOVERING)
        assertEquals(
            "error field must be unchanged after setting reconnectRecoveryState",
            "some error",
            updated.error
        )
    }

    // ── Regression: existing session truth fields unchanged during recovery ────

    @Test
    fun `disconnect while Active does not permanently break hostSessionSnapshot`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        // Verify initial state.
        assertNotNull(
            "hostSessionSnapshot must be set after setActiveForTest",
            controller.hostSessionSnapshot.value
        )

        // Short disconnect.
        client.simulateDisconnected()
        // After disconnect the snapshot may be detached but must not be null.
        // (The snapshot represents the detached state, not absence of state.)
        assertNotNull(
            "hostSessionSnapshot must remain non-null even after disconnect — state projection preserved",
            controller.hostSessionSnapshot.value
        )

        // After reconnect it must be restored to ATTACHED truth.
        client.simulateConnected()
        val snapshot = controller.hostSessionSnapshot.value
        assertNotNull("hostSessionSnapshot must be non-null after reconnect", snapshot)
        assertEquals(
            "attachment_state must be 'attached' after reconnect",
            "attached",
            snapshot!!.attachmentState
        )
    }

    @Test
    fun `reconnect produces a new runtimeSessionId (session truth refresh)`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val runtimeSessionIdBefore = controller.hostSessionSnapshot.value?.runtimeSessionId

        // Short disconnect + reconnect cycle.
        client.simulateDisconnected()
        client.simulateConnected()

        val runtimeSessionIdAfter = controller.hostSessionSnapshot.value?.runtimeSessionId

        assertNotNull("runtimeSessionId must be set before the cycle", runtimeSessionIdBefore)
        assertNotNull("runtimeSessionId must be set after the cycle", runtimeSessionIdAfter)
        assertFalse(
            "A new runtimeSessionId must be generated on reconnect so the V2 registry can " +
                "distinguish the reconnect event from an earlier session instance",
            runtimeSessionIdBefore == runtimeSessionIdAfter
        )
    }
}
