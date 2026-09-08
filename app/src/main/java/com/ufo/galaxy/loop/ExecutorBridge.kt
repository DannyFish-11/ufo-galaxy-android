package com.ufo.galaxy.loop

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.ImageScaler
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.local.FailureCode
import com.ufo.galaxy.local.GroundingFallbackLadder
import com.ufo.galaxy.observability.GalaxyLogger

/**
 * Maps [ActionStep]s to concrete [AccessibilityExecutor] actions and dispatches them.
 *
 * 需要屏幕坐标的动作(tap / scroll)由 [GroundingFallbackLadder] 把意图解析成 (x, y);
 * 不需要坐标的动作(type / back / home / open_app)直接派发。
 *
 * 定位失败时**只有滚动**会退到屏幕中心锚点(见 [STAGE_SCROLL_CENTRE_ANCHOR]),
 * 点击一律判失败并交给 [LoopController] 重规划 —— 不知道该点哪的时候点屏幕中间,
 * 落点可能是一条列表项、一个「删除」或一次付款确认。
 *
 * @param groundingService      Unified VLM grounding engine.
 * @param accessibilityExecutor Dispatches device actions via AccessibilityService.
 * @param imageScaler           Optional downscaler for grounding input;
 *                              use [NoOpImageScaler] in JVM tests.
 * @param scaledMaxEdge         Max longest edge (px) for primary grounding input; 0 = full resolution.
 */
