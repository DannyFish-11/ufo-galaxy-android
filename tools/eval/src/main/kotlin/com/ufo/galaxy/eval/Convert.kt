package com.ufo.galaxy.eval

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ufo.galaxy.inference.LocalPlannerService

/**
 * 动作格式换算。评测里加这一层,不改 App:
 *  - 我们的格式:`action_type` + `parameters`(tap 由定位引擎给出坐标);
 *  - 面壁的格式:紧凑 JSON,`POINT`、`to`、`TYPE`、`PRESS`、`STATUS`、`duration`,坐标按 0–1000 归一。
 */
object Convert {

    /** 我们的滚动方向说的是内容往哪走,手指反着划(见 App 的 HardwareKeyListener.dispatchScroll)。 */
    val FINGER_FOR_SCROLL = mapOf("down" to "up", "up" to "down", "left" to "left", "right" to "right")

    private val STOP_STATUS = setOf("finish", "satisfied", "impossible", "interrupt", "need_feedback")

    /**
     * App 规划器的一步(+ 定位引擎给的点,原图像素)→ [Act]。
     *
     * - tap:点在定位给的位置;定位失败按 (0, 0) 记 —— 和面壁对坐标解析失败的处理一样:类型算对、位置不对;
     * - scroll:换成手指方向;定位给了点就从那里划,没给就从屏幕中间;
     * - type:`text` 参数,没给就是 intent(App 的 ExecutorBridge 同样退回 intent);
     * - back / home → 按键,finish → 停;
     * - open_app:面壁的动作空间里没有(他们靠点桌面图标),原样记着,判不上;
     * - 规划器不认识的类型,App 按 tap 执行,这里也按点击算。
     */
    fun fromApp(step: LocalPlannerService.PlanStep?, point: Pair<Int, Int>?, width: Int, height: Int): Act? {
        step ?: return null
        val px = point?.let { it.first.toDouble() / width }
        val py = point?.let { it.second.toDouble() / height }
        return when (step.action_type) {
            "scroll" -> Act(
                "scroll",
                x = px ?: 0.5,
                y = py ?: 0.5,
                direction = FINGER_FOR_SCROLL[step.parameters["direction"] ?: "down"] ?: "up",
            )
            "type" -> Act("type", text = step.parameters["text"] ?: step.intent)
            "back" -> Act("press", button = "back")
            "home" -> Act("press", button = "home")
            "finish" -> Act("stop")
            "open_app" -> Act("open_app", text = step.parameters["package"] ?: "")
            else -> Act("click", x = px ?: 0.0, y = py ?: 0.0)
        }
    }

    /** 面壁模型吐的紧凑 JSON → [Act](他们的 action_map);不合格式是 null。别的候选端侧模型按这个格式评。 */
    fun fromCompact(raw: String): Act? {
        val o = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (e: Exception) {
            return null
        }
        val allowed = setOf("thought", "POINT", "to", "duration", "PRESS", "TYPE", "STATUS")
        if (o.keySet().any { it !in allowed }) return null
        val status = o.get("STATUS")?.asString ?: "continue"
        val duration = o.get("duration")?.asInt ?: StepJudge.DEFAULT_DURATION
        val point = o.getAsJsonArray("POINT")?.let { it[0].asDouble / 1000 to it[1].asDouble / 1000 }
        return when {
            status in STOP_STATUS -> Act("stop")
            o.has("TYPE") -> Act("type", text = o.get("TYPE").asString)
            point != null && !o.has("to") && duration == StepJudge.DEFAULT_DURATION -> Act("click", x = point.first, y = point.second)
            point != null && o.has("to") && duration == StepJudge.DEFAULT_DURATION -> {
                val to = o.get("to")
                val dir = if (to.isJsonPrimitive) {
                    to.asString.takeIf { it in setOf("up", "down", "left", "right") } ?: "no direction"
                } else {
                    val t = to.asJsonArray
                    StepJudge.direction(point.first, point.second, t[0].asDouble / 1000, t[1].asDouble / 1000)
                }
                Act("scroll", x = point.first, y = point.second, direction = dir)
            }
            point != null && duration > StepJudge.DEFAULT_DURATION -> Act("long_point", x = point.first, y = point.second)
            o.has("PRESS") -> Act("press", button = o.get("PRESS").asString.lowercase())
            o.has("duration") -> Act("stop")
            else -> null
        }
    }

    /**
     * [Act] → 面壁的紧凑 JSON。我们的预测也按这个格式写一份(`all.jsonl`),可以拿他们的 run_eval_agent.py 再判一遍对照。
     * 他们的动作空间里没有的(open_app、没给出动作)故意写成不合 schema 的,他们那边也判为没命中。
     */
    fun toCompact(act: Act?): JsonObject = JsonObject().apply {
        fun milli(v: Double?) = Math.round((v ?: 0.0) * 1000).toInt().coerceIn(0, 1000)
        when (act?.type) {
            "click" -> add("POINT", jsonArrayOf(milli(act.x), milli(act.y)))
            "long_point" -> {
                add("POINT", jsonArrayOf(milli(act.x), milli(act.y)))
                addProperty("duration", 1000)
            }
            "scroll" -> {
                add("POINT", jsonArrayOf(milli(act.x), milli(act.y)))
                addProperty("to", act.direction)
            }
            "type" -> addProperty("TYPE", act.text)
            "press" -> addProperty("PRESS", act.button?.uppercase())
            "stop" -> addProperty("STATUS", "finish")
            "open_app" -> addProperty("OPEN_APP", act.text)
            else -> addProperty("NO_ACTION", true)
        }
    }
}
