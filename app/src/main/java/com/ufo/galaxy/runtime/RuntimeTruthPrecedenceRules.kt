package com.ufo.galaxy.runtime

/**
 * PR-39 — Runtime Truth Precedence Rules.
 *
 * Defines the explicit three-tier truth model for the Android runtime so that the
 * relationship between authoritative internal state, derived snapshots, and
 * downstream host-facing projections is unambiguous and machine-readable.
 *
 * ## Problem addressed
 *
 * The Android runtime exposes state through several overlapping surfaces:
 * internal [RuntimeController] state fields, session-facing [AttachedRuntimeSession],
 * typed snapshots ([AttachedRuntimeHostSessionSnapshot]), and downstream projections
 * ([DelegatedTargetReadinessProjection]).  Without an explicit precedence model, the
 * relationship between these surfaces is implicit, creating opportunities for:
 *
 * - **Projection drift** — a downstream projection diverges from the authoritative
 *   truth because it was derived from a stale or partial source.
 * - **Canonical confusion** — consumers combine multiple partial surfaces to reconstruct
 *   session truth rather than reading from the designated authoritative surface.
 * - **Update coupling** — a mutation in one field is missed in a dependent surface
 *   because the update path is not declared or enforced.
 *
 * ## Three-tier truth model
 *
 * | [TruthTier]                          | Meaning                                                            |
 * |--------------------------------------|--------------------------------------------------------------------|
 * | [TruthTier.AUTHORITATIVE]            | Primary source of truth; directly owned by [RuntimeController].   |
 * | [TruthTier.SNAPSHOT]                 | Derived from authoritative fields; emitted as typed envelope.      |
 * | [TruthTier.PROJECTION]               | Derived from snapshots or authoritative fields; consumed outward.  |
 *
 * ### Tier 1 — Authoritative truth
 *
 * The five authoritative truth fields in [RuntimeController] are the sole primary
 * sources from which all other derived surfaces are computed.  They are never mutated
 * by downstream components — only [RuntimeController]'s internal lifecycle methods
 * write to them:
 *
 * - `_state` — overall runtime lifecycle (Idle / Starting / Active / Failed / LocalOnly).
 *   Written exclusively by [RuntimeController.transitionState].
 * - `_attachedSession` — current [AttachedRuntimeSession] or `null`.
 *   Written exclusively by [RuntimeController.openAttachedSession] /
 *   [RuntimeController.closeAttachedSession].
 * - `_currentRuntimeSessionId` — per-WS-connection UUID.
 *   Written exclusively by [RuntimeController.openAttachedSession].
 * - `_durableSessionContinuityRecord` — activation-era durable session anchor.
 *   Written by [RuntimeController.openAttachedSession], [RuntimeController.stop], and
 *   [RuntimeController.invalidateSession].
 * - `_rolloutControlSnapshot` — rollout gate / kill-switch truth.
 *   Written by [RuntimeController.refreshRolloutControlSnapshot] /
 *   [RuntimeController.applyKillSwitch].
 *
 * ### Tier 2 — Derived snapshot
 *
 * [RuntimeController.hostSessionSnapshot] ([AttachedRuntimeHostSessionSnapshot]) is the
 * single canonical snapshot derived from all relevant Tier-1 fields.  It is the
 * authoritative host-facing session envelope and the **sole permitted input** to all
 * Tier-3 projections that require session data.
 *
 * - Updated exclusively by `RuntimeController.updateHostSessionSnapshot()`.
 * - `updateHostSessionSnapshot()` is called after every mutation of `_attachedSession`
 *   or `_currentRuntimeSessionId`, guaranteeing synchrony.
 * - No component outside [RuntimeController] may construct an [AttachedRuntimeHostSessionSnapshot]
 *   directly from [AttachedRuntimeSession] fields — they must use
 *   [RuntimeController.currentHostSessionSnapshot] or observe
 *   [RuntimeController.hostSessionSnapshot].
 *
 * ### Tier 3 — Downstream projections
 *
 * All downstream host-facing projections must be derived from Tier-2 or Tier-1 surfaces
 * via a declared derivation path:
 *
 * - [RuntimeController.targetReadinessProjection] ([DelegatedTargetReadinessProjection]) —
 *   derived from [AttachedRuntimeHostSessionSnapshot] via
 *   [DelegatedTargetReadinessProjection.from]; co-updated with [hostSessionSnapshot] in
 *   `updateHostSessionSnapshot()`.
 * - [RuntimeController.currentDispatchReadiness] ([RuntimeDispatchReadinessCoordinator.DispatchReadiness]) —
 *   point-in-time combination of `_state`, `_attachedSession`, and `_rolloutControlSnapshot`
 *   via [RuntimeDispatchReadinessCoordinator.resolve]; never stored.
 * - [RuntimeController.currentCanonicalParticipant] ([CanonicalParticipantModel]) —
 *   derived from [AttachedRuntimeHostSessionSnapshot] and [DelegatedTargetReadinessProjection];
 *   point-in-time; never stored.
 *
 * ## Precedence rules
 *
 * Four rules govern how all surfaces interact:
 *
 * 1. **[PRECEDENCE_RULE_AUTHORITATIVE_WRITE_PATH]** — only [RuntimeController] internal
 *    lifecycle methods may mutate Tier-1 fields.
 * 2. **[PRECEDENCE_RULE_SNAPSHOT_SINGLE_GATE]** — Tier-2 snapshots must be derived
 *    exclusively from Tier-1 fields via the declared update path
 *    (`updateHostSessionSnapshot()`).
 * 3. **[PRECEDENCE_RULE_PROJECTION_FROM_SNAPSHOT]** — Tier-3 projections must be derived
 *    from Tier-2 snapshots or Tier-1 fields via the declared derivation path; they must
 *    not independently re-derive session truth from raw [AttachedRuntimeSession] fields.
 * 4. **[PRECEDENCE_RULE_CONSUMER_READ_SNAPSHOT]** — downstream consumers must observe
 *    Tier-2 or Tier-3 surfaces; they must not combine Tier-1 fields independently to
 *    reconstruct session truth.
 *
 * @see AttachedRuntimeHostSessionSnapshot
 * @see DelegatedTargetReadinessProjection
 * @see RuntimeController
 * @see HostFacingProjectionContract
 */
