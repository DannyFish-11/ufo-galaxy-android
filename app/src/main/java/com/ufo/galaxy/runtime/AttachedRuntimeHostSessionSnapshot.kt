package com.ufo.galaxy.runtime

/**
 * **Canonical attached runtime host-session snapshot / projection** (PR-19, post-#533
 * dual-repo runtime unification master plan — Canonical Attached Runtime Host-Session
 * Snapshot Projection, Android side).
 *
 * [AttachedRuntimeHostSessionSnapshot] is the authoritative, semantically stable output
 * envelope that the Android attached runtime emits to the main-repository authoritative
 * session registry.  It collapses all relevant runtime-session state into a single,
 * field-consistent, state-transition-coherent record so that the host registry can
 * consume Android session truth without inferring state from scattered fields.
 *
 * ## Why a separate snapshot type is needed
 *
 * [AttachedRuntimeSession] + [RuntimeHostDescriptor] together hold all necessary runtime
 * state, but their fields are spread across two models that evolve independently.  The
 * host registry needs a **stable, versioned projection** whose field set and state
 * semantics are guaranteed not to change between attach / detach / reconnect / invalidate
 * events — only the values change, never the keys.
 *
 * ## Nine canonical fields
 *
 * Every snapshot always contains all nine fields.  [invalidationReason] is the sole
 * conditional field — it is non-`null` only when [attachmentState] is
 * `"detached"` and the session was closed via
 * [AttachedRuntimeSession.DetachCause.INVALIDATION].
 *
 * | Field                    | Wire key                              | Always present? |
 * |--------------------------|---------------------------------------|-----------------|
 * | [sessionId]              | [KEY_SESSION_ID]                      | yes             |
 * | [deviceId]               | [KEY_DEVICE_ID]                       | yes             |
 * | [runtimeSessionId]       | [KEY_RUNTIME_SESSION_ID]              | yes             |
 * | [attachmentState]        | [KEY_ATTACHMENT_STATE]                | yes             |
 * | [isReuseValid]           | [KEY_IS_REUSE_VALID]                  | yes             |
 * | [delegatedExecutionCount]| [KEY_DELEGATED_EXECUTION_COUNT]       | yes             |
 * | [invalidationReason]     | [KEY_INVALIDATION_REASON]             | conditional     |
 * | [hostRole]               | [KEY_HOST_ROLE]                       | yes             |
 * | [posture]                | [KEY_POSTURE]                         | yes             |
 *
 * ## State-transition semantics
 *
 * The projection is designed so that the host can perform correct session-lifecycle
 * decisions purely from these nine fields, for all four transition scenarios:
 *
 * - **attach** → [attachmentState]`=attached`, [isReuseValid]`=true`,
 *   [invalidationReason]`=null`, [posture]`=join_runtime`
 * - **detach** → [attachmentState]`=detached`, [isReuseValid]`=false`,
 *   [invalidationReason]`=null`, [posture]`=control_only`
 * - **reconnect** → new [runtimeSessionId], [attachmentState]`=attached`,
 *   [isReuseValid]`=true`, [posture]`=join_runtime`
 * - **invalidate** → [attachmentState]`=detached`, [isReuseValid]`=false`,
 *   [invalidationReason]`="invalidation"`, [posture]`=control_only`
 *
 * ## Obtaining an instance
 *
 * Use [RuntimeController.currentHostSessionSnapshot]; do not construct directly.
 *
 * @property sessionId                Stable UUID identifying this [AttachedRuntimeSession].
 *                                    New for each attach event; constant across transitions.
 * @property deviceId                 Hardware device identifier from [RuntimeHostDescriptor.deviceId].
 * @property runtimeSessionId         Per-connection UUID, regenerated each time a new
 *                                    [AttachedRuntimeSession] is opened by [RuntimeController].
 *                                    Distinct from [sessionId]; useful for distinguishing
 *                                    reconnect events on the host side.
 * @property attachmentState          [AttachedRuntimeSession.State.wireValue] of the current state.
 * @property isReuseValid             `true` when [attachmentState]`==attached`; equivalent to
 *                                    [AttachedRuntimeSession.isReuseValid].
 * @property delegatedExecutionCount  Running count of delegated tasks accepted under this session.
 * @property invalidationReason       Non-`null` only when the session was closed via
 *                                    [AttachedRuntimeSession.DetachCause.INVALIDATION].
 *                                    Contains the [AttachedRuntimeSession.DetachCause.wireValue].
 * @property hostRole                 [RuntimeHostDescriptor.FormationRole.wireValue] of this host.
 * @property posture                  [SourceRuntimePosture.JOIN_RUNTIME] when [isReuseValid];
 *                                    [SourceRuntimePosture.CONTROL_ONLY] otherwise.
 */
