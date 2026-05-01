package com.ufo.galaxy.planner

import android.util.Log
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.inference.WarmupResult
import com.ufo.galaxy.runtime.NativeInferenceLoader

/**
 * MobileVLM V2-1.7B task planner backed by the llama.cpp native runtime.
 *
 * Uses JNI to call directly into `libllama.so` (the llama.cpp Android shared library),
 * bypassing any intermediary HTTP server. The GGUF model file is loaded into device RAM
 * on [loadModel] and released on [unloadModel].
 *
 * ## Availability guard
 * If `libllama.so` is absent from the APK, [NativeInferenceLoader.isLlamaCppAvailable]
 * returns false and [loadModel] returns false immediately. The application wires a
 * [DegradedPlannerService] fallback in that case (see `UFOGalaxyApplication`).
 *
 * ## JNI function conventions
 * All external functions are looked up dynamically via [System.loadLibrary] invoked by
 * [NativeInferenceLoader]. The C++ counterparts live in the llama.cpp JNI glue layer
 * (`android_llama_cpp.cpp`) and must use the Java method signature derived from this
 * fully-qualified class name:
 * `com_ufo_galaxy_planner_LlamaCppPlannerService`.
 *
 * @param modelPath    Absolute path to the GGUF model file on device storage.
 * @param maxTokens    Maximum tokens to generate per inference call.
 * @param temperature  Sampling temperature; lower = more deterministic.
 * @param timeoutMs    Soft timeout hint passed to the native layer (milliseconds).
 *                     The native runtime may not honour hard deadlines; the caller
 *                     should enforce its own deadline on the calling thread if needed.
 */
