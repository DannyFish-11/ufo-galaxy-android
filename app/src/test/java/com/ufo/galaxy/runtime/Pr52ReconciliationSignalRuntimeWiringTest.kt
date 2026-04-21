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
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-52 — Reconciliation signal runtime wiring integration tests.
 *
 * Validates that [RuntimeController.reconciliationSignals] emits the correct
 * [ReconciliationSignal] events at each key lifecycle point:
 *
 *  1. **Session open** → [ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT] emitted.
 *  2. **Session close / stop** → [ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT] emitted.
 *  3. **invalidateSession** → [ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT] emitted.
 *  4. **notifyParticipantHealthChanged** → [ReconciliationSignal.Kind.PARTICIPANT_STATE] emitted.
 *  5. **notifyTakeoverFailed (CANCELLED)** → [ReconciliationSignal.Kind.TASK_CANCELLED] emitted.
 *  6. **notifyTakeoverFailed (FAILED)** → [ReconciliationSignal.Kind.TASK_FAILED] emitted.
 *  7. **notifyTakeoverFailed (TIMEOUT)** → [ReconciliationSignal.Kind.TASK_FAILED] emitted.
 *  8. **notifyTakeoverFailed (DISCONNECT)** → [ReconciliationSignal.Kind.TASK_FAILED] emitted.
 *  9. **recordTaskAccepted** → [ReconciliationSignal.Kind.TASK_ACCEPTED] emitted.
 * 10. **recordTaskCompleted** → [ReconciliationSignal.Kind.TASK_RESULT] emitted.
 * 11. **publishRuntimeTruthSnapshot** → [ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT] emitted.
 *
 * ## Test matrix
 *
 * ### reconciliationSignals — flow availability
 *  - reconciliationSignals is non-null and observable before any lifecycle event
 *
 * ### Session open (setActiveForTest) — RUNTIME_TRUTH_SNAPSHOT
 *  - setActiveForTest emits RUNTIME_TRUTH_SNAPSHOT
 *  - RUNTIME_TRUTH_SNAPSHOT has non-null runtimeTruth
 *  - RUNTIME_TRUTH_SNAPSHOT participantId matches descriptor identity
 *  - RUNTIME_TRUTH_SNAPSHOT hasRuntimeTruth is true
 *
 * ### Session close (stop) — RUNTIME_TRUTH_SNAPSHOT
 *  - stop emits RUNTIME_TRUTH_SNAPSHOT after session close
 *
 * ### Session invalidate — RUNTIME_TRUTH_SNAPSHOT
 *  - invalidateSession emits RUNTIME_TRUTH_SNAPSHOT
 *
 * ### notifyParticipantHealthChanged — PARTICIPANT_STATE
 *  - PARTICIPANT_STATE signal emitted on health change
 *  - PARTICIPANT_STATE kind is PARTICIPANT_STATE
 *  - PARTICIPANT_STATE payload contains health_state
 *  - PARTICIPANT_STATE payload contains readiness_state
 *  - PARTICIPANT_STATE status is STATUS_STATE_CHANGED
 *
 * ### notifyTakeoverFailed — task terminal signals
 *  - CANCELLED cause emits TASK_CANCELLED
 *  - FAILED cause emits TASK_FAILED
 *  - TIMEOUT cause emits TASK_FAILED
 *  - DISCONNECT cause emits TASK_FAILED
 *  - task terminal signal taskId matches supplied taskId
 *  - TASK_FAILED payload contains error_detail for FAILED cause
 *
 * ### recordTaskAccepted — TASK_ACCEPTED
 *  - TASK_ACCEPTED signal emitted
 *  - TASK_ACCEPTED kind is TASK_ACCEPTED
 *  - TASK_ACCEPTED taskId matches supplied taskId
 *  - TASK_ACCEPTED status is STATUS_RUNNING
 *  - TASK_ACCEPTED participantId matches descriptor identity
 *
 * ### recordTaskCompleted — TASK_RESULT
 *  - TASK_RESULT signal emitted
 *  - TASK_RESULT kind is TASK_RESULT
 *  - TASK_RESULT taskId matches supplied taskId
 *  - TASK_RESULT status is STATUS_SUCCESS
 *
 * ### publishRuntimeTruthSnapshot — on-demand RUNTIME_TRUTH_SNAPSHOT
 *  - publishRuntimeTruthSnapshot emits RUNTIME_TRUTH_SNAPSHOT
 *  - snapshot has non-null runtimeTruth
 *  - snapshot participantId matches descriptor identity
 *
 * ### no hostDescriptor — no-op signals
 *  - publishRuntimeTruthSnapshot is no-op without hostDescriptor
 *  - recordTaskAccepted is no-op without hostDescriptor
 *  - recordTaskCompleted is no-op without hostDescriptor
 *
 * ### Reconciliation epoch — monotonic ordering
 *  - Each RUNTIME_TRUTH_SNAPSHOT has non-negative reconciliationEpoch
 *  - Successive snapshots have non-decreasing reconciliationEpoch values
 */
class Pr52ReconciliationSignalRuntimeWiringTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies (mirror RuntimeControllerAttachedSessionTest) ─────────

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

    // ── Builder helpers ───────────────────────────────────────────────────────

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
        hostId: String = "test-host-id",
        deviceId: String = "test-device"
    ): RuntimeHostDescriptor = RuntimeHostDescriptor(
        hostId = hostId,
        deviceId = deviceId,
        deviceRole = "phone",
        formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
    )

    private fun buildController(
        hostDescriptor: RuntimeHostDescriptor? = buildTestHostDescriptor(),
        timeoutMs: Long = 100L
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = InMemoryAppSettings(),
            loopController = buildLoopController(),
            registrationTimeoutMs = timeoutMs,
            hostDescriptor = hostDescriptor
        )
        return controller to client
    }

    /** Expected participant ID for the default test host descriptor. */
    private val expectedParticipantId: String
        get() = RuntimeIdentityContracts.participantNodeId(
            deviceId = "test-device",
            runtimeHostId = "test-host-id"
        )

    // ── Helpers to collect signals synchronously ──────────────────────────────

    /**
     * Collects the first emitted [ReconciliationSignal] after [action], or returns null
     * if no signal was emitted (flow is backed by a buffer so signals are not lost).
     */
    private fun collectFirstSignalAfter(
        controller: RuntimeController,
        action: () -> Unit
    ): ReconciliationSignal? = runBlocking {
        // Replay buffer: collect in a coroutine that races with action() to capture buffered events.
        val deferred = async {
            controller.reconciliationSignals.first()
        }
        action()
        try {
            withTimeout(500) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            null
        }
    }

    /**
     * Collects all [ReconciliationSignal]s buffered by the flow after [action] completes,
     * up to a short drain window.  Returns the collected signals in emission order.
     */
    private fun collectSignalsAfter(
        controller: RuntimeController,
        count: Int,
        action: () -> Unit
    ): List<ReconciliationSignal> = runBlocking {
        action()
        withTimeout(500) {
            (1..count).map { controller.reconciliationSignals.first() }
        }
    }

    // ── reconciliationSignals — flow availability ──────────────────────────────

    @Test
    fun `reconciliationSignals flow is non-null and observable before any lifecycle event`() {
        val (controller, _) = buildController()
        assertNotNull("reconciliationSignals must be non-null", controller.reconciliationSignals)
    }

    // ── Session open (setActiveForTest) — RUNTIME_TRUTH_SNAPSHOT ─────────────

    @Test
    fun `setActiveForTest emits RUNTIME_TRUTH_SNAPSHOT on reconciliationSignals`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)

        val signal = collectFirstSignalAfter(controller) { controller.setActiveForTest() }

        assertNotNull("setActiveForTest must emit a reconciliation signal", signal)
        assertEquals(
            "Signal kind must be RUNTIME_TRUTH_SNAPSHOT after session open",
            ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT,
            signal!!.kind
        )
    }

    @Test
    fun `RUNTIME_TRUTH_SNAPSHOT from session open has non-null runtimeTruth`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)

        val signal = collectFirstSignalAfter(controller) { controller.setActiveForTest() }

        assertNotNull("RUNTIME_TRUTH_SNAPSHOT must carry non-null runtimeTruth", signal?.runtimeTruth)
    }

    @Test
    fun `RUNTIME_TRUTH_SNAPSHOT from session open has participantId matching descriptor`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)

        val signal = collectFirstSignalAfter(controller) { controller.setActiveForTest() }

        assertEquals(
            "RUNTIME_TRUTH_SNAPSHOT participantId must match descriptor identity",
            expectedParticipantId,
            signal?.participantId
        )
    }

    @Test
    fun `RUNTIME_TRUTH_SNAPSHOT from session open has hasRuntimeTruth true`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)

        val signal = collectFirstSignalAfter(controller) { controller.setActiveForTest() }

        assertTrue(
            "RUNTIME_TRUTH_SNAPSHOT hasRuntimeTruth must be true",
            signal?.hasRuntimeTruth == true
        )
    }

    // ── Session close (stop) — RUNTIME_TRUTH_SNAPSHOT ────────────────────────

    @Test
    fun `stop emits RUNTIME_TRUTH_SNAPSHOT on reconciliationSignals after session close`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()
        // Drain the open snapshot first
        runBlocking {
            try {
                withTimeout(200) {
                    controller.reconciliationSignals.first()
                }
            } catch (_: Exception) { /* drain best-effort */ }
        }

        val signal = collectFirstSignalAfter(controller) { controller.stop() }

        assertNotNull("stop must emit a reconciliation signal", signal)
        assertEquals(
            "Signal kind must be RUNTIME_TRUTH_SNAPSHOT after session close",
            ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT,
            signal!!.kind
        )
    }

    // ── Session invalidate — RUNTIME_TRUTH_SNAPSHOT ───────────────────────────

    @Test
    fun `invalidateSession emits RUNTIME_TRUTH_SNAPSHOT on reconciliationSignals`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()
        // Drain the open snapshot first
        runBlocking {
            try {
                withTimeout(200) {
                    controller.reconciliationSignals.first()
                }
            } catch (_: Exception) { /* drain best-effort */ }
        }

        val signal = collectFirstSignalAfter(controller) { controller.invalidateSession() }

        assertNotNull("invalidateSession must emit a reconciliation signal", signal)
        assertEquals(
            "Signal kind must be RUNTIME_TRUTH_SNAPSHOT after invalidation",
            ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT,
            signal!!.kind
        )
    }

    // ── notifyParticipantHealthChanged — PARTICIPANT_STATE ────────────────────

    @Test
    fun `notifyParticipantHealthChanged emits PARTICIPANT_STATE signal`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        // Drain the open snapshot first
        runBlocking {
            try {
                withTimeout(200) {
                    controller.reconciliationSignals.first()
                }
            } catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.notifyParticipantHealthChanged(ParticipantHealthState.HEALTHY)
        }

        assertNotNull("health change must emit a reconciliation signal", signal)
        assertEquals(
            ReconciliationSignal.Kind.PARTICIPANT_STATE,
            signal!!.kind
        )
    }

    @Test
    fun `PARTICIPANT_STATE signal has STATUS_STATE_CHANGED`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.notifyParticipantHealthChanged(ParticipantHealthState.DEGRADED)
        }

        assertEquals(
            "PARTICIPANT_STATE status must be STATUS_STATE_CHANGED",
            ReconciliationSignal.STATUS_STATE_CHANGED,
            signal?.status
        )
    }

    @Test
    fun `PARTICIPANT_STATE payload contains health_state`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.notifyParticipantHealthChanged(ParticipantHealthState.HEALTHY)
        }

        assertEquals(
            "PARTICIPANT_STATE payload must contain health_state",
            ParticipantHealthState.HEALTHY.wireValue,
            signal?.payload?.get("health_state")
        )
    }

    @Test
    fun `PARTICIPANT_STATE payload contains readiness_state`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.notifyParticipantHealthChanged(
                ParticipantHealthState.HEALTHY,
                ParticipantReadinessState.READY
            )
        }

        assertEquals(
            "PARTICIPANT_STATE payload must contain readiness_state",
            ParticipantReadinessState.READY.wireValue,
            signal?.payload?.get("readiness_state")
        )
    }

    // ── notifyTakeoverFailed — task terminal signals ───────────────────────────

    @Test
    fun `notifyTakeoverFailed with CANCELLED emits TASK_CANCELLED signal`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            runBlocking {
                controller.notifyTakeoverFailed(
                    takeoverId = "takeover-1",
                    taskId = "task-cancel-1",
                    traceId = "trace-1",
                    reason = "user cancelled",
                    cause = TakeoverFallbackEvent.Cause.CANCELLED
                )
            }
        }

        assertNotNull("CANCELLED cause must emit a reconciliation signal", signal)
        assertEquals(
            "CANCELLED cause must emit TASK_CANCELLED signal",
            ReconciliationSignal.Kind.TASK_CANCELLED,
            signal!!.kind
        )
    }

    @Test
    fun `notifyTakeoverFailed with FAILED emits TASK_FAILED signal`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            runBlocking {
                controller.notifyTakeoverFailed(
                    takeoverId = "takeover-2",
                    taskId = "task-fail-1",
                    traceId = "trace-2",
                    reason = "pipeline exception",
                    cause = TakeoverFallbackEvent.Cause.FAILED
                )
            }
        }

        assertNotNull("FAILED cause must emit a reconciliation signal", signal)
        assertEquals(
            "FAILED cause must emit TASK_FAILED signal",
            ReconciliationSignal.Kind.TASK_FAILED,
            signal!!.kind
        )
    }

    @Test
    fun `notifyTakeoverFailed with TIMEOUT emits TASK_FAILED signal`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            runBlocking {
                controller.notifyTakeoverFailed(
                    takeoverId = "takeover-3",
                    taskId = "task-timeout-1",
                    traceId = "trace-3",
                    reason = "exceeded budget",
                    cause = TakeoverFallbackEvent.Cause.TIMEOUT
                )
            }
        }

        assertNotNull("TIMEOUT cause must emit a reconciliation signal", signal)
        assertEquals(
            "TIMEOUT cause must emit TASK_FAILED signal",
            ReconciliationSignal.Kind.TASK_FAILED,
            signal!!.kind
        )
    }

    @Test
    fun `notifyTakeoverFailed with DISCONNECT emits TASK_FAILED signal`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            runBlocking {
                controller.notifyTakeoverFailed(
                    takeoverId = "takeover-4",
                    taskId = "task-disconnect-1",
                    traceId = "trace-4",
                    reason = "ws disconnect",
                    cause = TakeoverFallbackEvent.Cause.DISCONNECT
                )
            }
        }

        assertNotNull("DISCONNECT cause must emit a reconciliation signal", signal)
        assertEquals(
            "DISCONNECT cause must emit TASK_FAILED signal",
            ReconciliationSignal.Kind.TASK_FAILED,
            signal!!.kind
        )
    }

    @Test
    fun `task terminal signal taskId matches supplied taskId`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            runBlocking {
                controller.notifyTakeoverFailed(
                    takeoverId = "takeover-5",
                    taskId = "my-specific-task-id",
                    traceId = "trace-5",
                    reason = "failed",
                    cause = TakeoverFallbackEvent.Cause.FAILED
                )
            }
        }

        assertEquals(
            "Task terminal signal taskId must match the supplied taskId",
            "my-specific-task-id",
            signal?.taskId
        )
    }

    @Test
    fun `TASK_FAILED payload contains error_detail for FAILED cause`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            runBlocking {
                controller.notifyTakeoverFailed(
                    takeoverId = "takeover-6",
                    taskId = "task-fail-detail",
                    traceId = "trace-6",
                    reason = "some_pipeline_error",
                    cause = TakeoverFallbackEvent.Cause.FAILED
                )
            }
        }

        val errorDetail = signal?.payload?.get("error_detail") as? String
        assertNotNull("TASK_FAILED payload must contain error_detail", errorDetail)
        assertTrue(
            "error_detail must contain the failure reason",
            errorDetail!!.contains("some_pipeline_error")
        )
    }

    // ── recordTaskAccepted — TASK_ACCEPTED ────────────────────────────────────

    @Test
    fun `recordTaskAccepted emits TASK_ACCEPTED signal`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.recordTaskAccepted("task-accepted-001")
        }

        assertNotNull("recordTaskAccepted must emit a reconciliation signal", signal)
        assertEquals(
            ReconciliationSignal.Kind.TASK_ACCEPTED,
            signal!!.kind
        )
    }

    @Test
    fun `TASK_ACCEPTED signal taskId matches supplied taskId`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.recordTaskAccepted("task-id-xyz")
        }

        assertEquals("task-id-xyz", signal?.taskId)
    }

    @Test
    fun `TASK_ACCEPTED signal status is STATUS_RUNNING`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.recordTaskAccepted("task-running-001")
        }

        assertEquals(ReconciliationSignal.STATUS_RUNNING, signal?.status)
    }

    @Test
    fun `TASK_ACCEPTED signal participantId matches descriptor identity`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.recordTaskAccepted("task-pid-001")
        }

        assertEquals(expectedParticipantId, signal?.participantId)
    }

    // ── recordTaskCompleted — TASK_RESULT ─────────────────────────────────────

    @Test
    fun `recordTaskCompleted emits TASK_RESULT signal`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.recordTaskCompleted("task-completed-001")
        }

        assertNotNull("recordTaskCompleted must emit a reconciliation signal", signal)
        assertEquals(
            ReconciliationSignal.Kind.TASK_RESULT,
            signal!!.kind
        )
    }

    @Test
    fun `TASK_RESULT signal taskId matches supplied taskId`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.recordTaskCompleted("task-done-789")
        }

        assertEquals("task-done-789", signal?.taskId)
    }

    @Test
    fun `TASK_RESULT signal status is STATUS_SUCCESS`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.recordTaskCompleted("task-success-001")
        }

        assertEquals(ReconciliationSignal.STATUS_SUCCESS, signal?.status)
    }

    // ── publishRuntimeTruthSnapshot — on-demand RUNTIME_TRUTH_SNAPSHOT ────────

    @Test
    fun `publishRuntimeTruthSnapshot emits RUNTIME_TRUTH_SNAPSHOT signal`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.publishRuntimeTruthSnapshot()
        }

        assertNotNull("publishRuntimeTruthSnapshot must emit a signal", signal)
        assertEquals(
            ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT,
            signal!!.kind
        )
    }

    @Test
    fun `on-demand RUNTIME_TRUTH_SNAPSHOT has non-null runtimeTruth`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.publishRuntimeTruthSnapshot()
        }

        assertNotNull("on-demand snapshot must carry non-null runtimeTruth", signal?.runtimeTruth)
    }

    @Test
    fun `on-demand RUNTIME_TRUTH_SNAPSHOT participantId matches descriptor identity`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        runBlocking {
            try { withTimeout(200) { controller.reconciliationSignals.first() } }
            catch (_: Exception) { /* drain */ }
        }

        val signal = collectFirstSignalAfter(controller) {
            controller.publishRuntimeTruthSnapshot()
        }

        assertEquals(expectedParticipantId, signal?.participantId)
    }

    // ── no hostDescriptor — no-op signals ─────────────────────────────────────

    @Test
    fun `publishRuntimeTruthSnapshot is no-op without hostDescriptor`() {
        val (controller, _) = buildController(hostDescriptor = null)

        val signal = collectFirstSignalAfter(controller) {
            controller.publishRuntimeTruthSnapshot()
        }

        assertNull(
            "publishRuntimeTruthSnapshot must be no-op when no hostDescriptor is set",
            signal
        )
    }

    @Test
    fun `recordTaskAccepted is no-op without hostDescriptor`() {
        val (controller, _) = buildController(hostDescriptor = null)

        val signal = collectFirstSignalAfter(controller) {
            controller.recordTaskAccepted("task-noop")
        }

        assertNull(
            "recordTaskAccepted must be no-op when no hostDescriptor is set",
            signal
        )
    }

    @Test
    fun `recordTaskCompleted is no-op without hostDescriptor`() {
        val (controller, _) = buildController(hostDescriptor = null)

        val signal = collectFirstSignalAfter(controller) {
            controller.recordTaskCompleted("task-noop")
        }

        assertNull(
            "recordTaskCompleted must be no-op when no hostDescriptor is set",
            signal
        )
    }

    // ── Reconciliation epoch — monotonic ordering ──────────────────────────────

    @Test
    fun `each RUNTIME_TRUTH_SNAPSHOT has non-negative reconciliationEpoch`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)

        val signal = collectFirstSignalAfter(controller) { controller.setActiveForTest() }

        assertTrue(
            "RUNTIME_TRUTH_SNAPSHOT reconciliationEpoch must be non-negative",
            (signal?.reconciliationEpoch ?: -1) >= 0
        )
    }

    @Test
    fun `successive RUNTIME_TRUTH_SNAPSHOT signals have strictly increasing reconciliationEpoch`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()

        val first = collectFirstSignalAfter(controller) {
            controller.publishRuntimeTruthSnapshot()
        }
        val second = collectFirstSignalAfter(controller) {
            controller.publishRuntimeTruthSnapshot()
        }

        assertNotNull("First snapshot must be emitted", first)
        assertNotNull("Second snapshot must be emitted", second)
        assertTrue(
            "Second snapshot epoch must be strictly greater than first snapshot epoch",
            second!!.reconciliationEpoch > first!!.reconciliationEpoch
        )
    }
}
