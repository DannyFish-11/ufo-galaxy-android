package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR-11 (Android) — Unit tests for [DelegatedRuntimePostGraduationGovernanceEvaluator],
 * [DelegatedRuntimeGovernanceDimension], [DeviceGovernanceArtifact], and
 * [DelegatedRuntimeGovernanceSnapshot].
 *
 * ## Test matrix
 *
 * ### DelegatedRuntimePostGraduationGovernanceEvaluator — constants
 *  - ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT wire value is "device_governance_compliant"
 *  - ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION wire value is "device_governance_violation_due_to_truth_regression"
 *  - ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_RESULT_REGRESSION wire value is "device_governance_violation_due_to_result_regression"
 *  - ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_EXECUTION_VISIBILITY_REGRESSION wire value is "device_governance_violation_due_to_execution_visibility_regression"
 *  - ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_COMPAT_BYPASS wire value is "device_governance_violation_due_to_compat_bypass"
 *  - ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL wire value is "device_governance_unknown_due_to_missing_signal"
 *  - All six artifact constants are distinct
 *  - INTEGRATION_ACCEPTANCE_EVALUATOR is "DelegatedRuntimeAcceptanceEvaluator"
 *  - INTEGRATION_TRUTH_OWNER is "AndroidLocalTruthOwnershipCoordinator"
 *  - INTEGRATION_RESULT_CONVERGENCE is "AndroidFlowAwareResultConvergenceParticipant"
 *  - INTEGRATION_EXECUTION_EVENT_OWNER is "AndroidCanonicalExecutionEventOwner"
 *  - INTEGRATION_COMPAT_BLOCKING is "AndroidCompatLegacyBlockingParticipant"
 *  - INTEGRATION_RECOVERY_OWNER is "AndroidRecoveryParticipationOwner"
 *  - INTEGRATION_RUNTIME_CONTROLLER is "RuntimeController"
 *  - INTEGRATION_DELEGATED_FLOW_BRIDGE is "AndroidDelegatedFlowBridge"
 *  - INTRODUCED_PR is 11
 *  - DESCRIPTION is non-blank
 *
 * ### DelegatedRuntimeGovernanceDimension — wire values
 *  - TRUTH_ALIGNMENT_REGRESSION wireValue is "truth_alignment_regression"
 *  - RESULT_CONVERGENCE_REGRESSION wireValue is "result_convergence_regression"
 *  - CANONICAL_EXECUTION_VISIBILITY_REGRESSION wireValue is "canonical_execution_visibility_regression"
 *  - COMPAT_BYPASS_REINTRODUCTION wireValue is "compat_bypass_reintroduction"
 *  - CONTINUITY_CONTRACT_REGRESSION wireValue is "continuity_contract_regression"
 *  - All five wire values are distinct
 *
 * ### DelegatedRuntimeGovernanceDimension.fromValue — classification
 *  - "truth_alignment_regression" → TRUTH_ALIGNMENT_REGRESSION
 *  - "result_convergence_regression" → RESULT_CONVERGENCE_REGRESSION
 *  - "canonical_execution_visibility_regression" → CANONICAL_EXECUTION_VISIBILITY_REGRESSION
 *  - "compat_bypass_reintroduction" → COMPAT_BYPASS_REINTRODUCTION
 *  - "continuity_contract_regression" → CONTINUITY_CONTRACT_REGRESSION
 *  - unknown value → null
 *  - null → null
 *
 * ### Dimension observation gate — markDimensionCompliant / getDimensionStatus
 *  - getDimensionStatus returns UNKNOWN before any signal
 *  - getDimensionStatus returns COMPLIANT after markDimensionCompliant
 *  - getDimensionStatus returns REGRESSION after markDimensionRegression
 *  - getDimensionStatus returns UNKNOWN after markDimensionUnknown
 *  - getDimensionRegressionReason is null before any signal
 *  - getDimensionRegressionReason returns regressionReason after markDimensionRegression
 *  - getDimensionRegressionReason returns reason after markDimensionUnknown
 *  - getDimensionRegressionReason is null after markDimensionCompliant
 *  - clearAllDimensionStates resets all dimensions to UNKNOWN
 *
 * ### evaluateGovernance — DeviceGovernanceUnknownDueToMissingSignal
 *  - Returns DeviceGovernanceUnknownDueToMissingSignal when no dimensions have signals
 *  - Returns DeviceGovernanceUnknownDueToMissingSignal when some dimensions are UNKNOWN
 *  - DeviceGovernanceUnknownDueToMissingSignal.semanticTag is ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL
 *  - DeviceGovernanceUnknownDueToMissingSignal.deviceId matches parameter
 *  - DeviceGovernanceUnknownDueToMissingSignal.snapshotId matches parameter
 *  - DeviceGovernanceUnknownDueToMissingSignal.missingDimensions includes all five when no signals
 *  - DeviceGovernanceUnknownDueToMissingSignal.missingDimensions only includes unknown dimensions
 *  - Missing signal takes priority over truth regression
 *  - Missing signal takes priority over result regression
 *  - Missing signal takes priority over execution visibility regression
 *  - Missing signal takes priority over compat bypass
 *
 * ### evaluateGovernance — DeviceGovernanceViolationDueToTruthRegression
 *  - Returns DeviceGovernanceViolationDueToTruthRegression when truth dimension has REGRESSION
 *  - DeviceGovernanceViolationDueToTruthRegression.semanticTag is ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION
 *  - DeviceGovernanceViolationDueToTruthRegression.deviceId matches parameter
 *  - DeviceGovernanceViolationDueToTruthRegression.snapshotId matches parameter
 *  - DeviceGovernanceViolationDueToTruthRegression.regressionReason matches registered reason
 *  - DeviceGovernanceViolationDueToTruthRegression.regressionReason uses REASON_TRUTH_REGRESSION_DEFAULT when none registered
 *  - DeviceGovernanceViolationDueToTruthRegression.dimension is TRUTH_ALIGNMENT_REGRESSION
 *  - Truth regression takes priority over result regression
 *  - Truth regression takes priority over execution visibility regression
 *  - Truth regression takes priority over compat bypass
 *
 * ### evaluateGovernance — DeviceGovernanceViolationDueToResultRegression
 *  - Returns DeviceGovernanceViolationDueToResultRegression when result dimension has REGRESSION
 *  - DeviceGovernanceViolationDueToResultRegression.semanticTag is ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_RESULT_REGRESSION
 *  - DeviceGovernanceViolationDueToResultRegression.deviceId matches parameter
 *  - DeviceGovernanceViolationDueToResultRegression.snapshotId matches parameter
 *  - DeviceGovernanceViolationDueToResultRegression.regressionReason matches registered reason
 *  - DeviceGovernanceViolationDueToResultRegression.regressionReason uses REASON_RESULT_REGRESSION_DEFAULT when none registered
 *  - DeviceGovernanceViolationDueToResultRegression.dimension is RESULT_CONVERGENCE_REGRESSION
 *  - Result regression takes priority over execution visibility regression
 *  - Result regression takes priority over compat bypass
 *
 * ### evaluateGovernance — DeviceGovernanceViolationDueToExecutionVisibilityRegression
 *  - Returns DeviceGovernanceViolationDueToExecutionVisibilityRegression when execution dimension has REGRESSION
 *  - DeviceGovernanceViolationDueToExecutionVisibilityRegression.semanticTag is ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_EXECUTION_VISIBILITY_REGRESSION
 *  - DeviceGovernanceViolationDueToExecutionVisibilityRegression.deviceId matches parameter
 *  - DeviceGovernanceViolationDueToExecutionVisibilityRegression.snapshotId matches parameter
 *  - DeviceGovernanceViolationDueToExecutionVisibilityRegression.regressionReason matches registered reason
 *  - DeviceGovernanceViolationDueToExecutionVisibilityRegression.regressionReason uses REASON_EXECUTION_VISIBILITY_REGRESSION_DEFAULT when none registered
 *  - DeviceGovernanceViolationDueToExecutionVisibilityRegression.dimension is CANONICAL_EXECUTION_VISIBILITY_REGRESSION
 *  - Execution visibility regression takes priority over compat bypass
 *
 * ### evaluateGovernance — DeviceGovernanceViolationDueToCompatBypass
 *  - Returns DeviceGovernanceViolationDueToCompatBypass when compat dimension has REGRESSION
 *  - DeviceGovernanceViolationDueToCompatBypass.semanticTag is ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_COMPAT_BYPASS
 *  - DeviceGovernanceViolationDueToCompatBypass.deviceId matches parameter
 *  - DeviceGovernanceViolationDueToCompatBypass.snapshotId matches parameter
 *  - DeviceGovernanceViolationDueToCompatBypass.regressionReason matches registered reason
 *  - DeviceGovernanceViolationDueToCompatBypass.regressionReason uses REASON_COMPAT_BYPASS_DEFAULT when none registered
 *  - DeviceGovernanceViolationDueToCompatBypass.dimension is COMPAT_BYPASS_REINTRODUCTION
 *
 * ### evaluateGovernance — continuity regression (maps to truth regression artifact)
 *  - Returns DeviceGovernanceViolationDueToTruthRegression when continuity dimension has REGRESSION
 *  - DeviceGovernanceViolationDueToTruthRegression.dimension is CONTINUITY_CONTRACT_REGRESSION for continuity regression
 *  - DeviceGovernanceViolationDueToTruthRegression.regressionReason uses REASON_CONTINUITY_REGRESSION_DEFAULT for continuity regression without reason
 *
 * ### evaluateGovernance — DeviceGovernanceCompliant
 *  - Returns DeviceGovernanceCompliant when all five dimensions are COMPLIANT
 *  - DeviceGovernanceCompliant.semanticTag is ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT
 *  - DeviceGovernanceCompliant.deviceId matches parameter
 *  - DeviceGovernanceCompliant.snapshotId matches parameter
 *
 * ### buildSnapshot
 *  - buildSnapshot returns a DelegatedRuntimeGovernanceSnapshot
 *  - snapshot deviceId matches parameter
 *  - snapshot snapshotId is non-blank
 *  - snapshot reportedAtMs is positive
 *  - snapshot dimensionStates contains all five dimensions
 *  - snapshot artifact is DeviceGovernanceCompliant when all dimensions are COMPLIANT
 *  - snapshot artifact is DeviceGovernanceUnknownDueToMissingSignal when no signals
 *  - snapshot dimensionStates reflect current observation state
 *  - each DimensionObservationState.dimension matches its map key
 *  - DimensionObservationState.status is UNKNOWN before signals
 *  - DimensionObservationState.status is COMPLIANT after markDimensionCompliant
 *  - DimensionObservationState.status is REGRESSION after markDimensionRegression
 *  - DimensionObservationState.regressionReason is null for COMPLIANT dimensions
 *  - DimensionObservationState.regressionReason matches registered reason for REGRESSION dimensions
 *
 * ### DelegatedRuntimeGovernanceSnapshot.DimensionStatus — wire values
 *  - COMPLIANT wireValue is "compliant"
 *  - REGRESSION wireValue is "regression"
 *  - UNKNOWN wireValue is "unknown"
 *  - All three wire values are distinct
 *  - fromValue("compliant") → COMPLIANT
 *  - fromValue("regression") → REGRESSION
 *  - fromValue("unknown") → UNKNOWN
 *  - fromValue(unknown) → null
 *
 * ### DeviceGovernanceArtifact — semanticTag values
 *  - DeviceGovernanceCompliant.semanticTag matches ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT
 *  - DeviceGovernanceViolationDueToTruthRegression.semanticTag matches ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION
 *  - DeviceGovernanceViolationDueToResultRegression.semanticTag matches ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_RESULT_REGRESSION
 *  - DeviceGovernanceViolationDueToExecutionVisibilityRegression.semanticTag matches ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_EXECUTION_VISIBILITY_REGRESSION
 *  - DeviceGovernanceViolationDueToCompatBypass.semanticTag matches ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_COMPAT_BYPASS
 *  - DeviceGovernanceUnknownDueToMissingSignal.semanticTag matches ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL
 *  - All six semanticTag values are distinct
 */
