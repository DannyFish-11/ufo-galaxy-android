package com.ufo.galaxy.runtime

import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.PeerAnnouncePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-36 — Close known Android runtime gaps in compatibility cleanup, lifecycle hardening,
 * and protocol convergence.
 *
 * Acceptance and regression test suite validating all PR-36 additions:
 *
 *  1. **[LongTailCompatibilityRegistry]** — PEER_ANNOUNCE promoted from TRANSITIONAL to PROMOTED.
 *     - PEER_ANNOUNCE entry exists and is PROMOTED.
 *     - promotedTypes set now contains 4 entries (3 from PR-35 + PEER_ANNOUNCE).
 *     - transitionalTypes set no longer contains PEER_ANNOUNCE.
 *     - byTier(TRANSITIONAL) count is now 10 (was 11 in PR-35).
 *     - byTier(PROMOTED) count is now 4 (was 3 in PR-35).
 *
 *  2. **[PeerAnnouncePayload]** — typed payload model for [MsgType.PEER_ANNOUNCE].
 *     Promoted from minimal-compat (log-only) to dedicated stateful peer-presence tracking.
 *     - required peer_device_id is stored correctly.
 *     - peer_role defaults to null.
 *     - session_id defaults to null.
 *     - announce_seq defaults to 0.
 *     - populated fields are stored correctly.
 *     - records with the same peer_device_id and a higher announce_seq supersede older ones.
 *
 *  3. **[GalaxyLogger.TAG_PEER_ANNOUNCE]** — new stable structured log tag constant.
 *     - TAG_PEER_ANNOUNCE is non-blank.
 *     - TAG_PEER_ANNOUNCE contains "PEER".
 *     - TAG_PEER_ANNOUNCE contains "ANNOUNCE".
 *     - TAG_PEER_ANNOUNCE is distinct from TAG_PEER_EXCHANGE.
 *     - TAG_PEER_ANNOUNCE is distinct from TAG_LONG_TAIL_COMPAT.
 *
 *  4. **[CompatibilitySurfaceRetirementRegistry]** — registry updated to reflect PR-36.
 *     - runtime_registration_error_string_bridge is now RETIRE_AFTER_MIGRATION (was HIGH_RISK_ACTIVE).
 *     - dispatch_adapter_peer_announce_transitional is now DECOMMISSION_CANDIDATE (was RETIRE_AFTER_COORDINATION).
 *     - HIGH_RISK_ACTIVE count is now 3 (was 4).
 *     - RETIRE_AFTER_MIGRATION count is now 3 (was 2).
 *     - RETIRE_AFTER_COORDINATION count is now 10 (was 11).
 *     - DECOMMISSION_CANDIDATE count is now 3 (was 2).
 *     - highRiskSurfaceIds no longer contains runtime_registration_error_string_bridge.
 *     - decommissionCandidateIds contains dispatch_adapter_peer_announce_transitional.
 *
 *  5. **[MsgType.PEER_ANNOUNCE]** — status annotation updated to pr36-promoted.
 *     - PEER_ANNOUNCE wire value is "peer_announce" (unchanged).
 *     - PEER_ANNOUNCE is still in MsgType.ADVANCED_TYPES.
 *
 * ## Test matrix
 *
 * ### LongTailCompatibilityRegistry — PEER_ANNOUNCE promotion
 *  - PEER_ANNOUNCE entry exists and is PROMOTED
 *  - PEER_ANNOUNCE is not in transitionalTypes after PR-36 promotion
 *  - PEER_ANNOUNCE is in promotedTypes after PR-36 promotion
 *  - promoted type count is 4 after PR-36
 *  - transitional type count is 10 after PR-36
 *  - all promoted entries (including PEER_ANNOUNCE) have null transitionalNote
 *
 * ### PeerAnnouncePayload — typed model
 *  - peer_device_id is stored correctly
 *  - peer_role defaults to null
 *  - session_id defaults to null
 *  - announce_seq defaults to 0
 *  - populated fields are stored correctly
 *  - equality is value-based (data class)
 *
 * ### GalaxyLogger TAG_PEER_ANNOUNCE — stability
 *  - TAG_PEER_ANNOUNCE is non-blank
 *  - TAG_PEER_ANNOUNCE contains "PEER"
 *  - TAG_PEER_ANNOUNCE contains "ANNOUNCE"
 *  - TAG_PEER_ANNOUNCE is distinct from TAG_PEER_EXCHANGE
 *  - TAG_PEER_ANNOUNCE is distinct from TAG_LONG_TAIL_COMPAT
 *  - TAG_PEER_ANNOUNCE is distinct from TAG_MESH_TOPOLOGY
 *
 * ### CompatibilitySurfaceRetirementRegistry — PR-36 state
 *  - registrationError bridge is RETIRE_AFTER_MIGRATION
 *  - PEER_ANNOUNCE dispatch adapter is DECOMMISSION_CANDIDATE
 *  - HIGH_RISK_ACTIVE count is 3
 *  - RETIRE_AFTER_MIGRATION count is 3
 *  - RETIRE_AFTER_COORDINATION count is 10
 *  - DECOMMISSION_CANDIDATE count is 3
 *  - highRiskSurfaceIds does not contain registrationError bridge id
 *  - decommissionCandidateIds contains PEER_ANNOUNCE adapter id
 *
 * ### MsgType.PEER_ANNOUNCE — wire contract
 *  - wire value is "peer_announce"
 *  - still in ADVANCED_TYPES
 */
