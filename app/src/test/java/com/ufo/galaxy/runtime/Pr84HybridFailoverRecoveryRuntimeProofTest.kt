package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.AutonomousExecutionPipeline
import com.ufo.galaxy.agent.DelegatedRuntimeUnit
import com.ufo.galaxy.agent.DelegatedTakeoverExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.GoalExecutionPipeline
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
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.shared.protocol.MsgType
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        ) = LocalGroundingService.GroundingResult(x = 540, y = 1170, confidence = 0.9f, element_description = "")
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
                policy_routing_outcome = PolicyRoutingContext.RoutingOutcome.RESUMED.wireValue,
                // posture gate(PR #533 契约):缺省 null 按 control_only 拦截(STATUS_DISABLED),
                // 显式声明 join_runtime 才允许本机恢复执行。
                source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertTrue(resumedResult.status == EdgeExecutor.STATUS_SUCCESS)
        assertTrue(resumedResult.is_continuation == true)

        val envelope = AipMessage(
            type = MsgType.GOAL_EXECUTION_RESULT,
            payload = gson.toJsonTree(resumedResult),
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

        // 契约更新(两代设计取舍,PR-117/126 隔离门为准):goal_execution_result 属
        // REPLAY_EPOCH_REQUIRED_TYPES(权威敏感),断线期间排队的旧纪元结果在
        // canonical 重连后【不得】逐字重放进新纪元——canonical ownership/epoch
        // 预过滤会阻断(shouldForward=false),Hybrid 连续性注册表亦明确
        // "Android MUST NOT re-transmit a prior result after reconnect"。
        // 结果收敛的当前正解:重连后由新纪元的全新上行送达(V2 依据 PR-62 重连
        // 空闲真相快照自行对账/重派)。
        val verbatimReplayed = recordingSocket.textMessages
            .map { JsonParser.parseString(it).asJsonObject }
            .firstOrNull { it.get("type")?.asString == MsgType.GOAL_EXECUTION_RESULT.value }
        assertNull(
            "Pre-reconnect queued goal_execution_result must NOT be replayed verbatim " +
                "into the new epoch (replay isolation gates, PR-117/126)",
            verbatimReplayed
        )

        // Fresh post-reconnect uplink in the new epoch is how the final result converges.
        assertTrue(
            "Fresh goal_execution_result uplink must succeed after canonical reconnect",
            wsClient.sendJson(gson.toJson(envelope))
        )
        val freshResultEnvelope = recordingSocket.textMessages
            .map { JsonParser.parseString(it).asJsonObject }
            .firstOrNull { it.get("type")?.asString == MsgType.GOAL_EXECUTION_RESULT.value }
        assertNotNull("Post-reconnect fresh goal_execution_result must reach V2", freshResultEnvelope)

        val replayPayload = freshResultEnvelope!!
            .getAsJsonObject("payload")
        assertEquals("hybrid-task-1", replayPayload.get("task_id").asString)
        assertEquals("cont-hybrid-1", replayPayload.get("continuity_token").asString)
        assertTrue(replayPayload.get("is_continuation").asBoolean)
        assertEquals("disconnect_during_execution", replayPayload.get("interruption_reason").asString)
        assertEquals("3", replayPayload.getAsJsonObject("recovery_context").get("resume_step").asString)
    }

    @Test
    fun `delegated takeover signal queued offline is isolation-blocked on reconnect while fresh transfer completes`() {
        val gson = Gson()
        val (controller, wsClient) = buildController()
        val recordingSocket = RecordingWebSocket()

        controller.setActiveForTest()

        val delegatedUnit = DelegatedRuntimeUnit(
            unitId = "takeover-unit-1",
            taskId = "takeover-task-1",
            traceId = "trace-takeover-proof",
            goal = "resume ownership transfer",
            attachedSessionId = "session-takeover-1"
        )
        val activationRecord = DelegatedActivationRecord.create(
            unit = delegatedUnit,
            activatedAtMs = 1_000L
        )

        val emittedSignals = mutableListOf<DelegatedExecutionSignal>()
        val executor = DelegatedTakeoverExecutor(
            pipeline = GoalExecutionPipeline { payload ->
                GoalResultPayload(
                    task_id = payload.task_id,
                    correlation_id = payload.task_id,
                    status = EdgeExecutor.STATUS_SUCCESS,
                    continuity_token = "cont-takeover-1",
                    recovery_context = mapOf(
                        "resume_step" to "4",
                        "origin" to "delegated_takeover_reconnect"
                    ),
                    is_resumable = true,
                    interruption_reason = "disconnect_during_takeover"
                )
            },
            signalSink = DelegatedExecutionSignalSink { signal -> emittedSignals += signal }
        )
        val outcome = executor.execute(
            unit = delegatedUnit,
            initialRecord = activationRecord,
            nowMs = 10_000L
        ) as DelegatedTakeoverExecutor.ExecutionOutcome.Completed
        val terminalSignal = emittedSignals.last()
        val expectedLifecycleSignalCount = 3 // ACK + PROGRESS + RESULT
        assertEquals(expectedLifecycleSignalCount, emittedSignals.size)
        assertTrue(terminalSignal.isResult)

        // Simulate transport interruption after takeover execution; terminal signal must queue.
        wsClient.simulateDisconnected()
        assertEquals(ReconnectRecoveryState.RECOVERING, controller.reconnectRecoveryState.value)

        val delegatedSignalEnvelope = AipMessage(
            type = MsgType.DELEGATED_EXECUTION_SIGNAL,
            payload = gson.toJsonTree(terminalSignal.toOutboundPayload(deviceId = "test-device")),
            correlation_id = delegatedUnit.taskId,
            device_id = "test-device",
            trace_id = delegatedUnit.traceId,
            idempotency_key = terminalSignal.signalId
        )
        val sentWhileDisconnected = wsClient.sendJson(gson.toJson(delegatedSignalEnvelope))
        assertFalse(sentWhileDisconnected)
        assertEquals(1, wsClient.offlineQueue.size)

        // 契约更新(两代设计取舍,PR-117/126 隔离门为准):delegated_execution_signal
        // 属 REPLAY_EPOCH_REQUIRED_TYPES,断线期间排队的旧纪元信号在 canonical 重连后
        // 【不得】逐字重放进新纪元;队列照常排水(阻断/丢弃),V2 依据 PR-62 重连
        // 空闲真相快照对该任务对账/重派。新纪元的全新上行不受影响(下方验证)。
        wsClient.installWebSocketForTest(recordingSocket)
        wsClient.simulateCanonicalReconnectOpenForTest()
        assertEquals(ReconnectRecoveryState.RECOVERED, controller.reconnectRecoveryState.value)
        assertEquals(0, wsClient.offlineQueue.size)

        val replayedSignalEnvelope = recordingSocket.textMessages
            .map { JsonParser.parseString(it).asJsonObject }
            .firstOrNull { it.get("type")?.asString == MsgType.DELEGATED_EXECUTION_SIGNAL.value }
        assertNull(
            "Pre-reconnect queued delegated_execution_signal must NOT be replayed verbatim " +
                "into the new epoch (replay isolation gates, PR-117/126)",
            replayedSignalEnvelope
        )

        val resumedEnvelope = AipMessage(
            type = MsgType.GOAL_EXECUTION_RESULT,
            payload = gson.toJsonTree(
                outcome.goalResult.copy(
                    task_id = delegatedUnit.taskId,
                    correlation_id = delegatedUnit.taskId
                )
            ),
            correlation_id = delegatedUnit.taskId,
            device_id = "test-device",
            trace_id = delegatedUnit.traceId
        )
        assertTrue(wsClient.sendJson(gson.toJson(resumedEnvelope)))

        val resumedResultEnvelope = recordingSocket.textMessages
            .map { JsonParser.parseString(it).asJsonObject }
            .lastOrNull { envelope ->
                envelope.get("type")?.asString == MsgType.GOAL_EXECUTION_RESULT.value &&
                    envelope.getAsJsonObject("payload").get("task_id").asString == delegatedUnit.taskId
            }
        assertNotNull("Resumed takeover transfer result must converge on canonical goal_execution_result uplink", resumedResultEnvelope)

        val resumedPayload = resumedResultEnvelope!!.getAsJsonObject("payload")
        assertTrue(resumedPayload.get("is_resumable").asBoolean)
        assertEquals("4", resumedPayload.getAsJsonObject("recovery_context").get("resume_step").asString)
    }
}
