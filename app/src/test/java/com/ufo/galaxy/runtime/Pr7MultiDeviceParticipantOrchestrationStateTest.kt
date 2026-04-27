package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-7 (Android companion) — Multi-device participant orchestration state surface.
 *
 * Acceptance and regression test suite for [MultiDeviceParticipantOrchestrationState]:
 *
 *  1. [MultiDeviceParticipantOrchestrationState.OrchestrationState] — six orchestration states
 *     with stable wire values and descriptions.
 *
 *  2. [MultiDeviceParticipantOrchestrationState.StateRecord] — construction and field access.
 *
 *  3. [MultiDeviceParticipantOrchestrationState.TransitionEntry] — transition registry entries
 *     with V2 event wire values and trigger strings.
 *
 *  4. [MultiDeviceParticipantOrchestrationState.VALID_TRANSITIONS] — registry count and
 *     structural invariants.
 *
 *  5. [MultiDeviceParticipantOrchestrationState.deriveState] — derivation rules for each
 *     orchestration state from the sub-state combination.
 *
 *  6. [MultiDeviceParticipantOrchestrationState.from] — StateRecord factory.
 *
 *  7. Joint-harness predicates: [eligibleForDelegatedTakeover], [shouldAdvertiseFullCapabilities],
 *     [requiresV2Rebalance].
 *
 *  8. Query helpers: [transitionsFrom], [transitionsTo], [isValidTransition].
 *
 * ## Test matrix
 *
 * ### OrchestrationState — wire values
 *  - CONNECTED wireValue is "connected"
 *  - DISCONNECTED wireValue is "disconnected"
 *  - RECONNECTING wireValue is "reconnecting"
 *  - DEGRADED wireValue is "degraded"
 *  - RECOVERING wireValue is "recovering"
 *  - CAPABILITY_LIMITED wireValue is "capability_limited"
 *  - all six wire values are distinct
 *
 * ### OrchestrationState — descriptions
 *  - all six states have non-blank descriptions
 *
 * ### OrchestrationState — count invariant
 *  - ORCHESTRATION_STATE_COUNT is 6
 *  - OrchestrationState.entries has exactly ORCHESTRATION_STATE_COUNT entries
 *
 * ### TransitionEntry — VALID_TRANSITIONS structural invariants
 *  - VALID_TRANSITION_COUNT is 14
 *  - VALID_TRANSITIONS has exactly VALID_TRANSITION_COUNT entries
 *  - all transitions have non-blank trigger strings
 *  - all transitions have non-blank rationale strings
 *  - all transitions with non-null v2EventEmitted carry a valid V2 wire value
 *
 * ### TransitionEntry — specific transitions exist
 *  - DISCONNECTED → RECONNECTING transition exists
 *  - CONNECTED → RECONNECTING transition exists
 *  - RECONNECTING → CONNECTED transition exists with DeviceReconnected event
 *  - RECONNECTING → DISCONNECTED transition exists
 *  - CONNECTED → DEGRADED transition exists
 *  - CONNECTED → CAPABILITY_LIMITED transition exists
 *  - DEGRADED → RECOVERING transition exists
 *  - RECOVERING → CONNECTED transition exists with DeviceHealthChanged event
 *  - CONNECTED → DISCONNECTED transition exists
 *
 * ### deriveState — state derivation rules
 *  - RECOVERING reconnect state → RECONNECTING
 *  - INACTIVE participation + IDLE reconnect → DISCONNECTED
 *  - RECOVERING health → RECOVERING
 *  - DEGRADED health + READY_WITH_FALLBACK readiness → CAPABILITY_LIMITED
 *  - DEGRADED health + READY readiness → DEGRADED
 *  - HEALTHY + ACTIVE + READY + IDLE → CONNECTED
 *
 * ### from factory — StateRecord construction
 *  - orchestrationState matches derived state
 *  - healthState is preserved
 *  - reconnectState is preserved
 *  - readinessState is preserved
 *  - participationState is preserved
 *
 * ### Joint-harness predicates
 *  - CONNECTED state → eligibleForDelegatedTakeover true
 *  - DISCONNECTED state → eligibleForDelegatedTakeover false
 *  - RECONNECTING state → eligibleForDelegatedTakeover false
 *  - DEGRADED state → eligibleForDelegatedTakeover false
 *  - RECOVERING state → eligibleForDelegatedTakeover false
 *  - CAPABILITY_LIMITED state → eligibleForDelegatedTakeover false
 *  - CONNECTED state → shouldAdvertiseFullCapabilities true
 *  - non-CONNECTED state → shouldAdvertiseFullCapabilities false
 *  - CONNECTED state → requiresV2Rebalance false
 *  - DISCONNECTED state → requiresV2Rebalance true
 *  - RECONNECTING state → requiresV2Rebalance true
 *  - DEGRADED state → requiresV2Rebalance true
 *  - RECOVERING state → requiresV2Rebalance true
 *  - CAPABILITY_LIMITED state → requiresV2Rebalance true
 *
 * ### Query helpers
 *  - transitionsFrom(CONNECTED) is non-empty
 *  - transitionsTo(CONNECTED) is non-empty
 *  - isValidTransition CONNECTED → DEGRADED is true
 *  - isValidTransition CONNECTED → RECOVERING is false (no direct transition)
 *
 * ### Invariant constants
 *  - INTRODUCED_PR is 7
 *  - INTRODUCED_PR_TITLE is non-blank
 */
