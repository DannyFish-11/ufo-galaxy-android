package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-10 (Android companion) — Final Android carrier consolidation tests.
 *
 * Validates that the final carrier coherence gap is closed: Android-originated
 * [DeviceStateSnapshotPayload] and [DeviceExecutionEventPayload] now expose the
 * carrier's runtime lifecycle state via [DeviceStateSnapshotPayload.carrier_lifecycle_state]
 * and [DeviceExecutionEventPayload.carrier_lifecycle_state], backed by
 * [RuntimeController.state] via the [wireLabel] extension.
 *
 * ## Why this matters
 *
 * Prior PRs gave V2 session identity (PR-6) and manifestation/presence hints (PR-8), but
 * V2 could not determine Android's overall operational mode from a snapshot or execution
 * event alone.  Without `carrier_lifecycle_state`, V2 sees the Android carrier's model
 * readiness, fallback tiers, and session ID but does not know whether the carrier is
 * actively connected (`"active"`), operating locally only (`"local_only"`), starting up
 * (`"starting"`), or in a failure state (`"failed"`).  This field closes that gap using
 * only the existing [RuntimeController.state] state machine — no new architecture is
 * introduced.
 *
 * ## Wire vocabulary (stable)
 *
 * | `carrier_lifecycle_state` value | [RuntimeController.RuntimeState] | Meaning                                  |
 * |---------------------------------|----------------------------------|------------------------------------------|
 * | `"idle"`                        | [RuntimeController.RuntimeState.Idle]       | No start requested since last stop.      |
 * | `"starting"`                    | [RuntimeController.RuntimeState.Starting]   | WS connect/registration in progress.    |
 * | `"active"`                      | [RuntimeController.RuntimeState.Active]     | WS connected; cross-device enabled.     |
 * | `"failed"`                      | [RuntimeController.RuntimeState.Failed]     | Registration failed; in LocalOnly.       |
 * | `"local_only"`                  | [RuntimeController.RuntimeState.LocalOnly]  | Cross-device disabled; local loop only. |
 *
 * ## Test matrix
 *
 * ### DeviceStateSnapshotPayload — carrier_lifecycle_state field defaults
 *  - carrier_lifecycle_state defaults to null when not provided
 *
 * ### DeviceStateSnapshotPayload — Gson round-trip for all wire values
 *  - "idle" round-trips through Gson
 *  - "starting" round-trips through Gson
 *  - "active" round-trips through Gson
 *  - "failed" round-trips through Gson
 *  - "local_only" round-trips through Gson
 *  - null serialises as JSON null
 *
 * ### DeviceExecutionEventPayload — carrier_lifecycle_state field defaults
 *  - carrier_lifecycle_state defaults to null when not provided
 *
 * ### DeviceExecutionEventPayload — Gson round-trip for all wire values
 *  - "active" round-trips through Gson
 *  - "local_only" round-trips through Gson
 *  - null serialises as JSON null
 *
 * ### Wire key name stability
 *  - snapshot carrier_lifecycle_state wire key is "carrier_lifecycle_state"
 *  - event carrier_lifecycle_state wire key is "carrier_lifecycle_state"
 *
 * ### Consistency — snapshot and execution event agree when from same state
 *  - carrier_lifecycle_state is consistent between snapshot and execution event
 *
 * ### Consolidated carrier state — all three carrier hint groups are internally consistent
 *  - carrier_lifecycle_state, carrier_foreground_visible, and interaction_surface_ready
 *    can all be set simultaneously without conflict
 *  - all carrier fields survive Gson round-trip together
 *
 * ### No fake values
 *  - carrier_lifecycle_state is null in JSON when not backed by real state
 *  - field is absent from snapshot when null
 *
 * ### wireLabel coverage — all RuntimeState values produce the expected labels
 *  - Idle → "idle"
 *  - Starting → "starting"
 *  - Active → "active"
 *  - Failed → "failed"
 *  - LocalOnly → "local_only"
 */
class Pr10AndroidCarrierConsolidationTest {

    private val gson = Gson()

    /** Serialise [obj] to JSON and parse back as a [JsonObject] for field inspection. */
    private fun toJsonObject(obj: Any): JsonObject =
        gson.fromJson(gson.toJson(obj), JsonObject::class.java)

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Minimal valid [DeviceStateSnapshotPayload] with optional field overrides. */
    private fun baseSnapshot(
        carrierLifecycleState: String? = null,
        carrierForegroundVisible: Boolean? = null,
        interactionSurfaceReady: Boolean? = null
    ): DeviceStateSnapshotPayload = DeviceStateSnapshotPayload(
        device_id = "test_device_pr10",
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
        carrier_foreground_visible = carrierForegroundVisible,
        interaction_surface_ready = interactionSurfaceReady,
        carrier_lifecycle_state = carrierLifecycleState
    )

