package com.ufo.galaxy.runtime

/**
 * PR-69 — Builder that derives a [ParticipantLifecycleTruthReport] from the existing
 * Android runtime state surfaces.
 *
 * [ParticipantLifecycleTruthReportBuilder] is the canonical entry point for constructing
 * a [ParticipantLifecycleTruthReport] from multi-dimensional runtime context.  It maps the
 * combined signal space (health × reconnect × registration × capability honesty) to the
 * nine-state [ParticipantLifecycleTruthState] model and validates evidence completeness.
 *
 * ## Derivation logic
 *
 * The builder evaluates the following inputs in priority order:
 *
 * 1. **Registration status** — if [RegistrationTruthStatus.NEVER_REGISTERED] or
 *    [RegistrationTruthStatus.REGISTRATION_REVOKED], the state is forced to
 *    [ParticipantLifecycleTruthState.UNREGISTERED].
 *
 * 2. **Participation state** — if [RuntimeHostDescriptor.HostParticipationState.INACTIVE]
 *    and registration is not pending, the state is forced to
 *    [ParticipantLifecycleTruthState.UNAVAILABLE].
 *
 * 3. **Formal lifecycle state** — maps [FormalParticipantLifecycleState] to the base
 *    [ParticipantLifecycleTruthState] via [ParticipantLifecycleTruthState.fromFormal].
 *
 * 4. **Recovery context** — if [ReconnectRecoveryState.RECOVERED] is observed:
 *    - If [reRegistrationOutcome] is [ReRegistrationOutcome.PENDING] →
 *      [ParticipantLifecycleTruthState.RE_REGISTERING].
 *    - If [reRegistrationOutcome] is [ReRegistrationOutcome.COMPLETED] and
 *      [capabilityAlignmentStatus] is not [CapabilityAlignmentStatus.NOT_ALIGNED] →
 *      [ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED], then resolves to
 *      [ParticipantLifecycleTruthState.ACTIVE] or [ParticipantLifecycleTruthState.DEGRADED]
 *      based on health state.
 *    - Otherwise → [ParticipantLifecycleTruthState.RECOVERED].
 *
 * ## Evidence completeness validation
 *
 * [build] classifies [LifecycleEvidenceCompleteness] as follows:
 *  - [INCONSISTENT][LifecycleEvidenceCompleteness.INCONSISTENT] — internal contradictions
 *    (e.g. ACTIVE with NEVER_REGISTERED; RECOVERED with no reconnect observed).
 *  - [INCOMPLETE][LifecycleEvidenceCompleteness.INCOMPLETE] — participantId is blank or
 *    health state is UNKNOWN.
 *  - [STALE][LifecycleEvidenceCompleteness.STALE] — [reportedAtMs] is older than
 *    [STALE_THRESHOLD_MS] from the current time.
 *  - [COMPLETE][LifecycleEvidenceCompleteness.COMPLETE] — all fields present and consistent,
 *    and recovery detail is fully specified when in a recovery phase.
 *  - [COMPLETE_WITH_GAPS][LifecycleEvidenceCompleteness.COMPLETE_WITH_GAPS] — otherwise.
 *
 * ## Integration with [AndroidDelegatedRuntimeAudit]
 *
 * [fromAuditSnapshot] produces a [ParticipantLifecycleTruthReport] from an
 * [AndroidDelegatedRuntimeAuditSnapshot], bridging the two PR-68/PR-69 truth surfaces.
 *
 * @see ParticipantLifecycleTruthReport
 * @see ParticipantLifecycleTruthState
 * @see AndroidDelegatedRuntimeAuditSnapshot
 */
object ParticipantLifecycleTruthReportBuilder {

    /**
     * Age in milliseconds beyond which a report is classified as
     * [LifecycleEvidenceCompleteness.STALE].  Defaults to 60 000 ms (one minute),
     * matching [AndroidDelegatedRuntimeAudit.DEFAULT_STALE_THRESHOLD_MS].
     */
    const val STALE_THRESHOLD_MS: Long = 60_000L

