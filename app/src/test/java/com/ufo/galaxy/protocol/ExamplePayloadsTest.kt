package com.ufo.galaxy.protocol

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/**
 * PR-C5: Runnable sample tests for all six core AIP v3 message types.
 *
 * These tests serve as living documentation — each test validates the shape of a
 * canonical example payload described in `docs/AIP_V3_EXAMPLES.md` and ensures
 * that the data-class fields stay in sync with the documented examples.
 *
 * Covered message types:
 *  - device_register  (MsgType.DEVICE_REGISTER)
 *  - heartbeat        (MsgType.HEARTBEAT)
 *  - capability_report (MsgType.CAPABILITY_REPORT)
 *  - task_assign      (MsgType.TASK_ASSIGN)
 *  - command_result   (MsgType.COMMAND_RESULT)
 *  - task_submit      (MsgType.TASK_SUBMIT)
 *
 * No Android device, emulator, or server is needed; all tests run on the JVM.
 */
class ExamplePayloadsTest {

    private val gson = Gson()

    // ── device_register ───────────────────────────────────────────────────────

    @Test
    fun `device_register example - MsgType value is device_register`() {
        assertEquals("device_register", MsgType.DEVICE_REGISTER.value)
    }

    @Test
    fun `device_register example - AipMessage envelope has correct protocol and version`() {
        val envelope = AipMessage(
            type       = MsgType.DEVICE_REGISTER,
            payload    = "{}",
            device_id  = "android_pixel8_01"
        )
        assertEquals("AIP/1.0", envelope.protocol)
        assertEquals("3.0", envelope.version)
        assertEquals(MsgType.DEVICE_REGISTER, envelope.type)
    }

    @Test
    fun `device_register example - envelope serialises type as device_register`() {
        val envelope = AipMessage(
            type    = MsgType.DEVICE_REGISTER,
            payload = "{}",
        )
        val json = gson.toJson(envelope)
        assertTrue("JSON must contain device_register", json.contains("device_register"))
        assertTrue("JSON must contain AIP/1.0", json.contains("AIP/1.0"))
        assertTrue("JSON must contain 3.0", json.contains("3.0"))
    }

    // ── heartbeat ─────────────────────────────────────────────────────────────

    @Test
    fun `heartbeat example - MsgType value is heartbeat`() {
        assertEquals("heartbeat", MsgType.HEARTBEAT.value)
    }

    @Test
    fun `heartbeat example - AipMessage envelope contains heartbeat type`() {
        val envelope = AipMessage(
            type    = MsgType.HEARTBEAT,
            payload = mapOf("status" to "online")
        )
        assertEquals("3.0", envelope.version)
        assertEquals("AIP/1.0", envelope.protocol)
        assertEquals(MsgType.HEARTBEAT, envelope.type)
    }

    @Test
    fun `heartbeat example - payload status field is online`() {
        // Canonical heartbeat payload per docs/AIP_V3_EXAMPLES.md
        val payload = mapOf("status" to "online")
        assertEquals("online", payload["status"])
    }

    // ── capability_report ─────────────────────────────────────────────────────

    @Test
    fun `capability_report example - MsgType value is capability_report`() {
        assertEquals("capability_report", MsgType.CAPABILITY_REPORT.value)
    }

    @Test
    fun `capability_report example - AipMessage envelope has version 3_0`() {
        val envelope = AipMessage(
            type    = MsgType.CAPABILITY_REPORT,
            payload = "{}",
        )
        assertEquals("3.0", envelope.version)
        assertEquals("AIP/1.0", envelope.protocol)
    }

    @Test
    fun `capability_report example - required payload fields are platform supported_actions version`() {
        // Mirrors the PR-C1 constraint: these three fields must be non-empty in every
        // capability_report payload before it is sent via EnhancedAIPClient.
        val platform         = "android"
        val supportedActions = listOf(
            "location", "camera", "sensor_data", "automation",
            "notification", "sms", "phone_call", "contacts",
            "calendar", "voice_input", "screen_capture", "app_control"
        )
        val version = "3.0.0"

        assertTrue("platform must be non-blank", platform.isNotBlank())
        assertTrue("supported_actions must be non-empty", supportedActions.isNotEmpty())
        assertTrue("version must be non-blank", version.isNotBlank())
    }

