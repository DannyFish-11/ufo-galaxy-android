package com.ufo.galaxy.local

import com.ufo.galaxy.agent.ImageScaler
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.perception.GroundingArbiter
import com.ufo.galaxy.perception.UiStructuredSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定位梯子：**手里攥着精确 bounds，就不许去点屏幕中心**。
 *
 * 起因是一处真实缺陷：无障碍树快照此前只被注入两级视觉的 prompt，然后就被丢掉了 ——
 * 梯子里一次都没有调用 [GroundingArbiter]。于是视觉失手时，梯子明明手里有带精确
 * bounds 的树候选，却直接跌到第 4/5 级去**点屏幕中心**（那一级名字叫
 * `accessibility_node`，实现里根本不碰快照）。
 *
 * 同一份快照在 [com.ufo.galaxy.agent.EdgeExecutor] 那条路径上是全程参与裁决的 ——
 * 两条执行路径对同一份证据的处置不一致，而这条（`InputRouter` → `LocalLoopExecutor`
 * → `LoopController` → `ExecutorBridge`）正是跨设备关闭时的本地主路。
 *
 * 这类缺陷不会让任何行为测试变红：梯子照样"成功"返回坐标，只是点错地方。
 */
class GroundingLadderArbitratesTheTreeTest {

    // 1080×2400 竖屏；主级压到长边 720 → 324×720。
    private val screenW = 1080
    private val screenH = 2400

    /** 按 maxEdge 真的缩放 —— 用 NoOp 会让 remap 换算悄悄变成恒等，测不出坐标空间问题。 */
    private class ScalingFake : ImageScaler {
        override fun scaleToMaxEdge(
            jpegBytes: ByteArray,
            fullWidth: Int,
            fullHeight: Int,
            maxEdge: Int
        ): ImageScaler.ScaledResult {
            val longest = maxOf(fullWidth, fullHeight)
            val s = if (longest <= maxEdge) 1.0 else maxEdge.toDouble() / longest
            return ImageScaler.ScaledResult("scaled-b64", (fullWidth * s).toInt(), (fullHeight * s).toInt())
        }
    }

    private class Grounder(
        private val loaded: Boolean = true,
        private val produce: () -> LocalGroundingService.GroundingResult
    ) : LocalGroundingService {
        var groundCalls = 0
        var lastStructuredContext: String? = null
        override fun loadModel() = loaded
        override fun unloadModel() {}
        override fun isModelLoaded() = loaded
        override fun ground(
            intent: String,
            screenshotBase64: String,
            width: Int,
            height: Int
        ): LocalGroundingService.GroundingResult {
            groundCalls++
            return produce()
        }
        override fun ground(
            intent: String,
            screenshotBase64: String,
            width: Int,
            height: Int,
            structuredContext: String?
        ): LocalGroundingService.GroundingResult {
            lastStructuredContext = structuredContext
            return ground(intent, screenshotBase64, width, height)
        }
    }

    /** "Log in" 按钮的精确中心是 (540, 1850) —— 与屏幕中心 (540, 1200) 明显不同。 */
    private fun snapshot() = UiStructuredSnapshot(
        packageName = "com.example",
        screenWidth = screenW,
        screenHeight = screenH,
        elements = listOf(
            UiStructuredSnapshot.UiElement(
                index = 0, text = "Log in", contentDescription = "",
                className = "android.widget.Button", clickable = true,
                left = 400, top = 1800, right = 680, bottom = 1900
            ),
            UiStructuredSnapshot.UiElement(
                index = 1, text = "Forgot password", contentDescription = "",
                className = "android.widget.TextView", clickable = true,
                left = 300, top = 2000, right = 780, bottom = 2060
            )
        )
    )

    private fun ladder(g: LocalGroundingService) = GroundingFallbackLadder(g, ScalingFake())

    private fun run(
        g: LocalGroundingService,
        intent: String = "Log in",
        snap: UiStructuredSnapshot? = snapshot()
    ) = ladder(g).ground("session", "1", intent, ByteArray(4), screenW, screenH, snap)

    private val ok = { LocalGroundingService.GroundingResult(100, 200, 0.9f, "btn") }
    private val failed = { LocalGroundingService.GroundingResult(0, 0, 0f, "", error = "engine down") }
    private val weak = { LocalGroundingService.GroundingResult(500, 1850, 0.05f, "?") }

