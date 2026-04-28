package com.ufo.galaxy.runtime

/**
 * PR-69 — Structured Android participant lifecycle truth report for cross-repo consumption.
 *
 * [ParticipantLifecycleTruthReport] is the canonical Android-side lifecycle truth surface
 * that formally expresses a participant's current position in its lifecycle — from initial
 * registration through active execution, degradation, recovery, re-registration, and
 * capability re-alignment — as a structured, machine-consumable report.
 *
 * ## Problem addressed
 *
 * Prior to PR-69, Android participant lifecycle state was distributed across multiple
 * orthogonal surfaces:
 *  - [ParticipantHealthState] — execution environment health only.
 *  - [ReconnectRecoveryState] — WS reconnect lifecycle only.
 *  - [FormalParticipantLifecycleState] — capability-gate-optimised five-state model.
 *  - [AndroidDelegatedRuntimeAuditSnapshot] — six-dimensional audit verdict.
 *
 * None of these surfaces answered the cross-repo question:
 *
 * > *"Is this participant registered, active, degraded, recovering, or re-aligning —
 * > and what is the evidence for each?"*
 *
 * V2 systems had to guess from log text or partially-assembled signals whether Android
 * was [ACTIVE][ParticipantLifecycleTruthState.ACTIVE],
 * [RECOVERED][ParticipantLifecycleTruthState.RECOVERED] (without completed
 * re-registration), or merely reconnected.
 *
 * [ParticipantLifecycleTruthReport] closes this gap by providing:
 *  - A single canonical [lifecycleTruthState] derived from all runtime sub-states.
 *  - Explicit [registrationStatus] covering first-time registration and re-registration.
 *  - Explicit [reconnectObserved] flag distinguishing reconnect from RECOVERED.
 *  - Explicit [reRegistrationOutcome] with completed/failed/pending distinction.
 *  - Explicit [capabilityAlignmentStatus] covering fully-aligned, partially-aligned,
 *    alignment-pending, and not-aligned.
 *  - Explicit [recoveredButDegraded] and [partiallyAligned] intermediate-state flags.
 *  - [evidenceCompleteness] indicating how complete the lifecycle evidence is.
 *  - [toWireMap] producing a V2-consumable, schema-versioned flat map.
 *
 * ## Consumption contract for V2
 *
 * A cross-repo consumer (V2 Python module, CI gate, governance layer) should:
 *
 * 1. Check [isCrossRepoConsumable] — if `false`, the report is incomplete and must not
 *    be used for acceptance or governance decisions.
 * 2. Check [lifecycleTruthState] — the canonical state for this participant.
 * 3. If [lifecycleTruthState] is [ParticipantLifecycleTruthState.RECOVERED], check
 *    [reRegistrationOutcome] and [capabilityAlignmentStatus] before treating as ACTIVE.
 * 4. If [lifecycleTruthState] is [ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED],
 *    check [recoveredButDegraded] to determine whether the participant returns to ACTIVE
 *    or DEGRADED.
 * 5. Use [toWireMap] for stable wire-format export.
 *
 * ## Prohibition on simplification
 *
 * This report explicitly prohibits the following simplifications:
 *  - Using a single boolean to represent participant health — use [lifecycleTruthState].
 *  - Treating reconnect as sufficient evidence of recovery — check [reconnectObserved]
 *    AND [reRegistrationOutcome] AND [capabilityAlignmentStatus].
 *  - Ignoring [capabilityAlignmentStatus] intermediate states.
 *
 * ## Integration with [AndroidDelegatedRuntimeAudit]
 *
 * [ParticipantLifecycleTruthReport] complements [AndroidDelegatedRuntimeAuditSnapshot]
 * (PR-68) rather than replacing it.  The audit snapshot answers "is the participant
 * ready for delegated execution?"; this report answers "what lifecycle state is the
 * participant in and how did it get here?"
 *
 * When both surfaces are present:
 *  - Use [AndroidDelegatedRuntimeAuditSnapshot] for execution readiness decisions.
 *  - Use [ParticipantLifecycleTruthReport] for lifecycle truth and recovery provenance.
 *
 * @property participantId          Stable participant node identifier.
 * @property deviceId               Hardware device identifier.
 * @property hostId                 Per-process runtime host identifier.
 * @property lifecycleTruthState    Canonical [ParticipantLifecycleTruthState] for this report.
 * @property registrationStatus     Current [RegistrationTruthStatus] of this participant.
 * @property reconnectObserved      `true` when a WS reconnect was observed since the last
 *                                  [ParticipantLifecycleTruthState.ACTIVE] state.
 * @property reRegistrationOutcome  Outcome of the most recent re-registration attempt;
 *                                  `null` when no re-registration has been attempted.
 * @property capabilityAlignmentStatus Current [CapabilityAlignmentStatus] for this participant.
 * @property recoveredButDegraded   `true` when the participant reached [RECOVERED] or
 *                                  [CAPABILITY_RE_ALIGNED] but runtime health is still impaired,
 *                                  meaning the participant is returning to DEGRADED, not ACTIVE.
 * @property partiallyAligned       `true` when capability re-alignment completed but only
 *                                  partially (e.g. base capabilities advertised but inference
 *                                  capability not yet re-aligned).
 * @property evidenceCompleteness   [LifecycleEvidenceCompleteness] classification for this report.
 * @property reportedAtMs           Epoch-millisecond timestamp when this report was produced.
 * @property reportEpoch            Monotonically increasing report counter scoped to this
 *                                  runtime process lifetime.
 *
 * @see ParticipantLifecycleTruthState
 * @see RegistrationTruthStatus
 * @see ReRegistrationOutcome
 * @see CapabilityAlignmentStatus
 * @see LifecycleEvidenceCompleteness
 * @see ParticipantLifecycleTruthReportBuilder
 * @see AndroidDelegatedRuntimeAuditSnapshot
 */
