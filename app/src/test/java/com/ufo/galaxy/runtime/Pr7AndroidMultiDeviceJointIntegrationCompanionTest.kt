package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-7 (Android companion) — Comprehensive joint integration support test suite.
 *
 * This test suite serves as the **primary automated verification** for the Android
 * participant's companion support for the V2 PR-7 multi-device / takeover /
 * failure-recovery joint integration harness.
 *
 * It validates the end-to-end semantics across all three new PR-7 Android surfaces:
 *  - [MultiDeviceParticipantOrchestrationState] — state derivation and transition coverage
 *  - [CapabilityHonestyGuard] — capability report honesty across all state transitions
 *  - [DelegatedTakeoverRecoveryContract] — takeover interruption and recovery semantics
 *
 * ## Coverage areas
 *
 * ### 1. Participant state transition coverage for multi-device scenarios
 *
 * Validates that every state in the six-state participant orchestration model
 * can be derived from realistic sub-state combinations, and that the transition
 * registry covers the complete set of state changes that the PR-7 joint harness
 * may drive on an Android participant.
 *
 * ### 2. Reconnect / recovery readiness
 *
 * Validates the full reconnect cycle:
 *   CONNECTED → RECONNECTING (WS disconnect) → CONNECTED (WS reconnect success)
 *   CONNECTED → RECONNECTING → DISCONNECTED (reconnect exhausted)
 *
 * And the full health degradation/recovery cycle:
 *   CONNECTED → DEGRADED → RECOVERING → CONNECTED
 *   CONNECTED → CAPABILITY_LIMITED → RECOVERING → CONNECTED
 *
 * ### 3. Degraded capability reporting
 *
 * Validates that capability reports remain honest in every degraded/recovery state,
 * and that the inference capability is not falsely advertised when the participant
 * is in any non-CONNECTED state or when inference status is not ACTIVE.
 *
 * ### 4. Delegated takeover support behavior
 *
 * Validates that the takeover eligibility gate is correctly enforced by the
 * orchestration state, and that the recovery contract specifies correct termination
 * signals for all interruption scenarios.
 *
 * ### 5. Capability honesty during runtime transitions
 *
 * Validates that for every valid state transition, the [CapabilityHonestyGuard]
 * correctly enforces the capability honesty contract: the pre-transition capability
 * set remains honest in the pre-transition state and the post-transition set is
 * appropriate for the post-transition state.
 *
 * ## Test matrix
 *
 * ### State transition coverage — all six states reachable
 *  - CONNECTED state is reachable from sub-states
 *  - DISCONNECTED state is reachable from sub-states
 *  - RECONNECTING state is reachable from sub-states
 *  - DEGRADED state is reachable from sub-states
 *  - RECOVERING state is reachable from sub-states
 *  - CAPABILITY_LIMITED state is reachable from sub-states
 *
 * ### Reconnect cycle coverage
 *  - CONNECTED to RECONNECTING transition is registered
 *  - RECONNECTING to CONNECTED transition is registered (reconnect success)
 *  - RECONNECTING to DISCONNECTED transition is registered (reconnect failure)
 *  - Reconnect success transition emits DeviceReconnected event
 *  - Reconnect failure transition emits DeviceDisconnected event
 *
 * ### Degradation/recovery cycle coverage
 *  - CONNECTED to DEGRADED transition is registered
 *  - DEGRADED to RECOVERING transition is registered
 *  - RECOVERING to CONNECTED transition is registered
 *  - Recovery completion transition emits DeviceHealthChanged event
 *  - CONNECTED to CAPABILITY_LIMITED transition is registered
 *  - CAPABILITY_LIMITED to RECOVERING transition is registered
 *
 * ### Capability honesty across state transitions
 *  - CONNECTED→RECONNECTING: RECONNECTING state blocks all capabilities
 *  - CONNECTED→DEGRADED: DEGRADED state blocks inference capability
 *  - CONNECTED→RECOVERING: RECOVERING state blocks all capabilities
 *  - RECONNECTING→CONNECTED: CONNECTED state allows full capabilities
 *  - RECOVERING→CONNECTED: CONNECTED state allows full capabilities
 *
 * ### Delegated takeover eligibility by state
 *  - Only CONNECTED state allows delegated takeover
 *  - All non-CONNECTED states block delegated takeover
 *
 * ### Capability honesty during degradation — full matrix
 *  - CONNECTED + ACTIVE: full capabilities honest
 *  - CONNECTED + DEGRADED inference: only base capabilities honest
 *  - DEGRADED: only base capabilities honest, inference violation detected
 *  - CAPABILITY_LIMITED: only base capabilities honest, inference violation detected
 *  - RECOVERING: no capabilities honest (any advertisement is violation)
 *  - RECONNECTING: no capabilities honest (any advertisement is violation)
 *  - DISCONNECTED: no capabilities honest
 *
 * ### Takeover recovery — all terminating scenarios require FAILED signal
 *  - connected_to_reconnecting terminating scenario requires RESULT/FAILED
 *  - connected_to_disconnected terminating scenario requires RESULT/FAILED
 *  - connected_to_degraded DRAIN_THEN_TERMINATE scenario requires RESULT/FAILED
 *
 * ### Takeover recovery — no scenario permits resume after reconnect
 *  - no registered scenario has mayResumeAfterReconnect = true
 *
 * ### Joint integration invariants
 *  - VALID_TRANSITION_COUNT matches ALL_WIRE_VALUES structural expectation
 *  - SCENARIO_COUNT matches terminatingScenarios + no-active-takeover total
 *  - all orchestration states appear in at least one valid transition
 */
