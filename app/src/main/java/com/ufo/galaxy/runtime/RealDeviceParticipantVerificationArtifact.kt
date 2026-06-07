package com.ufo.galaxy.runtime

/**
 * PR-70 (Android) — Sealed class representing the typed outcome of an Android
 * real-device participant verification run.
 *
 * [RealDeviceParticipantVerificationArtifact] is the typed output of
 * [RealDeviceParticipantVerificationBridge.evaluateArtifact].  Every V2 acceptance gate
 * that needs to absorb Android real-device participant verification evidence must inspect
 * the returned artifact before making a hardware-gated acceptance decision.
 *
 * ## Artifact semantics
 *
 * Each concrete subtype corresponds to a named verification outcome:
 *
 * | Subtype                                | Artifact tag                              | Description                                                                         |
 * |----------------------------------------|-------------------------------------------|-------------------------------------------------------------------------------------|
 * | [RealDeviceFullyVerified]              | `real_device_fully_verified`              | All required scenarios passed on a real physical device.                            |
 * | [EmulatorVerifiedOnly]                 | `emulator_verified_only`                  | All required scenarios passed on an emulator; no real device used.                  |
 * | [SimulatedVerifiedOnly]               | `simulated_verified_only`                 | Scenarios verified in software simulation only; no Android runtime used.            |
 * | [PartiallyVerified]                    | `partially_verified`                      | Some (but not all) required scenarios passed; verification is incomplete.           |
 * | [StaleVerification]                    | `stale_verification`                      | Previously verified evidence is too old; a fresh run is required.                   |
 * | [VerificationAbsent]                   | `verification_absent`                     | No verification has been run or evidence was explicitly cleared.                    |
 * | [VerificationBlockedNoDevice]          | `verification_blocked_no_device`          | No physical device was present; verification could not proceed.                     |
 *
 * ## Consumption contract
 *
 * Only [RealDeviceFullyVerified] provides authoritative real-device evidence that V2 can
 * use for hardware-gated acceptance decisions.  All other artifacts represent lower-
 * confidence or blocking conditions:
 *
 *  - [EmulatorVerifiedOnly]: V2 may use as advisory evidence but not for hardware-gated gates.
 *  - [SimulatedVerifiedOnly]: Advisory only; not hardware or emulator confirmed.
 *  - [PartiallyVerified]: Blocking — missing required scenario coverage must be resolved.
 *  - [StaleVerification]: Blocking — a fresh verification run is required.
 *  - [VerificationAbsent]: Blocking — no verification has been run.
 *  - [VerificationBlockedNoDevice]: Blocking — device must be connected before re-running.
 *
 * @see RealDeviceParticipantVerificationBridge
 * @see RealDeviceVerificationKind
 * @see RealDeviceVerificationScenario
 */
sealed class RealDeviceParticipantVerificationArtifact {

    /**
     * Stable wire tag identifying the verification artifact semantic for this instance.
     *
     * Matches one of the [RealDeviceParticipantVerificationBridge] `ARTIFACT_*` constants.
     */
    abstract val artifactTag: String

    // ── RealDeviceFullyVerified ───────────────────────────────────────────────

    /**
     * All required [RealDeviceVerificationScenario] values passed on a real physical Android
     * device.
     *
     * This is the only artifact that V2 accepts as authoritative real-device evidence for
     * hardware-gated acceptance decisions.
     *
     * Android-side semantic: [RealDeviceParticipantVerificationBridge.ARTIFACT_REAL_DEVICE_FULLY_VERIFIED].
     *
     * @property deviceId        The hardware device identifier.
     * @property participantId   The participant node identifier.
     * @property reportId        Stable identifier for the verification report.
     * @property passedScenarios The set of [RealDeviceVerificationScenario] values that passed.
     */
    data class RealDeviceFullyVerified(
        val deviceId: String,
        val participantId: String,
        val reportId: String,
        val passedScenarios: Set<RealDeviceVerificationScenario>
    ) : RealDeviceParticipantVerificationArtifact() {
        override val artifactTag: String =
            RealDeviceParticipantVerificationBridge.ARTIFACT_REAL_DEVICE_FULLY_VERIFIED
    }

    // ── EmulatorVerifiedOnly ──────────────────────────────────────────────────

    /**
     * All required scenarios passed on an Android emulator; no physical device was used.
     *
     * V2 may acknowledge emulator evidence as structural confirmation but must not treat it
     * as equivalent to [RealDeviceFullyVerified] for hardware-gated acceptance gates.
     *
     * Android-side semantic: [RealDeviceParticipantVerificationBridge.ARTIFACT_EMULATOR_VERIFIED_ONLY].
     *
     * @property deviceId        The emulator device identifier.
     * @property participantId   The participant node identifier.
     * @property reportId        Stable identifier for the verification report.
     * @property passedScenarios The set of [RealDeviceVerificationScenario] values that passed.
     */
    data class EmulatorVerifiedOnly(
        val deviceId: String,
        val participantId: String,
        val reportId: String,
        val passedScenarios: Set<RealDeviceVerificationScenario>
    ) : RealDeviceParticipantVerificationArtifact() {
        override val artifactTag: String =
            RealDeviceParticipantVerificationBridge.ARTIFACT_EMULATOR_VERIFIED_ONLY
    }

