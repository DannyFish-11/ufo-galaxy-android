package com.ufo.galaxy.runtime

import java.util.UUID

/**
 * PR-70 (Android) — Real-device participant verification bridge for dual-repo readiness
 * and acceptance.
 *
 * [RealDeviceParticipantVerificationBridge] is the canonical Android-side entry point for
 * recording per-scenario participant verification outcomes and producing structured,
 * V2-consumable [RealDeviceParticipantVerificationReport] artifacts.  It closes the gap
 * identified in the PR-70 problem statement:
 *
 * > *"The dual-repo system currently lacks a machine-readable bridge between Android device
 * > runtime observations and V2 readiness/acceptance verdict surfaces."*
 *
 * ## Background
 *
 * Prior to PR-70, Android participant verification evidence existed only as:
 *  - ADB/logcat text output (not structured).
 *  - Local unit-test pass/fail results (not exportable to V2).
 *  - Partial readiness snapshots ([AndroidDelegatedRuntimeAuditSnapshot]) that did not
 *    distinguish real-device from emulator or simulated scenarios.
 *
 * [RealDeviceParticipantVerificationBridge] bridges these runtime observations into a
 * structured [RealDeviceParticipantVerificationReport] with a typed
 * [RealDeviceParticipantVerificationArtifact] verdict that V2 can ingest.
 *
 * ## Verification flow
 *
 * 1. Create a bridge instance (optionally providing [deviceId], [participantId], and
 *    [verificationKind]).
 * 2. For each [RealDeviceVerificationScenario], call [recordScenarioOutcome] with the
 *    observed [ScenarioOutcomeStatus] (and optional reason).
 * 3. Optionally set [lifecycleTruthState] if a [ParticipantLifecycleTruthReport] is available.
 * 4. Call [buildReport] to obtain the full [RealDeviceParticipantVerificationReport].
 * 5. Inspect [RealDeviceParticipantVerificationReport.overallVerificationArtifact] or
 *    [RealDeviceParticipantVerificationReport.toWireMap] for V2 export.
 *
 * ## Artifact derivation rules
 *
 * [evaluateArtifact] applies the following rules in priority order:
 *
 * 1. **No device** — if [verificationKind] is [RealDeviceVerificationKind.NO_DEVICE] →
 *    [RealDeviceParticipantVerificationArtifact.VerificationBlockedNoDevice].
 *
 * 2. **Absent** — if no scenario outcomes have been recorded →
 *    [RealDeviceParticipantVerificationArtifact.VerificationAbsent].
 *
 * 3. **Stale** — if [verificationKind] is [RealDeviceVerificationKind.STALE] →
 *    [RealDeviceParticipantVerificationArtifact.StaleVerification].
 *
 * 4. **Simulated** — if [verificationKind] is [RealDeviceVerificationKind.SIMULATED]
 *    and all required scenarios passed →
 *    [RealDeviceParticipantVerificationArtifact.SimulatedVerifiedOnly].
 *
 * 5. **Incomplete (any kind)** — if any required scenario did not pass →
 *    [RealDeviceParticipantVerificationArtifact.PartiallyVerified].
 *
 * 6. **Emulator** — if [verificationKind] is [RealDeviceVerificationKind.EMULATOR] and all
 *    required scenarios passed →
 *    [RealDeviceParticipantVerificationArtifact.EmulatorVerifiedOnly].
 *
 * 7. **Real device** — if [verificationKind] is [RealDeviceVerificationKind.REAL_DEVICE]
 *    and all required scenarios passed →
 *    [RealDeviceParticipantVerificationArtifact.RealDeviceFullyVerified].
 *
 * 8. **Fallback** — any remaining case →
 *    [RealDeviceParticipantVerificationArtifact.VerificationAbsent].
 *
 * ## Integration with V2
 *
 * [RealDeviceParticipantVerificationReport.toWireMap] produces a flat `Map<String, Any?>`
 * with schema version [RealDeviceParticipantVerificationReport.SCHEMA_VERSION] that V2
 * Python code can directly ingest:
 *
 * ```python
 * bridge = RealDeviceParticipantVerificationBridge(device_id, participant_id, "real_device")
 * bridge.record_scenario_outcome("registration", "passed")
 * # …
 * report = bridge.build_report()
 * wire = report.to_wire_map()
 * # wire["artifact_tag"] == "real_device_fully_verified"
 * # wire["is_real_device_verified"] == True
 * ```
 *
 * @property deviceId           Hardware device identifier; blank when [verificationKind] is
 *                              [RealDeviceVerificationKind.NO_DEVICE].
 * @property participantId      Participant node identifier; may be blank for pre-registration runs.
 * @property verificationKind   The [RealDeviceVerificationKind] for this bridge session.
 *
 * @see RealDeviceParticipantVerificationReport
 * @see RealDeviceParticipantVerificationArtifact
 * @see RealDeviceVerificationKind
 * @see RealDeviceVerificationScenario
 */
