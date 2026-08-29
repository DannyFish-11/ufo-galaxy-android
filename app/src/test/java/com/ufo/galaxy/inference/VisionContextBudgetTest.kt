package com.ufo.galaxy.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规划送图的预算：**这个数必须真的算得出来，而且真的被执行**。
 *
 * 起因是一处真实缺陷：规划步把全分辨率截图的 base64 直接送进模型，而服务端 `-c` 恒 4096。
 * 一张 1080×2400 的原图约 3300 个视觉 token，加上元素清单与 512 的生成预留就没有余量了；
 * 而全链路没有任何一处数过、卡过、截过这个数 —— 溢出时 llama.cpp 静默截断 prompt
 * （截掉的往往正是尾部的指令），表现为"模型忽然不听话"而不是一个明确的失败。
 *
 * 所以这里钉的不是"某个具体的长边等于多少"，而是三件事：
 *  1. 全分辨率原图在 4096 窗口下**装不下** —— 缺陷本身可复现；
 *  2. 算出来的长边**真的装得下**（闭式解 + 向下校正必须自洽）；
 *  3. 预算随文本开销变化 —— 元素清单长的界面会自动让图变小，而不是一个固定常数。
 */
class VisionContextBudgetTest {

    private val ctx = LlamaServerController.DEFAULT_CONTEXT_SIZE
    private val generation = 512

    // 典型 1080p 手机竖屏。
    private val fullW = 1080
    private val fullH = 2400

    @Test
    fun `the defect is reproducible - a full resolution screenshot leaves no room for the prompt`() {
        val budget = VisionContextBudget.visionTokenBudget(ctx, generation, textTokens = 0)
        assertEquals("视觉预算 = 4096 − 512 生成 − 128 余量", 3456, budget)

        // 1080p：塞得进，但余量小到任何真实的元素清单都会把它顶穿。
        val tokens1080 = VisionContextBudget.visionTokensFor(fullW, fullH)
        assertEquals("1080×2400 原图的视觉 token", 3230, tokens1080)
        assertTrue(
            "1080p 原图送进去后只剩 ${budget - tokens1080} 个 token 给目标与元素清单 —— " +
                "这不是『必然溢出』，是余量小到一份真实的元素清单就能顶穿",
            budget - tokens1080 < 300,
        )

        // 1440p：无条件溢出。
        val tokens1440 = VisionContextBudget.visionTokensFor(1440, 3200)
        assertEquals("1440×3200 原图的视觉 token", 5814, tokens1440)
        assertTrue(
            "1440p 原图($tokens1440)装不进预算($budget) —— 这正是修复前每次规划都在做的事",
            tokens1440 > budget,
        )

        // 修复后：长边封顶 1536，给文本留出两千多。
        val capped = VisionContextBudget.tokensAtEdge(fullW, fullH, VisionContextBudget.ABSOLUTE_MAX_EDGE)
        assertTrue(
            "压到长边 ${VisionContextBudget.ABSOLUTE_MAX_EDGE} 后应给文本留出千级余量，实际剩 ${budget - capped}",
            budget - capped > 1500,
        )
    }

    @Test
    fun `the derived edge actually fits the budget`() {
        // 覆盖多种屏幕形状，含超长屏与横屏。
        listOf(
            1080 to 2400, 1440 to 3200, 720 to 1600, 2400 to 1080, 1080 to 1080,
        ).forEach { (w, h) ->
            listOf(0, 200, 800).forEach { textTokens ->
                val budget = VisionContextBudget.visionTokenBudget(ctx, generation, textTokens)
                val edge = VisionContextBudget.maxEdgeWithin(w, h, budget)
                if (edge > VisionContextBudget.MIN_MAX_EDGE) {
                    assertTrue(
                        "${w}×$h / text=$textTokens：算出的长边 $edge 仍然超预算 " +
                            "(${VisionContextBudget.tokensAtEdge(w, h, edge)} > $budget) —— " +
                            "闭式解用的是连续近似，向下校正没做对",
                        VisionContextBudget.fitsWithin(w, h, edge, budget),
                    )
                }
                assertTrue("长边不得超过上限", edge <= VisionContextBudget.ABSOLUTE_MAX_EDGE)
                assertTrue("长边不得低于下限", edge >= VisionContextBudget.MIN_MAX_EDGE)
            }
        }
    }

