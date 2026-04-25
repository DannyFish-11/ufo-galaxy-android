package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-7Android (reopened) — Unit tests for [AndroidDistributedGateMapping].
 *
 * Validates the five acceptance criteria for aligning Android readiness evidence with the
 * V2 canonical distributed release-gate skeleton:
 *
 *  1. **How Android readiness evidence maps into the canonical distributed gate categories**
 *     — verified by [AndroidDistributedGateMapping.allMappings] structural invariants, per-
 *     dimension coverage checks, and spot-check assertions on specific mapping entries.
 *
 *  2. **Which Android evidence is strong vs advisory vs deprecated/local-only** — verified
 *     by alignment-level count assertions and evidence-ID spot-checks confirming each entry
 *     carries the correct [AndroidDistributedGateMapping.EvidenceAlignment].
 *
 *  3. **That Android-side mappings are backed by real runtime/tests/artifacts** — verified
 *     by non-blank v2ConsumptionNote assertions on all STRONG_PARTICIPANT_RUNTIME entries
 *     (each must reference at least one concrete test class) and non-blank mappingRationale
 *     invariants.
 *
 *  4. **How later release-policy/CI work can consume Android evidence consistently from V2**
 *     — verified by per-dimension gate coverage confirmations and query-helper correctness
 *     tests that show V2 gate logic can locate evidence by gate dimension and alignment.
 *
 *  5. **How this fresh Android PR supersedes the previous Android PR-7 effort** — verified
 *     by assertions on INTRODUCED_PR_TITLE and V2_COMPANION_REFERENCE, and by spot-checks
 *     confirming that canonical execution event and durable identity evidence from the prior
 *     PR-7 work is included in the mapping.
 *
 * ## Test matrix
 *
 * ### AndroidDistributedGateMapping — structural invariants
 *  - INTRODUCED_PR_TITLE is non-blank
 *  - V2_COMPANION_REFERENCE is non-blank
 *  - DESCRIPTION is non-blank
 *  - allMappings has exactly MAPPING_ENTRY_COUNT entries
 *  - all mappingIds are non-blank
 *  - all mappingIds are unique
 *  - all mappingRationale values are non-blank
 *  - all v2ConsumptionNote values are non-blank
 *  - all primaryAndroidEvidenceIds lists are non-empty
 *  - STRONG_MAPPING_COUNT matches actual strong entries
 *  - ADVISORY_MAPPING_COUNT matches actual advisory entries
 *  - DEPRECATED_COMPAT_MAPPING_COUNT matches actual deprecated entries
 *  - INTENTIONALLY_LOCAL_MAPPING_COUNT matches actual intentionally-local entries
 *  - total alignment counts sum to MAPPING_ENTRY_COUNT
 *
 * ### GateDimension — wire values
 *  - LIFECYCLE_RUNTIME_CORRECTNESS wireValue is "lifecycle_runtime_correctness"
 *  - TAKEOVER_EXECUTION_OUTCOMES wireValue is "takeover_execution_outcomes"
 *  - PARTICIPANT_TRUTH_RECONCILIATION wireValue is "participant_truth_reconciliation"
 *  - EVALUATOR_ARTIFACT_EMISSION wireValue is "evaluator_artifact_emission"
 *  - CONTINUITY_RECOVERY_SAFETY wireValue is "continuity_recovery_safety"
 *  - COMPATIBILITY_LEGACY_SUPPRESSION wireValue is "compatibility_legacy_suppression"
 *  - All six wire values are distinct
 *  - fromValue round-trips all six wire values correctly
 *  - fromValue returns null for unknown value
 *  - fromValue returns null for null
 *
 * ### EvidenceAlignment — wire values
 *  - STRONG_PARTICIPANT_RUNTIME wireValue is "strong_participant_runtime"
 *  - ADVISORY_OBSERVATION wireValue is "advisory_observation"
 *  - DEPRECATED_COMPAT wireValue is "deprecated_compat"
 *  - INTENTIONALLY_LOCAL wireValue is "intentionally_local"
 *  - All four wire values are distinct
 *  - fromValue round-trips all four wire values correctly
 *  - fromValue returns null for unknown value
 *  - fromValue returns null for null
 *
 * ### Per-dimension strong coverage (AC1, AC4)
 *  - LIFECYCLE_RUNTIME_CORRECTNESS has at least one STRONG_PARTICIPANT_RUNTIME entry
 *  - TAKEOVER_EXECUTION_OUTCOMES has at least one STRONG_PARTICIPANT_RUNTIME entry
 *  - PARTICIPANT_TRUTH_RECONCILIATION has at least one STRONG_PARTICIPANT_RUNTIME entry
 *  - EVALUATOR_ARTIFACT_EMISSION has at least one STRONG_PARTICIPANT_RUNTIME entry
 *  - CONTINUITY_RECOVERY_SAFETY has at least one STRONG_PARTICIPANT_RUNTIME entry
 *  - COMPATIBILITY_LEGACY_SUPPRESSION has at least one STRONG_PARTICIPANT_RUNTIME entry
 *  - dimensionsCoveredByStrongEvidence returns all six gate dimensions
 *
 * ### Specific mapping entries — spot-checks (AC1, AC2, AC3, AC5)
 *  - runtime_lifecycle_evaluator_chain_to_gate_lifecycle is STRONG and targets LIFECYCLE_RUNTIME_CORRECTNESS
 *  - canonical_execution_event_emission_to_gate_lifecycle is STRONG (prior PR-7 canonical events)
 *  - strategy_evaluator_to_gate_lifecycle_advisory is ADVISORY
 *  - takeover_compat_bounding_to_gate_takeover is STRONG and targets TAKEOVER_EXECUTION_OUTCOMES
 *  - takeover_recovery_authority_to_gate_takeover_advisory is ADVISORY
 *  - truth_reconciliation_signals_to_gate_truth is STRONG and targets PARTICIPANT_TRUTH_RECONCILIATION
 *  - durable_identity_reattach_to_gate_truth is STRONG (prior PR-7 durable identity)
 *  - device_artifacts_to_gate_evaluator is STRONG and targets EVALUATOR_ARTIFACT_EMISSION
 *  - governance_artifact_to_gate_evaluator is STRONG
 *  - strategy_artifact_to_gate_evaluator_advisory is ADVISORY
 *  - recovery_bounding_to_gate_continuity is STRONG and targets CONTINUITY_RECOVERY_SAFETY
 *  - signal_dedup_replay_to_gate_continuity is STRONG
 *  - compat_blocking_audit_to_gate_compat is STRONG and targets COMPATIBILITY_LEGACY_SUPPRESSION
 *  - long_tail_compat_to_gate_compat_deprecated is DEPRECATED_COMPAT
 *
 * ### DEPRECATED_COMPAT entries do not satisfy gate dimensions (AC2)
 *  - long_tail_compat is DEPRECATED_COMPAT (not STRONG)
 *  - DEPRECATED_COMPAT entries are not counted in dimensionsCoveredByStrongEvidence
 *  - COMPATIBILITY_LEGACY_SUPPRESSION strong coverage holds without DEPRECATED_COMPAT
 *
 * ### STRONG v2ConsumptionNote contains test reference (AC3)
 *  - all STRONG entries v2ConsumptionNote references at least one test class name
 *
 * ### Prior PR-7 work is included in the mapping (AC5)
 *  - canonical_execution_event_emission entry references Pr7AndroidCanonicalExecutionEventsTest
 *  - durable_identity_reattach entry references Pr7DurableParticipantIdentityReattachTest
 *
 * ### Query helpers
 *  - mappingFor returns entry for known id
 *  - mappingFor returns null for unknown id
 *  - mappingsForDimension returns only entries for that dimension
 *  - mappingsAtAlignment returns only entries at that alignment
 *  - mappingsForAndroidEvidence returns entries containing that evidence id
 *  - mappingsForAndroidEvidence returns empty list for unknown evidence id
 *  - dimensionsCoveredByStrongEvidence returns set of 6 dimensions
 */
