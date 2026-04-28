package com.ufo.galaxy.runtime

import java.util.UUID

/**
 * PR-72 (Android) — Dual-repo E2E verification harness for real Android device participation
 * in the complete 7-stage verification chain.
 *
 * [DualRepoE2EVerificationHarness] is the canonical Android-side entry point for orchestrating
 * and recording the full dual-repo E2E verification chain.  It addresses the critical gap
 * identified in the PR-72 problem statement:
 *
 * > *"The existing [RealDeviceParticipantVerificationBridge] (PR-70) is a bridge — it provides
 * > structured classification of verification evidence.  It does not equal a harness that
 * > actually proves the complete: device_register → capability_report →
 * > task_assignment_reception → delegated_execution_available → task_result_return →
 * > reconnect_recovery → degraded_outcome_recording closed loop."*
 *
 * ## Background: What the PR-70 bridge does NOT cover
 *
 * The PR-70 bridge covers three required scenarios:
 *  1. REGISTRATION — device registration.
 *  2. COMMAND_RECEPTION — task assignment reception.
 *  3. DELEGATED_EXECUTION_AVAILABILITY — execution runtime available.
 *
 * Two critical stages in the E2E chain are **not** covered by the bridge:
 *  - **CAPABILITY_REPORT** — the device must send a capability report to V2 so that V2 can
 *    route tasks.  Without this stage, V2 has no basis for task dispatch.
 *  - **TASK_RESULT_RETURN** — the task result must be returned to V2.  Without this stage,
 *    the result-feedback loop is not closed; tasks disappear into the device with no
 *    evidence that V2 received the outcome.
 *
 * [DualRepoE2EVerificationHarness] adds both stages to the required set and provides the
 * full 7-stage chain covering the complete dual-repo closed loop.
 *
 * ## Verification chain
 *
 * 1. **DEVICE_REGISTER** — participant registers with V2 gateway.
 * 2. **CAPABILITY_REPORT** — capability report sent to V2 gateway.
 * 3. **TASK_ASSIGNMENT_RECEPTION** — task assignment received and acknowledged from V2.
 * 4. **DELEGATED_EXECUTION_AVAILABLE** — delegated execution runtime available and dispatched.
 * 5. **TASK_RESULT_RETURN** — task result returned to V2 gateway.
 * 6. **RECONNECT_RECOVERY** *(optional)* — participant survived disconnect and reconnected.
 * 7. **DEGRADED_OUTCOME_RECORDING** *(optional)* — degraded/recovered outcome recorded.
 *
 * ## Usage
 *
 * 1. Create a harness instance, providing [deviceId], [participantId], and [verificationKind].
 * 2. For each [DualRepoE2EVerificationStage], call [recordStageOutcome] with the observed
 *    [ScenarioOutcomeStatus] (and optional reason).
 * 3. Optionally set [lifecycleTruthState] if a [ParticipantLifecycleTruthReport] is available.
 * 4. Call [buildReport] to obtain the full [DualRepoE2EVerificationReport].
 * 5. Inspect [DualRepoE2EVerificationReport.overallArtifact] or
 *    [DualRepoE2EVerificationReport.toWireMap] for V2 export.
 *
 * ## Artifact derivation rules
 *
 * [evaluateArtifact] applies the following rules in priority order:
 *
 * 1. **No device** — if [verificationKind] is [RealDeviceVerificationKind.NO_DEVICE] →
 *    [DualRepoE2EVerificationArtifact.E2EBlockedNoDevice].
 *
 * 2. **Absent** — if no stage outcomes have been recorded →
 *    [DualRepoE2EVerificationArtifact.E2EAbsent].
 *
 * 3. **Stale** — if [verificationKind] is [RealDeviceVerificationKind.STALE] →
 *    [DualRepoE2EVerificationArtifact.E2EStale].
 *
 * 4. **Simulated** — if [verificationKind] is [RealDeviceVerificationKind.SIMULATED]
 *    and all required stages passed →
 *    [DualRepoE2EVerificationArtifact.E2ESimulatedOnly].
 *
 * 5. **Partially verified (any kind)** — if any required stage did not pass →
 *    [DualRepoE2EVerificationArtifact.E2EPartiallyVerified].
 *
 * 6. **Emulator** — if [verificationKind] is [RealDeviceVerificationKind.EMULATOR] and
 *    all required stages passed →
 *    [DualRepoE2EVerificationArtifact.E2EEmulatorVerifiedOnly].
 *
 * 7. **Real device** — if [verificationKind] is [RealDeviceVerificationKind.REAL_DEVICE]
 *    and all required stages passed →
 *    [DualRepoE2EVerificationArtifact.E2EFullyVerified].
 *
 * 8. **Fallback** — any remaining case →
 *    [DualRepoE2EVerificationArtifact.E2EAbsent].
 *
 * ## Honest output when no device is available
 *
 * When [verificationKind] is [RealDeviceVerificationKind.NO_DEVICE], [evaluateArtifact]
 * **always** returns [DualRepoE2EVerificationArtifact.E2EBlockedNoDevice] regardless of
 * any recorded stage outcomes.  This is the expected, correct behaviour for CI environments
 * where no physical device is attached.  The harness never promotes a no-device state to
 * any verified or partially-verified artifact.
 *
 * ## Bridge wiring
 *
 * [buildReport] internally creates a [RealDeviceParticipantVerificationBridge] and populates
 * it with the overlapping stage outcomes (the five stages that have a corresponding bridge
 * scenario via [DualRepoE2EVerificationStage.toBridgeScenario]).  [CAPABILITY_REPORT] and
 * [TASK_RESULT_RETURN] are new to this harness and are not forwarded to the bridge.  The
 * resulting [RealDeviceParticipantVerificationReport] is embedded in the
 * [DualRepoE2EVerificationReport] for backward compatibility.
 *
 * @property deviceId         Hardware device identifier; blank when [verificationKind] is
 *                            [RealDeviceVerificationKind.NO_DEVICE].
 * @property participantId    Participant node identifier; may be blank for pre-registration runs.
 * @property verificationKind The [RealDeviceVerificationKind] for this harness session.
 *
 * @see DualRepoE2EVerificationReport
 * @see DualRepoE2EVerificationArtifact
 * @see DualRepoE2EVerificationStage
 * @see RealDeviceParticipantVerificationBridge
 */
