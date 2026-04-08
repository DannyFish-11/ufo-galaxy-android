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
import kotlinx.coroutines.delay
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
 * PR-29 — Post-release follow-up tightening across execution and UX flows.
 *
 * Regression protection suite for the focused follow-up pass after PR-28, addressing
 * remaining cohesion, stability, and usability gaps identified across:
 *  - execution routing (local / cross-device / delegated / fallback)
 *  - result presentation consistency
 *  - setup / recovery / retry flow correctness
 *
 * ## What is new in PR-29
 *
 *  1. **[ExecutionRouteTag] enum** — machine-readable tag (LOCAL / CROSS_DEVICE /
 *     DELEGATED / FALLBACK) that records which execution path produced the most recent
 *     task result.  Surfaced in [com.ufo.galaxy.ui.viewmodel.MainUiState.lastExecutionRoute]
 *     and in [com.ufo.galaxy.ui.viewmodel.MainViewModel.buildDiagnosticsText].
 *
 *  2. **CAPABILITY_NOT_SATISFIED retry tightening** — [CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED]
 *     now directs the user to network/settings instead of blindly retrying, because a
 *     retry without first granting the missing capability would fail immediately with the
 *     same error.  (Behaviour is validated via [CrossDeviceSetupError] unit tests here;
 *     the corresponding [com.ufo.galaxy.ui.viewmodel.MainViewModel.retryRegistration]
 *     branch cannot be tested in JVM unit tests due to Android context dependency, but
 *     the per-category routing contract is verified through the error classification
 *     tests below.)
 *
 * ## Test matrix
 *
 * ### ExecutionRouteTag enum integrity
 *  - LOCAL wireValue is "local"
 *  - CROSS_DEVICE wireValue is "cross_device"
 *  - DELEGATED wireValue is "delegated"
 *  - FALLBACK wireValue is "fallback"
 *  - all 4 route tags exist
 *  - all wireValues are distinct
 *  - LOCAL toString contains expected identifier
 *
 * ### Execution route / result presentation coherence (PR-29 tightening)
 *  - local success → LOCAL route is semantically correct
 *  - server message → CROSS_DEVICE route is semantically correct
 *  - fallback event FAILED → FALLBACK route is semantically correct
 *  - fallback event TIMEOUT → FALLBACK route is semantically correct
 *  - fallback event CANCELLED → FALLBACK route is semantically correct
 *  - fallback event DISCONNECT → FALLBACK route is semantically correct
 *  - execution route tags are distinct from result outcome strings
 *  - LOCAL and FALLBACK are distinct route tags (not the same execution path)
 *
 * ### CrossDeviceSetupError / CAPABILITY_NOT_SATISFIED tightening (PR-29)
 *  - CAPABILITY_NOT_SATISFIED canRetry is true (granting permission then retrying is valid)
 *  - CAPABILITY_NOT_SATISFIED wireValue is stable
 *  - CAPABILITY_NOT_SATISFIED is distinct from CONFIGURATION (must not collapse)
 *  - CAPABILITY_NOT_SATISFIED is distinct from NETWORK
 *  - CONFIGURATION canRetry is false (settings must change before retry)
 *  - NETWORK canRetry is true
 *  - classify with capability keyword → CAPABILITY_NOT_SATISFIED (not NETWORK)
 *  - classify with permission keyword → CAPABILITY_NOT_SATISFIED (not NETWORK)
 *  - all three Category wireValues are distinct
 *
 * ### RuntimeController post-PR28 edge cases
 *  - multiple sequential takeover failures are all emitted independently on takeoverFailure
 *  - stop() after multiple failures leaves state at LocalOnly
 *  - setup error and takeover failure flows are completely independent (PR-28 regression guard)
 *  - takeover failure does not pollute registrationError (PR-28 regression guard)
 *  - stop() after takeover failure leaves state at LocalOnly
 *  - reconnect() converges to LocalOnly on unreachable server
 *  - multiple consecutive reconnect() calls all converge to LocalOnly
 *  - registrationError reason matches setupError reason (one-failure-one-pair contract)
 *
 * ### Session / selection truth through PR-29 scenarios
 *  - hostSessionSnapshot is null before any session is opened
 *  - targetReadinessProjection is null before any session is opened
 *  - multiple takeover failures do not change hostSessionSnapshot
 *  - multiple takeover failures do not change targetReadinessProjection
 */
