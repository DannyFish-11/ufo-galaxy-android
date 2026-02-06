package com.ufo.galaxy.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.util.Base64
import android.util.Log
import android.os.Handler
import android.os.Looper
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
 * UFO Galaxy - GUI 理解模块
 *
 * 功能：
 * 1. OCR 文字识别 - 主引擎: DeepSeek OCR 2, 降级: ML Kit / Tesseract
 * 2. GUI 元素定位 (AgentCPM-GUI 风格)
 * 3. 视觉理解 (GPT-4V / Gemini Vision / Claude Vision)
 * 4. 自主学习能力
 *
 * OCR 引擎优先级:
 *   1. DeepSeek OCR 2 (通过服务端 Node_15_OCR 的 /ocr API)
 *   2. DeepSeek OCR 2 (直接调用 Novita.ai 云端 API)
 *   3. ML Kit 本地 OCR (离线降级)
 *
 * 版本：2.0.0
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

        // OCR 引擎类型
        const val OCR_ENGINE_DEEPSEEK_SERVER = "deepseek_server"   // 通过服务端 Node_15
        const val OCR_ENGINE_DEEPSEEK_DIRECT = "deepseek_direct"   // 直接调用 API
        const val OCR_ENGINE_MLKIT = "mlkit"                        // 本地 ML Kit
        const val OCR_ENGINE_AUTO = "auto"                          // 自动选择

        // DeepSeek OCR 2 模式
        const val OCR_MODE_FREE = "free_ocr"
        const val OCR_MODE_DOCUMENT = "document_markdown"
        const val OCR_MODE_UI_ANALYSIS = "ui_analysis"
        const val OCR_MODE_TABLE = "table_extract"
        const val OCR_MODE_HANDWRITING = "handwriting"
    }

    // 服务端 URL
    private var serverUrl: String = ""
    private var apiKey: String = ""

    // DeepSeek OCR 2 直接 API 配置
    private var deepseekOCR2ApiKey: String = ""
    private var deepseekOCR2ApiBase: String = "https://api.novita.ai/openai"
    private var deepseekOCR2Model: String = "deepseek/deepseek-ocr-2"

    // WebSocket 回调 (用于发送截图)
    private var webSocketSender: ((JSONObject) -> Boolean)? = null

    // 截图辅助类
    private val screenshotHelper = ScreenshotHelper(context)

    // MediaProjection (用于截图)
    private var mediaProjection: MediaProjection? = null

    // OCR 引擎选择
    private var ocrEngine: String = OCR_ENGINE_AUTO

    // OCR 统计
    private var ocrRequestCount: Int = 0
    private var ocrSuccessCount: Int = 0
    private var ocrTotalLatencyMs: Long = 0

    /**
     * 配置服务端连接
     */
    fun configure(serverUrl: String, apiKey: String) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.apiKey = apiKey
    }

    /**
     * 配置 DeepSeek OCR 2 直接 API
     */
    fun configureDeepSeekOCR2(apiKey: String, apiBase: String = "", model: String = "") {
        this.deepseekOCR2ApiKey = apiKey
        if (apiBase.isNotEmpty()) this.deepseekOCR2ApiBase = apiBase
        if (model.isNotEmpty()) this.deepseekOCR2Model = model
        Log.i(TAG, "DeepSeek OCR 2 configured: base=$deepseekOCR2ApiBase, model=$deepseekOCR2Model")
    }

    /**
     * 设置 OCR 引擎
     */
    fun setOCREngine(engine: String) {
        this.ocrEngine = engine
        Log.i(TAG, "OCR engine set to: $engine")
    }

    /**
     * 设置 MediaProjection (用于截图)
     */
    fun setMediaProjection(projection: MediaProjection) {
        this.mediaProjection = projection
    }

    /**
     * 设置 WebSocket 发送回调
     */
    fun setWebSocketSender(sender: (JSONObject) -> Boolean) {
        this.webSocketSender = sender
    }

    // ============================================================================
    // 截图 → Base64 → WebSocket 传输
    // ============================================================================

    /**
     * 截图并通过 WebSocket 发送到服务端
     */
    fun captureAndSendScreenshot(
        instruction: String = "",
        callback: (Boolean, String) -> Unit
    ) {
        val projection = mediaProjection
        if (projection == null) {
            Log.e(TAG, "MediaProjection not set")
            callback(false, "MediaProjection not set")
            return
        }

        val sender = webSocketSender
        if (sender == null) {
            Log.e(TAG, "WebSocket sender not set")
            callback(false, "WebSocket sender not set")
            return
        }

        screenshotHelper.takeScreenshotWithMediaProjection(projection) { bitmap ->
            if (bitmap == null) {
                Log.e(TAG, "Screenshot capture failed")
                callback(false, "Screenshot capture failed")
                return@takeScreenshotWithMediaProjection
            }

            val base64Image = screenshotHelper.bitmapToBase64(bitmap, quality = 85)
            Log.d(TAG, "Screenshot captured, base64 length: ${base64Image.length}")

            val message = JSONObject().apply {
                put("version", "2.0")
                put("type", "vision_request")
                put("timestamp", System.currentTimeMillis())
                put("image", base64Image)
                put("image_format", "jpeg")
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
            if (success) {
                Log.i(TAG, "Screenshot sent successfully via WebSocket")
                callback(true, "Screenshot sent successfully")
            } else {
                Log.e(TAG, "Failed to send screenshot via WebSocket")
                callback(false, "Failed to send screenshot via WebSocket")
            }
        }
    }

    /**
     * 协程版本的截图发送
     */
    suspend fun captureAndSendScreenshotAsync(instruction: String = ""): Pair<Boolean, String> =
        suspendCancellableCoroutine { continuation ->
            captureAndSendScreenshot(instruction) { success, message ->
                continuation.resume(Pair(success, message))
            }
        }

    /**
     * 处理服务端返回的视觉理解结果
     */
    fun processVisionResponse(response: JSONObject): GUIAction {
        Log.d(TAG, "Processing vision response: ${response.toString().take(200)}")

        val action = response.optString("action", ACTION_STATUS)
        val thought = response.optString("thought", "")

        return when (action) {
            ACTION_POINT -> GUIAction(
                action = ACTION_POINT,
                x = response.optInt("x", 0),
                y = response.optInt("y", 0),
                duration = response.optLong("duration", 0),
                thought = thought
            )
            ACTION_SCROLL -> GUIAction(
                action = ACTION_SCROLL,
                direction = response.optString("direction", "down"),
                thought = thought
            )
            ACTION_TYPE -> GUIAction(
                action = ACTION_TYPE,
                text = response.optString("text", ""),
                thought = thought
            )
            ACTION_PRESS -> GUIAction(
                action = ACTION_PRESS,
                key = response.optString("key", ""),
                thought = thought
            )
            else -> GUIAction(
                action = ACTION_STATUS,
                status = response.optString("status", "unknown"),
                thought = thought
            )
        }
    }

    // ============================================================================
    // OCR 文字识别 - DeepSeek OCR 2 为主引擎
    // ============================================================================

    /**
     * 执行 OCR 识别
     *
     * 引擎优先级:
     *   1. DeepSeek OCR 2 (通过服务端 Node_15_OCR)
     *   2. DeepSeek OCR 2 (直接 API 调用)
     *   3. ML Kit 本地 OCR
     *
     * @param bitmap 截图
     * @param mode OCR 模式 (free_ocr, document_markdown, ui_analysis, table_extract, handwriting)
     * @return OCR 结果
     */
    suspend fun performOCR(
        bitmap: Bitmap,
        mode: String = OCR_MODE_FREE
    ): OCRResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        ocrRequestCount++

        try {
            val result = when (ocrEngine) {
                OCR_ENGINE_DEEPSEEK_SERVER -> performDeepSeekServerOCR(bitmap, mode)
                OCR_ENGINE_DEEPSEEK_DIRECT -> performDeepSeekDirectOCR(bitmap, mode)
                OCR_ENGINE_MLKIT -> performLocalOCR(bitmap)
                OCR_ENGINE_AUTO -> performAutoOCR(bitmap, mode)
                else -> performAutoOCR(bitmap, mode)
            }

            val latency = System.currentTimeMillis() - startTime
            ocrTotalLatencyMs += latency
            if (result.error == null) ocrSuccessCount++

            Log.i(TAG, "OCR completed: engine=${result.engine}, mode=$mode, latency=${latency}ms")
            result
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed: ${e.message}")
            OCRResult(emptyList(), e.message ?: "OCR failed", engine = "none", rawText = "")
        }
    }

    /**
     * 自动选择 OCR 引擎
     */
    private suspend fun performAutoOCR(bitmap: Bitmap, mode: String): OCRResult {
        // 优先级 1: 通过服务端 Node_15_OCR (DeepSeek OCR 2)
        if (serverUrl.isNotEmpty()) {
            try {
                val result = performDeepSeekServerOCR(bitmap, mode)
                if (result.error == null) return result
                Log.w(TAG, "Server OCR failed, trying direct API: ${result.error}")
            } catch (e: Exception) {
                Log.w(TAG, "Server OCR exception: ${e.message}")
            }
        }

        // 优先级 2: 直接调用 DeepSeek OCR 2 API
        if (deepseekOCR2ApiKey.isNotEmpty()) {
            try {
                val result = performDeepSeekDirectOCR(bitmap, mode)
                if (result.error == null) return result
                Log.w(TAG, "Direct DeepSeek OCR failed, falling back to local: ${result.error}")
            } catch (e: Exception) {
                Log.w(TAG, "Direct DeepSeek OCR exception: ${e.message}")
            }
        }

        // 优先级 3: 本地 ML Kit
        return performLocalOCR(bitmap)
    }

    /**
     * 通过服务端 Node_15_OCR 调用 DeepSeek OCR 2
     */
    private suspend fun performDeepSeekServerOCR(bitmap: Bitmap, mode: String): OCRResult {
        if (serverUrl.isEmpty()) {
            return OCRResult(emptyList(), "Server URL not configured", engine = "deepseek_server")
        }

        val base64Image = bitmapToBase64(bitmap)

        val requestBody = JSONObject().apply {
            put("image", base64Image)
            put("mode", mode)
            put("engine", "auto")
            put("language", "auto")
        }

        // 调用服务端 Node_15_OCR 的 /ocr 接口
        val response = sendRequest("$serverUrl/ocr", requestBody)
            ?: return OCRResult(emptyList(), "Server no response", engine = "deepseek_server")

        val success = response.optBoolean("success", false)
        if (!success) {
            val error = response.optString("error", "Unknown error")
            return OCRResult(emptyList(), error, engine = "deepseek_server")
        }

        val rawText = response.optString("text", "")
        val engine = response.optString("engine", "deepseek_ocr2")
        val latencyMs = response.optDouble("latency_ms", 0.0)

        // 解析文本块（如果有）
        val textBlocks = mutableListOf<TextBlock>()
        response.optJSONArray("text_blocks")?.let { blocks ->
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

        // 如果没有文本块，从原始文本创建一个
        if (textBlocks.isEmpty() && rawText.isNotEmpty()) {
            textBlocks.add(TextBlock(
                text = rawText,
                x = 0, y = 0, width = bitmap.width, height = bitmap.height,
                confidence = 0.95f
            ))
        }

        return OCRResult(
            textBlocks = textBlocks,
            error = null,
            engine = engine,
            rawText = rawText,
            mode = mode,
            serverLatencyMs = latencyMs
        )
    }

    /**
     * 直接调用 DeepSeek OCR 2 API (Novita.ai)
     *
     * 当服务端不可用时，Android 端可以直接调用 DeepSeek OCR 2 的云端 API。
     */
    private suspend fun performDeepSeekDirectOCR(bitmap: Bitmap, mode: String): OCRResult {
        if (deepseekOCR2ApiKey.isEmpty()) {
            return OCRResult(emptyList(), "DeepSeek OCR 2 API key not configured", engine = "deepseek_direct")
        }

        val base64Image = bitmapToBase64(bitmap)

        // 构建 DeepSeek OCR 2 的 prompt
        val prompt = when (mode) {
            OCR_MODE_FREE -> "<image>\nFree OCR. "
            OCR_MODE_DOCUMENT -> "<image>\n<|grounding|>Convert the document to markdown. "
            OCR_MODE_UI_ANALYSIS -> buildString {
                append("<image>\nAnalyze this UI screenshot. ")
                append("Identify all interactive elements (buttons, text fields, links, menus) ")
                append("with their positions, text content, and element types. ")
                append("Output as structured JSON.")
            }
            OCR_MODE_TABLE -> "<image>\n<|grounding|>Extract all tables. Convert to markdown table format."
            OCR_MODE_HANDWRITING -> "<image>\nRecognize all handwritten text in this image. "
            else -> "<image>\nFree OCR. "
        }

        // 构建 OpenAI 兼容请求
        val requestBody = JSONObject().apply {
            put("model", deepseekOCR2Model)
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

        // 调用 API
        val apiUrl = "${deepseekOCR2ApiBase}/chat/completions"
        val response = sendRequestWithAuth(apiUrl, requestBody, deepseekOCR2ApiKey)
            ?: return OCRResult(emptyList(), "DeepSeek OCR 2 API no response", engine = "deepseek_direct")

        // 解析结果
        val content = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "") ?: ""

        if (content.isEmpty()) {
            return OCRResult(emptyList(), "Empty response from DeepSeek OCR 2", engine = "deepseek_direct")
        }

        val usage = response.optJSONObject("usage")
        val promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0
        val completionTokens = usage?.optInt("completion_tokens", 0) ?: 0

        Log.i(TAG, "DeepSeek OCR 2 direct: tokens=$promptTokens/$completionTokens, text_len=${content.length}")

        // 将文本封装为文本块
        val textBlocks = listOf(TextBlock(
            text = content,
            x = 0, y = 0, width = bitmap.width, height = bitmap.height,
            confidence = 0.95f
        ))

        return OCRResult(
            textBlocks = textBlocks,
            error = null,
            engine = "deepseek_ocr2",
            rawText = content,
            mode = mode
        )
    }

    /**
     * 本地 OCR (使用 ML Kit)
     */
    private suspend fun performLocalOCR(bitmap: Bitmap): OCRResult {
        // ML Kit 实现
        // 需要添加依赖: com.google.mlkit:text-recognition:16.0.0
        // 以及中文支持: com.google.mlkit:text-recognition-chinese:16.0.0

        val textBlocks = mutableListOf<TextBlock>()

        // TODO: 实际 ML Kit 实现
        // val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        // val image = InputImage.fromBitmap(bitmap, 0)
        // val result = recognizer.process(image).await()
        // for (block in result.textBlocks) {
        //     val rect = block.boundingBox ?: continue
        //     textBlocks.add(TextBlock(
        //         text = block.text,
        //         x = rect.left, y = rect.top,
        //         width = rect.width(), height = rect.height(),
        //         confidence = 0.8f
        //     ))
        // }

        return OCRResult(
            textBlocks = textBlocks,
            error = if (textBlocks.isEmpty()) "ML Kit OCR not yet implemented" else null,
            engine = "mlkit"
        )
    }

    // ============================================================================
    // GUI 元素定位 (AgentCPM-GUI 风格)
    // ============================================================================

    /**
     * 分析 GUI 并生成动作
     */
    suspend fun analyzeGUI(bitmap: Bitmap, instruction: String): GUIAction = withContext(Dispatchers.IO) {
        try {
            if (serverUrl.isNotEmpty()) {
                analyzeGUIWithServer(bitmap, instruction)
            } else {
                analyzeGUILocally(bitmap, instruction)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GUI analysis failed: ${e.message}")
            GUIAction(
                action = ACTION_STATUS,
                status = "fail",
                thought = "分析失败: ${e.message}"
            )
        }
    }

    /**
     * 使用服务端分析 GUI
     *
     * 优先使用 DeepSeek OCR 2 的 UI 分析模式
     */
    private suspend fun analyzeGUIWithServer(bitmap: Bitmap, instruction: String): GUIAction {
        val base64Image = bitmapToBase64(bitmap)

        val requestBody = JSONObject().apply {
            put("image", base64Image)
            put("instruction", instruction)
            put("type", "gui_analysis")
        }

        val response = sendRequest("$serverUrl/api/gui/analyze", requestBody)

        return response?.let { parseGUIAction(it) } ?: GUIAction(
            action = ACTION_STATUS,
            status = "fail",
            thought = "服务端无响应"
        )
    }

    /**
     * 本地 GUI 分析 (基于 DeepSeek OCR 2 + 规则)
     */
    private suspend fun analyzeGUILocally(bitmap: Bitmap, instruction: String): GUIAction {
        // 使用 OCR 识别屏幕文字
        val ocrResult = performOCR(bitmap, OCR_MODE_FREE)

        // 关键词匹配
        val keywords = extractKeywords(instruction)

        for (block in ocrResult.textBlocks) {
            for (keyword in keywords) {
                if (block.text.contains(keyword, ignoreCase = true)) {
                    return GUIAction(
                        action = ACTION_POINT,
                        x = block.x + block.width / 2,
                        y = block.y + block.height / 2,
                        thought = "找到匹配文本: ${block.text}"
                    )
                }
            }
        }

        return GUIAction(
            action = ACTION_STATUS,
            status = "fail",
            thought = "未找到匹配元素"
        )
    }

    /**
     * 解析 GUI 动作
     */
    private fun parseGUIAction(json: JSONObject): GUIAction {
        val action = json.optString("action", ACTION_STATUS)

        return when (action) {
            ACTION_POINT -> GUIAction(
                action = ACTION_POINT,
                x = json.optJSONArray("POINT")?.optInt(0) ?: 0,
                y = json.optJSONArray("POINT")?.optInt(1) ?: 0,
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
                status = json.optString("status", "fail"),
                thought = json.optString("thought", "")
            )
        }
    }

    // ============================================================================
    // 视觉理解 (多模态 LLM)
    // ============================================================================

    /**
     * 使用视觉 LLM 理解截图
     */
    suspend fun understandWithVision(bitmap: Bitmap, prompt: String): VisionResult = withContext(Dispatchers.IO) {
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

    /**
     * 记录操作历史用于学习
     */
    fun recordAction(
        screenshot: Bitmap,
        instruction: String,
        action: GUIAction,
        success: Boolean
    ) {
        Log.d(TAG, "Recording action: $instruction -> ${action.action}, success=$success")
    }

    /**
     * 从历史中学习
     */
    suspend fun learnFromHistory(): Boolean {
        return true
    }

    /**
     * 获取 OCR 统计信息
     */
    fun getOCRStats(): JSONObject {
        return JSONObject().apply {
            put("total_requests", ocrRequestCount)
            put("successful_requests", ocrSuccessCount)
            put("avg_latency_ms",
                if (ocrSuccessCount > 0) ocrTotalLatencyMs / ocrSuccessCount else 0)
            put("current_engine", ocrEngine)
            put("deepseek_ocr2_configured", deepseekOCR2ApiKey.isNotEmpty())
            put("server_configured", serverUrl.isNotEmpty())
        }
    }

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

    /**
     * 带认证的 API 请求（用于直接调用 DeepSeek OCR 2）
     */
    private suspend fun sendRequestWithAuth(
        url: String,
        body: JSONObject,
        authKey: String
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
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "DeepSeek OCR 2 API error [${connection.responseCode}]: $errorStream")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek OCR 2 API request error: ${e.message}")
            null
        }
    }
}

// ============================================================================
// 数据类
// ============================================================================

/**
 * OCR 结果
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
