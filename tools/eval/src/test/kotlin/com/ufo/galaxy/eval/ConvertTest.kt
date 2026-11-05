package com.ufo.galaxy.eval

import com.ufo.galaxy.inference.LocalPlannerService.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** App 的动作 ⇄ 面壁的紧凑 JSON:评测里加的这一层换算,不改 App。 */
class ConvertTest {

    @Test
    fun `a tap lands where grounding pointed, as a fraction of the screen`() {
        val a = Convert.fromApp(PlanStep("tap", "设置图标"), 540 to 600, 1080, 2400)!!
        assertEquals("click", a.type)
        assertEquals(0.5, a.x!!, 1e-9)
        assertEquals(0.25, a.y!!, 1e-9)
        val lost = Convert.fromApp(PlanStep("tap", "设置图标"), null, 1080, 2400)!!
        assertEquals("定位失败:类型算对、位置不对(和面壁处理坐标解析失败一样)", 0.0, lost.x!!, 1e-9)
    }

    @Test
    fun `our scroll direction is where the content goes, the judge wants where the finger goes`() {
        val swipe = { d: String -> Convert.fromApp(PlanStep("scroll", "列表", mapOf("direction" to d)), null, 1080, 2400)!!.direction }
        assertEquals("up", swipe("down"))
        assertEquals("down", swipe("up"))
        assertEquals("left", swipe("left"))
        assertEquals("right", swipe("right"))
        assertEquals("没给方向按 down", "up", Convert.fromApp(PlanStep("scroll", "列表"), null, 1, 1)!!.direction)
    }

    @Test
    fun `type, keys, finish and what the judge has no word for`() {
        assertEquals("美式", Convert.fromApp(PlanStep("type", "搜索框", mapOf("text" to "美式")), null, 1, 1)!!.text)
        assertEquals("没给 text 时 App 退回 intent", "搜索框", Convert.fromApp(PlanStep("type", "搜索框"), null, 1, 1)!!.text)
        assertEquals("back", Convert.fromApp(PlanStep("back", ""), null, 1, 1)!!.button)
        assertEquals("stop", Convert.fromApp(PlanStep("finish", ""), null, 1, 1)!!.type)
        assertEquals("open_app", Convert.fromApp(PlanStep("open_app", "", mapOf("package" to "com.tencent.mm")), null, 1, 1)!!.type)
        assertEquals("不认识的类型 App 按 tap 做", "click", Convert.fromApp(PlanStep("swipe_magic", ""), 1 to 1, 2, 2)!!.type)
        assertNull(Convert.fromApp(null, null, 1, 1))
    }

    @Test
    fun `their compact json reads the way their action_map does`() {
        assertEquals("click", Convert.fromCompact("""{"POINT":[729,69]}""")!!.type)
        assertEquals(0.729, Convert.fromCompact("""{"POINT":[729,69]}""")!!.x!!, 1e-9)
        assertEquals("up", Convert.fromCompact("""{"POINT":[500,800],"to":"up"}""")!!.direction)
        assertEquals("down", Convert.fromCompact("""{"POINT":[500,200],"to":[500,800]}""")!!.direction)
        assertEquals("long_point", Convert.fromCompact("""{"POINT":[500,500],"duration":1000}""")!!.type)
        assertEquals("type", Convert.fromCompact("""{"TYPE":"你好"}""")!!.type)
        assertEquals("home", Convert.fromCompact("""{"PRESS":"HOME"}""")!!.button)
        assertEquals("stop", Convert.fromCompact("""{"STATUS":"finish"}""")!!.type)
        assertEquals("只等一会儿也算停", "stop", Convert.fromCompact("""{"duration":3000}""")!!.type)
        assertNull("不合 schema", Convert.fromCompact("""{"OPEN_APP":"微信"}"""))
        assertNull(Convert.fromCompact("不是 JSON"))
    }

    @Test
    fun `what we write in their format reads back the same, and what they have no word for stays a miss`() {
        val acts = listOf(
            Act("click", x = 0.5, y = 0.25),
            Act("scroll", x = 0.5, y = 0.5, direction = "left"),
            Act("type", text = "美式"),
            Act("press", button = "enter"),
            Act("stop"),
        )
        for (a in acts) {
            val back = Convert.fromCompact(Convert.toCompact(a).toString())!!
            assertEquals(a.type, back.type)
            assertEquals(a.direction, back.direction)
            assertEquals(a.text, back.text)
            assertEquals(a.button, back.button)
        }
        assertNull(Convert.fromCompact(Convert.toCompact(Act("open_app", text = "com.tencent.mm")).toString()))
        assertNull(Convert.fromCompact(Convert.toCompact(null).toString()))
    }
}
