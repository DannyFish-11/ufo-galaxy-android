package com.ufo.galaxy.runtime

/**
 * PR-12 (Android) — Structured snapshot of the Android device-side delegated runtime
 * strategy / evolution posture across all five canonical strategy dimensions.
 *
 * [DelegatedRuntimeStrategySnapshot] is the structured strategy report produced by
 * [DelegatedRuntimeStrategyEvaluator.buildSnapshot].  It captures:
 *
 *  - The overall [DeviceStrategyArtifact] verdict for this evaluation.
 *  - Per-dimension posture states for all five [DelegatedRuntimeStrategyDimension] values.
 *  - Identifiers and metadata enabling V2 program strategy / evolution control layer
 *    correlation.
 *
 * ## V2 program strategy / evolution control layer integration
 *
 * The snapshot is the primary artifact that a V2 program strategy / evolution control
 * layer consumes from the Android device side.  The layer must observe:
 *
 *  1. [artifact] — to determine the overall device strategy verdict.
 *  2. [dimensionStates] — to identify which specific dimensions carry strategic risk.
 *  3. [snapshotId] — to correlate snapshot emissions and detect staleness.
 *  4. [reportedAtMs] — to detect stale snapshots that have not been refreshed.
 *
 * ## Relationship to sibling layers
 *
 * [DelegatedRuntimeStrategySnapshot] is the program-level strategy counterpart of
 * [DelegatedRuntimeGovernanceSnapshot]: where governance snapshots monitor ongoing
 * post-graduation compliance, strategy snapshots reflect the broader evolution posture
 * and long-term program-level alignment of the Android delegated runtime with the V2
 * canonical strategy.
 *
 * @property snapshotId       Stable identifier for this snapshot instance.
 * @property deviceId         The device identifier for which this snapshot was evaluated.
 * @property artifact         The overall [DeviceStrategyArtifact] strategy verdict.
 * @property dimensionStates  Per-dimension posture state map; keyed by
 *                            [DelegatedRuntimeStrategyDimension].
 * @property reportedAtMs     Epoch-millisecond timestamp when this snapshot was produced.
 *
 * @see DelegatedRuntimeStrategyEvaluator
 * @see DeviceStrategyArtifact
 * @see DelegatedRuntimeStrategyDimension
 */
data class DelegatedRuntimeStrategySnapshot(
    val snapshotId: String,
    val deviceId: String,
    val artifact: DeviceStrategyArtifact,
    val dimensionStates: Map<DelegatedRuntimeStrategyDimension, DimensionPostureState>,
    val reportedAtMs: Long
) {

    /**
     * The posture state of a single [DelegatedRuntimeStrategyDimension].
     *
     * @property dimension    The dimension this state describes.
     * @property status       The [DimensionStatus] for this dimension.
     * @property riskReason   Human-readable explanation when [status] is
     *                        [DimensionStatus.AT_RISK] or [DimensionStatus.UNKNOWN];
     *                        null otherwise.
     */
    data class DimensionPostureState(
        val dimension: DelegatedRuntimeStrategyDimension,
        val status: DimensionStatus,
        val riskReason: String? = null
    )

    /**
     * Posture status of a single strategy dimension.
     *
     * @property wireValue  Stable lowercase string used in JSON payloads.
     */
    enum class DimensionStatus(val wireValue: String) {
        /** The dimension is on track; no strategic risk detected on this dimension. */
        ON_TRACK("on_track"),

        /** The dimension has a detected strategic risk; evolution posture is at risk. */
        AT_RISK("at_risk"),

        /** The dimension has no program signal; strategy cannot be determined. */
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
