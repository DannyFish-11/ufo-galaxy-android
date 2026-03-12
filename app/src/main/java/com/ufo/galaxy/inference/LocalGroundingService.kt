package com.ufo.galaxy.inference

/**
 * Local grounding service interface for SeeClick on-device GUI grounding.
 *
 * Pluggable runtime: NCNN (ARM NEON/Vulkan) or MNN backend.
 * Model: njucckevin/SeeClick (HuggingFace).
 *
 * Maps a natural-language intent and a live screenshot to physical screen coordinates.
 * Coordinates are produced exclusively on-device; the gateway never supplies x/y values.
 *
 * [NoOpGroundingService] is the safe default; it returns a structured error
 * without performing any inference.
 */
interface LocalGroundingService {

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
        val error: String? = null
    )

    /** Loads the SeeClick model weights into device memory. Returns true on success. */
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
}

/**
 * Safe default grounding service that returns a structured error without performing
 * any inference. Use when the SeeClick model is not loaded or the runtime is unavailable.
 * Provides all interface lifecycle hooks with no-op implementations.
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
        error = "SeeClick grounding not available: model not loaded"
    )
}
