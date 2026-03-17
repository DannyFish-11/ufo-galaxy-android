package com.ufo.galaxy.protocol

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/**
 * PR-C4: TaskSubmitPayload & MsgType alignment tests.
 *
 * Covers:
 *  (a) TaskSubmitPayload v3 shape and required fields
 *  (b) AipMessage envelope protocol / version fields
 *  (c) MsgType v3 constant alignment across all send/receive paths
 *  (d) LEGACY_TYPE_MAP normalization via MsgType.toV3Type()
 *  (e) Negative cases: blank task_text, missing device_id, legacy/v2 type strings
 */
class TaskSubmitV3Test {

    private val gson = Gson()

    // ── (a) TaskSubmitPayload v3 shape ────────────────────────────────────────

    @Test
    fun `TaskSubmitPayload v3 shape contains task_id task_text device_id session_id`() {
        val payload = TaskSubmitPayload(
            task_text = "open WeChat and send hello",
            device_id = "android_device001",
            session_id = "sess-abc-001",
            task_id = "task-uuid-001"
        )

        assertEquals("task-uuid-001", payload.task_id)
        assertEquals("open WeChat and send hello", payload.task_text)
        assertEquals("android_device001", payload.device_id)
        assertEquals("sess-abc-001", payload.session_id)
    }

