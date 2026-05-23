package com.ufo.galaxy.runtime

/**
 * PR-72 (Android) — Per-stage outcome record for a [DualRepoE2EVerificationHarness] run.
 *
 * [DualRepoE2EStageOutcome] records the pass/fail status and optional reason for a single
 * [DualRepoE2EVerificationStage] within a [DualRepoE2EVerificationReport].
 *
 * @property stage         The [DualRepoE2EVerificationStage] this outcome describes.
 * @property outcomeStatus The [ScenarioOutcomeStatus] observed for this stage.
 * @property reason        Optional human-readable explanation; expected when
 *                         [outcomeStatus] is not [ScenarioOutcomeStatus.PASSED].
 */
data class DualRepoE2EStageOutcome(
    val stage: DualRepoE2EVerificationStage,
    val outcomeStatus: ScenarioOutcomeStatus,
    val reason: String? = null
)

/**
 * Verification-hook kinds used by [DualRepoE2EVerificationHarness] to expose Android-side
 * roundtrip evidence beyond stage pass/fail status.
 */
enum class DualRepoE2EVerificationHookKind(val wireValue: String) {
    EXECUTION_RECEIVED("execution_received"),
    SIGNAL_EMITTED("signal_emitted"),
    RESULT_FEEDBACK("result_feedback"),
    STATE_CORRELATED("state_correlated");

    companion object {
        val REQUIRED_HOOKS: Set<DualRepoE2EVerificationHookKind> = setOf(
            EXECUTION_RECEIVED,
            SIGNAL_EMITTED,
            RESULT_FEEDBACK,
            STATE_CORRELATED
        )
    }
}

/**
 * Android-side roundtrip verification-hook record used for cross-device correlation checks.
 */
data class DualRepoE2EVerificationHookRecord(
    val kind: DualRepoE2EVerificationHookKind,
    val outcomeStatus: ScenarioOutcomeStatus,
    val traceId: String? = null,
    val runtimeSessionId: String? = null,
    val taskId: String? = null,
    val delegatedSignalKind: String? = null,
    val reason: String? = null
)

/**
 * Machine-readable evidence state vocabulary for cross-repo protected verification reporting.
 */
enum class IntegrationEvidenceState(val wireValue: String) {
    NOT_RUN("not_run"),
    PARTIALLY_RUN("partially_run"),
    FAILED("failed"),
    EVIDENCED("evidenced")
}

/**
 * Key behavior evidence status exposed for replay/reconnect/recovery/closure visibility.
 */
data class KeyBehaviorEvidenceStatus(
    val replayOrdering: IntegrationEvidenceState,
    val reconnectContinuity: IntegrationEvidenceState,
    val recoveryBehavior: IntegrationEvidenceState,
    val closureCompleteness: IntegrationEvidenceState
) {
    fun toWireMap(): Map<String, String> = mapOf(
        "replay_ordering" to replayOrdering.wireValue,
        "reconnect_continuity" to reconnectContinuity.wireValue,
        "recovery_behavior" to recoveryBehavior.wireValue,
        "closure_completeness" to closureCompleteness.wireValue
    )
}

/**
 * Canonical verification steps for Android local AI consumer flow uplinked to V2.
 */
enum class LocalAiCanonicalVerificationStep(val wireValue: String, val isRequired: Boolean) {
    LOCAL_INFERENCE_STARTED("local_inference_started", true),
    LOCAL_RESULT_GENERATED("local_result_generated", true),
    RUNTIME_VISIBLE_DIAGNOSTICS("runtime_visible_diagnostics", true),
    RESULT_UPLINK_EMITTED("result_uplink_emitted", true),
    V2_CANONICAL_INGRESS("v2_canonical_ingress", true),
    V2_TRUTH_RECONCILIATION("v2_truth_reconciliation", true),
    V2_CLOSURE_OUTWARD_COMPILED("v2_closure_outward_compiled", true);

    companion object {
        val ALL_STEPS: Set<LocalAiCanonicalVerificationStep> = entries.toSet()
    }
}

/**
 * Explicit authority boundary class for Android local inference outputs.
 */
