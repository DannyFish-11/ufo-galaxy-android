package com.ufo.galaxy.runtime

import java.util.UUID

/**
 * PR-10 (Android) — Android delegated runtime final acceptance / graduation participation
 * owner / evaluator / reporting layer for V2 final acceptance gate participation.
 *
 * [DelegatedRuntimeAcceptanceEvaluator] is the unified Android-side entry point for
 * delegated canonical runtime acceptance judgment and structured artifact reporting.  It
 * answers the following questions for every device-side acceptance evaluation:
 *
 *  - Has readiness been established as a prerequisite for graduation?
 *  - Are all six [DelegatedRuntimeAcceptanceDimension] evidence values sufficient?
 *  - Which specific dimension has the first unresolved evidence gap?
 *  - Are there dimensions that have no signal, making acceptance unknown?
 *  - What [DeviceAcceptanceArtifact] should be reported to the V2 graduation gate?
 *  - What [DelegatedRuntimeAcceptanceSnapshot] can be produced for the V2 gate to absorb?
 *
 * ## Background and motivation
 *
 * PR-9 established Android delegated runtime readiness — the ability to answer "is this
 * device ready to participate in a V2 canonical release gate?"  However, readiness only
 * reflects whether the device *looks* capable; it does not answer whether the device has
 * cross-dimensional evidence supporting canonical graduation.
 *
 * [DelegatedRuntimeAcceptanceEvaluator] closes this gap by building a second evaluation
 * layer on top of readiness that asks:
 *
 *  - Is there evidence across continuity / replay / reconnect boundaries?
 *  - Is there evidence of stable truth ownership and V2 alignment?
 *  - Is there evidence of reliable result convergence participation?
 *  - Is there evidence of correct canonical execution event emission?
 *  - Is there evidence that compat bypass risk has been resolved?
 *  - Has readiness itself been confirmed as a prerequisite?
 *
 * This produces an evidence-backed [DeviceAcceptanceArtifact] that the V2 PR-10 final
 * acceptance gate can reliably absorb as a high-confidence graduation signal.
 *
 * ## Acceptance dimensions
 *
 * Six dimensions govern the overall acceptance verdict ([DelegatedRuntimeAcceptanceDimension]):
 *
 * | Dimension                                          | Description                                                                       |
 * |----------------------------------------------------|-----------------------------------------------------------------------------------|
 * | [DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE]               | Readiness has been established as prerequisite.    |
 * | [DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE] | Continuity / replay / reconnect evidence is present. |
 * | [DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE]   | Truth ownership / alignment evidence is present.  |
 * | [DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE]          | Result convergence participation evidence is present. |
 * | [DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE]   | Canonical execution event evidence is present.    |
 * | [DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE]      | Compat / legacy blocking evidence is present.     |
 *
 * ## Evaluation logic
 *
 * [evaluateAcceptance] applies the following precedence order:
 *
 * 1. **Incomplete signal** — if any dimension has status
 *    [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN] →
 *    [DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal].
 *
 * 2. **Missing evidence (readiness prerequisite)** — if
 *    [DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE] has status
 *    [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] →
 *    [DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence].
 *
 * 3. **Truth gap** — if [DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE]
 *    has status [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] →
 *    [DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap].
 *
 * 4. **Result gap** — if [DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE]
 *    has status [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] →
 *    [DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap].
 *
 * 5. **Execution event gap** — if [DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE]
 *    has status [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] →
 *    [DeviceAcceptanceArtifact.DeviceRejectedDueToExecutionEventGap].
 *
 * 6. **Compat bypass risk** — if [DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE]
 *    has status [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] →
 *    [DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk].
 *
 * 7. **Continuity gap** — if [DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE]
 *    has status [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] →
 *    [DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap] with the continuity dimension
 *    (continuity failures directly produce truth gaps in the canonical acceptance model).
 *
 * 8. **Accepted** — all six dimensions are [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.EVIDENCED]
 *    → [DeviceAcceptanceArtifact.DeviceAcceptedForGraduation].
 *
 * ## Dimension gate API
 *
 * Each acceptance evidence dimension is governed by an internal gate that can be in one of
 * three states:
 *
 * | Gate state          | [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus] produced    |
 * |---------------------|------------------------------------------------------------------|
 * | Evidenced           | [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.EVIDENCED]   |
 * | Gap                 | [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP]         |
 * | Unknown             | [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN]     |
 *
 * Use [markDimensionEvidenced], [markDimensionGap], [markDimensionUnknown], and
 * [getDimensionStatus] to manage dimension gates.
 *
 * ## Integration points
 *
 * [DelegatedRuntimeAcceptanceEvaluator] establishes clear integration boundaries with
 * the following existing runtime modules:
 *
 * | Integration point constant                              | Module                                                                    | Role                                                                                        |
 * |---------------------------------------------------------|---------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
 * | [INTEGRATION_READINESS_EVALUATOR]                       | [DelegatedRuntimeReadinessEvaluator]                                      | Readiness prerequisite evidence source; must have reached DeviceReadyForRelease.            |
 * | [INTEGRATION_RECOVERY_OWNER]                            | [AndroidRecoveryParticipationOwner]                                       | Continuity / replay / reconnect evidence signal source.                                     |
 * | [INTEGRATION_TRUTH_OWNER]                               | [AndroidLocalTruthOwnershipCoordinator]                                   | Truth ownership / central alignment evidence signal source.                                 |
 * | [INTEGRATION_RESULT_CONVERGENCE]                        | [AndroidFlowAwareResultConvergenceParticipant]                            | Result convergence participation evidence signal source.                                    |
 * | [INTEGRATION_EXECUTION_EVENT_OWNER]                     | [AndroidCanonicalExecutionEventOwner]                                     | Canonical execution event evidence signal source.                                           |
 * | [INTEGRATION_COMPAT_BLOCKING]                           | [AndroidCompatLegacyBlockingParticipant]                                  | Compat / legacy blocking evidence signal source.                                            |
 * | [INTEGRATION_RUNTIME_CONTROLLER]                        | [RuntimeController]                                                       | Snapshot emission; acceptance artifacts forwarded via reconciliation signal.                |
 * | [INTEGRATION_DELEGATED_FLOW_BRIDGE]                     | [AndroidDelegatedFlowBridge]                                              | Flow-level acceptance alignment; snapshot consumed at graduation decision point.            |
 *
 * ## Thread safety
 *
 * All dimension gate operations ([markDimensionEvidenced], [markDimensionGap],
 * [markDimensionUnknown], [getDimensionStatus], [getDimensionGapReason],
 * [clearAllDimensionStates]) use [synchronized] blocks for safe cross-thread access.
 * [evaluateAcceptance] and [buildSnapshot] are pure functions that rely only on the
 * synchronized accessors above.
 *
 * ## Relationship to sibling PR layers
 *
 * [DelegatedRuntimeAcceptanceEvaluator] is the *final acceptance participation* layer;
 * it builds on top of the PR-9 *readiness participation* layer
 * ([DelegatedRuntimeReadinessEvaluator]) and aggregates evidence signals from PR-4 through
 * PR-8 into a unified device-side acceptance verdict that the V2 PR-10 graduation gate
 * can consume.  It does not replace or duplicate any of the per-dimension evidence layers;
 * it composes them into a graduation decision.
 *
 * @see DelegatedRuntimeAcceptanceDimension
 * @see DeviceAcceptanceArtifact
 * @see DelegatedRuntimeAcceptanceSnapshot
 * @see DelegatedRuntimeReadinessEvaluator
 * @see AndroidRecoveryParticipationOwner
 * @see AndroidLocalTruthOwnershipCoordinator
 * @see AndroidFlowAwareResultConvergenceParticipant
 * @see AndroidCanonicalExecutionEventOwner
 * @see AndroidCompatLegacyBlockingParticipant
 */