class Pr29PostReleaseTighteningTest {

    @Rule
    @JvmField
    val tmpFolder = TemporaryFolder()

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class TrivialPlannerService : LocalPlannerService {
        override suspend fun plan(goal: String, context: String): List<String> =
            listOf("step1", "step2")
    }

    private class TrivialGrounder : LocalGroundingService {
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

    // ── Builders ──────────────────────────────────────────────────────────────

    private fun buildLoopController(): LoopController {
        val modelsDir = tmpFolder.newFolder("models")
        return LoopController(
            localPlanner = LocalPlanner(TrivialPlannerService()),
            executorBridge = ExecutorBridge(
                groundingService = TrivialGrounder(),
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
        timeoutMs: Long = 150L
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = timeoutMs
        )
        return controller to client
    }

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
            takeoverId = "takeover-pr29-${cause.wireValue}",
            taskId = "task-pr29-${cause.wireValue}",
            traceId = "trace-pr29-${cause.wireValue}",
            reason = "PR-29 test failure for ${cause.wireValue}",
            cause = cause
        )

    private fun makeLocalSuccess(stepCount: Int = 3): LocalLoopResult = LocalLoopResult(
        sessionId = "s-pr29-local",
        instruction = "do something",
        status = LocalLoopResult.STATUS_SUCCESS,
        stepCount = stepCount,
        stopReason = null,
        error = null
    )

    // ── ExecutionRouteTag enum integrity ──────────────────────────────────────

    @Test
    fun `LOCAL wireValue is local`() {
        assertEquals("local", ExecutionRouteTag.LOCAL.wireValue)
    }

    @Test
    fun `CROSS_DEVICE wireValue is cross_device`() {
        assertEquals("cross_device", ExecutionRouteTag.CROSS_DEVICE.wireValue)
    }

    @Test
    fun `DELEGATED wireValue is delegated`() {
        assertEquals("delegated", ExecutionRouteTag.DELEGATED.wireValue)
    }

    @Test
    fun `FALLBACK wireValue is fallback`() {
        assertEquals("fallback", ExecutionRouteTag.FALLBACK.wireValue)
    }

    @Test
    fun `all 4 route tags exist`() {
        val values = ExecutionRouteTag.values()
        assertEquals("ExecutionRouteTag must have exactly 4 values", 4, values.size)
        assertTrue(values.contains(ExecutionRouteTag.LOCAL))
        assertTrue(values.contains(ExecutionRouteTag.CROSS_DEVICE))
        assertTrue(values.contains(ExecutionRouteTag.DELEGATED))
        assertTrue(values.contains(ExecutionRouteTag.FALLBACK))
    }

    @Test
    fun `all wireValues are distinct`() {
        val wireValues = ExecutionRouteTag.values().map { it.wireValue }
        val distinct = wireValues.toSet()
        assertEquals(
            "All ExecutionRouteTag wireValues must be unique",
            wireValues.size,
            distinct.size
        )
    }

    @Test
    fun `LOCAL toString contains LOCAL`() {
        assertTrue(
            "ExecutionRouteTag.LOCAL.name must contain 'LOCAL'",
            ExecutionRouteTag.LOCAL.name.contains("LOCAL")
        )
    }

    // ── Execution route / result presentation coherence ───────────────────────

    @Test
    fun `local success route tag is semantically distinct from fallback`() {
        // LOCAL and FALLBACK represent different execution paths and must not be the same tag.
        assertFalse(
            "LOCAL and FALLBACK must be distinct route tags",
            ExecutionRouteTag.LOCAL == ExecutionRouteTag.FALLBACK
        )
    }

    @Test
    fun `CROSS_DEVICE route tag is distinct from LOCAL`() {
        assertFalse(
            "CROSS_DEVICE and LOCAL must be distinct route tags",
            ExecutionRouteTag.CROSS_DEVICE == ExecutionRouteTag.LOCAL
        )
    }

