package com.ufo.galaxy.runtime

/**
 * PR-6Android (Android) — Android-side readiness evidence surface for release-gate and
 * governance review.
 *
 * [AndroidReadinessEvidenceSurface] is the **canonical readiness evidence audit object**
 * for the Android delegated runtime.  It answers the following questions for every
 * reviewer, release-gate, or governance step that needs to assess Android participant
 * readiness:
 *
 *  - What evidence sources exist on the Android side for each readiness dimension?
 *  - Which evidence is **canonical** (from the real runtime path, strong for release gating)?
 *  - Which evidence is **advisory** (observational / indirect, informative but not gating)?
 *  - Which evidence is **deprecated-compat** (from legacy paths; must NOT count as strong)?
 *  - Where are the concrete tests that validate each evidence entry?
 *  - How can V2 governance/release gates consume this evidence?
 *  - What Android-side readiness evidence work remains deferred?
 *
 * ## Background and motivation
 *
 * The Android repository has accumulated a significant body of runtime machinery —
 * delegated runtime lifecycle management, takeover execution handling, evaluator artifact
 * emission, truth reconciliation, continuity / offline durability hardening, and compat /
 * legacy suppression — across PRs 1 through PR-5Android.
 *
 * However, the evidence from all of these areas has remained scattered:
 *  - runtime lifecycle outcomes live in [DelegatedRuntimeReadinessEvaluator] and
 *    [DelegatedRuntimeAcceptanceEvaluator], each with their own test suites.
 *  - takeover execution outcomes live in [AndroidCompatLegacyBlockingParticipant] and
 *    [TakeoverFallbackEvent].
 *  - evaluator / reconciliation artifacts live in [DeviceReadinessArtifact],
 *    [DeviceAcceptanceArtifact], and [ReconciliationSignal].
 *  - continuity / recovery / offline durability evidence lives in
 *    [ContinuityRecoveryDurabilityContract], [AndroidRecoveryParticipationOwner], and
 *    [EmittedSignalLedger].
 *  - compat / legacy suppression evidence lives in [AndroidCompatLegacyBlockingParticipant],
 *    [CompatibilitySurfaceRetirementRegistry], and [AndroidAuthoritativePathAlignmentAudit].
 *
 * This leaves a major release-readiness problem: even if the runtime behavior is improving,
 * reviewers and later CI/governance steps cannot easily answer whether Android is actually
 * ready to be treated as a trustworthy participant by default.
 *
 * [AndroidReadinessEvidenceSurface] addresses this by providing:
 *  - A [EvidenceEntry] registry covering all readiness dimensions with confidence levels.
 *  - A [DeferredItem] registry making remaining gaps explicit with rationale.
 *  - Helper query methods for audit, test assertion, and governance consumption.
 *
 * ## Readiness dimensions
 *
 * Seven dimensions govern Android participant readiness for release/governance review:
 *
 * | Dimension                                     | Wire value                              | Primary evidence components                            |
 * |-----------------------------------------------|-----------------------------------------|--------------------------------------------------------|
 * | [ReadinessDimension.RUNTIME_LIFECYCLE]         | `runtime_lifecycle`                     | [DelegatedRuntimeReadinessEvaluator], [DelegatedRuntimeAcceptanceEvaluator] |
 * | [ReadinessDimension.TAKEOVER_EXECUTION]        | `takeover_execution`                    | [TakeoverFallbackEvent], [AndroidCompatLegacyBlockingParticipant] |
 * | [ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION] | `artifact_emission_reconciliation` | [DeviceReadinessArtifact], [DeviceAcceptanceArtifact], [ReconciliationSignal] |
 * | [ReadinessDimension.CONTINUITY_RECOVERY_SAFETY]| `continuity_recovery_safety`            | [ContinuityRecoveryDurabilityContract], [AndroidRecoveryParticipationOwner] |
 * | [ReadinessDimension.COMPATIBILITY_SUPPRESSION] | `compatibility_suppression`             | [AndroidCompatLegacyBlockingParticipant], [CompatibilitySurfaceRetirementRegistry] |
 * | [ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY] | `signal_replay_duplicate_safety`   | [EmittedSignalLedger], [AndroidContinuityIntegration]  |
 * | [ReadinessDimension.PARTICIPANT_LIFECYCLE_TRUTH] | `participant_lifecycle_truth`         | [ParticipantLifecycleTruthReport], [ParticipantLifecycleTruthReportBuilder] |
 *
 * ## Confidence levels
 *
 * Each evidence entry carries a [ConfidenceLevel] that indicates its strength as a
 * release / governance gate signal:
 *
 * | Level                                    | Meaning                                                                                 |
 * |------------------------------------------|-----------------------------------------------------------------------------------------|
 * | [ConfidenceLevel.CANONICAL]              | Evidence comes from the canonical runtime path; strong for release gating.              |
 * | [ConfidenceLevel.ADVISORY]               | Observational or indirect evidence; informative but not sufficient alone for gating.    |
 * | [ConfidenceLevel.DEPRECATED_COMPAT]      | Evidence from a legacy/compat path; must NOT count as strong readiness evidence.        |
 *
 * ## V2 authority boundary
 *
 * Android is a **participant**, not a release orchestrator.  V2 remains authoritative for:
 *  - Final release gate decisions.
 *  - Canonical truth convergence and participant state adjudication.
 *  - Session/task resumption and re-dispatch.
 *  - Governance and graduation policy.
 *
 * This surface provides the Android-side evidence that V2 release/governance gates consume;
 * it does not implement V2-side policy.
 *
 * @see DelegatedRuntimeReadinessEvaluator
 * @see DelegatedRuntimeAcceptanceEvaluator
 * @see ContinuityRecoveryDurabilityContract
 * @see AndroidCompatLegacyBlockingParticipant
 * @see AndroidAuthoritativePathAlignmentAudit
 * @see DeviceReadinessArtifact
 * @see DeviceAcceptanceArtifact
 */
object AndroidReadinessEvidenceSurface {

    // ── PR identifier ─────────────────────────────────────────────────────────

    /** The Android PR number that introduced this surface. */
    const val INTRODUCED_PR = 67

    /** Human-readable PR title. */
    const val INTRODUCED_PR_TITLE =
        "Make Android readiness evidence reviewable and release-gate friendly"

