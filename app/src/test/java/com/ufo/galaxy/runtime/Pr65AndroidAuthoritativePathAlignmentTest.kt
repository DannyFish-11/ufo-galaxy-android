package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-65 (Android) — Authoritative-path alignment audit: acceptance and regression tests.
 *
 * Validates the four PR-65 acceptance criteria:
 *
 *  1. Which Android-side behaviors are canonical vs. compat / legacy.
 *  2. That Android default behavior is less permissive toward deprecated runtime influence.
 *  3. That participant-side compatibility influence is easier for V2 to classify and control.
 *  4. What Android-side cleanup remains deferred for later phases.
 *
 * ## Test matrix
 *
 * ### AndroidAuthoritativePathAlignmentAudit — BehaviorTier enum
 *  - CANONICAL_DEFAULT wireValue is "canonical_default"
 *  - COMPAT_ALLOWED wireValue is "compat_allowed"
 *  - OBSERVATION_ONLY wireValue is "observation_only"
 *  - DEPRECATED_BUT_LIVE wireValue is "deprecated_but_live"
 *  - BLOCKED_RETIRED wireValue is "blocked_retired"
 *  - All five wireValues are distinct
 *  - fromValue returns correct tier for each known wireValue
 *  - fromValue returns null for unknown wireValue
 *
 * ### AndroidAuthoritativePathAlignmentAudit — SignalSemantics enum
 *  - CANONICAL_PARTICIPANT_EVIDENCE wireValue is "canonical_participant_evidence"
 *  - OBSERVATION_SIGNAL wireValue is "observation_signal"
 *  - LEGACY_INFLUENCED wireValue is "legacy_influenced"
 *  - BLOCKED wireValue is "blocked"
 *  - All four wireValues are distinct
 *  - fromValue returns correct semantics for each known wireValue
 *  - fromValue returns null for unknown wireValue
 *
 * ### AndroidAuthoritativePathAlignmentAudit — tier counts
 *  - entries has exactly CANONICAL_DEFAULT_COUNT CANONICAL_DEFAULT entries
 *  - entries has exactly COMPAT_ALLOWED_COUNT COMPAT_ALLOWED entries
 *  - entries has exactly OBSERVATION_ONLY_COUNT OBSERVATION_ONLY entries
 *  - entries has exactly DEPRECATED_BUT_LIVE_COUNT DEPRECATED_BUT_LIVE entries
 *  - entries has exactly BLOCKED_RETIRED_COUNT BLOCKED_RETIRED entries
 *  - total entry count equals sum of all tier counts
 *
 * ### AndroidAuthoritativePathAlignmentAudit — structural invariants
 *  - all behaviorIds are non-blank
 *  - all behaviorIds are unique
 *  - all labels are non-blank
 *  - all descriptions are non-blank
 *  - all enforcedBy values are non-blank
 *  - INTRODUCED_PR is 65
 *
 * ### AndroidAuthoritativePathAlignmentAudit — canonical default behaviors
 *  - reconciliation_signal_emission is CANONICAL_DEFAULT
 *  - runtime_truth_snapshot is CANONICAL_DEFAULT
 *  - truth_reconciliation_reducer is CANONICAL_DEFAULT
 *  - delegated_execution_signal_emission is CANONICAL_DEFAULT
 *  - device_readiness_report is CANONICAL_DEFAULT
 *  - rollout_safe_defaults is CANONICAL_DEFAULT
 *  - compat_legacy_blocking_canonical_confirm is CANONICAL_DEFAULT
 *  - all CANONICAL_DEFAULT entries have CANONICAL_PARTICIPANT_EVIDENCE signal semantics
 *
 * ### AndroidAuthoritativePathAlignmentAudit — compat allowed behaviors
 *  - legacy_message_type_remap is COMPAT_ALLOWED
 *  - long_tail_compat_handlers is COMPAT_ALLOWED
 *  - execution_contract_compat_validator is COMPAT_ALLOWED
 *  - no COMPAT_ALLOWED entry has CANONICAL_PARTICIPANT_EVIDENCE signal semantics
 *  - all COMPAT_ALLOWED entries have a deferralNote
 *
 * ### AndroidAuthoritativePathAlignmentAudit — observation-only behaviors
 *  - compat_surface_retirement_registry_decommission is OBSERVATION_ONLY
 *  - compat_legacy_blocking_observation_only is OBSERVATION_ONLY
 *  - runtime_observability_metadata is OBSERVATION_ONLY
 *  - all OBSERVATION_ONLY entries have OBSERVATION_SIGNAL signal semantics
 *  - no OBSERVATION_ONLY entry has CANONICAL_PARTICIPANT_EVIDENCE signal semantics
 *
 * ### AndroidAuthoritativePathAlignmentAudit — deprecated-but-live behaviors
 *  - high_risk_active_compat_surfaces is DEPRECATED_BUT_LIVE
 *  - legacy_fallback_local_after_delegated_failure is DEPRECATED_BUT_LIVE
 *  - ambiguous_legacy_state_quarantine is DEPRECATED_BUT_LIVE
 *  - all DEPRECATED_BUT_LIVE entries have LEGACY_INFLUENCED signal semantics
 *  - all DEPRECATED_BUT_LIVE entries have a deferralNote
 *  - no DEPRECATED_BUT_LIVE entry has CANONICAL_PARTICIPANT_EVIDENCE signal semantics
 *
 * ### AndroidAuthoritativePathAlignmentAudit — blocked/retired behaviors
 *  - legacy_runtime_path_blocked is BLOCKED_RETIRED
 *  - compat_contract_emit_suppressed is BLOCKED_RETIRED
 *  - all BLOCKED_RETIRED entries have BLOCKED signal semantics
 *  - no BLOCKED_RETIRED entry has CANONICAL_PARTICIPANT_EVIDENCE signal semantics
 *
 * ### AndroidAuthoritativePathAlignmentAudit — isCanonicalDefault
 *  - returns true for reconciliation_signal_emission
 *  - returns true for truth_reconciliation_reducer
 *  - returns false for legacy_message_type_remap
 *  - returns false for legacy_runtime_path_blocked
 *  - returns false for unknown behaviorId
 *
 * ### AndroidAuthoritativePathAlignmentAudit — isDeprecatedOrBlocked
 *  - returns true for high_risk_active_compat_surfaces
 *  - returns true for legacy_runtime_path_blocked
 *  - returns false for reconciliation_signal_emission
 *  - returns false for legacy_message_type_remap
 *  - returns false for unknown behaviorId
 *
 * ### AndroidAuthoritativePathAlignmentAudit — deferredEntries
 *  - all COMPAT_ALLOWED entries appear in deferredEntries
 *  - all DEPRECATED_BUT_LIVE entries appear in deferredEntries
 *  - no CANONICAL_DEFAULT entry appears in deferredEntries
 *  - no BLOCKED_RETIRED entry appears in deferredEntries
 *
 * ### AndroidAuthoritativePathAlignmentAudit — bySignalSemantics
 *  - bySignalSemantics(CANONICAL_PARTICIPANT_EVIDENCE) returns only CANONICAL_DEFAULT entries
 *  - bySignalSemantics(BLOCKED) returns only BLOCKED_RETIRED entries
 *  - bySignalSemantics(OBSERVATION_SIGNAL) is non-empty
 *  - bySignalSemantics(LEGACY_INFLUENCED) is non-empty
 *
 * ### AndroidAuthoritativePathAlignmentAudit — query helpers
 *  - canonicalDefaultEntries() size equals CANONICAL_DEFAULT_COUNT
 *  - compatAllowedEntries() size equals COMPAT_ALLOWED_COUNT
 *  - observationOnlyEntries() size equals OBSERVATION_ONLY_COUNT
 *  - deprecatedButLiveEntries() size equals DEPRECATED_BUT_LIVE_COUNT
 *  - blockedRetiredEntries() size equals BLOCKED_RETIRED_COUNT
 *  - forId returns entry with correct behaviorId
 *  - forId returns null for unknown id
 *
 * ### StabilizationBaseline — PR-65 registration
 *  - StabilizationBaseline contains exactly one entry with introducedPr == 65
 *  - PR-65 entry has surfaceId "android-authoritative-path-alignment-audit"
 *  - PR-65 entry has stability CANONICAL_STABLE
 *
 * ### Acceptance criterion 1 — canonical vs. compat/legacy distinction is explicit
 *  - CANONICAL_DEFAULT entries outnumber BLOCKED_RETIRED entries
 *  - compat path (legacy_message_type_remap) is NOT isCanonicalDefault
 *  - blocked path (legacy_runtime_path_blocked) is NOT isCanonicalDefault
 *  - canonical path (reconciliation_signal_emission) IS isCanonicalDefault
 *
 * ### Acceptance criterion 2 — deprecated behaviors are default-off
 *  - all DEPRECATED_BUT_LIVE entries are isDeprecatedOrBlocked
 *  - all BLOCKED_RETIRED entries are isDeprecatedOrBlocked
 *  - no CANONICAL_DEFAULT entry is isDeprecatedOrBlocked
 *
 * ### Acceptance criterion 3 — V2 can classify every Android signal
 *  - every entry has a non-null signalSemantics
 *  - no entry has a null signalSemantics
 *  - CANONICAL_PARTICIPANT_EVIDENCE entries all come from CANONICAL_DEFAULT tier
 *  - BLOCKED entries all come from BLOCKED_RETIRED tier
 *
 * ### Acceptance criterion 4 — deferred cleanup is explicitly listed
 *  - deferredEntries() is non-empty
 *  - every deferred entry has a non-blank deferralNote
 */
