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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-123 — Android V2 Distributed Activation Compatibility Contract tests.
 *
 * Validates:
 * 1. [AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass] has 4 values
 *    with the expected wire values.
 * 2. [AndroidV2DistributedActivationCompatibilityContract.classify] returns the correct class
 *    for each combination of taskId / dispatchPlanId / sessionId.
 * 3. [AndroidV2DistributedActivationCompatibilityContract.toWireMap] includes all expected keys.
 * 4. [AndroidV2DistributedActivationCompatibilityContract.DISTRIBUTED_ACTIVATION_INVARIANTS]
 *    all pass.
 * 5. [ReconciliationSignal.dispatchPlanId] field is null by default and carried through factories.
 * 6. [ReconciliationSignal.withDispatchPlanId] attaches / detaches a plan ID and updates payload.
 * 7. [ReconciliationSignal.KEY_DISPATCH_PLAN_ID] is the expected wire key.
 * 8. [RuntimeController.recordDelegatedTaskAccepted] accepts a dispatchPlanId parameter and
 *    the emitted TASK_ACCEPTED signal carries it.
 * 9. [RuntimeController.publishTaskResult] emits TASK_RESULT with the stored dispatchPlanId.
 * 10. [RuntimeController.publishTaskCancelled] emits TASK_CANCELLED with the stored dispatchPlanId.
 * 11. [StabilizationBaseline] registers android-v2-distributed-activation-compatibility-contract.
 */
class Pr123AndroidV2DistributedActivationCompatibilityContractTest {

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

    private fun buildController(): RuntimeController {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        return RuntimeController(
            webSocketClient = client,
            settings = InMemoryAppSettings(),
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L,
            hostDescriptor = RuntimeHostDescriptor(
                hostId = "host-123",
                deviceId = "Pixel-123",
                deviceRole = "phone",
                formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
            )
        )
    }

    // ── 1. ActivationIdentityClass enum values ────────────────────────────────

    @Test
    fun `ActivationIdentityClass has exactly 4 values`() {
        assertEquals(
            4,
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.entries.size
        )
    }

    @Test
    fun `DISPATCH_PLAN_ANCHORED wireValue is dispatch_plan_anchored`() {
        assertEquals(
            "dispatch_plan_anchored",
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
                .DISPATCH_PLAN_ANCHORED.wireValue
        )
    }

    @Test
    fun `TASK_IDENTITY_ONLY wireValue is task_identity_only`() {
        assertEquals(
            "task_identity_only",
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
                .TASK_IDENTITY_ONLY.wireValue
        )
    }

    @Test
    fun `SESSION_ANCHORED wireValue is session_anchored`() {
        assertEquals(
            "session_anchored",
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
                .SESSION_ANCHORED.wireValue
        )
    }

    @Test
    fun `UNANCHORED wireValue is unanchored`() {
        assertEquals(
            "unanchored",
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
                .UNANCHORED.wireValue
        )
    }

    @Test
    fun `DISPATCH_PLAN_ANCHORED isDispatchPlanAnchored is true`() {
        assertTrue(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
                .DISPATCH_PLAN_ANCHORED.isDispatchPlanAnchored
        )
    }

    @Test
    fun `TASK_IDENTITY_ONLY isDispatchPlanAnchored is false`() {
        assertFalse(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
                .TASK_IDENTITY_ONLY.isDispatchPlanAnchored
        )
    }

    @Test
    fun `SESSION_ANCHORED isAmbiguousForDistributed is true`() {
        assertTrue(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
                .SESSION_ANCHORED.isAmbiguousForDistributed
        )
    }

    @Test
    fun `UNANCHORED isAmbiguousForDistributed is true`() {
        assertTrue(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
                .UNANCHORED.isAmbiguousForDistributed
        )
    }

    @Test
    fun `DISPATCH_PLAN_ANCHORED isAmbiguousForDistributed is false`() {
        assertFalse(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
                .DISPATCH_PLAN_ANCHORED.isAmbiguousForDistributed
        )
    }

    // ── 2. classify() ─────────────────────────────────────────────────────────

    @Test
    fun `classify with taskId and dispatchPlanId yields DISPATCH_PLAN_ANCHORED`() {
        val result = AndroidV2DistributedActivationCompatibilityContract.classify(
            taskId = "t-1",
            dispatchPlanId = "plan-1",
            sessionId = "s-1"
        )
        assertEquals(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.DISPATCH_PLAN_ANCHORED,
            result
        )
    }

