package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-2 (Android companion) — Device execution-event emission validation test matrix.
 *
 * Proves that:
 *  1. [MsgType.DEVICE_EXECUTION_EVENT] has the correct wire value `"device_execution_event"`.
 *  2. [DeviceExecutionEventPayload] carries all V2-facing fields required by V2's
 *     `_parse_execution_event` function (flow_id, task_id, phase, step_index, is_blocking,
 *     blocking_reason, stagnation_detected, fallback_tier).
 *  3. The payload serialises to JSON whose keys match V2's primary snake_case field names.
 *  4. An [AipMessage] envelope wrapping [DeviceExecutionEventPayload] serialises without
 *     losing the `device_id`, `runtime_session_id`, and `idempotency_key` envelope fields.
 *  5. Phase constants on [DeviceExecutionEventPayload.Companion] have stable wire values that
 *     match the V2 phase vocabulary.
 *  6. [DeviceExecutionEventSink] is a functional interface that can be instantiated as a
 *     lambda and collects events for assertion.
 *  7. [GalaxyLogger.TAG_DEVICE_EXECUTION_EVENT] has the correct value.
 *  8. Distinct events produce distinct [DeviceExecutionEventPayload.event_id] values.
 *  9. Null optional fields (`fallback_tier`) are serialised as JSON null (not absent).
 * 10. Non-default field values round-trip correctly through Gson.
 *
 * ## Test matrix
 *
 * ### MsgType wire-value stability
 *  - DEVICE_EXECUTION_EVENT wire value is "device_execution_event"
 *  - DEVICE_EXECUTION_EVENT is distinct from DEVICE_STATE_SNAPSHOT
 *
 * ### Phase constant wire-value stability
 *  - PHASE_EXECUTION_STARTED is "execution_started"
 *  - PHASE_EXECUTION_PROGRESS is "execution_progress"
 *  - PHASE_COMPLETED is "completed"
 *  - PHASE_FAILED is "failed"
 *  - PHASE_STAGNATION_DETECTED is "stagnation_detected"
 *  - PHASE_CANCELLED is "cancelled"
 *  - PHASE_FALLBACK_TRANSITION is "fallback_transition"
 *  - PHASE_TAKEOVER_MILESTONE is "takeover_milestone"
 *  - All 8 phase constants are distinct
 *
 * ### Payload field population
 *  - flow_id field round-trips through Gson
 *  - task_id field round-trips through Gson
 *  - phase field round-trips through Gson
 *  - step_index field round-trips through Gson (defaults to -1)
 *  - is_blocking field round-trips through Gson (defaults to false)
 *  - blocking_reason field round-trips through Gson (defaults to empty string)
 *  - stagnation_detected field round-trips through Gson (defaults to false)
 *  - fallback_tier field is null when not set
 *  - fallback_tier field round-trips through Gson when set
 *  - device_id field round-trips through Gson
 *  - source_component field round-trips through Gson
 *  - timestamp_ms field is present and positive
 *  - event_id field is present and non-blank
 *  - event_id is distinct across two independent payload instances
 *
 * ### JSON serialisation
 *  - JSON keys match V2 primary snake_case names (not camelCase)
 *  - null fallback_tier is serialised as JSON null (not absent)
 *  - JSON object has all required V2-ingress keys
 *
 * ### AipMessage envelope
 *  - AipMessage envelope type is DEVICE_EXECUTION_EVENT
 *  - AipMessage envelope device_id is set from payload
 *  - AipMessage envelope correlation_id is set from task_id
 *  - AipMessage envelope idempotency_key matches event_id
 *  - AipMessage envelope serialises without loss of runtime_session_id
 *
 * ### DeviceExecutionEventSink functional interface
 *  - Sink receives payload via lambda and accumulates events
 *  - Sink receives multiple events in order
 *  - Sink does not throw on receipt
 *
 * ### GalaxyLogger tag stability
 *  - TAG_DEVICE_EXECUTION_EVENT value is "GALAXY:DEVICE:EXEC:EVENT"
 */
