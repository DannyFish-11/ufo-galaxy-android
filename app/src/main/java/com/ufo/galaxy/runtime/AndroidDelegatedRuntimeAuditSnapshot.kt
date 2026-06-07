package com.ufo.galaxy.runtime

/**
 * PR-68 (Android) — Structured snapshot of the Android delegated runtime audit state
 * across all six canonical audit dimensions.
 *
 * [AndroidDelegatedRuntimeAuditSnapshot] is the structured audit report produced by
 * [AndroidDelegatedRuntimeAudit.buildSnapshot].  It captures:
 *
 *  - The overall [AndroidDelegatedRuntimeAuditEvidence] conclusion for this audit.
 *  - Per-dimension audit states for all six [AndroidDelegatedRuntimeAuditDimension] values.
 *  - Identifiers and metadata enabling V2 acceptance gate correlation.
 *  - A [toWireMap] export function producing a stable, V2-consumable key-value payload.
 *
 * ## V2 evidence ingestion integration
 *
 * This snapshot is the primary artifact that a V2 acceptance gate, readiness system, or
 * governance layer consumes from the Android device side.  The gate must observe:
 *
 *  1. [evidence] — to determine the overall evidence quality verdict.
 *  2. [dimensionStates] — to identify which specific dimensions have issues.
 *  3. [snapshotId] — to correlate snapshot emissions and detect staleness.
 *  4. [reportedAtMs] — to detect stale snapshots that have not been refreshed.
 *  5. [toWireMap] — to obtain the stable, serialisable payload for cross-repo export.
 *
 * ## Cross-repo evidence export
 *
 * [toWireMap] produces a flat `Map<String, Any>` with a stable schema (schema_version
 * `"1.0"`) that V2 Python code can directly ingest without Android-side library
 * dependencies.  The map contains:
 *
 *  - All scalar metadata fields (snapshot_id, device_id, reported_at_ms, etc.)
 *  - `evidence_tag`: the [AndroidDelegatedRuntimeAuditEvidence.evidenceTag] of [evidence]
 *  - `dimension_states`: a `Map<String, String>` from dimension wire-name → status wire-value
 *  - `dimension_reasons`: a `Map<String, String>` from dimension wire-name → reason (when present)
 *
 * This map is designed so that a V2 Python module can consume it via:
 * ```python
 * snapshot = android_audit.build_snapshot(device_id)
 * wire = snapshot.to_wire_map()
 * # wire["evidence_tag"] in {"audit_evidence_ready", "audit_evidence_degraded", ...}
 * ```
 *
 * @property snapshotId       Stable identifier for this snapshot instance (UUID).
 * @property deviceId         The device identifier for which this snapshot was evaluated.
 * @property evidence         The overall [AndroidDelegatedRuntimeAuditEvidence] conclusion.
 * @property dimensionStates  Per-dimension audit state map; keyed by
 *                            [AndroidDelegatedRuntimeAuditDimension].
 * @property reportedAtMs     Epoch-millisecond timestamp when this snapshot was produced.
 *
 * @see AndroidDelegatedRuntimeAudit
 * @see AndroidDelegatedRuntimeAuditEvidence
 * @see AndroidDelegatedRuntimeAuditDimension
 */