class RealDeviceParticipantVerificationBridge(
    val deviceId: String = "",
    val participantId: String = "",
    val verificationKind: RealDeviceVerificationKind = RealDeviceVerificationKind.NO_DEVICE
) {

    // ── Internal state ────────────────────────────────────────────────────────

    private val scenarioOutcomes: MutableMap<RealDeviceVerificationScenario,
        RealDeviceParticipantVerificationReport.ScenarioOutcome> = mutableMapOf()

    /** Optional [ParticipantLifecycleTruthState] observed during verification. */
    var lifecycleTruthState: ParticipantLifecycleTruthState? = null

    // ── Scenario recording API ────────────────────────────────────────────────

    /**
     * Records the outcome for [scenario].
     *
     * If [scenario] was already recorded, the previous outcome is replaced.
     *
     * @param scenario The [RealDeviceVerificationScenario] to record.
     * @param status   The [ScenarioOutcomeStatus] observed for this scenario.
     * @param reason   Optional human-readable explanation; required when [status] is not
     *                 [ScenarioOutcomeStatus.PASSED].
     */
    fun recordScenarioOutcome(
        scenario: RealDeviceVerificationScenario,
        status: ScenarioOutcomeStatus,
        reason: String? = null
    ) {
        scenarioOutcomes[scenario] = RealDeviceParticipantVerificationReport.ScenarioOutcome(
            scenario = scenario,
            outcomeStatus = status,
            reason = reason
        )
    }

    /**
     * Returns the current [ScenarioOutcomeStatus] for [scenario], or `null` if no outcome
     * has been recorded.
     */
    fun getScenarioStatus(scenario: RealDeviceVerificationScenario): ScenarioOutcomeStatus? =
        scenarioOutcomes[scenario]?.outcomeStatus

    /**
     * Returns the currently recorded outcome map as an immutable snapshot.
     */
    fun getScenarioOutcomes(): Map<RealDeviceVerificationScenario,
        RealDeviceParticipantVerificationReport.ScenarioOutcome> =
        scenarioOutcomes.toMap()

    /**
     * Clears all recorded scenario outcomes and the [lifecycleTruthState].
     *
     * After calling this, a subsequent [buildReport] will produce a
     * [RealDeviceParticipantVerificationArtifact.VerificationAbsent] artifact.
     */
    fun clearAllOutcomes() {
        scenarioOutcomes.clear()
        lifecycleTruthState = null
    }

    // ── Artifact derivation ───────────────────────────────────────────────────

    /**
     * Evaluates the current scenario outcomes and [verificationKind] to produce a typed
     * [RealDeviceParticipantVerificationArtifact].
     *
     * @param reportId  Stable identifier for the report; defaults to a fresh UUID.
     * @return The typed artifact for the current bridge state.
     */
    fun evaluateArtifact(
        reportId: String = UUID.randomUUID().toString()
    ): RealDeviceParticipantVerificationArtifact {
        // Rule 1: No device present
        if (verificationKind == RealDeviceVerificationKind.NO_DEVICE) {
            return RealDeviceParticipantVerificationArtifact.VerificationBlockedNoDevice(
                blockedReason = REASON_NO_DEVICE_DEFAULT
            )
        }

        // Rule 2: No scenarios recorded at all
        if (scenarioOutcomes.isEmpty()) {
            return RealDeviceParticipantVerificationArtifact.VerificationAbsent(
                deviceId = deviceId,
                reason = REASON_NO_OUTCOMES_RECORDED
            )
        }

        // Rule 3: Stale evidence
        if (verificationKind == RealDeviceVerificationKind.STALE) {
            return RealDeviceParticipantVerificationArtifact.StaleVerification(
                deviceId = deviceId,
                reportId = reportId,
                staleReason = REASON_STALE_DEFAULT,
                reportedAtMs = System.currentTimeMillis()
            )
        }

        val passedScenarios = scenarioOutcomes
            .filter { it.value.outcomeStatus == ScenarioOutcomeStatus.PASSED }
            .keys
            .toSet()
        val missingRequired = RealDeviceVerificationScenario.REQUIRED_SCENARIOS - passedScenarios

        // Rule 4: Simulated kind — check required coverage first
        if (verificationKind == RealDeviceVerificationKind.SIMULATED) {
            return if (missingRequired.isEmpty()) {
                RealDeviceParticipantVerificationArtifact.SimulatedVerifiedOnly(
                    reportId = reportId,
                    passedScenarios = passedScenarios
                )
            } else {
                RealDeviceParticipantVerificationArtifact.PartiallyVerified(
                    deviceId = deviceId,
                    reportId = reportId,
                    passedScenarios = passedScenarios,
                    missingRequiredScenarios = missingRequired,
                    partialReason = buildPartialReason(missingRequired)
                )
            }
        }

        // Rule 5: Any required scenario did not pass → PartiallyVerified
        if (missingRequired.isNotEmpty()) {
            return RealDeviceParticipantVerificationArtifact.PartiallyVerified(
                deviceId = deviceId,
                reportId = reportId,
                passedScenarios = passedScenarios,
                missingRequiredScenarios = missingRequired,
                partialReason = buildPartialReason(missingRequired)
            )
        }

        // Rule 6: Emulator — all required scenarios passed
        if (verificationKind == RealDeviceVerificationKind.EMULATOR) {
            return RealDeviceParticipantVerificationArtifact.EmulatorVerifiedOnly(
                deviceId = deviceId,
                participantId = participantId,
                reportId = reportId,
                passedScenarios = passedScenarios
            )
        }

        // Rule 7: Real device — all required scenarios passed
        if (verificationKind == RealDeviceVerificationKind.REAL_DEVICE) {
            return RealDeviceParticipantVerificationArtifact.RealDeviceFullyVerified(
                deviceId = deviceId,
                participantId = participantId,
                reportId = reportId,
                passedScenarios = passedScenarios
            )
        }

        // Rule 8: Fallback
        return RealDeviceParticipantVerificationArtifact.VerificationAbsent(
            deviceId = deviceId,
            reason = REASON_INCOMPLETE_DEFAULT
        )
    }

    // ── Report construction ───────────────────────────────────────────────────

    /**
     * Builds a complete [RealDeviceParticipantVerificationReport] from the current bridge
     * state.
     *
     * @param nowMs  Current epoch-millisecond timestamp (defaults to [System.currentTimeMillis]).
     * @return A snapshot of the current verification state as a structured report.
     */
    fun buildReport(nowMs: Long = System.currentTimeMillis()): RealDeviceParticipantVerificationReport {
        val reportId = UUID.randomUUID().toString()
        val artifact = evaluateArtifact(reportId = reportId)
        return RealDeviceParticipantVerificationReport(
            reportId = reportId,
            deviceId = deviceId,
            participantId = participantId,
            verificationKind = verificationKind,
            scenarioOutcomes = scenarioOutcomes.toMap(),
            overallVerificationArtifact = artifact,
            lifecycleTruthState = lifecycleTruthState,
            reportedAtMs = nowMs
        )
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun buildPartialReason(
        missing: Set<RealDeviceVerificationScenario>
    ): String = "Required scenarios not passed: ${missing.joinToString { it.wireValue }}"

    // ── Public API ────────────────────────────────────────────────────────────

    companion object {

        // Artifact tag constants ──────────────────────────────────────────────

        /** Artifact tag for [RealDeviceParticipantVerificationArtifact.RealDeviceFullyVerified]. */
        const val ARTIFACT_REAL_DEVICE_FULLY_VERIFIED = "real_device_fully_verified"

        /** Artifact tag for [RealDeviceParticipantVerificationArtifact.EmulatorVerifiedOnly]. */
        const val ARTIFACT_EMULATOR_VERIFIED_ONLY = "emulator_verified_only"

        /** Artifact tag for [RealDeviceParticipantVerificationArtifact.SimulatedVerifiedOnly]. */
        const val ARTIFACT_SIMULATED_VERIFIED_ONLY = "simulated_verified_only"

        /** Artifact tag for [RealDeviceParticipantVerificationArtifact.PartiallyVerified]. */
        const val ARTIFACT_PARTIALLY_VERIFIED = "partially_verified"

        /** Artifact tag for [RealDeviceParticipantVerificationArtifact.StaleVerification]. */
        const val ARTIFACT_STALE_VERIFICATION = "stale_verification"

        /** Artifact tag for [RealDeviceParticipantVerificationArtifact.VerificationAbsent]. */
        const val ARTIFACT_VERIFICATION_ABSENT = "verification_absent"

        /** Artifact tag for [RealDeviceParticipantVerificationArtifact.VerificationBlockedNoDevice]. */
        const val ARTIFACT_VERIFICATION_BLOCKED_NO_DEVICE = "verification_blocked_no_device"

        // Default reason strings ──────────────────────────────────────────────

        /** Default reason used when no physical device was found. */
        const val REASON_NO_DEVICE_DEFAULT =
            "No physical Android device was present; verification cannot proceed without a device"

        /** Default reason used when no scenario outcomes have been recorded. */
        const val REASON_NO_OUTCOMES_RECORDED =
            "No scenario outcomes have been recorded; verification has not been run"

        /** Default reason used for stale evidence. */
        const val REASON_STALE_DEFAULT =
            "Verification evidence is stale; a fresh real-device run is required"

        /** Default reason used when verification is incomplete. */
        const val REASON_INCOMPLETE_DEFAULT =
            "Verification is incomplete; not all required scenarios were covered"

        // Metadata constants ─────────────────────────────────────────────────

        /** PR number that introduced this bridge. */
        const val INTRODUCED_PR = 70

        /** Human-readable description of this bridge. */
        const val DESCRIPTION =
            "Real-device participant verification bridge for dual-repo readiness and acceptance " +
                "(PR-70). Records per-scenario Android participant verification outcomes on real " +
                "devices, emulators, and simulations, and exports structured V2-consumable " +
                "artifacts distinguishing real-device from emulator, simulated, stale, " +
                "incomplete, and absent evidence."

        /** All seven distinct artifact tag constants. */
        val ALL_ARTIFACT_TAGS: Set<String> = setOf(
            ARTIFACT_REAL_DEVICE_FULLY_VERIFIED,
            ARTIFACT_EMULATOR_VERIFIED_ONLY,
            ARTIFACT_SIMULATED_VERIFIED_ONLY,
            ARTIFACT_PARTIALLY_VERIFIED,
            ARTIFACT_STALE_VERIFICATION,
            ARTIFACT_VERIFICATION_ABSENT,
            ARTIFACT_VERIFICATION_BLOCKED_NO_DEVICE
        )
    }
}
