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
 * PR-52 — Reconciliation Signal Emission: Factory Helpers and RuntimeController Integration.
 *
 * Acceptance and regression test suite covering all PR-52 additions:
 *
 *  1. [ReconciliationSignal.taskStatusUpdate] factory helper — creates TASK_STATUS_UPDATE
 *     signals with STATUS_IN_PROGRESS status and optional progressDetail payload.
 *
 *  2. [ReconciliationSignal.participantStateSignal] factory helper — creates PARTICIPANT_STATE
 *     signals with STATUS_STATE_CHANGED, carrying healthState/readinessState/posture in payload.
 *
 *  3. [RuntimeController.reconciliationSignals] — canonical Android→V2 reconciliation signal
 *     SharedFlow; non-null, buffered, observable from any coroutine scope.
 *
 *  4. [RuntimeController.recordDelegatedTaskAccepted] — session counter + TASK_ACCEPTED emission.
 *
 *  5. [RuntimeController.publishTaskResult] — TASK_RESULT emission.
 *
 *  6. [RuntimeController.publishTaskCancelled] — TASK_CANCELLED emission.
 *
 *  7. [RuntimeController.publishRuntimeTruthSnapshot] — RUNTIME_TRUTH_SNAPSHOT emission.
 *
 *  8. [RuntimeController.notifyTakeoverFailed] — also emits TASK_FAILED or TASK_CANCELLED
 *     (based on [TakeoverFallbackEvent.Cause]) on [reconciliationSignals].
 *
 *  9. [RuntimeController.notifyParticipantHealthChanged] — also emits PARTICIPANT_STATE on
 *     [reconciliationSignals].
 *
 * 10. [StabilizationBaseline] — exactly three PR-52 entries registered.
 *
 * ## Test matrix
 *
 * ### ReconciliationSignal.taskStatusUpdate — factory helper
 *  - produces Kind.TASK_STATUS_UPDATE
 *  - status is STATUS_IN_PROGRESS
 *  - participantId and taskId are set
 *  - progressDetail is in payload when provided
 *  - progressDetail absent from payload when omitted
 *  - correlationId is set when provided
 *  - signalId is non-blank
 *  - emittedAtMs is positive
 *  - reconciliationEpoch is echoed
 *  - isTerminal is false
 *
 * ### ReconciliationSignal.participantStateSignal — factory helper
 *  - produces Kind.PARTICIPANT_STATE
 *  - status is STATUS_STATE_CHANGED
 *  - participantId is set
 *  - taskId is null
 *  - payload contains health_state
 *  - payload contains readiness_state
 *  - payload contains source_runtime_posture when provided
 *  - posture absent from payload when not provided
 *  - isTerminal is false
 *  - hasRuntimeTruth is false
 *  - signalId is non-blank
 *  - reconciliationEpoch is echoed
 *
 * ### RuntimeController.reconciliationSignals — flow contract
 *  - reconciliationSignals is non-null
 *  - recordDelegatedTaskAccepted without hostDescriptor does not emit a signal
 *  - publishTaskResult without hostDescriptor does not emit a signal
 *  - publishTaskCancelled without hostDescriptor does not emit a signal
 *  - publishRuntimeTruthSnapshot without hostDescriptor does not emit a signal
 *
 * ### RuntimeController — recordDelegatedTaskAccepted
 *  - emits TASK_ACCEPTED on reconciliationSignals
 *  - emitted signal participantId matches hostDescriptor identity
 *  - emitted signal taskId matches provided taskId
 *  - emitted signal status is STATUS_RUNNING
 *  - correlationId is echoed on signal when provided
 *  - session execution counter is incremented
 *
 * ### RuntimeController — publishTaskResult
 *  - emits TASK_RESULT on reconciliationSignals
 *  - emitted signal participantId matches hostDescriptor identity
 *  - emitted signal taskId matches provided taskId
 *  - emitted signal status is STATUS_SUCCESS
 *
 * ### RuntimeController — publishTaskCancelled
 *  - emits TASK_CANCELLED on reconciliationSignals
 *  - emitted signal participantId matches hostDescriptor identity
 *  - emitted signal status is STATUS_CANCELLED
 *
 * ### RuntimeController — publishRuntimeTruthSnapshot
 *  - emits RUNTIME_TRUTH_SNAPSHOT on reconciliationSignals
 *  - emitted signal has non-null runtimeTruth
 *  - emitted signal hasRuntimeTruth is true
 *  - runtimeTruth participantId matches hostDescriptor identity
 *  - runtimeTruth healthState matches provided healthState
 *  - runtimeTruth readinessState matches provided readinessState
 *
 * ### RuntimeController — notifyTakeoverFailed reconciliation signal
 *  - FAILED cause emits TASK_FAILED signal
 *  - TIMEOUT cause emits TASK_FAILED signal
 *  - CANCELLED cause emits TASK_CANCELLED signal
 *  - DISCONNECT cause emits TASK_FAILED signal
 *  - emitted signal taskId matches provided taskId
 *  - no signal emitted without hostDescriptor
 *  - duplicate notifyTakeoverFailed (same takeoverId) does not emit second signal
 *
 * ### RuntimeController — notifyParticipantHealthChanged reconciliation signal
 *  - DEGRADED health emits PARTICIPANT_STATE signal
 *  - HEALTHY health emits PARTICIPANT_STATE signal
 *  - emitted signal payload contains health_state
 *  - no signal emitted without hostDescriptor
 *
 * ### StabilizationBaseline — PR-52 entries
 *  - exactly three entries with introducedPr == 52
 *  - reconciliation-signal-factories is CANONICAL_STABLE with EXTEND guidance
 *  - runtime-controller-reconciliation-signals is CANONICAL_STABLE with EXTEND guidance
 *  - runtime-controller-publish-truth-snapshot is CANONICAL_STABLE with EXTEND guidance
 *  - all PR-52 entries have non-blank rationale
 *  - all PR-52 entries reference com.ufo.galaxy packagePath
 */
