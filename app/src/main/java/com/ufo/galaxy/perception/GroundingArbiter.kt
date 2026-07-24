package com.ufo.galaxy.perception

import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.observability.GalaxyLogger

/**
 * 双通道定位仲裁器:视觉模型坐标 × 结构化树候选的综合判断。
 *
 * 所有者的明确要求:结构化信息与视觉**同时在场**、综合判断提准确率,
 * 而不是一个失败另一个兜底。裁决矩阵(证据一致性优先):
 *
 * | 视觉结果 | 树候选 | 裁决 | fusion_source |
 * |---|---|---|---|
 * | 有效,落在匹配元素内 | 有 | 视觉坐标,置信度取两者较大 | agreement |
 * | 有效,落在任意元素内但该元素不匹配意图,且存在强匹配候选 | 有 | 强候选中心点 | tree_override |
 * | 有效,不落在任何元素内(树覆盖了该点区域)且存在强匹配候选 | 有 | 强候选中心点 | tree_override |
 * | 有效 | 无/弱 | 视觉坐标原样 | vlm_only |
 * | 失败(error 非空)且存在强匹配候选 | 有 | 强候选中心点 | tree_rescue |
 * | 失败 | 无/弱 | 原失败结果透传(交下游梯子) | (无) |
 *
 * "强匹配"阈值 [STRONG_MATCH_THRESHOLD]:tree_override 属于推翻视觉证据的动作,
 * 阈值故意高于 tree_rescue([RESCUE_MATCH_THRESHOLD],视觉已失败,树只需可信即可)。
 * 每次裁决都打结构化日志,真机数据回流后可据实调阈值。
 *
 * 纯函数、无 Android 依赖,全部可 JVM 单测。
 */
object GroundingArbiter {

    private const val TAG = "GALAXY:PERCEPTION:ARBITER"

    /** 推翻视觉坐标(tree_override)所需的最低树匹配分。 */
    const val STRONG_MATCH_THRESHOLD = 0.75f

    /** 视觉失败后采用树候选(tree_rescue)所需的最低树匹配分。 */
    const val RESCUE_MATCH_THRESHOLD = 0.55f

    /** 裁决来源标签(进结构化日志与 [Fused.source])。 */
    const val SOURCE_AGREEMENT = "agreement"
    const val SOURCE_VLM_ONLY = "vlm_only"
    const val SOURCE_TREE_OVERRIDE = "tree_override"
    const val SOURCE_TREE_RESCUE = "tree_rescue"
    const val SOURCE_VLM_FAILED = "vlm_failed_no_tree"

    /** 仲裁结果:最终定位 + 来源标签。 */
    data class Fused(
        val result: LocalGroundingService.GroundingResult,
        val source: String
    )

    /**
     * 综合裁决。[vlm] 为视觉模型原始结果(可能带 error);[snapshot] 为同帧结构化
     * 快照(null = 无结构化通道,视觉结果原样透传);坐标系必须一致 —— 调用方负责
     * 在缩放截图定位时把树 bounds 或视觉坐标换算到同一空间后再进来。
     */
    fun fuse(
        intent: String,
        vlm: LocalGroundingService.GroundingResult,
        snapshot: UiStructuredSnapshot?
    ): Fused {
        if (snapshot == null || snapshot.elements.isEmpty()) {
            return log(intent, Fused(vlm, if (vlm.error == null) SOURCE_VLM_ONLY else SOURCE_VLM_FAILED))
        }

        val candidates = snapshot.matchCandidates(intent)
        val best = candidates.firstOrNull()

        // 视觉失败 → 树可信即救场。
        if (vlm.error != null) {
            return if (best != null && best.score >= RESCUE_MATCH_THRESHOLD) {
                log(intent, Fused(fromElement(best, snapshot), SOURCE_TREE_RESCUE))
            } else {
                log(intent, Fused(vlm, SOURCE_VLM_FAILED))
            }
        }

        // 视觉坐标落在意图匹配的元素内 → 双证据一致。
        val hitMatching = candidates.any { it.element.contains(vlm.x, vlm.y) && it.score > 0f }
        if (hitMatching) {
            val boosted = vlm.copy(confidence = maxOf(vlm.confidence, best?.score ?: 0f))
            return log(intent, Fused(boosted, SOURCE_AGREEMENT))
        }

        // 视觉坐标与强树候选相悖 → 树推翻视觉。
        if (best != null && best.score >= STRONG_MATCH_THRESHOLD) {
            return log(intent, Fused(fromElement(best, snapshot), SOURCE_TREE_OVERRIDE))
        }

        // 树无强证据 → 尊重视觉。
        return log(intent, Fused(vlm, SOURCE_VLM_ONLY))
    }

    private fun fromElement(
        scored: UiStructuredSnapshot.ScoredElement,
        snapshot: UiStructuredSnapshot
    ): LocalGroundingService.GroundingResult {
        val e = scored.element
        val maxX = (snapshot.screenWidth - 1).coerceAtLeast(0)
        val maxY = (snapshot.screenHeight - 1).coerceAtLeast(0)
        return LocalGroundingService.GroundingResult(
            x = if (maxX > 0) e.centerX.coerceIn(0, maxX) else e.centerX.coerceAtLeast(0),
            y = if (maxY > 0) e.centerY.coerceIn(0, maxY) else e.centerY.coerceAtLeast(0),
            confidence = scored.score,
            element_description = e.label
        )
    }

    private fun log(intent: String, fused: Fused): Fused {
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "grounding_fused",
                "source" to fused.source,
                "intent_len" to intent.length,
                "x" to fused.result.x,
                "y" to fused.result.y,
                "confidence" to fused.result.confidence,
                "error" to (fused.result.error ?: "")
            )
        )
        return fused
    }
}
