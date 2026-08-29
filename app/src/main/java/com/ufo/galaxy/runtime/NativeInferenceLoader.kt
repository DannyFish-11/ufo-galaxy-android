package com.ufo.galaxy.runtime

import android.util.Log

/**
 * Detects and loads native inference runtime libraries at application startup.
 *
 * Two runtimes are probed:
 * - **llama.cpp** (`libllama.so`)(无进程内消费方:规划与定位都走 llama.cpp **服务进程**的
 *   HTTP 口,见 [com.ufo.galaxy.inference.LlamaServerController];探测保留以如实上报).
 * - **NCNN** (`libncnn.so`) — lightweight CNN inference(已退役:历史 SeeClick 栈,库探测保留以兼容能力上报字段).
 *
 * ## 这个类回答的**不是**"本地推理能不能用"
 * [isLlamaCppAvailable] / [isNcnnAvailable] 只回答一件事:**这个 APK 里带没带那个 .so**。
 * 它跟本地闭环能不能跑毫无关系 —— 本地推理由一个独立的 `llama-server` 进程承担,进程内
 * 一个原生库都不需要。历史实现拿这两个标志当能力判据,在两个方向上都报反过:本地闭环
 * 跑通了却报"不可用"(APK 不带 .so),或 llama-server 根本没起却报"可用"(APK 恰好带了)。
 *
 * 判断本地推理是否可用,唯一判据是
 * [LocalIntelligenceCapabilityStatus.isLocalInferenceUsable](由
 * [LocalInferenceRuntimeManager] 的实际生命周期状态推导)。
 *
 * Both libraries are optional at the source-code level. If a library is absent from the
 * APK (e.g., the dependency was not included in the build), [System.loadLibrary] throws
 * [UnsatisfiedLinkError], which is caught and logged gracefully. The app continues in a
 * degraded mode that routes all inference to the remote V2 service instead.
 *
 * ## Integration
 * Call [loadAll] once during [android.app.Application.onCreate]. Results are cached; all
 * subsequent reads through [isLlamaCppAvailable] / [isNcnnAvailable] are lock-free.
 *
 * ## Adding the native libraries to the build
 * 1. Add the AAR dependencies in `app/build.gradle` (see module-level Gradle file).
 * 2. Ensure `ndk { abiFilters "arm64-v8a", "armeabi-v7a" }` is configured.
 * 3. The libraries are packaged automatically; no additional `System.load` call is needed
 *    beyond what this loader performs.
 */
object NativeInferenceLoader {

    private const val TAG = "NativeInferenceLoader"

    /** Native library name for llama.cpp (maps to `libllama.so`). */
    const val LIB_LLAMA = "llama"

    /** Native library name for NCNN (maps to `libncnn.so`). */
    const val LIB_NCNN = "ncnn"

    @Volatile private var llamaCppLoaded: Boolean = false
    @Volatile private var ncnnLoaded: Boolean = false
    @Volatile private var loadAttempted: Boolean = false

    /**
     * Attempts to load both native libraries. Safe to call multiple times; after the
     * first invocation the cached results are returned immediately.
     *
     * @return A [LoadResult] summarising which libraries were successfully loaded.
     */
    fun loadAll(): LoadResult {
        if (loadAttempted) return currentResult()
        synchronized(this) {
            if (loadAttempted) return currentResult()
            llamaCppLoaded = tryLoad(LIB_LLAMA)
            ncnnLoaded = tryLoad(LIB_NCNN)
            loadAttempted = true
        }
        return currentResult().also {
            Log.i(TAG, "Native runtimes loaded — llama.cpp=${it.llamaCppAvailable}, ncnn=${it.ncnnAvailable}")
        }
    }

    /**
     * Returns true when the llama.cpp native library is available and loaded.
     * Always call [loadAll] before querying this flag.
     */
    fun isLlamaCppAvailable(): Boolean = llamaCppLoaded

    /**
     * Returns true when the NCNN native library is available and loaded.
     * Always call [loadAll] before querying this flag.
     */
    fun isNcnnAvailable(): Boolean = ncnnLoaded

    /** Resets load state. Visible for testing only; do not call in production. */
    internal fun resetForTesting() {
        synchronized(this) {
            llamaCppLoaded = false
            ncnnLoaded = false
            loadAttempted = false
        }
    }

    private fun tryLoad(libName: String): Boolean {
        return try {
            System.loadLibrary(libName)
            Log.i(TAG, "Loaded native library: lib$libName.so")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library lib$libName.so not available — local inference disabled for this runtime. ${e.message}")
            false
        }
    }

    private fun currentResult() = LoadResult(
        llamaCppAvailable = llamaCppLoaded,
        ncnnAvailable = ncnnLoaded
    )

    /**
     * Summary of the native library load attempt.
     *
     * @property llamaCppAvailable  Whether llama.cpp (`libllama.so`) was loaded successfully.
     * @property ncnnAvailable      Whether NCNN (`libncnn.so`) was loaded successfully.
     */
    data class LoadResult(
        val llamaCppAvailable: Boolean,
        val ncnnAvailable: Boolean
    ) {
        /** True when at least one runtime is available. */
        val anyAvailable: Boolean get() = llamaCppAvailable || ncnnAvailable

        /**
         * True when both `.so` files are present in the APK.
         *
         * 注意:这**不**代表"完整本地推理能力"—— 本地推理能力见
         * [LocalIntelligenceCapabilityStatus.isLocalInferenceUsable]。
         */
        val fullyAvailable: Boolean get() = llamaCppAvailable && ncnnAvailable
    }
}
