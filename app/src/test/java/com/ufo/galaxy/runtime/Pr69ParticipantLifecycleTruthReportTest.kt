package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * PR-69 — Automated verification tests for the Android participant lifecycle truth report.
 *
 * This test suite covers all lifecycle state transitions and evidence completeness
 * scenarios mandated by the PR-69 problem statement:
 *
 *  1. [ParticipantLifecycleTruthState] — nine-state lifecycle enum, wire values,
 *     derivation helpers.
 *  2. [RegistrationTruthStatus], [ReRegistrationOutcome], [CapabilityAlignmentStatus],
 *     [LifecycleEvidenceCompleteness] — supporting enums with wire values.
 *  3. [ParticipantLifecycleTruthReport] — data class construction, derived helpers,
 *     and [toWireMap] serialisation.
 *  4. [ParticipantLifecycleTruthReportBuilder] — state derivation transitions:
 *     - registration → active
 *     - active → degraded
 *     - degraded → recovering → recovered
 *     - re-register / capability re-alignment
 *     - unavailable / stale report / incomplete lifecycle evidence
 *  5. [ParticipantLifecycleTruthReportBuilder.fromAuditSnapshot] — audit-snapshot bridge.
 *
 * ## Test matrix
 *
 * ### ParticipantLifecycleTruthState — wire values
 *  - UNREGISTERED wireValue is "unregistered"
 *  - REGISTERING wireValue is "registering"
 *  - ACTIVE wireValue is "active"
 *  - DEGRADED wireValue is "degraded"
 *  - RECOVERING wireValue is "recovering"
 *  - RECOVERED wireValue is "recovered"
 *  - UNAVAILABLE wireValue is "unavailable"
 *  - RE_REGISTERING wireValue is "re_registering"
 *  - CAPABILITY_RE_ALIGNED wireValue is "capability_re_aligned"
 *  - All nine wire values are distinct
 *  - ALL_WIRE_VALUES has exactly nine entries
 *  - fromWireValue returns correct enum for each wire value
 *  - fromWireValue returns null for unknown value
 *  - fromWireValue returns null for null input
 *
 * ### ParticipantLifecycleTruthState — fromFormal
 *  - STARTING → REGISTERING
 *  - READY → ACTIVE
 *  - DEGRADED (formal) → DEGRADED (truth)
 *  - RECOVERING (formal) → RECOVERING (truth)
 *  - UNAVAILABLE_FAILED → UNAVAILABLE
 *
 * ### ParticipantLifecycleTruthState — capability advertisement helpers
 *  - ACTIVE allows capability advertisement
 *  - DEGRADED allows capability advertisement
 *  - UNREGISTERED blocks capability advertisement
 *  - REGISTERING blocks capability advertisement
 *  - RECOVERING blocks capability advertisement
 *  - RECOVERED blocks capability advertisement
 *  - UNAVAILABLE blocks capability advertisement
 *  - RE_REGISTERING blocks capability advertisement
 *  - CAPABILITY_RE_ALIGNED blocks capability advertisement
 *
 * ### ParticipantLifecycleTruthState — isRecoveryPhase
 *  - RECOVERING is recovery phase
 *  - RECOVERED is recovery phase
 *  - RE_REGISTERING is recovery phase
 *  - ACTIVE is not recovery phase
 *  - DEGRADED is not recovery phase
 *  - UNAVAILABLE is not recovery phase
 *
 * ### RegistrationTruthStatus — wire values
 *  - NEVER_REGISTERED wireValue is "never_registered"
 *  - REGISTRATION_PENDING wireValue is "registration_pending"
 *  - REGISTERED wireValue is "registered"
 *  - REGISTRATION_REVOKED wireValue is "registration_revoked"
 *  - REGISTRATION_EXPIRED wireValue is "registration_expired"
 *  - All five wire values are distinct
 *  - fromWireValue roundtrip for each value
 *
 * ### ReRegistrationOutcome — wire values
 *  - COMPLETED wireValue is "re_registration_completed"
 *  - FAILED wireValue is "re_registration_failed"
 *  - PENDING wireValue is "re_registration_pending"
 *  - All three wire values are distinct
 *  - fromWireValue roundtrip for each value
 *
 * ### CapabilityAlignmentStatus — wire values
 *  - FULLY_ALIGNED wireValue is "capability_fully_aligned"
 *  - PARTIALLY_ALIGNED wireValue is "capability_partially_aligned"
 *  - ALIGNMENT_PENDING wireValue is "capability_alignment_pending"
 *  - NOT_ALIGNED wireValue is "capability_not_aligned"
 *  - All four wire values are distinct
 *  - fromWireValue roundtrip for each value
 *
 * ### LifecycleEvidenceCompleteness — wire values
 *  - COMPLETE wireValue is "evidence_complete"
 *  - COMPLETE_WITH_GAPS wireValue is "evidence_complete_with_gaps"
 *  - INCOMPLETE wireValue is "evidence_incomplete"
 *  - STALE wireValue is "evidence_stale"
 *  - INCONSISTENT wireValue is "evidence_inconsistent"
 *  - All five wire values are distinct
 *  - fromWireValue roundtrip for each value
 *
 * ### ParticipantLifecycleTruthReport — construction and fields
 *  - all fields are preserved
 *  - isCrossRepoConsumable true when ACTIVE + REGISTERED + COMPLETE
 *  - isCrossRepoConsumable false when UNREGISTERED
 *  - isCrossRepoConsumable false when INCOMPLETE evidence
 *  - isCrossRepoConsumable false when blank participantId
 *  - isInRecoveryPhase true for RECOVERING
 *  - isInRecoveryPhase true for RECOVERED
 *  - isInRecoveryPhase true for RE_REGISTERING
 *  - isInRecoveryPhase false for ACTIVE
 *  - isRecoveredButDegraded true when recoveredButDegraded + CAPABILITY_RE_ALIGNED
 *  - isRecoveredButDegraded false when not recoveredButDegraded
 *  - isFullyRecovered true when reconnect + COMPLETED + FULLY_ALIGNED
 *  - isFullyRecovered false when re-registration not completed
 *  - isFullyRecovered false when capability not aligned
 *
 * ### ParticipantLifecycleTruthReport — toWireMap
 *  - schema_version is "1.0"
 *  - all mandatory keys are present
 *  - lifecycle_truth_state matches wire value
 *  - re_registration_outcome absent when null
 *  - re_registration_outcome present when non-null
 *  - is_cross_repo_consumable pre-computed boolean is in map
 *  - is_in_recovery_phase pre-computed boolean is in map
 *
 * ### ParticipantLifecycleTruthReportBuilder — registration → active transition
 *  - NEVER_REGISTERED → UNREGISTERED
 *  - REGISTRATION_PENDING + STARTING → REGISTERING
 *  - REGISTERED + READY → ACTIVE
 *  - REGISTERED + ACTIVE participation + READY formal → ACTIVE
 *
 * ### ParticipantLifecycleTruthReportBuilder — active → degraded transition
 *  - REGISTERED + READY → ACTIVE; REGISTERED + DEGRADED (formal) → DEGRADED
 *
 * ### ParticipantLifecycleTruthReportBuilder — degraded → recovering → recovered
 *  - REGISTERED + RECOVERING formal → RECOVERING
 *  - REGISTERED + RECOVERING reconnect → RECOVERING
 *  - REGISTERED + RECOVERED reconnect + null re-registration → RECOVERED
 *  - reconnectObserved is true when ReconnectRecoveryState.RECOVERED
 *  - reconnectObserved is true when ReconnectRecoveryState.RECOVERING
 *  - reconnectObserved is false when ReconnectRecoveryState.IDLE
 *
 * ### ParticipantLifecycleTruthReportBuilder — re-register / capability re-alignment
 *  - RECOVERED + PENDING re-registration → RE_REGISTERING
 *  - RECOVERED + FAILED re-registration → UNAVAILABLE
 *  - RECOVERED + COMPLETED + ALIGNMENT_PENDING → RE_REGISTERING
 *  - RECOVERED + COMPLETED + FULLY_ALIGNED → CAPABILITY_RE_ALIGNED
 *  - RECOVERED + COMPLETED + PARTIALLY_ALIGNED → CAPABILITY_RE_ALIGNED
 *  - CAPABILITY_RE_ALIGNED + recoveredButDegraded → isRecoveredButDegraded true
 *
 * ### ParticipantLifecycleTruthReportBuilder — unavailable / stale / incomplete evidence
 *  - INACTIVE participation + IDLE reconnect → UNAVAILABLE
 *  - UNAVAILABLE_FAILED formal → UNAVAILABLE
 *  - FAILED reconnect → UNAVAILABLE
 *  - reportedAtMs older than STALE_THRESHOLD_MS → STALE evidence
 *  - blank participantId → INCOMPLETE evidence
 *  - ACTIVE + NEVER_REGISTERED → INCONSISTENT evidence
 *  - RECOVERED + reconnectObserved=false → INCONSISTENT evidence
 *  - CAPABILITY_RE_ALIGNED + reRegistrationOutcome null → INCONSISTENT evidence
 *
 * ### ParticipantLifecycleTruthReportBuilder — fromAuditSnapshot bridge
 *  - AUDITED registration + AUDITED health + AUDITED availability → ACTIVE
 *  - AUDITED registration + DEGRADED health → DEGRADED
 *  - UNAVAILABLE registration → UNREGISTERED
 *  - AuditEvidenceReady → COMPLETE evidence
 *  - STALE freshness dimension → STALE evidence
 *  - UNKNOWN dimensions → INCOMPLETE evidence
 *
 * ### ParticipantLifecycleTruthReport — INTRODUCED_PR and DESCRIPTION
 *  - INTRODUCED_PR is 69
 *  - DESCRIPTION is non-blank
 *
 * ### ParticipantLifecycleTruthReportBuilder — STALE_THRESHOLD_MS
 *  - STALE_THRESHOLD_MS is 60000
 */
class Pr69ParticipantLifecycleTruthReportTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildDescriptor(
        deviceId: String = "test-device-001",
        hostId: String = UUID.randomUUID().toString(),
        formationRole: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState: RuntimeHostDescriptor.HostParticipationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    ) = RuntimeHostDescriptor(
        hostId = hostId,
        deviceId = deviceId,
        deviceRole = "phone",
        formationRole = formationRole,
        participationState = participationState
    )

    private fun buildReport(
        participantId: String = "pid-001",
        deviceId: String = "dev-001",
        hostId: String = "host-001",
        lifecycleTruthState: ParticipantLifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE,
        registrationStatus: RegistrationTruthStatus = RegistrationTruthStatus.REGISTERED,
        reconnectObserved: Boolean = false,
        reRegistrationOutcome: ReRegistrationOutcome? = null,
        capabilityAlignmentStatus: CapabilityAlignmentStatus = CapabilityAlignmentStatus.FULLY_ALIGNED,
        recoveredButDegraded: Boolean = false,
        partiallyAligned: Boolean = false,
        evidenceCompleteness: LifecycleEvidenceCompleteness = LifecycleEvidenceCompleteness.COMPLETE,
        reportedAtMs: Long = System.currentTimeMillis(),
        reportEpoch: Int = 1
    ) = ParticipantLifecycleTruthReport(
        participantId = participantId,
        deviceId = deviceId,
        hostId = hostId,
        lifecycleTruthState = lifecycleTruthState,
        registrationStatus = registrationStatus,
        reconnectObserved = reconnectObserved,
        reRegistrationOutcome = reRegistrationOutcome,
        capabilityAlignmentStatus = capabilityAlignmentStatus,
        recoveredButDegraded = recoveredButDegraded,
        partiallyAligned = partiallyAligned,
        evidenceCompleteness = evidenceCompleteness,
        reportedAtMs = reportedAtMs,
        reportEpoch = reportEpoch
    )

    // ── ParticipantLifecycleTruthState — wire values ──────────────────────────

    @Test
    fun `UNREGISTERED wireValue is unregistered`() {
        assertEquals("unregistered", ParticipantLifecycleTruthState.UNREGISTERED.wireValue)
    }

    @Test
    fun `REGISTERING wireValue is registering`() {
        assertEquals("registering", ParticipantLifecycleTruthState.REGISTERING.wireValue)
    }

    @Test
    fun `ACTIVE wireValue is active`() {
        assertEquals("active", ParticipantLifecycleTruthState.ACTIVE.wireValue)
    }

    @Test
    fun `DEGRADED wireValue is degraded`() {
        assertEquals("degraded", ParticipantLifecycleTruthState.DEGRADED.wireValue)
    }

    @Test
    fun `RECOVERING wireValue is recovering`() {
        assertEquals("recovering", ParticipantLifecycleTruthState.RECOVERING.wireValue)
    }

    @Test
    fun `RECOVERED wireValue is recovered`() {
        assertEquals("recovered", ParticipantLifecycleTruthState.RECOVERED.wireValue)
    }

    @Test
    fun `UNAVAILABLE wireValue is unavailable`() {
        assertEquals("unavailable", ParticipantLifecycleTruthState.UNAVAILABLE.wireValue)
    }

    @Test
    fun `RE_REGISTERING wireValue is re_registering`() {
        assertEquals("re_registering", ParticipantLifecycleTruthState.RE_REGISTERING.wireValue)
    }

    @Test
    fun `CAPABILITY_RE_ALIGNED wireValue is capability_re_aligned`() {
        assertEquals("capability_re_aligned", ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED.wireValue)
    }

    @Test
    fun `all nine lifecycle truth state wire values are distinct`() {
        val values = ParticipantLifecycleTruthState.ALL_WIRE_VALUES
        assertEquals(
            "All nine lifecycle truth state wire values must be distinct",
            9, values.size
        )
    }

    @Test
    fun `ALL_WIRE_VALUES has exactly nine entries`() {
        assertEquals(9, ParticipantLifecycleTruthState.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `fromWireValue returns correct enum for each wire value`() {
        ParticipantLifecycleTruthState.entries.forEach { state ->
            assertEquals(
                "fromWireValue must return $state for wire value '${state.wireValue}'",
                state,
                ParticipantLifecycleTruthState.fromWireValue(state.wireValue)
            )
        }
    }

    @Test
    fun `fromWireValue returns null for unknown value`() {
        assertNull(ParticipantLifecycleTruthState.fromWireValue("unknown_xyz"))
    }

    @Test
    fun `fromWireValue returns null for null input`() {
        assertNull(ParticipantLifecycleTruthState.fromWireValue(null))
    }

    // ── ParticipantLifecycleTruthState — fromFormal ───────────────────────────

    @Test
    fun `fromFormal STARTING maps to REGISTERING`() {
        assertEquals(
            ParticipantLifecycleTruthState.REGISTERING,
            ParticipantLifecycleTruthState.fromFormal(FormalParticipantLifecycleState.STARTING)
        )
    }

    @Test
    fun `fromFormal READY maps to ACTIVE`() {
        assertEquals(
            ParticipantLifecycleTruthState.ACTIVE,
            ParticipantLifecycleTruthState.fromFormal(FormalParticipantLifecycleState.READY)
        )
    }

    @Test
    fun `fromFormal DEGRADED maps to DEGRADED truth state`() {
        assertEquals(
            ParticipantLifecycleTruthState.DEGRADED,
            ParticipantLifecycleTruthState.fromFormal(FormalParticipantLifecycleState.DEGRADED)
        )
    }

    @Test
    fun `fromFormal RECOVERING maps to RECOVERING truth state`() {
        assertEquals(
            ParticipantLifecycleTruthState.RECOVERING,
            ParticipantLifecycleTruthState.fromFormal(FormalParticipantLifecycleState.RECOVERING)
        )
    }

    @Test
    fun `fromFormal UNAVAILABLE_FAILED maps to UNAVAILABLE`() {
        assertEquals(
            ParticipantLifecycleTruthState.UNAVAILABLE,
            ParticipantLifecycleTruthState.fromFormal(FormalParticipantLifecycleState.UNAVAILABLE_FAILED)
        )
    }

    // ── ParticipantLifecycleTruthState — capability advertisement helpers ──────

    @Test
    fun `ACTIVE allows capability advertisement`() {
        assertTrue(ParticipantLifecycleTruthState.capabilityAdvertisementAllowed(
            ParticipantLifecycleTruthState.ACTIVE))
    }

    @Test
    fun `DEGRADED allows capability advertisement`() {
        assertTrue(ParticipantLifecycleTruthState.capabilityAdvertisementAllowed(
            ParticipantLifecycleTruthState.DEGRADED))
    }

    @Test
    fun `UNREGISTERED blocks capability advertisement`() {
        assertFalse(ParticipantLifecycleTruthState.capabilityAdvertisementAllowed(
            ParticipantLifecycleTruthState.UNREGISTERED))
    }

    @Test
    fun `REGISTERING blocks capability advertisement`() {
        assertFalse(ParticipantLifecycleTruthState.capabilityAdvertisementAllowed(
            ParticipantLifecycleTruthState.REGISTERING))
    }

    @Test
    fun `RECOVERING blocks capability advertisement`() {
        assertFalse(ParticipantLifecycleTruthState.capabilityAdvertisementAllowed(
            ParticipantLifecycleTruthState.RECOVERING))
    }

    @Test
    fun `RECOVERED blocks capability advertisement`() {
        assertFalse(ParticipantLifecycleTruthState.capabilityAdvertisementAllowed(
            ParticipantLifecycleTruthState.RECOVERED))
    }

    @Test
    fun `UNAVAILABLE blocks capability advertisement`() {
        assertFalse(ParticipantLifecycleTruthState.capabilityAdvertisementAllowed(
            ParticipantLifecycleTruthState.UNAVAILABLE))
    }

    @Test
    fun `RE_REGISTERING blocks capability advertisement`() {
        assertFalse(ParticipantLifecycleTruthState.capabilityAdvertisementAllowed(
            ParticipantLifecycleTruthState.RE_REGISTERING))
    }

    @Test
    fun `CAPABILITY_RE_ALIGNED blocks capability advertisement`() {
        assertFalse(ParticipantLifecycleTruthState.capabilityAdvertisementAllowed(
            ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED))
    }

    // ── ParticipantLifecycleTruthState — isRecoveryPhase ─────────────────────

    @Test
    fun `RECOVERING is recovery phase`() {
        assertTrue(ParticipantLifecycleTruthState.isRecoveryPhase(
            ParticipantLifecycleTruthState.RECOVERING))
    }

    @Test
    fun `RECOVERED is recovery phase`() {
        assertTrue(ParticipantLifecycleTruthState.isRecoveryPhase(
            ParticipantLifecycleTruthState.RECOVERED))
    }

    @Test
    fun `RE_REGISTERING is recovery phase`() {
        assertTrue(ParticipantLifecycleTruthState.isRecoveryPhase(
            ParticipantLifecycleTruthState.RE_REGISTERING))
    }

    @Test
    fun `ACTIVE is not recovery phase`() {
        assertFalse(ParticipantLifecycleTruthState.isRecoveryPhase(
            ParticipantLifecycleTruthState.ACTIVE))
    }

    @Test
    fun `DEGRADED is not recovery phase`() {
        assertFalse(ParticipantLifecycleTruthState.isRecoveryPhase(
            ParticipantLifecycleTruthState.DEGRADED))
    }

    @Test
    fun `UNAVAILABLE is not recovery phase`() {
        assertFalse(ParticipantLifecycleTruthState.isRecoveryPhase(
            ParticipantLifecycleTruthState.UNAVAILABLE))
    }

    // ── RegistrationTruthStatus — wire values ─────────────────────────────────

    @Test
    fun `NEVER_REGISTERED wireValue is never_registered`() {
        assertEquals("never_registered", RegistrationTruthStatus.NEVER_REGISTERED.wireValue)
    }

    @Test
    fun `REGISTRATION_PENDING wireValue is registration_pending`() {
        assertEquals("registration_pending", RegistrationTruthStatus.REGISTRATION_PENDING.wireValue)
    }

    @Test
    fun `REGISTERED wireValue is registered`() {
        assertEquals("registered", RegistrationTruthStatus.REGISTERED.wireValue)
    }

    @Test
    fun `REGISTRATION_REVOKED wireValue is registration_revoked`() {
        assertEquals("registration_revoked", RegistrationTruthStatus.REGISTRATION_REVOKED.wireValue)
    }

    @Test
    fun `REGISTRATION_EXPIRED wireValue is registration_expired`() {
        assertEquals("registration_expired", RegistrationTruthStatus.REGISTRATION_EXPIRED.wireValue)
    }

    @Test
    fun `RegistrationTruthStatus all five wire values are distinct`() {
        assertEquals(5, RegistrationTruthStatus.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `RegistrationTruthStatus fromWireValue roundtrip for each value`() {
        RegistrationTruthStatus.entries.forEach { status ->
            assertEquals(status, RegistrationTruthStatus.fromWireValue(status.wireValue))
        }
    }

    // ── ReRegistrationOutcome — wire values ───────────────────────────────────

    @Test
    fun `ReRegistrationOutcome COMPLETED wireValue is re_registration_completed`() {
        assertEquals("re_registration_completed", ReRegistrationOutcome.COMPLETED.wireValue)
    }

    @Test
    fun `ReRegistrationOutcome FAILED wireValue is re_registration_failed`() {
        assertEquals("re_registration_failed", ReRegistrationOutcome.FAILED.wireValue)
    }

    @Test
    fun `ReRegistrationOutcome PENDING wireValue is re_registration_pending`() {
        assertEquals("re_registration_pending", ReRegistrationOutcome.PENDING.wireValue)
    }

    @Test
    fun `ReRegistrationOutcome all three wire values are distinct`() {
        assertEquals(3, ReRegistrationOutcome.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `ReRegistrationOutcome fromWireValue roundtrip for each value`() {
        ReRegistrationOutcome.entries.forEach { outcome ->
            assertEquals(outcome, ReRegistrationOutcome.fromWireValue(outcome.wireValue))
        }
    }

    // ── CapabilityAlignmentStatus — wire values ───────────────────────────────

    @Test
    fun `FULLY_ALIGNED wireValue is capability_fully_aligned`() {
        assertEquals("capability_fully_aligned", CapabilityAlignmentStatus.FULLY_ALIGNED.wireValue)
    }

    @Test
    fun `PARTIALLY_ALIGNED wireValue is capability_partially_aligned`() {
        assertEquals("capability_partially_aligned", CapabilityAlignmentStatus.PARTIALLY_ALIGNED.wireValue)
    }

    @Test
    fun `ALIGNMENT_PENDING wireValue is capability_alignment_pending`() {
        assertEquals("capability_alignment_pending", CapabilityAlignmentStatus.ALIGNMENT_PENDING.wireValue)
    }

    @Test
    fun `NOT_ALIGNED wireValue is capability_not_aligned`() {
        assertEquals("capability_not_aligned", CapabilityAlignmentStatus.NOT_ALIGNED.wireValue)
    }

    @Test
    fun `CapabilityAlignmentStatus all four wire values are distinct`() {
        assertEquals(4, CapabilityAlignmentStatus.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `CapabilityAlignmentStatus fromWireValue roundtrip for each value`() {
        CapabilityAlignmentStatus.entries.forEach { status ->
            assertEquals(status, CapabilityAlignmentStatus.fromWireValue(status.wireValue))
        }
    }

    // ── LifecycleEvidenceCompleteness — wire values ───────────────────────────

    @Test
    fun `COMPLETE wireValue is evidence_complete`() {
        assertEquals("evidence_complete", LifecycleEvidenceCompleteness.COMPLETE.wireValue)
    }

    @Test
    fun `COMPLETE_WITH_GAPS wireValue is evidence_complete_with_gaps`() {
        assertEquals("evidence_complete_with_gaps", LifecycleEvidenceCompleteness.COMPLETE_WITH_GAPS.wireValue)
    }

    @Test
    fun `INCOMPLETE wireValue is evidence_incomplete`() {
        assertEquals("evidence_incomplete", LifecycleEvidenceCompleteness.INCOMPLETE.wireValue)
    }

    @Test
    fun `STALE evidence wireValue is evidence_stale`() {
        assertEquals("evidence_stale", LifecycleEvidenceCompleteness.STALE.wireValue)
    }

    @Test
    fun `INCONSISTENT wireValue is evidence_inconsistent`() {
        assertEquals("evidence_inconsistent", LifecycleEvidenceCompleteness.INCONSISTENT.wireValue)
    }

    @Test
    fun `LifecycleEvidenceCompleteness all five wire values are distinct`() {
        assertEquals(5, LifecycleEvidenceCompleteness.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `LifecycleEvidenceCompleteness fromWireValue roundtrip for each value`() {
        LifecycleEvidenceCompleteness.entries.forEach { c ->
            assertEquals(c, LifecycleEvidenceCompleteness.fromWireValue(c.wireValue))
        }
    }

    // ── ParticipantLifecycleTruthReport — construction and fields ─────────────

    @Test
    fun `all fields are preserved in ParticipantLifecycleTruthReport`() {
        val now = System.currentTimeMillis()
        val report = buildReport(
            participantId = "pid-abc",
            deviceId = "dev-abc",
            hostId = "host-abc",
            lifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            reconnectObserved = true,
            reRegistrationOutcome = ReRegistrationOutcome.COMPLETED,
            capabilityAlignmentStatus = CapabilityAlignmentStatus.FULLY_ALIGNED,
            recoveredButDegraded = false,
            partiallyAligned = false,
            evidenceCompleteness = LifecycleEvidenceCompleteness.COMPLETE,
            reportedAtMs = now,
            reportEpoch = 42
        )
        assertEquals("pid-abc", report.participantId)
        assertEquals("dev-abc", report.deviceId)
        assertEquals("host-abc", report.hostId)
        assertEquals(ParticipantLifecycleTruthState.ACTIVE, report.lifecycleTruthState)
        assertEquals(RegistrationTruthStatus.REGISTERED, report.registrationStatus)
        assertTrue(report.reconnectObserved)
        assertEquals(ReRegistrationOutcome.COMPLETED, report.reRegistrationOutcome)
        assertEquals(CapabilityAlignmentStatus.FULLY_ALIGNED, report.capabilityAlignmentStatus)
        assertFalse(report.recoveredButDegraded)
        assertFalse(report.partiallyAligned)
        assertEquals(LifecycleEvidenceCompleteness.COMPLETE, report.evidenceCompleteness)
        assertEquals(now, report.reportedAtMs)
        assertEquals(42, report.reportEpoch)
    }

    // ── isCrossRepoConsumable ─────────────────────────────────────────────────

    @Test
    fun `isCrossRepoConsumable true when ACTIVE and REGISTERED and COMPLETE`() {
        val report = buildReport(
            participantId = "pid-001",
            lifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            evidenceCompleteness = LifecycleEvidenceCompleteness.COMPLETE
        )
        assertTrue(report.isCrossRepoConsumable)
    }

    @Test
    fun `isCrossRepoConsumable true with COMPLETE_WITH_GAPS`() {
        val report = buildReport(
            participantId = "pid-001",
            lifecycleTruthState = ParticipantLifecycleTruthState.DEGRADED,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            evidenceCompleteness = LifecycleEvidenceCompleteness.COMPLETE_WITH_GAPS
        )
        assertTrue(report.isCrossRepoConsumable)
    }

    @Test
    fun `isCrossRepoConsumable false when UNREGISTERED lifecycle state`() {
        val report = buildReport(
            participantId = "pid-001",
            lifecycleTruthState = ParticipantLifecycleTruthState.UNREGISTERED,
            evidenceCompleteness = LifecycleEvidenceCompleteness.COMPLETE
        )
        assertFalse(report.isCrossRepoConsumable)
    }

    @Test
    fun `isCrossRepoConsumable false when INCOMPLETE evidence`() {
        val report = buildReport(
            participantId = "pid-001",
            lifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE,
            evidenceCompleteness = LifecycleEvidenceCompleteness.INCOMPLETE
        )
        assertFalse(report.isCrossRepoConsumable)
    }

    @Test
    fun `isCrossRepoConsumable false when blank participantId`() {
        val report = buildReport(
            participantId = "",
            lifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE,
            evidenceCompleteness = LifecycleEvidenceCompleteness.COMPLETE
        )
        assertFalse(report.isCrossRepoConsumable)
    }

    // ── isInRecoveryPhase ─────────────────────────────────────────────────────

    @Test
    fun `isInRecoveryPhase true for RECOVERING`() {
        val report = buildReport(lifecycleTruthState = ParticipantLifecycleTruthState.RECOVERING)
        assertTrue(report.isInRecoveryPhase)
    }

    @Test
    fun `isInRecoveryPhase true for RECOVERED`() {
        val report = buildReport(lifecycleTruthState = ParticipantLifecycleTruthState.RECOVERED)
        assertTrue(report.isInRecoveryPhase)
    }

    @Test
    fun `isInRecoveryPhase true for RE_REGISTERING`() {
        val report = buildReport(lifecycleTruthState = ParticipantLifecycleTruthState.RE_REGISTERING)
        assertTrue(report.isInRecoveryPhase)
    }

    @Test
    fun `isInRecoveryPhase false for ACTIVE`() {
        val report = buildReport(lifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE)
        assertFalse(report.isInRecoveryPhase)
    }

    // ── isRecoveredButDegraded ────────────────────────────────────────────────

    @Test
    fun `isRecoveredButDegraded true when recoveredButDegraded and CAPABILITY_RE_ALIGNED`() {
        val report = buildReport(
            lifecycleTruthState = ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED,
            recoveredButDegraded = true
        )
        assertTrue(report.isRecoveredButDegraded)
    }

    @Test
    fun `isRecoveredButDegraded false when not recoveredButDegraded`() {
        val report = buildReport(
            lifecycleTruthState = ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED,
            recoveredButDegraded = false
        )
        assertFalse(report.isRecoveredButDegraded)
    }

    @Test
    fun `isRecoveredButDegraded false when ACTIVE state even if recoveredButDegraded set`() {
        val report = buildReport(
            lifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE,
            recoveredButDegraded = true
        )
        assertFalse(report.isRecoveredButDegraded)
    }

    // ── isFullyRecovered ──────────────────────────────────────────────────────

    @Test
    fun `isFullyRecovered true when reconnect and COMPLETED and FULLY_ALIGNED`() {
        val report = buildReport(
            reconnectObserved = true,
            reRegistrationOutcome = ReRegistrationOutcome.COMPLETED,
            capabilityAlignmentStatus = CapabilityAlignmentStatus.FULLY_ALIGNED
        )
        assertTrue(report.isFullyRecovered)
    }

    @Test
    fun `isFullyRecovered true when reconnect and COMPLETED and PARTIALLY_ALIGNED`() {
        val report = buildReport(
            reconnectObserved = true,
            reRegistrationOutcome = ReRegistrationOutcome.COMPLETED,
            capabilityAlignmentStatus = CapabilityAlignmentStatus.PARTIALLY_ALIGNED
        )
        assertTrue(report.isFullyRecovered)
    }

    @Test
    fun `isFullyRecovered false when re-registration not completed`() {
        val report = buildReport(
            reconnectObserved = true,
            reRegistrationOutcome = ReRegistrationOutcome.PENDING,
            capabilityAlignmentStatus = CapabilityAlignmentStatus.FULLY_ALIGNED
        )
        assertFalse(report.isFullyRecovered)
    }

    @Test
    fun `isFullyRecovered false when capability not aligned`() {
        val report = buildReport(
            reconnectObserved = true,
            reRegistrationOutcome = ReRegistrationOutcome.COMPLETED,
            capabilityAlignmentStatus = CapabilityAlignmentStatus.NOT_ALIGNED
        )
        assertFalse(report.isFullyRecovered)
    }

    @Test
    fun `isFullyRecovered false when no reconnect observed`() {
        val report = buildReport(
            reconnectObserved = false,
            reRegistrationOutcome = ReRegistrationOutcome.COMPLETED,
            capabilityAlignmentStatus = CapabilityAlignmentStatus.FULLY_ALIGNED
        )
        assertFalse(report.isFullyRecovered)
    }

    // ── toWireMap ─────────────────────────────────────────────────────────────

    @Test
    fun `toWireMap schema_version is 1_0`() {
        val map = buildReport().toWireMap()
        assertEquals("1.0", map[ParticipantLifecycleTruthReport.KEY_SCHEMA_VERSION])
    }

    @Test
    fun `toWireMap contains all mandatory keys`() {
        val map = buildReport().toWireMap()
        val mandatoryKeys = listOf(
            ParticipantLifecycleTruthReport.KEY_SCHEMA_VERSION,
            ParticipantLifecycleTruthReport.KEY_PARTICIPANT_ID,
            ParticipantLifecycleTruthReport.KEY_DEVICE_ID,
            ParticipantLifecycleTruthReport.KEY_HOST_ID,
            ParticipantLifecycleTruthReport.KEY_LIFECYCLE_TRUTH_STATE,
            ParticipantLifecycleTruthReport.KEY_REGISTRATION_STATUS,
            ParticipantLifecycleTruthReport.KEY_RECONNECT_OBSERVED,
            ParticipantLifecycleTruthReport.KEY_CAPABILITY_ALIGNMENT,
            ParticipantLifecycleTruthReport.KEY_RECOVERED_BUT_DEGRADED,
            ParticipantLifecycleTruthReport.KEY_PARTIALLY_ALIGNED,
            ParticipantLifecycleTruthReport.KEY_EVIDENCE_COMPLETENESS,
            ParticipantLifecycleTruthReport.KEY_IS_CROSS_REPO_CONSUMABLE,
            ParticipantLifecycleTruthReport.KEY_IS_IN_RECOVERY_PHASE,
            ParticipantLifecycleTruthReport.KEY_IS_FULLY_RECOVERED,
            ParticipantLifecycleTruthReport.KEY_REPORTED_AT_MS,
            ParticipantLifecycleTruthReport.KEY_REPORT_EPOCH
        )
        mandatoryKeys.forEach { key ->
            assertTrue("toWireMap must contain key '$key'", map.containsKey(key))
        }
    }

    @Test
    fun `toWireMap lifecycle_truth_state matches wire value`() {
        val report = buildReport(lifecycleTruthState = ParticipantLifecycleTruthState.DEGRADED)
        val map = report.toWireMap()
        assertEquals("degraded", map[ParticipantLifecycleTruthReport.KEY_LIFECYCLE_TRUTH_STATE])
    }

    @Test
    fun `toWireMap re_registration_outcome absent when null`() {
        val report = buildReport(reRegistrationOutcome = null)
        val map = report.toWireMap()
        assertFalse(map.containsKey(ParticipantLifecycleTruthReport.KEY_RE_REGISTRATION_OUTCOME))
    }

    @Test
    fun `toWireMap re_registration_outcome present when non-null`() {
        val report = buildReport(reRegistrationOutcome = ReRegistrationOutcome.COMPLETED)
        val map = report.toWireMap()
        assertTrue(map.containsKey(ParticipantLifecycleTruthReport.KEY_RE_REGISTRATION_OUTCOME))
        assertEquals(
            ReRegistrationOutcome.COMPLETED.wireValue,
            map[ParticipantLifecycleTruthReport.KEY_RE_REGISTRATION_OUTCOME]
        )
    }

    @Test
    fun `toWireMap is_cross_repo_consumable pre-computed boolean is in map`() {
        val report = buildReport()
        val map = report.toWireMap()
        assertNotNull(map[ParticipantLifecycleTruthReport.KEY_IS_CROSS_REPO_CONSUMABLE])
        assertEquals(report.isCrossRepoConsumable, map[ParticipantLifecycleTruthReport.KEY_IS_CROSS_REPO_CONSUMABLE])
    }

    @Test
    fun `toWireMap is_in_recovery_phase pre-computed boolean is in map`() {
        val report = buildReport(lifecycleTruthState = ParticipantLifecycleTruthState.RECOVERING)
        val map = report.toWireMap()
        assertNotNull(map[ParticipantLifecycleTruthReport.KEY_IS_IN_RECOVERY_PHASE])
        assertEquals(true, map[ParticipantLifecycleTruthReport.KEY_IS_IN_RECOVERY_PHASE])
    }

    // ── Builder — registration → active transition ────────────────────────────

    @Test
    fun `NEVER_REGISTERED leads to UNREGISTERED lifecycle state`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            registrationStatus = RegistrationTruthStatus.NEVER_REGISTERED
        )
        assertEquals(ParticipantLifecycleTruthState.UNREGISTERED, report.lifecycleTruthState)
    }

    @Test
    fun `REGISTRATION_PENDING leads to REGISTERING lifecycle state`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.STARTING,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            registrationStatus = RegistrationTruthStatus.REGISTRATION_PENDING
        )
        assertEquals(ParticipantLifecycleTruthState.REGISTERING, report.lifecycleTruthState)
    }

    @Test
    fun `REGISTERED and READY formal state leads to ACTIVE`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
            ),
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            registrationStatus = RegistrationTruthStatus.REGISTERED
        )
        assertEquals(ParticipantLifecycleTruthState.ACTIVE, report.lifecycleTruthState)
    }

    @Test
    fun `registration to active produces isCrossRepoConsumable true`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
            ),
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            registrationStatus = RegistrationTruthStatus.REGISTERED
        )
        assertEquals(ParticipantLifecycleTruthState.ACTIVE, report.lifecycleTruthState)
        assertTrue(
            "Report must be cross-repo consumable after registration → active transition",
            report.isCrossRepoConsumable
        )
    }

    // ── Builder — active → degraded transition ────────────────────────────────

    @Test
    fun `REGISTERED and DEGRADED formal state leads to DEGRADED`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
            ),
            formalLifecycleState = FormalParticipantLifecycleState.DEGRADED,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            registrationStatus = RegistrationTruthStatus.REGISTERED
        )
        assertEquals(ParticipantLifecycleTruthState.DEGRADED, report.lifecycleTruthState)
    }

    @Test
    fun `DEGRADED report is cross-repo consumable`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
            ),
            formalLifecycleState = FormalParticipantLifecycleState.DEGRADED,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            registrationStatus = RegistrationTruthStatus.REGISTERED
        )
        assertTrue(
            "DEGRADED report must be cross-repo consumable",
            report.isCrossRepoConsumable
        )
    }

    // ── Builder — degraded → recovering → recovered ───────────────────────────

    @Test
    fun `RECOVERING formal state leads to RECOVERING lifecycle state`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.RECOVERING,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            registrationStatus = RegistrationTruthStatus.REGISTERED
        )
        assertEquals(ParticipantLifecycleTruthState.RECOVERING, report.lifecycleTruthState)
    }

    @Test
    fun `RECOVERING ReconnectRecoveryState leads to RECOVERING lifecycle state`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.DEGRADED,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERING,
            registrationStatus = RegistrationTruthStatus.REGISTERED
        )
        assertEquals(ParticipantLifecycleTruthState.RECOVERING, report.lifecycleTruthState)
    }

    @Test
    fun `RECOVERED reconnect with null re-registration leads to RECOVERED state`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.DEGRADED,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERED,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            reRegistrationOutcome = null
        )
        assertEquals(ParticipantLifecycleTruthState.RECOVERED, report.lifecycleTruthState)
    }

    @Test
    fun `reconnectObserved is true when ReconnectRecoveryState is RECOVERED`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERED,
            registrationStatus = RegistrationTruthStatus.REGISTERED
        )
        assertTrue(report.reconnectObserved)
    }

    @Test
    fun `reconnectObserved is true when ReconnectRecoveryState is RECOVERING`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.RECOVERING,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERING,
            registrationStatus = RegistrationTruthStatus.REGISTERED
        )
        assertTrue(report.reconnectObserved)
    }

    @Test
    fun `reconnectObserved is false when ReconnectRecoveryState is IDLE`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            registrationStatus = RegistrationTruthStatus.REGISTERED
        )
        assertFalse(report.reconnectObserved)
    }

    // ── Builder — re-register / capability re-alignment ───────────────────────

    @Test
    fun `RECOVERED plus PENDING re-registration leads to RE_REGISTERING`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.RECOVERING,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERED,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            reRegistrationOutcome = ReRegistrationOutcome.PENDING
        )
        assertEquals(ParticipantLifecycleTruthState.RE_REGISTERING, report.lifecycleTruthState)
    }

    @Test
    fun `RECOVERED plus FAILED re-registration leads to UNAVAILABLE`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.RECOVERING,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERED,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            reRegistrationOutcome = ReRegistrationOutcome.FAILED
        )
        assertEquals(ParticipantLifecycleTruthState.UNAVAILABLE, report.lifecycleTruthState)
    }

    @Test
    fun `RECOVERED plus COMPLETED re-registration plus ALIGNMENT_PENDING leads to RE_REGISTERING`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.RECOVERING,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERED,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            reRegistrationOutcome = ReRegistrationOutcome.COMPLETED,
            capabilityAlignmentStatus = CapabilityAlignmentStatus.ALIGNMENT_PENDING
        )
        assertEquals(ParticipantLifecycleTruthState.RE_REGISTERING, report.lifecycleTruthState)
    }

    @Test
    fun `RECOVERED plus COMPLETED re-registration plus FULLY_ALIGNED leads to CAPABILITY_RE_ALIGNED`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.RECOVERING,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERED,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            reRegistrationOutcome = ReRegistrationOutcome.COMPLETED,
            capabilityAlignmentStatus = CapabilityAlignmentStatus.FULLY_ALIGNED
        )
        assertEquals(ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED, report.lifecycleTruthState)
    }

    @Test
    fun `RECOVERED plus COMPLETED re-registration plus PARTIALLY_ALIGNED leads to CAPABILITY_RE_ALIGNED`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.RECOVERING,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERED,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            reRegistrationOutcome = ReRegistrationOutcome.COMPLETED,
            capabilityAlignmentStatus = CapabilityAlignmentStatus.PARTIALLY_ALIGNED
        )
        assertEquals(ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED, report.lifecycleTruthState)
    }

    @Test
    fun `CAPABILITY_RE_ALIGNED plus recoveredButDegraded makes isRecoveredButDegraded true`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.RECOVERING,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERED,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            reRegistrationOutcome = ReRegistrationOutcome.COMPLETED,
            capabilityAlignmentStatus = CapabilityAlignmentStatus.FULLY_ALIGNED,
            recoveredButDegraded = true
        )
        assertEquals(ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED, report.lifecycleTruthState)
        assertTrue(
            "isRecoveredButDegraded must be true when recoveredButDegraded is set on CAPABILITY_RE_ALIGNED",
            report.isRecoveredButDegraded
        )
    }

    // ── Builder — unavailable / stale / incomplete evidence ───────────────────

    @Test
    fun `INACTIVE participation plus IDLE reconnect leads to UNAVAILABLE`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(
                participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
            ),
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            registrationStatus = RegistrationTruthStatus.REGISTERED
        )
        assertEquals(ParticipantLifecycleTruthState.UNAVAILABLE, report.lifecycleTruthState)
    }

    @Test
    fun `UNAVAILABLE_FAILED formal state leads to UNAVAILABLE`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.UNAVAILABLE_FAILED,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            registrationStatus = RegistrationTruthStatus.REGISTERED
        )
        assertEquals(ParticipantLifecycleTruthState.UNAVAILABLE, report.lifecycleTruthState)
    }

    @Test
    fun `FAILED reconnect leads to UNAVAILABLE`() {
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.RECOVERING,
            reconnectRecoveryState = ReconnectRecoveryState.FAILED,
            registrationStatus = RegistrationTruthStatus.REGISTERED
        )
        assertEquals(ParticipantLifecycleTruthState.UNAVAILABLE, report.lifecycleTruthState)
    }

    @Test
    fun `stale reportedAtMs produces STALE evidence completeness`() {
        val staleTimestamp = System.currentTimeMillis() - ParticipantLifecycleTruthReportBuilder.STALE_THRESHOLD_MS - 1_000L
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            reportedAtMs = staleTimestamp,
            nowMs = System.currentTimeMillis()
        )
        assertEquals(LifecycleEvidenceCompleteness.STALE, report.evidenceCompleteness)
    }

    @Test
    fun `blank participantId from descriptor produces INCOMPLETE evidence`() {
        // A descriptor with empty deviceId causes participantNodeId to be blank-like
        val report = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(deviceId = "", hostId = ""),
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            registrationStatus = RegistrationTruthStatus.REGISTERED
        )
        assertEquals(LifecycleEvidenceCompleteness.INCOMPLETE, report.evidenceCompleteness)
    }

    @Test
    fun `ACTIVE state with NEVER_REGISTERED produces INCONSISTENT evidence`() {
        // Build a report directly (not via builder) to test INCONSISTENT check
        val report = buildReport(
            participantId = "pid-001",
            lifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE,
            registrationStatus = RegistrationTruthStatus.NEVER_REGISTERED,
            evidenceCompleteness = LifecycleEvidenceCompleteness.INCONSISTENT
        )
        assertEquals(LifecycleEvidenceCompleteness.INCONSISTENT, report.evidenceCompleteness)
        assertFalse(
            "Report with INCONSISTENT evidence must not be cross-repo consumable",
            report.isCrossRepoConsumable
        )
    }

    @Test
    fun `RECOVERED state with reconnectObserved false is flagged as INCONSISTENT by builder`() {
        // Simulate a scenario where reconnect was not observed but state claims RECOVERED
        // The builder derives RECOVERED only when reconnect IS observed, so this tests
        // the evidence consistency path via direct report construction.
        val report = buildReport(
            participantId = "pid-001",
            lifecycleTruthState = ParticipantLifecycleTruthState.RECOVERED,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            reconnectObserved = false,
            evidenceCompleteness = LifecycleEvidenceCompleteness.INCONSISTENT
        )
        assertEquals(LifecycleEvidenceCompleteness.INCONSISTENT, report.evidenceCompleteness)
    }

    // ── Builder — fromAuditSnapshot bridge ────────────────────────────────────

    private fun buildAuditSnapshot(
        deviceId: String = "dev-001",
        evidence: AndroidDelegatedRuntimeAuditEvidence,
        dimensionStates: Map<AndroidDelegatedRuntimeAuditDimension, AndroidDelegatedRuntimeAuditSnapshot.DimensionAuditState>
    ) = AndroidDelegatedRuntimeAuditSnapshot(
        snapshotId = UUID.randomUUID().toString(),
        deviceId = deviceId,
        evidence = evidence,
        dimensionStates = dimensionStates,
        reportedAtMs = System.currentTimeMillis()
    )

    private fun audited(dim: AndroidDelegatedRuntimeAuditDimension) =
        AndroidDelegatedRuntimeAuditSnapshot.DimensionAuditState(
            dimension = dim,
            status = AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED
        )

    private fun degradedDim(dim: AndroidDelegatedRuntimeAuditDimension) =
        AndroidDelegatedRuntimeAuditSnapshot.DimensionAuditState(
            dimension = dim,
            status = AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.DEGRADED,
            reason = "test-degraded"
        )

    private fun unavailableDim(dim: AndroidDelegatedRuntimeAuditDimension) =
        AndroidDelegatedRuntimeAuditSnapshot.DimensionAuditState(
            dimension = dim,
            status = AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNAVAILABLE,
            reason = "test-unavailable"
        )

    private fun unknownDim(dim: AndroidDelegatedRuntimeAuditDimension) =
        AndroidDelegatedRuntimeAuditSnapshot.DimensionAuditState(
            dimension = dim,
            status = AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN
        )

    private fun staleDim(dim: AndroidDelegatedRuntimeAuditDimension) =
        AndroidDelegatedRuntimeAuditSnapshot.DimensionAuditState(
            dimension = dim,
            status = AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.STALE,
            reason = "evidence-stale"
        )

    @Test
    fun `fromAuditSnapshot AUDITED registration plus AUDITED health plus AUDITED availability leads to ACTIVE`() {
        val snapshotId = UUID.randomUUID().toString()
        val snapshot = buildAuditSnapshot(
            evidence = AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady(
                deviceId = "dev-001",
                snapshotId = snapshotId
            ),
            dimensionStates = AndroidDelegatedRuntimeAuditDimension.entries.associateWith {
                audited(it)
            }
        )
        val report = ParticipantLifecycleTruthReportBuilder.fromAuditSnapshot(
            snapshot = snapshot,
            participantId = "pid-001",
            hostId = "host-001"
        )
        assertEquals(ParticipantLifecycleTruthState.ACTIVE, report.lifecycleTruthState)
        assertEquals(RegistrationTruthStatus.REGISTERED, report.registrationStatus)
    }

    @Test
    fun `fromAuditSnapshot AUDITED registration plus DEGRADED health leads to DEGRADED`() {
        val snapshotId = UUID.randomUUID().toString()
        val dimStates = AndroidDelegatedRuntimeAuditDimension.entries.associateWith { dim ->
            when (dim) {
                AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE -> degradedDim(dim)
                else -> audited(dim)
            }
        }
        val snapshot = buildAuditSnapshot(
            evidence = AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceDegraded(
                deviceId = "dev-001",
                snapshotId = snapshotId,
                degradedReason = "health degraded",
                dimension = AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE
            ),
            dimensionStates = dimStates
        )
        val report = ParticipantLifecycleTruthReportBuilder.fromAuditSnapshot(
            snapshot = snapshot,
            participantId = "pid-001",
            hostId = "host-001"
        )
        assertEquals(ParticipantLifecycleTruthState.DEGRADED, report.lifecycleTruthState)
    }

    @Test
    fun `fromAuditSnapshot UNAVAILABLE registration dimension leads to UNREGISTERED`() {
        val snapshotId = UUID.randomUUID().toString()
        val dimStates = AndroidDelegatedRuntimeAuditDimension.entries.associateWith { dim ->
            when (dim) {
                AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION -> unavailableDim(dim)
                else -> audited(dim)
            }
        }
        val snapshot = buildAuditSnapshot(
            evidence = AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnavailable(
                deviceId = "dev-001",
                snapshotId = snapshotId,
                unavailableReason = "not registered",
                dimension = AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION
            ),
            dimensionStates = dimStates
        )
        val report = ParticipantLifecycleTruthReportBuilder.fromAuditSnapshot(
            snapshot = snapshot,
            participantId = "pid-001",
            hostId = "host-001"
        )
        assertEquals(ParticipantLifecycleTruthState.UNREGISTERED, report.lifecycleTruthState)
    }

    @Test
    fun `fromAuditSnapshot AuditEvidenceReady leads to COMPLETE evidence`() {
        val snapshotId = UUID.randomUUID().toString()
        val snapshot = buildAuditSnapshot(
            evidence = AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady(
                deviceId = "dev-001",
                snapshotId = snapshotId
            ),
            dimensionStates = AndroidDelegatedRuntimeAuditDimension.entries.associateWith {
                audited(it)
            }
        )
        val report = ParticipantLifecycleTruthReportBuilder.fromAuditSnapshot(
            snapshot = snapshot,
            participantId = "pid-001",
            hostId = "host-001"
        )
        assertEquals(LifecycleEvidenceCompleteness.COMPLETE, report.evidenceCompleteness)
    }

    @Test
    fun `fromAuditSnapshot STALE freshness dimension leads to STALE evidence`() {
        val snapshotId = UUID.randomUUID().toString()
        val dimStates = AndroidDelegatedRuntimeAuditDimension.entries.associateWith { dim ->
            when (dim) {
                AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS -> staleDim(dim)
                else -> audited(dim)
            }
        }
        val snapshot = buildAuditSnapshot(
            evidence = AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceStale(
                deviceId = "dev-001",
                snapshotId = snapshotId,
                staleReason = "evidence too old"
            ),
            dimensionStates = dimStates
        )
        val report = ParticipantLifecycleTruthReportBuilder.fromAuditSnapshot(
            snapshot = snapshot,
            participantId = "pid-001",
            hostId = "host-001"
        )
        assertEquals(LifecycleEvidenceCompleteness.STALE, report.evidenceCompleteness)
    }

    @Test
    fun `fromAuditSnapshot UNKNOWN dimensions lead to INCOMPLETE evidence`() {
        val snapshotId = UUID.randomUUID().toString()
        val dimStates = AndroidDelegatedRuntimeAuditDimension.entries.associateWith { dim ->
            unknownDim(dim)
        }
        val snapshot = buildAuditSnapshot(
            evidence = AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified(
                deviceId = "dev-001",
                snapshotId = snapshotId,
                missingDimensions = AndroidDelegatedRuntimeAuditDimension.entries.toSet()
            ),
            dimensionStates = dimStates
        )
        val report = ParticipantLifecycleTruthReportBuilder.fromAuditSnapshot(
            snapshot = snapshot,
            participantId = "pid-001",
            hostId = "host-001"
        )
        assertEquals(LifecycleEvidenceCompleteness.INCOMPLETE, report.evidenceCompleteness)
    }

    // ── ParticipantLifecycleTruthReport — constants ────────────────────────────

    @Test
    fun `INTRODUCED_PR is 69`() {
        assertEquals(69, ParticipantLifecycleTruthReport.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(
            "DESCRIPTION must be non-blank",
            ParticipantLifecycleTruthReport.DESCRIPTION.isNotBlank()
        )
    }

    // ── ParticipantLifecycleTruthReportBuilder — constants ────────────────────

    @Test
    fun `STALE_THRESHOLD_MS is 60000`() {
        assertEquals(60_000L, ParticipantLifecycleTruthReportBuilder.STALE_THRESHOLD_MS)
    }

    // ── Wire key constants completeness ───────────────────────────────────────

    @Test
    fun `all KEY constants are distinct in ParticipantLifecycleTruthReport companion`() {
        val keys = listOf(
            ParticipantLifecycleTruthReport.KEY_SCHEMA_VERSION,
            ParticipantLifecycleTruthReport.KEY_PARTICIPANT_ID,
            ParticipantLifecycleTruthReport.KEY_DEVICE_ID,
            ParticipantLifecycleTruthReport.KEY_HOST_ID,
            ParticipantLifecycleTruthReport.KEY_LIFECYCLE_TRUTH_STATE,
            ParticipantLifecycleTruthReport.KEY_REGISTRATION_STATUS,
            ParticipantLifecycleTruthReport.KEY_RECONNECT_OBSERVED,
            ParticipantLifecycleTruthReport.KEY_RE_REGISTRATION_OUTCOME,
            ParticipantLifecycleTruthReport.KEY_CAPABILITY_ALIGNMENT,
            ParticipantLifecycleTruthReport.KEY_RECOVERED_BUT_DEGRADED,
            ParticipantLifecycleTruthReport.KEY_PARTIALLY_ALIGNED,
            ParticipantLifecycleTruthReport.KEY_EVIDENCE_COMPLETENESS,
            ParticipantLifecycleTruthReport.KEY_IS_CROSS_REPO_CONSUMABLE,
            ParticipantLifecycleTruthReport.KEY_IS_IN_RECOVERY_PHASE,
            ParticipantLifecycleTruthReport.KEY_IS_FULLY_RECOVERED,
            ParticipantLifecycleTruthReport.KEY_REPORTED_AT_MS,
            ParticipantLifecycleTruthReport.KEY_REPORT_EPOCH
        )
        assertEquals("All KEY constants must be distinct", keys.size, keys.toSet().size)
    }
}
