package com.ufo.galaxy.runtime

/**
 * Android-side unified operational/readiness surface contract for cross-repo aggregation.
 *
 * This contract does not replace the more detailed readiness, acceptance, governance, or
 * lifecycle contracts already present in Android. Instead, it derives a compact, structured
 * surface that higher-level aggregators can consume without guessing how Android uses
 * "present", "available", "ready", or "admitted".
 *
 * The surface is intentionally explicit about authority boundaries:
 *  - Android is authoritative for local identity presence, capability visibility, local
 *    readiness, path usability, degradation, and recovery participation.
 *  - Android can report prerequisites and local completion evidence for cross-device flows.
 *  - V2 remains authoritative for final admission, cross-repo aggregation, and final closure.
 */
object AndroidOperationalStateSurfaceContract {

    const val SCHEMA_VERSION = "1"

    enum class SurfaceAxis(val wireKey: String) {
        REGISTRATION_DISCOVERABILITY("registration_discoverability"),
        CAPABILITY_VISIBILITY("capability_visibility"),
        OPERATIONAL_READINESS("operational_readiness"),
        ACTIVE_USABLE_PATH("active_usable_path"),
        DEGRADED_MODE("degraded_mode"),
        RECOVERY_REPAIR("recovery_repair"),
        CROSS_DEVICE_PARTICIPATION("cross_device_participation"),
        SESSION_CONTINUITY("session_continuity"),
        TASK_INITIATION_ELIGIBILITY("task_initiation_eligibility"),
        RESULT_CLOSURE("result_closure"),
        MINIMUM_ACCESS_ADMISSION("minimum_access_admission");

        companion object {
            val ALL_WIRE_KEYS: Set<String> = entries.map { it.wireKey }.toSet()
        }
    }

    enum class AuthorityScope(val wireValue: String) {
        ANDROID_LOCAL_AUTHORITATIVE("android_local_authoritative"),
        ANDROID_LOCAL_SIGNAL_V2_COORDINATED("android_local_signal_v2_coordinated"),
        ANDROID_PREREQUISITE_V2_ADMITS("android_prerequisite_v2_admits"),
        ANDROID_LOCAL_COMPLETION_V2_CLOSES("android_local_completion_v2_closes"),
        V2_AUTHORITATIVE("v2_authoritative")
    }

    data class DerivationInput(
        val deviceId: String,
        val durableParticipantId: String?,
        val runtimeSessionId: String?,
        val attachedSessionId: String?,
        val capabilitySchemaVersion: String?,
        val localIntelligenceStatus: String?,
        val readinessArtifact: DeviceReadinessArtifact,
        val acceptanceArtifact: DeviceAcceptanceArtifact,
        val executionModeState: String?,
        val crossDeviceEligibility: Boolean?,
        val goalExecutionEligibility: Boolean?,
        val localLoopReady: Boolean?,
        val degradedConditionClass: String?,
        val reconnectRecoveryState: String?,
        val evidencePresenceKind: String?,
        val replayEligibility: String?,
        val participantIdentityFreshness: String?,
        val meshRuntimeClosed: Boolean?,
        val executionBusy: Boolean?,
        val activeExecutionCount: Int?
    )

    data class SurfaceProjection(
        val schemaVersion: String,
        val states: Map<String, String>,
        val authority: Map<String, String>,
        val limitations: List<String>
    )

