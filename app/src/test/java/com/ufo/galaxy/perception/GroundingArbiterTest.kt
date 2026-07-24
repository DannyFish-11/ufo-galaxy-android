package com.ufo.galaxy.perception

import com.ufo.galaxy.inference.LocalGroundingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双通道定位仲裁器的裁决矩阵测试:一致加信 / 树纠偏 / 树救场 / 视觉透传 /
 * 无树退化。全部纯 JVM。
 */
class GroundingArbiterTest {

    private fun snapshot(vararg els: UiStructuredSnapshot.UiElement) = UiStructuredSnapshot(
        packageName = "com.example",
        screenWidth = 1080,
        screenHeight = 2340,
        elements = els.toList()
    )

    private fun element(
        index: Int,
        text: String,
        l: Int, t: Int, r: Int, b: Int,
        clickable: Boolean = true
    ) = UiStructuredSnapshot.UiElement(
        index = index, text = text, contentDescription = "",
        className = "android.widget.Button", clickable = clickable,
        left = l, top = t, right = r, bottom = b
    )

    private fun vlmResult(x: Int, y: Int, conf: Float = 0.6f, error: String? = null) =
        LocalGroundingService.GroundingResult(
            x = x, y = y, confidence = conf, element_description = "", error = error
        )

    @Test
    fun `agreement - vlm point inside matching element keeps vlm point and boosts confidence`() {
        val snap = snapshot(element(0, "Login", 400, 1100, 680, 1240))
        val fused = GroundingArbiter.fuse("tap the Login button", vlmResult(540, 1170, conf = 0.3f), snap)
        assertEquals(GroundingArbiter.SOURCE_AGREEMENT, fused.source)
        assertEquals(540, fused.result.x)
        assertEquals(1170, fused.result.y)
        assertTrue("一致时置信度应取两路较大值", fused.result.confidence >= 0.3f)
        assertNull(fused.result.error)
    }

    @Test
    fun `tree_override - strong matching element elsewhere overrides stray vlm point`() {
        // 视觉点(100,100)不落在任何元素;树上有与意图强匹配的 Login 按钮。
        val snap = snapshot(element(0, "Login", 400, 1100, 680, 1240))
        val fused = GroundingArbiter.fuse("Login", vlmResult(100, 100), snap)
        assertEquals(GroundingArbiter.SOURCE_TREE_OVERRIDE, fused.source)
        assertEquals(540, fused.result.x)
        assertEquals(1170, fused.result.y)
        assertNull(fused.result.error)
    }

    @Test
    fun `tree_rescue - vlm failure recovered by confident tree candidate`() {
        val snap = snapshot(element(0, "设置", 900, 100, 1060, 200))
        val fused = GroundingArbiter.fuse(
            "点击设置",
            vlmResult(0, 0, conf = 0f, error = "VLM grounding failed: timeout"),
            snap
        )
        assertEquals(GroundingArbiter.SOURCE_TREE_RESCUE, fused.source)
        assertEquals(980, fused.result.x)
        assertEquals(150, fused.result.y)
        assertNull("救场结果不应带 error", fused.result.error)
    }

    @Test
    fun `vlm failure without tree candidate passes through untouched`() {
        val snap = snapshot(element(0, "完全无关的文本", 0, 0, 100, 100, clickable = false))
        val original = vlmResult(0, 0, conf = 0f, error = "VLM grounding failed: HTTP 500")
        val fused = GroundingArbiter.fuse("open the payments tab", original, snap)
        assertEquals(GroundingArbiter.SOURCE_VLM_FAILED, fused.source)
        assertEquals("原失败结果必须原样透传给下游梯子", original, fused.result)
    }

    @Test
    fun `vlm_only - weak tree evidence defers to vision`() {
        // 树候选匹配分低于 override 阈值 → 尊重视觉坐标。
        val snap = snapshot(element(0, "Log", 400, 1100, 680, 1240))
        val fused = GroundingArbiter.fuse(
            "tap the big red subscribe button", vlmResult(200, 900), snap
        )
        assertEquals(GroundingArbiter.SOURCE_VLM_ONLY, fused.source)
        assertEquals(200, fused.result.x)
        assertEquals(900, fused.result.y)
    }

    @Test
    fun `null snapshot degrades to pure vision passthrough`() {
        val original = vlmResult(333, 444)
        val fused = GroundingArbiter.fuse("anything", original, null)
        assertEquals(GroundingArbiter.SOURCE_VLM_ONLY, fused.source)
        assertEquals(original, fused.result)
    }

    @Test
    fun `rescue coordinates are clamped into screen bounds`() {
        // 元素中心越界(树 bounds 异常)时救场坐标必须钳位到屏幕内。
        val snap = snapshot(element(0, "确认", 1000, 2300, 1300, 2500))
        val fused = GroundingArbiter.fuse(
            "点确认", vlmResult(0, 0, error = "boom"), snap
        )
        assertEquals(GroundingArbiter.SOURCE_TREE_RESCUE, fused.source)
        assertTrue(fused.result.x <= 1079)
        assertTrue(fused.result.y <= 2339)
    }
}
