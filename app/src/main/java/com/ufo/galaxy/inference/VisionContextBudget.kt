package com.ufo.galaxy.inference

import kotlin.math.min
import kotlin.math.sqrt

/**
 * 规划/定位调用的**上下文预算**：截图能开多大，由服务端 `-c` 反推，而不是拍脑袋定。
 *
 * ## 这个类存在的原因（读码实证）
 * [LlamaServerController.DEFAULT_CONTEXT_SIZE] 恒为 4096，而规划步此前把**全分辨率**
 * 截图的 base64 直接交给模型（定位步才走 720 长边缩放）。按下面的分块口径实算
 * （数字由 `VisionContextBudgetTest` 跑出来，不是估的）：
 *
 * | 屏幕 | 原图视觉 token | 视觉预算(4096−512生成−128余量) | 结果 |
 * |---|---|---|---|
 * | 1080×2400 | 3230 | 3456 | 塞得进，但**只剩 226 个 token** 给目标与元素清单 |
 * | 1440×3200 | 5814 | 3456 | **直接装不下** |
 *
 * 也就是说 1080p 不是必然溢出，而是余量小到任何一份真实的元素清单都会把它顶穿；
 * 1440p 则是无条件溢出。
 *
 * 更要命的是**全链路没有任何一处数过、卡过、截过这个预算**：溢出时 llama.cpp 要么报错、
 * 要么静默截断 prompt（截掉的往往正是尾部的指令或图像 token），表现为"模型忽然不听话"
 * 而不是一个明确的失败。
 *
 * 所以这里把预算变成一个**算得出来、并且真的被执行**的数：
 *
 * ```
 * 视觉预算 = 上下文窗口 − 生成预留 − 文本开销 − 安全余量
 * 长边     = 满足该视觉预算的最大长边（保持宽高比）
 * ```
 *
 * ## 分块口径
 * Qwen3-VL 系（MAI-UI-2B 的底座）视觉编码为 14px patch + 2×2 merge，即每
 * [PIXELS_PER_VISION_TOKEN_EDGE]×[PIXELS_PER_VISION_TOKEN_EDGE] 像素块产出 1 个 token，
 * 不足一块的边缘像素不产出 token（与 llama.cpp `mtmd` 的分块口径一致，故取整向下）。
 *
 * ## 诚实边界
 * [textTokensFor] 是**估算**：端侧没有可用的 tokenizer（GGUF 词表在服务进程里，
 * 客户端拿不到），因此按字符数折算。[CHARS_PER_TEXT_TOKEN] 取值刻意偏小，
 * 使估算**高估** token 数 —— 预算判断上宁可把图压小一点，也不要溢出。
 * 视觉 token 那一半不是估算：它由分块几何直接算出。
 */
object VisionContextBudget {

    /**
     * 每个视觉 token 覆盖的像素块边长。
     *
     * Qwen3-VL：patch 14px，相邻 2×2 patch 合并为 1 个视觉 token → 28×28 像素/token。
     */
    const val PIXELS_PER_VISION_TOKEN_EDGE = 28

    /**
     * 文本 token 估算：多少个字符折 1 个 token。
     *
     * 取 3 而不是常见的 4：本仓 prompt 混有中文与 JSON 标点，两者的字符/token 比都低于
     * 英文散文。取小值 → 估出的 token 偏多 → 预算偏保守。方向是刻意选的。
     */
    const val CHARS_PER_TEXT_TOKEN = 3

    /** 安全余量：聊天模板标记、图像占位 token、分词边界抖动等未计入的零头。 */
    const val SAFETY_MARGIN_TOKENS = 128

    /**
     * 长边下限。低于这个尺寸，2B 模型基本读不出手机 UI 上的文字与图标，
     * 压到看不清等于没送图 —— 到这一步应当让调用方看到"预算不够"，而不是送一张糊图。
     */
    const val MIN_MAX_EDGE = 224

    /**
     * 长边上限。再大对 2B 档的收益已经很小，而视觉 token 是按面积平方增长的，
     * 尾延迟与内存都会明显变差。
     *
     * 实测口径：手机竖屏压到长边 1536 约 1296 个视觉 token，给文本留下 2160 —— 对
     * 4096 窗口而言，预算在常见机型上通常不 binding，实际起作用的是这条上限。
     * 预算真正 binding 的是元素清单很长的界面（例如文本开销约 2500 token 时，
     * 1080×2400 的长边会被压到 1290）。两条都要在，缺一条就会出现"要么永远不压、
     * 要么压过头"的形状。
     */
    const val ABSOLUTE_MAX_EDGE = 1536