    // ── Primary build method ──────────────────────────────────────────────────

    /**
     * Derives a [ParticipantLifecycleTruthReport] from explicit multi-dimensional runtime
     * context.
     *
     * @param descriptor              Current [RuntimeHostDescriptor].
     * @param formalLifecycleState    Current [FormalParticipantLifecycleState] from the
     *                                capability gate.
     * @param reconnectRecoveryState  Current [ReconnectRecoveryState] from the WS layer.
     * @param registrationStatus      Current [RegistrationTruthStatus] for this participant.
     * @param reRegistrationOutcome   Outcome of the most recent re-registration attempt;
     *                                `null` if no re-registration has been attempted.
     * @param capabilityAlignmentStatus Current [CapabilityAlignmentStatus].
     * @param recoveredButDegraded    `true` when recovery succeeded but runtime health is
     *                                still impaired.
     * @param partiallyAligned        `true` when capability re-alignment is only partial.
     * @param reportEpoch             Monotonically increasing counter scoped to the current
     *                                runtime process.
     * @param reportedAtMs            Timestamp of report production; defaults to now.
     * @param nowMs                   Current time used for staleness check; defaults to now.
     *                                Override in tests to control time.
     * @return A fully populated [ParticipantLifecycleTruthReport].
     */
    fun build(
        descriptor: RuntimeHostDescriptor,
        formalLifecycleState: FormalParticipantLifecycleState,
        reconnectRecoveryState: ReconnectRecoveryState,
        registrationStatus: RegistrationTruthStatus,
        reRegistrationOutcome: ReRegistrationOutcome? = null,
        capabilityAlignmentStatus: CapabilityAlignmentStatus = CapabilityAlignmentStatus.NOT_ALIGNED,
        recoveredButDegraded: Boolean = false,
        partiallyAligned: Boolean = false,
        reportEpoch: Int = 0,
        reportedAtMs: Long = System.currentTimeMillis(),
        nowMs: Long = System.currentTimeMillis()
    ): ParticipantLifecycleTruthReport {

        val participantId = if (descriptor.deviceId.isNotBlank() && descriptor.hostId.isNotBlank()) {
            RuntimeIdentityContracts.participantNodeId(
                deviceId = descriptor.deviceId,
                runtimeHostId = descriptor.hostId
            )
        } else {
            ""
        }

        val reconnectObserved = reconnectRecoveryState == ReconnectRecoveryState.RECOVERED ||
            reconnectRecoveryState == ReconnectRecoveryState.RECOVERING

        val derivedState = deriveLifecycleTruthState(
            descriptor = descriptor,
            formalLifecycleState = formalLifecycleState,
            reconnectRecoveryState = reconnectRecoveryState,
            registrationStatus = registrationStatus,
            reRegistrationOutcome = reRegistrationOutcome,
            capabilityAlignmentStatus = capabilityAlignmentStatus,
            recoveredButDegraded = recoveredButDegraded
        )

        val evidenceCompleteness = classifyEvidenceCompleteness(
            participantId = participantId,
            lifecycleTruthState = derivedState,
            formalLifecycleState = formalLifecycleState,
            reconnectRecoveryState = reconnectRecoveryState,
            registrationStatus = registrationStatus,
            reconnectObserved = reconnectObserved,
            reRegistrationOutcome = reRegistrationOutcome,
            capabilityAlignmentStatus = capabilityAlignmentStatus,
            reportedAtMs = reportedAtMs,
            nowMs = nowMs
        )

        return ParticipantLifecycleTruthReport(
            participantId = participantId,
            deviceId = descriptor.deviceId,
            hostId = descriptor.hostId,
            lifecycleTruthState = derivedState,
            registrationStatus = registrationStatus,
            reconnectObserved = reconnectObserved,
            reRegistrationOutcome = reRegistrationOutcome,
            capabilityAlignmentStatus = capabilityAlignmentStatus,
            recoveredButDegraded = recoveredButDegraded,
            partiallyAligned = partiallyAligned,
            evidenceCompleteness = evidenceCompleteness,
            reportedAtMs = reportedAtMs,
            reportEpoch = reportEpoch
        )
    }

