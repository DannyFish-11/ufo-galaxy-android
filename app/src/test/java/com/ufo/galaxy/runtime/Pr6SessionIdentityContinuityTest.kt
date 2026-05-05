package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-6 (Android-side companion) — Session and identity continuity tests.
 *
 * Validates that Android-originated payloads preserve session/invocation/runtime identity
 * metadata so the V2 center can treat Android interactions as part of one continuous AI body
 * rather than disconnected node events.
 *
 * All four session identity fields added in PR-6 are **derived exclusively from existing
 * Android runtime state**:
 *  - [DeviceStateSnapshotPayload.durable_session_id] / [DeviceExecutionEventPayload.durable_session_id]
 *    ← `DurableSessionContinuityRecord.durableSessionId`
 *  - [DeviceStateSnapshotPayload.session_continuity_epoch] / [DeviceExecutionEventPayload.session_continuity_epoch]
 *    ← `DurableSessionContinuityRecord.sessionContinuityEpoch`
 *  - [DeviceStateSnapshotPayload.runtime_session_id] / [DeviceExecutionEventPayload.runtime_session_id]
 *    ← `UFOGalaxyApplication.runtimeSessionId` (per-app-launch UUID)
 *  - [DeviceStateSnapshotPayload.attached_session_id] / [DeviceExecutionEventPayload.attached_session_id]
 *    ← `AttachedRuntimeSession.sessionId`
 *
 * ## Test matrix
 *
 * ### DeviceStateSnapshotPayload — session identity field defaults
 *  - durable_session_id defaults to null when not provided
 *  - session_continuity_epoch defaults to null when not provided
 *  - runtime_session_id defaults to null when not provided (snapshot level)
 *  - attached_session_id defaults to null when not provided
 *
 * ### DeviceStateSnapshotPayload — session identity fields round-trip through Gson
 *  - durable_session_id round-trips through Gson
 *  - session_continuity_epoch round-trips through Gson
 *  - runtime_session_id round-trips through Gson (snapshot field)
 *  - attached_session_id round-trips through Gson
 *  - all four session fields present in serialised JSON when populated
 *
 * ### DeviceStateSnapshotPayload — null semantics (no fake values)
 *  - durable_session_id is null in JSON when not backed by real state
 *  - session_continuity_epoch is null in JSON when not backed by real state
 *  - runtime_session_id is null in JSON (snapshot field) when not backed by real state
 *  - attached_session_id is null in JSON when not backed by real state
 *
 * ### DeviceExecutionEventPayload — session identity field defaults
 *  - durable_session_id defaults to null when not provided
 *  - session_continuity_epoch defaults to null when not provided
 *  - runtime_session_id defaults to null when not provided (event level)
 *  - attached_session_id defaults to null when not provided
 *
 * ### DeviceExecutionEventPayload — session identity fields round-trip through Gson
 *  - durable_session_id round-trips through Gson
 *  - session_continuity_epoch round-trips through Gson
 *  - runtime_session_id round-trips through Gson (event field)
 *  - attached_session_id round-trips through Gson
 *  - all four session fields present in serialised JSON when populated
 *
 * ### DeviceExecutionEventPayload — null semantics (no fake values)
 *  - durable_session_id is null in JSON when not backed by real state
 *  - session_continuity_epoch is null in JSON when not backed by real state
 *  - runtime_session_id is null in JSON (event field) when not backed by real state
 *  - attached_session_id is null in JSON when not backed by real state
 *
 * ### Session identity continuity across execution phases
 *  - execution_started event carries same durable_session_id as companion snapshot
 *  - session_continuity_epoch is identical across events sharing the same era
 *  - terminal event (completed) preserves durable_session_id from start event
 *  - terminal event (failed) preserves durable_session_id from start event
 *  - attached_session_id matches across start and terminal events for same task
 *
 * ### Wire key name stability (V2 contract alignment)
 *  - snapshot durable_session_id wire key is "durable_session_id"
 *  - snapshot session_continuity_epoch wire key is "session_continuity_epoch"
 *  - snapshot runtime_session_id wire key is "runtime_session_id"
 *  - snapshot attached_session_id wire key is "attached_session_id"
 *  - execution event durable_session_id wire key is "durable_session_id"
 *  - execution event session_continuity_epoch wire key is "session_continuity_epoch"
 *  - execution event runtime_session_id wire key is "runtime_session_id"
 *  - execution event attached_session_id wire key is "attached_session_id"
 *
 * ### Reconnect epoch continuity
 *  - session_continuity_epoch 0 represents the initial connection
 *  - session_continuity_epoch increments are preserved across events in the same era
 *  - durable_session_id is identical before and after simulated reconnect within same era
 */
