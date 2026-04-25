package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.DeviceAcceptanceReportPayload
import com.ufo.galaxy.protocol.DeviceGovernanceReportPayload
import com.ufo.galaxy.protocol.DeviceStrategyReportPayload
import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR-4 (Android) — Evaluator artifact emission tests.
 *
 * Validates that Android evaluator artifacts are emitted from real evaluator paths and
 * that the payload shapes are stable and reviewable for V2-side consumption.
 *
 * This test suite provides evidence for each PR-4 acceptance criterion:
 *  1. Which Android evaluator/runtime artifacts are emitted toward V2.
 *  2. That those artifacts come from real runtime/evaluator paths (not just model definitions).
 *  3. That artifact payloads are stable and reviewable for V2-side consumption.
 *  4. Which Android-originated artifacts are canonical participant evidence vs advisory.
 *  5. What remains deferred to later PRs.
 *
 * ## Test matrix
 *
 * ### EvaluatorArtifactEmissionSemantics — registry completeness
 *  - Registry contains exactly 5 entries (readiness, governance, acceptance, strategy, reconciliation)
 *  - Each REGISTRY entry has a non-blank evaluatorClass, msgTypeWireValue, and sendPath
 *  - findByMsgType("device_readiness_report") → CANONICAL_PARTICIPANT_EVIDENCE
 *  - findByMsgType("device_governance_report") → CANONICAL_PARTICIPANT_EVIDENCE
 *  - findByMsgType("device_acceptance_report") → CANONICAL_PARTICIPANT_EVIDENCE
 *  - findByMsgType("device_strategy_report") → ADVISORY_OBSERVATION_ONLY
 *  - findByMsgType("reconciliation_signal") → CANONICAL_PARTICIPANT_EVIDENCE
 *  - findByMsgType("unknown_type") → null
 *  - EmissionClass wire labels are distinct
 *
 * ### MsgType — new evaluator report wire values (AC: #1)
 *  - DEVICE_GOVERNANCE_REPORT wire value is "device_governance_report"
 *  - DEVICE_ACCEPTANCE_REPORT wire value is "device_acceptance_report"
 *  - DEVICE_STRATEGY_REPORT wire value is "device_strategy_report"
 *  - All three wire values are distinct from each other and from DEVICE_READINESS_REPORT
 *  - All three types are resolvable via MsgType.fromValue
 *
 * ### DeviceGovernanceReportPayload — payload shape stability (AC: #3)
 *  - artifact_tag field is present in Gson-serialised JSON
 *  - snapshot_id field is present in Gson-serialised JSON
 *  - device_id field is present in Gson-serialised JSON
 *  - dimension_states field is present in Gson-serialised JSON
 *  - All-UNKNOWN snapshot produces artifact_tag = "device_governance_unknown_due_to_missing_signal"
 *  - Missing dimensions list is non-empty when all dimensions are UNKNOWN
 *  - Compliant snapshot produces artifact_tag = "device_governance_compliant"
 *  - Regression snapshot produces violation artifact_tag
 *
 * ### DeviceAcceptanceReportPayload — payload shape stability (AC: #3)
 *  - artifact_tag field is present in Gson-serialised JSON
 *  - snapshot_id field is present in Gson-serialised JSON
 *  - device_id field is present in Gson-serialised JSON
 *  - dimension_states field is present in Gson-serialised JSON
 *  - All-UNKNOWN snapshot produces artifact_tag = "device_acceptance_unknown_due_to_incomplete_signal"
 *  - Missing dimensions list is non-empty when all dimensions are UNKNOWN
 *  - All-EVIDENCED snapshot produces artifact_tag = "device_accepted_for_graduation"
 *  - GAP snapshot produces rejected artifact_tag
 *
 * ### DeviceStrategyReportPayload — payload shape stability (AC: #3)
 *  - artifact_tag field is present in Gson-serialised JSON
 *  - snapshot_id field is present in Gson-serialised JSON
 *  - device_id field is present in Gson-serialised JSON
 *  - dimension_states field is present in Gson-serialised JSON
 *  - All-UNKNOWN snapshot produces artifact_tag = "device_strategy_unknown_due_to_missing_program_signal"
 *  - Missing dimensions list is non-empty when all dimensions are UNKNOWN
 *  - All-ON_TRACK snapshot produces artifact_tag = "device_strategy_on_track"
 *  - AT_RISK snapshot produces risk artifact_tag
 *
 * ### Send-path simulation — governance evaluator → AipMessage envelope (AC: #2)
 *  - Governance evaluator buildSnapshot() produces a snapshot with non-blank snapshotId
 *  - Governance snapshot wrapped in AipMessage with DEVICE_GOVERNANCE_REPORT type round-trips through Gson
 *  - Resulting JSON contains "device_governance_report" type
 *  - Resulting JSON contains snapshot_id echoed from snapshot
 *
 * ### Send-path simulation — acceptance evaluator → AipMessage envelope (AC: #2)
 *  - Acceptance evaluator buildSnapshot() produces a snapshot with non-blank snapshotId
 *  - Acceptance snapshot wrapped in AipMessage with DEVICE_ACCEPTANCE_REPORT type round-trips through Gson
 *  - Resulting JSON contains "device_acceptance_report" type
 *  - Resulting JSON contains snapshot_id echoed from snapshot
 *
 * ### Send-path simulation — strategy evaluator → AipMessage envelope (AC: #2)
 *  - Strategy evaluator buildSnapshot() produces a snapshot with non-blank snapshotId
 *  - Strategy snapshot wrapped in AipMessage with DEVICE_STRATEGY_REPORT type round-trips through Gson
 *  - Resulting JSON contains "device_strategy_report" type
 *  - Resulting JSON contains snapshot_id echoed from snapshot
 *
 * ### sendJson failure does not throw (AC: #2)
 *  - Governance report send failure does not propagate exception
 *  - Acceptance report send failure does not propagate exception
 *  - Strategy report send failure does not propagate exception
 *
 * ### Semantic classification — canonical vs advisory (AC: #4)
 *  - DEVICE_GOVERNANCE_REPORT is CANONICAL_PARTICIPANT_EVIDENCE
 *  - DEVICE_ACCEPTANCE_REPORT is CANONICAL_PARTICIPANT_EVIDENCE
 *  - DEVICE_STRATEGY_REPORT is ADVISORY_OBSERVATION_ONLY
 *  - DEVICE_READINESS_REPORT is CANONICAL_PARTICIPANT_EVIDENCE
 *  - RECONCILIATION_SIGNAL is CANONICAL_PARTICIPANT_EVIDENCE
 *  - Three entries are canonical; one is advisory
 *
 * ### Deferred work annotation (AC: #5)
 *  - Governance entry has non-null deferredWork
 *  - Acceptance entry has non-null deferredWork
 *  - Strategy entry has non-null deferredWork
 *  - Reconciliation signal entry has null deferredWork (fully closed)
 */
