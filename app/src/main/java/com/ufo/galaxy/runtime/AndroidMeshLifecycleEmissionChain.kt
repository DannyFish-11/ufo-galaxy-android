package com.ufo.galaxy.runtime

/**
 * Canonical Android-side mesh lifecycle emission state for a single mesh task session.
 *
 * This keeps join/result/leave emission as explicit local runtime truth so callers can
 * close lifecycle state consistently across success/failure/early-exit paths.
 */
object AndroidMeshLifecycleEmissionChain {

    enum class Phase(val wireValue: String) {
        CREATED("created"),
        JOIN_ATTEMPTED("join_attempted"),
        JOIN_EMITTED("join_emitted"),
        RESULT_ATTEMPTED("result_attempted"),
        RESULT_EMITTED("result_emitted"),
        LEAVE_ATTEMPTED("leave_attempted"),
        CLOSED("closed")
    }

    data class SessionState(
        val meshId: String,
        val taskId: String,
        val phase: Phase = Phase.CREATED,
        val joinAttempted: Boolean = false,
        val joinEmitted: Boolean = false,
        val resultAttempted: Boolean = false,
        val resultEmitted: Boolean = false,
        val leaveAttempted: Boolean = false,
        val leaveEmitted: Boolean = false,
        val leaveReason: String? = null
    ) {
        val shouldAttemptLeave: Boolean
            get() = joinAttempted && !leaveAttempted

        fun toWireMap(): Map<String, Any?> = mapOf(
            "mesh_id" to meshId,
            "task_id" to taskId,
            "mesh_lifecycle_phase" to phase.wireValue,
            "mesh_join_attempted" to joinAttempted,
            "mesh_join_emitted" to joinEmitted,
            "mesh_result_attempted" to resultAttempted,
            "mesh_result_emitted" to resultEmitted,
            "mesh_leave_attempted" to leaveAttempted,
            "mesh_leave_emitted" to leaveEmitted,
            "mesh_leave_reason" to leaveReason
        )
    }

    fun create(meshId: String, taskId: String): SessionState = SessionState(meshId = meshId, taskId = taskId)

    fun onJoin(state: SessionState, emitted: Boolean): SessionState = state.copy(
        phase = if (emitted) Phase.JOIN_EMITTED else Phase.JOIN_ATTEMPTED,
        joinAttempted = true,
        joinEmitted = emitted
    )

    fun onResult(state: SessionState, emitted: Boolean): SessionState = state.copy(
        phase = if (emitted) Phase.RESULT_EMITTED else Phase.RESULT_ATTEMPTED,
        resultAttempted = true,
        resultEmitted = emitted
    )

    fun onLeave(state: SessionState, emitted: Boolean, reason: String): SessionState = state.copy(
        phase = if (emitted) Phase.CLOSED else Phase.LEAVE_ATTEMPTED,
        leaveAttempted = true,
        leaveEmitted = emitted,
        leaveReason = reason
    )
}
