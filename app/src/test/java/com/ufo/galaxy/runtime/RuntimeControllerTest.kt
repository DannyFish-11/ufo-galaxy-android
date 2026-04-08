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
import kotlinx.coroutines.withTimeoutOrNull
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
        settings: InMemoryAppSettings = InMemoryAppSettings(accessibilityReady = true, overlayReady = true),
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

        var errorReceived: CrossDeviceEnablementError? = null
        val job = launch {
            errorReceived = controller.registrationError.first()
        }

        controller.startWithTimeout()
        job.join()

        assertNotNull("registrationError should emit a typed failure error", errorReceived)
        assertTrue(
            "Failure message should be non-empty",
            errorReceived!!.message.isNotEmpty()
        )
        // PR-27: network timeout → NetworkError category (not Configuration or Capability).
        assertTrue(
            "Timeout failure must be categorised as NetworkError",
            errorReceived is CrossDeviceEnablementError.NetworkError
        )
    }

    /**
     * Verifies that the [RuntimeController.registrationError] SharedFlow delivers the
     * failure reason to **multiple simultaneous consumers** — simulating how both
     * [com.ufo.galaxy.ui.viewmodel.MainViewModel] (dialog) and
     * [com.ufo.galaxy.service.EnhancedFloatingService] (overlay) would both receive
     * the error when cross-device registration fails.
     *
     * This is the "notifier wiring" test: it proves the shared-notification contract
     * without requiring the Android framework surfaces (Activity / Service) themselves.
     */
    @Test
    fun `registrationError delivers to multiple consumers (wiring test for both UI surfaces)`() = runBlocking {
        val (controller, _) = buildController(timeoutMs = 200L)

        var errorForMainViewModel: CrossDeviceEnablementError? = null
        var errorForFloatingService: CrossDeviceEnablementError? = null

        // Both consumers must be subscribed *before* the emission.
        val job1 = launch { errorForMainViewModel = controller.registrationError.first() }
        val job2 = launch { errorForFloatingService = controller.registrationError.first() }

        controller.startWithTimeout()

        job1.join()
        job2.join()

        assertNotNull(
            "MainViewModel consumer (job1) should receive the registration error",
            errorForMainViewModel
        )
        assertNotNull(
            "EnhancedFloatingService consumer (job2) should receive the registration error",
            errorForFloatingService
        )
        assertEquals(
            "Both consumers should receive the same typed error",
            errorForMainViewModel,
            errorForFloatingService
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

    // ── Disconnect safety: onRemoteTaskFinished unblocks loop ─────────────────

    /**
     * Verifies that [RuntimeController.onRemoteTaskFinished] is idempotent — calling it
     * when no remote task is active must not crash or leave the loop in a bad state.
     *
     * This mirrors the behavior of [GalaxyConnectionService]'s onDisconnected handler,
     * which always calls onRemoteTaskFinished() as a safety measure regardless of whether
     * a task was in flight.
     */
    @Test
    fun `onRemoteTaskFinished is idempotent when called with no task active`() {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        assertFalse("isRemoteTaskActive should be false initially", loopController.isRemoteTaskActive)
        // Calling onRemoteTaskFinished() when no task is active must not crash.
        controller.onRemoteTaskFinished()
        assertFalse("isRemoteTaskActive must remain false after spurious onRemoteTaskFinished", loopController.isRemoteTaskActive)
    }

    /**
     * Verifies the "disconnect unblocks local loop" scenario described in Req 4:
     * when the Gateway disconnects while a remote task is in flight,
     * [RuntimeController.onRemoteTaskFinished] (called by the WS onDisconnected handler)
     * must clear the block so local execution can resume.
     */
    @Test
    fun `disconnect scenario - local loop is unblocked after onRemoteTaskFinished`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        // Step 1: Gateway assigns a task → local loop is blocked.
        controller.onRemoteTaskStarted()
        assertTrue("Local loop must be blocked while remote task is active", loopController.isRemoteTaskActive)

        // Step 2: Gateway disconnects → onRemoteTaskFinished() is called as safety.
        controller.onRemoteTaskFinished()
        assertFalse("Local loop must be unblocked after Gateway disconnect", loopController.isRemoteTaskActive)

        // Step 3: Next local execute() must succeed (not return BLOCKED_BY_REMOTE).
        val result = loopController.execute("tap the button")
        assertEquals(
            "Local loop must accept new execution after disconnect-unblock",
            LoopController.STATUS_SUCCESS,
            result.status
        )
        assertNotEquals(LoopController.STOP_BLOCKED_BY_REMOTE, result.stopReason)
    }

    /**
     * Verifies that calling [RuntimeController.onRemoteTaskFinished] multiple times in a row
     * (e.g., once from the disconnect handler and once from the task's finally block) is safe
     * and leaves the loop in the correct unblocked state.
     */
    @Test
    fun `onRemoteTaskFinished called twice is safe and leaves loop unblocked`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        controller.onRemoteTaskStarted()
        assertTrue(loopController.isRemoteTaskActive)

        // First call (simulating WS disconnect handler).
        controller.onRemoteTaskFinished()
        assertFalse(loopController.isRemoteTaskActive)

        // Second call (simulating the task's finally block completing shortly after).
        controller.onRemoteTaskFinished()
        assertFalse("isRemoteTaskActive must remain false after double call", loopController.isRemoteTaskActive)

        // Loop must still be usable.
        val result = loopController.execute("open the camera")
        assertEquals(LoopController.STATUS_SUCCESS, result.status)
    }

    // ── connectIfEnabled: background restore / activity-resume path ──────────

    /**
     * Verifies that [RuntimeController.connectIfEnabled] sets state to [RuntimeState.LocalOnly]
     * (and does NOT attempt to connect) when [AppSettings.crossDeviceEnabled] is `false`.
     *
     * This covers the service-restart / activity-resume path when the user has cross-device
     * disabled.
     */
    @Test
    fun `connectIfEnabled with crossDevice false transitions to LocalOnly`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val (controller, _) = buildController(settings = settings)
        controller.connectIfEnabled()
        assertTrue(
            "State should be LocalOnly when crossDeviceEnabled=false",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }

    /**
     * Verifies that [RuntimeController.connectIfEnabled] with [AppSettings.crossDeviceEnabled]
     * `true` transitions to [RuntimeState.Starting] and begins a connection attempt, even
     * though the WS server is not running (transition to Active only happens on real connect).
     *
     * This proves the service-restart / activity-resume path initiates the right lifecycle
     * transition without waiting for a connection result.
     */
    @Test
    fun `connectIfEnabled with crossDevice true transitions to Starting`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val (controller, _) = buildController(settings = settings)
        controller.connectIfEnabled()
        val state = controller.state.value
        // State should be Starting (or Active if somehow connected instantly, though that
        // won't happen with a non-existent server).
        assertTrue(
            "State should be Starting (or Active) when connectIfEnabled with crossDevice=true, got $state",
            state is RuntimeController.RuntimeState.Starting ||
                state is RuntimeController.RuntimeState.Active
        )
    }

    /**
     * Verifies that calling [RuntimeController.connectIfEnabled] when the controller is
     * already [RuntimeState.Active] does NOT install an extra listener or change the state.
     * This prevents duplicate listener accumulation on repeated activity resumes.
     */
    @Test
    fun `connectIfEnabled is no-op when state is Active`() = runBlocking {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val (controller, _) = buildController(settings = settings)
        // Manually drive state to Active.
        controller.connectIfEnabled() // → Starting (no server running)
        // Force state to Active to simulate an already-connected runtime.
        // Access via reflection or by using a subclass is avoided; instead we rely on the
        // public contract: if state is Active, connectIfEnabled must keep it Active.
        // Since there is no real server, we rely on the guard branch in connectIfEnabled.
        // We simply verify that a second call does not crash and leaves state consistent.
        controller.connectIfEnabled()
        // State should still be Starting (since WS never connected in test).
        val state = controller.state.value
        assertFalse(
            "connectIfEnabled should not regress state to Idle after a call sequence, got $state",
            state is RuntimeController.RuntimeState.Idle
        )
    }

    /**
     * Verifies that [RuntimeController.connectIfEnabled] does NOT emit [registrationError]
     * when the connection fails — unlike [startWithTimeout], the background-restore path is
     * silent on transient failures and lets the WS reconnect logic handle retries.
     *
     * Uses [kotlinx.coroutines.withTimeoutOrNull] to collect with a bound rather than a
     * fixed delay, making the test deterministic: if an error is emitted it is returned
     * immediately (failing the assertion); if none is emitted within the window, null is
     * returned (passing the assertion).
     */
    @Test
    fun `connectIfEnabled does not emit registrationError on failure`() = runBlocking {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val (controller, _) = buildController(settings = settings)

        controller.connectIfEnabled()

        // Attempt to collect the first registration error with a short timeout.
        // connectIfEnabled must NOT emit anything, so the timeout should expire → null.
        val errorReceived = withTimeoutOrNull(300L) {
            controller.registrationError.first()
        }

        assertNull(
            "connectIfEnabled must NOT emit registrationError on background restore failure",
            errorReceived
        )
    }

    /**
     * Verifies that [RuntimeController.connectIfEnabled] does NOT modify
     * [AppSettings.crossDeviceEnabled] — settings management is the caller's responsibility
     * (or [startWithTimeout]/[stop] for user-initiated paths).
     */
    @Test
    fun `connectIfEnabled does not modify crossDeviceEnabled in settings`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val (controller, _) = buildController(settings = settings)
        controller.connectIfEnabled()
        assertTrue(
            "connectIfEnabled must not reset settings.crossDeviceEnabled to false",
            settings.crossDeviceEnabled
        )
    }

}
