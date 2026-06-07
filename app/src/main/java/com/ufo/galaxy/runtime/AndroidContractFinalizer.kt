package com.ufo.galaxy.runtime

/**
 * PR-41 — Android Contract Finalization.
 *
 * Declares the explicit, finalized Android-side contract responsibility boundaries
 * for all major runtime participation areas: readiness, session, transport continuity,
 * host-facing state, snapshots/projections, and dispatch eligibility.
 *
 * ## Purpose
 *
 * After the lifecycle, protocol, projection, and transport convergence work in earlier PRs,
 * residual ambiguity remains in how Android declares and enforces its contract responsibilities
 * relative to the center-side V2 runtime.  Without explicit boundary declarations, contract
 * drift can re-emerge through:
 *
 * - Overlapping contract interpretations where both Android and center claim ownership.
 * - Loosely fenced host-facing behaviors that allow center consumers to take undocumented
 *   dependencies on transitional surfaces.
 * - Ambiguous readiness/session responsibility splits that enable silent divergence from
 *   center expectations.
 *
 * [AndroidContractFinalizer] closes this gap by making every major contract boundary
 * explicit and machine-readable.  It does not change runtime behavior; it documents
 * the finalized contract responsibilities so that future work can build on a stable model.
 *
 * ## Responsibility clarity
 *
 * | [ResponsibilityClarity]   | Meaning                                                          |
 * |---------------------------|------------------------------------------------------------------|
 * | [ResponsibilityClarity.EXPLICIT]    | Boundary is clear; Android responsibility is documented and enforced by a canonical surface. |
 * | [ResponsibilityClarity.TRANSITIONAL]| Boundary is partially clarified; a convergence path exists but is not yet complete. |
 * | [ResponsibilityClarity.AMBIGUOUS]   | Boundary still carries unresolved semantic overlap with the center side. |
 *
 * ## Drift risk
 *
 * | [DriftRisk]          | Meaning                                                                          |
 * |----------------------|----------------------------------------------------------------------------------|
 * | [DriftRisk.LOW]      | Explicit canonical surface protects against divergence; no drift expected.       |
 * | [DriftRisk.MEDIUM]   | Some residual transitional surfaces exist; drift is possible but bounded.        |
 * | [DriftRisk.HIGH]     | Ambiguity or multiple competing surfaces create significant divergence risk.     |
 *
 * ## Boundary ownership
 *
 * | [BoundaryOwnership]          | Meaning                                                               |
 * |------------------------------|-----------------------------------------------------------------------|
 * | [BoundaryOwnership.ANDROID_OWNED]  | Android is the sole authority; center must consume Android projections. |
 * | [BoundaryOwnership.CENTER_OWNED]   | Center is the sole authority; Android must follow center-published contracts. |
 * | [BoundaryOwnership.SHARED]         | Both Android and center have acknowledged responsibility at this boundary. |
 */
object AndroidContractFinalizer {

    // ── Responsibility clarity ────────────────────────────────────────────────

    /**
     * How clearly the Android-side contract responsibility is defined at a given boundary.
     */
    enum class ResponsibilityClarity(val wireValue: String) {
        /**
         * The boundary is fully explicit.  Android's responsibility is documented by a
         * canonical surface (e.g. [RuntimeTruthPrecedenceRules], [HostFacingProjectionContract]).
         * No residual ambiguity about what Android owns at this boundary.
         */
        EXPLICIT("explicit"),

        /**
         * The boundary is partially clarified.  A canonical replacement exists and is
         * preferred, but transitional surfaces remain operationally active.  Convergence
         * work is needed before the boundary can be declared fully explicit.
         */
        TRANSITIONAL("transitional"),

        /**
         * The boundary still carries unresolved semantic overlap with the center side.
         * Both parties may have competing interpretations of who owns what.  Requires
         * cross-repo coordination to resolve.
         */
        AMBIGUOUS("ambiguous");

