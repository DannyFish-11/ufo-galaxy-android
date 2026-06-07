package com.ufo.galaxy.runtime

/**
 * PR-47 — Android-side observability compatibility for runtime lifecycle, dispatch,
 * and recovery metadata.
 *
 * V2 is adding production-grade runtime observability across device lifecycle, mesh
 * session transitions, dispatch decisions, executor target typing, and
 * continuity/recovery decisions.  This object declares the Android-side vocabulary
 * and helpers that allow the device runtime to participate cleanly in the broader
 * V2 observability model without rejecting richer runtime metadata.
 *
 * ## Scope
 *
 * [RuntimeObservabilityMetadata] is a **compatibility and semantic clarity** layer.
 * Android-side responsibilities are:
 *
 * 1. **Accept** observability/tracing metadata in inbound execution contracts
 *    without failing.
 * 2. **Preserve** cross-system trace correlation identifiers (dispatch, session,
 *    recovery) so they survive the full execution chain end-to-end.
 * 3. **Emit** structured lifecycle signals through [com.ufo.galaxy.observability.GalaxyLogger]
 *    in the format expected by the V2 observability model.
 * 4. **Remain backward compatible** — all observability fields are optional; null/absent
 *    values must be treated as equivalent to the legacy contract.
 *
 * ## Field vocabulary
 *
 * ### Inbound (downlink) observability metadata fields on commands/envelopes
 *
 * | Field                    | Purpose                                                         |
 * |--------------------------|----------------------------------------------------------------|
 * | [FIELD_DISPATCH_TRACE_ID]| Identifies the end-to-end dispatch chain for cross-system log  |
 * |                          | correlation.  Should be echoed back in result payloads.         |
 * | [FIELD_LIFECYCLE_EVENT_ID]| Correlates this command with a V2 lifecycle event that          |
 * |                          | triggered the dispatch (e.g. a reconnect or session restore).   |
 * | [FIELD_SESSION_CORRELATION_ID]| Session-level correlation identifier propagated across      |
 * |                          | session transitions, handoffs, and recovery events.             |
 *
 * ### Outbound (uplink) fields echoed in result payloads
 *
 * `dispatch_trace_id` is echoed back in [GoalResultPayload] so V2 can correlate
 * results with the originating dispatch chain.  Other fields are not required in
 * the uplink direction; they may be included in structured log entries
 * ([com.ufo.galaxy.observability.GalaxyLogger]) for operator diagnostics.
 *
 * ## Log tag vocabulary (GalaxyLogger)
 *
 * | Tag constant                                              | Event                                        |
 * |-----------------------------------------------------------|----------------------------------------------|
 * | [com.ufo.galaxy.observability.GalaxyLogger.TAG_DISPATCH_DECISION]   | Dispatch decision recorded (route, target, trace) |
 * | [com.ufo.galaxy.observability.GalaxyLogger.TAG_LIFECYCLE_OBSERVE]   | Lifecycle observability event emitted to V2   |
 * | [com.ufo.galaxy.observability.GalaxyLogger.TAG_RECOVERY_OBSERVE]    | Recovery tracing event with cross-system IDs  |
 *
 * ## Backward compatibility
 *
 * All observability fields added to inbound payloads in PR-47 are optional and
 * default to `null`.  Legacy senders that do not populate these fields are handled
 * transparently by Android without requiring any changes to existing callers.
 *
 * @see GoalExecutionPayload
 * @see TaskAssignPayload
 * @see com.ufo.galaxy.protocol.AipMessage
 * @see com.ufo.galaxy.observability.GalaxyLogger
 */
object RuntimeObservabilityMetadata {

    // ── Inbound field name constants ──────────────────────────────────────────

    /**
     * Field name for the cross-system dispatch trace identifier.
     *
     * When present in an inbound command, this identifier allows the full dispatch
     * chain (from V2 orchestrator → gateway → Android) to be traced with a single ID
     * in structured log entries and V2 observability pipelines.
     *
     * Android MUST echo this value in result payloads without modification.
     * A null or blank value MUST be ignored (treated as legacy / not provided).
     */
    const val FIELD_DISPATCH_TRACE_ID = "dispatch_trace_id"

    /**
     * Field name for the V2 lifecycle event identifier associated with this dispatch.
     *
     * When V2 dispatches a command in response to a lifecycle event (e.g. a reconnect
     * triggering a recovery execution, or a session restore triggering a resumed task),
     * this field carries the identifier of the triggering V2 lifecycle event so that
     * the execution result can be correlated back to the originating lifecycle transition.
     *
     * Android handlers MUST accept this field without failure.  It does not affect
     * execution semantics; it is purely for cross-system observability correlation.
     * A null or blank value is treated as "no lifecycle event correlation".
     */
    const val FIELD_LIFECYCLE_EVENT_ID = "lifecycle_event_id"

