package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr1AndroidAuthoritativeParticipationTruthTest {

    private val gson = Gson()

    @Test
    fun `derive returns local_only when cross-device is disabled`() {
        val state = AndroidAuthoritativeParticipationTruth.derive(
            baseInput(crossDeviceEnabled = false)
        )
        assertEquals(AndroidAuthoritativeParticipationTruth.State.LOCAL_ONLY, state)
    }

    @Test
    fun `derive returns control_only while registration is in flight`() {
        val state = AndroidAuthoritativeParticipationTruth.derive(
            baseInput(registrationInFlight = true)
        )
        assertEquals(AndroidAuthoritativeParticipationTruth.State.CONTROL_ONLY, state)
    }

    @Test
    fun `derive returns cross_device_capable when capability visibility is absent`() {
        val state = AndroidAuthoritativeParticipationTruth.derive(
            baseInput(capabilityVisible = false)
        )
        assertEquals(AndroidAuthoritativeParticipationTruth.State.CROSS_DEVICE_CAPABLE, state)
    }

    @Test
    fun `derive returns cross_device_enabled when runtime session is unavailable`() {
        val state = AndroidAuthoritativeParticipationTruth.derive(
            baseInput(runtimeSessionAvailable = false)
        )
        assertEquals(AndroidAuthoritativeParticipationTruth.State.CROSS_DEVICE_ENABLED, state)
    }

    @Test
    fun `derive returns fully_attached when readiness is not satisfied`() {
        val state = AndroidAuthoritativeParticipationTruth.derive(
            baseInput(readinessSatisfied = false, dispatchEligible = false)
        )
        assertEquals(AndroidAuthoritativeParticipationTruth.State.FULLY_ATTACHED, state)
    }

    @Test
    fun `derive returns fully_attached when continuous ingress backbone is absent`() {
        val state = AndroidAuthoritativeParticipationTruth.derive(
            baseInput(continuousIngressReady = false)
        )
        assertEquals(AndroidAuthoritativeParticipationTruth.State.FULLY_ATTACHED, state)
    }

    @Test
    fun `derive returns dispatch_eligible when ready and eligible but no active distributed activity`() {
        val state = AndroidAuthoritativeParticipationTruth.derive(
            baseInput(distributedRuntimeActivity = false)
        )
        assertEquals(AndroidAuthoritativeParticipationTruth.State.DISPATCH_ELIGIBLE, state)
    }

    @Test
    fun `derive returns distributed_participant when activity is distributed and eligible`() {
        val state = AndroidAuthoritativeParticipationTruth.derive(
            baseInput(distributedRuntimeActivity = true)
        )
        assertEquals(AndroidAuthoritativeParticipationTruth.State.DISTRIBUTED_PARTICIPANT, state)
    }

    @Test
    fun `participationTierFor maps canonical high-confidence states`() {
        assertEquals(
            AndroidAuthoritativeParticipationTruth.ParticipationTier.PRE_ATTACH,
            AndroidAuthoritativeParticipationTruth
                .participationTierFor(AndroidAuthoritativeParticipationTruth.State.CROSS_DEVICE_ENABLED)
        )
        assertEquals(
            AndroidAuthoritativeParticipationTruth.ParticipationTier.FULLY_ATTACHED,
            AndroidAuthoritativeParticipationTruth
                .participationTierFor(AndroidAuthoritativeParticipationTruth.State.FULLY_ATTACHED)
        )
        assertEquals(
            AndroidAuthoritativeParticipationTruth.ParticipationTier.DISTRIBUTED_PARTICIPANT,
            AndroidAuthoritativeParticipationTruth
                .participationTierFor(AndroidAuthoritativeParticipationTruth.State.DISTRIBUTED_PARTICIPANT)
        )
    }

    @Test
    fun `tracker records ordered transition history with trigger`() {
        val tracker = AndroidAuthoritativeParticipationTruth.Tracker(historyLimit = 8)
        tracker.evaluate(baseInput(crossDeviceEnabled = false), timestampMs = 10L)
        val snapshot = tracker.evaluate(
            baseInput(
                crossDeviceEnabled = true,
                wsConnected = true,
                capabilityVisible = true,
                runtimeSessionAvailable = true,
                fullyAttached = true,
                readinessSatisfied = true,
                dispatchEligible = true,
                distributedRuntimeActivity = true
            ),
            timestampMs = 20L
        )

        assertEquals(AndroidAuthoritativeParticipationTruth.State.DISTRIBUTED_PARTICIPANT, snapshot.state)
        assertEquals("cross_device_enabled", snapshot.lastTransitionTrigger)
        assertEquals(1L, snapshot.transitionSequence)
        assertTrue(snapshot.transitionHistory.isNotEmpty())
        assertTrue(snapshot.transitionHistoryWire.first().contains("local_only->distributed_participant"))
    }

    @Test
    fun `runtime truth map includes authoritative participation state`() {
        val descriptor = RuntimeHostDescriptor(
            hostId = "host-1",
            deviceId = "device-1",
            deviceRole = "phone",
            formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        val truth = AndroidParticipantRuntimeTruth.from(
            descriptor = descriptor,
            authoritativeParticipationState = AndroidAuthoritativeParticipationTruth.State.DISPATCH_ELIGIBLE.wireValue,
            authoritativeParticipationTransitionSequence = 7L,
            authoritativeParticipationTransitionTrigger = "readiness_satisfied",
            authoritativeParticipationTransitionHistory =
                listOf("7|fully_attached->dispatch_eligible|readiness_satisfied|171111111")
        )
        val map = truth.toMap()
        assertEquals(
            AndroidAuthoritativeParticipationTruth.State.DISPATCH_ELIGIBLE.wireValue,
            map[AndroidParticipantRuntimeTruth.KEY_AUTHORITATIVE_PARTICIPATION_STATE]
        )
        assertEquals(
            7L,
            map[AndroidParticipantRuntimeTruth.KEY_AUTHORITATIVE_PARTICIPATION_TRANSITION_SEQUENCE]
        )
        assertEquals(
            "readiness_satisfied",
            map[AndroidParticipantRuntimeTruth.KEY_AUTHORITATIVE_PARTICIPATION_TRANSITION_TRIGGER]
        )
    }

    @Test
    fun `device state snapshot payload serializes authoritative participation field`() {
        val payload = DeviceStateSnapshotPayload(
            device_id = "d1",
            llama_cpp_available = true,
            ncnn_available = true,
            active_runtime_type = "CENTER",
            model_ready = true,
            accessibility_ready = true,
            overlay_ready = true,
            local_loop_ready = true,
            model_id = "m1",
            runtime_type = "CENTER",
            checksum_ok = true,
            mobilevlm_present = true,
            mobilevlm_checksum_ok = true,
            seeclick_present = true,
            pending_first_download = false,
            warmup_result = "ok",
            offline_queue_depth = 0,
            current_fallback_tier = "none",
            authoritative_participation_state = AndroidAuthoritativeParticipationTruth.State.FULLY_ATTACHED.wireValue,
            participation_tier = AndroidAuthoritativeParticipationTruth
                .ParticipationTier.FULLY_ATTACHED
                .wireValue,
            authoritative_participation_transition_sequence = 5L,
            authoritative_participation_transition_trigger = "session_established"
        )
        val json = gson.toJson(payload)
        assertTrue(json.contains("\"authoritative_participation_state\":\"fully_attached\""))
        assertTrue(json.contains("\"participation_tier\":\"fully_attached\""))
        assertTrue(json.contains("\"authoritative_participation_transition_sequence\":5"))
    }

    @Test
    fun `device execution event payload serializes authoritative participation field`() {
        val payload = DeviceExecutionEventPayload(
            flow_id = "f1",
            task_id = "t1",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            authoritative_participation_state =
                AndroidAuthoritativeParticipationTruth.State.DISTRIBUTED_PARTICIPANT.wireValue,
            participation_tier = AndroidAuthoritativeParticipationTruth
                .ParticipationTier.DISTRIBUTED_PARTICIPANT
                .wireValue,
            authoritative_participation_transition_trigger = "distributed_activity_started"
        )
        val json = gson.toJson(payload)
        assertTrue(json.contains("\"authoritative_participation_state\":\"distributed_participant\""))
        assertTrue(json.contains("\"participation_tier\":\"distributed_participant\""))
        assertTrue(json.contains("\"authoritative_participation_transition_trigger\":\"distributed_activity_started\""))
    }

    @Test
    fun `goal result payload serializes participation tier and mode semantics fields`() {
        val payload = GoalResultPayload(
            task_id = "t-result-1",
            status = "success",
            participation_tier = AndroidAuthoritativeParticipationTruth
                .ParticipationTier.DISPATCH_ELIGIBLE
                .wireValue,
            execution_mode_state = "cross_device_active",
            cross_device_eligibility = true,
            local_mode_gate_deferred = false,
            local_inference_available = true
        )
        val json = gson.toJson(payload)
        assertTrue(json.contains("\"participation_tier\":\"dispatch_eligible\""))
        assertTrue(json.contains("\"execution_mode_state\":\"cross_device_active\""))
        assertTrue(json.contains("\"cross_device_eligibility\":true"))
        assertTrue(json.contains("\"local_inference_available\":true"))
    }

    private fun baseInput(
        crossDeviceEnabled: Boolean = true,
        wsConnected: Boolean = true,
        registrationInFlight: Boolean = false,
        capabilityVisible: Boolean = true,
        readinessSatisfied: Boolean = true,
        continuousIngressReady: Boolean = true,
        runtimeSessionAvailable: Boolean = true,
        fullyAttached: Boolean = true,
        dispatchEligible: Boolean = true,
        continuityIntact: Boolean = true,
        operatorSuspendedOrIsolated: Boolean = false,
        distributedRuntimeActivity: Boolean = false
    ): AndroidAuthoritativeParticipationTruth.DerivationInput =
        AndroidAuthoritativeParticipationTruth.DerivationInput(
            crossDeviceEnabled = crossDeviceEnabled,
            wsConnected = wsConnected,
            registrationInFlight = registrationInFlight,
            capabilityVisible = capabilityVisible,
            readinessSatisfied = readinessSatisfied,
            continuousIngressReady = continuousIngressReady,
            runtimeSessionAvailable = runtimeSessionAvailable,
            fullyAttached = fullyAttached,
            dispatchEligible = dispatchEligible,
            continuityIntact = continuityIntact,
            operatorSuspendedOrIsolated = operatorSuspendedOrIsolated,
            distributedRuntimeActivity = distributedRuntimeActivity
        )
}