    fun derive(input: DerivationInput): SurfaceProjection {
        val states = linkedMapOf(
            SurfaceAxis.REGISTRATION_DISCOVERABILITY.wireKey to deriveRegistrationState(input),
            SurfaceAxis.CAPABILITY_VISIBILITY.wireKey to deriveCapabilityVisibilityState(input),
            SurfaceAxis.OPERATIONAL_READINESS.wireKey to deriveOperationalReadinessState(input),
            SurfaceAxis.ACTIVE_USABLE_PATH.wireKey to deriveActiveUsablePathState(input),
            SurfaceAxis.DEGRADED_MODE.wireKey to deriveDegradedModeState(input),
            SurfaceAxis.RECOVERY_REPAIR.wireKey to deriveRecoveryRepairState(input),
            SurfaceAxis.CROSS_DEVICE_PARTICIPATION.wireKey to deriveCrossDeviceParticipationState(input),
            SurfaceAxis.SESSION_CONTINUITY.wireKey to deriveSessionContinuityState(input),
            SurfaceAxis.TASK_INITIATION_ELIGIBILITY.wireKey to deriveTaskInitiationEligibilityState(input),
            SurfaceAxis.RESULT_CLOSURE.wireKey to deriveResultClosureState(input),
            SurfaceAxis.MINIMUM_ACCESS_ADMISSION.wireKey to deriveMinimumAccessAdmissionState(input)
        )

        val authority = linkedMapOf(
            SurfaceAxis.REGISTRATION_DISCOVERABILITY.wireKey to AuthorityScope.ANDROID_LOCAL_AUTHORITATIVE.wireValue,
            SurfaceAxis.CAPABILITY_VISIBILITY.wireKey to AuthorityScope.ANDROID_LOCAL_AUTHORITATIVE.wireValue,
            SurfaceAxis.OPERATIONAL_READINESS.wireKey to AuthorityScope.ANDROID_LOCAL_AUTHORITATIVE.wireValue,
            SurfaceAxis.ACTIVE_USABLE_PATH.wireKey to AuthorityScope.ANDROID_LOCAL_AUTHORITATIVE.wireValue,
            SurfaceAxis.DEGRADED_MODE.wireKey to AuthorityScope.ANDROID_LOCAL_AUTHORITATIVE.wireValue,
            SurfaceAxis.RECOVERY_REPAIR.wireKey to AuthorityScope.ANDROID_LOCAL_AUTHORITATIVE.wireValue,
            SurfaceAxis.CROSS_DEVICE_PARTICIPATION.wireKey to AuthorityScope.ANDROID_LOCAL_SIGNAL_V2_COORDINATED.wireValue,
            SurfaceAxis.SESSION_CONTINUITY.wireKey to AuthorityScope.ANDROID_LOCAL_SIGNAL_V2_COORDINATED.wireValue,
            SurfaceAxis.TASK_INITIATION_ELIGIBILITY.wireKey to AuthorityScope.ANDROID_PREREQUISITE_V2_ADMITS.wireValue,
            SurfaceAxis.RESULT_CLOSURE.wireKey to AuthorityScope.ANDROID_LOCAL_COMPLETION_V2_CLOSES.wireValue,
            SurfaceAxis.MINIMUM_ACCESS_ADMISSION.wireKey to AuthorityScope.V2_AUTHORITATIVE.wireValue
        )

        val limitations = linkedSetOf(
            "v2_retains_final_admission_authority",
            "v2_retains_cross_repo_aggregation_authority",
            "v2_retains_final_closure_authority"
        )
        if (states[SurfaceAxis.OPERATIONAL_READINESS.wireKey] == "unknown") {
            limitations += "android_readiness_surface_incomplete"
        }
        if (states[SurfaceAxis.MINIMUM_ACCESS_ADMISSION.wireKey] == "minimum_access_unknown") {
            limitations += "android_minimum_access_surface_incomplete"
        }
        if (states[SurfaceAxis.REGISTRATION_DISCOVERABILITY.wireKey] == "identity_partial") {
            limitations += "durable_participant_identity_missing"
        }
        if (states[SurfaceAxis.CAPABILITY_VISIBILITY.wireKey] != "capability_visible") {
            limitations += "capability_visibility_partial"
        }
        if (states[SurfaceAxis.CROSS_DEVICE_PARTICIPATION.wireKey] == "eligible_not_attached") {
            limitations += "cross_device_session_unattached"
        }
        if (states[SurfaceAxis.DEGRADED_MODE.wireKey] == "compat_only") {
            limitations += "compat_path_limits_full_symmetry"
        }

        return SurfaceProjection(
            schemaVersion = SCHEMA_VERSION,
            states = states,
            authority = authority,
            limitations = limitations.toList()
        )
    }

    private fun deriveRegistrationState(input: DerivationInput): String = when {
        input.deviceId.isBlank() -> "identity_missing"
        input.durableParticipantId.isNullOrBlank() -> "identity_partial"
        !input.attachedSessionId.isNullOrBlank() || !input.runtimeSessionId.isNullOrBlank() ->
            "identity_present_session_attached"
        else -> "identity_present"
    }

