package com.ufo.galaxy.runtime

/**
 * PR-39 — Host-Facing Projection Contract.
 *
 * Declares the explicit, governed contract for all outward-facing runtime/session
 * projection surfaces exposed by the Android runtime to external consumers (host
 * registry, center-repo session authority, diagnostics, cross-repo policy logic).
 *
 * ## Purpose
 *
 * The Android runtime exposes several host-facing projection surfaces.  Without an
 * explicit contract declaration, consumers may:
 *
 * - Combine multiple partial surfaces and arrive at conflicting interpretations.
 * - Depend on transitional surfaces that will change, rather than the stable
 *   canonical surfaces.
 * - Introduce new observability or policy logic through non-canonical paths.
 *
 * [HostFacingProjectionContract] makes the outward contract **explicit and stable**:
 *
 * - Which surfaces are **canonical** — safe to depend on; field-stable; won't change
 *   between protocol versions without a versioned contract update.
 * - Which surfaces are **transitional** — retained for compatibility; consumers must
 *   migrate to the canonical replacement.
 * - Which surfaces are **deprecated** — retained only for backward compatibility;
 *   must not receive new consumers.
 *
 * ## Canonical host-facing projection surfaces
 *
 * The following surfaces are the stable, canonical outward contract for Android runtime
 * truth.  External consumers (host registry, diagnostics, test assertions) **must**
 * use these surfaces:
 *
 * - **Session truth** — [RuntimeController.hostSessionSnapshot]
 *   ([AttachedRuntimeHostSessionSnapshot]).  The single canonical typed session
 *   envelope; all nine base fields are guaranteed stable.
 * - **Readiness / suitability** — [RuntimeController.targetReadinessProjection]
 *   ([DelegatedTargetReadinessProjection]).  Pre-computed suitability decision;
 *   always co-derived from the session snapshot.
 * - **Dispatch eligibility** — [RuntimeController.currentDispatchReadiness]
 *   ([RuntimeDispatchReadinessCoordinator.DispatchReadiness]).  Point-in-time
 *   combined dispatch answer for runtime + session + rollout state.
 * - **Lifecycle events** — [RuntimeController.lifecycleTransitionEvents]
 *   ([RuntimeLifecycleTransitionEvent]).  Observable stream of all runtime state
 *   transitions with governed / unexpected classification.
 * - **Rollout gates** — [RuntimeController.rolloutControlSnapshot]
 *   ([RolloutControlSnapshot]).  Atomic snapshot of all rollout feature flags.
 * - **Reconnect recovery** — [RuntimeController.reconnectRecoveryState]
 *   ([ReconnectRecoveryState]).  User-facing WS reconnect lifecycle phase.
 * - **Durable session** — [RuntimeController.durableSessionContinuityRecord]
 *   ([DurableSessionContinuityRecord]).  Activation-era durable session identity.
 * - **Participant model** — [RuntimeController.currentCanonicalParticipant]
 *   ([CanonicalParticipantModel]).  Cross-repo participant model projection.
 *
 * ## Transitional / deprecated surfaces
 *
 * - `RuntimeController.currentSessionSnapshot()` — deprecated; superseded by
 *   [RuntimeController.hostSessionSnapshot] and
 *   [RuntimeController.currentHostSessionSnapshot].  Consumers must migrate.
 *
 * ## Derivation invariants
 *
 * The host-facing contract is guaranteed to be internally consistent because all
 * canonical projection surfaces are co-derived through a single update path:
 *
 * ```
 * Tier-1 authoritative fields mutated
 *   → RuntimeController.updateHostSessionSnapshot()
 *     → RuntimeController.hostSessionSnapshot updated
 *     → RuntimeController.targetReadinessProjection co-updated
 *       (DelegatedTargetReadinessProjection.from(snapshot))
 * ```
 *
 * This means `hostSessionSnapshot` and `targetReadinessProjection` are always in sync;
 * consumers can safely combine the two surfaces without risk of observing a stale
 * intermediate state.
 *
 * @see RuntimeTruthPrecedenceRules
 * @see AttachedRuntimeHostSessionSnapshot
 * @see DelegatedTargetReadinessProjection
 * @see RuntimeController
 */
object HostFacingProjectionContract {

    // ── Contract stability classification ─────────────────────────────────────

    /**
     * Stability classification for a host-facing projection surface.
     */
    enum class ContractStability(val wireValue: String) {
        /**
         * The surface is canonical and field-stable.  External consumers may build
         * production logic against this surface without risk of breaking changes
         * outside of a versioned contract update.
         */
        CANONICAL("canonical"),

