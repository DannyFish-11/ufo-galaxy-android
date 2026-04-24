package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR-5 (Android) — Unit tests for [AndroidLocalTruthOwner],
 * [LocalTruthOwnershipClassification], and [LocalTruthEmitDecision].
 *
 * ## Test matrix
 *
 * ### LocalTruthOwnershipClassification — wire values
 *  - LOCAL_AUTHORITATIVE_ASSERTION wireValue is "local_authoritative_assertion"
 *  - EXECUTION_EVIDENCE wireValue is "execution_evidence"
 *  - ADVISORY_LOCAL_TRUTH wireValue is "advisory_local_truth"
 *  - LOCAL_TERMINAL_CLAIM wireValue is "local_terminal_claim"
 *  - PARTIAL_RESULT_TRUTH wireValue is "partial_result_truth"
 *  - POSTURE_BOUND_TRUTH wireValue is "posture_bound_truth"
 *  - ALL_WIRE_VALUES has exactly six entries
 *  - all six wire values are distinct
 *  - fromValue returns correct tier for each wire value
 *  - fromValue returns ADVISORY_LOCAL_TRUTH for unknown value
 *  - fromValue returns ADVISORY_LOCAL_TRUTH for null input
 *
 * ### LocalTruthEmitDecision — wire tags
 *  - WIRE_TAG_EMIT_AUTHORITATIVE is "emit_as_authoritative_local_truth"
 *  - WIRE_TAG_EMIT_EVIDENCE is "emit_as_execution_evidence"
 *  - WIRE_TAG_EMIT_ADVISORY is "emit_as_advisory_only"
 *  - WIRE_TAG_SUPPRESS_TERMINAL is "suppress_due_to_local_terminal"
 *  - WIRE_TAG_SUPPRESS_POSTURE is "suppress_due_to_posture_conflict"
 *  - WIRE_TAG_HOLD_ALIGNMENT is "hold_for_central_alignment"
 *  - ALL_WIRE_TAGS has exactly six entries
 *  - all six wire tags are distinct
 *
 * ### LocalTruthEmitDecision — integration point constants
 *  - INTEGRATION_RECEIVER is "DelegatedRuntimeReceiver"
 *  - INTEGRATION_UNIT is "DelegatedRuntimeUnit"
 *  - INTEGRATION_ACTIVATION_RECORD is "DelegatedActivationRecord"
 *  - INTEGRATION_PIPELINE is "AutonomousExecutionPipeline"
 *  - INTEGRATION_LOOP_CONTROLLER is "LoopController"
 *  - INTEGRATION_TAKEOVER_EXECUTOR is "DelegatedTakeoverExecutor"
 *  - INTEGRATION_COLLABORATION_AGENT is "LocalCollaborationAgent"
 *  - ALL_INTEGRATION_POINTS has exactly seven entries
 *  - all seven integration point values are distinct
 *
 * ### AndroidLocalTruthOwner — companion constants
 *  - INTRODUCED_PR is 5
 *  - DESCRIPTION is non-blank
 *  - CLASSIFICATION_SIGNAL_ACK is EXECUTION_EVIDENCE
 *  - CLASSIFICATION_SIGNAL_PROGRESS is EXECUTION_EVIDENCE
 *  - CLASSIFICATION_SIGNAL_RESULT is LOCAL_AUTHORITATIVE_ASSERTION
 *  - CLASSIFICATION_RESULT_TERMINAL is LOCAL_TERMINAL_CLAIM
 *  - CLASSIFICATION_TASK_PHASE is EXECUTION_EVIDENCE
 *  - CLASSIFICATION_PARTIAL_RESULT is PARTIAL_RESULT_TRUTH
 *  - CLASSIFICATION_RECOVERY_CONTEXT is ADVISORY_LOCAL_TRUTH
 *  - CLASSIFICATION_POSTURE_ASSERTION is POSTURE_BOUND_TRUTH
 *
 * ### classifyAndGate — basic emit decisions
 *  - LOCAL_AUTHORITATIVE_ASSERTION returns EmitAsAuthoritativeLocalTruth
 *  - EXECUTION_EVIDENCE returns EmitAsExecutionEvidence
 *  - ADVISORY_LOCAL_TRUTH returns EmitAsAdvisoryOnly
 *  - PARTIAL_RESULT_TRUTH returns EmitAsAdvisoryOnly
 *  - POSTURE_BOUND_TRUTH with matching posture returns EmitAsAdvisoryOnly
 *  - LOCAL_TERMINAL_CLAIM returns EmitAsAuthoritativeLocalTruth
 *
 * ### classifyAndGate — terminal claim records and suppresses
 *  - LOCAL_TERMINAL_CLAIM records unitId as terminal during classifyAndGate
 *  - Subsequent EXECUTION_EVIDENCE for same unitId returns SuppressDueToLocalTerminal
 *  - Subsequent LOCAL_AUTHORITATIVE_ASSERTION for same unitId returns SuppressDueToLocalTerminal
 *  - Subsequent LOCAL_TERMINAL_CLAIM for same unitId returns SuppressDueToLocalTerminal
 *  - SuppressDueToLocalTerminal.unitId matches the registered unitId
 *  - SuppressDueToLocalTerminal.wireTag is "suppress_due_to_local_terminal"
 *  - Different unitId is not suppressed after another unit's terminal
 *
 * ### classifyAndGate — posture conflict
 *  - POSTURE_BOUND_TRUTH with old posture returns SuppressDueToPostureConflict
 *  - SuppressDueToPostureConflict.producedUnderPosture matches the old posture
 *  - SuppressDueToPostureConflict.currentPosture matches the current posture
 *  - SuppressDueToPostureConflict.wireTag is "suppress_due_to_posture_conflict"
 *  - Non-posture-bound truth is not affected by posture mismatch
 *
 * ### classifyAndGate — central alignment gate
 *  - Gate open: EXECUTION_EVIDENCE returns EmitAsExecutionEvidence (not held)
 *  - Gate closed: EXECUTION_EVIDENCE returns HoldForCentralAlignment
 *  - Gate closed: ADVISORY_LOCAL_TRUTH returns HoldForCentralAlignment
 *  - Gate closed: PARTIAL_RESULT_TRUTH returns HoldForCentralAlignment
 *  - Gate closed: LOCAL_TERMINAL_CLAIM bypasses gate and returns EmitAsAuthoritativeLocalTruth
 *  - Gate closed: LOCAL_AUTHORITATIVE_ASSERTION returns HoldForCentralAlignment
 *  - Gate opened after close: EXECUTION_EVIDENCE returns EmitAsExecutionEvidence again
 *  - HoldForCentralAlignment.wireTag is "hold_for_central_alignment"
 *
 * ### classifyAndGate — suppress takes priority over gate
 *  - Terminal-suppressed unit returns SuppressDueToLocalTerminal even when gate is closed
 *
 * ### Terminal registry
 *  - isLocallyTerminal is false initially
 *  - recordLocalTerminal makes isLocallyTerminal return true
 *  - terminalUnitCount is 0 initially
 *  - terminalUnitCount increments with recordLocalTerminal
 *  - clearTerminalRegistry makes isLocallyTerminal return false
 *  - clearTerminalRegistry resets terminalUnitCount to 0
 *  - Multiple distinct unitIds can all be registered simultaneously
 *
 * ### Posture management
 *  - currentPosture defaults to SourceRuntimePosture.DEFAULT
 *  - updateCurrentPosture updates currentPosture
 *  - updateCurrentPosture normalises unknown posture to DEFAULT
 *
 * ### Central alignment gate state
 *  - isCentralAlignmentGateClosed is false initially
 *  - closeCentralAlignmentGate sets gate to closed
 *  - openCentralAlignmentGate resets gate to open
 */
