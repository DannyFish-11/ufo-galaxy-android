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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Pr88InflightContinuityRecoveryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private class FakePlannerService : LocalPlannerService {
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
        hostId = "host-88",
        deviceId = "Pixel-88",
        deviceRole = "phone",
        formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    )

    private fun buildController(
        settings: InMemoryAppSettings = InMemoryAppSettings(),
        descriptor: RuntimeHostDescriptor? = buildDescriptor()
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L,
            hostDescriptor = descriptor
        )
        return controller to client
    }

    @Test
    fun `process recreation with persisted inflight task requires reconciliation`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true, deviceId = "Pixel-88")
        val (controller, _) = buildController(settings)
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-process-recreated")

        assertTrue(settings.inflightContinuityRecoveryArtifact.isNotBlank())

        val (recreatedController, _) = buildController(settings)
        recreatedController.onAppLifecycleTransition(AndroidAppLifecycleTransition.PROCESS_RECREATED)

        val recovery = recreatedController.inflightContinuityRecovery.value
        assertEquals(InflightContinuityDisposition.REQUIRES_RECONCILIATION, recovery.disposition)
        assertEquals("task-process-recreated", recovery.taskId)
        assertNull(recreatedController.activeTaskId)
        assertFalse(recreatedController.isRemoteExecutionActive.value)
        assertEquals("", settings.inflightContinuityRecoveryArtifact)
        val allocationSnapshot = AndroidTaskAllocationTruthSnapshot
            .fromJson(settings.taskAllocationTruthArtifact)
        val interruptedRecord = allocationSnapshot?.recentTaskAllocations
            ?.firstOrNull { it.taskId == "task-process-recreated" }
        assertNotNull(interruptedRecord)
        assertEquals(TaskAllocationPhase.CLOSED, interruptedRecord?.participantLocalPhase)
        assertEquals(TaskAllocationClosureClass.INTERRUPTED, interruptedRecord?.closureClass)
        assertEquals(false, interruptedRecord?.inFlightOwnership)
    }

    @Test
    fun `service restore with live active task reports recovered inflight`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true, deviceId = "Pixel-88")
        val (controller, _) = buildController(settings)
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-service-restart")

        controller.connectIfEnabled()

        val recovery = controller.inflightContinuityRecovery.value
        assertEquals(InflightContinuityDisposition.RECOVERED_INFLIGHT, recovery.disposition)
        assertEquals("task-service-restart", recovery.taskId)
        assertEquals("task-service-restart", controller.activeTaskId)
        assertTrue(controller.isRemoteExecutionActive.value)
        assertTrue(settings.inflightContinuityRecoveryArtifact.isNotBlank())
    }

    @Test
    fun `runtime stop with active task marks lost inflight`() {
        val settings = InMemoryAppSettings(deviceId = "Pixel-88")
        val (controller, _) = buildController(settings)
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-runtime-stop")

        controller.stop()

        val recovery = controller.inflightContinuityRecovery.value
        assertEquals(InflightContinuityDisposition.LOST_INFLIGHT, recovery.disposition)
        assertEquals("task-runtime-stop", recovery.taskId)
        assertNull(controller.activeTaskId)
        assertFalse(controller.isRemoteExecutionActive.value)
        assertEquals("", settings.inflightContinuityRecoveryArtifact)
        val allocationSnapshot = AndroidTaskAllocationTruthSnapshot
            .fromJson(settings.taskAllocationTruthArtifact)
        val interruptedRecord = allocationSnapshot?.recentTaskAllocations
            ?.firstOrNull { it.taskId == "task-runtime-stop" }
        assertNotNull(interruptedRecord)
        assertEquals(TaskAllocationPhase.CLOSED, interruptedRecord?.participantLocalPhase)
        assertEquals(TaskAllocationClosureClass.INTERRUPTED, interruptedRecord?.closureClass)
        assertEquals(false, interruptedRecord?.inFlightOwnership)
    }

    @Test
    fun `runtime truth snapshot carries inflight continuity classification`() = runBlocking {
        val settings = InMemoryAppSettings(deviceId = "Pixel-88")
        val (controller, _) = buildController(settings)
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-runtime-truth")
        controller.stop()

        val deferred = async {
            withTimeout(500) {
                controller.reconciliationSignals.first {
                    it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
                }
            }
        }

        controller.publishRuntimeTruthSnapshot()

        val signal = deferred.await()
        val truth = signal.runtimeTruth ?: error("runtimeTruth must be present")
        assertEquals("lost-inflight", truth.inflightContinuityState)
        assertEquals("task-runtime-truth", truth.inflightContinuityTaskId)
        assertEquals("runtime_stop", truth.inflightContinuitySource)
    }
}
