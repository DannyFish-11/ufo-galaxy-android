package com.ufo.galaxy.loop

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.local.FakeAccessibilityExecutor
import com.ufo.galaxy.local.FakeGroundingService
import com.ufo.galaxy.local.FakePlannerService
import com.ufo.galaxy.local.FakeScreenshotProvider
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.noNetworkModelDownloader
import com.ufo.galaxy.perception.UiSnapshotProvider
import com.ufo.galaxy.perception.UiStructuredSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 整条循环**要么真做成，要么如实说没做成** —— 不许有第三种。
 *
 * 这是端到端那一层的守卫，跑的是真的 [LoopController] + 真的 [ExecutorBridge] +
 * 真的 [com.ufo.galaxy.local.GroundingFallbackLadder]，只有最外面的模型、截图、
 * 无障碍执行器是假的。
 *
 * ## 它盯的是哪个形状
 *
 * 定位梯子里曾有三级"兜底"，实现都是**返回屏幕正中心**。于是：
 * 定位失败 → 点屏幕中间 → `dispatchGesture` 派发成功 → 这一步记成 SUCCESS →
 * 循环走完 → 任务报 `task_complete`。**每一步都成功，屏幕上什么也没发生。**
 *
 * 这种缺陷不会让任何行为测试变红，因为那些测试断言的都是"成功了"。
 * 所以这里两条一起钉：
 *
 *  1. 真的定位不出来时，会话必须**失败**，而且一次点击都不许派发出去；
 *  2. 但只要无障碍树里有匹配元素，**没有视觉模型也要能做完** —— 否则上一条就成了
 *     "把功能删干净换个安静"。这一条是前一条的对照组。
 */
class LoopEndsHonestlyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val screenW = 1080
    private val screenH = 2340

    /** 只记录、不真做的执行器；用来断言"到底往屏幕上派发了什么"。 */
    private class Recorder : AccessibilityExecutor {
        val dispatched = mutableListOf<AccessibilityExecutor.AccessibilityAction>()
        override fun execute(action: AccessibilityExecutor.AccessibilityAction): Boolean {
            dispatched += action
            return true // 手势确实派发出去了 —— 这正是「假成功」的来源
        }
    }

    private fun runLoop(
        grounder: com.ufo.galaxy.inference.LocalGroundingService,
        executor: AccessibilityExecutor,
        snapshot: UiStructuredSnapshot? = null,
        intent: String = "点开设置",
    ): LoopResult {
        val modelsDir = tmp.newFolder()
        val controller = LoopController(
            localPlanner = LocalPlanner(FakePlannerService.singleStep("tap", intent)),
            executorBridge = ExecutorBridge(
                groundingService = grounder,
                accessibilityExecutor = executor,
                imageScaler = NoOpImageScaler(),
                uiSnapshotProvider = snapshot?.let { s -> UiSnapshotProvider { s } },
            ),
            screenshotProvider = FakeScreenshotProvider(width = screenW, height = screenH),
            modelAssetManager = ModelAssetManager(modelsDir),
            modelDownloader = noNetworkModelDownloader(modelsDir),
        )
        return runBlocking { controller.execute("测试指令") }
    }

    private val settingsTree = UiStructuredSnapshot(
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

    @Test
    fun `定位不出来时，会话如实失败`() {
        val result = runLoop(
            grounder = FakeGroundingService.alwaysFail("model not loaded"),
            executor = Recorder(),
        )

        assertEquals(
            "定位不出来却报完成 —— 这正是「每步都成功、什么也没做成」的那个形状",
            LoopController.STATUS_FAILED, result.status,
        )
        assertNotNull("失败必须带原因", result.error)
    }

    @Test
    fun `定位不出来时，一次点击都不许派发`() {
        // 这条比上一条更硬：不只是"结论要说失败"，而是**不许往屏幕上戳**。
        // 屏幕中间可能正好是一条列表项、一个删除、一次付款确认。
        val recorder = Recorder()

        runLoop(grounder = FakeGroundingService.alwaysFail("model not loaded"), executor = recorder)

        assertEquals(
            "一个不知道该点哪的智能体往屏幕上派发了动作：${recorder.dispatched}",
            emptyList<AccessibilityExecutor.AccessibilityAction>(), recorder.dispatched,
        )
    }

    @Test
    fun `定位不出来时，落点绝不会是屏幕中心`() {
        // 直接钉住那个具体坐标：任何一次「悄悄把猜中心加回来」的改动都会让这条红。
        val recorder = Recorder()

        runLoop(grounder = FakeGroundingService.alwaysFail("x"), executor = recorder)

        assertTrue(
            "又出现了盲点屏幕中心：${recorder.dispatched}",
            recorder.dispatched.none {
                it == AccessibilityExecutor.AccessibilityAction.Tap(screenW / 2, screenH / 2)
            },
        )
    }

    @Test
    fun `没有视觉模型，但树里有这个元素时，任务照样做完`() {
        // 上面三条的对照组。缺了它，"定位失败就报失败"可以靠把功能删干净来满足。
        // 真机上这是最常见的一种状态：权重还没下完、llama-server 还没起来。
        val recorder = Recorder()

        val result = runLoop(
            grounder = FakeGroundingService.alwaysFail("model not loaded"),
            executor = recorder,
            snapshot = settingsTree,
            intent = "设置",
        )

        assertEquals(
            "手里攥着精确 bounds 却没做完 —— 没有模型的设备上这是唯一的定位路径",
            LoopController.STATUS_SUCCESS, result.status,
        )
        assertEquals(
            "坐标应当是元素 bounds 的精确中心，而不是任何猜测",
            listOf(AccessibilityExecutor.AccessibilityAction.Tap(540, 1850)),
            recorder.dispatched,
        )
    }

    @Test
    fun `执行器返回 false 时不算做成`() {
        // 另一半：坐标对了，但动作本身没做成（手势被系统取消就是这个形态）。
        val result = runLoop(
            grounder = FakeGroundingService.alwaysSucceed(),
            executor = FakeAccessibilityExecutor.alwaysFail(),
        )

        assertEquals(LoopController.STATUS_FAILED, result.status)
    }
}
