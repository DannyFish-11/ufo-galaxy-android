package com.ufo.galaxy.runtime

/**
 * PR-7 (Android) — Align Android readiness evidence with the canonical distributed
 * release-gate skeleton.
 *
 * [AndroidDistributedGateAlignment] is the **canonical gate-category mapping object** for
 * the Android delegated runtime.  It answers the following questions for every reviewer,
 * V2 gate operator, or release-policy author that needs to understand how Android evidence
 * fits into the distributed gate model:
 *
 *  - Which V2 canonical gate category does each Android evidence entry feed into?
 *  - How strong is each piece of Android evidence within that gate category?
 *  - Is the evidence strong participant-runtime evidence, advisory-only, deprecated-compat,
 *    or intentionally deferred for later policy work?
 *  - Can V2 consume Android evidence consistently across all canonical gate dimensions?
 *
 * ## Background and motivation
 *
 * Android PR-6 ([AndroidReadinessEvidenceSurface]) organised Android-side readiness
 * evidence into six local dimensions (runtime_lifecycle, takeover_execution,
 * artifact_emission_reconciliation, continuity_recovery_safety, compatibility_suppression,
 * signal_replay_duplicate_safety) and gave every entry a confidence level (CANONICAL /
 * ADVISORY / DEPRECATED_COMPAT).
 *
 * However, before the V2 canonical distributed release-gate skeleton (V2 PR-7) can reliably
 * consume Android participant evidence, the mapping from Android dimensions → V2 canonical
 * gate categories must be explicit.  Without an explicit mapping, later V2 release-policy
 * or CI-gating work has to infer alignment, which introduces semantic drift and makes it
 * harder to reason about which gate a given Android artifact satisfies.
 *
 * [AndroidDistributedGateAlignment] closes this gap by providing:
 *  - A [CanonicalGateCategory] enum matching the V2 canonical gate skeleton categories.
 *  - An [EvidenceAuthority] enum capturing the strength and scope of each evidence entry
 *    in the distributed gate model.
 *  - A [GateMappingEntry] registry (one entry per [AndroidReadinessEvidenceSurface.EvidenceEntry])
 *    explicitly mapping each piece of evidence to its gate category and authority level.
 *  - A [dimensionToCategoryMap] showing the stable dimension → category alignment.
 *  - Helper query methods for audit, test assertion, and V2 gate consumption.
 *
 * ## Canonical gate categories
 *
 * The V2 canonical distributed gate skeleton defines the following gate categories.  Android
 * maps to each category as a participant, not as an authority:
 *
 * | Category                                              | Wire value                          | Android dimension source                                        |
 * |-------------------------------------------------------|-------------------------------------|-----------------------------------------------------------------|
 * | [CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS] | `lifecycle_runtime_correctness`     | [AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE] |
 * | [CanonicalGateCategory.TAKEOVER_EXECUTION_OUTCOMES]   | `takeover_execution_outcomes`       | [AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION] |
 * | [CanonicalGateCategory.RECONCILIATION_ARTIFACT_EMISSION] | `reconciliation_artifact_emission` | [AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION] |
 * | [CanonicalGateCategory.CONTINUITY_RECOVERY_SAFETY]    | `continuity_recovery_safety`        | [AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY] |
 * | [CanonicalGateCategory.COMPATIBILITY_LEGACY_SUPPRESSION] | `compatibility_legacy_suppression` | [AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION] |
 * | [CanonicalGateCategory.SIGNAL_REPLAY_DUPLICATE_SAFETY] | `signal_replay_duplicate_safety`   | [AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY] |
 *
 * ## Evidence authority in the distributed gate model
 *
 * Each [GateMappingEntry] carries an [EvidenceAuthority] that governs how V2 should weight
 * Android evidence when evaluating a gate dimension:
 *
 * | Authority                                                  | Wire value                     | Gate implication                                                            |
 * |------------------------------------------------------------|--------------------------------|-----------------------------------------------------------------------------|
 * | [EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME]             | `strong_participant_runtime`   | Grounded in real Android runtime; V2 gate should count it as primary input. |
 * | [EvidenceAuthority.ADVISORY_OBSERVATION_ONLY]              | `advisory_observation_only`    | Observational; V2 gate may use as corroborating signal, not sole gate input.|
 * | [EvidenceAuthority.DEPRECATED_COMPATIBILITY]               | `deprecated_compatibility`     | From a legacy path; must NOT count toward canonical gate satisfaction.       |
 * | [EvidenceAuthority.INTENTIONALLY_LOCAL_DEFERRED]           | `intentionally_local_deferred` | Deferred by design; not available for gate consumption yet.                  |
 *
 * ## V2 authority boundary
 *
 * Android is a **participant**, not a gate operator.  This object provides the participant-side
 * mapping so V2 gate logic can interpret Android evidence correctly.  V2 remains authoritative
 * for:
 *  - Final release gate decisions and dimension satisfaction thresholds.
 *  - Canonical truth convergence and participant state adjudication.
 *  - Session/task resumption and re-dispatch.
 *  - Graduation and governance policy.
 *
 * @see AndroidReadinessEvidenceSurface
 * @see DelegatedRuntimeReadinessEvaluator
 * @see DelegatedRuntimeAcceptanceEvaluator
 * @see ContinuityRecoveryDurabilityContract
 * @see AndroidCompatLegacyBlockingParticipant
 */
