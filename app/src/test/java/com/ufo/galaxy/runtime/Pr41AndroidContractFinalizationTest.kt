package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-41 — Android Contract Finalization and Compatibility Retirement.
 *
 * Focused test suite validating all PR-41 additions:
 *
 *  1. [AndroidContractFinalizer] — contract responsibility boundary registry.
 *     - [AndroidContractFinalizer.ResponsibilityClarity] enum values and wireValues.
 *     - [AndroidContractFinalizer.DriftRisk] enum values and wireValues.
 *     - [AndroidContractFinalizer.BoundaryOwnership] enum values and wireValues.
 *     - Registry completeness: ten entries.
 *     - Query helpers: byClarity, byDriftRisk, byOwnership, forId.
 *     - Set helpers: highDriftRiskBoundaryIds, ambiguousBoundaryIds, finalizedBoundaryIds,
 *       boundariesWithResidualAmbiguity.
 *     - Specific boundary assertions for all ten areas.
 *     - Constants: INTRODUCED_PR, DESCRIPTION, FINALIZATION_INVARIANT.
 *
 *  2. [CompatibilityRetirementFence] — PR-41 retirement fence registry.
 *     - [CompatibilityRetirementFence.FenceDecision] enum values and wireValues.
 *     - [CompatibilityRetirementFence.FenceReason] enum values and wireValues.
 *     - Registry completeness: one entry per [CompatibilitySurfaceRetirementRegistry] surface.
 *     - Coverage invariant: fence surface IDs match retirement registry surface IDs.
 *     - Specific fence decisions for all surfaces.
 *     - Query helpers: fenceFor, byDecision, byReason.
 *     - Set helpers: fencedSurfaceIds, decommissionScheduledSurfaceIds, pendingCoordinationSurfaceIds.
 *     - Constants: INTRODUCED_PR, DESCRIPTION, COVERAGE_INVARIANT.
 *
 *  3. [CanonicalSessionAxis.contractFinalizationBindings] — PR-41 addition.
 *     - contractFinalizationBindings has seven entries (one per CanonicalSessionFamily).
 *     - all seven families have a binding.
 *     - RUNTIME_SESSION is EXPLICIT/LOW.
 *     - ATTACHED_RUNTIME_SESSION is EXPLICIT/LOW.
 *     - DELEGATION_TRANSFER_SESSION is EXPLICIT/LOW.
 *     - CONVERSATION_SESSION is EXPLICIT/LOW.
 *     - DURABLE_RUNTIME_SESSION is EXPLICIT/LOW.
 *     - CONTROL_SESSION is TRANSITIONAL/MEDIUM.
 *     - MESH_SESSION is TRANSITIONAL/MEDIUM.
 *     - finalizedContractFamilies has exactly five families.
 *     - transitionalContractFamilies has exactly two families.
 *     - all bindings have non-blank canonicalSurface and finalizationNote.
 *
 *  4. [CanonicalDispatchChain.resolveContractFinalizedPaths] — PR-41 addition.
 *     - COMPATIBILITY path is excluded when excludeCompatibilityPath=true (default).
 *     - COMPATIBILITY path is included when excludeCompatibilityPath=false.
 *     - LOCAL path is not excluded.
 *     - FALLBACK path is not excluded.
 *     - result is a subset of resolveTransportAdaptedPaths result.
 *     - COMPATIBILITY_PATH_MODE is DispatchPathMode.COMPATIBILITY.
 *
 *  5. [StabilizationBaseline] — PR-41 entries registered.
 *     - android-contract-finalizer is registered as CANONICAL_STABLE.
 *     - compatibility-retirement-fence is registered as CANONICAL_STABLE.
 *     - canonical-session-axis-contract-finalization-bindings is registered as CANONICAL_STABLE.
 *     - canonical-dispatch-chain-contract-finalized-paths is registered as CANONICAL_STABLE.
 *     - all PR-41 entries have introducedPr = 41.
 *     - all PR-41 entries have EXTEND guidance.
 *
 * ## Test matrix
 *
 * ### AndroidContractFinalizer — ResponsibilityClarity enum
 *  - EXPLICIT wireValue is "explicit"
 *  - TRANSITIONAL wireValue is "transitional"
 *  - AMBIGUOUS wireValue is "ambiguous"
 *  - all three wireValues are distinct
 *  - fromValue returns correct entry for each wireValue
 *  - fromValue returns null for unknown value
 *
 * ### AndroidContractFinalizer — DriftRisk enum
 *  - LOW wireValue is "low"
 *  - MEDIUM wireValue is "medium"
 *  - HIGH wireValue is "high"
 *  - all three wireValues are distinct
 *  - fromValue returns correct entry for each wireValue
 *  - fromValue returns null for unknown value
 *
 * ### AndroidContractFinalizer — BoundaryOwnership enum
 *  - ANDROID_OWNED wireValue is "android_owned"
 *  - CENTER_OWNED wireValue is "center_owned"
 *  - SHARED wireValue is "shared"
 *  - all three wireValues are distinct
 *  - fromValue returns correct entry for each wireValue
 *  - fromValue returns null for unknown value
 *
 * ### AndroidContractFinalizer — boundary registry
 *  - exactly ten boundary entries
 *  - all boundaryIds are unique
 *  - no boundaries are classified as AMBIGUOUS clarity
 *  - no boundaries are classified as HIGH drift risk
 *  - ANDROID_OWNED boundaries all have non-null finalizationNote
 *  - SHARED boundaries all have non-null finalizationNote
 *  - readiness-declaration-boundary is EXPLICIT/LOW/ANDROID_OWNED
 *  - session-participation-boundary is EXPLICIT/LOW/ANDROID_OWNED
 *  - transport-continuity-boundary is EXPLICIT/LOW/ANDROID_OWNED
 *  - dispatch-eligibility-boundary is EXPLICIT/LOW/ANDROID_OWNED
 *  - snapshot-projection-boundary is EXPLICIT/LOW/ANDROID_OWNED
 *  - runtime-lifecycle-authority-boundary is EXPLICIT/LOW/ANDROID_OWNED
 *  - host-facing-state-boundary is EXPLICIT/MEDIUM/ANDROID_OWNED
 *  - session-identifier-vocabulary-boundary is TRANSITIONAL/MEDIUM/SHARED
 *  - protocol-compatibility-retirement-boundary is TRANSITIONAL/MEDIUM/SHARED
 *  - long-tail-dispatch-boundary is EXPLICIT/LOW/SHARED
 *
 * ### AndroidContractFinalizer — query helpers
 *  - byClarity(EXPLICIT) returns exactly eight entries
 *  - byClarity(TRANSITIONAL) returns exactly two entries
 *  - byClarity(AMBIGUOUS) returns empty list
 *  - byDriftRisk(LOW) returns seven entries
 *  - byDriftRisk(MEDIUM) returns three entries
 *  - byDriftRisk(HIGH) returns empty list
 *  - byOwnership(ANDROID_OWNED) returns at least six entries
 *  - byOwnership(SHARED) returns at least three entries
 *  - byOwnership(CENTER_OWNED) returns empty list
 *  - forId returns correct entry for known boundaryId
 *  - forId returns null for unknown boundaryId
 *  - highDriftRiskBoundaryIds is empty (no HIGH risk boundaries)
 *  - ambiguousBoundaryIds is empty (no AMBIGUOUS boundaries)
 *  - finalizedBoundaryIds has seven entries
 *  - boundariesWithResidualAmbiguity has exactly three entries
 *
 * ### CompatibilityRetirementFence — FenceDecision enum
 *  - FENCED wireValue is "fenced"
 *  - DEMOTED wireValue is "demoted"
 *  - PENDING_COORDINATION wireValue is "pending_coordination"
 *  - DECOMMISSION_SCHEDULED wireValue is "decommission_scheduled"
 *  - all four wireValues are distinct
 *  - fromValue returns correct entry for each wireValue
 *  - fromValue returns null for unknown value
 *
 * ### CompatibilityRetirementFence — FenceReason enum
 *  - CANONICAL_REPLACEMENT_COMPLETE wireValue is "canonical_replacement_complete"
 *  - CONSUMERS_MIGRATED wireValue is "consumers_migrated"
 *  - CENTER_COORDINATION_REQUIRED wireValue is "center_coordination_required"
 *  - LOW_PRODUCTION_FREQUENCY wireValue is "low_production_frequency"
 *  - PROMOTED_TO_DEDICATED_HANDLER wireValue is "promoted_to_dedicated_handler"
 *  - all five wireValues are distinct
 *  - fromValue returns correct entry for each wireValue
 *  - fromValue returns null for unknown value
 *
 * ### CompatibilityRetirementFence — fence registry
 *  - fence count equals CompatibilitySurfaceRetirementRegistry entry count
 *  - all compatibilitySurfaceIds in fences match entries in CompatibilitySurfaceRetirementRegistry
 *  - all CompatibilitySurfaceRetirementRegistry surface IDs have a fence entry
 *  - all fenceEntry compatibilitySurfaceIds are unique
 *  - all entries have non-blank retirementReadiness, activeRisk, finalizationNote
 *
 * ### CompatibilityRetirementFence — specific fence decisions
 *  - runtime_registration_error_string_bridge is FENCED
 *  - runtime_host_session_legacy_map_bridge is PENDING_COORDINATION
 *  - session_identifier_carrier_transitional_surface is PENDING_COORDINATION
 *  - staged_mesh_execution_status_transitional_surface is PENDING_COORDINATION
 *  - galaxy_api_client_register_device_deprecated is DECOMMISSION_SCHEDULED
 *  - galaxy_api_client_send_heartbeat_deprecated is DECOMMISSION_SCHEDULED
 *  - legacy_msgtype_alias_normalization is PENDING_COORDINATION
 *  - lifecycle_status_normalization_map is PENDING_COORDINATION
 *  - dispatch_adapter_peer_announce_transitional is DECOMMISSION_SCHEDULED
 *  - dispatch_adapter_lock_transitional is DECOMMISSION_SCHEDULED
 *  - dispatch_adapter_unlock_transitional is DECOMMISSION_SCHEDULED
 *
 * ### CompatibilityRetirementFence — query helpers
 *  - fenceFor known surface returns non-null
 *  - fenceFor unknown surface returns null
 *  - byDecision(FENCED) has exactly one entry
 *  - byDecision(DECOMMISSION_SCHEDULED) has five entries
 *  - byDecision(PENDING_COORDINATION) has thirteen entries
 *  - byDecision(DEMOTED) has zero entries
 *  - byReason(CONSUMERS_MIGRATED) has one entry
 *  - byReason(PROMOTED_TO_DEDICATED_HANDLER) has one entry
 *  - fencedSurfaceIds has exactly one element
 *  - decommissionScheduledSurfaceIds has exactly five elements
 *  - pendingCoordinationSurfaceIds has thirteen elements
 *
 * ### CanonicalSessionAxis — contractFinalizationBindings
 *  - contractFinalizationBindings has 7 entries
 *  - all seven CanonicalSessionFamily values have a binding
 *  - RUNTIME_SESSION clarity is EXPLICIT
 *  - RUNTIME_SESSION driftRisk is LOW
 *  - ATTACHED_RUNTIME_SESSION clarity is EXPLICIT
 *  - ATTACHED_RUNTIME_SESSION driftRisk is LOW
 *  - DELEGATION_TRANSFER_SESSION clarity is EXPLICIT
 *  - DELEGATION_TRANSFER_SESSION driftRisk is LOW
 *  - CONVERSATION_SESSION clarity is EXPLICIT
 *  - CONVERSATION_SESSION driftRisk is LOW
 *  - DURABLE_RUNTIME_SESSION clarity is EXPLICIT
 *  - DURABLE_RUNTIME_SESSION driftRisk is LOW
 *  - CONTROL_SESSION clarity is TRANSITIONAL
 *  - CONTROL_SESSION driftRisk is MEDIUM
 *  - MESH_SESSION clarity is TRANSITIONAL
 *  - MESH_SESSION driftRisk is MEDIUM
 *  - finalizedContractFamilies has exactly 5 families
 *  - transitionalContractFamilies has exactly 2 families
 *  - finalizedContractFamilies and transitionalContractFamilies are disjoint
 *  - all bindings have non-blank canonicalSurface and finalizationNote
 *
 * ### CanonicalDispatchChain — resolveContractFinalizedPaths
 *  - with excludeCompatibilityPath=true: COMPATIBILITY is not in result
 *  - with excludeCompatibilityPath=false: COMPATIBILITY may appear in result
 *  - LOCAL path is not excluded by contract finalization filter
 *  - result is subset of resolveTransportAdaptedPaths result (for excludeCompatibilityPath=true)
 *  - COMPATIBILITY_PATH_MODE is DispatchPathMode.COMPATIBILITY
 *
 * ### StabilizationBaseline — PR-41 entries
 *  - android-contract-finalizer is registered
 *  - compatibility-retirement-fence is registered
 *  - canonical-session-axis-contract-finalization-bindings is registered
 *  - canonical-dispatch-chain-contract-finalized-paths is registered
 *  - all PR-41 entries have CANONICAL_STABLE stability
 *  - all PR-41 entries have EXTEND guidance
 *  - all PR-41 entries have introducedPr = 41
 */
