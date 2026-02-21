package com.ufo.galaxy.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.util.Base64
import android.util.Log
import android.os.Handler
import android.os.Looper
import com.ufo.galaxy.config.GalaxyConfig
import com.ufo.galaxy.utils.ScreenshotHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * UFO Galaxy - 融合视觉理解引擎
 *
 * 核心理念：OCR 和 GUI 理解不是两个独立步骤，而是一次视觉理解的两个输出维度。
 * DeepSeek OCR 2 作为 VLM，天然具备同时输出文本和 UI 结构的能力。
 *
 * 融合管线：
 *   截图 → DeepSeek OCR 2 (一次调用) → UnifiedVisionResult
 *                                         ├── OCR 文本 (rawText, textBlocks)
 *                                         ├── UI 元素树 (uiElements)
 *                                         ├── 语义理解 (sceneDescription)
 *                                         └── 动作建议 (suggestedActions)
 *                                              ↓
 *                                         动作决策 + 元素交互
 *                                              ↓
 *                                    复杂场景降级到 Gemini/GPT-4V
 *
 * 引擎优先级:
 *   1. DeepSeek OCR 2 融合模式 (通过服务端 VisionPipeline)
 *   2. DeepSeek OCR 2 融合模式 (直接调用 Novita.ai API)
 *   3. 分离模式: ML Kit OCR + 规则匹配 (离线降级)
 *
 * 版本：3.0.0 (GUI-OCR Fusion)
 * 日期：2026-02-06
 */
class GUIUnderstanding(private val context: Context) {

    companion object {
        private const val TAG = "GUIUnderstanding"

        // 动作类型 (AgentCPM-GUI 风格)
        const val ACTION_POINT = "POINT"
        const val ACTION_SCROLL = "SCROLL"
        const val ACTION_TYPE = "TYPE"
        const val ACTION_PRESS = "PRESS"
        const val ACTION_STATUS = "STATUS"

        // 融合引擎模式
        const val ENGINE_FUSED_SERVER = "fused_server"     // 服务端 VisionPipeline 融合
        const val ENGINE_FUSED_DIRECT = "fused_direct"     // 直接 API 融合
        const val ENGINE_SEPARATED = "separated"           // 分离模式（降级）
        const val ENGINE_AUTO = "auto"                     // 自动选择

        // 视觉理解模式
        const val MODE_FULL = "full"                       // 完整融合：OCR + GUI + 语义
        const val MODE_OCR_ONLY = "ocr_only"               // 仅 OCR
        const val MODE_GUI_ONLY = "gui_only"               // 仅 GUI 元素
        const val MODE_ACTION = "action"                   // 动作决策（含指令）
        const val MODE_DOCUMENT = "document"               // 文档转 Markdown
        const val MODE_TABLE = "table"                     // 表格提取
    }

    // ============================================================================
    // 配置
    // ============================================================================

    private var serverUrl: String = ""
    private var apiKey: String = ""

    // DeepSeek OCR 2 直接 API 配置
    private var deepseekApiKey: String = ""
    private var deepseekApiBase: String = "https://api.novita.ai/v3/openai"
    private var deepseekModel: String = "deepseek/deepseek-ocr2"

    // WebSocket 回调
    private var webSocketSender: ((JSONObject) -> Boolean)? = null

    // 截图辅助类
    private val screenshotHelper = ScreenshotHelper(context)
    private var mediaProjection: MediaProjection? = null

    // 引擎选择
    private var engineMode: String = ENGINE_AUTO

    // 统计
    private var stats = VisionStats()

    // 缓存：避免对同一截图重复调用
    private var lastScreenHash: Int = 0
    private var lastVisionResult: UnifiedVisionResult? = null

    // ============================================================================
    // 配置方法
    // ============================================================================

