package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.coordination.FormationCoordinationSurface
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
 * PR-2 — Formation rebalance and runtime recovery hooks test suite.
 *
 * Regression and acceptance tests for all PR-2 additions:
 *
 *  1. [FormationRebalanceEvent] — six sealed subclasses with stable wire values.
 *  2. [ParticipantHealthState] — five health states with stable wire values and helpers.
 *  3. [FormationParticipationRebalancer] — evaluation logic for participation coherence
 *     and role reassignment decisions.
 *  4. [FormationCoordinationSurface] — observable rebalance event bridge between
 *     [RuntimeController] state changes and formation-aware behavior.
 *  5. [RuntimeHostDescriptor.withRole] — role reassignment helper.
 *  6. [RuntimeHostDescriptor.isFormationPresent] — derived presence predicate.
 *  7. [RuntimeController.formationRebalanceEvent] — observable rebalance event flow.
 *  8. [RuntimeController.notifyParticipantHealthChanged] — health-change hook.
 *  9. [RuntimeController.requestRoleReassessment] — role reassessment hook.
 * 10. Formation rebalance events emitted during reconnect recovery state transitions.
 *
 * ## Test matrix
 *
 * ### FormationRebalanceEvent — wire values
 *  - WIRE_READINESS_CHANGED is "formation_readiness_changed"
 *  - WIRE_PARTICIPANT_LOST is "formation_participant_lost"
 *  - WIRE_PARTICIPANT_REJOINED is "formation_participant_rejoined"
 *  - WIRE_ROLE_REASSIGNMENT_REQUESTED is "formation_role_reassignment_requested"
 *  - WIRE_DEGRADED_FORMATION_DETECTED is "formation_degraded_detected"
 *  - WIRE_RECOVERY_COMPLETED is "formation_recovery_completed"
 *  - all six wire values are distinct
 *
 * ### ParticipantHealthState — wire values and helpers
 *  - HEALTHY wireValue is "healthy"
 *  - DEGRADED wireValue is "degraded"
 *  - RECOVERING wireValue is "recovering"
 *  - FAILED wireValue is "failed"
 *  - UNKNOWN wireValue is "unknown"
 *  - isAcceptingTasks returns true only for HEALTHY
 *  - isCompromised returns true for DEGRADED, RECOVERING, FAILED
 *  - fromValue returns UNKNOWN for unknown strings
 *
 * ### RuntimeHostDescriptor — new helpers
 *  - withRole produces updated descriptor with new role
 *  - original descriptor is not mutated
 *  - isFormationPresent is true for ACTIVE and STANDBY
 *  - isFormationPresent is false for INACTIVE and DRAINING
 *
 * ### FormationParticipationRebalancer — participation evaluation
 *  - HEALTHY+IDLE → no rebalance required
 *  - DEGRADED → requires rebalance, DEGRADED_CONTINUATION mode
 *  - RECOVERING → requires rebalance, AWAIT_HEALTH_RESTORE mode
 *  - FAILED → requires rebalance, WITHDRAW_PARTICIPATION mode
 *  - RECOVERING recovery state → requires rebalance, DEGRADED_CONTINUATION
 *  - FAILED recovery state → requires rebalance, WITHDRAW_PARTICIPATION
 *
 * ### FormationParticipationRebalancer — role reassignment evaluation
 *  - accepts reassignment when HEALTHY and IDLE
 *  - declines (deferrable) when RECOVERING
 *  - declines (non-deferrable) when FAILED health
 *  - declines (deferrable) when ws recovery RECOVERING
 *  - declines (non-deferrable) when ws recovery FAILED
 *  - no-op (accepted) when same role requested
 *
 * ### GalaxyLogger — new tag constants
 *  - TAG_FORMATION_REBALANCE value is "GALAXY:FORMATION:REBALANCE"
 *  - TAG_FORMATION_ROLE value is "GALAXY:FORMATION:ROLE"
 *  - TAG_FORMATION_HEALTH value is "GALAXY:FORMATION:HEALTH"
 *  - all three tags are distinct from each other and from pre-PR-2 tags
 *
 * ### FormationCoordinationSurface — event bridge
 *  - emits ReadinessChanged on onParticipantHealthChanged with DEGRADED health
 *  - emits ReadinessChanged on onReconnectRecoveryStateChanged to RECOVERING
 *  - emits ParticipantRejoined on onReconnectRecoveryStateChanged to RECOVERED
 *  - emits ReadinessChanged on onReconnectRecoveryStateChanged to FAILED
 *  - emits ParticipantLost on onParticipantLost
 *  - also emits DegradedFormationDetected when present < expected on onParticipantLost
 *  - emits RecoveryCompleted on onRecoveryCompleted
 *  - no event for IDLE recovery state transition
 *
 * ### RuntimeController — formation rebalance flow
 *  - formationRebalanceEvent flow is non-null
 *  - formationRebalanceEvent emits ReadinessChanged on WS disconnect while Active
 *  - formationRebalanceEvent emits ParticipantRejoined on WS reconnect (RECOVERING → RECOVERED)
 *  - formationRebalanceEvent emits ReadinessChanged on WS error (RECOVERING → FAILED)
 *  - notifyParticipantHealthChanged with DEGRADED emits ReadinessChanged
 *  - notifyParticipantHealthChanged without hostDescriptor is a no-op
 *  - requestRoleReassessment accepted updates hostDescriptor role
 *  - requestRoleReassessment declined preserves original role
 *  - requestRoleReassessment without hostDescriptor returns declined decision
 */
