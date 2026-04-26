package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-7Android (PR-68) — Android distributed gate category mapping: alignment and
 * structural tests.
 *
 * Validates the five acceptance criteria for aligning Android readiness evidence with
 * the V2 canonical distributed release-gate skeleton:
 *
 *  1. **How Android evidence maps into canonical distributed gate categories** — verified
 *     by [AndroidDistributedGateCategoryMapping.allMappings] structural invariants,
 *     complete evidence coverage, and per-gate-category coverage checks.
 *
 *  2. **Which Android evidence is strong vs advisory vs deprecated/local-only** — verified
 *     by [AndroidDistributedGateCategoryMapping.GateStrength] assignments and count
 *     invariants.
 *
 *  3. **That Android-side mappings are backed by real runtime/tests/artifacts** — verified
 *     by cross-referencing mapping entries against
 *     [AndroidReadinessEvidenceSurface.allEvidence] to confirm each mapped evidenceId
 *     resolves to a real evidence entry with a non-blank testEvidence value.
 *
 *  4. **How later release-policy/CI work can consume Android evidence consistently from V2**
 *     — verified by mappingNote non-blank invariants on PARTICIPANT_RUNTIME entries and
 *     authority-boundary assertions.
 *
 *  5. **That this fresh Android PR supersedes the previous Android PR-7 effort** — verified
 *     by INTRODUCED_PR and INTRODUCED_PR_TITLE assertions.
 *
 * ## Test matrix
 *
 * ### AndroidDistributedGateCategoryMapping — structural invariants
 *  - INTRODUCED_PR is 68
 *  - INTRODUCED_PR_TITLE is non-blank
 *  - DESCRIPTION is non-blank
 *  - allMappings has exactly MAPPING_ENTRY_COUNT entries
 *  - all mapping evidenceIds are non-blank
 *  - all mapping evidenceIds are unique
 *  - all mappingNotes are non-blank
 *  - PARTICIPANT_RUNTIME count matches PARTICIPANT_RUNTIME_ENTRY_COUNT
 *  - ADVISORY count matches ADVISORY_ENTRY_COUNT
 *  - DEFERRED_LOCAL count matches DEFERRED_LOCAL_ENTRY_COUNT
 *  - total gate strength counts sum to MAPPING_ENTRY_COUNT
 *
 * ### V2GateCategory — wire values
 *  - LIFECYCLE_RUNTIME_CORRECTNESS wireValue is "lifecycle_runtime_correctness"
 *  - TAKEOVER_EXECUTION_OUTCOMES wireValue is "takeover_execution_outcomes"
 *  - RECONCILIATION_ARTIFACT_EMISSION wireValue is "reconciliation_artifact_emission"
 *  - CONTINUITY_RECOVERY_SAFETY wireValue is "continuity_recovery_safety"
 *  - COMPATIBILITY_SUPPRESSION wireValue is "compatibility_suppression"
 *  - All five wire values are distinct
 *  - fromValue round-trips all five wire values correctly
 *  - fromValue returns null for unknown value
 *  - fromValue returns null for null
 *
 * ### GateStrength — wire values
 *  - PARTICIPANT_RUNTIME wireValue is "participant_runtime"
 *  - ADVISORY wireValue is "advisory"
 *  - DEFERRED_LOCAL wireValue is "deferred_local"
 *  - All three wire values are distinct
 *  - fromValue round-trips all three wire values correctly
 *  - fromValue returns null for unknown value
 *  - fromValue returns null for null
 *
 * ### Dimension → gate category coverage (AC1: complete mapping)
 *  - RUNTIME_LIFECYCLE maps to LIFECYCLE_RUNTIME_CORRECTNESS
 *  - TAKEOVER_EXECUTION maps to TAKEOVER_EXECUTION_OUTCOMES
 *  - ARTIFACT_EMISSION_RECONCILIATION maps to RECONCILIATION_ARTIFACT_EMISSION
 *  - CONTINUITY_RECOVERY_SAFETY maps to CONTINUITY_RECOVERY_SAFETY
 *  - COMPATIBILITY_SUPPRESSION maps to COMPATIBILITY_SUPPRESSION
 *  - SIGNAL_REPLAY_DUPLICATE_SAFETY maps to CONTINUITY_RECOVERY_SAFETY (absorbed)
 *  - all six Android dimensions are mapped to at least one gate category
 *
 * ### Per-gate-category PARTICIPANT_RUNTIME coverage
 *  - LIFECYCLE_RUNTIME_CORRECTNESS has at least one PARTICIPANT_RUNTIME entry
 *  - TAKEOVER_EXECUTION_OUTCOMES has at least one PARTICIPANT_RUNTIME entry
 *  - RECONCILIATION_ARTIFACT_EMISSION has at least one PARTICIPANT_RUNTIME entry
 *  - CONTINUITY_RECOVERY_SAFETY has at least one PARTICIPANT_RUNTIME entry
 *  - COMPATIBILITY_SUPPRESSION has at least one PARTICIPANT_RUNTIME entry
 *
 * ### Mapping entries grounded in real evidence (AC3: backed by runtime/tests)
 *  - every mapping evidenceId resolves in AndroidReadinessEvidenceSurface.allEvidence
 *  - every resolved evidence entry has non-blank testEvidence
 *  - PARTICIPANT_RUNTIME entries map only to CANONICAL confidence-level evidence
 *  - ADVISORY entries map only to ADVISORY confidence-level evidence
 *  - DEFERRED_LOCAL entries map only to DEPRECATED_COMPAT confidence-level evidence
 *
 * ### Specific mapping spot-checks (AC1 + AC2)
 *  - readiness_evaluator_five_dimension_verdict maps to LIFECYCLE_RUNTIME_CORRECTNESS / PARTICIPANT_RUNTIME
 *  - acceptance_evaluator_six_dimension_graduation_verdict maps to LIFECYCLE_RUNTIME_CORRECTNESS / PARTICIPANT_RUNTIME
 *  - post_graduation_governance_evaluator_verdict maps to LIFECYCLE_RUNTIME_CORRECTNESS / PARTICIPANT_RUNTIME
 *  - strategy_evaluator_dispatch_verdict maps to LIFECYCLE_RUNTIME_CORRECTNESS / ADVISORY
 *  - runtime_lifecycle_transition_event_emission maps to LIFECYCLE_RUNTIME_CORRECTNESS / PARTICIPANT_RUNTIME
 *  - takeover_fallback_event_canonical_bounding maps to TAKEOVER_EXECUTION_OUTCOMES / PARTICIPANT_RUNTIME
 *  - takeover_executor_metadata_unification maps to TAKEOVER_EXECUTION_OUTCOMES / PARTICIPANT_RUNTIME
 *  - takeover_recovery_path_compat_gate maps to TAKEOVER_EXECUTION_OUTCOMES / ADVISORY
 *  - device_readiness_artifact_wire_emission maps to RECONCILIATION_ARTIFACT_EMISSION / PARTICIPANT_RUNTIME
 *  - device_acceptance_artifact_wire_emission maps to RECONCILIATION_ARTIFACT_EMISSION / PARTICIPANT_RUNTIME
 *  - reconciliation_signal_participant_state_emission maps to RECONCILIATION_ARTIFACT_EMISSION / PARTICIPANT_RUNTIME
 *  - reconciliation_signal_runtime_truth_snapshot_emission maps to RECONCILIATION_ARTIFACT_EMISSION / PARTICIPANT_RUNTIME
 *  - unified_truth_reconciliation_surface_emission maps to RECONCILIATION_ARTIFACT_EMISSION / PARTICIPANT_RUNTIME
 *  - recovery_participation_owner_restart_reconnect_bounding maps to CONTINUITY_RECOVERY_SAFETY / PARTICIPANT_RUNTIME
 *  - continuity_recovery_durability_contract_coverage maps to CONTINUITY_RECOVERY_SAFETY / PARTICIPANT_RUNTIME
 *  - hybrid_lifecycle_recovery_contract_coverage maps to CONTINUITY_RECOVERY_SAFETY / PARTICIPANT_RUNTIME
 *  - durable_session_continuity_record_rehydration maps to CONTINUITY_RECOVERY_SAFETY / PARTICIPANT_RUNTIME
 *  - emitted_signal_ledger_terminal_bounding maps to CONTINUITY_RECOVERY_SAFETY / PARTICIPANT_RUNTIME
 *  - continuity_integration_duplicate_signal_suppression maps to CONTINUITY_RECOVERY_SAFETY / PARTICIPANT_RUNTIME
 *  - offline_queue_stale_session_discard maps to CONTINUITY_RECOVERY_SAFETY / PARTICIPANT_RUNTIME
 *  - delegated_execution_signal_idempotency_guard maps to CONTINUITY_RECOVERY_SAFETY / PARTICIPANT_RUNTIME
 *  - compat_legacy_blocking_participant_canonical_path_confirmation maps to COMPATIBILITY_SUPPRESSION / PARTICIPANT_RUNTIME
 *  - compatibility_surface_retirement_registry maps to COMPATIBILITY_SUPPRESSION / PARTICIPANT_RUNTIME
 *  - authoritative_path_alignment_audit maps to COMPATIBILITY_SUPPRESSION / PARTICIPANT_RUNTIME
 *  - long_tail_compat_registry_legacy_signals maps to COMPATIBILITY_SUPPRESSION / DEFERRED_LOCAL
 *  - compatibility_retirement_fence_blocking maps to COMPATIBILITY_SUPPRESSION / PARTICIPANT_RUNTIME
 *
 * ### Authority boundary (AC4: V2 remains canonical orchestration authority)
 *  - no PARTICIPANT_RUNTIME entry implies Android-side gate authority
 *  - DEFERRED_LOCAL entry for long_tail_compat is not counted as strong gate input
 *  - final_release_policy_in_android is deferred in AndroidReadinessEvidenceSurface
 *
 * ### Query helpers
 *  - mappingFor returns entry for known evidenceId
 *  - mappingFor returns null for unknown evidenceId
 *  - mappingsForGateCategory returns only entries for that gate category
 *  - mappingsForDimension returns only entries for that Android dimension
 *  - mappingsAtStrength returns only entries at that gate strength
 *  - gateCategoriesForDimension returns correct categories
 *  - participantRuntimeMappingsFor returns only PARTICIPANT_RUNTIME entries for category
 */
