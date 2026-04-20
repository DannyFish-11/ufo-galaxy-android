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