object AndroidDistributedGateAlignment {

    // ── PR identifier ─────────────────────────────────────────────────────────

    /** The Android PR number that introduced this alignment surface. */
    const val INTRODUCED_PR = 7

    /** Human-readable PR title. */
    const val INTRODUCED_PR_TITLE =
        "Reopen Android PR-7: Align Android readiness evidence with canonical distributed " +
            "release-gate skeleton"

    // ── CanonicalGateCategory ─────────────────────────────────────────────────

    /**
     * V2 canonical distributed gate categories that Android participant evidence maps into.
     *
     * These categories mirror the gate skeleton established in V2 PR-7.  Android maps its
     * readiness dimensions into these categories as a participant; V2 owns the canonical
     * definition and gate authority for each category.
     *
     * @property wireValue Stable string identifier matching the V2 canonical gate skeleton
     *                     wire representation.
     */
    enum class CanonicalGateCategory(val wireValue: String) {

        /**
         * Lifecycle / runtime correctness gate category.
         *
         * Covers whether the Android delegated runtime lifecycle is stable and produces
         * correctly-typed readiness/acceptance/governance verdicts that V2 release and
         * graduation gates can consume.
         *
         * Maps from: [AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE]
         */
        LIFECYCLE_RUNTIME_CORRECTNESS("lifecycle_runtime_correctness"),

        /**
         * Takeover execution outcomes gate category.
         *
         * Covers whether Android takeover and fallback execution paths are correctly bounded
         * by canonical flow controls and do not bypass compat/legacy blocking.
         *
         * Maps from: [AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION]
         */
        TAKEOVER_EXECUTION_OUTCOMES("takeover_execution_outcomes"),

        /**
         * Reconciliation / artifact emission gate category.
         *
         * Covers whether Android-side evaluator artifacts and reconciliation signals are
         * emitted from real runtime paths in a form that V2 can parse and classify.
         *
         * Maps from: [AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION]
         */
        RECONCILIATION_ARTIFACT_EMISSION("reconciliation_artifact_emission"),

        /**
         * Continuity / recovery safety gate category.
         *
         * Covers whether Android correctly bounds in-flight work after restart, reconnect,
         * or offline period, and whether stale/duplicate emissions are suppressed.
         *
         * Maps from: [AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY]
         */
        CONTINUITY_RECOVERY_SAFETY("continuity_recovery_safety"),

        /**
         * Compatibility / legacy suppression gate category.
         *
         * Covers whether compat/legacy influence paths are classified, blocked, or quarantined
         * and do not corrupt canonical runtime state or produce false-positive readiness signals.
         *
         * Maps from: [AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION]
         */
        COMPATIBILITY_LEGACY_SUPPRESSION("compatibility_legacy_suppression"),

        /**
         * Signal replay / duplicate safety gate category.
         *
         * Covers whether duplicate or stale signals are suppressed by the emission ledger and
         * that each logical signal is delivered to V2 at most once per execution era.
         *
         * Maps from: [AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY]
         */
        SIGNAL_REPLAY_DUPLICATE_SAFETY("signal_replay_duplicate_safety");

