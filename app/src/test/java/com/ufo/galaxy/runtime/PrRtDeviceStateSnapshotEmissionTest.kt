package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-RT — Device runtime-state snapshot emission validation test matrix.
 *
 * Proves that:
 *  1. [MsgType.DEVICE_STATE_SNAPSHOT] has the correct wire value `"device_state_snapshot"`.
 *  2. [DeviceStateSnapshotPayload] carries all required V2-facing fields.
 *  3. The payload serialises to a JSON object whose top-level keys match the V2
 *     `_parse_state_snapshot` field names (both primary snake_case names and the camelCase
 *     aliases accepted by the V2 parser — V2 chooses the first matching key via `_first_bool`
 *     / `_first_str` / `_first_int` helpers).
 *  4. An [AipMessage] envelope wrapping [DeviceStateSnapshotPayload] serialises without
 *     losing the `device_id` and `runtime_session_id` envelope fields.
 *  5. Snapshot with `local_loop_ready=true` and model/runtime availability produces an
 *     is-AI-ready result consistent with the V2 `DeviceStateSnapshot.is_local_ai_ready()`
 *     contract.
 *  6. Snapshot with `pending_first_download=true` and no model files present correctly
 *     marks the device as not yet ready.
 *  7. The `degraded_reasons` list is serialised as a JSON array (not a string).
 *  8. Null optional fields are serialised as JSON null (not absent), preserving V2 parser
 *     forward-compat contract.
 *  9. `offline_queue_depth` and `current_fallback_tier` are present in the serialised JSON.
 * 10. `runtime_health_snapshot` map is serialised as a JSON object when present.
 *
 * ## Test matrix
 *
 * ### MsgType wire-value stability
 *  - DEVICE_STATE_SNAPSHOT wire value is "device_state_snapshot"
 *
 * ### Payload field population
 *  - device_id field is set correctly
 *  - snapshot_ts field is present and positive
 *  - llama_cpp_available field round-trips through Gson
 *  - ncnn_available field round-trips through Gson
 *  - active_runtime_type field round-trips through Gson
 *  - model_ready field round-trips through Gson
 *  - accessibility_ready field round-trips through Gson
 *  - overlay_ready field round-trips through Gson
 *  - local_loop_ready field round-trips through Gson
 *  - degraded_reasons is serialised as a JSON array
 *  - model_id field round-trips through Gson
 *  - checksum_ok field round-trips through Gson
 *  - mobilevlm_present field round-trips through Gson
 *  - mobilevlm_checksum_ok field round-trips through Gson
 *  - seeclick_present field round-trips through Gson
 *  - pending_first_download field round-trips through Gson
 *  - warmup_result field round-trips through Gson
 *  - offline_queue_depth field round-trips through Gson
 *  - current_fallback_tier field round-trips through Gson
 *  - null fields are preserved as JSON null (not absent)
 *  - runtime_health_snapshot is serialised as a JSON object when non-null
 *  - local_loop_config is serialised as a JSON object when non-null
 *
 * ### AipMessage envelope
 *  - AipMessage envelope type is DEVICE_STATE_SNAPSHOT
 *  - AipMessage envelope device_id matches payload device_id
 *  - AipMessage envelope serialises without loss of runtime_session_id
 *
 * ### V2 is_local_ai_ready contract alignment
 *  - fully-ready snapshot satisfies is-AI-ready conditions
 *  - pending-first-download snapshot does NOT satisfy is-AI-ready conditions
 *
 * ### GalaxyLogger tag stability
 *  - TAG_DEVICE_STATE_SNAPSHOT value is "GALAXY:DEVICE:STATE:SNAPSHOT"
 */
class PrRtDeviceStateSnapshotEmissionTest {

    private val gson = Gson()

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Build a fully-populated snapshot representing a healthy device. */
    private fun fullyReadySnapshot(deviceId: String = "test_device_01"): DeviceStateSnapshotPayload =
        DeviceStateSnapshotPayload(
            device_id = deviceId,
            snapshot_ts = System.currentTimeMillis(),
            llama_cpp_available = true,
            ncnn_available = true,
            active_runtime_type = "HYBRID",
            model_ready = true,
            accessibility_ready = true,
            overlay_ready = true,
            local_loop_ready = true,
            degraded_reasons = emptyList(),
            model_id = "mobilevlm_v2_1.7b",
            runtime_type = "LLAMA_CPP",
            checksum_ok = true,
            mobilevlm_present = true,
            mobilevlm_checksum_ok = true,
            seeclick_present = true,
            pending_first_download = false,
            local_loop_config = mapOf(
                "max_steps" to 10,
                "enable_planner_fallback" to true
            ),
            warmup_result = "ok",
            runtime_health_snapshot = mapOf(
                "planner_health" to "HEALTHY",
                "grounding_health" to "HEALTHY"
            ),
            offline_queue_depth = 0,
            current_fallback_tier = "center_delegated_with_local_fallback"
        )

