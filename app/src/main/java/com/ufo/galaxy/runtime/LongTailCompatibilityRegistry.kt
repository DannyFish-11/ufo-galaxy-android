package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.MsgType

/**
 * PR-35 — Long-tail compatibility surface registry.
 *
 * Explicitly inventories every AIP v3 message type that currently operates (or previously
 * operated) in minimal-compat / generic-forward mode, and classifies it by its intended
 * future status.
 *
 * ## Purpose
 *
 * The main dispatch chain is mature, but several long-tail multi-device capability and
 * protocol paths still depend on placeholder-ACK responses and generic-forward behavior.
 * This registry makes the system honest about which multi-device flows are truly
 * closed-loop and which remain transitional:
 *
 * - Advanced message families (RELAY, FORWARD, REPLY, BROADCAST)
 * - File-transfer-adjacent surfaces (RAG_QUERY, CODE_EXECUTE)
 * - Peer-exchange and mesh-topology coordination (PEER_EXCHANGE, MESH_TOPOLOGY, PEER_ANNOUNCE)
 * - Coordination and remote-control surfaces (COORD_SYNC, WAKE_EVENT, SESSION_MIGRATE)
 * - Distributed locking (LOCK, UNLOCK)
 *
 * ## Classification tiers
 *
 * | [CompatTier]                  | Meaning                                                              |
 * |-------------------------------|----------------------------------------------------------------------|
 * | [CompatTier.CANONICAL]        | Fully implemented in the main dispatch chain; not a long-tail path.  |
 * | [CompatTier.PROMOTED]         | Promoted in PR-35 to dedicated stateful handling / lifecycle semantics. |
 * | [CompatTier.TRANSITIONAL]     | Minimal-compat placeholder; explicitly not intended for extension.   |
 *
 * ## Highest-value promoted flows (PR-35)
 *
 * The three highest-value long-tail flows are promoted from minimal-compat to dedicated
 * stateful handling in PR-35:
 *
 * 1. **[MsgType.PEER_EXCHANGE]** — Peer capability exchange: parsed into
 *    [com.ufo.galaxy.protocol.PeerExchangePayload]; capability record retained per peer;
 *    structured ACK sent.
 *
 * 2. **[MsgType.MESH_TOPOLOGY]** — Mesh topology update: parsed into
 *    [com.ufo.galaxy.protocol.MeshTopologyPayload]; last-known topology snapshot retained;
 *    structured ACK sent.
 *
 * 3. **[MsgType.COORD_SYNC]** — Coordination sync tick: promoted from generic ACK to
 *    sequence-aware [com.ufo.galaxy.protocol.CoordSyncAckPayload] response with session
 *    tick counter.
 *
 * ## Transitional surfaces
 *
 * All remaining minimal-compat paths are explicitly marked [CompatTier.TRANSITIONAL].
 * These surfaces **must not** be extended as canonical architecture.  Future work should
 * promote individual entries to [CompatTier.PROMOTED] or [CompatTier.CANONICAL] rather
 * than adding logic to the generic minimal-compat path.
 */
object LongTailCompatibilityRegistry {

    /**
     * Classification tier for a long-tail compatibility surface.
     *
     * @see LongTailCompatibilityRegistry
     */
    enum class CompatTier {
        /**
         * Fully implemented in the main dispatch chain.
         * Not a long-tail surface; included here for completeness.
         */
        CANONICAL,

        /**
         * Promoted in PR-35 to dedicated stateful handling or stronger lifecycle semantics.
         * Has a dedicated handler function, a typed payload model, and per-session state.
         */
        PROMOTED,

        /**
         * Minimal-compat placeholder — logged only or generic-ACK only.
         * Explicitly marked as transitional; **must not** be extended as canonical architecture.
         */
        TRANSITIONAL
    }

    /**
     * Registry entry for a single long-tail message type.
     *
     * @param type             The [MsgType] this entry describes.
     * @param tier             The current [CompatTier] classification.
     * @param description      Human-readable description of what this message type does.
     * @param transitionalNote For [CompatTier.TRANSITIONAL] entries: explains what the
     *                         minimal-compat path does and why it must not be extended.
     *                         `null` for [CompatTier.CANONICAL] and [CompatTier.PROMOTED] entries.
     */
    data class LongTailEntry(
        val type: MsgType,
        val tier: CompatTier,
        val description: String,
        val transitionalNote: String? = null
    )