    private fun deriveCapabilityVisibilityState(input: DerivationInput): String = when {
        !input.capabilitySchemaVersion.isNullOrBlank() && !input.localIntelligenceStatus.isNullOrBlank() ->
            "capability_visible"
        !input.capabilitySchemaVersion.isNullOrBlank() || !input.localIntelligenceStatus.isNullOrBlank() ->
            "capability_partial"
        else -> "capability_unreported"
    }

    private fun deriveOperationalReadinessState(input: DerivationInput): String = when (input.readinessArtifact) {
        is DeviceReadinessArtifact.DeviceReadyForRelease -> "ready"
        is DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal -> "unknown"
        else -> "blocked"
    }

    private fun deriveActiveUsablePathState(input: DerivationInput): String = when (input.executionModeState) {
        "cross_device_active" -> "cross_device_active"
        "cross_device_degraded" -> "cross_device_degraded"
        else -> if (input.localLoopReady == true) "local_only_usable" else "no_usable_path"
    }

    private fun deriveDegradedModeState(input: DerivationInput): String = when {
        input.reconnectRecoveryState == "recovering" -> "recovering"
        input.acceptanceArtifact is DeviceAcceptanceArtifact.DeviceRejectedDueToCompatBypassRisk ->
            "compat_only"
        input.degradedConditionClass in setOf("degraded", "fallback", "constrained", "partial", "delayed") ||
            input.executionModeState == "cross_device_degraded" -> "degraded"
        else -> "nominal"
    }

    private fun deriveRecoveryRepairState(input: DerivationInput): String = when {
        input.reconnectRecoveryState == "recovering" -> "recovery_active"
        input.reconnectRecoveryState == "failed" ||
            input.evidencePresenceKind == "failed_observation" -> "repair_needed"
        input.reconnectRecoveryState == "recovered" -> "recovered_pending_stability"
        else -> "none"
    }

    private fun deriveCrossDeviceParticipationState(input: DerivationInput): String = when {
        input.crossDeviceEligibility == false -> "local_only"
        input.executionModeState == "cross_device_active" -> "participating_active"
        input.executionModeState == "cross_device_degraded" -> "participating_degraded"
        input.crossDeviceEligibility == true -> "eligible_not_attached"
        else -> "participation_unknown"
    }

    private fun deriveSessionContinuityState(input: DerivationInput): String = when {
        input.replayEligibility == "stale_session_blocked" ||
            input.participantIdentityFreshness == "stale" ||
            input.reconnectRecoveryState == "failed" -> "continuity_at_risk"
        input.reconnectRecoveryState == "recovering" ||
            input.replayEligibility == "eligible_for_replay" -> "continuity_recovering"
        !input.attachedSessionId.isNullOrBlank() &&
            input.participantIdentityFreshness in setOf("fresh", "recovered") -> "continuity_confirmed"
        else -> "continuity_unknown"
    }

    private fun deriveTaskInitiationEligibilityState(input: DerivationInput): String = when {
        input.goalExecutionEligibility != true -> "blocked"
        input.crossDeviceEligibility == false && input.localLoopReady == true -> "eligible_local_only"
        input.crossDeviceEligibility == true &&
            input.readinessArtifact is DeviceReadinessArtifact.DeviceReadyForRelease -> "eligible_under_v2_governance"
        else -> "blocked_pending_readiness"
    }

    private fun deriveResultClosureState(input: DerivationInput): String = when {
        input.meshRuntimeClosed == true -> "closure_ready_for_v2"
        input.executionBusy == true || (input.activeExecutionCount ?: 0) > 0 -> "closure_in_flight"
        input.acceptanceArtifact is DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal ->
            "closure_unknown"
        input.acceptanceArtifact is DeviceAcceptanceArtifact.DeviceAcceptedForGraduation -> "closure_ready_for_v2"
        else -> "closure_blocked"
    }

    private fun deriveMinimumAccessAdmissionState(input: DerivationInput): String = when (input.acceptanceArtifact) {
        is DeviceAcceptanceArtifact.DeviceAcceptedForGraduation ->
            "minimum_access_ready_v2_admission_required"
        is DeviceAcceptanceArtifact.DeviceAcceptanceUnknownDueToIncompleteSignal ->
            "minimum_access_unknown"
        else -> "minimum_access_blocked"
    }
}
