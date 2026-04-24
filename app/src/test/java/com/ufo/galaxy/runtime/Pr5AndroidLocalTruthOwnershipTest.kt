package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR-5 (Android) — Unit tests for [AndroidLocalTruthOwnershipCoordinator],
 * [LocalTruthEmitDecision], and related truth classification / emit-gating contracts.
 *
 * ## Test matrix
 *
 * ### AndroidLocalTruthOwnershipCoordinator — constants
 *  - DECISION_EMIT_AS_AUTHORITATIVE_LOCAL_TRUTH wire value is "emit_as_authoritative_local_truth"
 *  - DECISION_EMIT_AS_EXECUTION_EVIDENCE wire value is "emit_as_execution_evidence"
 *  - DECISION_EMIT_AS_ADVISORY_ONLY wire value is "emit_as_advisory_only"
 *  - DECISION_SUPPRESS_DUE_TO_LOCAL_TERMINAL wire value is "suppress_due_to_local_terminal"
 *  - DECISION_SUPPRESS_DUE_TO_POSTURE_CONFLICT wire value is "suppress_due_to_posture_conflict"
 *  - DECISION_HOLD_FOR_CENTRAL_ALIGNMENT wire value is "hold_for_central_alignment"
 *  - All six decision constants are distinct
 *  - INTEGRATION_RECEIVER is "DelegatedRuntimeReceiver"
 *  - INTEGRATION_UNIT is "DelegatedRuntimeUnit"
 *  - INTEGRATION_ACTIVATION_RECORD is "DelegatedActivationRecord"
 *  - INTEGRATION_PIPELINE is "AutonomousExecutionPipeline"
 *  - INTEGRATION_LOOP_CONTROLLER is "LoopController"
 *  - INTEGRATION_TAKEOVER_EXECUTOR is "DelegatedTakeoverExecutor"
 *  - INTEGRATION_COLLABORATION_AGENT is "LocalCollaborationAgent"
 *  - INTRODUCED_PR is 5
 *  - DESCRIPTION is non-blank
 *
 * ### TruthClass — wire values
 *  - LOCAL_AUTHORITATIVE_ASSERTION wireValue is "local_authoritative_assertion"
 *  - EXECUTION_EVIDENCE wireValue is "execution_evidence"
 *  - ADVISORY_LOCAL_TRUTH wireValue is "advisory_local_truth"
 *  - LOCAL_TERMINAL_CLAIM wireValue is "local_terminal_claim"
 *  - PARTIAL_RESULT_TRUTH wireValue is "partial_result_truth"
 *  - POSTURE_BOUND_TRUTH wireValue is "posture_bound_truth"
 *  - All six TruthClass wire values are distinct
 *
 * ### classifyTruth — kind to class mapping
 *  - "ack" → LOCAL_AUTHORITATIVE_ASSERTION
 *  - "progress" → EXECUTION_EVIDENCE
 *  - "result_completed" → LOCAL_TERMINAL_CLAIM
 *  - "result_failed" → LOCAL_TERMINAL_CLAIM
 *  - "result_cancelled" → LOCAL_TERMINAL_CLAIM
 *  - "result_timed_out" → LOCAL_TERMINAL_CLAIM
 *  - "partial_result" → PARTIAL_RESULT_TRUTH
 *  - "task_phase" → EXECUTION_EVIDENCE
 *  - "posture_change" → POSTURE_BOUND_TRUTH
 *  - "advisory" → ADVISORY_LOCAL_TRUTH
 *  - unknown kind falls back to EXECUTION_EVIDENCE
 *
 * ### evaluateEmit — LOCAL_AUTHORITATIVE_ASSERTION (no suppression)
 *  - Returns EmitAsAuthoritativeLocalTruth for LOCAL_AUTHORITATIVE_ASSERTION class
 *  - EmitAsAuthoritativeLocalTruth.semanticTag is DECISION_EMIT_AS_AUTHORITATIVE_LOCAL_TRUTH
 *  - EmitAsAuthoritativeLocalTruth.unitId matches context unitId
 *  - EmitAsAuthoritativeLocalTruth.truthClass is LOCAL_AUTHORITATIVE_ASSERTION
 *
 * ### evaluateEmit — LOCAL_TERMINAL_CLAIM (no suppression)
 *  - Returns EmitAsAuthoritativeLocalTruth for LOCAL_TERMINAL_CLAIM class
 *  - EmitAsAuthoritativeLocalTruth.truthClass is LOCAL_TERMINAL_CLAIM
 *
 * ### evaluateEmit — EXECUTION_EVIDENCE (no suppression)
 *  - Returns EmitAsExecutionEvidence for EXECUTION_EVIDENCE class
 *  - EmitAsExecutionEvidence.semanticTag is DECISION_EMIT_AS_EXECUTION_EVIDENCE
 *  - EmitAsExecutionEvidence.unitId matches context unitId
 *
 * ### evaluateEmit — PARTIAL_RESULT_TRUTH (no suppression)
 *  - Returns EmitAsExecutionEvidence for PARTIAL_RESULT_TRUTH class
 *  - EmitAsExecutionEvidence.truthClass is PARTIAL_RESULT_TRUTH
 *
 * ### evaluateEmit — ADVISORY_LOCAL_TRUTH (no suppression)
 *  - Returns EmitAsAdvisoryOnly for ADVISORY_LOCAL_TRUTH class
 *  - EmitAsAdvisoryOnly.semanticTag is DECISION_EMIT_AS_ADVISORY_ONLY
 *  - EmitAsAdvisoryOnly.unitId matches context unitId
 *
 * ### evaluateEmit — POSTURE_BOUND_TRUTH with matching posture
 *  - Returns EmitAsAdvisoryOnly when generated posture equals current posture
 *
 * ### evaluateEmit — POSTURE_BOUND_TRUTH with changed posture (conflict)
 *  - Returns SuppressDueToPostureConflict when postures differ
 *  - SuppressDueToPostureConflict.semanticTag is DECISION_SUPPRESS_DUE_TO_POSTURE_CONFLICT
 *  - SuppressDueToPostureConflict.generatedPosture matches the generated posture
 *  - SuppressDueToPostureConflict.currentPosture matches the current posture
 *  - SuppressDueToPostureConflict.unitId matches context unitId
 *
 * ### evaluateEmit — POSTURE_BOUND_TRUTH with null postures
 *  - Returns EmitAsAdvisoryOnly when generatedPosture is null
 *  - Returns EmitAsAdvisoryOnly when currentPosture is null
 *  - Returns EmitAsAdvisoryOnly when both postures are null
 *
 * ### evaluateEmit — local terminal suppression
 *  - Returns SuppressDueToLocalTerminal after markLocalTerminal
 *  - SuppressDueToLocalTerminal.semanticTag is DECISION_SUPPRESS_DUE_TO_LOCAL_TERMINAL
 *  - SuppressDueToLocalTerminal.unitId matches the terminal unit
 *  - SuppressDueToLocalTerminal.terminalKind matches the registered kind
 *  - Terminal suppression takes priority over central alignment hold
 *  - Terminal suppression takes priority over posture conflict
 *  - Different unitId is not suppressed after marking another unit terminal
 *
 * ### evaluateEmit — central alignment hold
 *  - Returns HoldForCentralAlignment after closeTruthEmitGate
 *  - HoldForCentralAlignment.semanticTag is DECISION_HOLD_FOR_CENTRAL_ALIGNMENT
 *  - HoldForCentralAlignment.unitId matches the gated unit
 *  - Central alignment hold takes priority over posture conflict
 *  - After openTruthEmitGate, evaluateEmit returns normal decision
 *  - Different unitId is not held after gating another unit
 *
 * ### Local terminal registry
 *  - isLocalTerminal is false initially
 *  - isLocalTerminal is true after markLocalTerminal
 *  - isLocalTerminal is false after clearLocalTerminal
 *  - localTerminalKind is null when not terminal
 *  - localTerminalKind returns the registered kind after markLocalTerminal
 *  - localTerminalCount is 0 initially
 *  - localTerminalCount increments with markLocalTerminal
 *  - localTerminalCount decrements with clearLocalTerminal
 *  - clearAllLocalTerminals resets count to 0
 *  - Multiple distinct unitIds can all be registered as terminal simultaneously
 *
 * ### Truth emit gate
 *  - isTruthEmitGated is false initially
 *  - isTruthEmitGated is true after closeTruthEmitGate
 *  - isTruthEmitGated is false after openTruthEmitGate
 *  - gatedUnitCount is 0 initially
 *  - gatedUnitCount increments with closeTruthEmitGate
 *  - gatedUnitCount decrements with openTruthEmitGate
 *  - clearAllTruthEmitGates resets count to 0
 *  - Multiple distinct unitIds can all be gated simultaneously
 *
 * ### LocalTruthEmitDecision — semanticTag values
 *  - EmitAsAuthoritativeLocalTruth.semanticTag matches DECISION_EMIT_AS_AUTHORITATIVE_LOCAL_TRUTH
 *  - EmitAsExecutionEvidence.semanticTag matches DECISION_EMIT_AS_EXECUTION_EVIDENCE
 *  - EmitAsAdvisoryOnly.semanticTag matches DECISION_EMIT_AS_ADVISORY_ONLY
 *  - SuppressDueToLocalTerminal.semanticTag matches DECISION_SUPPRESS_DUE_TO_LOCAL_TERMINAL
 *  - SuppressDueToPostureConflict.semanticTag matches DECISION_SUPPRESS_DUE_TO_POSTURE_CONFLICT
 *  - HoldForCentralAlignment.semanticTag matches DECISION_HOLD_FOR_CENTRAL_ALIGNMENT
 *  - All six semanticTag values are distinct
 */
class Pr5AndroidLocalTruthOwnershipTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private lateinit var coordinator: AndroidLocalTruthOwnershipCoordinator

    @Before
    fun setUp() {
        coordinator = AndroidLocalTruthOwnershipCoordinator()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun context(
        unitId: String = "unit-1",
        truthClass: AndroidLocalTruthOwnershipCoordinator.TruthClass =
            AndroidLocalTruthOwnershipCoordinator.TruthClass.EXECUTION_EVIDENCE,
        generatedPosture: SourceRuntimePosture? = null,
        currentPosture: SourceRuntimePosture? = null,
        reason: String = ""
    ) = AndroidLocalTruthOwnershipCoordinator.EmitContext(
        unitId = unitId,
        truthClass = truthClass,
        generatedPosture = generatedPosture,
        currentPosture = currentPosture,
        reason = reason
    )

    private fun authoritativeContext(unitId: String = "unit-1") = context(
        unitId = unitId,
        truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_AUTHORITATIVE_ASSERTION
    )

    private fun terminalClaimContext(unitId: String = "unit-1") = context(
        unitId = unitId,
        truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_TERMINAL_CLAIM
    )

    private fun evidenceContext(unitId: String = "unit-1") = context(
        unitId = unitId,
        truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.EXECUTION_EVIDENCE
    )

    private fun partialResultContext(unitId: String = "unit-1") = context(
        unitId = unitId,
        truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.PARTIAL_RESULT_TRUTH
    )

    private fun advisoryContext(unitId: String = "unit-1") = context(
        unitId = unitId,
        truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.ADVISORY_LOCAL_TRUTH
    )

    private fun postureBoundContext(
        unitId: String = "unit-1",
        generatedPosture: SourceRuntimePosture,
        currentPosture: SourceRuntimePosture
    ) = context(
        unitId = unitId,
        truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.POSTURE_BOUND_TRUTH,
        generatedPosture = generatedPosture,
        currentPosture = currentPosture
    )

    // ── Constants tests ───────────────────────────────────────────────────────

    @Test
    fun `DECISION_EMIT_AS_AUTHORITATIVE_LOCAL_TRUTH wire value is emit_as_authoritative_local_truth`() {
        assertEquals(
            "emit_as_authoritative_local_truth",
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_AUTHORITATIVE_LOCAL_TRUTH
        )
    }

    @Test
    fun `DECISION_EMIT_AS_EXECUTION_EVIDENCE wire value is emit_as_execution_evidence`() {
        assertEquals(
            "emit_as_execution_evidence",
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_EXECUTION_EVIDENCE
        )
    }

    @Test
    fun `DECISION_EMIT_AS_ADVISORY_ONLY wire value is emit_as_advisory_only`() {
        assertEquals(
            "emit_as_advisory_only",
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_ADVISORY_ONLY
        )
    }

    @Test
    fun `DECISION_SUPPRESS_DUE_TO_LOCAL_TERMINAL wire value is suppress_due_to_local_terminal`() {
        assertEquals(
            "suppress_due_to_local_terminal",
            AndroidLocalTruthOwnershipCoordinator.DECISION_SUPPRESS_DUE_TO_LOCAL_TERMINAL
        )
    }

    @Test
    fun `DECISION_SUPPRESS_DUE_TO_POSTURE_CONFLICT wire value is suppress_due_to_posture_conflict`() {
        assertEquals(
            "suppress_due_to_posture_conflict",
            AndroidLocalTruthOwnershipCoordinator.DECISION_SUPPRESS_DUE_TO_POSTURE_CONFLICT
        )
    }

    @Test
    fun `DECISION_HOLD_FOR_CENTRAL_ALIGNMENT wire value is hold_for_central_alignment`() {
        assertEquals(
            "hold_for_central_alignment",
            AndroidLocalTruthOwnershipCoordinator.DECISION_HOLD_FOR_CENTRAL_ALIGNMENT
        )
    }

    @Test
    fun `all six decision constants are distinct`() {
        val decisions = setOf(
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_AUTHORITATIVE_LOCAL_TRUTH,
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_EXECUTION_EVIDENCE,
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_ADVISORY_ONLY,
            AndroidLocalTruthOwnershipCoordinator.DECISION_SUPPRESS_DUE_TO_LOCAL_TERMINAL,
            AndroidLocalTruthOwnershipCoordinator.DECISION_SUPPRESS_DUE_TO_POSTURE_CONFLICT,
            AndroidLocalTruthOwnershipCoordinator.DECISION_HOLD_FOR_CENTRAL_ALIGNMENT
        )
        assertEquals(6, decisions.size)
    }

    @Test
    fun `INTEGRATION_RECEIVER is DelegatedRuntimeReceiver`() {
        assertEquals(
            "DelegatedRuntimeReceiver",
            AndroidLocalTruthOwnershipCoordinator.INTEGRATION_RECEIVER
        )
    }

    @Test
    fun `INTEGRATION_UNIT is DelegatedRuntimeUnit`() {
        assertEquals(
            "DelegatedRuntimeUnit",
            AndroidLocalTruthOwnershipCoordinator.INTEGRATION_UNIT
        )
    }

    @Test
    fun `INTEGRATION_ACTIVATION_RECORD is DelegatedActivationRecord`() {
        assertEquals(
            "DelegatedActivationRecord",
            AndroidLocalTruthOwnershipCoordinator.INTEGRATION_ACTIVATION_RECORD
        )
    }

    @Test
    fun `INTEGRATION_PIPELINE is AutonomousExecutionPipeline`() {
        assertEquals(
            "AutonomousExecutionPipeline",
            AndroidLocalTruthOwnershipCoordinator.INTEGRATION_PIPELINE
        )
    }

    @Test
    fun `INTEGRATION_LOOP_CONTROLLER is LoopController`() {
        assertEquals(
            "LoopController",
            AndroidLocalTruthOwnershipCoordinator.INTEGRATION_LOOP_CONTROLLER
        )
    }

    @Test
    fun `INTEGRATION_TAKEOVER_EXECUTOR is DelegatedTakeoverExecutor`() {
        assertEquals(
            "DelegatedTakeoverExecutor",
            AndroidLocalTruthOwnershipCoordinator.INTEGRATION_TAKEOVER_EXECUTOR
        )
    }

    @Test
    fun `INTEGRATION_COLLABORATION_AGENT is LocalCollaborationAgent`() {
        assertEquals(
            "LocalCollaborationAgent",
            AndroidLocalTruthOwnershipCoordinator.INTEGRATION_COLLABORATION_AGENT
        )
    }

    @Test
    fun `INTRODUCED_PR is 5`() {
        assertEquals(5, AndroidLocalTruthOwnershipCoordinator.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(AndroidLocalTruthOwnershipCoordinator.DESCRIPTION.isNotBlank())
    }

    // ── TruthClass wire values ────────────────────────────────────────────────

    @Test
    fun `LOCAL_AUTHORITATIVE_ASSERTION wireValue is local_authoritative_assertion`() {
        assertEquals(
            "local_authoritative_assertion",
            AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_AUTHORITATIVE_ASSERTION.wireValue
        )
    }

    @Test
    fun `EXECUTION_EVIDENCE wireValue is execution_evidence`() {
        assertEquals(
            "execution_evidence",
            AndroidLocalTruthOwnershipCoordinator.TruthClass.EXECUTION_EVIDENCE.wireValue
        )
    }

    @Test
    fun `ADVISORY_LOCAL_TRUTH wireValue is advisory_local_truth`() {
        assertEquals(
            "advisory_local_truth",
            AndroidLocalTruthOwnershipCoordinator.TruthClass.ADVISORY_LOCAL_TRUTH.wireValue
        )
    }

    @Test
    fun `LOCAL_TERMINAL_CLAIM wireValue is local_terminal_claim`() {
        assertEquals(
            "local_terminal_claim",
            AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_TERMINAL_CLAIM.wireValue
        )
    }

    @Test
    fun `PARTIAL_RESULT_TRUTH wireValue is partial_result_truth`() {
        assertEquals(
            "partial_result_truth",
            AndroidLocalTruthOwnershipCoordinator.TruthClass.PARTIAL_RESULT_TRUTH.wireValue
        )
    }

    @Test
    fun `POSTURE_BOUND_TRUTH wireValue is posture_bound_truth`() {
        assertEquals(
            "posture_bound_truth",
            AndroidLocalTruthOwnershipCoordinator.TruthClass.POSTURE_BOUND_TRUTH.wireValue
        )
    }

    @Test
    fun `all six TruthClass wire values are distinct`() {
        val wireValues = AndroidLocalTruthOwnershipCoordinator.TruthClass.values()
            .map { it.wireValue }
            .toSet()
        assertEquals(6, wireValues.size)
    }

    // ── classifyTruth — kind to class mapping ─────────────────────────────────

    @Test
    fun `ack classifies as LOCAL_AUTHORITATIVE_ASSERTION`() {
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_AUTHORITATIVE_ASSERTION,
            coordinator.classifyTruth(AndroidLocalTruthOwnershipCoordinator.KIND_ACK)
        )
    }

    @Test
    fun `progress classifies as EXECUTION_EVIDENCE`() {
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.EXECUTION_EVIDENCE,
            coordinator.classifyTruth(AndroidLocalTruthOwnershipCoordinator.KIND_PROGRESS)
        )
    }

    @Test
    fun `result_completed classifies as LOCAL_TERMINAL_CLAIM`() {
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_TERMINAL_CLAIM,
            coordinator.classifyTruth(AndroidLocalTruthOwnershipCoordinator.KIND_RESULT_COMPLETED)
        )
    }

    @Test
    fun `result_failed classifies as LOCAL_TERMINAL_CLAIM`() {
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_TERMINAL_CLAIM,
            coordinator.classifyTruth(AndroidLocalTruthOwnershipCoordinator.KIND_RESULT_FAILED)
        )
    }

    @Test
    fun `result_cancelled classifies as LOCAL_TERMINAL_CLAIM`() {
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_TERMINAL_CLAIM,
            coordinator.classifyTruth(AndroidLocalTruthOwnershipCoordinator.KIND_RESULT_CANCELLED)
        )
    }

    @Test
    fun `result_timed_out classifies as LOCAL_TERMINAL_CLAIM`() {
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_TERMINAL_CLAIM,
            coordinator.classifyTruth(AndroidLocalTruthOwnershipCoordinator.KIND_RESULT_TIMED_OUT)
        )
    }

    @Test
    fun `partial_result classifies as PARTIAL_RESULT_TRUTH`() {
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.PARTIAL_RESULT_TRUTH,
            coordinator.classifyTruth(AndroidLocalTruthOwnershipCoordinator.KIND_PARTIAL_RESULT)
        )
    }

    @Test
    fun `task_phase classifies as EXECUTION_EVIDENCE`() {
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.EXECUTION_EVIDENCE,
            coordinator.classifyTruth(AndroidLocalTruthOwnershipCoordinator.KIND_TASK_PHASE)
        )
    }

    @Test
    fun `posture_change classifies as POSTURE_BOUND_TRUTH`() {
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.POSTURE_BOUND_TRUTH,
            coordinator.classifyTruth(AndroidLocalTruthOwnershipCoordinator.KIND_POSTURE_CHANGE)
        )
    }

    @Test
    fun `advisory classifies as ADVISORY_LOCAL_TRUTH`() {
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.ADVISORY_LOCAL_TRUTH,
            coordinator.classifyTruth(AndroidLocalTruthOwnershipCoordinator.KIND_ADVISORY)
        )
    }

    @Test
    fun `unknown kind falls back to EXECUTION_EVIDENCE`() {
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.EXECUTION_EVIDENCE,
            coordinator.classifyTruth("some_unknown_kind_that_does_not_exist")
        )
    }

    // ── evaluateEmit — LOCAL_AUTHORITATIVE_ASSERTION ──────────────────────────

    @Test
    fun `evaluateEmit returns EmitAsAuthoritativeLocalTruth for LOCAL_AUTHORITATIVE_ASSERTION`() {
        val decision = coordinator.evaluateEmit(authoritativeContext())
        assertInstanceOf(LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth::class.java, decision)
    }

    @Test
    fun `EmitAsAuthoritativeLocalTruth semanticTag is DECISION_EMIT_AS_AUTHORITATIVE_LOCAL_TRUTH`() {
        val decision = coordinator.evaluateEmit(authoritativeContext()) as LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_AUTHORITATIVE_LOCAL_TRUTH,
            decision.semanticTag
        )
    }

    @Test
    fun `EmitAsAuthoritativeLocalTruth unitId matches context unitId`() {
        val decision = coordinator.evaluateEmit(authoritativeContext("unit-auth-42")) as LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth
        assertEquals("unit-auth-42", decision.unitId)
    }

    @Test
    fun `EmitAsAuthoritativeLocalTruth truthClass is LOCAL_AUTHORITATIVE_ASSERTION`() {
        val decision = coordinator.evaluateEmit(authoritativeContext()) as LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_AUTHORITATIVE_ASSERTION,
            decision.truthClass
        )
    }

    // ── evaluateEmit — LOCAL_TERMINAL_CLAIM ───────────────────────────────────

    @Test
    fun `evaluateEmit returns EmitAsAuthoritativeLocalTruth for LOCAL_TERMINAL_CLAIM`() {
        val decision = coordinator.evaluateEmit(terminalClaimContext())
        assertInstanceOf(LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth::class.java, decision)
    }

    @Test
    fun `EmitAsAuthoritativeLocalTruth truthClass is LOCAL_TERMINAL_CLAIM when terminal claim`() {
        val decision = coordinator.evaluateEmit(terminalClaimContext()) as LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_TERMINAL_CLAIM,
            decision.truthClass
        )
    }

    // ── evaluateEmit — EXECUTION_EVIDENCE ─────────────────────────────────────

    @Test
    fun `evaluateEmit returns EmitAsExecutionEvidence for EXECUTION_EVIDENCE`() {
        val decision = coordinator.evaluateEmit(evidenceContext())
        assertInstanceOf(LocalTruthEmitDecision.EmitAsExecutionEvidence::class.java, decision)
    }

    @Test
    fun `EmitAsExecutionEvidence semanticTag is DECISION_EMIT_AS_EXECUTION_EVIDENCE`() {
        val decision = coordinator.evaluateEmit(evidenceContext()) as LocalTruthEmitDecision.EmitAsExecutionEvidence
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_EXECUTION_EVIDENCE,
            decision.semanticTag
        )
    }

    @Test
    fun `EmitAsExecutionEvidence unitId matches context unitId`() {
        val decision = coordinator.evaluateEmit(evidenceContext("unit-evidence-7")) as LocalTruthEmitDecision.EmitAsExecutionEvidence
        assertEquals("unit-evidence-7", decision.unitId)
    }

    // ── evaluateEmit — PARTIAL_RESULT_TRUTH ───────────────────────────────────

    @Test
    fun `evaluateEmit returns EmitAsExecutionEvidence for PARTIAL_RESULT_TRUTH`() {
        val decision = coordinator.evaluateEmit(partialResultContext())
        assertInstanceOf(LocalTruthEmitDecision.EmitAsExecutionEvidence::class.java, decision)
    }

    @Test
    fun `EmitAsExecutionEvidence truthClass is PARTIAL_RESULT_TRUTH when partial result`() {
        val decision = coordinator.evaluateEmit(partialResultContext()) as LocalTruthEmitDecision.EmitAsExecutionEvidence
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.TruthClass.PARTIAL_RESULT_TRUTH,
            decision.truthClass
        )
    }

    // ── evaluateEmit — ADVISORY_LOCAL_TRUTH ───────────────────────────────────

    @Test
    fun `evaluateEmit returns EmitAsAdvisoryOnly for ADVISORY_LOCAL_TRUTH`() {
        val decision = coordinator.evaluateEmit(advisoryContext())
        assertInstanceOf(LocalTruthEmitDecision.EmitAsAdvisoryOnly::class.java, decision)
    }

    @Test
    fun `EmitAsAdvisoryOnly semanticTag is DECISION_EMIT_AS_ADVISORY_ONLY`() {
        val decision = coordinator.evaluateEmit(advisoryContext()) as LocalTruthEmitDecision.EmitAsAdvisoryOnly
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_ADVISORY_ONLY,
            decision.semanticTag
        )
    }

    @Test
    fun `EmitAsAdvisoryOnly unitId matches context unitId`() {
        val decision = coordinator.evaluateEmit(advisoryContext("unit-advisory-9")) as LocalTruthEmitDecision.EmitAsAdvisoryOnly
        assertEquals("unit-advisory-9", decision.unitId)
    }

    // ── evaluateEmit — POSTURE_BOUND_TRUTH with matching posture ──────────────

    @Test
    fun `evaluateEmit returns EmitAsAdvisoryOnly when generated posture equals current posture`() {
        val decision = coordinator.evaluateEmit(
            postureBoundContext(
                generatedPosture = SourceRuntimePosture.JOIN_RUNTIME,
                currentPosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertInstanceOf(LocalTruthEmitDecision.EmitAsAdvisoryOnly::class.java, decision)
    }

    // ── evaluateEmit — POSTURE_BOUND_TRUTH with changed posture (conflict) ────

    @Test
    fun `evaluateEmit returns SuppressDueToPostureConflict when postures differ`() {
        val decision = coordinator.evaluateEmit(
            postureBoundContext(
                generatedPosture = SourceRuntimePosture.JOIN_RUNTIME,
                currentPosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertInstanceOf(LocalTruthEmitDecision.SuppressDueToPostureConflict::class.java, decision)
    }

    @Test
    fun `SuppressDueToPostureConflict semanticTag is DECISION_SUPPRESS_DUE_TO_POSTURE_CONFLICT`() {
        val decision = coordinator.evaluateEmit(
            postureBoundContext(
                generatedPosture = SourceRuntimePosture.JOIN_RUNTIME,
                currentPosture = SourceRuntimePosture.CONTROL_ONLY
            )
        ) as LocalTruthEmitDecision.SuppressDueToPostureConflict
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.DECISION_SUPPRESS_DUE_TO_POSTURE_CONFLICT,
            decision.semanticTag
        )
    }

    @Test
    fun `SuppressDueToPostureConflict generatedPosture matches the generated posture`() {
        val decision = coordinator.evaluateEmit(
            postureBoundContext(
                generatedPosture = SourceRuntimePosture.JOIN_RUNTIME,
                currentPosture = SourceRuntimePosture.CONTROL_ONLY
            )
        ) as LocalTruthEmitDecision.SuppressDueToPostureConflict
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, decision.generatedPosture)
    }

    @Test
    fun `SuppressDueToPostureConflict currentPosture matches the current posture`() {
        val decision = coordinator.evaluateEmit(
            postureBoundContext(
                generatedPosture = SourceRuntimePosture.JOIN_RUNTIME,
                currentPosture = SourceRuntimePosture.CONTROL_ONLY
            )
        ) as LocalTruthEmitDecision.SuppressDueToPostureConflict
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, decision.currentPosture)
    }

    @Test
    fun `SuppressDueToPostureConflict unitId matches context unitId`() {
        val decision = coordinator.evaluateEmit(
            postureBoundContext(
                unitId = "unit-posture-conflict",
                generatedPosture = SourceRuntimePosture.JOIN_RUNTIME,
                currentPosture = SourceRuntimePosture.CONTROL_ONLY
            )
        ) as LocalTruthEmitDecision.SuppressDueToPostureConflict
        assertEquals("unit-posture-conflict", decision.unitId)
    }

    // ── evaluateEmit — POSTURE_BOUND_TRUTH with null postures ─────────────────

    @Test
    fun `evaluateEmit returns EmitAsAdvisoryOnly when generatedPosture is null`() {
        val decision = coordinator.evaluateEmit(
            context(
                truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.POSTURE_BOUND_TRUTH,
                generatedPosture = null,
                currentPosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertInstanceOf(LocalTruthEmitDecision.EmitAsAdvisoryOnly::class.java, decision)
    }

    @Test
    fun `evaluateEmit returns EmitAsAdvisoryOnly when currentPosture is null`() {
        val decision = coordinator.evaluateEmit(
            context(
                truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.POSTURE_BOUND_TRUTH,
                generatedPosture = SourceRuntimePosture.JOIN_RUNTIME,
                currentPosture = null
            )
        )
        assertInstanceOf(LocalTruthEmitDecision.EmitAsAdvisoryOnly::class.java, decision)
    }

    @Test
    fun `evaluateEmit returns EmitAsAdvisoryOnly when both postures are null`() {
        val decision = coordinator.evaluateEmit(
            context(
                truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.POSTURE_BOUND_TRUTH,
                generatedPosture = null,
                currentPosture = null
            )
        )
        assertInstanceOf(LocalTruthEmitDecision.EmitAsAdvisoryOnly::class.java, decision)
    }

    // ── evaluateEmit — local terminal suppression ─────────────────────────────

    @Test
    fun `evaluateEmit returns SuppressDueToLocalTerminal after markLocalTerminal`() {
        coordinator.markLocalTerminal("unit-terminal", "completed")
        val decision = coordinator.evaluateEmit(authoritativeContext("unit-terminal"))
        assertInstanceOf(LocalTruthEmitDecision.SuppressDueToLocalTerminal::class.java, decision)
    }

    @Test
    fun `SuppressDueToLocalTerminal semanticTag is DECISION_SUPPRESS_DUE_TO_LOCAL_TERMINAL`() {
        coordinator.markLocalTerminal("unit-t", "failed")
        val decision = coordinator.evaluateEmit(authoritativeContext("unit-t")) as LocalTruthEmitDecision.SuppressDueToLocalTerminal
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.DECISION_SUPPRESS_DUE_TO_LOCAL_TERMINAL,
            decision.semanticTag
        )
    }

    @Test
    fun `SuppressDueToLocalTerminal unitId matches the terminal unit`() {
        coordinator.markLocalTerminal("unit-suppress-id", "cancelled")
        val decision = coordinator.evaluateEmit(authoritativeContext("unit-suppress-id")) as LocalTruthEmitDecision.SuppressDueToLocalTerminal
        assertEquals("unit-suppress-id", decision.unitId)
    }

    @Test
    fun `SuppressDueToLocalTerminal terminalKind matches the registered kind`() {
        coordinator.markLocalTerminal("unit-x", "timed_out")
        val decision = coordinator.evaluateEmit(authoritativeContext("unit-x")) as LocalTruthEmitDecision.SuppressDueToLocalTerminal
        assertEquals("timed_out", decision.terminalKind)
    }

    @Test
    fun `terminal suppression takes priority over central alignment hold`() {
        coordinator.markLocalTerminal("unit-priority", "completed")
        coordinator.closeTruthEmitGate("unit-priority")
        val decision = coordinator.evaluateEmit(authoritativeContext("unit-priority"))
        assertInstanceOf(LocalTruthEmitDecision.SuppressDueToLocalTerminal::class.java, decision)
    }

    @Test
    fun `terminal suppression takes priority over posture conflict`() {
        coordinator.markLocalTerminal("unit-posture-terminal", "completed")
        val decision = coordinator.evaluateEmit(
            postureBoundContext(
                unitId = "unit-posture-terminal",
                generatedPosture = SourceRuntimePosture.JOIN_RUNTIME,
                currentPosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertInstanceOf(LocalTruthEmitDecision.SuppressDueToLocalTerminal::class.java, decision)
    }

    @Test
    fun `different unitId is not suppressed after marking another unit terminal`() {
        coordinator.markLocalTerminal("unit-a", "completed")
        val decision = coordinator.evaluateEmit(evidenceContext("unit-b"))
        assertInstanceOf(LocalTruthEmitDecision.EmitAsExecutionEvidence::class.java, decision)
    }

    // ── evaluateEmit — central alignment hold ─────────────────────────────────

    @Test
    fun `evaluateEmit returns HoldForCentralAlignment after closeTruthEmitGate`() {
        coordinator.closeTruthEmitGate("unit-gated")
        val decision = coordinator.evaluateEmit(authoritativeContext("unit-gated"))
        assertInstanceOf(LocalTruthEmitDecision.HoldForCentralAlignment::class.java, decision)
    }

    @Test
    fun `HoldForCentralAlignment semanticTag is DECISION_HOLD_FOR_CENTRAL_ALIGNMENT`() {
        coordinator.closeTruthEmitGate("unit-hold")
        val decision = coordinator.evaluateEmit(authoritativeContext("unit-hold")) as LocalTruthEmitDecision.HoldForCentralAlignment
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.DECISION_HOLD_FOR_CENTRAL_ALIGNMENT,
            decision.semanticTag
        )
    }

    @Test
    fun `HoldForCentralAlignment unitId matches the gated unit`() {
        coordinator.closeTruthEmitGate("unit-hold-id")
        val decision = coordinator.evaluateEmit(authoritativeContext("unit-hold-id")) as LocalTruthEmitDecision.HoldForCentralAlignment
        assertEquals("unit-hold-id", decision.unitId)
    }

    @Test
    fun `central alignment hold takes priority over posture conflict`() {
        coordinator.closeTruthEmitGate("unit-hold-posture")
        val decision = coordinator.evaluateEmit(
            postureBoundContext(
                unitId = "unit-hold-posture",
                generatedPosture = SourceRuntimePosture.JOIN_RUNTIME,
                currentPosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertInstanceOf(LocalTruthEmitDecision.HoldForCentralAlignment::class.java, decision)
    }

    @Test
    fun `after openTruthEmitGate evaluateEmit returns normal decision`() {
        coordinator.closeTruthEmitGate("unit-gated-then-open")
        coordinator.openTruthEmitGate("unit-gated-then-open")
        val decision = coordinator.evaluateEmit(authoritativeContext("unit-gated-then-open"))
        assertInstanceOf(LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth::class.java, decision)
    }

    @Test
    fun `different unitId is not held after gating another unit`() {
        coordinator.closeTruthEmitGate("unit-a-gated")
        val decision = coordinator.evaluateEmit(evidenceContext("unit-b-free"))
        assertInstanceOf(LocalTruthEmitDecision.EmitAsExecutionEvidence::class.java, decision)
    }

    // ── Local terminal registry ───────────────────────────────────────────────

    @Test
    fun `isLocalTerminal is false initially`() {
        assertFalse(coordinator.isLocalTerminal("unit-new"))
    }

    @Test
    fun `isLocalTerminal is true after markLocalTerminal`() {
        coordinator.markLocalTerminal("unit-mark", "completed")
        assertTrue(coordinator.isLocalTerminal("unit-mark"))
    }

    @Test
    fun `isLocalTerminal is false after clearLocalTerminal`() {
        coordinator.markLocalTerminal("unit-clear", "failed")
        coordinator.clearLocalTerminal("unit-clear")
        assertFalse(coordinator.isLocalTerminal("unit-clear"))
    }

    @Test
    fun `localTerminalKind is null when not terminal`() {
        assertNull(coordinator.localTerminalKind("unit-no-kind"))
    }

    @Test
    fun `localTerminalKind returns the registered kind after markLocalTerminal`() {
        coordinator.markLocalTerminal("unit-kind", "cancelled")
        assertEquals("cancelled", coordinator.localTerminalKind("unit-kind"))
    }

    @Test
    fun `localTerminalCount is 0 initially`() {
        assertEquals(0, coordinator.localTerminalCount)
    }

    @Test
    fun `localTerminalCount increments with markLocalTerminal`() {
        coordinator.markLocalTerminal("unit-count-1", "completed")
        assertEquals(1, coordinator.localTerminalCount)
        coordinator.markLocalTerminal("unit-count-2", "failed")
        assertEquals(2, coordinator.localTerminalCount)
    }

    @Test
    fun `localTerminalCount decrements with clearLocalTerminal`() {
        coordinator.markLocalTerminal("unit-decrement", "completed")
        coordinator.clearLocalTerminal("unit-decrement")
        assertEquals(0, coordinator.localTerminalCount)
    }

    @Test
    fun `clearAllLocalTerminals resets count to 0`() {
        coordinator.markLocalTerminal("unit-a1", "completed")
        coordinator.markLocalTerminal("unit-a2", "failed")
        coordinator.clearAllLocalTerminals()
        assertEquals(0, coordinator.localTerminalCount)
    }

    @Test
    fun `multiple distinct unitIds can all be registered as terminal simultaneously`() {
        coordinator.markLocalTerminal("unit-multi-1", "completed")
        coordinator.markLocalTerminal("unit-multi-2", "failed")
        coordinator.markLocalTerminal("unit-multi-3", "cancelled")
        assertEquals(3, coordinator.localTerminalCount)
        assertTrue(coordinator.isLocalTerminal("unit-multi-1"))
        assertTrue(coordinator.isLocalTerminal("unit-multi-2"))
        assertTrue(coordinator.isLocalTerminal("unit-multi-3"))
    }

    // ── Truth emit gate ───────────────────────────────────────────────────────

    @Test
    fun `isTruthEmitGated is false initially`() {
        assertFalse(coordinator.isTruthEmitGated("unit-gate-new"))
    }

    @Test
    fun `isTruthEmitGated is true after closeTruthEmitGate`() {
        coordinator.closeTruthEmitGate("unit-gate-close")
        assertTrue(coordinator.isTruthEmitGated("unit-gate-close"))
    }

    @Test
    fun `isTruthEmitGated is false after openTruthEmitGate`() {
        coordinator.closeTruthEmitGate("unit-gate-open")
        coordinator.openTruthEmitGate("unit-gate-open")
        assertFalse(coordinator.isTruthEmitGated("unit-gate-open"))
    }

    @Test
    fun `gatedUnitCount is 0 initially`() {
        assertEquals(0, coordinator.gatedUnitCount)
    }

    @Test
    fun `gatedUnitCount increments with closeTruthEmitGate`() {
        coordinator.closeTruthEmitGate("unit-gate-inc-1")
        assertEquals(1, coordinator.gatedUnitCount)
        coordinator.closeTruthEmitGate("unit-gate-inc-2")
        assertEquals(2, coordinator.gatedUnitCount)
    }

    @Test
    fun `gatedUnitCount decrements with openTruthEmitGate`() {
        coordinator.closeTruthEmitGate("unit-gate-dec")
        coordinator.openTruthEmitGate("unit-gate-dec")
        assertEquals(0, coordinator.gatedUnitCount)
    }

    @Test
    fun `clearAllTruthEmitGates resets count to 0`() {
        coordinator.closeTruthEmitGate("unit-gate-clear-1")
        coordinator.closeTruthEmitGate("unit-gate-clear-2")
        coordinator.clearAllTruthEmitGates()
        assertEquals(0, coordinator.gatedUnitCount)
    }

    @Test
    fun `multiple distinct unitIds can all be gated simultaneously`() {
        coordinator.closeTruthEmitGate("unit-multi-gate-1")
        coordinator.closeTruthEmitGate("unit-multi-gate-2")
        coordinator.closeTruthEmitGate("unit-multi-gate-3")
        assertEquals(3, coordinator.gatedUnitCount)
        assertTrue(coordinator.isTruthEmitGated("unit-multi-gate-1"))
        assertTrue(coordinator.isTruthEmitGated("unit-multi-gate-2"))
        assertTrue(coordinator.isTruthEmitGated("unit-multi-gate-3"))
    }

    // ── LocalTruthEmitDecision — semanticTag values ───────────────────────────

    @Test
    fun `EmitAsAuthoritativeLocalTruth semanticTag matches DECISION_EMIT_AS_AUTHORITATIVE_LOCAL_TRUTH`() {
        val d = LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth(
            unitId = "u",
            truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_AUTHORITATIVE_ASSERTION,
            reason = "test"
        )
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_AUTHORITATIVE_LOCAL_TRUTH,
            d.semanticTag
        )
    }

    @Test
    fun `EmitAsExecutionEvidence semanticTag matches DECISION_EMIT_AS_EXECUTION_EVIDENCE`() {
        val d = LocalTruthEmitDecision.EmitAsExecutionEvidence(
            unitId = "u",
            truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.EXECUTION_EVIDENCE
        )
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_EXECUTION_EVIDENCE,
            d.semanticTag
        )
    }

    @Test
    fun `EmitAsAdvisoryOnly semanticTag matches DECISION_EMIT_AS_ADVISORY_ONLY`() {
        val d = LocalTruthEmitDecision.EmitAsAdvisoryOnly(
            unitId = "u",
            truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.ADVISORY_LOCAL_TRUTH
        )
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_ADVISORY_ONLY,
            d.semanticTag
        )
    }

    @Test
    fun `SuppressDueToLocalTerminal semanticTag matches DECISION_SUPPRESS_DUE_TO_LOCAL_TERMINAL`() {
        val d = LocalTruthEmitDecision.SuppressDueToLocalTerminal(
            unitId = "u",
            terminalKind = "completed"
        )
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.DECISION_SUPPRESS_DUE_TO_LOCAL_TERMINAL,
            d.semanticTag
        )
    }

    @Test
    fun `SuppressDueToPostureConflict semanticTag matches DECISION_SUPPRESS_DUE_TO_POSTURE_CONFLICT`() {
        val d = LocalTruthEmitDecision.SuppressDueToPostureConflict(
            unitId = "u",
            generatedPosture = SourceRuntimePosture.JOIN_RUNTIME,
            currentPosture = SourceRuntimePosture.CONTROL_ONLY
        )
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.DECISION_SUPPRESS_DUE_TO_POSTURE_CONFLICT,
            d.semanticTag
        )
    }

    @Test
    fun `HoldForCentralAlignment semanticTag matches DECISION_HOLD_FOR_CENTRAL_ALIGNMENT`() {
        val d = LocalTruthEmitDecision.HoldForCentralAlignment(
            unitId = "u",
            reason = "reconnect"
        )
        assertEquals(
            AndroidLocalTruthOwnershipCoordinator.DECISION_HOLD_FOR_CENTRAL_ALIGNMENT,
            d.semanticTag
        )
    }

    @Test
    fun `all six semanticTag values are distinct`() {
        val tags = setOf(
            LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth(
                unitId = "u",
                truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_AUTHORITATIVE_ASSERTION,
                reason = ""
            ).semanticTag,
            LocalTruthEmitDecision.EmitAsExecutionEvidence(
                unitId = "u",
                truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.EXECUTION_EVIDENCE
            ).semanticTag,
            LocalTruthEmitDecision.EmitAsAdvisoryOnly(
                unitId = "u",
                truthClass = AndroidLocalTruthOwnershipCoordinator.TruthClass.ADVISORY_LOCAL_TRUTH
            ).semanticTag,
            LocalTruthEmitDecision.SuppressDueToLocalTerminal(unitId = "u", terminalKind = "completed").semanticTag,
            LocalTruthEmitDecision.SuppressDueToPostureConflict(
                unitId = "u",
                generatedPosture = SourceRuntimePosture.JOIN_RUNTIME,
                currentPosture = SourceRuntimePosture.CONTROL_ONLY
            ).semanticTag,
            LocalTruthEmitDecision.HoldForCentralAlignment(unitId = "u", reason = "test").semanticTag
        )
        assertEquals(6, tags.size)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun <T> assertInstanceOf(clazz: Class<T>, actual: Any?) {
        assertNotNull("Expected instance of ${clazz.simpleName} but got null", actual)
        assertTrue(
            "Expected instance of ${clazz.simpleName} but got ${actual!!::class.simpleName}",
            clazz.isInstance(actual)
        )
    }
}
