package com.ufo.galaxy.runtime

/**
 * PR-43 — V2 multi-device runtime lifecycle event.
 *
 * A structured, V2-consumable event emitted by [RuntimeController] for every device
 * lifecycle transition relevant to the V2 multi-device runtime harness.  Each subclass
 * maps to one or more V2 hook call sites so V2 can wire its hooks to real Android device
 * events without inspecting raw Android-internal state flows.
 *
 * ## V2 hook mapping
 *
 * | Subclass                    | V2 hook target                                          |
 * |-----------------------------|---------------------------------------------------------|
 * | [DeviceConnected]           | `on_device_health_changed(deviceId, ONLINE/HEALTHY)`   |
 * | [DeviceReconnected]         | `on_device_health_changed(deviceId, ONLINE/HEALTHY)`   |
 * | [DeviceDisconnected]        | `on_device_health_changed(deviceId, OFFLINE)`           |
 * | [DeviceDegraded]            | `on_device_health_changed(deviceId, DEGRADED)`          |
 * | [DeviceHealthChanged]       | `on_device_health_changed(deviceId, …)`                 |
 * | [ParticipantReadinessChanged] | `on_participant_readiness_changed(deviceId, …)`       |
 *
 * ## Heartbeat semantics
 *
 * Android emits outbound WS heartbeats every 30 s
 * ([com.ufo.galaxy.network.GalaxyWebSocketClient.HEARTBEAT_INTERVAL_MS]).
 * Heartbeat **miss** detection — detecting that the remote host has not received a
 * heartbeat within a window — is **not available** on the Android side.  The canonical
 * Android-side signal for connectivity loss is a WS disconnect, surfaced here as
 * [DeviceDisconnected].  V2 should treat [DeviceDisconnected] with
 * [DeviceDisconnected.detachCause]`="disconnect"` as the Android equivalent of a
 * heartbeat-miss condition (the WS layer itself triggers once the underlying TCP
 * connection is lost).  Explicit heartbeat-miss events will not be emitted by this
 * class; this is intentional and stable.
 *
 * ## Wire values
 *
 * Each subclass carries a [wireValue] stable constant for use in structured log entries,
 * diagnostics payloads, and V2 event-schema bindings.  Wire values are stable and **must
 * not** be renamed after this PR ships without a corresponding V2 contract update.
 *
 * ## Consuming from V2
 *
 * Collect [RuntimeController.v2LifecycleEvents] from a coroutine scope scoped to the
 * V2 harness lifetime.  Each emitted event is a distinct subclass; use a `when`
 * expression or `is`-checks to dispatch to the appropriate V2 hook.
 *
 * ```kotlin
 * // Example V2-side consumer:
 * runtimeController.v2LifecycleEvents.collect { event ->
 *     when (event) {
 *         is V2MultiDeviceLifecycleEvent.DeviceConnected ->
 *             harness.on_device_health_changed(event.deviceId, V2HealthState.ONLINE)
 *         is V2MultiDeviceLifecycleEvent.DeviceReconnected ->
 *             harness.on_device_health_changed(event.deviceId, V2HealthState.ONLINE)
 *         is V2MultiDeviceLifecycleEvent.DeviceDisconnected ->
 *             harness.on_device_health_changed(event.deviceId, V2HealthState.OFFLINE)
 *         is V2MultiDeviceLifecycleEvent.DeviceDegraded ->
 *             harness.on_device_health_changed(event.deviceId, V2HealthState.DEGRADED)
 *         is V2MultiDeviceLifecycleEvent.DeviceHealthChanged ->
 *             harness.on_device_health_changed(event.deviceId, event.currentHealth)
 *         is V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged ->
 *             harness.on_participant_readiness_changed(event.deviceId, event.currentReadiness)
 *     }
 * }
 * ```
 *
 * @property wireValue Stable lowercase string identifying the event kind in structured
 *                     log entries and V2 schema bindings.
 */
sealed class V2MultiDeviceLifecycleEvent(open val wireValue: String) {

    /** Wall-clock epoch-millisecond timestamp when the event was emitted. */
    abstract val timestampMs: Long

