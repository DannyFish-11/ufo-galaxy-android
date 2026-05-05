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
 * PR-3 Android — Runtime-state schema consistency regression tests.
 *
 * Validates the two real schema gaps identified during the PR-3 cross-repo audit:
 *
 * ## Gap 1 — `event_ts` in `DeviceExecutionEventPayload`
 *
 * V2's `_parse_execution_event` reads `event_ts` / `eventTs` / `timestamp` (seconds, float)
 * to populate `DeviceExecutionEvent.event_ts`.  Prior to PR-3 the Android payload only carried
 * `timestamp_ms` (milliseconds, Long), which V2 does not read for `event_ts`.  PR-3 adds
 * [DeviceExecutionEventPayload.event_ts] as a backed field derived from
 * `timestamp_ms / 1000.0` so V2 can absorb the device-side timestamp correctly.
 *
 * ## Gap 2 — `planner_fallback_tier` and `grounding_fallback_tier` in `DeviceStateSnapshotPayload`
 *
 * V2's `_parse_state_snapshot` accepts `planner_fallback_tier` / `plannerFallbackTier` and
 * `grounding_fallback_tier` / `groundingFallbackTier` as separate per-subsystem fallback tier
 * fields.  Prior to PR-3 the Android payload only carried `current_fallback_tier` (derived from
 * the kill-switch rollout snapshot).  Android has real local anchors for the per-subsystem tiers
 * in [com.ufo.galaxy.config.FallbackConfig.enablePlannerFallback] and
 * [com.ufo.galaxy.config.FallbackConfig.enableGroundingFallback].  PR-3 adds these fields with
 * values `"active"` / `"disabled"` derived from those flags.
 *
 * ## Test matrix
 *
 * ### event_ts field (execution event)
 *  - event_ts is present in execution event JSON
 *  - event_ts is a positive double
 *  - event_ts equals timestamp_ms / 1000.0
 *  - event_ts round-trips through Gson
 *  - event_ts is independent of (but consistent with) timestamp_ms
 *  - event_ts is correct for a known timestamp_ms value
 *
 * ### planner_fallback_tier field (snapshot)
 *  - planner_fallback_tier is serialised as a JSON string when non-null
 *  - planner_fallback_tier is serialised as JSON null when null
 *  - planner_fallback_tier value "active" round-trips through Gson
 *  - planner_fallback_tier value "disabled" round-trips through Gson
 *
 * ### grounding_fallback_tier field (snapshot)
 *  - grounding_fallback_tier is serialised as a JSON string when non-null
 *  - grounding_fallback_tier is serialised as JSON null when null
 *  - grounding_fallback_tier value "active" round-trips through Gson
 *  - grounding_fallback_tier value "disabled" round-trips through Gson
 *
 * ### V2 field completeness (snapshot)
 *  - V2 _parse_state_snapshot primary keys for PR-3 fields are present in JSON
 *
 * ### Schema consistency — no field name drift between emit paths
 *  - Snapshot and execution event do not share contradictory field names for common semantics
 *  - event_ts is not present in snapshot (correct — different payload type)
 *  - planner_fallback_tier is not present in execution event (correct — snapshot-only field)
 */
class Pr3RuntimeStateSchemaConsistencyTest {

    private val gson = Gson()

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun executionEvent(
        flowId: String = "flow-pr3",
        taskId: String = "task-pr3",
        phase: String = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
        timestampMs: Long = 1_700_000_000_000L  // fixed for deterministic assertions
    ): DeviceExecutionEventPayload = DeviceExecutionEventPayload(
        flow_id = flowId,
        task_id = taskId,
        phase = phase,
        timestamp_ms = timestampMs
    )

    private fun snapshotWithFallbackTiers(
        plannerFallbackTier: String? = "active",
        groundingFallbackTier: String? = "active"
    ): DeviceStateSnapshotPayload = DeviceStateSnapshotPayload(
        device_id = "test_device_pr3",
        snapshot_ts = System.currentTimeMillis(),
        llama_cpp_available = true,
        ncnn_available = false,
        active_runtime_type = "LLAMA_CPP",
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
        local_loop_config = mapOf("max_steps" to 10),
        warmup_result = "ok",
        runtime_health_snapshot = mapOf("planner_health" to "HEALTHY"),
        offline_queue_depth = 0,
        current_fallback_tier = "center_delegated_with_local_fallback",
        planner_fallback_tier = plannerFallbackTier,
        grounding_fallback_tier = groundingFallbackTier
    )

