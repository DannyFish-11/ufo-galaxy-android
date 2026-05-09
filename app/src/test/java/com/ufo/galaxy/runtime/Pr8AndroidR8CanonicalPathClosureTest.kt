package com.ufo.galaxy.runtime

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import com.ufo.galaxy.session.DurableParticipantIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-8Android — Regression-protection tests for the Android-side R8 canonical path closure.
 *
 * Validates the two R8 gap closures introduced by this PR:
 *  1. [LocalExecutionModeGate] wiring — `execution_mode_state` is now a first-class field in
 *     [DeviceStateSnapshotPayload] and [DeviceExecutionEventPayload].
 *  2. [DurableParticipantIdentity] wiring — `durable_participant_id` and
 *     `participant_identity_freshness` are now first-class fields in [DeviceStateSnapshotPayload].
 *
 * ## Test matrix
 *
 * ### DeviceStateSnapshotPayload — new fields present
 *  - execution_mode_state field exists and defaults to null
 *  - durable_participant_id field exists and defaults to null
 *  - participant_identity_freshness field exists and defaults to null
 *  - execution_mode_state can be set to LocalExecutionModeGate wire values
 *  - durable_participant_id can be set to a UUID string
 *  - participant_identity_freshness can be set to IdentityFreshness wire values
 *
 * ### DeviceExecutionEventPayload — new field present
 *  - execution_mode_state field exists and defaults to null
 *  - execution_mode_state can be set to LocalExecutionModeGate wire values
 *
 * ### AndroidR8CanonicalPathClosureContract — invariants
 *  - all CLOSURE_INVARIANTS are true
 *  - INTRODUCED_PR is PR-8Android
 *  - WIRED_SURFACES contains LocalExecutionModeGate and DurableParticipantIdentity
 *
 * ### LocalExecutionModeGate — decide() → wire values emitted in snapshot
 *  - local_only when crossDeviceEnabled=false
 *  - transitioning when crossDeviceEnabled=true and wsConnected=false
 *  - cross_device_active when crossDeviceEnabled=true and wsConnected=true and not degraded
 *  - cross_device_degraded when crossDeviceEnabled=true and wsConnected=true and degraded
 *  - inactive when runtimeActive=false
 *
 * ### DurableParticipantIdentity — freshness wire values
 *  - createFresh() yields "fresh" wireValue
 *  - createRecovered() yields "recovered" wireValue
 *  - createFromGap() yields "stale" wireValue when offline gap exceeds threshold
 *  - createFromGap() yields "recovered" wireValue when offline gap is within threshold
 *
 * ### Wire key alignment — field names match gate/identity key constants
 *  - execution_mode_state matches LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE
 *  - durable_participant_id matches DurableParticipantIdentity.KEY_PARTICIPANT_ID
 *  - participant_identity_freshness matches DurableParticipantIdentity.KEY_IDENTITY_FRESHNESS
 *
 * ### StabilizationBaseline — new closure surfaces registered
 *  - android-r8-canonical-path-closure is in the baseline
 *
 * ### InMemoryAppSettings — durableParticipantId field available
 *  - durableParticipantId field exists and defaults to blank
 *  - durableParticipantId can be set and read back
 */
class Pr8AndroidR8CanonicalPathClosureTest {

    // ── Minimal DeviceStateSnapshotPayload factory ────────────────────────────

    private fun minimalSnapshot(
        executionModeState: String? = null,
        durableParticipantId: String? = null,
        participantIdentityFreshness: String? = null
    ) = DeviceStateSnapshotPayload(
        device_id = "test-device",
        llama_cpp_available = false,
        ncnn_available = false,
        active_runtime_type = "CENTER",
        model_ready = false,
        accessibility_ready = false,
        overlay_ready = false,
        local_loop_ready = false,
        model_id = null,
        runtime_type = null,
        checksum_ok = null,
        mobilevlm_present = false,
        mobilevlm_checksum_ok = false,
        seeclick_present = false,
        pending_first_download = true,
        warmup_result = "unavailable",
        offline_queue_depth = 0,
        current_fallback_tier = null,
        execution_mode_state = executionModeState,
        durable_participant_id = durableParticipantId,
        participant_identity_freshness = participantIdentityFreshness
    )

