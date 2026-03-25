package com.ufo.galaxy.local

/**
 * Determines whether the device UI changed between two consecutive screenshots.
 *
 * [PostActionObserver] is called by [com.ufo.galaxy.loop.LoopController] after each
 * action step to populate the [StepObservation.uiChanged] field. It uses a lightweight
 * byte-fingerprint comparison so it can run synchronously on the IO dispatcher with no
 * Android framework dependencies (making it fully JVM-testable).
 *
 * The comparison is intentionally coarse: any difference in the first [SAMPLE_BYTES]
 * bytes of the JPEG payload is treated as a UI change. This avoids false-positive
 * "no change" readings from minor JPEG encoding variation by sampling a region-agnostic
 * portion of the compressed bitstream rather than decoded pixels.
 *
 * Callers pass the JPEG bytes of the **pre-action** and **post-action** screenshots.
 * When either capture is null (capture failure), [uiChanged] returns `null` to signal
 * that the observation is inconclusive rather than forcing a false "no change" reading.
 */
class PostActionObserver {

    companion object {
        /** Number of bytes sampled from each screenshot for fingerprint comparison. */
        internal const val SAMPLE_BYTES = 512

        /**
         * Minimum proportion of differing sampled bytes required to declare a UI change.
         * Values below this threshold are treated as encoding noise.
         */
        internal const val CHANGE_THRESHOLD = 0.05f
    }

    /**
     * Compares [beforeJpeg] and [afterJpeg] to determine whether the UI changed.
     *
     * @param beforeJpeg JPEG bytes captured immediately before the action; null if unavailable.
     * @param afterJpeg  JPEG bytes captured immediately after the action; null if unavailable.
     * @return `true` if a meaningful UI change was detected, `false` if the screen looks
     *         identical, or `null` if the comparison could not be performed (missing capture).
     */
    fun uiChanged(beforeJpeg: ByteArray?, afterJpeg: ByteArray?): Boolean? {
        if (beforeJpeg == null || afterJpeg == null) return null
        if (beforeJpeg.isEmpty() || afterJpeg.isEmpty()) return null

        val limit = minOf(SAMPLE_BYTES, beforeJpeg.size, afterJpeg.size)
        var diffCount = 0
        for (i in 0 until limit) {
            if (beforeJpeg[i] != afterJpeg[i]) diffCount++
        }

        val diffRatio = diffCount.toFloat() / limit.toFloat()
        return diffRatio >= CHANGE_THRESHOLD
    }

    /**
     * Builds a [StepObservation] that combines accessibility execution outcomes with
     * a UI-change observation derived from comparing [beforeJpeg] and [afterJpeg].
     *
     * @param stepId         Step identifier.
     * @param actionType     Symbolic action type.
     * @param intent         Natural-language intent.
     * @param actionSucceeded Whether [com.ufo.galaxy.agent.AccessibilityExecutor.execute] returned true.
     * @param failureCode    Structured reason when [actionSucceeded] is false; null otherwise.
     * @param confidence     Grounding confidence [0.0, 1.0]; 0f when grounding was bypassed.
     * @param targetMatched  Whether the grounding engine found the intended target.
     * @param beforeJpeg     JPEG bytes of the screen before the action.
     * @param afterJpeg      JPEG bytes of the screen after the action.
     * @return Populated [StepObservation].
     */
    fun observe(
        stepId: String,
        actionType: String,
        intent: String,
        actionSucceeded: Boolean,
        failureCode: FailureCode?,
        confidence: Float,
        targetMatched: Boolean?,
        beforeJpeg: ByteArray?,
        afterJpeg: ByteArray?
    ): StepObservation {
        val changed = uiChanged(beforeJpeg, afterJpeg)
        val screenshotCaptured = afterJpeg != null

        return if (actionSucceeded) {
            StepObservation.success(
                stepId = stepId,
                actionType = actionType,
                intent = intent,
                uiChanged = changed,
                targetMatched = targetMatched,
                confidence = confidence
            )
        } else {
            StepObservation.failure(
                stepId = stepId,
                actionType = actionType,
                intent = intent,
                failureCode = failureCode ?: FailureCode.UNKNOWN,
                summary = (failureCode ?: FailureCode.UNKNOWN).description,
                confidence = confidence,
                screenshotCaptured = screenshotCaptured,
                uiChanged = changed,
                targetMatched = targetMatched
            )
        }
    }
}
