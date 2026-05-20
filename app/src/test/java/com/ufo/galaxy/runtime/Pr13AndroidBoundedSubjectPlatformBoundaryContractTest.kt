package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-13Android — Tests for [AndroidBoundedSubjectPlatformBoundaryContract].
 *
 * Locks Android bounded subject platform boundary invariants:
 *   - five non-overlapping boundary classes present
 *   - no boundary re-assembles canonical authority / truth / dispatch / closure
 *   - all three formal platform roles declared
 *   - three-way relationship map complete
 *   - quasi-platform state definition and android formal definition non-blank
 *   - V2 alignment map covers every boundary entry
 *   - eight platform boundary invariants present
 *   - StabilizationBaseline registers the surface
 */
class Pr13AndroidBoundedSubjectPlatformBoundaryContractTest {

    // ── 1. Formal roles ───────────────────────────────────────────────────────

    @Test
    fun `all three formal platform roles are present`() {
        val roles = AndroidBoundedSubjectPlatformBoundaryContract.formalRoles
        assertTrue(
            roles.contains(
                AndroidBoundedSubjectPlatformBoundaryContract.FormalPlatformRole.BOUNDED_RELATIVE_SUBJECT_RUNTIME
            )
        )
        assertTrue(
            roles.contains(
                AndroidBoundedSubjectPlatformBoundaryContract.FormalPlatformRole.LOCAL_AI_CONSUMER_HOST
            )
        )
        assertTrue(
            roles.contains(
                AndroidBoundedSubjectPlatformBoundaryContract.FormalPlatformRole.CANONICAL_ALIGNED_PARTICIPANT_RUNTIME
            )
        )
        assertEquals(3, roles.size)
    }

    @Test
    fun `validateAllFormalRolesPresent returns true`() {
        assertTrue(
            AndroidBoundedSubjectPlatformBoundaryContract.validateAllFormalRolesPresent()
        )
    }

    @Test
    fun `formal role wire values are stable`() {
        assertEquals(
            "bounded_relative_subject_runtime",
            AndroidBoundedSubjectPlatformBoundaryContract.FormalPlatformRole.BOUNDED_RELATIVE_SUBJECT_RUNTIME.wireValue
        )
        assertEquals(
            "local_ai_consumer_host",
            AndroidBoundedSubjectPlatformBoundaryContract.FormalPlatformRole.LOCAL_AI_CONSUMER_HOST.wireValue
        )
        assertEquals(
            "canonical_aligned_participant_runtime",
            AndroidBoundedSubjectPlatformBoundaryContract.FormalPlatformRole.CANONICAL_ALIGNED_PARTICIPANT_RUNTIME.wireValue
        )
    }

    // ── 2. Five boundary classes present and non-overlapping ──────────────────

    @Test
    fun `all five boundary classes are present in boundaryEntries`() {
        assertTrue(
            AndroidBoundedSubjectPlatformBoundaryContract.validateAllBoundaryClassesPresent()
        )
    }

    @Test
    fun `boundaryEntries has exactly five entries`() {
        assertEquals(5, AndroidBoundedSubjectPlatformBoundaryContract.boundaryEntries.size)
    }

    @Test
    fun `boundary class wire values are stable`() {
        assertEquals(
            "bounded_runtime_boundary",
            AndroidBoundedSubjectPlatformBoundaryContract.BoundaryClass.BOUNDED_RUNTIME_BOUNDARY.wireValue
        )
        assertEquals(
            "local_ai_consumer_boundary",
            AndroidBoundedSubjectPlatformBoundaryContract.BoundaryClass.LOCAL_AI_CONSUMER_BOUNDARY.wireValue
        )
        assertEquals(
            "participant_truth_uplink_boundary",
            AndroidBoundedSubjectPlatformBoundaryContract.BoundaryClass.PARTICIPANT_TRUTH_UPLINK_BOUNDARY.wireValue
        )
        assertEquals(
            "observability_diagnostics_evidence_boundary",
            AndroidBoundedSubjectPlatformBoundaryContract.BoundaryClass.OBSERVABILITY_DIAGNOSTICS_EVIDENCE_BOUNDARY.wireValue
        )
        assertEquals(
            "outward_consumption_boundary",
            AndroidBoundedSubjectPlatformBoundaryContract.BoundaryClass.OUTWARD_CONSUMPTION_BOUNDARY.wireValue
        )
    }

    @Test
    fun `each boundary class appears exactly once in boundaryEntries`() {
        val classes = AndroidBoundedSubjectPlatformBoundaryContract.boundaryEntries
            .map { it.boundaryClass }
        assertEquals(5, classes.distinct().size)
    }

    // ── 3. No authority re-assembly in any boundary ───────────────────────────

    @Test
    fun `validateNoBoundaryReassemblesAuthority returns true`() {
        assertTrue(
            AndroidBoundedSubjectPlatformBoundaryContract.validateNoBoundaryReassemblesAuthority()
        )
    }

    @Test
    fun `no boundary entry has mayReassembleCanonicalAuthority set`() {
        assertTrue(
            AndroidBoundedSubjectPlatformBoundaryContract.boundaryEntries.none {
                it.mayReassembleCanonicalAuthority
            }
        )
    }

