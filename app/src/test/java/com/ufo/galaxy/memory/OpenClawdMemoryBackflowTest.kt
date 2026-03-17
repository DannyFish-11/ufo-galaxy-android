package com.ufo.galaxy.memory

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
        OpenClawdMemoryBackflow(restBaseUrl = "http://100.0.0.1:8765", httpClient = client)

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
    fun `store returns true when server responds with 200`() {
        val bf = backflow(fakeClient(200, "{}"))
        assertTrue("store must return true on 200", bf.store(sampleEntry()))
    }

    @Test
    fun `store returns true when server responds with 201`() {
        val bf = backflow(fakeClient(201, "{}"))
        assertTrue("store must return true on 201", bf.store(sampleEntry()))
    }

    @Test
    fun `store returns false when server responds with 500`() {
        val bf = backflow(fakeClient(500))
        assertFalse("store must return false on 500", bf.store(sampleEntry()))
    }

    @Test
    fun `store returns false when server responds with 400`() {
        val bf = backflow(fakeClient(400))
        assertFalse("store must return false on 400", bf.store(sampleEntry()))
    }

    @Test
    fun `store returns false on network exception`() {
        val bf = backflow(errorClient())
        assertFalse("store must return false on network exception", bf.store(sampleEntry()))
    }

    // ── queryByTaskId() ────────────────────────────────────────────────────────

    @Test
    fun `queryByTaskId returns entry when server responds with single JSON object`() {
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
    fun `queryByTaskId returns first entry when server responds with JSON array`() {
        val json = """[${sampleJson("arr-001")},${sampleJson("arr-002")}]"""
        val bf = backflow(fakeClient(200, json))

        val result = bf.queryByTaskId("arr-001")

        assertNotNull(result)
        assertEquals("arr-001", result!!.task_id)
    }

    @Test
    fun `queryByTaskId returns null when server responds with 404`() {
        val bf = backflow(fakeClient(404))
        assertNull("result must be null for 404", bf.queryByTaskId("missing-id"))
    }

    @Test
    fun `queryByTaskId returns null on network exception`() {
        val bf = backflow(errorClient())
        assertNull("result must be null on network exception", bf.queryByTaskId("t-001"))
    }

    @Test
    fun `queryByTaskId returns null when response body is empty`() {
        val bf = backflow(fakeClient(200, ""))
        // Empty body should not crash; returns null from parse failure
        val result = bf.queryByTaskId("empty-body")
        assertNull(result)
    }

    @Test
    fun `queryByTaskId returns null when JSON array is empty`() {
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

    // ── v1-first with 404 legacy fallback ──────────────────────────────────────

    /**
     * Builds an [OkHttpClient] that returns [v1Code] for v1 URL patterns
     * (`/api/v1/...`) and [legacyCode] for all other paths.
     */
    private fun routingClient(
        v1Code: Int,
        legacyCode: Int,
        legacyBody: String = ""
    ): OkHttpClient {
        val interceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            val (code, body) = if ("/api/v1/" in url) Pair(v1Code, "") else Pair(legacyCode, legacyBody)
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
    fun `store falls back to legacy endpoint when v1 returns 404`() {
        // v1 returns 404; legacy returns 200
        val client = routingClient(v1Code = 404, legacyCode = 200)
        val bf = backflow(client)
        assertTrue("store must return true when legacy endpoint succeeds after v1 404", bf.store(sampleEntry()))
    }

    @Test
    fun `store returns false when both v1 and legacy return non-2xx`() {
        val client = routingClient(v1Code = 404, legacyCode = 500)
        val bf = backflow(client)
        assertFalse("store must return false when legacy also fails", bf.store(sampleEntry()))
    }

    @Test
    fun `store uses v1 endpoint and does not call legacy when v1 succeeds`() {
        // v1 returns 200; legacy would fail with 500 (should never be reached)
        val client = routingClient(v1Code = 200, legacyCode = 500)
        val bf = backflow(client)
        assertTrue("store must return true when v1 succeeds without touching legacy", bf.store(sampleEntry()))
    }

    @Test
    fun `queryByTaskId falls back to legacy endpoint when v1 returns 404`() {
        val legacyBody = sampleJson("fallback-q-001")
        val client = routingClient(v1Code = 404, legacyCode = 200, legacyBody = legacyBody)
        val bf = backflow(client)

        val result = bf.queryByTaskId("fallback-q-001")

        assertNotNull("queryByTaskId must return an entry from the legacy fallback", result)
        assertEquals("fallback-q-001", result!!.task_id)
    }

    @Test
    fun `queryByTaskId returns null when both v1 and legacy return 404`() {
        val client = routingClient(v1Code = 404, legacyCode = 404)
        val bf = backflow(client)
        assertNull("queryByTaskId must return null when both endpoints return 404", bf.queryByTaskId("missing"))
    }
}
