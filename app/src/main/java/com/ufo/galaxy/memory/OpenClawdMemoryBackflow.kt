package com.ufo.galaxy.memory

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenClawd 记忆回流（Memory Backflow）
 *
 * 当本机完成一次任务（本地模式或跨设备模式）后，将执行结果写入 V2 OpenClawd
 * 记忆库/数据库，以供 OpenClawd 智能体后续决策时检索。
 *
 * 上报接口（V2）：
 * - 写入端点：POST `{restBase}/api/v1/memory/store`
 * - 查询端点：GET  `{restBase}/api/v1/memory/query?task_id={task_id}`
 *
 * 写入载荷字段：
 * | 字段          | 说明                                  |
 * |--------------|---------------------------------------|
 * | task_id      | 任务唯一标识                            |
 * | device_id    | 执行设备 ID                             |
 * | goal         | 用户原始目标（自然语言）                   |
 * | status       | "success" / "error" / "partial"        |
 * | summary      | 执行摘要（可选）                          |
 * | steps        | 各步骤结果 JSON 数组（可选）              |
 * | error_msg    | 失败原因（status=error 时填写）           |
 * | timestamp_ms | 客户端时间戳（毫秒）                      |
 * | route_mode   | "local" 或 "cross_device"              |
 *
 * @param restBaseUrl V2 HTTP 基础地址，例如 `http://100.x.x.x:8000`
 * @param apiKey      可选的 Bearer token（为空时不附加 Authorization 头）
 * @param deviceId    本机设备 ID
 *
 * @author UFO³ Galaxy
 * @version 1.0 (P3)
 */
class OpenClawdMemoryBackflow(
    private val restBaseUrl: String,
    private val apiKey: String = "",
    private val deviceId: String = ""
) {
    companion object {
        private const val TAG = "MemoryBackflow"
        const val STORE_PATH = "/api/v1/memory/store"
        const val QUERY_PATH = "/api/v1/memory/query"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 将任务执行结果上报到 V2 OpenClawd 记忆库。
     *
     * @param entry 记忆条目，包含任务 ID、目标、状态、摘要和步骤等字段
     * @return true 表示写入成功（HTTP 2xx）；false 表示失败（网络异常或非 2xx）
     */
    suspend fun store(entry: MemoryEntry): Boolean = withContext(Dispatchers.IO) {
        val url = buildUrl(STORE_PATH)
        val body = entry.toJson().apply {
            // 注入设备 ID（覆盖空值）
            if (!has("device_id") || optString("device_id").isBlank()) {
                put("device_id", deviceId)
            }
        }
        val success = post(url, body)
        if (success) {
            Log.i(TAG, "✅ 记忆回流成功 task_id=${entry.taskId} status=${entry.status}")
        } else {
            Log.w(TAG, "⚠️ 记忆回流失败 task_id=${entry.taskId}")
        }
        success
    }

    /**
     * 按 taskId 查询已存储的记忆条目。
     *
     * @return 包含记忆数据的 [JSONObject]，查询失败时返回 null
     */
    suspend fun query(taskId: String): JSONObject? = withContext(Dispatchers.IO) {
        val url = buildUrl("$QUERY_PATH?task_id=$taskId")
        get(url)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    internal fun buildUrl(path: String): String = restBaseUrl.trimEnd('/') + path

    internal open fun post(url: String, body: JSONObject): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "post() error: ${e.message}")
            false
        }
    }

    internal open fun get(url: String): JSONObject? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            if (conn.responseCode in 200..299) {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "get() error: ${e.message}")
            null
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Data model
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 记忆条目 — 记录一次任务执行的完整上下文。
 *
 * @property taskId     任务唯一标识
 * @property goal       用户原始目标
 * @property deviceId   执行设备 ID（留空时由 [OpenClawdMemoryBackflow] 注入）
 * @property status     "success" / "error" / "partial"
 * @property summary    执行摘要（可选）
 * @property steps      各步骤结果 JSON 字符串列表（可选）
 * @property errorMsg   失败原因（status=error 时填写）
 * @property routeMode  "local" 或 "cross_device"
 * @property timestampMs 客户端时间戳（毫秒，默认当前时间）
 */
data class MemoryEntry(
    val taskId: String,
    val goal: String,
    val deviceId: String = "",
    val status: String = STATUS_SUCCESS,
    val summary: String = "",
    val steps: List<String> = emptyList(),
    val errorMsg: String = "",
    val routeMode: String = ROUTE_LOCAL,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("task_id", taskId)
        put("goal", goal)
        put("device_id", deviceId)
        put("status", status)
        put("summary", summary)
        put("error_msg", errorMsg)
        put("route_mode", routeMode)
        put("timestamp_ms", timestampMs)
        val stepsArr = org.json.JSONArray()
        steps.forEach { stepsArr.put(it) }
        put("steps", stepsArr)
    }

    companion object {
        const val STATUS_SUCCESS = "success"
        const val STATUS_ERROR = "error"
        const val STATUS_PARTIAL = "partial"
        const val ROUTE_LOCAL = "local"
        const val ROUTE_CROSS_DEVICE = "cross_device"

        /** 从 JSON 反序列化（V2 查询返回时使用）。 */
        fun fromJson(json: JSONObject): MemoryEntry {
            val steps = mutableListOf<String>()
            json.optJSONArray("steps")?.let { arr ->
                for (i in 0 until arr.length()) steps.add(arr.getString(i))
            }
            return MemoryEntry(
                taskId = json.optString("task_id", ""),
                goal = json.optString("goal", ""),
                deviceId = json.optString("device_id", ""),
                status = json.optString("status", STATUS_SUCCESS),
                summary = json.optString("summary", ""),
                steps = steps,
                errorMsg = json.optString("error_msg", ""),
                routeMode = json.optString("route_mode", ROUTE_LOCAL),
                timestampMs = json.optLong("timestamp_ms", System.currentTimeMillis())
            )
        }
    }
}
