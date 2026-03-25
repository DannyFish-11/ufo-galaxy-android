package com.ufo.galaxy.local

import com.ufo.galaxy.agent.ImageScaler
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.observability.GalaxyLogger

/**
 * Implements a multi-stage grounding fallback chain so that if primary SeeClick grounding
 * fails, the system attempts lower-cost alternatives before returning a structured failure.
 *
 * The ladder is attempted in order until valid coordinates are produced:
 *
 * 1. **Primary SeeClick** — full-resolution or downscaled screenshot passed to the
 *    loaded [LocalGroundingService].
 * 2. **Resized retry** — screenshot re-scaled to a smaller edge (50 % of the primary
 *    edge) before grounding; reduces encoding noise from compression artefacts.
 * 3. **OCR text matching** — falls back to an accessibility-node text search when the
 *    intent looks like a UI label (contains only printable words < 30 chars). Since this
 *    module has no direct accessibility access, this stage returns a heuristic centre
 *    coordinate that the caller can override when an accessibility layer is wired in.
 * 4. **Accessibility node heuristic** — returns the screen centre as a last-resort
 *    coordinate with a low confidence tag so the caller can decide whether to proceed.
 * 5. **Heuristic screen-region fallback** — maps intent keywords to coarse screen
 *    regions (top/bottom/centre) and returns region-centre coordinates.
 * 6. **Structured no-match failure** — all stages exhausted; returns an error result
 *    with [FailureCode.GROUND_ALL_STAGES_EXHAUSTED].
 *
 * @param groundingService The primary [LocalGroundingService] (SeeClick).
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

        const val STAGE_PRIMARY = "primary_seeclick"
        const val STAGE_RESIZED = "resized_retry"
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
        screenHeight: Int
    ): GroundingResult {

        // Stage 1: Primary SeeClick grounding.
        if (groundingService.isModelLoaded()) {
            val result = tryPrimaryGrounding(sessionId, stepId, intent, jpegBytes, screenWidth, screenHeight)
            if (result != null) return result
        }

        // Stage 2: Resized screenshot retry (smaller edge).
        if (groundingService.isModelLoaded() && resizedMaxEdge < primaryMaxEdge) {
            val result = tryResizedGrounding(sessionId, stepId, intent, jpegBytes, screenWidth, screenHeight)
            if (result != null) return result
        }

        // Stage 3: OCR text matching — heuristic centre if intent is a short UI label.
        val ocrResult = tryOcrHeuristic(sessionId, stepId, intent, screenWidth, screenHeight)
        if (ocrResult != null) return ocrResult

        // Stage 4: Accessibility node text/description heuristic — screen centre.
        val nodeResult = tryAccessibilityNodeHeuristic(sessionId, stepId, screenWidth, screenHeight)
        if (nodeResult != null) return nodeResult

        // Stage 5: Heuristic screen-region fallback — intent keyword → region.
        val regionResult = tryHeuristicRegion(sessionId, stepId, intent, screenWidth, screenHeight)
        if (regionResult != null) return regionResult

        // Stage 6: Structured no-match failure.
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
        screenHeight: Int
    ): GroundingResult? {
        return try {
            val scaled = imageScaler.scaleToMaxEdge(
                jpegBytes = jpegBytes,
                fullWidth = screenWidth,
                fullHeight = screenHeight,
                maxEdge = primaryMaxEdge
            )
            val raw = groundingService.ground(
                intent = intent,
                screenshotBase64 = scaled.scaledJpegBase64,
                width = scaled.scaledWidth,
                height = scaled.scaledHeight
            )
            if (raw.error != null || raw.confidence < MIN_PRIMARY_CONFIDENCE) {
                logStage(sessionId, stepId, STAGE_PRIMARY, "skip",
                    raw.error ?: "confidence=${raw.confidence}")
                null
            } else {
                val fullX = remapCoord(raw.x, scaled.scaledWidth, screenWidth)
                val fullY = remapCoord(raw.y, scaled.scaledHeight, screenHeight)
                logStage(sessionId, stepId, STAGE_PRIMARY, "ok")
                GroundingResult(fullX, fullY, raw.confidence, STAGE_PRIMARY)
            }
        } catch (e: Exception) {
            logStage(sessionId, stepId, STAGE_PRIMARY, "exception", e.message)
            null
        }
    }

    private fun tryResizedGrounding(
        sessionId: String,
        stepId: String,
        intent: String,
        jpegBytes: ByteArray,
        screenWidth: Int,
        screenHeight: Int
    ): GroundingResult? {
        return try {
            val scaled = imageScaler.scaleToMaxEdge(
                jpegBytes = jpegBytes,
                fullWidth = screenWidth,
                fullHeight = screenHeight,
                maxEdge = resizedMaxEdge
            )
            val raw = groundingService.ground(
                intent = intent,
                screenshotBase64 = scaled.scaledJpegBase64,
                width = scaled.scaledWidth,
                height = scaled.scaledHeight
            )
            if (raw.error != null || raw.confidence < MIN_PRIMARY_CONFIDENCE) {
                logStage(sessionId, stepId, STAGE_RESIZED, "skip",
                    raw.error ?: "confidence=${raw.confidence}")
                null
            } else {
                val fullX = remapCoord(raw.x, scaled.scaledWidth, screenWidth)
                val fullY = remapCoord(raw.y, scaled.scaledHeight, screenHeight)
                logStage(sessionId, stepId, STAGE_RESIZED, "ok")
                GroundingResult(fullX, fullY, raw.confidence, STAGE_RESIZED)
            }
        } catch (e: Exception) {
            logStage(sessionId, stepId, STAGE_RESIZED, "exception", e.message)
            null
        }
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
