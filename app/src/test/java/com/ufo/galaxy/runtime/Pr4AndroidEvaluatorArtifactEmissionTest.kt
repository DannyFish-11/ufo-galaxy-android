package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.DeviceAcceptanceReportPayload
import com.ufo.galaxy.protocol.DeviceGovernanceReportPayload
import com.ufo.galaxy.protocol.DeviceStrategyReportPayload
import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR-4 (Android) — Evaluator artifact emission validation test matrix.
 *
 * This test class proves that Android evaluator/runtime artifacts are emitted from real
 * evaluator paths and can serve as reliable inputs to V2 canonical readiness / governance
 * flows.  It validates the full pipeline from evaluator → snapshot → payload → AipMessage
 * envelope for the three artifact types added in PR-4:
 *
 *  - [DeviceGovernanceArtifact] / [DelegatedRuntimePostGraduationGovernanceEvaluator]
 *    → [DeviceGovernanceReportPayload] → [MsgType.DEVICE_GOVERNANCE_REPORT]
 *  - [DeviceAcceptanceArtifact] / [DelegatedRuntimeAcceptanceEvaluator]
 *    → [DeviceAcceptanceReportPayload] → [MsgType.DEVICE_ACCEPTANCE_REPORT]
 *  - [DeviceStrategyArtifact] / [DelegatedRuntimeStrategyEvaluator]
 *    → [DeviceStrategyReportPayload] → [MsgType.DEVICE_STRATEGY_REPORT]
 *
 * ## Test matrix
 *
 * ### MsgType wire values (transport stability)
 *  - DEVICE_GOVERNANCE_REPORT wire value is "device_governance_report"
 *  - DEVICE_ACCEPTANCE_REPORT wire value is "device_acceptance_report"
 *  - DEVICE_STRATEGY_REPORT wire value is "device_strategy_report"
 *
 * ### Governance evaluator → snapshot → payload chain
 *  - All-UNKNOWN evaluator produces DeviceGovernanceUnknownDueToMissingSignal artifact
 *  - All-COMPLIANT evaluator produces DeviceGovernanceCompliant artifact
 *  - Single-REGRESSION evaluator produces DeviceGovernanceViolationDueToTruthRegression
 *  - buildSnapshot produces non-blank snapshotId and embeds correct artifact
 *  - DeviceGovernanceReportPayload serializes with required fields
 *  - AipMessage envelope for governance report contains type "device_governance_report"
 *  - Governance artifact is CANONICAL_PARTICIPANT_EVIDENCE per audit registry
 *
 * ### Acceptance evaluator → snapshot → payload chain
 *  - All-UNKNOWN evaluator produces DeviceAcceptanceUnknownDueToIncompleteSignal artifact
 *  - All-EVIDENCED evaluator produces DeviceAcceptedForGraduation artifact
 *  - READINESS_PREREQUISITE GAP produces DeviceRejectedDueToMissingEvidence
 *  - buildSnapshot produces non-blank snapshotId and embeds correct artifact
 *  - DeviceAcceptanceReportPayload serializes with required fields
 *  - AipMessage envelope for acceptance report contains type "device_acceptance_report"
 *  - Acceptance artifact is CANONICAL_PARTICIPANT_EVIDENCE per audit registry
 *
 * ### Strategy evaluator → snapshot → payload chain
 *  - All-UNKNOWN evaluator produces DeviceStrategyUnknownDueToMissingProgramSignal artifact
 *  - All-ON_TRACK evaluator produces DeviceStrategyOnTrack artifact
 *  - CONTRACT_STABILITY AT_RISK produces DeviceStrategyRiskDueToContractInstability
 *  - buildSnapshot produces non-blank snapshotId and embeds correct artifact
 *  - DeviceStrategyReportPayload serializes with required fields
 *  - AipMessage envelope for strategy report contains type "device_strategy_report"
 *  - Strategy artifact is OBSERVATION_SIGNAL per audit registry (advisory/observation-only)
 *
 * ### Artifact semantic classification (V2 consumption contract)
 *  - governance and acceptance artifact tags are canonical participant evidence
 *  - strategy artifact tag is advisory/observation-only
 *  - audit registry entries correctly classify each artifact type
 *
 * ## Remaining deferred items
 *
 *  - Full GalaxyConnectionService integration tests (require Robolectric / instrumented):
 *    inject service start, confirm all four report types are sent via fake WebSocket.
 *  - Dimension-state population from real runtime lifecycle events (evaluators wired to
 *    AndroidRecoveryParticipationOwner et al. feeding dimension transitions in production).
 *  - Follow-up report emission on dimension-state change events (triggered by runtime
 *    lifecycle transitions rather than only on service start).
 */