class DualRepoE2EVerificationHarness(
    val deviceId: String = "",
    val participantId: String = "",
    val verificationKind: RealDeviceVerificationKind = RealDeviceVerificationKind.NO_DEVICE
) {

    // ── Internal state ────────────────────────────────────────────────────────

    private val stageOutcomes: MutableMap<DualRepoE2EVerificationStage, DualRepoE2EStageOutcome> =
        mutableMapOf()

    /** Optional [ParticipantLifecycleTruthState] observed during verification. */
    var lifecycleTruthState: ParticipantLifecycleTruthState? = null

    // ── Stage recording API ───────────────────────────────────────────────────

    /**
     * Records the outcome for [stage].
     *
     * If [stage] was already recorded, the previous outcome is replaced.
     *
     * @param stage   The [DualRepoE2EVerificationStage] to record.
     * @param status  The [ScenarioOutcomeStatus] observed for this stage.
     * @param reason  Optional human-readable explanation; expected when [status] is not
     *                [ScenarioOutcomeStatus.PASSED].
     */
    fun recordStageOutcome(
        stage: DualRepoE2EVerificationStage,
        status: ScenarioOutcomeStatus,
        reason: String? = null
    ) {
        stageOutcomes[stage] = DualRepoE2EStageOutcome(
            stage = stage,
            outcomeStatus = status,
            reason = reason
        )
    }

    /**
     * Returns the current [ScenarioOutcomeStatus] for [stage], or `null` if no outcome has
     * been recorded.
     */
    fun getStageStatus(stage: DualRepoE2EVerificationStage): ScenarioOutcomeStatus? =
        stageOutcomes[stage]?.outcomeStatus

    /**
     * Returns the currently recorded stage outcome map as an immutable snapshot.
     */
    fun getStageOutcomes(): Map<DualRepoE2EVerificationStage, DualRepoE2EStageOutcome> =
        stageOutcomes.toMap()

    /**
     * Clears all recorded stage outcomes and the [lifecycleTruthState].
     *
     * After calling this, a subsequent [buildReport] will produce a
     * [DualRepoE2EVerificationArtifact.E2EAbsent] artifact.
     */
    fun clearAllOutcomes() {
        stageOutcomes.clear()
        lifecycleTruthState = null
    }

    // ── Artifact derivation ───────────────────────────────────────────────────

    /**
     * Evaluates the current stage outcomes and [verificationKind] to produce a typed
     * [DualRepoE2EVerificationArtifact].
     *
     * @param reportId  Stable identifier for the report; defaults to a fresh UUID.
     * @return The typed artifact for the current harness state.
     */
    fun evaluateArtifact(
        reportId: String = UUID.randomUUID().toString()
    ): DualRepoE2EVerificationArtifact {
        // Rule 1: No device present — always blocked, regardless of recorded outcomes
        if (verificationKind == RealDeviceVerificationKind.NO_DEVICE) {
            return DualRepoE2EVerificationArtifact.E2EBlockedNoDevice(
                blockedReason = REASON_NO_DEVICE_DEFAULT
            )
        }

        // Rule 2: No stages recorded at all
        if (stageOutcomes.isEmpty()) {
            return DualRepoE2EVerificationArtifact.E2EAbsent(
                deviceId = deviceId,
                reason = REASON_NO_STAGES_RECORDED
            )
        }

        // Rule 3: Stale evidence
        if (verificationKind == RealDeviceVerificationKind.STALE) {
            return DualRepoE2EVerificationArtifact.E2EStale(
                deviceId = deviceId,
                reportId = reportId,
                staleReason = REASON_STALE_DEFAULT,
                reportedAtMs = System.currentTimeMillis()
            )
        }

        val passedStages = stageOutcomes
            .filter { it.value.outcomeStatus == ScenarioOutcomeStatus.PASSED }
            .keys
            .toSet()
        val missingRequired = DualRepoE2EVerificationStage.REQUIRED_STAGES - passedStages

        // Rule 4: Simulated kind — check required coverage first
        if (verificationKind == RealDeviceVerificationKind.SIMULATED) {
            return if (missingRequired.isEmpty()) {
                DualRepoE2EVerificationArtifact.E2ESimulatedOnly(
                    reportId = reportId,
                    passedStages = passedStages
                )
            } else {
                DualRepoE2EVerificationArtifact.E2EPartiallyVerified(
                    deviceId = deviceId,
                    reportId = reportId,
                    passedStages = passedStages,
                    missingRequiredStages = missingRequired,
                    partialReason = buildPartialReason(missingRequired)
                )
            }
        }

        // Rule 5: Any required stage did not pass → E2EPartiallyVerified
        if (missingRequired.isNotEmpty()) {
            return DualRepoE2EVerificationArtifact.E2EPartiallyVerified(
                deviceId = deviceId,
                reportId = reportId,
                passedStages = passedStages,
                missingRequiredStages = missingRequired,
                partialReason = buildPartialReason(missingRequired)
            )
        }

        // Rule 6: Emulator — all required stages passed
        if (verificationKind == RealDeviceVerificationKind.EMULATOR) {
            return DualRepoE2EVerificationArtifact.E2EEmulatorVerifiedOnly(
                deviceId = deviceId,
                participantId = participantId,
                reportId = reportId,
                passedStages = passedStages
            )
        }

        // Rule 7: Real device — all required stages passed
        if (verificationKind == RealDeviceVerificationKind.REAL_DEVICE) {
            return DualRepoE2EVerificationArtifact.E2EFullyVerified(
                deviceId = deviceId,
                participantId = participantId,
                reportId = reportId,
                passedStages = passedStages
            )
        }

        // Rule 8: Fallback
        return DualRepoE2EVerificationArtifact.E2EAbsent(
            deviceId = deviceId,
            reason = REASON_INCOMPLETE_DEFAULT
        )
    }

    // ── Report construction ───────────────────────────────────────────────────

    /**
     * Builds a complete [DualRepoE2EVerificationReport] from the current harness state.
     *
     * Internally creates and populates a [RealDeviceParticipantVerificationBridge] from the
     * overlapping stage outcomes (those with a bridge scenario mapping) and embeds the
     * resulting [RealDeviceParticipantVerificationReport] in the output for backward
     * compatibility.
     *
     * @param nowMs  Current epoch-millisecond timestamp (defaults to [System.currentTimeMillis]).
     * @return A snapshot of the current E2E verification state as a structured report.
     */
    fun buildReport(nowMs: Long = System.currentTimeMillis()): DualRepoE2EVerificationReport {
        val reportId = UUID.randomUUID().toString()
        val artifact = evaluateArtifact(reportId = reportId)
        val bridgeReport = buildBridgeReport(nowMs = nowMs)
        return DualRepoE2EVerificationReport(
            reportId = reportId,
            deviceId = deviceId,
            participantId = participantId,
            verificationKind = verificationKind,
            stageOutcomes = stageOutcomes.toMap(),
            overallArtifact = artifact,
            bridgeReport = bridgeReport,
            lifecycleTruthState = lifecycleTruthState,
            reportedAtMs = nowMs
        )
    }

    /**
     * Builds a [RealDeviceParticipantVerificationReport] from the subset of stage outcomes
     * that map to existing [RealDeviceVerificationScenario] entries.
     *
     * [CAPABILITY_REPORT] and [TASK_RESULT_RETURN] are new to this harness and are not
     * forwarded to the bridge.
     *
     * @param nowMs  Current epoch-millisecond timestamp.
     * @return A bridge-level report reflecting the overlapping stage outcomes.
     */
    fun buildBridgeReport(
        nowMs: Long = System.currentTimeMillis()
    ): RealDeviceParticipantVerificationReport {
        val bridge = RealDeviceParticipantVerificationBridge(
            deviceId = deviceId,
            participantId = participantId,
            verificationKind = verificationKind
        )
        bridge.lifecycleTruthState = lifecycleTruthState
        stageOutcomes.forEach { (stage, outcome) ->
            val scenario = stage.toBridgeScenario() ?: return@forEach
            bridge.recordScenarioOutcome(scenario, outcome.outcomeStatus, outcome.reason)
        }
        return bridge.buildReport(nowMs = nowMs)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun buildPartialReason(
        missing: Set<DualRepoE2EVerificationStage>
    ): String = "Required E2E stages not passed: ${missing.joinToString { it.wireValue }}"

    // ── Public API ────────────────────────────────────────────────────────────

    companion object {

        // Artifact tag constants ──────────────────────────────────────────────

        /** Artifact tag for [DualRepoE2EVerificationArtifact.E2EFullyVerified]. */
        const val ARTIFACT_E2E_FULLY_VERIFIED = "e2e_fully_verified"

        /** Artifact tag for [DualRepoE2EVerificationArtifact.E2EEmulatorVerifiedOnly]. */
        const val ARTIFACT_E2E_EMULATOR_VERIFIED_ONLY = "e2e_emulator_verified_only"

        /** Artifact tag for [DualRepoE2EVerificationArtifact.E2ESimulatedOnly]. */
        const val ARTIFACT_E2E_SIMULATED_ONLY = "e2e_simulated_only"

        /** Artifact tag for [DualRepoE2EVerificationArtifact.E2EPartiallyVerified]. */
        const val ARTIFACT_E2E_PARTIALLY_VERIFIED = "e2e_partially_verified"

        /** Artifact tag for [DualRepoE2EVerificationArtifact.E2EStale]. */
        const val ARTIFACT_E2E_STALE = "e2e_stale"

        /** Artifact tag for [DualRepoE2EVerificationArtifact.E2EAbsent]. */
        const val ARTIFACT_E2E_ABSENT = "e2e_absent"

        /** Artifact tag for [DualRepoE2EVerificationArtifact.E2EBlockedNoDevice]. */
        const val ARTIFACT_E2E_BLOCKED_NO_DEVICE = "e2e_blocked_no_device"

        // Default reason strings ──────────────────────────────────────────────

        /** Default reason used when no physical device was found. */
        const val REASON_NO_DEVICE_DEFAULT =
            "No physical Android device was present; E2E verification cannot proceed without a device"

        /** Default reason used when no stage outcomes have been recorded. */
        const val REASON_NO_STAGES_RECORDED =
            "No E2E stage outcomes have been recorded; verification has not been run"

        /** Default reason used for stale evidence. */
        const val REASON_STALE_DEFAULT =
            "E2E verification evidence is stale; a fresh real-device run is required"

        /** Default reason used when verification is incomplete. */
        const val REASON_INCOMPLETE_DEFAULT =
            "E2E verification is incomplete; not all required stages were covered"

        // Metadata constants ─────────────────────────────────────────────────

        /** PR number that introduced this harness. */
        const val INTRODUCED_PR = 72

        /** Human-readable description of this harness. */
        const val DESCRIPTION =
            "Dual-repo E2E verification harness for real Android device participation in " +
                "the complete 7-stage verification chain (PR-72). Covers " +
                "device_register → capability_report → task_assignment_reception → " +
                "delegated_execution_available → task_result_return → reconnect_recovery → " +
                "degraded_outcome_recording. Produces V2-consumable structured artifacts " +
                "distinguishing real-device E2E closure from emulator, simulated, partial, " +
                "stale, absent, and blocked-no-device states. Honestly outputs " +
                "e2e_blocked_no_device when no physical device is present."

        /** All seven distinct artifact tag constants. */
        val ALL_ARTIFACT_TAGS: Set<String> = setOf(
            ARTIFACT_E2E_FULLY_VERIFIED,
            ARTIFACT_E2E_EMULATOR_VERIFIED_ONLY,
            ARTIFACT_E2E_SIMULATED_ONLY,
            ARTIFACT_E2E_PARTIALLY_VERIFIED,
            ARTIFACT_E2E_STALE,
            ARTIFACT_E2E_ABSENT,
            ARTIFACT_E2E_BLOCKED_NO_DEVICE
        )
    }
}
