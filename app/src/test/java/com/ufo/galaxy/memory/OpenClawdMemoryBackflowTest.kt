package com.ufo.galaxy.memory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OpenClawdMemoryBackflow] and [MemoryEntry].
 *
 * Network calls are NOT made; [OpenClawdMemoryBackflow.post] and [get] are
 * verified via subclass overrides (test doubles) so the suite runs on the JVM
 * without a real V2 server.
 */
class OpenClawdMemoryBackflowTest {

    // ──────────────────────────────────────────────────────────────────────────
    // MemoryEntry serialisation / deserialisation
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `MemoryEntry toJson contains all required fields`() {
        val entry = MemoryEntry(
            taskId = "task_001",
            goal = "打开微信",
            deviceId = "phone_01",
            status = MemoryEntry.STATUS_SUCCESS,
            summary = "已成功打开微信",
            steps = listOf("click(icon)", "wait(1s)"),
            errorMsg = "",
            routeMode = MemoryEntry.ROUTE_CROSS_DEVICE,
            timestampMs = 1_700_000_000_000L
        )

        val json = entry.toJson()
        assertEquals("task_001", json.getString("task_id"))
        assertEquals("打开微信", json.getString("goal"))
        assertEquals("phone_01", json.getString("device_id"))
        assertEquals(MemoryEntry.STATUS_SUCCESS, json.getString("status"))
        assertEquals("已成功打开微信", json.getString("summary"))
        assertEquals(MemoryEntry.ROUTE_CROSS_DEVICE, json.getString("route_mode"))
        assertEquals(1_700_000_000_000L, json.getLong("timestamp_ms"))
        assertEquals(2, json.getJSONArray("steps").length())
    }

    @Test
    fun `MemoryEntry fromJson round-trips correctly`() {
        val original = MemoryEntry(
            taskId = "t_99",
            goal = "send email",
            deviceId = "tablet_02",
            status = MemoryEntry.STATUS_ERROR,
            summary = "",
            steps = listOf("step_a"),
            errorMsg = "timeout",
            routeMode = MemoryEntry.ROUTE_LOCAL,
            timestampMs = 1_000L
        )
        val restored = MemoryEntry.fromJson(original.toJson())
        assertEquals(original.taskId, restored.taskId)
        assertEquals(original.goal, restored.goal)
        assertEquals(original.deviceId, restored.deviceId)
        assertEquals(original.status, restored.status)
        assertEquals(original.errorMsg, restored.errorMsg)
        assertEquals(original.routeMode, restored.routeMode)
        assertEquals(original.timestampMs, restored.timestampMs)
        assertEquals(1, restored.steps.size)
        assertEquals("step_a", restored.steps[0])
    }

    @Test
    fun `MemoryEntry fromJson handles missing optional fields gracefully`() {
        val minimal = JSONObject().apply {
            put("task_id", "minimal_task")
            put("goal", "open app")
        }
        val entry = MemoryEntry.fromJson(minimal)
        assertEquals("minimal_task", entry.taskId)
        assertEquals("open app", entry.goal)
        assertEquals("", entry.deviceId)
        assertEquals(MemoryEntry.STATUS_SUCCESS, entry.status)
        assertTrue(entry.steps.isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // buildUrl
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `buildUrl trims trailing slash from base and prepends path`() {
        val backflow = OpenClawdMemoryBackflow("http://host:8000/")
        assertEquals(
            "http://host:8000${OpenClawdMemoryBackflow.STORE_PATH}",
            backflow.buildUrl(OpenClawdMemoryBackflow.STORE_PATH)
        )
    }

    @Test
    fun `buildUrl without trailing slash produces correct URL`() {
        val backflow = OpenClawdMemoryBackflow("http://host:8000")
        assertEquals(
            "http://host:8000${OpenClawdMemoryBackflow.QUERY_PATH}?task_id=abc",
            backflow.buildUrl("${OpenClawdMemoryBackflow.QUERY_PATH}?task_id=abc")
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // store() – test double (post returns controllable result)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `store returns true when post succeeds`() {
        val backflow = object : OpenClawdMemoryBackflow("http://fake:9000", deviceId = "dev_01") {
            override fun post(url: String, body: JSONObject): Boolean = true
        }
        val entry = MemoryEntry(taskId = "t1", goal = "test goal")
        val result = kotlinx.coroutines.runBlocking { backflow.store(entry) }
        assertTrue(result)
    }

    @Test
    fun `store returns false when post fails`() {
        val backflow = object : OpenClawdMemoryBackflow("http://fake:9000") {
            override fun post(url: String, body: JSONObject): Boolean = false
        }
        val entry = MemoryEntry(taskId = "t2", goal = "test goal")
        val result = kotlinx.coroutines.runBlocking { backflow.store(entry) }
        assertFalse(result)
    }

    @Test
    fun `store injects deviceId when entry deviceId is blank`() {
        var capturedBody: JSONObject? = null
        val backflow = object : OpenClawdMemoryBackflow(
            restBaseUrl = "http://fake:9000",
            deviceId = "injected_device"
        ) {
            override fun post(url: String, body: JSONObject): Boolean {
                capturedBody = body
                return true
            }
        }
        val entry = MemoryEntry(taskId = "t3", goal = "goal", deviceId = "")
        kotlinx.coroutines.runBlocking { backflow.store(entry) }
        assertNotNull(capturedBody)
        assertEquals("injected_device", capturedBody!!.getString("device_id"))
    }

    @Test
    fun `store preserves deviceId from entry when provided`() {
        var capturedBody: JSONObject? = null
        val backflow = object : OpenClawdMemoryBackflow(
            restBaseUrl = "http://fake:9000",
            deviceId = "should_not_override"
        ) {
            override fun post(url: String, body: JSONObject): Boolean {
                capturedBody = body
                return true
            }
        }
        val entry = MemoryEntry(taskId = "t4", goal = "goal", deviceId = "from_entry")
        kotlinx.coroutines.runBlocking { backflow.store(entry) }
        assertNotNull(capturedBody)
        assertEquals("from_entry", capturedBody!!.getString("device_id"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // query()
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `query returns JSONObject from get when available`() {
        val fakeResponse = JSONObject().apply {
            put("task_id", "q_task")
            put("status", "success")
        }
        val backflow = object : OpenClawdMemoryBackflow("http://fake:9000") {
            override fun get(url: String): JSONObject? = fakeResponse
        }
        val result = kotlinx.coroutines.runBlocking { backflow.query("q_task") }
        assertNotNull(result)
        assertEquals("q_task", result!!.getString("task_id"))
    }

    @Test
    fun `query returns null when get returns null`() {
        val backflow = object : OpenClawdMemoryBackflow("http://fake:9000") {
            override fun get(url: String): JSONObject? = null
        }
        val result = kotlinx.coroutines.runBlocking { backflow.query("missing_task") }
        assertEquals(null, result)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Constants
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `STATUS constants have expected string values`() {
        assertEquals("success", MemoryEntry.STATUS_SUCCESS)
        assertEquals("error", MemoryEntry.STATUS_ERROR)
        assertEquals("partial", MemoryEntry.STATUS_PARTIAL)
    }

    @Test
    fun `ROUTE constants have expected string values`() {
        assertEquals("local", MemoryEntry.ROUTE_LOCAL)
        assertEquals("cross_device", MemoryEntry.ROUTE_CROSS_DEVICE)
    }
}
