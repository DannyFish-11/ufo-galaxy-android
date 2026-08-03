package com.ufo.galaxy.memory

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * A single task-result entry stored in the OpenClawd memory system (P3).
 *
 * Field names mirror the server-side `/api/v1/memory/store` schema so the JSON
 * serialised by [Gson] matches the server contract without field-name mapping.
 *
 * @param task_id      Unique task identifier (primary query key).
 * @param goal         Natural-language goal that was executed.
 * @param status       Final task status: `"success"` | `"error"` | `"cancelled"` | `"timeout"`.
 * @param summary      Human-readable one-line outcome description.
 * @param steps        Ordered list of step-level result summaries.
 * @param route_mode   Routing path taken: `"local"` | `"cross_device"` | `"error"`.
 * @param timestamp_ms Unix epoch milliseconds when this entry was created.
 */
data class MemoryEntry(
    val task_id: String,
    val goal: String,
    val status: String,
    val summary: String,
    val steps: List<String> = emptyList(),
    val route_mode: String = "local",
    val timestamp_ms: Long = System.currentTimeMillis()
)

/**
 * OpenClawd memory backflow client (P3).
 *
 * Persists task results to the Gateway's memory store (`/api/v1/memory/store`) and
 * retrieves previously stored entries by `task_id` (`/api/v1/memory/query`).
 *
 * All network operations are synchronous (blocking); callers must invoke them from
 * a background thread or IO coroutine.  No Android framework dependency is introduced
 * so that the class remains fully unit-testable on the JVM.
 *
 * Usage:
 * ```kotlin
 * val backflow = OpenClawdMemoryBackflow(restBaseUrl = "http://100.0.0.1:9000")
 * val ok = backflow.store(MemoryEntry(task_id="t1", goal="open WeChat", status="success",
 *                                     summary="opened app", route_mode="cross_device"))
 * val entry = backflow.queryByTaskId("t1")
 * ```
 *
 * @param restBaseUrl REST base URL of the Gateway, e.g. `"http://100.0.0.1:9000"`.
 * @param httpClient  Optional [OkHttpClient]; defaults to a client with 5-second timeouts.
 *                    Inject a custom client in unit tests to avoid real network calls.
 * @param gson        Optional [Gson] instance; defaults to a plain [Gson()].
 */