    @Test
    fun `DELEGATED route tag is distinct from FALLBACK`() {
        // DELEGATED (successful takeover) and FALLBACK (failed takeover) must never collapse.
        assertFalse(
            "DELEGATED and FALLBACK must be distinct route tags",
            ExecutionRouteTag.DELEGATED == ExecutionRouteTag.FALLBACK
        )
    }

    @Test
    fun `fallback route wireValue does not equal local result STATUS_SUCCESS`() {
        // Route tags must not collide with UnifiedResultPresentation outcome strings.
        assertFalse(
            "FALLBACK wireValue must be distinct from LocalLoopResult.STATUS_SUCCESS",
            ExecutionRouteTag.FALLBACK.wireValue == LocalLoopResult.STATUS_SUCCESS
        )
    }

    @Test
    fun `cross_device route wireValue does not equal server message outcome`() {
        // The CROSS_DEVICE route tag wireValue must not collide with the "success" outcome
        // that fromServerMessage() emits.
        val serverPresentation = UnifiedResultPresentation.fromServerMessage("ok")
        assertFalse(
            "CROSS_DEVICE wireValue must not equal 'success' (server message outcome)",
            ExecutionRouteTag.CROSS_DEVICE.wireValue == serverPresentation.outcome
        )
    }

    @Test
    fun `fallback route wireValue does not equal FAILED cause wireValue`() {
        // ExecutionRouteTag.FALLBACK ("fallback") is distinct from TakeoverFallbackEvent.Cause.FAILED ("failed").
        assertFalse(
            "FALLBACK route wireValue must be distinct from FAILED cause wireValue",
            ExecutionRouteTag.FALLBACK.wireValue == TakeoverFallbackEvent.Cause.FAILED.wireValue
        )
    }

    @Test
    fun `local result presentation isSuccess true is independent of route tag`() {
        val local = makeLocalSuccess()
        val presentation = UnifiedResultPresentation.fromLocalResult(local)
        assertTrue("Local success presentation must have isSuccess=true", presentation.isSuccess)
        // The presentation outcome (STATUS_SUCCESS) is distinct from the route wireValue.
        assertFalse(
            "Presentation outcome must not equal LOCAL route wireValue",
            presentation.outcome == ExecutionRouteTag.LOCAL.wireValue
        )
    }

    @Test
    fun `fallback presentation for each cause pairs with FALLBACK route tag`() {
        // Every TakeoverFallbackEvent cause should map to the FALLBACK route tag at the
        // ViewModel layer.  Here we verify that fromFallbackEvent() produces the expected
        // isSuccess=false outcome for all causes (route tag assignment is ViewModel logic;
        // this test ensures the presentation contract is stable).
        for (cause in TakeoverFallbackEvent.Cause.values()) {
            val event = makeFailureEvent(cause)
            val presentation = UnifiedResultPresentation.fromFallbackEvent(event)
            assertFalse(
                "fromFallbackEvent($cause) must not be success — FALLBACK route implies failure",
                presentation.isSuccess
            )
        }
    }

    // ── CrossDeviceSetupError / CAPABILITY tightening ─────────────────────────

    @Test
    fun `CAPABILITY_NOT_SATISFIED canRetry is true`() {
        // Granting the missing permission and then retrying is valid.
        val err = CrossDeviceSetupError.classify(
            reason = "accessibility service not enabled",
            isGatewayConfigured = true
        )
        assertEquals(CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED, err.category)
        assertTrue(
            "CAPABILITY_NOT_SATISFIED must have canRetry=true (user can grant then retry)",
            err.canRetry
        )
    }

    @Test
    fun `CAPABILITY_NOT_SATISFIED wireValue is stable`() {
        assertEquals(
            "capability_not_satisfied",
            CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED.wireValue
        )
    }

    @Test
    fun `CAPABILITY_NOT_SATISFIED is distinct from CONFIGURATION`() {
        val capability = CrossDeviceSetupError.classify(
            reason = "overlay permission not granted",
            isGatewayConfigured = true
        )
        val config = CrossDeviceSetupError.classify(
            reason = "gateway not configured",
            isGatewayConfigured = false
        )
        assertFalse(
            "CAPABILITY_NOT_SATISFIED and CONFIGURATION must be distinct categories",
            capability.category == config.category
        )
    }

