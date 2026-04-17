package com.ufo.galaxy.runtime

/**
 * PR-38 — Protocol Convergence Boundary.
 *
 * Defines the explicit protocol/runtime interaction surface convergence boundary for
 * UFO Galaxy Android.  Every protocol-facing or runtime-interaction surface is
 * classified by its [SurfaceConvergenceStatus] and the [ConvergenceAction] that
 * must be taken to move it toward the canonical path.
 *
 * ## Purpose
 *
 * The Android runtime has evolved through multiple coordination and protocol layers.
 * Some long-tail compatibility or side-path surfaces remain and can blur the canonical
 * runtime path, create ambiguity around which protocol/runtime interaction is
 * authoritative, and increase maintenance cost.  As the broader Galaxy system moves
 * toward more governed multi-device runtime behavior, Android should converge on a
 * smaller set of clearly supported protocol/runtime paths.
 *
 * [ProtocolConvergenceBoundary] makes the convergence state explicit by classifying:
 *
 * - Which protocol/runtime interaction surfaces are **canonical** — primary path; safe
 *   to route through and extend.
 * - Which are **converging** — acknowledged transition state; must not receive new
 *   dependencies; consumers should be migrated to the canonical path.
 * - Which are **isolated** — explicitly behind a convergence boundary; no new logic
 *   allowed; retained only for backward compatibility.
 * - Which are **retired** — permanently blocked from new callers.
 *
 * ## Canonical protocol interaction path
 *
 * The canonical end-to-end outbound protocol interaction path is:
 * ```
 * User input
 *   → InputRouter (sole dispatch gate — canonical outbound entry)
 *   → [crossDeviceEnabled=true]  GalaxyWebSocketClient (sole outbound uplink)
 *   → [crossDeviceEnabled=false] LocalLoopExecutor (canonical local path)
 * ```
 *
 * The canonical inbound protocol interaction path is:
 * ```
 * GalaxyWebSocketClient (sole inbound WS surface)
 *   → GalaxyConnectionService (canonical inbound dispatch)
 *   → CanonicalDispatchChain paths (canonical execution)
 * ```
 *
 * All outbound task submissions must pass through [InputRouter].  All inbound protocol
 * messages must be received by [GalaxyConnectionService] via [GalaxyWebSocketClient].
 * No component may bypass these canonical gates.
 *
 * ## Classification tiers
 *
 * | [SurfaceConvergenceStatus]             | Meaning                                                             |
 * |----------------------------------------|---------------------------------------------------------------------|
 * | [SurfaceConvergenceStatus.CANONICAL]   | Authoritative; safe to extend; the primary protocol path.           |
 * | [SurfaceConvergenceStatus.CONVERGING]  | Transitional; no new dependencies; consumers must converge.         |
 * | [SurfaceConvergenceStatus.ISOLATED]    | Explicitly isolated; not extendable; no new logic permitted.        |
 * | [SurfaceConvergenceStatus.RETIRED]     | Permanently blocked from new callers; treat as removed.             |
 *
 * ## Convergence actions
 *
 * | [ConvergenceAction]                              | Meaning                                                       |
 * |--------------------------------------------------|---------------------------------------------------------------|
 * | [ConvergenceAction.NO_ACTION]                    | Canonical; no convergence work needed.                        |
 * | [ConvergenceAction.PROMOTE_TO_CANONICAL]         | Has a path to full canonicalization; implementation pending.  |
 * | [ConvergenceAction.ISOLATE_BEHIND_BOUNDARY]      | Keep but fence off; explicit no-extension boundary.           |
 * | [ConvergenceAction.RETIRE_AFTER_GATE]            | Retire once the coordination gate is cleared.                 |
 *
 * ## Relationship to other governance objects
 *
 * - [CanonicalDispatchChain] — describes which execution *paths* exist and their rollout
 *   gates.  [ProtocolConvergenceBoundary] classifies the protocol/runtime *surfaces*
 *   that those paths interact with.
 * - [LongTailCompatibilityRegistry] — classifies individual AIP v3 message types by
 *   their long-tail status.  [ProtocolConvergenceBoundary] lifts this classification to
 *   the surface level and adds convergence actions for each entry.
 * - [CompatibilitySurfaceRetirementRegistry] — tracks retirement timelines for individual
 *   surfaces.  [ProtocolConvergenceBoundary] is the complementary convergence-authority
 *   view: it says which surfaces are isolated or retired, not just which are high-risk.
 * - [StabilizationBaseline] — declares the stabilization classification of each surface.
 *   [ProtocolConvergenceBoundary] is registered in [StabilizationBaseline] as a
 *   canonical-stable governance surface.
 *
 * @see InputRouter
 * @see CanonicalDispatchChain
 * @see LongTailCompatibilityRegistry
 * @see CompatibilitySurfaceRetirementRegistry
 */
