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
        assertEquals("participating_active", projection.states["cross_device_participation"])
        assertEquals("continuity_confirmed", projection.states["session_continuity"])
        assertEquals("eligible_under_v2_governance", projection.states["task_initiation_eligibility"])
        assertEquals("closure_ready_for_v2", projection.states["result_closure"])
        assertTrue(
            projection.limitations.containsAll(
                listOf(
                    "v2_retains_final_admission_authority",
                    "v2_retains_cross_repo_aggregation_authority",
                    "v2_retains_final_closure_authority"
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
        assertEquals("compat_only", projection.states["degraded_mode"])
        assertEquals("recovery_active", projection.states["recovery_repair"])
        assertEquals("participating_degraded", projection.states["cross_device_participation"])
        assertEquals("continuity_at_risk", projection.states["session_continuity"])
        assertEquals("blocked_pending_readiness", projection.states["task_initiation_eligibility"])
        assertEquals("closure_in_flight", projection.states["result_closure"])
        assertEquals("minimum_access_blocked", projection.states["minimum_access_admission"])
        assertTrue(projection.limitations.contains("android_readiness_surface_incomplete"))
        assertTrue(projection.limitations.contains("durable_participant_identity_missing"))
        assertTrue(projection.limitations.contains("capability_visibility_partial"))
        assertTrue(projection.limitations.contains("compat_path_limits_full_symmetry"))
    }
}