    @Test
    fun `no boundary entry may finalize truth`() {
        assertTrue(
            AndroidBoundedSubjectPlatformBoundaryContract.boundaryEntries.none { it.mayFinalizeTruth }
        )
    }

    @Test
    fun `no boundary entry may arbitrate dispatch`() {
        assertTrue(
            AndroidBoundedSubjectPlatformBoundaryContract.boundaryEntries.none { it.mayArbitrateDispatch }
        )
    }

    @Test
    fun `no boundary entry may own closure`() {
        assertTrue(
            AndroidBoundedSubjectPlatformBoundaryContract.boundaryEntries.none { it.mayOwnClosure }
        )
    }

    // ── 4. Canonical anchor coverage ─────────────────────────────────────────

    @Test
    fun `BOUNDED_RUNTIME_BOUNDARY anchors AndroidBoundedSubjectRuntimeContract and RuntimeController`() {
        val entry = AndroidBoundedSubjectPlatformBoundaryContract.forId(
            "android-bounded-runtime-authority"
        )
        assertNotNull(entry)
        assertTrue(entry!!.canonicalAnchors.contains("AndroidBoundedSubjectRuntimeContract"))
        assertTrue(entry.canonicalAnchors.contains("RuntimeController"))
    }

    @Test
    fun `PARTICIPANT_TRUTH_UPLINK_BOUNDARY anchors all uplink contracts`() {
        val entry = AndroidBoundedSubjectPlatformBoundaryContract.forId(
            "android-participant-truth-uplink"
        )
        assertNotNull(entry)
        assertTrue(entry!!.canonicalAnchors.contains("AndroidResultUplinkBoundaryContract"))
        assertTrue(entry.canonicalAnchors.contains("AndroidDistributedTruthOwnershipUplinkContract"))
        assertTrue(entry.canonicalAnchors.contains("AndroidCompletionClosureUplinkContract"))
    }

    @Test
    fun `OUTWARD_CONSUMPTION_BOUNDARY anchors AndroidFinalSurfaceConvergenceContract`() {
        val entry = AndroidBoundedSubjectPlatformBoundaryContract.forId(
            "android-outward-consumption"
        )
        assertNotNull(entry)
        assertTrue(entry!!.canonicalAnchors.contains("AndroidFinalSurfaceConvergenceContract"))
    }

    @Test
    fun `OBSERVABILITY_DIAGNOSTICS_EVIDENCE_BOUNDARY anchors diagnostics and observability contracts`() {
        val entry = AndroidBoundedSubjectPlatformBoundaryContract.forId(
            "android-observability-diagnostics-evidence"
        )
        assertNotNull(entry)
        assertTrue(
            entry!!.canonicalAnchors.contains(
                "AndroidBoundedSubjectRuntimeContract.OBSERVABILITY_BOUNDARY_ENTRIES"
            )
        )
        assertTrue(entry.canonicalAnchors.contains("AndroidDiagnosticsFailureExplanationUplinkContract"))
    }

    @Test
    fun `all boundary entries have non-blank scopeDescription and v2AlignmentPath`() {
        AndroidBoundedSubjectPlatformBoundaryContract.boundaryEntries.forEach { entry ->
            assertTrue(
                "scopeDescription blank for ${entry.boundaryId}",
                entry.scopeDescription.isNotBlank()
            )
            assertTrue(
                "v2AlignmentPath blank for ${entry.boundaryId}",
                entry.v2AlignmentPath.isNotBlank()
            )
        }
    }

    // ── 5. Three-way relationship ─────────────────────────────────────────────

    @Test
    fun `three-way relationship map has three entries`() {
        assertEquals(3, AndroidBoundedSubjectPlatformBoundaryContract.threeWayRelationship.size)
    }

    @Test
    fun `three-way relationship covers android bounded runtime, v2 canonical center, outward consumers`() {
        val rel = AndroidBoundedSubjectPlatformBoundaryContract.threeWayRelationship
        assertTrue(rel.containsKey("android_bounded_runtime"))
        assertTrue(rel.containsKey("v2_canonical_center"))
        assertTrue(rel.containsKey("outward_consumers"))
    }

    @Test
    fun `v2 canonical center description references canonical authority`() {
        val v2Desc = AndroidBoundedSubjectPlatformBoundaryContract
            .threeWayRelationship["v2_canonical_center"]
        assertNotNull(v2Desc)
        assertTrue(v2Desc!!.contains("canonical_authority_center", ignoreCase = true))
    }

    @Test
    fun `outward consumers description references consumption only`() {
        val desc = AndroidBoundedSubjectPlatformBoundaryContract
            .threeWayRelationship["outward_consumers"]
        assertNotNull(desc)
        assertTrue(desc!!.contains("consumption_only", ignoreCase = true))
    }

    // ── 6. Quasi-platform state and formal definition ─────────────────────────

    @Test
    fun `QUASI_PLATFORM_STATE_DEFINITION is non-blank and contains dual-repo reference`() {
        val def = AndroidBoundedSubjectPlatformBoundaryContract.QUASI_PLATFORM_STATE_DEFINITION
        assertTrue(def.isNotBlank())
        assertTrue(def.contains("dual-repo", ignoreCase = true))
    }