object ProtocolConvergenceBoundary {

    // ── Convergence status ────────────────────────────────────────────────────

    /**
     * Convergence status of a protocol/runtime interaction surface.
     *
     * @see ProtocolConvergenceBoundary
     */
    enum class SurfaceConvergenceStatus(val wireValue: String) {
        /**
         * The surface is canonical and authoritative.
         *
         * It is the primary protocol interaction path for its domain.  New protocol or
         * runtime work should extend through this surface or its designated extension points.
         * No bypass of a [CANONICAL] gate is permitted.
         */
        CANONICAL("canonical"),

        /**
         * The surface is in an acknowledged transitional state.
         *
         * It may still be used but must not receive new dependencies.  Future work must
         * converge existing consumers to the canonical replacement recorded in
         * [ProtocolSurfaceConvergenceEntry.canonicalPath].
         */
        CONVERGING("converging"),

        /**
         * The surface is explicitly isolated behind a convergence boundary.
         *
         * It must not be extended with new logic.  It is retained for backward
         * compatibility only; no new callers should be introduced.  Future work must first
         * promote it through [LongTailCompatibilityRegistry] before extending it.
         */
        ISOLATED("isolated"),

        /**
         * The surface is retired — permanently blocked from new callers.
         *
         * Any existing usage should be migrated to the canonical replacement.
         */
        RETIRED("retired")
    }

    // ── Convergence action ────────────────────────────────────────────────────

    /**
     * The convergence action required to align a surface with the canonical path.
     */
    enum class ConvergenceAction(val wireValue: String) {
        /**
         * No convergence action needed — the surface is already canonical.
         */
        NO_ACTION("no_action"),

        /**
         * The surface should be promoted to full canonical status.
         *
         * A concrete promotion path exists; the surface has been partially promoted
         * (e.g. via [LongTailCompatibilityRegistry.CompatTier.PROMOTED]) but has not
         * yet reached full canonical lifecycle integration.
         */
        PROMOTE_TO_CANONICAL("promote_to_canonical"),

        /**
         * The surface must be isolated behind an explicit convergence boundary.
         *
         * No new logic or callers may be added.  The surface is fenced from the canonical
         * path.  Future work must first promote this surface through
         * [LongTailCompatibilityRegistry] before it can be extended.
         */
        ISOLATE_BEHIND_BOUNDARY("isolate_behind_boundary"),

        /**
         * The surface should be retired once the coordination gate recorded in
         * [ProtocolSurfaceConvergenceEntry.convergenceGate] is cleared.
         */
        RETIRE_AFTER_GATE("retire_after_gate")
    }

    // ── Entry model ───────────────────────────────────────────────────────────