    // ── Audit snapshot bridge ─────────────────────────────────────────────────

    /**
     * Derives a [ParticipantLifecycleTruthReport] from an [AndroidDelegatedRuntimeAuditSnapshot],
     * bridging PR-68 audit state to PR-69 lifecycle truth.
     *
     * The derived report reflects the audit snapshot's dimension states as lifecycle truth:
     *  - [AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION] AUDITED →
     *    [RegistrationTruthStatus.REGISTERED]; UNAVAILABLE → [RegistrationTruthStatus.NEVER_REGISTERED].
     *  - [AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_AVAILABILITY] AUDITED + HEALTH_STATE AUDITED →
     *    [ParticipantLifecycleTruthState.ACTIVE]; DEGRADED → [DEGRADED][ParticipantLifecycleTruthState.DEGRADED].
     *  - [AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS] STALE →
     *    [LifecycleEvidenceCompleteness.STALE].
     *
     * @param snapshot         The [AndroidDelegatedRuntimeAuditSnapshot] to derive from.
     * @param participantId    Stable participant identifier; must match [snapshot]'s device.
     * @param hostId           Per-process runtime host identifier.
     * @param reconnectObserved Whether a reconnect was observed since last ACTIVE state.
     * @param reRegistrationOutcome Outcome of the most recent re-registration attempt.
     * @param capabilityAlignmentStatus Current capability alignment status.
     * @param recoveredButDegraded Whether participant recovered but remains degraded.
     * @param partiallyAligned  Whether capability re-alignment is only partial.
     * @param reportEpoch       Monotonically increasing counter for this runtime process.
     * @param reportedAtMs      Timestamp of report production; defaults to now.
     * @return A [ParticipantLifecycleTruthReport] derived from the audit snapshot.
     */
    fun fromAuditSnapshot(
        snapshot: AndroidDelegatedRuntimeAuditSnapshot,
        participantId: String,
        hostId: String,
        reconnectObserved: Boolean = false,
        reRegistrationOutcome: ReRegistrationOutcome? = null,
        capabilityAlignmentStatus: CapabilityAlignmentStatus = CapabilityAlignmentStatus.NOT_ALIGNED,
        recoveredButDegraded: Boolean = false,
        partiallyAligned: Boolean = false,
        reportEpoch: Int = 0,
        reportedAtMs: Long = System.currentTimeMillis()
    ): ParticipantLifecycleTruthReport {

        val regDimState = snapshot.dimensionStates[AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION]
            ?.status
        val availDimState = snapshot.dimensionStates[AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_AVAILABILITY]
            ?.status
        val healthDimState = snapshot.dimensionStates[AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE]
            ?.status
        val freshnessDimState = snapshot.dimensionStates[AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS]
            ?.status

        val registrationStatus = when (regDimState) {
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED -> RegistrationTruthStatus.REGISTERED
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.DEGRADED -> RegistrationTruthStatus.REGISTRATION_EXPIRED
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNAVAILABLE -> RegistrationTruthStatus.NEVER_REGISTERED
            AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN -> RegistrationTruthStatus.NEVER_REGISTERED
            else -> RegistrationTruthStatus.NEVER_REGISTERED
        }

        val lifecycleTruthState = when {
            regDimState != AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED ->
                ParticipantLifecycleTruthState.UNREGISTERED
            availDimState == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNAVAILABLE ->
                ParticipantLifecycleTruthState.RECOVERING
            healthDimState == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED &&
                availDimState == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.AUDITED ->
                ParticipantLifecycleTruthState.ACTIVE
            healthDimState == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.DEGRADED ||
                availDimState == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.DEGRADED ->
                ParticipantLifecycleTruthState.DEGRADED
            else -> ParticipantLifecycleTruthState.UNAVAILABLE
        }

        val evidenceCompleteness = when {
            freshnessDimState == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.STALE ->
                LifecycleEvidenceCompleteness.STALE
            snapshot.dimensionStates.values.any {
                it.status == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.UNKNOWN
            } -> LifecycleEvidenceCompleteness.INCOMPLETE
            snapshot.dimensionStates.values.any {
                it.status == AndroidDelegatedRuntimeAuditSnapshot.DimensionStatus.MALFORMED
            } -> LifecycleEvidenceCompleteness.INCONSISTENT
            snapshot.evidence is AndroidDelegatedRuntimeAuditEvidence.AuditEvidenceReady ->
                LifecycleEvidenceCompleteness.COMPLETE
            else -> LifecycleEvidenceCompleteness.COMPLETE_WITH_GAPS
        }

        return ParticipantLifecycleTruthReport(
            participantId = participantId,
            deviceId = snapshot.deviceId,
            hostId = hostId,
            lifecycleTruthState = lifecycleTruthState,
            registrationStatus = registrationStatus,
            reconnectObserved = reconnectObserved,
            reRegistrationOutcome = reRegistrationOutcome,
            capabilityAlignmentStatus = capabilityAlignmentStatus,
            recoveredButDegraded = recoveredButDegraded,
            partiallyAligned = partiallyAligned,
            evidenceCompleteness = evidenceCompleteness,
            reportedAtMs = reportedAtMs,
            reportEpoch = reportEpoch
        )
    }

