package com.ufo.galaxy.runtime

import java.util.UUID

/**
 * PR-9 (Android) — Android delegated runtime readiness owner / evaluator / reporting
 * layer for V2 release gate participation.
 *
 * [DelegatedRuntimeReadinessEvaluator] is the unified Android-side entry point for
 * delegated canonical runtime readiness judgment and structured artifact reporting.  It
 * answers the following questions for every device-side readiness evaluation:
 *
 *  - Are all five [DelegatedRuntimeReadinessDimension] values sufficiently ready?
 *  - Which specific dimension has the first unresolved gap?
 *  - Are there dimensions that have no signal, making readiness unknown?
 *  - What [DeviceReadinessArtifact] should be reported to the V2 release gate?
 *  - What [DelegatedRuntimeReadinessSnapshot] can be produced for the V2 gate to absorb?
 *
 * ## Background and motivation
 *
 * Before PR-9, Android delegated runtime readiness was assessed implicitly through a
 * collection of per-subsystem states ([ParticipantReadinessState], health states, etc.)
 * with no unified device-side layer that could answer the question:
 *
 *  > "Is this Android delegated runtime ready to participate in a V2 canonical release gate?"
 *
 * This meant:
 *
 *  - truth / result / execution events / compat / continuity readiness gaps were scattered
 *    across log entries and local subsystem states with no structured readiness conclusion.
 *  - V2 release gate had no structured Android readiness signal to absorb; release
 *    participation was underdetermined even when Android was nominally healthy.
 *  - Continuity / replay / reconnect readiness was not separately trackable from
 *    general health.
 *  - A single compat gap could block release without producing an actionable gap artifact.
 *
 * [DelegatedRuntimeReadinessEvaluator] closes these gaps by providing a composable,
 * testable API that operates over the five readiness dimensions and produces structured
 * [DeviceReadinessArtifact] and [DelegatedRuntimeReadinessSnapshot] outputs that the V2
 * release gate can reliably consume.
 *
 * ## Readiness dimensions
 *
 * Five dimensions govern the overall readiness verdict ([DelegatedRuntimeReadinessDimension]):
 *
 * | Dimension                                 | Description                                                                    |
 * |-------------------------------------------|--------------------------------------------------------------------------------|
 * | [DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT]             | Reconnect / replay / resume semantics are stable.   |
 * | [DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT] | Truth ownership / alignment layer is stable.        |
 * | [DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION]        | Result convergence layer is stable.                 |
 * | [DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT]               | Canonical execution event layer is stable.          |
 * | [DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING]                  | Compat / legacy blocking layer is stable.           |
 *
 * ## Evaluation logic
 *
 * [evaluateReadiness] applies the following precedence order:
 *
 * 1. **Missing signal** — if any dimension has status [DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN] →
 *    [DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal].
 *
 * 2. **Truth gap** — if [DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT]
 *    has status [DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP] →
 *    [DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap].
 *
 * 3. **Result gap** — if [DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION]
 *    has status [DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP] →
 *    [DeviceReadinessArtifact.DeviceNotReadyDueToResultGap].
 *
 * 4. **Execution event gap** — if [DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT]
 *    has status [DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP] →
 *    [DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap].
 *
 * 5. **Compat gap** — if [DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING]
 *    has status [DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP] →
 *    [DeviceReadinessArtifact.DeviceNotReadyDueToCompatGap].
 *
 * 6. **Continuity gap** — if [DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT]
 *    has status [DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP] →
 *    [DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap] with the continuity dimension gap reason
 *    (truth gap is the canonical first-priority artifact for continuity gaps in the absence
 *    of a dedicated continuity artifact, as continuity failures directly produce truth gaps).
 *
 * 7. **Ready** — all five dimensions are [DelegatedRuntimeReadinessSnapshot.DimensionStatus.READY] →
 *    [DeviceReadinessArtifact.DeviceReadyForRelease].
 *
 * ## Dimension gate API
 *
 * Each readiness dimension is governed by an internal gate that can be in one of three
 * states:
 *
 * | Gate state  | [DelegatedRuntimeReadinessSnapshot.DimensionStatus] produced |
 * |-------------|--------------------------------------------------------------|
 * | Open (ready) | [DelegatedRuntimeReadinessSnapshot.DimensionStatus.READY]   |
 * | Closed (gap) | [DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP]     |
 * | Unknown      | [DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN] |
 *
 * Use [markDimensionReady], [markDimensionGap], [markDimensionUnknown], and
 * [getDimensionStatus] to manage dimension gates.
 *
 * ## Integration points
 *
 * [DelegatedRuntimeReadinessEvaluator] establishes clear integration boundaries with
 * the following existing runtime modules:
 *
 * | Integration point constant                         | Module                                                                    | Role                                                                          |
 * |----------------------------------------------------|---------------------------------------------------------------------------|-------------------------------------------------------------------------------|
 * | [INTEGRATION_RECOVERY_OWNER]                       | [AndroidRecoveryParticipationOwner]                                       | Continuity / replay / reconnect readiness signal source.                      |
 * | [INTEGRATION_TRUTH_OWNER]                          | [AndroidLocalTruthOwnershipCoordinator]                                   | Truth ownership / central alignment readiness signal source.                  |
 * | [INTEGRATION_RESULT_CONVERGENCE]                   | [AndroidFlowAwareResultConvergenceParticipant]                            | Result convergence participation readiness signal source.                     |
 * | [INTEGRATION_EXECUTION_EVENT_OWNER]                | [AndroidCanonicalExecutionEventOwner]                                     | Canonical execution event readiness signal source.                            |
 * | [INTEGRATION_COMPAT_BLOCKING]                      | [AndroidCompatLegacyBlockingParticipant]                                  | Compat / legacy blocking readiness signal source.                             |
 * | [INTEGRATION_RUNTIME_CONTROLLER]                   | [RuntimeController]                                                       | Snapshot emission; readiness artifacts forwarded via reconciliation signal.   |
 * | [INTEGRATION_DELEGATED_FLOW_BRIDGE]                | [AndroidDelegatedFlowBridge]                                              | Flow-level readiness alignment; snapshot consumed before flow execution.      |
 *
 * ## Thread safety
 *
 * All dimension gate operations ([markDimensionReady], [markDimensionGap],
 * [markDimensionUnknown], [getDimensionStatus], [getDimensionGapReason],
 * [clearAllDimensionStates]) use [synchronized] blocks for safe cross-thread access.
 * [evaluateReadiness] and [buildSnapshot] are pure functions that rely only on the
 * synchronized accessors above.
 *
 * ## Relationship to sibling PR layers
 *
 * [DelegatedRuntimeReadinessEvaluator] is the *readiness participation* layer; it
 * aggregates signals from PR-4 through PR-8 into a unified device-side readiness verdict
 * that the V2 PR-9 release gate can consume.  It does not replace or duplicate any of the
 * per-dimension layers; it composes them into a release-gating conclusion.
 *
 * @see DelegatedRuntimeReadinessDimension
 * @see DeviceReadinessArtifact
 * @see DelegatedRuntimeReadinessSnapshot
 * @see AndroidRecoveryParticipationOwner
 * @see AndroidLocalTruthOwnershipCoordinator
 * @see AndroidFlowAwareResultConvergenceParticipant
 * @see AndroidCanonicalExecutionEventOwner
 * @see AndroidCompatLegacyBlockingParticipant
 */