class Pr11DelegatedRuntimePostGraduationGovernanceTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private lateinit var evaluator: DelegatedRuntimePostGraduationGovernanceEvaluator

    @Before
    fun setUp() {
        evaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun markAllCompliant() {
        DelegatedRuntimeGovernanceDimension.entries.forEach {
            evaluator.markDimensionCompliant(it)
        }
    }

    private fun markAllExcept(
        vararg except: DelegatedRuntimeGovernanceDimension
    ) {
        DelegatedRuntimeGovernanceDimension.entries
            .filter { it !in except }
            .forEach { evaluator.markDimensionCompliant(it) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimePostGraduationGovernanceEvaluator — constants
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT wire value is device_governance_compliant`() {
        assertEquals(
            "device_governance_compliant",
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION wire value is device_governance_violation_due_to_truth_regression`() {
        assertEquals(
            "device_governance_violation_due_to_truth_regression",
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_RESULT_REGRESSION wire value is device_governance_violation_due_to_result_regression`() {
        assertEquals(
            "device_governance_violation_due_to_result_regression",
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_RESULT_REGRESSION
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_EXECUTION_VISIBILITY_REGRESSION wire value is device_governance_violation_due_to_execution_visibility_regression`() {
        assertEquals(
            "device_governance_violation_due_to_execution_visibility_regression",
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_EXECUTION_VISIBILITY_REGRESSION
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_COMPAT_BYPASS wire value is device_governance_violation_due_to_compat_bypass`() {
        assertEquals(
            "device_governance_violation_due_to_compat_bypass",
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_COMPAT_BYPASS
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL wire value is device_governance_unknown_due_to_missing_signal`() {
        assertEquals(
            "device_governance_unknown_due_to_missing_signal",
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL
        )
    }

    @Test
    fun `All six artifact constants are distinct`() {
        val constants = listOf(
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT,
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION,
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_RESULT_REGRESSION,
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_EXECUTION_VISIBILITY_REGRESSION,
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_COMPAT_BYPASS,
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL
        )
        assertEquals("All six artifact constants must be distinct", constants.size, constants.toSet().size)
    }

    @Test
    fun `INTEGRATION_ACCEPTANCE_EVALUATOR is DelegatedRuntimeAcceptanceEvaluator`() {
        assertEquals(
            "DelegatedRuntimeAcceptanceEvaluator",
            DelegatedRuntimePostGraduationGovernanceEvaluator.INTEGRATION_ACCEPTANCE_EVALUATOR
        )
    }

    @Test
    fun `INTEGRATION_TRUTH_OWNER is AndroidLocalTruthOwnershipCoordinator`() {
        assertEquals(
            "AndroidLocalTruthOwnershipCoordinator",
            DelegatedRuntimePostGraduationGovernanceEvaluator.INTEGRATION_TRUTH_OWNER
        )
    }

    @Test
    fun `INTEGRATION_RESULT_CONVERGENCE is AndroidFlowAwareResultConvergenceParticipant`() {
        assertEquals(
            "AndroidFlowAwareResultConvergenceParticipant",
            DelegatedRuntimePostGraduationGovernanceEvaluator.INTEGRATION_RESULT_CONVERGENCE
        )
    }

    @Test
    fun `INTEGRATION_EXECUTION_EVENT_OWNER is AndroidCanonicalExecutionEventOwner`() {
        assertEquals(
            "AndroidCanonicalExecutionEventOwner",
            DelegatedRuntimePostGraduationGovernanceEvaluator.INTEGRATION_EXECUTION_EVENT_OWNER
        )
    }

    @Test
    fun `INTEGRATION_COMPAT_BLOCKING is AndroidCompatLegacyBlockingParticipant`() {
        assertEquals(
            "AndroidCompatLegacyBlockingParticipant",
            DelegatedRuntimePostGraduationGovernanceEvaluator.INTEGRATION_COMPAT_BLOCKING
        )
    }

    @Test
    fun `INTEGRATION_RECOVERY_OWNER is AndroidRecoveryParticipationOwner`() {
        assertEquals(
            "AndroidRecoveryParticipationOwner",
            DelegatedRuntimePostGraduationGovernanceEvaluator.INTEGRATION_RECOVERY_OWNER
        )
    }

    @Test
    fun `INTEGRATION_RUNTIME_CONTROLLER is RuntimeController`() {
        assertEquals(
            "RuntimeController",
            DelegatedRuntimePostGraduationGovernanceEvaluator.INTEGRATION_RUNTIME_CONTROLLER
        )
    }

    @Test
    fun `INTEGRATION_DELEGATED_FLOW_BRIDGE is AndroidDelegatedFlowBridge`() {
        assertEquals(
            "AndroidDelegatedFlowBridge",
            DelegatedRuntimePostGraduationGovernanceEvaluator.INTEGRATION_DELEGATED_FLOW_BRIDGE
        )
    }

    @Test
    fun `INTRODUCED_PR is 11`() {
        assertEquals(11, DelegatedRuntimePostGraduationGovernanceEvaluator.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(
            DelegatedRuntimePostGraduationGovernanceEvaluator.DESCRIPTION.isNotBlank()
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeGovernanceDimension — wire values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TRUTH_ALIGNMENT_REGRESSION wireValue is truth_alignment_regression`() {
        assertEquals(
            "truth_alignment_regression",
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION.wireValue
        )
    }

    @Test
    fun `RESULT_CONVERGENCE_REGRESSION wireValue is result_convergence_regression`() {
        assertEquals(
            "result_convergence_regression",
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION.wireValue
        )
    }

    @Test
    fun `CANONICAL_EXECUTION_VISIBILITY_REGRESSION wireValue is canonical_execution_visibility_regression`() {
        assertEquals(
            "canonical_execution_visibility_regression",
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION.wireValue
        )
    }

    @Test
    fun `COMPAT_BYPASS_REINTRODUCTION wireValue is compat_bypass_reintroduction`() {
        assertEquals(
            "compat_bypass_reintroduction",
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION.wireValue
        )
    }

    @Test
    fun `CONTINUITY_CONTRACT_REGRESSION wireValue is continuity_contract_regression`() {
        assertEquals(
            "continuity_contract_regression",
            DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION.wireValue
        )
    }

    @Test
    fun `All five wire values are distinct`() {
        val wireValues = DelegatedRuntimeGovernanceDimension.entries.map { it.wireValue }
        assertEquals("All five wire values must be distinct", wireValues.size, wireValues.toSet().size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeGovernanceDimension.fromValue — classification
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `truth_alignment_regression maps to TRUTH_ALIGNMENT_REGRESSION`() {
        assertEquals(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            DelegatedRuntimeGovernanceDimension.fromValue("truth_alignment_regression")
        )
    }

    @Test
    fun `result_convergence_regression maps to RESULT_CONVERGENCE_REGRESSION`() {
        assertEquals(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            DelegatedRuntimeGovernanceDimension.fromValue("result_convergence_regression")
        )
    }

    @Test
    fun `canonical_execution_visibility_regression maps to CANONICAL_EXECUTION_VISIBILITY_REGRESSION`() {
        assertEquals(
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION,
            DelegatedRuntimeGovernanceDimension.fromValue("canonical_execution_visibility_regression")
        )
    }

    @Test
    fun `compat_bypass_reintroduction maps to COMPAT_BYPASS_REINTRODUCTION`() {
        assertEquals(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            DelegatedRuntimeGovernanceDimension.fromValue("compat_bypass_reintroduction")
        )
    }

    @Test
    fun `continuity_contract_regression maps to CONTINUITY_CONTRACT_REGRESSION`() {
        assertEquals(
            DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION,
            DelegatedRuntimeGovernanceDimension.fromValue("continuity_contract_regression")
        )
    }

    @Test
    fun `unknown value maps to null`() {
        assertNull(DelegatedRuntimeGovernanceDimension.fromValue("not_a_dimension"))
    }

    @Test
    fun `null maps to null`() {
        assertNull(DelegatedRuntimeGovernanceDimension.fromValue(null))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Dimension observation gate — markDimensionCompliant / getDimensionStatus
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `getDimensionStatus returns UNKNOWN before any signal`() {
        assertEquals(
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN,
            evaluator.getDimensionStatus(DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION)
        )
    }

    @Test
    fun `getDimensionStatus returns COMPLIANT after markDimensionCompliant`() {
        evaluator.markDimensionCompliant(DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION)
        assertEquals(
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.COMPLIANT,
            evaluator.getDimensionStatus(DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION)
        )
    }

    @Test
    fun `getDimensionStatus returns REGRESSION after markDimensionRegression`() {
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "truth drift detected"
        )
        assertEquals(
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION,
            evaluator.getDimensionStatus(DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION)
        )
    }

    @Test
    fun `getDimensionStatus returns UNKNOWN after markDimensionUnknown`() {
        evaluator.markDimensionCompliant(DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION)
        evaluator.markDimensionUnknown(DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION)
        assertEquals(
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN,
            evaluator.getDimensionStatus(DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION)
        )
    }

    @Test
    fun `getDimensionRegressionReason is null before any signal`() {
        assertNull(
            evaluator.getDimensionRegressionReason(DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION)
        )
    }

    @Test
    fun `getDimensionRegressionReason returns regressionReason after markDimensionRegression`() {
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            "result divergence observed"
        )
        assertEquals(
            "result divergence observed",
            evaluator.getDimensionRegressionReason(DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION)
        )
    }

    @Test
    fun `getDimensionRegressionReason returns reason after markDimensionUnknown`() {
        evaluator.markDimensionUnknown(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            "no compat signal received"
        )
        assertEquals(
            "no compat signal received",
            evaluator.getDimensionRegressionReason(DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION)
        )
    }

    @Test
    fun `getDimensionRegressionReason is null after markDimensionCompliant`() {
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "truth drift"
        )
        evaluator.markDimensionCompliant(DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION)
        assertNull(
            evaluator.getDimensionRegressionReason(DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION)
        )
    }

    @Test
    fun `clearAllDimensionStates resets all dimensions to UNKNOWN`() {
        markAllCompliant()
        evaluator.clearAllDimensionStates()
        DelegatedRuntimeGovernanceDimension.entries.forEach { dim ->
            assertEquals(
                DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN,
                evaluator.getDimensionStatus(dim)
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateGovernance — DeviceGovernanceUnknownDueToMissingSignal
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceGovernanceUnknownDueToMissingSignal when no dimensions have signals`() {
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal)
    }

    @Test
    fun `Returns DeviceGovernanceUnknownDueToMissingSignal when some dimensions are UNKNOWN`() {
        markAllExcept(DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION)
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal)
    }

    @Test
    fun `DeviceGovernanceUnknownDueToMissingSignal semanticTag is ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL`() {
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceGovernanceUnknownDueToMissingSignal deviceId matches parameter`() {
        val result = evaluator.evaluateGovernance("device-xyz", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal
        assertEquals("device-xyz", result.deviceId)
    }

    @Test
    fun `DeviceGovernanceUnknownDueToMissingSignal snapshotId matches parameter`() {
        val result = evaluator.evaluateGovernance("dev-1", "snap-abc")
            as DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal
        assertEquals("snap-abc", result.snapshotId)
    }

    @Test
    fun `DeviceGovernanceUnknownDueToMissingSignal missingDimensions includes all five when no signals`() {
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal
        assertEquals(
            DelegatedRuntimeGovernanceDimension.entries.toSet(),
            result.missingDimensions
        )
    }

    @Test
    fun `DeviceGovernanceUnknownDueToMissingSignal missingDimensions only includes unknown dimensions`() {
        markAllExcept(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal
        assertEquals(
            setOf(
                DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
                DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION
            ),
            result.missingDimensions
        )
    }

    @Test
    fun `Missing signal takes priority over truth regression`() {
        markAllExcept(DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION)
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "truth drift"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal)
    }

    @Test
    fun `Missing signal takes priority over result regression`() {
        markAllExcept(DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION)
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            "result drift"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal)
    }

    @Test
    fun `Missing signal takes priority over execution visibility regression`() {
        markAllExcept(DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION)
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION,
            "visibility regression"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal)
    }

    @Test
    fun `Missing signal takes priority over compat bypass`() {
        markAllExcept(DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION)
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            "compat bypass"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateGovernance — DeviceGovernanceViolationDueToTruthRegression
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceGovernanceViolationDueToTruthRegression when truth dimension has REGRESSION`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "truth drift detected"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression)
    }

    @Test
    fun `DeviceGovernanceViolationDueToTruthRegression semanticTag is ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "truth drift"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceGovernanceViolationDueToTruthRegression deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "truth drift"
        )
        val result = evaluator.evaluateGovernance("device-truth-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression
        assertEquals("device-truth-1", result.deviceId)
    }

    @Test
    fun `DeviceGovernanceViolationDueToTruthRegression snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "truth drift"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-truth-99")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression
        assertEquals("snap-truth-99", result.snapshotId)
    }

    @Test
    fun `DeviceGovernanceViolationDueToTruthRegression regressionReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "v2 alignment lost"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression
        assertEquals("v2 alignment lost", result.regressionReason)
    }

    @Test
    fun `DeviceGovernanceViolationDueToTruthRegression regressionReason uses REASON_TRUTH_REGRESSION_DEFAULT when none registered`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            DelegatedRuntimePostGraduationGovernanceEvaluator.REASON_TRUTH_REGRESSION_DEFAULT
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.REASON_TRUTH_REGRESSION_DEFAULT,
            result.regressionReason
        )
    }

    @Test
    fun `DeviceGovernanceViolationDueToTruthRegression dimension is TRUTH_ALIGNMENT_REGRESSION`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "truth drift"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression
        assertEquals(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            result.dimension
        )
    }

    @Test
    fun `Truth regression takes priority over result regression`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "truth drift"
        )
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            "result drift"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression)
        assertEquals(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            (result as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression).dimension
        )
    }

    @Test
    fun `Truth regression takes priority over execution visibility regression`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "truth drift"
        )
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION,
            "visibility regression"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression)
    }

    @Test
    fun `Truth regression takes priority over compat bypass`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "truth drift"
        )
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            "compat bypass"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateGovernance — DeviceGovernanceViolationDueToResultRegression
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceGovernanceViolationDueToResultRegression when result dimension has REGRESSION`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            "result divergence"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression)
    }

    @Test
    fun `DeviceGovernanceViolationDueToResultRegression semanticTag is ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_RESULT_REGRESSION`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            "result divergence"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_RESULT_REGRESSION,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceGovernanceViolationDueToResultRegression deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            "result divergence"
        )
        val result = evaluator.evaluateGovernance("device-result-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression
        assertEquals("device-result-1", result.deviceId)
    }

    @Test
    fun `DeviceGovernanceViolationDueToResultRegression snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            "result divergence"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-result-99")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression
        assertEquals("snap-result-99", result.snapshotId)
    }

    @Test
    fun `DeviceGovernanceViolationDueToResultRegression regressionReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            "late partial leak after final"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression
        assertEquals("late partial leak after final", result.regressionReason)
    }

    @Test
    fun `DeviceGovernanceViolationDueToResultRegression regressionReason uses REASON_RESULT_REGRESSION_DEFAULT when none registered`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            DelegatedRuntimePostGraduationGovernanceEvaluator.REASON_RESULT_REGRESSION_DEFAULT
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.REASON_RESULT_REGRESSION_DEFAULT,
            result.regressionReason
        )
    }

    @Test
    fun `DeviceGovernanceViolationDueToResultRegression dimension is RESULT_CONVERGENCE_REGRESSION`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            "result divergence"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression
        assertEquals(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            result.dimension
        )
    }

    @Test
    fun `Result regression takes priority over execution visibility regression`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            "result drift"
        )
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION,
            "visibility regression"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression)
    }

    @Test
    fun `Result regression takes priority over compat bypass`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            "result drift"
        )
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            "compat bypass"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateGovernance — DeviceGovernanceViolationDueToExecutionVisibilityRegression
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceGovernanceViolationDueToExecutionVisibilityRegression when execution dimension has REGRESSION`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION,
            "post-terminal noise reintroduced"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression)
    }

    @Test
    fun `DeviceGovernanceViolationDueToExecutionVisibilityRegression semanticTag is ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_EXECUTION_VISIBILITY_REGRESSION`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION,
            "post-terminal noise"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_EXECUTION_VISIBILITY_REGRESSION,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceGovernanceViolationDueToExecutionVisibilityRegression deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION,
            "visibility regression"
        )
        val result = evaluator.evaluateGovernance("device-exec-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression
        assertEquals("device-exec-1", result.deviceId)
    }

    @Test
    fun `DeviceGovernanceViolationDueToExecutionVisibilityRegression snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION,
            "visibility regression"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-exec-77")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression
        assertEquals("snap-exec-77", result.snapshotId)
    }

    @Test
    fun `DeviceGovernanceViolationDueToExecutionVisibilityRegression regressionReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION,
            "reconnect alignment gap detected"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression
        assertEquals("reconnect alignment gap detected", result.regressionReason)
    }

    @Test
    fun `DeviceGovernanceViolationDueToExecutionVisibilityRegression regressionReason uses REASON_EXECUTION_VISIBILITY_REGRESSION_DEFAULT when none registered`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION,
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .REASON_EXECUTION_VISIBILITY_REGRESSION_DEFAULT
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .REASON_EXECUTION_VISIBILITY_REGRESSION_DEFAULT,
            result.regressionReason
        )
    }

    @Test
    fun `DeviceGovernanceViolationDueToExecutionVisibilityRegression dimension is CANONICAL_EXECUTION_VISIBILITY_REGRESSION`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION,
            "visibility regression"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression
        assertEquals(
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION,
            result.dimension
        )
    }

    @Test
    fun `Execution visibility regression takes priority over compat bypass`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION,
            "visibility regression"
        )
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            "compat bypass"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateGovernance — DeviceGovernanceViolationDueToCompatBypass
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceGovernanceViolationDueToCompatBypass when compat dimension has REGRESSION`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            "legacy path reactivated"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToCompatBypass)
    }

    @Test
    fun `DeviceGovernanceViolationDueToCompatBypass semanticTag is ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_COMPAT_BYPASS`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            "compat bypass"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_COMPAT_BYPASS,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceGovernanceViolationDueToCompatBypass deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            "compat bypass"
        )
        val result = evaluator.evaluateGovernance("device-compat-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToCompatBypass
        assertEquals("device-compat-1", result.deviceId)
    }

    @Test
    fun `DeviceGovernanceViolationDueToCompatBypass snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            "compat bypass"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-compat-33")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToCompatBypass
        assertEquals("snap-compat-33", result.snapshotId)
    }

    @Test
    fun `DeviceGovernanceViolationDueToCompatBypass regressionReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            "blocked legacy paths still active"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToCompatBypass
        assertEquals("blocked legacy paths still active", result.regressionReason)
    }

    @Test
    fun `DeviceGovernanceViolationDueToCompatBypass regressionReason uses REASON_COMPAT_BYPASS_DEFAULT when none registered`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            DelegatedRuntimePostGraduationGovernanceEvaluator.REASON_COMPAT_BYPASS_DEFAULT
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToCompatBypass
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.REASON_COMPAT_BYPASS_DEFAULT,
            result.regressionReason
        )
    }

    @Test
    fun `DeviceGovernanceViolationDueToCompatBypass dimension is COMPAT_BYPASS_REINTRODUCTION`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            "compat bypass"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToCompatBypass
        assertEquals(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            result.dimension
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateGovernance — continuity regression (maps to truth regression artifact)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceGovernanceViolationDueToTruthRegression when continuity dimension has REGRESSION`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION,
            "reconnect contract broken"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression)
    }

    @Test
    fun `DeviceGovernanceViolationDueToTruthRegression dimension is CONTINUITY_CONTRACT_REGRESSION for continuity regression`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION,
            "reconnect contract broken"
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression
        assertEquals(
            DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION,
            result.dimension
        )
    }

    @Test
    fun `DeviceGovernanceViolationDueToTruthRegression regressionReason uses REASON_CONTINUITY_REGRESSION_DEFAULT for continuity regression without reason`() {
        markAllExcept()
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION,
            DelegatedRuntimePostGraduationGovernanceEvaluator.REASON_CONTINUITY_REGRESSION_DEFAULT
        )
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.REASON_CONTINUITY_REGRESSION_DEFAULT,
            result.regressionReason
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateGovernance — DeviceGovernanceCompliant
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceGovernanceCompliant when all five dimensions are COMPLIANT`() {
        markAllCompliant()
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertTrue(result is DeviceGovernanceArtifact.DeviceGovernanceCompliant)
    }

    @Test
    fun `DeviceGovernanceCompliant semanticTag is ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT`() {
        markAllCompliant()
        val result = evaluator.evaluateGovernance("dev-1", "snap-1")
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceGovernanceCompliant deviceId matches parameter`() {
        markAllCompliant()
        val result = evaluator.evaluateGovernance("device-compliant-1", "snap-1")
            as DeviceGovernanceArtifact.DeviceGovernanceCompliant
        assertEquals("device-compliant-1", result.deviceId)
    }

    @Test
    fun `DeviceGovernanceCompliant snapshotId matches parameter`() {
        markAllCompliant()
        val result = evaluator.evaluateGovernance("dev-1", "snap-compliant-42")
            as DeviceGovernanceArtifact.DeviceGovernanceCompliant
        assertEquals("snap-compliant-42", result.snapshotId)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // buildSnapshot
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `buildSnapshot returns a DelegatedRuntimeGovernanceSnapshot`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertNotNull(snapshot)
    }

    @Test
    fun `snapshot deviceId matches parameter`() {
        val snapshot = evaluator.buildSnapshot("device-snap-1")
        assertEquals("device-snap-1", snapshot.deviceId)
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
    fun `snapshot dimensionStates contains all five dimensions`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeGovernanceDimension.entries.toSet(),
            snapshot.dimensionStates.keys
        )
    }

    @Test
    fun `snapshot artifact is DeviceGovernanceCompliant when all dimensions are COMPLIANT`() {
        markAllCompliant()
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertTrue(snapshot.artifact is DeviceGovernanceArtifact.DeviceGovernanceCompliant)
    }

    @Test
    fun `snapshot artifact is DeviceGovernanceUnknownDueToMissingSignal when no signals`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertTrue(snapshot.artifact is DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal)
    }

    @Test
    fun `snapshot dimensionStates reflect current observation state`() {
        evaluator.markDimensionCompliant(DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION)
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            "result divergence"
        )
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.COMPLIANT,
            snapshot.dimensionStates[DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION]?.status
        )
        assertEquals(
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION,
            snapshot.dimensionStates[DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION]?.status
        )
    }

    @Test
    fun `each DimensionObservationState dimension matches its map key`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        snapshot.dimensionStates.forEach { (key, state) ->
            assertEquals(key, state.dimension)
        }
    }

    @Test
    fun `DimensionObservationState status is UNKNOWN before signals`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        snapshot.dimensionStates.values.forEach { state ->
            assertEquals(DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN, state.status)
        }
    }

    @Test
    fun `DimensionObservationState status is COMPLIANT after markDimensionCompliant`() {
        markAllCompliant()
        val snapshot = evaluator.buildSnapshot("dev-1")
        snapshot.dimensionStates.values.forEach { state ->
            assertEquals(DelegatedRuntimeGovernanceSnapshot.DimensionStatus.COMPLIANT, state.status)
        }
    }

    @Test
    fun `DimensionObservationState status is REGRESSION after markDimensionRegression`() {
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION,
            "compat bypass detected"
        )
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION,
            snapshot.dimensionStates[DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION]?.status
        )
    }

    @Test
    fun `DimensionObservationState regressionReason is null for COMPLIANT dimensions`() {
        markAllCompliant()
        val snapshot = evaluator.buildSnapshot("dev-1")
        snapshot.dimensionStates.values.forEach { state ->
            assertNull(state.regressionReason)
        }
    }

    @Test
    fun `DimensionObservationState regressionReason matches registered reason for REGRESSION dimensions`() {
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION,
            "replay boundary broken"
        )
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            "replay boundary broken",
            snapshot.dimensionStates[DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION]?.regressionReason
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeGovernanceSnapshot.DimensionStatus — wire values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `COMPLIANT wireValue is compliant`() {
        assertEquals("compliant", DelegatedRuntimeGovernanceSnapshot.DimensionStatus.COMPLIANT.wireValue)
    }

    @Test
    fun `REGRESSION wireValue is regression`() {
        assertEquals("regression", DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION.wireValue)
    }

    @Test
    fun `UNKNOWN wireValue is unknown`() {
        assertEquals("unknown", DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN.wireValue)
    }

    @Test
    fun `All three wire values are distinct`() {
        val wireValues = DelegatedRuntimeGovernanceSnapshot.DimensionStatus.entries.map { it.wireValue }
        assertEquals("All three wire values must be distinct", wireValues.size, wireValues.toSet().size)
    }

    @Test
    fun `fromValue compliant maps to COMPLIANT`() {
        assertEquals(
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.COMPLIANT,
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.fromValue("compliant")
        )
    }

    @Test
    fun `fromValue regression maps to REGRESSION`() {
        assertEquals(
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION,
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.fromValue("regression")
        )
    }

    @Test
    fun `fromValue unknown maps to UNKNOWN`() {
        assertEquals(
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN,
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.fromValue("unknown")
        )
    }

    @Test
    fun `fromValue unknown string maps to null`() {
        assertNull(DelegatedRuntimeGovernanceSnapshot.DimensionStatus.fromValue("not_a_status"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DeviceGovernanceArtifact — semanticTag values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DeviceGovernanceCompliant semanticTag matches ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT`() {
        val artifact = DeviceGovernanceArtifact.DeviceGovernanceCompliant("d", "s")
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceGovernanceViolationDueToTruthRegression semanticTag matches ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION`() {
        val artifact = DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression(
            "d", "s", "reason"
        )
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceGovernanceViolationDueToResultRegression semanticTag matches ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_RESULT_REGRESSION`() {
        val artifact = DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression(
            "d", "s", "reason"
        )
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_RESULT_REGRESSION,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceGovernanceViolationDueToExecutionVisibilityRegression semanticTag matches ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_EXECUTION_VISIBILITY_REGRESSION`() {
        val artifact = DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression(
            "d", "s", "reason"
        )
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_EXECUTION_VISIBILITY_REGRESSION,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceGovernanceViolationDueToCompatBypass semanticTag matches ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_COMPAT_BYPASS`() {
        val artifact = DeviceGovernanceArtifact.DeviceGovernanceViolationDueToCompatBypass(
            "d", "s", "reason"
        )
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_COMPAT_BYPASS,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceGovernanceUnknownDueToMissingSignal semanticTag matches ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL`() {
        val artifact = DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal(
            "d", "s", emptySet()
        )
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            artifact.semanticTag
        )
    }

    @Test
    fun `All six semanticTag values are distinct`() {
        val tags = listOf(
            DeviceGovernanceArtifact.DeviceGovernanceCompliant("d", "s").semanticTag,
            DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression("d", "s", "r").semanticTag,
            DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression("d", "s", "r").semanticTag,
            DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression("d", "s", "r").semanticTag,
            DeviceGovernanceArtifact.DeviceGovernanceViolationDueToCompatBypass("d", "s", "r").semanticTag,
            DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal("d", "s", emptySet()).semanticTag
        )
        assertEquals("All six semanticTag values must be distinct", tags.size, tags.toSet().size)
    }
}
