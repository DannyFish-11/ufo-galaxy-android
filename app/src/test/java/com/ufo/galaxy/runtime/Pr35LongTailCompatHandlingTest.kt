package com.ufo.galaxy.runtime

import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.CoordSyncAckPayload
import com.ufo.galaxy.protocol.MeshTopologyPayload
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.PeerExchangePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-35 — Replace minimal-compat long-tail paths with clearer canonical stateful handling
 * for multi-device execution surfaces.
 *
 * Acceptance and regression test suite validating all PR-35 additions:
 *
 *  1. **[LongTailCompatibilityRegistry]** — explicit inventory of all long-tail minimal-compat
 *     message types with [LongTailCompatibilityRegistry.CompatTier] classification.
 *     - All targeted long-tail types are registered.
 *     - The three promoted types (PEER_EXCHANGE, MESH_TOPOLOGY, COORD_SYNC) carry tier
 *       [LongTailCompatibilityRegistry.CompatTier.PROMOTED].
 *     - All remaining minimal-compat types carry tier
 *       [LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL].
 *     - [LongTailCompatibilityRegistry.byTier], [LongTailCompatibilityRegistry.forType],
 *       [LongTailCompatibilityRegistry.transitionalTypes], and
 *       [LongTailCompatibilityRegistry.promotedTypes] query helpers are correct.
 *
 *  2. **[PeerExchangePayload]** — typed payload model for [MsgType.PEER_EXCHANGE].
 *     Promoted from minimal-compat (log-only) to dedicated stateful handling.
 *
 *  3. **[MeshTopologyPayload]** — typed payload model for [MsgType.MESH_TOPOLOGY].
 *     Promoted from minimal-compat (log-only) to dedicated stateful handling.
 *
 *  4. **[CoordSyncAckPayload]** — sequence-aware acknowledgement payload for
 *     [MsgType.COORD_SYNC]. Promoted from generic [com.ufo.galaxy.protocol.AckPayload]
 *     to sequence-aware response with tick counter.
 *
 *  5. **New [GalaxyLogger] TAG constants** — stable structured log tags for the three
 *     promoted long-tail surfaces:
 *     - [GalaxyLogger.TAG_LONG_TAIL_COMPAT]
 *     - [GalaxyLogger.TAG_PEER_EXCHANGE]
 *     - [GalaxyLogger.TAG_MESH_TOPOLOGY]
 *     - [GalaxyLogger.TAG_COORD_SYNC]
 *
 *  6. **[MsgType.ACK_ON_RECEIPT_TYPES]** — [MsgType.COORD_SYNC] removed; it now sends a
 *     dedicated [CoordSyncAckPayload] response via the promoted handler path.
 *
 * ## Test matrix
 *
 * ### LongTailCompatibilityRegistry — inventory completeness
 *  - PEER_EXCHANGE entry exists and is PROMOTED
 *  - MESH_TOPOLOGY entry exists and is PROMOTED
 *  - COORD_SYNC entry exists and is PROMOTED
 *  - promoted types set contains exactly the three expected types
 *  - RELAY entry exists and is TRANSITIONAL
 *  - FORWARD entry exists and is TRANSITIONAL
 *  - REPLY entry exists and is TRANSITIONAL
 *  - RAG_QUERY entry exists and is TRANSITIONAL
 *  - CODE_EXECUTE entry exists and is TRANSITIONAL
 *  - PEER_ANNOUNCE entry exists and is TRANSITIONAL
 *  - WAKE_EVENT entry exists and is TRANSITIONAL
 *  - SESSION_MIGRATE entry exists and is TRANSITIONAL
 *  - BROADCAST entry exists and is TRANSITIONAL
 *  - LOCK entry exists and is TRANSITIONAL
 *  - UNLOCK entry exists and is TRANSITIONAL
 *  - HYBRID_EXECUTE entry exists and is CANONICAL
 *  - TAKEOVER_REQUEST entry exists and is CANONICAL
 *  - transitional types set does not contain promoted types
 *  - transitional types set does not contain canonical types
 *  - transitional entries all have non-null transitionalNote
 *  - promoted entries all have null transitionalNote
 *  - forType returns null for an unregistered canonical-path type
 *  - byTier(TRANSITIONAL) count is 11
 *  - byTier(PROMOTED) count is 3
 *  - byTier(CANONICAL) count is 2
 *
 * ### PeerExchangePayload — typed model
 *  - required source_device_id is stored correctly
 *  - capabilities list defaults to empty
 *  - mesh_id defaults to null
 *  - exchange_id defaults to null
 *  - populated fields are stored correctly
 *
 * ### MeshTopologyPayload — typed model
 *  - required mesh_id is stored correctly
 *  - nodes list defaults to empty
 *  - topology_seq defaults to 0
 *  - coordinator defaults to null
 *  - populated fields are stored correctly
 *
 * ### CoordSyncAckPayload — typed model
 *  - sync_id is stored correctly
 *  - device_id is stored correctly
 *  - tick_count is stored correctly
 *  - sync_seq defaults to 0
 *  - phase defaults to "active"
 *  - populated fields are stored correctly
 *
 * ### GalaxyLogger TAG constants — stability
 *  - TAG_LONG_TAIL_COMPAT is non-blank and contains "LONG_TAIL"
 *  - TAG_PEER_EXCHANGE is non-blank and contains "PEER"
 *  - TAG_MESH_TOPOLOGY is non-blank and contains "MESH"
 *  - TAG_COORD_SYNC is non-blank and contains "COORD"
 *  - all four tags are distinct
 *
 * ### MsgType.ACK_ON_RECEIPT_TYPES — COORD_SYNC removed
 *  - COORD_SYNC is not in ACK_ON_RECEIPT_TYPES
 *  - PEER_EXCHANGE is not in ACK_ON_RECEIPT_TYPES
 *  - MESH_TOPOLOGY is not in ACK_ON_RECEIPT_TYPES
 *  - RELAY still in ACK_ON_RECEIPT_TYPES (transitional, unchanged)
 *  - WAKE_EVENT still in ACK_ON_RECEIPT_TYPES (transitional, unchanged)
 *  - LOCK still in ACK_ON_RECEIPT_TYPES (transitional, unchanged)
 *  - UNLOCK still in ACK_ON_RECEIPT_TYPES (transitional, unchanged)
 *  - TAKEOVER_REQUEST still in ACK_ON_RECEIPT_TYPES (canonical)
 *
 * ### MsgType.ADVANCED_TYPES — promoted types still dispatched via advanced path
 *  - PEER_EXCHANGE still in ADVANCED_TYPES
 *  - MESH_TOPOLOGY still in ADVANCED_TYPES
 *  - COORD_SYNC still in ADVANCED_TYPES
 */
