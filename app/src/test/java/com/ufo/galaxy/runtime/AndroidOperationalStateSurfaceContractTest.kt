package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidOperationalStateSurfaceContractTest {

    @Test
    fun `derive publishes every required axis and authority mapping`() {
        val projection = AndroidOperationalStateSurfaceContract.derive(
            AndroidOperationalStateSurfaceContract.DerivationInput(
                deviceId = "device-1",
                durableParticipantId = "participant-1",
                runtimeSessionId = "runtime-1",
                attachedSessionId = "attached-1",
                capabilitySchemaVersion = "1",
                localIntelligenceStatus = "active",
                readinessArtifact = DeviceReadinessArtifact.DeviceReadyForRelease("device-1", "r1"),
                acceptanceArtifact = DeviceAcceptanceArtifact.DeviceAcceptedForGraduation("device-1", "a1"),
                executionModeState = "cross_device_active",
                crossDeviceEligibility = true,
                goalExecutionEligibility = true,
                localLoopReady = true,
                degradedConditionClass = "nominal",
                reconnectRecoveryState = "idle",
                evidencePresenceKind = "positive_evidence",
                replayEligibility = "queue_empty",
                participantIdentityFreshness = "fresh",
                meshRuntimeClosed = true,
                executionBusy = false,
                activeExecutionCount = 0
            )
        )

        assertEquals(AndroidOperationalStateSurfaceContract.SCHEMA_VERSION, projection.schemaVersion)
        assertEquals(
            AndroidOperationalStateSurfaceContract.SurfaceAxis.ALL_WIRE_KEYS,
            projection.states.keys
        )
        assertEquals(
            AndroidOperationalStateSurfaceContract.SurfaceAxis.ALL_WIRE_KEYS,
            projection.authority.keys
        )
        assertEquals(
            "minimum_access_ready_v2_admission_required",
            projection.states[AndroidOperationalStateSurfaceContract.SurfaceAxis.MINIMUM_ACCESS_ADMISSION.wireKey]
        )
        assertEquals(
            AndroidOperationalStateSurfaceContract.AuthorityScope.V2_AUTHORITATIVE.wireValue,
            projection.authority[AndroidOperationalStateSurfaceContract.SurfaceAxis.MINIMUM_ACCESS_ADMISSION.wireKey]
        )
    }

    @Test
    fun `derive expresses happy-path cross-device readiness without collapsing authority boundaries`() {
        val projection = AndroidOperationalStateSurfaceContract.derive(
            AndroidOperationalStateSurfaceContract.DerivationInput(
                deviceId = "device-2",
                durableParticipantId = "participant-2",
                runtimeSessionId = "runtime-2",
                attachedSessionId = "attached-2",
                capabilitySchemaVersion = "1",
                localIntelligenceStatus = "active",
                readinessArtifact = DeviceReadinessArtifact.DeviceReadyForRelease("device-2", "r2"),
                acceptanceArtifact = DeviceAcceptanceArtifact.DeviceAcceptedForGraduation("device-2", "a2"),
                executionModeState = "cross_device_active",
                crossDeviceEligibility = true,
                goalExecutionEligibility = true,
                localLoopReady = true,
                degradedConditionClass = "nominal",
                reconnectRecoveryState = "idle",
                evidencePresenceKind = "positive_evidence",
                replayEligibility = "queue_empty",
                participantIdentityFreshness = "recovered",
                meshRuntimeClosed = true,
                executionBusy = false,
                activeExecutionCount = 0
            )
        )

        assertEquals("identity_present_session_attached", projection.states["registration_discoverability"])
        assertEquals("capability_visible", projection.states["capability_visibility"])
        assertEquals("ready", projection.states["operational_readiness"])
        assertEquals("cross_device_active", projection.states["active_usable_path"])
        assertEquals("strong_runtime_node_active", projection.states["runtime_host_posture"])
        assertEquals("participating_active", projection.states["cross_device_participation"])
        assertEquals("continuity_confirmed", projection.states["session_continuity"])
        assertEquals("eligible_under_v2_governance", projection.states["task_initiation_eligibility"])
        assertEquals("closure_ready_for_v2", projection.states["result_closure"])
        assertEquals(
            "operator_visible_cross_device_ready",
            projection.states["operator_visible_control_perception"]
        )
        assertTrue(
            projection.limitations.containsAll(
                listOf(
                    "v2_retains_final_admission_authority",
                    "v2_retains_cross_repo_aggregation_authority",
                    "v2_retains_final_closure_authority",
                    "operator_visible_control_perception_is_projection_only"
                )
            )
        )
    }

    @Test
    fun `derive makes degraded recovery and incomplete admission states explicit`() {
        val projection = AndroidOperationalStateSurfaceContract.derive(
            AndroidOperationalStateSurfaceContract.DerivationInput(
                deviceId = "device-3",
                durableParticipantId = null,
                runtimeSessionId = null,
                attachedSessionId = null,
                capabilitySchemaVersion = null,
                localIntelligenceStatus = null,
                readinessArtifact = DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal(
                    "device-3",
                    "r3",
                    emptySet()
                ),
                acceptanceArtifact = DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk(
                    "device-3",
                    "a3",
                    "compat still active"
                ),
                executionModeState = "cross_device_degraded",
                crossDeviceEligibility = true,
                goalExecutionEligibility = true,
                localLoopReady = false,
                degradedConditionClass = "fallback",
                reconnectRecoveryState = "recovering",
                evidencePresenceKind = "failed_observation",
                replayEligibility = "stale_session_blocked",
                participantIdentityFreshness = "stale",
                meshRuntimeClosed = false,
                executionBusy = true,
                activeExecutionCount = 2
            )
        )

        assertEquals("identity_partial", projection.states["registration_discoverability"])
        assertEquals("capability_unreported", projection.states["capability_visibility"])
        assertEquals("unknown", projection.states["operational_readiness"])
        assertEquals("cross_device_degraded", projection.states["active_usable_path"])
        assertEquals("strong_runtime_node_degraded", projection.states["runtime_host_posture"])
        assertEquals("compat_only", projection.states["degraded_mode"])
        assertEquals("recovery_active", projection.states["recovery_repair"])
        assertEquals("participating_degraded", projection.states["cross_device_participation"])
        assertEquals("continuity_at_risk", projection.states["session_continuity"])
        assertEquals("blocked_pending_readiness", projection.states["task_initiation_eligibility"])
        assertEquals("closure_in_flight", projection.states["result_closure"])
        assertEquals("minimum_access_blocked", projection.states["minimum_access_admission"])
        assertEquals(
            "operator_visible_runtime_in_flight",
            projection.states["operator_visible_control_perception"]
        )
        assertTrue(projection.limitations.contains("android_readiness_surface_incomplete"))
        assertTrue(projection.limitations.contains("durable_participant_identity_missing"))
        assertTrue(projection.limitations.contains("capability_visibility_partial"))
        assertTrue(projection.limitations.contains("compat_path_limits_full_symmetry"))
    }

    @Test
    fun `derive expresses local-only eligible path when cross-device is off`() {
        val projection = AndroidOperationalStateSurfaceContract.derive(
            AndroidOperationalStateSurfaceContract.DerivationInput(
                deviceId = "device-4",
                durableParticipantId = "participant-4",
                runtimeSessionId = null,
                attachedSessionId = null,
                capabilitySchemaVersion = "1",
                localIntelligenceStatus = "active",
                readinessArtifact = DeviceReadinessArtifact.DeviceReadyForRelease("device-4", "r4"),
                acceptanceArtifact = DeviceAcceptanceArtifact.DeviceAcceptedForGraduation("device-4", "a4"),
                executionModeState = "local_only",
                crossDeviceEligibility = false,
                goalExecutionEligibility = true,
                localLoopReady = true,
                degradedConditionClass = "nominal",
                reconnectRecoveryState = "idle",
                evidencePresenceKind = "positive_evidence",
                replayEligibility = "queue_empty",
                participantIdentityFreshness = "fresh",
                meshRuntimeClosed = false,
                executionBusy = false,
                activeExecutionCount = 0
            )
        )

        assertEquals("identity_present", projection.states["registration_discoverability"])
        assertEquals("local_only_usable", projection.states["active_usable_path"])
        assertEquals("strong_runtime_node_local_execution", projection.states["runtime_host_posture"])
        assertEquals("local_only", projection.states["cross_device_participation"])
        assertEquals("eligible_local_only", projection.states["task_initiation_eligibility"])
        assertEquals(
            "operator_visible_local_ready",
            projection.states["operator_visible_control_perception"]
        )
        assertEquals(
            AndroidOperationalStateSurfaceContract.AuthorityScope.ANDROID_LOCAL_AUTHORITATIVE.wireValue,
            projection.authority["active_usable_path"]
        )
    }

    @Test
    fun `derive expresses no-usable-path state when device id is blank`() {
        val projection = AndroidOperationalStateSurfaceContract.derive(
            AndroidOperationalStateSurfaceContract.DerivationInput(
                deviceId = "",
                durableParticipantId = null,
                runtimeSessionId = null,
                attachedSessionId = null,
                capabilitySchemaVersion = null,
                localIntelligenceStatus = null,
                readinessArtifact = DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal(
                    "device-5",
                    "r5",
                    emptySet()
                ),
                acceptanceArtifact = DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal(
                    "device-5",
                    "a5",
                    emptySet()
                ),
                executionModeState = null,
                crossDeviceEligibility = null,
                goalExecutionEligibility = false,
                localLoopReady = false,
                degradedConditionClass = null,
                reconnectRecoveryState = null,
                evidencePresenceKind = null,
                replayEligibility = null,
                participantIdentityFreshness = null,
                meshRuntimeClosed = null,
                executionBusy = false,
                activeExecutionCount = 0
            )
        )

        assertEquals("identity_missing", projection.states["registration_discoverability"])
        assertEquals("capability_unreported", projection.states["capability_visibility"])
        assertEquals("no_usable_path", projection.states["active_usable_path"])
        assertEquals("runtime_node_unavailable", projection.states["runtime_host_posture"])
        assertEquals("blocked", projection.states["task_initiation_eligibility"])
        assertEquals("minimum_access_unknown", projection.states["minimum_access_admission"])
        assertEquals(
            "operator_visible_limited",
            projection.states["operator_visible_control_perception"]
        )
        assertTrue(projection.limitations.contains("android_readiness_surface_incomplete"))
        assertTrue(projection.limitations.contains("android_minimum_access_surface_incomplete"))
        assertTrue(projection.limitations.contains("capability_visibility_partial"))
    }

    @Test
    fun `derive expresses repair-needed state on failed observation and reconnect failure`() {
        val projection = AndroidOperationalStateSurfaceContract.derive(
            AndroidOperationalStateSurfaceContract.DerivationInput(
                deviceId = "device-6",
                durableParticipantId = "participant-6",
                runtimeSessionId = null,
                attachedSessionId = null,
                capabilitySchemaVersion = "1",
                localIntelligenceStatus = "degraded",
                readinessArtifact = DeviceReadinessArtifact.DeviceReadyForRelease("device-6", "r6"),
                acceptanceArtifact = DeviceAcceptanceArtifact.DeviceAcceptedForGraduation("device-6", "a6"),
                executionModeState = "local_only",
                crossDeviceEligibility = true,
                goalExecutionEligibility = true,
                localLoopReady = false,
                degradedConditionClass = "degraded",
                reconnectRecoveryState = "failed",
                evidencePresenceKind = "failed_observation",
                replayEligibility = "stale_session_blocked",
                participantIdentityFreshness = "stale",
                meshRuntimeClosed = false,
                executionBusy = false,
                activeExecutionCount = 0
            )
        )

        assertEquals("repair_needed", projection.states["recovery_repair"])
        assertEquals("continuity_at_risk", projection.states["session_continuity"])
        assertEquals("degraded", projection.states["degraded_mode"])
        assertEquals("eligible_not_attached", projection.states["cross_device_participation"])
        assertTrue(projection.limitations.contains("cross_device_session_unattached"))
    }

    @Test
    fun `derive schema version is present in every projection and ALL_WIRE_KEYS contains all axes`() {
        assertEquals("1", AndroidOperationalStateSurfaceContract.SCHEMA_VERSION)
        assertEquals(13, AndroidOperationalStateSurfaceContract.SurfaceAxis.ALL_WIRE_KEYS.size)
        assertTrue(
            AndroidOperationalStateSurfaceContract.SurfaceAxis.ALL_WIRE_KEYS.containsAll(
                setOf(
                    "registration_discoverability",
                    "capability_visibility",
                    "operational_readiness",
                    "active_usable_path",
                    "runtime_host_posture",
                    "degraded_mode",
                    "recovery_repair",
                    "cross_device_participation",
                    "session_continuity",
                    "task_initiation_eligibility",
                    "result_closure",
                    "minimum_access_admission",
                    "operator_visible_control_perception"
                )
            )
        )
    }
}
