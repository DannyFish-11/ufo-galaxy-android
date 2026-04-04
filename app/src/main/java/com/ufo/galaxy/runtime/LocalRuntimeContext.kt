package com.ufo.galaxy.runtime

/**
 * Carries the runtime-session context for a single Android-local task execution.
 *
 * [LocalRuntimeContext] is the Android-side analogue of the server-side runtime
 * session snapshot: it exposes the fields that local task/session/cross-device code
 * needs to make posture-aware decisions **without** conflating them with unrelated
 * settings such as exec_mode or capability flags.
 *
 * ## Key field: source_runtime_posture
 * Adopted from the main repo's PR #533 contract. Tells the local execution chain
 * whether the device that originated this task intends to participate as a runtime
 * executor (`"join_runtime"`) or only as a control/initiator (`"control_only"`).
 *
 * This value is set once at the start of a task and remains fixed for the lifetime
 * of that execution context. It must not be mutated mid-execution.
 *
 * ## Obtaining an instance
 * Build an instance via the [Builder] DSL or the [of] factory when all fields are
 * known at once:
 * ```kotlin
 * val ctx = LocalRuntimeContext.of(
 *     taskId          = "task-abc",
 *     sessionId       = "sess-xyz",
 *     sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
 * )
 * ```
 *
 * @param taskId               Unique identifier of the task being executed locally.
 * @param sessionId            Session-level grouping identifier (may be null for standalone tasks).
 * @param sourceRuntimePosture Canonical source-device participation posture for this task.
 *                             Use [SourceRuntimePosture.fromValue] to parse inbound values safely.
 *                             Defaults to [SourceRuntimePosture.DEFAULT] (`"control_only"`).
 * @param traceId              Optional end-to-end trace identifier propagated from the
 *                             originating message. Used for log correlation only.
 * @param deviceRole           Optional logical device role (e.g. `"phone"`, `"tablet"`).
 */
data class LocalRuntimeContext(
    val taskId: String,
    val sessionId: String? = null,
    val sourceRuntimePosture: String = SourceRuntimePosture.DEFAULT,
    val traceId: String? = null,
    val deviceRole: String? = null
) {

    /**
     * Returns `true` when [sourceRuntimePosture] is [SourceRuntimePosture.JOIN_RUNTIME],
     * meaning this device is an eligible participant in the runtime execution pool for
     * the current task.
     */
    val isJoinRuntime: Boolean get() = SourceRuntimePosture.isJoinRuntime(sourceRuntimePosture)

    /**
     * Returns `true` when [sourceRuntimePosture] is [SourceRuntimePosture.CONTROL_ONLY]
     * (or any unknown / null-equivalent value), meaning this device acts only as a
     * control/initiator and must not be allocated subtasks or counted as a result contributor.
     */
    val isControlOnly: Boolean get() = SourceRuntimePosture.isControlOnly(sourceRuntimePosture)

    // ── Factory / DSL ─────────────────────────────────────────────────────────

    companion object {

        /**
         * Convenience factory that creates a [LocalRuntimeContext] with [sourceRuntimePosture]
         * safely normalised via [SourceRuntimePosture.fromValue].
         *
         * Callers should prefer this factory over the data-class constructor when the posture
         * value originates from an external JSON payload, to guarantee unknown values are
         * resolved to the safe default.
         *
         * @param taskId               Unique task identifier.
         * @param sessionId            Optional session identifier.
         * @param sourceRuntimePosture Raw posture string from the inbound payload; may be null.
         * @param traceId              Optional trace identifier for log correlation.
         * @param deviceRole           Optional device role label.
         */
        fun of(
            taskId: String,
            sessionId: String? = null,
            sourceRuntimePosture: String? = null,
            traceId: String? = null,
            deviceRole: String? = null
        ): LocalRuntimeContext = LocalRuntimeContext(
            taskId = taskId,
            sessionId = sessionId,
            sourceRuntimePosture = SourceRuntimePosture.fromValue(sourceRuntimePosture),
            traceId = traceId,
            deviceRole = deviceRole
        )

        /**
         * Returns a minimal [LocalRuntimeContext] with safe defaults — useful for legacy
         * call-sites that do not yet supply posture information.
         *
         * @param taskId Unique task identifier.
         */
        fun defaultFor(taskId: String): LocalRuntimeContext = LocalRuntimeContext(taskId = taskId)
    }
}