class Pr65AndroidAuthoritativePathAlignmentTest {

    // ══════════════════════════════════════════════════════════════════════════
    // BehaviorTier enum
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `CANONICAL_DEFAULT wireValue is canonical_default`() {
        assertEquals("canonical_default", AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT.wireValue)
    }

    @Test
    fun `COMPAT_ALLOWED wireValue is compat_allowed`() {
        assertEquals("compat_allowed", AndroidAuthoritativePathAlignmentAudit.BehaviorTier.COMPAT_ALLOWED.wireValue)
    }

    @Test
    fun `OBSERVATION_ONLY wireValue is observation_only`() {
        assertEquals("observation_only", AndroidAuthoritativePathAlignmentAudit.BehaviorTier.OBSERVATION_ONLY.wireValue)
    }

    @Test
    fun `DEPRECATED_BUT_LIVE wireValue is deprecated_but_live`() {
        assertEquals("deprecated_but_live", AndroidAuthoritativePathAlignmentAudit.BehaviorTier.DEPRECATED_BUT_LIVE.wireValue)
    }

    @Test
    fun `BLOCKED_RETIRED wireValue is blocked_retired`() {
        assertEquals("blocked_retired", AndroidAuthoritativePathAlignmentAudit.BehaviorTier.BLOCKED_RETIRED.wireValue)
    }