    @Test
    fun `a small screenshot is never upscaled`() {
        val budget = VisionContextBudget.visionTokenBudget(ctx, generation, textTokens = 0)
        val edge = VisionContextBudget.maxEdgeWithin(fullWidth = 480, fullHeight = 800, visionTokenBudget = budget)
        assertTrue(
            "原图长边只有 800，放大到 $edge 不增加任何信息，只多烧 token",
            edge <= 800,
        )
    }

    @Test
    fun `a longer element list shrinks the image once the budget actually binds`() {
        val small = VisionContextBudget.visionTokenBudget(ctx, generation, textTokens = 50)
        val large = VisionContextBudget.visionTokenBudget(ctx, generation, textTokens = 1200)
        assertTrue("文本开销更大时视觉预算必须更小", large < small)

        // 常见机型上预算通常不 binding —— 真正封住长边的是 ABSOLUTE_MAX_EDGE，
        // 所以这里只能要求非递增，要求严格变小是错的（两边都撞上限，相等才对）。
        assertTrue(
            "长边必须非递增",
            VisionContextBudget.maxEdgeWithin(fullW, fullH, large) <=
                VisionContextBudget.maxEdgeWithin(fullW, fullH, small),
        )

        // 元素清单很长时预算才真的 binding，这时必须严格变小 —— 否则"按实际算"这件事
        // 就只是个摆设，实际永远只有一个固定长边在起作用。
        val binding = VisionContextBudget.visionTokenBudget(ctx, generation, textTokens = 2500)
        val bindingEdge = VisionContextBudget.maxEdgeWithin(fullW, fullH, binding)
        assertTrue(
            "文本开销 2500 时长边应被预算压到上限以下，实际 $bindingEdge",
            bindingEdge < VisionContextBudget.ABSOLUTE_MAX_EDGE,
        )
        assertTrue(
            "预算 binding 时长边必须严格变小",
            bindingEdge < VisionContextBudget.maxEdgeWithin(fullW, fullH, small),
        )
        assertTrue("被压过的长边必须真的装得下", VisionContextBudget.fitsWithin(fullW, fullH, bindingEdge, binding))
    }

    @Test
    fun `budget never goes negative and starving budgets are reported, not silently accepted`() {
        val starved = VisionContextBudget.visionTokenBudget(
            contextSize = 512, generationReserve = 512, textTokens = 4000,
        )
        assertEquals("预算被吃光时应为 0，不是负数", 0, starved)
        val edge = VisionContextBudget.maxEdgeWithin(fullW, fullH, starved)
        assertEquals("撑不住时回到下限", VisionContextBudget.MIN_MAX_EDGE, edge)
        assertFalse(
            "下限都装不下时必须报 false，让调用方决定不送图 —— 而不是硬塞一张会把指令挤掉的图",
            VisionContextBudget.fitsWithin(fullW, fullH, edge, starved),
        )
    }

    @Test
    fun `text token estimate rounds up so short strings are never free`() {
        assertEquals(0, VisionContextBudget.textTokensFor(null, ""))
        assertEquals("1 个字符也要算 1 个 token", 1, VisionContextBudget.textTokensFor("a"))
        val parts = VisionContextBudget.textTokensFor("abc", "def")
        assertTrue("多段应累加", parts >= 2)
    }

    @Test
    fun `vision tokens use whole blocks only`() {
        val e = VisionContextBudget.PIXELS_PER_VISION_TOKEN_EDGE
        assertEquals(1, VisionContextBudget.visionTokensFor(e, e))
        assertEquals("不足一块的边缘像素不产出 token", 1, VisionContextBudget.visionTokensFor(e + e - 1, e))
        assertEquals(0, VisionContextBudget.visionTokensFor(e - 1, e))
        assertEquals(0, VisionContextBudget.visionTokensFor(0, 0))
        assertEquals(6, VisionContextBudget.visionTokensFor(e * 3, e * 2))
    }
}
