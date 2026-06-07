package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-71 — Offline queue replay ordering / authority policy: acceptance and structural tests.
 *
 * Validates the four formal requirements for making Android offline queue replay ordering /
 * authority semantics programmatically consumable:
 *
 *  1. **Deferred semantics are correctly output** — REPLAY_ORDERING_GUARANTEE is DEFERRED;
 *     [OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.hasDeferredItems] returns `true`;
 *     [OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.toWireMap] contains "deferred"
 *     for that dimension.
 *
 *  2. **PARTIALLY_SUPPORTED and NON_AUTHORITATIVE are correctly distinguished** — they are
 *     separate [OfflineQueueReplayPolicy.ReplayPolicyStatus] values with distinct wire values
 *     and different semantic meanings; DUPLICATE_AVOIDANCE ≠ REPLAY_AUTHORITY status.
 *
 *  3. **Missing evidence is not optimistically upgraded to SUPPORTED** — DEFERRED,
 *     NON_AUTHORITATIVE, PARTIALLY_SUPPORTED, and ACCEPTED_LIMITATION entries all prevent
 *     [OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.hasFullSupport] from returning
 *     `true`.
 *
 *  4. **Policy can be output as a machine-consumable report/artifact** —
 *     [OfflineQueueReplayPolicy.buildReport] returns an
 *     [OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport] with a stable
 *     [OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.toWireMap] output.
 *
 * ## Test matrix
 *
 * ### Module-level constants
 *  - INTRODUCED_PR is 71
 *  - INTRODUCED_PR_TITLE is non-blank
 *  - SCHEMA_VERSION is "1.0"
 *  - DESCRIPTION is non-blank
 *
 * ### Structural invariants
 *  - ENTRY_COUNT is 5
 *  - allEntries has exactly ENTRY_COUNT entries
 *  - every ReplaySemanticDimension is covered exactly once in allEntries
 *  - all entries have non-blank rationale
 *  - all entries have non-blank evidenceReference
 *  - all entries have non-blank limitations
 *  - all entries have non-blank v2ConsumptionPath
 *  - status count constants sum to ENTRY_COUNT
 *  - NOT_APPLICABLE_COUNT is 0
 *
 * ### ReplaySemanticDimension — wire values
 *  - TASK_REPLAY_EXISTENCE wireValue is "task_replay_existence"
 *  - REPLAY_ORDERING_GUARANTEE wireValue is "replay_ordering_guarantee"
 *  - REPLAY_AUTHORITY wireValue is "replay_authority"
 *  - DUPLICATE_AVOIDANCE wireValue is "duplicate_avoidance"
 *  - EVENTUAL_RECOVERY wireValue is "eventual_recovery"
 *  - all five wire values are distinct
 *  - fromWireValue round-trips all five values
 *  - fromWireValue returns null for unknown value
 *  - fromWireValue returns null for null
 *  - ALL_WIRE_VALUES has exactly five entries
 *
 * ### ReplayPolicyStatus — wire values
 *  - SUPPORTED wireValue is "supported"
 *  - PARTIALLY_SUPPORTED wireValue is "partially_supported"
 *  - DEFERRED wireValue is "deferred"
 *  - NON_AUTHORITATIVE wireValue is "non_authoritative"
 *  - ACCEPTED_LIMITATION wireValue is "accepted_limitation"
 *  - NOT_APPLICABLE wireValue is "not_applicable"
 *  - all six wire values are distinct
 *  - DEFERRED and NON_AUTHORITATIVE have distinct wire values
 *  - PARTIALLY_SUPPORTED and NON_AUTHORITATIVE have distinct wire values
 *  - fromWireValue round-trips all six values
 *  - fromWireValue returns null for unknown value
 *  - fromWireValue returns null for null
 *  - ALL_WIRE_VALUES has exactly six entries
 *  - isOpenBoundary: DEFERRED returns true
 *  - isOpenBoundary: NON_AUTHORITATIVE returns true
 *  - isOpenBoundary: PARTIALLY_SUPPORTED returns true
 *  - isOpenBoundary: SUPPORTED returns false
 *  - isOpenBoundary: ACCEPTED_LIMITATION returns false
 *  - isOpenBoundary: NOT_APPLICABLE returns false
 *
 * ### Policy entries — spot checks (AC2: distinguish status values)
 *  - TASK_REPLAY_EXISTENCE is SUPPORTED
 *  - REPLAY_ORDERING_GUARANTEE is DEFERRED
 *  - REPLAY_AUTHORITY is NON_AUTHORITATIVE
 *  - DUPLICATE_AVOIDANCE is PARTIALLY_SUPPORTED
 *  - EVENTUAL_RECOVERY is ACCEPTED_LIMITATION
 *  - DUPLICATE_AVOIDANCE status != REPLAY_AUTHORITY status
 *  - REPLAY_ORDERING_GUARANTEE status != REPLAY_AUTHORITY status
 *
 * ### Deferred semantics output (AC1)
 *  - hasDeferredItems returns true
 *  - hasFullSupport returns false
 *  - wire map contains replay_ordering_guarantee_status = "deferred"
 *  - DEFERRED_COUNT is 1
 *  - entriesForStatus(DEFERRED) has exactly DEFERRED_COUNT entries
 *  - entriesForStatus(DEFERRED) contains REPLAY_ORDERING_GUARANTEE
 *
 * ### Distinction between PARTIALLY_SUPPORTED and NON_AUTHORITATIVE (AC2)
 *  - PARTIALLY_SUPPORTED != NON_AUTHORITATIVE
 *  - wire values are distinct
 *  - DUPLICATE_AVOIDANCE entry status == PARTIALLY_SUPPORTED
 *  - REPLAY_AUTHORITY entry status == NON_AUTHORITATIVE
 *  - wire map duplicate_avoidance_status == "partially_supported"
 *  - wire map replay_authority_status == "non_authoritative"
 *
 * ### No optimistic upgrade to SUPPORTED (AC3)
 *  - hasFullSupport returns false (deferred entry present)
 *  - a report built with only SUPPORTED entries does have full support (control case)
 *  - hasFullSupport returns false for a report with any DEFERRED entry
 *  - hasFullSupport returns false for a report with any NON_AUTHORITATIVE entry
 *  - hasFullSupport returns false for a report with any ACCEPTED_LIMITATION entry
 *  - hasFullSupport returns false for a report with any PARTIALLY_SUPPORTED entry
 *
 * ### Report / artifact output (AC4)
 *  - buildReport returns non-null report
 *  - report has ENTRY_COUNT entries
 *  - toWireMap contains KEY_SCHEMA_VERSION
 *  - toWireMap contains KEY_INTRODUCED_PR
 *  - toWireMap contains KEY_GENERATED_AT_MS
 *  - toWireMap contains KEY_ENTRY_COUNT
 *  - toWireMap contains KEY_HAS_FULL_SUPPORT
 *  - toWireMap contains KEY_HAS_DEFERRED_ITEMS
 *  - toWireMap contains KEY_HAS_NON_AUTHORITATIVE_ITEMS
 *  - toWireMap has_full_support = false
 *  - toWireMap has_deferred_items = true
 *  - toWireMap has_non_authoritative_items = true
 *  - toWireMap schema_version = "1.0"
 *  - toWireMap entry_count = 5
 *  - toWireMap introduced_pr = 71
 *  - all five dimension status keys are present in wire map
 *  - wire map is immutable (returns read-only map)
 *
 * ### Query helpers
 *  - entryFor returns entry for each known dimension
 *  - entryFor returns null for hypothetical unknown (no unknown dimensions possible, so
 *    verified via coverage of all five)
 *  - entriesForStatus returns correct entries for each status
 *  - statusFor returns correct status for each dimension
 *  - statusFor returns null for missing dimension (report with partial entries)
 *
 * ### AndroidReadinessEvidenceSurface integration
 *  - offline_queue_replay_ordering_policy evidence entry is present and ADVISORY
 *  - offline_queue_replay_ordering_authority_semantics deferred item is present
 *  - deferred item has SIGNAL_REPLAY_DUPLICATE_SAFETY dimension
 *  - deferred item description references DEFERRED
 *  - deferred item description references NON_AUTHORITATIVE
 */