    fun configure(serverUrl: String, apiKey: String) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.apiKey = apiKey
    }

    fun configureDeepSeekOCR2(apiKey: String, apiBase: String = "", model: String = "") {
        this.deepseekApiKey = apiKey
        if (apiBase.isNotEmpty()) this.deepseekApiBase = apiBase
        if (model.isNotEmpty()) this.deepseekModel = model
        Log.i(TAG, "DeepSeek OCR 2 configured: base=$deepseekApiBase, model=$deepseekModel")
    }

    fun setEngineMode(mode: String) {
        this.engineMode = mode
        Log.i(TAG, "Engine mode set to: $mode")
    }

    fun setMediaProjection(projection: MediaProjection) {
        this.mediaProjection = projection
    }

    fun setWebSocketSender(sender: (JSONObject) -> Boolean) {
        this.webSocketSender = sender
    }

    /**
     * 从 GalaxyConfig 自动加载配置（包括 DeepSeek OCR 2 API Key）
     * 应在 Application 或 Activity 初始化时调用
     */
    fun loadConfigFromGalaxyConfig() {
        try {
            val config = GalaxyConfig.getInstance(context)
            
            // 加载服务器配置
            val serverUrl = config.getServerUrl()
            if (serverUrl.isNotEmpty()) {
                configure(serverUrl, "")
            }
            
            // 加载 DeepSeek OCR 2 配置
            val ocrApiKey = config.getDeepSeekOCR2ApiKey()
            if (ocrApiKey.isNotEmpty()) {
                configureDeepSeekOCR2(
                    apiKey = ocrApiKey,
                    apiBase = config.getDeepSeekOCR2ApiBase(),
                    model = config.getDeepSeekOCR2Model()
                )
            }
            
            // 加载引擎模式
            val engine = config.getOCREngine()
            if (engine.isNotEmpty()) {
                setEngineMode(when(engine) {
                    "deepseek_ocr2" -> ENGINE_AUTO
                    "server" -> ENGINE_FUSED_SERVER
                    "direct" -> ENGINE_FUSED_DIRECT
                    "local" -> ENGINE_SEPARATED
                    else -> ENGINE_AUTO
                })
            }
            
            Log.i(TAG, "Config loaded from GalaxyConfig: " +
                "server=$serverUrl, ocr_key=${if (ocrApiKey.isNotEmpty()) "configured" else "empty"}, " +
                "engine=$engine")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config from GalaxyConfig", e)
        }
    }

    // ============================================================================
    // 核心融合接口：一次调用，同时获取 OCR + GUI + 语义
    // ============================================================================

    /**
     * 统一视觉理解 - 核心融合方法
     *
     * 一次截图 → 一次 VLM 调用 → 同时获取 OCR 文本、UI 元素、语义描述。
     * 这是所有其他方法的底层基础。
     *
     * @param bitmap 截图
     * @param mode 理解模式 (full, ocr_only, gui_only, action, document, table)
     * @param instruction 用户指令（仅 action 模式需要）
     * @param useCache 是否使用缓存（同一截图不重复调用）
     * @return UnifiedVisionResult 融合结果
     */
    suspend fun understand(
        bitmap: Bitmap,
        mode: String = MODE_FULL,
        instruction: String = "",
        useCache: Boolean = true
    ): UnifiedVisionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        stats.totalRequests++

        // 缓存检查
        val screenHash = bitmap.hashCode() + mode.hashCode() + instruction.hashCode()
        if (useCache && screenHash == lastScreenHash && lastVisionResult != null) {
            Log.d(TAG, "Using cached vision result")
            stats.cacheHits++
            return@withContext lastVisionResult!!
        }

        try {
            val result = when (engineMode) {
                ENGINE_FUSED_SERVER -> understandFusedServer(bitmap, mode, instruction)
                ENGINE_FUSED_DIRECT -> understandFusedDirect(bitmap, mode, instruction)
                ENGINE_SEPARATED -> understandSeparated(bitmap, mode, instruction)
                ENGINE_AUTO -> understandAuto(bitmap, mode, instruction)
                else -> understandAuto(bitmap, mode, instruction)
            }

            val latency = System.currentTimeMillis() - startTime
            stats.totalLatencyMs += latency
            if (result.success) stats.successCount++

            // 更新缓存
            lastScreenHash = screenHash
            lastVisionResult = result

            Log.i(TAG, "Vision understand: engine=${result.engine}, mode=$mode, " +
                    "ocr_blocks=${result.textBlocks.size}, ui_elements=${result.uiElements.size}, " +
                    "latency=${latency}ms")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Vision understand failed: ${e.message}")
            UnifiedVisionResult(
                success = false,
                engine = "none",
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * 自动选择最佳引擎
     */
    private suspend fun understandAuto(
        bitmap: Bitmap, mode: String, instruction: String
    ): UnifiedVisionResult {
        // 优先级 1: 服务端 VisionPipeline 融合模式
        if (serverUrl.isNotEmpty()) {
            try {
                val result = understandFusedServer(bitmap, mode, instruction)
                if (result.success) return result
                Log.w(TAG, "Server fused mode failed: ${result.error}")
            } catch (e: Exception) {
                Log.w(TAG, "Server fused mode exception: ${e.message}")
            }
        }

        // 优先级 2: 直接 API 融合模式
        if (deepseekApiKey.isNotEmpty()) {
            try {
                val result = understandFusedDirect(bitmap, mode, instruction)
                if (result.success) return result
                Log.w(TAG, "Direct fused mode failed: ${result.error}")
            } catch (e: Exception) {
                Log.w(TAG, "Direct fused mode exception: ${e.message}")
            }
        }

        // 优先级 3: 分离模式（离线降级）
        return understandSeparated(bitmap, mode, instruction)
    }

    // ============================================================================
    // 融合模式 1: 通过服务端 VisionPipeline
    // ============================================================================

    /**
     * 通过服务端 VisionPipeline 进行融合视觉理解
     *
     * 服务端的 VisionPipeline 使用 DeepSeek OCR 2 + Gemini/Qwen3-VL 进行融合分析，
     * 一次调用返回 OCR + GUI + 语义的完整结果。
     */
    private suspend fun understandFusedServer(
        bitmap: Bitmap, mode: String, instruction: String
    ): UnifiedVisionResult {
        if (serverUrl.isEmpty()) {
            return UnifiedVisionResult(success = false, engine = "fused_server",
                error = "Server URL not configured")
        }

        val base64Image = bitmapToBase64(bitmap)
        val screenSize = screenshotHelper.getScreenSize()

        val requestBody = JSONObject().apply {
            put("image", base64Image)
            put("mode", mode)
            put("instruction", instruction)
            put("screen_width", screenSize.first)
            put("screen_height", screenSize.second)
            put("fusion", true)  // 标记为融合模式
        }

        val response = sendRequest("$serverUrl/api/vision/understand", requestBody)
            ?: return UnifiedVisionResult(success = false, engine = "fused_server",
                error = "Server no response")

        return parseServerVisionResult(response, "fused_server")
    }

    /**
     * 解析服务端返回的融合视觉结果
     */
    private fun parseServerVisionResult(json: JSONObject, engine: String): UnifiedVisionResult {
        val success = json.optBoolean("success", false)
        if (!success) {
            return UnifiedVisionResult(success = false, engine = engine,
                error = json.optString("error", "Unknown error"))
        }

        val data = json.optJSONObject("data") ?: json

        // 解析 OCR 文本块
        val textBlocks = mutableListOf<TextBlock>()
        data.optJSONArray("text_blocks")?.let { blocks ->
            for (i in 0 until blocks.length()) {
                val item = blocks.getJSONObject(i)
                textBlocks.add(TextBlock(
                    text = item.getString("text"),
                    x = item.optInt("x", 0),
                    y = item.optInt("y", 0),
                    width = item.optInt("width", 0),
                    height = item.optInt("height", 0),
                    confidence = item.optDouble("confidence", 1.0).toFloat()
                ))
            }
        }

        // 解析 UI 元素
        val uiElements = mutableListOf<UIElement>()
        data.optJSONArray("ui_elements")?.let { elements ->
            for (i in 0 until elements.length()) {
                val item = elements.getJSONObject(i)
                uiElements.add(UIElement(
                    id = item.optString("id", "elem_$i"),
                    type = item.optString("type", "unknown"),
                    text = item.optString("text", ""),
                    bounds = parseBounds(item.optJSONObject("bounds")),
                    clickable = item.optBoolean("clickable", false),
                    scrollable = item.optBoolean("scrollable", false),
                    editable = item.optBoolean("editable", false),
                    confidence = item.optDouble("confidence", 1.0).toFloat()
                ))
            }
        }

        // 解析动作建议
        val suggestedActions = mutableListOf<GUIAction>()
        data.optJSONArray("suggested_actions")?.let { actions ->
            for (i in 0 until actions.length()) {
                val item = actions.getJSONObject(i)
                suggestedActions.add(parseGUIAction(item))
            }
        }

        return UnifiedVisionResult(
            success = true,
            engine = engine,
            rawText = data.optString("raw_text", textBlocks.joinToString("\n") { it.text }),
            textBlocks = textBlocks,
            uiElements = uiElements,
            sceneDescription = data.optString("scene_description", ""),
            suggestedActions = suggestedActions,
            serverLatencyMs = data.optDouble("latency_ms", 0.0)
        )
    }

    // ============================================================================
    // 融合模式 2: 直接调用 DeepSeek OCR 2 API
    // ============================================================================

    /**
     * 直接调用 DeepSeek OCR 2 API 进行融合视觉理解
     *
     * 通过精心设计的 prompt，让 DeepSeek OCR 2 在一次调用中同时输出：
     * - OCR 文本
     * - UI 元素列表（类型、位置、文本）
     * - 场景描述
     * - 动作建议（如果有指令）
     */
    private suspend fun understandFusedDirect(
        bitmap: Bitmap, mode: String, instruction: String
    ): UnifiedVisionResult {
        if (deepseekApiKey.isEmpty()) {
            return UnifiedVisionResult(success = false, engine = "fused_direct",
                error = "DeepSeek API key not configured")
        }

        val base64Image = bitmapToBase64(bitmap)
        val prompt = buildFusedPrompt(mode, instruction)

        val requestBody = JSONObject().apply {
            put("model", deepseekModel)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        })
                    })
                })
            })
            put("max_tokens", 8192)
            put("temperature", 0.1)
        }

        val apiUrl = "${deepseekApiBase}/chat/completions"
        val response = sendRequestWithAuth(apiUrl, requestBody, deepseekApiKey)
            ?: return UnifiedVisionResult(success = false, engine = "fused_direct",
                error = "DeepSeek API no response")

        val content = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "") ?: ""

        if (content.isEmpty()) {
            return UnifiedVisionResult(success = false, engine = "fused_direct",
                error = "Empty response from DeepSeek OCR 2")
        }

        return parseFusedVLMResponse(content, mode, "fused_direct")
    }

    /**
     * 构建融合 prompt - 让 VLM 在一次调用中输出 OCR + GUI + 语义
     */
    private fun buildFusedPrompt(mode: String, instruction: String): String {
        return when (mode) {
            MODE_FULL -> buildString {
                append("<image>\n")
                append("Analyze this screenshot comprehensively. Output a JSON object with:\n")
                append("1. \"raw_text\": All visible text on screen\n")
                append("2. \"ui_elements\": Array of UI elements, each with {\"type\", \"text\", \"bounds\":{\"x\",\"y\",\"w\",\"h\"}, \"clickable\", \"editable\"}\n")
                append("3. \"scene_description\": Brief description of the current screen/app state\n")
                if (instruction.isNotEmpty()) {
                    append("4. \"suggested_action\": Based on instruction \"$instruction\", suggest {\"action\",\"target\",\"thought\"}\n")
                }
                append("Output ONLY valid JSON, no markdown formatting.")
            }
            MODE_OCR_ONLY -> "<image>\nFree OCR. Extract all visible text."
            MODE_GUI_ONLY -> buildString {
                append("<image>\n")
                append("Identify all UI elements in this screenshot. ")
                append("Output a JSON array, each element: {\"type\", \"text\", \"bounds\":{\"x\",\"y\",\"w\",\"h\"}, \"clickable\", \"scrollable\", \"editable\"}\n")
                append("Element types: button, text, input, image, icon, menu, list, switch, checkbox, slider, tab, link\n")
                append("Output ONLY valid JSON array.")
            }
            MODE_ACTION -> buildString {
                append("<image>\n")
                append("User instruction: \"$instruction\"\n\n")
                append("Analyze the screenshot and determine the best action to fulfill the instruction.\n")
                append("Output a JSON object with:\n")
                append("1. \"thought\": Your reasoning process\n")
                append("2. \"action\": One of POINT/SCROLL/TYPE/PRESS/STATUS\n")
                append("3. For POINT: include \"x\" and \"y\" coordinates\n")
                append("4. For SCROLL: include \"direction\" (up/down/left/right)\n")
                append("5. For TYPE: include \"text\" to type\n")
                append("6. For PRESS: include \"key\" (back/home/recent)\n")
                append("7. \"ui_elements\": Array of relevant UI elements found\n")
                append("8. \"raw_text\": All visible text near the target area\n")
                append("Output ONLY valid JSON.")
            }
            MODE_DOCUMENT -> "<image>\n<|grounding|>Convert the document to markdown format."
            MODE_TABLE -> "<image>\n<|grounding|>Extract all tables. Convert to markdown table format."
            else -> "<image>\nFree OCR. "
        }
    }

    /**
     * 解析 VLM 融合响应 - 从一次调用中提取 OCR + GUI + 语义
     */
    private fun parseFusedVLMResponse(
        content: String, mode: String, engine: String
    ): UnifiedVisionResult {
        // 尝试解析为 JSON
        val json = tryParseJson(content)

        if (json != null) {
            // 成功解析为 JSON - 提取融合结果
            val textBlocks = mutableListOf<TextBlock>()
            val uiElements = mutableListOf<UIElement>()
            val suggestedActions = mutableListOf<GUIAction>()

            // 提取 OCR 文本
            val rawText = json.optString("raw_text", content)

            // 提取 UI 元素
            json.optJSONArray("ui_elements")?.let { elements ->
                for (i in 0 until elements.length()) {
                    val item = elements.getJSONObject(i)
                    val bounds = item.optJSONObject("bounds")
                    val elem = UIElement(
                        id = "elem_$i",
                        type = item.optString("type", "unknown"),
                        text = item.optString("text", ""),
                        bounds = if (bounds != null) Rect(
                            bounds.optInt("x", 0),
                            bounds.optInt("y", 0),
                            bounds.optInt("x", 0) + bounds.optInt("w", 0),
                            bounds.optInt("y", 0) + bounds.optInt("h", 0)
                        ) else Rect(),
                        clickable = item.optBoolean("clickable", false),
                        scrollable = item.optBoolean("scrollable", false),
                        editable = item.optBoolean("editable", false),
                        confidence = 0.9f
                    )
                    uiElements.add(elem)

                    // 同时将有文本的 UI 元素转为 TextBlock
                    if (elem.text.isNotEmpty()) {
                        textBlocks.add(TextBlock(
                            text = elem.text,
                            x = elem.bounds.left,
                            y = elem.bounds.top,
                            width = elem.bounds.width(),
                            height = elem.bounds.height(),
                            confidence = elem.confidence
                        ))
                    }
                }
            }

            // 提取动作建议
            json.optJSONObject("suggested_action")?.let { action ->
                suggestedActions.add(parseGUIAction(action))
            }

            // 如果 action 模式，直接解析顶层动作
            if (mode == MODE_ACTION && json.has("action")) {
                suggestedActions.clear()
                suggestedActions.add(parseGUIAction(json))
            }

            return UnifiedVisionResult(
                success = true,
                engine = engine,
                rawText = rawText,
                textBlocks = textBlocks,
                uiElements = uiElements,
                sceneDescription = json.optString("scene_description", ""),
                suggestedActions = suggestedActions
            )
        } else {
            // 无法解析为 JSON - 作为纯文本 OCR 结果
            return UnifiedVisionResult(
                success = true,
                engine = engine,
                rawText = content,
                textBlocks = listOf(TextBlock(
                    text = content, x = 0, y = 0, width = 0, height = 0, confidence = 0.9f
                ))
            )
        }
    }

    // ============================================================================
    // 分离模式（离线降级）
    // ============================================================================

    /**
     * 分离模式：OCR 和 GUI 分析独立进行
     * 当 DeepSeek OCR 2 不可用时使用
     */
    private suspend fun understandSeparated(
        bitmap: Bitmap, mode: String, instruction: String
    ): UnifiedVisionResult {
        // 使用本地 ML Kit 做 OCR
        val textBlocks = performLocalOCR(bitmap)

        // 基于 OCR 结果进行简单的 GUI 元素推断
        val uiElements = inferUIElementsFromOCR(textBlocks)

        // 如果有指令，基于关键词匹配生成动作建议
        val suggestedActions = if (instruction.isNotEmpty()) {
            inferActionFromOCR(textBlocks, instruction)
        } else {
            emptyList()
        }

        return UnifiedVisionResult(
            success = textBlocks.isNotEmpty(),
            engine = "separated_local",
            rawText = textBlocks.joinToString("\n") { it.text },
            textBlocks = textBlocks,
            uiElements = uiElements,
            sceneDescription = "Local analysis (offline mode)",
            suggestedActions = suggestedActions,
            error = if (textBlocks.isEmpty()) "No text detected locally" else null
        )
    }

    /**
     * 本地 OCR (ML Kit)
     */
    private suspend fun performLocalOCR(bitmap: Bitmap): List<TextBlock> {
        // TODO: 实际 ML Kit 实现
        // val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        // val image = InputImage.fromBitmap(bitmap, 0)
        // val result = recognizer.process(image).await()
        // return result.textBlocks.map { block ->
        //     val rect = block.boundingBox ?: Rect()
        //     TextBlock(block.text, rect.left, rect.top, rect.width(), rect.height(), 0.8f)
        // }
        return emptyList()
    }

    /**
     * 从 OCR 结果推断 UI 元素
     */
    private fun inferUIElementsFromOCR(textBlocks: List<TextBlock>): List<UIElement> {
        return textBlocks.mapIndexed { index, block ->
            val type = inferElementType(block.text)
            UIElement(
                id = "inferred_$index",
                type = type,
                text = block.text,
                bounds = Rect(block.x, block.y,
                    block.x + block.width, block.y + block.height),
                clickable = type in listOf("button", "link", "tab", "menu"),
                scrollable = false,
                editable = type == "input",
                confidence = block.confidence * 0.7f  // 推断置信度降低
            )
        }
    }

    /**
     * 根据文本内容推断 UI 元素类型
     */
    private fun inferElementType(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.matches(Regex("(确定|取消|ok|cancel|submit|save|delete|edit|next|back|done|yes|no|登录|注册|搜索|发送).*")) -> "button"
            lower.contains("http") || lower.contains("www") -> "link"
            lower.length > 100 -> "text"
            lower.matches(Regex("\\d{1,2}:\\d{2}.*")) -> "status"
            else -> "text"
        }
    }

    /**
     * 从 OCR 结果和指令推断动作
     */
    private fun inferActionFromOCR(
        textBlocks: List<TextBlock>, instruction: String
    ): List<GUIAction> {
        val keywords = extractKeywords(instruction)
        val actions = mutableListOf<GUIAction>()

        for (block in textBlocks) {
            for (keyword in keywords) {
                if (block.text.contains(keyword, ignoreCase = true)) {
                    actions.add(GUIAction(
                        action = ACTION_POINT,
                        x = block.x + block.width / 2,
                        y = block.y + block.height / 2,
                        thought = "Found matching text: '${block.text}' for keyword: '$keyword'"
                    ))
                    break
                }
            }
        }

        return actions
    }

    // ============================================================================
    // 便捷方法（基于融合引擎）
    // ============================================================================

    /**
     * 执行 OCR - 从融合结果中提取 OCR 部分
     */
    suspend fun performOCR(
        bitmap: Bitmap,
        mode: String = MODE_OCR_ONLY
    ): OCRResult {
        val visionResult = understand(bitmap, mode)
        return OCRResult(
            textBlocks = visionResult.textBlocks,
            error = visionResult.error,
            engine = visionResult.engine,
            rawText = visionResult.rawText,
            mode = mode,
            serverLatencyMs = visionResult.serverLatencyMs
        )
    }

    /**
     * 分析 GUI 并生成动作 - 从融合结果中提取动作
     */
    suspend fun analyzeGUI(bitmap: Bitmap, instruction: String): GUIAction {
        val visionResult = understand(bitmap, MODE_ACTION, instruction)

        // 优先使用 VLM 建议的动作
        if (visionResult.suggestedActions.isNotEmpty()) {
            return visionResult.suggestedActions.first()
        }

        // 降级：从 UI 元素中匹配
        val keywords = extractKeywords(instruction)
        for (element in visionResult.uiElements) {
            if (element.clickable) {
                for (keyword in keywords) {
                    if (element.text.contains(keyword, ignoreCase = true)) {
                        return GUIAction(
                            action = ACTION_POINT,
                            x = element.bounds.centerX(),
                            y = element.bounds.centerY(),
                            thought = "Matched UI element: ${element.type} '${element.text}'"
                        )
                    }
                }
            }
        }

        return GUIAction(
            action = ACTION_STATUS,
            status = if (visionResult.success) "no_match" else "fail",
            thought = visionResult.error ?: "No matching element found for: $instruction"
        )
    }

    /**
     * 获取 UI 元素列表 - 从融合结果中提取
     */
    suspend fun getUIElements(bitmap: Bitmap): List<UIElement> {
        val visionResult = understand(bitmap, MODE_GUI_ONLY)
        return visionResult.uiElements
    }

    /**
     * 查找指定文本的 UI 元素
     */
    suspend fun findElement(bitmap: Bitmap, targetText: String): UIElement? {
        val visionResult = understand(bitmap, MODE_FULL)
        return visionResult.uiElements.firstOrNull { element ->
            element.text.contains(targetText, ignoreCase = true)
        }
    }

    /**
     * 查找指定类型的所有 UI 元素
     */
    suspend fun findElementsByType(bitmap: Bitmap, type: String): List<UIElement> {
        val visionResult = understand(bitmap, MODE_GUI_ONLY)
        return visionResult.uiElements.filter { it.type == type }
    }

    // ============================================================================
    // 截图 → WebSocket 传输
    // ============================================================================

    fun captureAndSendScreenshot(
        instruction: String = "",
        callback: (Boolean, String) -> Unit
    ) {
        val projection = mediaProjection
        if (projection == null) {
            callback(false, "MediaProjection not set")
            return
        }

        val sender = webSocketSender
        if (sender == null) {
            callback(false, "WebSocket sender not set")
            return
        }

        screenshotHelper.takeScreenshotWithMediaProjection(projection) { bitmap ->
            if (bitmap == null) {
                callback(false, "Screenshot capture failed")
                return@takeScreenshotWithMediaProjection
            }

            val base64Image = screenshotHelper.bitmapToBase64(bitmap, quality = 85)

            val message = JSONObject().apply {
                put("version", "3.0")
                put("type", "vision_request")
                put("timestamp", System.currentTimeMillis())
                put("image", base64Image)
                put("image_format", "jpeg")
                put("fusion", true)  // 请求融合模式
                put("screen_size", JSONObject().apply {
                    val (width, height) = screenshotHelper.getScreenSize()
                    put("width", width)
                    put("height", height)
                })
                if (instruction.isNotEmpty()) {
                    put("instruction", instruction)
                }
            }

            val success = sender(message)
            callback(success, if (success) "Screenshot sent" else "Failed to send")
        }
    }

    suspend fun captureAndSendScreenshotAsync(instruction: String = ""): Pair<Boolean, String> =
        suspendCancellableCoroutine { continuation ->
            captureAndSendScreenshot(instruction) { success, message ->
                continuation.resume(Pair(success, message))
            }
        }

    /**
     * 处理服务端返回的融合视觉结果
     */
    fun processVisionResponse(response: JSONObject): GUIAction {
        // 优先解析为融合结果
        if (response.has("ui_elements") || response.has("text_blocks")) {
            val fusedResult = parseServerVisionResult(response, "server_response")
            if (fusedResult.suggestedActions.isNotEmpty()) {
                return fusedResult.suggestedActions.first()
            }
        }

        // 降级：解析为简单动作
        return parseGUIAction(response)
    }

    // ============================================================================
    // 视觉理解 (多模态 LLM)
    // ============================================================================

    suspend fun understandWithVision(bitmap: Bitmap, prompt: String): VisionResult =
        withContext(Dispatchers.IO) {
            try {
                val base64Image = bitmapToBase64(bitmap)

                val requestBody = JSONObject().apply {
                    put("model", "vision")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", prompt)
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", "data:image/jpeg;base64,$base64Image")
                                    })
                                })
                            })
                        })
                    })
                    put("max_tokens", 1024)
                }

                val response = sendRequest("$serverUrl/v1/chat/completions", requestBody)

                val content = response?.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "") ?: ""

                VisionResult(content, null)
            } catch (e: Exception) {
                Log.e(TAG, "Vision understanding failed: ${e.message}")
                VisionResult("", e.message)
            }
        }

    // ============================================================================
    // 自主学习
    // ============================================================================

    fun recordAction(
        screenshot: Bitmap,
        instruction: String,
        action: GUIAction,
        success: Boolean
    ) {
        Log.d(TAG, "Recording action: $instruction -> ${action.action}, success=$success")
        stats.actionsRecorded++
    }

    suspend fun learnFromHistory(): Boolean {
        return true
    }

    // ============================================================================
    // 统计
    // ============================================================================

    fun getStats(): JSONObject {
        return JSONObject().apply {
            put("total_requests", stats.totalRequests)
            put("successful_requests", stats.successCount)
            put("cache_hits", stats.cacheHits)
            put("actions_recorded", stats.actionsRecorded)
            put("avg_latency_ms",
                if (stats.successCount > 0) stats.totalLatencyMs / stats.successCount else 0)
            put("current_engine", engineMode)
            put("deepseek_configured", deepseekApiKey.isNotEmpty())
            put("server_configured", serverUrl.isNotEmpty())
            put("fusion_enabled", true)
        }
    }

    // 兼容旧接口
    fun getOCRStats(): JSONObject = getStats()

    // ============================================================================
    // 工具方法
    // ============================================================================

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun extractKeywords(text: String): List<String> {
        return text.split(" ", "，", "。", "的", "点击", "打开", "输入")
            .filter { it.length > 1 }
    }

    private fun tryParseJson(text: String): JSONObject? {
        return try {
            // 尝试直接解析
            JSONObject(text)
        } catch (e: Exception) {
            try {
                // 尝试提取 JSON 块
                val jsonStart = text.indexOf('{')
                val jsonEnd = text.lastIndexOf('}')
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    JSONObject(text.substring(jsonStart, jsonEnd + 1))
                } else {
                    null
                }
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun parseBounds(json: JSONObject?): Rect {
        if (json == null) return Rect()
        return Rect(
            json.optInt("x", 0),
            json.optInt("y", 0),
            json.optInt("x", 0) + json.optInt("w", json.optInt("width", 0)),
            json.optInt("y", 0) + json.optInt("h", json.optInt("height", 0))
        )
    }

    private fun parseGUIAction(json: JSONObject): GUIAction {
        val action = json.optString("action", ACTION_STATUS)
        return when (action) {
            ACTION_POINT -> GUIAction(
                action = ACTION_POINT,
                x = json.optJSONArray("POINT")?.optInt(0) ?: json.optInt("x", 0),
                y = json.optJSONArray("POINT")?.optInt(1) ?: json.optInt("y", 0),
                duration = json.optLong("duration", 0),
                thought = json.optString("thought", "")
            )
            ACTION_SCROLL -> GUIAction(
                action = ACTION_SCROLL,
                direction = json.optString("direction", "down"),
                thought = json.optString("thought", "")
            )
            ACTION_TYPE -> GUIAction(
                action = ACTION_TYPE,
                text = json.optString("text", ""),
                thought = json.optString("thought", "")
            )
            ACTION_PRESS -> GUIAction(
                action = ACTION_PRESS,
                key = json.optString("key", ""),
                thought = json.optString("thought", "")
            )
            else -> GUIAction(
                action = ACTION_STATUS,
                status = json.optString("status", "unknown"),
                thought = json.optString("thought", "")
            )
        }
    }

    private suspend fun sendRequest(url: String, body: JSONObject): JSONObject? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray())
            }

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                JSONObject(response)
            } else {
                Log.e(TAG, "Request failed: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request error: ${e.message}")
            null
        }
    }

    private suspend fun sendRequestWithAuth(
        url: String, body: JSONObject, authKey: String
    ): JSONObject? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $authKey")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray())
            }

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                JSONObject(response)
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: ""
                Log.e(TAG, "API error [${connection.responseCode}]: $errorStream")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "API request error: ${e.message}")
            null
        }
    }
}

