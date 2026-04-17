package com.ufo.galaxy.runtime

import com.ufo.galaxy.observability.GalaxyLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-10 — Explicit compatibility-surface retirement plan.
 *
 * Regression and acceptance test suite for all PR-10 additions:
 *
 *  1. **[CompatibilitySurfaceRetirementRegistry]** — explicit inventory of all high-risk
 *     compatibility surfaces with [CompatibilitySurfaceRetirementRegistry.RetirementTier]
 *     and [CompatibilitySurfaceRetirementRegistry.SurfaceKind] labels.
 *     - All targeted surfaces are registered.
 *     - Two [CompatibilitySurfaceRetirementRegistry.RetirementTier.HIGH_RISK_ACTIVE] runtime
 *       bridges (registrationError, currentSessionSnapshot) are present.
 *     - Both transitional PROTOCOL_SURFACE entries are present and HIGH_RISK_ACTIVE.
 *     - Deprecated API surfaces are RETIRE_AFTER_MIGRATION.
 *     - Protocol alias surfaces are RETIRE_AFTER_COORDINATION.
 *     - All 11 dispatch adapters are present (9 RETIRE_AFTER_COORDINATION + 2 DECOMMISSION_CANDIDATE).
 *     - [CompatibilitySurfaceRetirementRegistry.byTier],
 *       [CompatibilitySurfaceRetirementRegistry.byKind],
 *       [CompatibilitySurfaceRetirementRegistry.forId],
 *       [CompatibilitySurfaceRetirementRegistry.highRiskSurfaceIds],
 *       [CompatibilitySurfaceRetirementRegistry.decommissionCandidateIds], and
 *       [CompatibilitySurfaceRetirementRegistry.coordinationGatedCount] query helpers
 *       are correct.
 *
 *  2. **[GalaxyLogger.TAG_COMPAT_SURFACE]** — new stable structured log tag constant
 *     (`"GALAXY:COMPAT:SURFACE"`) for compatibility surface runtime observability.
 *
 * ## Test matrix
 *
 * ### CompatibilitySurfaceRetirementRegistry — inventory completeness
 *  - registrationError runtime bridge is registered and RETIRE_AFTER_MIGRATION (migrated in PR-36)
 *  - currentSessionSnapshot legacy map bridge is registered and HIGH_RISK_ACTIVE
 *  - session identifier carrier protocol surface is registered and HIGH_RISK_ACTIVE
 *  - staged mesh execution status protocol surface is registered and HIGH_RISK_ACTIVE
 *  - registerDevice deprecated API is registered and RETIRE_AFTER_MIGRATION
 *  - sendHeartbeat deprecated API is registered and RETIRE_AFTER_MIGRATION
 *  - legacy msgtype alias normalization is registered and RETIRE_AFTER_COORDINATION
 *  - lifecycle status normalization map is registered and RETIRE_AFTER_COORDINATION
 *  - RELAY dispatch adapter is registered and RETIRE_AFTER_COORDINATION
 *  - FORWARD dispatch adapter is registered and RETIRE_AFTER_COORDINATION
 *  - REPLY dispatch adapter is registered and RETIRE_AFTER_COORDINATION
 *  - RAG_QUERY dispatch adapter is registered and RETIRE_AFTER_COORDINATION
 *  - CODE_EXECUTE dispatch adapter is registered and RETIRE_AFTER_COORDINATION
 *  - PEER_ANNOUNCE dispatch adapter is registered and DECOMMISSION_CANDIDATE (promoted in PR-36)
 *  - WAKE_EVENT dispatch adapter is registered and RETIRE_AFTER_COORDINATION
 *  - SESSION_MIGRATE dispatch adapter is registered and RETIRE_AFTER_COORDINATION
 *  - BROADCAST dispatch adapter is registered and RETIRE_AFTER_COORDINATION
 *  - LOCK dispatch adapter is registered and DECOMMISSION_CANDIDATE
 *  - UNLOCK dispatch adapter is registered and DECOMMISSION_CANDIDATE
 *  - forId returns null for an unregistered surface ID
 *
 * ### CompatibilitySurfaceRetirementRegistry — tier and kind counts
 *  - HIGH_RISK_ACTIVE count is 3 (registrationError bridge moved to RETIRE_AFTER_MIGRATION in PR-36)
 *  - RETIRE_AFTER_MIGRATION count is 3 (registrationError bridge added in PR-36)
 *  - RETIRE_AFTER_COORDINATION count is 10 (PEER_ANNOUNCE promoted to DECOMMISSION_CANDIDATE in PR-36)
 *  - DECOMMISSION_CANDIDATE count is 3 (PEER_ANNOUNCE added in PR-36)
 *  - total entry count is 19
 *  - RUNTIME_BRIDGE kind count is 2
 *  - PROTOCOL_ALIAS kind count is 2
 *  - DISPATCH_ADAPTER kind count is 11
 *  - PROTOCOL_SURFACE kind count is 2
 *  - DEPRECATED_API kind count is 2
 *
 * ### CompatibilitySurfaceRetirementRegistry — data integrity
 *  - all entries have non-blank surfaceId
 *  - all entries have non-blank description
 *  - all entries have non-blank canonicalReplacement
 *  - all entries have non-blank retirementGate
 *  - all surfaceIds are unique (no duplicates)
 *  - coordinationGatedCount equals RETIRE_AFTER_COORDINATION count
 *  - highRiskSurfaceIds contains exactly the 3 remaining HIGH_RISK_ACTIVE surface IDs
 *  - decommissionCandidateIds contains exactly the 3 DECOMMISSION_CANDIDATE surface IDs
 *
 * ### CompatibilitySurfaceRetirementRegistry — governance contract
 *  - no RUNTIME_BRIDGE entry is DECOMMISSION_CANDIDATE (bridges need migration, not deletion)
 *  - no PROTOCOL_SURFACE entry is RETIRE_AFTER_MIGRATION (protocol surfaces need coordination)
 *  - DISPATCH_ADAPTER entries with RETIRE_AFTER_COORDINATION are the long-tail canonical set
 *
 * ### GalaxyLogger.TAG_COMPAT_SURFACE — stability
 *  - TAG_COMPAT_SURFACE is non-blank
 *  - TAG_COMPAT_SURFACE contains "COMPAT"
 *  - TAG_COMPAT_SURFACE contains "SURFACE"
 *  - TAG_COMPAT_SURFACE is distinct from TAG_LONG_TAIL_COMPAT
 *  - TAG_COMPAT_SURFACE is distinct from TAG_INTERACTION_ACCEPTANCE
 */