    /**
     * Field name for the session-level correlation identifier.
     *
     * A stable identifier that is preserved across session transitions, handoffs, and
     * recovery events within the same logical session context.  Unlike
     * [FIELD_DISPATCH_TRACE_ID] (which is scoped to a single dispatch chain), this
     * identifier spans multiple dispatches within the same session context.
     *
     * Android MUST accept this field without failure.  It does not affect execution
     * semantics.  A null or blank value is treated as "no session correlation context".
     */
    const val FIELD_SESSION_CORRELATION_ID = "session_correlation_id"

    // ── Log event kind constants ──────────────────────────────────────────────

    /**
     * Log event kind for a dispatch decision event.
     *
     * Used in [com.ufo.galaxy.observability.GalaxyLogger.TAG_DISPATCH_DECISION] entries
     * to record that an execution route and target were selected for a command.
     *
     * Required fields in the log entry:
     * - `event` = `"dispatch_decision_recorded"`
     * - `task_id`
     * - `route` (execution route wire value)
     *
     * Optional fields:
     * - [FIELD_DISPATCH_TRACE_ID]
     * - `executor_target_type`
     * - `session_id`
     */
    const val EVENT_DISPATCH_DECISION_RECORDED = "dispatch_decision_recorded"

    /**
     * Log event kind for a lifecycle observability event forwarded to the V2 model.
     *
     * Used in [com.ufo.galaxy.observability.GalaxyLogger.TAG_LIFECYCLE_OBSERVE] entries
     * to record that a device lifecycle event was emitted into the V2 observability stream.
     *
     * Required fields in the log entry:
     * - `event` = `"lifecycle_observe_emitted"`
     * - `lifecycle_kind` (wire value from [LifecycleObservabilityKind])
     * - `device_id`
     *
     * Optional fields:
     * - `session_id`
     * - [FIELD_LIFECYCLE_EVENT_ID]
     * - [FIELD_SESSION_CORRELATION_ID]
     */
    const val EVENT_LIFECYCLE_OBSERVE_EMITTED = "lifecycle_observe_emitted"

    /**
     * Log event kind for a recovery tracing event.
     *
     * Used in [com.ufo.galaxy.observability.GalaxyLogger.TAG_RECOVERY_OBSERVE] entries
     * to record recovery-related execution events with full cross-system tracing context.
     *
     * Required fields in the log entry:
     * - `event` = `"recovery_observe_recorded"`
     * - `task_id`
     *
     * Optional fields:
     * - [FIELD_DISPATCH_TRACE_ID]
     * - `continuity_token`
     * - `interruption_reason`
     * - `is_resumable`
     */
    const val EVENT_RECOVERY_OBSERVE_RECORDED = "recovery_observe_recorded"

    // ── LifecycleObservabilityKind enum ───────────────────────────────────────

    /**
     * Stable classification of a device lifecycle event for V2 observability integration.
     *
     * Each value corresponds to a distinct lifecycle transition in the Android device
     * lifecycle model.  These values are used in [EVENT_LIFECYCLE_OBSERVE_EMITTED] log
     * entries and provide a stable vocabulary for V2 observability consumers.
     *
     * ## Mapping to V2MultiDeviceLifecycleEvent
     *
     * | [LifecycleObservabilityKind]   | [V2MultiDeviceLifecycleEvent] subclass     |
     * |--------------------------------|--------------------------------------------|
     * | [DEVICE_ATTACH]                | [V2MultiDeviceLifecycleEvent.DeviceConnected]    |
     * | [DEVICE_RECONNECT]             | [V2MultiDeviceLifecycleEvent.DeviceReconnected]  |
     * | [DEVICE_DETACH]                | [V2MultiDeviceLifecycleEvent.DeviceDisconnected] |
     * | [DEVICE_DEGRADED]              | [V2MultiDeviceLifecycleEvent.DeviceDegraded]     |
     * | [READINESS_CHANGED]            | [V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged] |
     * | [HEALTH_CHANGED]               | [V2MultiDeviceLifecycleEvent.DeviceHealthChanged] |
     *
     * @property wireValue Stable lowercase string for use in structured log entries and
     *                     V2 observability schema bindings.
     */
    enum class LifecycleObservabilityKind(val wireValue: String) {

        /**
         * Device attached to the cross-device runtime for the first time in an activation era.
         *
         * Corresponds to [V2MultiDeviceLifecycleEvent.DeviceConnected] with a
         * [V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.CREATE_ACTIVATE] or
         * [V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.RESTORE_ACTIVATE] hint.
         */
        DEVICE_ATTACH("device_attach"),