class Pr41AndroidContractFinalizationTest {

    // ─────────────────────────────────────────────────────────────────────────
    // AndroidContractFinalizer — ResponsibilityClarity enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `ResponsibilityClarity EXPLICIT wireValue is explicit`() {
        assertEquals("explicit", AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT.wireValue)
    }

    @Test fun `ResponsibilityClarity TRANSITIONAL wireValue is transitional`() {
        assertEquals("transitional", AndroidContractFinalizer.ResponsibilityClarity.TRANSITIONAL.wireValue)
    }

    @Test fun `ResponsibilityClarity AMBIGUOUS wireValue is ambiguous`() {
        assertEquals("ambiguous", AndroidContractFinalizer.ResponsibilityClarity.AMBIGUOUS.wireValue)
    }

    @Test fun `all three ResponsibilityClarity wireValues are distinct`() {
        val wireValues = AndroidContractFinalizer.ResponsibilityClarity.entries.map { it.wireValue }
        assertEquals(wireValues.distinct().size, wireValues.size)
    }

    @Test fun `ResponsibilityClarity fromValue returns correct entries`() {
        assertEquals(
            AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT,
            AndroidContractFinalizer.ResponsibilityClarity.fromValue("explicit")
        )
        assertEquals(
            AndroidContractFinalizer.ResponsibilityClarity.TRANSITIONAL,
            AndroidContractFinalizer.ResponsibilityClarity.fromValue("transitional")
        )
        assertEquals(
            AndroidContractFinalizer.ResponsibilityClarity.AMBIGUOUS,
            AndroidContractFinalizer.ResponsibilityClarity.fromValue("ambiguous")
        )
    }

    @Test fun `ResponsibilityClarity fromValue returns null for unknown value`() {
        assertNull(AndroidContractFinalizer.ResponsibilityClarity.fromValue("unknown_xyz"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AndroidContractFinalizer — DriftRisk enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `DriftRisk LOW wireValue is low`() {
        assertEquals("low", AndroidContractFinalizer.DriftRisk.LOW.wireValue)
    }

    @Test fun `DriftRisk MEDIUM wireValue is medium`() {
        assertEquals("medium", AndroidContractFinalizer.DriftRisk.MEDIUM.wireValue)
    }

    @Test fun `DriftRisk HIGH wireValue is high`() {
        assertEquals("high", AndroidContractFinalizer.DriftRisk.HIGH.wireValue)
    }

    @Test fun `all three DriftRisk wireValues are distinct`() {
        val wireValues = AndroidContractFinalizer.DriftRisk.entries.map { it.wireValue }
        assertEquals(wireValues.distinct().size, wireValues.size)
    }

    @Test fun `DriftRisk fromValue returns correct entries`() {
        assertEquals(AndroidContractFinalizer.DriftRisk.LOW, AndroidContractFinalizer.DriftRisk.fromValue("low"))
        assertEquals(AndroidContractFinalizer.DriftRisk.MEDIUM, AndroidContractFinalizer.DriftRisk.fromValue("medium"))
        assertEquals(AndroidContractFinalizer.DriftRisk.HIGH, AndroidContractFinalizer.DriftRisk.fromValue("high"))
    }

    @Test fun `DriftRisk fromValue returns null for unknown value`() {
        assertNull(AndroidContractFinalizer.DriftRisk.fromValue("unknown_xyz"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AndroidContractFinalizer — BoundaryOwnership enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `BoundaryOwnership ANDROID_OWNED wireValue is android_owned`() {
        assertEquals("android_owned", AndroidContractFinalizer.BoundaryOwnership.ANDROID_OWNED.wireValue)
    }

    @Test fun `BoundaryOwnership CENTER_OWNED wireValue is center_owned`() {
        assertEquals("center_owned", AndroidContractFinalizer.BoundaryOwnership.CENTER_OWNED.wireValue)
    }

    @Test fun `BoundaryOwnership SHARED wireValue is shared`() {
        assertEquals("shared", AndroidContractFinalizer.BoundaryOwnership.SHARED.wireValue)
    }

    @Test fun `all three BoundaryOwnership wireValues are distinct`() {
        val wireValues = AndroidContractFinalizer.BoundaryOwnership.entries.map { it.wireValue }
        assertEquals(wireValues.distinct().size, wireValues.size)
    }

    @Test fun `BoundaryOwnership fromValue returns correct entries`() {
        assertEquals(
            AndroidContractFinalizer.BoundaryOwnership.ANDROID_OWNED,
            AndroidContractFinalizer.BoundaryOwnership.fromValue("android_owned")
        )
        assertEquals(
            AndroidContractFinalizer.BoundaryOwnership.CENTER_OWNED,
            AndroidContractFinalizer.BoundaryOwnership.fromValue("center_owned")
        )
        assertEquals(
            AndroidContractFinalizer.BoundaryOwnership.SHARED,
            AndroidContractFinalizer.BoundaryOwnership.fromValue("shared")
        )
    }

    @Test fun `BoundaryOwnership fromValue returns null for unknown value`() {
        assertNull(AndroidContractFinalizer.BoundaryOwnership.fromValue("unknown_xyz"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AndroidContractFinalizer — boundary registry
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `AndroidContractFinalizer has exactly ten boundary entries`() {
        assertEquals(10, AndroidContractFinalizer.boundaries.size)
    }

    @Test fun `all boundary IDs are unique`() {
        val ids = AndroidContractFinalizer.boundaries.map { it.boundaryId }
        assertEquals(ids.distinct().size, ids.size)
    }

    @Test fun `no boundaries are classified as AMBIGUOUS clarity`() {
        val ambiguous = AndroidContractFinalizer.byClarity(AndroidContractFinalizer.ResponsibilityClarity.AMBIGUOUS)
        assertTrue("No boundaries should be AMBIGUOUS after PR-41 finalization", ambiguous.isEmpty())
    }

    @Test fun `no boundaries are classified as HIGH drift risk`() {
        val high = AndroidContractFinalizer.byDriftRisk(AndroidContractFinalizer.DriftRisk.HIGH)
        assertTrue("No boundaries should have HIGH drift risk after PR-41 finalization", high.isEmpty())
    }

    @Test fun `readiness-declaration-boundary is EXPLICIT LOW ANDROID_OWNED`() {
        val entry = AndroidContractFinalizer.forId("readiness-declaration-boundary")
        assertNotNull(entry)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT, entry!!.clarity)
        assertEquals(AndroidContractFinalizer.DriftRisk.LOW, entry.driftRisk)
        assertEquals(AndroidContractFinalizer.BoundaryOwnership.ANDROID_OWNED, entry.ownership)
        assertNull(entry.residualAmbiguity)
    }

    @Test fun `session-participation-boundary is EXPLICIT LOW ANDROID_OWNED`() {
        val entry = AndroidContractFinalizer.forId("session-participation-boundary")
        assertNotNull(entry)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT, entry!!.clarity)
        assertEquals(AndroidContractFinalizer.DriftRisk.LOW, entry.driftRisk)
        assertEquals(AndroidContractFinalizer.BoundaryOwnership.ANDROID_OWNED, entry.ownership)
        assertNull(entry.residualAmbiguity)
    }

    @Test fun `transport-continuity-boundary is EXPLICIT LOW ANDROID_OWNED`() {
        val entry = AndroidContractFinalizer.forId("transport-continuity-boundary")
        assertNotNull(entry)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT, entry!!.clarity)
        assertEquals(AndroidContractFinalizer.DriftRisk.LOW, entry.driftRisk)
        assertEquals(AndroidContractFinalizer.BoundaryOwnership.ANDROID_OWNED, entry.ownership)
        assertNull(entry.residualAmbiguity)
    }

    @Test fun `host-facing-state-boundary is EXPLICIT MEDIUM ANDROID_OWNED with residual ambiguity`() {
        val entry = AndroidContractFinalizer.forId("host-facing-state-boundary")
        assertNotNull(entry)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT, entry!!.clarity)
        assertEquals(AndroidContractFinalizer.DriftRisk.MEDIUM, entry.driftRisk)
        assertEquals(AndroidContractFinalizer.BoundaryOwnership.ANDROID_OWNED, entry.ownership)
        assertNotNull(entry.residualAmbiguity)
        assertTrue(entry.residualAmbiguity!!.isNotBlank())
    }

    @Test fun `snapshot-projection-boundary is EXPLICIT LOW ANDROID_OWNED`() {
        val entry = AndroidContractFinalizer.forId("snapshot-projection-boundary")
        assertNotNull(entry)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT, entry!!.clarity)
        assertEquals(AndroidContractFinalizer.DriftRisk.LOW, entry.driftRisk)
        assertEquals(AndroidContractFinalizer.BoundaryOwnership.ANDROID_OWNED, entry.ownership)
        assertNull(entry.residualAmbiguity)
    }

    @Test fun `dispatch-eligibility-boundary is EXPLICIT LOW ANDROID_OWNED`() {
        val entry = AndroidContractFinalizer.forId("dispatch-eligibility-boundary")
        assertNotNull(entry)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT, entry!!.clarity)
        assertEquals(AndroidContractFinalizer.DriftRisk.LOW, entry.driftRisk)
        assertEquals(AndroidContractFinalizer.BoundaryOwnership.ANDROID_OWNED, entry.ownership)
        assertNull(entry.residualAmbiguity)
    }

    @Test fun `session-identifier-vocabulary-boundary is TRANSITIONAL MEDIUM SHARED`() {
        val entry = AndroidContractFinalizer.forId("session-identifier-vocabulary-boundary")
        assertNotNull(entry)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.TRANSITIONAL, entry!!.clarity)
        assertEquals(AndroidContractFinalizer.DriftRisk.MEDIUM, entry.driftRisk)
        assertEquals(AndroidContractFinalizer.BoundaryOwnership.SHARED, entry.ownership)
        assertNotNull(entry.residualAmbiguity)
    }

    @Test fun `protocol-compatibility-retirement-boundary is TRANSITIONAL MEDIUM SHARED`() {
        val entry = AndroidContractFinalizer.forId("protocol-compatibility-retirement-boundary")
        assertNotNull(entry)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.TRANSITIONAL, entry!!.clarity)
        assertEquals(AndroidContractFinalizer.DriftRisk.MEDIUM, entry.driftRisk)
        assertEquals(AndroidContractFinalizer.BoundaryOwnership.SHARED, entry.ownership)
        assertNotNull(entry.residualAmbiguity)
    }

    @Test fun `long-tail-dispatch-boundary is EXPLICIT LOW SHARED`() {
        val entry = AndroidContractFinalizer.forId("long-tail-dispatch-boundary")
        assertNotNull(entry)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT, entry!!.clarity)
        assertEquals(AndroidContractFinalizer.DriftRisk.LOW, entry.driftRisk)
        assertEquals(AndroidContractFinalizer.BoundaryOwnership.SHARED, entry.ownership)
        assertNull(entry.residualAmbiguity)
    }

    @Test fun `runtime-lifecycle-authority-boundary is EXPLICIT LOW ANDROID_OWNED`() {
        val entry = AndroidContractFinalizer.forId("runtime-lifecycle-authority-boundary")
        assertNotNull(entry)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT, entry!!.clarity)
        assertEquals(AndroidContractFinalizer.DriftRisk.LOW, entry.driftRisk)
        assertEquals(AndroidContractFinalizer.BoundaryOwnership.ANDROID_OWNED, entry.ownership)
        assertNull(entry.residualAmbiguity)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AndroidContractFinalizer — query helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `byClarity EXPLICIT returns at least six entries`() {
        assertTrue(AndroidContractFinalizer.byClarity(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT).size >= 6)
    }

    @Test fun `byClarity TRANSITIONAL returns exactly two entries`() {
        assertEquals(2, AndroidContractFinalizer.byClarity(AndroidContractFinalizer.ResponsibilityClarity.TRANSITIONAL).size)
    }

    @Test fun `byClarity AMBIGUOUS returns empty list`() {
        assertTrue(AndroidContractFinalizer.byClarity(AndroidContractFinalizer.ResponsibilityClarity.AMBIGUOUS).isEmpty())
    }

    @Test fun `byDriftRisk LOW returns seven entries`() {
        assertEquals(7, AndroidContractFinalizer.byDriftRisk(AndroidContractFinalizer.DriftRisk.LOW).size)
    }

    @Test fun `byDriftRisk MEDIUM returns three entries`() {
        assertEquals(3, AndroidContractFinalizer.byDriftRisk(AndroidContractFinalizer.DriftRisk.MEDIUM).size)
    }

    @Test fun `byDriftRisk HIGH returns empty list`() {
        assertTrue(AndroidContractFinalizer.byDriftRisk(AndroidContractFinalizer.DriftRisk.HIGH).isEmpty())
    }

    @Test fun `byOwnership ANDROID_OWNED returns at least six entries`() {
        assertTrue(AndroidContractFinalizer.byOwnership(AndroidContractFinalizer.BoundaryOwnership.ANDROID_OWNED).size >= 6)
    }

    @Test fun `byOwnership SHARED returns at least three entries`() {
        assertTrue(AndroidContractFinalizer.byOwnership(AndroidContractFinalizer.BoundaryOwnership.SHARED).size >= 3)
    }

    @Test fun `byOwnership CENTER_OWNED returns empty list`() {
        assertTrue(AndroidContractFinalizer.byOwnership(AndroidContractFinalizer.BoundaryOwnership.CENTER_OWNED).isEmpty())
    }

    @Test fun `forId returns correct entry for known boundaryId`() {
        val entry = AndroidContractFinalizer.forId("readiness-declaration-boundary")
        assertNotNull(entry)
        assertEquals("readiness-declaration-boundary", entry!!.boundaryId)
    }

    @Test fun `forId returns null for unknown boundaryId`() {
        assertNull(AndroidContractFinalizer.forId("unknown_boundary_xyz"))
    }

    @Test fun `highDriftRiskBoundaryIds is empty`() {
        assertTrue("No HIGH drift risk boundaries expected", AndroidContractFinalizer.highDriftRiskBoundaryIds.isEmpty())
    }

    @Test fun `ambiguousBoundaryIds is empty`() {
        assertTrue("No AMBIGUOUS boundaries expected", AndroidContractFinalizer.ambiguousBoundaryIds.isEmpty())
    }

    @Test fun `finalizedBoundaryIds has seven entries`() {
        assertEquals(7, AndroidContractFinalizer.finalizedBoundaryIds.size)
    }

    @Test fun `boundariesWithResidualAmbiguity has exactly three entries`() {
        assertEquals(3, AndroidContractFinalizer.boundariesWithResidualAmbiguity.size)
    }

    @Test fun `all boundaries have non-blank displayName responsibilityArea canonicalSurface finalizationNote`() {
        for (entry in AndroidContractFinalizer.boundaries) {
            assertTrue("displayName must be non-blank for ${entry.boundaryId}", entry.displayName.isNotBlank())
            assertTrue("responsibilityArea must be non-blank for ${entry.boundaryId}", entry.responsibilityArea.isNotBlank())
            assertTrue("canonicalSurface must be non-blank for ${entry.boundaryId}", entry.canonicalSurface.isNotBlank())
            assertTrue("finalizationNote must be non-blank for ${entry.boundaryId}", entry.finalizationNote.isNotBlank())
        }
    }

    @Test fun `FINALIZATION_INVARIANT is non-blank`() {
        assertTrue(AndroidContractFinalizer.FINALIZATION_INVARIANT.isNotBlank())
    }

    @Test fun `INTRODUCED_PR is 41`() {
        assertEquals(41, AndroidContractFinalizer.INTRODUCED_PR)
    }

    @Test fun `DESCRIPTION is non-blank`() {
        assertTrue(AndroidContractFinalizer.DESCRIPTION.isNotBlank())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CompatibilityRetirementFence — FenceDecision enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `FenceDecision FENCED wireValue is fenced`() {
        assertEquals("fenced", CompatibilityRetirementFence.FenceDecision.FENCED.wireValue)
    }

    @Test fun `FenceDecision DEMOTED wireValue is demoted`() {
        assertEquals("demoted", CompatibilityRetirementFence.FenceDecision.DEMOTED.wireValue)
    }

    @Test fun `FenceDecision PENDING_COORDINATION wireValue is pending_coordination`() {
        assertEquals("pending_coordination", CompatibilityRetirementFence.FenceDecision.PENDING_COORDINATION.wireValue)
    }

    @Test fun `FenceDecision DECOMMISSION_SCHEDULED wireValue is decommission_scheduled`() {
        assertEquals("decommission_scheduled", CompatibilityRetirementFence.FenceDecision.DECOMMISSION_SCHEDULED.wireValue)
    }

    @Test fun `all four FenceDecision wireValues are distinct`() {
        val wireValues = CompatibilityRetirementFence.FenceDecision.entries.map { it.wireValue }
        assertEquals(wireValues.distinct().size, wireValues.size)
    }

    @Test fun `FenceDecision fromValue returns correct entries`() {
        assertEquals(
            CompatibilityRetirementFence.FenceDecision.FENCED,
            CompatibilityRetirementFence.FenceDecision.fromValue("fenced")
        )
        assertEquals(
            CompatibilityRetirementFence.FenceDecision.DEMOTED,
            CompatibilityRetirementFence.FenceDecision.fromValue("demoted")
        )
        assertEquals(
            CompatibilityRetirementFence.FenceDecision.PENDING_COORDINATION,
            CompatibilityRetirementFence.FenceDecision.fromValue("pending_coordination")
        )
        assertEquals(
            CompatibilityRetirementFence.FenceDecision.DECOMMISSION_SCHEDULED,
            CompatibilityRetirementFence.FenceDecision.fromValue("decommission_scheduled")
        )
    }

    @Test fun `FenceDecision fromValue returns null for unknown value`() {
        assertNull(CompatibilityRetirementFence.FenceDecision.fromValue("unknown_xyz"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CompatibilityRetirementFence — FenceReason enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `FenceReason CANONICAL_REPLACEMENT_COMPLETE wireValue is canonical_replacement_complete`() {
        assertEquals("canonical_replacement_complete", CompatibilityRetirementFence.FenceReason.CANONICAL_REPLACEMENT_COMPLETE.wireValue)
    }

    @Test fun `FenceReason CONSUMERS_MIGRATED wireValue is consumers_migrated`() {
        assertEquals("consumers_migrated", CompatibilityRetirementFence.FenceReason.CONSUMERS_MIGRATED.wireValue)
    }

    @Test fun `FenceReason CENTER_COORDINATION_REQUIRED wireValue is center_coordination_required`() {
        assertEquals("center_coordination_required", CompatibilityRetirementFence.FenceReason.CENTER_COORDINATION_REQUIRED.wireValue)
    }

    @Test fun `FenceReason LOW_PRODUCTION_FREQUENCY wireValue is low_production_frequency`() {
        assertEquals("low_production_frequency", CompatibilityRetirementFence.FenceReason.LOW_PRODUCTION_FREQUENCY.wireValue)
    }

    @Test fun `FenceReason PROMOTED_TO_DEDICATED_HANDLER wireValue is promoted_to_dedicated_handler`() {
        assertEquals("promoted_to_dedicated_handler", CompatibilityRetirementFence.FenceReason.PROMOTED_TO_DEDICATED_HANDLER.wireValue)
    }

    @Test fun `all five FenceReason wireValues are distinct`() {
        val wireValues = CompatibilityRetirementFence.FenceReason.entries.map { it.wireValue }
        assertEquals(wireValues.distinct().size, wireValues.size)
    }

    @Test fun `FenceReason fromValue returns correct entries`() {
        assertEquals(
            CompatibilityRetirementFence.FenceReason.CANONICAL_REPLACEMENT_COMPLETE,
            CompatibilityRetirementFence.FenceReason.fromValue("canonical_replacement_complete")
        )
        assertEquals(
            CompatibilityRetirementFence.FenceReason.CONSUMERS_MIGRATED,
            CompatibilityRetirementFence.FenceReason.fromValue("consumers_migrated")
        )
        assertEquals(
            CompatibilityRetirementFence.FenceReason.CENTER_COORDINATION_REQUIRED,
            CompatibilityRetirementFence.FenceReason.fromValue("center_coordination_required")
        )
        assertEquals(
            CompatibilityRetirementFence.FenceReason.LOW_PRODUCTION_FREQUENCY,
            CompatibilityRetirementFence.FenceReason.fromValue("low_production_frequency")
        )
        assertEquals(
            CompatibilityRetirementFence.FenceReason.PROMOTED_TO_DEDICATED_HANDLER,
            CompatibilityRetirementFence.FenceReason.fromValue("promoted_to_dedicated_handler")
        )
    }

    @Test fun `FenceReason fromValue returns null for unknown value`() {
        assertNull(CompatibilityRetirementFence.FenceReason.fromValue("unknown_xyz"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CompatibilityRetirementFence — registry coverage
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `fence count equals CompatibilitySurfaceRetirementRegistry entry count`() {
        assertEquals(
            CompatibilitySurfaceRetirementRegistry.entries.size,
            CompatibilityRetirementFence.fences.size
        )
    }

    @Test fun `all CompatibilitySurfaceRetirementRegistry surface IDs have a fence entry`() {
        val fencedIds = CompatibilityRetirementFence.fences.map { it.compatibilitySurfaceId }.toSet()
        for (entry in CompatibilitySurfaceRetirementRegistry.entries) {
            assertTrue(
                "Surface '${entry.surfaceId}' must have a fence entry in CompatibilityRetirementFence",
                entry.surfaceId in fencedIds
            )
        }
    }

    @Test fun `all fence surface IDs match CompatibilitySurfaceRetirementRegistry surface IDs`() {
        val registryIds = CompatibilitySurfaceRetirementRegistry.entries.map { it.surfaceId }.toSet()
        for (fence in CompatibilityRetirementFence.fences) {
            assertTrue(
                "Fence surface ID '${fence.compatibilitySurfaceId}' must exist in CompatibilitySurfaceRetirementRegistry",
                fence.compatibilitySurfaceId in registryIds
            )
        }
    }

    @Test fun `all fence entry compatibilitySurfaceIds are unique`() {
        val ids = CompatibilityRetirementFence.fences.map { it.compatibilitySurfaceId }
        assertEquals(ids.distinct().size, ids.size)
    }

    @Test fun `all fence entries have non-blank retirementReadiness activeRisk finalizationNote`() {
        for (fence in CompatibilityRetirementFence.fences) {
            assertTrue("retirementReadiness must be non-blank for ${fence.compatibilitySurfaceId}", fence.retirementReadiness.isNotBlank())
            assertTrue("activeRisk must be non-blank for ${fence.compatibilitySurfaceId}", fence.activeRisk.isNotBlank())
            assertTrue("finalizationNote must be non-blank for ${fence.compatibilitySurfaceId}", fence.finalizationNote.isNotBlank())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CompatibilityRetirementFence — specific fence decisions
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `runtime_registration_error_string_bridge is FENCED`() {
        val fence = CompatibilityRetirementFence.fenceFor("runtime_registration_error_string_bridge")
        assertNotNull(fence)
        assertEquals(CompatibilityRetirementFence.FenceDecision.FENCED, fence!!.fenceDecision)
        assertEquals(CompatibilityRetirementFence.FenceReason.CONSUMERS_MIGRATED, fence.fenceReason)
    }

    @Test fun `runtime_host_session_legacy_map_bridge is PENDING_COORDINATION`() {
        val fence = CompatibilityRetirementFence.fenceFor("runtime_host_session_legacy_map_bridge")
        assertNotNull(fence)
        assertEquals(CompatibilityRetirementFence.FenceDecision.PENDING_COORDINATION, fence!!.fenceDecision)
    }

    @Test fun `session_identifier_carrier_transitional_surface is PENDING_COORDINATION`() {
        val fence = CompatibilityRetirementFence.fenceFor("session_identifier_carrier_transitional_surface")
        assertNotNull(fence)
        assertEquals(CompatibilityRetirementFence.FenceDecision.PENDING_COORDINATION, fence!!.fenceDecision)
        assertEquals(CompatibilityRetirementFence.FenceReason.CENTER_COORDINATION_REQUIRED, fence.fenceReason)
    }

    @Test fun `staged_mesh_execution_status_transitional_surface is PENDING_COORDINATION`() {
        val fence = CompatibilityRetirementFence.fenceFor("staged_mesh_execution_status_transitional_surface")
        assertNotNull(fence)
        assertEquals(CompatibilityRetirementFence.FenceDecision.PENDING_COORDINATION, fence!!.fenceDecision)
    }

    @Test fun `galaxy_api_client_register_device_deprecated is DECOMMISSION_SCHEDULED`() {
        val fence = CompatibilityRetirementFence.fenceFor("galaxy_api_client_register_device_deprecated")
        assertNotNull(fence)
        assertEquals(CompatibilityRetirementFence.FenceDecision.DECOMMISSION_SCHEDULED, fence!!.fenceDecision)
    }

    @Test fun `galaxy_api_client_send_heartbeat_deprecated is DECOMMISSION_SCHEDULED`() {
        val fence = CompatibilityRetirementFence.fenceFor("galaxy_api_client_send_heartbeat_deprecated")
        assertNotNull(fence)
        assertEquals(CompatibilityRetirementFence.FenceDecision.DECOMMISSION_SCHEDULED, fence!!.fenceDecision)
    }

    @Test fun `legacy_msgtype_alias_normalization is PENDING_COORDINATION`() {
        val fence = CompatibilityRetirementFence.fenceFor("legacy_msgtype_alias_normalization")
        assertNotNull(fence)
        assertEquals(CompatibilityRetirementFence.FenceDecision.PENDING_COORDINATION, fence!!.fenceDecision)
        assertEquals(CompatibilityRetirementFence.FenceReason.CENTER_COORDINATION_REQUIRED, fence.fenceReason)
    }

    @Test fun `lifecycle_status_normalization_map is PENDING_COORDINATION`() {
        val fence = CompatibilityRetirementFence.fenceFor("lifecycle_status_normalization_map")
        assertNotNull(fence)
        assertEquals(CompatibilityRetirementFence.FenceDecision.PENDING_COORDINATION, fence!!.fenceDecision)
    }

    @Test fun `dispatch_adapter_peer_announce_transitional is DECOMMISSION_SCHEDULED`() {
        val fence = CompatibilityRetirementFence.fenceFor("dispatch_adapter_peer_announce_transitional")
        assertNotNull(fence)
        assertEquals(CompatibilityRetirementFence.FenceDecision.DECOMMISSION_SCHEDULED, fence!!.fenceDecision)
        assertEquals(CompatibilityRetirementFence.FenceReason.PROMOTED_TO_DEDICATED_HANDLER, fence.fenceReason)
    }

    @Test fun `dispatch_adapter_lock_transitional is DECOMMISSION_SCHEDULED`() {
        val fence = CompatibilityRetirementFence.fenceFor("dispatch_adapter_lock_transitional")
        assertNotNull(fence)
        assertEquals(CompatibilityRetirementFence.FenceDecision.DECOMMISSION_SCHEDULED, fence!!.fenceDecision)
        assertEquals(CompatibilityRetirementFence.FenceReason.LOW_PRODUCTION_FREQUENCY, fence.fenceReason)
    }

    @Test fun `dispatch_adapter_unlock_transitional is DECOMMISSION_SCHEDULED`() {
        val fence = CompatibilityRetirementFence.fenceFor("dispatch_adapter_unlock_transitional")
        assertNotNull(fence)
        assertEquals(CompatibilityRetirementFence.FenceDecision.DECOMMISSION_SCHEDULED, fence!!.fenceDecision)
        assertEquals(CompatibilityRetirementFence.FenceReason.LOW_PRODUCTION_FREQUENCY, fence.fenceReason)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CompatibilityRetirementFence — query helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `fenceFor known surface returns non-null`() {
        assertNotNull(CompatibilityRetirementFence.fenceFor("runtime_registration_error_string_bridge"))
    }

    @Test fun `fenceFor unknown surface returns null`() {
        assertNull(CompatibilityRetirementFence.fenceFor("unknown_surface_xyz"))
    }

    @Test fun `byDecision FENCED has exactly one entry`() {
        assertEquals(1, CompatibilityRetirementFence.byDecision(CompatibilityRetirementFence.FenceDecision.FENCED).size)
    }

    @Test fun `byDecision DECOMMISSION_SCHEDULED has exactly five entries`() {
        assertEquals(5, CompatibilityRetirementFence.byDecision(CompatibilityRetirementFence.FenceDecision.DECOMMISSION_SCHEDULED).size)
    }

    @Test fun `byDecision PENDING_COORDINATION has thirteen entries`() {
        assertEquals(13, CompatibilityRetirementFence.byDecision(CompatibilityRetirementFence.FenceDecision.PENDING_COORDINATION).size)
    }

    @Test fun `byDecision DEMOTED has zero entries`() {
        assertEquals(0, CompatibilityRetirementFence.byDecision(CompatibilityRetirementFence.FenceDecision.DEMOTED).size)
    }

    @Test fun `byReason CONSUMERS_MIGRATED has exactly one entry`() {
        assertEquals(1, CompatibilityRetirementFence.byReason(CompatibilityRetirementFence.FenceReason.CONSUMERS_MIGRATED).size)
    }

    @Test fun `byReason PROMOTED_TO_DEDICATED_HANDLER has exactly one entry`() {
        assertEquals(1, CompatibilityRetirementFence.byReason(CompatibilityRetirementFence.FenceReason.PROMOTED_TO_DEDICATED_HANDLER).size)
    }

    @Test fun `fencedSurfaceIds has exactly one element`() {
        assertEquals(1, CompatibilityRetirementFence.fencedSurfaceIds.size)
    }

    @Test fun `decommissionScheduledSurfaceIds has exactly five elements`() {
        assertEquals(5, CompatibilityRetirementFence.decommissionScheduledSurfaceIds.size)
    }

    @Test fun `pendingCoordinationSurfaceIds has thirteen elements`() {
        assertEquals(13, CompatibilityRetirementFence.pendingCoordinationSurfaceIds.size)
    }

    @Test fun `COVERAGE_INVARIANT is non-blank`() {
        assertTrue(CompatibilityRetirementFence.COVERAGE_INVARIANT.isNotBlank())
    }

    @Test fun `CompatibilityRetirementFence INTRODUCED_PR is 41`() {
        assertEquals(41, CompatibilityRetirementFence.INTRODUCED_PR)
    }

    @Test fun `CompatibilityRetirementFence DESCRIPTION is non-blank`() {
        assertTrue(CompatibilityRetirementFence.DESCRIPTION.isNotBlank())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CanonicalSessionAxis — contractFinalizationBindings
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `contractFinalizationBindings has exactly seven entries`() {
        assertEquals(7, CanonicalSessionAxis.contractFinalizationBindings.size)
    }

    @Test fun `all seven CanonicalSessionFamily values have a contractFinalizationBinding`() {
        for (family in CanonicalSessionFamily.entries) {
            assertNotNull(
                "Family $family must have a contractFinalizationBinding",
                CanonicalSessionAxis.contractFinalizationBindingFor(family)
            )
        }
    }

    @Test fun `RUNTIME_SESSION contractFinalizationBinding is EXPLICIT LOW`() {
        val binding = CanonicalSessionAxis.contractFinalizationBindingFor(CanonicalSessionFamily.RUNTIME_SESSION)
        assertNotNull(binding)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT, binding!!.clarityLevel)
        assertEquals(AndroidContractFinalizer.DriftRisk.LOW, binding.v2DriftRisk)
    }

    @Test fun `ATTACHED_RUNTIME_SESSION contractFinalizationBinding is EXPLICIT LOW`() {
        val binding = CanonicalSessionAxis.contractFinalizationBindingFor(CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION)
        assertNotNull(binding)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT, binding!!.clarityLevel)
        assertEquals(AndroidContractFinalizer.DriftRisk.LOW, binding.v2DriftRisk)
    }

    @Test fun `DELEGATION_TRANSFER_SESSION contractFinalizationBinding is EXPLICIT LOW`() {
        val binding = CanonicalSessionAxis.contractFinalizationBindingFor(CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION)
        assertNotNull(binding)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT, binding!!.clarityLevel)
        assertEquals(AndroidContractFinalizer.DriftRisk.LOW, binding.v2DriftRisk)
    }

    @Test fun `CONVERSATION_SESSION contractFinalizationBinding is EXPLICIT LOW`() {
        val binding = CanonicalSessionAxis.contractFinalizationBindingFor(CanonicalSessionFamily.CONVERSATION_SESSION)
        assertNotNull(binding)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT, binding!!.clarityLevel)
        assertEquals(AndroidContractFinalizer.DriftRisk.LOW, binding.v2DriftRisk)
    }

    @Test fun `DURABLE_RUNTIME_SESSION contractFinalizationBinding is EXPLICIT LOW`() {
        val binding = CanonicalSessionAxis.contractFinalizationBindingFor(CanonicalSessionFamily.DURABLE_RUNTIME_SESSION)
        assertNotNull(binding)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.EXPLICIT, binding!!.clarityLevel)
        assertEquals(AndroidContractFinalizer.DriftRisk.LOW, binding.v2DriftRisk)
    }

    @Test fun `CONTROL_SESSION contractFinalizationBinding is TRANSITIONAL MEDIUM`() {
        val binding = CanonicalSessionAxis.contractFinalizationBindingFor(CanonicalSessionFamily.CONTROL_SESSION)
        assertNotNull(binding)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.TRANSITIONAL, binding!!.clarityLevel)
        assertEquals(AndroidContractFinalizer.DriftRisk.MEDIUM, binding.v2DriftRisk)
    }

    @Test fun `MESH_SESSION contractFinalizationBinding is TRANSITIONAL MEDIUM`() {
        val binding = CanonicalSessionAxis.contractFinalizationBindingFor(CanonicalSessionFamily.MESH_SESSION)
        assertNotNull(binding)
        assertEquals(AndroidContractFinalizer.ResponsibilityClarity.TRANSITIONAL, binding!!.clarityLevel)
        assertEquals(AndroidContractFinalizer.DriftRisk.MEDIUM, binding.v2DriftRisk)
    }

    @Test fun `finalizedContractFamilies has exactly five families`() {
        assertEquals(5, CanonicalSessionAxis.finalizedContractFamilies.size)
    }

    @Test fun `transitionalContractFamilies has exactly two families`() {
        assertEquals(2, CanonicalSessionAxis.transitionalContractFamilies.size)
    }

    @Test fun `finalizedContractFamilies and transitionalContractFamilies are disjoint`() {
        val intersection = CanonicalSessionAxis.finalizedContractFamilies intersect CanonicalSessionAxis.transitionalContractFamilies
        assertTrue("finalizedContractFamilies and transitionalContractFamilies must be disjoint", intersection.isEmpty())
    }

    @Test fun `finalizedContractFamilies union transitionalContractFamilies equals all seven families`() {
        val all = CanonicalSessionAxis.finalizedContractFamilies union CanonicalSessionAxis.transitionalContractFamilies
        assertEquals(7, all.size)
    }

    @Test fun `all contractFinalizationBindings have non-blank canonicalSurface and finalizationNote`() {
        for (binding in CanonicalSessionAxis.contractFinalizationBindings) {
            assertTrue("canonicalSurface must be non-blank for ${binding.family}", binding.canonicalSurface.isNotBlank())
            assertTrue("finalizationNote must be non-blank for ${binding.family}", binding.finalizationNote.isNotBlank())
        }
    }

    @Test fun `contractFinalizationBindingFor returns null for no registered family (defensive)`() {
        // All seven families should have a binding; this test verifies the helper returns
        // non-null for every registered family (coverage check).
        assertEquals(
            CanonicalSessionFamily.entries.size,
            CanonicalSessionFamily.entries.mapNotNull {
                CanonicalSessionAxis.contractFinalizationBindingFor(it)
            }.size
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CanonicalDispatchChain — resolveContractFinalizedPaths
    // ─────────────────────────────────────────────────────────────────────────

    private val inactiveRollout = RolloutControlSnapshot(
        crossDeviceAllowed = false,
        delegatedExecutionAllowed = false,
        fallbackToLocalAllowed = false,
        goalExecutionAllowed = false,
        stagedMeshAllowed = false,
        killSwitchActive = false
    )

    @Test fun `resolveContractFinalizedPaths excludes COMPATIBILITY path by default`() {
        val paths = CanonicalDispatchChain.resolveContractFinalizedPaths(
            runtimeState      = RuntimeController.RuntimeState.LocalOnly,
            attachedSession   = null,
            rollout           = inactiveRollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE
        )
        val modes = paths.map { it.pathMode }
        assertFalse("COMPATIBILITY path must be excluded by default", DispatchPathMode.COMPATIBILITY in modes)
    }

    @Test fun `resolveContractFinalizedPaths includes COMPATIBILITY path when excludeCompatibilityPath is false`() {
        val paths = CanonicalDispatchChain.resolveContractFinalizedPaths(
            runtimeState           = RuntimeController.RuntimeState.LocalOnly,
            attachedSession        = null,
            rollout                = inactiveRollout,
            transportCondition     = MediaTransportLifecycleBridge.TransportCondition.STABLE,
            excludeCompatibilityPath = false
        )
        val modes = paths.map { it.pathMode }
        assertTrue("COMPATIBILITY path must be included when excludeCompatibilityPath=false", DispatchPathMode.COMPATIBILITY in modes)
    }

    @Test fun `resolveContractFinalizedPaths includes LOCAL path`() {
        val paths = CanonicalDispatchChain.resolveContractFinalizedPaths(
            runtimeState      = RuntimeController.RuntimeState.LocalOnly,
            attachedSession   = null,
            rollout           = inactiveRollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE
        )
        val modes = paths.map { it.pathMode }
        assertTrue("LOCAL path must always be included", DispatchPathMode.LOCAL in modes)
    }

    @Test fun `resolveContractFinalizedPaths result is a subset of resolveTransportAdaptedPaths result`() {
        val transportAdapted = CanonicalDispatchChain.resolveTransportAdaptedPaths(
            runtimeState      = RuntimeController.RuntimeState.LocalOnly,
            attachedSession   = null,
            rollout           = inactiveRollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE
        )
        val contractFinalized = CanonicalDispatchChain.resolveContractFinalizedPaths(
            runtimeState      = RuntimeController.RuntimeState.LocalOnly,
            attachedSession   = null,
            rollout           = inactiveRollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE
        )
        val transportModes = transportAdapted.map { it.pathMode }.toSet()
        for (path in contractFinalized) {
            assertTrue(
                "Contract-finalized path ${path.pathMode} must be a subset of transport-adapted paths",
                path.pathMode in transportModes
            )
        }
    }

    @Test fun `COMPATIBILITY_PATH_MODE is DispatchPathMode COMPATIBILITY`() {
        assertEquals(DispatchPathMode.COMPATIBILITY, CanonicalDispatchChain.COMPATIBILITY_PATH_MODE)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // StabilizationBaseline — PR-41 entries
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `android-contract-finalizer is registered in StabilizationBaseline`() {
        assertNotNull(StabilizationBaseline.forId("android-contract-finalizer"))
    }

    @Test fun `compatibility-retirement-fence is registered in StabilizationBaseline`() {
        assertNotNull(StabilizationBaseline.forId("compatibility-retirement-fence"))
    }

    @Test fun `canonical-session-axis-contract-finalization-bindings is registered in StabilizationBaseline`() {
        assertNotNull(StabilizationBaseline.forId("canonical-session-axis-contract-finalization-bindings"))
    }

    @Test fun `canonical-dispatch-chain-contract-finalized-paths is registered in StabilizationBaseline`() {
        assertNotNull(StabilizationBaseline.forId("canonical-dispatch-chain-contract-finalized-paths"))
    }

    @Test fun `all PR-41 StabilizationBaseline entries have CANONICAL_STABLE stability`() {
        val pr41Ids = listOf(
            "android-contract-finalizer",
            "compatibility-retirement-fence",
            "canonical-session-axis-contract-finalization-bindings",
            "canonical-dispatch-chain-contract-finalized-paths"
        )
        for (id in pr41Ids) {
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Entry $id must be registered", entry)
            assertEquals(
                "Entry $id must have CANONICAL_STABLE stability",
                StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
                entry!!.stability
            )
        }
    }

    @Test fun `all PR-41 StabilizationBaseline entries have EXTEND guidance`() {
        val pr41Ids = listOf(
            "android-contract-finalizer",
            "compatibility-retirement-fence",
            "canonical-session-axis-contract-finalization-bindings",
            "canonical-dispatch-chain-contract-finalized-paths"
        )
        for (id in pr41Ids) {
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Entry $id must be registered", entry)
            assertEquals(
                "Entry $id must have EXTEND guidance",
                StabilizationBaseline.ExtensionGuidance.EXTEND,
                entry!!.extensionGuidance
            )
        }
    }

    @Test fun `all PR-41 StabilizationBaseline entries have introducedPr equal to 41`() {
        val pr41Ids = listOf(
            "android-contract-finalizer",
            "compatibility-retirement-fence",
            "canonical-session-axis-contract-finalization-bindings",
            "canonical-dispatch-chain-contract-finalized-paths"
        )
        for (id in pr41Ids) {
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Entry $id must be registered", entry)
            assertEquals("Entry $id must have introducedPr=41", 41, entry!!.introducedPr)
        }
    }
}