class Pr5aAndroidLocalTruthOwnershipTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private lateinit var owner: AndroidLocalTruthOwner

    @Before
    fun setUp() {
        owner = AndroidLocalTruthOwner()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun input(
        unitId: String = "unit-1",
        classification: LocalTruthOwnershipClassification,
        producedUnderPosture: String = SourceRuntimePosture.DEFAULT,
        signalKindHint: String = ""
    ) = AndroidLocalTruthOwner.TruthInput(
        unitId = unitId,
        classification = classification,
        producedUnderPosture = producedUnderPosture,
        signalKindHint = signalKindHint
    )

    // ── LocalTruthOwnershipClassification — wire values ───────────────────────

    @Test
    fun `LOCAL_AUTHORITATIVE_ASSERTION wireValue is local_authoritative_assertion`() {
        assertEquals(
            "local_authoritative_assertion",
            LocalTruthOwnershipClassification.LOCAL_AUTHORITATIVE_ASSERTION.wireValue
        )
    }

    @Test
    fun `EXECUTION_EVIDENCE wireValue is execution_evidence`() {
        assertEquals(
            "execution_evidence",
            LocalTruthOwnershipClassification.EXECUTION_EVIDENCE.wireValue
        )
    }

    @Test
    fun `ADVISORY_LOCAL_TRUTH wireValue is advisory_local_truth`() {
        assertEquals(
            "advisory_local_truth",
            LocalTruthOwnershipClassification.ADVISORY_LOCAL_TRUTH.wireValue
        )
    }

    @Test
    fun `LOCAL_TERMINAL_CLAIM wireValue is local_terminal_claim`() {
        assertEquals(
            "local_terminal_claim",
            LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM.wireValue
        )
    }

    @Test
    fun `PARTIAL_RESULT_TRUTH wireValue is partial_result_truth`() {
        assertEquals(
            "partial_result_truth",
            LocalTruthOwnershipClassification.PARTIAL_RESULT_TRUTH.wireValue
        )
    }

    @Test
    fun `POSTURE_BOUND_TRUTH wireValue is posture_bound_truth`() {
        assertEquals(
            "posture_bound_truth",
            LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH.wireValue
        )
    }

    @Test
    fun `ALL_WIRE_VALUES has exactly six entries`() {
        assertEquals(6, LocalTruthOwnershipClassification.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `all six wire values are distinct`() {
        val values = LocalTruthOwnershipClassification.ALL_WIRE_VALUES
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `fromValue returns correct tier for each wire value`() {
        LocalTruthOwnershipClassification.entries.forEach { tier ->
            assertEquals(tier, LocalTruthOwnershipClassification.fromValue(tier.wireValue))
        }
    }

    @Test
    fun `fromValue returns ADVISORY_LOCAL_TRUTH for unknown value`() {
        assertEquals(
            LocalTruthOwnershipClassification.ADVISORY_LOCAL_TRUTH,
            LocalTruthOwnershipClassification.fromValue("unknown_tier_xyz")
        )
    }

    @Test
    fun `fromValue returns ADVISORY_LOCAL_TRUTH for null input`() {
        assertEquals(
            LocalTruthOwnershipClassification.ADVISORY_LOCAL_TRUTH,
            LocalTruthOwnershipClassification.fromValue(null)
        )
    }

    // ── LocalTruthEmitDecision — wire tags ────────────────────────────────────

    @Test
    fun `WIRE_TAG_EMIT_AUTHORITATIVE is emit_as_authoritative_local_truth`() {
        assertEquals(
            "emit_as_authoritative_local_truth",
            LocalTruthEmitDecision.WIRE_TAG_EMIT_AUTHORITATIVE
        )
    }

    @Test
    fun `WIRE_TAG_EMIT_EVIDENCE is emit_as_execution_evidence`() {
        assertEquals("emit_as_execution_evidence", LocalTruthEmitDecision.WIRE_TAG_EMIT_EVIDENCE)
    }

    @Test
    fun `WIRE_TAG_EMIT_ADVISORY is emit_as_advisory_only`() {
        assertEquals("emit_as_advisory_only", LocalTruthEmitDecision.WIRE_TAG_EMIT_ADVISORY)
    }

    @Test
    fun `WIRE_TAG_SUPPRESS_TERMINAL is suppress_due_to_local_terminal`() {
        assertEquals(
            "suppress_due_to_local_terminal",
            LocalTruthEmitDecision.WIRE_TAG_SUPPRESS_TERMINAL
        )
    }

    @Test
    fun `WIRE_TAG_SUPPRESS_POSTURE is suppress_due_to_posture_conflict`() {
        assertEquals(
            "suppress_due_to_posture_conflict",
            LocalTruthEmitDecision.WIRE_TAG_SUPPRESS_POSTURE
        )
    }

    @Test
    fun `WIRE_TAG_HOLD_ALIGNMENT is hold_for_central_alignment`() {
        assertEquals("hold_for_central_alignment", LocalTruthEmitDecision.WIRE_TAG_HOLD_ALIGNMENT)
    }

    @Test
    fun `ALL_WIRE_TAGS has exactly six entries`() {
        assertEquals(6, LocalTruthEmitDecision.ALL_WIRE_TAGS.size)
    }

    @Test
    fun `all six wire tags are distinct`() {
        val tags = LocalTruthEmitDecision.ALL_WIRE_TAGS
        assertEquals(tags.size, tags.toSet().size)
    }

    // ── LocalTruthEmitDecision — integration point constants ──────────────────

    @Test
    fun `INTEGRATION_RECEIVER is DelegatedRuntimeReceiver`() {
        assertEquals("DelegatedRuntimeReceiver", LocalTruthEmitDecision.INTEGRATION_RECEIVER)
    }

    @Test
    fun `INTEGRATION_UNIT is DelegatedRuntimeUnit`() {
        assertEquals("DelegatedRuntimeUnit", LocalTruthEmitDecision.INTEGRATION_UNIT)
    }

    @Test
    fun `INTEGRATION_ACTIVATION_RECORD is DelegatedActivationRecord`() {
        assertEquals("DelegatedActivationRecord", LocalTruthEmitDecision.INTEGRATION_ACTIVATION_RECORD)
    }

    @Test
    fun `INTEGRATION_PIPELINE is AutonomousExecutionPipeline`() {
        assertEquals("AutonomousExecutionPipeline", LocalTruthEmitDecision.INTEGRATION_PIPELINE)
    }

    @Test
    fun `INTEGRATION_LOOP_CONTROLLER is LoopController`() {
        assertEquals("LoopController", LocalTruthEmitDecision.INTEGRATION_LOOP_CONTROLLER)
    }

    @Test
    fun `INTEGRATION_TAKEOVER_EXECUTOR is DelegatedTakeoverExecutor`() {
        assertEquals("DelegatedTakeoverExecutor", LocalTruthEmitDecision.INTEGRATION_TAKEOVER_EXECUTOR)
    }

    @Test
    fun `INTEGRATION_COLLABORATION_AGENT is LocalCollaborationAgent`() {
        assertEquals("LocalCollaborationAgent", LocalTruthEmitDecision.INTEGRATION_COLLABORATION_AGENT)
    }

    @Test
    fun `ALL_INTEGRATION_POINTS has exactly seven entries`() {
        assertEquals(7, LocalTruthEmitDecision.ALL_INTEGRATION_POINTS.size)
    }

    @Test
    fun `all seven integration point values are distinct`() {
        val points = LocalTruthEmitDecision.ALL_INTEGRATION_POINTS
        assertEquals(points.size, points.toSet().size)
    }

    // ── AndroidLocalTruthOwner — companion constants ──────────────────────────

    @Test
    fun `INTRODUCED_PR is 5`() {
        assertEquals(5, AndroidLocalTruthOwner.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(AndroidLocalTruthOwner.DESCRIPTION.isNotBlank())
    }

    @Test
    fun `CLASSIFICATION_SIGNAL_ACK is EXECUTION_EVIDENCE`() {
        assertEquals(
            LocalTruthOwnershipClassification.EXECUTION_EVIDENCE,
            AndroidLocalTruthOwner.CLASSIFICATION_SIGNAL_ACK
        )
    }

    @Test
    fun `CLASSIFICATION_SIGNAL_PROGRESS is EXECUTION_EVIDENCE`() {
        assertEquals(
            LocalTruthOwnershipClassification.EXECUTION_EVIDENCE,
            AndroidLocalTruthOwner.CLASSIFICATION_SIGNAL_PROGRESS
        )
    }

    @Test
    fun `CLASSIFICATION_SIGNAL_RESULT is LOCAL_AUTHORITATIVE_ASSERTION`() {
        assertEquals(
            LocalTruthOwnershipClassification.LOCAL_AUTHORITATIVE_ASSERTION,
            AndroidLocalTruthOwner.CLASSIFICATION_SIGNAL_RESULT
        )
    }

    @Test
    fun `CLASSIFICATION_RESULT_TERMINAL is LOCAL_TERMINAL_CLAIM`() {
        assertEquals(
            LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM,
            AndroidLocalTruthOwner.CLASSIFICATION_RESULT_TERMINAL
        )
    }

    @Test
    fun `CLASSIFICATION_TASK_PHASE is EXECUTION_EVIDENCE`() {
        assertEquals(
            LocalTruthOwnershipClassification.EXECUTION_EVIDENCE,
            AndroidLocalTruthOwner.CLASSIFICATION_TASK_PHASE
        )
    }

    @Test
    fun `CLASSIFICATION_PARTIAL_RESULT is PARTIAL_RESULT_TRUTH`() {
        assertEquals(
            LocalTruthOwnershipClassification.PARTIAL_RESULT_TRUTH,
            AndroidLocalTruthOwner.CLASSIFICATION_PARTIAL_RESULT
        )
    }

    @Test
    fun `CLASSIFICATION_RECOVERY_CONTEXT is ADVISORY_LOCAL_TRUTH`() {
        assertEquals(
            LocalTruthOwnershipClassification.ADVISORY_LOCAL_TRUTH,
            AndroidLocalTruthOwner.CLASSIFICATION_RECOVERY_CONTEXT
        )
    }

    @Test
    fun `CLASSIFICATION_POSTURE_ASSERTION is POSTURE_BOUND_TRUTH`() {
        assertEquals(
            LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH,
            AndroidLocalTruthOwner.CLASSIFICATION_POSTURE_ASSERTION
        )
    }

    // ── classifyAndGate — basic emit decisions ────────────────────────────────

    @Test
    fun `LOCAL_AUTHORITATIVE_ASSERTION returns EmitAsAuthoritativeLocalTruth`() {
        val decision = owner.classifyAndGate(
            input(classification = LocalTruthOwnershipClassification.LOCAL_AUTHORITATIVE_ASSERTION)
        )
        assertTrue(decision is LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth)
        assertEquals(
            LocalTruthEmitDecision.WIRE_TAG_EMIT_AUTHORITATIVE,
            decision.wireTag
        )
    }

    @Test
    fun `EXECUTION_EVIDENCE returns EmitAsExecutionEvidence`() {
        val decision = owner.classifyAndGate(
            input(classification = LocalTruthOwnershipClassification.EXECUTION_EVIDENCE)
        )
        assertTrue(decision is LocalTruthEmitDecision.EmitAsExecutionEvidence)
        assertEquals(LocalTruthEmitDecision.WIRE_TAG_EMIT_EVIDENCE, decision.wireTag)
    }

    @Test
    fun `ADVISORY_LOCAL_TRUTH returns EmitAsAdvisoryOnly`() {
        val decision = owner.classifyAndGate(
            input(classification = LocalTruthOwnershipClassification.ADVISORY_LOCAL_TRUTH)
        )
        assertTrue(decision is LocalTruthEmitDecision.EmitAsAdvisoryOnly)
        assertEquals(LocalTruthEmitDecision.WIRE_TAG_EMIT_ADVISORY, decision.wireTag)
    }

    @Test
    fun `PARTIAL_RESULT_TRUTH returns EmitAsAdvisoryOnly`() {
        val decision = owner.classifyAndGate(
            input(classification = LocalTruthOwnershipClassification.PARTIAL_RESULT_TRUTH)
        )
        assertTrue(decision is LocalTruthEmitDecision.EmitAsAdvisoryOnly)
        assertEquals(LocalTruthEmitDecision.WIRE_TAG_EMIT_ADVISORY, decision.wireTag)
    }

    @Test
    fun `POSTURE_BOUND_TRUTH with matching posture returns EmitAsAdvisoryOnly`() {
        owner.updateCurrentPosture(SourceRuntimePosture.JOIN_RUNTIME)
        val decision = owner.classifyAndGate(
            input(
                classification = LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH,
                producedUnderPosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertTrue(decision is LocalTruthEmitDecision.EmitAsAdvisoryOnly)
    }

    @Test
    fun `LOCAL_TERMINAL_CLAIM returns EmitAsAuthoritativeLocalTruth`() {
        val decision = owner.classifyAndGate(
            input(
                unitId = "unit-terminal",
                classification = LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM
            )
        )
        assertTrue(decision is LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth)
        assertEquals(LocalTruthEmitDecision.WIRE_TAG_EMIT_AUTHORITATIVE, decision.wireTag)
    }

    // ── classifyAndGate — terminal claim records and suppresses ───────────────

    @Test
    fun `LOCAL_TERMINAL_CLAIM records unitId as terminal during classifyAndGate`() {
        val unitId = "unit-to-terminate"
        assertFalse(owner.isLocallyTerminal(unitId))
        owner.classifyAndGate(
            input(
                unitId = unitId,
                classification = LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM
            )
        )
        assertTrue(owner.isLocallyTerminal(unitId))
    }

    @Test
    fun `subsequent EXECUTION_EVIDENCE for same unitId returns SuppressDueToLocalTerminal`() {
        val unitId = "unit-a"
        owner.classifyAndGate(
            input(unitId = unitId, classification = LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM)
        )
        val decision = owner.classifyAndGate(
            input(unitId = unitId, classification = LocalTruthOwnershipClassification.EXECUTION_EVIDENCE)
        )
        assertTrue(decision is LocalTruthEmitDecision.SuppressDueToLocalTerminal)
        assertEquals(LocalTruthEmitDecision.WIRE_TAG_SUPPRESS_TERMINAL, decision.wireTag)
    }

    @Test
    fun `subsequent LOCAL_AUTHORITATIVE_ASSERTION for same unitId returns SuppressDueToLocalTerminal`() {
        val unitId = "unit-b"
        owner.classifyAndGate(
            input(unitId = unitId, classification = LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM)
        )
        val decision = owner.classifyAndGate(
            input(unitId = unitId, classification = LocalTruthOwnershipClassification.LOCAL_AUTHORITATIVE_ASSERTION)
        )
        assertTrue(decision is LocalTruthEmitDecision.SuppressDueToLocalTerminal)
    }

    @Test
    fun `subsequent LOCAL_TERMINAL_CLAIM for same unitId returns SuppressDueToLocalTerminal`() {
        val unitId = "unit-c"
        owner.classifyAndGate(
            input(unitId = unitId, classification = LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM)
        )
        val decision = owner.classifyAndGate(
            input(unitId = unitId, classification = LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM)
        )
        assertTrue(decision is LocalTruthEmitDecision.SuppressDueToLocalTerminal)
    }

    @Test
    fun `SuppressDueToLocalTerminal unitId matches the registered unitId`() {
        val unitId = "unit-suppress-check"
        owner.recordLocalTerminal(unitId)
        val decision = owner.classifyAndGate(
            input(unitId = unitId, classification = LocalTruthOwnershipClassification.EXECUTION_EVIDENCE)
        ) as LocalTruthEmitDecision.SuppressDueToLocalTerminal
        assertEquals(unitId, decision.unitId)
    }

    @Test
    fun `SuppressDueToLocalTerminal wireTag is suppress_due_to_local_terminal`() {
        val unitId = "unit-tag-check"
        owner.recordLocalTerminal(unitId)
        val decision = owner.classifyAndGate(
            input(unitId = unitId, classification = LocalTruthOwnershipClassification.ADVISORY_LOCAL_TRUTH)
        ) as LocalTruthEmitDecision.SuppressDueToLocalTerminal
        assertEquals(LocalTruthEmitDecision.WIRE_TAG_SUPPRESS_TERMINAL, decision.wireTag)
    }

    @Test
    fun `different unitId is not suppressed after another unit terminal`() {
        owner.recordLocalTerminal("unit-terminated")
        val decision = owner.classifyAndGate(
            input(
                unitId = "unit-different",
                classification = LocalTruthOwnershipClassification.EXECUTION_EVIDENCE
            )
        )
        assertFalse(decision is LocalTruthEmitDecision.SuppressDueToLocalTerminal)
    }

    // ── classifyAndGate — posture conflict ────────────────────────────────────

    @Test
    fun `POSTURE_BOUND_TRUTH with old posture returns SuppressDueToPostureConflict`() {
        owner.updateCurrentPosture(SourceRuntimePosture.JOIN_RUNTIME)
        val decision = owner.classifyAndGate(
            input(
                classification = LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH,
                producedUnderPosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertTrue(decision is LocalTruthEmitDecision.SuppressDueToPostureConflict)
        assertEquals(LocalTruthEmitDecision.WIRE_TAG_SUPPRESS_POSTURE, decision.wireTag)
    }

    @Test
    fun `SuppressDueToPostureConflict producedUnderPosture matches old posture`() {
        owner.updateCurrentPosture(SourceRuntimePosture.JOIN_RUNTIME)
        val decision = owner.classifyAndGate(
            input(
                classification = LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH,
                producedUnderPosture = SourceRuntimePosture.CONTROL_ONLY
            )
        ) as LocalTruthEmitDecision.SuppressDueToPostureConflict
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, decision.producedUnderPosture)
    }

    @Test
    fun `SuppressDueToPostureConflict currentPosture matches current posture`() {
        owner.updateCurrentPosture(SourceRuntimePosture.JOIN_RUNTIME)
        val decision = owner.classifyAndGate(
            input(
                classification = LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH,
                producedUnderPosture = SourceRuntimePosture.CONTROL_ONLY
            )
        ) as LocalTruthEmitDecision.SuppressDueToPostureConflict
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, decision.currentPosture)
    }

    @Test
    fun `SuppressDueToPostureConflict wireTag is suppress_due_to_posture_conflict`() {
        owner.updateCurrentPosture(SourceRuntimePosture.JOIN_RUNTIME)
        val decision = owner.classifyAndGate(
            input(
                classification = LocalTruthOwnershipClassification.POSTURE_BOUND_TRUTH,
                producedUnderPosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertEquals(LocalTruthEmitDecision.WIRE_TAG_SUPPRESS_POSTURE, decision.wireTag)
    }

    @Test
    fun `non-posture-bound truth is not affected by posture mismatch`() {
        owner.updateCurrentPosture(SourceRuntimePosture.JOIN_RUNTIME)
        val decision = owner.classifyAndGate(
            input(
                classification = LocalTruthOwnershipClassification.EXECUTION_EVIDENCE,
                producedUnderPosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertFalse(decision is LocalTruthEmitDecision.SuppressDueToPostureConflict)
    }

    // ── classifyAndGate — central alignment gate ──────────────────────────────

    @Test
    fun `gate open EXECUTION_EVIDENCE returns EmitAsExecutionEvidence not held`() {
        val decision = owner.classifyAndGate(
            input(classification = LocalTruthOwnershipClassification.EXECUTION_EVIDENCE)
        )
        assertTrue(decision is LocalTruthEmitDecision.EmitAsExecutionEvidence)
    }

    @Test
    fun `gate closed EXECUTION_EVIDENCE returns HoldForCentralAlignment`() {
        owner.closeCentralAlignmentGate()
        val decision = owner.classifyAndGate(
            input(classification = LocalTruthOwnershipClassification.EXECUTION_EVIDENCE)
        )
        assertTrue(decision is LocalTruthEmitDecision.HoldForCentralAlignment)
        assertEquals(LocalTruthEmitDecision.WIRE_TAG_HOLD_ALIGNMENT, decision.wireTag)
    }

    @Test
    fun `gate closed ADVISORY_LOCAL_TRUTH returns HoldForCentralAlignment`() {
        owner.closeCentralAlignmentGate()
        val decision = owner.classifyAndGate(
            input(classification = LocalTruthOwnershipClassification.ADVISORY_LOCAL_TRUTH)
        )
        assertTrue(decision is LocalTruthEmitDecision.HoldForCentralAlignment)
    }

    @Test
    fun `gate closed PARTIAL_RESULT_TRUTH returns HoldForCentralAlignment`() {
        owner.closeCentralAlignmentGate()
        val decision = owner.classifyAndGate(
            input(classification = LocalTruthOwnershipClassification.PARTIAL_RESULT_TRUTH)
        )
        assertTrue(decision is LocalTruthEmitDecision.HoldForCentralAlignment)
    }

    @Test
    fun `gate closed LOCAL_TERMINAL_CLAIM bypasses gate and returns EmitAsAuthoritativeLocalTruth`() {
        owner.closeCentralAlignmentGate()
        val decision = owner.classifyAndGate(
            input(
                unitId = "unit-gate-bypass",
                classification = LocalTruthOwnershipClassification.LOCAL_TERMINAL_CLAIM
            )
        )
        assertTrue(decision is LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth)
    }

    @Test
    fun `gate closed LOCAL_AUTHORITATIVE_ASSERTION returns HoldForCentralAlignment`() {
        owner.closeCentralAlignmentGate()
        val decision = owner.classifyAndGate(
            input(classification = LocalTruthOwnershipClassification.LOCAL_AUTHORITATIVE_ASSERTION)
        )
        assertTrue(decision is LocalTruthEmitDecision.HoldForCentralAlignment)
    }

    @Test
    fun `gate opened after close EXECUTION_EVIDENCE returns EmitAsExecutionEvidence again`() {
        owner.closeCentralAlignmentGate()
        owner.openCentralAlignmentGate()
        val decision = owner.classifyAndGate(
            input(classification = LocalTruthOwnershipClassification.EXECUTION_EVIDENCE)
        )
        assertTrue(decision is LocalTruthEmitDecision.EmitAsExecutionEvidence)
    }

    @Test
    fun `HoldForCentralAlignment wireTag is hold_for_central_alignment`() {
        owner.closeCentralAlignmentGate()
        val decision = owner.classifyAndGate(
            input(classification = LocalTruthOwnershipClassification.EXECUTION_EVIDENCE)
        )
        assertEquals(LocalTruthEmitDecision.WIRE_TAG_HOLD_ALIGNMENT, decision.wireTag)
    }

    // ── classifyAndGate — suppress takes priority over gate ───────────────────

    @Test
    fun `terminal-suppressed unit returns SuppressDueToLocalTerminal even when gate is closed`() {
        val unitId = "unit-priority-check"
        owner.recordLocalTerminal(unitId)
        owner.closeCentralAlignmentGate()
        val decision = owner.classifyAndGate(
            input(unitId = unitId, classification = LocalTruthOwnershipClassification.EXECUTION_EVIDENCE)
        )
        assertTrue(decision is LocalTruthEmitDecision.SuppressDueToLocalTerminal)
    }

    // ── Terminal registry ─────────────────────────────────────────────────────

    @Test
    fun `isLocallyTerminal is false initially`() {
        assertFalse(owner.isLocallyTerminal("any-unit"))
    }

    @Test
    fun `recordLocalTerminal makes isLocallyTerminal return true`() {
        owner.recordLocalTerminal("unit-x")
        assertTrue(owner.isLocallyTerminal("unit-x"))
    }

    @Test
    fun `terminalUnitCount is 0 initially`() {
        assertEquals(0, owner.terminalUnitCount)
    }

    @Test
    fun `terminalUnitCount increments with recordLocalTerminal`() {
        owner.recordLocalTerminal("unit-1")
        owner.recordLocalTerminal("unit-2")
        assertEquals(2, owner.terminalUnitCount)
    }

    @Test
    fun `clearTerminalRegistry makes isLocallyTerminal return false`() {
        owner.recordLocalTerminal("unit-y")
        owner.clearTerminalRegistry()
        assertFalse(owner.isLocallyTerminal("unit-y"))
    }

    @Test
    fun `clearTerminalRegistry resets terminalUnitCount to 0`() {
        owner.recordLocalTerminal("unit-3")
        owner.recordLocalTerminal("unit-4")
        owner.clearTerminalRegistry()
        assertEquals(0, owner.terminalUnitCount)
    }

    @Test
    fun `multiple distinct unitIds can all be registered simultaneously`() {
        owner.recordLocalTerminal("unit-alpha")
        owner.recordLocalTerminal("unit-beta")
        owner.recordLocalTerminal("unit-gamma")
        assertTrue(owner.isLocallyTerminal("unit-alpha"))
        assertTrue(owner.isLocallyTerminal("unit-beta"))
        assertTrue(owner.isLocallyTerminal("unit-gamma"))
        assertEquals(3, owner.terminalUnitCount)
    }

    // ── Posture management ────────────────────────────────────────────────────

    @Test
    fun `currentPosture defaults to SourceRuntimePosture DEFAULT`() {
        assertEquals(SourceRuntimePosture.DEFAULT, owner.currentPosture)
    }

    @Test
    fun `updateCurrentPosture updates currentPosture`() {
        owner.updateCurrentPosture(SourceRuntimePosture.JOIN_RUNTIME)
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, owner.currentPosture)
    }

    @Test
    fun `updateCurrentPosture normalises unknown posture to DEFAULT`() {
        owner.updateCurrentPosture("not_a_real_posture")
        assertEquals(SourceRuntimePosture.DEFAULT, owner.currentPosture)
    }

    // ── Central alignment gate state ──────────────────────────────────────────

    @Test
    fun `isCentralAlignmentGateClosed is false initially`() {
        assertFalse(owner.isCentralAlignmentGateClosed)
    }

    @Test
    fun `closeCentralAlignmentGate sets gate to closed`() {
        owner.closeCentralAlignmentGate()
        assertTrue(owner.isCentralAlignmentGateClosed)
    }

    @Test
    fun `openCentralAlignmentGate resets gate to open`() {
        owner.closeCentralAlignmentGate()
        owner.openCentralAlignmentGate()
        assertFalse(owner.isCentralAlignmentGateClosed)
    }
}