enum class LocalAiResultAuthorityBoundaryClass(val wireValue: String) {
    LOCAL_RUNTIME_CONTRIBUTION_ONLY("local_runtime_contribution_only"),
    CANONICAL_FINAL_TRUTH_FORBIDDEN("canonical_final_truth_forbidden")
}

/**
 * Structured evidence showing whether Android local AI consumer flow stayed in canonical chain.
 */
data class LocalAiCanonicalFlowEvidence(
    val stepOutcomes: Map<LocalAiCanonicalVerificationStep, ScenarioOutcomeStatus>,
    val localInferenceCapabilityStatus: LocalIntelligenceCapabilityStatus,
    val localInferenceActivationTier: LocalIntelligenceActivationPolicy.ActivationTier,
    val authorityBoundaryClass: LocalAiResultAuthorityBoundaryClass,
    val reconciliationSignalKind: ReconciliationSignal.Kind,
    val localInferenceReason: String? = null
) {
    val isCanonicalChainVerified: Boolean
        get() = LocalAiCanonicalVerificationStep.ALL_STEPS.all { step ->
            stepOutcomes[step] == ScenarioOutcomeStatus.PASSED
        }

    val isLocalResultAuthorityBounded: Boolean
        get() = authorityBoundaryClass == LocalAiResultAuthorityBoundaryClass.LOCAL_RUNTIME_CONTRIBUTION_ONLY

    fun toWireMap(): Map<String, Any?> = mapOf(
        "step_outcomes" to stepOutcomes.mapKeys { it.key.wireValue }.mapValues { it.value.wireValue },
        "local_inference_capability_status" to localInferenceCapabilityStatus.wireValue,
        "local_inference_activation_tier" to localInferenceActivationTier.wireValue,
        "authority_boundary_class" to authorityBoundaryClass.wireValue,
        "reconciliation_signal_kind" to reconciliationSignalKind.wireValue,
        "local_inference_reason" to localInferenceReason,
        "is_canonical_chain_verified" to isCanonicalChainVerified,
        "is_local_result_authority_bounded" to isLocalResultAuthorityBounded
    )
}

/**
 * PR-72 (Android) — Structured dual-repo E2E verification report for cross-repo consumption
 * by V2 readiness / acceptance systems.
 *
 * [DualRepoE2EVerificationReport] is the canonical Android-side artifact that proves (or
 * explicitly refutes) that an Android participant's full lifecycle chain has been observed
 * on a real physical device in a dual-repo E2E run.  It is the primary output of
 * [DualRepoE2EVerificationHarness.buildReport].
 *
 * ## Problem addressed
 *
 * The existing [RealDeviceParticipantVerificationBridge] (PR-70) established a three-required-
 * scenario verification model (REGISTRATION, COMMAND_RECEPTION, DELEGATED_EXECUTION_AVAILABILITY)
 * and a structured bridge artifact.  However, the dual-repo E2E closed loop requires two
 * additional stages that the bridge does not cover:
 *
 *  1. **CAPABILITY_REPORT** — the device must send a capability report to V2 (not just
 *     register).  Without this, V2 cannot route tasks to the participant.
 *  2. **TASK_RESULT_RETURN** — the task result must be returned to V2 (not just executed
 *     locally).  Without this, the result-feedback loop is not closed.
 *
 * [DualRepoE2EVerificationReport] closes this gap by:
 *  - Extending the verification chain to seven stages covering the full round trip.
 *  - Producing a typed [DualRepoE2EVerificationArtifact] that V2 can inspect without
 *    parsing raw stage outcomes.
 *  - Embedding the lower-level [RealDeviceParticipantVerificationReport] for backward
 *    compatibility and transparency.
 *  - Exporting a V2-consumable [toWireMap] at schema version [SCHEMA_VERSION].
 *
 * ## V2 consumption contract
 *
 * A V2 consumer must:
 *
 * 1. Check [isV2Consumable] — if `false` the report must not be used for acceptance or
 *    governance decisions.
 * 2. Check [verificationKind] — only [RealDeviceVerificationKind.REAL_DEVICE] provides
 *    authoritative evidence for hardware-gated acceptance.
 * 3. Inspect [overallArtifact] — the typed artifact carries the canonical E2E verdict.
 * 4. Check [isRealDeviceE2EVerified] — `true` only for a complete real-device E2E run.
 * 5. Use [toWireMap] for stable serialized export.
 *
 * ## Prohibition on optimistic promotion
 *
 * This report **explicitly prohibits**:
 *  - Reporting [RealDeviceVerificationKind.REAL_DEVICE] when no physical device was present.
 *  - Reporting partial stage coverage as a fully verified E2E run.
 *  - Treating [RealDeviceVerificationKind.EMULATOR] or [SIMULATED] evidence as equivalent to
 *    [REAL_DEVICE] for hardware-gated decisions.
 *  - Treating [RealDeviceVerificationKind.STALE] evidence as current without a fresh run.
 *
 * @property reportId      Stable UUID identifier for this report instance.
 * @property deviceId      Hardware device identifier; blank if no device was present.
 * @property participantId Participant node identifier; blank if participant was not registered.
 * @property verificationKind [RealDeviceVerificationKind] classifying the provenance of this
 *                            report.
 * @property stageOutcomes Per-stage outcome map; keys are [DualRepoE2EVerificationStage].
 * @property verificationHooks Android-side hook evidence for runtime roundtrip correlation.
 * @property governanceSnapshot Optional Android governance snapshot captured during verification.
 * @property overallArtifact Typed [DualRepoE2EVerificationArtifact] for the overall verdict.
 * @property bridgeReport  Embedded [RealDeviceParticipantVerificationReport] from the
 *                         lower-level bridge (backward-compatible; includes the three original
 *                         required bridge scenarios).
 * @property lifecycleTruthState Optional [ParticipantLifecycleTruthState] observed at
 *                               verification time.
 * @property reportedAtMs  Epoch-millisecond timestamp when this report was produced.
 * @property schemaVersion Report schema version; currently [SCHEMA_VERSION].
 *
 * @see DualRepoE2EVerificationHarness
 * @see DualRepoE2EVerificationArtifact
 * @see DualRepoE2EVerificationStage
 * @see RealDeviceParticipantVerificationReport
 */
