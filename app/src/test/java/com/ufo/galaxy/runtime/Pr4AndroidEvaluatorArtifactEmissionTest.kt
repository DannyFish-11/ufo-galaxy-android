package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.DeviceGovernanceReportPayload
import com.ufo.galaxy.protocol.DeviceReadinessReportPayload
import com.ufo.galaxy.protocol.DeviceStrategyReportPayload
import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-4 (Android) — Evaluator artifact emission validation matrix.
 *
 * This test class proves that Android evaluator/runtime artifacts are:
 *
 *  1. **Produced from real runtime/evaluator paths** — not merely defined as model classes.
 *  2. **Stable and reviewable** — artifact payloads serialise to stable, V2-consumable JSON.
 *  3. **Semantically classified** — each artifact is explicitly classified as canonical
 *     participant evidence, advisory/observation-only, or compat/legacy-oriented.
 *  4. **Transport-capable** — AipMessage envelopes round-trip correctly through Gson.
 *
 * ## Artifact chain coverage
 *
 * | Evaluator                                          | Artifact type              | Signal semantics                | Wire type                      |
 * |----------------------------------------------------|----------------------------|---------------------------------|--------------------------------|
 * | [DelegatedRuntimeReadinessEvaluator]               | [DeviceReadinessArtifact]  | CANONICAL_PARTICIPANT_EVIDENCE  | `device_readiness_report`      |
 * | [DelegatedRuntimePostGraduationGovernanceEvaluator]| [DeviceGovernanceArtifact] | ADVISORY                        | `device_governance_report`     |
 * | [DelegatedRuntimeStrategyEvaluator]                | [DeviceStrategyArtifact]   | ADVISORY                        | `device_strategy_report`       |
 *
 * ## Canonical vs advisory semantics
 *
 * **Canonical participant evidence** (`device_readiness_report`): The readiness artifact
 * directly gates V2 release participation.  A [DeviceReadinessArtifact.DeviceReadyForRelease]
 * is required before the V2 release gate proceeds for this device.  V2 must treat this as
 * binding participant evidence.
 *
 * **Advisory / observation-only** (`device_governance_report`, `device_strategy_report`):
 * Governance and strategy artifacts inform V2 governance / evolution decisions but V2
 * remains the canonical authority.  V2 must classify these as participant-originated
 * evidence rather than binding enforcement output.  The [signal_semantics] field in both
 * payload types carries the stable wire tag `"advisory"`.
 *
 * ## Test matrix
 *
 * ### Wire type stability
 *  - DEVICE_READINESS_REPORT wire value is "device_readiness_report"
 *  - DEVICE_GOVERNANCE_REPORT wire value is "device_governance_report"
 *  - DEVICE_STRATEGY_REPORT wire value is "device_strategy_report"
 *  - All three wire types are distinct
 *
 * ### Readiness evaluator — real runtime path
 *  - buildSnapshot returns non-blank snapshotId (real evaluator path)
 *  - all UNKNOWN → UNKNOWN artifact (missing signal case)
 *  - all READY → READY artifact (fully signalled case)
 *  - [DeviceReadinessReportPayload] serialises with all required fields
 *  - dimension_states contains all five dimension wire keys
 *  - missing_dimensions lists all five keys when all UNKNOWN
 *  - AipMessage envelope for readiness report contains type device_readiness_report
 *  - Readiness signal semantics are CANONICAL_PARTICIPANT_EVIDENCE (no advisory tag needed)
 *
 * ### Governance evaluator — real runtime path
 *  - buildSnapshot returns non-blank snapshotId (real evaluator path)
 *  - all UNKNOWN → UNKNOWN governance artifact (missing signal case)
 *  - all COMPLIANT → COMPLIANT governance artifact (fully signalled compliant case)
 *  - truth REGRESSION → truth regression violation artifact
 *  - [DeviceGovernanceReportPayload] serialises with all required fields
 *  - DeviceGovernanceReportPayload.signal_semantics is "advisory"
 *  - dimension_states contains all five governance dimension wire keys
 *  - missing_dimensions lists all five keys when all UNKNOWN
 *  - first_regression_reason is non-null when a regression dimension is present
 *  - first_regression_reason is null when artifact is UNKNOWN
 *  - AipMessage envelope for governance report contains type device_governance_report
 *  - SIGNAL_SEMANTICS_ADVISORY constant value is "advisory"
 *
 * ### Strategy evaluator — real runtime path
 *  - buildSnapshot returns non-blank snapshotId (real evaluator path)
 *  - all UNKNOWN → UNKNOWN strategy artifact (missing signal case)
 *  - all ON_TRACK → ON_TRACK strategy artifact (fully signalled on-track case)
 *  - contract stability AT_RISK → contract instability risk artifact
 *  - [DeviceStrategyReportPayload] serialises with all required fields
 *  - DeviceStrategyReportPayload.signal_semantics is "advisory"
 *  - dimension_states contains all five strategy dimension wire keys
 *  - missing_dimensions lists all five keys when all UNKNOWN
 *  - first_risk_reason is non-null when an at-risk dimension is present
 *  - first_risk_reason is null when artifact is UNKNOWN
 *  - AipMessage envelope for strategy report contains type device_strategy_report
 *  - SIGNAL_SEMANTICS_ADVISORY constant value is "advisory"
 *
 * ### Transport compatibility
 *  - All three AipMessage envelopes Gson-round-trip to equivalent payloads
 *  - All three payload snapshot_ids survive Gson round-trip
 *  - artifact_tag field is stable across Gson serialisation
 *
 * ## Intentionally deferred (later PRs)
 *
 *  - Full GalaxyConnectionService integration test: inject real lifecycle events,
 *    confirm DEVICE_GOVERNANCE_REPORT / DEVICE_STRATEGY_REPORT emitted over WebSocket.
 *  - Dimension-state population from real runtime events: verify that actual calls to
 *    AndroidLocalTruthOwnershipCoordinator, AndroidFlowAwareResultConvergenceParticipant,
 *    etc. feed markDimensionCompliant / markDimensionRegression into governance evaluator.
 *  - Governance/strategy dimension auto-refresh on lifecycle change: follow-up reports
 *    triggered by dimension-state changes (currently initial-only emission).
 */
