package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.AutonomousExecutionPipeline
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.LocalCollaborationAgent
import com.ufo.galaxy.agent.LocalGoalExecutor
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
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.MsgType
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Pr84HybridFailoverRecoveryRuntimeProofTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private class TrivialPlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(steps = listOf(LocalPlannerService.PlanStep("tap", "button")))
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

    private class RecordingWebSocket : WebSocket {
        val textMessages = mutableListOf<String>()
        override fun request(): Request = Request.Builder().url("ws://localhost:9999").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean {
            textMessages.add(text)
            return true
        }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
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
            crossDeviceEnabled = true,
            offlineQueue = OfflineTaskQueue(prefs = null)
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = InMemoryAppSettings(crossDeviceEnabled = true),
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L
        )
        return controller to client
    }

    private fun buildPipeline(): AutonomousExecutionPipeline {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            goalExecutionEnabled = true,
            parallelExecutionEnabled = true,
            modelReady = true,
            accessibilityReady = true,
            overlayReady = true
        )
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshotProvider(),
            plannerService = TrivialPlannerService(),
            groundingService = FakeGrounder(),
            accessibilityExecutor = FakeAccessibilityExecutor()
        )
        val goalExec = LocalGoalExecutor(edge, "test-device")
        return AutonomousExecutionPipeline(
            settings = settings,
            goalExecutor = goalExec,
            collaborationAgent = LocalCollaborationAgent(goalExec),
            deviceId = "test-device",
            deviceRole = "phone"
        )
    }

    @Test
    fun `disconnect reconnect restore and resumed execution replay converges as final result`() {
        val gson = Gson()
        val (controller, wsClient) = buildController()
        val pipeline = buildPipeline()
        val recordingSocket = RecordingWebSocket()

        controller.setActiveForTest()
        val durableBefore = wsClient.getDurableSessionId()
        val epochBefore = wsClient.getSessionContinuityEpoch()
        assertNotNull(durableBefore)
        assertEquals(0, epochBefore)

        // Android resumes execution locally using the canonical pipeline while transport is down.
        val resumedResult = pipeline.handleGoalExecution(
            GoalExecutionPayload(
                task_id = "hybrid-task-1",
                goal = "resume interrupted flow",
                continuity_token = "cont-hybrid-1",
                recovery_context = mapOf(
                    "resume_step" to "3",
                    "origin" to "disconnect_reconnect_replay"
                ),
                is_resumable = true,
                interruption_reason = "disconnect_during_execution",
                policy_routing_outcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue
            )
        )
        assertTrue(resumedResult.status == EdgeExecutor.STATUS_SUCCESS)
        assertTrue(resumedResult.is_continuation == true)

        val envelope = AipMessage(
            type = MsgType.GOAL_EXECUTION_RESULT,
            payload = resumedResult,
            correlation_id = resumedResult.correlation_id,
            device_id = "test-device",
            trace_id = "trace-hybrid-proof"
        )

        wsClient.simulateDisconnected()
        assertEquals(ReconnectRecoveryState.RECOVERING, controller.reconnectRecoveryState.value)

        val sentImmediately = wsClient.sendJson(gson.toJson(envelope))
        assertFalse("Precondition: result must queue while disconnected", sentImmediately)
        assertEquals(1, wsClient.offlineQueue.size)

        wsClient.installWebSocketForTest(recordingSocket)
        wsClient.simulateCanonicalReconnectOpenForTest()
        assertEquals(ReconnectRecoveryState.RECOVERED, controller.reconnectRecoveryState.value)
        assertEquals("Durable continuity identity must survive reconnect", durableBefore, wsClient.getDurableSessionId())
        assertEquals(
            "Reconnect must increment continuity epoch exactly once",
            (epochBefore ?: 0) + 1,
            wsClient.getSessionContinuityEpoch()
        )
        assertEquals("Replay queue must drain on canonical reconnect-open path", 0, wsClient.offlineQueue.size)

        val replayedResultEnvelope = recordingSocket.textMessages
            .map { JsonParser.parseString(it).asJsonObject }
            .firstOrNull { it.get("type")?.asString == MsgType.GOAL_EXECUTION_RESULT.value }
        assertNotNull("Reconnected transport must deliver final converged goal_execution_result to V2", replayedResultEnvelope)

        val replayPayload = replayedResultEnvelope!!
            .getAsJsonObject("payload")
        assertEquals("hybrid-task-1", replayPayload.get("task_id").asString)
        assertEquals("cont-hybrid-1", replayPayload.get("continuity_token").asString)
        assertTrue(replayPayload.get("is_continuation").asBoolean)
        assertEquals("disconnect_during_execution", replayPayload.get("interruption_reason").asString)
        assertEquals("3", replayPayload.getAsJsonObject("recovery_context").get("resume_step").asString)
    }
}
