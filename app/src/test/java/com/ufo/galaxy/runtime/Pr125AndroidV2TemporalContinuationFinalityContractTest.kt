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
 * PR-125 — Android V2 Temporal Continuation Finality Contract tests.
 *
 * Validates:
 * 1. [AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass] has 4 values
 *    with the expected wire values and semantic properties.
 * 2. [AndroidV2TemporalContinuationFinalityContract.classify] returns the correct class
 *    for each combination of TerminalOutcomeKind / isTemporalWorkflowParticipant / isContinuation.
 * 3. [AndroidV2TemporalContinuationFinalityContract.toWireMap] includes all expected keys.
 * 4. [AndroidV2TemporalContinuationFinalityContract.TEMPORAL_CONTINUATION_INVARIANTS] all pass.
 * 5. [ReconciliationSignal] carries the expected KEY_TEMPORAL_* constants.
 * 6. [ReconciliationSignal.withTemporalWorkflowRunId] correctly updates both field and payload.
 * 7. [RuntimeController.publishTaskResult] TASK_RESULT signal carries temporal finality keys.
 * 8. [RuntimeController.notifyTakeoverFailed] TASK_FAILED signal carries temporal finality keys.
 * 9. Session-interrupt TASK_FAILED signal carries WORKFLOW_INTERRUPTED_RESUME_PENDING finality class
 *    when task has a temporal workflow run ID.
 * 10. [RuntimeController.recordDelegatedTaskAccepted] with temporalWorkflowRunId stores the ID
 *     and emits it in the TASK_ACCEPTED signal.
 * 11. [StabilizationBaseline] registers android-v2-temporal-continuation-finality-contract.
 * 12. [AndroidV2TemporalContinuationFinalityContract.V2_TEMPORAL_CONTINUATION_ALIGNMENT_MAP]
 *     covers all ContinuationFinalityClass values.
 */