    /**
     * A single protocol/runtime interaction surface entry in the convergence boundary.
     *
     * @param surfaceId       Stable machine-readable identifier (kebab-case).
     * @param displayName     Human-readable surface name.
     * @param status          Current [SurfaceConvergenceStatus] classification.
     * @param action          The [ConvergenceAction] required for this surface.
     * @param description     What this surface does in the protocol/runtime interaction model,
     *                        and why it carries its current convergence status.
     * @param canonicalPath   The canonical path that should be used instead of this surface,
     *                        when applicable.  `null` for [SurfaceConvergenceStatus.CANONICAL]
     *                        entries where no replacement exists.
     * @param convergenceGate The condition that must be met before convergence or retirement.
     *                        Applies to entries with [ConvergenceAction.RETIRE_AFTER_GATE] or
     *                        [ConvergenceAction.ISOLATE_BEHIND_BOUNDARY] that require external
     *                        coordination.  `null` when no external gate applies.
     */
    data class ProtocolSurfaceConvergenceEntry(
        val surfaceId: String,
        val displayName: String,
        val status: SurfaceConvergenceStatus,
        val action: ConvergenceAction,
        val description: String,
        val canonicalPath: String? = null,
        val convergenceGate: String? = null
    )

    // ── Registry ──────────────────────────────────────────────────────────────

    /**
     * Complete protocol/runtime interaction surface convergence registry.
     *
     * Entries are ordered: [SurfaceConvergenceStatus.CANONICAL] first, then
     * [SurfaceConvergenceStatus.CONVERGING], then [SurfaceConvergenceStatus.ISOLATED],
     * then [SurfaceConvergenceStatus.RETIRED].
     *
     * Consumers can query via [byStatus], [forId], [canonicalSurfaces],
     * [convergingSurfaces], [isolatedSurfaces], [retiredSurfaces], and
     * [pendingConvergenceSurfaces].
     */
    val entries: List<ProtocolSurfaceConvergenceEntry> = listOf(

        // ── Canonical: primary protocol/runtime interaction surfaces ──────────

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "input-router-dispatch-gate",
            displayName = "InputRouter",
            status      = SurfaceConvergenceStatus.CANONICAL,
            action      = ConvergenceAction.NO_ACTION,
            description = "Sole user-input dispatch gate and local-vs-cross-device routing " +
                "authority. All user input (text and voice) must pass through InputRouter.route(). " +
                "No component may bypass InputRouter to call LocalLoopExecutor or " +
                "GatewayClient.sendJson directly for user-initiated task submission."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "galaxy-websocket-client-outbound-uplink",
            displayName = "GalaxyWebSocketClient (outbound uplink)",
            status      = SurfaceConvergenceStatus.CANONICAL,
            action      = ConvergenceAction.NO_ACTION,
            description = "Sole outbound cross-device protocol uplink. All outbound AIP v3 " +
                "messages flow through GalaxyWebSocketClient.sendJson(). The outbound facade " +
                "is explicitly bounded: no component may construct and send wire messages " +
                "outside this surface."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "galaxy-connection-service-inbound-dispatch",
            displayName = "GalaxyConnectionService (inbound dispatch)",
            status      = SurfaceConvergenceStatus.CANONICAL,
            action      = ConvergenceAction.NO_ACTION,
            description = "Canonical inbound AIP v3 message dispatch surface. All inbound " +
                "protocol messages are received via GalaxyWebSocketClient and routed by " +
                "GalaxyConnectionService. The dispatch chain (task_assign, goal_execution, " +
                "takeover_request, task_cancel, and all long-tail types) is governed here."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "canonical-dispatch-chain-path-model",
            displayName = "CanonicalDispatchChain",
            status      = SurfaceConvergenceStatus.CANONICAL,
            action      = ConvergenceAction.NO_ACTION,
            description = "Canonical execution path model. Enumerates all dispatch paths, " +
                "their rollout gates, and their authority boundaries. State-aware eligibility " +
                "query is available via resolveEligiblePathsForState()."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "runtime-controller-lifecycle-authority",
            displayName = "RuntimeController (lifecycle authority)",
            status      = SurfaceConvergenceStatus.CANONICAL,
            action      = ConvergenceAction.NO_ACTION,
            description = "Sole lifecycle authority for WS connect/disconnect, " +
                "crossDeviceEnabled, attached session, and host participation state. " +
                "All runtime lifecycle transitions must go through RuntimeController."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "runtime-dispatch-readiness-coordinator-eligibility",
            displayName = "RuntimeDispatchReadinessCoordinator",
            status      = SurfaceConvergenceStatus.CANONICAL,
            action      = ConvergenceAction.NO_ACTION,
            description = "Canonical dispatch eligibility authority combining runtime state, " +
                "session state, and rollout flags into a single queryable result. Any component " +
                "that needs to determine whether cross-device dispatch is currently eligible " +
                "must use this coordinator."
        ),

