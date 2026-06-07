package com.ufo.galaxy.runtime

import java.util.UUID

/**
 * PR-70 (Android) — Structured real-device participant verification report for cross-repo
 * consumption by V2 readiness / acceptance systems.
 *
 * [RealDeviceParticipantVerificationReport] is the canonical Android-side artifact that
 * proves (or explicitly refutes) that an Android participant's lifecycle scenarios have
 * been observed on a real physical device.  It is the primary output of
 * [RealDeviceParticipantVerificationBridge.buildReport].
 *
 * ## Problem addressed
 *
 * Prior to PR-70, the dual-repo system lacked a machine-readable bridge between Android
 * device runtime observations and V2 readiness/acceptance verdict surfaces.  Evidence of
 * participant verification existed only in logs or partial test reports, with no structured
 * classification that V2 could consume programmatically.
 *
 * [RealDeviceParticipantVerificationReport] closes this gap by providing:
 *  - A [verificationKind] field that explicitly classifies the evidence provenance
 *    ([RealDeviceVerificationKind.REAL_DEVICE] / [EMULATOR] / [SIMULATED] / [STALE] /
 *    [INCOMPLETE] / [NO_DEVICE]).
 *  - Per-scenario pass/fail evidence in [scenarioOutcomes].
 *  - An [overallVerificationArtifact] — a typed [RealDeviceParticipantVerificationArtifact]
 *    that V2 can inspect without parsing the raw scenario map.
 *  - A [toWireMap] export producing a stable, schema-versioned flat map for cross-repo
 *    serialization.
 *
 * ## V2 consumption contract
 *
 * A V2 consumer (acceptance gate, governance layer, CI pipeline) must:
 *
 * 1. Check [isCrossRepoConsumable] — if `false` the report must not be used for acceptance
 *    or governance decisions.
 * 2. Check [verificationKind] — only [RealDeviceVerificationKind.REAL_DEVICE] provides
 *    authoritative evidence for hardware-gated acceptance.
 * 3. Inspect [overallVerificationArtifact] — the typed artifact carries the canonical
 *    verdict with wire-stable [RealDeviceParticipantVerificationArtifact.artifactTag].
 * 4. Use [toWireMap] for stable serialized export.
 *
 * ## Prohibition on optimistic promotion
 *
 * This report **explicitly prohibits**:
 *  - Reporting [RealDeviceVerificationKind.REAL_DEVICE] when no physical device was present.
 *  - Reporting partial scenario coverage as fully verified.
 *  - Treating [EMULATOR] or [SIMULATED] evidence as equivalent to [REAL_DEVICE] for
 *    hardware-gated decisions.
 *  - Treating [STALE] evidence as current without a fresh verification run.
 *
 * @property reportId                   Stable UUID identifier for this report instance.
 * @property deviceId                   Hardware device identifier; blank if no device was present.
 * @property participantId              Participant node identifier; blank if participant was not registered.
 * @property verificationKind           [RealDeviceVerificationKind] classifying the provenance of this report.
 * @property scenarioOutcomes           Per-scenario outcome map; keys are [RealDeviceVerificationScenario].
 * @property overallVerificationArtifact Typed [RealDeviceParticipantVerificationArtifact] for the overall verdict.
 * @property lifecycleTruthState        Optional [ParticipantLifecycleTruthState] observed at verification time.
 * @property reportedAtMs               Epoch-millisecond timestamp when this report was produced.
 * @property schemaVersion              Report schema version; currently `"1.0"`.
 *
 * @see RealDeviceParticipantVerificationBridge
 * @see RealDeviceParticipantVerificationArtifact
 * @see RealDeviceVerificationKind
 * @see RealDeviceVerificationScenario
 */
