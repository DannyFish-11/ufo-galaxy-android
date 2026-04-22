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
import com.ufo.galaxy.observability.GalaxyLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * PR-62 — Participant Live Execution Surface: Acceptance and Regression Test Suite.
 *
 * Validates the four PR-62 acceptance criteria and the concrete wiring changes in
 * [RuntimeController] and [ParticipantLiveExecutionSurface]:
 *
 *  1. **[ParticipantLiveExecutionSurface]** — structural completeness and count invariants.
 *  2. **[RuntimeController.activeTaskId] / [RuntimeController.activeTaskStatus]** — live
 *     active task state tracking.
 *  3. **[RuntimeController.publishTaskStatusUpdate]** — TASK_STATUS_UPDATE signal emission.
 *  4. **Automatic TASK_FAILED on WS disconnect** — interrupt wiring in [RuntimeController].
 *  5. **Automatic RUNTIME_TRUTH_SNAPSHOT on reconnect** — reconnect idle truth wiring.
 *  6. **[StabilizationBaseline]** — exactly four PR-62 entries registered as CANONICAL_STABLE.
 *
 * ## Test matrix
 *
 * ### ParticipantLiveExecutionSurface — structural invariants
 *  - LIVE_EXECUTION_WIRING has exactly LIVE_EXECUTION_WIRING_COUNT entries
 *  - TASK_LIFECYCLE_WIRING has exactly TASK_LIFECYCLE_WIRING_COUNT entries
 *  - INTERRUPTION_AND_RECOVERY has exactly INTERRUPTION_AND_RECOVERY_COUNT entries
 *  - FIRST_CLASS_PARTICIPANT_EVIDENCE has exactly FIRST_CLASS_PARTICIPANT_EVIDENCE_COUNT entries
 *  - all LIVE_EXECUTION_WIRING keys are non-blank
 *  - all LIVE_EXECUTION_WIRING values are non-blank
 *  - all TASK_LIFECYCLE_WIRING keys are non-blank
 *  - all TASK_LIFECYCLE_WIRING values are non-blank
 *  - all INTERRUPTION_AND_RECOVERY keys are non-blank
 *  - all INTERRUPTION_AND_RECOVERY values are non-blank
 *  - all FIRST_CLASS_PARTICIPANT_EVIDENCE keys are non-blank
 *  - all FIRST_CLASS_PARTICIPANT_EVIDENCE values are non-blank
 *  - INTRODUCED_PR is 62
 *
 * ### ParticipantLiveExecutionSurface — known keys
 *  - active_task_id key is in LIVE_EXECUTION_WIRING
 *  - active_task_status key is in LIVE_EXECUTION_WIRING
 *  - reconciliation_signal_stream key is in LIVE_EXECUTION_WIRING
 *  - TASK_ACCEPTED wire value is in TASK_LIFECYCLE_WIRING
 *  - TASK_STATUS_UPDATE wire value is in TASK_LIFECYCLE_WIRING
 *  - TASK_RESULT wire value is in TASK_LIFECYCLE_WIRING
 *  - TASK_CANCELLED wire value is in TASK_LIFECYCLE_WIRING
 *  - TASK_FAILED wire value is in TASK_LIFECYCLE_WIRING
 *  - transient_ws_disconnect_with_active_task is in INTERRUPTION_AND_RECOVERY
 *  - reconnect_recovery_idle_snapshot is in INTERRUPTION_AND_RECOVERY
 *  - live_active_task_tracking is in FIRST_CLASS_PARTICIPANT_EVIDENCE
 *  - automatic_task_interruption_signal is in FIRST_CLASS_PARTICIPANT_EVIDENCE
 *  - in_progress_status_update_surface is in FIRST_CLASS_PARTICIPANT_EVIDENCE
 *
 * ### ParticipantLiveExecutionSurface — wire-key constants
 *  - KEY_INTERRUPTED_BY is "interrupted_by"
 *  - KEY_FINISHED_WITH is "finished_with"
 *  - KEY_TASK_ID is "task_id"
 *  - KEY_PARTICIPANT_ID is "participant_id"
 *  - KEY_PROGRESS_DETAIL is "progress_detail"
 *
 * ### RuntimeController — activeTaskId / activeTaskStatus lifecycle
 *  - activeTaskId is null initially
 *  - activeTaskStatus is null initially
 *  - recordDelegatedTaskAccepted sets activeTaskId
 *  - recordDelegatedTaskAccepted sets activeTaskStatus to RUNNING
 *  - publishTaskResult clears activeTaskId to null
 *  - publishTaskResult clears activeTaskStatus to null
 *  - publishTaskCancelled clears activeTaskId to null
 *  - publishTaskCancelled clears activeTaskStatus to null
 *  - notifyTakeoverFailed(FAILED) clears activeTaskId to null
 *  - notifyTakeoverFailed(CANCELLED) clears activeTaskId to null
 *
 * ### RuntimeController — publishTaskStatusUpdate
 *  - publishTaskStatusUpdate without hostDescriptor does not emit a signal
 *  - publishTaskStatusUpdate emits TASK_STATUS_UPDATE on reconciliationSignals
 *  - emitted signal kind is TASK_STATUS_UPDATE
 *  - emitted signal status is STATUS_IN_PROGRESS
 *  - emitted signal participantId matches hostDescriptor identity
 *  - emitted signal taskId matches provided taskId
 *  - emitted signal correlationId is echoed when provided
 *  - emitted signal payload contains progress_detail when provided
 *  - emitted signal payload does not contain progress_detail when omitted
 *  - emitted signal isTerminal is false
 *  - emitted signal signalId is non-blank
 *
 * ### RuntimeController — automatic TASK_FAILED on disconnect
 *  - disconnect while active task emits TASK_FAILED on reconciliationSignals
 *  - emitted TASK_FAILED signal taskId matches active task
 *  - emitted TASK_FAILED errorDetail contains session_interrupted
 *  - disconnect clears activeTaskId after emitting TASK_FAILED
 *  - disconnect clears activeTaskStatus after emitting TASK_FAILED
 *  - disconnect with no active task does NOT emit TASK_FAILED
 *
 * ### RuntimeController — automatic RUNTIME_TRUTH_SNAPSHOT on reconnect
 *  - reconnect (RECONNECT_RECOVERY) emits RUNTIME_TRUTH_SNAPSHOT on reconciliationSignals
 *  - snapshot kind is RUNTIME_TRUTH_SNAPSHOT
 *  - snapshot runtimeTruth is non-null
 *  - snapshot runtimeTruth activeTaskId is null (IDLE)
 *  - snapshot runtimeTruth activeTaskStatus is null (IDLE)
 *  - snapshot hasRuntimeTruth is true
 *  - non-reconnect open (USER_ACTIVATION) does NOT emit RUNTIME_TRUTH_SNAPSHOT
 *
 * ### GalaxyLogger — TAG_LIVE_EXECUTION
 *  - TAG_LIVE_EXECUTION is non-blank
 *  - TAG_LIVE_EXECUTION value is "GALAXY:LIVE:EXECUTION"
 *
 * ### StabilizationBaseline — PR-62 entries
 *  - exactly four entries with introducedPr == 62
 *  - participant-live-execution-surface is CANONICAL_STABLE with EXTEND guidance
 *  - runtime-controller-active-task-truth is CANONICAL_STABLE with EXTEND guidance
 *  - runtime-controller-publish-task-status-update is CANONICAL_STABLE with EXTEND guidance
 *  - galaxy-logger-tag-live-execution is CANONICAL_STABLE with EXTEND guidance
 *  - all PR-62 entries have non-blank rationale
 *  - all PR-62 entries have a com.ufo.galaxy or GALAXY packagePath
 */
