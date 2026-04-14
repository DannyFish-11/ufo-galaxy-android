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

object UgcpSharedSchemaAlignment {

    val identityAlignments: List<UgcpIdentityAlignment> = listOf(
        UgcpIdentityAlignment("TaskId", "task_id"),
        UgcpIdentityAlignment("TraceId", "trace_id"),
        UgcpIdentityAlignment("ControlSessionId", "session_id"),
        UgcpIdentityAlignment("RuntimeSessionId", "runtime_session_id"),
        UgcpIdentityAlignment("MeshSessionId", "mesh_id"),
        UgcpIdentityAlignment("NodeId(source)", "device_id"),
        UgcpIdentityAlignment("NodeId(target)", "routing context / takeover target"),
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

    val readinessCapabilityTerms: Set<String> = setOf(
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

    val meshTerms: Set<String> = setOf(
        "mesh_join",
        "mesh_leave",
        "mesh_result"
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

    fun familyFor(type: MsgType): UgcpSchemaFamily? = messageFamilyAlignments[type]
}