    // ── SimulatedVerifiedOnly ─────────────────────────────────────────────────

    /**
     * Scenarios were verified in software-only simulation; no Android runtime was used.
     *
     * Simulated evidence is advisory only.  V2 must not use it for acceptance or
     * governance decisions.
     *
     * Android-side semantic: [RealDeviceParticipantVerificationBridge.ARTIFACT_SIMULATED_VERIFIED_ONLY].
     *
     * @property reportId        Stable identifier for the verification report.
     * @property passedScenarios The set of [RealDeviceVerificationScenario] values that passed.
     */
    data class SimulatedVerifiedOnly(
        val reportId: String,
        val passedScenarios: Set<RealDeviceVerificationScenario>
    ) : RealDeviceParticipantVerificationArtifact() {
        override val artifactTag: String =
            RealDeviceParticipantVerificationBridge.ARTIFACT_SIMULATED_VERIFIED_ONLY
    }

    // ── PartiallyVerified ─────────────────────────────────────────────────────

    /**
     * Some required [RealDeviceVerificationScenario] values passed but not all.
     *
     * This is a blocking condition.  V2 must not promote partial verification to a fully
     * verified state.  The [missingRequiredScenarios] field identifies which required
     * scenarios still need to pass.
     *
     * Android-side semantic: [RealDeviceParticipantVerificationBridge.ARTIFACT_PARTIALLY_VERIFIED].
     *
     * @property deviceId                 Hardware device identifier; blank if no device present.
     * @property reportId                 Stable identifier for the verification report.
     * @property passedScenarios          The set of [RealDeviceVerificationScenario] values that passed.
     * @property missingRequiredScenarios The required scenarios that did not pass.
     * @property partialReason            Human-readable explanation of the gap.
     */
    data class PartiallyVerified(
        val deviceId: String,
        val reportId: String,
        val passedScenarios: Set<RealDeviceVerificationScenario>,
        val missingRequiredScenarios: Set<RealDeviceVerificationScenario>,
        val partialReason: String
    ) : RealDeviceParticipantVerificationArtifact() {
        override val artifactTag: String =
            RealDeviceParticipantVerificationBridge.ARTIFACT_PARTIALLY_VERIFIED
    }

    // ── StaleVerification ─────────────────────────────────────────────────────

    /**
     * A previous verification run produced real-device (or higher-confidence) evidence but
     * the evidence is now older than the staleness threshold.
     *
     * V2 must trigger a fresh verification run before consuming this evidence for acceptance
     * decisions.
     *
     * Android-side semantic: [RealDeviceParticipantVerificationBridge.ARTIFACT_STALE_VERIFICATION].
     *
     * @property deviceId       Hardware device identifier from the previous run.
     * @property reportId       Stable identifier for the stale report.
     * @property staleReason    Human-readable explanation (e.g. "evidence is 90000ms old; threshold is 60000ms").
     * @property reportedAtMs   Timestamp of the stale report that triggered this artifact.
     */
    data class StaleVerification(
        val deviceId: String,
        val reportId: String,
        val staleReason: String,
        val reportedAtMs: Long
    ) : RealDeviceParticipantVerificationArtifact() {
        override val artifactTag: String =
            RealDeviceParticipantVerificationBridge.ARTIFACT_STALE_VERIFICATION
    }

    // ── VerificationAbsent ────────────────────────────────────────────────────

    /**
     * No verification has been run or all prior evidence was explicitly cleared.
     *
     * V2 must treat this as a blocking condition equivalent to an unverified participant.
     *
     * Android-side semantic: [RealDeviceParticipantVerificationBridge.ARTIFACT_VERIFICATION_ABSENT].
     *
     * @property deviceId  Hardware device identifier; blank when no device is known.
     * @property reason    Human-readable explanation of why verification is absent.
     */
    data class VerificationAbsent(
        val deviceId: String,
        val reason: String
    ) : RealDeviceParticipantVerificationArtifact() {
        override val artifactTag: String =
            RealDeviceParticipantVerificationBridge.ARTIFACT_VERIFICATION_ABSENT
    }

    // ── VerificationBlockedNoDevice ───────────────────────────────────────────

    /**
     * No physical Android device was present when verification was attempted.
     *
     * The bridge detected that no device was connected and refused to start a verification
     * run to avoid producing falsely optimistic results.
     *
     * Android-side semantic: [RealDeviceParticipantVerificationBridge.ARTIFACT_VERIFICATION_BLOCKED_NO_DEVICE].
     *
     * @property blockedReason  Human-readable explanation of why verification was blocked.
     */
    data class VerificationBlockedNoDevice(
        val blockedReason: String
    ) : RealDeviceParticipantVerificationArtifact() {
        override val artifactTag: String =
            RealDeviceParticipantVerificationBridge.ARTIFACT_VERIFICATION_BLOCKED_NO_DEVICE
    }
}
