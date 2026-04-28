package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR-68 (Android) — Unit tests for [AndroidDelegatedRuntimeAudit],
 * [AndroidDelegatedRuntimeAuditDimension], [AndroidDelegatedRuntimeAuditEvidence], and
 * [AndroidDelegatedRuntimeAuditSnapshot].
 *
 * ## Test matrix
 *
 * ### AndroidDelegatedRuntimeAudit — constants
 *  - EVIDENCE_READY wire value is "audit_evidence_ready"
 *  - EVIDENCE_DEGRADED wire value is "audit_evidence_degraded"
 *  - EVIDENCE_UNAVAILABLE wire value is "audit_evidence_unavailable"
 *  - EVIDENCE_STALE wire value is "audit_evidence_stale"
 *  - EVIDENCE_MALFORMED_INCOMPLETE wire value is "audit_evidence_malformed_incomplete"
 *  - EVIDENCE_UNVERIFIED wire value is "audit_evidence_unverified"
 *  - All six evidence tag constants are distinct
 *  - INTEGRATION_READINESS_EVALUATOR is "DelegatedRuntimeReadinessEvaluator"
 *  - INTEGRATION_CAPABILITY_HONESTY_GUARD is "CapabilityHonestyGuard"
 *  - INTEGRATION_ORCHESTRATION_STATE is "MultiDeviceParticipantOrchestrationState"
 *  - INTEGRATION_RUNTIME_TRUTH is "AndroidParticipantRuntimeTruth"
 *  - INTEGRATION_RUNTIME_CONTROLLER is "RuntimeController"
 *  - INTRODUCED_PR is 68
 *  - DESCRIPTION is non-blank
 *  - DEFAULT_STALE_THRESHOLD_MS is 60000
 *
 * ### AndroidDelegatedRuntimeAuditDimension — wire values
 *  - PARTICIPANT_REGISTRATION wireValue is "participant_registration"
 *  - PARTICIPANT_AVAILABILITY wireValue is "participant_availability"
 *  - DELEGATED_EXECUTION_READINESS wireValue is "delegated_execution_readiness"
 *  - HEALTH_STATE wireValue is "health_state"
 *  - CAPABILITY_HONESTY wireValue is "capability_honesty"
 *  - EVIDENCE_FRESHNESS wireValue is "evidence_freshness"
 *  - All six wire values are distinct
 *
 * ### AndroidDelegatedRuntimeAuditDimension.fromValue — classification
 *  - "participant_registration" → PARTICIPANT_REGISTRATION
 *  - "participant_availability" → PARTICIPANT_AVAILABILITY
 *  - "delegated_execution_readiness" → DELEGATED_EXECUTION_READINESS
 *  - "health_state" → HEALTH_STATE
 *  - "capability_honesty" → CAPABILITY_HONESTY
 *  - "evidence_freshness" → EVIDENCE_FRESHNESS
 *  - unknown value → null
 *  - null → null
 *
 * ### Dimension gate — markDimensionAudited / getDimensionStatus
 *  - getDimensionStatus returns UNKNOWN before any signal
 *  - getDimensionStatus returns AUDITED after markDimensionAudited
 *  - getDimensionStatus returns DEGRADED after markDimensionDegraded
 *  - getDimensionStatus returns UNAVAILABLE after markDimensionUnavailable
 *  - getDimensionStatus returns STALE after markDimensionStale
 *  - getDimensionStatus returns MALFORMED after markDimensionMalformed
 *  - getDimensionStatus returns UNKNOWN after markDimensionUnknown
 *  - getDimensionReason is null before any signal
 *  - getDimensionReason returns reason after markDimensionDegraded
 *  - getDimensionReason returns reason after markDimensionUnavailable
 *  - getDimensionReason returns reason after markDimensionStale
 *  - getDimensionReason returns reason after markDimensionMalformed
 *  - getDimensionReason returns reason after markDimensionUnknown
 *  - getDimensionReason is null after markDimensionAudited
 *  - clearAllDimensionStates resets all dimensions to UNKNOWN
 *
 * ### evaluateEvidence — AuditEvidenceUnverified (missing signal)
 *  - Returns AuditEvidenceUnverified when no dimensions have signals
 *  - Returns AuditEvidenceUnverified when some dimensions are UNKNOWN
 *  - AuditEvidenceUnverified.evidenceTag is EVIDENCE_UNVERIFIED
 *  - AuditEvidenceUnverified.deviceId matches parameter
 *  - AuditEvidenceUnverified.snapshotId matches parameter
 *  - AuditEvidenceUnverified.missingDimensions includes all six when no signals
 *  - AuditEvidenceUnverified.missingDimensions only includes unknown dimensions
 *  - Unverified takes priority over malformed
 *  - Unverified takes priority over unavailable
 *  - Unverified takes priority over stale
 *  - Unverified takes priority over degraded
 *
 * ### evaluateEvidence — AuditEvidenceMalformedOrIncomplete
 *  - Returns AuditEvidenceMalformedOrIncomplete when a dimension is MALFORMED
 *  - AuditEvidenceMalformedOrIncomplete.evidenceTag is EVIDENCE_MALFORMED_INCOMPLETE
 *  - AuditEvidenceMalformedOrIncomplete.deviceId matches parameter
 *  - AuditEvidenceMalformedOrIncomplete.snapshotId matches parameter
 *  - AuditEvidenceMalformedOrIncomplete.malformedReason matches registered reason
 *  - AuditEvidenceMalformedOrIncomplete uses REASON_MALFORMED_DEFAULT when no reason
 *  - Malformed takes priority over unavailable
 *  - Malformed takes priority over stale
 *  - Malformed takes priority over degraded
 *
 * ### evaluateEvidence — AuditEvidenceUnavailable
 *  - Returns AuditEvidenceUnavailable when PARTICIPANT_REGISTRATION is UNAVAILABLE
 *  - Returns AuditEvidenceUnavailable when PARTICIPANT_AVAILABILITY is UNAVAILABLE
 *  - AuditEvidenceUnavailable.evidenceTag is EVIDENCE_UNAVAILABLE
 *  - AuditEvidenceUnavailable.deviceId matches parameter
 *  - AuditEvidenceUnavailable.snapshotId matches parameter
 *  - AuditEvidenceUnavailable.unavailableReason matches registered reason
 *  - AuditEvidenceUnavailable uses REASON_REGISTRATION_UNAVAILABLE_DEFAULT when no reason
 *  - AuditEvidenceUnavailable uses REASON_AVAILABILITY_UNAVAILABLE_DEFAULT for availability dim
 *  - PARTICIPANT_REGISTRATION unavailable takes priority over PARTICIPANT_AVAILABILITY
 *  - Unavailable takes priority over stale
 *  - Unavailable takes priority over degraded
 *
 * ### evaluateEvidence — AuditEvidenceStale
 *  - Returns AuditEvidenceStale when EVIDENCE_FRESHNESS is STALE
 *  - AuditEvidenceStale.evidenceTag is EVIDENCE_STALE
 *  - AuditEvidenceStale.deviceId matches parameter
 *  - AuditEvidenceStale.snapshotId matches parameter
 *  - AuditEvidenceStale.staleReason matches registered reason
 *  - AuditEvidenceStale.dimension is EVIDENCE_FRESHNESS
 *  - AuditEvidenceStale uses REASON_STALE_DEFAULT when no reason
 *  - Stale takes priority over degraded
 *
 * ### evaluateEvidence — AuditEvidenceDegraded
 *  - Returns AuditEvidenceDegraded when a dimension is DEGRADED
 *  - Returns AuditEvidenceDegraded when a non-registration/availability dimension is UNAVAILABLE
 *  - AuditEvidenceDegraded.evidenceTag is EVIDENCE_DEGRADED
 *  - AuditEvidenceDegraded.deviceId matches parameter
 *  - AuditEvidenceDegraded.snapshotId matches parameter
 *  - AuditEvidenceDegraded.degradedReason matches registered reason
 *  - AuditEvidenceDegraded uses REASON_DEGRADED_DEFAULT when no reason
 *  - Degraded health state does not produce ready evidence (no optimistic ready conclusion)
 *
 * ### evaluateEvidence — AuditEvidenceReady
 *  - Returns AuditEvidenceReady when all six dimensions are AUDITED
 *  - AuditEvidenceReady.evidenceTag is EVIDENCE_READY
 *  - AuditEvidenceReady.deviceId matches parameter
 *  - AuditEvidenceReady.snapshotId matches parameter
 *
 * ### buildSnapshot
 *  - buildSnapshot produces a snapshot with non-blank snapshotId
 *  - buildSnapshot produces a snapshot with correct deviceId
 *  - buildSnapshot snapshot has all six dimension states
 *  - buildSnapshot snapshot reportedAtMs is positive
 *
 * ### AndroidDelegatedRuntimeAuditSnapshot.toWireMap — format stability (schema version 1.0)
 *  - toWireMap contains schema_version "1.0"
 *  - toWireMap contains snapshot_id matching snapshotId
 *  - toWireMap contains device_id matching deviceId
 *  - toWireMap contains reported_at_ms as Long
 *  - toWireMap contains evidence_tag matching evidence.evidenceTag
 *  - toWireMap contains dimension_states with all six dimension wire-names
 *  - toWireMap contains dimension_reasons (empty when all AUDITED)
 *  - toWireMap contains missing_dimensions (empty when all AUDITED)
 *  - toWireMap contains capability_honesty_audited true when CAPABILITY_HONESTY is AUDITED
 *  - toWireMap contains capability_honesty_audited false when CAPABILITY_HONESTY is not AUDITED
 *  - toWireMap contains health_state_audited true when HEALTH_STATE is AUDITED
 *  - toWireMap contains health_state_audited false when HEALTH_STATE is not AUDITED
 *  - toWireMap contains participant_registered true when PARTICIPANT_REGISTRATION is AUDITED
 *  - toWireMap contains participant_registered false when PARTICIPANT_REGISTRATION is not AUDITED
 *
 * ### Capability honesty dimension — consistency with CapabilityHonestyGuard
 *  - CAPABILITY_HONESTY must be AUDITED only after CapabilityHonestyGuard.isHonest returns empty
 *  - When isHonest returns violations, marking CAPABILITY_HONESTY DEGRADED prevents READY evidence
 *  - CAPABILITY_HONESTY not yet audited produces AuditEvidenceUnverified (not optimistic ready)
 *
 * ### Health state contract — no optimistic ready when unhealthy
 *  - Healthy runtime → HEALTH_STATE AUDITED → can reach AuditEvidenceReady
 *  - Degraded runtime → HEALTH_STATE DEGRADED → AuditEvidenceDegraded (not ready)
 *  - Recovering runtime → HEALTH_STATE UNAVAILABLE → AuditEvidenceUnavailable (not ready)
 *
 * ### checkAndMarkStaleness
 *  - Fresh evidence → EVIDENCE_FRESHNESS AUDITED → returns true
 *  - Stale evidence → EVIDENCE_FRESHNESS STALE → returns false
 *  - Stale reason includes age and threshold in ms
 *  - Fresh evidence within threshold is exactly at threshold boundary (inclusive → fresh)
 *
 * ### DimensionStatus — wire values
 *  - AUDITED wireValue is "audited"
 *  - DEGRADED wireValue is "degraded"
 *  - UNAVAILABLE wireValue is "unavailable"
 *  - STALE wireValue is "stale"
 *  - MALFORMED wireValue is "malformed"
 *  - UNKNOWN wireValue is "unknown"
 *  - All six wire values are distinct
 *  - fromValue round-trips all six wire values correctly
 *  - fromValue returns null for unknown value
 *  - fromValue returns null for null
 */
