package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-07 (Android) — Android Execution Lifecycle Hardening test suite.
 *
 * Regression and acceptance tests for all PR-07 additions:
 *  1. [AndroidExecutionLifecycleContract] — full 12-phase execution lifecycle state machine,
 *     transition table, and uplink requirements.
 *  2. [ExecutionUplinkDiscipline] — result/state uplink discipline rules and consistency
 *     validation.
 *
 * ## Coverage areas
 *
 * ### AndroidExecutionLifecycleContract — ExecutionLifecyclePhase wire values
 *  - All 12 phases have distinct wire values
 *  - Each wire value is stable and non-empty
 *  - fromValue() parses every wire value correctly
 *  - fromValue(null) returns UNKNOWN
 *  - fromValue(unknown) returns UNKNOWN
 *
 * ### AndroidExecutionLifecycleContract — Phase classification
 *  - CAPABILITY_IDLE is capability-only; not active; not terminal
 *  - PENDING, ACTIVATING are not active, not capability, not terminal
 *  - ACTIVE, DEGRADED are active execution phases
 *  - INTERRUPTED, TIMED_OUT, RETRYING are not active, not capability, not terminal
 *  - COMPLETED, FAILED, REJECTED are terminal phases
 *  - UNKNOWN is not active, not capability, not terminal
 *
 * ### AndroidExecutionLifecycleContract — Uplink requirements per phase
 *  - CAPABILITY_IDLE requires no uplinks
 *  - PENDING, ACTIVATING, ACTIVE, DEGRADED, RETRYING require state uplink only
 *  - INTERRUPTED, TIMED_OUT require both result and state uplinks
 *  - COMPLETED, FAILED, REJECTED require both result and state uplinks
 *
 * ### AndroidExecutionLifecycleContract — Transition table
 *  - Normal path: CAPABILITY_IDLE → PENDING → ACTIVATING → ACTIVE → COMPLETED
 *  - Degraded path: ACTIVE → DEGRADED → COMPLETED
 *  - Interruption path: ACTIVE → INTERRUPTED → RETRYING → ACTIVATING
 *  - Timeout path: ACTIVE → TIMED_OUT → FAILED
 *  - Rejection path: CAPABILITY_IDLE → REJECTED
 *  - Terminal → idle: COMPLETED/FAILED/REJECTED → CAPABILITY_IDLE
 *  - Invalid transitions are absent from the table
 *
 * ### AndroidExecutionLifecycleContract — INV invariants
 *  - Terminal phases have no valid transitions except to CAPABILITY_IDLE
 *  - INTERRUPTED cannot transition directly to ACTIVE
 *  - RETRYING cannot transition directly to ACTIVE
 *  - Terminal transitions all require result uplink
 *
 * ### ExecutionUplinkDiscipline — UplinkDecision per phase
 *  - CAPABILITY_IDLE: result PROHIBITED, state OPTIONAL
 *  - PENDING: result PROHIBITED, state REQUIRED
 *  - ACTIVATING: result PROHIBITED, state REQUIRED
 *  - ACTIVE: result PROHIBITED, state REQUIRED
 *  - DEGRADED: result PROHIBITED, state REQUIRED, degradedAware=true
 *  - INTERRUPTED: result REQUIRED, state REQUIRED, interruptionAware=true
 *  - TIMED_OUT: result REQUIRED, state REQUIRED
 *  - RETRYING: result PROHIBITED, state REQUIRED, interruptionAware=true, retryAware=true
 *  - COMPLETED: result REQUIRED, state REQUIRED
 *  - FAILED: result REQUIRED, state REQUIRED
 *  - REJECTED: result REQUIRED, state REQUIRED
 *
 * ### ExecutionUplinkDiscipline — Consistency validation
 *  - validateConsistency() returns empty list (no violations)
 *
 * ### Cross-contract disambiguation
 *  - CAPABILITY_IDLE vs ACTIVE vs COMPLETED are mutually exclusive categories
 *  - DEGRADED is classified as active execution, not capability, not terminal
 *  - INTERRUPTED is classified as non-active, non-terminal (retriable gap closed)
 */
class Pr85AndroidExecutionLifecycleHardeningTest {

    // ── Wire value tests ──────────────────────────────────────────────────────

    @Test
    fun `all ExecutionLifecyclePhase wire values are non-empty`() {
        for (phase in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries) {
            assertTrue(
                "Phase '${phase.name}' must have a non-empty wireValue",
                phase.wireValue.isNotBlank()
            )
        }
    }

    @Test
    fun `all ExecutionLifecyclePhase wire values are distinct`() {
        val wireValues = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries
            .map { it.wireValue }
        assertEquals(
            "All wire values must be distinct",
            wireValues.size,
            wireValues.toSet().size
        )
    }

    @Test
    fun `CAPABILITY_IDLE wireValue is capability_idle`() {
        assertEquals(
            "capability_idle",
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE.wireValue
        )
    }

    @Test
    fun `PENDING wireValue is pending`() {
        assertEquals(
            "pending",
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.PENDING.wireValue
        )
    }

    @Test
    fun `ACTIVATING wireValue is activating`() {
        assertEquals(
            "activating",
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVATING.wireValue
        )
    }

