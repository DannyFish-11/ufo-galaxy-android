package com.ufo.galaxy.runtime

/**
 * PR-4 (Android) — Authoritative capability reporting contract for V2 dispatch.
 *
 * [CapabilityAuthoritySnapshot] is the single, versioned, self-describing snapshot of
 * Android's capability authority that V2 can consume for authoritative dispatch scoring.
 * It formalizes the relationship between grounding / planning / local inference /
 * checksum / readiness signals into a cohesive, testable contract.
 *
 * ## Design goals
 *
 * 1. **Stable schema** — [SCHEMA_VERSION] allows V2 to detect contract drift across
 *    Android releases without relying on field inference.
 *
 * 2. **Explicit per-subsystem readiness** — [plannerReady] and [groundingReady] are
 *    first-class booleans, not derived from opaque map entries in
 *    `runtime_health_snapshot`.  V2 can score dispatch without parsing maps.
 *
 * 3. **Canonical inference status** — [localIntelligenceStatus] is the direct wire
 *    label of [LocalIntelligenceCapabilityStatus], the authoritative tier enum.
 *    V2 consumes exactly one field to determine inference availability.
 *
 * 4. **Model integrity gate** — [checksumValid] is an explicit, first-class signal
 *    for model checksum validity, separate from model presence.  A model can be
 *    present but corrupt; [checksumValid] surfaces this without requiring V2 to
 *    cross-reference multiple fields.
 *
 * 5. **Authority label** — [authorityLabel] is a human/machine-readable summary of
 *    the overall trust level V2 can place on this snapshot for dispatch purposes:
 *    - `"authoritative"`: all capability signals are healthy and verified.
 *    - `"degraded_authority"`: at least one subsystem is degraded but the snapshot
 *      is still usable for limited dispatch.
 *    - `"no_authority"`: inference is fully unavailable; base capabilities only.
 *    - `"pending"`: device is still warming up; capability signals are not yet stable.
 *
 * ## Authoritative field relationships
 *
 * | Condition                                         | authorityLabel         | inferenceReady |
 * |---------------------------------------------------|------------------------|----------------|
 * | plannerReady && groundingReady && checksumValid   | `"authoritative"`      | `true`         |
 * | (planner OR grounding) ready && checksumValid     | `"degraded_authority"` | `true`         |
 * | Neither planner nor grounding ready               | `"no_authority"`       | `false`        |
 * | Status is RECOVERING or Starting                  | `"pending"`            | `false`        |
 *
 * ## Wire encoding
 *
 * [toMap] produces a snake_case key map suitable for embedding in
 * [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload] as the `capability_authority`
 * field or for transmission as a standalone sub-object.
 *
 * @property schemaVersion         Stable version string for V2 schema-drift detection.
 * @property localIntelligenceStatus Wire value of [LocalIntelligenceCapabilityStatus].
 * @property plannerReady          `true` when the planner runtime is healthy/ready.
 * @property groundingReady        `true` when the grounding runtime is healthy/ready.
 * @property inferenceReady        `true` when at least one inference component is ready
 *                                 AND the model checksum is valid.
 * @property checksumValid         `true` when all registered model checksums are valid.
 * @property authorityLabel        Summary trust label for V2 dispatch scoring.
 * @property capabilityList        The exact capability strings this snapshot authorises.
 */
