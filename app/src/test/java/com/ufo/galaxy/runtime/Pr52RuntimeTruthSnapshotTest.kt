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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-52 — [RuntimeController.buildRuntimeTruthSnapshot] unit tests.
 *
 * Verifies that [RuntimeController.buildRuntimeTruthSnapshot] correctly builds
 * [AndroidParticipantRuntimeTruth] snapshots from the current runtime state
 * and provides monotonically increasing reconciliation epochs.
 *
 * ## Test matrix
 *
 * ### Null conditions
 *  - buildRuntimeTruthSnapshot returns null when no hostDescriptor is set.
 *
 * ### Happy path
 *  - buildRuntimeTruthSnapshot returns non-null when hostDescriptor is set.
 *  - Snapshot participantId is deviceId:hostId.
 *  - Snapshot deviceId matches descriptor.
 *  - Snapshot hostId matches descriptor.
 *  - Snapshot activeTaskId is null when not provided.
 *  - Snapshot activeTaskId echoes provided activeTaskId.
 *  - Snapshot activeTaskStatus is null when not provided.
 *  - Snapshot activeTaskStatus echoes provided activeTaskStatus.
 *
 * ### Epoch semantics
 *  - First snapshot epoch is 1.
 *  - Successive snapshots have strictly increasing epochs.
 *  - Each call increments epoch by exactly 1.
 *
 * ### ReconciliationSignal integration
 *  - runtimeTruthSnapshot factory produces signal of Kind RUNTIME_TRUTH_SNAPSHOT.
 *  - runtimeTruthSnapshot signal reconciliationEpoch matches the snapshot epoch.
 *  - runtimeTruthSnapshot signal participantId matches snapshot participantId.
 */
class Pr52RuntimeTruthSnapshotTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies ──────────────────────────────────────────────────────

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

    // ── Builder helpers ────────────────────────────────────────────────────────

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

    private fun buildTestHostDescriptor(
        hostId: String = "host-52",
        deviceId: String = "device-52"
    ): RuntimeHostDescriptor = RuntimeHostDescriptor(
        hostId = hostId,
        deviceId = deviceId,
        deviceRole = "phone",
        formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    )

    private fun buildController(
        hostDescriptor: RuntimeHostDescriptor? = null
    ): RuntimeController {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        return RuntimeController(
            webSocketClient = client,
            settings = InMemoryAppSettings(),
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L,
            hostDescriptor = hostDescriptor
        )
    }

    // ── Null conditions ────────────────────────────────────────────────────────

    @Test
    fun `buildRuntimeTruthSnapshot returns null when no hostDescriptor is set`() {
        val controller = buildController(hostDescriptor = null)
        assertNull(
            "buildRuntimeTruthSnapshot must return null when hostDescriptor is absent",
            controller.buildRuntimeTruthSnapshot()
        )
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    fun `buildRuntimeTruthSnapshot returns non-null when hostDescriptor is set`() {
        val controller = buildController(hostDescriptor = buildTestHostDescriptor())
        assertNotNull(
            "buildRuntimeTruthSnapshot must return a snapshot when hostDescriptor is present",
            controller.buildRuntimeTruthSnapshot()
        )
    }

    @Test
    fun `snapshot participantId is deviceId colon hostId`() {
        val descriptor = buildTestHostDescriptor(hostId = "h-52", deviceId = "d-52")
        val controller = buildController(hostDescriptor = descriptor)
        val snapshot = controller.buildRuntimeTruthSnapshot()!!
        assertEquals("d-52:h-52", snapshot.participantId)
    }

    @Test
    fun `snapshot deviceId matches descriptor deviceId`() {
        val descriptor = buildTestHostDescriptor(deviceId = "my-device")
        val controller = buildController(hostDescriptor = descriptor)
        val snapshot = controller.buildRuntimeTruthSnapshot()!!
        assertEquals("my-device", snapshot.deviceId)
    }

    @Test
    fun `snapshot hostId matches descriptor hostId`() {
        val descriptor = buildTestHostDescriptor(hostId = "my-host")
        val controller = buildController(hostDescriptor = descriptor)
        val snapshot = controller.buildRuntimeTruthSnapshot()!!
        assertEquals("my-host", snapshot.hostId)
    }

    @Test
    fun `snapshot activeTaskId is null when not provided`() {
        val controller = buildController(hostDescriptor = buildTestHostDescriptor())
        val snapshot = controller.buildRuntimeTruthSnapshot()!!
        assertNull("activeTaskId must be null by default", snapshot.activeTaskId)
    }

    @Test
    fun `snapshot activeTaskId echoes provided value`() {
        val controller = buildController(hostDescriptor = buildTestHostDescriptor())
        val snapshot = controller.buildRuntimeTruthSnapshot(activeTaskId = "task-abc")!!
        assertEquals("task-abc", snapshot.activeTaskId)
    }

    @Test
    fun `snapshot activeTaskStatus is null when not provided`() {
        val controller = buildController(hostDescriptor = buildTestHostDescriptor())
        val snapshot = controller.buildRuntimeTruthSnapshot()!!
        assertNull("activeTaskStatus must be null by default", snapshot.activeTaskStatus)
    }

    @Test
    fun `snapshot activeTaskStatus echoes provided value`() {
        val controller = buildController(hostDescriptor = buildTestHostDescriptor())
        val snapshot = controller.buildRuntimeTruthSnapshot(
            activeTaskStatus = ActiveTaskStatus.RUNNING
        )!!
        assertEquals(ActiveTaskStatus.RUNNING, snapshot.activeTaskStatus)
    }

    // ── Epoch semantics ────────────────────────────────────────────────────────

    @Test
    fun `first snapshot epoch is 1`() {
        val controller = buildController(hostDescriptor = buildTestHostDescriptor())
        val snapshot = controller.buildRuntimeTruthSnapshot()!!
        assertEquals("First snapshot epoch must be 1", 1, snapshot.reconciliationEpoch)
    }

    @Test
    fun `successive snapshots have strictly increasing epochs`() {
        val controller = buildController(hostDescriptor = buildTestHostDescriptor())
        val first = controller.buildRuntimeTruthSnapshot()!!
        val second = controller.buildRuntimeTruthSnapshot()!!
        val third = controller.buildRuntimeTruthSnapshot()!!
        assertTrue(
            "Epochs must be strictly increasing: ${first.reconciliationEpoch} < ${second.reconciliationEpoch} < ${third.reconciliationEpoch}",
            first.reconciliationEpoch < second.reconciliationEpoch &&
                second.reconciliationEpoch < third.reconciliationEpoch
        )
    }

    @Test
    fun `each call increments epoch by exactly 1`() {
        val controller = buildController(hostDescriptor = buildTestHostDescriptor())
        val first = controller.buildRuntimeTruthSnapshot()!!
        val second = controller.buildRuntimeTruthSnapshot()!!
        assertEquals(
            "Epoch must increment by exactly 1 per call",
            first.reconciliationEpoch + 1,
            second.reconciliationEpoch
        )
    }

    // ── ReconciliationSignal integration ───────────────────────────────────────

    @Test
    fun `runtimeTruthSnapshot signal has Kind RUNTIME_TRUTH_SNAPSHOT`() {
        val controller = buildController(hostDescriptor = buildTestHostDescriptor())
        val snapshot = controller.buildRuntimeTruthSnapshot()!!
        val signal = ReconciliationSignal.runtimeTruthSnapshot(truth = snapshot)
        assertEquals(ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT, signal.kind)
    }

    @Test
    fun `runtimeTruthSnapshot signal reconciliationEpoch matches snapshot epoch`() {
        val controller = buildController(hostDescriptor = buildTestHostDescriptor())
        val snapshot = controller.buildRuntimeTruthSnapshot()!!
        val signal = ReconciliationSignal.runtimeTruthSnapshot(truth = snapshot)
        assertEquals(snapshot.reconciliationEpoch, signal.reconciliationEpoch)
    }

    @Test
    fun `runtimeTruthSnapshot signal participantId matches snapshot participantId`() {
        val descriptor = buildTestHostDescriptor(hostId = "h-pr52", deviceId = "d-pr52")
        val controller = buildController(hostDescriptor = descriptor)
        val snapshot = controller.buildRuntimeTruthSnapshot()!!
        val signal = ReconciliationSignal.runtimeTruthSnapshot(truth = snapshot)
        assertEquals(snapshot.participantId, signal.participantId)
    }
}
