package com.ufo.galaxy.eval

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判定细则与面壁 AgentCPM-GUI 的 ActionEvaluator 一致:每条的期望值都是照他们的代码一步步算出来的。
 */
class JudgeTest {

    private fun gt(json: String) = StepJudge.gtOf(JsonParser.parseString(json).asJsonObject)

    /** 一次点击:标注点 (x=0.5, y=0.3),界面上两个控件框([y, x, h, w])。 */
    private val click = gt(
        """{"result_action_type":4,"result_touch_yx":"[0.3, 0.5]","result_lift_yx":"[0.3, 0.5]",
            "ui_positions":"[[0.25, 0.4, 0.1, 0.2], [0.8, 0.1, 0.05, 0.1]]","result_action_text":"",
            "episode_id":"e1","step_id":0,"subset":"domestic","instruction":"打开设置","image_width":1080,"image_height":2400}""",
    )

    @Test
    fun `a tap is a click when touch and lift are within 0_04 of each other, otherwise a swipe with its direction`() {
        assertEquals("click", click.act.type)
        assertEquals(0.5, click.act.x!!, 1e-9)
        val swipe = gt("""{"result_action_type":4,"result_touch_yx":"[0.8, 0.5]","result_lift_yx":"[0.2, 0.52]","ui_positions":"[]"}""")
        assertEquals("scroll", swipe.act.type)
        assertEquals("手指从下往上", "up", swipe.act.direction)
        val nearlyTap = gt("""{"result_action_type":4,"result_touch_yx":"[0.5, 0.5]","result_lift_yx":"[0.5, 0.53]","ui_positions":"[]"}""")
        assertEquals("挪了 0.03,还算点击", "click", nearlyTap.act.type)
    }

    @Test
    fun `a click inside the enlarged box of the target is exact`() {
        // 目标框 [0.25,0.4,0.1,0.2] 放大 1.2 倍后 x ∈ [0.38,0.62],y ∈ [0.24,0.36]
        assertTrue(StepJudge.judge(click, Act("click", x = 0.61, y = 0.355)).exactMatch)
        val r = StepJudge.judge(click, Act("click", x = 0.63, y = 0.3))
        assertTrue("出了框,但离标注点 0.13 ≤ 0.14,照样算对", r.exactMatch)
        assertFalse(StepJudge.judge(click, Act("click", x = 0.5, y = 0.45)).exactMatch)
    }

    @Test
    fun `the box is enlarged by 1_2 before checking, so a point just outside a big control still counts`() {
        // 框 [0.2, 0.2, 0.4, 0.4] 放大后 x、y 都是 [0.16, 0.64];(0.62, 0.62) 在原框外、放大框内,离标注点 0.52,远超 0.14
        val big = gt("""{"result_action_type":4,"result_touch_yx":"[0.25, 0.25]","result_lift_yx":"[0.25, 0.25]","ui_positions":"[[0.2, 0.2, 0.4, 0.4]]"}""")
        assertTrue(StepJudge.judge(big, Act("click", x = 0.62, y = 0.62)).exactMatch)
        assertFalse(StepJudge.judge(big, Act("click", x = 0.66, y = 0.62)).exactMatch)
    }

    @Test
    fun `without a box around the target the distance alone decides`() {
        val bare = gt("""{"result_action_type":4,"result_touch_yx":"[0.5, 0.5]","result_lift_yx":"[0.5, 0.5]","ui_positions":"[]"}""")
        assertFalse("离标注点 √0.02 ≈ 0.1414,超了 0.14", StepJudge.judge(bare, Act("click", x = 0.6, y = 0.6)).exactMatch)
        assertTrue(StepJudge.judge(bare, Act("click", x = 0.6, y = 0.59)).exactMatch)
    }

    @Test
    fun `type matches when either text contains the other, case and spaces ignored`() {
        val t = gt("""{"result_action_type":3,"result_action_text":"Coffee 美式","result_touch_yx":"[-1,-1]","result_lift_yx":"[-1,-1]"}""")
        assertTrue(StepJudge.judge(t, Act("type", text = " coffee ")).exactMatch)
        assertTrue(StepJudge.judge(t, Act("type", text = "coffee 美式 大杯")).exactMatch)
        val r = StepJudge.judge(t, Act("type", text = "茶"))
        assertTrue(r.typeMatch)
        assertFalse(r.exactMatch)
        assertEquals(0.0, r.textDist!!, 1e-9)
    }

