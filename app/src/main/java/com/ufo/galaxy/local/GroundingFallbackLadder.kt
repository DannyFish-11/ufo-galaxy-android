package com.ufo.galaxy.local

import com.ufo.galaxy.agent.ImageScaler
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.observability.GalaxyLogger

/**
 * 意图 → 屏幕坐标的多级定位链。按顺序尝试，先给出可信坐标的那一级胜出：
 *
 * 1. **主视觉定位** —— 截图缩到 [primaryMaxEdge] 送 [LocalGroundingService]，
 *    结果与结构化树快照一起交给 [com.ufo.galaxy.perception.GroundingArbiter] 裁决。
 * 2. **缩小重试** —— 再缩一半边长重来一次，降低压缩噪声的影响。同样经过裁决。
 * 3. **树救场** —— 两级视觉都没给出可信坐标时，若树里存在足够可信的匹配元素
 *    （[com.ufo.galaxy.perception.GroundingArbiter.RESCUE_MATCH_THRESHOLD]），
 *    采用该元素的中心点：**来自无障碍节点的精确 bounds，不是猜的**。
 * 4. **结构化失败** —— 以上都不成立，返回 [FailureCode.GROUND_ALL_STAGES_EXHAUSTED]。
 *
 * ## 为什么第 4 级是"失败"，而不是再兜一层
 *
 * 这里曾经还有三级兜底，名字分别叫 `ocr_text_match` / `accessibility_node` /
 * `heuristic_region`。三者的实现是同一件事：**返回屏幕正中心**，置信度 0.1。
 * （它们的文档注释里写着"a real implementation would…" —— 从来没有被实现过。）
 *
 * 后果不是"定位差一点"，而是**这条梯子永远不会失败**：
 * `tryAccessibilityNodeHeuristic` 只要屏幕尺寸已知就无条件返回中心点，于是它后面
 * 两级是死代码，而每一次真正的定位失败都变成"点一下屏幕中间"。再往上一层，
 * [com.ufo.galaxy.loop.ExecutorBridge] 只看 `dispatchGesture` 是否派发成功，
 * 于是这一步被记成 **SUCCESS**，整个任务最后被报成"完成"。
 *
 * 真机上的表现就是：智能体一本正经地点了几下屏幕中间，然后告诉你办好了。
 *
 * 盲点屏幕中心也不是"无害的猜测"：中心位置上可能正好是一条列表项、一个"删除"、
 * 一次付款确认。一个不知道自己该点哪的智能体，正确动作是**承认不知道**，
 * 让上层去重规划（[com.ufo.galaxy.loop.LoopController] 本来就有这条重规划路径），
 * 而不是随便点一下再报成功。
 *
 * 唯一真正不需要精确坐标的动作是滚动 —— 从屏幕中心起手滑本来就是合理默认。
 * 那属于**动作语义**，由 [com.ufo.galaxy.loop.ExecutorBridge] 显式决定并如实记录，
 * 不该伪装成一次"定位成功"藏在这条梯子里。
 *
 * ## 低于门限 = 视觉没给出结果
 * 置信度低于 [MIN_PRIMARY_CONFIDENCE] 的坐标不是"一个弱坐标"，而是噪声。把它当作
 * 视觉失败交给裁决器，裁决器才会走 tree_rescue；否则会拿噪声坐标去和树比对，
 * 有概率落进某个不相干的元素里被判成 agreement。
 *
 * @param groundingService 主视觉定位引擎（统一 VLM）。
 * @param imageScaler      第 1/2 级用的缩放器。
 * @param primaryMaxEdge   主级送模型时的最长边（px）。
 * @param resizedMaxEdge   缩小重试级的最长边（px）。
 */
