package com.ufo.galaxy.runtime

/**
 * PR-43 / PR-44 — V2 multi-device runtime lifecycle event.
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
 * ## PR-44 — V2 mesh session lifecycle mapping
 *
 * [DeviceConnected], [DeviceReconnected], and [DeviceDisconnected] carry an explicit
 * [MeshSessionLifecycleHint] that tells V2 which Mesh session lifecycle transition to
 * invoke, without requiring V2 to re-parse raw Android fields:
 *
 * | Event + condition                                              | [MeshSessionLifecycleHint]    | V2 mesh call          |
 * |----------------------------------------------------------------|-------------------------------|-----------------------|
 * | [DeviceConnected] with `openSource = "user_activation"`        | [MeshSessionLifecycleHint.CREATE_ACTIVATE]   | `create()` + `activate()` |
 * | [DeviceConnected] with `openSource = "background_restore"`     | [MeshSessionLifecycleHint.RESTORE_ACTIVATE]  | `restore()`               |
 * | [DeviceReconnected] (always)                                   | [MeshSessionLifecycleHint.RESTORE_ACTIVATE]  | `restore()`               |
 * | [DeviceDisconnected] with `detachCause = "disconnect"`         | [MeshSessionLifecycleHint.SUSPEND]           | `suspend()`               |
 * | [DeviceDisconnected] with any other `detachCause`              | [MeshSessionLifecycleHint.TERMINATE]         | `terminate()`             |
 *
 * [DeviceDisconnected] additionally carries [DeviceDisconnected.isResumable] (`true` when
 * `detachCause = "disconnect"`) so V2 can gate reconnect-recovery logic without separately
 * inspecting [DeviceDisconnected.detachCause].
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
 * // Example V2-side consumer (health + mesh session lifecycle):
 * runtimeController.v2LifecycleEvents.collect { event ->
 *     when (event) {
 *         is V2MultiDeviceLifecycleEvent.DeviceConnected -> {
 *             harness.on_device_health_changed(event.deviceId, V2HealthState.ONLINE)
 *             when (event.meshLifecycleHint) {
 *                 MeshSessionLifecycleHint.CREATE_ACTIVATE -> meshCoordinator.createAndActivate(event.durableSessionId)
 *                 MeshSessionLifecycleHint.RESTORE_ACTIVATE -> meshCoordinator.restore(event.durableSessionId)
 *                 else -> { /* not reached for DeviceConnected */ }
 *             }
 *         }
 *         is V2MultiDeviceLifecycleEvent.DeviceReconnected -> {
 *             harness.on_device_health_changed(event.deviceId, V2HealthState.ONLINE)
 *             meshCoordinator.restore(event.durableSessionId)  // always RESTORE_ACTIVATE
 *         }
 *         is V2MultiDeviceLifecycleEvent.DeviceDisconnected -> {
 *             harness.on_device_health_changed(event.deviceId, V2HealthState.OFFLINE)
 *             if (event.isResumable) meshCoordinator.suspend()
 *             else meshCoordinator.terminate()
 *         }
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

    // ── Mesh session lifecycle hint ────────────────────────────────────────────

    /**
     * PR-44 — Explicit V2 mesh-session lifecycle transition hint.
     *
     * Carried by [DeviceConnected], [DeviceReconnected], and [DeviceDisconnected] to tell
     * V2 exactly which Mesh session lifecycle transition the Android event corresponds to.
     * This eliminates the need for V2 to infer the transition from raw Android fields such
     * as [DeviceConnected.openSource] or [DeviceDisconnected.detachCause].
     *
     * ## Mapping to V2 mesh lifecycle calls
     *
     * | [MeshSessionLifecycleHint]    | V2 mesh call target                       | Android source condition                                                              |
     * |-------------------------------|-------------------------------------------|---------------------------------------------------------------------------------------|
     * | [CREATE_ACTIVATE]             | `create()` + `activate()`                 | [DeviceConnected] with `openSource = "user_activation"`                               |
     * | [RESTORE_ACTIVATE]            | `restore()` (+ `activate()` if needed)    | [DeviceConnected] with `openSource = "background_restore"`, or [DeviceReconnected]    |
     * | [SUSPEND]                     | `suspend()`                               | [DeviceDisconnected] with `detachCause = "disconnect"` (reconnect expected)           |
     * | [TERMINATE]                   | `terminate()`                             | [DeviceDisconnected] with `detachCause` in `["disable", "explicit_detach", "invalidation"]` |
     *
     * @property wireValue Stable lowercase string used in JSON payloads and V2 schema bindings.
     */
    enum class MeshSessionLifecycleHint(val wireValue: String) {

        /**
         * A new mesh session should be created and activated.
         *
         * Corresponds to [DeviceConnected] events with `openSource = "user_activation"`.
         * V2 target: `MeshSessionLifecycleCoordinator.create()` followed by `activate()`.
         * This is the first-time activation path — no prior durable session exists.
         */
        CREATE_ACTIVATE("mesh_create_activate"),

        /**
         * An existing durable mesh session should be restored (and reactivated if needed).
         *
         * Corresponds to [DeviceConnected] events with `openSource = "background_restore"`,
         * and to all [DeviceReconnected] events (transparent WS reconnect preserves the
         * durable session era).
         * V2 target: `MeshSessionLifecycleCoordinator.restore()`.
         * The [DeviceReconnected.durableSessionId] / [DeviceConnected.durableSessionId]
         * field carries the stable era identifier for V2 to locate the prior session.
         */
        RESTORE_ACTIVATE("mesh_restore_activate"),

        /**
         * The mesh session should be suspended — a reconnect is expected to follow.
         *
         * Corresponds to [DeviceDisconnected] events with `detachCause = "disconnect"`.
         * V2 target: `MeshSessionLifecycleCoordinator.suspend()`.
         * The durable session era is preserved; [DeviceReconnected] will follow if the
         * WS client successfully reconnects.  V2 must not terminate the mesh session on
         * a SUSPEND hint; it should await the subsequent RESTORE_ACTIVATE.
         */
        SUSPEND("mesh_suspend"),

        /**
         * The mesh session should be terminated — no reconnect is expected.
         *
         * Corresponds to [DeviceDisconnected] events with `detachCause` in
         * `["disable", "explicit_detach", "invalidation"]`.
         * V2 target: `MeshSessionLifecycleCoordinator.terminate()`.
         * The durable session era ends; a new CREATE_ACTIVATE event will start a fresh era
         * if the device re-enables cross-device participation.
         */
        TERMINATE("mesh_terminate");

        companion object {
            /**
             * Parses [value] to a [MeshSessionLifecycleHint], returning `null` for unknown
             * or null values.
             */
            fun fromValue(value: String?): MeshSessionLifecycleHint? =
                entries.firstOrNull { it.wireValue == value }

            /** All four wire values — useful for validation in tests and schema registries. */
            val ALL_WIRE_VALUES: Set<String> = setOf(
                CREATE_ACTIVATE.wireValue,
                RESTORE_ACTIVATE.wireValue,
                SUSPEND.wireValue,
                TERMINATE.wireValue
            )
        }
    }

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
     * V2 mesh session target: see [meshLifecycleHint] — either [MeshSessionLifecycleHint.CREATE_ACTIVATE]
     * (for `"user_activation"`) or [MeshSessionLifecycleHint.RESTORE_ACTIVATE] (for
     * `"background_restore"`).
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
    ) : V2MultiDeviceLifecycleEvent(WIRE_DEVICE_CONNECTED) {

        /**
         * PR-44 — Explicit mesh session lifecycle hint for this connect event.
         *
         * Derived from [openSource]:
         * - `"background_restore"` → [MeshSessionLifecycleHint.RESTORE_ACTIVATE]: an existing
         *   durable session era should be restored (process/service restarted after prior
         *   activation).
         * - any other value (including `"user_activation"`) → [MeshSessionLifecycleHint.CREATE_ACTIVATE]:
         *   a new mesh session should be created and activated.
         *
         * V2 should branch on this hint rather than re-parsing [openSource] itself.
         */
        val meshLifecycleHint: MeshSessionLifecycleHint
            get() = if (openSource == OPEN_SOURCE_BACKGROUND_RESTORE)
                MeshSessionLifecycleHint.RESTORE_ACTIVATE
            else
                MeshSessionLifecycleHint.CREATE_ACTIVATE
    }

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
     * V2 mesh session target: [MeshSessionLifecycleHint.RESTORE_ACTIVATE] — the durable session
     * era is preserved and should be restored rather than re-created.
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
    ) : V2MultiDeviceLifecycleEvent(WIRE_DEVICE_RECONNECTED) {

        /**
         * PR-44 — Explicit mesh session lifecycle hint for this reconnect event.
         *
         * Always [MeshSessionLifecycleHint.RESTORE_ACTIVATE]: a transparent WS reconnect
         * preserves the durable session era; V2 should restore the existing mesh session
         * rather than creating a new one.  Use [durableSessionId] as the stable anchor to
         * locate the prior session.
         */
        val meshLifecycleHint: MeshSessionLifecycleHint
            get() = MeshSessionLifecycleHint.RESTORE_ACTIVATE
    }

    // ── Disconnect / detach ───────────────────────────────────────────────────

    /**
     * The Android device has detached from the cross-device runtime.
     *
     * Emitted by [RuntimeController] whenever an
     * [com.ufo.galaxy.runtime.AttachedRuntimeSession] is closed, regardless of the
     * cause.  The [detachCause] wire value distinguishes the cause:
     *
     * | [detachCause]        | [meshLifecycleHint]                          | [isResumable] | Description |
     * |----------------------|----------------------------------------------|---------------|-------------|
     * | `"disconnect"`       | [MeshSessionLifecycleHint.SUSPEND]           | `true`        | Transient WS drop; WS client will auto-reconnect ([DeviceReconnected] will follow). |
     * | `"disable"`          | [MeshSessionLifecycleHint.TERMINATE]         | `false`       | Explicit stop / kill-switch; no reconnect expected. |
     * | `"explicit_detach"`  | [MeshSessionLifecycleHint.TERMINATE]         | `false`       | Operator or user explicitly detached this device. |
     * | `"invalidation"`     | [MeshSessionLifecycleHint.TERMINATE]         | `false`       | Session invalidated due to trust or protocol mismatch. |
     *
     * V2 hook target: `on_device_health_changed(deviceId, OFFLINE)`.
     *
     * V2 mesh session target: see [meshLifecycleHint] — either
     * [MeshSessionLifecycleHint.SUSPEND] (reconnect expected) or
     * [MeshSessionLifecycleHint.TERMINATE] (no reconnect expected).
     *
     * @property deviceId          Stable device identifier.
     * @property sessionId         Session ID of the session that was closed.
     * @property detachCause       Wire value from
     *                             [com.ufo.galaxy.runtime.AttachedRuntimeSession.DetachCause]:
     *                             `"disconnect"`, `"disable"`, `"explicit_detach"`, or
     *                             `"invalidation"`.
     * @property sessionDurationMs Approximate duration of the session in milliseconds.
     * @property timestampMs       Wall-clock emission timestamp.
     */
    data class DeviceDisconnected(
        override val deviceId: String,
        override val sessionId: String?,
        val detachCause: String,
        val sessionDurationMs: Long,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : V2MultiDeviceLifecycleEvent(WIRE_DEVICE_DISCONNECTED) {

        /**
         * PR-44 — Explicit mesh session lifecycle hint for this disconnect event.
         *
         * Derived from [detachCause]:
         * - `"disconnect"` → [MeshSessionLifecycleHint.SUSPEND]: transient WS drop; the durable
         *   session era is preserved and a [DeviceReconnected] event will follow if the WS client
         *   successfully reconnects.  V2 must **not** terminate the mesh session on SUSPEND; it
         *   should call `suspend()` and await the subsequent RESTORE_ACTIVATE.
         * - any other value → [MeshSessionLifecycleHint.TERMINATE]: permanent end; the durable
         *   era is cleared and V2 should call `terminate()` on the mesh session.
         *
         * V2 should branch on this hint rather than re-parsing [detachCause] itself.
         */
        val meshLifecycleHint: MeshSessionLifecycleHint
            get() = if (detachCause == DETACH_CAUSE_DISCONNECT)
                MeshSessionLifecycleHint.SUSPEND
            else
                MeshSessionLifecycleHint.TERMINATE

        /**
         * PR-44 — Whether reconnect and session restoration are expected after this disconnect.
         *
         * `true`  when [detachCause] is `"disconnect"` — the WS client will automatically
         *         attempt to reconnect; a [DeviceReconnected] event will follow on success.
         *         V2 should preserve the durable mesh session in anticipation of restore.
         *
         * `false` when [detachCause] is `"disable"`, `"explicit_detach"`, or `"invalidation"`
         *         — the durable session era has ended and no automatic reconnect is expected.
         *         V2 should terminate the mesh session.
         */
        val isResumable: Boolean
            get() = detachCause == DETACH_CAUSE_DISCONNECT
    }

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
     * @property deviceId               Stable device identifier.
     * @property sessionId              Session ID at degradation time; may be `null` if the
     *                                  session was already closed when degradation was detected.
     * @property degradationKind        Machine-readable degradation classification — one of
     *                                  `"ws_recovering"`, `"ws_recovery_failed"`,
     *                                  `"health_degraded"`, `"health_recovering"`,
     *                                  `"health_failed"`.
     * @property continuationMode       Wire value from
     *                                  [com.ufo.galaxy.runtime.FormationParticipationRebalancer.ContinuationMode]
     *                                  describing how execution should continue.
     * @property durableSessionId       Durable session era identifier at degradation time;
     *                                  the same value present in the preceding
     *                                  [DeviceConnected.durableSessionId] or
     *                                  [DeviceReconnected.durableSessionId] event (both of
     *                                  which carry this field).
     *                                  `null` when no durable record is active (e.g. before
     *                                  first activation). V2 should use this to correlate a
     *                                  `ws_recovering` or `ws_recovery_failed` event with the
     *                                  specific session era being recovered, without re-parsing
     *                                  prior events.
     * @property sessionContinuityEpoch Reconnect count within the durable era at degradation
     *                                  time; `null` when no durable record is active.
     * @property timestampMs            Wall-clock emission timestamp.
     */
    data class DeviceDegraded(
        override val deviceId: String,
        override val sessionId: String?,
        val degradationKind: String,
        val continuationMode: String,
        val durableSessionId: String? = null,
        val sessionContinuityEpoch: Int? = null,
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

        /**
         * PR-44 — Stable `openSource` wire value used by [DeviceConnected.meshLifecycleHint]
         * to identify background-restore activations that map to
         * [MeshSessionLifecycleHint.RESTORE_ACTIVATE].
         *
         * Matches [RuntimeController.SessionOpenSource.BACKGROUND_RESTORE.wireValue].
         */
        const val OPEN_SOURCE_BACKGROUND_RESTORE = "background_restore"

        /**
         * PR-44 — Stable `detachCause` wire value used by [DeviceDisconnected.meshLifecycleHint]
         * and [DeviceDisconnected.isResumable] to identify transient WS disconnects that map to
         * [MeshSessionLifecycleHint.SUSPEND] (reconnect expected).
         *
         * Matches [com.ufo.galaxy.runtime.AttachedRuntimeSession.DetachCause.DISCONNECT.wireValue].
         */
        const val DETACH_CAUSE_DISCONNECT = "disconnect"

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

        /** PR-44 identifier — mesh session lifecycle hint extension. */
        const val INTRODUCED_PR_MESH_HINT = 44

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