    // ── ReadinessDimension ────────────────────────────────────────────────────

    /**
     * The seven readiness dimensions covered by the Android evidence surface.
     *
     * @property wireValue Stable string identifier suitable for wire transmission and
     *                     audit log entries.
     */
    enum class ReadinessDimension(val wireValue: String) {

        /**
         * Runtime lifecycle correctness dimension.
         *
         * Covers whether the Android delegated runtime lifecycle is stable and
         * the device can answer the five readiness dimensions and six acceptance
         * dimensions evaluated by [DelegatedRuntimeReadinessEvaluator] and
         * [DelegatedRuntimeAcceptanceEvaluator].
         */
        RUNTIME_LIFECYCLE("runtime_lifecycle"),

        /**
         * Takeover execution behavior dimension.
         *
         * Covers whether Android takeover/fallback execution paths are correctly
         * bounded and do not bypass canonical flow controls.
         */
        TAKEOVER_EXECUTION("takeover_execution"),

        /**
         * Artifact emission and reconciliation dimension.
         *
         * Covers whether Android-side evaluator artifacts ([DeviceReadinessArtifact],
         * [DeviceAcceptanceArtifact]) and reconciliation signals ([ReconciliationSignal])
         * are emitted from real runtime paths in a form that V2 can consume.
         */
        ARTIFACT_EMISSION_RECONCILIATION("artifact_emission_reconciliation"),

        /**
         * Continuity and recovery safety dimension.
         *
         * Covers whether Android correctly bounds in-flight work after restart,
         * reconnect, or offline period, and whether stale/duplicate emissions are
         * suppressed after disruption.
         */
        CONTINUITY_RECOVERY_SAFETY("continuity_recovery_safety"),

        /**
         * Compatibility and legacy suppression dimension.
         *
         * Covers whether compat/legacy influence paths are classified, blocked, or
         * quarantined and do not corrupt canonical runtime state.
         */
        COMPATIBILITY_SUPPRESSION("compatibility_suppression"),

        /**
         * Signal replay and duplicate suppression safety dimension.
         *
         * Covers whether duplicate or stale signals are suppressed and the emission
         * ledger correctly bounds replay after terminal results.
         */
        SIGNAL_REPLAY_DUPLICATE_SAFETY("signal_replay_duplicate_safety"),

        /**
         * Participant lifecycle truth dimension (PR-69).
         *
         * Covers whether the Android participant's lifecycle state — from initial
         * registration through active, degraded, recovering, recovered,
         * re-registering, and capability re-aligned — is formally expressed as a
         * structured, machine-consumable truth surface that V2 and other cross-repo
         * systems can consume to make governance and acceptance decisions.
         *
         * Evidence in this dimension must distinguish:
         *  - reconnect from recovered (reconnect alone is NOT sufficient for recovery)
         *  - capability re-alignment intermediate states (ALIGNMENT_PENDING ≠ ALIGNED)
         *  - COMPLETE, COMPLETE_WITH_GAPS, STALE, INCOMPLETE, and INCONSISTENT evidence
         */
        PARTICIPANT_LIFECYCLE_TRUTH("participant_lifecycle_truth");

        companion object {
            /** Returns the dimension matching [wireValue], or `null` if not found. */
            fun fromValue(wireValue: String?): ReadinessDimension? =
                values().firstOrNull { it.wireValue == wireValue }
        }
    }

    // ── ConfidenceLevel ───────────────────────────────────────────────────────

    /**
     * The confidence level of an evidence entry; governs how strongly it counts toward
     * release and governance gate decisions.
     *
     * @property wireValue Stable string identifier.
     */
    enum class ConfidenceLevel(val wireValue: String) {

        /**
         * Evidence comes from the canonical runtime path.
         *
         * CANONICAL evidence is the primary signal for release gating and governance
         * decisions.  It is grounded in real runtime behavior (not test stubs or model
         * definitions) and validated by concrete tests.
         */
        CANONICAL("canonical"),

        /**
         * Observational or indirect evidence.
         *
         * ADVISORY evidence is informative and may support release confidence but is
         * not sufficient on its own to satisfy a dimension.  Reviewers should treat
         * it as corroborating context rather than a gating signal.
         */
        ADVISORY("advisory"),

        /**
         * Evidence from a legacy or compatibility path.
         *
         * DEPRECATED_COMPAT evidence must NOT count as strong readiness evidence.
         * It is retained for traceability and to confirm that the compat path is
         * correctly suppressed, but its presence does not advance readiness gating.
         */
        DEPRECATED_COMPAT("deprecated_compat");

        companion object {
            /** Returns the level matching [wireValue], or `null` if not found. */
            fun fromValue(wireValue: String?): ConfidenceLevel? =
                values().firstOrNull { it.wireValue == wireValue }
        }
    }

    // ── EvidenceEntry ─────────────────────────────────────────────────────────

    /**
     * A single Android-side readiness evidence entry.
     *
     * @property evidenceId         Stable identifier for this evidence entry, used in
     *                              test assertions and audit queries.
     * @property dimension          The [ReadinessDimension] this entry contributes to.
     * @property confidenceLevel    How strongly this entry counts toward release gating.
     * @property description        Human-readable description of what this evidence
     *                              demonstrates and how it is produced.
     * @property producedBy         Component(s) that produce this evidence at runtime.
     * @property testEvidence       Test class / method that grounds this evidence in real
     *                              runtime behavior.
     * @property v2ConsumptionPath  How V2 release/governance gates can consume or reference
     *                              this evidence.
     */
    data class EvidenceEntry(
        val evidenceId: String,
        val dimension: ReadinessDimension,
        val confidenceLevel: ConfidenceLevel,
        val description: String,
        val producedBy: String,
        val testEvidence: String,
        val v2ConsumptionPath: String
    )