data class RealDeviceParticipantVerificationReport(
    val reportId: String,
    val deviceId: String,
    val participantId: String,
    val verificationKind: RealDeviceVerificationKind,
    val scenarioOutcomes: Map<RealDeviceVerificationScenario, ScenarioOutcome>,
    val overallVerificationArtifact: RealDeviceParticipantVerificationArtifact,
    val lifecycleTruthState: ParticipantLifecycleTruthState?,
    val reportedAtMs: Long,
    val schemaVersion: String = SCHEMA_VERSION
) {

    /**
     * Returns `true` when this report can be safely consumed by V2 acceptance/readiness
     * systems.
     *
     * A report is cross-repo consumable only when:
     *  - [verificationKind] is [RealDeviceVerificationKind.REAL_DEVICE] or
     *    [RealDeviceVerificationKind.EMULATOR].
     *  - [overallVerificationArtifact] is not [RealDeviceParticipantVerificationArtifact.VerificationAbsent].
     *  - [deviceId] is not blank.
     */
    val isCrossRepoConsumable: Boolean
        get() = RealDeviceVerificationKind.isUsableEvidence(verificationKind)
            && overallVerificationArtifact !is RealDeviceParticipantVerificationArtifact.VerificationAbsent
            && deviceId.isNotBlank()

    /**
     * Returns `true` when this report carries authoritative real-device evidence
     * (i.e. [verificationKind] is [RealDeviceVerificationKind.REAL_DEVICE] and all
     * required scenarios passed).
     */
    val isRealDeviceVerified: Boolean
        get() = verificationKind == RealDeviceVerificationKind.REAL_DEVICE
            && overallVerificationArtifact is RealDeviceParticipantVerificationArtifact.RealDeviceFullyVerified

    /**
     * Produces a stable, V2-consumable key-value map for cross-repo serialization.
     *
     * The map uses snake_case keys and is safe for JSON serialization.  The schema is
     * stable at version [SCHEMA_VERSION].
     *
     * ## Top-level keys
     *
     * | Key                          | Type                | Description                                           |
     * |------------------------------|---------------------|-------------------------------------------------------|
     * | `schema_version`             | String              | Always [SCHEMA_VERSION].                              |
     * | `report_id`                  | String              | [reportId]                                            |
     * | `device_id`                  | String              | [deviceId]                                            |
     * | `participant_id`             | String              | [participantId]                                       |
     * | `verification_kind`          | String              | [verificationKind.wireValue]                          |
     * | `artifact_tag`               | String              | [overallVerificationArtifact.artifactTag]             |
     * | `lifecycle_truth_state`      | String?             | [lifecycleTruthState.wireValue] or `null`             |
     * | `reported_at_ms`             | Long                | [reportedAtMs]                                        |
     * | `is_cross_repo_consumable`   | Boolean             | [isCrossRepoConsumable]                               |
     * | `is_real_device_verified`    | Boolean             | [isRealDeviceVerified]                                |
     * | `scenario_outcomes`          | Map<String, String> | Scenario wireValue → outcome wireValue                |
     * | `scenario_reasons`           | Map<String, String> | Scenario wireValue → reason (only when present)       |
     */
    fun toWireMap(): Map<String, Any?> {
        val scenarioOutcomesWire: Map<String, String> = scenarioOutcomes
            .mapKeys { it.key.wireValue }
            .mapValues { it.value.outcomeStatus.wireValue }
        val scenarioReasonsWire: Map<String, String> = scenarioOutcomes
            .filter { it.value.reason != null }
            .mapKeys { it.key.wireValue }
            .mapValues { it.value.reason!! }
        return mapOf(
            "schema_version" to schemaVersion,
            "report_id" to reportId,
            "device_id" to deviceId,
            "participant_id" to participantId,
            "verification_kind" to verificationKind.wireValue,
            "artifact_tag" to overallVerificationArtifact.artifactTag,
            "lifecycle_truth_state" to lifecycleTruthState?.wireValue,
            "reported_at_ms" to reportedAtMs,
            "is_cross_repo_consumable" to isCrossRepoConsumable,
            "is_real_device_verified" to isRealDeviceVerified,
            "scenario_outcomes" to scenarioOutcomesWire,
            "scenario_reasons" to scenarioReasonsWire
        )
    }

    // ── Supporting types ──────────────────────────────────────────────────────

    /**
     * The pass/fail outcome for a single [RealDeviceVerificationScenario].
     *
     * @property scenario       The scenario this outcome describes.
     * @property outcomeStatus  The [ScenarioOutcomeStatus] for this scenario.
     * @property reason         Human-readable explanation when [outcomeStatus] is not
     *                          [ScenarioOutcomeStatus.PASSED]; `null` when passing.
     */
    data class ScenarioOutcome(
        val scenario: RealDeviceVerificationScenario,
        val outcomeStatus: ScenarioOutcomeStatus,
        val reason: String? = null
    )

    companion object {

        /** Stable schema version for [toWireMap]. */
        const val SCHEMA_VERSION = "1.0"

        /** Human-readable description of this report surface. */
        const val DESCRIPTION =
            "Structured real-device participant verification report for cross-repo consumption " +
                "(PR-70). Classifies Android participant verification evidence as real-device, " +
                "emulator, simulated, stale, incomplete, or absent, and exports a V2-consumable " +
                "wire map with per-scenario outcomes."

        /** PR number that introduced this report surface. */
        const val INTRODUCED_PR = 70
    }
}

// ── Supporting enumerations ───────────────────────────────────────────────────

/**
 * Pass/fail status for a single [RealDeviceVerificationScenario].
 *
 * @property wireValue  Stable lowercase string for wire transport.
 */
enum class ScenarioOutcomeStatus(val wireValue: String) {

    /**
     * The scenario passed: the expected participant behaviour was observed and confirmed.
     */
    PASSED("passed"),

    /**
     * The scenario failed: the expected participant behaviour was not observed, or an
     * error occurred during the scenario.
     */
    FAILED("failed"),

    /**
     * The scenario was not run: it was either not applicable to this verification session,
     * or was intentionally skipped (e.g. reconnect scenario skipped when no recovery
     * capability is available).
     */
    SKIPPED("skipped"),

    /**
     * The scenario was started but did not complete within the observation window
     * (e.g. a timeout occurred).
     */
    TIMED_OUT("timed_out");

    companion object {

        /**
         * Returns the [ScenarioOutcomeStatus] matching [value], or `null`.
         *
         * @param value  The wire-value string to look up.
         */
        fun fromWireValue(value: String?): ScenarioOutcomeStatus? =
            entries.firstOrNull { it.wireValue == value }

        /** All stable wire values for this enum, for validation purposes. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}
