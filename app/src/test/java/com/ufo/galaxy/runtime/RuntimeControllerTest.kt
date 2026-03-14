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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [RuntimeController] covering lifecycle transitions, registration failure
 * and fallback, remote/local handoff, and LoopController cancellation.
 *
 * All dependencies are replaced by lightweight in-memory fakes; no Android framework
 * or network is required. Tests run on the JVM via [runBlocking].
 */
class RuntimeControllerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies ─────────────────────────────────────────────────────

    /** Planner that always returns a single tap step. */
    private class SingleStepPlannerService : LocalPlannerService {
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
            localPlanner = LocalPlanner(SingleStepPlannerService()),
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
        loopController: LoopController = buildLoopController(),
        settings: InMemoryAppSettings = InMemoryAppSettings(),
        crossDeviceEnabled: Boolean = false,
        timeoutMs: Long = 100L // very short for tests
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = crossDeviceEnabled
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = loopController,
            registrationTimeoutMs = timeoutMs
        )
        return controller to client
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial state is Idle`() {
        val (controller, _) = buildController()
        assertTrue("Initial state should be Idle", controller.state.value is RuntimeController.RuntimeState.Idle)
    }

    // ── stop transitions to LocalOnly ─────────────────────────────────────────

    @Test
    fun `stop transitions to LocalOnly`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val (controller, _) = buildController(settings = settings)
        controller.stop()
        assertTrue(
            "State should be LocalOnly after stop()",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }

    @Test
    fun `stop disables cross-device in settings`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val (controller, _) = buildController(settings = settings)
        controller.stop()
        assertFalse("settings.crossDeviceEnabled should be false after stop()", settings.crossDeviceEnabled)
    }

    // ── startWithTimeout: failure when WS cannot connect ─────────────────────

    @Test
    fun `startWithTimeout returns false when WS cannot connect within timeout`() = runBlocking {
        val (controller, _) = buildController(timeoutMs = 200L)
        // WS server is not running, so connection will fail quickly
        val result = controller.startWithTimeout()
        assertFalse("startWithTimeout must return false when WS is unreachable", result)
    }

    @Test
    fun `startWithTimeout transitions to Failed then LocalOnly on timeout`() = runBlocking {
        val (controller, _) = buildController(timeoutMs = 200L)
        controller.startWithTimeout()
        // After failure the controller should end up in LocalOnly (after auto-fallback).
        val finalState = controller.state.value
        assertTrue(
            "Final state should be LocalOnly after registration failure, got $finalState",
            finalState is RuntimeController.RuntimeState.LocalOnly
        )
    }

    @Test
    fun `startWithTimeout emits registrationError on failure`() = runBlocking {
        val (controller, _) = buildController(timeoutMs = 200L)

        var errorReceived: String? = null
        val job = launch {
            errorReceived = controller.registrationError.first()
        }

        controller.startWithTimeout()
        job.join()

        assertNotNull("registrationError should emit a failure reason", errorReceived)
        assertTrue(
            "Failure reason should be non-empty",
            errorReceived!!.isNotEmpty()
        )
    }

    @Test
    fun `startWithTimeout falls back to LocalOnly and disables cross-device in settings`() = runBlocking {
        val settings = InMemoryAppSettings()
        val (controller, _) = buildController(settings = settings, timeoutMs = 200L)
        controller.startWithTimeout()
        assertFalse(
            "crossDeviceEnabled should be false after fallback",
            settings.crossDeviceEnabled
        )
        assertTrue(
            "State should be LocalOnly after fallback",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }

    // ── LoopController remote task handoff ────────────────────────────────────

    @Test
    fun `onRemoteTaskStarted sets loopController isRemoteTaskActive to true`() {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)
        assertFalse("isRemoteTaskActive should be false initially", loopController.isRemoteTaskActive)
        controller.onRemoteTaskStarted()
        assertTrue("isRemoteTaskActive should be true after onRemoteTaskStarted", loopController.isRemoteTaskActive)
    }

    @Test
    fun `onRemoteTaskFinished clears loopController isRemoteTaskActive`() {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)
        controller.onRemoteTaskStarted()
        assertTrue(loopController.isRemoteTaskActive)
        controller.onRemoteTaskFinished()
        assertFalse("isRemoteTaskActive should be false after onRemoteTaskFinished", loopController.isRemoteTaskActive)
    }

    @Test
    fun `loopController execute returns CANCELLED when remoteTaskActive is true`() = runBlocking {
        val loopController = buildLoopController()
        loopController.cancelForRemoteTask()  // Simulate remote task started
        val result = loopController.execute("tap the button")
        assertEquals(
            "LoopController should return CANCELLED when remote task is active",
            LoopController.STATUS_CANCELLED,
            result.status
        )
        assertEquals(LoopController.STOP_BLOCKED_BY_REMOTE, result.stopReason)
    }

    @Test
    fun `loopController execute succeeds after clearRemoteTaskBlock`() = runBlocking {
        val loopController = buildLoopController()
        loopController.cancelForRemoteTask()
        loopController.clearRemoteTaskBlock()
        val result = loopController.execute("tap the button")
        assertEquals(
            "LoopController should succeed after remote task block is cleared",
            LoopController.STATUS_SUCCESS,
            result.status
        )
    }

    // ── LoopController cancel during execution ────────────────────────────────

    @Test
    fun `loopController cancelForRemoteTask sets cancel flag`() = runBlocking {
        val loopController = buildLoopController()
        // isRemoteTaskActive = false initially, so execute() starts.
        // We immediately cancel after construction to test the flag path.
        loopController.cancelForRemoteTask()
        val result = loopController.execute("some task")
        assertEquals(LoopController.STATUS_CANCELLED, result.status)
    }

    // ── InputRouter (MessageRouter) integration with settings ─────────────────

    @Test
    fun `MessageRouter routes LOCAL when crossDeviceEnabled is false`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        var localCalled = false
        val router = com.ufo.galaxy.network.MessageRouter(
            settings = settings,
            webSocketClient = client
        ) { _ -> localCalled = true }
        val mode = router.route("open WeChat")
        assertEquals(com.ufo.galaxy.network.MessageRouter.RouteMode.LOCAL, mode)
        assertTrue("Local fallback must be called in OFF mode", localCalled)
    }

    @Test
    fun `MessageRouter routes ERROR when crossDeviceEnabled true but WS disconnected`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = true)
        var errorCalled = false
        val router = com.ufo.galaxy.network.MessageRouter(
            settings = settings,
            webSocketClient = client,
            onError = { errorCalled = true }
        ) { _ -> }
        val mode = router.route("open WeChat")
        assertEquals(com.ufo.galaxy.network.MessageRouter.RouteMode.ERROR, mode)
        assertTrue("onError must be called when WS unavailable", errorCalled)
    }
}
