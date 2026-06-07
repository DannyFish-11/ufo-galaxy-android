package com.ufo.galaxy.runtime

/**
 * PR-68 (Android) — Enum representing the six device-side audit dimensions that
 * collectively determine the Android delegated runtime audit state for V2 final
 * acceptance and readiness/governance consumption.
 *
 * Each dimension corresponds to a distinct aspect of Android participant runtime truth
 * that must be observable, verifiable, and exportable to V2 before the participant can
 * be treated as fully operational in the dual-repo system.
 *
 * ## Dimension overview
 *
 * | Dimension                        | Wire value                       | Primary owner module                                           |
 * |----------------------------------|----------------------------------|----------------------------------------------------------------|
 * | [PARTICIPANT_REGISTRATION]       | `participant_registration`       | [AndroidParticipantRuntimeTruth], device register flow         |
 * | [PARTICIPANT_AVAILABILITY]       | `participant_availability`       | [MultiDeviceParticipantOrchestrationState]                     |
 * | [DELEGATED_EXECUTION_READINESS]  | `delegated_execution_readiness`  | [DelegatedRuntimeReadinessEvaluator]                           |
 * | [HEALTH_STATE]                   | `health_state`                   | [CapabilityHonestyGuard], [ParticipantHealthState]             |
 * | [CAPABILITY_HONESTY]             | `capability_honesty`             | [CapabilityHonestyGuard]                                       |
 * | [EVIDENCE_FRESHNESS]             | `evidence_freshness`             | [AndroidDelegatedRuntimeAudit]                                 |
 *
 * @property wireValue Stable lowercase string used in JSON payloads and audit reports.
 *
 * @see AndroidDelegatedRuntimeAudit
 * @see AndroidDelegatedRuntimeAuditEvidence
 * @see AndroidDelegatedRuntimeAuditSnapshot
 */
enum class AndroidDelegatedRuntimeAuditDimension(val wireValue: String) {

    /**
     * Participant registration state.
     *
     * Covers whether the Android participant has completed a successful device registration
     * with the V2 gateway and holds a valid, active participant identity.  Registration must
     * have occurred and not have been revoked or expired for this dimension to be AUDITED.
     *
     * A DEGRADED state indicates partial registration (e.g. registered but session expired).
     * UNAVAILABLE indicates the device has never registered or registration was rejected.
     *
     * Primary module: [AndroidParticipantRuntimeTruth], DEVICE_REGISTER flow.
     */
    PARTICIPANT_REGISTRATION("participant_registration"),

    /**
     * Participant availability.
     *
     * Covers whether the Android participant is currently reachable and available to accept
     * delegated work.  Corresponds to the participant's
     * [MultiDeviceParticipantOrchestrationState.OrchestrationState] being CONNECTED,
     * DEGRADED, or CAPABILITY_LIMITED (available with limitations).
     *
     * UNAVAILABLE indicates the participant is DISCONNECTED, RECONNECTING, or RECOVERING.
     * DEGRADED indicates the participant is connected but with reduced capability.
     *
     * Primary module: [MultiDeviceParticipantOrchestrationState].
     */
    PARTICIPANT_AVAILABILITY("participant_availability"),

    /**
     * Delegated execution readiness.
     *
     * Covers whether the Android delegated runtime has produced a passing
     * [DeviceReadinessArtifact.DeviceReadyForRelease] outcome from
     * [DelegatedRuntimeReadinessEvaluator].  All five readiness dimensions must be
     * satisfied before this dimension can be AUDITED.
     *
     * DEGRADED indicates readiness has gaps on non-critical dimensions.
     * UNAVAILABLE indicates readiness evaluation has not been completed or has failed.
     *
     * Primary module: [DelegatedRuntimeReadinessEvaluator].
     */
    DELEGATED_EXECUTION_READINESS("delegated_execution_readiness"),

    /**
     * Health state (health / degraded / recovered).
     *
     * Covers the current health posture of the Android participant runtime.
     * AUDITED means the runtime is healthy (not in a degraded or recovering state).
     * DEGRADED means the runtime is in a degraded health state.
     * Corresponds directly to [MultiDeviceParticipantOrchestrationState.OrchestrationState]:
     *   - CONNECTED + ACTIVE inference → AUDITED
     *   - DEGRADED / CAPABILITY_LIMITED → DEGRADED
     *   - RECOVERING → UNAVAILABLE
     *   - RECONNECTING / DISCONNECTED → UNAVAILABLE
     *
     * This dimension must not be AUDITED when the runtime is not healthy, preventing
     * optimistic "ready" conclusions while the runtime is in a degraded state.
     *
     * Primary module: [CapabilityHonestyGuard], [ParticipantHealthState].
     */
    HEALTH_STATE("health_state"),

    /**
     * Capability honesty / advertisement consistency.
     *
     * Covers whether Android's capability advertisement is honest and consistent with the
     * actual runtime state.  Determined via [CapabilityHonestyGuard.isHonest]: the
     * advertised capability set must match what [CapabilityHonestyGuard.computeAllowedCapabilities]
     * permits for the current orchestration state and inference status.
     *
     * AUDITED means the last capability advertisement was fully honest (no violations).
     * DEGRADED means the advertisement had advisory-level inconsistencies.
     * MALFORMED/INCOMPLETE (represented as GAP reason "malformed_capability_report") means
     * the capability report itself was missing required fields or could not be evaluated.
     *
     * **Important**: capability advertisement alone does not prove capability honesty.
     * This dimension must be backed by an explicit [CapabilityHonestyGuard.isHonest] check.
     *
     * Primary module: [CapabilityHonestyGuard].
     */
    CAPABILITY_HONESTY("capability_honesty"),

    /**
     * Runtime evidence freshness / timestamp.
     *
     * Covers whether the audit evidence is sufficiently fresh to be consumed by V2.
     * Evidence is considered AUDITED (fresh) when the snapshot was produced within
     * [AndroidDelegatedRuntimeAudit.DEFAULT_STALE_THRESHOLD_MS] of the current time.
     * Evidence is STALE when the snapshot is older than the threshold.
     *
     * Stale evidence must not be reported as READY to V2; V2 must trigger a refresh
     * before treating Android participant state as current.
     *
     * Primary module: [AndroidDelegatedRuntimeAudit].
     */
    EVIDENCE_FRESHNESS("evidence_freshness");

    companion object {

        /**
         * Returns the [AndroidDelegatedRuntimeAuditDimension] with the given [wireValue],
         * or `null` if no match is found.
         *
         * @param value  The wire value string to look up.
         */
        fun fromValue(value: String?): AndroidDelegatedRuntimeAuditDimension? =
            entries.firstOrNull { it.wireValue == value }
    }
}
