package com.ufo.galaxy.inference

/**
 * Local grounding service interface for unified-VLM on-device GUI grounding.
 *
 * Pluggable runtime: NCNN (ARM NEON/Vulkan) or MNN backend.
 * Model: MAI-UI-2B(与规划器共用同一模型/服务)。
 *
 * Maps a natural-language intent and a live screenshot to physical screen coordinates.
 * Coordinates are produced exclusively on-device; the gateway never supplies x/y values.
 *
 * [NoOpGroundingService] is the safe default; it returns a structured error
 * without performing any inference.
 *
 * When the runtime is absent or unhealthy, prefer [DegradedGroundingService] over
 * [NoOpGroundingService]. [DegradedGroundingService] carries the precise
 * [com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState] and prefixes
 * all error messages with `DEGRADED:` so consumers can distinguish a degraded-runtime
 * result from a transient inference failure.
 */
interface LocalGroundingService {

    // ── Structured warmup ────────────────────────────────────────────────────

    /**
     * Pre-warms the grounding runtime and returns a [WarmupResult] with stage-level detail.
     *
     * Implementations should validate:
     * 1. Health endpoint reachability ([WarmupResult.WarmupStage.HEALTH_CHECK]).
     * 2. Dry-run grounding success ([WarmupResult.WarmupStage.DRY_RUN_INFERENCE]).
     * 3. Valid response shape ([WarmupResult.WarmupStage.RESPONSE_VALIDATION]).
     *
     * The default implementation delegates to [prewarm] and wraps the boolean result.
     * Override this method to provide richer failure detail.
     */
    fun warmupWithResult(): WarmupResult =
        if (prewarm()) WarmupResult.success()
        else WarmupResult.failure(WarmupResult.WarmupStage.HEALTH_CHECK, "Grounding warmup failed")

    /**
     * Resolved screen location for an action intent.
     *
     * @param x                   Horizontal pixel coordinate. 0 when [error] is non-null.
     * @param y                   Vertical pixel coordinate. 0 when [error] is non-null.
     * @param confidence          Model confidence score in [0.0, 1.0].
     * @param element_description Human-readable label of the matched UI element.
     * @param error               Non-null error description when grounding fails.
     */
    data class GroundingResult(
        val x: Int,
        val y: Int,
        val confidence: Float,
        val element_description: String,
        val error: String? = null,
        /**
         * 模型选中的**元素编号**（对应注入 prompt 的那份无障碍树元素清单的
         * `[i]`）；模型只给了像素坐标时为 null。
         *
         * ## 为什么要有这一路
         * 让模型直接吐 (x, y)，是把"认出这是哪个控件"和"这个控件在屏幕上的第几个
         * 像素"两件事压在一次生成里。第二件事模型做不好：图被缩放过、坐标要换算
         * 回全分辨率、小屏上差十几像素就点到隔壁。成熟的手机 GUI agent 框架
         * （AppAgent、android_world 的 T3A 这一类）走的都是另一条路 —— 给模型一份
         * 带编号的可交互元素清单，让它**选一个编号**，坐标由无障碍节点自己的
         * bounds 给出。
         *
         * 编号这条路的坐标是**精确的**（来自节点 bounds），而且答案可验证：
         * 编号要么在清单里，要么不在。像素坐标则永远"看起来是个合法答案"。
         *
         * 由 [com.ufo.galaxy.local.GroundingFallbackLadder] 拿着当帧快照把编号解析成
         * 坐标 —— 它持有快照，而本接口的实现只拿到渲染后的文本。
         */
        val elementIndex: Int? = null
    )

    /**
     * Pre-warms the inference server by performing a health ping and optionally
     * sending a minimal dry-run request to bring the model into active memory.
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
     * Grounds [intent] against [screenshotBase64] and returns physical coordinates.
     *
     * @param intent          Natural-language action intent from the planner.
     * @param screenshotBase64 Base64-encoded JPEG of the current device screen.
     * @param width           Screen width in pixels (0 if unknown).
     * @param height          Screen height in pixels (0 if unknown).
     * @return [GroundingResult] with coordinates, or with a non-null [GroundingResult.error].
     */
    fun ground(
        intent: String,
        screenshotBase64: String,
        width: Int = 0,
        height: Int = 0
    ): GroundingResult

    /**
     * 双通道版本:除截图外同时携带结构化屏幕上下文([structuredContext],由
     * [com.ufo.galaxy.perception.UiStructuredSnapshot.toPromptBlock] 生成的元素清单
     * 文本;null/空 = 无结构化通道)。
     *
     * 默认实现忽略 structuredContext 并委托给四参 [ground] —— 既有实现与全部测试
     * fake 无需感知本方法;支持双通道的实现(VlmGroundingEngine)覆写之,把元素
     * 清单注入 prompt 与截图一同送模型(同时在场、综合判断,而非兜底)。
     */
    fun ground(
        intent: String,
        screenshotBase64: String,
        width: Int,
        height: Int,
        structuredContext: String?
    ): GroundingResult = ground(intent, screenshotBase64, width, height)
}

/**
 * Minimal no-inference grounding service that returns a structured error without performing
 * any inference. Retained for backwards compatibility and unit test stubs.
 *
 * **Prefer [DegradedGroundingService] in production paths** where a human-readable
 * degraded reason and runtime-state context are needed. [DegradedGroundingService] replaces
 * [NoOpGroundingService] as the canonical fallback for runtime-absent scenarios.
 */
class NoOpGroundingService : LocalGroundingService {

    override fun loadModel(): Boolean = false

    override fun unloadModel() {}

    override fun isModelLoaded(): Boolean = false

    override fun ground(
        intent: String,
        screenshotBase64: String,
        width: Int,
        height: Int
    ): LocalGroundingService.GroundingResult = LocalGroundingService.GroundingResult(
        x = 0,
        y = 0,
        confidence = 0f,
        element_description = "",
        error = "VLM grounding not available: model not loaded"
    )
}
