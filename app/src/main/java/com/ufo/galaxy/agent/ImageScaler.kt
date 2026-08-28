package com.ufo.galaxy.agent

import com.ufo.galaxy.inference.VisionContextBudget

/**
 * Scales a JPEG screenshot for use as model input.
 *
 * The grounding engine performs best with images where the longest edge does not
 * exceed [maxEdge] pixels (default 720). Passing a smaller image reduces inference
 * latency and memory use; coordinates returned by the grounding engine must then
 * be remapped back to the full-resolution frame.
 *
 * Implementations:
 *  - [NoOpImageScaler] – returns the original bytes unchanged; used in JVM unit tests.
 *  - [com.ufo.galaxy.service.AndroidBitmapScaler] – uses [android.graphics.Bitmap] scaling.
 */
interface ImageScaler {

    /**
     * Result of a scaling operation.
     *
     * @param scaledJpegBase64 Base64-encoded JPEG at scaled resolution.
     * @param scaledWidth      Width of the scaled image in pixels.
     * @param scaledHeight     Height of the scaled image in pixels.
     */
    data class ScaledResult(
        val scaledJpegBase64: String,
        val scaledWidth: Int,
        val scaledHeight: Int
    )

    /**
     * Scales [jpegBytes] so the longest dimension is at most [maxEdge] pixels,
     * maintaining the original aspect ratio. Returns original dimensions unchanged
     * if the image already fits within [maxEdge].
     *
     * @param jpegBytes Raw JPEG bytes of the full-resolution screenshot.
     * @param fullWidth Full-resolution screen width in pixels.
     * @param fullHeight Full-resolution screen height in pixels.
     * @param maxEdge Maximum allowed length (px) of the longest edge after scaling.
     * @return [ScaledResult] with the (possibly downscaled) JPEG and its dimensions.
     */
    fun scaleToMaxEdge(
        jpegBytes: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        maxEdge: Int
    ): ScaledResult
}

/**
 * Pass-through [ImageScaler] that returns the original JPEG without modification.
 *
 * Used in JVM unit tests where [android.graphics.Bitmap] is unavailable.
 * Assumes [fullWidth] × [fullHeight] are accurate screen dimensions.
 */
class NoOpImageScaler : ImageScaler {
    override fun scaleToMaxEdge(
        jpegBytes: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        maxEdge: Int
    ): ImageScaler.ScaledResult {
        val base64 = java.util.Base64.getEncoder().encodeToString(jpegBytes)
        return ImageScaler.ScaledResult(
            scaledJpegBase64 = base64,
            scaledWidth = fullWidth,
            scaledHeight = fullHeight
        )
    }
}

/**
 * 规划步送图的结果:压到多大、为什么是这个大小、以及最终有没有送。
 *
 * @param base64            可直接放进 prompt 的 base64;**null = 预算不足,本次不送图**。
 * @param maxEdge           实际采用的长边(px)。
 * @param visionTokenBudget 本次调用留给图像的 token 数。
 * @param visionTokens      按 [maxEdge] 送图实际占用的视觉 token 数。
 * @param scaledWidth       缩放后宽度。
 * @param scaledHeight      缩放后高度。
 */
data class PlanningImage(
    val base64: String?,
    val maxEdge: Int,
    val visionTokenBudget: Int,
    val visionTokens: Int,
    val scaledWidth: Int,
    val scaledHeight: Int
) {
    /** 本次是否真的带了图。 */
    val hasImage: Boolean get() = base64 != null
}

/**
 * 把截图压到本次规划调用**装得下**的尺寸。
 *
 * 长边由 [VisionContextBudget] 从服务端上下文窗口反推:
 * `窗口 − 生成预留 − 本次 prompt 的文本开销 − 安全余量` 即视觉可用 token,
 * 再解出保持宽高比时最大的长边。文本开销按**本次真实拼进 prompt 的串**估算
 * (目标 + 约束 + 元素清单),不是一个固定常数 —— 元素清单长的界面会自动让图变小。
 *
 * 这条路径存在的原因是一处真实缺陷:规划步此前把**全分辨率**截图的 base64 直接送进
 * 模型,而服务端 `-c` 恒 4096 —— 一张 1080×2400 的原图约 3300 个视觉 token,加上元素
 * 清单与生成预留就没有余量了,1440p 屏必然溢出;而全链路没有任何一处数过、卡过、截过
 * 这个数,溢出表现为"模型忽然不听话"(prompt 被静默截断)而不是一个明确的失败。
 *
 * 定位步不走这里:它有自己的固定长边,因为定位需要坐标精度,且单次 prompt 里没有
 * 元素清单以外的大块文本。
 *
 * 预算连 [VisionContextBudget.MIN_MAX_EDGE] 都撑不住时返回 [PlanningImage.base64] == null
 * ——送一张糊到读不出图标的图,和送一张会把指令挤掉的图,都是错的;让规划器只凭元素清单
 * 工作,是这两者之外唯一诚实的选择。调用方据 [PlanningImage.hasImage] 决定如何记录。
 *
 * @param promptParts prompt 的非图像部分(目标、约束、元素清单…);null 项按 0 计。
 */
fun ImageScaler.scaleForPlanning(
    jpegBytes: ByteArray,
    fullWidth: Int,
    fullHeight: Int,
    contextSize: Int,
    generationReserve: Int,
    vararg promptParts: String?
): PlanningImage {
    val budget = VisionContextBudget.visionTokenBudget(
        contextSize = contextSize,
        generationReserve = generationReserve,
        textTokens = VisionContextBudget.textTokensFor(*promptParts)
    )
    val edge = VisionContextBudget.maxEdgeWithin(fullWidth, fullHeight, budget)
    if (!VisionContextBudget.fitsWithin(fullWidth, fullHeight, edge, budget)) {
        return PlanningImage(
            base64 = null,
            maxEdge = edge,
            visionTokenBudget = budget,
            visionTokens = VisionContextBudget.tokensAtEdge(fullWidth, fullHeight, edge),
            scaledWidth = 0,
            scaledHeight = 0
        )
    }
    val scaled = scaleToMaxEdge(jpegBytes, fullWidth, fullHeight, edge)
    return PlanningImage(
        base64 = scaled.scaledJpegBase64,
        maxEdge = edge,
        visionTokenBudget = budget,
        visionTokens = VisionContextBudget.visionTokensFor(scaled.scaledWidth, scaled.scaledHeight),
        scaledWidth = scaled.scaledWidth,
        scaledHeight = scaled.scaledHeight
    )
}