    // ── Internal derivation helpers ───────────────────────────────────────────

    private fun deriveLifecycleTruthState(
        descriptor: RuntimeHostDescriptor,
        formalLifecycleState: FormalParticipantLifecycleState,
        reconnectRecoveryState: ReconnectRecoveryState,
        registrationStatus: RegistrationTruthStatus,
        reRegistrationOutcome: ReRegistrationOutcome?,
        capabilityAlignmentStatus: CapabilityAlignmentStatus,
        recoveredButDegraded: Boolean
    ): ParticipantLifecycleTruthState {

        // Priority 1: unregistered / revoked → always UNREGISTERED
        if (registrationStatus == RegistrationTruthStatus.NEVER_REGISTERED ||
            registrationStatus == RegistrationTruthStatus.REGISTRATION_REVOKED
        ) {
            return ParticipantLifecycleTruthState.UNREGISTERED
        }

        // Priority 2: registration pending → REGISTERING
        if (registrationStatus == RegistrationTruthStatus.REGISTRATION_PENDING) {
            return ParticipantLifecycleTruthState.REGISTERING
        }

        // Priority 3: INACTIVE participation with no recovery → UNAVAILABLE
        if (descriptor.participationState == RuntimeHostDescriptor.HostParticipationState.INACTIVE &&
            reconnectRecoveryState == ReconnectRecoveryState.IDLE
        ) {
            return ParticipantLifecycleTruthState.UNAVAILABLE
        }

        // Priority 4: formal UNAVAILABLE_FAILED → UNAVAILABLE
        if (formalLifecycleState == FormalParticipantLifecycleState.UNAVAILABLE_FAILED) {
            return ParticipantLifecycleTruthState.UNAVAILABLE
        }

        // Priority 5: recovery context resolution
        when (reconnectRecoveryState) {
            ReconnectRecoveryState.RECOVERING ->
                return ParticipantLifecycleTruthState.RECOVERING

            ReconnectRecoveryState.FAILED ->
                return ParticipantLifecycleTruthState.UNAVAILABLE

            ReconnectRecoveryState.RECOVERED -> {
                // Recovery succeeded — determine re-registration / alignment phase
                return when (reRegistrationOutcome) {
                    ReRegistrationOutcome.PENDING ->
                        ParticipantLifecycleTruthState.RE_REGISTERING

                    ReRegistrationOutcome.FAILED ->
                        ParticipantLifecycleTruthState.UNAVAILABLE

                    ReRegistrationOutcome.COMPLETED -> {
                        // Re-registration done — check capability alignment
                        when (capabilityAlignmentStatus) {
                            CapabilityAlignmentStatus.FULLY_ALIGNED,
                            CapabilityAlignmentStatus.PARTIALLY_ALIGNED ->
                                // Alignment done — resolve to health-derived final state
                                if (recoveredButDegraded) ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED
                                else ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED

                            CapabilityAlignmentStatus.ALIGNMENT_PENDING ->
                                ParticipantLifecycleTruthState.RE_REGISTERING

                            CapabilityAlignmentStatus.NOT_ALIGNED ->
                                ParticipantLifecycleTruthState.RECOVERED
                        }
                    }

                    null ->
                        // No re-registration attempted yet after recovery
                        ParticipantLifecycleTruthState.RECOVERED
                }
            }

            ReconnectRecoveryState.IDLE -> { /* fall through to formal state mapping */ }
        }

        // Priority 6: map from formal lifecycle state
        return when (formalLifecycleState) {
            FormalParticipantLifecycleState.STARTING -> {
                if (registrationStatus == RegistrationTruthStatus.REGISTERED)
                    ParticipantLifecycleTruthState.ACTIVE
                else
                    ParticipantLifecycleTruthState.REGISTERING
            }
            FormalParticipantLifecycleState.READY -> ParticipantLifecycleTruthState.ACTIVE
            FormalParticipantLifecycleState.DEGRADED -> ParticipantLifecycleTruthState.DEGRADED
            FormalParticipantLifecycleState.RECOVERING -> ParticipantLifecycleTruthState.RECOVERING
            FormalParticipantLifecycleState.UNAVAILABLE_FAILED -> ParticipantLifecycleTruthState.UNAVAILABLE
        }
    }