    /** Build a snapshot representing a device awaiting first model download. */
    private fun pendingDownloadSnapshot(deviceId: String = "test_device_02"): DeviceStateSnapshotPayload =
        DeviceStateSnapshotPayload(
            device_id = deviceId,
            snapshot_ts = System.currentTimeMillis(),
            llama_cpp_available = false,
            ncnn_available = false,
            active_runtime_type = "CENTER",
            model_ready = false,
            accessibility_ready = false,
            overlay_ready = false,
            local_loop_ready = false,
            degraded_reasons = listOf("ACCESSIBILITY_SERVICE_DISABLED", "MODEL_FILES_MISSING"),
            model_id = null,
            runtime_type = null,
            checksum_ok = null,
            mobilevlm_present = false,
            mobilevlm_checksum_ok = false,
            seeclick_present = false,
            pending_first_download = true,
            local_loop_config = null,
            warmup_result = "unavailable",
            runtime_health_snapshot = null,
            offline_queue_depth = 3,
            current_fallback_tier = "local_only"
        )

    // ═════════════════════════════════════════════════════════════════════════
    // MsgType wire-value stability
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `DEVICE_STATE_SNAPSHOT wire value is device_state_snapshot`() {
        assertEquals(
            "Wire value must match V2 DEVICE_STATE_SNAPSHOT_MSG_TYPE constant",
            "device_state_snapshot",
            MsgType.DEVICE_STATE_SNAPSHOT.value
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Payload field population — fully-ready snapshot
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `device_id field is set correctly`() {
        val snap = fullyReadySnapshot("Pixel_8_Test")
        assertEquals("Pixel_8_Test", snap.device_id)
    }

    @Test
    fun `snapshot_ts field is positive`() {
        val snap = fullyReadySnapshot()
        assertTrue("snapshot_ts must be positive", snap.snapshot_ts > 0L)
    }

    @Test
    fun `llama_cpp_available field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("llama_cpp_available").asBoolean)
    }

    @Test
    fun `ncnn_available field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("ncnn_available").asBoolean)
    }

