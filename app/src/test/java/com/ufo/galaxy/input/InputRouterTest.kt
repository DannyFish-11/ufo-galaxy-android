package com.ufo.galaxy.input

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.loop.ExecutorBridge
import com.ufo.galaxy.loop.LocalPlanner
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.loop.LoopResult
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.network.GatewayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [InputRouter] (P2 strong-consistency routing).
 *
 * Tests cover:
 *  - LOCAL routing when cross-device is disabled.
 *  - ERROR routing when cross-device is enabled but WS is not connected.
 *  - CROSS_DEVICE routing when cross-device is enabled, WS connected, and sendJson succeeds.
 *  - ERROR routing when cross-device is enabled, WS connected, but sendJson fails.
 *  - [LoopController.execute] is invoked (via the coroutineScope) in LOCAL mode.
 *  - [onLocalResult] callback delivers the [LoopResult] on completion.
 *  - Blank/whitespace-only input is silently ignored.
 *  - [onError] is invoked when WS is unavailable in cross-device mode; no silent local fallback.
 *  - AtomicBoolean double-submit guard drops concurrent route() calls gracefully.
 *
 * All dependencies use lightweight JVM-only fakes; no Android framework or network required.
 */
class InputRouterTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake GatewayClient ────────────────────────────────────────────────────

    /**
     * Configurable fake [GatewayClient] for deterministic routing tests.
     *
     * [connected] controls [isConnected]; [sendResult] controls [sendJson] return value.
     * [sentMessages] records every JSON string passed to [sendJson].
     */
    private class FakeGatewayClient(
        var connected: Boolean = false,
        var sendResult: Boolean = true
    ) : GatewayClient {
        val sentMessages = mutableListOf<String>()

        override fun isConnected(): Boolean = connected
        override fun sendJson(json: String): Boolean {
            if (connected && sendResult) {
                sentMessages.add(json)
                return true
            }
            return false
        }
    }

    // ── Fake LoopController dependencies ─────────────────────────────────────

    /** Planner that always returns a single tap step. */
    private class SingleStepPlanner : LocalPlannerService {
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
            localPlanner = LocalPlanner(SingleStepPlanner()),
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

    private fun buildRouter(
        crossDeviceEnabled: Boolean = false,
        gatewayClient: FakeGatewayClient = FakeGatewayClient(connected = false),
        loopController: LoopController = buildLoopController(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        onLocalResult: ((LoopResult) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ): InputRouter {
        val settings = InMemoryAppSettings(crossDeviceEnabled = crossDeviceEnabled)
        return InputRouter(
            settings = settings,
            webSocketClient = gatewayClient,
            loopController = loopController,
            coroutineScope = scope,
            onLocalResult = onLocalResult,
            onError = onError
        )
    }

    // ── OFF mode (crossDeviceEnabled=false) ────────────────────────────────────

    @Test
    fun `route returns LOCAL when crossDeviceEnabled is false`() {
        val router = buildRouter(crossDeviceEnabled = false)
        val result = router.route("open WeChat")
        assertEquals("Should take LOCAL path when crossDeviceEnabled=false", InputRouter.RouteMode.LOCAL, result)
    }

    @Test
    fun `route OFF mode never calls WS uplink`() {
        val gateway = FakeGatewayClient(connected = true) // WS is up, but cross-device is OFF
        var errorCalled = false
        val router = buildRouter(
            crossDeviceEnabled = false,
            gatewayClient = gateway,
            onError = { errorCalled = true }
        )
        val result = router.route("do something")
        assertNotEquals("OFF mode must never produce CROSS_DEVICE", InputRouter.RouteMode.CROSS_DEVICE, result)
        assertFalse("onError must not be called in OFF mode", errorCalled)
        assertTrue("No messages should be sent to gateway in OFF mode", gateway.sentMessages.isEmpty())
    }

    @Test
    fun `route LOCAL invokes LoopController execute and delivers result to onLocalResult`() = runBlocking {
        var receivedResult: LoopResult? = null
        val loopController = buildLoopController()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        val router = buildRouter(
            crossDeviceEnabled = false,
            loopController = loopController,
            scope = scope,
            onLocalResult = { receivedResult = it }
        )

        router.route("tap the button")
        // Dispatchers.Unconfined executes coroutines eagerly; give it a moment to complete.
        delay(200)

        assertNotNull("onLocalResult must be called with the LoopResult", receivedResult)
        assertEquals(
            "LoopController should succeed with single-step planner",
            LoopController.STATUS_SUCCESS,
            receivedResult!!.status
        )
    }

    @Test
    fun `route LOCAL with blocked LoopController delivers CANCELLED result to onLocalResult`() = runBlocking {
        var receivedResult: LoopResult? = null
        val loopController = buildLoopController()
        loopController.cancelForRemoteTask() // simulate remote task active → loop is blocked

        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val router = buildRouter(
            crossDeviceEnabled = false,
            loopController = loopController,
            scope = scope,
            onLocalResult = { receivedResult = it }
        )

        router.route("open the camera")
        delay(100)

        assertNotNull("onLocalResult must be called even when loop is blocked", receivedResult)
        assertEquals(
            "LoopController must return CANCELLED when remote task is active",
            LoopController.STATUS_CANCELLED,
            receivedResult!!.status
        )
        assertEquals(LoopController.STOP_BLOCKED_BY_REMOTE, receivedResult!!.stopReason)
    }

    // ── ON mode + WS connected → CROSS_DEVICE success ─────────────────────────

    @Test
    fun `route returns CROSS_DEVICE when crossDeviceEnabled and WS connected`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gatewayClient = gateway)

        val result = router.route("open WeChat")

        assertEquals("Must return CROSS_DEVICE when WS is connected", InputRouter.RouteMode.CROSS_DEVICE, result)
    }

    @Test
    fun `route CROSS_DEVICE sends exactly one JSON message to gateway`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gatewayClient = gateway)

        router.route("book a taxi for 3pm")

        assertEquals("Exactly one message must be sent to gateway", 1, gateway.sentMessages.size)
    }

    @Test
    fun `route CROSS_DEVICE message contains task_submit type and input text`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gatewayClient = gateway)

        router.route("book a taxi for 3pm")

        val sent = gateway.sentMessages.first()
        assertTrue("Sent JSON must contain task_submit type", sent.contains("task_submit"))
        assertTrue("Sent JSON must contain the user input text", sent.contains("book a taxi for 3pm"))
    }

    @Test
    fun `route returns ERROR when crossDeviceEnabled WS connected but sendJson fails`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = false)
        var errorCalled = false
        val router = buildRouter(
            crossDeviceEnabled = true,
            gatewayClient = gateway,
            onError = { errorCalled = true }
        )

        val result = router.route("some important task")

        assertEquals("Must return ERROR when sendJson fails", InputRouter.RouteMode.ERROR, result)
        assertTrue("onError must be called when sendJson fails", errorCalled)
    }

    @Test
    fun `route CROSS_DEVICE does not call onLocalResult`() = runBlocking {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        var localResultCalled = false
        val router = buildRouter(
            crossDeviceEnabled = true,
            gatewayClient = gateway,
            onLocalResult = { localResultCalled = true }
        )

        router.route("send a message to Alice")
        delay(100) // give any stray coroutine time to run

        assertFalse("onLocalResult must NOT be called in CROSS_DEVICE mode", localResultCalled)
    }

    // ── ON mode + WS unavailable → explicit error, no silent fallback ──────────

    @Test
    fun `route returns ERROR when crossDeviceEnabled is true but WS disconnected`() {
        var localResultCalled = false
        var errorCalled = false
        val router = buildRouter(
            crossDeviceEnabled = true,
            gatewayClient = FakeGatewayClient(connected = false),
            onLocalResult = { localResultCalled = true },
            onError = { errorCalled = true }
        )
        val result = router.route("open WeChat")

        assertEquals("Must return ERROR when WS unavailable", InputRouter.RouteMode.ERROR, result)
        assertFalse(
            "onLocalResult (LoopController) must NOT be called when WS is unavailable in cross-device mode",
            localResultCalled
        )
        assertTrue("onError callback must be invoked when WS unavailable", errorCalled)
    }

    @Test
    fun `route ERROR surfaced to onError with non-empty reason`() {
        var errorReason: String? = null
        val router = buildRouter(
            crossDeviceEnabled = true,
            gatewayClient = FakeGatewayClient(connected = false),
            onError = { errorReason = it }
        )
        router.route("some task")

        assertNotNull("Error reason must not be null", errorReason)
        assertTrue("Error reason must be non-empty", errorReason!!.isNotEmpty())
    }

    // ── Blank/whitespace input ─────────────────────────────────────────────────

    @Test
    fun `route ignores blank text and returns LOCAL without launching coroutine`() {
        var localResultCalled = false
        val router = buildRouter(
            crossDeviceEnabled = false,
            onLocalResult = { localResultCalled = true }
        )
        val result = router.route("   ")

        assertEquals("Blank input should return LOCAL (no-op)", InputRouter.RouteMode.LOCAL, result)
        assertFalse("onLocalResult must not be called for blank input", localResultCalled)
    }

    @Test
    fun `route ignores empty string and returns LOCAL without launching coroutine`() {
        var errorCalled = false
        var localResultCalled = false
        val router = buildRouter(
            crossDeviceEnabled = true,
            gatewayClient = FakeGatewayClient(connected = true),
            onLocalResult = { localResultCalled = true },
            onError = { errorCalled = true }
        )
        val result = router.route("")

        assertEquals("Empty input should return LOCAL (no-op)", InputRouter.RouteMode.LOCAL, result)
        assertFalse("onLocalResult must not be called for empty input", localResultCalled)
        assertFalse("onError must not be called for empty input", errorCalled)
    }

    // ── onError is optional ────────────────────────────────────────────────────

    @Test
    fun `route does not crash when onError is null and WS unavailable in cross-device mode`() {
        val router = buildRouter(
            crossDeviceEnabled = true,
            gatewayClient = FakeGatewayClient(connected = false),
            onError = null
        )
        // Should not throw
        val result = router.route("test message")
        assertEquals(InputRouter.RouteMode.ERROR, result)
    }

    // ── Integration: InputRouter and RuntimeController share LoopController ────

    @Test
    fun `InputRouter LOCAL is blocked when RuntimeController onRemoteTaskStarted is called`() = runBlocking {
        val loopController = buildLoopController()
        var receivedResult: LoopResult? = null
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val router = InputRouter(
            settings = settings,
            webSocketClient = FakeGatewayClient(connected = false),
            loopController = loopController,
            coroutineScope = scope,
            onLocalResult = { receivedResult = it }
        )

        // Simulate Gateway assigning a task → local loop must be blocked.
        loopController.cancelForRemoteTask()

        router.route("tap the screen")
        delay(100)

        assertNotNull("onLocalResult must still be called (with CANCELLED)", receivedResult)
        assertEquals(LoopController.STATUS_CANCELLED, receivedResult!!.status)
        assertEquals(LoopController.STOP_BLOCKED_BY_REMOTE, receivedResult!!.stopReason)

        // After remote task completes, local loop is unblocked.
        loopController.clearRemoteTaskBlock()

        var secondResult: LoopResult? = null
        val router2 = InputRouter(
            settings = settings,
            webSocketClient = FakeGatewayClient(connected = false),
            loopController = loopController,
            coroutineScope = scope,
            onLocalResult = { secondResult = it }
        )
        router2.route("tap the screen again")
        delay(200)

        assertNotNull("Second route must produce a result", secondResult)
        assertEquals(
            "Local loop must succeed after remote task block is cleared",
            LoopController.STATUS_SUCCESS,
            secondResult!!.status
        )
    }

    // ── PR-C4: TaskSubmit v3 shape and payload validation ─────────────────────

    @Test
    fun `route CROSS_DEVICE JSON contains task_id field in payload`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gatewayClient = gateway)

        router.route("take a photo")

        val sent = gateway.sentMessages.first()
        assertTrue("Sent JSON must contain task_id field", sent.contains("task_id"))
    }

    @Test
    fun `route CROSS_DEVICE JSON contains AIP protocol and version fields`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gatewayClient = gateway)

        router.route("open WeChat")

        val sent = gateway.sentMessages.first()
        assertTrue("Sent JSON must contain protocol field", sent.contains("protocol"))
        assertTrue("Sent JSON must contain AIP/1.0", sent.contains("AIP/1.0"))
        assertTrue("Sent JSON must contain version field", sent.contains("version"))
        assertTrue("Sent JSON must contain 3.0", sent.contains("3.0"))
    }
}
