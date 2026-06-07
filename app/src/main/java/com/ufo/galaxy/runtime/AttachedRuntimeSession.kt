package com.ufo.galaxy.runtime

import java.util.UUID

/**
 * **Canonical Android attached-runtime session** (PR-7, post-#533 dual-repo runtime
 * unification master plan — Android Attached-Runtime Session Semantics).
 *
 * Models the *stable, persistent attachment relationship* between this Android device
 * and the cross-device runtime, formed once cross-device participation is enabled and
 * the gateway connection succeeds.
 *
 * ## Key design intent
 *
 * Before PR-7 the Android side only knew whether the WebSocket was connected
 * ([RuntimeController.RuntimeState.Active]).  That state does not distinguish:
 *  - Ordinary connection/presence (WS is up but cross-device runtime participation is
 *    not actively requested or authorised).
 *  - **Explicit cross-device attached runtime participation** — the device has been
 *    enabled as a persistent runtime surface that the main orchestrator can use for
 *    delegated agent execution and session continuation.
 *
 * [AttachedRuntimeSession] fills that gap.  A session is created exactly once per
 * successful cross-device activation (not per WS reconnect) and persists until one of
 * the four explicit termination causes ends it.  Callers can observe
 * [RuntimeController.attachedSession] as a [kotlinx.coroutines.flow.StateFlow] to
 * react to session lifecycle transitions.
 *
 * ## Session reuse across multiple delegated tasks (PR-14)
 *
 * A single [AttachedRuntimeSession] is designed to serve **multiple successive delegated
 * task executions** without being re-created between tasks.  Each accepted delegated unit
 * increments [delegatedExecutionCount] (via [withExecutionAccepted]) so both the local
 * Android side and the host can observe how many tasks have flowed through this session.
 *
 * The host must **not** assume that one task = one session.  Reuse is valid as long as
 * [isReuseValid] returns `true` (i.e. [state] == [State.ATTACHED]).  Once any of the five
 * termination causes fires — [DetachCause.EXPLICIT_DETACH], [DetachCause.DISCONNECT],
 * [DetachCause.DISABLE], [DetachCause.INVALIDATION], or [DetachCause.NORMAL] — [isReuseValid]
 * becomes `false` and no further delegated units should be directed at this session.
 *
 * ## Lifecycle
 *
 * ```
 * cross-device ON + WS connected
 *         │
 *         ▼
 *    ┌─────────┐   beginDetaching(cause)   ┌───────────┐   detachedWith(cause)   ┌──────────┐
 *    │ ATTACHED │ ────────────────────────▶│ DETACHING │ ──────────────────────▶ │ DETACHED │
 *    └─────────┘                           └───────────┘                          └──────────┘
 *         │                                                                             ▲
 *         └────────────────── detachedWith(cause) (abrupt) ────────────────────────────┘
 * ```
 *
 * Five termination causes are recognised:
 *  - [DetachCause.EXPLICIT_DETACH]  — operator or user explicitly detached this device.
 *  - [DetachCause.DISCONNECT]       — underlying WebSocket connection was lost.
 *  - [DetachCause.DISABLE]          — cross-device was disabled (user toggled off).
 *  - [DetachCause.INVALIDATION]     — session invalidated (auth expired, identity change, etc.).
 *  - [DetachCause.NORMAL]           — normal lifecycle transition with no fault condition.
 *
 * ## Immutability
 * [AttachedRuntimeSession] is immutable.  Use [beginDetaching] and [detachedWith] to
 * produce updated copies without mutating the original.
 *
 * ## Obtaining an instance
 * Use the [create] factory:
 * ```kotlin
 * val session = AttachedRuntimeSession.create(
 *     hostId   = runtimeHostDescriptor.hostId,
 *     deviceId = runtimeHostDescriptor.deviceId
 * )
 * ```
 *
 * @property sessionId               Stable UUID identifying this specific attachment session.
 *                                   A new [sessionId] is generated for each new attach event;
 *                                   it remains fixed across any state transitions of this session.
 * @property hostId                  Stable runtime-host identifier from [RuntimeHostDescriptor.hostId].
 * @property deviceId                Hardware device identifier (e.g. `Build.MANUFACTURER + "_" + Build.MODEL`).
 * @property attachedAtMs            Epoch-millisecond timestamp when this session was created.
 * @property state                   Current lifecycle state of this session.
 * @property delegatedExecutionCount Running count of delegated tasks accepted under this session.
 *                                   Incremented via [withExecutionAccepted] each time an inbound
 *                                   [com.ufo.galaxy.agent.DelegatedRuntimeUnit] is accepted.
 *                                   Starts at `0` for a freshly created session.
 * @property detachCause             Cause of detachment; `null` while [state] is [State.ATTACHED]
 *                                   or [State.DETACHING] (before [detachedWith] is called).
 * @property detachedAtMs            Epoch-millisecond timestamp of full detachment;
 *                                   `null` while [state] is [State.ATTACHED] or [State.DETACHING].
 */
