package com.ufo.galaxy.speech

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.input.InputRouter
import com.ufo.galaxy.loop.ExecutorBridge
import com.ufo.galaxy.loop.LocalPlanner
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.network.GatewayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [NaturalLanguageInputManager].
 *
 * Validates that [NaturalLanguageInputManager] only collects input and forwards it to
 * the canonical [InputRouter] — it must not make routing decisions itself.
 * [InputRouter] routes all cross-device uplink through [GatewayClient] (implemented by
 * [com.ufo.galaxy.network.GalaxyWebSocketClient]), making it the sole transport backbone.
 *
 * These are pure JVM tests; no Android framework required.
 */
class NaturalLanguageInputManagerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake GatewayClient ────────────────────────────────────────────────────

    /**
     * Configurable fake [GatewayClient] that records sent messages without real network calls.
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

    private class FakePlannerService : LocalPlannerService {
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

    private class FakeGroundingService : LocalGroundingService {
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
            localPlanner = LocalPlanner(FakePlannerService()),
            executorBridge = ExecutorBridge(
                groundingService = FakeGroundingService(),
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
        gateway: FakeGatewayClient = FakeGatewayClient(connected = false),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    ): InputRouter {
        val settings = InMemoryAppSettings(crossDeviceEnabled = crossDeviceEnabled)
        return InputRouter(
            settings = settings,
            webSocketClient = gateway,
            loopController = buildLoopController(),
            coroutineScope = scope
        )
    }

    // ── submit() ──────────────────────────────────────────────────────────────

    @Test
    fun `submit returns true when cross-device enabled and WS connected`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)
        val manager = NaturalLanguageInputManager(router)

        val result = manager.submit("打开微信")

        assertTrue("submit must return true when input is routed via cross-device uplink", result)
        assertEquals("One message must be sent to the gateway", 1, gateway.sentMessages.size)
    }

    @Test
    fun `submit sends text via cross-device uplink containing the input`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)
        val manager = NaturalLanguageInputManager(router)

        manager.submit("打开微信")

        val sent = gateway.sentMessages.first()
        assertTrue("WS message must contain the submitted text", sent.contains("打开微信"))
    }

    @Test
    fun `submit trims whitespace before routing via cross-device uplink`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)
        val manager = NaturalLanguageInputManager(router)

        manager.submit("  发送消息  ")

        val sent = gateway.sentMessages.first()
        assertTrue("Trimmed text must appear in the WS message", sent.contains("发送消息"))
    }

    @Test
    fun `submit returns false when cross-device disabled`() {
        val router = buildRouter(crossDeviceEnabled = false)
        val manager = NaturalLanguageInputManager(router)

        val result = manager.submit("open WeChat")

        assertFalse("submit must return false when cross-device is disabled (local path)", result)
    }

    @Test
    fun `submit returns false for empty string`() {
        val router = buildRouter(crossDeviceEnabled = true,
            gateway = FakeGatewayClient(connected = true))
        val manager = NaturalLanguageInputManager(router)

        val result = manager.submit("")

        assertFalse("empty input must return false", result)
    }

    @Test
    fun `submit returns false for blank string`() {
        val router = buildRouter(crossDeviceEnabled = true,
            gateway = FakeGatewayClient(connected = true))
        val manager = NaturalLanguageInputManager(router)

        val result = manager.submit("   ")

        assertFalse("blank input must return false", result)
    }

    @Test
    fun `submit delegates routing decision to InputRouter not to NLInputManager`() {
        // NaturalLanguageInputManager must not decide local vs WS — it just forwards.
        // With crossDeviceEnabled=true and WS connected, both inputs go cross-device.
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)
        val manager = NaturalLanguageInputManager(router)

        manager.submit("task one")
        manager.submit("task two")

        assertEquals("both inputs must be forwarded to the cross-device uplink", 2,
            gateway.sentMessages.size)
    }

    // ── submitVoiceResult() ───────────────────────────────────────────────────

    @Test
    fun `submitVoiceResult forwards voice transcript via cross-device uplink`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)
        val manager = NaturalLanguageInputManager(router)

        manager.submitVoiceResult("打开设置")

        val sent = gateway.sentMessages.first()
        assertTrue("Voice transcript must appear in WS message", sent.contains("打开设置"))
    }

    @Test
    fun `submitVoiceResult returns false for empty transcript`() {
        val router = buildRouter(crossDeviceEnabled = true,
            gateway = FakeGatewayClient(connected = true))
        val manager = NaturalLanguageInputManager(router)

        val result = manager.submitVoiceResult("")

        assertFalse("empty voice transcript must return false", result)
    }

    @Test
    fun `submitVoiceResult behaves identically to submit`() {
        val gatewayForText = FakeGatewayClient(connected = true, sendResult = true)
        val gatewayForVoice = FakeGatewayClient(connected = true, sendResult = true)

        val routerForText = buildRouter(crossDeviceEnabled = true, gateway = gatewayForText)
        val routerForVoice = buildRouter(crossDeviceEnabled = true, gateway = gatewayForVoice)

        val textResult = NaturalLanguageInputManager(routerForText).submit("go home")
        val voiceResult = NaturalLanguageInputManager(routerForVoice).submitVoiceResult("go home")

        assertEquals("submit and submitVoiceResult must produce identical routing result",
            textResult, voiceResult)
        assertEquals("both must send exactly one message to the gateway",
            gatewayForText.sentMessages.size, gatewayForVoice.sentMessages.size)
    }

    // ── Routing decisions must stay in InputRouter ────────────────────────────

    @Test
    fun `NLInputManager does not call connect or disconnect on client directly`() {
        // NaturalLanguageInputManager must not touch the gateway client directly.
        // It only calls router.route() which may or may not use the client.
        val gateway = FakeGatewayClient(connected = false, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = false, gateway = gateway)
        val manager = NaturalLanguageInputManager(router)

        // After submit (local path), client must still be disconnected.
        manager.submit("hello")

        assertFalse(
            "NLInputManager must not connect the gateway client",
            gateway.isConnected()
        )
    }
}
