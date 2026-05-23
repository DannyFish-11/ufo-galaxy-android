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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-124 — Android V2 Failure Recovery Compatibility Contract tests.
 *
 * Validates:
 * 1. [AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass] has 5 values
 *    with the expected wire values.
 * 2. [AndroidV2FailureRecoveryCompatibilityContract.classify] returns the correct class
 *    for each TerminalOutcomeKind and hadPartialProgress combination.
 * 3. [AndroidV2FailureRecoveryCompatibilityContract.toWireMap] includes all expected keys.
 * 4. [AndroidV2FailureRecoveryCompatibilityContract.FAILURE_RECOVERY_INVARIANTS]
 *    all pass.
 * 5. [ReconciliationSignal] carries the expected KEY_FAILURE_RECOVERY_CLASS constants.
 * 6. [RuntimeController.notifyTakeoverFailed] TASK_FAILED signal carries failure_recovery_class.
 * 7. Session-interrupt TASK_FAILED carries SESSION_INTERRUPTED failure recovery class.
 * 8. [StabilizationBaseline] registers android-v2-failure-recovery-compatibility-contract.
 */
class Pr124AndroidV2FailureRecoveryCompatibilityContractTest {

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
                hostId = "host-124",
                deviceId = "Pixel-124",
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
                hostId = "host-124b",
                deviceId = "Pixel-124b",
                deviceRole = "phone",
                formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
            )
        )
        return controller to client
    }

    // ── 1. FailureRecoveryClass enum values ───────────────────────────────────

    @Test
    fun `FailureRecoveryClass has exactly 5 values`() {
        assertEquals(
            5,
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.entries.size
        )
    }

    @Test
    fun `CLEAN_TIMEOUT wireValue is clean_timeout`() {
        assertEquals(
            "clean_timeout",
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.CLEAN_TIMEOUT.wireValue
        )
    }

    @Test
    fun `SESSION_INTERRUPTED wireValue is session_interrupted`() {
        assertEquals(
            "session_interrupted",
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass
                .SESSION_INTERRUPTED.wireValue
        )
    }

    @Test
    fun `PARTIAL_THEN_INTERRUPTED wireValue is partial_then_interrupted`() {
        assertEquals(
            "partial_then_interrupted",
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass
                .PARTIAL_THEN_INTERRUPTED.wireValue
        )
    }

    @Test
    fun `EXECUTION_FAILED wireValue is execution_failed`() {
        assertEquals(
            "execution_failed",
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass
                .EXECUTION_FAILED.wireValue
        )
    }

    @Test
    fun `NOT_A_FAILURE wireValue is not_a_failure`() {
        assertEquals(
            "not_a_failure",
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass
                .NOT_A_FAILURE.wireValue
        )
    }

    @Test
    fun `CLEAN_TIMEOUT isRetryEligible is true`() {
        assertTrue(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass
                .CLEAN_TIMEOUT.isRetryEligible
        )
    }

    @Test
    fun `SESSION_INTERRUPTED isInterruption is true`() {
        assertTrue(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass
                .SESSION_INTERRUPTED.isInterruption
        )
    }

    @Test
    fun `PARTIAL_THEN_INTERRUPTED requiresPartialReconciliation is true`() {
        assertTrue(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass
                .PARTIAL_THEN_INTERRUPTED.requiresPartialReconciliation
        )
    }

    @Test
    fun `PARTIAL_THEN_INTERRUPTED isRetryEligible is false`() {
        assertFalse(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass
                .PARTIAL_THEN_INTERRUPTED.isRetryEligible
        )
    }

    @Test
    fun `NOT_A_FAILURE isRetryEligible is false`() {
        assertFalse(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass
                .NOT_A_FAILURE.isRetryEligible
        )
    }

    @Test
    fun `NOT_A_FAILURE isInterruption is false`() {
        assertFalse(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass
                .NOT_A_FAILURE.isInterruption
        )
    }

    // ── 2. classify() ─────────────────────────────────────────────────────────

    @Test
    fun `TIMEOUT classifies as CLEAN_TIMEOUT without partial progress`() {
        val result = AndroidV2FailureRecoveryCompatibilityContract.classify(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT
        )
        assertEquals(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.CLEAN_TIMEOUT,
            result
        )
    }

    @Test
    fun `TIMEOUT with hadPartialProgress classifies as PARTIAL_THEN_INTERRUPTED`() {
        val result = AndroidV2FailureRecoveryCompatibilityContract.classify(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT,
            hadPartialProgress = true
        )
        assertEquals(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED,
            result
        )
    }

    @Test
    fun `INTERRUPTION classifies as SESSION_INTERRUPTED without partial progress`() {
        val result = AndroidV2FailureRecoveryCompatibilityContract.classify(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION
        )
        assertEquals(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.SESSION_INTERRUPTED,
            result
        )
    }

    @Test
    fun `INTERRUPTION with hadPartialProgress classifies as PARTIAL_THEN_INTERRUPTED`() {
        val result = AndroidV2FailureRecoveryCompatibilityContract.classify(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION,
            hadPartialProgress = true
        )
        assertEquals(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED,
            result
        )
    }

    @Test
    fun `FAILURE classifies as EXECUTION_FAILED`() {
        val result = AndroidV2FailureRecoveryCompatibilityContract.classify(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FAILURE
        )
        assertEquals(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.EXECUTION_FAILED,
            result
        )
    }

    @Test
    fun `ABORT classifies as EXECUTION_FAILED`() {
        val result = AndroidV2FailureRecoveryCompatibilityContract.classify(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.ABORT
        )
        assertEquals(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.EXECUTION_FAILED,
            result
        )
    }

    @Test
    fun `PARTIAL_COMPLETION classifies as PARTIAL_THEN_INTERRUPTED`() {
        val result = AndroidV2FailureRecoveryCompatibilityContract.classify(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION
        )
        assertEquals(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED,
            result
        )
    }

    @Test
    fun `COMPLETION classifies as NOT_A_FAILURE`() {
        val result = AndroidV2FailureRecoveryCompatibilityContract.classify(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION
        )
        assertEquals(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.NOT_A_FAILURE,
            result
        )
    }

    @Test
    fun `RECOVERY classifies as NOT_A_FAILURE`() {
        val result = AndroidV2FailureRecoveryCompatibilityContract.classify(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.RECOVERY
        )
        assertEquals(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.NOT_A_FAILURE,
            result
        )
    }

    @Test
    fun `NON_TERMINAL classifies as NOT_A_FAILURE`() {
        val result = AndroidV2FailureRecoveryCompatibilityContract.classify(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.NON_TERMINAL
        )
        assertEquals(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.NOT_A_FAILURE,
            result
        )
    }

    @Test
    fun `FALLBACK classifies as NOT_A_FAILURE`() {
        val result = AndroidV2FailureRecoveryCompatibilityContract.classify(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FALLBACK
        )
        assertEquals(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.NOT_A_FAILURE,
            result
        )
    }

    // ── 3. toWireMap ──────────────────────────────────────────────────────────

    @Test
    fun `toWireMap includes failure_recovery_class key`() {
        val map = AndroidV2FailureRecoveryCompatibilityContract.toWireMap(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.CLEAN_TIMEOUT
        )
        assertTrue(
            AndroidV2FailureRecoveryCompatibilityContract.KEY_FAILURE_RECOVERY_CLASS in map
        )
    }

    @Test
    fun `toWireMap includes failure_had_partial_progress key`() {
        val map = AndroidV2FailureRecoveryCompatibilityContract.toWireMap(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.SESSION_INTERRUPTED
        )
        assertTrue(
            AndroidV2FailureRecoveryCompatibilityContract.KEY_HAD_PARTIAL_PROGRESS in map
        )
    }

    @Test
    fun `toWireMap includes failure_is_retry_eligible key`() {
        val map = AndroidV2FailureRecoveryCompatibilityContract.toWireMap(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.EXECUTION_FAILED
        )
        assertTrue(
            AndroidV2FailureRecoveryCompatibilityContract.KEY_IS_RETRY_ELIGIBLE in map
        )
    }

    @Test
    fun `toWireMap includes failure_recovery_schema_version key`() {
        val map = AndroidV2FailureRecoveryCompatibilityContract.toWireMap(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.NOT_A_FAILURE
        )
        assertTrue(
            AndroidV2FailureRecoveryCompatibilityContract.KEY_FAILURE_RECOVERY_SCHEMA_VERSION in map
        )
    }

    @Test
    fun `toWireMap CLEAN_TIMEOUT failure_recovery_class is clean_timeout`() {
        val map = AndroidV2FailureRecoveryCompatibilityContract.toWireMap(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.CLEAN_TIMEOUT
        )
        assertEquals(
            "clean_timeout",
            map[AndroidV2FailureRecoveryCompatibilityContract.KEY_FAILURE_RECOVERY_CLASS]
        )
    }

    @Test
    fun `toWireMap CLEAN_TIMEOUT failure_is_retry_eligible is true`() {
        val map = AndroidV2FailureRecoveryCompatibilityContract.toWireMap(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.CLEAN_TIMEOUT
        )
        assertEquals(
            "true",
            map[AndroidV2FailureRecoveryCompatibilityContract.KEY_IS_RETRY_ELIGIBLE]
        )
    }

    @Test
    fun `toWireMap PARTIAL_THEN_INTERRUPTED failure_is_retry_eligible is false`() {
        val map = AndroidV2FailureRecoveryCompatibilityContract.toWireMap(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED
        )
        assertEquals(
            "false",
            map[AndroidV2FailureRecoveryCompatibilityContract.KEY_IS_RETRY_ELIGIBLE]
        )
    }

    @Test
    fun `toWireMap with hadPartialProgress=true failure_had_partial_progress is true`() {
        val map = AndroidV2FailureRecoveryCompatibilityContract.toWireMap(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.PARTIAL_THEN_INTERRUPTED,
            hadPartialProgress = true
        )
        assertEquals(
            "true",
            map[AndroidV2FailureRecoveryCompatibilityContract.KEY_HAD_PARTIAL_PROGRESS]
        )
    }

    @Test
    fun `toWireMap default hadPartialProgress=false failure_had_partial_progress is false`() {
        val map = AndroidV2FailureRecoveryCompatibilityContract.toWireMap(
            AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.CLEAN_TIMEOUT
        )
        assertEquals(
            "false",
            map[AndroidV2FailureRecoveryCompatibilityContract.KEY_HAD_PARTIAL_PROGRESS]
        )
    }

    // ── 4. FAILURE_RECOVERY_INVARIANTS ────────────────────────────────────────

    @Test
    fun `all FAILURE_RECOVERY_INVARIANTS pass`() {
        AndroidV2FailureRecoveryCompatibilityContract.FAILURE_RECOVERY_INVARIANTS
            .forEach { (key, value) ->
                assertTrue("Invariant $key failed", value)
            }
    }

    @Test
    fun `FAILURE_RECOVERY_INVARIANTS has exactly 15 entries`() {
        assertEquals(
            15,
            AndroidV2FailureRecoveryCompatibilityContract.FAILURE_RECOVERY_INVARIANTS.size
        )
    }

    // ── 5. ReconciliationSignal KEY_FAILURE_RECOVERY_CLASS constants ──────────

    @Test
    fun `KEY_FAILURE_RECOVERY_CLASS wire value is failure_recovery_class`() {
        assertEquals(
            "failure_recovery_class",
            ReconciliationSignal.KEY_FAILURE_RECOVERY_CLASS
        )
    }

    @Test
    fun `KEY_FAILURE_HAD_PARTIAL_PROGRESS wire value is failure_had_partial_progress`() {
        assertEquals(
            "failure_had_partial_progress",
            ReconciliationSignal.KEY_FAILURE_HAD_PARTIAL_PROGRESS
        )
    }

    @Test
    fun `KEY_FAILURE_IS_RETRY_ELIGIBLE wire value is failure_is_retry_eligible`() {
        assertEquals(
            "failure_is_retry_eligible",
            ReconciliationSignal.KEY_FAILURE_IS_RETRY_ELIGIBLE
        )
    }

    @Test
    fun `KEY_FAILURE_RECOVERY_SCHEMA_VERSION wire value is failure_recovery_schema_version`() {
        assertEquals(
            "failure_recovery_schema_version",
            ReconciliationSignal.KEY_FAILURE_RECOVERY_SCHEMA_VERSION
        )
    }

    // ── 6. RuntimeController — TASK_FAILED from notifyTakeoverFailed carries failure_recovery_class

    @Test
    fun `notifyTakeoverFailed TIMEOUT carries CLEAN_TIMEOUT failure_recovery_class`() =
        runBlocking {
            val controller = buildController()

            controller.notifyTakeoverFailed(
                takeoverId = "to-timeout-124",
                taskId = "task-timeout-124",
                traceId = "trace-timeout-124",
                reason = "execution timed out",
                cause = TakeoverFallbackEvent.Cause.TIMEOUT
            )

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_FAILED signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_FAILED, signal!!.kind)
            assertEquals(
                AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.CLEAN_TIMEOUT.wireValue,
                signal.payload[ReconciliationSignal.KEY_FAILURE_RECOVERY_CLASS]
            )
        }

    @Test
    fun `notifyTakeoverFailed FAILED carries EXECUTION_FAILED failure_recovery_class`() =
        runBlocking {
            val controller = buildController()

            controller.notifyTakeoverFailed(
                takeoverId = "to-failed-124",
                taskId = "task-failed-124",
                traceId = "trace-failed-124",
                reason = "internal error",
                cause = TakeoverFallbackEvent.Cause.FAILED
            )

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_FAILED signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_FAILED, signal!!.kind)
            assertEquals(
                AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.EXECUTION_FAILED.wireValue,
                signal.payload[ReconciliationSignal.KEY_FAILURE_RECOVERY_CLASS]
            )
        }

    @Test
    fun `notifyTakeoverFailed DISCONNECT carries SESSION_INTERRUPTED failure_recovery_class`() =
        runBlocking {
            val controller = buildController()

            controller.notifyTakeoverFailed(
                takeoverId = "to-disconnect-124",
                taskId = "task-disconnect-124",
                traceId = "trace-disconnect-124",
                reason = "ws disconnected",
                cause = TakeoverFallbackEvent.Cause.DISCONNECT
            )

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_FAILED signal", signal)
            assertEquals(ReconciliationSignal.Kind.TASK_FAILED, signal!!.kind)
            assertEquals(
                AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.SESSION_INTERRUPTED.wireValue,
                signal.payload[ReconciliationSignal.KEY_FAILURE_RECOVERY_CLASS]
            )
        }

    @Test
    fun `notifyTakeoverFailed TASK_FAILED signal has failure_is_retry_eligible key`() =
        runBlocking {
            val controller = buildController()

            controller.notifyTakeoverFailed(
                takeoverId = "to-retry-124",
                taskId = "task-retry-124",
                traceId = "trace-retry-124",
                reason = "execution timed out",
                cause = TakeoverFallbackEvent.Cause.TIMEOUT
            )

            val signal = withTimeoutOrNull(300) { controller.reconciliationSignals.first() }
            assertNotNull("Expected TASK_FAILED signal", signal)
            assertTrue(
                ReconciliationSignal.KEY_FAILURE_IS_RETRY_ELIGIBLE in signal!!.payload
            )
        }

    // ── 7. Session-interrupt TASK_FAILED carries SESSION_INTERRUPTED ──────────

    @Test
    fun `session disconnect TASK_FAILED carries SESSION_INTERRUPTED failure_recovery_class`() =
        runBlocking {
            val (controller, client) = buildControllerWithClient()
            controller.setActiveForTest()
            controller.recordDelegatedTaskAccepted("task-interrupt-124")

            val signals = mutableListOf<ReconciliationSignal>()
            val job = kotlinx.coroutines.launch {
                controller.reconciliationSignals.collect { signals.add(it) }
            }

            // Simulate WS disconnect (triggers closeAttachedSession → TASK_FAILED)
            client.simulateDisconnected()
            kotlinx.coroutines.delay(300)
            job.cancel()

            val taskFailedSignal = signals.firstOrNull {
                it.kind == ReconciliationSignal.Kind.TASK_FAILED &&
                    it.taskId == "task-interrupt-124"
            }
            assertNotNull("Expected TASK_FAILED signal on disconnect", taskFailedSignal)
            assertEquals(
                AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.SESSION_INTERRUPTED.wireValue,
                taskFailedSignal!!.payload[ReconciliationSignal.KEY_FAILURE_RECOVERY_CLASS]
            )
        }

    @Test
    fun `session disconnect TASK_FAILED has failure_recovery_schema_version`() =
        runBlocking {
            val (controller, client) = buildControllerWithClient()
            controller.setActiveForTest()
            controller.recordDelegatedTaskAccepted("task-schema-124")

            val signals = mutableListOf<ReconciliationSignal>()
            val job = kotlinx.coroutines.launch {
                controller.reconciliationSignals.collect { signals.add(it) }
            }

            client.simulateDisconnected()
            kotlinx.coroutines.delay(300)
            job.cancel()

            val taskFailedSignal = signals.firstOrNull {
                it.kind == ReconciliationSignal.Kind.TASK_FAILED &&
                    it.taskId == "task-schema-124"
            }
            assertNotNull("Expected TASK_FAILED signal on disconnect", taskFailedSignal)
            assertTrue(
                ReconciliationSignal.KEY_FAILURE_RECOVERY_SCHEMA_VERSION in taskFailedSignal!!.payload
            )
        }

    // ── 8. StabilizationBaseline registration ────────────────────────────────

    @Test
    fun `StabilizationBaseline registers android-v2-failure-recovery-compatibility-contract`() {
        val entry = StabilizationBaseline.forId(
            "android-v2-failure-recovery-compatibility-contract"
        )
        assertNotNull("Expected entry in StabilizationBaseline", entry)
    }

    @Test
    fun `android-v2-failure-recovery-compatibility-contract is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId(
            "android-v2-failure-recovery-compatibility-contract"
        )
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
    }

    @Test
    fun `android-v2-failure-recovery-compatibility-contract has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId(
            "android-v2-failure-recovery-compatibility-contract"
        )
        assertEquals(
            StabilizationBaseline.ExtensionGuidance.EXTEND,
            entry!!.extensionGuidance
        )
    }

    @Test
    fun `android-v2-failure-recovery-compatibility-contract introducedPr is 124`() {
        val entry = StabilizationBaseline.forId(
            "android-v2-failure-recovery-compatibility-contract"
        )
        assertEquals(124, entry!!.introducedPr)
    }

    // ── 9. V2_FAILURE_RECOVERY_ALIGNMENT_MAP coverage ─────────────────────────

    @Test
    fun `V2_FAILURE_RECOVERY_ALIGNMENT_MAP covers all FailureRecoveryClass values`() {
        AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass.entries.forEach { cls ->
            assertTrue(
                "V2_FAILURE_RECOVERY_ALIGNMENT_MAP missing entry for ${cls.wireValue}",
                AndroidV2FailureRecoveryCompatibilityContract.V2_FAILURE_RECOVERY_ALIGNMENT_MAP
                    .containsKey(cls.wireValue)
            )
        }
    }

    // ── 10. All wire values are distinct ──────────────────────────────────────

    @Test
    fun `all FailureRecoveryClass wire values are distinct`() {
        val wireValues = AndroidV2FailureRecoveryCompatibilityContract.FailureRecoveryClass
            .entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }
}