class ExecutorBridge(
    private val groundingService: LocalGroundingService,
    private val accessibilityExecutor: AccessibilityExecutor,
    private val imageScaler: ImageScaler = NoOpImageScaler(),
    private val scaledMaxEdge: Int = 720,
    /**
     * 结构化感知通道(无障碍树快照)。非 null 时每步与截图同帧采集,元素清单注入
     * 梯子的主/缩放视觉定位 prompt,并作为最后一级「树救场」的证据来源。
     *
     * 这一路**不是可选的锦上添花**:设备上没有 VLM 权重时,树是唯一的定位依据。
     * null(默认,仅单测用)= 纯视觉,没有模型就没有任何定位能力。
     */
    private val uiSnapshotProvider: com.ufo.galaxy.perception.UiSnapshotProvider? = null
) {

    companion object {
        internal const val TAG = "GALAXY:LOOP:EXECUTOR"

        /**
         * Action types that bypass coordinate grounding and are dispatched directly.
         * All others (tap, scroll) go through [GroundingFallbackLadder].
         */
        private val NO_GROUNDING_ACTIONS = setOf("type", "back", "home", "open_app")

        /**
         * 定位失败但动作是滚动时用的 stage 标签。
         *
         * 单独起名字,是为了让真机日志能一眼分出「模型定位到了一个滚动锚点」和
         * 「定位失败、我们退到了屏幕中心」—— 这两者此前在梯子里被混成同一件事。
         */
        const val STAGE_SCROLL_CENTRE_ANCHOR = "scroll_centre_anchor"

        /**
         * 屏幕中心锚点的置信度。刻意不是 0:动作本身是正确执行的(滚动确实滚了),
         * 只是锚点不是定位出来的。也刻意不高:上层若要按置信度筛,这一步应当排在
         * 任何真实定位结果之后。
         */
        const val SCROLL_ANCHOR_CONFIDENCE = 0.1f
    }

    private val groundingLadder = GroundingFallbackLadder(
        groundingService = groundingService,
        imageScaler = imageScaler,
        primaryMaxEdge = if (scaledMaxEdge > 0) scaledMaxEdge else GroundingFallbackLadder.DEFAULT_PRIMARY_MAX_EDGE,
        resizedMaxEdge = if (scaledMaxEdge > 0) scaledMaxEdge / 2 else GroundingFallbackLadder.DEFAULT_RESIZED_MAX_EDGE
    )

    /**
     * Executes [step] against the device and returns an updated [ActionStep] with the result.
     *
     * 定位走 [GroundingFallbackLadder]:主视觉 → 缩小重试 → 无障碍树救场。三级都拿不到
     * 可信坐标时,点击类动作抛出并被记为失败(交给上层重规划),滚动退到屏幕中心锚点。
     *
     * @param step         Action to execute.
     * @param jpegBytes    Full-resolution JPEG bytes of the current screen (used for grounding).
     * @param screenWidth  Screen width in pixels; 0 when unknown.
     * @param screenHeight Screen height in pixels; 0 when unknown.
     * @return Updated [ActionStep] with [StepStatus.SUCCESS] or [StepStatus.FAILED].
     */
    fun execute(
        step: ActionStep,
        jpegBytes: ByteArray,
        screenWidth: Int,
        screenHeight: Int
    ): ActionStep {
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "execute",
                "step_id" to step.id,
                "action_type" to step.actionType,
                "intent" to step.intent.take(80)
            )
        )

        // 终止动作不落地成任何设备动作:不定位、不派发、也**不碰执行器**。
        // 它表达的是"目标已达成",不是一次操作。放到这里而不是做成一个
        // AccessibilityAction 成员,是因为 HardwareKeyListener.executeAction 里那个
        // when 是对密封类穷尽的 —— 多一个成员会要求那里也处理它,而它压根不该走到那儿。
        if (step.actionType == LoopController.ACTION_FINISH) {
            GalaxyLogger.log(TAG, mapOf(
                "event" to "finish_declared",
                "step_id" to step.id,
                "intent" to step.intent.take(80)
            ))
            return step.copy(status = StepStatus.SUCCESS, confidence = 1f)
        }

        return try {
            val (action, confidence, groundingStage) = resolveAction(
                step, jpegBytes, screenWidth, screenHeight
            )
            val success = accessibilityExecutor.execute(action)
            val status = if (success) StepStatus.SUCCESS else StepStatus.FAILED
            val failureCode = if (!success) FailureCode.EXEC_ACCESSIBILITY_RETURNED_FALSE else null
            val failureReason = if (!success) failureCode?.description ?: "execution_failed" else null

            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "execute_result",
                    "step_id" to step.id,
                    "action_type" to step.actionType,
                    "success" to success,
                    "confidence" to confidence,
                    "grounding_stage" to groundingStage
                )
            )

            step.copy(
                status = status,
                confidence = confidence,
                failureReason = failureReason,
                failureCode = failureCode
            )
        } catch (e: Exception) {
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "execute_error",
                    "step_id" to step.id,
                    "error" to (e.message ?: "unknown")
                )
            )
            step.copy(
                status = StepStatus.FAILED,
                failureReason = e.message ?: "Execution exception",
                failureCode = FailureCode.EXEC_EXCEPTION
            )
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Returns a Triple of (action, confidence, groundingStage).
     * groundingStage is the name of the grounding ladder stage used (for logging).
     */
    private fun resolveAction(
        step: ActionStep,
        jpegBytes: ByteArray,
        screenWidth: Int,
        screenHeight: Int
    ): Triple<AccessibilityExecutor.AccessibilityAction, Float, String> {

        // Actions that never need grounding are dispatched directly.
        if (step.actionType in NO_GROUNDING_ACTIONS) {
            return Triple(buildDirectAction(step), 1f, "direct")
        }

        // Use the grounding fallback ladder for coordinate-based actions.
        // 双通道:同帧采集树快照,元素清单注入梯子的视觉级 prompt。
        // 真 bug 修复(坐标空间混用):此前在此处预渲染全分辨率元素清单文本,而梯子
        // 内部会把截图缩放到不同边长 —— prompt 中元素坐标与截图空间不一致。改为
        // 传快照本体,由梯子按各级实际缩放尺寸换算后注入。
        val grounding = groundingLadder.ground(
            sessionId = "",
            stepId = step.id,
            intent = step.intent,
            jpegBytes = jpegBytes,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            uiSnapshot = uiSnapshotProvider?.capture()
        )

        if (!grounding.succeeded) {
            // 滚动是唯一一个「不知道确切位置也能正确执行」的动作:从屏幕中心起手滑,
            // 本来就是各家框架的默认锚点。所以这里显式降级,并把 stage 记成
            // scroll_centre_anchor —— 是一个**有名字的、如实记录的**决定。
            //
            // 点击不适用:不知道该点哪时点屏幕中间,可能正好落在一条列表项、
            // 一个「删除」、一次付款确认上。那种情况下唯一正确的动作是承认定位失败,
            // 交给 LoopController 去重规划。
            if (step.actionType == "scroll" && screenWidth > 0 && screenHeight > 0) {
                GalaxyLogger.log(TAG, mapOf(
                    "event" to "scroll_centre_anchor",
                    "step_id" to step.id,
                    "reason" to (grounding.error ?: "grounding_failed")
                ))
                return Triple(
                    AccessibilityExecutor.AccessibilityAction.Scroll(
                        screenWidth / 2, screenHeight / 2,
                        step.parameters.getOrDefault("direction", "down")
                    ),
                    SCROLL_ANCHOR_CONFIDENCE,
                    STAGE_SCROLL_CENTRE_ANCHOR
                )
            }
            throw IllegalStateException(
                grounding.error ?: FailureCode.GROUND_ALL_STAGES_EXHAUSTED.description
            )
        }

        val action: AccessibilityExecutor.AccessibilityAction = when (step.actionType) {
            "tap" -> AccessibilityExecutor.AccessibilityAction.Tap(grounding.x, grounding.y)
            "scroll" -> AccessibilityExecutor.AccessibilityAction.Scroll(
                grounding.x, grounding.y,
                step.parameters.getOrDefault("direction", "down")
            )
            else -> AccessibilityExecutor.AccessibilityAction.Tap(grounding.x, grounding.y)
        }

        return Triple(action, grounding.confidence, grounding.stageUsed)
    }

    private fun buildDirectAction(
        step: ActionStep
    ): AccessibilityExecutor.AccessibilityAction = when (step.actionType) {
        "type" -> AccessibilityExecutor.AccessibilityAction.TypeText.from(
            step.parameters, fallbackText = step.intent
        )
        "back" -> AccessibilityExecutor.AccessibilityAction.Back
        "home" -> AccessibilityExecutor.AccessibilityAction.Home
        "open_app" -> AccessibilityExecutor.AccessibilityAction.OpenApp(
            step.parameters.getOrDefault("package", "")
        )
        "scroll" -> AccessibilityExecutor.AccessibilityAction.Scroll(
            0, 0,
            step.parameters.getOrDefault("direction", "down")
        )
        else -> AccessibilityExecutor.AccessibilityAction.Tap(0, 0)
    }
}