class Pr7AndroidMultiDeviceJointIntegrationCompanionTest {

    private val S = MultiDeviceParticipantOrchestrationState
    private val G = CapabilityHonestyGuard
    private val C = DelegatedTakeoverRecoveryContract
    private val OS = MultiDeviceParticipantOrchestrationState.OrchestrationState

    // ── State transition coverage — all six states reachable ──────────────────

    @Test
    fun `CONNECTED state is reachable from sub-states`() {
        val record = S.from(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(OS.CONNECTED, record.orchestrationState)
    }

    @Test
    fun `DISCONNECTED state is reachable from sub-states`() {
        val record = S.from(
            healthState = ParticipantHealthState.UNKNOWN,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
        assertEquals(OS.DISCONNECTED, record.orchestrationState)
    }

    @Test
    fun `RECONNECTING state is reachable from sub-states`() {
        val record = S.from(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.RECOVERING,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(OS.RECONNECTING, record.orchestrationState)
    }

    @Test
    fun `DEGRADED state is reachable from sub-states`() {
        val record = S.from(
            healthState = ParticipantHealthState.DEGRADED,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(OS.DEGRADED, record.orchestrationState)
    }

    @Test
    fun `RECOVERING state is reachable from sub-states`() {
        val record = S.from(
            healthState = ParticipantHealthState.RECOVERING,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(OS.RECOVERING, record.orchestrationState)
    }

    @Test
    fun `CAPABILITY_LIMITED state is reachable from sub-states`() {
        val record = S.from(
            healthState = ParticipantHealthState.DEGRADED,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY_WITH_FALLBACK,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(OS.CAPABILITY_LIMITED, record.orchestrationState)
    }

    // ── Reconnect cycle coverage ───────────────────────────────────────────────

    @Test
    fun `CONNECTED to RECONNECTING transition is registered`() {
        assertTrue(S.isValidTransition(OS.CONNECTED, OS.RECONNECTING))
    }

    @Test
    fun `RECONNECTING to CONNECTED transition is registered`() {
        assertTrue(S.isValidTransition(OS.RECONNECTING, OS.CONNECTED))
    }

    @Test
    fun `RECONNECTING to DISCONNECTED transition is registered`() {
        assertTrue(S.isValidTransition(OS.RECONNECTING, OS.DISCONNECTED))
    }

    @Test
    fun `reconnect success transition emits DeviceReconnected`() {
        val t = S.transitionsFrom(OS.RECONNECTING)
            .first { it.toState == OS.CONNECTED }
        assertEquals(V2MultiDeviceLifecycleEvent.WIRE_DEVICE_RECONNECTED, t.v2EventEmitted)
    }

    @Test
    fun `reconnect failure transition emits DeviceDisconnected`() {
        val t = S.transitionsFrom(OS.RECONNECTING)
            .first { it.toState == OS.DISCONNECTED }
        assertEquals(V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DISCONNECTED, t.v2EventEmitted)
    }

    // ── Degradation/recovery cycle coverage ────────────────────────────────────

    @Test
    fun `CONNECTED to DEGRADED transition is registered`() {
        assertTrue(S.isValidTransition(OS.CONNECTED, OS.DEGRADED))
    }

    @Test
    fun `DEGRADED to RECOVERING transition is registered`() {
        assertTrue(S.isValidTransition(OS.DEGRADED, OS.RECOVERING))
    }

    @Test
    fun `RECOVERING to CONNECTED transition is registered`() {
        assertTrue(S.isValidTransition(OS.RECOVERING, OS.CONNECTED))
    }

    @Test
    fun `recovery completion transition emits DeviceHealthChanged`() {
        val t = S.transitionsFrom(OS.RECOVERING)
            .first { it.toState == OS.CONNECTED }
        assertEquals(V2MultiDeviceLifecycleEvent.WIRE_DEVICE_HEALTH_CHANGED, t.v2EventEmitted)
    }

    @Test
    fun `CONNECTED to CAPABILITY_LIMITED transition is registered`() {
        assertTrue(S.isValidTransition(OS.CONNECTED, OS.CAPABILITY_LIMITED))
    }

    @Test
    fun `CAPABILITY_LIMITED to RECOVERING transition is registered`() {
        assertTrue(S.isValidTransition(OS.CAPABILITY_LIMITED, OS.RECOVERING))
    }

    // ── Capability honesty across state transitions ────────────────────────────

    @Test
    fun `RECONNECTING state blocks all capabilities`() {
        val allowed = G.computeAllowedCapabilities(OS.RECONNECTING, LocalIntelligenceCapabilityStatus.ACTIVE)
        assertTrue("RECONNECTING must allow no capabilities", allowed.isEmpty())
    }

    @Test
    fun `DEGRADED state blocks inference capability`() {
        val allowed = G.computeAllowedCapabilities(OS.DEGRADED, LocalIntelligenceCapabilityStatus.ACTIVE)
        assertFalse("DEGRADED must not allow inference", allowed.contains(G.CAPABILITY_LOCAL_MODEL_INFERENCE))
        assertTrue("DEGRADED must still allow base capabilities", allowed.containsAll(G.BASE_CAPABILITIES))
    }

    @Test
    fun `RECOVERING state blocks all capabilities`() {
        val allowed = G.computeAllowedCapabilities(OS.RECOVERING, LocalIntelligenceCapabilityStatus.ACTIVE)
        assertTrue("RECOVERING must allow no capabilities", allowed.isEmpty())
    }

    @Test
    fun `CONNECTED state after reconnect allows full capabilities`() {
        val allowed = G.computeAllowedCapabilities(OS.CONNECTED, LocalIntelligenceCapabilityStatus.ACTIVE)
        assertTrue("After reconnect CONNECTED must allow full capabilities",
            allowed.contains(G.CAPABILITY_LOCAL_MODEL_INFERENCE))
        assertTrue(allowed.containsAll(G.BASE_CAPABILITIES))
    }

    @Test
    fun `CONNECTED state after recovery allows full capabilities`() {
        val allowed = G.computeAllowedCapabilities(OS.CONNECTED, LocalIntelligenceCapabilityStatus.ACTIVE)
        assertTrue("After health recovery CONNECTED must allow full capabilities",
            allowed.contains(G.CAPABILITY_LOCAL_MODEL_INFERENCE))
    }

    // ── Delegated takeover eligibility by state ───────────────────────────────

    @Test
    fun `only CONNECTED state allows delegated takeover`() {
        val connectedRecord = S.from(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertTrue("CONNECTED must allow delegated takeover", S.eligibleForDelegatedTakeover(connectedRecord))
    }

    @Test
    fun `all non-CONNECTED states block delegated takeover`() {
        val nonConnectedRecords = listOf(
            S.from(ParticipantHealthState.UNKNOWN, ReconnectRecoveryState.IDLE,
                ParticipantReadinessState.NOT_READY, RuntimeHostDescriptor.HostParticipationState.INACTIVE),
            S.from(ParticipantHealthState.HEALTHY, ReconnectRecoveryState.RECOVERING,
                ParticipantReadinessState.NOT_READY, RuntimeHostDescriptor.HostParticipationState.ACTIVE),
            S.from(ParticipantHealthState.DEGRADED, ReconnectRecoveryState.IDLE,
                ParticipantReadinessState.READY, RuntimeHostDescriptor.HostParticipationState.ACTIVE),
            S.from(ParticipantHealthState.RECOVERING, ReconnectRecoveryState.IDLE,
                ParticipantReadinessState.NOT_READY, RuntimeHostDescriptor.HostParticipationState.ACTIVE),
            S.from(ParticipantHealthState.DEGRADED, ReconnectRecoveryState.IDLE,
                ParticipantReadinessState.READY_WITH_FALLBACK, RuntimeHostDescriptor.HostParticipationState.ACTIVE)
        )
        nonConnectedRecords.forEach { record ->
            assertFalse(
                "State ${record.orchestrationState} must not allow delegated takeover",
                S.eligibleForDelegatedTakeover(record)
            )
        }
    }

    // ── Capability honesty during degradation — full matrix ───────────────────

    @Test
    fun `CONNECTED ACTIVE full capabilities report is honest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = G.BASE_CAPABILITIES + G.CAPABILITY_LOCAL_MODEL_INFERENCE,
            orchestrationState = OS.CONNECTED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        assertTrue("CONNECTED+ACTIVE full capability must be honest", G.isHonest(report).isEmpty())
    }

    @Test
    fun `CONNECTED DEGRADED inference base-only report is honest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = G.BASE_CAPABILITIES,
            orchestrationState = OS.CONNECTED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DEGRADED
        )
        assertTrue(G.isHonest(report).isEmpty())
    }

    @Test
    fun `CONNECTED DEGRADED inference with inference advertised is dishonest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = G.BASE_CAPABILITIES + G.CAPABILITY_LOCAL_MODEL_INFERENCE,
            orchestrationState = OS.CONNECTED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DEGRADED
        )
        assertFalse("CONNECTED+DEGRADED inference advertising inference must be dishonest",
            G.isHonest(report).isEmpty())
    }

    @Test
    fun `DEGRADED state with base only is honest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = G.BASE_CAPABILITIES,
            orchestrationState = OS.DEGRADED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        assertTrue(G.isHonest(report).isEmpty())
    }

    @Test
    fun `DEGRADED state advertising inference is dishonest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = G.BASE_CAPABILITIES + G.CAPABILITY_LOCAL_MODEL_INFERENCE,
            orchestrationState = OS.DEGRADED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        assertFalse(G.isHonest(report).isEmpty())
    }

    @Test
    fun `RECOVERING state with no capabilities is honest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = emptySet(),
            orchestrationState = OS.RECOVERING,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        assertTrue(G.isHonest(report).isEmpty())
    }

    @Test
    fun `RECOVERING state advertising any capability is dishonest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = setOf("autonomous_goal_execution"),
            orchestrationState = OS.RECOVERING,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        assertFalse(G.isHonest(report).isEmpty())
    }

    @Test
    fun `RECONNECTING state with no capabilities is honest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = emptySet(),
            orchestrationState = OS.RECONNECTING,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        assertTrue(G.isHonest(report).isEmpty())
    }

    @Test
    fun `DISCONNECTED state with no capabilities is honest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = emptySet(),
            orchestrationState = OS.DISCONNECTED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DISABLED
        )
        assertTrue(G.isHonest(report).isEmpty())
    }

    // ── Takeover recovery — terminating scenarios require FAILED signal ────────

    @Test
    fun `connected_to_reconnecting terminating scenario requires RESULT_FAILED signal`() {
        val s = C.scenarioFor(OS.CONNECTED, OS.RECONNECTING)!!
        assertEquals(
            DelegatedTakeoverRecoveryContract.TakeoverInterruptionOutcome.TERMINATED_WITH_SIGNAL,
            s.interruptionOutcome
        )
        assertEquals(DelegatedExecutionSignal.Kind.RESULT.wireValue, s.requiredSignalKind)
        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED.wireValue, s.requiredResultKind)
    }

    @Test
    fun `connected_to_disconnected terminating scenario requires RESULT_FAILED signal`() {
        val s = C.scenarioFor(OS.CONNECTED, OS.DISCONNECTED)!!
        assertEquals(
            DelegatedTakeoverRecoveryContract.TakeoverInterruptionOutcome.TERMINATED_WITH_SIGNAL,
            s.interruptionOutcome
        )
        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED.wireValue, s.requiredResultKind)
    }

    @Test
    fun `connected_to_degraded DRAIN_THEN_TERMINATE scenario requires RESULT_FAILED signal`() {
        val s = C.scenarioFor(OS.CONNECTED, OS.DEGRADED)!!
        assertEquals(
            DelegatedTakeoverRecoveryContract.TakeoverInterruptionOutcome.DRAIN_THEN_TERMINATE,
            s.interruptionOutcome
        )
        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED.wireValue, s.requiredResultKind)
    }

    // ── Takeover recovery — no scenario permits resume after reconnect ─────────

    @Test
    fun `no registered recovery scenario has mayResumeAfterReconnect true`() {
        val anyResumeAllowed = C.ALL_SCENARIOS.any { it.mayResumeAfterReconnect }
        assertFalse(
            "No scenario must permit resuming a delegated takeover after reconnect without V2 re-authorization",
            anyResumeAllowed
        )
    }

    // ── Joint integration invariants ───────────────────────────────────────────

    @Test
    fun `all orchestration states appear in at least one valid transition`() {
        val statesInTransitions = (
            S.VALID_TRANSITIONS.map { it.fromState } +
                S.VALID_TRANSITIONS.map { it.toState }
            ).toSet()

        OS.entries.forEach { state ->
            assertTrue(
                "OrchestrationState.$state must appear in at least one valid transition",
                statesInTransitions.contains(state)
            )
        }
    }

    @Test
    fun `SCENARIO_COUNT matches terminatingScenarios plus no-active-takeover total`() {
        val terminatingCount = C.terminatingScenarios.size
        val noActiveCount = C.ALL_SCENARIOS.count {
            it.interruptionOutcome == DelegatedTakeoverRecoveryContract.TakeoverInterruptionOutcome.NO_ACTIVE_TAKEOVER
        }
        assertEquals(
            "terminatingScenarios + no_active_takeover must equal SCENARIO_COUNT",
            C.SCENARIO_COUNT,
            terminatingCount + noActiveCount
        )
    }

    @Test
    fun `capability guard BASE_CAPABILITIES are subset of CONNECTED allowed capabilities`() {
        val allowed = G.computeAllowedCapabilities(OS.CONNECTED, LocalIntelligenceCapabilityStatus.ACTIVE)
        assertTrue(
            "All BASE_CAPABILITIES must be in CONNECTED allowed set",
            allowed.containsAll(G.BASE_CAPABILITIES)
        )
    }

    @Test
    fun `every transition that degrades emits a DeviceDegraded or DeviceDisconnected event`() {
        val degradingStates = setOf(OS.DEGRADED, OS.RECONNECTING, OS.RECOVERING, OS.CAPABILITY_LIMITED)
        S.VALID_TRANSITIONS
            .filter { it.toState in degradingStates }
            .forEach { t ->
                val ev = t.v2EventEmitted
                assertNotNull(
                    "Transition ${t.fromState} → ${t.toState} must emit a V2 event",
                    ev
                )
                assertTrue(
                    "Transition ${t.fromState} → ${t.toState} must emit DeviceDegraded or DeviceDisconnected",
                    ev == V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DEGRADED ||
                        ev == V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DISCONNECTED
                )
            }
    }
}
