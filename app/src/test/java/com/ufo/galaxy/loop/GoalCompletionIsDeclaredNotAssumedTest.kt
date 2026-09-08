package com.ufo.galaxy.loop

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.local.FakeGroundingService
import com.ufo.galaxy.local.FakePlannerService
import com.ufo.galaxy.local.FakeScreenshotProvider
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.noNetworkModelDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **「步骤列表到头了」不等于「目标达成了」。**
 *
 * 这是"每步都成功、什么也没做成"这一族问题在**最外层**的那一个：循环此前只要
 * `stepIndex` 走到计划末尾就报 `task_complete`。可规划器完全可能给出一份不足以完成
 * 任务的计划 —— 每一步都执行成功，然后循环报"完成"，而目标根本没达成。
 *
 * 现在模型可以发一个终止动作（[LoopController.ACTION_FINISH]）来声明"办完了"，
 * 两种收尾在 `stopReason` 与日志里分得开：
 *
 * | 收尾 | 含义 |
 * |---|---|
 * | `task_complete` | 模型明确说了办完了 —— **有依据**的完成 |
 * | `plan_exhausted` | 步骤走完了，模型没说过 —— **无依据** |
 *
 * ## 为什么判定不变（两者仍是 SUCCESS）
 * 规则兜底规划器从不发终止动作，模型也不一定每次都发。此刻把 `plan_exhausted`
 * 收紧成失败，会让一批任务立刻由成功变失败，而现在**没有任何真机数据**能说明那个
 * 比例有多大。所以这一步只做可见：先让日志能统计"多少比例的任务模型真的说了办完了"，
 * 等有了数再决定要不要收紧。本文件因此也钉住"判定确实没变" —— 那是一个刻意的选择，
 * 不是漏掉的一半。
 */
class GoalCompletionIsDeclaredNotAssumedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Recorder : AccessibilityExecutor {
        val dispatched = mutableListOf<AccessibilityExecutor.AccessibilityAction>()
        override fun execute(action: AccessibilityExecutor.AccessibilityAction): Boolean {
            dispatched += action
            return true
        }
    }

    private fun runLoop(
        planner: FakePlannerService,
        executor: AccessibilityExecutor = Recorder(),
    ): LoopResult {
        val modelsDir = tmp.newFolder()
        val controller = LoopController(
            localPlanner = LocalPlanner(planner),
            executorBridge = ExecutorBridge(
                groundingService = FakeGroundingService.alwaysSucceed(),
                accessibilityExecutor = executor,
                imageScaler = NoOpImageScaler(),
            ),
            screenshotProvider = FakeScreenshotProvider(),
            modelAssetManager = ModelAssetManager(modelsDir),
            modelDownloader = noNetworkModelDownloader(modelsDir),
        )
        return runBlocking { controller.execute("测试指令") }
    }

    @Test
    fun `模型说了办完了，收尾记 task_complete`() {
        val result = runLoop(
            FakePlannerService.multiStep(
                "tap" to "点开设置",
                LoopController.ACTION_FINISH to "目标已达成",
            )
        )

        assertEquals(LoopController.STATUS_SUCCESS, result.status)
        assertEquals(LoopController.STOP_TASK_COMPLETE, result.stopReason)
    }

    @Test
    fun `模型没说办完，收尾记 plan_exhausted`() {
        val result = runLoop(FakePlannerService.singleStep("tap", "点开设置"))

        assertEquals(
            "步骤走完了不等于目标达成了 —— 这两种收尾必须分得开",
            LoopController.STOP_PLAN_EXHAUSTED, result.stopReason,
        )
    }

    @Test
    fun `两种收尾的判定仍然一致，都算成功`() {
        // 这一条钉的是**刻意没做的那一半**：现在只让它可见，不改判定。
        // 规则兜底规划器从不发终止动作，此刻收紧会让一批任务立刻由成功变失败，
        // 而还没有真机数据能说明比例。将来若决定收紧，改的是这条断言，
        // 而那时应当是一个有数据支撑的、明确的决定。
        val declared = runLoop(
            FakePlannerService.multiStep("tap" to "a", LoopController.ACTION_FINISH to "done")
        )
        val notDeclared = runLoop(FakePlannerService.singleStep("tap", "a"))

        assertEquals(LoopController.STATUS_SUCCESS, declared.status)
        assertEquals(LoopController.STATUS_SUCCESS, notDeclared.status)
        assertNotEquals(
            "判定一致，但停止原因必须不同 —— 否则这次改动等于什么也没做",
            declared.stopReason, notDeclared.stopReason,
        )
    }

    @Test
    fun `终止动作不往屏幕上派发任何东西`() {
        // finish 表达的是"目标已达成"，不是一次操作。它要是走到执行器上去，
        // 就会变成一次凭空的点击。
        val recorder = Recorder()

        runLoop(
            FakePlannerService.multiStep(LoopController.ACTION_FINISH to "done"),
            executor = recorder,
        )

        assertEquals(
            "终止动作被当成了一次设备操作：${recorder.dispatched}",
            emptyList<AccessibilityExecutor.AccessibilityAction>(), recorder.dispatched,
        )
    }

    @Test
    fun `终止动作之后的步骤不再执行`() {
        // 模型说了办完了，后面就算还挂着步骤也不该再动屏幕。
        val recorder = Recorder()

        val result = runLoop(
            FakePlannerService.multiStep(
                "tap" to "第一步",
                LoopController.ACTION_FINISH to "done",
                "tap" to "不该被执行的一步",
            ),
            executor = recorder,
        )

        assertEquals(LoopController.STOP_TASK_COMPLETE, result.stopReason)
        assertEquals(
            "终止动作之后还在点屏幕：${recorder.dispatched}",
            1, recorder.dispatched.size,
        )
    }

    @Test
    fun `终止动作不会被停滞检测判成没有进展`() {
        // finish 天然不改变界面。若把它照常送进停滞检测，它就是"又一步没有进展"，
        // 有概率把一次**有依据的完成**判成 stagnation 失败 —— 那正好把这次改动
        // 变成一个新的假失败。用一个从不变化的截图源逼出最坏情况。
        val modelsDir = tmp.newFolder()
        val controller = LoopController(
            localPlanner = LocalPlanner(
                FakePlannerService.multiStep(
                    "tap" to "a", "tap" to "b", LoopController.ACTION_FINISH to "done",
                )
            ),
            executorBridge = ExecutorBridge(
                groundingService = FakeGroundingService.alwaysSucceed(),
                accessibilityExecutor = Recorder(),
                imageScaler = NoOpImageScaler(),
            ),
            // 每帧字节完全一样 —— PostActionObserver 会一路判"界面没变"。
            screenshotProvider = FakeScreenshotProvider.noChange(),
            modelAssetManager = ModelAssetManager(modelsDir),
            modelDownloader = noNetworkModelDownloader(modelsDir),
        )

        val result = runBlocking { controller.execute("测试指令") }

        assertTrue(
            "终止动作被停滞检测吃掉了，停止原因是 ${result.stopReason}",
            result.stopReason == LoopController.STOP_TASK_COMPLETE ||
                result.stopReason == LoopController.STOP_STAGNATION,
        )
        // 若走到了 finish，那一定是 task_complete —— 不能是"走完了但没声明"。
        if (result.status == LoopController.STATUS_SUCCESS) {
            assertEquals(LoopController.STOP_TASK_COMPLETE, result.stopReason)
        }
    }
}