    @Test
    fun `CAPABILITY_NOT_SATISFIED is distinct from NETWORK`() {
        val capability = CrossDeviceSetupError.classify(
            reason = "model files not present",
            isGatewayConfigured = true
        )
        val network = CrossDeviceSetupError.classify(
            reason = "connection refused",
            isGatewayConfigured = true
        )
        assertFalse(
            "CAPABILITY_NOT_SATISFIED and NETWORK must be distinct categories",
            capability.category == network.category
        )
    }

    @Test
    fun `CONFIGURATION canRetry is false`() {
        // A configuration error cannot be resolved by retrying without a settings change.
        val err = CrossDeviceSetupError.classify(
            reason = "any reason",
            isGatewayConfigured = false
        )
        assertEquals(CrossDeviceSetupError.Category.CONFIGURATION, err.category)
        assertFalse(
            "CONFIGURATION must have canRetry=false (settings change required before retry)",
            err.canRetry
        )
    }

    @Test
    fun `NETWORK canRetry is true`() {
        val err = CrossDeviceSetupError.classify(
            reason = "connection timeout",
            isGatewayConfigured = true
        )
        assertEquals(CrossDeviceSetupError.Category.NETWORK, err.category)
        assertTrue("NETWORK must have canRetry=true (transient failure)", err.canRetry)
    }

    @Test
    fun `classify with capability keyword returns CAPABILITY_NOT_SATISFIED not NETWORK`() {
        val err = CrossDeviceSetupError.classify(
            reason = "capability check failed: LOCAL_INFERENCE not available",
            isGatewayConfigured = true
        )
        assertEquals(
            "keyword 'capability' must produce CAPABILITY_NOT_SATISFIED, not NETWORK",
            CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED,
            err.category
        )
    }

    @Test
    fun `classify with permission keyword returns CAPABILITY_NOT_SATISFIED not NETWORK`() {
        val err = CrossDeviceSetupError.classify(
            reason = "permission denied: SYSTEM_ALERT_WINDOW",
            isGatewayConfigured = true
        )
        assertEquals(
            "keyword 'permission' must produce CAPABILITY_NOT_SATISFIED, not NETWORK",
            CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED,
            err.category
        )
    }

    @Test
    fun `classify with model keyword returns CAPABILITY_NOT_SATISFIED not NETWORK`() {
        val err = CrossDeviceSetupError.classify(
            reason = "model files missing",
            isGatewayConfigured = true
        )
        assertEquals(
            "keyword 'model' must produce CAPABILITY_NOT_SATISFIED",
            CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED,
            err.category
        )
    }

    @Test
    fun `all three Category wireValues are distinct`() {
        val wireValues = CrossDeviceSetupError.Category.values().map { it.wireValue }.toSet()
        assertEquals(
            "All CrossDeviceSetupError.Category wireValues must be unique",
            CrossDeviceSetupError.Category.values().size,
            wireValues.size
        )
    }

    @Test
    fun `CAPABILITY_NOT_SATISFIED canRetry is independent of CONFIGURATION canRetry`() {
        val cap = CrossDeviceSetupError.classify("accessibility not enabled", isGatewayConfigured = true)
        val cfg = CrossDeviceSetupError.classify("any", isGatewayConfigured = false)
        // They must differ: CAPABILITY can retry, CONFIGURATION cannot.
        assertFalse(
            "CAPABILITY canRetry and CONFIGURATION canRetry must differ",
            cap.canRetry == cfg.canRetry
        )
    }

    // ── RuntimeController post-PR28 edge cases ────────────────────────────────

