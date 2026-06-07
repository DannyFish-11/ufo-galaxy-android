package com.ufo.galaxy.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ufo.galaxy.agent.ImageScaler
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * [ImageScaler] implementation that uses [android.graphics.Bitmap] to downscale
 * a JPEG screenshot so the longest edge does not exceed [maxEdge] pixels.
 *
 * The full-resolution JPEG is decoded, scaled in-memory, and re-encoded to JPEG
 * at [jpegQuality] (default 85). If the image already fits within [maxEdge], the
 * original bytes are returned without re-encoding to avoid lossy double-compression.
 *
 * Snapshot payloads always use the full-resolution image captured by
 * [AccessibilityScreenshotProvider]; only the grounding input is downscaled here.
 */
class AndroidBitmapScaler(
    private val jpegQuality: Int = 85
) : ImageScaler {

    override fun scaleToMaxEdge(
        jpegBytes: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        maxEdge: Int
    ): ImageScaler.ScaledResult {
        val longestEdge = maxOf(fullWidth, fullHeight)
        if (longestEdge <= maxEdge || maxEdge <= 0) {
            // Image already within budget — return as-is.
            return ImageScaler.ScaledResult(
                scaledJpegBase64 = Base64.getEncoder().encodeToString(jpegBytes),
                scaledWidth = fullWidth,
                scaledHeight = fullHeight
            )
        }

        val scale = maxEdge.toFloat() / longestEdge.toFloat()
        val targetWidth = (fullWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (fullHeight * scale).toInt().coerceAtLeast(1)

        val original = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return ImageScaler.ScaledResult(
                // Decode failed — fall back to original bytes.
                scaledJpegBase64 = Base64.getEncoder().encodeToString(jpegBytes),
                scaledWidth = fullWidth,
                scaledHeight = fullHeight
            )

        val scaled = Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true)
        original.recycle()

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        scaled.recycle()

        return ImageScaler.ScaledResult(
            scaledJpegBase64 = Base64.getEncoder().encodeToString(out.toByteArray()),
            scaledWidth = targetWidth,
            scaledHeight = targetHeight
        )
    }
}