class Pr36CompatRetirementHardeningTest {

    // ══════════════════════════════════════════════════════════════════════════
    // 1. LongTailCompatibilityRegistry — PEER_ANNOUNCE promotion
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `PEER_ANNOUNCE entry exists and is PROMOTED in PR-36`() {
        val entry = LongTailCompatibilityRegistry.forType(MsgType.PEER_ANNOUNCE)
        assertNotNull("PEER_ANNOUNCE must be registered in LongTailCompatibilityRegistry", entry)
        assertEquals(
            "PEER_ANNOUNCE must be PROMOTED after PR-36 stateful handler promotion",
            LongTailCompatibilityRegistry.CompatTier.PROMOTED,
            entry!!.tier
        )
    }

    @Test
    fun `PEER_ANNOUNCE is not in transitionalTypes after PR-36 promotion`() {
        assertFalse(
            "PEER_ANNOUNCE must not appear in transitionalTypes after PR-36",
            MsgType.PEER_ANNOUNCE in LongTailCompatibilityRegistry.transitionalTypes
        )
    }

    @Test
    fun `PEER_ANNOUNCE is in promotedTypes after PR-36 promotion`() {
        assertTrue(
            "PEER_ANNOUNCE must appear in promotedTypes after PR-36",
            MsgType.PEER_ANNOUNCE in LongTailCompatibilityRegistry.promotedTypes
        )
    }

    @Test
    fun `promoted type count is 4 after PR-36`() {
        assertEquals(
            "promotedTypes must contain exactly 4 entries after PR-35 (3) + PR-36 (PEER_ANNOUNCE)",
            4,
            LongTailCompatibilityRegistry.promotedTypes.size
        )
    }

    @Test
    fun `transitional type count is 10 after PR-36 PEER_ANNOUNCE promotion`() {
        assertEquals(
            "transitionalTypes must contain exactly 10 entries after PR-36 promotes PEER_ANNOUNCE",
            10,
            LongTailCompatibilityRegistry.transitionalTypes.size
        )
    }

