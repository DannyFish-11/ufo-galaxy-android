package com.ufo.galaxy.runtime

/**
 * PR-7Android (reopened) — Android-side alignment mapping for the V2 canonical
 * distributed release-gate skeleton.
 *
 * [AndroidDistributedGateMapping] provides the minimum safe Android-side structure so
 * that Android readiness evidence produced by [AndroidReadinessEvidenceSurface] can map
 * cleanly into the V2 canonical distributed gate skeleton established by V2 PR-7.
 *
 * This object does **not** implement release policy or grant Android any independent gate
 * authority.  V2 remains the canonical orchestration authority.  This object only makes
 * the Android-to-V2 evidence mapping explicit, reviewable, and programmatically queryable.
 *
 * ## Background and motivation
 *
 * Android PR-6 ([AndroidReadinessEvidenceSurface]) organised Android evidence into six
 * internal readiness dimensions and made each evidence entry confidence-levelled and
 * V2-consumable.  However, it did not explicitly map these internal Android dimensions to
 * the V2 canonical distributed gate dimensions that V2 PR-7 establishes.
 *
 * Without an explicit mapping:
 *  - reviewers cannot easily verify that every V2 gate dimension has corresponding Android
 *    evidence;
 *  - governance tooling cannot programmatically determine which Android evidence is
 *    relevant to a given V2 gate check;
 *  - the distinction between strong participant-runtime evidence, advisory/observational
 *    evidence, deprecated-compat evidence, and intentionally-local/deferred evidence is
 *    implicit rather than declared.
 *
 * [AndroidDistributedGateMapping] closes these gaps by providing:
 *  - A [GateDimension] enum declaring the V2 canonical distributed gate dimensions with
 *    stable wire values.
 *  - An [EvidenceAlignment] enum replacing implicit confidence-level inference with
 *    explicit alignment strength declarations.
 *  - A [GateMappingEntry] registry mapping every Android evidence category to its
 *    corresponding V2 gate dimension and alignment strength.
 *  - Helper query methods for audit, governance tooling, and later CI consumption.
 *
 * ## V2 canonical distributed gate dimensions
 *
 * The six dimensions correspond to the canonical gate categories established in V2 PR-7:
 *
 * | Dimension                                           | Wire value                           | Description                                        |
 * |-----------------------------------------------------|--------------------------------------|----------------------------------------------------|
 * | [GateDimension.LIFECYCLE_RUNTIME_CORRECTNESS]       | `lifecycle_runtime_correctness`      | Runtime lifecycle stability and evaluator chains   |
 * | [GateDimension.TAKEOVER_EXECUTION_OUTCOMES]         | `takeover_execution_outcomes`        | Takeover/fallback path bounding and metadata       |
 * | [GateDimension.PARTICIPANT_TRUTH_RECONCILIATION]    | `participant_truth_reconciliation`   | Truth reconciliation and participant state signals |
 * | [GateDimension.EVALUATOR_ARTIFACT_EMISSION]         | `evaluator_artifact_emission`        | Structured evaluator artifacts emitted to V2       |
 * | [GateDimension.CONTINUITY_RECOVERY_SAFETY]          | `continuity_recovery_safety`         | Recovery bounding, signal dedup, replay safety     |
 * | [GateDimension.COMPATIBILITY_LEGACY_SUPPRESSION]    | `compatibility_legacy_suppression`   | Compat/legacy path suppression evidence            |
 *
 * ## Evidence alignment model
 *
 * Each [GateMappingEntry] carries an [EvidenceAlignment] that replaces the implicit
 * confidence-level model from [AndroidReadinessEvidenceSurface.ConfidenceLevel] with an
 * explicit gate-consumption alignment declaration:
 *
 * | Alignment                                      | Wire value                     | Meaning                                                                   |
 * |------------------------------------------------|--------------------------------|---------------------------------------------------------------------------|
 * | [EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME] | `strong_participant_runtime`   | From the canonical Android runtime path; suitable for V2 gate consumption |
 * | [EvidenceAlignment.ADVISORY_OBSERVATION]       | `advisory_observation`         | Observational/indirect; informative but not gate-sufficient alone         |
 * | [EvidenceAlignment.DEPRECATED_COMPAT]          | `deprecated_compat`            | Legacy/compat path; must NOT count toward V2 gate readiness               |
 * | [EvidenceAlignment.INTENTIONALLY_LOCAL]        | `intentionally_local`          | Local or deferred by design; V2 does not consume directly                 |
 *
 * ## Supersession of prior PR-7 work
 *
 * The prior Android PR-7 effort introduced canonical execution event tracking and durable
 * participant identity reattachment (see [AndroidCanonicalExecutionEventOwner] and the
 * [Pr7AndroidCanonicalExecutionEventsTest] / [Pr7DurableParticipantIdentityReattachTest]
 * test suites).  That runtime work remains valid and its outputs are included in this
 * mapping (see `canonical_execution_event_emission` entries below).
 *
 * This reopened PR-7 builds on that foundation by providing the explicit gate-alignment
 * layer that connects all Android evidence — including the earlier PR-7 canonical event
 * work — to the V2 distributed gate skeleton.
 *
 * ## V2 authority boundary
 *
 * Android is a **participant**, not a release orchestrator.  V2 remains authoritative for:
 *  - Final release gate decisions.
 *  - Canonical truth convergence and participant state adjudication.
 *  - Session/task resumption and re-dispatch.
 *  - Graduation and governance policy.
 *
 * This object provides the Android-side evidence alignment; V2 gate logic decides what
 * to do with it.
 *
 * @see AndroidReadinessEvidenceSurface
 * @see AndroidCanonicalExecutionEventOwner
 * @see DelegatedRuntimeReadinessEvaluator
 * @see DelegatedRuntimeAcceptanceEvaluator
 * @see ContinuityRecoveryDurabilityContract
 */
