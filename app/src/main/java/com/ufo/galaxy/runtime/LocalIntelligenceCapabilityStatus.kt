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
     * No inference runtime is operational due to an explicit management decision (safe mode,
     * clean shutdown, or pre-start state) or because the startup pipeline failed.
     * The [LocalInferenceRuntimeManager] is in [LocalInferenceRuntimeManager.ManagerState.Stopped],
     * [LocalInferenceRuntimeManager.ManagerState.Starting],
     * [LocalInferenceRuntimeManager.ManagerState.Failed],
     * [LocalInferenceRuntimeManager.ManagerState.FailedStartup], or
     * [LocalInferenceRuntimeManager.ManagerState.SafeMode].
     * Local model inference is completely unavailable.
     */
    DISABLED("disabled"),

    /**
     * The runtime was previously operational but has become temporarily unavailable — e.g.,
     * after an unexpected runtime crash but **before** recovery has been initiated.
     *
     * Distinct from [DISABLED]: [UNAVAILABLE] implies a transient, unintended loss of
     * capability rather than a deliberate shutdown. Consumers may retry after a short delay.
     *
     * Distinct from [RECOVERING]: [UNAVAILABLE] is emitted before any recovery cycle begins
     * (e.g., immediately after detecting a crash). Once [LocalInferenceRuntimeManager.recoverIfUnhealthy]
     * starts executing, the state advances to [RECOVERING].
     */
    UNAVAILABLE("unavailable"),

    /**
     * The runtime manager detected an unhealthy component and is actively executing a
     * stop-then-start recovery cycle ([LocalInferenceRuntimeManager.recoverIfUnhealthy]).
     *
     * The [LocalInferenceRuntimeManager] is in [LocalInferenceRuntimeManager.ManagerState.Recovering].
     * Inference requests should be queued or deferred until the state resolves to
     * [ACTIVE] or [DEGRADED].
     */
    RECOVERING("recovering");

    companion object {

        /**
         * Derives a [LocalIntelligenceCapabilityStatus] from the current
         * [LocalInferenceRuntimeManager.ManagerState].
         *
         * | ManagerState      | Returned status  |
         * |-------------------|------------------|
         * | `Running`         | [ACTIVE]         |
         * | `Degraded`        | [DEGRADED]       |
         * | `PartialReady`    | [DEGRADED]       |
         * | `Recovering`      | [RECOVERING]     |
         * | `Unavailable`     | [UNAVAILABLE]    |
         * | `Stopped`         | [DISABLED]       |
         * | `Starting`        | [DISABLED]       |
         * | `Failed`          | [DISABLED]       |
         * | `FailedStartup`   | [DISABLED]       |
         * | `SafeMode`        | [DISABLED]       |
         */
        fun from(state: LocalInferenceRuntimeManager.ManagerState): LocalIntelligenceCapabilityStatus =
            when (state) {
                is LocalInferenceRuntimeManager.ManagerState.Running       -> ACTIVE
                is LocalInferenceRuntimeManager.ManagerState.Degraded      -> DEGRADED
                is LocalInferenceRuntimeManager.ManagerState.PartialReady  -> DEGRADED
                is LocalInferenceRuntimeManager.ManagerState.Recovering    -> RECOVERING
                is LocalInferenceRuntimeManager.ManagerState.Unavailable   -> UNAVAILABLE
                is LocalInferenceRuntimeManager.ManagerState.Stopped       -> DISABLED
                is LocalInferenceRuntimeManager.ManagerState.Starting      -> DISABLED
                is LocalInferenceRuntimeManager.ManagerState.Failed        -> DISABLED
                is LocalInferenceRuntimeManager.ManagerState.FailedStartup -> DISABLED
                is LocalInferenceRuntimeManager.ManagerState.SafeMode      -> DISABLED
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