class Pr52ReconciliationSignalEmissionTest {

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
        deviceId: String = "Pixel-52",
        hostId: String = "host-52",
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

    /**
     * Returns the expected participantId for the test descriptor.
     */
    private fun expectedParticipantId(
        deviceId: String = "Pixel-52",
        hostId: String = "host-52"
    ): String = RuntimeIdentityContracts.participantNodeId(deviceId, hostId)

    // ── ReconciliationSignal.taskStatusUpdate — factory helper ────────────────

    @Test
    fun `taskStatusUpdate produces Kind TASK_STATUS_UPDATE`() {
        val signal = ReconciliationSignal.taskStatusUpdate("pid", "tid")
        assertEquals(ReconciliationSignal.Kind.TASK_STATUS_UPDATE, signal.kind)
    }

    @Test
    fun `taskStatusUpdate status is STATUS_IN_PROGRESS`() {
        val signal = ReconciliationSignal.taskStatusUpdate("pid", "tid")
        assertEquals(ReconciliationSignal.STATUS_IN_PROGRESS, signal.status)
    }

    @Test
    fun `taskStatusUpdate participantId and taskId are set`() {
        val signal = ReconciliationSignal.taskStatusUpdate("my-pid", "my-tid")
        assertEquals("my-pid", signal.participantId)
        assertEquals("my-tid", signal.taskId)
    }

    @Test
    fun `taskStatusUpdate progressDetail is in payload when provided`() {
        val signal = ReconciliationSignal.taskStatusUpdate("pid", "tid", progressDetail = "step_3_of_5")
        assertEquals("step_3_of_5", signal.payload["progress_detail"])
    }

    @Test
    fun `taskStatusUpdate progressDetail absent from payload when omitted`() {
        val signal = ReconciliationSignal.taskStatusUpdate("pid", "tid")
        assertFalse(signal.payload.containsKey("progress_detail"))
    }

    @Test
    fun `taskStatusUpdate correlationId is set when provided`() {
        val signal = ReconciliationSignal.taskStatusUpdate("pid", "tid", correlationId = "corr-1")
        assertEquals("corr-1", signal.correlationId)
    }

    @Test
    fun `taskStatusUpdate signalId is non-blank`() {
        val signal = ReconciliationSignal.taskStatusUpdate("pid", "tid")
        assertTrue(signal.signalId.isNotBlank())
    }

    @Test
    fun `taskStatusUpdate emittedAtMs is positive`() {
        val signal = ReconciliationSignal.taskStatusUpdate("pid", "tid")
        assertTrue(signal.emittedAtMs > 0L)
    }

    @Test
    fun `taskStatusUpdate reconciliationEpoch is echoed`() {
        val signal = ReconciliationSignal.taskStatusUpdate("pid", "tid", reconciliationEpoch = 7)
        assertEquals(7, signal.reconciliationEpoch)
    }

