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
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.ui.viewmodel.MainUiState
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
 * PR-30 — Post-rollout observability, diagnostics, and operator-facing troubleshooting signals.
 *
 * Regression and acceptance test suite for all PR-30 additions:
 *
 *  1. New [GalaxyLogger] tag constants (`TAG_EXEC_ROUTE`, `TAG_SETUP_RECOVERY`,
 *     `TAG_RECONNECT_OUTCOME`, `TAG_FALLBACK_DECISION`) are defined with stable wire values.
 *
 *  2. [MainUiState.executionRouteCounts] tracks per-route task counts and is updated
 *     independently per route without cross-contamination.
 *
 *  3. [MainUiState.lastSetupFailureCategory] persists the most recent setup failure
 *     category and is never cleared on dialog dismiss — distinct from
 *     [MainUiState.registrationFailureCategory].
 *
 *  4. [RuntimeController.notifyTakeoverFailed] emits a [GalaxyLogger.TAG_FALLBACK_DECISION]
 *     log entry alongside the existing `takeover_failed` entry.
 *
 *  5. [RuntimeController.reconnect] emits a [GalaxyLogger.TAG_RECONNECT_OUTCOME] log
 *     entry capturing success/failure after the reconnect attempt completes.
 *
 *  6. Diagnostics text from the helper includes all PR-30 sections.
 *
 *  7. Route-count bookkeeping is deterministic and regression-safe.
 *
 * ## Test matrix
 *
 * ### GalaxyLogger PR-30 tag constants
 *  - TAG_EXEC_ROUTE value is "GALAXY:EXEC:ROUTE"
 *  - TAG_SETUP_RECOVERY value is "GALAXY:SETUP:RECOVERY"
 *  - TAG_RECONNECT_OUTCOME value is "GALAXY:RECONNECT:OUTCOME"
 *  - TAG_FALLBACK_DECISION value is "GALAXY:FALLBACK:DECISION"
 *  - all four new PR-30 tags are distinct from each other
 *  - all four new PR-30 tags are distinct from pre-PR30 tags
 *
 * ### MainUiState.executionRouteCounts
 *  - defaults to empty map
 *  - incrementRouteCount logic: zero → one
 *  - incrementRouteCount logic: multiple increments accumulate
 *  - different routes are tracked independently
 *  - all four routes can be tracked simultaneously
 *  - route counts survive copy() of other fields
 *
 * ### MainUiState.lastSetupFailureCategory
 *  - defaults to null
 *  - can be set to NETWORK
 *  - can be set to CONFIGURATION
 *  - can be set to CAPABILITY_NOT_SATISFIED
 *  - is independent of registrationFailureCategory
 *  - persists when registrationFailureCategory is cleared (dialog dismiss simulation)
 *  - last category wins when set twice
 *
 * ### ExecutionRouteTag / route observability semantics
 *  - LOCAL wireValue matches expected operator-visible string
 *  - CROSS_DEVICE wireValue matches expected operator-visible string
 *  - DELEGATED wireValue matches expected operator-visible string
 *  - FALLBACK wireValue matches expected operator-visible string
 *  - each route has a unique wireValue
 *
 * ### RuntimeController PR-30 reconnect outcome logging
 *  - reconnect on unreachable server emits TAG_RECONNECT_OUTCOME entry in GalaxyLogger
 *  - reconnect outcome entry has outcome="failure" when server unreachable
 *
 * ### RuntimeController PR-30 fallback decision logging
 *  - notifyTakeoverFailed emits TAG_FALLBACK_DECISION entry in GalaxyLogger
 *  - TAG_FALLBACK_DECISION entry includes takeover_id, task_id, cause, reason fields
 *  - TAG_FALLBACK_DECISION is emitted for all four TakeoverFallbackEvent causes
 *
 * ### Diagnostics text sections (PR-30 additions)
 *  - diagnostics text includes "-- Execution Routes (this session) --" section
 *  - empty counts renders "(no tasks completed)"
 *  - non-zero counts are shown with wire-value labels
 *  - zero-count routes are omitted from output
 *  - lastSetupFailureCategory appears in diagnostics text when set
 *  - lastSetupFailureCategory does not appear when null
 */