        companion object {
            /** Returns the [ResponsibilityClarity] with [value], or `null` if unknown. */
            fun fromValue(value: String): ResponsibilityClarity? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Drift risk ────────────────────────────────────────────────────────────

    /**
     * Risk that Android behavior will diverge from center expectations at a contract boundary.
     */
    enum class DriftRisk(val wireValue: String) {
        /**
         * Low risk.  A canonical surface explicitly governs the boundary; behavioral divergence
         * requires violating the canonical surface contract.
         */
        LOW("low"),

        /**
         * Medium risk.  Some transitional surfaces remain; a consumer choosing the wrong surface
         * could observe divergent behavior, but canonical surfaces are the default path.
         */
        MEDIUM("medium"),

        /**
         * High risk.  Ambiguity, competing interpretations, or multiple active surfaces at this
         * boundary mean that behavioral divergence is plausible without explicit consumer guidance.
         */
        HIGH("high");

        companion object {
            /** Returns the [DriftRisk] with [value], or `null` if unknown. */
            fun fromValue(value: String): DriftRisk? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Boundary ownership ────────────────────────────────────────────────────

    /**
     * Which side owns the contract responsibility at a given boundary.
     */
    enum class BoundaryOwnership(val wireValue: String) {
        /**
         * Android is the sole authority at this boundary.  Center-side consumers must
         * observe Android's canonical projections; they must not bypass Android's
         * declared truth surfaces.
         */
        ANDROID_OWNED("android_owned"),

        /**
         * Center is the sole authority at this boundary.  Android must consume and follow
         * center-published schemas, vocabularies, and lifecycle directives.
         */
        CENTER_OWNED("center_owned"),

        /**
         * Shared responsibility.  Both Android and center have acknowledged ownership at this
         * boundary and participate in a cooperative contract (e.g. session identifier vocabulary
         * that spans both repos).
         */
        SHARED("shared");

        companion object {
            /** Returns the [BoundaryOwnership] with [value], or `null` if unknown. */
            fun fromValue(value: String): BoundaryOwnership? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Contract boundary entry ───────────────────────────────────────────────

    /**
     * A single Android-side contract responsibility boundary declaration.
     *
     * @param boundaryId         Stable machine-readable identifier for this boundary.
     * @param displayName        Short human-readable name of the boundary area.
     * @param responsibilityArea The runtime participation area this boundary governs
     *                           (e.g. "readiness", "session", "transport_continuity").
     * @param clarity            [ResponsibilityClarity] for this boundary.
     * @param driftRisk          [DriftRisk] if this boundary is left unaddressed.
     * @param ownership          [BoundaryOwnership] classification.
     * @param canonicalSurface   The canonical Android surface that governs this boundary
     *                           (Kotlin symbol or object name).
     * @param finalizationNote   One-sentence note on what was finalized at this boundary
     *                           in PR-41 and what remains.
     * @param residualAmbiguity  Description of any remaining cross-repo ambiguity after
     *                           PR-41, or `null` if fully resolved.
     */
    data class ContractBoundaryEntry(
        val boundaryId: String,
        val displayName: String,
        val responsibilityArea: String,
        val clarity: ResponsibilityClarity,
        val driftRisk: DriftRisk,
        val ownership: BoundaryOwnership,
        val canonicalSurface: String,
        val finalizationNote: String,
        val residualAmbiguity: String? = null
    )

    // ── Boundary registry ─────────────────────────────────────────────────────

    /**
     * Complete registry of Android-side contract responsibility boundaries, covering
     * all major runtime participation areas touched in PR-41.
     *
     * Ordered by [DriftRisk] descending so high-risk boundaries are evaluated first.
     */
    val boundaries: List<ContractBoundaryEntry> = listOf(

        // ── Readiness boundary ────────────────────────────────────────────────

        ContractBoundaryEntry(
            boundaryId = "readiness-declaration-boundary",
            displayName = "Android Readiness Declaration",
            responsibilityArea = "readiness",
            clarity = ResponsibilityClarity.EXPLICIT,
            driftRisk = DriftRisk.LOW,
            ownership = BoundaryOwnership.ANDROID_OWNED,
            canonicalSurface = "RuntimeController.targetReadinessProjection " +
                "(DelegatedTargetReadinessProjection)",
            finalizationNote = "Android's readiness contract is fully governed by " +
                "RuntimeController.targetReadinessProjection; all external consumers " +
                "observe this canonical surface exclusively.",
            residualAmbiguity = null
        ),

        // ── Session participation boundary ────────────────────────────────────

        ContractBoundaryEntry(
            boundaryId = "session-participation-boundary",
            displayName = "Android Session Participation",
            responsibilityArea = "session",
            clarity = ResponsibilityClarity.EXPLICIT,
            driftRisk = DriftRisk.LOW,
            ownership = BoundaryOwnership.ANDROID_OWNED,
            canonicalSurface = "RuntimeController.hostSessionSnapshot " +
                "(AttachedRuntimeHostSessionSnapshot)",
            finalizationNote = "Session participation boundaries are governed by the " +
                "canonical seven-family session axis (CanonicalSessionAxis) and the " +
                "host-session snapshot projection; truthBinding and transportContinuityBinding " +
                "per family make derivation explicit.",
            residualAmbiguity = null
        ),

        // ── Transport continuity boundary ─────────────────────────────────────

        ContractBoundaryEntry(
            boundaryId = "transport-continuity-boundary",
            displayName = "Android Transport Continuity",
            responsibilityArea = "transport_continuity",
            clarity = ResponsibilityClarity.EXPLICIT,
            driftRisk = DriftRisk.LOW,
            ownership = BoundaryOwnership.ANDROID_OWNED,
            canonicalSurface = "TransportContinuityAnchor + MediaTransportLifecycleBridge",
            finalizationNote = "Transport continuity policies and task-lifecycle adaptations are " +
                "explicitly governed by TransportContinuityAnchor and MediaTransportLifecycleBridge " +
                "(PR-40); no ambiguous transport-driven side paths remain in the canonical model.",
            residualAmbiguity = null
        ),

        // ── Host-facing state exposure boundary ───────────────────────────────

        ContractBoundaryEntry(
            boundaryId = "host-facing-state-boundary",
            displayName = "Android Host-Facing State Exposure",
            responsibilityArea = "host_facing_state",
            clarity = ResponsibilityClarity.EXPLICIT,
            driftRisk = DriftRisk.MEDIUM,
            ownership = BoundaryOwnership.ANDROID_OWNED,
            canonicalSurface = "HostFacingProjectionContract",
            finalizationNote = "Canonical and deprecated host-facing surfaces are explicitly " +
                "classified by HostFacingProjectionContract (PR-39); the legacy " +
                "currentSessionSnapshot() map bridge remains operationally active but is " +
                "fenced at HIGH_RISK_ACTIVE in CompatibilitySurfaceRetirementRegistry.",
            residualAmbiguity = "Legacy map bridge (currentSessionSnapshot) is still active; " +
                "full retirement requires all host-facing consumers to migrate."
        ),

        // ── Snapshot / projection boundary ───────────────────────────────────

        ContractBoundaryEntry(
            boundaryId = "snapshot-projection-boundary",
            displayName = "Android Snapshot / Projection Derivation",
            responsibilityArea = "snapshot_projection",
            clarity = ResponsibilityClarity.EXPLICIT,
            driftRisk = DriftRisk.LOW,
            ownership = BoundaryOwnership.ANDROID_OWNED,
            canonicalSurface = "RuntimeTruthPrecedenceRules",
            finalizationNote = "The three-tier truth model (AUTHORITATIVE → SNAPSHOT → PROJECTION) " +
                "is fully declared by RuntimeTruthPrecedenceRules (PR-39); snapshot derivation " +
                "paths are machine-readable and derivation invariants are documented.",
            residualAmbiguity = null
        ),

        // ── Dispatch eligibility boundary ─────────────────────────────────────

        ContractBoundaryEntry(
            boundaryId = "dispatch-eligibility-boundary",
            displayName = "Android Dispatch Eligibility",
            responsibilityArea = "dispatch_eligibility",
            clarity = ResponsibilityClarity.EXPLICIT,
            driftRisk = DriftRisk.LOW,
            ownership = BoundaryOwnership.ANDROID_OWNED,
            canonicalSurface = "RuntimeDispatchReadinessCoordinator + CanonicalDispatchChain",
            finalizationNote = "Dispatch eligibility is governed by RuntimeDispatchReadinessCoordinator " +
                "(PR-37) combining runtime/session/rollout state; transport-adapted path resolution " +
                "(PR-40) extends this with transport condition filtering.",
            residualAmbiguity = null
        ),

        // ── Session identifier vocabulary boundary ────────────────────────────

        ContractBoundaryEntry(
            boundaryId = "session-identifier-vocabulary-boundary",
            displayName = "Android Session Identifier Vocabulary",
            responsibilityArea = "session_identifier_vocabulary",
            clarity = ResponsibilityClarity.TRANSITIONAL,
            driftRisk = DriftRisk.MEDIUM,
            ownership = BoundaryOwnership.SHARED,
            canonicalSurface = "CanonicalSessionAxis (CanonicalSessionFamily + wireAlias)",
            finalizationNote = "Android session identifier families are explicitly mapped to " +
                "canonical cross-repo terms via CanonicalSessionAxis; two families still carry " +
                "wire aliases (CONTROL_SESSION: session_id→control_session_id; " +
                "MESH_SESSION: mesh_id→mesh_session_id) pending center-Android naming convergence.",
            residualAmbiguity = "Wire alias convergence (session_id and mesh_id) requires " +
                "coordinated center-Android vocabulary rollout before naming can be normalized."
        ),

        // ── Protocol compatibility retirement boundary ────────────────────────

        ContractBoundaryEntry(
            boundaryId = "protocol-compatibility-retirement-boundary",
            displayName = "Android Protocol Compatibility Retirement",
            responsibilityArea = "protocol_compatibility",
            clarity = ResponsibilityClarity.TRANSITIONAL,
            driftRisk = DriftRisk.MEDIUM,
            ownership = BoundaryOwnership.SHARED,
            canonicalSurface = "CompatibilitySurfaceRetirementRegistry + CompatibilityRetirementFence",
            finalizationNote = "All high-risk compatibility surfaces are inventoried and fenced in " +
                "CompatibilitySurfaceRetirementRegistry; PR-41 introduces CompatibilityRetirementFence " +
                "to record explicit fence/retire/demotion decisions for each surface.",
            residualAmbiguity = "Several RETIRE_AFTER_COORDINATION surfaces require center confirmation " +
                "before Android can unilaterally retire them."
        ),

        // ── Long-tail dispatch compatibility boundary ─────────────────────────

        ContractBoundaryEntry(
            boundaryId = "long-tail-dispatch-boundary",
            displayName = "Android Long-Tail Dispatch Compatibility",
            responsibilityArea = "long_tail_dispatch",
            clarity = ResponsibilityClarity.EXPLICIT,
            driftRisk = DriftRisk.LOW,
            ownership = BoundaryOwnership.SHARED,
            canonicalSurface = "LongTailCompatibilityRegistry + ProtocolConvergenceBoundary",
            finalizationNote = "All long-tail message-type handlers are classified by " +
                "LongTailCompatibilityRegistry with explicit convergence actions (PR-38); " +
                "ProtocolConvergenceBoundary documents the interaction between each protocol " +
                "surface and the canonical runtime model.",
            residualAmbiguity = null
        ),

        // ── Runtime lifecycle state authority boundary ────────────────────────

        ContractBoundaryEntry(
            boundaryId = "runtime-lifecycle-authority-boundary",
            displayName = "Android Runtime Lifecycle Authority",
            responsibilityArea = "runtime_lifecycle",
            clarity = ResponsibilityClarity.EXPLICIT,
            driftRisk = DriftRisk.LOW,
            ownership = BoundaryOwnership.ANDROID_OWNED,
            canonicalSurface = "RuntimeController.state + RuntimeLifecycleTransitionEvent",
            finalizationNote = "Android is the sole lifecycle authority; state transitions are " +
                "governed by RuntimeController and exposed as RuntimeLifecycleTransitionEvent " +
                "(PR-37); the permanent WS listener enforces lifecycle invariants on reconnect.",
            residualAmbiguity = null
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /** Returns all boundaries with the given [clarity] classification. */
    fun byClarity(clarity: ResponsibilityClarity): List<ContractBoundaryEntry> =
        boundaries.filter { it.clarity == clarity }

    /** Returns all boundaries with the given [risk] classification. */
    fun byDriftRisk(risk: DriftRisk): List<ContractBoundaryEntry> =
        boundaries.filter { it.driftRisk == risk }

    /** Returns all boundaries with the given [ownership] classification. */
    fun byOwnership(ownership: BoundaryOwnership): List<ContractBoundaryEntry> =
        boundaries.filter { it.ownership == ownership }

    /** Returns the boundary entry for [boundaryId], or `null` if not registered. */
    fun forId(boundaryId: String): ContractBoundaryEntry? =
        boundaries.firstOrNull { it.boundaryId == boundaryId }

    /**
     * Set of boundary IDs where [DriftRisk.HIGH] residual ambiguity exists.
     *
     * These boundaries require the highest-priority attention; unaddressed they allow
     * Android behavior to diverge materially from center expectations.
     */
    val highDriftRiskBoundaryIds: Set<String> =
        boundaries.filter { it.driftRisk == DriftRisk.HIGH }.map { it.boundaryId }.toSet()

    /**
     * Set of boundary IDs still classified as [ResponsibilityClarity.AMBIGUOUS].
     *
     * Boundaries in this set require cross-repo coordination before they can be
     * finalized.  No new feature work should be routed through ambiguous boundaries.
     */
    val ambiguousBoundaryIds: Set<String> =
        boundaries.filter { it.clarity == ResponsibilityClarity.AMBIGUOUS }.map { it.boundaryId }.toSet()

    /**
     * Set of boundary IDs that are [ResponsibilityClarity.EXPLICIT] and [DriftRisk.LOW].
     *
     * These are the finalized, stable Android contract responsibility boundaries.
     * Future extension work should target these boundaries' canonical surfaces.
     */
    val finalizedBoundaryIds: Set<String> =
        boundaries
            .filter {
                it.clarity == ResponsibilityClarity.EXPLICIT && it.driftRisk == DriftRisk.LOW
            }
            .map { it.boundaryId }
            .toSet()

    /**
     * Set of boundary IDs that still carry residual ambiguity
     * ([ContractBoundaryEntry.residualAmbiguity] is non-null).
     *
     * Consumers should treat these boundaries as requiring additional convergence work
     * before full contract clarity is achieved.
     */
    val boundariesWithResidualAmbiguity: Set<String> =
        boundaries.filter { it.residualAmbiguity != null }.map { it.boundaryId }.toSet()

    // ── System summary constants ──────────────────────────────────────────────

    /**
     * The PR number that introduced this contract finalization model.
     */
    const val INTRODUCED_PR: Int = 41

    /**
     * Short description of what this object declares.
     */
    const val DESCRIPTION: String =
        "Explicit Android-side contract responsibility boundary registry for PR-41 finalization. " +
            "Declares clarity, drift risk, and ownership per major runtime participation area. " +
            "Documents what was finalized in PR-41 and what residual ambiguity remains. " +
            "Provides machine-readable invariant governance for future extension work."

    /**
     * Invariant: all finalized boundaries must have an explicit canonical surface
     * (non-blank [ContractBoundaryEntry.canonicalSurface]) and no residual ambiguity.
     *
     * Consumers can assert this invariant in tests to catch regressions where a
     * previously finalized boundary loses its canonical surface reference.
     */
    const val FINALIZATION_INVARIANT: String =
        "Every boundary classified as EXPLICIT with LOW drift risk must reference a " +
            "non-blank canonicalSurface and have a null residualAmbiguity field. " +
            "Adding new EXPLICIT/LOW boundaries without a canonical surface is a contract violation."
}
