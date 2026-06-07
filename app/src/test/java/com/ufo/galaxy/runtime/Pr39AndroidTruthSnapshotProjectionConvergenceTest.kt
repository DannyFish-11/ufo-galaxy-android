package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-39 — Android Truth / Snapshot / Host-facing Projection Convergence.
 *
 * Focused test suite validating all PR-39 additions:
 *
 *  1. [RuntimeTruthPrecedenceRules] — three-tier truth model.
 *     - [RuntimeTruthPrecedenceRules.TruthTier] enum values and wireValues.
 *     - [RuntimeTruthPrecedenceRules.ProjectionStorage] enum values and wireValues.
 *     - Registry completeness: five AUTHORITATIVE, one SNAPSHOT, three PROJECTION entries.
 *     - Tier query helpers: [RuntimeTruthPrecedenceRules.byTier],
 *       [RuntimeTruthPrecedenceRules.forId], [RuntimeTruthPrecedenceRules.isRegistered].
 *     - Tier set helpers: authoritativeSurfaceIds, snapshotSurfaceIds, projectionSurfaceIds.
 *     - Storage helpers: reactiveProjectionIds, pointInTimeProjectionIds.
 *     - AUTHORITATIVE entries have no derivedFrom.
 *     - SNAPSHOT and PROJECTION entries all have non-null derivedFrom.
 *     - Precedence rules list has four entries.
 *     - Gate constants are non-blank.
 *
 *  2. [HostFacingProjectionContract] — explicit projection contract governance.
 *     - [HostFacingProjectionContract.ContractStability] enum values and wireValues.
 *     - [HostFacingProjectionContract.ProjectionKind] enum values and wireValues.
 *     - Registry completeness: eight canonical, zero transitional, one deprecated.
 *     - Canonical surfaces all have null canonicalReplacement.
 *     - Deprecated surface has non-null canonicalReplacement.
 *     - Query helpers: byStability, byKind, forId, isCanonical, isDeprecated.
 *     - Set helpers: canonicalSurfaceIds, deprecatedSurfaceIds, reactiveContractIds.
 *     - coUpdatedSnapshotAndReadinessSurfaceIds contains exactly two entries.
 *     - CO_DERIVATION_INVARIANT is non-blank.
 *
 *  3. [CanonicalSessionAxis] truth binding additions (PR-39).
 *     - [CanonicalSessionAxis.SessionTruthBinding] data class exists with correct fields.
 *     - truthBindings has seven entries (one per session family).
 *     - truthBindingFor returns correct entry per family.
 *     - RUNTIME_SESSION binding has non-null snapshotCarrier.
 *     - ATTACHED_RUNTIME_SESSION binding has non-null snapshotCarrier.
 *     - DURABLE_RUNTIME_SESSION binding has non-null snapshotCarrier.
 *     - CONTROL_SESSION binding has null snapshotCarrier.
 *     - DELEGATION_TRANSFER_SESSION binding has null snapshotCarrier.
 *     - CONVERSATION_SESSION binding has null snapshotCarrier.
 *     - MESH_SESSION binding has null snapshotCarrier.
 *     - familiesWithSnapshotCarrier contains exactly three families.
 *     - familiesWithoutSnapshotCarrier contains exactly four families.
 *     - all truth bindings have non-blank authoritativeSource and projectionNote.
 *
 *  4. [StabilizationBaseline] — PR-39 entries registered.
 *     - runtime-truth-precedence-rules is registered as CANONICAL_STABLE.
 *     - host-facing-projection-contract is registered as CANONICAL_STABLE.
 *     - canonical-session-axis-truth-bindings is registered as CANONICAL_STABLE.
 *     - all PR-39 entries have introducedPr = 39.
 *
 * ## Test matrix
 *
 * ### RuntimeTruthPrecedenceRules — TruthTier enum
 *  - AUTHORITATIVE wireValue is "authoritative"
 *  - SNAPSHOT wireValue is "snapshot"
 *  - PROJECTION wireValue is "projection"
 *  - all three wireValues are distinct
 *
 * ### RuntimeTruthPrecedenceRules — ProjectionStorage enum
 *  - REACTIVE wireValue is "reactive"
 *  - POINT_IN_TIME wireValue is "point_in_time"
 *  - both wireValues are distinct
 *
 * ### RuntimeTruthPrecedenceRules — registry
 *  - exactly five AUTHORITATIVE entries
 *  - exactly one SNAPSHOT entry
 *  - exactly three PROJECTION entries
 *  - nine total entries
 *  - all surfaceIds are unique
 *  - AUTHORITATIVE entries have null derivedFrom
 *  - SNAPSHOT and PROJECTION entries have non-null derivedFrom
 *  - snapshot entry is reactive
 *  - reactive PROJECTION entries are reactive
 *  - point-in-time PROJECTION entries are point-in-time
 *
 * ### RuntimeTruthPrecedenceRules — query helpers
 *  - byTier(AUTHORITATIVE) count is 5
 *  - byTier(SNAPSHOT) count is 1
 *  - byTier(PROJECTION) count is 3
 *  - forId returns correct entry for known surfaceIds
 *  - forId returns null for unknown surfaceId
 *  - isRegistered returns true for registered surfaceIds
 *  - isRegistered returns false for unknown surfaceId
 *  - authoritativeSurfaceIds has 5 elements
 *  - snapshotSurfaceIds has 1 element
 *  - projectionSurfaceIds has 3 elements
 *  - reactiveProjectionIds does not include point-in-time entries
 *  - pointInTimeProjectionIds does not include reactive entries
 *
 * ### RuntimeTruthPrecedenceRules — precedence rules and constants
 *  - precedenceRules has 4 entries
 *  - all four rules are non-blank
 *  - SNAPSHOT_UPDATE_GATE contains "updateHostSessionSnapshot"
 *  - STATE_TRANSITION_GATE contains "transitionState"
 *  - INTRODUCED_PR is 39
 *  - DESCRIPTION is non-blank
 *
 * ### HostFacingProjectionContract — ContractStability enum
 *  - CANONICAL wireValue is "canonical"
 *  - TRANSITIONAL wireValue is "transitional"
 *  - DEPRECATED wireValue is "deprecated"
 *  - all three wireValues are distinct
 *
 * ### HostFacingProjectionContract — ProjectionKind enum
 *  - REACTIVE_FLOW wireValue is "reactive_flow"
 *  - EVENT_STREAM wireValue is "event_stream"
 *  - POINT_IN_TIME_QUERY wireValue is "point_in_time_query"
 *  - all three wireValues are distinct
 *
 * ### HostFacingProjectionContract — registry
 *  - exactly eight CANONICAL entries
 *  - exactly zero TRANSITIONAL entries
 *  - exactly one DEPRECATED entry
 *  - nine total entries
 *  - all surfaceIds are unique
 *  - canonical entries have null canonicalReplacement
 *  - deprecated entries have non-null canonicalReplacement
 *  - all entries have non-blank displayName, kotlinSymbol, projectedType, consumptionGuidance
 *
 * ### HostFacingProjectionContract — query helpers
 *  - byStability(CANONICAL) count is 8
 *  - byStability(DEPRECATED) count is 1
 *  - byKind(REACTIVE_FLOW) count is 5
 *  - byKind(EVENT_STREAM) count is 1
 *  - byKind(POINT_IN_TIME_QUERY) count is 3
 *  - forId returns correct entry for known surfaceId
 *  - forId returns null for unknown surfaceId
 *  - isCanonical returns true for canonical surface
 *  - isCanonical returns false for deprecated surface
 *  - isCanonical returns false for unknown surface
 *  - isDeprecated returns true for deprecated surface
 *  - isDeprecated returns false for canonical surface
 *  - canonicalSurfaceIds has 8 elements
 *  - deprecatedSurfaceIds has 1 element
 *  - reactiveContractIds includes both REACTIVE_FLOW and EVENT_STREAM surfaces
 *
 * ### HostFacingProjectionContract — co-derivation invariant
 *  - coUpdatedSnapshotAndReadinessSurfaceIds has exactly 2 entries
 *  - coUpdatedSnapshotAndReadinessSurfaceIds contains contract-host-session-snapshot
 *  - coUpdatedSnapshotAndReadinessSurfaceIds contains contract-target-readiness-projection
 *  - CO_DERIVATION_INVARIANT is non-blank
 *  - CO_DERIVATION_INVARIANT mentions hostSessionSnapshot
 *  - CO_DERIVATION_INVARIANT mentions targetReadinessProjection
 *
 * ### CanonicalSessionAxis — SessionTruthBinding
 *  - truthBindings has 7 entries (one per CanonicalSessionFamily value)
 *  - all seven session families have a binding
 *  - RUNTIME_SESSION snapshotCarrier is non-null
 *  - ATTACHED_RUNTIME_SESSION snapshotCarrier is non-null
 *  - DURABLE_RUNTIME_SESSION snapshotCarrier is non-null
 *  - CONTROL_SESSION snapshotCarrier is null
 *  - DELEGATION_TRANSFER_SESSION snapshotCarrier is null
 *  - CONVERSATION_SESSION snapshotCarrier is null
 *  - MESH_SESSION snapshotCarrier is null
 *  - familiesWithSnapshotCarrier count is 3
 *  - familiesWithoutSnapshotCarrier count is 4
 *  - familiesWithSnapshotCarrier and familiesWithoutSnapshotCarrier are disjoint
 *  - their union equals all seven CanonicalSessionFamily values
 *  - all bindings have non-blank authoritativeSource and projectionNote
 *  - truthBindingFor returns null for unknown family (impossible but defensive)
 *
 * ### StabilizationBaseline — PR-39 entries
 *  - runtime-truth-precedence-rules entry is registered
 *  - host-facing-projection-contract entry is registered
 *  - canonical-session-axis-truth-bindings entry is registered
 *  - all three entries have CANONICAL_STABLE stability
 *  - all three entries have EXTEND guidance
 *  - all three entries have introducedPr 39
 */
