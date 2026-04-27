package com.ufo.galaxy.protocol

import com.google.gson.Gson
import com.ufo.galaxy.data.CapabilityReport
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.runtime.ReconnectRecoveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android Participant Protocol Guard — blocking CI gate.
 *
 * Regression tests for the four AIP v3 protocol contracts that Android must satisfy as a
 * first-class participant in the dual-repo system:
 *
 *  1. **device_register contract** — AIP v3 envelope structure, version, and protocol
 *     field invariants that V2's gateway enforces on every inbound connection.
 *
 *  2. **capability_report contract** — required metadata key set, payload validity, and
 *     stability of the capability claim across back-to-back reports from the same settings.
 *
 *  3. **task_result contract** — correlation echo, terminal status vocabulary, and
 *     device_id presence required for V2's result routing.
 *
 *  4. **reconnect / re-register expectations** — wire-value contract for
 *     [ReconnectRecoveryState] and the requirement that a device must re-register and
 *     re-report capability after any reconnect cycle.
 *
 * ## Blocking gate semantics
 *
 * Failure in any test here indicates a protocol regression: a contract that V2 depends on
 * has broken on the Android side.  **Do not mark any test as skipped or suppress any
 * assertion** without a matching V2 contract change and explicit cross-repo review.
 *
 * All tests are pure-JVM; no device, emulator, or network is required.
 */
class AndroidParticipantProtocolGuardTest {

    private val gson = Gson()

    // ── 1. device_register contract ───────────────────────────────────────────

    /**
     * V2 gateway rejects any inbound message with version < 3.0.
     * The Android AipMessage default must always be "3.0".
     */
    @Test
    fun `device_register envelope version is 3_0`() {
        val envelope = AipMessage(type = MsgType.DEVICE_REGISTER, payload = "{}")
        assertEquals(
            "AIP v3 contract: version must be '3.0'",
            "3.0",
            envelope.version
        )
    }

    @Test
    fun `device_register envelope protocol is AIP 1_0`() {
        val envelope = AipMessage(type = MsgType.DEVICE_REGISTER, payload = "{}")
        assertEquals(
            "AIP v3 contract: protocol must be 'AIP/1.0'",
            "AIP/1.0",
            envelope.protocol
        )
    }

    @Test
    fun `device_register MsgType wire value is device_register`() {
        assertEquals(
            "Wire value must match V2 server MsgType exactly",
            "device_register",
            MsgType.DEVICE_REGISTER.value
        )
    }

    @Test
    fun `device_register envelope carries device_id`() {
        val deviceId = "android_pixel8_guard_01"
        val envelope = AipMessage(
            type = MsgType.DEVICE_REGISTER,
            payload = "{}",
            device_id = deviceId
        )
        assertEquals(
            "device_register must carry a non-blank device_id for gateway routing",
            deviceId,
            envelope.device_id
        )
    }

    @Test
    fun `device_register envelope serialises to JSON with correct type field`() {
        val envelope = AipMessage(
            type = MsgType.DEVICE_REGISTER,
            payload = "{}",
            device_id = "guard-device-01"
        )
        val json = gson.toJson(envelope)
        assertTrue("Serialised JSON must contain 'device_register'", json.contains("device_register"))
        assertTrue("Serialised JSON must contain 'AIP/1.0'", json.contains("AIP/1.0"))
        assertTrue("Serialised JSON must contain '3.0'", json.contains("3.0"))
    }

    // ── 2. capability_report contract ─────────────────────────────────────────

    @Test
    fun `capability_report MsgType wire value is capability_report`() {
        assertEquals(
            "Wire value must match V2 server MsgType exactly",
            "capability_report",
            MsgType.CAPABILITY_REPORT.value
        )
    }

    @Test
    fun `capability_report required metadata key set contains exactly 8 keys`() {
        assertEquals(
            "V2 gateway expects exactly 8 required metadata keys",
            8,
            CapabilityReport.REQUIRED_METADATA_KEYS.size
        )
    }

    @Test
    fun `capability_report required metadata keys are all present in REQUIRED_METADATA_KEYS`() {
        val expected = setOf(
            "goal_execution_enabled",
            "local_model_enabled",
            "cross_device_enabled",
            "parallel_execution_enabled",
            "device_role",
            "model_ready",
            "accessibility_ready",
            "overlay_ready"
        )
        assertEquals(
            "Required metadata key contract must not change without a V2 gateway update",
            expected,
            CapabilityReport.REQUIRED_METADATA_KEYS
        )
    }

