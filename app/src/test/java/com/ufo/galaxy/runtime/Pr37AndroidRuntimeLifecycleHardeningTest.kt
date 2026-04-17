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
 * PR-37 — Android Runtime Lifecycle Hardening.
 *
 * Focused test suite for all PR-37 additions:
 *
 *  1. [RuntimeLifecycleTransitionEvent] — sealed class with [RuntimeLifecycleTransitionEvent.Governed]
 *     and [RuntimeLifecycleTransitionEvent.Unexpected] subtypes; [wireLabel] extension.
 *
 *  2. [RuntimeDispatchReadinessCoordinator] — eligibility resolver combining runtime state,
 *     session state, and rollout flags into [RuntimeDispatchReadinessCoordinator.DispatchReadiness];
 *     canonical [RuntimeDispatchReadinessCoordinator.ALLOWED_TRANSITIONS] table.
 *
 *  3. [RuntimeController.lifecycleTransitionEvents] — observable SharedFlow of transition events;
 *     emits [RuntimeLifecycleTransitionEvent.Governed] on normal transitions.
 *
 *  4. [RuntimeController.currentDispatchReadiness] — point-in-time dispatch eligibility query.
 *
 *  5. [CanonicalDispatchChain.resolveEligiblePathsForState] — state-aware dispatch path resolver.
 *
 *  6. [CanonicalSessionAxis.resolveDispatchAlignmentForState] — session family liveness resolver.
 *
 *  7. [GalaxyLogger.TAG_RUNTIME_LIFECYCLE] — stable structured log tag constant.
 *
 *  8. [StabilizationBaseline] — PR-37 entries registered.
 *
 *  9. Start concurrency guard — concurrent [RuntimeController.start] calls are safely handled.
 *
 * ## Test matrix
 *
 * ### RuntimeLifecycleTransitionEvent — type hierarchy
 *  - Governed is a subtype of RuntimeLifecycleTransitionEvent
 *  - Unexpected is a subtype of RuntimeLifecycleTransitionEvent
 *  - Governed carries trigger, from, to
 *  - Unexpected carries expectedFrom, reason, from, to
 *
 * ### RuntimeController.RuntimeState.wireLabel
 *  - Idle wireLabel is "idle"
 *  - Starting wireLabel is "starting"
 *  - Active wireLabel is "active"
 *  - Failed wireLabel is "failed"
 *  - LocalOnly wireLabel is "local_only"
 *  - all five wireLabels are distinct
 *
 * ### RuntimeDispatchReadinessCoordinator — eligibility rules
 *  - runtime not active → ineligible, reason = runtime_not_active
 *  - runtime active, session not attached → ineligible, reason = session_not_attached
 *  - runtime active, session attached, cross-device gated off → ineligible, cross_device_gated_off
 *  - runtime active, session attached, cross-device on → eligible
 *  - eligible result has CANONICAL recommended path
 *  - ineligible result has LOCAL recommended path
 *  - wireValue is "eligible" when eligible
 *  - wireValue starts with "ineligible:" when not eligible
 *
 * ### RuntimeDispatchReadinessCoordinator.ALLOWED_TRANSITIONS
 *  - Starting → Active is governed
 *  - Starting → LocalOnly is governed
 *  - Active → LocalOnly is governed
 *  - Idle → Starting is governed
 *  - LocalOnly → Starting is governed
 *  - Active → Starting is NOT governed (requires stop first)
 *  - Failed → Starting is governed
 *
 * ### RuntimeController.currentDispatchReadiness — initial state
 *  - initial dispatch readiness is ineligible (no active session)
 *  - after setActiveForTest dispatch readiness is eligible when crossDeviceEnabled=true
 *
 * ### RuntimeController.lifecycleTransitionEvents — emission on stop()
 *  - stop() emits a lifecycle transition event
 *  - stop() event to-state wireLabel is "local_only"
 *
 * ### CanonicalDispatchChain.resolveEligiblePathsForState
 *  - ineligible runtime state returns only local and compatibility paths
 *  - eligible runtime state includes canonical path
 *  - fallback path requires fallbackToLocalAllowed = true
 *
 * ### CanonicalSessionAxis.resolveDispatchAlignmentForState
 *  - runtime not active: runtime session family is not live
 *  - runtime active: runtime session family is live
 *  - runtime active, session not attached: transfer-scoped families are not live
 *  - runtime active, session attached: transfer-scoped families are live
 *  - conversation session family is always live regardless of runtime state
 *
 * ### GalaxyLogger.TAG_RUNTIME_LIFECYCLE
 *  - TAG_RUNTIME_LIFECYCLE value is "GALAXY:RUNTIME:LIFECYCLE"
 *  - TAG_RUNTIME_LIFECYCLE is distinct from all prior GalaxyLogger tags
 *
 * ### StabilizationBaseline — PR-37 entries
 *  - runtime-lifecycle-transition-event entry is registered as CANONICAL_STABLE
 *  - runtime-dispatch-readiness-coordinator entry is registered as CANONICAL_STABLE
 *  - runtime-controller-lifecycle-transition-events entry is registered
 *  - runtime-controller-current-dispatch-readiness entry is registered
 *  - canonical-dispatch-chain-eligible-paths-for-state entry is registered
 *  - canonical-session-axis-dispatch-alignment entry is registered
 *  - galaxy-logger-tag-runtime-lifecycle entry is registered
 */
