package com.ufo.galaxy.loop

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.local.FakeGroundingService
import com.ufo.galaxy.local.FakePlannerService
import com.ufo.galaxy.local.FakeScreenshotProvider
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.noNetworkModelDownloader
import com.ufo.galaxy.perception.UiSnapshotProvider
import com.ufo.galaxy.perception.UiStructuredSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **没有视觉模型、也截不到图，这条闭环照样要能跑。**
 *
 * 这不是一个边角场景，是三种**很常见**的真实状态：
 *
 *  1. 设备是 API 26~29 —— `AccessibilityService.takeScreenshot` 是 API 30 才引入的，
 *     而本模块 `minSdk` 是 26，且本应用不申请 MediaProjection，没有替代路径；
 *  2. 当前是 `FLAG_SECURE` 窗口（银行、密码页、部分聊天应用）—— 截图必然失败；
 *  3. 撞上平台的 333ms 截图节流。
 *
 * 再叠上「llama-server 二进制没随 APK 发 / 权重还没下完」——那是装完 APK 的默认状态——
 * 就得到一台**没有模型、也没有截图**的设备。
 *
 * 此前这三种情况下整条会话直接以 `screenshot_failed` 终止，哪怕无障碍树完全读得到、
 * 定位与执行都不需要那张图。也就是说：装上 APK、开好无障碍，在一台 Android 9 上，
 * 或者在任何一个银行页面上，它一步都跑不了 —— 而失败原因只是"截图失败"。
 *
 * 现在截图只是**两条感知通道之一**：
 *
 * | 通道 | 用途 | 拿不到时 |
 * |---|---|---|
 * | 截图 | 喂视觉模型 | 跳过两级视觉，走树 |
 * | 无障碍树 | 定位（精确 bounds）+ 变化检测（指纹） | 与截图都没有才终止 |
 */
class RunsWithoutScreenshotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Recorder : AccessibilityExecutor {
        val dispatched = mutableListOf<AccessibilityExecutor.AccessibilityAction>()
        override fun execute(action: AccessibilityExecutor.AccessibilityAction): Boolean {
            dispatched += action
            return true
        }
    }

    /** 截图永远失败 —— API < 30 / FLAG_SECURE 窗口在真机上就是这个形态。 */
    private class NoScreenshots : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg(): ByteArray =
            throw IllegalStateException("截图失败（reason=unsupported_sdk）")
        override fun screenWidth(): Int = 1080
        override fun screenHeight(): Int = 2400
    }

    private fun tree(vararg labels: Pair<String, Int>) = UiStructuredSnapshot(
        packageName = "com.android.settings",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = labels.mapIndexed { i, (label, top) ->
            UiStructuredSnapshot.UiElement(
                index = i, text = label, contentDescription = "",
                className = "android.widget.TextView", clickable = true,
                left = 400, top = top, right = 680, bottom = top + 100,
            )
        },
    )

    private fun runLoop(
        snapshotProvider: UiSnapshotProvider?,
        executor: AccessibilityExecutor,
        screenshots: EdgeExecutor.ScreenshotProvider = NoScreenshots(),
        intent: String = "设置",
    ): LoopResult {
        val modelsDir = tmp.newFolder()
        val controller = LoopController(
            localPlanner = LocalPlanner(FakePlannerService.singleStep("tap", intent)),
            executorBridge = ExecutorBridge(
                // 没有视觉模型 —— 装完 APK 的默认状态。
                groundingService = FakeGroundingService.notLoaded(),
                accessibilityExecutor = executor,
                imageScaler = NoOpImageScaler(),
                uiSnapshotProvider = snapshotProvider,
            ),
            screenshotProvider = screenshots,
            modelAssetManager = ModelAssetManager(modelsDir),
            modelDownloader = noNetworkModelDownloader(modelsDir),
            uiSnapshotProvider = snapshotProvider,
        )
        return runBlocking { controller.execute("打开设置") }
    }

    @Test
    fun `没有模型也没有截图，但树读得到，任务照样做完`() {
        val recorder = Recorder()

        val result = runLoop(
            snapshotProvider = { tree("设置" to 1800) },
            executor = recorder,
        )

        assertEquals(
            "这正是装完 APK、在一台 Android 9 上（或任何银行页面上）的形态 —— " +
                "此前它一步都跑不了，失败原因只写着「截图失败」",
            LoopController.STATUS_SUCCESS, result.status,
        )
        assertEquals(
            "落点应当是元素 bounds 的精确中心",
            listOf(AccessibilityExecutor.AccessibilityAction.Tap(540, 1850)),
            recorder.dispatched,
        )
    }

    @Test
    fun `两条感知通道都没有时，才终止`() {
        // 截图没有、树也没有 = 真的看不见屏幕。这时候必须停 ——
        // 让它接着跑就是让它闭着眼睛点。
        val recorder = Recorder()

        val result = runLoop(snapshotProvider = null, executor = recorder)

        assertEquals(LoopController.STATUS_FAILED, result.status)
        assertEquals(LoopController.STOP_SCREENSHOT_FAILED, result.stopReason)
        assertTrue("看不见屏幕却还派发了动作", recorder.dispatched.isEmpty())
    }

    @Test
    fun `截图失败的具体原因要出现在错误信息里`() {
        // 「API 太低」和「这一屏是 FLAG_SECURE」处置完全不同，日志里必须分得出来。
        val result = runLoop(snapshotProvider = null, executor = Recorder())

        assertTrue(
            "错误信息里没有截图失败的具体原因：${result.error}",
            result.error.orEmpty().contains("unsupported_sdk"),
        )
    }

    @Test
    fun `没有截图时，变化检测靠树指纹`() {
        // 没有像素就没法比字节。若树指纹这条路没接上，uiChanged 会一路是 null，
        // 停滞检测在这些设备上就等于关掉了 —— 智能体会一直重复同一个无效动作。
        val a = tree("设置" to 1800)
        val b = tree("设置" to 1800, "关于本机" to 1950)

        assertNotEquals(
            "两屏元素数量不同，指纹却一样 —— 那就检测不出任何变化",
            a.signature(), b.signature(),
        )
        assertEquals("同一屏的指纹必须稳定", a.signature(), tree("设置" to 1800).signature())
    }

    @Test
    fun `树指纹对位置变化也敏感`() {
        // 列表滚动时标签可能一模一样，位置变了才是「翻页了」的证据。
        // 指纹只看标签的话，滚动会被判成「界面没变」。
        val before = tree("第一项" to 800, "第二项" to 950)
        val afterScroll = tree("第一项" to 500, "第二项" to 650)

        assertNotEquals(
            "只滚动、标签不变时指纹没变 —— 翻页会被判成没有进展",
            before.signature(), afterScroll.signature(),
        )
    }
}
