package com.ufo.galaxy.runtime

/**
 * PR-7Android / PR-68 — Android-side alignment layer mapping readiness evidence
 * dimensions to the V2 canonical distributed release-gate skeleton.
 *
 * [AndroidDistributedGateCategoryMapping] is the companion alignment object that makes
 * Android readiness evidence easy to classify, consume, and compare within the V2
 * canonical distributed gate model introduced by V2 PR-7.
 *
 * ## Background and motivation
 *
 * [AndroidReadinessEvidenceSurface] (PR-6Android) organised Android readiness evidence
 * into six participant-side dimensions with typed confidence levels.  However, that
 * surface speaks in Android-internal terms ([AndroidReadinessEvidenceSurface.ReadinessDimension]).
 * The V2 canonical distributed release-gate skeleton uses its own canonical gate
 * categories to absorb participant evidence from multiple devices/runtimes.
 *
 * Before this mapping layer exists:
 *  - Reviewers must manually cross-reference Android dimensions against V2 gate categories
 *    to determine which Android evidence feeds which gate decision.
 *  - V2 governance tooling cannot programmatically determine which Android evidence
 *    qualifies as strong participant-runtime input to a given gate category.
 *  - The distinction between "strong participant-runtime evidence", "advisory evidence",
 *    and "deferred/local-only evidence" is not expressed in a form V2 can directly consume.
 *
 * [AndroidDistributedGateCategoryMapping] closes these gaps by providing:
 *  - A [V2GateCategory] enum mirroring the five canonical distributed gate categories
 *    established by V2 PR-7.
 *  - A [GateStrength] enum classifying how Android evidence contributes to each gate.
 *  - A [GateMappingEntry] registry mapping each Android evidence entry to its gate
 *    category and gate strength.
 *  - Query helpers for governance tooling to retrieve mappings by category, dimension,
 *    or strength.
 *
 * ## V2 gate categories vs Android dimensions
 *
 * The six Android [AndroidReadinessEvidenceSurface.ReadinessDimension] values map to
 * five V2 gate categories.  [SIGNAL_REPLAY_DUPLICATE_SAFETY] is absorbed into
 * [V2GateCategory.CONTINUITY_RECOVERY_SAFETY] in the V2 canonical model because
 * duplicate/replay suppression is part of the continuity safety guarantee.
 *
 * | Android dimension                             | V2 gate category                        |
 * |-----------------------------------------------|-----------------------------------------|
 * | RUNTIME_LIFECYCLE                             | LIFECYCLE_RUNTIME_CORRECTNESS           |
 * | TAKEOVER_EXECUTION                            | TAKEOVER_EXECUTION_OUTCOMES             |
 * | ARTIFACT_EMISSION_RECONCILIATION              | RECONCILIATION_ARTIFACT_EMISSION        |
 * | CONTINUITY_RECOVERY_SAFETY                    | CONTINUITY_RECOVERY_SAFETY              |
 * | COMPATIBILITY_SUPPRESSION                     | COMPATIBILITY_SUPPRESSION               |
 * | SIGNAL_REPLAY_DUPLICATE_SAFETY                | CONTINUITY_RECOVERY_SAFETY (absorbed)   |
 *
 * ## Evidence gate strength
 *
 * Each mapping entry carries a [GateStrength] indicating how the Android evidence
 * contributes to the V2 gate decision:
 *
 * | GateStrength                | Meaning                                                                                       |
 * |-----------------------------|-----------------------------------------------------------------------------------------------|
 * | [GateStrength.PARTICIPANT_RUNTIME] | Strong evidence from actual Android runtime execution; suitable for canonical gate consumption. |
 * | [GateStrength.ADVISORY]     | Informative or indirect evidence; supports gate confidence but not sufficient alone.          |
 * | [GateStrength.DEFERRED_LOCAL] | Intentionally local or deferred; must NOT be counted as strong gate input.                  |
 *
 * ## V2 authority boundary
 *
 * This mapping object does not implement Android-side gate authority.  V2 remains the
 * canonical orchestration authority for all release and graduation decisions.  Android
 * provides evidence; V2 consumes it via the gate category model described here.
 *
 * @see AndroidReadinessEvidenceSurface
 * @see DelegatedRuntimeReadinessEvaluator
 * @see DelegatedRuntimeAcceptanceEvaluator
 * @see ContinuityRecoveryDurabilityContract
 */
