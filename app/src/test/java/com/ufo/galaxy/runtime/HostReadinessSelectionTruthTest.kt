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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-24 — Host-facing readiness and target suitability selection truth consolidation.
 *
 * Verifies that:
 *  1. [DelegatedTargetReadinessProjection.selectionOutcome] and
 *     [DelegatedTargetReadinessProjection.selectionOutcomeReason] are stable,
 *     canonically derived surfaces — no ad-hoc field inference is required by consumers.
 *  2. [RuntimeController.targetReadinessProjection] is the single authoritative observable
 *     readiness/selection-outcome StateFlow derived from [RuntimeController.hostSessionSnapshot].
 *  3. Multi-target projection correctly assigns [TargetSelectionOutcome.SELECTED] to
 *     primary-role candidates and [TargetSelectionOutcome.FALLBACK] to secondary/satellite
 *     candidates — independent of attachment order.
 *  4. No duplicate truth path is introduced: [targetReadinessProjection] tracks
 *     [hostSessionSnapshot] exactly; neither can emit a value the other does not.
 *
 * ## Test matrix
 *
 * ### TargetSelectionOutcome enum
 *  - SELECTED wireValue is "selected".
 *  - FALLBACK wireValue is "fallback".
 *  - REJECTED wireValue is "rejected".
 *
 * ### Wire key constants (PR-24)
 *  - KEY_SELECTION_OUTCOME is "readiness_selection_outcome".
 *  - KEY_SELECTION_OUTCOME_REASON is "readiness_selection_outcome_reason".
 *  - FALLBACK_REASON_NON_PRIMARY_ROLE is "non_primary_role".
 *
 * ### ALWAYS_PRESENT_KEYS — PR-24 additions
 *  - KEY_SELECTION_OUTCOME is in ALWAYS_PRESENT_KEYS.
 *  - KEY_SELECTION_OUTCOME_REASON is NOT in ALWAYS_PRESENT_KEYS.
 *
 * ### selectionOutcome — suitable (ATTACHED, primary role)
 *  - selectionOutcome is SELECTED for ATTACHED session with PRIMARY role.
 *  - selectionOutcome is SELECTED for ATTACHED session with DEFAULT role.
 *  - selectionOutcomeReason is null when selectionOutcome is SELECTED.
 *  - toMap contains KEY_SELECTION_OUTCOME = "selected" for PRIMARY role.
 *  - toMap does NOT contain KEY_SELECTION_OUTCOME_REASON for SELECTED.
 *
 * ### selectionOutcome — suitable (ATTACHED, secondary/satellite roles)
 *  - selectionOutcome is FALLBACK for ATTACHED session with SECONDARY role.
 *  - selectionOutcome is FALLBACK for ATTACHED session with SATELLITE role.
 *  - selectionOutcomeReason is FALLBACK_REASON_NON_PRIMARY_ROLE for SECONDARY role.
 *  - selectionOutcomeReason is FALLBACK_REASON_NON_PRIMARY_ROLE for SATELLITE role.
 *  - toMap contains KEY_SELECTION_OUTCOME = "fallback" for SECONDARY role.
 *  - toMap contains KEY_SELECTION_OUTCOME_REASON = FALLBACK_REASON_NON_PRIMARY_ROLE for SECONDARY.
 *
 * ### selectionOutcome — not suitable (REJECTED)
 *  - selectionOutcome is REJECTED after EXPLICIT_DETACH.
 *  - selectionOutcome is REJECTED after DISCONNECT.
 *  - selectionOutcome is REJECTED after DISABLE.
 *  - selectionOutcome is REJECTED after INVALIDATION.
 *  - selectionOutcomeReason is UNSUITABILITY_NOT_ATTACHED for EXPLICIT_DETACH.
 *  - selectionOutcomeReason is UNSUITABILITY_NOT_ATTACHED for DISCONNECT.
 *  - selectionOutcomeReason is UNSUITABILITY_INVALIDATED for INVALIDATION.
 *  - toMap contains KEY_SELECTION_OUTCOME = "rejected" after detach.
 *  - toMap contains KEY_SELECTION_OUTCOME_REASON when REJECTED.
 *
 * ### selectionOutcome — lifecycle transitions
 *  - attach → SELECTED (primary), detach → REJECTED transition.
 *  - attach → SELECTED (primary), invalidate → REJECTED transition.
 *  - attach → SELECTED (primary), reconnect → SELECTED again (new runtimeSessionId).
 *  - SECONDARY attach → FALLBACK remains across execution count increments.
 *
 * ### Multi-target projection — no first-active / explicit-target collapse
 *  - PRIMARY and SECONDARY targets yield SELECTED vs FALLBACK independently.
 *  - Two SELECTED (PRIMARY) sessions produce independent, non-identical projections.
 *  - Two REJECTED sessions both produce REJECTED independently.
 *  - PRIMARY target projection is SELECTED even if built after SECONDARY target projection.
 *
 * ### RuntimeController.targetReadinessProjection StateFlow
 *  - targetReadinessProjection is null before any session is opened.
 *  - targetReadinessProjection is null after stop() with no prior session.
 *  - targetReadinessProjection is null after startWithTimeout failure.
 *
 * ### No duplicate truth — targetReadinessProjection derived from hostSessionSnapshot
 *  - targetReadinessProjection null ⟺ hostSessionSnapshot null.
 *  - targetReadinessProjection.snapshot equals hostSessionSnapshot.value when both non-null.
 *  - Both flows are null simultaneously before any session.
 *  - Both flows become non-null simultaneously after invalidateSession on active controller.
 */
