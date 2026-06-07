package com.ufo.galaxy.runtime

import java.util.UUID

/**
 * PR-68 (Android) — Android delegated runtime audit module: the unified Android-side
 * entry point for structured, cross-repo-consumable runtime evidence production.
 *
 * [AndroidDelegatedRuntimeAudit] is the canonical Android participant audit layer that
 * closes the `android_participant unresolved` gap in V2's
 * `system_final_acceptance_verdict`.  It answers the following questions for every
 * audit evaluation:
 *
 *  - Is the Android participant registered and available?
 *  - Is the delegated execution runtime ready?
 *  - Is the runtime health state healthy, degraded, or recovered?
 *  - Are capability advertisements consistent with actual runtime state?
 *  - Is the audit evidence fresh enough to be consumed by V2?
 *  - What [AndroidDelegatedRuntimeAuditEvidence] should be exported to V2?
 *  - What [AndroidDelegatedRuntimeAuditSnapshot] can be ingested by V2?
 *
 * ## Background and motivation
 *
 * Before PR-68, the V2 `system_final_acceptance_verdict` produced an
 * `[UNRESOLVED] android_participant` result because `core.android_delegated_runtime_audit`
 * was absent.  Android participant runtime state was not observable in a structured,
 * V2-consumable form:
 *
 *  - Participant registration and availability were not exportable as evidence.
 *  - Delegated execution readiness was not aggregated from PR-9 evaluator output.
 *  - Health state (healthy / degraded / recovered) was not distinctly classified.
 *  - Capability honesty was declared by advertisement but never explicitly verified via
 *    [CapabilityHonestyGuard.isHonest].
 *  - No freshness / staleness boundary was defined for evidence validity.
 *  - No stable cross-repo wire format existed for V2 ingestion.
 *
 * [AndroidDelegatedRuntimeAudit] closes these gaps by providing:
 *  - A composable, testable dimension gate API (AUDITED / DEGRADED / UNAVAILABLE /
 *    STALE / MALFORMED / UNKNOWN).
 *  - A strict evidence evaluation model that cannot produce [EVIDENCE_READY] when the
 *    runtime is unhealthy or capability advertisements are unverified.
 *  - A [buildSnapshot] method producing a [AndroidDelegatedRuntimeAuditSnapshot] with
 *    [AndroidDelegatedRuntimeAuditSnapshot.toWireMap] for cross-repo export.
 *
 * ## Audit dimensions
 *
 * Six dimensions govern the overall evidence verdict ([AndroidDelegatedRuntimeAuditDimension]):
 *
 * | Dimension                          | Description                                                       |
 * |------------------------------------|-------------------------------------------------------------------|
 * | [PARTICIPANT_REGISTRATION]         | Participant has a valid, active registration with V2 gateway.     |
 * | [PARTICIPANT_AVAILABILITY]         | Participant is reachable and can accept delegated work.           |
 * | [DELEGATED_EXECUTION_READINESS]    | PR-9 readiness evaluator has produced DeviceReadyForRelease.      |
 * | [HEALTH_STATE]                     | Runtime health is healthy (not degraded or recovering).           |
 * | [CAPABILITY_HONESTY]               | Capability advertisement is verified honest by CapabilityHonestyGuard. |
 * | [EVIDENCE_FRESHNESS]               | Audit evidence is within the freshness threshold.                 |
 *
 * ## Evaluation logic
 *
 * [evaluateEvidence] applies the following priority order:
 *
 * 1. **Unverified** — any dimension with status [DimensionStatus.UNKNOWN] →
 *    [AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified].
 *
 * 2. **Malformed/incomplete** — any dimension with status [DimensionStatus.MALFORMED] →
 *    [AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceMalformedOrIncomplete].
 *
 * 3. **Unavailable** — [PARTICIPANT_REGISTRATION] or [PARTICIPANT_AVAILABILITY] has
 *    status [DimensionStatus.UNAVAILABLE] →
 *    [AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnavailable].
 *
 * 4. **Stale** — [EVIDENCE_FRESHNESS] has status [DimensionStatus.STALE] →
 *    [AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceStale].
 *
 * 5. **Degraded** — any remaining dimension is [DimensionStatus.DEGRADED] or
 *    [DimensionStatus.UNAVAILABLE] →
 *    [AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceDegraded].
 *
 * 6. **Ready** — all six dimensions are [DimensionStatus.AUDITED] →
 *    [AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady].
 *
 * ## Capability honesty contract
 *
 * The [CAPABILITY_HONESTY] dimension must be explicitly marked via
 * [markDimensionAudited] **only** after a successful [CapabilityHonestyGuard.isHonest]
 * check returns an empty violation list.  Advertising capabilities without this check
 * does not satisfy the dimension.
 *
 * ## Health state contract
 *
 * The [HEALTH_STATE] dimension must reflect the **actual** runtime health, not an
 * aspirational state.  If the runtime is in a DEGRADED or RECOVERING orchestration
 * state, the [HEALTH_STATE] dimension must be [DimensionStatus.DEGRADED] or
 * [DimensionStatus.UNAVAILABLE] respectively.  It must not be [DimensionStatus.AUDITED]
 * when the runtime is not healthy — this prevents optimistic "ready" conclusions while
 * the runtime is unhealthy.
 *
 * ## Staleness detection
 *
 * Call [checkAndMarkStaleness] to compare the snapshot's [reportedAtMs] against the
 * current time and automatically mark [EVIDENCE_FRESHNESS] as [DimensionStatus.STALE]
 * when the evidence age exceeds [stalenessThresholdMs].  The threshold defaults to
 * [DEFAULT_STALE_THRESHOLD_MS] but can be overridden for testing.
 *
 * ## Thread safety
 *
 * All dimension gate operations use [synchronized] blocks for safe cross-thread access.
 * [evaluateEvidence] and [buildSnapshot] are pure functions that rely only on the
 * synchronized accessors.
 *
 * ## Integration points
 *
 * | Integration point constant              | Module                                           | Role                                                                 |
 * |-----------------------------------------|--------------------------------------------------|----------------------------------------------------------------------|
 * | [INTEGRATION_READINESS_EVALUATOR]       | [DelegatedRuntimeReadinessEvaluator]             | Supplies DELEGATED_EXECUTION_READINESS signal.                       |
 * | [INTEGRATION_CAPABILITY_HONESTY_GUARD]  | [CapabilityHonestyGuard]                         | Validates CAPABILITY_HONESTY dimension.                              |
 * | [INTEGRATION_ORCHESTRATION_STATE]       | [MultiDeviceParticipantOrchestrationState]        | Supplies PARTICIPANT_AVAILABILITY + HEALTH_STATE signals.            |
 * | [INTEGRATION_RUNTIME_TRUTH]             | [AndroidParticipantRuntimeTruth]                 | Supplies PARTICIPANT_REGISTRATION signal.                            |
 * | [INTEGRATION_RUNTIME_CONTROLLER]        | [RuntimeController]                              | Consumes snapshot and forwards to V2 via reconciliation signal.      |
 *
 * @see AndroidDelegatedRuntimeAuditDimension
 * @see AndroidDelegatedRuntimeAuditEvidence
 * @see AndroidDelegatedRuntimeAuditSnapshot
 * @see DelegatedRuntimeReadinessEvaluator
 * @see CapabilityHonestyGuard
 */