object AndroidDistributedGateCategoryMapping {

    // ── PR identifier ─────────────────────────────────────────────────────────

    /** The Android PR number that introduced this mapping surface. */
    const val INTRODUCED_PR = 68

    /** Human-readable PR title. */
    const val INTRODUCED_PR_TITLE =
        "Reopen Android PR-7: Align Android readiness evidence with canonical distributed " +
            "release-gate skeleton"

    /**
     * Human-readable description of this mapping surface, suitable for wire metadata
     * or audit log entries.
     */
    const val DESCRIPTION =
        "Android-side gate category mapping: aligns Android readiness evidence dimensions " +
            "with the V2 canonical distributed release-gate skeleton, classifies each evidence " +
            "entry as participant-runtime, advisory, or deferred/local, and exposes query " +
            "helpers for V2 governance tooling."

    // ── V2GateCategory ────────────────────────────────────────────────────────

    /**
     * The five canonical distributed gate categories from the V2 PR-7 release-gate skeleton.
     *
     * These categories are the canonical labels used by V2 to absorb participant evidence
     * across devices and runtimes.  Android evidence is mapped to these categories by this
     * object; the V2 gate skeleton is the authority for their definitions.
     *
     * @property wireValue Stable string identifier used in wire transmission and audit logs.
     */
    enum class V2GateCategory(val wireValue: String) {

        /**
         * Lifecycle and runtime correctness gate category.
         *
         * Absorbs evidence that the participant runtime lifecycle is stable and that
         * structured readiness / acceptance / governance verdicts can be produced and
         * consumed by V2 release and graduation gates.
         *
         * Android contributions: readiness evaluator, acceptance evaluator, post-graduation
         * governance evaluator, runtime lifecycle transition events.
         */
        LIFECYCLE_RUNTIME_CORRECTNESS("lifecycle_runtime_correctness"),

        /**
         * Takeover execution outcomes gate category.
         *
         * Absorbs evidence that participant takeover and fallback execution paths are
         * correctly bounded by canonical flow controls and that takeover metadata
         * round-trips faithfully.
         *
         * Android contributions: takeover fallback bounding, takeover metadata unification.
         */
        TAKEOVER_EXECUTION_OUTCOMES("takeover_execution_outcomes"),

        /**
         * Reconciliation artifact emission gate category.
         *
         * Absorbs evidence that participant-side evaluator artifacts and reconciliation
         * signals are emitted from real runtime paths in a V2-consumable form.
         *
         * Android contributions: device readiness artifact wire emission, device acceptance
         * artifact wire emission, reconciliation signal participant state / runtime truth
         * snapshot emission, unified truth reconciliation surface.
         */
        RECONCILIATION_ARTIFACT_EMISSION("reconciliation_artifact_emission"),

        /**
         * Continuity and recovery safety gate category.
         *
         * Absorbs evidence that the participant correctly bounds in-flight work after
         * disruption, that duplicate/stale signals are suppressed, and that recovery
         * handshake evidence is available for V2 replay decisions.
         *
         * Android contributions: recovery participation owner bounding, continuity
         * durability contract, hybrid lifecycle recovery, durable session rehydration,
         * signal replay ledger bounding, duplicate signal suppression, stale session
         * discard, execution signal idempotency.
         */
        CONTINUITY_RECOVERY_SAFETY("continuity_recovery_safety"),