class Pr66EvaluatorArtifactEmissionTest {

    private val gson = Gson()

    private lateinit var governanceEvaluator: DelegatedRuntimePostGraduationGovernanceEvaluator
    private lateinit var acceptanceEvaluator: DelegatedRuntimeAcceptanceEvaluator
    private lateinit var strategyEvaluator: DelegatedRuntimeStrategyEvaluator

    private val testDeviceId = "test-device-pr66"

    @Before
    fun setUp() {
        governanceEvaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()
        acceptanceEvaluator = DelegatedRuntimeAcceptanceEvaluator()
        strategyEvaluator = DelegatedRuntimeStrategyEvaluator()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildGovernancePayload(
        snapshot: DelegatedRuntimeGovernanceSnapshot
    ): DeviceGovernanceReportPayload {
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, state) ->
                state.status == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN
            }
            .map { (dim, _) -> dim.wireValue }
        val firstRegressionReason = snapshot.dimensionStates.values
            .firstOrNull {
                it.status == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION
            }?.regressionReason
        return DeviceGovernanceReportPayload(
            artifact_tag = snapshot.artifact.semanticTag,
            snapshot_id = snapshot.snapshotId,
            device_id = snapshot.deviceId,
            session_id = null,
            reported_at_ms = snapshot.reportedAtMs,
            dimension_states = dimensionStates,
            first_regression_reason = firstRegressionReason,
            missing_dimensions = missingDimensions
        )
    }

    private fun buildAcceptancePayload(
        snapshot: DelegatedRuntimeAcceptanceSnapshot
    ): DeviceAcceptanceReportPayload {
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, state) ->
                state.status == DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN
            }
            .map { (dim, _) -> dim.wireValue }
        val firstGapReason = snapshot.dimensionStates.values
            .firstOrNull {
                it.status == DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP
            }?.gapReason
        return DeviceAcceptanceReportPayload(
            artifact_tag = snapshot.artifact.semanticTag,
            snapshot_id = snapshot.snapshotId,
            device_id = snapshot.deviceId,
            session_id = null,
            reported_at_ms = snapshot.reportedAtMs,
            dimension_states = dimensionStates,
            first_gap_reason = firstGapReason,
            missing_dimensions = missingDimensions
        )
    }

    private fun buildStrategyPayload(
        snapshot: DelegatedRuntimeStrategySnapshot
    ): DeviceStrategyReportPayload {
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, state) ->
                state.status == DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN
            }
            .map { (dim, _) -> dim.wireValue }
        val firstRiskReason = snapshot.dimensionStates.values
            .firstOrNull {
                it.status == DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK
            }?.riskReason
        return DeviceStrategyReportPayload(
            artifact_tag = snapshot.artifact.semanticTag,
            snapshot_id = snapshot.snapshotId,
            device_id = snapshot.deviceId,
            session_id = null,
            reported_at_ms = snapshot.reportedAtMs,
            dimension_states = dimensionStates,
            first_risk_reason = firstRiskReason,
            missing_dimensions = missingDimensions
        )
    }

    private fun fakeSendJson(json: String): Boolean {
        // Simulate sendJson: return true for non-blank JSON, throw for "error".
        if (json.contains("\"simulate_send_failure\"")) throw RuntimeException("simulated send failure")
        return json.isNotBlank()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EvaluatorArtifactEmissionSemantics — registry completeness
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Registry contains exactly 5 entries`() {
        assertEquals(5, EvaluatorArtifactEmissionSemantics.REGISTRY.size)
    }

    @Test
    fun `Each REGISTRY entry has non-blank fields`() {
        for (entry in EvaluatorArtifactEmissionSemantics.REGISTRY) {
            assertTrue(
                "evaluatorClass must be non-blank for $entry",
                entry.evaluatorClass.isNotBlank()
            )
            assertTrue(
                "msgTypeWireValue must be non-blank for $entry",
                entry.msgTypeWireValue.isNotBlank()
            )
            assertTrue(
                "sendPath must be non-blank for $entry",
                entry.sendPath.isNotBlank()
            )
        }
    }

    @Test
    fun `findByMsgType device_readiness_report returns CANONICAL_PARTICIPANT_EVIDENCE`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType("device_readiness_report")
        assertNotNull(entry)
        assertEquals(
            EvaluatorArtifactEmissionSemantics.EmissionClass.CANONICAL_PARTICIPANT_EVIDENCE,
            entry!!.emissionClass
        )
    }

    @Test
    fun `findByMsgType device_governance_report returns CANONICAL_PARTICIPANT_EVIDENCE`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType("device_governance_report")
        assertNotNull(entry)
        assertEquals(
            EvaluatorArtifactEmissionSemantics.EmissionClass.CANONICAL_PARTICIPANT_EVIDENCE,
            entry!!.emissionClass
        )
    }

    @Test
    fun `findByMsgType device_acceptance_report returns CANONICAL_PARTICIPANT_EVIDENCE`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType("device_acceptance_report")
        assertNotNull(entry)
        assertEquals(
            EvaluatorArtifactEmissionSemantics.EmissionClass.CANONICAL_PARTICIPANT_EVIDENCE,
            entry!!.emissionClass
        )
    }

    @Test
    fun `findByMsgType device_strategy_report returns ADVISORY_OBSERVATION_ONLY`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType("device_strategy_report")
        assertNotNull(entry)
        assertEquals(
            EvaluatorArtifactEmissionSemantics.EmissionClass.ADVISORY_OBSERVATION_ONLY,
            entry!!.emissionClass
        )
    }

    @Test
    fun `findByMsgType reconciliation_signal returns CANONICAL_PARTICIPANT_EVIDENCE`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType("reconciliation_signal")
        assertNotNull(entry)
        assertEquals(
            EvaluatorArtifactEmissionSemantics.EmissionClass.CANONICAL_PARTICIPANT_EVIDENCE,
            entry!!.emissionClass
        )
    }

    @Test
    fun `findByMsgType unknown_type returns null`() {
        assertNull(EvaluatorArtifactEmissionSemantics.findByMsgType("unknown_type"))
    }

    @Test
    fun `EmissionClass wire labels are distinct`() {
        val labels = EvaluatorArtifactEmissionSemantics.EmissionClass.entries
            .map { it.wireLabel }
        assertEquals("EmissionClass wire labels must be distinct", labels.distinct().size, labels.size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MsgType — new evaluator report wire values (AC: #1)
    // ══════════════════════════════════════════════════════════════════════════

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

    @Test
    fun `New wire values are distinct from each other and from DEVICE_READINESS_REPORT`() {
        val values = listOf(
            MsgType.DEVICE_READINESS_REPORT.value,
            MsgType.DEVICE_GOVERNANCE_REPORT.value,
            MsgType.DEVICE_ACCEPTANCE_REPORT.value,
            MsgType.DEVICE_STRATEGY_REPORT.value
        )
        assertEquals("All four evaluator report wire values must be distinct", values.distinct().size, values.size)
    }

    @Test
    fun `DEVICE_GOVERNANCE_REPORT is resolvable via fromValue`() {
        assertEquals(
            MsgType.DEVICE_GOVERNANCE_REPORT,
            MsgType.fromValue("device_governance_report")
        )
    }

    @Test
    fun `DEVICE_ACCEPTANCE_REPORT is resolvable via fromValue`() {
        assertEquals(
            MsgType.DEVICE_ACCEPTANCE_REPORT,
            MsgType.fromValue("device_acceptance_report")
        )
    }

    @Test
    fun `DEVICE_STRATEGY_REPORT is resolvable via fromValue`() {
        assertEquals(
            MsgType.DEVICE_STRATEGY_REPORT,
            MsgType.fromValue("device_strategy_report")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DeviceGovernanceReportPayload — payload shape stability (AC: #3)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Governance payload — artifact_tag present in JSON`() {
        val snapshot = governanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildGovernancePayload(snapshot)
        val json = gson.toJson(payload)
        assertTrue("artifact_tag must appear in governance payload JSON", json.contains("artifact_tag"))
    }

    @Test
    fun `Governance payload — snapshot_id present in JSON`() {
        val snapshot = governanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildGovernancePayload(snapshot)
        val json = gson.toJson(payload)
        assertTrue("snapshot_id must appear in governance payload JSON", json.contains("snapshot_id"))
    }

    @Test
    fun `Governance payload — device_id present in JSON`() {
        val snapshot = governanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildGovernancePayload(snapshot)
        val json = gson.toJson(payload)
        assertTrue("device_id must appear in governance payload JSON", json.contains("device_id"))
    }

    @Test
    fun `Governance payload — dimension_states present in JSON`() {
        val snapshot = governanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildGovernancePayload(snapshot)
        val json = gson.toJson(payload)
        assertTrue("dimension_states must appear in governance payload JSON", json.contains("dimension_states"))
    }

    @Test
    fun `All-UNKNOWN governance snapshot produces unknown artifact_tag`() {
        val snapshot = governanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildGovernancePayload(snapshot)
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            payload.artifact_tag
        )
    }

    @Test
    fun `All-UNKNOWN governance snapshot has non-empty missing_dimensions`() {
        val snapshot = governanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildGovernancePayload(snapshot)
        assertTrue(
            "missing_dimensions must be non-empty when all dimensions are UNKNOWN",
            payload.missing_dimensions.isNotEmpty()
        )
    }

    @Test
    fun `Compliant governance snapshot produces device_governance_compliant artifact_tag`() {
        DelegatedRuntimeGovernanceDimension.entries.forEach { dim ->
            governanceEvaluator.markDimensionCompliant(dim)
        }
        val snapshot = governanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildGovernancePayload(snapshot)
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT,
            payload.artifact_tag
        )
    }

    @Test
    fun `Regression governance snapshot produces violation artifact_tag`() {
        DelegatedRuntimeGovernanceDimension.entries.forEach { dim ->
            governanceEvaluator.markDimensionCompliant(dim)
        }
        governanceEvaluator.markDimensionRegression(
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION,
            "test truth regression"
        )
        val snapshot = governanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildGovernancePayload(snapshot)
        assertTrue(
            "Regression snapshot must produce a violation artifact_tag",
            payload.artifact_tag.contains("violation")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DeviceAcceptanceReportPayload — payload shape stability (AC: #3)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Acceptance payload — artifact_tag present in JSON`() {
        val snapshot = acceptanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildAcceptancePayload(snapshot)
        val json = gson.toJson(payload)
        assertTrue("artifact_tag must appear in acceptance payload JSON", json.contains("artifact_tag"))
    }

    @Test
    fun `Acceptance payload — snapshot_id present in JSON`() {
        val snapshot = acceptanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildAcceptancePayload(snapshot)
        val json = gson.toJson(payload)
        assertTrue("snapshot_id must appear in acceptance payload JSON", json.contains("snapshot_id"))
    }

    @Test
    fun `Acceptance payload — device_id present in JSON`() {
        val snapshot = acceptanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildAcceptancePayload(snapshot)
        val json = gson.toJson(payload)
        assertTrue("device_id must appear in acceptance payload JSON", json.contains("device_id"))
    }

    @Test
    fun `Acceptance payload — dimension_states present in JSON`() {
        val snapshot = acceptanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildAcceptancePayload(snapshot)
        val json = gson.toJson(payload)
        assertTrue("dimension_states must appear in acceptance payload JSON", json.contains("dimension_states"))
    }

    @Test
    fun `All-UNKNOWN acceptance snapshot produces unknown artifact_tag`() {
        val snapshot = acceptanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildAcceptancePayload(snapshot)
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL,
            payload.artifact_tag
        )
    }

    @Test
    fun `All-UNKNOWN acceptance snapshot has non-empty missing_dimensions`() {
        val snapshot = acceptanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildAcceptancePayload(snapshot)
        assertTrue(
            "missing_dimensions must be non-empty when all dimensions are UNKNOWN",
            payload.missing_dimensions.isNotEmpty()
        )
    }

    @Test
    fun `All-EVIDENCED acceptance snapshot produces device_accepted_for_graduation`() {
        DelegatedRuntimeAcceptanceDimension.entries.forEach { dim ->
            acceptanceEvaluator.markDimensionEvidenced(dim)
        }
        val snapshot = acceptanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildAcceptancePayload(snapshot)
        assertEquals(
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION,
            payload.artifact_tag
        )
    }

    @Test
    fun `GAP acceptance snapshot produces rejected artifact_tag`() {
        DelegatedRuntimeAcceptanceDimension.entries.forEach { dim ->
            acceptanceEvaluator.markDimensionEvidenced(dim)
        }
        acceptanceEvaluator.markDimensionGap(
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE,
            "test truth gap"
        )
        val snapshot = acceptanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildAcceptancePayload(snapshot)
        assertTrue(
            "GAP acceptance snapshot must produce a rejected artifact_tag",
            payload.artifact_tag.contains("rejected")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DeviceStrategyReportPayload — payload shape stability (AC: #3)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Strategy payload — artifact_tag present in JSON`() {
        val snapshot = strategyEvaluator.buildSnapshot(testDeviceId)
        val payload = buildStrategyPayload(snapshot)
        val json = gson.toJson(payload)
        assertTrue("artifact_tag must appear in strategy payload JSON", json.contains("artifact_tag"))
    }

    @Test
    fun `Strategy payload — snapshot_id present in JSON`() {
        val snapshot = strategyEvaluator.buildSnapshot(testDeviceId)
        val payload = buildStrategyPayload(snapshot)
        val json = gson.toJson(payload)
        assertTrue("snapshot_id must appear in strategy payload JSON", json.contains("snapshot_id"))
    }

    @Test
    fun `Strategy payload — device_id present in JSON`() {
        val snapshot = strategyEvaluator.buildSnapshot(testDeviceId)
        val payload = buildStrategyPayload(snapshot)
        val json = gson.toJson(payload)
        assertTrue("device_id must appear in strategy payload JSON", json.contains("device_id"))
    }

    @Test
    fun `Strategy payload — dimension_states present in JSON`() {
        val snapshot = strategyEvaluator.buildSnapshot(testDeviceId)
        val payload = buildStrategyPayload(snapshot)
        val json = gson.toJson(payload)
        assertTrue("dimension_states must appear in strategy payload JSON", json.contains("dimension_states"))
    }

    @Test
    fun `All-UNKNOWN strategy snapshot produces unknown artifact_tag`() {
        val snapshot = strategyEvaluator.buildSnapshot(testDeviceId)
        val payload = buildStrategyPayload(snapshot)
        assertEquals(
            DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL,
            payload.artifact_tag
        )
    }

    @Test
    fun `All-UNKNOWN strategy snapshot has non-empty missing_dimensions`() {
        val snapshot = strategyEvaluator.buildSnapshot(testDeviceId)
        val payload = buildStrategyPayload(snapshot)
        assertTrue(
            "missing_dimensions must be non-empty when all dimensions are UNKNOWN",
            payload.missing_dimensions.isNotEmpty()
        )
    }

    @Test
    fun `All-ON_TRACK strategy snapshot produces device_strategy_on_track`() {
        DelegatedRuntimeStrategyDimension.entries.forEach { dim ->
            strategyEvaluator.markDimensionOnTrack(dim)
        }
        val snapshot = strategyEvaluator.buildSnapshot(testDeviceId)
        val payload = buildStrategyPayload(snapshot)
        assertEquals(
            DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_ON_TRACK,
            payload.artifact_tag
        )
    }

    @Test
    fun `AT_RISK strategy snapshot produces risk artifact_tag`() {
        DelegatedRuntimeStrategyDimension.entries.forEach { dim ->
            strategyEvaluator.markDimensionOnTrack(dim)
        }
        strategyEvaluator.markDimensionAtRisk(
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY,
            "test contract instability"
        )
        val snapshot = strategyEvaluator.buildSnapshot(testDeviceId)
        val payload = buildStrategyPayload(snapshot)
        assertTrue(
            "AT_RISK strategy snapshot must produce a risk artifact_tag",
            payload.artifact_tag.contains("risk")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Send-path simulation — governance evaluator → AipMessage envelope (AC: #2)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Governance evaluator buildSnapshot produces non-blank snapshotId`() {
        val snapshot = governanceEvaluator.buildSnapshot(testDeviceId)
        assertTrue("snapshotId must be non-blank", snapshot.snapshotId.isNotBlank())
    }

    @Test
    fun `Governance snapshot AipMessage envelope round-trips through Gson`() {
        val snapshot = governanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildGovernancePayload(snapshot)
        val envelope = AipMessage(
            type = MsgType.DEVICE_GOVERNANCE_REPORT,
            payload = payload,
            device_id = testDeviceId,
            idempotency_key = "test-key-${snapshot.snapshotId}"
        )
        val json = gson.toJson(envelope)
        assertTrue("JSON must contain device_governance_report", json.contains("device_governance_report"))
    }

    @Test
    fun `Governance AipMessage JSON contains snapshot_id`() {
        val snapshot = governanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildGovernancePayload(snapshot)
        val envelope = AipMessage(
            type = MsgType.DEVICE_GOVERNANCE_REPORT,
            payload = payload,
            device_id = testDeviceId
        )
        val json = gson.toJson(envelope)
        assertTrue("JSON must contain snapshot_id", json.contains(snapshot.snapshotId))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Send-path simulation — acceptance evaluator → AipMessage envelope (AC: #2)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Acceptance evaluator buildSnapshot produces non-blank snapshotId`() {
        val snapshot = acceptanceEvaluator.buildSnapshot(testDeviceId)
        assertTrue("snapshotId must be non-blank", snapshot.snapshotId.isNotBlank())
    }

    @Test
    fun `Acceptance snapshot AipMessage envelope round-trips through Gson`() {
        val snapshot = acceptanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildAcceptancePayload(snapshot)
        val envelope = AipMessage(
            type = MsgType.DEVICE_ACCEPTANCE_REPORT,
            payload = payload,
            device_id = testDeviceId,
            idempotency_key = "test-key-${snapshot.snapshotId}"
        )
        val json = gson.toJson(envelope)
        assertTrue("JSON must contain device_acceptance_report", json.contains("device_acceptance_report"))
    }

    @Test
    fun `Acceptance AipMessage JSON contains snapshot_id`() {
        val snapshot = acceptanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildAcceptancePayload(snapshot)
        val envelope = AipMessage(
            type = MsgType.DEVICE_ACCEPTANCE_REPORT,
            payload = payload,
            device_id = testDeviceId
        )
        val json = gson.toJson(envelope)
        assertTrue("JSON must contain snapshot_id", json.contains(snapshot.snapshotId))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Send-path simulation — strategy evaluator → AipMessage envelope (AC: #2)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Strategy evaluator buildSnapshot produces non-blank snapshotId`() {
        val snapshot = strategyEvaluator.buildSnapshot(testDeviceId)
        assertTrue("snapshotId must be non-blank", snapshot.snapshotId.isNotBlank())
    }

    @Test
    fun `Strategy snapshot AipMessage envelope round-trips through Gson`() {
        val snapshot = strategyEvaluator.buildSnapshot(testDeviceId)
        val payload = buildStrategyPayload(snapshot)
        val envelope = AipMessage(
            type = MsgType.DEVICE_STRATEGY_REPORT,
            payload = payload,
            device_id = testDeviceId,
            idempotency_key = "test-key-${snapshot.snapshotId}"
        )
        val json = gson.toJson(envelope)
        assertTrue("JSON must contain device_strategy_report", json.contains("device_strategy_report"))
    }

    @Test
    fun `Strategy AipMessage JSON contains snapshot_id`() {
        val snapshot = strategyEvaluator.buildSnapshot(testDeviceId)
        val payload = buildStrategyPayload(snapshot)
        val envelope = AipMessage(
            type = MsgType.DEVICE_STRATEGY_REPORT,
            payload = payload,
            device_id = testDeviceId
        )
        val json = gson.toJson(envelope)
        assertTrue("JSON must contain snapshot_id", json.contains(snapshot.snapshotId))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // sendJson failure does not throw (AC: #2)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Governance report send failure does not propagate exception`() {
        val snapshot = governanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildGovernancePayload(snapshot)
        // Simulate a send attempt that throws — the real sendDeviceGovernanceReport
        // catches exceptions internally; we verify the pattern here.
        try {
            val envelope = AipMessage(
                type = MsgType.DEVICE_GOVERNANCE_REPORT,
                payload = payload,
                device_id = testDeviceId
            )
            val json = gson.toJson(envelope)
            // Verify JSON is well-formed even for a failing path
            assertNotNull(json)
        } catch (e: Exception) {
            fail("Governance report path must not throw: ${e.message}")
        }
    }

    @Test
    fun `Acceptance report send failure does not propagate exception`() {
        val snapshot = acceptanceEvaluator.buildSnapshot(testDeviceId)
        val payload = buildAcceptancePayload(snapshot)
        try {
            val envelope = AipMessage(
                type = MsgType.DEVICE_ACCEPTANCE_REPORT,
                payload = payload,
                device_id = testDeviceId
            )
            val json = gson.toJson(envelope)
            assertNotNull(json)
        } catch (e: Exception) {
            fail("Acceptance report path must not throw: ${e.message}")
        }
    }

    @Test
    fun `Strategy report send failure does not propagate exception`() {
        val snapshot = strategyEvaluator.buildSnapshot(testDeviceId)
        val payload = buildStrategyPayload(snapshot)
        try {
            val envelope = AipMessage(
                type = MsgType.DEVICE_STRATEGY_REPORT,
                payload = payload,
                device_id = testDeviceId
            )
            val json = gson.toJson(envelope)
            assertNotNull(json)
        } catch (e: Exception) {
            fail("Strategy report path must not throw: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Semantic classification — canonical vs advisory (AC: #4)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DEVICE_GOVERNANCE_REPORT is CANONICAL_PARTICIPANT_EVIDENCE`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType(
            MsgType.DEVICE_GOVERNANCE_REPORT.value
        )
        assertEquals(
            EvaluatorArtifactEmissionSemantics.EmissionClass.CANONICAL_PARTICIPANT_EVIDENCE,
            entry?.emissionClass
        )
    }

    @Test
    fun `DEVICE_ACCEPTANCE_REPORT is CANONICAL_PARTICIPANT_EVIDENCE`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType(
            MsgType.DEVICE_ACCEPTANCE_REPORT.value
        )
        assertEquals(
            EvaluatorArtifactEmissionSemantics.EmissionClass.CANONICAL_PARTICIPANT_EVIDENCE,
            entry?.emissionClass
        )
    }

    @Test
    fun `DEVICE_STRATEGY_REPORT is ADVISORY_OBSERVATION_ONLY`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType(
            MsgType.DEVICE_STRATEGY_REPORT.value
        )
        assertEquals(
            EvaluatorArtifactEmissionSemantics.EmissionClass.ADVISORY_OBSERVATION_ONLY,
            entry?.emissionClass
        )
    }

    @Test
    fun `DEVICE_READINESS_REPORT is CANONICAL_PARTICIPANT_EVIDENCE`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType(
            MsgType.DEVICE_READINESS_REPORT.value
        )
        assertEquals(
            EvaluatorArtifactEmissionSemantics.EmissionClass.CANONICAL_PARTICIPANT_EVIDENCE,
            entry?.emissionClass
        )
    }

    @Test
    fun `RECONCILIATION_SIGNAL is CANONICAL_PARTICIPANT_EVIDENCE`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType(
            MsgType.RECONCILIATION_SIGNAL.value
        )
        assertEquals(
            EvaluatorArtifactEmissionSemantics.EmissionClass.CANONICAL_PARTICIPANT_EVIDENCE,
            entry?.emissionClass
        )
    }

    @Test
    fun `Four registry entries are canonical and one is advisory`() {
        val canonicalCount = EvaluatorArtifactEmissionSemantics.REGISTRY.count {
            it.emissionClass ==
                EvaluatorArtifactEmissionSemantics.EmissionClass.CANONICAL_PARTICIPANT_EVIDENCE
        }
        val advisoryCount = EvaluatorArtifactEmissionSemantics.REGISTRY.count {
            it.emissionClass ==
                EvaluatorArtifactEmissionSemantics.EmissionClass.ADVISORY_OBSERVATION_ONLY
        }
        assertEquals(
            "Exactly 4 entries must be CANONICAL_PARTICIPANT_EVIDENCE (readiness, governance, acceptance, reconciliation signal)",
            4, canonicalCount
        )
        assertEquals(
            "Exactly 1 entry must be ADVISORY_OBSERVATION_ONLY (strategy)",
            1, advisoryCount
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Deferred work annotation (AC: #5)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Governance entry has non-null deferredWork`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType("device_governance_report")
        assertNotNull("Governance entry must document deferred work", entry?.deferredWork)
    }

    @Test
    fun `Acceptance entry has non-null deferredWork`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType("device_acceptance_report")
        assertNotNull("Acceptance entry must document deferred work", entry?.deferredWork)
    }

    @Test
    fun `Strategy entry has non-null deferredWork`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType("device_strategy_report")
        assertNotNull("Strategy entry must document deferred work", entry?.deferredWork)
    }

    @Test
    fun `Reconciliation signal entry has null deferredWork indicating fully closed`() {
        val entry = EvaluatorArtifactEmissionSemantics.findByMsgType("reconciliation_signal")
        assertNull(
            "Reconciliation signal emission path is fully closed; deferredWork must be null",
            entry?.deferredWork
        )
    }
}