    @Test
    fun `classify with taskId only yields TASK_IDENTITY_ONLY`() {
        val result = AndroidV2DistributedActivationCompatibilityContract.classify(
            taskId = "t-2",
            dispatchPlanId = null,
            sessionId = "s-2"
        )
        assertEquals(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.TASK_IDENTITY_ONLY,
            result
        )
    }

    @Test
    fun `classify with sessionId only yields SESSION_ANCHORED`() {
        val result = AndroidV2DistributedActivationCompatibilityContract.classify(
            taskId = null,
            dispatchPlanId = null,
            sessionId = "s-3"
        )
        assertEquals(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.SESSION_ANCHORED,
            result
        )
    }

    @Test
    fun `classify with no identifiers yields UNANCHORED`() {
        val result = AndroidV2DistributedActivationCompatibilityContract.classify(
            taskId = null,
            dispatchPlanId = null,
            sessionId = null
        )
        assertEquals(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.UNANCHORED,
            result
        )
    }

    @Test
    fun `classify with blank taskId yields SESSION_ANCHORED when sessionId present`() {
        val result = AndroidV2DistributedActivationCompatibilityContract.classify(
            taskId = "",
            dispatchPlanId = null,
            sessionId = "s-4"
        )
        assertEquals(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.SESSION_ANCHORED,
            result
        )
    }

    // ── 3. toWireMap ──────────────────────────────────────────────────────────

    @Test
    fun `toWireMap includes activation_identity_class key`() {
        val map = AndroidV2DistributedActivationCompatibilityContract.toWireMap(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.DISPATCH_PLAN_ANCHORED
        )
        assertTrue(
            AndroidV2DistributedActivationCompatibilityContract.KEY_ACTIVATION_IDENTITY_CLASS in map
        )
    }

    @Test
    fun `toWireMap includes activation_has_dispatch_plan_id key`() {
        val map = AndroidV2DistributedActivationCompatibilityContract.toWireMap(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.TASK_IDENTITY_ONLY
        )
        assertTrue(
            AndroidV2DistributedActivationCompatibilityContract.KEY_HAS_DISPATCH_PLAN_ID in map
        )
    }

    @Test
    fun `toWireMap includes activation_has_task_id key`() {
        val map = AndroidV2DistributedActivationCompatibilityContract.toWireMap(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.SESSION_ANCHORED
        )
        assertTrue(
            AndroidV2DistributedActivationCompatibilityContract.KEY_HAS_TASK_ID in map
        )
    }

    @Test
    fun `toWireMap includes activation_identity_schema_version key`() {
        val map = AndroidV2DistributedActivationCompatibilityContract.toWireMap(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.UNANCHORED
        )
        assertTrue(
            AndroidV2DistributedActivationCompatibilityContract.KEY_ACTIVATION_SCHEMA_VERSION in map
        )
    }

    @Test
    fun `toWireMap DISPATCH_PLAN_ANCHORED has_dispatch_plan_id is true`() {
        val map = AndroidV2DistributedActivationCompatibilityContract.toWireMap(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.DISPATCH_PLAN_ANCHORED
        )
        assertEquals(
            "true",
            map[AndroidV2DistributedActivationCompatibilityContract.KEY_HAS_DISPATCH_PLAN_ID]
        )
    }

    @Test
    fun `toWireMap TASK_IDENTITY_ONLY has_dispatch_plan_id is false`() {
        val map = AndroidV2DistributedActivationCompatibilityContract.toWireMap(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.TASK_IDENTITY_ONLY
        )
        assertEquals(
            "false",
            map[AndroidV2DistributedActivationCompatibilityContract.KEY_HAS_DISPATCH_PLAN_ID]
        )
    }

    // ── 4. DISTRIBUTED_ACTIVATION_INVARIANTS ─────────────────────────────────

    @Test
    fun `all DISTRIBUTED_ACTIVATION_INVARIANTS pass`() {
        AndroidV2DistributedActivationCompatibilityContract.DISTRIBUTED_ACTIVATION_INVARIANTS
            .forEach { (key, value) ->
                assertTrue("Invariant $key failed", value)
            }
    }

    @Test
    fun `DISTRIBUTED_ACTIVATION_INVARIANTS has exactly 6 entries`() {
        assertEquals(
            6,
            AndroidV2DistributedActivationCompatibilityContract.DISTRIBUTED_ACTIVATION_INVARIANTS.size
        )
    }

