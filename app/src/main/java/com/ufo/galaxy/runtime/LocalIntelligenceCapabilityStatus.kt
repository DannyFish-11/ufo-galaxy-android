package com.ufo.galaxy.runtime

/**
 * Structured capability status for the Android local intelligence subsystem.
 *
 * This enum collapses the full [LocalInferenceRuntimeManager.ManagerState] into three
 * canonical capability tiers that the gateway and capability_report consumers can act on:
 *
 * | Status       | Meaning                                                                 |
 * |--------------|-------------------------------------------------------------------------|
 * | [ACTIVE]     | Both planner and grounding runtimes are healthy. Full local AI available. |
 * | [DEGRADED]   | At least one runtime is partially operational. Limited local AI available. |
 * | [DISABLED]   | No inference runtime is operational. Local AI unavailable.              |
 *
 * ## Derivation from ManagerState
 * Use [LocalIntelligenceCapabilityStatus.from] to derive the status from a live
 * [LocalInferenceRuntimeManager.ManagerState]:
 * ```kotlin
 * val status = LocalIntelligenceCapabilityStatus.from(manager.state.value)
 * ```
 *
 * ## Wire representation
 * [wireValue] is the stable lowercase string used in `capability_report` metadata payloads
 * (key: `local_intelligence_status`).
 *
 * @property wireValue  Stable lowercase string for serialisation to wire metadata.
 */
enum class LocalIntelligenceCapabilityStatus(val wireValue: String) {

    /**
     * Both planner and grounding runtimes passed all warmup stages.
     * The [LocalInferenceRuntimeManager] is in [LocalInferenceRuntimeManager.ManagerState.Running].
     * Full local model inference is available.
     */
    ACTIVE("active"),

    /**
     * At least one inference runtime is partially operational.
     * The [LocalInferenceRuntimeManager] is in [LocalInferenceRuntimeManager.ManagerState.Degraded].
     * Limited local model inference is available (e.g., planning without grounding or vice versa).
     */
    DEGRADED("degraded"),

    /**
     * No inference runtime is operational.
     * The [LocalInferenceRuntimeManager] is in [LocalInferenceRuntimeManager.ManagerState.Stopped],
     * [LocalInferenceRuntimeManager.ManagerState.Starting],
     * [LocalInferenceRuntimeManager.ManagerState.Failed], or
     * [LocalInferenceRuntimeManager.ManagerState.SafeMode].
     * Local model inference is completely unavailable.
     */
    DISABLED("disabled");

    companion object {

        /**
         * Derives a [LocalIntelligenceCapabilityStatus] from the current
         * [LocalInferenceRuntimeManager.ManagerState].
         *
         * | ManagerState  | Returned status |
         * |---------------|-----------------|
         * | `Running`     | [ACTIVE]        |
         * | `Degraded`    | [DEGRADED]      |
         * | `Stopped`     | [DISABLED]      |
         * | `Starting`    | [DISABLED]      |
         * | `Failed`      | [DISABLED]      |
         * | `SafeMode`    | [DISABLED]      |
         */
        fun from(state: LocalInferenceRuntimeManager.ManagerState): LocalIntelligenceCapabilityStatus =
            when (state) {
                is LocalInferenceRuntimeManager.ManagerState.Running  -> ACTIVE
                is LocalInferenceRuntimeManager.ManagerState.Degraded -> DEGRADED
                is LocalInferenceRuntimeManager.ManagerState.Stopped  -> DISABLED
                is LocalInferenceRuntimeManager.ManagerState.Starting -> DISABLED
                is LocalInferenceRuntimeManager.ManagerState.Failed   -> DISABLED
                is LocalInferenceRuntimeManager.ManagerState.SafeMode -> DISABLED
            }

        /**
         * Derives a [LocalIntelligenceCapabilityStatus] from a [RuntimeStartResult].
         *
         * | RuntimeStartResult | Returned status |
         * |--------------------|-----------------|
         * | `Success`          | [ACTIVE]        |
         * | `Degraded`         | [DEGRADED]      |
         * | `Failure`          | [DISABLED]      |
         */
        fun from(result: RuntimeStartResult): LocalIntelligenceCapabilityStatus = when {
            result.isSuccess -> ACTIVE
            result.isUsable  -> DEGRADED
            else             -> DISABLED
        }

        /**
         * Parses [value] to a [LocalIntelligenceCapabilityStatus], or returns [DISABLED]
         * for unknown wire values.
         *
         * @param value Wire string from a metadata payload; may be null.
         */
        fun fromWireValue(value: String?): LocalIntelligenceCapabilityStatus =
            entries.firstOrNull { it.wireValue == value } ?: DISABLED
    }
}
