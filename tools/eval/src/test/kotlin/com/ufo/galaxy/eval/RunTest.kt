package com.ufo.galaxy.eval

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import com.ufo.galaxy.grounding.VlmGroundingEngine
import com.ufo.galaxy.planner.VlmPlanner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO

/**
 * 端到端:App 自己的 [VlmPlanner] / [VlmGroundingEngine] 真的发 HTTP 请求,对面是一个按剧本回话的 llama-server 替身。
 * 测的是评测这条链(读数据 → 压图 → App 的提示词与解析 → 换算 → 判),不是模型。
 */
class RunTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val plannerReplies = ConcurrentLinkedQueue<String>()
    private val groundingReplies = ConcurrentLinkedQueue<String>()
    private val groundingAsks = mutableListOf<String>()
    private val endpoint get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            val req = JsonParser.parseString(ex.requestBody.readBytes().toString(Charsets.UTF_8)).asJsonObject
            val system = req.getAsJsonArray("messages")[0].asJsonObject.get("content").asString
            val content = if (system.contains("grounding engine")) {
                groundingAsks += req.getAsJsonArray("messages")[1].toString()
                groundingReplies.poll() ?: """{"x":0,"y":0}"""
            } else {
                plannerReplies.poll() ?: """{"steps":[]}"""
            }
            val body = JsonObject().apply {
                add("choices", com.google.gson.JsonArray().apply {
                    add(JsonObject().apply { add("message", JsonObject().apply { addProperty("content", content) }) })
                })
            }.toString().toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun image(file: File, w: Int, h: Int): File {
        file.parentFile.mkdirs()
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", file)
        return file
    }

    @Test
    fun `L1 sends the screenshot at the App's grounding size and judges the point in full resolution`() {
        val root = tmp.newFolder("eval")
        image(File(root, "grounding_eval/dataset/images/0.jpeg"), 1080, 2400)
        val samples = listOf(
            GroundingJudge.Sample("0", "grounding_eval/dataset/images/0.jpeg", "设置按钮", intArrayOf(500, 1100, 600, 1300)),
            GroundingJudge.Sample("1", "grounding_eval/dataset/images/0.jpeg", "返回", intArrayOf(500, 1100, 600, 1300)),
        )
        // 长边压到 720:324×720。定位回 (162, 360) → 原图 (540, 1200),在框里
        groundingReplies += """{"x":162,"y":360,"confidence":0.9,"element":"设置"}"""
        groundingReplies += """{"x":10,"y":10,"confidence":0.4,"element":"?"}"""
        val items = L1.run(samples, root, VlmGroundingEngine(endpointUrl = endpoint))
        assertEquals(listOf(true, false), items.map { it.hit })
        assertEquals(540 to 1200, items[0].x to items[0].y)
        assertTrue(groundingAsks[0], groundingAsks[0].contains("Screenshot size: 324x720"))
        assertEquals(0.5, L1.summary(items).get("accuracy").asDouble, 1e-9)
    }

    private fun episode(dir: File, name: String, steps: List<String>) {
        val seq = File(dir, "test/domestic/$name").apply { mkdirs() }
        File(seq, "$name.json").writeText("[" + steps.joinToString(",") + "]")
        steps.indices.forEach { image(File(seq, "${name}_$it.jpeg"), 1080, 2400) }
    }

    @Test
    fun `L2 asks the App's planner, grounds a tap like the App does, and scores TM, EM and episodes`() {
        val data = tmp.newFolder("cagui")
        val common = """"instruction":"点一杯美式","image_width":1080,"image_height":2400,"subset":"domestic""""
        episode(
            data, "ep1",
            listOf(
                """{"episode_id":"ep1","step_id":0,$common,"result_action_type":4,"result_touch_yx":"[0.5, 0.5]","result_lift_yx":"[0.5, 0.5]","ui_positions":"[[0.45, 0.4, 0.1, 0.2]]","result_action_text":""}""",
                """{"episode_id":"ep1","step_id":1,$common,"result_action_type":3,"result_touch_yx":"[-1, -1]","result_lift_yx":"[-1, -1]","ui_positions":"[]","result_action_text":"美式"}""",
                """{"episode_id":"ep1","step_id":2,$common,"result_action_type":10,"result_touch_yx":"[-1, -1]","result_lift_yx":"[-1, -1]","ui_positions":"[]","result_action_text":""}""",
            ),
        )
        episode(
            data, "ep2",
            listOf("""{"episode_id":"ep2","step_id":0,$common,"result_action_type":4,"result_touch_yx":"[0.2, 0.2]","result_lift_yx":"[0.2, 0.2]","ui_positions":"[]","result_action_text":""}"""),
        )
        plannerReplies += """{"steps":[{"action_type":"tap","intent":"咖啡","parameters":{}},{"action_type":"finish","intent":"","parameters":{}}]}"""
        plannerReplies += """```json
            {"steps":[{"action_type":"type","intent":"搜索框","parameters":{"text":"美式","submit":"false"}}]}
            ```"""
        plannerReplies += """{"steps":[{"action_type":"finish","intent":"","parameters":{}}]}"""
        plannerReplies += """{"steps":[{"action_type":"open_app","intent":"","parameters":{"package":"com.starbucks"}}]}"""
        groundingReplies += """{"x":162,"y":360}""" // 原图 (540, 1200),即 (0.5, 0.5),在放大后的框里

        val episodes = L2.load(File(data, "test"))
        assertEquals(listOf(3, 1), episodes.map { it.size })
        val scored = L2.run(episodes, VlmPlanner(endpointUrl = endpoint), VlmGroundingEngine(endpointUrl = endpoint))
        assertEquals(listOf(true, true, true, false), scored.map { it.result.exactMatch })
        assertEquals("open_app 面壁没有:类型都对不上", false, scored[3].result.typeMatch)

        val summary = L2.summary(scored)
        val total = summary.getAsJsonObject("atomic").getAsJsonObject("total")
        assertEquals(0.75, total.get("type_acc").asDouble, 1e-9)
        assertEquals(0.75, total.get("exact_acc").asDouble, 1e-9)
        assertEquals(0.5, summary.getAsJsonObject("episode").get("success_rate").asDouble, 1e-9)

        val theirs = L2.theirFormat(scored).trim().lines().map { JsonParser.parseString(it).asJsonObject }
        assertEquals("""{"POINT":[500,500]}""", theirs[0].get("pred").toString())
        assertEquals("""{"TYPE":"美式"}""", theirs[1].get("pred").toString())
        assertEquals("domestic", theirs[0].get("category").asString)
    }
}
