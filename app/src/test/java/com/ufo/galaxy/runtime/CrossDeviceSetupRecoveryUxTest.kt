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
import delay
import kotlinx.coroutines.launch
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
 * PR-27 — Product-grade cross-device setup, retry, and recovery UX.
 *
 * Verifies that:
 *  1. **Error differentiation**: [RuntimeController.setupError] emits a typed
 *     [CrossDeviceSetupError] on every registration failure so that consumers can
 *     present category-appropriate recovery actions rather than a single generic
 *     "Retry" option.
 *  2. **Category classification**: [CrossDeviceSetupError.classify] maps raw failure
 *     reasons to correct categories:
 *     - No gateway configured → [CrossDeviceSetupError.Category.CONFIGURATION].
 *     - Network/timeout failure with gateway configured → [CrossDeviceSetupError.Category.NETWORK].
 *     - Capability/permission-related keyword in reason → [CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED].
 *  3. **Retry semantics**: [CrossDeviceSetupError.canRetry] is `true` for NETWORK and
 *     CAPABILITY_NOT_SATISFIED errors, `false` for CONFIGURATION errors.
 *  4. **Reconnect flow**: [RuntimeController.reconnect] (stop + startWithTimeout) stops
 *     the runtime, then attempts a fresh start; failure still emits [setupError] on the
 *     new attempt and ends in LocalOnly.
 *  5. **Recovery stability**: after any number of failed attempts the runtime converges
 *     to [RuntimeController.RuntimeState.LocalOnly], never leaves state dangling in
 *     [RuntimeController.RuntimeState.Starting] or [RuntimeController.RuntimeState.Failed].
 *  6. **Independence**: [setupError] and [registrationError] are emitted atomically in the
 *     same [handleFailure] call; both flows receive an event for every failure.
 *  7. **Save-settings reconnect**: settings mutation followed by reconnect emits a fresh
 *     [setupError] that reflects the updated gateway configuration state.
 *  8. **No duplicate authority**: [RuntimeController] remains the sole authority — no
 *     second path produces conflicting setup-error or retry state.
 *
 * ## Dependency
 *
 * Assumes PR-26 baseline: [RuntimeController.registrationError] and
 * [RuntimeController.takeoverFailure] are already in place.  [setupError] is a new
 * parallel flow (PR-27) that does not replace or interfere with either prior flow.
 *
 * ## Test matrix
 *
 * ### CrossDeviceSetupError.classify — category derivation
 *  - classify with gateway not configured returns CONFIGURATION category
 *  - classify with gateway configured and generic reason returns NETWORK category
 *  - classify with "permission" keyword returns CAPABILITY_NOT_SATISFIED
 *  - classify with "accessibility" keyword returns CAPABILITY_NOT_SATISFIED
 *  - classify with "overlay" keyword returns CAPABILITY_NOT_SATISFIED
 *  - classify with "model" keyword returns CAPABILITY_NOT_SATISFIED
 *  - classify with "capability" keyword returns CAPABILITY_NOT_SATISFIED
 *
 * ### CrossDeviceSetupError.canRetry
 *  - CONFIGURATION error has canRetry = false
 *  - NETWORK error has canRetry = true
 *  - CAPABILITY_NOT_SATISFIED error has canRetry = true
 *
 * ### CrossDeviceSetupError wire values
 *  - CONFIGURATION wireValue is "configuration_error"
 *  - NETWORK wireValue is "network_error"
 *  - CAPABILITY_NOT_SATISFIED wireValue is "capability_not_satisfied"
 *
 * ### setupError emission on registration failure
 *  - setupError emits on startWithTimeout failure
 *  - setupError category is NETWORK when gateway is configured (no real server)
 *  - setupError reason matches registrationError reason
 *  - registrationError and setupError both emit on the same failure
 *
 * ### Recovery stability after failure
 *  - state is LocalOnly after startWithTimeout failure
 *  - settings.crossDeviceEnabled is false after failure
 *  - multiple sequential failures all end in LocalOnly
 *  - setupError emits once per failure (not doubled)
 *
 * ### Reconnect flow
 *  - reconnect() after stop returns false when server is unreachable
 *  - reconnect() ends in LocalOnly
 *  - reconnect() emits setupError on the new attempt
 *  - reconnect() emits registrationError on the new attempt
 *
 * ### Save-settings reconnect
 *  - settings change before reconnect is reflected in new setupError classification
 *  - gateway configured before reconnect → NETWORK category (not CONFIGURATION)
 *  - gateway cleared before reconnect → CONFIGURATION category
 *
 * ### setupError independence from takeoverFailure
 *  - notifyTakeoverFailed does not emit setupError
 *  - setupError does not emit on notifyTakeoverFailed
 */
class CrossDeviceSetupRecoveryUxTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies ─────────────────────────────────────────────────────

    private class TrivialPlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(steps = emptyList())
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
        loopController: LoopController = buildLoopController(),
        timeoutMs: Long = 150L
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = loopController,
            registrationTimeoutMs = timeoutMs
        )
        return controller to client
    }

    // ── CrossDeviceSetupError.classify — category derivation ─────────────────

    @Test
    fun `classify with gateway not configured returns CONFIGURATION category`() {
        val err = CrossDeviceSetupError.classify(reason = "无法连接到 Gateway", isGatewayConfigured = false)
        assertEquals(
            "Unconfigured gateway must yield CONFIGURATION category",
            CrossDeviceSetupError.Category.CONFIGURATION,
            err.category
        )
    }

    @Test
    fun `classify with gateway configured and generic timeout reason returns NETWORK category`() {
        val err = CrossDeviceSetupError.classify(
            reason = "跨设备注册超时（15秒）。请检查 Gateway 是否在线。",
            isGatewayConfigured = true
        )
        assertEquals(
            "Timeout with configured gateway must yield NETWORK category",
            CrossDeviceSetupError.Category.NETWORK,
            err.category
        )
    }

    @Test
    fun `classify with gateway configured and connection refused reason returns NETWORK category`() {
        val err = CrossDeviceSetupError.classify(
            reason = "跨设备注册失败：无法连接到 Gateway。请检查网络或服务器设置。",
            isGatewayConfigured = true
        )
        assertEquals(
            "Connection failure with configured gateway must yield NETWORK category",
            CrossDeviceSetupError.Category.NETWORK,
            err.category
        )
    }

    @Test
    fun `classify with permission keyword returns CAPABILITY_NOT_SATISFIED`() {
        val err = CrossDeviceSetupError.classify(
            reason = "Missing permission: SYSTEM_ALERT_WINDOW",
            isGatewayConfigured = true
        )
        assertEquals(CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED, err.category)
    }

    @Test
    fun `classify with accessibility keyword returns CAPABILITY_NOT_SATISFIED`() {
        val err = CrossDeviceSetupError.classify(
            reason = "Accessibility service is not enabled",
            isGatewayConfigured = true
        )
        assertEquals(CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED, err.category)
    }

    @Test
    fun `classify with overlay keyword returns CAPABILITY_NOT_SATISFIED`() {
        val err = CrossDeviceSetupError.classify(
            reason = "Overlay permission not granted",
            isGatewayConfigured = true
        )
        assertEquals(CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED, err.category)
    }

    @Test
    fun `classify with model keyword returns CAPABILITY_NOT_SATISFIED`() {
        val err = CrossDeviceSetupError.classify(
            reason = "Model files not found on device",
            isGatewayConfigured = true
        )
        assertEquals(CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED, err.category)
    }

    @Test
    fun `classify with capability keyword returns CAPABILITY_NOT_SATISFIED`() {
        val err = CrossDeviceSetupError.classify(
            reason = "Required capability not satisfied: LOCAL_INFERENCE",
            isGatewayConfigured = true
        )
        assertEquals(CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED, err.category)
    }

    // ── CrossDeviceSetupError.canRetry ────────────────────────────────────────

    @Test
    fun `CONFIGURATION error has canRetry false`() {
        val err = CrossDeviceSetupError.classify(reason = "no gateway", isGatewayConfigured = false)
        assertFalse(
            "CONFIGURATION errors cannot be retried without a settings change",
            err.canRetry
        )
    }

    @Test
    fun `NETWORK error has canRetry true`() {
        val err = CrossDeviceSetupError.classify(
            reason = "timeout — gateway unreachable",
            isGatewayConfigured = true
        )
        assertTrue("NETWORK errors should be retryable", err.canRetry)
    }

    @Test
    fun `CAPABILITY_NOT_SATISFIED error has canRetry true`() {
        val err = CrossDeviceSetupError.classify(
            reason = "Accessibility service not enabled",
            isGatewayConfigured = true
        )
        assertTrue("CAPABILITY_NOT_SATISFIED errors should offer a retry after granting", err.canRetry)
    }

    // ── CrossDeviceSetupError wire values ─────────────────────────────────────

    @Test
    fun `CONFIGURATION wireValue is configuration_error`() {
        assertEquals("configuration_error", CrossDeviceSetupError.Category.CONFIGURATION.wireValue)
    }

    @Test
    fun `NETWORK wireValue is network_error`() {
        assertEquals("network_error", CrossDeviceSetupError.Category.NETWORK.wireValue)
    }

    @Test
    fun `CAPABILITY_NOT_SATISFIED wireValue is capability_not_satisfied`() {
        assertEquals(
            "capability_not_satisfied",
            CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED.wireValue
        )
    }

    @Test
    fun `classify preserves reason field unchanged`() {
        val reason = "Custom failure reason with details"
        val err = CrossDeviceSetupError.classify(reason = reason, isGatewayConfigured = true)
        assertEquals("reason must be forwarded unchanged", reason, err.reason)
    }

    // ── setupError emission on registration failure ───────────────────────────

    @Test
    fun `setupError emits on startWithTimeout failure`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)

        var received: CrossDeviceSetupError? = null
        val job = launch { received = controller.setupError.first() }

        controller.startWithTimeout()
        job.join()

        assertNotNull("setupError must emit on startWithTimeout failure", received)
    }

    @Test
    fun `setupError category is NETWORK when gateway is configured but server unreachable`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)

        var received: CrossDeviceSetupError? = null
        val job = launch { received = controller.setupError.first() }

        controller.startWithTimeout()
        job.join()

        assertEquals(
            "With configured gateway and unreachable server the category must be NETWORK",
            CrossDeviceSetupError.Category.NETWORK,
            received!!.category
        )
    }

    @Test
    fun `setupError and registrationError both emit on the same failure`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)

        var setupErrReceived: CrossDeviceSetupError? = null
        var regErrReceived: String? = null

        val j1 = launch { setupErrReceived = controller.setupError.first() }
        val j2 = launch { regErrReceived = controller.registrationError.first() }

        controller.startWithTimeout()
        j1.join()
        j2.join()

        assertNotNull("setupError must emit on failure", setupErrReceived)
        assertNotNull("registrationError must emit on the same failure", regErrReceived)
    }

    @Test
    fun `setupError reason matches registrationError reason`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)

        var setupErrReason: String? = null
        var regErrReason: String? = null

        val j1 = launch { setupErrReason = controller.setupError.first().reason }
        val j2 = launch { regErrReason = controller.registrationError.first() }

        controller.startWithTimeout()
        j1.join()
        j2.join()

        assertEquals(
            "setupError.reason must equal the registrationError string",
            regErrReason,
            setupErrReason
        )
    }

    // ── Recovery stability after failure ─────────────────────────────────────

    @Test
    fun `state is LocalOnly after startWithTimeout failure`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)
        controller.startWithTimeout()
        assertTrue(
            "State must be LocalOnly after registration failure",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }

    @Test
    fun `settings crossDeviceEnabled is false after failure`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)
        controller.startWithTimeout()
        assertFalse(
            "settings.crossDeviceEnabled must be reverted to false after failure",
            settings.crossDeviceEnabled
        )
    }

    @Test
    fun `multiple sequential failures all end in LocalOnly`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 100L)
        repeat(3) {
            controller.startWithTimeout()
            assertTrue(
                "State must be LocalOnly after each failure (attempt ${it + 1})",
                controller.state.value is RuntimeController.RuntimeState.LocalOnly
            )
        }
    }

    @Test
    fun `setupError emits once per failure attempt`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 100L)

        val received = mutableListOf<CrossDeviceSetupError>()
        val collectJob = launch {
            controller.setupError.collect { received.add(it) }
        }

        controller.startWithTimeout()
        delay(50)
        controller.startWithTimeout()
        delay(50)

        collectJob.cancel()

        assertEquals("Each failure attempt must emit exactly one setupError", 2, received.size)
    }

    // ── Reconnect flow ────────────────────────────────────────────────────────

    @Test
    fun `reconnect returns false when server is unreachable`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)
        val result = controller.reconnect()
        assertFalse("reconnect must return false when the gateway is unreachable", result)
    }

    @Test
    fun `reconnect ends in LocalOnly`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)
        controller.reconnect()
        assertTrue(
            "State must be LocalOnly after failed reconnect",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }

    @Test
    fun `reconnect emits setupError on the new start attempt`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)

        var received: CrossDeviceSetupError? = null
        val job = launch { received = controller.setupError.first() }

        controller.reconnect()
        job.join()

        assertNotNull("reconnect must emit setupError when the new start attempt fails", received)
    }

    @Test
    fun `reconnect emits registrationError on the new start attempt`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)

        var received: String? = null
        val job = launch { received = controller.registrationError.first() }

        controller.reconnect()
        job.join()

        assertNotNull("reconnect must emit registrationError when the new start attempt fails", received)
    }

    // ── Save-settings reconnect ───────────────────────────────────────────────

    @Test
    fun `gateway configured before reconnect yields NETWORK category not CONFIGURATION`() = runBlocking {
        // Start with no gateway, fail, then configure and reconnect.
        val settings = InMemoryAppSettings()
        val (controller, _) = buildController(settings = settings, timeoutMs = 100L)

        // First attempt: no gateway → CONFIGURATION error.
        var firstErr: CrossDeviceSetupError? = null
        val j1 = launch { firstErr = controller.setupError.first() }
        controller.startWithTimeout()
        j1.join()
        assertEquals(CrossDeviceSetupError.Category.CONFIGURATION, firstErr!!.category)

        // Now configure a gateway and reconnect.
        settings.gatewayHost = "10.0.0.1"

        var secondErr: CrossDeviceSetupError? = null
        val j2 = launch { secondErr = controller.setupError.first() }
        controller.reconnect()
        j2.join()

        assertEquals(
            "After configuring a gateway, setupError category must switch to NETWORK",
            CrossDeviceSetupError.Category.NETWORK,
            secondErr!!.category
        )
    }

    @Test
    fun `gateway cleared before reconnect yields CONFIGURATION category`() = runBlocking {
        // Start with gateway configured, then clear it and reconnect.
        val settings = InMemoryAppSettings(gatewayHost = "10.0.0.1")
        val (controller, _) = buildController(settings = settings, timeoutMs = 100L)

        // First attempt: gateway configured → NETWORK error.
        var firstErr: CrossDeviceSetupError? = null
        val j1 = launch { firstErr = controller.setupError.first() }
        controller.startWithTimeout()
        j1.join()
        assertEquals(CrossDeviceSetupError.Category.NETWORK, firstErr!!.category)

        // Clear the gateway.
        settings.gatewayHost = ""

        var secondErr: CrossDeviceSetupError? = null
        val j2 = launch { secondErr = controller.setupError.first() }
        controller.reconnect()
        j2.join()

        assertEquals(
            "After clearing the gateway, setupError category must switch to CONFIGURATION",
            CrossDeviceSetupError.Category.CONFIGURATION,
            secondErr!!.category
        )
    }

    // ── setupError independence from takeoverFailure ──────────────────────────

    @Test
    fun `notifyTakeoverFailed does not emit on setupError`() = runBlocking {
        val (controller, _) = buildController()

        var setupErrEmitted = false
        val collectJob = launch {
            withTimeoutOrNull(300L) {
                controller.setupError.collect { setupErrEmitted = true }
            }
        }

        // notifyTakeoverFailed is a takeover-level event, not a registration/setup event.
        controller.notifyTakeoverFailed(
            takeoverId = "to-001",
            taskId = "task-001",
            traceId = "trace-001",
            reason = "Pipeline exception",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )

        // Give a brief window for any spurious emission.
        delay(250)
        collectJob.cancel()

        assertFalse(
            "notifyTakeoverFailed must not emit on setupError (independent failure domains)",
            setupErrEmitted
        )
    }

    @Test
    fun `registrationError failure does not emit on takeoverFailure`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)

        var takeoverErrEmitted = false
        val collectJob = launch {
            withTimeoutOrNull(500L) {
                controller.takeoverFailure.collect { takeoverErrEmitted = true }
            }
        }

        controller.startWithTimeout()

        // Brief window for any spurious emission.
        delay(200)
        collectJob.cancel()

        assertFalse(
            "A registration failure must not emit on takeoverFailure (independent failure domains)",
            takeoverErrEmitted
        )
    }

    // ── setupError delivers to multiple consumers ─────────────────────────────

    @Test
    fun `setupError delivers to multiple concurrent consumers`() = runBlocking {
        val settings = InMemoryAppSettings(gatewayHost = "192.168.1.100")
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)

        var consumer1: CrossDeviceSetupError? = null
        var consumer2: CrossDeviceSetupError? = null

        val j1 = launch { consumer1 = controller.setupError.first() }
        val j2 = launch { consumer2 = controller.setupError.first() }

        controller.startWithTimeout()
        j1.join()
        j2.join()

        assertNotNull("First consumer must receive setupError", consumer1)
        assertNotNull("Second consumer must receive setupError", consumer2)
        assertEquals(
            "Both consumers must receive identical category",
            consumer1!!.category,
            consumer2!!.category
        )
    }
}