    @Test
    fun `ACTIVE wireValue is active`() {
        assertEquals(
            "active",
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE.wireValue
        )
    }

    @Test
    fun `DEGRADED wireValue is degraded`() {
        assertEquals(
            "degraded",
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED.wireValue
        )
    }

    @Test
    fun `INTERRUPTED wireValue is interrupted`() {
        assertEquals(
            "interrupted",
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED.wireValue
        )
    }

    @Test
    fun `TIMED_OUT wireValue is timed_out`() {
        assertEquals(
            "timed_out",
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TIMED_OUT.wireValue
        )
    }

    @Test
    fun `RETRYING wireValue is retrying`() {
        assertEquals(
            "retrying",
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RETRYING.wireValue
        )
    }

    @Test
    fun `COMPLETED wireValue is completed`() {
        assertEquals(
            "completed",
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED.wireValue
        )
    }

    @Test
    fun `FAILED wireValue is failed`() {
        assertEquals(
            "failed",
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED.wireValue
        )
    }

    @Test
    fun `REJECTED wireValue is rejected`() {
        assertEquals(
            "rejected",
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED.wireValue
        )
    }

    @Test
    fun `UNKNOWN wireValue is unknown`() {
        assertEquals(
            "unknown",
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.UNKNOWN.wireValue
        )
    }

    // ── fromValue() parsing tests ─────────────────────────────────────────────

    @Test
    fun `fromValue parses all known wire values correctly`() {
        for (phase in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries) {
            val parsed = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.fromValue(phase.wireValue)
            assertEquals(
                "fromValue('${phase.wireValue}') must return ${phase.name}",
                phase,
                parsed
            )
        }
    }

    @Test
    fun `fromValue returns UNKNOWN for null input`() {
        assertEquals(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.UNKNOWN,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.fromValue(null)
        )
    }

    @Test
    fun `fromValue returns UNKNOWN for unrecognised string`() {
        assertEquals(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.UNKNOWN,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.fromValue("not_a_real_phase")
        )
    }

    // ── Phase classification: isCapabilityOnly ────────────────────────────────

    @Test
    fun `CAPABILITY_IDLE is capability-only`() {
        assertTrue(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE.isCapabilityOnly)
    }

    @Test
    fun `no other phase is capability-only`() {
        val nonCapabilityPhases = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries
            .filter { it != AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE }
        for (phase in nonCapabilityPhases) {
            assertFalse(
                "Phase '${phase.name}' must not be capability-only",
                phase.isCapabilityOnly
            )
        }
    }

    @Test
    fun `isCapabilityOnly helper matches enum attribute`() {
        for (phase in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries) {
            assertEquals(
                "isCapabilityOnly(${phase.name}) must match phase.isCapabilityOnly",
                phase.isCapabilityOnly,
                AndroidExecutionLifecycleContract.isCapabilityOnly(phase)
            )
        }
    }

    // ── Phase classification: isActiveExecution ───────────────────────────────

    @Test
    fun `ACTIVE is an active execution phase`() {
        assertTrue(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE.isActiveExecution)
    }

    @Test
    fun `DEGRADED is an active execution phase`() {
        assertTrue(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED.isActiveExecution)
    }

    @Test
    fun `ACTIVE_EXECUTION_PHASES contains exactly ACTIVE and DEGRADED`() {
        val expected = setOf(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED
        )
        assertEquals(expected, AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE_EXECUTION_PHASES)
    }

    @Test
    fun `CAPABILITY_IDLE is not an active execution phase`() {
        assertFalse(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE.isActiveExecution)
    }

    @Test
    fun `PENDING is not an active execution phase`() {
        assertFalse(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.PENDING.isActiveExecution)
    }

    @Test
    fun `ACTIVATING is not an active execution phase`() {
        assertFalse(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVATING.isActiveExecution)
    }

    @Test
    fun `INTERRUPTED is not an active execution phase`() {
        assertFalse(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED.isActiveExecution)
    }

    @Test
    fun `TIMED_OUT is not an active execution phase`() {
        assertFalse(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TIMED_OUT.isActiveExecution)
    }

    @Test
    fun `RETRYING is not an active execution phase`() {
        assertFalse(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RETRYING.isActiveExecution)
    }

    @Test
    fun `COMPLETED is not an active execution phase`() {
        assertFalse(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED.isActiveExecution)
    }

    @Test
    fun `FAILED is not an active execution phase`() {
        assertFalse(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED.isActiveExecution)
    }

    @Test
    fun `REJECTED is not an active execution phase`() {
        assertFalse(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED.isActiveExecution)
    }

    @Test
    fun `isActiveExecution helper matches enum attribute`() {
        for (phase in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries) {
            assertEquals(
                "isActiveExecution(${phase.name}) must match phase.isActiveExecution",
                phase.isActiveExecution,
                AndroidExecutionLifecycleContract.isActiveExecution(phase)
            )
        }
    }

    // ── Phase classification: isTerminal ──────────────────────────────────────

    @Test
    fun `COMPLETED is a terminal phase`() {
        assertTrue(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED.isTerminal)
    }