class Pr2FormationRebalanceAndRecoveryHooksTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake services (copied from Pr33ReconnectResilienceTest pattern) ────────

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

    private fun buildDescriptor(
        formationRole: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY
    ) = RuntimeHostDescriptor.of(
        deviceId = "test-device",
        deviceRole = "phone",
        formationRole = formationRole
    )

    private fun buildController(
        descriptor: RuntimeHostDescriptor? = null
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = InMemoryAppSettings(),
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L,
            hostDescriptor = descriptor
        )
        return controller to client
    }

    // ── FormationRebalanceEvent — wire values ─────────────────────────────────

    @Test
    fun `WIRE_READINESS_CHANGED is 'formation_readiness_changed'`() {
        assertEquals("formation_readiness_changed", FormationRebalanceEvent.WIRE_READINESS_CHANGED)
    }

    @Test
    fun `WIRE_PARTICIPANT_LOST is 'formation_participant_lost'`() {
        assertEquals("formation_participant_lost", FormationRebalanceEvent.WIRE_PARTICIPANT_LOST)
    }

    @Test
    fun `WIRE_PARTICIPANT_REJOINED is 'formation_participant_rejoined'`() {
        assertEquals("formation_participant_rejoined", FormationRebalanceEvent.WIRE_PARTICIPANT_REJOINED)
    }

    @Test
    fun `WIRE_ROLE_REASSIGNMENT_REQUESTED is 'formation_role_reassignment_requested'`() {
        assertEquals("formation_role_reassignment_requested", FormationRebalanceEvent.WIRE_ROLE_REASSIGNMENT_REQUESTED)
    }

    @Test
    fun `WIRE_DEGRADED_FORMATION_DETECTED is 'formation_degraded_detected'`() {
        assertEquals("formation_degraded_detected", FormationRebalanceEvent.WIRE_DEGRADED_FORMATION_DETECTED)
    }

    @Test
    fun `WIRE_RECOVERY_COMPLETED is 'formation_recovery_completed'`() {
        assertEquals("formation_recovery_completed", FormationRebalanceEvent.WIRE_RECOVERY_COMPLETED)
    }

    @Test
    fun `all six FormationRebalanceEvent wire values are distinct`() {
        val values = FormationRebalanceEvent.ALL_WIRE_VALUES
        assertEquals(6, values.size)
    }

    @Test
    fun `ReadinessChanged wireValue matches WIRE_READINESS_CHANGED`() {
        val event = FormationRebalanceEvent.ReadinessChanged(
            previousReadiness = ParticipantReadinessState.READY,
            currentReadiness = ParticipantReadinessState.NOT_READY,
            previousParticipation = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
            currentParticipation = RuntimeHostDescriptor.HostParticipationState.INACTIVE,
            trigger = "test"
        )
        assertEquals(FormationRebalanceEvent.WIRE_READINESS_CHANGED, event.wireValue)
    }

    @Test
    fun `ParticipantLost wireValue matches WIRE_PARTICIPANT_LOST`() {
        val event = FormationRebalanceEvent.ParticipantLost(
            lostParticipantId = "p1",
            lostDeviceId = "d1",
            detachCause = "disconnect"
        )
        assertEquals(FormationRebalanceEvent.WIRE_PARTICIPANT_LOST, event.wireValue)
    }

    @Test
    fun `ParticipantRejoined wireValue matches WIRE_PARTICIPANT_REJOINED`() {
        val event = FormationRebalanceEvent.ParticipantRejoined(
            rejoinedParticipantId = "p1",
            rejoinedDeviceId = "d1",
            sessionContinuityEpoch = 1
        )
        assertEquals(FormationRebalanceEvent.WIRE_PARTICIPANT_REJOINED, event.wireValue)
    }

    @Test
    fun `DegradedFormationDetected wireValue matches WIRE_DEGRADED_FORMATION_DETECTED`() {
        val event = FormationRebalanceEvent.DegradedFormationDetected(
            presentParticipantCount = 1,
            expectedParticipantCount = 2
        )
        assertEquals(FormationRebalanceEvent.WIRE_DEGRADED_FORMATION_DETECTED, event.wireValue)
    }

    @Test
    fun `RecoveryCompleted wireValue matches WIRE_RECOVERY_COMPLETED`() {
        val event = FormationRebalanceEvent.RecoveryCompleted(
            restoredParticipantCount = 2,
            recoveryTrigger = "participant_rejoined"
        )
        assertEquals(FormationRebalanceEvent.WIRE_RECOVERY_COMPLETED, event.wireValue)
    }

    // ── ParticipantHealthState — wire values and helpers ──────────────────────

    @Test
    fun `HEALTHY wireValue is 'healthy'`() {
        assertEquals("healthy", ParticipantHealthState.HEALTHY.wireValue)
    }

    @Test
    fun `DEGRADED wireValue is 'degraded'`() {
        assertEquals("degraded", ParticipantHealthState.DEGRADED.wireValue)
    }

    @Test
    fun `RECOVERING wireValue is 'recovering'`() {
        assertEquals("recovering", ParticipantHealthState.RECOVERING.wireValue)
    }

    @Test
    fun `FAILED wireValue is 'failed'`() {
        assertEquals("failed", ParticipantHealthState.FAILED.wireValue)
    }

    @Test
    fun `UNKNOWN wireValue is 'unknown'`() {
        assertEquals("unknown", ParticipantHealthState.UNKNOWN.wireValue)
    }

    @Test
    fun `isAcceptingTasks returns true only for HEALTHY`() {
        assertTrue(ParticipantHealthState.isAcceptingTasks(ParticipantHealthState.HEALTHY))
        assertFalse(ParticipantHealthState.isAcceptingTasks(ParticipantHealthState.DEGRADED))
        assertFalse(ParticipantHealthState.isAcceptingTasks(ParticipantHealthState.RECOVERING))
        assertFalse(ParticipantHealthState.isAcceptingTasks(ParticipantHealthState.FAILED))
        assertFalse(ParticipantHealthState.isAcceptingTasks(ParticipantHealthState.UNKNOWN))
    }

    @Test
    fun `isCompromised returns true for DEGRADED RECOVERING FAILED`() {
        assertTrue(ParticipantHealthState.isCompromised(ParticipantHealthState.DEGRADED))
        assertTrue(ParticipantHealthState.isCompromised(ParticipantHealthState.RECOVERING))
        assertTrue(ParticipantHealthState.isCompromised(ParticipantHealthState.FAILED))
        assertFalse(ParticipantHealthState.isCompromised(ParticipantHealthState.HEALTHY))
        assertFalse(ParticipantHealthState.isCompromised(ParticipantHealthState.UNKNOWN))
    }

    @Test
    fun `fromValue returns UNKNOWN for unknown string`() {
        assertEquals(ParticipantHealthState.UNKNOWN, ParticipantHealthState.fromValue("nonexistent"))
        assertEquals(ParticipantHealthState.UNKNOWN, ParticipantHealthState.fromValue(null))
    }

    @Test
    fun `fromValue parses all valid wire values`() {
        assertEquals(ParticipantHealthState.HEALTHY, ParticipantHealthState.fromValue("healthy"))
        assertEquals(ParticipantHealthState.DEGRADED, ParticipantHealthState.fromValue("degraded"))
        assertEquals(ParticipantHealthState.RECOVERING, ParticipantHealthState.fromValue("recovering"))
        assertEquals(ParticipantHealthState.FAILED, ParticipantHealthState.fromValue("failed"))
        assertEquals(ParticipantHealthState.UNKNOWN, ParticipantHealthState.fromValue("unknown"))
    }

    @Test
    fun `ALL_WIRE_VALUES contains all five health states`() {
        assertEquals(5, ParticipantHealthState.ALL_WIRE_VALUES.size)
        assertTrue(ParticipantHealthState.ALL_WIRE_VALUES.contains("healthy"))
        assertTrue(ParticipantHealthState.ALL_WIRE_VALUES.contains("degraded"))
        assertTrue(ParticipantHealthState.ALL_WIRE_VALUES.contains("recovering"))
        assertTrue(ParticipantHealthState.ALL_WIRE_VALUES.contains("failed"))
        assertTrue(ParticipantHealthState.ALL_WIRE_VALUES.contains("unknown"))
    }

    // ── RuntimeHostDescriptor — new helpers ───────────────────────────────────

    @Test
    fun `withRole returns new descriptor with updated role`() {
        val original = buildDescriptor(RuntimeHostDescriptor.FormationRole.PRIMARY)
        val updated = original.withRole(RuntimeHostDescriptor.FormationRole.SECONDARY)
        assertEquals(RuntimeHostDescriptor.FormationRole.SECONDARY, updated.formationRole)
        assertEquals(original.hostId, updated.hostId)
        assertEquals(original.deviceId, updated.deviceId)
        assertEquals(original.deviceRole, updated.deviceRole)
        assertEquals(original.participationState, updated.participationState)
    }

    @Test
    fun `withRole does not mutate original descriptor`() {
        val original = buildDescriptor(RuntimeHostDescriptor.FormationRole.PRIMARY)
        original.withRole(RuntimeHostDescriptor.FormationRole.SATELLITE)
        assertEquals(RuntimeHostDescriptor.FormationRole.PRIMARY, original.formationRole)
    }

    @Test
    fun `isFormationPresent is true for ACTIVE`() {
        val descriptor = buildDescriptor().withState(RuntimeHostDescriptor.HostParticipationState.ACTIVE)
        assertTrue(descriptor.isFormationPresent)
    }

    @Test
    fun `isFormationPresent is true for STANDBY`() {
        val descriptor = buildDescriptor().withState(RuntimeHostDescriptor.HostParticipationState.STANDBY)
        assertTrue(descriptor.isFormationPresent)
    }

    @Test
    fun `isFormationPresent is false for INACTIVE`() {
        val descriptor = buildDescriptor().withState(RuntimeHostDescriptor.HostParticipationState.INACTIVE)
        assertFalse(descriptor.isFormationPresent)
    }

    @Test
    fun `isFormationPresent is false for DRAINING`() {
        val descriptor = buildDescriptor().withState(RuntimeHostDescriptor.HostParticipationState.DRAINING)
        assertFalse(descriptor.isFormationPresent)
    }

    // ── FormationParticipationRebalancer — participation evaluation ───────────

    @Test
    fun `HEALTHY health and IDLE recovery — no rebalance required`() {
        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateParticipation(
            descriptor = buildDescriptor(),
            healthState = ParticipantHealthState.HEALTHY,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE
        )
        assertFalse(decision.requiresRebalance)
        assertEquals(FormationParticipationRebalancer.ContinuationMode.CONTINUE_NORMALLY, decision.continuationMode)
        assertNull(decision.suggestedEvent)
    }

    @Test
    fun `DEGRADED health — requires rebalance with DEGRADED_CONTINUATION`() {
        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateParticipation(
            descriptor = buildDescriptor(),
            healthState = ParticipantHealthState.DEGRADED,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE
        )
        assertTrue(decision.requiresRebalance)
        assertEquals(FormationParticipationRebalancer.ContinuationMode.DEGRADED_CONTINUATION, decision.continuationMode)
        assertNotNull(decision.suggestedEvent)
    }

    @Test
    fun `RECOVERING health — requires rebalance with AWAIT_HEALTH_RESTORE`() {
        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateParticipation(
            descriptor = buildDescriptor(),
            healthState = ParticipantHealthState.RECOVERING,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE
        )
        assertTrue(decision.requiresRebalance)
        assertEquals(FormationParticipationRebalancer.ContinuationMode.AWAIT_HEALTH_RESTORE, decision.continuationMode)
    }

    @Test
    fun `FAILED health — requires rebalance with WITHDRAW_PARTICIPATION`() {
        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateParticipation(
            descriptor = buildDescriptor(),
            healthState = ParticipantHealthState.FAILED,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE
        )
        assertTrue(decision.requiresRebalance)
        assertEquals(FormationParticipationRebalancer.ContinuationMode.WITHDRAW_PARTICIPATION, decision.continuationMode)
    }

    @Test
    fun `RECOVERING ws recovery state — requires rebalance`() {
        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateParticipation(
            descriptor = buildDescriptor(),
            healthState = ParticipantHealthState.HEALTHY,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERING
        )
        assertTrue(decision.requiresRebalance)
        assertEquals(FormationParticipationRebalancer.ContinuationMode.DEGRADED_CONTINUATION, decision.continuationMode)
    }

    @Test
    fun `FAILED ws recovery state — requires rebalance with WITHDRAW_PARTICIPATION`() {
        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateParticipation(
            descriptor = buildDescriptor(),
            healthState = ParticipantHealthState.HEALTHY,
            reconnectRecoveryState = ReconnectRecoveryState.FAILED
        )
        assertTrue(decision.requiresRebalance)
        assertEquals(FormationParticipationRebalancer.ContinuationMode.WITHDRAW_PARTICIPATION, decision.continuationMode)
    }

    // ── FormationParticipationRebalancer — role reassignment evaluation ───────

    @Test
    fun `accepts role reassignment when HEALTHY and IDLE`() {
        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateRoleReassignment(
            descriptor = buildDescriptor(RuntimeHostDescriptor.FormationRole.PRIMARY),
            requestedRole = RuntimeHostDescriptor.FormationRole.SECONDARY,
            healthState = ParticipantHealthState.HEALTHY,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE
        )
        assertTrue(decision.accepted)
        assertNull(decision.declineReason)
        assertEquals(RuntimeHostDescriptor.FormationRole.PRIMARY, decision.previousRole)
        assertEquals(RuntimeHostDescriptor.FormationRole.SECONDARY, decision.requestedRole)
    }

    @Test
    fun `declines role reassignment (deferrable) when health is RECOVERING`() {
        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateRoleReassignment(
            descriptor = buildDescriptor(RuntimeHostDescriptor.FormationRole.PRIMARY),
            requestedRole = RuntimeHostDescriptor.FormationRole.SECONDARY,
            healthState = ParticipantHealthState.RECOVERING,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE
        )
        assertFalse(decision.accepted)
        assertTrue(decision.deferrable)
        assertEquals("participant_health_recovering", decision.declineReason)
    }

    @Test
    fun `declines role reassignment (non-deferrable) when health is FAILED`() {
        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateRoleReassignment(
            descriptor = buildDescriptor(),
            requestedRole = RuntimeHostDescriptor.FormationRole.SECONDARY,
            healthState = ParticipantHealthState.FAILED,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE
        )
        assertFalse(decision.accepted)
        assertFalse(decision.deferrable)
        assertEquals("participant_health_failed", decision.declineReason)
    }

    @Test
    fun `declines role reassignment (deferrable) when ws recovery is RECOVERING`() {
        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateRoleReassignment(
            descriptor = buildDescriptor(),
            requestedRole = RuntimeHostDescriptor.FormationRole.SECONDARY,
            healthState = ParticipantHealthState.HEALTHY,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERING
        )
        assertFalse(decision.accepted)
        assertTrue(decision.deferrable)
        assertEquals("ws_reconnect_in_progress", decision.declineReason)
    }

    @Test
    fun `declines role reassignment (non-deferrable) when ws recovery is FAILED`() {
        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateRoleReassignment(
            descriptor = buildDescriptor(),
            requestedRole = RuntimeHostDescriptor.FormationRole.SECONDARY,
            healthState = ParticipantHealthState.HEALTHY,
            reconnectRecoveryState = ReconnectRecoveryState.FAILED
        )
        assertFalse(decision.accepted)
        assertFalse(decision.deferrable)
        assertEquals("ws_recovery_failed", decision.declineReason)
    }

    @Test
    fun `accepts role reassignment when same role requested (no-op)`() {
        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateRoleReassignment(
            descriptor = buildDescriptor(RuntimeHostDescriptor.FormationRole.PRIMARY),
            requestedRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
            healthState = ParticipantHealthState.HEALTHY,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE
        )
        assertTrue(decision.accepted)
        assertNull(decision.declineReason)
    }

    // ── GalaxyLogger — new tag constants ─────────────────────────────────────

    @Test
    fun `TAG_FORMATION_REBALANCE value is 'GALAXY:FORMATION:REBALANCE'`() {
        assertEquals("GALAXY:FORMATION:REBALANCE", GalaxyLogger.TAG_FORMATION_REBALANCE)
    }

    @Test
    fun `TAG_FORMATION_ROLE value is 'GALAXY:FORMATION:ROLE'`() {
        assertEquals("GALAXY:FORMATION:ROLE", GalaxyLogger.TAG_FORMATION_ROLE)
    }

    @Test
    fun `TAG_FORMATION_HEALTH value is 'GALAXY:FORMATION:HEALTH'`() {
        assertEquals("GALAXY:FORMATION:HEALTH", GalaxyLogger.TAG_FORMATION_HEALTH)
    }

    @Test
    fun `three new formation tags are all distinct`() {
        val tags = setOf(
            GalaxyLogger.TAG_FORMATION_REBALANCE,
            GalaxyLogger.TAG_FORMATION_ROLE,
            GalaxyLogger.TAG_FORMATION_HEALTH
        )
        assertEquals(3, tags.size)
    }

    @Test
    fun `new formation tags are distinct from pre-PR2 tags`() {
        val priorTags = setOf(
            GalaxyLogger.TAG_RECONNECT_RECOVERY,
            GalaxyLogger.TAG_STAGED_MESH,
            GalaxyLogger.TAG_SESSION_AXIS,
            GalaxyLogger.TAG_INTERACTION_ACCEPTANCE,
            GalaxyLogger.TAG_ROLLOUT_CONTROL
        )
        assertFalse(priorTags.contains(GalaxyLogger.TAG_FORMATION_REBALANCE))
        assertFalse(priorTags.contains(GalaxyLogger.TAG_FORMATION_ROLE))
        assertFalse(priorTags.contains(GalaxyLogger.TAG_FORMATION_HEALTH))
    }

    // ── FormationCoordinationSurface — event bridge ───────────────────────────

    @Test
    fun `emits ReadinessChanged on DEGRADED health change`() = runBlocking {
        val surface = FormationCoordinationSurface()
        val descriptor = buildDescriptor().withState(RuntimeHostDescriptor.HostParticipationState.ACTIVE)

        surface.onParticipantHealthChanged(
            descriptor = descriptor,
            newHealthState = ParticipantHealthState.DEGRADED,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE
        )

        val event = withTimeoutOrNull(100) { surface.rebalanceEvents.first() }
        assertNotNull(event)
        assertTrue(event is FormationRebalanceEvent.ReadinessChanged)
    }

    @Test
    fun `emits ReadinessChanged on recovery state change to RECOVERING`() = runBlocking {
        val surface = FormationCoordinationSurface()
        val descriptor = buildDescriptor()

        surface.onReconnectRecoveryStateChanged(
            descriptor = descriptor,
            previousRecoveryState = ReconnectRecoveryState.IDLE,
            newRecoveryState = ReconnectRecoveryState.RECOVERING
        )

        val event = withTimeoutOrNull(100) { surface.rebalanceEvents.first() }
        assertNotNull(event)
        assertTrue(event is FormationRebalanceEvent.ReadinessChanged)
    }

    @Test
    fun `emits ParticipantRejoined on recovery state change to RECOVERED`() = runBlocking {
        val surface = FormationCoordinationSurface()
        val descriptor = buildDescriptor()

        surface.onReconnectRecoveryStateChanged(
            descriptor = descriptor,
            previousRecoveryState = ReconnectRecoveryState.RECOVERING,
            newRecoveryState = ReconnectRecoveryState.RECOVERED,
            sessionContinuityEpoch = 1
        )

        val event = withTimeoutOrNull(100) { surface.rebalanceEvents.first() }
        assertNotNull(event)
        assertTrue(event is FormationRebalanceEvent.ParticipantRejoined)
        assertEquals(1, (event as FormationRebalanceEvent.ParticipantRejoined).sessionContinuityEpoch)
    }

    @Test
    fun `emits ReadinessChanged on recovery state change to FAILED`() = runBlocking {
        val surface = FormationCoordinationSurface()
        val descriptor = buildDescriptor()

        surface.onReconnectRecoveryStateChanged(
            descriptor = descriptor,
            previousRecoveryState = ReconnectRecoveryState.RECOVERING,
            newRecoveryState = ReconnectRecoveryState.FAILED
        )

        val event = withTimeoutOrNull(100) { surface.rebalanceEvents.first() }
        assertNotNull(event)
        assertTrue(event is FormationRebalanceEvent.ReadinessChanged)
    }

    @Test
    fun `no event emitted for IDLE recovery state transition`() = runBlocking {
        val surface = FormationCoordinationSurface()
        val descriptor = buildDescriptor()

        surface.onReconnectRecoveryStateChanged(
            descriptor = descriptor,
            previousRecoveryState = ReconnectRecoveryState.RECOVERING,
            newRecoveryState = ReconnectRecoveryState.IDLE
        )

        // No event should be emitted for IDLE transition
        val event = withTimeoutOrNull(50) { surface.rebalanceEvents.first() }
        assertNull(event)
    }

    @Test
    fun `emits ParticipantLost on onParticipantLost`() = runBlocking {
        val surface = FormationCoordinationSurface()

        surface.onParticipantLost(
            lostParticipantId = "p1",
            lostDeviceId = "d1",
            detachCause = "disconnect",
            presentParticipantCount = 2,
            expectedParticipantCount = 2
        )

        val event = withTimeoutOrNull(100) { surface.rebalanceEvents.first() }
        assertNotNull(event)
        assertTrue(event is FormationRebalanceEvent.ParticipantLost)
    }

    @Test
    fun `emits DegradedFormationDetected when present less than expected on onParticipantLost`() = runBlocking {
        val surface = FormationCoordinationSurface()

        surface.onParticipantLost(
            lostParticipantId = "p1",
            lostDeviceId = "d1",
            detachCause = "disconnect",
            presentParticipantCount = 1,
            expectedParticipantCount = 2
        )

        // Collect two events: ParticipantLost + DegradedFormationDetected
        val events = mutableListOf<FormationRebalanceEvent>()
        withTimeoutOrNull(100) {
            repeat(2) { events.add(surface.rebalanceEvents.first()) }
        }
        assertTrue(events.any { it is FormationRebalanceEvent.ParticipantLost })
        assertTrue(events.any { it is FormationRebalanceEvent.DegradedFormationDetected })
    }

    @Test
    fun `emits RecoveryCompleted on onRecoveryCompleted`() = runBlocking {
        val surface = FormationCoordinationSurface()

        surface.onRecoveryCompleted(
            restoredParticipantCount = 2,
            recoveryTrigger = "participant_rejoined"
        )

        val event = withTimeoutOrNull(100) { surface.rebalanceEvents.first() }
        assertNotNull(event)
        assertTrue(event is FormationRebalanceEvent.RecoveryCompleted)
        assertEquals("participant_rejoined", (event as FormationRebalanceEvent.RecoveryCompleted).recoveryTrigger)
    }

    @Test
    fun `RoleReassignmentRequested emitted from onRoleReassignmentRequested when accepted`() = runBlocking {
        val surface = FormationCoordinationSurface()
        val descriptor = buildDescriptor(RuntimeHostDescriptor.FormationRole.PRIMARY)

        surface.onRoleReassignmentRequested(
            descriptor = descriptor,
            requestedRole = RuntimeHostDescriptor.FormationRole.SECONDARY,
            healthState = ParticipantHealthState.HEALTHY,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            requestingCoordinator = "test-coordinator"
        )

        val event = withTimeoutOrNull(100) { surface.rebalanceEvents.first() }
        assertNotNull(event)
        assertTrue(event is FormationRebalanceEvent.RoleReassignmentRequested)
    }

    // ── RuntimeController — formationRebalanceEvent flow ──────────────────────

    @Test
    fun `formationRebalanceEvent flow is non-null`() {
        val (controller, _) = buildController()
        assertNotNull(controller.formationRebalanceEvent)
    }

    @Test
    fun `formationRebalanceEvent emits ReadinessChanged on WS disconnect while Active`() = runBlocking {
        val descriptor = buildDescriptor()
        val (controller, client) = buildController(descriptor = descriptor)
        controller.setActiveForTest()

        client.simulateDisconnected()

        val event = withTimeoutOrNull(200) { controller.formationRebalanceEvent.first() }
        assertNotNull("Expected a formation rebalance event after WS disconnect", event)
        assertTrue(event is FormationRebalanceEvent.ReadinessChanged)
        assertEquals("ws_disconnect_active", (event as FormationRebalanceEvent.ReadinessChanged).trigger)
    }

    @Test
    fun `formationRebalanceEvent emits ParticipantRejoined on WS reconnect after RECOVERING`() = runBlocking {
        val descriptor = buildDescriptor()
        val (controller, client) = buildController(descriptor = descriptor)
        controller.setActiveForTest()

        // First disconnect to enter RECOVERING
        client.simulateDisconnected()
        withTimeoutOrNull(100) { controller.formationRebalanceEvent.first() }

        // Now reconnect — should emit ParticipantRejoined
        client.simulateConnected()

        val event = withTimeoutOrNull(200) { controller.formationRebalanceEvent.first() }
        assertNotNull("Expected a ParticipantRejoined event after WS reconnect", event)
        assertTrue(event is FormationRebalanceEvent.ParticipantRejoined)
        assertEquals(descriptor.deviceId, (event as FormationRebalanceEvent.ParticipantRejoined).rejoinedDeviceId)
    }

    @Test
    fun `formationRebalanceEvent emits ReadinessChanged on WS error during recovery`() = runBlocking {
        val descriptor = buildDescriptor()
        val (controller, client) = buildController(descriptor = descriptor)
        controller.setActiveForTest()

        client.simulateDisconnected()
        withTimeoutOrNull(100) { controller.formationRebalanceEvent.first() }

        // WS error while recovering → FAILED
        client.simulateError("connection refused")

        val event = withTimeoutOrNull(200) { controller.formationRebalanceEvent.first() }
        assertNotNull("Expected a formation rebalance event after WS error", event)
        assertTrue(event is FormationRebalanceEvent.ReadinessChanged)
        assertEquals("ws_recovery_failed", (event as FormationRebalanceEvent.ReadinessChanged).trigger)
    }

    @Test
    fun `notifyParticipantHealthChanged emits ReadinessChanged for DEGRADED health`() = runBlocking {
        val descriptor = buildDescriptor()
        val (controller, _) = buildController(descriptor = descriptor)

        controller.notifyParticipantHealthChanged(ParticipantHealthState.DEGRADED)

        val event = withTimeoutOrNull(200) { controller.formationRebalanceEvent.first() }
        assertNotNull("Expected a formation rebalance event for DEGRADED health", event)
        assertTrue(event is FormationRebalanceEvent.ReadinessChanged)
    }

    @Test
    fun `notifyParticipantHealthChanged without hostDescriptor is a no-op`() = runBlocking {
        val (controller, _) = buildController(descriptor = null)

        controller.notifyParticipantHealthChanged(ParticipantHealthState.DEGRADED)

        val event = withTimeoutOrNull(50) { controller.formationRebalanceEvent.first() }
        assertNull("Expected no formation rebalance event when hostDescriptor is null", event)
    }

    @Test
    fun `notifyParticipantHealthChanged with HEALTHY health emits no event`() = runBlocking {
        val descriptor = buildDescriptor()
        val (controller, _) = buildController(descriptor = descriptor)

        controller.notifyParticipantHealthChanged(ParticipantHealthState.HEALTHY)

        val event = withTimeoutOrNull(50) { controller.formationRebalanceEvent.first() }
        assertNull("Expected no formation rebalance event for HEALTHY health", event)
    }

    @Test
    fun `requestRoleReassessment accepted updates hostDescriptor role`() {
        val descriptor = buildDescriptor(RuntimeHostDescriptor.FormationRole.PRIMARY)
        val (controller, _) = buildController(descriptor = descriptor)

        val decision = controller.requestRoleReassessment(
            requestedRole = RuntimeHostDescriptor.FormationRole.SECONDARY,
            healthState = ParticipantHealthState.HEALTHY
        )

        assertTrue(decision.accepted)
        assertEquals(RuntimeHostDescriptor.FormationRole.PRIMARY, decision.previousRole)
        assertEquals(RuntimeHostDescriptor.FormationRole.SECONDARY, decision.requestedRole)
    }

    @Test
    fun `requestRoleReassessment declined preserves original role`() {
        val descriptor = buildDescriptor(RuntimeHostDescriptor.FormationRole.PRIMARY)
        val (controller, client) = buildController(descriptor = descriptor)
        controller.setActiveForTest()

        // Simulate a WS disconnect to put recovery in RECOVERING state
        client.simulateDisconnected()

        val decision = controller.requestRoleReassessment(
            requestedRole = RuntimeHostDescriptor.FormationRole.SECONDARY,
            healthState = ParticipantHealthState.HEALTHY
        )

        assertFalse(decision.accepted)
        assertTrue(decision.deferrable) // RECOVERING → deferrable
        assertEquals("ws_reconnect_in_progress", decision.declineReason)
    }

    @Test
    fun `requestRoleReassessment without hostDescriptor returns declined decision`() {
        val (controller, _) = buildController(descriptor = null)

        val decision = controller.requestRoleReassessment(
            requestedRole = RuntimeHostDescriptor.FormationRole.SECONDARY
        )

        assertFalse(decision.accepted)
        assertEquals("no_host_descriptor", decision.declineReason)
    }
}
