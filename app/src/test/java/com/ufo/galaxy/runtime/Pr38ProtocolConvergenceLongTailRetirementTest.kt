package com.ufo.galaxy.runtime

import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-38 — Android Protocol Convergence: Promote or Retire Long-tail Surfaces.
 *
 * Focused test suite validating all PR-38 additions:
 *
 *  1. [ProtocolConvergenceBoundary] — complete convergence authority registry.
 *     - All canonical surfaces are registered with [ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CANONICAL].
 *     - All converging surfaces are registered with [ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CONVERGING].
 *     - All isolated surfaces are registered with [ProtocolConvergenceBoundary.SurfaceConvergenceStatus.ISOLATED].
 *     - All retired surfaces are registered with [ProtocolConvergenceBoundary.SurfaceConvergenceStatus.RETIRED].
 *     - [ProtocolConvergenceBoundary.isCanonical] and [ProtocolConvergenceBoundary.isIsolated] helpers.
 *     - [ProtocolConvergenceBoundary.pendingConvergenceSurfaces] covers CONVERGING + ISOLATED only.
 *     - Query helpers return expected subsets.
 *
 *  2. [LongTailCompatibilityRegistry.ConvergenceAction] — PR-38 addition.
 *     - New [LongTailCompatibilityRegistry.ConvergenceAction] enum has three values.
 *     - [LongTailCompatibilityRegistry.LongTailEntry.convergenceAction] defaults match tier.
 *     - All TRANSITIONAL entries have [LongTailCompatibilityRegistry.ConvergenceAction.ISOLATE_BEHIND_BOUNDARY].
 *     - All PROMOTED entries have [LongTailCompatibilityRegistry.ConvergenceAction.PROMOTE_TO_CANONICAL].
 *     - All CANONICAL entries have [LongTailCompatibilityRegistry.ConvergenceAction.NO_ACTION].
 *     - [LongTailCompatibilityRegistry.isolatedTypes] equals transitionalTypes.
 *     - [LongTailCompatibilityRegistry.convergingTypes] equals promotedTypes.
 *     - [LongTailCompatibilityRegistry.byConvergenceAction] returns correct subsets.
 *
 *  3. [GalaxyLogger.TAG_PROTOCOL_CONVERGENCE] — new stable structured log tag.
 *     - TAG_PROTOCOL_CONVERGENCE is non-blank.
 *     - TAG_PROTOCOL_CONVERGENCE contains "PROTOCOL".
 *     - TAG_PROTOCOL_CONVERGENCE contains "CONVERGENCE".
 *     - TAG_PROTOCOL_CONVERGENCE is distinct from all prior tags.
 *
 *  4. [StabilizationBaseline] — PR-38 entries registered.
 *     - protocol-convergence-boundary is registered as CANONICAL_STABLE.
 *     - long-tail-compatibility-registry-convergence-actions is registered.
 *     - galaxy-logger-tag-protocol-convergence is registered.
 *
 * ## Test matrix
 *
 * ### ProtocolConvergenceBoundary — convergence status enum
 *  - CANONICAL wireValue is "canonical"
 *  - CONVERGING wireValue is "converging"
 *  - ISOLATED wireValue is "isolated"
 *  - RETIRED wireValue is "retired"
 *  - all four wireValues are distinct
 *
 * ### ProtocolConvergenceBoundary — convergence action enum
 *  - NO_ACTION wireValue is "no_action"
 *  - PROMOTE_TO_CANONICAL wireValue is "promote_to_canonical"
 *  - ISOLATE_BEHIND_BOUNDARY wireValue is "isolate_behind_boundary"
 *  - RETIRE_AFTER_GATE wireValue is "retire_after_gate"
 *  - all four wireValues are distinct
 *
 * ### ProtocolConvergenceBoundary — canonical surfaces
 *  - InputRouter is registered as CANONICAL
 *  - GalaxyWebSocketClient outbound uplink is registered as CANONICAL
 *  - GalaxyConnectionService inbound dispatch is registered as CANONICAL
 *  - CanonicalDispatchChain path model is registered as CANONICAL
 *  - RuntimeController lifecycle authority is registered as CANONICAL
 *  - RuntimeDispatchReadinessCoordinator is registered as CANONICAL
 *  - canonical surfaces all have NO_ACTION convergence action
 *  - canonical surfaces all have null canonicalPath
 *  - isCanonical returns true for canonical surface IDs
 *  - isCanonical returns false for non-canonical surface IDs
 *  - isCanonical returns false for unregistered surface IDs
 *
 * ### ProtocolConvergenceBoundary — converging surfaces
 *  - PEER_EXCHANGE converging entry is registered
 *  - MESH_TOPOLOGY converging entry is registered
 *  - COORD_SYNC converging entry is registered
 *  - PEER_ANNOUNCE converging entry is registered
 *  - session identifier carrier transitional entry is registered as CONVERGING
 *  - staged mesh execution status transitional entry is registered as CONVERGING
 *  - legacy session snapshot map entry is registered as CONVERGING
 *  - all converging surfaces with PROMOTE_TO_CANONICAL have non-null canonicalPath
 *  - converging surfaces with RETIRE_AFTER_GATE have non-null convergenceGate
 *
 * ### ProtocolConvergenceBoundary — isolated surfaces
 *  - RELAY is registered as ISOLATED
 *  - FORWARD is registered as ISOLATED
 *  - REPLY is registered as ISOLATED
 *  - RAG_QUERY is registered as ISOLATED
 *  - CODE_EXECUTE is registered as ISOLATED
 *  - WAKE_EVENT is registered as ISOLATED
 *  - SESSION_MIGRATE is registered as ISOLATED
 *  - BROADCAST is registered as ISOLATED
 *  - LOCK/UNLOCK is registered as ISOLATED
 *  - all isolated surfaces have ISOLATE_BEHIND_BOUNDARY action
 *  - all isolated surfaces have non-null convergenceGate
 *  - isIsolated returns true for isolated surface IDs
 *  - isIsolated returns false for canonical surface IDs
 *
 * ### ProtocolConvergenceBoundary — retired surfaces
 *  - registrationError deprecated bridge is registered as RETIRED
 *  - retired surfaces have RETIRE_AFTER_GATE action
 *  - retired surfaces have non-null canonicalPath
 *
 * ### ProtocolConvergenceBoundary — pendingConvergenceSurfaces
 *  - pendingConvergenceSurfaces includes CONVERGING entries
 *  - pendingConvergenceSurfaces includes ISOLATED entries
 *  - pendingConvergenceSurfaces does NOT include CANONICAL entries
 *  - pendingConvergenceSurfaces does NOT include RETIRED entries
 *
 * ### ProtocolConvergenceBoundary — forId / byStatus helpers
 *  - forId returns the correct entry
 *  - forId returns null for unknown surface IDs
 *  - byStatus returns only entries with the given status
 *
 * ### LongTailCompatibilityRegistry.ConvergenceAction — enum values
 *  - NO_ACTION wireValue is "no_action"
 *  - PROMOTE_TO_CANONICAL wireValue is "promote_to_canonical"
 *  - ISOLATE_BEHIND_BOUNDARY wireValue is "isolate_behind_boundary"
 *  - three values total
 *
 * ### LongTailCompatibilityRegistry — convergenceAction per entry
 *  - TRANSITIONAL entries have ISOLATE_BEHIND_BOUNDARY
 *  - PROMOTED entries have PROMOTE_TO_CANONICAL
 *  - CANONICAL entries have NO_ACTION
 *
 * ### LongTailCompatibilityRegistry — isolatedTypes
 *  - isolatedTypes equals transitionalTypes
 *  - isolatedTypes count matches transitionalTypes count (10 after PR-36)
 *
 * ### LongTailCompatibilityRegistry — convergingTypes
 *  - convergingTypes equals promotedTypes
 *  - convergingTypes count matches promotedTypes count (4 after PR-36)
 *
 * ### LongTailCompatibilityRegistry — byConvergenceAction
 *  - byConvergenceAction(NO_ACTION) count is 2
 *  - byConvergenceAction(PROMOTE_TO_CANONICAL) count is 4
 *  - byConvergenceAction(ISOLATE_BEHIND_BOUNDARY) count is 10
 *
 * ### GalaxyLogger.TAG_PROTOCOL_CONVERGENCE — stability
 *  - TAG_PROTOCOL_CONVERGENCE is non-blank
 *  - TAG_PROTOCOL_CONVERGENCE contains "PROTOCOL"
 *  - TAG_PROTOCOL_CONVERGENCE contains "CONVERGENCE"
 *  - TAG_PROTOCOL_CONVERGENCE is distinct from TAG_LONG_TAIL_COMPAT
 *  - TAG_PROTOCOL_CONVERGENCE is distinct from TAG_RUNTIME_LIFECYCLE
 *  - TAG_PROTOCOL_CONVERGENCE is distinct from TAG_COMPAT_SURFACE
 *
 * ### StabilizationBaseline — PR-38 entries
 *  - protocol-convergence-boundary entry is registered as CANONICAL_STABLE
 *  - long-tail-compatibility-registry-convergence-actions entry is registered
 *  - galaxy-logger-tag-protocol-convergence entry is registered as CANONICAL_STABLE
 *  - all PR-38 entries have introducedPr = 38
 */
