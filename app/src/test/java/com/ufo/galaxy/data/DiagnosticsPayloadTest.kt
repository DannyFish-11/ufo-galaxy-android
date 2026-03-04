package com.ufo.galaxy.data

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证结构化诊断/遥测载荷字段，确保服务端 Loop 1（自修复）和 Loop 2（学习反馈）
 * 所需的 error_type、error_context、task_id、node_name 字段均已包含。
 */
class DiagnosticsPayloadTest {

    @Test
    fun `diagnostics payload contains required fields for server-side learning`() {
        val payload = DiagnosticsPayload(
            error_type = "network_timeout",
            error_context = "Connection timed out after 30s on task step 2",
            task_id = "task-abc-123",
            node_name = "android_agent_01"
        )

        assertEquals("network_timeout", payload.error_type)
        assertEquals("Connection timed out after 30s on task step 2", payload.error_context)
        assertEquals("task-abc-123", payload.task_id)
        assertEquals("android_agent_01", payload.node_name)
        assertTrue("timestamp must be positive", payload.timestamp > 0)
    }

    @Test
    fun `diagnostics payload records timestamp automatically`() {
        val before = System.currentTimeMillis()
        val payload = DiagnosticsPayload(
            error_type = "permission_denied",
            error_context = "Camera permission not granted",
            task_id = "task-xyz-456",
            node_name = "android_agent_02"
        )
        val after = System.currentTimeMillis()

        assertTrue(payload.timestamp in before..after)
    }

    @Test
    fun `diagnostics payload supports diverse error types`() {
        val errorTypes = listOf(
            "network_timeout", "permission_denied", "task_parse_error",
            "execution_failed", "unknown"
        )

        for (errorType in errorTypes) {
            val payload = DiagnosticsPayload(
                error_type = errorType,
                error_context = "context for $errorType",
                task_id = "task-001",
                node_name = "node-001"
            )
            assertEquals(errorType, payload.error_type)
        }
    }
}