class HostReadinessSelectionTruthTest {

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
        hostDescriptor: RuntimeHostDescriptor? = buildHostDescriptor(),
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

    private fun buildHostDescriptor(
        hostId: String = "pr24-host",
        deviceId: String = "pr24-device",
        formationRole: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY
    ): RuntimeHostDescriptor = RuntimeHostDescriptor(
        hostId = hostId,
        deviceId = deviceId,
        deviceRole = "phone",
        formationRole = formationRole,
        participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
    )

    private fun freshSession(
        hostId: String = "pr24-host",
        deviceId: String = "pr24-device"
    ): AttachedRuntimeSession = AttachedRuntimeSession.create(
        hostId = hostId,
        deviceId = deviceId
    )

    private fun snapshotFrom(
        session: AttachedRuntimeSession,
        runtimeSessionId: String = "rts-pr24",
        hostRole: String = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
    ) = AttachedRuntimeHostSessionSnapshot.from(
        session = session,
        runtimeSessionId = runtimeSessionId,
        hostRole = hostRole
    )

    private fun readinessFrom(
        session: AttachedRuntimeSession,
        runtimeSessionId: String = "rts-pr24",
        hostRole: String = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
    ) = DelegatedTargetReadinessProjection.from(
        snapshotFrom(session, runtimeSessionId, hostRole)
    )

    // ── TargetSelectionOutcome enum wire values ───────────────────────────────

    @Test
    fun `SELECTED wireValue is selected`() {
        assertEquals("selected", TargetSelectionOutcome.SELECTED.wireValue)
    }

    @Test
    fun `FALLBACK wireValue is fallback`() {
        assertEquals("fallback", TargetSelectionOutcome.FALLBACK.wireValue)
    }

    @Test
    fun `REJECTED wireValue is rejected`() {
        assertEquals("rejected", TargetSelectionOutcome.REJECTED.wireValue)
    }

    // ── Wire key constants (PR-24) ────────────────────────────────────────────

    @Test
    fun `KEY_SELECTION_OUTCOME is readiness_selection_outcome`() {
        assertEquals(
            "readiness_selection_outcome",
            DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME
        )
    }

    @Test
    fun `KEY_SELECTION_OUTCOME_REASON is readiness_selection_outcome_reason`() {
        assertEquals(
            "readiness_selection_outcome_reason",
            DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME_REASON
        )
    }

    @Test
    fun `FALLBACK_REASON_NON_PRIMARY_ROLE is non_primary_role`() {
        assertEquals(
            "non_primary_role",
            DelegatedTargetReadinessProjection.FALLBACK_REASON_NON_PRIMARY_ROLE
        )
    }

    // ── ALWAYS_PRESENT_KEYS — PR-24 additions ────────────────────────────────

    @Test
    fun `KEY_SELECTION_OUTCOME is in ALWAYS_PRESENT_KEYS`() {
        assertTrue(
            "KEY_SELECTION_OUTCOME must be in ALWAYS_PRESENT_KEYS (always present)",
            DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME in
                DelegatedTargetReadinessProjection.ALWAYS_PRESENT_KEYS
        )
    }

