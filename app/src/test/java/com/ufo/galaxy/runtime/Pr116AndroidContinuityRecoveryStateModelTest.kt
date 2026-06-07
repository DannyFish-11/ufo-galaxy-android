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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-116 — Android Continuity Recovery State Model tests.
 *
 * Validates that:
 * 1. Process rebuild without live active task produces [InflightContinuityDisposition.LOST_INFLIGHT].
 * 2. Process rebuild with live active task produces [InflightContinuityDisposition.RECOVERED_INFLIGHT].
 * 3. [AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION] is written
 *    into [ReconciliationSignal] payload so V2 can consume it without reading nested truth fields.
 * 4. An artifact from a stale durable session is classified as
 *    [InflightContinuityDisposition.STALE_RECOVERY_ARTIFACT] and MUST NOT masquerade as current
 *    continuity evidence in the new session.
 * 5. [AndroidContinuityRecoveryStateModel.derive] correctly maps ReconnectRecoveryState and
 *    InflightContinuityDisposition to unified RecoveryPhase values.
 * 6. [ReconciliationSignal.runtimeTruthSnapshot] carries recovery state payload keys.
 */
class Pr116AndroidContinuityRecoveryStateModelTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ─── fakes ────────────────────────────────────────────────────────────────

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
            LocalGroundingService.GroundingResult(x = 540, y = 1170, confidence = 0.9f)
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
        val modelsDir = tmpFolder.newFolder("models-${System.nanoTime()}")
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
        settings: InMemoryAppSettings = InMemoryAppSettings(),
        descriptor: RuntimeHostDescriptor? = RuntimeHostDescriptor(
            hostId = "host-116", deviceId = "Pixel-116", deviceRole = "phone",
            formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
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
            hostDescriptor = descriptor
        )
    }

    // ─── 1. Recovery state derive() contract ─────────────────────────────────

    @Test
    fun `derive returns RECOVERING when reconnect state is RECOVERING`() {
        val phase = AndroidContinuityRecoveryStateModel.derive(
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERING,
            inflightDisposition = InflightContinuityDisposition.REQUIRES_RECONCILIATION
        )
        assertEquals(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING,
            phase
        )
    }

    @Test
    fun `derive returns RECOVERY_FAILED when reconnect state is FAILED`() {
        val phase = AndroidContinuityRecoveryStateModel.derive(
            reconnectRecoveryState = ReconnectRecoveryState.FAILED,
            inflightDisposition = InflightContinuityDisposition.LOST_INFLIGHT
        )
        assertEquals(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERY_FAILED,
            phase
        )
    }

    @Test
    fun `derive returns STALE_RECOVERY_ARTIFACT for stale disposition`() {
        val phase = AndroidContinuityRecoveryStateModel.derive(
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            inflightDisposition = InflightContinuityDisposition.STALE_RECOVERY_ARTIFACT
        )
        assertEquals(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.STALE_RECOVERY_ARTIFACT,
            phase
        )
    }

    @Test
    fun `derive returns LOST_INFLIGHT for lost inflight disposition when not RECOVERING`() {
        val phase = AndroidContinuityRecoveryStateModel.derive(
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            inflightDisposition = InflightContinuityDisposition.LOST_INFLIGHT
        )
        assertEquals(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.LOST_INFLIGHT,
            phase
        )
    }

    @Test
    fun `derive returns REQUIRES_RECONCILIATION for reconciliation disposition`() {
        val phase = AndroidContinuityRecoveryStateModel.derive(
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            inflightDisposition = InflightContinuityDisposition.REQUIRES_RECONCILIATION
        )
        assertEquals(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION,
            phase
        )
    }

    @Test
    fun `derive returns RECOVERED_INFLIGHT for recovered inflight disposition`() {
        val phase = AndroidContinuityRecoveryStateModel.derive(
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERED,
            inflightDisposition = InflightContinuityDisposition.RECOVERED_INFLIGHT
        )
        assertEquals(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERED_INFLIGHT,
            phase
        )
    }

    @Test
    fun `derive returns RESUMED_CLEANLY when no recovery event and state is IDLE`() {
        val phase = AndroidContinuityRecoveryStateModel.derive(
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            inflightDisposition = InflightContinuityDisposition.RESUMED_CLEANLY
        )
        assertEquals(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RESUMED_CLEANLY,
            phase
        )
    }

    // ─── 2. Process rebuild → lost-inflight (no live task) ───────────────────

    @Test
    fun `process rebuild without live active task produces lost-inflight`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = false,  // crossDeviceEnabled=false → LOST_INFLIGHT path
            deviceId = "Pixel-116"
        )
        val firstController = buildController(settings)
        firstController.setActiveForTest()
        firstController.recordDelegatedTaskAccepted("task-lost-116")

        // Confirm artifact was persisted
        assertTrue(
            "Artifact should be persisted after task accepted",
            settings.inflightContinuityRecoveryArtifact.isNotBlank()
        )

        // Simulate process rebuild: new controller, same settings, no live task
        val rebuiltController = buildController(settings)
        rebuiltController.onAppLifecycleTransition(AndroidAppLifecycleTransition.PROCESS_RECREATED)

        val recovery = rebuiltController.inflightContinuityRecovery.value
        assertEquals(
            "Process rebuild without live task must produce LOST_INFLIGHT",
            InflightContinuityDisposition.LOST_INFLIGHT,
            recovery.disposition
        )
        assertEquals("task-lost-116", recovery.taskId)
        assertNull("Active task must be null after LOST_INFLIGHT", rebuiltController.activeTaskId)
        // Unified recovery phase must reflect lost-inflight
        val unifiedPhase = AndroidContinuityRecoveryStateModel.derive(
            reconnectRecoveryState = rebuiltController.reconnectRecoveryState.value,
            inflightDisposition = recovery.disposition
        )
        assertEquals(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.LOST_INFLIGHT,
            unifiedPhase
        )
    }

    // ─── 3. Process rebuild → recovered-inflight (live task present) ─────────

    @Test
    fun `recoverable inflight task is marked recovered-inflight when task is still live`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            deviceId = "Pixel-116"
        )
        val controller = buildController(settings)
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-recovered-116")

        // Trigger connectIfEnabled, which calls classifyPersistedInflightContinuity with a live task
        controller.connectIfEnabled()

        val recovery = controller.inflightContinuityRecovery.value
        assertEquals(
            "Live in-flight task during reconnect must be marked RECOVERED_INFLIGHT",
            InflightContinuityDisposition.RECOVERED_INFLIGHT,
            recovery.disposition
        )
        assertEquals("task-recovered-116", recovery.taskId)
        assertEquals("task-recovered-116", controller.activeTaskId)

        val unifiedPhase = AndroidContinuityRecoveryStateModel.derive(
            reconnectRecoveryState = controller.reconnectRecoveryState.value,
            inflightDisposition = recovery.disposition
        )
        assertEquals(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERED_INFLIGHT,
            unifiedPhase
        )
    }

    // ─── 4. REQUIRES_RECONCILIATION written into reconciliation signal payload ─

    @Test
    fun `recovery state is written into RUNTIME_TRUTH_SNAPSHOT payload`() = runBlocking {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = false,
            deviceId = "Pixel-116"
        )
        val controller = buildController(settings)
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-reconcile-116")
        controller.stop()  // → LOST_INFLIGHT (crossDevice disabled)

        val deferred = async {
            withTimeout(500) {
                controller.reconciliationSignals.first {
                    it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
                }
            }
        }

        controller.publishRuntimeTruthSnapshot()

        val signal = deferred.await()
        // The payload must carry the recovery state key (INV-REC-04)
        val recoveryStateInPayload =
            signal.payload[ReconciliationSignal.KEY_CONTINUITY_RECOVERY_STATE]
        assertNotNull(
            "RUNTIME_TRUTH_SNAPSHOT payload MUST carry continuity_recovery_state",
            recoveryStateInPayload
        )
        assertEquals(
            "Stopped controller with active task must report lost-inflight in payload",
            InflightContinuityDisposition.LOST_INFLIGHT.wireValue,
            recoveryStateInPayload
        )
        val schemaVersionInPayload =
            signal.payload[ReconciliationSignal.KEY_CONTINUITY_RECOVERY_SCHEMA_VERSION]
        assertNotNull(
            "RUNTIME_TRUTH_SNAPSHOT payload MUST carry continuity_recovery_schema_version",
            schemaVersionInPayload
        )
        assertEquals(
            AndroidContinuityRecoveryStateModel.SCHEMA_VERSION,
            schemaVersionInPayload
        )
    }

    @Test
    fun `requires-reconciliation is written into RUNTIME_TRUTH_SNAPSHOT payload after process rebuild with crossDevice enabled`() = runBlocking {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            deviceId = "Pixel-116"
        )
        // First controller — record a task to persist the inflight recovery artifact
        val firstController = buildController(settings)
        firstController.setActiveForTest()
        firstController.recordDelegatedTaskAccepted("task-reconcile-cross-116")

        // New controller simulates process rebuild with crossDevice still enabled
        val controller = buildController(settings)
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.PROCESS_RECREATED)
        // After PROCESS_RECREATED with a persisted artifact and crossDevice enabled,
        // the inflight disposition is REQUIRES_RECONCILIATION

        val deferred = async {
            withTimeout(500) {
                controller.reconciliationSignals.first {
                    it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
                }
            }
        }

        controller.publishRuntimeTruthSnapshot()

        val signal = deferred.await()
        // requires-reconciliation must be written into the uplink payload (INV-REC-04 + INV-REC-05)
        val recoveryStateInPayload =
            signal.payload[ReconciliationSignal.KEY_CONTINUITY_RECOVERY_STATE]
        assertEquals(
            "RUNTIME_TRUTH_SNAPSHOT payload must carry requires-reconciliation after process " +
                "rebuild with crossDevice enabled and unrecovered artifact",
            InflightContinuityDisposition.REQUIRES_RECONCILIATION.wireValue,
            recoveryStateInPayload
        )
        // Source must also be present so V2 observability can attribute the observation
        val recoverySourceInPayload =
            signal.payload[ReconciliationSignal.KEY_CONTINUITY_RECOVERY_SOURCE]
        assertNotNull(
            "RUNTIME_TRUTH_SNAPSHOT payload MUST carry continuity_recovery_source",
            recoverySourceInPayload
        )
    }

    // ─── 5. requires-reconciliation on process rebuild with crossDevice enabled ─

    @Test
    fun `process rebuild with crossDevice enabled produces requires-reconciliation`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            deviceId = "Pixel-116"
        )
        val firstController = buildController(settings)
        firstController.setActiveForTest()
        firstController.recordDelegatedTaskAccepted("task-recon-116")
        assertTrue(settings.inflightContinuityRecoveryArtifact.isNotBlank())

        // New controller (process rebuild), no live task
        val rebuiltController = buildController(settings)
        rebuiltController.onAppLifecycleTransition(AndroidAppLifecycleTransition.PROCESS_RECREATED)

        val recovery = rebuiltController.inflightContinuityRecovery.value
        assertEquals(
            "Process rebuild with crossDevice enabled and no live task must be REQUIRES_RECONCILIATION",
            InflightContinuityDisposition.REQUIRES_RECONCILIATION,
            recovery.disposition
        )
        assertNull(rebuiltController.activeTaskId)

        val unifiedPhase = AndroidContinuityRecoveryStateModel.derive(
            reconnectRecoveryState = rebuiltController.reconnectRecoveryState.value,
            inflightDisposition = recovery.disposition
        )
        assertEquals(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION,
            unifiedPhase
        )
    }

    // ─── 6. Stale artifact from old session MUST NOT masquerade as current continuity ─

    @Test
    fun `stale recovery artifact from different durable session is rejected as STALE_RECOVERY_ARTIFACT`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            deviceId = "Pixel-116"
        )
        // Manually write an artifact that claims to belong to an old durable session
        val oldSessionArtifact = InflightContinuityRecoveryArtifact(
            taskId = "task-old-session-116",
            activeTaskStatus = ActiveTaskStatus.RUNNING.wireValue,
            durableSessionId = "old-session-era-aaa",
            sessionContinuityEpoch = 1,
            runtimeAttachmentSessionId = null,
            attachedSessionId = null
        )
        settings.inflightContinuityRecoveryArtifact = oldSessionArtifact.toJson()

        // The new controller has a different durable session ID set via InMemoryAppSettings.
        // lastDurableSessionId represents the most-recently committed session era in settings,
        // which the controller checks in classifyPersistedInflightContinuityIfNeeded when
        // _durableSessionContinuityRecord is not yet established after process recreate.
        settings.lastDurableSessionId = "new-session-era-bbb"

        val controller = buildController(settings)
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.PROCESS_RECREATED)

        val recovery = controller.inflightContinuityRecovery.value
        assertEquals(
            "Artifact from a different durable session MUST be classified as STALE_RECOVERY_ARTIFACT",
            InflightContinuityDisposition.STALE_RECOVERY_ARTIFACT,
            recovery.disposition
        )
        assertNull(
            "Active task MUST NOT be set from a stale artifact",
            controller.activeTaskId
        )
        val unifiedPhase = AndroidContinuityRecoveryStateModel.derive(
            reconnectRecoveryState = controller.reconnectRecoveryState.value,
            inflightDisposition = recovery.disposition
        )
        assertEquals(
            "Unified phase for stale artifact must be STALE_RECOVERY_ARTIFACT",
            AndroidContinuityRecoveryStateModel.RecoveryPhase.STALE_RECOVERY_ARTIFACT,
            unifiedPhase
        )
        // V2_CONSUMPTION_PATH_MAP must declare a stale artifact rejection path
        val consumptionPath = AndroidContinuityRecoveryStateModel.V2_CONSUMPTION_PATH_MAP[
            AndroidContinuityRecoveryStateModel.RecoveryPhase.STALE_RECOVERY_ARTIFACT.wireValue
        ]
        assertNotNull("V2 consumption path must be declared for STALE_RECOVERY_ARTIFACT", consumptionPath)
        assertTrue(
            "V2 consumption path for stale artifact must reference rejection",
            consumptionPath!!.contains("rejection", ignoreCase = true)
        )
    }

    // ─── 7. Wire map correctness ──────────────────────────────────────────────

    @Test
    fun `toWireMap returns all three required keys with correct values`() {
        val phase = AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION
        val wireMap = AndroidContinuityRecoveryStateModel.toWireMap(phase, "process_recreated")

        assertEquals(
            phase.wireValue,
            wireMap[AndroidContinuityRecoveryStateModel.KEY_CONTINUITY_RECOVERY_STATE]
        )
        assertEquals(
            "process_recreated",
            wireMap[AndroidContinuityRecoveryStateModel.KEY_CONTINUITY_RECOVERY_SOURCE]
        )
        assertEquals(
            AndroidContinuityRecoveryStateModel.SCHEMA_VERSION,
            wireMap[AndroidContinuityRecoveryStateModel.KEY_CONTINUITY_RECOVERY_SCHEMA_VERSION]
        )
    }

    // ─── 8. Invariants are non-empty and cover key constraints ───────────────

    @Test
    fun `RECOVERY_STATE_INVARIANTS is non-empty and covers uplink wire key requirement`() {
        val invariants = AndroidContinuityRecoveryStateModel.RECOVERY_STATE_INVARIANTS
        assertTrue("RECOVERY_STATE_INVARIANTS must be non-empty", invariants.isNotEmpty())
        val hasUplinkInvariant = invariants.any { inv ->
            inv.contains("INV-REC-04") && inv.contains("uplink metadata")
        }
        assertTrue(
            "Must have invariant INV-REC-04 requiring uplink metadata population",
            hasUplinkInvariant
        )
    }

    @Test
    fun `RECOVERY_STATE_INVARIANTS contains stale artifact boundary`() {
        val hasStaleInvariant = AndroidContinuityRecoveryStateModel.RECOVERY_STATE_INVARIANTS
            .any { inv -> inv.contains("INV-REC-02") && inv.contains("STALE_RECOVERY_ARTIFACT") }
        assertTrue(
            "Must have invariant INV-REC-02 declaring STALE_RECOVERY_ARTIFACT boundaries",
            hasStaleInvariant
        )
    }

    // ─── 9. StabilizationBaseline registration ───────────────────────────────

    @Test
    fun `android-continuity-recovery-state-model is registered in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("android-continuity-recovery-state-model")
        assertNotNull("android-continuity-recovery-state-model must be registered", entry)
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
        assertEquals(116, entry.introducedPr)
    }

    // ─── 10. unifiedRecoveryPhase StateFlow on RuntimeController ─────────────

    @Test
    fun `unifiedRecoveryPhase StateFlow starts as RESUMED_CLEANLY`() {
        val controller = buildController()
        assertEquals(
            "Initial unifiedRecoveryPhase MUST be RESUMED_CLEANLY before any lifecycle event",
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RESUMED_CLEANLY,
            controller.unifiedRecoveryPhase.value
        )
    }

    @Test
    fun `unifiedRecoveryPhase updates to LOST_INFLIGHT when stop is called with active task`() {
        val settings = InMemoryAppSettings(deviceId = "Pixel-116")
        val controller = buildController(settings)
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-unified-116")

        controller.stop()

        assertEquals(
            "unifiedRecoveryPhase MUST be LOST_INFLIGHT after stop() with an active task",
            AndroidContinuityRecoveryStateModel.RecoveryPhase.LOST_INFLIGHT,
            controller.unifiedRecoveryPhase.value
        )
    }

    @Test
    fun `unifiedRecoveryPhase is RECOVERING when reconnect recovery state is RECOVERING`() {
        val controller = buildController()
        controller.setActiveForTest()

        controller.setReconnectRecoveryStateForTest(ReconnectRecoveryState.RECOVERING)

        assertEquals(
            "unifiedRecoveryPhase MUST be RECOVERING when reconnect recovery state is RECOVERING",
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING,
            controller.unifiedRecoveryPhase.value
        )
    }

    @Test
    fun `unifiedRecoveryPhase is RECOVERY_FAILED when reconnect recovery state is FAILED`() {
        val controller = buildController()
        controller.setActiveForTest()

        controller.setReconnectRecoveryStateForTest(ReconnectRecoveryState.FAILED)

        assertEquals(
            "unifiedRecoveryPhase MUST be RECOVERY_FAILED when reconnect recovery state is FAILED",
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERY_FAILED,
            controller.unifiedRecoveryPhase.value
        )
    }

    // ─── 11. publishRuntimeTruthSnapshot carries unified phase, not raw disposition ─

    @Test
    fun `RUNTIME_TRUTH_SNAPSHOT carries RECOVERING when reconnect is RECOVERING regardless of inflight disposition`() = runBlocking {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            deviceId = "Pixel-116"
        )
        val controller = buildController(settings)
        controller.setActiveForTest()
        // Set reconnect RECOVERING to simulate mid-reconnect state
        controller.setReconnectRecoveryStateForTest(ReconnectRecoveryState.RECOVERING)
        // inflight disposition is RESUMED_CLEANLY (no artifact), but unified phase is RECOVERING

        val deferred = async {
            withTimeout(500) {
                controller.reconciliationSignals.first {
                    it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
                }
            }
        }

        controller.publishRuntimeTruthSnapshot()

        val signal = deferred.await()
        val recoveryStateInPayload =
            signal.payload[ReconciliationSignal.KEY_CONTINUITY_RECOVERY_STATE]
        assertEquals(
            "RUNTIME_TRUTH_SNAPSHOT MUST carry 'recovering' when reconnect is RECOVERING, " +
                "not the raw inflight disposition",
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING.wireValue,
            recoveryStateInPayload
        )
    }

    @Test
    fun `unifiedRecoveryPhase returns to clean state after reconnect recovery succeeds`() {
        val settings = InMemoryAppSettings(deviceId = "Pixel-116")
        val controller = buildController(settings)
        controller.setActiveForTest()

        // Simulate reconnect cycle
        controller.setReconnectRecoveryStateForTest(ReconnectRecoveryState.RECOVERING)
        assertEquals(
            "During reconnect, unifiedRecoveryPhase must be RECOVERING",
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING,
            controller.unifiedRecoveryPhase.value
        )

        controller.setReconnectRecoveryStateForTest(ReconnectRecoveryState.RECOVERED)
        assertEquals(
            "After reconnect, unifiedRecoveryPhase must return to RESUMED_CLEANLY " +
                "(RECOVERED reconnect + RESUMED_CLEANLY inflight = RESUMED_CLEANLY)",
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RESUMED_CLEANLY,
            controller.unifiedRecoveryPhase.value
        )
    }
}
