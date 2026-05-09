package com.ufo.galaxy.runtime

import com.ufo.galaxy.session.DurableParticipantIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-79 (Android) — Semantic contract closure for Android-side producer semantics.
 *
 * Validates both new PR-79 surfaces:
 *  1. [LocalExecutionModeGate] — execution mode state/transition semantics stability
 *  2. [DurableParticipantIdentity] — durable participant identity freshness semantics
 *
 * ## Purpose (R7 / R11)
 *
 * These tests serve as **regression-protection** for the canonical V2 consumption paths:
 *  - `android_runtime_transition_reducer.py` reads [LocalExecutionModeGate] wire values.
 *  - `unified_governance_semantics.py` reads [DurableParticipantIdentity] freshness.
 *  - `android_device_state_store.py` reads [DurableParticipantIdentity.toWireMap].
 *  - `unified/capability_resolver.py` reads mode-gate `acceptsCrossDeviceTasks`.
 *
 * ## Test matrix
 *
 * ### INTRODUCED_PR
 *  - LocalExecutionModeGate.INTRODUCED_PR is 79
 *  - SCHEMA_VERSION constants are "1.0"
 *
 * ### ExecutionModeState — wire value stability
 *  - INACTIVE wireValue is "inactive"
 *  - LOCAL_ONLY wireValue is "local_only"
 *  - CROSS_DEVICE_ACTIVE wireValue is "cross_device_active"
 *  - CROSS_DEVICE_DEGRADED wireValue is "cross_device_degraded"
 *  - TRANSITIONING wireValue is "transitioning"
 *  - ALL_WIRE_VALUES has exactly five entries
 *  - all wire values are distinct
 *  - fromWireValue round-trips all five values
 *  - fromWireValue returns null for unknown
 *
 * ### ExecutionModeState — task acceptance semantics
 *  - INACTIVE does not accept cross-device tasks
 *  - LOCAL_ONLY does not accept cross-device tasks
 *  - TRANSITIONING does not accept cross-device tasks
 *  - CROSS_DEVICE_ACTIVE accepts cross-device tasks
 *  - CROSS_DEVICE_DEGRADED accepts cross-device tasks
 *
 * ### ExecutionModeState — V2 governance semantics
 *  - CROSS_DEVICE_ACTIVE v2GovernanceActive is true
 *  - CROSS_DEVICE_DEGRADED v2GovernanceActive is true
 *  - INACTIVE v2GovernanceActive is false
 *  - LOCAL_ONLY v2GovernanceActive is false
 *  - TRANSITIONING v2GovernanceActive is false
 *
 * ### ModeTransitionEvent — wire value stability
 *  - all eight events have non-blank wire values
 *  - ALL_WIRE_VALUES has exactly eight entries
 *  - all event wire values are distinct
 *  - fromWireValue round-trips all eight values
 *  - fromWireValue returns null for unknown
 *
 * ### ModeTransitionEvent — hold semantics
 *  - CROSS_DEVICE_ENABLED causesHold is true
 *  - WS_DISCONNECTED causesHold is true
 *  - RUNTIME_STARTED causesHold is true
 *  - CROSS_DEVICE_DISABLED causesHold is false
 *  - WS_CONNECTED causesHold is false
 *  - CAPABILITY_DEGRADED causesHold is false
 *  - CAPABILITY_RESTORED causesHold is false
 *  - RUNTIME_STOPPED causesHold is false
 *
 * ### decide() — state derivation
 *  - inactive when runtimeActive=false
 *  - LOCAL_ONLY when cross_device=false
 *  - TRANSITIONING when cross_device=true and ws_connected=false
 *  - CROSS_DEVICE_ACTIVE when cross_device=true and ws_connected=true and not degraded
 *  - CROSS_DEVICE_DEGRADED when cross_device=true and ws_connected=true and degraded
 *
 * ### decide() — transitioningTo semantics
 *  - TRANSITIONING to CROSS_DEVICE_ACTIVE when not degraded
 *  - TRANSITIONING to CROSS_DEVICE_DEGRADED when degraded
 *  - null transitioningTo when not TRANSITIONING
 *
 * ### ModeGateDecision.toWireMap()
 *  - contains execution_mode_state key
 *  - contains schema_version key
 *  - contains accepts_cross_device_tasks key
 *  - contains v2_governance_active key
 *  - contains is_hold_state key
 *  - triggering_event present when triggeringEvent provided
 *  - triggering_event absent when triggeringEvent is null
 *  - transitioning_to present when TRANSITIONING
 *
 * ### GATE_INVARIANTS
 *  - all invariant values are true
 *
 * ### TRANSITION_TABLE
 *  - transition table is non-empty
 *  - all registered transitions have valid from/to/trigger references
 *  - isRegisteredTransition returns true for all registered entries
 *  - isRegisteredTransition returns false for unknown transition
 *
 * ### DurableParticipantIdentity — wire value stability
 *  - FRESH wireValue is "fresh"
 *  - RECOVERED wireValue is "recovered"
 *  - STALE wireValue is "stale"
 *  - all freshness wire values are distinct
 *  - fromWireValue round-trips all three values
 *  - fromWireValue returns null for unknown
 *
 * ### DurableParticipantIdentity.createFresh
 *  - creates identity with FRESH freshness
 *  - generates non-blank participantId
 *  - isCurrent is true for FRESH
 *  - isRecovered is false for FRESH
 *  - offlineGapMs is 0 for fresh registration
 *
 * ### DurableParticipantIdentity.createRecovered
 *  - creates identity with RECOVERED freshness
 *  - preserves existingParticipantId
 *  - isCurrent is true for RECOVERED
 *  - isRecovered is true for RECOVERED
 *
 * ### DurableParticipantIdentity.createFromGap
 *  - null existingParticipantId → FRESH
 *  - short offline gap → RECOVERED, preserves participantId
 *  - long offline gap >= STALE_THRESHOLD → STALE, rotates participantId
 *
 * ### DurableParticipantIdentity — stale semantics
 *  - STALE isCurrent is false
 *  - STALE isRecovered is false
 *
 * ### DurableParticipantIdentity.toWireMap()
 *  - all REQUIRED_WIRE_KEYS present
 *  - KEY_DURABLE_SESSION_ID present when durableSessionId non-null
 *  - KEY_DURABLE_SESSION_ID absent when durableSessionId is null
 *  - schema_version is "1.0"
 *  - freshness wire value matches enum wireValue
 *
 * ### DurableParticipantIdentity.touch
 *  - touch updates lastActiveEpochMs
 *  - touch does not change participantId or other fields
 *
 * ### Cross-surface: mode gate + participant identity
 *  - LOCAL_ONLY mode with FRESH identity: isCurrent=true, acceptsCrossDeviceTasks=false
 *  - CROSS_DEVICE_ACTIVE mode with STALE identity: isCurrent=false, acceptsCrossDeviceTasks=true
 */