        // ── Converging: promoted long-tail flows and transitional protocol surfaces ──

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "long-tail-peer-exchange-converging",
            displayName = "LongTailCompatibilityRegistry: PEER_EXCHANGE (PROMOTED, PR-35)",
            status      = SurfaceConvergenceStatus.CONVERGING,
            action      = ConvergenceAction.PROMOTE_TO_CANONICAL,
            description = "PEER_EXCHANGE was promoted from minimal-compat to dedicated stateful " +
                "peer-capability tracking in PR-35. Converging toward canonical status as " +
                "peer capability exchange is integrated into the formation model.",
            canonicalPath = "PeerExchangePayload handler in GalaxyConnectionService; " +
                "per-peer capability record tracked per session."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "long-tail-mesh-topology-converging",
            displayName = "LongTailCompatibilityRegistry: MESH_TOPOLOGY (PROMOTED, PR-35)",
            status      = SurfaceConvergenceStatus.CONVERGING,
            action      = ConvergenceAction.PROMOTE_TO_CANONICAL,
            description = "MESH_TOPOLOGY was promoted from minimal-compat to dedicated stateful " +
                "topology-snapshot retention in PR-35. Converging toward canonical status as " +
                "mesh topology is integrated into the formation governance model.",
            canonicalPath = "MeshTopologyPayload handler; last-known topology snapshot retained."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "long-tail-coord-sync-converging",
            displayName = "LongTailCompatibilityRegistry: COORD_SYNC (PROMOTED, PR-35)",
            status      = SurfaceConvergenceStatus.CONVERGING,
            action      = ConvergenceAction.PROMOTE_TO_CANONICAL,
            description = "COORD_SYNC was promoted from generic ACK to sequence-aware " +
                "CoordSyncAckPayload in PR-35. Converging toward canonical status as " +
                "coordination-sync tick handling is integrated into the runtime authority model.",
            canonicalPath = "CoordSyncAckPayload response; per-session sync tick counter."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "long-tail-peer-announce-converging",
            displayName = "LongTailCompatibilityRegistry: PEER_ANNOUNCE (PROMOTED, PR-36)",
            status      = SurfaceConvergenceStatus.CONVERGING,
            action      = ConvergenceAction.PROMOTE_TO_CANONICAL,
            description = "PEER_ANNOUNCE was promoted from minimal-compat to dedicated stateful " +
                "peer-presence tracking in PR-36. Converging toward canonical status as " +
                "peer presence handling is integrated into the formation/session model.",
            canonicalPath = "PeerAnnouncePayload handler; per-session peer presence record."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "protocol-surface-session-identifier-carrier-transitional",
            displayName = "ProtocolSurface.SESSION_IDENTIFIER_CARRIER (TRANSITIONAL_COMPATIBILITY)",
            status      = SurfaceConvergenceStatus.CONVERGING,
            action      = ConvergenceAction.PROMOTE_TO_CANONICAL,
            description = "Android session identifier carriers are not yet fully unified with " +
                "the center canonical vocabulary. CanonicalSessionAxis maps all carriers to " +
                "canonical session families, but naming convergence (e.g. mesh_id → " +
                "mesh_session_id) is deferred pending cross-repo coordination.",
            canonicalPath = "CanonicalSessionAxis canonical session family vocabulary; " +
                "UgcpSharedSchemaAlignment.identityAlignments.",
            convergenceGate = "center-Android coordination to align session identifier carrier " +
                "naming with the canonical session family vocabulary."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "protocol-surface-staged-mesh-execution-status-transitional",
            displayName = "ProtocolSurface.STAGED_MESH_EXECUTION_STATUS (TRANSITIONAL_COMPATIBILITY)",
            status      = SurfaceConvergenceStatus.CONVERGING,
            action      = ConvergenceAction.PROMOTE_TO_CANONICAL,
            description = "StagedMeshParticipationResult.ExecutionStatus wire values are not " +
                "yet fully aligned with the center canonical terminal status vocabulary. " +
                "The status wires work correctly but differ in naming from the canonical set.",
            canonicalPath = "UgcpProtocolConsistencyRules.terminalStateVocabularyRule canonical set.",
            convergenceGate = "center-Android coordination to map ExecutionStatus wire values " +
                "to canonical terminal status values."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "legacy-session-snapshot-map-converging",
            displayName = "RuntimeController.currentSessionSnapshot() (Map<String,Any>?)",
            status      = SurfaceConvergenceStatus.CONVERGING,
            action      = ConvergenceAction.RETIRE_AFTER_GATE,
            description = "Legacy untyped map projection of the attached session. Retained while " +
                "remaining consumers migrate to the canonical typed snapshot. Must not be used " +
                "by any new code.",
            canonicalPath = "RuntimeController.hostSessionSnapshot " +
                "(StateFlow<AttachedRuntimeHostSessionSnapshot?>); " +
                "RuntimeController.currentHostSessionSnapshot().",
            convergenceGate = "All host-facing consumers must migrate to hostSessionSnapshot / " +
                "currentHostSessionSnapshot before this bridge can be removed."
        ),

        // ── Isolated: explicitly bounded long-tail surfaces ───────────────────

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "long-tail-relay-isolated",
            displayName = "MsgType.RELAY (minimal-compat, ISOLATED)",
            status      = SurfaceConvergenceStatus.ISOLATED,
            action      = ConvergenceAction.ISOLATE_BEHIND_BOUNDARY,
            description = "RELAY minimal-compat handler: generic ACK sent on receipt. No " +
                "relay-chain logic exists. Explicitly isolated — must not receive new logic. " +
                "Future work must promote to a dedicated relay executor via " +
                "LongTailCompatibilityRegistry before this surface can be extended.",
            canonicalPath = "Future: dedicated relay executor (not yet implemented).",
            convergenceGate = "center-Android coordination on relay lifecycle semantics."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "long-tail-forward-isolated",
            displayName = "MsgType.FORWARD (minimal-compat, ISOLATED)",
            status      = SurfaceConvergenceStatus.ISOLATED,
            action      = ConvergenceAction.ISOLATE_BEHIND_BOUNDARY,
            description = "FORWARD minimal-compat handler: logged only; no peer-routing logic. " +
                "Explicitly isolated — must not receive new logic. Future work must promote to " +
                "a dedicated forwarding executor via LongTailCompatibilityRegistry.",
            canonicalPath = "Future: dedicated peer-routing executor (not yet implemented).",
            convergenceGate = "center-Android coordination on peer-routing lifecycle semantics."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "long-tail-reply-isolated",
            displayName = "MsgType.REPLY (minimal-compat, ISOLATED)",
            status      = SurfaceConvergenceStatus.ISOLATED,
            action      = ConvergenceAction.ISOLATE_BEHIND_BOUNDARY,
            description = "REPLY minimal-compat handler: logged only; no reply-state machine. " +
                "Explicitly isolated — must not receive new logic. Future work must promote to " +
                "a dedicated reply-state executor via LongTailCompatibilityRegistry.",
            canonicalPath = "Future: dedicated reply-state executor (not yet implemented).",
            convergenceGate = "center-Android coordination on reply-state lifecycle semantics."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "long-tail-rag-query-isolated",
            displayName = "MsgType.RAG_QUERY (minimal-compat, ISOLATED)",
            status      = SurfaceConvergenceStatus.ISOLATED,
            action      = ConvergenceAction.ISOLATE_BEHIND_BOUNDARY,
            description = "RAG_QUERY minimal-compat handler: empty result returned; no RAG " +
                "pipeline. Explicitly isolated — must not receive new logic until a canonical " +
                "RAG pipeline is implemented and promoted through LongTailCompatibilityRegistry.",
            canonicalPath = "Future: canonical RAG query pipeline (not yet implemented).",
            convergenceGate = "RAG pipeline implementation and LongTailCompatibilityRegistry promotion."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "long-tail-code-execute-isolated",
            displayName = "MsgType.CODE_EXECUTE (minimal-compat, ISOLATED)",
            status      = SurfaceConvergenceStatus.ISOLATED,
            action      = ConvergenceAction.ISOLATE_BEHIND_BOUNDARY,
            description = "CODE_EXECUTE minimal-compat handler: error result returned; no sandbox. " +
                "Explicitly isolated — must not receive new logic until a canonical sandbox " +
                "is implemented and promoted through LongTailCompatibilityRegistry.",
            canonicalPath = "Future: canonical code execution sandbox (not yet implemented).",
            convergenceGate = "Sandbox implementation and LongTailCompatibilityRegistry promotion."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "long-tail-wake-event-isolated",
            displayName = "MsgType.WAKE_EVENT (minimal-compat, ISOLATED)",
            status      = SurfaceConvergenceStatus.ISOLATED,
            action      = ConvergenceAction.ISOLATE_BEHIND_BOUNDARY,
            description = "WAKE_EVENT minimal-compat handler: generic ACK sent; no suspend/resume " +
                "state machine. Explicitly isolated — must not receive new logic until a canonical " +
                "wake/resume state machine is implemented.",
            canonicalPath = "Future: canonical device wake/resume state machine (not yet implemented).",
            convergenceGate = "center-Android coordination on wake/suspend lifecycle semantics."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "long-tail-session-migrate-isolated",
            displayName = "MsgType.SESSION_MIGRATE (minimal-compat, ISOLATED)",
            status      = SurfaceConvergenceStatus.ISOLATED,
            action      = ConvergenceAction.ISOLATE_BEHIND_BOUNDARY,
            description = "SESSION_MIGRATE minimal-compat handler: degrade/reject reply sent; no " +
                "migration logic. Explicitly isolated — must not receive new logic until a " +
                "canonical session-migration protocol is specified.",
            canonicalPath = "Future: canonical session migration protocol (not yet specified).",
            convergenceGate = "center-Android coordination on session migration contract."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "long-tail-broadcast-isolated",
            displayName = "MsgType.BROADCAST (minimal-compat, ISOLATED)",
            status      = SurfaceConvergenceStatus.ISOLATED,
            action      = ConvergenceAction.ISOLATE_BEHIND_BOUNDARY,
            description = "BROADCAST minimal-compat handler: logged only; no broadcast fan-out. " +
                "Explicitly isolated — must not receive new logic until a canonical broadcast " +
                "model is specified.",
            canonicalPath = "Future: canonical broadcast model (not yet specified).",
            convergenceGate = "center-Android coordination on broadcast lifecycle semantics."
        ),

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "long-tail-lock-unlock-isolated",
            displayName = "MsgType.LOCK / MsgType.UNLOCK (minimal-compat, ISOLATED)",
            status      = SurfaceConvergenceStatus.ISOLATED,
            action      = ConvergenceAction.ISOLATE_BEHIND_BOUNDARY,
            description = "LOCK and UNLOCK minimal-compat handlers: generic ACK sent; no lock " +
                "manager. Explicitly isolated — must not receive new logic until a canonical " +
                "distributed lock model is implemented.",
            canonicalPath = "Future: canonical distributed lock manager (not yet implemented).",
            convergenceGate = "center-Android coordination on distributed locking contract."
        ),

        // ── Retired: permanently blocked from new callers ─────────────────────

        ProtocolSurfaceConvergenceEntry(
            surfaceId   = "registration-error-string-bridge-retired",
            displayName = "RuntimeController.registrationError (deprecated SharedFlow<String>)",
            status      = SurfaceConvergenceStatus.RETIRED,
            action      = ConvergenceAction.RETIRE_AFTER_GATE,
            description = "Legacy untyped string error emission. Deprecated in PR-36. All known " +
                "consumers (MainViewModel, EnhancedFloatingService) have been migrated to " +
                "setupError (SharedFlow<CrossDeviceSetupError>). Treated as retired from an " +
                "extension perspective; retained only for backward compatibility.",
            canonicalPath = "RuntimeController.setupError (SharedFlow<CrossDeviceSetupError>).",
            convergenceGate = "Confirm no remaining call sites observe registrationError before removal."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns all entries with the given [status].
     */
    fun byStatus(status: SurfaceConvergenceStatus): List<ProtocolSurfaceConvergenceEntry> =
        entries.filter { it.status == status }

    /**
     * Returns the [ProtocolSurfaceConvergenceEntry] for [surfaceId], or `null` if not found.
     */
    fun forId(surfaceId: String): ProtocolSurfaceConvergenceEntry? =
        entries.find { it.surfaceId == surfaceId }

    /**
     * Returns all entries classified as [SurfaceConvergenceStatus.CANONICAL].
     *
     * These are the primary protocol/runtime interaction paths — authoritative, safe to
     * extend, and the correct choice for new protocol or runtime work in their domain.
     */
    val canonicalSurfaces: List<ProtocolSurfaceConvergenceEntry>
        get() = byStatus(SurfaceConvergenceStatus.CANONICAL)

    /**
     * Returns all entries classified as [SurfaceConvergenceStatus.CONVERGING].
     *
     * These surfaces are in transition.  They are acknowledged but must not receive new
     * dependencies; existing consumers must be migrated to the canonical replacement.
     */
    val convergingSurfaces: List<ProtocolSurfaceConvergenceEntry>
        get() = byStatus(SurfaceConvergenceStatus.CONVERGING)

    /**
     * Returns all entries classified as [SurfaceConvergenceStatus.ISOLATED].
     *
     * These surfaces are explicitly behind a convergence boundary.  No new logic is
     * permitted.  They are retained only for backward compatibility and must be promoted
     * via [LongTailCompatibilityRegistry] before they can be extended.
     */
    val isolatedSurfaces: List<ProtocolSurfaceConvergenceEntry>
        get() = byStatus(SurfaceConvergenceStatus.ISOLATED)

    /**
     * Returns all entries classified as [SurfaceConvergenceStatus.RETIRED].
     *
     * These surfaces are permanently blocked from new callers.  Any existing usage should
     * be migrated to the canonical replacement recorded in [ProtocolSurfaceConvergenceEntry.canonicalPath].
     */
    val retiredSurfaces: List<ProtocolSurfaceConvergenceEntry>
        get() = byStatus(SurfaceConvergenceStatus.RETIRED)

    /**
     * Returns all entries for which convergence work is still pending:
     * entries with [SurfaceConvergenceStatus.CONVERGING] or [SurfaceConvergenceStatus.ISOLATED].
     *
     * Callers can use this list to enumerate remaining convergence work items without
     * filtering on status explicitly.
     */
    val pendingConvergenceSurfaces: List<ProtocolSurfaceConvergenceEntry>
        get() = entries.filter {
            it.status == SurfaceConvergenceStatus.CONVERGING ||
                it.status == SurfaceConvergenceStatus.ISOLATED
        }

    /**
     * Returns `true` when [surfaceId] refers to a [SurfaceConvergenceStatus.CANONICAL]
     * surface in this registry.
     *
     * Use to assert that a specific surface is classified as canonical before using it as
     * an extension point for new protocol/runtime work.  Returns `false` both when the
     * surface is found with a non-canonical status and when it is not registered at all.
     */
    fun isCanonical(surfaceId: String): Boolean =
        forId(surfaceId)?.status == SurfaceConvergenceStatus.CANONICAL

    /**
     * Returns `true` when [surfaceId] is explicitly [SurfaceConvergenceStatus.ISOLATED].
     *
     * Use to enforce that no new logic is added to isolated surfaces.  Returns `false`
     * both when the surface is found with a non-isolated status and when it is not registered.
     */
    fun isIsolated(surfaceId: String): Boolean =
        forId(surfaceId)?.status == SurfaceConvergenceStatus.ISOLATED
}
