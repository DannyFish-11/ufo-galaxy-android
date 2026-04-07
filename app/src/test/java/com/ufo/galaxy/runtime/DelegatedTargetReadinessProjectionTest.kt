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
 * Unit tests for [DelegatedTargetReadinessProjection] (PR-20 / PR-24, post-#533 dual-repo
 * runtime unification master plan — Host-Facing Delegated Target Readiness Projection,
 * Android side).
 *
 * [DelegatedTargetReadinessProjection] is the authoritative host-facing projection that
 * the main-repository delegated target selection policy consumes to decide whether this
 * Android attached runtime is currently suitable to receive a new delegated execution.
 *
 * ## Test matrix
 *
 * ### Wire key constants
 *  - KEY_SESSION_ID forwards the snapshot's KEY_SESSION_ID value.
 *  - KEY_IS_SUITABLE_TARGET is "readiness_is_suitable_target".
 *  - KEY_UNSUITABILITY_REASON is "readiness_unsuitability_reason".
 *  - KEY_SELECTION_OUTCOME is "readiness_selection_outcome".
 *
 * ### Unsuitability reason constants
 *  - UNSUITABILITY_INVALIDATED is "invalidated".
 *  - UNSUITABILITY_NOT_ATTACHED is "not_attached".
 *
 * ### SelectionOutcome wire values
 *  - SelectionOutcome.SELECTED wireValue is "selected".
 *  - SelectionOutcome.REJECTED wireValue is "rejected".
 *  - SelectionOutcome.FALLBACK wireValue is "fallback".
 *
 * ### from — attach / suitable projection
 *  - isSuitableTarget is true for an ATTACHED session.
 *  - unsuitabilityReason is null when suitable.
 *  - selectionOutcome is SELECTED for an ATTACHED session.
 *  - snapshot fields are preserved (sessionId, deviceId, runtimeSessionId).
 *  - posture is join_runtime for ATTACHED session.
 *  - toMap contains KEY_IS_SUITABLE_TARGET = true.
 *  - toMap contains KEY_SELECTION_OUTCOME = "selected" when ATTACHED.
 *  - toMap does NOT contain KEY_UNSUITABILITY_REASON when suitable.
 *
 * ### from — detach / not-suitable projection
 *  - isSuitableTarget is false after EXPLICIT_DETACH.
 *  - unsuitabilityReason is UNSUITABILITY_NOT_ATTACHED after EXPLICIT_DETACH.
 *  - selectionOutcome is FALLBACK after EXPLICIT_DETACH.
 *  - isSuitableTarget is false after DISCONNECT.
 *  - unsuitabilityReason is UNSUITABILITY_NOT_ATTACHED after DISCONNECT.
 *  - selectionOutcome is FALLBACK after DISCONNECT.
 *  - isSuitableTarget is false after DISABLE.
 *  - unsuitabilityReason is UNSUITABILITY_NOT_ATTACHED after DISABLE.
 *  - selectionOutcome is FALLBACK after DISABLE.
 *  - toMap contains KEY_UNSUITABILITY_REASON when not suitable for non-invalidation reasons.
 *  - toMap contains KEY_SELECTION_OUTCOME = "fallback" after detach.
 *
 * ### from — invalidate projection
 *  - isSuitableTarget is false after INVALIDATION.
 *  - unsuitabilityReason is UNSUITABILITY_INVALIDATED after INVALIDATION.
 *  - selectionOutcome is REJECTED after INVALIDATION.
 *  - toMap contains KEY_UNSUITABILITY_REASON = UNSUITABILITY_INVALIDATED.
 *  - toMap contains KEY_SELECTION_OUTCOME = "rejected" after INVALIDATION.
 *
 * ### from — detaching projection
 *  - isSuitableTarget is false while session is in DETACHING state.
 *  - unsuitabilityReason is UNSUITABILITY_NOT_ATTACHED in DETACHING state.
 *  - selectionOutcome is FALLBACK in DETACHING state.
 *
 * ### from — delegated execution count
 *  - delegatedExecutionCount reflects updated count after withExecutionAccepted.
 *  - isSuitableTarget remains true after execution count increments on ATTACHED session.
 *  - selectionOutcome remains SELECTED after execution count increments.
 *
 * ### Readiness transition correctness
 *  - attach → suitable, detach → not suitable.
 *  - attach → suitable, invalidate → not suitable (with UNSUITABILITY_INVALIDATED).
 *  - attach → suitable, reconnect (new runtimeSessionId) → suitable again.
 *  - attach → SELECTED, detach → FALLBACK, selectionOutcome transitions are stable.
 *  - attach → SELECTED, invalidate → REJECTED, selectionOutcome transitions are stable.
 *  - attach → SELECTED, reconnect → SELECTED again, selectionOutcome is stable.
 *
 * ### Host-facing consistency — toMap
 *  - ALWAYS_PRESENT_KEYS all present on ATTACHED projection.
 *  - ALWAYS_PRESENT_KEYS all present on DETACHED projection.
 *  - toMap includes all nine snapshot fields for ATTACHED projection.
 *  - toMap includes all nine snapshot fields for DETACHED projection.
 *
 * ### ALWAYS_PRESENT_KEYS constant
 *  - ALWAYS_PRESENT_KEYS contains exactly 10 entries.
 *  - KEY_SELECTION_OUTCOME is in ALWAYS_PRESENT_KEYS.
 *  - KEY_UNSUITABILITY_REASON is NOT in ALWAYS_PRESENT_KEYS.
 *  - KEY_INVALIDATION_REASON is NOT in ALWAYS_PRESENT_KEYS.
 *
 * ### RuntimeController.currentDelegatedTargetReadinessProjection
 *  - Returns null before any session is opened.
 *  - Returns null after stop() with no prior session.
 *  - Returns null after startWithTimeout failure.
 */
class DelegatedTargetReadinessProjectionTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun freshSession(
        hostId: String = "host-pr20",
        deviceId: String = "pixel-9"
    ) = AttachedRuntimeSession.create(hostId = hostId, deviceId = deviceId)

    private fun snapshotFrom(
        session: AttachedRuntimeSession,
        runtimeSessionId: String = "rts-pr20",
        hostRole: String = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
    ) = AttachedRuntimeHostSessionSnapshot.from(
        session = session,
        runtimeSessionId = runtimeSessionId,
        hostRole = hostRole
    )

    private fun readinessFrom(
        session: AttachedRuntimeSession,
        runtimeSessionId: String = "rts-pr20",
        hostRole: String = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
    ) = DelegatedTargetReadinessProjection.from(snapshotFrom(session, runtimeSessionId, hostRole))

    // ── Wire key constant values ──────────────────────────────────────────────

    @Test
    fun `KEY_SESSION_ID forwards snapshot KEY_SESSION_ID value`() {
        assertEquals(
            AttachedRuntimeHostSessionSnapshot.KEY_SESSION_ID,
            DelegatedTargetReadinessProjection.KEY_SESSION_ID
        )
    }

    @Test
    fun `KEY_IS_SUITABLE_TARGET is readiness_is_suitable_target`() {
        assertEquals(
            "readiness_is_suitable_target",
            DelegatedTargetReadinessProjection.KEY_IS_SUITABLE_TARGET
        )
    }

    @Test
    fun `KEY_UNSUITABILITY_REASON is readiness_unsuitability_reason`() {
        assertEquals(
            "readiness_unsuitability_reason",
            DelegatedTargetReadinessProjection.KEY_UNSUITABILITY_REASON
        )
    }

    @Test
    fun `KEY_SELECTION_OUTCOME is readiness_selection_outcome`() {
        assertEquals(
            "readiness_selection_outcome",
            DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME
        )
    }

    // ── SelectionOutcome wire values ──────────────────────────────────────────

    @Test
    fun `SelectionOutcome SELECTED wireValue is selected`() {
        assertEquals(
            "selected",
            DelegatedTargetReadinessProjection.SelectionOutcome.SELECTED.wireValue
        )
    }

    @Test
    fun `SelectionOutcome REJECTED wireValue is rejected`() {
        assertEquals(
            "rejected",
            DelegatedTargetReadinessProjection.SelectionOutcome.REJECTED.wireValue
        )
    }

    @Test
    fun `SelectionOutcome FALLBACK wireValue is fallback`() {
        assertEquals(
            "fallback",
            DelegatedTargetReadinessProjection.SelectionOutcome.FALLBACK.wireValue
        )
    }

    // ── Unsuitability reason constants ────────────────────────────────────────

    @Test
    fun `UNSUITABILITY_INVALIDATED is invalidated`() {
        assertEquals("invalidated", DelegatedTargetReadinessProjection.UNSUITABILITY_INVALIDATED)
    }

    @Test
    fun `UNSUITABILITY_NOT_ATTACHED is not_attached`() {
        assertEquals("not_attached", DelegatedTargetReadinessProjection.UNSUITABILITY_NOT_ATTACHED)
    }

    // ── from — attach / suitable projection ──────────────────────────────────

    @Test
    fun `from ATTACHED isSuitableTarget is true`() {
        assertTrue(
            "isSuitableTarget must be true for an ATTACHED session",
            readinessFrom(freshSession()).isSuitableTarget
        )
    }

    @Test
    fun `from ATTACHED unsuitabilityReason is null`() {
        assertNull(
            "unsuitabilityReason must be null when session is suitable",
            readinessFrom(freshSession()).unsuitabilityReason
        )
    }

    @Test
    fun `from ATTACHED selectionOutcome is SELECTED`() {
        assertEquals(
            DelegatedTargetReadinessProjection.SelectionOutcome.SELECTED,
            readinessFrom(freshSession()).selectionOutcome
        )
    }

    @Test
    fun `from ATTACHED sessionId matches session sessionId`() {
        val session = freshSession()
        assertEquals(session.sessionId, readinessFrom(session).sessionId)
    }

    @Test
    fun `from ATTACHED deviceId matches session deviceId`() {
        val session = freshSession(deviceId = "samsung-s25")
        assertEquals("samsung-s25", readinessFrom(session).deviceId)
    }

    @Test
    fun `from ATTACHED runtimeSessionId matches supplied value`() {
        val r = readinessFrom(freshSession(), runtimeSessionId = "rts-abc-pr20")
        assertEquals("rts-abc-pr20", r.runtimeSessionId)
    }

    @Test
    fun `from ATTACHED posture is join_runtime`() {
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, readinessFrom(freshSession()).posture)
    }

    @Test
    fun `toMap contains KEY_IS_SUITABLE_TARGET true when ATTACHED`() {
        val map = readinessFrom(freshSession()).toMap()
        assertEquals(true, map[DelegatedTargetReadinessProjection.KEY_IS_SUITABLE_TARGET])
    }

    @Test
    fun `toMap contains KEY_SELECTION_OUTCOME selected when ATTACHED`() {
        val map = readinessFrom(freshSession()).toMap()
        assertEquals(
            DelegatedTargetReadinessProjection.SelectionOutcome.SELECTED.wireValue,
            map[DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME]
        )
    }

    @Test
    fun `toMap does NOT contain KEY_UNSUITABILITY_REASON when suitable`() {
        val map = readinessFrom(freshSession()).toMap()
        assertFalse(
            "KEY_UNSUITABILITY_REASON must be absent when session is suitable",
            map.containsKey(DelegatedTargetReadinessProjection.KEY_UNSUITABILITY_REASON)
        )
    }

    // ── from — detach / not-suitable projection ───────────────────────────────

    @Test
    fun `from EXPLICIT_DETACH isSuitableTarget is false`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        assertFalse(readinessFrom(detached).isSuitableTarget)
    }

    @Test
    fun `from EXPLICIT_DETACH unsuitabilityReason is not_attached`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_NOT_ATTACHED,
            readinessFrom(detached).unsuitabilityReason
        )
    }

    @Test
    fun `from EXPLICIT_DETACH selectionOutcome is FALLBACK`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        assertEquals(
            DelegatedTargetReadinessProjection.SelectionOutcome.FALLBACK,
            readinessFrom(detached).selectionOutcome
        )
    }

    @Test
    fun `from DISCONNECT isSuitableTarget is false`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertFalse(readinessFrom(detached).isSuitableTarget)
    }

    @Test
    fun `from DISCONNECT unsuitabilityReason is not_attached`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_NOT_ATTACHED,
            readinessFrom(detached).unsuitabilityReason
        )
    }

    @Test
    fun `from DISCONNECT selectionOutcome is FALLBACK`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(
            DelegatedTargetReadinessProjection.SelectionOutcome.FALLBACK,
            readinessFrom(detached).selectionOutcome
        )
    }

    @Test
    fun `from DISABLE isSuitableTarget is false`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertFalse(readinessFrom(detached).isSuitableTarget)
    }

    @Test
    fun `from DISABLE unsuitabilityReason is not_attached`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_NOT_ATTACHED,
            readinessFrom(detached).unsuitabilityReason
        )
    }

    @Test
    fun `from DISABLE selectionOutcome is FALLBACK`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertEquals(
            DelegatedTargetReadinessProjection.SelectionOutcome.FALLBACK,
            readinessFrom(detached).selectionOutcome
        )
    }

    @Test
    fun `toMap contains KEY_UNSUITABILITY_REASON when not suitable for non-invalidation reasons`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val map = readinessFrom(detached).toMap()
        assertTrue(
            "KEY_UNSUITABILITY_REASON must be present when session is not suitable",
            map.containsKey(DelegatedTargetReadinessProjection.KEY_UNSUITABILITY_REASON)
        )
    }

    @Test
    fun `toMap contains KEY_SELECTION_OUTCOME fallback after detach`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val map = readinessFrom(detached).toMap()
        assertEquals(
            DelegatedTargetReadinessProjection.SelectionOutcome.FALLBACK.wireValue,
            map[DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME]
        )
    }

    // ── from — invalidate projection ──────────────────────────────────────────

    @Test
    fun `from INVALIDATION isSuitableTarget is false`() {
        val invalidated = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        assertFalse(readinessFrom(invalidated).isSuitableTarget)
    }

    @Test
    fun `from INVALIDATION unsuitabilityReason is invalidated`() {
        val invalidated = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_INVALIDATED,
            readinessFrom(invalidated).unsuitabilityReason
        )
    }

    @Test
    fun `toMap contains KEY_UNSUITABILITY_REASON UNSUITABILITY_INVALIDATED for INVALIDATION`() {
        val invalidated = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val map = readinessFrom(invalidated).toMap()
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_INVALIDATED,
            map[DelegatedTargetReadinessProjection.KEY_UNSUITABILITY_REASON]
        )
    }

    // ── from — detaching projection ───────────────────────────────────────────

    @Test
    fun `from DETACHING isSuitableTarget is false`() {
        val detaching = freshSession().beginDetaching(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        assertFalse(
            "isSuitableTarget must be false in DETACHING state",
            readinessFrom(detaching).isSuitableTarget
        )
    }

    @Test
    fun `from DETACHING unsuitabilityReason is not_attached`() {
        val detaching = freshSession().beginDetaching(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_NOT_ATTACHED,
            readinessFrom(detaching).unsuitabilityReason
        )
    }

    // ── from — delegated execution count ─────────────────────────────────────

    @Test
    fun `delegatedExecutionCount reflects updated count after withExecutionAccepted`() {
        val session = freshSession().withExecutionAccepted().withExecutionAccepted()
        assertEquals(2, readinessFrom(session).delegatedExecutionCount)
    }

    @Test
    fun `isSuitableTarget remains true after execution count increments on ATTACHED session`() {
        val session = freshSession()
            .withExecutionAccepted()
            .withExecutionAccepted()
            .withExecutionAccepted()
        assertTrue(
            "isSuitableTarget must remain true after execution count increments while ATTACHED",
            readinessFrom(session).isSuitableTarget
        )
    }

    // ── Readiness transition correctness ─────────────────────────────────────

    @Test
    fun `attach then detach transitions isSuitableTarget from true to false`() {
        val session = freshSession()
        val afterAttach = readinessFrom(session)
        val afterDetach = readinessFrom(
            session.detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        )
        assertTrue("After attach: isSuitableTarget must be true", afterAttach.isSuitableTarget)
        assertFalse("After detach: isSuitableTarget must be false", afterDetach.isSuitableTarget)
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_NOT_ATTACHED,
            afterDetach.unsuitabilityReason
        )
    }

    @Test
    fun `attach then invalidate transitions isSuitableTarget from true to false with UNSUITABILITY_INVALIDATED`() {
        val session = freshSession()
        val afterAttach = readinessFrom(session)
        val afterInvalidate = readinessFrom(
            session.detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        )
        assertTrue("After attach: isSuitableTarget must be true", afterAttach.isSuitableTarget)
        assertFalse(
            "After invalidate: isSuitableTarget must be false",
            afterInvalidate.isSuitableTarget
        )
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_INVALIDATED,
            afterInvalidate.unsuitabilityReason
        )
    }

    @Test
    fun `reconnect produces suitable projection with new runtimeSessionId`() {
        val session = freshSession()
        val rts1 = "rts-reconnect-first"
        val rts2 = "rts-reconnect-second"
        val firstConnection = readinessFrom(session, runtimeSessionId = rts1)
        val secondConnection = readinessFrom(session, runtimeSessionId = rts2)
        assertTrue("First connection: isSuitableTarget must be true", firstConnection.isSuitableTarget)
        assertTrue("After reconnect: isSuitableTarget must be true", secondConnection.isSuitableTarget)
        assertNotEquals(
            "Reconnect must produce a different runtimeSessionId",
            firstConnection.runtimeSessionId,
            secondConnection.runtimeSessionId
        )
    }

    // ── Host-facing consistency — toMap ───────────────────────────────────────

    @Test
    fun `ALWAYS_PRESENT_KEYS all present on ATTACHED projection toMap`() {
        val map = readinessFrom(freshSession()).toMap()
        val missing = DelegatedTargetReadinessProjection.ALWAYS_PRESENT_KEYS - map.keys
        assertTrue(
            "toMap must contain all always-present keys for ATTACHED projection; missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `ALWAYS_PRESENT_KEYS all present on DETACHED projection toMap`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val map = readinessFrom(detached).toMap()
        val missing = DelegatedTargetReadinessProjection.ALWAYS_PRESENT_KEYS - map.keys
        assertTrue(
            "toMap must contain all always-present keys for DETACHED projection; missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `toMap includes all nine snapshot fields for ATTACHED projection`() {
        val map = readinessFrom(freshSession()).toMap()
        val missing = AttachedRuntimeHostSessionSnapshot.ALWAYS_PRESENT_KEYS - map.keys
        assertTrue(
            "toMap must forward all nine snapshot always-present keys; missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `toMap includes all nine snapshot fields for DETACHED projection`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        val map = readinessFrom(detached).toMap()
        val missing = AttachedRuntimeHostSessionSnapshot.ALWAYS_PRESENT_KEYS - map.keys
        assertTrue(
            "toMap must forward all nine snapshot always-present keys for DETACHED; missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `toMap KEY_IS_SUITABLE_TARGET is false for DETACHED projection`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val map = readinessFrom(detached).toMap()
        assertEquals(false, map[DelegatedTargetReadinessProjection.KEY_IS_SUITABLE_TARGET])
    }

    // ── ALWAYS_PRESENT_KEYS constant ──────────────────────────────────────────

    @Test
    fun `ALWAYS_PRESENT_KEYS contains exactly 10 entries`() {
        assertEquals(10, DelegatedTargetReadinessProjection.ALWAYS_PRESENT_KEYS.size)
    }

    @Test
    fun `KEY_UNSUITABILITY_REASON is NOT in ALWAYS_PRESENT_KEYS`() {
        assertFalse(
            "KEY_UNSUITABILITY_REASON must be excluded from ALWAYS_PRESENT_KEYS as it is conditional",
            DelegatedTargetReadinessProjection.KEY_UNSUITABILITY_REASON in
                DelegatedTargetReadinessProjection.ALWAYS_PRESENT_KEYS
        )
    }

    @Test
    fun `KEY_INVALIDATION_REASON is NOT in ALWAYS_PRESENT_KEYS`() {
        assertFalse(
            "KEY_INVALIDATION_REASON must be excluded from ALWAYS_PRESENT_KEYS as it is conditional",
            DelegatedTargetReadinessProjection.KEY_INVALIDATION_REASON in
                DelegatedTargetReadinessProjection.ALWAYS_PRESENT_KEYS
        )
    }

    // ── RuntimeController.currentDelegatedTargetReadinessProjection ───────────

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
        hostDescriptor: RuntimeHostDescriptor? = null,
        timeoutMs: Long = 100L
    ): RuntimeController = RuntimeController(
        webSocketClient = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        ),
        settings = InMemoryAppSettings(),
        loopController = buildLoopController(),
        registrationTimeoutMs = timeoutMs,
        hostDescriptor = hostDescriptor
    )

    @Test
    fun `currentDelegatedTargetReadinessProjection returns null before any session is opened`() {
        val controller = buildController()
        assertNull(
            "currentDelegatedTargetReadinessProjection must return null before any session is opened",
            controller.currentDelegatedTargetReadinessProjection()
        )
    }

    @Test
    fun `currentDelegatedTargetReadinessProjection returns null after stop with no prior session`() {
        val controller = buildController()
        controller.stop()
        assertNull(
            "currentDelegatedTargetReadinessProjection must remain null after stop() with no session",
            controller.currentDelegatedTargetReadinessProjection()
        )
    }

    @Test
    fun `currentDelegatedTargetReadinessProjection returns null after startWithTimeout failure`() =
        runBlocking {
            val controller = buildController(timeoutMs = 200L)
            controller.startWithTimeout()
            assertNull(
                "currentDelegatedTargetReadinessProjection must be null after a failed startWithTimeout",
                controller.currentDelegatedTargetReadinessProjection()
            )
        }
}
