package com.ufo.galaxy.eval

import com.ufo.galaxy.inference.VisionContextBudget
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * 截图送进模型之前,按 App 的做法压尺寸(只是在电脑上用 java.awt 做):
 *  - 定位:长边压到 [GROUNDING_MAX_EDGE](App 的 EdgeExecutor 默认 720),坐标再换算回原图;
 *  - 规划:长边由 [VisionContextBudget] 从服务端上下文窗口反推(App 的 ImageScaler.scaleForPlanning),装不下就不送图。
 */
object Images {
    const val GROUNDING_MAX_EDGE = 720

    /** App 的 LlamaServerController.DEFAULT_CONTEXT_SIZE。 */
    const val CONTEXT_SIZE = 4096

    /** App 的 EdgeExecutor.DEFAULT_PLANNER_GENERATION_RESERVE。 */
    const val PLANNER_GENERATION_RESERVE = 512

    data class Scaled(val base64: String, val width: Int, val height: Int)

    fun load(file: File): BufferedImage = ImageIO.read(file) ?: throw IllegalArgumentException("读不出图:$file")

    /** 长边不超过 [maxEdge](本来就不超就原样),编成 JPEG 的 base64。 */
    fun scaleToMaxEdge(img: BufferedImage, maxEdge: Int): Scaled {
        val longest = maxOf(img.width, img.height)
        val (w, h) = if (longest <= maxEdge || maxEdge <= 0) {
            img.width to img.height
        } else {
            val s = maxEdge.toFloat() / longest
            (img.width * s).toInt().coerceAtLeast(1) to (img.height * s).toInt().coerceAtLeast(1)
        }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, w, h, null)
            dispose()
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(out, "jpg", it) }.toByteArray()
        return Scaled(Base64.getEncoder().encodeToString(bytes), w, h)
    }

    /** 规划步送的图:按这次 prompt 的文本开销算出装得下的长边;连最小可读尺寸都装不下就不送(null)。 */
    fun forPlanning(img: BufferedImage, vararg promptParts: String?): Scaled? {
        val budget = VisionContextBudget.visionTokenBudget(
            contextSize = CONTEXT_SIZE,
            generationReserve = PLANNER_GENERATION_RESERVE,
            textTokens = VisionContextBudget.textTokensFor(*promptParts),
        )
        val edge = VisionContextBudget.maxEdgeWithin(img.width, img.height, budget)
        if (!VisionContextBudget.fitsWithin(img.width, img.height, edge, budget)) return null
        return scaleToMaxEdge(img, edge)
    }

    /** 缩放图里的点 → 原图像素。 */
    fun toFull(x: Int, y: Int, scaled: Scaled, fullWidth: Int, fullHeight: Int): Pair<Int, Int> =
        (x.toLong() * fullWidth / scaled.width).toInt() to (y.toLong() * fullHeight / scaled.height).toInt()
}