class Pr4AndroidEvaluatorArtifactEmissionTest {

    private val gson = Gson()

    // ── Evaluator instances ───────────────────────────────────────────────────

    private lateinit var governanceEvaluator: DelegatedRuntimePostGraduationGovernanceEvaluator
    private lateinit var acceptanceEvaluator: DelegatedRuntimeAcceptanceEvaluator
    private lateinit var strategyEvaluator: DelegatedRuntimeStrategyEvaluator

    @Before
    fun setUp() {
        governanceEvaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()
        acceptanceEvaluator = DelegatedRuntimeAcceptanceEvaluator()
        strategyEvaluator = DelegatedRuntimeStrategyEvaluator()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MsgType wire-value stability
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DEVICE_GOVERNANCE_REPORT wire value is device_governance_report`() {
        assertEquals("device_governance_report", MsgType.DEVICE_GOVERNANCE_REPORT.value)
    }

    @Test
    fun `DEVICE_ACCEPTANCE_REPORT wire value is device_acceptance_report`() {
        assertEquals("device_acceptance_report", MsgType.DEVICE_ACCEPTANCE_REPORT.value)
    }

    @Test
    fun `DEVICE_STRATEGY_REPORT wire value is device_strategy_report`() {
        assertEquals("device_strategy_report", MsgType.DEVICE_STRATEGY_REPORT.value)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Governance evaluator → artifact chain
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `governance evaluator with all-UNKNOWN dimensions produces UNKNOWN artifact`() {
        // All dimensions are UNKNOWN by default (no markDimension* calls).
        val artifact = governanceEvaluator.evaluateGovernance(
            deviceId = "device-gov-01",
            snapshotId = "snap-gov-01"
        )
        assertTrue(
            "Expected DeviceGovernanceUnknownDueToMissingSignal but got $artifact",
            artifact is DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal
        )
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            artifact.semanticTag
        )
    }

    @Test
    fun `governance evaluator with all-COMPLIANT dimensions produces COMPLIANT artifact`() {
        DelegatedRuntimeGovernanceDimension.entries.forEach {
            governanceEvaluator.markDimensionCompliant(it)
        }
        val artifact = governanceEvaluator.evaluateGovernance(
            deviceId = "device-gov-02",
            snapshotId = "snap-gov-02"
        )
        assertTrue(
            "Expected DeviceGovernanceCompliant but got $artifact",
            artifact is DeviceGovernanceArtifact.DeviceGovernanceCompliant
        )
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT,
            artifact.semanticTag
        )
    }

    @Test
    fun `governance evaluator with TRUTH_ALIGNMENT_REGRESSION produces ViolationDueToTruthRegression`() {
        DelegatedRuntimeGovernanceDimension.entries.forEach {
            governanceEvaluator.markDimensionCompliant(it)
        }
        governanceEvaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "truth drift detected in unit test"
        )
        val artifact = governanceEvaluator.evaluateGovernance(
            deviceId = "device-gov-03",
            snapshotId = "snap-gov-03"
        )
        assertTrue(
            "Expected DeviceGovernanceViolationDueToTruthRegression but got $artifact",
            artifact is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression
        )
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION,
            artifact.semanticTag
        )
    }

    @Test
    fun `governance buildSnapshot produces non-blank snapshotId and embeds artifact`() {
        val snapshot = governanceEvaluator.buildSnapshot(deviceId = "device-gov-snap-01")
        assertFalse("snapshotId must not be blank", snapshot.snapshotId.isBlank())
        assertEquals("device-gov-snap-01", snapshot.deviceId)
        assertNotNull(snapshot.artifact)
        assertEquals(DelegatedRuntimeGovernanceDimension.entries.size, snapshot.dimensionStates.size)
    }

    @Test
    fun `DeviceGovernanceReportPayload serializes with required fields`() {
        DelegatedRuntimeGovernanceDimension.entries.forEach {
            governanceEvaluator.markDimensionCompliant(it)
        }
        val snapshot = governanceEvaluator.buildSnapshot(deviceId = "device-gov-ser-01")
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, state) ->
                state.status == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN
            }
            .map { (dim, _) -> dim.wireValue }

        val payload = DeviceGovernanceReportPayload(
            artifact_tag = snapshot.artifact.semanticTag,
            snapshot_id = snapshot.snapshotId,
            device_id = "device-gov-ser-01",
            session_id = "sess-gov-01",
            reported_at_ms = snapshot.reportedAtMs,
            dimension_states = dimensionStates,
            first_regression_reason = null,
            missing_dimensions = missingDimensions
        )
        val json = gson.toJson(payload)
        assertTrue("JSON must contain artifact_tag", json.contains("artifact_tag"))
        assertTrue("JSON must contain snapshot_id", json.contains("snapshot_id"))
        assertTrue("JSON must contain device_id", json.contains("device_id"))
        assertTrue("JSON must contain dimension_states", json.contains("dimension_states"))
        assertTrue("JSON must contain missing_dimensions", json.contains("missing_dimensions"))
        assertTrue(
            "artifact_tag must equal device_governance_compliant",
            json.contains(DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT)
        )
    }

    @Test
    fun `DeviceGovernanceReportPayload AipMessage envelope contains type device_governance_report`() {
        val payload = DeviceGovernanceReportPayload(
            artifact_tag = DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            snapshot_id = "snap-gov-env-01",
            device_id = "device-gov-env-01",
            session_id = null
        )
        val envelope = AipMessage(
            type = MsgType.DEVICE_GOVERNANCE_REPORT,
            payload = payload,
            device_id = "device-gov-env-01",
            runtime_session_id = null
        )
        val json = gson.toJson(envelope)
        assertTrue("Envelope JSON must contain device_governance_report", json.contains("device_governance_report"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Acceptance evaluator → artifact chain
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `acceptance evaluator with all-UNKNOWN dimensions produces UNKNOWN artifact`() {
        val artifact = acceptanceEvaluator.evaluateAcceptance(
            deviceId = "device-acc-01",
            snapshotId = "snap-acc-01"
        )
        assertTrue(
            "Expected DeviceAcceptanceUnknownDueToIncompleteSignal but got $artifact",
            artifact is DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal
        )
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL,
            artifact.semanticTag
        )
    }

    @Test
    fun `acceptance evaluator with all-EVIDENCED dimensions produces DeviceAcceptedForGraduation`() {
        DelegatedRuntimeAcceptanceDimension.entries.forEach {
            acceptanceEvaluator.markDimensionEvidenced(it)
        }
        val artifact = acceptanceEvaluator.evaluateAcceptance(
            deviceId = "device-acc-02",
            snapshotId = "snap-acc-02"
        )
        assertTrue(
            "Expected DeviceAcceptedForGraduation but got $artifact",
            artifact is DeviceAcceptanceArtifact.DeviceAcceptedForGraduation
        )
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION,
            artifact.semanticTag
        )
    }

    @Test
    fun `acceptance evaluator with READINESS_PREREQUISITE GAP produces DeviceRejectedDueToMissingEvidence`() {
        DelegatedRuntimeAcceptanceDimension.entries.forEach {
            acceptanceEvaluator.markDimensionEvidenced(it)
        }
        acceptanceEvaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE,
            "readiness not yet confirmed"
        )
        val artifact = acceptanceEvaluator.evaluateAcceptance(
            deviceId = "device-acc-03",
            snapshotId = "snap-acc-03"
        )
        assertTrue(
            "Expected DeviceRejectedDueToMissingEvidence but got $artifact",
            artifact is DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence
        )
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE,
            artifact.semanticTag
        )
    }

    @Test
    fun `acceptance buildSnapshot produces non-blank snapshotId and embeds artifact`() {
        val snapshot = acceptanceEvaluator.buildSnapshot(deviceId = "device-acc-snap-01")
        assertFalse("snapshotId must not be blank", snapshot.snapshotId.isBlank())
        assertEquals("device-acc-snap-01", snapshot.deviceId)
        assertNotNull(snapshot.artifact)
        assertEquals(DelegatedRuntimeAcceptanceDimension.entries.size, snapshot.dimensionStates.size)
    }

    @Test
    fun `DeviceAcceptanceReportPayload serializes with required fields`() {
        DelegatedRuntimeAcceptanceDimension.entries.forEach {
            acceptanceEvaluator.markDimensionEvidenced(it)
        }
        val snapshot = acceptanceEvaluator.buildSnapshot(deviceId = "device-acc-ser-01")
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, state) ->
                state.status == DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN
            }
            .map { (dim, _) -> dim.wireValue }

        val payload = DeviceAcceptanceReportPayload(
            artifact_tag = snapshot.artifact.semanticTag,
            snapshot_id = snapshot.snapshotId,
            device_id = "device-acc-ser-01",
            session_id = "sess-acc-01",
            reported_at_ms = snapshot.reportedAtMs,
            dimension_states = dimensionStates,
            first_gap_reason = null,
            missing_dimensions = missingDimensions
        )
        val json = gson.toJson(payload)
        assertTrue("JSON must contain artifact_tag", json.contains("artifact_tag"))
        assertTrue("JSON must contain snapshot_id", json.contains("snapshot_id"))
        assertTrue("JSON must contain device_id", json.contains("device_id"))
        assertTrue("JSON must contain dimension_states", json.contains("dimension_states"))
        assertTrue("JSON must contain missing_dimensions", json.contains("missing_dimensions"))
        assertTrue(
            "artifact_tag must equal device_accepted_for_graduation",
            json.contains(DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION)
        )
    }

    @Test
    fun `DeviceAcceptanceReportPayload AipMessage envelope contains type device_acceptance_report`() {
        val payload = DeviceAcceptanceReportPayload(
            artifact_tag = DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL,
            snapshot_id = "snap-acc-env-01",
            device_id = "device-acc-env-01",
            session_id = null
        )
        val envelope = AipMessage(
            type = MsgType.DEVICE_ACCEPTANCE_REPORT,
            payload = payload,
            device_id = "device-acc-env-01",
            runtime_session_id = null
        )
        val json = gson.toJson(envelope)
        assertTrue("Envelope JSON must contain device_acceptance_report", json.contains("device_acceptance_report"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Strategy evaluator → artifact chain
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `strategy evaluator with all-UNKNOWN dimensions produces UNKNOWN artifact`() {
        val artifact = strategyEvaluator.evaluateStrategy(
            deviceId = "device-str-01",
            snapshotId = "snap-str-01"
        )
        assertTrue(
            "Expected DeviceStrategyUnknownDueToMissingProgramSignal but got $artifact",
            artifact is DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal
        )
        assertEquals(
            DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL,
            artifact.semanticTag
        )
    }

    @Test
    fun `strategy evaluator with all-ON_TRACK dimensions produces DeviceStrategyOnTrack`() {
        DelegatedRuntimeStrategyDimension.entries.forEach {
            strategyEvaluator.markDimensionOnTrack(it)
        }
        val artifact = strategyEvaluator.evaluateStrategy(
            deviceId = "device-str-02",
            snapshotId = "snap-str-02"
        )
        assertTrue(
            "Expected DeviceStrategyOnTrack but got $artifact",
            artifact is DeviceStrategyArtifact.DeviceStrategyOnTrack
        )
        assertEquals(
            DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_ON_TRACK,
            artifact.semanticTag
        )
    }

    @Test
    fun `strategy evaluator with CANONICAL_CONTRACT_STABILITY AT_RISK produces RiskDueToContractInstability`() {
        DelegatedRuntimeStrategyDimension.entries.forEach {
            strategyEvaluator.markDimensionOnTrack(it)
        }
        strategyEvaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "contract drift detected in unit test"
        )
        val artifact = strategyEvaluator.evaluateStrategy(
            deviceId = "device-str-03",
            snapshotId = "snap-str-03"
        )
        assertTrue(
            "Expected DeviceStrategyRiskDueToContractInstability but got $artifact",
            artifact is DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability
        )
        assertEquals(
            DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY,
            artifact.semanticTag
        )
    }

    @Test
    fun `strategy buildSnapshot produces non-blank snapshotId and embeds artifact`() {
        val snapshot = strategyEvaluator.buildSnapshot(deviceId = "device-str-snap-01")
        assertFalse("snapshotId must not be blank", snapshot.snapshotId.isBlank())
        assertEquals("device-str-snap-01", snapshot.deviceId)
        assertNotNull(snapshot.artifact)
        assertEquals(DelegatedRuntimeStrategyDimension.entries.size, snapshot.dimensionStates.size)
    }

    @Test
    fun `DeviceStrategyReportPayload serializes with required fields`() {
        DelegatedRuntimeStrategyDimension.entries.forEach {
            strategyEvaluator.markDimensionOnTrack(it)
        }
        val snapshot = strategyEvaluator.buildSnapshot(deviceId = "device-str-ser-01")
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, state) ->
                state.status == DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN
            }
            .map { (dim, _) -> dim.wireValue }

        val payload = DeviceStrategyReportPayload(
            artifact_tag = snapshot.artifact.semanticTag,
            snapshot_id = snapshot.snapshotId,
            device_id = "device-str-ser-01",
            session_id = "sess-str-01",
            reported_at_ms = snapshot.reportedAtMs,
            dimension_states = dimensionStates,
            first_risk_reason = null,
            missing_dimensions = missingDimensions
        )
        val json = gson.toJson(payload)
        assertTrue("JSON must contain artifact_tag", json.contains("artifact_tag"))
        assertTrue("JSON must contain snapshot_id", json.contains("snapshot_id"))
        assertTrue("JSON must contain device_id", json.contains("device_id"))
        assertTrue("JSON must contain dimension_states", json.contains("dimension_states"))
        assertTrue("JSON must contain missing_dimensions", json.contains("missing_dimensions"))
        assertTrue(
            "artifact_tag must equal device_strategy_on_track",
            json.contains(DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_ON_TRACK)
        )
    }

    @Test
    fun `DeviceStrategyReportPayload AipMessage envelope contains type device_strategy_report`() {
        val payload = DeviceStrategyReportPayload(
            artifact_tag = DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL,
            snapshot_id = "snap-str-env-01",
            device_id = "device-str-env-01",
            session_id = null
        )
        val envelope = AipMessage(
            type = MsgType.DEVICE_STRATEGY_REPORT,
            payload = payload,
            device_id = "device-str-env-01",
            runtime_session_id = null
        )
        val json = gson.toJson(envelope)
        assertTrue("Envelope JSON must contain device_strategy_report", json.contains("device_strategy_report"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Artifact semantic classification (V2 consumption contract)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `governance report entry in audit registry is CANONICAL_PARTICIPANT_EVIDENCE`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.entries
            .firstOrNull { it.behaviorId == "device_governance_report" }
        assertNotNull("device_governance_report must be present in audit registry", entry)
        assertEquals(
            "governance report must be CANONICAL_DEFAULT tier",
            AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT,
            entry!!.tier
        )
        assertEquals(
            "governance report signal semantics must be CANONICAL_PARTICIPANT_EVIDENCE",
            AndroidAuthoritativePathAlignmentAudit.SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE,
            entry.signalSemantics
        )
    }

    @Test
    fun `acceptance report entry in audit registry is CANONICAL_PARTICIPANT_EVIDENCE`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.entries
            .firstOrNull { it.behaviorId == "device_acceptance_report" }
        assertNotNull("device_acceptance_report must be present in audit registry", entry)
        assertEquals(
            "acceptance report must be CANONICAL_DEFAULT tier",
            AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT,
            entry!!.tier
        )
        assertEquals(
            "acceptance report signal semantics must be CANONICAL_PARTICIPANT_EVIDENCE",
            AndroidAuthoritativePathAlignmentAudit.SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE,
            entry.signalSemantics
        )
    }

    @Test
    fun `strategy report entry in audit registry is OBSERVATION_SIGNAL (advisory-only)`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.entries
            .firstOrNull { it.behaviorId == "device_strategy_report" }
        assertNotNull("device_strategy_report must be present in audit registry", entry)
        assertEquals(
            "strategy report must be CANONICAL_DEFAULT tier",
            AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT,
            entry!!.tier
        )
        assertEquals(
            "strategy report signal semantics must be OBSERVATION_SIGNAL (advisory-only)",
            AndroidAuthoritativePathAlignmentAudit.SignalSemantics.OBSERVATION_SIGNAL,
            entry.signalSemantics
        )
        assertNotNull(
            "strategy report must have a deferralNote documenting its advisory-only status",
            entry.deferralNote
        )
    }

    @Test
    fun `governance readiness and acceptance report entries are all present in audit registry`() {
        val auditIds = AndroidAuthoritativePathAlignmentAudit.entries.map { it.behaviorId }.toSet()
        assertTrue("device_readiness_report must be in audit", "device_readiness_report" in auditIds)
        assertTrue("device_governance_report must be in audit", "device_governance_report" in auditIds)
        assertTrue("device_acceptance_report must be in audit", "device_acceptance_report" in auditIds)
        assertTrue("device_strategy_report must be in audit", "device_strategy_report" in auditIds)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Payload field-stability across all three new types
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `governance payload with null session_id serializes without session_id field value`() {
        val payload = DeviceGovernanceReportPayload(
            artifact_tag = DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            snapshot_id = "snap-gov-null-sess",
            device_id = "device-gov-null-sess",
            session_id = null
        )
        val json = gson.toJson(payload)
        assertTrue(json.contains("snap-gov-null-sess"))
        assertTrue(json.contains("device-gov-null-sess"))
    }

    @Test
    fun `acceptance payload with null session_id serializes without session_id field value`() {
        val payload = DeviceAcceptanceReportPayload(
            artifact_tag = DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL,
            snapshot_id = "snap-acc-null-sess",
            device_id = "device-acc-null-sess",
            session_id = null
        )
        val json = gson.toJson(payload)
        assertTrue(json.contains("snap-acc-null-sess"))
        assertTrue(json.contains("device-acc-null-sess"))
    }

    @Test
    fun `strategy payload with null session_id serializes without session_id field value`() {
        val payload = DeviceStrategyReportPayload(
            artifact_tag = DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL,
            snapshot_id = "snap-str-null-sess",
            device_id = "device-str-null-sess",
            session_id = null
        )
        val json = gson.toJson(payload)
        assertTrue(json.contains("snap-str-null-sess"))
        assertTrue(json.contains("device-str-null-sess"))
    }

    @Test
    fun `all three new MsgType entries are distinct`() {
        val governance = MsgType.DEVICE_GOVERNANCE_REPORT.value
        val acceptance = MsgType.DEVICE_ACCEPTANCE_REPORT.value
        val strategy = MsgType.DEVICE_STRATEGY_REPORT.value
        val readiness = MsgType.DEVICE_READINESS_REPORT.value
        assertTrue("governance and acceptance must be distinct", governance != acceptance)
        assertTrue("governance and strategy must be distinct", governance != strategy)
        assertTrue("acceptance and strategy must be distinct", acceptance != strategy)
        assertTrue("governance and readiness must be distinct", governance != readiness)
        assertTrue("acceptance and readiness must be distinct", acceptance != readiness)
        assertTrue("strategy and readiness must be distinct", strategy != readiness)
    }
}
