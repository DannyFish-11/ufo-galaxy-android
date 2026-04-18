package com.ufo.galaxy.runtime

/**
 * PR-41 — Compatibility Retirement Fence.
 *
 * Records the explicit PR-41 fence/retire/demotion decision for every compatibility
 * surface inventoried in [CompatibilitySurfaceRetirementRegistry].
 *
 * ## Purpose
 *
 * [CompatibilitySurfaceRetirementRegistry] inventories all high-risk compatibility surfaces
 * and classifies each by [CompatibilitySurfaceRetirementRegistry.RetirementTier].  However,
 * it does not record the **decision** reached in each finalization pass about whether a surface
 * is actively fenced (blocked from receiving new consumers), demoted to a lower-risk tier,
 * or already retired.
 *
 * [CompatibilityRetirementFence] fills that gap for the PR-41 finalization pass:
 *
 * - Every surface in [CompatibilitySurfaceRetirementRegistry.entries] receives a [FenceEntry]
 *   declaring the [FenceDecision] reached in PR-41.
 * - The decision is informed by whether a canonical replacement is ready, whether all known
 *   consumers have migrated, and whether external gate conditions have been confirmed.
 * - This registry provides a machine-readable audit trail of the PR-41 compatibility
 *   retirement pass for future contributors and cross-repo reviewers.
 *
 * ## Fence decisions
 *
 * | [FenceDecision]                             | Meaning                                                                     |
 * |---------------------------------------------|-----------------------------------------------------------------------------|
 * | [FenceDecision.FENCED]                      | Surface is fenced: blocked from new consumers; canonical replacement ready. |
 * | [FenceDecision.DEMOTED]                     | Surface risk tier has been formally lowered; retirement path is clear.      |
 * | [FenceDecision.PENDING_COORDINATION]        | Retirement blocked on external gate; surface remains active but guarded.    |
 * | [FenceDecision.DECOMMISSION_SCHEDULED]      | Retirement is imminently scheduled; surface has no remaining active utility.|
 *
 * ## Fence reasons
 *
 * | [FenceReason]                               | Meaning                                                                  |
 * |---------------------------------------------|--------------------------------------------------------------------------|
 * | [FenceReason.CANONICAL_REPLACEMENT_COMPLETE]| Canonical replacement exists and all known consumers have migrated.      |
 * | [FenceReason.CONSUMERS_MIGRATED]            | All known consumers of the surface have been migrated to the replacement. |
 * | [FenceReason.CENTER_COORDINATION_REQUIRED]  | External gate requires center-side action before Android can retire.     |
 * | [FenceReason.LOW_PRODUCTION_FREQUENCY]      | Surface has low observed production usage; safe to schedule for cleanup. |
 * | [FenceReason.PROMOTED_TO_DEDICATED_HANDLER] | The surface was promoted out of compatibility handling to a canonical handler. |
 */
object CompatibilityRetirementFence {

    // ── Fence decision ────────────────────────────────────────────────────────

    /**
     * The PR-41 retirement decision for a compatibility surface.
     */
    enum class FenceDecision(val wireValue: String) {
        /**
         * The surface is actively fenced: no new consumers may be added.  The canonical
         * replacement is ready and confirmed; retirement can proceed once all remaining
         * call sites are confirmed clear.
         */
        FENCED("fenced"),

        /**
         * The surface's risk tier has been formally lowered based on PR-41 review.
         * The surface is no longer classified as the highest-risk tier; a clear retirement
         * path exists and work is in progress.
         */
        DEMOTED("demoted"),

        /**
         * Retirement is blocked on an external gate condition (center-Android coordination,
         * gateway schema rollout, or production-traffic confirmation).  The surface remains
         * active but is explicitly guarded against new extension work.
         */
        PENDING_COORDINATION("pending_coordination"),

        /**
         * Retirement is imminently scheduled.  The surface has no remaining active utility
         * and can be removed in the next cleanup pass once the minimal gate condition clears.
         */
        DECOMMISSION_SCHEDULED("decommission_scheduled");