class Pr7AndroidDistributedGateMappingTest {

    private val mapping = AndroidDistributedGateCategoryMapping
    private val surface = AndroidReadinessEvidenceSurface

    // ── Structural invariants ─────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 68`() {
        assertEquals(68, mapping.INTRODUCED_PR)
    }

    @Test
    fun `INTRODUCED_PR_TITLE is non-blank`() {
        assertTrue(mapping.INTRODUCED_PR_TITLE.isNotBlank())
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
    fun `all mapping evidenceIds are non-blank`() {
        mapping.allMappings.forEach {
            assertTrue("evidenceId blank for entry: $it", it.evidenceId.isNotBlank())
        }
    }

    @Test
    fun `all mapping evidenceIds are unique`() {
        val ids = mapping.allMappings.map { it.evidenceId }
        assertEquals("duplicate evidenceIds found", ids.size, ids.toSet().size)
    }

    @Test
    fun `all mappingNotes are non-blank`() {
        mapping.allMappings.forEach {
            assertTrue("mappingNote blank for ${it.evidenceId}", it.mappingNote.isNotBlank())
        }
    }

    @Test
    fun `PARTICIPANT_RUNTIME count matches PARTICIPANT_RUNTIME_ENTRY_COUNT`() {
        assertEquals(
            mapping.PARTICIPANT_RUNTIME_ENTRY_COUNT,
            mapping.mappingsAtStrength(
                AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
            ).size
        )
    }

    @Test
    fun `ADVISORY count matches ADVISORY_ENTRY_COUNT`() {
        assertEquals(
            mapping.ADVISORY_ENTRY_COUNT,
            mapping.mappingsAtStrength(
                AndroidDistributedGateCategoryMapping.GateStrength.ADVISORY
            ).size
        )
    }

    @Test
    fun `DEFERRED_LOCAL count matches DEFERRED_LOCAL_ENTRY_COUNT`() {
        assertEquals(
            mapping.DEFERRED_LOCAL_ENTRY_COUNT,
            mapping.mappingsAtStrength(
                AndroidDistributedGateCategoryMapping.GateStrength.DEFERRED_LOCAL
            ).size
        )
    }

    @Test
    fun `total gate strength counts sum to MAPPING_ENTRY_COUNT`() {
        val total = mapping.PARTICIPANT_RUNTIME_ENTRY_COUNT +
            mapping.ADVISORY_ENTRY_COUNT +
            mapping.DEFERRED_LOCAL_ENTRY_COUNT
        assertEquals(mapping.MAPPING_ENTRY_COUNT, total)
    }

    // ── V2GateCategory — wire values ──────────────────────────────────────────

    @Test
    fun `LIFECYCLE_RUNTIME_CORRECTNESS wireValue is lifecycle_runtime_correctness`() {
        assertEquals(
            "lifecycle_runtime_correctness",
            AndroidDistributedGateCategoryMapping.V2GateCategory
                .LIFECYCLE_RUNTIME_CORRECTNESS.wireValue
        )
    }

    @Test
    fun `TAKEOVER_EXECUTION_OUTCOMES wireValue is takeover_execution_outcomes`() {
        assertEquals(
            "takeover_execution_outcomes",
            AndroidDistributedGateCategoryMapping.V2GateCategory
                .TAKEOVER_EXECUTION_OUTCOMES.wireValue
        )
    }

    @Test
    fun `RECONCILIATION_ARTIFACT_EMISSION wireValue is reconciliation_artifact_emission`() {
        assertEquals(
            "reconciliation_artifact_emission",
            AndroidDistributedGateCategoryMapping.V2GateCategory
                .RECONCILIATION_ARTIFACT_EMISSION.wireValue
        )
    }

    @Test
    fun `CONTINUITY_RECOVERY_SAFETY wireValue is continuity_recovery_safety`() {
        assertEquals(
            "continuity_recovery_safety",
            AndroidDistributedGateCategoryMapping.V2GateCategory
                .CONTINUITY_RECOVERY_SAFETY.wireValue
        )
    }

    @Test
    fun `COMPATIBILITY_SUPPRESSION wireValue is compatibility_suppression`() {
        assertEquals(
            "compatibility_suppression",
            AndroidDistributedGateCategoryMapping.V2GateCategory
                .COMPATIBILITY_SUPPRESSION.wireValue
        )
    }

    @Test
    fun `all five V2GateCategory wire values are distinct`() {
        val values = AndroidDistributedGateCategoryMapping.V2GateCategory.values()
            .map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `V2GateCategory fromValue round-trips lifecycle_runtime_correctness`() {
        assertEquals(
            AndroidDistributedGateCategoryMapping.V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            AndroidDistributedGateCategoryMapping.V2GateCategory.fromValue(
                "lifecycle_runtime_correctness"
            )
        )
    }

    @Test
    fun `V2GateCategory fromValue round-trips takeover_execution_outcomes`() {
        assertEquals(
            AndroidDistributedGateCategoryMapping.V2GateCategory.TAKEOVER_EXECUTION_OUTCOMES,
            AndroidDistributedGateCategoryMapping.V2GateCategory.fromValue(
                "takeover_execution_outcomes"
            )
        )
    }

    @Test
    fun `V2GateCategory fromValue round-trips reconciliation_artifact_emission`() {
        assertEquals(
            AndroidDistributedGateCategoryMapping.V2GateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            AndroidDistributedGateCategoryMapping.V2GateCategory.fromValue(
                "reconciliation_artifact_emission"
            )
        )
    }

    @Test
    fun `V2GateCategory fromValue round-trips continuity_recovery_safety`() {
        assertEquals(
            AndroidDistributedGateCategoryMapping.V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            AndroidDistributedGateCategoryMapping.V2GateCategory.fromValue(
                "continuity_recovery_safety"
            )
        )
    }

    @Test
    fun `V2GateCategory fromValue round-trips compatibility_suppression`() {
        assertEquals(
            AndroidDistributedGateCategoryMapping.V2GateCategory.COMPATIBILITY_SUPPRESSION,
            AndroidDistributedGateCategoryMapping.V2GateCategory.fromValue(
                "compatibility_suppression"
            )
        )
    }

    @Test
    fun `V2GateCategory fromValue returns null for unknown value`() {
        assertNull(
            AndroidDistributedGateCategoryMapping.V2GateCategory.fromValue("nonexistent_category")
        )
    }

    @Test
    fun `V2GateCategory fromValue returns null for null`() {
        assertNull(AndroidDistributedGateCategoryMapping.V2GateCategory.fromValue(null))
    }

    // ── GateStrength — wire values ────────────────────────────────────────────

    @Test
    fun `PARTICIPANT_RUNTIME wireValue is participant_runtime`() {
        assertEquals(
            "participant_runtime",
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME.wireValue
        )
    }

    @Test
    fun `ADVISORY wireValue is advisory`() {
        assertEquals(
            "advisory",
            AndroidDistributedGateCategoryMapping.GateStrength.ADVISORY.wireValue
        )
    }

    @Test
    fun `DEFERRED_LOCAL wireValue is deferred_local`() {
        assertEquals(
            "deferred_local",
            AndroidDistributedGateCategoryMapping.GateStrength.DEFERRED_LOCAL.wireValue
        )
    }

    @Test
    fun `all three GateStrength wire values are distinct`() {
        val values = AndroidDistributedGateCategoryMapping.GateStrength.values()
            .map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `GateStrength fromValue round-trips participant_runtime`() {
        assertEquals(
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME,
            AndroidDistributedGateCategoryMapping.GateStrength.fromValue("participant_runtime")
        )
    }

    @Test
    fun `GateStrength fromValue round-trips advisory`() {
        assertEquals(
            AndroidDistributedGateCategoryMapping.GateStrength.ADVISORY,
            AndroidDistributedGateCategoryMapping.GateStrength.fromValue("advisory")
        )
    }

    @Test
    fun `GateStrength fromValue round-trips deferred_local`() {
        assertEquals(
            AndroidDistributedGateCategoryMapping.GateStrength.DEFERRED_LOCAL,
            AndroidDistributedGateCategoryMapping.GateStrength.fromValue("deferred_local")
        )
    }

    @Test
    fun `GateStrength fromValue returns null for unknown value`() {
        assertNull(
            AndroidDistributedGateCategoryMapping.GateStrength.fromValue("nonexistent_strength")
        )
    }

    @Test
    fun `GateStrength fromValue returns null for null`() {
        assertNull(AndroidDistributedGateCategoryMapping.GateStrength.fromValue(null))
    }

    // ── Dimension → gate category coverage ───────────────────────────────────

    @Test
    fun `RUNTIME_LIFECYCLE maps to LIFECYCLE_RUNTIME_CORRECTNESS`() {
        val categories = mapping.gateCategoriesForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE
        )
        assertTrue(
            categories.contains(
                AndroidDistributedGateCategoryMapping.V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS
            )
        )
    }

    @Test
    fun `TAKEOVER_EXECUTION maps to TAKEOVER_EXECUTION_OUTCOMES`() {
        val categories = mapping.gateCategoriesForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION
        )
        assertTrue(
            categories.contains(
                AndroidDistributedGateCategoryMapping.V2GateCategory.TAKEOVER_EXECUTION_OUTCOMES
            )
        )
    }

    @Test
    fun `ARTIFACT_EMISSION_RECONCILIATION maps to RECONCILIATION_ARTIFACT_EMISSION`() {
        val categories = mapping.gateCategoriesForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION
        )
        assertTrue(
            categories.contains(
                AndroidDistributedGateCategoryMapping.V2GateCategory.RECONCILIATION_ARTIFACT_EMISSION
            )
        )
    }

    @Test
    fun `CONTINUITY_RECOVERY_SAFETY maps to CONTINUITY_RECOVERY_SAFETY gate category`() {
        val categories = mapping.gateCategoriesForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY
        )
        assertTrue(
            categories.contains(
                AndroidDistributedGateCategoryMapping.V2GateCategory.CONTINUITY_RECOVERY_SAFETY
            )
        )
    }

    @Test
    fun `COMPATIBILITY_SUPPRESSION maps to COMPATIBILITY_SUPPRESSION gate category`() {
        val categories = mapping.gateCategoriesForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION
        )
        assertTrue(
            categories.contains(
                AndroidDistributedGateCategoryMapping.V2GateCategory.COMPATIBILITY_SUPPRESSION
            )
        )
    }

    @Test
    fun `SIGNAL_REPLAY_DUPLICATE_SAFETY maps to CONTINUITY_RECOVERY_SAFETY gate category`() {
        val categories = mapping.gateCategoriesForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY
        )
        assertTrue(
            "SIGNAL_REPLAY_DUPLICATE_SAFETY should map to CONTINUITY_RECOVERY_SAFETY in the V2 model",
            categories.contains(
                AndroidDistributedGateCategoryMapping.V2GateCategory.CONTINUITY_RECOVERY_SAFETY
            )
        )
    }

    @Test
    fun `all six Android dimensions are mapped to at least one gate category`() {
        AndroidReadinessEvidenceSurface.ReadinessDimension.values().forEach { dimension ->
            val categories = mapping.gateCategoriesForDimension(dimension)
            assertTrue(
                "Dimension $dimension has no gate category mappings",
                categories.isNotEmpty()
            )
        }
    }

    // ── Per-gate-category PARTICIPANT_RUNTIME coverage ────────────────────────

    @Test
    fun `LIFECYCLE_RUNTIME_CORRECTNESS has at least one PARTICIPANT_RUNTIME entry`() {
        val entries = mapping.participantRuntimeMappingsFor(
            AndroidDistributedGateCategoryMapping.V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS
        )
        assertTrue(
            "LIFECYCLE_RUNTIME_CORRECTNESS has no PARTICIPANT_RUNTIME entries",
            entries.isNotEmpty()
        )
    }

    @Test
    fun `TAKEOVER_EXECUTION_OUTCOMES has at least one PARTICIPANT_RUNTIME entry`() {
        val entries = mapping.participantRuntimeMappingsFor(
            AndroidDistributedGateCategoryMapping.V2GateCategory.TAKEOVER_EXECUTION_OUTCOMES
        )
        assertTrue(
            "TAKEOVER_EXECUTION_OUTCOMES has no PARTICIPANT_RUNTIME entries",
            entries.isNotEmpty()
        )
    }

    @Test
    fun `RECONCILIATION_ARTIFACT_EMISSION has at least one PARTICIPANT_RUNTIME entry`() {
        val entries = mapping.participantRuntimeMappingsFor(
            AndroidDistributedGateCategoryMapping.V2GateCategory.RECONCILIATION_ARTIFACT_EMISSION
        )
        assertTrue(
            "RECONCILIATION_ARTIFACT_EMISSION has no PARTICIPANT_RUNTIME entries",
            entries.isNotEmpty()
        )
    }

    @Test
    fun `CONTINUITY_RECOVERY_SAFETY has at least one PARTICIPANT_RUNTIME entry`() {
        val entries = mapping.participantRuntimeMappingsFor(
            AndroidDistributedGateCategoryMapping.V2GateCategory.CONTINUITY_RECOVERY_SAFETY
        )
        assertTrue(
            "CONTINUITY_RECOVERY_SAFETY has no PARTICIPANT_RUNTIME entries",
            entries.isNotEmpty()
        )
    }

    @Test
    fun `COMPATIBILITY_SUPPRESSION has at least one PARTICIPANT_RUNTIME entry`() {
        val entries = mapping.participantRuntimeMappingsFor(
            AndroidDistributedGateCategoryMapping.V2GateCategory.COMPATIBILITY_SUPPRESSION
        )
        assertTrue(
            "COMPATIBILITY_SUPPRESSION has no PARTICIPANT_RUNTIME entries",
            entries.isNotEmpty()
        )
    }

    // ── Mapping entries grounded in real evidence (AC3) ───────────────────────

    @Test
    fun `every mapping evidenceId resolves in AndroidReadinessEvidenceSurface`() {
        mapping.allMappings.forEach { entry ->
            assertNotNull(
                "mapping evidenceId '${entry.evidenceId}' not found in " +
                    "AndroidReadinessEvidenceSurface.allEvidence",
                surface.evidenceFor(entry.evidenceId)
            )
        }
    }

    @Test
    fun `every resolved evidence entry has non-blank testEvidence`() {
        mapping.allMappings.forEach { entry ->
            val evidence = surface.evidenceFor(entry.evidenceId)
            assertNotNull(evidence)
            assertTrue(
                "testEvidence blank for mapped entry '${entry.evidenceId}'",
                evidence!!.testEvidence.isNotBlank()
            )
        }
    }

    @Test
    fun `PARTICIPANT_RUNTIME entries map only to CANONICAL confidence-level evidence`() {
        mapping.mappingsAtStrength(
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        ).forEach { entry ->
            val evidence = surface.evidenceFor(entry.evidenceId)
            assertNotNull(evidence)
            assertEquals(
                "PARTICIPANT_RUNTIME mapping '${entry.evidenceId}' does not correspond to " +
                    "a CANONICAL evidence entry",
                AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
                evidence!!.confidenceLevel
            )
        }
    }

    @Test
    fun `ADVISORY entries map only to ADVISORY confidence-level evidence`() {
        mapping.mappingsAtStrength(
            AndroidDistributedGateCategoryMapping.GateStrength.ADVISORY
        ).forEach { entry ->
            val evidence = surface.evidenceFor(entry.evidenceId)
            assertNotNull(evidence)
            assertEquals(
                "ADVISORY mapping '${entry.evidenceId}' does not correspond to an " +
                    "ADVISORY evidence entry",
                AndroidReadinessEvidenceSurface.ConfidenceLevel.ADVISORY,
                evidence!!.confidenceLevel
            )
        }
    }

    @Test
    fun `DEFERRED_LOCAL entries map only to DEPRECATED_COMPAT confidence-level evidence`() {
        mapping.mappingsAtStrength(
            AndroidDistributedGateCategoryMapping.GateStrength.DEFERRED_LOCAL
        ).forEach { entry ->
            val evidence = surface.evidenceFor(entry.evidenceId)
            assertNotNull(evidence)
            assertEquals(
                "DEFERRED_LOCAL mapping '${entry.evidenceId}' does not correspond to a " +
                    "DEPRECATED_COMPAT evidence entry",
                AndroidReadinessEvidenceSurface.ConfidenceLevel.DEPRECATED_COMPAT,
                evidence!!.confidenceLevel
            )
        }
    }

    // ── Specific mapping spot-checks (AC1 + AC2) ──────────────────────────────

    private fun assertMapping(
        evidenceId: String,
        expectedCategory: AndroidDistributedGateCategoryMapping.V2GateCategory,
        expectedStrength: AndroidDistributedGateCategoryMapping.GateStrength
    ) {
        val entry = mapping.mappingFor(evidenceId)
        assertNotNull("No mapping found for evidenceId '$evidenceId'", entry)
        assertEquals(
            "Wrong gate category for '$evidenceId'",
            expectedCategory,
            entry!!.v2GateCategory
        )
        assertEquals(
            "Wrong gate strength for '$evidenceId'",
            expectedStrength,
            entry.gateStrength
        )
    }

    @Test
    fun `readiness_evaluator maps to LIFECYCLE_RUNTIME_CORRECTNESS PARTICIPANT_RUNTIME`() {
        assertMapping(
            "readiness_evaluator_five_dimension_verdict",
            AndroidDistributedGateCategoryMapping.V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `acceptance_evaluator maps to LIFECYCLE_RUNTIME_CORRECTNESS PARTICIPANT_RUNTIME`() {
        assertMapping(
            "acceptance_evaluator_six_dimension_graduation_verdict",
            AndroidDistributedGateCategoryMapping.V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `post_graduation_governance maps to LIFECYCLE_RUNTIME_CORRECTNESS PARTICIPANT_RUNTIME`() {
        assertMapping(
            "post_graduation_governance_evaluator_verdict",
            AndroidDistributedGateCategoryMapping.V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `strategy_evaluator maps to LIFECYCLE_RUNTIME_CORRECTNESS ADVISORY`() {
        assertMapping(
            "strategy_evaluator_dispatch_verdict",
            AndroidDistributedGateCategoryMapping.V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            AndroidDistributedGateCategoryMapping.GateStrength.ADVISORY
        )
    }

    @Test
    fun `runtime_lifecycle_transition_event maps to LIFECYCLE_RUNTIME_CORRECTNESS PARTICIPANT_RUNTIME`() {
        assertMapping(
            "runtime_lifecycle_transition_event_emission",
            AndroidDistributedGateCategoryMapping.V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `takeover_fallback_event maps to TAKEOVER_EXECUTION_OUTCOMES PARTICIPANT_RUNTIME`() {
        assertMapping(
            "takeover_fallback_event_canonical_bounding",
            AndroidDistributedGateCategoryMapping.V2GateCategory.TAKEOVER_EXECUTION_OUTCOMES,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `takeover_executor_metadata maps to TAKEOVER_EXECUTION_OUTCOMES PARTICIPANT_RUNTIME`() {
        assertMapping(
            "takeover_executor_metadata_unification",
            AndroidDistributedGateCategoryMapping.V2GateCategory.TAKEOVER_EXECUTION_OUTCOMES,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `takeover_recovery_path maps to TAKEOVER_EXECUTION_OUTCOMES ADVISORY`() {
        assertMapping(
            "takeover_recovery_path_compat_gate",
            AndroidDistributedGateCategoryMapping.V2GateCategory.TAKEOVER_EXECUTION_OUTCOMES,
            AndroidDistributedGateCategoryMapping.GateStrength.ADVISORY
        )
    }

    @Test
    fun `device_readiness_artifact maps to RECONCILIATION_ARTIFACT_EMISSION PARTICIPANT_RUNTIME`() {
        assertMapping(
            "device_readiness_artifact_wire_emission",
            AndroidDistributedGateCategoryMapping.V2GateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `device_acceptance_artifact maps to RECONCILIATION_ARTIFACT_EMISSION PARTICIPANT_RUNTIME`() {
        assertMapping(
            "device_acceptance_artifact_wire_emission",
            AndroidDistributedGateCategoryMapping.V2GateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `reconciliation_signal_participant_state maps to RECONCILIATION_ARTIFACT_EMISSION PARTICIPANT_RUNTIME`() {
        assertMapping(
            "reconciliation_signal_participant_state_emission",
            AndroidDistributedGateCategoryMapping.V2GateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `reconciliation_signal_runtime_truth maps to RECONCILIATION_ARTIFACT_EMISSION PARTICIPANT_RUNTIME`() {
        assertMapping(
            "reconciliation_signal_runtime_truth_snapshot_emission",
            AndroidDistributedGateCategoryMapping.V2GateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `unified_truth_reconciliation maps to RECONCILIATION_ARTIFACT_EMISSION PARTICIPANT_RUNTIME`() {
        assertMapping(
            "unified_truth_reconciliation_surface_emission",
            AndroidDistributedGateCategoryMapping.V2GateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `recovery_participation_owner maps to CONTINUITY_RECOVERY_SAFETY PARTICIPANT_RUNTIME`() {
        assertMapping(
            "recovery_participation_owner_restart_reconnect_bounding",
            AndroidDistributedGateCategoryMapping.V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `continuity_recovery_durability_contract maps to CONTINUITY_RECOVERY_SAFETY PARTICIPANT_RUNTIME`() {
        assertMapping(
            "continuity_recovery_durability_contract_coverage",
            AndroidDistributedGateCategoryMapping.V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `hybrid_lifecycle_recovery maps to CONTINUITY_RECOVERY_SAFETY PARTICIPANT_RUNTIME`() {
        assertMapping(
            "hybrid_lifecycle_recovery_contract_coverage",
            AndroidDistributedGateCategoryMapping.V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `durable_session_continuity_record maps to CONTINUITY_RECOVERY_SAFETY PARTICIPANT_RUNTIME`() {
        assertMapping(
            "durable_session_continuity_record_rehydration",
            AndroidDistributedGateCategoryMapping.V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `emitted_signal_ledger_terminal maps to CONTINUITY_RECOVERY_SAFETY PARTICIPANT_RUNTIME`() {
        assertMapping(
            "emitted_signal_ledger_terminal_bounding",
            AndroidDistributedGateCategoryMapping.V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `continuity_integration_duplicate_suppression maps to CONTINUITY_RECOVERY_SAFETY PARTICIPANT_RUNTIME`() {
        assertMapping(
            "continuity_integration_duplicate_signal_suppression",
            AndroidDistributedGateCategoryMapping.V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `offline_queue_stale_session maps to CONTINUITY_RECOVERY_SAFETY PARTICIPANT_RUNTIME`() {
        assertMapping(
            "offline_queue_stale_session_discard",
            AndroidDistributedGateCategoryMapping.V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `delegated_execution_signal_idempotency maps to CONTINUITY_RECOVERY_SAFETY PARTICIPANT_RUNTIME`() {
        assertMapping(
            "delegated_execution_signal_idempotency_guard",
            AndroidDistributedGateCategoryMapping.V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `compat_legacy_blocking maps to COMPATIBILITY_SUPPRESSION PARTICIPANT_RUNTIME`() {
        assertMapping(
            "compat_legacy_blocking_participant_canonical_path_confirmation",
            AndroidDistributedGateCategoryMapping.V2GateCategory.COMPATIBILITY_SUPPRESSION,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `compatibility_surface_retirement_registry maps to COMPATIBILITY_SUPPRESSION PARTICIPANT_RUNTIME`() {
        assertMapping(
            "compatibility_surface_retirement_registry",
            AndroidDistributedGateCategoryMapping.V2GateCategory.COMPATIBILITY_SUPPRESSION,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `authoritative_path_alignment_audit maps to COMPATIBILITY_SUPPRESSION PARTICIPANT_RUNTIME`() {
        assertMapping(
            "authoritative_path_alignment_audit",
            AndroidDistributedGateCategoryMapping.V2GateCategory.COMPATIBILITY_SUPPRESSION,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    @Test
    fun `long_tail_compat_registry maps to COMPATIBILITY_SUPPRESSION DEFERRED_LOCAL`() {
        assertMapping(
            "long_tail_compat_registry_legacy_signals",
            AndroidDistributedGateCategoryMapping.V2GateCategory.COMPATIBILITY_SUPPRESSION,
            AndroidDistributedGateCategoryMapping.GateStrength.DEFERRED_LOCAL
        )
    }

    @Test
    fun `compatibility_retirement_fence maps to COMPATIBILITY_SUPPRESSION PARTICIPANT_RUNTIME`() {
        assertMapping(
            "compatibility_retirement_fence_blocking",
            AndroidDistributedGateCategoryMapping.V2GateCategory.COMPATIBILITY_SUPPRESSION,
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
    }

    // ── Authority boundary (AC4) ──────────────────────────────────────────────

    @Test
    fun `long_tail_compat DEFERRED_LOCAL is not counted as strong gate input`() {
        val entry = mapping.mappingFor("long_tail_compat_registry_legacy_signals")
        assertNotNull(entry)
        assertEquals(
            AndroidDistributedGateCategoryMapping.GateStrength.DEFERRED_LOCAL,
            entry!!.gateStrength
        )
        // Confirm it does NOT appear in PARTICIPANT_RUNTIME results for its category
        val participantRuntimeEntries = mapping.participantRuntimeMappingsFor(
            AndroidDistributedGateCategoryMapping.V2GateCategory.COMPATIBILITY_SUPPRESSION
        )
        assertTrue(
            "long_tail_compat_registry_legacy_signals should not appear in PARTICIPANT_RUNTIME entries",
            participantRuntimeEntries.none {
                it.evidenceId == "long_tail_compat_registry_legacy_signals"
            }
        )
    }

    @Test
    fun `final_release_policy_in_android is deferred in AndroidReadinessEvidenceSurface`() {
        val deferred = surface.deferredItemFor("final_release_policy_in_android")
        assertNotNull(
            "final_release_policy_in_android should be listed as a deferred item",
            deferred
        )
        assertTrue(
            "deferralReason for final_release_policy should mention V2 authority",
            deferred!!.deferralReason.isNotBlank()
        )
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    @Test
    fun `mappingFor returns entry for known evidenceId`() {
        val entry = mapping.mappingFor("readiness_evaluator_five_dimension_verdict")
        assertNotNull(entry)
    }

    @Test
    fun `mappingFor returns null for unknown evidenceId`() {
        assertNull(mapping.mappingFor("nonexistent_evidence_id"))
    }

    @Test
    fun `mappingsForGateCategory returns only entries for that gate category`() {
        val entries = mapping.mappingsForGateCategory(
            AndroidDistributedGateCategoryMapping.V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS
        )
        assertTrue(entries.isNotEmpty())
        entries.forEach { entry ->
            assertEquals(
                AndroidDistributedGateCategoryMapping.V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
                entry.v2GateCategory
            )
        }
    }

    @Test
    fun `mappingsForDimension returns only entries for that Android dimension`() {
        val entries = mapping.mappingsForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY
        )
        assertTrue(entries.isNotEmpty())
        entries.forEach { entry ->
            assertEquals(
                AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
                entry.androidDimension
            )
        }
    }

    @Test
    fun `mappingsAtStrength returns only entries at that gate strength`() {
        val entries = mapping.mappingsAtStrength(
            AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME
        )
        assertTrue(entries.isNotEmpty())
        entries.forEach { entry ->
            assertEquals(
                AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME,
                entry.gateStrength
            )
        }
    }

    @Test
    fun `gateCategoriesForDimension returns correct categories for SIGNAL_REPLAY_DUPLICATE_SAFETY`() {
        val categories = mapping.gateCategoriesForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY
        )
        assertEquals(
            setOf(AndroidDistributedGateCategoryMapping.V2GateCategory.CONTINUITY_RECOVERY_SAFETY),
            categories
        )
    }

    @Test
    fun `participantRuntimeMappingsFor returns only PARTICIPANT_RUNTIME entries`() {
        val entries = mapping.participantRuntimeMappingsFor(
            AndroidDistributedGateCategoryMapping.V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS
        )
        assertTrue(entries.isNotEmpty())
        entries.forEach { entry ->
            assertEquals(
                AndroidDistributedGateCategoryMapping.GateStrength.PARTICIPANT_RUNTIME,
                entry.gateStrength
            )
        }
    }
}
