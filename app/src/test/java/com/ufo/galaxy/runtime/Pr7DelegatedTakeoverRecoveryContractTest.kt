package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-7 (Android companion) — Delegated takeover recovery contract for multi-device scenarios.
 *
 * Acceptance and regression test suite for [DelegatedTakeoverRecoveryContract]:
 *
 *  1. [DelegatedTakeoverRecoveryContract.TakeoverInterruptionOutcome] — four outcomes
 *     with stable wire values.
 *
 *  2. [DelegatedTakeoverRecoveryContract.RecoveryScenario] — construction and field access.
 *
 *  3. [DelegatedTakeoverRecoveryContract.ALL_SCENARIOS] — count invariant and structural
 *     checks.
 *
 *  4. Specific scenarios: connected-to-reconnecting, connected-to-disconnected,
 *     connected-to-degraded with active takeover; recovery scenarios; capability-limited.
 *
 *  5. [DelegatedTakeoverRecoveryContract.terminatingScenarios] — only scenarios that
 *     require terminal signal emission.
 *
 *  6. [DelegatedTakeoverRecoveryContract.noResumeAfterReconnectScenarios] — all scenarios
 *     disallow resume after reconnect.
 *
 *  7. Query helpers: [scenarioFor].
 *
 * ## Test matrix
 *
 * ### TakeoverInterruptionOutcome — wire values
 *  - UNAFFECTED wireValue is "unaffected"
 *  - TERMINATED_WITH_SIGNAL wireValue is "terminated_with_signal"
 *  - DRAIN_THEN_TERMINATE wireValue is "drain_then_terminate"
 *  - NO_ACTIVE_TAKEOVER wireValue is "no_active_takeover"
 *  - all four wire values are distinct
 *
 * ### ALL_SCENARIOS — count invariant
 *  - SCENARIO_COUNT is 7
 *  - ALL_SCENARIOS has exactly SCENARIO_COUNT entries
 *
 * ### ALL_SCENARIOS — structural invariants
 *  - all scenario IDs are non-blank
 *  - all scenario IDs are unique
 *  - all scenarios with TERMINATED_WITH_SIGNAL have non-null requiredSignalKind
 *  - all scenarios with TERMINATED_WITH_SIGNAL have non-null requiredResultKind
 *  - all scenarios with TERMINATED_WITH_SIGNAL have non-null requiredReasonPrefix
 *  - all scenarios have non-blank rationale
 *
 * ### Specific scenarios
 *  - connected_to_reconnecting_with_active_takeover exists
 *  - connected_to_reconnecting requires TERMINATED_WITH_SIGNAL
 *  - connected_to_reconnecting requires RESULT signal kind
 *  - connected_to_reconnecting requires FAILED result kind
 *  - connected_to_reconnecting reason prefix is participant_disconnected
 *  - connected_to_reconnecting mayResumeAfterReconnect is false
 *  - connected_to_disconnected_with_active_takeover exists
 *  - connected_to_disconnected requires TERMINATED_WITH_SIGNAL
 *  - connected_to_disconnected mayResumeAfterReconnect is false
 *  - connected_to_degraded_with_active_takeover exists
 *  - connected_to_degraded requires DRAIN_THEN_TERMINATE
 *  - connected_to_degraded reason prefix is participant_degraded
 *  - reconnecting_to_connected_no_active_takeover exists
 *  - reconnecting_to_connected has NO_ACTIVE_TAKEOVER
 *  - recovering_to_connected_no_active_takeover exists
 *  - recovering_to_connected has NO_ACTIVE_TAKEOVER
 *  - capability_limited_no_active_takeover exists
 *
 * ### terminatingScenarios
 *  - terminatingScenarios is non-empty
 *  - terminatingScenarios includes connected_to_reconnecting
 *  - terminatingScenarios includes connected_to_disconnected
 *  - terminatingScenarios includes connected_to_degraded
 *  - terminatingScenarios does not include reconnecting_to_connected
 *
 * ### noResumeAfterReconnectScenarios
 *  - noResumeAfterReconnectScenarios has exactly SCENARIO_COUNT entries
 *
 * ### Reason prefix constants
 *  - REASON_PARTICIPANT_DISCONNECTED is "participant_disconnected"
 *  - REASON_PARTICIPANT_DEGRADED is "participant_degraded"
 *
 * ### Invariant constants
 *  - INTRODUCED_PR is 7
 *  - INTRODUCED_PR_TITLE is non-blank
 */