        companion object {
            /** Returns the category matching [wireValue], or `null` if not found. */
            fun fromValue(wireValue: String?): CanonicalGateCategory? =
                values().firstOrNull { it.wireValue == wireValue }
        }
    }

    // ── EvidenceAuthority ─────────────────────────────────────────────────────

    /**
     * The authority level of an Android evidence entry within the distributed gate model.
     *
     * Governs how V2 should weight the evidence when evaluating a canonical gate dimension.
     *
     * @property wireValue Stable string identifier.
     */
    enum class EvidenceAuthority(val wireValue: String) {

        /**
         * Evidence is grounded in real Android runtime behavior.
         *
         * V2 gate logic should treat this as the primary input for canonical gate dimension
         * satisfaction.  It is backed by concrete tests, typed artifacts, and real runtime
         * paths — not test stubs or model definitions.
         */
        STRONG_PARTICIPANT_RUNTIME("strong_participant_runtime"),

        /**
         * Evidence is observational or indirect.
         *
         * V2 gate logic may use this as a corroborating signal but should not use it as the
         * sole basis for satisfying a gate dimension.
         */
        ADVISORY_OBSERVATION_ONLY("advisory_observation_only"),

        /**
         * Evidence originates from a legacy or compatibility path.
         *
         * V2 gate logic must NOT count this toward canonical gate dimension satisfaction.
         * It is retained for traceability and suppression confirmation only.
         */
        DEPRECATED_COMPATIBILITY("deprecated_compatibility"),

        /**
         * Evidence is intentionally deferred to a later Android or V2 PR.
         *
         * V2 gate logic should treat this dimension contribution as pending; the absence of
         * this evidence is expected and documented, not a gap.
         */
        INTENTIONALLY_LOCAL_DEFERRED("intentionally_local_deferred");

        companion object {
            /** Returns the authority matching [wireValue], or `null` if not found. */
            fun fromValue(wireValue: String?): EvidenceAuthority? =
                values().firstOrNull { it.wireValue == wireValue }
        }
    }

    // ── GateMappingEntry ──────────────────────────────────────────────────────

    /**
     * A single entry in the Android → V2 canonical gate category mapping matrix.
     *
     * Each entry connects one [AndroidReadinessEvidenceSurface.EvidenceEntry] to the V2
     * canonical gate category it feeds into, together with the authority level and a note
     * explaining how V2 should interpret this evidence in the distributed gate context.
     *
     * @property evidenceId              Stable identifier matching an
     *                                   [AndroidReadinessEvidenceSurface.EvidenceEntry.evidenceId].
     * @property androidDimension        The Android-side readiness dimension this evidence
     *                                   belongs to (from [AndroidReadinessEvidenceSurface]).
     * @property canonicalGateCategory   The V2 canonical gate category this evidence maps to.
     * @property evidenceAuthority       How strongly this evidence counts in the distributed
     *                                   gate model.
     * @property gateMappingNote         Explanation of how V2 should interpret or consume this
     *                                   evidence within the canonical gate dimension.
     */
    data class GateMappingEntry(
        val evidenceId: String,
        val androidDimension: AndroidReadinessEvidenceSurface.ReadinessDimension,
        val canonicalGateCategory: CanonicalGateCategory,
        val evidenceAuthority: EvidenceAuthority,
        val gateMappingNote: String
    )

    // ── Dimension → Category mapping ──────────────────────────────────────────

    /**
     * Stable mapping from Android readiness dimensions to V2 canonical gate categories.
     *
     * This is the primary alignment surface.  Each Android dimension maps one-to-one to a
     * V2 canonical gate category; evidence within that dimension contributes to the
     * corresponding category in the distributed gate skeleton.
     */
    val dimensionToCategoryMap:
        Map<AndroidReadinessEvidenceSurface.ReadinessDimension, CanonicalGateCategory> = mapOf(
        AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE to
            CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
        AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION to
            CanonicalGateCategory.TAKEOVER_EXECUTION_OUTCOMES,
        AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION to
            CanonicalGateCategory.RECONCILIATION_ARTIFACT_EMISSION,
        AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY to
            CanonicalGateCategory.CONTINUITY_RECOVERY_SAFETY,
        AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION to
            CanonicalGateCategory.COMPATIBILITY_LEGACY_SUPPRESSION,
        AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY to
            CanonicalGateCategory.SIGNAL_REPLAY_DUPLICATE_SAFETY
    )

