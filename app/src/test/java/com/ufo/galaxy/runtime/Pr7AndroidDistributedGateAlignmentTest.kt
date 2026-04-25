package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-7 (Android) — Android distributed gate alignment: acceptance and structural tests.
 *
 * Validates the five acceptance criteria for aligning Android readiness evidence with the
 * canonical distributed release-gate skeleton:
 *
 *  1. **How Android readiness evidence maps into canonical distributed gate categories** —
 *     verified by [dimensionToCategoryMap] completeness and [gateMappings] structural
 *     invariants.
 *
 *  2. **Which Android evidence is strong vs advisory vs deprecated/local-only** —
 *     verified by [EvidenceAuthority] assignments and count invariants.
 *
 *  3. **That Android-side mappings are backed by real runtime/tests/artifacts** —
 *     verified by cross-referencing gate mapping entries against
 *     [AndroidReadinessEvidenceSurface.allEvidence] (every mapped evidenceId must exist).
 *
 *  4. **How later release-policy/CI work can consume Android evidence consistently** —
 *     verified by gateMappingNote non-blank invariants and category coverage checks.
 *
 *  5. **How this fresh Android PR-7 supersedes the previous Android PR-7 effort** —
 *     verified by INTRODUCED_PR and INTRODUCED_PR_TITLE constants.
 *
 * ## Test matrix
 *
 * ### AndroidDistributedGateAlignment — structural invariants
 *  - INTRODUCED_PR is 7
 *  - INTRODUCED_PR_TITLE is non-blank
 *  - DESCRIPTION is non-blank
 *  - gateMappings has exactly GATE_MAPPING_COUNT entries
 *  - all evidenceIds in gateMappings are non-blank
 *  - all evidenceIds in gateMappings are unique
 *  - all gateMappingNotes are non-blank
 *  - STRONG_PARTICIPANT_RUNTIME count matches STRONG_PARTICIPANT_RUNTIME_COUNT
 *  - ADVISORY_OBSERVATION_ONLY count matches ADVISORY_OBSERVATION_ONLY_COUNT
 *  - DEPRECATED_COMPATIBILITY count matches DEPRECATED_COMPATIBILITY_COUNT
 *  - INTENTIONALLY_LOCAL_DEFERRED count matches INTENTIONALLY_LOCAL_DEFERRED_COUNT
 *  - authority counts sum to GATE_MAPPING_COUNT
 *
 * ### CanonicalGateCategory — wire values (AC1: how Android evidence maps to gate categories)
 *  - LIFECYCLE_RUNTIME_CORRECTNESS wireValue is "lifecycle_runtime_correctness"
 *  - TAKEOVER_EXECUTION_OUTCOMES wireValue is "takeover_execution_outcomes"
 *  - RECONCILIATION_ARTIFACT_EMISSION wireValue is "reconciliation_artifact_emission"
 *  - CONTINUITY_RECOVERY_SAFETY wireValue is "continuity_recovery_safety"
 *  - COMPATIBILITY_LEGACY_SUPPRESSION wireValue is "compatibility_legacy_suppression"
 *  - SIGNAL_REPLAY_DUPLICATE_SAFETY wireValue is "signal_replay_duplicate_safety"
 *  - All six wire values are distinct
 *  - fromValue round-trips all six wire values correctly
 *  - fromValue returns null for unknown value
 *  - fromValue returns null for null
 *
 * ### EvidenceAuthority — wire values (AC2: which evidence is strong vs advisory vs deprecated)
 *  - STRONG_PARTICIPANT_RUNTIME wireValue is "strong_participant_runtime"
 *  - ADVISORY_OBSERVATION_ONLY wireValue is "advisory_observation_only"
 *  - DEPRECATED_COMPATIBILITY wireValue is "deprecated_compatibility"
 *  - INTENTIONALLY_LOCAL_DEFERRED wireValue is "intentionally_local_deferred"
 *  - All four wire values are distinct
 *  - fromValue round-trips all four wire values correctly
 *  - fromValue returns null for unknown value
 *  - fromValue returns null for null
 *
 * ### dimensionToCategoryMap — completeness (AC1: dimension → category alignment)
 *  - All six Android dimensions are present in dimensionToCategoryMap
 *  - RUNTIME_LIFECYCLE maps to LIFECYCLE_RUNTIME_CORRECTNESS
 *  - TAKEOVER_EXECUTION maps to TAKEOVER_EXECUTION_OUTCOMES
 *  - ARTIFACT_EMISSION_RECONCILIATION maps to RECONCILIATION_ARTIFACT_EMISSION
 *  - CONTINUITY_RECOVERY_SAFETY maps to CONTINUITY_RECOVERY_SAFETY
 *  - COMPATIBILITY_SUPPRESSION maps to COMPATIBILITY_LEGACY_SUPPRESSION
 *  - SIGNAL_REPLAY_DUPLICATE_SAFETY maps to SIGNAL_REPLAY_DUPLICATE_SAFETY
 *
 * ### Per-category strong coverage (AC3: mappings backed by real runtime)
 *  - LIFECYCLE_RUNTIME_CORRECTNESS has at least one STRONG_PARTICIPANT_RUNTIME entry
 *  - TAKEOVER_EXECUTION_OUTCOMES has at least one STRONG_PARTICIPANT_RUNTIME entry
 *  - RECONCILIATION_ARTIFACT_EMISSION has at least one STRONG_PARTICIPANT_RUNTIME entry
 *  - CONTINUITY_RECOVERY_SAFETY has at least one STRONG_PARTICIPANT_RUNTIME entry
 *  - COMPATIBILITY_LEGACY_SUPPRESSION has at least one STRONG_PARTICIPANT_RUNTIME entry
 *  - SIGNAL_REPLAY_DUPLICATE_SAFETY has at least one STRONG_PARTICIPANT_RUNTIME entry
 *
 * ### Cross-reference with AndroidReadinessEvidenceSurface (AC3: backed by real artifacts)
 *  - every evidenceId in gateMappings exists in AndroidReadinessEvidenceSurface.allEvidence
 *  - GATE_MAPPING_COUNT equals AndroidReadinessEvidenceSurface.EVIDENCE_ENTRY_COUNT
 *  - gateMappings authority aligns with AndroidReadinessEvidenceSurface confidence levels
 *
 * ### Specific evidence entries — spot-checks (AC2: strong vs advisory vs deprecated)
 *  - readiness_evaluator_five_dimension_verdict maps to LIFECYCLE_RUNTIME_CORRECTNESS / STRONG
 *  - acceptance_evaluator_six_dimension_graduation_verdict maps to LIFECYCLE / STRONG
 *  - strategy_evaluator_dispatch_verdict maps to LIFECYCLE / ADVISORY
 *  - takeover_fallback_event_canonical_bounding maps to TAKEOVER / STRONG
 *  - takeover_recovery_path_compat_gate maps to TAKEOVER / ADVISORY
 *  - device_readiness_artifact_wire_emission maps to RECONCILIATION / STRONG
 *  - long_tail_compat_registry_legacy_signals maps to COMPATIBILITY / DEPRECATED_COMPATIBILITY
 *  - emitted_signal_ledger_terminal_bounding maps to SIGNAL_REPLAY / STRONG
 *
 * ### Query helpers
 *  - mappingFor returns entry for known id
 *  - mappingFor returns null for unknown id
 *  - mappingsForCategory returns only entries for that category
 *  - mappingsForAuthority returns only entries for that authority
 *  - categoryFor returns correct category for each dimension
 *  - categoryFor returns null for an artificially unknown dimension (fromValue check)
 *
 * ### DEPRECATED_COMPATIBILITY entries do not satisfy canonical gate dimensions
 *  - long_tail_compat_registry_legacy_signals is DEPRECATED_COMPATIBILITY
 *  - DEPRECATED_COMPATIBILITY entries are not counted in STRONG or ADVISORY totals
 *  - canonical coverage per category holds even without DEPRECATED_COMPATIBILITY entries
 *
 * ### AC4: V2 consumption — gateMappingNote references
 *  - all STRONG_PARTICIPANT_RUNTIME entries have non-blank gateMappingNote
 *  - readiness evaluator mapping note references ReconciliationSignal
 *  - recovery participation owner mapping note references WaitForV2ReplayDecision
 *  - compat legacy blocking mapping note references semanticTag
 */