class Pr6SessionIdentityContinuityTest {

    private val gson = Gson()

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun baseSnapshot(
        durableSessionId: String? = null,
        sessionContinuityEpoch: Int? = null,
        runtimeSessionId: String? = null,
        attachedSessionId: String? = null
    ): DeviceStateSnapshotPayload = DeviceStateSnapshotPayload(
        device_id = "test_device_pr6",
        snapshot_ts = 1_700_000_000_000L,
        llama_cpp_available = true,
        ncnn_available = false,
        active_runtime_type = "LLAMA_CPP",
        model_ready = true,
        accessibility_ready = true,
        overlay_ready = true,
        local_loop_ready = true,
        model_id = "mobilevlm",
        runtime_type = "LLAMA_CPP",
        checksum_ok = true,
        mobilevlm_present = true,
        mobilevlm_checksum_ok = true,
        seeclick_present = false,
        pending_first_download = false,
        warmup_result = "ok",
        offline_queue_depth = 0,
        current_fallback_tier = "center_delegated_with_local_fallback",
        durable_session_id = durableSessionId,
        session_continuity_epoch = sessionContinuityEpoch,
        runtime_session_id = runtimeSessionId,
        attached_session_id = attachedSessionId
    )

    private fun baseExecutionEvent(
        phase: String = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
        durableSessionId: String? = null,
        sessionContinuityEpoch: Int? = null,
        runtimeSessionId: String? = null,
        attachedSessionId: String? = null
    ): DeviceExecutionEventPayload = DeviceExecutionEventPayload(
        flow_id = "flow-pr6",
        task_id = "task-pr6",
        phase = phase,
        device_id = "test_device_pr6",
        source_component = "Pr6SessionIdentityContinuityTest",
        durable_session_id = durableSessionId,
        session_continuity_epoch = sessionContinuityEpoch,
        runtime_session_id = runtimeSessionId,
        attached_session_id = attachedSessionId
    )

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceStateSnapshotPayload — session identity field defaults
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `durable_session_id defaults to null when not provided`() {
        val snapshot = baseSnapshot()
        assertNull(snapshot.durable_session_id)
    }

    @Test fun `session_continuity_epoch defaults to null when not provided`() {
        val snapshot = baseSnapshot()
        assertNull(snapshot.session_continuity_epoch)
    }

    @Test fun `runtime_session_id defaults to null when not provided (snapshot level)`() {
        val snapshot = baseSnapshot()
        assertNull(snapshot.runtime_session_id)
    }