class Pr71OfflineQueueReplayPolicyTest {

    private val policy = OfflineQueueReplayPolicy

    // ── Module-level constants ─────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 71`() {
        assertEquals(71, policy.INTRODUCED_PR)
    }

    @Test
    fun `INTRODUCED_PR_TITLE is non-blank`() {
        assertTrue(policy.INTRODUCED_PR_TITLE.isNotBlank())
    }

    @Test
    fun `SCHEMA_VERSION is 1_0`() {
        assertEquals("1.0", policy.SCHEMA_VERSION)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(policy.DESCRIPTION.isNotBlank())
    }

    // ── Structural invariants ─────────────────────────────────────────────────

    @Test
    fun `ENTRY_COUNT is 5`() {
        assertEquals(5, policy.ENTRY_COUNT)
    }

    @Test
    fun `allEntries has exactly ENTRY_COUNT entries`() {
        assertEquals(policy.ENTRY_COUNT, policy.allEntries.size)
    }

    @Test
    fun `every ReplaySemanticDimension is covered exactly once`() {
        val dimensions = policy.allEntries.map { it.dimension }
        val expected = OfflineQueueReplayPolicy.ReplaySemanticDimension.values().toSet()
        assertEquals(
            "Every ReplaySemanticDimension must appear exactly once",
            expected,
            dimensions.toSet()
        )
        assertEquals(
            "No dimension should appear more than once",
            dimensions.size,
            dimensions.toSet().size
        )
    }

    @Test
    fun `all entries have non-blank rationale`() {
        policy.allEntries.forEach {
            assertTrue("rationale blank for ${it.dimension}", it.rationale.isNotBlank())
        }
    }

    @Test
    fun `all entries have non-blank evidenceReference`() {
        policy.allEntries.forEach {
            assertTrue(
                "evidenceReference blank for ${it.dimension}",
                it.evidenceReference.isNotBlank()
            )
        }
    }

    @Test
    fun `all entries have non-blank limitations`() {
        policy.allEntries.forEach {
            assertTrue("limitations blank for ${it.dimension}", it.limitations.isNotBlank())
        }
    }

    @Test
    fun `all entries have non-blank v2ConsumptionPath`() {
        policy.allEntries.forEach {
            assertTrue(
                "v2ConsumptionPath blank for ${it.dimension}",
                it.v2ConsumptionPath.isNotBlank()
            )
        }
    }

    @Test
    fun `status count constants sum to ENTRY_COUNT`() {
        val total = policy.SUPPORTED_COUNT +
            policy.PARTIALLY_SUPPORTED_COUNT +
            policy.DEFERRED_COUNT +
            policy.NON_AUTHORITATIVE_COUNT +
            policy.ACCEPTED_LIMITATION_COUNT +
            policy.NOT_APPLICABLE_COUNT
        assertEquals(policy.ENTRY_COUNT, total)
    }

    @Test
    fun `NOT_APPLICABLE_COUNT is 0`() {
        assertEquals(0, policy.NOT_APPLICABLE_COUNT)
    }

    // ── ReplaySemanticDimension — wire values ─────────────────────────────────

    @Test
    fun `TASK_REPLAY_EXISTENCE wireValue is task_replay_existence`() {
        assertEquals(
            "task_replay_existence",
            OfflineQueueReplayPolicy.ReplaySemanticDimension.TASK_REPLAY_EXISTENCE.wireValue
        )
    }

    @Test
    fun `REPLAY_ORDERING_GUARANTEE wireValue is replay_ordering_guarantee`() {
        assertEquals(
            "replay_ordering_guarantee",
            OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_ORDERING_GUARANTEE.wireValue
        )
    }

    @Test
    fun `REPLAY_AUTHORITY wireValue is replay_authority`() {
        assertEquals(
            "replay_authority",
            OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_AUTHORITY.wireValue
        )
    }

    @Test
    fun `DUPLICATE_AVOIDANCE wireValue is duplicate_avoidance`() {
        assertEquals(
            "duplicate_avoidance",
            OfflineQueueReplayPolicy.ReplaySemanticDimension.DUPLICATE_AVOIDANCE.wireValue
        )
    }

    @Test
    fun `EVENTUAL_RECOVERY wireValue is eventual_recovery`() {
        assertEquals(
            "eventual_recovery",
            OfflineQueueReplayPolicy.ReplaySemanticDimension.EVENTUAL_RECOVERY.wireValue
        )
    }

    @Test
    fun `all five ReplaySemanticDimension wire values are distinct`() {
        val wireValues = OfflineQueueReplayPolicy.ReplaySemanticDimension.values()
            .map { it.wireValue }
        assertEquals("duplicate wire values found", wireValues.size, wireValues.toSet().size)
    }

    @Test
    fun `fromWireValue round-trips all five ReplaySemanticDimension values`() {
        OfflineQueueReplayPolicy.ReplaySemanticDimension.values().forEach { dim ->
            assertEquals(
                "fromWireValue round-trip failed for $dim",
                dim,
                OfflineQueueReplayPolicy.ReplaySemanticDimension.fromWireValue(dim.wireValue)
            )
        }
    }

    @Test
    fun `fromWireValue returns null for unknown ReplaySemanticDimension`() {
        assertNull(
            OfflineQueueReplayPolicy.ReplaySemanticDimension.fromWireValue("unknown_dimension")
        )
    }

    @Test
    fun `fromWireValue returns null for null ReplaySemanticDimension`() {
        assertNull(OfflineQueueReplayPolicy.ReplaySemanticDimension.fromWireValue(null))
    }

    @Test
    fun `ALL_WIRE_VALUES has exactly five ReplaySemanticDimension entries`() {
        assertEquals(5, OfflineQueueReplayPolicy.ReplaySemanticDimension.ALL_WIRE_VALUES.size)
    }

    // ── ReplayPolicyStatus — wire values ──────────────────────────────────────

    @Test
    fun `SUPPORTED wireValue is supported`() {
        assertEquals(
            "supported",
            OfflineQueueReplayPolicy.ReplayPolicyStatus.SUPPORTED.wireValue
        )
    }

    @Test
    fun `PARTIALLY_SUPPORTED wireValue is partially_supported`() {
        assertEquals(
            "partially_supported",
            OfflineQueueReplayPolicy.ReplayPolicyStatus.PARTIALLY_SUPPORTED.wireValue
        )
    }

    @Test
    fun `DEFERRED wireValue is deferred`() {
        assertEquals(
            "deferred",
            OfflineQueueReplayPolicy.ReplayPolicyStatus.DEFERRED.wireValue
        )
    }

    @Test
    fun `NON_AUTHORITATIVE wireValue is non_authoritative`() {
        assertEquals(
            "non_authoritative",
            OfflineQueueReplayPolicy.ReplayPolicyStatus.NON_AUTHORITATIVE.wireValue
        )
    }

    @Test
    fun `ACCEPTED_LIMITATION wireValue is accepted_limitation`() {
        assertEquals(
            "accepted_limitation",
            OfflineQueueReplayPolicy.ReplayPolicyStatus.ACCEPTED_LIMITATION.wireValue
        )
    }

    @Test
    fun `NOT_APPLICABLE wireValue is not_applicable`() {
        assertEquals(
            "not_applicable",
            OfflineQueueReplayPolicy.ReplayPolicyStatus.NOT_APPLICABLE.wireValue
        )
    }

    @Test
    fun `all six ReplayPolicyStatus wire values are distinct`() {
        val wireValues = OfflineQueueReplayPolicy.ReplayPolicyStatus.values().map { it.wireValue }
        assertEquals("duplicate wire values found", wireValues.size, wireValues.toSet().size)
    }

    @Test
    fun `DEFERRED and NON_AUTHORITATIVE have distinct wire values`() {
        assertNotEquals(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.DEFERRED.wireValue,
            OfflineQueueReplayPolicy.ReplayPolicyStatus.NON_AUTHORITATIVE.wireValue
        )
    }

    @Test
    fun `PARTIALLY_SUPPORTED and NON_AUTHORITATIVE have distinct wire values`() {
        assertNotEquals(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.PARTIALLY_SUPPORTED.wireValue,
            OfflineQueueReplayPolicy.ReplayPolicyStatus.NON_AUTHORITATIVE.wireValue
        )
    }

    @Test
    fun `fromWireValue round-trips all six ReplayPolicyStatus values`() {
        OfflineQueueReplayPolicy.ReplayPolicyStatus.values().forEach { status ->
            assertEquals(
                "fromWireValue round-trip failed for $status",
                status,
                OfflineQueueReplayPolicy.ReplayPolicyStatus.fromWireValue(status.wireValue)
            )
        }
    }

    @Test
    fun `fromWireValue returns null for unknown ReplayPolicyStatus`() {
        assertNull(OfflineQueueReplayPolicy.ReplayPolicyStatus.fromWireValue("unknown_status"))
    }

    @Test
    fun `fromWireValue returns null for null ReplayPolicyStatus`() {
        assertNull(OfflineQueueReplayPolicy.ReplayPolicyStatus.fromWireValue(null))
    }

    @Test
    fun `ALL_WIRE_VALUES has exactly six ReplayPolicyStatus entries`() {
        assertEquals(6, OfflineQueueReplayPolicy.ReplayPolicyStatus.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `isOpenBoundary returns true for DEFERRED`() {
        assertTrue(OfflineQueueReplayPolicy.ReplayPolicyStatus.DEFERRED.isOpenBoundary())
    }

    @Test
    fun `isOpenBoundary returns true for NON_AUTHORITATIVE`() {
        assertTrue(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.NON_AUTHORITATIVE.isOpenBoundary()
        )
    }

    @Test
    fun `isOpenBoundary returns true for PARTIALLY_SUPPORTED`() {
        assertTrue(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.PARTIALLY_SUPPORTED.isOpenBoundary()
        )
    }

    @Test
    fun `isOpenBoundary returns false for SUPPORTED`() {
        assertFalse(OfflineQueueReplayPolicy.ReplayPolicyStatus.SUPPORTED.isOpenBoundary())
    }

    @Test
    fun `isOpenBoundary returns false for ACCEPTED_LIMITATION`() {
        assertFalse(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.ACCEPTED_LIMITATION.isOpenBoundary()
        )
    }

    @Test
    fun `isOpenBoundary returns false for NOT_APPLICABLE`() {
        assertFalse(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.NOT_APPLICABLE.isOpenBoundary()
        )
    }

    // ── Policy entries — spot checks ──────────────────────────────────────────

    @Test
    fun `TASK_REPLAY_EXISTENCE is SUPPORTED`() {
        val entry = policy.entryFor(
            OfflineQueueReplayPolicy.ReplaySemanticDimension.TASK_REPLAY_EXISTENCE
        )
        assertNotNull(entry)
        assertEquals(OfflineQueueReplayPolicy.ReplayPolicyStatus.SUPPORTED, entry!!.status)
    }

    @Test
    fun `REPLAY_ORDERING_GUARANTEE is DEFERRED`() {
        val entry = policy.entryFor(
            OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_ORDERING_GUARANTEE
        )
        assertNotNull(entry)
        assertEquals(OfflineQueueReplayPolicy.ReplayPolicyStatus.DEFERRED, entry!!.status)
    }

    @Test
    fun `REPLAY_AUTHORITY is NON_AUTHORITATIVE`() {
        val entry = policy.entryFor(
            OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_AUTHORITY
        )
        assertNotNull(entry)
        assertEquals(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.NON_AUTHORITATIVE,
            entry!!.status
        )
    }

    @Test
    fun `DUPLICATE_AVOIDANCE is PARTIALLY_SUPPORTED`() {
        val entry = policy.entryFor(
            OfflineQueueReplayPolicy.ReplaySemanticDimension.DUPLICATE_AVOIDANCE
        )
        assertNotNull(entry)
        assertEquals(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.PARTIALLY_SUPPORTED,
            entry!!.status
        )
    }

    @Test
    fun `EVENTUAL_RECOVERY is ACCEPTED_LIMITATION`() {
        val entry = policy.entryFor(
            OfflineQueueReplayPolicy.ReplaySemanticDimension.EVENTUAL_RECOVERY
        )
        assertNotNull(entry)
        assertEquals(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.ACCEPTED_LIMITATION,
            entry!!.status
        )
    }

    @Test
    fun `DUPLICATE_AVOIDANCE status is not the same as REPLAY_AUTHORITY status`() {
        val duplicateStatus = policy.entryFor(
            OfflineQueueReplayPolicy.ReplaySemanticDimension.DUPLICATE_AVOIDANCE
        )!!.status
        val authorityStatus = policy.entryFor(
            OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_AUTHORITY
        )!!.status
        assertNotEquals(
            "DUPLICATE_AVOIDANCE and REPLAY_AUTHORITY must have distinct statuses",
            duplicateStatus,
            authorityStatus
        )
    }

    @Test
    fun `REPLAY_ORDERING_GUARANTEE status is not the same as REPLAY_AUTHORITY status`() {
        val orderingStatus = policy.entryFor(
            OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_ORDERING_GUARANTEE
        )!!.status
        val authorityStatus = policy.entryFor(
            OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_AUTHORITY
        )!!.status
        assertNotEquals(
            "REPLAY_ORDERING_GUARANTEE and REPLAY_AUTHORITY must have distinct statuses",
            orderingStatus,
            authorityStatus
        )
    }

    // ── Deferred semantics output (AC1) ───────────────────────────────────────

    @Test
    fun `hasDeferredItems returns true`() {
        assertTrue(policy.buildReport().hasDeferredItems())
    }

    @Test
    fun `hasFullSupport returns false`() {
        assertFalse(policy.buildReport().hasFullSupport())
    }

    @Test
    fun `wire map contains replay_ordering_guarantee_status = deferred`() {
        val wireMap = policy.buildReport().toWireMap()
        assertEquals(
            "deferred",
            wireMap["replay_ordering_guarantee_status"]
        )
    }

    @Test
    fun `DEFERRED_COUNT is 1`() {
        assertEquals(1, policy.DEFERRED_COUNT)
    }

    @Test
    fun `entriesForStatus DEFERRED has exactly DEFERRED_COUNT entries`() {
        val deferred = policy.entriesForStatus(OfflineQueueReplayPolicy.ReplayPolicyStatus.DEFERRED)
        assertEquals(policy.DEFERRED_COUNT, deferred.size)
    }

    @Test
    fun `entriesForStatus DEFERRED contains REPLAY_ORDERING_GUARANTEE`() {
        val deferred = policy.entriesForStatus(OfflineQueueReplayPolicy.ReplayPolicyStatus.DEFERRED)
        assertTrue(
            deferred.any {
                it.dimension == OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_ORDERING_GUARANTEE
            }
        )
    }

    // ── Distinction between PARTIALLY_SUPPORTED and NON_AUTHORITATIVE (AC2) ──

    @Test
    fun `PARTIALLY_SUPPORTED is not the same enum value as NON_AUTHORITATIVE`() {
        assertNotEquals(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.PARTIALLY_SUPPORTED,
            OfflineQueueReplayPolicy.ReplayPolicyStatus.NON_AUTHORITATIVE
        )
    }

    @Test
    fun `PARTIALLY_SUPPORTED and NON_AUTHORITATIVE wire values are distinct`() {
        assertNotEquals(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.PARTIALLY_SUPPORTED.wireValue,
            OfflineQueueReplayPolicy.ReplayPolicyStatus.NON_AUTHORITATIVE.wireValue
        )
    }

    @Test
    fun `DUPLICATE_AVOIDANCE entry status is PARTIALLY_SUPPORTED`() {
        assertEquals(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.PARTIALLY_SUPPORTED,
            policy.entryFor(
                OfflineQueueReplayPolicy.ReplaySemanticDimension.DUPLICATE_AVOIDANCE
            )!!.status
        )
    }

    @Test
    fun `REPLAY_AUTHORITY entry status is NON_AUTHORITATIVE`() {
        assertEquals(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.NON_AUTHORITATIVE,
            policy.entryFor(
                OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_AUTHORITY
            )!!.status
        )
    }

    @Test
    fun `wire map duplicate_avoidance_status is partially_supported`() {
        val wireMap = policy.buildReport().toWireMap()
        assertEquals("partially_supported", wireMap["duplicate_avoidance_status"])
    }

    @Test
    fun `wire map replay_authority_status is non_authoritative`() {
        val wireMap = policy.buildReport().toWireMap()
        assertEquals("non_authoritative", wireMap["replay_authority_status"])
    }

    // ── No optimistic upgrade to SUPPORTED (AC3) ──────────────────────────────

    @Test
    fun `hasFullSupport returns false because non-SUPPORTED entries are present`() {
        assertFalse(
            "hasFullSupport must return false when DEFERRED, NON_AUTHORITATIVE, " +
                "PARTIALLY_SUPPORTED, or ACCEPTED_LIMITATION entries exist",
            policy.buildReport().hasFullSupport()
        )
    }

    @Test
    fun `a report with only SUPPORTED entries does have full support (control case)`() {
        val allSupported = OfflineQueueReplayPolicy.ReplaySemanticDimension.values().map { dim ->
            OfflineQueueReplayPolicy.ReplayPolicyEntry(
                dimension = dim,
                status = OfflineQueueReplayPolicy.ReplayPolicyStatus.SUPPORTED,
                rationale = "control",
                evidenceReference = "control",
                limitations = "none",
                v2ConsumptionPath = "control"
            )
        }
        val controlReport = OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport(
            entries = allSupported
        )
        assertTrue(
            "A report with all SUPPORTED entries must return hasFullSupport = true",
            controlReport.hasFullSupport()
        )
    }

    @Test
    fun `hasFullSupport returns false if any entry is DEFERRED`() {
        val entries = listOf(
            OfflineQueueReplayPolicy.ReplayPolicyEntry(
                dimension = OfflineQueueReplayPolicy.ReplaySemanticDimension.TASK_REPLAY_EXISTENCE,
                status = OfflineQueueReplayPolicy.ReplayPolicyStatus.DEFERRED,
                rationale = "test",
                evidenceReference = "test",
                limitations = "test",
                v2ConsumptionPath = "test"
            )
        )
        val report = OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport(entries = entries)
        assertFalse(report.hasFullSupport())
    }

    @Test
    fun `hasFullSupport returns false if any entry is NON_AUTHORITATIVE`() {
        val entries = listOf(
            OfflineQueueReplayPolicy.ReplayPolicyEntry(
                dimension = OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_AUTHORITY,
                status = OfflineQueueReplayPolicy.ReplayPolicyStatus.NON_AUTHORITATIVE,
                rationale = "test",
                evidenceReference = "test",
                limitations = "test",
                v2ConsumptionPath = "test"
            )
        )
        val report = OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport(entries = entries)
        assertFalse(report.hasFullSupport())
    }

    @Test
    fun `hasFullSupport returns false if any entry is ACCEPTED_LIMITATION`() {
        val entries = listOf(
            OfflineQueueReplayPolicy.ReplayPolicyEntry(
                dimension = OfflineQueueReplayPolicy.ReplaySemanticDimension.EVENTUAL_RECOVERY,
                status = OfflineQueueReplayPolicy.ReplayPolicyStatus.ACCEPTED_LIMITATION,
                rationale = "test",
                evidenceReference = "test",
                limitations = "test",
                v2ConsumptionPath = "test"
            )
        )
        val report = OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport(entries = entries)
        assertFalse(report.hasFullSupport())
    }

    @Test
    fun `hasFullSupport returns false if any entry is PARTIALLY_SUPPORTED`() {
        val entries = listOf(
            OfflineQueueReplayPolicy.ReplayPolicyEntry(
                dimension = OfflineQueueReplayPolicy.ReplaySemanticDimension.DUPLICATE_AVOIDANCE,
                status = OfflineQueueReplayPolicy.ReplayPolicyStatus.PARTIALLY_SUPPORTED,
                rationale = "test",
                evidenceReference = "test",
                limitations = "test",
                v2ConsumptionPath = "test"
            )
        )
        val report = OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport(entries = entries)
        assertFalse(report.hasFullSupport())
    }

    // ── Report / artifact output (AC4) ────────────────────────────────────────

    @Test
    fun `buildReport returns non-null report`() {
        assertNotNull(policy.buildReport())
    }

    @Test
    fun `report has ENTRY_COUNT entries`() {
        assertEquals(policy.ENTRY_COUNT, policy.buildReport().entries.size)
    }

    @Test
    fun `toWireMap contains KEY_SCHEMA_VERSION`() {
        val wireMap = policy.buildReport().toWireMap()
        assertTrue(
            wireMap.containsKey(
                OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.KEY_SCHEMA_VERSION
            )
        )
    }

    @Test
    fun `toWireMap contains KEY_INTRODUCED_PR`() {
        val wireMap = policy.buildReport().toWireMap()
        assertTrue(
            wireMap.containsKey(
                OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.KEY_INTRODUCED_PR
            )
        )
    }

    @Test
    fun `toWireMap contains KEY_GENERATED_AT_MS`() {
        val wireMap = policy.buildReport().toWireMap()
        assertTrue(
            wireMap.containsKey(
                OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.KEY_GENERATED_AT_MS
            )
        )
    }

    @Test
    fun `toWireMap contains KEY_ENTRY_COUNT`() {
        val wireMap = policy.buildReport().toWireMap()
        assertTrue(
            wireMap.containsKey(
                OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.KEY_ENTRY_COUNT
            )
        )
    }

    @Test
    fun `toWireMap contains KEY_HAS_FULL_SUPPORT`() {
        val wireMap = policy.buildReport().toWireMap()
        assertTrue(
            wireMap.containsKey(
                OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.KEY_HAS_FULL_SUPPORT
            )
        )
    }

    @Test
    fun `toWireMap contains KEY_HAS_DEFERRED_ITEMS`() {
        val wireMap = policy.buildReport().toWireMap()
        assertTrue(
            wireMap.containsKey(
                OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.KEY_HAS_DEFERRED_ITEMS
            )
        )
    }

    @Test
    fun `toWireMap contains KEY_HAS_NON_AUTHORITATIVE_ITEMS`() {
        val wireMap = policy.buildReport().toWireMap()
        assertTrue(
            wireMap.containsKey(
                OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.KEY_HAS_NON_AUTHORITATIVE_ITEMS
            )
        )
    }

    @Test
    fun `toWireMap has_full_support is false`() {
        val wireMap = policy.buildReport().toWireMap()
        assertEquals(false, wireMap[OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.KEY_HAS_FULL_SUPPORT])
    }

    @Test
    fun `toWireMap has_deferred_items is true`() {
        val wireMap = policy.buildReport().toWireMap()
        assertEquals(true, wireMap[OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.KEY_HAS_DEFERRED_ITEMS])
    }

    @Test
    fun `toWireMap has_non_authoritative_items is true`() {
        val wireMap = policy.buildReport().toWireMap()
        assertEquals(
            true,
            wireMap[OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.KEY_HAS_NON_AUTHORITATIVE_ITEMS]
        )
    }

    @Test
    fun `toWireMap schema_version is 1_0`() {
        val wireMap = policy.buildReport().toWireMap()
        assertEquals("1.0", wireMap[OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.KEY_SCHEMA_VERSION])
    }

    @Test
    fun `toWireMap entry_count is 5`() {
        val wireMap = policy.buildReport().toWireMap()
        assertEquals(5, wireMap[OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.KEY_ENTRY_COUNT])
    }

    @Test
    fun `toWireMap introduced_pr is 71`() {
        val wireMap = policy.buildReport().toWireMap()
        assertEquals(71, wireMap[OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport.KEY_INTRODUCED_PR])
    }

    @Test
    fun `all five dimension status keys are present in wire map`() {
        val wireMap = policy.buildReport().toWireMap()
        OfflineQueueReplayPolicy.ReplaySemanticDimension.values().forEach { dim ->
            val key = "${dim.wireValue}_status"
            assertTrue("wire map missing key: $key", wireMap.containsKey(key))
        }
    }

    @Test
    fun `wire map task_replay_existence_status is supported`() {
        val wireMap = policy.buildReport().toWireMap()
        assertEquals("supported", wireMap["task_replay_existence_status"])
    }

    @Test
    fun `wire map eventual_recovery_status is accepted_limitation`() {
        val wireMap = policy.buildReport().toWireMap()
        assertEquals("accepted_limitation", wireMap["eventual_recovery_status"])
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    @Test
    fun `entryFor returns entry for every known dimension`() {
        OfflineQueueReplayPolicy.ReplaySemanticDimension.values().forEach { dim ->
            assertNotNull("entryFor returned null for $dim", policy.entryFor(dim))
        }
    }

    @Test
    fun `entriesForStatus SUPPORTED has SUPPORTED_COUNT entries`() {
        assertEquals(
            policy.SUPPORTED_COUNT,
            policy.entriesForStatus(OfflineQueueReplayPolicy.ReplayPolicyStatus.SUPPORTED).size
        )
    }

    @Test
    fun `entriesForStatus PARTIALLY_SUPPORTED has PARTIALLY_SUPPORTED_COUNT entries`() {
        assertEquals(
            policy.PARTIALLY_SUPPORTED_COUNT,
            policy.entriesForStatus(
                OfflineQueueReplayPolicy.ReplayPolicyStatus.PARTIALLY_SUPPORTED
            ).size
        )
    }

    @Test
    fun `entriesForStatus NON_AUTHORITATIVE has NON_AUTHORITATIVE_COUNT entries`() {
        assertEquals(
            policy.NON_AUTHORITATIVE_COUNT,
            policy.entriesForStatus(
                OfflineQueueReplayPolicy.ReplayPolicyStatus.NON_AUTHORITATIVE
            ).size
        )
    }

    @Test
    fun `entriesForStatus ACCEPTED_LIMITATION has ACCEPTED_LIMITATION_COUNT entries`() {
        assertEquals(
            policy.ACCEPTED_LIMITATION_COUNT,
            policy.entriesForStatus(
                OfflineQueueReplayPolicy.ReplayPolicyStatus.ACCEPTED_LIMITATION
            ).size
        )
    }

    @Test
    fun `statusFor returns correct status for REPLAY_ORDERING_GUARANTEE`() {
        val report = policy.buildReport()
        assertEquals(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.DEFERRED,
            report.statusFor(
                OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_ORDERING_GUARANTEE
            )
        )
    }

    @Test
    fun `statusFor returns correct status for REPLAY_AUTHORITY`() {
        val report = policy.buildReport()
        assertEquals(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.NON_AUTHORITATIVE,
            report.statusFor(OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_AUTHORITY)
        )
    }

    @Test
    fun `statusFor returns correct status for DUPLICATE_AVOIDANCE`() {
        val report = policy.buildReport()
        assertEquals(
            OfflineQueueReplayPolicy.ReplayPolicyStatus.PARTIALLY_SUPPORTED,
            report.statusFor(
                OfflineQueueReplayPolicy.ReplaySemanticDimension.DUPLICATE_AVOIDANCE
            )
        )
    }

    @Test
    fun `statusFor returns null for a dimension not present in a partial report`() {
        val partialReport = OfflineQueueReplayPolicy.OfflineQueueReplayPolicyReport(
            entries = listOf(
                OfflineQueueReplayPolicy.ReplayPolicyEntry(
                    dimension = OfflineQueueReplayPolicy.ReplaySemanticDimension.TASK_REPLAY_EXISTENCE,
                    status = OfflineQueueReplayPolicy.ReplayPolicyStatus.SUPPORTED,
                    rationale = "test",
                    evidenceReference = "test",
                    limitations = "none",
                    v2ConsumptionPath = "test"
                )
            )
        )
        assertNull(
            "statusFor should return null for a dimension not in the entries list",
            partialReport.statusFor(
                OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_ORDERING_GUARANTEE
            )
        )
    }

    // ── AndroidReadinessEvidenceSurface integration ───────────────────────────

    @Test
    fun `offline_queue_replay_ordering_policy evidence entry is present and ADVISORY`() {
        val entry = AndroidReadinessEvidenceSurface.evidenceFor("offline_queue_replay_ordering_policy")
        assertNotNull(
            "offline_queue_replay_ordering_policy evidence entry must be present",
            entry
        )
        assertEquals(
            AndroidReadinessEvidenceSurface.ConfidenceLevel.ADVISORY,
            entry!!.confidenceLevel
        )
    }

    @Test
    fun `offline_queue_replay_ordering_authority_semantics deferred item is present`() {
        val item = AndroidReadinessEvidenceSurface.deferredItemFor(
            "offline_queue_replay_ordering_authority_semantics"
        )
        assertNotNull(
            "offline_queue_replay_ordering_authority_semantics deferred item must be present",
            item
        )
    }

    @Test
    fun `offline_queue_replay_ordering_authority_semantics deferred item has SIGNAL_REPLAY_DUPLICATE_SAFETY dimension`() {
        val item = AndroidReadinessEvidenceSurface.deferredItemFor(
            "offline_queue_replay_ordering_authority_semantics"
        )
        assertNotNull(item)
        assertEquals(
            AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            item!!.dimension
        )
    }

    @Test
    fun `offline_queue_replay_ordering_authority_semantics deferred description references DEFERRED`() {
        val item = AndroidReadinessEvidenceSurface.deferredItemFor(
            "offline_queue_replay_ordering_authority_semantics"
        )
        assertNotNull(item)
        assertTrue(
            "deferred item description must mention DEFERRED",
            item!!.description.contains("DEFERRED", ignoreCase = true)
        )
    }

    @Test
    fun `offline_queue_replay_ordering_authority_semantics deferred description references NON_AUTHORITATIVE`() {
        val item = AndroidReadinessEvidenceSurface.deferredItemFor(
            "offline_queue_replay_ordering_authority_semantics"
        )
        assertNotNull(item)
        assertTrue(
            "deferred item description must mention NON_AUTHORITATIVE",
            item!!.description.contains("NON_AUTHORITATIVE", ignoreCase = true)
        )
    }
}
