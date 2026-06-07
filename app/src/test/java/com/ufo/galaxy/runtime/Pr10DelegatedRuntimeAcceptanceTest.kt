package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR-10 (Android) — Unit tests for [DelegatedRuntimeAcceptanceEvaluator],
 * [DelegatedRuntimeAcceptanceDimension], [DeviceAcceptanceArtifact], and
 * [DelegatedRuntimeAcceptanceSnapshot].
 *
 * ## Test matrix
 *
 * ### DelegatedRuntimeAcceptanceEvaluator — constants
 *  - ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION wire value is "device_accepted_for_graduation"
 *  - ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE wire value is "device_rejected_due_to_missing_evidence"
 *  - ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP wire value is "device_rejected_due_to_truth_gap"
 *  - ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP wire value is "device_rejected_due_to_result_gap"
 *  - ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP wire value is "device_rejected_due_to_execution_event_gap"
 *  - ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK wire value is "device_rejected_due_to_compat_bypass_risk"
 *  - ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL wire value is "device_acceptance_unknown_due_to_incomplete_signal"
 *  - All seven artifact constants are distinct
 *  - INTEGRATION_READINESS_EVALUATOR is "DelegatedRuntimeReadinessEvaluator"
 *  - INTEGRATION_RECOVERY_OWNER is "AndroidRecoveryParticipationOwner"
 *  - INTEGRATION_TRUTH_OWNER is "AndroidLocalTruthOwnershipCoordinator"
 *  - INTEGRATION_RESULT_CONVERGENCE is "AndroidFlowAwareResultConvergenceParticipant"
 *  - INTEGRATION_EXECUTION_EVENT_OWNER is "AndroidCanonicalExecutionEventOwner"
 *  - INTEGRATION_COMPAT_BLOCKING is "AndroidCompatLegacyBlockingParticipant"
 *  - INTEGRATION_RUNTIME_CONTROLLER is "RuntimeController"
 *  - INTEGRATION_DELEGATED_FLOW_BRIDGE is "AndroidDelegatedFlowBridge"
 *  - INTRODUCED_PR is 10
 *  - DESCRIPTION is non-blank
 *
 * ### DelegatedRuntimeAcceptanceDimension — wire values
 *  - READINESS_PREREQUISITE wireValue is "readiness_prerequisite"
 *  - CONTINUITY_REPLAY_RECONNECT_EVIDENCE wireValue is "continuity_replay_reconnect_evidence"
 *  - TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE wireValue is "truth_ownership_alignment_evidence"
 *  - RESULT_CONVERGENCE_EVIDENCE wireValue is "result_convergence_evidence"
 *  - CANONICAL_EXECUTION_EVENT_EVIDENCE wireValue is "canonical_execution_event_evidence"
 *  - COMPAT_LEGACY_BLOCKING_EVIDENCE wireValue is "compat_legacy_blocking_evidence"
 *  - All six wire values are distinct
 *
 * ### DelegatedRuntimeAcceptanceDimension.fromValue — classification
 *  - "readiness_prerequisite" → READINESS_PREREQUISITE
 *  - "continuity_replay_reconnect_evidence" → CONTINUITY_REPLAY_RECONNECT_EVIDENCE
 *  - "truth_ownership_alignment_evidence" → TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE
 *  - "result_convergence_evidence" → RESULT_CONVERGENCE_EVIDENCE
 *  - "canonical_execution_event_evidence" → CANONICAL_EXECUTION_EVENT_EVIDENCE
 *  - "compat_legacy_blocking_evidence" → COMPAT_LEGACY_BLOCKING_EVIDENCE
 *  - unknown value → null
 *  - null → null
 *
 * ### Dimension gate — markDimensionEvidenced / getDimensionStatus
 *  - getDimensionStatus returns UNKNOWN before any signal
 *  - getDimensionStatus returns EVIDENCED after markDimensionEvidenced
 *  - getDimensionStatus returns GAP after markDimensionGap
 *  - getDimensionStatus returns UNKNOWN after markDimensionUnknown
 *  - getDimensionGapReason is null before any signal
 *  - getDimensionGapReason returns gapReason after markDimensionGap
 *  - getDimensionGapReason returns reason after markDimensionUnknown
 *  - getDimensionGapReason is null after markDimensionEvidenced
 *  - clearAllDimensionStates resets all dimensions to UNKNOWN
 *
 * ### evaluateAcceptance — DeviceAcceptanceUnknownDueToIncompleteSignal
 *  - Returns DeviceAcceptanceUnknownDueToIncompleteSignal when no dimensions have signals
 *  - Returns DeviceAcceptanceUnknownDueToIncompleteSignal when some dimensions are UNKNOWN
 *  - DeviceAcceptanceUnknownDueToIncompleteSignal.semanticTag is ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL
 *  - DeviceAcceptanceUnknownDueToIncompleteSignal.deviceId matches parameter
 *  - DeviceAcceptanceUnknownDueToIncompleteSignal.snapshotId matches parameter
 *  - DeviceAcceptanceUnknownDueToIncompleteSignal.missingDimensions includes all six when no signals
 *  - DeviceAcceptanceUnknownDueToIncompleteSignal.missingDimensions only includes unknown dimensions
 *  - Incomplete signal takes priority over missing evidence gap
 *  - Incomplete signal takes priority over truth gap
 *  - Incomplete signal takes priority over result gap
 *  - Incomplete signal takes priority over execution event gap
 *  - Incomplete signal takes priority over compat bypass risk
 *
 * ### evaluateAcceptance — DeviceRejectedDueToMissingEvidence
 *  - Returns DeviceRejectedDueToMissingEvidence when readiness prerequisite dimension has GAP
 *  - DeviceRejectedDueToMissingEvidence.semanticTag is ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE
 *  - DeviceRejectedDueToMissingEvidence.deviceId matches parameter
 *  - DeviceRejectedDueToMissingEvidence.snapshotId matches parameter
 *  - DeviceRejectedDueToMissingEvidence.gapReason matches registered reason
 *  - DeviceRejectedDueToMissingEvidence.gapReason uses REASON_READINESS_PREREQUISITE_GAP_DEFAULT when none registered
 *  - DeviceRejectedDueToMissingEvidence.dimension is READINESS_PREREQUISITE
 *  - Missing evidence takes priority over truth gap
 *  - Missing evidence takes priority over result gap
 *  - Missing evidence takes priority over execution event gap
 *  - Missing evidence takes priority over compat bypass risk
 *
 * ### evaluateAcceptance — DeviceRejectedDueToTruthGap
 *  - Returns DeviceRejectedDueToTruthGap when truth dimension has GAP
 *  - DeviceRejectedDueToTruthGap.semanticTag is ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP
 *  - DeviceRejectedDueToTruthGap.deviceId matches parameter
 *  - DeviceRejectedDueToTruthGap.snapshotId matches parameter
 *  - DeviceRejectedDueToTruthGap.gapReason matches registered reason
 *  - DeviceRejectedDueToTruthGap.gapReason uses REASON_TRUTH_GAP_DEFAULT when none registered
 *  - DeviceRejectedDueToTruthGap.dimension is TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE
 *  - Truth gap takes priority over result gap
 *  - Truth gap takes priority over execution event gap
 *  - Truth gap takes priority over compat bypass risk
 *
 * ### evaluateAcceptance — DeviceRejectedDueToResultGap
 *  - Returns DeviceRejectedDueToResultGap when result dimension has GAP
 *  - DeviceRejectedDueToResultGap.semanticTag is ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP
 *  - DeviceRejectedDueToResultGap.deviceId matches parameter
 *  - DeviceRejectedDueToResultGap.snapshotId matches parameter
 *  - DeviceRejectedDueToResultGap.gapReason matches registered reason
 *  - DeviceRejectedDueToResultGap.gapReason uses REASON_RESULT_GAP_DEFAULT when none registered
 *  - DeviceRejectedDueToResultGap.dimension is RESULT_CONVERGENCE_EVIDENCE
 *  - Result gap takes priority over execution event gap
 *  - Result gap takes priority over compat bypass risk
 *
 * ### evaluateAcceptance — DeviceRejectedDueToExecutionEventGap
 *  - Returns DeviceRejectedDueToExecutionEventGap when execution event dimension has GAP
 *  - DeviceRejectedDueToExecutionEventGap.semanticTag is ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP
 *  - DeviceRejectedDueToExecutionEventGap.deviceId matches parameter
 *  - DeviceRejectedDueToExecutionEventGap.snapshotId matches parameter
 *  - DeviceRejectedDueToExecutionEventGap.gapReason matches registered reason
 *  - DeviceRejectedDueToExecutionEventGap.gapReason uses REASON_EXECUTION_EVENT_GAP_DEFAULT when none registered
 *  - DeviceRejectedDueToExecutionEventGap.dimension is CANONICAL_EXECUTION_EVENT_EVIDENCE
 *  - Execution event gap takes priority over compat bypass risk
 *
 * ### evaluateAcceptance — DeviceRejectedDueToCompatBypassRisk
 *  - Returns DeviceRejectedDueToCompatBypassRisk when compat dimension has GAP
 *  - DeviceRejectedDueToCompatBypassRisk.semanticTag is ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK
 *  - DeviceRejectedDueToCompatBypassRisk.deviceId matches parameter
 *  - DeviceRejectedDueToCompatBypassRisk.snapshotId matches parameter
 *  - DeviceRejectedDueToCompatBypassRisk.gapReason matches registered reason
 *  - DeviceRejectedDueToCompatBypassRisk.gapReason uses REASON_COMPAT_BYPASS_RISK_DEFAULT when none registered
 *  - DeviceRejectedDueToCompatBypassRisk.dimension is COMPAT_LEGACY_BLOCKING_EVIDENCE
 *
 * ### evaluateAcceptance — Continuity gap (maps to truth gap artifact)
 *  - Returns DeviceRejectedDueToTruthGap when continuity evidence dimension has GAP
 *  - DeviceRejectedDueToTruthGap.dimension is CONTINUITY_REPLAY_RECONNECT_EVIDENCE for continuity gap
 *  - DeviceRejectedDueToTruthGap.gapReason uses REASON_CONTINUITY_GAP_DEFAULT for continuity gap without reason
 *
 * ### evaluateAcceptance — DeviceAcceptedForGraduation
 *  - Returns DeviceAcceptedForGraduation when all six dimensions are EVIDENCED
 *  - DeviceAcceptedForGraduation.semanticTag is ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION
 *  - DeviceAcceptedForGraduation.deviceId matches parameter
 *  - DeviceAcceptedForGraduation.snapshotId matches parameter
 *
 * ### buildSnapshot
 *  - buildSnapshot returns a DelegatedRuntimeAcceptanceSnapshot
 *  - snapshot deviceId matches parameter
 *  - snapshot snapshotId is non-blank
 *  - snapshot reportedAtMs is positive
 *  - snapshot dimensionStates contains all six dimensions
 *  - snapshot artifact is DeviceAcceptedForGraduation when all dimensions are EVIDENCED
 *  - snapshot artifact is DeviceAcceptanceUnknownDueToIncompleteSignal when no signals
 *  - snapshot dimensionStates reflect current gate state
 *  - each DimensionEvidenceState.dimension matches its map key
 *  - DimensionEvidenceState.status is UNKNOWN before signals
 *  - DimensionEvidenceState.status is EVIDENCED after markDimensionEvidenced
 *  - DimensionEvidenceState.status is GAP after markDimensionGap
 *  - DimensionEvidenceState.gapReason is null for EVIDENCED dimensions
 *  - DimensionEvidenceState.gapReason matches registered reason for GAP dimensions
 *
 * ### DelegatedRuntimeAcceptanceSnapshot.DimensionStatus — wire values
 *  - EVIDENCED wireValue is "evidenced"
 *  - GAP wireValue is "gap"
 *  - UNKNOWN wireValue is "unknown"
 *  - All three wire values are distinct
 *  - fromValue("evidenced") → EVIDENCED
 *  - fromValue("gap") → GAP
 *  - fromValue("unknown") → UNKNOWN
 *  - fromValue(unknown) → null
 *
 * ### DeviceAcceptanceArtifact — semanticTag values
 *  - DeviceAcceptedForGraduation.semanticTag matches ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION
 *  - DeviceRejectedDueToMissingEvidence.semanticTag matches ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE
 *  - DeviceRejectedDueToTruthGap.semanticTag matches ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP
 *  - DeviceRejectedDueToResultGap.semanticTag matches ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP
 *  - DeviceRejectedDueToExecutionEventGap.semanticTag matches ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP
 *  - DeviceRejectedDueToCompatBypassRisk.semanticTag matches ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK
 *  - DeviceAcceptanceUnknownDueToIncompleteSignal.semanticTag matches ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL
 *  - All seven semanticTag values are distinct
 */
class Pr10DelegatedRuntimeAcceptanceTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private lateinit var evaluator: DelegatedRuntimeAcceptanceEvaluator

    @Before
    fun setUp() {
        evaluator = DelegatedRuntimeAcceptanceEvaluator()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun markAllEvidenced() {
        DelegatedRuntimeAcceptanceDimension.entries.forEach {
            evaluator.markDimensionEvidenced(it)
        }
    }

    private fun markAllExcept(
        vararg except: DelegatedRuntimeAcceptanceDimension
    ) {
        DelegatedRuntimeAcceptanceDimension.entries
            .filter { it !in except }
            .forEach { evaluator.markDimensionEvidenced(it) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeAcceptanceEvaluator — constants
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION wire value is device_accepted_for_graduation`() {
        assertEquals(
            "device_accepted_for_graduation",
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE wire value is device_rejected_due_to_missing_evidence`() {
        assertEquals(
            "device_rejected_due_to_missing_evidence",
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP wire value is device_rejected_due_to_truth_gap`() {
        assertEquals(
            "device_rejected_due_to_truth_gap",
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP wire value is device_rejected_due_to_result_gap`() {
        assertEquals(
            "device_rejected_due_to_result_gap",
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP wire value is device_rejected_due_to_execution_event_gap`() {
        assertEquals(
            "device_rejected_due_to_execution_event_gap",
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK wire value is device_rejected_due_to_compat_bypass_risk`() {
        assertEquals(
            "device_rejected_due_to_compat_bypass_risk",
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL wire value is device_acceptance_unknown_due_to_incomplete_signal`() {
        assertEquals(
            "device_acceptance_unknown_due_to_incomplete_signal",
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL
        )
    }

    @Test
    fun `All seven artifact constants are distinct`() {
        val constants = listOf(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION,
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE,
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP,
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP,
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP,
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK,
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL
        )
        assertEquals("All seven artifact constants must be distinct", constants.size, constants.toSet().size)
    }

    @Test
    fun `INTEGRATION_READINESS_EVALUATOR is DelegatedRuntimeReadinessEvaluator`() {
        assertEquals(
            "DelegatedRuntimeReadinessEvaluator",
            DelegatedRuntimeAcceptanceEvaluator.INTEGRATION_READINESS_EVALUATOR
        )
    }

    @Test
    fun `INTEGRATION_RECOVERY_OWNER is AndroidRecoveryParticipationOwner`() {
        assertEquals(
            "AndroidRecoveryParticipationOwner",
            DelegatedRuntimeAcceptanceEvaluator.INTEGRATION_RECOVERY_OWNER
        )
    }

    @Test
    fun `INTEGRATION_TRUTH_OWNER is AndroidLocalTruthOwnershipCoordinator`() {
        assertEquals(
            "AndroidLocalTruthOwnershipCoordinator",
            DelegatedRuntimeAcceptanceEvaluator.INTEGRATION_TRUTH_OWNER
        )
    }

    @Test
    fun `INTEGRATION_RESULT_CONVERGENCE is AndroidFlowAwareResultConvergenceParticipant`() {
        assertEquals(
            "AndroidFlowAwareResultConvergenceParticipant",
            DelegatedRuntimeAcceptanceEvaluator.INTEGRATION_RESULT_CONVERGENCE
        )
    }

    @Test
    fun `INTEGRATION_EXECUTION_EVENT_OWNER is AndroidCanonicalExecutionEventOwner`() {
        assertEquals(
            "AndroidCanonicalExecutionEventOwner",
            DelegatedRuntimeAcceptanceEvaluator.INTEGRATION_EXECUTION_EVENT_OWNER
        )
    }

    @Test
    fun `INTEGRATION_COMPAT_BLOCKING is AndroidCompatLegacyBlockingParticipant`() {
        assertEquals(
            "AndroidCompatLegacyBlockingParticipant",
            DelegatedRuntimeAcceptanceEvaluator.INTEGRATION_COMPAT_BLOCKING
        )
    }

    @Test
    fun `INTEGRATION_RUNTIME_CONTROLLER is RuntimeController`() {
        assertEquals(
            "RuntimeController",
            DelegatedRuntimeAcceptanceEvaluator.INTEGRATION_RUNTIME_CONTROLLER
        )
    }

    @Test
    fun `INTEGRATION_DELEGATED_FLOW_BRIDGE is AndroidDelegatedFlowBridge`() {
        assertEquals(
            "AndroidDelegatedFlowBridge",
            DelegatedRuntimeAcceptanceEvaluator.INTEGRATION_DELEGATED_FLOW_BRIDGE
        )
    }

    @Test
    fun `INTRODUCED_PR is 10`() {
        assertEquals(10, DelegatedRuntimeAcceptanceEvaluator.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(DelegatedRuntimeAcceptanceEvaluator.DESCRIPTION.isNotBlank())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeAcceptanceDimension — wire values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `READINESS_PREREQUISITE wireValue is readiness_prerequisite`() {
        assertEquals(
            "readiness_prerequisite",
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE.wireValue
        )
    }

    @Test
    fun `CONTINUITY_REPLAY_RECONNECT_EVIDENCE wireValue is continuity_replay_reconnect_evidence`() {
        assertEquals(
            "continuity_replay_reconnect_evidence",
            DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE.wireValue
        )
    }

    @Test
    fun `TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE wireValue is truth_ownership_alignment_evidence`() {
        assertEquals(
            "truth_ownership_alignment_evidence",
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE.wireValue
        )
    }

    @Test
    fun `RESULT_CONVERGENCE_EVIDENCE wireValue is result_convergence_evidence`() {
        assertEquals(
            "result_convergence_evidence",
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE.wireValue
        )
    }

    @Test
    fun `CANONICAL_EXECUTION_EVENT_EVIDENCE wireValue is canonical_execution_event_evidence`() {
        assertEquals(
            "canonical_execution_event_evidence",
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE.wireValue
        )
    }

    @Test
    fun `COMPAT_LEGACY_BLOCKING_EVIDENCE wireValue is compat_legacy_blocking_evidence`() {
        assertEquals(
            "compat_legacy_blocking_evidence",
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE.wireValue
        )
    }

    @Test
    fun `All six dimension wire values are distinct`() {
        val wireValues = DelegatedRuntimeAcceptanceDimension.entries.map { it.wireValue }
        assertEquals(
            "All six dimension wire values must be distinct",
            wireValues.size,
            wireValues.toSet().size
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeAcceptanceDimension.fromValue — classification
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `readiness_prerequisite maps to READINESS_PREREQUISITE`() {
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            DelegatedRuntimeAcceptanceDimension.fromValue("readiness_prerequisite")
        )
    }

    @Test
    fun `continuity_replay_reconnect_evidence maps to CONTINUITY_REPLAY_RECONNECT_EVIDENCE`() {
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE,
            DelegatedRuntimeAcceptanceDimension.fromValue("continuity_replay_reconnect_evidence")
        )
    }

    @Test
    fun `truth_ownership_alignment_evidence maps to TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE`() {
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            DelegatedRuntimeAcceptanceDimension.fromValue("truth_ownership_alignment_evidence")
        )
    }

    @Test
    fun `result_convergence_evidence maps to RESULT_CONVERGENCE_EVIDENCE`() {
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            DelegatedRuntimeAcceptanceDimension.fromValue("result_convergence_evidence")
        )
    }

    @Test
    fun `canonical_execution_event_evidence maps to CANONICAL_EXECUTION_EVENT_EVIDENCE`() {
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            DelegatedRuntimeAcceptanceDimension.fromValue("canonical_execution_event_evidence")
        )
    }

    @Test
    fun `compat_legacy_blocking_evidence maps to COMPAT_LEGACY_BLOCKING_EVIDENCE`() {
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            DelegatedRuntimeAcceptanceDimension.fromValue("compat_legacy_blocking_evidence")
        )
    }

    @Test
    fun `unknown value maps to null`() {
        assertNull(DelegatedRuntimeAcceptanceDimension.fromValue("not_a_dimension"))
    }

    @Test
    fun `null maps to null`() {
        assertNull(DelegatedRuntimeAcceptanceDimension.fromValue(null))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Dimension gate — markDimensionEvidenced / getDimensionStatus
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `getDimensionStatus returns UNKNOWN before any signal`() {
        assertEquals(
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN,
            evaluator.getDimensionStatus(DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE)
        )
    }

    @Test
    fun `getDimensionStatus returns EVIDENCED after markDimensionEvidenced`() {
        evaluator.markDimensionEvidenced(DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE)
        assertEquals(
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.EVIDENCED,
            evaluator.getDimensionStatus(DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE)
        )
    }

    @Test
    fun `getDimensionStatus returns GAP after markDimensionGap`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "no truth assertion confirmed"
        )
        assertEquals(
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP,
            evaluator.getDimensionStatus(
                DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE
            )
        )
    }

    @Test
    fun `getDimensionStatus returns UNKNOWN after markDimensionUnknown`() {
        evaluator.markDimensionEvidenced(DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE)
        evaluator.markDimensionUnknown(DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE)
        assertEquals(
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN,
            evaluator.getDimensionStatus(DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE)
        )
    }

    @Test
    fun `getDimensionGapReason is null before any signal`() {
        assertNull(
            evaluator.getDimensionGapReason(DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE)
        )
    }

    @Test
    fun `getDimensionGapReason returns gapReason after markDimensionGap`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            "convergence gate closed for 5 flows"
        )
        assertEquals(
            "convergence gate closed for 5 flows",
            evaluator.getDimensionGapReason(DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE)
        )
    }

    @Test
    fun `getDimensionGapReason returns reason after markDimensionUnknown`() {
        evaluator.markDimensionUnknown(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            "event channel not initialized"
        )
        assertEquals(
            "event channel not initialized",
            evaluator.getDimensionGapReason(
                DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE
            )
        )
    }

    @Test
    fun `getDimensionGapReason is null after markDimensionEvidenced`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "legacy path still active"
        )
        evaluator.markDimensionEvidenced(DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE)
        assertNull(
            evaluator.getDimensionGapReason(
                DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE
            )
        )
    }

    @Test
    fun `clearAllDimensionStates resets all dimensions to UNKNOWN`() {
        markAllEvidenced()
        evaluator.clearAllDimensionStates()
        DelegatedRuntimeAcceptanceDimension.entries.forEach { dim ->
            assertEquals(
                DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN,
                evaluator.getDimensionStatus(dim)
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateAcceptance — DeviceAcceptanceUnknownDueToIncompleteSignal
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceAcceptanceUnknownDueToIncompleteSignal when no dimensions have signals`() {
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal>(result)
    }

    @Test
    fun `Returns DeviceAcceptanceUnknownDueToIncompleteSignal when some dimensions are UNKNOWN`() {
        markAllExcept(DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE)
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal>(result)
    }

    @Test
    fun `DeviceAcceptanceUnknownDueToIncompleteSignal semanticTag is ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL`() {
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceAcceptanceUnknownDueToIncompleteSignal deviceId matches parameter`() {
        val result = evaluator.evaluateAcceptance("device-unknown-signal", "snap-1") as
            DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal
        assertEquals("device-unknown-signal", result.deviceId)
    }

    @Test
    fun `DeviceAcceptanceUnknownDueToIncompleteSignal snapshotId matches parameter`() {
        val result = evaluator.evaluateAcceptance("dev-1", "snap-unknown-42") as
            DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal
        assertEquals("snap-unknown-42", result.snapshotId)
    }

    @Test
    fun `DeviceAcceptanceUnknownDueToIncompleteSignal missingDimensions includes all six when no signals`() {
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.entries.toSet(),
            result.missingDimensions
        )
    }

    @Test
    fun `DeviceAcceptanceUnknownDueToIncompleteSignal missingDimensions only includes unknown dimensions`() {
        markAllExcept(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal
        assertEquals(
            setOf(
                DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
                DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE
            ),
            result.missingDimensions
        )
    }

    @Test
    fun `Incomplete signal takes priority over missing evidence gap`() {
        evaluator.markDimensionEvidenced(DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE)
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            "readiness not confirmed"
        )
        // CONTINUITY, RESULT, CANONICAL_EVENT, COMPAT remain UNKNOWN
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal>(result)
    }

    @Test
    fun `Incomplete signal takes priority over truth gap`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "truth gap"
        )
        // other dimensions remain UNKNOWN
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal>(result)
    }

    @Test
    fun `Incomplete signal takes priority over result gap`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            "result gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal>(result)
    }

    @Test
    fun `Incomplete signal takes priority over execution event gap`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            "event gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal>(result)
    }

    @Test
    fun `Incomplete signal takes priority over compat bypass risk`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "compat bypass"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal>(result)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateAcceptance — DeviceRejectedDueToMissingEvidence
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceRejectedDueToMissingEvidence when readiness prerequisite dimension has GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            "readiness not reached"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence>(result)
    }

    @Test
    fun `DeviceRejectedDueToMissingEvidence semanticTag is ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            "readiness not reached"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceRejectedDueToMissingEvidence deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            "readiness not reached"
        )
        val result = evaluator.evaluateAcceptance("device-missing-evidence", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence
        assertEquals("device-missing-evidence", result.deviceId)
    }

    @Test
    fun `DeviceRejectedDueToMissingEvidence snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            "readiness not reached"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-missing-99") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence
        assertEquals("snap-missing-99", result.snapshotId)
    }

    @Test
    fun `DeviceRejectedDueToMissingEvidence gapReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            "device readiness artifact was not DeviceReadyForRelease"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence
        assertEquals("device readiness artifact was not DeviceReadyForRelease", result.gapReason)
    }

    @Test
    fun `DeviceRejectedDueToMissingEvidence gapReason uses REASON_READINESS_PREREQUISITE_GAP_DEFAULT when none registered`() {
        markAllExcept(DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE)
        evaluator.markDimensionGap(DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE, "")
        // Re-register with empty reason by marking gap without reason via blank
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            DelegatedRuntimeAcceptanceEvaluator.REASON_READINESS_PREREQUISITE_GAP_DEFAULT
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.REASON_READINESS_PREREQUISITE_GAP_DEFAULT,
            result.gapReason
        )
    }

    @Test
    fun `DeviceRejectedDueToMissingEvidence dimension is READINESS_PREREQUISITE`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            "readiness not reached"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            result.dimension
        )
    }

    @Test
    fun `Missing evidence takes priority over truth gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            "readiness not reached"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "truth gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence>(result)
    }

    @Test
    fun `Missing evidence takes priority over result gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            "readiness not reached"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            "result gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence>(result)
    }

    @Test
    fun `Missing evidence takes priority over execution event gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            "readiness not reached"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            "event gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence>(result)
    }

    @Test
    fun `Missing evidence takes priority over compat bypass risk`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            "readiness not reached"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "compat bypass"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence>(result)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateAcceptance — DeviceRejectedDueToTruthGap
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceRejectedDueToTruthGap when truth dimension has GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "truth alignment not confirmed"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap>(result)
    }

    @Test
    fun `DeviceRejectedDueToTruthGap semanticTag is ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "truth gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceRejectedDueToTruthGap deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "truth gap"
        )
        val result = evaluator.evaluateAcceptance("device-truth-gap", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap
        assertEquals("device-truth-gap", result.deviceId)
    }

    @Test
    fun `DeviceRejectedDueToTruthGap snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "truth gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-truth-77") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap
        assertEquals("snap-truth-77", result.snapshotId)
    }

    @Test
    fun `DeviceRejectedDueToTruthGap gapReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "posture conflict suppressing truth assertions"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap
        assertEquals("posture conflict suppressing truth assertions", result.gapReason)
    }

    @Test
    fun `DeviceRejectedDueToTruthGap gapReason uses REASON_TRUTH_GAP_DEFAULT when none registered`() {
        markAllExcept(DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE)
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            DelegatedRuntimeAcceptanceEvaluator.REASON_TRUTH_GAP_DEFAULT
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.REASON_TRUTH_GAP_DEFAULT,
            result.gapReason
        )
    }

    @Test
    fun `DeviceRejectedDueToTruthGap dimension is TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "truth gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            result.dimension
        )
    }

    @Test
    fun `Truth gap takes priority over result gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "truth gap"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            "result gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap>(result)
    }

    @Test
    fun `Truth gap takes priority over execution event gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "truth gap"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            "event gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap>(result)
    }

    @Test
    fun `Truth gap takes priority over compat bypass risk`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "truth gap"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "compat bypass"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap>(result)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateAcceptance — DeviceRejectedDueToResultGap
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceRejectedDueToResultGap when result dimension has GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            "result convergence evidence missing"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap>(result)
    }

    @Test
    fun `DeviceRejectedDueToResultGap semanticTag is ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            "result gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceRejectedDueToResultGap deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            "result gap"
        )
        val result = evaluator.evaluateAcceptance("device-result-gap", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap
        assertEquals("device-result-gap", result.deviceId)
    }

    @Test
    fun `DeviceRejectedDueToResultGap snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            "result gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-result-55") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap
        assertEquals("snap-result-55", result.snapshotId)
    }

    @Test
    fun `DeviceRejectedDueToResultGap gapReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            "late partials detected after final"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap
        assertEquals("late partials detected after final", result.gapReason)
    }

    @Test
    fun `DeviceRejectedDueToResultGap gapReason uses REASON_RESULT_GAP_DEFAULT when none registered`() {
        markAllExcept(DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE)
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            DelegatedRuntimeAcceptanceEvaluator.REASON_RESULT_GAP_DEFAULT
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.REASON_RESULT_GAP_DEFAULT,
            result.gapReason
        )
    }

    @Test
    fun `DeviceRejectedDueToResultGap dimension is RESULT_CONVERGENCE_EVIDENCE`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            "result gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            result.dimension
        )
    }

    @Test
    fun `Result gap takes priority over execution event gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            "result gap"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            "event gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap>(result)
    }

    @Test
    fun `Result gap takes priority over compat bypass risk`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            "result gap"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "compat bypass"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap>(result)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateAcceptance — DeviceRejectedDueToExecutionEventGap
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceRejectedDueToExecutionEventGap when execution event dimension has GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            "execution event evidence absent"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToExecutionEventGap>(result)
    }

    @Test
    fun `DeviceRejectedDueToExecutionEventGap semanticTag is ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            "event gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToExecutionEventGap
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceRejectedDueToExecutionEventGap deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            "event gap"
        )
        val result = evaluator.evaluateAcceptance("device-event-gap", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToExecutionEventGap
        assertEquals("device-event-gap", result.deviceId)
    }

    @Test
    fun `DeviceRejectedDueToExecutionEventGap snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            "event gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-event-33") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToExecutionEventGap
        assertEquals("snap-event-33", result.snapshotId)
    }

    @Test
    fun `DeviceRejectedDueToExecutionEventGap gapReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            "post-terminal noise not suppressed"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToExecutionEventGap
        assertEquals("post-terminal noise not suppressed", result.gapReason)
    }

    @Test
    fun `DeviceRejectedDueToExecutionEventGap gapReason uses REASON_EXECUTION_EVENT_GAP_DEFAULT when none registered`() {
        markAllExcept(DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE)
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            DelegatedRuntimeAcceptanceEvaluator.REASON_EXECUTION_EVENT_GAP_DEFAULT
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToExecutionEventGap
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.REASON_EXECUTION_EVENT_GAP_DEFAULT,
            result.gapReason
        )
    }

    @Test
    fun `DeviceRejectedDueToExecutionEventGap dimension is CANONICAL_EXECUTION_EVENT_EVIDENCE`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            "event gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToExecutionEventGap
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            result.dimension
        )
    }

    @Test
    fun `Execution event gap takes priority over compat bypass risk`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            "event gap"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "compat bypass"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToExecutionEventGap>(result)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateAcceptance — DeviceRejectedDueToCompatBypassRisk
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceRejectedDueToCompatBypassRisk when compat dimension has GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "legacy path still reaching canonical surface"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk>(result)
    }

    @Test
    fun `DeviceRejectedDueToCompatBypassRisk semanticTag is ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "compat bypass"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceRejectedDueToCompatBypassRisk deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "compat bypass"
        )
        val result = evaluator.evaluateAcceptance("device-compat-bypass", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk
        assertEquals("device-compat-bypass", result.deviceId)
    }

    @Test
    fun `DeviceRejectedDueToCompatBypassRisk snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "compat bypass"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-compat-11") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk
        assertEquals("snap-compat-11", result.snapshotId)
    }

    @Test
    fun `DeviceRejectedDueToCompatBypassRisk gapReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "quarantined units not released after V2 alignment"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk
        assertEquals("quarantined units not released after V2 alignment", result.gapReason)
    }

    @Test
    fun `DeviceRejectedDueToCompatBypassRisk gapReason uses REASON_COMPAT_BYPASS_RISK_DEFAULT when none registered`() {
        markAllExcept(DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE)
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            DelegatedRuntimeAcceptanceEvaluator.REASON_COMPAT_BYPASS_RISK_DEFAULT
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.REASON_COMPAT_BYPASS_RISK_DEFAULT,
            result.gapReason
        )
    }

    @Test
    fun `DeviceRejectedDueToCompatBypassRisk dimension is COMPAT_LEGACY_BLOCKING_EVIDENCE`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "compat bypass"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            result.dimension
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateAcceptance — Continuity gap (maps to truth gap artifact)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceRejectedDueToTruthGap when continuity evidence dimension has GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE,
            "replay boundary not validated"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap>(result)
    }

    @Test
    fun `DeviceRejectedDueToTruthGap dimension is CONTINUITY_REPLAY_RECONNECT_EVIDENCE for continuity gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE,
            "replay boundary not validated"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE,
            result.dimension
        )
    }

    @Test
    fun `DeviceRejectedDueToTruthGap gapReason uses REASON_CONTINUITY_GAP_DEFAULT for continuity gap without reason`() {
        markAllExcept(DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE)
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE,
            DelegatedRuntimeAcceptanceEvaluator.REASON_CONTINUITY_GAP_DEFAULT
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.REASON_CONTINUITY_GAP_DEFAULT,
            result.gapReason
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateAcceptance — DeviceAcceptedForGraduation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceAcceptedForGraduation when all six dimensions are EVIDENCED`() {
        markAllEvidenced()
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1")
        assertIs<DeviceAcceptanceArtifact.DeviceAcceptedForGraduation>(result)
    }

    @Test
    fun `DeviceAcceptedForGraduation semanticTag is ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION`() {
        markAllEvidenced()
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceAcceptedForGraduation
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceAcceptedForGraduation deviceId matches parameter`() {
        markAllEvidenced()
        val result = evaluator.evaluateAcceptance("device-accepted", "snap-1") as
            DeviceAcceptanceArtifact.DeviceAcceptedForGraduation
        assertEquals("device-accepted", result.deviceId)
    }

    @Test
    fun `DeviceAcceptedForGraduation snapshotId matches parameter`() {
        markAllEvidenced()
        val result = evaluator.evaluateAcceptance("dev-1", "snap-accepted-final") as
            DeviceAcceptanceArtifact.DeviceAcceptedForGraduation
        assertEquals("snap-accepted-final", result.snapshotId)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // buildSnapshot
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `buildSnapshot returns a DelegatedRuntimeAcceptanceSnapshot`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertNotNull(snapshot)
    }

    @Test
    fun `snapshot deviceId matches parameter`() {
        val snapshot = evaluator.buildSnapshot("dev-snapshot-id")
        assertEquals("dev-snapshot-id", snapshot.deviceId)
    }

    @Test
    fun `snapshot snapshotId is non-blank`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertTrue(snapshot.snapshotId.isNotBlank())
    }

    @Test
    fun `snapshot reportedAtMs is positive`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertTrue(snapshot.reportedAtMs > 0)
    }

    @Test
    fun `snapshot dimensionStates contains all six dimensions`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeAcceptanceDimension.entries.toSet(),
            snapshot.dimensionStates.keys
        )
    }

    @Test
    fun `snapshot artifact is DeviceAcceptedForGraduation when all dimensions are EVIDENCED`() {
        markAllEvidenced()
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertIs<DeviceAcceptanceArtifact.DeviceAcceptedForGraduation>(snapshot.artifact)
    }

    @Test
    fun `snapshot artifact is DeviceAcceptanceUnknownDueToIncompleteSignal when no signals`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertIs<DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal>(snapshot.artifact)
    }

    @Test
    fun `snapshot dimensionStates reflect current gate state`() {
        evaluator.markDimensionEvidenced(DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE)
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "truth not confirmed"
        )
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.EVIDENCED,
            snapshot.dimensionStates[DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE]?.status
        )
        assertEquals(
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP,
            snapshot.dimensionStates[DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE]?.status
        )
    }

    @Test
    fun `each DimensionEvidenceState dimension matches its map key`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        snapshot.dimensionStates.forEach { (key, state) ->
            assertEquals(key, state.dimension)
        }
    }

    @Test
    fun `DimensionEvidenceState status is UNKNOWN before signals`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        snapshot.dimensionStates.values.forEach { state ->
            assertEquals(DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN, state.status)
        }
    }

    @Test
    fun `DimensionEvidenceState status is EVIDENCED after markDimensionEvidenced`() {
        evaluator.markDimensionEvidenced(DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE)
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.EVIDENCED,
            snapshot.dimensionStates[DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE]?.status
        )
    }

    @Test
    fun `DimensionEvidenceState status is GAP after markDimensionGap`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "compat bypass"
        )
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP,
            snapshot.dimensionStates[DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE]?.status
        )
    }

    @Test
    fun `DimensionEvidenceState gapReason is null for EVIDENCED dimensions`() {
        evaluator.markDimensionEvidenced(DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE)
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertNull(
            snapshot.dimensionStates[DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE]?.gapReason
        )
    }

    @Test
    fun `DimensionEvidenceState gapReason matches registered reason for GAP dimensions`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE,
            "session replay not confirmed across reconnect"
        )
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            "session replay not confirmed across reconnect",
            snapshot.dimensionStates[DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE]?.gapReason
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeAcceptanceSnapshot.DimensionStatus — wire values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `EVIDENCED wireValue is evidenced`() {
        assertEquals(
            "evidenced",
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.EVIDENCED.wireValue
        )
    }

    @Test
    fun `GAP wireValue is gap`() {
        assertEquals(
            "gap",
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP.wireValue
        )
    }

    @Test
    fun `UNKNOWN wireValue is unknown`() {
        assertEquals(
            "unknown",
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN.wireValue
        )
    }

    @Test
    fun `All three dimension status wire values are distinct`() {
        val wireValues = DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.entries.map { it.wireValue }
        assertEquals(
            "All three wire values must be distinct",
            wireValues.size,
            wireValues.toSet().size
        )
    }

    @Test
    fun `fromValue evidenced maps to EVIDENCED`() {
        assertEquals(
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.EVIDENCED,
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.fromValue("evidenced")
        )
    }

    @Test
    fun `fromValue gap maps to GAP`() {
        assertEquals(
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP,
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.fromValue("gap")
        )
    }

    @Test
    fun `fromValue unknown maps to UNKNOWN`() {
        assertEquals(
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN,
            DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.fromValue("unknown")
        )
    }

    @Test
    fun `fromValue unknown string maps to null`() {
        assertNull(DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.fromValue("not_a_status"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DeviceAcceptanceArtifact — semanticTag values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DeviceAcceptedForGraduation semanticTag matches ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION`() {
        markAllEvidenced()
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceAcceptedForGraduation
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceRejectedDueToMissingEvidence semanticTag matches ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            "readiness not reached"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceRejectedDueToTruthGap semanticTag matches ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "truth gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceRejectedDueToResultGap semanticTag matches ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
            "result gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceRejectedDueToExecutionEventGap semanticTag matches ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE,
            "event gap"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToExecutionEventGap
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceRejectedDueToCompatBypassRisk semanticTag matches ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE,
            "compat bypass"
        )
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceAcceptanceUnknownDueToIncompleteSignal semanticTag matches ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL`() {
        val result = evaluator.evaluateAcceptance("dev-1", "snap-1") as
            DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL,
            result.semanticTag
        )
    }

    @Test
    fun `All seven semanticTag values are distinct`() {
        val tags = listOf(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION,
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE,
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP,
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP,
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP,
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK,
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL
        )
        assertEquals("All seven semanticTag values must be distinct", tags.size, tags.toSet().size)
    }

    // ── Helper: assertIs inline (compatible with JUnit4) ──────────────────────

    private inline fun <reified T> assertIs(value: Any?) {
        assertTrue(
            "Expected ${T::class.simpleName} but was ${value?.javaClass?.simpleName}",
            value is T
        )
    }
}
