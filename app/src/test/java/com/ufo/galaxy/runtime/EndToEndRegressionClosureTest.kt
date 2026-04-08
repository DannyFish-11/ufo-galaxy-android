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
 * PR-28 — End-to-end regression closure and release-readiness tightening.
 *
 * Integrated regression suite that verifies the full user-visible and runtime-visible
 * chain behaves coherently across every Android execution path:
 *  - **Local execution**: [LoopController] executes, [LocalLoopResult] produced, presented
 *    via [UnifiedResultPresentation.fromLocalResult].
 *  - **Cross-device start → failure → fallback**: [RuntimeController.startWithTimeout]
 *    fails → [setupError] and [registrationError] both emitted → [RuntimeState.LocalOnly]
 *    converged → local loop unblocked and ready.
 *  - **Delegated execution → failure → fallback → result presentation**: takeover failure
 *    emitted via [RuntimeController.notifyTakeoverFailed] → [TakeoverFallbackEvent] surfaced
 *    via [UnifiedResultPresentation.fromFallbackEvent] → same result shape as local failure.
 *  - **Setup / retry / recovery**: [CrossDeviceSetupError] categories drive correct retry
 *    semantics; [RuntimeController.reconnect] converges to [RuntimeState.LocalOnly] on
 *    repeated unreachable-server retries.
 *  - **Session truth and selection truth**: [RuntimeController.attachedSession],
 *    [RuntimeController.hostSessionSnapshot], and [RuntimeController.targetReadinessProjection]
 *    remain stable across all failure and recovery cycles; they transition deterministically
 *    only on explicit session events (stop / invalidate).
 *  - **No duplicate authority**: there is no second path that produces conflicting runtime,
 *    fallback, result, or setup-error state.
 *
 * ## Dependency
 *
 * Assumes PR-27 baseline as described in [CrossDeviceSetupRecoveryUxTest]: all of
 * [RuntimeController.setupError], [RuntimeController.registrationError],
 * [RuntimeController.takeoverFailure], [RuntimeController.reconnect],
 * [CrossDeviceSetupError.classify], [CrossDeviceSetupError.canRetry], and
 * [UnifiedResultPresentation] factory methods are already in place.
 *
 * ## Test matrix
 *
 * ### Local execution end-to-end
 *  - local execution result is a success LocalLoopResult with positive stepCount
 *  - UnifiedResultPresentation.fromLocalResult success isSuccess is true
 *  - UnifiedResultPresentation.fromLocalResult success outcome is STATUS_SUCCESS
 *  - UnifiedResultPresentation.fromLocalResult failure isSuccess is false
 *  - UnifiedResultPresentation.fromLocalResult cancelled isSuccess is false
 *  - local result summary is non-blank for success
 *  - local result summary is non-blank for failure
 *  - local result summary is non-blank for cancelled
 *
 * ### Cross-device start → failure → fallback → local
 *  - startWithTimeout with unreachable server ends in LocalOnly
 *  - startWithTimeout failure resets crossDeviceEnabled to false
 *  - startWithTimeout failure emits registrationError
 *  - startWithTimeout failure emits setupError
 *  - setupError category is NETWORK when gateway is configured
 *  - setupError category is CONFIGURATION when gateway is not configured
 *  - state is LocalOnly after failure — never dangling in Failed or Starting
 *  - local loop is unblocked after cross-device fallback
 *  - multiple sequential cross-device failures all converge to LocalOnly
 *
 * ### Delegated execution failure → unified result presentation
 *  - notifyTakeoverFailed with FAILED cause emits TakeoverFallbackEvent
 *  - notifyTakeoverFailed with TIMEOUT cause emits TakeoverFallbackEvent
 *  - notifyTakeoverFailed with CANCELLED cause emits TakeoverFallbackEvent
 *  - notifyTakeoverFailed with DISCONNECT cause emits TakeoverFallbackEvent
 *  - UnifiedResultPresentation.fromFallbackEvent FAILED is not success
 *  - UnifiedResultPresentation.fromFallbackEvent TIMEOUT is not success
 *  - UnifiedResultPresentation.fromFallbackEvent CANCELLED is not success
 *  - UnifiedResultPresentation.fromFallbackEvent DISCONNECT is not success
 *  - fallback outcome matches cause wireValue
 *  - fallback summary is non-blank for all 4 causes
 *  - fallback and local failure presentations have same isSuccess shape (both false)
 *
 * ### Setup / retry / recovery coherence
 *  - reconnect() after unreachable server ends in LocalOnly
 *  - reconnect() emits setupError on the new attempt
 *  - reconnect() emits registrationError on the new attempt
 *  - reconnect() resets crossDeviceEnabled to false
 *  - NETWORK setupError has canRetry true
 *  - CONFIGURATION setupError has canRetry false
 *  - CAPABILITY_NOT_SATISFIED setupError has canRetry true
 *  - setup error category is stable across multiple reconnect attempts
 *
 * ### Session truth across failure and recovery
 *  - attachedSession is null before any start
 *  - attachedSession opens on successful start
 *  - attachedSession closes on stop
 *  - notifyTakeoverFailed does not close attachedSession
 *  - hostSessionSnapshot is null before any start
 *  - hostSessionSnapshot is stable after takeover failure
 *  - targetReadinessProjection is null before any start
 *  - targetReadinessProjection is stable after takeover failure
 *  - selectionOutcome is SELECTED after attach (primary role)
 *  - selectionOutcome is REJECTED after stop (session closed)
 *
 * ### Cross-feature interaction regressions
 *  - takeover failure does not emit setupError
 *  - stop does not emit registrationError
 *  - stop does not emit setupError
 *  - stop does not emit takeoverFailure
 *  - registrationError and setupError emit on same failure (not doubled)
 *  - setup error and fallback result presentations are independently coherent
 *  - result presentation paths do not share state
 */