    @Test
    fun `FAILED is a terminal phase`() {
        assertTrue(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED.isTerminal)
    }

    @Test
    fun `REJECTED is a terminal phase`() {
        assertTrue(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED.isTerminal)
    }

    @Test
    fun `TERMINAL_PHASES contains exactly COMPLETED FAILED and REJECTED`() {
        val expected = setOf(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED
        )
        assertEquals(expected, AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TERMINAL_PHASES)
    }

    @Test
    fun `non-terminal phases are not terminal`() {
        val nonTerminal = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries
            .filter { !it.isTerminal }
        val expected = setOf(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.PENDING,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVATING,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TIMED_OUT,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RETRYING,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.UNKNOWN
        )
        assertEquals(expected, nonTerminal.toSet())
    }

    @Test
    fun `isTerminal helper matches enum attribute`() {
        for (phase in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries) {
            assertEquals(
                "isTerminal(${phase.name}) must match phase.isTerminal",
                phase.isTerminal,
                AndroidExecutionLifecycleContract.isTerminal(phase)
            )
        }
    }

    // ── Phase uplink requirement attributes ───────────────────────────────────

    @Test
    fun `CAPABILITY_IDLE requires no uplinks`() {
        val phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE
        assertFalse("CAPABILITY_IDLE must not require result uplink", phase.requiresResultUplink)
        assertFalse("CAPABILITY_IDLE must not require state uplink", phase.requiresStateUplink)
    }

    @Test
    fun `PENDING requires state uplink but not result uplink`() {
        val phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.PENDING
        assertFalse("PENDING must not require result uplink", phase.requiresResultUplink)
        assertTrue("PENDING must require state uplink", phase.requiresStateUplink)
    }

    @Test
    fun `ACTIVATING requires state uplink but not result uplink`() {
        val phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVATING
        assertFalse("ACTIVATING must not require result uplink", phase.requiresResultUplink)
        assertTrue("ACTIVATING must require state uplink", phase.requiresStateUplink)
    }

    @Test
    fun `ACTIVE requires state uplink but not result uplink`() {
        val phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE
        assertFalse("ACTIVE must not require result uplink", phase.requiresResultUplink)
        assertTrue("ACTIVE must require state uplink", phase.requiresStateUplink)
    }

    @Test
    fun `DEGRADED requires state uplink but not result uplink`() {
        val phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED
        assertFalse("DEGRADED must not require result uplink", phase.requiresResultUplink)
        assertTrue("DEGRADED must require state uplink", phase.requiresStateUplink)
    }

    @Test
    fun `INTERRUPTED requires both result and state uplinks`() {
        val phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED
        assertTrue("INTERRUPTED must require result uplink", phase.requiresResultUplink)
        assertTrue("INTERRUPTED must require state uplink", phase.requiresStateUplink)
    }

    @Test
    fun `TIMED_OUT requires both result and state uplinks`() {
        val phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TIMED_OUT
        assertTrue("TIMED_OUT must require result uplink", phase.requiresResultUplink)
        assertTrue("TIMED_OUT must require state uplink", phase.requiresStateUplink)
    }

    @Test
    fun `RETRYING requires state uplink but not result uplink`() {
        val phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RETRYING
        assertFalse("RETRYING must not require result uplink", phase.requiresResultUplink)
        assertTrue("RETRYING must require state uplink", phase.requiresStateUplink)
    }

    @Test
    fun `COMPLETED requires both result and state uplinks`() {
        val phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED
        assertTrue("COMPLETED must require result uplink", phase.requiresResultUplink)
        assertTrue("COMPLETED must require state uplink", phase.requiresStateUplink)
    }

    @Test
    fun `FAILED requires both result and state uplinks`() {
        val phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED
        assertTrue("FAILED must require result uplink", phase.requiresResultUplink)
        assertTrue("FAILED must require state uplink", phase.requiresStateUplink)
    }

    @Test
    fun `REJECTED requires both result and state uplinks`() {
        val phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED
        assertTrue("REJECTED must require result uplink", phase.requiresResultUplink)
        assertTrue("REJECTED must require state uplink", phase.requiresStateUplink)
    }

    @Test
    fun `RESULT_UPLINK_REQUIRED_PHASES contains exactly interruption and terminal phases`() {
        val expected = setOf(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TIMED_OUT,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED
        )
        assertEquals(
            expected,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RESULT_UPLINK_REQUIRED_PHASES
        )
    }

    // ── Transition table: normal path ─────────────────────────────────────────