class Pr79AndroidSemanticContractClosureTest {

    // ── INTRODUCED_PR + schema versions ──────────────────────────────────────

    @Test
    fun `LocalExecutionModeGate INTRODUCED_PR is 79`() {
        assertEquals(79, LocalExecutionModeGate.INTRODUCED_PR)
    }

    @Test
    fun `LocalExecutionModeGate SCHEMA_VERSION is 1_0`() {
        assertEquals("1.0", LocalExecutionModeGate.SCHEMA_VERSION)
    }

    @Test
    fun `DurableParticipantIdentity SCHEMA_VERSION is 1_0`() {
        assertEquals("1.0", DurableParticipantIdentity.SCHEMA_VERSION)
    }

    // ── ExecutionModeState — wire value stability ─────────────────────────────

    @Test
    fun `INACTIVE wireValue is inactive`() {
        assertEquals("inactive", LocalExecutionModeGate.ExecutionModeState.INACTIVE.wireValue)
    }

    @Test
    fun `LOCAL_ONLY wireValue is local_only`() {
        assertEquals("local_only", LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.wireValue)
    }

    @Test
    fun `CROSS_DEVICE_ACTIVE wireValue is cross_device_active`() {
        assertEquals(
            "cross_device_active",
            LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_ACTIVE.wireValue
        )
    }

    @Test
    fun `CROSS_DEVICE_DEGRADED wireValue is cross_device_degraded`() {
        assertEquals(
            "cross_device_degraded",
            LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_DEGRADED.wireValue
        )
    }

    @Test
    fun `TRANSITIONING wireValue is transitioning`() {
        assertEquals(
            "transitioning",
            LocalExecutionModeGate.ExecutionModeState.TRANSITIONING.wireValue
        )
    }

