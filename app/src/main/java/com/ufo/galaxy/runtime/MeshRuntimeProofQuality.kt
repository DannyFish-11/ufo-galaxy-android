package com.ufo.galaxy.runtime

/**
 * PR-8 (Android) — Explicit mesh/runtime proof quality grade for producer-side readiness
 * reporting.
 *
 * [MeshRuntimeProofQuality] distinguishes how strong the evidence is behind any
 * mesh-participation or runtime-readiness signal emitted by Android to V2.  Without this
 * distinction V2 may receive a readiness-adjacent signal (e.g. [isParticipationReady] ==
 * `true`, [ReadinessLevel.READY]) that *looks* live-healthy but is actually backed by
 * partial, stale, or structurally-inferred data only.
 *
 * ## Proof-quality grades
 *
 * | Grade                    | Wire value               | Semantics                                                                                 |
 * |--------------------------|--------------------------|-------------------------------------------------------------------------------------------|
 * | [LIVE]                   | `live`                   | All dimensions freshly observed; evidence is fully consumable without hedging.            |
 * | [PARTIAL]                | `partial`                | Some dimensions directly observed but others inferred or degraded; consume with caution.  |
 * | [STALE]                  | `stale`                  | Evidence was valid but is now older than [STALE_THRESHOLD_MS]; treat as non-authoritative.|
 * | [MISSING]                | `missing`                | No direct runtime measurement available; state must not be treated as live-ready.        |
 * | [STRUCTURALLY_INFERRED]  | `structurally_inferred`  | State derived from structural/gate conditions only with no observed runtime confirmation. |
 *
 * ## Derivation contract
 *
 * [derive] encodes the canonical proof-quality derivation rules for both
 * [AndroidMeshParticipationRuntimeContract] and [AndroidMeshParticipationContract]:
 *
 * 1. If participation state is INACTIVE or health is FAILED/UNKNOWN → [MISSING].
 * 2. If health is RECOVERING → [STALE] (evidence from before the recovery cycle).
 * 3. If health is HEALTHY, participation is ACTIVE, no fallback, no constraints
 *    **and** barrier is NOT_APPLICABLE or RELEASED → [LIVE].
 * 4. If health is DEGRADED **or** fallbackActive **or** barrierState is WAITING/TIMED_OUT
 *    **or** a rollout constraint is active → [PARTIAL].
 * 5. If participation state is anything other than ACTIVE (e.g. STANDBY/DRAINING) but
 *    cross-device is enabled → [STRUCTURALLY_INFERRED].
 *
 * ## V2 consumption contract
 *
 * V2 must not promote Android participation to its canonical live-ready mesh-node state
 * unless [proofQuality] is [LIVE].  All other grades must be treated as degraded, pending,
 * or missing proof respectively.
 *
 * @property wireValue Stable lowercase wire value included in all V2-facing payloads.
 * @property description Human-readable description for diagnostics surfaces.
 */