object RuntimeTruthPrecedenceRules {

    // ── Tier enum ─────────────────────────────────────────────────────────────

    /**
     * Three-tier classification for runtime truth and projection surfaces.
     */
    enum class TruthTier(val wireValue: String) {
        /**
         * Primary source of truth.  Directly owned by [RuntimeController] internal
         * fields.  No external component may write to an AUTHORITATIVE field.
         */
        AUTHORITATIVE("authoritative"),

        /**
         * Derived from AUTHORITATIVE fields via a single, declared update path.
         * Emitted as an immutable, typed envelope.  Never directly mutated by
         * downstream consumers.
         */
        SNAPSHOT("snapshot"),

        /**
         * Derived from SNAPSHOT or AUTHORITATIVE surfaces via a declared derivation
         * path.  Consumed by host registries, diagnostics, and cross-repo policy logic.
         * May be point-in-time (not stored) or reactive (stored in a flow).
         */
        PROJECTION("projection")
    }

    // ── Update-path classification ────────────────────────────────────────────

    /**
     * Whether a projection surface is stored in a [kotlinx.coroutines.flow.StateFlow]
     * (reactive) or computed on demand (point-in-time).
     */
    enum class ProjectionStorage(val wireValue: String) {
        /**
         * The projection value is stored in a [kotlinx.coroutines.flow.StateFlow]
         * and can be observed reactively.  Kept in sync with its source surface
         * via an explicit update path.
         */
        REACTIVE("reactive"),

        /**
         * The projection is computed on-demand by a query method.  Not stored;
         * each call returns a fresh value derived from current truth.
         */
        POINT_IN_TIME("point_in_time")
    }

    // ── Truth entry model ─────────────────────────────────────────────────────

