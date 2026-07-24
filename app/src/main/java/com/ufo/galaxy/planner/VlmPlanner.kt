package com.ufo.galaxy.planner

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSyntaxException
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.inference.WarmupResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * MAI-UI-2B local task planner(统一 VLM 的"规划"职责,Qwen3-VL-2B 底座的 GUI 专精模型)。
 *
 * Communicates with a llama.cpp inference server running locally at
 * [endpointUrl] (default: http://127.0.0.1:8080). The server must accept
 * OpenAI-compatible POST /v1/chat/completions requests and return a JSON response
 * containing a "choices[0].message.content" field with a JSON action plan.
 *
 * Model:   mradermacher/MAI-UI-2B-GGUF (HuggingFace, Q4_K_M + mmproj f16;2B 档 GUI SOTA)
 * Runtime: llama.cpp —— 服务端启动必须同时传入 LLM 权重与 `--mmproj` 视觉投影文件
 *          ([com.ufo.galaxy.model.ModelAssetManager.vlmMmprojPath]),否则无法处理
 *          截图输入(旧 MobileVLM 组合正是因缺 mmproj 而"从未看见过屏幕")。
 * Model file path exposed via [modelPath] for the local inference server to locate weights.
 *
 * 定位(grounding)由 [com.ufo.galaxy.grounding.VlmGroundingEngine] 调用**同一个**
 * llama.cpp 服务完成 —— 单模型双职,手机只驻留一份 2B 权重。
 *
 * Expected plan JSON from model:
 * ```json
 * {"steps":[{"action_type":"tap","intent":"click login button","parameters":{}}]}
 * ```
 *
 * All coordinates are excluded from the plan; the grounding engine resolves them.
 *
 * @param endpointUrl   Local inference server URL.
 * @param modelPath     Absolute path to the GGUF model file (passed to server at startup).
 *                      Empty string means the server locates the model itself.
 * @param maxTokens     Maximum tokens the model may generate per call.
 * @param temperature   Sampling temperature (lower = more deterministic).
 * @param timeoutMs     HTTP connect/read timeout in milliseconds.
 * @param maxRetries    Number of additional attempts after a transient failure.
 */
class VlmPlanner(
    private val endpointUrl: String = "http://127.0.0.1:8080",
    val modelPath: String = "",
    private val maxTokens: Int = 512,
    private val temperature: Double = 0.1,
    private val timeoutMs: Int = 30_000,
    private val maxRetries: Int = 1
) : LocalPlannerService {

    private val gson = Gson()
    private var modelLoaded = false

    /** Stores the last structured warmup failure for diagnostics; null when loaded. */
    @Volatile
    var lastWarmupResult: WarmupResult = WarmupResult.failure(
        WarmupResult.WarmupStage.HEALTH_CHECK, "loadModel not yet called"
    )
        private set

    companion object {
        private const val COMPLETIONS_PATH = "/v1/chat/completions"
        private const val HEALTH_PATH = "/health"
        private const val MODEL_NAME = "mai-ui-2b"

        private const val SYSTEM_PROMPT =
            "You are a mobile GUI agent. " +
            "Given a task goal and optional screen image, " +
            "produce a JSON action plan in this format: " +
            "{\"steps\":[{" +
            "\"action_type\":\"tap|scroll|type|open_app|back|home\"," +
            "\"intent\":\"<natural language target description>\"," +
            "\"parameters\":{}" +
            "}]}. " +
            "Do not include x/y screen coordinates; describe the target intent only."
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
     * Pre-warms the VLM llama.cpp inference server by pinging /health and sending a minimal
     * 1-token dry-run completion request to bring the model weights into active memory.
     * Returns true if the server is reachable and responds to the dry-run.
     *
     * Prefer [warmupWithResult] for structured failure reporting.
     */
    override fun prewarm(): Boolean = warmupWithResult().success

    /**
     * Hardened warmup that validates:
     * 1. Health endpoint reachability.
     * 2. Dry-run inference round-trip.
     * 3. Valid response shape (choices[0].message.content present).
     *
     * Sets [modelLoaded] and [lastWarmupResult] as a side-effect.
     */
    override fun warmupWithResult(): WarmupResult {
        // Stage 1: health check
        if (!pingEndpoint()) {
            val result = WarmupResult.failure(
                WarmupResult.WarmupStage.HEALTH_CHECK,
                "VLM health endpoint not reachable at $endpointUrl"
            )
            modelLoaded = false
            lastWarmupResult = result
            return result
        }

        // Stage 2: dry-run inference
        val responseText = try {
            httpPost(buildDryRunRequestJson())
        } catch (e: Exception) {
            val result = WarmupResult.failure(
                WarmupResult.WarmupStage.DRY_RUN_INFERENCE,
                "VLM dry-run inference failed: ${e.message}"
            )
            modelLoaded = false
            lastWarmupResult = result
            return result
        }

        // Stage 3: response shape validation
        val content = try {
            val root = gson.fromJson(responseText, JsonObject::class.java)
            root?.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
        } catch (e: Exception) {
            null
        }

        if (content == null) {
            val result = WarmupResult.failure(
                WarmupResult.WarmupStage.RESPONSE_VALIDATION,
                "VLM dry-run response missing choices[0].message.content"
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

    override fun plan(
        goal: String,
        constraints: List<String>,
        screenshotBase64: String?
    ): LocalPlannerService.PlanResult = plan(goal, constraints, screenshotBase64, null)

    /**
     * 双通道规划:截图 + 无障碍树元素清单同帧注入 prompt(同时在场、综合判断)。
     * structuredContext 为空时与旧版行为一致。
     */
    override fun plan(
        goal: String,
        constraints: List<String>,
        screenshotBase64: String?,
        structuredContext: String?
    ): LocalPlannerService.PlanResult {
        val prompt = buildPrompt(goal, constraints, history = emptyList(), structuredContext)
        return callModelWithRetry(prompt, screenshotBase64)
    }

    override fun replan(
        goal: String,
        constraints: List<String>,
        failedStep: LocalPlannerService.PlanStep,
        error: String,
        screenshotBase64: String?
    ): LocalPlannerService.PlanResult {
        val history = listOf(
            "Failed step: action=${failedStep.action_type} intent=\"${failedStep.intent}\" error=$error"
        )
        val prompt = buildPrompt(goal, constraints, history)
        return callModelWithRetry(prompt, screenshotBase64)
    }

    private fun buildPrompt(
        goal: String,
        constraints: List<String>,
        history: List<String>,
        structuredContext: String? = null
    ): String = buildString {
        append("Goal: $goal\n")
        if (constraints.isNotEmpty()) {
            append("Constraints: ${constraints.joinToString("; ")}\n")
        }
        if (history.isNotEmpty()) {
            append("History: ${history.joinToString(" | ")}\n")
        }
        // 结构化通道:与截图同帧的无障碍树元素清单,给规划提供确定性的屏幕语义。
        if (!structuredContext.isNullOrBlank()) {
            append("Known screen elements (accessibility tree):\n$structuredContext\n")
        }
        append("Produce a JSON action plan.")
    }

    private fun callModelWithRetry(
        prompt: String,
        screenshotBase64: String?
    ): LocalPlannerService.PlanResult {
        var lastError = "VLM inference failed"
        repeat(maxRetries + 1) { attempt ->
            try {
                val requestJson = buildRequestJson(prompt, screenshotBase64)
                val responseText = httpPost(requestJson)
                return parseResponse(responseText)
            } catch (e: Exception) {
                lastError = "VLM inference failed (attempt ${attempt + 1}): ${e.message}"
            }
        }
        return LocalPlannerService.PlanResult(steps = emptyList(), error = lastError)
    }

    private fun buildRequestJson(prompt: String, screenshotBase64: String?): String {
        val messages = JsonArray()

        val systemMsg = JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", SYSTEM_PROMPT)
        }
        messages.add(systemMsg)

        val userContent: Any = if (screenshotBase64 != null) {
            JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", prompt)
                })
                add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", "data:image/jpeg;base64,$screenshotBase64")
                    })
                })
            }
        } else {
            JsonPrimitive(prompt)
        }

        val userMsg = JsonObject().apply {
            addProperty("role", "user")
            add("content", gson.toJsonTree(userContent))
        }
        messages.add(userMsg)

        val request = JsonObject().apply {
            addProperty("model", MODEL_NAME)
            add("messages", messages)
            addProperty("max_tokens", maxTokens)
            addProperty("temperature", temperature)
            if (modelPath.isNotEmpty()) addProperty("model_path", modelPath)
        }

        return gson.toJson(request)
    }

    private fun httpPost(requestJson: String): String {
        val url = URL("$endpointUrl$COMPLETIONS_PATH")
        val conn = url.openConnection() as HttpURLConnection
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

    private fun parseResponse(responseText: String): LocalPlannerService.PlanResult {
        return try {
            val root = gson.fromJson(responseText, JsonObject::class.java)
            val content = root
                .getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?: return LocalPlannerService.PlanResult(
                    steps = emptyList(),
                    error = "VLM: missing content in response"
                )

            val jsonContent = extractJsonBlock(content)
            val planJson = gson.fromJson(jsonContent, JsonObject::class.java)
            val stepsArray = planJson.getAsJsonArray("steps")
                ?: return LocalPlannerService.PlanResult(
                    steps = emptyList(),
                    error = "VLM: no steps array in plan"
                )

            val steps = stepsArray.map { el ->
                val obj = el.asJsonObject
                LocalPlannerService.PlanStep(
                    action_type = obj.get("action_type")?.asString ?: "tap",
                    intent = obj.get("intent")?.asString ?: "",
                    parameters = obj.getAsJsonObject("parameters")
                        ?.entrySet()
                        ?.associate { (k, v) -> k to v.asString }
                        ?: emptyMap()
                )
            }
            LocalPlannerService.PlanResult(steps = steps)
        } catch (e: JsonSyntaxException) {
            LocalPlannerService.PlanResult(
                steps = emptyList(),
                error = "VLM: failed to parse plan JSON: ${e.message}"
            )
        } catch (e: Exception) {
            LocalPlannerService.PlanResult(
                steps = emptyList(),
                error = "VLM: unexpected parse error: ${e.message}"
            )
        }
    }

    /**
     * Extracts JSON content from model output that may be wrapped in a markdown code block.
     */
    private fun extractJsonBlock(text: String): String {
        val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val match = codeBlockRegex.find(text)
        if (match != null) return match.groupValues[1].trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text
    }

    private fun pingEndpoint(): Boolean {
        return try {
            val url = URL("$endpointUrl$HEALTH_PATH")
            val conn = url.openConnection() as HttpURLConnection
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

    /**
     * Builds a minimal 1-token completion request for pre-warming the inference server.
     * The response content is discarded; only the round-trip is needed.
     */
    private fun buildDryRunRequestJson(): String {
        val messages = JsonArray()
        messages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", "ok")
        })
        val request = JsonObject().apply {
            addProperty("model", MODEL_NAME)
            add("messages", messages)
            addProperty("max_tokens", 1)
            addProperty("temperature", temperature)
        }
        return gson.toJson(request)
    }
}