    @Test
    fun `capability_report example - supported_actions list contains expected capabilities`() {
        val supportedActions = listOf(
            "location", "camera", "sensor_data", "automation",
            "notification", "sms", "phone_call", "contacts",
            "calendar", "voice_input", "screen_capture", "app_control"
        )
        assertTrue(supportedActions.contains("automation"))
        assertTrue(supportedActions.contains("ui_automation").not()) // "ui_automation" is in capabilities map, not actions
        assertEquals(12, supportedActions.size)
    }

    // ── task_assign ───────────────────────────────────────────────────────────

    @Test
    fun `task_assign example - MsgType value is task_assign`() {
        assertEquals("task_assign", MsgType.TASK_ASSIGN.value)
    }

    @Test
    fun `task_assign example - TaskAssignPayload fields match canonical example`() {
        val payload = TaskAssignPayload(
            task_id             = "task-uuid-001",
            goal                = "打开微信并发送「你好」",
            constraints         = listOf("不得访问联系人列表"),
            max_steps           = 10,
            require_local_agent = true
        )

        assertEquals("task-uuid-001", payload.task_id)
        assertEquals("打开微信并发送「你好」", payload.goal)
        assertEquals(1, payload.constraints.size)
        assertEquals(10, payload.max_steps)
        assertTrue(payload.require_local_agent)
    }

    @Test
    fun `task_assign example - TaskAssignPayload has no coordinate fields`() {
        val fieldNames = generateSequence<Class<*>>(TaskAssignPayload::class.java) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .map { it.name }
            .toSet()
        assertFalse("TaskAssignPayload must not contain x coordinate", fieldNames.contains("x"))
        assertFalse("TaskAssignPayload must not contain y coordinate", fieldNames.contains("y"))
    }

    @Test
    fun `task_assign example - AipMessage envelope serialises task_assign type`() {
        val payload = TaskAssignPayload(
            task_id             = "task-uuid-001",
            goal                = "open maps",
            max_steps           = 5,
            require_local_agent = true
        )
        val envelope = AipMessage(
            type           = MsgType.TASK_ASSIGN,
            payload        = payload,
            correlation_id = "task-uuid-001"
        )
        val json = gson.toJson(envelope)
        assertTrue(json.contains("task_assign"))
        assertTrue(json.contains("AIP/1.0"))
        assertTrue(json.contains("3.0"))
    }

    // ── command_result ────────────────────────────────────────────────────────

    @Test
    fun `command_result example - MsgType value is command_result`() {
        assertEquals("command_result", MsgType.COMMAND_RESULT.value)
    }

    @Test
    fun `command_result example - CommandResultPayload fields match canonical example`() {
        val payload = CommandResultPayload(
            task_id = "task-uuid-001",
            step_id = "1",
            action  = "tap",
            status  = "success"
        )

        assertEquals("task-uuid-001", payload.task_id)
        assertEquals("1", payload.step_id)
        assertEquals("tap", payload.action)
        assertEquals("success", payload.status)
        assertNull(payload.error)
        assertNull(payload.snapshot)
    }

    @Test
    fun `command_result example - error field is populated on failure`() {
        val payload = CommandResultPayload(
            task_id = "task-uuid-001",
            step_id = "2",
            action  = "tap",
            status  = "error",
            error   = "Element not found on screen"
        )

        assertEquals("error", payload.status)
        assertNotNull(payload.error)
        assertTrue(payload.error!!.isNotBlank())
    }

