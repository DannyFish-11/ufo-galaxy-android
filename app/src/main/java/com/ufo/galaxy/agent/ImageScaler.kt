package com.ufo.galaxy.agent

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