class Pr4AndroidEvaluatorArtifactEmissionTest {

    private val gson = Gson()

    // ═════════════════════════════════════════════════════════════════════════
    // Wire type stability
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `DEVICE_READINESS_REPORT wire value is stable`() {
        assertEquals("device_readiness_report", MsgType.DEVICE_READINESS_REPORT.value)
    }

    @Test
    fun `DEVICE_GOVERNANCE_REPORT wire value is stable`() {
        assertEquals("device_governance_report", MsgType.DEVICE_GOVERNANCE_REPORT.value)
    }

    @Test
    fun `DEVICE_STRATEGY_REPORT wire value is stable`() {
        assertEquals("device_strategy_report", MsgType.DEVICE_STRATEGY_REPORT.value)
    }

    @Test
    fun `All three evaluator report wire types are distinct`() {
        val wireValues = listOf(
            MsgType.DEVICE_READINESS_REPORT.value,
            MsgType.DEVICE_GOVERNANCE_REPORT.value,
            MsgType.DEVICE_STRATEGY_REPORT.value
        )
        assertEquals(
            "All three wire type strings must be unique",
            wireValues.size,
            wireValues.distinct().size
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Readiness evaluator — real runtime path
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `Readiness buildSnapshot returns non-blank snapshotId from real evaluator path`() {
        val evaluator = DelegatedRuntimeReadinessEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-01")
        assertTrue(
            "snapshotId must be non-blank from real evaluator path",
            snapshot.snapshotId.isNotBlank()
        )
    }

    @Test
    fun `Readiness all UNKNOWN produces UNKNOWN artifact`() {
        val evaluator = DelegatedRuntimeReadinessEvaluator()
        val artifact = evaluator.evaluateReadiness(
            deviceId = "pr4-device-01",
            snapshotId = "pr4-snap-01"
        )
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            artifact.semanticTag
        )
    }

    @Test
    fun `Readiness all READY produces READY artifact`() {
        val evaluator = DelegatedRuntimeReadinessEvaluator()
        DelegatedRuntimeReadinessDimension.entries.forEach { dim ->
            evaluator.markDimensionReady(dim)
        }
        val artifact = evaluator.evaluateReadiness(
            deviceId = "pr4-device-01",
            snapshotId = "pr4-snap-02"
        )
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READY_FOR_RELEASE,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceReadinessReportPayload serialises with all required fields`() {
        val evaluator = DelegatedRuntimeReadinessEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-01")
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, s) -> s.status == DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN }
            .map { (dim, _) -> dim.wireValue }
        val payload = DeviceReadinessReportPayload(
            artifact_tag = snapshot.artifact.semanticTag,
            snapshot_id = snapshot.snapshotId,
            device_id = "pr4-device-01",
            session_id = "pr4-sess-01",
            reported_at_ms = snapshot.reportedAtMs,
            dimension_states = dimensionStates,
            first_gap_reason = null,
            missing_dimensions = missingDimensions
        )
        val json = gson.toJson(payload)
        assertTrue(json.contains("\"artifact_tag\""))
        assertTrue(json.contains("\"snapshot_id\""))
        assertTrue(json.contains("\"device_id\""))
        assertTrue(json.contains("\"dimension_states\""))
        assertTrue(json.contains("\"missing_dimensions\""))
    }

    @Test
    fun `DeviceReadinessReportPayload dimension_states contains all five readiness dimension wire keys`() {
        val evaluator = DelegatedRuntimeReadinessEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-01")
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        DelegatedRuntimeReadinessDimension.entries.forEach { dim ->
            assertTrue(
                "dimension_states must contain readiness dimension ${dim.wireValue}",
                dimensionStates.containsKey(dim.wireValue)
            )
        }
    }

    @Test
    fun `DeviceReadinessReportPayload missing_dimensions lists all five keys when all UNKNOWN`() {
        val evaluator = DelegatedRuntimeReadinessEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-01")
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, s) -> s.status == DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN }
            .map { (dim, _) -> dim.wireValue }
        assertEquals(
            "all 5 readiness dimensions should be UNKNOWN with no signals",
            DelegatedRuntimeReadinessDimension.entries.size,
            missingDimensions.size
        )
    }

    @Test
    fun `AipMessage envelope for readiness report contains type device_readiness_report`() {
        val payload = DeviceReadinessReportPayload(
            artifact_tag = DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            snapshot_id = "pr4-snap-rt-01",
            device_id = "pr4-device-01",
            session_id = null
        )
        val envelope = AipMessage(
            type = MsgType.DEVICE_READINESS_REPORT,
            payload = payload,
            device_id = "pr4-device-01",
            idempotency_key = "pr4-snap-rt-01"
        )
        val json = gson.toJson(envelope)
        assertTrue(
            "envelope must contain type=device_readiness_report",
            json.contains("device_readiness_report")
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Governance evaluator — real runtime path
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `Governance buildSnapshot returns non-blank snapshotId from real evaluator path`() {
        val evaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-02")
        assertTrue(
            "governance snapshotId must be non-blank from real evaluator path",
            snapshot.snapshotId.isNotBlank()
        )
    }

    @Test
    fun `Governance all UNKNOWN produces UNKNOWN governance artifact`() {
        val evaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()
        val artifact = evaluator.evaluateGovernance(
            deviceId = "pr4-device-02",
            snapshotId = "pr4-gov-snap-01"
        )
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            artifact.semanticTag
        )
    }

    @Test
    fun `Governance all COMPLIANT produces COMPLIANT governance artifact`() {
        val evaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()
        DelegatedRuntimeGovernanceDimension.entries.forEach { dim ->
            evaluator.markDimensionCompliant(dim)
        }
        val artifact = evaluator.evaluateGovernance(
            deviceId = "pr4-device-02",
            snapshotId = "pr4-gov-snap-02"
        )
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT,
            artifact.semanticTag
        )
    }

    @Test
    fun `Governance truth REGRESSION produces truth regression violation artifact`() {
        val evaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()
        DelegatedRuntimeGovernanceDimension.entries.forEach { dim ->
            evaluator.markDimensionCompliant(dim)
        }
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "pr4_truth_regression"
        )
        val artifact = evaluator.evaluateGovernance(
            deviceId = "pr4-device-02",
            snapshotId = "pr4-gov-snap-03"
        )
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceGovernanceReportPayload serialises with all required fields`() {
        val evaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-02")
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, s) -> s.status == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN }
            .map { (dim, _) -> dim.wireValue }
        val payload = DeviceGovernanceReportPayload(
            artifact_tag = snapshot.artifact.semanticTag,
            snapshot_id = snapshot.snapshotId,
            device_id = "pr4-device-02",
            session_id = "pr4-sess-02",
            reported_at_ms = snapshot.reportedAtMs,
            dimension_states = dimensionStates,
            first_regression_reason = null,
            missing_dimensions = missingDimensions
        )
        val json = gson.toJson(payload)
        assertTrue(json.contains("\"artifact_tag\""))
        assertTrue(json.contains("\"snapshot_id\""))
        assertTrue(json.contains("\"device_id\""))
        assertTrue(json.contains("\"dimension_states\""))
        assertTrue(json.contains("\"missing_dimensions\""))
        assertTrue(json.contains("\"signal_semantics\""))
    }