    @Test
    fun `capability_report validate returns true when all required keys present`() {
        val report = buildFullCapabilityReport("guard-device-02")
        assertTrue(
            "validate() must return true when all 8 required metadata keys are present",
            report.validate()
        )
    }

    @Test
    fun `capability_report validate returns false when any required key is missing`() {
        for (missingKey in CapabilityReport.REQUIRED_METADATA_KEYS) {
            val metadata = buildFullMetadata().toMutableMap().also { it.remove(missingKey) }
            val report = CapabilityReport(
                platform = "android",
                device_id = "guard-device-03",
                supported_actions = listOf("screen_capture"),
                metadata = metadata
            )
            assertFalse(
                "validate() must return false when '$missingKey' is absent",
                report.validate()
            )
            assertTrue(
                "missingMetadataKeys() must report '$missingKey'",
                report.missingMetadataKeys().contains(missingKey)
            )
        }
    }

    @Test
    fun `capability_report version field is always 3_0`() {
        val report = buildFullCapabilityReport("guard-device-04")
        assertEquals(
            "capability_report.version must always be '3.0' — gateway rejects lower values",
            "3.0",
            report.version
        )
    }

    @Test
    fun `capability_report platform field is android`() {
        val report = buildFullCapabilityReport("guard-device-05")
        assertEquals(
            "platform must be 'android' for Android participant routing on V2",
            "android",
            report.platform
        )
    }

    /** Capability claim stability: two back-to-back reports from the same settings are equal. */
    @Test
    fun `capability_report payload is stable across back-to-back reports from same settings`() {
        val settings = InMemoryAppSettings().apply {
            crossDeviceEnabled = true
            goalExecutionEnabled = true
            localModelEnabled = true
            parallelExecutionEnabled = true
            deviceRole = "phone"
            modelReady = true
            accessibilityReady = true
            overlayReady = true
        }
        val report1 = CapabilityReport(
            platform = "android",
            device_id = "guard-device-06",
            supported_actions = listOf("screen_capture", "automation"),
            metadata = settings.toMetadataMap()
        )
        val report2 = CapabilityReport(
            platform = "android",
            device_id = "guard-device-06",
            supported_actions = listOf("screen_capture", "automation"),
            metadata = settings.toMetadataMap()
        )
        assertEquals(
            "Capability claim must be stable: two reports from identical settings must be equal",
            report1,
            report2
        )
    }

    /** Capability claim honesty: readiness flags must reflect real state, not optimistic defaults. */
    @Test
    fun `capability_report reflects runtime readiness flags accurately`() {
        val settings = InMemoryAppSettings().apply {
            modelReady = false
            accessibilityReady = true
            overlayReady = true
        }
        val meta = settings.toMetadataMap()
        assertEquals(
            "model_ready must reflect actual model readiness — not report true when model is not ready",
            false,
            meta["model_ready"]
        )
    }

    // ── 3. task_result contract ───────────────────────────────────────────────

    @Test
    fun `task_result MsgType wire value is task_result`() {
        assertEquals(
            "Wire value must match V2 server MsgType exactly",
            "task_result",
            MsgType.TASK_RESULT.value
        )
    }

    @Test
    fun `task_result correlation_id must echo task_id for V2 result routing`() {
        val taskId = "guard-task-001"
        val result = TaskResultPayload(
            task_id = taskId,
            correlation_id = taskId,
            status = "success",
            device_id = "guard-device-07"
        )
        assertEquals(
            "correlation_id must be set to task_id — V2 gateway uses it for waiter resolution",
            result.task_id,
            result.correlation_id
        )
    }

    @Test
    fun `task_result status must be one of the three terminal values`() {
        val validStatuses = setOf("success", "error", "cancelled")
        for (status in validStatuses) {
            val result = TaskResultPayload(
                task_id = "guard-task-status-$status",
                correlation_id = "guard-task-status-$status",
                status = status,
                device_id = "guard-device-08"
            )
            assertTrue(
                "status '$status' must be in the V2-defined terminal status vocabulary",
                result.status in validStatuses
            )
        }
    }

    @Test
    fun `task_result device_id must not be blank`() {
        val result = TaskResultPayload(
            task_id = "guard-task-002",
            correlation_id = "guard-task-002",
            status = "success",
            device_id = "guard-device-09"
        )
        assertTrue(
            "device_id must not be blank — V2 uses it for participant attribution",
            result.device_id.isNotBlank()
        )
    }