    // ═════════════════════════════════════════════════════════════════════════
    // event_ts field — execution event
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `event_ts is present in execution event JSON`() {
        val evt = executionEvent()
        val json = gson.toJsonTree(evt).asJsonObject
        assertTrue(
            "event_ts must be present as a top-level key in the JSON payload",
            json.has("event_ts")
        )
    }

    @Test
    fun `event_ts is a positive double in JSON`() {
        val evt = executionEvent()
        val json = gson.toJsonTree(evt).asJsonObject
        val evtTs = json.get("event_ts").asDouble
        assertTrue("event_ts must be positive (> 0)", evtTs > 0.0)
    }

    @Test
    fun `event_ts equals timestamp_ms divided by 1000`() {
        val fixedMs = 1_700_000_000_000L
        val evt = executionEvent(timestampMs = fixedMs)
        val expectedSeconds = fixedMs / 1000.0
        assertEquals(
            "event_ts must equal timestamp_ms / 1000.0",
            expectedSeconds,
            evt.event_ts,
            0.0
        )
    }

    @Test
    fun `event_ts correct value for known timestamp_ms`() {
        val knownMs = 1_700_000_123_456L  // 2023-11-14 at 22:55:23.456 UTC
        val evt = executionEvent(timestampMs = knownMs)
        val json = gson.toJsonTree(evt).asJsonObject
        val evtTs = json.get("event_ts").asDouble
        assertEquals(
            "event_ts must be knownMs / 1000.0",
            knownMs / 1000.0,
            evtTs,
            0.001  // allow sub-millisecond float rounding
        )
    }

    @Test
    fun `event_ts round-trips through Gson`() {
        val fixedMs = 1_700_000_000_000L
        val evt = executionEvent(timestampMs = fixedMs)
        val json = gson.toJsonTree(evt).asJsonObject
        val serialised = json.get("event_ts").asDouble
        assertEquals(
            "event_ts must round-trip correctly",
            fixedMs / 1000.0,
            serialised,
            0.0
        )
    }

    @Test
    fun `event_ts and timestamp_ms are in consistent units`() {
        val fixedMs = 1_700_000_000_000L
        val evt = executionEvent(timestampMs = fixedMs)
        val json = gson.toJsonTree(evt).asJsonObject
        val tsMs = json.get("timestamp_ms").asLong
        val evtTs = json.get("event_ts").asDouble
        assertEquals(
            "event_ts * 1000 must equal timestamp_ms (consistent units)",
            tsMs.toDouble(),
            evtTs * 1000.0,
            1.0  // allow 1ms float rounding tolerance
        )
    }

