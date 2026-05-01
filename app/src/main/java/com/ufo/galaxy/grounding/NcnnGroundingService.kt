package com.ufo.galaxy.grounding

import android.util.Log
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.WarmupResult
import com.ufo.galaxy.runtime.NativeInferenceLoader

/**
 * SeeClick GUI grounding engine backed by the NCNN native runtime.
 *
 * Uses JNI to call directly into `libncnn.so` (the NCNN Android shared library),
 * bypassing any intermediary HTTP server. The model param and bin files are loaded
 * into device RAM on [loadModel] and released on [unloadModel].
 *
 * ## Availability guard
 * If `libncnn.so` is absent from the APK, [NativeInferenceLoader.isNcnnAvailable]
 * returns false and [loadModel] returns false immediately. The application wires a
 * [DegradedGroundingService] fallback in that case (see `UFOGalaxyApplication`).
 *
 * ## JNI function conventions
 * All external functions are looked up dynamically via [System.loadLibrary] invoked by
 * [NativeInferenceLoader]. The C++ counterparts live in the NCNN JNI glue layer
 * (`android_ncnn_seeclick.cpp`) and must use the Java method signature derived from this
 * fully-qualified class name:
 * `com_ufo_galaxy_grounding_NcnnGroundingService`.
 *
 * ## Output encoding
 * [nativeGround] returns a float array `[x, y, confidence]` in absolute pixel
 * coordinates normalised to the input image dimensions. A null return or a zero-length
 * array indicates inference failure.
 *
 * @param modelParamPath  Absolute path to the NCNN `.param` architecture file.
 * @param modelBinPath    Absolute path to the NCNN `.bin` weight file.
 * @param timeoutMs       Soft timeout hint passed to the native layer (milliseconds).
 */
class NcnnGroundingService(
    val modelParamPath: String,
    val modelBinPath: String,
    private val timeoutMs: Int = 15_000
) : LocalGroundingService {

    private companion object {
        private const val TAG = "NcnnGroundingService"

        // Minimal 1×1 white JPEG for dry-run grounding validation.
        private const val DRY_RUN_SCREENSHOT_B64 =
            "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a" +
            "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFgABAQEAAAAAAAAA" +
            "AAAAAAAAAAf/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAA/ACoAAA=="
    }

    /** Opaque native NCNN Net handle; 0 means no model is loaded. */
    @Volatile private var nativeHandle: Long = 0L

    // ── JNI declarations ──────────────────────────────────────────────────────

    /**
     * Creates an NCNN Net and loads model weights from [paramPath] and [binPath].
     * Returns an opaque handle on success, 0 on failure.
     * Must be called after `System.loadLibrary("ncnn")`.
     */
    private external fun nativeLoadModel(paramPath: String, binPath: String): Long

    /**
     * Releases an NCNN Net previously created by [nativeLoadModel].
     * Calling with [handle] == 0 is a no-op.
     */
    private external fun nativeFreeModel(handle: Long)

    /**
     * Runs GUI-grounding inference on the device screen.
     *
     * @param handle           Handle returned by [nativeLoadModel].
     * @param screenshotBase64 Base64-encoded JPEG of the current device screen.
     * @param intentText       Natural-language action intent from the planner.
     * @param width            Screen width in pixels (hint; 0 if unknown).
     * @param height           Screen height in pixels (hint; 0 if unknown).
     * @return FloatArray `[x, y, confidence]` in pixel coordinates, or null on failure.
     */
    private external fun nativeGround(
        handle: Long,
        screenshotBase64: String,
        intentText: String,
        width: Int,
        height: Int
    ): FloatArray?

    // ── LocalGroundingService ─────────────────────────────────────────────────

    override fun loadModel(): Boolean {
        if (!NativeInferenceLoader.isNcnnAvailable()) {
            Log.w(TAG, "loadModel: NCNN native library not available")
            return false
        }
        if (modelParamPath.isBlank() || modelBinPath.isBlank()) {
            Log.e(TAG, "loadModel: modelParamPath or modelBinPath is blank — cannot load SeeClick")
            return false
        }
        return try {
            val handle = nativeLoadModel(modelParamPath, modelBinPath)
            if (handle == 0L) {
                Log.e(TAG, "loadModel: nativeLoadModel returned 0 — check param/bin paths: " +
                    "param=$modelParamPath bin=$modelBinPath")
                false
            } else {
                nativeHandle = handle
                Log.i(TAG, "SeeClick NCNN model loaded: param=$modelParamPath bin=$modelBinPath")
                true
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "loadModel: JNI symbol missing in libncnn.so: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "loadModel: unexpected error: ${e.message}")
            false
        }
    }

    override fun unloadModel() {
        val handle = nativeHandle
        if (handle != 0L) {
            try {
                nativeFreeModel(handle)
            } catch (e: Exception) {
                Log.w(TAG, "unloadModel: error freeing NCNN Net: ${e.message}")
            }
            nativeHandle = 0L
            Log.i(TAG, "SeeClick NCNN model unloaded")
        }
    }

    override fun isModelLoaded(): Boolean = nativeHandle != 0L

    override fun warmupWithResult(): WarmupResult {
        if (!NativeInferenceLoader.isNcnnAvailable()) {
            return WarmupResult.failure(
                WarmupResult.WarmupStage.HEALTH_CHECK,
                "NCNN native library not available (libncnn.so missing from APK)"
            )
        }
        if (!isModelLoaded() && !loadModel()) {
            return WarmupResult.failure(
                WarmupResult.WarmupStage.HEALTH_CHECK,
                "SeeClick NCNN model failed to load — param=$modelParamPath bin=$modelBinPath"
            )
        }
        // Dry-run: ground a 1×1 synthetic image.
        val result = runGround("dry-run", DRY_RUN_SCREENSHOT_B64, 1, 1)
        return if (result.error == null) {
            WarmupResult.success()
        } else {
            WarmupResult.failure(
                WarmupResult.WarmupStage.DRY_RUN_INFERENCE,
                "SeeClick dry-run grounding failed: ${result.error}"
            )
        }
    }

    override fun ground(
        intent: String,
        screenshotBase64: String,
        width: Int,
        height: Int
    ): LocalGroundingService.GroundingResult {
        if (!isModelLoaded()) {
            return LocalGroundingService.GroundingResult(
                x = 0, y = 0, confidence = 0f,
                element_description = "",
                error = "SeeClick not loaded — call loadModel() first"
            )
        }
        return runGround(intent, screenshotBase64, width, height)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun runGround(
        intent: String,
        screenshotBase64: String,
        width: Int,
        height: Int
    ): LocalGroundingService.GroundingResult {
        return try {
            val raw = nativeGround(nativeHandle, screenshotBase64, intent, width, height)
            if (raw == null || raw.size < 2) {
                LocalGroundingService.GroundingResult(
                    x = 0, y = 0, confidence = 0f,
                    element_description = "",
                    error = "SeeClick (NCNN): inference returned no coordinates"
                )
            } else {
                LocalGroundingService.GroundingResult(
                    x = raw[0].toInt(),
                    y = raw[1].toInt(),
                    confidence = if (raw.size > 2) raw[2] else 0f,
                    element_description = ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "nativeGround threw: ${e.message}")
            LocalGroundingService.GroundingResult(
                x = 0, y = 0, confidence = 0f,
                element_description = "",
                error = "SeeClick (NCNN): inference error: ${e.message}"
            )
        }
    }
}
