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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Android V2 continuity bridge — durable continuity identity across reconnects.
 *
 * Regression test suite proving that Android behaves as a **stable durable continuity
 * source** across reconnects on the canonical registration / runtime path.  Specifically:
 *
 *  1. **Identity survives reconnect** — `durable_session_id` is preserved in both
 *     [RuntimeController.durableSessionContinuityRecord] and the downstream
 *     [GalaxyWebSocketClient] after a transparent WS disconnect + reconnect.
 *
 *  2. **Reconnect payloads carry the correct identity** — after reconnect, the
 *     [GalaxyWebSocketClient] holds the same `durable_session_id` and an incremented
 *     `session_continuity_epoch`, so the next `device_register` / `capability_report`
 *     handshake presents consistent continuity identity to V2 rather than fresh-session
 *     semantics.
 *
 *  3. **Epoch changes only under intended conditions** — `session_continuity_epoch`
 *     increments exactly once per transparent reconnect (RECONNECT_RECOVERY) and is
 *     reset to `0` only when a brand-new activation era begins (after stop or
 *     invalidation).  It does NOT increment on the initial activation, on multiple
 *     activations without an intervening disconnect, or on `stop()` + re-activate.
 *
 *  4. **Wire fields cleared on teardown** — after `stop()` or `invalidateSession()`,
 *     both `durable_session_id` and `session_continuity_epoch` are cleared from the
 *     [GalaxyWebSocketClient] so a subsequent reconnect does not carry a stale era
 *     identity into the new activation.
 *
 *  5. **V2 wire key names are stable** — the wire key constants
 *     [DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID] and
 *     [DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH] match the V2
 *     canonical field names used in the `device_register` / `capability_report` JSON.
 *
 * ## Canonical path under test
 *
 * ```
 * permanentWsListener.onConnected()  [RuntimeController]
 *     └─ openAttachedSession(RECONNECT_RECOVERY)
 *         └─ DurableSessionContinuityRecord.withEpochIncremented()
 *         └─ webSocketClient.updateRuntimeConnectionConfig(
 *                durableSessionId = <preserved>,
 *                sessionContinuityEpoch = <incremented>)
 *             └─ sendHandshake() includes durable_session_id / session_continuity_epoch
 * ```
 *
 * ## Test matrix
 *
 * ### WsClient — durable identity propagation on initial activation
 *  - WsClient receives durableSessionId after initial activation
 *  - WsClient receives sessionContinuityEpoch 0 after initial activation
 *  - WsClient durableSessionId matches RuntimeController record on activation
 *  - WsClient sessionContinuityEpoch matches RuntimeController record on activation
 *
 * ### WsClient — durable identity preserved after reconnect
 *  - WsClient durableSessionId is unchanged after disconnect-reconnect cycle
 *  - WsClient durableSessionId is unchanged after second disconnect-reconnect cycle
 *  - WsClient durableSessionId is unchanged after three consecutive reconnect cycles
 *
 * ### WsClient — continuity epoch increments on reconnect
 *  - WsClient sessionContinuityEpoch is 1 after first reconnect
 *  - WsClient sessionContinuityEpoch is 2 after second reconnect
 *  - WsClient sessionContinuityEpoch increments match RuntimeController record
 *
 * ### WsClient — epoch changes only under intended conditions
 *  - WsClient sessionContinuityEpoch is 0 immediately after activation (no reconnect yet)
 *  - WsClient sessionContinuityEpoch does not increment on activation alone
 *  - stop then reactivate produces epoch 0 (fresh era, not continuation)
 *  - stop then reactivate produces a different durableSessionId (fresh era)
 *
 * ### WsClient — identity cleared on stop()
 *  - WsClient durableSessionId is null after stop()
 *  - WsClient sessionContinuityEpoch is null after stop()
 *  - WsClient identity is null after stop() even following reconnect cycles
 *
 * ### WsClient — identity cleared on invalidateSession()
 *  - WsClient durableSessionId is null after invalidateSession()
 *  - WsClient sessionContinuityEpoch is null after invalidateSession()
 *
 * ### RuntimeController / WsClient — cross-object consistency
 *  - RuntimeController epoch matches WsClient epoch after reconnect
 *  - RuntimeController durableSessionId matches WsClient durableSessionId after reconnect
 *
 * ### V2 wire key contract
 *  - KEY_DURABLE_SESSION_ID wire value is "durable_session_id"
 *  - KEY_SESSION_CONTINUITY_EPOCH wire value is "session_continuity_epoch"
 */