data class DualRepoE2EVerificationReport(
    val reportId: String,
    val deviceId: String,
    val participantId: String,
    val verificationKind: RealDeviceVerificationKind,
    val stageOutcomes: Map<DualRepoE2EVerificationStage, DualRepoE2EStageOutcome>,
    val verificationHooks: Map<DualRepoE2EVerificationHookKind, DualRepoE2EVerificationHookRecord>,
    val governanceSnapshot: DelegatedRuntimeGovernanceSnapshot?,
    val localAiCanonicalFlowEvidence: LocalAiCanonicalFlowEvidence? = null,
    val overallArtifact: DualRepoE2EVerificationArtifact,
    val bridgeReport: RealDeviceParticipantVerificationReport,
    val lifecycleTruthState: ParticipantLifecycleTruthState?,
    val reportedAtMs: Long,
    val schemaVersion: String = SCHEMA_VERSION
) {

    /**
     * Returns `true` when this report carries authoritative real-device dual-repo E2E evidence
     * (i.e. [verificationKind] is [RealDeviceVerificationKind.REAL_DEVICE] and all five
     * required stages passed).
     */
    val isRealDeviceE2EVerified: Boolean
        get() = verificationKind == RealDeviceVerificationKind.REAL_DEVICE
            && overallArtifact is DualRepoE2EVerificationArtifact.E2EFullyVerified

    /**
     * Returns `true` when this report can be safely consumed by V2 acceptance/readiness
     * systems.
     *
     * A report is V2-consumable only when:
     *  - [verificationKind] is [RealDeviceVerificationKind.REAL_DEVICE] or
     *    [RealDeviceVerificationKind.EMULATOR] (usable evidence).
     *  - [overallArtifact] is not [DualRepoE2EVerificationArtifact.E2EAbsent].
     *  - [deviceId] is not blank.
     */
    val isV2Consumable: Boolean
        get() = RealDeviceVerificationKind.isUsableEvidence(verificationKind)
            && overallArtifact !is DualRepoE2EVerificationArtifact.E2EAbsent
            && deviceId.isNotBlank()

    /** True when all canonical verification hooks are present and PASSED. */
    val hasCanonicalRoundTripHooks: Boolean
        get() = DualRepoE2EVerificationHookKind.REQUIRED_HOOKS.all { kind ->
            verificationHooks[kind]?.outcomeStatus == ScenarioOutcomeStatus.PASSED
        }

    /** Correlated trace id when all hook-level non-blank values agree; null otherwise. */
    val correlatedTraceId: String?
        get() = verificationHooks.values
            .mapNotNull { it.traceId?.takeIf(String::isNotBlank) }
            .distinct()
            .singleOrNull()

    /** Correlated runtime session id when all hook-level non-blank values agree; null otherwise. */
    val correlatedRuntimeSessionId: String?
        get() = verificationHooks.values
            .mapNotNull { it.runtimeSessionId?.takeIf(String::isNotBlank) }
            .distinct()
            .singleOrNull()

    /** Correlated task id when all hook-level non-blank values agree; null otherwise. */
    val correlatedTaskId: String?
        get() = verificationHooks.values
            .mapNotNull { it.taskId?.takeIf(String::isNotBlank) }
            .distinct()
            .singleOrNull()

    /** True when trace/runtime_session/task identifiers are all non-null and correlated. */
    val isIdentityCorrelated: Boolean
        get() = correlatedTraceId != null && correlatedRuntimeSessionId != null && correlatedTaskId != null

    /**
     * `true` when Android evidence proves participation-ready runtime conditions:
     * registration + capability report + task reception + delegated execution availability.
     */
    val isParticipationReadyEvidence: Boolean
        get() = PARTICIPATION_READY_STAGES.all { stage ->
            stageOutcomes[stage]?.outcomeStatus == ScenarioOutcomeStatus.PASSED
        }

    /**
     * `true` when Android evidence proves runtime closure (ready evidence + result returned).
     */
    val isRuntimeClosedEvidence: Boolean
        get() = isParticipationReadyEvidence &&
            stageOutcomes[DualRepoE2EVerificationStage.TASK_RESULT_RETURN]?.outcomeStatus ==
            ScenarioOutcomeStatus.PASSED

    /** `true` when Android provided a structured governance snapshot alongside the E2E report. */
    val hasGovernanceSignal: Boolean
        get() = governanceSnapshot != null

    /** `true` when local AI consumer flow is fully observed in canonical V2 chain. */
    val isLocalAiCanonicalChainVerified: Boolean
        get() {
            val evidence = localAiCanonicalFlowEvidence ?: return false
            return evidence.isCanonicalChainVerified && evidence.isLocalResultAuthorityBounded
        }

    /**
     * Machine-readable status projection for key cross-repo behaviors.
     *
     * This projection intentionally reports bounded evidence states rather than optimistic
     * promotion. When a behavior was not exercised in this run, it remains `not_run`.
     */
    val keyBehaviorEvidenceStatus: KeyBehaviorEvidenceStatus
        get() = KeyBehaviorEvidenceStatus(
            replayOrdering = deriveReplayOrderingEvidenceState(),
            reconnectContinuity = deriveSingleStageEvidenceState(
                stageOutcomes[DualRepoE2EVerificationStage.RECONNECT_RECOVERY]?.outcomeStatus
            ),
            recoveryBehavior = deriveRecoveryBehaviorEvidenceState(),
            closureCompleteness = deriveClosureCompletenessEvidenceState()
        )

    /**
     * Produces a stable, V2-consumable key-value map for cross-repo serialization.
     *
     * The map uses snake_case keys and is safe for JSON serialization.  The schema is
     * stable at version [SCHEMA_VERSION].
     *
     * ## Top-level keys
     *
     * | Key                              | Type                | Description                                            |
     * |----------------------------------|---------------------|--------------------------------------------------------|
     * | `schema_version`                 | String              | Always [SCHEMA_VERSION].                               |
     * | `report_id`                      | String              | [reportId]                                             |
     * | `device_id`                      | String              | [deviceId]                                             |
     * | `participant_id`                 | String              | [participantId]                                        |
     * | `verification_kind`              | String              | [verificationKind.wireValue]                           |
     * | `artifact_tag`                   | String              | [overallArtifact.artifactTag]                          |
     * | `lifecycle_truth_state`          | String?             | [lifecycleTruthState.wireValue] or `null`              |
     * | `reported_at_ms`                 | Long                | [reportedAtMs]                                         |
     * | `is_real_device_e2e_verified`    | Boolean             | [isRealDeviceE2EVerified]                              |
     * | `is_v2_consumable`               | Boolean             | [isV2Consumable]                                       |
     * | `has_governance_signal`          | Boolean             | Whether [governanceSnapshot] is present.               |
     * | `stage_outcomes`                 | Map<String, String> | Stage wireValue → outcome wireValue                    |
     * | `stage_reasons`                  | Map<String, String> | Stage wireValue → reason (only when present)           |
     * | `governance_artifact_tag`        | String?             | [governanceSnapshot.artifact.semanticTag]              |
     * | `governance_dimension_states`    | Map<String, String> | Governance dimension wireValue → status wireValue      |
     * | `bridge_artifact_tag`            | String              | [bridgeReport.overallVerificationArtifact.artifactTag] |
     * | `bridge_is_real_device_verified` | Boolean             | [bridgeReport.isRealDeviceVerified]                    |
     */
    fun toWireMap(): Map<String, Any?> {
        val stageOutcomesWire: Map<String, String> = stageOutcomes
            .mapKeys { it.key.wireValue }
            .mapValues { it.value.outcomeStatus.wireValue }
        val stageReasonsWire: Map<String, String> = stageOutcomes
            .filter { it.value.reason != null }
            .mapKeys { it.key.wireValue }
            .mapValues { it.value.reason!! }
        val verificationHooksWire: Map<String, String> = verificationHooks
            .mapKeys { it.key.wireValue }
            .mapValues { it.value.outcomeStatus.wireValue }
        val verificationHookReasonsWire: Map<String, String> = verificationHooks
            .filter { it.value.reason != null }
            .mapKeys { it.key.wireValue }
            .mapValues { it.value.reason!! }
        val verificationHookTraceIdsWire: Map<String, String> = verificationHooks
            .filter { !it.value.traceId.isNullOrBlank() }
            .mapKeys { it.key.wireValue }
            .mapValues { it.value.traceId!! }
        val verificationHookRuntimeSessionIdsWire: Map<String, String> = verificationHooks
            .filter { !it.value.runtimeSessionId.isNullOrBlank() }
            .mapKeys { it.key.wireValue }
            .mapValues { it.value.runtimeSessionId!! }
        val verificationHookTaskIdsWire: Map<String, String> = verificationHooks
            .filter { !it.value.taskId.isNullOrBlank() }
            .mapKeys { it.key.wireValue }
            .mapValues { it.value.taskId!! }
        val delegatedSignalKindsWire: Map<String, String> = verificationHooks
            .filter { !it.value.delegatedSignalKind.isNullOrBlank() }
            .mapKeys { it.key.wireValue }
            .mapValues { it.value.delegatedSignalKind!! }
        val governanceDimensionStatesWire: Map<String, String> = governanceSnapshot
            ?.dimensionStates
            ?.mapKeys { it.key.wireValue }
            ?.mapValues { it.value.status.wireValue }
            ?: emptyMap()
        val governanceDimensionReasonsWire: Map<String, String> = governanceSnapshot
            ?.dimensionStates
            ?.filter { !it.value.regressionReason.isNullOrBlank() }
            ?.mapKeys { it.key.wireValue }
            ?.mapValues { it.value.regressionReason!! }
            ?: emptyMap()
        val governanceMissingDimensionsWire: List<String> = when (val artifact = governanceSnapshot?.artifact) {
            is DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal ->
                artifact.missingDimensions.map { it.wireValue }
            else -> emptyList()
        }
        val governanceBlockingDimensionWire: String? = when (val artifact = governanceSnapshot?.artifact) {
            is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression ->
                artifact.dimension.wireValue
            is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression ->
                artifact.dimension.wireValue
            is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression ->
                artifact.dimension.wireValue
            is DeviceGovernanceArtifact.DeviceGovernanceViolationDueToCompatBypass ->
                artifact.dimension.wireValue
            else -> null
        }
        return mapOf(
            "schema_version" to schemaVersion,
            "report_id" to reportId,
            "device_id" to deviceId,
            "participant_id" to participantId,
            "verification_kind" to verificationKind.wireValue,
            "artifact_tag" to overallArtifact.artifactTag,
            "lifecycle_truth_state" to lifecycleTruthState?.wireValue,
            "reported_at_ms" to reportedAtMs,
            "is_real_device_e2e_verified" to isRealDeviceE2EVerified,
            "is_v2_consumable" to isV2Consumable,
            "has_canonical_roundtrip_hooks" to hasCanonicalRoundTripHooks,
            "is_identity_correlated" to isIdentityCorrelated,
            "is_participation_ready_evidence" to isParticipationReadyEvidence,
            "is_runtime_closed_evidence" to isRuntimeClosedEvidence,
            "key_behavior_evidence_status" to keyBehaviorEvidenceStatus.toWireMap(),
            "is_local_ai_canonical_chain_verified" to isLocalAiCanonicalChainVerified,
            "has_governance_signal" to hasGovernanceSignal,
            "correlated_trace_id" to correlatedTraceId,
            "correlated_runtime_session_id" to correlatedRuntimeSessionId,
            "correlated_task_id" to correlatedTaskId,
            "canonical_uplink_path_map" to CANONICAL_UPLINK_PATH_MAP,
            "stage_outcomes" to stageOutcomesWire,
            "stage_reasons" to stageReasonsWire,
            "verification_hooks" to verificationHooksWire,
            "verification_hook_reasons" to verificationHookReasonsWire,
            "verification_hook_trace_ids" to verificationHookTraceIdsWire,
            "verification_hook_runtime_session_ids" to verificationHookRuntimeSessionIdsWire,
            "verification_hook_task_ids" to verificationHookTaskIdsWire,
            "verification_hook_delegated_signal_kinds" to delegatedSignalKindsWire,
            "governance_snapshot_id" to governanceSnapshot?.snapshotId,
            "governance_device_id" to governanceSnapshot?.deviceId,
            "governance_artifact_tag" to governanceSnapshot?.artifact?.semanticTag,
            "governance_reported_at_ms" to governanceSnapshot?.reportedAtMs,
            "governance_blocking_dimension" to governanceBlockingDimensionWire,
            "governance_missing_dimensions" to governanceMissingDimensionsWire,
            "governance_dimension_states" to governanceDimensionStatesWire,
            "governance_dimension_reasons" to governanceDimensionReasonsWire,
            "local_ai_canonical_flow" to localAiCanonicalFlowEvidence?.toWireMap(),
            "bridge_artifact_tag" to bridgeReport.overallVerificationArtifact.artifactTag,
            "bridge_is_real_device_verified" to bridgeReport.isRealDeviceVerified
        )
    }

    private fun deriveReplayOrderingEvidenceState(): IntegrationEvidenceState {
        val orderedReplayStages = listOf(
            DualRepoE2EVerificationStage.DEVICE_REGISTER,
            DualRepoE2EVerificationStage.CAPABILITY_REPORT,
            DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION,
            DualRepoE2EVerificationStage.DELEGATED_EXECUTION_AVAILABLE,
            DualRepoE2EVerificationStage.TASK_RESULT_RETURN
        )
        val statuses = orderedReplayStages.map { stageOutcomes[it]?.outcomeStatus }
        return deriveEvidenceStateFromStatuses(statuses)
    }

    private fun deriveRecoveryBehaviorEvidenceState(): IntegrationEvidenceState {
        val statuses = listOf(
            stageOutcomes[DualRepoE2EVerificationStage.RECONNECT_RECOVERY]?.outcomeStatus,
            stageOutcomes[DualRepoE2EVerificationStage.DEGRADED_OUTCOME_RECORDING]?.outcomeStatus
        )
        return deriveEvidenceStateFromStatuses(statuses)
    }

    private fun deriveClosureCompletenessEvidenceState(): IntegrationEvidenceState {
        val taskResultStatus = stageOutcomes[DualRepoE2EVerificationStage.TASK_RESULT_RETURN]?.outcomeStatus
        val resultFeedbackStatus =
            verificationHooks[DualRepoE2EVerificationHookKind.RESULT_FEEDBACK]?.outcomeStatus
        val statuses = listOf(taskResultStatus, resultFeedbackStatus)
        if (statuses.all { it == null }) return IntegrationEvidenceState.NOT_RUN
        if (
            taskResultStatus == ScenarioOutcomeStatus.PASSED &&
            resultFeedbackStatus == ScenarioOutcomeStatus.PASSED &&
            isRuntimeClosedEvidence
        ) {
            return IntegrationEvidenceState.EVIDENCED
        }
        if (statuses.any { it == ScenarioOutcomeStatus.FAILED || it == ScenarioOutcomeStatus.TIMED_OUT }) {
            return IntegrationEvidenceState.FAILED
        }
        return IntegrationEvidenceState.PARTIALLY_RUN
    }

    private fun deriveSingleStageEvidenceState(status: ScenarioOutcomeStatus?): IntegrationEvidenceState =
        when (status) {
            null -> IntegrationEvidenceState.NOT_RUN
            ScenarioOutcomeStatus.PASSED -> IntegrationEvidenceState.EVIDENCED
            ScenarioOutcomeStatus.FAILED, ScenarioOutcomeStatus.TIMED_OUT -> IntegrationEvidenceState.FAILED
            ScenarioOutcomeStatus.SKIPPED -> IntegrationEvidenceState.PARTIALLY_RUN
        }

    private fun deriveEvidenceStateFromStatuses(
        statuses: List<ScenarioOutcomeStatus?>
    ): IntegrationEvidenceState {
        if (statuses.all { it == null }) return IntegrationEvidenceState.NOT_RUN
        if (statuses.any { it == ScenarioOutcomeStatus.FAILED || it == ScenarioOutcomeStatus.TIMED_OUT }) {
            return IntegrationEvidenceState.FAILED
        }
        val recordedStatuses = statuses.filterNotNull()
        val hasMissingStatuses = statuses.any { it == null }
        val allPassed = recordedStatuses.isNotEmpty() && recordedStatuses.all { it == ScenarioOutcomeStatus.PASSED }
        return when {
            allPassed && !hasMissingStatuses -> IntegrationEvidenceState.EVIDENCED
            else -> IntegrationEvidenceState.PARTIALLY_RUN
        }
    }

    companion object {

        /** Stable schema version for [toWireMap]. */
        const val SCHEMA_VERSION = "1.0"

        val PARTICIPATION_READY_STAGES: Set<DualRepoE2EVerificationStage> = setOf(
            DualRepoE2EVerificationStage.DEVICE_REGISTER,
            DualRepoE2EVerificationStage.CAPABILITY_REPORT,
            DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION,
            DualRepoE2EVerificationStage.DELEGATED_EXECUTION_AVAILABLE
        )

        /**
         * Cross-repo contract anchors verified against V2 canonical module names used by prior
         * Android↔V2 boundary contracts. These are semantic integration anchors, not runtime
         * file-existence assertions.
         */
        val CANONICAL_UPLINK_PATH_MAP: Map<String, String> = mapOf(
            "android_result_uplink" to "goal_execution_result/device_execution_event",
            "v2_result_ingress" to "core/unified_result_ingress.py",
            "v2_android_truth_ssot" to "core/v2_android_truth_ssot.py",
            "v2_truth_reconciliation" to "core/unified_runtime_truth_ingress.py",
            "v2_closure_outward_compile" to "core/outward_runtime_truth.py::compile_outward_truth"
        )

        /** Human-readable description of this report surface. */
        const val DESCRIPTION =
            "Dual-repo E2E verification report covering the complete 7-stage participant " +
                "lifecycle chain: device_register → capability_report → " +
                "task_assignment_reception → delegated_execution_available → " +
                "task_result_return → reconnect_recovery → degraded_outcome_recording " +
                "(PR-72). Produces V2-consumable structured artifacts distinguishing " +
                "real-device, emulator, simulated, partial, stale, absent, and " +
                "blocked-no-device E2E closure states."

        /** PR number that introduced this report surface. */
        const val INTRODUCED_PR = 72
    }
}
