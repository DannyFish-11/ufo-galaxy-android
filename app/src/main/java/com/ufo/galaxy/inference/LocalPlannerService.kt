package com.ufo.galaxy.inference

/**
 * Local planner service interface for unified-VLM on-device task planning.
 *
 * Pluggable runtime: llama.cpp (GGUF INT4/INT8) or MLC-LLM backend.
 * Model: MAI-UI-2B(mradermacher/MAI-UI-2B-GGUF,Qwen3-VL-2B 底座)。
 *
 * Implementations must provide load/unload lifecycle hooks to manage on-device model
 * memory.
 *
 * When the runtime is absent or unhealthy, use [DegradedPlannerService] (not
 * [NoOpPlannerService]) as the canonical fallback. [DegradedPlannerService] carries
 * the precise [com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState] that
 * caused the unavailability and prefixes all error messages with `DEGRADED:` so
 * consumers can distinguish a degraded-runtime result from a transient inference failure.
 *
 * Boundary note: [LocalPlannerService] is limited to local task decomposition/planning
 * for Android execution. It is not the system-level semantic authority; centralized
 * semantic authority remains in V2 for cross-device semantic decisions.
 */
interface LocalPlannerService {

    /**
     * Runtime authority boundary for local planning.
     *
     * Android local planning may only act as an execution sub-decision for work that is already
     * committed to Android execution.  It must not silently override center/V2 orchestration
     * when the inbound constraints explicitly reserve planning authority to the center.
     */
    enum class AuthorityBoundary(val wireValue: String, val allowsAutonomousExecution: Boolean) {
        /** Android may plan and execute locally as a bounded sub-decision. */
        LOCAL_EXECUTION_SUBDECISION("local_execution_subdecision", true),

        /** Local planning may only produce a suggestion; execution must wait for center review. */
        LOCAL_SUGGESTION_ONLY("local_suggestion_only", false),

        /** Center/V2 retained planning authority; Android must not plan autonomously. */
        CENTER_AUTHORITY_REQUIRED("center_authority_required", false);

        companion object {
            fun fromWireValue(value: String?): AuthorityBoundary? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    /**
     * Resolved authority decision for a local planning attempt.
     *
     * @property requiresCenterRevalidation True when Android must not continue planning/execution
     * without an explicit V2 follow-up decision.
     */
    data class PlannerAuthorityDecision(
        val boundary: AuthorityBoundary,
        val reason: String,
        val requiresCenterRevalidation: Boolean
    )

    companion object {
        const val CONSTRAINT_V2_AUTHORITY_ONLY = "v2_authority_only"
        const val CONSTRAINT_CENTER_AUTHORITY_LOCKED = "center_authority_locked"
        const val CONSTRAINT_LOCAL_PLANNER_SUGGESTION_ONLY = "local_planner_suggestion_only"

        /**
         * Resolves the local-planning authority boundary from inbound constraints.
         *
         * This is the Android-side enforcement seam for the V2 authority boundary: when V2 marks
         * a task as center-authoritative or suggestion-only, Android must not silently continue
         * with autonomous local planning.
         */
        fun classifyAuthority(constraints: List<String>): PlannerAuthorityDecision {
            val normalized = constraints.map { it.trim().lowercase() }.toSet()
            return when {
                CONSTRAINT_V2_AUTHORITY_ONLY in normalized ||
                    CONSTRAINT_CENTER_AUTHORITY_LOCKED in normalized ->
                    PlannerAuthorityDecision(
                        boundary = AuthorityBoundary.CENTER_AUTHORITY_REQUIRED,
                        reason = "center_authority_locked",
                        requiresCenterRevalidation = true
                    )
                CONSTRAINT_LOCAL_PLANNER_SUGGESTION_ONLY in normalized ->
                    PlannerAuthorityDecision(
                        boundary = AuthorityBoundary.LOCAL_SUGGESTION_ONLY,
                        reason = "local_planner_suggestion_only",
                        requiresCenterRevalidation = true
                    )
                else ->
                    PlannerAuthorityDecision(
                        boundary = AuthorityBoundary.LOCAL_EXECUTION_SUBDECISION,
                        reason = "android_local_execution_subdecision",
                        requiresCenterRevalidation = false
                    )
            }
        }
    }

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

    /** Loads the VLM model weights into device memory. Returns true on success. */
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
 * Minimal no-inference planner that returns a structured error without performing any
 * inference. Retained for backwards compatibility and unit test stubs.
 *
 * **Prefer [DegradedPlannerService] in production paths** where a human-readable
 * degraded reason and runtime-state context are needed. [DegradedPlannerService] replaces
 * [NoOpPlannerService] as the canonical fallback for runtime-absent scenarios.
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
        error = "VLM planner not available: model not loaded"
    )

    override fun replan(
        goal: String,
        constraints: List<String>,
        failedStep: LocalPlannerService.PlanStep,
        error: String,
        screenshotBase64: String?
    ): LocalPlannerService.PlanResult = LocalPlannerService.PlanResult(
        steps = emptyList(),
        error = "VLM planner not available: model not loaded"
    )
}