    @Test
    fun `CAPABILITY_IDLE to PENDING is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.PENDING
            )
        )
    }

    @Test
    fun `PENDING to ACTIVATING is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.PENDING,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVATING
            )
        )
    }

    @Test
    fun `ACTIVATING to ACTIVE is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVATING,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE
            )
        )
    }

    @Test
    fun `ACTIVE to COMPLETED is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED
            )
        )
    }

    @Test
    fun `ACTIVE to COMPLETED transition requires both uplinks`() {
        val req = AndroidExecutionLifecycleContract.uplinkRequirementFor(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED
        )
        assertNotNull(req)
        assertTrue("ACTIVE→COMPLETED must require result uplink", req!!.resultUplinkRequired)
        assertTrue("ACTIVE→COMPLETED must require state uplink", req.stateUplinkRequired)
    }

    // ── Transition table: degraded/fallback path ──────────────────────────────

    @Test
    fun `ACTIVE to DEGRADED is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED
            )
        )
    }

    @Test
    fun `DEGRADED to ACTIVE is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE
            )
        )
    }

    @Test
    fun `DEGRADED to COMPLETED is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED
            )
        )
    }

    @Test
    fun `DEGRADED to FAILED is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED
            )
        )
    }

    @Test
    fun `ACTIVATING to DEGRADED is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVATING,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED
            )
        )
    }

    @Test
    fun `ACTIVE to DEGRADED transition requires state uplink but not result uplink`() {
        val req = AndroidExecutionLifecycleContract.uplinkRequirementFor(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED
        )
        assertNotNull(req)
        assertFalse("ACTIVE→DEGRADED must NOT require result uplink", req!!.resultUplinkRequired)
        assertTrue("ACTIVE→DEGRADED must require state uplink", req.stateUplinkRequired)
    }

    // ── Transition table: interruption path ───────────────────────────────────

    @Test
    fun `ACTIVE to INTERRUPTED is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED
            )
        )
    }

    @Test
    fun `DEGRADED to INTERRUPTED is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED
            )
        )
    }

    @Test
    fun `ACTIVATING to INTERRUPTED is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVATING,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED
            )
        )
    }

    @Test
    fun `ACTIVE to INTERRUPTED transition requires both uplinks`() {
        val req = AndroidExecutionLifecycleContract.uplinkRequirementFor(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED
        )
        assertNotNull(req)
        assertTrue("ACTIVE→INTERRUPTED must require result uplink", req!!.resultUplinkRequired)
        assertTrue("ACTIVE→INTERRUPTED must require state uplink", req.stateUplinkRequired)
    }

    // ── Transition table: retry path ──────────────────────────────────────────

    @Test
    fun `INTERRUPTED to RETRYING is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RETRYING
            )
        )
    }

    @Test
    fun `RETRYING to ACTIVATING is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RETRYING,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVATING
            )
        )
    }

    @Test
    fun `RETRYING to FAILED is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RETRYING,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED
            )
        )
    }

    @Test
    fun `INTERRUPTED to FAILED is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED
            )
        )
    }

    @Test
    fun `INTERRUPTED to RETRYING transition requires state uplink but not result uplink`() {
        val req = AndroidExecutionLifecycleContract.uplinkRequirementFor(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RETRYING
        )
        assertNotNull(req)
        assertFalse("INTERRUPTED→RETRYING must NOT require result uplink", req!!.resultUplinkRequired)
        assertTrue("INTERRUPTED→RETRYING must require state uplink", req.stateUplinkRequired)
    }

    // ── Transition table: timeout path ────────────────────────────────────────

    @Test
    fun `ACTIVE to TIMED_OUT is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TIMED_OUT
            )
        )
    }

    @Test
    fun `TIMED_OUT to FAILED is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TIMED_OUT,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED
            )
        )
    }

    @Test
    fun `ACTIVE to TIMED_OUT transition requires both uplinks`() {
        val req = AndroidExecutionLifecycleContract.uplinkRequirementFor(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TIMED_OUT
        )
        assertNotNull(req)
        assertTrue("ACTIVE→TIMED_OUT must require result uplink", req!!.resultUplinkRequired)
        assertTrue("ACTIVE→TIMED_OUT must require state uplink", req.stateUplinkRequired)
    }

    @Test
    fun `TIMED_OUT to FAILED transition requires state uplink but not result uplink (result already emitted)`() {
        val req = AndroidExecutionLifecycleContract.uplinkRequirementFor(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TIMED_OUT,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED
        )
        assertNotNull(req)
        assertFalse("TIMED_OUT→FAILED must NOT require result uplink (already emitted at TIMED_OUT)", req!!.resultUplinkRequired)
        assertTrue("TIMED_OUT→FAILED must require state uplink", req.stateUplinkRequired)
    }

    // ── Transition table: rejection path ─────────────────────────────────────

    @Test
    fun `CAPABILITY_IDLE to REJECTED is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED
            )
        )
    }

    @Test
    fun `PENDING to REJECTED is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.PENDING,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED
            )
        )
    }

    @Test
    fun `CAPABILITY_IDLE to REJECTED transition requires both uplinks`() {
        val req = AndroidExecutionLifecycleContract.uplinkRequirementFor(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE,
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED
        )
        assertNotNull(req)
        assertTrue("CAPABILITY_IDLE→REJECTED must require result uplink", req!!.resultUplinkRequired)
        assertTrue("CAPABILITY_IDLE→REJECTED must require state uplink", req.stateUplinkRequired)
    }

    // ── Transition table: terminal → idle return path ─────────────────────────

    @Test
    fun `COMPLETED to CAPABILITY_IDLE is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE
            )
        )
    }

    @Test
    fun `FAILED to CAPABILITY_IDLE is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE
            )
        )
    }

    @Test
    fun `REJECTED to CAPABILITY_IDLE is a valid transition`() {
        assertTrue(
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE
            )
        )
    }

    // ── INV invariants: terminal phases ──────────────────────────────────────

    @Test
    fun `INV-01 terminal phases cannot transition to non-terminal non-idle phases`() {
        val terminalPhases = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TERMINAL_PHASES
        val invalidTargets = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries
            .filter { !it.isTerminal && it != AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE }

        for (terminal in terminalPhases) {
            for (target in invalidTargets) {
                assertFalse(
                    "INV-01: ${terminal.name} must not have valid transition to ${target.name}",
                    AndroidExecutionLifecycleContract.isValidTransition(terminal, target)
                )
            }
        }
    }

    @Test
    fun `INV-04 INTERRUPTED cannot transition directly to ACTIVE`() {
        assertFalse(
            "INV-04: INTERRUPTED must not have valid transition to ACTIVE",
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE
            )
        )
    }

    @Test
    fun `INV-04 RETRYING cannot transition directly to ACTIVE`() {
        assertFalse(
            "INV-04: RETRYING must not transition directly to ACTIVE (must go through ACTIVATING)",
            AndroidExecutionLifecycleContract.isValidTransition(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RETRYING,
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE
            )
        )
    }

    @Test
    fun `INV-02 all terminal phase entries require result uplink`() {
        for (terminal in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TERMINAL_PHASES) {
            assertTrue(
                "INV-02: Terminal phase '${terminal.name}' must requireResultUplink=true",
                terminal.requiresResultUplink
            )
        }
    }

    @Test
    fun `INV-03 all non-CAPABILITY_IDLE phases require state uplink`() {
        val phases = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries
            .filter {
                it != AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE &&
                    it != AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.UNKNOWN
            }
        for (phase in phases) {
            assertTrue(
                "INV-03: Phase '${phase.name}' must requireStateUplink=true",
                phase.requiresStateUplink
            )
        }
    }

    @Test
    fun `INV-06 CAPABILITY_IDLE requires no uplinks`() {
        val idle = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE
        assertFalse("INV-06: CAPABILITY_IDLE must not requireResultUplink", idle.requiresResultUplink)
        assertFalse("INV-06: CAPABILITY_IDLE must not requireStateUplink", idle.requiresStateUplink)
    }

    // ── Lifecycle invariant list ──────────────────────────────────────────────

    @Test
    fun `LIFECYCLE_INVARIANTS list is non-empty`() {
        assertTrue(
            "LIFECYCLE_INVARIANTS must be non-empty",
            AndroidExecutionLifecycleContract.LIFECYCLE_INVARIANTS.isNotEmpty()
        )
    }

    @Test
    fun `LIFECYCLE_INVARIANTS contains 12 invariants`() {
        assertEquals(12, AndroidExecutionLifecycleContract.LIFECYCLE_INVARIANTS.size)
    }

    @Test
    fun `all LIFECYCLE_INVARIANTS start with INV-`() {
        for (inv in AndroidExecutionLifecycleContract.LIFECYCLE_INVARIANTS) {
            assertTrue(
                "Invariant must start with 'INV-': $inv",
                inv.startsWith("INV-")
            )
        }
    }

    // ── Wire key constants ────────────────────────────────────────────────────

    @Test
    fun `KEY_EXECUTION_LIFECYCLE_PHASE is execution_lifecycle_phase`() {
        assertEquals(
            "execution_lifecycle_phase",
            AndroidExecutionLifecycleContract.KEY_EXECUTION_LIFECYCLE_PHASE
        )
    }

    @Test
    fun `KEY_RETRY_ATTEMPT_COUNT is retry_attempt_count`() {
        assertEquals(
            "retry_attempt_count",
            AndroidExecutionLifecycleContract.KEY_RETRY_ATTEMPT_COUNT
        )
    }

    @Test
    fun `KEY_INTERRUPTION_CAUSE is interruption_cause`() {
        assertEquals(
            "interruption_cause",
            AndroidExecutionLifecycleContract.KEY_INTERRUPTION_CAUSE
        )
    }

    @Test
    fun `KEY_FALLBACK_TIER is fallback_tier`() {
        assertEquals(
            "fallback_tier",
            AndroidExecutionLifecycleContract.KEY_FALLBACK_TIER
        )
    }

    @Test
    fun `KEY_IS_DEGRADED_EXECUTION is is_degraded_execution`() {
        assertEquals(
            "is_degraded_execution",
            AndroidExecutionLifecycleContract.KEY_IS_DEGRADED_EXECUTION
        )
    }

    @Test
    fun `interruption cause constants are non-empty and distinct`() {
        val causes = listOf(
            AndroidExecutionLifecycleContract.INTERRUPTION_CAUSE_WS_DISCONNECT,
            AndroidExecutionLifecycleContract.INTERRUPTION_CAUSE_EXPLICIT_CANCEL,
            AndroidExecutionLifecycleContract.INTERRUPTION_CAUSE_PROCESS_KILL,
            AndroidExecutionLifecycleContract.INTERRUPTION_CAUSE_SESSION_INVALIDATION
        )
        for (cause in causes) {
            assertTrue("Interruption cause must be non-empty: '$cause'", cause.isNotBlank())
        }
        assertEquals("Interruption causes must be distinct", causes.size, causes.toSet().size)
    }

    @Test
    fun `fallback tier constants are non-empty and distinct`() {
        val tiers = listOf(
            AndroidExecutionLifecycleContract.FALLBACK_TIER_DEGRADED_PLANNER,
            AndroidExecutionLifecycleContract.FALLBACK_TIER_DEGRADED_GROUNDER,
            AndroidExecutionLifecycleContract.FALLBACK_TIER_REMOTE_BRIDGE
        )
        for (tier in tiers) {
            assertTrue("Fallback tier must be non-empty: '$tier'", tier.isNotBlank())
        }
        assertEquals("Fallback tiers must be distinct", tiers.size, tiers.toSet().size)
    }

    // ── validTransitionsFrom() ────────────────────────────────────────────────

    @Test
    fun `validTransitionsFrom CAPABILITY_IDLE includes PENDING and REJECTED`() {
        val valid = AndroidExecutionLifecycleContract.validTransitionsFrom(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE
        )
        assertTrue(valid.contains(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.PENDING))
        assertTrue(valid.contains(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED))
    }

    @Test
    fun `validTransitionsFrom INTERRUPTED does not include ACTIVE`() {
        val valid = AndroidExecutionLifecycleContract.validTransitionsFrom(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED
        )
        assertFalse(valid.contains(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE))
    }

    @Test
    fun `validTransitionsFrom terminal phases only includes CAPABILITY_IDLE`() {
        for (terminal in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TERMINAL_PHASES) {
            val valid = AndroidExecutionLifecycleContract.validTransitionsFrom(terminal)
            assertEquals(
                "Terminal phase '${terminal.name}' must only transition to CAPABILITY_IDLE",
                setOf(AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE),
                valid
            )
        }
    }

    // ── ExecutionUplinkDiscipline — classify() ────────────────────────────────

    @Test
    fun `classify CAPABILITY_IDLE returns result PROHIBITED state OPTIONAL`() {
        val decision = ExecutionUplinkDiscipline.classify(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE
        )
        assertEquals(ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED, decision.resultUplinkRule)
        assertEquals(ExecutionUplinkDiscipline.StateUplinkRule.OPTIONAL, decision.stateUplinkRule)
        assertFalse(decision.resultRequired)
        assertFalse(decision.stateRequired)
    }

    @Test
    fun `classify PENDING returns result PROHIBITED state REQUIRED`() {
        val decision = ExecutionUplinkDiscipline.classify(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.PENDING
        )
        assertEquals(ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED, decision.resultUplinkRule)
        assertEquals(ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED, decision.stateUplinkRule)
    }

    @Test
    fun `classify ACTIVATING returns result PROHIBITED state REQUIRED`() {
        val decision = ExecutionUplinkDiscipline.classify(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVATING
        )
        assertEquals(ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED, decision.resultUplinkRule)
        assertEquals(ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED, decision.stateUplinkRule)
    }

    @Test
    fun `classify ACTIVE returns result PROHIBITED state REQUIRED`() {
        val decision = ExecutionUplinkDiscipline.classify(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE
        )
        assertEquals(ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED, decision.resultUplinkRule)
        assertEquals(ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED, decision.stateUplinkRule)
    }

    @Test
    fun `classify DEGRADED returns result PROHIBITED state REQUIRED and degradedAware`() {
        val decision = ExecutionUplinkDiscipline.classify(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED
        )
        assertEquals(ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED, decision.resultUplinkRule)
        assertEquals(ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED, decision.stateUplinkRule)
        assertTrue("DEGRADED decision must be degradedAware", decision.degradedAware)
    }

    @Test
    fun `classify INTERRUPTED returns result REQUIRED state REQUIRED and interruptionAware`() {
        val decision = ExecutionUplinkDiscipline.classify(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED
        )
        assertEquals(ExecutionUplinkDiscipline.ResultUplinkRule.REQUIRED, decision.resultUplinkRule)
        assertEquals(ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED, decision.stateUplinkRule)
        assertTrue("INTERRUPTED decision must be interruptionAware", decision.interruptionAware)
    }

    @Test
    fun `classify TIMED_OUT returns result REQUIRED state REQUIRED`() {
        val decision = ExecutionUplinkDiscipline.classify(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TIMED_OUT
        )
        assertEquals(ExecutionUplinkDiscipline.ResultUplinkRule.REQUIRED, decision.resultUplinkRule)
        assertEquals(ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED, decision.stateUplinkRule)
    }

    @Test
    fun `classify RETRYING returns result PROHIBITED state REQUIRED interruptionAware and retryAware`() {
        val decision = ExecutionUplinkDiscipline.classify(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RETRYING
        )
        assertEquals(ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED, decision.resultUplinkRule)
        assertEquals(ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED, decision.stateUplinkRule)
        assertTrue("RETRYING decision must be interruptionAware", decision.interruptionAware)
        assertTrue("RETRYING decision must be retryAware", decision.retryAware)
    }

    @Test
    fun `classify COMPLETED returns result REQUIRED state REQUIRED`() {
        val decision = ExecutionUplinkDiscipline.classify(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED
        )
        assertEquals(ExecutionUplinkDiscipline.ResultUplinkRule.REQUIRED, decision.resultUplinkRule)
        assertEquals(ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED, decision.stateUplinkRule)
    }

    @Test
    fun `classify FAILED returns result REQUIRED state REQUIRED`() {
        val decision = ExecutionUplinkDiscipline.classify(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED
        )
        assertEquals(ExecutionUplinkDiscipline.ResultUplinkRule.REQUIRED, decision.resultUplinkRule)
        assertEquals(ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED, decision.stateUplinkRule)
    }

    @Test
    fun `classify REJECTED returns result REQUIRED state REQUIRED`() {
        val decision = ExecutionUplinkDiscipline.classify(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED
        )
        assertEquals(ExecutionUplinkDiscipline.ResultUplinkRule.REQUIRED, decision.resultUplinkRule)
        assertEquals(ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED, decision.stateUplinkRule)
    }

    // ── ExecutionUplinkDiscipline — convenience helpers ───────────────────────

    @Test
    fun `requiresResultUplink returns true for INTERRUPTED`() {
        assertTrue(
            ExecutionUplinkDiscipline.requiresResultUplink(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED
            )
        )
    }

    @Test
    fun `requiresResultUplink returns false for RETRYING`() {
        assertFalse(
            ExecutionUplinkDiscipline.requiresResultUplink(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RETRYING
            )
        )
    }

    @Test
    fun `prohibitsResultUplink returns true for ACTIVE`() {
        assertTrue(
            ExecutionUplinkDiscipline.prohibitsResultUplink(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE
            )
        )
    }

    @Test
    fun `prohibitsResultUplink returns true for CAPABILITY_IDLE`() {
        assertTrue(
            ExecutionUplinkDiscipline.prohibitsResultUplink(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE
            )
        )
    }

    @Test
    fun `prohibitsResultUplink returns false for COMPLETED`() {
        assertFalse(
            ExecutionUplinkDiscipline.prohibitsResultUplink(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED
            )
        )
    }

    @Test
    fun `requiresStateUplink returns true for all execution phases`() {
        for (phase in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.EXECUTION_PHASES) {
            assertTrue(
                "requiresStateUplink must be true for execution phase '${phase.name}'",
                ExecutionUplinkDiscipline.requiresStateUplink(phase)
            )
        }
    }

    @Test
    fun `requiresStateUplink returns true for all terminal phases`() {
        for (phase in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TERMINAL_PHASES) {
            assertTrue(
                "requiresStateUplink must be true for terminal phase '${phase.name}'",
                ExecutionUplinkDiscipline.requiresStateUplink(phase)
            )
        }
    }

    // ── ExecutionUplinkDiscipline — UPLINK_DECISION_TABLE coverage ────────────

    @Test
    fun `UPLINK_DECISION_TABLE has an entry for every ExecutionLifecyclePhase`() {
        for (phase in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries) {
            assertNotNull(
                "UPLINK_DECISION_TABLE must have an entry for phase '${phase.name}'",
                ExecutionUplinkDiscipline.UPLINK_DECISION_TABLE[phase.wireValue]
            )
        }
    }

    @Test
    fun `all UPLINK_DECISION_TABLE uplinkNote values are non-empty`() {
        for ((wireValue, decision) in ExecutionUplinkDiscipline.UPLINK_DECISION_TABLE) {
            assertTrue(
                "UPLINK_DECISION_TABLE entry for '$wireValue' must have non-empty uplinkNote",
                decision.uplinkNote.isNotBlank()
            )
        }
    }

    // ── ExecutionUplinkDiscipline — validateConsistency() ────────────────────

    @Test
    fun `validateConsistency returns no violations`() {
        val violations = ExecutionUplinkDiscipline.validateConsistency()
        assertTrue(
            "ExecutionUplinkDiscipline.validateConsistency() must return no violations, " +
                "but found: $violations",
            violations.isEmpty()
        )
    }

    // ── Cross-contract disambiguation ─────────────────────────────────────────

    @Test
    fun `CAPABILITY_IDLE ACTIVE and COMPLETED are mutually exclusive categories`() {
        val idlePhase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE
        val activePhase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE
        val completedPhase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED

        // CAPABILITY_IDLE: capability-only, not active, not terminal
        assertTrue(idlePhase.isCapabilityOnly)
        assertFalse(idlePhase.isActiveExecution)
        assertFalse(idlePhase.isTerminal)

        // ACTIVE: active execution, not capability-only, not terminal
        assertFalse(activePhase.isCapabilityOnly)
        assertTrue(activePhase.isActiveExecution)
        assertFalse(activePhase.isTerminal)

        // COMPLETED: terminal, not active, not capability-only
        assertFalse(completedPhase.isCapabilityOnly)
        assertFalse(completedPhase.isActiveExecution)
        assertTrue(completedPhase.isTerminal)
    }

    @Test
    fun `DEGRADED is active execution not capability-only and not terminal`() {
        val phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED
        assertTrue("DEGRADED must be isActiveExecution", phase.isActiveExecution)
        assertFalse("DEGRADED must not be isCapabilityOnly", phase.isCapabilityOnly)
        assertFalse("DEGRADED must not be isTerminal", phase.isTerminal)
    }

    @Test
    fun `INTERRUPTED is not active execution and not terminal (retriable gap closed)`() {
        val phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED
        assertFalse("INTERRUPTED must not be isActiveExecution", phase.isActiveExecution)
        assertFalse("INTERRUPTED must not be isTerminal (retriable)", phase.isTerminal)
        assertFalse("INTERRUPTED must not be isCapabilityOnly", phase.isCapabilityOnly)
    }

    @Test
    fun `no phase can be both active and terminal`() {
        for (phase in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries) {
            assertFalse(
                "Phase '${phase.name}' cannot be both isActiveExecution and isTerminal",
                phase.isActiveExecution && phase.isTerminal
            )
        }
    }

    @Test
    fun `no phase can be both capability-only and active execution`() {
        for (phase in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries) {
            assertFalse(
                "Phase '${phase.name}' cannot be both isCapabilityOnly and isActiveExecution",
                phase.isCapabilityOnly && phase.isActiveExecution
            )
        }
    }

    @Test
    fun `no phase can be both capability-only and terminal`() {
        for (phase in AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.entries) {
            assertFalse(
                "Phase '${phase.name}' cannot be both isCapabilityOnly and isTerminal",
                phase.isCapabilityOnly && phase.isTerminal
            )
        }
    }

    // ── ResultUplinkRule and StateUplinkRule wire values ──────────────────────

    @Test
    fun `ResultUplinkRule REQUIRED wireValue is required`() {
        assertEquals("required", ExecutionUplinkDiscipline.ResultUplinkRule.REQUIRED.wireValue)
    }

    @Test
    fun `ResultUplinkRule OPTIONAL wireValue is optional`() {
        assertEquals("optional", ExecutionUplinkDiscipline.ResultUplinkRule.OPTIONAL.wireValue)
    }

    @Test
    fun `ResultUplinkRule PROHIBITED wireValue is prohibited`() {
        assertEquals("prohibited", ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED.wireValue)
    }

    @Test
    fun `StateUplinkRule REQUIRED wireValue is required`() {
        assertEquals("required", ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED.wireValue)
    }

    @Test
    fun `StateUplinkRule OPTIONAL wireValue is optional`() {
        assertEquals("optional", ExecutionUplinkDiscipline.StateUplinkRule.OPTIONAL.wireValue)
    }

    @Test
    fun `all ResultUplinkRule wire values are distinct`() {
        val wireValues = ExecutionUplinkDiscipline.ResultUplinkRule.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    @Test
    fun `all StateUplinkRule wire values are distinct`() {
        val wireValues = ExecutionUplinkDiscipline.StateUplinkRule.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    // ── UplinkDecision boolean helpers ────────────────────────────────────────

    @Test
    fun `UplinkDecision resultRequired is true only when rule is REQUIRED`() {
        val required = ExecutionUplinkDiscipline.UplinkDecision(
            resultUplinkRule = ExecutionUplinkDiscipline.ResultUplinkRule.REQUIRED,
            stateUplinkRule = ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED,
            uplinkNote = "test"
        )
        val optional = required.copy(resultUplinkRule = ExecutionUplinkDiscipline.ResultUplinkRule.OPTIONAL)
        val prohibited = required.copy(resultUplinkRule = ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED)

        assertTrue("REQUIRED must set resultRequired=true", required.resultRequired)
        assertFalse("OPTIONAL must set resultRequired=false", optional.resultRequired)
        assertFalse("PROHIBITED must set resultRequired=false", prohibited.resultRequired)
    }

    @Test
    fun `UplinkDecision resultProhibited is true only when rule is PROHIBITED`() {
        val prohibited = ExecutionUplinkDiscipline.UplinkDecision(
            resultUplinkRule = ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED,
            stateUplinkRule = ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED,
            uplinkNote = "test"
        )
        val required = prohibited.copy(resultUplinkRule = ExecutionUplinkDiscipline.ResultUplinkRule.REQUIRED)
        val optional = prohibited.copy(resultUplinkRule = ExecutionUplinkDiscipline.ResultUplinkRule.OPTIONAL)

        assertTrue("PROHIBITED must set resultProhibited=true", prohibited.resultProhibited)
        assertFalse("REQUIRED must set resultProhibited=false", required.resultProhibited)
        assertFalse("OPTIONAL must set resultProhibited=false", optional.resultProhibited)
    }

    @Test
    fun `UplinkDecision stateRequired is true only when rule is REQUIRED`() {
        val required = ExecutionUplinkDiscipline.UplinkDecision(
            resultUplinkRule = ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED,
            stateUplinkRule = ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED,
            uplinkNote = "test"
        )
        val optional = required.copy(stateUplinkRule = ExecutionUplinkDiscipline.StateUplinkRule.OPTIONAL)

        assertTrue("REQUIRED must set stateRequired=true", required.stateRequired)
        assertFalse("OPTIONAL must set stateRequired=false", optional.stateRequired)
    }
}