enum class MeshRuntimeProofQuality(
    val wireValue: String,
    val description: String
) {

    /**
     * All runtime dimensions have been freshly observed and are consistent.  Health is
     * confirmed HEALTHY, participation is confirmed ACTIVE, no fallback path is engaged,
     * and no rollout constraint is blocking the primary path.  V2 may treat Android as a
     * fully live mesh node without applying any proof-quality hedge.
     */
    LIVE(
        wireValue = "live",
        description = "All runtime dimensions freshly observed and consistent; fully live proof"
    ),

    /**
     * One or more runtime dimensions are observed but compromised: health is DEGRADED,
     * a fallback tier is active, a policy constraint is limiting the primary path, or the
     * barrier is in a non-nominal state.  Participation is possible but the proof is not
     * fully authoritative.  V2 should apply reduced dispatch priority or additional hedging.
     */
    PARTIAL(
        wireValue = "partial",
        description = "Some dimensions observed but one or more are degraded, constrained, or in fallback"
    ),

    /**
     * Evidence was valid during a previous observation window but is now older than
     * [STALE_THRESHOLD_MS].  This grade is also emitted when health is RECOVERING, since
     * the runtime is re-establishing itself from a prior degraded or failed state.  V2
     * must request a fresh proof cycle before treating the participant as live-ready.
     */
    STALE(
        wireValue = "stale",
        description = "Evidence is older than stale threshold or runtime is in post-recovery warmup"
    ),

    /**
     * No direct runtime measurement is available.  Participation state is INACTIVE, health
     * is FAILED or UNKNOWN, or cross-device gates are fully closed.  The mesh/runtime
     * proof is absent; V2 must not use this signal for readiness decisions.
     */
    MISSING(
        wireValue = "missing",
        description = "No direct runtime measurement available; proof is absent"
    ),

    /**
     * State has been derived from structural gate conditions (e.g. cross-device enabled,
     * rollout flag set) without any observed runtime confirmation that the participant is
     * actually functioning.  V2 must not promote this to live-ready without additional
     * evidence from a real execution cycle or health check.
     */
    STRUCTURALLY_INFERRED(
        wireValue = "structurally_inferred",
        description = "State derived from structural/gate conditions only; no observed runtime confirmation"
    );

    companion object {

        /**
         * Evidence age beyond which a HEALTHY/ACTIVE proof is reclassified as [STALE].
         * Intentionally generous at 60 seconds to align with the stale threshold used by
         * [ParticipantLifecycleTruthReportBuilder.STALE_THRESHOLD_MS].
         */
        const val STALE_THRESHOLD_MS: Long = 60_000L

        /** Canonical wire key for embedding proof quality in V2-facing payloads. */
        const val WIRE_KEY = "mesh_runtime_proof_quality"

        /** All stable wire values; used for schema-drift validation. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()

        /**
         * Returns the [MeshRuntimeProofQuality] for the given [wireValue], or `null`.
         */
        fun fromWireValue(value: String?): MeshRuntimeProofQuality? =
            entries.firstOrNull { it.wireValue == value }

        /**
         * Canonical proof-quality derivation from live Android runtime signals.
         *
         * Evaluation order matters: each rule is evaluated in priority order and the first
         * matching rule determines the grade.
         *
         * @param healthState       Current [ParticipantHealthState].
         * @param participationState Current [RuntimeHostDescriptor.HostParticipationState].
         * @param barrierState      Current [BarrierParticipationState].
         * @param fallbackActive    Whether a fallback execution tier is engaged.
         * @param crossDeviceAllowed Whether cross-device collaboration is enabled.
         * @param delegatedExecutionAllowed Whether delegated execution is permitted.
         */
        fun derive(
            healthState: ParticipantHealthState,
            participationState: RuntimeHostDescriptor.HostParticipationState,
            barrierState: BarrierParticipationState,
            fallbackActive: Boolean,
            crossDeviceAllowed: Boolean,
            delegatedExecutionAllowed: Boolean
        ): MeshRuntimeProofQuality {

            // Rule 1: cross-device disabled or participation inactive or health is failed/unknown
            // → no usable proof at all.
            if (!crossDeviceAllowed ||
                participationState == RuntimeHostDescriptor.HostParticipationState.INACTIVE ||
                healthState == ParticipantHealthState.FAILED ||
                healthState == ParticipantHealthState.UNKNOWN
            ) {
                return MISSING
            }

            // Rule 2: runtime is in post-recovery warmup → stale (prior evidence may be present
            // but the current cycle has not yet confirmed health).
            if (healthState == ParticipantHealthState.RECOVERING ||
                healthState == ParticipantHealthState.STARTING
            ) {
                return STALE
            }

            // Rule 3: health is HEALTHY, participation is ACTIVE, no fallback, no constraint,
            // and barrier is nominal → fully live proof.
            if (healthState == ParticipantHealthState.HEALTHY &&
                participationState == RuntimeHostDescriptor.HostParticipationState.ACTIVE &&
                !fallbackActive &&
                delegatedExecutionAllowed &&
                (barrierState == BarrierParticipationState.NOT_APPLICABLE ||
                    barrierState == BarrierParticipationState.RELEASED)
            ) {
                return LIVE
            }

            // Rule 4: health is DEGRADED, fallback is active, barrier is in a non-nominal state,
            // or a rollout constraint is active → partial proof (some dimensions compromised).
            if (healthState == ParticipantHealthState.DEGRADED ||
                fallbackActive ||
                barrierState == BarrierParticipationState.WAITING ||
                barrierState == BarrierParticipationState.TIMED_OUT ||
                !delegatedExecutionAllowed
            ) {
                return PARTIAL
            }

            // Rule 5: cross-device is enabled and gates are open, but participation state is
            // not ACTIVE (e.g. STANDBY / DRAINING) → structurally inferred only.
            return STRUCTURALLY_INFERRED
        }
    }
}