class Pr2DeviceExecutionEventEmissionTest {

    private val gson = Gson()

    // ── MsgType wire-value stability ─────────────────────────────────────────

    @Test fun `DEVICE_EXECUTION_EVENT wire value is device_execution_event`() {
        assertEquals("device_execution_event", MsgType.DEVICE_EXECUTION_EVENT.value)
    }

    @Test fun `DEVICE_EXECUTION_EVENT is distinct from DEVICE_STATE_SNAPSHOT`() {
        assertNotEquals(MsgType.DEVICE_EXECUTION_EVENT, MsgType.DEVICE_STATE_SNAPSHOT)
        assertNotEquals(
            MsgType.DEVICE_EXECUTION_EVENT.value,
            MsgType.DEVICE_STATE_SNAPSHOT.value
        )
    }

    // ── Phase constant wire-value stability ───────────────────────────────────

    @Test fun `PHASE_EXECUTION_STARTED is execution_started`() {
        assertEquals("execution_started", DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED)
    }

    @Test fun `PHASE_EXECUTION_PROGRESS is execution_progress`() {
        assertEquals("execution_progress", DeviceExecutionEventPayload.PHASE_EXECUTION_PROGRESS)
    }

    @Test fun `PHASE_COMPLETED is completed`() {
        assertEquals("completed", DeviceExecutionEventPayload.PHASE_COMPLETED)
    }

    @Test fun `PHASE_FAILED is failed`() {
        assertEquals("failed", DeviceExecutionEventPayload.PHASE_FAILED)
    }

    @Test fun `PHASE_STAGNATION_DETECTED is stagnation_detected`() {
        assertEquals("stagnation_detected", DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED)
    }

    @Test fun `PHASE_CANCELLED is cancelled`() {
        assertEquals("cancelled", DeviceExecutionEventPayload.PHASE_CANCELLED)
    }

    @Test fun `PHASE_FALLBACK_TRANSITION is fallback_transition`() {
        assertEquals("fallback_transition", DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION)
    }

    @Test fun `PHASE_TAKEOVER_MILESTONE is takeover_milestone`() {
        assertEquals("takeover_milestone", DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE)
    }

    @Test fun `all 8 phase constants are distinct`() {
        val phases = setOf(
            DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            DeviceExecutionEventPayload.PHASE_EXECUTION_PROGRESS,
            DeviceExecutionEventPayload.PHASE_COMPLETED,
            DeviceExecutionEventPayload.PHASE_FAILED,
            DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED,
            DeviceExecutionEventPayload.PHASE_CANCELLED,
            DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION,
            DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE
        )
        assertEquals(8, phases.size)
    }

    // ── Payload field population ──────────────────────────────────────────────