    @Test
    fun `ExecutionModeState ALL_WIRE_VALUES has exactly five entries`() {
        assertEquals(5, LocalExecutionModeGate.ExecutionModeState.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `all ExecutionModeState wire values are distinct`() {
        val wireValues = LocalExecutionModeGate.ExecutionModeState.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    @Test
    fun `ExecutionModeState fromWireValue round-trips all five values`() {
        for (state in LocalExecutionModeGate.ExecutionModeState.entries) {
            assertEquals(
                "fromWireValue should round-trip ${state.wireValue}",
                state,
                LocalExecutionModeGate.ExecutionModeState.fromWireValue(state.wireValue)
            )
        }
    }

    @Test
    fun `ExecutionModeState fromWireValue returns null for unknown`() {
        assertNull(LocalExecutionModeGate.ExecutionModeState.fromWireValue("unknown_mode_xyz"))
    }

    // ── ExecutionModeState — task acceptance semantics ────────────────────────

    @Test
    fun `INACTIVE does not accept cross-device tasks`() {
        assertFalse(LocalExecutionModeGate.ExecutionModeState.INACTIVE.acceptsCrossDeviceTasks)
    }

    @Test
    fun `LOCAL_ONLY does not accept cross-device tasks`() {
        assertFalse(LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.acceptsCrossDeviceTasks)
    }

    @Test
    fun `TRANSITIONING does not accept cross-device tasks`() {
        assertFalse(LocalExecutionModeGate.ExecutionModeState.TRANSITIONING.acceptsCrossDeviceTasks)
    }

    @Test
    fun `capabilityMetadataSemanticsFor projects TRANSITIONING to degraded cross-device hold`() {
        val semantics = LocalExecutionModeGate.capabilityMetadataSemanticsFor(
            LocalExecutionModeGate.ExecutionModeState.TRANSITIONING
        )

        assertEquals("cross_device", semantics.modeState)
        assertEquals("degraded", semantics.modeReadinessState)
        assertFalse(semantics.acceptsCrossDeviceTasks)
        assertFalse(semantics.v2GovernanceActive)
        assertTrue(semantics.isHoldState)
    }

    @Test
    fun `capabilityMetadataSemanticsFor projects LOCAL_ONLY to degraded local-only semantics`() {
        val semantics = LocalExecutionModeGate.capabilityMetadataSemanticsFor(
            LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY
        )

        assertEquals("local_only", semantics.modeState)
        assertEquals("degraded", semantics.modeReadinessState)
        assertFalse(semantics.acceptsCrossDeviceTasks)
        assertFalse(semantics.v2GovernanceActive)
        assertFalse(semantics.isHoldState)
    }

    @Test
    fun `CROSS_DEVICE_ACTIVE accepts cross-device tasks`() {
        assertTrue(LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_ACTIVE.acceptsCrossDeviceTasks)
    }

    @Test
    fun `CROSS_DEVICE_DEGRADED accepts cross-device tasks`() {
        assertTrue(LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_DEGRADED.acceptsCrossDeviceTasks)
    }

    // ── ExecutionModeState — V2 governance semantics ──────────────────────────

    @Test
    fun `CROSS_DEVICE_ACTIVE v2GovernanceActive is true`() {
        assertTrue(LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_ACTIVE.v2GovernanceActive)
    }

    @Test
    fun `CROSS_DEVICE_DEGRADED v2GovernanceActive is true`() {
        assertTrue(LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_DEGRADED.v2GovernanceActive)
    }

    @Test
    fun `INACTIVE v2GovernanceActive is false`() {
        assertFalse(LocalExecutionModeGate.ExecutionModeState.INACTIVE.v2GovernanceActive)
    }

    @Test
    fun `LOCAL_ONLY v2GovernanceActive is false`() {
        assertFalse(LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.v2GovernanceActive)
    }

    @Test
    fun `TRANSITIONING v2GovernanceActive is false`() {
        assertFalse(LocalExecutionModeGate.ExecutionModeState.TRANSITIONING.v2GovernanceActive)
    }

    // ── ModeTransitionEvent — wire value stability ────────────────────────────

    @Test
    fun `all ModeTransitionEvent entries have non-blank wire values`() {
        for (event in LocalExecutionModeGate.ModeTransitionEvent.entries) {
            assertTrue(
                "wireValue blank for ${event.name}",
                event.wireValue.isNotBlank()
            )
        }
    }

    @Test
    fun `ModeTransitionEvent ALL_WIRE_VALUES has exactly eight entries`() {
        assertEquals(8, LocalExecutionModeGate.ModeTransitionEvent.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `all ModeTransitionEvent wire values are distinct`() {
        val wireValues = LocalExecutionModeGate.ModeTransitionEvent.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    @Test
    fun `ModeTransitionEvent fromWireValue round-trips all eight values`() {
        for (event in LocalExecutionModeGate.ModeTransitionEvent.entries) {
            assertEquals(
                "fromWireValue should round-trip ${event.wireValue}",
                event,
                LocalExecutionModeGate.ModeTransitionEvent.fromWireValue(event.wireValue)
            )
        }
    }

    @Test
    fun `ModeTransitionEvent fromWireValue returns null for unknown`() {
        assertNull(LocalExecutionModeGate.ModeTransitionEvent.fromWireValue("unknown_event_xyz"))
    }

    // ── ModeTransitionEvent — hold semantics ──────────────────────────────────

    @Test
    fun `CROSS_DEVICE_ENABLED causesHold is true`() {
        assertTrue(LocalExecutionModeGate.ModeTransitionEvent.CROSS_DEVICE_ENABLED.causesHold)
    }

    @Test
    fun `WS_DISCONNECTED causesHold is true`() {
        assertTrue(LocalExecutionModeGate.ModeTransitionEvent.WS_DISCONNECTED.causesHold)
    }

    @Test
    fun `RUNTIME_STARTED causesHold is true`() {
        assertTrue(LocalExecutionModeGate.ModeTransitionEvent.RUNTIME_STARTED.causesHold)
    }

    @Test
    fun `CROSS_DEVICE_DISABLED causesHold is false`() {
        assertFalse(LocalExecutionModeGate.ModeTransitionEvent.CROSS_DEVICE_DISABLED.causesHold)
    }

    @Test
    fun `WS_CONNECTED causesHold is false`() {
        assertFalse(LocalExecutionModeGate.ModeTransitionEvent.WS_CONNECTED.causesHold)
    }

    @Test
    fun `CAPABILITY_DEGRADED causesHold is false`() {
        assertFalse(LocalExecutionModeGate.ModeTransitionEvent.CAPABILITY_DEGRADED.causesHold)
    }

    @Test
    fun `CAPABILITY_RESTORED causesHold is false`() {
        assertFalse(LocalExecutionModeGate.ModeTransitionEvent.CAPABILITY_RESTORED.causesHold)
    }

    @Test
    fun `RUNTIME_STOPPED causesHold is false`() {
        assertFalse(LocalExecutionModeGate.ModeTransitionEvent.RUNTIME_STOPPED.causesHold)
    }

    // ── decide() — state derivation ───────────────────────────────────────────

    @Test
    fun `decide returns INACTIVE when runtimeActive is false`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = false,
            runtimeActive = false
        )
        assertEquals(LocalExecutionModeGate.ExecutionModeState.INACTIVE, decision.state)
    }

    @Test
    fun `decide returns LOCAL_ONLY when cross_device is false`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = false,
            wsConnected = false,
            capabilityDegraded = false
        )
        assertEquals(LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY, decision.state)
    }

    @Test
    fun `decide returns TRANSITIONING when cross_device=true and ws_connected=false`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = false,
            capabilityDegraded = false
        )
        assertEquals(LocalExecutionModeGate.ExecutionModeState.TRANSITIONING, decision.state)
    }