data class AttachedRuntimeHostSessionSnapshot(
    val sessionId: String,
    val deviceId: String,
    val runtimeSessionId: String,
    val attachmentState: String,
    val isReuseValid: Boolean,
    val delegatedExecutionCount: Int,
    val invalidationReason: String?,
    val hostRole: String,
    val posture: String
) {

    // ── Serialisation ─────────────────────────────────────────────────────────

    /**
     * Builds the canonical wire map for this snapshot.
     *
     * All nine keys are always present in the returned map, with the sole exception that
     * [KEY_INVALIDATION_REASON] is included **only** when [invalidationReason] is non-`null`.
     * This keeps the wire format lean for the common non-invalidation case while giving the
     * host a stable, discoverable key for invalidation scenarios.
     *
     * @return An immutable [Map] suitable for merging into AIP v3 metadata payloads or for
     *         transmission to the main-repo authoritative session registry.
     */
    fun toMap(): Map<String, Any> = buildMap {
        put(KEY_SESSION_ID, sessionId)
        put(KEY_DEVICE_ID, deviceId)
        put(KEY_RUNTIME_SESSION_ID, runtimeSessionId)
        put(KEY_ATTACHMENT_STATE, attachmentState)
        put(KEY_IS_REUSE_VALID, isReuseValid)
        put(KEY_DELEGATED_EXECUTION_COUNT, delegatedExecutionCount)
        invalidationReason?.let { put(KEY_INVALIDATION_REASON, it) }
        put(KEY_HOST_ROLE, hostRole)
        put(KEY_POSTURE, posture)
    }

    // ── Companion / factory ───────────────────────────────────────────────────

    companion object {

        // ── Wire key constants ────────────────────────────────────────────────

        /** Wire key for [sessionId]. */
        const val KEY_SESSION_ID = "snapshot_session_id"

        /** Wire key for [deviceId]. */
        const val KEY_DEVICE_ID = "snapshot_device_id"

        /** Wire key for [runtimeSessionId]. */
        const val KEY_RUNTIME_SESSION_ID = "snapshot_runtime_session_id"

        /** Wire key for [attachmentState] ([AttachedRuntimeSession.State.wireValue]). */
        const val KEY_ATTACHMENT_STATE = "snapshot_attachment_state"

        /** Wire key for [isReuseValid]. */
        const val KEY_IS_REUSE_VALID = "snapshot_is_reuse_valid"

        /** Wire key for [delegatedExecutionCount]. */
        const val KEY_DELEGATED_EXECUTION_COUNT = "snapshot_delegated_execution_count"

        /**
         * Wire key for [invalidationReason].
         * Present in [toMap] output **only** when [invalidationReason] is non-`null`.
         */
        const val KEY_INVALIDATION_REASON = "snapshot_invalidation_reason"

        /** Wire key for [hostRole] ([RuntimeHostDescriptor.FormationRole.wireValue]). */
        const val KEY_HOST_ROLE = "snapshot_host_role"

        /** Wire key for [posture] ([SourceRuntimePosture] string). */
        const val KEY_POSTURE = "snapshot_posture"

        // ── All nine required keys — useful in tests ──────────────────────────

        /**
         * The eight keys that are **always** present in [toMap] output, regardless of
         * session state.  [KEY_INVALIDATION_REASON] is excluded because it is conditional.
         */
        val ALWAYS_PRESENT_KEYS: Set<String> = setOf(
            KEY_SESSION_ID,
            KEY_DEVICE_ID,
            KEY_RUNTIME_SESSION_ID,
            KEY_ATTACHMENT_STATE,
            KEY_IS_REUSE_VALID,
            KEY_DELEGATED_EXECUTION_COUNT,
            KEY_HOST_ROLE,
            KEY_POSTURE
        )

        // ── Factory ───────────────────────────────────────────────────────────

        /**
         * Builds an [AttachedRuntimeHostSessionSnapshot] from the current state of
         * [session] and the supplied [runtimeSessionId] / [hostRole].
         *
         * This is the **canonical construction path** used by [RuntimeController].
         * Direct callers outside [RuntimeController] should use
         * [RuntimeController.currentHostSessionSnapshot] instead.
         *
         * **Projection rules applied here:**
         *  - [posture] → `join_runtime` when [AttachedRuntimeSession.isReuseValid];
         *    `control_only` otherwise.
         *  - [invalidationReason] → [AttachedRuntimeSession.DetachCause.wireValue] when
         *    [AttachedRuntimeSession.detachCause] is
         *    [AttachedRuntimeSession.DetachCause.INVALIDATION]; `null` otherwise.
         *
         * @param session          Current [AttachedRuntimeSession] to project.
         * @param runtimeSessionId Per-connection UUID managed by [RuntimeController].
         * @param hostRole         [RuntimeHostDescriptor.FormationRole.wireValue] of this host.
         * @return A fully populated [AttachedRuntimeHostSessionSnapshot].
         */
        fun from(
            session: AttachedRuntimeSession,
            runtimeSessionId: String,
            hostRole: String
        ): AttachedRuntimeHostSessionSnapshot {
            val posture = if (session.isReuseValid) {
                SourceRuntimePosture.JOIN_RUNTIME
            } else {
                SourceRuntimePosture.CONTROL_ONLY
            }
            val invalidationReason = if (
                session.detachCause == AttachedRuntimeSession.DetachCause.INVALIDATION
            ) {
                session.detachCause.wireValue
            } else {
                null
            }
            return AttachedRuntimeHostSessionSnapshot(
                sessionId = session.sessionId,
                deviceId = session.deviceId,
                runtimeSessionId = runtimeSessionId,
                attachmentState = session.state.wireValue,
                isReuseValid = session.isReuseValid,
                delegatedExecutionCount = session.delegatedExecutionCount,
                invalidationReason = invalidationReason,
                hostRole = hostRole,
                posture = posture
            )
        }
    }
}
