package com.ufo.galaxy.runtime

/**
 * PR-10 — Explicit compatibility-surface retirement plan.
 *
 * Inventories every high-risk compatibility surface in the Android repository,
 * classifies each by [RetirementTier] and [SurfaceKind], and records the
 * canonical replacement and retirement gate that must be cleared before the
 * surface can be removed.
 *
 * ## Purpose
 *
 * The system carries several categories of compatibility surfaces that remain
 * operationally visible:
 *
 * - **Runtime bridges** — legacy typed or untyped projections kept alive while
 *   consumers migrate to canonical typed replacements.
 * - **Protocol alias surfaces** — legacy wire-value normalization paths retained
 *   for backward compatibility with older message producers.
 * - **Dispatch adapters** — minimal-compat / placeholder message-type handlers
 *   retained in the dispatch chain while canonical implementations are pending.
 * - **Protocol compatibility surfaces** — shared protocol surfaces still classified
 *   as [com.ufo.galaxy.protocol.ProtocolSurfaceClass.TRANSITIONAL_COMPATIBILITY].
 * - **Deprecated API methods** — deprecated REST/API methods retained for diagnostic
 *   or compatibility purposes only.
 *
 * Without an explicit inventory, these surfaces remain operationally visible and
 * may be mistaken for canonical governance layers, causing future work to route
 * through legacy or transitional paths even after canonical replacements exist.
 *
 * ## Retirement tiers
 *
 * | [RetirementTier]                          | Meaning                                                                    |
 * |-------------------------------------------|----------------------------------------------------------------------------|
 * | [RetirementTier.HIGH_RISK_ACTIVE]         | Active and operationally visible. Highest risk of canonical confusion.     |
 * | [RetirementTier.RETIRE_AFTER_MIGRATION]   | Canonical replacement exists. Retire once all consumers migrate.           |
 * | [RetirementTier.RETIRE_AFTER_COORDINATION]| Retirement gated on cross-repo or external coordination.                   |
 * | [RetirementTier.DECOMMISSION_CANDIDATE]   | Lowest remaining utility. Remove in next cleanup pass when gate clears.    |
 *
 * ## Surface kinds
 *
 * | [SurfaceKind]                    | Category                                                                |
 * |----------------------------------|-------------------------------------------------------------------------|
 * | [SurfaceKind.RUNTIME_BRIDGE]     | Legacy typed/untyped runtime projection or compatibility bridge.        |
 * | [SurfaceKind.PROTOCOL_ALIAS]     | Wire-value alias normalization kept for legacy message producers.       |
 * | [SurfaceKind.DISPATCH_ADAPTER]   | Placeholder / minimal-compat message-type dispatch adapter.             |
 * | [SurfaceKind.PROTOCOL_SURFACE]   | Shared protocol surface still classified as TRANSITIONAL_COMPATIBILITY. |
 * | [SurfaceKind.DEPRECATED_API]     | Deprecated REST or API method retained for compatibility purposes.      |
 */
object CompatibilitySurfaceRetirementRegistry {

    /**
     * Retirement priority tier for a compatibility surface.
     */
    enum class RetirementTier {
        /**
         * The surface is active and operationally visible enough to be mistaken for a
         * canonical governance layer.  Has a canonical replacement but no retirement
         * timeline set.  **Must not** be extended.  New code must use the canonical
         * replacement recorded in [CompatibilitySurfaceEntry.canonicalReplacement].
         */
        HIGH_RISK_ACTIVE,

        /**
         * Canonical replacement exists and is ready.  Retirement is gated on migration
         * completion — all known consumers must switch to the canonical replacement before
         * the compatibility surface can be removed.  No external coordination required.
         */
        RETIRE_AFTER_MIGRATION,

        /**
         * Retirement requires explicit cross-repository or center-Android coordination.
         * The Android side cannot safely remove the surface unilaterally.  Retirement
         * is blocked until the external gate recorded in [CompatibilitySurfaceEntry.retirementGate]
         * is cleared.
         */
        RETIRE_AFTER_COORDINATION,

        /**
         * Lowest remaining utility, no active extension, no new dependencies expected.
         * Should be removed in the next cleanup pass once the gate condition is met.
         */
        DECOMMISSION_CANDIDATE
    }