    @Test
    fun `active_runtime_type field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertEquals("HYBRID", json.get("active_runtime_type").asString)
    }

    @Test
    fun `model_ready field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("model_ready").asBoolean)
    }

    @Test
    fun `accessibility_ready field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("accessibility_ready").asBoolean)
    }

    @Test
    fun `overlay_ready field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("overlay_ready").asBoolean)
    }

    @Test
    fun `local_loop_ready field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("local_loop_ready").asBoolean)
    }

    @Test
    fun `degraded_reasons is serialised as a JSON array`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(
            "degraded_reasons must be a JSON array",
            json.get("degraded_reasons").isJsonArray
        )
        assertEquals(0, json.getAsJsonArray("degraded_reasons").size())
    }

    @Test
    fun `degraded_reasons list is serialised with correct entries`() {
        val snap = pendingDownloadSnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        val arr = json.getAsJsonArray("degraded_reasons")
        assertEquals(2, arr.size())
        val reasons = arr.map { it.asString }
        assertTrue(reasons.contains("ACCESSIBILITY_SERVICE_DISABLED"))
        assertTrue(reasons.contains("MODEL_FILES_MISSING"))
    }

    @Test
    fun `model_id field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertEquals("mobilevlm_v2_1.7b", json.get("model_id").asString)
    }

    @Test
    fun `checksum_ok field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("checksum_ok").asBoolean)
    }

    @Test
    fun `mobilevlm_present field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("mobilevlm_present").asBoolean)
    }

    @Test
    fun `mobilevlm_checksum_ok field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("mobilevlm_checksum_ok").asBoolean)
    }

    @Test
    fun `seeclick_present field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("seeclick_present").asBoolean)
    }

    @Test
    fun `pending_first_download field is false for fully-ready snapshot`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertFalse(json.get("pending_first_download").asBoolean)
    }

    @Test
    fun `pending_first_download field is true for pending-download snapshot`() {
        val snap = pendingDownloadSnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("pending_first_download").asBoolean)
    }

    @Test
    fun `warmup_result field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertEquals("ok", json.get("warmup_result").asString)
    }

    @Test
    fun `offline_queue_depth field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertEquals(0, json.get("offline_queue_depth").asInt)
    }

    @Test
    fun `offline_queue_depth reflects non-zero queue depth`() {
        val snap = pendingDownloadSnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertEquals(3, json.get("offline_queue_depth").asInt)
    }

    @Test
    fun `current_fallback_tier field round-trips through Gson`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertEquals("center_delegated_with_local_fallback", json.get("current_fallback_tier").asString)
    }

    @Test
    fun `current_fallback_tier is local_only for pending-download snapshot`() {
        val snap = pendingDownloadSnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertEquals("local_only", json.get("current_fallback_tier").asString)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Null field handling
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `null model_id is preserved as JSON null`() {
        val snap = pendingDownloadSnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(
            "model_id must be JSON null when absent, not omitted",
            json.get("model_id").isJsonNull
        )
    }

    @Test
    fun `null runtime_type is preserved as JSON null`() {
        val snap = pendingDownloadSnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("runtime_type").isJsonNull)
    }

    @Test
    fun `null checksum_ok is preserved as JSON null`() {
        val snap = pendingDownloadSnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("checksum_ok").isJsonNull)
    }

    @Test
    fun `null runtime_health_snapshot is preserved as JSON null`() {
        val snap = pendingDownloadSnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("runtime_health_snapshot").isJsonNull)
    }

    @Test
    fun `null local_loop_config is preserved as JSON null`() {
        val snap = pendingDownloadSnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(json.get("local_loop_config").isJsonNull)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Nested objects
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `runtime_health_snapshot is serialised as a JSON object when non-null`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(
            "runtime_health_snapshot must be a JSON object",
            json.get("runtime_health_snapshot").isJsonObject
        )
        val health = json.getAsJsonObject("runtime_health_snapshot")
        assertEquals("HEALTHY", health.get("planner_health").asString)
        assertEquals("HEALTHY", health.get("grounding_health").asString)
    }

    @Test
    fun `local_loop_config is serialised as a JSON object when non-null`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(
            "local_loop_config must be a JSON object",
            json.get("local_loop_config").isJsonObject
        )
        val cfg = json.getAsJsonObject("local_loop_config")
        assertEquals(10, cfg.get("max_steps").asInt)
        assertTrue(cfg.get("enable_planner_fallback").asBoolean)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AipMessage envelope
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `AipMessage envelope type is DEVICE_STATE_SNAPSHOT`() {
        val snap = fullyReadySnapshot()
        val envelope = AipMessage(
            type = MsgType.DEVICE_STATE_SNAPSHOT,
            payload = snap,
            device_id = snap.device_id,
            runtime_session_id = "test-runtime-session-1"
        )
        assertEquals(MsgType.DEVICE_STATE_SNAPSHOT, envelope.type)
    }

    @Test
    fun `AipMessage envelope device_id matches snapshot device_id`() {
        val deviceId = "Pixel_9_Envelope_Test"
        val snap = fullyReadySnapshot(deviceId)
        val envelope = AipMessage(
            type = MsgType.DEVICE_STATE_SNAPSHOT,
            payload = snap,
            device_id = deviceId,
            runtime_session_id = "test-runtime-session-2"
        )
        assertEquals(deviceId, envelope.device_id)
    }

    @Test
    fun `AipMessage envelope serialises without loss of runtime_session_id`() {
        val snap = fullyReadySnapshot()
        val sessionId = "test-runtime-session-3"
        val envelope = AipMessage(
            type = MsgType.DEVICE_STATE_SNAPSHOT,
            payload = snap,
            device_id = snap.device_id,
            runtime_session_id = sessionId
        )
        val json = gson.toJsonTree(envelope).asJsonObject
        // The AipMessage Gson serialiser may use "type" as a JsonElement — verify it round-trips
        val payloadObj = json.get("payload")
        assertNotNull("payload field must be present", payloadObj)
        assertFalse("payload must not be null", payloadObj.isJsonNull)
        // runtime_session_id must survive serialisation
        assertEquals(sessionId, json.get("runtime_session_id").asString)
    }

    @Test
    fun `AipMessage full serialise-deserialise round-trip preserves all key fields`() {
        val deviceId = "roundtrip_device"
        val snap = fullyReadySnapshot(deviceId)
        val envelope = AipMessage(
            type = MsgType.DEVICE_STATE_SNAPSHOT,
            payload = snap,
            device_id = deviceId,
            runtime_session_id = "rt-session-roundtrip",
            idempotency_key = "idem-key-001"
        )

        val jsonStr = gson.toJson(envelope)
        val parsed = gson.fromJson(jsonStr, com.google.gson.JsonObject::class.java)

        assertEquals("device_state_snapshot", parsed.get("type").asString)
        assertEquals(deviceId, parsed.get("device_id").asString)
        assertEquals("rt-session-roundtrip", parsed.get("runtime_session_id").asString)
        assertEquals("idem-key-001", parsed.get("idempotency_key").asString)
        // The payload must be a JSON object (not a string)
        assertTrue(parsed.get("payload").isJsonObject)
        val payloadJson = parsed.getAsJsonObject("payload")
        assertEquals(deviceId, payloadJson.get("device_id").asString)
        assertTrue(payloadJson.get("model_ready").asBoolean)
        assertTrue(payloadJson.get("local_loop_ready").asBoolean)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // V2 is_local_ai_ready contract alignment
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * V2 DeviceStateSnapshot.is_local_ai_ready() returns True when:
     *   local_loop_ready AND model_ready AND (llama_cpp_available OR ncnn_available)
     *
     * Verify our payload populates all three conditions correctly for a fully-ready device.
     */
    @Test
    fun `fully-ready snapshot satisfies V2 is_local_ai_ready conditions`() {
        val snap = fullyReadySnapshot()
        assertTrue("local_loop_ready must be true", snap.local_loop_ready == true)
        assertTrue("model_ready must be true", snap.model_ready == true)
        assertTrue(
            "at least one of llama_cpp_available or ncnn_available must be true",
            snap.llama_cpp_available == true || snap.ncnn_available == true
        )
        // All three conditions met → is_local_ai_ready = True in V2
    }

    @Test
    fun `pending-download snapshot does NOT satisfy V2 is_local_ai_ready conditions`() {
        val snap = pendingDownloadSnapshot()
        val isLocalAiReady = (snap.local_loop_ready == true) &&
            (snap.model_ready == true) &&
            (snap.llama_cpp_available == true || snap.ncnn_available == true)
        assertFalse(
            "pending-first-download snapshot must not satisfy is_local_ai_ready",
            isLocalAiReady
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // GalaxyLogger tag stability
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `TAG_DEVICE_STATE_SNAPSHOT value is GALAXY DEVICE STATE SNAPSHOT`() {
        assertEquals(
            "GALAXY:DEVICE:STATE:SNAPSHOT",
            com.ufo.galaxy.observability.GalaxyLogger.TAG_DEVICE_STATE_SNAPSHOT
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Payload construction completeness
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `payload built with all required V2 keys present in JSON`() {
        val snap = fullyReadySnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        // Required V2 fields (from _parse_state_snapshot primary keys)
        val requiredKeys = listOf(
            "device_id",
            "snapshot_ts",
            "llama_cpp_available",
            "ncnn_available",
            "active_runtime_type",
            "model_ready",
            "accessibility_ready",
            "overlay_ready",
            "local_loop_ready",
            "degraded_reasons",
            "model_id",
            "runtime_type",
            "checksum_ok",
            "mobilevlm_present",
            "mobilevlm_checksum_ok",
            "seeclick_present",
            "pending_first_download",
            "warmup_result",
            "offline_queue_depth",
            "current_fallback_tier"
        )
        val missingKeys = requiredKeys.filter { !json.has(it) }
        assertTrue(
            "The following required V2 keys are missing from the payload JSON: $missingKeys",
            missingKeys.isEmpty()
        )
    }

    @Test
    fun `pending-download payload built with all required V2 keys present in JSON`() {
        val snap = pendingDownloadSnapshot()
        val json = gson.toJsonTree(snap).asJsonObject
        val requiredKeys = listOf(
            "device_id",
            "snapshot_ts",
            "llama_cpp_available",
            "ncnn_available",
            "active_runtime_type",
            "model_ready",
            "accessibility_ready",
            "overlay_ready",
            "local_loop_ready",
            "degraded_reasons",
            "pending_first_download",
            "warmup_result",
            "offline_queue_depth",
            "current_fallback_tier"
        )
        val missingKeys = requiredKeys.filter { !json.has(it) }
        assertTrue(
            "The following required V2 keys are missing from the pending-download payload JSON: $missingKeys",
            missingKeys.isEmpty()
        )
    }
}