    /**
     * Complete registry of all long-tail message types, ordered by tier and type.
     *
     * Consumers can query entries via [byTier], [forType], [transitionalTypes], or
     * [promotedTypes].
     */
    val entries: List<LongTailEntry> = listOf(

        // ── Promoted: highest-value long-tail flows promoted to stateful handling in PR-35 ──

        LongTailEntry(
            type = MsgType.PEER_EXCHANGE,
            tier = CompatTier.PROMOTED,
            description = "Peer capability exchange between devices in a session. " +
                "PR-35: parsed into PeerExchangePayload; peer capability record retained " +
                "per source device; structured ACK sent. " +
                "Previous status: logged only (minimal-compat)."
        ),
        LongTailEntry(
            type = MsgType.MESH_TOPOLOGY,
            tier = CompatTier.PROMOTED,
            description = "Mesh topology update: gateway pushes node list / adjacency snapshot. " +
                "PR-35: parsed into MeshTopologyPayload; last-known topology snapshot retained " +
                "and topology_seq tracked; structured ACK sent. " +
                "Previous status: logged only (minimal-compat)."
        ),
        LongTailEntry(
            type = MsgType.COORD_SYNC,
            tier = CompatTier.PROMOTED,
            description = "Coordination sync tick between the coordinator and participants. " +
                "PR-35: promoted from generic ACK to sequence-aware CoordSyncAckPayload response " +
                "with per-session tick counter. " +
                "Previous status: generic ACK sent (minimal-compat)."
        ),

        // ── Canonical: types fully implemented in the main dispatch chain ────────────────

        LongTailEntry(
            type = MsgType.HYBRID_EXECUTE,
            tier = CompatTier.CANONICAL,
            description = "Hybrid (partial-local / partial-remote) task execution. " +
                "Payload parsed into HybridExecutePayload; degrade reply sent with structured " +
                "reason when the full hybrid executor is unavailable."
        ),
        LongTailEntry(
            type = MsgType.TAKEOVER_REQUEST,
            tier = CompatTier.CANONICAL,
            description = "Main runtime asks Android to take over execution of an in-flight task. " +
                "Full takeover executor and delegated signal pipeline implemented in PR-5+."
        ),

        // ── Transitional: minimal-compat surfaces — explicitly not for extension ──────────

        LongTailEntry(
            type = MsgType.RELAY,
            tier = CompatTier.TRANSITIONAL,
            description = "Gateway relays a message from another node to this device.",
            transitionalNote = "Generic ACK sent on receipt; no relay-chain logic implemented. " +
                "Transitional — must not be extended as canonical architecture."
        ),
        LongTailEntry(
            type = MsgType.FORWARD,
            tier = CompatTier.TRANSITIONAL,
            description = "Gateway requests this device to forward a task to another peer.",
            transitionalNote = "Logged only; no peer-routing logic implemented. " +
                "Transitional — must not be extended as canonical architecture."
        ),
        LongTailEntry(
            type = MsgType.REPLY,
            tier = CompatTier.TRANSITIONAL,
            description = "Gateway sends a directed reply to a previous device-originated request.",
            transitionalNote = "Logged only; no reply-state machine implemented. " +
                "Transitional — must not be extended as canonical architecture."
        ),
        LongTailEntry(
            type = MsgType.RAG_QUERY,
            tier = CompatTier.TRANSITIONAL,
            description = "Gateway sends a retrieval-augmented generation query to the device.",
            transitionalNote = "Empty result returned; no RAG pipeline implemented. " +
                "Transitional — must not be extended as canonical architecture."
        ),
        LongTailEntry(
            type = MsgType.CODE_EXECUTE,
            tier = CompatTier.TRANSITIONAL,
            description = "Gateway requests on-device code execution (e.g. Python snippet).",
            transitionalNote = "Error result returned; no sandbox implemented. " +
                "Transitional — must not be extended as canonical architecture."
        ),
        LongTailEntry(
            type = MsgType.PEER_ANNOUNCE,
            tier = CompatTier.TRANSITIONAL,
            description = "Gateway announces a new peer device joining the session.",
            transitionalNote = "Logged only; no peer-state tracking implemented. " +
                "Transitional — must not be extended as canonical architecture."
        ),
        LongTailEntry(
            type = MsgType.WAKE_EVENT,
            tier = CompatTier.TRANSITIONAL,
            description = "Gateway sends a wake event to resume an idle or suspended device.",
            transitionalNote = "Generic ACK sent; no suspend/resume state machine implemented. " +
                "Transitional — must not be extended as canonical architecture."
        ),
        LongTailEntry(
            type = MsgType.SESSION_MIGRATE,
            tier = CompatTier.TRANSITIONAL,
            description = "Gateway requests session state migration to another device.",
            transitionalNote = "Degrade/reject reply sent; no full migration logic implemented. " +
                "Transitional — must not be extended as canonical architecture."
        ),
        LongTailEntry(
            type = MsgType.BROADCAST,
            tier = CompatTier.TRANSITIONAL,
            description = "Gateway broadcasts a message to all devices in a session.",
            transitionalNote = "Logged only; no broadcast fan-out implemented. " +
                "Transitional — must not be extended as canonical architecture."
        ),
        LongTailEntry(
            type = MsgType.LOCK,
            tier = CompatTier.TRANSITIONAL,
            description = "Gateway requests a distributed resource lock.",
            transitionalNote = "Generic ACK sent; no lock-manager implemented. " +
                "Transitional — must not be extended as canonical architecture."
        ),
        LongTailEntry(
            type = MsgType.UNLOCK,
            tier = CompatTier.TRANSITIONAL,
            description = "Gateway releases a distributed resource lock.",
            transitionalNote = "Generic ACK sent; no lock-manager implemented. " +
                "Transitional — must not be extended as canonical architecture."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /** Returns all entries classified under [tier]. */
    fun byTier(tier: CompatTier): List<LongTailEntry> = entries.filter { it.tier == tier }

    /**
     * Returns the [LongTailEntry] for [type], or `null` if the type is not registered.
     *
     * Note: not every [MsgType] is registered here — only long-tail / compatibility-surface
     * types.  Canonical primary-path types (TASK_ASSIGN, GOAL_EXECUTION, etc.) are not
     * included because they do not operate through the compatibility surface.
     */
    fun forType(type: MsgType): LongTailEntry? = entries.find { it.type == type }

    /**
     * Set of all message types classified as [CompatTier.TRANSITIONAL].
     *
     * These types operate in minimal-compat mode and **must not** be extended as canonical
     * architecture.  Callers can use this set to assert that new dispatch logic is never
     * added to transitional types.
     */
    val transitionalTypes: Set<MsgType>
        get() = byTier(CompatTier.TRANSITIONAL).map { it.type }.toSet()

    /**
     * Set of all message types promoted from minimal-compat to dedicated stateful handling
     * in PR-35.
     */
    val promotedTypes: Set<MsgType>
        get() = byTier(CompatTier.PROMOTED).map { it.type }.toSet()
}