    /**
     * Structural category of a compatibility surface.
     */
    enum class SurfaceKind {
        /** Legacy typed or untyped runtime projection kept for backward compatibility. */
        RUNTIME_BRIDGE,

        /** Wire-value alias normalization path for legacy message producers. */
        PROTOCOL_ALIAS,

        /** Placeholder / minimal-compat message-type dispatch adapter. */
        DISPATCH_ADAPTER,

        /** Shared protocol surface still classified as TRANSITIONAL_COMPATIBILITY. */
        PROTOCOL_SURFACE,

        /** Deprecated REST or API method retained for diagnostic / compatibility purposes. */
        DEPRECATED_API
    }

    /**
     * A single inventoried compatibility surface.
     *
     * @param surfaceId            Stable machine-readable identifier for this surface.
     * @param surfaceKind          Structural category of the surface.
     * @param description          Human-readable description of what the surface does.
     * @param canonicalReplacement The canonical path that new code must use instead.
     * @param retirementTier       Priority tier: how urgently should this surface be retired?
     * @param retirementGate       What must happen before this surface can be removed?
     * @param notes                Optional supplementary notes.
     */
    data class CompatibilitySurfaceEntry(
        val surfaceId: String,
        val surfaceKind: SurfaceKind,
        val description: String,
        val canonicalReplacement: String,
        val retirementTier: RetirementTier,
        val retirementGate: String,
        val notes: String? = null
    )