class Pr125AndroidV2TemporalContinuationFinalityContractTest {

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
            LocalGroundingService.GroundingResult(x = 540, y = 1170, confidence = 0.9f, element_description = "")
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
                hostId = "host-125",
                deviceId = "Pixel-125",
                deviceRole = "phone",
                formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
            )
        )
    }

    private fun buildControllerWithClient(): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = InMemoryAppSettings(),
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L,
            hostDescriptor = RuntimeHostDescriptor(
                hostId = "host-125b",
                deviceId = "Pixel-125b",
                deviceRole = "phone",
                formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
            )
        )
        return controller to client
    }

    // ── 1. ContinuationFinalityClass enum values ──────────────────────────────

    @Test
    fun `ContinuationFinalityClass has exactly 4 values`() {
        assertEquals(
            4,
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.entries.size
        )
    }

    @Test
    fun `WORKFLOW_COMPLETE_FINAL wireValue is workflow_complete_final`() {
        assertEquals(
            "workflow_complete_final",
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass
                .WORKFLOW_COMPLETE_FINAL.wireValue
        )
    }

    @Test
    fun `WORKFLOW_CONTINUATION_ACTIVE wireValue is workflow_continuation_active`() {
        assertEquals(
            "workflow_continuation_active",
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass
                .WORKFLOW_CONTINUATION_ACTIVE.wireValue
        )
    }

    @Test
    fun `WORKFLOW_INTERRUPTED_RESUME_PENDING wireValue is workflow_interrupted_resume_pending`() {
        assertEquals(
            "workflow_interrupted_resume_pending",
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass
                .WORKFLOW_INTERRUPTED_RESUME_PENDING.wireValue
        )
    }

    @Test
    fun `NOT_TEMPORAL_WORKFLOW_PATH wireValue is not_temporal_workflow_path`() {
        assertEquals(
            "not_temporal_workflow_path",
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass
                .NOT_TEMPORAL_WORKFLOW_PATH.wireValue
        )
    }

    @Test
    fun `WORKFLOW_COMPLETE_FINAL isWorkflowFinal is true`() {
        assertTrue(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass
                .WORKFLOW_COMPLETE_FINAL.isWorkflowFinal
        )
    }

    @Test
    fun `WORKFLOW_COMPLETE_FINAL hasPendingTemporalResume is false`() {
        assertFalse(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass
                .WORKFLOW_COMPLETE_FINAL.hasPendingTemporalResume
        )
    }

    @Test
    fun `WORKFLOW_CONTINUATION_ACTIVE isWorkflowFinal is false`() {
        assertFalse(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass
                .WORKFLOW_CONTINUATION_ACTIVE.isWorkflowFinal
        )
    }

    @Test
    fun `WORKFLOW_CONTINUATION_ACTIVE hasPendingTemporalResume is true`() {
        assertTrue(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass
                .WORKFLOW_CONTINUATION_ACTIVE.hasPendingTemporalResume
        )
    }

    @Test
    fun `WORKFLOW_INTERRUPTED_RESUME_PENDING isWorkflowFinal is false`() {
        assertFalse(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass
                .WORKFLOW_INTERRUPTED_RESUME_PENDING.isWorkflowFinal
        )
    }

    @Test
    fun `WORKFLOW_INTERRUPTED_RESUME_PENDING hasPendingTemporalResume is true`() {
        assertTrue(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass
                .WORKFLOW_INTERRUPTED_RESUME_PENDING.hasPendingTemporalResume
        )
    }

    @Test
    fun `NOT_TEMPORAL_WORKFLOW_PATH isWorkflowFinal is false`() {
        assertFalse(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass
                .NOT_TEMPORAL_WORKFLOW_PATH.isWorkflowFinal
        )
    }

    @Test
    fun `NOT_TEMPORAL_WORKFLOW_PATH hasPendingTemporalResume is false`() {
        assertFalse(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass
                .NOT_TEMPORAL_WORKFLOW_PATH.hasPendingTemporalResume
        )
    }

    // ── 2. classify() ─────────────────────────────────────────────────────────

    @Test
    fun `non-participant classifies as NOT_TEMPORAL_WORKFLOW_PATH regardless of outcome`() {
        assertEquals(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH,
            AndroidV2TemporalContinuationFinalityContract.classify(
                terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION,
                isTemporalWorkflowParticipant = false
            )
        )
    }

    @Test
    fun `non-participant INTERRUPTION classifies as NOT_TEMPORAL_WORKFLOW_PATH`() {
        assertEquals(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH,
            AndroidV2TemporalContinuationFinalityContract.classify(
                terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION,
                isTemporalWorkflowParticipant = false
            )
        )
    }

    @Test
    fun `COMPLETION with participant classifies as WORKFLOW_COMPLETE_FINAL`() {
        assertEquals(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL,
            AndroidV2TemporalContinuationFinalityContract.classify(
                terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION,
                isTemporalWorkflowParticipant = true
            )
        )
    }

    @Test
    fun `FAILURE with participant classifies as WORKFLOW_COMPLETE_FINAL`() {
        assertEquals(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL,
            AndroidV2TemporalContinuationFinalityContract.classify(
                terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FAILURE,
                isTemporalWorkflowParticipant = true
            )
        )
    }

    @Test
    fun `ABORT with participant classifies as WORKFLOW_COMPLETE_FINAL`() {
        assertEquals(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL,
            AndroidV2TemporalContinuationFinalityContract.classify(
                terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.ABORT,
                isTemporalWorkflowParticipant = true
            )
        )
    }

    @Test
    fun `INTERRUPTION with participant classifies as WORKFLOW_INTERRUPTED_RESUME_PENDING`() {
        assertEquals(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_INTERRUPTED_RESUME_PENDING,
            AndroidV2TemporalContinuationFinalityContract.classify(
                terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION,
                isTemporalWorkflowParticipant = true
            )
        )
    }

    @Test
    fun `TIMEOUT with participant classifies as WORKFLOW_INTERRUPTED_RESUME_PENDING`() {
        assertEquals(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_INTERRUPTED_RESUME_PENDING,
            AndroidV2TemporalContinuationFinalityContract.classify(
                terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT,
                isTemporalWorkflowParticipant = true
            )
        )
    }

    @Test
    fun `PARTIAL_COMPLETION with participant classifies as WORKFLOW_INTERRUPTED_RESUME_PENDING`() {
        assertEquals(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_INTERRUPTED_RESUME_PENDING,
            AndroidV2TemporalContinuationFinalityContract.classify(
                terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION,
                isTemporalWorkflowParticipant = true
            )
        )
    }

    @Test
    fun `NON_TERMINAL with participant classifies as WORKFLOW_CONTINUATION_ACTIVE`() {
        assertEquals(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_CONTINUATION_ACTIVE,
            AndroidV2TemporalContinuationFinalityContract.classify(
                terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.NON_TERMINAL,
                isTemporalWorkflowParticipant = true
            )
        )
    }

    @Test
    fun `isContinuation true with participant overrides to WORKFLOW_CONTINUATION_ACTIVE`() {
        assertEquals(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_CONTINUATION_ACTIVE,
            AndroidV2TemporalContinuationFinalityContract.classify(
                terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION,
                isTemporalWorkflowParticipant = true,
                isContinuation = true
            )
        )
    }

    @Test
    fun `isContinuation false with COMPLETION and participant classifies as WORKFLOW_COMPLETE_FINAL`() {
        assertEquals(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL,
            AndroidV2TemporalContinuationFinalityContract.classify(
                terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION,
                isTemporalWorkflowParticipant = true,
                isContinuation = false
            )
        )
    }

    // ── 3. toWireMap ──────────────────────────────────────────────────────────

    @Test
    fun `toWireMap includes temporal_continuation_finality_class key`() {
        val map = AndroidV2TemporalContinuationFinalityContract.toWireMap(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL
        )
        assertTrue(
            AndroidV2TemporalContinuationFinalityContract.KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS in map
        )
    }

    @Test
    fun `toWireMap includes temporal_is_workflow_final key`() {
        val map = AndroidV2TemporalContinuationFinalityContract.toWireMap(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL
        )
        assertTrue(
            AndroidV2TemporalContinuationFinalityContract.KEY_IS_TEMPORAL_WORKFLOW_FINAL in map
        )
    }

    @Test
    fun `toWireMap includes temporal_has_pending_resume key`() {
        val map = AndroidV2TemporalContinuationFinalityContract.toWireMap(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL
        )
        assertTrue(
            AndroidV2TemporalContinuationFinalityContract.KEY_HAS_PENDING_TEMPORAL_RESUME in map
        )
    }

    @Test
    fun `toWireMap includes temporal_continuation_finality_schema_version key`() {
        val map = AndroidV2TemporalContinuationFinalityContract.toWireMap(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH
        )
        assertTrue(
            AndroidV2TemporalContinuationFinalityContract.KEY_TEMPORAL_CONTINUATION_FINALITY_SCHEMA_VERSION in map
        )
    }

    @Test
    fun `toWireMap WORKFLOW_COMPLETE_FINAL temporal_is_workflow_final is true`() {
        val map = AndroidV2TemporalContinuationFinalityContract.toWireMap(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL
        )
        assertEquals(
            "true",
            map[AndroidV2TemporalContinuationFinalityContract.KEY_IS_TEMPORAL_WORKFLOW_FINAL]
        )
    }

    @Test
    fun `toWireMap WORKFLOW_INTERRUPTED_RESUME_PENDING temporal_has_pending_resume is true`() {
        val map = AndroidV2TemporalContinuationFinalityContract.toWireMap(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_INTERRUPTED_RESUME_PENDING
        )
        assertEquals(
            "true",
            map[AndroidV2TemporalContinuationFinalityContract.KEY_HAS_PENDING_TEMPORAL_RESUME]
        )
    }

    @Test
    fun `toWireMap NOT_TEMPORAL_WORKFLOW_PATH temporal_is_workflow_final is false`() {
        val map = AndroidV2TemporalContinuationFinalityContract.toWireMap(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH
        )
        assertEquals(
            "false",
            map[AndroidV2TemporalContinuationFinalityContract.KEY_IS_TEMPORAL_WORKFLOW_FINAL]
        )
    }

    @Test
    fun `toWireMap with temporalWorkflowRunId includes temporal_workflow_run_id key`() {
        val map = AndroidV2TemporalContinuationFinalityContract.toWireMap(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL,
            temporalWorkflowRunId = "wf-run-125-test"
        )
        assertEquals(
            "wf-run-125-test",
            map[AndroidV2TemporalContinuationFinalityContract.KEY_TEMPORAL_WORKFLOW_RUN_ID]
        )
    }

    @Test
    fun `toWireMap without temporalWorkflowRunId does not include temporal_workflow_run_id key`() {
        val map = AndroidV2TemporalContinuationFinalityContract.toWireMap(
            AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH
        )
        assertFalse(
            AndroidV2TemporalContinuationFinalityContract.KEY_TEMPORAL_WORKFLOW_RUN_ID in map
        )
    }

    // ── 4. TEMPORAL_CONTINUATION_INVARIANTS ───────────────────────────────────

    @Test
    fun `all TEMPORAL_CONTINUATION_INVARIANTS pass`() {
        AndroidV2TemporalContinuationFinalityContract.TEMPORAL_CONTINUATION_INVARIANTS
            .forEach { (key, value) ->
                assertTrue("Invariant $key failed", value)
            }
    }

    @Test
    fun `TEMPORAL_CONTINUATION_INVARIANTS has exactly 15 entries`() {
        assertEquals(
            15,
            AndroidV2TemporalContinuationFinalityContract.TEMPORAL_CONTINUATION_INVARIANTS.size
        )
    }

    // ── 5. ReconciliationSignal KEY_TEMPORAL_* constants ─────────────────────

    @Test
    fun `KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS wire value is temporal_continuation_finality_class`() {
        assertEquals(
            "temporal_continuation_finality_class",
            ReconciliationSignal.KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS
        )
    }

    @Test
    fun `KEY_IS_TEMPORAL_WORKFLOW_FINAL wire value is temporal_is_workflow_final`() {
        assertEquals(
            "temporal_is_workflow_final",
            ReconciliationSignal.KEY_IS_TEMPORAL_WORKFLOW_FINAL
        )
    }

    @Test
    fun `KEY_HAS_PENDING_TEMPORAL_RESUME wire value is temporal_has_pending_resume`() {
        assertEquals(
            "temporal_has_pending_resume",
            ReconciliationSignal.KEY_HAS_PENDING_TEMPORAL_RESUME
        )
    }

    @Test
    fun `KEY_TEMPORAL_CONTINUATION_FINALITY_SCHEMA_VERSION wire value is temporal_continuation_finality_schema_version`() {
        assertEquals(
            "temporal_continuation_finality_schema_version",
            ReconciliationSignal.KEY_TEMPORAL_CONTINUATION_FINALITY_SCHEMA_VERSION
        )
    }

    @Test
    fun `KEY_TEMPORAL_WORKFLOW_RUN_ID wire value is temporal_workflow_run_id`() {
        assertEquals(
            "temporal_workflow_run_id",
            ReconciliationSignal.KEY_TEMPORAL_WORKFLOW_RUN_ID
        )
    }

    // ── 6. ReconciliationSignal.withTemporalWorkflowRunId ─────────────────────

    @Test
    fun `withTemporalWorkflowRunId sets temporalWorkflowRunId field`() {
        val signal = ReconciliationSignal(
            kind = ReconciliationSignal.Kind.TASK_RESULT,
            participantId = "pid-125",
            taskId = "task-125",
            correlationId = null,
            status = ReconciliationSignal.STATUS_SUCCESS,
            signalId = "sig-125",
            emittedAtMs = 1000L,
            reconciliationEpoch = 1
        )
        val updated = signal.withTemporalWorkflowRunId("wf-run-125")
        assertEquals("wf-run-125", updated.temporalWorkflowRunId)
    }

    @Test
    fun `withTemporalWorkflowRunId adds temporal_workflow_run_id to payload`() {
        val signal = ReconciliationSignal(
            kind = ReconciliationSignal.Kind.TASK_RESULT,
            participantId = "pid-125b",
            taskId = "task-125b",
            correlationId = null,
            status = ReconciliationSignal.STATUS_SUCCESS,
            signalId = "sig-125b",
            emittedAtMs = 1000L,
            reconciliationEpoch = 1
        )
        val updated = signal.withTemporalWorkflowRunId("wf-run-125b")
        assertEquals(
            "wf-run-125b",
            updated.payload[AndroidV2TemporalContinuationFinalityContract.KEY_TEMPORAL_WORKFLOW_RUN_ID]
        )
    }

    @Test
    fun `withTemporalWorkflowRunId null clears temporalWorkflowRunId field`() {
        val signal = ReconciliationSignal(
            kind = ReconciliationSignal.Kind.TASK_RESULT,
            participantId = "pid-125c",
            taskId = "task-125c",
            correlationId = null,
            status = ReconciliationSignal.STATUS_SUCCESS,
            signalId = "sig-125c",
            emittedAtMs = 1000L,
            reconciliationEpoch = 1,
            temporalWorkflowRunId = "wf-run-125c"
        )
        val updated = signal.withTemporalWorkflowRunId(null)
        assertNull(updated.temporalWorkflowRunId)
    }

    @Test
    fun `withTemporalWorkflowRunId null removes temporal_workflow_run_id from payload`() {
        val signal = ReconciliationSignal(
            kind = ReconciliationSignal.Kind.TASK_RESULT,
            participantId = "pid-125d",
            taskId = "task-125d",
            correlationId = null,
            status = ReconciliationSignal.STATUS_SUCCESS,
            payload = mapOf(
                AndroidV2TemporalContinuationFinalityContract.KEY_TEMPORAL_WORKFLOW_RUN_ID to "wf-run-125d"
            ),
            signalId = "sig-125d",
            emittedAtMs = 1000L,
            reconciliationEpoch = 1,
            temporalWorkflowRunId = "wf-run-125d"
        )
        val updated = signal.withTemporalWorkflowRunId(null)
        assertFalse(
            AndroidV2TemporalContinuationFinalityContract.KEY_TEMPORAL_WORKFLOW_RUN_ID in updated.payload
        )
    }

    // ── 7. RuntimeController TASK_RESULT carries temporal finality keys ────────

    @Test
    fun `publishTaskResult TASK_RESULT carries temporal_continuation_finality_class`() =
        runBlocking {
            val controller = buildController()
            controller.recordDelegatedTaskAccepted("task-result-125")
            withTimeoutOrNull(200) { controller.reconciliationSignals.first() }

            controller.publishTaskResult("task-result-125")

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_RESULT signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_RESULT, signal!!.kind)
            assertTrue(
                ReconciliationSignal.KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS in signal.payload
            )
        }

    @Test
    fun `publishTaskResult without temporalWorkflowRunId emits NOT_TEMPORAL_WORKFLOW_PATH`() =
        runBlocking {
            val controller = buildController()
            controller.recordDelegatedTaskAccepted("task-no-temporal-125")
            withTimeoutOrNull(200) { controller.reconciliationSignals.first() }

            controller.publishTaskResult("task-no-temporal-125")

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_RESULT signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_RESULT, signal!!.kind)
            assertEquals(
                AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH.wireValue,
                signal.payload[ReconciliationSignal.KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS]
            )
        }

    @Test
    fun `publishTaskResult with temporalWorkflowRunId emits WORKFLOW_COMPLETE_FINAL`() =
        runBlocking {
            val controller = buildController()
            controller.recordDelegatedTaskAccepted(
                "task-temporal-125",
                temporalWorkflowRunId = "wf-run-125-result"
            )
            withTimeoutOrNull(200) { controller.reconciliationSignals.first() }

            controller.publishTaskResult("task-temporal-125")

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_RESULT signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_RESULT, signal!!.kind)
            assertEquals(
                AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_COMPLETE_FINAL.wireValue,
                signal.payload[ReconciliationSignal.KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS]
            )
        }

    @Test
    fun `publishTaskResult with temporalWorkflowRunId emits temporal_is_workflow_final=true`() =
        runBlocking {
            val controller = buildController()
            controller.recordDelegatedTaskAccepted(
                "task-temporal-final-125",
                temporalWorkflowRunId = "wf-run-125-final"
            )
            withTimeoutOrNull(200) { controller.reconciliationSignals.first() }

            controller.publishTaskResult("task-temporal-final-125")

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_RESULT signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_RESULT, signal!!.kind)
            assertEquals(
                "true",
                signal.payload[ReconciliationSignal.KEY_IS_TEMPORAL_WORKFLOW_FINAL]
            )
        }

    @Test
    fun `publishTaskResult with temporalWorkflowRunId carries temporal_workflow_run_id in payload`() =
        runBlocking {
            val controller = buildController()
            controller.recordDelegatedTaskAccepted(
                "task-temporal-runid-125",
                temporalWorkflowRunId = "wf-run-125-payload"
            )
            withTimeoutOrNull(200) { controller.reconciliationSignals.first() }

            controller.publishTaskResult("task-temporal-runid-125")

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_RESULT signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_RESULT, signal!!.kind)
            assertEquals(
                "wf-run-125-payload",
                signal.payload[AndroidV2TemporalContinuationFinalityContract.KEY_TEMPORAL_WORKFLOW_RUN_ID]
            )
        }

    // ── 8. notifyTakeoverFailed TASK_FAILED carries temporal finality keys ─────

    @Test
    fun `notifyTakeoverFailed without temporalWorkflowRunId emits NOT_TEMPORAL_WORKFLOW_PATH`() =
        runBlocking {
            val controller = buildController()

            controller.notifyTakeoverFailed(
                takeoverId = "to-no-temporal-125",
                taskId = "task-no-temporal-tf-125",
                traceId = "trace-no-temporal-125",
                reason = "execution failed",
                cause = TakeoverFallbackEvent.Cause.FAILED
            )

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_FAILED signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_FAILED, signal!!.kind)
            assertEquals(
                AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH.wireValue,
                signal.payload[ReconciliationSignal.KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS]
            )
        }

    @Test
    fun `notifyTakeoverFailed TIMEOUT with temporalWorkflowRunId emits WORKFLOW_INTERRUPTED_RESUME_PENDING`() =
        runBlocking {
            val controller = buildController()
            controller.recordDelegatedTaskAccepted(
                "task-temporal-timeout-125",
                temporalWorkflowRunId = "wf-run-125-timeout"
            )

            controller.notifyTakeoverFailed(
                takeoverId = "to-timeout-125",
                taskId = "task-temporal-timeout-125",
                traceId = "trace-timeout-125",
                reason = "execution timed out",
                cause = TakeoverFallbackEvent.Cause.TIMEOUT
            )

            val signal = withTimeoutOrNull(300) {
                controller.reconciliationSignals.first { it.kind == ReconciliationSignal.Kind.TASK_FAILED }
            }
            assertNotNull("Expected TASK_FAILED signal", signal)
            assertEquals(
                AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_INTERRUPTED_RESUME_PENDING.wireValue,
                signal!!.payload[ReconciliationSignal.KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS]
            )
        }

    @Test
    fun `notifyTakeoverFailed TASK_FAILED signal has temporal_continuation_finality_schema_version`() =
        runBlocking {
            val controller = buildController()

            controller.notifyTakeoverFailed(
                takeoverId = "to-schema-125",
                taskId = "task-schema-tf-125",
                traceId = "trace-schema-125",
                reason = "execution timed out",
                cause = TakeoverFallbackEvent.Cause.TIMEOUT
            )

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_FAILED signal", signal)
            assertTrue(
                ReconciliationSignal.KEY_TEMPORAL_CONTINUATION_FINALITY_SCHEMA_VERSION in signal!!.payload
            )
        }

    // ── 9. Session-interrupt TASK_FAILED with temporalWorkflowRunId ───────────

    @Test
    fun `session disconnect with temporalWorkflowRunId TASK_FAILED emits WORKFLOW_INTERRUPTED_RESUME_PENDING`() =
        runBlocking {
            val (controller, client) = buildControllerWithClient()
            controller.setActiveForTest()
            controller.recordDelegatedTaskAccepted(
                "task-interrupt-temporal-125",
                temporalWorkflowRunId = "wf-run-125-interrupt"
            )

            val signals = mutableListOf<ReconciliationSignal>()
            val job = launch {
                controller.reconciliationSignals.collect { signals.add(it) }
            }

            client.simulateDisconnected()
            delay(300)
            job.cancel()

            val taskFailedSignal = signals.firstOrNull {
                it.kind == ReconciliationSignal.Kind.TASK_FAILED &&
                    it.taskId == "task-interrupt-temporal-125"
            }
            assertNotNull("Expected TASK_FAILED signal on disconnect", taskFailedSignal)
            assertEquals(
                AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_INTERRUPTED_RESUME_PENDING.wireValue,
                taskFailedSignal!!.payload[ReconciliationSignal.KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS]
            )
        }

    @Test
    fun `session disconnect with temporalWorkflowRunId TASK_FAILED has temporal_has_pending_resume=true`() =
        runBlocking {
            val (controller, client) = buildControllerWithClient()
            controller.setActiveForTest()
            controller.recordDelegatedTaskAccepted(
                "task-interrupt-resume-125",
                temporalWorkflowRunId = "wf-run-125-pending-resume"
            )

            val signals = mutableListOf<ReconciliationSignal>()
            val job = launch {
                controller.reconciliationSignals.collect { signals.add(it) }
            }

            client.simulateDisconnected()
            delay(300)
            job.cancel()

            val taskFailedSignal = signals.firstOrNull {
                it.kind == ReconciliationSignal.Kind.TASK_FAILED &&
                    it.taskId == "task-interrupt-resume-125"
            }
            assertNotNull("Expected TASK_FAILED signal on disconnect", taskFailedSignal)
            assertEquals(
                "true",
                taskFailedSignal!!.payload[ReconciliationSignal.KEY_HAS_PENDING_TEMPORAL_RESUME]
            )
        }

    @Test
    fun `session disconnect without temporalWorkflowRunId TASK_FAILED emits NOT_TEMPORAL_WORKFLOW_PATH`() =
        runBlocking {
            val (controller, client) = buildControllerWithClient()
            controller.setActiveForTest()
            controller.recordDelegatedTaskAccepted("task-interrupt-no-temporal-125")

            val signals = mutableListOf<ReconciliationSignal>()
            val job = launch {
                controller.reconciliationSignals.collect { signals.add(it) }
            }

            client.simulateDisconnected()
            delay(300)
            job.cancel()

            val taskFailedSignal = signals.firstOrNull {
                it.kind == ReconciliationSignal.Kind.TASK_FAILED &&
                    it.taskId == "task-interrupt-no-temporal-125"
            }
            assertNotNull("Expected TASK_FAILED signal on disconnect", taskFailedSignal)
            assertEquals(
                AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH.wireValue,
                taskFailedSignal!!.payload[ReconciliationSignal.KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS]
            )
        }

    // ── 10. recordDelegatedTaskAccepted with temporalWorkflowRunId ─────────────

    @Test
    fun `recordDelegatedTaskAccepted with temporalWorkflowRunId emits TASK_ACCEPTED with temporal_workflow_run_id`() =
        runBlocking {
            val controller = buildController()

            controller.recordDelegatedTaskAccepted(
                taskId = "task-accepted-temporal-125",
                temporalWorkflowRunId = "wf-run-125-accepted"
            )

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_ACCEPTED signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_ACCEPTED, signal!!.kind)
            assertEquals(
                "wf-run-125-accepted",
                signal.payload[AndroidV2TemporalContinuationFinalityContract.KEY_TEMPORAL_WORKFLOW_RUN_ID]
            )
        }

    @Test
    fun `recordDelegatedTaskAccepted with temporalWorkflowRunId TASK_ACCEPTED has WORKFLOW_CONTINUATION_ACTIVE`() =
        runBlocking {
            val controller = buildController()

            controller.recordDelegatedTaskAccepted(
                taskId = "task-accepted-continuation-125",
                temporalWorkflowRunId = "wf-run-125-continuation"
            )

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_ACCEPTED signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_ACCEPTED, signal!!.kind)
            assertEquals(
                AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_CONTINUATION_ACTIVE.wireValue,
                signal.payload[ReconciliationSignal.KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS]
            )
        }

    @Test
    fun `recordDelegatedTaskAccepted without temporalWorkflowRunId TASK_ACCEPTED has NOT_TEMPORAL_WORKFLOW_PATH`() =
        runBlocking {
            val controller = buildController()

            controller.recordDelegatedTaskAccepted(taskId = "task-accepted-no-temporal-125")

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_ACCEPTED signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_ACCEPTED, signal!!.kind)
            assertEquals(
                AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.NOT_TEMPORAL_WORKFLOW_PATH.wireValue,
                signal.payload[ReconciliationSignal.KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS]
            )
        }

    @Test
    fun `publishTaskStatusUpdate with temporalWorkflowRunId emits WORKFLOW_CONTINUATION_ACTIVE`() =
        runBlocking {
            val controller = buildController()

            controller.recordDelegatedTaskAccepted(
                taskId = "task-status-temporal-125",
                temporalWorkflowRunId = "wf-run-125-status"
            )
            withTimeoutOrNull(200) { controller.reconciliationSignals.first() }

            controller.publishTaskStatusUpdate(
                taskId = "task-status-temporal-125",
                progressDetail = "continuing"
            )

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_STATUS_UPDATE signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_STATUS_UPDATE, signal!!.kind)
            assertEquals(
                "wf-run-125-status",
                signal.payload[AndroidV2TemporalContinuationFinalityContract.KEY_TEMPORAL_WORKFLOW_RUN_ID]
            )
            assertEquals(
                AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.WORKFLOW_CONTINUATION_ACTIVE.wireValue,
                signal.payload[ReconciliationSignal.KEY_TEMPORAL_CONTINUATION_FINALITY_CLASS]
            )
        }

    // ── 11. StabilizationBaseline registration ────────────────────────────────

    @Test
    fun `StabilizationBaseline registers android-v2-temporal-continuation-finality-contract`() {
        val entry = StabilizationBaseline.forId(
            "android-v2-temporal-continuation-finality-contract"
        )
        assertNotNull("Expected entry in StabilizationBaseline", entry)
    }

    @Test
    fun `android-v2-temporal-continuation-finality-contract is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId(
            "android-v2-temporal-continuation-finality-contract"
        )
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
    }

    @Test
    fun `android-v2-temporal-continuation-finality-contract has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId(
            "android-v2-temporal-continuation-finality-contract"
        )
        assertEquals(
            StabilizationBaseline.ExtensionGuidance.EXTEND,
            entry!!.extensionGuidance
        )
    }

    @Test
    fun `android-v2-temporal-continuation-finality-contract introducedPr is 125`() {
        val entry = StabilizationBaseline.forId(
            "android-v2-temporal-continuation-finality-contract"
        )
        assertEquals(125, entry!!.introducedPr)
    }

    // ── 12. V2_TEMPORAL_CONTINUATION_ALIGNMENT_MAP coverage ──────────────────

    @Test
    fun `V2_TEMPORAL_CONTINUATION_ALIGNMENT_MAP covers all ContinuationFinalityClass values`() {
        AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass.entries.forEach { cls ->
            assertTrue(
                "V2_TEMPORAL_CONTINUATION_ALIGNMENT_MAP missing entry for ${cls.wireValue}",
                AndroidV2TemporalContinuationFinalityContract.V2_TEMPORAL_CONTINUATION_ALIGNMENT_MAP
                    .containsKey(cls.wireValue)
            )
        }
    }

    // ── 13. All wire values are distinct ──────────────────────────────────────

    @Test
    fun `all ContinuationFinalityClass wire values are distinct`() {
        val wireValues = AndroidV2TemporalContinuationFinalityContract.ContinuationFinalityClass
            .entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }
}