class Pr10CompatibilitySurfaceRetirementTest {

    // ══════════════════════════════════════════════════════════════════════════
    // 1. CompatibilitySurfaceRetirementRegistry — individual entry validation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `registrationError runtime bridge is registered and RETIRE_AFTER_MIGRATION`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId(
            "runtime_registration_error_string_bridge"
        )
        assertNotNull("registrationError bridge must be registered", entry)
        assertEquals(
            "registrationError bridge must be RETIRE_AFTER_MIGRATION after PR-36 consumer migration",
            CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_MIGRATION,
            entry!!.retirementTier
        )
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.RUNTIME_BRIDGE, entry.surfaceKind)
    }

    @Test
    fun `currentSessionSnapshot legacy map bridge is registered and HIGH_RISK_ACTIVE`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId(
            "runtime_host_session_legacy_map_bridge"
        )
        assertNotNull("currentSessionSnapshot bridge must be registered", entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.HIGH_RISK_ACTIVE, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.RUNTIME_BRIDGE, entry.surfaceKind)
    }

    @Test
    fun `session identifier carrier protocol surface is registered and HIGH_RISK_ACTIVE`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId(
            "session_identifier_carrier_transitional_surface"
        )
        assertNotNull("SESSION_IDENTIFIER_CARRIER surface must be registered", entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.HIGH_RISK_ACTIVE, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.PROTOCOL_SURFACE, entry.surfaceKind)
    }

    @Test
    fun `staged mesh execution status protocol surface is registered and HIGH_RISK_ACTIVE`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId(
            "staged_mesh_execution_status_transitional_surface"
        )
        assertNotNull("STAGED_MESH_EXECUTION_STATUS surface must be registered", entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.HIGH_RISK_ACTIVE, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.PROTOCOL_SURFACE, entry.surfaceKind)
    }

    @Test
    fun `registerDevice deprecated API is registered and RETIRE_AFTER_MIGRATION`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId(
            "galaxy_api_client_register_device_deprecated"
        )
        assertNotNull("GalaxyApiClient.registerDevice must be registered", entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_MIGRATION, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.DEPRECATED_API, entry.surfaceKind)
    }

    @Test
    fun `sendHeartbeat deprecated API is registered and RETIRE_AFTER_MIGRATION`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId(
            "galaxy_api_client_send_heartbeat_deprecated"
        )
        assertNotNull("GalaxyApiClient.sendHeartbeat must be registered", entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_MIGRATION, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.DEPRECATED_API, entry.surfaceKind)
    }

    @Test
    fun `legacy msgtype alias normalization is registered and RETIRE_AFTER_COORDINATION`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId(
            "legacy_msgtype_alias_normalization"
        )
        assertNotNull("legacy msgtype alias normalization must be registered", entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.PROTOCOL_ALIAS, entry.surfaceKind)
    }

    @Test
    fun `lifecycle status normalization map is registered and RETIRE_AFTER_COORDINATION`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId(
            "lifecycle_status_normalization_map"
        )
        assertNotNull("lifecycle status normalization map must be registered", entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.PROTOCOL_ALIAS, entry.surfaceKind)
    }

    @Test
    fun `RELAY dispatch adapter is registered and RETIRE_AFTER_COORDINATION`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId("dispatch_adapter_relay_transitional")
        assertNotNull(entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.DISPATCH_ADAPTER, entry.surfaceKind)
    }

    @Test
    fun `FORWARD dispatch adapter is registered and RETIRE_AFTER_COORDINATION`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId("dispatch_adapter_forward_transitional")
        assertNotNull(entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.DISPATCH_ADAPTER, entry.surfaceKind)
    }

    @Test
    fun `REPLY dispatch adapter is registered and RETIRE_AFTER_COORDINATION`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId("dispatch_adapter_reply_transitional")
        assertNotNull(entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.DISPATCH_ADAPTER, entry.surfaceKind)
    }

    @Test
    fun `RAG_QUERY dispatch adapter is registered and RETIRE_AFTER_COORDINATION`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId("dispatch_adapter_rag_query_transitional")
        assertNotNull(entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.DISPATCH_ADAPTER, entry.surfaceKind)
    }

    @Test
    fun `CODE_EXECUTE dispatch adapter is registered and RETIRE_AFTER_COORDINATION`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId("dispatch_adapter_code_execute_transitional")
        assertNotNull(entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.DISPATCH_ADAPTER, entry.surfaceKind)
    }

    @Test
    fun `PEER_ANNOUNCE dispatch adapter is registered and DECOMMISSION_CANDIDATE`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId("dispatch_adapter_peer_announce_transitional")
        assertNotNull(entry)
        assertEquals(
            "PEER_ANNOUNCE adapter must be DECOMMISSION_CANDIDATE after PR-36 stateful handler promotion",
            CompatibilitySurfaceRetirementRegistry.RetirementTier.DECOMMISSION_CANDIDATE,
            entry!!.retirementTier
        )
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.DISPATCH_ADAPTER, entry.surfaceKind)
    }

    @Test
    fun `WAKE_EVENT dispatch adapter is registered and RETIRE_AFTER_COORDINATION`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId("dispatch_adapter_wake_event_transitional")
        assertNotNull(entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.DISPATCH_ADAPTER, entry.surfaceKind)
    }

    @Test
    fun `SESSION_MIGRATE dispatch adapter is registered and RETIRE_AFTER_COORDINATION`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId("dispatch_adapter_session_migrate_transitional")
        assertNotNull(entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.DISPATCH_ADAPTER, entry.surfaceKind)
    }

    @Test
    fun `BROADCAST dispatch adapter is registered and RETIRE_AFTER_COORDINATION`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId("dispatch_adapter_broadcast_transitional")
        assertNotNull(entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.DISPATCH_ADAPTER, entry.surfaceKind)
    }

    @Test
    fun `LOCK dispatch adapter is registered and DECOMMISSION_CANDIDATE`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId("dispatch_adapter_lock_transitional")
        assertNotNull(entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.DECOMMISSION_CANDIDATE, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.DISPATCH_ADAPTER, entry.surfaceKind)
    }

    @Test
    fun `UNLOCK dispatch adapter is registered and DECOMMISSION_CANDIDATE`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId("dispatch_adapter_unlock_transitional")
        assertNotNull(entry)
        assertEquals(CompatibilitySurfaceRetirementRegistry.RetirementTier.DECOMMISSION_CANDIDATE, entry!!.retirementTier)
        assertEquals(CompatibilitySurfaceRetirementRegistry.SurfaceKind.DISPATCH_ADAPTER, entry.surfaceKind)
    }

    @Test
    fun `forId returns null for an unregistered surface ID`() {
        assertNull(CompatibilitySurfaceRetirementRegistry.forId("non_existent_surface_xyz"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. CompatibilitySurfaceRetirementRegistry — tier and kind counts
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `HIGH_RISK_ACTIVE count is 3 after PR-36 registrationError bridge migration`() {
        assertEquals(
            "exactly 3 HIGH_RISK_ACTIVE entries expected after PR-36 moves registrationError to RETIRE_AFTER_MIGRATION",
            3,
            CompatibilitySurfaceRetirementRegistry.byTier(
                CompatibilitySurfaceRetirementRegistry.RetirementTier.HIGH_RISK_ACTIVE
            ).size
        )
    }

    @Test
    fun `RETIRE_AFTER_MIGRATION count is 3 after PR-36 registrationError migration`() {
        assertEquals(
            "exactly 3 RETIRE_AFTER_MIGRATION entries expected after PR-36 adds registrationError bridge",
            3,
            CompatibilitySurfaceRetirementRegistry.byTier(
                CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_MIGRATION
            ).size
        )
    }

    @Test
    fun `RETIRE_AFTER_COORDINATION count is 10 after PR-36 PEER_ANNOUNCE promotion`() {
        assertEquals(
            "exactly 10 RETIRE_AFTER_COORDINATION entries expected after PR-36 moves PEER_ANNOUNCE to DECOMMISSION_CANDIDATE",
            10,
            CompatibilitySurfaceRetirementRegistry.byTier(
                CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION
            ).size
        )
    }

    @Test
    fun `DECOMMISSION_CANDIDATE count is 3 after PR-36 PEER_ANNOUNCE promotion`() {
        assertEquals(
            "exactly 3 DECOMMISSION_CANDIDATE entries expected after PR-36 adds PEER_ANNOUNCE",
            3,
            CompatibilitySurfaceRetirementRegistry.byTier(
                CompatibilitySurfaceRetirementRegistry.RetirementTier.DECOMMISSION_CANDIDATE
            ).size
        )
    }

    @Test
    fun `total entry count is 19`() {
        assertEquals("total entry count must be 19", 19, CompatibilitySurfaceRetirementRegistry.entries.size)
    }

    @Test
    fun `RUNTIME_BRIDGE kind count is 2`() {
        assertEquals(
            2,
            CompatibilitySurfaceRetirementRegistry.byKind(
                CompatibilitySurfaceRetirementRegistry.SurfaceKind.RUNTIME_BRIDGE
            ).size
        )
    }

    @Test
    fun `PROTOCOL_ALIAS kind count is 2`() {
        assertEquals(
            2,
            CompatibilitySurfaceRetirementRegistry.byKind(
                CompatibilitySurfaceRetirementRegistry.SurfaceKind.PROTOCOL_ALIAS
            ).size
        )
    }

    @Test
    fun `DISPATCH_ADAPTER kind count is 11`() {
        assertEquals(
            11,
            CompatibilitySurfaceRetirementRegistry.byKind(
                CompatibilitySurfaceRetirementRegistry.SurfaceKind.DISPATCH_ADAPTER
            ).size
        )
    }

    @Test
    fun `PROTOCOL_SURFACE kind count is 2`() {
        assertEquals(
            2,
            CompatibilitySurfaceRetirementRegistry.byKind(
                CompatibilitySurfaceRetirementRegistry.SurfaceKind.PROTOCOL_SURFACE
            ).size
        )
    }

    @Test
    fun `DEPRECATED_API kind count is 2`() {
        assertEquals(
            2,
            CompatibilitySurfaceRetirementRegistry.byKind(
                CompatibilitySurfaceRetirementRegistry.SurfaceKind.DEPRECATED_API
            ).size
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. CompatibilitySurfaceRetirementRegistry — data integrity
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `all entries have non-blank surfaceId`() {
        CompatibilitySurfaceRetirementRegistry.entries.forEach { entry ->
            assertTrue(
                "surfaceId must not be blank for entry: $entry",
                entry.surfaceId.isNotBlank()
            )
        }
    }

    @Test
    fun `all entries have non-blank description`() {
        CompatibilitySurfaceRetirementRegistry.entries.forEach { entry ->
            assertTrue(
                "description must not be blank for entry: ${entry.surfaceId}",
                entry.description.isNotBlank()
            )
        }
    }

    @Test
    fun `all entries have non-blank canonicalReplacement`() {
        CompatibilitySurfaceRetirementRegistry.entries.forEach { entry ->
            assertTrue(
                "canonicalReplacement must not be blank for entry: ${entry.surfaceId}",
                entry.canonicalReplacement.isNotBlank()
            )
        }
    }

    @Test
    fun `all entries have non-blank retirementGate`() {
        CompatibilitySurfaceRetirementRegistry.entries.forEach { entry ->
            assertTrue(
                "retirementGate must not be blank for entry: ${entry.surfaceId}",
                entry.retirementGate.isNotBlank()
            )
        }
    }

    @Test
    fun `all surfaceIds are unique`() {
        val ids = CompatibilitySurfaceRetirementRegistry.entries.map { it.surfaceId }
        assertEquals("all surfaceIds must be unique; duplicates found", ids.size, ids.toSet().size)
    }

    @Test
    fun `coordinationGatedCount equals RETIRE_AFTER_COORDINATION count`() {
        val expected = CompatibilitySurfaceRetirementRegistry.byTier(
            CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION
        ).size
        assertEquals(expected, CompatibilitySurfaceRetirementRegistry.coordinationGatedCount)
    }

    @Test
    fun `highRiskSurfaceIds contains exactly the 3 remaining HIGH_RISK_ACTIVE surface IDs`() {
        val highRisk = CompatibilitySurfaceRetirementRegistry.highRiskSurfaceIds
        assertEquals(
            "highRiskSurfaceIds must contain exactly 3 entries after PR-36 moves registrationError to RETIRE_AFTER_MIGRATION",
            3,
            highRisk.size
        )
        assertTrue(highRisk.contains("runtime_host_session_legacy_map_bridge"))
        assertTrue(highRisk.contains("session_identifier_carrier_transitional_surface"))
        assertTrue(highRisk.contains("staged_mesh_execution_status_transitional_surface"))
        assertFalse(
            "runtime_registration_error_string_bridge must not be HIGH_RISK_ACTIVE after PR-36 deprecation+migration",
            highRisk.contains("runtime_registration_error_string_bridge")
        )
    }

    @Test
    fun `decommissionCandidateIds contains exactly the 3 DECOMMISSION_CANDIDATE surface IDs`() {
        val candidates = CompatibilitySurfaceRetirementRegistry.decommissionCandidateIds
        assertEquals(
            "decommissionCandidateIds must contain exactly 3 entries after PR-36 adds PEER_ANNOUNCE",
            3,
            candidates.size
        )
        assertTrue(candidates.contains("dispatch_adapter_lock_transitional"))
        assertTrue(candidates.contains("dispatch_adapter_unlock_transitional"))
        assertTrue(candidates.contains("dispatch_adapter_peer_announce_transitional"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. CompatibilitySurfaceRetirementRegistry — governance contract
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `no RUNTIME_BRIDGE entry is DECOMMISSION_CANDIDATE`() {
        val bridges = CompatibilitySurfaceRetirementRegistry.byKind(
            CompatibilitySurfaceRetirementRegistry.SurfaceKind.RUNTIME_BRIDGE
        )
        bridges.forEach { entry ->
            assertFalse(
                "RUNTIME_BRIDGE '${entry.surfaceId}' must not be DECOMMISSION_CANDIDATE; " +
                    "bridges require migration gates, not simple deletion",
                entry.retirementTier == CompatibilitySurfaceRetirementRegistry.RetirementTier.DECOMMISSION_CANDIDATE
            )
        }
    }

    @Test
    fun `no PROTOCOL_SURFACE entry is RETIRE_AFTER_MIGRATION`() {
        val protocolSurfaces = CompatibilitySurfaceRetirementRegistry.byKind(
            CompatibilitySurfaceRetirementRegistry.SurfaceKind.PROTOCOL_SURFACE
        )
        protocolSurfaces.forEach { entry ->
            assertFalse(
                "PROTOCOL_SURFACE '${entry.surfaceId}' must not be RETIRE_AFTER_MIGRATION; " +
                    "protocol surfaces require cross-repo coordination",
                entry.retirementTier == CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_MIGRATION
            )
        }
    }

    @Test
    fun `DISPATCH_ADAPTER entries with RETIRE_AFTER_COORDINATION are the long-tail canonical set`() {
        val coordAdapters = CompatibilitySurfaceRetirementRegistry.byKind(
            CompatibilitySurfaceRetirementRegistry.SurfaceKind.DISPATCH_ADAPTER
        ).filter {
            it.retirementTier == CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION
        }
        val expectedIds = setOf(
            "dispatch_adapter_relay_transitional",
            "dispatch_adapter_forward_transitional",
            "dispatch_adapter_reply_transitional",
            "dispatch_adapter_rag_query_transitional",
            "dispatch_adapter_code_execute_transitional",
            // PEER_ANNOUNCE removed: promoted to DECOMMISSION_CANDIDATE in PR-36
            "dispatch_adapter_wake_event_transitional",
            "dispatch_adapter_session_migrate_transitional",
            "dispatch_adapter_broadcast_transitional"
        )
        assertEquals(
            "RETIRE_AFTER_COORDINATION dispatch adapters must match the long-tail canonical set",
            expectedIds,
            coordAdapters.map { it.surfaceId }.toSet()
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. GalaxyLogger.TAG_COMPAT_SURFACE — stability
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TAG_COMPAT_SURFACE is non-blank`() {
        assertTrue("TAG_COMPAT_SURFACE must not be blank", GalaxyLogger.TAG_COMPAT_SURFACE.isNotBlank())
    }

    @Test
    fun `TAG_COMPAT_SURFACE contains COMPAT`() {
        assertTrue(
            "TAG_COMPAT_SURFACE must contain 'COMPAT'",
            GalaxyLogger.TAG_COMPAT_SURFACE.contains("COMPAT")
        )
    }

    @Test
    fun `TAG_COMPAT_SURFACE contains SURFACE`() {
        assertTrue(
            "TAG_COMPAT_SURFACE must contain 'SURFACE'",
            GalaxyLogger.TAG_COMPAT_SURFACE.contains("SURFACE")
        )
    }

    @Test
    fun `TAG_COMPAT_SURFACE is distinct from TAG_LONG_TAIL_COMPAT`() {
        assertFalse(
            "TAG_COMPAT_SURFACE must be distinct from TAG_LONG_TAIL_COMPAT",
            GalaxyLogger.TAG_COMPAT_SURFACE == GalaxyLogger.TAG_LONG_TAIL_COMPAT
        )
    }

    @Test
    fun `TAG_COMPAT_SURFACE is distinct from TAG_INTERACTION_ACCEPTANCE`() {
        assertFalse(
            "TAG_COMPAT_SURFACE must be distinct from TAG_INTERACTION_ACCEPTANCE",
            GalaxyLogger.TAG_COMPAT_SURFACE == GalaxyLogger.TAG_INTERACTION_ACCEPTANCE
        )
    }
}