object AndroidDistributedGateMapping {

    // ── PR metadata ───────────────────────────────────────────────────────────

    /** Human-readable title of the Android PR that introduced this mapping. */
    const val INTRODUCED_PR_TITLE =
        "Reopen Android PR-7: Align Android readiness evidence with canonical " +
            "distributed release-gate skeleton"

    /**
     * Reference to the companion V2 PR that established the canonical distributed
     * gate skeleton this mapping aligns with.
     */
    const val V2_COMPANION_REFERENCE =
        "V2 PR-7 (reopened): Add canonical release gate skeleton for distributed " +
            "readiness evidence — DannyFish-11/ufo-galaxy-realization-v2"

    /**
     * Human-readable description of this mapping object, suitable for wire metadata
     * or audit log entries.
     */
    const val DESCRIPTION =
        "Android-side gate mapping matrix: aligns Android readiness evidence categories " +
            "with the six V2 canonical distributed gate dimensions (lifecycle/runtime " +
            "correctness, takeover execution, participant truth reconciliation, evaluator " +
            "artifact emission, continuity/recovery safety, and compatibility/legacy " +
            "suppression); declares strong, advisory, deprecated-compat, and " +
            "intentionally-local alignment boundaries."

    // ── GateDimension ─────────────────────────────────────────────────────────

    /**
     * The six V2 canonical distributed gate dimensions.
     *
     * These wire values are stable identifiers for the gate categories established in
     * V2 PR-7.  Android evidence maps into these dimensions; V2 gate logic decides
     * whether each dimension is satisfied.
     *
     * @property wireValue Stable string identifier suitable for wire transmission and
     *                     audit log entries.
     */
    enum class GateDimension(val wireValue: String) {

        /**
         * Runtime lifecycle correctness gate dimension.
         *
         * Evidence that the Android delegated runtime lifecycle is stable:
         * the readiness, acceptance, and governance evaluator chains all produce
         * correct verdicts, and lifecycle transition events are properly emitted.
         */
        LIFECYCLE_RUNTIME_CORRECTNESS("lifecycle_runtime_correctness"),

        /**
         * Takeover execution outcomes gate dimension.
         *
         * Evidence that Android takeover and fallback execution paths are correctly
         * bounded by canonical flow controls and that takeover metadata is faithfully
         * propagated through the Android execution pipeline.
         */
        TAKEOVER_EXECUTION_OUTCOMES("takeover_execution_outcomes"),

        /**
         * Participant truth reconciliation gate dimension.
         *
         * Evidence that Android-side truth ownership is stable and that reconciliation
         * signals carrying the runtime truth snapshot are emitted correctly to V2.
         */
        PARTICIPANT_TRUTH_RECONCILIATION("participant_truth_reconciliation"),

        /**
         * Evaluator artifact emission gate dimension.
         *
         * Evidence that structured evaluator artifacts (readiness, acceptance,
         * governance, strategy) are emitted from real runtime paths with stable wire
         * values that V2 release/graduation/governance gates can parse.
         */
        EVALUATOR_ARTIFACT_EMISSION("evaluator_artifact_emission"),