        /**
         * Device reconnected to the cross-device runtime after a transparent WS drop.
         *
         * Corresponds to [V2MultiDeviceLifecycleEvent.DeviceReconnected].  The durable
         * session era is preserved.
         */
        DEVICE_RECONNECT("device_reconnect"),

        /**
         * Device detached from the cross-device runtime.
         *
         * Corresponds to [V2MultiDeviceLifecycleEvent.DeviceDisconnected].  The
         * [V2MultiDeviceLifecycleEvent.DeviceDisconnected.isResumable] flag indicates
         * whether the durable era is preserved.
         */
        DEVICE_DETACH("device_detach"),

        /**
         * Device entered a degraded or unavailable state.
         *
         * Corresponds to [V2MultiDeviceLifecycleEvent.DeviceDegraded].
         */
        DEVICE_DEGRADED("device_degraded"),

        /**
         * Participant readiness or participation state changed.
         *
         * Corresponds to [V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged].
         */
        READINESS_CHANGED("readiness_changed"),

        /**
         * Execution environment health state changed.
         *
         * Corresponds to [V2MultiDeviceLifecycleEvent.DeviceHealthChanged].
         */
        HEALTH_CHANGED("health_changed");

        companion object {

            /**
             * Returns the [LifecycleObservabilityKind] with [wireValue] == [value], or `null`
             * for unknown / null values.
             *
             * Unknown values MUST be tolerated; callers must not reject log entries or events
             * that carry future [LifecycleObservabilityKind] values.
             */
            fun fromValue(value: String?): LifecycleObservabilityKind? =
                entries.firstOrNull { it.wireValue == value }

            /**
             * Set of all canonical [LifecycleObservabilityKind.wireValue] strings.
             *
             * Useful for validation in tests and schema registries.
             */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Helper functions ──────────────────────────────────────────────────────

    /**
     * Returns `true` when [dispatchTraceId] is a non-null, non-blank value that can
     * be used as a cross-system dispatch trace correlation identifier.
     *
     * A null or blank [dispatchTraceId] indicates that the sender does not support
     * dispatch-level tracing (legacy / pre-V2 sender); callers should handle this
     * gracefully by falling back to the session-level [TraceContext.currentTraceId].
     *
     * @param dispatchTraceId The [FIELD_DISPATCH_TRACE_ID] value from an inbound command.
     */
    fun hasDispatchTraceId(dispatchTraceId: String?): Boolean = !dispatchTraceId.isNullOrBlank()

    /**
     * Returns `true` when [lifecycleEventId] is a non-null, non-blank value indicating
     * that this dispatch was triggered by a specific V2 lifecycle event.
     *
     * @param lifecycleEventId The [FIELD_LIFECYCLE_EVENT_ID] value from an inbound command.
     */
    fun hasLifecycleEventId(lifecycleEventId: String?): Boolean = !lifecycleEventId.isNullOrBlank()

    /**
     * Returns `true` when [sessionCorrelationId] is a non-null, non-blank value that
     * can be used for session-level cross-system log correlation.
     *
     * @param sessionCorrelationId The [FIELD_SESSION_CORRELATION_ID] value from an inbound command.
     */
    fun hasSessionCorrelationId(sessionCorrelationId: String?): Boolean =
        !sessionCorrelationId.isNullOrBlank()

    /**
     * Selects the most specific available trace correlation identifier from the provided
     * observability fields, in order of specificity:
     *
     * 1. [dispatchTraceId] — most specific (dispatch-chain scoped)
     * 2. [sessionTraceId] — session-scoped fallback
     * 3. [contextTraceId] — session-level fallback from [TraceContext]
     *
     * Returns the first non-null, non-blank value encountered, or [contextTraceId] as
     * the unconditional fallback.
     *
     * @param dispatchTraceId      Optional dispatch-trace ID from [FIELD_DISPATCH_TRACE_ID].
     * @param sessionTraceId       Optional session-correlation ID from [FIELD_SESSION_CORRELATION_ID].
     * @param contextTraceId       Session-level trace ID from [TraceContext.currentTraceId].
     */
    fun resolveTraceId(
        dispatchTraceId: String?,
        sessionTraceId: String?,
        contextTraceId: String
    ): String = when {
        !dispatchTraceId.isNullOrBlank() -> dispatchTraceId
        !sessionTraceId.isNullOrBlank()  -> sessionTraceId
        else                             -> contextTraceId
    }

    // ── PR number / introduction tracking ────────────────────────────────────

    /** The PR number that introduced this observability metadata vocabulary. */
    const val INTRODUCED_PR: Int = 47
}