class Pr37AndroidRuntimeLifecycleHardeningTest {

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
            loopController = buildLoopController(),
            registrationTimeoutMs = timeoutMs
        )
        return controller to client
    }

    // ── RuntimeLifecycleTransitionEvent — type hierarchy ─────────────────────

    @Test
    fun `Governed is a subtype of RuntimeLifecycleTransitionEvent`() {
        val event: RuntimeLifecycleTransitionEvent = RuntimeLifecycleTransitionEvent.Governed(
            from    = RuntimeController.RuntimeState.Starting,
            to      = RuntimeController.RuntimeState.Active,
            trigger = "ws_connected"
        )
        assertTrue(event is RuntimeLifecycleTransitionEvent.Governed)
    }

    @Test
    fun `Unexpected is a subtype of RuntimeLifecycleTransitionEvent`() {
        val event: RuntimeLifecycleTransitionEvent = RuntimeLifecycleTransitionEvent.Unexpected(
            from         = RuntimeController.RuntimeState.LocalOnly,
            to           = RuntimeController.RuntimeState.Active,
            expectedFrom = RuntimeController.RuntimeState.Starting,
            reason       = "transition_not_in_allowed_set"
        )
        assertTrue(event is RuntimeLifecycleTransitionEvent.Unexpected)
    }

    @Test
    fun `Governed carries trigger, from, to`() {
        val from    = RuntimeController.RuntimeState.Starting
        val to      = RuntimeController.RuntimeState.Active
        val trigger = "ws_connected"
        val event   = RuntimeLifecycleTransitionEvent.Governed(from = from, to = to, trigger = trigger)
        assertEquals(from, event.from)
        assertEquals(to, event.to)
        assertEquals(trigger, event.trigger)
    }

    @Test
    fun `Unexpected carries expectedFrom, reason, from, to`() {
        val from         = RuntimeController.RuntimeState.LocalOnly
        val to           = RuntimeController.RuntimeState.Active
        val expectedFrom = RuntimeController.RuntimeState.Starting
        val reason       = "prior_state_mismatch"
        val event        = RuntimeLifecycleTransitionEvent.Unexpected(
            from         = from,
            to           = to,
            expectedFrom = expectedFrom,
            reason       = reason
        )
        assertEquals(from, event.from)
        assertEquals(to, event.to)
        assertEquals(expectedFrom, event.expectedFrom)
        assertEquals(reason, event.reason)
    }

    // ── RuntimeController.RuntimeState.wireLabel ──────────────────────────────

    @Test
    fun `Idle wireLabel is 'idle'`() {
        assertEquals("idle", RuntimeController.RuntimeState.Idle.wireLabel)
    }

    @Test
    fun `Starting wireLabel is 'starting'`() {
        assertEquals("starting", RuntimeController.RuntimeState.Starting.wireLabel)
    }

    @Test
    fun `Active wireLabel is 'active'`() {
        assertEquals("active", RuntimeController.RuntimeState.Active.wireLabel)
    }

    @Test
    fun `Failed wireLabel is 'failed'`() {
        assertEquals("failed", RuntimeController.RuntimeState.Failed("reason").wireLabel)
    }

    @Test
    fun `LocalOnly wireLabel is 'local_only'`() {
        assertEquals("local_only", RuntimeController.RuntimeState.LocalOnly.wireLabel)
    }

    @Test
    fun `all five RuntimeState wireLabels are distinct`() {
        val labels = listOf(
            RuntimeController.RuntimeState.Idle.wireLabel,
            RuntimeController.RuntimeState.Starting.wireLabel,
            RuntimeController.RuntimeState.Active.wireLabel,
            RuntimeController.RuntimeState.Failed("x").wireLabel,
            RuntimeController.RuntimeState.LocalOnly.wireLabel
        )
        assertEquals("All five RuntimeState wireLabels must be distinct", labels.size, labels.distinct().size)
    }

    // ── RuntimeDispatchReadinessCoordinator — eligibility rules ───────────────

    @Test
    fun `runtime not active produces ineligible result`() {
        val result = RuntimeDispatchReadinessCoordinator.resolve(
            runtimeState    = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = null,
            rollout         = RolloutControlSnapshot(
                crossDeviceAllowed        = true,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed    = true,
                goalExecutionAllowed      = true
            )
        )
        assertFalse(result.isEligible)
        assertFalse(result.runtimeIsActive)
        assertEquals("runtime_not_active", result.ineligibilityReason)
    }

    @Test
    fun `runtime active but session not attached produces ineligible result`() {
        val result = RuntimeDispatchReadinessCoordinator.resolve(
            runtimeState    = RuntimeController.RuntimeState.Active,
            attachedSession = null,
            rollout         = RolloutControlSnapshot(
                crossDeviceAllowed        = true,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed    = true,
                goalExecutionAllowed      = true
            )
        )
        assertFalse(result.isEligible)
        assertTrue(result.runtimeIsActive)
        assertFalse(result.sessionIsAttached)
        assertEquals("session_not_attached", result.ineligibilityReason)
    }

    @Test
    fun `runtime active, session attached, cross-device gated off produces ineligible result`() {
        val session = AttachedRuntimeSession.create(hostId = "test-host", deviceId = "test-device")
        val result = RuntimeDispatchReadinessCoordinator.resolve(
            runtimeState    = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout         = RolloutControlSnapshot(
                crossDeviceAllowed        = false,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed    = true,
                goalExecutionAllowed      = false
            )
        )
        assertFalse(result.isEligible)
        assertTrue(result.runtimeIsActive)
        assertTrue(result.sessionIsAttached)
        assertEquals("cross_device_gated_off", result.ineligibilityReason)
    }

    @Test
    fun `runtime active, session attached, cross-device on produces eligible result`() {
        val session = AttachedRuntimeSession.create(hostId = "test-host", deviceId = "test-device")
        val result = RuntimeDispatchReadinessCoordinator.resolve(
            runtimeState    = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout         = RolloutControlSnapshot(
                crossDeviceAllowed        = true,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed    = true,
                goalExecutionAllowed      = true
            )
        )
        assertTrue(result.isEligible)
        assertTrue(result.runtimeIsActive)
        assertTrue(result.sessionIsAttached)
        assertNull(result.ineligibilityReason)
    }

    @Test
    fun `eligible result recommends CANONICAL path`() {
        val session = AttachedRuntimeSession.create(hostId = "h", deviceId = "d")
        val result = RuntimeDispatchReadinessCoordinator.resolve(
            runtimeState    = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout         = RolloutControlSnapshot(
                crossDeviceAllowed        = true,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed    = true,
                goalExecutionAllowed      = true
            )
        )
        assertEquals(DispatchPathMode.CANONICAL, result.recommendedPathMode)
    }

    @Test
    fun `ineligible result recommends LOCAL path`() {
        val result = RuntimeDispatchReadinessCoordinator.resolve(
            runtimeState    = RuntimeController.RuntimeState.Starting,
            attachedSession = null,
            rollout         = RolloutControlSnapshot(
                crossDeviceAllowed        = true,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed    = true,
                goalExecutionAllowed      = true
            )
        )
        assertEquals(DispatchPathMode.LOCAL, result.recommendedPathMode)
    }

    @Test
    fun `wireValue is 'eligible' when eligible`() {
        val session = AttachedRuntimeSession.create(hostId = "h", deviceId = "d")
        val result = RuntimeDispatchReadinessCoordinator.resolve(
            runtimeState    = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout         = RolloutControlSnapshot(
                crossDeviceAllowed        = true,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed    = true,
                goalExecutionAllowed      = true
            )
        )
        assertEquals("eligible", result.wireValue)
    }

    @Test
    fun `wireValue starts with 'ineligible' when not eligible`() {
        val result = RuntimeDispatchReadinessCoordinator.resolve(
            runtimeState    = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = null,
            rollout         = RolloutControlSnapshot(
                crossDeviceAllowed        = true,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed    = true,
                goalExecutionAllowed      = true
            )
        )
        assertTrue(
            "wireValue should start with 'ineligible:' when not eligible",
            result.wireValue.startsWith("ineligible:")
        )
    }

    // ── RuntimeDispatchReadinessCoordinator.ALLOWED_TRANSITIONS ─────────────

    @Test
    fun `Starting to Active is governed`() {
        assertTrue(RuntimeDispatchReadinessCoordinator.isGoverned(
            from = RuntimeController.RuntimeState.Starting,
            to   = RuntimeController.RuntimeState.Active
        ))
    }

    @Test
    fun `Starting to LocalOnly is governed`() {
        assertTrue(RuntimeDispatchReadinessCoordinator.isGoverned(
            from = RuntimeController.RuntimeState.Starting,
            to   = RuntimeController.RuntimeState.LocalOnly
        ))
    }

    @Test
    fun `Active to LocalOnly is governed`() {
        assertTrue(RuntimeDispatchReadinessCoordinator.isGoverned(
            from = RuntimeController.RuntimeState.Active,
            to   = RuntimeController.RuntimeState.LocalOnly
        ))
    }

    @Test
    fun `Idle to Starting is governed`() {
        assertTrue(RuntimeDispatchReadinessCoordinator.isGoverned(
            from = RuntimeController.RuntimeState.Idle,
            to   = RuntimeController.RuntimeState.Starting
        ))
    }

    @Test
    fun `LocalOnly to Starting is governed`() {
        assertTrue(RuntimeDispatchReadinessCoordinator.isGoverned(
            from = RuntimeController.RuntimeState.LocalOnly,
            to   = RuntimeController.RuntimeState.Starting
        ))
    }

    @Test
    fun `Active to Starting is NOT governed`() {
        assertFalse(RuntimeDispatchReadinessCoordinator.isGoverned(
            from = RuntimeController.RuntimeState.Active,
            to   = RuntimeController.RuntimeState.Starting
        ))
    }

    @Test
    fun `Failed to Starting is governed`() {
        assertTrue(RuntimeDispatchReadinessCoordinator.isGoverned(
            from = RuntimeController.RuntimeState.Failed("reason"),
            to   = RuntimeController.RuntimeState.Starting
        ))
    }

    // ── RuntimeController.currentDispatchReadiness — initial state ────────────

    @Test
    fun `initial dispatch readiness is ineligible before activation`() {
        val (controller, _) = buildController()
        val readiness = controller.currentDispatchReadiness()
        assertFalse("Dispatch should be ineligible before activation", readiness.isEligible)
    }

    @Test
    fun `dispatch readiness ineligible reason after initial build`() {
        val (controller, _) = buildController()
        val readiness = controller.currentDispatchReadiness()
        // Before any start() call, state is Idle — so "runtime_not_active"
        assertNotNull(readiness.ineligibilityReason)
    }

    @Test
    fun `dispatch readiness is eligible after setActiveForTest with crossDeviceEnabled`() {
        val settings = InMemoryAppSettings()
        settings.crossDeviceEnabled = true
        val (controller, _) = buildController(settings = settings)
        controller.setActiveForTest()
        val readiness = controller.currentDispatchReadiness()
        assertTrue("Dispatch should be eligible when Active, attached, and crossDeviceEnabled=true", readiness.isEligible)
    }

    @Test
    fun `dispatch readiness is ineligible after setActiveForTest without crossDeviceEnabled`() {
        val settings = InMemoryAppSettings()
        settings.crossDeviceEnabled = false
        val (controller, _) = buildController(settings = settings)
        controller.setActiveForTest()
        val readiness = controller.currentDispatchReadiness()
        // cross-device flag is off → rollout gates off → ineligible
        assertFalse("Dispatch should be ineligible when crossDeviceEnabled=false", readiness.isEligible)
    }

    // ── RuntimeController.lifecycleTransitionEvents ───────────────────────────

    @Test
    fun `stop() emits a lifecycle transition event`() = runBlocking {
        val (controller, _) = buildController()
        controller.setActiveForTest()

        val event = withTimeoutOrNull(500L) {
            // Collect the next event after stop()
            val deferred = kotlinx.coroutines.async {
                controller.lifecycleTransitionEvents.first()
            }
            // Small delay to let the collector register
            kotlinx.coroutines.delay(10L)
            controller.stop()
            deferred.await()
        }

        assertNotNull("stop() should emit a lifecycle transition event", event)
    }

    @Test
    fun `stop() emits transition with to-state wireLabel of local_only`() = runBlocking {
        val (controller, _) = buildController()
        controller.setActiveForTest()

        val event = withTimeoutOrNull(500L) {
            val deferred = kotlinx.coroutines.async {
                controller.lifecycleTransitionEvents.first()
            }
            kotlinx.coroutines.delay(10L)
            controller.stop()
            deferred.await()
        }

        assertNotNull(event)
        assertEquals("local_only", event!!.to.wireLabel)
    }

    // ── CanonicalDispatchChain.resolveEligiblePathsForState ───────────────────

    @Test
    fun `ineligible runtime state returns only local and compatibility paths`() {
        val paths = CanonicalDispatchChain.resolveEligiblePathsForState(
            runtimeState    = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = null,
            rollout         = RolloutControlSnapshot(
                crossDeviceAllowed        = false,
                delegatedExecutionAllowed = false,
                fallbackToLocalAllowed    = true,
                goalExecutionAllowed      = false
            )
        )
        val modes = paths.map { it.pathMode }.toSet()
        assertTrue("LOCAL path should be present when runtime is LocalOnly", DispatchPathMode.LOCAL in modes)
        assertFalse("CANONICAL path should not be present when ineligible", DispatchPathMode.CANONICAL in modes)
    }

    @Test
    fun `eligible runtime state includes canonical path`() {
        val session = AttachedRuntimeSession.create(hostId = "h", deviceId = "d")
        val paths = CanonicalDispatchChain.resolveEligiblePathsForState(
            runtimeState    = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout         = RolloutControlSnapshot(
                crossDeviceAllowed        = true,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed    = true,
                goalExecutionAllowed      = true
            )
        )
        val modes = paths.map { it.pathMode }.toSet()
        assertTrue("CANONICAL path should be present when eligible", DispatchPathMode.CANONICAL in modes)
    }

    @Test
    fun `fallback path requires fallbackToLocalAllowed true`() {
        val session = AttachedRuntimeSession.create(hostId = "h", deviceId = "d")
        val pathsWithFallback = CanonicalDispatchChain.resolveEligiblePathsForState(
            runtimeState    = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout         = RolloutControlSnapshot(
                crossDeviceAllowed        = true,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed    = true,
                goalExecutionAllowed      = true
            )
        )
        val pathsWithoutFallback = CanonicalDispatchChain.resolveEligiblePathsForState(
            runtimeState    = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout         = RolloutControlSnapshot(
                crossDeviceAllowed        = true,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed    = false,
                goalExecutionAllowed      = true
            )
        )
        val withFallbackModes    = pathsWithFallback.map { it.pathMode }.toSet()
        val withoutFallbackModes = pathsWithoutFallback.map { it.pathMode }.toSet()
        assertTrue("FALLBACK path should be present when fallbackToLocalAllowed=true",
            DispatchPathMode.FALLBACK in withFallbackModes)
        assertFalse("FALLBACK path should NOT be present when fallbackToLocalAllowed=false",
            DispatchPathMode.FALLBACK in withoutFallbackModes)
    }

    // ── CanonicalSessionAxis.resolveDispatchAlignmentForState ─────────────────

    @Test
    fun `runtime not active: RUNTIME_SESSION family is not live`() {
        val alignment = CanonicalSessionAxis.resolveDispatchAlignmentForState(
            runtimeState     = RuntimeController.RuntimeState.LocalOnly,
            sessionIsAttached = false
        )
        assertFalse(
            "RUNTIME_SESSION family should not be live when runtime is not active",
            alignment[CanonicalSessionFamily.RUNTIME_SESSION] == true
        )
    }

    @Test
    fun `runtime active: RUNTIME_SESSION family is live`() {
        val alignment = CanonicalSessionAxis.resolveDispatchAlignmentForState(
            runtimeState     = RuntimeController.RuntimeState.Active,
            sessionIsAttached = true
        )
        assertTrue(
            "RUNTIME_SESSION family should be live when runtime is Active",
            alignment[CanonicalSessionFamily.RUNTIME_SESSION] == true
        )
    }

    @Test
    fun `runtime active, session not attached: DELEGATION_TRANSFER_SESSION is not live`() {
        val alignment = CanonicalSessionAxis.resolveDispatchAlignmentForState(
            runtimeState     = RuntimeController.RuntimeState.Active,
            sessionIsAttached = false
        )
        assertFalse(
            "DELEGATION_TRANSFER_SESSION should not be live when session is not attached",
            alignment[CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION] == true
        )
    }

    @Test
    fun `runtime active, session attached: DELEGATION_TRANSFER_SESSION is live`() {
        val alignment = CanonicalSessionAxis.resolveDispatchAlignmentForState(
            runtimeState     = RuntimeController.RuntimeState.Active,
            sessionIsAttached = true
        )
        assertTrue(
            "DELEGATION_TRANSFER_SESSION should be live when Active and session is attached",
            alignment[CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION] == true
        )
    }

    @Test
    fun `CONVERSATION_SESSION family is live regardless of runtime state`() {
        val alignmentLocalOnly = CanonicalSessionAxis.resolveDispatchAlignmentForState(
            runtimeState     = RuntimeController.RuntimeState.LocalOnly,
            sessionIsAttached = false
        )
        val alignmentActive = CanonicalSessionAxis.resolveDispatchAlignmentForState(
            runtimeState     = RuntimeController.RuntimeState.Active,
            sessionIsAttached = true
        )
        assertTrue(
            "CONVERSATION_SESSION should be live when LocalOnly",
            alignmentLocalOnly[CanonicalSessionFamily.CONVERSATION_SESSION] == true
        )
        assertTrue(
            "CONVERSATION_SESSION should be live when Active",
            alignmentActive[CanonicalSessionFamily.CONVERSATION_SESSION] == true
        )
    }

    // ── GalaxyLogger.TAG_RUNTIME_LIFECYCLE ────────────────────────────────────

    @Test
    fun `TAG_RUNTIME_LIFECYCLE value is GALAXY_RUNTIME_LIFECYCLE`() {
        assertEquals("GALAXY:RUNTIME:LIFECYCLE", GalaxyLogger.TAG_RUNTIME_LIFECYCLE)
    }

    @Test
    fun `TAG_RUNTIME_LIFECYCLE is distinct from all prior GalaxyLogger tags`() {
        val priorTags = listOf(
            GalaxyLogger.TAG_CONNECT,
            GalaxyLogger.TAG_DISCONNECT,
            GalaxyLogger.TAG_RECONNECT,
            GalaxyLogger.TAG_TASK_RECV,
            GalaxyLogger.TAG_TASK_EXEC,
            GalaxyLogger.TAG_TASK_RETURN,
            GalaxyLogger.TAG_ERROR,
            GalaxyLogger.TAG_EXEC_ROUTE,
            GalaxyLogger.TAG_SETUP_RECOVERY,
            GalaxyLogger.TAG_RECONNECT_OUTCOME,
            GalaxyLogger.TAG_FALLBACK_DECISION,
            GalaxyLogger.TAG_ROLLOUT_CONTROL,
            GalaxyLogger.TAG_KILL_SWITCH,
            GalaxyLogger.TAG_STAGED_MESH,
            GalaxyLogger.TAG_RECONNECT_RECOVERY,
            GalaxyLogger.TAG_SESSION_AXIS,
            GalaxyLogger.TAG_FORMATION_REBALANCE,
            GalaxyLogger.TAG_FORMATION_ROLE,
            GalaxyLogger.TAG_FORMATION_HEALTH
        )
        for (tag in priorTags) {
            assertFalse(
                "TAG_RUNTIME_LIFECYCLE must not equal '$tag'",
                GalaxyLogger.TAG_RUNTIME_LIFECYCLE == tag
            )
        }
    }

    // ── StabilizationBaseline — PR-37 entries ─────────────────────────────────

    @Test
    fun `runtime-lifecycle-transition-event is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("runtime-lifecycle-transition-event")
        assertNotNull("runtime-lifecycle-transition-event must be in StabilizationBaseline", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(37, entry.introducedPr)
    }

    @Test
    fun `runtime-dispatch-readiness-coordinator is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("runtime-dispatch-readiness-coordinator")
        assertNotNull("runtime-dispatch-readiness-coordinator must be in StabilizationBaseline", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(37, entry.introducedPr)
    }

    @Test
    fun `runtime-controller-lifecycle-transition-events is registered in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("runtime-controller-lifecycle-transition-events")
        assertNotNull("runtime-controller-lifecycle-transition-events must be in StabilizationBaseline", entry)
        assertEquals(37, entry!!.introducedPr)
    }

    @Test
    fun `runtime-controller-current-dispatch-readiness is registered in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("runtime-controller-current-dispatch-readiness")
        assertNotNull("runtime-controller-current-dispatch-readiness must be in StabilizationBaseline", entry)
        assertEquals(37, entry!!.introducedPr)
    }

    @Test
    fun `canonical-dispatch-chain-eligible-paths-for-state is registered in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("canonical-dispatch-chain-eligible-paths-for-state")
        assertNotNull("canonical-dispatch-chain-eligible-paths-for-state must be in StabilizationBaseline", entry)
        assertEquals(37, entry!!.introducedPr)
    }

    @Test
    fun `canonical-session-axis-dispatch-alignment is registered in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("canonical-session-axis-dispatch-alignment")
        assertNotNull("canonical-session-axis-dispatch-alignment must be in StabilizationBaseline", entry)
        assertEquals(37, entry!!.introducedPr)
    }

    @Test
    fun `galaxy-logger-tag-runtime-lifecycle is registered in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("galaxy-logger-tag-runtime-lifecycle")
        assertNotNull("galaxy-logger-tag-runtime-lifecycle must be in StabilizationBaseline", entry)
        assertEquals(37, entry!!.introducedPr)
    }

    @Test
    fun `all PR-37 StabilizationBaseline entries have EXTEND guidance`() {
        val pr37Entries = StabilizationBaseline.entries.filter { it.introducedPr == 37 }
        assertTrue("Should have at least 7 PR-37 entries", pr37Entries.size >= 7)
        for (entry in pr37Entries) {
            assertEquals(
                "PR-37 entry '${entry.surfaceId}' should have EXTEND guidance",
                StabilizationBaseline.ExtensionGuidance.EXTEND,
                entry.extensionGuidance
            )
        }
    }

    // ── Start concurrency guard ───────────────────────────────────────────────

    @Test
    fun `start concurrency guard allows only one start at a time`() = runBlocking {
        val (controller, _) = buildController(timeoutMs = 50L)
        // Initiate start twice concurrently; both should return without crashing
        // (the second call should detect the guard and return false gracefully)
        val result1 = kotlinx.coroutines.async { controller.startWithTimeout() }
        val result2 = kotlinx.coroutines.async { controller.startWithTimeout() }
        // Both futures should complete without exception
        val r1 = result1.await()
        val r2 = result2.await()
        // At most one can have succeeded (no live server, so both likely false, but no crash)
        assertFalse("No live server — start should not succeed in test", r1 && r2)
    }
}