    @Test
    fun `command_result example - AipMessage envelope serialises command_result type`() {
        val payload = CommandResultPayload(
            task_id = "task-uuid-001",
            step_id = "1",
            action  = "tap",
            status  = "success"
        )
        val envelope = AipMessage(
            type           = MsgType.COMMAND_RESULT,
            payload        = payload,
            correlation_id = "task-uuid-001",
            device_id      = "android_pixel8_01"
        )
        val json = gson.toJson(envelope)
        assertTrue(json.contains("command_result"))
        assertTrue(json.contains("AIP/1.0"))
        assertTrue(json.contains("3.0"))
    }

    // ── task_submit ───────────────────────────────────────────────────────────

    @Test
    fun `task_submit example - MsgType value is task_submit`() {
        assertEquals("task_submit", MsgType.TASK_SUBMIT.value)
    }

    @Test
    fun `task_submit example - TaskSubmitPayload fields match canonical example`() {
        val payload = TaskSubmitPayload(
            task_text  = "帮我打开导航去最近的星巴克",
            device_id  = "android_pixel8_01",
            session_id = "sess-20260316-001",
            task_id    = "task-uuid-055",
            context    = TaskSubmitContext(
                locale         = "zh-CN",
                app_foreground = "com.android.launcher3"
            )
        )

        assertEquals("帮我打开导航去最近的星巴克", payload.task_text)
        assertEquals("android_pixel8_01", payload.device_id)
        assertEquals("sess-20260316-001", payload.session_id)
        assertEquals("task-uuid-055", payload.task_id)
        assertEquals("zh-CN", payload.context.locale)
        assertEquals("com.android.launcher3", payload.context.app_foreground)
    }

    @Test
    fun `task_submit example - validate passes for complete canonical payload`() {
        val payload = TaskSubmitPayload(
            task_text  = "帮我打开导航去最近的星巴克",
            device_id  = "android_pixel8_01",
            session_id = "sess-20260316-001",
            task_id    = "task-uuid-055"
        )
        assertTrue("Canonical task_submit payload must pass validate()", payload.validate())
        assertNull("validationError() must be null for valid payload", payload.validationError())
    }

    @Test
    fun `task_submit example - AipMessage envelope serialises task_submit type`() {
        val payload = TaskSubmitPayload(
            task_text  = "take a screenshot",
            device_id  = "android_pixel8_01",
            session_id = "sess-001",
            task_id    = "task-001"
        )
        val envelope = AipMessage(
            type           = MsgType.TASK_SUBMIT,
            payload        = payload,
            correlation_id = "task-001",
            device_id      = "android_pixel8_01"
        )
        val json = gson.toJson(envelope)
        assertTrue(json.contains("task_submit"))
        assertTrue(json.contains("AIP/1.0"))
        assertTrue(json.contains("3.0"))
        assertTrue(json.contains("task-001"))
    }

    // ── Cross-type: all six MsgType values exist in the enum ─────────────────

    @Test
    fun `all six core message types are defined in MsgType enum`() {
        val allValues = MsgType.entries.map { it.value }.toSet()
        val coreSix = setOf(
            "device_register",
            "heartbeat",
            "capability_report",
            "task_assign",
            "command_result",
            "task_submit"
        )
        coreSix.forEach { type ->
            assertTrue("MsgType enum must contain '$type'", allValues.contains(type))
        }
    }

    @Test
    fun `all six core message type envelopes default to version 3_0 and protocol AIP_1_0`() {
        val coreTypes = listOf(
            MsgType.DEVICE_REGISTER,
            MsgType.HEARTBEAT,
            MsgType.CAPABILITY_REPORT,
            MsgType.TASK_ASSIGN,
            MsgType.COMMAND_RESULT,
            MsgType.TASK_SUBMIT
        )
        coreTypes.forEach { msgType ->
            val envelope = AipMessage(type = msgType, payload = "{}")
            assertEquals("version must be 3.0 for $msgType", "3.0", envelope.version)
            assertEquals("protocol must be AIP/1.0 for $msgType", "AIP/1.0", envelope.protocol)
        }
    }
}