        /**
         * Compatibility and legacy suppression gate category.
         *
         * Absorbs evidence that compat/legacy influence paths are classified, blocked,
         * or quarantined and do not corrupt canonical runtime state.
         *
         * Android contributions: compat legacy blocking canonical path confirmation,
         * compatibility surface retirement registry, authoritative path alignment audit,
         * compatibility retirement fence blocking.
         *
         * Note: [AndroidReadinessEvidenceSurface.ConfidenceLevel.DEPRECATED_COMPAT]
         * entries (e.g. long-tail compat registry) are mapped as [GateStrength.DEFERRED_LOCAL]
         * and must NOT count toward this gate.
         */
        COMPATIBILITY_SUPPRESSION("compatibility_suppression");

        companion object {
            /** Returns the category matching [wireValue], or `null` if not found. */
            fun fromValue(wireValue: String?): V2GateCategory? =
                values().firstOrNull { it.wireValue == wireValue }
        }
    }

    // ── GateStrength ──────────────────────────────────────────────────────────

    /**
     * Classification of how Android evidence contributes to a V2 gate decision.
     *
     * This classification is the Android-to-V2 gate projection of
     * [AndroidReadinessEvidenceSurface.ConfidenceLevel]:
     *
     * | Android ConfidenceLevel | GateStrength               | Rationale                                           |
     * |-------------------------|----------------------------|-----------------------------------------------------|
     * | CANONICAL               | PARTICIPANT_RUNTIME        | Evidence from the real Android runtime path.        |
     * | ADVISORY                | ADVISORY                   | Observational/indirect; informs but does not gate.  |
     * | DEPRECATED_COMPAT       | DEFERRED_LOCAL             | Must NOT count toward gate decisions.               |
     *
     * @property wireValue Stable string identifier.
     */
    enum class GateStrength(val wireValue: String) {

        /**
         * Strong evidence from actual Android runtime execution.
         *
         * PARTICIPANT_RUNTIME evidence is the primary input to the V2 canonical gate
         * category for this Android dimension.  V2 should treat this as reliable
         * participant input when making a gate decision for the participant (this device).
         */
        PARTICIPANT_RUNTIME("participant_runtime"),

        /**
         * Informative or indirect evidence.
         *
         * ADVISORY evidence supports gate confidence but is not sufficient on its own
         * to satisfy a gate dimension.  V2 should treat it as corroborating context.
         */
        ADVISORY("advisory"),

        /**
         * Intentionally local or deferred evidence.
         *
         * DEFERRED_LOCAL evidence must NOT be counted as strong gate input.  It is
         * retained for traceability — to confirm suppression or to indicate that work
         * is deferred to a later PR — but its presence does not advance gate readiness.
         */
        DEFERRED_LOCAL("deferred_local");

        companion object {
            /** Returns the strength matching [wireValue], or `null` if not found. */
            fun fromValue(wireValue: String?): GateStrength? =
                values().firstOrNull { it.wireValue == wireValue }
        }
    }

    // ── GateMappingEntry ──────────────────────────────────────────────────────

    /**
     * A single entry in the Android-to-V2-gate mapping table.
     *
     * Each entry declares that a specific Android evidence item ([evidenceId]) from a
     * specific Android readiness dimension ([androidDimension]) contributes to a specific
     * V2 gate category ([v2GateCategory]) with a declared gate strength ([gateStrength]).
     *
     * @property evidenceId        The [AndroidReadinessEvidenceSurface.EvidenceEntry.evidenceId]
     *                             this mapping entry refers to.
     * @property androidDimension  The Android readiness dimension this evidence belongs to.
     * @property v2GateCategory    The V2 canonical distributed gate category this evidence
     *                             maps into.
     * @property gateStrength      How strongly this evidence contributes to the V2 gate.
     * @property mappingNote       Human-readable note explaining the mapping decision and
     *                             any semantics the reviewer should understand.
     */
    data class GateMappingEntry(
        val evidenceId: String,
        val androidDimension: AndroidReadinessEvidenceSurface.ReadinessDimension,
        val v2GateCategory: V2GateCategory,
        val gateStrength: GateStrength,
        val mappingNote: String
    )

