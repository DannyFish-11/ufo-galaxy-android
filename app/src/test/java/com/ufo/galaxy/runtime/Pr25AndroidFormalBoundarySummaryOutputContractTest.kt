package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-25Android — Tests for [AndroidFormalBoundarySummaryOutputContract].
 *
 * Locks all formal boundary summary output invariants:
 *   - six boundary summary classes present and non-overlapping
 *   - no boundary claims canonical truth convergence / final closure / platform sovereignty
 *   - six system role declarations all active
 *   - six is/is-not boundaries with non-blank "is not" statements
 *   - four restore/replay sub-boundary summaries present
 *   - nine anti-drift invariants present and non-blank
 *   - V2 alignment map covers all six boundary classes
 *   - all four validation helpers return true
 *   - top-level summary constants non-blank and semantically correct
 *   - StabilizationBaseline registration at PR 115
 */
class Pr25AndroidFormalBoundarySummaryOutputContractTest {

    // ── 1. Schema constants ───────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 115`() {
        assertEquals(115, AndroidFormalBoundarySummaryOutputContract.INTRODUCED_PR)
    }

    @Test
    fun `SCHEMA_VERSION is non-blank`() {
        assertTrue(AndroidFormalBoundarySummaryOutputContract.SCHEMA_VERSION.isNotBlank())
    }

    @Test
    fun `DESCRIPTION is non-blank and references formal boundary summary`() {
        val desc = AndroidFormalBoundarySummaryOutputContract.DESCRIPTION
        assertTrue(desc.isNotBlank())
        assertTrue(desc.contains("formal boundary summary", ignoreCase = true))
    }

    // ── 2. System role declarations ───────────────────────────────────────────

    @Test
    fun `exactly six system role declarations are present`() {
        assertEquals(6, AndroidFormalBoundarySummaryOutputContract.SYSTEM_ROLE_DECLARATIONS.size)
    }

    @Test
    fun `all system role declarations are active in current system`() {
        AndroidFormalBoundarySummaryOutputContract.SYSTEM_ROLE_DECLARATIONS.forEach { decl ->
            assertTrue(
                "SystemRoleDeclaration '${decl.roleClass}' not active",
                decl.isActiveInCurrentSystem
            )
        }
    }

    @Test
    fun `system role declarations include all six required roles`() {
        val roleWires = AndroidFormalBoundarySummaryOutputContract.SYSTEM_ROLE_DECLARATIONS
            .map { it.wireValue }
        assertTrue(roleWires.contains("bounded_relative_subject_runtime"))
        assertTrue(roleWires.contains("local_runtime_host"))
        assertTrue(roleWires.contains("local_continuity_holder"))
        assertTrue(roleWires.contains("local_execution_policy_participant"))
        assertTrue(roleWires.contains("local_ai_consumer_host"))
        assertTrue(roleWires.contains("distributed_participant"))
    }

    @Test
    fun `all system role declarations have non-blank canonicalAnchor`() {
        AndroidFormalBoundarySummaryOutputContract.SYSTEM_ROLE_DECLARATIONS.forEach { decl ->
            assertTrue(
                "canonicalAnchor blank for role '${decl.roleClass}'",
                decl.canonicalAnchor.isNotBlank()
            )
        }
    }

    @Test
    fun `validateAllSystemRolesActive returns true`() {
        assertTrue(AndroidFormalBoundarySummaryOutputContract.validateAllSystemRolesActive())
    }

    // ── 3. Is-vs-is-not boundaries ────────────────────────────────────────────

    @Test
    fun `exactly six is-vs-is-not boundaries are present`() {
        assertEquals(6, AndroidFormalBoundarySummaryOutputContract.IS_VS_IS_NOT_BOUNDARIES.size)
    }

    @Test
    fun `all is-not statements are non-blank`() {
        AndroidFormalBoundarySummaryOutputContract.IS_VS_IS_NOT_BOUNDARIES.forEach { b ->
            assertTrue(
                "isNotStatement blank for boundary '${b.boundaryId}'",
                b.isNotStatement.isNotBlank()
            )
        }
    }

    @Test
    fun `all is statements are non-blank`() {
        AndroidFormalBoundarySummaryOutputContract.IS_VS_IS_NOT_BOUNDARIES.forEach { b ->
            assertTrue(
                "isStatement blank for boundary '${b.boundaryId}'",
                b.isStatement.isNotBlank()
            )
        }
    }

    @Test
    fun `validateAllIsNotStatementsNonBlank returns true`() {
        assertTrue(AndroidFormalBoundarySummaryOutputContract.validateAllIsNotStatementsNonBlank())
    }

    @Test
    fun `is-not boundary for android-role-bounded-subject-runtime references parallel canonical center`() {
        val boundary = AndroidFormalBoundarySummaryOutputContract.IS_VS_IS_NOT_BOUNDARIES
            .firstOrNull { it.boundaryId == "android-role-bounded-subject-runtime" }
        assertNotNull(boundary)
        assertTrue(
            boundary!!.isNotStatement.contains("parallel canonical center", ignoreCase = true)
        )
    }

    @Test
    fun `is-not boundary for android-truth-contributor references canonical truth finalizer`() {
        val boundary = AndroidFormalBoundarySummaryOutputContract.IS_VS_IS_NOT_BOUNDARIES
            .firstOrNull { it.boundaryId == "android-truth-contributor" }
        assertNotNull(boundary)
        assertTrue(
            boundary!!.isNotStatement.contains("canonical truth finalizer", ignoreCase = true)
        )
    }

    @Test
    fun `is-not boundary for android-role-local-runtime-host references fully sovereign`() {
        val boundary = AndroidFormalBoundarySummaryOutputContract.IS_VS_IS_NOT_BOUNDARIES
            .firstOrNull { it.boundaryId == "android-role-local-runtime-host" }
        assertNotNull(boundary)
        assertTrue(
            boundary!!.isNotStatement.contains("fully sovereign", ignoreCase = true)
        )
    }

    // ── 4. Six boundary summary output entries ───────────────────────────────

    @Test
    fun `exactly six boundary summary output entries are present`() {
        assertEquals(
            6,
            AndroidFormalBoundarySummaryOutputContract.BOUNDARY_SUMMARY_OUTPUT_ENTRIES.size
        )
    }

    @Test
    fun `all six boundary summary classes are covered`() {
        val covered = AndroidFormalBoundarySummaryOutputContract.BOUNDARY_SUMMARY_OUTPUT_ENTRIES
            .map { it.boundaryClass }
            .toSet()
        AndroidFormalBoundarySummaryOutputContract.BoundarySummaryClass.values().forEach { cls ->
            assertTrue("BoundarySummaryClass $cls not covered", covered.contains(cls))
        }
    }

    @Test
    fun `each boundary summary class appears exactly once`() {
        val classes = AndroidFormalBoundarySummaryOutputContract.BOUNDARY_SUMMARY_OUTPUT_ENTRIES
            .map { it.boundaryClass }
        assertEquals(6, classes.distinct().size)
    }

    @Test
    fun `no boundary summary entry allows canonical truth convergence`() {
        assertTrue(
            AndroidFormalBoundarySummaryOutputContract.BOUNDARY_SUMMARY_OUTPUT_ENTRIES.none {
                it.allowsCanonicalTruthConvergence
            }
        )
    }

    @Test
    fun `no boundary summary entry allows final closure adjudication`() {
        assertTrue(
            AndroidFormalBoundarySummaryOutputContract.BOUNDARY_SUMMARY_OUTPUT_ENTRIES.none {
                it.allowsFinalClosureAdjudication
            }
        )
    }

    @Test
    fun `no boundary summary entry allows platform sovereignty`() {
        assertTrue(
            AndroidFormalBoundarySummaryOutputContract.BOUNDARY_SUMMARY_OUTPUT_ENTRIES.none {
                it.allowsPlatformSovereignty
            }
        )
    }

    @Test
    fun `all boundary summary entries have non-blank summary`() {
        AndroidFormalBoundarySummaryOutputContract.BOUNDARY_SUMMARY_OUTPUT_ENTRIES.forEach { entry ->
            assertTrue(
                "summary blank for boundary class ${entry.boundaryClass}",
                entry.summary.isNotBlank()
            )
        }
    }

    @Test
    fun `all boundary summary entries have non-empty moduleAnchors`() {
        AndroidFormalBoundarySummaryOutputContract.BOUNDARY_SUMMARY_OUTPUT_ENTRIES.forEach { entry ->
            assertTrue(
                "moduleAnchors empty for boundary class ${entry.boundaryClass}",
                entry.moduleAnchors.isNotEmpty()
            )
        }
    }

    @Test
    fun `all boundary summary entries have non-blank v2AlignmentPath`() {
        AndroidFormalBoundarySummaryOutputContract.BOUNDARY_SUMMARY_OUTPUT_ENTRIES.forEach { entry ->
            assertTrue(
                "v2AlignmentPath blank for boundary class ${entry.boundaryClass}",
                entry.v2AlignmentPath.isNotBlank()
            )
        }
    }

    @Test
    fun `validateCompleteFormalBoundarySummary returns true`() {
        assertTrue(AndroidFormalBoundarySummaryOutputContract.validateCompleteFormalBoundarySummary())
    }

    // ── 5. Specific boundary entries ──────────────────────────────────────────

    @Test
    fun `ANDROID_BOUNDED_SUBJECT_RUNTIME entry anchors AndroidBoundedSubjectRuntimeContract`() {
        val entry = AndroidFormalBoundarySummaryOutputContract.forBoundaryClass(
            AndroidFormalBoundarySummaryOutputContract.BoundarySummaryClass.ANDROID_BOUNDED_SUBJECT_RUNTIME
        )
        assertNotNull(entry)
        assertTrue(entry!!.moduleAnchors.contains("AndroidBoundedSubjectRuntimeContract"))
    }

    @Test
    fun `CONTINUITY_RESTORE_REPLAY entry anchors AndroidContinuityIntegration`() {
        val entry = AndroidFormalBoundarySummaryOutputContract.forBoundaryClass(
            AndroidFormalBoundarySummaryOutputContract.BoundarySummaryClass.CONTINUITY_RESTORE_REPLAY
        )
        assertNotNull(entry)
        assertTrue(entry!!.moduleAnchors.contains("AndroidContinuityIntegration"))
    }

    @Test
    fun `UPLINK_INGRESS_CONTRACT entry anchors AndroidGovernanceExecutionPolicyIngressContract`() {
        val entry = AndroidFormalBoundarySummaryOutputContract.forBoundaryClass(
            AndroidFormalBoundarySummaryOutputContract.BoundarySummaryClass.UPLINK_INGRESS_CONTRACT
        )
        assertNotNull(entry)
        assertTrue(
            entry!!.moduleAnchors.contains("AndroidGovernanceExecutionPolicyIngressContract")
        )
    }

    @Test
    fun `OUTWARD_UI_CONSUMPTION entry anchors AndroidFinalSurfaceConvergenceContract`() {
        val entry = AndroidFormalBoundarySummaryOutputContract.forBoundaryClass(
            AndroidFormalBoundarySummaryOutputContract.BoundarySummaryClass.OUTWARD_UI_CONSUMPTION
        )
        assertNotNull(entry)
        assertTrue(entry!!.moduleAnchors.contains("AndroidFinalSurfaceConvergenceContract"))
    }

    @Test
    fun `NON_SOVEREIGN_ROLE entry anchors QUASI_PLATFORM_STATE_DEFINITION`() {
        val entry = AndroidFormalBoundarySummaryOutputContract.forBoundaryClass(
            AndroidFormalBoundarySummaryOutputContract.BoundarySummaryClass.NON_SOVEREIGN_ROLE
        )
        assertNotNull(entry)
        assertTrue(
            entry!!.moduleAnchors.any { anchor ->
                anchor.contains("QUASI_PLATFORM_STATE_DEFINITION", ignoreCase = true)
            }
        )
    }

    @Test
    fun `forBoundaryClass returns null for missing class (contracts should be complete)`() {
        // All six classes should be covered — verify forBoundaryClass returns non-null for each
        AndroidFormalBoundarySummaryOutputContract.BoundarySummaryClass.values().forEach { cls ->
            assertNotNull(
                "forBoundaryClass returned null for $cls — coverage gap detected",
                AndroidFormalBoundarySummaryOutputContract.forBoundaryClass(cls)
            )
        }
    }

    // ── 6. Restore / replay boundary summaries ────────────────────────────────

    @Test
    fun `exactly four restore-replay boundary summaries are present`() {
        assertEquals(
            4,
            AndroidFormalBoundarySummaryOutputContract.RESTORE_REPLAY_BOUNDARY_SUMMARIES.size
        )
    }

    @Test
    fun `restore-replay summaries include androidOwns=true entries`() {
        assertTrue(
            AndroidFormalBoundarySummaryOutputContract.RESTORE_REPLAY_BOUNDARY_SUMMARIES.any {
                it.androidOwns
            }
        )
    }

    @Test
    fun `restore-replay summaries include v2CanonicalAuthority=true entry`() {
        assertTrue(
            AndroidFormalBoundarySummaryOutputContract.RESTORE_REPLAY_BOUNDARY_SUMMARIES.any {
                it.v2CanonicalAuthority
            }
        )
    }

    @Test
    fun `offline-queue-replay sub-boundary is Android-owned and not V2-canonical-authority`() {
        val sub = AndroidFormalBoundarySummaryOutputContract.RESTORE_REPLAY_BOUNDARY_SUMMARIES
            .firstOrNull { it.subBoundaryId == "offline-queue-replay" }
        assertNotNull(sub)
        assertTrue(sub!!.androidOwns)
        assertFalse(sub.v2CanonicalAuthority)
    }

    @Test
    fun `continuity-state-uplink sub-boundary is V2-canonical-authority`() {
        val sub = AndroidFormalBoundarySummaryOutputContract.RESTORE_REPLAY_BOUNDARY_SUMMARIES
            .firstOrNull { it.subBoundaryId == "continuity-state-uplink" }
        assertNotNull(sub)
        assertTrue(sub!!.v2CanonicalAuthority)
    }

    @Test
    fun `all restore-replay summaries have non-blank description`() {
        AndroidFormalBoundarySummaryOutputContract.RESTORE_REPLAY_BOUNDARY_SUMMARIES.forEach { sub ->
            assertTrue(
                "description blank for sub-boundary '${sub.subBoundaryId}'",
                sub.description.isNotBlank()
            )
        }
    }

    @Test
    fun `all restore-replay summaries have non-empty moduleAnchors`() {
        AndroidFormalBoundarySummaryOutputContract.RESTORE_REPLAY_BOUNDARY_SUMMARIES.forEach { sub ->
            assertTrue(
                "moduleAnchors empty for sub-boundary '${sub.subBoundaryId}'",
                sub.moduleAnchors.isNotEmpty()
            )
        }
    }

    // ── 7. Anti-drift invariants ──────────────────────────────────────────────

    @Test
    fun `exactly nine anti-drift invariants are present`() {
        assertEquals(9, AndroidFormalBoundarySummaryOutputContract.ANTI_DRIFT_INVARIANTS.size)
    }

    @Test
    fun `all anti-drift invariants are non-blank`() {
        AndroidFormalBoundarySummaryOutputContract.ANTI_DRIFT_INVARIANTS.forEach { inv ->
            assertTrue("blank anti-drift invariant found", inv.isNotBlank())
        }
    }

    @Test
    fun `validateAntiDriftInvariantCoverage returns true`() {
        assertTrue(AndroidFormalBoundarySummaryOutputContract.validateAntiDriftInvariantCoverage())
    }

    @Test
    fun `anti-drift invariants include no-authority-claim guard`() {
        assertTrue(
            AndroidFormalBoundarySummaryOutputContract.ANTI_DRIFT_INVARIANTS.any {
                it.contains("allowsCanonicalTruthConvergence", ignoreCase = true) ||
                    it.contains("allowsFinalClosureAdjudication", ignoreCase = true)
            }
        )
    }

    @Test
    fun `anti-drift invariants include outward consumption anchor guard`() {
        assertTrue(
            AndroidFormalBoundarySummaryOutputContract.ANTI_DRIFT_INVARIANTS.any {
                it.contains("OUTWARD_UI_CONSUMPTION", ignoreCase = true) ||
                    it.contains("AndroidFinalSurfaceConvergenceContract", ignoreCase = true)
            }
        )
    }

    // ── 8. V2 boundary narrative alignment map ────────────────────────────────

    @Test
    fun `V2_BOUNDARY_NARRATIVE_ALIGNMENT_MAP has exactly six entries`() {
        assertEquals(
            6,
            AndroidFormalBoundarySummaryOutputContract.V2_BOUNDARY_NARRATIVE_ALIGNMENT_MAP.size
        )
    }

    @Test
    fun `all V2 alignment map values are non-blank`() {
        AndroidFormalBoundarySummaryOutputContract.V2_BOUNDARY_NARRATIVE_ALIGNMENT_MAP
            .values.forEach { v ->
                assertTrue("blank V2 alignment path found", v.isNotBlank())
            }
    }

    @Test
    fun `V2 alignment map covers all six boundary summary class wire values`() {
        assertTrue(AndroidFormalBoundarySummaryOutputContract.validateV2AlignmentMapCoverage())
    }

    @Test
    fun `V2 alignment map contains non_sovereign_role key aligned to canonical authority center`() {
        val path = AndroidFormalBoundarySummaryOutputContract
            .V2_BOUNDARY_NARRATIVE_ALIGNMENT_MAP["non_sovereign_role"]
        assertNotNull(path)
        assertTrue(path!!.contains("canonical_authority_center", ignoreCase = true))
    }

    @Test
    fun `V2 alignment map contains uplink_ingress_contract key aligned to closure chain`() {
        val path = AndroidFormalBoundarySummaryOutputContract
            .V2_BOUNDARY_NARRATIVE_ALIGNMENT_MAP["uplink_ingress_contract"]
        assertNotNull(path)
        assertTrue(
            path!!.contains("closure_chain", ignoreCase = true) ||
                path.contains("acceptance", ignoreCase = true)
        )
    }

    // ── 9. Top-level summary constants ───────────────────────────────────────

    @Test
    fun `ANDROID_FORMAL_ROLE_SUMMARY is non-blank and references bounded relative subject runtime`() {
        val summary = AndroidFormalBoundarySummaryOutputContract.ANDROID_FORMAL_ROLE_SUMMARY
        assertTrue(summary.isNotBlank())
        assertTrue(summary.contains("bounded relative subject runtime", ignoreCase = true))
    }

    @Test
    fun `ANDROID_FORMAL_ROLE_SUMMARY references non-sovereign`() {
        val summary = AndroidFormalBoundarySummaryOutputContract.ANDROID_FORMAL_ROLE_SUMMARY
        assertTrue(summary.contains("non-sovereign", ignoreCase = true))
    }

    @Test
    fun `ANDROID_NON_SOVEREIGN_STATEMENT is non-blank`() {
        assertTrue(
            AndroidFormalBoundarySummaryOutputContract.ANDROID_NON_SOVEREIGN_STATEMENT.isNotBlank()
        )
    }

    @Test
    fun `ANDROID_NON_SOVEREIGN_STATEMENT references parallel canonical center`() {
        val statement = AndroidFormalBoundarySummaryOutputContract.ANDROID_NON_SOVEREIGN_STATEMENT
        assertTrue(statement.contains("parallel canonical center", ignoreCase = true))
    }

    @Test
    fun `ANDROID_NON_SOVEREIGN_STATEMENT references fully sovereign distributed authority node`() {
        val statement = AndroidFormalBoundarySummaryOutputContract.ANDROID_NON_SOVEREIGN_STATEMENT
        assertTrue(statement.contains("fully sovereign distributed authority node", ignoreCase = true))
    }

    @Test
    fun `ANDROID_NON_SOVEREIGN_STATEMENT references V2 canonical center`() {
        val statement = AndroidFormalBoundarySummaryOutputContract.ANDROID_NON_SOVEREIGN_STATEMENT
        assertTrue(statement.contains("V2 canonical center", ignoreCase = true))
    }

    // ── 10. BoundarySummaryClass wire values are stable ───────────────────────

    @Test
    fun `ANDROID_BOUNDED_SUBJECT_RUNTIME wire value is stable`() {
        assertEquals(
            "android_bounded_subject_runtime_boundary",
            AndroidFormalBoundarySummaryOutputContract
                .BoundarySummaryClass.ANDROID_BOUNDED_SUBJECT_RUNTIME.wireValue
        )
    }

    @Test
    fun `LOCAL_VISIBLE_VS_CANONICAL_VISIBLE wire value is stable`() {
        assertEquals(
            "local_visible_vs_canonical_visible_boundary",
            AndroidFormalBoundarySummaryOutputContract
                .BoundarySummaryClass.LOCAL_VISIBLE_VS_CANONICAL_VISIBLE.wireValue
        )
    }

    @Test
    fun `CONTINUITY_RESTORE_REPLAY wire value is stable`() {
        assertEquals(
            "continuity_restore_replay_boundary",
            AndroidFormalBoundarySummaryOutputContract
                .BoundarySummaryClass.CONTINUITY_RESTORE_REPLAY.wireValue
        )
    }

    @Test
    fun `UPLINK_INGRESS_CONTRACT wire value is stable`() {
        assertEquals(
            "uplink_ingress_contract_boundary",
            AndroidFormalBoundarySummaryOutputContract
                .BoundarySummaryClass.UPLINK_INGRESS_CONTRACT.wireValue
        )
    }

    @Test
    fun `OUTWARD_UI_CONSUMPTION wire value is stable`() {
        assertEquals(
            "outward_ui_consumption_boundary",
            AndroidFormalBoundarySummaryOutputContract
                .BoundarySummaryClass.OUTWARD_UI_CONSUMPTION.wireValue
        )
    }

    @Test
    fun `NON_SOVEREIGN_ROLE wire value is stable`() {
        assertEquals(
            "non_sovereign_role_boundary",
            AndroidFormalBoundarySummaryOutputContract
                .BoundarySummaryClass.NON_SOVEREIGN_ROLE.wireValue
        )
    }

    // ── 11. StabilizationBaseline registration ────────────────────────────────

    @Test
    fun `android-formal-boundary-summary-output is registered in StabilizationBaseline`() {
        assertTrue(
            StabilizationBaseline.isRegistered("android-formal-boundary-summary-output")
        )
    }

    @Test
    fun `android-formal-boundary-summary-output is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("android-formal-boundary-summary-output")
        assertNotNull(entry)
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `android-formal-boundary-summary-output introduced in PR 115`() {
        val entry = StabilizationBaseline.forId("android-formal-boundary-summary-output")
        assertNotNull(entry)
        assertEquals(115, entry!!.introducedPr)
    }
}
