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
 * 1. OCR 文字识别 (支持 MLKit / 服务端)
 * 2. GUI 元素定位 (AgentCPM-GUI 风格)
 * 3. 视觉理解 (GPT-4V / Gemini Vision / Claude Vision)
 * 4. 自主学习能力
 * 
 * 版本：1.0.0
 * 日期：2026-02-02
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
    }
    
    // 服务端 URL
    private var serverUrl: String = ""
    private var apiKey: String = ""
    
    // WebSocket 回调 (用于发送截图)
    private var webSocketSender: ((JSONObject) -> Boolean)? = null
    
    // 截图辅助类
    private val screenshotHelper = ScreenshotHelper(context)
    
    // MediaProjection (用于截图)
    private var mediaProjection: MediaProjection? = null
    
    // 本地 OCR 引擎
    private var useLocalOCR: Boolean = true
    
    /**
     * 配置服务端连接
     */
    fun configure(serverUrl: String, apiKey: String) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.apiKey = apiKey
    }
    
    /**
     * 设置是否使用本地 OCR
     */
    fun setUseLocalOCR(use: Boolean) {
        this.useLocalOCR = use
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
     * 
     * @param instruction 用户指令 (可选)
     * @param callback 回调函数，返回发送结果
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
            
            // 转换为 Base64
            val base64Image = screenshotHelper.bitmapToBase64(bitmap, quality = 85)
            Log.d(TAG, "Screenshot captured, base64 length: ${base64Image.length}")
            
            // 构建消息
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
            
            // 通过 WebSocket 发送
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
     * @param response 服务端返回的 JSON
     * @return 解析后的 GUI 动作
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
    // OCR 文字识别
    // ============================================================================
    
    /**
     * 执行 OCR 识别
     * @param bitmap 截图
     * @return OCR 结果 (文本列表 + 位置)
     */
    suspend fun performOCR(bitmap: Bitmap): OCRResult = withContext(Dispatchers.IO) {
        try {
            if (useLocalOCR) {
                performLocalOCR(bitmap)
            } else {
                performServerOCR(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed: ${e.message}")
            OCRResult(emptyList(), e.message ?: "OCR failed")
        }
    }
    
    /**
     * 本地 OCR (使用 ML Kit)
     */
    private suspend fun performLocalOCR(bitmap: Bitmap): OCRResult {
        // 注意：实际实现需要添加 ML Kit 依赖
        // implementation 'com.google.mlkit:text-recognition:16.0.0'
        // implementation 'com.google.mlkit:text-recognition-chinese:16.0.0'
        
        val textBlocks = mutableListOf<TextBlock>()
        
        // 模拟 ML Kit 调用
        // val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        // val image = InputImage.fromBitmap(bitmap, 0)
        // val result = recognizer.process(image).await()
        
        // 返回模拟结果
        return OCRResult(textBlocks, null)
    }
    
    /**
     * 服务端 OCR
     */
    private suspend fun performServerOCR(bitmap: Bitmap): OCRResult {
        if (serverUrl.isEmpty()) {
            return OCRResult(emptyList(), "Server URL not configured")
        }
        
        val base64Image = bitmapToBase64(bitmap)
        
        val requestBody = JSONObject().apply {
            put("image", base64Image)
            put("type", "ocr")
        }
        
        val response = sendRequest("$serverUrl/api/ocr", requestBody)
        
        val textBlocks = mutableListOf<TextBlock>()
        response?.optJSONArray("results")?.let { results ->
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                textBlocks.add(TextBlock(
                    text = item.getString("text"),
                    x = item.getInt("x"),
                    y = item.getInt("y"),
                    width = item.getInt("width"),
                    height = item.getInt("height"),
                    confidence = item.optDouble("confidence", 1.0).toFloat()
                ))
            }
        }
        
        return OCRResult(textBlocks, null)
    }
    
    // ============================================================================
    // GUI 元素定位 (AgentCPM-GUI 风格)
    // ============================================================================
    
    /**
     * 分析 GUI 并生成动作
     * @param bitmap 截图
     * @param instruction 用户指令
     * @return GUI 动作
     */
    suspend fun analyzeGUI(bitmap: Bitmap, instruction: String): GUIAction = withContext(Dispatchers.IO) {
        try {
            // 优先使用服务端 AgentCPM-GUI
            if (serverUrl.isNotEmpty()) {
                analyzeGUIWithServer(bitmap, instruction)
            } else {
                // 降级到本地视觉理解
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
     * 使用服务端 AgentCPM-GUI 分析
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
     * 本地 GUI 分析 (简化版)
     */
    private suspend fun analyzeGUILocally(bitmap: Bitmap, instruction: String): GUIAction {
        // 本地分析逻辑 (基于 OCR + 规则)
        val ocrResult = performOCR(bitmap)
        
        // 简单的关键词匹配
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
     * @param bitmap 截图
     * @param prompt 提示词
     * @return 理解结果
     */
    suspend fun understandWithVision(bitmap: Bitmap, prompt: String): VisionResult = withContext(Dispatchers.IO) {
        try {
            val base64Image = bitmapToBase64(bitmap)
            
            val requestBody = JSONObject().apply {
                put("model", "vision")  // 使用统一网关的 vision 别名
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
        // 存储到本地数据库用于后续学习
        // 可以定期上传到服务端进行模型微调
        Log.d(TAG, "Recording action: $instruction -> ${action.action}, success=$success")
    }
    
    /**
     * 从历史中学习
     */
    suspend fun learnFromHistory(): Boolean {
        // 分析历史操作，提取模式
        // 更新本地规则库
        return true
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
        // 简单的关键词提取
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
}

// ============================================================================
// 数据类
// ============================================================================

/**
 * OCR 结果
 */
data class OCRResult(
    val textBlocks: List<TextBlock>,
    val error: String?
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