    // ── Gate mapping matrix ───────────────────────────────────────────────────

    /**
     * Full Android → V2 canonical gate category mapping matrix.
     *
     * One [GateMappingEntry] per [AndroidReadinessEvidenceSurface.allEvidence] entry.
     * A reviewer or V2 gate operator can use this matrix to determine:
     *  - which V2 canonical gate category a piece of Android evidence feeds into
     *  - how strongly it counts in that gate category
     *  - what note accompanies the mapping for V2 gate consumption
     */
    val gateMappings: List<GateMappingEntry> = listOf(

        // ── LIFECYCLE_RUNTIME_CORRECTNESS ─────────────────────────────────────

        GateMappingEntry(
            evidenceId = "readiness_evaluator_five_dimension_verdict",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            canonicalGateCategory = CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "DeviceReadinessArtifact.semanticTag is the primary Android " +
                "participant signal for the lifecycle/runtime correctness gate.  V2 PR-9 " +
                "release gate parses semanticTag from ReconciliationSignal PARTICIPANT_STATE " +
                "payload to decide if this Android device satisfies the gate dimension."
        ),

        GateMappingEntry(
            evidenceId = "acceptance_evaluator_six_dimension_graduation_verdict",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            canonicalGateCategory = CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "DeviceAcceptanceArtifact.semanticTag extends lifecycle correctness " +
                "evidence into the graduation phase.  V2 PR-10 graduation gate consumes this to " +
                "confirm the device has cleared all six acceptance dimensions before graduation."
        ),

        GateMappingEntry(
            evidenceId = "post_graduation_governance_evaluator_verdict",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            canonicalGateCategory = CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "DeviceGovernanceArtifact provides ongoing lifecycle correctness " +
                "evidence after graduation.  V2 PR-11 governance gate absorbs this to confirm " +
                "the device remains in a valid post-graduation governance state."
        ),

        GateMappingEntry(
            evidenceId = "strategy_evaluator_dispatch_verdict",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            canonicalGateCategory = CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            evidenceAuthority = EvidenceAuthority.ADVISORY_OBSERVATION_ONLY,
            gateMappingNote = "DeviceStrategyArtifact is advisory context for the lifecycle " +
                "correctness gate.  V2 strategy gate may use it for routing decisions but it " +
                "does not independently satisfy the lifecycle correctness gate dimension."
        ),

        GateMappingEntry(
            evidenceId = "runtime_lifecycle_transition_event_emission",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            canonicalGateCategory = CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "RuntimeLifecycleTransitionEvent emissions provide fine-grained " +
                "lifecycle attach/detach boundary evidence.  V2 can observe these via " +
                "ReconciliationSignal to confirm stable lifecycle transitions without needing " +
                "to poll Android state."
        ),

        // ── TAKEOVER_EXECUTION_OUTCOMES ───────────────────────────────────────

        GateMappingEntry(
            evidenceId = "takeover_fallback_event_canonical_bounding",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION,
            canonicalGateCategory = CanonicalGateCategory.TAKEOVER_EXECUTION_OUTCOMES,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "CompatLegacyBlockingDecision.semanticTag in ReconciliationSignal " +
                "PARTICIPANT_STATE payload is the primary Android takeover execution outcome for " +
                "the gate.  V2 compat influence auditor interprets the blocking outcome to " +
                "confirm that non-canonical takeover paths are bounded."
        ),

        GateMappingEntry(
            evidenceId = "takeover_executor_metadata_unification",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION,
            canonicalGateCategory = CanonicalGateCategory.TAKEOVER_EXECUTION_OUTCOMES,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "TakeoverEnvelope wire fields round-tripping through the Android " +
                "execution pipeline confirms that V2 takeover metadata (continuity_token, " +
                "is_resumable, recovery_context) is faithfully received and echoed back.  " +
                "V2 gate can verify metadata fidelity from DelegatedExecutionSignal RESULT payloads."
        ),

        GateMappingEntry(
            evidenceId = "takeover_recovery_path_compat_gate",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION,
            canonicalGateCategory = CanonicalGateCategory.TAKEOVER_EXECUTION_OUTCOMES,
            evidenceAuthority = EvidenceAuthority.ADVISORY_OBSERVATION_ONLY,
            gateMappingNote = "General compat gate coverage of takeover recovery is advisory.  " +
                "Dedicated session-authority bounding for takeover recovery is deferred pending " +
                "the V2 takeover authority model stabilising.  V2 gate should not rely on this " +
                "entry alone to satisfy the takeover execution outcomes dimension."
        ),

        // ── RECONCILIATION_ARTIFACT_EMISSION ──────────────────────────────────

        GateMappingEntry(
            evidenceId = "device_readiness_artifact_wire_emission",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            canonicalGateCategory = CanonicalGateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "DeviceReadinessArtifact subtypes with stable semanticTag wire " +
                "values are emitted via ReconciliationSignal Kind.PARTICIPANT_STATE.  V2 release " +
                "gate parses semanticTag to classify the Android readiness verdict without " +
                "needing to inspect internal Android state."
        ),

        GateMappingEntry(
            evidenceId = "device_acceptance_artifact_wire_emission",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            canonicalGateCategory = CanonicalGateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "DeviceAcceptanceArtifact subtypes are emitted via " +
                "ReconciliationSignal Kind.PARTICIPANT_STATE.  V2 graduation gate parses " +
                "semanticTag to classify the Android graduation verdict."
        ),

        GateMappingEntry(
            evidenceId = "reconciliation_signal_participant_state_emission",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            canonicalGateCategory = CanonicalGateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "ReconciliationSignal PARTICIPANT_STATE is the primary wire " +
                "emission path for all Android readiness artifacts to reach V2.  V2 participant " +
                "state registry ingests this via GalaxyConnectionService WebSocket, making it " +
                "the canonical artifact emission gate input."
        ),

        GateMappingEntry(
            evidenceId = "reconciliation_signal_runtime_truth_snapshot_emission",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            canonicalGateCategory = CanonicalGateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "ReconciliationSignal RUNTIME_TRUTH_SNAPSHOT provides V2 with a " +
                "structured canonical truth snapshot at reconciliation points.  V2 truth " +
                "reconciliation layer absorbs this via UnifiedTruthReconciliationSurface to " +
                "maintain canonical truth alignment."
        ),

        GateMappingEntry(
            evidenceId = "unified_truth_reconciliation_surface_emission",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            canonicalGateCategory = CanonicalGateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "UnifiedTruthReconciliationSurface aggregates truth patch, " +
                "precedence, and reduction outputs into a single auditable emission.  V2 gate " +
                "can confirm truth reconciliation correctness by inspecting the combined " +
                "RUNTIME_TRUTH_SNAPSHOT payload."
        ),

        // ── CONTINUITY_RECOVERY_SAFETY ────────────────────────────────────────

        GateMappingEntry(
            evidenceId = "recovery_participation_owner_restart_reconnect_bounding",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            canonicalGateCategory = CanonicalGateCategory.CONTINUITY_RECOVERY_SAFETY,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "AndroidRecoveryParticipationOwner bounds post-restart and " +
                "post-reconnect recovery decisions.  WaitForV2ReplayDecision.durableSessionId " +
                "is forwarded to V2 in the recovery handshake; V2 gate can confirm Android " +
                "defers to V2 rather than self-authorizing continuation."
        ),

        GateMappingEntry(
            evidenceId = "continuity_recovery_durability_contract_coverage",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            canonicalGateCategory = CanonicalGateCategory.CONTINUITY_RECOVERY_SAFETY,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "ContinuityRecoveryDurabilityContract.coveredBehaviors (13 " +
                "behaviors) and .boundedEmissions (6 rules) are queryable by V2 governance " +
                "tooling.  This provides the machine-readable continuity evidence matrix that " +
                "the continuity/recovery safety gate dimension requires."
        ),

        GateMappingEntry(
            evidenceId = "hybrid_lifecycle_recovery_contract_coverage",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            canonicalGateCategory = CanonicalGateCategory.CONTINUITY_RECOVERY_SAFETY,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "AndroidLifecycleRecoveryContract and HybridRuntimeContinuityContract " +
                "enforce canonical recovery boundaries under hybrid runtime scenarios.  V2 " +
                "re-dispatch policy can trust Android recovery decisions forwarded via " +
                "ReconciliationSignal to represent correctly-bounded hybrid recovery."
        ),

        GateMappingEntry(
            evidenceId = "durable_session_continuity_record_rehydration",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            canonicalGateCategory = CanonicalGateCategory.CONTINUITY_RECOVERY_SAFETY,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "DelegatedFlowContinuityRecord-backed RehydrateThenContinue " +
                "presents rehydrated session context to V2 via recovery handshake.  V2 gate " +
                "confirms or overrides based on canonical session state; Android does not " +
                "self-authorize continuation."
        ),

        // ── COMPATIBILITY_LEGACY_SUPPRESSION ──────────────────────────────────

        GateMappingEntry(
            evidenceId = "compat_legacy_blocking_participant_canonical_path_confirmation",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            canonicalGateCategory = CanonicalGateCategory.COMPATIBILITY_LEGACY_SUPPRESSION,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "CompatLegacyBlockingDecision.semanticTag in ReconciliationSignal " +
                "PARTICIPANT_STATE payload is the primary compat/legacy suppression gate input. " +
                "V2 compat influence auditor uses this to confirm whether the Android runtime " +
                "path is canonical or compat-influenced."
        ),

        GateMappingEntry(
            evidenceId = "compatibility_surface_retirement_registry",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            canonicalGateCategory = CanonicalGateCategory.COMPATIBILITY_LEGACY_SUPPRESSION,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "CompatibilitySurfaceRetirementRegistry is queryable by V2 " +
                "governance tooling; retired surface IDs can be cross-referenced against V2 " +
                "compat influence audit to confirm suppression completeness."
        ),

        GateMappingEntry(
            evidenceId = "authoritative_path_alignment_audit",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            canonicalGateCategory = CanonicalGateCategory.COMPATIBILITY_LEGACY_SUPPRESSION,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "AndroidAuthoritativePathAlignmentAudit entries exported via " +
                "ReconciliationSignal compat-state payload map each compat influence point to " +
                "its classification (canonical / compat-allowed / deprecated-blocked).  V2 " +
                "PR-3 single-path convergence gate absorbs this to confirm authoritative path " +
                "convergence is progressing on Android."
        ),

        GateMappingEntry(
            evidenceId = "long_tail_compat_registry_legacy_signals",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            canonicalGateCategory = CanonicalGateCategory.COMPATIBILITY_LEGACY_SUPPRESSION,
            evidenceAuthority = EvidenceAuthority.DEPRECATED_COMPATIBILITY,
            gateMappingNote = "LongTailCompatibilityRegistry legacy signals are DEPRECATED and " +
                "must NOT be counted toward gate dimension satisfaction.  V2 compat audit may " +
                "reference them for suppression confirmation only.  V2 gate logic must exclude " +
                "this entry from canonical dimension satisfaction checks."
        ),

        GateMappingEntry(
            evidenceId = "compatibility_retirement_fence_blocking",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            canonicalGateCategory = CanonicalGateCategory.COMPATIBILITY_LEGACY_SUPPRESSION,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "CompatibilityRetirementFence blocks execution through retired " +
                "compat surfaces at runtime.  Fence block outcomes observable via " +
                "ReconciliationSignal compat-state fields provide active suppression evidence " +
                "that V2 PR-3 compat influence gate can cross-reference."
        ),

        // ── SIGNAL_REPLAY_DUPLICATE_SAFETY ────────────────────────────────────

        GateMappingEntry(
            evidenceId = "emitted_signal_ledger_terminal_bounding",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            canonicalGateCategory = CanonicalGateCategory.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "EmittedSignalLedger.replayBounded() ensures V2 never receives " +
                "stale ACK/PROGRESS after a terminal RESULT within the same process lifetime.  " +
                "V2 gate can treat Android RESULT signals as terminal without needing to guard " +
                "against Android re-emitting earlier-phase signals after terminal emission."
        ),

        GateMappingEntry(
            evidenceId = "continuity_integration_duplicate_signal_suppression",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            canonicalGateCategory = CanonicalGateCategory.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "AndroidContinuityIntegration.suppressDuplicateLocalEmit() " +
                "prevents duplicate DelegatedExecutionSignal for a signalId already seen in " +
                "the current era.  V2 receives each signalId at most once per execution era; " +
                "gate logic can rely on signalId uniqueness within an era."
        ),

        GateMappingEntry(
            evidenceId = "offline_queue_stale_session_discard",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            canonicalGateCategory = CanonicalGateCategory.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "OfflineTaskQueue.discardForDifferentSession() removes stale-era " +
                "messages before queue drain, ensuring V2 only receives current-era results from " +
                "the offline flush path.  Gate logic can trust offline drain output as " +
                "current-era only."
        ),

        GateMappingEntry(
            evidenceId = "delegated_execution_signal_idempotency_guard",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            canonicalGateCategory = CanonicalGateCategory.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            evidenceAuthority = EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME,
            gateMappingNote = "DelegatedExecutionSignal.signalId is the stable end-to-end " +
                "deduplication key.  V2 deduplicates inbound signals by signalId; Android-side " +
                "idempotency guard and V2-side deduplication together close the duplicate " +
                "delivery gap in the distributed gate model."
        )
    )