class DelegatedRuntimeReadinessEvaluator {

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Per-dimension readiness gate state.
     *
     * Map key is [DelegatedRuntimeReadinessDimension]; value is a pair of
     * ([DelegatedRuntimeReadinessSnapshot.DimensionStatus], optional gap reason).
     * All dimensions start as [DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN]
     * until a signal is received.
     */
    private val _dimensionStates: MutableMap<
        DelegatedRuntimeReadinessDimension,
        Pair<DelegatedRuntimeReadinessSnapshot.DimensionStatus, String?>
    > = mutableMapOf()

    // ── Core API — dimension gate management ──────────────────────────────────

    /**
     * Marks [dimension] as ready.
     *
     * After this call, [getDimensionStatus] returns
     * [DelegatedRuntimeReadinessSnapshot.DimensionStatus.READY] for [dimension].
     *
     * @param dimension  The readiness dimension to mark ready.
     */
    fun markDimensionReady(dimension: DelegatedRuntimeReadinessDimension) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                DelegatedRuntimeReadinessSnapshot.DimensionStatus.READY to null
        }
    }

    /**
     * Marks [dimension] as having an unresolved gap.
     *
     * After this call, [getDimensionStatus] returns
     * [DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP] for [dimension].
     *
     * @param dimension  The readiness dimension to mark as having a gap.
     * @param gapReason  Human-readable explanation of the gap.
     */
    fun markDimensionGap(dimension: DelegatedRuntimeReadinessDimension, gapReason: String) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP to gapReason
        }
    }

    /**
     * Marks [dimension] as having no signal (unknown).
     *
     * After this call, [getDimensionStatus] returns
     * [DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN] for [dimension].
     *
     * @param dimension  The readiness dimension to mark as unknown.
     * @param reason     Optional explanation of why the signal is missing.
     */
    fun markDimensionUnknown(
        dimension: DelegatedRuntimeReadinessDimension,
        reason: String? = null
    ) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN to reason
        }
    }

    /**
     * Returns the current [DelegatedRuntimeReadinessSnapshot.DimensionStatus] for [dimension].
     *
     * Returns [DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN] if no signal has
     * been received for [dimension].
     *
     * @param dimension  The readiness dimension to query.
     */
    fun getDimensionStatus(
        dimension: DelegatedRuntimeReadinessDimension
    ): DelegatedRuntimeReadinessSnapshot.DimensionStatus =
        synchronized(_dimensionStates) {
            _dimensionStates[dimension]?.first
                ?: DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN
        }

    /**
     * Returns the gap reason registered for [dimension], or `null` if the dimension is
     * not in [DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP] or
     * [DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN] state.
     *
     * @param dimension  The readiness dimension to query.
     */
    fun getDimensionGapReason(dimension: DelegatedRuntimeReadinessDimension): String? =
        synchronized(_dimensionStates) { _dimensionStates[dimension]?.second }

    /**
     * Resets all dimension states to [DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN].
     *
     * Use only when the full execution era is being reset (e.g. session close /
     * process recreation) and all prior readiness conclusions are no longer applicable.
     */
    fun clearAllDimensionStates() {
        synchronized(_dimensionStates) { _dimensionStates.clear() }
    }

    // ── Core API — readiness evaluation ───────────────────────────────────────

    /**
     * Evaluates the current readiness of the Android delegated runtime and returns the
     * appropriate [DeviceReadinessArtifact].
     *
     * ## Evaluation logic
     *
     * 1. **Missing signal** — any dimension with [DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN]
     *    produces [DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal].
     *
     * 2. **Truth gap** — [DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT]
     *    with [DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP] produces
     *    [DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap].
     *
     * 3. **Result gap** — [DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION]
     *    with [DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP] produces
     *    [DeviceReadinessArtifact.DeviceNotReadyDueToResultGap].
     *
     * 4. **Execution event gap** — [DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT]
     *    with [DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP] produces
     *    [DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap].
     *
     * 5. **Compat gap** — [DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING]
     *    with [DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP] produces
     *    [DeviceReadinessArtifact.DeviceNotReadyDueToCompatGap].
     *
     * 6. **Continuity gap** — [DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT]
     *    with [DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP] produces
     *    [DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap] (continuity gaps directly
     *    produce truth gaps in the canonical readiness model).
     *
     * 7. **Ready** — all five dimensions are [DelegatedRuntimeReadinessSnapshot.DimensionStatus.READY]
     *    → [DeviceReadinessArtifact.DeviceReadyForRelease].
     *
     * @param deviceId    The device identifier to embed in the artifact.
     * @param snapshotId  The snapshot identifier to embed in the artifact.
     * @return The [DeviceReadinessArtifact] for the current dimension states.
     */
    fun evaluateReadiness(deviceId: String, snapshotId: String): DeviceReadinessArtifact {
        val states = synchronized(_dimensionStates) { HashMap(_dimensionStates) }

        // ── 1. Missing signal ──────────────────────────────────────────────────
        val unknownDimensions = DelegatedRuntimeReadinessDimension.entries.filter { dim ->
            (states[dim]?.first ?: DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN) ==
                DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN
        }.toSet()

        if (unknownDimensions.isNotEmpty()) {
            return DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal(
                deviceId = deviceId,
                snapshotId = snapshotId,
                missingDimensions = unknownDimensions
            )
        }

        // ── 2. Truth gap ───────────────────────────────────────────────────────
        val truthState =
            states[DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT]
        if (truthState?.first == DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP) {
            return DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap(
                deviceId = deviceId,
                snapshotId = snapshotId,
                gapReason = truthState.second ?: REASON_TRUTH_GAP_DEFAULT
            )
        }

        // ── 3. Result gap ──────────────────────────────────────────────────────
        val resultState =
            states[DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION]
        if (resultState?.first == DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP) {
            return DeviceReadinessArtifact.DeviceNotReadyDueToResultGap(
                deviceId = deviceId,
                snapshotId = snapshotId,
                gapReason = resultState.second ?: REASON_RESULT_GAP_DEFAULT
            )
        }

        // ── 4. Execution event gap ─────────────────────────────────────────────
        val eventState =
            states[DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT]
        if (eventState?.first == DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP) {
            return DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap(
                deviceId = deviceId,
                snapshotId = snapshotId,
                gapReason = eventState.second ?: REASON_EXECUTION_EVENT_GAP_DEFAULT
            )
        }

        // ── 5. Compat gap ──────────────────────────────────────────────────────
        val compatState =
            states[DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING]
        if (compatState?.first == DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP) {
            return DeviceReadinessArtifact.DeviceNotReadyDueToCompatGap(
                deviceId = deviceId,
                snapshotId = snapshotId,
                gapReason = compatState.second ?: REASON_COMPAT_GAP_DEFAULT
            )
        }

        // ── 6. Continuity gap ──────────────────────────────────────────────────
        val continuityState =
            states[DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT]
        if (continuityState?.first == DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP) {
            return DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap(
                deviceId = deviceId,
                snapshotId = snapshotId,
                gapReason = continuityState.second ?: REASON_CONTINUITY_GAP_DEFAULT,
                dimension = DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT
            )
        }

        // ── 7. All dimensions ready ────────────────────────────────────────────
        return DeviceReadinessArtifact.DeviceReadyForRelease(
            deviceId = deviceId,
            snapshotId = snapshotId
        )
    }

    // ── Core API — snapshot builder ───────────────────────────────────────────

    /**
     * Builds a full [DelegatedRuntimeReadinessSnapshot] for the current dimension states.
     *
     * The snapshot embeds:
     *  - A freshly generated [snapshotId] (UUID).
     *  - The [DeviceReadinessArtifact] from [evaluateReadiness].
     *  - Per-dimension [DelegatedRuntimeReadinessSnapshot.DimensionReadinessState] entries.
     *  - The current wall-clock timestamp in [DelegatedRuntimeReadinessSnapshot.reportedAtMs].
     *
     * @param deviceId  The device identifier to embed in the snapshot.
     * @return The [DelegatedRuntimeReadinessSnapshot] for the current state.
     */
    fun buildSnapshot(deviceId: String): DelegatedRuntimeReadinessSnapshot {
        val snapshotId = UUID.randomUUID().toString()
        val artifact = evaluateReadiness(deviceId = deviceId, snapshotId = snapshotId)

        val dimensionStates = DelegatedRuntimeReadinessDimension.entries.associate { dim ->
            val status = getDimensionStatus(dim)
            val gapReason = getDimensionGapReason(dim)
            dim to DelegatedRuntimeReadinessSnapshot.DimensionReadinessState(
                dimension = dim,
                status = status,
                gapReason = gapReason
            )
        }

        return DelegatedRuntimeReadinessSnapshot(
            snapshotId = snapshotId,
            deviceId = deviceId,
            artifact = artifact,
            dimensionStates = dimensionStates,
            reportedAtMs = System.currentTimeMillis()
        )
    }

    // ── Companion — constants ─────────────────────────────────────────────────

    companion object {

        // ── Artifact semantic tag constants ───────────────────────────────────

        /**
         * Canonical wire value for the [DeviceReadinessArtifact.DeviceReadyForRelease] artifact.
         *
         * All five readiness dimensions are satisfied; the device may participate in the
         * V2 canonical release gate.
         */
        const val ARTIFACT_DEVICE_READY_FOR_RELEASE = "device_ready_for_release"

        /**
         * Canonical wire value for the [DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap]
         * artifact.
         *
         * The local truth ownership / central alignment dimension (or continuity dimension
         * when it directly produces truth gaps) has an unresolved gap.
         */
        const val ARTIFACT_DEVICE_NOT_READY_DUE_TO_TRUTH_GAP =
            "device_not_ready_due_to_truth_gap"

        /**
         * Canonical wire value for the [DeviceReadinessArtifact.DeviceNotReadyDueToResultGap]
         * artifact.
         *
         * The result convergence participation dimension has an unresolved gap.
         */
        const val ARTIFACT_DEVICE_NOT_READY_DUE_TO_RESULT_GAP =
            "device_not_ready_due_to_result_gap"

        /**
         * Canonical wire value for the
         * [DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap] artifact.
         *
         * The canonical execution event dimension has an unresolved gap.
         */
        const val ARTIFACT_DEVICE_NOT_READY_DUE_TO_EXECUTION_EVENT_GAP =
            "device_not_ready_due_to_execution_event_gap"

        /**
         * Canonical wire value for the [DeviceReadinessArtifact.DeviceNotReadyDueToCompatGap]
         * artifact.
         *
         * The compat / legacy blocking dimension has an unresolved gap.
         */
        const val ARTIFACT_DEVICE_NOT_READY_DUE_TO_COMPAT_GAP =
            "device_not_ready_due_to_compat_gap"

        /**
         * Canonical wire value for the
         * [DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal] artifact.
         *
         * One or more dimensions have no signal; readiness cannot be determined.
         */
        const val ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL =
            "device_readiness_unknown_due_to_missing_signal"

        // ── Integration point constants ───────────────────────────────────────

        /**
         * Integration point identifier for [AndroidRecoveryParticipationOwner].
         *
         * Recovery owner: supplies the continuity / replay / reconnect readiness signal
         * for [DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT].
         */
        const val INTEGRATION_RECOVERY_OWNER = "AndroidRecoveryParticipationOwner"

        /**
         * Integration point identifier for [AndroidLocalTruthOwnershipCoordinator].
         *
         * Truth owner: supplies the local truth ownership / central alignment readiness
         * signal for [DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT].
         */
        const val INTEGRATION_TRUTH_OWNER = "AndroidLocalTruthOwnershipCoordinator"

        /**
         * Integration point identifier for [AndroidFlowAwareResultConvergenceParticipant].
         *
         * Result convergence participant: supplies the result convergence participation
         * readiness signal for [DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION].
         */
        const val INTEGRATION_RESULT_CONVERGENCE = "AndroidFlowAwareResultConvergenceParticipant"

        /**
         * Integration point identifier for [AndroidCanonicalExecutionEventOwner].
         *
         * Execution event owner: supplies the canonical execution event readiness signal
         * for [DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT].
         */
        const val INTEGRATION_EXECUTION_EVENT_OWNER = "AndroidCanonicalExecutionEventOwner"

        /**
         * Integration point identifier for [AndroidCompatLegacyBlockingParticipant].
         *
         * Compat blocking participant: supplies the compat / legacy blocking readiness
         * signal for [DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING].
         */
        const val INTEGRATION_COMPAT_BLOCKING = "AndroidCompatLegacyBlockingParticipant"

        /**
         * Integration point identifier for [RuntimeController].
         *
         * Runtime controller: consumes [DelegatedRuntimeReadinessSnapshot] and forwards
         * the device readiness artifact to V2 via the reconciliation signal channel.
         */
        const val INTEGRATION_RUNTIME_CONTROLLER = "RuntimeController"

        /**
         * Integration point identifier for [AndroidDelegatedFlowBridge].
         *
         * Delegated flow bridge: aligns flow-level readiness before accepting new
         * delegated execution; snapshot is consumed at flow entry point.
         */
        const val INTEGRATION_DELEGATED_FLOW_BRIDGE = "AndroidDelegatedFlowBridge"

        // ── Default gap reason constants ──────────────────────────────────────

        /**
         * Default gap reason used when the truth ownership / central alignment dimension
         * has a gap but no explicit reason was registered.
         */
        const val REASON_TRUTH_GAP_DEFAULT =
            "local_truth_ownership_central_alignment_gap_detected"

        /**
         * Default gap reason used when the result convergence participation dimension
         * has a gap but no explicit reason was registered.
         */
        const val REASON_RESULT_GAP_DEFAULT =
            "result_convergence_participation_gap_detected"

        /**
         * Default gap reason used when the canonical execution event dimension has a gap
         * but no explicit reason was registered.
         */
        const val REASON_EXECUTION_EVENT_GAP_DEFAULT =
            "canonical_execution_event_gap_detected"

        /**
         * Default gap reason used when the compat / legacy blocking dimension has a gap
         * but no explicit reason was registered.
         */
        const val REASON_COMPAT_GAP_DEFAULT =
            "compat_legacy_blocking_gap_detected"

        /**
         * Default gap reason used when the continuity / replay / reconnect dimension has
         * a gap but no explicit reason was registered.
         */
        const val REASON_CONTINUITY_GAP_DEFAULT =
            "continuity_replay_reconnect_gap_detected"

        // ── PR designation ────────────────────────────────────────────────────

        /**
         * The PR designation for this component.
         */
        const val INTRODUCED_PR = 9

        /**
         * Human-readable description of this component's role in the delegated runtime
         * readiness / release participation model.
         */
        const val DESCRIPTION =
            "Android delegated runtime readiness owner / evaluator / reporting layer: " +
                "aggregates continuity/replay/reconnect, truth ownership/central alignment, " +
                "result convergence, canonical execution event, and compat/legacy blocking " +
                "readiness signals into a unified device-side readiness verdict; produces " +
                "structured DeviceReadinessArtifact and DelegatedRuntimeReadinessSnapshot " +
                "outputs for V2 release gate participation."
    }
}
