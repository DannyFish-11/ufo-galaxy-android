package com.ufo.galaxy.eval

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ufo.galaxy.grounding.VlmGroundingEngine
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.planner.VlmPlanner
import java.io.File

/**
 * L1 定位:截图 + 一句描述(按功能描述 Fun2Point,或按界面上的文字 Text2Point)→ 点在哪。
 * 用 App 的 [VlmGroundingEngine]:截图长边压到 720 送进去,回来的点换算回原图,看落没落在目标框里。
 */
object L1 {
    data class Item(val id: String, val x: Int?, val y: Int?, val hit: Boolean, val error: String?, val ms: Long)

    fun run(samples: List<GroundingJudge.Sample>, imageRoot: File, engine: LocalGroundingService): List<Item> =
        samples.map { s ->
            val img = Images.load(File(imageRoot, s.image))
            val scaled = Images.scaleToMaxEdge(img, Images.GROUNDING_MAX_EDGE)
            val started = System.currentTimeMillis()
            val g = engine.ground(s.text, scaled.base64, scaled.width, scaled.height)
            val ms = System.currentTimeMillis() - started
            if (g.error != null || g.elementIndex != null) {
                // 没给元素清单时回编号没有意义,和定位失败一样算没点中
                Item(s.id, null, null, false, g.error ?: "回了元素编号,但这次没有给元素清单", ms)
            } else {
                val (x, y) = Images.toFull(g.x, g.y, scaled, img.width, img.height)
                Item(s.id, x, y, GroundingJudge.inside(s.box, x.toDouble(), y.toDouble()), null, ms)
            }
        }

    fun summary(items: List<Item>): JsonObject = JsonObject().apply {
        addProperty("count", items.size)
        addProperty("accuracy", Math.round(GroundingJudge.accuracy(items.map { it.hit }) * 10_000) / 10_000.0)
        addProperty("errors", items.count { it.error != null })
        addProperty("mean_ms", if (items.isEmpty()) 0 else items.sumOf { it.ms } / items.size)
    }
}

/**
 * L2 单步动作(离线):指令 + 当前截图 → 下一步动作,和标注比 TM / EM。
 * 和面壁的评测一样,每一步只给指令和当前截图,不给前几步。
 * 用 App 的 [VlmPlanner] 出计划、取第一步;tap / scroll 再用 [VlmGroundingEngine] 定位,和 App 的 EdgeExecutor 一样。
 */
object L2 {
    data class Step(val gt: GtStep, val image: File)

    /** `<data>/<子集>/<任务>/<任务>.json`,图是同目录的 `<任务>_<步>.jpeg`(或 .png),再不然按标注里的 image_path。 */
    fun load(dataDir: File): List<List<Step>> {
        val episodes = mutableListOf<List<Step>>()
        for (subset in dataDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()) {
            for (seq in subset.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()) {
                val file = File(seq, "${seq.name}.json")
                if (!file.isFile) continue
                val steps = JsonParser.parseString(file.readText()).asJsonArray.map { el ->
                    val o = el.asJsonObject
                    if (!o.has("subset")) o.addProperty("subset", subset.name)
                    val gt = StepJudge.gtOf(o)
                    val image = listOf("jpeg", "png").map { File(seq, "${seq.name}_${gt.stepId}.$it") }.firstOrNull { it.isFile }
                        ?: File(dataDir, gt.imagePath)
                    Step(gt, image)
                }
                episodes += steps
            }
        }
        return episodes
    }

    /** 这一步 App 会做什么。规划失败、没给出步骤是 null(格式没命中)。 */
    fun predict(step: Step, planner: LocalPlannerService, grounding: LocalGroundingService): Act? {
        val img = Images.load(step.image)
        val goal = step.gt.instruction
        val plan = planner.plan(goal, emptyList(), Images.forPlanning(img, goal)?.base64)
        val first = plan.steps.firstOrNull()
        if (plan.error != null || first == null) return null
        var point: Pair<Int, Int>? = null
        if (first.action_type == "tap" || first.action_type == "scroll") {
            val scaled = Images.scaleToMaxEdge(img, Images.GROUNDING_MAX_EDGE)
            val g = grounding.ground(first.intent, scaled.base64, scaled.width, scaled.height)
            if (g.error == null && g.elementIndex == null) point = Images.toFull(g.x, g.y, scaled, img.width, img.height)
        }
        return Convert.fromApp(first, point, img.width, img.height)
    }

