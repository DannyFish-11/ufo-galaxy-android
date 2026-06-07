package com.ufo.galaxy.runtime

/**
 * PR-10 (Android) — Structured snapshot of the Android device-side delegated runtime
 * acceptance state across all six canonical acceptance evidence dimensions.
 *
 * [DelegatedRuntimeAcceptanceSnapshot] is the structured acceptance report produced by
 * [DelegatedRuntimeAcceptanceEvaluator.buildSnapshot].  It captures:
 *
 *  - The overall [DeviceAcceptanceArtifact] conclusion for this evaluation.
 *  - Per-dimension evidence states for all six [DelegatedRuntimeAcceptanceDimension] values.
 *  - Identifiers and metadata enabling V2 final acceptance gate correlation.
 *
 * ## V2 final acceptance gate integration
 *
 * The snapshot is the primary artifact that a V2 final acceptance / graduation gate
 * consumes from the Android device side.  The gate must observe:
 *
 *  1. [artifact] — to determine the overall device acceptance verdict.
 *  2. [dimensionStates] — to identify which specific dimensions have evidence gaps.
 *  3. [snapshotId] — to correlate snapshot emissions and detect staleness.
 *  4. [reportedAtMs] — to detect stale snapshots that have not been refreshed.
 *
 * @property snapshotId         Stable identifier for this snapshot instance.
 * @property deviceId           The device identifier for which this snapshot was evaluated.
 * @property artifact           The overall [DeviceAcceptanceArtifact] conclusion.
 * @property dimensionStates    Per-dimension evidence state map; keyed by
 *                              [DelegatedRuntimeAcceptanceDimension].
 * @property reportedAtMs       Epoch-millisecond timestamp when this snapshot was produced.
 *
 * @see DelegatedRuntimeAcceptanceEvaluator
 * @see DeviceAcceptanceArtifact
 * @see DelegatedRuntimeAcceptanceDimension
 */
data class DelegatedRuntimeAcceptanceSnapshot(
    val snapshotId: String,
    val deviceId: String,
    val artifact: DeviceAcceptanceArtifact,
    val dimensionStates: Map<DelegatedRuntimeAcceptanceDimension, DimensionEvidenceState>,
    val reportedAtMs: Long
) {

    /**
     * The evidence state of a single [DelegatedRuntimeAcceptanceDimension].
     *
     * @property dimension   The dimension this state describes.
     * @property status      The [DimensionStatus] for this dimension.
     * @property gapReason   Human-readable explanation when [status] is [DimensionStatus.GAP]
     *                       or [DimensionStatus.UNKNOWN]; null otherwise.
     */
    data class DimensionEvidenceState(
        val dimension: DelegatedRuntimeAcceptanceDimension,
        val status: DimensionStatus,
        val gapReason: String? = null
    )

    /**
     * Status of a single acceptance evidence dimension.
     *
     * @property wireValue  Stable lowercase string used in JSON payloads.
     */
    enum class DimensionStatus(val wireValue: String) {
        /** The dimension has sufficient evidence; no gap detected. */
        EVIDENCED("evidenced"),

        /** The dimension has a detected evidence gap; device is not accepted on this dimension. */
        GAP("gap"),

        /** The dimension has no signal; acceptance cannot be determined. */
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
