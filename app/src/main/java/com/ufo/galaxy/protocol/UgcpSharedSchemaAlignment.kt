package com.ufo.galaxy.protocol

/**
 * Lightweight Android-side UGCP shared-schema alignment registry.
 *
 * This is intentionally additive and non-disruptive: it does not alter current AIP wire fields
 * or runtime behavior. It freezes canonical mapping terms used for cross-repo convergence work.
 */
enum class UgcpSchemaFamily {
    IDENTITY,
    CONTROL,
    RUNTIME,
    COORDINATION,
    TRUTH
}

data class UgcpIdentityAlignment(
    val canonicalIdentity: String,
    val androidCarrier: String
)

data class UgcpTransferEventAlignment(
    val androidEvent: String,
    val canonicalTransferSemantic: String
)

data class UgcpCoordinationEventAlignment(
    val androidEvent: String,
    val canonicalCoordinationSemantic: String
)

enum class UgcpTruthEventSemanticClass {
    AUTHORITATIVE_STATE_TRANSITION,
    AUTHORITATIVE_RESULT_REPORT,
    OBSERVATIONAL_EVENT_EMISSION
}

data class UgcpTruthEventAlignment(
    val androidSignal: String,
    val canonicalTruthEventSemantic: String,
    val semanticClass: UgcpTruthEventSemanticClass
)

object UgcpSharedSchemaAlignment {
    const val runtimeWsProfileName: String = "ugcp.runtime_ws_profile.android"

    const val runtimeWsProfileTransport: String = "aip_ws"

    const val runtimeWsProfileStatus: String = "incremental_alignment"

    const val controlTransferProfileName: String = "ugcp.control_transfer_profile.android"

    const val controlTransferProfileStatus: String = "incremental_alignment"

    const val coordinationProfileName: String = "ugcp.coordination_profile.android"

    const val coordinationProfileStatus: String = "incremental_alignment"

    const val truthEventModelName: String = "ugcp.truth_event_model.android"

    const val truthEventModelStatus: String = "incremental_alignment"

    val identityAlignments: List<UgcpIdentityAlignment> = listOf(
        UgcpIdentityAlignment("TaskId", "task_id"),
        UgcpIdentityAlignment("TraceId", "trace_id"),
        UgcpIdentityAlignment("ControlSessionId", "session_id"),
        UgcpIdentityAlignment("RuntimeSessionId", "runtime_session_id"),
        UgcpIdentityAlignment("MeshSessionId", "mesh_id"),
        UgcpIdentityAlignment("NodeId(source)", "device_id"),
        UgcpIdentityAlignment("NodeId(target)", "target_device_id / target_node"),
        UgcpIdentityAlignment("ExecutionInstanceId", "signal_id or idempotency_key")
    )

    val messageFamilyAlignments: Map<MsgType, UgcpSchemaFamily> = mapOf(
        MsgType.DEVICE_REGISTER to UgcpSchemaFamily.IDENTITY,
        MsgType.HEARTBEAT to UgcpSchemaFamily.RUNTIME,
        MsgType.HEARTBEAT_ACK to UgcpSchemaFamily.RUNTIME,
        MsgType.CAPABILITY_REPORT to UgcpSchemaFamily.RUNTIME,
        MsgType.TASK_SUBMIT to UgcpSchemaFamily.CONTROL,
        MsgType.TASK_ASSIGN to UgcpSchemaFamily.CONTROL,
        MsgType.TAKEOVER_REQUEST to UgcpSchemaFamily.CONTROL,
        MsgType.TAKEOVER_RESPONSE to UgcpSchemaFamily.CONTROL,
        MsgType.DELEGATED_EXECUTION_SIGNAL to UgcpSchemaFamily.RUNTIME,
        MsgType.MESH_JOIN to UgcpSchemaFamily.COORDINATION,
        MsgType.MESH_LEAVE to UgcpSchemaFamily.COORDINATION,
        MsgType.MESH_RESULT to UgcpSchemaFamily.COORDINATION,
        MsgType.TASK_RESULT to UgcpSchemaFamily.TRUTH,
        MsgType.COMMAND_RESULT to UgcpSchemaFamily.TRUTH,
        MsgType.GOAL_RESULT to UgcpSchemaFamily.TRUTH,
        MsgType.GOAL_EXECUTION_RESULT to UgcpSchemaFamily.TRUTH
    )

