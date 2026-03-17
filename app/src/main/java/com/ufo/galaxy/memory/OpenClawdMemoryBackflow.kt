package com.ufo.galaxy.memory

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 * val backflow = OpenClawdMemoryBackflow(restBaseUrl = "http://100.0.0.1:8765")
 * val ok = backflow.store(MemoryEntry(task_id="t1", goal="open WeChat", status="success",
 *                                     summary="opened app", route_mode="cross_device"))
 * val entry = backflow.queryByTaskId("t1")
 * ```
 *
 * @param restBaseUrl REST base URL of the Gateway, e.g. `"http://100.0.0.1:8765"`.
 * @param httpClient  Optional [OkHttpClient]; defaults to a client with 5-second timeouts.
 *                    Inject a custom client in unit tests to avoid real network calls.
 * @param gson        Optional [Gson] instance; defaults to a plain [Gson()].
 */
class OpenClawdMemoryBackflow(
    private val restBaseUrl: String,
    private val httpClient: OkHttpClient = defaultClient(),
    private val gson: Gson = Gson()
) {

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Persists [entry] to `/api/v1/memory/store` (v1-first).
     *
     * If the v1 endpoint returns HTTP 404, the request is automatically retried
     * against the legacy `/api/memory/store` path.  Any other error is logged and
     * `false` is returned immediately — no second attempt is made.
     *
     * @return `true` when the server responded with a 2xx status; `false` on any
     *         network or server error.
     */
    fun store(entry: MemoryEntry): Boolean {
        val base = restBaseUrl.trimEnd('/')
        val v1Url = "$base/api/v1/memory/store"
        val json = gson.toJson(entry)
        return try {
            val request = Request.Builder()
                .url(v1Url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    // v1 not present — fall back to legacy endpoint (compatibility window)
                    Log.w(TAG, "[MEMORY:STORE] v1 returned 404; falling back to legacy path task_id=${entry.task_id}")
                    storeLegacy(base, entry, json)
                } else {
                    val ok = response.isSuccessful
                    Log.i(TAG, "[MEMORY:STORE] task_id=${entry.task_id} status=${entry.status} http=${response.code} ok=$ok endpoint=v1")
                    ok
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MEMORY:STORE] task_id=${entry.task_id} error=${e.message}", e)
            false
        }
    }

    /** Legacy fallback: POST to `/api/memory/store` when the v1 path returns 404. */
    private fun storeLegacy(base: String, entry: MemoryEntry, json: String): Boolean {
        val legacyUrl = "$base/api/memory/store"
        return try {
            val request = Request.Builder()
                .url(legacyUrl)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val ok = response.isSuccessful
                Log.i(TAG, "[MEMORY:STORE] task_id=${entry.task_id} status=${entry.status} http=${response.code} ok=$ok endpoint=legacy")
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MEMORY:STORE:LEGACY] task_id=${entry.task_id} error=${e.message}", e)
            false
        }
    }

    /**
     * Retrieves a previously stored [MemoryEntry] by [taskId].
     *
     * Calls `GET /api/v1/memory/query?task_id=<taskId>` (v1-first) and deserializes
     * the first matching entry from the JSON response body.  Falls back to the legacy
     * `/api/memory/query` path if the v1 endpoint returns HTTP 404.
     *
     * @return The matching [MemoryEntry], or `null` when no entry was found or a network
     *         or parse error occurred.
     */
    fun queryByTaskId(taskId: String): MemoryEntry? {
        val base = restBaseUrl.trimEnd('/')
        val v1Url = "$base/api/v1/memory/query?task_id=${encode(taskId)}"
        return try {
            val request = Request.Builder().url(v1Url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    Log.w(TAG, "[MEMORY:QUERY] v1 returned 404; falling back to legacy path task_id=$taskId")
                    queryLegacy(base, taskId)
                } else if (!response.isSuccessful) {
                    Log.w(TAG, "[MEMORY:QUERY] task_id=$taskId http=${response.code} endpoint=v1")
                    null
                } else {
                    val body = response.body?.string() ?: return null
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

    /** Legacy fallback: GET `/api/memory/query` when the v1 path returns 404. */
    private fun queryLegacy(base: String, taskId: String): MemoryEntry? {
        val legacyUrl = "$base/api/memory/query?task_id=${encode(taskId)}"
        return try {
            val request = Request.Builder().url(legacyUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "[MEMORY:QUERY:LEGACY] task_id=$taskId http=${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                parseFirstEntry(body).also { entry ->
                    Log.i(TAG, "[MEMORY:QUERY:LEGACY] task_id=$taskId found=${entry != null}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MEMORY:QUERY:LEGACY] task_id=$taskId error=${e.message}", e)
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
        private const val TAG = "OpenClawdMemory"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
