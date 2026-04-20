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
 * PR-G — runtime_attachment_session_id continuity: client-side identity lifecycle management.
 *
 * Acceptance and regression test suite validating all PR-G requirements for
 * `runtime_attachment_session_id` persistence, reconnect reuse, and wire propagation:
 *
 *  1. **Identity generation** — [RuntimeController.runtimeAttachmentSessionId] is `null`
 *     before activation and non-null once [RuntimeController.setActiveForTest] assigns it.
 *
 *  2. **Reconnect identity preservation** — On a transparent WS disconnect + reconnect,
 *     the same `runtime_attachment_session_id` is preserved so V2 continuity consumer
 *     recognises the returning device as resuming the same attachment, not starting fresh.
 *
 *  3. **AttachedRuntimeSession sessionId alignment** — After `setActiveForTest` and after
 *     reconnect, `AttachedRuntimeSession.sessionId` equals `runtimeAttachmentSessionId`.
 *
 *  4. **Stop resets identity** — [RuntimeController.stop] clears
 *     `runtimeAttachmentSessionId` to `null`, ending the attachment era; the next
 *     activation will generate a fresh ID.
 *
 *  5. **InvalidateSession resets identity** — [RuntimeController.invalidateSession]
 *     similarly clears `runtimeAttachmentSessionId` so stale identity is not reused after
 *     explicit invalidation.
 *
 *  6. **GalaxyWebSocketClient wire propagation** — After `setActiveForTest`, the
 *     [GalaxyWebSocketClient] holds the same `runtime_attachment_session_id` that the
 *     controller does, ensuring it will be included in the next `device_register` /
 *     `capability_report` handshake.
 *
 *  7. **Reconnect wire propagation** — After a disconnect+reconnect cycle, the
 *     [GalaxyWebSocketClient] still holds the *same* (preserved) session ID, so the
 *     reconnect handshake carries the stable attachment identity the V2 server expects.
 *
 *  8. **Stop clears WS client identity** — After [RuntimeController.stop], the
 *     [GalaxyWebSocketClient] no longer holds a `runtime_attachment_session_id`, so the
 *     next reconnect starts without accidentally carrying a stale identity.
 *
 *  9. **Repeated reconnects do not generate new IDs** — Multiple disconnect+reconnect
 *     cycles all preserve the same `runtime_attachment_session_id` throughout the
 *     activation era.
 *
 * ## Test matrix
 *
 * ### RuntimeController.runtimeAttachmentSessionId — initial state
 *  - runtimeAttachmentSessionId is null before activation
 *
 * ### RuntimeController.runtimeAttachmentSessionId — setActiveForTest assigns an ID
 *  - runtimeAttachmentSessionId is non-null after setActiveForTest
 *  - runtimeAttachmentSessionId is non-blank after setActiveForTest
 *
 * ### RuntimeController.runtimeAttachmentSessionId — reconnect preserves ID
 *  - runtimeAttachmentSessionId is preserved after disconnect+reconnect cycle
 *  - runtimeAttachmentSessionId is preserved after second disconnect+reconnect cycle
 *  - repeated reconnects do not generate distinct IDs
 *
 * ### RuntimeController — AttachedRuntimeSession.sessionId alignment
 *  - attachedSession sessionId equals runtimeAttachmentSessionId after activation
 *  - attachedSession sessionId equals runtimeAttachmentSessionId after reconnect
 *
 * ### RuntimeController.runtimeAttachmentSessionId — stop() resets to null
 *  - runtimeAttachmentSessionId is null after stop()
 *  - runtimeAttachmentSessionId is null after stop() even after a reconnect cycle
 *
 * ### RuntimeController.runtimeAttachmentSessionId — new era after stop+reactivate
 *  - new activation after stop() generates a different runtimeAttachmentSessionId
 *
 * ### RuntimeController.runtimeAttachmentSessionId — invalidateSession() resets to null
 *  - runtimeAttachmentSessionId is null after invalidateSession()
 *
 * ### GalaxyWebSocketClient — identity propagation
 *  - getRuntimeAttachmentSessionId() matches controller runtimeAttachmentSessionId after activation
 *  - getRuntimeAttachmentSessionId() is preserved after disconnect+reconnect
 *  - getRuntimeAttachmentSessionId() is null after stop()
 *  - getRuntimeAttachmentSessionId() is null after invalidateSession()
 */