data class AndroidDelegatedRuntimeAuditSnapshot(
    val snapshotId: String,
    val deviceId: String,
    val evidence: AndroidDelegatedRuntimeAuditEvidence,
    val dimensionStates: Map<AndroidDelegatedRuntimeAuditDimension, DimensionAuditState>,
    val reportedAtMs: Long
) {

    /**
     * The audit state of a single [AndroidDelegatedRuntimeAuditDimension].
     *
     * @property dimension  The dimension this state describes.
     * @property status     The [DimensionStatus] for this dimension.
     * @property reason     Human-readable explanation when [status] is not [DimensionStatus.AUDITED];
     *                      null when dimension is fully audited.
     */
    data class DimensionAuditState(
        val dimension: AndroidDelegatedRuntimeAuditDimension,
        val status: DimensionStatus,
        val reason: String? = null
    )

    /**
     * Status of a single audit dimension.
     *
     * @property wireValue  Stable lowercase string used in JSON payloads and audit reports.
     */
    enum class DimensionStatus(val wireValue: String) {

        /** The dimension has been audited and has no issues; fully operational. */
        AUDITED("audited"),

        /** The dimension is degraded; participant is available but not fully healthy. */
        DEGRADED("degraded"),

        /** The dimension is unavailable; participant is unreachable or not registered. */
        UNAVAILABLE("unavailable"),

        /** The dimension's evidence data is stale beyond the freshness threshold. */
        STALE("stale"),

        /** The dimension's evidence data is malformed or incomplete. */
        MALFORMED("malformed"),

        /** The dimension has no signal; audit cannot be determined for this dimension. */
        UNKNOWN("unknown");

        companion object {
            /**
             * Returns the [DimensionStatus] with the given [wireValue], or `null` if not found.
             */
            fun fromValue(value: String?): DimensionStatus? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Cross-repo wire export ────────────────────────────────────────────────

    /**
     * Produces a stable, V2-consumable `Map<String, Any>` representing this snapshot.
     *
     * The returned map has schema version `"1.0"` and contains all fields required for
     * V2 evidence ingestion.  It is safe to serialise to JSON and can be consumed by V2
     * Python code without Android-side library dependencies.
     *
     * ## Map keys (schema version 1.0)
     *
     * | Key                    | Type                   | Description                                                  |
     * |------------------------|------------------------|--------------------------------------------------------------|
     * | `schema_version`       | String                 | Always `"1.0"`.                                              |
     * | `snapshot_id`          | String                 | UUID of this snapshot; stable across retransmissions.        |
     * | `device_id`            | String                 | Device identifier.                                           |
     * | `reported_at_ms`       | Long                   | Epoch-ms timestamp of snapshot production.                   |
     * | `evidence_tag`         | String                 | Stable wire tag of [evidence].                               |
     * | `dimension_states`     | Map<String, String>    | dimension wire-name → status wire-value for all 6 dimensions.|
     * | `dimension_reasons`    | Map<String, String>    | dimension wire-name → reason (only for non-AUDITED states).  |
     * | `missing_dimensions`   | List<String>           | Wire names of UNKNOWN dimensions; empty if none.             |
     * | `capability_honesty_audited` | Boolean          | `true` when CAPABILITY_HONESTY dimension is AUDITED.         |
     * | `health_state_audited` | Boolean                | `true` when HEALTH_STATE dimension is AUDITED.               |
     * | `participant_registered` | Boolean              | `true` when PARTICIPANT_REGISTRATION dimension is AUDITED.   |
     *
     * @return A stable `Map<String, Any>` for cross-repo evidence export.
     */
    fun toWireMap(): Map<String, Any> {
        val stateMap: Map<String, String> = dimensionStates.entries.associate { (dim, state) ->
            dim.wireValue to state.status.wireValue
        }
        val reasonMap: Map<String, String> = dimensionStates.entries
            .filter { (_, state) -> state.reason != null }
            .associate { (dim, state) -> dim.wireValue to state.reason!! }
        val missingList: List<String> = dimensionStates.entries
            .filter { (_, state) ->
                state.status == DimensionStatus.UNKNOWN
            }
            .map { (dim, _) -> dim.wireValue }

        return mapOf(
            "schema_version" to "1.0",
            "snapshot_id" to snapshotId,
            "device_id" to deviceId,
            "reported_at_ms" to reportedAtMs,
            "evidence_tag" to evidence.evidenceTag,
            "dimension_states" to stateMap,
            "dimension_reasons" to reasonMap,
            "missing_dimensions" to missingList,
            "capability_honesty_audited" to (
                dimensionStates[AndroidDelegatedRuntimeAuditDimension.CAPABILITY_HONESTY]
                    ?.status == DimensionStatus.AUDITED
                ),
            "health_state_audited" to (
                dimensionStates[AndroidDelegatedRuntimeAuditDimension.HEALTH_STATE]
                    ?.status == DimensionStatus.AUDITED
                ),
            "participant_registered" to (
                dimensionStates[AndroidDelegatedRuntimeAuditDimension.PARTICIPANT_REGISTRATION]
                    ?.status == DimensionStatus.AUDITED
                )
        )
    }
}