    private fun minimalEvent(executionModeState: String? = null) = DeviceExecutionEventPayload(
        flow_id = "flow-1",
        task_id = "task-1",
        phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
        execution_mode_state = executionModeState
    )

    // ── DeviceStateSnapshotPayload — new fields present ───────────────────────

    @Test
    fun `execution_mode_state field exists and defaults to null`() {
        val snapshot = minimalSnapshot()
        assertNull(snapshot.execution_mode_state)
    }

    @Test
    fun `durable_participant_id field exists and defaults to null`() {
        val snapshot = minimalSnapshot()
        assertNull(snapshot.durable_participant_id)
    }

    @Test
    fun `participant_identity_freshness field exists and defaults to null`() {
        val snapshot = minimalSnapshot()
        assertNull(snapshot.participant_identity_freshness)
    }

    @Test
    fun `execution_mode_state can hold LocalExecutionModeGate wire values`() {
        for (state in LocalExecutionModeGate.ExecutionModeState.entries) {
            val snapshot = minimalSnapshot(executionModeState = state.wireValue)
            assertEquals(state.wireValue, snapshot.execution_mode_state)
        }
    }

    @Test
    fun `durable_participant_id can be set to a UUID string`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val snapshot = minimalSnapshot(durableParticipantId = uuid)
        assertEquals(uuid, snapshot.durable_participant_id)
    }

    @Test
    fun `participant_identity_freshness can hold IdentityFreshness wire values`() {
        for (freshness in DurableParticipantIdentity.IdentityFreshness.entries) {
            val snapshot = minimalSnapshot(participantIdentityFreshness = freshness.wireValue)
            assertEquals(freshness.wireValue, snapshot.participant_identity_freshness)
        }
    }

    // ── DeviceExecutionEventPayload — new field present ───────────────────────

    @Test
    fun `execution_mode_state field in execution event exists and defaults to null`() {
        val event = minimalEvent()
        assertNull(event.execution_mode_state)
    }

    @Test
    fun `execution_mode_state in execution event can hold LocalExecutionModeGate wire values`() {
        for (state in LocalExecutionModeGate.ExecutionModeState.entries) {
            val event = minimalEvent(executionModeState = state.wireValue)
            assertEquals(state.wireValue, event.execution_mode_state)
        }
    }

    // ── AndroidR8CanonicalPathClosureContract — invariants ────────────────────

    @Test
    fun `all CLOSURE_INVARIANTS are true`() {
        val failures = AndroidR8CanonicalPathClosureContract.CLOSURE_INVARIANTS
            .filterValues { !it }
        assertTrue(
            "CLOSURE_INVARIANTS failures: ${failures.keys}",
            failures.isEmpty()
        )
    }

    @Test
    fun `INTRODUCED_PR is PR-8Android`() {
        assertEquals("PR-8Android", AndroidR8CanonicalPathClosureContract.INTRODUCED_PR)
    }

    @Test
    fun `WIRED_SURFACES contains LocalExecutionModeGate`() {
        assertTrue(
            AndroidR8CanonicalPathClosureContract.WIRED_SURFACES.contains(
                "com.ufo.galaxy.runtime.LocalExecutionModeGate"
            )
        )
    }

    @Test
    fun `WIRED_SURFACES contains DurableParticipantIdentity`() {
        assertTrue(
            AndroidR8CanonicalPathClosureContract.WIRED_SURFACES.contains(
                "com.ufo.galaxy.session.DurableParticipantIdentity"
            )
        )
    }

    // ── LocalExecutionModeGate decide() → execution_mode_state wire values ────

    @Test
    fun `decide returns LOCAL_ONLY when crossDeviceEnabled is false`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = false,
            wsConnected = false,
            capabilityDegraded = false
        )
        assertEquals("local_only", decision.state.wireValue)
        val snapshot = minimalSnapshot(executionModeState = decision.state.wireValue)
        assertEquals("local_only", snapshot.execution_mode_state)
    }

    @Test
    fun `decide returns TRANSITIONING when crossDeviceEnabled and not wsConnected`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = false,
            capabilityDegraded = false
        )
        assertEquals("transitioning", decision.state.wireValue)
        val snapshot = minimalSnapshot(executionModeState = decision.state.wireValue)
        assertEquals("transitioning", snapshot.execution_mode_state)
    }

    @Test
    fun `decide returns CROSS_DEVICE_ACTIVE when crossDeviceEnabled and wsConnected and not degraded`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = false
        )
        assertEquals("cross_device_active", decision.state.wireValue)
        val snapshot = minimalSnapshot(executionModeState = decision.state.wireValue)
        assertEquals("cross_device_active", snapshot.execution_mode_state)
    }

    @Test
    fun `decide returns CROSS_DEVICE_DEGRADED when crossDeviceEnabled and wsConnected and degraded`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = true,
            degradationReasons = listOf("inference_unavailable")
        )
        assertEquals("cross_device_degraded", decision.state.wireValue)
        val snapshot = minimalSnapshot(executionModeState = decision.state.wireValue)
        assertEquals("cross_device_degraded", snapshot.execution_mode_state)
    }

    @Test
    fun `decide returns INACTIVE when runtimeActive is false`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = false,
            runtimeActive = false
        )
        assertEquals("inactive", decision.state.wireValue)
        val snapshot = minimalSnapshot(executionModeState = decision.state.wireValue)
        assertEquals("inactive", snapshot.execution_mode_state)
    }

    @Test
    fun `execution event carries execution_mode_state matching snapshot state`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = false
        )
        val snapshot = minimalSnapshot(executionModeState = decision.state.wireValue)
        val event = minimalEvent(executionModeState = decision.state.wireValue)
        assertEquals(snapshot.execution_mode_state, event.execution_mode_state)
    }

    // ── DurableParticipantIdentity — freshness wire values ────────────────────

    @Test
    fun `createFresh yields fresh wireValue`() {
        val identity = DurableParticipantIdentity.createFresh(
            deviceId = "device-1",
            deviceRole = "phone"
        )
        assertEquals("fresh", identity.freshness.wireValue)
        val snapshot = minimalSnapshot(participantIdentityFreshness = identity.freshness.wireValue)
        assertEquals("fresh", snapshot.participant_identity_freshness)
    }

    @Test
    fun `createRecovered yields recovered wireValue`() {
        val record = DurableSessionContinuityRecord(
            durableSessionId = "session-1",
            sessionContinuityEpoch = 2,
            activationEpochMs = System.currentTimeMillis() - 60_000L,
            activationSource = "reconnect"
        )
        val identity = DurableParticipantIdentity.createRecovered(
            existingParticipantId = "participant-1",
            deviceId = "device-1",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = System.currentTimeMillis() - 30_000L
        )
        assertEquals("recovered", identity.freshness.wireValue)
        val snapshot = minimalSnapshot(participantIdentityFreshness = identity.freshness.wireValue)
        assertEquals("recovered", snapshot.participant_identity_freshness)
    }

    @Test
    fun `createFromGap yields stale when offline gap exceeds threshold`() {
        val now = System.currentTimeMillis()
        val staleGap = DurableParticipantIdentity.STALE_THRESHOLD_MS + 1_000L
        val record = DurableSessionContinuityRecord(
            durableSessionId = "session-1",
            sessionContinuityEpoch = 1,
            activationEpochMs = now,
            activationSource = "test"
        )
        val identity = DurableParticipantIdentity.createFromGap(
            existingParticipantId = "participant-1",
            deviceId = "device-1",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = now - staleGap
        )
        assertEquals("stale", identity.freshness.wireValue)
        val snapshot = minimalSnapshot(participantIdentityFreshness = identity.freshness.wireValue)
        assertEquals("stale", snapshot.participant_identity_freshness)
    }

    @Test
    fun `createFromGap yields recovered when offline gap is within threshold`() {
        val now = System.currentTimeMillis()
        val recentGap = 60_000L // 1 minute — well within 24-hour threshold
        val record = DurableSessionContinuityRecord(
            durableSessionId = "session-1",
            sessionContinuityEpoch = 1,
            activationEpochMs = now,
            activationSource = "test"
        )
        val identity = DurableParticipantIdentity.createFromGap(
            existingParticipantId = "participant-1",
            deviceId = "device-1",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = now - recentGap
        )
        assertEquals("recovered", identity.freshness.wireValue)
    }

    @Test
    fun `createFromGap yields fresh when no existing participant ID`() {
        val record = DurableSessionContinuityRecord(
            durableSessionId = "session-1",
            sessionContinuityEpoch = 0,
            activationEpochMs = System.currentTimeMillis(),
            activationSource = "first_activation"
        )
        val identity = DurableParticipantIdentity.createFromGap(
            existingParticipantId = null,
            deviceId = "device-1",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = System.currentTimeMillis()
        )
        assertEquals("fresh", identity.freshness.wireValue)
    }

    // ── Wire key alignment ────────────────────────────────────────────────────

    @Test
    fun `execution_mode_state field name matches LocalExecutionModeGate wire key constant`() {
        assertEquals("execution_mode_state", LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE)
    }

    @Test
    fun `durable_participant_id field name matches DurableParticipantIdentity wire key constant`() {
        assertEquals("durable_participant_id", DurableParticipantIdentity.KEY_PARTICIPANT_ID)
    }

    @Test
    fun `participant_identity_freshness field name matches DurableParticipantIdentity wire key constant`() {
        assertEquals("participant_identity_freshness", DurableParticipantIdentity.KEY_IDENTITY_FRESHNESS)
    }

    // ── StabilizationBaseline — closure surfaces registered ──────────────────

    @Test
    fun `android-r8-canonical-path-closure is registered in StabilizationBaseline`() {
        assertTrue(StabilizationBaseline.isRegistered("android-r8-canonical-path-closure"))
    }

    @Test
    fun `android-r8-canonical-path-closure is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-r8-canonical-path-closure")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `android-r8-canonical-path-closure is in extendableSurfaceIds`() {
        assertTrue(StabilizationBaseline.extendableSurfaceIds.contains("android-r8-canonical-path-closure"))
    }

    // ── InMemoryAppSettings — durableParticipantId field ─────────────────────

    @Test
    fun `InMemoryAppSettings durableParticipantId defaults to blank`() {
        val settings = InMemoryAppSettings()
        assertEquals("", settings.durableParticipantId)
    }

    @Test
    fun `InMemoryAppSettings durableParticipantId can be set and read back`() {
        val settings = InMemoryAppSettings()
        val id = "550e8400-e29b-41d4-a716-446655440000"
        settings.durableParticipantId = id
        assertEquals(id, settings.durableParticipantId)
    }

    // ── End-to-end scenario: snapshot with all R8 fields populated ────────────

    @Test
    fun `snapshot with all R8 fields populated reflects canonical emission contract`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = true,
            capabilityDegraded = false
        )
        val record = DurableSessionContinuityRecord(
            durableSessionId = "session-42",
            sessionContinuityEpoch = 3,
            activationEpochMs = System.currentTimeMillis() - 10_000L,
            activationSource = "user_activation"
        )
        val identity = DurableParticipantIdentity.createFromGap(
            existingParticipantId = "participant-stable-id",
            deviceId = "device-1",
            deviceRole = "phone",
            continuityRecord = record,
            lastActiveEpochMs = System.currentTimeMillis() - 5_000L
        )

        val snapshot = minimalSnapshot(
            executionModeState = decision.state.wireValue,
            durableParticipantId = identity.participantId,
            participantIdentityFreshness = identity.freshness.wireValue
        )

        // execution_mode_state: V2 android_runtime_transition_reducer.py reads this
        assertEquals("cross_device_active", snapshot.execution_mode_state)

        // durable_participant_id: V2 android_device_state_store.py uses this as primary key
        assertEquals("participant-stable-id", snapshot.durable_participant_id)

        // participant_identity_freshness: V2 unified_governance_semantics.py reads this
        assertEquals("recovered", snapshot.participant_identity_freshness)
    }

    @Test
    fun `snapshot transitioning state emits transitioning execution_mode_state`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = false,
            capabilityDegraded = false
        )
        val snapshot = minimalSnapshot(executionModeState = decision.state.wireValue)
        assertEquals("transitioning", snapshot.execution_mode_state)
        // acceptsCrossDeviceTasks is false → V2 must not dispatch tasks
        assertFalse(decision.state.acceptsCrossDeviceTasks)
        // v2GovernanceActive is false → V2 governance is inactive during transition
        assertFalse(decision.state.v2GovernanceActive)
    }

    @Test
    fun `decide isHoldState is true for transitioning execution mode`() {
        val decision = LocalExecutionModeGate.decide(
            crossDeviceEnabled = true,
            wsConnected = false,
            capabilityDegraded = false
        )
        assertTrue(decision.isHoldState)
    }
}