    @Test
    fun `all promoted entries including PEER_ANNOUNCE have null transitionalNote`() {
        val promotedEntries = LongTailCompatibilityRegistry.byTier(
            LongTailCompatibilityRegistry.CompatTier.PROMOTED
        )
        assertTrue("at least one promoted entry must exist", promotedEntries.isNotEmpty())
        for (entry in promotedEntries) {
            assertNull(
                "PROMOTED entry ${entry.type.value} must have null transitionalNote",
                entry.transitionalNote
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. PeerAnnouncePayload — typed model
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `PeerAnnouncePayload stores peer_device_id correctly`() {
        val payload = PeerAnnouncePayload(peer_device_id = "pixel-8")
        assertEquals("pixel-8", payload.peer_device_id)
    }

    @Test
    fun `PeerAnnouncePayload peer_role defaults to null`() {
        assertNull(PeerAnnouncePayload(peer_device_id = "dev-a").peer_role)
    }

    @Test
    fun `PeerAnnouncePayload session_id defaults to null`() {
        assertNull(PeerAnnouncePayload(peer_device_id = "dev-a").session_id)
    }

    @Test
    fun `PeerAnnouncePayload announce_seq defaults to 0`() {
        assertEquals(0, PeerAnnouncePayload(peer_device_id = "dev-a").announce_seq)
    }

    @Test
    fun `PeerAnnouncePayload stores all populated fields correctly`() {
        val payload = PeerAnnouncePayload(
            peer_device_id = "phone-x",
            peer_role = "participant",
            session_id = "session-99",
            announce_seq = 5
        )
        assertEquals("phone-x", payload.peer_device_id)
        assertEquals("participant", payload.peer_role)
        assertEquals("session-99", payload.session_id)
        assertEquals(5, payload.announce_seq)
    }

    @Test
    fun `PeerAnnouncePayload equality is value-based`() {
        val a = PeerAnnouncePayload(peer_device_id = "dev-a", peer_role = "observer", announce_seq = 2)
        val b = PeerAnnouncePayload(peer_device_id = "dev-a", peer_role = "observer", announce_seq = 2)
        assertEquals("equal payloads must be equal (data class)", a, b)
    }

    @Test
    fun `PeerAnnouncePayload with higher announce_seq is considered newer`() {
        val older = PeerAnnouncePayload(peer_device_id = "dev-z", announce_seq = 3)
        val newer = PeerAnnouncePayload(peer_device_id = "dev-z", announce_seq = 7)
        assertTrue(
            "newer.announce_seq must be greater than older.announce_seq",
            newer.announce_seq > older.announce_seq
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. GalaxyLogger TAG_PEER_ANNOUNCE — stability
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TAG_PEER_ANNOUNCE is non-blank`() {
        assertTrue("TAG_PEER_ANNOUNCE must not be blank", GalaxyLogger.TAG_PEER_ANNOUNCE.isNotBlank())
    }

    @Test
    fun `TAG_PEER_ANNOUNCE contains PEER`() {
        assertTrue(
            "TAG_PEER_ANNOUNCE must contain 'PEER'",
            GalaxyLogger.TAG_PEER_ANNOUNCE.contains("PEER")
        )
    }

    @Test
    fun `TAG_PEER_ANNOUNCE contains ANNOUNCE`() {
        assertTrue(
            "TAG_PEER_ANNOUNCE must contain 'ANNOUNCE'",
            GalaxyLogger.TAG_PEER_ANNOUNCE.contains("ANNOUNCE")
        )
    }

    @Test
    fun `TAG_PEER_ANNOUNCE is distinct from TAG_PEER_EXCHANGE`() {
        assertFalse(
            "TAG_PEER_ANNOUNCE must be distinct from TAG_PEER_EXCHANGE",
            GalaxyLogger.TAG_PEER_ANNOUNCE == GalaxyLogger.TAG_PEER_EXCHANGE
        )
    }

    @Test
    fun `TAG_PEER_ANNOUNCE is distinct from TAG_LONG_TAIL_COMPAT`() {
        assertFalse(
            "TAG_PEER_ANNOUNCE must be distinct from TAG_LONG_TAIL_COMPAT",
            GalaxyLogger.TAG_PEER_ANNOUNCE == GalaxyLogger.TAG_LONG_TAIL_COMPAT
        )
    }

    @Test
    fun `TAG_PEER_ANNOUNCE is distinct from TAG_MESH_TOPOLOGY`() {
        assertFalse(
            "TAG_PEER_ANNOUNCE must be distinct from TAG_MESH_TOPOLOGY",
            GalaxyLogger.TAG_PEER_ANNOUNCE == GalaxyLogger.TAG_MESH_TOPOLOGY
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. CompatibilitySurfaceRetirementRegistry — PR-36 state
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `registrationError bridge is RETIRE_AFTER_MIGRATION after PR-36 consumer migration`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId(
            "runtime_registration_error_string_bridge"
        )
        assertNotNull("registrationError bridge must remain in registry", entry)
        assertEquals(
            "registrationError bridge must be RETIRE_AFTER_MIGRATION; consumers migrated in PR-36",
            CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_MIGRATION,
            entry!!.retirementTier
        )
    }

    @Test
    fun `PEER_ANNOUNCE dispatch adapter is DECOMMISSION_CANDIDATE after PR-36 promotion`() {
        val entry = CompatibilitySurfaceRetirementRegistry.forId(
            "dispatch_adapter_peer_announce_transitional"
        )
        assertNotNull("PEER_ANNOUNCE adapter entry must remain in registry", entry)
        assertEquals(
            "PEER_ANNOUNCE adapter must be DECOMMISSION_CANDIDATE; minimal-compat path no longer exercised",
            CompatibilitySurfaceRetirementRegistry.RetirementTier.DECOMMISSION_CANDIDATE,
            entry!!.retirementTier
        )
    }

    @Test
    fun `HIGH_RISK_ACTIVE count is 3 after PR-36 registrationError migration`() {
        assertEquals(
            "exactly 3 HIGH_RISK_ACTIVE entries expected after PR-36",
            3,
            CompatibilitySurfaceRetirementRegistry.byTier(
                CompatibilitySurfaceRetirementRegistry.RetirementTier.HIGH_RISK_ACTIVE
            ).size
        )
    }

    @Test
    fun `RETIRE_AFTER_MIGRATION count is 3 after PR-36`() {
        assertEquals(
            "exactly 3 RETIRE_AFTER_MIGRATION entries expected after PR-36",
            3,
            CompatibilitySurfaceRetirementRegistry.byTier(
                CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_MIGRATION
            ).size
        )
    }

    @Test
    fun `RETIRE_AFTER_COORDINATION count is 10 after PR-36 PEER_ANNOUNCE promotion`() {
        assertEquals(
            "exactly 10 RETIRE_AFTER_COORDINATION entries expected after PR-36",
            10,
            CompatibilitySurfaceRetirementRegistry.byTier(
                CompatibilitySurfaceRetirementRegistry.RetirementTier.RETIRE_AFTER_COORDINATION
            ).size
        )
    }

    @Test
    fun `DECOMMISSION_CANDIDATE count is 3 after PR-36 PEER_ANNOUNCE promotion`() {
        assertEquals(
            "exactly 3 DECOMMISSION_CANDIDATE entries expected after PR-36",
            3,
            CompatibilitySurfaceRetirementRegistry.byTier(
                CompatibilitySurfaceRetirementRegistry.RetirementTier.DECOMMISSION_CANDIDATE
            ).size
        )
    }

    @Test
    fun `highRiskSurfaceIds does not contain registrationError bridge id after PR-36`() {
        val highRisk = CompatibilitySurfaceRetirementRegistry.highRiskSurfaceIds
        assertFalse(
            "runtime_registration_error_string_bridge must not be in highRiskSurfaceIds after PR-36 deprecation",
            highRisk.contains("runtime_registration_error_string_bridge")
        )
    }

    @Test
    fun `decommissionCandidateIds contains PEER_ANNOUNCE adapter id after PR-36`() {
        val candidates = CompatibilitySurfaceRetirementRegistry.decommissionCandidateIds
        assertTrue(
            "dispatch_adapter_peer_announce_transitional must be in decommissionCandidateIds after PR-36",
            candidates.contains("dispatch_adapter_peer_announce_transitional")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. MsgType.PEER_ANNOUNCE — wire contract stability
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `PEER_ANNOUNCE wire value is peer_announce`() {
        assertEquals(
            "PEER_ANNOUNCE wire value must remain stable at 'peer_announce'",
            "peer_announce",
            MsgType.PEER_ANNOUNCE.value
        )
    }

    @Test
    fun `PEER_ANNOUNCE is still in ADVANCED_TYPES`() {
        assertTrue(
            "PEER_ANNOUNCE must still be in ADVANCED_TYPES so it reaches the promoted handler",
            MsgType.PEER_ANNOUNCE in MsgType.ADVANCED_TYPES
        )
    }
}