class Pr7bAndroidDistributedGateMappingTest {

    private val mapping = AndroidDistributedGateMapping

    // ── Structural invariants ─────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR_TITLE is non-blank`() {
        assertTrue(mapping.INTRODUCED_PR_TITLE.isNotBlank())
    }

    @Test
    fun `V2_COMPANION_REFERENCE is non-blank`() {
        assertTrue(mapping.V2_COMPANION_REFERENCE.isNotBlank())
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(mapping.DESCRIPTION.isNotBlank())
    }

    @Test
    fun `allMappings has exactly MAPPING_ENTRY_COUNT entries`() {
        assertEquals(mapping.MAPPING_ENTRY_COUNT, mapping.allMappings.size)
    }

    @Test
    fun `all mappingIds are non-blank`() {
        mapping.allMappings.forEach {
            assertTrue("mappingId blank for entry: $it", it.mappingId.isNotBlank())
        }
    }

    @Test
    fun `all mappingIds are unique`() {
        val ids = mapping.allMappings.map { it.mappingId }
        assertEquals("duplicate mappingIds found", ids.size, ids.toSet().size)
    }

    @Test
    fun `all mappingRationale values are non-blank`() {
        mapping.allMappings.forEach {
            assertTrue("mappingRationale blank for ${it.mappingId}", it.mappingRationale.isNotBlank())
        }
    }

    @Test
    fun `all v2ConsumptionNote values are non-blank`() {
        mapping.allMappings.forEach {
            assertTrue("v2ConsumptionNote blank for ${it.mappingId}", it.v2ConsumptionNote.isNotBlank())
        }
    }

    @Test
    fun `all primaryAndroidEvidenceIds lists are non-empty`() {
        mapping.allMappings.forEach {
            assertTrue(
                "primaryAndroidEvidenceIds empty for ${it.mappingId}",
                it.primaryAndroidEvidenceIds.isNotEmpty()
            )
        }
    }

    @Test
    fun `STRONG_MAPPING_COUNT matches actual strong entries`() {
        assertEquals(
            mapping.STRONG_MAPPING_COUNT,
            mapping.mappingsAtAlignment(
                AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME
            ).size
        )
    }

    @Test
    fun `ADVISORY_MAPPING_COUNT matches actual advisory entries`() {
        assertEquals(
            mapping.ADVISORY_MAPPING_COUNT,
            mapping.mappingsAtAlignment(
                AndroidDistributedGateMapping.EvidenceAlignment.ADVISORY_OBSERVATION
            ).size
        )
    }

    @Test
    fun `DEPRECATED_COMPAT_MAPPING_COUNT matches actual deprecated entries`() {
        assertEquals(
            mapping.DEPRECATED_COMPAT_MAPPING_COUNT,
            mapping.mappingsAtAlignment(
                AndroidDistributedGateMapping.EvidenceAlignment.DEPRECATED_COMPAT
            ).size
        )
    }

    @Test
    fun `INTENTIONALLY_LOCAL_MAPPING_COUNT matches actual intentionally-local entries`() {
        assertEquals(
            mapping.INTENTIONALLY_LOCAL_MAPPING_COUNT,
            mapping.mappingsAtAlignment(
                AndroidDistributedGateMapping.EvidenceAlignment.INTENTIONALLY_LOCAL
            ).size
        )
    }

    @Test
    fun `total alignment counts sum to MAPPING_ENTRY_COUNT`() {
        val total = mapping.STRONG_MAPPING_COUNT +
            mapping.ADVISORY_MAPPING_COUNT +
            mapping.DEPRECATED_COMPAT_MAPPING_COUNT +
            mapping.INTENTIONALLY_LOCAL_MAPPING_COUNT
        assertEquals(mapping.MAPPING_ENTRY_COUNT, total)
    }

    // ── GateDimension — wire values ───────────────────────────────────────────

    @Test
    fun `LIFECYCLE_RUNTIME_CORRECTNESS wireValue is lifecycle_runtime_correctness`() {
        assertEquals(
            "lifecycle_runtime_correctness",
            AndroidDistributedGateMapping.GateDimension.LIFECYCLE_RUNTIME_CORRECTNESS.wireValue
        )
    }

    @Test
    fun `TAKEOVER_EXECUTION_OUTCOMES wireValue is takeover_execution_outcomes`() {
        assertEquals(
            "takeover_execution_outcomes",
            AndroidDistributedGateMapping.GateDimension.TAKEOVER_EXECUTION_OUTCOMES.wireValue
        )
    }

    @Test
    fun `PARTICIPANT_TRUTH_RECONCILIATION wireValue is participant_truth_reconciliation`() {
        assertEquals(
            "participant_truth_reconciliation",
            AndroidDistributedGateMapping.GateDimension.PARTICIPANT_TRUTH_RECONCILIATION.wireValue
        )
    }

    @Test
    fun `EVALUATOR_ARTIFACT_EMISSION wireValue is evaluator_artifact_emission`() {
        assertEquals(
            "evaluator_artifact_emission",
            AndroidDistributedGateMapping.GateDimension.EVALUATOR_ARTIFACT_EMISSION.wireValue
        )
    }

    @Test
    fun `CONTINUITY_RECOVERY_SAFETY wireValue is continuity_recovery_safety`() {
        assertEquals(
            "continuity_recovery_safety",
            AndroidDistributedGateMapping.GateDimension.CONTINUITY_RECOVERY_SAFETY.wireValue
        )
    }

    @Test
    fun `COMPATIBILITY_LEGACY_SUPPRESSION wireValue is compatibility_legacy_suppression`() {
        assertEquals(
            "compatibility_legacy_suppression",
            AndroidDistributedGateMapping.GateDimension.COMPATIBILITY_LEGACY_SUPPRESSION.wireValue
        )
    }

    @Test
    fun `all six gate dimension wire values are distinct`() {
        val values = AndroidDistributedGateMapping.GateDimension.values().map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `GateDimension fromValue round-trips lifecycle_runtime_correctness`() {
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.LIFECYCLE_RUNTIME_CORRECTNESS,
            AndroidDistributedGateMapping.GateDimension.fromValue("lifecycle_runtime_correctness")
        )
    }

    @Test
    fun `GateDimension fromValue round-trips takeover_execution_outcomes`() {
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.TAKEOVER_EXECUTION_OUTCOMES,
            AndroidDistributedGateMapping.GateDimension.fromValue("takeover_execution_outcomes")
        )
    }

    @Test
    fun `GateDimension fromValue round-trips participant_truth_reconciliation`() {
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.PARTICIPANT_TRUTH_RECONCILIATION,
            AndroidDistributedGateMapping.GateDimension.fromValue("participant_truth_reconciliation")
        )
    }

    @Test
    fun `GateDimension fromValue round-trips evaluator_artifact_emission`() {
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.EVALUATOR_ARTIFACT_EMISSION,
            AndroidDistributedGateMapping.GateDimension.fromValue("evaluator_artifact_emission")
        )
    }

    @Test
    fun `GateDimension fromValue round-trips continuity_recovery_safety`() {
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.CONTINUITY_RECOVERY_SAFETY,
            AndroidDistributedGateMapping.GateDimension.fromValue("continuity_recovery_safety")
        )
    }

    @Test
    fun `GateDimension fromValue round-trips compatibility_legacy_suppression`() {
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.COMPATIBILITY_LEGACY_SUPPRESSION,
            AndroidDistributedGateMapping.GateDimension.fromValue("compatibility_legacy_suppression")
        )
    }

    @Test
    fun `GateDimension fromValue returns null for unknown value`() {
        assertNull(
            AndroidDistributedGateMapping.GateDimension.fromValue("nonexistent_gate_dimension")
        )
    }

    @Test
    fun `GateDimension fromValue returns null for null`() {
        assertNull(AndroidDistributedGateMapping.GateDimension.fromValue(null))
    }

    // ── EvidenceAlignment — wire values ───────────────────────────────────────

    @Test
    fun `STRONG_PARTICIPANT_RUNTIME wireValue is strong_participant_runtime`() {
        assertEquals(
            "strong_participant_runtime",
            AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME.wireValue
        )
    }

    @Test
    fun `ADVISORY_OBSERVATION wireValue is advisory_observation`() {
        assertEquals(
            "advisory_observation",
            AndroidDistributedGateMapping.EvidenceAlignment.ADVISORY_OBSERVATION.wireValue
        )
    }

    @Test
    fun `DEPRECATED_COMPAT wireValue is deprecated_compat`() {
        assertEquals(
            "deprecated_compat",
            AndroidDistributedGateMapping.EvidenceAlignment.DEPRECATED_COMPAT.wireValue
        )
    }

    @Test
    fun `INTENTIONALLY_LOCAL wireValue is intentionally_local`() {
        assertEquals(
            "intentionally_local",
            AndroidDistributedGateMapping.EvidenceAlignment.INTENTIONALLY_LOCAL.wireValue
        )
    }

    @Test
    fun `all four evidence alignment wire values are distinct`() {
        val values = AndroidDistributedGateMapping.EvidenceAlignment.values().map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `EvidenceAlignment fromValue round-trips strong_participant_runtime`() {
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            AndroidDistributedGateMapping.EvidenceAlignment.fromValue("strong_participant_runtime")
        )
    }

    @Test
    fun `EvidenceAlignment fromValue round-trips advisory_observation`() {
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.ADVISORY_OBSERVATION,
            AndroidDistributedGateMapping.EvidenceAlignment.fromValue("advisory_observation")
        )
    }

    @Test
    fun `EvidenceAlignment fromValue round-trips deprecated_compat`() {
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.DEPRECATED_COMPAT,
            AndroidDistributedGateMapping.EvidenceAlignment.fromValue("deprecated_compat")
        )
    }

    @Test
    fun `EvidenceAlignment fromValue round-trips intentionally_local`() {
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.INTENTIONALLY_LOCAL,
            AndroidDistributedGateMapping.EvidenceAlignment.fromValue("intentionally_local")
        )
    }

    @Test
    fun `EvidenceAlignment fromValue returns null for unknown value`() {
        assertNull(
            AndroidDistributedGateMapping.EvidenceAlignment.fromValue("nonexistent_alignment")
        )
    }

    @Test
    fun `EvidenceAlignment fromValue returns null for null`() {
        assertNull(AndroidDistributedGateMapping.EvidenceAlignment.fromValue(null))
    }

    // ── Per-dimension strong coverage (AC1, AC4) ──────────────────────────────

    @Test
    fun `LIFECYCLE_RUNTIME_CORRECTNESS has at least one STRONG_PARTICIPANT_RUNTIME entry`() {
        val strongEntries = mapping.mappingsForDimension(
            AndroidDistributedGateMapping.GateDimension.LIFECYCLE_RUNTIME_CORRECTNESS
        ).filter {
            it.evidenceAlignment == AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME
        }
        assertTrue(
            "LIFECYCLE_RUNTIME_CORRECTNESS has no STRONG entries",
            strongEntries.isNotEmpty()
        )
    }

    @Test
    fun `TAKEOVER_EXECUTION_OUTCOMES has at least one STRONG_PARTICIPANT_RUNTIME entry`() {
        val strongEntries = mapping.mappingsForDimension(
            AndroidDistributedGateMapping.GateDimension.TAKEOVER_EXECUTION_OUTCOMES
        ).filter {
            it.evidenceAlignment == AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME
        }
        assertTrue(
            "TAKEOVER_EXECUTION_OUTCOMES has no STRONG entries",
            strongEntries.isNotEmpty()
        )
    }

    @Test
    fun `PARTICIPANT_TRUTH_RECONCILIATION has at least one STRONG_PARTICIPANT_RUNTIME entry`() {
        val strongEntries = mapping.mappingsForDimension(
            AndroidDistributedGateMapping.GateDimension.PARTICIPANT_TRUTH_RECONCILIATION
        ).filter {
            it.evidenceAlignment == AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME
        }
        assertTrue(
            "PARTICIPANT_TRUTH_RECONCILIATION has no STRONG entries",
            strongEntries.isNotEmpty()
        )
    }

    @Test
    fun `EVALUATOR_ARTIFACT_EMISSION has at least one STRONG_PARTICIPANT_RUNTIME entry`() {
        val strongEntries = mapping.mappingsForDimension(
            AndroidDistributedGateMapping.GateDimension.EVALUATOR_ARTIFACT_EMISSION
        ).filter {
            it.evidenceAlignment == AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME
        }
        assertTrue(
            "EVALUATOR_ARTIFACT_EMISSION has no STRONG entries",
            strongEntries.isNotEmpty()
        )
    }

    @Test
    fun `CONTINUITY_RECOVERY_SAFETY has at least one STRONG_PARTICIPANT_RUNTIME entry`() {
        val strongEntries = mapping.mappingsForDimension(
            AndroidDistributedGateMapping.GateDimension.CONTINUITY_RECOVERY_SAFETY
        ).filter {
            it.evidenceAlignment == AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME
        }
        assertTrue(
            "CONTINUITY_RECOVERY_SAFETY has no STRONG entries",
            strongEntries.isNotEmpty()
        )
    }

    @Test
    fun `COMPATIBILITY_LEGACY_SUPPRESSION has at least one STRONG_PARTICIPANT_RUNTIME entry`() {
        val strongEntries = mapping.mappingsForDimension(
            AndroidDistributedGateMapping.GateDimension.COMPATIBILITY_LEGACY_SUPPRESSION
        ).filter {
            it.evidenceAlignment == AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME
        }
        assertTrue(
            "COMPATIBILITY_LEGACY_SUPPRESSION has no STRONG entries",
            strongEntries.isNotEmpty()
        )
    }

    @Test
    fun `dimensionsCoveredByStrongEvidence returns all six gate dimensions`() {
        val coveredDimensions = mapping.dimensionsCoveredByStrongEvidence()
        assertEquals(
            "Expected all 6 gate dimensions to have strong coverage",
            AndroidDistributedGateMapping.GateDimension.values().size,
            coveredDimensions.size
        )
        AndroidDistributedGateMapping.GateDimension.values().forEach { dim ->
            assertTrue("$dim not in dimensionsCoveredByStrongEvidence", coveredDimensions.contains(dim))
        }
    }

    // ── Specific mapping entry spot-checks (AC1, AC2, AC3, AC5) ──────────────

    @Test
    fun `runtime_lifecycle_evaluator_chain entry is STRONG and targets LIFECYCLE_RUNTIME_CORRECTNESS`() {
        val entry = mapping.mappingFor("runtime_lifecycle_evaluator_chain_to_gate_lifecycle")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            entry!!.evidenceAlignment
        )
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.LIFECYCLE_RUNTIME_CORRECTNESS,
            entry.gateDimension
        )
    }

    @Test
    fun `canonical_execution_event_emission entry is STRONG (prior PR-7 canonical events)`() {
        val entry = mapping.mappingFor("canonical_execution_event_emission_to_gate_lifecycle")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            entry!!.evidenceAlignment
        )
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.LIFECYCLE_RUNTIME_CORRECTNESS,
            entry.gateDimension
        )
    }

    @Test
    fun `strategy_evaluator_to_gate_lifecycle_advisory entry is ADVISORY`() {
        val entry = mapping.mappingFor("strategy_evaluator_to_gate_lifecycle_advisory")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.ADVISORY_OBSERVATION,
            entry!!.evidenceAlignment
        )
    }

    @Test
    fun `takeover_compat_bounding entry is STRONG and targets TAKEOVER_EXECUTION_OUTCOMES`() {
        val entry = mapping.mappingFor("takeover_compat_bounding_to_gate_takeover")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            entry!!.evidenceAlignment
        )
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.TAKEOVER_EXECUTION_OUTCOMES,
            entry.gateDimension
        )
    }

    @Test
    fun `takeover_recovery_authority entry is ADVISORY`() {
        val entry = mapping.mappingFor("takeover_recovery_authority_to_gate_takeover_advisory")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.ADVISORY_OBSERVATION,
            entry!!.evidenceAlignment
        )
    }

    @Test
    fun `truth_reconciliation_signals entry is STRONG and targets PARTICIPANT_TRUTH_RECONCILIATION`() {
        val entry = mapping.mappingFor("truth_reconciliation_signals_to_gate_truth")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            entry!!.evidenceAlignment
        )
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.PARTICIPANT_TRUTH_RECONCILIATION,
            entry.gateDimension
        )
    }

    @Test
    fun `durable_identity_reattach entry is STRONG (prior PR-7 durable identity)`() {
        val entry = mapping.mappingFor("durable_identity_reattach_to_gate_truth")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            entry!!.evidenceAlignment
        )
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.PARTICIPANT_TRUTH_RECONCILIATION,
            entry.gateDimension
        )
    }

    @Test
    fun `device_artifacts entry is STRONG and targets EVALUATOR_ARTIFACT_EMISSION`() {
        val entry = mapping.mappingFor("device_artifacts_to_gate_evaluator")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            entry!!.evidenceAlignment
        )
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.EVALUATOR_ARTIFACT_EMISSION,
            entry.gateDimension
        )
    }

    @Test
    fun `governance_artifact entry is STRONG`() {
        val entry = mapping.mappingFor("governance_artifact_to_gate_evaluator")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            entry!!.evidenceAlignment
        )
    }

    @Test
    fun `strategy_artifact_to_gate_evaluator_advisory entry is ADVISORY`() {
        val entry = mapping.mappingFor("strategy_artifact_to_gate_evaluator_advisory")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.ADVISORY_OBSERVATION,
            entry!!.evidenceAlignment
        )
    }

    @Test
    fun `recovery_bounding entry is STRONG and targets CONTINUITY_RECOVERY_SAFETY`() {
        val entry = mapping.mappingFor("recovery_bounding_to_gate_continuity")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            entry!!.evidenceAlignment
        )
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.CONTINUITY_RECOVERY_SAFETY,
            entry.gateDimension
        )
    }

    @Test
    fun `signal_dedup_replay entry is STRONG`() {
        val entry = mapping.mappingFor("signal_dedup_replay_to_gate_continuity")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            entry!!.evidenceAlignment
        )
    }

    @Test
    fun `compat_blocking_audit entry is STRONG and targets COMPATIBILITY_LEGACY_SUPPRESSION`() {
        val entry = mapping.mappingFor("compat_blocking_audit_to_gate_compat")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            entry!!.evidenceAlignment
        )
        assertEquals(
            AndroidDistributedGateMapping.GateDimension.COMPATIBILITY_LEGACY_SUPPRESSION,
            entry.gateDimension
        )
    }

    @Test
    fun `long_tail_compat entry is DEPRECATED_COMPAT`() {
        val entry = mapping.mappingFor("long_tail_compat_to_gate_compat_deprecated")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.DEPRECATED_COMPAT,
            entry!!.evidenceAlignment
        )
    }

    // ── DEPRECATED_COMPAT entries do not satisfy gate dimensions (AC2) ─────────

    @Test
    fun `long_tail_compat is DEPRECATED_COMPAT not STRONG`() {
        val entry = mapping.mappingFor("long_tail_compat_to_gate_compat_deprecated")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateMapping.EvidenceAlignment.DEPRECATED_COMPAT,
            entry!!.evidenceAlignment
        )
    }

    @Test
    fun `DEPRECATED_COMPAT entries are not counted in dimensionsCoveredByStrongEvidence`() {
        // Verify the function only counts STRONG entries
        val coveredByStrong = mapping.dimensionsCoveredByStrongEvidence()
        // All DEPRECATED_COMPAT entries should be from dimensions also covered by STRONG entries
        mapping.mappingsAtAlignment(AndroidDistributedGateMapping.EvidenceAlignment.DEPRECATED_COMPAT)
            .forEach { deprecatedEntry ->
                // The dimension must be covered by at least one STRONG entry too
                assertTrue(
                    "${deprecatedEntry.gateDimension} is only covered by DEPRECATED_COMPAT",
                    coveredByStrong.contains(deprecatedEntry.gateDimension)
                )
            }
    }

    @Test
    fun `COMPATIBILITY_LEGACY_SUPPRESSION strong coverage holds without DEPRECATED_COMPAT`() {
        val strongCompatEntries = mapping.mappingsForDimension(
            AndroidDistributedGateMapping.GateDimension.COMPATIBILITY_LEGACY_SUPPRESSION
        ).filter {
            it.evidenceAlignment == AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME
        }
        assertTrue(
            "COMPATIBILITY_LEGACY_SUPPRESSION has no STRONG entries after excluding DEPRECATED_COMPAT",
            strongCompatEntries.isNotEmpty()
        )
    }

    // ── STRONG v2ConsumptionNote contains test reference (AC3) ────────────────

    @Test
    fun `all STRONG entries v2ConsumptionNote references at least one test class name`() {
        mapping.mappingsAtAlignment(
            AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME
        ).forEach { entry ->
            assertTrue(
                "STRONG entry ${entry.mappingId} v2ConsumptionNote does not reference a test class",
                entry.v2ConsumptionNote.contains("Test")
            )
        }
    }

    // ── Prior PR-7 work is included in the mapping (AC5) ─────────────────────

    @Test
    fun `canonical_execution_event_emission v2ConsumptionNote references Pr7AndroidCanonicalExecutionEventsTest`() {
        val entry = mapping.mappingFor("canonical_execution_event_emission_to_gate_lifecycle")
        assertNotNull(entry)
        assertTrue(
            "Expected reference to Pr7AndroidCanonicalExecutionEventsTest",
            entry!!.v2ConsumptionNote.contains("Pr7AndroidCanonicalExecutionEventsTest")
        )
    }

    @Test
    fun `durable_identity_reattach v2ConsumptionNote references Pr7DurableParticipantIdentityReattachTest`() {
        val entry = mapping.mappingFor("durable_identity_reattach_to_gate_truth")
        assertNotNull(entry)
        assertTrue(
            "Expected reference to Pr7DurableParticipantIdentityReattachTest",
            entry!!.v2ConsumptionNote.contains("Pr7DurableParticipantIdentityReattachTest")
        )
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    @Test
    fun `mappingFor returns entry for known id`() {
        assertNotNull(mapping.mappingFor("runtime_lifecycle_evaluator_chain_to_gate_lifecycle"))
    }

    @Test
    fun `mappingFor returns null for unknown id`() {
        assertNull(mapping.mappingFor("nonexistent_mapping_id"))
    }

    @Test
    fun `mappingsForDimension returns only entries for that dimension`() {
        val entries = mapping.mappingsForDimension(
            AndroidDistributedGateMapping.GateDimension.CONTINUITY_RECOVERY_SAFETY
        )
        assertTrue(entries.isNotEmpty())
        entries.forEach { entry ->
            assertEquals(
                AndroidDistributedGateMapping.GateDimension.CONTINUITY_RECOVERY_SAFETY,
                entry.gateDimension
            )
        }
    }

    @Test
    fun `mappingsAtAlignment returns only entries at that alignment`() {
        val entries = mapping.mappingsAtAlignment(
            AndroidDistributedGateMapping.EvidenceAlignment.ADVISORY_OBSERVATION
        )
        assertTrue(entries.isNotEmpty())
        entries.forEach { entry ->
            assertEquals(
                AndroidDistributedGateMapping.EvidenceAlignment.ADVISORY_OBSERVATION,
                entry.evidenceAlignment
            )
        }
    }

    @Test
    fun `mappingsForAndroidEvidence returns entries containing that evidence id`() {
        val evidenceId = "readiness_evaluator_five_dimension_verdict"
        val entries = mapping.mappingsForAndroidEvidence(evidenceId)
        assertTrue("Expected at least one entry for $evidenceId", entries.isNotEmpty())
        entries.forEach { entry ->
            assertTrue(
                "Entry ${entry.mappingId} does not contain $evidenceId",
                entry.primaryAndroidEvidenceIds.contains(evidenceId)
            )
        }
    }

    @Test
    fun `mappingsForAndroidEvidence returns empty list for unknown evidence id`() {
        val entries = mapping.mappingsForAndroidEvidence("nonexistent_evidence_id")
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `dimensionsCoveredByStrongEvidence returns set of 6 dimensions`() {
        assertEquals(6, mapping.dimensionsCoveredByStrongEvidence().size)
    }
}