    /**
     * All Android-side readiness evidence entries.
     *
     * A reviewer can use this list to determine:
     *  - which Android signals/tests/artifacts count as readiness evidence
     *  - which evidence is strong/canonical vs advisory/observational
     *  - where the main readiness dimensions are covered in the Android repository
     *  - how later governance/release gating work can consume this evidence
     */
    val allEvidence: List<EvidenceEntry> = listOf(

        // ── RUNTIME_LIFECYCLE ─────────────────────────────────────────────────

        EvidenceEntry(
            evidenceId = "readiness_evaluator_five_dimension_verdict",
            dimension = ReadinessDimension.RUNTIME_LIFECYCLE,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "DelegatedRuntimeReadinessEvaluator produces a DeviceReadinessArtifact " +
                "across five dimensions (continuity/replay/reconnect, local truth ownership, " +
                "result convergence, canonical execution event, compat/legacy blocking).  A " +
                "DeviceReadyForRelease outcome confirms all five dimensions are satisfied; any " +
                "gap produces a typed, actionable artifact that V2 can classify.",
            producedBy = "DelegatedRuntimeReadinessEvaluator",
            testEvidence = "Pr9DelegatedRuntimeReadinessTest: evaluateReadiness returns " +
                "DeviceReadyForRelease when all dimensions are READY; all gap-artifact subtypes " +
                "and their semanticTag wire values are validated",
            v2ConsumptionPath = "DeviceReadinessArtifact.semanticTag emitted via " +
                "ReconciliationSignal (Kind.PARTICIPANT_STATE) → V2 PR-9 release gate absorbs " +
                "via DelegatedRuntimeReadinessSnapshot.toWireMap()"
        ),

        EvidenceEntry(
            evidenceId = "acceptance_evaluator_six_dimension_graduation_verdict",
            dimension = ReadinessDimension.RUNTIME_LIFECYCLE,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "DelegatedRuntimeAcceptanceEvaluator produces a DeviceAcceptanceArtifact " +
                "across six dimensions (readiness prerequisite, continuity/replay/reconnect, truth " +
                "ownership/alignment, result convergence, canonical execution event, compat/legacy " +
                "blocking).  DeviceAcceptedForGraduation confirms all six are evidenced; any gap " +
                "produces a typed rejection artifact with the blocking dimension and reason.",
            producedBy = "DelegatedRuntimeAcceptanceEvaluator",
            testEvidence = "Pr10DelegatedRuntimeAcceptanceTest: evaluateAcceptance returns " +
                "DeviceAcceptedForGraduation when all dimensions are EVIDENCED; all rejection " +
                "subtypes and their semanticTag wire values are validated",
            v2ConsumptionPath = "DeviceAcceptanceArtifact.semanticTag emitted via " +
                "ReconciliationSignal → V2 PR-10 graduation gate absorbs via " +
                "DelegatedRuntimeAcceptanceSnapshot.toWireMap()"
        ),

        EvidenceEntry(
            evidenceId = "post_graduation_governance_evaluator_verdict",
            dimension = ReadinessDimension.RUNTIME_LIFECYCLE,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "DelegatedRuntimePostGraduationGovernanceEvaluator produces a " +
                "DeviceGovernanceArtifact tracking post-graduation governance health across " +
                "governance dimensions.  Provides ongoing evidence that a graduated device " +
                "remains in a valid governance state, not just at point-in-time release.",
            producedBy = "DelegatedRuntimePostGraduationGovernanceEvaluator",
            testEvidence = "Pr11DelegatedRuntimePostGraduationGovernanceTest: evaluateGovernance " +
                "verdict and DeviceGovernanceArtifact subtype wire values are validated",
            v2ConsumptionPath = "DeviceGovernanceArtifact emitted via ReconciliationSignal → " +
                "V2 PR-11 post-graduation governance gate absorbs via " +
                "DelegatedRuntimeGovernanceSnapshot.toWireMap()"
        ),

        EvidenceEntry(
            evidenceId = "strategy_evaluator_dispatch_verdict",
            dimension = ReadinessDimension.RUNTIME_LIFECYCLE,
            confidenceLevel = ConfidenceLevel.ADVISORY,
            description = "DelegatedRuntimeStrategyEvaluator produces a DeviceStrategyArtifact " +
                "reflecting the current dispatch strategy dimension statuses.  Advisory rather " +
                "than canonical because strategy is an input to runtime policy, not a direct " +
                "participant readiness outcome.",
            producedBy = "DelegatedRuntimeStrategyEvaluator",
            testEvidence = "Pr12DelegatedRuntimeStrategyTest: evaluateStrategy returns " +
                "DeviceStrategyArtifact with correct dimension statuses and wire values",
            v2ConsumptionPath = "DeviceStrategyArtifact emitted via ReconciliationSignal → " +
                "V2 strategy gate uses DelegatedRuntimeStrategySnapshot.toWireMap()"
        ),

        EvidenceEntry(
            evidenceId = "runtime_lifecycle_transition_event_emission",
            dimension = ReadinessDimension.RUNTIME_LIFECYCLE,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "RuntimeLifecycleTransitionEvent emissions from the delegated runtime " +
                "lifecycle track attach, detach, and recovery boundaries.  These events are " +
                "consumed by RuntimeController to update reconciliation and readiness signals.",
            producedBy = "RuntimeController, AndroidLifecycleRecoveryContract",
            testEvidence = "Pr37AndroidRuntimeLifecycleHardeningTest: lifecycle transition " +
                "events are emitted for each attach/detach boundary and consumed by " +
                "RuntimeController",
            v2ConsumptionPath = "RuntimeController.reconciliationSignals SharedFlow → " +
                "GalaxyConnectionService sends via WebSocket to V2"
        ),

        // ── TAKEOVER_EXECUTION ────────────────────────────────────────────────

        EvidenceEntry(
            evidenceId = "takeover_fallback_event_canonical_bounding",
            dimension = ReadinessDimension.TAKEOVER_EXECUTION,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "TakeoverFallbackEvent-driven execution paths are gated by " +
                "AndroidCompatLegacyBlockingParticipant before entering the canonical " +
                "execution pipeline.  A takeover that arrives on a non-canonical path is " +
                "blocked or quarantined, not silently processed.",
            producedBy = "AndroidCompatLegacyBlockingParticipant, TakeoverFallbackEvent",
            testEvidence = "Pr8AndroidCompatLegacyBlockingTest: takeover paths with " +
                "non-canonical influence classes produce BlockLegacyRuntimeTransition or " +
                "QuarantineAmbiguousLegacyState decisions",
            v2ConsumptionPath = "CompatLegacyBlockingDecision.semanticTag emitted as part of " +
                "ReconciliationSignal PARTICIPANT_STATE payload; V2 interprets blocking outcome"
        ),

        EvidenceEntry(
            evidenceId = "takeover_executor_metadata_unification",
            dimension = ReadinessDimension.TAKEOVER_EXECUTION,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "TakeoverEnvelope fields (source_dispatch_strategy, continuity_token, " +
                "recovery_context, is_resumable) are stable across the takeover executor path, " +
                "ensuring that V2 takeover metadata is faithfully received and propagated " +
                "through the Android execution pipeline.",
            producedBy = "TakeoverEnvelope, AutonomousExecutionPipeline",
            testEvidence = "Pr03TakeoverMetadataUnificationTest: continuity_token, " +
                "is_resumable, and recovery_context fields round-trip through the takeover " +
                "execution pipeline",
            v2ConsumptionPath = "TakeoverEnvelope wire fields consumed from V2 handoff; " +
                "continuity_token echoed back in DelegatedExecutionSignal RESULT payloads"
        ),

        EvidenceEntry(
            evidenceId = "takeover_recovery_path_compat_gate",
            dimension = ReadinessDimension.TAKEOVER_EXECUTION,
            confidenceLevel = ConfidenceLevel.ADVISORY,
            description = "TakeoverFallbackEvent-driven recovery currently relies on the general " +
                "AndroidCompatLegacyBlockingParticipant gate rather than a dedicated " +
                "post-reconnect authority check.  This is advisory evidence (not yet canonical " +
                "session-authority bounding) pending V2 takeover authority model stabilisation.",
            producedBy = "AndroidCompatLegacyBlockingParticipant",
            testEvidence = "Pr8AndroidCompatLegacyBlockingTest: general compat blocking is " +
                "exercised for takeover paths; dedicated takeover session-authority bounding " +
                "is noted as deferred",
            v2ConsumptionPath = "Deferred — see deferredItems: takeover_session_authority_bounding"
        ),

        // ── ARTIFACT_EMISSION_RECONCILIATION ──────────────────────────────────

        EvidenceEntry(
            evidenceId = "device_readiness_artifact_wire_emission",
            dimension = ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "DeviceReadinessArtifact subtypes produced by " +
                "DelegatedRuntimeReadinessEvaluator carry stable semanticTag wire values and " +
                "are emitted via ReconciliationSignal to V2.  The artifact represents the " +
                "canonical device-side readiness verdict for the V2 release gate.",
            producedBy = "DelegatedRuntimeReadinessEvaluator, DeviceReadinessArtifact",
            testEvidence = "Pr4AndroidEvaluatorArtifactEmissionTest: DeviceReadinessArtifact " +
                "subtype wire values are validated and emission path to ReconciliationSignal " +
                "is verified",
            v2ConsumptionPath = "ReconciliationSignal Kind.PARTICIPANT_STATE → V2 parses " +
                "DeviceReadinessArtifact.semanticTag from payload and routes to release gate"
        ),

        EvidenceEntry(
            evidenceId = "device_acceptance_artifact_wire_emission",
            dimension = ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "DeviceAcceptanceArtifact subtypes produced by " +
                "DelegatedRuntimeAcceptanceEvaluator carry stable semanticTag wire values and " +
                "are emitted via ReconciliationSignal to V2.  The artifact represents the " +
                "canonical device-side acceptance verdict for the V2 graduation gate.",
            producedBy = "DelegatedRuntimeAcceptanceEvaluator, DeviceAcceptanceArtifact",
            testEvidence = "Pr4AndroidEvaluatorArtifactEmissionTest: DeviceAcceptanceArtifact " +
                "subtype wire values are validated and emission path to ReconciliationSignal " +
                "is verified",
            v2ConsumptionPath = "ReconciliationSignal Kind.PARTICIPANT_STATE → V2 parses " +
                "DeviceAcceptanceArtifact.semanticTag from payload and routes to graduation gate"
        ),

        EvidenceEntry(
            evidenceId = "reconciliation_signal_participant_state_emission",
            dimension = ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "ReconciliationSignal with Kind.PARTICIPANT_STATE carries structured " +
                "participant state payload including health, readiness, and artifact fields.  " +
                "This is the primary emission path for Android readiness evidence to reach V2.",
            producedBy = "RuntimeController, ReconciliationSignal",
            testEvidence = "Pr52ReconciliationSignalEmissionTest: PARTICIPANT_STATE signal is " +
                "emitted from RuntimeController with all required fields; wire payload round-trip " +
                "is validated",
            v2ConsumptionPath = "ReconciliationSignal → GalaxyConnectionService → V2 WebSocket " +
                "ingestion → V2 participant state registry"
        ),

        EvidenceEntry(
            evidenceId = "reconciliation_signal_runtime_truth_snapshot_emission",
            dimension = ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "ReconciliationSignal with Kind.RUNTIME_TRUTH_SNAPSHOT carries the " +
                "current AndroidParticipantRuntimeTruth snapshot.  This provides V2 with a " +
                "structured view of Android's canonical truth state at reconciliation points.",
            producedBy = "RuntimeController, AndroidParticipantRuntimeTruth",
            testEvidence = "Pr51AndroidParticipantRuntimeTruthTest: runtime truth snapshot is " +
                "validated for field correctness; Pr52ReconciliationSignalEmissionTest: " +
                "RUNTIME_TRUTH_SNAPSHOT emission path is validated",
            v2ConsumptionPath = "ReconciliationSignal Kind.RUNTIME_TRUTH_SNAPSHOT → V2 truth " +
                "reconciliation layer absorbs via UnifiedTruthReconciliationSurface"
        ),

        EvidenceEntry(
            evidenceId = "unified_truth_reconciliation_surface_emission",
            dimension = ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "UnifiedTruthReconciliationSurface aggregates truth patch, truth " +
                "precedence, and reconciliation reduction outputs into a single auditable " +
                "surface.  Its output feeds the canonical truth snapshot emitted to V2.",
            producedBy = "UnifiedTruthReconciliationSurface, TruthReconciliationReducer",
            testEvidence = "Pr64UnifiedTruthReconciliationTest: reconciliation surface " +
                "aggregation and reduction outputs are validated",
            v2ConsumptionPath = "Truth patch output → AndroidParticipantRuntimeTruth → " +
                "ReconciliationSignal RUNTIME_TRUTH_SNAPSHOT → V2"
        ),

        // ── CONTINUITY_RECOVERY_SAFETY ────────────────────────────────────────

        EvidenceEntry(
            evidenceId = "recovery_participation_owner_restart_reconnect_bounding",
            dimension = ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "AndroidRecoveryParticipationOwner correctly bounds in-flight " +
                "participant/runtime work after process recreation and transport reconnect.  " +
                "Process recreation with prior context yields RehydrateThenContinue (Android " +
                "presents context to V2, does not self-authorize).  Process recreation without " +
                "context yields WaitForV2ReplayDecision (Android defers to V2 entirely).",
            producedBy = "AndroidRecoveryParticipationOwner",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: process recreation with " +
                "context → RehydrateThenContinue; process recreation without context → " +
                "WaitForV2ReplayDecision; transport reconnect → WaitForV2ReplayDecision",
            v2ConsumptionPath = "LocalRecoveryDecision.WaitForV2ReplayDecision.durableSessionId " +
                "is forwarded to V2 in the recovery handshake; V2 decides whether to replay, " +
                "resume, or start fresh"
        ),

        EvidenceEntry(
            evidenceId = "continuity_recovery_durability_contract_coverage",
            dimension = ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "ContinuityRecoveryDurabilityContract provides a machine-readable " +
                "validation matrix of 13 covered recovery behaviors and 6 bounded emission " +
                "rules, all proven by concrete tests.  This is the primary continuity evidence " +
                "document for the PR-5Android hardening work.",
            producedBy = "ContinuityRecoveryDurabilityContract",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: all 13 covered behaviors and " +
                "all 6 bounded emission rules are verified by concrete runtime tests",
            v2ConsumptionPath = "ContinuityRecoveryDurabilityContract.coveredBehaviors and " +
                ".boundedEmissions are queryable by V2 governance tooling via Android SDK; " +
                "INTRODUCED_PR=66 is a stable audit reference"
        ),

        EvidenceEntry(
            evidenceId = "hybrid_lifecycle_recovery_contract_coverage",
            dimension = ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "AndroidLifecycleRecoveryContract establishes the canonical Android " +
                "lifecycle recovery boundary contracts across hybrid runtime scenarios.  " +
                "Pr53AndroidLifecycleRecoveryHybridHardeningTest validates the recovery contract " +
                "under hybrid runtime conditions.",
            producedBy = "AndroidLifecycleRecoveryContract, HybridRuntimeContinuityContract",
            testEvidence = "Pr53AndroidLifecycleRecoveryHybridHardeningTest: hybrid recovery " +
                "contract enforcement and boundary conditions are validated",
            v2ConsumptionPath = "Recovery decision artifacts forwarded via ReconciliationSignal " +
                "to V2; V2 interprets recovery outcome and decides re-dispatch policy"
        ),

        EvidenceEntry(
            evidenceId = "durable_session_continuity_record_rehydration",
            dimension = ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "DelegatedFlowContinuityRecord persists the active flow execution " +
                "context for post-restart rehydration.  AndroidRecoveryParticipationOwner reads " +
                "this record to determine whether a RehydrateThenContinue decision is possible.",
            producedBy = "DelegatedFlowContinuityRecord, AndroidRecoveryParticipationOwner",
            testEvidence = "Pr4AndroidRecoveryParticipationOwnerTest: ContinuityResume scenario " +
                "with DelegatedFlowContinuityRecord yields RehydrateThenContinue with correct " +
                "localContext and checkpoint",
            v2ConsumptionPath = "RehydrateThenContinue decision presented to V2 via recovery " +
                "handshake; V2 confirms or overrides continuation based on canonical session state"
        ),

        // ── COMPATIBILITY_SUPPRESSION ─────────────────────────────────────────

        EvidenceEntry(
            evidenceId = "compat_legacy_blocking_participant_canonical_path_confirmation",
            dimension = ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "AndroidCompatLegacyBlockingParticipant classifies each path context " +
                "into one of five CompatLegacyInfluenceClasses and produces a typed " +
                "CompatLegacyBlockingDecision.  CANONICAL_RUNTIME_PATH_CONFIRMED produces " +
                "ConfirmCanonicalRuntimeTransition — the explicit positive evidence that the " +
                "path is not compat-influenced.",
            producedBy = "AndroidCompatLegacyBlockingParticipant",
            testEvidence = "Pr8AndroidCompatLegacyBlockingTest: CANONICAL_RUNTIME_PATH_CONFIRMED " +
                "input produces ConfirmCanonicalRuntimeTransition; all five blocking decision " +
                "subtypes and their semanticTag wire values are validated",
            v2ConsumptionPath = "CompatLegacyBlockingDecision.semanticTag in ReconciliationSignal " +
                "PARTICIPANT_STATE payload → V2 compat influence auditor"
        ),

        EvidenceEntry(
            evidenceId = "compatibility_surface_retirement_registry",
            dimension = ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "CompatibilitySurfaceRetirementRegistry records all Android compat " +
                "surfaces that have been retired, the PR in which retirement was declared, and " +
                "the justification.  This provides auditable evidence that deprecated surfaces " +
                "have been formally retired and are no longer active.",
            producedBy = "CompatibilitySurfaceRetirementRegistry",
            testEvidence = "Pr10CompatibilitySurfaceRetirementTest: retirement registry entries " +
                "are validated for correct PR attribution and retirement justification",
            v2ConsumptionPath = "Retirement registry is queryable by V2 governance tooling; " +
                "retired surface IDs can be cross-referenced against V2 compat influence audit"
        ),

        EvidenceEntry(
            evidenceId = "authoritative_path_alignment_audit",
            dimension = ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "AndroidAuthoritativePathAlignmentAudit documents the convergence " +
                "toward a single authoritative canonical path and the suppression of compat " +
                "influence in default behavior.  It maps each compat influence point to its " +
                "current classification (canonical / compat-allowed / deprecated-blocked).",
            producedBy = "AndroidAuthoritativePathAlignmentAudit",
            testEvidence = "Pr65AndroidAuthoritativePathAlignmentTest: all alignment audit " +
                "entries are validated for correct classification and non-blank justification",
            v2ConsumptionPath = "Audit entries exported via ReconciliationSignal compat-state " +
                "payload; V2 PR-3 single-path convergence gate absorbs compat classification"
        ),

        EvidenceEntry(
            evidenceId = "long_tail_compat_registry_legacy_signals",
            dimension = ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            confidenceLevel = ConfidenceLevel.DEPRECATED_COMPAT,
            description = "LongTailCompatibilityRegistry retains legacy compat signal " +
                "registrations for historical traceability.  These are explicitly " +
                "DEPRECATED_COMPAT — they should not count as strong readiness evidence " +
                "and must not influence canonical runtime state.",
            producedBy = "LongTailCompatibilityRegistry",
            testEvidence = "Pr35LongTailCompatHandlingTest: long-tail compat signals are " +
                "classified as advisory/deprecated and do not influence canonical outcomes",
            v2ConsumptionPath = "These signals are NOT forwarded as canonical readiness " +
                "evidence; V2 compat audit may reference them for suppression confirmation"
        ),

        EvidenceEntry(
            evidenceId = "compatibility_retirement_fence_blocking",
            dimension = ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "CompatibilityRetirementFence blocks execution through retired compat " +
                "surfaces at runtime, not just at configuration time.  This provides active " +
                "evidence that suppression is enforced in the execution path.",
            producedBy = "CompatibilityRetirementFence",
            testEvidence = "Pr36CompatRetirementHardeningTest: retirement fence blocks execution " +
                "through retired compat surfaces at the correct enforcement point",
            v2ConsumptionPath = "Fence block outcomes can be observed via ReconciliationSignal " +
                "compat-state fields; V2 PR-3 compat influence gate cross-references fence status"
        ),

        // ── SIGNAL_REPLAY_DUPLICATE_SAFETY ────────────────────────────────────

        EvidenceEntry(
            evidenceId = "emitted_signal_ledger_terminal_bounding",
            dimension = ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "EmittedSignalLedger.replayBounded() suppresses ACK and PROGRESS " +
                "signals after a terminal RESULT has been recorded, preventing stale pre-terminal " +
                "signal replay after execution has completed.  RESULT replay is idempotent " +
                "(original signalId preserved).",
            producedBy = "EmittedSignalLedger",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: replayBounded suppresses ACK " +
                "and PROGRESS after terminal RESULT; RESULT replay preserves signalId",
            v2ConsumptionPath = "Bounded signals are silently dropped before reaching the " +
                "DelegatedExecutionSignalSink transport layer; V2 never receives stale " +
                "ACK/PROGRESS after terminal RESULT"
        ),

        EvidenceEntry(
            evidenceId = "continuity_integration_duplicate_signal_suppression",
            dimension = ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "AndroidContinuityIntegration.suppressDuplicateLocalEmit() prevents " +
                "duplicate DelegatedExecutionSignal emission for a signalId already seen in the " +
                "current era.  This guards against duplicate delivery on reconnect or rebind.",
            producedBy = "AndroidContinuityIntegration",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: suppressDuplicateLocalEmit " +
                "returns false for unseen signalId, true after markLocalEmitSeen; " +
                "clearEmittedSignalsSeen resets the seen-set",
            v2ConsumptionPath = "Suppressed duplicates never enter the WebSocket send path; " +
                "V2 receives each signalId at most once per execution era"
        ),

        EvidenceEntry(
            evidenceId = "offline_queue_stale_session_discard",
            dimension = ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "OfflineTaskQueue.discardForDifferentSession() removes messages tagged " +
                "with a session that no longer matches the current authority tag before queue " +
                "drain, preventing late offline flush for an already-replaced session era.",
            producedBy = "OfflineTaskQueue",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: discardForDifferentSession " +
                "removes stale-tagged messages; preserves null-tagged and current-session messages",
            v2ConsumptionPath = "Discarded messages never reach V2; drain path delivers only " +
                "current-era task results to V2 offline processing"
        ),

        EvidenceEntry(
            evidenceId = "delegated_execution_signal_idempotency_guard",
            dimension = ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "DelegatedExecutionSignal carries a stable signalId that is " +
                "deduplicated end-to-end.  DelegatedExecutionSignalIdempotencyTest validates " +
                "that signalId is preserved across serialization and that duplicate-ID signals " +
                "are correctly identified.",
            producedBy = "DelegatedExecutionSignal, EmittedSignalLedger",
            testEvidence = "DelegatedExecutionSignalIdempotencyTest: signalId stability, " +
                "serialization round-trip, and duplicate detection are validated",
            v2ConsumptionPath = "V2 deduplicates inbound DelegatedExecutionSignal by signalId; " +
                "Android-side signalId is the primary deduplication key"
        ),

        // ── PARTICIPANT_LIFECYCLE_TRUTH ────────────────────────────────────────

        EvidenceEntry(
            evidenceId = "participant_lifecycle_truth_nine_state_model",
            dimension = ReadinessDimension.PARTICIPANT_LIFECYCLE_TRUTH,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "ParticipantLifecycleTruthState provides the nine-state lifecycle " +
                "truth enum (UNREGISTERED, REGISTERING, ACTIVE, DEGRADED, RECOVERING, " +
                "RECOVERED, UNAVAILABLE, RE_REGISTERING, CAPABILITY_RE_ALIGNED) with stable " +
                "wire values for V2 ingestion.  Extends FormalParticipantLifecycleState with " +
                "cross-repo registration and recovery truth states.  fromFormal() maps the " +
                "capability-gate-optimised five-state model to the truth model conservatively.  " +
                "capabilityAdvertisementAllowed() and isRecoveryPhase() provide correct " +
                "dispatch-gate logic: only ACTIVE and DEGRADED may advertise capabilities; " +
                "RECOVERING, RECOVERED, and RE_REGISTERING are recovery-phase states that " +
                "block dispatch.  Prohibits single-boolean health representation.",
            producedBy = "ParticipantLifecycleTruthState",
            testEvidence = "Pr69ParticipantLifecycleTruthReportTest: all nine wire values " +
                "are distinct and round-trip via fromWireValue; fromFormal maps each formal " +
                "state correctly; capabilityAdvertisementAllowed and isRecoveryPhase return " +
                "correct values for each of the nine states; ALL_WIRE_VALUES has exactly " +
                "nine entries",
            v2ConsumptionPath = "ParticipantLifecycleTruthReport.toWireMap() emits " +
                "lifecycle_truth_state as a stable wire key; V2 ingests via " +
                "ParticipantLifecycleTruthReport schema v1.0 to determine participant " +
                "lifecycle position before making acceptance or governance decisions"
        ),

        EvidenceEntry(
            evidenceId = "participant_lifecycle_truth_report_cross_repo_export",
            dimension = ReadinessDimension.PARTICIPANT_LIFECYCLE_TRUTH,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "ParticipantLifecycleTruthReport is the canonical structured " +
                "lifecycle truth surface that aggregates ParticipantLifecycleTruthState, " +
                "RegistrationTruthStatus, reconnectObserved, ReRegistrationOutcome, " +
                "CapabilityAlignmentStatus, recoveredButDegraded, partiallyAligned, and " +
                "LifecycleEvidenceCompleteness into a single, schema-versioned, V2-consumable " +
                "report.  toWireMap() (schema v1.0) produces a stable key→value map that " +
                "V2 can ingest.  isCrossRepoConsumable flags whether the report has " +
                "sufficient evidence for cross-repo acceptance decisions.  isFullyRecovered " +
                "requires reconnect observed AND re-registration completed AND capability " +
                "alignment achieved — reconnect alone is NOT sufficient.  " +
                "isRecoveredButDegraded captures the intermediate state where recovery " +
                "completed but runtime health remains impaired.",
            producedBy = "ParticipantLifecycleTruthReport, ParticipantLifecycleTruthReportBuilder",
            testEvidence = "Pr69ParticipantLifecycleTruthReportTest: all fields preserved; " +
                "isCrossRepoConsumable true for ACTIVE/REGISTERED/COMPLETE, false for " +
                "UNREGISTERED/INCOMPLETE/blank participantId; isFullyRecovered requires " +
                "reconnect AND COMPLETED re-registration AND aligned capability; " +
                "isRecoveredButDegraded true only for CAPABILITY_RE_ALIGNED/DEGRADED with " +
                "recoveredButDegraded flag; toWireMap schema_version is '1.0'; all KEY " +
                "constants are distinct",
            v2ConsumptionPath = "ParticipantLifecycleTruthReport.toWireMap() exported via " +
                "AndroidParticipantRuntimeTruth or ReconciliationSignal; V2 ingests via " +
                "lifecycle truth ingestion layer, checks isCrossRepoConsumable before use, " +
                "and classifies participant as ready/degraded/unavailable/recovering based " +
                "on lifecycleTruthState and capabilityAlignmentStatus wire values"
        ),

        EvidenceEntry(
            evidenceId = "participant_lifecycle_truth_report_builder_derivation",
            dimension = ReadinessDimension.PARTICIPANT_LIFECYCLE_TRUTH,
            confidenceLevel = ConfidenceLevel.CANONICAL,
            description = "ParticipantLifecycleTruthReportBuilder.build() derives the " +
                "correct ParticipantLifecycleTruthState from multi-dimensional runtime " +
                "context (FormalParticipantLifecycleState × ReconnectRecoveryState × " +
                "RegistrationTruthStatus × ReRegistrationOutcome × CapabilityAlignmentStatus) " +
                "using a six-priority derivation chain.  fromAuditSnapshot() bridges the " +
                "PR-68 AndroidDelegatedRuntimeAuditSnapshot to the PR-69 lifecycle truth " +
                "model, ensuring both audit and lifecycle surfaces are coherent.  " +
                "classifyEvidenceCompleteness() enforces STALE (older than 60 s), " +
                "INCOMPLETE (blank participantId), INCONSISTENT (ACTIVE without REGISTERED, " +
                "RECOVERED without reconnect, CAPABILITY_RE_ALIGNED without COMPLETED " +
                "re-registration), COMPLETE, and COMPLETE_WITH_GAPS evidence classifications.  " +
                "Reconnect alone (without completed re-registration AND capability alignment) " +
                "does NOT produce a COMPLETE or fully-recovered report.",
            producedBy = "ParticipantLifecycleTruthReportBuilder",
            testEvidence = "Pr69ParticipantLifecycleTruthReportTest: registration→active, " +
                "active→degraded, degraded→recovering→recovered, re-register/capability " +
                "re-alignment, unavailable/stale/incomplete/inconsistent evidence paths; " +
                "RECOVERED+PENDING→RE_REGISTERING; RECOVERED+COMPLETED+FULLY_ALIGNED→" +
                "CAPABILITY_RE_ALIGNED; RECOVERED+FAILED→UNAVAILABLE; fromAuditSnapshot " +
                "AUDITED→ACTIVE, DEGRADED health→DEGRADED, UNAVAILABLE registration→" +
                "UNREGISTERED, STALE freshness→STALE, UNKNOWN dimensions→INCOMPLETE; " +
                "STALE_THRESHOLD_MS is 60000",
            v2ConsumptionPath = "ParticipantLifecycleTruthReportBuilder.build() is the " +
                "primary production entry point on Android; fromAuditSnapshot() is the " +
                "bridge for V2 systems that already hold an AndroidDelegatedRuntimeAuditSnapshot; " +
                "the resulting report is exported via toWireMap() for V2 ingestion and " +
                "consumed by lifecycle truth ingestion layer to update participant state " +
                "in the V2 cross-repo acceptance verdict"
        )
    )