    @Test
    fun `ANDROID_FORMAL_DEFINITION is non-blank and references bounded relative subject runtime`() {
        val def = AndroidBoundedSubjectPlatformBoundaryContract.ANDROID_FORMAL_DEFINITION
        assertTrue(def.isNotBlank())
        assertTrue(def.contains("bounded relative subject runtime", ignoreCase = true))
    }

    @Test
    fun `ANDROID_FORMAL_DEFINITION explicitly states Android is not a parallel canonical center`() {
        val def = AndroidBoundedSubjectPlatformBoundaryContract.ANDROID_FORMAL_DEFINITION
        assertTrue(def.contains("not a parallel canonical center", ignoreCase = true))
    }

    // ── 7. Platform boundary invariants ──────────────────────────────────────

    @Test
    fun `exactly eight platform boundary invariants are present`() {
        assertEquals(8, AndroidBoundedSubjectPlatformBoundaryContract.platformBoundaryInvariants.size)
    }

    @Test
    fun `platform boundary invariants are all non-blank`() {
        AndroidBoundedSubjectPlatformBoundaryContract.platformBoundaryInvariants.forEach { inv ->
            assertTrue("blank invariant found", inv.isNotBlank())
        }
    }

    @Test
    fun `invariants reference authority reassembly prohibition`() {
        val invs = AndroidBoundedSubjectPlatformBoundaryContract.platformBoundaryInvariants
        assertTrue(
            invs.any { it.contains("re-assemble canonical authority", ignoreCase = true) }
        )
    }

    @Test
    fun `invariants reference three-way relationship`() {
        val invs = AndroidBoundedSubjectPlatformBoundaryContract.platformBoundaryInvariants
        assertTrue(
            invs.any { it.contains("three-way relationship", ignoreCase = true) }
        )
    }

    // ── 8. V2 alignment map ───────────────────────────────────────────────────

    @Test
    fun `V2_PLATFORM_BOUNDARY_ALIGNMENT_MAP covers all five boundary entry IDs`() {
        val mapKeys = AndroidBoundedSubjectPlatformBoundaryContract.V2_PLATFORM_BOUNDARY_ALIGNMENT_MAP.keys
        AndroidBoundedSubjectPlatformBoundaryContract.boundaryEntries.forEach { entry ->
            assertTrue(
                "V2 alignment map missing entry for ${entry.boundaryId}",
                mapKeys.contains(entry.boundaryId)
            )
        }
    }

    @Test
    fun `all V2 alignment map values are non-blank`() {
        AndroidBoundedSubjectPlatformBoundaryContract.V2_PLATFORM_BOUNDARY_ALIGNMENT_MAP
            .values.forEach { v ->
                assertTrue("blank V2 alignment path found", v.isNotBlank())
            }
    }

    // ── 9. Query helpers ──────────────────────────────────────────────────────

    @Test
    fun `forId returns correct entries for all five boundary IDs`() {
        listOf(
            "android-bounded-runtime-authority",
            "android-local-ai-consumer",
            "android-participant-truth-uplink",
            "android-observability-diagnostics-evidence",
            "android-outward-consumption"
        ).forEach { id ->
            assertNotNull("forId returned null for $id",
                AndroidBoundedSubjectPlatformBoundaryContract.forId(id))
        }
    }

    @Test
    fun `forId returns null for unknown boundary ID`() {
        assertEquals(
            null,
            AndroidBoundedSubjectPlatformBoundaryContract.forId("nonexistent-boundary-id")
        )
    }

    @Test
    fun `forBoundaryClass returns exactly one entry per class`() {
        AndroidBoundedSubjectPlatformBoundaryContract.BoundaryClass.values().forEach { cls ->
            val entries = AndroidBoundedSubjectPlatformBoundaryContract.forBoundaryClass(cls)
            assertEquals("Expected 1 entry for $cls, got ${entries.size}", 1, entries.size)
        }
    }

    // ── 10. Introduced PR and description ─────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 103`() {
        assertEquals(103, AndroidBoundedSubjectPlatformBoundaryContract.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank and references bounded subject platform boundary`() {
        val desc = AndroidBoundedSubjectPlatformBoundaryContract.DESCRIPTION
        assertTrue(desc.isNotBlank())
        assertTrue(desc.contains("bounded subject platform boundary", ignoreCase = true))
    }

    // ── 11. StabilizationBaseline registration ────────────────────────────────

    @Test
    fun `android-bounded-subject-platform-boundary is registered in StabilizationBaseline`() {
        assertTrue(StabilizationBaseline.isRegistered("android-bounded-subject-platform-boundary"))
    }

    @Test
    fun `android-bounded-subject-platform-boundary is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("android-bounded-subject-platform-boundary")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `android-bounded-subject-platform-boundary introduced in PR 103`() {
        val entry = StabilizationBaseline.forId("android-bounded-subject-platform-boundary")
        assertNotNull(entry)
        assertEquals(103, entry!!.introducedPr)
    }
}
