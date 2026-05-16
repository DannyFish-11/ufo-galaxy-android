package com.ufo.galaxy.runtime

/**
 * PR-11 — Architecture stabilization baseline.
 *
 * Defines the explicit stabilization baseline for UFO Galaxy Android following the
 * governance/dispatch/truth/compatibility work established through PR-1 to PR-10
 * (and PR-11 through PR-35 in the full PR series).
 *
 * ## Purpose
 *
 * The prior PR series established canonical governance, execution-chain clarity,
 * truth/projection separation, and compatibility retirement mechanisms.  However,
 * there was no single explicit document that said **what the system is now considered
 * safe to build on**.  This object provides that declaration so that:
 *
 * - Canonical surfaces that are ready for extension are clearly identified.
 * - Transitional surfaces that must not be extended are explicitly labelled.
 * - Future contributors know where to extend the system versus where to converge
 *   toward the canonical path.
 * - The repositories can evolve coherently without reintroducing governance ambiguity
 *   or compatibility drift.
 *
 * ## Surface stability classifications
 *
 * | [SurfaceStability]                   | Meaning                                                                  |
 * |--------------------------------------|--------------------------------------------------------------------------|
 * | [SurfaceStability.CANONICAL_STABLE]  | Safe to build on.  Extension is permitted and encouraged.                |
 * | [SurfaceStability.CANONICAL_FROZEN]  | Canonical but intentionally frozen; extend by wrapping, not modifying.   |
 * | [SurfaceStability.TRANSITIONAL]      | Retained for compatibility.  Must not be extended.  Converge away.       |
 * | [SurfaceStability.RETIREMENT_GATED]  | Awaiting cross-repo coordination before retirement.  No new dependencies.|
 *
 * ## Extension vs convergence guidance
 *
 * | [ExtensionGuidance]               | Meaning                                                                  |
 * |-----------------------------------|--------------------------------------------------------------------------|
 * | [ExtensionGuidance.EXTEND]        | The canonical home for this category of future work.                     |
 * | [ExtensionGuidance.CONVERGE]      | Future work must migrate consumers to the canonical replacement.          |
 * | [ExtensionGuidance.WRAP_ONLY]     | Extend by adding wrapper/observer; do not modify the frozen surface.      |
 * | [ExtensionGuidance.NO_NEW_WORK]   | Surface must not receive new logic.  Retire when gate clears.             |
 *
 * ## Canonical stable surfaces
 *
 * The following surfaces are considered **canonically stable** after the governance work:
 *
 * - **Lifecycle truth** — `RuntimeController.state`, `RuntimeController.hostSessionSnapshot`,
 *   `RuntimeController.targetReadinessProjection`, `RuntimeController.reconnectRecoveryState`.
 * - **Dispatch governance** — `CanonicalDispatchChain`, `RolloutControlSnapshot`, `ExecutionRouteTag`.
 * - **Signal emission** — `DelegatedExecutionSignal`, `EmittedSignalLedger`.
 * - **Session identity** — `CanonicalSessionAxis`, `AttachedRuntimeHostSessionSnapshot`.
 * - **Projection models** — `CanonicalParticipantModel`, `CanonicalDeviceModel`,
 *   `CanonicalCapabilityProviderModel` (read-model contracts; not lifecycle truth owners).
 * - **Protocol alignment** — `UgcpSharedSchemaAlignment`, `UgcpProtocolConsistencyRules`.
 * - **Compatibility inventory** — `CompatibilitySurfaceRetirementRegistry`, `LongTailCompatibilityRegistry`.
 * - **Rollout/kill-switch** — `RolloutControlSnapshot.applyKillSwitch()`.
 * - **Failure classification** — `CrossDeviceSetupError`, `TakeoverFallbackEvent`.
 * - **Observability** — all `GalaxyLogger.TAG_*` constants added through PR-35.
 * - **Identity linkage** — `RuntimeIdentityContracts`.
 *
 * ## Transitional surfaces
 *
 * The following surfaces remain transitional and **must not** be extended as canonical
 * architecture:
 *
 * - Legacy wire-value alias surfaces in `UgcpSharedSchemaAlignment`
 *   (entries classified as `TRANSITIONAL_COMPATIBILITY`).
 * - `CompatibilitySurfaceRetirementRegistry` entries with tier `HIGH_RISK_ACTIVE`
 *   (specifically `currentSessionSnapshot` legacy bridge; `registrationError` has been
 *   deprecated and its consumers migrated in PR-36).
 * - `LongTailCompatibilityRegistry` entries with tier `TRANSITIONAL`.
 * - Protocol surfaces still classified as `ProtocolSurfaceClass.TRANSITIONAL_COMPATIBILITY`
 *   in `UgcpProtocolConsistencyRules`.
 *
 * @see CanonicalDispatchChain
 * @see CompatibilitySurfaceRetirementRegistry
 * @see LongTailCompatibilityRegistry
 * @see RolloutControlSnapshot
 * @see CanonicalSessionAxis
 */
object StabilizationBaseline {

    // ── Surface-stability classification ──────────────────────────────────────

    /**
     * Stability classification for a surface in the stabilization baseline.
     */
    enum class SurfaceStability {
        /**
         * The surface is canonical and considered safe to build on.
         * Extension is permitted and encouraged.
         * New cross-device, delegation, signal, or session logic should extend this surface.
         */
        CANONICAL_STABLE,

        /**
         * The surface is canonical but intentionally frozen to prevent drift.
         * Consumers should wrap or observe the surface rather than modifying it.
         * Examples: identity contract constants, frozen wire-value vocabulary.
         */
        CANONICAL_FROZEN,

        /**
         * The surface is retained for backward compatibility only.
         * It must not receive new logic.
         * Future work must converge consumers toward the canonical replacement
         * recorded in [BaselineSurfaceEntry.canonicalReplacement].
         */
        TRANSITIONAL,

        /**
         * Retirement is gated on cross-repo or external coordination.
         * No new Android-side dependencies must be introduced.
         * The surface must be treated as if already retired from an extension perspective.
         */
        RETIREMENT_GATED
    }

    // ── Extension guidance ────────────────────────────────────────────────────

    /**
     * Extension guidance for a domain category of future work.
     */
    enum class ExtensionGuidance {
        /**
         * This is the canonical home for future work in this domain.
         * New features should extend through this surface or its designated extension points.
         */
        EXTEND,

        /**
         * Future work in this domain must migrate remaining consumers to the canonical
         * replacement before the transitional surface can be retired.
         */
        CONVERGE,

        /**
         * The surface is frozen.  Future work must interact with it through wrappers or
         * observers only; no direct modification is permitted.
         */
        WRAP_ONLY,

        /**
         * This surface must not receive new logic.
         * Retire it when the gate condition recorded in [BaselineSurfaceEntry.retirementGate]
         * is satisfied.
         */
        NO_NEW_WORK
    }

    // ── Baseline entry ────────────────────────────────────────────────────────

    /**
     * A single entry in the stabilization baseline surface registry.
     *
     * @param surfaceId           Stable identifier for the surface (kebab-case).
     * @param displayName         Human-readable surface name.
     * @param packagePath         Kotlin package or file path that owns the surface.
     * @param stability           The [SurfaceStability] classification of this surface.
     * @param extensionGuidance   The [ExtensionGuidance] that applies to this surface.
     * @param canonicalReplacement For [SurfaceStability.TRANSITIONAL] and
     *                             [SurfaceStability.RETIREMENT_GATED] entries: the canonical
     *                             surface that replaces this one.  `null` for stable surfaces.
     * @param retirementGate      For [SurfaceStability.RETIREMENT_GATED] entries: the
     *                            condition that must be met before retirement.  `null` for
     *                            stable surfaces.
     * @param rationale           One-sentence rationale for this classification.
     * @param introducedPr        The PR number that introduced or last classified this surface.
     */
    data class BaselineSurfaceEntry(
        val surfaceId: String,
        val displayName: String,
        val packagePath: String,
        val stability: SurfaceStability,
        val extensionGuidance: ExtensionGuidance,
        val canonicalReplacement: String? = null,
        val retirementGate: String? = null,
        val rationale: String,
        val introducedPr: Int
    )

    // ── Canonical-stable surface registry ─────────────────────────────────────