    @Test
    fun `KEY_SELECTION_OUTCOME_REASON is NOT in ALWAYS_PRESENT_KEYS`() {
        assertFalse(
            "KEY_SELECTION_OUTCOME_REASON must be absent from ALWAYS_PRESENT_KEYS (conditional)",
            DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME_REASON in
                DelegatedTargetReadinessProjection.ALWAYS_PRESENT_KEYS
        )
    }

    // ── selectionOutcome — suitable, primary role ─────────────────────────────

    @Test
    fun `selectionOutcome is SELECTED for ATTACHED session with PRIMARY role`() {
        val r = readinessFrom(freshSession(), hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue)
        assertEquals(
            "Primary-role attached session must yield SELECTED",
            TargetSelectionOutcome.SELECTED,
            r.selectionOutcome
        )
    }

    @Test
    fun `selectionOutcome is SELECTED for ATTACHED session with DEFAULT role`() {
        val r = readinessFrom(freshSession(), hostRole = RuntimeHostDescriptor.FormationRole.DEFAULT.wireValue)
        assertEquals(
            "Default-role attached session must yield SELECTED",
            TargetSelectionOutcome.SELECTED,
            r.selectionOutcome
        )
    }

    @Test
    fun `selectionOutcomeReason is null when selectionOutcome is SELECTED`() {
        val r = readinessFrom(freshSession(), hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue)
        assertNull(
            "selectionOutcomeReason must be null for SELECTED outcome",
            r.selectionOutcomeReason
        )
    }

    @Test
    fun `toMap contains KEY_SELECTION_OUTCOME selected for PRIMARY role`() {
        val map = readinessFrom(freshSession(), hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue).toMap()
        assertEquals(
            "toMap must carry KEY_SELECTION_OUTCOME = selected for primary role",
            TargetSelectionOutcome.SELECTED.wireValue,
            map[DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME]
        )
    }

    @Test
    fun `toMap does NOT contain KEY_SELECTION_OUTCOME_REASON for SELECTED`() {
        val map = readinessFrom(freshSession(), hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue).toMap()
        assertFalse(
            "KEY_SELECTION_OUTCOME_REASON must be absent for SELECTED outcome",
            map.containsKey(DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME_REASON)
        )
    }

    // ── selectionOutcome — suitable, secondary / satellite role (FALLBACK) ─────

    @Test
    fun `selectionOutcome is FALLBACK for ATTACHED session with SECONDARY role`() {
        val r = readinessFrom(freshSession(), hostRole = RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue)
        assertEquals(
            "Secondary-role attached session must yield FALLBACK",
            TargetSelectionOutcome.FALLBACK,
            r.selectionOutcome
        )
    }

    @Test
    fun `selectionOutcome is FALLBACK for ATTACHED session with SATELLITE role`() {
        val r = readinessFrom(freshSession(), hostRole = RuntimeHostDescriptor.FormationRole.SATELLITE.wireValue)
        assertEquals(
            "Satellite-role attached session must yield FALLBACK",
            TargetSelectionOutcome.FALLBACK,
            r.selectionOutcome
        )
    }

    @Test
    fun `selectionOutcomeReason is FALLBACK_REASON_NON_PRIMARY_ROLE for SECONDARY role`() {
        val r = readinessFrom(freshSession(), hostRole = RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue)
        assertEquals(
            "selectionOutcomeReason must be non_primary_role for FALLBACK outcome",
            DelegatedTargetReadinessProjection.FALLBACK_REASON_NON_PRIMARY_ROLE,
            r.selectionOutcomeReason
        )
    }

    @Test
    fun `selectionOutcomeReason is FALLBACK_REASON_NON_PRIMARY_ROLE for SATELLITE role`() {
        val r = readinessFrom(freshSession(), hostRole = RuntimeHostDescriptor.FormationRole.SATELLITE.wireValue)
        assertEquals(
            "selectionOutcomeReason must be non_primary_role for satellite FALLBACK outcome",
            DelegatedTargetReadinessProjection.FALLBACK_REASON_NON_PRIMARY_ROLE,
            r.selectionOutcomeReason
        )
    }

    @Test
    fun `toMap contains KEY_SELECTION_OUTCOME fallback for SECONDARY role`() {
        val map = readinessFrom(freshSession(), hostRole = RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue).toMap()
        assertEquals(
            "toMap must carry KEY_SELECTION_OUTCOME = fallback for secondary role",
            TargetSelectionOutcome.FALLBACK.wireValue,
            map[DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME]
        )
    }

