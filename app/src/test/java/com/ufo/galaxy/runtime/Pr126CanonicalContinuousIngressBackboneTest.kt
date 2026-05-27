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
import com.ufo.galaxy.network.OfflineTaskQueue
import com.ufo.galaxy.protocol.DeviceGroundingPayload
import com.ufo.galaxy.protocol.DeviceLocalPerceptionPayload
import com.ufo.galaxy.protocol.DevicePerceptionEmissionPayload
import com.ufo.galaxy.protocol.DeviceVisionPayload
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Pr126CanonicalContinuousIngressBackboneTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private class RecordingWebSocket : WebSocket {
        override fun request(): Request = Request.Builder().url("ws://localhost:9999").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    private class TrivialPlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep("tap", "tap the button"))
            )
        override fun replan(
            goal: String,
            constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String,
            screenshotBase64: String?
        ) = LocalPlannerService.PlanResult(steps = emptyList(), error = "no replan")
    }

    private class FakeGrounder : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(
            intent: String,
            screenshotBase64: String,
            width: Int,
            height: Int
        ) = LocalGroundingService.GroundingResult(
            x = 10,
            y = 10,
            confidence = 0.9f,
            element_description = "button"
        )
    }

    private class FakeAccessibilityExecutor : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    private class FakeScreenshotProvider : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        override fun screenWidth() = 1080
        override fun screenHeight() = 2400
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
        val settings = InMemoryAppSettings().apply { crossDeviceEnabled = true }
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = OfflineTaskQueue(prefs = null)
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L
        )
        return controller to client
    }

    private fun canonicalPerceptionPayload() =
        DevicePerceptionEmissionPayload(
            flow_id = "flow-continuous",
            task_id = "task-continuous",
            emission_kind = DevicePerceptionEmissionPayload.EMISSION_KIND_MULTIMODAL_PARTICIPATION_SIGNAL,
            perception_stage = DevicePerceptionEmissionPayload.STAGE_GROUNDING,
            carrier_semantics = DevicePerceptionEmissionPayload.CARRIER_SEMANTICS_MULTIMODAL_MAIN_CHAIN,
            participation_semantics = DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT,
            participates_in_multimodal_main_chain = true,
            vision_payload = DeviceVisionPayload(prompt_text = "tap login"),
            grounding_payload = DeviceGroundingPayload(
                intent = "tap login",
                input_width = 1080,
                input_height = 2400
            ),
            local_perception_payload = DeviceLocalPerceptionPayload(
                capture_present = true,
                planner_participated = true,
                grounding_participated = true
            )
        )

    @Test
    fun `unified continuous ingress construction folds device stream and perception into one backbone`() {
        val deviceStream = CanonicalContinuousIngressBackboneBuilder.deviceStreamSession(
            deviceId = "device-1",
            runtimeSessionId = "runtime-1",
            routeMode = "cross_device",
            connected = true
        )
        val perception = CanonicalContinuousIngressBackboneBuilder
            .continuousPerceptionSession(canonicalPerceptionPayload())

        val backbone = CanonicalContinuousIngressBackboneBuilder.assemble(deviceStream, perception)

        assertTrue(backbone.hasUnifiedContinuousIngress)
        assertEquals(
            setOf(
                CanonicalContinuousIngressEntry.ContinuousIngressFamily.DEVICE_STREAM_SESSION,
                CanonicalContinuousIngressEntry.ContinuousIngressFamily
                    .CONTINUOUS_PERCEPTION_SESSION_INPUT
            ),
            backbone.activeFamilies
        )
        assertTrue(backbone.supportsCanonicalCognitionBackbone)
    }

    @Test
    fun `runtime participation consumer stays fully attached until unified continuous ingress is present`() {
        val (controller, client) = buildController()
        client.installWebSocketForTest(RecordingWebSocket())
        controller.setActiveForTest()
        client.simulateConnected()

        val withoutContinuousIngress = controller.evaluateAuthoritativeParticipationSnapshot(
            readinessSatisfied = true,
            distributedRuntimeActivity = false
        )
        assertEquals(
            AndroidAuthoritativeParticipationTruth.State.FULLY_ATTACHED,
            withoutContinuousIngress.state
        )
        assertFalse(withoutContinuousIngress.canDispatch)

        assertTrue(client.sendDevicePerceptionEmission(canonicalPerceptionPayload()))

        val withContinuousIngress = controller.evaluateAuthoritativeParticipationSnapshot(
            readinessSatisfied = true,
            distributedRuntimeActivity = false
        )
        assertEquals(
            AndroidAuthoritativeParticipationTruth.State.DISPATCH_ELIGIBLE,
            withContinuousIngress.state
        )
        assertTrue(withContinuousIngress.canDispatch)
    }

    @Test
    fun `device stream path is promoted from transport session into canonical cognition carrier`() {
        val backbone = CanonicalContinuousIngressBackboneBuilder.assemble(
            CanonicalContinuousIngressBackboneBuilder.deviceStreamSession(
                deviceId = "device-1",
                runtimeSessionId = "runtime-1",
                routeMode = "cross_device",
                connected = true
            )
        )

        val deviceStreamEntry = backbone.activeEntries.single()
        assertEquals(
            CanonicalContinuousIngressEntry.ContinuousIngressFamily.DEVICE_STREAM_SESSION,
            deviceStreamEntry.family
        )
        assertEquals(
            CanonicalContinuousIngressEntry.CognitionRole.CANONICAL_COGNITION_CARRIER,
            deviceStreamEntry.cognitionRole
        )
        assertTrue(backbone.promotesDeviceStreamIntoCanonicalCognition)
    }

    @Test
    fun `galaxy websocket client publishes unified continuous ingress after canonical perception emission`() {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = OfflineTaskQueue(prefs = null)
        )
        client.installWebSocketForTest(RecordingWebSocket())
        client.simulateConnected()

        assertFalse(client.canonicalContinuousIngress.value.hasUnifiedContinuousIngress)

        assertTrue(client.sendDevicePerceptionEmission(canonicalPerceptionPayload()))

        val backbone = client.canonicalContinuousIngress.value
        assertTrue(backbone.hasUnifiedContinuousIngress)
        assertTrue(backbone.hasCanonicalPerceptionInput)
        assertTrue(backbone.supportsCanonicalCognitionBackbone)
    }
}
