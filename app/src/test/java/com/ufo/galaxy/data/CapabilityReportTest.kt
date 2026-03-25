package com.ufo.galaxy.data

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 AIP v3.0 能力上报载荷字段，确保服务端 Loop 3（自动扩展）
 * 所需的 platform、device_id、supported_actions、version 字段均已包含。
 *
 * Also validates the canonical runtime identity contract enforced by
 * [CapabilityReport.REQUIRED_METADATA_KEYS] and [CapabilityReport.validate].
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

    // ── Canonical runtime identity contract ──────────────────────────────────

    @Test
    fun `REQUIRED_METADATA_KEYS contains exactly the eight canonical fields`() {
        val keys = CapabilityReport.REQUIRED_METADATA_KEYS

        assertEquals("must have exactly 8 canonical keys", 8, keys.size)
        assertTrue(keys.contains("goal_execution_enabled"))
        assertTrue(keys.contains("local_model_enabled"))
        assertTrue(keys.contains("cross_device_enabled"))
        assertTrue(keys.contains("parallel_execution_enabled"))
        assertTrue(keys.contains("device_role"))
        assertTrue(keys.contains("model_ready"))
        assertTrue(keys.contains("accessibility_ready"))
        assertTrue(keys.contains("overlay_ready"))
    }

    @Test
    fun `validate returns true when all eight required keys are present`() {
        val report = CapabilityReport(
            platform = "android",
            device_id = "dev-validate-001",
            supported_actions = listOf("screen_capture"),
            metadata = mapOf(
                "goal_execution_enabled" to true,
                "local_model_enabled" to true,
                "cross_device_enabled" to true,
                "parallel_execution_enabled" to false,
                "device_role" to "phone",
                "model_ready" to true,
                "accessibility_ready" to true,
                "overlay_ready" to true
            )
        )

        assertTrue("validate() must return true for a complete metadata map", report.validate())
        assertTrue("missingMetadataKeys() must be empty", report.missingMetadataKeys().isEmpty())
    }

    @Test
    fun `validate returns false when metadata is empty`() {
        val report = CapabilityReport(
            platform = "android",
            device_id = "dev-validate-002",
            supported_actions = listOf("screen_capture")
        )

        assertFalse("validate() must return false for empty metadata", report.validate())
        assertEquals(
            "all 8 keys must be reported missing",
            CapabilityReport.REQUIRED_METADATA_KEYS,
            report.missingMetadataKeys()
        )
    }

    @Test
    fun `validate returns false when readiness flags are missing`() {
        val report = CapabilityReport(
            platform = "android",
            device_id = "dev-validate-003",
            supported_actions = listOf("screen_capture"),
            metadata = mapOf(
                "goal_execution_enabled" to true,
                "local_model_enabled" to true,
                "cross_device_enabled" to true,
                "parallel_execution_enabled" to false,
                "device_role" to "phone"
            )
        )

        assertFalse("validate() must return false when readiness flags are absent", report.validate())
        val missing = report.missingMetadataKeys()
        assertTrue(missing.contains("model_ready"))
        assertTrue(missing.contains("accessibility_ready"))
        assertTrue(missing.contains("overlay_ready"))
    }

    @Test
    fun `toMetadataMap from InMemoryAppSettings produces a valid CapabilityReport`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            goalExecutionEnabled = true,
            localModelEnabled = true,
            parallelExecutionEnabled = false,
            deviceRole = "phone",
            modelReady = true,
            accessibilityReady = true,
            overlayReady = true
        )

        val report = CapabilityReport(
            platform = "android",
            device_id = "dev-settings-001",
            supported_actions = listOf("screen_capture"),
            metadata = settings.toMetadataMap()
        )

        assertTrue(
            "CapabilityReport built from AppSettings.toMetadataMap() must pass validate()",
            report.validate()
        )
    }
}
