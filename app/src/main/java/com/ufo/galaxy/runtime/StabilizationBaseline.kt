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