class Pr38ProtocolConvergenceLongTailRetirementTest {

    // ─────────────────────────────────────────────────────────────────────────
    // ProtocolConvergenceBoundary — convergence status enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `CANONICAL wireValue is canonical`() {
        assertEquals("canonical", ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CANONICAL.wireValue)
    }

    @Test fun `CONVERGING wireValue is converging`() {
        assertEquals("converging", ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CONVERGING.wireValue)
    }

    @Test fun `ISOLATED wireValue is isolated`() {
        assertEquals("isolated", ProtocolConvergenceBoundary.SurfaceConvergenceStatus.ISOLATED.wireValue)
    }

    @Test fun `RETIRED wireValue is retired`() {
        assertEquals("retired", ProtocolConvergenceBoundary.SurfaceConvergenceStatus.RETIRED.wireValue)
    }

    @Test fun `all four SurfaceConvergenceStatus wireValues are distinct`() {
        val wireValues = ProtocolConvergenceBoundary.SurfaceConvergenceStatus.entries.map { it.wireValue }
        assertEquals(wireValues.distinct().size, wireValues.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ProtocolConvergenceBoundary — convergence action enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `NO_ACTION wireValue is no_action`() {
        assertEquals("no_action", ProtocolConvergenceBoundary.ConvergenceAction.NO_ACTION.wireValue)
    }

    @Test fun `PROMOTE_TO_CANONICAL wireValue is promote_to_canonical`() {
        assertEquals("promote_to_canonical", ProtocolConvergenceBoundary.ConvergenceAction.PROMOTE_TO_CANONICAL.wireValue)
    }

    @Test fun `ISOLATE_BEHIND_BOUNDARY wireValue is isolate_behind_boundary`() {
        assertEquals("isolate_behind_boundary", ProtocolConvergenceBoundary.ConvergenceAction.ISOLATE_BEHIND_BOUNDARY.wireValue)
    }

    @Test fun `RETIRE_AFTER_GATE wireValue is retire_after_gate`() {
        assertEquals("retire_after_gate", ProtocolConvergenceBoundary.ConvergenceAction.RETIRE_AFTER_GATE.wireValue)
    }

    @Test fun `all four ConvergenceAction wireValues are distinct`() {
        val wireValues = ProtocolConvergenceBoundary.ConvergenceAction.entries.map { it.wireValue }
        assertEquals(wireValues.distinct().size, wireValues.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ProtocolConvergenceBoundary — canonical surfaces
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `InputRouter is registered as CANONICAL`() {
        val entry = ProtocolConvergenceBoundary.forId("input-router-dispatch-gate")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CANONICAL, entry!!.status)
    }

    @Test fun `GalaxyWebSocketClient outbound uplink is registered as CANONICAL`() {
        val entry = ProtocolConvergenceBoundary.forId("galaxy-websocket-client-outbound-uplink")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CANONICAL, entry!!.status)
    }

    @Test fun `GalaxyConnectionService inbound dispatch is registered as CANONICAL`() {
        val entry = ProtocolConvergenceBoundary.forId("galaxy-connection-service-inbound-dispatch")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CANONICAL, entry!!.status)
    }

    @Test fun `CanonicalDispatchChain path model is registered as CANONICAL`() {
        val entry = ProtocolConvergenceBoundary.forId("canonical-dispatch-chain-path-model")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CANONICAL, entry!!.status)
    }

    @Test fun `RuntimeController lifecycle authority is registered as CANONICAL`() {
        val entry = ProtocolConvergenceBoundary.forId("runtime-controller-lifecycle-authority")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CANONICAL, entry!!.status)
    }

    @Test fun `RuntimeDispatchReadinessCoordinator is registered as CANONICAL`() {
        val entry = ProtocolConvergenceBoundary.forId("runtime-dispatch-readiness-coordinator-eligibility")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CANONICAL, entry!!.status)
    }

    @Test fun `canonical surfaces all have NO_ACTION convergence action`() {
        ProtocolConvergenceBoundary.canonicalSurfaces.forEach { entry ->
            assertEquals(
                "Expected NO_ACTION for canonical surface ${entry.surfaceId}",
                ProtocolConvergenceBoundary.ConvergenceAction.NO_ACTION,
                entry.action
            )
        }
    }

    @Test fun `canonical surfaces all have null canonicalPath`() {
        ProtocolConvergenceBoundary.canonicalSurfaces.forEach { entry ->
            assertNull(
                "Expected null canonicalPath for canonical surface ${entry.surfaceId}",
                entry.canonicalPath
            )
        }
    }

    @Test fun `isCanonical returns true for canonical surface IDs`() {
        assertTrue(ProtocolConvergenceBoundary.isCanonical("input-router-dispatch-gate"))
        assertTrue(ProtocolConvergenceBoundary.isCanonical("canonical-dispatch-chain-path-model"))
        assertTrue(ProtocolConvergenceBoundary.isCanonical("runtime-controller-lifecycle-authority"))
    }

    @Test fun `isCanonical returns false for non-canonical surface IDs`() {
        assertFalse(ProtocolConvergenceBoundary.isCanonical("long-tail-relay-isolated"))
        assertFalse(ProtocolConvergenceBoundary.isCanonical("long-tail-peer-exchange-converging"))
        assertFalse(ProtocolConvergenceBoundary.isCanonical("registration-error-string-bridge-retired"))
    }

    @Test fun `isCanonical returns false for unregistered surface IDs`() {
        assertFalse(ProtocolConvergenceBoundary.isCanonical("not-a-real-surface-id"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ProtocolConvergenceBoundary — converging surfaces
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `PEER_EXCHANGE converging entry is registered`() {
        val entry = ProtocolConvergenceBoundary.forId("long-tail-peer-exchange-converging")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CONVERGING, entry!!.status)
    }

    @Test fun `MESH_TOPOLOGY converging entry is registered`() {
        val entry = ProtocolConvergenceBoundary.forId("long-tail-mesh-topology-converging")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CONVERGING, entry!!.status)
    }

    @Test fun `COORD_SYNC converging entry is registered`() {
        val entry = ProtocolConvergenceBoundary.forId("long-tail-coord-sync-converging")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CONVERGING, entry!!.status)
    }

    @Test fun `PEER_ANNOUNCE converging entry is registered`() {
        val entry = ProtocolConvergenceBoundary.forId("long-tail-peer-announce-converging")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CONVERGING, entry!!.status)
    }

    @Test fun `session identifier carrier transitional entry is registered as CONVERGING`() {
        val entry = ProtocolConvergenceBoundary.forId("protocol-surface-session-identifier-carrier-transitional")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CONVERGING, entry!!.status)
    }

    @Test fun `staged mesh execution status transitional entry is registered as CONVERGING`() {
        val entry = ProtocolConvergenceBoundary.forId("protocol-surface-staged-mesh-execution-status-transitional")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CONVERGING, entry!!.status)
    }

    @Test fun `legacy session snapshot map entry is registered as CONVERGING`() {
        val entry = ProtocolConvergenceBoundary.forId("legacy-session-snapshot-map-converging")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CONVERGING, entry!!.status)
    }

    @Test fun `converging surfaces with PROMOTE_TO_CANONICAL have non-null canonicalPath`() {
        ProtocolConvergenceBoundary.convergingSurfaces
            .filter { it.action == ProtocolConvergenceBoundary.ConvergenceAction.PROMOTE_TO_CANONICAL }
            .forEach { entry ->
                assertNotNull(
                    "Expected non-null canonicalPath for converging surface ${entry.surfaceId}",
                    entry.canonicalPath
                )
            }
    }

    @Test fun `converging surfaces with RETIRE_AFTER_GATE have non-null convergenceGate`() {
        ProtocolConvergenceBoundary.convergingSurfaces
            .filter { it.action == ProtocolConvergenceBoundary.ConvergenceAction.RETIRE_AFTER_GATE }
            .forEach { entry ->
                assertNotNull(
                    "Expected non-null convergenceGate for retiring surface ${entry.surfaceId}",
                    entry.convergenceGate
                )
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ProtocolConvergenceBoundary — isolated surfaces
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `RELAY is registered as ISOLATED`() {
        val entry = ProtocolConvergenceBoundary.forId("long-tail-relay-isolated")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.ISOLATED, entry!!.status)
    }

    @Test fun `FORWARD is registered as ISOLATED`() {
        val entry = ProtocolConvergenceBoundary.forId("long-tail-forward-isolated")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.ISOLATED, entry!!.status)
    }

    @Test fun `REPLY is registered as ISOLATED`() {
        val entry = ProtocolConvergenceBoundary.forId("long-tail-reply-isolated")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.ISOLATED, entry!!.status)
    }

    @Test fun `RAG_QUERY is registered as ISOLATED`() {
        val entry = ProtocolConvergenceBoundary.forId("long-tail-rag-query-isolated")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.ISOLATED, entry!!.status)
    }

    @Test fun `CODE_EXECUTE is registered as ISOLATED`() {
        val entry = ProtocolConvergenceBoundary.forId("long-tail-code-execute-isolated")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.ISOLATED, entry!!.status)
    }

    @Test fun `WAKE_EVENT is registered as ISOLATED`() {
        val entry = ProtocolConvergenceBoundary.forId("long-tail-wake-event-isolated")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.ISOLATED, entry!!.status)
    }

    @Test fun `SESSION_MIGRATE is registered as ISOLATED`() {
        val entry = ProtocolConvergenceBoundary.forId("long-tail-session-migrate-isolated")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.ISOLATED, entry!!.status)
    }

    @Test fun `BROADCAST is registered as ISOLATED`() {
        val entry = ProtocolConvergenceBoundary.forId("long-tail-broadcast-isolated")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.ISOLATED, entry!!.status)
    }

    @Test fun `LOCK UNLOCK is registered as ISOLATED`() {
        val entry = ProtocolConvergenceBoundary.forId("long-tail-lock-unlock-isolated")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.ISOLATED, entry!!.status)
    }

    @Test fun `all isolated surfaces have ISOLATE_BEHIND_BOUNDARY action`() {
        ProtocolConvergenceBoundary.isolatedSurfaces.forEach { entry ->
            assertEquals(
                "Expected ISOLATE_BEHIND_BOUNDARY for isolated surface ${entry.surfaceId}",
                ProtocolConvergenceBoundary.ConvergenceAction.ISOLATE_BEHIND_BOUNDARY,
                entry.action
            )
        }
    }

    @Test fun `all isolated surfaces have non-null convergenceGate`() {
        ProtocolConvergenceBoundary.isolatedSurfaces.forEach { entry ->
            assertNotNull(
                "Expected non-null convergenceGate for isolated surface ${entry.surfaceId}",
                entry.convergenceGate
            )
        }
    }

    @Test fun `isIsolated returns true for isolated surface IDs`() {
        assertTrue(ProtocolConvergenceBoundary.isIsolated("long-tail-relay-isolated"))
        assertTrue(ProtocolConvergenceBoundary.isIsolated("long-tail-forward-isolated"))
        assertTrue(ProtocolConvergenceBoundary.isIsolated("long-tail-rag-query-isolated"))
    }

    @Test fun `isIsolated returns false for canonical surface IDs`() {
        assertFalse(ProtocolConvergenceBoundary.isIsolated("input-router-dispatch-gate"))
        assertFalse(ProtocolConvergenceBoundary.isIsolated("canonical-dispatch-chain-path-model"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ProtocolConvergenceBoundary — retired surfaces
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `registrationError deprecated bridge is registered as RETIRED`() {
        val entry = ProtocolConvergenceBoundary.forId("registration-error-string-bridge-retired")
        assertNotNull(entry)
        assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.RETIRED, entry!!.status)
    }

    @Test fun `retired surfaces have RETIRE_AFTER_GATE action`() {
        ProtocolConvergenceBoundary.retiredSurfaces.forEach { entry ->
            assertEquals(
                "Expected RETIRE_AFTER_GATE for retired surface ${entry.surfaceId}",
                ProtocolConvergenceBoundary.ConvergenceAction.RETIRE_AFTER_GATE,
                entry.action
            )
        }
    }

    @Test fun `retired surfaces have non-null canonicalPath`() {
        ProtocolConvergenceBoundary.retiredSurfaces.forEach { entry ->
            assertNotNull(
                "Expected non-null canonicalPath for retired surface ${entry.surfaceId}",
                entry.canonicalPath
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ProtocolConvergenceBoundary — pendingConvergenceSurfaces
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `pendingConvergenceSurfaces includes CONVERGING entries`() {
        val pending = ProtocolConvergenceBoundary.pendingConvergenceSurfaces
        val convergingIds = ProtocolConvergenceBoundary.convergingSurfaces.map { it.surfaceId }.toSet()
        convergingIds.forEach { id ->
            assertTrue("Expected $id in pendingConvergenceSurfaces", pending.any { it.surfaceId == id })
        }
    }

    @Test fun `pendingConvergenceSurfaces includes ISOLATED entries`() {
        val pending = ProtocolConvergenceBoundary.pendingConvergenceSurfaces
        val isolatedIds = ProtocolConvergenceBoundary.isolatedSurfaces.map { it.surfaceId }.toSet()
        isolatedIds.forEach { id ->
            assertTrue("Expected $id in pendingConvergenceSurfaces", pending.any { it.surfaceId == id })
        }
    }

    @Test fun `pendingConvergenceSurfaces does NOT include CANONICAL entries`() {
        val pending = ProtocolConvergenceBoundary.pendingConvergenceSurfaces
        val canonicalIds = ProtocolConvergenceBoundary.canonicalSurfaces.map { it.surfaceId }.toSet()
        canonicalIds.forEach { id ->
            assertFalse("Expected $id NOT in pendingConvergenceSurfaces", pending.any { it.surfaceId == id })
        }
    }

    @Test fun `pendingConvergenceSurfaces does NOT include RETIRED entries`() {
        val pending = ProtocolConvergenceBoundary.pendingConvergenceSurfaces
        val retiredIds = ProtocolConvergenceBoundary.retiredSurfaces.map { it.surfaceId }.toSet()
        retiredIds.forEach { id ->
            assertFalse("Expected $id NOT in pendingConvergenceSurfaces", pending.any { it.surfaceId == id })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ProtocolConvergenceBoundary — forId / byStatus helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `forId returns correct entry`() {
        val entry = ProtocolConvergenceBoundary.forId("input-router-dispatch-gate")
        assertNotNull(entry)
        assertEquals("input-router-dispatch-gate", entry!!.surfaceId)
        assertEquals("InputRouter", entry.displayName)
    }

    @Test fun `forId returns null for unknown surface IDs`() {
        assertNull(ProtocolConvergenceBoundary.forId("not-a-real-surface-id"))
    }

    @Test fun `byStatus returns only entries with the given status`() {
        val canonical = ProtocolConvergenceBoundary.byStatus(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CANONICAL)
        assertTrue(canonical.isNotEmpty())
        canonical.forEach { entry ->
            assertEquals(ProtocolConvergenceBoundary.SurfaceConvergenceStatus.CANONICAL, entry.status)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LongTailCompatibilityRegistry.ConvergenceAction — enum values
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `LongTailCompatibilityRegistry ConvergenceAction NO_ACTION wireValue is no_action`() {
        assertEquals("no_action", LongTailCompatibilityRegistry.ConvergenceAction.NO_ACTION.wireValue)
    }

    @Test fun `LongTailCompatibilityRegistry ConvergenceAction PROMOTE_TO_CANONICAL wireValue is promote_to_canonical`() {
        assertEquals("promote_to_canonical", LongTailCompatibilityRegistry.ConvergenceAction.PROMOTE_TO_CANONICAL.wireValue)
    }

    @Test fun `LongTailCompatibilityRegistry ConvergenceAction ISOLATE_BEHIND_BOUNDARY wireValue is isolate_behind_boundary`() {
        assertEquals("isolate_behind_boundary", LongTailCompatibilityRegistry.ConvergenceAction.ISOLATE_BEHIND_BOUNDARY.wireValue)
    }

    @Test fun `LongTailCompatibilityRegistry ConvergenceAction has three values`() {
        assertEquals(3, LongTailCompatibilityRegistry.ConvergenceAction.entries.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LongTailCompatibilityRegistry — convergenceAction per entry
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `TRANSITIONAL entries have ISOLATE_BEHIND_BOUNDARY convergenceAction`() {
        LongTailCompatibilityRegistry.byTier(LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL)
            .forEach { entry ->
                assertEquals(
                    "Expected ISOLATE_BEHIND_BOUNDARY for transitional entry ${entry.type}",
                    LongTailCompatibilityRegistry.ConvergenceAction.ISOLATE_BEHIND_BOUNDARY,
                    entry.convergenceAction
                )
            }
    }

    @Test fun `PROMOTED entries have PROMOTE_TO_CANONICAL convergenceAction`() {
        LongTailCompatibilityRegistry.byTier(LongTailCompatibilityRegistry.CompatTier.PROMOTED)
            .forEach { entry ->
                assertEquals(
                    "Expected PROMOTE_TO_CANONICAL for promoted entry ${entry.type}",
                    LongTailCompatibilityRegistry.ConvergenceAction.PROMOTE_TO_CANONICAL,
                    entry.convergenceAction
                )
            }
    }

    @Test fun `CANONICAL entries have NO_ACTION convergenceAction`() {
        LongTailCompatibilityRegistry.byTier(LongTailCompatibilityRegistry.CompatTier.CANONICAL)
            .forEach { entry ->
                assertEquals(
                    "Expected NO_ACTION for canonical entry ${entry.type}",
                    LongTailCompatibilityRegistry.ConvergenceAction.NO_ACTION,
                    entry.convergenceAction
                )
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LongTailCompatibilityRegistry — isolatedTypes
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `isolatedTypes equals transitionalTypes`() {
        assertEquals(LongTailCompatibilityRegistry.transitionalTypes, LongTailCompatibilityRegistry.isolatedTypes)
    }

    @Test fun `isolatedTypes count is 10 after PR-36`() {
        assertEquals(10, LongTailCompatibilityRegistry.isolatedTypes.size)
    }

    @Test fun `isolatedTypes contains RELAY`() {
        assertTrue(MsgType.RELAY in LongTailCompatibilityRegistry.isolatedTypes)
    }

    @Test fun `isolatedTypes contains FORWARD`() {
        assertTrue(MsgType.FORWARD in LongTailCompatibilityRegistry.isolatedTypes)
    }

    @Test fun `isolatedTypes contains REPLY`() {
        assertTrue(MsgType.REPLY in LongTailCompatibilityRegistry.isolatedTypes)
    }

    @Test fun `isolatedTypes does NOT contain PEER_EXCHANGE`() {
        assertFalse(MsgType.PEER_EXCHANGE in LongTailCompatibilityRegistry.isolatedTypes)
    }

    @Test fun `isolatedTypes does NOT contain TAKEOVER_REQUEST`() {
        assertFalse(MsgType.TAKEOVER_REQUEST in LongTailCompatibilityRegistry.isolatedTypes)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LongTailCompatibilityRegistry — convergingTypes
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `convergingTypes equals promotedTypes`() {
        assertEquals(LongTailCompatibilityRegistry.promotedTypes, LongTailCompatibilityRegistry.convergingTypes)
    }

    @Test fun `convergingTypes count is 4 after PR-36`() {
        assertEquals(4, LongTailCompatibilityRegistry.convergingTypes.size)
    }

    @Test fun `convergingTypes contains PEER_EXCHANGE`() {
        assertTrue(MsgType.PEER_EXCHANGE in LongTailCompatibilityRegistry.convergingTypes)
    }

    @Test fun `convergingTypes contains PEER_ANNOUNCE`() {
        assertTrue(MsgType.PEER_ANNOUNCE in LongTailCompatibilityRegistry.convergingTypes)
    }

    @Test fun `convergingTypes does NOT contain RELAY`() {
        assertFalse(MsgType.RELAY in LongTailCompatibilityRegistry.convergingTypes)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LongTailCompatibilityRegistry — byConvergenceAction
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `byConvergenceAction NO_ACTION count is 2`() {
        assertEquals(2, LongTailCompatibilityRegistry.byConvergenceAction(LongTailCompatibilityRegistry.ConvergenceAction.NO_ACTION).size)
    }

    @Test fun `byConvergenceAction PROMOTE_TO_CANONICAL count is 4`() {
        assertEquals(4, LongTailCompatibilityRegistry.byConvergenceAction(LongTailCompatibilityRegistry.ConvergenceAction.PROMOTE_TO_CANONICAL).size)
    }

    @Test fun `byConvergenceAction ISOLATE_BEHIND_BOUNDARY count is 10`() {
        assertEquals(10, LongTailCompatibilityRegistry.byConvergenceAction(LongTailCompatibilityRegistry.ConvergenceAction.ISOLATE_BEHIND_BOUNDARY).size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GalaxyLogger.TAG_PROTOCOL_CONVERGENCE — stability
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `TAG_PROTOCOL_CONVERGENCE is non-blank`() {
        assertTrue(GalaxyLogger.TAG_PROTOCOL_CONVERGENCE.isNotBlank())
    }

    @Test fun `TAG_PROTOCOL_CONVERGENCE contains PROTOCOL`() {
        assertTrue(GalaxyLogger.TAG_PROTOCOL_CONVERGENCE.contains("PROTOCOL"))
    }

    @Test fun `TAG_PROTOCOL_CONVERGENCE contains CONVERGENCE`() {
        assertTrue(GalaxyLogger.TAG_PROTOCOL_CONVERGENCE.contains("CONVERGENCE"))
    }

    @Test fun `TAG_PROTOCOL_CONVERGENCE is distinct from TAG_LONG_TAIL_COMPAT`() {
        assertFalse(GalaxyLogger.TAG_PROTOCOL_CONVERGENCE == GalaxyLogger.TAG_LONG_TAIL_COMPAT)
    }

    @Test fun `TAG_PROTOCOL_CONVERGENCE is distinct from TAG_RUNTIME_LIFECYCLE`() {
        assertFalse(GalaxyLogger.TAG_PROTOCOL_CONVERGENCE == GalaxyLogger.TAG_RUNTIME_LIFECYCLE)
    }

    @Test fun `TAG_PROTOCOL_CONVERGENCE is distinct from TAG_COMPAT_SURFACE`() {
        assertFalse(GalaxyLogger.TAG_PROTOCOL_CONVERGENCE == GalaxyLogger.TAG_COMPAT_SURFACE)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // StabilizationBaseline — PR-38 entries
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `protocol-convergence-boundary entry is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("protocol-convergence-boundary")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test fun `long-tail-compatibility-registry-convergence-actions entry is registered`() {
        val entry = StabilizationBaseline.forId("long-tail-compatibility-registry-convergence-actions")
        assertNotNull(entry)
    }

    @Test fun `galaxy-logger-tag-protocol-convergence entry is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("galaxy-logger-tag-protocol-convergence")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test fun `all PR-38 entries have introducedPr 38`() {
        val pr38Ids = listOf(
            "protocol-convergence-boundary",
            "long-tail-compatibility-registry-convergence-actions",
            "galaxy-logger-tag-protocol-convergence"
        )
        pr38Ids.forEach { id ->
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Expected baseline entry for $id", entry)
            assertEquals("Expected introducedPr=38 for $id", 38, entry!!.introducedPr)
        }
    }
}
