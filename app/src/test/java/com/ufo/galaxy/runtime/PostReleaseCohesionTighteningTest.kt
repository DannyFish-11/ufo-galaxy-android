package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.local.LocalLoopResult
import com.ufo.galaxy.loop.ExecutorBridge
import com.ufo.galaxy.loop.LocalPlanner
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.ui.viewmodel.UnifiedResultPresentation
import kotlinx.coroutines.launch
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
 * PR-29 — Post-release cohesion and stability tightening.
 *
 * Integrated tightening suite that addresses gaps discovered across Android execution paths
 * after PR-28 baseline:
 *
 * ## Changes validated by this suite
 *
 * ### 1. `UnifiedResultPresentation.fromLocalResult` — explicit STATUS_DISABLED handling
 *
 * Previously the `STATUS_DISABLED` outcome fell through to the generic `else` branch and
 * was presented as a plain failure.  PR-29 adds an explicit branch with a precise
 * "not executed — path disabled" message that accurately reflects the posture-gated
 * execution state, distinct from a genuine execution failure ([LocalLoopResult.STATUS_FAILED]).
 *
 * ### 2. `RuntimeController.isRemoteExecutionActive` — deterministic execution-flight state
 *
 * A new [RuntimeController.isRemoteExecutionActive] `StateFlow<Boolean>` is `true` between
 * [RuntimeController.onRemoteTaskStarted] and [RuntimeController.onRemoteTaskFinished] (or
 * [RuntimeController.stop]).  Surface layers observe this flow to clear loading indicators
 * without duplicating execution-state tracking in their own fields.
 *
 * ### 3. `RuntimeController.notifyTakeoverFailed` — duplicate emission guard
 *
 * Concurrent failure signals for the same `takeoverId` (e.g. both an explicit FAILED outcome
 * and a simultaneous WS disconnect attempt to notify) previously produced two
 * [TakeoverFallbackEvent] emissions.  The deduplication guard introduced in PR-29 ensures
 * that at most one event is emitted per `takeoverId` within a single attached session
 * lifetime.  The guard is reset when [closeAttachedSession] is called, so a fresh session
 * (after reconnect) starts clean.
 *
 * ## Test matrix
 *
 * ### UnifiedResultPresentation — STATUS_DISABLED handling
 *  - fromLocalResult with STATUS_DISABLED produces isSuccess = false
 *  - fromLocalResult with STATUS_DISABLED outcome matches STATUS_DISABLED constant
 *  - fromLocalResult with STATUS_DISABLED summary is non-blank
 *  - fromLocalResult with STATUS_DISABLED summary is distinct from STATUS_FAILED summary
 *  - fromLocalResult with STATUS_DISABLED summary is distinct from STATUS_CANCELLED summary
 *  - fromLocalResult with STATUS_DISABLED does not contain "失败" (failure wording)
 *  - fromLocalResult with STATUS_FAILED falls through to generic failure message
 *  - fromLocalResult STATUS_DISABLED and STATUS_FAILED are both not-success but distinct outcomes
 *
 * ### RuntimeController.isRemoteExecutionActive — flight state
 *  - isRemoteExecutionActive is false before any task starts
 *  - isRemoteExecutionActive becomes true after onRemoteTaskStarted
 *  - isRemoteExecutionActive returns to false after onRemoteTaskFinished
 *  - isRemoteExecutionActive is false after stop() clears in-flight state
 *  - rapid start/finish cycles leave flag false at idle
 *  - multiple sequential tasks leave flag false when all finished
 *
 * ### RuntimeController.notifyTakeoverFailed — deduplication guard
 *  - first notifyTakeoverFailed for a takeoverId emits an event
 *  - second notifyTakeoverFailed for the same takeoverId is suppressed
 *  - distinct takeoverIds each emit their own event
 *  - deduplication set clears on stop() (session close)
 *  - after session close, same takeoverId can emit again for a new session context
 *  - deduplication does not suppress events with different causes for different IDs
 *  - large number of distinct takeoverIds all emit (no false de-duplication)
 *
 * ### Cross-path regression protection
 *  - stop() resets both isRemoteExecutionActive and clears dedup state
 *  - notifyTakeoverFailed does not affect isRemoteExecutionActive
 *  - onRemoteTaskFinished does not clear attachedSession
 *  - STATUS_DISABLED and STATUS_CANCELLED are both not-success and distinct
 *  - all four LocalLoopResult statuses map to distinct outcomes
 *  - server message presentation remains success regardless of local state
 */
class PostReleaseCohesionTighteningTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies ─────────────────────────────────────────────────────

    private class TrivialPlannerService : LocalPlannerService {
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
            localPlanner = LocalPlanner(TrivialPlannerService()),
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
        loopController: LoopController = buildLoopController()
    ): RuntimeController {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        return RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = loopController
        )
    }

    private fun makeDisabledResult(sessionId: String = "s-dis") = LocalLoopResult(
        sessionId = sessionId,
        instruction = "test goal",
        status = LocalLoopResult.STATUS_DISABLED,
        stepCount = 0,
        stopReason = null,
        error = null
    )

    private fun makeFailedResult(error: String = "step limit reached") = LocalLoopResult(
        sessionId = "s-fail",
        instruction = "test goal",
        status = LocalLoopResult.STATUS_FAILED,
        stepCount = 3,
        stopReason = "step_limit",
        error = error
    )

    private fun makeCancelledResult() = LocalLoopResult(
        sessionId = "s-cancel",
        instruction = "test goal",
        status = LocalLoopResult.STATUS_CANCELLED,
        stepCount = 1,
        stopReason = null,
        error = null
    )

    private fun makeSuccessResult(stepCount: Int = 4) = LocalLoopResult(
        sessionId = "s-ok",
        instruction = "test goal",
        status = LocalLoopResult.STATUS_SUCCESS,
        stepCount = stepCount,
        stopReason = null,
        error = null
    )

    private fun makeFailureEvent(
        takeoverId: String = "t-default",
        cause: TakeoverFallbackEvent.Cause = TakeoverFallbackEvent.Cause.FAILED
    ) = TakeoverFallbackEvent(
        takeoverId = takeoverId,
        taskId = "task-1",
        traceId = "trace-1",
        reason = "test failure",
        cause = cause
    )

    // ════════════════════════════════════════════════════════════════════════════
    // UnifiedResultPresentation — STATUS_DISABLED handling
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `fromLocalResult with STATUS_DISABLED produces isSuccess false`() {
        val p = UnifiedResultPresentation.fromLocalResult(makeDisabledResult())
        assertFalse(
            "STATUS_DISABLED must not be presented as a success",
            p.isSuccess
        )
    }

    @Test
    fun `fromLocalResult with STATUS_DISABLED outcome matches STATUS_DISABLED constant`() {
        val p = UnifiedResultPresentation.fromLocalResult(makeDisabledResult())
        assertEquals(
            "STATUS_DISABLED outcome must equal LocalLoopResult.STATUS_DISABLED",
            LocalLoopResult.STATUS_DISABLED,
            p.outcome
        )
    }

    @Test
    fun `fromLocalResult with STATUS_DISABLED summary is non-blank`() {
        val p = UnifiedResultPresentation.fromLocalResult(makeDisabledResult())
        assertTrue(
            "STATUS_DISABLED summary must not be blank",
            p.summary.isNotBlank()
        )
    }

    @Test
    fun `fromLocalResult with STATUS_DISABLED summary is distinct from STATUS_FAILED summary`() {
        val disabledP = UnifiedResultPresentation.fromLocalResult(makeDisabledResult())
        val failedP = UnifiedResultPresentation.fromLocalResult(makeFailedResult())
        assertFalse(
            "STATUS_DISABLED and STATUS_FAILED must produce distinct summaries",
            disabledP.summary == failedP.summary
        )
    }

    @Test
    fun `fromLocalResult with STATUS_DISABLED summary is distinct from STATUS_CANCELLED summary`() {
        val disabledP = UnifiedResultPresentation.fromLocalResult(makeDisabledResult())
        val cancelledP = UnifiedResultPresentation.fromLocalResult(makeCancelledResult())
        assertFalse(
            "STATUS_DISABLED and STATUS_CANCELLED must produce distinct summaries",
            disabledP.summary == cancelledP.summary
        )
    }

    @Test
    fun `fromLocalResult with STATUS_DISABLED summary does not contain failure wording`() {
        val p = UnifiedResultPresentation.fromLocalResult(makeDisabledResult())
        assertFalse(
            "STATUS_DISABLED summary must not use the generic failure wording (失败)",
            p.summary.contains("失败")
        )
    }

    @Test
    fun `fromLocalResult with STATUS_FAILED falls through to generic failure message`() {
        val p = UnifiedResultPresentation.fromLocalResult(makeFailedResult("model error"))
        assertFalse("STATUS_FAILED must not be success", p.isSuccess)
        assertEquals(
            "STATUS_FAILED outcome must equal the status string itself",
            LocalLoopResult.STATUS_FAILED,
            p.outcome
        )
        assertTrue(
            "STATUS_FAILED summary must contain the error text",
            p.summary.contains("model error")
        )
    }

    @Test
    fun `fromLocalResult STATUS_DISABLED and STATUS_FAILED are both not-success but distinct outcomes`() {
        val disabledP = UnifiedResultPresentation.fromLocalResult(makeDisabledResult())
        val failedP = UnifiedResultPresentation.fromLocalResult(makeFailedResult())
        assertFalse("STATUS_DISABLED must not be success", disabledP.isSuccess)
        assertFalse("STATUS_FAILED must not be success", failedP.isSuccess)
        assertFalse(
            "STATUS_DISABLED and STATUS_FAILED must have distinct outcome strings",
            disabledP.outcome == failedP.outcome
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // RuntimeController.isRemoteExecutionActive — flight state
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `isRemoteExecutionActive is false before any task starts`() {
        val controller = buildController()
        assertFalse(
            "isRemoteExecutionActive must be false before any remote task is started",
            controller.isRemoteExecutionActive.value
        )
    }

    @Test
    fun `isRemoteExecutionActive becomes true after onRemoteTaskStarted`() {
        val controller = buildController()
        controller.onRemoteTaskStarted()
        assertTrue(
            "isRemoteExecutionActive must be true after onRemoteTaskStarted",
            controller.isRemoteExecutionActive.value
        )
    }

    @Test
    fun `isRemoteExecutionActive returns to false after onRemoteTaskFinished`() {
        val controller = buildController()
        controller.onRemoteTaskStarted()
        controller.onRemoteTaskFinished()
        assertFalse(
            "isRemoteExecutionActive must be false after onRemoteTaskFinished",
            controller.isRemoteExecutionActive.value
        )
    }

    @Test
    fun `isRemoteExecutionActive is false after stop clears in-flight state`() {
        val controller = buildController()
        controller.onRemoteTaskStarted()
        assertTrue(
            "isRemoteExecutionActive must be true after starting",
            controller.isRemoteExecutionActive.value
        )
        controller.stop()
        assertFalse(
            "stop() must clear isRemoteExecutionActive to false even when a task was in-flight",
            controller.isRemoteExecutionActive.value
        )
    }

    @Test
    fun `rapid start finish cycles leave flag false at idle`() {
        val controller = buildController()
        repeat(5) {
            controller.onRemoteTaskStarted()
            controller.onRemoteTaskFinished()
        }
        assertFalse(
            "isRemoteExecutionActive must settle to false after rapid start/finish cycles",
            controller.isRemoteExecutionActive.value
        )
    }

    @Test
    fun `multiple sequential tasks leave flag false when all finished`() {
        val controller = buildController()
        for (i in 1..3) {
            controller.onRemoteTaskStarted()
            assertTrue(
                "isRemoteExecutionActive must be true during task $i",
                controller.isRemoteExecutionActive.value
            )
            controller.onRemoteTaskFinished()
            assertFalse(
                "isRemoteExecutionActive must be false after task $i finishes",
                controller.isRemoteExecutionActive.value
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // RuntimeController.notifyTakeoverFailed — deduplication guard
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `first notifyTakeoverFailed for a takeoverId emits an event`() = runBlocking {
        val controller = buildController()
        var received: TakeoverFallbackEvent? = null
        val job = launch { received = controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "t-dedup-1",
            taskId = "task-1",
            traceId = "trace-1",
            reason = "first notification",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        job.join()
        assertNotNull(
            "First notifyTakeoverFailed for a new takeoverId must emit an event",
            received
        )
        assertEquals("t-dedup-1", received!!.takeoverId)
    }

    @Test
    fun `second notifyTakeoverFailed for the same takeoverId is suppressed`() = runBlocking {
        val controller = buildController()
        val collected = mutableListOf<TakeoverFallbackEvent>()
        val job = launch {
            // Collect the first event, then wait briefly for any second event
            collected.add(controller.takeoverFailure.first())
        }

        // Emit first notification — should be collected
        controller.notifyTakeoverFailed(
            takeoverId = "t-dedup-dup",
            taskId = "task-dup",
            traceId = "trace-dup",
            reason = "first",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        job.join()

        // Attempt a second notification for the same ID — must be suppressed
        var secondReceived: TakeoverFallbackEvent? = null
        val job2 = launch {
            secondReceived = withTimeoutOrNull(200L) {
                controller.takeoverFailure.first()
            }
        }
        controller.notifyTakeoverFailed(
            takeoverId = "t-dedup-dup",
            taskId = "task-dup",
            traceId = "trace-dup",
            reason = "duplicate",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        job2.join()

        assertNull(
            "Second notifyTakeoverFailed for the same takeoverId must be suppressed (no event emitted)",
            secondReceived
        )
        assertEquals(
            "Exactly one event must have been emitted for the duplicated takeoverId",
            1,
            collected.size
        )
    }

    @Test
    fun `distinct takeoverIds each emit their own event`() = runBlocking {
        val controller = buildController()
        val ids = listOf("t-a", "t-b", "t-c")
        val received = mutableListOf<String>()

        for (id in ids) {
            val job = launch { received.add(controller.takeoverFailure.first().takeoverId) }
            controller.notifyTakeoverFailed(
                takeoverId = id,
                taskId = "task-$id",
                traceId = "trace-$id",
                reason = "failure for $id",
                cause = TakeoverFallbackEvent.Cause.TIMEOUT
            )
            job.join()
        }

        assertEquals(
            "Each distinct takeoverId must produce one event",
            ids.size,
            received.size
        )
        assertEquals(
            "Received takeover IDs must match the emitted IDs in order",
            ids,
            received
        )
    }

    @Test
    fun `deduplication set clears on stop so same takeoverId can be reused after stop`() = runBlocking {
        val controller = buildController()

        // Emit first notification to register the ID
        val job1 = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "t-after-stop",
            taskId = "task-stop",
            traceId = "trace-stop",
            reason = "before stop",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        job1.join()

        // stop() clears the dedup set (via closeAttachedSession)
        controller.stop()

        // After stop, the same takeoverId should be emittable again
        var afterStop: TakeoverFallbackEvent? = null
        val job2 = launch { afterStop = controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "t-after-stop",
            taskId = "task-stop",
            traceId = "trace-stop",
            reason = "after stop — new session context",
            cause = TakeoverFallbackEvent.Cause.DISCONNECT
        )
        job2.join()

        assertNotNull(
            "Same takeoverId must be emittable again after stop() clears deduplication state",
            afterStop
        )
    }

    @Test
    fun `deduplication does not suppress events with different causes for different IDs`() = runBlocking {
        val controller = buildController()
        val causes = TakeoverFallbackEvent.Cause.values()
        val received = mutableListOf<TakeoverFallbackEvent>()

        for ((i, cause) in causes.withIndex()) {
            val uniqueId = "t-cause-$i"
            val job = launch { received.add(controller.takeoverFailure.first()) }
            controller.notifyTakeoverFailed(
                takeoverId = uniqueId,
                taskId = "task-$i",
                traceId = "trace-$i",
                reason = "cause test $cause",
                cause = cause
            )
            job.join()
        }

        assertEquals(
            "One event per distinct takeoverId must be emitted regardless of cause variety",
            causes.size,
            received.size
        )
        val receivedCauses = received.map { it.cause }.toSet()
        assertEquals(
            "All four causes must appear in the received events",
            causes.toSet(),
            receivedCauses
        )
    }

    @Test
    fun `large number of distinct takeoverIds all emit without false deduplication`() = runBlocking {
        val controller = buildController()
        val count = 20
        val receivedIds = mutableListOf<String>()

        for (i in 0 until count) {
            val id = "t-bulk-$i"
            val job = launch { receivedIds.add(controller.takeoverFailure.first().takeoverId) }
            controller.notifyTakeoverFailed(
                takeoverId = id,
                taskId = "task-bulk-$i",
                traceId = "trace-bulk-$i",
                reason = "bulk test $i",
                cause = TakeoverFallbackEvent.Cause.CANCELLED
            )
            job.join()
        }

        assertEquals(
            "All $count distinct takeoverIds must produce their own event",
            count,
            receivedIds.size
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Cross-path regression protection
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `stop resets both isRemoteExecutionActive and clears dedup state`() = runBlocking {
        val controller = buildController()

        // Set up in-flight state
        controller.onRemoteTaskStarted()
        assertTrue(controller.isRemoteExecutionActive.value)

        // Emit a failure event so the dedup set is populated
        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "t-combined",
            taskId = "task-combined",
            traceId = "trace-combined",
            reason = "pre-stop failure",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        job.join()

        // stop() should reset everything
        controller.stop()

        assertFalse(
            "stop() must clear isRemoteExecutionActive",
            controller.isRemoteExecutionActive.value
        )
        // Verify dedup state is cleared: same ID should emit again
        var afterStopEvent: TakeoverFallbackEvent? = null
        val job2 = launch { afterStopEvent = controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "t-combined",
            taskId = "task-combined-2",
            traceId = "trace-combined-2",
            reason = "post-stop failure",
            cause = TakeoverFallbackEvent.Cause.DISCONNECT
        )
        job2.join()
        assertNotNull(
            "After stop(), the same takeoverId must emit again (dedup state reset)",
            afterStopEvent
        )
    }

    @Test
    fun `notifyTakeoverFailed does not affect isRemoteExecutionActive`() = runBlocking {
        val controller = buildController()
        controller.onRemoteTaskStarted()
        assertTrue(controller.isRemoteExecutionActive.value)

        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "t-no-active-change",
            taskId = "task-nac",
            traceId = "trace-nac",
            reason = "failure must not clear active flag",
            cause = TakeoverFallbackEvent.Cause.TIMEOUT
        )
        job.join()

        assertTrue(
            "notifyTakeoverFailed must not modify isRemoteExecutionActive " +
                "(onRemoteTaskFinished is responsible for clearing it)",
            controller.isRemoteExecutionActive.value
        )
    }

    @Test
    fun `onRemoteTaskFinished does not close attachedSession`() {
        val controller = buildController()
        val sessionBefore = controller.attachedSession.value
        controller.onRemoteTaskStarted()
        controller.onRemoteTaskFinished()
        assertEquals(
            "onRemoteTaskFinished must not create or destroy the attachedSession",
            sessionBefore,
            controller.attachedSession.value
        )
    }

    @Test
    fun `STATUS_DISABLED and STATUS_CANCELLED are both not-success and distinct`() {
        val disabledP = UnifiedResultPresentation.fromLocalResult(makeDisabledResult())
        val cancelledP = UnifiedResultPresentation.fromLocalResult(makeCancelledResult())
        assertFalse("STATUS_DISABLED must not be success", disabledP.isSuccess)
        assertFalse("STATUS_CANCELLED must not be success", cancelledP.isSuccess)
        assertFalse(
            "STATUS_DISABLED and STATUS_CANCELLED must have distinct outcome strings",
            disabledP.outcome == cancelledP.outcome
        )
    }

    @Test
    fun `all four LocalLoopResult statuses map to distinct outcomes`() {
        val outcomes = setOf(
            UnifiedResultPresentation.fromLocalResult(makeSuccessResult()).outcome,
            UnifiedResultPresentation.fromLocalResult(makeFailedResult()).outcome,
            UnifiedResultPresentation.fromLocalResult(makeCancelledResult()).outcome,
            UnifiedResultPresentation.fromLocalResult(makeDisabledResult()).outcome
        )
        assertEquals(
            "All four LocalLoopResult status values must produce distinct outcome strings",
            4,
            outcomes.size
        )
    }

    @Test
    fun `server message presentation remains success regardless of local execution state`() {
        val controller = buildController()
        controller.onRemoteTaskStarted()
        // Even while a remote task is in-flight, server message presentation is success
        val p = UnifiedResultPresentation.fromServerMessage("Task completed on remote device")
        assertTrue(
            "fromServerMessage must always produce isSuccess=true regardless of local execution state",
            p.isSuccess
        )
        controller.onRemoteTaskFinished()
    }

    @Test
    fun `isRemoteExecutionActive is false after start and finish without intermediate access`() {
        val controller = buildController()
        controller.onRemoteTaskStarted()
        controller.onRemoteTaskFinished()
        // Access the value only after both events — must be false, never "stuck" at true
        assertFalse(
            "isRemoteExecutionActive must be false when all tasks have finished",
            controller.isRemoteExecutionActive.value
        )
    }
}