        /**
         * The surface is retained for compatibility while consumers migrate to the
         * canonical replacement.  Must not receive new production consumers.
         */
        TRANSITIONAL("transitional"),

        /**
         * The surface is formally deprecated.  Retained only for backward compatibility.
         * No new consumers are permitted.  Retirement is pending.
         */
        DEPRECATED("deprecated")
    }

    // ── Projection kind ───────────────────────────────────────────────────────

    /**
     * Kind classification for a host-facing projection surface, describing how it
     * is consumed.
     */
    enum class ProjectionKind(val wireValue: String) {
        /**
         * A [kotlinx.coroutines.flow.StateFlow] that can be observed reactively.
         * Consumers should collect this flow from a lifecycle-scoped coroutine.
         */
        REACTIVE_FLOW("reactive_flow"),

        /**
         * A [kotlinx.coroutines.flow.SharedFlow] of one-time events.
         * Consumers should collect this flow and handle each event once.
         */
        EVENT_STREAM("event_stream"),

        /**
         * A point-in-time query method.  Consumers call it when they need a
         * fresh value; the result is not cached.
         */
        POINT_IN_TIME_QUERY("point_in_time_query")
    }

    // ── Contract entry model ──────────────────────────────────────────────────

    /**
     * A single entry in the host-facing projection contract registry.
     *
     * @param surfaceId             Stable kebab-case identifier for the surface.
     * @param displayName           Human-readable name for documentation.
     * @param kotlinSymbol          Fully-qualified Kotlin symbol / method.
     * @param projectedType         The return type or flow element type (display only).
     * @param stability             [ContractStability] classification.
     * @param kind                  [ProjectionKind] for this surface.
     * @param derivedFromTruthTier  Stable surfaceId of the [RuntimeTruthPrecedenceRules.TruthEntry]
     *                              this projection is derived from.
     * @param canonicalReplacement  For TRANSITIONAL and DEPRECATED surfaces: the canonical
     *                              surface that replaces this one.  `null` for canonical surfaces.
     * @param consumptionGuidance   One-sentence guidance for how this surface should be consumed.
     * @param introducedPr          The PR number that introduced this surface.
     */
    data class ProjectionContractEntry(
        val surfaceId: String,
        val displayName: String,
        val kotlinSymbol: String,
        val projectedType: String,
        val stability: ContractStability,
        val kind: ProjectionKind,
        val derivedFromTruthTier: String,
        val canonicalReplacement: String? = null,
        val consumptionGuidance: String,
        val introducedPr: Int
    )

    // ── Contract registry ─────────────────────────────────────────────────────

