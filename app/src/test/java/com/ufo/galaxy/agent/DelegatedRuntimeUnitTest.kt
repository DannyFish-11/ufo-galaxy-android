package com.ufo.galaxy.agent

import com.ufo.galaxy.runtime.SourceRuntimePosture
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [DelegatedRuntimeUnit] — the canonical Android-internal representation
 * of a delegated runtime work unit produced by [DelegatedRuntimeReceiver] (PR-8,
 * post-#533 dual-repo runtime unification master plan — Delegated Runtime
 * Receipt/Activation Foundations, Android side).
 *
 * ## Test matrix
 *
 * ### [DelegatedRuntimeUnit.fromEnvelope] factory
 *  - unitId echoes takeover_id from the envelope.
 *  - taskId echoes task_id from the envelope.
 *  - traceId echoes trace_id from the envelope.
 *  - goal echoes goal from the envelope.
 *  - attachedSessionId is set from the supplied parameter (not the envelope).
 *  - sourceDeviceId echoes source_device_id; null when absent.
 *  - resolvedPosture is normalised via SourceRuntimePosture.fromValue.
 *  - checkpoint echoes the envelope checkpoint; null when absent.
 *  - constraints echo the envelope constraints.
 *  - receivedAtMs uses the supplied timestamp.
 *
 * ### Posture derived helpers
 *  - isSourceJoinRuntime is true only for JOIN_RUNTIME posture.
 *  - isSourceControlOnly is true for CONTROL_ONLY and null/unknown postures.
 *
 * ### [DelegatedRuntimeUnit.toMetadataMap]
 *  - Contains all required keys: unit_id, task_id, trace_id, attached_session_id,
 *    resolved_posture, received_at_ms.
 *  - source_device_id is absent when null.
 *  - checkpoint key is absent when null.
 *  - Values match the unit fields exactly.
 *
 * ### Metadata key constants
 *  - KEY_UNIT_ID is "delegated_unit_id".
 *  - KEY_TASK_ID is "delegated_unit_task_id".
 *  - KEY_TRACE_ID is "delegated_unit_trace_id".
 *  - KEY_ATTACHED_SESSION_ID is "delegated_unit_attached_session_id".
 *  - KEY_RESOLVED_POSTURE is "delegated_unit_resolved_posture".
 *  - KEY_RECEIVED_AT_MS is "delegated_unit_received_at_ms".
 *  - KEY_SOURCE_DEVICE_ID is "delegated_unit_source_device_id".
 *  - KEY_CHECKPOINT is "delegated_unit_checkpoint".
 *
 * ### Immutability
 *  - DelegatedRuntimeUnit is a data class; copy() leaves the original unchanged.
 */
class DelegatedRuntimeUnitTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun minimalEnvelope(
        takeoverId: String = "to-1",
        taskId: String = "t-1",
        traceId: String = "tr-1",
        goal: String = "open gallery",
        sourceDeviceId: String? = null,
        posture: String? = null,
        checkpoint: String? = null,
        constraints: List<String> = emptyList()
    ) = TakeoverRequestEnvelope(
        takeover_id = takeoverId,
        task_id = taskId,
        trace_id = traceId,
        goal = goal,
        source_device_id = sourceDeviceId,
        source_runtime_posture = posture,
        checkpoint = checkpoint,
        constraints = constraints
    )

    private fun fromEnvelope(
        envelope: TakeoverRequestEnvelope = minimalEnvelope(),
        sessionId: String = "session-abc",
        receivedAtMs: Long = 1_000L
    ) = DelegatedRuntimeUnit.fromEnvelope(
        envelope = envelope,
        attachedSessionId = sessionId,
        receivedAtMs = receivedAtMs
    )

    // ── fromEnvelope: field mapping ───────────────────────────────────────────

    @Test
    fun `unitId echoes takeover_id from envelope`() {
        val unit = fromEnvelope(minimalEnvelope(takeoverId = "my-takeover-99"))
        assertEquals("unitId must echo the envelope takeover_id", "my-takeover-99", unit.unitId)
    }

    @Test
    fun `taskId echoes task_id from envelope`() {
        val unit = fromEnvelope(minimalEnvelope(taskId = "task-42"))
        assertEquals("taskId must echo the envelope task_id", "task-42", unit.taskId)
    }

    @Test
    fun `traceId echoes trace_id from envelope`() {
        val unit = fromEnvelope(minimalEnvelope(traceId = "trace-xyz"))
        assertEquals("traceId must echo the envelope trace_id", "trace-xyz", unit.traceId)
    }

    @Test
    fun `goal echoes goal from envelope`() {
        val unit = fromEnvelope(minimalEnvelope(goal = "launch camera"))
        assertEquals("goal must echo the envelope goal", "launch camera", unit.goal)
    }

    @Test
    fun `attachedSessionId is set from the supplied parameter not the envelope`() {
        val unit = fromEnvelope(sessionId = "explicit-session-id-7")
        assertEquals(
            "attachedSessionId must come from the caller-supplied session parameter",
            "explicit-session-id-7",
            unit.attachedSessionId
        )
    }

    @Test
    fun `sourceDeviceId echoes source_device_id from envelope`() {
        val unit = fromEnvelope(minimalEnvelope(sourceDeviceId = "pc-host-1"))
        assertEquals("sourceDeviceId must echo the envelope source_device_id", "pc-host-1", unit.sourceDeviceId)
    }

    @Test
    fun `sourceDeviceId is null when envelope source_device_id is absent`() {
        val unit = fromEnvelope(minimalEnvelope(sourceDeviceId = null))
        assertNull("sourceDeviceId must be null when absent from the envelope", unit.sourceDeviceId)
    }

    @Test
    fun `resolvedPosture is JOIN_RUNTIME when envelope posture is JOIN_RUNTIME`() {
        val unit = fromEnvelope(minimalEnvelope(posture = SourceRuntimePosture.JOIN_RUNTIME))
        assertEquals(
            "resolvedPosture must be JOIN_RUNTIME when the envelope posture is JOIN_RUNTIME",
            SourceRuntimePosture.JOIN_RUNTIME,
            unit.resolvedPosture
        )
    }

    @Test
    fun `resolvedPosture is CONTROL_ONLY when envelope posture is CONTROL_ONLY`() {
        val unit = fromEnvelope(minimalEnvelope(posture = SourceRuntimePosture.CONTROL_ONLY))
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, unit.resolvedPosture)
    }

    @Test
    fun `resolvedPosture defaults to CONTROL_ONLY when envelope posture is null`() {
        val unit = fromEnvelope(minimalEnvelope(posture = null))
        assertEquals(
            "null posture must resolve to the safe default CONTROL_ONLY",
            SourceRuntimePosture.CONTROL_ONLY,
            unit.resolvedPosture
        )
    }

    @Test
    fun `resolvedPosture defaults to CONTROL_ONLY for unknown posture value`() {
        val unit = fromEnvelope(minimalEnvelope(posture = "unknown_value"))
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, unit.resolvedPosture)
    }

    @Test
    fun `checkpoint echoes envelope checkpoint when present`() {
        val unit = fromEnvelope(minimalEnvelope(checkpoint = "step-3-completed"))
        assertEquals("step-3-completed", unit.checkpoint)
    }

    @Test
    fun `checkpoint is null when envelope checkpoint is absent`() {
        val unit = fromEnvelope(minimalEnvelope(checkpoint = null))
        assertNull(unit.checkpoint)
    }

    @Test
    fun `constraints echo envelope constraints`() {
        val unit = fromEnvelope(minimalEnvelope(constraints = listOf("no audio", "landscape only")))
        assertEquals(listOf("no audio", "landscape only"), unit.constraints)
    }

    @Test
    fun `constraints is empty list when envelope has no constraints`() {
        val unit = fromEnvelope(minimalEnvelope(constraints = emptyList()))
        assertTrue(unit.constraints.isEmpty())
    }

    @Test
    fun `receivedAtMs uses the supplied timestamp`() {
        val unit = fromEnvelope(receivedAtMs = 12345L)
        assertEquals("receivedAtMs must use the caller-supplied timestamp", 12345L, unit.receivedAtMs)
    }

    // ── Posture derived helpers ───────────────────────────────────────────────

    @Test
    fun `isSourceJoinRuntime is true when resolvedPosture is JOIN_RUNTIME`() {
        val unit = fromEnvelope(minimalEnvelope(posture = SourceRuntimePosture.JOIN_RUNTIME))
        assertTrue("isSourceJoinRuntime must be true for JOIN_RUNTIME posture", unit.isSourceJoinRuntime)
    }

    @Test
    fun `isSourceJoinRuntime is false when resolvedPosture is CONTROL_ONLY`() {
        val unit = fromEnvelope(minimalEnvelope(posture = SourceRuntimePosture.CONTROL_ONLY))
        assertFalse("isSourceJoinRuntime must be false for CONTROL_ONLY posture", unit.isSourceJoinRuntime)
    }

    @Test
    fun `isSourceControlOnly is true when resolvedPosture is CONTROL_ONLY`() {
        val unit = fromEnvelope(minimalEnvelope(posture = SourceRuntimePosture.CONTROL_ONLY))
        assertTrue(unit.isSourceControlOnly)
    }

    @Test
    fun `isSourceControlOnly is true when posture is null (default)`() {
        val unit = fromEnvelope(minimalEnvelope(posture = null))
        assertTrue("null posture must be treated as CONTROL_ONLY", unit.isSourceControlOnly)
    }

    @Test
    fun `isSourceControlOnly is false when resolvedPosture is JOIN_RUNTIME`() {
        val unit = fromEnvelope(minimalEnvelope(posture = SourceRuntimePosture.JOIN_RUNTIME))
        assertFalse(unit.isSourceControlOnly)
    }

    @Test
    fun `isSourceJoinRuntime and isSourceControlOnly are mutually exclusive`() {
        val joinUnit = fromEnvelope(minimalEnvelope(posture = SourceRuntimePosture.JOIN_RUNTIME))
        val ctrlUnit = fromEnvelope(minimalEnvelope(posture = SourceRuntimePosture.CONTROL_ONLY))
        assertTrue(joinUnit.isSourceJoinRuntime xor joinUnit.isSourceControlOnly)
        assertTrue(ctrlUnit.isSourceJoinRuntime xor ctrlUnit.isSourceControlOnly)
    }

    // ── toMetadataMap ─────────────────────────────────────────────────────────

    @Test
    fun `toMetadataMap contains unit_id key`() {
        val unit = fromEnvelope(minimalEnvelope(takeoverId = "to-map-1"))
        val map = unit.toMetadataMap()
        assertTrue("toMetadataMap must contain KEY_UNIT_ID", map.containsKey(DelegatedRuntimeUnit.KEY_UNIT_ID))
        assertEquals("to-map-1", map[DelegatedRuntimeUnit.KEY_UNIT_ID])
    }

    @Test
    fun `toMetadataMap contains task_id key`() {
        val unit = fromEnvelope(minimalEnvelope(taskId = "t-map-2"))
        val map = unit.toMetadataMap()
        assertTrue(map.containsKey(DelegatedRuntimeUnit.KEY_TASK_ID))
        assertEquals("t-map-2", map[DelegatedRuntimeUnit.KEY_TASK_ID])
    }

    @Test
    fun `toMetadataMap contains trace_id key`() {
        val unit = fromEnvelope(minimalEnvelope(traceId = "tr-map-3"))
        val map = unit.toMetadataMap()
        assertTrue(map.containsKey(DelegatedRuntimeUnit.KEY_TRACE_ID))
        assertEquals("tr-map-3", map[DelegatedRuntimeUnit.KEY_TRACE_ID])
    }

    @Test
    fun `toMetadataMap contains attached_session_id key matching session`() {
        val unit = fromEnvelope(sessionId = "sess-map-4")
        val map = unit.toMetadataMap()
        assertTrue(map.containsKey(DelegatedRuntimeUnit.KEY_ATTACHED_SESSION_ID))
        assertEquals("sess-map-4", map[DelegatedRuntimeUnit.KEY_ATTACHED_SESSION_ID])
    }

    @Test
    fun `toMetadataMap contains resolved_posture key`() {
        val unit = fromEnvelope(minimalEnvelope(posture = SourceRuntimePosture.JOIN_RUNTIME))
        val map = unit.toMetadataMap()
        assertTrue(map.containsKey(DelegatedRuntimeUnit.KEY_RESOLVED_POSTURE))
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, map[DelegatedRuntimeUnit.KEY_RESOLVED_POSTURE])
    }

    @Test
    fun `toMetadataMap contains received_at_ms key`() {
        val unit = fromEnvelope(receivedAtMs = 99_000L)
        val map = unit.toMetadataMap()
        assertTrue(map.containsKey(DelegatedRuntimeUnit.KEY_RECEIVED_AT_MS))
        assertEquals(99_000L, map[DelegatedRuntimeUnit.KEY_RECEIVED_AT_MS])
    }

    @Test
    fun `toMetadataMap source_device_id is absent when null`() {
        val unit = fromEnvelope(minimalEnvelope(sourceDeviceId = null))
        val map = unit.toMetadataMap()
        assertFalse(
            "KEY_SOURCE_DEVICE_ID must be absent when sourceDeviceId is null",
            map.containsKey(DelegatedRuntimeUnit.KEY_SOURCE_DEVICE_ID)
        )
    }

    @Test
    fun `toMetadataMap source_device_id is present when non-null`() {
        val unit = fromEnvelope(minimalEnvelope(sourceDeviceId = "host-pc"))
        val map = unit.toMetadataMap()
        assertTrue(map.containsKey(DelegatedRuntimeUnit.KEY_SOURCE_DEVICE_ID))
        assertEquals("host-pc", map[DelegatedRuntimeUnit.KEY_SOURCE_DEVICE_ID])
    }

    @Test
    fun `toMetadataMap checkpoint is absent when null`() {
        val unit = fromEnvelope(minimalEnvelope(checkpoint = null))
        val map = unit.toMetadataMap()
        assertFalse(
            "KEY_CHECKPOINT must be absent when checkpoint is null",
            map.containsKey(DelegatedRuntimeUnit.KEY_CHECKPOINT)
        )
    }

    @Test
    fun `toMetadataMap checkpoint is present when non-null`() {
        val unit = fromEnvelope(minimalEnvelope(checkpoint = "step-7"))
        val map = unit.toMetadataMap()
        assertTrue(map.containsKey(DelegatedRuntimeUnit.KEY_CHECKPOINT))
        assertEquals("step-7", map[DelegatedRuntimeUnit.KEY_CHECKPOINT])
    }

    // ── Metadata key constant values ──────────────────────────────────────────

    @Test
    fun `KEY_UNIT_ID is delegated_unit_id`() {
        assertEquals("delegated_unit_id", DelegatedRuntimeUnit.KEY_UNIT_ID)
    }

    @Test
    fun `KEY_TASK_ID is delegated_unit_task_id`() {
        assertEquals("delegated_unit_task_id", DelegatedRuntimeUnit.KEY_TASK_ID)
    }

    @Test
    fun `KEY_TRACE_ID is delegated_unit_trace_id`() {
        assertEquals("delegated_unit_trace_id", DelegatedRuntimeUnit.KEY_TRACE_ID)
    }

    @Test
    fun `KEY_ATTACHED_SESSION_ID is delegated_unit_attached_session_id`() {
        assertEquals("delegated_unit_attached_session_id", DelegatedRuntimeUnit.KEY_ATTACHED_SESSION_ID)
    }

    @Test
    fun `KEY_RESOLVED_POSTURE is delegated_unit_resolved_posture`() {
        assertEquals("delegated_unit_resolved_posture", DelegatedRuntimeUnit.KEY_RESOLVED_POSTURE)
    }

    @Test
    fun `KEY_RECEIVED_AT_MS is delegated_unit_received_at_ms`() {
        assertEquals("delegated_unit_received_at_ms", DelegatedRuntimeUnit.KEY_RECEIVED_AT_MS)
    }

    @Test
    fun `KEY_SOURCE_DEVICE_ID is delegated_unit_source_device_id`() {
        assertEquals("delegated_unit_source_device_id", DelegatedRuntimeUnit.KEY_SOURCE_DEVICE_ID)
    }

    @Test
    fun `KEY_CHECKPOINT is delegated_unit_checkpoint`() {
        assertEquals("delegated_unit_checkpoint", DelegatedRuntimeUnit.KEY_CHECKPOINT)
    }

    // ── Immutability ──────────────────────────────────────────────────────────

    @Test
    fun `copy leaves original unchanged`() {
        val original = fromEnvelope(minimalEnvelope(goal = "open contacts"))
        val copy = original.copy(goal = "open gallery")
        assertEquals(
            "copy() must not mutate the original",
            "open contacts",
            original.goal
        )
        assertEquals("open gallery", copy.goal)
    }
}