        /**
         * Continuity and recovery safety gate dimension.
         *
         * Evidence that Android correctly bounds in-flight work after restart,
         * reconnect, or offline period, and that duplicate/stale signals are
         * suppressed before reaching V2.
         */
        CONTINUITY_RECOVERY_SAFETY("continuity_recovery_safety"),

        /**
         * Compatibility and legacy suppression gate dimension.
         *
         * Evidence that compat/legacy influence paths are classified, blocked, or
         * quarantined and do not corrupt canonical runtime state or gate evidence.
         */
        COMPATIBILITY_LEGACY_SUPPRESSION("compatibility_legacy_suppression");

        companion object {
            /** Returns the dimension matching [wireValue], or `null` if not found. */
            fun fromValue(wireValue: String?): GateDimension? =
                values().firstOrNull { it.wireValue == wireValue }
        }
    }

    // ── EvidenceAlignment ─────────────────────────────────────────────────────

    /**
     * The alignment strength of an Android evidence category relative to a V2 gate
     * dimension.
     *
     * This replaces implicit confidence-level inference from
     * [AndroidReadinessEvidenceSurface.ConfidenceLevel] with an explicit gate-consumption
     * alignment declaration.
     *
     * @property wireValue Stable string identifier.
     */
    enum class EvidenceAlignment(val wireValue: String) {

        /**
         * Evidence from the canonical Android runtime path.
         *
         * STRONG_PARTICIPANT_RUNTIME evidence is the primary signal for the corresponding
         * V2 gate dimension.  It is grounded in real runtime behavior, validated by
         * concrete tests, and may be consumed by V2 as direct participant evidence.
         */
        STRONG_PARTICIPANT_RUNTIME("strong_participant_runtime"),

        /**
         * Observational or indirect evidence.
         *
         * ADVISORY_OBSERVATION evidence is informative and may support gate confidence
         * but is not gate-sufficient alone.  V2 may treat it as corroborating context
         * rather than a primary gate input.
         */
        ADVISORY_OBSERVATION("advisory_observation"),

        /**
         * Evidence from a legacy or compatibility path.
         *
         * DEPRECATED_COMPAT evidence must NOT count as strong gate evidence.  It is
         * retained for traceability and to confirm that the compat path is correctly
         * suppressed, but its presence does not advance gate satisfaction.
         */
        DEPRECATED_COMPAT("deprecated_compat"),

        /**
         * Evidence that is local or deferred by design.
         *
         * INTENTIONALLY_LOCAL evidence covers Android-side behaviors or guarantees that
         * are correct and validated locally but are not directly consumed by V2 gate
         * logic.  These may become gate-relevant in a later PR or after V2 wire contract
         * stabilisation.
         */
        INTENTIONALLY_LOCAL("intentionally_local");

        companion object {
            /** Returns the alignment matching [wireValue], or `null` if not found. */
            fun fromValue(wireValue: String?): EvidenceAlignment? =
                values().firstOrNull { it.wireValue == wireValue }
        }
    }

    // ── GateMappingEntry ──────────────────────────────────────────────────────

    /**
     * A single Android-to-V2 gate evidence mapping entry.
     *
     * @property mappingId              Stable identifier for this mapping entry.
     * @property gateDimension          The V2 canonical [GateDimension] this entry maps into.
     * @property evidenceAlignment      How strongly this Android evidence aligns to the gate.
     * @property androidDimension       The [AndroidReadinessEvidenceSurface.ReadinessDimension]
     *                                  that Android-side evidence is drawn from.
     * @property primaryAndroidEvidenceIds
     *                                  The [AndroidReadinessEvidenceSurface.EvidenceEntry.evidenceId]
     *                                  values from [AndroidReadinessEvidenceSurface.allEvidence]
     *                                  that constitute this mapping.
     * @property mappingRationale       Why these Android evidence entries map to this gate
     *                                  dimension at this alignment strength.
     * @property v2ConsumptionNote      How V2 gate logic can locate and consume this
     *                                  Android evidence when evaluating the gate dimension.
     */
    data class GateMappingEntry(
        val mappingId: String,
        val gateDimension: GateDimension,
        val evidenceAlignment: EvidenceAlignment,
        val androidDimension: AndroidReadinessEvidenceSurface.ReadinessDimension,
        val primaryAndroidEvidenceIds: List<String>,
        val mappingRationale: String,
        val v2ConsumptionNote: String
    )

