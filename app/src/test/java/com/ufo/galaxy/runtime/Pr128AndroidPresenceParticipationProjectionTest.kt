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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Pr128AndroidPresenceParticipationProjectionTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private class FakePlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep("tap", "tap"))
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
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(x = 100, y = 200, confidence = 0.9f, element_description = "")
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
        val modelsDir = tmpFolder.newFolder("presence-models-${System.nanoTime()}")
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

    private fun buildController(
        settings: InMemoryAppSettings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            accessibilityReady = true,
            overlayReady = true,
            deviceId = "Pixel-128"
        )
    ): RuntimeController {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        return RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L,
            hostDescriptor = descriptor()
        )
    }

    private fun descriptor(
        deviceRole: String = "phone",
        role: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY
    ): RuntimeHostDescriptor = RuntimeHostDescriptor(
        hostId = "host-128",
        deviceId = "Pixel-128",
        deviceRole = deviceRole,
        formationRole = role,
        participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    )

    private fun sessionSnapshot(
        posture: String = SourceRuntimePosture.JOIN_RUNTIME
    ): AttachedRuntimeHostSessionSnapshot = AttachedRuntimeHostSessionSnapshot(
        sessionId = "session-128",
        deviceId = "Pixel-128",
        runtimeSessionId = "runtime-128",
        attachmentState = AttachedRuntimeSession.State.ATTACHED.wireValue,
        isReuseValid = true,
        delegatedExecutionCount = 1,
        invalidationReason = null,
        hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue,
        posture = posture
    )

    private fun buildTruth(
        deviceRole: String = "phone",
        carrierForegroundVisible: Boolean,
        interactionSurfaceReady: Boolean,
        readinessState: ParticipantReadinessState = ParticipantReadinessState.READY
    ): AndroidParticipantRuntimeTruth = AndroidParticipantRuntimeTruth.from(
        descriptor = descriptor(deviceRole = deviceRole),
        sessionSnapshot = sessionSnapshot(),
        healthState = ParticipantHealthState.HEALTHY,
        readinessState = readinessState,
        carrierForegroundVisible = carrierForegroundVisible,
        interactionSurfaceReady = interactionSurfaceReady,
        reconciliationEpoch = 128
    )

    @Test
    fun `android presence participation enters canonical runtime truth structure`() {
        val truth = buildTruth(
            carrierForegroundVisible = true,
            interactionSurfaceReady = true
        )

        val projection = truth.presenceParticipationProjection
        assertEquals(AndroidPresenceProjectionLayer.MANIFEST, projection.layer)
        assertEquals(
            AndroidPresenceParticipationKind.HANDOFF_INTERACTIVE,
            projection.participationKind
        )
        assertEquals(
            AndroidExecutionPresenceRelation.EXECUTION_AND_PRESENCE,
            projection.executionPresenceRelation
        )
        assertEquals(
            AndroidPresenceActionVisibility.HANDOFF_READY,
            projection.actionVisibility
        )

        @Suppress("UNCHECKED_CAST")
        val projectionMap = truth.toMap()[
            AndroidParticipantRuntimeTruth.KEY_PRESENCE_PARTICIPATION_PROJECTION
        ] as Map<String, Any>
        assertEquals(
            AndroidPresenceProjectionLayer.MANIFEST.wireValue,
            projectionMap[AndroidPresenceParticipationProjection.KEY_LAYER]
        )
        assertEquals(
            AndroidPresenceParticipationKind.HANDOFF_INTERACTIVE.wireValue,
            projectionMap[AndroidPresenceParticipationProjection.KEY_PARTICIPATION_KIND]
        )
        assertEquals(
            AndroidPresenceActionVisibility.HANDOFF_READY.wireValue,
            projectionMap[AndroidPresenceParticipationProjection.KEY_ACTION_VISIBILITY]
        )
    }

    @Test
    fun `presence projection output differs between foreground and background participation`() {
        val foregroundTruth = buildTruth(
            carrierForegroundVisible = true,
            interactionSurfaceReady = false
        )
        val backgroundTruth = buildTruth(
            carrierForegroundVisible = false,
            interactionSurfaceReady = false
        )

        assertEquals(
            AndroidPresenceProjectionLayer.MANIFEST,
            foregroundTruth.presenceParticipationProjection.layer
        )
        assertEquals(
            AndroidPresenceParticipationKind.FOREGROUND_ENGAGED,
            foregroundTruth.presenceParticipationProjection.participationKind
        )
        assertEquals(
            AndroidPresenceActionVisibility.FOREGROUND_VISIBLE,
            foregroundTruth.presenceParticipationProjection.actionVisibility
        )

        assertEquals(
            AndroidPresenceProjectionLayer.LIMINAL,
            backgroundTruth.presenceParticipationProjection.layer
        )
        assertEquals(
            AndroidPresenceParticipationKind.BACKGROUND_PARTICIPANT,
            backgroundTruth.presenceParticipationProjection.participationKind
        )
        assertEquals(
            AndroidPresenceActionVisibility.BACKGROUND_DEFERRED,
            backgroundTruth.presenceParticipationProjection.actionVisibility
        )
    }

    @Test
    fun `execution participant and presence participant stay distinct in canonical projection`() {
        val executionAndPresence = buildTruth(
            deviceRole = "phone",
            carrierForegroundVisible = true,
            interactionSurfaceReady = true
        )
        val presenceOnly = buildTruth(
            deviceRole = "hub",
            carrierForegroundVisible = true,
            interactionSurfaceReady = true
        )

        assertEquals(
            AndroidExecutionPresenceRelation.EXECUTION_AND_PRESENCE,
            executionAndPresence.presenceParticipationProjection.executionPresenceRelation
        )
        assertEquals(
            AndroidExecutionPresenceRelation.PRESENCE_ONLY,
            presenceOnly.presenceParticipationProjection.executionPresenceRelation
        )
        assertEquals(
            RuntimeNodeExecutionParticipationState.BLOCKED,
            presenceOnly.presenceParticipationProjection.executionParticipationState
        )
        assertTrue(
            "Presence-only projection proves presence does not collapse into execution metadata",
            presenceOnly.presenceParticipationProjection.isPresenceParticipant
        )
    }

    @Test
    fun `RuntimeController publishes foreground and background presence projection differences`() =
        runBlocking {
            val controller = buildController()
            controller.setActiveForTest()

            controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.FOREGROUND)
            // 测试修复:reconciliationSignals 是 replay=0 热流,默认 async 要等父协程
            // 挂起才开始收集,生产方同步 tryEmit 的信号在订阅前就已丢失;
            // 用 UNDISPATCHED 确保先订阅(挂起在 first)再触发生产方。
            val foregroundDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(500) {
                    controller.reconciliationSignals.first {
                        it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
                    }
                }
            }
            controller.publishRuntimeTruthSnapshot(
                healthState = ParticipantHealthState.HEALTHY,
                readinessState = ParticipantReadinessState.READY
            )
            val foregroundSignal = foregroundDeferred.await()

            controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.BACKGROUND)
            // 测试修复:同上,先订阅后发布。
            val backgroundDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(500) {
                    controller.reconciliationSignals.first {
                        it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
                    }
                }
            }
            controller.publishRuntimeTruthSnapshot(
                healthState = ParticipantHealthState.HEALTHY,
                readinessState = ParticipantReadinessState.READY
            )
            val backgroundSignal = backgroundDeferred.await()

            @Suppress("UNCHECKED_CAST")
            val foregroundProjection = foregroundSignal.payload[
                AndroidParticipantRuntimeTruth.KEY_PRESENCE_PARTICIPATION_PROJECTION
            ] as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val backgroundProjection = backgroundSignal.payload[
                AndroidParticipantRuntimeTruth.KEY_PRESENCE_PARTICIPATION_PROJECTION
            ] as Map<String, Any>

            assertEquals(
                AndroidPresenceProjectionLayer.MANIFEST.wireValue,
                foregroundProjection[AndroidPresenceParticipationProjection.KEY_LAYER]
            )
            assertEquals(
                AndroidPresenceActionVisibility.HANDOFF_READY.wireValue,
                foregroundProjection[AndroidPresenceParticipationProjection.KEY_ACTION_VISIBILITY]
            )
            assertEquals(
                AndroidPresenceProjectionLayer.LIMINAL.wireValue,
                backgroundProjection[AndroidPresenceParticipationProjection.KEY_LAYER]
            )
            assertEquals(
                AndroidPresenceActionVisibility.BACKGROUND_DEFERRED.wireValue,
                backgroundProjection[AndroidPresenceParticipationProjection.KEY_ACTION_VISIBILITY]
            )
        }
}