    /**
     * The full Android-to-V2-gate mapping table.
     *
     * Every evidence entry in [AndroidReadinessEvidenceSurface.allEvidence] has a
     * corresponding entry here declaring how it maps to the V2 canonical gate skeleton.
     *
     * Reviewers can use this table to determine:
     *  - Which Android evidence is gate-worthy (PARTICIPANT_RUNTIME) vs advisory vs
     *    deferred/local-only.
     *  - How each V2 gate category is covered by Android participant evidence.
     *  - Which gate categories lack strong Android participant evidence (advisory-only
     *    or deferred-only coverage).
     */
    val allMappings: List<GateMappingEntry> = listOf(

        // ── LIFECYCLE_RUNTIME_CORRECTNESS ──────────────────────────────────────

        GateMappingEntry(
            evidenceId = "readiness_evaluator_five_dimension_verdict",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            v2GateCategory = V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "DelegatedRuntimeReadinessEvaluator produces a typed " +
                "DeviceReadinessArtifact encoding all five readiness dimensions.  " +
                "DeviceReadyForRelease is the positive participant-runtime gate signal for " +
                "LIFECYCLE_RUNTIME_CORRECTNESS; any gap subtype is a typed rejection signal."
        ),

        GateMappingEntry(
            evidenceId = "acceptance_evaluator_six_dimension_graduation_verdict",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            v2GateCategory = V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "DelegatedRuntimeAcceptanceEvaluator produces a typed " +
                "DeviceAcceptanceArtifact encoding all six acceptance dimensions.  " +
                "DeviceAcceptedForGraduation extends LIFECYCLE_RUNTIME_CORRECTNESS beyond " +
                "initial release to graduation readiness."
        ),

        GateMappingEntry(
            evidenceId = "post_graduation_governance_evaluator_verdict",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            v2GateCategory = V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "DelegatedRuntimePostGraduationGovernanceEvaluator produces a typed " +
                "DeviceGovernanceArtifact.  This is ongoing LIFECYCLE_RUNTIME_CORRECTNESS " +
                "evidence from a graduated device — V2 PR-11 governance gate absorbs it."
        ),

        GateMappingEntry(
            evidenceId = "strategy_evaluator_dispatch_verdict",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            v2GateCategory = V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            gateStrength = GateStrength.ADVISORY,
            mappingNote = "DeviceStrategyArtifact is advisory: strategy is an input to runtime " +
                "policy, not a direct participant readiness outcome.  LIFECYCLE_RUNTIME_CORRECTNESS " +
                "does not depend on strategy being resolved; V2 may treat this as informative context."
        ),

        GateMappingEntry(
            evidenceId = "runtime_lifecycle_transition_event_emission",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            v2GateCategory = V2GateCategory.LIFECYCLE_RUNTIME_CORRECTNESS,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "RuntimeLifecycleTransitionEvent emissions bound attach/detach/recovery " +
                "transitions and drive RuntimeController reconciliation updates.  These are " +
                "participant-runtime signals for LIFECYCLE_RUNTIME_CORRECTNESS."
        ),

        // ── TAKEOVER_EXECUTION_OUTCOMES ────────────────────────────────────────

        GateMappingEntry(
            evidenceId = "takeover_fallback_event_canonical_bounding",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION,
            v2GateCategory = V2GateCategory.TAKEOVER_EXECUTION_OUTCOMES,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "AndroidCompatLegacyBlockingParticipant gates every TakeoverFallbackEvent " +
                "path before canonical pipeline entry.  ConfirmCanonicalRuntimeTransition is the " +
                "positive TAKEOVER_EXECUTION_OUTCOMES signal; blocking/quarantine decisions are " +
                "typed rejection signals."
        ),

        GateMappingEntry(
            evidenceId = "takeover_executor_metadata_unification",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION,
            v2GateCategory = V2GateCategory.TAKEOVER_EXECUTION_OUTCOMES,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "TakeoverEnvelope wire fields (continuity_token, is_resumable, " +
                "recovery_context) round-trip faithfully through the Android execution pipeline.  " +
                "This is participant-runtime evidence that V2 takeover metadata arrives intact."
        ),

        GateMappingEntry(
            evidenceId = "takeover_recovery_path_compat_gate",
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION,
            v2GateCategory = V2GateCategory.TAKEOVER_EXECUTION_OUTCOMES,
            gateStrength = GateStrength.ADVISORY,
            mappingNote = "Takeover recovery currently relies on the general compat gate rather " +
                "than dedicated session-authority bounding.  This is advisory for " +
                "TAKEOVER_EXECUTION_OUTCOMES; explicit session-authority bounding is deferred " +
                "pending V2 takeover authority model stabilisation."
        ),

        // ── RECONCILIATION_ARTIFACT_EMISSION ──────────────────────────────────

        GateMappingEntry(
            evidenceId = "device_readiness_artifact_wire_emission",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            v2GateCategory = V2GateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "DeviceReadinessArtifact subtypes carry stable semanticTag wire values " +
                "and are emitted via ReconciliationSignal to V2.  This is the primary " +
                "RECONCILIATION_ARTIFACT_EMISSION signal for the V2 release gate."
        ),

        GateMappingEntry(
            evidenceId = "device_acceptance_artifact_wire_emission",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            v2GateCategory = V2GateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "DeviceAcceptanceArtifact subtypes carry stable semanticTag wire values " +
                "and extend RECONCILIATION_ARTIFACT_EMISSION to the graduation gate."
        ),

        GateMappingEntry(
            evidenceId = "reconciliation_signal_participant_state_emission",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            v2GateCategory = V2GateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "ReconciliationSignal Kind.PARTICIPANT_STATE is the primary emission " +
                "path for Android readiness evidence to V2.  RECONCILIATION_ARTIFACT_EMISSION " +
                "depends on this path being functional and validated."
        ),

        GateMappingEntry(
            evidenceId = "reconciliation_signal_runtime_truth_snapshot_emission",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            v2GateCategory = V2GateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "ReconciliationSignal Kind.RUNTIME_TRUTH_SNAPSHOT provides V2 with a " +
                "structured view of Android canonical truth state.  Part of the full " +
                "RECONCILIATION_ARTIFACT_EMISSION picture for V2 truth reconciliation."
        ),

        GateMappingEntry(
            evidenceId = "unified_truth_reconciliation_surface_emission",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            v2GateCategory = V2GateCategory.RECONCILIATION_ARTIFACT_EMISSION,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "UnifiedTruthReconciliationSurface aggregates truth patch and reduction " +
                "outputs into the canonical truth snapshot emitted to V2.  It is participant-runtime " +
                "evidence for the RECONCILIATION_ARTIFACT_EMISSION gate."
        ),

        // ── CONTINUITY_RECOVERY_SAFETY (from CONTINUITY_RECOVERY_SAFETY dimension) ──

        GateMappingEntry(
            evidenceId = "recovery_participation_owner_restart_reconnect_bounding",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            v2GateCategory = V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "AndroidRecoveryParticipationOwner correctly produces typed recovery " +
                "decisions (RehydrateThenContinue, WaitForV2ReplayDecision) for all disruption " +
                "scenarios.  Strong CONTINUITY_RECOVERY_SAFETY signal for V2 recovery handshake."
        ),

        GateMappingEntry(
            evidenceId = "continuity_recovery_durability_contract_coverage",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            v2GateCategory = V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "ContinuityRecoveryDurabilityContract machine-readable matrix (13 " +
                "covered behaviors, 6 bounded emission rules) is the primary structured evidence " +
                "document for CONTINUITY_RECOVERY_SAFETY; queryable by V2 governance tooling."
        ),

        GateMappingEntry(
            evidenceId = "hybrid_lifecycle_recovery_contract_coverage",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            v2GateCategory = V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "AndroidLifecycleRecoveryContract covers hybrid runtime recovery " +
                "boundary conditions.  Participant-runtime evidence for CONTINUITY_RECOVERY_SAFETY " +
                "under hybrid scenarios."
        ),

        GateMappingEntry(
            evidenceId = "durable_session_continuity_record_rehydration",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            v2GateCategory = V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "DelegatedFlowContinuityRecord enables RehydrateThenContinue decisions " +
                "after process recreation.  Android presents context to V2 and does not " +
                "self-authorize; this is the correct CONTINUITY_RECOVERY_SAFETY authority model."
        ),

        // ── CONTINUITY_RECOVERY_SAFETY (from SIGNAL_REPLAY_DUPLICATE_SAFETY dimension) ──
        //
        // Android's SIGNAL_REPLAY_DUPLICATE_SAFETY dimension is absorbed into the V2
        // CONTINUITY_RECOVERY_SAFETY gate category because duplicate/replay suppression
        // is part of the continuity safety guarantee in the V2 canonical model.

        GateMappingEntry(
            evidenceId = "emitted_signal_ledger_terminal_bounding",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            v2GateCategory = V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "EmittedSignalLedger.replayBounded() suppresses stale ACK/PROGRESS " +
                "after terminal RESULT.  In the V2 gate model this is CONTINUITY_RECOVERY_SAFETY " +
                "evidence because replay bounding is part of the continuity contract."
        ),

        GateMappingEntry(
            evidenceId = "continuity_integration_duplicate_signal_suppression",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            v2GateCategory = V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "AndroidContinuityIntegration.suppressDuplicateLocalEmit() prevents " +
                "duplicate delivery per era.  Maps to CONTINUITY_RECOVERY_SAFETY in V2's gate " +
                "model as part of the continuity safety guarantee."
        ),

        GateMappingEntry(
            evidenceId = "offline_queue_stale_session_discard",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            v2GateCategory = V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "OfflineTaskQueue.discardForDifferentSession() prevents stale offline " +
                "flush for a replaced session era.  Maps to CONTINUITY_RECOVERY_SAFETY: session " +
                "era safety is part of the continuity contract."
        ),

        GateMappingEntry(
            evidenceId = "delegated_execution_signal_idempotency_guard",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            v2GateCategory = V2GateCategory.CONTINUITY_RECOVERY_SAFETY,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "DelegatedExecutionSignal signalId deduplication is end-to-end " +
                "idempotency evidence.  V2 relies on signalId for its own deduplication; Android-" +
                "side signalId stability is CONTINUITY_RECOVERY_SAFETY evidence."
        ),

        // ── COMPATIBILITY_SUPPRESSION ──────────────────────────────────────────

        GateMappingEntry(
            evidenceId = "compat_legacy_blocking_participant_canonical_path_confirmation",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            v2GateCategory = V2GateCategory.COMPATIBILITY_SUPPRESSION,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "AndroidCompatLegacyBlockingParticipant produces typed " +
                "CompatLegacyBlockingDecision for all path contexts.  " +
                "ConfirmCanonicalRuntimeTransition is the positive COMPATIBILITY_SUPPRESSION " +
                "signal; blocking/quarantine decisions are typed rejection signals for V2."
        ),

        GateMappingEntry(
            evidenceId = "compatibility_surface_retirement_registry",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            v2GateCategory = V2GateCategory.COMPATIBILITY_SUPPRESSION,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "CompatibilitySurfaceRetirementRegistry provides an auditable record of " +
                "retired compat surfaces.  Queryable by V2 governance tooling for cross-reference " +
                "against V2 compat influence audit."
        ),

        GateMappingEntry(
            evidenceId = "authoritative_path_alignment_audit",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            v2GateCategory = V2GateCategory.COMPATIBILITY_SUPPRESSION,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "AndroidAuthoritativePathAlignmentAudit maps each compat influence " +
                "point to canonical/compat-allowed/deprecated-blocked classification.  " +
                "Exported via ReconciliationSignal compat-state payload for V2 PR-3 gate."
        ),

        GateMappingEntry(
            evidenceId = "long_tail_compat_registry_legacy_signals",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            v2GateCategory = V2GateCategory.COMPATIBILITY_SUPPRESSION,
            gateStrength = GateStrength.DEFERRED_LOCAL,
            mappingNote = "LongTailCompatibilityRegistry is DEPRECATED_COMPAT in Android terms " +
                "and DEFERRED_LOCAL in gate terms.  These legacy signals are retained for " +
                "traceability only and must NOT count toward COMPATIBILITY_SUPPRESSION gate " +
                "readiness.  V2 compat audit may reference them for suppression confirmation."
        ),

        GateMappingEntry(
            evidenceId = "compatibility_retirement_fence_blocking",
            androidDimension =
                AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            v2GateCategory = V2GateCategory.COMPATIBILITY_SUPPRESSION,
            gateStrength = GateStrength.PARTICIPANT_RUNTIME,
            mappingNote = "CompatibilityRetirementFence actively blocks execution through retired " +
                "compat surfaces at runtime.  This is participant-runtime evidence that " +
                "COMPATIBILITY_SUPPRESSION is enforced in the execution path, not just declared."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /** Returns the [GateMappingEntry] for [evidenceId], or `null` if not found. */
    fun mappingFor(evidenceId: String): GateMappingEntry? =
        allMappings.firstOrNull { it.evidenceId == evidenceId }

    /** Returns all mapping entries for a given V2 [gateCategory]. */
    fun mappingsForGateCategory(gateCategory: V2GateCategory): List<GateMappingEntry> =
        allMappings.filter { it.v2GateCategory == gateCategory }

    /** Returns all mapping entries for a given Android [dimension]. */
    fun mappingsForDimension(
        dimension: AndroidReadinessEvidenceSurface.ReadinessDimension
    ): List<GateMappingEntry> =
        allMappings.filter { it.androidDimension == dimension }

    /**
     * Returns all mapping entries with a given [gateStrength].
     *
     * Use this to retrieve all [GateStrength.PARTICIPANT_RUNTIME] entries for a gate
     * category to determine the strong participant-runtime inputs that V2 should consider.
     */
    fun mappingsAtStrength(gateStrength: GateStrength): List<GateMappingEntry> =
        allMappings.filter { it.gateStrength == gateStrength }

    /**
     * Returns the set of V2 gate categories covered by a given Android [dimension].
     *
     * Most dimensions map to a single gate category; [SIGNAL_REPLAY_DUPLICATE_SAFETY]
     * maps to [V2GateCategory.CONTINUITY_RECOVERY_SAFETY] in the V2 canonical model.
     */
    fun gateCategoriesForDimension(
        dimension: AndroidReadinessEvidenceSurface.ReadinessDimension
    ): Set<V2GateCategory> =
        mappingsForDimension(dimension).map { it.v2GateCategory }.toSet()

    /**
     * Returns all [GateStrength.PARTICIPANT_RUNTIME] mapping entries for a gate category.
     *
     * This is the primary query for V2 governance tooling to determine which Android
     * evidence is strong gate input for a given category.
     */
    fun participantRuntimeMappingsFor(gateCategory: V2GateCategory): List<GateMappingEntry> =
        mappingsForGateCategory(gateCategory).filter {
            it.gateStrength == GateStrength.PARTICIPANT_RUNTIME
        }

    // ── Count constants for test assertions ───────────────────────────────────

    /** Expected total number of mapping entries at the time of this PR. */
    const val MAPPING_ENTRY_COUNT = 26

    /** Expected number of PARTICIPANT_RUNTIME gate strength entries. */
    const val PARTICIPANT_RUNTIME_ENTRY_COUNT = 23

    /** Expected number of ADVISORY gate strength entries. */
    const val ADVISORY_ENTRY_COUNT = 2

    /** Expected number of DEFERRED_LOCAL gate strength entries. */
    const val DEFERRED_LOCAL_ENTRY_COUNT = 1
}