class GroundingFallbackLadder(
    private val groundingService: LocalGroundingService,
    private val imageScaler: ImageScaler = NoOpImageScaler(),
    val primaryMaxEdge: Int = DEFAULT_PRIMARY_MAX_EDGE,
    val resizedMaxEdge: Int = DEFAULT_RESIZED_MAX_EDGE
) {

    companion object {
        internal const val STAGE_TAG = "GALAXY:LOOP:GROUNDING:LADDER"

        // 历史值曾是 "primary_seeclick" —— SeeClick 栈早已退役,定位由统一 VLM 承担。
        const val STAGE_PRIMARY = "primary_vlm"
        const val STAGE_RESIZED = "resized_retry"

        /** 两级视觉都没给出可信坐标,但树里有足够可信的匹配元素 —— 用它的精确中心点。 */
        const val STAGE_TREE_RESCUE = "tree_rescue"

        const val STAGE_NO_MATCH = "no_match"

        /**
         * 坐标来源标签:模型选了一个元素编号,坐标取自该无障碍节点的精确 bounds。
         * 与裁决器的那几个 `SOURCE_*` 并列出现在 `stageUsed` 里,真机日志回流时
         * 能直接看出这一步的坐标是谁给的。
         */
        const val SOURCE_ELEMENT_INDEX = "element_index"

        const val DEFAULT_PRIMARY_MAX_EDGE = 720
        const val DEFAULT_RESIZED_MAX_EDGE = 360

        /** Minimum confidence required to accept a primary grounding result. */
        const val MIN_PRIMARY_CONFIDENCE = 0.2f
    }

    /**
     * Result from a grounding ladder attempt.
     *
     * @param x           Horizontal coordinate in full-resolution pixel space.
     * @param y           Vertical coordinate in full-resolution pixel space.
     * @param confidence  Grounding confidence; low values indicate heuristic fallbacks.
     * @param stageUsed   Name of the stage that produced this result.
     * @param error       Non-null only when all stages are exhausted.
     * @param failureCode Structured failure code; set only when all stages exhausted.
     */
    data class GroundingResult(
        val x: Int,
        val y: Int,
        val confidence: Float,
        val stageUsed: String,
        val error: String? = null,
        val failureCode: FailureCode? = null
    ) {
        val succeeded: Boolean get() = error == null
    }

    /**
     * Grounds [intent] against [jpegBytes] by descending the fallback ladder.
     *
     * @param sessionId    Session identifier for logging.
     * @param stepId       Step identifier for logging.
     * @param intent       Natural-language action intent.
     * @param jpegBytes    Full-resolution JPEG bytes of the current screen.
     * @param screenWidth  Full-resolution screen width in pixels.
     * @param screenHeight Full-resolution screen height in pixels.
     * @return [GroundingResult] with either valid coordinates or an exhausted error.
     */
    fun ground(
        sessionId: String,
        stepId: String,
        intent: String,
        jpegBytes: ByteArray,
        screenWidth: Int,
        screenHeight: Int,
        // 双通道:无障碍树结构化快照,与截图同帧注入主/缩放两级视觉定位的 prompt
        // (null = 无结构化通道,行为与旧版一致)。
        // 真 bug 修复(坐标空间混用):此前入参是调用方预渲染的全分辨率元素清单文本,
        // 而本梯子内部把截图缩到 primary/resized 两种不同边长 —— prompt 里的元素坐标
        // 与截图像素空间不一致且每级不同。改为传入快照本体,由各级按自己的缩放尺寸
        // 换算后再渲染注入。
        uiSnapshot: com.ufo.galaxy.perception.UiStructuredSnapshot? = null
    ): GroundingResult {

        // Stage 1: Primary VLM grounding.
        if (groundingService.isModelLoaded()) {
            val result = tryPrimaryGrounding(sessionId, stepId, intent, jpegBytes, screenWidth, screenHeight, uiSnapshot)
            if (result != null) return result
        }

        // Stage 2: Resized screenshot retry (smaller edge).
        if (groundingService.isModelLoaded() && resizedMaxEdge < primaryMaxEdge) {
            val result = tryResizedGrounding(sessionId, stepId, intent, jpegBytes, screenWidth, screenHeight, uiSnapshot)
            if (result != null) return result
        }

        // Stage 3: 树救场 —— 两级视觉都没给出可信坐标,但树里有足够可信的匹配元素。
        // 这是最后一级,也是唯一不依赖模型的一级:没有 VLM 权重的设备上,整条定位
        // 链能不能工作全靠它。
        val rescued = tryTreeRescue(sessionId, stepId, intent, uiSnapshot)
        if (rescued != null) return rescued

        // 到这里就是真的不知道该点哪：两级视觉没结果，树里也没有足够可信的候选。
        // 不再往下兜 —— 见类文档「为什么第 4 级是失败」。
        GalaxyLogger.log(STAGE_TAG, mapOf(
            "event" to "ladder_stage",
            "session_id" to sessionId,
            "step_id" to stepId,
            "stage" to STAGE_NO_MATCH,
            "outcome" to "exhausted"
        ))
        return GroundingResult(
            x = 0, y = 0, confidence = 0f,
            stageUsed = STAGE_NO_MATCH,
            error = "All grounding stages exhausted for intent: ${intent.take(60)}",
            failureCode = FailureCode.GROUND_ALL_STAGES_EXHAUSTED
        )
    }

    // ── Stage implementations ─────────────────────────────────────────────────

    private fun tryPrimaryGrounding(
        sessionId: String,
        stepId: String,
        intent: String,
        jpegBytes: ByteArray,
        screenWidth: Int,
        screenHeight: Int,
        uiSnapshot: com.ufo.galaxy.perception.UiStructuredSnapshot? = null
    ): GroundingResult? = tryVisionStage(
        sessionId, stepId, intent, jpegBytes, screenWidth, screenHeight, uiSnapshot,
        stage = STAGE_PRIMARY, maxEdge = primaryMaxEdge
    )

    private fun tryResizedGrounding(
        sessionId: String,
        stepId: String,
        intent: String,
        jpegBytes: ByteArray,
        screenWidth: Int,
        screenHeight: Int,
        uiSnapshot: com.ufo.galaxy.perception.UiStructuredSnapshot? = null
    ): GroundingResult? = tryVisionStage(
        sessionId, stepId, intent, jpegBytes, screenWidth, screenHeight, uiSnapshot,
        stage = STAGE_RESIZED, maxEdge = resizedMaxEdge
    )

    /**
     * 一级视觉定位:缩放 → 送模型 → 换算回全分辨率 → **与树快照综合裁决**。
     *
     * 两级视觉(主级 / 缩小重试)只有 [stage] 标签与 [maxEdge] 不同,其余完全一致,
     * 故收敛到这里一处 —— 此前是两份几乎逐行相同的复制,任何一处改动都要记得改两遍
     * (树注入的坐标空间换算就曾经只改对了一处)。
     *
     * ## 裁决在换算之后
     * [com.ufo.galaxy.perception.GroundingArbiter] 要求视觉坐标与树 bounds 在**同一坐标
     * 空间**。树快照是全分辨率的,而模型看到的是缩放图,所以必须先 remap 回全分辨率再裁决。
     * (注入 prompt 的元素清单则相反 —— 那是给模型看的,要换算到缩放图空间。)
     *
     * ## 低于门限 = 视觉没给出结果
     * 置信度低于 [MIN_PRIMARY_CONFIDENCE] 的坐标不是"一个弱坐标",而是噪声。把它当作
     * 视觉失败交给裁决器,裁决器才会走 tree_rescue;否则会拿噪声坐标去和树比对,
     * 有概率落进某个不相干的元素里被判成 agreement。
     */
    private fun tryVisionStage(
        sessionId: String,
        stepId: String,
        intent: String,
        jpegBytes: ByteArray,
        screenWidth: Int,
        screenHeight: Int,
        uiSnapshot: com.ufo.galaxy.perception.UiStructuredSnapshot?,
        stage: String,
        maxEdge: Int
    ): GroundingResult? {
        return try {
            val scaled = imageScaler.scaleToMaxEdge(
                jpegBytes = jpegBytes,
                fullWidth = screenWidth,
                fullHeight = screenHeight,
                maxEdge = maxEdge
            )
            val raw = groundingService.ground(
                intent = intent,
                screenshotBase64 = scaled.scaledJpegBase64,
                width = scaled.scaledWidth,
                height = scaled.scaledHeight,
                // 坐标空间对齐:元素清单换算到本级缩放图空间后再注入 prompt。
                structuredContext = uiSnapshot
                    ?.scaledTo(scaled.scaledWidth, scaled.scaledHeight)
                    ?.toPromptBlock()
            )

            // 编号命中优先:模型从我们给的封闭清单里挑了一个元素,坐标直接取那个
            // 无障碍节点的**精确 bounds 中心**(全分辨率空间)。
            //
            // 这条路刻意绕开了缩放换算与坐标裁决:
            //  · 没有 remap —— 坐标从来没进过缩放图空间,也就没有换算误差;
            //  · 不用裁决 —— 裁决器的活儿是"视觉坐标和树对不对得上",而这里视觉
            //    给的本来就是树里的一个元素,没有两方可对。
            resolveByIndex(sessionId, stepId, stage, raw, uiSnapshot)?.let { return it }

            // 模型给了编号但解析不出来(编号越界 / 这一帧没有快照)。此时它带回来的
            // x/y 是引擎填的 0 —— 一个**不是坐标的坐标**。若照常往下走,(0,0) 会带着
            // 模型给编号时的那个高置信度进裁决器,把屏幕左上角当成一次可信定位。
            // 所以这一级按"视觉没给出结果"处理,让裁决走树救场。
            val indexUnresolvable = raw.error == null && raw.elementIndex != null

            val visionAtFullRes = if (
                raw.error != null || indexUnresolvable || raw.confidence < MIN_PRIMARY_CONFIDENCE
            ) {
                LocalGroundingService.GroundingResult(
                    x = 0, y = 0, confidence = 0f, element_description = "",
                    error = raw.error
                        ?: if (indexUnresolvable) "element_index_unresolvable:${raw.elementIndex}"
                        else "confidence_below_gate:${raw.confidence}"
                )
            } else {
                raw.copy(
                    x = remapCoord(raw.x, scaled.scaledWidth, screenWidth),
                    y = remapCoord(raw.y, scaled.scaledHeight, screenHeight)
                )
            }

            val fused = com.ufo.galaxy.perception.GroundingArbiter.fuse(
                intent = intent,
                vlm = visionAtFullRes,
                snapshot = uiSnapshot
            )
            if (fused.result.error != null) {
                logStage(sessionId, stepId, stage, "skip", fused.result.error)
                return null
            }
            // 树救场**不在这一级收下**:那样会让下一级(缩小重试)永远跑不到 —— 用一个刚过
            // 救场门限(0.55)的树候选,顶掉一次本可能给出高置信度视觉命中的重试。救场属于
            // 「两级视觉都用尽之后」,由 [tryTreeRescue] 统一做。
            // agreement / tree_override / vlm_only 三种仍然在本级收下:它们都需要**本级的
            // 视觉坐标**才成立,推迟没有意义。
            if (fused.source == com.ufo.galaxy.perception.GroundingArbiter.SOURCE_TREE_RESCUE) {
                logStage(sessionId, stepId, stage, "skip", "vision_failed_defer_rescue")
                return null
            }
            // 裁决来源进 stageUsed,真机日志回流时能直接看出这一步的坐标是谁给的。
            logStage(sessionId, stepId, stage, "ok", "fusion=${fused.source}")
            GroundingResult(
                x = fused.result.x,
                y = fused.result.y,
                confidence = fused.result.confidence,
                stageUsed = "$stage+${fused.source}"
            )
        } catch (e: Exception) {
            logStage(sessionId, stepId, stage, "exception", e.message)
            null
        }
    }

    /**
     * 把模型给的元素编号解析成坐标。
     *
     * 只在编号**确实落在当帧快照里**时才成立 —— 编号越界说明模型在编,那和一个
     * 编出来的像素坐标一样不可信,如实返回 null 让它继续走后面的路。这正是编号这条
     * 路比像素强的地方:答案能被校验,而像素坐标永远"看起来合法"。
     *
     * @return 命中时的结果;模型没给编号、没有快照、或编号越界时返回 null。
     */
    private fun resolveByIndex(
        sessionId: String,
        stepId: String,
        stage: String,
        raw: LocalGroundingService.GroundingResult,
        uiSnapshot: com.ufo.galaxy.perception.UiStructuredSnapshot?
    ): GroundingResult? {
        val index = raw.elementIndex ?: return null
        if (raw.error != null) return null
        val element = uiSnapshot?.elements?.firstOrNull { it.index == index }
        if (element == null) {
            // 越界/无快照:记一笔再放行。真机日志里这一条能直接说明"模型在编编号"。
            logStage(sessionId, stepId, stage, "skip", "element_index_out_of_range:$index")
            return null
        }
        logStage(sessionId, stepId, stage, "ok", "element_index=$index")
        return GroundingResult(
            x = element.centerX,
            y = element.centerY,
            confidence = raw.confidence,
            stageUsed = "$stage+$SOURCE_ELEMENT_INDEX"
        )
    }

    /**
     * 两级视觉都没给出可信坐标后的树救场。
     *
     * 交给同一个裁决器处理(视觉侧传一个显式失败结果),只接受它判定为
     * [com.ufo.galaxy.perception.GroundingArbiter.SOURCE_TREE_RESCUE] 的结果 ——
     * 救场门限 [com.ufo.galaxy.perception.GroundingArbiter.RESCUE_MATCH_THRESHOLD]
     * 低于推翻门限,因为此时视觉已经失败,树只需要"可信"而不需要"强到能推翻证据"。
     *
     * 无快照 / 无足够可信候选时返回 null —— 梯子到此为止,如实返回定位失败。
     *
     * ## 这一级是树救场的**唯一**入口
     * 两级视觉里的裁决只收下 agreement / tree_override / vlm_only —— 那三种都需要本级的
     * 视觉坐标才成立。判成 tree_rescue 的(即本级视觉没给出可信结果)一律推迟到这里,
     * 因为在主级就收下树,会让缩小重试那一级永远跑不到:用一个刚过救场门限的树候选,
     * 顶掉一次本可能给出高置信度视觉命中的重试。
     *
     * 所以本级覆盖的是全部"视觉用尽"的形态:
     *  1. 两级视觉都判 tree_rescue(模型在跑,但这一帧两个尺寸都读不出来);
     *  2. [groundingService] 的 `isModelLoaded()` 为 false —— 两级视觉被整个跳过,
     *     裁决器根本没被调用过(权重没下完、warmup 没过、服务没起,都是这一种);
     *  3. 两级视觉都抛异常 —— catch 分支直接返回 null,同样没走到裁决。
     *
     * 没有这一级,上面三种情形会带着满手的精确 bounds 直接判定位失败 —— 尤其是
     * 第 2 种(设备上根本没有 VLM 权重),那时树是**唯一**的定位证据来源。
     */
    private fun tryTreeRescue(
        sessionId: String,
        stepId: String,
        intent: String,
        uiSnapshot: com.ufo.galaxy.perception.UiStructuredSnapshot?
    ): GroundingResult? {
        if (uiSnapshot == null || uiSnapshot.elements.isEmpty()) return null
        val fused = com.ufo.galaxy.perception.GroundingArbiter.fuse(
            intent = intent,
            vlm = LocalGroundingService.GroundingResult(
                x = 0, y = 0, confidence = 0f, element_description = "",
                error = "all_vision_stages_failed"
            ),
            snapshot = uiSnapshot
        )
        if (fused.source != com.ufo.galaxy.perception.GroundingArbiter.SOURCE_TREE_RESCUE) {
            logStage(sessionId, stepId, STAGE_TREE_RESCUE, "skip", "no_credible_candidate")
            return null
        }
        logStage(sessionId, stepId, STAGE_TREE_RESCUE, "ok", fused.result.element_description.take(60))
        return GroundingResult(
            x = fused.result.x,
            y = fused.result.y,
            confidence = fused.result.confidence,
            stageUsed = STAGE_TREE_RESCUE
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun logStage(
        sessionId: String,
        stepId: String,
        stage: String,
        outcome: String,
        detail: String? = null
    ) {
        GalaxyLogger.log(STAGE_TAG, buildMap {
            put("event", "ladder_stage")
            put("session_id", sessionId)
            put("step_id", stepId)
            put("stage", stage)
            put("outcome", outcome)
            if (detail != null) put("detail", detail.take(120))
        })
    }

    private fun remapCoord(coordInScaled: Int, scaledDim: Int, fullDim: Int): Int {
        if (scaledDim <= 0 || fullDim <= 0) return coordInScaled
        return Math.round(coordInScaled.toFloat() * fullDim.toFloat() / scaledDim.toFloat())
    }
}
