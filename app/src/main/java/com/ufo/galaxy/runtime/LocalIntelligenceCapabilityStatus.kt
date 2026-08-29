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

    /**
     * 本地推理是否真的可以承接派发 —— [ACTIVE] 或 [DEGRADED] 为真。
     *
     * 这是"本地大模型能不能用"的唯一判据。历史实现问的是
     * `NativeInferenceLoader.isLlamaCppAvailable()`(即 `libllama.so` 有没有 load 上),
     * 那是**错的**:规划与定位都走 llama.cpp **服务进程**的 HTTP 口,进程内一个原生库
     * 都不需要。于是旧口径在两个方向上都报反了 ——
     *   · 本地闭环明明跑得好好的(llama-server 在跑、权重齐、warmup 过)却报 false;
     *   · APK 里恰好带了个 `libllama.so`、llama-server 根本没起,却报 true。
     */
    val isLocalInferenceUsable: Boolean
        get() = this == ACTIVE || this == DEGRADED

    /**
     * `DeviceStateSnapshot.active_runtime_type` / `capability_report` 的取值。
     *
     * 只产出 [RUNTIME_TYPE_LLAMA_CPP] 与 [RUNTIME_TYPE_CENTER] 两种。协议上仍然合法的
     * `"NCNN"` / `"HYBRID"` 不再产出:NCNN 栈(历史 SeeClick 定位)已整体退役,官方仓
     * 从不存在 NCNN 端口,该后端在生产上从未真正供给成功过 —— 报它等于报一个不存在的
     * 能力。
     */
    val activeRuntimeType: String
        get() = if (isLocalInferenceUsable) RUNTIME_TYPE_LLAMA_CPP else RUNTIME_TYPE_CENTER

    companion object {

        /** `active_runtime_type`:本地 llama.cpp 服务承担推理。 */
        const val RUNTIME_TYPE_LLAMA_CPP = "LLAMA_CPP"

        /** `active_runtime_type`:本地推理不可用,推理由中心(V2 网关)承担。 */
        const val RUNTIME_TYPE_CENTER = "CENTER"

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
