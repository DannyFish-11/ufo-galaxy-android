package com.ufo.galaxy.runtime

/**
 * PR-65 (Android) — Authoritative-path alignment audit: structured classification of all
 * Android-side compat / legacy runtime behaviors.
 *
 * [AndroidAuthoritativePathAlignmentAudit] is the single, machine-readable reference
 * that answers the four acceptance criteria required for the Android companion to V2's
 * single-authoritative-path convergence work:
 *
 *  1. Which Android-side behaviors are **canonical vs. compat / legacy**?
 *  2. That Android **default behavior is less permissive** toward deprecated runtime influence.
 *  3. That **participant-side compatibility influence is easier for V2 to classify and control**.
 *  4. What Android-side cleanup **remains deferred** for later phases.
 *
 * ## Behavior classification model
 *
 * Every Android-side runtime behavior is assigned exactly one [BehaviorTier]:
 *
 * | Tier                          | Wire value                      | Meaning                                                                                            |
 * |-------------------------------|---------------------------------|-----------------------------------------------------------------------------------------------------|
 * | [BehaviorTier.CANONICAL_DEFAULT]   | `canonical_default`        | The authoritative runtime behavior under normal conditions.  V2 should treat artifacts from this tier as canonical participant evidence. |
 * | [BehaviorTier.COMPAT_ALLOWED]      | `compat_allowed`           | Legacy / compatibility behavior still intentionally permitted.  Explicitly bounded; must not silently expand. |
 * | [BehaviorTier.OBSERVATION_ONLY]    | `observation_only`         | Behavior retained for diagnostic / audit surfaces only.  Must not write canonical state or influence V2 truth. |
 * | [BehaviorTier.DEPRECATED_BUT_LIVE] | `deprecated_but_live`      | Behavior that is deprecated but not yet removed.  Explicitly gated; must not receive new callers.  |
 * | [BehaviorTier.BLOCKED_RETIRED]     | `blocked_retired`          | Behavior that is blocked or fully retired.  No new traffic should reach these paths.               |
 *
 * ## Signal semantics contract
 *
 * Android-originated signals reaching V2 must be classifiable into one of three categories
 * to preserve the authority boundary:
 *
 * - **Canonical participant evidence** — produced by [BehaviorTier.CANONICAL_DEFAULT] paths;
 *   V2 should apply these to canonical participant truth without further filtering.
 * - **Observation-only signals** — produced by [BehaviorTier.OBSERVATION_ONLY] or
 *   [BehaviorTier.COMPAT_ALLOWED] paths; V2 may consume these for diagnostics but must not
 *   let them mutate canonical truth unilaterally.
 * - **Deprecated / blocked signals** — produced by [BehaviorTier.DEPRECATED_BUT_LIVE] or
 *   [BehaviorTier.BLOCKED_RETIRED] paths; V2 must treat these as legacy-influenced and
 *   route them through explicit compat evaluation before any canonical state write.
 *
 * ## Default-off posture for deprecated/compat behaviors
 *
 * Any behavior in [BehaviorTier.DEPRECATED_BUT_LIVE] or [BehaviorTier.BLOCKED_RETIRED]
 * is considered **default-off**: it must not activate under normal participant conditions
 * without an explicit rollout flag or V2 compat gate.  [AndroidCompatLegacyBlockingParticipant]
 * enforces this at integration points; callers must query it before permitting execution.
 *
 * ## Relationship to sibling components
 *
 * - [AndroidCompatLegacyBlockingParticipant] (PR-8) — enforcement layer; evaluates blocking
 *   decisions at each integration point.  [AndroidAuthoritativePathAlignmentAudit] provides
 *   the *classification inventory*; [AndroidCompatLegacyBlockingParticipant] provides
 *   *runtime enforcement*.
 * - [CompatibilitySurfaceRetirementRegistry] (PR-10) — surface-level retirement plan.
 *   [AndroidAuthoritativePathAlignmentAudit] provides the *behavior-level* view across all
 *   tiers, including canonical surfaces that PR-10 does not cover.
 * - [StabilizationBaseline] (PR-11) — declares which surfaces are safe to build on.
 *   [AndroidAuthoritativePathAlignmentAudit] declares *which tier* each surface's behavior
 *   belongs to from the perspective of the authoritative-path model.
 * - [TruthReconciliationReducer] (PR-64) — single reducer for truth convergence; all
 *   authoritative truth inputs classified here as [BehaviorTier.CANONICAL_DEFAULT] flow
 *   through the reducer before influencing [AndroidParticipantRuntimeTruth].
 *
 * @see AndroidCompatLegacyBlockingParticipant
 * @see CompatibilitySurfaceRetirementRegistry
 * @see StabilizationBaseline
 * @see TruthReconciliationReducer
 */