class Pr30ObservabilityDiagnosticsTest {

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
            takeoverId = "takeover-pr30-${cause.wireValue}",
            taskId = "task-pr30-${cause.wireValue}",
            traceId = "trace-pr30-${cause.wireValue}",
            reason = "PR-30 test failure for ${cause.wireValue}",
            cause = cause
        )

    // ── GalaxyLogger PR-30 tag constants ──────────────────────────────────────

    @Test
    fun `TAG_EXEC_ROUTE value is GALAXY EXEC ROUTE`() {
        assertEquals("GALAXY:EXEC:ROUTE", GalaxyLogger.TAG_EXEC_ROUTE)
    }

    @Test
    fun `TAG_SETUP_RECOVERY value is GALAXY SETUP RECOVERY`() {
        assertEquals("GALAXY:SETUP:RECOVERY", GalaxyLogger.TAG_SETUP_RECOVERY)
    }

    @Test
    fun `TAG_RECONNECT_OUTCOME value is GALAXY RECONNECT OUTCOME`() {
        assertEquals("GALAXY:RECONNECT:OUTCOME", GalaxyLogger.TAG_RECONNECT_OUTCOME)
    }

    @Test
    fun `TAG_FALLBACK_DECISION value is GALAXY FALLBACK DECISION`() {
        assertEquals("GALAXY:FALLBACK:DECISION", GalaxyLogger.TAG_FALLBACK_DECISION)
    }

    @Test
    fun `all four new PR-30 tags are distinct from each other`() {
        val pr30Tags = setOf(
            GalaxyLogger.TAG_EXEC_ROUTE,
            GalaxyLogger.TAG_SETUP_RECOVERY,
            GalaxyLogger.TAG_RECONNECT_OUTCOME,
            GalaxyLogger.TAG_FALLBACK_DECISION
        )
        assertEquals("All four PR-30 tags must be distinct", 4, pr30Tags.size)
    }

    @Test
    fun `all four new PR-30 tags are distinct from pre-PR30 tags`() {
        val prePr30Tags = setOf(
            GalaxyLogger.TAG_CONNECT,
            GalaxyLogger.TAG_DISCONNECT,
            GalaxyLogger.TAG_RECONNECT,
            GalaxyLogger.TAG_TASK_RECV,
            GalaxyLogger.TAG_TASK_EXEC,
            GalaxyLogger.TAG_TASK_RETURN,
            GalaxyLogger.TAG_READINESS,
            GalaxyLogger.TAG_DEGRADED,
            GalaxyLogger.TAG_ERROR,
            GalaxyLogger.TAG_LOCAL_LOOP_START,
            GalaxyLogger.TAG_LOCAL_LOOP_STEP,
            GalaxyLogger.TAG_LOCAL_LOOP_PLAN,
            GalaxyLogger.TAG_LOCAL_LOOP_DONE
        )
        val pr30Tags = listOf(
            GalaxyLogger.TAG_EXEC_ROUTE,
            GalaxyLogger.TAG_SETUP_RECOVERY,
            GalaxyLogger.TAG_RECONNECT_OUTCOME,
            GalaxyLogger.TAG_FALLBACK_DECISION
        )
        pr30Tags.forEach { tag ->
            assertFalse(
                "PR-30 tag '$tag' must not clash with a pre-PR30 tag",
                prePr30Tags.contains(tag)
            )
        }
    }

    // ── ExecutionRouteTag / route observability semantics ─────────────────────

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
    fun `each route tag has a unique wireValue`() {
        val wireValues = ExecutionRouteTag.values().map { it.wireValue }
        assertEquals(
            "All ExecutionRouteTag wireValues must be distinct",
            wireValues.size,
            wireValues.toSet().size
        )
    }

    // ── MainUiState.executionRouteCounts ──────────────────────────────────────

    @Test
    fun `executionRouteCounts defaults to empty map`() {
        assertEquals(emptyMap<ExecutionRouteTag, Int>(), MainUiState().executionRouteCounts)
    }

    @Test
    fun `incrementRouteCount zero to one for LOCAL`() {
        val counts = emptyMap<ExecutionRouteTag, Int>()
        val updated = counts + (ExecutionRouteTag.LOCAL to ((counts[ExecutionRouteTag.LOCAL] ?: 0) + 1))
        assertEquals(1, updated[ExecutionRouteTag.LOCAL])
    }

    @Test
    fun `incrementRouteCount accumulates multiple increments for CROSS_DEVICE`() {
        var counts = emptyMap<ExecutionRouteTag, Int>()
        repeat(4) {
            counts = counts + (ExecutionRouteTag.CROSS_DEVICE to ((counts[ExecutionRouteTag.CROSS_DEVICE] ?: 0) + 1))
        }
        assertEquals(4, counts[ExecutionRouteTag.CROSS_DEVICE])
    }

    @Test
    fun `different routes are tracked independently`() {
        var state = MainUiState()

        // Simulate 2 LOCAL results
        repeat(2) {
            state = state.copy(
                executionRouteCounts = state.executionRouteCounts +
                    (ExecutionRouteTag.LOCAL to ((state.executionRouteCounts[ExecutionRouteTag.LOCAL] ?: 0) + 1))
            )
        }
        // Simulate 3 CROSS_DEVICE results
        repeat(3) {
            state = state.copy(
                executionRouteCounts = state.executionRouteCounts +
                    (ExecutionRouteTag.CROSS_DEVICE to ((state.executionRouteCounts[ExecutionRouteTag.CROSS_DEVICE] ?: 0) + 1))
            )
        }

        assertEquals(2, state.executionRouteCounts[ExecutionRouteTag.LOCAL])
        assertEquals(3, state.executionRouteCounts[ExecutionRouteTag.CROSS_DEVICE])
        assertNull(state.executionRouteCounts[ExecutionRouteTag.DELEGATED])
        assertNull(state.executionRouteCounts[ExecutionRouteTag.FALLBACK])
    }

    @Test
    fun `all four routes can be tracked simultaneously`() {
        var counts = emptyMap<ExecutionRouteTag, Int>()
        ExecutionRouteTag.values().forEachIndexed { idx, tag ->
            val increment = idx + 1
            repeat(increment) {
                counts = counts + (tag to ((counts[tag] ?: 0) + 1))
            }
        }
        assertEquals(1, counts[ExecutionRouteTag.LOCAL])
        assertEquals(2, counts[ExecutionRouteTag.CROSS_DEVICE])
        assertEquals(3, counts[ExecutionRouteTag.DELEGATED])
        assertEquals(4, counts[ExecutionRouteTag.FALLBACK])
    }

    @Test
    fun `route counts survive copy of other MainUiState fields`() {
        val original = MainUiState(
            executionRouteCounts = mapOf(ExecutionRouteTag.LOCAL to 5),
            lastExecutionRoute = ExecutionRouteTag.LOCAL,
            lastSetupFailureCategory = CrossDeviceSetupError.Category.NETWORK
        )
        val copied = original.copy(isLoading = true, isConnected = true)
        assertEquals(5, copied.executionRouteCounts[ExecutionRouteTag.LOCAL])
        // PR-30: lastSetupFailureCategory must also survive copy operations
        assertEquals(CrossDeviceSetupError.Category.NETWORK, copied.lastSetupFailureCategory)
    }

    // ── MainUiState.lastSetupFailureCategory ──────────────────────────────────

    @Test
    fun `lastSetupFailureCategory defaults to null`() {
        assertNull(MainUiState().lastSetupFailureCategory)
    }

    @Test
    fun `lastSetupFailureCategory can be set to NETWORK`() {
        val state = MainUiState(lastSetupFailureCategory = CrossDeviceSetupError.Category.NETWORK)
        assertEquals(CrossDeviceSetupError.Category.NETWORK, state.lastSetupFailureCategory)
    }

    @Test
    fun `lastSetupFailureCategory can be set to CONFIGURATION`() {
        val state = MainUiState(lastSetupFailureCategory = CrossDeviceSetupError.Category.CONFIGURATION)
        assertEquals(CrossDeviceSetupError.Category.CONFIGURATION, state.lastSetupFailureCategory)
    }

    @Test
    fun `lastSetupFailureCategory can be set to CAPABILITY_NOT_SATISFIED`() {
        val state = MainUiState(
            lastSetupFailureCategory = CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED
        )
        assertEquals(
            CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED,
            state.lastSetupFailureCategory
        )
    }

    @Test
    fun `lastSetupFailureCategory is independent of registrationFailureCategory`() {
        val state = MainUiState(
            registrationFailureCategory = CrossDeviceSetupError.Category.NETWORK,
            lastSetupFailureCategory = CrossDeviceSetupError.Category.CONFIGURATION
        )
        assertEquals(CrossDeviceSetupError.Category.NETWORK, state.registrationFailureCategory)
        assertEquals(CrossDeviceSetupError.Category.CONFIGURATION, state.lastSetupFailureCategory)
    }

    @Test
    fun `lastSetupFailureCategory persists when registrationFailureCategory is cleared`() {
        // Simulate: failure → dialog shown → user dismisses dialog
        val afterFailure = MainUiState(
            registrationFailure = "some error",
            registrationFailureCategory = CrossDeviceSetupError.Category.NETWORK,
            lastSetupFailureCategory = CrossDeviceSetupError.Category.NETWORK
        )
        // Simulate clearRegistrationFailure()
        val afterDismiss = afterFailure.copy(
            registrationFailure = null,
            registrationFailureCategory = null
        )
        // lastSetupFailureCategory must NOT be cleared — operator diagnostic persists
        assertNull(afterDismiss.registrationFailure)
        assertNull(afterDismiss.registrationFailureCategory)
        assertEquals(CrossDeviceSetupError.Category.NETWORK, afterDismiss.lastSetupFailureCategory)
    }

    @Test
    fun `last category wins when lastSetupFailureCategory is set twice`() {
        val state = MainUiState(lastSetupFailureCategory = CrossDeviceSetupError.Category.NETWORK)
        val updated = state.copy(lastSetupFailureCategory = CrossDeviceSetupError.Category.CONFIGURATION)
        assertEquals(CrossDeviceSetupError.Category.CONFIGURATION, updated.lastSetupFailureCategory)
    }

    // ── RuntimeController PR-30 reconnect outcome logging ─────────────────────

    @Test
    fun `reconnect on unreachable server emits TAG_RECONNECT_OUTCOME entry`() = runBlocking {
        GalaxyLogger.clear()
        val (controller, _) = buildConfiguredController(timeoutMs = 100L)
        try {
            controller.reconnect()
        } catch (_: Exception) {
            // ignore any coroutine exceptions from the unreachable server
        }
        val entries = GalaxyLogger.getEntries()
        val reconnectOutcomeEntries = entries.filter { it.tag == GalaxyLogger.TAG_RECONNECT_OUTCOME }
        assertTrue(
            "Expected at least one TAG_RECONNECT_OUTCOME entry after reconnect",
            reconnectOutcomeEntries.isNotEmpty()
        )
    }

    @Test
    fun `reconnect outcome entry has outcome field on failure`() = runBlocking {
        GalaxyLogger.clear()
        val (controller, _) = buildConfiguredController(timeoutMs = 100L)
        try {
            controller.reconnect()
        } catch (_: Exception) { }
        val entry = GalaxyLogger.getEntries()
            .lastOrNull { it.tag == GalaxyLogger.TAG_RECONNECT_OUTCOME }
        assertNotNull("Expected a TAG_RECONNECT_OUTCOME entry", entry)
        val outcome = entry!!.fields["outcome"]
        assertNotNull("TAG_RECONNECT_OUTCOME entry must contain 'outcome' field", outcome)
        // Unreachable server → outcome must be "failure"
        assertEquals("failure", outcome)
    }

    // ── RuntimeController PR-30 fallback decision logging ────────────────────

    @Test
    fun `notifyTakeoverFailed emits TAG_FALLBACK_DECISION log entry`() = runBlocking {
        GalaxyLogger.clear()
        val (controller, _) = buildController()
        controller.notifyTakeoverFailed(
            takeoverId = "takeover-fallback-signal-test",
            taskId = "task-fallback-signal",
            traceId = "trace-fallback-signal",
            reason = "pipeline exception",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        val entries = GalaxyLogger.getEntries()
        val fallbackEntries = entries.filter { it.tag == GalaxyLogger.TAG_FALLBACK_DECISION }
        assertTrue(
            "Expected at least one TAG_FALLBACK_DECISION entry",
            fallbackEntries.isNotEmpty()
        )
    }

    @Test
    fun `TAG_FALLBACK_DECISION entry includes required fields`() = runBlocking {
        GalaxyLogger.clear()
        val (controller, _) = buildController()
        controller.notifyTakeoverFailed(
            takeoverId = "takeover-fields-test",
            taskId = "task-fields-test",
            traceId = "trace-fields",
            reason = "test-reason",
            cause = TakeoverFallbackEvent.Cause.TIMEOUT
        )
        val entry = GalaxyLogger.getEntries()
            .lastOrNull { it.tag == GalaxyLogger.TAG_FALLBACK_DECISION }
        assertNotNull("Expected a TAG_FALLBACK_DECISION entry", entry)
        assertEquals("takeover-fields-test", entry!!.fields["takeover_id"])
        assertEquals("task-fields-test", entry.fields["task_id"])
        assertEquals(TakeoverFallbackEvent.Cause.TIMEOUT.wireValue, entry.fields["cause"])
        assertEquals("test-reason", entry.fields["reason"])
    }

    @Test
    fun `TAG_FALLBACK_DECISION is emitted for FAILED cause`() = runBlocking {
        GalaxyLogger.clear()
        val (controller, _) = buildController()
        val event = makeFailureEvent(TakeoverFallbackEvent.Cause.FAILED)
        controller.notifyTakeoverFailed(
            takeoverId = event.takeoverId,
            taskId = event.taskId,
            traceId = event.traceId,
            reason = event.reason,
            cause = event.cause
        )
        val entry = GalaxyLogger.getEntries()
            .lastOrNull { it.tag == GalaxyLogger.TAG_FALLBACK_DECISION }
        assertNotNull(entry)
        assertEquals(TakeoverFallbackEvent.Cause.FAILED.wireValue, entry!!.fields["cause"])
    }

    @Test
    fun `TAG_FALLBACK_DECISION is emitted for TIMEOUT cause`() = runBlocking {
        GalaxyLogger.clear()
        val (controller, _) = buildController()
        val event = makeFailureEvent(TakeoverFallbackEvent.Cause.TIMEOUT)
        controller.notifyTakeoverFailed(
            takeoverId = event.takeoverId,
            taskId = event.taskId,
            traceId = event.traceId,
            reason = event.reason,
            cause = event.cause
        )
        val entry = GalaxyLogger.getEntries()
            .lastOrNull { it.tag == GalaxyLogger.TAG_FALLBACK_DECISION }
        assertNotNull(entry)
        assertEquals(TakeoverFallbackEvent.Cause.TIMEOUT.wireValue, entry!!.fields["cause"])
    }

    @Test
    fun `TAG_FALLBACK_DECISION is emitted for CANCELLED cause`() = runBlocking {
        GalaxyLogger.clear()
        val (controller, _) = buildController()
        val event = makeFailureEvent(TakeoverFallbackEvent.Cause.CANCELLED)
        controller.notifyTakeoverFailed(
            takeoverId = event.takeoverId,
            taskId = event.taskId,
            traceId = event.traceId,
            reason = event.reason,
            cause = event.cause
        )
        val entry = GalaxyLogger.getEntries()
            .lastOrNull { it.tag == GalaxyLogger.TAG_FALLBACK_DECISION }
        assertNotNull(entry)
        assertEquals(TakeoverFallbackEvent.Cause.CANCELLED.wireValue, entry!!.fields["cause"])
    }

    @Test
    fun `TAG_FALLBACK_DECISION is emitted for DISCONNECT cause`() = runBlocking {
        GalaxyLogger.clear()
        val (controller, _) = buildController()
        val event = makeFailureEvent(TakeoverFallbackEvent.Cause.DISCONNECT)
        controller.notifyTakeoverFailed(
            takeoverId = event.takeoverId,
            taskId = event.taskId,
            traceId = event.traceId,
            reason = event.reason,
            cause = event.cause
        )
        val entry = GalaxyLogger.getEntries()
            .lastOrNull { it.tag == GalaxyLogger.TAG_FALLBACK_DECISION }
        assertNotNull(entry)
        assertEquals(TakeoverFallbackEvent.Cause.DISCONNECT.wireValue, entry!!.fields["cause"])
    }

    @Test
    fun `TAG_FALLBACK_DECISION deduplication — second call for same takeoverId emits only one entry`() =
        runBlocking {
            GalaxyLogger.clear()
            val (controller, _) = buildController()
            val takeoverId = "takeover-dedup-pr30"
            controller.notifyTakeoverFailed(
                takeoverId = takeoverId,
                taskId = "task-dedup",
                traceId = "trace-dedup",
                reason = "first failure",
                cause = TakeoverFallbackEvent.Cause.FAILED
            )
            // Second call with the same takeoverId should be deduplicated
            controller.notifyTakeoverFailed(
                takeoverId = takeoverId,
                taskId = "task-dedup",
                traceId = "trace-dedup",
                reason = "second failure duplicate",
                cause = TakeoverFallbackEvent.Cause.FAILED
            )
            val fallbackEntries = GalaxyLogger.getEntries()
                .filter {
                    it.tag == GalaxyLogger.TAG_FALLBACK_DECISION &&
                        it.fields["takeover_id"] == takeoverId
                }
            assertEquals(
                "Duplicate takeover failure must emit TAG_FALLBACK_DECISION only once",
                1,
                fallbackEntries.size
            )
        }

    // ── Diagnostics text sections ─────────────────────────────────────────────

    @Test
    fun `diagnostics text includes Execution Routes section`() {
        val text = buildDiagnosticsTextFromState(MainUiState())
        assertTrue(text.contains("-- Execution Routes (this session) --"))
    }

    @Test
    fun `diagnostics text shows no tasks when executionRouteCounts is empty`() {
        val text = buildDiagnosticsTextFromState(MainUiState(executionRouteCounts = emptyMap()))
        assertTrue(text.contains("(no tasks completed)"))
    }

    @Test
    fun `diagnostics text shows local route count`() {
        val state = MainUiState(executionRouteCounts = mapOf(ExecutionRouteTag.LOCAL to 7))
        val text = buildDiagnosticsTextFromState(state)
        assertTrue(text.contains("local: 7"))
    }

    @Test
    fun `diagnostics text shows cross_device route count`() {
        val state = MainUiState(executionRouteCounts = mapOf(ExecutionRouteTag.CROSS_DEVICE to 2))
        val text = buildDiagnosticsTextFromState(state)
        assertTrue(text.contains("cross_device: 2"))
    }

    @Test
    fun `diagnostics text shows fallback route count`() {
        val state = MainUiState(executionRouteCounts = mapOf(ExecutionRouteTag.FALLBACK to 1))
        val text = buildDiagnosticsTextFromState(state)
        assertTrue(text.contains("fallback: 1"))
    }

    @Test
    fun `diagnostics text omits zero-count routes`() {
        val state = MainUiState(
            executionRouteCounts = mapOf(ExecutionRouteTag.LOCAL to 1)
        )
        val text = buildDiagnosticsTextFromState(state)
        assertFalse(text.contains("cross_device: 0"))
        assertFalse(text.contains("delegated: 0"))
        assertFalse(text.contains("fallback: 0"))
    }

    @Test
    fun `diagnostics text does not show last setup failure when null`() {
        val text = buildDiagnosticsTextFromState(MainUiState(lastSetupFailureCategory = null))
        assertFalse(text.contains("Last setup failure:"))
    }

    @Test
    fun `diagnostics text shows last setup failure category when NETWORK`() {
        val state = MainUiState(lastSetupFailureCategory = CrossDeviceSetupError.Category.NETWORK)
        val text = buildDiagnosticsTextFromState(state)
        assertTrue(text.contains("Last setup failure: network"))
    }

    @Test
    fun `diagnostics text shows last setup failure category when CONFIGURATION`() {
        val state = MainUiState(lastSetupFailureCategory = CrossDeviceSetupError.Category.CONFIGURATION)
        val text = buildDiagnosticsTextFromState(state)
        assertTrue(text.contains("Last setup failure: configuration"))
    }

    @Test
    fun `diagnostics text shows last setup failure category when CAPABILITY_NOT_SATISFIED`() {
        val state = MainUiState(
            lastSetupFailureCategory = CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED
        )
        val text = buildDiagnosticsTextFromState(state)
        assertTrue(text.contains("Last setup failure: capability_not_satisfied"))
    }

    @Test
    fun `diagnostics text shows last execution route for LOCAL`() {
        val state = MainUiState(lastExecutionRoute = ExecutionRouteTag.LOCAL)
        val text = buildDiagnosticsTextFromState(state)
        assertTrue(text.contains("Route: local"))
    }

    @Test
    fun `diagnostics text shows last execution route for FALLBACK`() {
        val state = MainUiState(lastExecutionRoute = ExecutionRouteTag.FALLBACK)
        val text = buildDiagnosticsTextFromState(state)
        assertTrue(text.contains("Route: fallback"))
    }

    @Test
    fun `diagnostics text shows route none before first task`() {
        val state = MainUiState(lastExecutionRoute = null)
        val text = buildDiagnosticsTextFromState(state)
        assertTrue(text.contains("Route: none"))
    }

    // ── Helper: mirrors MainViewModel.buildDiagnosticsText ────────────────────

    /**
     * Mirrors [com.ufo.galaxy.ui.viewmodel.MainViewModel.buildDiagnosticsText] to allow
     * JVM-only assertion without an Android Application instance.
     *
     * Must be kept in sync with the real implementation in MainViewModel whenever new
     * sections are added.  Kept minimal (no timestamp formatting) so that assertions
     * remain deterministic.
     */
    private fun buildDiagnosticsTextFromState(s: MainUiState): String {
        return buildString {
            appendLine("=== UFO Galaxy Diagnostics ===")
            appendLine("Time: <test>")
            appendLine()
            appendLine("-- Connection --")
            appendLine("State: ${if (s.isConnected) "Connected" else "Disconnected"}")
            appendLine("Network OK: ${s.networkOk}")
            appendLine("Reconnect attempts: ${s.reconnectAttempt}")
            appendLine("Offline queue: ${s.queueSize}")
            appendLine()
            appendLine("-- Readiness --")
            appendLine("Model files: ${if (s.modelReady) "Ready" else "Not ready"}")
            appendLine("Accessibility: ${if (s.accessibilityReady) "Enabled" else "Disabled"}")
            appendLine("Overlay (SYSTEM_ALERT_WINDOW): ${if (s.overlayReady) "Granted" else "Denied"}")
            appendLine("Degraded mode: ${if (s.degradedMode) "Yes" else "No"}")
            appendLine()
            appendLine("-- Health Checklist --")
            appendLine("Network OK: ${s.networkOk}")
            appendLine("Permissions OK: ${s.accessibilityReady && s.overlayReady}")
            appendLine("Battery optimizations disabled: ${s.batteryOptimizationsDisabled}")
            appendLine()
            // PR-29
            appendLine("-- Last Execution Route --")
            appendLine("Route: ${s.lastExecutionRoute?.wireValue ?: "none"}")
            appendLine()
            // PR-30
            appendLine("-- Execution Routes (this session) --")
            if (s.executionRouteCounts.isEmpty()) {
                appendLine("  (no tasks completed)")
            } else {
                ExecutionRouteTag.values().forEach { tag ->
                    val count = s.executionRouteCounts[tag] ?: 0
                    if (count > 0) appendLine("  ${tag.wireValue}: $count")
                }
            }
            if (s.lastSetupFailureCategory != null) {
                appendLine("Last setup failure: ${s.lastSetupFailureCategory.wireValue}")
            }
            appendLine()
            appendLine("-- Recent Errors (last ${s.recentErrors.size}) --")
            if (s.recentErrors.isEmpty()) {
                appendLine("  (none)")
            } else {
                s.recentErrors.forEach { e -> appendLine("  [ts] ${e.reason}") }
            }
            appendLine()
            appendLine("-- Recent Tasks (last ${s.recentTaskIds.size}) --")
            if (s.recentTaskIds.isEmpty()) {
                appendLine("  (none)")
            } else {
                s.recentTaskIds.forEach { t -> appendLine("  [ts] ${t.taskId} → ${t.outcome}") }
            }
        }.trimEnd()
    }
}