    @Test
    fun `scroll needs the same finger direction, press the same button, stop is always exact`() {
        val swipe = gt("""{"result_action_type":4,"result_touch_yx":"[0.8, 0.5]","result_lift_yx":"[0.2, 0.5]"}""")
        assertTrue(StepJudge.judge(swipe, Act("scroll", direction = "up")).exactMatch)
        assertFalse(StepJudge.judge(swipe, Act("scroll", direction = "down")).exactMatch)
        val back = gt("""{"result_action_type":5}""")
        assertTrue(StepJudge.judge(back, Act("press", button = "back")).exactMatch)
        assertFalse(StepJudge.judge(back, Act("press", button = "home")).exactMatch)
        val done = gt("""{"result_action_type":10,"result_action_text":""}""")
        assertTrue(StepJudge.judge(done, Act("stop")).exactMatch)
    }

    @Test
    fun `a missing prediction misses the format, a wrong type misses both`() {
        val none = StepJudge.judge(click, null)
        assertFalse(none.formatHit)
        assertFalse(none.typeMatch)
        val openApp = StepJudge.judge(click, Act("open_app", text = "com.tencent.mm"))
        assertTrue(openApp.formatHit)
        assertFalse(openApp.typeMatch)
    }

    @Test
    fun `direction ties go up, down, left, right in that order`() {
        assertEquals("up", StepJudge.direction(0.5, 0.5, 0.6, 0.4)) // 右上 45°:上与右并列,取先出现的上
        assertEquals("down", StepJudge.direction(0.5, 0.5, 0.4, 0.6))
        assertEquals("no direction", StepJudge.direction(0.5, 0.5, 0.5, 0.5))
    }

    @Test
    fun `levenshtein ratio is the normalised indel similarity`() {
        assertEquals(1.0, StepJudge.levenshteinRatio("", ""), 1e-9)
        assertEquals("公共子序列 3 个字:2·3/8", 0.75, StepJudge.levenshteinRatio("abcd", "abce"), 1e-9)
    }

    @Test
    fun `metrics count per type, and an episode succeeds only when every step is exact`() {
        val ok = StepResult("click", "click", true, true, formatHit = true, pixelDistance = 10.0)
        val tmOnly = StepResult("click", "click", true, false, formatHit = true, pixelDistance = 30.0)
        val miss = StepResult("type", null, false, false, formatHit = false)
        val m = StepJudge.atomicMetrics(listOf(ok, tmOnly, miss))
        assertEquals(3, m.getAsJsonObject("total").get("count").asInt)
        assertEquals(0.6667, m.getAsJsonObject("total").get("type_acc").asDouble, 1e-9)
        assertEquals(0.3333, m.getAsJsonObject("total").get("exact_acc").asDouble, 1e-9)
        assertEquals(0.6667, m.getAsJsonObject("total").get("hit_rate").asDouble, 1e-9)
        assertEquals(0.5, m.getAsJsonObject("CLICK").get("exact_acc").asDouble, 1e-9)
        assertEquals(20.0, m.get("median_pixel_distance").asDouble, 1e-9)
        val e = StepJudge.episodeMetrics(mapOf("a" to listOf(ok, ok), "b" to listOf(ok, tmOnly, ok)))
        assertEquals(0.5, e.get("success_rate").asDouble, 1e-9)
        assertEquals("b 只连续做对第一步:1/3", (1.0 + 1.0 / 3) / 2, e.get("goal_progress").asDouble, 1e-4)
    }

    @Test
    fun `grounding counts a point on the edge of the target box`() {
        val s = GroundingJudge.sampleOf(
            JsonParser.parseString("""{"task":"bbox2text","image":"i/0.jpeg","id":7,"abs_position":"<100, 200, 300, 400>","text":"QQ音乐"}""").asJsonObject,
        )
        assertEquals("7", s.id)
        assertTrue(GroundingJudge.inside(s.box, 100.0, 400.0))
        assertFalse(GroundingJudge.inside(s.box, 99.9, 300.0))
    }
}
