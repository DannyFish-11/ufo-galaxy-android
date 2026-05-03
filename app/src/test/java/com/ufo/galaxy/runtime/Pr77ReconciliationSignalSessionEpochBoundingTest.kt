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
import com.ufo.galaxy.protocol.ReconciliationSignalPayload
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-77 — Reconciliation signal session-epoch bounding.
 *
 * This closes the Android-side stale-reconciliation gap by ensuring reconciliation signals carry
 * the durable activation-era session id and the reconnect epoch that V2 can use as an ingestion
 * staleness guard.
 */
class Pr77ReconciliationSignalSessionEpochBoundingTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private class FakePlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(steps = emptyList())
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
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(x = 10, y = 20, confidence = 0.9f)
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
            localPlanner = LocalPlanner(FakePlannerService()),
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

    private fun buildDescriptor() = RuntimeHostDescriptor(
        hostId = "host-pr77",
        deviceId = "device-pr77",
        deviceRole = "phone",
        formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    )

    private fun buildController(): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = InMemoryAppSettings(deviceId = "device-pr77"),
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L,
            hostDescriptor = buildDescriptor()
        )
        return controller to client
    }

    @Test
    fun `ReconciliationSignal exposes durable session wire keys`() {
        assertEquals("durable_session_id", ReconciliationSignal.KEY_DURABLE_SESSION_ID)
        assertEquals("session_continuity_epoch", ReconciliationSignal.KEY_SESSION_CONTINUITY_EPOCH)
    }

    @Test
    fun `factory defaults keep durable session fields absent for compatibility`() {
        val signal = ReconciliationSignal.taskResult("participant", "task")

        assertNull(signal.durableSessionId)
        assertNull(signal.sessionContinuityEpoch)
    }

    @Test
    fun `factory preserves durable session identity and epoch`() {
        val signal = ReconciliationSignal.taskAccepted(
            participantId = "participant",
            taskId = "task",
            durableSessionId = "durable-77",
            sessionContinuityEpoch = 3
        )

        assertEquals("durable-77", signal.durableSessionId)
        assertEquals(3, signal.sessionContinuityEpoch)
    }

    @Test
    fun `payload carries durable session identity and continuity epoch`() {
        val signal = ReconciliationSignal.taskResult(
            participantId = "participant",
            taskId = "task",
            durableSessionId = "durable-77",
            sessionContinuityEpoch = 2
        )
        val payload = ReconciliationSignalPayload(
            signal_id = signal.signalId,
            kind = signal.kind.wireValue,
            participant_id = signal.participantId,
            status = signal.status,
            emitted_at_ms = signal.emittedAtMs,
            reconciliation_epoch = signal.reconciliationEpoch,
            device_id = "device-pr77",
            task_id = signal.taskId,
            durable_session_id = signal.durableSessionId,
            session_continuity_epoch = signal.sessionContinuityEpoch
        )

        assertEquals("durable-77", payload.durable_session_id)
        assertEquals(2, payload.session_continuity_epoch)
    }

    @Test
    fun `RuntimeController reconciliation signals carry epoch zero before reconnect`() = runBlocking {
        val (controller, _) = buildController()
        controller.setActiveForTest()
        val record = controller.durableSessionContinuityRecord.value
        assertNotNull(record)

        val deferred = async {
            withTimeout(500) {
                controller.reconciliationSignals.first { it.kind == ReconciliationSignal.Kind.TASK_ACCEPTED }
            }
        }
        controller.recordDelegatedTaskAccepted("task-before-reconnect")
        val signal = deferred.await()

        assertEquals(record!!.durableSessionId, signal.durableSessionId)
        assertEquals(0, signal.sessionContinuityEpoch)
    }

    @Test
    fun `RuntimeController reconciliation signals carry incremented epoch after reconnect`() = runBlocking {
        val (controller, client) = buildController()
        controller.setActiveForTest()
        val initialRecord = controller.durableSessionContinuityRecord.value
        assertNotNull(initialRecord)

        client.simulateDisconnected()
        client.simulateConnected()
        val reconnectedRecord = controller.durableSessionContinuityRecord.value
        assertNotNull(reconnectedRecord)
        assertEquals(initialRecord!!.durableSessionId, reconnectedRecord!!.durableSessionId)
        assertEquals(1, reconnectedRecord.sessionContinuityEpoch)

        val deferred = async {
            withTimeout(500) {
                controller.reconciliationSignals.first { it.kind == ReconciliationSignal.Kind.TASK_RESULT }
            }
        }
        controller.publishTaskResult("task-after-reconnect")
        val signal = deferred.await()

        assertEquals(reconnectedRecord.durableSessionId, signal.durableSessionId)
        assertEquals(1, signal.sessionContinuityEpoch)
    }
}