data class AttachedRuntimeSession(
    val sessionId: String,
    val hostId: String,
    val deviceId: String,
    val attachedAtMs: Long = System.currentTimeMillis(),
    val state: State = State.ATTACHED,
    val delegatedExecutionCount: Int = 0,
    val detachCause: DetachCause? = null,
    val detachedAtMs: Long? = null
) {

    // ── Enums ─────────────────────────────────────────────────────────────────

    /**
     * Lifecycle state of this attached-runtime session.
     *
     * @property wireValue Stable lowercase string used in JSON payloads and logging.
     */
    enum class State(val wireValue: String) {

        /**
         * Actively participating as a persistent runtime surface.
         *
         * The main orchestrator may dispatch delegated agents or task continuations to
         * this device.  New tasks may be accepted.
         */
        ATTACHED("attached"),

        /**
         * Graceful shutdown in progress.
         *
         * No new tasks should be accepted.  In-flight work may complete before the
         * session transitions to [DETACHED].  The [AttachedRuntimeSession.detachCause]
         * field is set during this transition so observers can react to the impending
         * termination before it is final.
         */
        DETACHING("detaching"),

        /**
         * No longer participating in the runtime.
         *
         * The [AttachedRuntimeSession.detachCause] field records why participation ended.
         * A new [AttachedRuntimeSession] must be created (via [create]) when cross-device
         * participation is re-enabled.
         */
        DETACHED("detached");

        companion object {
            /**
             * Parses [value] to a [State], returning [DEFAULT] for unknown or null values.
             *
             * @param value Wire string from a JSON payload; may be null.
             */
            fun fromValue(value: String?): State =
                entries.firstOrNull { it.wireValue == value } ?: DEFAULT

            /** Default state for a freshly created session. */
            val DEFAULT: State = ATTACHED
        }
    }

    /**
     * Cause of detachment when a session transitions out of [State.ATTACHED].
     *
     * @property wireValue Stable lowercase string used in JSON payloads and logging.
     */
    enum class DetachCause(val wireValue: String) {

        /**
         * The operator or user explicitly requested detachment of this device from
         * the cross-device runtime.  This is the cleanest form of session termination;
         * no automatic reconnect is expected.
         */
        EXPLICIT_DETACH("explicit_detach"),

        /**
         * The underlying WebSocket connection was lost (network interruption,
         * server restart, etc.).  A new session may be established when the
         * connection is restored, depending on application policy.
         */
        DISCONNECT("disconnect"),

        /**
         * Cross-device participation was administratively disabled — e.g. the user
         * toggled off the cross-device switch.  The session is terminated immediately.
         */
        DISABLE("disable"),

        /**
         * The session was invalidated due to a state inconsistency: host identity
         * change, authentication expiry, protocol version mismatch, or any other
         * condition that makes the current session no longer trustworthy.
         */
        INVALIDATION("invalidation"),

        /**
         * The session ended through a normal, expected lifecycle transition — not due
         * to a network fault, administrative disable, or identity invalidation.
         *
         * Use this cause when the session is closed as part of ordinary operation
         * (e.g., the participation era completed cleanly and no reconnect is needed).
         */
        NORMAL("normal");

        companion object {
            /**
             * Parses [value] to a [DetachCause], returning `null` for unknown or null values.
             *
             * @param value Wire string from a JSON payload; may be null.
             */
            fun fromValue(value: String?): DetachCause? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * `true` when this session is in [State.ATTACHED] — i.e. actively participating
     * as a persistent runtime surface.
     *
     * A session in [State.DETACHING] is **not** considered attached; callers must not
     * submit new tasks to a [State.DETACHING] or [State.DETACHED] session.
     */
    val isAttached: Boolean
        get() = state == State.ATTACHED

    /**
     * Alias that makes runtime-layer semantics explicit for convergence work.
     *
     * This value identifies a RuntimeAttachmentSession and must not be confused with
     * conversation/history session IDs.
     */
    val runtimeAttachmentSessionId: String
        get() = sessionId

    /**
     * `true` when the host may safely direct additional delegated tasks to this session.
     *
     * Reuse is valid for as long as [state] remains [State.ATTACHED].  Once any
     * termination cause fires (detach / disconnect / disable / invalidation) this
     * returns `false` and no further delegated units should be accepted under this
     * session — a new [AttachedRuntimeSession] must be created if the host reconnects.
     *
     * Semantically equivalent to [isAttached]; exposed under a distinct name so
     * call-sites that are reasoning about multi-task reuse communicate their intent
     * clearly rather than conflating liveness with reuse eligibility.
     */
    val isReuseValid: Boolean
        get() = isAttached

    /**
     * `true` when this session has fully terminated ([State.DETACHED]).
     */
    val isDetached: Boolean
        get() = state == State.DETACHED

    /**
     * Wall-clock duration of this session in milliseconds.
     *
     * While [state] is [State.ATTACHED] or [State.DETACHING], returns the elapsed time
     * from [attachedAtMs] to now.  Once [state] is [State.DETACHED], returns the exact
     * session duration using [detachedAtMs].
     */
    val durationMs: Long
        get() = (detachedAtMs ?: System.currentTimeMillis()) - attachedAtMs

    /**
     * Produces a copy of this session with [delegatedExecutionCount] incremented by one.
     *
     * Call this each time the session accepts an inbound delegated task (i.e. a
     * [com.ufo.galaxy.agent.DelegatedRuntimeUnit] is accepted by
     * [com.ufo.galaxy.agent.DelegatedRuntimeReceiver]).  The increment happens on the
     * Android side so both local telemetry and the host's reconciliation logic can
     * observe how many tasks have been dispatched through this session lifetime.
     *
     * This method is a no-op guard: if [state] is not [State.ATTACHED] the increment is
     * still applied to the copy (so callers need not gate on state themselves), but
     * [isReuseValid] will remain `false` and the caller should not be routing new tasks
     * through a non-ATTACHED session in the first place.
     *
     * @return A new [AttachedRuntimeSession] with [delegatedExecutionCount] == old + 1;
     *         all other fields are unchanged.
     */
    fun withExecutionAccepted(): AttachedRuntimeSession =
        copy(delegatedExecutionCount = delegatedExecutionCount + 1)

    /**
     * Produces a copy of this session with [state] set to [State.DETACHING] and
     * [detachCause] set to [cause].
     *
     * Used to signal the beginning of a graceful shutdown sequence.  Callers that do
     * not need a graceful drain phase may skip this and call [detachedWith] directly.
     *
     * @param cause The reason this session is beginning to detach.
     * @return A new [AttachedRuntimeSession] in [State.DETACHING]; the original is unchanged.
     */
    fun beginDetaching(cause: DetachCause): AttachedRuntimeSession =
        copy(state = State.DETACHING, detachCause = cause)

    /**
     * Produces a copy of this session fully transitioned to [State.DETACHED].
     *
     * May be called directly from [State.ATTACHED] (abrupt termination, e.g.
     * [DetachCause.DISCONNECT]) or from [State.DETACHING] (graceful shutdown
     * completion).  If [detachCause] is already set by a prior call to [beginDetaching],
     * [cause] takes precedence (allowing the final cause to differ from the pending one).
     *
     * @param cause        The reason this session is detaching; becomes [detachCause].
     * @param timestampMs  Epoch-millisecond timestamp for [detachedAtMs]; defaults to now.
     * @return A new [AttachedRuntimeSession] in [State.DETACHED]; the original is unchanged.
     */
    fun detachedWith(
        cause: DetachCause,
        timestampMs: Long = System.currentTimeMillis()
    ): AttachedRuntimeSession = copy(
        state = State.DETACHED,
        detachCause = cause,
        detachedAtMs = timestampMs
    )

    /**
     * Builds the canonical **host-facing** metadata map for wire transmission or
     * diagnostic logging.
     *
     * This map is the authoritative representation of session reuse state that the host
     * (main orchestrator) can consume to determine whether this Android session is still
     * a valid target for delegated dispatch.  All fields required for host-side reuse
     * binding are guaranteed to be present (PR-17).
     *
     * Keys always present:
     *  - [KEY_SESSION_ID]                  — stable session identifier.
     *  - [KEY_HOST_ID]                     — runtime host identifier.
     *  - [KEY_DEVICE_ID]                   — hardware device identifier.
     *  - [KEY_STATE]                       — [State.wireValue] of the current state.
     *  - [KEY_ATTACHED_AT_MS]              — epoch-ms attach timestamp.
     *  - [KEY_DELEGATED_EXECUTION_COUNT]   — number of delegated tasks accepted so far.
     *  - [KEY_IS_REUSE_VALID]              — `true` iff [isReuseValid]; stable boolean for
     *                                        host-facing consumption without re-deriving state.
     *
     * Keys conditionally present:
     *  - [KEY_DETACH_CAUSE]                — [DetachCause.wireValue]; **absent** when
     *                                        [detachCause] is `null` (session is still active).
     *
     * @return An immutable [Map] suitable for merging into AIP v3 metadata payloads.
     */
    fun toMetadataMap(): Map<String, Any> = buildMap {
        put(KEY_SESSION_ID, sessionId)
        put(KEY_HOST_ID, hostId)
        put(KEY_DEVICE_ID, deviceId)
        put(KEY_STATE, state.wireValue)
        put(KEY_ATTACHED_AT_MS, attachedAtMs)
        put(KEY_DELEGATED_EXECUTION_COUNT, delegatedExecutionCount)
        put(KEY_IS_REUSE_VALID, isReuseValid)
        detachCause?.let { put(KEY_DETACH_CAUSE, it.wireValue) }
    }

    // ── Factory / companion ───────────────────────────────────────────────────

    companion object {

        // ── Metadata key constants ────────────────────────────────────────────

        /** Metadata key for the unique session identifier. */
        const val KEY_SESSION_ID = "attached_session_id"

        /** Metadata key for the runtime host identifier. */
        const val KEY_HOST_ID = "attached_session_host_id"

        /** Metadata key for the [State.wireValue] string. */
        const val KEY_STATE = "attached_session_state"

        /** Metadata key for the epoch-ms attach timestamp. */
        const val KEY_ATTACHED_AT_MS = "attached_session_attached_at_ms"

        /** Metadata key for the [delegatedExecutionCount] integer. */
        const val KEY_DELEGATED_EXECUTION_COUNT = "attached_session_delegated_execution_count"

        /**
         * Metadata key for the hardware device identifier ([deviceId]).
         *
         * Always present in [toMetadataMap] output so the host can identify which physical
         * device owns this session without additional look-up (PR-17).
         */
        const val KEY_DEVICE_ID = "attached_session_device_id"

        /**
         * Metadata key for the [isReuseValid] boolean.
         *
         * Always present in [toMetadataMap] output.  `true` when the session is in
         * [State.ATTACHED]; `false` in [State.DETACHING] or [State.DETACHED].
         * Provided as a pre-computed boolean so the host does not need to re-derive
         * reuse eligibility from [KEY_STATE] (PR-17).
         */
        const val KEY_IS_REUSE_VALID = "attached_session_is_reuse_valid"

        /**
         * Metadata key for the [DetachCause.wireValue] string.
         * This key is **absent** from [toMetadataMap] output when [detachCause] is null.
         */
        const val KEY_DETACH_CAUSE = "attached_session_detach_cause"

        /**
         * Creates a new [AttachedRuntimeSession] in [State.ATTACHED] for the given host.
         *
         * The session starts immediately; [attachedAtMs] is set to the current time.
         *
         * @param hostId    Stable runtime-host identifier from [RuntimeHostDescriptor.hostId].
         *                  Pass an empty string when no [RuntimeHostDescriptor] is available
         *                  (e.g. in tests).
         * @param deviceId  Hardware device identifier (e.g. `Build.MANUFACTURER + "_" + Build.MODEL`).
         * @param sessionId Stable per-session UUID; auto-generated (UUID v4) if not supplied.
         * @return A new [AttachedRuntimeSession] in [State.ATTACHED].
         */
        fun create(
            hostId: String,
            deviceId: String,
            sessionId: String = UUID.randomUUID().toString()
        ): AttachedRuntimeSession = AttachedRuntimeSession(
            sessionId = sessionId,
            hostId = hostId,
            deviceId = deviceId,
            attachedAtMs = System.currentTimeMillis(),
            state = State.ATTACHED,
            detachCause = null,
            detachedAtMs = null
        )
    }
}
