package com.ufo.galaxy.runtime

/**
 * PR-11 (Android) — Structured snapshot of the Android device-side delegated runtime
 * post-graduation governance state across all five canonical governance dimensions.
 *
 * [DelegatedRuntimeGovernanceSnapshot] is the structured governance report produced by
 * [DelegatedRuntimePostGraduationGovernanceEvaluator.buildSnapshot].  It captures:
 *
 *  - The overall [DeviceGovernanceArtifact] verdict for this evaluation.
 *  - Per-dimension observation states for all five [DelegatedRuntimeGovernanceDimension] values.
 *  - Identifiers and metadata enabling V2 post-graduation governance layer correlation.
 *
 * ## V2 post-graduation governance layer integration
 *
 * The snapshot is the primary artifact that a V2 post-graduation governance / enforcement
 * layer consumes from the Android device side.  The layer must observe:
 *
 *  1. [artifact] — to determine the overall device governance verdict.
 *  2. [dimensionStates] — to identify which specific dimensions have regression.
 *  3. [snapshotId] — to correlate snapshot emissions and detect staleness.
 *  4. [reportedAtMs] — to detect stale snapshots that have not been refreshed.
 *
 * ## Relationship to acceptance layer
 *
 * [DelegatedRuntimeGovernanceSnapshot] is the post-graduation counterpart of
 * [DelegatedRuntimeAcceptanceSnapshot]: where acceptance snapshots are produced once at
 * the graduation gate, governance snapshots are produced continuously during the
 * post-graduation runtime phase to prove ongoing canonical compliance.
 *
 * @property snapshotId       Stable identifier for this snapshot instance.
 * @property deviceId         The device identifier for which this snapshot was evaluated.
 * @property artifact         The overall [DeviceGovernanceArtifact] governance verdict.
 * @property dimensionStates  Per-dimension observation state map; keyed by
 *                            [DelegatedRuntimeGovernanceDimension].
 * @property reportedAtMs     Epoch-millisecond timestamp when this snapshot was produced.
 *
 * @see DelegatedRuntimePostGraduationGovernanceEvaluator
 * @see DeviceGovernanceArtifact
 * @see DelegatedRuntimeGovernanceDimension
 */
data class DelegatedRuntimeGovernanceSnapshot(
    val snapshotId: String,
    val deviceId: String,
    val artifact: DeviceGovernanceArtifact,
    val dimensionStates: Map<DelegatedRuntimeGovernanceDimension, DimensionObservationState>,
    val reportedAtMs: Long
) {

    /**
     * The observation state of a single [DelegatedRuntimeGovernanceDimension].
     *
     * @property dimension          The dimension this state describes.
     * @property status             The [DimensionStatus] for this dimension.
     * @property regressionReason   Human-readable explanation when [status] is
     *                              [DimensionStatus.REGRESSION] or [DimensionStatus.UNKNOWN];
     *                              null otherwise.
     */
    data class DimensionObservationState(
        val dimension: DelegatedRuntimeGovernanceDimension,
        val status: DimensionStatus,
        val regressionReason: String? = null
    )

    /**
     * Observation status of a single post-graduation governance dimension.
     *
     * @property wireValue  Stable lowercase string used in JSON payloads.
     */
    enum class DimensionStatus(val wireValue: String) {
        /** The dimension is being observed without detected regression; governance compliant. */
        COMPLIANT("compliant"),

        /** The dimension has a detected regression; governance violation on this dimension. */
        REGRESSION("regression"),

        /** The dimension has no observation signal; governance cannot be determined. */
        UNKNOWN("unknown");

        companion object {
            /**
             * Returns the [DimensionStatus] with the given [wireValue], or `null` if not found.
             */
            fun fromValue(value: String?): DimensionStatus? =
                entries.firstOrNull { it.wireValue == value }
        }
    }
}