    /**
     * Complete inventory of all catalogued high-risk compatibility surfaces, ordered by tier.
     *
     * Consumers can query entries via [byTier], [byKind], [forId], [highRiskSurfaceIds], and
     * [decommissionCandidateIds].
     */
    val entries: List<CompatibilitySurfaceEntry> = listOf(

        // ── HIGH_RISK_ACTIVE: operationally visible, risk of mistaken canonical use ─────

        CompatibilitySurfaceEntry(
            surfaceId = "runtime_registration_error_string_bridge",
            surfaceKind = SurfaceKind.RUNTIME_BRIDGE,
            description = "RuntimeController.registrationError: SharedFlow<String> — legacy untyped " +
                "string error emission. Operationally visible to UI and diagnostic collectors. " +
                "Cannot be distinguished from canonical error surfaces without reading source.",
            canonicalReplacement = "RuntimeController.setupError (SharedFlow<CrossDeviceSetupError>)",
            retirementTier = RetirementTier.HIGH_RISK_ACTIVE,
            retirementGate = "All UI and diagnostics collectors must migrate to setupError " +
                "before this bridge is removed.",
            notes = "Retained for backward compatibility (PR-27). Do not add new observers."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "runtime_host_session_legacy_map_bridge",
            surfaceKind = SurfaceKind.RUNTIME_BRIDGE,
            description = "RuntimeController.currentSessionSnapshot(): Map<String,Any>? — legacy " +
                "untyped map projection of the host session. Operationally visible to host-facing " +
                "consumers. The untyped return type obscures the canonical typed projection.",
            canonicalReplacement = "RuntimeController.hostSessionSnapshot " +
                "(StateFlow<AttachedRuntimeHostSessionSnapshot?>) and " +
                "RuntimeController.currentHostSessionSnapshot()",
            retirementTier = RetirementTier.HIGH_RISK_ACTIVE,
            retirementGate = "All host-facing consumers must migrate to hostSessionSnapshot / " +
                "currentHostSessionSnapshot.",
            notes = "Retained for legacy host-facing consumers (PR-17). Do not add new call sites."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "session_identifier_carrier_transitional_surface",
            surfaceKind = SurfaceKind.PROTOCOL_SURFACE,
            description = "ProtocolSurface.SESSION_IDENTIFIER_CARRIER: classified as " +
                "TRANSITIONAL_COMPATIBILITY. Android session identifier carriers are not yet " +
                "fully unified with the center canonical vocabulary, creating drift risk.",
            canonicalReplacement = "Canonical session family vocabulary: CanonicalSessionFamily + " +
                "UgcpSharedSchemaAlignment.identityAlignments",
            retirementTier = RetirementTier.HIGH_RISK_ACTIVE,
            retirementGate = "Requires center-Android coordination to align session identifier " +
                "carrier naming with the canonical session family vocabulary.",
            notes = "See docs/ugcp/PROTOCOL_CONSISTENCY_RULES.md for the convergence path."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "staged_mesh_execution_status_transitional_surface",
            surfaceKind = SurfaceKind.PROTOCOL_SURFACE,
            description = "ProtocolSurface.STAGED_MESH_EXECUTION_STATUS: classified as " +
                "TRANSITIONAL_COMPATIBILITY. StagedMeshParticipationResult.ExecutionStatus " +
                "wire values are not yet fully aligned with the center canonical terminal " +
                "status vocabulary.",
            canonicalReplacement = "Canonical terminal status vocabulary: " +
                "UgcpProtocolConsistencyRules.terminalStateVocabularyRule",
            retirementTier = RetirementTier.HIGH_RISK_ACTIVE,
            retirementGate = "Requires center-Android coordination to map ExecutionStatus wire " +
                "values to canonical terminal status values.",
            notes = "ExecutionStatus.wireValue constants define the retirement scope."
        ),

        // ── RETIRE_AFTER_MIGRATION: canonical replacement ready; gated on migration ──────

        CompatibilitySurfaceEntry(
            surfaceId = "galaxy_api_client_register_device_deprecated",
            surfaceKind = SurfaceKind.DEPRECATED_API,
            description = "GalaxyApiClient.registerDevice: legacy REST device-registration endpoint. " +
                "Annotated @Deprecated. No new call sites should be added.",
            canonicalReplacement = "GalaxyWebSocketClient automatic capability_report on WS connect",
            retirementTier = RetirementTier.RETIRE_AFTER_MIGRATION,
            retirementGate = "Remove all remaining call sites; confirm no production usage path remains.",
            notes = "Retained for diagnostic compatibility. See maintainer-guide.md."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "galaxy_api_client_send_heartbeat_deprecated",
            surfaceKind = SurfaceKind.DEPRECATED_API,
            description = "GalaxyApiClient.sendHeartbeat: legacy REST heartbeat endpoint. " +
                "Annotated @Deprecated. No new call sites should be added.",
            canonicalReplacement = "GalaxyWebSocketClient automatic heartbeat every 30 s",
            retirementTier = RetirementTier.RETIRE_AFTER_MIGRATION,
            retirementGate = "Remove all remaining call sites; confirm no production usage path remains.",
            notes = "Retained for diagnostic compatibility. See maintainer-guide.md."
        ),

        // ── RETIRE_AFTER_COORDINATION: external gate required before retirement ───────────

        CompatibilitySurfaceEntry(
            surfaceId = "legacy_msgtype_alias_normalization",
            surfaceKind = SurfaceKind.PROTOCOL_ALIAS,
            description = "MsgType.LEGACY_TYPE_MAP + MsgType.toV3Type(): normalizes legacy message " +
                "type strings sent by older gateway versions before canonical dispatch routing.",
            canonicalReplacement = "Canonical MsgType wire values sent directly by the gateway",
            retirementTier = RetirementTier.RETIRE_AFTER_COORDINATION,
            retirementGate = "Center must stop emitting legacy type aliases. " +
                "Requires coordinated gateway schema rollout.",
            notes = "Normalization boundary: runtime_ingress.type_normalization_and_tier_classification."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "lifecycle_status_normalization_map",
            surfaceKind = SurfaceKind.PROTOCOL_ALIAS,
            description = "UgcpSharedSchemaAlignment.lifecycleStatusNormalizations: maps legacy " +
                "lifecycle/terminal status strings (e.g. 'completed' → 'success', 'failed' → 'error') " +
                "to canonical vocabulary for result processing.",
            canonicalReplacement = "Canonical terminal status vocabulary: " +
                "success / error / cancelled / timeout / rejected / partial / disabled",
            retirementTier = RetirementTier.RETIRE_AFTER_COORDINATION,
            retirementGate = "Transfer result producers (gateway and remote runtimes) must emit " +
                "canonical terminal statuses only. Requires coordinated rollout.",
            notes = "Normalization boundary: transfer.result_kind_lifecycle_status_normalization."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "dispatch_adapter_relay_transitional",
            surfaceKind = SurfaceKind.DISPATCH_ADAPTER,
            description = "MsgType.RELAY minimal-compat handler: generic ACK sent on receipt; " +
                "no relay-chain logic implemented.",
            canonicalReplacement = "Dedicated relay executor (not yet implemented)",
            retirementTier = RetirementTier.RETIRE_AFTER_COORDINATION,
            retirementGate = "Center must formally retire RELAY message usage or promote to " +
                "dedicated executor.",
            notes = "See LongTailCompatibilityRegistry.forType(MsgType.RELAY)."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "dispatch_adapter_forward_transitional",
            surfaceKind = SurfaceKind.DISPATCH_ADAPTER,
            description = "MsgType.FORWARD minimal-compat handler: logged only; " +
                "no peer-routing logic implemented.",
            canonicalReplacement = "Dedicated peer-routing executor (not yet implemented)",
            retirementTier = RetirementTier.RETIRE_AFTER_COORDINATION,
            retirementGate = "Center must formally retire FORWARD usage or promote to dedicated executor.",
            notes = "See LongTailCompatibilityRegistry.forType(MsgType.FORWARD)."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "dispatch_adapter_reply_transitional",
            surfaceKind = SurfaceKind.DISPATCH_ADAPTER,
            description = "MsgType.REPLY minimal-compat handler: logged only; " +
                "no reply-state machine implemented.",
            canonicalReplacement = "Dedicated reply-state machine (not yet implemented)",
            retirementTier = RetirementTier.RETIRE_AFTER_COORDINATION,
            retirementGate = "Center must formally retire REPLY usage or promote to dedicated executor.",
            notes = "See LongTailCompatibilityRegistry.forType(MsgType.REPLY)."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "dispatch_adapter_rag_query_transitional",
            surfaceKind = SurfaceKind.DISPATCH_ADAPTER,
            description = "MsgType.RAG_QUERY minimal-compat handler: empty result returned; " +
                "no RAG pipeline implemented.",
            canonicalReplacement = "Dedicated on-device RAG pipeline (not yet implemented)",
            retirementTier = RetirementTier.RETIRE_AFTER_COORDINATION,
            retirementGate = "Center must provide RAG pipeline specification or formally retire " +
                "RAG_QUERY usage.",
            notes = "See LongTailCompatibilityRegistry.forType(MsgType.RAG_QUERY)."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "dispatch_adapter_code_execute_transitional",
            surfaceKind = SurfaceKind.DISPATCH_ADAPTER,
            description = "MsgType.CODE_EXECUTE minimal-compat handler: error result returned; " +
                "no sandbox implemented.",
            canonicalReplacement = "Dedicated on-device code sandbox (not yet implemented)",
            retirementTier = RetirementTier.RETIRE_AFTER_COORDINATION,
            retirementGate = "Center must provide sandbox specification or formally retire " +
                "CODE_EXECUTE usage.",
            notes = "See LongTailCompatibilityRegistry.forType(MsgType.CODE_EXECUTE)."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "dispatch_adapter_peer_announce_transitional",
            surfaceKind = SurfaceKind.DISPATCH_ADAPTER,
            description = "MsgType.PEER_ANNOUNCE minimal-compat handler: logged only; " +
                "no peer-state tracking implemented.",
            canonicalReplacement = "Dedicated peer-state tracker (not yet implemented)",
            retirementTier = RetirementTier.RETIRE_AFTER_COORDINATION,
            retirementGate = "Center must formally retire PEER_ANNOUNCE or promote to dedicated tracker.",
            notes = "See LongTailCompatibilityRegistry.forType(MsgType.PEER_ANNOUNCE)."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "dispatch_adapter_wake_event_transitional",
            surfaceKind = SurfaceKind.DISPATCH_ADAPTER,
            description = "MsgType.WAKE_EVENT minimal-compat handler: generic ACK sent; " +
                "no suspend/resume state machine implemented.",
            canonicalReplacement = "Dedicated suspend/resume state machine (not yet implemented)",
            retirementTier = RetirementTier.RETIRE_AFTER_COORDINATION,
            retirementGate = "Center must formally retire WAKE_EVENT or promote to dedicated executor.",
            notes = "See LongTailCompatibilityRegistry.forType(MsgType.WAKE_EVENT)."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "dispatch_adapter_session_migrate_transitional",
            surfaceKind = SurfaceKind.DISPATCH_ADAPTER,
            description = "MsgType.SESSION_MIGRATE minimal-compat handler: degrade/reject reply sent; " +
                "no full migration logic implemented.",
            canonicalReplacement = "Dedicated session migration executor (not yet implemented)",
            retirementTier = RetirementTier.RETIRE_AFTER_COORDINATION,
            retirementGate = "Center must provide migration specification or formally retire " +
                "SESSION_MIGRATE usage.",
            notes = "See LongTailCompatibilityRegistry.forType(MsgType.SESSION_MIGRATE)."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "dispatch_adapter_broadcast_transitional",
            surfaceKind = SurfaceKind.DISPATCH_ADAPTER,
            description = "MsgType.BROADCAST minimal-compat handler: logged only; " +
                "no broadcast fan-out implemented.",
            canonicalReplacement = "Dedicated broadcast fan-out executor (not yet implemented)",
            retirementTier = RetirementTier.RETIRE_AFTER_COORDINATION,
            retirementGate = "Center must formally retire BROADCAST or promote to dedicated executor.",
            notes = "See LongTailCompatibilityRegistry.forType(MsgType.BROADCAST)."
        ),

        // ── DECOMMISSION_CANDIDATE: minimal remaining utility; next cleanup pass ───────────

        CompatibilitySurfaceEntry(
            surfaceId = "dispatch_adapter_lock_transitional",
            surfaceKind = SurfaceKind.DISPATCH_ADAPTER,
            description = "MsgType.LOCK minimal-compat handler: generic ACK sent; " +
                "no distributed lock manager implemented. Low observed production frequency.",
            canonicalReplacement = "Dedicated distributed lock manager (not yet implemented)",
            retirementTier = RetirementTier.DECOMMISSION_CANDIDATE,
            retirementGate = "Center confirms LOCK is no longer sent in production, or " +
                "lock manager implemented.",
            notes = "See LongTailCompatibilityRegistry.forType(MsgType.LOCK)."
        ),

        CompatibilitySurfaceEntry(
            surfaceId = "dispatch_adapter_unlock_transitional",
            surfaceKind = SurfaceKind.DISPATCH_ADAPTER,
            description = "MsgType.UNLOCK minimal-compat handler: generic ACK sent; " +
                "no distributed lock manager implemented. Low observed production frequency.",
            canonicalReplacement = "Dedicated distributed lock manager (not yet implemented)",
            retirementTier = RetirementTier.DECOMMISSION_CANDIDATE,
            retirementGate = "Center confirms UNLOCK is no longer sent in production, or " +
                "lock manager implemented.",
            notes = "See LongTailCompatibilityRegistry.forType(MsgType.UNLOCK)."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /** Returns all entries classified under [tier]. */
    fun byTier(tier: RetirementTier): List<CompatibilitySurfaceEntry> =
        entries.filter { it.retirementTier == tier }

    /** Returns all entries classified under [kind]. */
    fun byKind(kind: SurfaceKind): List<CompatibilitySurfaceEntry> =
        entries.filter { it.surfaceKind == kind }

    /**
     * Returns the entry with [surfaceId], or `null` if the surface is not registered.
     *
     * Note: not every compatibility surface in the system is registered here — only those
     * that have been explicitly inventoried as high-risk under PR-10.
     */
    fun forId(surfaceId: String): CompatibilitySurfaceEntry? =
        entries.find { it.surfaceId == surfaceId }

    /**
     * Set of surface IDs classified as [RetirementTier.HIGH_RISK_ACTIVE].
     *
     * These are the surfaces most likely to be mistaken for canonical governance layers.
     * Callers can assert that no new canonical-looking usage is routed through these surfaces.
     */
    val highRiskSurfaceIds: Set<String>
        get() = byTier(RetirementTier.HIGH_RISK_ACTIVE).map { it.surfaceId }.toSet()

    /**
     * Set of surface IDs classified as [RetirementTier.DECOMMISSION_CANDIDATE].
     *
     * These surfaces have the lowest remaining utility and should be removed in the next
     * cleanup pass once their retirement gate is cleared.
     */
    val decommissionCandidateIds: Set<String>
        get() = byTier(RetirementTier.DECOMMISSION_CANDIDATE).map { it.surfaceId }.toSet()

    /**
     * Count of surfaces gated on external coordination before retirement.
     *
     * These surfaces cannot be safely removed from the Android side alone; center-Android
     * coordination is required.
     */
    val coordinationGatedCount: Int
        get() = byTier(RetirementTier.RETIRE_AFTER_COORDINATION).size
}