    @Test
    fun `decide returns CROSS_DEVICE_ACTIVE when cross_device=true ws_connected=true not_degraded`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = false
        )
        assertEquals(LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_ACTIVE, decision.state)
    }

    @Test
    fun `decide returns CROSS_DEVICE_DEGRADED when cross_device=true ws_connected=true degraded`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = true,
            degradationReasons = listOf("inference_unavailable")
        )
        assertEquals(LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_DEGRADED, decision.state)
    }

    // ── decide() — transitioningTo semantics ──────────────────────────────────

    @Test
    fun `TRANSITIONING transitioningTo is CROSS_DEVICE_ACTIVE when not degraded`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = false,
            capabilityDegraded = false
        )
        assertEquals(
            LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_ACTIVE,
            decision.transitioningTo
        )
    }

    @Test
    fun `TRANSITIONING transitioningTo is CROSS_DEVICE_DEGRADED when degraded`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = false,
            capabilityDegraded = true
        )
        assertEquals(
            LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_DEGRADED,
            decision.transitioningTo
        )
    }

    @Test
    fun `transitioningTo is null when not TRANSITIONING`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = false
        )
        assertNull(decision.transitioningTo)
    }

    // ── ModeGateDecision.toWireMap() ──────────────────────────────────────────

    @Test
    fun `toWireMap contains execution_mode_state key`() {
        val wireMap = makeActiveDecision().toWireMap()
        assertTrue(wireMap.containsKey(LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE))
    }

    @Test
    fun `toWireMap execution_mode_state value matches state wireValue`() {
        val decision = makeActiveDecision()
        val wireMap = decision.toWireMap()
        assertEquals(
            decision.state.wireValue,
            wireMap[LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE]
        )
    }

    @Test
    fun `toWireMap contains schema_version key`() {
        val wireMap = makeActiveDecision().toWireMap()
        assertTrue(wireMap.containsKey(LocalExecutionModeGate.KEY_SCHEMA_VERSION))
    }

    @Test
    fun `toWireMap schema_version is 1_0`() {
        val wireMap = makeActiveDecision().toWireMap()
        assertEquals("1.0", wireMap[LocalExecutionModeGate.KEY_SCHEMA_VERSION])
    }

    @Test
    fun `toWireMap contains accepts_cross_device_tasks key`() {
        val wireMap = makeActiveDecision().toWireMap()
        assertTrue(wireMap.containsKey(LocalExecutionModeGate.KEY_ACCEPTS_CROSS_DEVICE_TASKS))
    }

    @Test
    fun `toWireMap CROSS_DEVICE_ACTIVE accepts_cross_device_tasks is true`() {
        val wireMap = makeActiveDecision().toWireMap()
        assertEquals(true, wireMap[LocalExecutionModeGate.KEY_ACCEPTS_CROSS_DEVICE_TASKS])
    }

    @Test
    fun `toWireMap LOCAL_ONLY accepts_cross_device_tasks is false`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = false,
            wsConnected = false,
            capabilityDegraded = false
        )
        assertEquals(false, decision.toWireMap()[LocalExecutionModeGate.KEY_ACCEPTS_CROSS_DEVICE_TASKS])
    }

    @Test
    fun `toWireMap contains v2_governance_active key`() {
        val wireMap = makeActiveDecision().toWireMap()
        assertTrue(wireMap.containsKey(LocalExecutionModeGate.KEY_V2_GOVERNANCE_ACTIVE))
    }

    @Test
    fun `toWireMap contains is_hold_state key`() {
        val wireMap = makeActiveDecision().toWireMap()
        assertTrue(wireMap.containsKey(LocalExecutionModeGate.KEY_IS_HOLD_STATE))
    }

    @Test
    fun `toWireMap triggering_event present when triggeringEvent provided`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = false,
            triggeringEvent = LocalExecutionModeGate.ModeTransitionEvent.WS_CONNECTED
        )
        assertTrue(decision.toWireMap().containsKey(LocalExecutionModeGate.KEY_TRIGGERING_EVENT))
        assertEquals("ws_connected", decision.toWireMap()[LocalExecutionModeGate.KEY_TRIGGERING_EVENT])
    }

    @Test
    fun `toWireMap triggering_event absent when triggeringEvent is null`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = false,
            triggeringEvent = null
        )
        assertFalse(decision.toWireMap().containsKey(LocalExecutionModeGate.KEY_TRIGGERING_EVENT))
    }

    @Test
    fun `toWireMap transitioning_to present when TRANSITIONING`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = false,
            capabilityDegraded = false
        )
        assertEquals(
            LocalExecutionModeGate.ExecutionModeState.TRANSITIONING,
            decision.state
        )
        assertTrue(decision.toWireMap().containsKey(LocalExecutionModeGate.KEY_TRANSITIONING_TO))
    }

    @Test
    fun `toWireMap degradation_reasons is empty string when no degradation`() {
        val wireMap = makeActiveDecision().toWireMap()
        assertEquals("", wireMap[LocalExecutionModeGate.KEY_DEGRADATION_REASONS])
    }

    @Test
    fun `toWireMap degradation_reasons contains reasons when degraded`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = true,
            degradationReasons = listOf("inference_unavailable", "model_not_loaded")
        )
        val reasons = decision.toWireMap()[LocalExecutionModeGate.KEY_DEGRADATION_REASONS] as String
        assertTrue(reasons.contains("inference_unavailable"))
        assertTrue(reasons.contains("model_not_loaded"))
    }

    // ── GATE_INVARIANTS ───────────────────────────────────────────────────────

    @Test
    fun `all GATE_INVARIANTS values are true`() {
        for ((key, value) in LocalExecutionModeGate.GATE_INVARIANTS) {
            assertTrue("GATE_INVARIANT '$key' is false", value)
        }
    }

    @Test
    fun `GATE_INVARIANTS is non-empty`() {
        assertTrue(LocalExecutionModeGate.GATE_INVARIANTS.isNotEmpty())
    }

    // ── TRANSITION_TABLE ──────────────────────────────────────────────────────

    @Test
    fun `TRANSITION_TABLE is non-empty`() {
        assertTrue(LocalExecutionModeGate.TRANSITION_TABLE.isNotEmpty())
    }

    @Test
    fun `TRANSITION_TABLE entries have valid from-to-trigger references`() {
        val allStates = LocalExecutionModeGate.ExecutionModeState.entries.toSet()
        val allEvents = LocalExecutionModeGate.ModeTransitionEvent.entries.toSet()
        for (entry in LocalExecutionModeGate.TRANSITION_TABLE) {
            assertTrue("from state ${entry.from} not in entries", entry.from in allStates)
            assertTrue("to state ${entry.to} not in entries", entry.to in allStates)
            assertTrue("trigger ${entry.trigger} not in entries", entry.trigger in allEvents)
        }
    }

    @Test
    fun `isRegisteredTransition returns true for all TRANSITION_TABLE entries`() {
        for (entry in LocalExecutionModeGate.TRANSITION_TABLE) {
            assertTrue(
                "isRegisteredTransition should be true for ${entry.from} → ${entry.to} via ${entry.trigger}",
                LocalExecutionModeGate.isRegisteredTransition(entry.from, entry.to, entry.trigger)
            )
        }
    }

    @Test
    fun `isRegisteredTransition returns false for unknown transition`() {
        assertFalse(
            LocalExecutionModeGate.isRegisteredTransition(
                from = LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY,
                to = LocalExecutionModeGate.ExecutionModeState.INACTIVE,
                trigger = LocalExecutionModeGate.ModeTransitionEvent.WS_CONNECTED
            )
        )
    }

    @Test
    fun `LOCAL_ONLY to TRANSITIONING via CROSS_DEVICE_ENABLED is registered`() {
        assertTrue(
            LocalExecutionModeGate.isRegisteredTransition(
                from = LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY,
                to = LocalExecutionModeGate.ExecutionModeState.TRANSITIONING,
                trigger = LocalExecutionModeGate.ModeTransitionEvent.CROSS_DEVICE_ENABLED
            )
        )
    }

    @Test
    fun `TRANSITIONING to CROSS_DEVICE_ACTIVE via WS_CONNECTED is registered`() {
        assertTrue(
            LocalExecutionModeGate.isRegisteredTransition(
                from = LocalExecutionModeGate.ExecutionModeState.TRANSITIONING,
                to = LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_ACTIVE,
                trigger = LocalExecutionModeGate.ModeTransitionEvent.WS_CONNECTED
            )
        )
    }

    @Test
    fun `CROSS_DEVICE_ACTIVE to CROSS_DEVICE_DEGRADED via CAPABILITY_DEGRADED is registered`() {
        assertTrue(
            LocalExecutionModeGate.isRegisteredTransition(
                from = LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_ACTIVE,
                to = LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_DEGRADED,
                trigger = LocalExecutionModeGate.ModeTransitionEvent.CAPABILITY_DEGRADED
            )
        )
    }

    @Test
    fun `CROSS_DEVICE_DEGRADED to CROSS_DEVICE_ACTIVE via CAPABILITY_RESTORED is registered`() {
        assertTrue(
            LocalExecutionModeGate.isRegisteredTransition(
                from = LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_DEGRADED,
                to = LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_ACTIVE,
                trigger = LocalExecutionModeGate.ModeTransitionEvent.CAPABILITY_RESTORED
            )
        )
    }

    // ── TransitionSemanticClass ───────────────────────────────────────────────

    @Test
    fun `ACTIVATION semantic class wireValue is activation`() {
        assertEquals(
            "activation",
            LocalExecutionModeGate.TransitionSemanticClass.ACTIVATION.wireValue
        )
    }

    @Test
    fun `DEACTIVATION semantic class wireValue is deactivation`() {
        assertEquals(
            "deactivation",
            LocalExecutionModeGate.TransitionSemanticClass.DEACTIVATION.wireValue
        )
    }

    @Test
    fun `all TransitionSemanticClass entries have non-blank wire values`() {
        for (cls in LocalExecutionModeGate.TransitionSemanticClass.entries) {
            assertTrue("wireValue blank for ${cls.name}", cls.wireValue.isNotBlank())
        }
    }

    // ── DurableParticipantIdentity — freshness wire values ────────────────────

    @Test
    fun `FRESH wireValue is fresh`() {
        assertEquals("fresh", DurableParticipantIdentity.IdentityFreshness.FRESH.wireValue)
    }

    @Test
    fun `RECOVERED wireValue is recovered`() {
        assertEquals("recovered", DurableParticipantIdentity.IdentityFreshness.RECOVERED.wireValue)
    }

    @Test
    fun `STALE wireValue is stale`() {
        assertEquals("stale", DurableParticipantIdentity.IdentityFreshness.STALE.wireValue)
    }

    @Test
    fun `all IdentityFreshness wire values are distinct`() {
        val wireValues = DurableParticipantIdentity.IdentityFreshness.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    @Test
    fun `IdentityFreshness fromWireValue round-trips all three values`() {
        for (freshness in DurableParticipantIdentity.IdentityFreshness.entries) {
            assertEquals(
                freshness,
                DurableParticipantIdentity.IdentityFreshness.fromWireValue(freshness.wireValue)
            )
        }
    }

    @Test
    fun `IdentityFreshness fromWireValue returns null for unknown`() {
        assertNull(DurableParticipantIdentity.IdentityFreshness.fromWireValue("unknown_freshness"))
    }

    @Test
    fun `all IdentityFreshness entries have non-blank v2Guidance`() {
        for (freshness in DurableParticipantIdentity.IdentityFreshness.entries) {
            assertTrue(
                "v2Guidance blank for ${freshness.name}",
                freshness.v2Guidance.isNotBlank()
            )
        }
    }

    // ── DurableParticipantIdentity.createFresh ────────────────────────────────

    @Test
    fun `createFresh creates identity with FRESH freshness`() {
        val identity = DurableParticipantIdentity.createFresh(
            deviceId = "device-001",
            deviceRole = "phone"
        )
        assertEquals(DurableParticipantIdentity.IdentityFreshness.FRESH, identity.freshness)
    }

    @Test
    fun `createFresh generates non-blank participantId`() {
        val identity = DurableParticipantIdentity.createFresh(
            deviceId = "device-001",
            deviceRole = "phone"
        )
        assertTrue(identity.participantId.isNotBlank())
    }

    @Test
    fun `createFresh generates unique participantIds`() {
        val a = DurableParticipantIdentity.createFresh("device-001", "phone")
        val b = DurableParticipantIdentity.createFresh("device-001", "phone")
        assertNotEquals(a.participantId, b.participantId)
    }

    @Test
    fun `createFresh isCurrent is true`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        assertTrue(identity.isCurrent)
    }

    @Test
    fun `createFresh isRecovered is false`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        assertFalse(identity.isRecovered)
    }

    @Test
    fun `createFresh offlineGapMs is 0`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        assertEquals(0L, identity.offlineGapMs)
    }

    @Test
    fun `createFresh durableSessionId is null when not provided`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        assertNull(identity.durableSessionId)
    }

    @Test
    fun `createFresh durableSessionId is set when provided`() {
        val identity = DurableParticipantIdentity.createFresh(
            deviceId = "device-001",
            deviceRole = "phone",
            durableSessionId = "era-uuid-001"
        )
        assertEquals("era-uuid-001", identity.durableSessionId)
    }

    // ── DurableParticipantIdentity.createRecovered ────────────────────────────

    @Test
    fun `createRecovered creates identity with RECOVERED freshness`() {
        val record = DurableSessionContinuityRecord.create("reconnect_recovery")
        val identity = DurableParticipantIdentity.createRecovered(
            existingParticipantId = "existing-pid-001",
            deviceId = "device-001",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = System.currentTimeMillis() - 5_000L
        )
        assertEquals(DurableParticipantIdentity.IdentityFreshness.RECOVERED, identity.freshness)
    }

    @Test
    fun `createRecovered preserves existingParticipantId`() {
        val record = DurableSessionContinuityRecord.create("reconnect_recovery")
        val identity = DurableParticipantIdentity.createRecovered(
            existingParticipantId = "existing-pid-001",
            deviceId = "device-001",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = System.currentTimeMillis() - 5_000L
        )
        assertEquals("existing-pid-001", identity.participantId)
    }

    @Test
    fun `createRecovered isCurrent is true`() {
        val record = DurableSessionContinuityRecord.create("reconnect_recovery")
        val identity = DurableParticipantIdentity.createRecovered(
            existingParticipantId = "existing-pid-001",
            deviceId = "device-001",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = System.currentTimeMillis() - 5_000L
        )
        assertTrue(identity.isCurrent)
    }

    @Test
    fun `createRecovered isRecovered is true`() {
        val record = DurableSessionContinuityRecord.create("reconnect_recovery")
        val identity = DurableParticipantIdentity.createRecovered(
            existingParticipantId = "existing-pid-001",
            deviceId = "device-001",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = System.currentTimeMillis() - 5_000L
        )
        assertTrue(identity.isRecovered)
    }

    @Test
    fun `createRecovered links durableSessionId from continuityRecord`() {
        val record = DurableSessionContinuityRecord.create("reconnect_recovery")
        val identity = DurableParticipantIdentity.createRecovered(
            existingParticipantId = "existing-pid-001",
            deviceId = "device-001",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = System.currentTimeMillis() - 5_000L
        )
        assertEquals(record.durableSessionId, identity.durableSessionId)
    }

    // ── DurableParticipantIdentity.createFromGap ──────────────────────────────

    @Test
    fun `createFromGap with null existingParticipantId returns FRESH`() {
        val record = DurableSessionContinuityRecord.create("user_activation")
        val identity = DurableParticipantIdentity.createFromGap(
            existingParticipantId = null,
            deviceId = "device-001",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = System.currentTimeMillis()
        )
        assertEquals(DurableParticipantIdentity.IdentityFreshness.FRESH, identity.freshness)
    }

    @Test
    fun `createFromGap with short gap returns RECOVERED and preserves participantId`() {
        val record = DurableSessionContinuityRecord.create("reconnect_recovery")
        val recentActive = System.currentTimeMillis() - 60_000L // 1 minute ago — well within threshold
        val identity = DurableParticipantIdentity.createFromGap(
            existingParticipantId = "existing-pid-002",
            deviceId = "device-001",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = recentActive
        )
        assertEquals(DurableParticipantIdentity.IdentityFreshness.RECOVERED, identity.freshness)
        assertEquals("existing-pid-002", identity.participantId)
    }

    @Test
    fun `createFromGap with long gap returns STALE and rotates participantId`() {
        val record = DurableSessionContinuityRecord.create("background_restore")
        val staleActive = System.currentTimeMillis() - (DurableParticipantIdentity.STALE_THRESHOLD_MS + 10_000L)
        val identity = DurableParticipantIdentity.createFromGap(
            existingParticipantId = "old-pid-003",
            deviceId = "device-001",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = staleActive
        )
        assertEquals(DurableParticipantIdentity.IdentityFreshness.STALE, identity.freshness)
        assertNotEquals("old-pid-003", identity.participantId)
    }

    // ── DurableParticipantIdentity — stale semantics ──────────────────────────

    @Test
    fun `STALE isCurrent is false`() {
        val identity = buildStaleIdentity()
        assertFalse(identity.isCurrent)
    }

    @Test
    fun `STALE isRecovered is false`() {
        val identity = buildStaleIdentity()
        assertFalse(identity.isRecovered)
    }

    // ── DurableParticipantIdentity.toWireMap() ────────────────────────────────

    @Test
    fun `toWireMap contains all REQUIRED_WIRE_KEYS`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        val wireMap = identity.toWireMap()
        for (key in DurableParticipantIdentity.REQUIRED_WIRE_KEYS) {
            assertTrue("Required wire key '$key' missing from toWireMap()", wireMap.containsKey(key))
        }
    }

    @Test
    fun `toWireMap KEY_DURABLE_SESSION_ID present when durableSessionId non-null`() {
        val identity = DurableParticipantIdentity.createFresh(
            deviceId = "device-001",
            deviceRole = "phone",
            durableSessionId = "era-001"
        )
        assertTrue(identity.toWireMap().containsKey(DurableParticipantIdentity.KEY_DURABLE_SESSION_ID))
    }

    @Test
    fun `toWireMap KEY_DURABLE_SESSION_ID absent when durableSessionId is null`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        assertFalse(identity.toWireMap().containsKey(DurableParticipantIdentity.KEY_DURABLE_SESSION_ID))
    }

    @Test
    fun `toWireMap schema_version is 1_0`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        assertEquals("1.0", identity.toWireMap()[DurableParticipantIdentity.KEY_SCHEMA_VERSION])
    }

    @Test
    fun `toWireMap freshness wire value matches enum wireValue`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        assertEquals(
            DurableParticipantIdentity.IdentityFreshness.FRESH.wireValue,
            identity.toWireMap()[DurableParticipantIdentity.KEY_IDENTITY_FRESHNESS]
        )
    }

    @Test
    fun `toWireMap participant_id matches participantId field`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        assertEquals(identity.participantId, identity.toWireMap()[DurableParticipantIdentity.KEY_PARTICIPANT_ID])
    }

    @Test
    fun `toWireMap is_current is true for FRESH`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        assertEquals(true, identity.toWireMap()[DurableParticipantIdentity.KEY_IS_CURRENT])
    }

    @Test
    fun `toWireMap is_current is false for STALE`() {
        val identity = buildStaleIdentity()
        assertEquals(false, identity.toWireMap()[DurableParticipantIdentity.KEY_IS_CURRENT])
    }

    @Test
    fun `toWireMap is_recovered is false for FRESH`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        assertEquals(false, identity.toWireMap()[DurableParticipantIdentity.KEY_IS_RECOVERED])
    }

    // ── DurableParticipantIdentity.touch ──────────────────────────────────────

    @Test
    fun `touch updates lastActiveEpochMs`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        val newTimestamp = System.currentTimeMillis() + 60_000L
        val touched = DurableParticipantIdentity.touch(identity, newTimestamp)
        assertEquals(newTimestamp, touched.lastActiveEpochMs)
    }

    @Test
    fun `touch does not change participantId`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        val touched = DurableParticipantIdentity.touch(identity, System.currentTimeMillis())
        assertEquals(identity.participantId, touched.participantId)
    }

    @Test
    fun `touch does not change freshness`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        val touched = DurableParticipantIdentity.touch(identity, System.currentTimeMillis())
        assertEquals(identity.freshness, touched.freshness)
    }

    @Test
    fun `touch does not change deviceId or deviceRole`() {
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        val touched = DurableParticipantIdentity.touch(identity, System.currentTimeMillis())
        assertEquals(identity.deviceId, touched.deviceId)
        assertEquals(identity.deviceRole, touched.deviceRole)
    }

    // ── STALE_THRESHOLD_MS contract ───────────────────────────────────────────

    @Test
    fun `STALE_THRESHOLD_MS is 24 hours`() {
        assertEquals(86_400_000L, DurableParticipantIdentity.STALE_THRESHOLD_MS)
    }

    // ── Cross-surface: mode gate + participant identity ────────────────────────

    @Test
    fun `LOCAL_ONLY mode with FRESH identity is valid non-participant state`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = false,
            wsConnected = false,
            capabilityDegraded = false
        )
        val identity = DurableParticipantIdentity.createFresh("device-001", "phone")
        // isCurrent=true but acceptsCrossDeviceTasks=false: Android is live but not cross-device
        assertTrue(identity.isCurrent)
        assertFalse(decision.acceptsCrossDeviceTasks)
    }

    @Test
    fun `CROSS_DEVICE_ACTIVE mode with STALE identity signals V2 to re-validate`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = false
        )
        val identity = buildStaleIdentity()
        // acceptsCrossDeviceTasks=true but isCurrent=false: V2 must re-validate before dispatching
        assertTrue(decision.acceptsCrossDeviceTasks)
        assertFalse(identity.isCurrent)
        // V2 must not dispatch with a stale identity even if mode says it's active
    }

    @Test
    fun `CROSS_DEVICE_ACTIVE mode with RECOVERED identity is canonical dispatch state`() {
        val record = DurableSessionContinuityRecord.create("reconnect_recovery")
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = false
        )
        val identity = DurableParticipantIdentity.createRecovered(
            existingParticipantId = "pid-003",
            deviceId = "device-001",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = System.currentTimeMillis() - 2_000L
        )
        // Both conditions met for safe dispatch
        assertTrue(decision.acceptsCrossDeviceTasks)
        assertTrue(identity.isCurrent)
        assertTrue(identity.isRecovered)
    }

    // ── ALL_WIRE_KEYS completeness ────────────────────────────────────────────

    @Test
    fun `LocalExecutionModeGate ALL_WIRE_KEYS is non-empty`() {
        assertTrue(LocalExecutionModeGate.ALL_WIRE_KEYS.isNotEmpty())
    }

    @Test
    fun `DurableParticipantIdentity REQUIRED_WIRE_KEYS is non-empty`() {
        assertTrue(DurableParticipantIdentity.REQUIRED_WIRE_KEYS.isNotEmpty())
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun makeActiveDecision(): LocalExecutionModeGate.ModeGateDecision =
        LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = false
        )

    private fun buildStaleIdentity(): DurableParticipantIdentity = DurableParticipantIdentity(
        participantId = "stale-pid-001",
        deviceId = "device-001",
        deviceRole = "phone",
        freshness = DurableParticipantIdentity.IdentityFreshness.STALE,
        durableSessionId = null,
        sessionContinuityEpoch = 0,
        registrationEpochMs = System.currentTimeMillis(),
        lastActiveEpochMs = System.currentTimeMillis() - DurableParticipantIdentity.STALE_THRESHOLD_MS - 1_000L
    )
}
