package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-67 / PR-6Android — Android readiness evidence surface: acceptance and structural tests.
 *
 * Validates the four acceptance criteria for making Android-side readiness evidence
 * reviewable and release-gate friendly:
 *
 *  1. **What Android-side signals/tests/artifacts count as readiness evidence** — verified
 *     by [AndroidReadinessEvidenceSurface.allEvidence] structural invariants and spot-checks.
 *
 *  2. **Which evidence is strong/canonical vs advisory/observational** — verified by
 *     confidence-level assignments and count invariants.
 *
 *  3. **Where the main readiness dimensions are covered** — verified by per-dimension
 *     coverage checks confirming that each [AndroidReadinessEvidenceSurface.ReadinessDimension]
 *     has at least one [AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL] entry.
 *
 *  4. **How later governance/release gating can consume this evidence** — verified by
 *     v2ConsumptionPath non-blank invariants on all CANONICAL entries.
 *
 * ## Test matrix
 *
 * ### AndroidReadinessEvidenceSurface — structural invariants
 *  - INTRODUCED_PR is 67
 *  - INTRODUCED_PR_TITLE is non-blank
 *  - DESCRIPTION is non-blank
 *  - allEvidence has exactly EVIDENCE_ENTRY_COUNT entries
 *  - all evidenceIds are non-blank
 *  - all evidenceIds are unique
 *  - all descriptions are non-blank
 *  - all producedBy values are non-blank
 *  - all testEvidence values are non-blank
 *  - all v2ConsumptionPath values are non-blank
 *  - CANONICAL count matches CANONICAL_EVIDENCE_COUNT
 *  - ADVISORY count matches ADVISORY_EVIDENCE_COUNT
 *  - DEPRECATED_COMPAT count matches DEPRECATED_COMPAT_EVIDENCE_COUNT
 *  - total confidence level counts sum to EVIDENCE_ENTRY_COUNT
 *  - deferredItems has exactly DEFERRED_ITEM_COUNT entries
 *  - all deferred itemIds are non-blank
 *  - all deferred itemIds are unique
 *  - all deferred descriptions are non-blank
 *  - all deferred deferralReasons are non-blank
 *  - all deferred deferredTo values are non-blank
 *
 * ### ReadinessDimension — wire values
 *  - RUNTIME_LIFECYCLE wireValue is "runtime_lifecycle"
 *  - TAKEOVER_EXECUTION wireValue is "takeover_execution"
 *  - ARTIFACT_EMISSION_RECONCILIATION wireValue is "artifact_emission_reconciliation"
 *  - CONTINUITY_RECOVERY_SAFETY wireValue is "continuity_recovery_safety"
 *  - COMPATIBILITY_SUPPRESSION wireValue is "compatibility_suppression"
 *  - SIGNAL_REPLAY_DUPLICATE_SAFETY wireValue is "signal_replay_duplicate_safety"
 *  - PARTICIPANT_LIFECYCLE_TRUTH wireValue is "participant_lifecycle_truth"
 *  - All seven wire values are distinct
 *  - fromValue round-trips all seven wire values correctly
 *  - fromValue returns null for unknown value
 *  - fromValue returns null for null
 *
 * ### ConfidenceLevel — wire values
 *  - CANONICAL wireValue is "canonical"
 *  - ADVISORY wireValue is "advisory"
 *  - DEPRECATED_COMPAT wireValue is "deprecated_compat"
 *  - All three wire values are distinct
 *  - fromValue round-trips all three wire values correctly
 *  - fromValue returns null for unknown value
 *  - fromValue returns null for null
 *
 * ### Per-dimension canonical coverage
 *  - RUNTIME_LIFECYCLE has at least one CANONICAL entry
 *  - TAKEOVER_EXECUTION has at least one CANONICAL entry
 *  - ARTIFACT_EMISSION_RECONCILIATION has at least one CANONICAL entry
 *  - CONTINUITY_RECOVERY_SAFETY has at least one CANONICAL entry
 *  - COMPATIBILITY_SUPPRESSION has at least one CANONICAL entry
 *  - SIGNAL_REPLAY_DUPLICATE_SAFETY has at least one CANONICAL entry
 *  - PARTICIPANT_LIFECYCLE_TRUTH has at least one CANONICAL entry
 *
 * ### Specific evidence entries — spot-checks (AC1: what counts as evidence)
 *  - readiness_evaluator_five_dimension_verdict is present and CANONICAL
 *  - acceptance_evaluator_six_dimension_graduation_verdict is present and CANONICAL
 *  - post_graduation_governance_evaluator_verdict is present and CANONICAL
 *  - strategy_evaluator_dispatch_verdict is present and ADVISORY
 *  - runtime_lifecycle_transition_event_emission is present and CANONICAL
 *  - takeover_fallback_event_canonical_bounding is present and CANONICAL
 *  - takeover_executor_metadata_unification is present and CANONICAL
 *  - takeover_recovery_path_compat_gate is present and ADVISORY
 *  - device_readiness_artifact_wire_emission is present and CANONICAL
 *  - device_acceptance_artifact_wire_emission is present and CANONICAL
 *  - reconciliation_signal_participant_state_emission is present and CANONICAL
 *  - reconciliation_signal_runtime_truth_snapshot_emission is present and CANONICAL
 *  - unified_truth_reconciliation_surface_emission is present and CANONICAL
 *  - recovery_participation_owner_restart_reconnect_bounding is present and CANONICAL
 *  - continuity_recovery_durability_contract_coverage is present and CANONICAL
 *  - hybrid_lifecycle_recovery_contract_coverage is present and CANONICAL
 *  - durable_session_continuity_record_rehydration is present and CANONICAL
 *  - online_execution_continuity_gate_closure is present and CANONICAL
 *  - compat_legacy_blocking_participant_canonical_path_confirmation is present and CANONICAL
 *  - compatibility_surface_retirement_registry is present and CANONICAL
 *  - authoritative_path_alignment_audit is present and CANONICAL
 *  - long_tail_compat_registry_legacy_signals is present and DEPRECATED_COMPAT
 *  - compatibility_retirement_fence_blocking is present and CANONICAL
 *  - emitted_signal_ledger_terminal_bounding is present and CANONICAL
 *  - continuity_integration_duplicate_signal_suppression is present and CANONICAL
 *  - offline_queue_stale_session_discard is present and CANONICAL
 *  - delegated_execution_signal_idempotency_guard is present and CANONICAL
 *  - participant_lifecycle_truth_nine_state_model is present and CANONICAL
 *  - participant_lifecycle_truth_report_cross_repo_export is present and CANONICAL
 *  - participant_lifecycle_truth_report_builder_derivation is present and CANONICAL
 *
 * ### CANONICAL entries V2 consumption paths (AC4: how governance gates can consume evidence)
 *  - all CANONICAL entries have non-blank v2ConsumptionPath
 *  - readiness evaluator entry v2ConsumptionPath references ReconciliationSignal
 *  - acceptance evaluator entry v2ConsumptionPath references graduation gate
 *  - reconciliation signal entry v2ConsumptionPath references GalaxyConnectionService
 *  - continuity contract entry v2ConsumptionPath references coveredBehaviors
 *
 * ### Deferred items (AC5: what remains deferred)
 *  - takeover_session_authority_bounding is deferred
 *  - emit_ledger_cross_process_persistence is deferred
 *  - reconciliation_signal_epoch_bounding_after_reconnect is deferred
 *  - instrumented_e2e_readiness_evidence_test is deferred
 *  - final_release_policy_in_android is deferred
 *  - all deferred items have a dimension assigned
 *  - final_release_policy_in_android deferred dimension is RUNTIME_LIFECYCLE
 *
 * ### Query helpers
 *  - evidenceFor returns entry for known id
 *  - evidenceFor returns null for unknown id
 *  - evidenceForDimension returns only entries for that dimension
 *  - evidenceAtLevel returns only entries at that level
 *  - deferredItemFor returns item for known id
 *  - deferredItemFor returns null for unknown id
 *
 * ### DEPRECATED_COMPAT entries do not have canonical dimension coverage implications
 *  - long_tail_compat_registry_legacy_signals is DEPRECATED_COMPAT (not CANONICAL)
 *  - DEPRECATED_COMPAT entries are not counted toward canonical coverage
 *  - canonical coverage per dimension holds even without DEPRECATED_COMPAT entries
 */