class Pr7MultiDeviceParticipantOrchestrationStateTest {

    private val S = MultiDeviceParticipantOrchestrationState

    // ── OrchestrationState — wire values ──────────────────────────────────────

    @Test
    fun `CONNECTED wireValue is connected`() {
        assertEquals("connected", S.OrchestrationState.CONNECTED.wireValue)
    }

    @Test
    fun `DISCONNECTED wireValue is disconnected`() {
        assertEquals("disconnected", S.OrchestrationState.DISCONNECTED.wireValue)
    }

    @Test
    fun `RECONNECTING wireValue is reconnecting`() {
        assertEquals("reconnecting", S.OrchestrationState.RECONNECTING.wireValue)
    }

    @Test
    fun `DEGRADED wireValue is degraded`() {
        assertEquals("degraded", S.OrchestrationState.DEGRADED.wireValue)
    }

    @Test
    fun `RECOVERING wireValue is recovering`() {
        assertEquals("recovering", S.OrchestrationState.RECOVERING.wireValue)
    }

    @Test
    fun `CAPABILITY_LIMITED wireValue is capability_limited`() {
        assertEquals("capability_limited", S.OrchestrationState.CAPABILITY_LIMITED.wireValue)
    }

    @Test
    fun `all six wire values are distinct`() {
        val wires = S.OrchestrationState.entries.map { it.wireValue }
        assertEquals(
            "All OrchestrationState wire values must be distinct",
            wires.size,
            wires.toSet().size
        )
    }

    // ── OrchestrationState — descriptions ─────────────────────────────────────

    @Test
    fun `all six states have non-blank descriptions`() {
        S.OrchestrationState.entries.forEach { state ->
            assertTrue(
                "OrchestrationState.${state.name} description must be non-blank",
                state.description.isNotBlank()
            )
        }
    }

    // ── OrchestrationState — count invariant ──────────────────────────────────

    @Test
    fun `ORCHESTRATION_STATE_COUNT is 6`() {
        assertEquals(6, S.ORCHESTRATION_STATE_COUNT)
    }

    @Test
    fun `OrchestrationState entries has exactly ORCHESTRATION_STATE_COUNT entries`() {
        assertEquals(S.ORCHESTRATION_STATE_COUNT, S.OrchestrationState.entries.size)
    }

    // ── VALID_TRANSITIONS — structural invariants ─────────────────────────────

    @Test
    fun `VALID_TRANSITION_COUNT is 14`() {
        assertEquals(14, S.VALID_TRANSITION_COUNT)
    }

