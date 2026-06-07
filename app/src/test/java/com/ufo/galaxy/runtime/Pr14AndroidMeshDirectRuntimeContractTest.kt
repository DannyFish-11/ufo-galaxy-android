package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import com.ufo.galaxy.protocol.PeerAnnouncePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr14AndroidMeshDirectRuntimeContractTest {

    private val gson = Gson()

    @Test
    fun `derive reports direct peer route when topology presence and capability all align`() {
        val snapshot = AndroidMeshDirectRuntimeContract.derive(
            AndroidMeshDirectRuntimeContract.DerivationInput(
                meshId = "mesh-pr14",
                localDeviceId = "android-a",
                wsConnected = true,
                topologyMeshId = "mesh-pr14",
                topologyNodes = listOf("android-a", "peer-b"),
                peerAnnouncements = mapOf(
                    "peer-b" to PeerAnnouncePayload(peer_device_id = "peer-b", peer_role = "participant")
                ),
                peerCapabilities = mapOf(
                    "peer-b" to listOf(AndroidMeshDirectRuntimeContract.REQUIRED_CAPABILITY_PARALLEL_SUBTASK)
                )
            )
        )

        assertEquals(AndroidMeshDirectRuntimeContract.DirectPathRoute.DIRECT_PEER, snapshot.route)
        assertEquals(AndroidMeshDirectRuntimeContract.DirectPathState.READY, snapshot.state)
        assertTrue(snapshot.channelReady)
        assertTrue(snapshot.directPathViable)
        assertEquals(1, snapshot.peerCount)
        assertEquals(1, snapshot.readyPeerCount)
        assertTrue(snapshot.reasonCodes.isEmpty())
    }

    @Test
    fun `derive falls back when remote peer has no confirmed mesh capability`() {
        val snapshot = AndroidMeshDirectRuntimeContract.derive(
            AndroidMeshDirectRuntimeContract.DerivationInput(
                meshId = "mesh-pr14",
                localDeviceId = "android-a",
                wsConnected = true,
                topologyMeshId = "mesh-pr14",
                topologyNodes = listOf("android-a", "peer-b"),
                peerAnnouncements = mapOf(
                    "peer-b" to PeerAnnouncePayload(peer_device_id = "peer-b", peer_role = "participant")
                ),
                peerCapabilities = mapOf(
                    "peer-b" to listOf("takeover_only")
                )
            )
        )

        assertEquals(AndroidMeshDirectRuntimeContract.DirectPathRoute.GATEWAY_FALLBACK, snapshot.route)
        assertEquals(AndroidMeshDirectRuntimeContract.DirectPathState.FALLBACK, snapshot.state)
        assertFalse(snapshot.directPathViable)
        assertTrue(
            snapshot.reasonCodes.contains(AndroidMeshDirectRuntimeContract.REASON_PEER_CAPABILITY_UNCONFIRMED)
        )
    }

    @Test
    fun `failed direct send attempt converts ready direct route into explicit fallback`() {
        val ready = AndroidMeshDirectRuntimeContract.derive(
            AndroidMeshDirectRuntimeContract.DerivationInput(
                meshId = "mesh-pr14",
                localDeviceId = "android-a",
                wsConnected = true,
                topologyMeshId = "mesh-pr14",
                topologyNodes = listOf("android-a", "peer-b"),
                peerAnnouncements = mapOf(
                    "peer-b" to PeerAnnouncePayload(peer_device_id = "peer-b")
                ),
                peerCapabilities = mapOf(
                    "peer-b" to listOf(AndroidMeshDirectRuntimeContract.REQUIRED_CAPABILITY_PARALLEL_SUBTASK)
                )
            )
        )

        val failed = AndroidMeshDirectRuntimeContract.onDirectSendAttempt(
            snapshot = ready,
            stage = AndroidMeshDirectRuntimeContract.AttemptStage.RESULT,
            succeeded = false
        )

        assertEquals(AndroidMeshDirectRuntimeContract.DirectPathRoute.GATEWAY_FALLBACK, failed.route)
        assertEquals(AndroidMeshDirectRuntimeContract.DirectPathState.FALLBACK, failed.state)
        assertEquals("result", failed.lastAttemptStage)
        assertEquals(false, failed.lastAttemptSucceeded)
        assertTrue(failed.reasonCodes.contains(AndroidMeshDirectRuntimeContract.REASON_SEND_FAILED_RESULT))
    }

    @Test
    fun `device state snapshot serializes mesh direct runtime truth fields`() {
        val payload = DeviceStateSnapshotPayload(
            device_id = "android-a",
            llama_cpp_available = true,
            ncnn_available = true,
            active_runtime_type = "HYBRID",
            model_ready = true,
            accessibility_ready = true,
            overlay_ready = true,
            local_loop_ready = true,
            warmup_result = "ok",
            offline_queue_depth = 0,
            current_fallback_tier = "center_delegated",
            mesh_direct_schema_version = AndroidMeshDirectRuntimeContract.SCHEMA_VERSION,
            mesh_direct_state = AndroidMeshDirectRuntimeContract.DirectPathState.FALLBACK.wireValue,
            mesh_direct_route = AndroidMeshDirectRuntimeContract.DirectPathRoute.GATEWAY_FALLBACK.wireValue,
            mesh_direct_channel_ready = false,
            mesh_direct_peer_count = 2,
            mesh_direct_ready_peer_count = 0,
            mesh_direct_reason_codes = listOf(AndroidMeshDirectRuntimeContract.REASON_PEER_CAPABILITY_UNCONFIRMED),
            mesh_direct_last_attempt_stage = AndroidMeshDirectRuntimeContract.AttemptStage.RESULT.wireValue,
            mesh_direct_last_attempt_succeeded = false
        )

        val json = gson.toJsonTree(payload).asJsonObject
        assertEquals(
            AndroidMeshDirectRuntimeContract.SCHEMA_VERSION,
            json.get("mesh_direct_schema_version").asString
        )
        assertEquals("fallback", json.get("mesh_direct_state").asString)
        assertEquals("gateway_fallback", json.get("mesh_direct_route").asString)
        assertEquals(false, json.get("mesh_direct_last_attempt_succeeded").asBoolean)
    }

    @Test
    fun `device execution event serializes mesh direct fallback evidence`() {
        val payload = DeviceExecutionEventPayload(
            flow_id = "mesh-pr14",
            task_id = "task-pr14",
            phase = DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION,
            mesh_direct_schema_version = AndroidMeshDirectRuntimeContract.SCHEMA_VERSION,
            mesh_direct_state = AndroidMeshDirectRuntimeContract.DirectPathState.FALLBACK.wireValue,
            mesh_direct_route = AndroidMeshDirectRuntimeContract.DirectPathRoute.GATEWAY_FALLBACK.wireValue,
            mesh_direct_channel_ready = false,
            mesh_direct_peer_count = 1,
            mesh_direct_ready_peer_count = 0,
            mesh_direct_reason_codes = listOf(AndroidMeshDirectRuntimeContract.REASON_SEND_FAILED_JOIN),
            mesh_direct_last_attempt_stage = AndroidMeshDirectRuntimeContract.AttemptStage.JOIN.wireValue,
            mesh_direct_last_attempt_succeeded = false
        )

        val json = gson.toJsonTree(payload).asJsonObject
        assertEquals("fallback_transition", json.get("phase").asString)
        assertEquals("join", json.get("mesh_direct_last_attempt_stage").asString)
        assertTrue(
            json.getAsJsonArray("mesh_direct_reason_codes")
                .any { it.asString == AndroidMeshDirectRuntimeContract.REASON_SEND_FAILED_JOIN }
        )
    }
}
