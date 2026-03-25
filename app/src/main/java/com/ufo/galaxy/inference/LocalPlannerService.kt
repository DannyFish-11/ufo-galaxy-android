package com.ufo.galaxy.inference

/**
 * Local planner service interface for MobileVLM V2-1.7B on-device task planning.
 *
 * Pluggable runtime: llama.cpp (GGUF INT4/INT8) or MLC-LLM backend.
 * Model: mtgv/MobileVLM_V2-1.7B (HuggingFace).
 *
 * Implementations must provide load/unload lifecycle hooks to manage on-device model
 * memory. [NoOpPlannerService] is the safe default; it returns a structured error
 * without performing any inference.
 */
interface LocalPlannerService {

    // ── Structured warmup ────────────────────────────────────────────────────

    /**
     * Pre-warms the planner runtime and returns a [WarmupResult] with stage-level detail.
     *
     * Implementations should validate:
     * 1. Health endpoint reachability ([WarmupResult.WarmupStage.HEALTH_CHECK]).
     * 2. Dry-run inference success ([WarmupResult.WarmupStage.DRY_RUN_INFERENCE]).
     * 3. Valid response shape ([WarmupResult.WarmupStage.RESPONSE_VALIDATION]).
     *
     * The default implementation delegates to [prewarm] and wraps the boolean result.
     * Override this method to provide richer failure detail.
     */
    fun warmupWithResult(): WarmupResult =
        if (prewarm()) WarmupResult.success()
        else WarmupResult.failure(WarmupResult.WarmupStage.HEALTH_CHECK, "Planner warmup failed")

    /**
     * A single planned action step produced by the planner.
     *
     * @param action_type Symbolic action: "tap" | "scroll" | "type" | "open_app" | "back" | "home".
     * @param intent      Natural-language description forwarded to the grounding engine.
     * @param parameters  Action-specific key-value pairs (e.g., "text", "direction", "package").
     */
    data class PlanStep(
        val action_type: String,
        val intent: String,
        val parameters: Map<String, String> = emptyMap()
    )

    /**
     * Result from a [plan] or [replan] call.
     * [error] is non-null when planning fails; [steps] is empty in that case.
     */
    data class PlanResult(
        val steps: List<PlanStep>,
        val error: String? = null
    )

    /**
     * Pre-warms the inference server by performing a health ping and optionally
     * sending a minimal dry-run request to bring JIT/model weights into cache.
     * Returns true if the server is reachable after pre-warming.
     *
     * The default implementation delegates to [loadModel].
     */
    fun prewarm(): Boolean = loadModel()

    /** Loads the MobileVLM model weights into device memory. Returns true on success. */
    fun loadModel(): Boolean

    /** Releases model weights from device memory. */
    fun unloadModel()

    /** Returns true if the model is currently loaded and ready for inference. */
    fun isModelLoaded(): Boolean

    /**
     * Produces an ordered list of action steps for [goal].
     *
     * @param goal            High-level natural-language task objective.
     * @param constraints     Optional constraint strings from the gateway.
     * @param screenshotBase64 Optional Base64-encoded JPEG of the current screen.
     * @return [PlanResult] with steps, or with a non-null [PlanResult.error] on failure.
     */
    fun plan(
        goal: String,
        constraints: List<String>,
        screenshotBase64: String? = null
    ): PlanResult

    /**
     * Produces a revised plan after [failedStep] encountered [error].
     *
     * @param goal            Original task objective.
     * @param constraints     Constraint strings (may be updated for retry).
     * @param failedStep      The step that failed.
     * @param error           Description of the failure.
     * @param screenshotBase64 Optional Base64-encoded JPEG of the current screen.
     * @return [PlanResult] with revised steps, or with a non-null [PlanResult.error] on failure.
     */
    fun replan(
        goal: String,
        constraints: List<String>,
        failedStep: PlanStep,
        error: String,
        screenshotBase64: String? = null
    ): PlanResult
}

/**
 * Safe default planner that returns a structured error without performing any inference.
 * Use this when the MobileVLM model is not yet loaded or the runtime is unavailable.
 * Provides all interface lifecycle hooks with no-op implementations.
 */
class NoOpPlannerService : LocalPlannerService {

    override fun loadModel(): Boolean = false

    override fun unloadModel() {}

    override fun isModelLoaded(): Boolean = false

    override fun plan(
        goal: String,
        constraints: List<String>,
        screenshotBase64: String?
    ): LocalPlannerService.PlanResult = LocalPlannerService.PlanResult(
        steps = emptyList(),
        error = "MobileVLM planner not available: model not loaded"
    )

    override fun replan(
        goal: String,
        constraints: List<String>,
        failedStep: LocalPlannerService.PlanStep,
        error: String,
        screenshotBase64: String?
    ): LocalPlannerService.PlanResult = LocalPlannerService.PlanResult(
        steps = emptyList(),
        error = "MobileVLM planner not available: model not loaded"
    )
}