object AndroidAuthoritativePathAlignmentAudit {

    // ── Behavior tier classification ──────────────────────────────────────────

    /**
     * Classification tier for an Android-side runtime behavior in the authoritative-path model.
     */
    enum class BehaviorTier(val wireValue: String) {

        /**
         * The authoritative runtime behavior under normal conditions.
         *
         * These are the paths Android prefers by default.  Artifacts produced on these
         * paths should be treated by V2 as canonical participant evidence.  New participant
         * logic should extend these surfaces, not the compat / legacy paths below.
         */
        CANONICAL_DEFAULT("canonical_default"),

        /**
         * Legacy / compatibility behavior still intentionally permitted.
         *
         * These paths remain because a canonical replacement is not yet fully deployed or
         * because explicit cross-repo coordination is required before retirement.  They are
         * explicitly bounded: their scope must not expand, and new callers must not be added.
         *
         * V2 may consume artifacts from these paths but must not treat them as canonical
         * participant evidence without additional filtering.
         */
        COMPAT_ALLOWED("compat_allowed"),

        /**
         * Behavior retained solely for diagnostic or audit surfaces.
         *
         * These paths produce no canonical state writes and do not influence V2 truth.
         * Their output is permitted for logging, observability, and operator inspection only.
         * The [AndroidCompatLegacyBlockingParticipant.DECISION_ALLOW_OBSERVATION_ONLY]
         * decision governs these paths at runtime.
         */
        OBSERVATION_ONLY("observation_only"),

        /**
         * Behavior that is deprecated but not yet fully removed.
         *
         * These paths are explicitly gated: they must not receive new callers, they must not
         * activate under normal runtime conditions, and they must not silently influence V2
         * canonical truth.  Activation requires an explicit rollout override or compat gate.
         *
         * V2 must route artifacts from these paths through explicit compat evaluation before
         * any canonical state write.
         */
        DEPRECATED_BUT_LIVE("deprecated_but_live"),

        /**
         * Behavior that is blocked or fully retired.
         *
         * These paths are suppressed by [AndroidCompatLegacyBlockingParticipant] and must
         * not reach V2 canonical surfaces.  Continued presence in code is for reference /
         * retirement tracking only.
         */
        BLOCKED_RETIRED("blocked_retired");

