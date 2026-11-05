package com.ufo.galaxy.eval

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * 判定细则照抄面壁 AgentCPM-GUI 的评测(github.com/OpenBMB/AgentCPM-GUI,Apache-2.0):
 *  - 单步动作:eval/utils/evaluator.py 的 ActionEvaluator(本身沿用 Qwen2.5-VL 的评测);
 *  - 定位:eval/grounding_eval/code/minicpm 下 fun2bbox / text2bbox 两个脚本里的 verify(点落在目标框里)。
 * 常数、先后顺序、边界取等都按原代码,这样我们的数字能直接和他们公开的数字比。
 */

/**
 * 一步动作,统一到面壁评测的口径。坐标是 0..1 的相对值(x 向右、y 向下)。
 *
 * @param type      click / long_point / scroll / type / press / stop;我们独有、他们没有的(open_app)照原样留着,判不上
 * @param direction 滑动时**手指**移动的方向:up / down / left / right
 * @param button    back / home / enter
 */
data class Act(
    val type: String,
    val x: Double? = null,
    val y: Double? = null,
    val direction: String? = null,
    val text: String? = null,
    val button: String? = null,
)

/** 标注的一步:动作,以及界面上各控件的框([y, x, h, w],0..1)。 */
data class GtStep(
    val act: Act,
    val boxes: List<DoubleArray> = emptyList(),
    val subset: String = "",
    val episodeId: String = "",
    val stepId: Int = 0,
    val instruction: String = "",
    val imagePath: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

data class StepResult(
    val gtType: String,
    val predType: String?,
    val typeMatch: Boolean,
    val exactMatch: Boolean,
    val textDist: Double? = null,
    val formatHit: Boolean,
    val pixelDistance: Double? = null,
)

object StepJudge {
    /** 点击离标注点多远还算对(屏幕的比例)。 */
    const val TAP_DISTANCE_THRESHOLD = 0.14

    /** 按下与抬起相距多少以内算点击、以外算滑动。 */
    const val SWIPE_DISTANCE_THRESHOLD = 0.04

    /** 标注框放大的倍数(aitz 与 Qwen2.5-VL 都取 1.2)。 */
    const val BOX_AUGMENT = 1.2

    /** 紧凑 JSON 里 duration 的缺省值(毫秒)。 */
    const val DEFAULT_DURATION = 200

    /** AndroidInTheWild 的动作类型编号(标注里的 result_action_type)。 */
    object ActionType {
        const val LONG_POINT = 0
        const val NO_ACTION = 1
        const val TYPE = 3
        const val DUAL_POINT = 4
        const val PRESS_BACK = 5
        const val PRESS_HOME = 6
        const val PRESS_ENTER = 7
        const val STATUS_TASK_COMPLETE = 10
        const val STATUS_TASK_IMPOSSIBLE = 11
    }

    private fun yx(raw: String?): DoubleArray {
        val arr = JsonParser.parseString(raw ?: "[-1, -1]").asJsonArray
        return doubleArrayOf(arr[0].asDouble, arr[1].asDouble)
    }

    private fun boxes(raw: String?): List<DoubleArray> {
        if (raw.isNullOrBlank()) return emptyList()
        return JsonParser.parseString(raw).asJsonArray.map { b ->
            val a = b.asJsonArray
            doubleArrayOf(a[0].asDouble, a[1].asDouble, a[2].asDouble, a[3].asDouble)
        }
    }

    private fun str(o: JsonObject, key: String): String? = o.get(key)?.takeUnless { it.isJsonNull }?.asString

    /** 一步标注(CAGUI / aitz 的 step JSON)→ [GtStep]。 */
    fun gtOf(step: JsonObject): GtStep {
        val type = step.get("result_action_type").asInt
        val text = str(step, "result_action_text") ?: ""
        val (act, boxes) = when (type) {
            ActionType.TYPE -> Act("type", text = text) to emptyList()
            ActionType.DUAL_POINT -> {
                val start = yx(str(step, "result_touch_yx"))
                val end = yx(str(step, "result_lift_yx"))
                if (isTap(start, end)) {
                    Act("click", x = start[1], y = start[0]) to boxes(str(step, "ui_positions"))
                } else {
                    Act("scroll", direction = direction(start[1], start[0], end[1], end[0])) to emptyList()
                }
            }
            ActionType.LONG_POINT -> {
                val start = yx(str(step, "result_touch_yx"))
                Act("long_point", x = start[1], y = start[0]) to boxes(str(step, "ui_positions"))
            }
            ActionType.PRESS_BACK -> Act("press", button = "back") to emptyList()
            ActionType.PRESS_HOME -> Act("press", button = "home") to emptyList()
            ActionType.PRESS_ENTER -> Act("press", button = "enter") to emptyList()
            ActionType.STATUS_TASK_COMPLETE, ActionType.STATUS_TASK_IMPOSSIBLE -> Act("stop", text = text) to emptyList()
            ActionType.NO_ACTION -> Act("stop") to emptyList()
            else -> throw IllegalArgumentException("不认识的动作类型:$type")
        }
        return GtStep(
            act = act,
            boxes = boxes,
            subset = str(step, "subset") ?: str(step, "image_path")?.substringBefore('/') ?: "",
            episodeId = str(step, "episode_id") ?: "",
            stepId = step.get("step_id")?.asInt ?: 0,
            instruction = str(step, "instruction") ?: "",
            imagePath = str(step, "image_path") ?: "",
            width = step.get("image_width")?.asInt ?: 0,
            height = step.get("image_height")?.asInt ?: 0,
        )
    }

    /** 按下与抬起的相对位置([y, x])挨得足够近就是点击。 */
    fun isTap(startYx: DoubleArray, endYx: DoubleArray): Boolean =
        dist(startYx[0], startYx[1], endYx[0], endYx[1]) <= SWIPE_DISTANCE_THRESHOLD

    /** 从 (x1, y1) 到 (x2, y2) 最接近上下左右哪一个;原地不动是 "no direction"。并列时按 上、下、左、右 的先后取。 */
    fun direction(x1: Double, y1: Double, x2: Double, y2: Double): String {
        val vx = x2 - x1
        val vy = y2 - y1
        val len = sqrt(vx * vx + vy * vy)
        if (len == 0.0) return "no direction"
        val ux = vx / len
        val uy = vy / len
        var best = -Double.MAX_VALUE
        var closest = "no direction"
        for ((name, d) in listOf("up" to (0.0 to -1.0), "down" to (0.0 to 1.0), "left" to (-1.0 to 0.0), "right" to (1.0 to 0.0))) {
            val cos = ux * d.first + uy * d.second
            if (cos > best) {
                best = cos
                closest = name
            }
        }
        return closest
    }

    /** 每个框各边按比例放大,夹在 0..1 里(框是 [y, x, h, w])。 */
    fun enlarge(box: DoubleArray, factor: Double = BOX_AUGMENT): DoubleArray {
        val hDelta = (factor - 1) * box[2]
        val wDelta = (factor - 1) * box[3]
        return doubleArrayOf(
            max(0.0, box[0] - hDelta / 2),
            max(0.0, box[1] - wDelta / 2),
            min(1.0, box[2] + hDelta),
            min(1.0, box[3] + wDelta),
        )
    }

    private fun inside(box: DoubleArray, x: Double, y: Double): Boolean =
        box[1] <= x && x <= box[1] + box[3] && box[0] <= y && y <= box[0] + box[2]

    private fun dist(a1: Double, a2: Double, b1: Double, b2: Double): Double {
        val d1 = a1 - b1
        val d2 = a2 - b2
        return sqrt(d1 * d1 + d2 * d2)
    }

    /** 判一步:类型对不对(TM)、类型和参数都对不对(EM)。 */
    fun judge(gt: GtStep, pred: Act?): StepResult {
        val g = gt.act
        val typeMatch = pred != null && pred.type == g.type
        var exact = false
        var textDist: Double? = null
        var pixel: Double? = null
        if (typeMatch && (g.type == "click" || g.type == "long_point")) {
            val gx = g.x!!
            val gy = g.y!!
            val px = pred!!.x ?: 0.0
            val py = pred.y ?: 0.0
            val hit = gt.boxes.map { enlarge(it) }.filter { inside(it, gx, gy) }
            var refX = gx
            var refY = gy
            if (hit.isEmpty()) {
                exact = dist(gx, gy, px, py) <= TAP_DISTANCE_THRESHOLD
            } else {
                for (b in hit) {
                    if (inside(b, px, py)) {
                        exact = true
                        refX = b[1] + b[3] / 2
                        refY = b[0] + b[2] / 2
                        break
                    }
                }
                if (!exact) exact = dist(gx, gy, px, py) <= TAP_DISTANCE_THRESHOLD
            }
            pixel = dist(px * 1000, py * 1000, refX * 1000, refY * 1000)
        }
        if (typeMatch && g.type == "scroll") exact = pred!!.direction == g.direction
        if (typeMatch && g.type == "type") {
            val pd = (pred!!.text ?: "").lowercase().trim()
            val gtText = (g.text ?: "").lowercase().trim()
            textDist = levenshteinRatio(pd, gtText)
            exact = gtText.contains(pd) || pd.contains(gtText)
        }
        if (typeMatch && g.type == "press") exact = pred!!.button == g.button
        if (typeMatch && g.type == "stop") exact = true
        return StepResult(g.type, pred?.type, typeMatch, exact, textDist, pred != null, pixel)
    }

    /** python-Levenshtein 的 ratio:按插入 / 删除距离归一的相似度,两个都空是 1。 */
    fun levenshteinRatio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val lcs = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 1..a.length) for (j in 1..b.length) {
            lcs[i][j] = if (a[i - 1] == b[j - 1]) lcs[i - 1][j - 1] + 1 else max(lcs[i - 1][j], lcs[i][j - 1])
        }
        return 2.0 * lcs[a.length][b.length] / (a.length + b.length)
    }

    /** 各动作类型与总体的 TM / EM、格式命中率、文字相似度、点击偏差(千分位像素)。 */
    fun atomicMetrics(results: List<StepResult>): JsonObject {
        val out = JsonObject()
        fun bucket(name: String, of: List<StepResult>) = JsonObject().apply {
            addProperty("count", of.size)
            addProperty("type_acc", if (of.isEmpty()) 0.0 else round4(of.count { it.typeMatch }.toDouble() / of.size))
            addProperty("exact_acc", if (of.isEmpty()) 0.0 else round4(of.count { it.exactMatch }.toDouble() / of.size))
            out.add(name, this)
        }
        bucket("total", results).addProperty(
            "hit_rate", if (results.isEmpty()) 0.0 else round4(results.count { it.formatHit }.toDouble() / results.size),
        )
        for (t in listOf("click", "long_point", "scroll", "press", "stop", "type")) {
            bucket(t.uppercase(), results.filter { it.gtType == t })
        }
        val dists = results.mapNotNull { it.textDist }
        out.getAsJsonObject("TYPE").addProperty("text_dist_avg", if (dists.isEmpty()) 0.0 else round4(dists.average()))
        val pixels = results.mapNotNull { it.pixelDistance }.sorted()
        out.addProperty("median_pixel_distance", if (pixels.isEmpty()) -1.0 else round4(median(pixels)))
        out.addProperty("mean_pixel_distance", if (pixels.isEmpty()) -1.0 else round4(pixels.average()))
        return out
    }

    /** 每个任务:从第一步起连续做对几步(进度),全做对才算成功。 */
    fun episodeMetrics(byEpisode: Map<String, List<StepResult>>): JsonObject {
        val success = mutableListOf<Boolean>()
        val progress = mutableListOf<Double>()
        for ((_, steps) in byEpisode) {
            val done = steps.takeWhile { it.exactMatch }.size
            success += done == steps.size
            progress += done.toDouble() / steps.size
        }
        return JsonObject().apply {
            addProperty("total_episodes", success.size)
            addProperty("total_steps", byEpisode.values.sumOf { it.size })
            addProperty("num_successes", success.count { it })
            addProperty("success_rate", if (success.isEmpty()) 0.0 else round4(success.count { it }.toDouble() / success.size))
            addProperty("goal_progress", if (progress.isEmpty()) 0.0 else round4(progress.average()))
        }
    }

    private fun median(sorted: List<Double>): Double =
        if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2

    private fun round4(v: Double): Double = Math.round(v * 10_000) / 10_000.0
}

/** 定位(L1):点落在目标框里就对。 */
object GroundingJudge {
    /** 一条定位标注:图、一句描述(功能描述或界面上的文字)、目标框(原图像素,左上右下)。 */
    data class Sample(val id: String, val image: String, val text: String, val box: IntArray)

    fun sampleOf(line: JsonObject): Sample {
        val box = line.get("abs_position").asString.trim('<', '>').split(',').map { it.trim().toDouble().toInt() }
        return Sample(line.get("id").asString, line.get("image").asString, line.get("text").asString, box.toIntArray())
    }

    /** 原图像素里的一点在不在框里(边上算在里面)。 */
    fun inside(box: IntArray, x: Double, y: Double): Boolean = box[0] <= x && x <= box[2] && box[1] <= y && y <= box[3]

    fun accuracy(hits: List<Boolean>): Double = if (hits.isEmpty()) 0.0 else hits.count { it }.toDouble() / hits.size
}

/** 结果文件里写一个 JsonArray 的小工具。 */
internal fun jsonArrayOf(vararg items: Number): JsonArray = JsonArray().apply { items.forEach { add(it) } }
