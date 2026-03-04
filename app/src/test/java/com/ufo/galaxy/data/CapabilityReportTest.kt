package com.ufo.galaxy.data

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 AIP v3.0 能力上报载荷字段，确保服务端 Loop 3（自动扩展）
 * 所需的 platform、device_id、supported_actions、version 字段均已包含。
 */
class CapabilityReportTest {

    @Test
    fun `capability report contains required AIP v3_0 fields`() {
        val report = CapabilityReport(
            platform = "android",
            device_id = "test-device-001",
            supported_actions = listOf("location", "camera", "automation"),
            version = "3.0"
        )

        assertEquals("android", report.platform)
        assertEquals("test-device-001", report.device_id)
        assertEquals("3.0", report.version)
        assertTrue("supported_actions must not be empty", report.supported_actions.isNotEmpty())
    }

    @Test
    fun `capability report default version is 3_0`() {
        val report = CapabilityReport(
            platform = "android",
            device_id = "device-xyz",
            supported_actions = listOf("screen_capture")
        )

        assertEquals("3.0", report.version)
    }

    @Test
    fun `capability report supported_actions contains expected Android actions`() {
        val expectedActions = listOf(
            "location", "camera", "sensor_data", "automation",
            "notification", "sms", "phone_call", "contacts",
            "calendar", "voice_input", "screen_capture", "app_control"
        )

        val report = CapabilityReport(
            platform = "android",
            device_id = "device-xyz",
            supported_actions = expectedActions
        )

        assertTrue(report.supported_actions.containsAll(expectedActions))
    }
}