    @Test
    fun `All five BehaviorTier wireValues are distinct`() {
        val values = AndroidAuthoritativePathAlignmentAudit.BehaviorTier.entries.map { it.wireValue }
        assertEquals("All BehaviorTier wireValues must be distinct", values.size, values.toSet().size)
    }

    @Test
    fun `BehaviorTier fromValue returns correct tier for each known wireValue`() {
        AndroidAuthoritativePathAlignmentAudit.BehaviorTier.entries.forEach { tier ->
            assertEquals(tier, AndroidAuthoritativePathAlignmentAudit.BehaviorTier.fromValue(tier.wireValue))
        }
    }

    @Test
    fun `BehaviorTier fromValue returns null for unknown wireValue`() {
        assertNull(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.fromValue("unknown_tier"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SignalSemantics enum
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `CANONICAL_PARTICIPANT_EVIDENCE wireValue is canonical_participant_evidence`() {
        assertEquals(
            "canonical_participant_evidence",
            AndroidAuthoritativePathAlignmentAudit.SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE.wireValue
        )
    }

    @Test
    fun `OBSERVATION_SIGNAL wireValue is observation_signal`() {
        assertEquals(
            "observation_signal",
            AndroidAuthoritativePathAlignmentAudit.SignalSemantics.OBSERVATION_SIGNAL.wireValue
        )
    }

    @Test
    fun `LEGACY_INFLUENCED wireValue is legacy_influenced`() {
        assertEquals(
            "legacy_influenced",
            AndroidAuthoritativePathAlignmentAudit.SignalSemantics.LEGACY_INFLUENCED.wireValue
        )
    }

    @Test
    fun `BLOCKED wireValue is blocked`() {
        assertEquals(
            "blocked",
            AndroidAuthoritativePathAlignmentAudit.SignalSemantics.BLOCKED.wireValue
        )
    }

    @Test
    fun `All four SignalSemantics wireValues are distinct`() {
        val values = AndroidAuthoritativePathAlignmentAudit.SignalSemantics.entries.map { it.wireValue }
        assertEquals("All SignalSemantics wireValues must be distinct", values.size, values.toSet().size)
    }

    @Test
    fun `SignalSemantics fromValue returns correct semantics for each known wireValue`() {
        AndroidAuthoritativePathAlignmentAudit.SignalSemantics.entries.forEach { semantics ->
            assertEquals(semantics, AndroidAuthoritativePathAlignmentAudit.SignalSemantics.fromValue(semantics.wireValue))
        }
    }

    @Test
    fun `SignalSemantics fromValue returns null for unknown wireValue`() {
        assertNull(AndroidAuthoritativePathAlignmentAudit.SignalSemantics.fromValue("unknown_semantics"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tier counts
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `entries has exactly CANONICAL_DEFAULT_COUNT CANONICAL_DEFAULT entries`() {
        assertEquals(
            AndroidAuthoritativePathAlignmentAudit.CANONICAL_DEFAULT_COUNT,
            AndroidAuthoritativePathAlignmentAudit.entries.count {
                it.tier == AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT
            }
        )
    }

    @Test
    fun `entries has exactly COMPAT_ALLOWED_COUNT COMPAT_ALLOWED entries`() {
        assertEquals(
            AndroidAuthoritativePathAlignmentAudit.COMPAT_ALLOWED_COUNT,
            AndroidAuthoritativePathAlignmentAudit.entries.count {
                it.tier == AndroidAuthoritativePathAlignmentAudit.BehaviorTier.COMPAT_ALLOWED
            }
        )
    }

    @Test
    fun `entries has exactly OBSERVATION_ONLY_COUNT OBSERVATION_ONLY entries`() {
        assertEquals(
            AndroidAuthoritativePathAlignmentAudit.OBSERVATION_ONLY_COUNT,
            AndroidAuthoritativePathAlignmentAudit.entries.count {
                it.tier == AndroidAuthoritativePathAlignmentAudit.BehaviorTier.OBSERVATION_ONLY
            }
        )
    }

    @Test
    fun `entries has exactly DEPRECATED_BUT_LIVE_COUNT DEPRECATED_BUT_LIVE entries`() {
        assertEquals(
            AndroidAuthoritativePathAlignmentAudit.DEPRECATED_BUT_LIVE_COUNT,
            AndroidAuthoritativePathAlignmentAudit.entries.count {
                it.tier == AndroidAuthoritativePathAlignmentAudit.BehaviorTier.DEPRECATED_BUT_LIVE
            }
        )
    }

    @Test
    fun `entries has exactly BLOCKED_RETIRED_COUNT BLOCKED_RETIRED entries`() {
        assertEquals(
            AndroidAuthoritativePathAlignmentAudit.BLOCKED_RETIRED_COUNT,
            AndroidAuthoritativePathAlignmentAudit.entries.count {
                it.tier == AndroidAuthoritativePathAlignmentAudit.BehaviorTier.BLOCKED_RETIRED
            }
        )
    }

    @Test
    fun `total entry count equals sum of all tier counts`() {
        val expected = AndroidAuthoritativePathAlignmentAudit.CANONICAL_DEFAULT_COUNT +
            AndroidAuthoritativePathAlignmentAudit.COMPAT_ALLOWED_COUNT +
            AndroidAuthoritativePathAlignmentAudit.OBSERVATION_ONLY_COUNT +
            AndroidAuthoritativePathAlignmentAudit.DEPRECATED_BUT_LIVE_COUNT +
            AndroidAuthoritativePathAlignmentAudit.BLOCKED_RETIRED_COUNT
        assertEquals(expected, AndroidAuthoritativePathAlignmentAudit.entries.size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Structural invariants
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `all behaviorIds are non-blank`() {
        AndroidAuthoritativePathAlignmentAudit.entries.forEach { entry ->
            assertTrue("behaviorId must be non-blank for entry: ${entry.label}", entry.behaviorId.isNotBlank())
        }
    }

    @Test
    fun `all behaviorIds are unique`() {
        val ids = AndroidAuthoritativePathAlignmentAudit.entries.map { it.behaviorId }
        assertEquals("All behaviorIds must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `all labels are non-blank`() {
        AndroidAuthoritativePathAlignmentAudit.entries.forEach { entry ->
            assertTrue("label must be non-blank for behaviorId: ${entry.behaviorId}", entry.label.isNotBlank())
        }
    }

    @Test
    fun `all descriptions are non-blank`() {
        AndroidAuthoritativePathAlignmentAudit.entries.forEach { entry ->
            assertTrue("description must be non-blank for behaviorId: ${entry.behaviorId}", entry.description.isNotBlank())
        }
    }

    @Test
    fun `all enforcedBy values are non-blank`() {
        AndroidAuthoritativePathAlignmentAudit.entries.forEach { entry ->
            assertTrue("enforcedBy must be non-blank for behaviorId: ${entry.behaviorId}", entry.enforcedBy.isNotBlank())
        }
    }

    @Test
    fun `INTRODUCED_PR is 65`() {
        assertEquals(65, AndroidAuthoritativePathAlignmentAudit.INTRODUCED_PR)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Canonical default behaviors
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `reconciliation_signal_emission is CANONICAL_DEFAULT`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("reconciliation_signal_emission")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT, entry!!.tier)
    }

    @Test
    fun `runtime_truth_snapshot is CANONICAL_DEFAULT`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("runtime_truth_snapshot")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT, entry!!.tier)
    }

    @Test
    fun `truth_reconciliation_reducer is CANONICAL_DEFAULT`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("truth_reconciliation_reducer")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT, entry!!.tier)
    }

    @Test
    fun `delegated_execution_signal_emission is CANONICAL_DEFAULT`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("delegated_execution_signal_emission")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT, entry!!.tier)
    }

    @Test
    fun `device_readiness_report is CANONICAL_DEFAULT`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("device_readiness_report")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT, entry!!.tier)
    }