    @Test
    fun `taskStatusUpdate isTerminal is false`() {
        val signal = ReconciliationSignal.taskStatusUpdate("pid", "tid")
        assertFalse(signal.isTerminal)
    }

    // ── ReconciliationSignal.participantStateSignal — factory helper ──────────

    @Test
    fun `participantStateSignal produces Kind PARTICIPANT_STATE`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "pid",
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY
        )
        assertEquals(ReconciliationSignal.Kind.PARTICIPANT_STATE, signal.kind)
    }

    @Test
    fun `participantStateSignal status is STATUS_STATE_CHANGED`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "pid",
            healthState = ParticipantHealthState.DEGRADED,
            readinessState = ParticipantReadinessState.NOT_READY
        )
        assertEquals(ReconciliationSignal.STATUS_STATE_CHANGED, signal.status)
    }

    @Test
    fun `participantStateSignal participantId is set`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "my-pid",
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY
        )
        assertEquals("my-pid", signal.participantId)
    }

    @Test
    fun `participantStateSignal taskId is null`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "pid",
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY
        )
        assertNull(signal.taskId)
    }

    @Test
    fun `participantStateSignal payload contains health_state`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "pid",
            healthState = ParticipantHealthState.DEGRADED,
            readinessState = ParticipantReadinessState.READY
        )
        assertEquals(ParticipantHealthState.DEGRADED.wireValue, signal.payload["health_state"])
    }

    @Test
    fun `participantStateSignal payload contains readiness_state`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "pid",
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY_WITH_FALLBACK
        )
        assertEquals(ParticipantReadinessState.READY_WITH_FALLBACK.wireValue, signal.payload["readiness_state"])
    }

    @Test
    fun `participantStateSignal payload contains source_runtime_posture when provided`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "pid",
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, signal.payload["source_runtime_posture"])
    }

    @Test
    fun `participantStateSignal posture absent from payload when not provided`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "pid",
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY
        )
        assertFalse(signal.payload.containsKey("source_runtime_posture"))
    }

    @Test
    fun `participantStateSignal isTerminal is false`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "pid",
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY
        )
        assertFalse(signal.isTerminal)
    }

    @Test
    fun `participantStateSignal hasRuntimeTruth is false`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "pid",
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY
        )
        assertFalse(signal.hasRuntimeTruth)
    }

    @Test
    fun `participantStateSignal signalId is non-blank`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "pid",
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY
        )
        assertTrue(signal.signalId.isNotBlank())
    }

    @Test
    fun `participantStateSignal reconciliationEpoch is echoed`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "pid",
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            reconciliationEpoch = 12
        )
        assertEquals(12, signal.reconciliationEpoch)
    }

    // ── RuntimeController.reconciliationSignals — flow contract ───────────────

    @Test
    fun `reconciliationSignals is non-null`() {
        val (controller, _) = buildController()
        assertNotNull(controller.reconciliationSignals)
    }

    @Test
    fun `recordDelegatedTaskAccepted without hostDescriptor does not emit a signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = null)
        controller.recordDelegatedTaskAccepted("task-1")
        val signal = withTimeoutOrNull(50) { controller.reconciliationSignals.first() }
        assertNull("Expected no reconciliation signal when hostDescriptor is null", signal)
    }

    @Test
    fun `publishTaskResult without hostDescriptor does not emit a signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = null)
        controller.publishTaskResult("task-1")
        val signal = withTimeoutOrNull(50) { controller.reconciliationSignals.first() }
        assertNull("Expected no reconciliation signal when hostDescriptor is null", signal)
    }

    @Test
    fun `publishTaskCancelled without hostDescriptor does not emit a signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = null)
        controller.publishTaskCancelled("task-1")
        val signal = withTimeoutOrNull(50) { controller.reconciliationSignals.first() }
        assertNull("Expected no reconciliation signal when hostDescriptor is null", signal)
    }

    @Test
    fun `publishRuntimeTruthSnapshot without hostDescriptor does not emit a signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = null)
        controller.publishRuntimeTruthSnapshot()
        val signal = withTimeoutOrNull(50) { controller.reconciliationSignals.first() }
        assertNull("Expected no reconciliation signal when hostDescriptor is null", signal)
    }

    // ── RuntimeController — recordDelegatedTaskAccepted ───────────────────────

    @Test
    fun `recordDelegatedTaskAccepted emits TASK_ACCEPTED on reconciliationSignals`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())
        controller.openTestSession()

        controller.recordDelegatedTaskAccepted("task-abc")

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull("Expected TASK_ACCEPTED signal on reconciliationSignals", signal)
        assertEquals(ReconciliationSignal.Kind.TASK_ACCEPTED, signal!!.kind)
    }

    @Test
    fun `recordDelegatedTaskAccepted emitted signal participantId matches hostDescriptor`() = runBlocking {
        val descriptor = buildDescriptor()
        val (controller, _) = buildController(descriptor = descriptor)
        controller.openTestSession()

        controller.recordDelegatedTaskAccepted("task-pid-check")

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals(expectedParticipantId(), signal!!.participantId)
    }

    @Test
    fun `recordDelegatedTaskAccepted emitted signal taskId matches provided taskId`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())
        controller.openTestSession()

        controller.recordDelegatedTaskAccepted("task-xyz")

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals("task-xyz", signal!!.taskId)
    }

    @Test
    fun `recordDelegatedTaskAccepted emitted signal status is STATUS_RUNNING`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())
        controller.openTestSession()

        controller.recordDelegatedTaskAccepted("task-status")

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals(ReconciliationSignal.STATUS_RUNNING, signal!!.status)
    }

    @Test
    fun `recordDelegatedTaskAccepted correlationId is echoed when provided`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())
        controller.openTestSession()

        controller.recordDelegatedTaskAccepted("task-corr", correlationId = "corr-XYZ")

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals("corr-XYZ", signal!!.correlationId)
    }

    @Test
    fun `recordDelegatedTaskAccepted increments session execution counter`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())
        controller.openTestSession()

        val before = controller.attachedSession.value?.delegatedExecutionCount ?: 0
        controller.recordDelegatedTaskAccepted("task-counter")

        val after = controller.attachedSession.value?.delegatedExecutionCount ?: 0
        assertEquals("Execution count must have incremented by 1", before + 1, after)
    }

    // ── RuntimeController — publishTaskResult ─────────────────────────────────

    @Test
    fun `publishTaskResult emits TASK_RESULT on reconciliationSignals`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.publishTaskResult("task-result-1")

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull("Expected TASK_RESULT signal on reconciliationSignals", signal)
        assertEquals(ReconciliationSignal.Kind.TASK_RESULT, signal!!.kind)
    }

    @Test
    fun `publishTaskResult emitted signal participantId matches hostDescriptor`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.publishTaskResult("task-result-pid")

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals(expectedParticipantId(), signal!!.participantId)
    }

    @Test
    fun `publishTaskResult emitted signal taskId matches provided taskId`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.publishTaskResult("task-result-2")

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals("task-result-2", signal!!.taskId)
    }

    @Test
    fun `publishTaskResult emitted signal status is STATUS_SUCCESS`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.publishTaskResult("task-result-status")

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals(ReconciliationSignal.STATUS_SUCCESS, signal!!.status)
    }

    // ── RuntimeController — publishTaskCancelled ──────────────────────────────

    @Test
    fun `publishTaskCancelled emits TASK_CANCELLED on reconciliationSignals`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.publishTaskCancelled("task-cancel-1")

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull("Expected TASK_CANCELLED signal on reconciliationSignals", signal)
        assertEquals(ReconciliationSignal.Kind.TASK_CANCELLED, signal!!.kind)
    }

    @Test
    fun `publishTaskCancelled emitted signal participantId matches hostDescriptor`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.publishTaskCancelled("task-cancel-pid")

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals(expectedParticipantId(), signal!!.participantId)
    }

    @Test
    fun `publishTaskCancelled emitted signal status is STATUS_CANCELLED`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.publishTaskCancelled("task-cancel-status")

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals(ReconciliationSignal.STATUS_CANCELLED, signal!!.status)
    }

    // ── RuntimeController — publishRuntimeTruthSnapshot ──────────────────────

    @Test
    fun `publishRuntimeTruthSnapshot emits RUNTIME_TRUTH_SNAPSHOT on reconciliationSignals`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.publishRuntimeTruthSnapshot(
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY
        )

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull("Expected RUNTIME_TRUTH_SNAPSHOT signal on reconciliationSignals", signal)
        assertEquals(ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT, signal!!.kind)
    }

    @Test
    fun `publishRuntimeTruthSnapshot emitted signal has non-null runtimeTruth`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.publishRuntimeTruthSnapshot(ParticipantHealthState.HEALTHY)

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertNotNull("runtimeTruth must be non-null for RUNTIME_TRUTH_SNAPSHOT", signal!!.runtimeTruth)
    }

    @Test
    fun `publishRuntimeTruthSnapshot emitted signal hasRuntimeTruth is true`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.publishRuntimeTruthSnapshot(ParticipantHealthState.HEALTHY)

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertTrue("hasRuntimeTruth must be true for RUNTIME_TRUTH_SNAPSHOT", signal!!.hasRuntimeTruth)
    }

    @Test
    fun `publishRuntimeTruthSnapshot runtimeTruth participantId matches hostDescriptor`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.publishRuntimeTruthSnapshot(ParticipantHealthState.HEALTHY)

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals(expectedParticipantId(), signal!!.runtimeTruth!!.participantId)
    }

    @Test
    fun `publishRuntimeTruthSnapshot runtimeTruth healthState matches provided healthState`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.publishRuntimeTruthSnapshot(
            healthState = ParticipantHealthState.DEGRADED,
            readinessState = ParticipantReadinessState.READY
        )

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals(ParticipantHealthState.DEGRADED, signal!!.runtimeTruth!!.healthState)
    }

    @Test
    fun `publishRuntimeTruthSnapshot runtimeTruth readinessState matches provided readinessState`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.publishRuntimeTruthSnapshot(
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY_WITH_FALLBACK
        )

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals(ParticipantReadinessState.READY_WITH_FALLBACK, signal!!.runtimeTruth!!.readinessState)
    }

    // ── RuntimeController — notifyTakeoverFailed reconciliation signal ─────────

    @Test
    fun `notifyTakeoverFailed with FAILED cause emits TASK_FAILED signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.notifyTakeoverFailed(
            takeoverId = "to-1",
            taskId = "task-fail-1",
            traceId = "trace-1",
            reason = "pipeline exception",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull("Expected TASK_FAILED signal for FAILED cause", signal)
        assertEquals(ReconciliationSignal.Kind.TASK_FAILED, signal!!.kind)
    }

    @Test
    fun `notifyTakeoverFailed with TIMEOUT cause emits TASK_FAILED signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.notifyTakeoverFailed(
            takeoverId = "to-2",
            taskId = "task-timeout-1",
            traceId = "trace-2",
            reason = "timed out",
            cause = TakeoverFallbackEvent.Cause.TIMEOUT
        )

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull("Expected TASK_FAILED signal for TIMEOUT cause", signal)
        assertEquals(ReconciliationSignal.Kind.TASK_FAILED, signal!!.kind)
    }

    @Test
    fun `notifyTakeoverFailed with CANCELLED cause emits TASK_CANCELLED signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.notifyTakeoverFailed(
            takeoverId = "to-3",
            taskId = "task-cancel-1",
            traceId = "trace-3",
            reason = "user cancelled",
            cause = TakeoverFallbackEvent.Cause.CANCELLED
        )

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull("Expected TASK_CANCELLED signal for CANCELLED cause", signal)
        assertEquals(ReconciliationSignal.Kind.TASK_CANCELLED, signal!!.kind)
    }

    @Test
    fun `notifyTakeoverFailed with DISCONNECT cause emits TASK_FAILED signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.notifyTakeoverFailed(
            takeoverId = "to-4",
            taskId = "task-disconnect-1",
            traceId = "trace-4",
            reason = "ws disconnected",
            cause = TakeoverFallbackEvent.Cause.DISCONNECT
        )

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull("Expected TASK_FAILED signal for DISCONNECT cause", signal)
        assertEquals(ReconciliationSignal.Kind.TASK_FAILED, signal!!.kind)
    }

    @Test
    fun `notifyTakeoverFailed emitted signal taskId matches provided taskId`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.notifyTakeoverFailed(
            takeoverId = "to-5",
            taskId = "task-id-check",
            traceId = "trace-5",
            reason = "error",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals("task-id-check", signal!!.taskId)
    }

    @Test
    fun `notifyTakeoverFailed without hostDescriptor does not emit reconciliation signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = null)

        controller.notifyTakeoverFailed(
            takeoverId = "to-6",
            taskId = "task-no-descriptor",
            traceId = "trace-6",
            reason = "error",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )

        val signal = withTimeoutOrNull(50) { controller.reconciliationSignals.first() }
        assertNull("Expected no reconciliation signal when hostDescriptor is null", signal)
    }

    @Test
    fun `notifyTakeoverFailed duplicate does not emit second reconciliation signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        // First call — should emit a signal
        controller.notifyTakeoverFailed(
            takeoverId = "to-dup",
            taskId = "task-dup",
            traceId = "trace-dup",
            reason = "error",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        // Drain the first signal
        withTimeoutOrNull(200) { controller.reconciliationSignals.first() }

        // Second call with same takeoverId — deduplication guard should suppress it
        controller.notifyTakeoverFailed(
            takeoverId = "to-dup",
            taskId = "task-dup",
            traceId = "trace-dup",
            reason = "error again",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        val secondSignal = withTimeoutOrNull(50) { controller.reconciliationSignals.first() }
        assertNull("Duplicate notifyTakeoverFailed must not emit a second signal", secondSignal)
    }

    // ── RuntimeController — notifyParticipantHealthChanged reconciliation signal

    @Test
    fun `notifyParticipantHealthChanged with DEGRADED emits PARTICIPANT_STATE signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.notifyParticipantHealthChanged(ParticipantHealthState.DEGRADED)

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull("Expected PARTICIPANT_STATE signal for DEGRADED health", signal)
        assertEquals(ReconciliationSignal.Kind.PARTICIPANT_STATE, signal!!.kind)
    }

    @Test
    fun `notifyParticipantHealthChanged with HEALTHY emits PARTICIPANT_STATE signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.notifyParticipantHealthChanged(ParticipantHealthState.HEALTHY)

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull("Expected PARTICIPANT_STATE signal for HEALTHY health", signal)
        assertEquals(ReconciliationSignal.Kind.PARTICIPANT_STATE, signal!!.kind)
    }

    @Test
    fun `notifyParticipantHealthChanged emitted signal payload contains health_state`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())

        controller.notifyParticipantHealthChanged(ParticipantHealthState.RECOVERING)

        val signal = withTimeoutOrNull(200) { controller.reconciliationSignals.first() }
        assertNotNull(signal)
        assertEquals(
            ParticipantHealthState.RECOVERING.wireValue,
            signal!!.payload["health_state"]
        )
    }

    @Test
    fun `notifyParticipantHealthChanged without hostDescriptor does not emit reconciliation signal`() = runBlocking {
        val (controller, _) = buildController(descriptor = null)

        controller.notifyParticipantHealthChanged(ParticipantHealthState.DEGRADED)

        val signal = withTimeoutOrNull(50) { controller.reconciliationSignals.first() }
        assertNull("Expected no reconciliation signal when hostDescriptor is null", signal)
    }

    // ── StabilizationBaseline — PR-52 entries ─────────────────────────────────

    @Test
    fun `StabilizationBaseline has exactly three entries with introducedPr == 52`() {
        val pr52Entries = StabilizationBaseline.entries.filter { it.introducedPr == 52 }
        assertEquals(3, pr52Entries.size)
    }

    @Test
    fun `reconciliation-signal-factories is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("reconciliation-signal-factories")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `runtime-controller-reconciliation-signals is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("runtime-controller-reconciliation-signals")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `runtime-controller-publish-truth-snapshot is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("runtime-controller-publish-truth-snapshot")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `all PR-52 entries have non-blank rationale`() {
        val pr52Entries = StabilizationBaseline.entries.filter { it.introducedPr == 52 }
        pr52Entries.forEach { entry ->
            assertTrue(
                "Entry ${entry.surfaceId} must have non-blank rationale",
                entry.rationale.isNotBlank()
            )
        }
    }

    @Test
    fun `all PR-52 entries reference com.ufo.galaxy packagePath`() {
        val pr52Entries = StabilizationBaseline.entries.filter { it.introducedPr == 52 }
        pr52Entries.forEach { entry ->
            assertTrue(
                "Entry ${entry.surfaceId} packagePath must start with com.ufo.galaxy",
                entry.packagePath.startsWith("com.ufo.galaxy")
            )
        }
    }

    // ── Test session helper ───────────────────────────────────────────────────

    /**
     * Opens a test-only attached session on the controller so that
     * [RuntimeController.recordDelegatedTaskAccepted] can increment the execution counter.
     *
     * Directly mirrors the pattern used in [RuntimeControllerAttachedSessionTest].
     */
    private fun RuntimeController.openTestSession() {
        setActiveForTest()
    }
}