class DelegatedRuntimeAcceptanceEvaluator {

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Per-dimension acceptance evidence gate state.
     *
     * Map key is [DelegatedRuntimeAcceptanceDimension]; value is a pair of
     * ([DelegatedRuntimeAcceptanceSnapshot.DimensionStatus], optional gap reason).
     * All dimensions start as [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN]
     * until a signal is received.
     */
    private val _dimensionStates: MutableMap<
        DelegatedRuntimeAcceptanceDimension,
        Pair<DelegatedRuntimeAcceptanceSnapshot.DimensionStatus, String?>
    > = mutableMapOf()

    // ── Core API — dimension gate management ──────────────────────────────────

    /**
     * Marks [dimension] as having sufficient evidence.
     *
     * After this call, [getDimensionStatus] returns
     * [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.EVIDENCED] for [dimension].
     *
     * @param dimension  The acceptance evidence dimension to mark as evidenced.
     */
    fun markDimensionEvidenced(dimension: DelegatedRuntimeAcceptanceDimension) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.EVIDENCED to null
        }
    }

    /**
     * Marks [dimension] as having an unresolved evidence gap.
     *
     * After this call, [getDimensionStatus] returns
     * [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] for [dimension].
     *
     * @param dimension  The acceptance evidence dimension to mark as having a gap.
     * @param gapReason  Human-readable explanation of the evidence gap.
     */
    fun markDimensionGap(dimension: DelegatedRuntimeAcceptanceDimension, gapReason: String) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP to gapReason
        }
    }

    /**
     * Marks [dimension] as having no signal (unknown).
     *
     * After this call, [getDimensionStatus] returns
     * [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN] for [dimension].
     *
     * @param dimension  The acceptance evidence dimension to mark as unknown.
     * @param reason     Optional explanation of why the signal is missing.
     */
    fun markDimensionUnknown(
        dimension: DelegatedRuntimeAcceptanceDimension,
        reason: String? = null
    ) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN to reason
        }
    }

    /**
     * Returns the current [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus] for [dimension].
     *
     * Returns [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN] if no signal has
     * been received for [dimension].
     *
     * @param dimension  The acceptance evidence dimension to query.
     */
    fun getDimensionStatus(
        dimension: DelegatedRuntimeAcceptanceDimension
    ): DelegatedRuntimeAcceptanceSnapshot.DimensionStatus =
        synchronized(_dimensionStates) {
            _dimensionStates[dimension]?.first
                ?: DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN
        }

    /**
     * Returns the gap reason registered for [dimension], or `null` if the dimension is
     * not in [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] or
     * [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN] state.
     *
     * @param dimension  The acceptance evidence dimension to query.
     */
    fun getDimensionGapReason(dimension: DelegatedRuntimeAcceptanceDimension): String? =
        synchronized(_dimensionStates) { _dimensionStates[dimension]?.second }

    /**
     * Resets all dimension states to [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN].
     *
     * Use only when the full execution era is being reset (e.g. session close /
     * process recreation) and all prior acceptance conclusions are no longer applicable.
     */
    fun clearAllDimensionStates() {
        synchronized(_dimensionStates) { _dimensionStates.clear() }
    }

    // ── Core API — acceptance evaluation ──────────────────────────────────────

    /**
     * Evaluates the current acceptance of the Android delegated runtime and returns the
     * appropriate [DeviceAcceptanceArtifact].
     *
     * ## Evaluation logic
     *
     * 1. **Incomplete signal** — any dimension with [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN]
     *    produces [DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal].
     *
     * 2. **Missing evidence (readiness prerequisite)** —
     *    [DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE] with
     *    [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] produces
     *    [DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence].
     *
     * 3. **Truth gap** — [DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE]
     *    with [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] produces
     *    [DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap].
     *
     * 4. **Result gap** — [DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE]
     *    with [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] produces
     *    [DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap].
     *
     * 5. **Execution event gap** — [DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE]
     *    with [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] produces
     *    [DeviceAcceptanceArtifact.DeviceRejectedDueToExecutionEventGap].
     *
     * 6. **Compat bypass risk** — [DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE]
     *    with [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] produces
     *    [DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk].
     *
     * 7. **Continuity gap** — [DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE]
     *    with [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] produces
     *    [DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap] (continuity failures
     *    directly produce truth gaps in the canonical acceptance model).
     *
     * 8. **Accepted** — all six dimensions are [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.EVIDENCED]
     *    → [DeviceAcceptanceArtifact.DeviceAcceptedForGraduation].
     *
     * @param deviceId    The device identifier to embed in the artifact.
     * @param snapshotId  The snapshot identifier to embed in the artifact.
     * @return The [DeviceAcceptanceArtifact] for the current dimension states.
     */
    fun evaluateAcceptance(deviceId: String, snapshotId: String): DeviceAcceptanceArtifact {
        val states = synchronized(_dimensionStates) { HashMap(_dimensionStates) }

        // ── 1. Incomplete signal ───────────────────────────────────────────────
        val unknownDimensions = DelegatedRuntimeAcceptanceDimension.entries.filter { dim ->
            (states[dim]?.first ?: DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN) ==
                DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN
        }.toSet()

        if (unknownDimensions.isNotEmpty()) {
            return DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal(
                deviceId = deviceId,
                snapshotId = snapshotId,
                missingDimensions = unknownDimensions
            )
        }

        // ── 2. Missing evidence (readiness prerequisite) ───────────────────────
        val readinessState =
            states[DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE]
        if (readinessState?.first == DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP) {
            return DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence(
                deviceId = deviceId,
                snapshotId = snapshotId,
                gapReason = readinessState.second ?: REASON_READINESS_PREREQUISITE_GAP_DEFAULT
            )
        }

        // ── 3. Truth gap ───────────────────────────────────────────────────────
        val truthState =
            states[DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE]
        if (truthState?.first == DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP) {
            return DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap(
                deviceId = deviceId,
                snapshotId = snapshotId,
                gapReason = truthState.second ?: REASON_TRUTH_GAP_DEFAULT
            )
        }

        // ── 4. Result gap ──────────────────────────────────────────────────────
        val resultState =
            states[DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE]
        if (resultState?.first == DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP) {
            return DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap(
                deviceId = deviceId,
                snapshotId = snapshotId,
                gapReason = resultState.second ?: REASON_RESULT_GAP_DEFAULT
            )
        }

        // ── 5. Execution event gap ─────────────────────────────────────────────
        val eventState =
            states[DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE]
        if (eventState?.first == DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP) {
            return DeviceAcceptanceArtifact.DeviceRejectedDueToExecutionEventGap(
                deviceId = deviceId,
                snapshotId = snapshotId,
                gapReason = eventState.second ?: REASON_EXECUTION_EVENT_GAP_DEFAULT
            )
        }

        // ── 6. Compat bypass risk ──────────────────────────────────────────────
        val compatState =
            states[DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE]
        if (compatState?.first == DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP) {
            return DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk(
                deviceId = deviceId,
                snapshotId = snapshotId,
                gapReason = compatState.second ?: REASON_COMPAT_BYPASS_RISK_DEFAULT
            )
        }

        // ── 7. Continuity gap ──────────────────────────────────────────────────
        val continuityState =
            states[DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE]
        if (continuityState?.first == DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP) {
            return DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap(
                deviceId = deviceId,
                snapshotId = snapshotId,
                gapReason = continuityState.second ?: REASON_CONTINUITY_GAP_DEFAULT,
                dimension = DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE
            )
        }

        // ── 8. All dimensions evidenced ────────────────────────────────────────
        return DeviceAcceptanceArtifact.DeviceAcceptedForGraduation(
            deviceId = deviceId,
            snapshotId = snapshotId
        )
    }

    // ── Core API — snapshot builder ───────────────────────────────────────────

    /**
     * Builds a full [DelegatedRuntimeAcceptanceSnapshot] for the current dimension states.
     *
     * The snapshot embeds:
     *  - A freshly generated [snapshotId] (UUID).
     *  - The [DeviceAcceptanceArtifact] from [evaluateAcceptance].
     *  - Per-dimension [DelegatedRuntimeAcceptanceSnapshot.DimensionEvidenceState] entries.
     *  - The current wall-clock timestamp in [DelegatedRuntimeAcceptanceSnapshot.reportedAtMs].
     *
     * @param deviceId  The device identifier to embed in the snapshot.
     * @return The [DelegatedRuntimeAcceptanceSnapshot] for the current state.
     */
    fun buildSnapshot(deviceId: String): DelegatedRuntimeAcceptanceSnapshot {
        val snapshotId = UUID.randomUUID().toString()
        val artifact = evaluateAcceptance(deviceId = deviceId, snapshotId = snapshotId)

        val dimensionStates = DelegatedRuntimeAcceptanceDimension.entries.associate { dim ->
            val status = getDimensionStatus(dim)
            val gapReason = getDimensionGapReason(dim)
            dim to DelegatedRuntimeAcceptanceSnapshot.DimensionEvidenceState(
                dimension = dim,
                status = status,
                gapReason = gapReason
            )
        }

        return DelegatedRuntimeAcceptanceSnapshot(
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
         * Canonical wire value for the [DeviceAcceptanceArtifact.DeviceAcceptedForGraduation]
         * artifact.
         *
         * All six acceptance evidence dimensions are satisfied; the device may participate
         * in the V2 final acceptance / graduation gate.
         */
        const val ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION = "device_accepted_for_graduation"

        /**
         * Canonical wire value for the
         * [DeviceAcceptanceArtifact.DeviceRejectedDueToMissingEvidence] artifact.
         *
         * The readiness prerequisite has not been established, or a required evidence
         * dimension is explicitly missing.
         */
        const val ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE =
            "device_rejected_due_to_missing_evidence"

        /**
         * Canonical wire value for the [DeviceAcceptanceArtifact.DeviceRejectedDueToTruthGap]
         * artifact.
         *
         * The truth ownership / alignment evidence dimension (or continuity evidence
         * dimension when it directly produces truth gaps) has an unresolved gap.
         */
        const val ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP =
            "device_rejected_due_to_truth_gap"

        /**
         * Canonical wire value for the [DeviceAcceptanceArtifact.DeviceRejectedDueToResultGap]
         * artifact.
         *
         * The result convergence evidence dimension has an unresolved gap.
         */
        const val ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP =
            "device_rejected_due_to_result_gap"

        /**
         * Canonical wire value for the
         * [DeviceAcceptanceArtifact.DeviceRejectedDueToExecutionEventGap] artifact.
         *
         * The canonical execution event evidence dimension has an unresolved gap.
         */
        const val ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP =
            "device_rejected_due_to_execution_event_gap"

        /**
         * Canonical wire value for the
         * [DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk] artifact.
         *
         * The compat / legacy blocking evidence dimension has an unresolved bypass risk.
         */
        const val ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK =
            "device_rejected_due_to_compat_bypass_risk"

        /**
         * Canonical wire value for the
         * [DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal] artifact.
         *
         * One or more evidence dimensions have no signal; acceptance cannot be determined.
         */
        const val ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL =
            "device_acceptance_unknown_due_to_incomplete_signal"

        // ── Integration point constants ───────────────────────────────────────

        /**
         * Integration point identifier for [DelegatedRuntimeReadinessEvaluator].
         *
         * Readiness evaluator: provides the [DeviceReadinessArtifact.DeviceReadyForRelease]
         * outcome that feeds the [DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE]
         * evidence dimension.
         */
        const val INTEGRATION_READINESS_EVALUATOR = "DelegatedRuntimeReadinessEvaluator"

        /**
         * Integration point identifier for [AndroidRecoveryParticipationOwner].
         *
         * Recovery owner: supplies the continuity / replay / reconnect evidence signal
         * for [DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE].
         */
        const val INTEGRATION_RECOVERY_OWNER = "AndroidRecoveryParticipationOwner"

        /**
         * Integration point identifier for [AndroidLocalTruthOwnershipCoordinator].
         *
         * Truth owner: supplies the truth ownership / central alignment evidence signal
         * for [DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE].
         */
        const val INTEGRATION_TRUTH_OWNER = "AndroidLocalTruthOwnershipCoordinator"

        /**
         * Integration point identifier for [AndroidFlowAwareResultConvergenceParticipant].
         *
         * Result convergence participant: supplies the result convergence participation
         * evidence signal for [DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE].
         */
        const val INTEGRATION_RESULT_CONVERGENCE = "AndroidFlowAwareResultConvergenceParticipant"

        /**
         * Integration point identifier for [AndroidCanonicalExecutionEventOwner].
         *
         * Execution event owner: supplies the canonical execution event evidence signal
         * for [DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE].
         */
        const val INTEGRATION_EXECUTION_EVENT_OWNER = "AndroidCanonicalExecutionEventOwner"

        /**
         * Integration point identifier for [AndroidCompatLegacyBlockingParticipant].
         *
         * Compat blocking participant: supplies the compat / legacy blocking evidence
         * signal for [DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE].
         */
        const val INTEGRATION_COMPAT_BLOCKING = "AndroidCompatLegacyBlockingParticipant"

        /**
         * Integration point identifier for [RuntimeController].
         *
         * Runtime controller: consumes [DelegatedRuntimeAcceptanceSnapshot] and forwards
         * the device acceptance artifact to V2 via the reconciliation signal channel.
         */
        const val INTEGRATION_RUNTIME_CONTROLLER = "RuntimeController"

        /**
         * Integration point identifier for [AndroidDelegatedFlowBridge].
         *
         * Delegated flow bridge: aligns flow-level acceptance before graduation decisions;
         * snapshot is consumed at the graduation decision point.
         */
        const val INTEGRATION_DELEGATED_FLOW_BRIDGE = "AndroidDelegatedFlowBridge"

        // ── Default gap reason constants ──────────────────────────────────────

        /**
         * Default gap reason used when the readiness prerequisite dimension has a gap
         * but no explicit reason was registered.
         */
        const val REASON_READINESS_PREREQUISITE_GAP_DEFAULT =
            "readiness_prerequisite_not_established"

        /**
         * Default gap reason used when the truth ownership / alignment evidence dimension
         * has a gap but no explicit reason was registered.
         */
        const val REASON_TRUTH_GAP_DEFAULT =
            "truth_ownership_alignment_evidence_gap_detected"

        /**
         * Default gap reason used when the result convergence evidence dimension has a
         * gap but no explicit reason was registered.
         */
        const val REASON_RESULT_GAP_DEFAULT =
            "result_convergence_evidence_gap_detected"

        /**
         * Default gap reason used when the canonical execution event evidence dimension
         * has a gap but no explicit reason was registered.
         */
        const val REASON_EXECUTION_EVENT_GAP_DEFAULT =
            "canonical_execution_event_evidence_gap_detected"

        /**
         * Default gap reason used when the compat / legacy blocking evidence dimension
         * has a gap but no explicit reason was registered.
         */
        const val REASON_COMPAT_BYPASS_RISK_DEFAULT =
            "compat_legacy_blocking_evidence_bypass_risk_detected"

        /**
         * Default gap reason used when the continuity / replay / reconnect evidence
         * dimension has a gap but no explicit reason was registered.
         */
        const val REASON_CONTINUITY_GAP_DEFAULT =
            "continuity_replay_reconnect_evidence_gap_detected"

        // ── PR designation ────────────────────────────────────────────────────

        /**
         * The PR designation for this component.
         */
        const val INTRODUCED_PR = 10

        /**
         * Human-readable description of this component's role in the delegated runtime
         * final acceptance / graduation participation model.
         */
        const val DESCRIPTION =
            "Android delegated runtime final acceptance / graduation participation owner / " +
                "evaluator / reporting layer: aggregates readiness prerequisite, " +
                "continuity/replay/reconnect, truth ownership/alignment, result convergence, " +
                "canonical execution event, and compat/legacy blocking evidence signals into " +
                "a unified device-side acceptance verdict; produces structured " +
                "DeviceAcceptanceArtifact and DelegatedRuntimeAcceptanceSnapshot outputs for " +
                "V2 final acceptance gate participation."
    }
}
