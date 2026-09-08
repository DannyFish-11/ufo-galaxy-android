package com.ufo.galaxy.local

import com.ufo.galaxy.agent.ImageScaler
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.perception.UiStructuredSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **让模型选编号，别让它数像素。**
 *
 * 模型直接吐 (x, y)，是把两件事压进一次生成：认出这是哪个控件，以及这个控件在屏幕上
 * 的第几个像素。第二件事它做不好 —— 送进去的图被缩放过，坐标还要换算回全分辨率，
 * 小屏上差十几像素就点到隔壁那一项。成熟的手机 GUI agent 框架走的都是另一条路：
 * 给模型一份带编号的元素清单，让它选一个编号，坐标由无障碍节点自己的 bounds 给出。
 *
 * 这条路有两个性质是像素路径拿不到的：
 *
 *  1. **坐标是精确的** —— 来自节点 bounds，中间没有任何缩放换算；
 *  2. **答案可以被校验** —— 编号要么在清单里、要么不在。一个编出来的像素坐标则
 *     永远"看起来是个合法答案"，无从判断真假。
 *
 * 本文件钉的就是这两条，外加编号越界时必须**如实退回**而不是硬取一个元素。
 */
class ElementIndexGroundingTest {

    private val screenW = 1080
    private val screenH = 2400

    /** 真的按 maxEdge 缩放：用 NoOp 会让"坐标没经过换算"这条性质测不出来。 */
    private class ScalingFake : ImageScaler {
        override fun scaleToMaxEdge(
            jpegBytes: ByteArray,
            fullWidth: Int,
            fullHeight: Int,
            maxEdge: Int
        ): ImageScaler.ScaledResult {
            val longest = maxOf(fullWidth, fullHeight)
            val s = if (longest <= maxEdge) 1.0 else maxEdge.toDouble() / longest
            return ImageScaler.ScaledResult("b64", (fullWidth * s).toInt(), (fullHeight * s).toInt())
        }
    }