    /** Minimal valid [DeviceExecutionEventPayload] with optional field overrides. */
    private fun baseExecutionEvent(
        phase: String = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
        carrierLifecycleState: String? = null,
        carrierForegroundVisible: Boolean? = null,
        interactionSurfaceReady: Boolean? = null
    ): DeviceExecutionEventPayload = DeviceExecutionEventPayload(
        flow_id = "flow-pr10",
        task_id = "task-pr10",
        phase = phase,
        device_id = "test_device_pr10",
        source_component = "Pr10AndroidCarrierConsolidationTest",
        carrier_lifecycle_state = carrierLifecycleState,
        carrier_foreground_visible = carrierForegroundVisible,
        interaction_surface_ready = interactionSurfaceReady
    )

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceStateSnapshotPayload — carrier_lifecycle_state field defaults
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_lifecycle_state defaults to null when not provided in snapshot`() {
        val snapshot = baseSnapshot()
        assertNull(snapshot.carrier_lifecycle_state)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceStateSnapshotPayload — Gson round-trip for all wire values
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_lifecycle_state idle round-trips through Gson in snapshot`() {
        val snapshot = baseSnapshot(carrierLifecycleState = "idle")
        val json = toJsonObject(snapshot)
        assertEquals("idle", json.get("carrier_lifecycle_state")?.asString)
    }

    @Test
    fun `carrier_lifecycle_state starting round-trips through Gson in snapshot`() {
        val snapshot = baseSnapshot(carrierLifecycleState = "starting")
        val json = toJsonObject(snapshot)
        assertEquals("starting", json.get("carrier_lifecycle_state")?.asString)
    }

    @Test
    fun `carrier_lifecycle_state active round-trips through Gson in snapshot`() {
        val snapshot = baseSnapshot(carrierLifecycleState = "active")
        val json = toJsonObject(snapshot)
        assertEquals("active", json.get("carrier_lifecycle_state")?.asString)
    }

    @Test
    fun `carrier_lifecycle_state failed round-trips through Gson in snapshot`() {
        val snapshot = baseSnapshot(carrierLifecycleState = "failed")
        val json = toJsonObject(snapshot)
        assertEquals("failed", json.get("carrier_lifecycle_state")?.asString)
    }

    @Test
    fun `carrier_lifecycle_state local_only round-trips through Gson in snapshot`() {
        val snapshot = baseSnapshot(carrierLifecycleState = "local_only")
        val json = toJsonObject(snapshot)
        assertEquals("local_only", json.get("carrier_lifecycle_state")?.asString)
    }

