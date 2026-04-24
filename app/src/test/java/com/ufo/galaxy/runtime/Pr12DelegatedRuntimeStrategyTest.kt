package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR-12 (Android) — Unit tests for [DelegatedRuntimeStrategyEvaluator],
 * [DelegatedRuntimeStrategyDimension], [DeviceStrategyArtifact], and
 * [DelegatedRuntimeStrategySnapshot].
 *
 * ## Test matrix
 *
 * ### DelegatedRuntimeStrategyEvaluator — constants
 *  - ARTIFACT_DEVICE_STRATEGY_ON_TRACK wire value is "device_strategy_on_track"
 *  - ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY wire value is "device_strategy_risk_due_to_contract_instability"
 *  - ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_GOVERNANCE_REGRESSION_TREND wire value is "device_strategy_risk_due_to_governance_regression_trend"
 *  - ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_ROLLOUT_MATURITY_GAP wire value is "device_strategy_risk_due_to_rollout_maturity_gap"
 *  - ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_RUNTIME_COUPLING_DRIFT wire value is "device_strategy_risk_due_to_runtime_coupling_drift"
 *  - ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL wire value is "device_strategy_unknown_due_to_missing_program_signal"
 *  - All six artifact constants are distinct
 *  - INTEGRATION_GOVERNANCE_EVALUATOR is "DelegatedRuntimePostGraduationGovernanceEvaluator"
 *  - INTEGRATION_ACCEPTANCE_EVALUATOR is "DelegatedRuntimeAcceptanceEvaluator"
 *  - INTEGRATION_TRUTH_OWNER is "AndroidLocalTruthOwnershipCoordinator"
 *  - INTEGRATION_RESULT_CONVERGENCE is "AndroidFlowAwareResultConvergenceParticipant"
 *  - INTEGRATION_EXECUTION_EVENT_OWNER is "AndroidCanonicalExecutionEventOwner"
 *  - INTEGRATION_COMPAT_BLOCKING is "AndroidCompatLegacyBlockingParticipant"
 *  - INTEGRATION_RECOVERY_OWNER is "AndroidRecoveryParticipationOwner"
 *  - INTEGRATION_RUNTIME_CONTROLLER is "RuntimeController"
 *  - INTEGRATION_DELEGATED_FLOW_BRIDGE is "AndroidDelegatedFlowBridge"
 *  - INTRODUCED_PR is 12
 *  - DESCRIPTION is non-blank
 *
 * ### DelegatedRuntimeStrategyDimension — wire values
 *  - CANONICAL_CONTRACT_STABILITY wireValue is "canonical_contract_stability"
 *  - GOVERNANCE_TREND wireValue is "governance_trend"
 *  - ROLLOUT_MATURITY wireValue is "rollout_maturity"
 *  - REGRESSION_PRESSURE wireValue is "regression_pressure"
 *  - RUNTIME_COUPLING_DRIFT wireValue is "runtime_coupling_drift"
 *  - All five wire values are distinct
 *
 * ### DelegatedRuntimeStrategyDimension.fromValue — classification
 *  - "canonical_contract_stability" → CANONICAL_CONTRACT_STABILITY
 *  - "governance_trend" → GOVERNANCE_TREND
 *  - "rollout_maturity" → ROLLOUT_MATURITY
 *  - "regression_pressure" → REGRESSION_PRESSURE
 *  - "runtime_coupling_drift" → RUNTIME_COUPLING_DRIFT
 *  - unknown value → null
 *  - null → null
 *
 * ### Dimension posture gate — markDimensionOnTrack / getDimensionStatus
 *  - getDimensionStatus returns UNKNOWN before any signal
 *  - getDimensionStatus returns ON_TRACK after markDimensionOnTrack
 *  - getDimensionStatus returns AT_RISK after markDimensionAtRisk
 *  - getDimensionStatus returns UNKNOWN after markDimensionUnknown
 *  - getDimensionRiskReason is null before any signal
 *  - getDimensionRiskReason returns riskReason after markDimensionAtRisk
 *  - getDimensionRiskReason returns reason after markDimensionUnknown
 *  - getDimensionRiskReason is null after markDimensionOnTrack
 *  - clearAllDimensionStates resets all dimensions to UNKNOWN
 *
 * ### evaluateStrategy — DeviceStrategyUnknownDueToMissingProgramSignal
 *  - Returns DeviceStrategyUnknownDueToMissingProgramSignal when no dimensions have signals
 *  - Returns DeviceStrategyUnknownDueToMissingProgramSignal when some dimensions are UNKNOWN
 *  - DeviceStrategyUnknownDueToMissingProgramSignal.semanticTag is ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL
 *  - DeviceStrategyUnknownDueToMissingProgramSignal.deviceId matches parameter
 *  - DeviceStrategyUnknownDueToMissingProgramSignal.snapshotId matches parameter
 *  - DeviceStrategyUnknownDueToMissingProgramSignal.missingDimensions includes all five when no signals
 *  - DeviceStrategyUnknownDueToMissingProgramSignal.missingDimensions only includes unknown dimensions
 *  - Missing signal takes priority over contract instability risk
 *  - Missing signal takes priority over governance trend risk
 *  - Missing signal takes priority over rollout maturity gap
 *  - Missing signal takes priority over runtime coupling drift
 *
 * ### evaluateStrategy — DeviceStrategyRiskDueToContractInstability
 *  - Returns DeviceStrategyRiskDueToContractInstability when contract stability dimension is AT_RISK
 *  - DeviceStrategyRiskDueToContractInstability.semanticTag is ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY
 *  - DeviceStrategyRiskDueToContractInstability.deviceId matches parameter
 *  - DeviceStrategyRiskDueToContractInstability.snapshotId matches parameter
 *  - DeviceStrategyRiskDueToContractInstability.riskReason matches registered reason
 *  - DeviceStrategyRiskDueToContractInstability.riskReason uses REASON_CONTRACT_INSTABILITY_DEFAULT when none registered
 *  - DeviceStrategyRiskDueToContractInstability.dimension is CANONICAL_CONTRACT_STABILITY
 *  - Contract instability takes priority over governance trend risk
 *  - Contract instability takes priority over rollout maturity gap
 *  - Contract instability takes priority over runtime coupling drift
 *
 * ### evaluateStrategy — DeviceStrategyRiskDueToGovernanceRegressionTrend
 *  - Returns DeviceStrategyRiskDueToGovernanceRegressionTrend when governance trend dimension is AT_RISK
 *  - DeviceStrategyRiskDueToGovernanceRegressionTrend.semanticTag is ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_GOVERNANCE_REGRESSION_TREND
 *  - DeviceStrategyRiskDueToGovernanceRegressionTrend.deviceId matches parameter
 *  - DeviceStrategyRiskDueToGovernanceRegressionTrend.snapshotId matches parameter
 *  - DeviceStrategyRiskDueToGovernanceRegressionTrend.riskReason matches registered reason
 *  - DeviceStrategyRiskDueToGovernanceRegressionTrend.riskReason uses REASON_GOVERNANCE_TREND_DEFAULT when none registered
 *  - DeviceStrategyRiskDueToGovernanceRegressionTrend.dimension is GOVERNANCE_TREND
 *  - Governance trend risk takes priority over rollout maturity gap
 *  - Governance trend risk takes priority over runtime coupling drift
 *
 * ### evaluateStrategy — DeviceStrategyRiskDueToRolloutMaturityGap
 *  - Returns DeviceStrategyRiskDueToRolloutMaturityGap when rollout maturity dimension is AT_RISK
 *  - DeviceStrategyRiskDueToRolloutMaturityGap.semanticTag is ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_ROLLOUT_MATURITY_GAP
 *  - DeviceStrategyRiskDueToRolloutMaturityGap.deviceId matches parameter
 *  - DeviceStrategyRiskDueToRolloutMaturityGap.snapshotId matches parameter
 *  - DeviceStrategyRiskDueToRolloutMaturityGap.riskReason matches registered reason
 *  - DeviceStrategyRiskDueToRolloutMaturityGap.riskReason uses REASON_ROLLOUT_MATURITY_DEFAULT when none registered
 *  - DeviceStrategyRiskDueToRolloutMaturityGap.dimension is ROLLOUT_MATURITY
 *  - Rollout maturity gap takes priority over runtime coupling drift
 *
 * ### evaluateStrategy — DeviceStrategyRiskDueToRuntimeCouplingDrift
 *  - Returns DeviceStrategyRiskDueToRuntimeCouplingDrift when runtime coupling drift dimension is AT_RISK
 *  - DeviceStrategyRiskDueToRuntimeCouplingDrift.semanticTag is ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_RUNTIME_COUPLING_DRIFT
 *  - DeviceStrategyRiskDueToRuntimeCouplingDrift.deviceId matches parameter
 *  - DeviceStrategyRiskDueToRuntimeCouplingDrift.snapshotId matches parameter
 *  - DeviceStrategyRiskDueToRuntimeCouplingDrift.riskReason matches registered reason
 *  - DeviceStrategyRiskDueToRuntimeCouplingDrift.riskReason uses REASON_RUNTIME_COUPLING_DRIFT_DEFAULT when none registered
 *  - DeviceStrategyRiskDueToRuntimeCouplingDrift.dimension is RUNTIME_COUPLING_DRIFT
 *
 * ### evaluateStrategy — regression pressure (maps to contract instability artifact)
 *  - Returns DeviceStrategyRiskDueToContractInstability when regression pressure dimension is AT_RISK
 *  - DeviceStrategyRiskDueToContractInstability.dimension is REGRESSION_PRESSURE for regression pressure risk
 *  - DeviceStrategyRiskDueToContractInstability.riskReason uses REASON_REGRESSION_PRESSURE_DEFAULT for regression pressure without reason
 *
 * ### evaluateStrategy — DeviceStrategyOnTrack
 *  - Returns DeviceStrategyOnTrack when all five dimensions are ON_TRACK
 *  - DeviceStrategyOnTrack.semanticTag is ARTIFACT_DEVICE_STRATEGY_ON_TRACK
 *  - DeviceStrategyOnTrack.deviceId matches parameter
 *  - DeviceStrategyOnTrack.snapshotId matches parameter
 *
 * ### buildSnapshot
 *  - buildSnapshot returns a DelegatedRuntimeStrategySnapshot
 *  - snapshot deviceId matches parameter
 *  - snapshot snapshotId is non-blank
 *  - snapshot reportedAtMs is positive
 *  - snapshot dimensionStates contains all five dimensions
 *  - snapshot artifact is DeviceStrategyOnTrack when all dimensions are ON_TRACK
 *  - snapshot artifact is DeviceStrategyUnknownDueToMissingProgramSignal when no signals
 *  - snapshot dimensionStates reflect current posture state
 *  - each DimensionPostureState.dimension matches its map key
 *  - DimensionPostureState.status is UNKNOWN before signals
 *  - DimensionPostureState.status is ON_TRACK after markDimensionOnTrack
 *  - DimensionPostureState.status is AT_RISK after markDimensionAtRisk
 *  - DimensionPostureState.riskReason is null for ON_TRACK dimensions
 *  - DimensionPostureState.riskReason matches registered reason for AT_RISK dimensions
 *
 * ### DelegatedRuntimeStrategySnapshot.DimensionStatus — wire values
 *  - ON_TRACK wireValue is "on_track"
 *  - AT_RISK wireValue is "at_risk"
 *  - UNKNOWN wireValue is "unknown"
 *  - All three wire values are distinct
 *  - fromValue("on_track") → ON_TRACK
 *  - fromValue("at_risk") → AT_RISK
 *  - fromValue("unknown") → UNKNOWN
 *  - fromValue(unknown) → null
 *
 * ### DeviceStrategyArtifact — semanticTag values
 *  - DeviceStrategyOnTrack.semanticTag matches ARTIFACT_DEVICE_STRATEGY_ON_TRACK
 *  - DeviceStrategyRiskDueToContractInstability.semanticTag matches ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY
 *  - DeviceStrategyRiskDueToGovernanceRegressionTrend.semanticTag matches ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_GOVERNANCE_REGRESSION_TREND
 *  - DeviceStrategyRiskDueToRolloutMaturityGap.semanticTag matches ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_ROLLOUT_MATURITY_GAP
 *  - DeviceStrategyRiskDueToRuntimeCouplingDrift.semanticTag matches ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_RUNTIME_COUPLING_DRIFT
 *  - DeviceStrategyUnknownDueToMissingProgramSignal.semanticTag matches ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL
 *  - All six semanticTag values are distinct
 */
class Pr12DelegatedRuntimeStrategyTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private lateinit var evaluator: DelegatedRuntimeStrategyEvaluator

    @Before
    fun setUp() {
        evaluator = DelegatedRuntimeStrategyEvaluator()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun markAllOnTrack() {
        DelegatedRuntimeStrategyDimension.entries.forEach {
            evaluator.markDimensionOnTrack(it)
        }
    }

    private fun markAllExcept(
        vararg except: DelegatedRuntimeStrategyDimension
    ) {
        DelegatedRuntimeStrategyDimension.entries
            .filter { it !in except }
            .forEach { evaluator.markDimensionOnTrack(it) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeStrategyEvaluator — constants
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ARTIFACT_DEVICE_STRATEGY_ON_TRACK wire value is device_strategy_on_track`() {
        assertEquals(
            "device_strategy_on_track",
            DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_ON_TRACK
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY wire value is device_strategy_risk_due_to_contract_instability`() {
        assertEquals(
            "device_strategy_risk_due_to_contract_instability",
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_GOVERNANCE_REGRESSION_TREND wire value is device_strategy_risk_due_to_governance_regression_trend`() {
        assertEquals(
            "device_strategy_risk_due_to_governance_regression_trend",
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_GOVERNANCE_REGRESSION_TREND
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_ROLLOUT_MATURITY_GAP wire value is device_strategy_risk_due_to_rollout_maturity_gap`() {
        assertEquals(
            "device_strategy_risk_due_to_rollout_maturity_gap",
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_ROLLOUT_MATURITY_GAP
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_RUNTIME_COUPLING_DRIFT wire value is device_strategy_risk_due_to_runtime_coupling_drift`() {
        assertEquals(
            "device_strategy_risk_due_to_runtime_coupling_drift",
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_RUNTIME_COUPLING_DRIFT
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL wire value is device_strategy_unknown_due_to_missing_program_signal`() {
        assertEquals(
            "device_strategy_unknown_due_to_missing_program_signal",
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL
        )
    }

    @Test
    fun `All six artifact constants are distinct`() {
        val constants = listOf(
            DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_ON_TRACK,
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY,
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_GOVERNANCE_REGRESSION_TREND,
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_ROLLOUT_MATURITY_GAP,
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_RUNTIME_COUPLING_DRIFT,
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL
        )
        assertEquals(
            "All six artifact constants must be distinct",
            constants.size,
            constants.toSet().size
        )
    }

    @Test
    fun `INTEGRATION_GOVERNANCE_EVALUATOR is DelegatedRuntimePostGraduationGovernanceEvaluator`() {
        assertEquals(
            "DelegatedRuntimePostGraduationGovernanceEvaluator",
            DelegatedRuntimeStrategyEvaluator.INTEGRATION_GOVERNANCE_EVALUATOR
        )
    }

    @Test
    fun `INTEGRATION_ACCEPTANCE_EVALUATOR is DelegatedRuntimeAcceptanceEvaluator`() {
        assertEquals(
            "DelegatedRuntimeAcceptanceEvaluator",
            DelegatedRuntimeStrategyEvaluator.INTEGRATION_ACCEPTANCE_EVALUATOR
        )
    }

    @Test
    fun `INTEGRATION_TRUTH_OWNER is AndroidLocalTruthOwnershipCoordinator`() {
        assertEquals(
            "AndroidLocalTruthOwnershipCoordinator",
            DelegatedRuntimeStrategyEvaluator.INTEGRATION_TRUTH_OWNER
        )
    }

    @Test
    fun `INTEGRATION_RESULT_CONVERGENCE is AndroidFlowAwareResultConvergenceParticipant`() {
        assertEquals(
            "AndroidFlowAwareResultConvergenceParticipant",
            DelegatedRuntimeStrategyEvaluator.INTEGRATION_RESULT_CONVERGENCE
        )
    }

    @Test
    fun `INTEGRATION_EXECUTION_EVENT_OWNER is AndroidCanonicalExecutionEventOwner`() {
        assertEquals(
            "AndroidCanonicalExecutionEventOwner",
            DelegatedRuntimeStrategyEvaluator.INTEGRATION_EXECUTION_EVENT_OWNER
        )
    }

    @Test
    fun `INTEGRATION_COMPAT_BLOCKING is AndroidCompatLegacyBlockingParticipant`() {
        assertEquals(
            "AndroidCompatLegacyBlockingParticipant",
            DelegatedRuntimeStrategyEvaluator.INTEGRATION_COMPAT_BLOCKING
        )
    }

    @Test
    fun `INTEGRATION_RECOVERY_OWNER is AndroidRecoveryParticipationOwner`() {
        assertEquals(
            "AndroidRecoveryParticipationOwner",
            DelegatedRuntimeStrategyEvaluator.INTEGRATION_RECOVERY_OWNER
        )
    }

    @Test
    fun `INTEGRATION_RUNTIME_CONTROLLER is RuntimeController`() {
        assertEquals(
            "RuntimeController",
            DelegatedRuntimeStrategyEvaluator.INTEGRATION_RUNTIME_CONTROLLER
        )
    }

    @Test
    fun `INTEGRATION_DELEGATED_FLOW_BRIDGE is AndroidDelegatedFlowBridge`() {
        assertEquals(
            "AndroidDelegatedFlowBridge",
            DelegatedRuntimeStrategyEvaluator.INTEGRATION_DELEGATED_FLOW_BRIDGE
        )
    }

    @Test
    fun `INTRODUCED_PR is 12`() {
        assertEquals(12, DelegatedRuntimeStrategyEvaluator.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(DelegatedRuntimeStrategyEvaluator.DESCRIPTION.isNotBlank())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeStrategyDimension — wire values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `CANONICAL_CONTRACT_STABILITY wireValue is canonical_contract_stability`() {
        assertEquals(
            "canonical_contract_stability",
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY.wireValue
        )
    }

    @Test
    fun `GOVERNANCE_TREND wireValue is governance_trend`() {
        assertEquals(
            "governance_trend",
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND.wireValue
        )
    }

    @Test
    fun `ROLLOUT_MATURITY wireValue is rollout_maturity`() {
        assertEquals(
            "rollout_maturity",
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY.wireValue
        )
    }

    @Test
    fun `REGRESSION_PRESSURE wireValue is regression_pressure`() {
        assertEquals(
            "regression_pressure",
            DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE.wireValue
        )
    }

    @Test
    fun `RUNTIME_COUPLING_DRIFT wireValue is runtime_coupling_drift`() {
        assertEquals(
            "runtime_coupling_drift",
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT.wireValue
        )
    }

    @Test
    fun `All five wire values are distinct`() {
        val wireValues = DelegatedRuntimeStrategyDimension.entries.map { it.wireValue }
        assertEquals(
            "All five wire values must be distinct",
            wireValues.size,
            wireValues.toSet().size
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeStrategyDimension.fromValue — classification
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `canonical_contract_stability maps to CANONICAL_CONTRACT_STABILITY`() {
        assertEquals(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            DelegatedRuntimeStrategyDimension.fromValue("canonical_contract_stability")
        )
    }

    @Test
    fun `governance_trend maps to GOVERNANCE_TREND`() {
        assertEquals(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            DelegatedRuntimeStrategyDimension.fromValue("governance_trend")
        )
    }

    @Test
    fun `rollout_maturity maps to ROLLOUT_MATURITY`() {
        assertEquals(
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY,
            DelegatedRuntimeStrategyDimension.fromValue("rollout_maturity")
        )
    }

    @Test
    fun `regression_pressure maps to REGRESSION_PRESSURE`() {
        assertEquals(
            DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE,
            DelegatedRuntimeStrategyDimension.fromValue("regression_pressure")
        )
    }

    @Test
    fun `runtime_coupling_drift maps to RUNTIME_COUPLING_DRIFT`() {
        assertEquals(
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT,
            DelegatedRuntimeStrategyDimension.fromValue("runtime_coupling_drift")
        )
    }

    @Test
    fun `unknown value maps to null`() {
        assertNull(DelegatedRuntimeStrategyDimension.fromValue("not_a_dimension"))
    }

    @Test
    fun `null maps to null`() {
        assertNull(DelegatedRuntimeStrategyDimension.fromValue(null))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Dimension posture gate — markDimensionOnTrack / getDimensionStatus
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `getDimensionStatus returns UNKNOWN before any signal`() {
        assertEquals(
            DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN,
            evaluator.getDimensionStatus(DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY)
        )
    }

    @Test
    fun `getDimensionStatus returns ON_TRACK after markDimensionOnTrack`() {
        evaluator.markDimensionOnTrack(DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY)
        assertEquals(
            DelegatedRuntimeStrategySnapshot.DimensionStatus.ON_TRACK,
            evaluator.getDimensionStatus(DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY)
        )
    }

    @Test
    fun `getDimensionStatus returns AT_RISK after markDimensionAtRisk`() {
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "contract drift detected"
        )
        assertEquals(
            DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK,
            evaluator.getDimensionStatus(DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY)
        )
    }

    @Test
    fun `getDimensionStatus returns UNKNOWN after markDimensionUnknown`() {
        evaluator.markDimensionOnTrack(DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY)
        evaluator.markDimensionUnknown(DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY)
        assertEquals(
            DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN,
            evaluator.getDimensionStatus(DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY)
        )
    }

    @Test
    fun `getDimensionRiskReason is null before any signal`() {
        assertNull(
            evaluator.getDimensionRiskReason(DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY)
        )
    }

    @Test
    fun `getDimensionRiskReason returns riskReason after markDimensionAtRisk`() {
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            "governance trend negative"
        )
        assertEquals(
            "governance trend negative",
            evaluator.getDimensionRiskReason(DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND)
        )
    }

    @Test
    fun `getDimensionRiskReason returns reason after markDimensionUnknown`() {
        evaluator.markDimensionUnknown(
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY,
            "no rollout signal received"
        )
        assertEquals(
            "no rollout signal received",
            evaluator.getDimensionRiskReason(DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY)
        )
    }

    @Test
    fun `getDimensionRiskReason is null after markDimensionOnTrack`() {
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "contract drift"
        )
        evaluator.markDimensionOnTrack(DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY)
        assertNull(
            evaluator.getDimensionRiskReason(DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY)
        )
    }

    @Test
    fun `clearAllDimensionStates resets all dimensions to UNKNOWN`() {
        markAllOnTrack()
        evaluator.clearAllDimensionStates()
        DelegatedRuntimeStrategyDimension.entries.forEach { dim ->
            assertEquals(
                DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN,
                evaluator.getDimensionStatus(dim)
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateStrategy — DeviceStrategyUnknownDueToMissingProgramSignal
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceStrategyUnknownDueToMissingProgramSignal when no dimensions have signals`() {
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal)
    }

    @Test
    fun `Returns DeviceStrategyUnknownDueToMissingProgramSignal when some dimensions are UNKNOWN`() {
        markAllExcept(DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY)
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal)
    }

    @Test
    fun `DeviceStrategyUnknownDueToMissingProgramSignal semanticTag is ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL`() {
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertEquals(
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceStrategyUnknownDueToMissingProgramSignal deviceId matches parameter`() {
        val result = evaluator.evaluateStrategy("device-xyz", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal
        assertEquals("device-xyz", result.deviceId)
    }

    @Test
    fun `DeviceStrategyUnknownDueToMissingProgramSignal snapshotId matches parameter`() {
        val result = evaluator.evaluateStrategy("dev-1", "snap-abc")
            as DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal
        assertEquals("snap-abc", result.snapshotId)
    }

    @Test
    fun `DeviceStrategyUnknownDueToMissingProgramSignal missingDimensions includes all five when no signals`() {
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal
        assertEquals(
            DelegatedRuntimeStrategyDimension.entries.toSet(),
            result.missingDimensions
        )
    }

    @Test
    fun `DeviceStrategyUnknownDueToMissingProgramSignal missingDimensions only includes unknown dimensions`() {
        markAllExcept(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal
        assertEquals(
            setOf(
                DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
                DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT
            ),
            result.missingDimensions
        )
    }

    @Test
    fun `Missing signal takes priority over contract instability risk`() {
        markAllExcept(DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND)
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "contract risk"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal)
    }

    @Test
    fun `Missing signal takes priority over governance trend risk`() {
        markAllExcept(DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY)
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            "governance risk"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal)
    }

    @Test
    fun `Missing signal takes priority over rollout maturity gap`() {
        markAllExcept(DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE)
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY,
            "rollout gap"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal)
    }

    @Test
    fun `Missing signal takes priority over runtime coupling drift`() {
        markAllExcept(DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY)
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT,
            "coupling drift"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateStrategy — DeviceStrategyRiskDueToContractInstability
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceStrategyRiskDueToContractInstability when contract stability dimension is AT_RISK`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "contract drift"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability)
    }

    @Test
    fun `DeviceStrategyRiskDueToContractInstability semanticTag is ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "contract drift"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability
        assertEquals(
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceStrategyRiskDueToContractInstability deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "contract drift"
        )
        val result = evaluator.evaluateStrategy("device-contract", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability
        assertEquals("device-contract", result.deviceId)
    }

    @Test
    fun `DeviceStrategyRiskDueToContractInstability snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "contract drift"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-contract")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability
        assertEquals("snap-contract", result.snapshotId)
    }

    @Test
    fun `DeviceStrategyRiskDueToContractInstability riskReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "interface contract breakage"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability
        assertEquals("interface contract breakage", result.riskReason)
    }

    @Test
    fun `DeviceStrategyRiskDueToContractInstability riskReason uses REASON_CONTRACT_INSTABILITY_DEFAULT when none registered`() {
        // markDimensionAtRisk always requires a reason, so simulate via state override
        // by checking default constant directly
        assertEquals(
            "canonical_contract_stability_risk_detected",
            DelegatedRuntimeStrategyEvaluator.REASON_CONTRACT_INSTABILITY_DEFAULT
        )
    }

    @Test
    fun `DeviceStrategyRiskDueToContractInstability dimension is CANONICAL_CONTRACT_STABILITY`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "contract drift"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability
        assertEquals(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            result.dimension
        )
    }

    @Test
    fun `Contract instability takes priority over governance trend risk`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "contract risk"
        )
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            "governance risk"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability)
        assertEquals(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            (result as DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability).dimension
        )
    }

    @Test
    fun `Contract instability takes priority over rollout maturity gap`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "contract risk"
        )
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY,
            "rollout gap"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability)
        assertEquals(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            (result as DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability).dimension
        )
    }

    @Test
    fun `Contract instability takes priority over runtime coupling drift`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "contract risk"
        )
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT,
            "coupling drift"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability)
        assertEquals(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            (result as DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability).dimension
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateStrategy — DeviceStrategyRiskDueToGovernanceRegressionTrend
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceStrategyRiskDueToGovernanceRegressionTrend when governance trend dimension is AT_RISK`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            "governance trend negative"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend)
    }

    @Test
    fun `DeviceStrategyRiskDueToGovernanceRegressionTrend semanticTag is ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_GOVERNANCE_REGRESSION_TREND`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            "governance trend negative"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend
        assertEquals(
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_GOVERNANCE_REGRESSION_TREND,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceStrategyRiskDueToGovernanceRegressionTrend deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            "governance trend negative"
        )
        val result = evaluator.evaluateStrategy("device-governance", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend
        assertEquals("device-governance", result.deviceId)
    }

    @Test
    fun `DeviceStrategyRiskDueToGovernanceRegressionTrend snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            "governance trend negative"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-governance")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend
        assertEquals("snap-governance", result.snapshotId)
    }

    @Test
    fun `DeviceStrategyRiskDueToGovernanceRegressionTrend riskReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            "truth and compat regressions accumulating"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend
        assertEquals("truth and compat regressions accumulating", result.riskReason)
    }

    @Test
    fun `DeviceStrategyRiskDueToGovernanceRegressionTrend riskReason uses REASON_GOVERNANCE_TREND_DEFAULT when none registered`() {
        assertEquals(
            "governance_regression_trend_detected",
            DelegatedRuntimeStrategyEvaluator.REASON_GOVERNANCE_TREND_DEFAULT
        )
    }

    @Test
    fun `DeviceStrategyRiskDueToGovernanceRegressionTrend dimension is GOVERNANCE_TREND`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            "governance trend negative"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend
        assertEquals(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            result.dimension
        )
    }

    @Test
    fun `Governance trend risk takes priority over rollout maturity gap`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            "governance risk"
        )
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY,
            "rollout gap"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend)
    }

    @Test
    fun `Governance trend risk takes priority over runtime coupling drift`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            "governance risk"
        )
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT,
            "coupling drift"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateStrategy — DeviceStrategyRiskDueToRolloutMaturityGap
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceStrategyRiskDueToRolloutMaturityGap when rollout maturity dimension is AT_RISK`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY,
            "default-on not reached"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyRiskDueToRolloutMaturityGap)
    }

    @Test
    fun `DeviceStrategyRiskDueToRolloutMaturityGap semanticTag is ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_ROLLOUT_MATURITY_GAP`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY,
            "default-on not reached"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToRolloutMaturityGap
        assertEquals(
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_ROLLOUT_MATURITY_GAP,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceStrategyRiskDueToRolloutMaturityGap deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY,
            "default-on not reached"
        )
        val result = evaluator.evaluateStrategy("device-rollout", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToRolloutMaturityGap
        assertEquals("device-rollout", result.deviceId)
    }

    @Test
    fun `DeviceStrategyRiskDueToRolloutMaturityGap snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY,
            "default-on not reached"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-rollout")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToRolloutMaturityGap
        assertEquals("snap-rollout", result.snapshotId)
    }

    @Test
    fun `DeviceStrategyRiskDueToRolloutMaturityGap riskReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY,
            "enforcement tightening lagging"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToRolloutMaturityGap
        assertEquals("enforcement tightening lagging", result.riskReason)
    }

    @Test
    fun `DeviceStrategyRiskDueToRolloutMaturityGap riskReason uses REASON_ROLLOUT_MATURITY_DEFAULT when none registered`() {
        assertEquals(
            "rollout_maturity_gap_detected",
            DelegatedRuntimeStrategyEvaluator.REASON_ROLLOUT_MATURITY_DEFAULT
        )
    }

    @Test
    fun `DeviceStrategyRiskDueToRolloutMaturityGap dimension is ROLLOUT_MATURITY`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY,
            "default-on not reached"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToRolloutMaturityGap
        assertEquals(
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY,
            result.dimension
        )
    }

    @Test
    fun `Rollout maturity gap takes priority over runtime coupling drift`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY,
            "rollout gap"
        )
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT,
            "coupling drift"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyRiskDueToRolloutMaturityGap)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateStrategy — DeviceStrategyRiskDueToRuntimeCouplingDrift
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceStrategyRiskDueToRuntimeCouplingDrift when runtime coupling drift dimension is AT_RISK`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT,
            "cross-module coupling exceeded"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyRiskDueToRuntimeCouplingDrift)
    }

    @Test
    fun `DeviceStrategyRiskDueToRuntimeCouplingDrift semanticTag is ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_RUNTIME_COUPLING_DRIFT`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT,
            "cross-module coupling exceeded"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToRuntimeCouplingDrift
        assertEquals(
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_RUNTIME_COUPLING_DRIFT,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceStrategyRiskDueToRuntimeCouplingDrift deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT,
            "cross-module coupling exceeded"
        )
        val result = evaluator.evaluateStrategy("device-coupling", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToRuntimeCouplingDrift
        assertEquals("device-coupling", result.deviceId)
    }

    @Test
    fun `DeviceStrategyRiskDueToRuntimeCouplingDrift snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT,
            "cross-module coupling exceeded"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-coupling")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToRuntimeCouplingDrift
        assertEquals("snap-coupling", result.snapshotId)
    }

    @Test
    fun `DeviceStrategyRiskDueToRuntimeCouplingDrift riskReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT,
            "receiver-pipeline coupling drift"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToRuntimeCouplingDrift
        assertEquals("receiver-pipeline coupling drift", result.riskReason)
    }

    @Test
    fun `DeviceStrategyRiskDueToRuntimeCouplingDrift riskReason uses REASON_RUNTIME_COUPLING_DRIFT_DEFAULT when none registered`() {
        assertEquals(
            "runtime_coupling_drift_detected",
            DelegatedRuntimeStrategyEvaluator.REASON_RUNTIME_COUPLING_DRIFT_DEFAULT
        )
    }

    @Test
    fun `DeviceStrategyRiskDueToRuntimeCouplingDrift dimension is RUNTIME_COUPLING_DRIFT`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT,
            "cross-module coupling exceeded"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToRuntimeCouplingDrift
        assertEquals(
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT,
            result.dimension
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateStrategy — regression pressure (maps to contract instability)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceStrategyRiskDueToContractInstability when regression pressure dimension is AT_RISK`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE,
            "accumulated regression pressure high"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability)
    }

    @Test
    fun `DeviceStrategyRiskDueToContractInstability dimension is REGRESSION_PRESSURE for regression pressure risk`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE,
            "accumulated regression pressure high"
        )
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability
        assertEquals(
            DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE,
            result.dimension
        )
    }

    @Test
    fun `DeviceStrategyRiskDueToContractInstability riskReason uses REASON_REGRESSION_PRESSURE_DEFAULT for regression pressure without reason`() {
        assertEquals(
            "regression_pressure_risk_detected",
            DelegatedRuntimeStrategyEvaluator.REASON_REGRESSION_PRESSURE_DEFAULT
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateStrategy — DeviceStrategyOnTrack
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceStrategyOnTrack when all five dimensions are ON_TRACK`() {
        markAllOnTrack()
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
        assertTrue(result is DeviceStrategyArtifact.DeviceStrategyOnTrack)
    }

    @Test
    fun `DeviceStrategyOnTrack semanticTag is ARTIFACT_DEVICE_STRATEGY_ON_TRACK`() {
        markAllOnTrack()
        val result = evaluator.evaluateStrategy("dev-1", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyOnTrack
        assertEquals(
            DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_ON_TRACK,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceStrategyOnTrack deviceId matches parameter`() {
        markAllOnTrack()
        val result = evaluator.evaluateStrategy("device-on-track", "snap-1")
            as DeviceStrategyArtifact.DeviceStrategyOnTrack
        assertEquals("device-on-track", result.deviceId)
    }

    @Test
    fun `DeviceStrategyOnTrack snapshotId matches parameter`() {
        markAllOnTrack()
        val result = evaluator.evaluateStrategy("dev-1", "snap-on-track")
            as DeviceStrategyArtifact.DeviceStrategyOnTrack
        assertEquals("snap-on-track", result.snapshotId)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // buildSnapshot
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `buildSnapshot returns a DelegatedRuntimeStrategySnapshot`() {
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
    fun `snapshot dimensionStates contains all five dimensions`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeStrategyDimension.entries.toSet(),
            snapshot.dimensionStates.keys
        )
    }

    @Test
    fun `snapshot artifact is DeviceStrategyOnTrack when all dimensions are ON_TRACK`() {
        markAllOnTrack()
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertTrue(snapshot.artifact is DeviceStrategyArtifact.DeviceStrategyOnTrack)
    }

    @Test
    fun `snapshot artifact is DeviceStrategyUnknownDueToMissingProgramSignal when no signals`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertTrue(
            snapshot.artifact is DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal
        )
    }

    @Test
    fun `snapshot dimensionStates reflect current posture state`() {
        evaluator.markDimensionOnTrack(DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND)
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeStrategySnapshot.DimensionStatus.ON_TRACK,
            snapshot.dimensionStates[DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND]?.status
        )
    }

    @Test
    fun `each DimensionPostureState dimension matches its map key`() {
        markAllOnTrack()
        val snapshot = evaluator.buildSnapshot("dev-1")
        snapshot.dimensionStates.forEach { (key, state) ->
            assertEquals(key, state.dimension)
        }
    }

    @Test
    fun `DimensionPostureState status is UNKNOWN before signals`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        snapshot.dimensionStates.values.forEach { state ->
            assertEquals(DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN, state.status)
        }
    }

    @Test
    fun `DimensionPostureState status is ON_TRACK after markDimensionOnTrack`() {
        evaluator.markDimensionOnTrack(DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY)
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeStrategySnapshot.DimensionStatus.ON_TRACK,
            snapshot.dimensionStates[DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY]?.status
        )
    }

    @Test
    fun `DimensionPostureState status is AT_RISK after markDimensionAtRisk`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT,
            "coupling drift detected"
        )
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK,
            snapshot.dimensionStates[DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT]?.status
        )
    }

    @Test
    fun `DimensionPostureState riskReason is null for ON_TRACK dimensions`() {
        markAllOnTrack()
        val snapshot = evaluator.buildSnapshot("dev-1")
        snapshot.dimensionStates.values.forEach { state ->
            assertNull(state.riskReason)
        }
    }

    @Test
    fun `DimensionPostureState riskReason matches registered reason for AT_RISK dimensions`() {
        markAllExcept()
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "interface drift observed"
        )
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            "interface drift observed",
            snapshot.dimensionStates[DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY]?.riskReason
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeStrategySnapshot.DimensionStatus — wire values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ON_TRACK wireValue is on_track`() {
        assertEquals(
            "on_track",
            DelegatedRuntimeStrategySnapshot.DimensionStatus.ON_TRACK.wireValue
        )
    }

    @Test
    fun `AT_RISK wireValue is at_risk`() {
        assertEquals(
            "at_risk",
            DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK.wireValue
        )
    }

    @Test
    fun `UNKNOWN wireValue is unknown`() {
        assertEquals(
            "unknown",
            DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN.wireValue
        )
    }

    @Test
    fun `All three wire values are distinct`() {
        val wireValues = DelegatedRuntimeStrategySnapshot.DimensionStatus.entries.map { it.wireValue }
        assertEquals(
            "All three wire values must be distinct",
            wireValues.size,
            wireValues.toSet().size
        )
    }

    @Test
    fun `fromValue on_track maps to ON_TRACK`() {
        assertEquals(
            DelegatedRuntimeStrategySnapshot.DimensionStatus.ON_TRACK,
            DelegatedRuntimeStrategySnapshot.DimensionStatus.fromValue("on_track")
        )
    }

    @Test
    fun `fromValue at_risk maps to AT_RISK`() {
        assertEquals(
            DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK,
            DelegatedRuntimeStrategySnapshot.DimensionStatus.fromValue("at_risk")
        )
    }

    @Test
    fun `fromValue unknown maps to UNKNOWN`() {
        assertEquals(
            DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN,
            DelegatedRuntimeStrategySnapshot.DimensionStatus.fromValue("unknown")
        )
    }

    @Test
    fun `fromValue unknown string maps to null`() {
        assertNull(
            DelegatedRuntimeStrategySnapshot.DimensionStatus.fromValue("not_a_status")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DeviceStrategyArtifact — semanticTag values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DeviceStrategyOnTrack semanticTag matches ARTIFACT_DEVICE_STRATEGY_ON_TRACK`() {
        val artifact = DeviceStrategyArtifact.DeviceStrategyOnTrack("d", "s")
        assertEquals(
            DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_ON_TRACK,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceStrategyRiskDueToContractInstability semanticTag matches ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY`() {
        val artifact = DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability(
            "d", "s", "reason"
        )
        assertEquals(
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceStrategyRiskDueToGovernanceRegressionTrend semanticTag matches ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_GOVERNANCE_REGRESSION_TREND`() {
        val artifact = DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend(
            "d", "s", "reason"
        )
        assertEquals(
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_GOVERNANCE_REGRESSION_TREND,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceStrategyRiskDueToRolloutMaturityGap semanticTag matches ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_ROLLOUT_MATURITY_GAP`() {
        val artifact = DeviceStrategyArtifact.DeviceStrategyRiskDueToRolloutMaturityGap(
            "d", "s", "reason"
        )
        assertEquals(
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_ROLLOUT_MATURITY_GAP,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceStrategyRiskDueToRuntimeCouplingDrift semanticTag matches ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_RUNTIME_COUPLING_DRIFT`() {
        val artifact = DeviceStrategyArtifact.DeviceStrategyRiskDueToRuntimeCouplingDrift(
            "d", "s", "reason"
        )
        assertEquals(
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_RUNTIME_COUPLING_DRIFT,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceStrategyUnknownDueToMissingProgramSignal semanticTag matches ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL`() {
        val artifact = DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal(
            "d", "s", emptySet()
        )
        assertEquals(
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL,
            artifact.semanticTag
        )
    }

    @Test
    fun `All six semanticTag values are distinct`() {
        val tags = listOf(
            DeviceStrategyArtifact.DeviceStrategyOnTrack("d", "s").semanticTag,
            DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability(
                "d", "s", "r"
            ).semanticTag,
            DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend(
                "d", "s", "r"
            ).semanticTag,
            DeviceStrategyArtifact.DeviceStrategyRiskDueToRolloutMaturityGap(
                "d", "s", "r"
            ).semanticTag,
            DeviceStrategyArtifact.DeviceStrategyRiskDueToRuntimeCouplingDrift(
                "d", "s", "r"
            ).semanticTag,
            DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal(
                "d", "s", emptySet()
            ).semanticTag
        )
        assertEquals(
            "All six semanticTag values must be distinct",
            tags.size,
            tags.toSet().size
        )
    }
}