    /**
     * 一张 [width]×[height] 的图会产出多少视觉 token。
     *
     * 按整块向下取整；任一边不足一块时为 0（送这样的图没有意义）。
     */
    fun visionTokensFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        val cols = width / PIXELS_PER_VISION_TOKEN_EDGE
        val rows = height / PIXELS_PER_VISION_TOKEN_EDGE
        return cols * rows
    }

    /** [parts] 拼起来大约占多少文本 token（估算，方向偏高，见类文档）。 */
    fun textTokensFor(vararg parts: String?): Int {
        val chars = parts.sumOf { it?.length ?: 0 }
        if (chars <= 0) return 0
        // 向上取整，避免短串被算成 0。
        return (chars + CHARS_PER_TEXT_TOKEN - 1) / CHARS_PER_TEXT_TOKEN
    }

    /**
     * 本次调用还剩多少 token 可以给图。
     *
     * @param contextSize      服务端 `-c`（[LlamaServerController.DEFAULT_CONTEXT_SIZE]）。
     * @param generationReserve 留给模型生成的 token 数（规划器的 maxTokens）。
     * @param textTokens       prompt 里非图像部分的 token 估算（见 [textTokensFor]）。
     * @return 可用于图像的 token 数；预算被文本与生成吃光时返回 0。
     */
    fun visionTokenBudget(contextSize: Int, generationReserve: Int, textTokens: Int): Int =
        (contextSize - generationReserve - textTokens - SAFETY_MARGIN_TOKENS).coerceAtLeast(0)

    /**
     * 在 [visionTokenBudget] 之内，[fullWidth]×[fullHeight] 的截图长边最多能开到多少。
     *
     * 保持宽高比。返回值恒在 [[MIN_MAX_EDGE], [ABSOLUTE_MAX_EDGE]] 内，且不超过原图长边
     * （原图已经够小时不放大 —— 放大不增加信息，只多烧 token）。
     *
     * 预算不足以支撑 [MIN_MAX_EDGE] 时返回 [MIN_MAX_EDGE] 并由 [fitsWithin] 报 false：
     * 判断"够不够"是调用方的事，本函数不替它做静默降级。
     */
    fun maxEdgeWithin(fullWidth: Int, fullHeight: Int, visionTokenBudget: Int): Int {
        val longest = maxOf(fullWidth, fullHeight)
        if (longest <= 0) return MIN_MAX_EDGE
        val shortest = minOf(fullWidth, fullHeight)
        if (shortest <= 0) return MIN_MAX_EDGE

        val ceiling = min(longest, ABSOLUTE_MAX_EDGE)
        if (visionTokenBudget <= 0) return MIN_MAX_EDGE

        // 闭式解：长边 E 时视觉 token ≈ (E/S)·(E·r/S)，其中 r = 短边/长边、S = 块边长。
        // 解 E = sqrt(budget · S² / r)。随后逐级校正，保证结果对 visionTokensFor 真的成立
        // ——闭式解用的是连续近似，而 visionTokensFor 是整块向下取整的。
        val ratio = shortest.toDouble() / longest.toDouble()
        val blockArea = (PIXELS_PER_VISION_TOKEN_EDGE * PIXELS_PER_VISION_TOKEN_EDGE).toDouble()
        val estimate = sqrt(visionTokenBudget.toDouble() * blockArea / ratio).toInt()

        var edge = estimate.coerceIn(MIN_MAX_EDGE, ceiling)
        // 向下校正:闭式解可能略微超预算(取整方向不同)。步长取一个块边长,最多走
        // (ceiling - MIN_MAX_EDGE)/28 步,必然终止。
        while (edge > MIN_MAX_EDGE && tokensAtEdge(fullWidth, fullHeight, edge) > visionTokenBudget) {
            edge -= PIXELS_PER_VISION_TOKEN_EDGE
        }
        return edge.coerceIn(MIN_MAX_EDGE, ceiling)
    }

    /** 把长边压到 [edge] 后（保持宽高比）会产出多少视觉 token。 */
    fun tokensAtEdge(fullWidth: Int, fullHeight: Int, edge: Int): Int {
        val longest = maxOf(fullWidth, fullHeight)
        if (longest <= 0 || edge <= 0) return 0
        val scale = min(1.0, edge.toDouble() / longest.toDouble())
        return visionTokensFor((fullWidth * scale).toInt(), (fullHeight * scale).toInt())
    }

    /** 按 [edge] 送图是否真的装得下 [visionTokenBudget]。 */
    fun fitsWithin(fullWidth: Int, fullHeight: Int, edge: Int, visionTokenBudget: Int): Boolean =
        tokensAtEdge(fullWidth, fullHeight, edge) <= visionTokenBudget

    /**
     * 规划步的一站式入口：给定屏幕尺寸与本次 prompt 的文本部分，算出该用多大的长边。
     *
     * @param contextSize       服务端上下文窗口。
     * @param generationReserve 规划器的 maxTokens。
     * @param promptParts       prompt 的非图像部分（系统提示、目标、约束、元素清单…）。
     */
    fun plannerMaxEdge(
        fullWidth: Int,
        fullHeight: Int,
        contextSize: Int,
        generationReserve: Int,
        vararg promptParts: String?
    ): Int = maxEdgeWithin(
        fullWidth = fullWidth,
        fullHeight = fullHeight,
        visionTokenBudget = visionTokenBudget(
            contextSize = contextSize,
            generationReserve = generationReserve,
            textTokens = textTokensFor(*promptParts)
        )
    )
}