    // ── DeferredItem ──────────────────────────────────────────────────────────

    /**
     * An Android-side readiness evidence item that is explicitly deferred to a later PR.
     *
     * @property itemId          Stable identifier for the deferred item.
     * @property dimension       Which [ReadinessDimension] this item belongs to.
     * @property description     What has been deferred and why.
     * @property deferralReason  Rationale for deferring (e.g. "requires V2-side coordination").
     * @property deferredTo      Target phase or PR where this item is expected to land.
     */
    data class DeferredItem(
        val itemId: String,
        val dimension: ReadinessDimension,
        val description: String,
        val deferralReason: String,
        val deferredTo: String
    )

    /**
     * Android-side readiness evidence items explicitly deferred to later PRs.
     *
     * A reviewer can use this list to understand what remains deferred and why, so that
     * release gating decisions are not surprised by unaddressed gaps.
     */
    val deferredItems: List<DeferredItem> = listOf(

        DeferredItem(
            itemId = "takeover_session_authority_bounding",
            dimension = ReadinessDimension.TAKEOVER_EXECUTION,
            description = "TakeoverFallbackEvent-driven recovery paths do not yet have " +
                "explicit session-authority bounding equivalent to the main delegated " +
                "execution path.  Takeover recovery currently relies on the general " +
                "AndroidCompatLegacyBlockingParticipant gate rather than a dedicated " +
                "post-reconnect authority check keyed on V2 takeover session identifiers.",
            deferralReason = "Requires V2-side takeover authority model (V2 PR companion) to " +
                "be stable before Android can bind its bounding logic to explicit V2 takeover " +
                "session identifiers.  See ContinuityRecoveryDurabilityContract.deferredItems " +
                "entry takeover_recovery_path_explicit_bounding.",
            deferredTo = "V2 PR-2 companion work once V2 takeover authority surface is " +
                "published and Android can reference a stable takeover session wire contract"
        ),

        DeferredItem(
            itemId = "emit_ledger_cross_process_persistence",
            dimension = ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            description = "EmittedSignalLedger is in-memory only — seen-set and terminal " +
                "result state are lost on process recreation.  Replay bounding is therefore " +
                "only effective within a single process lifetime.",
            deferralReason = "Cross-process signal idempotency requires persisting the ledger " +
                "to disk alongside DelegatedFlowContinuityRecord, which requires store design " +
                "and V2-side duplicate-detection validation first.",
            deferredTo = "Post-release hardening PR after DelegatedFlowContinuityStore schema " +
                "stabilises and V2 confirms its own duplicate-detection policy"
        ),

        DeferredItem(
            itemId = "reconciliation_signal_epoch_bounding_after_reconnect",
            dimension = ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            description = "ReconciliationSignal emission after reconnect is not yet explicitly " +
                "gated on the new durable session epoch — a late reconciliation signal from a " +
                "prior epoch could theoretically reach V2 after a new session has started.",
            deferralReason = "Requires ReconciliationSignal to carry the current " +
                "sessionContinuityEpoch as a wire field and V2 to validate it on ingestion.  " +
                "Coordinate with V2 PR-5 wire contract before adding the epoch check on Android.",
            deferredTo = "V2 PR-5 continuity recovery PR, once epoch-stamped reconciliation " +
                "signals are part of the wire contract"
        ),

        DeferredItem(
            itemId = "instrumented_e2e_readiness_evidence_test",
            dimension = ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            description = "Full end-to-end instrumented test that injects a canonical execution " +
                "flow into the real Android runtime and confirms that readiness/acceptance " +
                "artifacts are emitted via GalaxyConnectionService → WebSocket → V2.  Currently " +
                "all evidence is validated in unit tests; the real service integration path is " +
                "not yet covered by automated CI.",
            deferralReason = "Requires Robolectric or instrumented test environment with a " +
                "test-double V2 WebSocket server.  Full integration is out of scope for the " +
                "readiness evidence reviewability PR.",
            deferredTo = "Later integration testing PR once the V2 test-double infrastructure " +
                "is available for Android instrumented tests"
        ),

        DeferredItem(
            itemId = "final_release_policy_in_android",
            dimension = ReadinessDimension.RUNTIME_LIFECYCLE,
            description = "Android does not implement final release policy — readiness and " +
                "acceptance evaluators produce evidence artifacts but do not autonomously " +
                "gate releases.  V2 remains the canonical orchestration authority for release " +
                "and graduation decisions.",
            deferralReason = "By design: V2 is the canonical orchestration authority.  Android " +
                "produces evidence; V2 consumes it and makes governance decisions.  This " +
                "deferred item is a deliberate boundary, not an omission.",
            deferredTo = "V2-side governance PRs (V2 PR-4 through V2 PR-6) implement the " +
                "final release policy that consumes Android evidence"
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /** Returns the [EvidenceEntry] with [evidenceId], or `null` if not found. */
    fun evidenceFor(evidenceId: String): EvidenceEntry? =
        allEvidence.firstOrNull { it.evidenceId == evidenceId }

    /** Returns all evidence entries for a given [dimension]. */
    fun evidenceForDimension(dimension: ReadinessDimension): List<EvidenceEntry> =
        allEvidence.filter { it.dimension == dimension }

    /** Returns all evidence entries with a given [confidenceLevel]. */
    fun evidenceAtLevel(confidenceLevel: ConfidenceLevel): List<EvidenceEntry> =
        allEvidence.filter { it.confidenceLevel == confidenceLevel }

    /** Returns the [DeferredItem] with [itemId], or `null` if not found. */
    fun deferredItemFor(itemId: String): DeferredItem? =
        deferredItems.firstOrNull { it.itemId == itemId }

    // ── Count constants for test assertions ───────────────────────────────────

    /** Expected total number of evidence entries at the time of this PR. */
    const val EVIDENCE_ENTRY_COUNT = 29

    /** Expected number of CANONICAL confidence-level evidence entries. */
    const val CANONICAL_EVIDENCE_COUNT = 26

    /** Expected number of ADVISORY confidence-level evidence entries. */
    const val ADVISORY_EVIDENCE_COUNT = 2

    /** Expected number of DEPRECATED_COMPAT confidence-level evidence entries. */
    const val DEPRECATED_COMPAT_EVIDENCE_COUNT = 1

    /** Expected number of deferred items at the time of this PR. */
    const val DEFERRED_ITEM_COUNT = 5

    // ── Description constant ──────────────────────────────────────────────────

    /**
     * Human-readable description of this evidence surface, suitable for wire metadata
     * or audit log entries.
     */
    const val DESCRIPTION =
        "Android-side readiness evidence surface: organises runtime lifecycle, takeover " +
            "execution, artifact emission/reconciliation, continuity/recovery, compat " +
            "suppression, and signal replay/duplicate safety evidence into a reviewable, " +
            "confidence-levelled audit object for V2 release-gate and governance consumption."
}
