package com.ufo.galaxy.runtime

/**
 * PR-72 (Android) — Sealed class representing the typed outcome of a dual-repo E2E
 * verification harness run.
 *
 * [DualRepoE2EVerificationArtifact] is the typed output of
 * [DualRepoE2EVerificationHarness.evaluateArtifact].  Every V2 acceptance gate or CI
 * consumer that needs to absorb Android dual-repo E2E evidence must inspect the returned
 * artifact before making a hardware-gated or E2E-closure decision.
 *
 * ## Artifact semantics
 *
 * Each concrete subtype corresponds to a named verification outcome:
 *
 * | Subtype                  | Artifact tag                    | Description                                                                                  |
 * |--------------------------|---------------------------------|----------------------------------------------------------------------------------------------|
 * | [E2EFullyVerified]       | `e2e_fully_verified`            | All five required stages passed on a real physical device.                                   |
 * | [E2EEmulatorVerifiedOnly]| `e2e_emulator_verified_only`    | All five required stages passed on an emulator; no real device used.                         |
 * | [E2ESimulatedOnly]       | `e2e_simulated_only`            | Stages verified in software simulation only; no Android runtime used.                        |
 * | [E2EPartiallyVerified]   | `e2e_partially_verified`        | Some (but not all) required stages passed; E2E closure is incomplete.                        |
 * | [E2EStale]               | `e2e_stale`                     | Previously verified evidence is too old; a fresh run is required.                            |
 * | [E2EAbsent]              | `e2e_absent`                    | No verification has been run or evidence was explicitly cleared.                             |
 * | [E2EBlockedNoDevice]     | `e2e_blocked_no_device`         | No physical device was present; E2E verification could not proceed.                          |
 *
 * ## Consumption contract
 *
 * Only [E2EFullyVerified] provides authoritative real-device dual-repo E2E evidence that
 * V2 can use for hardware-gated acceptance decisions:
 *
 *  - [E2EEmulatorVerifiedOnly]: V2 may use as structural advisory evidence but not for
 *    hardware-gated gates.
 *  - [E2ESimulatedOnly]: Advisory only; not confirmed on Android runtime.
 *  - [E2EPartiallyVerified]: Blocking — missing required stage coverage must be resolved.
 *  - [E2EStale]: Blocking — a fresh verification run is required.
 *  - [E2EAbsent]: Blocking — no E2E verification has been run.
 *  - [E2EBlockedNoDevice]: Blocking — device must be connected before re-running.
 *
 * ## Prohibition on optimistic promotion
 *
 * A CI or governance consumer **must not**:
 *  - Treat [E2EEmulatorVerifiedOnly] or [E2ESimulatedOnly] as equivalent to
 *    [E2EFullyVerified] for hardware-gated decisions.
 *  - Promote [E2EPartiallyVerified] to fully verified because some stages passed.
 *  - Promote [E2EBlockedNoDevice] to any verified state by inferring device presence.
 *  - Treat [E2EStale] as current without a fresh real-device run.
 *
 * @see DualRepoE2EVerificationHarness
 * @see DualRepoE2EVerificationStage
 * @see RealDeviceVerificationKind
 */
sealed class DualRepoE2EVerificationArtifact {

    /**
     * Stable wire tag identifying the verification artifact semantic for this instance.
     *
     * Matches one of the [DualRepoE2EVerificationHarness] `ARTIFACT_E2E_*` constants.
     */
    abstract val artifactTag: String

    // ── E2EFullyVerified ──────────────────────────────────────────────────────

    /**
     * All five required [DualRepoE2EVerificationStage] values passed on a real physical
     * Android device.
     *
     * This is the only artifact that V2 accepts as authoritative dual-repo E2E evidence
     * for hardware-gated acceptance decisions.
     *
     * @property deviceId       The hardware device identifier.
     * @property participantId  The participant node identifier.
     * @property reportId       Stable identifier for the verification report.
     * @property passedStages   The set of [DualRepoE2EVerificationStage] values that passed.
     */
    data class E2EFullyVerified(
        val deviceId: String,
        val participantId: String,
        val reportId: String,
        val passedStages: Set<DualRepoE2EVerificationStage>
    ) : DualRepoE2EVerificationArtifact() {
        override val artifactTag: String =
            DualRepoE2EVerificationHarness.ARTIFACT_E2E_FULLY_VERIFIED
    }

    // ── E2EEmulatorVerifiedOnly ───────────────────────────────────────────────