class PrGRuntimeAttachmentSessionIdContinuityTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies ─────────────────────────────────────────────────────

    private class TrivialPlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep("tap", "button"))
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

    // ── runtimeAttachmentSessionId — initial state ────────────────────────────

    @Test
    fun `runtimeAttachmentSessionId is null before activation`() {
        val (controller, _) = buildController()
        assertNull(
            "runtimeAttachmentSessionId must be null before any activation",
            controller.runtimeAttachmentSessionId
        )
    }

    // ── runtimeAttachmentSessionId — setActiveForTest assigns an ID ───────────

    @Test
    fun `runtimeAttachmentSessionId is non-null after setActiveForTest`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()
        assertNotNull(
            "runtimeAttachmentSessionId must be non-null after setActiveForTest",
            controller.runtimeAttachmentSessionId
        )
    }

    @Test
    fun `runtimeAttachmentSessionId is non-blank after setActiveForTest`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()
        assertTrue(
            "runtimeAttachmentSessionId must be non-blank after setActiveForTest",
            controller.runtimeAttachmentSessionId!!.isNotBlank()
        )
    }

    // ── runtimeAttachmentSessionId — reconnect preserves ID ──────────────────

    @Test
    fun `runtimeAttachmentSessionId is preserved after disconnect-reconnect cycle`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val idBeforeDisconnect = controller.runtimeAttachmentSessionId

        client.simulateDisconnected()
        client.simulateConnected()

        val idAfterReconnect = controller.runtimeAttachmentSessionId

        assertEquals(
            "runtime_attachment_session_id must be stable across a transparent reconnect",
            idBeforeDisconnect,
            idAfterReconnect
        )
    }

    @Test
    fun `runtimeAttachmentSessionId is preserved after second disconnect-reconnect cycle`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val originalId = controller.runtimeAttachmentSessionId

        client.simulateDisconnected()
        client.simulateConnected()
        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals(
            "runtime_attachment_session_id must be stable across multiple reconnect cycles",
            originalId,
            controller.runtimeAttachmentSessionId
        )
    }

    @Test
    fun `repeated reconnects do not generate distinct attachment session IDs`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val originalId = controller.runtimeAttachmentSessionId

        // Three disconnect+reconnect cycles.
        repeat(3) {
            client.simulateDisconnected()
            client.simulateConnected()
        }

        assertEquals(
            "runtime_attachment_session_id must not change across repeated reconnect cycles",
            originalId,
            controller.runtimeAttachmentSessionId
        )
    }

    // ── AttachedRuntimeSession.sessionId alignment ────────────────────────────

    @Test
    fun `attachedSession sessionId equals runtimeAttachmentSessionId after activation`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()

        val session = controller.attachedSession.value
        assertNotNull("attachedSession must be non-null after setActiveForTest", session)
        assertEquals(
            "AttachedRuntimeSession.sessionId must equal runtimeAttachmentSessionId",
            controller.runtimeAttachmentSessionId,
            session!!.sessionId
        )
    }

    @Test
    fun `attachedSession sessionId equals runtimeAttachmentSessionId after reconnect`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        client.simulateDisconnected()
        client.simulateConnected()

        val session = controller.attachedSession.value
        assertNotNull("attachedSession must be non-null after reconnect", session)
        assertEquals(
            "AttachedRuntimeSession.sessionId must equal runtimeAttachmentSessionId after reconnect",
            controller.runtimeAttachmentSessionId,
            session!!.sessionId
        )
    }

    // ── runtimeAttachmentSessionId — stop() resets to null ───────────────────

    @Test
    fun `runtimeAttachmentSessionId is null after stop()`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()

        assertNotNull(
            "Precondition: runtimeAttachmentSessionId must be set before stop",
            controller.runtimeAttachmentSessionId
        )

        controller.stop()

        assertNull(
            "runtimeAttachmentSessionId must be null after stop()",
            controller.runtimeAttachmentSessionId
        )
    }

    @Test
    fun `runtimeAttachmentSessionId is null after stop() even after a reconnect cycle`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        client.simulateDisconnected()
        client.simulateConnected()

        controller.stop()

        assertNull(
            "runtimeAttachmentSessionId must be null after stop(), even after reconnect cycle",
            controller.runtimeAttachmentSessionId
        )
    }

    // ── runtimeAttachmentSessionId — new era after stop+reactivate ───────────

    @Test
    fun `new activation after stop() generates a different runtimeAttachmentSessionId`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()

        val firstEraId = controller.runtimeAttachmentSessionId!!
        controller.stop()

        // Second activation — should be a fresh era.
        controller.setActiveForTest()
        val secondEraId = controller.runtimeAttachmentSessionId!!

        assertNotEquals(
            "Each new activation era after stop() must generate a fresh runtimeAttachmentSessionId",
            firstEraId,
            secondEraId
        )
    }

    // ── runtimeAttachmentSessionId — invalidateSession() resets to null ───────

    @Test
    fun `runtimeAttachmentSessionId is null after invalidateSession()`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()

        assertNotNull(
            "Precondition: runtimeAttachmentSessionId must be set before invalidation",
            controller.runtimeAttachmentSessionId
        )

        controller.invalidateSession()

        assertNull(
            "runtimeAttachmentSessionId must be null after invalidateSession()",
            controller.runtimeAttachmentSessionId
        )
    }

    // ── GalaxyWebSocketClient — identity propagation ──────────────────────────

    @Test
    fun `getRuntimeAttachmentSessionId() matches controller id after activation`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        assertEquals(
            "GalaxyWebSocketClient must hold the same runtime_attachment_session_id as controller",
            controller.runtimeAttachmentSessionId,
            client.getRuntimeAttachmentSessionId()
        )
    }

    @Test
    fun `getRuntimeAttachmentSessionId() is preserved after disconnect-reconnect`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val idBeforeReconnect = client.getRuntimeAttachmentSessionId()

        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals(
            "GalaxyWebSocketClient must hold the preserved runtime_attachment_session_id after reconnect",
            idBeforeReconnect,
            client.getRuntimeAttachmentSessionId()
        )
    }

    @Test
    fun `getRuntimeAttachmentSessionId() is null after stop()`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        assertNotNull(
            "Precondition: client must hold session ID before stop",
            client.getRuntimeAttachmentSessionId()
        )

        controller.stop()

        assertNull(
            "GalaxyWebSocketClient runtime_attachment_session_id must be null after stop()",
            client.getRuntimeAttachmentSessionId()
        )
    }

    @Test
    fun `getRuntimeAttachmentSessionId() is null after invalidateSession()`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        assertNotNull(
            "Precondition: client must hold session ID before invalidation",
            client.getRuntimeAttachmentSessionId()
        )

        controller.invalidateSession()

        assertNull(
            "GalaxyWebSocketClient runtime_attachment_session_id must be null after invalidateSession()",
            client.getRuntimeAttachmentSessionId()
        )
    }

    // ── Existing PR-1 continuity invariant not broken ─────────────────────────

    @Test
    fun `durableSessionId is still preserved after disconnect-reconnect (PR-G does not break PR-1)`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val durableIdBefore = controller.durableSessionContinuityRecord.value!!.durableSessionId

        client.simulateDisconnected()
        client.simulateConnected()

        val durableIdAfter = controller.durableSessionContinuityRecord.value!!.durableSessionId

        assertEquals(
            "PR-G must not break PR-1: durableSessionId must remain stable across reconnects",
            durableIdBefore,
            durableIdAfter
        )
    }

    @Test
    fun `runtimeAttachmentSessionId and durableSessionId are distinct values`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()

        val attachmentId = controller.runtimeAttachmentSessionId!!
        val durableId = controller.durableSessionContinuityRecord.value!!.durableSessionId

        assertNotEquals(
            "runtimeAttachmentSessionId and durableSessionId must be distinct UUID values",
            attachmentId,
            durableId
        )
    }
}