class Pr7AndroidDistributedGateAlignmentTest {

    private val alignment = AndroidDistributedGateAlignment
    private val surface = AndroidReadinessEvidenceSurface

    // ── Structural invariants ─────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 7`() {
        assertEquals(7, alignment.INTRODUCED_PR)
    }

    @Test
    fun `INTRODUCED_PR_TITLE is non-blank`() {
        assertTrue(alignment.INTRODUCED_PR_TITLE.isNotBlank())
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(alignment.DESCRIPTION.isNotBlank())
    }

    @Test
    fun `gateMappings has exactly GATE_MAPPING_COUNT entries`() {
        assertEquals(alignment.GATE_MAPPING_COUNT, alignment.gateMappings.size)
    }

    @Test
    fun `all evidenceIds in gateMappings are non-blank`() {
        alignment.gateMappings.forEach {
            assertTrue("evidenceId blank for gate mapping: $it", it.evidenceId.isNotBlank())
        }
    }

    @Test
    fun `all evidenceIds in gateMappings are unique`() {
        val ids = alignment.gateMappings.map { it.evidenceId }
        assertEquals("duplicate evidenceIds in gateMappings", ids.size, ids.toSet().size)
    }

    @Test
    fun `all gateMappingNotes are non-blank`() {
        alignment.gateMappings.forEach {
            assertTrue("gateMappingNote blank for ${it.evidenceId}", it.gateMappingNote.isNotBlank())
        }
    }

    @Test
    fun `STRONG_PARTICIPANT_RUNTIME count matches STRONG_PARTICIPANT_RUNTIME_COUNT`() {
        assertEquals(
            alignment.STRONG_PARTICIPANT_RUNTIME_COUNT,
            alignment.mappingsForAuthority(
                AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME
            ).size
        )
    }

    @Test
    fun `ADVISORY_OBSERVATION_ONLY count matches ADVISORY_OBSERVATION_ONLY_COUNT`() {
        assertEquals(
            alignment.ADVISORY_OBSERVATION_ONLY_COUNT,
            alignment.mappingsForAuthority(
                AndroidDistributedGateAlignment.EvidenceAuthority.ADVISORY_OBSERVATION_ONLY
            ).size
        )
    }

    @Test
    fun `DEPRECATED_COMPATIBILITY count matches DEPRECATED_COMPATIBILITY_COUNT`() {
        assertEquals(
            alignment.DEPRECATED_COMPATIBILITY_COUNT,
            alignment.mappingsForAuthority(
                AndroidDistributedGateAlignment.EvidenceAuthority.DEPRECATED_COMPATIBILITY
            ).size
        )
    }

    @Test
    fun `INTENTIONALLY_LOCAL_DEFERRED count matches INTENTIONALLY_LOCAL_DEFERRED_COUNT`() {
        assertEquals(
            alignment.INTENTIONALLY_LOCAL_DEFERRED_COUNT,
            alignment.mappingsForAuthority(
                AndroidDistributedGateAlignment.EvidenceAuthority.INTENTIONALLY_LOCAL_DEFERRED
            ).size
        )
    }

    @Test
    fun `authority counts sum to GATE_MAPPING_COUNT`() {
        val total = alignment.STRONG_PARTICIPANT_RUNTIME_COUNT +
            alignment.ADVISORY_OBSERVATION_ONLY_COUNT +
            alignment.DEPRECATED_COMPATIBILITY_COUNT +
            alignment.INTENTIONALLY_LOCAL_DEFERRED_COUNT
        assertEquals(alignment.GATE_MAPPING_COUNT, total)
    }

    // ── CanonicalGateCategory — wire values ───────────────────────────────────

    @Test
    fun `LIFECYCLE_RUNTIME_CORRECTNESS wireValue is lifecycle_runtime_correctness`() {
        assertEquals(
            "lifecycle_runtime_correctness",
            AndroidDistributedGateAlignment.CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS
                .wireValue
        )
    }

    @Test
    fun `TAKEOVER_EXECUTION_OUTCOMES wireValue is takeover_execution_outcomes`() {
        assertEquals(
            "takeover_execution_outcomes",
            AndroidDistributedGateAlignment.CanonicalGateCategory.TAKEOVER_EXECUTION_OUTCOMES
                .wireValue
        )
    }

    @Test
    fun `RECONCILIATION_ARTIFACT_EMISSION wireValue is reconciliation_artifact_emission`() {
        assertEquals(
            "reconciliation_artifact_emission",
            AndroidDistributedGateAlignment.CanonicalGateCategory.RECONCILIATION_ARTIFACT_EMISSION
                .wireValue
        )
    }

    @Test
    fun `CONTINUITY_RECOVERY_SAFETY wireValue is continuity_recovery_safety`() {
        assertEquals(
            "continuity_recovery_safety",
            AndroidDistributedGateAlignment.CanonicalGateCategory.CONTINUITY_RECOVERY_SAFETY
                .wireValue
        )
    }

    @Test
    fun `COMPATIBILITY_LEGACY_SUPPRESSION wireValue is compatibility_legacy_suppression`() {
        assertEquals(
            "compatibility_legacy_suppression",
            AndroidDistributedGateAlignment.CanonicalGateCategory.COMPATIBILITY_LEGACY_SUPPRESSION
                .wireValue
        )
    }

    @Test
    fun `SIGNAL_REPLAY_DUPLICATE_SAFETY wireValue is signal_replay_duplicate_safety`() {
        assertEquals(
            "signal_replay_duplicate_safety",
            AndroidDistributedGateAlignment.CanonicalGateCategory.SIGNAL_REPLAY_DUPLICATE_SAFETY
                .wireValue
        )
    }

    @Test
    fun `all canonical gate category wire values are distinct`() {
        val wireValues = AndroidDistributedGateAlignment.CanonicalGateCategory.values()
            .map { it.wireValue }
        assertEquals(
            "duplicate canonical gate category wire values found",
            wireValues.size,
            wireValues.toSet().size
        )
    }

    @Test
    fun `fromValue round-trips all six canonical gate category wire values`() {
        AndroidDistributedGateAlignment.CanonicalGateCategory.values().forEach { category ->
            assertEquals(
                category,
                AndroidDistributedGateAlignment.CanonicalGateCategory.fromValue(category.wireValue)
            )
        }
    }

    @Test
    fun `CanonicalGateCategory fromValue returns null for unknown value`() {
        assertNull(
            AndroidDistributedGateAlignment.CanonicalGateCategory.fromValue("unknown_category")
        )
    }

    @Test
    fun `CanonicalGateCategory fromValue returns null for null`() {
        assertNull(AndroidDistributedGateAlignment.CanonicalGateCategory.fromValue(null))
    }

    // ── EvidenceAuthority — wire values ───────────────────────────────────────

    @Test
    fun `STRONG_PARTICIPANT_RUNTIME wireValue is strong_participant_runtime`() {
        assertEquals(
            "strong_participant_runtime",
            AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME.wireValue
        )
    }

    @Test
    fun `ADVISORY_OBSERVATION_ONLY wireValue is advisory_observation_only`() {
        assertEquals(
            "advisory_observation_only",
            AndroidDistributedGateAlignment.EvidenceAuthority.ADVISORY_OBSERVATION_ONLY.wireValue
        )
    }

    @Test
    fun `DEPRECATED_COMPATIBILITY wireValue is deprecated_compatibility`() {
        assertEquals(
            "deprecated_compatibility",
            AndroidDistributedGateAlignment.EvidenceAuthority.DEPRECATED_COMPATIBILITY.wireValue
        )
    }

    @Test
    fun `INTENTIONALLY_LOCAL_DEFERRED wireValue is intentionally_local_deferred`() {
        assertEquals(
            "intentionally_local_deferred",
            AndroidDistributedGateAlignment.EvidenceAuthority.INTENTIONALLY_LOCAL_DEFERRED.wireValue
        )
    }

    @Test
    fun `all evidence authority wire values are distinct`() {
        val wireValues = AndroidDistributedGateAlignment.EvidenceAuthority.values()
            .map { it.wireValue }
        assertEquals(
            "duplicate evidence authority wire values found",
            wireValues.size,
            wireValues.toSet().size
        )
    }

    @Test
    fun `fromValue round-trips all four evidence authority wire values`() {
        AndroidDistributedGateAlignment.EvidenceAuthority.values().forEach { authority ->
            assertEquals(
                authority,
                AndroidDistributedGateAlignment.EvidenceAuthority.fromValue(authority.wireValue)
            )
        }
    }

    @Test
    fun `EvidenceAuthority fromValue returns null for unknown value`() {
        assertNull(
            AndroidDistributedGateAlignment.EvidenceAuthority.fromValue("unknown_authority")
        )
    }

    @Test
    fun `EvidenceAuthority fromValue returns null for null`() {
        assertNull(AndroidDistributedGateAlignment.EvidenceAuthority.fromValue(null))
    }

    // ── dimensionToCategoryMap — completeness ─────────────────────────────────

    @Test
    fun `all six Android dimensions are present in dimensionToCategoryMap`() {
        val mappedDimensions = alignment.dimensionToCategoryMap.keys
        AndroidReadinessEvidenceSurface.ReadinessDimension.values().forEach { dim ->
            assertTrue(
                "dimension $dim not found in dimensionToCategoryMap",
                mappedDimensions.contains(dim)
            )
        }
    }

    @Test
    fun `RUNTIME_LIFECYCLE maps to LIFECYCLE_RUNTIME_CORRECTNESS`() {
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            alignment.dimensionToCategoryMap[
                AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE
            ]
        )
    }

    @Test
    fun `TAKEOVER_EXECUTION maps to TAKEOVER_EXECUTION_OUTCOMES`() {
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.TAKEOVER_EXECUTION_OUTCOMES,
            alignment.dimensionToCategoryMap[
                AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION
            ]
        )
    }

    @Test
    fun `ARTIFACT_EMISSION_RECONCILIATION maps to RECONCILIATION_ARTIFACT_EMISSION`() {
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            alignment.dimensionToCategoryMap[
                AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION
            ]
        )
    }

    @Test
    fun `CONTINUITY_RECOVERY_SAFETY dimension maps to CONTINUITY_RECOVERY_SAFETY category`() {
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.CONTINUITY_RECOVERY_SAFETY,
            alignment.dimensionToCategoryMap[
                AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY
            ]
        )
    }

    @Test
    fun `COMPATIBILITY_SUPPRESSION maps to COMPATIBILITY_LEGACY_SUPPRESSION`() {
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.COMPATIBILITY_LEGACY_SUPPRESSION,
            alignment.dimensionToCategoryMap[
                AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION
            ]
        )
    }

    @Test
    fun `SIGNAL_REPLAY_DUPLICATE_SAFETY dimension maps to SIGNAL_REPLAY_DUPLICATE_SAFETY category`() {
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            alignment.dimensionToCategoryMap[
                AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY
            ]
        )
    }

    // ── Per-category strong coverage ──────────────────────────────────────────

    @Test
    fun `LIFECYCLE_RUNTIME_CORRECTNESS has at least one STRONG_PARTICIPANT_RUNTIME entry`() {
        assertTrue(
            alignment.mappingsForCategory(
                AndroidDistributedGateAlignment.CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS
            ).any {
                it.evidenceAuthority ==
                    AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME
            }
        )
    }

    @Test
    fun `TAKEOVER_EXECUTION_OUTCOMES has at least one STRONG_PARTICIPANT_RUNTIME entry`() {
        assertTrue(
            alignment.mappingsForCategory(
                AndroidDistributedGateAlignment.CanonicalGateCategory.TAKEOVER_EXECUTION_OUTCOMES
            ).any {
                it.evidenceAuthority ==
                    AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME
            }
        )
    }

    @Test
    fun `RECONCILIATION_ARTIFACT_EMISSION has at least one STRONG_PARTICIPANT_RUNTIME entry`() {
        assertTrue(
            alignment.mappingsForCategory(
                AndroidDistributedGateAlignment.CanonicalGateCategory.RECONCILIATION_ARTIFACT_EMISSION
            ).any {
                it.evidenceAuthority ==
                    AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME
            }
        )
    }

    @Test
    fun `CONTINUITY_RECOVERY_SAFETY category has at least one STRONG_PARTICIPANT_RUNTIME entry`() {
        assertTrue(
            alignment.mappingsForCategory(
                AndroidDistributedGateAlignment.CanonicalGateCategory.CONTINUITY_RECOVERY_SAFETY
            ).any {
                it.evidenceAuthority ==
                    AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME
            }
        )
    }

    @Test
    fun `COMPATIBILITY_LEGACY_SUPPRESSION has at least one STRONG_PARTICIPANT_RUNTIME entry`() {
        assertTrue(
            alignment.mappingsForCategory(
                AndroidDistributedGateAlignment.CanonicalGateCategory.COMPATIBILITY_LEGACY_SUPPRESSION
            ).any {
                it.evidenceAuthority ==
                    AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME
            }
        )
    }

    @Test
    fun `SIGNAL_REPLAY_DUPLICATE_SAFETY category has at least one STRONG_PARTICIPANT_RUNTIME entry`() {
        assertTrue(
            alignment.mappingsForCategory(
                AndroidDistributedGateAlignment.CanonicalGateCategory.SIGNAL_REPLAY_DUPLICATE_SAFETY
            ).any {
                it.evidenceAuthority ==
                    AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME
            }
        )
    }

    // ── Cross-reference with AndroidReadinessEvidenceSurface ──────────────────

    @Test
    fun `every evidenceId in gateMappings exists in AndroidReadinessEvidenceSurface`() {
        val surfaceIds = surface.allEvidence.map { it.evidenceId }.toSet()
        alignment.gateMappings.forEach { mapping ->
            assertTrue(
                "evidenceId '${mapping.evidenceId}' in gateMappings not found in " +
                    "AndroidReadinessEvidenceSurface.allEvidence",
                surfaceIds.contains(mapping.evidenceId)
            )
        }
    }

    @Test
    fun `GATE_MAPPING_COUNT equals AndroidReadinessEvidenceSurface EVIDENCE_ENTRY_COUNT`() {
        assertEquals(surface.EVIDENCE_ENTRY_COUNT, alignment.GATE_MAPPING_COUNT)
    }

    @Test
    fun `CANONICAL surface entries all have STRONG or ADVISORY authority in gate mappings`() {
        val canonicalIds = surface.evidenceAtLevel(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL
        ).map { it.evidenceId }.toSet()
        alignment.gateMappings
            .filter { it.evidenceId in canonicalIds }
            .forEach { mapping ->
                assertTrue(
                    "CANONICAL surface entry '${mapping.evidenceId}' has unexpected authority " +
                        "${mapping.evidenceAuthority} (expected STRONG_PARTICIPANT_RUNTIME or " +
                        "ADVISORY_OBSERVATION_ONLY)",
                    mapping.evidenceAuthority ==
                        AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME ||
                        mapping.evidenceAuthority ==
                        AndroidDistributedGateAlignment.EvidenceAuthority.ADVISORY_OBSERVATION_ONLY
                )
            }
    }

    @Test
    fun `DEPRECATED_COMPAT surface entries all have DEPRECATED_COMPATIBILITY authority`() {
        val deprecatedIds = surface.evidenceAtLevel(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.DEPRECATED_COMPAT
        ).map { it.evidenceId }.toSet()
        alignment.gateMappings
            .filter { it.evidenceId in deprecatedIds }
            .forEach { mapping ->
                assertEquals(
                    "DEPRECATED_COMPAT surface entry '${mapping.evidenceId}' should have " +
                        "DEPRECATED_COMPATIBILITY authority",
                    AndroidDistributedGateAlignment.EvidenceAuthority.DEPRECATED_COMPATIBILITY,
                    mapping.evidenceAuthority
                )
            }
    }

    // ── Specific evidence entries — spot-checks ───────────────────────────────

    @Test
    fun `readiness_evaluator_five_dimension_verdict maps to LIFECYCLE_RUNTIME_CORRECTNESS and STRONG`() {
        val mapping = alignment.mappingFor("readiness_evaluator_five_dimension_verdict")
        assertNotNull(mapping)
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            mapping!!.canonicalGateCategory
        )
        assertEquals(
            AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            mapping.evidenceAuthority
        )
    }

    @Test
    fun `acceptance_evaluator_six_dimension_graduation_verdict maps to LIFECYCLE and STRONG`() {
        val mapping = alignment.mappingFor("acceptance_evaluator_six_dimension_graduation_verdict")
        assertNotNull(mapping)
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            mapping!!.canonicalGateCategory
        )
        assertEquals(
            AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            mapping.evidenceAuthority
        )
    }

    @Test
    fun `strategy_evaluator_dispatch_verdict maps to LIFECYCLE and ADVISORY`() {
        val mapping = alignment.mappingFor("strategy_evaluator_dispatch_verdict")
        assertNotNull(mapping)
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            mapping!!.canonicalGateCategory
        )
        assertEquals(
            AndroidDistributedGateAlignment.EvidenceAuthority.ADVISORY_OBSERVATION_ONLY,
            mapping.evidenceAuthority
        )
    }

    @Test
    fun `takeover_fallback_event_canonical_bounding maps to TAKEOVER and STRONG`() {
        val mapping = alignment.mappingFor("takeover_fallback_event_canonical_bounding")
        assertNotNull(mapping)
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.TAKEOVER_EXECUTION_OUTCOMES,
            mapping!!.canonicalGateCategory
        )
        assertEquals(
            AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            mapping.evidenceAuthority
        )
    }

    @Test
    fun `takeover_recovery_path_compat_gate maps to TAKEOVER and ADVISORY`() {
        val mapping = alignment.mappingFor("takeover_recovery_path_compat_gate")
        assertNotNull(mapping)
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.TAKEOVER_EXECUTION_OUTCOMES,
            mapping!!.canonicalGateCategory
        )
        assertEquals(
            AndroidDistributedGateAlignment.EvidenceAuthority.ADVISORY_OBSERVATION_ONLY,
            mapping.evidenceAuthority
        )
    }

    @Test
    fun `device_readiness_artifact_wire_emission maps to RECONCILIATION and STRONG`() {
        val mapping = alignment.mappingFor("device_readiness_artifact_wire_emission")
        assertNotNull(mapping)
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            mapping!!.canonicalGateCategory
        )
        assertEquals(
            AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            mapping.evidenceAuthority
        )
    }

    @Test
    fun `long_tail_compat_registry_legacy_signals maps to COMPATIBILITY and DEPRECATED_COMPATIBILITY`() {
        val mapping = alignment.mappingFor("long_tail_compat_registry_legacy_signals")
        assertNotNull(mapping)
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.COMPATIBILITY_LEGACY_SUPPRESSION,
            mapping!!.canonicalGateCategory
        )
        assertEquals(
            AndroidDistributedGateAlignment.EvidenceAuthority.DEPRECATED_COMPATIBILITY,
            mapping.evidenceAuthority
        )
    }

    @Test
    fun `emitted_signal_ledger_terminal_bounding maps to SIGNAL_REPLAY and STRONG`() {
        val mapping = alignment.mappingFor("emitted_signal_ledger_terminal_bounding")
        assertNotNull(mapping)
        assertEquals(
            AndroidDistributedGateAlignment.CanonicalGateCategory.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            mapping!!.canonicalGateCategory
        )
        assertEquals(
            AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            mapping.evidenceAuthority
        )
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    @Test
    fun `mappingFor returns entry for known id`() {
        assertNotNull(alignment.mappingFor("readiness_evaluator_five_dimension_verdict"))
    }

    @Test
    fun `mappingFor returns null for unknown id`() {
        assertNull(alignment.mappingFor("no_such_evidence_id"))
    }

    @Test
    fun `mappingsForCategory returns only entries for that category`() {
        val category = AndroidDistributedGateAlignment.CanonicalGateCategory.CONTINUITY_RECOVERY_SAFETY
        val results = alignment.mappingsForCategory(category)
        assertTrue(results.isNotEmpty())
        results.forEach {
            assertEquals(category, it.canonicalGateCategory)
        }
    }

    @Test
    fun `mappingsForAuthority returns only entries for that authority`() {
        val authority = AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME
        val results = alignment.mappingsForAuthority(authority)
        assertTrue(results.isNotEmpty())
        results.forEach {
            assertEquals(authority, it.evidenceAuthority)
        }
    }

    @Test
    fun `categoryFor returns correct category for each dimension`() {
        alignment.dimensionToCategoryMap.forEach { (dim, expectedCategory) ->
            assertEquals(
                "categoryFor($dim) returned wrong category",
                expectedCategory,
                alignment.categoryFor(dim)
            )
        }
    }

    // ── DEPRECATED_COMPATIBILITY entries do not satisfy canonical gate ─────────

    @Test
    fun `long_tail_compat_registry_legacy_signals is DEPRECATED_COMPATIBILITY`() {
        val mapping = alignment.mappingFor("long_tail_compat_registry_legacy_signals")
        assertNotNull(mapping)
        assertEquals(
            AndroidDistributedGateAlignment.EvidenceAuthority.DEPRECATED_COMPATIBILITY,
            mapping!!.evidenceAuthority
        )
    }

    @Test
    fun `DEPRECATED_COMPATIBILITY entries are not counted in STRONG or ADVISORY totals`() {
        val strongCount = alignment.mappingsForAuthority(
            AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME
        ).size
        val advisoryCount = alignment.mappingsForAuthority(
            AndroidDistributedGateAlignment.EvidenceAuthority.ADVISORY_OBSERVATION_ONLY
        ).size
        val deprecatedCount = alignment.mappingsForAuthority(
            AndroidDistributedGateAlignment.EvidenceAuthority.DEPRECATED_COMPATIBILITY
        ).size

        assertEquals(alignment.STRONG_PARTICIPANT_RUNTIME_COUNT, strongCount)
        assertEquals(alignment.ADVISORY_OBSERVATION_ONLY_COUNT, advisoryCount)
        assertEquals(alignment.DEPRECATED_COMPATIBILITY_COUNT, deprecatedCount)
        // The three counts should sum correctly without overlap
        assertEquals(
            alignment.GATE_MAPPING_COUNT,
            strongCount + advisoryCount + deprecatedCount +
                alignment.INTENTIONALLY_LOCAL_DEFERRED_COUNT
        )
    }

    @Test
    fun `canonical gate coverage per category holds even without DEPRECATED_COMPATIBILITY entries`() {
        AndroidDistributedGateAlignment.CanonicalGateCategory.values().forEach { category ->
            val hasStrong = alignment.mappingsForCategory(category).any {
                it.evidenceAuthority ==
                    AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME
            }
            assertTrue(
                "Category $category has no STRONG_PARTICIPANT_RUNTIME entry even after " +
                    "excluding DEPRECATED_COMPATIBILITY entries",
                hasStrong
            )
        }
    }

    // ── AC4: V2 consumption — gateMappingNote references ─────────────────────

    @Test
    fun `all STRONG_PARTICIPANT_RUNTIME entries have non-blank gateMappingNote`() {
        alignment.mappingsForAuthority(
            AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME
        ).forEach {
            assertTrue(
                "gateMappingNote blank for STRONG entry ${it.evidenceId}",
                it.gateMappingNote.isNotBlank()
            )
        }
    }

    @Test
    fun `readiness evaluator mapping note references ReconciliationSignal`() {
        val mapping = alignment.mappingFor("readiness_evaluator_five_dimension_verdict")
        assertNotNull(mapping)
        assertTrue(
            "readiness evaluator gateMappingNote should reference ReconciliationSignal",
            mapping!!.gateMappingNote.contains("ReconciliationSignal")
        )
    }

    @Test
    fun `recovery participation owner mapping note references WaitForV2ReplayDecision`() {
        val mapping = alignment.mappingFor(
            "recovery_participation_owner_restart_reconnect_bounding"
        )
        assertNotNull(mapping)
        assertTrue(
            "recovery participation owner gateMappingNote should reference WaitForV2ReplayDecision",
            mapping!!.gateMappingNote.contains("WaitForV2ReplayDecision")
        )
    }

    @Test
    fun `compat legacy blocking mapping note references semanticTag`() {
        val mapping = alignment.mappingFor(
            "compat_legacy_blocking_participant_canonical_path_confirmation"
        )
        assertNotNull(mapping)
        assertTrue(
            "compat legacy blocking gateMappingNote should reference semanticTag",
            mapping!!.gateMappingNote.contains("semanticTag")
        )
    }
}