    /**
     * Stable device identifier for this event.  Matches
     * [com.ufo.galaxy.data.AppSettings.deviceId] and the `device_id` field in outbound
     * AIP messages.
     */
    abstract val deviceId: String

    /**
     * Session identifier of the [com.ufo.galaxy.runtime.AttachedRuntimeSession] in scope
     * when the event was emitted.  `null` when no session was active at emission time (e.g.
     * for [DeviceDisconnected] events emitted after the session was closed).
     */
    abstract val sessionId: String?

    // ── Connect / attach ──────────────────────────────────────────────────────

    /**
     * The Android device has successfully attached to the cross-device runtime.
     *
     * Emitted by [RuntimeController] when an [com.ufo.galaxy.runtime.AttachedRuntimeSession]
     * is opened for the first time after a user-initiated start or background restore (i.e.
     * **not** a transparent reconnect — that is [DeviceReconnected]).
     *
     * V2 hook target: `on_device_health_changed(deviceId, ONLINE/HEALTHY)`.
     *
     * @property deviceId              Stable device identifier.
     * @property sessionId             Session ID of the newly opened attached session.
     * @property runtimeSessionId      Per-connection runtime session UUID (changes on each
     *                                 connect/reconnect, distinct from [sessionId]).
     * @property durableSessionId      Durable session era identifier; persists across
     *                                 reconnects within the same activation era.
     * @property sessionContinuityEpoch Number of reconnects within this durable era (0 on
     *                                 first connect).
     * @property openSource            Wire value of the session-open source:
     *                                 `"user_activation"` or `"background_restore"`.
     * @property timestampMs           Wall-clock emission timestamp.
     */
    data class DeviceConnected(
        override val deviceId: String,
        override val sessionId: String,
        val runtimeSessionId: String,
        val durableSessionId: String?,
        val sessionContinuityEpoch: Int,
        val openSource: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : V2MultiDeviceLifecycleEvent(WIRE_DEVICE_CONNECTED)

    // ── Reconnect / re-attach ─────────────────────────────────────────────────

    /**
     * The Android device has successfully reconnected to the cross-device runtime after a
     * transparent WS disconnect.
     *
     * Emitted by [RuntimeController] when an [com.ufo.galaxy.runtime.AttachedRuntimeSession]
     * is reopened with
     * [com.ufo.galaxy.runtime.RuntimeController.SessionOpenSource.RECONNECT_RECOVERY].
     * The [durableSessionId] is preserved from the previous session era; only the
     * [runtimeSessionId] and [sessionContinuityEpoch] change.
     *
     * V2 hook target: `on_device_health_changed(deviceId, ONLINE/HEALTHY)`.
     *
     * @property deviceId              Stable device identifier.
     * @property sessionId             Session ID of the newly reopened attached session.
     * @property runtimeSessionId      New per-connection runtime session UUID.
     * @property durableSessionId      Same durable session era identifier as before the
     *                                 disconnect; epoch is incremented.
     * @property sessionContinuityEpoch Incremented reconnect count within the durable era.
     * @property timestampMs           Wall-clock emission timestamp.
     */
    data class DeviceReconnected(
        override val deviceId: String,
        override val sessionId: String,
        val runtimeSessionId: String,
        val durableSessionId: String?,
        val sessionContinuityEpoch: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : V2MultiDeviceLifecycleEvent(WIRE_DEVICE_RECONNECTED)

    // ── Disconnect / detach ───────────────────────────────────────────────────

    /**
     * The Android device has detached from the cross-device runtime.
     *
     * Emitted by [RuntimeController] whenever an
     * [com.ufo.galaxy.runtime.AttachedRuntimeSession] is closed, regardless of the
     * cause.  The [detachCause] wire value distinguishes the cause:
     *
     * | [detachCause]      | Description |
     * |--------------------|-------------|
     * | `"disconnect"`     | Transient WS drop; WS client will auto-reconnect ([DeviceReconnected] will follow). |
     * | `"disable"`        | Explicit stop / kill-switch; no reconnect expected. |
     * | `"invalidation"`   | Session invalidated due to trust or protocol mismatch. |
     *
     * V2 hook target: `on_device_health_changed(deviceId, OFFLINE)`.
     *
     * @property deviceId          Stable device identifier.
     * @property sessionId         Session ID of the session that was closed.
     * @property detachCause       Wire value from
     *                             [com.ufo.galaxy.runtime.AttachedRuntimeSession.DetachCause]:
     *                             `"disconnect"`, `"disable"`, or `"invalidation"`.
     * @property sessionDurationMs Approximate duration of the session in milliseconds.
     * @property timestampMs       Wall-clock emission timestamp.
     */
    data class DeviceDisconnected(
        override val deviceId: String,
        override val sessionId: String?,
        val detachCause: String,
        val sessionDurationMs: Long,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : V2MultiDeviceLifecycleEvent(WIRE_DEVICE_DISCONNECTED)

    // ── Degraded / unavailable ────────────────────────────────────────────────

    /**
     * The Android device has entered a degraded or unavailable state.
     *
     * Emitted by [RuntimeController] when:
     *  - The WS reconnect recovery state transitions to [ReconnectRecoveryState.RECOVERING]
     *    (`degradationKind = "ws_recovering"`).
     *  - The WS reconnect recovery state transitions to [ReconnectRecoveryState.FAILED]
     *    (`degradationKind = "ws_recovery_failed"`).
     *  - Participant health is reported as [ParticipantHealthState.DEGRADED]
     *    (`degradationKind = "health_degraded"`).
     *  - Participant health is reported as [ParticipantHealthState.RECOVERING]
     *    (`degradationKind = "health_recovering"`).
     *  - Participant health is reported as [ParticipantHealthState.FAILED]
     *    (`degradationKind = "health_failed"`).
     *
     * V2 hook target: `on_device_health_changed(deviceId, DEGRADED)`.
     *
     * @property deviceId          Stable device identifier.
     * @property sessionId         Session ID at degradation time; may be `null` if the
     *                             session was already closed when degradation was detected.
     * @property degradationKind   Machine-readable degradation classification — one of
     *                             `"ws_recovering"`, `"ws_recovery_failed"`,
     *                             `"health_degraded"`, `"health_recovering"`,
     *                             `"health_failed"`.
     * @property continuationMode  Wire value from
     *                             [com.ufo.galaxy.runtime.FormationParticipationRebalancer.ContinuationMode]
     *                             describing how execution should continue.
     * @property timestampMs       Wall-clock emission timestamp.
     */
    data class DeviceDegraded(
        override val deviceId: String,
        override val sessionId: String?,
        val degradationKind: String,
        val continuationMode: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : V2MultiDeviceLifecycleEvent(WIRE_DEVICE_DEGRADED)

    // ── Health changed ────────────────────────────────────────────────────────

    /**
     * The Android device's execution environment health state changed.
     *
     * Emitted by [RuntimeController.notifyParticipantHealthChanged] when an external
     * caller reports a new [ParticipantHealthState].  This event carries the raw health
     * classification so V2 can map it to the appropriate multi-device health condition.
     *
     * V2 hook target: `on_device_health_changed(deviceId, currentHealth)`.
     *
     * @property deviceId          Stable device identifier.
     * @property sessionId         Session ID at health-change time.
     * @property previousHealth    Prior health state wire value (from
     *                             [ParticipantHealthState.wireValue]).
     * @property currentHealth     New health state wire value.
     * @property requiresRebalance Whether the new health state requires a formation rebalance.
     * @property continuationMode  Wire value from
     *                             [FormationParticipationRebalancer.ContinuationMode].
     * @property trigger           Machine-readable cause description (e.g. `"health_degraded"`).
     * @property timestampMs       Wall-clock emission timestamp.
     */
    data class DeviceHealthChanged(
        override val deviceId: String,
        override val sessionId: String?,
        val previousHealth: String,
        val currentHealth: String,
        val requiresRebalance: Boolean,
        val continuationMode: String,
        val trigger: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : V2MultiDeviceLifecycleEvent(WIRE_DEVICE_HEALTH_CHANGED)

    // ── Readiness changed ─────────────────────────────────────────────────────

    /**
     * The Android participant's readiness or participation state changed in a way relevant
     * to multi-device task admission.
     *
     * Emitted by [RuntimeController] whenever a [FormationRebalanceEvent.ReadinessChanged]
     * is produced — either from a WS reconnect recovery transition or from an explicit
     * health-change evaluation.
     *
     * V2 hook target: `on_participant_readiness_changed(deviceId, currentReadiness)`.
     *
     * @property deviceId              Stable device identifier.
     * @property sessionId             Session ID at readiness-change time.
     * @property previousReadiness     Prior readiness state wire value (from
     *                                 [ParticipantReadinessState.wireValue]).
     * @property currentReadiness      New readiness state wire value.
     * @property previousParticipation Prior participation state wire value (from
     *                                 [RuntimeHostDescriptor.HostParticipationState.wireValue]).
     * @property currentParticipation  New participation state wire value.
     * @property trigger               Machine-readable cause description (e.g.
     *                                 `"ws_disconnect_active"`, `"health_state_degraded"`).
     * @property timestampMs           Wall-clock emission timestamp.
     */
    data class ParticipantReadinessChanged(
        override val deviceId: String,
        override val sessionId: String?,
        val previousReadiness: String,
        val currentReadiness: String,
        val previousParticipation: String,
        val currentParticipation: String,
        val trigger: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : V2MultiDeviceLifecycleEvent(WIRE_PARTICIPANT_READINESS_CHANGED)

    // ── Companion / wire-value constants ──────────────────────────────────────

    companion object {

        /** Wire value for [DeviceConnected] events. */
        const val WIRE_DEVICE_CONNECTED = "v2_device_connected"

        /** Wire value for [DeviceReconnected] events. */
        const val WIRE_DEVICE_RECONNECTED = "v2_device_reconnected"

        /** Wire value for [DeviceDisconnected] events. */
        const val WIRE_DEVICE_DISCONNECTED = "v2_device_disconnected"

        /** Wire value for [DeviceDegraded] events. */
        const val WIRE_DEVICE_DEGRADED = "v2_device_degraded"

        /** Wire value for [DeviceHealthChanged] events. */
        const val WIRE_DEVICE_HEALTH_CHANGED = "v2_device_health_changed"

        /** Wire value for [ParticipantReadinessChanged] events. */
        const val WIRE_PARTICIPANT_READINESS_CHANGED = "v2_participant_readiness_changed"

        /**
         * Stable wire value that V2 should record in its schema as the explicit declaration
         * that Android-side heartbeat-miss events are not emitted.
         *
         * Android sends outbound WS heartbeats every 30 s but does not detect heartbeat miss
         * as a distinct event.  V2 must treat [WIRE_DEVICE_DISCONNECTED] with
         * `detachCause="disconnect"` as the equivalent signal.
         */
        const val WIRE_HEARTBEAT_MISS_UNSUPPORTED = "v2_heartbeat_miss_unsupported"

        /** All stable wire values — useful for validation in tests and schema registries. */
        val ALL_WIRE_VALUES: Set<String> = setOf(
            WIRE_DEVICE_CONNECTED,
            WIRE_DEVICE_RECONNECTED,
            WIRE_DEVICE_DISCONNECTED,
            WIRE_DEVICE_DEGRADED,
            WIRE_DEVICE_HEALTH_CHANGED,
            WIRE_PARTICIPANT_READINESS_CHANGED
        )

        /** PR-43 identifier. */
        const val INTRODUCED_PR = 43

        /** Human-readable description of this surface for documentation and baseline entries. */
        const val DESCRIPTION =
            "V2-consumable device lifecycle event sealed class — provides stable, typed events " +
                "for V2 multi-device runtime hook integration without exposing Android-internal " +
                "state machines to the V2 layer."

        /**
         * Coverage invariant: all emitted events must carry a non-blank [wireValue] and
         * [deviceId], and the [wireValue] must be in [ALL_WIRE_VALUES].
         */
        const val COVERAGE_INVARIANT =
            "Every V2MultiDeviceLifecycleEvent subclass must: (1) carry a non-blank wireValue " +
                "that is registered in ALL_WIRE_VALUES; (2) carry a non-blank deviceId; " +
                "(3) carry a non-negative timestampMs."
    }
}
