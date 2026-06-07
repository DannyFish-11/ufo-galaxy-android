package com.ufo.galaxy.runtime

/**
 * PR-9 (Android) — Structured snapshot of the Android device-side delegated runtime
 * readiness state across all five canonical readiness dimensions.
 *
 * [DelegatedRuntimeReadinessSnapshot] is the structured readiness report produced by
 * [DelegatedRuntimeReadinessEvaluator.buildSnapshot].  It captures:
 *
 *  - The overall [DeviceReadinessArtifact] conclusion for this evaluation.
 *  - Per-dimension readiness states for all five [DelegatedRuntimeReadinessDimension] values.
 *  - Identifiers and metadata enabling V2 release gate correlation.
 *
 * ## V2 release gate integration
 *
 * The snapshot is the primary artifact that a V2 release gate consumes from the Android
 * device side.  The gate must observe:
 *
 *  1. [artifact] — to determine the overall device readiness verdict.
 *  2. [dimensionStates] — to identify which specific dimensions have gaps.
 *  3. [snapshotId] — to correlate snapshot emissions and detect staleness.
 *  4. [reportedAtMs] — to detect stale snapshots that have not been refreshed.
 *
 * @property snapshotId         Stable identifier for this snapshot instance.  Monotonically
 *                              increasing per device across the session.
 * @property deviceId           The device identifier for which this snapshot was evaluated.
 * @property artifact           The overall [DeviceReadinessArtifact] conclusion.
 * @property dimensionStates    Per-dimension readiness state map; keyed by
 *                              [DelegatedRuntimeReadinessDimension].
 * @property reportedAtMs       Epoch-millisecond timestamp when this snapshot was produced.
 *
 * @see DelegatedRuntimeReadinessEvaluator
 * @see DeviceReadinessArtifact
 * @see DelegatedRuntimeReadinessDimension
 */
data class DelegatedRuntimeReadinessSnapshot(
    val snapshotId: String,
    val deviceId: String,
    val artifact: DeviceReadinessArtifact,
    val dimensionStates: Map<DelegatedRuntimeReadinessDimension, DimensionReadinessState>,
    val reportedAtMs: Long
) {

    /**
     * The readiness state of a single [DelegatedRuntimeReadinessDimension].
     *
     * @property dimension   The dimension this state describes.
     * @property status      The [DimensionStatus] for this dimension.
     * @property gapReason   Human-readable explanation when [status] is [DimensionStatus.GAP]
     *                       or [DimensionStatus.UNKNOWN]; null otherwise.
     */
    data class DimensionReadinessState(
        val dimension: DelegatedRuntimeReadinessDimension,
        val status: DimensionStatus,
        val gapReason: String? = null
    )

    /**
     * Status of a single readiness dimension.
     *
     * @property wireValue  Stable lowercase string used in JSON payloads.
     */
    enum class DimensionStatus(val wireValue: String) {
        /** The dimension is fully satisfied; no gap detected. */
        READY("ready"),

        /** The dimension has a detected gap; device is not ready on this dimension. */
        GAP("gap"),

        /** The dimension has no signal; readiness cannot be determined. */
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