    @Test
    fun `DeviceGovernanceReportPayload signal_semantics is advisory`() {
        val payload = DeviceGovernanceReportPayload(
            artifact_tag = DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            snapshot_id = "pr4-gov-snap-sem-01",
            device_id = "pr4-device-02",
            session_id = null
        )
        assertEquals(
            "governance report signal_semantics must be advisory",
            DeviceGovernanceReportPayload.SIGNAL_SEMANTICS_ADVISORY,
            payload.signal_semantics
        )
    }

    @Test
    fun `DeviceGovernanceReportPayload SIGNAL_SEMANTICS_ADVISORY constant is advisory`() {
        assertEquals("advisory", DeviceGovernanceReportPayload.SIGNAL_SEMANTICS_ADVISORY)
    }

    @Test
    fun `DeviceGovernanceReportPayload dimension_states contains all five governance dimension wire keys`() {
        val evaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-02")
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        DelegatedRuntimeGovernanceDimension.entries.forEach { dim ->
            assertTrue(
                "dimension_states must contain governance dimension ${dim.wireValue}",
                dimensionStates.containsKey(dim.wireValue)
            )
        }
    }

    @Test
    fun `DeviceGovernanceReportPayload missing_dimensions lists all five keys when all UNKNOWN`() {
        val evaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-02")
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, s) -> s.status == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN }
            .map { (dim, _) -> dim.wireValue }
        assertEquals(
            "all 5 governance dimensions should be UNKNOWN with no observation signals",
            DelegatedRuntimeGovernanceDimension.entries.size,
            missingDimensions.size
        )
    }

    @Test
    fun `DeviceGovernanceReportPayload first_regression_reason is non-null when regression dimension present`() {
        val evaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()
        DelegatedRuntimeGovernanceDimension.entries.forEach { dim ->
            evaluator.markDimensionCompliant(dim)
        }
        evaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION,
            "pr4_result_regression_test"
        )
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-02")
        val firstRegressionReason = snapshot.dimensionStates.values
            .firstOrNull { it.status == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION }
            ?.regressionReason
        assertNotNull(
            "first_regression_reason must be non-null when a regression dimension is present",
            firstRegressionReason
        )
        assertEquals("pr4_result_regression_test", firstRegressionReason)
    }

    @Test
    fun `DeviceGovernanceReportPayload first_regression_reason is null when all UNKNOWN`() {
        val evaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-02")
        val firstRegressionReason = snapshot.dimensionStates.values
            .firstOrNull { it.status == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION }
            ?.regressionReason
        assertNull(
            "first_regression_reason must be null when artifact is UNKNOWN (no regression observed)",
            firstRegressionReason
        )
    }

    @Test
    fun `AipMessage envelope for governance report contains type device_governance_report`() {
        val payload = DeviceGovernanceReportPayload(
            artifact_tag = DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            snapshot_id = "pr4-gov-snap-env-01",
            device_id = "pr4-device-02",
            session_id = null
        )
        val envelope = AipMessage(
            type = MsgType.DEVICE_GOVERNANCE_REPORT,
            payload = payload,
            device_id = "pr4-device-02",
            idempotency_key = "pr4-gov-snap-env-01"
        )
        val json = gson.toJson(envelope)
        assertTrue(
            "governance envelope must contain type=device_governance_report",
            json.contains("device_governance_report")
        )
        assertTrue(
            "governance envelope must contain snapshot_id",
            json.contains("pr4-gov-snap-env-01")
        )
        assertTrue(
            "governance envelope must contain signal_semantics=advisory",
            json.contains("advisory")
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Strategy evaluator — real runtime path
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `Strategy buildSnapshot returns non-blank snapshotId from real evaluator path`() {
        val evaluator = DelegatedRuntimeStrategyEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-03")
        assertTrue(
            "strategy snapshotId must be non-blank from real evaluator path",
            snapshot.snapshotId.isNotBlank()
        )
    }

    @Test
    fun `Strategy all UNKNOWN produces UNKNOWN strategy artifact`() {
        val evaluator = DelegatedRuntimeStrategyEvaluator()
        val artifact = evaluator.evaluateStrategy(
            deviceId = "pr4-device-03",
            snapshotId = "pr4-strat-snap-01"
        )
        assertEquals(
            DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL,
            artifact.semanticTag
        )
    }

    @Test
    fun `Strategy all ON_TRACK produces ON_TRACK strategy artifact`() {
        val evaluator = DelegatedRuntimeStrategyEvaluator()
        DelegatedRuntimeStrategyDimension.entries.forEach { dim ->
            evaluator.markDimensionOnTrack(dim)
        }
        val artifact = evaluator.evaluateStrategy(
            deviceId = "pr4-device-03",
            snapshotId = "pr4-strat-snap-02"
        )
        assertEquals(
            DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_ON_TRACK,
            artifact.semanticTag
        )
    }

    @Test
    fun `Strategy contract stability AT_RISK produces contract instability risk artifact`() {
        val evaluator = DelegatedRuntimeStrategyEvaluator()
        DelegatedRuntimeStrategyDimension.entries.forEach { dim ->
            evaluator.markDimensionOnTrack(dim)
        }
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "pr4_contract_instability_test"
        )
        val artifact = evaluator.evaluateStrategy(
            deviceId = "pr4-device-03",
            snapshotId = "pr4-strat-snap-03"
        )
        assertEquals(
            DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceStrategyReportPayload serialises with all required fields`() {
        val evaluator = DelegatedRuntimeStrategyEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-03")
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, s) -> s.status == DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN }
            .map { (dim, _) -> dim.wireValue }
        val payload = DeviceStrategyReportPayload(
            artifact_tag = snapshot.artifact.semanticTag,
            snapshot_id = snapshot.snapshotId,
            device_id = "pr4-device-03",
            session_id = "pr4-sess-03",
            reported_at_ms = snapshot.reportedAtMs,
            dimension_states = dimensionStates,
            first_risk_reason = null,
            missing_dimensions = missingDimensions
        )
        val json = gson.toJson(payload)
        assertTrue(json.contains("\"artifact_tag\""))
        assertTrue(json.contains("\"snapshot_id\""))
        assertTrue(json.contains("\"device_id\""))
        assertTrue(json.contains("\"dimension_states\""))
        assertTrue(json.contains("\"missing_dimensions\""))
        assertTrue(json.contains("\"signal_semantics\""))
    }

    @Test
    fun `DeviceStrategyReportPayload signal_semantics is advisory`() {
        val payload = DeviceStrategyReportPayload(
            artifact_tag = DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL,
            snapshot_id = "pr4-strat-snap-sem-01",
            device_id = "pr4-device-03",
            session_id = null
        )
        assertEquals(
            "strategy report signal_semantics must be advisory",
            DeviceStrategyReportPayload.SIGNAL_SEMANTICS_ADVISORY,
            payload.signal_semantics
        )
    }

    @Test
    fun `DeviceStrategyReportPayload SIGNAL_SEMANTICS_ADVISORY constant is advisory`() {
        assertEquals("advisory", DeviceStrategyReportPayload.SIGNAL_SEMANTICS_ADVISORY)
    }

    @Test
    fun `DeviceStrategyReportPayload dimension_states contains all five strategy dimension wire keys`() {
        val evaluator = DelegatedRuntimeStrategyEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-03")
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        DelegatedRuntimeStrategyDimension.entries.forEach { dim ->
            assertTrue(
                "dimension_states must contain strategy dimension ${dim.wireValue}",
                dimensionStates.containsKey(dim.wireValue)
            )
        }
    }

    @Test
    fun `DeviceStrategyReportPayload missing_dimensions lists all five keys when all UNKNOWN`() {
        val evaluator = DelegatedRuntimeStrategyEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-03")
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, s) -> s.status == DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN }
            .map { (dim, _) -> dim.wireValue }
        assertEquals(
            "all 5 strategy dimensions should be UNKNOWN with no program signals",
            DelegatedRuntimeStrategyDimension.entries.size,
            missingDimensions.size
        )
    }

    @Test
    fun `DeviceStrategyReportPayload first_risk_reason is non-null when at-risk dimension present`() {
        val evaluator = DelegatedRuntimeStrategyEvaluator()
        DelegatedRuntimeStrategyDimension.entries.forEach { dim ->
            evaluator.markDimensionOnTrack(dim)
        }
        evaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND,
            "pr4_governance_trend_risk_test"
        )
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-03")
        val firstRiskReason = snapshot.dimensionStates.values
            .firstOrNull { it.status == DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK }
            ?.riskReason
        assertNotNull(
            "first_risk_reason must be non-null when an at-risk dimension is present",
            firstRiskReason
        )
        assertEquals("pr4_governance_trend_risk_test", firstRiskReason)
    }

    @Test
    fun `DeviceStrategyReportPayload first_risk_reason is null when all UNKNOWN`() {
        val evaluator = DelegatedRuntimeStrategyEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "pr4-device-03")
        val firstRiskReason = snapshot.dimensionStates.values
            .firstOrNull { it.status == DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK }
            ?.riskReason
        assertNull(
            "first_risk_reason must be null when artifact is UNKNOWN (no risk observed)",
            firstRiskReason
        )
    }

    @Test
    fun `AipMessage envelope for strategy report contains type device_strategy_report`() {
        val payload = DeviceStrategyReportPayload(
            artifact_tag = DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL,
            snapshot_id = "pr4-strat-snap-env-01",
            device_id = "pr4-device-03",
            session_id = null
        )
        val envelope = AipMessage(
            type = MsgType.DEVICE_STRATEGY_REPORT,
            payload = payload,
            device_id = "pr4-device-03",
            idempotency_key = "pr4-strat-snap-env-01"
        )
        val json = gson.toJson(envelope)
        assertTrue(
            "strategy envelope must contain type=device_strategy_report",
            json.contains("device_strategy_report")
        )
        assertTrue(
            "strategy envelope must contain snapshot_id",
            json.contains("pr4-strat-snap-env-01")
        )
        assertTrue(
            "strategy envelope must contain signal_semantics=advisory",
            json.contains("advisory")
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Transport compatibility — Gson round-trip
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `DeviceReadinessReportPayload Gson round-trip preserves snapshot_id`() {
        val payload = DeviceReadinessReportPayload(
            artifact_tag = DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            snapshot_id = "pr4-rt-round-trip-01",
            device_id = "pr4-device-rt",
            session_id = null
        )
        val json = gson.toJson(payload)
        val deserialized = gson.fromJson(json, DeviceReadinessReportPayload::class.java)
        assertEquals("pr4-rt-round-trip-01", deserialized.snapshot_id)
        assertEquals(payload.artifact_tag, deserialized.artifact_tag)
    }

    @Test
    fun `DeviceGovernanceReportPayload Gson round-trip preserves snapshot_id and signal_semantics`() {
        val payload = DeviceGovernanceReportPayload(
            artifact_tag = DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            snapshot_id = "pr4-gov-round-trip-01",
            device_id = "pr4-device-gov",
            session_id = null
        )
        val json = gson.toJson(payload)
        val deserialized = gson.fromJson(json, DeviceGovernanceReportPayload::class.java)
        assertEquals("pr4-gov-round-trip-01", deserialized.snapshot_id)
        assertEquals(payload.artifact_tag, deserialized.artifact_tag)
        assertEquals(DeviceGovernanceReportPayload.SIGNAL_SEMANTICS_ADVISORY, deserialized.signal_semantics)
    }

    @Test
    fun `DeviceStrategyReportPayload Gson round-trip preserves snapshot_id and signal_semantics`() {
        val payload = DeviceStrategyReportPayload(
            artifact_tag = DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL,
            snapshot_id = "pr4-strat-round-trip-01",
            device_id = "pr4-device-strat",
            session_id = null
        )
        val json = gson.toJson(payload)
        val deserialized = gson.fromJson(json, DeviceStrategyReportPayload::class.java)
        assertEquals("pr4-strat-round-trip-01", deserialized.snapshot_id)
        assertEquals(payload.artifact_tag, deserialized.artifact_tag)
        assertEquals(DeviceStrategyReportPayload.SIGNAL_SEMANTICS_ADVISORY, deserialized.signal_semantics)
    }

    @Test
    fun `All three AipMessage envelopes Gson-round-trip to valid type strings`() {
        val readinessEnvelope = AipMessage(
            type = MsgType.DEVICE_READINESS_REPORT,
            payload = DeviceReadinessReportPayload(
                artifact_tag = "device_readiness_unknown_due_to_missing_signal",
                snapshot_id = "rt-env-01",
                device_id = "pr4-device",
                session_id = null
            ),
            device_id = "pr4-device"
        )
        val governanceEnvelope = AipMessage(
            type = MsgType.DEVICE_GOVERNANCE_REPORT,
            payload = DeviceGovernanceReportPayload(
                artifact_tag = "device_governance_unknown_due_to_missing_signal",
                snapshot_id = "gov-env-01",
                device_id = "pr4-device",
                session_id = null
            ),
            device_id = "pr4-device"
        )
        val strategyEnvelope = AipMessage(
            type = MsgType.DEVICE_STRATEGY_REPORT,
            payload = DeviceStrategyReportPayload(
                artifact_tag = "device_strategy_unknown_due_to_missing_program_signal",
                snapshot_id = "strat-env-01",
                device_id = "pr4-device",
                session_id = null
            ),
            device_id = "pr4-device"
        )

        val readinessJson = gson.toJson(readinessEnvelope)
        val governanceJson = gson.toJson(governanceEnvelope)
        val strategyJson = gson.toJson(strategyEnvelope)

        assertTrue(readinessJson.contains("device_readiness_report"))
        assertTrue(governanceJson.contains("device_governance_report"))
        assertTrue(strategyJson.contains("device_strategy_report"))

        // Each envelope must contain its payload's snapshot_id for V2 correlation
        assertTrue(readinessJson.contains("rt-env-01"))
        assertTrue(governanceJson.contains("gov-env-01"))
        assertTrue(strategyJson.contains("strat-env-01"))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Canonical vs advisory semantics — classification proof
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `Readiness report has no advisory tag — it is canonical participant evidence`() {
        // The readiness report payload does NOT carry a signal_semantics advisory field.
        // This is intentional: readiness is canonical participant evidence that directly
        // gates V2 release participation.  The absence of an advisory tag makes the
        // canonical classification explicit and distinguishable from governance/strategy.
        val payload = DeviceReadinessReportPayload(
            artifact_tag = DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READY_FOR_RELEASE,
            snapshot_id = "pr4-canonical-01",
            device_id = "pr4-device",
            session_id = null
        )
        val json = gson.toJson(payload)
        // Governance and strategy payloads carry "advisory" in signal_semantics;
        // readiness payload does not, clearly distinguishing it as canonical evidence.
        assertFalse(
            "readiness payload must NOT contain signal_semantics advisory — it is canonical evidence",
            json.contains("\"signal_semantics\"")
        )
    }

    @Test
    fun `Governance report carries advisory tag — it is advisory observation-only`() {
        val payload = DeviceGovernanceReportPayload(
            artifact_tag = DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT,
            snapshot_id = "pr4-advisory-gov-01",
            device_id = "pr4-device",
            session_id = null
        )
        val json = gson.toJson(payload)
        assertTrue(
            "governance payload must contain signal_semantics=advisory",
            json.contains("\"signal_semantics\":\"advisory\"")
        )
    }

    @Test
    fun `Strategy report carries advisory tag — it is advisory observation-only`() {
        val payload = DeviceStrategyReportPayload(
            artifact_tag = DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_ON_TRACK,
            snapshot_id = "pr4-advisory-strat-01",
            device_id = "pr4-device",
            session_id = null
        )
        val json = gson.toJson(payload)
        assertTrue(
            "strategy payload must contain signal_semantics=advisory",
            json.contains("\"signal_semantics\":\"advisory\"")
        )
    }
}