    /**
     * A single entry in the truth precedence registry.
     *
     * @param surfaceId        Stable kebab-case identifier for the surface.
     * @param displayName      Human-readable name for diagnostics and documentation.
     * @param tier             [TruthTier] classification for this surface.
     * @param derivedFrom      For SNAPSHOT and PROJECTION tiers: stable comma-separated
     *                         list of surfaceIds this surface is derived from.
     *                         `null` for AUTHORITATIVE surfaces.
     * @param updatePath       Machine-readable description of the sole code path
     *                         that mutates (AUTHORITATIVE, reactive SNAPSHOT/PROJECTION)
     *                         or computes (point-in-time PROJECTION) this surface.
     * @param storage          [ProjectionStorage] for SNAPSHOT / PROJECTION surfaces;
     *                         `null` for AUTHORITATIVE fields (they are always reactive
     *                         via their backing MutableStateFlow).
     * @param rationale        One-sentence rationale for the tier and derivation path.
     */
    data class TruthEntry(
        val surfaceId: String,
        val displayName: String,
        val tier: TruthTier,
        val derivedFrom: String?,
        val updatePath: String,
        val storage: ProjectionStorage?,
        val rationale: String
    )

    // ── Registry ──────────────────────────────────────────────────────────────

    /**
     * Complete truth precedence registry, ordered by tier.
     */
    val entries: List<TruthEntry> = listOf(

        // ── Tier 1 — Authoritative truth ──────────────────────────────────────

        TruthEntry(
            surfaceId = "auth-runtime-state",
            displayName = "RuntimeController._state (StateFlow<RuntimeState>)",
            tier = TruthTier.AUTHORITATIVE,
            derivedFrom = null,
            updatePath = "RuntimeController.transitionState() — sole write path",
            storage = null,
            rationale = "Primary runtime lifecycle truth; all state transitions must go " +
                "through transitionState() so lifecycle events are observable and unexpected " +
                "transitions are logged."
        ),
        TruthEntry(
            surfaceId = "auth-attached-session",
            displayName = "RuntimeController._attachedSession (StateFlow<AttachedRuntimeSession?>)",
            tier = TruthTier.AUTHORITATIVE,
            derivedFrom = null,
            updatePath = "RuntimeController.openAttachedSession() / closeAttachedSession() " +
                "/ recordDelegatedExecutionAccepted() — sole write paths",
            storage = null,
            rationale = "Primary attached-session truth; all session mutations go through " +
                "the three designated internal methods, never through direct field assignment."
        ),
        TruthEntry(
            surfaceId = "auth-runtime-session-id",
            displayName = "RuntimeController._currentRuntimeSessionId (String?)",
            tier = TruthTier.AUTHORITATIVE,
            derivedFrom = null,
            updatePath = "RuntimeController.openAttachedSession() — sole write path; " +
                "UUID.randomUUID() called atomically with session creation",
            storage = null,
            rationale = "Per-WS-connection UUID; always refreshed atomically when a new " +
                "attached session is opened so snapshot projection is never stale."
        ),
        TruthEntry(
            surfaceId = "auth-durable-session-record",
            displayName = "RuntimeController._durableSessionContinuityRecord " +
                "(StateFlow<DurableSessionContinuityRecord?>)",
            tier = TruthTier.AUTHORITATIVE,
            derivedFrom = null,
            updatePath = "RuntimeController.openAttachedSession() (create/increment), " +
                "RuntimeController.stop() (clear), RuntimeController.invalidateSession() (clear)",
            storage = null,
            rationale = "Durable activation-era anchor; persists across WS reconnects; " +
                "cleared only on explicit stop/invalidate."
        ),
        TruthEntry(
            surfaceId = "auth-rollout-control-snapshot",
            displayName = "RuntimeController._rolloutControlSnapshot (StateFlow<RolloutControlSnapshot>)",
            tier = TruthTier.AUTHORITATIVE,
            derivedFrom = null,
            updatePath = "RuntimeController.refreshRolloutControlSnapshot() / " +
                "RuntimeController.applyKillSwitch() — sole write paths; called after " +
                "any AppSettings flag mutation",
            storage = null,
            rationale = "Rollout gate / kill-switch truth; atomic view of all rollout flags."
        ),

        // ── Tier 2 — Derived snapshot ─────────────────────────────────────────

        TruthEntry(
            surfaceId = "snap-host-session-snapshot",
            displayName = "RuntimeController.hostSessionSnapshot " +
                "(StateFlow<AttachedRuntimeHostSessionSnapshot?>)",
            tier = TruthTier.SNAPSHOT,
            derivedFrom = "auth-attached-session, auth-runtime-session-id, " +
                "auth-durable-session-record",
            updatePath = "RuntimeController.updateHostSessionSnapshot() — sole write path; " +
                "called after every mutation of _attachedSession or _currentRuntimeSessionId",
            storage = ProjectionStorage.REACTIVE,
            rationale = "Canonical typed session envelope for host-registry consumption; " +
                "derived exclusively from the three authoritative session fields via the " +
                "single update gate, preventing derivation drift."
        ),

        // ── Tier 3 — Downstream projections ──────────────────────────────────

        TruthEntry(
            surfaceId = "proj-target-readiness",
            displayName = "RuntimeController.targetReadinessProjection " +
                "(StateFlow<DelegatedTargetReadinessProjection?>)",
            tier = TruthTier.PROJECTION,
            derivedFrom = "snap-host-session-snapshot",
            updatePath = "RuntimeController.updateHostSessionSnapshot() → " +
                "DelegatedTargetReadinessProjection.from(snapshot) — co-updated with " +
                "snap-host-session-snapshot in a single atomic method call",
            storage = ProjectionStorage.REACTIVE,
            rationale = "Pre-computed suitability projection; always co-updated with the " +
                "snapshot in updateHostSessionSnapshot() so the two flows are never stale " +
                "relative to each other."
        ),
        TruthEntry(
            surfaceId = "proj-dispatch-readiness",
            displayName = "RuntimeController.currentDispatchReadiness() (DispatchReadiness)",
            tier = TruthTier.PROJECTION,
            derivedFrom = "auth-runtime-state, auth-attached-session, auth-rollout-control-snapshot",
            updatePath = "RuntimeController.currentDispatchReadiness() — point-in-time query; " +
                "not stored; calls RuntimeDispatchReadinessCoordinator.resolve() on demand",
            storage = ProjectionStorage.POINT_IN_TIME,
            rationale = "Dispatch eligibility answer combining runtime lifecycle, session " +
                "attachment, and rollout gates; not cached to prevent staleness."
        ),
        TruthEntry(
            surfaceId = "proj-canonical-participant",
            displayName = "RuntimeController.currentCanonicalParticipant() (CanonicalParticipantModel?)",
            tier = TruthTier.PROJECTION,
            derivedFrom = "snap-host-session-snapshot, proj-target-readiness",
            updatePath = "RuntimeController.currentCanonicalParticipant() — point-in-time query; " +
                "not stored; derives from currentHostSessionSnapshot() and " +
                "DelegatedTargetReadinessProjection.from()",
            storage = ProjectionStorage.POINT_IN_TIME,
            rationale = "Cross-repo participant model projection; derived from snapshot and " +
                "readiness for external consumption; not stored because it aggregates other " +
                "point-in-time surfaces."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns all entries with the given [tier].
     */
    fun byTier(tier: TruthTier): List<TruthEntry> = entries.filter { it.tier == tier }

    /**
     * Returns the entry for [surfaceId], or `null` if not registered.
     */
    fun forId(surfaceId: String): TruthEntry? = entries.firstOrNull { it.surfaceId == surfaceId }

    /**
     * `true` if the given [surfaceId] is registered in the registry.
     */
    fun isRegistered(surfaceId: String): Boolean = forId(surfaceId) != null

    /** All surface IDs classified as [TruthTier.AUTHORITATIVE]. */
    val authoritativeSurfaceIds: Set<String> =
        byTier(TruthTier.AUTHORITATIVE).map { it.surfaceId }.toSet()

    /** All surface IDs classified as [TruthTier.SNAPSHOT]. */
    val snapshotSurfaceIds: Set<String> =
        byTier(TruthTier.SNAPSHOT).map { it.surfaceId }.toSet()

    /** All surface IDs classified as [TruthTier.PROJECTION]. */
    val projectionSurfaceIds: Set<String> =
        byTier(TruthTier.PROJECTION).map { it.surfaceId }.toSet()

    /**
     * Returns all entries whose [TruthEntry.storage] is
     * [ProjectionStorage.REACTIVE] (stored in a [kotlinx.coroutines.flow.StateFlow]).
     */
    val reactiveProjectionIds: Set<String> =
        entries.filter { it.storage == ProjectionStorage.REACTIVE }
            .map { it.surfaceId }
            .toSet()

    /**
     * Returns all entries whose [TruthEntry.storage] is
     * [ProjectionStorage.POINT_IN_TIME] (computed on-demand, not stored).
     */
    val pointInTimeProjectionIds: Set<String> =
        entries.filter { it.storage == ProjectionStorage.POINT_IN_TIME }
            .map { it.surfaceId }
            .toSet()

    // ── Precedence rules ──────────────────────────────────────────────────────

    /**
     * Rule 1: Only [RuntimeController] internal lifecycle methods may write Tier-1
     * (AUTHORITATIVE) fields.  No downstream component, test fixture, or projection
     * helper may mutate an AUTHORITATIVE field directly.
     */
    const val PRECEDENCE_RULE_AUTHORITATIVE_WRITE_PATH: String =
        "Tier-1 (AUTHORITATIVE) fields are owned exclusively by RuntimeController " +
            "internal lifecycle methods.  No external component may mutate them directly."

    /**
     * Rule 2: Tier-2 (SNAPSHOT) values must be derived exclusively from Tier-1 fields
     * via [RuntimeController]'s `updateHostSessionSnapshot()` — the single, declared
     * update gate.  No component may construct [AttachedRuntimeHostSessionSnapshot]
     * directly from raw [AttachedRuntimeSession] fields outside this gate.
     */
    const val PRECEDENCE_RULE_SNAPSHOT_SINGLE_GATE: String =
        "Tier-2 (SNAPSHOT) values are derived from Tier-1 fields exclusively via " +
            "RuntimeController.updateHostSessionSnapshot() — the single canonical update gate."

    /**
     * Rule 3: Tier-3 (PROJECTION) values must be derived from Tier-2 snapshots or
     * Tier-1 fields via the declared derivation path.  Projections must not
     * independently re-derive session truth by combining raw [AttachedRuntimeSession]
     * fields.
     */
    const val PRECEDENCE_RULE_PROJECTION_FROM_SNAPSHOT: String =
        "Tier-3 (PROJECTION) values are derived from the Tier-2 snapshot or Tier-1 " +
            "fields via the declared derivation path; never from raw session fields."

    /**
     * Rule 4: Downstream consumers (host registries, diagnostics, UI, tests) must
     * observe Tier-2 or Tier-3 surfaces only.  They must not combine Tier-1 fields
     * independently to reconstruct session truth, as this bypasses the single-gate
     * guarantee and risks projection drift.
     */
    const val PRECEDENCE_RULE_CONSUMER_READ_SNAPSHOT: String =
        "Downstream consumers must observe Tier-2 or Tier-3 surfaces.  They must not " +
            "combine Tier-1 fields independently to reconstruct session truth."

    /**
     * All four precedence rules as an ordered list.
     */
    val precedenceRules: List<String> = listOf(
        PRECEDENCE_RULE_AUTHORITATIVE_WRITE_PATH,
        PRECEDENCE_RULE_SNAPSHOT_SINGLE_GATE,
        PRECEDENCE_RULE_PROJECTION_FROM_SNAPSHOT,
        PRECEDENCE_RULE_CONSUMER_READ_SNAPSHOT
    )

    // ── Update-path invariants ────────────────────────────────────────────────

    /**
     * The single method name that is the sole write path for the Tier-2 snapshot
     * and its co-derived Tier-3 projections.
     *
     * Any mutation of [RuntimeController]'s authoritative session fields must
     * end by calling this method to keep Tier-2 and Tier-3 surfaces synchronised.
     */
    const val SNAPSHOT_UPDATE_GATE: String = "RuntimeController.updateHostSessionSnapshot"

    /**
     * The single method name that is the sole write path for Tier-1 runtime state.
     */
    const val STATE_TRANSITION_GATE: String = "RuntimeController.transitionState"

    // ── System summary constants ──────────────────────────────────────────────

    /**
     * The PR number that introduced these truth precedence rules.
     */
    const val INTRODUCED_PR: Int = 39

    /**
     * Short description of what this object declares.
     */
    const val DESCRIPTION: String =
        "Three-tier truth precedence model for the Android runtime: " +
            "AUTHORITATIVE (RuntimeController internal fields) → " +
            "SNAPSHOT (AttachedRuntimeHostSessionSnapshot) → " +
            "PROJECTION (DelegatedTargetReadinessProjection, DispatchReadiness, " +
            "CanonicalParticipantModel).  All derivation paths are explicit and gated " +
            "through single, declared update methods to prevent projection drift."
}