    private class Grounder(
        private val produce: () -> LocalGroundingService.GroundingResult
    ) : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(
            intent: String,
            screenshotBase64: String,
            width: Int,
            height: Int
        ) = produce()
    }

    private val snapshot = UiStructuredSnapshot(
        packageName = "com.example",
        screenWidth = screenW,
        screenHeight = screenH,
        elements = listOf(
            UiStructuredSnapshot.UiElement(
                index = 0, text = "取消", contentDescription = "",
                className = "android.widget.Button", clickable = true,
                left = 100, top = 2000, right = 400, bottom = 2100,
            ),
            UiStructuredSnapshot.UiElement(
                index = 1, text = "确定", contentDescription = "",
                className = "android.widget.Button", clickable = true,
                left = 680, top = 2000, right = 980, bottom = 2100,
            ),
        ),
    )

    private fun ladder(result: LocalGroundingService.GroundingResult) =
        GroundingFallbackLadder(
            groundingService = Grounder { result },
            imageScaler = ScalingFake(),
            primaryMaxEdge = 720,
            resizedMaxEdge = 360,
        )

    private fun ground(
        result: LocalGroundingService.GroundingResult,
        snap: UiStructuredSnapshot? = snapshot,
    ) = ladder(result).ground(
        sessionId = "s", stepId = "step_1", intent = "确定",
        jpegBytes = ByteArray(4), screenWidth = screenW, screenHeight = screenH,
        uiSnapshot = snap,
    )

    private fun byIndex(i: Int, confidence: Float = 0.9f) =
        LocalGroundingService.GroundingResult(
            x = 0, y = 0, confidence = confidence,
            element_description = "确定", elementIndex = i,
        )

    @Test
    fun `编号被解析成该元素 bounds 的精确中心`() {
        val r = ground(byIndex(1))

        assertTrue(r.succeeded)
        assertEquals("x 应是 (680+980)/2", 830, r.x)
        assertEquals("y 应是 (2000+2100)/2", 2050, r.y)
    }

    @Test
    fun `编号路径不经过任何缩放换算`() {
        // 像素路径上，模型看到的是 324×720 的缩放图，坐标必须 remap 回全分辨率；
        // 编号路径的坐标从来没进过缩放空间，所以必须**逐像素**等于 bounds 中心 ——
        // 一旦有人给它顺手加了一次 remap，这条立刻红。
        val r = ground(byIndex(0))

        assertEquals(250, r.x)
        assertEquals(2050, r.y)
    }

    @Test
    fun `stageUsed 能看出坐标是编号给的`() {
        val r = ground(byIndex(1))

        assertTrue(
            "真机日志要能直接分辨坐标来源，实际 ${r.stageUsed}",
            r.stageUsed.endsWith(GroundingFallbackLadder.SOURCE_ELEMENT_INDEX),
        )
    }

    @Test
    fun `编号越界不许硬凑一个元素`() {
        // 模型编了个不存在的编号。这时唯一正确的处置是当作"这一级没结果"，
        // 而不是取第一个 / 最后一个 / 取模。编号可校验，正是它比像素强的地方。
        //
        // 注意钉的是**坐标来源**而不是坐标值：后续的树救场完全可能凭意图文本
        // 独立找到同一个元素、给出同一个坐标 —— 那是对的，不该被这条判红。
        val r = ground(byIndex(99))

        assertFalse(
            "越界编号被当成了一次编号命中，实际 ${r.stageUsed}",
            r.stageUsed.endsWith(GroundingFallbackLadder.SOURCE_ELEMENT_INDEX),
        )
    }

    @Test
    fun `解析不出来的编号不会把 0,0 当成可信坐标送进裁决`() {
        // 引擎在走编号路径时把 x/y 填成 0。编号解析不了却照常往下走，
        // 就等于拿着模型给编号时的那个高置信度，把屏幕左上角报成一次可信定位。
        val r = ground(byIndex(99, confidence = 0.95f))

        assertNotEquals("屏幕左上角被当成了定位结果", 0, r.x)
        assertNotEquals(0, r.y)
    }

    @Test
    fun `负数编号按没给处理`() {
        val r = ground(
            LocalGroundingService.GroundingResult(
                x = 100, y = 200, confidence = 0.8f,
                element_description = "", elementIndex = -1,
            )
        )
        // 应当走像素路径：(100,200) 在 324×720 空间里，换算回全分辨率会明显变大。
        assertTrue("负编号应被忽略并退回像素路径：(${r.x},${r.y})", r.x > 100 && r.y > 200)
    }

    @Test
    fun `没有清单却回了编号，就是没有结果`() {
        // 没注入清单时 prompt 不会提供编号这条形式，模型仍回编号说明它在编。
        // 引擎在编号形式下把 x/y 填成 0，所以这里没有任何可用坐标 ——
        // 唯一诚实的处置是这一级判失败，而不是把 (0,0) 报成一次定位。
        val r = ground(
            LocalGroundingService.GroundingResult(
                x = 0, y = 0, confidence = 0.9f,
                element_description = "", elementIndex = 1,
            ),
            snap = null,
        )

        assertFalse("没有清单可校验编号时，不许编一个坐标出来", r.succeeded)
        assertEquals(GroundingFallbackLadder.STAGE_NO_MATCH, r.stageUsed)
    }

    @Test
    fun `带 error 的结果即使有编号也不采信`() {
        val r = ground(
            LocalGroundingService.GroundingResult(
                x = 0, y = 0, confidence = 0.9f, element_description = "",
                error = "timeout", elementIndex = 1,
            )
        )

        assertFalse(
            "一次失败的推理里顺带回来的编号不能当成编号命中，实际 ${r.stageUsed}",
            r.stageUsed.endsWith(GroundingFallbackLadder.SOURCE_ELEMENT_INDEX),
        )
    }
}