class Pr35LongTailCompatHandlingTest {

    // ══════════════════════════════════════════════════════════════════════════
    // 1. LongTailCompatibilityRegistry — inventory completeness
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `PEER_EXCHANGE entry exists and is PROMOTED`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.PEER_EXCHANGE)
        assertNotNull("PEER_EXCHANGE must be registered in LongTailCompatibilityRegistry", entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.PROMOTED, entry!!.tier)
    }

    @Test
    fun `MESH_TOPOLOGY entry exists and is PROMOTED`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.MESH_TOPOLOGY)
        assertNotNull("MESH_TOPOLOGY must be registered", entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.PROMOTED, entry!!.tier)
    }

    @Test
    fun `COORD_SYNC entry exists and is PROMOTED`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.COORD_SYNC)
        assertNotNull("COORD_SYNC must be registered", entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.PROMOTED, entry!!.tier)
    }

    @Test
    fun `promoted types set contains exactly the three expected PR-35 types`() {
        val promoted = LongTailCompatibilityRegistry.promotedTypes
        assertTrue("PEER_EXCHANGE must be in promotedTypes", MsgType.PEER_EXCHANGE in promoted)
        assertTrue("MESH_TOPOLOGY must be in promotedTypes", MsgType.MESH_TOPOLOGY in promoted)
        assertTrue("COORD_SYNC must be in promotedTypes", MsgType.COORD_SYNC in promoted)
        assertEquals("exactly 3 promoted types in PR-35", 3, promoted.size)
    }

    @Test
    fun `RELAY entry exists and is TRANSITIONAL`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.RELAY)
        assertNotNull(entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL, entry!!.tier)
    }

    @Test
    fun `FORWARD entry exists and is TRANSITIONAL`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.FORWARD)
        assertNotNull(entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL, entry!!.tier)
    }

    @Test
    fun `REPLY entry exists and is TRANSITIONAL`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.REPLY)
        assertNotNull(entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL, entry!!.tier)
    }

    @Test
    fun `RAG_QUERY entry exists and is TRANSITIONAL`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.RAG_QUERY)
        assertNotNull(entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL, entry!!.tier)
    }

    @Test
    fun `CODE_EXECUTE entry exists and is TRANSITIONAL`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.CODE_EXECUTE)
        assertNotNull(entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL, entry!!.tier)
    }

    @Test
    fun `PEER_ANNOUNCE entry exists and is TRANSITIONAL`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.PEER_ANNOUNCE)
        assertNotNull(entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL, entry!!.tier)
    }

    @Test
    fun `WAKE_EVENT entry exists and is TRANSITIONAL`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.WAKE_EVENT)
        assertNotNull(entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL, entry!!.tier)
    }

    @Test
    fun `SESSION_MIGRATE entry exists and is TRANSITIONAL`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.SESSION_MIGRATE)
        assertNotNull(entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL, entry!!.tier)
    }

    @Test
    fun `BROADCAST entry exists and is TRANSITIONAL`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.BROADCAST)
        assertNotNull(entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL, entry!!.tier)
    }

    @Test
    fun `LOCK entry exists and is TRANSITIONAL`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.LOCK)
        assertNotNull(entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL, entry!!.tier)
    }

    @Test
    fun `UNLOCK entry exists and is TRANSITIONAL`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.UNLOCK)
        assertNotNull(entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL, entry!!.tier)
    }

    @Test
    fun `HYBRID_EXECUTE entry exists and is CANONICAL`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.HYBRID_EXECUTE)
        assertNotNull(entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.CANONICAL, entry!!.tier)
    }

    @Test
    fun `TAKEOVER_REQUEST entry exists and is CANONICAL`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.TAKEOVER_REQUEST)
        assertNotNull(entry)
        assertEquals(LongTailCompatibilityRegistry.CompatTier.CANONICAL, entry!!.tier)
    }

    @Test
    fun `transitional types set does not contain the three promoted types`() {
        val transitional = LongTailCompatibilityRegistry.transitionalTypes
        assertFalse("PEER_EXCHANGE must not be transitional", MsgType.PEER_EXCHANGE in transitional)
        assertFalse("MESH_TOPOLOGY must not be transitional", MsgType.MESH_TOPOLOGY in transitional)
        assertFalse("COORD_SYNC must not be transitional", MsgType.COORD_SYNC in transitional)
    }

    @Test
    fun `transitional types set does not contain canonical types`() {
        val transitional = LongTailCompatibilityRegistry.transitionalTypes
        assertFalse("HYBRID_EXECUTE must not be transitional", MsgType.HYBRID_EXECUTE in transitional)
        assertFalse("TAKEOVER_REQUEST must not be transitional", MsgType.TAKEOVER_REQUEST in transitional)
    }

    @Test
    fun `all transitional entries have non-null transitionalNote`() {
        val transitionalEntries = LongTailCompatibilityRegistry.byTier(
            LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL
        )
        assertTrue("at least one transitional entry must exist", transitionalEntries.isNotEmpty())
        for (entry in transitionalEntries) {
            assertNotNull(
                "TRANSITIONAL entry ${entry.type.value} must have a transitionalNote",
                entry.transitionalNote
            )
            assertTrue(
                "TRANSITIONAL entry ${entry.type.value} transitionalNote must be non-blank",
                entry.transitionalNote!!.isNotBlank()
            )
        }
    }

    @Test
    fun `all promoted entries have null transitionalNote`() {
        val promotedEntries = LongTailCompatibilityRegistry.byTier(
            LongTailCompatibilityRegistry.CompatTier.PROMOTED
        )
        for (entry in promotedEntries) {
            assertNull(
                "PROMOTED entry ${entry.type.value} must not have a transitionalNote",
                entry.transitionalNote
            )
        }
    }

    @Test
    fun `forType returns null for an unregistered primary-path type`() {
        assertNull(
            "TASK_ASSIGN is a canonical primary-path type and must not appear in the long-tail registry",
            LongTailCompatibilityRegistry.forType(MsgType.TASK_ASSIGN)
        )
        assertNull(
            "GOAL_EXECUTION is a canonical primary-path type and must not appear in the long-tail registry",
            LongTailCompatibilityRegistry.forType(MsgType.GOAL_EXECUTION)
        )
    }

    @Test
    fun `byTier TRANSITIONAL returns 11 entries`() {
        assertEquals(
            "exactly 11 TRANSITIONAL entries expected in PR-35 registry",
            11,
            LongTailCompatibilityRegistry.byTier(LongTailCompatibilityRegistry.CompatTier.TRANSITIONAL).size
        )
    }

    @Test
    fun `byTier PROMOTED returns 3 entries`() {
        assertEquals(
            "exactly 3 PROMOTED entries expected in PR-35 registry",
            3,
            LongTailCompatibilityRegistry.byTier(LongTailCompatibilityRegistry.CompatTier.PROMOTED).size
        )
    }

    @Test
    fun `byTier CANONICAL returns 2 entries`() {
        assertEquals(
            "exactly 2 CANONICAL entries expected in PR-35 registry",
            2,
            LongTailCompatibilityRegistry.byTier(LongTailCompatibilityRegistry.CompatTier.CANONICAL).size
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. PeerExchangePayload — typed model
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `PeerExchangePayload stores source_device_id correctly`() {
        val payload = PeerExchangePayload(source_device_id = "pixel-9")
        assertEquals("pixel-9", payload.source_device_id)
    }

    @Test
    fun `PeerExchangePayload capabilities defaults to empty list`() {
        val payload = PeerExchangePayload(source_device_id = "dev-a")
        assertTrue("capabilities should default to empty", payload.capabilities.isEmpty())
    }

    @Test
    fun `PeerExchangePayload mesh_id defaults to null`() {
        assertNull(PeerExchangePayload(source_device_id = "dev-a").mesh_id)
    }

    @Test
    fun `PeerExchangePayload exchange_id defaults to null`() {
        assertNull(PeerExchangePayload(source_device_id = "dev-a").exchange_id)
    }

    @Test
    fun `PeerExchangePayload stores all populated fields correctly`() {
        val caps = listOf("accessibility", "vision", "speech")
        val payload = PeerExchangePayload(
            source_device_id = "phone-b",
            capabilities = caps,
            mesh_id = "mesh-42",
            exchange_id = "exch-99"
        )
        assertEquals("phone-b", payload.source_device_id)
        assertEquals(caps, payload.capabilities)
        assertEquals("mesh-42", payload.mesh_id)
        assertEquals("exch-99", payload.exchange_id)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. MeshTopologyPayload — typed model
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `MeshTopologyPayload stores mesh_id correctly`() {
        val payload = MeshTopologyPayload(mesh_id = "mesh-1")
        assertEquals("mesh-1", payload.mesh_id)
    }

    @Test
    fun `MeshTopologyPayload nodes defaults to empty list`() {
        assertTrue(MeshTopologyPayload(mesh_id = "m").nodes.isEmpty())
    }

    @Test
    fun `MeshTopologyPayload topology_seq defaults to 0`() {
        assertEquals(0, MeshTopologyPayload(mesh_id = "m").topology_seq)
    }

    @Test
    fun `MeshTopologyPayload coordinator defaults to null`() {
        assertNull(MeshTopologyPayload(mesh_id = "m").coordinator)
    }

    @Test
    fun `MeshTopologyPayload stores all populated fields correctly`() {
        val nodes = listOf("dev-a", "dev-b", "dev-c")
        val payload = MeshTopologyPayload(
            mesh_id = "mesh-7",
            nodes = nodes,
            topology_seq = 13,
            coordinator = "dev-a"
        )
        assertEquals("mesh-7", payload.mesh_id)
        assertEquals(nodes, payload.nodes)
        assertEquals(13, payload.topology_seq)
        assertEquals("dev-a", payload.coordinator)
    }

    @Test
    fun `MeshTopologyPayload higher sequence number is newer`() {
        val earlier = MeshTopologyPayload(mesh_id = "m", topology_seq = 4)
        val later = MeshTopologyPayload(mesh_id = "m", topology_seq = 7)
        assertTrue("higher seq should be considered newer", later.topology_seq > earlier.topology_seq)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. CoordSyncAckPayload — typed model
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `CoordSyncAckPayload stores sync_id and device_id correctly`() {
        val payload = CoordSyncAckPayload(
            sync_id = "sync-001",
            device_id = "Pixel_9",
            tick_count = 1
        )
        assertEquals("sync-001", payload.sync_id)
        assertEquals("Pixel_9", payload.device_id)
    }

    @Test
    fun `CoordSyncAckPayload stores tick_count correctly`() {
        val payload = CoordSyncAckPayload(sync_id = "s", device_id = "d", tick_count = 7)
        assertEquals(7, payload.tick_count)
    }

    @Test
    fun `CoordSyncAckPayload sync_seq defaults to 0`() {
        val payload = CoordSyncAckPayload(sync_id = "s", device_id = "d", tick_count = 1)
        assertEquals(0, payload.sync_seq)
    }

    @Test
    fun `CoordSyncAckPayload phase defaults to active`() {
        val payload = CoordSyncAckPayload(sync_id = "s", device_id = "d", tick_count = 1)
        assertEquals("active", payload.phase)
    }

    @Test
    fun `CoordSyncAckPayload stores all populated fields correctly`() {
        val payload = CoordSyncAckPayload(
            sync_id = "sync-77",
            device_id = "tablet-x",
            sync_seq = 42,
            tick_count = 15,
            phase = "active"
        )
        assertEquals("sync-77", payload.sync_id)
        assertEquals("tablet-x", payload.device_id)
        assertEquals(42, payload.sync_seq)
        assertEquals(15, payload.tick_count)
        assertEquals("active", payload.phase)
    }

    @Test
    fun `CoordSyncAckPayload tick_count is monotonically ordered`() {
        val first = CoordSyncAckPayload(sync_id = "s1", device_id = "d", tick_count = 3)
        val second = CoordSyncAckPayload(sync_id = "s2", device_id = "d", tick_count = 4)
        assertTrue("second tick should have higher count", second.tick_count > first.tick_count)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. GalaxyLogger TAG constants — stability
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TAG_LONG_TAIL_COMPAT is non-blank and contains LONG_TAIL`() {
        assertTrue(GalaxyLogger.TAG_LONG_TAIL_COMPAT.isNotBlank())
        assertTrue(
            "TAG_LONG_TAIL_COMPAT must contain LONG_TAIL",
            GalaxyLogger.TAG_LONG_TAIL_COMPAT.contains("LONG_TAIL")
        )
    }

    @Test
    fun `TAG_PEER_EXCHANGE is non-blank and contains PEER`() {
        assertTrue(GalaxyLogger.TAG_PEER_EXCHANGE.isNotBlank())
        assertTrue(
            "TAG_PEER_EXCHANGE must contain PEER",
            GalaxyLogger.TAG_PEER_EXCHANGE.contains("PEER")
        )
    }

    @Test
    fun `TAG_MESH_TOPOLOGY is non-blank and contains MESH`() {
        assertTrue(GalaxyLogger.TAG_MESH_TOPOLOGY.isNotBlank())
        assertTrue(
            "TAG_MESH_TOPOLOGY must contain MESH",
            GalaxyLogger.TAG_MESH_TOPOLOGY.contains("MESH")
        )
    }

    @Test
    fun `TAG_COORD_SYNC is non-blank and contains COORD`() {
        assertTrue(GalaxyLogger.TAG_COORD_SYNC.isNotBlank())
        assertTrue(
            "TAG_COORD_SYNC must contain COORD",
            GalaxyLogger.TAG_COORD_SYNC.contains("COORD")
        )
    }

    @Test
    fun `all four PR-35 TAG constants are distinct`() {
        val tags = setOf(
            GalaxyLogger.TAG_LONG_TAIL_COMPAT,
            GalaxyLogger.TAG_PEER_EXCHANGE,
            GalaxyLogger.TAG_MESH_TOPOLOGY,
            GalaxyLogger.TAG_COORD_SYNC
        )
        assertEquals("all four PR-35 TAG constants must be distinct", 4, tags.size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. MsgType.ACK_ON_RECEIPT_TYPES — COORD_SYNC removed, others unchanged
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `COORD_SYNC is not in ACK_ON_RECEIPT_TYPES after PR-35 promotion`() {
        assertFalse(
            "COORD_SYNC must not be in ACK_ON_RECEIPT_TYPES: it now uses CoordSyncAckPayload path",
            MsgType.COORD_SYNC in MsgType.ACK_ON_RECEIPT_TYPES
        )
    }

    @Test
    fun `PEER_EXCHANGE is not in ACK_ON_RECEIPT_TYPES`() {
        assertFalse(
            "PEER_EXCHANGE must not be in ACK_ON_RECEIPT_TYPES: its ack is sent by the dedicated handler",
            MsgType.PEER_EXCHANGE in MsgType.ACK_ON_RECEIPT_TYPES
        )
    }

    @Test
    fun `MESH_TOPOLOGY is not in ACK_ON_RECEIPT_TYPES`() {
        assertFalse(
            "MESH_TOPOLOGY must not be in ACK_ON_RECEIPT_TYPES: its ack is sent by the dedicated handler",
            MsgType.MESH_TOPOLOGY in MsgType.ACK_ON_RECEIPT_TYPES
        )
    }

    @Test
    fun `RELAY is still in ACK_ON_RECEIPT_TYPES as a transitional surface`() {
        assertTrue(MsgType.RELAY in MsgType.ACK_ON_RECEIPT_TYPES)
    }

    @Test
    fun `WAKE_EVENT is still in ACK_ON_RECEIPT_TYPES as a transitional surface`() {
        assertTrue(MsgType.WAKE_EVENT in MsgType.ACK_ON_RECEIPT_TYPES)
    }

    @Test
    fun `LOCK is still in ACK_ON_RECEIPT_TYPES as a transitional surface`() {
        assertTrue(MsgType.LOCK in MsgType.ACK_ON_RECEIPT_TYPES)
    }

    @Test
    fun `UNLOCK is still in ACK_ON_RECEIPT_TYPES as a transitional surface`() {
        assertTrue(MsgType.UNLOCK in MsgType.ACK_ON_RECEIPT_TYPES)
    }

    @Test
    fun `TAKEOVER_REQUEST is still in ACK_ON_RECEIPT_TYPES as the canonical delivery ack`() {
        assertTrue(MsgType.TAKEOVER_REQUEST in MsgType.ACK_ON_RECEIPT_TYPES)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. MsgType.ADVANCED_TYPES — promoted types still dispatched via onAdvancedMessage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `PEER_EXCHANGE remains in ADVANCED_TYPES so WS client dispatches to onAdvancedMessage`() {
        assertTrue(
            "PEER_EXCHANGE must remain in ADVANCED_TYPES; dedicated routing happens inside onAdvancedMessage",
            MsgType.PEER_EXCHANGE in MsgType.ADVANCED_TYPES
        )
    }

    @Test
    fun `MESH_TOPOLOGY remains in ADVANCED_TYPES so WS client dispatches to onAdvancedMessage`() {
        assertTrue(
            "MESH_TOPOLOGY must remain in ADVANCED_TYPES",
            MsgType.MESH_TOPOLOGY in MsgType.ADVANCED_TYPES
        )
    }

    @Test
    fun `COORD_SYNC remains in ADVANCED_TYPES so WS client dispatches to onAdvancedMessage`() {
        assertTrue(
            "COORD_SYNC must remain in ADVANCED_TYPES",
            MsgType.COORD_SYNC in MsgType.ADVANCED_TYPES
        )
    }
}