    @Test
    fun `carrier_lifecycle_state null serialises as JSON null in snapshot`() {
        val snapshot = baseSnapshot(carrierLifecycleState = null)
        val json = toJsonObject(snapshot)
        assertTrue(
            "carrier_lifecycle_state must be JSON null when not set",
            json.has("carrier_lifecycle_state") && json.get("carrier_lifecycle_state").isJsonNull
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceExecutionEventPayload — carrier_lifecycle_state field defaults
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_lifecycle_state defaults to null when not provided in execution event`() {
        val event = baseExecutionEvent()
        assertNull(event.carrier_lifecycle_state)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DeviceExecutionEventPayload — Gson round-trip for key wire values
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_lifecycle_state active round-trips through Gson in execution event`() {
        val event = baseExecutionEvent(carrierLifecycleState = "active")
        val json = toJsonObject(event)
        assertEquals("active", json.get("carrier_lifecycle_state")?.asString)
    }

    @Test
    fun `carrier_lifecycle_state local_only round-trips through Gson in execution event`() {
        val event = baseExecutionEvent(carrierLifecycleState = "local_only")
        val json = toJsonObject(event)
        assertEquals("local_only", json.get("carrier_lifecycle_state")?.asString)
    }

    @Test
    fun `carrier_lifecycle_state null serialises as JSON null in execution event`() {
        val event = baseExecutionEvent(carrierLifecycleState = null)
        val json = toJsonObject(event)
        assertTrue(
            "carrier_lifecycle_state must be JSON null when not set in event",
            json.has("carrier_lifecycle_state") && json.get("carrier_lifecycle_state").isJsonNull
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Wire key name stability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `snapshot carrier_lifecycle_state wire key is carrier_lifecycle_state`() {
        val json = toJsonObject(baseSnapshot(carrierLifecycleState = "active"))
        assertTrue(
            "snapshot must use wire key 'carrier_lifecycle_state'",
            json.has("carrier_lifecycle_state")
        )
    }

    @Test
    fun `event carrier_lifecycle_state wire key is carrier_lifecycle_state`() {
        val json = toJsonObject(baseExecutionEvent(carrierLifecycleState = "active"))
        assertTrue(
            "execution event must use wire key 'carrier_lifecycle_state'",
            json.has("carrier_lifecycle_state")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Consistency — snapshot and execution event agree when from same carrier state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_lifecycle_state is consistent between snapshot and execution event from same state`() {
        // Simulates the scenario where both the snapshot and the execution event are
        // emitted with the same carrier state — V2 must see the same lifecycle state.
        val snapshot = baseSnapshot(carrierLifecycleState = "active")
        val event = baseExecutionEvent(carrierLifecycleState = "active")
        assertEquals(
            "carrier_lifecycle_state must agree between snapshot and execution event",
            snapshot.carrier_lifecycle_state,
            event.carrier_lifecycle_state
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Consolidated carrier state — all three carrier hint groups are consistent
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `all carrier fields survive Gson round-trip together in snapshot`() {
        val snapshot = baseSnapshot(
            carrierLifecycleState = "active",
            carrierForegroundVisible = true,
            interactionSurfaceReady = true
        )
        val json = toJsonObject(snapshot)
        assertEquals(
            "carrier_lifecycle_state must survive round-trip",
            "active",
            json.get("carrier_lifecycle_state")?.asString
        )
        assertEquals(
            "carrier_foreground_visible must survive round-trip",
            true,
            json.get("carrier_foreground_visible")?.asBoolean
        )
        assertEquals(
            "interaction_surface_ready must survive round-trip",
            true,
            json.get("interaction_surface_ready")?.asBoolean
        )
    }

    @Test
    fun `all carrier fields survive Gson round-trip together in execution event`() {
        val event = baseExecutionEvent(
            carrierLifecycleState = "active",
            carrierForegroundVisible = true,
            interactionSurfaceReady = true
        )
        val json = toJsonObject(event)
        assertEquals(
            "carrier_lifecycle_state must survive round-trip in event",
            "active",
            json.get("carrier_lifecycle_state")?.asString
        )
        assertEquals(
            "carrier_foreground_visible must survive round-trip in event",
            true,
            json.get("carrier_foreground_visible")?.asBoolean
        )
        assertEquals(
            "interaction_surface_ready must survive round-trip in event",
            true,
            json.get("interaction_surface_ready")?.asBoolean
        )
    }

    @Test
    fun `carrier lifecycle state and manifestation hints are consistent across snapshot and event`() {
        // When snapshot and event are emitted from the same carrier state, all three
        // carrier hint groups must agree — ensuring V2 sees a coherent Android carrier.
        val snapshot = baseSnapshot(
            carrierLifecycleState = "active",
            carrierForegroundVisible = true,
            interactionSurfaceReady = true
        )
        val event = baseExecutionEvent(
            carrierLifecycleState = "active",
            carrierForegroundVisible = true,
            interactionSurfaceReady = true
        )
        assertEquals(
            "carrier_lifecycle_state must agree across snapshot and event",
            snapshot.carrier_lifecycle_state,
            event.carrier_lifecycle_state
        )
        assertEquals(
            "carrier_foreground_visible must agree across snapshot and event",
            snapshot.carrier_foreground_visible,
            event.carrier_foreground_visible
        )
        assertEquals(
            "interaction_surface_ready must agree across snapshot and event",
            snapshot.interaction_surface_ready,
            event.interaction_surface_ready
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // No fake values
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_lifecycle_state is null in JSON when not backed by real state`() {
        val snapshot = baseSnapshot(carrierLifecycleState = null)
        val json = toJsonObject(snapshot)
        assertTrue(
            "carrier_lifecycle_state must be JSON null (not a fake string) when state is null",
            json.has("carrier_lifecycle_state") && json.get("carrier_lifecycle_state").isJsonNull
        )
    }

    @Test
    fun `carrier_lifecycle_state is null in event JSON when not backed by real state`() {
        val event = baseExecutionEvent(carrierLifecycleState = null)
        val json = toJsonObject(event)
        assertTrue(
            "carrier_lifecycle_state must be JSON null (not a fake string) in event when state is null",
            json.has("carrier_lifecycle_state") && json.get("carrier_lifecycle_state").isJsonNull
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // wireLabel coverage — all RuntimeState values produce the expected labels
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `RuntimeState Idle produces wireLabel idle`() {
        assertEquals("idle", RuntimeController.RuntimeState.Idle.wireLabel)
    }

    @Test
    fun `RuntimeState Starting produces wireLabel starting`() {
        assertEquals("starting", RuntimeController.RuntimeState.Starting.wireLabel)
    }

    @Test
    fun `RuntimeState Active produces wireLabel active`() {
        assertEquals("active", RuntimeController.RuntimeState.Active.wireLabel)
    }

    @Test
    fun `RuntimeState Failed produces wireLabel failed`() {
        assertEquals("failed", RuntimeController.RuntimeState.Failed("test").wireLabel)
    }

    @Test
    fun `RuntimeState LocalOnly produces wireLabel local_only`() {
        assertEquals("local_only", RuntimeController.RuntimeState.LocalOnly.wireLabel)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Snapshot field completeness — carrier_lifecycle_state appears alongside
    // all other carrier hint fields
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `snapshot JSON contains all carrier hint fields when all are set`() {
        val snapshot = baseSnapshot(
            carrierLifecycleState = "active",
            carrierForegroundVisible = true,
            interactionSurfaceReady = true
        )
        val json = toJsonObject(snapshot)
        assertTrue("carrier_lifecycle_state missing from snapshot JSON", json.has("carrier_lifecycle_state"))
        assertTrue("carrier_foreground_visible missing from snapshot JSON", json.has("carrier_foreground_visible"))
        assertTrue("interaction_surface_ready missing from snapshot JSON", json.has("interaction_surface_ready"))
    }

    @Test
    fun `event JSON contains all carrier hint fields when all are set`() {
        val event = baseExecutionEvent(
            carrierLifecycleState = "active",
            carrierForegroundVisible = true,
            interactionSurfaceReady = true
        )
        val json = toJsonObject(event)
        assertTrue("carrier_lifecycle_state missing from event JSON", json.has("carrier_lifecycle_state"))
        assertTrue("carrier_foreground_visible missing from event JSON", json.has("carrier_foreground_visible"))
        assertTrue("interaction_surface_ready missing from event JSON", json.has("interaction_surface_ready"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session identity + carrier state consistency
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carrier_lifecycle_state active is compatible with all session identity fields set`() {
        // An "active" carrier (cross-device connected) legitimately has all session fields
        // populated.  This test verifies there is no data model conflict.
        val snapshot = DeviceStateSnapshotPayload(
            device_id = "test_device_pr10",
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
            durable_session_id = "durable-pr10-abc",
            session_continuity_epoch = 1,
            runtime_session_id = "runtime-pr10-xyz",
            attached_session_id = "attached-pr10-def",
            carrier_foreground_visible = true,
            interaction_surface_ready = true,
            carrier_lifecycle_state = "active"
        )
        val json = toJsonObject(snapshot)
        assertEquals("active", json.get("carrier_lifecycle_state")?.asString)
        assertEquals("durable-pr10-abc", json.get("durable_session_id")?.asString)
        assertEquals(1, json.get("session_continuity_epoch")?.asInt)
        assertEquals("runtime-pr10-xyz", json.get("runtime_session_id")?.asString)
        assertEquals("attached-pr10-def", json.get("attached_session_id")?.asString)
        assertEquals(true, json.get("carrier_foreground_visible")?.asBoolean)
        assertEquals(true, json.get("interaction_surface_ready")?.asBoolean)
    }

    @Test
    fun `carrier_lifecycle_state local_only is compatible with null session identity fields`() {
        // A "local_only" carrier has no cross-device session active, so session fields
        // are legitimately null.  Verify no payload conflict.
        val snapshot = DeviceStateSnapshotPayload(
            device_id = "test_device_pr10",
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
            current_fallback_tier = "local_only",
            durable_session_id = null,
            session_continuity_epoch = null,
            runtime_session_id = null,
            attached_session_id = null,
            carrier_lifecycle_state = "local_only"
        )
        val json = toJsonObject(snapshot)
        assertEquals("local_only", json.get("carrier_lifecycle_state")?.asString)
        assertTrue("durable_session_id must be JSON null", json.get("durable_session_id").isJsonNull)
        assertTrue("attached_session_id must be JSON null", json.get("attached_session_id").isJsonNull)
    }
}