    @Test
    fun `VALID_TRANSITIONS has exactly VALID_TRANSITION_COUNT entries`() {
        assertEquals(S.VALID_TRANSITION_COUNT, S.VALID_TRANSITIONS.size)
    }

    @Test
    fun `all transitions have non-blank trigger strings`() {
        S.VALID_TRANSITIONS.forEach { t ->
            assertTrue(
                "Transition ${t.fromState} → ${t.toState} must have non-blank trigger",
                t.trigger.isNotBlank()
            )
        }
    }

    @Test
    fun `all transitions have non-blank rationale strings`() {
        S.VALID_TRANSITIONS.forEach { t ->
            assertTrue(
                "Transition ${t.fromState} → ${t.toState} must have non-blank rationale",
                t.rationale.isNotBlank()
            )
        }
    }

    @Test
    fun `all non-null v2EventEmitted values are valid V2 wire values`() {
        val validWireValues = V2MultiDeviceLifecycleEvent.ALL_WIRE_VALUES
        S.VALID_TRANSITIONS.forEach { t ->
            val ev = t.v2EventEmitted ?: return@forEach
            assertTrue(
                "Transition ${t.fromState} → ${t.toState} v2EventEmitted='$ev' must be in ALL_WIRE_VALUES",
                validWireValues.contains(ev)
            )
        }
    }

    // ── VALID_TRANSITIONS — specific transitions ──────────────────────────────

    @Test
    fun `DISCONNECTED to RECONNECTING transition exists`() {
        assertTrue(
            S.isValidTransition(
                S.OrchestrationState.DISCONNECTED,
                S.OrchestrationState.RECONNECTING
            )
        )
    }

    @Test
    fun `CONNECTED to RECONNECTING transition exists`() {
        assertTrue(
            S.isValidTransition(
                S.OrchestrationState.CONNECTED,
                S.OrchestrationState.RECONNECTING
            )
        )
    }

    @Test
    fun `RECONNECTING to CONNECTED transition exists with DeviceReconnected event`() {
        val t = S.transitionsFrom(S.OrchestrationState.RECONNECTING)
            .firstOrNull { it.toState == S.OrchestrationState.CONNECTED }
        assertNotNull("RECONNECTING → CONNECTED transition must exist", t)
        assertEquals(
            "RECONNECTING → CONNECTED must emit DeviceReconnected",
            V2MultiDeviceLifecycleEvent.WIRE_DEVICE_RECONNECTED,
            t!!.v2EventEmitted
        )
    }

    @Test
    fun `RECONNECTING to DISCONNECTED transition exists`() {
        assertTrue(
            S.isValidTransition(
                S.OrchestrationState.RECONNECTING,
                S.OrchestrationState.DISCONNECTED
            )
        )
    }

    @Test
    fun `CONNECTED to DEGRADED transition exists`() {
        assertTrue(
            S.isValidTransition(
                S.OrchestrationState.CONNECTED,
                S.OrchestrationState.DEGRADED
            )
        )
    }

    @Test
    fun `CONNECTED to CAPABILITY_LIMITED transition exists`() {
        assertTrue(
            S.isValidTransition(
                S.OrchestrationState.CONNECTED,
                S.OrchestrationState.CAPABILITY_LIMITED
            )
        )
    }

    @Test
    fun `DEGRADED to RECOVERING transition exists`() {
        assertTrue(
            S.isValidTransition(
                S.OrchestrationState.DEGRADED,
                S.OrchestrationState.RECOVERING
            )
        )
    }

    @Test
    fun `RECOVERING to CONNECTED transition exists with DeviceHealthChanged event`() {
        val t = S.transitionsFrom(S.OrchestrationState.RECOVERING)
            .firstOrNull { it.toState == S.OrchestrationState.CONNECTED }
        assertNotNull("RECOVERING → CONNECTED transition must exist", t)
        assertEquals(
            "RECOVERING → CONNECTED must emit DeviceHealthChanged",
            V2MultiDeviceLifecycleEvent.WIRE_DEVICE_HEALTH_CHANGED,
            t!!.v2EventEmitted
        )
    }