    val runtimeWsProfileMessageFamilies: Set<MsgType> = setOf(
        MsgType.DEVICE_REGISTER,
        MsgType.CAPABILITY_REPORT,
        MsgType.HEARTBEAT,
        MsgType.HEARTBEAT_ACK,
        MsgType.TASK_SUBMIT,
        MsgType.TASK_ASSIGN,
        MsgType.TASK_RESULT,
        MsgType.COMMAND_RESULT,
        MsgType.GOAL_RESULT,
        MsgType.GOAL_EXECUTION_RESULT,
        MsgType.TAKEOVER_REQUEST,
        MsgType.TAKEOVER_RESPONSE,
        MsgType.DELEGATED_EXECUTION_SIGNAL,
        MsgType.MESH_JOIN,
        MsgType.MESH_LEAVE,
        MsgType.MESH_RESULT
    )

    val sessionContinuityTerms: Set<String> = setOf(
        "session_id (control_session_id alias)",
        "runtime_session_id",
        "attached_session_id",
        "mesh_id (mesh_session_id alias)",
        "reconnect_recovery_state: idle|recovering|recovered|failed",
        "attached_session_state: attached|detaching|detached",
        "detach_cause: explicit_detach|disconnect|disable|invalidation"
    )

    val readinessCapabilityTerms: Set<String> = setOf(
        "source_runtime_posture",
        "model_ready",
        "accessibility_ready",
        "overlay_ready",
        "degraded_mode"
    )

    val transferTerms: Set<String> = setOf(
        "takeover_request",
        "takeover_response",
        "delegated_execution_signal"
    )

    val transferLifecycleTerms: Set<String> = setOf(
        "transfer_accept",
        "transfer_reject",
        "transfer_cancel",
        "transfer_expire",
        "transfer_adopt",
        "transfer_resume"
    )

    val transferEventAlignments: List<UgcpTransferEventAlignment> = listOf(
        UgcpTransferEventAlignment(
            androidEvent = "takeover_response.accepted=true",
            canonicalTransferSemantic = "transfer_accept"
        ),
        UgcpTransferEventAlignment(
            androidEvent = "takeover_response.accepted=false",
            canonicalTransferSemantic = "transfer_reject"
        ),
        UgcpTransferEventAlignment(
            androidEvent = "delegated_execution_signal.result_kind=cancelled",
            canonicalTransferSemantic = "transfer_cancel"
        ),
        UgcpTransferEventAlignment(
            androidEvent = "delegated_execution_signal.result_kind=timeout",
            canonicalTransferSemantic = "transfer_expire"
        ),
        UgcpTransferEventAlignment(
            androidEvent = "delegated_execution_signal.result_kind=rejected",
            canonicalTransferSemantic = "transfer_reject"
        ),
        UgcpTransferEventAlignment(
            androidEvent = "delegated_handoff_contract.continuation_token",
            canonicalTransferSemantic = "transfer_adopt"
        ),
        UgcpTransferEventAlignment(
            androidEvent = "delegated_handoff_contract.handoff_reason=continuation",
            canonicalTransferSemantic = "transfer_resume"
        )
    )

    val meshTerms: Set<String> = setOf(
        "mesh_join",
        "mesh_leave",
        "mesh_result"
    )

    val coordinationRoleTerms: Set<String> = setOf(
        "participant",
        "coordinator"
    )

    val coordinationReadinessTerms: Set<String> = setOf(
        "source_runtime_posture=join_runtime",
        "source_runtime_posture=control_only",
        "model_ready",
        "accessibility_ready",
        "overlay_ready",
        "degraded_mode"
    )

    val coordinationLifecycleTerms: Set<String> = setOf(
        "coordination_participant_joined",
        "coordination_participant_left",
        "coordination_execution_result_reported",
        "coordination_execution_terminal"
    )

