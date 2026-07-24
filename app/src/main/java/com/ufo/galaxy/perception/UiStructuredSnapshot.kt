package com.ufo.galaxy.perception

/**
 * 结构化屏幕快照:无障碍树拍平后的可交互/带语义元素清单。
 *
 * 这是"双通道感知"的结构化通道载体:与截图**同时**送入 VLM(注入 prompt 的
 * 元素清单)并参与 [GroundingArbiter] 的坐标综合判断 —— 不是视觉失败后的兜底,
 * 而是并行证据源。树读取失败(无障碍服务未连接等)时快照为 null,链路自动退化
 * 为纯视觉,行为与引入本层之前完全一致。
 *
 * 纯 JVM 数据模型:采集(Android 侧)与消费(prompt 注入 / 仲裁)分离,
 * 本文件全部可单测。
 */
data class UiStructuredSnapshot(
    /** 前台应用包名;未知为空串。 */
    val packageName: String = "",
    /** 屏幕像素宽(与元素 bounds 同一坐标系)。 */
    val screenWidth: Int = 0,
    /** 屏幕像素高。 */
    val screenHeight: Int = 0,
    /** 拍平并剪枝后的元素清单(遍历序即编号序)。 */
    val elements: List<UiElement> = emptyList()
) {

    /**
     * 单个 UI 元素(无障碍节点投影)。bounds 为屏幕绝对像素坐标。
     */
    data class UiElement(
        val index: Int,
        val text: String,
        val contentDescription: String,
        val className: String,
        val clickable: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2

        /** (x, y) 是否落在本元素 bounds 内(含边界)。 */
        fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

        /** 元素的语义标签:text 优先,空则用 contentDescription。 */
        val label: String get() = text.ifBlank { contentDescription }
    }

    companion object {
        /** prompt 注入的元素数量上限:控制 token 成本,超出部分按语义价值截断。 */
        const val MAX_PROMPT_ELEMENTS = 60

        /** 单元素标签在 prompt 中的最大字符数,防超长文本撑爆上下文。 */
        const val MAX_LABEL_LENGTH = 60
    }

    /**
     * 渲染为注入 VLM prompt 的紧凑元素清单,一行一元素:
     * `[3] "登录" Button clickable (540,1170)`
     *
     * 只包含带语义标签的元素(无标签纯容器对模型无用);超过
     * [MAX_PROMPT_ELEMENTS] 时优先保留 clickable 元素。返回空串表示无可注入内容
     * (调用方应跳过注入,保持纯视觉 prompt)。
     */
    fun toPromptBlock(maxElements: Int = MAX_PROMPT_ELEMENTS): String {
        val labeled = elements.filter { it.label.isNotBlank() }
        if (labeled.isEmpty()) return ""
        val selected = if (labeled.size <= maxElements) {
            labeled
        } else {
            // clickable 优先,其余按原序补足。
            val clickables = labeled.filter { it.clickable }
            val rest = labeled.filter { !it.clickable }
            (clickables + rest).take(maxElements).sortedBy { it.index }
        }
        return selected.joinToString("\n") { e ->
            val shortLabel = e.label.take(MAX_LABEL_LENGTH)
            val kind = e.className.substringAfterLast('.').ifBlank { "View" }
            val clickTag = if (e.clickable) " clickable" else ""
            "[${e.index}] \"$shortLabel\" $kind$clickTag (${e.centerX},${e.centerY})"
        }
    }

    /**
     * 按意图文本给每个元素打匹配分(0.0~1.0),返回按分降序的候选。
     *
     * 评分:标签与意图的归一化 token 重叠率;完整包含(标签是意图子串或反之)
     * 额外加权;clickable 加小权。纯启发式 —— 它只是仲裁的证据之一,阈值判断
     * 在 [GroundingArbiter]。
     */
    fun matchCandidates(intent: String): List<ScoredElement> {
        val intentTokens = tokenize(intent)
        if (intentTokens.isEmpty()) return emptyList()
        return elements.mapNotNull { e ->
            val label = e.label
            if (label.isBlank()) return@mapNotNull null
            val labelTokens = tokenize(label)
            if (labelTokens.isEmpty()) return@mapNotNull null
            val overlap = intentTokens.intersect(labelTokens).size.toFloat() /
                maxOf(intentTokens.size, 1).toFloat()
            val normIntent = normalize(intent)
            val normLabel = normalize(label)
            val containment = when {
                normLabel.isNotBlank() && normIntent.contains(normLabel) -> 0.5f
                normIntent.isNotBlank() && normLabel.contains(normIntent) -> 0.5f
                else -> 0f
            }
            val clickBonus = if (e.clickable) 0.1f else 0f
            val score = (overlap * 0.5f + containment + clickBonus).coerceAtMost(1f)
            if (score <= 0f) null else ScoredElement(e, score)
        }.sortedByDescending { it.score }
    }

    /** 元素 + 意图匹配分。 */
    data class ScoredElement(val element: UiElement, val score: Float)

    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() || it.code > 0x2E7F }

    /**
     * 混合分词:拉丁词按非字母数字切分;CJK 字符逐字成 token
     * (中文 UI 标签多为 2~4 字短词,逐字重叠率已够仲裁用)。
     */
    private fun tokenize(s: String): Set<String> {
        val tokens = mutableSetOf<String>()
        val latin = StringBuilder()
        for (ch in s.lowercase()) {
            when {
                ch.isLetterOrDigit() && ch.code < 0x2E80 -> latin.append(ch)
                ch.code >= 0x2E80 -> {
                    if (latin.isNotEmpty()) { tokens.add(latin.toString()); latin.clear() }
                    tokens.add(ch.toString())
                }
                else -> {
                    if (latin.isNotEmpty()) { tokens.add(latin.toString()); latin.clear() }
                }
            }
        }
        if (latin.isNotEmpty()) tokens.add(latin.toString())
        // 过滤无区分度的单字符拉丁 token。
        return tokens.filterNot { it.length == 1 && it[0].code < 0x2E80 }.toSet()
    }
}

/**
 * 结构化快照提供者。Android 实现走无障碍树;JVM 测试注入固定快照。
 * 返回 null 表示当前拿不到结构化信息(服务未连接/树为空),链路退化为纯视觉。
 */
fun interface UiSnapshotProvider {
    fun capture(): UiStructuredSnapshot?
}
