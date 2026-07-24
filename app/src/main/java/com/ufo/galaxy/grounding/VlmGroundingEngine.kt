package com.ufo.galaxy.grounding

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.WarmupResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * GUI grounding engine backed by the unified MAI-UI-2B model —— Qwen3-VL-2B 底座(统一 VLM 的
 * "定位"职责)。
 *
 * Talks to the **same** local llama.cpp server as [com.ufo.galaxy.planner.VlmPlanner]
 * (default: http://127.0.0.1:8080, OpenAI-compatible POST /v1/chat/completions)。
 * 单模型双职:规划与定位共用一份 2B 权重与一个推理进程。
 *
 * 取代历史 SeeClick NCNN 栈的原因(读码 + 调研实证):
 *  - SeeClick 本体是 Qwen-VL 9.6B,官方仓从不存在 NCNN 端口,`seeclick.ncnn.param/bin`
 *    从未成功供给 —— 该定位槽位在生产上从未真正工作过;
 *  - NCNN 生态不存在任何现代 GUI 定位模型可替换,该 runtime 已整体退役。
 *
 * MAI-UI-2B 为 GUI grounding 专精训练(ScreenSpot-Pro 2B 档 SOTA,绝对像素坐标)。本引擎通过严格 JSON 指令约束
 * 输出:`{"x":<int>,"y":<int>,"confidence":<0..1>,"element":"<desc>"}`,并在解析侧做
 * code-fence 剥离与坐标钳位,保持 [LocalGroundingService] 契约(坐标只在端侧产生,
 * gateway 永不下发 x/y)不变。
 *
 * @param endpointUrl  Local llama.cpp server URL(与规划器同一服务)。
 * @param modelPath    Absolute path to the GGUF LLM weight file(供服务端定位权重;空 = 服务端自寻)。
 * @param mmprojPath   Absolute path to the mmproj vision-projector file(服务端 `--mmproj` 所需;
 *                     空 = 服务端自寻)。缺 mmproj 的服务无法处理截图 —— 这是旧栈的致命缺口,
 *                     故在此显式建模。
 * @param timeoutMs    HTTP connect/read timeout in milliseconds.
 */
class VlmGroundingEngine(
    private val endpointUrl: String = "http://127.0.0.1:8080",
    val modelPath: String = "",
    val mmprojPath: String = "",
    private val timeoutMs: Int = 15_000
) : LocalGroundingService {

    private val gson = Gson()
    private var modelLoaded = false

    /** Stores the last structured warmup failure for diagnostics; null when loaded. */
    @Volatile
    var lastWarmupResult: WarmupResult = WarmupResult.failure(
        WarmupResult.WarmupStage.HEALTH_CHECK, "loadModel not yet called"
    )
        private set

    private companion object {
        private const val COMPLETIONS_PATH = "/v1/chat/completions"
        private const val HEALTH_PATH = "/health"
        private const val MODEL_NAME = "mai-ui-2b"

        /** 定位调用生成的 token 很少(单个 JSON 对象),小预算即可,降低尾延迟。 */
        private const val MAX_TOKENS = 96

        private const val SYSTEM_PROMPT =
            "You are a GUI grounding engine. You are given a phone screenshot and a target " +
            "described in natural language. Reply with ONLY a JSON object, no prose, no " +
            "markdown fence, in exactly this format: " +
            "{\"x\":<int>,\"y\":<int>,\"confidence\":<float 0..1>,\"element\":\"<short description>\"} " +
            "where (x, y) is the best click point for the target in absolute pixels of the " +
            "given screenshot."

        // Minimal 1×1 white JPEG for dry-run grounding validation(继承自旧引擎)。
        private const val DRY_RUN_SCREENSHOT_B64 =
            "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a" +
                "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFgABAQEAAAAAAAAA" +
                "AAAAAAAAAAf/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAA/ACoAAA=="
    }

    override fun loadModel(): Boolean {
        val result = warmupWithResult()
        return result.success
    }

    override fun unloadModel() {
        modelLoaded = false
    }

    override fun isModelLoaded(): Boolean = modelLoaded

    /**
     * Pre-warms the shared llama.cpp server for grounding use.
     * Prefer [warmupWithResult] for structured failure reporting.
     */
    override fun prewarm(): Boolean = warmupWithResult().success

    /**
     * Hardened warmup that validates:
     * 1. Health endpoint reachability.
     * 2. Dry-run grounding round-trip with a 1×1 synthetic image(经真实多模态路径,
     *    因此也间接验证了服务端带 mmproj —— 纯文本服务会在此阶段失败)。
     * 3. Valid response shape (parsable x and y fields present).
     *
     * Sets [modelLoaded] and [lastWarmupResult] as a side-effect.
     */
    override fun warmupWithResult(): WarmupResult {
        // Stage 1: health check
        if (!pingEndpoint()) {
            val result = WarmupResult.failure(
                WarmupResult.WarmupStage.HEALTH_CHECK,
                "VLM grounding endpoint not reachable at $endpointUrl"
            )
            modelLoaded = false
            lastWarmupResult = result
            return result
        }

        // Stage 2: dry-run grounding with a minimal synthetic screenshot
        val responseText = try {
            httpPost(buildRequestJson("dry-run", DRY_RUN_SCREENSHOT_B64, 1, 1))
        } catch (e: Exception) {
            val result = WarmupResult.failure(
                WarmupResult.WarmupStage.DRY_RUN_INFERENCE,
                "VLM dry-run grounding failed: ${e.message}"
            )
            modelLoaded = false
            lastWarmupResult = result
            return result
        }

        // Stage 3: response shape validation — coordinates must be parsable
        val parsed = parseResponse(responseText, width = 1, height = 1)
        if (parsed.error != null) {
            val result = WarmupResult.failure(
                WarmupResult.WarmupStage.RESPONSE_VALIDATION,
                "VLM dry-run grounding response invalid: ${parsed.error}"
            )
            modelLoaded = false
            lastWarmupResult = result
            return result
        }

        val result = WarmupResult.success()
        modelLoaded = true
        lastWarmupResult = result
        return result
    }

    override fun ground(
        intent: String,
        screenshotBase64: String,
        width: Int,
        height: Int
    ): LocalGroundingService.GroundingResult {
        return try {
            val responseText = httpPost(buildRequestJson(intent, screenshotBase64, width, height))
            parseResponse(responseText, width, height)
        } catch (e: IOException) {
            LocalGroundingService.GroundingResult(
                x = 0,
                y = 0,
                confidence = 0f,
                element_description = "",
                error = "VLM grounding failed: ${e.message}"
            )
        } catch (e: Exception) {
            LocalGroundingService.GroundingResult(
                x = 0,
                y = 0,
                confidence = 0f,
                element_description = "",
                error = "VLM grounding error: ${e.message}"
            )
        }
    }

    /**
     * Builds an OpenAI-compatible chat request: system prompt + user turn carrying the
     * intent text (with真实屏幕尺寸,供模型换算绝对坐标) and the screenshot as an
     * `image_url` data URI —— 与 [com.ufo.galaxy.planner.VlmPlanner] 完全同构的请求形状,
     * 因此复用同一个 llama.cpp 服务与其 mmproj 视觉链路。
     */
    private fun buildRequestJson(
        intent: String,
        screenshotBase64: String,
        width: Int,
        height: Int
    ): String {
        val messages = JsonArray()

        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", SYSTEM_PROMPT)
        })

        val userContent = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty(
                    "text",
                    "Screenshot size: ${width}x${height} px. Target: $intent"
                )
            })
            add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply {
                    addProperty("url", "data:image/jpeg;base64,$screenshotBase64")
                })
            })
        }
        messages.add(JsonObject().apply {
            addProperty("role", "user")
            add("content", userContent)
        })

        val request = JsonObject().apply {
            addProperty("model", MODEL_NAME)
            add("messages", messages)
            addProperty("max_tokens", MAX_TOKENS)
            addProperty("temperature", 0.0)
        }
        return gson.toJson(request)
    }

    private fun httpPost(requestJson: String): String {
        val url = URL("$endpointUrl$COMPLETIONS_PATH")
        val conn = (url.openConnection() as? HttpURLConnection)
            ?: throw IllegalArgumentException("Only HTTP/HTTPS URLs are supported")
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.outputStream.use { it.write(requestJson.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                throw IOException("HTTP $code from VLM endpoint")
            }
            return conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Parses the chat completion into a [LocalGroundingService.GroundingResult].
     *
     * 解析链:choices[0].message.content → 剥离可能的 ```json 围栏 → JSON 对象 →
     * x/y 取整并钳位到 [0, width/height)。任何一步失败都返回带 error 的零坐标结果,
     * 交由上层 GroundingFallbackLadder 处理 —— 与旧引擎的失败语义保持一致。
     */
    private fun parseResponse(
        responseText: String,
        width: Int,
        height: Int
    ): LocalGroundingService.GroundingResult {
        val content = try {
            gson.fromJson(responseText, JsonObject::class.java)
                ?.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
        } catch (e: Exception) {
            null
        } ?: return LocalGroundingService.GroundingResult(
            x = 0, y = 0, confidence = 0f,
            element_description = "",
            error = "VLM: missing choices[0].message.content in response"
        )

        // 剥离 ```json ... ``` / ``` ... ``` 围栏与首尾空白;再截取首个 {...} 对象,
        // 容忍模型偶发在 JSON 前后加语句的情况。
        val stripped = content
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return LocalGroundingService.GroundingResult(
                x = 0, y = 0, confidence = 0f,
                element_description = "",
                error = "VLM: no JSON object in grounding reply"
            )
        }

        return try {
            val root = gson.fromJson(stripped.substring(start, end + 1), JsonObject::class.java)
            val rawX = root.get("x")?.asDouble
                ?: return LocalGroundingService.GroundingResult(
                    x = 0, y = 0, confidence = 0f,
                    element_description = "",
                    error = "VLM: missing x coordinate in grounding reply"
                )
            val rawY = root.get("y")?.asDouble
                ?: return LocalGroundingService.GroundingResult(
                    x = 0, y = 0, confidence = 0f,
                    element_description = "",
                    error = "VLM: missing y coordinate in grounding reply"
                )
            LocalGroundingService.GroundingResult(
                x = rawX.toInt().coerceIn(0, (width - 1).coerceAtLeast(0)),
                y = rawY.toInt().coerceIn(0, (height - 1).coerceAtLeast(0)),
                confidence = root.get("confidence")?.asFloat ?: 0f,
                element_description = root.get("element")?.asString ?: ""
            )
        } catch (e: JsonSyntaxException) {
            LocalGroundingService.GroundingResult(
                x = 0, y = 0, confidence = 0f,
                element_description = "",
                error = "VLM: failed to parse grounding JSON: ${e.message}"
            )
        }
    }

    private fun pingEndpoint(): Boolean {
        return try {
            val url = URL("$endpointUrl$HEALTH_PATH")
            val conn = (url.openConnection() as? HttpURLConnection)
                ?: return false
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 3_000
                conn.readTimeout = 3_000
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }
}