class AndroidDelegatedRuntimeAudit {

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Per-dimension audit state.
     *
     * Map key is [AndroidDelegatedRuntimeAuditDimension]; value is a pair of
     * ([AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus], optional reason).
     * All dimensions start as [AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN]
     * until a signal is received.
     */
    private val _dimensionStates: MutableMap<
        AndroidDelegatedRuntimeAuditDimension,
        Pair<AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus, String?>
    > = mutableMapOf()

    // ── Core API — dimension gate management ──────────────────────────────────

    /**
     * Marks [dimension] as fully audited with no issues.
     *
     * After this call, [getDimensionStatus] returns
     * [AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED] for [dimension].
     *
     * @param dimension  The audit dimension to mark audited.
     */
    fun markDimensionAudited(dimension: AndroidDelegatedRuntimeAuditDimension) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED to null
        }
    }

    /**
     * Marks [dimension] as degraded.
     *
     * After this call, [getDimensionStatus] returns
     * [AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.DEGRADED] for [dimension].
     *
     * @param dimension      The audit dimension to mark degraded.
     * @param degradedReason Human-readable explanation of the degraded condition.
     */
    fun markDimensionDegraded(
        dimension: AndroidDelegatedRuntimeAuditDimension,
        degradedReason: String
    ) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.DEGRADED to degradedReason
        }
    }

    /**
     * Marks [dimension] as unavailable.
     *
     * After this call, [getDimensionStatus] returns
     * [AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNAVAILABLE] for [dimension].
     *
     * @param dimension          The audit dimension to mark unavailable.
     * @param unavailableReason  Human-readable explanation of why the dimension is unavailable.
     */
    fun markDimensionUnavailable(
        dimension: AndroidDelegatedRuntimeAuditDimension,
        unavailableReason: String
    ) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNAVAILABLE to unavailableReason
        }
    }

    /**
     * Marks [dimension] as having stale evidence.
     *
     * After this call, [getDimensionStatus] returns
     * [AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.STALE] for [dimension].
     *
     * Typically called on [AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS] when
     * [checkAndMarkStaleness] detects that evidence age exceeds the threshold.
     *
     * @param dimension    The audit dimension to mark stale.
     * @param staleReason  Human-readable explanation of why evidence is stale.
     */
    fun markDimensionStale(
        dimension: AndroidDelegatedRuntimeAuditDimension,
        staleReason: String
    ) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.STALE to staleReason
        }
    }

    /**
     * Marks [dimension] as having malformed or incomplete data.
     *
     * After this call, [getDimensionStatus] returns
     * [AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.MALFORMED] for [dimension].
     *
     * @param dimension       The audit dimension to mark malformed.
     * @param malformedReason Human-readable explanation of what is malformed or missing.
     */
    fun markDimensionMalformed(
        dimension: AndroidDelegatedRuntimeAuditDimension,
        malformedReason: String
    ) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.MALFORMED to malformedReason
        }
    }

    /**
     * Marks [dimension] as having no signal (unknown).
     *
     * After this call, [getDimensionStatus] returns
     * [AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN] for [dimension].
     *
     * @param dimension  The audit dimension to mark unknown.
     * @param reason     Optional explanation of why the signal is missing.
     */
    fun markDimensionUnknown(
        dimension: AndroidDelegatedRuntimeAuditDimension,
        reason: String? = null
    ) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN to reason
        }
    }

    /**
     * Returns the current [AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus] for
     * [dimension].
     *
     * Returns [AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN] if no signal
     * has been received for [dimension].
     *
     * @param dimension  The audit dimension to query.
     */
    fun getDimensionStatus(
        dimension: AndroidDelegatedRuntimeAuditDimension
    ): AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus =
        synchronized(_dimensionStates) {
            _dimensionStates[dimension]?.first
                ?: AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN
        }

    /**
     * Returns the reason registered for [dimension], or `null` if the dimension is
     * AUDITED or has no reason.
     *
     * @param dimension  The audit dimension to query.
     */
    fun getDimensionReason(dimension: AndroidDelegatedRuntimeAuditDimension): String? =
        synchronized(_dimensionStates) { _dimensionStates[dimension]?.second }

    /**
     * Resets all dimension states to [AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN].
     *
     * Use only when the full audit era is being reset (e.g. session close / process
     * recreation) and all prior audit conclusions are no longer applicable.
     */
    fun clearAllDimensionStates() {
        synchronized(_dimensionStates) { _dimensionStates.clear() }
    }

    // ── Staleness detection ───────────────────────────────────────────────────

    /**
     * Checks whether the audit evidence is stale and marks the
     * [AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS] dimension accordingly.
     *
     * If `currentTimeMs - reportedAtMs > stalenessThresholdMs`, marks
     * [EVIDENCE_FRESHNESS] as [AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.STALE].
     * Otherwise, marks it as [AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED].
     *
     * @param reportedAtMs        The epoch-ms timestamp of the evidence to check.
     * @param currentTimeMs       The current epoch-ms time (defaults to [System.currentTimeMillis]).
     * @param stalenessThresholdMs The maximum acceptable age in milliseconds before evidence
     *                             is considered stale (defaults to [DEFAULT_STALE_THRESHOLD_MS]).
     * @return `true` if the evidence is fresh (AUDITED); `false` if stale.
     */
    fun checkAndMarkStaleness(
        reportedAtMs: Long,
        currentTimeMs: Long = System.currentTimeMillis(),
        stalenessThresholdMs: Long = DEFAULT_STALE_THRESHOLD_MS
    ): Boolean {
        val ageMs = currentTimeMs - reportedAtMs
        return if (ageMs > stalenessThresholdMs) {
            markDimensionStale(
                AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS,
                "evidence is ${ageMs}ms old; threshold is ${stalenessThresholdMs}ms"
            )
            false
        } else {
            markDimensionAudited(AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS)
            true
        }
    }

    // ── Core API — evidence evaluation ────────────────────────────────────────

    /**
     * Evaluates the current audit state and returns the appropriate
     * [AndroidDelegatedRuntimeAuditEvidence].
     *
     * ## Evaluation priority
     *
     * 1. **Unverified** — any dimension is UNKNOWN.
     * 2. **Malformed/incomplete** — any dimension is MALFORMED.
     * 3. **Unavailable** — PARTICIPANT_REGISTRATION or PARTICIPANT_AVAILABILITY is UNAVAILABLE.
     * 4. **Stale** — EVIDENCE_FRESHNESS is STALE.
     * 5. **Degraded** — any remaining dimension is DEGRADED or UNAVAILABLE.
     * 6. **Ready** — all six dimensions are AUDITED.
     *
     * @param deviceId    The device identifier to embed in the evidence.
     * @param snapshotId  The snapshot identifier to embed in the evidence.
     * @return The [AndroidDelegatedRuntimeAuditEvidence] for the current dimension states.
     */
    fun evaluateEvidence(
        deviceId: String,
        snapshotId: String
    ): AndroidDelegatedRuntimeAuditEvidence {
        val states = synchronized(_dimensionStates) { HashMap(_dimensionStates) }

        // ── 1. Unverified — any UNKNOWN dimension ──────────────────────────────
        val unknownDimensions = AndroidDelegatedRuntimeAuditDimension.entries.filter { dim ->
            (states[dim]?.first
                ?: AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN) ==
                AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN
        }.toSet()

        if (unknownDimensions.isNotEmpty()) {
            return AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified(
                deviceId = deviceId,
                snapshotId = snapshotId,
                missingDimensions = unknownDimensions
            )
        }

        // ── 2. Malformed/incomplete — any MALFORMED dimension ─────────────────
        val malformedEntry = AndroidDelegatedRuntimeAuditDimension.entries.firstOrNull { dim ->
            states[dim]?.first == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.MALFORMED
        }
        if (malformedEntry != null) {
            val reason = states[malformedEntry]?.second ?: REASON_MALFORMED_DEFAULT
            return AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceMalformedOrIncomplete(
                deviceId = deviceId,
                snapshotId = snapshotId,
                malformedReason = reason,
                dimension = malformedEntry
            )
        }

        // ── 3. Unavailable — PARTICIPANT_REGISTRATION or PARTICIPANT_AVAILABILITY is UNAVAILABLE
        val regState = states[AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION]
        if (regState?.first == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNAVAILABLE) {
            return AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnavailable(
                deviceId = deviceId,
                snapshotId = snapshotId,
                unavailableReason = regState.second ?: REASON_REGISTRATION_UNAVAILABLE_DEFAULT,
                dimension = AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION
            )
        }
        val availState = states[AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_AVAILABILITY]
        if (availState?.first == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNAVAILABLE) {
            return AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnavailable(
                deviceId = deviceId,
                snapshotId = snapshotId,
                unavailableReason = availState.second ?: REASON_AVAILABILITY_UNAVAILABLE_DEFAULT,
                dimension = AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_AVAILABILITY
            )
        }

        // ── 4. Stale — EVIDENCE_FRESHNESS is STALE ───────────────────────────
        val freshnessState = states[AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS]
        if (freshnessState?.first == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.STALE) {
            return AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceStale(
                deviceId = deviceId,
                snapshotId = snapshotId,
                staleReason = freshnessState.second ?: REASON_STALE_DEFAULT
            )
        }

        // ── 5. Degraded — any remaining DEGRADED or UNAVAILABLE dimension ─────
        val degradedEntry = AndroidDelegatedRuntimeAuditDimension.entries.firstOrNull { dim ->
            val s = states[dim]?.first
            s == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.DEGRADED ||
                s == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNAVAILABLE
        }
        if (degradedEntry != null) {
            val reason = states[degradedEntry]?.second ?: REASON_DEGRADED_DEFAULT
            return AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceDegraded(
                deviceId = deviceId,
                snapshotId = snapshotId,
                degradedReason = reason,
                dimension = degradedEntry
            )
        }

        // ── 6. Ready — all six dimensions are AUDITED ─────────────────────────
        return AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady(
            deviceId = deviceId,
            snapshotId = snapshotId
        )
    }

    // ── Core API — snapshot builder ───────────────────────────────────────────

    /**
     * Builds a full [AndroidDelegatedRuntimeAuditSnapshot] for the current dimension states.
     *
     * The snapshot embeds:
     *  - A freshly generated [snapshotId] (UUID).
     *  - The [AndroidDelegatedRuntimeAuditEvidence] from [evaluateEvidence].
     *  - Per-dimension [AndroidDelegatedRuntimeAuditSnapshot.DimensionAuditState] entries.
     *  - The current wall-clock timestamp in [AndroidDelegatedRuntimeAuditSnapshot.reportedAtMs].
     *
     * @param deviceId  The device identifier to embed in the snapshot.
     * @return The [AndroidDelegatedRuntimeAuditSnapshot] for the current state.
     */
    fun buildSnapshot(deviceId: String): AndroidDelegatedRuntimeAuditSnapshot {
        val snapshotId = UUID.randomUUID().toString()
        val evidence = evaluateEvidence(deviceId = deviceId, snapshotId = snapshotId)

        val dimensionStates =
            AndroidDelegatedRuntimeAuditDimension.entries.associate { dim ->
                val status = getDimensionStatus(dim)
                val reason = getDimensionReason(dim)
                dim to AndroidDelegatedRuntimeAuditSnapshot.DimensionAuditState(
                    dimension = dim,
                    status = status,
                    reason = reason
                )
            }

        return AndroidDelegatedRuntimeAuditSnapshot(
            snapshotId = snapshotId,
            deviceId = deviceId,
            evidence = evidence,
            dimensionStates = dimensionStates,
            reportedAtMs = System.currentTimeMillis()
        )
    }

    // ── Companion — constants ─────────────────────────────────────────────────

    companion object {

        // ── Evidence quality tag constants ────────────────────────────────────

        /**
         * Canonical wire tag for [AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady].
         *
         * All six audit dimensions are AUDITED and evidence is fresh.  V2 may treat the
         * Android participant as fully operational.
         */
        const val EVIDENCE_READY = "audit_evidence_ready"

        /**
         * Canonical wire tag for [AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceDegraded].
         *
         * One or more dimensions are DEGRADED; participant is available with limitations.
         */
        const val EVIDENCE_DEGRADED = "audit_evidence_degraded"

        /**
         * Canonical wire tag for [AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnavailable].
         *
         * Participant is unavailable: not registered, offline, or recovering.
         */
        const val EVIDENCE_UNAVAILABLE = "audit_evidence_unavailable"

        /**
         * Canonical wire tag for [AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceStale].
         *
         * Evidence is older than [DEFAULT_STALE_THRESHOLD_MS]; a refresh is required.
         */
        const val EVIDENCE_STALE = "audit_evidence_stale"

        /**
         * Canonical wire tag for
         * [AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceMalformedOrIncomplete].
         *
         * Evidence is malformed or incomplete; V2 must not consume it.
         */
        const val EVIDENCE_MALFORMED_INCOMPLETE = "audit_evidence_malformed_incomplete"

        /**
         * Canonical wire tag for [AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceUnverified].
         *
         * One or more dimensions have no signal; audit evidence cannot be concluded.
         */
        const val EVIDENCE_UNVERIFIED = "audit_evidence_unverified"

        // ── Integration point constants ───────────────────────────────────────

        /**
         * Integration point identifier for [DelegatedRuntimeReadinessEvaluator].
         *
         * Readiness evaluator: supplies the DELEGATED_EXECUTION_READINESS dimension signal
         * by providing a [DeviceReadinessArtifact.DeviceReadyForRelease] outcome.
         */
        const val INTEGRATION_READINESS_EVALUATOR = "DelegatedRuntimeReadinessEvaluator"

        /**
         * Integration point identifier for [CapabilityHonestyGuard].
         *
         * Capability honesty guard: supplies the CAPABILITY_HONESTY dimension signal by
         * running [CapabilityHonestyGuard.isHonest] against the current capability report.
         */
        const val INTEGRATION_CAPABILITY_HONESTY_GUARD = "CapabilityHonestyGuard"

        /**
         * Integration point identifier for [MultiDeviceParticipantOrchestrationState].
         *
         * Orchestration state: supplies PARTICIPANT_AVAILABILITY and HEALTH_STATE signals
         * derived from the current [MultiDeviceParticipantOrchestrationState.OrchestrationState].
         */
        const val INTEGRATION_ORCHESTRATION_STATE = "MultiDeviceParticipantOrchestrationState"

        /**
         * Integration point identifier for [AndroidParticipantRuntimeTruth].
         *
         * Runtime truth: supplies the PARTICIPANT_REGISTRATION signal confirming that the
         * device has completed a successful DEVICE_REGISTER exchange with V2.
         */
        const val INTEGRATION_RUNTIME_TRUTH = "AndroidParticipantRuntimeTruth"

        /**
         * Integration point identifier for [RuntimeController].
         *
         * Runtime controller: consumes [AndroidDelegatedRuntimeAuditSnapshot] and forwards
         * the audit evidence to V2 via the reconciliation signal channel.
         */
        const val INTEGRATION_RUNTIME_CONTROLLER = "RuntimeController"

        // ── Default reason constants ──────────────────────────────────────────

        /**
         * Default reason used when a MALFORMED dimension has no explicit reason registered.
         */
        const val REASON_MALFORMED_DEFAULT = "audit_dimension_data_malformed_or_incomplete"

        /**
         * Default reason used when PARTICIPANT_REGISTRATION is UNAVAILABLE with no reason.
         */
        const val REASON_REGISTRATION_UNAVAILABLE_DEFAULT =
            "participant_registration_not_completed_or_revoked"

        /**
         * Default reason used when PARTICIPANT_AVAILABILITY is UNAVAILABLE with no reason.
         */
        const val REASON_AVAILABILITY_UNAVAILABLE_DEFAULT =
            "participant_unavailable_disconnected_or_recovering"

        /**
         * Default reason used when EVIDENCE_FRESHNESS is STALE with no reason.
         */
        const val REASON_STALE_DEFAULT = "audit_evidence_freshness_threshold_exceeded"

        /**
         * Default reason used when a DEGRADED dimension has no explicit reason registered.
         */
        const val REASON_DEGRADED_DEFAULT = "audit_dimension_degraded"

        // ── Freshness configuration ───────────────────────────────────────────

        /**
         * Default staleness threshold in milliseconds.
         *
         * Audit evidence older than this value is considered stale and must not be
         * consumed by V2 as current participant state.  Default: 60 seconds.
         */
        const val DEFAULT_STALE_THRESHOLD_MS: Long = 60_000L

        // ── PR designation ────────────────────────────────────────────────────

        /**
         * The PR designation for this component.
         */
        const val INTRODUCED_PR = 68

        /**
         * Human-readable description of this component's role in the Android delegated
         * runtime audit and cross-repo evidence export model.
         */
        const val DESCRIPTION =
            "Android delegated runtime audit module: aggregates participant registration, " +
                "availability, delegated execution readiness, health state, capability honesty, " +
                "and evidence freshness into a unified structured evidence verdict; produces " +
                "AndroidDelegatedRuntimeAuditEvidence and AndroidDelegatedRuntimeAuditSnapshot " +
                "with toWireMap() for cross-repo V2 evidence ingestion and system-level " +
                "acceptance/readiness/governance audit consumption."
    }
}