    val coordinationEventAlignments: List<UgcpCoordinationEventAlignment> = listOf(
        UgcpCoordinationEventAlignment(
            androidEvent = "mesh_join",
            canonicalCoordinationSemantic = "coordination_participant_joined"
        ),
        UgcpCoordinationEventAlignment(
            androidEvent = "mesh_leave",
            canonicalCoordinationSemantic = "coordination_participant_left"
        ),
        UgcpCoordinationEventAlignment(
            androidEvent = "mesh_result",
            canonicalCoordinationSemantic = "coordination_execution_result_reported"
        ),
        UgcpCoordinationEventAlignment(
            androidEvent = "mesh_result.status in {success,partial,error}",
            canonicalCoordinationSemantic = "coordination_execution_terminal"
        )
    )

    val terminalVocabulary: Set<String> = setOf(
        "success",
        "error",
        "cancelled",
        "completed",
        "failed",
        "timeout",
        "rejected",
        "partial"
    )

    val coordinationOutcomeVocabulary: Set<String> = terminalVocabulary.intersect(
        setOf("success", "partial", "error")
    )

    val authoritativeTruthSurfaces: Set<String> = setOf(
        "RuntimeController.state",
        "RuntimeController.hostSessionSnapshot",
        "RuntimeController.targetReadinessProjection",
        "RuntimeController.reconnectRecoveryState",
        "task_result|command_result|goal_result|goal_execution_result terminal status"
    )

    val observationalNotificationSurfaces: Set<String> = setOf(
        "RuntimeController.takeoverFailure",
        "delegated_execution_signal.ack/progress/result",
        "mesh_join|mesh_leave informational participation emission"
    )

    val truthEventAlignments: List<UgcpTruthEventAlignment> = listOf(
        UgcpTruthEventAlignment(
            androidSignal = "RuntimeController.state transition",
            canonicalTruthEventSemantic = "runtime_state_truth_updated",
            semanticClass = UgcpTruthEventSemanticClass.AUTHORITATIVE_STATE_TRANSITION
        ),
        UgcpTruthEventAlignment(
            androidSignal = "RuntimeController.hostSessionSnapshot update",
            canonicalTruthEventSemantic = "attached_runtime_session_truth_updated",
            semanticClass = UgcpTruthEventSemanticClass.AUTHORITATIVE_STATE_TRANSITION
        ),
        UgcpTruthEventAlignment(
            androidSignal = "RuntimeController.targetReadinessProjection update",
            canonicalTruthEventSemantic = "delegated_target_selection_truth_updated",
            semanticClass = UgcpTruthEventSemanticClass.AUTHORITATIVE_STATE_TRANSITION
        ),
        UgcpTruthEventAlignment(
            androidSignal = "RuntimeController.reconnectRecoveryState transition",
            canonicalTruthEventSemantic = "runtime_reconnect_recovery_truth_updated",
            semanticClass = UgcpTruthEventSemanticClass.AUTHORITATIVE_STATE_TRANSITION
        ),
        UgcpTruthEventAlignment(
            androidSignal = "task_result|command_result|goal_result|goal_execution_result",
            canonicalTruthEventSemantic = "execution_terminal_truth_reported",
            semanticClass = UgcpTruthEventSemanticClass.AUTHORITATIVE_RESULT_REPORT
        ),
        UgcpTruthEventAlignment(
            androidSignal = "RuntimeController.takeoverFailure emission",
            canonicalTruthEventSemantic = "transfer_fallback_notified",
            semanticClass = UgcpTruthEventSemanticClass.OBSERVATIONAL_EVENT_EMISSION
        ),
        UgcpTruthEventAlignment(
            androidSignal = "delegated_execution_signal.signal_kind=ack|progress|result",
            canonicalTruthEventSemantic = "delegated_execution_lifecycle_notified",
            semanticClass = UgcpTruthEventSemanticClass.OBSERVATIONAL_EVENT_EMISSION
        ),
        UgcpTruthEventAlignment(
            androidSignal = "mesh_result.status in {success,partial,error}",
            canonicalTruthEventSemantic = "coordination_execution_terminal_reported",
            semanticClass = UgcpTruthEventSemanticClass.AUTHORITATIVE_RESULT_REPORT
        )
    )

    fun familyFor(type: MsgType): UgcpSchemaFamily? = messageFamilyAlignments[type]
}