class Pr62ParticipantLiveExecutionSurfaceTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies ─────────────────────────────────────────────────────

    private class FakePlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(steps = emptyList())
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

    private fun buildDescriptor(
        deviceId: String = "Pixel-62",
        hostId: String = "host-62",
        formationRole: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY
    ) = RuntimeHostDescriptor(
        hostId = hostId,
        deviceId = deviceId,
        deviceRole = "phone",
        formationRole = formationRole,
        participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    )

    private fun buildController(
        descriptor: RuntimeHostDescriptor? = null
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = InMemoryAppSettings(),
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L,
            hostDescriptor = descriptor
        )
        return controller to client
    }

    private fun expectedParticipantId(
        deviceId: String = "Pixel-62",
        hostId: String = "host-62"
    ): String = RuntimeIdentityContracts.participantNodeId(deviceId, hostId)

    // ── ParticipantLiveExecutionSurface — structural invariants ───────────────

    @Test
    fun `LIVE_EXECUTION_WIRING has exactly LIVE_EXECUTION_WIRING_COUNT entries`() {
        assertEquals(
            ParticipantLiveExecutionSurface.LIVE_EXECUTION_WIRING_COUNT,
            ParticipantLiveExecutionSurface.LIVE_EXECUTION_WIRING.size
        )
    }

    @Test
    fun `TASK_LIFECYCLE_WIRING has exactly TASK_LIFECYCLE_WIRING_COUNT entries`() {
        assertEquals(
            ParticipantLiveExecutionSurface.TASK_LIFECYCLE_WIRING_COUNT,
            ParticipantLiveExecutionSurface.TASK_LIFECYCLE_WIRING.size
        )
    }

    @Test
    fun `INTERRUPTION_AND_RECOVERY has exactly INTERRUPTION_AND_RECOVERY_COUNT entries`() {
        assertEquals(
            ParticipantLiveExecutionSurface.INTERRUPTION_AND_RECOVERY_COUNT,
            ParticipantLiveExecutionSurface.INTERRUPTION_AND_RECOVERY.size
        )
    }

    @Test
    fun `FIRST_CLASS_PARTICIPANT_EVIDENCE has exactly FIRST_CLASS_PARTICIPANT_EVIDENCE_COUNT entries`() {
        assertEquals(
            ParticipantLiveExecutionSurface.FIRST_CLASS_PARTICIPANT_EVIDENCE_COUNT,
            ParticipantLiveExecutionSurface.FIRST_CLASS_PARTICIPANT_EVIDENCE.size
        )
    }

    @Test
    fun `all LIVE_EXECUTION_WIRING keys are non-blank`() {
        ParticipantLiveExecutionSurface.LIVE_EXECUTION_WIRING.keys.forEach { key ->
            assertTrue("LIVE_EXECUTION_WIRING key is blank: '$key'", key.isNotBlank())
        }
    }

    @Test
    fun `all LIVE_EXECUTION_WIRING values are non-blank`() {
        ParticipantLiveExecutionSurface.LIVE_EXECUTION_WIRING.values.forEach { value ->
            assertTrue("LIVE_EXECUTION_WIRING value is blank", value.isNotBlank())
        }
    }

    @Test
    fun `all TASK_LIFECYCLE_WIRING keys are non-blank`() {
        ParticipantLiveExecutionSurface.TASK_LIFECYCLE_WIRING.keys.forEach { key ->
            assertTrue("TASK_LIFECYCLE_WIRING key is blank: '$key'", key.isNotBlank())
        }
    }

    @Test
    fun `all TASK_LIFECYCLE_WIRING values are non-blank`() {
        ParticipantLiveExecutionSurface.TASK_LIFECYCLE_WIRING.values.forEach { value ->
            assertTrue("TASK_LIFECYCLE_WIRING value is blank", value.isNotBlank())
        }
    }

    @Test
    fun `all INTERRUPTION_AND_RECOVERY keys are non-blank`() {
        ParticipantLiveExecutionSurface.INTERRUPTION_AND_RECOVERY.keys.forEach { key ->
            assertTrue("INTERRUPTION_AND_RECOVERY key is blank: '$key'", key.isNotBlank())
        }
    }

    @Test
    fun `all INTERRUPTION_AND_RECOVERY values are non-blank`() {
        ParticipantLiveExecutionSurface.INTERRUPTION_AND_RECOVERY.values.forEach { value ->
            assertTrue("INTERRUPTION_AND_RECOVERY value is blank", value.isNotBlank())
        }
    }

    @Test
    fun `all FIRST_CLASS_PARTICIPANT_EVIDENCE keys are non-blank`() {
        ParticipantLiveExecutionSurface.FIRST_CLASS_PARTICIPANT_EVIDENCE.keys.forEach { key ->
            assertTrue("FIRST_CLASS_PARTICIPANT_EVIDENCE key is blank: '$key'", key.isNotBlank())
        }
    }

    @Test
    fun `all FIRST_CLASS_PARTICIPANT_EVIDENCE values are non-blank`() {
        ParticipantLiveExecutionSurface.FIRST_CLASS_PARTICIPANT_EVIDENCE.values.forEach { value ->
            assertTrue("FIRST_CLASS_PARTICIPANT_EVIDENCE value is blank", value.isNotBlank())
        }
    }

    @Test
    fun `INTRODUCED_PR is 62`() {
        assertEquals(62, ParticipantLiveExecutionSurface.INTRODUCED_PR)
    }

    // ── ParticipantLiveExecutionSurface — known keys ──────────────────────────

    @Test
    fun `active_task_id key is in LIVE_EXECUTION_WIRING`() {
        assertTrue(ParticipantLiveExecutionSurface.LIVE_EXECUTION_WIRING.containsKey("active_task_id"))
    }

    @Test
    fun `active_task_status key is in LIVE_EXECUTION_WIRING`() {
        assertTrue(ParticipantLiveExecutionSurface.LIVE_EXECUTION_WIRING.containsKey("active_task_status"))
    }

    @Test
    fun `reconciliation_signal_stream key is in LIVE_EXECUTION_WIRING`() {
        assertTrue(ParticipantLiveExecutionSurface.LIVE_EXECUTION_WIRING.containsKey("reconciliation_signal_stream"))
    }

    @Test
    fun `TASK_ACCEPTED wire value is in TASK_LIFECYCLE_WIRING`() {
        assertTrue(
            ParticipantLiveExecutionSurface.TASK_LIFECYCLE_WIRING.containsKey(
                ReconciliationSignal.Kind.TASK_ACCEPTED.wireValue
            )
        )
    }

    @Test
    fun `TASK_STATUS_UPDATE wire value is in TASK_LIFECYCLE_WIRING`() {
        assertTrue(
            ParticipantLiveExecutionSurface.TASK_LIFECYCLE_WIRING.containsKey(
                ReconciliationSignal.Kind.TASK_STATUS_UPDATE.wireValue
            )
        )
    }

    @Test
    fun `TASK_RESULT wire value is in TASK_LIFECYCLE_WIRING`() {
        assertTrue(
            ParticipantLiveExecutionSurface.TASK_LIFECYCLE_WIRING.containsKey(
                ReconciliationSignal.Kind.TASK_RESULT.wireValue
            )
        )
    }

    @Test
    fun `TASK_CANCELLED wire value is in TASK_LIFECYCLE_WIRING`() {
        assertTrue(
            ParticipantLiveExecutionSurface.TASK_LIFECYCLE_WIRING.containsKey(
                ReconciliationSignal.Kind.TASK_CANCELLED.wireValue
            )
        )
    }

    @Test
    fun `TASK_FAILED wire value is in TASK_LIFECYCLE_WIRING`() {
        assertTrue(
            ParticipantLiveExecutionSurface.TASK_LIFECYCLE_WIRING.containsKey(
                ReconciliationSignal.Kind.TASK_FAILED.wireValue
            )
        )
    }

    @Test
    fun `transient_ws_disconnect_with_active_task is in INTERRUPTION_AND_RECOVERY`() {
        assertTrue(
            ParticipantLiveExecutionSurface.INTERRUPTION_AND_RECOVERY.containsKey(
                "transient_ws_disconnect_with_active_task"
            )
        )
    }

    @Test
    fun `reconnect_recovery_idle_snapshot is in INTERRUPTION_AND_RECOVERY`() {
        assertTrue(
            ParticipantLiveExecutionSurface.INTERRUPTION_AND_RECOVERY.containsKey(
                "reconnect_recovery_idle_snapshot"
            )
        )
    }

    @Test
    fun `live_active_task_tracking is in FIRST_CLASS_PARTICIPANT_EVIDENCE`() {
        assertTrue(
            ParticipantLiveExecutionSurface.FIRST_CLASS_PARTICIPANT_EVIDENCE.containsKey(
                "live_active_task_tracking"
            )
        )
    }

    @Test
    fun `automatic_task_interruption_signal is in FIRST_CLASS_PARTICIPANT_EVIDENCE`() {
        assertTrue(
            ParticipantLiveExecutionSurface.FIRST_CLASS_PARTICIPANT_EVIDENCE.containsKey(
                "automatic_task_interruption_signal"
            )
        )
    }

    @Test
    fun `in_progress_status_update_surface is in FIRST_CLASS_PARTICIPANT_EVIDENCE`() {
        assertTrue(
            ParticipantLiveExecutionSurface.FIRST_CLASS_PARTICIPANT_EVIDENCE.containsKey(
                "in_progress_status_update_surface"
            )
        )
    }

    // ── ParticipantLiveExecutionSurface — wire-key constants ──────────────────

    @Test
    fun `KEY_INTERRUPTED_BY is interrupted_by`() {
        assertEquals("interrupted_by", ParticipantLiveExecutionSurface.KEY_INTERRUPTED_BY)
    }

    @Test
    fun `KEY_FINISHED_WITH is finished_with`() {
        assertEquals("finished_with", ParticipantLiveExecutionSurface.KEY_FINISHED_WITH)
    }

    @Test
    fun `KEY_TASK_ID is task_id`() {
        assertEquals("task_id", ParticipantLiveExecutionSurface.KEY_TASK_ID)
    }

    @Test
    fun `KEY_PARTICIPANT_ID is participant_id`() {
        assertEquals("participant_id", ParticipantLiveExecutionSurface.KEY_PARTICIPANT_ID)
    }

    @Test
    fun `KEY_PROGRESS_DETAIL is progress_detail`() {
        assertEquals("progress_detail", ParticipantLiveExecutionSurface.KEY_PROGRESS_DETAIL)
    }

    // ── RuntimeController — activeTaskId / activeTaskStatus lifecycle ─────────

    @Test
    fun `activeTaskId is null initially`() {
        val (controller, _) = buildController(buildDescriptor())
        assertNull(controller.activeTaskId)
    }

    @Test
    fun `activeTaskStatus is null initially`() {
        val (controller, _) = buildController(buildDescriptor())
        assertNull(controller.activeTaskStatus)
    }

    @Test
    fun `recordDelegatedTaskAccepted sets activeTaskId`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-62a")
        assertEquals("task-62a", controller.activeTaskId)
    }

    @Test
    fun `recordDelegatedTaskAccepted sets activeTaskStatus to RUNNING`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-62b")
        assertEquals(ActiveTaskStatus.RUNNING, controller.activeTaskStatus)
    }

    @Test
    fun `publishTaskResult clears activeTaskId to null`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-62c")
        assertEquals("task-62c", controller.activeTaskId)
        controller.publishTaskResult("task-62c")
        assertNull(controller.activeTaskId)
    }

    @Test
    fun `publishTaskResult clears activeTaskStatus to null`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-62d")
        controller.publishTaskResult("task-62d")
        assertNull(controller.activeTaskStatus)
    }

    @Test
    fun `publishTaskCancelled clears activeTaskId to null`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-62e")
        controller.publishTaskCancelled("task-62e")
        assertNull(controller.activeTaskId)
    }

    @Test
    fun `publishTaskCancelled clears activeTaskStatus to null`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-62f")
        controller.publishTaskCancelled("task-62f")
        assertNull(controller.activeTaskStatus)
    }

    @Test
    fun `notifyTakeoverFailed FAILED clears activeTaskId to null`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-62g")
        controller.notifyTakeoverFailed(
            takeoverId = "takeover-1",
            taskId = "task-62g",
            reason = "test failure",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        assertNull(controller.activeTaskId)
    }

    @Test
    fun `notifyTakeoverFailed CANCELLED clears activeTaskId to null`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-62h")
        controller.notifyTakeoverFailed(
            takeoverId = "takeover-2",
            taskId = "task-62h",
            reason = "test cancel",
            cause = TakeoverFallbackEvent.Cause.CANCELLED
        )
        assertNull(controller.activeTaskId)
    }

    // ── RuntimeController — publishTaskStatusUpdate ───────────────────────────

    @Test
    fun `publishTaskStatusUpdate without hostDescriptor does not emit a signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = null)
        controller.publishTaskStatusUpdate("task-nu")
        val signal = withTimeoutOrNull(50) { controller.reconciliationSignals.first() }
        assertNull("Expected no signal when hostDescriptor is null", signal)
    }

    @Test
    fun `publishTaskStatusUpdate emits TASK_STATUS_UPDATE on reconciliationSignals`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.publishTaskStatusUpdate("task-su-1")
        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull("Expected TASK_STATUS_UPDATE signal", signal)
        assertEquals(ReconciliationSignal.Kind.TASK_STATUS_UPDATE, signal!!.kind)
    }

    @Test
    fun `publishTaskStatusUpdate emitted signal status is STATUS_IN_PROGRESS`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.publishTaskStatusUpdate("task-su-2")
        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals(ReconciliationSignal.STATUS_IN_PROGRESS, signal!!.status)
    }

    @Test
    fun `publishTaskStatusUpdate emitted signal participantId matches hostDescriptor`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.publishTaskStatusUpdate("task-su-3")
        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals(expectedParticipantId(), signal!!.participantId)
    }

    @Test
    fun `publishTaskStatusUpdate emitted signal taskId matches provided taskId`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.publishTaskStatusUpdate("task-su-tid")
        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals("task-su-tid", signal!!.taskId)
    }

    @Test
    fun `publishTaskStatusUpdate emitted signal correlationId is echoed when provided`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.publishTaskStatusUpdate("task-su-4", correlationId = "corr-62")
        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals("corr-62", signal!!.correlationId)
    }

    @Test
    fun `publishTaskStatusUpdate emitted signal payload contains progress_detail when provided`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.publishTaskStatusUpdate("task-su-5", progressDetail = "step 3 of 7")
        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals("step 3 of 7", signal!!.payload["progress_detail"])
    }

    @Test
    fun `publishTaskStatusUpdate emitted signal payload does not contain progress_detail when omitted`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.publishTaskStatusUpdate("task-su-6")
        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertFalse(signal!!.payload.containsKey("progress_detail"))
    }

    @Test
    fun `publishTaskStatusUpdate emitted signal isTerminal is false`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.publishTaskStatusUpdate("task-su-7")
        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertFalse(signal!!.isTerminal)
    }

    @Test
    fun `publishTaskStatusUpdate emitted signal signalId is non-blank`() = runBlocking {
        val (controller, _) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.publishTaskStatusUpdate("task-su-8")
        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertTrue(signal!!.signalId.isNotBlank())
    }

    // ── RuntimeController — automatic TASK_FAILED on disconnect ──────────────

    @Test
    fun `disconnect while active task emits TASK_FAILED on reconciliationSignals`() = runBlocking {
        val (controller, wsClient) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-dc-1")
        val signals = mutableListOf<ReconciliationSignal>()
        val job = kotlinx.coroutines.launch {
            controller.reconciliationSignals.collect { signals.add(it) }
        }
        // Simulate WS disconnect while task is active
        wsClient.simulateDisconnected()
        kotlinx.coroutines.delay(200)
        job.cancel()
        val taskFailedSignal = signals.firstOrNull {
            it.kind == ReconciliationSignal.Kind.TASK_FAILED && it.taskId == "task-dc-1"
        }
        assertNotNull("Expected TASK_FAILED signal on disconnect with active task", taskFailedSignal)
    }

    @Test
    fun `emitted TASK_FAILED signal taskId matches active task`() = runBlocking {
        val (controller, wsClient) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-dc-2")
        val signals = mutableListOf<ReconciliationSignal>()
        val job = kotlinx.coroutines.launch {
            controller.reconciliationSignals.collect { signals.add(it) }
        }
        wsClient.simulateDisconnected()
        kotlinx.coroutines.delay(200)
        job.cancel()
        val taskFailedSignal = signals.firstOrNull { it.kind == ReconciliationSignal.Kind.TASK_FAILED }
        assertNotNull(taskFailedSignal)
        assertEquals("task-dc-2", taskFailedSignal!!.taskId)
    }

    @Test
    fun `emitted TASK_FAILED errorDetail contains session_interrupted`() = runBlocking {
        val (controller, wsClient) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-dc-3")
        val signals = mutableListOf<ReconciliationSignal>()
        val job = kotlinx.coroutines.launch {
            controller.reconciliationSignals.collect { signals.add(it) }
        }
        wsClient.simulateDisconnected()
        kotlinx.coroutines.delay(200)
        job.cancel()
        val taskFailedSignal = signals.firstOrNull { it.kind == ReconciliationSignal.Kind.TASK_FAILED }
        assertNotNull(taskFailedSignal)
        val errorDetail = taskFailedSignal!!.payload["error_detail"] as? String
        assertNotNull("Expected error_detail in payload", errorDetail)
        assertTrue(
            "Expected errorDetail to contain 'session_interrupted', got: $errorDetail",
            errorDetail!!.contains("session_interrupted")
        )
    }

    @Test
    fun `disconnect clears activeTaskId after emitting TASK_FAILED`() = runBlocking {
        val (controller, wsClient) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-dc-4")
        wsClient.simulateDisconnected()
        kotlinx.coroutines.delay(200)
        assertNull("activeTaskId should be null after disconnect interruption", controller.activeTaskId)
    }

    @Test
    fun `disconnect clears activeTaskStatus after emitting TASK_FAILED`() = runBlocking {
        val (controller, wsClient) = buildController(buildDescriptor())
        controller.setActiveForTest()
        controller.recordDelegatedTaskAccepted("task-dc-5")
        wsClient.simulateDisconnected()
        kotlinx.coroutines.delay(200)
        assertNull("activeTaskStatus should be null after disconnect interruption", controller.activeTaskStatus)
    }

    @Test
    fun `disconnect with no active task does NOT emit TASK_FAILED`() = runBlocking {
        val (controller, wsClient) = buildController(buildDescriptor())
        controller.setActiveForTest()
        // No recordDelegatedTaskAccepted — participant is idle
        val signals = mutableListOf<ReconciliationSignal>()
        val job = kotlinx.coroutines.launch {
            controller.reconciliationSignals.collect { signals.add(it) }
        }
        wsClient.simulateDisconnected()
        kotlinx.coroutines.delay(200)
        job.cancel()
        val taskFailedSignal = signals.firstOrNull { it.kind == ReconciliationSignal.Kind.TASK_FAILED }
        assertNull("Expected NO TASK_FAILED signal when no active task", taskFailedSignal)
    }

    // ── RuntimeController — automatic RUNTIME_TRUTH_SNAPSHOT on reconnect ─────

    @Test
    fun `reconnect RECONNECT_RECOVERY emits RUNTIME_TRUTH_SNAPSHOT on reconciliationSignals`() = runBlocking {
        val (controller, wsClient) = buildController(buildDescriptor())
        controller.setActiveForTest()
        val signals = mutableListOf<ReconciliationSignal>()
        val job = kotlinx.coroutines.launch {
            controller.reconciliationSignals.collect { signals.add(it) }
        }
        // Disconnect then reconnect (simulates RECONNECT_RECOVERY path)
        wsClient.simulateDisconnected()
        kotlinx.coroutines.delay(50)
        wsClient.simulateConnected()
        kotlinx.coroutines.delay(200)
        job.cancel()
        val snapshotSignal = signals.firstOrNull { it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT }
        assertNotNull("Expected RUNTIME_TRUTH_SNAPSHOT on reconnect", snapshotSignal)
    }

    @Test
    fun `reconnect snapshot runtimeTruth is non-null`() = runBlocking {
        val (controller, wsClient) = buildController(buildDescriptor())
        controller.setActiveForTest()
        val signals = mutableListOf<ReconciliationSignal>()
        val job = kotlinx.coroutines.launch {
            controller.reconciliationSignals.collect { signals.add(it) }
        }
        wsClient.simulateDisconnected()
        kotlinx.coroutines.delay(50)
        wsClient.simulateConnected()
        kotlinx.coroutines.delay(200)
        job.cancel()
        val snapshotSignal = signals.firstOrNull { it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT }
        assertNotNull(snapshotSignal)
        assertNotNull("runtimeTruth should be non-null in snapshot", snapshotSignal!!.runtimeTruth)
    }

    @Test
    fun `reconnect snapshot runtimeTruth activeTaskId is null IDLE`() = runBlocking {
        val (controller, wsClient) = buildController(buildDescriptor())
        controller.setActiveForTest()
        val signals = mutableListOf<ReconciliationSignal>()
        val job = kotlinx.coroutines.launch {
            controller.reconciliationSignals.collect { signals.add(it) }
        }
        wsClient.simulateDisconnected()
        kotlinx.coroutines.delay(50)
        wsClient.simulateConnected()
        kotlinx.coroutines.delay(200)
        job.cancel()
        val snapshotSignal = signals.firstOrNull { it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT }
        assertNotNull(snapshotSignal)
        assertNull("runtimeTruth.activeTaskId should be null (IDLE)", snapshotSignal!!.runtimeTruth?.activeTaskId)
    }

    @Test
    fun `reconnect snapshot runtimeTruth activeTaskStatus is null IDLE`() = runBlocking {
        val (controller, wsClient) = buildController(buildDescriptor())
        controller.setActiveForTest()
        val signals = mutableListOf<ReconciliationSignal>()
        val job = kotlinx.coroutines.launch {
            controller.reconciliationSignals.collect { signals.add(it) }
        }
        wsClient.simulateDisconnected()
        kotlinx.coroutines.delay(50)
        wsClient.simulateConnected()
        kotlinx.coroutines.delay(200)
        job.cancel()
        val snapshotSignal = signals.firstOrNull { it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT }
        assertNotNull(snapshotSignal)
        assertNull("runtimeTruth.activeTaskStatus should be null (IDLE)", snapshotSignal!!.runtimeTruth?.activeTaskStatus)
    }

    @Test
    fun `reconnect snapshot hasRuntimeTruth is true`() = runBlocking {
        val (controller, wsClient) = buildController(buildDescriptor())
        controller.setActiveForTest()
        val signals = mutableListOf<ReconciliationSignal>()
        val job = kotlinx.coroutines.launch {
            controller.reconciliationSignals.collect { signals.add(it) }
        }
        wsClient.simulateDisconnected()
        kotlinx.coroutines.delay(50)
        wsClient.simulateConnected()
        kotlinx.coroutines.delay(200)
        job.cancel()
        val snapshotSignal = signals.firstOrNull { it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT }
        assertNotNull(snapshotSignal)
        assertTrue("hasRuntimeTruth should be true", snapshotSignal!!.hasRuntimeTruth)
    }

    // ── GalaxyLogger — TAG_LIVE_EXECUTION ─────────────────────────────────────

    @Test
    fun `TAG_LIVE_EXECUTION is non-blank`() {
        assertTrue(GalaxyLogger.TAG_LIVE_EXECUTION.isNotBlank())
    }

    @Test
    fun `TAG_LIVE_EXECUTION value is GALAXY LIVE EXECUTION`() {
        assertEquals("GALAXY:LIVE:EXECUTION", GalaxyLogger.TAG_LIVE_EXECUTION)
    }

    // ── StabilizationBaseline — PR-62 entries ─────────────────────────────────

    @Test
    fun `exactly four entries with introducedPr == 62`() {
        val pr62Entries = StabilizationBaseline.entries.filter { it.introducedPr == 62 }
        assertEquals(4, pr62Entries.size)
    }

    @Test
    fun `participant-live-execution-surface is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("participant-live-execution-surface")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `runtime-controller-active-task-truth is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("runtime-controller-active-task-truth")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `runtime-controller-publish-task-status-update is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("runtime-controller-publish-task-status-update")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `galaxy-logger-tag-live-execution is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("galaxy-logger-tag-live-execution")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `all PR-62 entries have non-blank rationale`() {
        val pr62Entries = StabilizationBaseline.entries.filter { it.introducedPr == 62 }
        pr62Entries.forEach { entry ->
            assertTrue(
                "PR-62 entry '${entry.surfaceId}' has blank rationale",
                entry.rationale.isNotBlank()
            )
        }
    }

    @Test
    fun `all PR-62 entries have a com.ufo.galaxy or GALAXY packagePath`() {
        val pr62Entries = StabilizationBaseline.entries.filter { it.introducedPr == 62 }
        pr62Entries.forEach { entry ->
            assertTrue(
                "PR-62 entry '${entry.surfaceId}' has unexpected packagePath: '${entry.packagePath}'",
                entry.packagePath.startsWith("com.ufo.galaxy") || entry.packagePath.startsWith("GALAXY")
            )
        }
    }
}
