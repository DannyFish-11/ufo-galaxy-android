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
 * 调用方传动作前后的截图字节，**以及（可选的）动作前后无障碍树指纹**。
 * 树指纹在场时优先用它 —— 见 [uiChanged] 的说明：它比像素更准，而且在截图拿不到的
 * 设备与页面上是唯一可用的判据。两条通道都拿不到时返回 `null`，表示**结论不明**，
 * 而不是硬报一个"没变"。
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
    fun uiChanged(beforeJpeg: ByteArray?, afterJpeg: ByteArray?): Boolean? =
        uiChanged(beforeJpeg, afterJpeg, beforeTreeSignature = null, afterTreeSignature = null)

    /**
     * 判断界面变了没有：**优先用无障碍树的指纹，拿不到再退回像素**。
     *
     * ## 为什么树指纹排在前面
     * 像素比较会被动画、闪烁的光标、跳动的时间戳搅动 —— 那些都不代表界面真的变了。
     * 树指纹只在元素集合或位置真的变了时才变，是更准的判据；各家成熟的手机 GUI agent
     * 框架用的也是这一类程序化判据，而不是让模型自评。
     *
     * ## 为什么必须有树这条路
     * 截图在三种真实情况下拿不到：设备 API < 30（`takeScreenshot` 是 API 30 才有的，
     * 而本模块 minSdk 26）、当前是 `FLAG_SECURE` 窗口（银行、密码页）、撞上平台的
     * 333ms 节流。此前这三种情况下"界面变了没有"完全没有答案，而那是循环判断有没有
     * 进展的唯一依据 —— 于是停滞检测在这些设备/页面上等于关掉了。
     *
     * @return 变了 / 没变；两条通道都拿不到时返回 null（**结论不明**，而不是"没变"）。
     */
    fun uiChanged(
        beforeJpeg: ByteArray?,
        afterJpeg: ByteArray?,
        beforeTreeSignature: String?,
        afterTreeSignature: String?
    ): Boolean? {
        if (!beforeTreeSignature.isNullOrEmpty() && !afterTreeSignature.isNullOrEmpty()) {
            return beforeTreeSignature != afterTreeSignature
        }
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
        afterJpeg: ByteArray?,
        /** 动作前的无障碍树指纹；null = 这一帧没读到树。 */
        beforeTreeSignature: String? = null,
        /** 动作后的无障碍树指纹；null = 这一帧没读到树。 */
        afterTreeSignature: String? = null
    ): StepObservation {
        val changed = uiChanged(beforeJpeg, afterJpeg, beforeTreeSignature, afterTreeSignature)
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
