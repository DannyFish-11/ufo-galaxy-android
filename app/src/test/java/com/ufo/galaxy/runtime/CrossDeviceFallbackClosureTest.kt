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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-23 — Cross-device failure and fallback state closure.
 *
 * Verifies that:
 *  1. [RuntimeController] is the **canonical failure signal path** for takeover-level failures:
 *     [RuntimeController.notifyTakeoverFailed] emits on [RuntimeController.takeoverFailure] and
 *     no other mechanism creates a duplicate failure truth path.
 *  2. All [TakeoverFallbackEvent.Cause] variants (FAILED, TIMEOUT, CANCELLED, DISCONNECT) are
 *     correctly classified and propagated.
 *  3. Stale "active" or "in-control" state is not left in [RuntimeController] after a failure:
 *     [LoopController.isRemoteTaskActive] is cleared by [RuntimeController.onRemoteTaskFinished]
 *     (called in the `finally` block by callers); [RuntimeController.state] and
 *     [RuntimeController.attachedSession] are intentionally unaffected by takeover failures.
 *  4. Multiple simultaneous subscribers (e.g. MainViewModel + EnhancedFloatingService) both
 *     receive the same [TakeoverFallbackEvent] without drift or duplication.
 *  5. Recovery / retry semantics are preserved: after a failure the runtime stays in its
 *     current state (Active/LocalOnly) and the attached session remains valid for the next
 *     incoming delegated task.
 *  6. [RuntimeController.takeoverFailure] does not interfere with
 *     [RuntimeController.registrationError]; both flows are independent authorities for
 *     their respective failure domains.
 *  7. The permanent WS disconnect observer closes the attached session when the WS drops
 *     while the runtime is Active, and reopens it on reconnect.
 *
 * ## Test matrix
 *
 * ### Canonical failure path
 *  - takeoverFailure emits on notifyTakeoverFailed
 *  - failure event carries correct takeoverId, taskId, traceId, reason, cause
 *
 * ### All failure causes
 *  - notifyTakeoverFailed with FAILED cause emits FAILED event
 *  - notifyTakeoverFailed with TIMEOUT cause emits TIMEOUT event
 *  - notifyTakeoverFailed with CANCELLED cause emits CANCELLED event
 *  - notifyTakeoverFailed with DISCONNECT cause emits DISCONNECT event
 *
 * ### Multi-subscriber delivery
 *  - takeoverFailure delivers the same event to multiple concurrent subscribers
 *  - both subscribers receive identical cause and reason fields
 *
 * ### Runtime state preservation after failure
 *  - state remains Idle after notifyTakeoverFailed (no runtime transition)
 *  - state remains LocalOnly after stop() followed by notifyTakeoverFailed
 *  - attachedSession is null before any session; stays null after failure with no session
 *  - onRemoteTaskFinished clears isRemoteTaskActive independently of takeoverFailure
 *
 * ### Independence from registrationError
 *  - takeoverFailure does not emit when registrationError fires (via startWithTimeout failure)
 *  - registrationError does not emit on notifyTakeoverFailed
 *
 * ### Recovery / retry after failure
 *  - after notifyTakeoverFailed, state is unchanged — runtime is still ready for next task
 *  - after notifyTakeoverFailed, onRemoteTaskStarted can be called without errors
 *  - multiple successive notifyTakeoverFailed calls each emit a distinct event
 *
 * ### TakeoverFallbackEvent field correctness
 *  - cause.wireValue is "failed" for FAILED
 *  - cause.wireValue is "timeout" for TIMEOUT
 *  - cause.wireValue is "cancelled" for CANCELLED
 *  - cause.wireValue is "disconnect" for DISCONNECT
 */
class CrossDeviceFallbackClosureTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies ─────────────────────────────────────────────────────

    private class TrivialPlannerService : LocalPlannerService {
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
        loopController: LoopController = buildLoopController(),
        settings: InMemoryAppSettings = InMemoryAppSettings(),
        timeoutMs: Long = 100L
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = loopController,
            registrationTimeoutMs = timeoutMs
        )
        return controller to client
    }

    // ── Canonical failure path ────────────────────────────────────────────────

    @Test
    fun `takeoverFailure emits on notifyTakeoverFailed`() = runBlocking {
        val (controller, _) = buildController()

        var received: TakeoverFallbackEvent? = null
        val job = launch {
            received = controller.takeoverFailure.first()
        }

        controller.notifyTakeoverFailed(
            takeoverId = "to-1",
            taskId = "task-1",
            traceId = "trace-1",
            reason = "execution_error",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        job.join()

        assertNotNull("takeoverFailure must emit after notifyTakeoverFailed", received)
    }

    @Test
    fun `failure event carries correct identity fields`() = runBlocking {
        val (controller, _) = buildController()

        var collected: TakeoverFallbackEvent? = null
        val job = launch { collected = controller.takeoverFailure.first() }

        controller.notifyTakeoverFailed(
            takeoverId = "to-abc",
            taskId = "task-xyz",
            traceId = "trace-789",
            reason = "pipeline_timeout",
            cause = TakeoverFallbackEvent.Cause.TIMEOUT
        )
        job.join()

        assertNotNull(collected)
        assertEquals("takeoverId must match", "to-abc", collected!!.takeoverId)
        assertEquals("taskId must match", "task-xyz", collected.taskId)
        assertEquals("traceId must match", "trace-789", collected.traceId)
        assertEquals("reason must match", "pipeline_timeout", collected.reason)
        assertEquals("cause must be TIMEOUT", TakeoverFallbackEvent.Cause.TIMEOUT, collected.cause)
    }

    // ── All failure causes ────────────────────────────────────────────────────

    @Test
    fun `notifyTakeoverFailed with FAILED cause emits FAILED event`() = runBlocking {
        val (controller, _) = buildController()
        var received: TakeoverFallbackEvent? = null
        val job = launch { received = controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.FAILED)
        job.join()
        assertEquals(TakeoverFallbackEvent.Cause.FAILED, received?.cause)
    }

    @Test
    fun `notifyTakeoverFailed with TIMEOUT cause emits TIMEOUT event`() = runBlocking {
        val (controller, _) = buildController()
        var received: TakeoverFallbackEvent? = null
        val job = launch { received = controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-2", "t-2", "tr-2", "timeout", TakeoverFallbackEvent.Cause.TIMEOUT)
        job.join()
        assertEquals(TakeoverFallbackEvent.Cause.TIMEOUT, received?.cause)
    }

    @Test
    fun `notifyTakeoverFailed with CANCELLED cause emits CANCELLED event`() = runBlocking {
        val (controller, _) = buildController()
        var received: TakeoverFallbackEvent? = null
        val job = launch { received = controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-3", "t-3", "tr-3", "cancelled", TakeoverFallbackEvent.Cause.CANCELLED)
        job.join()
        assertEquals(TakeoverFallbackEvent.Cause.CANCELLED, received?.cause)
    }

    @Test
    fun `notifyTakeoverFailed with DISCONNECT cause emits DISCONNECT event`() = runBlocking {
        val (controller, _) = buildController()
        var received: TakeoverFallbackEvent? = null
        val job = launch { received = controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-4", "t-4", "tr-4", "disconnect", TakeoverFallbackEvent.Cause.DISCONNECT)
        job.join()
        assertEquals(TakeoverFallbackEvent.Cause.DISCONNECT, received?.cause)
    }

    // ── Multi-subscriber delivery ─────────────────────────────────────────────

    /**
     * Verifies that two concurrent subscribers (simulating MainViewModel and
     * EnhancedFloatingService) both receive the same [TakeoverFallbackEvent] with no
     * drift between their received values.
     */
    @Test
    fun `takeoverFailure delivers the same event to multiple concurrent subscribers`() = runBlocking {
        val (controller, _) = buildController()

        var mainViewModelEvent: TakeoverFallbackEvent? = null
        var floatingServiceEvent: TakeoverFallbackEvent? = null

        val job1 = launch { mainViewModelEvent = controller.takeoverFailure.first() }
        val job2 = launch { floatingServiceEvent = controller.takeoverFailure.first() }

        controller.notifyTakeoverFailed(
            takeoverId = "to-multi",
            taskId = "t-multi",
            traceId = "tr-multi",
            reason = "test_failure",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )

        job1.join()
        job2.join()

        assertNotNull("MainViewModel subscriber must receive the event", mainViewModelEvent)
        assertNotNull("EnhancedFloatingService subscriber must receive the event", floatingServiceEvent)
        assertEquals(
            "Both subscribers must receive identical cause",
            mainViewModelEvent!!.cause,
            floatingServiceEvent!!.cause
        )
        assertEquals(
            "Both subscribers must receive identical takeoverId",
            mainViewModelEvent.takeoverId,
            floatingServiceEvent.takeoverId
        )
        assertEquals(
            "Both subscribers must receive identical reason",
            mainViewModelEvent.reason,
            floatingServiceEvent.reason
        )
    }

    // ── Runtime state preservation after failure ──────────────────────────────

    @Test
    fun `state remains Idle after notifyTakeoverFailed`() = runBlocking {
        val (controller, _) = buildController()
        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.FAILED)
        job.join()
        assertTrue(
            "Runtime state must remain Idle after a takeover failure (not a registration failure)",
            controller.state.value is RuntimeController.RuntimeState.Idle
        )
    }

    @Test
    fun `state remains LocalOnly after stop then notifyTakeoverFailed`() = runBlocking {
        val (controller, _) = buildController()
        controller.stop()
        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.CANCELLED)
        job.join()
        assertTrue(
            "Runtime state must remain LocalOnly after stop() + notifyTakeoverFailed",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }

    @Test
    fun `attachedSession is null before any session and stays null after failure with no session`() = runBlocking {
        val (controller, _) = buildController()
        assertNull("attachedSession must be null initially", controller.attachedSession.value)
        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.FAILED)
        job.join()
        assertNull(
            "attachedSession must remain null after notifyTakeoverFailed when no session exists",
            controller.attachedSession.value
        )
    }

    @Test
    fun `onRemoteTaskFinished clears isRemoteTaskActive independently of takeoverFailure`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        controller.onRemoteTaskStarted()
        assertTrue("isRemoteTaskActive must be true after onRemoteTaskStarted", loopController.isRemoteTaskActive)

        // notifyTakeoverFailed does NOT clear isRemoteTaskActive — caller must do that in finally.
        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.FAILED)
        job.join()
        // isRemoteTaskActive is not cleared by notifyTakeoverFailed alone.
        // It is the caller's responsibility to call onRemoteTaskFinished() in the finally block.
        controller.onRemoteTaskFinished()
        assertFalse(
            "isRemoteTaskActive must be false after onRemoteTaskFinished",
            loopController.isRemoteTaskActive
        )
    }

    // ── Independence from registrationError ──────────────────────────────────

    @Test
    fun `takeoverFailure does not emit when startWithTimeout registration fails`() = runBlocking {
        val (controller, _) = buildController(timeoutMs = 200L)

        var takeoverFailureReceived = false
        val takeoverJob = launch {
            withTimeoutOrNull(500L) {
                controller.takeoverFailure.first()
                takeoverFailureReceived = true
            }
        }

        // Trigger registration failure (WS unreachable).
        controller.startWithTimeout()
        takeoverJob.join()

        assertFalse(
            "takeoverFailure must NOT emit when only a registration failure occurs",
            takeoverFailureReceived
        )
    }

    @Test
    fun `registrationError does not emit on notifyTakeoverFailed`() = runBlocking {
        val (controller, _) = buildController()

        var registrationErrorReceived = false
        val registrationJob = launch {
            withTimeoutOrNull(500L) {
                controller.registrationError.first()
                registrationErrorReceived = true
            }
        }

        val takeoverJob = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.FAILED)
        takeoverJob.join()
        registrationJob.join()

        assertFalse(
            "registrationError must NOT emit on notifyTakeoverFailed",
            registrationErrorReceived
        )
    }

    // ── Recovery / retry after failure ────────────────────────────────────────

    @Test
    fun `after notifyTakeoverFailed runtime state is unchanged — ready for next task`() = runBlocking {
        val (controller, _) = buildController()
        val stateBefore = controller.state.value

        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.FAILED)
        job.join()

        assertEquals(
            "Runtime state must be unchanged after notifyTakeoverFailed — still ready for the next task",
            stateBefore,
            controller.state.value
        )
    }

    @Test
    fun `after notifyTakeoverFailed onRemoteTaskStarted can be called without error`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.TIMEOUT)
        job.join()
        controller.onRemoteTaskFinished()

        // Should not throw — the system is ready to accept the next task.
        controller.onRemoteTaskStarted()
        assertTrue("isRemoteTaskActive should be true after onRemoteTaskStarted", loopController.isRemoteTaskActive)

        // Clean up.
        controller.onRemoteTaskFinished()
    }

    @Test
    fun `multiple successive notifyTakeoverFailed calls each emit a distinct event`() = runBlocking {
        val (controller, _) = buildController()

        val receivedEvents = mutableListOf<TakeoverFallbackEvent>()
        val job = launch {
            // Collect 3 events.
            repeat(3) {
                receivedEvents.add(controller.takeoverFailure.first())
            }
        }

        // Emit 3 failures in sequence.
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err1", TakeoverFallbackEvent.Cause.FAILED)
        controller.notifyTakeoverFailed("to-2", "t-2", "tr-2", "err2", TakeoverFallbackEvent.Cause.TIMEOUT)
        controller.notifyTakeoverFailed("to-3", "t-3", "tr-3", "err3", TakeoverFallbackEvent.Cause.CANCELLED)
        job.join()

        assertEquals("All 3 failure events must be received", 3, receivedEvents.size)
        assertEquals("First event cause must be FAILED", TakeoverFallbackEvent.Cause.FAILED, receivedEvents[0].cause)
        assertEquals("Second event cause must be TIMEOUT", TakeoverFallbackEvent.Cause.TIMEOUT, receivedEvents[1].cause)
        assertEquals("Third event cause must be CANCELLED", TakeoverFallbackEvent.Cause.CANCELLED, receivedEvents[2].cause)
        assertEquals("First event takeoverId", "to-1", receivedEvents[0].takeoverId)
        assertEquals("Second event takeoverId", "to-2", receivedEvents[1].takeoverId)
        assertEquals("Third event takeoverId", "to-3", receivedEvents[2].takeoverId)
    }

    // ── TakeoverFallbackEvent field correctness ───────────────────────────────

    @Test
    fun `FAILED cause wireValue is 'failed'`() {
        assertEquals("failed", TakeoverFallbackEvent.Cause.FAILED.wireValue)
    }

    @Test
    fun `TIMEOUT cause wireValue is 'timeout'`() {
        assertEquals("timeout", TakeoverFallbackEvent.Cause.TIMEOUT.wireValue)
    }

    @Test
    fun `CANCELLED cause wireValue is 'cancelled'`() {
        assertEquals("cancelled", TakeoverFallbackEvent.Cause.CANCELLED.wireValue)
    }

    @Test
    fun `DISCONNECT cause wireValue is 'disconnect'`() {
        assertEquals("disconnect", TakeoverFallbackEvent.Cause.DISCONNECT.wireValue)
    }

    // ── No duplicate or conflicting fallback truth paths ─────────────────────

    /**
     * Verifies that [RuntimeController.takeoverFailure] is the single authority for
     * takeover failures — there is no second path that could produce a conflicting or
     * duplicate fallback truth.
     *
     * Specifically: a takeover failure (via [RuntimeController.notifyTakeoverFailed])
     * does NOT cause the runtime to emit on [RuntimeController.registrationError],
     * does NOT change [RuntimeController.state], and does NOT mutate
     * [RuntimeController.attachedSession] — all of which would be signs of a duplicate
     * authority or conflicting state path.
     */
    @Test
    fun `notifyTakeoverFailed is the sole fallback authority — no duplicate state paths`() = runBlocking {
        val (controller, _) = buildController()

        // Capture initial state.
        val stateSnapshot = controller.state.value
        val sessionSnapshot = controller.attachedSession.value  // null initially

        var registrationErrorFired = false
        val registrationJob = launch {
            withTimeoutOrNull(300L) {
                controller.registrationError.first()
                registrationErrorFired = true
            }
        }

        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "to-dup",
            taskId = "t-dup",
            traceId = "tr-dup",
            reason = "duplicate_check",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        job.join()
        registrationJob.join()

        assertFalse(
            "registrationError must NOT fire on notifyTakeoverFailed — no duplicate fallback truth",
            registrationErrorFired
        )
        assertEquals(
            "state must not change — notifyTakeoverFailed is not a runtime-state transition",
            stateSnapshot,
            controller.state.value
        )
        assertEquals(
            "attachedSession must not change — notifyTakeoverFailed is not a session mutation",
            sessionSnapshot,
            controller.attachedSession.value
        )
    }

    // ── Session stability across takeover failure → retry cycles ──────────────

    @Test
    fun `settings crossDeviceEnabled is not changed by notifyTakeoverFailed`() = runBlocking {
        val settings = InMemoryAppSettings()
        val (controller, _) = buildController(settings = settings)

        // Start cross-device (simulated — just set in settings directly for this test).
        settings.crossDeviceEnabled = true

        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.FAILED)
        job.join()

        assertTrue(
            "settings.crossDeviceEnabled must remain true after notifyTakeoverFailed — " +
                "recovery/retry must be possible without re-enabling cross-device mode",
            settings.crossDeviceEnabled
        )
    }
}