class OpenClawdMemoryBackflow(
    private val restBaseUrl: String,
    private val httpClient: OkHttpClient = defaultClient(),
    private val gson: Gson = Gson()
) {

    init {
        require(restBaseUrl.startsWith("https://") || isTailscaleIp(restBaseUrl)) {
            "OpenClawdMemoryBackflow requires HTTPS (non-Tailscale) or Tailscale HTTP"
        }
    }

    /** Checks whether the URL uses a Tailscale IP (100.64.0.0/10 CGNAT space). */
    private fun isTailscaleIp(url: String): Boolean {
        val tailscaleRange = Regex("^https?://(100\\.(6[4-9]|[7-9][0-9]|1[0-1][0-9]|12[0-7])\\.\\d{1,3}\\.\\d{1,3})")
        return tailscaleRange.find(url) != null
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Persists [entry] to `/api/v1/memory/store` (v1-first).
     *
     * V2 上没有无版本的 memory 路由,所以这里**没有 legacy 兜底** —— 详见方法体内的注释。
     * Any error is logged and
     * `false` is returned immediately — no second attempt is made.
     *
     * @return `true` when the server responded with a 2xx status; `false` on any
     *         network or server error.
     */
    suspend fun store(entry: MemoryEntry): Boolean = withContext(Dispatchers.IO) {
        val base = restBaseUrl.trimEnd('/')
        val v1Url = "$base/api/v1/memory/store"
        val json = gson.toJson(entry)
        try {
            val request = Request.Builder()
                .url(v1Url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                // 这里此前有一条 404 兜底,打 /api/memory/store。
                // 实测:V2 上**根本没有无版本的 memory 路由**(/api/memory/store → 404),
                // 也就是说那条兜底从来没有救回过任何一次请求 —— 它唯一的作用是在
                // v1 真出问题时多打一次注定失败的请求,并留下一条"已降级"的假象日志。
                // (对照 /api/devices/*:那一族 legacy 路径 V2 上是真有的,所以那边的兜底保留。)
                val ok = response.isSuccessful
                Log.i(TAG, "[MEMORY:STORE] task_id=${entry.task_id} status=${entry.status} http=${response.code} ok=$ok endpoint=v1")
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MEMORY:STORE] task_id=${entry.task_id} error=${e.message}", e)
            false
        }
    }

    /**
     * Retrieves a previously stored [MemoryEntry] by [taskId].
     *
     * Calls `GET /api/v1/memory/query?task_id=<taskId>` and deserializes the first
     * matching entry from the JSON response body.
     *
     * 注意 404 在这条链路上有两种含义:服务端对"这条记录不存在"也返回 404(带自家信封)。
     * 那是正常的 miss,不是端点缺失 —— 两者的区分见方法体内的注释。
     * V2 上没有无版本的 memory 路由,所以这里没有 legacy 兜底。
     *
     * @return The matching [MemoryEntry], or `null` when no entry was found or a network
     *         or parse error occurred.
     */
    suspend fun queryByTaskId(taskId: String): MemoryEntry? = withContext(Dispatchers.IO) {
        val base = restBaseUrl.trimEnd('/')
        val v1Url = "$base/api/v1/memory/query?task_id=${encode(taskId)}"
        try {
            val request = Request.Builder().url(v1Url).get().build()
            httpClient.newCall(request).execute().use { response ->
                // 404 在这条链路上有**两种含义**,必须分开:
                //   a) 路由不存在(端点缺失)—— 该兜底;
                //   b) 路由存在,但这条 task_id 没有记录 —— 这是正常的 miss,不该兜底。
                // V2 服务端对 (b) 返回的是自家信封 {"success":false,"error":"not found",...},
                // 而 FastAPI 对 (a) 返回 {"detail":"Not Found"}。此前不分,于是**每一次
                // 缓存未命中**都会白打一次根本不存在的 legacy 请求,并打出一条
                // "v1 returned 404"的告警 —— 真出事时这条日志会把人往错的方向带。
                if (response.code == 404 && !isEndpointMissing(response.peekBody(MAX_PEEK_BYTES).string())) {
                    Log.d(TAG, "[MEMORY:QUERY] miss task_id=$taskId")
                    return@withContext null
                }
                if (response.code == 404) {
                    // 走到这里说明是**端点缺失**(上面已经把语义 miss 分流掉了)。
                    // 此前这里会兜底打 /api/memory/query —— 而 V2 上那条同样不存在,
                    // 兜底本身也是 404。删掉之后行为不变(都返回 null),少一次无用往返,
                    // 而且日志不再谎称"已降级到 legacy"。
                    Log.w(TAG, "[MEMORY:QUERY] v1 endpoint missing task_id=$taskId")
                    null
                } else if (!response.isSuccessful) {
                    Log.w(TAG, "[MEMORY:QUERY] task_id=$taskId http=${response.code} endpoint=v1")
                    null
                } else {
                    val body = response.body?.string() ?: return@withContext null
                    parseFirstEntry(body).also { entry ->
                        Log.i(TAG, "[MEMORY:QUERY] task_id=$taskId found=${entry != null} endpoint=v1")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MEMORY:QUERY] task_id=$taskId error=${e.message}", e)
            null
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Parses the first [MemoryEntry] from a JSON response body.
     *
     * The server may return either a single object or a JSON array; both forms are
     * handled.  Returns `null` when parsing fails or the body contains an empty array.
     */
    private fun parseFirstEntry(body: String): MemoryEntry? {
        return try {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val array = gson.fromJson(body, Array<MemoryEntry>::class.java)
                array.firstOrNull()
            } else {
                gson.fromJson(body, MemoryEntry::class.java)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[MEMORY:PARSE] failed to parse response: ${e.message}")
            null
        }
    }

    /** URL-encodes a query parameter value using ASCII percent-encoding. */
    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        /**
         * 这个 404 是"端点不存在"还是"这条记录不存在"?
         *
         * 判据:V2 的业务 404 一定带自家信封(有 `success` 字段);FastAPI 的路由级 404
         * 是 `{"detail":"Not Found"}`。读不出 JSON 时按**端点缺失**处理 —— 那一侧
         * 只是多打一次兜底请求,而反过来判错会把真正的端点缺失静默吞掉。
         */
        internal fun isEndpointMissing(body: String?): Boolean {
            if (body.isNullOrBlank()) return true
            return try {
                !org.json.JSONObject(body).has("success")
            } catch (e: org.json.JSONException) {
                true
            }
        }

        /** 只窥探判定所需的前若干字节,不把整个响应体读进内存。 */
        private const val MAX_PEEK_BYTES = 512L

        private const val TAG = "OpenClawdMemory"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