data class CapabilityAuthoritySnapshot(
    val schemaVersion: String,
    val localIntelligenceStatus: String,
    val plannerReady: Boolean,
    val groundingReady: Boolean,
    val inferenceReady: Boolean,
    val checksumValid: Boolean,
    val authorityLabel: String,
    val capabilityList: List<String>
) {

    /**
     * Encodes this snapshot as a snake_case map for wire transmission.
     *
     * V2 may embed this map as the `capability_authority` field in the
     * `device_state_snapshot` payload.
     */
    fun toMap(): Map<String, Any> = mapOf(
        "schema_version"            to schemaVersion,
        "local_intelligence_status" to localIntelligenceStatus,
        "planner_ready"             to plannerReady,
        "grounding_ready"           to groundingReady,
        "inference_ready"           to inferenceReady,
        "checksum_valid"            to checksumValid,
        "authority_label"           to authorityLabel,
        "capability_list"           to capabilityList
    )

    companion object {

        /**
         * Stable schema version for this contract.
         *
         * V2 reads this field to detect Android-side schema drift without field inference.
         *
         * **When to increment:**
         * - Increment for any **breaking change**: removing a field, renaming a field,
         *   changing a field's type, or changing the semantics of an existing value.
         * - Increment for any **additive change** that V2 must handle conditionally
         *   (e.g. a new authority label value that older V2 versions do not understand).
         *
         * **When NOT to increment:**
         * - Pure comment/documentation changes.
         * - Adding fields that V2 can safely ignore when absent (V2 must tolerate
         *   unknown fields gracefully if this is the strategy chosen).
         *
         * After incrementing, update this comment block and update any V2-side schema
         * version checks accordingly.
         */
        const val SCHEMA_VERSION: String = "1"

        // ── Authority label constants ─────────────────────────────────────────

        /** All capability signals are healthy and verified. Full dispatch eligible. */
        const val AUTHORITY_LABEL_AUTHORITATIVE: String = "authoritative"

        /** At least one subsystem is degraded but the snapshot is usable for limited dispatch. */
        const val AUTHORITY_LABEL_DEGRADED: String = "degraded_authority"

        /** Inference is fully unavailable; base capabilities only. No local inference dispatch. */
        const val AUTHORITY_LABEL_NONE: String = "no_authority"

        /** Device is still warming up; capability signals are not yet stable. */
        const val AUTHORITY_LABEL_PENDING: String = "pending"

        // ── Base capability constants — single source of truth via CapabilityHonestyGuard ─

        /**
         * The base capability list derived from [CapabilityHonestyGuard.BASE_CAPABILITIES].
         * Using the guard as the canonical source avoids string duplication and ensures
         * [CapabilityAuthoritySnapshot] and [CapabilityHonestyGuard] are always consistent.
         */
        private val BASE_CAPABILITY_LIST: List<String> =
            CapabilityHonestyGuard.BASE_CAPABILITIES.toList()

        private const val CAPABILITY_LOCAL_MODEL_INFERENCE: String =
            CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE

        // ── Builder from ManagerState ─────────────────────────────────────────

        /**
         * Derives a [CapabilityAuthoritySnapshot] from the current
         * [LocalInferenceRuntimeManager.ManagerState] and model integrity state.
         *
         * This is the canonical builder for the snapshot.  It encodes the
         * authoritative relationship between all capability fields so that
         * call sites do not need to duplicate the derivation logic.
         *
         * @param managerState  Current [LocalInferenceRuntimeManager.ManagerState].
         * @param checksumValid `true` when all registered model checksums pass verification.
         */
        fun from(
            managerState: LocalInferenceRuntimeManager.ManagerState,
            checksumValid: Boolean
        ): CapabilityAuthoritySnapshot {
            val status = LocalIntelligenceCapabilityStatus.from(managerState)

            val plannerReady: Boolean
            val groundingReady: Boolean

            when (managerState) {
                is LocalInferenceRuntimeManager.ManagerState.Running -> {
                    plannerReady  = managerState.snapshot.plannerHealth ==
                        RuntimeHealthSnapshot.ComponentHealth.HEALTHY
                    groundingReady = managerState.snapshot.groundingHealth ==
                        RuntimeHealthSnapshot.ComponentHealth.HEALTHY
                }
                is LocalInferenceRuntimeManager.ManagerState.Degraded -> {
                    plannerReady  = managerState.snapshot.plannerHealth ==
                        RuntimeHealthSnapshot.ComponentHealth.HEALTHY
                    groundingReady = managerState.snapshot.groundingHealth ==
                        RuntimeHealthSnapshot.ComponentHealth.HEALTHY
                }
                is LocalInferenceRuntimeManager.ManagerState.PartialReady -> {
                    // PartialReady: planner loaded, grounding still warming up.
                    plannerReady  = true
                    groundingReady = false
                }
                else -> {
                    plannerReady  = false
                    groundingReady = false
                }
            }

            // inferenceReady: at least one component is ready AND checksum is valid.
            val inferenceReady = (plannerReady || groundingReady) && checksumValid

            val authorityLabel = deriveAuthorityLabel(
                managerState   = managerState,
                plannerReady   = plannerReady,
                groundingReady = groundingReady,
                checksumValid  = checksumValid
            )

            val capabilityList: List<String> = if (inferenceReady) {
                BASE_CAPABILITY_LIST + CAPABILITY_LOCAL_MODEL_INFERENCE
            } else {
                BASE_CAPABILITY_LIST
            }

            return CapabilityAuthoritySnapshot(
                schemaVersion           = SCHEMA_VERSION,
                localIntelligenceStatus = status.wireValue,
                plannerReady            = plannerReady,
                groundingReady          = groundingReady,
                inferenceReady          = inferenceReady,
                checksumValid           = checksumValid,
                authorityLabel          = authorityLabel,
                capabilityList          = capabilityList
            )
        }

        // ── Authority label derivation ────────────────────────────────────────

        /**
         * Derives the authority label from the full set of capability signals.
         *
         * Authority label derivation rules (evaluated in priority order):
         * 1. If the manager state is RECOVERING / Starting → `"pending"`.
         * 2. If neither planner nor grounding is ready → `"no_authority"`.
         * 3. If both planner and grounding are ready AND checksum is valid → `"authoritative"`.
         * 4. Otherwise (partial readiness or checksum failure) → `"degraded_authority"`.
         */
        private fun deriveAuthorityLabel(
            managerState: LocalInferenceRuntimeManager.ManagerState,
            plannerReady: Boolean,
            groundingReady: Boolean,
            checksumValid: Boolean
        ): String = when (managerState) {
            is LocalInferenceRuntimeManager.ManagerState.Starting,
            is LocalInferenceRuntimeManager.ManagerState.Recovering,
            is LocalInferenceRuntimeManager.ManagerState.PartialReady ->
                AUTHORITY_LABEL_PENDING

            is LocalInferenceRuntimeManager.ManagerState.Running -> {
                if (plannerReady && groundingReady && checksumValid) {
                    AUTHORITY_LABEL_AUTHORITATIVE
                } else {
                    AUTHORITY_LABEL_DEGRADED
                }
            }

            is LocalInferenceRuntimeManager.ManagerState.Degraded ->
                AUTHORITY_LABEL_DEGRADED

            else -> AUTHORITY_LABEL_NONE
        }
    }
}