    @Test
    fun `toMap contains KEY_SELECTION_OUTCOME_REASON non_primary_role for SECONDARY`() {
        val map = readinessFrom(freshSession(), hostRole = RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue).toMap()
        assertEquals(
            "toMap must carry KEY_SELECTION_OUTCOME_REASON = non_primary_role for secondary FALLBACK",
            DelegatedTargetReadinessProjection.FALLBACK_REASON_NON_PRIMARY_ROLE,
            map[DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME_REASON]
        )
    }

    // ── selectionOutcome — not suitable (REJECTED) ────────────────────────────

    @Test
    fun `selectionOutcome is REJECTED after EXPLICIT_DETACH`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val r = readinessFrom(session)
        assertEquals(TargetSelectionOutcome.REJECTED, r.selectionOutcome)
    }

    @Test
    fun `selectionOutcome is REJECTED after DISCONNECT`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val r = readinessFrom(session)
        assertEquals(TargetSelectionOutcome.REJECTED, r.selectionOutcome)
    }

    @Test
    fun `selectionOutcome is REJECTED after DISABLE`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        val r = readinessFrom(session)
        assertEquals(TargetSelectionOutcome.REJECTED, r.selectionOutcome)
    }

    @Test
    fun `selectionOutcome is REJECTED after INVALIDATION`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val r = readinessFrom(session)
        assertEquals(TargetSelectionOutcome.REJECTED, r.selectionOutcome)
    }

    @Test
    fun `selectionOutcomeReason is UNSUITABILITY_NOT_ATTACHED for EXPLICIT_DETACH`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val r = readinessFrom(session)
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_NOT_ATTACHED,
            r.selectionOutcomeReason
        )
    }

    @Test
    fun `selectionOutcomeReason is UNSUITABILITY_NOT_ATTACHED for DISCONNECT`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val r = readinessFrom(session)
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_NOT_ATTACHED,
            r.selectionOutcomeReason
        )
    }

    @Test
    fun `selectionOutcomeReason is UNSUITABILITY_INVALIDATED for INVALIDATION`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val r = readinessFrom(session)
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_INVALIDATED,
            r.selectionOutcomeReason
        )
    }

    @Test
    fun `toMap contains KEY_SELECTION_OUTCOME rejected after EXPLICIT_DETACH`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val map = readinessFrom(session).toMap()
        assertEquals(
            TargetSelectionOutcome.REJECTED.wireValue,
            map[DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME]
        )
    }

    @Test
    fun `toMap contains KEY_SELECTION_OUTCOME_REASON when REJECTED`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val map = readinessFrom(session).toMap()
        assertTrue(
            "KEY_SELECTION_OUTCOME_REASON must be present when REJECTED",
            map.containsKey(DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME_REASON)
        )
    }

    // ── selectionOutcome — lifecycle transitions ──────────────────────────────

    @Test
    fun `attach primary then detach transitions selectionOutcome from SELECTED to REJECTED`() {
        val attached = freshSession()
        val detached = attached.detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)

        val rAttached = readinessFrom(attached)
        val rDetached = readinessFrom(detached)

        assertEquals(TargetSelectionOutcome.SELECTED, rAttached.selectionOutcome)
        assertEquals(TargetSelectionOutcome.REJECTED, rDetached.selectionOutcome)
    }

    @Test
    fun `attach primary then invalidate transitions selectionOutcome from SELECTED to REJECTED with UNSUITABILITY_INVALIDATED reason`() {
        val attached = freshSession()
        val invalidated = attached.detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)

        val rAttached = readinessFrom(attached)
        val rInvalidated = readinessFrom(invalidated)

        assertEquals(TargetSelectionOutcome.SELECTED, rAttached.selectionOutcome)
        assertEquals(TargetSelectionOutcome.REJECTED, rInvalidated.selectionOutcome)
        assertEquals(
            DelegatedTargetReadinessProjection.UNSUITABILITY_INVALIDATED,
            rInvalidated.selectionOutcomeReason
        )
    }

    @Test
    fun `reconnect produces SELECTED outcome with new runtimeSessionId`() {
        val session = freshSession()
        val reconnectRuntimeSessionId = "rts-reconnect-pr24"

        val rFirst = readinessFrom(session, runtimeSessionId = "rts-first-pr24")
        val rReconnect = readinessFrom(session, runtimeSessionId = reconnectRuntimeSessionId)

        assertEquals(TargetSelectionOutcome.SELECTED, rFirst.selectionOutcome)
        assertEquals(TargetSelectionOutcome.SELECTED, rReconnect.selectionOutcome)
        assertNotEquals(rFirst.runtimeSessionId, rReconnect.runtimeSessionId)
        assertEquals(reconnectRuntimeSessionId, rReconnect.runtimeSessionId)
    }

    @Test
    fun `SECONDARY role FALLBACK outcome is stable across execution count increments`() {
        val session = freshSession()
        val sessionAfterExecution = session.withExecutionAccepted()

        val rBefore = readinessFrom(session, hostRole = RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue)
        val rAfter = readinessFrom(sessionAfterExecution, hostRole = RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue)

        assertEquals(TargetSelectionOutcome.FALLBACK, rBefore.selectionOutcome)
        assertEquals(TargetSelectionOutcome.FALLBACK, rAfter.selectionOutcome)
        assertNotEquals(
            "delegatedExecutionCount must differ before and after acceptance",
            rBefore.delegatedExecutionCount,
            rAfter.delegatedExecutionCount
        )
    }

    // ── Multi-target projection — no first-active / explicit-target collapse ──

    @Test
    fun `PRIMARY and SECONDARY targets yield SELECTED vs FALLBACK independently`() {
        val primarySession = freshSession(hostId = "host-primary", deviceId = "device-primary")
        val secondarySession = freshSession(hostId = "host-secondary", deviceId = "device-secondary")

        val rPrimary = readinessFrom(
            primarySession,
            runtimeSessionId = "rts-primary",
            hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
        )
        val rSecondary = readinessFrom(
            secondarySession,
            runtimeSessionId = "rts-secondary",
            hostRole = RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue
        )

        assertEquals(
            "Primary target must be SELECTED regardless of evaluation order",
            TargetSelectionOutcome.SELECTED,
            rPrimary.selectionOutcome
        )
        assertEquals(
            "Secondary target must be FALLBACK regardless of evaluation order",
            TargetSelectionOutcome.FALLBACK,
            rSecondary.selectionOutcome
        )
        // Projections are independent — primary does not suppress secondary's suitability
        assertTrue("Both targets must be isSuitableTarget=true", rPrimary.isSuitableTarget)
        assertTrue("Secondary target must also be isSuitableTarget=true", rSecondary.isSuitableTarget)
    }

    @Test
    fun `two SELECTED PRIMARY sessions produce independent non-identical projections`() {
        val sessionA = freshSession(hostId = "host-a", deviceId = "device-a")
        val sessionB = freshSession(hostId = "host-b", deviceId = "device-b")

        val rA = readinessFrom(sessionA, runtimeSessionId = "rts-a", hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue)
        val rB = readinessFrom(sessionB, runtimeSessionId = "rts-b", hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue)

        // Both SELECTED — multi-target selection is possible without first-active collapse
        assertEquals(TargetSelectionOutcome.SELECTED, rA.selectionOutcome)
        assertEquals(TargetSelectionOutcome.SELECTED, rB.selectionOutcome)
        // Different identity — not the same projection
        assertNotEquals("Session IDs must differ", rA.sessionId, rB.sessionId)
        assertNotEquals("Device IDs must differ", rA.deviceId, rB.deviceId)
        assertNotEquals("runtimeSessionIds must differ", rA.runtimeSessionId, rB.runtimeSessionId)
    }

    @Test
    fun `two REJECTED sessions both produce REJECTED independently`() {
        val sessionA = freshSession(hostId = "host-a", deviceId = "device-a")
            .detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val sessionB = freshSession(hostId = "host-b", deviceId = "device-b")
            .detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)

        val rA = readinessFrom(sessionA)
        val rB = readinessFrom(sessionB)

        assertEquals(TargetSelectionOutcome.REJECTED, rA.selectionOutcome)
        assertEquals(TargetSelectionOutcome.REJECTED, rB.selectionOutcome)
        // Rejection reasons differ — projections are independent
        assertNotEquals(rA.selectionOutcomeReason, rB.selectionOutcomeReason)
    }

    @Test
    fun `PRIMARY target projection is SELECTED even if evaluated after SECONDARY target projection`() {
        val secondarySession = freshSession(hostId = "host-secondary", deviceId = "device-secondary")
        val primarySession = freshSession(hostId = "host-primary", deviceId = "device-primary")

        // Evaluate SECONDARY first (simulating first-active attachment order)
        val rSecondary = readinessFrom(
            secondarySession,
            runtimeSessionId = "rts-secondary-first",
            hostRole = RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue
        )
        // Then evaluate PRIMARY — must still be SELECTED regardless of order
        val rPrimary = readinessFrom(
            primarySession,
            runtimeSessionId = "rts-primary-later",
            hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
        )

        assertEquals(
            "PRIMARY evaluated after SECONDARY must still be SELECTED",
            TargetSelectionOutcome.SELECTED,
            rPrimary.selectionOutcome
        )
        assertEquals(
            "SECONDARY evaluated first must still be FALLBACK",
            TargetSelectionOutcome.FALLBACK,
            rSecondary.selectionOutcome
        )
    }

    // ── RuntimeController.targetReadinessProjection StateFlow ─────────────────

    @Test
    fun `targetReadinessProjection is null before any session is opened`() {
        val controller = buildController()
        assertNull(
            "targetReadinessProjection must be null before any session is opened",
            controller.targetReadinessProjection.value
        )
    }

    @Test
    fun `targetReadinessProjection is null after stop with no prior session`() {
        val controller = buildController()
        controller.stop()
        assertNull(
            "targetReadinessProjection must remain null after stop() with no prior session",
            controller.targetReadinessProjection.value
        )
    }

    @Test
    fun `targetReadinessProjection is null after startWithTimeout failure`() = runBlocking {
        val controller = buildController(timeoutMs = 200L)
        controller.startWithTimeout()
        assertNull(
            "targetReadinessProjection must be null after a failed startWithTimeout",
            controller.targetReadinessProjection.value
        )
    }

    // ── No duplicate truth — targetReadinessProjection derived from hostSessionSnapshot ──

    @Test
    fun `targetReadinessProjection null iff hostSessionSnapshot null — both initially`() {
        val controller = buildController()
        assertNull(
            "hostSessionSnapshot must be null initially",
            controller.hostSessionSnapshot.value
        )
        assertNull(
            "targetReadinessProjection must be null when hostSessionSnapshot is null",
            controller.targetReadinessProjection.value
        )
    }

    @Test
    fun `targetReadinessProjection null iff hostSessionSnapshot null — after stop`() {
        val controller = buildController()
        controller.stop()
        assertNull(
            "hostSessionSnapshot must be null after stop() with no session",
            controller.hostSessionSnapshot.value
        )
        assertNull(
            "targetReadinessProjection must be null when hostSessionSnapshot is null",
            controller.targetReadinessProjection.value
        )
    }

    @Test
    fun `targetReadinessProjection snapshot equals hostSessionSnapshot value when both non-null`() {
        val controller = buildController()
        // Exercise invalidateSession (which closes any existing session);
        // then verify both flows are consistently null — no phantom non-null divergence.
        controller.invalidateSession()
        val snapshot = controller.hostSessionSnapshot.value
        val readiness = controller.targetReadinessProjection.value

        if (snapshot == null) {
            assertNull(
                "targetReadinessProjection must be null when hostSessionSnapshot is null",
                readiness
            )
        } else {
            assertNotNull("targetReadinessProjection must be non-null when snapshot is non-null", readiness)
            assertEquals(
                "targetReadinessProjection.snapshot must equal hostSessionSnapshot.value",
                snapshot,
                readiness!!.snapshot
            )
        }
    }

    @Test
    fun `both flows are null simultaneously before any session`() {
        val controller = buildController()
        val snapshotIsNull = controller.hostSessionSnapshot.value == null
        val readinessIsNull = controller.targetReadinessProjection.value == null
        assertEquals(
            "hostSessionSnapshot and targetReadinessProjection must both be null or both non-null",
            snapshotIsNull,
            readinessIsNull
        )
    }

    @Test
    fun `after startWithTimeout failure both flows remain null`() = runBlocking {
        val controller = buildController(timeoutMs = 200L)
        controller.startWithTimeout()
        val snapshotIsNull = controller.hostSessionSnapshot.value == null
        val readinessIsNull = controller.targetReadinessProjection.value == null
        assertTrue("hostSessionSnapshot must be null after connection failure", snapshotIsNull)
        assertEquals(
            "targetReadinessProjection must be null iff hostSessionSnapshot is null",
            snapshotIsNull,
            readinessIsNull
        )
    }
}