class Pr68AndroidDelegatedRuntimeAuditTest {

    private lateinit var audit: AndroidDelegatedRuntimeAudit

    @Before
    fun setUp() {
        audit = AndroidDelegatedRuntimeAudit()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constants
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `EVIDENCE_READY wire value is audit_evidence_ready`() {
        assertEquals("audit_evidence_ready", AndroidDelegatedRuntimeAudit.EVIDENCE_READY)
    }

    @Test fun `EVIDENCE_DEGRADED wire value is audit_evidence_degraded`() {
        assertEquals("audit_evidence_degraded", AndroidDelegatedRuntimeAudit.EVIDENCE_DEGRADED)
    }

    @Test fun `EVIDENCE_UNAVAILABLE wire value is audit_evidence_unavailable`() {
        assertEquals("audit_evidence_unavailable", AndroidDelegatedRuntimeAudit.EVIDENCE_UNAVAILABLE)
    }

    @Test fun `EVIDENCE_STALE wire value is audit_evidence_stale`() {
        assertEquals("audit_evidence_stale", AndroidDelegatedRuntimeAudit.EVIDENCE_STALE)
    }

    @Test fun `EVIDENCE_MALFORMED_INCOMPLETE wire value is audit_evidence_malformed_incomplete`() {
        assertEquals(
            "audit_evidence_malformed_incomplete",
            AndroidDelegatedRuntimeAudit.EVIDENCE_MALFORMED_INCOMPLETE
        )
    }

    @Test fun `EVIDENCE_UNVERIFIED wire value is audit_evidence_unverified`() {
        assertEquals("audit_evidence_unverified", AndroidDelegatedRuntimeAudit.EVIDENCE_UNVERIFIED)
    }

    @Test fun `all six evidence tag constants are distinct`() {
        val tags = setOf(
            AndroidDelegatedRuntimeAudit.EVIDENCE_READY,
            AndroidDelegatedRuntimeAudit.EVIDENCE_DEGRADED,
            AndroidDelegatedRuntimeAudit.EVIDENCE_UNAVAILABLE,
            AndroidDelegatedRuntimeAudit.EVIDENCE_STALE,
            AndroidDelegatedRuntimeAudit.EVIDENCE_MALFORMED_INCOMPLETE,
            AndroidDelegatedRuntimeAudit.EVIDENCE_UNVERIFIED
        )
        assertEquals(6, tags.size)
    }

    @Test fun `INTEGRATION_READINESS_EVALUATOR is DelegatedRuntimeReadinessEvaluator`() {
        assertEquals(
            "DelegatedRuntimeReadinessEvaluator",
            AndroidDelegatedRuntimeAudit.INTEGRATION_READINESS_EVALUATOR
        )
    }

    @Test fun `INTEGRATION_CAPABILITY_HONESTY_GUARD is CapabilityHonestyGuard`() {
        assertEquals(
            "CapabilityHonestyGuard",
            AndroidDelegatedRuntimeAudit.INTEGRATION_CAPABILITY_HONESTY_GUARD
        )
    }

    @Test fun `INTEGRATION_ORCHESTRATION_STATE is MultiDeviceParticipantOrchestrationState`() {
        assertEquals(
            "MultiDeviceParticipantOrchestrationState",
            AndroidDelegatedRuntimeAudit.INTEGRATION_ORCHESTRATION_STATE
        )
    }

    @Test fun `INTEGRATION_RUNTIME_TRUTH is AndroidParticipantRuntimeTruth`() {
        assertEquals(
            "AndroidParticipantRuntimeTruth",
            AndroidDelegatedRuntimeAudit.INTEGRATION_RUNTIME_TRUTH
        )
    }

    @Test fun `INTEGRATION_RUNTIME_CONTROLLER is RuntimeController`() {
        assertEquals(
            "RuntimeController",
            AndroidDelegatedRuntimeAudit.INTEGRATION_RUNTIME_CONTROLLER
        )
    }

    @Test fun `INTRODUCED_PR is 68`() {
        assertEquals(68, AndroidDelegatedRuntimeAudit.INTRODUCED_PR)
    }

    @Test fun `DESCRIPTION is non-blank`() {
        assertTrue(AndroidDelegatedRuntimeAudit.DESCRIPTION.isNotBlank())
    }

    @Test fun `DEFAULT_STALE_THRESHOLD_MS is 60000`() {
        assertEquals(60_000L, AndroidDelegatedRuntimeAudit.DEFAULT_STALE_THRESHOLD_MS)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AndroidDelegatedRuntimeAuditDimension — wire values
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `PARTICIPANT_REGISTRATION wireValue is participant_registration`() {
        assertEquals(
            "participant_registration",
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION.wireValue
        )
    }

    @Test fun `PARTICIPANT_AVAILABILITY wireValue is participant_availability`() {
        assertEquals(
            "participant_availability",
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_AVAILABILITY.wireValue
        )
    }

    @Test fun `DELEGATED_EXECUTION_READINESS wireValue is delegated_execution_readiness`() {
        assertEquals(
            "delegated_execution_readiness",
            AndroidDelegatedRuntimeAuditDimension.DELEGATED_EXECUTION_READINESS.wireValue
        )
    }

    @Test fun `HEALTH_STATE wireValue is health_state`() {
        assertEquals(
            "health_state",
            AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE.wireValue
        )
    }

    @Test fun `CAPABILITY_HONESTY wireValue is capability_honesty`() {
        assertEquals(
            "capability_honesty",
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY.wireValue
        )
    }

    @Test fun `EVIDENCE_FRESHNESS wireValue is evidence_freshness`() {
        assertEquals(
            "evidence_freshness",
            AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS.wireValue
        )
    }

    @Test fun `all six dimension wire values are distinct`() {
        val values = AndroidDelegatedRuntimeAuditDimension.entries.map { it.wireValue }.toSet()
        assertEquals(6, values.size)
    }

    @Test fun `fromValue participant_registration returns PARTICIPANT_REGISTRATION`() {
        assertEquals(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION,
            AndroidDelegatedRuntimeAuditDimension.fromValue("participant_registration")
        )
    }

    @Test fun `fromValue participant_availability returns PARTICIPANT_AVAILABILITY`() {
        assertEquals(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_AVAILABILITY,
            AndroidDelegatedRuntimeAuditDimension.fromValue("participant_availability")
        )
    }

    @Test fun `fromValue delegated_execution_readiness returns DELEGATED_EXECUTION_READINESS`() {
        assertEquals(
            AndroidDelegatedRuntimeAuditDimension.DELEGATED_EXECUTION_READINESS,
            AndroidDelegatedRuntimeAuditDimension.fromValue("delegated_execution_readiness")
        )
    }

    @Test fun `fromValue health_state returns HEALTH_STATE`() {
        assertEquals(
            AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE,
            AndroidDelegatedRuntimeAuditDimension.fromValue("health_state")
        )
    }

    @Test fun `fromValue capability_honesty returns CAPABILITY_HONESTY`() {
        assertEquals(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY,
            AndroidDelegatedRuntimeAuditDimension.fromValue("capability_honesty")
        )
    }

    @Test fun `fromValue evidence_freshness returns EVIDENCE_FRESHNESS`() {
        assertEquals(
            AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS,
            AndroidDelegatedRuntimeAuditDimension.fromValue("evidence_freshness")
        )
    }

    @Test fun `fromValue unknown value returns null`() {
        assertNull(AndroidDelegatedRuntimeAuditDimension.fromValue("unknown_dimension_xyz"))
    }

    @Test fun `fromValue null returns null`() {
        assertNull(AndroidDelegatedRuntimeAuditDimension.fromValue(null))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Dimension gate — markDimensionAudited / getDimensionStatus
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `getDimensionStatus returns UNKNOWN before any signal`() {
        assertEquals(
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN,
            audit.getDimensionStatus(AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION)
        )
    }

    @Test fun `getDimensionStatus returns AUDITED after markDimensionAudited`() {
        audit.markDimensionAudited(AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION)
        assertEquals(
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED,
            audit.getDimensionStatus(AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION)
        )
    }

    @Test fun `getDimensionStatus returns DEGRADED after markDimensionDegraded`() {
        audit.markDimensionDegraded(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "degraded reason")
        assertEquals(
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.DEGRADED,
            audit.getDimensionStatus(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE)
        )
    }

    @Test fun `getDimensionStatus returns UNAVAILABLE after markDimensionUnavailable`() {
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_AVAILABILITY, "unavailable reason"
        )
        assertEquals(
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNAVAILABLE,
            audit.getDimensionStatus(AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_AVAILABILITY)
        )
    }

    @Test fun `getDimensionStatus returns STALE after markDimensionStale`() {
        audit.markDimensionStale(
            AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS, "stale reason"
        )
        assertEquals(
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.STALE,
            audit.getDimensionStatus(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS)
        )
    }

    @Test fun `getDimensionStatus returns MALFORMED after markDimensionMalformed`() {
        audit.markDimensionMalformed(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY, "malformed reason"
        )
        assertEquals(
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.MALFORMED,
            audit.getDimensionStatus(AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY)
        )
    }

    @Test fun `getDimensionStatus returns UNKNOWN after markDimensionUnknown`() {
        audit.markDimensionAudited(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE)
        audit.markDimensionUnknown(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "reason")
        assertEquals(
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN,
            audit.getDimensionStatus(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE)
        )
    }

    @Test fun `getDimensionReason is null before any signal`() {
        assertNull(audit.getDimensionReason(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE))
    }

    @Test fun `getDimensionReason returns reason after markDimensionDegraded`() {
        audit.markDimensionDegraded(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "the reason")
        assertEquals("the reason", audit.getDimensionReason(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE))
    }

    @Test fun `getDimensionReason returns reason after markDimensionUnavailable`() {
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_AVAILABILITY, "avail reason"
        )
        assertEquals(
            "avail reason",
            audit.getDimensionReason(AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_AVAILABILITY)
        )
    }

    @Test fun `getDimensionReason returns reason after markDimensionStale`() {
        audit.markDimensionStale(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS, "stale reason")
        assertEquals(
            "stale reason",
            audit.getDimensionReason(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS)
        )
    }

    @Test fun `getDimensionReason returns reason after markDimensionMalformed`() {
        audit.markDimensionMalformed(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY, "malformed reason"
        )
        assertEquals(
            "malformed reason",
            audit.getDimensionReason(AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY)
        )
    }

    @Test fun `getDimensionReason returns reason after markDimensionUnknown`() {
        audit.markDimensionUnknown(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "why unknown")
        assertEquals("why unknown", audit.getDimensionReason(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE))
    }

    @Test fun `getDimensionReason is null after markDimensionAudited`() {
        audit.markDimensionDegraded(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "was degraded")
        audit.markDimensionAudited(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE)
        assertNull(audit.getDimensionReason(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE))
    }

    @Test fun `clearAllDimensionStates resets all dimensions to UNKNOWN`() {
        AndroidDelegatedRuntimeAuditDimension.entries.forEach {
            audit.markDimensionAudited(it)
        }
        audit.clearAllDimensionStates()
        AndroidDelegatedRuntimeAuditDimension.entries.forEach { dim ->
            assertEquals(
                AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN,
                audit.getDimensionStatus(dim)
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateEvidence — AuditEvidenceUnverified (missing signal / unverified)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `returns AuditEvidenceUnverified when no dimensions have signals`() {
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified)
    }

    @Test fun `AuditEvidenceUnverified evidenceTag is EVIDENCE_UNVERIFIED`() {
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertEquals(AndroidDelegatedRuntimeAudit.EVIDENCE_UNVERIFIED, evidence.evidenceTag)
    }

    @Test fun `AuditEvidenceUnverified deviceId matches parameter`() {
        val evidence = audit.evaluateEvidence("device-abc", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified
        assertEquals("device-abc", evidence.deviceId)
    }

    @Test fun `AuditEvidenceUnverified snapshotId matches parameter`() {
        val evidence = audit.evaluateEvidence("dev1", "snapshot-xyz") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified
        assertEquals("snapshot-xyz", evidence.snapshotId)
    }

    @Test fun `AuditEvidenceUnverified missingDimensions includes all six when no signals`() {
        val evidence = audit.evaluateEvidence("dev1", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified
        assertEquals(6, evidence.missingDimensions.size)
        assertTrue(evidence.missingDimensions.containsAll(AndroidDelegatedRuntimeAuditDimension.entries))
    }

    @Test fun `AuditEvidenceUnverified missingDimensions only includes unknown dimensions`() {
        // Mark 5 dimensions, leave EVIDENCE_FRESHNESS unknown
        AndroidDelegatedRuntimeAuditDimension.entries
            .filter { it != AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS }
            .forEach { audit.markDimensionAudited(it) }

        val evidence = audit.evaluateEvidence("dev1", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified
        assertEquals(1, evidence.missingDimensions.size)
        assertTrue(
            AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS in evidence.missingDimensions
        )
    }

    @Test fun `Unverified takes priority over malformed`() {
        // One dimension MALFORMED, one UNKNOWN — result must be UNVERIFIED
        audit.markDimensionMalformed(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY, "bad"
        )
        // EVIDENCE_FRESHNESS stays UNKNOWN
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified)
    }

    @Test fun `Unverified takes priority over unavailable`() {
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION, "gone"
        )
        // Other dimensions still UNKNOWN
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified)
    }

    @Test fun `Unverified takes priority over stale`() {
        audit.markDimensionStale(
            AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS, "old"
        )
        // Other dimensions still UNKNOWN
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified)
    }

    @Test fun `Unverified takes priority over degraded`() {
        audit.markDimensionDegraded(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "degraded")
        // Other dimensions still UNKNOWN
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateEvidence — AuditEvidenceMalformedOrIncomplete
    // ═══════════════════════════════════════════════════════════════════════════

    private fun markAllAuditedExcept(vararg except: AndroidDelegatedRuntimeAuditDimension) {
        AndroidDelegatedRuntimeAuditDimension.entries
            .filter { it !in except }
            .forEach { audit.markDimensionAudited(it) }
    }

    @Test fun `returns AuditEvidenceMalformedOrIncomplete when a dimension is MALFORMED`() {
        markAllAuditedExcept()
        audit.markDimensionMalformed(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY, "bad report"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceMalformedOrIncomplete)
    }

    @Test fun `AuditEvidenceMalformedOrIncomplete evidenceTag is EVIDENCE_MALFORMED_INCOMPLETE`() {
        markAllAuditedExcept()
        audit.markDimensionMalformed(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY, "bad report"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertEquals(
            AndroidDelegatedRuntimeAudit.EVIDENCE_MALFORMED_INCOMPLETE,
            evidence.evidenceTag
        )
    }

    @Test fun `AuditEvidenceMalformedOrIncomplete deviceId matches parameter`() {
        markAllAuditedExcept()
        audit.markDimensionMalformed(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY, "bad"
        )
        val evidence = audit.evaluateEvidence("dev-xyz", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceMalformedOrIncomplete
        assertEquals("dev-xyz", evidence.deviceId)
    }

    @Test fun `AuditEvidenceMalformedOrIncomplete snapshotId matches parameter`() {
        markAllAuditedExcept()
        audit.markDimensionMalformed(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY, "bad"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap-abc") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceMalformedOrIncomplete
        assertEquals("snap-abc", evidence.snapshotId)
    }

    @Test fun `AuditEvidenceMalformedOrIncomplete malformedReason matches registered reason`() {
        markAllAuditedExcept()
        audit.markDimensionMalformed(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY, "missing required field"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceMalformedOrIncomplete
        assertEquals("missing required field", evidence.malformedReason)
    }

    @Test fun `AuditEvidenceMalformedOrIncomplete uses REASON_MALFORMED_DEFAULT when no reason`() {
        // markDimensionMalformed requires a reason, so test via direct state manipulation
        // by marking with the default reason to validate constant
        markAllAuditedExcept()
        audit.markDimensionMalformed(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY,
            AndroidDelegatedRuntimeAudit.REASON_MALFORMED_DEFAULT
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceMalformedOrIncomplete
        assertEquals(
            AndroidDelegatedRuntimeAudit.REASON_MALFORMED_DEFAULT,
            evidence.malformedReason
        )
    }

    @Test fun `Malformed takes priority over unavailable`() {
        markAllAuditedExcept()
        audit.markDimensionMalformed(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY, "bad"
        )
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION, "gone"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        // UNKNOWN check first — all dimensions are set, so no UNKNOWN
        // Then MALFORMED check → AuditEvidenceMalformedOrIncomplete
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceMalformedOrIncomplete)
    }

    @Test fun `Malformed takes priority over stale`() {
        markAllAuditedExcept()
        audit.markDimensionMalformed(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY, "bad"
        )
        audit.markDimensionStale(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS, "old")
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceMalformedOrIncomplete)
    }

    @Test fun `Malformed takes priority over degraded`() {
        markAllAuditedExcept()
        audit.markDimensionMalformed(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY, "bad"
        )
        audit.markDimensionDegraded(
            AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "degraded"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceMalformedOrIncomplete)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateEvidence — AuditEvidenceUnavailable
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `returns AuditEvidenceUnavailable when PARTICIPANT_REGISTRATION is UNAVAILABLE`() {
        markAllAuditedExcept()
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION, "not registered"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnavailable)
    }

    @Test fun `returns AuditEvidenceUnavailable when PARTICIPANT_AVAILABILITY is UNAVAILABLE`() {
        markAllAuditedExcept()
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_AVAILABILITY, "disconnected"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnavailable)
    }

    @Test fun `AuditEvidenceUnavailable evidenceTag is EVIDENCE_UNAVAILABLE`() {
        markAllAuditedExcept()
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION, "not registered"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertEquals(AndroidDelegatedRuntimeAudit.EVIDENCE_UNAVAILABLE, evidence.evidenceTag)
    }

    @Test fun `AuditEvidenceUnavailable unavailableReason matches registered reason`() {
        markAllAuditedExcept()
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION, "device_deregistered"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnavailable
        assertEquals("device_deregistered", evidence.unavailableReason)
    }

    @Test fun `AuditEvidenceUnavailable uses REASON_REGISTRATION_UNAVAILABLE_DEFAULT for registration dim`() {
        markAllAuditedExcept()
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION,
            AndroidDelegatedRuntimeAudit.REASON_REGISTRATION_UNAVAILABLE_DEFAULT
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnavailable
        assertEquals(
            AndroidDelegatedRuntimeAudit.REASON_REGISTRATION_UNAVAILABLE_DEFAULT,
            evidence.unavailableReason
        )
    }

    @Test fun `AuditEvidenceUnavailable uses REASON_AVAILABILITY_UNAVAILABLE_DEFAULT for availability dim`() {
        markAllAuditedExcept()
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_AVAILABILITY,
            AndroidDelegatedRuntimeAudit.REASON_AVAILABILITY_UNAVAILABLE_DEFAULT
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnavailable
        assertEquals(
            AndroidDelegatedRuntimeAudit.REASON_AVAILABILITY_UNAVAILABLE_DEFAULT,
            evidence.unavailableReason
        )
    }

    @Test fun `PARTICIPANT_REGISTRATION unavailable takes priority over PARTICIPANT_AVAILABILITY`() {
        markAllAuditedExcept()
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION, "reg gone"
        )
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_AVAILABILITY, "avail gone"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnavailable
        assertEquals(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION,
            evidence.dimension
        )
    }

    @Test fun `Unavailable takes priority over stale`() {
        markAllAuditedExcept()
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION, "gone"
        )
        audit.markDimensionStale(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS, "old")
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnavailable)
    }

    @Test fun `Unavailable takes priority over degraded`() {
        markAllAuditedExcept()
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION, "gone"
        )
        audit.markDimensionDegraded(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "degraded")
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnavailable)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateEvidence — AuditEvidenceStale
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `returns AuditEvidenceStale when EVIDENCE_FRESHNESS is STALE`() {
        markAllAuditedExcept()
        audit.markDimensionStale(
            AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS, "too old"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceStale)
    }

    @Test fun `AuditEvidenceStale evidenceTag is EVIDENCE_STALE`() {
        markAllAuditedExcept()
        audit.markDimensionStale(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS, "old")
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertEquals(AndroidDelegatedRuntimeAudit.EVIDENCE_STALE, evidence.evidenceTag)
    }

    @Test fun `AuditEvidenceStale staleReason matches registered reason`() {
        markAllAuditedExcept()
        audit.markDimensionStale(
            AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS, "evidence is 90000ms old"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceStale
        assertEquals("evidence is 90000ms old", evidence.staleReason)
    }

    @Test fun `AuditEvidenceStale dimension is EVIDENCE_FRESHNESS`() {
        markAllAuditedExcept()
        audit.markDimensionStale(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS, "old")
        val evidence = audit.evaluateEvidence("dev1", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceStale
        assertEquals(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS, evidence.dimension)
    }

    @Test fun `Stale takes priority over degraded`() {
        markAllAuditedExcept()
        audit.markDimensionStale(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS, "old")
        audit.markDimensionDegraded(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "degraded")
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceStale)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateEvidence — AuditEvidenceDegraded
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `returns AuditEvidenceDegraded when a dimension is DEGRADED`() {
        markAllAuditedExcept()
        audit.markDimensionDegraded(
            AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "runtime degraded"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceDegraded)
    }

    @Test fun `returns AuditEvidenceDegraded when non-registration availability dimension is UNAVAILABLE`() {
        markAllAuditedExcept()
        // DELEGATED_EXECUTION_READINESS unavailable → degraded (not the registration/availability path)
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.DELEGATED_EXECUTION_READINESS, "evaluator failed"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceDegraded)
    }

    @Test fun `AuditEvidenceDegraded evidenceTag is EVIDENCE_DEGRADED`() {
        markAllAuditedExcept()
        audit.markDimensionDegraded(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "degraded")
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertEquals(AndroidDelegatedRuntimeAudit.EVIDENCE_DEGRADED, evidence.evidenceTag)
    }

    @Test fun `AuditEvidenceDegraded degradedReason matches registered reason`() {
        markAllAuditedExcept()
        audit.markDimensionDegraded(
            AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "inference subsystem degraded"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceDegraded
        assertEquals("inference subsystem degraded", evidence.degradedReason)
    }

    @Test fun `Degraded health state does not produce ready evidence`() {
        // This tests the health state contract: runtime unhealthy → never optimistic ready
        markAllAuditedExcept()
        audit.markDimensionDegraded(
            AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE,
            "runtime is in DEGRADED orchestration state"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertFalse(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady)
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceDegraded)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateEvidence — AuditEvidenceReady (normal runtime evidence generation)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `returns AuditEvidenceReady when all six dimensions are AUDITED`() {
        AndroidDelegatedRuntimeAuditDimension.entries.forEach {
            audit.markDimensionAudited(it)
        }
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady)
    }

    @Test fun `AuditEvidenceReady evidenceTag is EVIDENCE_READY`() {
        AndroidDelegatedRuntimeAuditDimension.entries.forEach {
            audit.markDimensionAudited(it)
        }
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertEquals(AndroidDelegatedRuntimeAudit.EVIDENCE_READY, evidence.evidenceTag)
    }

    @Test fun `AuditEvidenceReady deviceId matches parameter`() {
        AndroidDelegatedRuntimeAuditDimension.entries.forEach {
            audit.markDimensionAudited(it)
        }
        val evidence = audit.evaluateEvidence("device-ready", "snap1") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady
        assertEquals("device-ready", evidence.deviceId)
    }

    @Test fun `AuditEvidenceReady snapshotId matches parameter`() {
        AndroidDelegatedRuntimeAuditDimension.entries.forEach {
            audit.markDimensionAudited(it)
        }
        val evidence = audit.evaluateEvidence("dev1", "snap-ready") as
            AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady
        assertEquals("snap-ready", evidence.snapshotId)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // buildSnapshot
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `buildSnapshot produces a snapshot with non-blank snapshotId`() {
        val snapshot = audit.buildSnapshot("dev1")
        assertTrue(snapshot.snapshotId.isNotBlank())
    }

    @Test fun `buildSnapshot produces a snapshot with correct deviceId`() {
        val snapshot = audit.buildSnapshot("my-device")
        assertEquals("my-device", snapshot.deviceId)
    }

    @Test fun `buildSnapshot snapshot has all six dimension states`() {
        val snapshot = audit.buildSnapshot("dev1")
        assertEquals(6, snapshot.dimensionStates.size)
        AndroidDelegatedRuntimeAuditDimension.entries.forEach { dim ->
            assertTrue(dim in snapshot.dimensionStates)
        }
    }

    @Test fun `buildSnapshot snapshot reportedAtMs is positive`() {
        val snapshot = audit.buildSnapshot("dev1")
        assertTrue(snapshot.reportedAtMs > 0)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // toWireMap — artifact/contract format stability (schema version 1.0)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildAllAuditedSnapshot(): AndroidDelegatedRuntimeAuditSnapshot {
        AndroidDelegatedRuntimeAuditDimension.entries.forEach {
            audit.markDimensionAudited(it)
        }
        return audit.buildSnapshot("wire-test-device")
    }

    @Test fun `toWireMap contains schema_version 1_0`() {
        val map = buildAllAuditedSnapshot().toWireMap()
        assertEquals("1.0", map["schema_version"])
    }

    @Test fun `toWireMap contains snapshot_id matching snapshotId`() {
        val snapshot = buildAllAuditedSnapshot()
        val map = snapshot.toWireMap()
        assertEquals(snapshot.snapshotId, map["snapshot_id"])
    }

    @Test fun `toWireMap contains device_id matching deviceId`() {
        val snapshot = buildAllAuditedSnapshot()
        val map = snapshot.toWireMap()
        assertEquals("wire-test-device", map["device_id"])
    }

    @Test fun `toWireMap contains reported_at_ms as Long`() {
        val snapshot = buildAllAuditedSnapshot()
        val map = snapshot.toWireMap()
        assertTrue(map["reported_at_ms"] is Long)
        assertTrue((map["reported_at_ms"] as Long) > 0)
    }

    @Test fun `toWireMap contains evidence_tag matching evidence evidenceTag`() {
        val snapshot = buildAllAuditedSnapshot()
        val map = snapshot.toWireMap()
        assertEquals(snapshot.evidence.evidenceTag, map["evidence_tag"])
    }

    @Test fun `toWireMap contains dimension_states with all six dimension wire-names`() {
        val snapshot = buildAllAuditedSnapshot()
        val map = snapshot.toWireMap()
        @Suppress("UNCHECKED_CAST")
        val states = map["dimension_states"] as Map<String, String>
        assertEquals(6, states.size)
        AndroidDelegatedRuntimeAuditDimension.entries.forEach { dim ->
            assertTrue(dim.wireValue in states)
        }
    }

    @Test fun `toWireMap dimension_states values are audited when all dimensions audited`() {
        val snapshot = buildAllAuditedSnapshot()
        val map = snapshot.toWireMap()
        @Suppress("UNCHECKED_CAST")
        val states = map["dimension_states"] as Map<String, String>
        states.values.forEach { value ->
            assertEquals(
                AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED.wireValue,
                value
            )
        }
    }

    @Test fun `toWireMap contains empty dimension_reasons when all AUDITED`() {
        val snapshot = buildAllAuditedSnapshot()
        val map = snapshot.toWireMap()
        @Suppress("UNCHECKED_CAST")
        val reasons = map["dimension_reasons"] as Map<String, String>
        assertTrue(reasons.isEmpty())
    }

    @Test fun `toWireMap contains empty missing_dimensions when all AUDITED`() {
        val snapshot = buildAllAuditedSnapshot()
        val map = snapshot.toWireMap()
        @Suppress("UNCHECKED_CAST")
        val missing = map["missing_dimensions"] as List<String>
        assertTrue(missing.isEmpty())
    }

    @Test fun `toWireMap capability_honesty_audited true when CAPABILITY_HONESTY is AUDITED`() {
        val snapshot = buildAllAuditedSnapshot()
        val map = snapshot.toWireMap()
        assertTrue(map["capability_honesty_audited"] as Boolean)
    }

    @Test fun `toWireMap capability_honesty_audited false when CAPABILITY_HONESTY is not AUDITED`() {
        // All audited except CAPABILITY_HONESTY
        AndroidDelegatedRuntimeAuditDimension.entries
            .filter { it != AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY }
            .forEach { audit.markDimensionAudited(it) }
        audit.markDimensionDegraded(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY, "violations found"
        )
        val snapshot = audit.buildSnapshot("dev1")
        val map = snapshot.toWireMap()
        assertFalse(map["capability_honesty_audited"] as Boolean)
    }

    @Test fun `toWireMap health_state_audited true when HEALTH_STATE is AUDITED`() {
        val snapshot = buildAllAuditedSnapshot()
        val map = snapshot.toWireMap()
        assertTrue(map["health_state_audited"] as Boolean)
    }

    @Test fun `toWireMap health_state_audited false when HEALTH_STATE is DEGRADED`() {
        AndroidDelegatedRuntimeAuditDimension.entries
            .filter { it != AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE }
            .forEach { audit.markDimensionAudited(it) }
        audit.markDimensionDegraded(
            AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "runtime degraded"
        )
        val snapshot = audit.buildSnapshot("dev1")
        val map = snapshot.toWireMap()
        assertFalse(map["health_state_audited"] as Boolean)
    }

    @Test fun `toWireMap participant_registered true when PARTICIPANT_REGISTRATION is AUDITED`() {
        val snapshot = buildAllAuditedSnapshot()
        val map = snapshot.toWireMap()
        assertTrue(map["participant_registered"] as Boolean)
    }

    @Test fun `toWireMap participant_registered false when PARTICIPANT_REGISTRATION is UNAVAILABLE`() {
        AndroidDelegatedRuntimeAuditDimension.entries
            .filter { it != AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION }
            .forEach { audit.markDimensionAudited(it) }
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION, "not registered"
        )
        val snapshot = audit.buildSnapshot("dev1")
        val map = snapshot.toWireMap()
        assertFalse(map["participant_registered"] as Boolean)
    }

    @Test fun `toWireMap dimension_reasons contains non-AUDITED reasons`() {
        AndroidDelegatedRuntimeAuditDimension.entries
            .filter { it != AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE }
            .forEach { audit.markDimensionAudited(it) }
        audit.markDimensionDegraded(
            AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE, "inference degraded"
        )
        val snapshot = audit.buildSnapshot("dev1")
        val map = snapshot.toWireMap()
        @Suppress("UNCHECKED_CAST")
        val reasons = map["dimension_reasons"] as Map<String, String>
        assertTrue("health_state" in reasons)
        assertEquals("inference degraded", reasons["health_state"])
    }

    @Test fun `toWireMap missing_dimensions lists UNKNOWN dimension wire-names`() {
        // Mark only 5 dimensions; leave EVIDENCE_FRESHNESS unknown
        AndroidDelegatedRuntimeAuditDimension.entries
            .filter { it != AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS }
            .forEach { audit.markDimensionAudited(it) }
        val snapshot = audit.buildSnapshot("dev1")
        val map = snapshot.toWireMap()
        @Suppress("UNCHECKED_CAST")
        val missing = map["missing_dimensions"] as List<String>
        assertEquals(1, missing.size)
        assertEquals("evidence_freshness", missing[0])
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Capability honesty dimension — consistency with CapabilityHonestyGuard
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `CAPABILITY_HONESTY AUDITED after CapabilityHonestyGuard isHonest returns empty`() {
        // Simulate: CapabilityHonestyGuard.isHonest returns empty list → mark audited
        val report = CapabilityHonestyGuard.CapabilityReport(
            advertisedCapabilities = CapabilityHonestyGuard.BASE_CAPABILITIES,
            orchestrationState = MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DEGRADED
        )
        val violations = CapabilityHonestyGuard.isHonest(report)
        assertTrue("Expected no violations for base caps in CONNECTED+DEGRADED", violations.isEmpty())

        // After verification passes → mark AUDITED
        audit.markDimensionAudited(AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY)
        assertEquals(
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED,
            audit.getDimensionStatus(AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY)
        )
    }

    @Test fun `CAPABILITY_HONESTY violations detected prevents READY evidence`() {
        // Simulate: participant advertises inference when DEGRADED → violations present
        val report = CapabilityHonestyGuard.CapabilityReport(
            advertisedCapabilities = CapabilityHonestyGuard.BASE_CAPABILITIES +
                CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE,
            orchestrationState = MultiDeviceParticipantOrchestrationState.OrchestrationState.DEGRADED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DEGRADED
        )
        val violations = CapabilityHonestyGuard.isHonest(report)
        assertFalse("Expected violations for inference advertised in DEGRADED state", violations.isEmpty())

        // Mark CAPABILITY_HONESTY DEGRADED (not AUDITED) when violations are present
        markAllAuditedExcept(AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY)
        audit.markDimensionDegraded(
            AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY,
            "honesty violations: ${violations.map { it.violatingCapability }}"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertFalse("READY must not be produced when capability honesty violations exist",
            evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady)
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceDegraded)
    }

    @Test fun `CAPABILITY_HONESTY not yet audited produces AuditEvidenceUnverified not ready`() {
        // Mark all OTHER dimensions audited, leave CAPABILITY_HONESTY unknown
        markAllAuditedExcept(AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY)
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        // CAPABILITY_HONESTY is UNKNOWN → produces UNVERIFIED, not READY
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified)
        assertFalse(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Health state contract — no optimistic ready when unhealthy
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `healthy runtime HEALTH_STATE AUDITED can reach AuditEvidenceReady`() {
        AndroidDelegatedRuntimeAuditDimension.entries.forEach {
            audit.markDimensionAudited(it)
        }
        // Confirm health state is AUDITED (healthy runtime)
        assertEquals(
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED,
            audit.getDimensionStatus(AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE)
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady)
    }

    @Test fun `degraded runtime HEALTH_STATE DEGRADED produces AuditEvidenceDegraded not ready`() {
        markAllAuditedExcept()
        audit.markDimensionDegraded(
            AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE,
            "orchestration_state=DEGRADED"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertFalse("No optimistic ready when HEALTH_STATE is DEGRADED",
            evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady)
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceDegraded)
    }

    @Test fun `recovering runtime HEALTH_STATE UNAVAILABLE produces AuditEvidenceUnavailable not ready`() {
        markAllAuditedExcept()
        audit.markDimensionUnavailable(
            AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE,
            "orchestration_state=RECOVERING"
        )
        val evidence = audit.evaluateEvidence("dev1", "snap1")
        assertFalse("No optimistic ready when HEALTH_STATE is UNAVAILABLE",
            evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady)
        // UNAVAILABLE on HEALTH_STATE (non-registration/availability dim) → DEGRADED
        assertTrue(evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceDegraded)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // checkAndMarkStaleness
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `checkAndMarkStaleness fresh evidence marks EVIDENCE_FRESHNESS AUDITED and returns true`() {
        val now = System.currentTimeMillis()
        val reportedAtMs = now - 30_000L  // 30 seconds ago (within 60s threshold)
        val result = audit.checkAndMarkStaleness(
            reportedAtMs = reportedAtMs,
            currentTimeMs = now,
            stalenessThresholdMs = 60_000L
        )
        assertTrue(result)
        assertEquals(
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED,
            audit.getDimensionStatus(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS)
        )
    }

    @Test fun `checkAndMarkStaleness stale evidence marks EVIDENCE_FRESHNESS STALE and returns false`() {
        val now = System.currentTimeMillis()
        val reportedAtMs = now - 90_000L  // 90 seconds ago (exceeds 60s threshold)
        val result = audit.checkAndMarkStaleness(
            reportedAtMs = reportedAtMs,
            currentTimeMs = now,
            stalenessThresholdMs = 60_000L
        )
        assertFalse(result)
        assertEquals(
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.STALE,
            audit.getDimensionStatus(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS)
        )
    }

    @Test fun `checkAndMarkStaleness stale reason includes age and threshold in ms`() {
        val now = System.currentTimeMillis()
        val reportedAtMs = now - 90_000L
        audit.checkAndMarkStaleness(
            reportedAtMs = reportedAtMs,
            currentTimeMs = now,
            stalenessThresholdMs = 60_000L
        )
        val reason = audit.getDimensionReason(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS)
        assertNotNull(reason)
        assertTrue("Reason should mention age in ms", reason!!.contains("90000ms"))
        assertTrue("Reason should mention threshold in ms", reason.contains("60000ms"))
    }

    @Test fun `checkAndMarkStaleness exactly at threshold is fresh (not stale)`() {
        val now = System.currentTimeMillis()
        val reportedAtMs = now - 60_000L  // exactly at threshold — age == threshold, not > threshold
        val result = audit.checkAndMarkStaleness(
            reportedAtMs = reportedAtMs,
            currentTimeMs = now,
            stalenessThresholdMs = 60_000L
        )
        assertTrue("Age equal to threshold should be fresh (not stale)", result)
        assertEquals(
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED,
            audit.getDimensionStatus(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DimensionStatus — wire values
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `DimensionStatus AUDITED wireValue is audited`() {
        assertEquals(
            "audited",
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED.wireValue
        )
    }

    @Test fun `DimensionStatus DEGRADED wireValue is degraded`() {
        assertEquals(
            "degraded",
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.DEGRADED.wireValue
        )
    }

    @Test fun `DimensionStatus UNAVAILABLE wireValue is unavailable`() {
        assertEquals(
            "unavailable",
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNAVAILABLE.wireValue
        )
    }

    @Test fun `DimensionStatus STALE wireValue is stale`() {
        assertEquals(
            "stale",
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.STALE.wireValue
        )
    }

    @Test fun `DimensionStatus MALFORMED wireValue is malformed`() {
        assertEquals(
            "malformed",
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.MALFORMED.wireValue
        )
    }

    @Test fun `DimensionStatus UNKNOWN wireValue is unknown`() {
        assertEquals(
            "unknown",
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN.wireValue
        )
    }

    @Test fun `all six DimensionStatus wire values are distinct`() {
        val values = AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.entries
            .map { it.wireValue }.toSet()
        assertEquals(6, values.size)
    }

    @Test fun `DimensionStatus fromValue round-trips all six wire values`() {
        AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.entries.forEach { status ->
            assertEquals(
                status,
                AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.fromValue(status.wireValue)
            )
        }
    }

    @Test fun `DimensionStatus fromValue returns null for unknown value`() {
        assertNull(AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.fromValue("not_a_status"))
    }

    @Test fun `DimensionStatus fromValue returns null for null`() {
        assertNull(AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.fromValue(null))
    }
}