    /**
     * The complete Android-to-V2 canonical gate mapping matrix.
     *
     * A reviewer can use this list to determine:
     *  - how every Android evidence category maps into V2 canonical gate dimensions
     *  - which Android evidence is strong vs advisory vs deprecated/local-only
     *  - which V2 gate dimensions are covered by Android evidence
     *  - how later release-policy/CI work can consume Android evidence by gate dimension
     */
    val allMappings: List<GateMappingEntry> = listOf(

        // ── LIFECYCLE_RUNTIME_CORRECTNESS ──────────────────────────────────────

        GateMappingEntry(
            mappingId = "runtime_lifecycle_evaluator_chain_to_gate_lifecycle",
            gateDimension = GateDimension.LIFECYCLE_RUNTIME_CORRECTNESS,
            evidenceAlignment = EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            primaryAndroidEvidenceIds = listOf(
                "readiness_evaluator_five_dimension_verdict",
                "acceptance_evaluator_six_dimension_graduation_verdict",
                "post_graduation_governance_evaluator_verdict",
                "runtime_lifecycle_transition_event_emission"
            ),
            mappingRationale = "DelegatedRuntimeReadinessEvaluator, DelegatedRuntimeAcceptance" +
                "Evaluator, and DelegatedRuntimePostGraduationGovernanceEvaluator form a " +
                "complete readiness → acceptance → governance evaluator chain.  Their outputs " +
                "(DeviceReadinessArtifact, DeviceAcceptanceArtifact, DeviceGovernanceArtifact) " +
                "directly answer whether Android lifecycle is stable enough to participate in " +
                "V2's LIFECYCLE_RUNTIME_CORRECTNESS gate dimension.",
            v2ConsumptionNote = "DeviceReadinessArtifact.semanticTag and DeviceAcceptanceArtifact" +
                ".semanticTag are emitted via ReconciliationSignal Kind.PARTICIPANT_STATE → V2 " +
                "parses semanticTag to determine lifecycle correctness gate satisfaction.  Test " +
                "evidence: Pr9DelegatedRuntimeReadinessTest, Pr10DelegatedRuntimeAcceptanceTest, " +
                "Pr11DelegatedRuntimePostGraduationGovernanceTest, " +
                "Pr37AndroidRuntimeLifecycleHardeningTest."
        ),

        GateMappingEntry(
            mappingId = "canonical_execution_event_emission_to_gate_lifecycle",
            gateDimension = GateDimension.LIFECYCLE_RUNTIME_CORRECTNESS,
            evidenceAlignment = EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            primaryAndroidEvidenceIds = listOf(
                "runtime_lifecycle_transition_event_emission"
            ),
            mappingRationale = "AndroidCanonicalExecutionEventOwner (introduced in the prior " +
                "Android PR-7 effort) ensures canonical execution events are emitted with stable " +
                "wire values and that duplicate, post-terminal, and noise events are correctly " +
                "suppressed.  This event-level correctness evidence feeds the V2 lifecycle " +
                "correctness gate via ReconciliationSignal.",
            v2ConsumptionNote = "CanonicalExecutionEvent.wireValue emitted via RuntimeController" +
                ".reconciliationSignals → GalaxyConnectionService → V2.  Gate_decision_emitted " +
                "events are the canonical execution boundary marker for lifecycle correctness " +
                "verification.  Test evidence: Pr7AndroidCanonicalExecutionEventsTest."
        ),

        GateMappingEntry(
            mappingId = "strategy_evaluator_to_gate_lifecycle_advisory",
            gateDimension = GateDimension.LIFECYCLE_RUNTIME_CORRECTNESS,
            evidenceAlignment = EvidenceAlignment.ADVISORY_OBSERVATION,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            primaryAndroidEvidenceIds = listOf(
                "strategy_evaluator_dispatch_verdict"
            ),
            mappingRationale = "DelegatedRuntimeStrategyEvaluator produces a DeviceStrategyArtifact " +
                "reflecting dispatch strategy dimension statuses.  Advisory rather than strong " +
                "because strategy is an input to runtime policy decisions, not a direct " +
                "lifecycle correctness verdict that the V2 gate can consume as participant evidence.",
            v2ConsumptionNote = "DeviceStrategyArtifact emitted via ReconciliationSignal → V2 " +
                "strategy gate uses DelegatedRuntimeStrategySnapshot.toWireMap() as auxiliary " +
                "context, not as a gate-sufficient lifecycle signal.  Test evidence: " +
                "Pr12DelegatedRuntimeStrategyTest."
        ),

        // ── TAKEOVER_EXECUTION_OUTCOMES ────────────────────────────────────────

        GateMappingEntry(
            mappingId = "takeover_compat_bounding_to_gate_takeover",
            gateDimension = GateDimension.TAKEOVER_EXECUTION_OUTCOMES,
            evidenceAlignment = EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION,
            primaryAndroidEvidenceIds = listOf(
                "takeover_fallback_event_canonical_bounding",
                "takeover_executor_metadata_unification"
            ),
            mappingRationale = "TakeoverFallbackEvent execution paths are gated by " +
                "AndroidCompatLegacyBlockingParticipant before entering the canonical pipeline; " +
                "non-canonical takeover paths are blocked or quarantined.  TakeoverEnvelope " +
                "metadata (continuity_token, is_resumable, recovery_context) round-trips " +
                "faithfully through the Android execution pipeline.  Together these provide " +
                "strong evidence that Android takeover execution outcomes are correctly bounded.",
            v2ConsumptionNote = "CompatLegacyBlockingDecision.semanticTag is emitted in " +
                "ReconciliationSignal PARTICIPANT_STATE → V2 takeover outcome auditor interprets " +
                "blocking decision.  TakeoverEnvelope wire fields confirm V2 handoff metadata is " +
                "received correctly.  Test evidence: Pr8AndroidCompatLegacyBlockingTest, " +
                "Pr03TakeoverMetadataUnificationTest."
        ),

        GateMappingEntry(
            mappingId = "takeover_recovery_authority_to_gate_takeover_advisory",
            gateDimension = GateDimension.TAKEOVER_EXECUTION_OUTCOMES,
            evidenceAlignment = EvidenceAlignment.ADVISORY_OBSERVATION,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.TAKEOVER_EXECUTION,
            primaryAndroidEvidenceIds = listOf(
                "takeover_recovery_path_compat_gate"
            ),
            mappingRationale = "TakeoverFallbackEvent recovery currently uses the general " +
                "AndroidCompatLegacyBlockingParticipant gate rather than a dedicated " +
                "post-reconnect session-authority check keyed on V2 takeover session identifiers.  " +
                "This is advisory evidence (general gate coverage) pending V2 takeover authority " +
                "model stabilisation.  Dedicated bounding is deferred; see deferredItems in " +
                "AndroidReadinessEvidenceSurface: takeover_session_authority_bounding.",
            v2ConsumptionNote = "Deferred: dedicated takeover session-authority bounding will " +
                "map to TAKEOVER_EXECUTION_OUTCOMES as STRONG_PARTICIPANT_RUNTIME once V2 PR-2 " +
                "companion takeover authority surface is stable.  Until then V2 should treat " +
                "this as advisory takeover evidence only.  Test evidence: " +
                "Pr8AndroidCompatLegacyBlockingTest."
        ),

        // ── PARTICIPANT_TRUTH_RECONCILIATION ───────────────────────────────────

        GateMappingEntry(
            mappingId = "truth_reconciliation_signals_to_gate_truth",
            gateDimension = GateDimension.PARTICIPANT_TRUTH_RECONCILIATION,
            evidenceAlignment = EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            primaryAndroidEvidenceIds = listOf(
                "reconciliation_signal_runtime_truth_snapshot_emission",
                "unified_truth_reconciliation_surface_emission",
                "reconciliation_signal_participant_state_emission"
            ),
            mappingRationale = "ReconciliationSignal Kind.RUNTIME_TRUTH_SNAPSHOT carries " +
                "AndroidParticipantRuntimeTruth validated by UnifiedTruthReconciliationSurface " +
                "and TruthReconciliationReducer.  ReconciliationSignal Kind.PARTICIPANT_STATE " +
                "carries structured participant state.  Together these are the canonical Android " +
                "truth ownership and reconciliation evidence that V2's participant truth " +
                "reconciliation gate dimension consumes.",
            v2ConsumptionNote = "ReconciliationSignal (both RUNTIME_TRUTH_SNAPSHOT and " +
                "PARTICIPANT_STATE kinds) → GalaxyConnectionService → V2 WebSocket ingestion " +
                "→ V2 truth reconciliation layer / participant state registry.  Test evidence: " +
                "Pr51AndroidParticipantRuntimeTruthTest, Pr52ReconciliationSignalEmissionTest, " +
                "Pr64UnifiedTruthReconciliationTest."
        ),

        GateMappingEntry(
            mappingId = "durable_identity_reattach_to_gate_truth",
            gateDimension = GateDimension.PARTICIPANT_TRUTH_RECONCILIATION,
            evidenceAlignment = EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            primaryAndroidEvidenceIds = listOf(
                "reconciliation_signal_runtime_truth_snapshot_emission"
            ),
            mappingRationale = "Durable participant identity reattach semantics (introduced in " +
                "the prior Android PR-7 effort) ensure that a reconnecting Android participant " +
                "can present its prior durable identity to V2 for truth reconciliation.  This " +
                "aligns with V2's need to know that a reconnecting participant carries stable " +
                "identity across disruptions.",
            v2ConsumptionNote = "Durable participant identity is encoded in the " +
                "RuntimeTruthSnapshot emitted via ReconciliationSignal RUNTIME_TRUTH_SNAPSHOT " +
                "→ V2 truth reconciliation absorbs the identity claim and confirms or overrides.  " +
                "Test evidence: Pr7DurableParticipantIdentityReattachTest."
        ),

        // ── EVALUATOR_ARTIFACT_EMISSION ────────────────────────────────────────

        GateMappingEntry(
            mappingId = "device_artifacts_to_gate_evaluator",
            gateDimension = GateDimension.EVALUATOR_ARTIFACT_EMISSION,
            evidenceAlignment = EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.ARTIFACT_EMISSION_RECONCILIATION,
            primaryAndroidEvidenceIds = listOf(
                "device_readiness_artifact_wire_emission",
                "device_acceptance_artifact_wire_emission"
            ),
            mappingRationale = "DeviceReadinessArtifact and DeviceAcceptanceArtifact carry " +
                "stable semanticTag wire values emitted from real evaluator runtime paths via " +
                "ReconciliationSignal.  These are the primary structured Android evaluator " +
                "outputs that V2 release and graduation gates consume.",
            v2ConsumptionNote = "ReconciliationSignal Kind.PARTICIPANT_STATE → V2 parses " +
                "DeviceReadinessArtifact.semanticTag (routes to V2 PR-9 release gate) and " +
                "DeviceAcceptanceArtifact.semanticTag (routes to V2 PR-10 graduation gate).  " +
                "Test evidence: Pr4AndroidEvaluatorArtifactEmissionTest."
        ),

        GateMappingEntry(
            mappingId = "governance_artifact_to_gate_evaluator",
            gateDimension = GateDimension.EVALUATOR_ARTIFACT_EMISSION,
            evidenceAlignment = EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            primaryAndroidEvidenceIds = listOf(
                "post_graduation_governance_evaluator_verdict"
            ),
            mappingRationale = "DeviceGovernanceArtifact from " +
                "DelegatedRuntimePostGraduationGovernanceEvaluator provides ongoing evidence " +
                "that a graduated device remains in a valid governance state beyond point-in-time " +
                "release.  This is the post-graduation evaluator artifact for V2 governance gate.",
            v2ConsumptionNote = "DeviceGovernanceArtifact emitted via ReconciliationSignal → " +
                "V2 PR-11 post-graduation governance gate absorbs via " +
                "DelegatedRuntimeGovernanceSnapshot.toWireMap().  Test evidence: " +
                "Pr11DelegatedRuntimePostGraduationGovernanceTest."
        ),

        GateMappingEntry(
            mappingId = "strategy_artifact_to_gate_evaluator_advisory",
            gateDimension = GateDimension.EVALUATOR_ARTIFACT_EMISSION,
            evidenceAlignment = EvidenceAlignment.ADVISORY_OBSERVATION,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.RUNTIME_LIFECYCLE,
            primaryAndroidEvidenceIds = listOf(
                "strategy_evaluator_dispatch_verdict"
            ),
            mappingRationale = "DeviceStrategyArtifact from DelegatedRuntimeStrategyEvaluator " +
                "reflects dispatch strategy dimension statuses.  Advisory because strategy is " +
                "an input to runtime policy decisions, not a direct participant readiness verdict " +
                "that the evaluator artifact emission gate can consume as primary evidence.",
            v2ConsumptionNote = "DeviceStrategyArtifact emitted via ReconciliationSignal → V2 " +
                "strategy gate uses DelegatedRuntimeStrategySnapshot.toWireMap() as auxiliary " +
                "context.  Test evidence: Pr12DelegatedRuntimeStrategyTest."
        ),

        // ── CONTINUITY_RECOVERY_SAFETY ─────────────────────────────────────────

        GateMappingEntry(
            mappingId = "recovery_bounding_to_gate_continuity",
            gateDimension = GateDimension.CONTINUITY_RECOVERY_SAFETY,
            evidenceAlignment = EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY,
            primaryAndroidEvidenceIds = listOf(
                "recovery_participation_owner_restart_reconnect_bounding",
                "continuity_recovery_durability_contract_coverage",
                "hybrid_lifecycle_recovery_contract_coverage",
                "durable_session_continuity_record_rehydration"
            ),
            mappingRationale = "AndroidRecoveryParticipationOwner correctly bounds in-flight " +
                "work after process recreation and transport reconnect.  " +
                "ContinuityRecoveryDurabilityContract documents 13 covered recovery behaviors " +
                "and 6 bounded emission rules proven by concrete tests.  This body of evidence " +
                "directly satisfies the V2 CONTINUITY_RECOVERY_SAFETY gate dimension, which " +
                "requires that participant-side work is not silently lost or replayed incorrectly " +
                "across disruptions.",
            v2ConsumptionNote = "LocalRecoveryDecision.WaitForV2ReplayDecision.durableSessionId " +
                "is forwarded to V2 in recovery handshake → V2 decides replay/resume/fresh.  " +
                "ContinuityRecoveryDurabilityContract.coveredBehaviors and .boundedEmissions are " +
                "queryable by V2 governance tooling.  Test evidence: " +
                "Pr66ContinuityRecoveryDurabilityTest, Pr4AndroidRecoveryParticipationOwnerTest, " +
                "Pr53AndroidLifecycleRecoveryHybridHardeningTest."
        ),

        GateMappingEntry(
            mappingId = "signal_dedup_replay_to_gate_continuity",
            gateDimension = GateDimension.CONTINUITY_RECOVERY_SAFETY,
            evidenceAlignment = EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.SIGNAL_REPLAY_DUPLICATE_SAFETY,
            primaryAndroidEvidenceIds = listOf(
                "emitted_signal_ledger_terminal_bounding",
                "continuity_integration_duplicate_signal_suppression",
                "offline_queue_stale_session_discard",
                "delegated_execution_signal_idempotency_guard"
            ),
            mappingRationale = "EmittedSignalLedger terminal bounding, duplicate signal " +
                "suppression in AndroidContinuityIntegration, stale offline queue discard, and " +
                "DelegatedExecutionSignal signalId idempotency together guarantee that V2 " +
                "receives each signal at most once per execution era and never receives stale " +
                "post-terminal ACK/PROGRESS.  This is the Android-side safety guarantee that " +
                "the V2 CONTINUITY_RECOVERY_SAFETY gate dimension requires from participants.",
            v2ConsumptionNote = "Bounded/suppressed signals are dropped before reaching the " +
                "DelegatedExecutionSignalSink transport layer; V2 never receives duplicates.  " +
                "V2 deduplicates inbound DelegatedExecutionSignal by signalId as a defence-in-depth " +
                "measure.  Test evidence: Pr66ContinuityRecoveryDurabilityTest, " +
                "DelegatedExecutionSignalIdempotencyTest, EmittedSignalLedgerTest."
        ),

        // ── COMPATIBILITY_LEGACY_SUPPRESSION ───────────────────────────────────

        GateMappingEntry(
            mappingId = "compat_blocking_audit_to_gate_compat",
            gateDimension = GateDimension.COMPATIBILITY_LEGACY_SUPPRESSION,
            evidenceAlignment = EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            primaryAndroidEvidenceIds = listOf(
                "compat_legacy_blocking_participant_canonical_path_confirmation",
                "compatibility_surface_retirement_registry",
                "authoritative_path_alignment_audit",
                "compatibility_retirement_fence_blocking"
            ),
            mappingRationale = "AndroidCompatLegacyBlockingParticipant classifies each path " +
                "into five CompatLegacyInfluenceClasses and produces typed blocking decisions.  " +
                "CompatibilitySurfaceRetirementRegistry documents all retired Android compat " +
                "surfaces.  AndroidAuthoritativePathAlignmentAudit maps each compat influence " +
                "point to its current classification.  CompatibilityRetirementFence actively " +
                "blocks execution through retired surfaces at runtime.  Together these provide " +
                "comprehensive evidence for V2's COMPATIBILITY_LEGACY_SUPPRESSION gate dimension.",
            v2ConsumptionNote = "CompatLegacyBlockingDecision.semanticTag in ReconciliationSignal " +
                "PARTICIPANT_STATE → V2 compat influence auditor.  Retirement registry queryable " +
                "by V2 governance tooling; retired surface IDs cross-referenced against V2 compat " +
                "influence audit.  Audit entries exported via ReconciliationSignal compat-state " +
                "payload → V2 PR-3 single-path convergence gate.  Test evidence: " +
                "Pr8AndroidCompatLegacyBlockingTest, Pr10CompatibilitySurfaceRetirementTest, " +
                "Pr65AndroidAuthoritativePathAlignmentTest, Pr36CompatRetirementHardeningTest."
        ),

        GateMappingEntry(
            mappingId = "long_tail_compat_to_gate_compat_deprecated",
            gateDimension = GateDimension.COMPATIBILITY_LEGACY_SUPPRESSION,
            evidenceAlignment = EvidenceAlignment.DEPRECATED_COMPAT,
            androidDimension = AndroidReadinessEvidenceSurface.ReadinessDimension.COMPATIBILITY_SUPPRESSION,
            primaryAndroidEvidenceIds = listOf(
                "long_tail_compat_registry_legacy_signals"
            ),
            mappingRationale = "LongTailCompatibilityRegistry retains legacy compat signal " +
                "registrations for historical traceability.  These are explicitly " +
                "DEPRECATED_COMPAT — they confirm compat paths exist but must NOT count as " +
                "strong evidence for the COMPATIBILITY_LEGACY_SUPPRESSION gate.  They are " +
                "listed here for completeness and to make the DEPRECATED_COMPAT boundary " +
                "explicit for V2 gate reviewers.",
            v2ConsumptionNote = "These signals are NOT forwarded as canonical gate evidence.  " +
                "V2 compat audit may reference them for suppression confirmation only.  " +
                "V2 gate logic must exclude DEPRECATED_COMPAT entries from gate satisfaction " +
                "calculations.  Test evidence: Pr35LongTailCompatHandlingTest."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns the [GateMappingEntry] with [mappingId], or `null` if not found.
     */
    fun mappingFor(mappingId: String): GateMappingEntry? =
        allMappings.firstOrNull { it.mappingId == mappingId }

    /**
     * Returns all mapping entries targeting [gateDimension].
     */
    fun mappingsForDimension(gateDimension: GateDimension): List<GateMappingEntry> =
        allMappings.filter { it.gateDimension == gateDimension }

    /**
     * Returns all mapping entries with [evidenceAlignment].
     */
    fun mappingsAtAlignment(evidenceAlignment: EvidenceAlignment): List<GateMappingEntry> =
        allMappings.filter { it.evidenceAlignment == evidenceAlignment }

    /**
     * Returns all [GateMappingEntry] that include [androidEvidenceId] in their
     * [GateMappingEntry.primaryAndroidEvidenceIds] list.
     */
    fun mappingsForAndroidEvidence(androidEvidenceId: String): List<GateMappingEntry> =
        allMappings.filter { it.primaryAndroidEvidenceIds.contains(androidEvidenceId) }

    /**
     * Returns all [GateDimension] values that have at least one
     * [EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME] mapping entry.
     *
     * A reviewer can use this to confirm that every V2 gate dimension has strong Android
     * evidence coverage.
     */
    fun dimensionsCoveredByStrongEvidence(): Set<GateDimension> =
        mappingsAtAlignment(EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME)
            .map { it.gateDimension }
            .toSet()

    // ── Count constants for test assertions ───────────────────────────────────

    /** Expected total number of gate mapping entries at the time of this PR. */
    const val MAPPING_ENTRY_COUNT = 14

    /** Expected number of STRONG_PARTICIPANT_RUNTIME alignment entries. */
    const val STRONG_MAPPING_COUNT = 10

    /** Expected number of ADVISORY_OBSERVATION alignment entries. */
    const val ADVISORY_MAPPING_COUNT = 3

    /** Expected number of DEPRECATED_COMPAT alignment entries. */
    const val DEPRECATED_COMPAT_MAPPING_COUNT = 1

    /** Expected number of INTENTIONALLY_LOCAL alignment entries. */
    const val INTENTIONALLY_LOCAL_MAPPING_COUNT = 0
}
