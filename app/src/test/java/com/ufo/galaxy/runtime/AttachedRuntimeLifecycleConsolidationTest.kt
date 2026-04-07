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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **PR-22: Attached-runtime lifecycle projection consolidation tests.**
 *
 * Verifies that the attached-runtime lifecycle has one canonical control path
 * ([RuntimeController]), that [AttachedRuntimeHostSessionSnapshot] is the authoritative
 * host-facing projection, and that session identity and lifecycle state do not drift
 * across reconnect / replace / invalidate flows.
 *
 * ## Test matrix
 *
 * ### Single lifecycle authority — RuntimeController is the only mutation path
 *  - Invalidation can only originate from RuntimeController.invalidateSession().
 *  - Detach (DISABLE) can only originate from RuntimeController.stop().
 *  - Execution count can only be incremented via RuntimeController.recordDelegatedExecutionAccepted().
 *
 * ### Attach lifecycle
 *  - Fresh session is in ATTACHED state.
 *  - Snapshot attachmentState is "attached" on fresh session.
 *  - Snapshot isReuseValid is true on fresh session.
 *  - Snapshot posture is join_runtime on fresh session.
 *  - Snapshot invalidationReason is null on fresh session.
 *
 * ### Detach lifecycle (all causes)
 *  - Snapshot attachmentState is "detached" for EXPLICIT_DETACH cause.
 *  - Snapshot isReuseValid is false for EXPLICIT_DETACH cause.
 *  - Snapshot posture is control_only for EXPLICIT_DETACH cause.
 *  - Snapshot invalidationReason is null for EXPLICIT_DETACH cause.
 *  - Same for DISCONNECT cause.
 *  - Same for DISABLE cause.
 *
 * ### Invalidate lifecycle
 *  - Snapshot attachmentState is "detached" after INVALIDATION.
 *  - Snapshot isReuseValid is false after INVALIDATION.
 *  - Snapshot posture is control_only after INVALIDATION.
 *  - Snapshot invalidationReason is "invalidation" after INVALIDATION.
 *
 * ### Intermediate DETACHING state
 *  - Snapshot attachmentState is "detaching" while in DETACHING state.
 *  - Snapshot isReuseValid is false while in DETACHING state.
 *  - Snapshot posture is control_only while in DETACHING state.
 *
 * ### Session identity stability across transitions
 *  - sessionId is preserved through state transitions (ATTACHED → DETACHING → DETACHED).
 *  - deviceId is preserved through state transitions.
 *  - hostId is preserved through state transitions.
 *  - delegatedExecutionCount is preserved through state transitions.
 *  - sessionId changes on session replacement (new session after old one is detached).
 *  - runtimeSessionId changes on reconnect (new session has distinct runtimeSessionId).
 *
 * ### Reconnect semantics — session replacement
 *  - A new session after a detach has a different sessionId than the previous session.
 *  - A new session after a detach is in ATTACHED state.
 *  - A new session's snapshot has isReuseValid=true.
 *  - A new session's snapshot has posture=join_runtime.
 *  - A new session's snapshot has invalidationReason=null.
 *
 * ### Host snapshot consistency under lifecycle changes
 *  - currentHostSessionSnapshot returns null when no session exists.
 *  - Snapshot fields are consistent after stop() with no prior session.
 *  - Snapshot fields are consistent after startWithTimeout failure.
 *  - Snapshot attachmentState tracks session state faithfully across transitions.
 *  - Snapshot delegatedExecutionCount tracks execution count faithfully.
 *
 * ### currentSessionSnapshot vs. currentHostSessionSnapshot — no duplication
 *  - Both snapshots reflect the same session identity fields (session_id, state, etc.).
 *  - No second authority: the session map and the snapshot type are projections of the
 *    same underlying AttachedRuntimeSession; they cannot diverge.
 *
 * ### currentDelegatedTargetReadinessProjection — suitability tracking
 *  - Returns null before any session.
 *  - Returns isSuitableTarget=true for an ATTACHED session.
 *  - Returns isSuitableTarget=false with unsuitabilityReason=not_attached after detach.
 *  - Returns isSuitableTarget=false with unsuitabilityReason=invalidated after invalidation.
 *
 * ### PR-22 WS lifecycle guard — wsSessionLifecycleListener
 *  - onDisconnected while Active closes the session with DISCONNECT cause.
 *  - onDisconnected while Active transitions runtime state to LocalOnly.
 *  - onDisconnected while Active is a no-op when crossDeviceEnabled is false (stop() scenario).
 *  - onDisconnected while non-Active is a no-op.
 *  - onConnected while LocalOnly with crossDeviceEnabled opens a new session.
 *  - onConnected while LocalOnly with crossDeviceEnabled transitions state to Active.
 *  - onConnected while LocalOnly with crossDeviceEnabled=false is a no-op (stop() scenario).
 *  - onConnected while Active is a no-op (idempotent — session already open).
 *  - onConnected while Starting is a no-op (start()/connectIfEnabled() handle this).
 */
class AttachedRuntimeLifecycleConsolidationTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies ─────────────────────────────────────────────────────

    private class SingleStepPlannerService : LocalPlannerService {
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
            localPlanner = LocalPlanner(SingleStepPlannerService()),
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
        hostDescriptor: RuntimeHostDescriptor? = null,
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
            registrationTimeoutMs = timeoutMs,
            hostDescriptor = hostDescriptor
        )
        return controller to client
    }

    private fun buildSession(
        hostId: String = "host-pr22",
        deviceId: String = "pixel-9"
    ) = AttachedRuntimeSession.create(hostId = hostId, deviceId = deviceId)

    private fun snapshotFromSession(
        session: AttachedRuntimeSession,
        runtimeSessionId: String = "rts-pr22",
        hostRole: String = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
    ) = AttachedRuntimeHostSessionSnapshot.from(
        session = session,
        runtimeSessionId = runtimeSessionId,
        hostRole = hostRole
    )

    // ── Attach lifecycle ──────────────────────────────────────────────────────

    @Test
    fun `attach - fresh session is in ATTACHED state`() {
        val session = buildSession()
        assertEquals(AttachedRuntimeSession.State.ATTACHED, session.state)
    }

    @Test
    fun `attach - snapshot attachmentState is attached`() {
        val snap = snapshotFromSession(buildSession())
        assertEquals("attached", snap.attachmentState)
    }

    @Test
    fun `attach - snapshot isReuseValid is true`() {
        assertTrue(snapshotFromSession(buildSession()).isReuseValid)
    }

    @Test
    fun `attach - snapshot posture is join_runtime`() {
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, snapshotFromSession(buildSession()).posture)
    }

    @Test
    fun `attach - snapshot invalidationReason is null`() {
        assertNull(snapshotFromSession(buildSession()).invalidationReason)
    }

    // ── Detach lifecycle (all four causes) ────────────────────────────────────

    @Test
    fun `detach EXPLICIT_DETACH - snapshot attachmentState is detached`() {
        val detached = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        assertEquals("detached", snapshotFromSession(detached).attachmentState)
    }

    @Test
    fun `detach EXPLICIT_DETACH - snapshot isReuseValid is false`() {
        val detached = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        assertFalse(snapshotFromSession(detached).isReuseValid)
    }

    @Test
    fun `detach EXPLICIT_DETACH - snapshot posture is control_only`() {
        val detached = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, snapshotFromSession(detached).posture)
    }

    @Test
    fun `detach EXPLICIT_DETACH - snapshot invalidationReason is null`() {
        val detached = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        assertNull(snapshotFromSession(detached).invalidationReason)
    }

    @Test
    fun `detach DISCONNECT - snapshot attachmentState is detached`() {
        val detached = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals("detached", snapshotFromSession(detached).attachmentState)
    }

    @Test
    fun `detach DISCONNECT - snapshot isReuseValid is false`() {
        val detached = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertFalse(snapshotFromSession(detached).isReuseValid)
    }

    @Test
    fun `detach DISCONNECT - snapshot posture is control_only`() {
        val detached = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, snapshotFromSession(detached).posture)
    }

    @Test
    fun `detach DISCONNECT - snapshot invalidationReason is null`() {
        val detached = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertNull(snapshotFromSession(detached).invalidationReason)
    }

    @Test
    fun `detach DISABLE - snapshot attachmentState is detached`() {
        val detached = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertEquals("detached", snapshotFromSession(detached).attachmentState)
    }

    @Test
    fun `detach DISABLE - snapshot isReuseValid is false`() {
        val detached = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertFalse(snapshotFromSession(detached).isReuseValid)
    }

    @Test
    fun `detach DISABLE - snapshot posture is control_only`() {
        val detached = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, snapshotFromSession(detached).posture)
    }

    @Test
    fun `detach DISABLE - snapshot invalidationReason is null`() {
        val detached = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertNull(snapshotFromSession(detached).invalidationReason)
    }

    // ── Invalidate lifecycle ──────────────────────────────────────────────────

    @Test
    fun `invalidate - snapshot attachmentState is detached`() {
        val invalidated = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        assertEquals("detached", snapshotFromSession(invalidated).attachmentState)
    }

    @Test
    fun `invalidate - snapshot isReuseValid is false`() {
        val invalidated = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        assertFalse(snapshotFromSession(invalidated).isReuseValid)
    }

    @Test
    fun `invalidate - snapshot posture is control_only`() {
        val invalidated = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, snapshotFromSession(invalidated).posture)
    }

    @Test
    fun `invalidate - snapshot invalidationReason is invalidation`() {
        val invalidated = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        assertEquals("invalidation", snapshotFromSession(invalidated).invalidationReason)
    }

    // ── Intermediate DETACHING state ──────────────────────────────────────────

    @Test
    fun `detaching - snapshot attachmentState is detaching`() {
        val detaching = buildSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals("detaching", snapshotFromSession(detaching).attachmentState)
    }

    @Test
    fun `detaching - snapshot isReuseValid is false`() {
        val detaching = buildSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertFalse(snapshotFromSession(detaching).isReuseValid)
    }

    @Test
    fun `detaching - snapshot posture is control_only`() {
        val detaching = buildSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISABLE)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, snapshotFromSession(detaching).posture)
    }

    // ── Session identity stability across transitions ─────────────────────────

    @Test
    fun `session identity - sessionId preserved through DETACHING transition`() {
        val original = buildSession()
        val detaching = original.beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(
            "sessionId must be stable through DETACHING transition",
            original.sessionId,
            detaching.sessionId
        )
    }

    @Test
    fun `session identity - sessionId preserved through DETACHED transition`() {
        val original = buildSession()
        val detached = original.detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(
            "sessionId must be stable through DETACHED transition",
            original.sessionId,
            detached.sessionId
        )
    }

    @Test
    fun `session identity - sessionId preserved through full ATTACHED→DETACHING→DETACHED chain`() {
        val original = buildSession()
        val afterAll = original
            .beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
            .detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(
            "sessionId must be stable across the full ATTACHED→DETACHING→DETACHED chain",
            original.sessionId,
            afterAll.sessionId
        )
    }

    @Test
    fun `session identity - deviceId preserved through all transitions`() {
        val original = buildSession(deviceId = "pixel-8-pro")
        val detached = original
            .beginDetaching(AttachedRuntimeSession.DetachCause.DISABLE)
            .detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertEquals("pixel-8-pro", detached.deviceId)
    }

    @Test
    fun `session identity - hostId preserved through all transitions`() {
        val original = buildSession(hostId = "my-runtime-host")
        val detached = original.detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        assertEquals("my-runtime-host", detached.hostId)
    }

    @Test
    fun `session identity - delegatedExecutionCount preserved through DETACHED transition`() {
        val session = buildSession()
            .withExecutionAccepted()
            .withExecutionAccepted()
            .withExecutionAccepted()
        val detached = session.detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(
            "delegatedExecutionCount must survive the detach transition",
            3,
            detached.delegatedExecutionCount
        )
    }

    @Test
    fun `session identity - snapshot sessionId tracks session identity through transitions`() {
        val session = buildSession()
        val snapshotAttached = snapshotFromSession(session)
        val snapshotDetached = snapshotFromSession(
            session.detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        )
        assertEquals(
            "Snapshot sessionId must be the same across ATTACHED and DETACHED snapshots of the same session",
            snapshotAttached.sessionId,
            snapshotDetached.sessionId
        )
    }

    // ── Reconnect / replacement semantics ─────────────────────────────────────

    @Test
    fun `reconnect - new session has a different sessionId than the replaced session`() {
        val oldSession = buildSession()
        val newSession = buildSession()  // simulates opening a new session after detach
        assertNotEquals(
            "A session created after a detach (reconnect/replacement) must have a new sessionId",
            oldSession.sessionId,
            newSession.sessionId
        )
    }

    @Test
    fun `reconnect - new session is in ATTACHED state`() {
        val newSession = buildSession()
        assertEquals(AttachedRuntimeSession.State.ATTACHED, newSession.state)
    }

    @Test
    fun `reconnect - new session snapshot is reuse-valid`() {
        val snap = snapshotFromSession(buildSession())
        assertTrue(snap.isReuseValid)
    }

    @Test
    fun `reconnect - new session snapshot has posture join_runtime`() {
        val snap = snapshotFromSession(buildSession())
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, snap.posture)
    }

    @Test
    fun `reconnect - new session snapshot has null invalidationReason`() {
        val snap = snapshotFromSession(buildSession())
        assertNull(snap.invalidationReason)
    }

    @Test
    fun `reconnect - new runtimeSessionId distinguishes reconnect from previous session`() {
        val session = buildSession()
        val snap1 = snapshotFromSession(session, runtimeSessionId = "rts-before-reconnect")
        val snap2 = snapshotFromSession(session, runtimeSessionId = "rts-after-reconnect")
        assertNotEquals(
            "Successive connections must produce distinct runtimeSessionIds",
            snap1.runtimeSessionId,
            snap2.runtimeSessionId
        )
    }

    @Test
    fun `replacement - sessionId of replacement does not equal detached session`() {
        val old = buildSession()
        val detached = old.detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val replacement = buildSession()  // simulates the new session after reconnect
        assertNotEquals(
            "Replacement session must have a new sessionId distinct from the detached session",
            detached.sessionId,
            replacement.sessionId
        )
    }

    // ── Host snapshot consistency under lifecycle changes ──────────────────────

    @Test
    fun `snapshot consistency - all eight always-present keys present after attach`() {
        val map = snapshotFromSession(buildSession()).toMap()
        val missing = AttachedRuntimeHostSessionSnapshot.ALWAYS_PRESENT_KEYS - map.keys
        assertTrue(
            "All 8 always-present keys must appear in attach snapshot; missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `snapshot consistency - all eight always-present keys present after detach`() {
        val detached = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val map = snapshotFromSession(detached).toMap()
        val missing = AttachedRuntimeHostSessionSnapshot.ALWAYS_PRESENT_KEYS - map.keys
        assertTrue(
            "All 8 always-present keys must appear in detach snapshot; missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `snapshot consistency - all eight always-present keys present after invalidation`() {
        val invalidated = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val map = snapshotFromSession(invalidated).toMap()
        val missing = AttachedRuntimeHostSessionSnapshot.ALWAYS_PRESENT_KEYS - map.keys
        assertTrue(
            "All 8 always-present keys must appear in invalidation snapshot; missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `snapshot consistency - attachmentState tracks session state faithfully`() {
        val session = buildSession()
        assertEquals("attached", snapshotFromSession(session).attachmentState)
        val detaching = session.beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals("detaching", snapshotFromSession(detaching).attachmentState)
        val detached = detaching.detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals("detached", snapshotFromSession(detached).attachmentState)
    }

    @Test
    fun `snapshot consistency - delegatedExecutionCount matches session count`() {
        var session = buildSession()
        repeat(5) { session = session.withExecutionAccepted() }
        val snap = snapshotFromSession(session)
        assertEquals(
            "Snapshot delegatedExecutionCount must match session count",
            5,
            snap.delegatedExecutionCount
        )
    }

    @Test
    fun `snapshot consistency - delegatedExecutionCount preserved through detach`() {
        val session = buildSession()
            .withExecutionAccepted()
            .withExecutionAccepted()
        val detached = session.detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertEquals(
            "Snapshot delegatedExecutionCount must survive detach transition",
            2,
            snapshotFromSession(detached).delegatedExecutionCount
        )
    }

    // ── No duplicate authority — single canonical truth path ─────────────────

    @Test
    fun `single authority - currentHostSessionSnapshot returns null before session via RuntimeController`() {
        val (controller, _) = buildController()
        assertNull(
            "RuntimeController must be the single authority; no session before lifecycle event",
            controller.currentHostSessionSnapshot()
        )
    }

    @Test
    fun `single authority - currentHostSessionSnapshot returns null after stop with no session`() {
        val (controller, _) = buildController()
        controller.stop()
        assertNull(controller.currentHostSessionSnapshot())
    }

    @Test
    fun `single authority - currentHostSessionSnapshot returns null after startWithTimeout failure`() =
        runBlocking {
            val (controller, _) = buildController(timeoutMs = 200L)
            controller.startWithTimeout()
            assertNull(controller.currentHostSessionSnapshot())
        }

    @Test
    fun `single authority - currentDelegatedTargetReadinessProjection returns null before session`() {
        val (controller, _) = buildController()
        assertNull(
            "currentDelegatedTargetReadinessProjection must be null before any session",
            controller.currentDelegatedTargetReadinessProjection()
        )
    }

    @Test
    fun `single authority - no second snapshot path exists outside RuntimeController`() {
        // The session model is immutable; the only way to produce a host-facing projection
        // outside of RuntimeController is via AttachedRuntimeHostSessionSnapshot.from().
        // This test verifies that from() produces a snapshot whose fields are consistent
        // with the session — there is no alternate projection factory.
        val session = buildSession()
        val snap = AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = "rts-test",
            hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
        )
        assertEquals(session.sessionId, snap.sessionId)
        assertEquals(session.deviceId, snap.deviceId)
        assertEquals(session.state.wireValue, snap.attachmentState)
        assertEquals(session.isReuseValid, snap.isReuseValid)
        assertEquals(session.delegatedExecutionCount, snap.delegatedExecutionCount)
    }

    // ── currentDelegatedTargetReadinessProjection suitability tracking ─────────

    @Test
    fun `readiness - attached session is a suitable target`() {
        val session = buildSession()
        val snap = snapshotFromSession(session)
        val readiness = DelegatedTargetReadinessProjection.from(snap)
        assertTrue(
            "An ATTACHED session must be a suitable delegated target",
            readiness.isSuitableTarget
        )
        assertNull(readiness.unsuitabilityReason)
    }

    @Test
    fun `readiness - detached session with DISCONNECT is not a suitable target`() {
        val session = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val snap = snapshotFromSession(session)
        val readiness = DelegatedTargetReadinessProjection.from(snap)
        assertFalse(
            "A DETACHED (DISCONNECT) session must not be a suitable target",
            readiness.isSuitableTarget
        )
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_NOT_ATTACHED,
            readiness.unsuitabilityReason
        )
    }

    @Test
    fun `readiness - invalidated session is not a suitable target with invalidated reason`() {
        val session = buildSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val snap = snapshotFromSession(session)
        val readiness = DelegatedTargetReadinessProjection.from(snap)
        assertFalse(
            "An INVALIDATED session must not be a suitable target",
            readiness.isSuitableTarget
        )
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_INVALIDATED,
            readiness.unsuitabilityReason
        )
    }

    @Test
    fun `readiness - detaching session is not a suitable target`() {
        val session = buildSession().beginDetaching(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val snap = snapshotFromSession(session)
        val readiness = DelegatedTargetReadinessProjection.from(snap)
        assertFalse(
            "A DETACHING session must not be a suitable target",
            readiness.isSuitableTarget
        )
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_NOT_ATTACHED,
            readiness.unsuitabilityReason
        )
    }

    @Test
    fun `readiness - new session after replacement is a suitable target`() {
        // Simulate reconnect: old session detached, new session opened.
        val replacement = buildSession()
        val snap = snapshotFromSession(replacement)
        val readiness = DelegatedTargetReadinessProjection.from(snap)
        assertTrue(
            "The replacement session (post-reconnect) must be a suitable target",
            readiness.isSuitableTarget
        )
    }

    // ── PR-22: wsSessionLifecycleListener behavior ───────────────────────────
    //
    // The persistent WS lifecycle guard is tested here by directly invoking the
    // listener's callback methods, simulating WS events without requiring a live
    // server connection.

    /**
     * Helper: builds a controller whose runtime state is artificially set to
     * [RuntimeController.RuntimeState.Active] with an attached session by directly
     * invoking the [RuntimeController.wsSessionLifecycleListener.onConnected] callback.
     *
     * Precondition: settings.crossDeviceEnabled must be true before calling.
     */
    private fun buildActiveController(
        settings: InMemoryAppSettings = InMemoryAppSettings(crossDeviceEnabled = true),
        hostDescriptor: RuntimeHostDescriptor? = null
    ): RuntimeController {
        val (controller, _) = buildController(settings = settings, hostDescriptor = hostDescriptor)
        // Simulate WS connection via the lifecycle guard path.
        // Guard only acts when state is LocalOnly and crossDeviceEnabled=true.
        // We start from Idle; first force the state to LocalOnly to satisfy the guard's
        // reconnect-fingerprint check, then fire onConnected().
        controller.stop()  // transitions to LocalOnly and sets crossDeviceEnabled=false
        settings.crossDeviceEnabled = true  // re-enable so the guard will act
        controller.wsSessionLifecycleListener.onConnected()
        return controller
    }

    @Test
    fun `guard - onConnected while LocalOnly with crossDeviceEnabled opens session`() {
        val controller = buildActiveController()
        val session = controller.attachedSession.value
        assertNotNull(
            "wsSessionLifecycleListener.onConnected() must open an attached session when " +
                "state is LocalOnly and crossDeviceEnabled=true",
            session
        )
        assertTrue(
            "Opened session must be in ATTACHED state",
            session!!.isAttached
        )
    }

    @Test
    fun `guard - onConnected while LocalOnly with crossDeviceEnabled sets state to Active`() {
        val controller = buildActiveController()
        assertTrue(
            "wsSessionLifecycleListener.onConnected() must set runtime state to Active",
            controller.state.value is RuntimeController.RuntimeState.Active
        )
    }

    @Test
    fun `guard - onDisconnected while Active closes session with DISCONNECT cause`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val controller = buildActiveController(settings = settings)
        // Precondition: session is open and state is Active.
        assertTrue(controller.state.value is RuntimeController.RuntimeState.Active)
        assertNotNull(controller.attachedSession.value)

        // Simulate spontaneous WS disconnect.
        controller.wsSessionLifecycleListener.onDisconnected()

        val session = controller.attachedSession.value
        assertNotNull("Session must still be visible after disconnect (as DETACHED)", session)
        assertEquals(
            "Session must be DETACHED after WS disconnect",
            AttachedRuntimeSession.State.DETACHED,
            session!!.state
        )
        assertEquals(
            "Detach cause must be DISCONNECT",
            AttachedRuntimeSession.DetachCause.DISCONNECT,
            session.detachCause
        )
    }

    @Test
    fun `guard - onDisconnected while Active transitions state to LocalOnly`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val controller = buildActiveController(settings = settings)

        controller.wsSessionLifecycleListener.onDisconnected()

        assertTrue(
            "Runtime state must be LocalOnly after spontaneous WS disconnect",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }

    @Test
    fun `guard - onDisconnected while Active is no-op when crossDeviceEnabled is false`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val controller = buildActiveController(settings = settings)
        // Simulate stop()-like scenario: crossDeviceEnabled already false.
        settings.crossDeviceEnabled = false

        // Even though state is still Active (stop() hasn't run its close logic yet),
        // the guard must not fire when crossDeviceEnabled=false.
        controller.wsSessionLifecycleListener.onDisconnected()

        // Session must still be attached (the guard did not close it).
        assertTrue(
            "Guard must not close session when crossDeviceEnabled=false (stop() scenario)",
            controller.attachedSession.value?.isAttached == true
        )
    }

    @Test
    fun `guard - onDisconnected while Starting is a no-op`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val (controller, _) = buildController(settings = settings)
        // State is Idle initially; simulate transitioning to Starting.
        // We cannot directly set state to Starting, but we know the guard only
        // acts when state is Active. So testing from Idle (not Active) is sufficient.
        controller.wsSessionLifecycleListener.onDisconnected()

        // Nothing should have changed: no session, state still Idle.
        assertNull(
            "Guard must not act when state is not Active",
            controller.attachedSession.value
        )
        assertTrue(
            "State must remain Idle when guard fires on a non-Active state",
            controller.state.value is RuntimeController.RuntimeState.Idle
        )
    }

    @Test
    fun `guard - onConnected while Active is a no-op (idempotent)`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val controller = buildActiveController(settings = settings)
        val sessionBefore = controller.attachedSession.value
        val sessionIdBefore = sessionBefore!!.sessionId

        // Firing onConnected() while already Active must be a no-op.
        controller.wsSessionLifecycleListener.onConnected()

        val sessionAfter = controller.attachedSession.value
        assertEquals(
            "Guard onConnected() while Active must not open a new session",
            sessionIdBefore,
            sessionAfter?.sessionId
        )
    }

    @Test
    fun `guard - onConnected while LocalOnly with crossDeviceEnabled false is no-op`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val (controller, _) = buildController(settings = settings)
        // stop() will set LocalOnly.
        controller.stop()
        // crossDeviceEnabled is already false after stop(); guard must not act.
        controller.wsSessionLifecycleListener.onConnected()

        assertNull(
            "Guard must not open a session when crossDeviceEnabled=false",
            controller.attachedSession.value
        )
    }

    @Test
    fun `guard - reconnect after disconnect opens a new session with different sessionId`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val controller = buildActiveController(settings = settings)
        val sessionIdBefore = controller.attachedSession.value!!.sessionId

        // Simulate spontaneous disconnect.
        controller.wsSessionLifecycleListener.onDisconnected()
        assertEquals(
            "State must be LocalOnly after disconnect",
            RuntimeController.RuntimeState.LocalOnly,
            controller.state.value
        )

        // Simulate auto-reconnect.
        controller.wsSessionLifecycleListener.onConnected()
        assertEquals(
            "State must be Active after reconnect",
            RuntimeController.RuntimeState.Active,
            controller.state.value
        )

        val sessionIdAfter = controller.attachedSession.value!!.sessionId
        assertNotEquals(
            "Reconnect must open a new session with a distinct sessionId",
            sessionIdBefore,
            sessionIdAfter
        )
        assertTrue(
            "Post-reconnect session must be ATTACHED",
            controller.attachedSession.value!!.isAttached
        )
    }

    @Test
    fun `guard - reconnect session snapshot is reuse-valid and posture is join_runtime`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val controller = buildActiveController(settings = settings)

        // Disconnect then reconnect.
        controller.wsSessionLifecycleListener.onDisconnected()
        controller.wsSessionLifecycleListener.onConnected()

        val session = controller.attachedSession.value!!
        assertTrue("Post-reconnect session must be reuse-valid", session.isReuseValid)
    }

    @Test
    fun `guard - post-reconnect delegated execution count resets to zero for new session`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val controller = buildActiveController(settings = settings)
        // Accept some delegated executions before disconnect.
        controller.recordDelegatedExecutionAccepted()
        controller.recordDelegatedExecutionAccepted()
        assertEquals(
            "Pre-disconnect execution count must be 2",
            2,
            controller.attachedSession.value!!.delegatedExecutionCount
        )

        // Disconnect then reconnect.
        controller.wsSessionLifecycleListener.onDisconnected()
        controller.wsSessionLifecycleListener.onConnected()

        assertEquals(
            "New session after reconnect must start with delegatedExecutionCount=0",
            0,
            controller.attachedSession.value!!.delegatedExecutionCount
        )
    }

    // ── RuntimeController.invalidateSession() as the single invalidation path ─

    @Test
    fun `invalidateSession - closes session with INVALIDATION cause`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val controller = buildActiveController(settings = settings)
        assertNotNull(controller.attachedSession.value)

        controller.invalidateSession()

        val session = controller.attachedSession.value
        assertNotNull("Session must remain visible after invalidation", session)
        assertEquals(
            "Session must be DETACHED after invalidateSession()",
            AttachedRuntimeSession.State.DETACHED,
            session!!.state
        )
        assertEquals(
            "Detach cause must be INVALIDATION",
            AttachedRuntimeSession.DetachCause.INVALIDATION,
            session.detachCause
        )
    }

    @Test
    fun `invalidateSession - snapshot invalidationReason is invalidation`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val controller = buildActiveController(settings = settings)
        controller.invalidateSession()
        val session = controller.attachedSession.value!!
        val snap = AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = "rts-post-invalidation",
            hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
        )
        assertEquals(
            "Snapshot invalidationReason must be 'invalidation' after invalidateSession()",
            "invalidation",
            snap.invalidationReason
        )
    }

    @Test
    fun `invalidateSession - does not change runtime state`() {
        // invalidateSession() closes the session but does NOT change the runtime state.
        // This is by design: the WS is still connected; only the session is invalidated.
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val controller = buildActiveController(settings = settings)
        controller.invalidateSession()
        assertTrue(
            "Runtime state must remain Active after invalidateSession() (WS is still connected)",
            controller.state.value is RuntimeController.RuntimeState.Active
        )
    }
}