// ============================================================================
// 数据类
// ============================================================================

/**
 * 统一视觉理解结果 - OCR + GUI + 语义 的融合输出
 */
data class UnifiedVisionResult(
    val success: Boolean,
    val engine: String,
    val rawText: String = "",
    val textBlocks: List<TextBlock> = emptyList(),
    val uiElements: List<UIElement> = emptyList(),
    val sceneDescription: String = "",
    val suggestedActions: List<GUIAction> = emptyList(),
    val serverLatencyMs: Double = 0.0,
    val error: String? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("success", success)
            put("engine", engine)
            put("raw_text", rawText)
            put("scene_description", sceneDescription)
            put("text_blocks", JSONArray().apply {
                textBlocks.forEach { block ->
                    put(JSONObject().apply {
                        put("text", block.text)
                        put("x", block.x)
                        put("y", block.y)
                        put("width", block.width)
                        put("height", block.height)
                        put("confidence", block.confidence.toDouble())
                    })
                }
            })
            put("ui_elements", JSONArray().apply {
                uiElements.forEach { elem ->
                    put(JSONObject().apply {
                        put("id", elem.id)
                        put("type", elem.type)
                        put("text", elem.text)
                        put("bounds", JSONObject().apply {
                            put("x", elem.bounds.left)
                            put("y", elem.bounds.top)
                            put("w", elem.bounds.width())
                            put("h", elem.bounds.height())
                        })
                        put("clickable", elem.clickable)
                        put("scrollable", elem.scrollable)
                        put("editable", elem.editable)
                    })
                }
            })
            put("suggested_actions", JSONArray().apply {
                suggestedActions.forEach { action -> put(action.toJson()) }
            })
            error?.let { put("error", it) }
        }
    }
}

