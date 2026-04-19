package com.ufo.galaxy.runtime

import android.util.Log
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.observability.GalaxyLogger
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * **Sole lifecycle authority** for the cross-device collaboration runtime (AIP v3 + Galaxy
 * Gateway).
 *
 * All WS connect / disconnect decisions, settings mutations, and runtime-state transitions
 * for the cross-device pipeline **must** go through this class. No other component should
 * call [GalaxyWebSocketClient.connect], [GalaxyWebSocketClient.disconnect], or mutate
 * [AppSettings.crossDeviceEnabled] directly.
 *
 * Lifecycle overview:
 *  - **User-initiated ON** ([startWithTimeout] → [start]): Enables the WS client, connects
 *    within [registrationTimeoutMs], transitions to [RuntimeState.Active] on success.
 *    On failure / timeout: emits [registrationError], falls back to [RuntimeState.LocalOnly],
 *    and resets [AppSettings.crossDeviceEnabled] to `false`.
 *  - **User-initiated OFF** ([stop]): Cleanly disconnects the WS, disables cross-device in
 *    [settings], and transitions to [RuntimeState.LocalOnly].
 *  - **Background restore** ([connectIfEnabled]): Called on service restart or activity
 *    resume.  Syncs the WS client with the persisted [AppSettings.crossDeviceEnabled] value
 *    and triggers a best-effort reconnect — without emitting [registrationError] or
 *    modifying settings on transient failure.
 *  - **Failure**: Any registration or network failure inside [start] / [startWithTimeout]
 *    emits a typed [CrossDeviceSetupError] via [setupError] so both
 *    [com.ufo.galaxy.ui.MainActivity] and [com.ufo.galaxy.service.EnhancedFloatingService]
 *    can show a dialog/alert with category-appropriate recovery actions — **never** silently
 *    log-only.  The legacy [registrationError] string bridge is also emitted for backward
 *    compatibility but is deprecated; new code must use [setupError].
 *  - **Reconnect** ([reconnect]): convenience method that cleanly stops and then
 *    re-starts the runtime in one atomic step; intended for "save settings and reconnect"
 *    flows and category-aware retry logic.
 *  - **Takeover failure**: Individual delegated task failures (FAILED / TIMEOUT / CANCELLED
 *    outcomes from [com.ufo.galaxy.agent.DelegatedTakeoverExecutor.execute], or a WS
 *    disconnect while a takeover was active) are emitted via [takeoverFailure].  Surface
 *    layers must observe this flow to clear stale "active" or "in-control" state without
 *    independently transitioning the runtime (PR-23).  Duplicate notifications for the
 *    same `takeoverId` within a single session lifetime are silently suppressed (PR-29).
 *
 * Remote execution flight state (PR-29):
 *  - [isRemoteExecutionActive]: `true` from [onRemoteTaskStarted] until [onRemoteTaskFinished]
 *    or [stop]; surface layers observe this to avoid maintaining their own redundant flag.
 *
 * Attached-runtime session (PR-7 / PR-14):
 *  - [attachedSession] exposes the current [AttachedRuntimeSession] as a [StateFlow].
 *  - A session is created when the runtime transitions to [RuntimeState.Active] (i.e.
 *    WS connected with cross-device enabled).  It represents stable, persistent Android
 *    runtime participation — not just WS connectivity.
 *  - A single session spans **multiple** successive delegated task executions; it is
 *    **not** re-created between tasks.  [recordDelegatedExecutionAccepted] increments the
 *    session's [AttachedRuntimeSession.delegatedExecutionCount] each time a delegated unit
 *    is accepted, without touching the session identity or state (PR-14).
 *  - The session is detached when [stop] is called ([AttachedRuntimeSession.DetachCause.DISABLE]),
 *    when the WS connection is lost ([AttachedRuntimeSession.DetachCause.DISCONNECT]),
 *    when a registration failure occurs ([AttachedRuntimeSession.DetachCause.DISCONNECT]), or
 *    when [invalidateSession] is called ([AttachedRuntimeSession.DetachCause.INVALIDATION]).
 *  - When a [hostDescriptor] is provided, its [RuntimeHostDescriptor.HostParticipationState]
 *    is kept in sync: [RuntimeHostDescriptor.HostParticipationState.ACTIVE] while the session
 *    is [AttachedRuntimeSession.State.ATTACHED], [RuntimeHostDescriptor.HostParticipationState.INACTIVE]
 *    after it is detached.
 *
 * Remote task handoff (AIP v3 compliance):
 *  - [onRemoteTaskStarted]: called when a `task_assign` or `goal_execution` arrives from
 *    the Gateway; cancels any running local [LoopController] session and sets
 *    [LoopController.isRemoteTaskActive] = true.
 *  - [onRemoteTaskFinished]: called when the device has sent back the `task_result` /
 *    `goal_result`; clears [LoopController.isRemoteTaskActive] so local execution may
 *    resume on the next user request.
 *
 * OpenClawd memory backflow is wired in [com.ufo.galaxy.service.GalaxyConnectionService]
 * after each task result is sent; the [route_mode] is set to `"cross_device"` when
 * cross-device is active, `"local"` otherwise.
 *
 * @param webSocketClient        Live WebSocket client; used to connect/disconnect.
 * @param settings               Persistent settings; [AppSettings.crossDeviceEnabled] is
 *                               updated on success and on fallback to local.
 * @param loopController         Local closed-loop controller; paused on remote task arrival.
 * @param registrationTimeoutMs  Max time in ms to wait for a WS connection (default 15 s).
 * @param hostDescriptor         Optional [RuntimeHostDescriptor] for this Android host.
 *                               When supplied, the descriptor's participation state is kept
 *                               in sync with the [attachedSession] lifecycle, and the updated
 *                               descriptor is propagated to [webSocketClient] via
 *                               [GalaxyWebSocketClient.setRuntimeHostDescriptor].
 *                               May be `null` when no descriptor has been initialised yet
 *                               (e.g. in unit tests or early startup).
 */
