package com.ufo.galaxy.memory

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [OpenClawdMemoryBackflow] and [MemoryEntry] (P3).
 *
 * All HTTP calls are intercepted via a custom [OkHttpClient] interceptor to avoid
 * real network I/O.  Tests verify:
 *  - [OpenClawdMemoryBackflow.store] returns true on 2xx, false on non-2xx.
 *  - [OpenClawdMemoryBackflow.queryByTaskId] parses a single JSON object response.
 *  - [OpenClawdMemoryBackflow.queryByTaskId] parses a JSON array response.
 *  - [OpenClawdMemoryBackflow.queryByTaskId] returns null on 404 or network error.
 *  - [MemoryEntry] default field values are correct.
 *  - [MemoryEntry] field names serialise correctly (required for server contract).
 *  - [OpenClawdMemoryBackflow.store] returns false on network exception.
 */
class OpenClawdMemoryBackflowTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun fakeClient(code: Int, body: String = ""): OkHttpClient {
        val interceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    private fun errorClient(): OkHttpClient {
        val interceptor = Interceptor { _ ->
            throw java.io.IOException("connection refused")
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    private fun backflow(client: OkHttpClient) =
        OpenClawdMemoryBackflow(restBaseUrl = "http://100.0.0.1:9000", httpClient = client)

    private fun sampleEntry(taskId: String = "t-001") = MemoryEntry(
        task_id = taskId,
        goal = "open WeChat",
        status = "success",
        summary = "opened app successfully",
        steps = listOf("tap app icon", "wait for launch"),
        route_mode = "cross_device",
        timestamp_ms = 1700000000000L
    )

    private fun sampleJson(taskId: String = "t-001") =
        """{"task_id":"$taskId","goal":"open WeChat","status":"success","summary":"opened app successfully","steps":["tap app icon","wait for launch"],"route_mode":"cross_device","timestamp_ms":1700000000000}"""

    // ── store() ────────────────────────────────────────────────────────────────

    @Test
    fun `store returns true when server responds with 200`() = runBlocking {
        val bf = backflow(fakeClient(200, "{}"))
        assertTrue("store must return true on 200", bf.store(sampleEntry()))
    }

    @Test
    fun `store returns true when server responds with 201`() = runBlocking {
        val bf = backflow(fakeClient(201, "{}"))
        assertTrue("store must return true on 201", bf.store(sampleEntry()))
    }

    @Test
    fun `store returns false when server responds with 500`() = runBlocking {
        val bf = backflow(fakeClient(500))
        assertFalse("store must return false on 500", bf.store(sampleEntry()))
    }

    @Test
    fun `store returns false when server responds with 400`() = runBlocking {
        val bf = backflow(fakeClient(400))
        assertFalse("store must return false on 400", bf.store(sampleEntry()))
    }

    @Test
    fun `store returns false on network exception`() = runBlocking {
        val bf = backflow(errorClient())
        assertFalse("store must return false on network exception", bf.store(sampleEntry()))
    }

    // ── queryByTaskId() ────────────────────────────────────────────────────────

    @Test
    fun `queryByTaskId returns entry when server responds with single JSON object`() = runBlocking {
        val json = sampleJson("q-001")
        val bf = backflow(fakeClient(200, json))

        val result = bf.queryByTaskId("q-001")

        assertNotNull("result must not be null for valid response", result)
        assertEquals("q-001", result!!.task_id)
        assertEquals("open WeChat", result.goal)
        assertEquals("success", result.status)
        assertEquals("cross_device", result.route_mode)
    }

    @Test
    fun `queryByTaskId returns first entry when server responds with JSON array`() = runBlocking {
        val json = """[${sampleJson("arr-001")},${sampleJson("arr-002")}]"""
        val bf = backflow(fakeClient(200, json))

        val result = bf.queryByTaskId("arr-001")

        assertNotNull(result)
        assertEquals("arr-001", result!!.task_id)
    }

    @Test
    fun `queryByTaskId returns null when server responds with 404`() = runBlocking {
        val bf = backflow(fakeClient(404))
        assertNull("result must be null for 404", bf.queryByTaskId("missing-id"))
    }

    @Test
    fun `queryByTaskId returns null on network exception`() = runBlocking {
        val bf = backflow(errorClient())
        assertNull("result must be null on network exception", bf.queryByTaskId("t-001"))
    }

    @Test
    fun `queryByTaskId returns null when response body is empty`() = runBlocking {
        val bf = backflow(fakeClient(200, ""))
        // Empty body should not crash; returns null from parse failure
        val result = bf.queryByTaskId("empty-body")
        assertNull(result)
    }

    @Test
    fun `queryByTaskId returns null when JSON array is empty`() = runBlocking {
        val bf = backflow(fakeClient(200, "[]"))
        assertNull(bf.queryByTaskId("t-empty"))
    }

    // ── MemoryEntry defaults ───────────────────────────────────────────────────

    @Test
    fun `MemoryEntry default route_mode is local`() {
        val entry = MemoryEntry(task_id = "t", goal = "g", status = "ok", summary = "s")
        assertEquals("local", entry.route_mode)
    }

    @Test
    fun `MemoryEntry default steps is empty list`() {
        val entry = MemoryEntry(task_id = "t", goal = "g", status = "ok", summary = "s")
        assertTrue(entry.steps.isEmpty())
    }

    @Test
    fun `MemoryEntry timestamp_ms is set to non-zero by default`() {
        val entry = MemoryEntry(task_id = "t", goal = "g", status = "ok", summary = "s")
        assertTrue("timestamp_ms should be positive", entry.timestamp_ms > 0L)
    }

    // ── MemoryEntry field contract ─────────────────────────────────────────────

    @Test
    fun `MemoryEntry stores all required fields`() {
        val entry = sampleEntry("field-test")

        assertEquals("field-test", entry.task_id)
        assertEquals("open WeChat", entry.goal)
        assertEquals("success", entry.status)
        assertEquals("opened app successfully", entry.summary)
        assertEquals(listOf("tap app icon", "wait for launch"), entry.steps)
        assertEquals("cross_device", entry.route_mode)
        assertEquals(1700000000000L, entry.timestamp_ms)
    }

    @Test
    fun `MemoryEntry round-trips through Gson serialisation`() {
        val gson = com.google.gson.Gson()
        val original = sampleEntry("rt-001")
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, MemoryEntry::class.java)

        assertEquals(original.task_id, restored.task_id)
        assertEquals(original.goal, restored.goal)
        assertEquals(original.status, restored.status)
        assertEquals(original.summary, restored.summary)
        assertEquals(original.steps, restored.steps)
        assertEquals(original.route_mode, restored.route_mode)
        assertEquals(original.timestamp_ms, restored.timestamp_ms)
    }

    @Test
    fun `Gson serialises MemoryEntry with snake_case field names`() {
        val gson = com.google.gson.Gson()
        val json = gson.toJson(sampleEntry("snake-001"))

        assertTrue("JSON must contain task_id", json.contains("\"task_id\""))
        assertTrue("JSON must contain route_mode", json.contains("\"route_mode\""))
        assertTrue("JSON must contain timestamp_ms", json.contains("\"timestamp_ms\""))
    }

    // ── 404 的两种含义,以及"不再有 legacy 兜底" ────────────────────────────
    //
    // 这一整块此前断言的是"v1 404 就降级到 /api/memory/*"。实测:V2 上**根本没有**
    // 无版本的 memory 路由,那条兜底从来没救回过任何一次请求 —— 它只是在 v1 真出问题时
    // 多打一次注定失败的请求,并留下一条"已降级"的假象日志。兜底已删,这些用例
    // 相应改成断言新的行为,并且**把"只打一次"本身钉住** —— 否则兜底哪天被谁加回来,
    // 没有任何断言会红。

    /** 记录每一次实际发出的请求 URL,并按调用方给定的码作答。 */
    private fun countingClient(code: Int, body: String = "", seen: MutableList<String>): OkHttpClient {
        val interceptor = Interceptor { chain ->
            seen += chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    @Test
    fun `store returns false on 404 and does not attempt a second request`() = runBlocking {
        val seen = mutableListOf<String>()
        val bf = backflow(countingClient(code = 404, seen = seen))
        assertFalse("v1 404 时 store 应直接失败", bf.store(sampleEntry()))
        assertEquals("不该再打第二次(V2 上没有无版本的 memory 路由)", 1, seen.size)
        assertTrue("唯一那次必须打 v1", seen[0].contains("/api/v1/memory/store"))
    }

    @Test
    fun `store succeeds via v1`() = runBlocking {
        val seen = mutableListOf<String>()
        val bf = backflow(countingClient(code = 200, seen = seen))
        assertTrue(bf.store(sampleEntry()))
        assertEquals(1, seen.size)
    }

    /**
     * 服务端对"这条记录不存在"返回的是 **带自家信封的 404**
     * (`{"success":false,"error":"not found",...}`)。那是正常的 miss,不是端点缺失,
     * 不该触发任何降级 —— 此前不分,于是每一次缓存未命中都会白打一次请求,
     * 还打出一条"v1 returned 404"的告警,真出事时会把人往错的方向带。
     */
    @Test
    fun `queryByTaskId treats an enveloped 404 as a plain miss`() = runBlocking {
        val seen = mutableListOf<String>()
        val bf = backflow(
            countingClient(code = 404, body = """{"success":false,"error":"not found","task_id":"missing"}""", seen = seen)
        )
        assertNull("语义 miss 应返回 null", bf.queryByTaskId("missing"))
        assertEquals("语义 miss 不该引发第二次请求", 1, seen.size)
    }

    /** FastAPI 的路由级 404 是 `{"detail":"Not Found"}` —— 没有 success 字段。 */
    @Test
    fun `queryByTaskId treats a routing 404 as endpoint missing`() = runBlocking {
        val seen = mutableListOf<String>()
        val bf = backflow(countingClient(code = 404, body = """{"detail":"Not Found"}""", seen = seen))
        assertNull(bf.queryByTaskId("missing"))
        // 端点缺失同样返回 null,但走的是另一条分支;这里同样不该有第二次请求,
        // 因为兜底目标在 V2 上也不存在。
        assertEquals(1, seen.size)
    }

    @Test
    fun `isEndpointMissing distinguishes the two kinds of 404`() {
        // 带自家信封 → 记录不存在,端点是在的
        assertFalse(OpenClawdMemoryBackflow.isEndpointMissing("""{"success":false,"error":"not found"}"""))
        assertFalse(OpenClawdMemoryBackflow.isEndpointMissing("""{"success":true}"""))
        // FastAPI 路由级 404 / 空体 / 读不出 JSON → 按端点缺失处理。
        // 读不出时**刻意**倒向"端点缺失":那一侧只是多一次兜底请求,
        // 反过来判错会把真正的端点缺失静默吞掉。
        assertTrue(OpenClawdMemoryBackflow.isEndpointMissing("""{"detail":"Not Found"}"""))
        assertTrue(OpenClawdMemoryBackflow.isEndpointMissing(""))
        assertTrue(OpenClawdMemoryBackflow.isEndpointMissing(null))
        assertTrue(OpenClawdMemoryBackflow.isEndpointMissing("not json at all"))
    }

    @Test
    fun `queryByTaskId parses the entry on 200`() = runBlocking {
        val seen = mutableListOf<String>()
        val bf = backflow(countingClient(code = 200, body = sampleJson("q-ok-001"), seen = seen))
        val result = bf.queryByTaskId("q-ok-001")
        assertNotNull(result)
        assertEquals("q-ok-001", result!!.task_id)
        assertEquals(1, seen.size)
    }
}