data class ParticipantLifecycleTruthReport(
    val participantId: String,
    val deviceId: String,
    val hostId: String,
    val lifecycleTruthState: ParticipantLifecycleTruthState,
    val registrationStatus: RegistrationTruthStatus,
    val reconnectObserved: Boolean,
    val reRegistrationOutcome: ReRegistrationOutcome?,
    val capabilityAlignmentStatus: CapabilityAlignmentStatus,
    val recoveredButDegraded: Boolean,
    val partiallyAligned: Boolean,
    val evidenceCompleteness: LifecycleEvidenceCompleteness,
    val reportedAtMs: Long,
    val reportEpoch: Int
) {

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * Returns `true` when this report is complete enough to be consumed by cross-repo
     * systems (V2 acceptance gate, governance layer, CI).
     *
     * A report is **cross-repo consumable** when:
     *  - [participantId] is non-blank.
     *  - [lifecycleTruthState] is not [ParticipantLifecycleTruthState.UNREGISTERED]
     *    (participant has at least started the registration process).
     *  - [evidenceCompleteness] is [LifecycleEvidenceCompleteness.COMPLETE] or
     *    [LifecycleEvidenceCompleteness.COMPLETE_WITH_GAPS].
     *
     * A non-consumable report must not be used for acceptance or governance decisions.
     * V2 should wait for a subsequent consumable report or a lifecycle event that completes
     * the evidence.
     */
    val isCrossRepoConsumable: Boolean
        get() = participantId.isNotBlank() &&
            lifecycleTruthState != ParticipantLifecycleTruthState.UNREGISTERED &&
            (evidenceCompleteness == LifecycleEvidenceCompleteness.COMPLETE ||
                evidenceCompleteness == LifecycleEvidenceCompleteness.COMPLETE_WITH_GAPS)

    /**
     * Returns `true` when the participant is in a recovery phase that blocks dispatch.
     *
     * Recovery-phase states: [RECOVERING][ParticipantLifecycleTruthState.RECOVERING],
     * [RECOVERED][ParticipantLifecycleTruthState.RECOVERED] (re-alignment pending),
     * [RE_REGISTERING][ParticipantLifecycleTruthState.RE_REGISTERING].
     */
    val isInRecoveryPhase: Boolean
        get() = ParticipantLifecycleTruthState.isRecoveryPhase(lifecycleTruthState)

    /**
     * Returns `true` when the participant has been recovered and capability re-alignment
     * is complete, but the runtime health remains impaired (returning to DEGRADED, not ACTIVE).
     *
     * Equivalent to: [recoveredButDegraded] && [lifecycleTruthState] is
     * [CAPABILITY_RE_ALIGNED][ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED] or
     * [DEGRADED][ParticipantLifecycleTruthState.DEGRADED] after a recovery cycle.
     */
    val isRecoveredButDegraded: Boolean
        get() = recoveredButDegraded && (
            lifecycleTruthState == ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED ||
                lifecycleTruthState == ParticipantLifecycleTruthState.DEGRADED
            )

    /**
     * Returns `true` when the participant has completed the full recovery lifecycle:
     *  - [reconnectObserved] is `true`.
     *  - [reRegistrationOutcome] is [ReRegistrationOutcome.COMPLETED].
     *  - [capabilityAlignmentStatus] is [CapabilityAlignmentStatus.FULLY_ALIGNED] or
     *    [CapabilityAlignmentStatus.PARTIALLY_ALIGNED].
     *
     * Note: a fully-recovered participant may still be [recoveredButDegraded] if runtime
     * health is impaired.
     */
    val isFullyRecovered: Boolean
        get() = reconnectObserved &&
            reRegistrationOutcome == ReRegistrationOutcome.COMPLETED &&
            (capabilityAlignmentStatus == CapabilityAlignmentStatus.FULLY_ALIGNED ||
                capabilityAlignmentStatus == CapabilityAlignmentStatus.PARTIALLY_ALIGNED)

    // ── Wire serialization ────────────────────────────────────────────────────

    /**
     * Produces a stable, V2-consumable `Map<String, Any>` for cross-repo export.
     *
     * The map uses schema version `"1.0"` and contains all fields required for V2
     * lifecycle truth ingestion.  It is safe to serialise to JSON.
     *
     * ## Map keys (schema version 1.0)
     *
     * | Key                             | Type      | Description                                                          |
     * |---------------------------------|-----------|----------------------------------------------------------------------|
     * | [KEY_SCHEMA_VERSION]            | String    | Always `"1.0"`.                                                      |
     * | [KEY_PARTICIPANT_ID]            | String    | Stable participant identity.                                         |
     * | [KEY_DEVICE_ID]                 | String    | Hardware device identifier.                                          |
     * | [KEY_HOST_ID]                   | String    | Per-process runtime host identifier.                                 |
     * | [KEY_LIFECYCLE_TRUTH_STATE]     | String    | [lifecycleTruthState] wire value.                                    |
     * | [KEY_REGISTRATION_STATUS]       | String    | [registrationStatus] wire value.                                     |
     * | [KEY_RECONNECT_OBSERVED]        | Boolean   | Whether a reconnect was observed since last ACTIVE state.            |
     * | [KEY_RE_REGISTRATION_OUTCOME]   | String?   | [reRegistrationOutcome] wire value; absent when null.                |
     * | [KEY_CAPABILITY_ALIGNMENT]      | String    | [capabilityAlignmentStatus] wire value.                              |
     * | [KEY_RECOVERED_BUT_DEGRADED]    | Boolean   | Whether participant recovered but is still degraded.                 |
     * | [KEY_PARTIALLY_ALIGNED]         | Boolean   | Whether capability re-alignment is only partial.                     |
     * | [KEY_EVIDENCE_COMPLETENESS]     | String    | [evidenceCompleteness] wire value.                                   |
     * | [KEY_IS_CROSS_REPO_CONSUMABLE]  | Boolean   | Pre-computed cross-repo consumability flag.                          |
     * | [KEY_IS_IN_RECOVERY_PHASE]      | Boolean   | Pre-computed recovery phase flag.                                    |
     * | [KEY_IS_FULLY_RECOVERED]        | Boolean   | Pre-computed fully-recovered flag.                                   |
     * | [KEY_REPORTED_AT_MS]            | Long      | Epoch-ms timestamp of report production.                             |
     * | [KEY_REPORT_EPOCH]              | Int       | Monotonic report counter.                                            |
     */
    fun toWireMap(): Map<String, Any> = buildMap {
        put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        put(KEY_PARTICIPANT_ID, participantId)
        put(KEY_DEVICE_ID, deviceId)
        put(KEY_HOST_ID, hostId)
        put(KEY_LIFECYCLE_TRUTH_STATE, lifecycleTruthState.wireValue)
        put(KEY_REGISTRATION_STATUS, registrationStatus.wireValue)
        put(KEY_RECONNECT_OBSERVED, reconnectObserved)
        reRegistrationOutcome?.let { put(KEY_RE_REGISTRATION_OUTCOME, it.wireValue) }
        put(KEY_CAPABILITY_ALIGNMENT, capabilityAlignmentStatus.wireValue)
        put(KEY_RECOVERED_BUT_DEGRADED, recoveredButDegraded)
        put(KEY_PARTIALLY_ALIGNED, partiallyAligned)
        put(KEY_EVIDENCE_COMPLETENESS, evidenceCompleteness.wireValue)
        put(KEY_IS_CROSS_REPO_CONSUMABLE, isCrossRepoConsumable)
        put(KEY_IS_IN_RECOVERY_PHASE, isInRecoveryPhase)
        put(KEY_IS_FULLY_RECOVERED, isFullyRecovered)
        put(KEY_REPORTED_AT_MS, reportedAtMs)
        put(KEY_REPORT_EPOCH, reportEpoch)
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {

        /** Schema version for [toWireMap] output. Always `"1.0"`. */
        const val SCHEMA_VERSION = "1.0"

        /** Wire key for [ParticipantLifecycleTruthReport.Companion.SCHEMA_VERSION]. */
        const val KEY_SCHEMA_VERSION = "schema_version"

        /** Wire key for [participantId]. */
        const val KEY_PARTICIPANT_ID = "participant_id"

        /** Wire key for [deviceId]. */
        const val KEY_DEVICE_ID = "device_id"

        /** Wire key for [hostId]. */
        const val KEY_HOST_ID = "host_id"

        /** Wire key for [lifecycleTruthState] ([ParticipantLifecycleTruthState.wireValue]). */
        const val KEY_LIFECYCLE_TRUTH_STATE = "lifecycle_truth_state"

        /** Wire key for [registrationStatus] ([RegistrationTruthStatus.wireValue]). */
        const val KEY_REGISTRATION_STATUS = "registration_status"

        /** Wire key for [reconnectObserved]. */
        const val KEY_RECONNECT_OBSERVED = "reconnect_observed"

        /** Wire key for [reRegistrationOutcome] ([ReRegistrationOutcome.wireValue]); absent when null. */
        const val KEY_RE_REGISTRATION_OUTCOME = "re_registration_outcome"

        /** Wire key for [capabilityAlignmentStatus] ([CapabilityAlignmentStatus.wireValue]). */
        const val KEY_CAPABILITY_ALIGNMENT = "capability_alignment_status"

        /** Wire key for [recoveredButDegraded]. */
        const val KEY_RECOVERED_BUT_DEGRADED = "recovered_but_degraded"

        /** Wire key for [partiallyAligned]. */
        const val KEY_PARTIALLY_ALIGNED = "partially_aligned"

        /** Wire key for [evidenceCompleteness] ([LifecycleEvidenceCompleteness.wireValue]). */
        const val KEY_EVIDENCE_COMPLETENESS = "evidence_completeness"

        /** Wire key for [isCrossRepoConsumable] pre-computed boolean. */
        const val KEY_IS_CROSS_REPO_CONSUMABLE = "is_cross_repo_consumable"

        /** Wire key for [isInRecoveryPhase] pre-computed boolean. */
        const val KEY_IS_IN_RECOVERY_PHASE = "is_in_recovery_phase"

        /** Wire key for [isFullyRecovered] pre-computed boolean. */
        const val KEY_IS_FULLY_RECOVERED = "is_fully_recovered"

        /** Wire key for [reportedAtMs]. */
        const val KEY_REPORTED_AT_MS = "reported_at_ms"

        /** Wire key for [reportEpoch]. */
        const val KEY_REPORT_EPOCH = "report_epoch"

        /** PR number that introduced this surface. */
        const val INTRODUCED_PR = 69

        /** Description of this surface's purpose. */
        const val DESCRIPTION =
            "Structured Android participant lifecycle truth report for cross-repo consumption " +
                "(PR-69). Expresses participant lifecycle state from registration through " +
                "active, degraded, recovery, re-registration, and capability re-alignment."
    }
}

// ── Supporting enumerations ───────────────────────────────────────────────────

/**
 * Registration truth status for a participant, covering both first-time registration
 * and re-registration after recovery.
 *
 * @property wireValue Stable lowercase string for wire transport.
 */
enum class RegistrationTruthStatus(val wireValue: String) {

    /**
     * The participant has never registered with the V2 gateway.
     * Device has no gateway identity.
     */
    NEVER_REGISTERED("never_registered"),

    /**
     * A registration (or re-registration) attempt is in progress and has not yet
     * been confirmed by the gateway.
     */
    REGISTRATION_PENDING("registration_pending"),

    /**
     * The participant has a valid, active registration with the V2 gateway.
     * Identity is confirmed and not expired or revoked.
     */
    REGISTERED("registered"),

    /**
     * The participant's previous registration was explicitly revoked by the gateway
     * or by user action (deregistration).
     */
    REGISTRATION_REVOKED("registration_revoked"),

    /**
     * The participant was previously registered but the registration has expired
     * (e.g. token expiry, session timeout without renewal).
     */
    REGISTRATION_EXPIRED("registration_expired");

    companion object {
        /** Returns the [RegistrationTruthStatus] with the given [wireValue], or `null`. */
        fun fromWireValue(value: String?): RegistrationTruthStatus? =
            entries.firstOrNull { it.wireValue == value }

        /** All stable wire values for validation. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}

/**
 * Outcome of a re-registration attempt after a recovery cycle.
 *
 * @property wireValue Stable lowercase string for wire transport.
 */
enum class ReRegistrationOutcome(val wireValue: String) {

    /**
     * Re-registration completed successfully; the gateway confirmed the participant's
     * identity and the registration is now active.
     */
    COMPLETED("re_registration_completed"),

    /**
     * Re-registration failed; the gateway rejected or timed out the re-registration
     * attempt.  Participant is in an unregistered or unavailable state.
     */
    FAILED("re_registration_failed"),

    /**
     * Re-registration is in progress and has not yet completed or failed.
     */
    PENDING("re_registration_pending");

    companion object {
        /** Returns the [ReRegistrationOutcome] with the given [wireValue], or `null`. */
        fun fromWireValue(value: String?): ReRegistrationOutcome? =
            entries.firstOrNull { it.wireValue == value }

        /** All stable wire values for validation. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}

/**
 * Capability re-alignment status after a recovery or re-registration cycle.
 *
 * Capability re-alignment is the process by which the participant re-advertises its
 * capability set to V2 after a recovery/re-registration, ensuring that V2's view of
 * available capabilities is consistent with the current runtime state.
 *
 * @property wireValue Stable lowercase string for wire transport.
 */
enum class CapabilityAlignmentStatus(val wireValue: String) {

    /**
     * Capability re-alignment completed successfully.  All capabilities that the
     * current runtime state permits are advertised, and no excess capabilities are
     * claimed.
     */
    FULLY_ALIGNED("capability_fully_aligned"),

    /**
     * Capability re-alignment completed but only partially.  Base capabilities are
     * advertised correctly but some capabilities (e.g. inference) could not be
     * re-aligned due to runtime health impairment.
     */
    PARTIALLY_ALIGNED("capability_partially_aligned"),

    /**
     * Capability re-alignment has not yet completed.  Re-advertisement is still in
     * progress or is awaiting re-registration completion.
     */
    ALIGNMENT_PENDING("capability_alignment_pending"),

    /**
     * Capability re-alignment has not been attempted, or the last attempt failed.
     * V2 must not assume capability availability for this participant.
     */
    NOT_ALIGNED("capability_not_aligned");

    companion object {
        /** Returns the [CapabilityAlignmentStatus] with the given [wireValue], or `null`. */
        fun fromWireValue(value: String?): CapabilityAlignmentStatus? =
            entries.firstOrNull { it.wireValue == value }

        /** All stable wire values for validation. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}

/**
 * Completeness classification for the lifecycle evidence contained in a
 * [ParticipantLifecycleTruthReport].
 *
 * V2 systems must check [LifecycleEvidenceCompleteness] before consuming a report.
 * Only [COMPLETE] and [COMPLETE_WITH_GAPS] reports are safe for cross-repo consumption
 * (reflected in [ParticipantLifecycleTruthReport.isCrossRepoConsumable]).
 *
 * @property wireValue Stable lowercase string for wire transport.
 */
enum class LifecycleEvidenceCompleteness(val wireValue: String) {

    /**
     * All lifecycle evidence fields are present and internally consistent.
     * The report can be fully consumed by V2 without any caveats.
     */
    COMPLETE("evidence_complete"),

    /**
     * Core lifecycle evidence is present but some optional fields have gaps
     * (e.g. recovery details are absent because no recovery has occurred yet,
     * or re-registration outcome is null because no re-registration was attempted).
     * The report is still consumable but V2 should acknowledge the gaps.
     */
    COMPLETE_WITH_GAPS("evidence_complete_with_gaps"),

    /**
     * The lifecycle evidence is incomplete: one or more required fields could not be
     * determined.  The report must not be consumed for acceptance or governance decisions.
     */
    INCOMPLETE("evidence_incomplete"),

    /**
     * The lifecycle evidence contains stale fields that exceed the freshness threshold.
     * V2 must trigger a refresh before consuming this report.
     */
    STALE("evidence_stale"),

    /**
     * The lifecycle evidence is in an inconsistent state (e.g. [RECOVERED] state but
     * no reconnect observed, or [ACTIVE] with [RegistrationTruthStatus.NEVER_REGISTERED]).
     * V2 must reject this report.
     */
    INCONSISTENT("evidence_inconsistent");

    companion object {
        /** Returns the [LifecycleEvidenceCompleteness] with the given [wireValue], or `null`. */
        fun fromWireValue(value: String?): LifecycleEvidenceCompleteness? =
            entries.firstOrNull { it.wireValue == value }

        /** All stable wire values for validation. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}