class Pr67AndroidReadinessEvidenceSurfaceTest {

    private val surface = AndroidReadinessEvidenceSurface

    // ── Structural invariants ─────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 67`() {
        assertEquals(67, surface.INTRODUCED_PR)
    }

    @Test
    fun `INTRODUCED_PR_TITLE is non-blank`() {
        assertTrue(surface.INTRODUCED_PR_TITLE.isNotBlank())
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(surface.DESCRIPTION.isNotBlank())
    }

    @Test
    fun `allEvidence has exactly EVIDENCE_ENTRY_COUNT entries`() {
        assertEquals(surface.EVIDENCE_ENTRY_COUNT, surface.allEvidence.size)
    }

    @Test
    fun `all evidenceIds are non-blank`() {
        surface.allEvidence.forEach {
            assertTrue("evidenceId blank for entry: $it", it.evidenceId.isNotBlank())
        }
    }

    @Test
    fun `all evidenceIds are unique`() {
        val ids = surface.allEvidence.map { it.evidenceId }
        assertEquals("duplicate evidenceIds found", ids.size, ids.toSet().size)
    }

    @Test
    fun `all descriptions are non-blank`() {
        surface.allEvidence.forEach {
            assertTrue("description blank for ${it.evidenceId}", it.description.isNotBlank())
        }
    }

    @Test
    fun `all producedBy values are non-blank`() {
        surface.allEvidence.forEach {
            assertTrue("producedBy blank for ${it.evidenceId}", it.producedBy.isNotBlank())
        }
    }

    @Test
    fun `all testEvidence values are non-blank`() {
        surface.allEvidence.forEach {
            assertTrue("testEvidence blank for ${it.evidenceId}", it.testEvidence.isNotBlank())
        }
    }

    @Test
    fun `all v2ConsumptionPath values are non-blank`() {
        surface.allEvidence.forEach {
            assertTrue(
                "v2ConsumptionPath blank for ${it.evidenceId}",
                it.v2ConsumptionPath.isNotBlank()
            )
        }
    }

    @Test
    fun `CANONICAL count matches CANONICAL_EVIDENCE_COUNT`() {
        assertEquals(
            surface.CANONICAL_EVIDENCE_COUNT,
            surface.evidenceAtLevel(AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL).size
        )
    }

    @Test
    fun `ADVISORY count matches ADVISORY_EVIDENCE_COUNT`() {
        assertEquals(
            surface.ADVISORY_EVIDENCE_COUNT,
            surface.evidenceAtLevel(AndroidReadinessEvidenceSurface.ConfidenceLevel.ADVISORY).size
        )
    }

    @Test
    fun `DEPRECATED_COMPAT count matches DEPRECATED_COMPAT_EVIDENCE_COUNT`() {
        assertEquals(
            surface.DEPRECATED_COMPAT_EVIDENCE_COUNT,
            surface.evidenceAtLevel(
                AndroidReadinessEvidenceSurface.ConfidenceLevel.DEPRECATED_COMPAT
            ).size
        )
    }

    @Test
    fun `total confidence level counts sum to EVIDENCE_ENTRY_COUNT`() {
        val total = surface.CANONICAL_EVIDENCE_COUNT +
            surface.ADVISORY_EVIDENCE_COUNT +
            surface.DEPRECATED_COMPAT_EVIDENCE_COUNT
        assertEquals(surface.EVIDENCE_ENTRY_COUNT, total)
    }

    @Test
    fun `deferredItems has exactly DEFERRED_ITEM_COUNT entries`() {
        assertEquals(surface.DEFERRED_ITEM_COUNT, surface.deferredItems.size)
    }

    @Test
    fun `all deferred itemIds are non-blank`() {
        surface.deferredItems.forEach {
            assertTrue("itemId blank for deferred item: $it", it.itemId.isNotBlank())
        }
    }

    @Test
    fun `all deferred itemIds are unique`() {
        val ids = surface.deferredItems.map { it.itemId }
        assertEquals("duplicate deferred itemIds found", ids.size, ids.toSet().size)
    }

    @Test
    fun `all deferred descriptions are non-blank`() {
        surface.deferredItems.forEach {
            assertTrue("description blank for deferred ${it.itemId}", it.description.isNotBlank())
        }
    }

    @Test
    fun `all deferred deferralReasons are non-blank`() {
        surface.deferredItems.forEach {
            assertTrue(
                "deferralReason blank for deferred ${it.itemId}",
                it.deferralReason.isNotBlank()
            )
        }
    }

    @Test
    fun `all deferred deferredTo values are non-blank`() {
        surface.deferredItems.forEach {
            assertTrue("deferredTo blank for deferred ${it.itemId}", it.deferredTo.isNotBlank())
        }
    }

    // ── ReadinessDimension — wire values ──────────────────────────────────────

    @Test
    fun `RUNTIME_LIFECYCLE wireValue is runtime_lifecycle`() {
        assertEquals(
            "runtime_lifecycle",
            AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE.wireValue
        )
    }

    @Test
    fun `TAKEOVER_EXECUTION wireValue is takeover_execution`() {
        assertEquals(
            "takeover_execution",
            AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION.wireValue
        )
    }

    @Test
    fun `ARTIFACT_EMISSION_RECONCILIATION wireValue is artifact_emission_reconciliation`() {
        assertEquals(
            "artifact_emission_reconciliation",
            AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION.wireValue
        )
    }

    @Test
    fun `CONTINUITY_RECOVERY_SAFETY wireValue is continuity_recovery_safety`() {
        assertEquals(
            "continuity_recovery_safety",
            AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY.wireValue
        )
    }

    @Test
    fun `COMPATIBILITY_SUPPRESSION wireValue is compatibility_suppression`() {
        assertEquals(
            "compatibility_suppression",
            AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION.wireValue
        )
    }

    @Test
    fun `SIGNAL_REPLAY_DUPLICATE_SAFETY wireValue is signal_replay_duplicate_safety`() {
        assertEquals(
            "signal_replay_duplicate_safety",
            AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY.wireValue
        )
    }

    @Test
    fun `PARTICIPANT_LIFECYCLE_TRUTH wireValue is participant_lifecycle_truth`() {
        assertEquals(
            "participant_lifecycle_truth",
            AndroidReadinessEvidenceSurface.ReadinessDimension.PARTICIPANT_LIFECYCLE_TRUTH.wireValue
        )
    }

    @Test
    fun `all seven dimension wire values are distinct`() {
        val values = AndroidReadinessEvidenceSurface.ReadinessDimension.values()
            .map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `fromValue round-trips runtime_lifecycle`() {
        assertEquals(
            AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            AndroidReadinessEvidenceSurface.ReadinessDimension.fromValue("runtime_lifecycle")
        )
    }

    @Test
    fun `fromValue round-trips takeover_execution`() {
        assertEquals(
            AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION,
            AndroidReadinessEvidenceSurface.ReadinessDimension.fromValue("takeover_execution")
        )
    }

    @Test
    fun `fromValue round-trips artifact_emission_reconciliation`() {
        assertEquals(
            AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            AndroidReadinessEvidenceSurface.ReadinessDimension.fromValue(
                "artifact_emission_reconciliation"
            )
        )
    }

    @Test
    fun `fromValue round-trips continuity_recovery_safety`() {
        assertEquals(
            AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            AndroidReadinessEvidenceSurface.ReadinessDimension.fromValue("continuity_recovery_safety")
        )
    }

    @Test
    fun `fromValue round-trips compatibility_suppression`() {
        assertEquals(
            AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            AndroidReadinessEvidenceSurface.ReadinessDimension.fromValue("compatibility_suppression")
        )
    }

    @Test
    fun `fromValue round-trips signal_replay_duplicate_safety`() {
        assertEquals(
            AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            AndroidReadinessEvidenceSurface.ReadinessDimension.fromValue(
                "signal_replay_duplicate_safety"
            )
        )
    }

    @Test
    fun `fromValue round-trips participant_lifecycle_truth`() {
        assertEquals(
            AndroidReadinessEvidenceSurface.ReadinessDimension.PARTICIPANT_LIFECYCLE_TRUTH,
            AndroidReadinessEvidenceSurface.ReadinessDimension.fromValue(
                "participant_lifecycle_truth"
            )
        )
    }

    @Test
    fun `ReadinessDimension fromValue returns null for unknown value`() {
        assertNull(
            AndroidReadinessEvidenceSurface.ReadinessDimension.fromValue("nonexistent_dimension")
        )
    }

    @Test
    fun `ReadinessDimension fromValue returns null for null`() {
        assertNull(AndroidReadinessEvidenceSurface.ReadinessDimension.fromValue(null))
    }

    // ── ConfidenceLevel — wire values ─────────────────────────────────────────

    @Test
    fun `CANONICAL wireValue is canonical`() {
        assertEquals(
            "canonical",
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL.wireValue
        )
    }

    @Test
    fun `ADVISORY wireValue is advisory`() {
        assertEquals(
            "advisory",
            AndroidReadinessEvidenceSurface.ConfidenceLevel.ADVISORY.wireValue
        )
    }

    @Test
    fun `DEPRECATED_COMPAT wireValue is deprecated_compat`() {
        assertEquals(
            "deprecated_compat",
            AndroidReadinessEvidenceSurface.ConfidenceLevel.DEPRECATED_COMPAT.wireValue
        )
    }

    @Test
    fun `all three confidence level wire values are distinct`() {
        val values = AndroidReadinessEvidenceSurface.ConfidenceLevel.values()
            .map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `ConfidenceLevel fromValue round-trips canonical`() {
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            AndroidReadinessEvidenceSurface.ConfidenceLevel.fromValue("canonical")
        )
    }

    @Test
    fun `ConfidenceLevel fromValue round-trips advisory`() {
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.ADVISORY,
            AndroidReadinessEvidenceSurface.ConfidenceLevel.fromValue("advisory")
        )
    }

    @Test
    fun `ConfidenceLevel fromValue round-trips deprecated_compat`() {
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.DEPRECATED_COMPAT,
            AndroidReadinessEvidenceSurface.ConfidenceLevel.fromValue("deprecated_compat")
        )
    }

    @Test
    fun `ConfidenceLevel fromValue returns null for unknown value`() {
        assertNull(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.fromValue("nonexistent_level")
        )
    }

    @Test
    fun `ConfidenceLevel fromValue returns null for null`() {
        assertNull(AndroidReadinessEvidenceSurface.ConfidenceLevel.fromValue(null))
    }

    // ── Per-dimension canonical coverage ─────────────────────────────────────

    @Test
    fun `RUNTIME_LIFECYCLE has at least one CANONICAL entry`() {
        val entries = surface.evidenceForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE
        ).filter {
            it.confidenceLevel == AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL
        }
        assertTrue(
            "RUNTIME_LIFECYCLE has no CANONICAL entries",
            entries.isNotEmpty()
        )
    }

    @Test
    fun `TAKEOVER_EXECUTION has at least one CANONICAL entry`() {
        val entries = surface.evidenceForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION
        ).filter {
            it.confidenceLevel == AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL
        }
        assertTrue(
            "TAKEOVER_EXECUTION has no CANONICAL entries",
            entries.isNotEmpty()
        )
    }

    @Test
    fun `ARTIFACT_EMISSION_RECONCILIATION has at least one CANONICAL entry`() {
        val entries = surface.evidenceForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION
        ).filter {
            it.confidenceLevel == AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL
        }
        assertTrue(
            "ARTIFACT_EMISSION_RECONCILIATION has no CANONICAL entries",
            entries.isNotEmpty()
        )
    }

    @Test
    fun `CONTINUITY_RECOVERY_SAFETY has at least one CANONICAL entry`() {
        val entries = surface.evidenceForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY
        ).filter {
            it.confidenceLevel == AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL
        }
        assertTrue(
            "CONTINUITY_RECOVERY_SAFETY has no CANONICAL entries",
            entries.isNotEmpty()
        )
    }

    @Test
    fun `COMPATIBILITY_SUPPRESSION has at least one CANONICAL entry`() {
        val entries = surface.evidenceForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION
        ).filter {
            it.confidenceLevel == AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL
        }
        assertTrue(
            "COMPATIBILITY_SUPPRESSION has no CANONICAL entries",
            entries.isNotEmpty()
        )
    }

    @Test
    fun `SIGNAL_REPLAY_DUPLICATE_SAFETY has at least one CANONICAL entry`() {
        val entries = surface.evidenceForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY
        ).filter {
            it.confidenceLevel == AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL
        }
        assertTrue(
            "SIGNAL_REPLAY_DUPLICATE_SAFETY has no CANONICAL entries",
            entries.isNotEmpty()
        )
    }

    @Test
    fun `PARTICIPANT_LIFECYCLE_TRUTH has at least one CANONICAL entry`() {
        val entries = surface.evidenceForDimension(
            AndroidReadinessEvidenceSurface.ReadinessDimension.PARTICIPANT_LIFECYCLE_TRUTH
        ).filter {
            it.confidenceLevel == AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL
        }
        assertTrue(
            "PARTICIPANT_LIFECYCLE_TRUTH has no CANONICAL entries",
            entries.isNotEmpty()
        )
    }

    // ── Specific evidence entries — spot-checks (AC1) ─────────────────────────

    @Test
    fun `readiness_evaluator_five_dimension_verdict is present and CANONICAL`() {
        val entry = surface.evidenceFor("readiness_evaluator_five_dimension_verdict")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `acceptance_evaluator_six_dimension_graduation_verdict is present and CANONICAL`() {
        val entry = surface.evidenceFor("acceptance_evaluator_six_dimension_graduation_verdict")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `post_graduation_governance_evaluator_verdict is present and CANONICAL`() {
        val entry = surface.evidenceFor("post_graduation_governance_evaluator_verdict")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `strategy_evaluator_dispatch_verdict is present and ADVISORY`() {
        val entry = surface.evidenceFor("strategy_evaluator_dispatch_verdict")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.ADVISORY,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `runtime_lifecycle_transition_event_emission is present and CANONICAL`() {
        val entry = surface.evidenceFor("runtime_lifecycle_transition_event_emission")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `takeover_fallback_event_canonical_bounding is present and CANONICAL`() {
        val entry = surface.evidenceFor("takeover_fallback_event_canonical_bounding")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `takeover_executor_metadata_unification is present and CANONICAL`() {
        val entry = surface.evidenceFor("takeover_executor_metadata_unification")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `takeover_recovery_path_compat_gate is present and ADVISORY`() {
        val entry = surface.evidenceFor("takeover_recovery_path_compat_gate")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.ADVISORY,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `device_readiness_artifact_wire_emission is present and CANONICAL`() {
        val entry = surface.evidenceFor("device_readiness_artifact_wire_emission")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `device_acceptance_artifact_wire_emission is present and CANONICAL`() {
        val entry = surface.evidenceFor("device_acceptance_artifact_wire_emission")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `reconciliation_signal_participant_state_emission is present and CANONICAL`() {
        val entry = surface.evidenceFor("reconciliation_signal_participant_state_emission")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `reconciliation_signal_runtime_truth_snapshot_emission is present and CANONICAL`() {
        val entry = surface.evidenceFor("reconciliation_signal_runtime_truth_snapshot_emission")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `unified_truth_reconciliation_surface_emission is present and CANONICAL`() {
        val entry = surface.evidenceFor("unified_truth_reconciliation_surface_emission")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `recovery_participation_owner_restart_reconnect_bounding is present and CANONICAL`() {
        val entry = surface.evidenceFor(
            "recovery_participation_owner_restart_reconnect_bounding"
        )
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `continuity_recovery_durability_contract_coverage is present and CANONICAL`() {
        val entry = surface.evidenceFor("continuity_recovery_durability_contract_coverage")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `hybrid_lifecycle_recovery_contract_coverage is present and CANONICAL`() {
        val entry = surface.evidenceFor("hybrid_lifecycle_recovery_contract_coverage")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `durable_session_continuity_record_rehydration is present and CANONICAL`() {
        val entry = surface.evidenceFor("durable_session_continuity_record_rehydration")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `online_execution_continuity_gate_closure is present and CANONICAL`() {
        val entry = surface.evidenceFor("online_execution_continuity_gate_closure")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `compat_legacy_blocking_participant_canonical_path_confirmation is present and CANONICAL`() {
        val entry = surface.evidenceFor(
            "compat_legacy_blocking_participant_canonical_path_confirmation"
        )
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `compatibility_surface_retirement_registry is present and CANONICAL`() {
        val entry = surface.evidenceFor("compatibility_surface_retirement_registry")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `authoritative_path_alignment_audit is present and CANONICAL`() {
        val entry = surface.evidenceFor("authoritative_path_alignment_audit")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `long_tail_compat_registry_legacy_signals is present and DEPRECATED_COMPAT`() {
        val entry = surface.evidenceFor("long_tail_compat_registry_legacy_signals")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.DEPRECATED_COMPAT,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `compatibility_retirement_fence_blocking is present and CANONICAL`() {
        val entry = surface.evidenceFor("compatibility_retirement_fence_blocking")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `emitted_signal_ledger_terminal_bounding is present and CANONICAL`() {
        val entry = surface.evidenceFor("emitted_signal_ledger_terminal_bounding")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `continuity_integration_duplicate_signal_suppression is present and CANONICAL`() {
        val entry = surface.evidenceFor(
            "continuity_integration_duplicate_signal_suppression"
        )
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `offline_queue_stale_session_discard is present and CANONICAL`() {
        val entry = surface.evidenceFor("offline_queue_stale_session_discard")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `delegated_execution_signal_idempotency_guard is present and CANONICAL`() {
        val entry = surface.evidenceFor("delegated_execution_signal_idempotency_guard")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `participant_lifecycle_truth_nine_state_model is present and CANONICAL`() {
        val entry = surface.evidenceFor("participant_lifecycle_truth_nine_state_model")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `participant_lifecycle_truth_report_cross_repo_export is present and CANONICAL`() {
        val entry = surface.evidenceFor("participant_lifecycle_truth_report_cross_repo_export")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `participant_lifecycle_truth_report_builder_derivation is present and CANONICAL`() {
        val entry = surface.evidenceFor("participant_lifecycle_truth_report_builder_derivation")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL,
            entry!!.confidenceLevel
        )
    }

    // ── CANONICAL entries V2 consumption paths (AC4) ──────────────────────────

    @Test
    fun `all CANONICAL entries have non-blank v2ConsumptionPath`() {
        surface.evidenceAtLevel(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL
        ).forEach {
            assertTrue(
                "v2ConsumptionPath blank for CANONICAL entry ${it.evidenceId}",
                it.v2ConsumptionPath.isNotBlank()
            )
        }
    }

    @Test
    fun `readiness evaluator entry v2ConsumptionPath references ReconciliationSignal`() {
        val entry = surface.evidenceFor("readiness_evaluator_five_dimension_verdict")
        assertNotNull(entry)
        assertTrue(
            "v2ConsumptionPath does not reference ReconciliationSignal",
            entry!!.v2ConsumptionPath.contains("ReconciliationSignal")
        )
    }

    @Test
    fun `acceptance evaluator entry v2ConsumptionPath references graduation gate`() {
        val entry = surface.evidenceFor("acceptance_evaluator_six_dimension_graduation_verdict")
        assertNotNull(entry)
        assertTrue(
            "v2ConsumptionPath does not reference graduation gate",
            entry!!.v2ConsumptionPath.contains("graduation")
        )
    }

    @Test
    fun `reconciliation signal entry v2ConsumptionPath references GalaxyConnectionService`() {
        val entry = surface.evidenceFor("reconciliation_signal_participant_state_emission")
        assertNotNull(entry)
        assertTrue(
            "v2ConsumptionPath does not reference GalaxyConnectionService",
            entry!!.v2ConsumptionPath.contains("GalaxyConnectionService")
        )
    }

    @Test
    fun `continuity contract entry v2ConsumptionPath references coveredBehaviors`() {
        val entry = surface.evidenceFor("continuity_recovery_durability_contract_coverage")
        assertNotNull(entry)
        assertTrue(
            "v2ConsumptionPath does not reference coveredBehaviors",
            entry!!.v2ConsumptionPath.contains("coveredBehaviors")
        )
    }

    // ── Deferred items (AC5) ──────────────────────────────────────────────────

    @Test
    fun `takeover_session_authority_bounding is deferred`() {
        assertNotNull(surface.deferredItemFor("takeover_session_authority_bounding"))
    }

    @Test
    fun `emit_ledger_cross_process_persistence is deferred`() {
        assertNotNull(surface.deferredItemFor("emit_ledger_cross_process_persistence"))
    }

    @Test
    fun `reconciliation_signal_epoch_bounding_after_reconnect is deferred`() {
        assertNotNull(
            surface.deferredItemFor("reconciliation_signal_epoch_bounding_after_reconnect")
        )
    }

    @Test
    fun `instrumented_e2e_readiness_evidence_test is deferred`() {
        assertNotNull(surface.deferredItemFor("instrumented_e2e_readiness_evidence_test"))
    }

    @Test
    fun `final_release_policy_in_android is deferred`() {
        assertNotNull(surface.deferredItemFor("final_release_policy_in_android"))
    }

    @Test
    fun `all deferred items have a dimension assigned`() {
        surface.deferredItems.forEach {
            assertNotNull(
                "dimension null for deferred item ${it.itemId}",
                it.dimension
            )
        }
    }

    @Test
    fun `final_release_policy_in_android deferred dimension is RUNTIME_LIFECYCLE`() {
        val item = surface.deferredItemFor("final_release_policy_in_android")
        assertNotNull(item)
        assertEquals(
            AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            item!!.dimension
        )
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    @Test
    fun `evidenceFor returns entry for known id`() {
        assertNotNull(
            surface.evidenceFor("readiness_evaluator_five_dimension_verdict")
        )
    }

    @Test
    fun `evidenceFor returns null for unknown id`() {
        assertNull(surface.evidenceFor("nonexistent_evidence_id_xyz"))
    }

    @Test
    fun `evidenceForDimension returns only entries for that dimension`() {
        val dim = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE
        val entries = surface.evidenceForDimension(dim)
        assertTrue(entries.isNotEmpty())
        entries.forEach {
            assertEquals(
                "entry ${it.evidenceId} has wrong dimension",
                dim,
                it.dimension
            )
        }
    }

    @Test
    fun `evidenceAtLevel returns only entries at that level`() {
        val level = AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL
        val entries = surface.evidenceAtLevel(level)
        assertTrue(entries.isNotEmpty())
        entries.forEach {
            assertEquals(
                "entry ${it.evidenceId} has wrong confidence level",
                level,
                it.confidenceLevel
            )
        }
    }

    @Test
    fun `deferredItemFor returns item for known id`() {
        assertNotNull(surface.deferredItemFor("takeover_session_authority_bounding"))
    }

    @Test
    fun `deferredItemFor returns null for unknown id`() {
        assertNull(surface.deferredItemFor("nonexistent_deferred_id_xyz"))
    }

    // ── DEPRECATED_COMPAT entries do not count for canonical coverage ─────────

    @Test
    fun `long_tail_compat_registry_legacy_signals is DEPRECATED_COMPAT not CANONICAL`() {
        val entry = surface.evidenceFor("long_tail_compat_registry_legacy_signals")
        assertNotNull(entry)
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.DEPRECATED_COMPAT,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `DEPRECATED_COMPAT entries are not counted toward CANONICAL count`() {
        val deprecatedIds = surface.evidenceAtLevel(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.DEPRECATED_COMPAT
        ).map { it.evidenceId }
        val canonicalIds = surface.evidenceAtLevel(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL
        ).map { it.evidenceId }
        assertTrue(
            "DEPRECATED_COMPAT ids should not overlap with CANONICAL ids",
            deprecatedIds.none { it in canonicalIds }
        )
    }

    @Test
    fun `canonical coverage per dimension holds even without DEPRECATED_COMPAT entries`() {
        val canonicalOnly = surface.allEvidence.filter {
            it.confidenceLevel == AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL
        }
        AndroidReadinessEvidenceSurface.ReadinessDimension.values().forEach { dim ->
            val dimCanonical = canonicalOnly.filter { it.dimension == dim }
            assertTrue(
                "dimension $dim has no CANONICAL evidence (excluding DEPRECATED_COMPAT)",
                dimCanonical.isNotEmpty()
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun assertNull(value: Any?) {
        org.junit.Assert.assertNull(value)
    }
}