    // ── Count constants for test assertions ───────────────────────────────────

    /** Expected total number of gate mapping entries at the time of this PR. */
    const val GATE_MAPPING_COUNT = 26

    /** Expected number of [EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME] mapping entries. */
    const val STRONG_PARTICIPANT_RUNTIME_COUNT = 23

    /** Expected number of [EvidenceAuthority.ADVISORY_OBSERVATION_ONLY] mapping entries. */
    const val ADVISORY_OBSERVATION_ONLY_COUNT = 2

    /** Expected number of [EvidenceAuthority.DEPRECATED_COMPATIBILITY] mapping entries. */
    const val DEPRECATED_COMPATIBILITY_COUNT = 1

    /** Expected number of [EvidenceAuthority.INTENTIONALLY_LOCAL_DEFERRED] mapping entries. */
    const val INTENTIONALLY_LOCAL_DEFERRED_COUNT = 0

    /** Expected number of canonical gate categories. */
    const val CANONICAL_GATE_CATEGORY_COUNT = 6

    // ── Query helpers ─────────────────────────────────────────────────────────

    /** Returns the [GateMappingEntry] for [evidenceId], or `null` if not found. */
    fun mappingFor(evidenceId: String): GateMappingEntry? =
        gateMappings.firstOrNull { it.evidenceId == evidenceId }