/**
 * UI 元素
 */
data class UIElement(
    val id: String,
    val type: String,           // button, text, input, image, icon, menu, list, switch, etc.
    val text: String,
    val bounds: Rect,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val confidence: Float = 1.0f
)

/**
 * OCR 结果 (兼容旧接口)
 */
data class OCRResult(
    val textBlocks: List<TextBlock>,
    val error: String?,
    val engine: String = "unknown",
    val rawText: String = "",
    val mode: String = "free_ocr",
    val serverLatencyMs: Double = 0.0
)

/**
 * 文本块
 */
data class TextBlock(
    val text: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val confidence: Float
)

/**
 * GUI 动作 (AgentCPM-GUI 风格)
 */
data class GUIAction(
    val action: String,
    val x: Int = 0,
    val y: Int = 0,
    val duration: Long = 0,
    val direction: String = "",
    val text: String = "",
    val key: String = "",
    val status: String = "",
    val thought: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("action", action)
            put("thought", thought)
            when (action) {
                GUIUnderstanding.ACTION_POINT -> {
                    put("POINT", JSONArray().apply {
                        put(x)
                        put(y)
                    })
                    if (duration > 0) put("duration", duration)
                }
                GUIUnderstanding.ACTION_SCROLL -> put("direction", direction)
                GUIUnderstanding.ACTION_TYPE -> put("text", text)
                GUIUnderstanding.ACTION_PRESS -> put("key", key)
                GUIUnderstanding.ACTION_STATUS -> put("status", status)
            }
        }
    }
}

/**
 * 视觉理解结果
 */
data class VisionResult(
    val content: String,
    val error: String?
)

/**
 * 视觉统计
 */
data class VisionStats(
    var totalRequests: Int = 0,
    var successCount: Int = 0,
    var cacheHits: Int = 0,
    var actionsRecorded: Int = 0,
    var totalLatencyMs: Long = 0
)
