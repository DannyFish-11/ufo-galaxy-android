package com.ufo.galaxy.local

import com.ufo.galaxy.agent.ImageScaler
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.observability.GalaxyLogger

/**
 * Implements a multi-stage grounding fallback chain so that if primary VLM grounding
 * fails, the system attempts lower-cost alternatives before returning a structured failure.
 *
 * The ladder is attempted in order until valid coordinates are produced:
 *
 * 1. **Primary VLM grounding** — screenshot downscaled to [primaryMaxEdge], passed to the
 *    loaded [LocalGroundingService], **然后与结构化树快照一起交给
 *    [com.ufo.galaxy.perception.GroundingArbiter] 综合裁决**。
 * 2. **Resized retry** — screenshot re-scaled to a smaller edge (50 % of the primary
 *    edge) before grounding; reduces encoding noise from compression artefacts. 同样经过裁决。
 * 3. **Tree rescue** — 两级视觉都没给出可信坐标时,若树里存在足够可信的匹配元素
 *    ([com.ufo.galaxy.perception.GroundingArbiter.RESCUE_MATCH_THRESHOLD]),
 *    直接采用该元素的中心点(**精确 bounds,不是猜的**)。
 * 4. **OCR text matching** — 意图看起来像一个短 UI 标签时的粗略近似:屏幕中心 + 极低置信度。
 * 5. **Accessibility node heuristic** — 屏幕中心兜底,低置信度标签,交调用方决定要不要用。
 * 6. **Heuristic screen-region fallback** — maps intent keywords to coarse screen
 *    regions (top/bottom/centre) and returns region-centre coordinates.
 * 7. **Structured no-match failure** — all stages exhausted; returns an error result
 *    with [FailureCode.GROUND_ALL_STAGES_EXHAUSTED].
 *
 * ## 为什么加第 1/2 级的裁决与第 3 级(读码实证的真缺陷)
 * 结构化树快照此前**只被注入两级视觉的 prompt,然后就被丢掉了** —— 梯子里一次都没有调用
 * [com.ufo.galaxy.perception.GroundingArbiter]。于是视觉失手时,梯子明明手里攥着带精确
 * bounds 的树候选,却直接跌到第 4/5 级去**点屏幕中心**(名字叫 accessibility_node,实现里
 * 根本不碰快照)。同一份快照在 [com.ufo.galaxy.agent.EdgeExecutor] 那条路径上是全程参与
 * 裁决的 —— 两条执行路径对同一份证据的处置不一致,而这条正是跨设备关闭时的本地主路。
 *
 * 另外,低于 [MIN_PRIMARY_CONFIDENCE] 的视觉坐标在这里被当作「视觉没有给出可信结果」
 * (而不是一个弱坐标)交给裁决器 —— 这样裁决器走的是 tree_rescue,而不是拿一个噪声坐标
 * 去和树比对。
 *
 * @param groundingService The primary [LocalGroundingService] (unified VLM).
 * @param imageScaler      Scaler used for stages 1 and 2.
 * @param primaryMaxEdge   Max longest edge (px) for the primary grounding call.
 * @param resizedMaxEdge   Max longest edge (px) for the resized-retry stage.
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

        const val STAGE_OCR_TEXT = "ocr_text_match"
        const val STAGE_ACCESSIBILITY_NODE = "accessibility_node"
        const val STAGE_HEURISTIC_REGION = "heuristic_region"
        const val STAGE_NO_MATCH = "no_match"

        const val DEFAULT_PRIMARY_MAX_EDGE = 720
        const val DEFAULT_RESIZED_MAX_EDGE = 360

        /** Confidence assigned to accessibility-node fallback coordinates. */
        const val FALLBACK_CONFIDENCE = 0.1f

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
        // 这一级必须排在下面三个启发式之前:那三个返回的是**猜的**屏幕中心,
        // 而这一级返回的是无障碍树里那个元素的**精确 bounds 中心**。
        val rescued = tryTreeRescue(sessionId, stepId, intent, uiSnapshot)
        if (rescued != null) return rescued

        // Stage 4: OCR text matching — heuristic centre if intent is a short UI label.
        val ocrResult = tryOcrHeuristic(sessionId, stepId, intent, screenWidth, screenHeight)
        if (ocrResult != null) return ocrResult

        // Stage 5: Accessibility node text/description heuristic — screen centre.
        val nodeResult = tryAccessibilityNodeHeuristic(sessionId, stepId, screenWidth, screenHeight)
        if (nodeResult != null) return nodeResult

        // Stage 6: Heuristic screen-region fallback — intent keyword → region.
        val regionResult = tryHeuristicRegion(sessionId, stepId, intent, screenWidth, screenHeight)
        if (regionResult != null) return regionResult

        // Stage 7: Structured no-match failure.
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

            val visionAtFullRes = if (raw.error != null || raw.confidence < MIN_PRIMARY_CONFIDENCE) {
                LocalGroundingService.GroundingResult(
                    x = 0, y = 0, confidence = 0f, element_description = "",
                    error = raw.error ?: "confidence_below_gate:${raw.confidence}"
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
     * 两级视觉都没给出可信坐标后的树救场。
     *
     * 交给同一个裁决器处理(视觉侧传一个显式失败结果),只接受它判定为
     * [com.ufo.galaxy.perception.GroundingArbiter.SOURCE_TREE_RESCUE] 的结果 ——
     * 救场门限 [com.ufo.galaxy.perception.GroundingArbiter.RESCUE_MATCH_THRESHOLD]
     * 低于推翻门限,因为此时视觉已经失败,树只需要"可信"而不需要"强到能推翻证据"。
     *
     * 无快照 / 无足够可信候选时返回 null,梯子继续跌到下面的启发式级。
     *
     * ## 这一级什么时候真的会跑到(离线实跑确认,别当成冗余删掉)
     * 视觉失败时,救场其实在**第 1 级内部**就发生了 —— 裁决器在那里已经拿到了树,
     * 越早救越好,所以正常路径的 stageUsed 是 `primary_vlm+tree_rescue`。
     *
     * 本级是另外两种情形下树的**唯一入口**:
     *  1. [groundingService] 的 `isModelLoaded()` 为 false —— 两级视觉被整个跳过,
     *     裁决器根本没被调用过(权重没下完、warmup 没过、服务没起,都是这一种);
     *  2. 两级视觉都抛异常 —— catch 分支直接返回 null,同样没走到裁决。
     *
     * 没有这一级,上面两种情形会带着满手的精确 bounds 直接跌到"点屏幕中心"。
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

    /**
     * OCR text match heuristic: if the intent is short and looks like a UI label,
     * return the screen centre with low confidence as a coarse approximation.
     * A real implementation would query the accessibility tree for matching text nodes.
     */
    private fun tryOcrHeuristic(
        sessionId: String,
        stepId: String,
        intent: String,
        screenWidth: Int,
        screenHeight: Int
    ): GroundingResult? {
        // Only apply when intent looks like a short UI label (no spaces or few words).
        val trimmed = intent.trim()
        val wordCount = trimmed.split("\\s+".toRegex()).size
        if (trimmed.length > 30 || wordCount > 4) return null
        if (screenWidth <= 0 || screenHeight <= 0) return null

        logStage(sessionId, stepId, STAGE_OCR_TEXT, "ok")
        return GroundingResult(
            x = screenWidth / 2,
            y = screenHeight / 2,
            confidence = FALLBACK_CONFIDENCE,
            stageUsed = STAGE_OCR_TEXT
        )
    }

    /**
     * Accessibility node heuristic: returns the screen centre as the best-guess target
     * when no other grounding is available. A real implementation would walk the
     * accessibility node tree searching for nodes matching the intent text.
     */
    private fun tryAccessibilityNodeHeuristic(
        sessionId: String,
        stepId: String,
        screenWidth: Int,
        screenHeight: Int
    ): GroundingResult? {
        if (screenWidth <= 0 || screenHeight <= 0) return null

        logStage(sessionId, stepId, STAGE_ACCESSIBILITY_NODE, "ok")
        return GroundingResult(
            x = screenWidth / 2,
            y = screenHeight / 2,
            confidence = FALLBACK_CONFIDENCE,
            stageUsed = STAGE_ACCESSIBILITY_NODE
        )
    }

    /**
     * Heuristic screen-region fallback: maps intent keywords to coarse screen regions
     * (top third, bottom third, or centre) and returns the region's centre coordinate.
     */
    private fun tryHeuristicRegion(
        sessionId: String,
        stepId: String,
        intent: String,
        screenWidth: Int,
        screenHeight: Int
    ): GroundingResult? {
        if (screenWidth <= 0 || screenHeight <= 0) return null

        val lower = intent.lowercase()
        val centreX = screenWidth / 2
        val y = when {
            lower.contains("top") || lower.contains("status") || lower.contains("notification") ->
                screenHeight / 6
            lower.contains("bottom") || lower.contains("nav") || lower.contains("toolbar") ->
                screenHeight * 5 / 6
            else -> screenHeight / 2
        }

        logStage(sessionId, stepId, STAGE_HEURISTIC_REGION, "ok")
        return GroundingResult(
            x = centreX,
            y = y,
            confidence = FALLBACK_CONFIDENCE,
            stageUsed = STAGE_HEURISTIC_REGION
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
