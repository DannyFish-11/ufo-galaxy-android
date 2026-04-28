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
     * | `stage_outcomes`                 | Map<String, String> | Stage wireValue → outcome wireValue                    |
     * | `stage_reasons`                  | Map<String, String> | Stage wireValue → reason (only when present)           |
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
            "stage_outcomes" to stageOutcomesWire,
            "stage_reasons" to stageReasonsWire,
            "bridge_artifact_tag" to bridgeReport.overallVerificationArtifact.artifactTag,
            "bridge_is_real_device_verified" to bridgeReport.isRealDeviceVerified
        )
    }

    companion object {

        /** Stable schema version for [toWireMap]. */
        const val SCHEMA_VERSION = "1.0"

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