    private fun classifyEvidenceCompleteness(
        participantId: String,
        lifecycleTruthState: ParticipantLifecycleTruthState,
        formalLifecycleState: FormalParticipantLifecycleState,
        reconnectRecoveryState: ReconnectRecoveryState,
        registrationStatus: RegistrationTruthStatus,
        reconnectObserved: Boolean,
        reRegistrationOutcome: ReRegistrationOutcome?,
        capabilityAlignmentStatus: CapabilityAlignmentStatus,
        reportedAtMs: Long,
        nowMs: Long
    ): LifecycleEvidenceCompleteness {

        // Stale check
        if (nowMs - reportedAtMs > STALE_THRESHOLD_MS) {
            return LifecycleEvidenceCompleteness.STALE
        }

        // Incomplete: blank participantId
        if (participantId.isBlank()) {
            return LifecycleEvidenceCompleteness.INCOMPLETE
        }

        // Inconsistency checks
        val hasInconsistency = when {
            // ACTIVE requires REGISTERED
            lifecycleTruthState == ParticipantLifecycleTruthState.ACTIVE &&
                registrationStatus != RegistrationTruthStatus.REGISTERED -> true

            // RECOVERED requires reconnect observed
            lifecycleTruthState == ParticipantLifecycleTruthState.RECOVERED &&
                !reconnectObserved -> true

            // RE_REGISTERING requires reconnect observed
            lifecycleTruthState == ParticipantLifecycleTruthState.RE_REGISTERING &&
                !reconnectObserved -> true

            // CAPABILITY_RE_ALIGNED requires COMPLETED re-registration
            lifecycleTruthState == ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED &&
                reRegistrationOutcome != ReRegistrationOutcome.COMPLETED -> true

            else -> false
        }

        if (hasInconsistency) {
            return LifecycleEvidenceCompleteness.INCONSISTENT
        }

        // Complete: all recovery fields specified when in recovery phase
        val isInRecoveryPhase = ParticipantLifecycleTruthState.isRecoveryPhase(lifecycleTruthState)
        val recoveryFullySpecified = !isInRecoveryPhase ||
            (reRegistrationOutcome != null &&
                capabilityAlignmentStatus != CapabilityAlignmentStatus.NOT_ALIGNED)

        return if (recoveryFullySpecified) {
            LifecycleEvidenceCompleteness.COMPLETE
        } else {
            LifecycleEvidenceCompleteness.COMPLETE_WITH_GAPS
        }
    }
}
