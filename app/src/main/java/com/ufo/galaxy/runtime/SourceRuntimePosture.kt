package com.ufo.galaxy.runtime

/**
 * Canonical source-device runtime posture values, mirroring the server-side
 * `source_runtime_posture` semantics introduced in the main repo's PR #533.
 *
 * ## Semantics
 * - [CONTROL_ONLY] – The source device acts purely as a control/initiator endpoint.
 *   It sends instructions but does **not** join the runtime execution pool for the
 *   current task. The source will not be allocated subtasks, will not contribute to
 *   parallel result sets, and will not be a merge target.
 *
 * - [JOIN_RUNTIME] – The source device also participates as a runtime executor for
 *   the current task. It may receive subtasks, contribute to parallel groups, and be
 *   included in result merge operations alongside other registered runtime hosts.
 *
 * ## Wire representation
 * On the wire (JSON / AIP v3) this field travels as the string constant
 * [CONTROL_ONLY] (`"control_only"`) or [JOIN_RUNTIME] (`"join_runtime"`).
 * Always use [fromValue] when parsing an inbound value to ensure safe unknown-value
 * handling; use [DEFAULT] when the field is absent from a legacy payload.
 *
 * ## Relationship to exec_mode
 * `source_runtime_posture` is orthogonal to `exec_mode`:
 *  - `exec_mode` expresses **where** a task executes (local / remote / both).
 *  - `source_runtime_posture` expresses **whether the source device itself joins the
 *    runtime** for the current task, regardless of where execution ultimately lands.
 */
object SourceRuntimePosture {

    /** Source device only controls/initiates; it does not join the runtime execution pool. */
    const val CONTROL_ONLY = "control_only"

    /** Source device joins the runtime and may participate as an executor for this task. */
    const val JOIN_RUNTIME = "join_runtime"

    /**
     * Safe default when `source_runtime_posture` is absent from a legacy payload.
     * Defaults to [CONTROL_ONLY] — the historically implicit behaviour before PR #533.
     */
    const val DEFAULT = CONTROL_ONLY

    /** All valid posture values, for validation and documentation purposes. */
    val ALL_VALUES: Set<String> = setOf(CONTROL_ONLY, JOIN_RUNTIME)

    /**
     * Parses [value] to a canonical posture string.
     *
     * Returns the validated canonical string if [value] is a known posture value,
     * or [DEFAULT] when [value] is `null`, blank, or unrecognised.
     * This ensures backwards-safe handling of future or unknown values.
     *
     * @param value Raw posture string from a JSON payload or session context.
     * @return A canonical posture string — always either [CONTROL_ONLY] or [JOIN_RUNTIME].
     */
    fun fromValue(value: String?): String =
        if (value != null && value in ALL_VALUES) value else DEFAULT

    /**
     * Returns `true` when [posture] is [JOIN_RUNTIME], meaning the source device is
     * eligible to participate as a runtime executor for the current task.
     */
    fun isJoinRuntime(posture: String?): Boolean = posture == JOIN_RUNTIME

    /**
     * Returns `true` when [posture] is [CONTROL_ONLY] (including the safe-default
     * case where [posture] is `null` or unrecognised).
     */
    fun isControlOnly(posture: String?): Boolean = !isJoinRuntime(posture)
}