        companion object {
            /** Returns the [BehaviorTier] whose [wireValue] equals [value], or `null`. */
            fun fromValue(value: String): BehaviorTier? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Behavior entry ────────────────────────────────────────────────────────

    /**
     * A single classified Android-side behavior entry in the authoritative-path audit.
     *
     * @property behaviorId         Stable identifier for this behavior, used in query helpers
     *                              and audit surfaces.
     * @property tier               The [BehaviorTier] classifying this behavior.
     * @property label              Short human-readable label.
     * @property description        Detailed description of the behavior and its current role.
     * @property signalSemantics    How artifacts produced on this path should be treated by V2
     *                              ([SignalSemantics]).
     * @property enforcedBy         The Android component(s) enforcing this classification at
     *                              runtime (e.g. [AndroidCompatLegacyBlockingParticipant]).
     * @property deferralNote       Optional note describing any cleanup deferred to later phases.
     * @property introducedPr       The PR that introduced or reclassified this behavior.
     */
    data class BehaviorEntry(
        val behaviorId: String,
        val tier: BehaviorTier,
        val label: String,
        val description: String,
        val signalSemantics: SignalSemantics,
        val enforcedBy: String,
        val deferralNote: String? = null,
        val introducedPr: Int
    )

    /**
     * Signal semantics classification — how V2 should treat artifacts produced on this path.
     */
    enum class SignalSemantics(val wireValue: String) {
        /**
         * V2 should apply artifacts directly to canonical participant truth.
         * These are first-class, authority-bearing participant signals.
         */
        CANONICAL_PARTICIPANT_EVIDENCE("canonical_participant_evidence"),

        /**
         * V2 may use for diagnostics and operator surfaces but must not let these
         * directly mutate canonical truth unilaterally.
         */
        OBSERVATION_SIGNAL("observation_signal"),

        /**
         * V2 must route through explicit compat evaluation before any canonical state write.
         * These artifacts carry legacy-influenced semantics.
         */
        LEGACY_INFLUENCED("legacy_influenced"),

        /**
         * V2 should reject or drop; this path is blocked on the Android side.
         */
        BLOCKED("blocked");

        companion object {
            /** Returns the [SignalSemantics] whose [wireValue] equals [value], or `null`. */
            fun fromValue(value: String): SignalSemantics? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Audit registry ────────────────────────────────────────────────────────

    /**
     * Complete registry of Android-side runtime behavior classifications.
     *
     * Every Android behavior that can influence participant state, emit signals reaching V2,
     * or affect truth convergence is listed here with an explicit [BehaviorTier].  The list
     * is the single source of truth for "what does Android emit and which tier is it in?"
     *
     * The registry is organized into five groups matching the five [BehaviorTier] values.
     */
    val entries: List<BehaviorEntry> = listOf(

        // ══════════════════════════════════════════════════════════════════════
        // CANONICAL_DEFAULT — authoritative behaviors preferred under normal conditions
        // ══════════════════════════════════════════════════════════════════════

        BehaviorEntry(
            behaviorId = "reconciliation_signal_emission",
            tier = BehaviorTier.CANONICAL_DEFAULT,
            label = "ReconciliationSignal emission via RuntimeController",
            description = "RuntimeController emits structured ReconciliationSignals on its " +
                "reconciliationSignals SharedFlow for all task lifecycle events " +
                "(TASK_ACCEPTED, TASK_STATUS_UPDATE, TASK_RESULT, TASK_CANCELLED, " +
                "TASK_FAILED, PARTICIPANT_STATE, RUNTIME_TRUTH_SNAPSHOT). " +
                "GalaxyConnectionService collects this flow and transmits each signal to V2 " +
                "as a RECONCILIATION_SIGNAL wire message. This is the canonical outbound " +
                "signal channel; all participant state updates reaching V2 should originate here.",
            signalSemantics = SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE,
            enforcedBy = "RuntimeController.reconciliationSignals / GalaxyConnectionService",
            introducedPr = 65
        ),

        BehaviorEntry(
            behaviorId = "runtime_truth_snapshot",
            tier = BehaviorTier.CANONICAL_DEFAULT,
            label = "AndroidParticipantRuntimeTruth snapshot publication",
            description = "RuntimeController.publishRuntimeTruthSnapshot() assembles a full " +
                "AndroidParticipantRuntimeTruth snapshot and emits it as a " +
                "RUNTIME_TRUTH_SNAPSHOT ReconciliationSignal. V2 treats this as Android's " +
                "authoritative self-reported local truth for canonical reconciliation passes. " +
                "Emitted on reconnect (with IDLE active task state) and on demand.",
            signalSemantics = SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE,
            enforcedBy = "RuntimeController.publishRuntimeTruthSnapshot()",
            introducedPr = 65
        ),

        BehaviorEntry(
            behaviorId = "truth_reconciliation_reducer",
            tier = BehaviorTier.CANONICAL_DEFAULT,
            label = "TruthReconciliationReducer — single local truth convergence entry point",
            description = "TruthReconciliationReducer.reduce(current, patch) is the single " +
                "entry point for all local truth mutations. Enforces epoch gating, " +
                "authoritative-only mutation, terminal idempotency, and participant safety. " +
                "All authoritative truth updates (delegated result/cancelled/failed, " +
                "handoff accepted/rejected, takeover accepted/rejected, session terminal) " +
                "must flow through the reducer before updating AndroidParticipantRuntimeTruth.",
            signalSemantics = SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE,
            enforcedBy = "TruthReconciliationReducer",
            introducedPr = 65
        ),

        BehaviorEntry(
            behaviorId = "delegated_execution_signal_emission",
            tier = BehaviorTier.CANONICAL_DEFAULT,
            label = "DelegatedExecutionSignal emission via DelegatedTakeoverExecutor",
            description = "DelegatedTakeoverExecutor emits exactly three signals per accepted " +
                "execution unit in invariant order: ACK (emissionSeq=1), PROGRESS (2), " +
                "RESULT (3). EmittedSignalLedger records every emitted signal for replay-safe " +
                "re-delivery. Signal identity (signalId, emissionSeq) enables V2 deduplication " +
                "and sequence verification. This is the canonical outbound delegated execution " +
                "signal chain.",
            signalSemantics = SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE,
            enforcedBy = "DelegatedTakeoverExecutor / EmittedSignalLedger",
            introducedPr = 65
        ),

        BehaviorEntry(
            behaviorId = "device_readiness_report",
            tier = BehaviorTier.CANONICAL_DEFAULT,
            label = "Device readiness report on service start",
            description = "GalaxyConnectionService emits an initial device readiness report " +
                "immediately after service start via sendDeviceReadinessReport(). This gives " +
                "V2's readiness gate and governance paths a structured Android-side baseline " +
                "artifact. All dimensions start as UNKNOWN at initial report time, which is " +
                "itself a valid structured signal (V2 can distinguish 'not yet reported' from " +
                "'reported with a gap').",
            signalSemantics = SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE,
            enforcedBy = "GalaxyConnectionService.sendDeviceReadinessReport()",
            introducedPr = 65
        ),

        BehaviorEntry(
            behaviorId = "rollout_safe_defaults",
            tier = BehaviorTier.CANONICAL_DEFAULT,
            label = "RolloutControlSnapshot.SAFE_DEFAULTS — default-off cross-device / goal execution",
            description = "RolloutControlSnapshot.SAFE_DEFAULTS disables cross-device and " +
                "goal execution by default (crossDeviceAllowed=false, goalExecutionAllowed=false). " +
                "This ensures that the system starts in the safest local-only mode and that " +
                "remote execution paths must be explicitly opted into. Prevents unintended " +
                "activation of remote/delegated paths on first use.",
            signalSemantics = SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE,
            enforcedBy = "RolloutControlSnapshot.SAFE_DEFAULTS",
            introducedPr = 65
        ),

        BehaviorEntry(
            behaviorId = "compat_legacy_blocking_canonical_confirm",
            tier = BehaviorTier.CANONICAL_DEFAULT,
            label = "AndroidCompatLegacyBlockingParticipant — CANONICAL_RUNTIME_PATH_CONFIRMED decision",
            description = "When AndroidCompatLegacyBlockingParticipant.evaluateBlockingDecision() " +
                "returns ConfirmCanonicalRuntimeTransition, the path has been confirmed as " +
                "canonical and execution proceeds through V2 canonical flow controls without " +
                "restriction. Unknown influence class wire values default to " +
                "BLOCK_DUE_TO_LEGACY_RUNTIME_PATH (safe-by-default blocking posture).",
            signalSemantics = SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE,
            enforcedBy = "AndroidCompatLegacyBlockingParticipant.evaluateBlockingDecision()",
            introducedPr = 65
        ),

        // ══════════════════════════════════════════════════════════════════════
        // COMPAT_ALLOWED — compatibility behaviors still intentionally permitted
        // ══════════════════════════════════════════════════════════════════════

        BehaviorEntry(
            behaviorId = "legacy_message_type_remap",
            tier = BehaviorTier.COMPAT_ALLOWED,
            label = "Legacy message type remapping (MsgType.LEGACY_TYPE_MAP / toV3Type())",
            description = "Inbound legacy task_execute and task_status_query messages are " +
                "remapped to task_assign at two layers: MsgType.LEGACY_TYPE_MAP (toV3Type()) " +
                "and GalaxyWebSocketClient.handleMessage(). No fork logic is maintained; the " +
                "canonical task_assign path is reached regardless. Retained until V2 stops " +
                "producing legacy-typed messages.",
            signalSemantics = SignalSemantics.OBSERVATION_SIGNAL,
            enforcedBy = "MsgType.LEGACY_TYPE_MAP / CanonicalDispatchChain COMPATIBILITY path",
            deferralNote = "Retire when V2 stops producing legacy-typed messages; " +
                "gated on V2-side LEGACY_TYPE_MAP removal.",
            introducedPr = 65
        ),

        BehaviorEntry(
            behaviorId = "long_tail_compat_handlers",
            tier = BehaviorTier.COMPAT_ALLOWED,
            label = "Long-tail compat message handlers (LongTailCompatibilityRegistry)",
            description = "Minimal-compat handlers for low-frequency or legacy message types " +
                "(WAKE_EVENT, SESSION_MIGRATE, BROADCAST, etc.) retained in LongTailCompatibilityRegistry. " +
                "These handlers log or degrade gracefully but do not implement canonical behavior. " +
                "Explicitly bounded: no new canonical logic should route through these handlers.",
            signalSemantics = SignalSemantics.OBSERVATION_SIGNAL,
            enforcedBy = "LongTailCompatibilityRegistry",
            deferralNote = "Each entry has a stated retirement gate in LongTailCompatibilityRegistry. " +
                "Retire per-entry when cross-repo coordination clears.",
            introducedPr = 65
        ),

        BehaviorEntry(
            behaviorId = "execution_contract_compat_validator",
            tier = BehaviorTier.COMPAT_ALLOWED,
            label = "ExecutionContractCompatibilityValidator — legacy contract acceptance",
            description = "ExecutionContractCompatibilityValidator accepts payloads that pass " +
                "legacy contract checks alongside canonical checks. Retained because some V2 " +
                "producers still emit legacy-shaped payloads. Compat acceptance is explicitly " +
                "bounded: AndroidCompatLegacyBlockingParticipant at " +
                "INTEGRATION_COMPAT_VALIDATOR must be queried before allowing legacy-accepted " +
                "payloads to proceed through canonical execution paths.",
            signalSemantics = SignalSemantics.LEGACY_INFLUENCED,
            enforcedBy = "AndroidCompatLegacyBlockingParticipant.INTEGRATION_COMPAT_VALIDATOR",
            deferralNote = "Retire when V2 exclusively emits canonical-shaped execution payloads.",
            introducedPr = 65
        ),

        // ══════════════════════════════════════════════════════════════════════
        // OBSERVATION_ONLY — retained for diagnostics/audit; no canonical state writes
        // ══════════════════════════════════════════════════════════════════════

        BehaviorEntry(
            behaviorId = "compat_surface_retirement_registry_decommission",
            tier = BehaviorTier.OBSERVATION_ONLY,
            label = "CompatibilitySurfaceRetirementRegistry DECOMMISSION_CANDIDATE entries",
            description = "Entries classified as DECOMMISSION_CANDIDATE in " +
                "CompatibilitySurfaceRetirementRegistry have the lowest remaining utility " +
                "and no active extension. Their surfaces are retained only for reference / " +
                "audit inspection. No new callers should use these surfaces; they are " +
                "permitted to remain visible only for operator review.",
            signalSemantics = SignalSemantics.OBSERVATION_SIGNAL,
            enforcedBy = "CompatibilitySurfaceRetirementRegistry",
            deferralNote = "Remove in next cleanup pass once per-entry retirement gate clears.",
            introducedPr = 65
        ),

        BehaviorEntry(
            behaviorId = "compat_legacy_blocking_observation_only",
            tier = BehaviorTier.OBSERVATION_ONLY,
            label = "AndroidCompatLegacyBlockingParticipant — ALLOW_FOR_OBSERVATION_ONLY decision",
            description = "When AndroidCompatLegacyBlockingParticipant.evaluateBlockingDecision() " +
                "returns AllowObservationOnly (ALLOW_FOR_OBSERVATION_ONLY influence class), " +
                "the path is permitted for passive observation. It must not write canonical " +
                "state, emit authoritative results, influence signal semantics, or alter " +
                "runtime transitions. Output from these paths is read-only diagnostic surface.",
            signalSemantics = SignalSemantics.OBSERVATION_SIGNAL,
            enforcedBy = "AndroidCompatLegacyBlockingParticipant.evaluateBlockingDecision()",
            introducedPr = 65
        ),

        BehaviorEntry(
            behaviorId = "runtime_observability_metadata",
            tier = BehaviorTier.OBSERVATION_ONLY,
            label = "RuntimeObservabilityMetadata — structured diagnostic metadata",
            description = "RuntimeObservabilityMetadata provides structured diagnostic metadata " +
                "for operator surfaces, audit, and telemetry. It does not produce canonical " +
                "truth updates; its role is to make runtime behavior reviewable by V2 operators " +
                "and tooling without influencing the canonical execution path.",
            signalSemantics = SignalSemantics.OBSERVATION_SIGNAL,
            enforcedBy = "RuntimeObservabilityMetadata",
            introducedPr = 65
        ),

        // ══════════════════════════════════════════════════════════════════════
        // DEPRECATED_BUT_LIVE — deprecated, gated, must not receive new callers
        // ══════════════════════════════════════════════════════════════════════

        BehaviorEntry(
            behaviorId = "high_risk_active_compat_surfaces",
            tier = BehaviorTier.DEPRECATED_BUT_LIVE,
            label = "CompatibilitySurfaceRetirementRegistry HIGH_RISK_ACTIVE entries",
            description = "Entries classified as HIGH_RISK_ACTIVE in " +
                "CompatibilitySurfaceRetirementRegistry are operationally visible enough to " +
                "be mistaken for canonical governance layers. They have canonical replacements " +
                "but no retirement timeline yet. Must not be extended. New code must use the " +
                "canonical replacement recorded in each entry's canonicalReplacement field.",
            signalSemantics = SignalSemantics.LEGACY_INFLUENCED,
            enforcedBy = "CompatibilitySurfaceRetirementRegistry / AndroidCompatLegacyBlockingParticipant",
            deferralNote = "Each HIGH_RISK_ACTIVE entry requires an explicit retirement " +
                "timeline before it can be removed. Deferred pending V2 migration completion.",
            introducedPr = 65
        ),

        BehaviorEntry(
            behaviorId = "legacy_fallback_local_after_delegated_failure",
            tier = BehaviorTier.DEPRECATED_BUT_LIVE,
            label = "Fallback-to-local after delegated execution failure",
            description = "When delegated execution fails and RolloutControlSnapshot.fallbackToLocalAllowed " +
                "is true, the runtime may fall back to local execution. This behavior prevents " +
                "hard failures under degraded cross-device connectivity but carries deprecated " +
                "semantics: it can silently substitute local execution for a V2-intended delegated " +
                "task, potentially confusing V2's canonical participant view. Must not be invoked " +
                "as a default without V2 awareness.",
            signalSemantics = SignalSemantics.LEGACY_INFLUENCED,
            enforcedBy = "RolloutControlSnapshot.fallbackToLocalAllowed / TakeoverFallbackEvent",
            deferralNote = "Retire fallback-local path once V2 has explicit fallback-decision " +
                "authority and can instruct Android rather than Android deciding unilaterally.",
            introducedPr = 65
        ),

        BehaviorEntry(
            behaviorId = "ambiguous_legacy_state_quarantine",
            tier = BehaviorTier.DEPRECATED_BUT_LIVE,
            label = "Quarantined legacy execution state pending V2 coordination",
            description = "When AndroidCompatLegacyBlockingParticipant returns " +
                "QuarantineLegacyExecutionState, the affected unit's execution state from a " +
                "prior reconnect era, interrupted recovery, or incomplete replay sequence " +
                "cannot be resolved locally. The state is held in the quarantine registry. " +
                "It remains live (not yet discarded) because discard requires V2 alignment. " +
                "V2 must treat quarantined states as deprecated-but-present and must not " +
                "apply them to canonical truth until alignment is complete.",
            signalSemantics = SignalSemantics.LEGACY_INFLUENCED,
            enforcedBy = "AndroidCompatLegacyBlockingParticipant quarantine registry",
            deferralNote = "Clear quarantine registry after V2 center-side alignment confirms " +
                "each quarantined state is resolved.",
            introducedPr = 65
        ),

        // ══════════════════════════════════════════════════════════════════════
        // BLOCKED_RETIRED — blocked by AndroidCompatLegacyBlockingParticipant; no new traffic
        // ══════════════════════════════════════════════════════════════════════

        BehaviorEntry(
            behaviorId = "legacy_runtime_path_blocked",
            tier = BehaviorTier.BLOCKED_RETIRED,
            label = "Non-canonical runtime paths — blocked via BLOCK_DUE_TO_LEGACY_RUNTIME_PATH",
            description = "Legacy executor, legacy delegated receiver contract, and old pipeline " +
                "activation paths are blocked by AndroidCompatLegacyBlockingParticipant when " +
                "the influence class is BLOCK_DUE_TO_LEGACY_RUNTIME_PATH. Blocked paths are " +
                "registered in the blocked-path registry and must not receive new traffic. " +
                "AndroidCompatLegacyBlockingParticipant.isPathBlocked() returns true for all " +
                "registered blocked paths.",
            signalSemantics = SignalSemantics.BLOCKED,
            enforcedBy = "AndroidCompatLegacyBlockingParticipant blocked-path registry",
            introducedPr = 65
        ),

        BehaviorEntry(
            behaviorId = "compat_contract_emit_suppressed",
            tier = BehaviorTier.BLOCKED_RETIRED,
            label = "Legacy compat contract emission — suppressed via BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE",
            description = "Old validator acceptance of compat-shaped payloads, old emit semantics " +
                "forwarding compat-contract results, old contract adapter remapping, and legacy " +
                "result pass-through that bypasses canonical truth gating are all suppressed by " +
                "AndroidCompatLegacyBlockingParticipant when the influence class is " +
                "BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE. The SuppressLegacyEmit decision prevents " +
                "these artifacts from reaching V2 canonical surfaces.",
            signalSemantics = SignalSemantics.BLOCKED,
            enforcedBy = "AndroidCompatLegacyBlockingParticipant SuppressLegacyEmit decision",
            introducedPr = 65
        )
    )

    // ── Behavior tier counts ──────────────────────────────────────────────────

    /** Expected count of [BehaviorTier.CANONICAL_DEFAULT] entries in [entries]. */
    const val CANONICAL_DEFAULT_COUNT = 7

    /** Expected count of [BehaviorTier.COMPAT_ALLOWED] entries in [entries]. */
    const val COMPAT_ALLOWED_COUNT = 3

    /** Expected count of [BehaviorTier.OBSERVATION_ONLY] entries in [entries]. */
    const val OBSERVATION_ONLY_COUNT = 3

    /** Expected count of [BehaviorTier.DEPRECATED_BUT_LIVE] entries in [entries]. */
    const val DEPRECATED_BUT_LIVE_COUNT = 3

    /** Expected count of [BehaviorTier.BLOCKED_RETIRED] entries in [entries]. */
    const val BLOCKED_RETIRED_COUNT = 2

    /** PR designation for this audit surface. */
    const val INTRODUCED_PR = 65

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns all entries in [BehaviorTier.CANONICAL_DEFAULT].
     *
     * Artifacts produced on these paths should be treated by V2 as canonical participant
     * evidence.
     */
    fun canonicalDefaultEntries(): List<BehaviorEntry> =
        entries.filter { it.tier == BehaviorTier.CANONICAL_DEFAULT }

    /**
     * Returns all entries in [BehaviorTier.COMPAT_ALLOWED].
     *
     * These paths remain intentionally permitted but must not be extended.  V2 must
     * not treat artifacts from these paths as canonical participant evidence without
     * additional filtering.
     */
    fun compatAllowedEntries(): List<BehaviorEntry> =
        entries.filter { it.tier == BehaviorTier.COMPAT_ALLOWED }

    /**
     * Returns all entries in [BehaviorTier.OBSERVATION_ONLY].
     *
     * Output from these paths is read-only diagnostic surface and must not write
     * canonical state or influence V2 truth.
     */
    fun observationOnlyEntries(): List<BehaviorEntry> =
        entries.filter { it.tier == BehaviorTier.OBSERVATION_ONLY }

    /**
     * Returns all entries in [BehaviorTier.DEPRECATED_BUT_LIVE].
     *
     * These paths are deprecated but not yet removed.  They must not receive new
     * callers and must not activate under normal runtime conditions.
     */
    fun deprecatedButLiveEntries(): List<BehaviorEntry> =
        entries.filter { it.tier == BehaviorTier.DEPRECATED_BUT_LIVE }

    /**
     * Returns all entries in [BehaviorTier.BLOCKED_RETIRED].
     *
     * These paths are blocked by [AndroidCompatLegacyBlockingParticipant] and must
     * not reach V2 canonical surfaces.
     */
    fun blockedRetiredEntries(): List<BehaviorEntry> =
        entries.filter { it.tier == BehaviorTier.BLOCKED_RETIRED }

    /**
     * Returns `true` when the behavior with [behaviorId] is classified as
     * [BehaviorTier.CANONICAL_DEFAULT].
     *
     * Callers can use this as a lightweight gate to confirm that a path context is
     * on the authoritative participant route before allowing canonical state writes.
     *
     * @param behaviorId  The stable behavior identifier to look up.
     */
    fun isCanonicalDefault(behaviorId: String): Boolean =
        entries.any { it.behaviorId == behaviorId && it.tier == BehaviorTier.CANONICAL_DEFAULT }

    /**
     * Returns `true` when the behavior with [behaviorId] is classified in any
     * blocking or deprecation tier ([BehaviorTier.DEPRECATED_BUT_LIVE] or
     * [BehaviorTier.BLOCKED_RETIRED]).
     *
     * Callers can use this as a fast guard: if `isDeprecatedOrBlocked` returns `true`,
     * the path must not proceed under normal runtime conditions without an explicit
     * override.
     *
     * @param behaviorId  The stable behavior identifier to look up.
     */
    fun isDeprecatedOrBlocked(behaviorId: String): Boolean =
        entries.any {
            it.behaviorId == behaviorId &&
                (it.tier == BehaviorTier.DEPRECATED_BUT_LIVE || it.tier == BehaviorTier.BLOCKED_RETIRED)
        }

    /**
     * Returns all entries that have a [BehaviorEntry.deferralNote] — i.e., behaviors
     * where cleanup has been explicitly deferred to a later phase.
     *
     * This helper produces the "what remains deferred" portion of the reviewable artifact.
     */
    fun deferredEntries(): List<BehaviorEntry> =
        entries.filter { it.deferralNote != null }

    /**
     * Returns the [BehaviorEntry] with [behaviorId], or `null` if not found.
     *
     * @param behaviorId  The stable behavior identifier to look up.
     */
    fun forId(behaviorId: String): BehaviorEntry? =
        entries.firstOrNull { it.behaviorId == behaviorId }

    /**
     * Returns all entries whose [BehaviorEntry.signalSemantics] equals [semantics].
     *
     * V2 can use [SignalSemantics.CANONICAL_PARTICIPANT_EVIDENCE] to identify which
     * Android-originated signals may be applied directly to canonical participant truth.
     *
     * @param semantics  The [SignalSemantics] to filter by.
     */
    fun bySignalSemantics(semantics: SignalSemantics): List<BehaviorEntry> =
        entries.filter { it.signalSemantics == semantics }
}