    /** Returns all [GateMappingEntry] entries for a given [category]. */
    fun mappingsForCategory(category: CanonicalGateCategory): List<GateMappingEntry> =
        gateMappings.filter { it.canonicalGateCategory == category }

    /** Returns all [GateMappingEntry] entries with a given [authority]. */
    fun mappingsForAuthority(authority: EvidenceAuthority): List<GateMappingEntry> =
        gateMappings.filter { it.evidenceAuthority == authority }

    /**
     * Returns the [CanonicalGateCategory] for [dimension], or `null` if not mapped.
     *
     * Convenience accessor for the [dimensionToCategoryMap].
     */
    fun categoryFor(
        dimension: AndroidReadinessEvidenceSurface.ReadinessDimension
    ): CanonicalGateCategory? = dimensionToCategoryMap[dimension]

    // ── Description constant ──────────────────────────────────────────────────

    /**
     * Human-readable description of this alignment surface, suitable for wire metadata
     * or audit log entries.
     */
    const val DESCRIPTION =
        "Android distributed gate alignment: maps Android readiness evidence dimensions to " +
            "V2 canonical distributed gate categories, classifying each evidence entry as " +
            "strong participant-runtime, advisory, deprecated-compat, or intentionally " +
            "deferred, so V2 gate logic can consume Android evidence consistently across " +
            "all canonical gate dimensions."
}