    data class Scored(val step: Step, val pred: Act?, val result: StepResult)

    fun run(episodes: List<List<Step>>, planner: LocalPlannerService, grounding: LocalGroundingService): List<Scored> =
        episodes.flatten().map { s -> predict(s, planner, grounding).let { Scored(s, it, StepJudge.judge(s.gt, it)) } }

    fun summary(scored: List<Scored>): JsonObject = JsonObject().apply {
        add("atomic", StepJudge.atomicMetrics(scored.map { it.result }))
        val byEpisode = scored.groupBy { "${it.step.gt.subset}-${it.step.gt.episodeId}" }
            .mapValues { (_, v) -> v.sortedBy { it.step.gt.stepId }.map { it.result } }
        add("episode", StepJudge.episodeMetrics(byEpisode))
    }

    /** 面壁 run_eval_agent.py 认的预测文件(一行一步),可以拿他们的脚本再判一遍对照。 */
    fun theirFormat(scored: List<Scored>): String = scored.joinToString("\n", postfix = "\n") { s ->
        JsonObject().apply {
            addProperty("category", s.step.gt.subset)
            addProperty("episode_id", s.step.gt.episodeId)
            addProperty("step_id", s.step.gt.stepId)
            add("pred", Convert.toCompact(s.pred))
        }.toString()
    }
}

private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

private fun usage(): Nothing {
    System.err.println(
        """
        用法(先在电脑上起 llama-server,载 App 用的同一份 GGUF 和 mmproj,见 tools/eval/README.md):
          l1 --data <cap.jsonl|ocr.jsonl> --root <AgentCPM-GUI/eval 目录> [--endpoint http://127.0.0.1:8080] [--limit N] [--out 目录]
          l2 --data <chinese_app_test/test 目录> [--endpoint http://127.0.0.1:8080] [--limit N] [--out 目录]
        """.trimIndent(),
    )
    kotlin.system.exitProcess(2)
}

fun main(args: Array<String>) {
    val cmd = args.firstOrNull() ?: usage()
    val opts = args.drop(1).chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    val endpoint = opts["endpoint"] ?: "http://127.0.0.1:8080"
    val limit = opts["limit"]?.toInt() ?: Int.MAX_VALUE
    val out = File(opts["out"] ?: "eval_results").apply { mkdirs() }
    val grounding = VlmGroundingEngine(endpointUrl = endpoint, timeoutMs = 120_000)
    when (cmd) {
        "l1" -> {
            val data = File(opts["data"] ?: usage())
            val samples = data.readLines().filter { it.isNotBlank() }.take(limit)
                .map { GroundingJudge.sampleOf(JsonParser.parseString(it).asJsonObject) }
            val items = L1.run(samples, File(opts["root"] ?: usage()), grounding)
            File(out, "l1_${data.nameWithoutExtension}.jsonl").writeText(items.joinToString("\n", postfix = "\n") { gson.toJson(it).replace("\n", "") })
            val summary = L1.summary(items).apply { addProperty("data", data.name) }
            File(out, "l1_${data.nameWithoutExtension}_summary.json").writeText(gson.toJson(summary))
            println(gson.toJson(summary))
        }
        "l2" -> {
            val planner = VlmPlanner(endpointUrl = endpoint, timeoutMs = 120_000)
            val episodes = L2.load(File(opts["data"] ?: usage())).take(limit)
            val scored = L2.run(episodes, planner, grounding)
            File(out, "all.jsonl").writeText(L2.theirFormat(scored))
            val summary = L2.summary(scored)
            File(out, "l2_summary.json").writeText(gson.toJson(summary))
            println(gson.toJson(summary))
        }
        else -> usage()
    }
}