    // ── 5. ReconciliationSignal.dispatchPlanId default is null ────────────────

    @Test
    fun `taskAccepted signal dispatchPlanId defaults to null`() {
        val signal = ReconciliationSignal.taskAccepted(
            participantId = "p-1",
            taskId = "t-1"
        )
        assertNull(signal.dispatchPlanId)
    }

    @Test
    fun `taskResult signal dispatchPlanId defaults to null`() {
        val signal = ReconciliationSignal.taskResult(
            participantId = "p-1",
            taskId = "t-1"
        )
        assertNull(signal.dispatchPlanId)
    }

    @Test
    fun `taskFailed signal dispatchPlanId defaults to null`() {
        val signal = ReconciliationSignal.taskFailed(
            participantId = "p-1",
            taskId = "t-1"
        )
        assertNull(signal.dispatchPlanId)
    }

    // ── 6. ReconciliationSignal.withDispatchPlanId ────────────────────────────

    @Test
    fun `withDispatchPlanId sets dispatchPlanId field`() {
        val base = ReconciliationSignal.taskAccepted(participantId = "p-1", taskId = "t-1")
        val updated = base.withDispatchPlanId("plan-abc")
        assertEquals("plan-abc", updated.dispatchPlanId)
    }

    @Test
    fun `withDispatchPlanId adds KEY_DISPATCH_PLAN_ID to payload`() {
        val base = ReconciliationSignal.taskResult(participantId = "p-1", taskId = "t-1")
        val updated = base.withDispatchPlanId("plan-xyz")
        assertEquals("plan-xyz", updated.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID])
    }

    @Test
    fun `withDispatchPlanId null clears dispatchPlanId field`() {
        val base = ReconciliationSignal.taskAccepted(participantId = "p-1", taskId = "t-1")
            .withDispatchPlanId("plan-1")
        val cleared = base.withDispatchPlanId(null)
        assertNull(cleared.dispatchPlanId)
    }

    @Test
    fun `withDispatchPlanId null removes KEY_DISPATCH_PLAN_ID from payload`() {
        val base = ReconciliationSignal.taskAccepted(participantId = "p-1", taskId = "t-1")
            .withDispatchPlanId("plan-1")
        val cleared = base.withDispatchPlanId(null)
        assertFalse(ReconciliationSignal.KEY_DISPATCH_PLAN_ID in cleared.payload)
    }

    @Test
    fun `withDispatchPlanId preserves other payload fields`() {
        val base = ReconciliationSignal.taskResult(participantId = "p-1", taskId = "t-1")
        val updated = base.withDispatchPlanId("plan-abc")
        // All original payload keys must still be present.
        base.payload.keys.forEach { key ->
            assertTrue("Payload key '$key' was lost by withDispatchPlanId", key in updated.payload)
        }
    }

    // ── 7. KEY_DISPATCH_PLAN_ID constant ──────────────────────────────────────

    @Test
    fun `KEY_DISPATCH_PLAN_ID wire value is dispatch_plan_id`() {
        assertEquals("dispatch_plan_id", ReconciliationSignal.KEY_DISPATCH_PLAN_ID)
    }

    // ── 8. RuntimeController — TASK_ACCEPTED carries dispatchPlanId ───────────

    @Test
    fun `recordDelegatedTaskAccepted with dispatchPlanId emits TASK_ACCEPTED with plan id`() =
        runBlocking {
            val controller = buildController()

            controller.recordDelegatedTaskAccepted(
                taskId = "task-plan-test",
                correlationId = null,
                dispatchPlanId = "plan-001"
            )

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_ACCEPTED signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_ACCEPTED, signal!!.kind)
            assertEquals("plan-001", signal.dispatchPlanId)
            assertEquals(
                "plan-001",
                signal.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID]
            )
        }

    @Test
    fun `recordDelegatedTaskAccepted without dispatchPlanId emits TASK_ACCEPTED with null plan id`() =
        runBlocking {
            val controller = buildController()

            controller.recordDelegatedTaskAccepted(taskId = "task-no-plan")

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_ACCEPTED signal", signal)
            assertNull(signal!!.dispatchPlanId)
            assertFalse(ReconciliationSignal.KEY_DISPATCH_PLAN_ID in signal.payload)
        }

    // ── 9. RuntimeController — TASK_RESULT carries dispatchPlanId ────────────

    @Test
    fun `publishTaskResult emits TASK_RESULT with stored dispatchPlanId`() = runBlocking {
        val controller = buildController()

        controller.recordDelegatedTaskAccepted(
            taskId = "task-result-plan",
            dispatchPlanId = "plan-result-001"
        )
        // Drain the TASK_ACCEPTED signal.
        withTimeoutOrNull(200) { controller.reconciliationSignals.first() }

        controller.publishTaskResult("task-result-plan")

        val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
        assertNotNull("Expected TASK_RESULT signal", signal)
        assertEquals(ReconciliationSignal.Kind.TASK_RESULT, signal!!.kind)
        assertEquals("plan-result-001", signal.dispatchPlanId)
        assertEquals(
            "plan-result-001",
            signal.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID]
        )
    }

    // ── 10. RuntimeController — TASK_CANCELLED carries dispatchPlanId ─────────

    @Test
    fun `publishTaskCancelled emits TASK_CANCELLED with stored dispatchPlanId`() = runBlocking {
        val controller = buildController()

        controller.recordDelegatedTaskAccepted(
            taskId = "task-cancel-plan",
            dispatchPlanId = "plan-cancel-001"
        )
        // Drain the TASK_ACCEPTED signal.
        withTimeoutOrNull(200) { controller.reconciliationSignals.first() }

        controller.publishTaskCancelled("task-cancel-plan")

        val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
        assertNotNull("Expected TASK_CANCELLED signal", signal)
        assertEquals(ReconciliationSignal.Kind.TASK_CANCELLED, signal!!.kind)
        assertEquals("plan-cancel-001", signal.dispatchPlanId)
    }

    @Test
    fun `publishTaskStatusUpdate emits TASK_STATUS_UPDATE with stored dispatchPlanId`() = runBlocking {
        val controller = buildController()

        controller.recordDelegatedTaskAccepted(
            taskId = "task-status-plan",
            dispatchPlanId = "plan-status-001"
        )
        withTimeoutOrNull(200) { controller.reconciliationSignals.first() }

        controller.publishTaskStatusUpdate("task-status-plan", progressDetail = "still-running")

        val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
        assertNotNull("Expected TASK_STATUS_UPDATE signal", signal)
        assertEquals(ReconciliationSignal.Kind.TASK_STATUS_UPDATE, signal!!.kind)
        assertEquals("plan-status-001", signal.dispatchPlanId)
        assertEquals(
            "plan-status-001",
            signal.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID]
        )
    }

    // ── 11. StabilizationBaseline registration ────────────────────────────────

    @Test
    fun `StabilizationBaseline registers android-v2-distributed-activation-compatibility-contract`() {
        val entry = StabilizationBaseline.forId(
            "android-v2-distributed-activation-compatibility-contract"
        )
        assertNotNull("Expected entry in StabilizationBaseline", entry)
    }

    @Test
    fun `android-v2-distributed-activation-compatibility-contract is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId(
            "android-v2-distributed-activation-compatibility-contract"
        )
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
    }

    @Test
    fun `android-v2-distributed-activation-compatibility-contract has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId(
            "android-v2-distributed-activation-compatibility-contract"
        )
        assertEquals(
            StabilizationBaseline.ExtensionGuidance.EXTEND,
            entry!!.extensionGuidance
        )
    }

    @Test
    fun `android-v2-distributed-activation-compatibility-contract introducedPr is 123`() {
        val entry = StabilizationBaseline.forId(
            "android-v2-distributed-activation-compatibility-contract"
        )
        assertEquals(123, entry!!.introducedPr)
    }

    // ── 12. V2_DISTRIBUTED_ACTIVATION_ALIGNMENT_MAP coverage ─────────────────

    @Test
    fun `V2_DISTRIBUTED_ACTIVATION_ALIGNMENT_MAP covers all ActivationIdentityClass values`() {
        AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.entries
            .forEach { cls ->
                assertTrue(
                    "V2_DISTRIBUTED_ACTIVATION_ALIGNMENT_MAP missing entry for ${cls.wireValue}",
                    cls.wireValue in
                        AndroidV2DistributedActivationCompatibilityContract
                            .V2_DISTRIBUTED_ACTIVATION_ALIGNMENT_MAP
                )
            }
    }
}