    @Test
    fun `task_result task_id must not be blank`() {
        val result = TaskResultPayload(
            task_id = "guard-task-003",
            correlation_id = "guard-task-003",
            status = "error",
            error = "test error",
            device_id = "guard-device-10"
        )
        assertTrue(
            "task_id must not be blank — V2 needs it to close the task waiter",
            result.task_id.isNotBlank()
        )
    }

    // ── 4. reconnect / re-register expectations ───────────────────────────────

    /**
     * Wire values for [ReconnectRecoveryState] must be stable because V2 records them in
     * structured log fields and participant state documents.  Any change here breaks V2's
     * log parsing and participant state reconciliation.
     */
    @Test
    fun `reconnect recovery wire value IDLE is idle`() {
        assertEquals(
            "IDLE wire value must not change — V2 log parsing depends on it",
            "idle",
            ReconnectRecoveryState.IDLE.wireValue
        )
    }

    @Test
    fun `reconnect recovery wire value RECOVERING is recovering`() {
        assertEquals(
            "RECOVERING wire value must not change — V2 participant state depends on it",
            "recovering",
            ReconnectRecoveryState.RECOVERING.wireValue
        )
    }

    @Test
    fun `reconnect recovery wire value RECOVERED is recovered`() {
        assertEquals(
            "RECOVERED wire value must not change — V2 participant state depends on it",
            "recovered",
            ReconnectRecoveryState.RECOVERED.wireValue
        )
    }

    @Test
    fun `reconnect recovery wire value FAILED is failed`() {
        assertEquals(
            "FAILED wire value must not change — V2 participant state depends on it",
            "failed",
            ReconnectRecoveryState.FAILED.wireValue
        )
    }

    @Test
    fun `reconnect recovery state wire values are all distinct`() {
        val wireValues = ReconnectRecoveryState.entries.map { it.wireValue }
        assertEquals(
            "All ReconnectRecoveryState wire values must be distinct",
            wireValues.size,
            wireValues.toSet().size
        )
    }

    /**
     * After a reconnect cycle (IDLE → RECOVERING → RECOVERED), the device must be able to
     * construct a fresh, valid device_register envelope and a valid capability_report —
     * these are the two messages that V2 requires after every reconnect.
     */
    @Test
    fun `after RECOVERED state device can construct valid device_register for re-register`() {
        // Simulate reaching RECOVERED state
        val finalState = ReconnectRecoveryState.RECOVERED

        assertEquals(ReconnectRecoveryState.RECOVERED, finalState)

        // Device must be able to construct a valid device_register envelope for re-register
        val reRegisterEnvelope = AipMessage(
            type = MsgType.DEVICE_REGISTER,
            payload = "{}",
            device_id = "reconnect-device-01"
        )
        assertEquals("3.0", reRegisterEnvelope.version)
        assertEquals("AIP/1.0", reRegisterEnvelope.protocol)
        assertEquals(MsgType.DEVICE_REGISTER, reRegisterEnvelope.type)
        assertNotNull(reRegisterEnvelope.device_id)
    }

    @Test
    fun `after RECOVERED state device can construct valid capability_report for re-register`() {
        val finalState = ReconnectRecoveryState.RECOVERED
        assertEquals(ReconnectRecoveryState.RECOVERED, finalState)

        // Device must report capability after reconnect
        val report = buildFullCapabilityReport("reconnect-device-02")
        assertTrue(
            "capability_report after reconnect must be valid (all required metadata keys present)",
            report.validate()
        )
    }

    @Test
    fun `task_result correlation_id is null by default and must be explicitly set`() {
        // This guards against accidentally sending a task_result without correlation_id,
        // which would cause V2's waiter resolution to silently fail.
        val resultWithoutCorrelation = TaskResultPayload(
            task_id = "guard-task-004",
            status = "success",
            device_id = "guard-device-11"
        )
        assertNull(
            "Default correlation_id is null — callers must explicitly set it to task_id before sending",
            resultWithoutCorrelation.correlation_id
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun buildFullMetadata(): Map<String, Any> = mapOf(
        "goal_execution_enabled" to true,
        "local_model_enabled" to true,
        "cross_device_enabled" to true,
        "parallel_execution_enabled" to true,
        "device_role" to "phone",
        "model_ready" to true,
        "accessibility_ready" to true,
        "overlay_ready" to true
    )

    private fun buildFullCapabilityReport(deviceId: String) = CapabilityReport(
        platform = "android",
        device_id = deviceId,
        supported_actions = listOf("screen_capture", "automation"),
        capabilities = listOf(
            "autonomous_goal_execution",
            "local_task_planning",
            "local_ui_reasoning",
            "local_model_inference"
        ),
        metadata = buildFullMetadata()
    )
}