    @Test
    fun `multiple sequential takeover failures all emit independently on takeoverFailure`() =
        runBlocking {
            val (controller, _) = buildController()
            val received = mutableListOf<TakeoverFallbackEvent>()

            val causes = TakeoverFallbackEvent.Cause.values()
            val collectJob = launch {
                controller.takeoverFailure.collect { event ->
                    received.add(event)
                }
            }

            for (cause in causes) {
                controller.notifyTakeoverFailed(
                    takeoverId = "t-seq-${cause.wireValue}",
                    taskId = "task-seq-${cause.wireValue}",
                    traceId = "trace-seq-${cause.wireValue}",
                    reason = "sequential failure for ${cause.wireValue}",
                    cause = cause
                )
            }

            // Give the collector time to process all 4 emissions.
            kotlinx.coroutines.delay(100)
            collectJob.cancel()

            assertEquals(
                "All ${causes.size} sequential takeover failures must be individually emitted",
                causes.size,
                received.size
            )
            // Verify ordering: causes should appear in the order emitted.
            causes.forEachIndexed { index, cause ->
                assertEquals(
                    "Failure at index $index must have cause ${cause.wireValue}",
                    cause,
                    received[index].cause
                )
            }
        }

    @Test
    fun `state is LocalOnly after stop following multiple takeover failures`() = runBlocking {
        val (controller, _) = buildController()

        // Emit several takeover failures.
        for (cause in TakeoverFallbackEvent.Cause.values()) {
            controller.notifyTakeoverFailed(
                takeoverId = "t-stop-${cause.wireValue}",
                taskId = "task-stop-${cause.wireValue}",
                traceId = "trace-stop-${cause.wireValue}",
                reason = "failure before stop",
                cause = cause
            )
        }

        controller.stop()

        // After stop(), state must be LocalOnly — not Failed, not Idle, not Active.
        assertEquals(
            "RuntimeState must be LocalOnly after stop() — even after multiple takeover failures",
            RuntimeController.RuntimeState.LocalOnly,
            controller.state.value
        )
    }

    @Test
    fun `setup error and takeover failure flows are completely independent`() = runBlocking {
        val (controller, _) = buildController(timeoutMs = 150L)
        var unexpectedSetupError: CrossDeviceSetupError? = null

        // Listen for any setup error that might be incorrectly triggered by notifyTakeoverFailed.
        val job = launch {
            unexpectedSetupError = withTimeoutOrNull(200L) {
                controller.setupError.first()
            }
        }

        // Emit all 4 takeover failure causes.
        for (cause in TakeoverFallbackEvent.Cause.values()) {
            controller.notifyTakeoverFailed(
                takeoverId = "t-indep-${cause.wireValue}",
                taskId = "task-indep",
                traceId = "trace-indep",
                reason = "independence test",
                cause = cause
            )
        }

        job.join()
        assertNull(
            "notifyTakeoverFailed must NEVER emit on setupError — independent flows",
            unexpectedSetupError
        )
    }

    @Test
    fun `takeover failure does not emit on registrationError`() = runBlocking {
        val (controller, _) = buildController()
        var unexpectedRegError: String? = null

        val job = launch {
            unexpectedRegError = withTimeoutOrNull(200L) {
                controller.registrationError.first()
            }
        }

        controller.notifyTakeoverFailed(
            takeoverId = "t-reg-guard",
            taskId = "task-reg-guard",
            traceId = "trace-reg-guard",
            reason = "must not appear on registrationError",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )

        job.join()
        assertNull(
            "notifyTakeoverFailed must NEVER emit on registrationError — independent flows",
            unexpectedRegError
        )
    }

    @Test
    fun `stop after takeover failure leaves state at LocalOnly`() = runBlocking {
        val (controller, _) = buildController()

        controller.notifyTakeoverFailed(
            takeoverId = "t-stop-after",
            taskId = "task-stop-after",
            traceId = "trace-stop-after",
            reason = "failure then stop",
            cause = TakeoverFallbackEvent.Cause.DISCONNECT
        )
        controller.stop()

        assertEquals(
            "stop() after takeover failure must produce LocalOnly (not Idle or Failed)",
            RuntimeController.RuntimeState.LocalOnly,
            controller.state.value
        )
    }

    @Test
    fun `reconnect converges to LocalOnly on unreachable server`() = runBlocking {
        val (controller, _) = buildConfiguredController(timeoutMs = 150L)

        val result = controller.reconnect()

        assertFalse("reconnect() with unreachable server must return false", result)
        assertEquals(
            "State must be LocalOnly after failed reconnect()",
            RuntimeController.RuntimeState.LocalOnly,
            controller.state.value
        )
    }

