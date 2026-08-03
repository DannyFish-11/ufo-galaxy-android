package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrA2AndroidMeshRuntimeSignalProtocolTest {

    private val gson = Gson()

    @Test
    fun `derive emits live participation readiness for active mesh runtime`() {
        // 测试修复:原场景用 barrier=WAITING 制造 PARTIAL 证明等级,但 BARRIER_WAITING
        // 生命周期按设计不接受新子任务(isAcceptingSubtasks 明确排除),
        // isParticipationReady 必然为 false,与断言矛盾。改用 health=DEGRADED +
        // barrier=NOT_APPLICABLE:生命周期为 DEGRADED(可接受、带保留),
        // 证明等级同样是 PARTIAL,且就绪/参与语义与测试意图一致。
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.DEGRADED,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.EXECUTING,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )

        assertTrue(report.isParticipationReady)
        assertTrue(report.isRuntimeEngaged)
        assertFalse(report.isRuntimeClosed)
        assertEquals(MeshRuntimeProofQuality.PARTIAL, report.proofQuality)
    }

    @Test
    fun `derive emits runtime_closed for terminal collaboration after participation exits`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.COMPLETED,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )

        assertFalse(report.isParticipationReady)
        assertTrue(report.isRuntimeClosed)
        assertEquals(MeshRuntimeProofQuality.MISSING, report.proofQuality)
    }

    @Test
    fun `snapshot payload serializes mesh readiness proof and closure fields`() {
        // 测试修复:同上,BARRIER_WAITING 生命周期不接受新子任务,
        // mesh_participation_ready 必然序列化为 false;改用 DEGRADED 生命周期
        // (可接受、带保留)以匹配下方的 ready=true 断言。
        val report = AndroidMeshParticipationRuntimeContract.MeshRuntimeStateReport(
            participationLifecycle = MeshParticipationLifecycleState.DEGRADED,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationLifecycle = CollaborationLifecycleState.EXECUTING,
            constrainedReasons = emptyList(),
            proofQuality = MeshRuntimeProofQuality.PARTIAL
        )
        val payload = DeviceStateSnapshotPayload(
            device_id = "device-a2",
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
            model_id = null,
            runtime_type = null,
            checksum_ok = null,
            mobilevlm_present = false,
            mobilevlm_checksum_ok = false,
            seeclick_present = false,
            pending_first_download = true,
            mesh_participation_lifecycle_state = report.participationLifecycle.wireValue,
            barrier_participation_state = report.barrierState.wireValue,
            collaboration_lifecycle_state = report.collaborationLifecycle.wireValue,
            mesh_constrained_reasons = report.constrainedReasons,
            mesh_participation_ready = report.isParticipationReady,
            mesh_runtime_engaged = report.isRuntimeEngaged,
            mesh_runtime_closed = report.isRuntimeClosed,
            mesh_runtime_proof_quality = report.proofQuality.wireValue
        )

        val json = gson.toJsonTree(payload).asJsonObject
        assertTrue(json.get("mesh_participation_ready").asBoolean)
        assertTrue(json.get("mesh_runtime_engaged").asBoolean)
        assertFalse(json.get("mesh_runtime_closed").asBoolean)
        assertEquals("partial", json.get("mesh_runtime_proof_quality").asString)
        assertEquals("not_applicable", json.get("barrier_participation_state").asString)
    }

    @Test
    fun `snapshot payload serializes runtime_closed true for terminal closure signal`() {
        val payload = DeviceStateSnapshotPayload(
            device_id = "device-a2-closed",
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
            model_id = null,
            runtime_type = null,
            checksum_ok = null,
            mobilevlm_present = false,
            mobilevlm_checksum_ok = false,
            seeclick_present = false,
            pending_first_download = true,
            mesh_participation_lifecycle_state = MeshParticipationLifecycleState.INACTIVE.wireValue,
            barrier_participation_state = BarrierParticipationState.NOT_APPLICABLE.wireValue,
            collaboration_lifecycle_state = CollaborationLifecycleState.COMPLETED.wireValue,
            mesh_constrained_reasons = emptyList(),
            mesh_participation_ready = false,
            mesh_runtime_engaged = false,
            mesh_runtime_closed = true,
            mesh_runtime_proof_quality = MeshRuntimeProofQuality.MISSING.wireValue
        )

        val json = gson.toJsonTree(payload).asJsonObject
        assertTrue(json.get("mesh_runtime_closed").asBoolean)
        assertEquals("missing", json.get("mesh_runtime_proof_quality").asString)
    }

    private fun rollout(
        crossDeviceAllowed: Boolean,
        delegatedExecutionAllowed: Boolean
    ): RolloutControlSnapshot = RolloutControlSnapshot(
        crossDeviceAllowed = crossDeviceAllowed,
        delegatedExecutionAllowed = delegatedExecutionAllowed,
        fallbackToLocalAllowed = true,
        goalExecutionAllowed = true
    )
}