class LlamaCppPlannerService(
    val modelPath: String,
    private val maxTokens: Int = 512,
    private val temperature: Float = 0.1f,
    private val timeoutMs: Int = 30_000
) : LocalPlannerService {

    private companion object {
        private const val TAG = "LlamaCppPlannerService"

        private const val SYSTEM_PROMPT =
            "You are a mobile GUI agent. " +
            "Given a task goal and optional screen image, " +
            "produce a JSON action plan in this format: " +
            "{\"steps\":[{" +
            "\"action_type\":\"tap|scroll|type|open_app|back|home\"," +
            "\"intent\":\"<natural language target description>\"," +
            "\"parameters\":{}" +
            "}]}. " +
            "Do not include x/y screen coordinates; describe the target intent only."
    }

    /** Opaque native model handle; 0 means no model is loaded. */
    @Volatile private var nativeHandle: Long = 0L

    // ── JNI declarations ──────────────────────────────────────────────────────

    /**
     * Initialises a llama.cpp context from the GGUF file at [path].
     * Returns an opaque context handle on success, 0 on failure.
     * Must be called after `System.loadLibrary("llama")`.
     */
    private external fun nativeLoadModel(path: String, threads: Int): Long

    /**
     * Releases a context previously created by [nativeLoadModel].
     * Calling with [handle] == 0 is a no-op.
     */
    private external fun nativeFreeModel(handle: Long)

    /**
     * Runs a text-completion request against a loaded llama.cpp context.
     *
     * @param handle       Context handle returned by [nativeLoadModel].
     * @param prompt       Full prompt text (system + user content).
     * @param maxTokens    Maximum tokens to generate.
     * @param temperature  Sampling temperature.
     * @param timeoutMs    Soft generation time limit in milliseconds.
     * @return The generated text string, or null if inference fails.
     */
    private external fun nativeCompletion(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        timeoutMs: Int
    ): String?

    // ── LocalPlannerService ───────────────────────────────────────────────────

    override fun loadModel(): Boolean {
        if (!NativeInferenceLoader.isLlamaCppAvailable()) {
            Log.w(TAG, "loadModel: llama.cpp native library not available")
            return false
        }
        if (modelPath.isBlank()) {
            Log.e(TAG, "loadModel: modelPath is blank — cannot load GGUF weights")
            return false
        }
        return try {
            val handle = nativeLoadModel(modelPath, threads = Runtime.getRuntime().availableProcessors())
            if (handle == 0L) {
                Log.e(TAG, "loadModel: nativeLoadModel returned 0 — check model path and format: $modelPath")
                false
            } else {
                nativeHandle = handle
                Log.i(TAG, "MobileVLM GGUF model loaded via llama.cpp: $modelPath")
                true
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "loadModel: JNI symbol missing in libllama.so: ${e.message}")
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
                Log.w(TAG, "unloadModel: error freeing native context: ${e.message}")
            }
            nativeHandle = 0L
            Log.i(TAG, "MobileVLM llama.cpp model unloaded")
        }
    }

    override fun isModelLoaded(): Boolean = nativeHandle != 0L

    override fun warmupWithResult(): WarmupResult {
        if (!NativeInferenceLoader.isLlamaCppAvailable()) {
            return WarmupResult.failure(
                WarmupResult.WarmupStage.HEALTH_CHECK,
                "llama.cpp native library not available (libllama.so missing from APK)"
            )
        }
        if (!isModelLoaded() && !loadModel()) {
            return WarmupResult.failure(
                WarmupResult.WarmupStage.HEALTH_CHECK,
                "MobileVLM GGUF model failed to load from: $modelPath"
            )
        }
        // Dry-run: single-token completion to verify the loaded model responds correctly.
        val dryRunResult = runCompletion("ok", maxTokens = 1)
        return if (dryRunResult != null) {
            WarmupResult.success()
        } else {
            WarmupResult.failure(
                WarmupResult.WarmupStage.DRY_RUN_INFERENCE,
                "MobileVLM dry-run inference returned null"
            )
        }
    }

    override fun plan(
        goal: String,
        constraints: List<String>,
        screenshotBase64: String?
    ): LocalPlannerService.PlanResult {
        if (!isModelLoaded()) {
            return LocalPlannerService.PlanResult(
                steps = emptyList(),
                error = "MobileVLM not loaded — call loadModel() first"
            )
        }
        val prompt = buildPrompt(goal, constraints, screenshotBase64, history = emptyList())
        return runInference(prompt)
    }

    override fun replan(
        goal: String,
        constraints: List<String>,
        failedStep: LocalPlannerService.PlanStep,
        error: String,
        screenshotBase64: String?
    ): LocalPlannerService.PlanResult {
        if (!isModelLoaded()) {
            return LocalPlannerService.PlanResult(
                steps = emptyList(),
                error = "MobileVLM not loaded — call loadModel() first"
            )
        }
        val history = listOf(
            "Failed step: action=${failedStep.action_type} intent=\"${failedStep.intent}\" error=$error"
        )
        val prompt = buildPrompt(goal, constraints, screenshotBase64, history)
        return runInference(prompt)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun buildPrompt(
        goal: String,
        constraints: List<String>,
        screenshotBase64: String?,
        history: List<String>
    ): String = buildString {
        append(SYSTEM_PROMPT)
        append("\n\nGoal: $goal\n")
        if (constraints.isNotEmpty()) {
            append("Constraints: ${constraints.joinToString("; ")}\n")
        }
        if (history.isNotEmpty()) {
            append("History: ${history.joinToString(" | ")}\n")
        }
        if (screenshotBase64 != null) {
            // Native runtime receives the image embedded in the prompt text.
            // The llama.cpp multimodal extension (llava) handles <image> tokens.
            append("<image>$screenshotBase64</image>\n")
        }
        append("Produce a JSON action plan.")
    }

    private fun runInference(prompt: String): LocalPlannerService.PlanResult {
        val raw = runCompletion(prompt, maxTokens)
            ?: return LocalPlannerService.PlanResult(
                steps = emptyList(),
                error = "MobileVLM (llama.cpp): inference returned null"
            )
        return parseSteps(raw)
    }

    private fun runCompletion(prompt: String, maxTokens: Int): String? {
        return try {
            nativeCompletion(nativeHandle, prompt, maxTokens, temperature, timeoutMs)
        } catch (e: Exception) {
            Log.e(TAG, "nativeCompletion threw: ${e.message}")
            null
        }
    }

    private fun parseSteps(raw: String): LocalPlannerService.PlanResult {
        // Extract JSON block from raw model output (may contain markdown fences).
        val json = extractJsonBlock(raw)
        return try {
            val stepsRe = Regex(""""action_type"\s*:\s*"([^"]+)".*?"intent"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            val steps = stepsRe.findAll(json).map { m ->
                LocalPlannerService.PlanStep(
                    action_type = m.groupValues[1],
                    intent = m.groupValues[2]
                )
            }.toList()
            if (steps.isEmpty()) {
                LocalPlannerService.PlanResult(steps = emptyList(), error = "MobileVLM (llama.cpp): no steps parsed from: $raw")
            } else {
                LocalPlannerService.PlanResult(steps = steps)
            }
        } catch (e: Exception) {
            LocalPlannerService.PlanResult(
                steps = emptyList(),
                error = "MobileVLM (llama.cpp): parse error: ${e.message}"
            )
        }
    }

    private fun extractJsonBlock(text: String): String {
        val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val match = codeBlockRegex.find(text)
        if (match != null) return match.groupValues[1].trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text
    }
}