    @Test
    fun `TaskSubmitPayload has no coordinate fields`() {
        val payload = TaskSubmitPayload(
            task_text = "scroll down",
            device_id = "dev-x",
            session_id = "sess-y",
            task_id = "task-z"
        )
        val fieldNames = generateSequence<Class<*>>(payload.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .map { it.name }
            .toSet()
        assertFalse("TaskSubmitPayload must not contain x coordinate", fieldNames.contains("x"))
        assertFalse("TaskSubmitPayload must not contain y coordinate", fieldNames.contains("y"))
    }

    @Test
    fun `TaskSubmitPayload serialises task_id and task_text into JSON`() {
        val payload = TaskSubmitPayload(
            task_text = "set alarm for 7am",
            device_id = "dev-001",
            session_id = "sess-001",
            task_id = "task-alarm-001"
        )
        val json = gson.toJson(payload)
        assertTrue("JSON must contain task_id", json.contains("task_id"))
        assertTrue("JSON must contain task-alarm-001", json.contains("task-alarm-001"))
        assertTrue("JSON must contain task_text", json.contains("task_text"))
        assertTrue("JSON must contain set alarm for 7am", json.contains("set alarm for 7am"))
    }

    // ── (b) AipMessage envelope protocol / version ────────────────────────────

    @Test
    fun `AipMessage envelope contains protocol AIP_1_0 by default`() {
        val msg = AipMessage(
            type = MsgType.TASK_SUBMIT,
            payload = "test"
        )
        assertEquals("AIP/1.0", msg.protocol)
    }

    @Test
    fun `AipMessage envelope version defaults to 3_0`() {
        val msg = AipMessage(
            type = MsgType.TASK_SUBMIT,
            payload = "test"
        )
        assertEquals("3.0", msg.version)
    }

    @Test
    fun `AipMessage serialises protocol and version into JSON`() {
        val payload = TaskSubmitPayload(
            task_text = "take screenshot",
            device_id = "dev-002",
            session_id = "sess-002",
            task_id = "task-002"
        )
        val envelope = AipMessage(
            type = MsgType.TASK_SUBMIT,
            payload = payload,
            correlation_id = "task-002",
            device_id = "dev-002"
        )
        val json = gson.toJson(envelope)
        assertTrue("Serialised JSON must contain protocol", json.contains("protocol"))
        assertTrue("Serialised JSON must contain AIP/1.0", json.contains("AIP/1.0"))
        assertTrue("Serialised JSON must contain version", json.contains("version"))
        assertTrue("Serialised JSON must contain 3.0", json.contains("3.0"))
    }

    @Test
    fun `AipMessage task_submit envelope contains task_submit type in JSON`() {
        val envelope = AipMessage(
            type = MsgType.TASK_SUBMIT,
            payload = TaskSubmitPayload("do something", "dev-x", "sess-x", "task-x")
        )
        val json = gson.toJson(envelope)
        assertTrue("Serialised JSON must contain task_submit", json.contains("task_submit"))
    }

    // ── (c) MsgType v3 constant alignment ─────────────────────────────────────

    @Test
    fun `MsgType TASK_SUBMIT value matches AIP v3 wire string`() {
        assertEquals("task_submit", MsgType.TASK_SUBMIT.value)
    }

    @Test
    fun `MsgType v3 constants all match expected wire values`() {
        assertEquals("device_register",  MsgType.DEVICE_REGISTER.value)
        assertEquals("heartbeat",         MsgType.HEARTBEAT.value)
        assertEquals("capability_report", MsgType.CAPABILITY_REPORT.value)
        assertEquals("task_assign",       MsgType.TASK_ASSIGN.value)
        assertEquals("command_result",    MsgType.COMMAND_RESULT.value)
        assertEquals("task_submit",       MsgType.TASK_SUBMIT.value)
        assertEquals("task_result",       MsgType.TASK_RESULT.value)
        assertEquals("goal_result",       MsgType.GOAL_RESULT.value)
        assertEquals("task_cancel",       MsgType.TASK_CANCEL.value)
        assertEquals("cancel_result",     MsgType.CANCEL_RESULT.value)
    }

    // ── (d) LEGACY_TYPE_MAP normalization ─────────────────────────────────────

    @Test
    fun `LEGACY_TYPE_MAP maps registration to device_register`() {
        assertEquals("device_register", MsgType.toV3Type("registration"))
    }

    @Test
    fun `LEGACY_TYPE_MAP maps register to device_register`() {
        assertEquals("device_register", MsgType.toV3Type("register"))
    }

    @Test
    fun `LEGACY_TYPE_MAP maps command to task_assign`() {
        assertEquals("task_assign", MsgType.toV3Type("command"))
    }

    @Test
    fun `LEGACY_TYPE_MAP maps command_result to command_result`() {
        assertEquals("command_result", MsgType.toV3Type("command_result"))
    }

    @Test
    fun `LEGACY_TYPE_MAP maps heartbeat to heartbeat`() {
        assertEquals("heartbeat", MsgType.toV3Type("heartbeat"))
    }

    @Test
    fun `LEGACY_TYPE_MAP maps task_execute to task_assign`() {
        assertEquals("task_assign", MsgType.toV3Type("task_execute"),
            "task_execute must be remapped to task_assign for Task Manager unification")
    }

    @Test
    fun `LEGACY_TYPE_MAP maps task_status_query to task_assign`() {
        assertEquals("task_assign", MsgType.toV3Type("task_status_query"),
            "task_status_query must be remapped to task_assign for Task Manager unification")
    }

    @Test
    fun `toV3Type returns unknown types unchanged`() {
        assertEquals("unknown_legacy_type", MsgType.toV3Type("unknown_legacy_type"))
    }

    @Test
    fun `toV3Type returns v3 task_submit unchanged`() {
        assertEquals("task_submit", MsgType.toV3Type("task_submit"))
    }

    @Test
    fun `toV3Type returns v3 task_assign unchanged`() {
        assertEquals("task_assign", MsgType.toV3Type("task_assign"))
    }

    @Test
    fun `LEGACY_TYPE_MAP entries are all v3 names from MsgType`() {
        val v3Values = MsgType.entries.map { it.value }.toSet()
        MsgType.LEGACY_TYPE_MAP.values.forEach { v3Name ->
            assertTrue(
                "LEGACY_TYPE_MAP value '$v3Name' must be a valid MsgType v3 wire value",
                v3Values.contains(v3Name)
            )
        }
    }

    // ── (e) Negative cases ─────────────────────────────────────────────────────

    @Test
    fun `TaskSubmitPayload validate fails when task_text is blank`() {
        val payload = TaskSubmitPayload(
            task_text = "   ",
            device_id = "dev-001",
            session_id = "sess-001",
            task_id = "task-001"
        )
        assertFalse("validate() must return false for blank task_text", payload.validate())
    }

    @Test
    fun `TaskSubmitPayload validate fails when task_text is empty`() {
        val payload = TaskSubmitPayload(
            task_text = "",
            device_id = "dev-001",
            session_id = "sess-001",
            task_id = "task-001"
        )
        assertFalse("validate() must return false for empty task_text", payload.validate())
    }

    @Test
    fun `TaskSubmitPayload validate fails when device_id is blank`() {
        val payload = TaskSubmitPayload(
            task_text = "open maps",
            device_id = "",
            session_id = "sess-001",
            task_id = "task-001"
        )
        assertFalse("validate() must return false for blank device_id", payload.validate())
    }

    @Test
    fun `TaskSubmitPayload validate fails when session_id is blank`() {
        val payload = TaskSubmitPayload(
            task_text = "open maps",
            device_id = "dev-001",
            session_id = "",
            task_id = "task-001"
        )
        assertFalse("validate() must return false for blank session_id", payload.validate())
    }

    @Test
    fun `TaskSubmitPayload validate passes when all required fields are set`() {
        val payload = TaskSubmitPayload(
            task_text = "book a taxi",
            device_id = "android_device001",
            session_id = "sess-xyz",
            task_id = "task-xyz"
        )
        assertTrue("validate() must return true when all required fields are non-blank", payload.validate())
    }

    @Test
    fun `TaskSubmitPayload validate passes even when task_id is empty default`() {
        // task_id is optional in the v3 schema (defaults to ""); the gateway uses
        // AipMessage.correlation_id for matching, so task_id empty is acceptable for validate().
        val payload = TaskSubmitPayload(
            task_text = "take screenshot",
            device_id = "dev-001",
            session_id = "sess-001"
            // task_id intentionally left as default ""
        )
        assertTrue("validate() must pass when only task_id is empty (not required)", payload.validate())
    }

    @Test
    fun `TaskSubmitPayload validationError reports task_text when blank`() {
        val payload = TaskSubmitPayload(
            task_text = "",
            device_id = "dev-001",
            session_id = "sess-001"
        )
        assertEquals("task_text is blank", payload.validationError())
    }

    @Test
    fun `TaskSubmitPayload validationError reports device_id when blank`() {
        val payload = TaskSubmitPayload(
            task_text = "do something",
            device_id = "",
            session_id = "sess-001"
        )
        assertEquals("device_id is blank", payload.validationError())
    }

    @Test
    fun `TaskSubmitPayload validationError reports session_id when blank`() {
        val payload = TaskSubmitPayload(
            task_text = "do something",
            device_id = "dev-001",
            session_id = ""
        )
        assertEquals("session_id is blank", payload.validationError())
    }

    @Test
    fun `TaskSubmitPayload validationError returns null when all fields valid`() {
        val payload = TaskSubmitPayload(
            task_text = "do something",
            device_id = "dev-001",
            session_id = "sess-001",
            task_id = "task-001"
        )
        assertNull("validationError() must return null when all fields are valid", payload.validationError())
    }

    @Test
    fun `legacy type string v2 registration is not a valid MsgType value`() {
        val v3Values = MsgType.entries.map { it.value }.toSet()
        assertFalse(
            "v2 string 'registration' must not be a raw MsgType value (use toV3Type for normalisation)",
            v3Values.contains("registration")
        )
    }

    @Test
    fun `legacy type string command is not a valid MsgType value`() {
        val v3Values = MsgType.entries.map { it.value }.toSet()
        assertFalse(
            "v2 string 'command' must not be a raw MsgType value (use toV3Type for normalisation)",
            v3Values.contains("command")
        )
    }
}