class Pr7DelegatedTakeoverRecoveryContractTest {

    private val C = DelegatedTakeoverRecoveryContract
    private val Out = DelegatedTakeoverRecoveryContract.TakeoverInterruptionOutcome
    private val S = MultiDeviceParticipantOrchestrationState.OrchestrationState

    // ── TakeoverInterruptionOutcome — wire values ─────────────────────────────

    @Test
    fun `UNAFFECTED wireValue is unaffected`() {
        assertEquals("unaffected", Out.UNAFFECTED.wireValue)
    }

    @Test
    fun `TERMINATED_WITH_SIGNAL wireValue is terminated_with_signal`() {
        assertEquals("terminated_with_signal", Out.TERMINATED_WITH_SIGNAL.wireValue)
    }

    @Test
    fun `DRAIN_THEN_TERMINATE wireValue is drain_then_terminate`() {
        assertEquals("drain_then_terminate", Out.DRAIN_THEN_TERMINATE.wireValue)
    }

    @Test
    fun `NO_ACTIVE_TAKEOVER wireValue is no_active_takeover`() {
        assertEquals("no_active_takeover", Out.NO_ACTIVE_TAKEOVER.wireValue)
    }

    @Test
    fun `all four TakeoverInterruptionOutcome wire values are distinct`() {
        val wires = Out.entries.map { it.wireValue }
        assertEquals(wires.size, wires.toSet().size)
    }

    // ── ALL_SCENARIOS — count invariant ───────────────────────────────────────

    @Test
    fun `SCENARIO_COUNT is 7`() {
        assertEquals(7, C.SCENARIO_COUNT)
    }

    @Test
    fun `ALL_SCENARIOS has exactly SCENARIO_COUNT entries`() {
        assertEquals(C.SCENARIO_COUNT, C.ALL_SCENARIOS.size)
    }

    // ── ALL_SCENARIOS — structural invariants ─────────────────────────────────

    @Test
    fun `all scenario IDs are non-blank`() {
        C.ALL_SCENARIOS.forEach { s ->
            assertTrue(
                "Scenario ID must be non-blank",
                s.scenarioId.isNotBlank()
            )
        }
    }

    @Test
    fun `all scenario IDs are unique`() {
        val ids = C.ALL_SCENARIOS.map { it.scenarioId }
        assertEquals(
            "All scenario IDs must be unique",
            ids.size,
            ids.toSet().size
        )
    }

    @Test
    fun `all TERMINATED_WITH_SIGNAL scenarios have non-null requiredSignalKind`() {
        C.ALL_SCENARIOS
            .filter { it.interruptionOutcome == Out.TERMINATED_WITH_SIGNAL }
            .forEach { s ->
                assertNotNull(
                    "Scenario ${s.scenarioId} must have non-null requiredSignalKind",
                    s.requiredSignalKind
                )
            }
    }

    @Test
    fun `all TERMINATED_WITH_SIGNAL scenarios have non-null requiredResultKind`() {
        C.ALL_SCENARIOS
            .filter { it.interruptionOutcome == Out.TERMINATED_WITH_SIGNAL }
            .forEach { s ->
                assertNotNull(
                    "Scenario ${s.scenarioId} must have non-null requiredResultKind",
                    s.requiredResultKind
                )
            }
    }

    @Test
    fun `all TERMINATED_WITH_SIGNAL scenarios have non-null requiredReasonPrefix`() {
        C.ALL_SCENARIOS
            .filter { it.interruptionOutcome == Out.TERMINATED_WITH_SIGNAL }
            .forEach { s ->
                assertNotNull(
                    "Scenario ${s.scenarioId} must have non-null requiredReasonPrefix",
                    s.requiredReasonPrefix
                )
            }
    }

