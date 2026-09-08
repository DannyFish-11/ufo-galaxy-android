package com.ufo.galaxy.loop

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.local.FailureCode
import com.ufo.galaxy.perception.UiStructuredSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **不知道该点哪的时候，不许点。**
 *
 * 这条钉的是一处会让整套本地闭环"看起来能跑"的缺陷。[com.ufo.galaxy.local.GroundingFallbackLadder]
 * 里曾有三级兜底（`ocr_text_match` / `accessibility_node` / `heuristic_region`），
 * 三者的实现是同一件事：**返回屏幕正中心**。其中 `accessibility_node` 只要屏幕尺寸
 * 已知就无条件返回，于是：
 *
 *  1. 这条梯子**永远不会失败**（`no_match` 是死代码）；
 *  2. [ExecutorBridge] 只看 `AccessibilityExecutor.execute` 是否派发成功，于是一次
 *     盲点被记成 [StepStatus.SUCCESS]；
 *  3. [LoopController] 看到步步 SUCCESS，最后把任务报成 `task_complete`。
 *
 * 真机上的表现：智能体点了几下屏幕中间，然后告诉你办好了。而屏幕中间可能正好是
 * 一条列表项、一个「删除」、一次付款确认。
 *
 * 这类缺陷不会让任何既有测试变红 —— 它们断言的都是"成功了"。所以要专门钉它。
 */
class BlindCentreTapIsNotSuccessTest {

    private val screenW = 1080
    private val screenH = 2400

    /** 永远定位不出来的视觉引擎：模型没加载。真机上「权重没下完」就是这个形态。 */
    private class NoGrounding : LocalGroundingService {
        override fun loadModel() = false
        override fun unloadModel() {}
        override fun isModelLoaded() = false
        override fun ground(
            intent: String,
            screenshotBase64: String,
            width: Int,
            height: Int
        ) = LocalGroundingService.GroundingResult(
            x = 0, y = 0, confidence = 0f, element_description = "", error = "not_loaded"
        )
    }

    /** 记下真正被派发的动作。 */
    private class Recorder : AccessibilityExecutor {
        val dispatched = mutableListOf<AccessibilityExecutor.AccessibilityAction>()
        override fun execute(action: AccessibilityExecutor.AccessibilityAction): Boolean {
            dispatched += action
            return true // 手势确实派发出去了 —— 这正是「假成功」的来源
        }
    }

    private fun bridge(recorder: Recorder, snapshot: UiStructuredSnapshot? = null) =
        ExecutorBridge(
            groundingService = NoGrounding(),
            accessibilityExecutor = recorder,
            uiSnapshotProvider = snapshot?.let { s -> com.ufo.galaxy.perception.UiSnapshotProvider { s } }
        )

    private fun step(type: String, intent: String = "点开设置") = ActionStep(
        id = "step_1", actionType = type, intent = intent
    )

    @Test
    fun `定位失败的点击必须判失败，而不是点屏幕中心`() {
        val recorder = Recorder()

        val result = bridge(recorder).execute(step("tap"), ByteArray(8), screenW, screenH)

        assertEquals(
            "定位不出来却报成功 —— 上层会把一次盲点当成任务完成",
            StepStatus.FAILED, result.status,
        )
        assertTrue(
            "一个不知道该点哪的智能体，不许往屏幕上点任何一下。实际派发了：${recorder.dispatched}",
            recorder.dispatched.isEmpty(),
        )
    }

    @Test
    fun `定位失败的点击带得出结构化失败码`() {
        val result = bridge(Recorder()).execute(step("tap"), ByteArray(8), screenW, screenH)

        assertEquals(FailureCode.EXEC_EXCEPTION, result.failureCode)
        assertTrue(
            "失败原因要能看出是定位用尽，而不是一句 unknown：${result.failureReason}",
            result.failureReason.orEmpty().isNotBlank(),
        )
    }

    @Test
    fun `点击的落点绝不会是屏幕中心`() {
        // 直接钉住那个具体的坐标：任何一次「悄悄退回猜中心」的改动都会让这条红。
        val recorder = Recorder()

        bridge(recorder).execute(step("tap"), ByteArray(8), screenW, screenH)

        for (action in recorder.dispatched) {
            assertNotEquals(
                "又出现了盲点屏幕中心",
                AccessibilityExecutor.AccessibilityAction.Tap(screenW / 2, screenH / 2),
                action,
            )
        }
    }

    @Test
    fun `滚动可以退到屏幕中心锚点，因为滚动本来就不需要精确坐标`() {
        // 这是与点击相反的一侧：滚动从屏幕中心起手滑是各家框架的默认，
        // 是一个正确的动作，不是一次伪装成功的猜测。
        val recorder = Recorder()

        val result = bridge(recorder).execute(step("scroll", "往下翻"), ByteArray(8), screenW, screenH)

        assertEquals(StepStatus.SUCCESS, result.status)
        assertNull(result.failureCode)
        assertEquals(
            listOf(
                AccessibilityExecutor.AccessibilityAction.Scroll(screenW / 2, screenH / 2, "down")
            ),
            recorder.dispatched,
        )
        assertEquals(
            "锚点置信度要低，好让上层按置信度筛时排在真实定位之后",
            ExecutorBridge.SCROLL_ANCHOR_CONFIDENCE, result.confidence, 1e-6f,
        )
    }

    @Test
    fun `屏幕尺寸未知时连滚动也不许猜`() {
        // 尺寸是 0 就连「中心」都算不出来，此时任何坐标都是编的。
        val recorder = Recorder()

        val result = bridge(recorder).execute(step("scroll", "往下翻"), ByteArray(8), 0, 0)

        assertEquals(StepStatus.FAILED, result.status)
        assertTrue(recorder.dispatched.isEmpty())
    }

    @Test
    fun `树里有匹配元素时，没有模型也照样点得准`() {
        // 这是删掉三级猜测之后**必须仍然成立**的那一半：设备上没有 VLM 权重时，
        // 无障碍树是唯一的定位依据，走的是 tree_rescue，落点是元素的精确 bounds 中心。
        val snapshot = UiStructuredSnapshot(
            packageName = "com.android.settings",
            screenWidth = screenW,
            screenHeight = screenH,
            elements = listOf(
                UiStructuredSnapshot.UiElement(
                    index = 0, text = "设置", contentDescription = "",
                    className = "android.widget.TextView", clickable = true,
                    left = 400, top = 1800, right = 680, bottom = 1900,
                )
            ),
        )
        val recorder = Recorder()

        val result = bridge(recorder, snapshot).execute(step("tap", "设置"), ByteArray(8), screenW, screenH)

        assertEquals(StepStatus.SUCCESS, result.status)
        assertEquals(
            listOf(AccessibilityExecutor.AccessibilityAction.Tap(540, 1850)),
            recorder.dispatched,
        )
    }
}