    @Test fun `flow_id field round-trips through Gson`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "flow-abc",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
        )
        val json = gson.toJsonTree(p).asJsonObject
        assertEquals("flow-abc", json.get("flow_id").asString)
    }

    @Test fun `task_id field round-trips through Gson`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "flow-abc",
            task_id = "task-xyz",
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED
        )
        val json = gson.toJsonTree(p).asJsonObject
        assertEquals("task-xyz", json.get("task_id").asString)
    }

    @Test fun `phase field round-trips through Gson`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_FAILED
        )
        val json = gson.toJsonTree(p).asJsonObject
        assertEquals("failed", json.get("phase").asString)
    }

    @Test fun `step_index defaults to -1`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
        )
        assertEquals(-1, p.step_index)
        val json = gson.toJsonTree(p).asJsonObject
        assertEquals(-1, json.get("step_index").asInt)
    }

    @Test fun `step_index field round-trips through Gson`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_PROGRESS,
            step_index = 4
        )
        val json = gson.toJsonTree(p).asJsonObject
        assertEquals(4, json.get("step_index").asInt)
    }

    @Test fun `is_blocking defaults to false`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
        )
        assertFalse(p.is_blocking)
        val json = gson.toJsonTree(p).asJsonObject
        assertFalse(json.get("is_blocking").asBoolean)
    }

    @Test fun `is_blocking field round-trips through Gson`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_FAILED,
            is_blocking = true
        )
        val json = gson.toJsonTree(p).asJsonObject
        assertTrue(json.get("is_blocking").asBoolean)
    }

    @Test fun `blocking_reason defaults to empty string`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
        )
        assertEquals("", p.blocking_reason)
        val json = gson.toJsonTree(p).asJsonObject
        assertEquals("", json.get("blocking_reason").asString)
    }

    @Test fun `blocking_reason field round-trips through Gson`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_FAILED,
            is_blocking = true,
            blocking_reason = "execution_exception"
        )
        val json = gson.toJsonTree(p).asJsonObject
        assertEquals("execution_exception", json.get("blocking_reason").asString)
    }

    @Test fun `stagnation_detected defaults to false`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
        )
        assertFalse(p.stagnation_detected)
        val json = gson.toJsonTree(p).asJsonObject
        assertFalse(json.get("stagnation_detected").asBoolean)
    }

    @Test fun `stagnation_detected field round-trips through Gson`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED,
            is_blocking = true,
            stagnation_detected = true
        )
        val json = gson.toJsonTree(p).asJsonObject
        assertTrue(json.get("stagnation_detected").asBoolean)
    }

    @Test fun `fallback_tier is null when not set`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
        )
        assertNull(p.fallback_tier)
    }

    @Test fun `fallback_tier field round-trips through Gson when set`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION,
            fallback_tier = "local_fallback"
        )
        val json = gson.toJsonTree(p).asJsonObject
        assertEquals("local_fallback", json.get("fallback_tier").asString)
    }

    @Test fun `device_id field round-trips through Gson`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            device_id = "Pixel_8"
        )
        val json = gson.toJsonTree(p).asJsonObject
        assertEquals("Pixel_8", json.get("device_id").asString)
    }

    @Test fun `source_component field round-trips through Gson`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            source_component = "GalaxyConnectionService.handleGoalExecution"
        )
        val json = gson.toJsonTree(p).asJsonObject
        assertEquals(
            "GalaxyConnectionService.handleGoalExecution",
            json.get("source_component").asString
        )
    }

    @Test fun `timestamp_ms field is present and positive`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED
        )
        val json = gson.toJsonTree(p).asJsonObject
        assertTrue(json.has("timestamp_ms"))
        assertTrue(json.get("timestamp_ms").asLong > 0)
    }

    @Test fun `event_id field is present and non-blank`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED
        )
        assertTrue(p.event_id.isNotBlank())
        val json = gson.toJsonTree(p).asJsonObject
        assertTrue(json.has("event_id"))
        assertTrue(json.get("event_id").asString.isNotBlank())
    }

    @Test fun `event_id is distinct across two independent payload instances`() {
        val p1 = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
        )
        val p2 = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED
        )
        assertNotEquals(p1.event_id, p2.event_id)
    }

    // ── JSON serialisation ────────────────────────────────────────────────────

    @Test fun `JSON keys match V2 primary snake_case names`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "flow-1",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            step_index = 0,
            is_blocking = false,
            blocking_reason = "",
            stagnation_detected = false,
            fallback_tier = null,
            device_id = "d1"
        )
        val json = gson.toJsonTree(p).asJsonObject
        // V2's _parse_execution_event reads these primary names first
        assertTrue(json.has("flow_id"))
        assertTrue(json.has("task_id"))
        assertTrue(json.has("phase"))
        assertTrue(json.has("step_index"))
        assertTrue(json.has("is_blocking"))
        assertTrue(json.has("blocking_reason"))
        assertTrue(json.has("stagnation_detected"))
        assertTrue(json.has("fallback_tier"))
    }

    @Test fun `null fallback_tier is serialised as JSON null`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
        )
        val json = gson.toJsonTree(p).asJsonObject
        assertTrue(json.has("fallback_tier"))
        assertTrue(json.get("fallback_tier").isJsonNull)
    }

    @Test fun `JSON object has all required V2-ingress keys`() {
        val required = listOf(
            "flow_id", "task_id", "phase", "step_index",
            "is_blocking", "blocking_reason", "stagnation_detected", "fallback_tier"
        )
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
        )
        val json = gson.toJsonTree(p).asJsonObject
        for (key in required) {
            assertTrue("Missing V2-required key: $key", json.has(key))
        }
    }

    // ── AipMessage envelope ───────────────────────────────────────────────────

    @Test fun `AipMessage envelope type is DEVICE_EXECUTION_EVENT`() {
        val payload = DeviceExecutionEventPayload(
            flow_id = "flow-1",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            device_id = "Pixel_8"
        )
        val envelope = AipMessage(
            type = MsgType.DEVICE_EXECUTION_EVENT,
            payload = payload,
            device_id = "Pixel_8",
            correlation_id = payload.task_id,
            idempotency_key = payload.event_id
        )
        assertEquals(MsgType.DEVICE_EXECUTION_EVENT, envelope.type)
    }

    @Test fun `AipMessage envelope device_id is set from payload`() {
        val payload = DeviceExecutionEventPayload(
            flow_id = "flow-1",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            device_id = "Pixel_8"
        )
        val envelope = AipMessage(
            type = MsgType.DEVICE_EXECUTION_EVENT,
            payload = payload,
            device_id = payload.device_id,
            correlation_id = payload.task_id,
            idempotency_key = payload.event_id
        )
        assertEquals("Pixel_8", envelope.device_id)
    }

    @Test fun `AipMessage envelope correlation_id is set from task_id`() {
        val payload = DeviceExecutionEventPayload(
            flow_id = "flow-1",
            task_id = "task-abc",
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED
        )
        val envelope = AipMessage(
            type = MsgType.DEVICE_EXECUTION_EVENT,
            payload = payload,
            correlation_id = payload.task_id,
            idempotency_key = payload.event_id
        )
        assertEquals("task-abc", envelope.correlation_id)
    }

    @Test fun `AipMessage envelope idempotency_key matches event_id`() {
        val payload = DeviceExecutionEventPayload(
            flow_id = "flow-1",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED
        )
        val envelope = AipMessage(
            type = MsgType.DEVICE_EXECUTION_EVENT,
            payload = payload,
            idempotency_key = payload.event_id
        )
        assertEquals(payload.event_id, envelope.idempotency_key)
    }

    @Test fun `AipMessage envelope serialises without loss of runtime_session_id`() {
        val payload = DeviceExecutionEventPayload(
            flow_id = "flow-1",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED
        )
        val envelope = AipMessage(
            type = MsgType.DEVICE_EXECUTION_EVENT,
            payload = payload,
            device_id = "d1",
            runtime_session_id = "rs-123",
            idempotency_key = payload.event_id
        )
        val json = gson.toJsonTree(envelope).asJsonObject
        assertNotNull(json.get("runtime_session_id"))
        assertEquals("rs-123", json.get("runtime_session_id").asString)
    }

    // ── DeviceExecutionEventSink functional interface ─────────────────────────

    @Test fun `sink receives payload via lambda and accumulates events`() {
        val emitted = mutableListOf<DeviceExecutionEventPayload>()
        val sink = DeviceExecutionEventSink { payload -> emitted += payload }

        val p = DeviceExecutionEventPayload(
            flow_id = "flow-1",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
        )
        sink.onEvent(p)
        assertEquals(1, emitted.size)
        assertEquals(p, emitted[0])
    }

    @Test fun `sink receives multiple events in order`() {
        val emitted = mutableListOf<String>()
        val sink = DeviceExecutionEventSink { payload -> emitted += payload.phase }

        sink.onEvent(
            DeviceExecutionEventPayload(
                flow_id = "f",
                task_id = "t",
                phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
            )
        )
        sink.onEvent(
            DeviceExecutionEventPayload(
                flow_id = "f",
                task_id = "t",
                phase = DeviceExecutionEventPayload.PHASE_COMPLETED
            )
        )

        assertEquals(2, emitted.size)
        assertEquals(DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED, emitted[0])
        assertEquals(DeviceExecutionEventPayload.PHASE_COMPLETED, emitted[1])
    }

    @Test fun `sink does not throw on receipt`() {
        val sink = DeviceExecutionEventSink { /* no-op */ }
        // Should not throw
        sink.onEvent(
            DeviceExecutionEventPayload(
                flow_id = "f",
                task_id = "t",
                phase = DeviceExecutionEventPayload.PHASE_FAILED,
                is_blocking = true,
                blocking_reason = "test_blocking"
            )
        )
    }

    // ── GalaxyLogger tag stability ────────────────────────────────────────────

    @Test fun `TAG_DEVICE_EXECUTION_EVENT value is GALAXY:DEVICE:EXEC:EVENT`() {
        assertEquals("GALAXY:DEVICE:EXEC:EVENT", GalaxyLogger.TAG_DEVICE_EXECUTION_EVENT)
    }

    // ── Stagnation event shape ────────────────────────────────────────────────

    @Test fun `stagnation event carries is_blocking=true and stagnation_detected=true`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED,
            is_blocking = true,
            blocking_reason = "stagnation_guard_triggered",
            stagnation_detected = true
        )
        assertTrue(p.is_blocking)
        assertTrue(p.stagnation_detected)
        assertEquals(DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED, p.phase)
    }

    // ── Fallback transition event shape ───────────────────────────────────────

    @Test fun `fallback event carries phase fallback_transition and blocking_reason`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "task-1",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION,
            blocking_reason = "bridge_handoff_failed"
        )
        assertEquals(DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION, p.phase)
        assertEquals("bridge_handoff_failed", p.blocking_reason)
        assertFalse(p.is_blocking)
    }

    // ── Takeover milestone event shape ────────────────────────────────────────

    @Test fun `takeover event carries phase takeover_milestone and flow_id as takeover_id`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "takeover-xyz",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE
        )
        assertEquals(DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE, p.phase)
        assertEquals("takeover-xyz", p.flow_id)
    }

    // ── V2 alignment: camelCase aliases acceptance ─────────────────────────────

    @Test fun `V2 accepts camelCase aliases - flowId key maps to flow_id value`() {
        // V2's _parse_execution_event reads:
        //   payload.get("flow_id") or payload.get("flowId") or ""
        // Verify that the snake_case key produced by Android JSON is the primary V2 key.
        val p = DeviceExecutionEventPayload(
            flow_id = "my-flow",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
        )
        val json = gson.toJsonTree(p).asJsonObject
        // Android sends snake_case (primary V2 key), not camelCase (alias)
        assertTrue("Android must send primary snake_case key 'flow_id'", json.has("flow_id"))
        assertFalse("Android must not send camelCase alias 'flowId'", json.has("flowId"))
        assertEquals("my-flow", json.get("flow_id").asString)
    }

    @Test fun `V2 accepts camelCase aliases - isBlocking key maps to is_blocking value`() {
        val p = DeviceExecutionEventPayload(
            flow_id = "f",
            task_id = "t",
            phase = DeviceExecutionEventPayload.PHASE_FAILED,
            is_blocking = true
        )
        val json = gson.toJsonTree(p).asJsonObject
        assertTrue("Android must send primary snake_case key 'is_blocking'", json.has("is_blocking"))
        assertTrue(json.get("is_blocking").asBoolean)
    }
}