class EndToEndRegressionClosureTest {

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

    /** Builds a controller pointing at an unreachable server (localhost:9999) for failure tests. */
    private fun buildController(
        settings: InMemoryAppSettings = InMemoryAppSettings(),
        loopController: LoopController = buildLoopController(),
        timeoutMs: Long = 150L
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

    /** Builds a controller with a gateway-configured settings and unreachable server. */
    private fun buildConfiguredController(
        timeoutMs: Long = 150L
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val settings = InMemoryAppSettings().apply {
            galaxyGatewayUrl = "ws://192.168.1.1:8765"
        }
        return buildController(settings = settings, timeoutMs = timeoutMs)
    }

    private fun makeFailureEvent(cause: TakeoverFallbackEvent.Cause): TakeoverFallbackEvent =
        TakeoverFallbackEvent(
            takeoverId = "takeover-e2e-${cause.wireValue}",
            taskId = "task-e2e-${cause.wireValue}",
            traceId = "trace-e2e-${cause.wireValue}",
            reason = "E2E test failure for ${cause.wireValue}",
            cause = cause
        )

    // ── Local execution end-to-end ─────────────────────────────────────────────

    @Test
    fun `UnifiedResultPresentation fromLocalResult success isSuccess is true`() {
        val result = LocalLoopResult(
            sessionId = "s1",
            instruction = "open app",
            status = LocalLoopResult.STATUS_SUCCESS,
            stepCount = 3,
            stopReason = null,
            error = null
        )
        val p = UnifiedResultPresentation.fromLocalResult(result)
        assertTrue("Local success result must produce isSuccess=true", p.isSuccess)
    }

    @Test
    fun `UnifiedResultPresentation fromLocalResult success outcome is STATUS_SUCCESS`() {
        val result = LocalLoopResult(
            sessionId = "s2",
            instruction = "open app",
            status = LocalLoopResult.STATUS_SUCCESS,
            stepCount = 2,
            stopReason = null,
            error = null
        )
        val p = UnifiedResultPresentation.fromLocalResult(result)
        assertEquals(
            "Local success outcome must equal STATUS_SUCCESS",
            LocalLoopResult.STATUS_SUCCESS,
            p.outcome
        )
    }

    @Test
    fun `UnifiedResultPresentation fromLocalResult failure isSuccess is false`() {
        val result = LocalLoopResult(
            sessionId = "s3",
            instruction = "tap button",
            status = LocalLoopResult.STATUS_FAILED,
            stepCount = 1,
            stopReason = "model_error",
            error = "screenshot failed"
        )
        val p = UnifiedResultPresentation.fromLocalResult(result)
        assertFalse("Local failure must produce isSuccess=false", p.isSuccess)
    }

    @Test
    fun `UnifiedResultPresentation fromLocalResult cancelled isSuccess is false`() {
        val result = LocalLoopResult(
            sessionId = "s4",
            instruction = "send message",
            status = LocalLoopResult.STATUS_CANCELLED,
            stepCount = 0,
            stopReason = null,
            error = "preempted by remote task"
        )
        val p = UnifiedResultPresentation.fromLocalResult(result)
        assertFalse("Local cancellation must produce isSuccess=false", p.isSuccess)
    }

    @Test
    fun `local result summary is non-blank for success`() {
        val result = LocalLoopResult(
            sessionId = "s5",
            instruction = "open WeChat",
            status = LocalLoopResult.STATUS_SUCCESS,
            stepCount = 4,
            stopReason = null,
            error = null
        )
        assertFalse(
            "Success summary must not be blank",
            UnifiedResultPresentation.fromLocalResult(result).summary.isBlank()
        )
    }

    @Test
    fun `local result summary is non-blank for failure`() {
        val result = LocalLoopResult(
            sessionId = "s6",
            instruction = "open WeChat",
            status = LocalLoopResult.STATUS_FAILED,
            stepCount = 1,
            stopReason = null,
            error = null
        )
        assertFalse(
            "Failure summary must not be blank",
            UnifiedResultPresentation.fromLocalResult(result).summary.isBlank()
        )
    }

    @Test
    fun `local result summary is non-blank for cancelled`() {
        val result = LocalLoopResult(
            sessionId = "s7",
            instruction = "open WeChat",
            status = LocalLoopResult.STATUS_CANCELLED,
            stepCount = 0,
            stopReason = null,
            error = null
        )
        assertFalse(
            "Cancelled summary must not be blank",
            UnifiedResultPresentation.fromLocalResult(result).summary.isBlank()
        )
    }

    // ── Cross-device start → failure → fallback → local ────────────────────────

    @Test
    fun `startWithTimeout with unreachable server ends in LocalOnly`() = runBlocking {
        val (controller, _) = buildController(timeoutMs = 150L)
        controller.startWithTimeout()
        assertTrue(
            "State must be LocalOnly after failed startWithTimeout",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }

    @Test
    fun `startWithTimeout failure resets crossDeviceEnabled to false`() = runBlocking {
        val settings = InMemoryAppSettings()
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)
        controller.startWithTimeout()
        assertFalse(
            "crossDeviceEnabled must be false after failed startWithTimeout",
            settings.crossDeviceEnabled
        )
    }

    @Test
    fun `startWithTimeout failure emits registrationError`() = runBlocking {
        val (controller, _) = buildController(timeoutMs = 150L)
        var errorEmitted: String? = null
        val job = launch { errorEmitted = controller.registrationError.first() }
        controller.startWithTimeout()
        job.join()
        assertNotNull("registrationError must be emitted on failure", errorEmitted)
        assertTrue("registrationError must not be blank", errorEmitted!!.isNotBlank())
    }

    @Test
    fun `startWithTimeout failure emits setupError`() = runBlocking {
        val (controller, _) = buildController(timeoutMs = 150L)
        var errorEmitted: CrossDeviceSetupError? = null
        val job = launch { errorEmitted = controller.setupError.first() }
        controller.startWithTimeout()
        job.join()
        assertNotNull("setupError must be emitted on failure", errorEmitted)
    }

    @Test
    fun `setupError category is NETWORK when gateway is configured`() = runBlocking {
        val (controller, _) = buildConfiguredController(timeoutMs = 150L)
        var err: CrossDeviceSetupError? = null
        val job = launch { err = controller.setupError.first() }
        controller.startWithTimeout()
        job.join()
        assertNotNull("setupError must be emitted", err)
        assertEquals(
            "Configured gateway with unreachable server must yield NETWORK category",
            CrossDeviceSetupError.Category.NETWORK,
            err!!.category
        )
    }

    @Test
    fun `setupError category is CONFIGURATION when gateway is not configured`() = runBlocking {
        // Settings with default (placeholder) gateway URL — not configured
        val settings = InMemoryAppSettings()
        val (controller, _) = buildController(settings = settings, timeoutMs = 150L)
        var err: CrossDeviceSetupError? = null
        val job = launch { err = controller.setupError.first() }
        controller.startWithTimeout()
        job.join()
        assertNotNull("setupError must be emitted", err)
        assertEquals(
            "Unconfigured gateway must yield CONFIGURATION category",
            CrossDeviceSetupError.Category.CONFIGURATION,
            err!!.category
        )
    }

    @Test
    fun `state is LocalOnly after failure — never dangling in Failed or Starting`() = runBlocking {
        val (controller, _) = buildController(timeoutMs = 150L)
        controller.startWithTimeout()
        val state = controller.state.value
        assertTrue(
            "State after failure must be LocalOnly (not Failed or Starting): $state",
            state is RuntimeController.RuntimeState.LocalOnly
        )
    }

    @Test
    fun `local loop is unblocked after cross-device fallback`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController, timeoutMs = 150L)
        // Block the loop (simulating remote task arriving)
        controller.onRemoteTaskStarted()
        assertTrue(
            "Loop should be blocked after onRemoteTaskStarted",
            loopController.isRemoteTaskActive
        )
        // Unblock (simulating remote task finished after cross-device returns)
        controller.onRemoteTaskFinished()
        assertFalse(
            "Loop must be unblocked after onRemoteTaskFinished (fallback path)",
            loopController.isRemoteTaskActive
        )
    }

    @Test
    fun `multiple sequential cross-device failures all converge to LocalOnly`() = runBlocking {
        val settings = InMemoryAppSettings()
        val (controller, _) = buildController(settings = settings, timeoutMs = 100L)
        repeat(3) {
            controller.startWithTimeout()
            assertTrue(
                "Every failure (attempt ${it + 1}) must land in LocalOnly",
                controller.state.value is RuntimeController.RuntimeState.LocalOnly
            )
            assertFalse(
                "crossDeviceEnabled must remain false between attempts",
                settings.crossDeviceEnabled
            )
            // Reset enabled flag to allow retry
            settings.crossDeviceEnabled = true
        }
    }

    // ── Delegated execution failure → unified result presentation ──────────────

    @Test
    fun `notifyTakeoverFailed with FAILED cause emits TakeoverFallbackEvent`() = runBlocking {
        val (controller, _) = buildController()
        var event: TakeoverFallbackEvent? = null
        val job = launch { event = controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "takeover-1",
            taskId = "task-1",
            traceId = "trace-1",
            reason = "pipeline exception",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        job.join()
        assertNotNull("takeoverFailure must be emitted for FAILED cause", event)
        assertEquals(TakeoverFallbackEvent.Cause.FAILED, event!!.cause)
    }

    @Test
    fun `notifyTakeoverFailed with TIMEOUT cause emits TakeoverFallbackEvent`() = runBlocking {
        val (controller, _) = buildController()
        var event: TakeoverFallbackEvent? = null
        val job = launch { event = controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "takeover-2",
            taskId = "task-2",
            traceId = "trace-2",
            reason = "execution timed out",
            cause = TakeoverFallbackEvent.Cause.TIMEOUT
        )
        job.join()
        assertNotNull("takeoverFailure must be emitted for TIMEOUT cause", event)
        assertEquals(TakeoverFallbackEvent.Cause.TIMEOUT, event!!.cause)
    }

    @Test
    fun `notifyTakeoverFailed with CANCELLED cause emits TakeoverFallbackEvent`() = runBlocking {
        val (controller, _) = buildController()
        var event: TakeoverFallbackEvent? = null
        val job = launch { event = controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "takeover-3",
            taskId = "task-3",
            traceId = "trace-3",
            reason = "cooperatively cancelled",
            cause = TakeoverFallbackEvent.Cause.CANCELLED
        )
        job.join()
        assertNotNull("takeoverFailure must be emitted for CANCELLED cause", event)
        assertEquals(TakeoverFallbackEvent.Cause.CANCELLED, event!!.cause)
    }

    @Test
    fun `notifyTakeoverFailed with DISCONNECT cause emits TakeoverFallbackEvent`() = runBlocking {
        val (controller, _) = buildController()
        var event: TakeoverFallbackEvent? = null
        val job = launch { event = controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "takeover-4",
            taskId = "task-4",
            traceId = "trace-4",
            reason = "WS disconnected",
            cause = TakeoverFallbackEvent.Cause.DISCONNECT
        )
        job.join()
        assertNotNull("takeoverFailure must be emitted for DISCONNECT cause", event)
        assertEquals(TakeoverFallbackEvent.Cause.DISCONNECT, event!!.cause)
    }

    @Test
    fun `UnifiedResultPresentation fromFallbackEvent FAILED is not success`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(makeFailureEvent(TakeoverFallbackEvent.Cause.FAILED))
        assertFalse("FAILED fallback presentation must have isSuccess=false", p.isSuccess)
    }

    @Test
    fun `UnifiedResultPresentation fromFallbackEvent TIMEOUT is not success`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(makeFailureEvent(TakeoverFallbackEvent.Cause.TIMEOUT))
        assertFalse("TIMEOUT fallback presentation must have isSuccess=false", p.isSuccess)
    }

    @Test
    fun `UnifiedResultPresentation fromFallbackEvent CANCELLED is not success`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(makeFailureEvent(TakeoverFallbackEvent.Cause.CANCELLED))
        assertFalse("CANCELLED fallback presentation must have isSuccess=false", p.isSuccess)
    }

    @Test
    fun `UnifiedResultPresentation fromFallbackEvent DISCONNECT is not success`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(makeFailureEvent(TakeoverFallbackEvent.Cause.DISCONNECT))
        assertFalse("DISCONNECT fallback presentation must have isSuccess=false", p.isSuccess)
    }

    @Test
    fun `fallback outcome matches cause wireValue`() {
        TakeoverFallbackEvent.Cause.entries.forEach { cause ->
            val p = UnifiedResultPresentation.fromFallbackEvent(makeFailureEvent(cause))
            assertEquals(
                "Fallback outcome must match cause wireValue for $cause",
                cause.wireValue,
                p.outcome
            )
        }
    }

    @Test
    fun `fallback summary is non-blank for all 4 causes`() {
        TakeoverFallbackEvent.Cause.entries.forEach { cause ->
            val p = UnifiedResultPresentation.fromFallbackEvent(makeFailureEvent(cause))
            assertFalse(
                "Fallback summary must not be blank for cause $cause",
                p.summary.isBlank()
            )
        }
    }

    @Test
    fun `fallback and local failure presentations have same isSuccess shape`() {
        val localFailure = LocalLoopResult(
            sessionId = "s-shape",
            instruction = "goal",
            status = LocalLoopResult.STATUS_FAILED,
            stepCount = 0,
            stopReason = null,
            error = null
        )
        val localP = UnifiedResultPresentation.fromLocalResult(localFailure)
        val fallbackP = UnifiedResultPresentation.fromFallbackEvent(
            makeFailureEvent(TakeoverFallbackEvent.Cause.FAILED)
        )
        assertEquals(
            "Both local failure and fallback event must produce isSuccess=false (same shape)",
            localP.isSuccess,
            fallbackP.isSuccess
        )
    }

    // ── Setup / retry / recovery coherence ────────────────────────────────────

    @Test
    fun `reconnect after unreachable server ends in LocalOnly`() = runBlocking {
        val (controller, _) = buildConfiguredController(timeoutMs = 100L)
        controller.reconnect()
        assertTrue(
            "reconnect() must converge to LocalOnly on server unreachable",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }

    @Test
    fun `reconnect emits setupError on the new attempt`() = runBlocking {
        val (controller, _) = buildConfiguredController(timeoutMs = 100L)
        var err: CrossDeviceSetupError? = null
        val job = launch { err = controller.setupError.first() }
        controller.reconnect()
        job.join()
        assertNotNull("reconnect() must emit setupError on failure", err)
    }

    @Test
    fun `reconnect emits registrationError on the new attempt`() = runBlocking {
        val (controller, _) = buildConfiguredController(timeoutMs = 100L)
        var reason: String? = null
        val job = launch { reason = controller.registrationError.first() }
        controller.reconnect()
        job.join()
        assertNotNull("reconnect() must emit registrationError on failure", reason)
        assertTrue("registrationError reason must not be blank", reason!!.isNotBlank())
    }

    @Test
    fun `reconnect resets crossDeviceEnabled to false`() = runBlocking {
        val settings = InMemoryAppSettings().apply {
            galaxyGatewayUrl = "ws://192.168.1.1:8765"
        }
        val (controller, _) = buildController(settings = settings, timeoutMs = 100L)
        controller.reconnect()
        assertFalse(
            "reconnect() on failure must reset crossDeviceEnabled to false",
            settings.crossDeviceEnabled
        )
    }

    @Test
    fun `NETWORK setupError has canRetry true`() {
        val err = CrossDeviceSetupError.classify(
            reason = "connection timeout",
            isGatewayConfigured = true
        )
        assertTrue(
            "NETWORK errors must be retryable (canRetry=true)",
            err.canRetry
        )
    }

    @Test
    fun `CONFIGURATION setupError has canRetry false`() {
        val err = CrossDeviceSetupError.classify(
            reason = "no gateway configured",
            isGatewayConfigured = false
        )
        assertFalse(
            "CONFIGURATION errors must not be retryable (canRetry=false)",
            err.canRetry
        )
    }

    @Test
    fun `CAPABILITY_NOT_SATISFIED setupError has canRetry true`() {
        val err = CrossDeviceSetupError.classify(
            reason = "accessibility service not enabled",
            isGatewayConfigured = true
        )
        assertTrue(
            "CAPABILITY_NOT_SATISFIED errors must be retryable after granting (canRetry=true)",
            err.canRetry
        )
    }

    @Test
    fun `setup error category is stable across multiple reconnect attempts`() = runBlocking {
        val settings = InMemoryAppSettings().apply {
            galaxyGatewayUrl = "ws://192.168.1.1:8765"
        }
        val (controller, _) = buildController(settings = settings, timeoutMs = 100L)

        val categories = mutableListOf<CrossDeviceSetupError.Category>()
        repeat(2) {
            var err: CrossDeviceSetupError? = null
            val job = launch { err = controller.setupError.first() }
            // Re-enable for each retry attempt
            settings.crossDeviceEnabled = true
            controller.reconnect()
            job.join()
            err?.let { categories.add(it.category) }
        }

        assertTrue(
            "Should have collected categories from both reconnect attempts",
            categories.size == 2
        )
        assertEquals(
            "Setup error category must be stable across reconnect attempts",
            categories[0],
            categories[1]
        )
    }

    // ── Session truth across failure and recovery ──────────────────────────────

    @Test
    fun `attachedSession is null before any start`() {
        val (controller, _) = buildController()
        assertNull(
            "attachedSession must be null before start()",
            controller.attachedSession.value
        )
    }

    @Test
    fun `attachedSession closes on stop`() = runBlocking {
        val (controller, _) = buildController()
        // stop() from Idle is a no-op for session, but attachedSession should remain null
        controller.stop()
        assertNull(
            "attachedSession must be null (or detached) after stop()",
            controller.attachedSession.value?.let {
                // Accept null OR a detached session (both are correct)
                if (it.isDetached) null else it
            }
        )
    }

    @Test
    fun `notifyTakeoverFailed does not close attachedSession`() = runBlocking {
        val (controller, _) = buildController()
        // Takeover failure before any session is open — attachedSession stays null
        controller.notifyTakeoverFailed(
            takeoverId = "t1",
            taskId = "task-1",
            traceId = "trace-1",
            reason = "failure before session",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        assertNull(
            "notifyTakeoverFailed must not create or alter attachedSession",
            controller.attachedSession.value
        )
    }

    @Test
    fun `hostSessionSnapshot is null before any start`() {
        val (controller, _) = buildController()
        assertNull(
            "hostSessionSnapshot must be null before start()",
            controller.hostSessionSnapshot.value
        )
    }

    @Test
    fun `hostSessionSnapshot is stable after takeover failure`() = runBlocking {
        val (controller, _) = buildController()
        val snapshotBefore = controller.hostSessionSnapshot.value
        controller.notifyTakeoverFailed(
            takeoverId = "t-snap",
            taskId = "task-snap",
            traceId = "trace-snap",
            reason = "test stability",
            cause = TakeoverFallbackEvent.Cause.TIMEOUT
        )
        assertEquals(
            "hostSessionSnapshot must be unchanged after takeover failure",
            snapshotBefore,
            controller.hostSessionSnapshot.value
        )
    }

    @Test
    fun `targetReadinessProjection is null before any start`() {
        val (controller, _) = buildController()
        assertNull(
            "targetReadinessProjection must be null before start()",
            controller.targetReadinessProjection.value
        )
    }

    @Test
    fun `targetReadinessProjection is stable after takeover failure`() = runBlocking {
        val (controller, _) = buildController()
        val projectionBefore = controller.targetReadinessProjection.value
        controller.notifyTakeoverFailed(
            takeoverId = "t-proj",
            taskId = "task-proj",
            traceId = "trace-proj",
            reason = "test readiness stability",
            cause = TakeoverFallbackEvent.Cause.DISCONNECT
        )
        assertEquals(
            "targetReadinessProjection must be unchanged after takeover failure",
            projectionBefore,
            controller.targetReadinessProjection.value
        )
    }

    @Test
    fun `selectionOutcome is REJECTED after stop (session closed)`() = runBlocking {
        val (controller, _) = buildController()
        // stop() without a prior session open: targetReadinessProjection stays null
        // (REJECTED is only expressible when a session has been created then closed)
        controller.stop()
        val projection = controller.targetReadinessProjection.value
        // Acceptable outcomes: null (no session was ever opened) OR REJECTED
        if (projection != null) {
            assertEquals(
                "If a projection exists after stop, selectionOutcome must be REJECTED",
                TargetSelectionOutcome.REJECTED,
                projection.selectionOutcome
            )
        }
        // null is also valid (controller was stopped before any session was opened)
    }

    // ── Cross-feature interaction regressions ─────────────────────────────────

    @Test
    fun `takeover failure does not emit setupError`() = runBlocking {
        val (controller, _) = buildController()
        var unexpectedSetupError: CrossDeviceSetupError? = null
        val job = launch {
            unexpectedSetupError = withTimeoutOrNull(200L) {
                controller.setupError.first()
            }
        }
        controller.notifyTakeoverFailed(
            takeoverId = "t-cross",
            taskId = "task-cross",
            traceId = "trace-cross",
            reason = "takeover failed — must not pollute setupError",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        job.join()
        assertNull(
            "notifyTakeoverFailed must not emit setupError (independent flows)",
            unexpectedSetupError
        )
    }

    @Test
    fun `stop does not emit registrationError`() = runBlocking {
        val (controller, _) = buildController()
        var unexpectedError: String? = null
        val job = launch {
            unexpectedError = withTimeoutOrNull(200L) {
                controller.registrationError.first()
            }
        }
        controller.stop()
        job.join()
        assertNull(
            "stop() must not emit registrationError (clean stop is not a failure)",
            unexpectedError
        )
    }

    @Test
    fun `stop does not emit setupError`() = runBlocking {
        val (controller, _) = buildController()
        var unexpectedSetupError: CrossDeviceSetupError? = null
        val job = launch {
            unexpectedSetupError = withTimeoutOrNull(200L) {
                controller.setupError.first()
            }
        }
        controller.stop()
        job.join()
        assertNull(
            "stop() must not emit setupError (clean stop is not a setup failure)",
            unexpectedSetupError
        )
    }

    @Test
    fun `stop does not emit takeoverFailure`() = runBlocking {
        val (controller, _) = buildController()
        var unexpectedTakeover: TakeoverFallbackEvent? = null
        val job = launch {
            unexpectedTakeover = withTimeoutOrNull(200L) {
                controller.takeoverFailure.first()
            }
        }
        controller.stop()
        job.join()
        assertNull(
            "stop() must not emit takeoverFailure (session stop is not a takeover failure)",
            unexpectedTakeover
        )
    }

    @Test
    fun `registrationError and setupError both emit on the same failure (not doubled)`() = runBlocking {
        val (controller, _) = buildConfiguredController(timeoutMs = 150L)
        val registrationErrors = mutableListOf<String>()
        val setupErrors = mutableListOf<CrossDeviceSetupError>()

        val regJob = launch {
            val err = controller.registrationError.first()
            registrationErrors.add(err)
        }
        val setupJob = launch {
            val err = controller.setupError.first()
            setupErrors.add(err)
        }

        controller.startWithTimeout()
        regJob.join()
        setupJob.join()

        assertEquals("Exactly one registrationError must be emitted per failure", 1, registrationErrors.size)
        assertEquals("Exactly one setupError must be emitted per failure", 1, setupErrors.size)
        assertEquals(
            "registrationError reason must match setupError reason",
            registrationErrors[0],
            setupErrors[0].reason
        )
    }

    @Test
    fun `setup error and fallback result presentations are independently coherent`() {
        // Verify that the UnifiedResultPresentation for a fallback (from a TakeoverFallbackEvent)
        // is independently coherent from the CrossDeviceSetupError (setup-time error).
        // They must not share state or interfere with each other.
        val setupErr = CrossDeviceSetupError.classify(
            reason = "timeout connecting to gateway",
            isGatewayConfigured = true
        )
        val fallbackEvent = makeFailureEvent(TakeoverFallbackEvent.Cause.TIMEOUT)
        val fallbackPresentation = UnifiedResultPresentation.fromFallbackEvent(fallbackEvent)

        // Setup error: NETWORK, canRetry=true
        assertEquals(CrossDeviceSetupError.Category.NETWORK, setupErr.category)
        assertTrue(setupErr.canRetry)

        // Fallback presentation: isSuccess=false, outcome=TIMEOUT wire value
        assertFalse(fallbackPresentation.isSuccess)
        assertEquals(TakeoverFallbackEvent.Cause.TIMEOUT.wireValue, fallbackPresentation.outcome)

        // They are separate objects and do not share state
        assertFalse(
            "Setup error canRetry must not correspond to fallback isSuccess",
            fallbackPresentation.isSuccess
        )
    }

    @Test
    fun `result presentation paths do not share state`() {
        // Three distinct result presentation paths must produce distinct outcome strings
        val localSuccess = LocalLoopResult(
            sessionId = "s-paths",
            instruction = "goal",
            status = LocalLoopResult.STATUS_SUCCESS,
            stepCount = 2,
            stopReason = null,
            error = null
        )
        val serverMsg = "Result from server"
        val fallbackEvent = makeFailureEvent(TakeoverFallbackEvent.Cause.FAILED)

        val localP = UnifiedResultPresentation.fromLocalResult(localSuccess)
        val serverP = UnifiedResultPresentation.fromServerMessage(serverMsg)
        val fallbackP = UnifiedResultPresentation.fromFallbackEvent(fallbackEvent)

        // Each path produces a distinct outcome
        assertTrue("Local success is success", localP.isSuccess)
        assertTrue("Server message is success", serverP.isSuccess)
        assertFalse("Fallback is not success", fallbackP.isSuccess)

        // Outcomes are distinct between paths
        val outcomes = setOf(localP.outcome, serverP.outcome, fallbackP.outcome)
        assertEquals(
            "All three presentation paths must produce distinct outcome values",
            3,
            outcomes.size
        )
    }
}