    @Test
    fun `event_ts is distinct from timestamp_ms field name`() {
        val evt = executionEvent()
        val json = gson.toJsonTree(evt).asJsonObject
        assertTrue("Both timestamp_ms and event_ts must be present", json.has("timestamp_ms"))
        assertTrue("Both timestamp_ms and event_ts must be present", json.has("event_ts"))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // planner_fallback_tier field — snapshot
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `planner_fallback_tier active round-trips through Gson`() {
        val snap = snapshotWithFallbackTiers(plannerFallbackTier = "active")
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue("planner_fallback_tier must be present in JSON", json.has("planner_fallback_tier"))
        assertEquals("active", json.get("planner_fallback_tier").asString)
    }

    @Test
    fun `planner_fallback_tier disabled round-trips through Gson`() {
        val snap = snapshotWithFallbackTiers(plannerFallbackTier = "disabled")
        val json = gson.toJsonTree(snap).asJsonObject
        assertEquals("disabled", json.get("planner_fallback_tier").asString)
    }

    @Test
    fun `planner_fallback_tier null is serialised as JSON null`() {
        val snap = snapshotWithFallbackTiers(plannerFallbackTier = null)
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(
            "planner_fallback_tier must be present as JSON null when null",
            json.has("planner_fallback_tier")
        )
        assertTrue(
            "planner_fallback_tier must be JSON null when null",
            json.get("planner_fallback_tier").isJsonNull
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // grounding_fallback_tier field — snapshot
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `grounding_fallback_tier active round-trips through Gson`() {
        val snap = snapshotWithFallbackTiers(groundingFallbackTier = "active")
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue("grounding_fallback_tier must be present in JSON", json.has("grounding_fallback_tier"))
        assertEquals("active", json.get("grounding_fallback_tier").asString)
    }

    @Test
    fun `grounding_fallback_tier disabled round-trips through Gson`() {
        val snap = snapshotWithFallbackTiers(groundingFallbackTier = "disabled")
        val json = gson.toJsonTree(snap).asJsonObject
        assertEquals("disabled", json.get("grounding_fallback_tier").asString)
    }

    @Test
    fun `grounding_fallback_tier null is serialised as JSON null`() {
        val snap = snapshotWithFallbackTiers(groundingFallbackTier = null)
        val json = gson.toJsonTree(snap).asJsonObject
        assertTrue(
            "grounding_fallback_tier must be present as JSON null when null",
            json.has("grounding_fallback_tier")
        )
        assertTrue(
            "grounding_fallback_tier must be JSON null when null",
            json.get("grounding_fallback_tier").isJsonNull
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // V2 field completeness — PR-3 additions
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `snapshot with PR-3 fields contains all V2 _parse_state_snapshot PR-3 primary keys`() {
        val snap = snapshotWithFallbackTiers()
        val json = gson.toJsonTree(snap).asJsonObject
        val pr3Keys = listOf("planner_fallback_tier", "grounding_fallback_tier")
        val missingKeys = pr3Keys.filter { !json.has(it) }
        assertTrue(
            "PR-3 V2-facing snapshot keys must all be present in JSON: $missingKeys",
            missingKeys.isEmpty()
        )
    }

    @Test
    fun `execution event with PR-3 field contains V2 event_ts primary key`() {
        val evt = executionEvent()
        val json = gson.toJsonTree(evt).asJsonObject
        assertTrue(
            "event_ts must be present in execution event JSON for V2 _parse_execution_event",
            json.has("event_ts")
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Schema consistency — no cross-type field contamination
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `planner_fallback_tier is not present in execution event JSON`() {
        val evt = executionEvent()
        val json = gson.toJsonTree(evt).asJsonObject
        // planner_fallback_tier is a snapshot-only field — it must not appear in execution events
        assertTrue(
            "planner_fallback_tier must NOT be a field in execution event JSON",
            !json.has("planner_fallback_tier")
        )
    }

    @Test
    fun `grounding_fallback_tier is not present in execution event JSON`() {
        val evt = executionEvent()
        val json = gson.toJsonTree(evt).asJsonObject
        assertTrue(
            "grounding_fallback_tier must NOT be a field in execution event JSON",
            !json.has("grounding_fallback_tier")
        )
    }

    @Test
    fun `event_ts is not present in snapshot JSON`() {
        val snap = snapshotWithFallbackTiers()
        val json = gson.toJsonTree(snap).asJsonObject
        // event_ts is an execution-event field — it must not appear in snapshots
        assertTrue(
            "event_ts must NOT be a field in snapshot JSON",
            !json.has("event_ts")
        )
    }

    @Test
    fun `planner and grounding fallback tier values are independent`() {
        val snap = snapshotWithFallbackTiers(
            plannerFallbackTier = "active",
            groundingFallbackTier = "disabled"
        )
        val json = gson.toJsonTree(snap).asJsonObject
        assertEquals("active", json.get("planner_fallback_tier").asString)
        assertEquals("disabled", json.get("grounding_fallback_tier").asString)
    }

    @Test
    fun `both new snapshot fallback tier fields default to null`() {
        val snap = DeviceStateSnapshotPayload(
            device_id = "test",
            llama_cpp_available = null,
            ncnn_available = null,
            active_runtime_type = null,
            model_ready = null,
            accessibility_ready = null,
            overlay_ready = null,
            local_loop_ready = null,
            model_id = null,
            runtime_type = null,
            checksum_ok = null,
            mobilevlm_present = null,
            mobilevlm_checksum_ok = null,
            seeclick_present = null,
            pending_first_download = null,
            warmup_result = null,
            offline_queue_depth = null,
            current_fallback_tier = null
            // planner_fallback_tier and grounding_fallback_tier omitted — should default to null
        )
        assertNull(snap.planner_fallback_tier)
        assertNull(snap.grounding_fallback_tier)
    }
}
