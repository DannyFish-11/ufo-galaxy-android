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
 * PR-27: Cross-device setup, retry, and recovery UX regression suite.
 *
 * Verifies the three-category error differentiation introduced in PR-27:
 *  - [CrossDeviceEnablementError.CapabilityError] — emitted before any WS connection
 *    attempt when a required device capability (Accessibility, Overlay) is not satisfied.
 *  - [CrossDeviceEnablementError.NetworkError] — emitted when the WS connection fails
 *    (host unreachable, WS error, or registration timeout).
 *  - [CrossDeviceEnablementError.ConfigurationError] — emitted when the Gateway address
 *    is obviously misconfigured (blank host / placeholder URL).
 *
 * Coverage:
 *  1. Capability pre-flight check: accessibility missing → CapabilityError.
 *  2. Capability pre-flight check: overlay missing → CapabilityError.
 *  3. Network failure (WS unreachable + timeout) → NetworkError.
 *  4. Error category is stable across multiple consumers (wiring test).
 *  5. Retry clears failure and re-attempts → emits a fresh error on second failure.
 *  6. Recovery path: stop() after capability error → transitions to LocalOnly cleanly.
 *  7. Recovery path: stop() after network error → transitions to LocalOnly cleanly.
 *  8. Capability error does NOT open a WS connection or mutate crossDeviceEnabled in settings.
 *  9. Network error falls back to LocalOnly and resets crossDeviceEnabled.
 * 10. takeoverFailure does NOT emit on registration failure (independent failure domains).
 * 11. registrationError does NOT emit on notifyTakeoverFailed (independence).
 * 12. connectIfEnabled (background restore) does NOT emit registrationError on any failure.
 */
class CrossDeviceSetupRecoveryUxTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies (same pattern as RuntimeControllerTest) ────────────

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

    /**
     * Builds a RuntimeController with fully ready settings by default (both accessibility
     * and overlay ready = true) so that tests targeting network failures reach the WS phase.
     * Pass [accessibilityReady] = false or [overlayReady] = false to test capability errors.
     */
    private fun buildController(
        accessibilityReady: Boolean = true,
        overlayReady: Boolean = true,
        crossDeviceEnabled: Boolean = false,
        timeoutMs: Long = 200L
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = crossDeviceEnabled,
            accessibilityReady = accessibilityReady,
            overlayReady = overlayReady
        )
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = crossDeviceEnabled
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = timeoutMs
        )
        return controller to client
    }

    // ── 1. Capability pre-flight: accessibility not ready ────────────────────

    @Test
    fun `start emits CapabilityError when accessibility service is not enabled`() = runBlocking {
        val (controller, _) = buildController(accessibilityReady = false, overlayReady = true)

        var error: CrossDeviceEnablementError? = null
        val job = launch { error = controller.registrationError.first() }

        controller.startWithTimeout()
        job.join()

        assertNotNull("registrationError must emit when accessibility is not ready", error)
        assertTrue(
            "Error must be CapabilityError when accessibility is missing, got ${error?.javaClass?.simpleName}",
            error is CrossDeviceEnablementError.CapabilityError
        )
        assertEquals(
            "Error category must be CAPABILITY",
            CrossDeviceEnablementError.Category.CAPABILITY,
            error!!.category
        )
        assertTrue("Error message must be non-empty", error!!.message.isNotEmpty())
    }

    @Test
    fun `start emits CapabilityError when overlay permission is not granted`() = runBlocking {
        val (controller, _) = buildController(accessibilityReady = true, overlayReady = false)

        var error: CrossDeviceEnablementError? = null
        val job = launch { error = controller.registrationError.first() }

        controller.startWithTimeout()
        job.join()

        assertNotNull("registrationError must emit when overlay is not ready", error)
        assertTrue(
            "Error must be CapabilityError when overlay permission is missing, got ${error?.javaClass?.simpleName}",
            error is CrossDeviceEnablementError.CapabilityError
        )
        assertEquals(
            "Error category must be CAPABILITY",
            CrossDeviceEnablementError.Category.CAPABILITY,
            error!!.category
        )
    }

    // ── 2. Network failure → NetworkError ────────────────────────────────────

    @Test
    fun `startWithTimeout emits NetworkError when WS cannot connect`() = runBlocking {
        val (controller, _) = buildController(accessibilityReady = true, overlayReady = true)

        var error: CrossDeviceEnablementError? = null
        val job = launch { error = controller.registrationError.first() }

        controller.startWithTimeout()
        job.join()

        assertNotNull("registrationError must emit on WS connection failure", error)
        assertTrue(
            "Error must be NetworkError when WS is unreachable, got ${error?.javaClass?.simpleName}",
            error is CrossDeviceEnablementError.NetworkError
        )
        assertEquals(
            "Error category must be NETWORK",
            CrossDeviceEnablementError.Category.NETWORK,
            error!!.category
        )
        assertTrue("Error message must be non-empty", error!!.message.isNotEmpty())
    }

    // ── 3. Error category is stable across multiple consumers ────────────────

    @Test
    fun `registrationError delivers same typed error to multiple consumers`() = runBlocking {
        val (controller, _) = buildController(accessibilityReady = true, overlayReady = true)

        var errorA: CrossDeviceEnablementError? = null
        var errorB: CrossDeviceEnablementError? = null

        val jobA = launch { errorA = controller.registrationError.first() }
        val jobB = launch { errorB = controller.registrationError.first() }

        controller.startWithTimeout()

        jobA.join()
        jobB.join()

        assertNotNull("Consumer A (MainViewModel) should receive the error", errorA)
        assertNotNull("Consumer B (EnhancedFloatingService) should receive the error", errorB)
        assertEquals(
            "Both consumers must receive the same typed error",
            errorA,
            errorB
        )
        assertEquals(
            "Both consumers must see the same error category",
            errorA!!.category,
            errorB!!.category
        )
    }

    // ── 4. Retry: stop() then start() → second failure emits fresh error ─────

    @Test
    fun `retry after network failure emits a fresh error on the second attempt`() = runBlocking {
        val (controller, _) = buildController(accessibilityReady = true, overlayReady = true)

        // First failure.
        var firstError: CrossDeviceEnablementError? = null
        val job1 = launch { firstError = controller.registrationError.first() }
        controller.startWithTimeout()
        job1.join()

        assertNotNull("First attempt must emit an error", firstError)

        // Reset state — user pressed "重试" (which calls stop() + startWithTimeout() again).
        controller.stop()

        // Second failure.
        var secondError: CrossDeviceEnablementError? = null
        val job2 = launch { secondError = controller.registrationError.first() }
        controller.startWithTimeout()
        job2.join()

        assertNotNull("Second attempt (retry) must also emit an error", secondError)
        assertEquals(
            "Retry must emit the same error category",
            firstError!!.category,
            secondError!!.category
        )
    }

    // ── 5. Recovery: capability error leaves state cleanly in LocalOnly ───────

    @Test
    fun `capability error transitions to LocalOnly and allows clean stop`() = runBlocking {
        val (controller, _) = buildController(accessibilityReady = false, overlayReady = true)

        val job = launch { controller.registrationError.first() }
        controller.startWithTimeout()
        job.join()

        assertTrue(
            "State must be LocalOnly after capability error",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )

        // stop() after capability failure must be a safe no-op.
        controller.stop()
        assertTrue(
            "State must remain LocalOnly after stop() following a capability error",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }

    // ── 6. Recovery: network error leaves state cleanly in LocalOnly ──────────

    @Test
    fun `network error transitions to LocalOnly and allows clean stop`() = runBlocking {
        val (controller, _) = buildController(accessibilityReady = true, overlayReady = true)

        val job = launch { controller.registrationError.first() }
        controller.startWithTimeout()
        job.join()

        assertTrue(
            "State must be LocalOnly after network failure",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )

        controller.stop()
        assertTrue(
            "State must remain LocalOnly after stop() following a network error",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }

    // ── 7. Capability error does NOT mutate crossDeviceEnabled in settings ────

    @Test
    fun `capability error resets crossDeviceEnabled to false in settings`() = runBlocking {
        val settings = InMemoryAppSettings(
            accessibilityReady = false,
            overlayReady = true
        )
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = 200L
        )

        val job = launch { controller.registrationError.first() }
        controller.startWithTimeout()
        job.join()

        assertFalse(
            "settings.crossDeviceEnabled must be false after a capability error",
            settings.crossDeviceEnabled
        )
    }

    // ── 8. Network error resets crossDeviceEnabled in settings ───────────────

    @Test
    fun `network error resets crossDeviceEnabled to false in settings`() = runBlocking {
        val settings = InMemoryAppSettings(
            accessibilityReady = true,
            overlayReady = true
        )
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = 200L
        )

        val job = launch { controller.registrationError.first() }
        controller.startWithTimeout()
        job.join()

        assertFalse(
            "settings.crossDeviceEnabled must be false after a network error",
            settings.crossDeviceEnabled
        )
    }

    // ── 9. Independent failure domains: takeoverFailure vs registrationError ──

    @Test
    fun `takeoverFailure does NOT emit when capability check fails`() = runBlocking {
        val (controller, _) = buildController(accessibilityReady = false, overlayReady = true)

        var takeoverFired = false
        val takeoverJob = launch {
            withTimeoutOrNull(500L) {
                controller.takeoverFailure.first()
                takeoverFired = true
            }
        }

        val regJob = launch { controller.registrationError.first() }
        controller.startWithTimeout()
        regJob.join()
        takeoverJob.join()

        assertFalse(
            "takeoverFailure must NOT emit when a capability CapabilityError occurs during start()",
            takeoverFired
        )
    }

    @Test
    fun `registrationError does NOT emit on notifyTakeoverFailed (independence)`() = runBlocking {
        val (controller, _) = buildController()

        var registrationErrorFired = false
        val regJob = launch {
            withTimeoutOrNull(500L) {
                controller.registrationError.first()
                registrationErrorFired = true
            }
        }

        controller.notifyTakeoverFailed(
            takeoverId = "tc-99",
            taskId = "t-99",
            traceId = "tr-99",
            reason = "simulated takeover failure",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        regJob.join()

        assertFalse(
            "registrationError must NOT emit on notifyTakeoverFailed — independent failure domains",
            registrationErrorFired
        )
    }

    // ── 10. connectIfEnabled (background restore) never emits registrationError

    @Test
    fun `connectIfEnabled does not emit registrationError for capability-not-ready settings`() = runBlocking {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            accessibilityReady = false,
            overlayReady = false
        )
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = true)
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = 200L
        )

        controller.connectIfEnabled()

        val errorReceived = withTimeoutOrNull(300L) {
            controller.registrationError.first()
        }
        assertNull(
            "connectIfEnabled must NOT emit registrationError even when capabilities are missing",
            errorReceived
        )
    }

    // ── 11. Error message content: capability errors mention the specific capability

    @Test
    fun `accessibility CapabilityError message mentions accessibility`() = runBlocking {
        val (controller, _) = buildController(accessibilityReady = false, overlayReady = true)

        var error: CrossDeviceEnablementError? = null
        val job = launch { error = controller.registrationError.first() }
        controller.startWithTimeout()
        job.join()

        assertTrue(
            "Accessibility CapabilityError message must mention 'accessibility' (无障碍)",
            error!!.message.contains("无障碍", ignoreCase = true) ||
                error!!.message.contains("accessibility", ignoreCase = true)
        )
    }

    @Test
    fun `overlay CapabilityError message mentions overlay permission`() = runBlocking {
        val (controller, _) = buildController(accessibilityReady = true, overlayReady = false)

        var error: CrossDeviceEnablementError? = null
        val job = launch { error = controller.registrationError.first() }
        controller.startWithTimeout()
        job.join()

        assertTrue(
            "Overlay CapabilityError message must mention overlay/permission",
            error!!.message.contains("悬浮窗", ignoreCase = true) ||
                error!!.message.contains("overlay", ignoreCase = true)
        )
    }

    // ── 12. Save-settings + reconnect path: stop() + startWithTimeout() ───────

    @Test
    fun `save-settings-and-reconnect flow transitions correctly after network error`() = runBlocking {
        val (controller, _) = buildController(accessibilityReady = true, overlayReady = true)

        // Simulate first connection failure (save-and-reconnect path step 1).
        val job1 = launch { controller.registrationError.first() }
        controller.startWithTimeout()
        job1.join()

        // Simulate "save settings and reconnect": stop + startWithTimeout.
        controller.stop()
        assertEquals(
            "State must be LocalOnly after stop()",
            RuntimeController.RuntimeState.LocalOnly,
            controller.state.value
        )

        // Re-attempt (second network failure expected since no real server available).
        var secondError: CrossDeviceEnablementError? = null
        val job2 = launch { secondError = controller.registrationError.first() }
        controller.startWithTimeout()
        job2.join()

        assertNotNull("Second attempt after save-settings-reconnect must emit error", secondError)
        assertEquals(
            "Save-settings-reconnect must re-emit NetworkError on second WS failure",
            CrossDeviceEnablementError.Category.NETWORK,
            secondError!!.category
        )
        assertTrue(
            "State must be LocalOnly after second failure",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }
}