    @Test
    fun `vision failure is rescued by the tree instead of tapping the screen centre`() {
        val r = run(Grounder(produce = failed))
        assertTrue("必须仍然产出坐标", r.succeeded)
        assertTrue(
            "视觉失败时应由树救场，实际 stage=${r.stageUsed}",
            r.stageUsed.endsWith(GroundingArbiter.SOURCE_TREE_RESCUE),
        )
        assertEquals("应采用元素的精确 bounds 中心 x", 540, r.x)
        assertEquals("应采用元素的精确 bounds 中心 y", 1850, r.y)
        assertFalse(
            "又跌回『点屏幕中心』的启发式了 —— 那正是这次修复要消灭的形状",
            r.stageUsed == GroundingFallbackLadder.STAGE_OCR_TEXT ||
                r.stageUsed == GroundingFallbackLadder.STAGE_ACCESSIBILITY_NODE ||
                r.stageUsed == GroundingFallbackLadder.STAGE_HEURISTIC_REGION,
        )
    }

    @Test
    fun `a below-gate coordinate is treated as no result, not as a weak coordinate`() {
        // 置信度 0.05 的坐标是噪声。若把它当作有效坐标交给裁决器，它有概率落进某个
        // 不相干的元素里被判成 agreement，反而把噪声"洗成"高置信度结果。
        val r = run(Grounder(produce = weak))
        assertEquals(540, r.x)
        assertEquals(1850, r.y)
        assertTrue("应走树救场，实际 ${r.stageUsed}", r.stageUsed.endsWith(GroundingArbiter.SOURCE_TREE_RESCUE))
    }

    @Test
    fun `vision landing inside the matching element is an agreement, and confidence is lifted`() {
        // 全分辨率 (540,1850) 在 324×720 缩放空间里是 (162,555)。
        val r = run(Grounder(produce = { LocalGroundingService.GroundingResult(162, 555, 0.4f, "btn") }))
        assertTrue("双证据一致应判 agreement，实际 ${r.stageUsed}",
            r.stageUsed.endsWith(GroundingArbiter.SOURCE_AGREEMENT))
        assertTrue("置信度应取两者较大，实际 ${r.confidence}", r.confidence >= 0.4f)
    }

    @Test
    fun `the dedicated tree-rescue stage is the only entry when both vision stages are skipped`() {
        // 定位模型没加载 → 两级视觉被整个跳过，裁决器根本没被调用过。
        // 没有这一级，这里会带着满手的精确 bounds 跌到"点屏幕中心"。
        val g = Grounder(loaded = false, produce = ok)
        val r = run(g)
        assertEquals("模型未加载时不得调用 ground()", 0, g.groundCalls)
        assertEquals(
            "两级视觉被跳过时，专门的树救场级必须接住",
            GroundingFallbackLadder.STAGE_TREE_RESCUE, r.stageUsed,
        )
        assertEquals(540, r.x)
        assertEquals(1850, r.y)
    }

    @Test
    fun `without a tree the ladder behaves exactly as before`() {
        // 无结构化通道时必须一字不差地退回旧行为，否则会打断既有本地闭环。
        val success = run(Grounder(produce = ok), snap = null)
        assertTrue(success.succeeded)
        assertTrue("纯视觉应判 vlm_only，实际 ${success.stageUsed}",
            success.stageUsed.endsWith(GroundingArbiter.SOURCE_VLM_ONLY))
        // remap 必须真的发生：模型看到的是 324×720，(100,200) 换算回全分辨率应明显变大。
        assertTrue("坐标没有换算回全分辨率：(${success.x},${success.y})",
            success.x > 100 && success.y > 200)

        val fallback = run(Grounder(produce = failed), intent = "tap ok", snap = null)
        assertTrue("无树时仍须有兜底坐标", fallback.succeeded)
    }

    @Test
    fun `the element list injected into the prompt is in the scaled image space`() {
        val g = Grounder(produce = ok)
        run(g)
        assertNotNull("元素清单没有注入 prompt —— 结构化通道又断了", g.lastStructuredContext)
        val ctx = g.lastStructuredContext!!
        assertTrue("元素清单为空", ctx.isNotBlank())
        assertFalse(
            "prompt 里出现了全分辨率坐标（1850），与模型实际看到的缩放图对不上：\n$ctx",
            ctx.contains("1850"),
        )
    }
}