class RuntimeController(
    private val webSocketClient: GalaxyWebSocketClient,
    private val settings: AppSettings,
    private val loopController: LoopController,
    private val registrationTimeoutMs: Long = DEFAULT_REGISTRATION_TIMEOUT_MS,
    private var hostDescriptor: RuntimeHostDescriptor? = null
) {

    // ── Runtime state ─────────────────────────────────────────────────────────

    /** Observable lifecycle state of the cross-device runtime. */
    sealed class RuntimeState {
        /** No start has been requested since the last [stop] or app launch. */
        object Idle : RuntimeState()
        /** [start] is in progress; waiting for WS connection and registration. */
        object Starting : RuntimeState()
        /** WS connected and device registered; Gateway can assign tasks. */
        object Active : RuntimeState()
        /**
         * Registration failed (network error or timeout). The controller has automatically
         * fallen back to [LocalOnly]. The failing reason is emitted on [registrationError].
         */
        data class Failed(val reason: String) : RuntimeState()
        /** Cross-device is OFF; the [LoopController] handles all execution. */
        object LocalOnly : RuntimeState()
    }

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Idle)

    /**
     * Current lifecycle state. Observe from UI to drive connection indicators and
     * to detect [RuntimeState.Failed] events for dialog display.
     */
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    /**
     * One-time registration failure events.
     *
     * Emits a human-readable failure reason whenever [start] fails (network error,
     * timeout, or explicit WS error).
     *
     * @deprecated Consumers must migrate to [setupError] which provides typed
     * [CrossDeviceSetupError] classification for category-appropriate recovery actions.
     * This legacy string bridge is retained for backward compatibility only and must not
     * receive new observers.  See [CompatibilitySurfaceRetirementRegistry] entry
     * `"runtime_registration_error_string_bridge"`.
     */
    @Deprecated(
        message = "Migrate to setupError (SharedFlow<CrossDeviceSetupError>) for typed, " +
            "category-aware error handling. This string bridge is a HIGH_RISK_ACTIVE " +
            "compatibility surface — see CompatibilitySurfaceRetirementRegistry.",
        replaceWith = ReplaceWith("setupError")
    )
    private val _registrationError = MutableSharedFlow<String>(extraBufferCapacity = 1)

    @Deprecated(
        message = "Migrate to setupError (SharedFlow<CrossDeviceSetupError>) for typed, " +
            "category-aware error handling. This string bridge is a HIGH_RISK_ACTIVE " +
            "compatibility surface — see CompatibilitySurfaceRetirementRegistry.",
        replaceWith = ReplaceWith("setupError")
    )
    val registrationError: SharedFlow<String> = _registrationError.asSharedFlow()

    /**
     * PR-27 — Typed setup error events for product-grade error differentiation.
     *
     * The **canonical failure signal** for cross-device setup errors.  All consumers must
     * observe this flow (not the deprecated [registrationError] string bridge) to present
     * **category-appropriate** recovery actions:
     *
     *  - [CrossDeviceSetupError.Category.CONFIGURATION] — gateway not configured; recovery
     *    action is to open network settings.
     *  - [CrossDeviceSetupError.Category.NETWORK] — transient network failure; recovery
     *    action is to retry the connection.
     *  - [CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED] — permissions or
     *    capability requirements not met; recovery action is to open system settings.
     *
     * The [CrossDeviceSetupError.canRetry] flag indicates whether the retry path is
     * meaningful for the given category without a settings change.
     * The [CrossDeviceSetupError.reason] carries the same human-readable string that the
     * deprecated [registrationError] bridge emits, so a single [setupError] observer
     * provides complete information.
     */
    private val _setupError = MutableSharedFlow<CrossDeviceSetupError>(extraBufferCapacity = 1)
    val setupError: SharedFlow<CrossDeviceSetupError> = _setupError.asSharedFlow()

    /**
     * One-time delegated-takeover failure events (PR-23 — cross-device failure and
     * fallback state closure).
     *
     * Emits a [TakeoverFallbackEvent] whenever a delegated task execution terminates with
     * a non-completion outcome:
     *  - [TakeoverFallbackEvent.Cause.FAILED] — pipeline threw an unclassified exception.
     *  - [TakeoverFallbackEvent.Cause.TIMEOUT] — execution exceeded its wall-clock budget.
     *  - [TakeoverFallbackEvent.Cause.CANCELLED] — execution was cooperatively cancelled.
     *  - [TakeoverFallbackEvent.Cause.DISCONNECT] — WS disconnected while a takeover was
     *    active; the in-flight execution result is unknown.
     *
     * Surface layers ([com.ufo.galaxy.ui.viewmodel.MainViewModel],
     * [com.ufo.galaxy.service.EnhancedFloatingService]) **must** collect this flow and
     * clear any stale "active" or "in-control" indicators — **never** silently ignore the
     * event.  The runtime state and [attachedSession] are intentionally NOT modified by
     * [notifyTakeoverFailed]; the device stays in cross-device mode and the session remains
     * valid for the next incoming delegated task.
     *
     * Callers that need to record the failure in diagnostics should also call [pushError]
     * (or equivalent) after receiving the event.
     */
    private val _takeoverFailure = MutableSharedFlow<TakeoverFallbackEvent>(extraBufferCapacity = 4)
    val takeoverFailure: SharedFlow<TakeoverFallbackEvent> = _takeoverFailure.asSharedFlow()

    /**
     * Current attached-runtime session, or `null` when no session is active.
     *
     * A non-null value in [AttachedRuntimeSession.State.ATTACHED] indicates that this
     * Android device is actively participating as a persistent runtime surface — distinct
     * from mere WS connectivity.  Observe from any component that needs to distinguish
     * ordinary connection/presence from explicit cross-device attached runtime participation.
     *
     * Lifecycle:
     *  - Set to a new [AttachedRuntimeSession] in [AttachedRuntimeSession.State.ATTACHED]
     *    when the runtime transitions to [RuntimeState.Active].
     *  - Transitioned to [AttachedRuntimeSession.State.DETACHED] on [stop], WS disconnect,
     *    registration failure, or [invalidateSession].
     *
     * The latest detached value is intentionally retained (instead of being cleared to `null`)
     * so consumers can still observe detachment cause and retirement semantics in a stable,
     * queryable form through [attachedSession], [hostSessionSnapshot], and
     * [targetReadinessProjection].
     *
     * (PR-7 — Android Attached-Runtime Session Semantics)
     */
    private val _attachedSession = MutableStateFlow<AttachedRuntimeSession?>(null)
    val attachedSession: StateFlow<AttachedRuntimeSession?> = _attachedSession.asStateFlow()

    /**
     * Per-connection UUID generated fresh each time [openAttachedSession] creates a new
     * [AttachedRuntimeSession] (PR-19).
     *
     * Distinct from [AttachedRuntimeSession.sessionId]: while [sessionId] identifies the
     * application-level attached session, [_currentRuntimeSessionId] tracks the specific
     * runtime connection instance within that session.  The host registry can use the two
     * IDs together to distinguish reconnect events from entirely new sessions.
     *
     * `null` until the first [openAttachedSession] call; remains set through the session
     * lifecycle (including after detach) so that [currentHostSessionSnapshot] can project
     * a complete snapshot for any session state.
     */
    @Volatile
    private var _currentRuntimeSessionId: String? = null

    /**
     * The **authoritative observable host-session projection** for this Android attached
     * runtime (PR-22 — attached-runtime lifecycle projection consolidation).
     *
     * This [StateFlow] is the single, canonical observable path through which all
     * external consumers (host registry, diagnostics, test assertions) should observe
     * attached-runtime session truth.  It is guaranteed to emit a fresh
     * [AttachedRuntimeHostSessionSnapshot] on every lifecycle transition:
     *  - **attach** — non-`null` snapshot with [AttachedRuntimeHostSessionSnapshot.isReuseValid]`=true`.
     *  - **detach** — snapshot with [AttachedRuntimeHostSessionSnapshot.isReuseValid]`=false`.
     *  - **invalidate** — snapshot with [AttachedRuntimeHostSessionSnapshot.invalidationReason] set.
     *  - **reconnect** — snapshot with a new [AttachedRuntimeHostSessionSnapshot.runtimeSessionId].
     *  - **delegated execution accepted** — snapshot with incremented
     *    [AttachedRuntimeHostSessionSnapshot.delegatedExecutionCount].
     *
     * `null` before the first [openAttachedSession] call.
     *
     * This flow is **derived exclusively from [_attachedSession] and [_currentRuntimeSessionId]**
     * via [updateHostSessionSnapshot]; no other component should update the underlying
     * [_hostSessionSnapshot] directly.  All attached-runtime mutations must go through
     * [openAttachedSession], [closeAttachedSession], or [recordDelegatedExecutionAccepted]
     * on [RuntimeController], which keep this projection in sync automatically.
     *
     * For a point-in-time query, use [currentHostSessionSnapshot].
     */
    private val _hostSessionSnapshot = MutableStateFlow<AttachedRuntimeHostSessionSnapshot?>(null)
    val hostSessionSnapshot: StateFlow<AttachedRuntimeHostSessionSnapshot?> =
        _hostSessionSnapshot.asStateFlow()

    /**
     * The **authoritative observable delegated target readiness projection** for this
     * Android attached runtime (PR-24 — selection truth consolidation).
     *
     * This [StateFlow] is the stable, canonical observable path through which external
     * consumers (main-repository target selection policy, diagnostics) should observe the
     * pre-classified readiness / suitability / selection-outcome truth for this device.
     * It is guaranteed to emit a fresh [DelegatedTargetReadinessProjection] on every
     * lifecycle transition — in lock-step with [hostSessionSnapshot].
     *
     * Unlike [currentDelegatedTargetReadinessProjection] (point-in-time query), this flow
     * allows reactive consumption and ensures selection decisions are always based on the
     * latest emitted truth rather than an ad-hoc poll.
     *
     * `null` before the first [openAttachedSession] call.
     *
     * Derived exclusively from [_hostSessionSnapshot] via [updateHostSessionSnapshot]; no
     * other component should update the underlying [_targetReadinessProjection] directly.
     */
    // Invariant: written exclusively inside [updateHostSessionSnapshot]; never mutated directly elsewhere in this class.
    private val _targetReadinessProjection = MutableStateFlow<DelegatedTargetReadinessProjection?>(null)
    val targetReadinessProjection: StateFlow<DelegatedTargetReadinessProjection?> =
        _targetReadinessProjection.asStateFlow()

    /**
     * PR-29 — Whether a remote (delegated) execution is currently in flight.
     *
     * Set to `true` by [onRemoteTaskStarted] and back to `false` by [onRemoteTaskFinished].
     * Surface layers ([com.ufo.galaxy.ui.viewmodel.MainViewModel],
     * [com.ufo.galaxy.service.EnhancedFloatingService]) may observe this flow to
     * deterministically clear any "loading" or "in-control" UI indicator without tracking
     * the flag in their own local state.
     *
     * The value is authoritative only for **delegated/remote** execution.  Local execution
     * state is tracked separately by [com.ufo.galaxy.ui.viewmodel.MainViewModel.isLoading]
     * via the [com.ufo.galaxy.input.InputRouter] callback.
     */
    private val _isRemoteExecutionActive = MutableStateFlow(false)
    val isRemoteExecutionActive: StateFlow<Boolean> = _isRemoteExecutionActive.asStateFlow()

    /**
     * PR-31 — Observable canonical snapshot of all rollout-control flags.
     *
     * Emits a fresh [RolloutControlSnapshot] whenever any of the underlying rollout flags
     * change via [applyKillSwitch] or direct [AppSettings] mutation followed by a call to
     * [refreshRolloutControlSnapshot].  The snapshot is always consistent with the current
     * [settings] state at emission time.
     *
     * `null` is never emitted; the initial value is derived from [settings] at construction
     * time so that observers always have a valid baseline.
     *
     * External consumers (diagnostics, test assertions) should prefer this flow over
     * reading individual [AppSettings] flags so they get an atomic, stable view.
     */
    private val _rolloutControlSnapshot =
        MutableStateFlow(RolloutControlSnapshot.from(settings))
    val rolloutControlSnapshot: StateFlow<RolloutControlSnapshot> =
        _rolloutControlSnapshot.asStateFlow()

    /**
     * PR-33 — Observable product-grade reconnect recovery state.
     *
     * Tracks the current phase of the WS reconnect lifecycle from the **user's perspective**
     * and from Android's role as a **recovery participant** (not coordinator).  Transitions
     * to [ReconnectRecoveryState.RECOVERING] when the WS drops while the runtime is
     * [RuntimeState.Active] (transparent auto-reconnect in progress), to
     * [ReconnectRecoveryState.RECOVERED] when the WS successfully re-establishes
     * (the durable continuity epoch is already updated before RECOVERED is set), and
     * to [ReconnectRecoveryState.FAILED] when reconnect attempts are exhausted or the WS
     * emits a terminal error.  Always reset to [ReconnectRecoveryState.IDLE] by [stop].
     *
     * **Continuity ordering guarantee**: [ReconnectRecoveryState.RECOVERED] is set only
     * after [openAttachedSession] increments [DurableSessionContinuityRecord.sessionContinuityEpoch]
     * and emits [V2MultiDeviceLifecycleEvent.DeviceReconnected].  Observers of this flow
     * can read [durableSessionContinuityRecord] as soon as they see RECOVERED and be
     * certain the epoch already reflects the completed reconnect cycle.
     *
     * Surface layers ([com.ufo.galaxy.ui.viewmodel.MainViewModel],
     * [com.ufo.galaxy.service.EnhancedFloatingService]) observe this flow to show a
     * user-comprehensible "Recovering…" indicator during short disconnects rather than
     * just toggling `isConnected=false` — making the system appear recoverable to users.
     *
     * The [ReconnectRecoveryState] wire values are stable and must not be renamed after
     * this PR ships without a corresponding V2 contract update.
     */
    private val _reconnectRecoveryState =
        MutableStateFlow(ReconnectRecoveryState.IDLE)
    val reconnectRecoveryState: StateFlow<ReconnectRecoveryState> =
        _reconnectRecoveryState.asStateFlow()

    /**
     * PR-37 — Observable runtime lifecycle state transition event stream.
     *
     * Emits a [RuntimeLifecycleTransitionEvent] for every state change driven by
     * [transitionState].  Both [RuntimeLifecycleTransitionEvent.Governed] (expected)
     * and [RuntimeLifecycleTransitionEvent.Unexpected] (race/ordering anomaly) events
     * are emitted so consumers can observe both normal and anomalous transitions.
     *
     * Backed by a buffer of [LIFECYCLE_TRANSITION_EVENT_BUFFER_CAPACITY] entries.
     * Observers should drain promptly to avoid event loss.
     */
    private val _lifecycleTransitionEvents =
        MutableSharedFlow<RuntimeLifecycleTransitionEvent>(
            extraBufferCapacity = LIFECYCLE_TRANSITION_EVENT_BUFFER_CAPACITY
        )

    /**
     * Observable stream of [RuntimeLifecycleTransitionEvent] instances (PR-37).
     *
     * Collect from a coroutine scope scoped to the component's lifetime to observe
     * all governed and unexpected state transitions for diagnostics and test assertions.
     */
    val lifecycleTransitionEvents: SharedFlow<RuntimeLifecycleTransitionEvent> =
        _lifecycleTransitionEvents.asSharedFlow()

    /**
     * PR-37 — Concurrency guard for [start].
     *
     * Prevents two concurrent callers from both passing the Active-guard in [start] and
     * entering the connection handshake simultaneously.  [AtomicBoolean.compareAndSet]
     * ensures exactly one caller proceeds; others find the guard already set and return
     * the current state.
     */
    private val _startInProgress = AtomicBoolean(false)

    /**
     * PR-2 — Observable formation rebalance event stream.
     *
     * Emits a [FormationRebalanceEvent] whenever a participant health or readiness change,
     * a reconnect-driven recovery state transition, or a role reassignment request
     * produces a rebalance-relevant condition.
     *
     * Events are produced by [notifyParticipantHealthChanged], [requestRoleReassessment],
     * and internally by the permanent WS listener when the reconnect recovery state
     * transitions.  Observers can collect this flow to drive formation-aware behavior
     * without inspecting raw [reconnectRecoveryState] transitions directly.
     *
     * Backed by a buffer of [FORMATION_REBALANCE_EVENT_BUFFER_CAPACITY] entries; if the
     * buffer is full, the oldest events are dropped.  Callers that need guaranteed delivery
     * must drain the flow promptly.
     */
    private val _formationRebalanceEvent =
        MutableSharedFlow<FormationRebalanceEvent>(extraBufferCapacity = FORMATION_REBALANCE_EVENT_BUFFER_CAPACITY)

    /**
     * Observable stream of [FormationRebalanceEvent] instances.
     *
     * Collect from a coroutine scope scoped to the component's lifetime
     * (e.g. `viewModelScope`, `serviceScope`).
     */
    val formationRebalanceEvent: SharedFlow<FormationRebalanceEvent> =
        _formationRebalanceEvent.asSharedFlow()

    /**
     * PR-43 — Observable V2 multi-device runtime lifecycle event stream.
     *
     * Emits a [V2MultiDeviceLifecycleEvent] for every Android device lifecycle transition
     * that is directly relevant to the V2 multi-device runtime harness hook integration:
     *
     *  - [V2MultiDeviceLifecycleEvent.DeviceConnected]           — device attached.
     *  - [V2MultiDeviceLifecycleEvent.DeviceReconnected]         — device reconnected after WS drop.
     *  - [V2MultiDeviceLifecycleEvent.DeviceDisconnected]        — device detached.
     *  - [V2MultiDeviceLifecycleEvent.DeviceDegraded]            — device entered degraded state.
     *  - [V2MultiDeviceLifecycleEvent.DeviceHealthChanged]       — execution environment health changed.
     *  - [V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged] — readiness/participation changed.
     *
     * V2 consumers should collect this flow and dispatch each event to the corresponding
     * V2 harness hook (`on_device_health_changed`, `on_participant_readiness_changed`) as
     * documented in [V2MultiDeviceLifecycleEvent].
     *
     * Heartbeat-miss events are **not** emitted; V2 should treat
     * [V2MultiDeviceLifecycleEvent.DeviceDisconnected] with `detachCause="disconnect"` as
     * the Android equivalent of a heartbeat-miss condition.
     *
     * Backed by a buffer of [V2_LIFECYCLE_EVENT_BUFFER_CAPACITY] entries.
     * Observers should drain promptly to avoid event loss.
     */
    private val _v2LifecycleEvents =
        MutableSharedFlow<V2MultiDeviceLifecycleEvent>(
            extraBufferCapacity = V2_LIFECYCLE_EVENT_BUFFER_CAPACITY
        )

    /**
     * Observable stream of [V2MultiDeviceLifecycleEvent] instances (PR-43).
     *
     * Collect from a coroutine scope scoped to the V2 harness lifetime to receive all
     * Android-side lifecycle events as V2-consumable typed events.
     */
    val v2LifecycleEvents: SharedFlow<V2MultiDeviceLifecycleEvent> =
        _v2LifecycleEvents.asSharedFlow()

    /**
     * PR-1 — Observable durable session continuity record for this Android runtime.
     *
     * Tracks the **durable session identity** that persists across WS reconnects within a
     * single activation era.  Unlike [_currentRuntimeSessionId] (per-connection) and the
     * [attachedSession]'s [AttachedRuntimeSession.sessionId] (per-attached-session),
     * [durableSessionContinuityRecord] holds a stable [DurableSessionContinuityRecord.durableSessionId]
     * that remains constant until an explicit [stop] or [invalidateSession] call.
     *
     * Lifecycle:
     *  - Created (epoch=0) when [openAttachedSession] is called with [SessionOpenSource.USER_ACTIVATION]
     *    or [SessionOpenSource.BACKGROUND_RESTORE] and no existing record is present.
     *  - Epoch incremented when [openAttachedSession] is called with [SessionOpenSource.RECONNECT_RECOVERY],
     *    preserving the same [DurableSessionContinuityRecord.durableSessionId].
     *  - Reset to `null` by [stop] and [invalidateSession], terminating the durable era.
     *
     * `null` before the first [openAttachedSession] call and after [stop] / [invalidateSession].
     *
     * The durable session record is projected into [hostSessionSnapshot] via
     * [AttachedRuntimeHostSessionSnapshot.durableSessionId] and
     * [AttachedRuntimeHostSessionSnapshot.sessionContinuityEpoch] so host-facing consumers
     * can observe the durable session continuity without additional queries.
     */
    private val _durableSessionContinuityRecord =
        MutableStateFlow<DurableSessionContinuityRecord?>(null)

    /**
     * Observable durable session continuity record (PR-1).
     *
     * Non-`null` while the runtime is within an activation era.  Resets to `null` on
     * [stop] and [invalidateSession].  Observers can use
     * [DurableSessionContinuityRecord.durableSessionId] as the most-stable Android-side
     * session anchor across multiple reconnects.
     */
    val durableSessionContinuityRecord: StateFlow<DurableSessionContinuityRecord?> =
        _durableSessionContinuityRecord.asStateFlow()

    /**
     * PR-29 — Deduplication guard for [notifyTakeoverFailed].
     *
     * Stores the set of `takeoverId` values for which a [TakeoverFallbackEvent] has already
     * been emitted during the current [attachedSession] lifetime.  A second call to
     * [notifyTakeoverFailed] with the same `takeoverId` is silently dropped, preventing
     * the UI from presenting two error messages for a single logical failure (e.g. when both
     * the explicit failure outcome and a simultaneous WS disconnect both try to notify).
     *
     * Cleared in [closeAttachedSession] so that a session re-open (after reconnect) starts
     * with a clean deduplication state.
     */
    private val _emittedFailureTakeoverIds = mutableSetOf<String>()

    /**
     * PR-43 — Tracks the most recently reported [ParticipantHealthState] for this device.
     *
     * Maintained by [notifyParticipantHealthChanged] and used to populate the `previousHealth`
     * field of [V2MultiDeviceLifecycleEvent.DeviceHealthChanged] events so V2 receives a
     * meaningful before/after health transition rather than a hardcoded UNKNOWN baseline.
     *
     * Initialised to [ParticipantHealthState.UNKNOWN] (no health assessment yet).
     * Reset to [ParticipantHealthState.UNKNOWN] by [stop] and [invalidateSession] to match
     * the durable-session lifecycle (health context is scoped to an activation era).
     */
    @Volatile
    private var _lastKnownHealthState: ParticipantHealthState = ParticipantHealthState.UNKNOWN

    /** Internal scope used for the observer that handles WS connection during [start]. */
    private val controllerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Permanent WS listener installed once at construction time to handle disconnect /
     * reconnect events while the runtime is in [RuntimeState.Active] (PR-23).
     *
     * **Disconnect while Active** — closes the [attachedSession] with
     * [AttachedRuntimeSession.DetachCause.DISCONNECT] so downstream observers
     * ([hostSessionSnapshot]) immediately reflect the detached truth.  No state
     * transition is triggered; the runtime stays [RuntimeState.Active] and the WS
     * client's built-in reconnect will re-establish the connection.
     *
     * **Reconnect while Active** — reopens the [attachedSession] so a fresh session
     * (with a new [_currentRuntimeSessionId]) is created for the new WS connection.
     * This prevents state drift where the old (now-stale) ATTACHED session was observed
     * by host-registry consumers after a transparent reconnect.
     *
     * The listener deliberately ignores `onConnected` / `onDisconnected` events while
     * NOT in [RuntimeState.Active] so it does not interfere with the one-shot listeners
     * installed by [start] and [connectIfEnabled].
     */
    private val permanentWsListener = object : GalaxyWebSocketClient.Listener {
        override fun onConnected() {
            // Reopen session on reconnect only when the runtime was already Active
            // (i.e. this is a transparent reconnect, not the initial connection).
            if (_state.value == RuntimeState.Active && _attachedSession.value?.isAttached != true) {
                Log.i(TAG, "[RUNTIME] WS reconnected while Active — reopening attached session")
                GalaxyLogger.log(TAG, mapOf("event" to "runtime_ws_reconnected"))
                // PR-33 / PR-04: openAttachedSession MUST be called before transitioning to
                // RECOVERED so that the durable continuity epoch is incremented and
                // DeviceReconnected is emitted on the V2 stream BEFORE any observer can
                // read reconnectRecoveryState == RECOVERED.  This ordering guarantee means
                // that whenever a consumer observes RECOVERED, durableSessionContinuityRecord
                // already holds the new epoch for this reconnect cycle.
                openAttachedSession(SessionOpenSource.RECONNECT_RECOVERY)
                // PR-33: Mark recovery as successful if we were in the middle of recovery so
                // the UI can transition from "Recovering…" to "Connected" / normal state.
                if (_reconnectRecoveryState.value == ReconnectRecoveryState.RECOVERING) {
                    _reconnectRecoveryState.value = ReconnectRecoveryState.RECOVERED
                    GalaxyLogger.log(
                        GalaxyLogger.TAG_RECONNECT_RECOVERY,
                        mapOf(
                            "transition" to "recovering→recovered",
                            "trigger" to "ws_reconnected_active"
                        )
                    )
                    Log.i(TAG, "[RUNTIME] Reconnect recovery: RECOVERING → RECOVERED")
                    // PR-2: Emit a ParticipantRejoined formation rebalance event so formation
                    // observers know this participant is back and formation can re-evaluate.
                    emitFormationRebalanceForRecovery(
                        previousRecoveryState = ReconnectRecoveryState.RECOVERING,
                        newRecoveryState = ReconnectRecoveryState.RECOVERED
                    )
                }
            }
        }

        override fun onDisconnected() {
            // Close the attached session only when the runtime is Active — transient WS
            // drops should not leave a stale ATTACHED session visible to host-registry
            // consumers.  The runtime state stays Active; the WS client will reconnect.
            if (_state.value == RuntimeState.Active) {
                Log.w(TAG, "[RUNTIME] WS disconnected while Active — closing attached session")
                GalaxyLogger.log(TAG, mapOf("event" to "runtime_ws_disconnected_active"))
                closeAttachedSession(AttachedRuntimeSession.DetachCause.DISCONNECT)
                // PR-33: Surface the in-progress reconnect as RECOVERING so the UI can show
                // a "Recovering…" indicator rather than just clearing the connected flag.
                _reconnectRecoveryState.value = ReconnectRecoveryState.RECOVERING
                GalaxyLogger.log(
                    GalaxyLogger.TAG_RECONNECT_RECOVERY,
                    mapOf(
                        "transition" to "idle→recovering",
                        "trigger" to "ws_disconnect_active"
                    )
                )
                Log.i(TAG, "[RUNTIME] Reconnect recovery: IDLE → RECOVERING")
                // PR-2: Emit a ReadinessChanged formation rebalance event so formation
                // observers know this participant is temporarily unavailable.
                emitFormationRebalanceForRecovery(
                    previousRecoveryState = ReconnectRecoveryState.IDLE,
                    newRecoveryState = ReconnectRecoveryState.RECOVERING
                )
            }
        }

        override fun onError(error: String) {
            // PR-33: If a WS error occurs while recovery is in progress (e.g. max reconnect
            // attempts exhausted), mark recovery as failed so the UI can show a "Connection
            // failed — please reconnect" CTA instead of an indefinite "Recovering…" banner.
            if (_reconnectRecoveryState.value == ReconnectRecoveryState.RECOVERING) {
                _reconnectRecoveryState.value = ReconnectRecoveryState.FAILED
                GalaxyLogger.log(
                    GalaxyLogger.TAG_RECONNECT_RECOVERY,
                    mapOf(
                        "transition" to "recovering→failed",
                        "trigger" to "ws_error",
                        "error" to error
                    )
                )
                Log.w(TAG, "[RUNTIME] Reconnect recovery: RECOVERING → FAILED (error=$error)")
                // PR-2: Emit a ReadinessChanged formation rebalance event so formation
                // observers know this participant has been withdrawn from formation.
                emitFormationRebalanceForRecovery(
                    previousRecoveryState = ReconnectRecoveryState.RECOVERING,
                    newRecoveryState = ReconnectRecoveryState.FAILED
                )
            }
        }

        override fun onMessage(message: String) = Unit
    }

    init {
        webSocketClient.addListener(permanentWsListener)
    }

    /**
     * Starts the cross-device runtime:
     * 1. Enables the WS client and updates [settings.crossDeviceEnabled].
     * 2. Attempts to connect within [registrationTimeoutMs].
     * 3. On success → [RuntimeState.Active].
     * 4. On failure/timeout → emits [registrationError], falls back to local-only,
     *    and returns `false`.
     *
     * This function is **suspend**; call it from a coroutine (e.g., `viewModelScope`).
     *
     * @return `true` when the runtime is now active; `false` when fallback occurred.
     */
    suspend fun start(): Boolean {
        if (_state.value == RuntimeState.Active) {
            Log.d(TAG, "start() called while already active — no-op")
            return true
        }

        // PR-37: Concurrency guard — if a start is already in progress, return the current
        // active state rather than racing into a second connection handshake.
        if (!_startInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "[RUNTIME] start() called while another start is in progress — returning current active state")
            GalaxyLogger.log(TAG, mapOf("event" to "runtime_start_concurrent_guard_hit"))
            return _state.value == RuntimeState.Active
        }

        try {
        transitionState(to = RuntimeState.Starting, expectedFrom = null, trigger = "start")
        Log.i(TAG, "[RUNTIME] Starting cross-device runtime (timeout=${registrationTimeoutMs}ms)")
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_start", "timeout_ms" to registrationTimeoutMs))

        // Enable WS client for this attempt.
        syncWebSocketRuntimeSettings()
        webSocketClient.setCrossDeviceEnabled(true)
        settings.crossDeviceEnabled = true

        // Use a temporary listener to observe the first connection result.
        // The listener is removed once we have a definitive success or failure.
        var registrationListener: GalaxyWebSocketClient.Listener? = null

        val connected = suspendCancellableCoroutine<Boolean> { cont ->
            val listener = object : GalaxyWebSocketClient.Listener {
                override fun onConnected() {
                    Log.i(TAG, "[RUNTIME] WS connected — registration complete")
                    GalaxyLogger.log(TAG, mapOf("event" to "runtime_registered"))
                    webSocketClient.removeListener(this)
                    if (cont.isActive) cont.resume(true)
                }

                override fun onDisconnected() {
                    // Only treat disconnect as failure while we're still waiting to connect.
                    if (_state.value == RuntimeState.Starting) {
                        webSocketClient.removeListener(this)
                        if (cont.isActive) cont.resume(false)
                    }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "[RUNTIME] WS error during registration: $error")
                    webSocketClient.removeListener(this)
                    if (cont.isActive) cont.resume(false)
                }

                override fun onMessage(message: String) = Unit
            }

            registrationListener = listener
            webSocketClient.addListener(listener)
            webSocketClient.connect()

            cont.invokeOnCancellation {
                webSocketClient.removeListener(listener)
            }
        }

        // Clean up any stale reference (defensive — already removed inside the listener).
        registrationListener?.let { webSocketClient.removeListener(it) }

        return if (connected) {
            transitionState(to = RuntimeState.Active, expectedFrom = RuntimeState.Starting, trigger = "ws_connected")
            openAttachedSession(SessionOpenSource.USER_ACTIVATION)
            Log.i(TAG, "[RUNTIME] Runtime is now Active")
            true
        } else {
            val reason = "跨设备注册失败：无法连接到 Gateway。请检查网络或服务器设置。"
            handleFailure(reason)
            false
        }
        } finally {
            _startInProgress.set(false)
        }
    }

    /**
     * Starts the cross-device runtime with a connection timeout.
     *
     * Wraps [start] inside a [withTimeout] block so that if the WS never connects
     * (e.g. wrong server URL, no route to host), the controller falls back to
     * local-only mode instead of waiting forever.
     *
     * Must be called from a coroutine context (e.g. `viewModelScope` or `serviceScope`).
     *
     * @return `true` if the runtime became [RuntimeState.Active]; `false` on any failure.
     */
    suspend fun startWithTimeout(): Boolean {
        return try {
            withTimeout(registrationTimeoutMs) { start() }
        } catch (e: TimeoutCancellationException) {
            val reason = "跨设备注册超时（${registrationTimeoutMs / 1000}秒）。请检查 Gateway 是否在线。"
            Log.w(TAG, "[RUNTIME] Registration timed out: $reason")
            handleFailure(reason)
            false
        }
    }

    /**
     * Restores the cross-device runtime state from [settings] without going through the
     * full user-initiated [startWithTimeout] / [start] flow.
     *
     * **When to call**: from [com.ufo.galaxy.service.GalaxyConnectionService.onStartCommand]
     * on service (re)start, and from [com.ufo.galaxy.ui.viewmodel.MainViewModel.onResume] on
     * activity resume.  This is the **only** place [GalaxyWebSocketClient.connect] should be
     * invoked outside of [start]; all other callers must use [startWithTimeout] or [stop].
     *
     * Behaviour:
     * - If [AppSettings.crossDeviceEnabled] is `true`: ensures the WS client is enabled,
     *   installs a one-shot listener to transition to [RuntimeState.Active] when the WS
     *   connects, and calls [GalaxyWebSocketClient.connect].  If the connection fails or is
     *   lost, the WS client's built-in exponential-backoff reconnect handles retries silently
     *   (no [registrationError] emitted, no settings mutation).
     * - If [AppSettings.crossDeviceEnabled] is `false`: disables the WS client and transitions
     *   to [RuntimeState.LocalOnly] (no-op if already there).
     *
     * This method is **non-suspending** and thread-safe; it may be called from any thread.
     */
    fun connectIfEnabled() {
        val crossDeviceOn = settings.crossDeviceEnabled
        Log.d(TAG, "[RUNTIME] connectIfEnabled: crossDevice=$crossDeviceOn state=${_state.value}")
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_connect_if_enabled", "cross_device" to crossDeviceOn))

        syncWebSocketRuntimeSettings()
        webSocketClient.setCrossDeviceEnabled(crossDeviceOn)

        if (!crossDeviceOn) {
            if (_state.value !is RuntimeState.LocalOnly) {
                transitionState(to = RuntimeState.LocalOnly, expectedFrom = null, trigger = "connect_if_enabled_local_only")
            }
            return
        }

        // Only install a state-transition listener when we are not already in the middle of
        // a start() or already active — avoid double-listener races.
        if (_state.value !is RuntimeState.Starting && _state.value !is RuntimeState.Active) {
            transitionState(to = RuntimeState.Starting, expectedFrom = null, trigger = "connect_if_enabled_cross_device")
            webSocketClient.addListener(object : GalaxyWebSocketClient.Listener {
                override fun onConnected() {
                    Log.i(TAG, "[RUNTIME] connectIfEnabled: WS connected → Active")
                    GalaxyLogger.log(TAG, mapOf("event" to "runtime_restore_active"))
                    webSocketClient.removeListener(this)
                    if (_state.value == RuntimeState.Starting) {
                        transitionState(to = RuntimeState.Active, expectedFrom = RuntimeState.Starting, trigger = "connect_if_enabled_ws_connected")
                        openAttachedSession(SessionOpenSource.BACKGROUND_RESTORE)
                    }
                }

                override fun onDisconnected() {
                    webSocketClient.removeListener(this)
                    if (_state.value == RuntimeState.Starting) {
                        // Transient failure — fall back to LocalOnly; WS reconnect will retry.
                        closeAttachedSession(AttachedRuntimeSession.DetachCause.DISCONNECT)
                        transitionState(to = RuntimeState.LocalOnly, expectedFrom = RuntimeState.Starting, trigger = "connect_if_enabled_ws_failure")
                    }
                }

                override fun onError(error: String) {
                    webSocketClient.removeListener(this)
                    if (_state.value == RuntimeState.Starting) {
                        closeAttachedSession(AttachedRuntimeSession.DetachCause.DISCONNECT)
                        transitionState(to = RuntimeState.LocalOnly, expectedFrom = RuntimeState.Starting, trigger = "connect_if_enabled_ws_failure")
                    }
                }

                override fun onMessage(message: String) = Unit
            })
        }

        webSocketClient.connect()
    }

    /**
     * Stops the cross-device runtime cleanly:
     * 1. Disconnects the WebSocket.
     * 2. Disables cross-device in [settings] and the WS client.
     * 3. Closes the [attachedSession] with [AttachedRuntimeSession.DetachCause.DISABLE].
     * 4. Transitions to [RuntimeState.LocalOnly].
     */
    fun stop() {
        Log.i(TAG, "[RUNTIME] Stopping cross-device runtime")
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_stop"))
        settings.crossDeviceEnabled = false
        webSocketClient.setCrossDeviceEnabled(false)
        if (webSocketClient.isConnected()) {
            webSocketClient.disconnect()
        }
        closeAttachedSession(AttachedRuntimeSession.DetachCause.DISABLE)
        // PR-29: A runtime stop should also clear any in-flight remote execution state
        // so that surface layers do not display a stale loading/in-control indicator.
        _isRemoteExecutionActive.value = false
        // PR-33: Reset recovery state to IDLE on explicit stop so the UI returns to a
        // clean baseline.  Any ongoing "Recovering…" or "Failed" banner should disappear
        // when the user deliberately turns off cross-device or triggers a full reconnect.
        _reconnectRecoveryState.value = ReconnectRecoveryState.IDLE
        // PR-1: Terminate the durable session era on explicit stop.  The next activation
        // will start a fresh era with a new durableSessionId.
        _durableSessionContinuityRecord.value = null
        // PR-43: Reset health-state tracker so the next activation era starts from UNKNOWN.
        _lastKnownHealthState = ParticipantHealthState.UNKNOWN
        // PR-37: Use transitionState() so the transition event is observable and any
        // unexpected prior state is surfaced as a diagnostic event.
        transitionState(to = RuntimeState.LocalOnly, expectedFrom = null, trigger = "stop")
        // PR-31: Refresh the rollout snapshot after stopping.
        refreshRolloutControlSnapshot()
    }

    /**
     * PR-31 — Kill-switch: atomically disables all remote execution paths.
     *
     * This is the canonical one-call safe-disable entry point for production rollback
     * and emergency shutdown scenarios.  It performs an atomic sequence:
     *  1. Sets [AppSettings.crossDeviceEnabled] = `false` and [AppSettings.goalExecutionEnabled]
     *     = `false` so that both the WS connection guard and the goal-execution feature flag
     *     are off — independently ensuring that no new delegated task is accepted.
     *  2. Calls [stop] to disconnect the WebSocket, detach the [attachedSession], clear any
     *     in-flight remote execution state, and transition to [RuntimeState.LocalOnly].
     *  3. Emits a [GalaxyLogger.TAG_KILL_SWITCH] structured log entry for operator audit.
     *  4. Refreshes [rolloutControlSnapshot] so observers see the new state immediately.
     *
     * The [AppSettings.delegatedExecutionAllowed] and [AppSettings.fallbackToLocalAllowed]
     * flags are deliberately **not** cleared: delegation and fallback are already blocked by
     * the two primary flags above, and leaving them `true` makes it straightforward to
     * re-enable the full system by only flipping the primary flags — without having to
     * remember which of the fine-grained flags were previously active.
     *
     * This method is **non-suspending** and safe to call from any thread.
     *
     * @param reason Optional human-readable explanation for the kill-switch activation,
     *               included in the structured log entry for operator traceability.
     */
    fun applyKillSwitch(reason: String = "operator kill-switch") {
        Log.w(TAG, "[RUNTIME] Kill-switch activated: $reason")
        // Atomically disable the two primary remote-execution gates.
        settings.crossDeviceEnabled = false
        settings.goalExecutionEnabled = false
        // PR-31: Log each flag change individually so operators can filter by flag name.
        GalaxyLogger.log(
            GalaxyLogger.TAG_ROLLOUT_CONTROL,
            mapOf(
                "flag" to RolloutControlSnapshot.KEY_CROSS_DEVICE_ALLOWED,
                "value" to false,
                "source" to "kill_switch"
            )
        )
        GalaxyLogger.log(
            GalaxyLogger.TAG_ROLLOUT_CONTROL,
            mapOf(
                "flag" to RolloutControlSnapshot.KEY_GOAL_EXECUTION_ALLOWED,
                "value" to false,
                "source" to "kill_switch"
            )
        )
        // Emit the high-level kill-switch event.
        GalaxyLogger.log(
            GalaxyLogger.TAG_KILL_SWITCH,
            mapOf("reason" to reason)
        )
        // Cleanly stop all active cross-device work (also refreshes rollout snapshot).
        stop()
    }

    /**
     * PR-31 — Refreshes [rolloutControlSnapshot] from the current [settings] state.
     *
     * Must be called whenever any rollout-control flag in [settings] is mutated outside
     * of [applyKillSwitch] so that the observable snapshot stays in sync.  [stop] and
     * [applyKillSwitch] call this automatically; other callers (e.g. after a settings
     * save) should also call this to keep the snapshot current.
     *
     * This method is safe to call from any thread.
     */
    fun refreshRolloutControlSnapshot() {
        _rolloutControlSnapshot.value = RolloutControlSnapshot.from(settings)
    }



    /**
     * Must be called when the Gateway assigns a remote task (`task_assign` or
     * `goal_execution`). Cancels any running local [LoopController] session and marks
     * the loop as blocked while the remote task executes.
     *
     * AIP v3 compliance: only tasks delivered via `task_assign` / `goal_execution`
     * envelopes trigger this; direct user input via [com.ufo.galaxy.input.InputRouter]
     * in cross-device mode goes uplink as `task_submit` and is not affected.
     */
    fun onRemoteTaskStarted() {
        Log.d(TAG, "[RUNTIME] Remote task started — cancelling any local loop")
        GalaxyLogger.log(TAG, mapOf("event" to "remote_task_started"))
        // PR-29: Signal that a remote execution is now in flight so surface layers can
        // show a deterministic "loading / in-control" indicator without local state tracking.
        _isRemoteExecutionActive.value = true
        loopController.cancelForRemoteTask()
    }

    /**
     * Must be called after the device has sent back the `task_result` / `goal_result`
     * to the Gateway. Clears the remote-task block so that the local [LoopController]
     * may handle the next user request.
     *
     * This does not automatically re-launch a local loop; the next user input will do so.
     */
    fun onRemoteTaskFinished() {
        Log.d(TAG, "[RUNTIME] Remote task finished — local loop unblocked")
        GalaxyLogger.log(TAG, mapOf("event" to "remote_task_finished"))
        // PR-29: Clear the in-flight flag so surface layers can dismiss loading indicators.
        _isRemoteExecutionActive.value = false
        loopController.clearRemoteTaskBlock()
    }

    /**
     * Notifies all surface layers that a delegated-takeover execution ended with a
     * non-completion outcome (PR-23 — cross-device failure and fallback state closure).
     *
     * This is the **canonical failure signal path** for individual takeover failures.
     * It emits a [TakeoverFallbackEvent] on [takeoverFailure] so that
     * [com.ufo.galaxy.ui.viewmodel.MainViewModel] and
     * [com.ufo.galaxy.service.EnhancedFloatingService] can clear any stale "active"
     * or "in-control" state they may have set when the takeover started.
     *
     * ## Deduplication (PR-29)
     *
     * At most one event is emitted per `takeoverId` within a single [attachedSession]
     * lifetime.  A second call with the same `takeoverId` is silently dropped to prevent
     * double-emission when both an explicit failure path and a simultaneous WS disconnect
     * race to call this method.  The deduplication set is reset in [closeAttachedSession].
     *
     * ## What this method does NOT do
     *
     * - Does **not** change [state] or close [attachedSession] — takeover-level failures
     *   do not represent a loss of the cross-device session; the device remains attached
     *   and ready for the next incoming delegated task.
     * - Does **not** call [onRemoteTaskFinished] — that must be called by the caller
     *   (typically in a `finally` block around the entire takeover coroutine) so the loop
     *   block is cleared regardless of whether this method is called.
     *
     * ## Ordering guarantee
     *
     * Callers must call [onRemoteTaskFinished] **after** (or in the same `finally` block
     * as) [notifyTakeoverFailed] so the [LoopController] block is cleared in the correct
     * order: failure event observed → local execution re-enabled.
     *
     * @param takeoverId  Stable takeover identifier from the original request envelope.
     * @param taskId      Task identifier from the original request envelope.
     * @param traceId     Trace identifier for cross-layer log correlation.
     * @param reason      Human-readable failure description for diagnostics.
     * @param cause       Machine-readable failure classification.
     */
    suspend fun notifyTakeoverFailed(
        takeoverId: String,
        taskId: String,
        traceId: String,
        reason: String,
        cause: TakeoverFallbackEvent.Cause
    ) {
        // PR-29: Deduplication guard — drop a second notification for the same takeoverId
        // within the same session lifetime to prevent double-emission when both an explicit
        // failure path and a simultaneous WS disconnect both attempt to notify.
        synchronized(_emittedFailureTakeoverIds) {
            if (!_emittedFailureTakeoverIds.add(takeoverId)) {
                Log.d(
                    TAG,
                    "[RUNTIME] notifyTakeoverFailed: duplicate suppressed takeover_id=$takeoverId cause=${cause.wireValue}"
                )
                return
            }
        }
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "takeover_failed",
                "takeover_id" to takeoverId,
                "task_id" to taskId,
                "trace_id" to traceId,
                "reason" to reason,
                "cause" to cause.wireValue
            )
        )
        // PR-30: Emit a structured fallback-decision signal for operator diagnostics.
        // This is distinct from the takeover_failed event above — it uses the stable
        // TAG_FALLBACK_DECISION tag so operators can filter fallback decisions across
        // all execution-path log entries without parsing the `event` field.
        GalaxyLogger.log(
            GalaxyLogger.TAG_FALLBACK_DECISION,
            mapOf(
                "takeover_id" to takeoverId,
                "task_id" to taskId,
                "cause" to cause.wireValue,
                "reason" to reason
            )
        )
        Log.w(
            TAG,
            "[RUNTIME] Takeover failed: takeover_id=$takeoverId task_id=$taskId " +
                "trace_id=$traceId reason=$reason cause=${cause.wireValue}"
        )
        _takeoverFailure.emit(
            TakeoverFallbackEvent(
                takeoverId = takeoverId,
                taskId = taskId,
                traceId = traceId,
                reason = reason,
                cause = cause
            )
        )
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Handles a registration failure: emits [registrationError] and [setupError], updates
     * [state] to [RuntimeState.Failed], closes any [attachedSession] with
     * [AttachedRuntimeSession.DetachCause.DISCONNECT], then falls back to local-only by
     * disabling cross-device in both [settings] and the [webSocketClient].
     *
     * PR-27: Also classifies the failure into a [CrossDeviceSetupError] and emits it on
     * [setupError] so that consumers can present category-appropriate recovery actions
     * without inspecting the raw [reason] string.
     */
    private suspend fun handleFailure(reason: String) {
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_failed", "reason" to reason))
        transitionState(to = RuntimeState.Failed(reason), expectedFrom = RuntimeState.Starting, trigger = "start_failure")
        _registrationError.emit(reason)
        // PR-27: Classify and emit on the typed setup-error flow.
        val setupErr = CrossDeviceSetupError.classify(reason, isGatewayConfigured())
        GalaxyLogger.log(
            TAG,
            mapOf(
                "event" to "runtime_setup_error",
                "category" to setupErr.category.wireValue,
                "can_retry" to setupErr.canRetry
            )
        )
        _setupError.emit(setupErr)
        closeAttachedSession(AttachedRuntimeSession.DetachCause.DISCONNECT)
        // Fall back to local mode.
        settings.crossDeviceEnabled = false
        webSocketClient.setCrossDeviceEnabled(false)
        if (webSocketClient.isConnected()) {
            webSocketClient.disconnect()
        }
        transitionState(to = RuntimeState.LocalOnly, expectedFrom = null, trigger = "failure_fallback")
        Log.w(TAG, "[RUNTIME] Fell back to LocalOnly after failure: $reason (category=${setupErr.category.wireValue})")
    }

    /**
     * Stops the current runtime and immediately starts again with the same settings.
     *
     * PR-27 — This is the canonical **reconnect** entry point for:
     *  - "Save settings and reconnect" flows triggered from the network settings screen.
     *  - Category-aware retry after a [CrossDeviceSetupError] — callers should prefer
     *    this over separate [stop] + [startWithTimeout] calls to avoid leaving the
     *    runtime in an inconsistent state between the two calls.
     *
     * The method is equivalent to [stop] followed immediately by [startWithTimeout] but
     * guarantees atomicity: no external state can be observed between the stop and the
     * start.
     *
     * Must be called from a coroutine context (e.g. `viewModelScope` or `serviceScope`).
     *
     * @return `true` if the runtime became [RuntimeState.Active] after the reconnect;
     *         `false` on any failure (same semantics as [startWithTimeout]).
     */
    suspend fun reconnect(): Boolean {
        Log.i(TAG, "[RUNTIME] reconnect: stopping and restarting")
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_reconnect"))
        syncWebSocketRuntimeSettings()
        stop()
        val result = startWithTimeout()
        // PR-30: Log the reconnect outcome for operator diagnostics.
        GalaxyLogger.log(
            GalaxyLogger.TAG_RECONNECT_OUTCOME,
            mapOf(
                "outcome" to if (result) "success" else "failure",
                "state" to _state.value.javaClass.simpleName
            )
        )
        return result
    }

    fun cancel() {
        controllerScope.cancel()
    }

    /** Applies latest AppSettings connection/auth/session values onto the live WS client. */
    private fun syncWebSocketRuntimeSettings() {
        webSocketClient.updateRuntimeConnectionConfig(
            serverUrl = settings.effectiveGatewayWsUrl(),
            gatewayToken = settings.gatewayToken,
            runtimeSessionId = com.ufo.galaxy.UFOGalaxyApplication.runtimeSessionId,
            deviceId = settings.deviceId
        )
    }

    // ── Attached-runtime session management (PR-7) ────────────────────────────

    /**
     * Explicitly invalidates the current [attachedSession] with
     * [AttachedRuntimeSession.DetachCause.INVALIDATION].
     *
     * Call this when a state inconsistency (host identity change, auth expiry, protocol
     * mismatch) makes the current session no longer trustworthy.  The runtime state is
     * **not** changed; only the session is closed.  A new session will be opened if the
     * runtime reconnects with cross-device still enabled.
     *
     * PR-1: Also terminates the durable session era, since session invalidation means the
     * current session identity can no longer be trusted as a durable anchor.  The next
     * [openAttachedSession] call will start a fresh durable era.
     */
    fun invalidateSession() {
        Log.i(TAG, "[RUNTIME] Invalidating attached runtime session")
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_session_invalidated"))
        closeAttachedSession(AttachedRuntimeSession.DetachCause.INVALIDATION)
        // PR-1: Terminate the durable era — the invalidated identity is no longer a
        // trustworthy durable anchor for the center-side durable mesh/session registry.
        _durableSessionContinuityRecord.value = null
        // PR-43: Reset health-state tracker so the next activation era starts from UNKNOWN.
        _lastKnownHealthState = ParticipantHealthState.UNKNOWN
    }

    /**
     * Returns a legacy host-facing snapshot of the current attached session's reuse state
     * as a [Map], or `null` when no session is active (PR-17).
     *
     * @deprecated Consumers must migrate to [hostSessionSnapshot] (reactive
     * [StateFlow]) or [currentHostSessionSnapshot] (point-in-time typed query).
     * This untyped map projection is a HIGH_RISK_ACTIVE compatibility surface retained
     * for backward compatibility only — see [CompatibilitySurfaceRetirementRegistry]
     * entry `"runtime_host_session_legacy_map_bridge"`.
     *
     * Prefer [hostSessionSnapshot] for reactive canonical truth and
     * [currentHostSessionSnapshot] for point-in-time typed reads.
     *
     * @return An immutable [Map] with all host-facing session fields; `null` if no session exists.
     */
    @Deprecated(
        message = "Migrate to hostSessionSnapshot (StateFlow<AttachedRuntimeHostSessionSnapshot?>) " +
            "for reactive truth, or currentHostSessionSnapshot() for point-in-time typed reads. " +
            "This untyped map bridge is a HIGH_RISK_ACTIVE compatibility surface — see " +
            "CompatibilitySurfaceRetirementRegistry.",
        replaceWith = ReplaceWith("currentHostSessionSnapshot()")
    )
    fun currentSessionSnapshot(): Map<String, Any>? =
        _attachedSession.value?.toMetadataMap()

    /**
     * Returns the canonical **host-session snapshot / projection** for this Android attached
     * runtime, or `null` when no session has been opened yet (PR-19).
     *
     * The returned [AttachedRuntimeHostSessionSnapshot] is the authoritative, field-stable
     * input source for the main-repository authoritative session registry.  All nine
     * canonical fields are populated; [AttachedRuntimeHostSessionSnapshot.invalidationReason]
     * is the only conditional field — non-`null` only when the session was closed via
     * [AttachedRuntimeSession.DetachCause.INVALIDATION].
     *
     * ## Projection semantics across lifecycle events
     *
     *  - **attach** — [AttachedRuntimeHostSessionSnapshot.attachmentState]`=attached`,
     *    [AttachedRuntimeHostSessionSnapshot.isReuseValid]`=true`,
     *    [AttachedRuntimeHostSessionSnapshot.posture]`=join_runtime`.
     *  - **detach** — [AttachedRuntimeHostSessionSnapshot.attachmentState]`=detached`,
     *    [AttachedRuntimeHostSessionSnapshot.isReuseValid]`=false`,
     *    [AttachedRuntimeHostSessionSnapshot.posture]`=control_only`,
     *    [AttachedRuntimeHostSessionSnapshot.invalidationReason]`=null`.
     *  - **reconnect** — new [AttachedRuntimeHostSessionSnapshot.runtimeSessionId],
     *    [AttachedRuntimeHostSessionSnapshot.attachmentState]`=attached`,
     *    same [AttachedRuntimeHostSessionSnapshot.durableSessionId] with incremented epoch.
     *  - **invalidate** — [AttachedRuntimeHostSessionSnapshot.attachmentState]`=detached`,
     *    [AttachedRuntimeHostSessionSnapshot.invalidationReason]`="invalidation"`,
     *    [AttachedRuntimeHostSessionSnapshot.durableSessionId]`=null`.
     *
     * @return A fully populated [AttachedRuntimeHostSessionSnapshot]; `null` before the
     *         first [openAttachedSession] call.
     */
    fun currentHostSessionSnapshot(): AttachedRuntimeHostSessionSnapshot? {
        val session = _attachedSession.value ?: return null
        val runtimeSessionId = _currentRuntimeSessionId ?: return null
        val hostRole = hostDescriptor?.formationRole?.wireValue
            ?: RuntimeHostDescriptor.FormationRole.DEFAULT.wireValue
        return AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = runtimeSessionId,
            hostRole = hostRole,
            durableRecord = _durableSessionContinuityRecord.value
        )
    }

    /**
     * Returns the canonical **host-facing delegated target readiness projection** for this
     * Android attached runtime, or `null` when no session has been opened yet (PR-20).
     *
     * The returned [DelegatedTargetReadinessProjection] is the authoritative, stable input
     * for the main-repository delegated target selection policy.  It embeds the full
     * nine-field [AttachedRuntimeHostSessionSnapshot] and adds a pre-computed
     * [DelegatedTargetReadinessProjection.isSuitableTarget] boolean with an accompanying
     * [DelegatedTargetReadinessProjection.unsuitabilityReason] for diagnostics and policy
     * explainability.
     *
     * ## Projection semantics across lifecycle events
     *
     *  - **attach** — [DelegatedTargetReadinessProjection.isSuitableTarget]`=true`,
     *    [DelegatedTargetReadinessProjection.unsuitabilityReason]`=null`.
     *  - **detach** — [DelegatedTargetReadinessProjection.isSuitableTarget]`=false`,
     *    [DelegatedTargetReadinessProjection.unsuitabilityReason]`="not_attached"`.
     *  - **reconnect** — new [DelegatedTargetReadinessProjection.runtimeSessionId],
     *    [DelegatedTargetReadinessProjection.isSuitableTarget]`=true`.
     *  - **invalidate** — [DelegatedTargetReadinessProjection.isSuitableTarget]`=false`,
     *    [DelegatedTargetReadinessProjection.unsuitabilityReason]`="invalidated"`.
     *
     * @return A fully populated [DelegatedTargetReadinessProjection]; `null` before the
     *         first [openAttachedSession] call.
     */
    fun currentDelegatedTargetReadinessProjection(): DelegatedTargetReadinessProjection? {
        val snapshot = currentHostSessionSnapshot() ?: return null
        return DelegatedTargetReadinessProjection.from(snapshot)
    }

    /**
     * Returns the additive canonical participant-model projection for this Android runtime host.
     *
     * This is a non-breaking compatibility layer for cross-repo convergence: existing runtime,
     * session, readiness, capability, and transport behavior is unchanged.
     *
     * @param capabilityRefs Optional capability IDs/names to link into the participant model.
     * @return A [CanonicalParticipantModel] when [hostDescriptor] exists; otherwise `null`.
     */
    fun currentCanonicalParticipant(capabilityRefs: Set<String> = emptySet()): CanonicalParticipantModel? {
        val descriptor = hostDescriptor ?: return null
        val snapshot = currentHostSessionSnapshot()
        val readiness = snapshot?.let { DelegatedTargetReadinessProjection.from(it) }
        return AndroidParticipantModelMapper.fromRuntimeHostDescriptor(
            descriptor = descriptor,
            hostSessionSnapshot = snapshot,
            readinessProjection = readiness,
            capabilityRefs = capabilityRefs
        )
    }

    /**
     * Records that a delegated execution has been accepted under the current
     * [AttachedRuntimeSession], incrementing
     * [AttachedRuntimeSession.delegatedExecutionCount] by one (PR-14).
     *
     * This is the sole mechanism by which the session's execution counter is advanced.
     * It must be called **after** [com.ufo.galaxy.agent.DelegatedRuntimeReceiver.receive]
     * returns [com.ufo.galaxy.agent.DelegatedRuntimeReceiver.ReceiptResult.Accepted] and
     * **before** the task is handed off to
     * [com.ufo.galaxy.agent.DelegatedTakeoverExecutor.execute], so that the counter
     * reflects the number of tasks that have been dispatched — not just completed.
     *
     * ## No per-task session re-creation
     *
     * This method updates the in-place session value without creating a new
     * [AttachedRuntimeSession] or modifying [AttachedRuntimeSession.sessionId].
     * The session identity (sessionId, hostId, deviceId, attachedAtMs, state) is
     * preserved exactly; only [AttachedRuntimeSession.delegatedExecutionCount] changes.
     * This guarantees that multiple delegated tasks can flow through the same attached
     * session without any re-init overhead.
     *
     * ## No-op conditions
     *
     *  - If no session is currently active ([attachedSession] is `null`), this is a no-op.
     *  - If the current session is not in [AttachedRuntimeSession.State.ATTACHED] (i.e. it
     *    is [AttachedRuntimeSession.State.DETACHING] or [AttachedRuntimeSession.State.DETACHED]),
     *    this is a no-op: the caller must not be accepting new tasks through a non-reusable
     *    session, so incrementing would be misleading.
     */
    fun recordDelegatedExecutionAccepted() {
        val current = _attachedSession.value ?: return
        if (!current.isAttached) return
        _attachedSession.value = current.withExecutionAccepted()
        updateHostSessionSnapshot()
        Log.d(
            TAG,
            "[RUNTIME] Delegated execution accepted: session_id=${current.sessionId} " +
                "execution_count=${current.delegatedExecutionCount + 1}"
        )
    }

    /**
     * Opens a new [AttachedRuntimeSession] and publishes it on [_attachedSession].
     *
     * If [hostDescriptor] is available, its participation state is updated to
     * [RuntimeHostDescriptor.HostParticipationState.ACTIVE] and the updated descriptor
     * is propagated to [webSocketClient] via [GalaxyWebSocketClient.setRuntimeHostDescriptor].
     *
     * No-op if a session is already in [AttachedRuntimeSession.State.ATTACHED] to prevent
     * duplicate session creation on redundant connection events.
     *
     * [source] explicitly records lifecycle authority for session creation/replacement:
     *  - [SessionOpenSource.USER_ACTIVATION]   : user initiated enable/start
     *  - [SessionOpenSource.BACKGROUND_RESTORE]: process/service restore path
     *  - [SessionOpenSource.RECONNECT_RECOVERY]: transparent reconnect replacement
     *  - [SessionOpenSource.TEST_ONLY]         : test-only synthetic activation
     */
    private enum class SessionOpenSource(val wireValue: String) {
        USER_ACTIVATION("user_activation"),
        BACKGROUND_RESTORE("background_restore"),
        RECONNECT_RECOVERY("reconnect_recovery"),
        TEST_ONLY("test_only")
    }

    private fun syncHostDescriptorParticipationState(
        state: RuntimeHostDescriptor.HostParticipationState
    ) {
        val descriptor = hostDescriptor ?: return
        if (descriptor.participationState == state) return
        val updated = descriptor.withState(state)
        hostDescriptor = updated
        webSocketClient.setRuntimeHostDescriptor(updated)
    }

    private fun openAttachedSession(source: SessionOpenSource) {
        if (_attachedSession.value?.isAttached == true) {
            Log.d(TAG, "[RUNTIME] openAttachedSession: session already attached — no-op")
            return
        }
        val replacingDetachedSessionId = _attachedSession.value?.sessionId
        val descriptor = hostDescriptor
        if (descriptor == null) {
            Log.w(TAG, "[RUNTIME] openAttachedSession: no hostDescriptor available; session will use unknown-host identity")
        }
        val session = AttachedRuntimeSession.create(
            hostId = descriptor?.hostId ?: "unknown-host",
            deviceId = descriptor?.deviceId ?: settings.deviceId
        )
        // Generate a fresh per-connection runtime session ID for the snapshot projection (PR-19).
        _currentRuntimeSessionId = UUID.randomUUID().toString()
        _attachedSession.value = session

        // PR-1: Manage durable session continuity record.
        //  - USER_ACTIVATION / BACKGROUND_RESTORE / TEST_ONLY with no existing record:
        //    create a new durable era (epoch=0).
        //  - RECONNECT_RECOVERY with existing record: increment epoch (same durableSessionId).
        //  - USER_ACTIVATION / BACKGROUND_RESTORE with an existing record: preserve it.
        //    (Defensive: in practice stop() clears the record before a new activation starts.)
        val currentDurable = _durableSessionContinuityRecord.value
        _durableSessionContinuityRecord.value = when {
            source == SessionOpenSource.RECONNECT_RECOVERY && currentDurable != null ->
                currentDurable.withEpochIncremented()
            currentDurable == null ->
                DurableSessionContinuityRecord.create(source.wireValue)
            else ->
                currentDurable
        }

        updateHostSessionSnapshot()
        Log.i(
            TAG,
            "[RUNTIME] Attached runtime session opened: source=${source.wireValue} " +
                "session_id=${session.sessionId} host_id=${session.hostId} " +
                "runtime_session_id=${_currentRuntimeSessionId} " +
                "durable_session_id=${_durableSessionContinuityRecord.value?.durableSessionId} " +
                "epoch=${_durableSessionContinuityRecord.value?.sessionContinuityEpoch}"
        )
        val attachLog = mutableMapOf<String, Any>(
            "event" to "runtime_session_attached",
            "source" to source.wireValue
        )
        replacingDetachedSessionId?.let {
            attachLog["replaces_detached_session_id"] = it
        }
        _durableSessionContinuityRecord.value?.let {
            attachLog[DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID] = it.durableSessionId
            attachLog[DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH] = it.sessionContinuityEpoch
        }
        GalaxyLogger.log(TAG, attachLog + session.toMetadataMap())
        // Sync host descriptor participation state to ACTIVE.
        syncHostDescriptorParticipationState(RuntimeHostDescriptor.HostParticipationState.ACTIVE)
        // PR-37: Verify session/state alignment after opening.
        checkSessionStateAlignment()
        // PR-43: Emit V2 lifecycle event for V2 multi-device hook integration.
        // TEST_ONLY source is excluded — synthetic test activations must not appear on
        // the production V2 event stream.
        if (source != SessionOpenSource.TEST_ONLY) {
            val runtimeSessionId = _currentRuntimeSessionId ?: ""
            val durableSessionId = _durableSessionContinuityRecord.value?.durableSessionId
            val epoch = _durableSessionContinuityRecord.value?.sessionContinuityEpoch ?: 0
            val v2Event: V2MultiDeviceLifecycleEvent = if (source == SessionOpenSource.RECONNECT_RECOVERY) {
                V2MultiDeviceLifecycleEvent.DeviceReconnected(
                    deviceId = session.deviceId,
                    sessionId = session.sessionId,
                    runtimeSessionId = runtimeSessionId,
                    durableSessionId = durableSessionId,
                    sessionContinuityEpoch = epoch
                )
            } else {
                V2MultiDeviceLifecycleEvent.DeviceConnected(
                    deviceId = session.deviceId,
                    sessionId = session.sessionId,
                    runtimeSessionId = runtimeSessionId,
                    durableSessionId = durableSessionId,
                    sessionContinuityEpoch = epoch,
                    openSource = source.wireValue
                )
            }
            emitV2LifecycleEvent(v2Event)
        }
    }

    /**
     * Closes the current [AttachedRuntimeSession] with the given [cause] and publishes
     * the detached session on [_attachedSession].
     *
     * If [hostDescriptor] is available, its participation state is updated to
     * [RuntimeHostDescriptor.HostParticipationState.INACTIVE] and the updated descriptor
     * is propagated to [webSocketClient].
     *
     * No-op if no session is currently attached.
     */
    private fun closeAttachedSession(cause: AttachedRuntimeSession.DetachCause) {
        val current = _attachedSession.value ?: return
        if (current.isDetached) return
        val detached = current.detachedWith(cause)
        _attachedSession.value = detached
        updateHostSessionSnapshot()
        Log.i(TAG, "[RUNTIME] Attached runtime session closed: session_id=${detached.sessionId} cause=${cause.wireValue}")
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_session_detached") + detached.toMetadataMap())
        // PR-29: Clear the takeover deduplication set on session close so that any
        // subsequent session re-open begins with a clean deduplication state.
        synchronized(_emittedFailureTakeoverIds) {
            _emittedFailureTakeoverIds.clear()
        }
        // Sync host descriptor participation state to INACTIVE.
        syncHostDescriptorParticipationState(RuntimeHostDescriptor.HostParticipationState.INACTIVE)
        // PR-37: Verify session/state alignment after closing.  A closed session while
        // Active is expected during transient WS disconnect (recovery in progress).
        checkSessionStateAlignment()
        // PR-43: Emit V2 lifecycle event for V2 multi-device hook integration.
        val sessionDurationMs = detached.durationMs
        emitV2LifecycleEvent(
            V2MultiDeviceLifecycleEvent.DeviceDisconnected(
                deviceId = detached.deviceId,
                sessionId = detached.sessionId,
                detachCause = cause.wireValue,
                sessionDurationMs = sessionDurationMs
            )
        )
    }

    // ── Snapshot projection sync ──────────────────────────────────────────────

    /**
     * Recomputes and publishes the [AttachedRuntimeHostSessionSnapshot] on
     * [_hostSessionSnapshot] and the derived [DelegatedTargetReadinessProjection] on
     * [_targetReadinessProjection] (PR-24).
     *
     * Called after every mutation of [_attachedSession] or [_currentRuntimeSessionId]
     * to keep [hostSessionSnapshot] and [targetReadinessProjection] in sync with the
     * canonical session truth.  This is the **only** place both flows are written,
     * ensuring a single update path and preventing divergence between the two surfaces.
     */
    private fun updateHostSessionSnapshot() {
        val snapshot = currentHostSessionSnapshot()
        _hostSessionSnapshot.value = snapshot
        _targetReadinessProjection.value = snapshot?.let {
            DelegatedTargetReadinessProjection.from(it)
        }
    }

    // ── PR-43: V2 lifecycle event emission helper ─────────────────────────────

    /**
     * PR-43 — Emits a [V2MultiDeviceLifecycleEvent] on [_v2LifecycleEvents] and logs a
     * structured entry under [GalaxyLogger.TAG_V2_LIFECYCLE].
     *
     * This is the **single write path** for [_v2LifecycleEvents].  All V2 lifecycle event
     * emissions must go through this method so the structured log entry is always emitted
     * alongside the flow event.
     *
     * @param event The [V2MultiDeviceLifecycleEvent] to emit.
     */
    private fun emitV2LifecycleEvent(event: V2MultiDeviceLifecycleEvent) {
        val fields = buildMap<String, Any> {
            put("event", event.wireValue)
            put("device_id", event.deviceId)
            event.sessionId?.let { put("session_id", it) }
            when (event) {
                is V2MultiDeviceLifecycleEvent.DeviceConnected -> {
                    put("runtime_session_id", event.runtimeSessionId)
                    event.durableSessionId?.let { put("durable_session_id", it) }
                    put("session_continuity_epoch", event.sessionContinuityEpoch)
                    put("open_source", event.openSource)
                }
                is V2MultiDeviceLifecycleEvent.DeviceReconnected -> {
                    put("runtime_session_id", event.runtimeSessionId)
                    event.durableSessionId?.let { put("durable_session_id", it) }
                    put("session_continuity_epoch", event.sessionContinuityEpoch)
                }
                is V2MultiDeviceLifecycleEvent.DeviceDisconnected -> {
                    put("detach_cause", event.detachCause)
                    put("session_duration_ms", event.sessionDurationMs)
                }
                is V2MultiDeviceLifecycleEvent.DeviceDegraded -> {
                    put("degradation_kind", event.degradationKind)
                    put("continuation_mode", event.continuationMode)
                    event.durableSessionId?.let { put("durable_session_id", it) }
                    event.sessionContinuityEpoch?.let { put("session_continuity_epoch", it) }
                }
                is V2MultiDeviceLifecycleEvent.DeviceHealthChanged -> {
                    put("previous_health", event.previousHealth)
                    put("current_health", event.currentHealth)
                    put("requires_rebalance", event.requiresRebalance)
                    put("continuation_mode", event.continuationMode)
                    put("trigger", event.trigger)
                }
                is V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged -> {
                    put("previous_readiness", event.previousReadiness)
                    put("current_readiness", event.currentReadiness)
                    put("previous_participation", event.previousParticipation)
                    put("current_participation", event.currentParticipation)
                    put("trigger", event.trigger)
                }
            }
        }
        GalaxyLogger.log(GalaxyLogger.TAG_V2_LIFECYCLE, fields)
        _v2LifecycleEvents.tryEmit(event)
    }

    // ── Setup error helpers (PR-27) ───────────────────────────────────────────

    /**
     * Returns `true` when the current [settings] contain a real (non-placeholder) gateway
     * address — either a non-blank [AppSettings.gatewayHost] override, or a
     * [AppSettings.galaxyGatewayUrl] that does not match the `100.x.x.x` placeholder
     * pattern used in the default build configuration.
     *
     * Called by [handleFailure] to determine the correct [CrossDeviceSetupError.Category]
     * for the emitted [setupError] event.
     */
    private fun isGatewayConfigured(): Boolean {
        if (settings.gatewayHost.isNotBlank()) return true
        val url = settings.galaxyGatewayUrl
        if (url.isBlank()) return false
        // Reject the default placeholder pattern (e.g. "ws://100.x.x.x:8765").
        if (url.contains("x.x", ignoreCase = true)) return false
        if (url.matches(Regex(".*://[^/]*x[^/]*/.*", RegexOption.IGNORE_CASE))) return false
        return true
    }

    // ── PR-33 test-support API ────────────────────────────────────────────────

    /**
     * PR-33 — For testing only: transitions the runtime directly to [RuntimeState.Active]
     * and opens an attached session, bypassing the WebSocket connection handshake.
     *
     * This allows unit tests to place the runtime into [RuntimeState.Active] state so that
     * [permanentWsListener] disconnect/reconnect transitions (and their effect on
     * [reconnectRecoveryState]) can be exercised deterministically without a live WS server.
     *
     * After calling this, use [GalaxyWebSocketClient.simulateDisconnected] /
     * [GalaxyWebSocketClient.simulateConnected] / [GalaxyWebSocketClient.simulateError]
     * to drive the recovery state machine.
     *
     * **Do not call from production code.**
     */
    internal fun setActiveForTest() {
        // PR-37: Use transitionState() so test paths also emit lifecycle events.
        transitionState(to = RuntimeState.Active, expectedFrom = null, trigger = "test_activation")
        openAttachedSession(SessionOpenSource.TEST_ONLY)
    }

    // ── PR-37: Lifecycle hardening helpers ────────────────────────────────────

    /**
     * PR-37 — Validates and applies a runtime state transition.
     *
     * Checks whether the transition from the current state to [to] is a governed
     * (expected) transition according to [RuntimeDispatchReadinessCoordinator.ALLOWED_TRANSITIONS].
     * Emits a [RuntimeLifecycleTransitionEvent.Governed] on a valid transition and a
     * [RuntimeLifecycleTransitionEvent.Unexpected] on any transition that is not in the
     * allowed set — without blocking the transition.  The state is always applied, even
     * if unexpected, to preserve existing runtime behavior.
     *
     * The [expectedFrom] parameter provides an additional call-site assertion: if the
     * actual current state does not match [expectedFrom] (when non-null), an [Unexpected]
     * event is emitted regardless of whether the transition is in the allowed set.
     *
     * This is the **single write point** for [_state] inside [RuntimeController]; all
     * state mutations must go through this method so the [lifecycleTransitionEvents] flow
     * is always accurate.
     *
     * @param to           The target [RuntimeState].
     * @param expectedFrom Optional prior state assertion for extra call-site safety.
     *                     `null` means "no specific prior state expected" (e.g. [stop]).
     * @param trigger      Machine-readable label for the operation causing the transition.
     */
    private fun transitionState(
        to: RuntimeState,
        expectedFrom: RuntimeState?,
        trigger: String
    ) {
        val from = _state.value
        val isGoverned = RuntimeDispatchReadinessCoordinator.isGoverned(from, to)
        val mismatch = expectedFrom != null && from::class != expectedFrom::class

        _state.value = to

        val event: RuntimeLifecycleTransitionEvent = if (isGoverned && !mismatch) {
            RuntimeLifecycleTransitionEvent.Governed(from = from, to = to, trigger = trigger)
        } else {
            val reason = when {
                !isGoverned -> "transition_not_in_allowed_set"
                mismatch    -> "prior_state_mismatch_expected_${expectedFrom!!.wireLabel}"
                else        -> "unexpected"
            }
            Log.w(
                TAG,
                "[RUNTIME] Unexpected state transition: $from → $to " +
                    "(trigger=$trigger, reason=$reason)"
            )
            GalaxyLogger.log(
                GalaxyLogger.TAG_RUNTIME_LIFECYCLE,
                mapOf(
                    "event"       to "runtime_unexpected_state_transition",
                    "from"        to from.wireLabel,
                    "to"          to to.wireLabel,
                    "trigger"     to trigger,
                    "reason"      to reason
                )
            )
            RuntimeLifecycleTransitionEvent.Unexpected(
                from         = from,
                to           = to,
                expectedFrom = expectedFrom ?: from,
                reason       = reason
            )
        }

        GalaxyLogger.log(
            GalaxyLogger.TAG_RUNTIME_LIFECYCLE,
            mapOf(
                "event"   to "runtime_state_transition",
                "from"    to from.wireLabel,
                "to"      to to.wireLabel,
                "trigger" to trigger
            )
        )
        _lifecycleTransitionEvents.tryEmit(event)
    }

    /**
     * PR-37 — Returns the current dispatch readiness assessment for this runtime.
     *
     * Combines the current [state], [attachedSession], and [rolloutControlSnapshot] via
     * [RuntimeDispatchReadinessCoordinator.resolve] into a single, authoritative
     * [RuntimeDispatchReadinessCoordinator.DispatchReadiness] result.
     *
     * This is the canonical place to ask: "Is this Android device currently eligible to
     * dispatch work via the canonical cross-device path, and if not, why?"
     *
     * The result is a point-in-time snapshot; call this immediately before making a
     * dispatch decision or use [lifecycleTransitionEvents] to react to changes.
     */
    fun currentDispatchReadiness(): RuntimeDispatchReadinessCoordinator.DispatchReadiness =
        RuntimeDispatchReadinessCoordinator.resolve(
            runtimeState    = _state.value,
            attachedSession = _attachedSession.value,
            rollout         = _rolloutControlSnapshot.value
        )

    /**
     * PR-37 — Checks that the current session state is consistent with the current
     * runtime state, and logs a diagnostic warning if drift is detected.
     *
     * This is an **observability-only** check — it never throws and never mutates state.
     * Drift warnings in the log indicate a potential lifecycle ordering issue that warrants
     * investigation, but do not affect runtime behavior.
     *
     * Called internally after state transitions that should produce or remove a session
     * (e.g. moving to Active without an ATTACHED session, or remaining Active after a
     * detach event).
     *
     * Drift conditions detected:
     *  - Runtime is [RuntimeState.Active] but no ATTACHED session exists.
     *  - Runtime is NOT [RuntimeState.Active] but an ATTACHED session still exists.
     */
    private fun checkSessionStateAlignment() {
        val state = _state.value
        val sessionIsAttached = _attachedSession.value?.isAttached == true
        val driftDetected = (state is RuntimeState.Active && !sessionIsAttached) ||
            (state !is RuntimeState.Active && sessionIsAttached)
        if (driftDetected) {
            Log.w(
                TAG,
                "[RUNTIME] Session/state alignment drift: state=${state.wireLabel} " +
                    "sessionIsAttached=$sessionIsAttached"
            )
            GalaxyLogger.log(
                GalaxyLogger.TAG_RUNTIME_LIFECYCLE,
                mapOf(
                    "event"              to "runtime_session_state_drift",
                    "runtime_state"      to state.wireLabel,
                    "session_is_attached" to sessionIsAttached
                )
            )
        }
    }

    /**
     * PR-2 — Notifies the runtime that this participant's execution environment health has
     * changed.  The runtime evaluates the new health state via [FormationParticipationRebalancer]
     * and emits a [FormationRebalanceEvent] on [formationRebalanceEvent] if a rebalance is
     * warranted.
     *
     * This is the canonical hook for external health-change signals that originate outside
     * the WS reconnect lifecycle (e.g. inference model crash, accessibility service failure).
     * WS-driven health changes are handled automatically by the permanent WS listener and
     * do not need to go through this hook.
     *
     * This method is **non-suspending** and safe to call from any thread.
     *
     * @param newHealthState The new [ParticipantHealthState] to evaluate.
     * @param readinessState Optional current [ParticipantReadinessState]; defaults to UNKNOWN.
     */
    fun notifyParticipantHealthChanged(
        newHealthState: ParticipantHealthState,
        readinessState: ParticipantReadinessState = ParticipantReadinessState.UNKNOWN
    ) {
        val descriptor = hostDescriptor ?: run {
            Log.d(TAG, "[RUNTIME] notifyParticipantHealthChanged: no hostDescriptor — skipping")
            return
        }
        Log.d(TAG, "[RUNTIME] notifyParticipantHealthChanged: health=${newHealthState.wireValue}")

        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateParticipation(
            descriptor = descriptor,
            healthState = newHealthState,
            reconnectRecoveryState = _reconnectRecoveryState.value,
            readinessState = readinessState,
            activeSessionSnapshot = currentHostSessionSnapshot()
        )

        GalaxyLogger.log(
            GalaxyLogger.TAG_FORMATION_HEALTH,
            mapOf(
                "event" to "participant_health_changed",
                "health_state" to newHealthState.wireValue,
                "requires_rebalance" to decision.requiresRebalance,
                "continuation_mode" to decision.continuationMode.wireValue
            )
        )

        if (decision.requiresRebalance) {
            val event = decision.suggestedEvent
                ?: FormationRebalanceEvent.ReadinessChanged(
                    previousReadiness = readinessState,
                    currentReadiness = ParticipantReadinessState.UNKNOWN,
                    previousParticipation = descriptor.participationState,
                    currentParticipation = descriptor.participationState,
                    trigger = "health_${newHealthState.wireValue}"
                )
            GalaxyLogger.log(
                GalaxyLogger.TAG_FORMATION_REBALANCE,
                mapOf(
                    "event" to event.wireValue,
                    "rationale" to decision.rationale
                )
            )
            _formationRebalanceEvent.tryEmit(event)
            // PR-43: Also emit ParticipantReadinessChanged on the V2 stream when the
            // rebalance event carries a readiness state transition.
            if (event is FormationRebalanceEvent.ReadinessChanged) {
                emitV2LifecycleEvent(
                    V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged(
                        deviceId = descriptor.deviceId,
                        sessionId = _attachedSession.value?.sessionId,
                        previousReadiness = event.previousReadiness.wireValue,
                        currentReadiness = event.currentReadiness.wireValue,
                        previousParticipation = event.previousParticipation.wireValue,
                        currentParticipation = event.currentParticipation.wireValue,
                        trigger = event.trigger
                    )
                )
            }
        }
        // PR-43: Emit V2 health-changed event regardless of whether a rebalance is
        // required, so V2 always has visibility into execution-environment health
        // transitions — even for HEALTHY reports that require no rebalance.
        // DeviceDegraded is additionally emitted for compromised states so the V2
        // harness can distinguish a simple health signal from an active degradation.
        val previousHealth = _lastKnownHealthState
        _lastKnownHealthState = newHealthState
        emitV2LifecycleEvent(
            V2MultiDeviceLifecycleEvent.DeviceHealthChanged(
                deviceId = descriptor.deviceId,
                sessionId = _attachedSession.value?.sessionId,
                previousHealth = previousHealth.wireValue,
                currentHealth = newHealthState.wireValue,
                requiresRebalance = decision.requiresRebalance,
                continuationMode = decision.continuationMode.wireValue,
                trigger = "health_${newHealthState.wireValue}"
            )
        )
        if (ParticipantHealthState.isCompromised(newHealthState)) {
            val degradationKind = when (newHealthState) {
                ParticipantHealthState.DEGRADED   -> "health_degraded"
                ParticipantHealthState.RECOVERING -> "health_recovering"
                ParticipantHealthState.FAILED     -> "health_failed"
                else                              -> "health_${newHealthState.wireValue}"
            }
            emitV2LifecycleEvent(
                V2MultiDeviceLifecycleEvent.DeviceDegraded(
                    deviceId = descriptor.deviceId,
                    sessionId = _attachedSession.value?.sessionId,
                    degradationKind = degradationKind,
                    continuationMode = decision.continuationMode.wireValue,
                    durableSessionId = _durableSessionContinuityRecord.value?.durableSessionId,
                    sessionContinuityEpoch = _durableSessionContinuityRecord.value?.sessionContinuityEpoch
                )
            )
        }
    }

    /**
     * PR-2 — Requests role reassessment for this participant.
     *
     * Evaluates whether this device should accept a change from its current
     * [RuntimeHostDescriptor.formationRole] to [requestedRole] via
     * [FormationParticipationRebalancer.evaluateRoleReassignment].  If the reassignment is
     * accepted, the [hostDescriptor] is updated in-place and propagated to
     * [GalaxyWebSocketClient.setRuntimeHostDescriptor], and a
     * [FormationRebalanceEvent.RoleReassignmentRequested] event is emitted.
     *
     * If the reassignment is declined or deferred, the current role is preserved and
     * only a diagnostic log entry is emitted (no flow event for declined cases).
     *
     * This method is **non-suspending** and safe to call from any thread.
     *
     * @param requestedRole          The new [RuntimeHostDescriptor.FormationRole] being proposed.
     * @param healthState            Current [ParticipantHealthState] of this device.
     *                               Defaults to [ParticipantHealthState.HEALTHY]; callers that
     *                               track health state independently should pass the current value.
     * @param requestingCoordinator  Identifier of the entity requesting the reassignment.
     *                               Used for diagnostics; does not affect the evaluation result.
     * @param sessionId              Optional session identity scoping the reassignment.
     * @return The [FormationParticipationRebalancer.RoleReassignmentDecision] describing
     *         whether the change was accepted, declined, or deferred.
     */
    fun requestRoleReassessment(
        requestedRole: RuntimeHostDescriptor.FormationRole,
        healthState: ParticipantHealthState = ParticipantHealthState.HEALTHY,
        requestingCoordinator: String = "local",
        sessionId: String? = null
    ): FormationParticipationRebalancer.RoleReassignmentDecision {
        val descriptor = hostDescriptor ?: return FormationParticipationRebalancer.RoleReassignmentDecision(
            accepted = false,
            deferrable = false,
            previousRole = RuntimeHostDescriptor.FormationRole.DEFAULT,
            requestedRole = requestedRole,
            declineReason = "no_host_descriptor"
        )

        Log.d(
            TAG,
            "[RUNTIME] requestRoleReassessment: " +
                "${descriptor.formationRole.wireValue}→${requestedRole.wireValue} " +
                "health=${healthState.wireValue}"
        )

        val rebalancer = FormationParticipationRebalancer()
        val decision = rebalancer.evaluateRoleReassignment(
            descriptor = descriptor,
            requestedRole = requestedRole,
            healthState = healthState,
            reconnectRecoveryState = _reconnectRecoveryState.value
        )

        val outcomeTag = when {
            decision.accepted -> "role_reassignment_accepted"
            decision.deferrable -> "role_reassignment_deferred"
            else -> "role_reassignment_declined"
        }
        GalaxyLogger.log(
            GalaxyLogger.TAG_FORMATION_ROLE,
            buildMap {
                put("event", outcomeTag)
                put("previous_role", decision.previousRole.wireValue)
                put("requested_role", decision.requestedRole.wireValue)
                put("accepted", decision.accepted)
                put("deferrable", decision.deferrable)
                decision.declineReason?.let { put("decline_reason", it) }
            }
        )

        if (decision.accepted && requestedRole != descriptor.formationRole) {
            // Apply the role change: update descriptor and propagate to the WS client.
            val updatedDescriptor = descriptor.withRole(requestedRole)
            hostDescriptor = updatedDescriptor
            webSocketClient.setRuntimeHostDescriptor(updatedDescriptor)
            Log.i(
                TAG,
                "[RUNTIME] Formation role updated: " +
                    "${decision.previousRole.wireValue}→${decision.requestedRole.wireValue}"
            )
            // Emit a RoleReassignmentRequested event to signal observers that the role changed.
            // Use decision.previousRole (captured before the update) to avoid referring to
            // the already-mutated hostDescriptor.
            val event = FormationRebalanceEvent.RoleReassignmentRequested(
                requestedRole = requestedRole,
                previousRole = decision.previousRole,
                requestingCoordinator = requestingCoordinator,
                sessionId = sessionId
            )
            GalaxyLogger.log(
                GalaxyLogger.TAG_FORMATION_REBALANCE,
                mapOf(
                    "event" to event.wireValue,
                    "previous_role" to decision.previousRole.wireValue,
                    "requested_role" to requestedRole.wireValue
                )
            )
            _formationRebalanceEvent.tryEmit(event)
        }

        return decision
    }

    // ── PR-2: Internal formation rebalance helpers ────────────────────────────

    /**
     * Emits a [FormationRebalanceEvent] corresponding to a [ReconnectRecoveryState]
     * transition.  Called by the permanent WS listener on each recovery-state change so
     * that formation observers receive typed events without needing to interpret raw
     * [reconnectRecoveryState] values.
     *
     * V2 [V2MultiDeviceLifecycleEvent.DeviceDegraded] events for [ReconnectRecoveryState.RECOVERING]
     * and [ReconnectRecoveryState.FAILED] are emitted **unconditionally** (i.e. even when
     * [hostDescriptor] is `null`) using the session identity from [_attachedSession] and
     * [_durableSessionContinuityRecord].  This ensures the V2 stream always reflects the
     * full recovery cycle regardless of whether a host descriptor has been configured.
     *
     * [FormationRebalanceEvent] emission and [V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged]
     * still require a non-null [hostDescriptor] (formation role and participation state
     * fields are not available without a descriptor).
     *
     * @param previousRecoveryState The recovery state before the transition.
     * @param newRecoveryState      The recovery state after the transition.
     */
    private fun emitFormationRebalanceForRecovery(
        previousRecoveryState: ReconnectRecoveryState,
        newRecoveryState: ReconnectRecoveryState
    ) {
        // V2 DeviceDegraded events are emitted unconditionally for RECOVERING and FAILED so
        // that the V2 stream reflects the complete recovery-state machine regardless of
        // whether a hostDescriptor is configured.  Identity fields (durableSessionId,
        // sessionContinuityEpoch) from the active durable record are included so V2 can
        // correlate these degradation events with the specific session era being recovered.
        val sessionDeviceId = _attachedSession.value?.deviceId ?: settings.deviceId.takeIf { it.isNotBlank() }
        if (sessionDeviceId != null) {
            when (newRecoveryState) {
                ReconnectRecoveryState.RECOVERING -> emitV2LifecycleEvent(
                    V2MultiDeviceLifecycleEvent.DeviceDegraded(
                        deviceId = sessionDeviceId,
                        sessionId = _attachedSession.value?.sessionId,
                        degradationKind = "ws_recovering",
                        continuationMode = FormationParticipationRebalancer.ContinuationMode.DEGRADED_CONTINUATION.wireValue,
                        durableSessionId = _durableSessionContinuityRecord.value?.durableSessionId,
                        sessionContinuityEpoch = _durableSessionContinuityRecord.value?.sessionContinuityEpoch
                    )
                )
                ReconnectRecoveryState.FAILED -> emitV2LifecycleEvent(
                    V2MultiDeviceLifecycleEvent.DeviceDegraded(
                        deviceId = sessionDeviceId,
                        sessionId = _attachedSession.value?.sessionId,
                        degradationKind = "ws_recovery_failed",
                        continuationMode = FormationParticipationRebalancer.ContinuationMode.WITHDRAW_PARTICIPATION.wireValue,
                        durableSessionId = _durableSessionContinuityRecord.value?.durableSessionId,
                        sessionContinuityEpoch = _durableSessionContinuityRecord.value?.sessionContinuityEpoch
                    )
                )
                else -> { /* RECOVERED is handled by openAttachedSession; IDLE is a no-op */ }
            }
        }

        // Formation rebalance events and ParticipantReadinessChanged require a hostDescriptor
        // because they carry role and participation-state fields derived from it.
        val descriptor = hostDescriptor ?: return
        val event: FormationRebalanceEvent = when (newRecoveryState) {
            ReconnectRecoveryState.RECOVERING -> FormationRebalanceEvent.ReadinessChanged(
                previousReadiness = ParticipantReadinessState.READY,
                currentReadiness = ParticipantReadinessState.NOT_READY,
                previousParticipation = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                currentParticipation = RuntimeHostDescriptor.HostParticipationState.INACTIVE,
                trigger = "ws_disconnect_active"
            )
            ReconnectRecoveryState.RECOVERED -> FormationRebalanceEvent.ParticipantRejoined(
                rejoinedParticipantId = RuntimeIdentityContracts.participantNodeId(
                    deviceId = descriptor.deviceId,
                    runtimeHostId = descriptor.hostId
                ),
                rejoinedDeviceId = descriptor.deviceId,
                sessionContinuityEpoch = _durableSessionContinuityRecord.value?.sessionContinuityEpoch,
                affectedMeshId = null
            )
            ReconnectRecoveryState.FAILED -> FormationRebalanceEvent.ReadinessChanged(
                previousReadiness = ParticipantReadinessState.NOT_READY,
                currentReadiness = ParticipantReadinessState.NOT_READY,
                previousParticipation = RuntimeHostDescriptor.HostParticipationState.INACTIVE,
                currentParticipation = RuntimeHostDescriptor.HostParticipationState.INACTIVE,
                trigger = "ws_recovery_failed"
            )
            ReconnectRecoveryState.IDLE -> return
        }
        GalaxyLogger.log(
            GalaxyLogger.TAG_FORMATION_REBALANCE,
            mapOf(
                "event" to event.wireValue,
                "previous_recovery_state" to previousRecoveryState.wireValue,
                "new_recovery_state" to newRecoveryState.wireValue
            )
        )
        _formationRebalanceEvent.tryEmit(event)
        // PR-43: Emit ParticipantReadinessChanged on the V2 stream for RECOVERING and FAILED
        // transitions.  DeviceDegraded is already emitted unconditionally above; here we only
        // add the readiness-state companion event that requires descriptor participation fields.
        when (newRecoveryState) {
            ReconnectRecoveryState.RECOVERING -> {
                if (event is FormationRebalanceEvent.ReadinessChanged) {
                    emitV2LifecycleEvent(
                        V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged(
                            deviceId = descriptor.deviceId,
                            sessionId = _attachedSession.value?.sessionId,
                            previousReadiness = event.previousReadiness.wireValue,
                            currentReadiness = event.currentReadiness.wireValue,
                            previousParticipation = event.previousParticipation.wireValue,
                            currentParticipation = event.currentParticipation.wireValue,
                            trigger = event.trigger
                        )
                    )
                }
            }
            ReconnectRecoveryState.FAILED -> {
                if (event is FormationRebalanceEvent.ReadinessChanged) {
                    emitV2LifecycleEvent(
                        V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged(
                            deviceId = descriptor.deviceId,
                            sessionId = _attachedSession.value?.sessionId,
                            previousReadiness = event.previousReadiness.wireValue,
                            currentReadiness = event.currentReadiness.wireValue,
                            previousParticipation = event.previousParticipation.wireValue,
                            currentParticipation = event.currentParticipation.wireValue,
                            trigger = event.trigger
                        )
                    )
                }
            }
            else -> { /* RECOVERED handled by openAttachedSession; IDLE returns early above */ }
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "GALAXY:RUNTIME"

        /** Default maximum time to wait for a WS connection during [startWithTimeout]. */
        const val DEFAULT_REGISTRATION_TIMEOUT_MS = 15_000L

        /**
         * PR-2 — Buffer capacity for [_formationRebalanceEvent].
         *
         * Sized to accommodate a burst of formation events (e.g. multiple participants
         * transitioning simultaneously in a mesh session) without dropping events when
         * observers are briefly slow to drain the flow.
         */
        private const val FORMATION_REBALANCE_EVENT_BUFFER_CAPACITY = 16

        /**
         * PR-37 — Buffer capacity for [_lifecycleTransitionEvents].
         *
         * Sized to accommodate a sequence of rapid state transitions (e.g. start → active
         * → disconnect → reconnect → active) without dropping events when observers are
         * briefly slow to drain.
         */
        private const val LIFECYCLE_TRANSITION_EVENT_BUFFER_CAPACITY = 32

        /**
         * PR-43 — Buffer capacity for [_v2LifecycleEvents].
         *
         * Sized to accommodate a burst of lifecycle events (connect → health-change →
         * readiness-change) without dropping events when the V2 harness consumer is
         * briefly slow to drain.
         */
        private const val V2_LIFECYCLE_EVENT_BUFFER_CAPACITY = 16
    }
}