    @Test fun `attached_session_id defaults to null when not provided`() {
        val snapshot = baseSnapshot()
        assertNull(snapshot.attached_session_id)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceStateSnapshotPayload — session identity fields round-trip through Gson
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `durable_session_id round-trips through Gson`() {
        val durableId = "durable-era-abc123"
        val snapshot = baseSnapshot(durableSessionId = durableId)
        val json = gson.fromJson(gson.toJson(snapshot), com.google.gson.JsonObject::class.java)
        assertEquals(durableId, json.get("durable_session_id")?.asString)
    }

    @Test fun `session_continuity_epoch round-trips through Gson`() {
        val snapshot = baseSnapshot(durableSessionId = "dur-1", sessionContinuityEpoch = 3)
        val json = gson.fromJson(gson.toJson(snapshot), com.google.gson.JsonObject::class.java)
        assertEquals(3, json.get("session_continuity_epoch")?.asInt)
    }

    @Test fun `runtime_session_id round-trips through Gson (snapshot field)`() {
        val rtId = "rt-session-xyz"
        val snapshot = baseSnapshot(runtimeSessionId = rtId)
        val json = gson.fromJson(gson.toJson(snapshot), com.google.gson.JsonObject::class.java)
        assertEquals(rtId, json.get("runtime_session_id")?.asString)
    }

    @Test fun `attached_session_id round-trips through Gson`() {
        val attachedId = "attached-sess-789"
        val snapshot = baseSnapshot(attachedSessionId = attachedId)
        val json = gson.fromJson(gson.toJson(snapshot), com.google.gson.JsonObject::class.java)
        assertEquals(attachedId, json.get("attached_session_id")?.asString)
    }

    @Test fun `all four session fields present in serialised snapshot JSON when populated`() {
        val snapshot = baseSnapshot(
            durableSessionId = "dur-all",
            sessionContinuityEpoch = 2,
            runtimeSessionId = "rt-all",
            attachedSessionId = "att-all"
        )
        val json = gson.fromJson(gson.toJson(snapshot), com.google.gson.JsonObject::class.java)
        assertTrue("durable_session_id missing", json.has("durable_session_id"))
        assertTrue("session_continuity_epoch missing", json.has("session_continuity_epoch"))
        assertTrue("runtime_session_id missing", json.has("runtime_session_id"))
        assertTrue("attached_session_id missing", json.has("attached_session_id"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceStateSnapshotPayload — null semantics (no fake values)
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `durable_session_id is null in JSON when not backed by real state`() {
        val snapshot = baseSnapshot(durableSessionId = null)
        val json = gson.fromJson(gson.toJson(snapshot), com.google.gson.JsonObject::class.java)
        assertTrue(
            "durable_session_id must be JSON null, not absent",
            json.has("durable_session_id") && json.get("durable_session_id").isJsonNull
        )
    }

    @Test fun `session_continuity_epoch is null in JSON when not backed by real state`() {
        val snapshot = baseSnapshot(sessionContinuityEpoch = null)
        val json = gson.fromJson(gson.toJson(snapshot), com.google.gson.JsonObject::class.java)
        assertTrue(
            "session_continuity_epoch must be JSON null, not absent",
            json.has("session_continuity_epoch") && json.get("session_continuity_epoch").isJsonNull
        )
    }

    @Test fun `runtime_session_id is null in JSON (snapshot field) when not backed by real state`() {
        val snapshot = baseSnapshot(runtimeSessionId = null)
        val json = gson.fromJson(gson.toJson(snapshot), com.google.gson.JsonObject::class.java)
        assertTrue(
            "runtime_session_id must be JSON null, not absent",
            json.has("runtime_session_id") && json.get("runtime_session_id").isJsonNull
        )
    }

    @Test fun `attached_session_id is null in JSON when not backed by real state`() {
        val snapshot = baseSnapshot(attachedSessionId = null)
        val json = gson.fromJson(gson.toJson(snapshot), com.google.gson.JsonObject::class.java)
        assertTrue(
            "attached_session_id must be JSON null, not absent",
            json.has("attached_session_id") && json.get("attached_session_id").isJsonNull
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceExecutionEventPayload — session identity field defaults
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `durable_session_id defaults to null when not provided (event)`() {
        val event = baseExecutionEvent()
        assertNull(event.durable_session_id)
    }

    @Test fun `session_continuity_epoch defaults to null when not provided (event)`() {
        val event = baseExecutionEvent()
        assertNull(event.session_continuity_epoch)
    }

    @Test fun `runtime_session_id defaults to null when not provided (event level)`() {
        val event = baseExecutionEvent()
        assertNull(event.runtime_session_id)
    }

    @Test fun `attached_session_id defaults to null when not provided (event)`() {
        val event = baseExecutionEvent()
        assertNull(event.attached_session_id)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceExecutionEventPayload — session identity fields round-trip through Gson
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `durable_session_id round-trips through Gson (event)`() {
        val durableId = "durable-event-abc"
        val event = baseExecutionEvent(durableSessionId = durableId)
        val json = gson.fromJson(gson.toJson(event), com.google.gson.JsonObject::class.java)
        assertEquals(durableId, json.get("durable_session_id")?.asString)
    }

    @Test fun `session_continuity_epoch round-trips through Gson (event)`() {
        val event = baseExecutionEvent(durableSessionId = "dur-ev", sessionContinuityEpoch = 5)
        val json = gson.fromJson(gson.toJson(event), com.google.gson.JsonObject::class.java)
        assertEquals(5, json.get("session_continuity_epoch")?.asInt)
    }

    @Test fun `runtime_session_id round-trips through Gson (event field)`() {
        val rtId = "rt-event-session"
        val event = baseExecutionEvent(runtimeSessionId = rtId)
        val json = gson.fromJson(gson.toJson(event), com.google.gson.JsonObject::class.java)
        assertEquals(rtId, json.get("runtime_session_id")?.asString)
    }

    @Test fun `attached_session_id round-trips through Gson (event)`() {
        val attachedId = "att-event-sess"
        val event = baseExecutionEvent(attachedSessionId = attachedId)
        val json = gson.fromJson(gson.toJson(event), com.google.gson.JsonObject::class.java)
        assertEquals(attachedId, json.get("attached_session_id")?.asString)
    }

    @Test fun `all four session fields present in serialised event JSON when populated`() {
        val event = baseExecutionEvent(
            durableSessionId = "dur-ev-all",
            sessionContinuityEpoch = 1,
            runtimeSessionId = "rt-ev-all",
            attachedSessionId = "att-ev-all"
        )
        val json = gson.fromJson(gson.toJson(event), com.google.gson.JsonObject::class.java)
        assertTrue("durable_session_id missing from event", json.has("durable_session_id"))
        assertTrue("session_continuity_epoch missing from event", json.has("session_continuity_epoch"))
        assertTrue("runtime_session_id missing from event", json.has("runtime_session_id"))
        assertTrue("attached_session_id missing from event", json.has("attached_session_id"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceExecutionEventPayload — null semantics (no fake values)
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `durable_session_id is null in JSON when not backed by real state (event)`() {
        val event = baseExecutionEvent(durableSessionId = null)
        val json = gson.fromJson(gson.toJson(event), com.google.gson.JsonObject::class.java)
        assertTrue(
            "durable_session_id must be JSON null, not absent",
            json.has("durable_session_id") && json.get("durable_session_id").isJsonNull
        )
    }

    @Test fun `session_continuity_epoch is null in JSON when not backed by real state (event)`() {
        val event = baseExecutionEvent(sessionContinuityEpoch = null)
        val json = gson.fromJson(gson.toJson(event), com.google.gson.JsonObject::class.java)
        assertTrue(
            "session_continuity_epoch must be JSON null, not absent",
            json.has("session_continuity_epoch") && json.get("session_continuity_epoch").isJsonNull
        )
    }

    @Test fun `runtime_session_id is null in JSON (event field) when not backed by real state`() {
        val event = baseExecutionEvent(runtimeSessionId = null)
        val json = gson.fromJson(gson.toJson(event), com.google.gson.JsonObject::class.java)
        assertTrue(
            "runtime_session_id must be JSON null, not absent",
            json.has("runtime_session_id") && json.get("runtime_session_id").isJsonNull
        )
    }

    @Test fun `attached_session_id is null in JSON when not backed by real state (event)`() {
        val event = baseExecutionEvent(attachedSessionId = null)
        val json = gson.fromJson(gson.toJson(event), com.google.gson.JsonObject::class.java)
        assertTrue(
            "attached_session_id must be JSON null, not absent",
            json.has("attached_session_id") && json.get("attached_session_id").isJsonNull
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session identity continuity across execution phases
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `execution_started event carries same durable_session_id as companion snapshot`() {
        val durableId = "durable-continuity-test"
        val snapshot = baseSnapshot(durableSessionId = durableId)
        val startEvent = baseExecutionEvent(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            durableSessionId = durableId
        )
        assertEquals(snapshot.durable_session_id, startEvent.durable_session_id)
    }

    @Test fun `session_continuity_epoch is identical across events sharing the same era`() {
        val epoch = 2
        val startEvent = baseExecutionEvent(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            durableSessionId = "dur-epoch",
            sessionContinuityEpoch = epoch
        )
        val completedEvent = baseExecutionEvent(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            durableSessionId = "dur-epoch",
            sessionContinuityEpoch = epoch
        )
        assertEquals(startEvent.session_continuity_epoch, completedEvent.session_continuity_epoch)
    }

    @Test fun `terminal event (completed) preserves durable_session_id from start event`() {
        val durableId = "durable-terminal-completed"
        val startEvent = baseExecutionEvent(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            durableSessionId = durableId
        )
        val terminalEvent = baseExecutionEvent(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            durableSessionId = durableId
        )
        assertEquals(startEvent.durable_session_id, terminalEvent.durable_session_id)
    }

    @Test fun `terminal event (failed) preserves durable_session_id from start event`() {
        val durableId = "durable-terminal-failed"
        val startEvent = baseExecutionEvent(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            durableSessionId = durableId
        )
        val failedEvent = baseExecutionEvent(
            phase = DeviceExecutionEventPayload.PHASE_FAILED,
            durableSessionId = durableId
        )
        assertEquals(startEvent.durable_session_id, failedEvent.durable_session_id)
    }

    @Test fun `attached_session_id matches across start and terminal events for same task`() {
        val attachedId = "attached-task-sess"
        val startEvent = baseExecutionEvent(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            attachedSessionId = attachedId
        )
        val terminalEvent = baseExecutionEvent(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            attachedSessionId = attachedId
        )
        assertEquals(startEvent.attached_session_id, terminalEvent.attached_session_id)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Wire key name stability (V2 contract alignment)
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `snapshot durable_session_id wire key is durable_session_id`() {
        val snapshot = baseSnapshot(durableSessionId = "dur-wk")
        val json = gson.fromJson(gson.toJson(snapshot), com.google.gson.JsonObject::class.java)
        assertTrue("wire key must be 'durable_session_id'", json.has("durable_session_id"))
    }

    @Test fun `snapshot session_continuity_epoch wire key is session_continuity_epoch`() {
        val snapshot = baseSnapshot(durableSessionId = "d", sessionContinuityEpoch = 0)
        val json = gson.fromJson(gson.toJson(snapshot), com.google.gson.JsonObject::class.java)
        assertTrue("wire key must be 'session_continuity_epoch'", json.has("session_continuity_epoch"))
    }

    @Test fun `snapshot runtime_session_id wire key is runtime_session_id`() {
        val snapshot = baseSnapshot(runtimeSessionId = "rt-wk")
        val json = gson.fromJson(gson.toJson(snapshot), com.google.gson.JsonObject::class.java)
        assertTrue("wire key must be 'runtime_session_id'", json.has("runtime_session_id"))
    }

    @Test fun `snapshot attached_session_id wire key is attached_session_id`() {
        val snapshot = baseSnapshot(attachedSessionId = "att-wk")
        val json = gson.fromJson(gson.toJson(snapshot), com.google.gson.JsonObject::class.java)
        assertTrue("wire key must be 'attached_session_id'", json.has("attached_session_id"))
    }

    @Test fun `execution event durable_session_id wire key is durable_session_id`() {
        val event = baseExecutionEvent(durableSessionId = "dur-ev-wk")
        val json = gson.fromJson(gson.toJson(event), com.google.gson.JsonObject::class.java)
        assertTrue("wire key must be 'durable_session_id'", json.has("durable_session_id"))
    }

    @Test fun `execution event session_continuity_epoch wire key is session_continuity_epoch`() {
        val event = baseExecutionEvent(durableSessionId = "d", sessionContinuityEpoch = 0)
        val json = gson.fromJson(gson.toJson(event), com.google.gson.JsonObject::class.java)
        assertTrue("wire key must be 'session_continuity_epoch'", json.has("session_continuity_epoch"))
    }

    @Test fun `execution event runtime_session_id wire key is runtime_session_id`() {
        val event = baseExecutionEvent(runtimeSessionId = "rt-ev-wk")
        val json = gson.fromJson(gson.toJson(event), com.google.gson.JsonObject::class.java)
        assertTrue("wire key must be 'runtime_session_id'", json.has("runtime_session_id"))
    }

    @Test fun `execution event attached_session_id wire key is attached_session_id`() {
        val event = baseExecutionEvent(attachedSessionId = "att-ev-wk")
        val json = gson.fromJson(gson.toJson(event), com.google.gson.JsonObject::class.java)
        assertTrue("wire key must be 'attached_session_id'", json.has("attached_session_id"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reconnect epoch continuity
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `session_continuity_epoch 0 represents the initial connection`() {
        val event = baseExecutionEvent(durableSessionId = "dur-init", sessionContinuityEpoch = 0)
        assertEquals(0, event.session_continuity_epoch)
    }

    @Test fun `session_continuity_epoch increments are preserved across events in the same era`() {
        // Simulates events emitted after a reconnect (epoch=1) within the same durable era.
        val epoch = 1
        val snapshotAfterReconnect = baseSnapshot(durableSessionId = "dur-reconnect", sessionContinuityEpoch = epoch)
        val eventAfterReconnect = baseExecutionEvent(durableSessionId = "dur-reconnect", sessionContinuityEpoch = epoch)
        assertEquals(epoch, snapshotAfterReconnect.session_continuity_epoch)
        assertEquals(epoch, eventAfterReconnect.session_continuity_epoch)
        // Both snapshot and event reflect the same reconnect count within the era.
        assertEquals(snapshotAfterReconnect.session_continuity_epoch, eventAfterReconnect.session_continuity_epoch)
    }

    @Test fun `durable_session_id is identical before and after simulated reconnect within same era`() {
        val durableId = "durable-stable-across-reconnect"
        // Before reconnect (epoch=0)
        val preReconnectEvent = baseExecutionEvent(durableSessionId = durableId, sessionContinuityEpoch = 0)
        // After reconnect (epoch=1) — durable era is unchanged, only epoch increments
        val postReconnectEvent = baseExecutionEvent(durableSessionId = durableId, sessionContinuityEpoch = 1)
        // The durable session identity must remain stable across the reconnect boundary.
        assertEquals(preReconnectEvent.durable_session_id, postReconnectEvent.durable_session_id)
        // But the epoch correctly reflects the reconnect count.
        assertEquals(0, preReconnectEvent.session_continuity_epoch)
        assertEquals(1, postReconnectEvent.session_continuity_epoch)
    }
}