    @Test
    fun `all scenarios have non-blank rationale`() {
        C.ALL_SCENARIOS.forEach { s ->
            assertTrue(
                "Scenario ${s.scenarioId} rationale must be non-blank",
                s.rationale.isNotBlank()
            )
        }
    }

    // ── Specific scenario: connected_to_reconnecting ──────────────────────────

    @Test
    fun `connected_to_reconnecting_with_active_takeover scenario exists`() {
        assertNotNull(
            C.scenarioFor(S.CONNECTED, S.RECONNECTING)
        )
    }

    @Test
    fun `connected_to_reconnecting requires TERMINATED_WITH_SIGNAL`() {
        val s = C.scenarioFor(S.CONNECTED, S.RECONNECTING)!!
        assertEquals(Out.TERMINATED_WITH_SIGNAL, s.interruptionOutcome)
    }

    @Test
    fun `connected_to_reconnecting requiredSignalKind is result`() {
        val s = C.scenarioFor(S.CONNECTED, S.RECONNECTING)!!
        assertEquals(DelegatedExecutionSignal.Kind.RESULT.wireValue, s.requiredSignalKind)
    }

    @Test
    fun `connected_to_reconnecting requiredResultKind is failed`() {
        val s = C.scenarioFor(S.CONNECTED, S.RECONNECTING)!!
        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED.wireValue, s.requiredResultKind)
    }

    @Test
    fun `connected_to_reconnecting reasonPrefix is participant_disconnected`() {
        val s = C.scenarioFor(S.CONNECTED, S.RECONNECTING)!!
        assertEquals(C.REASON_PARTICIPANT_DISCONNECTED, s.requiredReasonPrefix)
    }

    @Test
    fun `connected_to_reconnecting mayResumeAfterReconnect is false`() {
        val s = C.scenarioFor(S.CONNECTED, S.RECONNECTING)!!
        assertFalse(s.mayResumeAfterReconnect)
    }

    // ── Specific scenario: connected_to_disconnected ──────────────────────────

    @Test
    fun `connected_to_disconnected_with_active_takeover scenario exists`() {
        assertNotNull(C.scenarioFor(S.CONNECTED, S.DISCONNECTED))
    }

    @Test
    fun `connected_to_disconnected requires TERMINATED_WITH_SIGNAL`() {
        val s = C.scenarioFor(S.CONNECTED, S.DISCONNECTED)!!
        assertEquals(Out.TERMINATED_WITH_SIGNAL, s.interruptionOutcome)
    }

    @Test
    fun `connected_to_disconnected mayResumeAfterReconnect is false`() {
        val s = C.scenarioFor(S.CONNECTED, S.DISCONNECTED)!!
        assertFalse(s.mayResumeAfterReconnect)
    }

    // ── Specific scenario: connected_to_degraded ──────────────────────────────

    @Test
    fun `connected_to_degraded_with_active_takeover scenario exists`() {
        assertNotNull(C.scenarioFor(S.CONNECTED, S.DEGRADED))
    }

    @Test
    fun `connected_to_degraded requires DRAIN_THEN_TERMINATE`() {
        val s = C.scenarioFor(S.CONNECTED, S.DEGRADED)!!
        assertEquals(Out.DRAIN_THEN_TERMINATE, s.interruptionOutcome)
    }

    @Test
    fun `connected_to_degraded reason prefix is participant_degraded`() {
        val s = C.scenarioFor(S.CONNECTED, S.DEGRADED)!!
        assertEquals(C.REASON_PARTICIPANT_DEGRADED, s.requiredReasonPrefix)
    }

    // ── Specific scenario: reconnecting_to_connected ──────────────────────────

    @Test
    fun `reconnecting_to_connected_no_active_takeover scenario exists`() {
        assertNotNull(C.scenarioFor(S.RECONNECTING, S.CONNECTED))
    }

    @Test
    fun `reconnecting_to_connected has NO_ACTIVE_TAKEOVER outcome`() {
        val s = C.scenarioFor(S.RECONNECTING, S.CONNECTED)!!
        assertEquals(Out.NO_ACTIVE_TAKEOVER, s.interruptionOutcome)
    }

    @Test
    fun `reconnecting_to_connected mayResumeAfterReconnect is false`() {
        val s = C.scenarioFor(S.RECONNECTING, S.CONNECTED)!!
        assertFalse(s.mayResumeAfterReconnect)
    }

    // ── Specific scenario: recovering_to_connected ────────────────────────────

    @Test
    fun `recovering_to_connected_no_active_takeover scenario exists`() {
        assertNotNull(C.scenarioFor(S.RECOVERING, S.CONNECTED))
    }

    @Test
    fun `recovering_to_connected has NO_ACTIVE_TAKEOVER outcome`() {
        val s = C.scenarioFor(S.RECOVERING, S.CONNECTED)!!
        assertEquals(Out.NO_ACTIVE_TAKEOVER, s.interruptionOutcome)
    }

    // ── Specific scenario: capability_limited ─────────────────────────────────

    @Test
    fun `capability_limited_no_active_takeover scenario exists`() {
        assertNotNull(C.scenarioFor(S.CONNECTED, S.CAPABILITY_LIMITED))
    }

    // ── terminatingScenarios ──────────────────────────────────────────────────

    @Test
    fun `terminatingScenarios is non-empty`() {
        assertTrue(C.terminatingScenarios.isNotEmpty())
    }

    @Test
    fun `terminatingScenarios includes connected_to_reconnecting`() {
        assertTrue(
            C.terminatingScenarios.any {
                it.fromState == S.CONNECTED && it.toState == S.RECONNECTING
            }
        )
    }

    @Test
    fun `terminatingScenarios includes connected_to_disconnected`() {
        assertTrue(
            C.terminatingScenarios.any {
                it.fromState == S.CONNECTED && it.toState == S.DISCONNECTED
            }
        )
    }

    @Test
    fun `terminatingScenarios includes connected_to_degraded`() {
        assertTrue(
            C.terminatingScenarios.any {
                it.fromState == S.CONNECTED && it.toState == S.DEGRADED
            }
        )
    }

    @Test
    fun `terminatingScenarios does not include reconnecting_to_connected`() {
        assertFalse(
            C.terminatingScenarios.any {
                it.fromState == S.RECONNECTING && it.toState == S.CONNECTED
            }
        )
    }

    // ── noResumeAfterReconnectScenarios ───────────────────────────────────────

    @Test
    fun `noResumeAfterReconnectScenarios has exactly SCENARIO_COUNT entries`() {
        assertEquals(C.SCENARIO_COUNT, C.noResumeAfterReconnectScenarios.size)
    }

    // ── Reason prefix constants ───────────────────────────────────────────────

    @Test
    fun `REASON_PARTICIPANT_DISCONNECTED is participant_disconnected`() {
        assertEquals("participant_disconnected", C.REASON_PARTICIPANT_DISCONNECTED)
    }

    @Test
    fun `REASON_PARTICIPANT_DEGRADED is participant_degraded`() {
        assertEquals("participant_degraded", C.REASON_PARTICIPANT_DEGRADED)
    }

    // ── Invariant constants ───────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 7`() {
        assertEquals(7, C.INTRODUCED_PR)
    }

    @Test
    fun `INTRODUCED_PR_TITLE is non-blank`() {
        assertTrue(C.INTRODUCED_PR_TITLE.isNotBlank())
    }

    // ── scenarioFor query helper ──────────────────────────────────────────────

    @Test
    fun `scenarioFor returns null for unregistered transition`() {
        assertNull(C.scenarioFor(S.RECOVERING, S.DEGRADED))
    }

    @Test
    fun `scenarioFor returns correct scenario for registered transition`() {
        val s = C.scenarioFor(S.CONNECTED, S.RECONNECTING)
        assertNotNull(s)
        assertEquals(S.CONNECTED, s!!.fromState)
        assertEquals(S.RECONNECTING, s.toState)
    }
}