    /**
     * All five required stages passed on an Android emulator; no physical device was used.
     *
     * V2 may acknowledge emulator evidence as structural confirmation but must not treat it
     * as equivalent to [E2EFullyVerified] for hardware-gated acceptance gates.
     *
     * @property deviceId       The emulator device identifier.
     * @property participantId  The participant node identifier.
     * @property reportId       Stable identifier for the verification report.
     * @property passedStages   The set of [DualRepoE2EVerificationStage] values that passed.
     */
    data class E2EEmulatorVerifiedOnly(
        val deviceId: String,
        val participantId: String,
        val reportId: String,
        val passedStages: Set<DualRepoE2EVerificationStage>
    ) : DualRepoE2EVerificationArtifact() {
        override val artifactTag: String =
            DualRepoE2EVerificationHarness.ARTIFACT_E2E_EMULATOR_VERIFIED_ONLY
    }

    // ── E2ESimulatedOnly ──────────────────────────────────────────────────────

    /**
     * Stages were verified in software-only simulation; no Android runtime was used.
     *
     * Simulated evidence is advisory only.  V2 must not use it for acceptance or
     * governance decisions.
     *
     * @property reportId      Stable identifier for the verification report.
     * @property passedStages  The set of [DualRepoE2EVerificationStage] values that passed.
     */
    data class E2ESimulatedOnly(
        val reportId: String,
        val passedStages: Set<DualRepoE2EVerificationStage>
    ) : DualRepoE2EVerificationArtifact() {
        override val artifactTag: String =
            DualRepoE2EVerificationHarness.ARTIFACT_E2E_SIMULATED_ONLY
    }

    // ── E2EPartiallyVerified ──────────────────────────────────────────────────

    /**
     * Some required [DualRepoE2EVerificationStage] values passed but not all.
     *
     * This is a blocking condition.  V2 must not promote partial E2E verification to a
     * fully verified state.  [missingRequiredStages] identifies which required stages still
     * need to pass.
     *
     * @property deviceId              Hardware device identifier; blank if no device present.
     * @property reportId              Stable identifier for the verification report.
     * @property passedStages          The set of [DualRepoE2EVerificationStage] values that passed.
     * @property missingRequiredStages The required stages that did not pass.
     * @property partialReason         Human-readable explanation of the gap.
     */
    data class E2EPartiallyVerified(
        val deviceId: String,
        val reportId: String,
        val passedStages: Set<DualRepoE2EVerificationStage>,
        val missingRequiredStages: Set<DualRepoE2EVerificationStage>,
        val partialReason: String
    ) : DualRepoE2EVerificationArtifact() {
        override val artifactTag: String =
            DualRepoE2EVerificationHarness.ARTIFACT_E2E_PARTIALLY_VERIFIED
    }

    // ── E2EStale ──────────────────────────────────────────────────────────────

    /**
     * A previous E2E verification run produced evidence (at some confidence level) but the
     * evidence is now older than the staleness threshold.
     *
     * V2 must trigger a fresh verification run before consuming this evidence.
     *
     * @property deviceId      Hardware device identifier from the previous run.
     * @property reportId      Stable identifier for the stale report.
     * @property staleReason   Human-readable explanation.
     * @property reportedAtMs  Timestamp of the stale report that triggered this artifact.
     */
    data class E2EStale(
        val deviceId: String,
        val reportId: String,
        val staleReason: String,
        val reportedAtMs: Long
    ) : DualRepoE2EVerificationArtifact() {
        override val artifactTag: String =
            DualRepoE2EVerificationHarness.ARTIFACT_E2E_STALE
    }

    // ── E2EAbsent ─────────────────────────────────────────────────────────────

    /**
     * No E2E verification has been run or all prior evidence was explicitly cleared.
     *
     * V2 must treat this as a blocking condition equivalent to an unverified participant.
     *
     * @property deviceId  Hardware device identifier; blank when no device is known.
     * @property reason    Human-readable explanation of why E2E verification is absent.
     */
    data class E2EAbsent(
        val deviceId: String,
        val reason: String
    ) : DualRepoE2EVerificationArtifact() {
        override val artifactTag: String =
            DualRepoE2EVerificationHarness.ARTIFACT_E2E_ABSENT
    }

    // ── E2EBlockedNoDevice ────────────────────────────────────────────────────

    /**
     * No physical Android device was present when E2E verification was attempted.
     *
     * The harness detected that no device was connected and refused to start a verification
     * run to avoid producing falsely optimistic results.  This is the canonical honest
     * output for CI environments where no real device is attached.
     *
     * V2 must treat [E2EBlockedNoDevice] as a blocking condition.  A CI pipeline that
     * produces this artifact is behaving correctly — it is **not** reporting failure; it is
     * reporting the honest absence of a real device.
     *
     * @property blockedReason  Human-readable explanation of why verification was blocked.
     */
    data class E2EBlockedNoDevice(
        val blockedReason: String
    ) : DualRepoE2EVerificationArtifact() {
        override val artifactTag: String =
            DualRepoE2EVerificationHarness.ARTIFACT_E2E_BLOCKED_NO_DEVICE
    }
}