    @Test
    fun `CONNECTED to DISCONNECTED transition exists`() {
        assertTrue(
            S.isValidTransition(
                S.OrchestrationState.CONNECTED,
                S.OrchestrationState.DISCONNECTED
            )
        )
    }

    // ── deriveState — state derivation rules ──────────────────────────────────

    @Test
    fun `RECOVERING reconnect state derives RECONNECTING`() {
        val derived = S.deriveState(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.RECOVERING,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(S.OrchestrationState.RECONNECTING, derived)
    }

    @Test
    fun `INACTIVE participation and IDLE reconnect derives DISCONNECTED`() {
        val derived = S.deriveState(
            healthState = ParticipantHealthState.UNKNOWN,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
        assertEquals(S.OrchestrationState.DISCONNECTED, derived)
    }

    @Test
    fun `RECOVERING health derives RECOVERING orchestration state`() {
        val derived = S.deriveState(
            healthState = ParticipantHealthState.RECOVERING,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(S.OrchestrationState.RECOVERING, derived)
    }

    @Test
    fun `DEGRADED health and READY_WITH_FALLBACK readiness derives CAPABILITY_LIMITED`() {
        val derived = S.deriveState(
            healthState = ParticipantHealthState.DEGRADED,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY_WITH_FALLBACK,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(S.OrchestrationState.CAPABILITY_LIMITED, derived)
    }

    @Test
    fun `DEGRADED health and READY readiness derives DEGRADED`() {
        val derived = S.deriveState(
            healthState = ParticipantHealthState.DEGRADED,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(S.OrchestrationState.DEGRADED, derived)
    }

    @Test
    fun `HEALTHY ACTIVE READY IDLE derives CONNECTED`() {
        val derived = S.deriveState(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(S.OrchestrationState.CONNECTED, derived)
    }

    // ── from factory — StateRecord construction ────────────────────────────────

    @Test
    fun `from factory orchestrationState matches derived state`() {
        val record = S.from(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(S.OrchestrationState.CONNECTED, record.orchestrationState)
    }

    @Test
    fun `from factory healthState is preserved`() {
        val record = S.from(
            healthState = ParticipantHealthState.DEGRADED,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY_WITH_FALLBACK,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(ParticipantHealthState.DEGRADED, record.healthState)
    }

    @Test
    fun `from factory reconnectState is preserved`() {
        val record = S.from(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.RECOVERING,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(ReconnectRecoveryState.RECOVERING, record.reconnectState)
    }

    @Test
    fun `from factory readinessState is preserved`() {
        val record = S.from(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(ParticipantReadinessState.READY, record.readinessState)
    }

    @Test
    fun `from factory participationState is preserved`() {
        val record = S.from(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
        assertEquals(RuntimeHostDescriptor.HostParticipationState.INACTIVE, record.participationState)
    }

    // ── Joint-harness predicates — eligibleForDelegatedTakeover ───────────────

    @Test
    fun `CONNECTED state eligibleForDelegatedTakeover is true`() {
        val record = connectedRecord()
        assertTrue(S.eligibleForDelegatedTakeover(record))
    }

    @Test
    fun `DISCONNECTED state eligibleForDelegatedTakeover is false`() {
        val record = S.from(
            healthState = ParticipantHealthState.UNKNOWN,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
        assertFalse(S.eligibleForDelegatedTakeover(record))
    }

    @Test
    fun `RECONNECTING state eligibleForDelegatedTakeover is false`() {
        val record = S.from(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.RECOVERING,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertFalse(S.eligibleForDelegatedTakeover(record))
    }

    @Test
    fun `DEGRADED state eligibleForDelegatedTakeover is false`() {
        val record = S.from(
            healthState = ParticipantHealthState.DEGRADED,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertFalse(S.eligibleForDelegatedTakeover(record))
    }

    @Test
    fun `RECOVERING state eligibleForDelegatedTakeover is false`() {
        val record = S.from(
            healthState = ParticipantHealthState.RECOVERING,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertFalse(S.eligibleForDelegatedTakeover(record))
    }

    @Test
    fun `CAPABILITY_LIMITED state eligibleForDelegatedTakeover is false`() {
        val record = S.from(
            healthState = ParticipantHealthState.DEGRADED,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY_WITH_FALLBACK,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertFalse(S.eligibleForDelegatedTakeover(record))
    }

    // ── Joint-harness predicates — shouldAdvertiseFullCapabilities ────────────

    @Test
    fun `CONNECTED state shouldAdvertiseFullCapabilities is true`() {
        assertTrue(S.shouldAdvertiseFullCapabilities(connectedRecord()))
    }

    @Test
    fun `DISCONNECTED state shouldAdvertiseFullCapabilities is false`() {
        val record = disconnectedRecord()
        assertFalse(S.shouldAdvertiseFullCapabilities(record))
    }

    @Test
    fun `RECONNECTING state shouldAdvertiseFullCapabilities is false`() {
        val record = S.from(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.RECOVERING,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertFalse(S.shouldAdvertiseFullCapabilities(record))
    }

    @Test
    fun `DEGRADED state shouldAdvertiseFullCapabilities is false`() {
        val record = S.from(
            healthState = ParticipantHealthState.DEGRADED,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertFalse(S.shouldAdvertiseFullCapabilities(record))
    }

    // ── Joint-harness predicates — requiresV2Rebalance ────────────────────────

    @Test
    fun `CONNECTED state requiresV2Rebalance is false`() {
        assertFalse(S.requiresV2Rebalance(connectedRecord()))
    }

    @Test
    fun `DISCONNECTED state requiresV2Rebalance is true`() {
        assertTrue(S.requiresV2Rebalance(disconnectedRecord()))
    }

    @Test
    fun `RECONNECTING state requiresV2Rebalance is true`() {
        val record = S.from(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.RECOVERING,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertTrue(S.requiresV2Rebalance(record))
    }

    @Test
    fun `DEGRADED state requiresV2Rebalance is true`() {
        val record = S.from(
            healthState = ParticipantHealthState.DEGRADED,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertTrue(S.requiresV2Rebalance(record))
    }

    @Test
    fun `RECOVERING state requiresV2Rebalance is true`() {
        val record = S.from(
            healthState = ParticipantHealthState.RECOVERING,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertTrue(S.requiresV2Rebalance(record))
    }

    @Test
    fun `CAPABILITY_LIMITED state requiresV2Rebalance is true`() {
        val record = S.from(
            healthState = ParticipantHealthState.DEGRADED,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY_WITH_FALLBACK,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertTrue(S.requiresV2Rebalance(record))
    }

    // ── Query helpers ──────────────────────────────────────────────────────────

    @Test
    fun `transitionsFrom CONNECTED is non-empty`() {
        assertTrue(S.transitionsFrom(S.OrchestrationState.CONNECTED).isNotEmpty())
    }

    @Test
    fun `transitionsTo CONNECTED is non-empty`() {
        assertTrue(S.transitionsTo(S.OrchestrationState.CONNECTED).isNotEmpty())
    }

    @Test
    fun `isValidTransition CONNECTED to DEGRADED is true`() {
        assertTrue(
            S.isValidTransition(S.OrchestrationState.CONNECTED, S.OrchestrationState.DEGRADED)
        )
    }

    @Test
    fun `isValidTransition CONNECTED to RECOVERING is false`() {
        assertFalse(
            S.isValidTransition(S.OrchestrationState.CONNECTED, S.OrchestrationState.RECOVERING)
        )
    }

    // ── Invariant constants ───────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 7`() {
        assertEquals(7, S.INTRODUCED_PR)
    }

    @Test
    fun `INTRODUCED_PR_TITLE is non-blank`() {
        assertTrue(S.INTRODUCED_PR_TITLE.isNotBlank())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun connectedRecord(): MultiDeviceParticipantOrchestrationState.StateRecord =
        S.from(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )

    private fun disconnectedRecord(): MultiDeviceParticipantOrchestrationState.StateRecord =
        S.from(
            healthState = ParticipantHealthState.UNKNOWN,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
}
