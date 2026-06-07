package com.ufo.galaxy.runtime

import android.util.Log

/**
 * Detects and loads native inference runtime libraries at application startup.
 *
 * Two runtimes are managed:
 * - **llama.cpp** (`libllama.so`) — GGUF-format model execution for MobileVLM.
 * - **NCNN** (`libncnn.so`) — lightweight CNN inference for SeeClick.
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

        /** True when both runtimes are available (full local inference capability). */
        val fullyAvailable: Boolean get() = llamaCppAvailable && ncnnAvailable
    }
}