        companion object {
            /** Returns the [FenceDecision] with [value], or `null` if unknown. */
            fun fromValue(value: String): FenceDecision? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Fence reason ─────────────────────────────────────────────────────────

    /**
     * The reason behind the PR-41 fence decision for a compatibility surface.
     */
    enum class FenceReason(val wireValue: String) {
        /**
         * A canonical replacement surface exists and is production-ready; the compatibility
         * surface is no longer the preferred path.
         */
        CANONICAL_REPLACEMENT_COMPLETE("canonical_replacement_complete"),

        /**
         * All known consumers of this compatibility surface have been migrated to the
         * canonical replacement.  No remaining active call sites are known.
         */
        CONSUMERS_MIGRATED("consumers_migrated"),

        /**
         * Retirement requires explicit center-Android coordination.  The Android side cannot
         * safely remove the surface unilaterally without center confirmation.
         */
        CENTER_COORDINATION_REQUIRED("center_coordination_required"),

        /**
         * The surface has low observed production usage frequency; it is safe to schedule for
         * removal in the next cleanup pass without risk of active consumer impact.
         */
        LOW_PRODUCTION_FREQUENCY("low_production_frequency"),

        /**
         * The surface was previously a minimal-compat placeholder but has been promoted to a
         * dedicated canonical handler.  The compatibility entry is retained only for registry
         * completeness.
         */
        PROMOTED_TO_DEDICATED_HANDLER("promoted_to_dedicated_handler");

        companion object {
            /** Returns the [FenceReason] with [value], or `null` if unknown. */
            fun fromValue(value: String): FenceReason? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Fence entry ───────────────────────────────────────────────────────────

    /**
     * A single PR-41 fence decision entry for a compatibility surface.
     *
     * @param compatibilitySurfaceId  The [CompatibilitySurfaceRetirementRegistry.CompatibilitySurfaceEntry.surfaceId]
     *                                this entry covers.
     * @param fenceDecision           The [FenceDecision] reached in PR-41 for this surface.
     * @param fenceReason             The primary [FenceReason] driving the decision.
     * @param retirementReadiness     Human-readable summary of how retirement-ready this surface is.
     * @param activeRisk              Human-readable note on the remaining active risk if the surface
     *                                is not retired by the stated gate condition.
     * @param finalizationNote        One-sentence note on what PR-41 accomplished for this surface.
     */
    data class FenceEntry(
        val compatibilitySurfaceId: String,
        val fenceDecision: FenceDecision,
        val fenceReason: FenceReason,
        val retirementReadiness: String,
        val activeRisk: String,
        val finalizationNote: String
    )

    // ── Fence registry ────────────────────────────────────────────────────────

    /**
     * Complete registry of PR-41 fence decisions, one entry per
     * [CompatibilitySurfaceRetirementRegistry.entries] surface.
     */
    val fences: List<FenceEntry> = listOf(

        // ── Runtime bridges ───────────────────────────────────────────────────

        FenceEntry(
            compatibilitySurfaceId = "runtime_registration_error_string_bridge",
            fenceDecision = FenceDecision.FENCED,
            fenceReason = FenceReason.CONSUMERS_MIGRATED,
            retirementReadiness = "Deprecated in PR-36; all known consumers (MainViewModel, " +
                "EnhancedFloatingService) have migrated to setupError. " +
                "Confirmed no active production observers remain.",
            activeRisk = "Retained in codebase but @Deprecated; risk is low because any new " +
                "observer would require bypassing the @Deprecated annotation.",
            finalizationNote = "PR-41 confirms fence: no new consumers may be added; " +
                "removal gated only on a final call-site audit to confirm no external observers."
        ),

        FenceEntry(
            compatibilitySurfaceId = "runtime_host_session_legacy_map_bridge",
            fenceDecision = FenceDecision.PENDING_COORDINATION,
            fenceReason = FenceReason.CANONICAL_REPLACEMENT_COMPLETE,
            retirementReadiness = "Canonical replacement (hostSessionSnapshot / " +
                "currentHostSessionSnapshot) is complete and stable since PR-22. " +
                "Retirement gated on confirming all host-facing consumers have migrated.",
            activeRisk = "Legacy map bridge is still operationally visible; host-facing " +
                "consumers that depend on Map<String,Any> return type cannot trivially " +
                "switch to the typed snapshot without a consumer migration.",
            finalizationNote = "PR-41 fences the map bridge: no new call sites permitted; " +
                "active consumers must migrate to hostSessionSnapshot before retirement."
        ),

        // ── Protocol surfaces ─────────────────────────────────────────────────

        FenceEntry(
            compatibilitySurfaceId = "session_identifier_carrier_transitional_surface",
            fenceDecision = FenceDecision.PENDING_COORDINATION,
            fenceReason = FenceReason.CENTER_COORDINATION_REQUIRED,
            retirementReadiness = "CanonicalSessionAxis fully documents the wire-alias relationship " +
                "(session_id → control_session_id) but the Android wire field cannot be renamed " +
                "unilaterally; requires a coordinated center-Android vocabulary rollout.",
            activeRisk = "Wire alias mismatch creates ongoing documentation burden and potential " +
                "confusion in cross-repo consumers that inspect raw wire fields.",
            finalizationNote = "PR-41 acknowledges this surface as blocked on center coordination; " +
                "CanonicalSessionAxis.wireAlias makes the alias relationship machine-readable."
        ),

        FenceEntry(
            compatibilitySurfaceId = "staged_mesh_execution_status_transitional_surface",
            fenceDecision = FenceDecision.PENDING_COORDINATION,
            fenceReason = FenceReason.CENTER_COORDINATION_REQUIRED,
            retirementReadiness = "StagedMeshParticipationResult.ExecutionStatus wire values need " +
                "mapping to canonical terminal status vocabulary; requires center-Android coordination.",
            activeRisk = "ExecutionStatus values may be interpreted differently by center-side " +
                "consumers that expect canonical terminal status vocabulary.",
            finalizationNote = "PR-41 notes the coordination requirement; no Android-unilateral " +
                "action is possible until center publishes the canonical terminal status mapping."
        ),

        // ── Deprecated APIs ───────────────────────────────────────────────────

        FenceEntry(
            compatibilitySurfaceId = "galaxy_api_client_register_device_deprecated",
            fenceDecision = FenceDecision.DECOMMISSION_SCHEDULED,
            fenceReason = FenceReason.CANONICAL_REPLACEMENT_COMPLETE,
            retirementReadiness = "GalaxyWebSocketClient capability_report on connect replaces " +
                "the REST device-registration endpoint; no known active production call sites.",
            activeRisk = "Retained for diagnostic compatibility only; minimal operational risk.",
            finalizationNote = "PR-41 schedules this for decommission in the next cleanup pass; " +
                "removal gated on confirming no remaining diagnostic or test call sites."
        ),

        FenceEntry(
            compatibilitySurfaceId = "galaxy_api_client_send_heartbeat_deprecated",
            fenceDecision = FenceDecision.DECOMMISSION_SCHEDULED,
            fenceReason = FenceReason.CANONICAL_REPLACEMENT_COMPLETE,
            retirementReadiness = "GalaxyWebSocketClient automatic heartbeat (30 s) replaces the " +
                "REST heartbeat endpoint; no known active production call sites.",
            activeRisk = "Retained for diagnostic compatibility only; minimal operational risk.",
            finalizationNote = "PR-41 schedules this for decommission alongside registerDevice; " +
                "removal can be batched in the same cleanup pass."
        ),

        // ── Protocol alias surfaces ───────────────────────────────────────────

        FenceEntry(
            compatibilitySurfaceId = "legacy_msgtype_alias_normalization",
            fenceDecision = FenceDecision.PENDING_COORDINATION,
            fenceReason = FenceReason.CENTER_COORDINATION_REQUIRED,
            retirementReadiness = "Android normalization path is correct and fenced; retirement " +
                "blocked until center stops emitting legacy type aliases in production traffic.",
            activeRisk = "Removing normalization before center stops emitting legacy types would " +
                "break canonical dispatch routing for affected message types.",
            finalizationNote = "PR-41 confirms this surface must remain active until center " +
                "confirms the gateway schema rollout; fence blocks new alias additions."
        ),

        FenceEntry(
            compatibilitySurfaceId = "lifecycle_status_normalization_map",
            fenceDecision = FenceDecision.PENDING_COORDINATION,
            fenceReason = FenceReason.CENTER_COORDINATION_REQUIRED,
            retirementReadiness = "Normalization map is correct and bounded; retirement blocked until " +
                "transfer result producers emit canonical terminal statuses exclusively.",
            activeRisk = "Removing normalization before coordinated rollout would cause terminal " +
                "status misclassification in the result processing pipeline.",
            finalizationNote = "PR-41 confirms this surface must remain active until center provides " +
                "coordinated rollout confirmation; no new normalization entries should be added."
        ),

        // ── Dispatch adapters ─────────────────────────────────────────────────

        FenceEntry(
            compatibilitySurfaceId = "dispatch_adapter_relay_transitional",
            fenceDecision = FenceDecision.PENDING_COORDINATION,
            fenceReason = FenceReason.CENTER_COORDINATION_REQUIRED,
            retirementReadiness = "Minimal-compat handler is correct and bounded; no canonical " +
                "relay executor is implemented.  Retirement or promotion requires center decision.",
            activeRisk = "Relay messages will continue to receive a generic ACK without relay-chain " +
                "semantics until center either retires the RELAY message type or promotes this handler.",
            finalizationNote = "PR-41 confirms the handler boundary; no new relay logic should be " +
                "added to the minimal-compat path."
        ),

        FenceEntry(
            compatibilitySurfaceId = "dispatch_adapter_forward_transitional",
            fenceDecision = FenceDecision.PENDING_COORDINATION,
            fenceReason = FenceReason.CENTER_COORDINATION_REQUIRED,
            retirementReadiness = "Minimal-compat handler is bounded; no canonical peer-routing " +
                "executor is implemented.  Retirement gated on center decision.",
            activeRisk = "FORWARD messages are logged only; no peer-routing logic will execute " +
                "until center promotes this handler or retires the message type.",
            finalizationNote = "PR-41 maintains the fence; handler must not receive new routing logic."
        ),

        FenceEntry(
            compatibilitySurfaceId = "dispatch_adapter_reply_transitional",
            fenceDecision = FenceDecision.PENDING_COORDINATION,
            fenceReason = FenceReason.CENTER_COORDINATION_REQUIRED,
            retirementReadiness = "Minimal-compat handler is bounded; no reply-state machine " +
                "is implemented.  Retirement gated on center decision.",
            activeRisk = "REPLY messages are logged only; no reply-state machine will execute " +
                "until center promotes or retires the message type.",
            finalizationNote = "PR-41 maintains the fence; handler must not receive new reply logic."
        ),

        FenceEntry(
            compatibilitySurfaceId = "dispatch_adapter_rag_query_transitional",
            fenceDecision = FenceDecision.PENDING_COORDINATION,
            fenceReason = FenceReason.CENTER_COORDINATION_REQUIRED,
            retirementReadiness = "Minimal-compat handler returns empty result; no RAG pipeline " +
                "is implemented.  Retirement gated on center specification or retirement.",
            activeRisk = "RAG_QUERY messages will return empty results until center provides " +
                "a pipeline specification or formally retires RAG_QUERY.",
            finalizationNote = "PR-41 maintains the fence; no new RAG logic should be added here."
        ),

        FenceEntry(
            compatibilitySurfaceId = "dispatch_adapter_code_execute_transitional",
            fenceDecision = FenceDecision.PENDING_COORDINATION,
            fenceReason = FenceReason.CENTER_COORDINATION_REQUIRED,
            retirementReadiness = "Minimal-compat handler returns error result; no code sandbox " +
                "is implemented.  Retirement gated on center sandbox specification.",
            activeRisk = "CODE_EXECUTE messages will return error results until center provides " +
                "a sandbox specification or formally retires CODE_EXECUTE.",
            finalizationNote = "PR-41 maintains the fence; no new execution logic should be added here."
        ),

        FenceEntry(
            compatibilitySurfaceId = "dispatch_adapter_peer_announce_transitional",
            fenceDecision = FenceDecision.DECOMMISSION_SCHEDULED,
            fenceReason = FenceReason.PROMOTED_TO_DEDICATED_HANDLER,
            retirementReadiness = "PEER_ANNOUNCE was promoted to a dedicated stateful handler in " +
                "PR-36; the minimal-compat path is no longer exercised.",
            activeRisk = "Minimal — entry is retained only for registry completeness; the " +
                "promoted handler is the canonical path.",
            finalizationNote = "PR-41 confirms decommission scheduling; this registry entry can " +
                "be removed in the next cleanup pass alongside the other decommission candidates."
        ),

        FenceEntry(
            compatibilitySurfaceId = "dispatch_adapter_wake_event_transitional",
            fenceDecision = FenceDecision.PENDING_COORDINATION,
            fenceReason = FenceReason.CENTER_COORDINATION_REQUIRED,
            retirementReadiness = "Generic ACK handler is bounded; no suspend/resume state machine " +
                "is implemented.  Retirement gated on center decision.",
            activeRisk = "WAKE_EVENT messages will receive a generic ACK only; no suspend/resume " +
                "state will be maintained until center promotes or retires the message type.",
            finalizationNote = "PR-41 maintains the fence; handler must not receive new wake logic."
        ),

        FenceEntry(
            compatibilitySurfaceId = "dispatch_adapter_session_migrate_transitional",
            fenceDecision = FenceDecision.PENDING_COORDINATION,
            fenceReason = FenceReason.CENTER_COORDINATION_REQUIRED,
            retirementReadiness = "Degrade/reject reply handler is bounded; no session migration " +
                "executor is implemented.  Retirement gated on center migration specification.",
            activeRisk = "SESSION_MIGRATE messages will be rejected without migration logic " +
                "until center provides a migration specification or formally retires the type.",
            finalizationNote = "PR-41 maintains the fence; no new migration logic should be added here."
        ),

        FenceEntry(
            compatibilitySurfaceId = "dispatch_adapter_broadcast_transitional",
            fenceDecision = FenceDecision.PENDING_COORDINATION,
            fenceReason = FenceReason.CENTER_COORDINATION_REQUIRED,
            retirementReadiness = "Log-only handler is bounded; no broadcast fan-out is implemented. " +
                "Retirement gated on center decision.",
            activeRisk = "BROADCAST messages are logged only; no fan-out logic will execute " +
                "until center promotes or retires the message type.",
            finalizationNote = "PR-41 maintains the fence; handler must not receive new broadcast logic."
        ),

        // ── Decommission candidates ───────────────────────────────────────────

        FenceEntry(
            compatibilitySurfaceId = "dispatch_adapter_lock_transitional",
            fenceDecision = FenceDecision.DECOMMISSION_SCHEDULED,
            fenceReason = FenceReason.LOW_PRODUCTION_FREQUENCY,
            retirementReadiness = "Low observed production frequency; no distributed lock manager " +
                "is implemented.  Safe to remove once center confirms LOCK is no longer sent.",
            activeRisk = "LOCK messages receive a generic ACK; low production frequency means " +
                "this is unlikely to cause operational impact if removed.",
            finalizationNote = "PR-41 schedules LOCK for decommission; removal gated on center " +
                "confirmation that LOCK is no longer emitted in production traffic."
        ),

        FenceEntry(
            compatibilitySurfaceId = "dispatch_adapter_unlock_transitional",
            fenceDecision = FenceDecision.DECOMMISSION_SCHEDULED,
            fenceReason = FenceReason.LOW_PRODUCTION_FREQUENCY,
            retirementReadiness = "Low observed production frequency; no distributed lock manager " +
                "is implemented.  Safe to remove once center confirms UNLOCK is no longer sent.",
            activeRisk = "UNLOCK messages receive a generic ACK; low production frequency means " +
                "this is unlikely to cause operational impact if removed.",
            finalizationNote = "PR-41 schedules UNLOCK for decommission alongside LOCK; " +
                "removal can be batched in the same cleanup pass."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /** Returns the [FenceEntry] for [surfaceId], or `null` if not registered. */
    fun fenceFor(surfaceId: String): FenceEntry? =
        fences.firstOrNull { it.compatibilitySurfaceId == surfaceId }

    /** Returns all fence entries with the given [decision]. */
    fun byDecision(decision: FenceDecision): List<FenceEntry> =
        fences.filter { it.fenceDecision == decision }

    /** Returns all fence entries with the given [reason]. */
    fun byReason(reason: FenceReason): List<FenceEntry> =
        fences.filter { it.fenceReason == reason }

    /**
     * Set of compatibility surface IDs that are fenced ([FenceDecision.FENCED]).
     *
     * Fenced surfaces have a canonical replacement confirmed as ready and all known
     * consumers migrated.  No new call sites may be added to fenced surfaces.
     */
    val fencedSurfaceIds: Set<String> =
        fences.filter { it.fenceDecision == FenceDecision.FENCED }.map { it.compatibilitySurfaceId }.toSet()

    /**
     * Set of compatibility surface IDs scheduled for decommission
     * ([FenceDecision.DECOMMISSION_SCHEDULED]).
     *
     * These surfaces have no remaining active utility and can be removed in the next
     * cleanup pass once their gate conditions are confirmed clear.
     */
    val decommissionScheduledSurfaceIds: Set<String> =
        fences
            .filter { it.fenceDecision == FenceDecision.DECOMMISSION_SCHEDULED }
            .map { it.compatibilitySurfaceId }
            .toSet()

    /**
     * Set of compatibility surface IDs where retirement is pending external coordination
     * ([FenceDecision.PENDING_COORDINATION]).
     *
     * These surfaces remain active but guarded; no new extension work should target them.
     */
    val pendingCoordinationSurfaceIds: Set<String> =
        fences
            .filter { it.fenceDecision == FenceDecision.PENDING_COORDINATION }
            .map { it.compatibilitySurfaceId }
            .toSet()

    // ── System summary constants ──────────────────────────────────────────────

    /**
     * The PR number that introduced this retirement fence registry.
     */
    const val INTRODUCED_PR: Int = 41

    /**
     * Short description of what this registry records.
     */
    const val DESCRIPTION: String =
        "PR-41 compatibility retirement fence registry — records explicit fence/retire/demotion " +
            "decisions for every compatibility surface in CompatibilitySurfaceRetirementRegistry. " +
            "Provides a machine-readable audit trail of the PR-41 finalization pass."

    /**
     * Invariant: every surface in [CompatibilitySurfaceRetirementRegistry.entries] must have
     * exactly one [FenceEntry] in [fences].  No surface should be left without a PR-41
     * decision; if a new surface is added to the registry it must also receive a fence entry.
     *
     * Consumers can assert this invariant in tests by comparing
     * [CompatibilitySurfaceRetirementRegistry.entries] surface IDs against [fences] surface IDs.
     */
    const val COVERAGE_INVARIANT: String =
        "Every CompatibilitySurfaceRetirementRegistry entry must have exactly one FenceEntry " +
            "in CompatibilityRetirementFence.fences.  One-to-one coverage is required."
}