    /**
     * Complete stabilization baseline registry, ordered by stability tier and domain.
     *
     * Use [byStability], [byGuidance], [forId], [canonicalStableSurfaceIds], and
     * [transitionalSurfaceIds] for targeted queries.
     */
    val entries: List<BaselineSurfaceEntry> = listOf(

        // ── Lifecycle truth surfaces (canonical stable) ───────────────────────

        BaselineSurfaceEntry(
            surfaceId = "runtime-controller-state",
            displayName = "RuntimeController.state",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Sole authoritative WS-lifecycle and cross-device enable/disable truth surface.",
            introducedPr = 1
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-controller-host-session-snapshot",
            displayName = "RuntimeController.hostSessionSnapshot",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical attached-session truth projection for host/session continuity semantics.",
            introducedPr = 22
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-controller-target-readiness-projection",
            displayName = "RuntimeController.targetReadinessProjection",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical delegated-selection and readiness truth surface.",
            introducedPr = 24
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-controller-reconnect-recovery-state",
            displayName = "RuntimeController.reconnectRecoveryState",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical reconnect recovery lifecycle truth surface.",
            introducedPr = 33
        ),

        // ── Dispatch governance surfaces (canonical stable) ───────────────────

        BaselineSurfaceEntry(
            surfaceId = "canonical-dispatch-chain",
            displayName = "CanonicalDispatchChain",
            packagePath = "com.ufo.galaxy.runtime.CanonicalDispatchChain",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Single canonical machine-readable dispatch chain model covering all execution paths.",
            introducedPr = 12
        ),
        BaselineSurfaceEntry(
            surfaceId = "rollout-control-snapshot",
            displayName = "RolloutControlSnapshot",
            packagePath = "com.ufo.galaxy.runtime.RolloutControlSnapshot",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical rollout-gate and kill-switch authority for all execution paths.",
            introducedPr = 31
        ),
        BaselineSurfaceEntry(
            surfaceId = "execution-route-tag",
            displayName = "ExecutionRouteTag",
            packagePath = "com.ufo.galaxy.runtime.ExecutionRouteTag",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical machine-readable result-path tag for all execution routes.",
            introducedPr = 29
        ),

        // ── Signal emission surfaces (canonical stable) ───────────────────────

        BaselineSurfaceEntry(
            surfaceId = "delegated-execution-signal",
            displayName = "DelegatedExecutionSignal",
            packagePath = "com.ufo.galaxy.runtime.DelegatedExecutionSignal",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical ACK/PROGRESS/RESULT signal contract with idempotency keys and emission ordering.",
            introducedPr = 15
        ),
        BaselineSurfaceEntry(
            surfaceId = "emitted-signal-ledger",
            displayName = "EmittedSignalLedger",
            packagePath = "com.ufo.galaxy.runtime.EmittedSignalLedger",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical replay-safe signal identity store; stable basis for signal deduplication.",
            introducedPr = 18
        ),

        // ── Session identity surfaces (canonical stable) ──────────────────────

        BaselineSurfaceEntry(
            surfaceId = "canonical-session-axis",
            displayName = "CanonicalSessionAxis",
            packagePath = "com.ufo.galaxy.runtime.CanonicalSessionAxis",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical session identity model defining session families for all execution paths.",
            introducedPr = 3
        ),
        BaselineSurfaceEntry(
            surfaceId = "attached-runtime-host-session-snapshot",
            displayName = "AttachedRuntimeHostSessionSnapshot",
            packagePath = "com.ufo.galaxy.runtime.AttachedRuntimeHostSessionSnapshot",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical typed attached-session projection with stable 9-field identity contract.",
            introducedPr = 19
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-identity-contracts",
            displayName = "RuntimeIdentityContracts",
            packagePath = "com.ufo.galaxy.runtime.RuntimeIdentityContracts",
            stability = SurfaceStability.CANONICAL_FROZEN,
            extensionGuidance = ExtensionGuidance.WRAP_ONLY,
            rationale = "Frozen identity-linkage contract for participant/device/capability IDs; extend via wrapping.",
            introducedPr = 19
        ),

        // ── Projection model surfaces (canonical stable) ──────────────────────

        BaselineSurfaceEntry(
            surfaceId = "canonical-participant-model",
            displayName = "CanonicalParticipantModel",
            packagePath = "com.ufo.galaxy.runtime.CanonicalParticipantModel",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical participant read-model contract; safe extension point for participant projection work.",
            introducedPr = 6
        ),
        BaselineSurfaceEntry(
            surfaceId = "canonical-device-model",
            displayName = "CanonicalDeviceModel",
            packagePath = "com.ufo.galaxy.runtime.CanonicalDeviceModel",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical device read-model contract; safe extension point for device projection work.",
            introducedPr = 6
        ),
        BaselineSurfaceEntry(
            surfaceId = "canonical-capability-provider-model",
            displayName = "CanonicalCapabilityProviderModel",
            packagePath = "com.ufo.galaxy.runtime.CanonicalCapabilityProviderModel",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical capability-provider read-model contract; safe extension point for capability work.",
            introducedPr = 6
        ),

        // ── Failure classification surfaces (canonical stable) ────────────────

        BaselineSurfaceEntry(
            surfaceId = "cross-device-setup-error",
            displayName = "CrossDeviceSetupError",
            packagePath = "com.ufo.galaxy.runtime.CrossDeviceSetupError",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical cross-device setup failure classification surface; covers all setup error categories.",
            introducedPr = 27
        ),
        BaselineSurfaceEntry(
            surfaceId = "takeover-fallback-event",
            displayName = "TakeoverFallbackEvent",
            packagePath = "com.ufo.galaxy.runtime.TakeoverFallbackEvent",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical cross-device fallback event contract covering all 4 fallback causes.",
            introducedPr = 23
        ),

        // ── Compatibility inventory surfaces (canonical stable) ───────────────

        BaselineSurfaceEntry(
            surfaceId = "compatibility-surface-retirement-registry",
            displayName = "CompatibilitySurfaceRetirementRegistry",
            packagePath = "com.ufo.galaxy.runtime.CompatibilitySurfaceRetirementRegistry",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical governance inventory for all high-risk compatibility surfaces and their retirement tiers.",
            introducedPr = 10
        ),
        BaselineSurfaceEntry(
            surfaceId = "long-tail-compatibility-registry",
            displayName = "LongTailCompatibilityRegistry",
            packagePath = "com.ufo.galaxy.runtime.LongTailCompatibilityRegistry",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical governance inventory for long-tail minimal-compat message-type classifications.",
            introducedPr = 35
        ),

        // ── Protocol alignment surfaces (canonical stable) ────────────────────

        BaselineSurfaceEntry(
            surfaceId = "ugcp-shared-schema-alignment",
            displayName = "UgcpSharedSchemaAlignment",
            packagePath = "com.ufo.galaxy.protocol.UgcpSharedSchemaAlignment",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical alignment registry for all shared semantic mapping between Android and center-repo.",
            introducedPr = 8
        ),
        BaselineSurfaceEntry(
            surfaceId = "ugcp-protocol-consistency-rules",
            displayName = "UgcpProtocolConsistencyRules",
            packagePath = "com.ufo.galaxy.protocol.UgcpProtocolConsistencyRules",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical consistency rule registry for all shared protocol field-name and value contracts.",
            introducedPr = 8
        ),

        // ── Transitional surfaces ─────────────────────────────────────────────

        BaselineSurfaceEntry(
            surfaceId = "legacy-registration-error-bridge",
            displayName = "RuntimeController.registrationError (legacy SharedFlow<String>)",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.RETIREMENT_GATED,
            extensionGuidance = ExtensionGuidance.CONVERGE,
            canonicalReplacement = "RuntimeController.setupError (SharedFlow<CrossDeviceSetupError>)",
            rationale = "Deprecated in PR-36 after all known consumers (MainViewModel, EnhancedFloatingService) " +
                "were migrated to setupError. Surface is @Deprecated but retained for backward compatibility " +
                "pending confirmation that no external call sites remain.",
            introducedPr = 10
        ),
        BaselineSurfaceEntry(
            surfaceId = "legacy-current-session-snapshot-map",
            displayName = "RuntimeController.currentSessionSnapshot() (Map<String,Any>?)",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.TRANSITIONAL,
            extensionGuidance = ExtensionGuidance.CONVERGE,
            canonicalReplacement = "RuntimeController.hostSessionSnapshot (AttachedRuntimeHostSessionSnapshot?)",
            rationale = "Legacy untyped map projection retained while consumers migrate to the canonical typed snapshot.",
            introducedPr = 10
        ),
        BaselineSurfaceEntry(
            surfaceId = "ugcp-legacy-wire-value-aliases",
            displayName = "UgcpSharedSchemaAlignment legacy alias normalization maps",
            packagePath = "com.ufo.galaxy.protocol.UgcpSharedSchemaAlignment",
            stability = SurfaceStability.TRANSITIONAL,
            extensionGuidance = ExtensionGuidance.CONVERGE,
            canonicalReplacement = "UgcpSharedSchemaAlignment canonical vocabulary sets",
            rationale = "Legacy wire-value alias surfaces retained for backward compatibility with older message producers.",
            introducedPr = 8
        ),

        // ── Retirement-gated surfaces ─────────────────────────────────────────

        BaselineSurfaceEntry(
            surfaceId = "long-tail-relay-forward-reply",
            displayName = "LongTailCompatibilityRegistry: RELAY / FORWARD / REPLY (TRANSITIONAL)",
            packagePath = "com.ufo.galaxy.runtime.LongTailCompatibilityRegistry",
            stability = SurfaceStability.RETIREMENT_GATED,
            extensionGuidance = ExtensionGuidance.NO_NEW_WORK,
            canonicalReplacement = "Future promoted long-tail handlers (promote individually via LongTailCompatibilityRegistry)",
            retirementGate = "center-android coordination on relay/forward/reply lifecycle semantics",
            rationale = "Minimal-compat placeholders explicitly not intended for extension; retire after coordination.",
            introducedPr = 35
        ),
        BaselineSurfaceEntry(
            surfaceId = "protocol-surface-transitional-compatibility",
            displayName = "ProtocolSurface entries classified as TRANSITIONAL_COMPATIBILITY",
            packagePath = "com.ufo.galaxy.protocol.UgcpProtocolConsistencyRules",
            stability = SurfaceStability.RETIREMENT_GATED,
            extensionGuidance = ExtensionGuidance.NO_NEW_WORK,
            canonicalReplacement = "Canonically classified ProtocolSurface entries",
            retirementGate = "cross-repo protocol consistency rule tightening pass",
            rationale = "Protocol surfaces still classified as TRANSITIONAL_COMPATIBILITY pending cross-repo tightening.",
            introducedPr = 8
        ),

        // ── PR-37: Android runtime lifecycle hardening surfaces ───────────────

        BaselineSurfaceEntry(
            surfaceId = "runtime-lifecycle-transition-event",
            displayName = "RuntimeLifecycleTransitionEvent",
            packagePath = "com.ufo.galaxy.runtime.RuntimeLifecycleTransitionEvent",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical observable lifecycle state transition event — " +
                "explicit governed/unexpected classification with stable trigger vocabulary.",
            introducedPr = 37
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-dispatch-readiness-coordinator",
            displayName = "RuntimeDispatchReadinessCoordinator",
            packagePath = "com.ufo.galaxy.runtime.RuntimeDispatchReadinessCoordinator",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical authority for combining runtime state + session state + " +
                "rollout flags into a single dispatch eligibility answer.",
            introducedPr = 37
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-controller-lifecycle-transition-events",
            displayName = "RuntimeController.lifecycleTransitionEvents",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Observable SharedFlow of runtime lifecycle transitions, " +
                "enabling diagnostics and test assertions without polling the raw state flow.",
            introducedPr = 37
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-controller-current-dispatch-readiness",
            displayName = "RuntimeController.currentDispatchReadiness()",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Point-in-time dispatch eligibility query combining runtime/session/rollout state.",
            introducedPr = 37
        ),
        BaselineSurfaceEntry(
            surfaceId = "canonical-dispatch-chain-eligible-paths-for-state",
            displayName = "CanonicalDispatchChain.resolveEligiblePathsForState()",
            packagePath = "com.ufo.galaxy.runtime.CanonicalDispatchChain",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "State-aware dispatch path resolver — returns eligible paths given runtime/session/rollout truth.",
            introducedPr = 37
        ),
        BaselineSurfaceEntry(
            surfaceId = "canonical-session-axis-dispatch-alignment",
            displayName = "CanonicalSessionAxis.resolveDispatchAlignmentForState()",
            packagePath = "com.ufo.galaxy.runtime.CanonicalSessionAxis",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Session family liveness resolver — maps runtime/session state to live session families.",
            introducedPr = 37
        ),
        BaselineSurfaceEntry(
            surfaceId = "galaxy-logger-tag-runtime-lifecycle",
            displayName = "GalaxyLogger.TAG_RUNTIME_LIFECYCLE",
            packagePath = "com.ufo.galaxy.observability.GalaxyLogger",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Stable structured log tag for all runtime lifecycle state transition events.",
            introducedPr = 37
        ),

        // ── PR-38: Android protocol convergence surfaces ──────────────────────

        BaselineSurfaceEntry(
            surfaceId = "protocol-convergence-boundary",
            displayName = "ProtocolConvergenceBoundary",
            packagePath = "com.ufo.galaxy.runtime.ProtocolConvergenceBoundary",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical protocol/runtime interaction surface convergence authority — " +
                "classifies every protocol surface as CANONICAL, CONVERGING, ISOLATED, or RETIRED " +
                "and records the required convergence action per surface.",
            introducedPr = 38
        ),
        BaselineSurfaceEntry(
            surfaceId = "long-tail-compatibility-registry-convergence-actions",
            displayName = "LongTailCompatibilityRegistry.ConvergenceAction + LongTailEntry.convergenceAction",
            packagePath = "com.ufo.galaxy.runtime.LongTailCompatibilityRegistry",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-38 addition: convergence action per long-tail entry makes protocol " +
                "convergence intent machine-readable; isolatedTypes and convergingTypes queries " +
                "expose the boundary surface sets.",
            introducedPr = 38
        ),
        BaselineSurfaceEntry(
            surfaceId = "galaxy-logger-tag-protocol-convergence",
            displayName = "GalaxyLogger.TAG_PROTOCOL_CONVERGENCE",
            packagePath = "com.ufo.galaxy.observability.GalaxyLogger",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Stable structured log tag for all protocol convergence boundary enforcement events.",
            introducedPr = 38
        ),

        // ── PR-39: Android truth / snapshot / projection convergence surfaces ─

        BaselineSurfaceEntry(
            surfaceId = "runtime-truth-precedence-rules",
            displayName = "RuntimeTruthPrecedenceRules",
            packagePath = "com.ufo.galaxy.runtime.RuntimeTruthPrecedenceRules",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical three-tier truth model (AUTHORITATIVE → SNAPSHOT → PROJECTION) " +
                "declaring the precedence chain, derivation paths, and single-gate update invariants " +
                "for all Android runtime/session truth surfaces.",
            introducedPr = 39
        ),
        BaselineSurfaceEntry(
            surfaceId = "host-facing-projection-contract",
            displayName = "HostFacingProjectionContract",
            packagePath = "com.ufo.galaxy.runtime.HostFacingProjectionContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Explicit host-facing projection contract governing all outward runtime/session " +
                "surfaces — CANONICAL / TRANSITIONAL / DEPRECATED classification with co-derivation " +
                "invariants for snapshot and readiness surfaces.",
            introducedPr = 39
        ),
        BaselineSurfaceEntry(
            surfaceId = "canonical-session-axis-truth-bindings",
            displayName = "CanonicalSessionAxis.truthBindings / truthBindingFor()",
            packagePath = "com.ufo.galaxy.runtime.CanonicalSessionAxis",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-39 addition: SessionTruthBinding entries make the derivation path " +
                "from authoritative truth to snapshot carrier explicit per session family; " +
                "familiesWithSnapshotCarrier / familiesWithoutSnapshotCarrier expose the " +
                "projection coverage boundary.",
            introducedPr = 39
        ),

        // ── PR-40: Android media transport and task-lifecycle convergence surfaces ─

        BaselineSurfaceEntry(
            surfaceId = "media-transport-lifecycle-bridge",
            displayName = "MediaTransportLifecycleBridge",
            packagePath = "com.ufo.galaxy.runtime.MediaTransportLifecycleBridge",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical integration model between Android media/session transport " +
                "conditions (STABLE / DEGRADED / INTERRUPTED / SUSPENDED) and task/runtime " +
                "lifecycle adaptations — makes transport→lifecycle mappings explicit and " +
                "machine-readable; reduces ambiguous transport-driven side paths.",
            introducedPr = 40
        ),
        BaselineSurfaceEntry(
            surfaceId = "transport-continuity-anchor",
            displayName = "TransportContinuityAnchor",
            packagePath = "com.ufo.galaxy.runtime.TransportContinuityAnchor",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical transport continuity anchor governing session continuity policy " +
                "across ATTACH, DETACH, RECONNECT, DEGRADATION, and RECOVERY events — declares " +
                "ownership boundaries and recovery sequences so transport-driven behavior cannot " +
                "diverge from canonical runtime lifecycle semantics.",
            introducedPr = 40
        ),
        BaselineSurfaceEntry(
            surfaceId = "canonical-dispatch-chain-transport-adapted-paths",
            displayName = "CanonicalDispatchChain.resolveTransportAdaptedPaths()",
            packagePath = "com.ufo.galaxy.runtime.CanonicalDispatchChain",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-40 addition: transport-condition–aware dispatch path resolver — " +
                "extends resolveEligiblePathsForState() with a TransportCondition filter that " +
                "suppresses cross-device paths during INTERRUPTED and SUSPENDED conditions.",
            introducedPr = 40
        ),
        BaselineSurfaceEntry(
            surfaceId = "canonical-session-axis-transport-continuity-bindings",
            displayName = "CanonicalSessionAxis.transportContinuityBindings / transportContinuityBindingFor()",
            packagePath = "com.ufo.galaxy.runtime.CanonicalSessionAxis",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-40 addition: TransportContinuityBinding entries make the interaction " +
                "between TransportContinuityAnchor events and session families machine-readable; " +
                "familiesAffectedByInterruption and familiesSurvivingReconnect expose the " +
                "transport-continuity coverage boundary.",
            introducedPr = 40
        ),

        // ── PR-41: Android contract finalization surfaces ─────────────────────

        BaselineSurfaceEntry(
            surfaceId = "android-contract-finalizer",
            displayName = "AndroidContractFinalizer",
            packagePath = "com.ufo.galaxy.runtime.AndroidContractFinalizer",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Canonical Android-side contract responsibility boundary registry — " +
                "declares clarity, drift risk, and ownership per major runtime participation area " +
                "(readiness, session, transport continuity, host-facing state, dispatch eligibility). " +
                "Makes finalized Android/V2 contract boundaries machine-readable.",
            introducedPr = 41
        ),
        BaselineSurfaceEntry(
            surfaceId = "compatibility-retirement-fence",
            displayName = "CompatibilityRetirementFence",
            packagePath = "com.ufo.galaxy.runtime.CompatibilityRetirementFence",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-41 compatibility retirement fence registry — records explicit " +
                "fence/retire/demotion decisions for every compatibility surface in " +
                "CompatibilitySurfaceRetirementRegistry; provides a machine-readable audit " +
                "trail of the PR-41 finalization pass.",
            introducedPr = 41
        ),
        BaselineSurfaceEntry(
            surfaceId = "canonical-session-axis-contract-finalization-bindings",
            displayName = "CanonicalSessionAxis.contractFinalizationBindings / contractFinalizationBindingFor()",
            packagePath = "com.ufo.galaxy.runtime.CanonicalSessionAxis",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-41 addition: ContractFinalizationBinding entries make the PR-41 " +
                "finalization status and V2 drift risk per session family machine-readable; " +
                "finalizedContractFamilies and transitionalContractFamilies expose the " +
                "contract-clarity boundary per family.",
            introducedPr = 41
        ),
        BaselineSurfaceEntry(
            surfaceId = "canonical-dispatch-chain-contract-finalized-paths",
            displayName = "CanonicalDispatchChain.resolveContractFinalizedPaths()",
            packagePath = "com.ufo.galaxy.runtime.CanonicalDispatchChain",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-41 addition: contract-finalization–aware dispatch path resolver — " +
                "extends resolveTransportAdaptedPaths() with a compatibility-path exclusion " +
                "filter so callers building on finalized contract boundaries can obtain a " +
                "compatibility-surface-free dispatch path set.",
            introducedPr = 41
        ),

        // ── PR-42: Android runtime invariant enforcement surfaces ─────────────

        BaselineSurfaceEntry(
            surfaceId = "runtime-invariant-enforcer",
            displayName = "RuntimeInvariantEnforcer",
            packagePath = "com.ufo.galaxy.runtime.RuntimeInvariantEnforcer",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-42 canonical Android runtime invariant enforcer — defines nine " +
                "high-value invariants across SESSION, TRANSPORT, READINESS, DISPATCH, and " +
                "SNAPSHOT scopes; provides pure-function checkAll() and violatedInvariantIds() " +
                "helpers that make runtime drift conditions locally detectable without " +
                "framework state.",
            introducedPr = 42
        ),
        BaselineSurfaceEntry(
            surfaceId = "canonical-dispatch-chain-invariant-protected-paths",
            displayName = "CanonicalDispatchChain.resolveInvariantProtectedPaths()",
            packagePath = "com.ufo.galaxy.runtime.CanonicalDispatchChain",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-42 addition: invariant-enforcement–aware dispatch path resolver — " +
                "extends resolveContractFinalizedPaths() with a RuntimeInvariantEnforcer check " +
                "layer; CRITICAL SESSION/DISPATCH violations suppress all paths; CRITICAL " +
                "TRANSPORT violations additionally suppress cross-device paths; returns " +
                "InvariantProtectedPathResult for caller inspection of violations.",
            introducedPr = 42
        ),
        BaselineSurfaceEntry(
            surfaceId = "canonical-session-axis-invariant-bindings",
            displayName = "CanonicalSessionAxis.invariantBindings / invariantBindingFor()",
            packagePath = "com.ufo.galaxy.runtime.CanonicalSessionAxis",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-42 addition: SessionInvariantBinding entries make the guarding " +
                "RuntimeInvariantEnforcer invariants per session family machine-readable; " +
                "familiesWithActiveInvariant exposes which families have active local invariant " +
                "protection.",
            introducedPr = 42
        ),
        BaselineSurfaceEntry(
            surfaceId = "invariant-protected-path-result",
            displayName = "InvariantProtectedPathResult",
            packagePath = "com.ufo.galaxy.runtime.InvariantProtectedPathResult",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-42 result type for resolveInvariantProtectedPaths() — carries " +
                "resolved dispatch paths, all invariant violations, and a blockedByInvariant " +
                "flag; provides allInvariantsSatisfied and violatedIds for caller-side " +
                "violation inspection.",
            introducedPr = 42
        )
    ) + listOf(

        // ── PR-43: V2 multi-device lifecycle integration surfaces ──────────────

        BaselineSurfaceEntry(
            surfaceId = "v2-multi-device-lifecycle-event",
            displayName = "V2MultiDeviceLifecycleEvent",
            packagePath = "com.ufo.galaxy.runtime.V2MultiDeviceLifecycleEvent",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-43 canonical V2-consumable device lifecycle event sealed class — " +
                "provides stable, typed events (DeviceConnected, DeviceReconnected, " +
                "DeviceDisconnected, DeviceDegraded, DeviceHealthChanged, " +
                "ParticipantReadinessChanged) for V2 multi-device runtime hook integration " +
                "without exposing Android-internal state machines to the V2 layer. " +
                "Heartbeat-miss semantics are explicitly declared as unsupported.",
            introducedPr = 43
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-controller-v2-lifecycle-events",
            displayName = "RuntimeController.v2LifecycleEvents",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-43 observable SharedFlow of V2MultiDeviceLifecycleEvent — the " +
                "single consumption point for V2 harness hook integration. Emits events for " +
                "all connect/disconnect/reconnect/health/readiness lifecycle transitions in " +
                "the production Android runtime path; TEST_ONLY sources are excluded.",
            introducedPr = 43
        ),
        BaselineSurfaceEntry(
            surfaceId = "galaxy-logger-tag-v2-lifecycle",
            displayName = "GalaxyLogger.TAG_V2_LIFECYCLE",
            packagePath = "com.ufo.galaxy.observability.GalaxyLogger",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-43 stable structured log tag for all V2 multi-device lifecycle " +
                "events emitted via RuntimeController.v2LifecycleEvents — provides an " +
                "operator-facing observability stream for V2 integration diagnostics.",
            introducedPr = 43
        )
    ) + listOf(

        // ── PR-44: Mesh session lifecycle hint extension ──────────────────────

        BaselineSurfaceEntry(
            surfaceId = "v2-mesh-session-lifecycle-hint",
            displayName = "V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint",
            packagePath = "com.ufo.galaxy.runtime.V2MultiDeviceLifecycleEvent",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-44 explicit mesh session lifecycle hint enum carried by " +
                "DeviceConnected, DeviceReconnected, and DeviceDisconnected events — " +
                "maps Android lifecycle events to V2 mesh session lifecycle transitions " +
                "(CREATE_ACTIVATE, RESTORE_ACTIVATE, SUSPEND, TERMINATE) without requiring " +
                "V2 to re-parse raw Android fields like openSource or detachCause.",
            introducedPr = 44
        ),
        BaselineSurfaceEntry(
            surfaceId = "v2-device-disconnected-is-resumable",
            displayName = "V2MultiDeviceLifecycleEvent.DeviceDisconnected.isResumable",
            packagePath = "com.ufo.galaxy.runtime.V2MultiDeviceLifecycleEvent",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-44 computed boolean on DeviceDisconnected — true when " +
                "detachCause is 'disconnect' (transient WS drop; reconnect and session " +
                "restore expected); false for permanent termination causes. Provides V2 " +
                "an explicit resumability signal without requiring detachCause string parsing.",
            introducedPr = 44
        )
    ) + listOf(

        // ── PR-45: Explicit executor target typing surfaces ───────────────────

        BaselineSurfaceEntry(
            surfaceId = "executor-target-type",
            displayName = "ExecutorTargetType",
            packagePath = "com.ufo.galaxy.runtime.ExecutorTargetType",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-45 canonical Android-side executor target type vocabulary — " +
                "provides stable wire-value constants (ANDROID_DEVICE, NODE_SERVICE, WORKER, " +
                "LOCAL) for explicit executor target typing introduced by V2; supplies " +
                "isAndroidEligible() helper for safe dispatch gating without string comparison.",
            introducedPr = 45
        ),

        // ── PR-46: Durable continuity and recovery context surfaces ───────────

        BaselineSurfaceEntry(
            surfaceId = "continuity-recovery-context",
            displayName = "ContinuityRecoveryContext",
            packagePath = "com.ufo.galaxy.runtime.ContinuityRecoveryContext",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-46 Android-side continuity and recovery context vocabulary — " +
                "declares interruption_reason wire values (REASON_RECONNECT, REASON_HANDOFF, " +
                "REASON_DEVICE_PAUSE, REASON_TRANSPORT_DEGRADED) and provides helpers " +
                "(isResumableExecution, isTerminalExecution, isKnownInterruptionReason, " +
                "isTransportInterruption) for safe recovery classification without string parsing.",
            introducedPr = 46
        ),

        // ── PR-47: Runtime observability compatibility surfaces ───────────────

        BaselineSurfaceEntry(
            surfaceId = "runtime-observability-metadata",
            displayName = "RuntimeObservabilityMetadata",
            packagePath = "com.ufo.galaxy.runtime.RuntimeObservabilityMetadata",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-47 Android-side observability compatibility vocabulary — declares " +
                "cross-system tracing field names (FIELD_DISPATCH_TRACE_ID, " +
                "FIELD_LIFECYCLE_EVENT_ID, FIELD_SESSION_CORRELATION_ID), event kind constants " +
                "for structured log entries, LifecycleObservabilityKind enum mapping Android " +
                "lifecycle events to V2 observability classifications, and helpers " +
                "(hasDispatchTraceId, hasLifecycleEventId, hasSessionCorrelationId, " +
                "resolveTraceId) for safe null-tolerant tracing ID resolution.",
            introducedPr = 47
        ),
        BaselineSurfaceEntry(
            surfaceId = "goal-execution-payload-observability-fields",
            displayName = "GoalExecutionPayload.dispatch_trace_id / lifecycle_event_id",
            packagePath = "com.ufo.galaxy.protocol.GoalExecutionPayload",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-47 optional observability/tracing metadata fields on " +
                "GoalExecutionPayload — dispatch_trace_id enables cross-system dispatch chain " +
                "correlation; lifecycle_event_id links the dispatch to the triggering V2 " +
                "lifecycle event. Both fields default to null for legacy-sender backward " +
                "compatibility. dispatch_trace_id is echoed back in GoalResultPayload for " +
                "full-chain V2 observability correlation.",
            introducedPr = 47
        ),
        BaselineSurfaceEntry(
            surfaceId = "task-assign-payload-observability-fields",
            displayName = "TaskAssignPayload.dispatch_trace_id / lifecycle_event_id",
            packagePath = "com.ufo.galaxy.protocol.TaskAssignPayload",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-47 optional observability/tracing metadata fields on " +
                "TaskAssignPayload — mirrors the GoalExecutionPayload additions for the " +
                "task_assign path; allows V2 to attach dispatch trace and lifecycle event " +
                "correlation identifiers to task_assign commands without requiring Android " +
                "callers to change any existing logic.",
            introducedPr = 47
        ),
        BaselineSurfaceEntry(
            surfaceId = "aip-message-observability-fields",
            displayName = "AipMessage.dispatch_trace_id / session_correlation_id",
            packagePath = "com.ufo.galaxy.protocol.AipMessage",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-47 optional observability/tracing fields on the AipMessage envelope — " +
                "dispatch_trace_id for cross-system dispatch chain correlation at the envelope " +
                "level; session_correlation_id for session-level cross-system tracing across " +
                "session transitions and handoffs. Both fields default to null for full " +
                "backward compatibility with pre-PR-47 senders.",
            introducedPr = 47
        ),
        BaselineSurfaceEntry(
            surfaceId = "galaxy-logger-tag-dispatch-decision",
            displayName = "GalaxyLogger.TAG_DISPATCH_DECISION",
            packagePath = "com.ufo.galaxy.observability.GalaxyLogger",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-47 stable structured log tag for Android dispatch decision events — " +
                "records the execution route and target with full observability context " +
                "(dispatch_trace_id, executor_target_type) so dispatch decisions can be " +
                "correlated across the V2 observability pipeline.",
            introducedPr = 47
        ),
        BaselineSurfaceEntry(
            surfaceId = "galaxy-logger-tag-lifecycle-observe",
            displayName = "GalaxyLogger.TAG_LIFECYCLE_OBSERVE",
            packagePath = "com.ufo.galaxy.observability.GalaxyLogger",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-47 stable structured log tag for device lifecycle observability events " +
                "forwarded to the V2 observability model — provides a dedicated observability " +
                "stream for lifecycle-triggered events (attach, reconnect, detach, degraded) " +
                "with session correlation and lifecycle event identifiers.",
            introducedPr = 47
        ),
        BaselineSurfaceEntry(
            surfaceId = "galaxy-logger-tag-recovery-observe",
            displayName = "GalaxyLogger.TAG_RECOVERY_OBSERVE",
            packagePath = "com.ufo.galaxy.observability.GalaxyLogger",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-47 stable structured log tag for recovery execution observability events — " +
                "records recovery-related executions with dispatch_trace_id, continuity_token, " +
                "and is_resumable so recovery events can be traced end-to-end across the V2 " +
                "observability pipeline.",
            introducedPr = 47
        ),

        // ── PR-48: Execution contract compatibility validation surfaces ──────────

        BaselineSurfaceEntry(
            surfaceId = "execution-contract-compatibility-validator",
            displayName = "ExecutionContractCompatibilityValidator",
            packagePath = "com.ufo.galaxy.runtime.ExecutionContractCompatibilityValidator",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-48 unified Android-side compatibility validator for the evolved V2 " +
                "runtime execution contract — declares DispatchStrategyHint wire values " +
                "(LOCAL, REMOTE_HANDOFF, FALLBACK_LOCAL, STAGED_MESH), CompatibilityArea " +
                "enum covering all four evolved contract areas, CompatibilityCheckResult " +
                "data class for structured compatibility introspection, and helper methods " +
                "(checkPayloadCompatibility, isAndroidEligibleStrategy, hasDispatchPlanId) " +
                "that encode backward-compatibility expectations explicitly and testably.",
            introducedPr = 48
        ),
        BaselineSurfaceEntry(
            surfaceId = "goal-execution-payload-dispatch-metadata-fields",
            displayName = "GoalExecutionPayload.dispatch_plan_id / source_dispatch_strategy",
            packagePath = "com.ufo.galaxy.protocol.GoalExecutionPayload",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-48 richer dispatch metadata fields on GoalExecutionPayload — " +
                "dispatch_plan_id enables correlation between inbound Android executions and " +
                "the V2 source dispatch plan that generated them; source_dispatch_strategy " +
                "carries the V2 orchestrator routing strategy hint (local, remote_handoff, " +
                "fallback_local, staged_mesh). Both fields default to null for full backward " +
                "compatibility with pre-PR-48 senders. dispatch_plan_id is echoed back in " +
                "GoalResultPayload for end-to-end dispatch plan correlation.",
            introducedPr = 48
        ),
        BaselineSurfaceEntry(
            surfaceId = "goal-result-payload-dispatch-plan-id-echo",
            displayName = "GoalResultPayload.dispatch_plan_id",
            packagePath = "com.ufo.galaxy.protocol.GoalResultPayload",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-48 dispatch plan identifier echo field on GoalResultPayload — " +
                "echoed from GoalExecutionPayload.dispatch_plan_id so V2 can correlate " +
                "Android execution results with the originating dispatch plan. Defaults to " +
                "null for full backward compatibility with pre-PR-48 result consumers.",
            introducedPr = 48
        ),

        // ── PR-03: Takeover metadata unification & three-path dispatch symmetry ──

        BaselineSurfaceEntry(
            surfaceId = "takeover-request-envelope-dispatch-metadata-fields",
            displayName = "TakeoverRequestEnvelope.dispatch_plan_id / source_dispatch_strategy / executor_target_type",
            packagePath = "com.ufo.galaxy.agent.TakeoverRequestEnvelope",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-03 takeover path dispatch metadata alignment — adds dispatch_plan_id " +
                "and source_dispatch_strategy (PR-48 parity) plus executor_target_type (PR-E parity) " +
                "to TakeoverRequestEnvelope so all three Android dispatch entry paths (goal_execution, " +
                "handoff, takeover) carry symmetric richer dispatch metadata. All fields default to " +
                "null for full backward compatibility with pre-PR-03 / legacy senders.",
            introducedPr = 3
        ),
        BaselineSurfaceEntry(
            surfaceId = "handoff-envelope-v2-dispatch-metadata-fields",
            displayName = "HandoffEnvelopeV2.dispatch_plan_id / source_dispatch_strategy",
            packagePath = "com.ufo.galaxy.agent.HandoffEnvelopeV2",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-03 handoff path dispatch metadata alignment — adds dispatch_plan_id " +
                "and source_dispatch_strategy (PR-48 parity) to HandoffEnvelopeV2 so the handoff " +
                "path carries the same richer dispatch metadata as goal_execution and takeover. " +
                "Both fields default to null for full backward compatibility with legacy callers.",
            introducedPr = 3
        ),
        BaselineSurfaceEntry(
            surfaceId = "execution-contract-validator-three-path-overloads",
            displayName = "ExecutionContractCompatibilityValidator.checkPayloadCompatibility(HandoffEnvelopeV2/TakeoverRequestEnvelope)",
            packagePath = "com.ufo.galaxy.runtime.ExecutionContractCompatibilityValidator",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-03 unified validator overloads — extends checkPayloadCompatibility " +
                "with HandoffEnvelopeV2 and TakeoverRequestEnvelope overloads so all three dispatch " +
                "entry paths produce uniform CompatibilityCheckResult interpretation. Observability/" +
                "tracing and policy routing flags are always false for handoff/takeover paths as those " +
                "fields are not present on those paths. Never throws; legacy payloads produce all-false results.",
            introducedPr = 3
        ),
        BaselineSurfaceEntry(
            surfaceId = "executor-target-type-log-label",
            displayName = "ExecutorTargetType.logLabel",
            packagePath = "com.ufo.galaxy.runtime.ExecutorTargetType",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-03 unified executor target type log expression — adds logLabel(rawValue) " +
                "helper to ExecutorTargetType that produces a stable structured log string " +
                "(executor_target_type=<raw> canonical=<canonical> eligible=<bool>) for consistent " +
                "observability across all three dispatch paths. Replaces ad-hoc inline log " +
                "construction in AutonomousExecutionPipeline. Never throws.",
            introducedPr = 3
        ),

        // ── PR-49 (PR-I): Policy-driven routing outcome compatibility surfaces ──

        BaselineSurfaceEntry(
            surfaceId = "policy-routing-context",
            displayName = "PolicyRoutingContext",
            packagePath = "com.ufo.galaxy.runtime.PolicyRoutingContext",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-49 (PR-I) Android-side policy-driven routing outcome vocabulary — " +
                "declares RoutingOutcome wire values (ACCEPTED, DEGRADED_READINESS, " +
                "TEMPORARILY_UNAVAILABLE, RESUMED, REJECTED), degradation and unavailability " +
                "reason constants, and semantic helpers (shouldProceed, isTemporaryHold, " +
                "isPolicyRejection, isResumedExecution, isDegradedReadiness) so Android remains " +
                "a predictable participant in policy-driven multi-device execution without " +
                "becoming a policy decision-maker. Backward compatible: null/absent outcomes " +
                "are treated as legacy (proceed).",
            introducedPr = 49
        ),
        BaselineSurfaceEntry(
            surfaceId = "goal-execution-payload-policy-routing-fields",
            displayName = "GoalExecutionPayload.policy_routing_outcome / policy_failure_reason / readiness_degradation_hint",
            packagePath = "com.ufo.galaxy.protocol.GoalExecutionPayload",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-49 (PR-I) policy routing outcome fields on GoalExecutionPayload — " +
                "policy_routing_outcome carries V2 policy layer's routing decision for this device " +
                "(accepted, degraded_readiness, temporarily_unavailable, resumed, rejected); " +
                "policy_failure_reason carries machine-readable reason for non-accepted outcomes; " +
                "readiness_degradation_hint carries degradation detail for degraded_readiness outcomes. " +
                "All fields default to null for full backward compatibility with pre-PR-I senders.",
            introducedPr = 49
        ),
        BaselineSurfaceEntry(
            surfaceId = "goal-result-payload-policy-routing-outcome-echo",
            displayName = "GoalResultPayload.policy_routing_outcome",
            packagePath = "com.ufo.galaxy.protocol.GoalResultPayload",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-49 (PR-I) policy routing outcome echo field on GoalResultPayload — " +
                "echoed from GoalExecutionPayload.policy_routing_outcome so V2 can correlate " +
                "Android execution results with the policy layer's routing decision. Defaults to " +
                "null for full backward compatibility with pre-PR-I result consumers.",
            introducedPr = 49
        )
    ) + listOf(

        // ── PR-50: Reconnect / lifecycle output semantics strengthening ───────

        BaselineSurfaceEntry(
            surfaceId = "v2-device-degraded-durable-session-id",
            displayName = "V2MultiDeviceLifecycleEvent.DeviceDegraded.durableSessionId",
            packagePath = "com.ufo.galaxy.runtime.V2MultiDeviceLifecycleEvent",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-50 identity-stability field on DeviceDegraded — carries the " +
                "durable session era identifier (same value present in DeviceConnected / " +
                "DeviceReconnected) so V2 can correlate ws_recovering and " +
                "ws_recovery_failed events with the specific session era being recovered " +
                "without re-parsing prior events. Nullable for backward compatibility.",
            introducedPr = 50
        ),
        BaselineSurfaceEntry(
            surfaceId = "v2-device-degraded-session-continuity-epoch",
            displayName = "V2MultiDeviceLifecycleEvent.DeviceDegraded.sessionContinuityEpoch",
            packagePath = "com.ufo.galaxy.runtime.V2MultiDeviceLifecycleEvent",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-50 identity-stability field on DeviceDegraded — carries the " +
                "reconnect count within the durable era at degradation time so V2 can " +
                "determine the exact reconnect cycle a degradation event belongs to. " +
                "Nullable for backward compatibility when no durable record is active.",
            introducedPr = 50
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-controller-recovery-v2-unconditional",
            displayName = "RuntimeController.emitFormationRebalanceForRecovery V2 unconditional emission",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-50 behavioral guarantee — DeviceDegraded V2 lifecycle events " +
                "for RECOVERING and FAILED recovery states are emitted unconditionally " +
                "(i.e. even when hostDescriptor is null), using session identity from " +
                "the active attachedSession and durableSessionContinuityRecord. This " +
                "ensures V2 always receives the complete recovery-cycle signal regardless " +
                "of host descriptor availability.",
            introducedPr = 50
        )
    ) + listOf(

        // ── PR-5B: Policy routing outcome behavioral semantics ───────────────

        BaselineSurfaceEntry(
            surfaceId = "policy-outcome-hold-result",
            displayName = "PolicyRoutingContext.RESULT_STATUS_HOLD / GoalResultPayload.hold_reason",
            packagePath = "com.ufo.galaxy.runtime.PolicyRoutingContext",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-5B non-terminal hold semantics for TEMPORARILY_UNAVAILABLE — " +
                "PolicyRoutingContext.RESULT_STATUS_HOLD (\"hold\") is the wire status returned " +
                "when policy_routing_outcome=temporarily_unavailable; GoalResultPayload.hold_reason " +
                "carries RESULT_HOLD_REASON_TEMPORARILY_UNAVAILABLE (\"policy_temporarily_unavailable\") " +
                "so V2 can distinguish a non-terminal device hold from administrative disabled or " +
                "terminal error, and retry dispatch when readiness is restored. " +
                "Both fields default to null for backward compatibility with pre-PR-5B consumers.",
            introducedPr = 5
        ),
        BaselineSurfaceEntry(
            surfaceId = "policy-outcome-rejection-detail",
            displayName = "GoalResultPayload.policy_rejection_detail",
            packagePath = "com.ufo.galaxy.protocol.GoalResultPayload",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-5B structured rejection detail for REJECTED outcomes — " +
                "GoalResultPayload.policy_rejection_detail carries the echoed " +
                "GoalExecutionPayload.policy_failure_reason when policy_routing_outcome=rejected, " +
                "so V2 can distinguish the specific rejection reason rather than relying solely " +
                "on the generic error string. Defaults to null for non-rejected outcomes and " +
                "legacy paths; backward compatible with pre-PR-5B result consumers.",
            introducedPr = 5
        ),
        BaselineSurfaceEntry(
            surfaceId = "policy-outcome-continuation-marker",
            displayName = "GoalResultPayload.is_continuation",
            packagePath = "com.ufo.galaxy.protocol.GoalResultPayload",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-5B continuation-aware execution marker for RESUMED outcomes — " +
                "GoalResultPayload.is_continuation is set to true when Android executed a task " +
                "with policy_routing_outcome=resumed, signalling that the execution was a " +
                "continuation of a prior interrupted task (not a fresh dispatch). V2 can use " +
                "this marker to distinguish resumed executions from fresh ones without parsing " +
                "the policy_routing_outcome field on the result side. Defaults to null for " +
                "non-resumed outcomes and legacy paths.",
            introducedPr = 5
        ),
        BaselineSurfaceEntry(
            surfaceId = "autonomous-execution-pipeline-hold-status",
            displayName = "AutonomousExecutionPipeline.STATUS_HOLD / REASON_TEMPORARILY_UNAVAILABLE",
            packagePath = "com.ufo.galaxy.agent.AutonomousExecutionPipeline",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-5B pipeline-level hold status constants — STATUS_HOLD and " +
                "REASON_TEMPORARILY_UNAVAILABLE mirror PolicyRoutingContext.RESULT_STATUS_HOLD " +
                "and RESULT_HOLD_REASON_TEMPORARILY_UNAVAILABLE for consistent consumption " +
                "at the pipeline layer. AutonomousExecutionPipeline returns STATUS_HOLD (not " +
                "STATUS_DISABLED, not error) for temporarily_unavailable payloads, ensuring " +
                "V2 can stably distinguish hold from administrative disabled.",
            introducedPr = 5
        )
    ) + listOf(

        // ── PR-51: Android participant runtime truth and reconciliation signal ─

        BaselineSurfaceEntry(
            surfaceId = "android-participant-runtime-truth",
            displayName = "AndroidParticipantRuntimeTruth",
            packagePath = "com.ufo.galaxy.runtime.AndroidParticipantRuntimeTruth",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-51 consolidated participant-level runtime truth snapshot for V2 " +
                "reconciliation — combines participant identity, participationState, " +
                "coordinationRole, sourceRuntimePosture, session truth (sessionId, " +
                "sessionState, delegatedExecutionCount), healthState, readinessState, " +
                "active task status, and a reconciliationEpoch into a single, point-in-time " +
                "map-serializable snapshot. Provides isFullyReconcilable and " +
                "isAttachedAndEligible helpers. V2 should treat this as Android's " +
                "authoritative self-reported local truth for canonical reconciliation passes.",
            introducedPr = 51
        ),
        BaselineSurfaceEntry(
            surfaceId = "active-task-status",
            displayName = "ActiveTaskStatus",
            packagePath = "com.ufo.galaxy.runtime.ActiveTaskStatus",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-51 in-flight task execution status enum carried by " +
                "AndroidParticipantRuntimeTruth — RUNNING, PENDING, CANCELLING, FAILING — " +
                "exposes the Android-side view of a currently executing task so V2 can " +
                "track task progress before a terminal AndroidSessionContribution arrives. " +
                "Complements AndroidSessionContribution (terminal results) with a " +
                "pre-terminal in-progress status signal.",
            introducedPr = 51
        ),
        BaselineSurfaceEntry(
            surfaceId = "reconciliation-signal",
            displayName = "ReconciliationSignal",
            packagePath = "com.ufo.galaxy.runtime.ReconciliationSignal",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-51 structured, protocol-safe wrapper for all Android→V2 " +
                "reconciliation signals — TASK_ACCEPTED, TASK_STATUS_UPDATE, TASK_RESULT, " +
                "TASK_CANCELLED, TASK_FAILED, PARTICIPANT_STATE, RUNTIME_TRUTH_SNAPSHOT. " +
                "Provides factory helpers (taskAccepted, taskCancelled, taskFailed, " +
                "taskResult, runtimeTruthSnapshot) and isTerminal/hasRuntimeTruth helpers. " +
                "Covers all lifecycle phases of Android→V2 signal flow, including " +
                "pre-terminal cancel/failure notification and full snapshot publication.",
            introducedPr = 51
        )
    ) + listOf(

        // ── PR-52: ReconciliationSignal factory helpers and RuntimeController emission ──

        BaselineSurfaceEntry(
            surfaceId = "reconciliation-signal-factories",
            displayName = "ReconciliationSignal factory helpers (taskStatusUpdate, participantStateSignal)",
            packagePath = "com.ufo.galaxy.runtime.ReconciliationSignal",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-52 completes ReconciliationSignal factory coverage with " +
                "taskStatusUpdate (TASK_STATUS_UPDATE kind, STATUS_IN_PROGRESS, optional " +
                "progressDetail) and participantStateSignal (PARTICIPANT_STATE kind, " +
                "STATUS_STATE_CHANGED, carries healthState + readinessState + optional " +
                "posture in payload).  Fills the factory gap left by PR-51 for the two " +
                "non-terminal, non-snapshot signal kinds.",
            introducedPr = 52
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-controller-reconciliation-signals",
            displayName = "RuntimeController.reconciliationSignals",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-52 adds reconciliationSignals SharedFlow to RuntimeController — " +
                "the canonical Android→V2 structured reconciliation signal stream.  Emission " +
                "points: recordDelegatedTaskAccepted (TASK_ACCEPTED), notifyTakeoverFailed " +
                "(TASK_FAILED or TASK_CANCELLED), publishTaskResult (TASK_RESULT), " +
                "publishTaskCancelled (TASK_CANCELLED), notifyParticipantHealthChanged " +
                "(PARTICIPANT_STATE), publishRuntimeTruthSnapshot (RUNTIME_TRUTH_SNAPSHOT). " +
                "V2 collects this flow to reconcile its canonical participant truth without " +
                "polling or inspecting internal RuntimeController state.",
            introducedPr = 52
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-controller-publish-truth-snapshot",
            displayName = "RuntimeController.publishRuntimeTruthSnapshot",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-52 adds publishRuntimeTruthSnapshot to RuntimeController — the " +
                "canonical entry point for a full RUNTIME_TRUTH_SNAPSHOT reconciliation " +
                "signal.  Assembles AndroidParticipantRuntimeTruth from the current " +
                "hostDescriptor and hostSessionSnapshot, advances the reconciliationEpoch, " +
                "and emits the snapshot on reconciliationSignals for V2 to perform a full " +
                "reconciliation pass.  V2 should treat the snapshot as Android's authoritative " +
                "self-reported local truth and resolve conflicts in its favour.",
            introducedPr = 52
        )
    ) + listOf(

        // ── PR-53: Android lifecycle, recovery, and hybrid-participant hardening ─

        BaselineSurfaceEntry(
            surfaceId = "app-lifecycle-participant-boundary",
            displayName = "AppLifecycleParticipantBoundary",
            packagePath = "com.ufo.galaxy.runtime.AppLifecycleParticipantBoundary",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-53 canonical mapping from Android app lifecycle events " +
                "(FOREGROUND, BACKGROUND, BACKGROUND_KILL, CONFIGURATION_CHANGE, " +
                "MEMORY_PRESSURE, PROCESS_RECREATION) to participant-side state effects " +
                "(ParticipantStateEffect) and session continuity behaviors " +
                "(SessionContinuityBehavior). Makes lifecycle→participant state transitions " +
                "reviewable and intentional; declares what Android can recover locally vs " +
                "what requires V2 resync. Eliminates implicit/scattered lifecycle behavior.",
            introducedPr = 53
        ),
        BaselineSurfaceEntry(
            surfaceId = "hybrid-participant-capability-boundary",
            displayName = "HybridParticipantCapabilityBoundary",
            packagePath = "com.ufo.galaxy.runtime.HybridParticipantCapabilityBoundary",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-53 explicit hybrid/distributed execution capability maturity registry " +
                "for Android participant — classifies STAGED_MESH_EXECUTION, BARRIER_PARTICIPATION, " +
                "and FORMATION_REBALANCE as FULLY_WIRED; classifies HYBRID_EXECUTE, RAG_QUERY, and " +
                "CODE_EXECUTE as CONTRACT_FIRST with mandatory non-silent response contracts. " +
                "Eliminates silent degrade-only behavior for CONTRACT_FIRST capabilities; provides " +
                "RESULT_CAPABILITY_MATURITY_TAG wire constant for V2-readable limitation signaling.",
            introducedPr = 53
        ),
        BaselineSurfaceEntry(
            surfaceId = "participant-recovery-readiness-snapshot",
            displayName = "ParticipantRecoveryReadinessSnapshot",
            packagePath = "com.ufo.galaxy.runtime.ParticipantRecoveryReadinessSnapshot",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-53 static durability/V2-resync registry for all runtime-critical " +
                "local state fields — covers crossDeviceEnabled (SETTINGS_PERSISTED, survives kill), " +
                "durableSessionId and health/readiness (DURABLE_IN_MEMORY, survives reconnect), " +
                "AttachedRuntimeSession and in-flight task state (EPHEMERAL), and global session " +
                "assignment (V2_CANONICAL). Provides locallyRecoverableAfterWsReconnect, " +
                "requiresV2ResyncAfterProcessKillList, and requiresV2ResyncAfterWsReconnectList " +
                "query helpers so recovery correctness is reviewable in tests.",
            introducedPr = 53
        )
    ) + listOf(

        // ── PR-60: Android app lifecycle transitions, hybrid capability, and recovery contract ─

        BaselineSurfaceEntry(
            surfaceId = "android-app-lifecycle-transition",
            displayName = "AndroidAppLifecycleTransition",
            packagePath = "com.ufo.galaxy.runtime.AndroidAppLifecycleTransition",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-60 explicit Android app-level lifecycle transition model for the " +
                "participant runtime — FOREGROUND, BACKGROUND, PROCESS_RECREATED, " +
                "RUNTIME_STOPPED, CONFIGURATION_CHANGE — with wireValue and runtimeImplication " +
                "for each. Makes lifecycle authority explicit: every app-level event that can " +
                "affect the runtime is named and reviewable. RuntimeController.onAppLifecycleTransition " +
                "dispatches each transition to the correct runtime action (connectIfEnabled or stop). " +
                "Eliminates implicit scattered lifecycle handling.",
            introducedPr = 60
        ),
        BaselineSurfaceEntry(
            surfaceId = "hybrid-participant-capability",
            displayName = "HybridParticipantCapability",
            packagePath = "com.ufo.galaxy.runtime.HybridParticipantCapability",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-60 structured hybrid/distributed execution capability model for the " +
                "Android participant — HYBRID_EXECUTE_FULL (NOT_YET_IMPLEMENTED), " +
                "STAGED_MESH_SUBTASK (AVAILABLE), PARALLEL_SUBTASK (AVAILABLE), " +
                "WEBRTC_PEER_TRANSPORT (MINIMAL_COMPAT), BARRIER_COORDINATION (NOT_YET_IMPLEMENTED). " +
                "Replaces silent degrade-only behavior with named, structured capability status. " +
                "V2 can distinguish capability-unavailable from not-ready from intentional-deferral. " +
                "Provides availableCapabilities() and deferredCapabilities() query helpers.",
            introducedPr = 60
        ),
        BaselineSurfaceEntry(
            surfaceId = "android-lifecycle-recovery-contract",
            displayName = "AndroidLifecycleRecoveryContract",
            packagePath = "com.ufo.galaxy.runtime.AndroidLifecycleRecoveryContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-60 structured recovery boundary contract declaring what survives and " +
                "what is lost across Android process recreation, transient WS disconnect, and " +
                "hybrid participant scenarios. PROCESS_RECREATION_BOUNDARY maps " +
                "SURVIVES_PROCESS_RECREATION (crossDeviceEnabled) and " +
                "LOST_ON_PROCESS_RECREATION (DurableSessionContinuityRecord, in-flight task state). " +
                "TRANSIENT_DISCONNECT_RECOVERY declares android_recovers_locally and v2_must_handle " +
                "boundaries. HYBRID_LIMITATIONS documents NOT_YET_IMPLEMENTED hybrid capabilities " +
                "so reviewers can verify completeness.",
            introducedPr = 60
        )
    ) + listOf(

        // ── PR-61: Participant runtime semantics boundary — structured reviewer-facing contract ─

        BaselineSurfaceEntry(
            surfaceId = "participant-runtime-semantics-boundary",
            displayName = "ParticipantRuntimeSemanticsBoundary",
            packagePath = "com.ufo.galaxy.runtime.ParticipantRuntimeSemanticsBoundary",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-61 single structured reviewer-facing boundary for three acceptance " +
                "criteria: (1) ANDROID_TRUTH_DOMAIN — 10 Android-owned truth domains with " +
                "canonical surface, snapshot surface, and V2 consumer action for each; " +
                "(2) EXECUTION_OUTCOMES — 7-entry cancel/status/failure/result protocol with " +
                "phase, isTerminal, emit-condition, and V2-action for each outcome; and " +
                "PROTOCOL_SAFETY_RULES (5 invariants); " +
                "(3) FIRST_CLASS_PARTICIPANT_DECLARATION + 9 distinguishing behaviours + " +
                "5 NOT_ORCHESTRATION_AUTHORITY_BOUNDARIES. Provides a single point of reference " +
                "for reviewers to verify Android's participant runtime truth, execution semantics, " +
                "and first-class participant posture without assembling from scattered surfaces.",
            introducedPr = 61
        ),
        BaselineSurfaceEntry(
            surfaceId = "participant-execution-phase",
            displayName = "ParticipantRuntimeSemanticsBoundary.ExecutionPhase",
            packagePath = "com.ufo.galaxy.runtime.ParticipantRuntimeSemanticsBoundary",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-61 execution phase taxonomy for the Android participant task lifecycle — " +
                "PRE_EXECUTION, IN_PROGRESS, TERMINAL, ANY — with stable wireValue for each. " +
                "Used to classify each ExecutionOutcome in the EXECUTION_OUTCOMES protocol registry " +
                "so reviewers can immediately see which outcomes are pre-execution, in-progress, " +
                "terminal, or lifecycle-independent. Mirrors ReconciliationSignal phase semantics.",
            introducedPr = 61
        ),
        BaselineSurfaceEntry(
            surfaceId = "participant-truth-domain-entry",
            displayName = "ParticipantRuntimeSemanticsBoundary.TruthDomainEntry",
            packagePath = "com.ufo.galaxy.runtime.ParticipantRuntimeSemanticsBoundary",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-61 structured truth domain entry used in ANDROID_TRUTH_DOMAIN — " +
                "captures domainKey, fields, canonicalSurface, snapshotSurface, and v2ConsumerAction " +
                "for each Android-owned truth domain. Enables programmatic verification that the " +
                "complete truth ownership inventory is present and non-empty in tests, and serves " +
                "as documentation of V2's expected response to each domain update.",
            introducedPr = 61
        )
    ) + listOf(

        // ── PR-62: Participant live execution surface — runtime truth wired into live execution ─

        BaselineSurfaceEntry(
            surfaceId = "participant-live-execution-surface",
            displayName = "ParticipantLiveExecutionSurface",
            packagePath = "com.ufo.galaxy.runtime.ParticipantLiveExecutionSurface",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-62 canonical reviewer reference for tracing Android participant truth " +
                "through live execution, lifecycle, and protocol surfaces. Answers all four PR-62 " +
                "acceptance criteria: LIVE_EXECUTION_WIRING (6 entries: where truth lives in execution " +
                "code paths), TASK_LIFECYCLE_WIRING (5 entries: how cancel/status/failure/result affect " +
                "continuity), INTERRUPTION_AND_RECOVERY (6 scenarios: what emits/clears/resets on " +
                "interruption), FIRST_CLASS_PARTICIPANT_EVIDENCE (7 behaviors: why Android is now a " +
                "live runtime, not a shell). Companion to RuntimeController active task truth fields " +
                "(_activeTaskId, _activeTaskStatus), publishTaskStatusUpdate(), auto-TASK_FAILED on " +
                "disconnect, and auto-RUNTIME_TRUTH_SNAPSHOT on reconnect.",
            introducedPr = 62
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-controller-active-task-truth",
            displayName = "RuntimeController.activeTaskId / activeTaskStatus",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-62 live active task state tracking in RuntimeController: " +
                "_activeTaskId (volatile String?) and _activeTaskStatus (volatile ActiveTaskStatus?) " +
                "track the in-flight task across accept, status-update, result, cancel, and failure " +
                "lifecycle phases. Set by recordDelegatedTaskAccepted(); cleared by publishTaskResult(), " +
                "publishTaskCancelled(), notifyTakeoverFailed(), and clearActiveTaskState() (which is " +
                "called automatically by closeAttachedSession on DISCONNECT/INVALIDATION). Enables " +
                "automatic TASK_FAILED emission when a session is interrupted, and automatic " +
                "RUNTIME_TRUTH_SNAPSHOT with IDLE state on reconnect.",
            introducedPr = 62
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-controller-publish-task-status-update",
            displayName = "RuntimeController.publishTaskStatusUpdate()",
            packagePath = "com.ufo.galaxy.runtime.RuntimeController",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-62 live execution method for emitting TASK_STATUS_UPDATE reconciliation " +
                "signals. Before PR-62, ReconciliationSignal.Kind.TASK_STATUS_UPDATE existed but " +
                "RuntimeController had no publishTaskStatusUpdate() method — the TASK_STATUS_UPDATE " +
                "wire path was defined but not wired into the live execution surface. " +
                "publishTaskStatusUpdate(taskId, correlationId?, progressDetail?) closes this gap: " +
                "it emits the signal with STATUS_IN_PROGRESS and logs to TAG_LIVE_EXECUTION. " +
                "Callers (e.g. DelegatedTakeoverExecutor) can report intermediate step progress " +
                "to V2 without exposing raw reconciliationSignals write access.",
            introducedPr = 62
        ),
        BaselineSurfaceEntry(
            surfaceId = "galaxy-logger-tag-live-execution",
            displayName = "GalaxyLogger.TAG_LIVE_EXECUTION",
            packagePath = "com.ufo.galaxy.observability.GalaxyLogger",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-62 structured log tag for all live participant execution events: " +
                "task_accepted, task_status_update, task_interrupted_by_session_close, " +
                "active_task_state_cleared, reconnect_idle_truth_snapshot_emitted. " +
                "Provides a single filter path (GALAXY:LIVE:EXECUTION) for tracing Android " +
                "participant truth through the complete task lifecycle in production logs.",
            introducedPr = 62
        ),

        // ── PR-63: Participant progress feedback surfaces — richer execution progress outbound ─

        BaselineSurfaceEntry(
            surfaceId = "participant-progress-feedback-surface",
            displayName = "ParticipantProgressFeedbackSurface",
            packagePath = "com.ufo.galaxy.runtime.ParticipantProgressFeedbackSurface",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-63 canonical reviewer reference for Android participant richer " +
                "execution progress and feedback surfaces. Answers all four PR-63 acceptance " +
                "criteria: PROGRESS_FEEDBACK_SURFACES (5 entries: which richer states are emitted), " +
                "OUTBOUND_EXECUTION_MODELING (5 entries: how execution is represented beyond " +
                "ack/result/error), OBSERVABILITY_SURFACES (4 entries: how Android activity is " +
                "reviewable from outside), AUTHORITY_BOUNDARY_DECLARATIONS (5 entries: feedback " +
                "bounded under participant-local authority). Companion to ParticipantProgressCheckpoint, " +
                "SubtaskProgressReport, and ReconciliationSignal structured progress payload keys.",
            introducedPr = 63
        ),
        BaselineSurfaceEntry(
            surfaceId = "participant-progress-checkpoint",
            displayName = "ParticipantProgressCheckpoint",
            packagePath = "com.ufo.galaxy.runtime.ParticipantProgressCheckpoint",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-63 named, structured execution-stage marker for Android's local AI " +
                "pipeline. Carries checkpointId (stable stage identifier from KNOWN_CHECKPOINT_IDS), " +
                "stepIndex, optional totalSteps, optional detail, and toPayloadMap() for wire " +
                "encoding into ReconciliationSignal.Kind.TASK_STATUS_UPDATE payload. Eight canonical " +
                "stage identifiers: planning_started, planning_complete, grounding_started, " +
                "grounding_complete, step_executing, step_complete, replanning, finalizing. " +
                "Replaces free-form progressDetail string with a typed, structured execution stage model.",
            introducedPr = 63
        ),
        BaselineSurfaceEntry(
            surfaceId = "subtask-progress-report",
            displayName = "SubtaskProgressReport",
            packagePath = "com.ufo.galaxy.runtime.SubtaskProgressReport",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-63 typed subtask-level progress report for Android's plan execution. " +
                "Carries subtaskIndex (zero-based), optional subtaskTotal, subtaskLabel (step " +
                "description), subtaskStatus enum (PENDING / EXECUTING / COMPLETE / FAILED / SKIPPED), " +
                "and toPayloadMap() for wire encoding. Provides V2 with ordered, typed step-N-of-M " +
                "execution progress rather than an opaque step count integer. " +
                "SubtaskStatus.ALL_WIRE_VALUES and fromValue() support safe V2-side parsing.",
            introducedPr = 63
        ),
        BaselineSurfaceEntry(
            surfaceId = "reconciliation-signal-progress-keys",
            displayName = "ReconciliationSignal progress payload key constants",
            packagePath = "com.ufo.galaxy.runtime.ReconciliationSignal",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-63 stable payload key constants added to ReconciliationSignal companion: " +
                "KEY_CHECKPOINT_ID, KEY_STEP_INDEX, KEY_TOTAL_STEPS (checkpoint progress), " +
                "KEY_SUBTASK_INDEX, KEY_SUBTASK_TOTAL, KEY_SUBTASK_STATUS, KEY_SUBTASK_LABEL " +
                "(subtask progress). These constants give V2 and tooling stable, named keys " +
                "for structured progress fields in TASK_STATUS_UPDATE payloads, replacing " +
                "the prior single 'progress_detail' free-form string key.",
            introducedPr = 63
        ),

        // ── PR-64: Unified truth/reconciliation convergence — RuntimeTruthPatch + reducer ─

        BaselineSurfaceEntry(
            surfaceId = "unified-truth-reconciliation-surface",
            displayName = "UnifiedTruthReconciliationSurface",
            packagePath = "com.ufo.galaxy.runtime.UnifiedTruthReconciliationSurface",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-64 canonical reviewer reference for Android unified truth/reconciliation " +
                "convergence model. Answers all four PR-64 acceptance criteria: " +
                "AUTHORITATIVE_TRUTH_SOURCES (8 entries: delegated result/cancelled/failed, " +
                "handoff accepted/rejected, takeover accepted/rejected, session terminal), " +
                "NOTIFICATION_SIGNALS (4 entries: participant state changed, task status update, " +
                "runtime truth snapshot request, subtask checkpoint), " +
                "CONVERGENCE_MODEL (6 entries: single reducer entry, typed discriminator, " +
                "authoritative flag gate, epoch ordering guard, terminal idempotency, participant safety), " +
                "MULTI_EVENT_ORDERING (6 entries: ordering scenarios demonstrating stable truth convergence). " +
                "Companion to RuntimeTruthPatch and TruthReconciliationReducer.",
            introducedPr = 64
        ),
        BaselineSurfaceEntry(
            surfaceId = "runtime-truth-patch",
            displayName = "RuntimeTruthPatch",
            packagePath = "com.ufo.galaxy.runtime.RuntimeTruthPatch",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-64 typed, atomic, event-sourced truth update data class. " +
                "Carries patchId, participantId, taskId, Kind (9-value enum: delegated_task_result, " +
                "delegated_task_cancelled, delegated_task_failed, handoff_accepted, handoff_rejected, " +
                "takeover_accepted, takeover_rejected, participant_state_changed, session_terminal), " +
                "isAuthoritative (authoritative vs. notification flag), taskTerminalOutcome " +
                "(COMPLETED / CANCELLED / FAILED), handoffOutcome (ACCEPTED / REJECTED), " +
                "takeoverOutcome (ACCEPTED / REJECTED), errorDetail, patchedAtMs, reconciliationEpoch. " +
                "toMap() produces stable wire key→value Map under KEY_* constants. " +
                "Replaces per-module independent truth mutations with a single typed event envelope.",
            introducedPr = 64
        ),
        BaselineSurfaceEntry(
            surfaceId = "truth-reconciliation-reducer",
            displayName = "TruthReconciliationReducer",
            packagePath = "com.ufo.galaxy.runtime.TruthReconciliationReducer",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-64 pure, stateless reducer function object implementing Android's unified " +
                "local truth convergence. Primary API: reduce(current, patch) → ReduceResult. " +
                "Batch API: reduceFold(initial, patches) → AndroidParticipantRuntimeTruth. " +
                "Enforces four invariants: (1) epoch gating — stale patches discarded, " +
                "(2) authoritative-only mutation — notification signals advance epoch only, " +
                "(3) terminal idempotency — duplicate terminal patches are safe no-ops, " +
                "(4) participant safety — cross-participant patches rejected. " +
                "ReduceResult.applied / discardReason enable caller-side observability without " +
                "snapshot equality comparison. Closes the pre-PR-64 gap where multiple modules " +
                "independently advanced the same task terminal state.",
            introducedPr = 64
        )
    ) + listOf(

        // ── PR-65: Android authoritative-path alignment audit ─────────────────

        BaselineSurfaceEntry(
            surfaceId = "android-authoritative-path-alignment-audit",
            displayName = "AndroidAuthoritativePathAlignmentAudit",
            packagePath = "com.ufo.galaxy.runtime.AndroidAuthoritativePathAlignmentAudit",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-65 single machine-readable reference classifying all Android-side " +
                "runtime behaviors into five authoritative-path tiers: CANONICAL_DEFAULT (7 entries, " +
                "V2 treats as canonical participant evidence), COMPAT_ALLOWED (3 entries, bounded " +
                "legacy compatibility still permitted), OBSERVATION_ONLY (3 entries, diagnostic " +
                "surfaces only), DEPRECATED_BUT_LIVE (3 entries, explicitly gated, default-off), " +
                "BLOCKED_RETIRED (2 entries, suppressed by AndroidCompatLegacyBlockingParticipant). " +
                "SignalSemantics enum (CANONICAL_PARTICIPANT_EVIDENCE, OBSERVATION_SIGNAL, " +
                "LEGACY_INFLUENCED, BLOCKED) maps each tier to a V2-consumable artifact classification. " +
                "Provides isCanonicalDefault(), isDeprecatedOrBlocked(), deferredEntries(), " +
                "bySignalSemantics(), and per-tier query helpers so reviewers can verify: " +
                "(1) which behaviors are canonical vs. compat/legacy, " +
                "(2) deprecated behaviors are default-off, " +
                "(3) V2 can classify every Android-originated signal, " +
                "(4) deferred cleanup is explicitly inventoried with retirement notes.",
            introducedPr = 65
        )
    ) + listOf(

        // ── PR-14 (formal capability declaration): capability honesty and recovery semantics ─

        BaselineSurfaceEntry(
            surfaceId = "formal-participant-lifecycle-state",
            displayName = "FormalParticipantLifecycleState",
            packagePath = "com.ufo.galaxy.runtime.FormalParticipantLifecycleState",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-14 unified five-state participant lifecycle model (STARTING, READY, " +
                "DEGRADED, RECOVERING, UNAVAILABLE_FAILED) that bridges the internal state machinery " +
                "to outward-facing capability advertisement and protocol consistency contracts. " +
                "Provides fromManagerState(), fromHealthState(), capabilityAdvertisementAllowed(), " +
                "inferenceCapabilityAllowed(), and requiresCapabilityReAdvertise() so every layer " +
                "uses the same canonical capability gate rather than ad-hoc state checks.",
            introducedPr = 14
        ),
        BaselineSurfaceEntry(
            surfaceId = "capability-readvertise-contract",
            displayName = "CapabilityReadvertiseContract",
            packagePath = "com.ufo.galaxy.runtime.CapabilityReadvertiseContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-14 formal post-recovery capability re-advertisement contract. " +
                "Defines ReadvertiseAction (READVERTISE_FULL, READVERTISE_BASE_ONLY, " +
                "SUPPRESS_UNTIL_STABLE, BLOCK_READVERTISE), ReadvertiseDecision (binding action + " +
                "allowedCapabilities + rationale), decide() (state → decision), " +
                "requiresReRegistration() (state transition → re-registration gate), and " +
                "heartbeatMustMatchReadvertise() (heartbeat consistency enforcement). " +
                "Closes the gap between recovery completion and control-plane capability alignment.",
            introducedPr = 14
        ),
        BaselineSurfaceEntry(
            surfaceId = "recovery-capability-alignment-guard",
            displayName = "RecoveryCapabilityAlignmentGuard",
            packagePath = "com.ufo.galaxy.runtime.RecoveryCapabilityAlignmentGuard",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-14 post-recovery capability honesty guard. Validates that advertised " +
                "capabilities are aligned with FormalParticipantLifecycleState and " +
                "LocalIntelligenceCapabilityStatus truth. Provides checkAlignment() (returns " +
                "AlignmentResult with violations list), computeAllowedCapabilities() (authoritative " +
                "capability set for any state combination), isAdvertisementBlocked(), " +
                "inferenceCapabilityPermitted(), and requiresPostRecoveryReRegistration() " +
                "(returns ReRegistrationRequirement when control-plane re-registration is needed). " +
                "Ensures the control plane never sees stale capability claims after recovery.",
            introducedPr = 14
        ),

        // ── PR-69: participant lifecycle truth report surfaces ────────────────

        BaselineSurfaceEntry(
            surfaceId = "participant-lifecycle-truth-state",
            displayName = "ParticipantLifecycleTruthState",
            packagePath = "com.ufo.galaxy.runtime.ParticipantLifecycleTruthState",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-69 formal nine-state participant lifecycle truth enum. Extends " +
                "FormalParticipantLifecycleState (capability-gate-optimised) with cross-repo " +
                "truth states: UNREGISTERED, REGISTERING, ACTIVE, DEGRADED, RECOVERING, " +
                "RECOVERED, UNAVAILABLE, RE_REGISTERING, CAPABILITY_RE_ALIGNED. Provides " +
                "fromFormal() for conservative mapping, capabilityAdvertisementAllowed(), " +
                "isRecoveryPhase(), and ALL_WIRE_VALUES. Canonical truth surface for " +
                "lifecycle state in participant lifecycle truth reports.",
            introducedPr = 69
        ),
        BaselineSurfaceEntry(
            surfaceId = "participant-lifecycle-truth-report",
            displayName = "ParticipantLifecycleTruthReport",
            packagePath = "com.ufo.galaxy.runtime.ParticipantLifecycleTruthReport",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-69 structured Android participant lifecycle truth report for " +
                "cross-repo consumption. Single canonical lifecycle truth surface aggregating " +
                "ParticipantLifecycleTruthState, RegistrationTruthStatus, reconnectObserved, " +
                "ReRegistrationOutcome, CapabilityAlignmentStatus, recoveredButDegraded, " +
                "partiallyAligned, and LifecycleEvidenceCompleteness. Provides toWireMap() " +
                "(schema v1.0) for V2 ingestion, isCrossRepoConsumable, isInRecoveryPhase, " +
                "isFullyRecovered, and isRecoveredButDegraded. Prohibits single-boolean health " +
                "and treats reconnect-alone as insufficient recovery evidence.",
            introducedPr = 69
        ),
        BaselineSurfaceEntry(
            surfaceId = "participant-lifecycle-truth-report-builder",
            displayName = "ParticipantLifecycleTruthReportBuilder",
            packagePath = "com.ufo.galaxy.runtime.ParticipantLifecycleTruthReportBuilder",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-69 builder for deriving ParticipantLifecycleTruthReport from " +
                "multi-dimensional runtime context. Integrates FormalParticipantLifecycleState, " +
                "ReconnectRecoveryState, RegistrationTruthStatus, ReRegistrationOutcome, and " +
                "CapabilityAlignmentStatus into the nine-state lifecycle truth model. Provides " +
                "build() (from runtime context) and fromAuditSnapshot() (from PR-68 " +
                "AndroidDelegatedRuntimeAuditSnapshot) bridges. Validates evidence completeness " +
                "including STALE, INCOMPLETE, INCONSISTENT, COMPLETE_WITH_GAPS, and COMPLETE " +
                "classifications.",
            introducedPr = 69
        )
    ) + listOf(

        // ── PR-79: Android semantic-contract closure surfaces (R7/R11) ────────

        BaselineSurfaceEntry(
            surfaceId = "local-execution-mode-gate",
            displayName = "LocalExecutionModeGate",
            packagePath = "com.ufo.galaxy.runtime.LocalExecutionModeGate",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-79 explicit, machine-verifiable gate for Android execution mode " +
                "state/transition semantics. Closes R7/R11 drift gap: names the complete set " +
                "of ExecutionModeState values (INACTIVE, LOCAL_ONLY, CROSS_DEVICE_ACTIVE, " +
                "CROSS_DEVICE_DEGRADED, TRANSITIONING) with task-acceptance and V2-governance " +
                "flags; names all eight ModeTransitionEvent values with causesHold semantics; " +
                "provides decide() factory for snapshot-emission; provides TRANSITION_TABLE for " +
                "V2 android_runtime_transition_reducer.py to validate observed transitions. " +
                "V2 canonical consumers must read execution_mode_state from device-state snapshots " +
                "rather than inferring from field combinations.",
            introducedPr = 79
        ),
        BaselineSurfaceEntry(
            surfaceId = "durable-participant-identity",
            displayName = "DurableParticipantIdentity",
            packagePath = "com.ufo.galaxy.session.DurableParticipantIdentity",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-79 stable per-installation participant identity anchor with explicit " +
                "freshness semantics. Closes R7/R11 identity-freshness drift: participantId is " +
                "constant across all activation eras and process recreations; IdentityFreshness " +
                "(FRESH/RECOVERED/STALE) is Android-originated and authoritative — V2 " +
                "android_device_state_store.py must read it rather than applying its own " +
                "heuristics. STALE_THRESHOLD_MS (24 h) matches V2 participant inactivity " +
                "timeout. createFromGap() factory applies threshold to classify freshness at " +
                "registration time. toWireMap() provides all REQUIRED_WIRE_KEYS for V2 " +
                "participant-record correlation.",
            introducedPr = 79
        )
    ) + listOf(

        // ── PR-8Android: R8 canonical path closure surfaces ───────────────────

        BaselineSurfaceEntry(
            surfaceId = "android-r8-canonical-path-closure",
            displayName = "AndroidR8CanonicalPathClosureContract",
            packagePath = "com.ufo.galaxy.runtime.AndroidR8CanonicalPathClosureContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-8Android R8 canonical-path closure contract. Wires LocalExecutionModeGate " +
                "and DurableParticipantIdentity (both introduced in PR-79) into the canonical " +
                "DeviceStateSnapshotPayload and DeviceExecutionEventPayload emission paths. " +
                "Closes the R8 gap: V2 android_runtime_transition_reducer.py can now read " +
                "execution_mode_state directly; V2 android_device_state_store.py can now read " +
                "durable_participant_id and participant_identity_freshness without applying its " +
                "own inference heuristics. CLOSURE_INVARIANTS provides machine-verifiable " +
                "evidence that the fields are present on the canonical emission paths.",
            introducedPr = 80
        )
    ) + listOf(

        // ── PR-08Android: Canonical runtime truth unification ─────────────────

        // introducedPr uses the internal numeric PR series ID (81 = PR-08Android in string form;
        // the numeric convention in StabilizationBaseline increments per registered PR).
        BaselineSurfaceEntry(
            surfaceId = "android-canonical-runtime-truth-contract",
            displayName = "AndroidCanonicalRuntimeTruthContract",
            packagePath = "com.ufo.galaxy.runtime.AndroidCanonicalRuntimeTruthContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-08Android canonical runtime truth unification contract. Introduces " +
                "ReportedStateSemanticClass (5-class: capability, observation, active_runtime, " +
                "derived_local, terminal_reporting), DegradedConditionClass (7-class: nominal, " +
                "degraded, fallback, constrained, partial, delayed, recovered), " +
                "ResultUplinkSemanticClass (4-class: authoritative_terminal, authoritative_interruption, " +
                "authoritative_recovery, informational), and LocalObservationBasis (4-value: live_runtime, " +
                "cached_state, derived_projection, none). Classifier methods classifySnapshot(), " +
                "classifyDegradedCondition(), and classifyResultUplink() derive the dominant class " +
                "from existing payload fields. New wire fields reported_state_semantic_class, " +
                "degraded_condition_class, local_observation_basis added to DeviceStateSnapshotPayload; " +
                "reported_state_semantic_class and result_uplink_semantic_class added to " +
                "DeviceExecutionEventPayload. Closes the semantic-class gap: V2's canonical truth " +
                "reducer can now route any Android snapshot to the correct truth tier without implicit " +
                "per-field knowledge of derivation paths.",
            introducedPr = 81
        )
    ) + listOf(

        // ── PR-3: Canonical capability export contract ────────────────────────

        BaselineSurfaceEntry(
            surfaceId = "android-capability-export-contract",
            displayName = "AndroidCapabilityExportContract",
            packagePath = "com.ufo.galaxy.runtime.AndroidCapabilityExportContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-3 canonical Android capability export contract for cross-repository V2 validation. " +
                "Replaces the scattered capability schema definition (split across CapabilityReport, " +
                "AndroidCapabilityVector, and GalaxyWebSocketClient) with a single, versioned, " +
                "machine-verifiable contract object. Introduces CONTRACT_SCHEMA_VERSION (emitted as " +
                "'contract_schema_version' in capability_report metadata) so V2's capability_registry.py " +
                "can detect Android-side schema drift without diffing individual field lists. Provides " +
                "TOP_LEVEL_FIELD_DESCRIPTORS (11 required fields), METADATA_FIELD_DESCRIPTORS (29 required " +
                "fields: 8 core readiness + 4 scheduling basis + 16 canonical gate + 1 contract version), " +
                "validate() and validateMetadata() producing ValidationResult, and regression-anchor " +
                "constants EXPECTED_REQUIRED_TOP_LEVEL_FIELD_COUNT / EXPECTED_REQUIRED_METADATA_FIELD_COUNT. " +
                "GalaxyWebSocketClient.normalizedCapabilityMetadata() emits contract_schema_version = '1' " +
                "in every capability_report metadata map. Pr3AndroidCapabilityExportContractTest provides " +
                "the blocking CI gate protecting schema stability.",
            introducedPr = 82
        ),

        // ── PR-7B: Android truth publication semantics ────────────────────────

        BaselineSurfaceEntry(
            surfaceId = "android-truth-publication-semantics",
            displayName = "AndroidTruthPublicationSemanticsContract",
            packagePath = "com.ufo.galaxy.runtime.AndroidTruthPublicationSemanticsContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-7B canonical Android truth publication semantics contract. Aligns Android-side " +
                "truth emission with V2's hardened governance expectations so that missing or unverifiable " +
                "Android state is never represented as implicitly healthy. Introduces EvidencePresenceKind " +
                "(6 values: POSITIVE_EVIDENCE/UNKNOWN/UNAVAILABLE/DELAYED/PARTIAL/FAILED_OBSERVATION) with " +
                "classifySnapshotEvidencePresence() and classifyEventEvidencePresence() classifiers. Adds " +
                "evidence_presence_kind: String? wire field to DeviceStateSnapshotPayload and " +
                "DeviceExecutionEventPayload so V2 applies correct governance policy (standard dispatch, " +
                "withhold dispatch, block dependent dispatch, staleness penalty, subsystem-limited dispatch, " +
                "or recovery policy) without relying on optimistic defaults for absent evidence. " +
                "GalaxyConnectionService populates evidence_presence_kind at all snapshot and event " +
                "emission call sites. Test: Pr7BAndroidTruthPublicationSemanticsTest.",
            introducedPr = 83
        ),

        // ── PR-12: Android real reconnect/recovery participation contract ──────

        BaselineSurfaceEntry(
            surfaceId = "android-reconnect-recovery-participation",
            displayName = "AndroidReconnectRecoveryParticipationContract",
            packagePath = "com.ufo.galaxy.runtime.AndroidReconnectRecoveryParticipationContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-12 canonical Android reconnect/recovery participation contract. Makes Android a " +
                "true runtime recovery participant rather than a simulated source of reconnect-like messages. " +
                "Introduces ReconnectParticipationKind (4 values: FRESH_ATTACH/TRANSPORT_RECONNECT/" +
                "PROCESS_RECREATION_WITH_CONTEXT/PROCESS_RECREATION_WITHOUT_CONTEXT), IdentityReuseDecision " +
                "(3 values: REUSE_DURABLE_PARTICIPANT/REUSE_SESSION_ONLY/FRESH_IDENTITY), and " +
                "ReplayEligibility (3 values: ELIGIBLE_FOR_REPLAY/STALE_SESSION_BLOCKED/QUEUE_EMPTY). " +
                "Adds reconnect_participation_kind, identity_reuse_decision, and replay_eligibility wire " +
                "fields to DeviceStateSnapshotPayload so V2 can apply the correct recovery closure path " +
                "without inferring it from field combinations. GalaxyConnectionService populates all three " +
                "fields at sendDeviceStateSnapshot() call time. Test: Pr12AndroidReconnectRecoveryParticipationTest.",
            introducedPr = 84
        )
    ) + listOf(

        // ── PR-11B: Local diagnostics reason closure ───────────────────────────

        BaselineSurfaceEntry(
            surfaceId = "android-local-diagnostic-reason-contract",
            displayName = "AndroidLocalDiagnosticReasonContract",
            packagePath = "com.ufo.galaxy.runtime.AndroidLocalDiagnosticReasonContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-11B stable local diagnostics reason contract for V2 canonical diagnosis. " +
                "Defines versioned, code-backed diagnostic reason structures (domain + reason + " +
                "local cause) for runtime/capability/recovery/takeover/mesh-participation paths, " +
                "replacing ad hoc text-only diagnostics. GalaxyWebSocketClient.sendDiagnostics now " +
                "emits diagnostic_schema_version, diagnostic_domain, diagnostic_reason, and local_cause " +
                "in DiagnosticsPayload so local causes are explicit and machine-reconcilable by V2.",
            introducedPr = 85
        ),

        // ── PR-993: Android NL initiation contract ────────────────────────────

        BaselineSurfaceEntry(
            surfaceId = "android-nl-initiation-contract",
            displayName = "AndroidNlInitiationContract",
            packagePath = "com.ufo.galaxy.runtime.AndroidNlInitiationContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-993 canonical Android NL initiation contract. Defines the authority-bounded " +
                "natural-language initiation capability for Android in cross-device mode. Introduces " +
                "NlInitiationMode (1 value: ANDROID_NL_CROSS_DEVICE), NlAuthorityScope (1 value: V2_CENTRAL), " +
                "and NlInitiationMetadata (origin/initiationMode/authorityScope/lineage/correlationId/ " +
                "runtimeSessionId/deviceContext). Enforces the cross_device_enabled gate: build() returns " +
                "null when cross-device is off, preventing NL initiation in local mode. InputRouter.sendViaWebSocket() " +
                "now calls AndroidNlInitiationContract.build() and embeds nl_initiation_origin, " +
                "nl_initiation_mode, nl_initiation_authority_scope, nl_initiation_lineage into the outbound " +
                "TaskSubmitPayload, and includes the full wire map in TaskSubmitContext.extra. " +
                "AndroidNlSemanticContract.nlInitiationMetadata() merges standard handoff metadata with the " +
                "PR-993 wire fields. INITIATION_INVARIANTS declares: cross_device gate required, V2 central " +
                "authority required, no parallel intent system, semantic authority transfers to V2, enters " +
                "V2 main chain. Test: AndroidNlInitiationContractTest.",
            introducedPr = 86
        )
    ) + listOf(

        // ── Operational state surface symmetry PR ─────────────────────────────

        BaselineSurfaceEntry(
            surfaceId = "android-operational-state-surface",
            displayName = "AndroidOperationalStateSurfaceContract",
            packagePath = "com.ufo.galaxy.runtime.AndroidOperationalStateSurfaceContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Unified Android operational/readiness surface contract establishing Android as a " +
                "first-class, structured participant in the cross-repo state model rather than only a partial " +
                "signal source. Introduces SurfaceAxis (11 axes: REGISTRATION_DISCOVERABILITY/CAPABILITY_VISIBILITY/" +
                "OPERATIONAL_READINESS/ACTIVE_USABLE_PATH/DEGRADED_MODE/RECOVERY_REPAIR/CROSS_DEVICE_PARTICIPATION/" +
                "SESSION_CONTINUITY/TASK_INITIATION_ELIGIBILITY/RESULT_CLOSURE/MINIMUM_ACCESS_ADMISSION) and " +
                "AuthorityScope (5 values: ANDROID_LOCAL_AUTHORITATIVE/ANDROID_LOCAL_SIGNAL_V2_COORDINATED/" +
                "ANDROID_PREREQUISITE_V2_ADMITS/ANDROID_LOCAL_COMPLETION_V2_CLOSES/V2_AUTHORITATIVE). " +
                "derive() produces a SurfaceProjection with per-axis state strings, per-axis authority " +
                "classification, and a machine-readable limitations list that explicitly records where V2 " +
                "retains final admission, aggregation, and closure authority. " +
                "DeviceStateSnapshotPayload gains operational_surface_schema_version, operational_surface_states, " +
                "operational_surface_authority, and operational_surface_limitations fields. " +
                "GalaxyConnectionService populates all four fields at sendDeviceStateSnapshot() time. " +
                "Authority boundaries: Android is locally authoritative for identity, capability visibility, " +
                "readiness, active path, degraded mode, and recovery-repair; cross-device participation and " +
                "session continuity are android-local-signal-v2-coordinated; task initiation eligibility is " +
                "android-prerequisite-v2-admits; result closure is android-local-completion-v2-closes; " +
                "minimum_access_admission remains v2_authoritative. " +
                "Test: AndroidOperationalStateSurfaceContractTest.",
            introducedPr = 87
        )
    ) + listOf(

        // ── PR-2: NL-driven execution spine contract ───────────────────────────
        // NOTE: introducedPr=88 is the GitHub PR number for the PR-2 convergence item.

        BaselineSurfaceEntry(
            surfaceId = "android-nl-driven-execution-spine-contract",
            displayName = "AndroidNlDrivenExecutionSpineContract",
            packagePath = "com.ufo.galaxy.runtime.AndroidNlDrivenExecutionSpineContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-2 Android-side natural-language-driven execution spine participation " +
                "contract. Integrates Android as a real participant in the system's full " +
                "problem-solving spine (NL request → center routing → Android execution → " +
                "result return → problem closure). Introduces ExecutionSpineParticipationKind " +
                "(4 values: TAKEOVER_INTERACTIVE/DELEGATED_EXECUTION/LOCAL_ASSISTIVE/DEGRADED_FALLBACK), " +
                "ProblemSolvingClosureClass (5 values: TASK_COMPLETED_PROBLEM_SOLVED/" +
                "TASK_COMPLETED_PROBLEM_PARTIAL/TASK_COMPLETED_PROBLEM_OPEN/" +
                "TASK_INCOMPLETE_PROBLEM_OPEN/EXECUTION_DELEGATED_FURTHER), " +
                "ExecutionSpineTraceContribution (structured trace record per execution), and " +
                "SPINE_PARTICIPATION_INVARIANTS. GoalResultPayload gains " +
                "problem_solving_closure_class and execution_spine_participation_kind fields. " +
                "GoalExecutionPayload gains problem_context and problem_solving_role fields. " +
                "HandoffEnvelopeV2 gains problem_context and problem_solving_role fields. " +
                "AutonomousExecutionPipeline.handleGoalExecution/handleParallelSubtask now " +
                "classify spine participation and populate problem_solving_closure_class in " +
                "emitted GoalResultPayload so V2 unified_result_ingress and " +
                "canonical_completion_ingress can distinguish task completion from " +
                "problem-solving progress without raw-status inspection. V2 integration points: " +
                "core/unified_result_ingress.py (problem_solving_closure_class), " +
                "core/canonical_completion_ingress.py (problem_solving_closure_class), " +
                "core/task_result_canonical_truth_chain.py (execution_spine_participation_kind). " +
                "Test: Pr2AndroidNlDrivenExecutionSpineTest.",
            introducedPr = 88
        )
    ) + listOf(

        // ── PR-5: Runtime observability and problem-solving audit contract ─────

        BaselineSurfaceEntry(
            surfaceId = "android-runtime-observability-audit-contract",
            displayName = "AndroidRuntimeObservabilityAuditContract",
            packagePath = "com.ufo.galaxy.runtime.AndroidRuntimeObservabilityAuditContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-5 Android-side runtime observability and problem-solving audit contract. " +
                "Introduces ExecutionPathTag (6 values: LOCAL_PATH/CROSS_DEVICE_PATH/DELEGATED_PATH/" +
                "TAKEOVER_PATH/DEGRADED_PATH/UNKNOWN) for canonical tagging of execution paths; " +
                "AuditContributionClass (9 values: PARTICIPATION_ATTESTATION/EXECUTION_CONTRIBUTION/" +
                "EXECUTION_OUTCOME/INTERRUPTION_RECORD/RECOVERY_CONTRIBUTION/TAKEOVER_CONTRIBUTION/" +
                "DELEGATED_CONTRIBUTION/OPERATOR_ACTION_OUTCOME/INFORMATIONAL) for end-to-end " +
                "problem-solving audit; ObservabilityReliabilityClass (5 values: HIGH_FIDELITY/" +
                "REDUCED_FIDELITY/STALE/INTERRUPTED/UNKNOWN) for V2-side SLO computation. " +
                "Classifiers classifyExecutionPath(), classifyAuditContribution(), and " +
                "classifyObservabilityReliability() derive all classes from existing runtime signals " +
                "without new probes. DeviceStateSnapshotPayload gains observability_audit_schema_version, " +
                "execution_path_tag, audit_contribution_class, observability_reliability_class fields. " +
                "DeviceExecutionEventPayload gains the same four fields. GalaxyConnectionService populates " +
                "all four fields in sendDeviceStateSnapshot() and in deviceExecutionEventSink enrichment. " +
                "V2 integration points: core/android_device_state_store.py (execution_path_tag), " +
                "core/problem_solving_audit_chain.py (audit_contribution_class), " +
                "metrics/android_slo_metrics.py (observability_reliability_class), " +
                "board/reliability_surface.py (audit_contribution_class). " +
                "OBSERVABILITY_AUDIT_INVARIANTS declares 10 invariants covering path-tag, audit-class, " +
                "and reliability-class emission requirements. " +
                "Test: Pr89AndroidRuntimeObservabilityAuditTest.",
            introducedPr = 89
        )
    ) + listOf(

        // ── 统一真相上行合约（Android Unified Truth Uplink Contract）───────────────

        BaselineSurfaceEntry(
            surfaceId = "android-unified-truth-uplink-contract",
            displayName = "AndroidUnifiedTruthUplinkContract",
            packagePath = "com.ufo.galaxy.runtime.AndroidUnifiedTruthUplinkContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Android 侧向 V2 中心上报运行时真相的统一、稳定、机器可消费合约。" +
                "建立了六大真相分类（TruthCategory：PARTICIPATION/MODE/EXECUTION/CLOSURE_UPSTREAM/" +
                "CONTINUITY/LOCAL_CAPABILITY），统一了此前分散在多个载体中的真相字段。" +
                "引入 ConstraintSemantics（6 值：NONE/RUNTIME_CONSTRAINED/RUNTIME_DEFERRED/" +
                "LOCAL_MODE_GATE_DEFERRED/EXECUTION_PRESSURE/HOLD）消除约束/延迟/持有语义的" +
                "字段组合推断；引入 LocalCapabilityState（5 值：FULL/PARTIAL/DEGRADED/" +
                "UNAVAILABLE/UNKNOWN）统一本地能力真相。" +
                "UnifiedTruthSnapshot 保证 participation_tier/execution_mode_state/" +
                "constraint_semantics/local_capability_state 在所有快照中永不为 null。" +
                "GoalResultPayload 新增 dispatch_eligible/distributed_participant/session_attached/" +
                "local_mode_active/runtime_constrained/runtime_deferred/local_llm_ready/" +
                "accessibility_ready/local_mode_capable 字段，确保 participation_tier 在所有" +
                "委托结果路径中有保底默认值（pre_attach）。" +
                "DeviceStateSnapshotPayload 新增 unified_truth_schema_version/" +
                "dispatch_eligible/distributed_participant/session_attached/local_mode_active/" +
                "runtime_constrained/runtime_deferred/constraint_semantics/local_llm_ready/" +
                "local_mode_capable/local_capability_state 字段。" +
                "GalaxyConnectionService.sendGoalResult 和 sendDeviceStateSnapshot 在单一发送层" +
                "强制填充所有新字段，确保 V2 无需字段组合推断即可消费 Android 运行时真相。" +
                "UPLINK_INVARIANTS 声明 15 条不变量涵盖参与/模式/约束/能力/wire map 一致性。" +
                "V2 集成点：core/android_device_state_store.py（dispatch_eligible/local_mode_capable），" +
                "core/unified_result_ingress.py（participation_tier 保底），" +
                "core/android_runtime_transition_reducer.py（constraint_semantics），" +
                "metrics/android_capability_metrics.py（local_capability_state）。" +
                "Test: AndroidUnifiedTruthUplinkContractTest.",
            introducedPr = 90
        )
    ) + listOf(

        // ── PR-9: Android 最小可用路径与可操作性合约 ─────────────────────────────
        BaselineSurfaceEntry(
            surfaceId = "android-minimal-operability-contract",
            displayName = "AndroidMinimalOperabilityContract",
            packagePath = "com.ufo.galaxy.runtime.AndroidMinimalOperabilityContract",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-9 Android 侧最小可用路径与可操作性合约。将 Android APK 构建、安装、V2 连接、" +
                "跨设备参与、委托执行、本地模式就绪、结果上行与失败诊断从「作者记忆工作流」提升为" +
                "明确、可机器验证、可诊断的形式化可操作性合约。" +
                "引入 OperabilityPathStep（10 步最小可用路径枚举）、" +
                "PathBlockCondition（6 个路径阻断前提，含受影响步骤与是否可自愈标记）、" +
                "CapabilityDegradationKind（5 种仅降级能力的条件，含 isRecoverable 标记）、" +
                "LocalModeReadinessGate（本地模式就绪门，4 维度含硬/软前提区分，assess() 返回 " +
                "LocalModeReadinessSnapshot 含 overall_ready/failing_hard_gates）、" +
                "DelegatedExecutionBlockKind（5 种委托执行阻断分类，含 blocksTaskTypes 列表）、" +
                "FailureDiagnosticKind（9 种失败诊断分类，每类含 actionableHint 机器可读建议）、" +
                "以及 OPERABILITY_INVARIANTS（12 条形式化不变量）。" +
                "与 AndroidLocalDiagnosticReasonContract（运行时层诊断）互补，" +
                "共同实现从操作路径层到运行时层的完整诊断覆盖。" +
                "toContractMetaWireMap() 提供合约元数据 wire 序列化，可嵌入诊断载体或设备状态快照。" +
                "Test: Pr9AndroidMinimalOperabilityContractTest.",
            introducedPr = 9
        )
    ) + listOf(

        // ── 统一参与者生命周期阶段合约 ────────────────────────────────────────────

        BaselineSurfaceEntry(
            surfaceId = "android-unified-participant-lifecycle-phase",
            displayName = "AndroidUnifiedParticipantLifecyclePhase",
            packagePath = "com.ufo.galaxy.runtime.AndroidUnifiedParticipantLifecyclePhase",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "Android 侧统一参与者生命周期阶段合约，消除注册/连接/可见/就绪/参与/接管就绪 " +
                "之间的多字段组合推断歧义，提供 V2 可直接消费的单一权威 wire 字段 unified_lifecycle_phase。" +
                "引入 Phase（10 值枚举：UNREGISTERED/REGISTERED/CONNECTED/VISIBLE/READY/" +
                "TAKEOVER_ELIGIBLE/PARTICIPATING/DEGRADED/RECOVERING/UNAVAILABLE），每个阶段具有 " +
                "稳定 wireValue 和 phaseRank（正值=主路径前进，负值=降级/恢复/不可用）。" +
                "DerivationInput 汇聚 12 个现有运行时信号（FormalParticipantLifecycleState/" +
                "ReconnectRecoveryState/crossDeviceEnabled/wsConnected/hasDurableParticipantId/" +
                "capabilityVisible/sessionAttached/readinessSatisfied/executionBusy/takeoverActive/" +
                "interactionSurfaceReady/governanceBlocked），derive() 按 12 级优先级推导阶段，" +
                "无需新探针。TAKEOVER_ELIGIBLE 明确建模接管就绪语义（READY + 无活跃执行 + 交互面就绪），" +
                "消除原先通过 dispatch_eligible + governance_blocked + execution_busy 组合推断的歧义。" +
                "isActivelyParticipating()/isDispatchAllowed()/isTakeoverAcceptable() 为 V2 提供" +
                "语义查询方法；isForwardTransition()/isDegradationTransition() 支持降级事件检测与" +
                "formation 重平衡触发。DeviceStateSnapshotPayload 新增 unified_lifecycle_phase/" +
                "unified_lifecycle_schema_version 字段；DeviceExecutionEventPayload 同步新增。" +
                "GalaxyConnectionService.sendDeviceStateSnapshot() 在快照发送层唯一推导并填充，" +
                "deviceExecutionEventSink 在执行事件发射层同步填充，确保每条上行消息均携带精确阶段。" +
                "PHASE_INVARIANTS 声明 12 条形式化不变量，覆盖各阶段前提条件、wire 字段填充要求。" +
                "V2 集成点：core/android_device_state_store.py（unified_lifecycle_phase），" +
                "core/android_runtime_transition_reducer.py（阶段迁移检测），" +
                "core/takeover_eligibility_router.py（isTakeoverAcceptable 语义），" +
                "metrics/android_lifecycle_metrics.py（phaseRank SLO 计算）。" +
                "Test: AndroidUnifiedParticipantLifecyclePhaseTest.",
            introducedPr = 91
        ),
        BaselineSurfaceEntry(
            surfaceId = "android-registration-posture-handshake",
            displayName = "RegistrationPostureHandshake",
            packagePath = "com.ufo.galaxy.network.GalaxyWebSocketClient",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-B2 (Android) 在 device_register 和 capability_report 两条握手消息中补充了 " +
                "缺失的 source_runtime_posture 字段，使 V2 在注册阶段即可进行更早、更准确的参与层级推断，" +
                "而无需等待后续的 device_state_snapshot 或 device_execution_event。" +
                "推导规则：crossDeviceEnabled && goal_execution_enabled → join_runtime；否则 control_only。" +
                "两条握手消息携带相同值以保证一致性。" +
                "V2 集成点：core/android_session_registrar.py（device_register 消费 source_runtime_posture），" +
                "core/capability_report_ingress.py（capability_report 消费 source_runtime_posture），" +
                "core/participation_tier_router.py（利用注册阶段 posture 实现早期层级推断）。" +
                "Test: HandshakeRegistrationPostureTest.",
            introducedPr = 92
        ),
        BaselineSurfaceEntry(
            surfaceId = "android-operator-action-receiver",
            displayName = "OperatorActionReceiver",
            packagePath = "com.ufo.galaxy.runtime.OperatorActionReceiver",
            stability = SurfaceStability.CANONICAL_STABLE,
            extensionGuidance = ExtensionGuidance.EXTEND,
            rationale = "PR-B2 (Android) 创建了 OperatorActionReceiver 作为 Android 侧接收和路由 " +
                "V2 下行 directed operator action 的专用、可独立测试组件，解决了以下三个问题：" +
                "（1）V2 下行 operator action 的接收与治理验证逻辑此前以内联方式嵌入 GalaxyConnectionService，" +
                "缺少可独立测试的接收器组件；" +
                "（2）OperatorActionResultPayload 缺少关键参与上下文字段（participation_tier、" +
                "local_mode_active、runtime_constrained、runtime_deferred、delegated_execution_active），" +
                "导致 V2 下游消费方无法对 operator action 的完整运行时语境作出判断；" +
                "（3）本地参与状态、分布式参与状态与执行活跃状态之间的歧义未消除。" +
                "OperatorActionReceiver 提供 buildParticipationContext()（在 action 接收时刻捕获完整" +
                "参与上下文快照）和 evaluateGovernanceDecision()（将 V2 下行 action 路由至" +
                "AndroidOperatorActionGovernanceContract 并返回携带完整参与上下文的 GovernanceDecision）。" +
                "RECEIVER_INVARIANTS 声明 7 条形式化不变量。" +
                "GalaxyConnectionService.handleOperatorActionRequest() 更新为通过 OperatorActionReceiver" +
                "进行治理门控，并在 DECISION 和 EXECUTION 两个阶段均携带完整参与上下文。" +
                "V2 集成点：core/operator_action_consumer.py（消费新增参与上下文字段），" +
                "core/participation_tier_router.py（利用 operator_action_result 中的 participation_tier），" +
                "panels/operator_board.py（利用 delegated_execution_active 字段）。" +
                "Test: OperatorActionReceiverTest.",
            introducedPr = 92
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns all entries with the given [stability] tier.
     */
    fun byStability(stability: SurfaceStability): List<BaselineSurfaceEntry> =
        entries.filter { it.stability == stability }

    /**
     * Returns all entries with the given [guidance] classification.
     */
    fun byGuidance(guidance: ExtensionGuidance): List<BaselineSurfaceEntry> =
        entries.filter { it.extensionGuidance == guidance }

    /**
     * Returns the entry for [surfaceId], or `null` if no entry is registered.
     */
    fun forId(surfaceId: String): BaselineSurfaceEntry? =
        entries.firstOrNull { it.surfaceId == surfaceId }

    /**
     * Set of all [BaselineSurfaceEntry.surfaceId] values for surfaces with
     * [SurfaceStability.CANONICAL_STABLE] or [SurfaceStability.CANONICAL_FROZEN].
     */
    val canonicalSurfaceIds: Set<String> =
        entries
            .filter {
                it.stability == SurfaceStability.CANONICAL_STABLE ||
                    it.stability == SurfaceStability.CANONICAL_FROZEN
            }
            .map { it.surfaceId }
            .toSet()

    /**
     * Set of all [BaselineSurfaceEntry.surfaceId] values for surfaces with
     * [SurfaceStability.TRANSITIONAL] or [SurfaceStability.RETIREMENT_GATED].
     */
    val transitionalSurfaceIds: Set<String> =
        entries
            .filter {
                it.stability == SurfaceStability.TRANSITIONAL ||
                    it.stability == SurfaceStability.RETIREMENT_GATED
            }
            .map { it.surfaceId }
            .toSet()

    /**
     * Set of all [BaselineSurfaceEntry.surfaceId] values for surfaces where
     * future work should [ExtensionGuidance.EXTEND].
     */
    val extendableSurfaceIds: Set<String> =
        entries
            .filter { it.extensionGuidance == ExtensionGuidance.EXTEND }
            .map { it.surfaceId }
            .toSet()

    /**
     * Returns `true` if the given [surfaceId] is registered in this baseline.
     */
    fun isRegistered(surfaceId: String): Boolean = forId(surfaceId) != null

    // ── System-level summary constants ────────────────────────────────────────

    /**
     * The PR number that established this stabilization baseline.
     */
    const val BASELINE_PR: Int = 11

    /**
     * Short description of what this baseline declares.
     */
    const val BASELINE_DESCRIPTION: String =
        "Stabilization baseline after governance/dispatch/truth/compatibility work (PR-1 through PR-10+). " +
            "Identifies canonical stable, canonical frozen, transitional, and retirement-gated surfaces. " +
            "Future contributors should EXTEND canonical surfaces and CONVERGE away from transitional ones."

    /**
     * The category of future work that must prefer canonical-only paths.
     *
     * Any of these categories attempted through transitional or compatibility surfaces
     * constitutes a governance violation that will reintroduce compatibility drift.
     */
    val canonicalOnlyWorkCategories: Set<String> = setOf(
        "new_execution_path_variants",
        "new_session_identity_fields",
        "new_rollout_gate_flags",
        "new_delegated_signal_kinds",
        "new_failure_classification_categories",
        "new_protocol_consistency_rules",
        "new_canonical_projection_fields"
    )

    /**
     * The surfaces where future extension should happen (not convergence).
     *
     * This is the authoritative list of canonical extension points after the stabilization
     * baseline.  Any new feature work should be routed through one of these surfaces.
     */
    val extensionPoints: Set<String> = setOf(
        "CanonicalDispatchChain — add new DispatchPathDescriptor entries",
        "RolloutControlSnapshot — add new rollout gate fields (center-coordinated)",
        "ExecutionRouteTag — add new result-path tags (center-coordinated)",
        "DelegatedExecutionSignal — add new signal metadata fields (backward-compatible)",
        "CrossDeviceSetupError — add new failure Category values",
        "TakeoverFallbackEvent — add new fallback Cause values",
        "CompatibilitySurfaceRetirementRegistry — register new compatibility surfaces",
        "LongTailCompatibilityRegistry — promote TRANSITIONAL entries to PROMOTED or CANONICAL",
        "UgcpSharedSchemaAlignment — add new canonical alignment entries",
        "UgcpProtocolConsistencyRules — add new consistency rules",
        "GalaxyLogger TAG_* constants — add new structured log tags"
    )

    /**
     * The surfaces where convergence should continue (not extension).
     *
     * Convergence work must migrate consumers away from these surfaces toward
     * the canonical replacements recorded in the corresponding [BaselineSurfaceEntry].
     */
    val convergenceTargets: Set<String> = setOf(
        "RuntimeController.registrationError → retired (deprecated in PR-36; consumers migrated to setupError)",
        "RuntimeController.currentSessionSnapshot() → RuntimeController.hostSessionSnapshot",
        "UgcpSharedSchemaAlignment legacy alias maps → canonical vocabulary sets",
        "LongTailCompatibilityRegistry TRANSITIONAL entries → PROMOTED or CANONICAL",
        "ProtocolSurface TRANSITIONAL_COMPATIBILITY entries → canonical ProtocolSurface entries"
    )
}