class PrAndroidDurableContinuityReconnectBridgeTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies ─────────────────────────────────────────────────────

    private class TrivialPlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(steps = listOf(LocalPlannerService.PlanStep("tap", "button")))
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

    private fun buildController(): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = InMemoryAppSettings(),
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L
        )
        return controller to client
    }

    // ── WsClient — durable identity propagation on initial activation ─────────

    @Test
    fun `WsClient receives durableSessionId after initial activation`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        assertNotNull(
            "GalaxyWebSocketClient must hold a non-null durableSessionId after initial activation",
            client.getDurableSessionId()
        )
    }

    @Test
    fun `WsClient receives sessionContinuityEpoch 0 after initial activation`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        assertEquals(
            "GalaxyWebSocketClient must hold sessionContinuityEpoch=0 after initial activation " +
                "(no reconnect has occurred yet)",
            0,
            client.getSessionContinuityEpoch()
        )
    }

    @Test
    fun `WsClient durableSessionId matches RuntimeController record on activation`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val expectedId = controller.durableSessionContinuityRecord.value!!.durableSessionId
        assertEquals(
            "GalaxyWebSocketClient must hold the same durableSessionId as RuntimeController after activation",
            expectedId,
            client.getDurableSessionId()
        )
    }

    @Test
    fun `WsClient sessionContinuityEpoch matches RuntimeController record on activation`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val expectedEpoch = controller.durableSessionContinuityRecord.value!!.sessionContinuityEpoch
        assertEquals(
            "GalaxyWebSocketClient must hold the same sessionContinuityEpoch as RuntimeController after activation",
            expectedEpoch,
            client.getSessionContinuityEpoch()
        )
    }

    // ── WsClient — durable identity preserved after reconnect ─────────────────

    @Test
    fun `WsClient durableSessionId is unchanged after disconnect-reconnect cycle`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val idBeforeReconnect = client.getDurableSessionId()
        assertNotNull("Precondition: durableSessionId must be set before reconnect", idBeforeReconnect)

        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals(
            "durable_session_id must be identical in WsClient before and after a transparent reconnect — " +
                "V2 continuity bridge expects the same ID to recognize a resuming device, not a new one",
            idBeforeReconnect,
            client.getDurableSessionId()
        )
    }

    @Test
    fun `WsClient durableSessionId is unchanged after second disconnect-reconnect cycle`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val originalId = client.getDurableSessionId()

        client.simulateDisconnected()
        client.simulateConnected()
        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals(
            "durable_session_id must remain stable across two consecutive reconnect cycles",
            originalId,
            client.getDurableSessionId()
        )
    }

    @Test
    fun `WsClient durableSessionId is unchanged after three consecutive reconnect cycles`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val originalId = client.getDurableSessionId()

        repeat(3) {
            client.simulateDisconnected()
            client.simulateConnected()
        }

        assertEquals(
            "durable_session_id must remain stable across three consecutive reconnect cycles",
            originalId,
            client.getDurableSessionId()
        )
    }

    // ── WsClient — continuity epoch increments on reconnect ───────────────────

    @Test
    fun `WsClient sessionContinuityEpoch is 1 after first reconnect`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        assertEquals("Precondition: epoch must be 0 before reconnect", 0, client.getSessionContinuityEpoch())

        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals(
            "session_continuity_epoch must be 1 in WsClient after the first transparent reconnect — " +
                "V2 continuity bridge uses this to distinguish reconnect from fresh session",
            1,
            client.getSessionContinuityEpoch()
        )
    }

    @Test
    fun `WsClient sessionContinuityEpoch is 2 after second reconnect`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals("Precondition: epoch must be 1 after first reconnect", 1, client.getSessionContinuityEpoch())

        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals(
            "session_continuity_epoch must be 2 in WsClient after the second transparent reconnect",
            2,
            client.getSessionContinuityEpoch()
        )
    }

    @Test
    fun `WsClient sessionContinuityEpoch increments match RuntimeController record`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        client.simulateDisconnected()
        client.simulateConnected()

        val controllerEpoch = controller.durableSessionContinuityRecord.value!!.sessionContinuityEpoch
        val wsClientEpoch = client.getSessionContinuityEpoch()

        assertEquals(
            "WsClient sessionContinuityEpoch must equal RuntimeController record epoch after reconnect",
            controllerEpoch,
            wsClientEpoch
        )
    }

    // ── WsClient — epoch changes only under intended conditions ───────────────

    @Test
    fun `WsClient sessionContinuityEpoch is 0 immediately after activation with no reconnect`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        assertEquals(
            "session_continuity_epoch must be 0 immediately after initial activation — " +
                "epoch only increments on RECONNECT_RECOVERY, not on initial attach",
            0,
            client.getSessionContinuityEpoch()
        )
    }

    @Test
    fun `WsClient sessionContinuityEpoch does not increment on activation alone`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val epochAfterActivation = client.getSessionContinuityEpoch()

        // No disconnect/reconnect — epoch must not change.
        assertEquals(
            "session_continuity_epoch must not increment without an actual reconnect event",
            epochAfterActivation,
            client.getSessionContinuityEpoch()
        )
    }

    @Test
    fun `stop then reactivate produces epoch 0 in WsClient (fresh era, not continuation)`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        // Perform a reconnect to bump the epoch to 1.
        client.simulateDisconnected()
        client.simulateConnected()
        assertEquals("Precondition: epoch must be 1 before stop", 1, client.getSessionContinuityEpoch())

        // Explicit stop — durable era ends.
        controller.stop()

        // Second activation — must start a fresh era at epoch 0.
        controller.setActiveForTest()

        assertEquals(
            "session_continuity_epoch must be 0 after stop+reactivate — a new era starts from epoch 0, " +
                "not from where the previous era left off",
            0,
            client.getSessionContinuityEpoch()
        )
    }

    @Test
    fun `stop then reactivate produces a different durableSessionId in WsClient (fresh era)`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val firstEraId = client.getDurableSessionId()!!

        controller.stop()
        controller.setActiveForTest()

        val secondEraId = client.getDurableSessionId()!!

        assertNotEquals(
            "A new activation era after stop() must produce a fresh durable_session_id — " +
                "the old era identity must not carry into the new era",
            firstEraId,
            secondEraId
        )
    }

    // ── WsClient — identity cleared on stop() ────────────────────────────────

    @Test
    fun `WsClient durableSessionId is null after stop()`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        assertNotNull("Precondition: durableSessionId must be set before stop", client.getDurableSessionId())

        controller.stop()

        assertNull(
            "GalaxyWebSocketClient durableSessionId must be null after stop() — " +
                "a stale era identity must not be carried into the next handshake",
            client.getDurableSessionId()
        )
    }

    @Test
    fun `WsClient sessionContinuityEpoch is null after stop()`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        assertEquals(
            "Precondition: sessionContinuityEpoch must be 0 before stop (initial activation, no reconnect)",
            0,
            client.getSessionContinuityEpoch()
        )

        controller.stop()

        assertNull(
            "GalaxyWebSocketClient sessionContinuityEpoch must be null after stop()",
            client.getSessionContinuityEpoch()
        )
    }

    @Test
    fun `WsClient continuity identity is null after stop() even following reconnect cycles`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        // Drive through two reconnect cycles before stopping.
        client.simulateDisconnected()
        client.simulateConnected()
        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals("Precondition: epoch must be 2 before stop", 2, client.getSessionContinuityEpoch())

        controller.stop()

        assertNull(
            "GalaxyWebSocketClient durableSessionId must be null after stop(), even following reconnect cycles",
            client.getDurableSessionId()
        )
        assertNull(
            "GalaxyWebSocketClient sessionContinuityEpoch must be null after stop(), even following reconnect cycles",
            client.getSessionContinuityEpoch()
        )
    }

    // ── WsClient — identity cleared on invalidateSession() ───────────────────

    @Test
    fun `WsClient durableSessionId is null after invalidateSession()`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        assertNotNull("Precondition: durableSessionId must be set before invalidation", client.getDurableSessionId())

        controller.invalidateSession()

        assertNull(
            "GalaxyWebSocketClient durableSessionId must be null after invalidateSession() — " +
                "invalidated identity must not be presented to V2 as a re-attach hint",
            client.getDurableSessionId()
        )
    }

    @Test
    fun `WsClient sessionContinuityEpoch is null after invalidateSession()`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        assertEquals(
            "Precondition: sessionContinuityEpoch must be 0 before invalidation (initial activation, no reconnect)",
            0,
            client.getSessionContinuityEpoch()
        )

        controller.invalidateSession()

        assertNull(
            "GalaxyWebSocketClient sessionContinuityEpoch must be null after invalidateSession()",
            client.getSessionContinuityEpoch()
        )
    }

    // ── RuntimeController / WsClient — cross-object consistency after reconnect

    @Test
    fun `RuntimeController epoch matches WsClient epoch after reconnect`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        client.simulateDisconnected()
        client.simulateConnected()

        val controllerEpoch = controller.durableSessionContinuityRecord.value!!.sessionContinuityEpoch
        val wsEpoch = client.getSessionContinuityEpoch()

        assertEquals(
            "RuntimeController.durableSessionContinuityRecord.sessionContinuityEpoch " +
                "must equal GalaxyWebSocketClient.getSessionContinuityEpoch() after reconnect — " +
                "both must reflect the same incremented epoch for the V2 continuity bridge to consume",
            controllerEpoch,
            wsEpoch
        )
    }

    @Test
    fun `RuntimeController durableSessionId matches WsClient durableSessionId after reconnect`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        client.simulateDisconnected()
        client.simulateConnected()

        val controllerDurableId = controller.durableSessionContinuityRecord.value!!.durableSessionId
        val wsDurableId = client.getDurableSessionId()

        assertEquals(
            "RuntimeController.durableSessionContinuityRecord.durableSessionId " +
                "must equal GalaxyWebSocketClient.getDurableSessionId() after reconnect — " +
                "both must carry the same stable era identity",
            controllerDurableId,
            wsDurableId
        )
    }

    @Test
    fun `RuntimeController and WsClient agree across three reconnect cycles`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        repeat(3) { cycleIndex ->
            client.simulateDisconnected()
            client.simulateConnected()

            val controllerRecord = controller.durableSessionContinuityRecord.value!!
            assertEquals(
                "RuntimeController durableSessionId must match WsClient after reconnect cycle ${cycleIndex + 1}",
                controllerRecord.durableSessionId,
                client.getDurableSessionId()
            )
            assertEquals(
                "RuntimeController epoch must match WsClient after reconnect cycle ${cycleIndex + 1}",
                controllerRecord.sessionContinuityEpoch,
                client.getSessionContinuityEpoch()
            )
        }
    }

    // ── V2 wire key contract ──────────────────────────────────────────────────

    @Test
    fun `KEY_DURABLE_SESSION_ID wire value is durable_session_id`() {
        assertEquals(
            "The durable_session_id wire key must match the V2 canonical field name used in device_register",
            "durable_session_id",
            DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID
        )
    }

    @Test
    fun `KEY_SESSION_CONTINUITY_EPOCH wire value is session_continuity_epoch`() {
        assertEquals(
            "The session_continuity_epoch wire key must match the V2 canonical field name used in device_register",
            "session_continuity_epoch",
            DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH
        )
    }

    @Test
    fun `wire keys are stable and non-blank`() {
        assertTrue(
            "KEY_DURABLE_SESSION_ID must be non-blank",
            DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID.isNotBlank()
        )
        assertTrue(
            "KEY_SESSION_CONTINUITY_EPOCH must be non-blank",
            DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH.isNotBlank()
        )
    }
}