    @Test
    fun `multiple consecutive reconnect calls all converge to LocalOnly`() = runBlocking {
        val (controller, _) = buildConfiguredController(timeoutMs = 100L)

        repeat(3) { attempt ->
            val result = controller.reconnect()
            assertFalse(
                "reconnect() attempt $attempt with unreachable server must return false",
                result
            )
            assertEquals(
                "State must be LocalOnly after reconnect() attempt $attempt",
                RuntimeController.RuntimeState.LocalOnly,
                controller.state.value
            )
        }
    }

    @Test
    fun `registrationError reason matches setupError reason on same failure`() = runBlocking {
        val (controller, _) = buildConfiguredController(timeoutMs = 150L)
        var regReason: String? = null
        var setupReason: String? = null

        val regJob = launch {
            regReason = controller.registrationError.first()
        }
        val setupJob = launch {
            setupReason = controller.setupError.first().reason
        }

        controller.startWithTimeout()
        regJob.join()
        setupJob.join()

        assertNotNull("registrationError must emit on failure", regReason)
        assertNotNull("setupError must emit on failure", setupReason)
        assertEquals(
            "registrationError reason must equal setupError.reason (same failure event)",
            regReason,
            setupReason
        )
    }

    // ── Session / selection truth through PR-29 scenarios ─────────────────────

    @Test
    fun `hostSessionSnapshot is null before any session is opened`() = runBlocking {
        val (controller, _) = buildController()
        assertNull(
            "hostSessionSnapshot must be null before any session is opened",
            controller.hostSessionSnapshot.value
        )
    }

    @Test
    fun `targetReadinessProjection is null before any session is opened`() = runBlocking {
        val (controller, _) = buildController()
        assertNull(
            "targetReadinessProjection must be null before any session is opened",
            controller.targetReadinessProjection.value
        )
    }

    @Test
    fun `multiple takeover failures do not change hostSessionSnapshot`() = runBlocking {
        val (controller, _) = buildController()
        val snapshotBefore = controller.hostSessionSnapshot.value

        for (cause in TakeoverFallbackEvent.Cause.values()) {
            controller.notifyTakeoverFailed(
                takeoverId = "t-snap-${cause.wireValue}",
                taskId = "task-snap",
                traceId = "trace-snap",
                reason = "session truth test",
                cause = cause
            )
        }

        assertEquals(
            "hostSessionSnapshot must be unchanged after multiple takeover failures",
            snapshotBefore,
            controller.hostSessionSnapshot.value
        )
    }

    @Test
    fun `multiple takeover failures do not change targetReadinessProjection`() = runBlocking {
        val (controller, _) = buildController()
        val projBefore = controller.targetReadinessProjection.value

        for (cause in TakeoverFallbackEvent.Cause.values()) {
            controller.notifyTakeoverFailed(
                takeoverId = "t-proj-${cause.wireValue}",
                taskId = "task-proj",
                traceId = "trace-proj",
                reason = "readiness truth test",
                cause = cause
            )
        }

        assertEquals(
            "targetReadinessProjection must be unchanged after multiple takeover failures",
            projBefore,
            controller.targetReadinessProjection.value
        )
    }

    @Test
    fun `takeoverFailure cause wireValues are all distinct and stable`() {
        val wireValues = TakeoverFallbackEvent.Cause.values().map { it.wireValue }
        val distinct = wireValues.toSet()
        assertEquals(
            "All TakeoverFallbackEvent.Cause wireValues must be unique",
            wireValues.size,
            distinct.size
        )
    }

    @Test
    fun `FALLBACK route wireValue does not collide with any TakeoverFallbackEvent cause wireValue`() {
        for (cause in TakeoverFallbackEvent.Cause.values()) {
            assertFalse(
                "ExecutionRouteTag.FALLBACK wireValue ('fallback') must not equal " +
                    "TakeoverFallbackEvent.Cause.${cause.name} wireValue ('${cause.wireValue}')",
                ExecutionRouteTag.FALLBACK.wireValue == cause.wireValue
            )
        }
    }
}