    @Test
    fun `rollout_safe_defaults is CANONICAL_DEFAULT`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("rollout_safe_defaults")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT, entry!!.tier)
    }

    @Test
    fun `compat_legacy_blocking_canonical_confirm is CANONICAL_DEFAULT`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("compat_legacy_blocking_canonical_confirm")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT, entry!!.tier)
    }

    @Test
    fun `all CANONICAL_DEFAULT entries have CANONICAL_PARTICIPANT_EVIDENCE signal semantics`() {
        AndroidAuthoritativePathAlignmentAudit.canonicalDefaultEntries().forEach { entry ->
            assertEquals(
                "CANONICAL_DEFAULT entry '${entry.behaviorId}' must have CANONICAL_PARTICIPANT_EVIDENCE semantics",
                AndroidAuthoritativePathAlignmentAudit.SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE,
                entry.signalSemantics
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Compat allowed behaviors
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `legacy_message_type_remap is COMPAT_ALLOWED`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("legacy_message_type_remap")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.COMPAT_ALLOWED, entry!!.tier)
    }

    @Test
    fun `long_tail_compat_handlers is COMPAT_ALLOWED`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("long_tail_compat_handlers")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.COMPAT_ALLOWED, entry!!.tier)
    }

    @Test
    fun `execution_contract_compat_validator is COMPAT_ALLOWED`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("execution_contract_compat_validator")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.COMPAT_ALLOWED, entry!!.tier)
    }

    @Test
    fun `no COMPAT_ALLOWED entry has CANONICAL_PARTICIPANT_EVIDENCE signal semantics`() {
        AndroidAuthoritativePathAlignmentAudit.compatAllowedEntries().forEach { entry ->
            assertFalse(
                "COMPAT_ALLOWED entry '${entry.behaviorId}' must not have CANONICAL_PARTICIPANT_EVIDENCE semantics",
                entry.signalSemantics == AndroidAuthoritativePathAlignmentAudit.SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE
            )
        }
    }

    @Test
    fun `all COMPAT_ALLOWED entries have a deferralNote`() {
        AndroidAuthoritativePathAlignmentAudit.compatAllowedEntries().forEach { entry ->
            assertNotNull("COMPAT_ALLOWED entry '${entry.behaviorId}' must have a deferralNote", entry.deferralNote)
            assertTrue("deferralNote must be non-blank for '${entry.behaviorId}'", entry.deferralNote!!.isNotBlank())
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Observation-only behaviors
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `compat_surface_retirement_registry_decommission is OBSERVATION_ONLY`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("compat_surface_retirement_registry_decommission")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.OBSERVATION_ONLY, entry!!.tier)
    }

    @Test
    fun `compat_legacy_blocking_observation_only is OBSERVATION_ONLY`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("compat_legacy_blocking_observation_only")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.OBSERVATION_ONLY, entry!!.tier)
    }

    @Test
    fun `runtime_observability_metadata is OBSERVATION_ONLY`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("runtime_observability_metadata")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.OBSERVATION_ONLY, entry!!.tier)
    }

    @Test
    fun `all OBSERVATION_ONLY entries have OBSERVATION_SIGNAL signal semantics`() {
        AndroidAuthoritativePathAlignmentAudit.observationOnlyEntries().forEach { entry ->
            assertEquals(
                "OBSERVATION_ONLY entry '${entry.behaviorId}' must have OBSERVATION_SIGNAL semantics",
                AndroidAuthoritativePathAlignmentAudit.SignalSemantics.OBSERVATION_SIGNAL,
                entry.signalSemantics
            )
        }
    }

    @Test
    fun `no OBSERVATION_ONLY entry has CANONICAL_PARTICIPANT_EVIDENCE signal semantics`() {
        AndroidAuthoritativePathAlignmentAudit.observationOnlyEntries().forEach { entry ->
            assertFalse(
                "OBSERVATION_ONLY entry '${entry.behaviorId}' must not have CANONICAL_PARTICIPANT_EVIDENCE semantics",
                entry.signalSemantics == AndroidAuthoritativePathAlignmentAudit.SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Deprecated-but-live behaviors
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `high_risk_active_compat_surfaces is DEPRECATED_BUT_LIVE`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("high_risk_active_compat_surfaces")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.DEPRECATED_BUT_LIVE, entry!!.tier)
    }

    @Test
    fun `legacy_fallback_local_after_delegated_failure is DEPRECATED_BUT_LIVE`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("legacy_fallback_local_after_delegated_failure")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.DEPRECATED_BUT_LIVE, entry!!.tier)
    }

    @Test
    fun `ambiguous_legacy_state_quarantine is DEPRECATED_BUT_LIVE`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("ambiguous_legacy_state_quarantine")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.DEPRECATED_BUT_LIVE, entry!!.tier)
    }

    @Test
    fun `all DEPRECATED_BUT_LIVE entries have LEGACY_INFLUENCED signal semantics`() {
        AndroidAuthoritativePathAlignmentAudit.deprecatedButLiveEntries().forEach { entry ->
            assertEquals(
                "DEPRECATED_BUT_LIVE entry '${entry.behaviorId}' must have LEGACY_INFLUENCED semantics",
                AndroidAuthoritativePathAlignmentAudit.SignalSemantics.LEGACY_INFLUENCED,
                entry.signalSemantics
            )
        }
    }

    @Test
    fun `all DEPRECATED_BUT_LIVE entries have a deferralNote`() {
        AndroidAuthoritativePathAlignmentAudit.deprecatedButLiveEntries().forEach { entry ->
            assertNotNull("DEPRECATED_BUT_LIVE entry '${entry.behaviorId}' must have a deferralNote", entry.deferralNote)
            assertTrue("deferralNote must be non-blank for '${entry.behaviorId}'", entry.deferralNote!!.isNotBlank())
        }
    }

    @Test
    fun `no DEPRECATED_BUT_LIVE entry has CANONICAL_PARTICIPANT_EVIDENCE signal semantics`() {
        AndroidAuthoritativePathAlignmentAudit.deprecatedButLiveEntries().forEach { entry ->
            assertFalse(
                "DEPRECATED_BUT_LIVE entry '${entry.behaviorId}' must not have CANONICAL_PARTICIPANT_EVIDENCE semantics",
                entry.signalSemantics == AndroidAuthoritativePathAlignmentAudit.SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Blocked/retired behaviors
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `legacy_runtime_path_blocked is BLOCKED_RETIRED`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("legacy_runtime_path_blocked")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.BLOCKED_RETIRED, entry!!.tier)
    }

    @Test
    fun `compat_contract_emit_suppressed is BLOCKED_RETIRED`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("compat_contract_emit_suppressed")
        assertNotNull(entry)
        assertEquals(AndroidAuthoritativePathAlignmentAudit.BehaviorTier.BLOCKED_RETIRED, entry!!.tier)
    }

    @Test
    fun `all BLOCKED_RETIRED entries have BLOCKED signal semantics`() {
        AndroidAuthoritativePathAlignmentAudit.blockedRetiredEntries().forEach { entry ->
            assertEquals(
                "BLOCKED_RETIRED entry '${entry.behaviorId}' must have BLOCKED semantics",
                AndroidAuthoritativePathAlignmentAudit.SignalSemantics.BLOCKED,
                entry.signalSemantics
            )
        }
    }

    @Test
    fun `no BLOCKED_RETIRED entry has CANONICAL_PARTICIPANT_EVIDENCE signal semantics`() {
        AndroidAuthoritativePathAlignmentAudit.blockedRetiredEntries().forEach { entry ->
            assertFalse(
                "BLOCKED_RETIRED entry '${entry.behaviorId}' must not have CANONICAL_PARTICIPANT_EVIDENCE semantics",
                entry.signalSemantics == AndroidAuthoritativePathAlignmentAudit.SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // isCanonicalDefault
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isCanonicalDefault returns true for reconciliation_signal_emission`() {
        assertTrue(AndroidAuthoritativePathAlignmentAudit.isCanonicalDefault("reconciliation_signal_emission"))
    }

    @Test
    fun `isCanonicalDefault returns true for truth_reconciliation_reducer`() {
        assertTrue(AndroidAuthoritativePathAlignmentAudit.isCanonicalDefault("truth_reconciliation_reducer"))
    }

    @Test
    fun `isCanonicalDefault returns false for legacy_message_type_remap`() {
        assertFalse(AndroidAuthoritativePathAlignmentAudit.isCanonicalDefault("legacy_message_type_remap"))
    }

    @Test
    fun `isCanonicalDefault returns false for legacy_runtime_path_blocked`() {
        assertFalse(AndroidAuthoritativePathAlignmentAudit.isCanonicalDefault("legacy_runtime_path_blocked"))
    }

    @Test
    fun `isCanonicalDefault returns false for unknown behaviorId`() {
        assertFalse(AndroidAuthoritativePathAlignmentAudit.isCanonicalDefault("nonexistent_behavior"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // isDeprecatedOrBlocked
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isDeprecatedOrBlocked returns true for high_risk_active_compat_surfaces`() {
        assertTrue(AndroidAuthoritativePathAlignmentAudit.isDeprecatedOrBlocked("high_risk_active_compat_surfaces"))
    }

    @Test
    fun `isDeprecatedOrBlocked returns true for legacy_runtime_path_blocked`() {
        assertTrue(AndroidAuthoritativePathAlignmentAudit.isDeprecatedOrBlocked("legacy_runtime_path_blocked"))
    }

    @Test
    fun `isDeprecatedOrBlocked returns false for reconciliation_signal_emission`() {
        assertFalse(AndroidAuthoritativePathAlignmentAudit.isDeprecatedOrBlocked("reconciliation_signal_emission"))
    }

    @Test
    fun `isDeprecatedOrBlocked returns false for legacy_message_type_remap`() {
        assertFalse(AndroidAuthoritativePathAlignmentAudit.isDeprecatedOrBlocked("legacy_message_type_remap"))
    }

    @Test
    fun `isDeprecatedOrBlocked returns false for unknown behaviorId`() {
        assertFalse(AndroidAuthoritativePathAlignmentAudit.isDeprecatedOrBlocked("nonexistent_behavior"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // deferredEntries
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `all COMPAT_ALLOWED entries appear in deferredEntries`() {
        val deferredIds = AndroidAuthoritativePathAlignmentAudit.deferredEntries().map { it.behaviorId }.toSet()
        AndroidAuthoritativePathAlignmentAudit.compatAllowedEntries().forEach { entry ->
            assertTrue("COMPAT_ALLOWED entry '${entry.behaviorId}' must appear in deferredEntries", entry.behaviorId in deferredIds)
        }
    }

    @Test
    fun `all DEPRECATED_BUT_LIVE entries appear in deferredEntries`() {
        val deferredIds = AndroidAuthoritativePathAlignmentAudit.deferredEntries().map { it.behaviorId }.toSet()
        AndroidAuthoritativePathAlignmentAudit.deprecatedButLiveEntries().forEach { entry ->
            assertTrue("DEPRECATED_BUT_LIVE entry '${entry.behaviorId}' must appear in deferredEntries", entry.behaviorId in deferredIds)
        }
    }

    @Test
    fun `no CANONICAL_DEFAULT entry appears in deferredEntries`() {
        val deferredIds = AndroidAuthoritativePathAlignmentAudit.deferredEntries().map { it.behaviorId }.toSet()
        AndroidAuthoritativePathAlignmentAudit.canonicalDefaultEntries().forEach { entry ->
            assertFalse("CANONICAL_DEFAULT entry '${entry.behaviorId}' must not appear in deferredEntries", entry.behaviorId in deferredIds)
        }
    }

    @Test
    fun `no BLOCKED_RETIRED entry appears in deferredEntries`() {
        val deferredIds = AndroidAuthoritativePathAlignmentAudit.deferredEntries().map { it.behaviorId }.toSet()
        AndroidAuthoritativePathAlignmentAudit.blockedRetiredEntries().forEach { entry ->
            assertFalse("BLOCKED_RETIRED entry '${entry.behaviorId}' must not appear in deferredEntries", entry.behaviorId in deferredIds)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // bySignalSemantics
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `bySignalSemantics CANONICAL_PARTICIPANT_EVIDENCE returns only CANONICAL_DEFAULT entries`() {
        val results = AndroidAuthoritativePathAlignmentAudit.bySignalSemantics(
            AndroidAuthoritativePathAlignmentAudit.SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE
        )
        results.forEach { entry ->
            assertEquals(
                "Entry '${entry.behaviorId}' from bySignalSemantics(CANONICAL_PARTICIPANT_EVIDENCE) must be CANONICAL_DEFAULT",
                AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT,
                entry.tier
            )
        }
    }

    @Test
    fun `bySignalSemantics BLOCKED returns only BLOCKED_RETIRED entries`() {
        val results = AndroidAuthoritativePathAlignmentAudit.bySignalSemantics(
            AndroidAuthoritativePathAlignmentAudit.SignalSemantics.BLOCKED
        )
        results.forEach { entry ->
            assertEquals(
                "Entry '${entry.behaviorId}' from bySignalSemantics(BLOCKED) must be BLOCKED_RETIRED",
                AndroidAuthoritativePathAlignmentAudit.BehaviorTier.BLOCKED_RETIRED,
                entry.tier
            )
        }
    }

    @Test
    fun `bySignalSemantics OBSERVATION_SIGNAL is non-empty`() {
        assertTrue(
            AndroidAuthoritativePathAlignmentAudit.bySignalSemantics(
                AndroidAuthoritativePathAlignmentAudit.SignalSemantics.OBSERVATION_SIGNAL
            ).isNotEmpty()
        )
    }

    @Test
    fun `bySignalSemantics LEGACY_INFLUENCED is non-empty`() {
        assertTrue(
            AndroidAuthoritativePathAlignmentAudit.bySignalSemantics(
                AndroidAuthoritativePathAlignmentAudit.SignalSemantics.LEGACY_INFLUENCED
            ).isNotEmpty()
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Query helpers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `canonicalDefaultEntries size equals CANONICAL_DEFAULT_COUNT`() {
        assertEquals(
            AndroidAuthoritativePathAlignmentAudit.CANONICAL_DEFAULT_COUNT,
            AndroidAuthoritativePathAlignmentAudit.canonicalDefaultEntries().size
        )
    }

    @Test
    fun `compatAllowedEntries size equals COMPAT_ALLOWED_COUNT`() {
        assertEquals(
            AndroidAuthoritativePathAlignmentAudit.COMPAT_ALLOWED_COUNT,
            AndroidAuthoritativePathAlignmentAudit.compatAllowedEntries().size
        )
    }

    @Test
    fun `observationOnlyEntries size equals OBSERVATION_ONLY_COUNT`() {
        assertEquals(
            AndroidAuthoritativePathAlignmentAudit.OBSERVATION_ONLY_COUNT,
            AndroidAuthoritativePathAlignmentAudit.observationOnlyEntries().size
        )
    }

    @Test
    fun `deprecatedButLiveEntries size equals DEPRECATED_BUT_LIVE_COUNT`() {
        assertEquals(
            AndroidAuthoritativePathAlignmentAudit.DEPRECATED_BUT_LIVE_COUNT,
            AndroidAuthoritativePathAlignmentAudit.deprecatedButLiveEntries().size
        )
    }

    @Test
    fun `blockedRetiredEntries size equals BLOCKED_RETIRED_COUNT`() {
        assertEquals(
            AndroidAuthoritativePathAlignmentAudit.BLOCKED_RETIRED_COUNT,
            AndroidAuthoritativePathAlignmentAudit.blockedRetiredEntries().size
        )
    }

    @Test
    fun `forId returns entry with correct behaviorId`() {
        val entry = AndroidAuthoritativePathAlignmentAudit.forId("reconciliation_signal_emission")
        assertNotNull(entry)
        assertEquals("reconciliation_signal_emission", entry!!.behaviorId)
    }

    @Test
    fun `forId returns null for unknown id`() {
        assertNull(AndroidAuthoritativePathAlignmentAudit.forId("nonexistent_behavior_id"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // StabilizationBaseline — PR-65 registration
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `StabilizationBaseline contains exactly one entry with introducedPr == 65`() {
        val pr65Entries = StabilizationBaseline.entries.filter { it.introducedPr == 65 }
        assertEquals(1, pr65Entries.size)
    }

    @Test
    fun `PR-65 entry has surfaceId android-authoritative-path-alignment-audit`() {
        val entry = StabilizationBaseline.entries.firstOrNull { it.introducedPr == 65 }
        assertNotNull(entry)
        assertEquals("android-authoritative-path-alignment-audit", entry!!.surfaceId)
    }

    @Test
    fun `PR-65 entry has stability CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.entries.firstOrNull { it.introducedPr == 65 }
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Acceptance criterion 1 — canonical vs compat/legacy distinction is explicit
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `CANONICAL_DEFAULT entries outnumber BLOCKED_RETIRED entries`() {
        assertTrue(
            "Canonical behaviors must outnumber blocked behaviors",
            AndroidAuthoritativePathAlignmentAudit.canonicalDefaultEntries().size >
                AndroidAuthoritativePathAlignmentAudit.blockedRetiredEntries().size
        )
    }

    @Test
    fun `compat path legacy_message_type_remap is NOT isCanonicalDefault`() {
        assertFalse(AndroidAuthoritativePathAlignmentAudit.isCanonicalDefault("legacy_message_type_remap"))
    }

    @Test
    fun `blocked path legacy_runtime_path_blocked is NOT isCanonicalDefault`() {
        assertFalse(AndroidAuthoritativePathAlignmentAudit.isCanonicalDefault("legacy_runtime_path_blocked"))
    }

    @Test
    fun `canonical path reconciliation_signal_emission IS isCanonicalDefault`() {
        assertTrue(AndroidAuthoritativePathAlignmentAudit.isCanonicalDefault("reconciliation_signal_emission"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Acceptance criterion 2 — deprecated behaviors are default-off
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `all DEPRECATED_BUT_LIVE entries are isDeprecatedOrBlocked`() {
        AndroidAuthoritativePathAlignmentAudit.deprecatedButLiveEntries().forEach { entry ->
            assertTrue(
                "DEPRECATED_BUT_LIVE entry '${entry.behaviorId}' must be isDeprecatedOrBlocked",
                AndroidAuthoritativePathAlignmentAudit.isDeprecatedOrBlocked(entry.behaviorId)
            )
        }
    }

    @Test
    fun `all BLOCKED_RETIRED entries are isDeprecatedOrBlocked`() {
        AndroidAuthoritativePathAlignmentAudit.blockedRetiredEntries().forEach { entry ->
            assertTrue(
                "BLOCKED_RETIRED entry '${entry.behaviorId}' must be isDeprecatedOrBlocked",
                AndroidAuthoritativePathAlignmentAudit.isDeprecatedOrBlocked(entry.behaviorId)
            )
        }
    }

    @Test
    fun `no CANONICAL_DEFAULT entry is isDeprecatedOrBlocked`() {
        AndroidAuthoritativePathAlignmentAudit.canonicalDefaultEntries().forEach { entry ->
            assertFalse(
                "CANONICAL_DEFAULT entry '${entry.behaviorId}' must not be isDeprecatedOrBlocked",
                AndroidAuthoritativePathAlignmentAudit.isDeprecatedOrBlocked(entry.behaviorId)
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Acceptance criterion 3 — V2 can classify every Android signal
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `every entry has a non-null signalSemantics`() {
        AndroidAuthoritativePathAlignmentAudit.entries.forEach { entry ->
            assertNotNull("signalSemantics must be non-null for behaviorId: ${entry.behaviorId}", entry.signalSemantics)
        }
    }

    @Test
    fun `CANONICAL_PARTICIPANT_EVIDENCE entries all come from CANONICAL_DEFAULT tier`() {
        AndroidAuthoritativePathAlignmentAudit.bySignalSemantics(
            AndroidAuthoritativePathAlignmentAudit.SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE
        ).forEach { entry ->
            assertEquals(
                "CANONICAL_PARTICIPANT_EVIDENCE must only appear in CANONICAL_DEFAULT entries",
                AndroidAuthoritativePathAlignmentAudit.BehaviorTier.CANONICAL_DEFAULT,
                entry.tier
            )
        }
    }

    @Test
    fun `BLOCKED semantics entries all come from BLOCKED_RETIRED tier`() {
        AndroidAuthoritativePathAlignmentAudit.bySignalSemantics(
            AndroidAuthoritativePathAlignmentAudit.SignalSemantics.BLOCKED
        ).forEach { entry ->
            assertEquals(
                "BLOCKED signal semantics must only appear in BLOCKED_RETIRED entries",
                AndroidAuthoritativePathAlignmentAudit.BehaviorTier.BLOCKED_RETIRED,
                entry.tier
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Acceptance criterion 4 — deferred cleanup is explicitly listed
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `deferredEntries is non-empty`() {
        assertTrue(
            "There must be at least one deferred entry",
            AndroidAuthoritativePathAlignmentAudit.deferredEntries().isNotEmpty()
        )
    }

    @Test
    fun `every deferred entry has a non-blank deferralNote`() {
        AndroidAuthoritativePathAlignmentAudit.deferredEntries().forEach { entry ->
            assertNotNull("deferralNote must be non-null for '${entry.behaviorId}'", entry.deferralNote)
            assertTrue("deferralNote must be non-blank for '${entry.behaviorId}'", entry.deferralNote!!.isNotBlank())
        }
    }
}