    /**
     * Complete host-facing projection contract registry, ordered by stability tier.
     */
    val entries: List<ProjectionContractEntry> = listOf(

        // ── Canonical surfaces ────────────────────────────────────────────────

        ProjectionContractEntry(
            surfaceId = "contract-host-session-snapshot",
            displayName = "RuntimeController.hostSessionSnapshot",
            kotlinSymbol = "com.ufo.galaxy.runtime.RuntimeController.hostSessionSnapshot",
            projectedType = "StateFlow<AttachedRuntimeHostSessionSnapshot?>",
            stability = ContractStability.CANONICAL,
            kind = ProjectionKind.REACTIVE_FLOW,
            derivedFromTruthTier = "snap-host-session-snapshot",
            consumptionGuidance = "Observe reactively for session truth; or call " +
                "currentHostSessionSnapshot() for point-in-time reads.  This is the sole " +
                "canonical session surface for host-registry and cross-repo consumers.",
            introducedPr = 22
        ),
        ProjectionContractEntry(
            surfaceId = "contract-target-readiness-projection",
            displayName = "RuntimeController.targetReadinessProjection",
            kotlinSymbol = "com.ufo.galaxy.runtime.RuntimeController.targetReadinessProjection",
            projectedType = "StateFlow<DelegatedTargetReadinessProjection?>",
            stability = ContractStability.CANONICAL,
            kind = ProjectionKind.REACTIVE_FLOW,
            derivedFromTruthTier = "proj-target-readiness",
            consumptionGuidance = "Observe reactively to determine whether this Android " +
                "device is currently a suitable delegated execution target.  Always in sync " +
                "with hostSessionSnapshot; do not combine them with independent field reads.",
            introducedPr = 24
        ),
        ProjectionContractEntry(
            surfaceId = "contract-dispatch-readiness",
            displayName = "RuntimeController.currentDispatchReadiness()",
            kotlinSymbol = "com.ufo.galaxy.runtime.RuntimeController.currentDispatchReadiness",
            projectedType = "RuntimeDispatchReadinessCoordinator.DispatchReadiness",
            stability = ContractStability.CANONICAL,
            kind = ProjectionKind.POINT_IN_TIME_QUERY,
            derivedFromTruthTier = "proj-dispatch-readiness",
            consumptionGuidance = "Call immediately before a dispatch decision to get the " +
                "canonical eligibility answer.  For reactive dispatch decisions, observe " +
                "lifecycleTransitionEvents instead.",
            introducedPr = 37
        ),
        ProjectionContractEntry(
            surfaceId = "contract-lifecycle-transition-events",
            displayName = "RuntimeController.lifecycleTransitionEvents",
            kotlinSymbol = "com.ufo.galaxy.runtime.RuntimeController.lifecycleTransitionEvents",
            projectedType = "SharedFlow<RuntimeLifecycleTransitionEvent>",
            stability = ContractStability.CANONICAL,
            kind = ProjectionKind.EVENT_STREAM,
            derivedFromTruthTier = "auth-runtime-state",
            consumptionGuidance = "Collect from a lifecycle-scoped coroutine to observe all " +
                "runtime state transitions.  Both Governed and Unexpected events are emitted.",
            introducedPr = 37
        ),
        ProjectionContractEntry(
            surfaceId = "contract-rollout-control-snapshot",
            displayName = "RuntimeController.rolloutControlSnapshot",
            kotlinSymbol = "com.ufo.galaxy.runtime.RuntimeController.rolloutControlSnapshot",
            projectedType = "StateFlow<RolloutControlSnapshot>",
            stability = ContractStability.CANONICAL,
            kind = ProjectionKind.REACTIVE_FLOW,
            derivedFromTruthTier = "auth-rollout-control-snapshot",
            consumptionGuidance = "Observe for atomic rollout flag state.  Never read " +
                "individual AppSettings flags directly — use this snapshot for a consistent view.",
            introducedPr = 31
        ),
        ProjectionContractEntry(
            surfaceId = "contract-reconnect-recovery-state",
            displayName = "RuntimeController.reconnectRecoveryState",
            kotlinSymbol = "com.ufo.galaxy.runtime.RuntimeController.reconnectRecoveryState",
            projectedType = "StateFlow<ReconnectRecoveryState>",
            stability = ContractStability.CANONICAL,
            kind = ProjectionKind.REACTIVE_FLOW,
            derivedFromTruthTier = "auth-runtime-state",
            consumptionGuidance = "Observe to surface WS reconnect lifecycle to the user " +
                "(Recovering… / Recovered / Failed).  Do not maintain a separate reconnect " +
                "flag in UI layers.",
            introducedPr = 33
        ),
        ProjectionContractEntry(
            surfaceId = "contract-durable-session-record",
            displayName = "RuntimeController.durableSessionContinuityRecord",
            kotlinSymbol = "com.ufo.galaxy.runtime.RuntimeController.durableSessionContinuityRecord",
            projectedType = "StateFlow<DurableSessionContinuityRecord?>",
            stability = ContractStability.CANONICAL,
            kind = ProjectionKind.REACTIVE_FLOW,
            derivedFromTruthTier = "auth-durable-session-record",
            consumptionGuidance = "Observe for the stable durable session identity that " +
                "persists across WS reconnects.  Use durableSessionId as the most-stable " +
                "Android-side session anchor across reconnects.",
            introducedPr = 1
        ),
        ProjectionContractEntry(
            surfaceId = "contract-canonical-participant",
            displayName = "RuntimeController.currentCanonicalParticipant()",
            kotlinSymbol = "com.ufo.galaxy.runtime.RuntimeController.currentCanonicalParticipant",
            projectedType = "CanonicalParticipantModel?",
            stability = ContractStability.CANONICAL,
            kind = ProjectionKind.POINT_IN_TIME_QUERY,
            derivedFromTruthTier = "proj-canonical-participant",
            consumptionGuidance = "Call to obtain a cross-repo participant model for this " +
                "Android runtime host.  Returns null when no hostDescriptor is available.",
            introducedPr = 6
        ),

        // ── Deprecated surfaces ───────────────────────────────────────────────

        ProjectionContractEntry(
            surfaceId = "contract-legacy-session-snapshot-map",
            displayName = "RuntimeController.currentSessionSnapshot() (deprecated)",
            kotlinSymbol = "com.ufo.galaxy.runtime.RuntimeController.currentSessionSnapshot",
            projectedType = "Map<String, Any>?",
            stability = ContractStability.DEPRECATED,
            kind = ProjectionKind.POINT_IN_TIME_QUERY,
            derivedFromTruthTier = "auth-attached-session",
            canonicalReplacement = "RuntimeController.hostSessionSnapshot " +
                "(StateFlow<AttachedRuntimeHostSessionSnapshot?>)",
            consumptionGuidance = "Do not use.  Migrate all consumers to " +
                "RuntimeController.hostSessionSnapshot or currentHostSessionSnapshot().",
            introducedPr = 10
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns all entries with the given [stability].
     */
    fun byStability(stability: ContractStability): List<ProjectionContractEntry> =
        entries.filter { it.stability == stability }

    /**
     * Returns all entries with the given [kind].
     */
    fun byKind(kind: ProjectionKind): List<ProjectionContractEntry> =
        entries.filter { it.kind == kind }

    /**
     * Returns the entry for [surfaceId], or `null` if not registered.
     */
    fun forId(surfaceId: String): ProjectionContractEntry? =
        entries.firstOrNull { it.surfaceId == surfaceId }

    /**
     * `true` if the given [surfaceId] is registered as [ContractStability.CANONICAL].
     */
    fun isCanonical(surfaceId: String): Boolean =
        forId(surfaceId)?.stability == ContractStability.CANONICAL

    /**
     * `true` if the given [surfaceId] is registered as [ContractStability.DEPRECATED].
     */
    fun isDeprecated(surfaceId: String): Boolean =
        forId(surfaceId)?.stability == ContractStability.DEPRECATED

    /**
     * All [ProjectionContractEntry.surfaceId] values for canonical surfaces.
     */
    val canonicalSurfaceIds: Set<String> =
        byStability(ContractStability.CANONICAL).map { it.surfaceId }.toSet()

    /**
     * All [ProjectionContractEntry.surfaceId] values for deprecated surfaces.
     */
    val deprecatedSurfaceIds: Set<String> =
        byStability(ContractStability.DEPRECATED).map { it.surfaceId }.toSet()

    /**
     * All [ProjectionContractEntry.surfaceId] values for reactive flow surfaces
     * (kind == [ProjectionKind.REACTIVE_FLOW] or [ProjectionKind.EVENT_STREAM]).
     */
    val reactiveContractIds: Set<String> =
        entries
            .filter {
                it.kind == ProjectionKind.REACTIVE_FLOW ||
                    it.kind == ProjectionKind.EVENT_STREAM
            }
            .map { it.surfaceId }
            .toSet()

    // ── Co-derivation invariants ───────────────────────────────────────────────

    /**
     * The surfaceIds of the two canonical projection surfaces that are co-updated
     * atomically in a single `updateHostSessionSnapshot()` call.
     *
     * These two surfaces are guaranteed to be in sync: observing one is sufficient
     * for any consumer that needs both session state and suitability truth.
     */
    val coUpdatedSnapshotAndReadinessSurfaceIds: Set<String> = setOf(
        "contract-host-session-snapshot",
        "contract-target-readiness-projection"
    )

    /**
     * Machine-readable description of the co-derivation invariant between
     * [RuntimeController.hostSessionSnapshot] and
     * [RuntimeController.targetReadinessProjection].
     */
    const val CO_DERIVATION_INVARIANT: String =
        "RuntimeController.hostSessionSnapshot and RuntimeController.targetReadinessProjection " +
            "are always co-updated in a single updateHostSessionSnapshot() call.  Consumers " +
            "that observe both flows will never see a stale intermediate state where the " +
            "snapshot reflects a new session lifecycle event but the readiness projection " +
            "still reflects the prior state."

    // ── System summary constants ──────────────────────────────────────────────

    /**
     * The PR number that introduced this explicit projection contract.
     */
    const val INTRODUCED_PR: Int = 39

    /**
     * Short description of what this contract declares.
     */
    const val DESCRIPTION: String =
        "Explicit host-facing projection contract governing all outward runtime/session " +
            "surfaces exposed by the Android runtime.  Classifies each surface as " +
            "CANONICAL (stable, safe for production dependencies), TRANSITIONAL " +
            "(must migrate), or DEPRECATED (no new consumers).  Declares co-derivation " +
            "invariants so consumers know which surfaces can be safely combined."
}