class Pr39AndroidTruthSnapshotProjectionConvergenceTest {

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeTruthPrecedenceRules — TruthTier enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `AUTHORITATIVE wireValue is authoritative`() {
        assertEquals("authoritative", RuntimeTruthPrecedenceRules.TruthTier.AUTHORITATIVE.wireValue)
    }

    @Test fun `SNAPSHOT wireValue is snapshot`() {
        assertEquals("snapshot", RuntimeTruthPrecedenceRules.TruthTier.SNAPSHOT.wireValue)
    }

    @Test fun `PROJECTION wireValue is projection`() {
        assertEquals("projection", RuntimeTruthPrecedenceRules.TruthTier.PROJECTION.wireValue)
    }

    @Test fun `all three TruthTier wireValues are distinct`() {
        val wireValues = RuntimeTruthPrecedenceRules.TruthTier.entries.map { it.wireValue }
        assertEquals("TruthTier wireValues must all be unique", wireValues.distinct().size, wireValues.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeTruthPrecedenceRules — ProjectionStorage enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `REACTIVE wireValue is reactive`() {
        assertEquals("reactive", RuntimeTruthPrecedenceRules.ProjectionStorage.REACTIVE.wireValue)
    }

    @Test fun `POINT_IN_TIME wireValue is point_in_time`() {
        assertEquals("point_in_time", RuntimeTruthPrecedenceRules.ProjectionStorage.POINT_IN_TIME.wireValue)
    }

    @Test fun `ProjectionStorage wireValues are distinct`() {
        val wireValues = RuntimeTruthPrecedenceRules.ProjectionStorage.entries.map { it.wireValue }
        assertEquals("ProjectionStorage wireValues must all be unique", wireValues.distinct().size, wireValues.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeTruthPrecedenceRules — registry completeness
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `exactly five AUTHORITATIVE entries`() {
        assertEquals(5, RuntimeTruthPrecedenceRules.byTier(RuntimeTruthPrecedenceRules.TruthTier.AUTHORITATIVE).size)
    }

    @Test fun `exactly one SNAPSHOT entry`() {
        assertEquals(1, RuntimeTruthPrecedenceRules.byTier(RuntimeTruthPrecedenceRules.TruthTier.SNAPSHOT).size)
    }

    @Test fun `exactly three PROJECTION entries`() {
        assertEquals(3, RuntimeTruthPrecedenceRules.byTier(RuntimeTruthPrecedenceRules.TruthTier.PROJECTION).size)
    }

    @Test fun `nine total truth entries`() {
        assertEquals(9, RuntimeTruthPrecedenceRules.entries.size)
    }

    @Test fun `all truth entry surfaceIds are unique`() {
        val ids = RuntimeTruthPrecedenceRules.entries.map { it.surfaceId }
        assertEquals("Truth entry surfaceIds must be unique", ids.distinct().size, ids.size)
    }

    @Test fun `AUTHORITATIVE entries have null derivedFrom`() {
        val authEntries = RuntimeTruthPrecedenceRules.byTier(RuntimeTruthPrecedenceRules.TruthTier.AUTHORITATIVE)
        assertTrue("All AUTHORITATIVE entries must have null derivedFrom",
            authEntries.all { it.derivedFrom == null })
    }

    @Test fun `SNAPSHOT and PROJECTION entries have non-null derivedFrom`() {
        val derived = RuntimeTruthPrecedenceRules.entries.filter {
            it.tier != RuntimeTruthPrecedenceRules.TruthTier.AUTHORITATIVE
        }
        assertTrue("All non-AUTHORITATIVE entries must have non-null derivedFrom",
            derived.all { it.derivedFrom != null })
    }

    @Test fun `snapshot entry has REACTIVE storage`() {
        val snapshotEntries = RuntimeTruthPrecedenceRules.byTier(RuntimeTruthPrecedenceRules.TruthTier.SNAPSHOT)
        assertTrue("Snapshot entries must be REACTIVE",
            snapshotEntries.all { it.storage == RuntimeTruthPrecedenceRules.ProjectionStorage.REACTIVE })
    }

    @Test fun `all entries have non-blank surfaceId updatePath and rationale`() {
        for (entry in RuntimeTruthPrecedenceRules.entries) {
            assertTrue("surfaceId must be non-blank for ${entry.surfaceId}", entry.surfaceId.isNotBlank())
            assertTrue("updatePath must be non-blank for ${entry.surfaceId}", entry.updatePath.isNotBlank())
            assertTrue("rationale must be non-blank for ${entry.surfaceId}", entry.rationale.isNotBlank())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeTruthPrecedenceRules — query helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `authoritativeSurfaceIds has five elements`() {
        assertEquals(5, RuntimeTruthPrecedenceRules.authoritativeSurfaceIds.size)
    }

    @Test fun `snapshotSurfaceIds has one element`() {
        assertEquals(1, RuntimeTruthPrecedenceRules.snapshotSurfaceIds.size)
    }

    @Test fun `projectionSurfaceIds has three elements`() {
        assertEquals(3, RuntimeTruthPrecedenceRules.projectionSurfaceIds.size)
    }

    @Test fun `forId returns correct entry for auth-runtime-state`() {
        val entry = RuntimeTruthPrecedenceRules.forId("auth-runtime-state")
        assertNotNull(entry)
        assertEquals(RuntimeTruthPrecedenceRules.TruthTier.AUTHORITATIVE, entry!!.tier)
    }

    @Test fun `forId returns correct entry for snap-host-session-snapshot`() {
        val entry = RuntimeTruthPrecedenceRules.forId("snap-host-session-snapshot")
        assertNotNull(entry)
        assertEquals(RuntimeTruthPrecedenceRules.TruthTier.SNAPSHOT, entry!!.tier)
    }

    @Test fun `forId returns correct entry for proj-target-readiness`() {
        val entry = RuntimeTruthPrecedenceRules.forId("proj-target-readiness")
        assertNotNull(entry)
        assertEquals(RuntimeTruthPrecedenceRules.TruthTier.PROJECTION, entry!!.tier)
    }

    @Test fun `forId returns null for unknown surfaceId`() {
        assertNull(RuntimeTruthPrecedenceRules.forId("completely-unknown-surface-xyz"))
    }

    @Test fun `isRegistered returns true for known surfaceIds`() {
        assertTrue(RuntimeTruthPrecedenceRules.isRegistered("auth-runtime-state"))
        assertTrue(RuntimeTruthPrecedenceRules.isRegistered("snap-host-session-snapshot"))
        assertTrue(RuntimeTruthPrecedenceRules.isRegistered("proj-dispatch-readiness"))
    }

    @Test fun `isRegistered returns false for unknown surfaceId`() {
        assertFalse(RuntimeTruthPrecedenceRules.isRegistered("unknown-xyz"))
    }

    @Test fun `reactiveProjectionIds does not include point-in-time entries`() {
        val ptIds = RuntimeTruthPrecedenceRules.pointInTimeProjectionIds
        val reactiveIds = RuntimeTruthPrecedenceRules.reactiveProjectionIds
        assertTrue("reactive and point-in-time sets must be disjoint",
            (reactiveIds intersect ptIds).isEmpty())
    }

    @Test fun `proj-target-readiness is reactive`() {
        assertTrue(RuntimeTruthPrecedenceRules.reactiveProjectionIds.contains("proj-target-readiness"))
    }

    @Test fun `proj-dispatch-readiness is point-in-time`() {
        assertTrue(RuntimeTruthPrecedenceRules.pointInTimeProjectionIds.contains("proj-dispatch-readiness"))
    }

    @Test fun `proj-canonical-participant is point-in-time`() {
        assertTrue(RuntimeTruthPrecedenceRules.pointInTimeProjectionIds.contains("proj-canonical-participant"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeTruthPrecedenceRules — precedence rules and constants
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `precedenceRules has four entries`() {
        assertEquals(4, RuntimeTruthPrecedenceRules.precedenceRules.size)
    }

    @Test fun `all four precedence rules are non-blank`() {
        assertTrue("All precedence rules must be non-blank",
            RuntimeTruthPrecedenceRules.precedenceRules.all { it.isNotBlank() })
    }

    @Test fun `SNAPSHOT_UPDATE_GATE contains updateHostSessionSnapshot`() {
        assertTrue(RuntimeTruthPrecedenceRules.SNAPSHOT_UPDATE_GATE.contains("updateHostSessionSnapshot"))
    }

    @Test fun `STATE_TRANSITION_GATE contains transitionState`() {
        assertTrue(RuntimeTruthPrecedenceRules.STATE_TRANSITION_GATE.contains("transitionState"))
    }

    @Test fun `RuntimeTruthPrecedenceRules INTRODUCED_PR is 39`() {
        assertEquals(39, RuntimeTruthPrecedenceRules.INTRODUCED_PR)
    }

    @Test fun `RuntimeTruthPrecedenceRules DESCRIPTION is non-blank`() {
        assertTrue(RuntimeTruthPrecedenceRules.DESCRIPTION.isNotBlank())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HostFacingProjectionContract — ContractStability enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `ContractStability CANONICAL wireValue is canonical`() {
        assertEquals("canonical", HostFacingProjectionContract.ContractStability.CANONICAL.wireValue)
    }

    @Test fun `ContractStability TRANSITIONAL wireValue is transitional`() {
        assertEquals("transitional", HostFacingProjectionContract.ContractStability.TRANSITIONAL.wireValue)
    }

    @Test fun `ContractStability DEPRECATED wireValue is deprecated`() {
        assertEquals("deprecated", HostFacingProjectionContract.ContractStability.DEPRECATED.wireValue)
    }

    @Test fun `all three ContractStability wireValues are distinct`() {
        val wireValues = HostFacingProjectionContract.ContractStability.entries.map { it.wireValue }
        assertEquals("ContractStability wireValues must all be unique",
            wireValues.distinct().size, wireValues.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HostFacingProjectionContract — ProjectionKind enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `REACTIVE_FLOW wireValue is reactive_flow`() {
        assertEquals("reactive_flow", HostFacingProjectionContract.ProjectionKind.REACTIVE_FLOW.wireValue)
    }

    @Test fun `EVENT_STREAM wireValue is event_stream`() {
        assertEquals("event_stream", HostFacingProjectionContract.ProjectionKind.EVENT_STREAM.wireValue)
    }

    @Test fun `POINT_IN_TIME_QUERY wireValue is point_in_time_query`() {
        assertEquals("point_in_time_query", HostFacingProjectionContract.ProjectionKind.POINT_IN_TIME_QUERY.wireValue)
    }

    @Test fun `all three ProjectionKind wireValues are distinct`() {
        val wireValues = HostFacingProjectionContract.ProjectionKind.entries.map { it.wireValue }
        assertEquals("ProjectionKind wireValues must all be unique",
            wireValues.distinct().size, wireValues.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HostFacingProjectionContract — registry completeness
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `exactly eight CANONICAL contract entries`() {
        assertEquals(8, HostFacingProjectionContract.byStability(
            HostFacingProjectionContract.ContractStability.CANONICAL).size)
    }

    @Test fun `exactly zero TRANSITIONAL contract entries`() {
        assertEquals(0, HostFacingProjectionContract.byStability(
            HostFacingProjectionContract.ContractStability.TRANSITIONAL).size)
    }

    @Test fun `exactly one DEPRECATED contract entry`() {
        assertEquals(1, HostFacingProjectionContract.byStability(
            HostFacingProjectionContract.ContractStability.DEPRECATED).size)
    }

    @Test fun `nine total contract entries`() {
        assertEquals(9, HostFacingProjectionContract.entries.size)
    }

    @Test fun `all contract entry surfaceIds are unique`() {
        val ids = HostFacingProjectionContract.entries.map { it.surfaceId }
        assertEquals("Contract entry surfaceIds must be unique", ids.distinct().size, ids.size)
    }

    @Test fun `canonical entries have null canonicalReplacement`() {
        val canonicalEntries = HostFacingProjectionContract.byStability(
            HostFacingProjectionContract.ContractStability.CANONICAL)
        assertTrue("All CANONICAL entries must have null canonicalReplacement",
            canonicalEntries.all { it.canonicalReplacement == null })
    }

    @Test fun `deprecated entry has non-null canonicalReplacement`() {
        val deprecatedEntries = HostFacingProjectionContract.byStability(
            HostFacingProjectionContract.ContractStability.DEPRECATED)
        assertTrue("All DEPRECATED entries must have non-null canonicalReplacement",
            deprecatedEntries.all { it.canonicalReplacement != null })
    }

    @Test fun `all contract entries have non-blank required fields`() {
        for (entry in HostFacingProjectionContract.entries) {
            assertTrue("displayName must be non-blank for ${entry.surfaceId}",
                entry.displayName.isNotBlank())
            assertTrue("kotlinSymbol must be non-blank for ${entry.surfaceId}",
                entry.kotlinSymbol.isNotBlank())
            assertTrue("projectedType must be non-blank for ${entry.surfaceId}",
                entry.projectedType.isNotBlank())
            assertTrue("consumptionGuidance must be non-blank for ${entry.surfaceId}",
                entry.consumptionGuidance.isNotBlank())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HostFacingProjectionContract — query helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `byStability CANONICAL count is 8`() {
        assertEquals(8, HostFacingProjectionContract.byStability(
            HostFacingProjectionContract.ContractStability.CANONICAL).size)
    }

    @Test fun `byStability DEPRECATED count is 1`() {
        assertEquals(1, HostFacingProjectionContract.byStability(
            HostFacingProjectionContract.ContractStability.DEPRECATED).size)
    }

    @Test fun `byKind REACTIVE_FLOW count is 5`() {
        assertEquals(5, HostFacingProjectionContract.byKind(
            HostFacingProjectionContract.ProjectionKind.REACTIVE_FLOW).size)
    }

    @Test fun `byKind EVENT_STREAM count is 1`() {
        assertEquals(1, HostFacingProjectionContract.byKind(
            HostFacingProjectionContract.ProjectionKind.EVENT_STREAM).size)
    }

    @Test fun `byKind POINT_IN_TIME_QUERY count is 3`() {
        assertEquals(3, HostFacingProjectionContract.byKind(
            HostFacingProjectionContract.ProjectionKind.POINT_IN_TIME_QUERY).size)
    }

    @Test fun `forId returns correct entry for contract-host-session-snapshot`() {
        val entry = HostFacingProjectionContract.forId("contract-host-session-snapshot")
        assertNotNull(entry)
        assertEquals(HostFacingProjectionContract.ContractStability.CANONICAL, entry!!.stability)
    }

    @Test fun `forId returns correct entry for contract-legacy-session-snapshot-map`() {
        val entry = HostFacingProjectionContract.forId("contract-legacy-session-snapshot-map")
        assertNotNull(entry)
        assertEquals(HostFacingProjectionContract.ContractStability.DEPRECATED, entry!!.stability)
    }

    @Test fun `forId returns null for unknown surfaceId`() {
        assertNull(HostFacingProjectionContract.forId("completely-unknown-contract-surface-xyz"))
    }

    @Test fun `isCanonical returns true for canonical surface`() {
        assertTrue(HostFacingProjectionContract.isCanonical("contract-host-session-snapshot"))
    }

    @Test fun `isCanonical returns false for deprecated surface`() {
        assertFalse(HostFacingProjectionContract.isCanonical("contract-legacy-session-snapshot-map"))
    }

    @Test fun `isCanonical returns false for unknown surface`() {
        assertFalse(HostFacingProjectionContract.isCanonical("unknown-surface-xyz"))
    }

    @Test fun `isDeprecated returns true for deprecated surface`() {
        assertTrue(HostFacingProjectionContract.isDeprecated("contract-legacy-session-snapshot-map"))
    }

    @Test fun `isDeprecated returns false for canonical surface`() {
        assertFalse(HostFacingProjectionContract.isDeprecated("contract-host-session-snapshot"))
    }

    @Test fun `canonicalSurfaceIds has 8 elements`() {
        assertEquals(8, HostFacingProjectionContract.canonicalSurfaceIds.size)
    }

    @Test fun `deprecatedSurfaceIds has 1 element`() {
        assertEquals(1, HostFacingProjectionContract.deprecatedSurfaceIds.size)
    }

    @Test fun `reactiveContractIds includes REACTIVE_FLOW surfaces`() {
        val reactiveFlow = HostFacingProjectionContract.byKind(
            HostFacingProjectionContract.ProjectionKind.REACTIVE_FLOW)
        for (entry in reactiveFlow) {
            assertTrue("${entry.surfaceId} must be in reactiveContractIds",
                HostFacingProjectionContract.reactiveContractIds.contains(entry.surfaceId))
        }
    }

    @Test fun `reactiveContractIds includes EVENT_STREAM surfaces`() {
        val eventStream = HostFacingProjectionContract.byKind(
            HostFacingProjectionContract.ProjectionKind.EVENT_STREAM)
        for (entry in eventStream) {
            assertTrue("${entry.surfaceId} must be in reactiveContractIds",
                HostFacingProjectionContract.reactiveContractIds.contains(entry.surfaceId))
        }
    }

    @Test fun `reactiveContractIds does not include POINT_IN_TIME_QUERY surfaces`() {
        val pointInTime = HostFacingProjectionContract.byKind(
            HostFacingProjectionContract.ProjectionKind.POINT_IN_TIME_QUERY)
        for (entry in pointInTime) {
            assertFalse("${entry.surfaceId} must NOT be in reactiveContractIds",
                HostFacingProjectionContract.reactiveContractIds.contains(entry.surfaceId))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HostFacingProjectionContract — co-derivation invariant
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `coUpdatedSnapshotAndReadinessSurfaceIds has exactly 2 entries`() {
        assertEquals(2, HostFacingProjectionContract.coUpdatedSnapshotAndReadinessSurfaceIds.size)
    }

    @Test fun `coUpdatedSnapshotAndReadinessSurfaceIds contains contract-host-session-snapshot`() {
        assertTrue(HostFacingProjectionContract.coUpdatedSnapshotAndReadinessSurfaceIds
            .contains("contract-host-session-snapshot"))
    }

    @Test fun `coUpdatedSnapshotAndReadinessSurfaceIds contains contract-target-readiness-projection`() {
        assertTrue(HostFacingProjectionContract.coUpdatedSnapshotAndReadinessSurfaceIds
            .contains("contract-target-readiness-projection"))
    }

    @Test fun `CO_DERIVATION_INVARIANT is non-blank`() {
        assertTrue(HostFacingProjectionContract.CO_DERIVATION_INVARIANT.isNotBlank())
    }

    @Test fun `CO_DERIVATION_INVARIANT mentions hostSessionSnapshot`() {
        assertTrue(HostFacingProjectionContract.CO_DERIVATION_INVARIANT.contains("hostSessionSnapshot"))
    }

    @Test fun `CO_DERIVATION_INVARIANT mentions targetReadinessProjection`() {
        assertTrue(HostFacingProjectionContract.CO_DERIVATION_INVARIANT.contains("targetReadinessProjection"))
    }

    @Test fun `HostFacingProjectionContract INTRODUCED_PR is 39`() {
        assertEquals(39, HostFacingProjectionContract.INTRODUCED_PR)
    }

    @Test fun `HostFacingProjectionContract DESCRIPTION is non-blank`() {
        assertTrue(HostFacingProjectionContract.DESCRIPTION.isNotBlank())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CanonicalSessionAxis — SessionTruthBinding (PR-39 additions)
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `truthBindings has 7 entries one per session family`() {
        assertEquals(
            "truthBindings must have one entry per CanonicalSessionFamily",
            CanonicalSessionFamily.entries.size,
            CanonicalSessionAxis.truthBindings.size
        )
    }

    @Test fun `all seven session families have a truth binding`() {
        for (family in CanonicalSessionFamily.entries) {
            assertNotNull(
                "Missing truthBinding for family ${family.name}",
                CanonicalSessionAxis.truthBindingFor(family)
            )
        }
    }

    @Test fun `RUNTIME_SESSION binding has non-null snapshotCarrier`() {
        val binding = CanonicalSessionAxis.truthBindingFor(CanonicalSessionFamily.RUNTIME_SESSION)
        assertNotNull(binding)
        assertNotNull("RUNTIME_SESSION must have a snapshot carrier", binding!!.snapshotCarrier)
    }

    @Test fun `ATTACHED_RUNTIME_SESSION binding has non-null snapshotCarrier`() {
        val binding = CanonicalSessionAxis.truthBindingFor(CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION)
        assertNotNull(binding)
        assertNotNull("ATTACHED_RUNTIME_SESSION must have a snapshot carrier", binding!!.snapshotCarrier)
    }

    @Test fun `DURABLE_RUNTIME_SESSION binding has non-null snapshotCarrier`() {
        val binding = CanonicalSessionAxis.truthBindingFor(CanonicalSessionFamily.DURABLE_RUNTIME_SESSION)
        assertNotNull(binding)
        assertNotNull("DURABLE_RUNTIME_SESSION must have a snapshot carrier", binding!!.snapshotCarrier)
    }

    @Test fun `CONTROL_SESSION binding has null snapshotCarrier`() {
        val binding = CanonicalSessionAxis.truthBindingFor(CanonicalSessionFamily.CONTROL_SESSION)
        assertNotNull(binding)
        assertNull("CONTROL_SESSION must have null snapshotCarrier", binding!!.snapshotCarrier)
    }

    @Test fun `DELEGATION_TRANSFER_SESSION binding has null snapshotCarrier`() {
        val binding = CanonicalSessionAxis.truthBindingFor(CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION)
        assertNotNull(binding)
        assertNull("DELEGATION_TRANSFER_SESSION must have null snapshotCarrier",
            binding!!.snapshotCarrier)
    }

    @Test fun `CONVERSATION_SESSION binding has null snapshotCarrier`() {
        val binding = CanonicalSessionAxis.truthBindingFor(CanonicalSessionFamily.CONVERSATION_SESSION)
        assertNotNull(binding)
        assertNull("CONVERSATION_SESSION must have null snapshotCarrier", binding!!.snapshotCarrier)
    }

    @Test fun `MESH_SESSION binding has null snapshotCarrier`() {
        val binding = CanonicalSessionAxis.truthBindingFor(CanonicalSessionFamily.MESH_SESSION)
        assertNotNull(binding)
        assertNull("MESH_SESSION must have null snapshotCarrier", binding!!.snapshotCarrier)
    }

    @Test fun `familiesWithSnapshotCarrier count is 3`() {
        assertEquals(
            "Exactly three session families have snapshot carriers",
            3,
            CanonicalSessionAxis.familiesWithSnapshotCarrier.size
        )
    }

    @Test fun `familiesWithoutSnapshotCarrier count is 4`() {
        assertEquals(
            "Exactly four session families have no snapshot carrier",
            4,
            CanonicalSessionAxis.familiesWithoutSnapshotCarrier.size
        )
    }

    @Test fun `familiesWithSnapshotCarrier and familiesWithoutSnapshotCarrier are disjoint`() {
        val intersection = CanonicalSessionAxis.familiesWithSnapshotCarrier intersect
            CanonicalSessionAxis.familiesWithoutSnapshotCarrier
        assertTrue(
            "familiesWithSnapshotCarrier and familiesWithoutSnapshotCarrier must be disjoint",
            intersection.isEmpty()
        )
    }

    @Test fun `familiesWithSnapshotCarrier and familiesWithoutSnapshotCarrier union covers all families`() {
        val union = CanonicalSessionAxis.familiesWithSnapshotCarrier union
            CanonicalSessionAxis.familiesWithoutSnapshotCarrier
        assertEquals(
            "The union must equal all CanonicalSessionFamily values",
            CanonicalSessionFamily.entries.toSet(),
            union
        )
    }

    @Test fun `familiesWithSnapshotCarrier contains RUNTIME_SESSION`() {
        assertTrue(CanonicalSessionAxis.familiesWithSnapshotCarrier.contains(
            CanonicalSessionFamily.RUNTIME_SESSION))
    }

    @Test fun `familiesWithSnapshotCarrier contains ATTACHED_RUNTIME_SESSION`() {
        assertTrue(CanonicalSessionAxis.familiesWithSnapshotCarrier.contains(
            CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION))
    }

    @Test fun `familiesWithSnapshotCarrier contains DURABLE_RUNTIME_SESSION`() {
        assertTrue(CanonicalSessionAxis.familiesWithSnapshotCarrier.contains(
            CanonicalSessionFamily.DURABLE_RUNTIME_SESSION))
    }

    @Test fun `all truth bindings have non-blank authoritativeSource and projectionNote`() {
        for (binding in CanonicalSessionAxis.truthBindings) {
            assertTrue("authoritativeSource must be non-blank for ${binding.family.name}",
                binding.authoritativeSource.isNotBlank())
            assertTrue("projectionNote must be non-blank for ${binding.family.name}",
                binding.projectionNote.isNotBlank())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // StabilizationBaseline — PR-39 entries
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `runtime-truth-precedence-rules entry is registered in baseline`() {
        val entry = StabilizationBaseline.forId("runtime-truth-precedence-rules")
        assertNotNull("runtime-truth-precedence-rules must be registered in StabilizationBaseline", entry)
    }

    @Test fun `runtime-truth-precedence-rules entry is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("runtime-truth-precedence-rules")
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry?.stability)
    }

    @Test fun `runtime-truth-precedence-rules entry has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("runtime-truth-precedence-rules")
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry?.extensionGuidance)
    }

    @Test fun `runtime-truth-precedence-rules entry has introducedPr 39`() {
        val entry = StabilizationBaseline.forId("runtime-truth-precedence-rules")
        assertEquals(39, entry?.introducedPr)
    }

    @Test fun `host-facing-projection-contract entry is registered in baseline`() {
        val entry = StabilizationBaseline.forId("host-facing-projection-contract")
        assertNotNull("host-facing-projection-contract must be registered in StabilizationBaseline", entry)
    }

    @Test fun `host-facing-projection-contract entry is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("host-facing-projection-contract")
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry?.stability)
    }

    @Test fun `host-facing-projection-contract entry has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("host-facing-projection-contract")
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry?.extensionGuidance)
    }

    @Test fun `host-facing-projection-contract entry has introducedPr 39`() {
        val entry = StabilizationBaseline.forId("host-facing-projection-contract")
        assertEquals(39, entry?.introducedPr)
    }

    @Test fun `canonical-session-axis-truth-bindings entry is registered in baseline`() {
        val entry = StabilizationBaseline.forId("canonical-session-axis-truth-bindings")
        assertNotNull("canonical-session-axis-truth-bindings must be registered in StabilizationBaseline", entry)
    }

    @Test fun `canonical-session-axis-truth-bindings entry is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("canonical-session-axis-truth-bindings")
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry?.stability)
    }

    @Test fun `canonical-session-axis-truth-bindings entry has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("canonical-session-axis-truth-bindings")
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry?.extensionGuidance)
    }

    @Test fun `canonical-session-axis-truth-bindings entry has introducedPr 39`() {
        val entry = StabilizationBaseline.forId("canonical-session-axis-truth-bindings")
        assertEquals(39, entry?.introducedPr)
    }

    @Test fun `all three PR-39 baseline entries have introducedPr 39`() {
        val pr39Ids = listOf(
            "runtime-truth-precedence-rules",
            "host-facing-projection-contract",
            "canonical-session-axis-truth-bindings"
        )
        for (id in pr39Ids) {
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Entry $id must exist", entry)
            assertEquals("Entry $id must have introducedPr=39", 39, entry!!.introducedPr)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cross-object consistency: truth tier surfaceIds match host-facing contract
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `snapshot truth tier surfaceId corresponds to canonical contract surface`() {
        // The Tier-2 snapshot surface (snap-host-session-snapshot) must align with the
        // canonical contract for hostSessionSnapshot (contract-host-session-snapshot).
        val snapshotEntry = RuntimeTruthPrecedenceRules.forId("snap-host-session-snapshot")
        assertNotNull("snap-host-session-snapshot must be registered in TruthPrecedenceRules",
            snapshotEntry)
        val contractEntry = HostFacingProjectionContract.forId("contract-host-session-snapshot")
        assertNotNull("contract-host-session-snapshot must be registered in HostFacingProjectionContract",
            contractEntry)
        // Both surfaces are REACTIVE / REACTIVE_FLOW
        assertEquals("Snapshot truth entry must be REACTIVE",
            RuntimeTruthPrecedenceRules.ProjectionStorage.REACTIVE, snapshotEntry!!.storage)
        assertEquals("Contract host session snapshot must be REACTIVE_FLOW",
            HostFacingProjectionContract.ProjectionKind.REACTIVE_FLOW, contractEntry!!.kind)
    }

    @Test fun `readiness projection truth tier surfaceId corresponds to canonical contract surface`() {
        val truthEntry = RuntimeTruthPrecedenceRules.forId("proj-target-readiness")
        assertNotNull("proj-target-readiness must be registered in TruthPrecedenceRules", truthEntry)
        val contractEntry = HostFacingProjectionContract.forId("contract-target-readiness-projection")
        assertNotNull("contract-target-readiness-projection must be registered in " +
            "HostFacingProjectionContract", contractEntry)
        assertEquals("Readiness projection must be REACTIVE",
            RuntimeTruthPrecedenceRules.ProjectionStorage.REACTIVE, truthEntry!!.storage)
        assertEquals("Contract readiness projection must be REACTIVE_FLOW",
            HostFacingProjectionContract.ProjectionKind.REACTIVE_FLOW, contractEntry!!.kind)
    }

    @Test fun `all baseline PR-39 entries are in canonicalSurfaceIds`() {
        val pr39Ids = listOf(
            "runtime-truth-precedence-rules",
            "host-facing-projection-contract",
            "canonical-session-axis-truth-bindings"
        )
        for (id in pr39Ids) {
            assertTrue("$id must be in StabilizationBaseline.canonicalSurfaceIds",
                StabilizationBaseline.canonicalSurfaceIds.contains(id))
        }
    }
}
