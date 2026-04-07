package com.ufo.galaxy.runtime

import android.util.Log
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.observability.GalaxyLogger
import java.util.UUID
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
 *    emits a human-readable reason via [registrationError] so both
 *    [com.ufo.galaxy.ui.MainActivity] and [com.ufo.galaxy.service.EnhancedFloatingService]
 *    can show a dialog/alert — **never** silently log-only.
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
     * timeout, or explicit WS error). Both [com.ufo.galaxy.ui.MainActivity] and
     * [com.ufo.galaxy.service.EnhancedFloatingService] must collect this flow and
     * show an AlertDialog/overlay dialog — **never** silently swallow the event.
     */
    private val _registrationError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val registrationError: SharedFlow<String> = _registrationError.asSharedFlow()

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
     *  - Transitioned to [AttachedRuntimeSession.State.DETACHED] (and then cleared to `null`
     *    after a brief period) on [stop], WS disconnect, registration failure, or [invalidateSession].
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

    /** Internal scope used for the observer that handles WS connection during [start]. */
    private val controllerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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

        _state.value = RuntimeState.Starting
        Log.i(TAG, "[RUNTIME] Starting cross-device runtime (timeout=${registrationTimeoutMs}ms)")
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_start", "timeout_ms" to registrationTimeoutMs))

        // Enable WS client for this attempt.
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
            _state.value = RuntimeState.Active
            openAttachedSession()
            Log.i(TAG, "[RUNTIME] Runtime is now Active")
            true
        } else {
            val reason = "跨设备注册失败：无法连接到 Gateway。请检查网络或服务器设置。"
            handleFailure(reason)
            false
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

        webSocketClient.setCrossDeviceEnabled(crossDeviceOn)

        if (!crossDeviceOn) {
            if (_state.value !is RuntimeState.LocalOnly) {
                _state.value = RuntimeState.LocalOnly
            }
            return
        }

        // Only install a state-transition listener when we are not already in the middle of
        // a start() or already active — avoid double-listener races.
        if (_state.value !is RuntimeState.Starting && _state.value !is RuntimeState.Active) {
            _state.value = RuntimeState.Starting
            webSocketClient.addListener(object : GalaxyWebSocketClient.Listener {
                override fun onConnected() {
                    Log.i(TAG, "[RUNTIME] connectIfEnabled: WS connected → Active")
                    GalaxyLogger.log(TAG, mapOf("event" to "runtime_restore_active"))
                    webSocketClient.removeListener(this)
                    if (_state.value == RuntimeState.Starting) {
                        _state.value = RuntimeState.Active
                        openAttachedSession()
                    }
                }

                override fun onDisconnected() {
                    webSocketClient.removeListener(this)
                    if (_state.value == RuntimeState.Starting) {
                        // Transient failure — fall back to LocalOnly; WS reconnect will retry.
                        closeAttachedSession(AttachedRuntimeSession.DetachCause.DISCONNECT)
                        _state.value = RuntimeState.LocalOnly
                    }
                }

                override fun onError(error: String) {
                    webSocketClient.removeListener(this)
                    if (_state.value == RuntimeState.Starting) {
                        closeAttachedSession(AttachedRuntimeSession.DetachCause.DISCONNECT)
                        _state.value = RuntimeState.LocalOnly
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
        _state.value = RuntimeState.LocalOnly
    }

    // ── Remote/local task handoff ─────────────────────────────────────────────

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
        loopController.clearRemoteTaskBlock()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Handles a registration failure: emits [registrationError], updates [state] to
     * [RuntimeState.Failed], closes any [attachedSession] with
     * [AttachedRuntimeSession.DetachCause.DISCONNECT], then falls back to local-only by
     * disabling cross-device in both [settings] and the [webSocketClient].
     */
    private suspend fun handleFailure(reason: String) {
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_failed", "reason" to reason))
        _state.value = RuntimeState.Failed(reason)
        _registrationError.emit(reason)
        closeAttachedSession(AttachedRuntimeSession.DetachCause.DISCONNECT)
        // Fall back to local mode.
        settings.crossDeviceEnabled = false
        webSocketClient.setCrossDeviceEnabled(false)
        if (webSocketClient.isConnected()) {
            webSocketClient.disconnect()
        }
        _state.value = RuntimeState.LocalOnly
        Log.w(TAG, "[RUNTIME] Fell back to LocalOnly after failure: $reason")
    }

    fun cancel() {
        controllerScope.cancel()
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
     */
    fun invalidateSession() {
        Log.i(TAG, "[RUNTIME] Invalidating attached runtime session")
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_session_invalidated"))
        closeAttachedSession(AttachedRuntimeSession.DetachCause.INVALIDATION)
    }

    /**
     * Returns an authoritative **host-facing snapshot** of the current attached session's
     * reuse state as a [Map], or `null` when no session is active (PR-17).
     *
     * The returned map is identical to [AttachedRuntimeSession.toMetadataMap] and always
     * contains at minimum:
     *  - [AttachedRuntimeSession.KEY_SESSION_ID]
     *  - [AttachedRuntimeSession.KEY_DEVICE_ID]
     *  - [AttachedRuntimeSession.KEY_STATE]
     *  - [AttachedRuntimeSession.KEY_DELEGATED_EXECUTION_COUNT]
     *  - [AttachedRuntimeSession.KEY_IS_REUSE_VALID]
     *  - [AttachedRuntimeSession.KEY_DETACH_CAUSE] (when applicable)
     *
     * Use this as the canonical input for host-side reuse binding decisions.  The snapshot
     * is a point-in-time copy; observe [attachedSession] as a [kotlinx.coroutines.flow.StateFlow]
     * for continuous updates.
     *
     * @return An immutable [Map] with all host-facing session fields; `null` if no session exists.
     */
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
     *    [AttachedRuntimeHostSessionSnapshot.attachmentState]`=attached`.
     *  - **invalidate** — [AttachedRuntimeHostSessionSnapshot.attachmentState]`=detached`,
     *    [AttachedRuntimeHostSessionSnapshot.invalidationReason]`="invalidation"`.
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
            hostRole = hostRole
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
     */
    private fun openAttachedSession() {
        if (_attachedSession.value?.isAttached == true) {
            Log.d(TAG, "[RUNTIME] openAttachedSession: session already attached — no-op")
            return
        }
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
        updateHostSessionSnapshot()
        Log.i(TAG, "[RUNTIME] Attached runtime session opened: session_id=${session.sessionId} host_id=${session.hostId} runtime_session_id=${_currentRuntimeSessionId}")
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_session_attached") + session.toMetadataMap())
        // Sync host descriptor participation state to ACTIVE.
        if (descriptor != null) {
            val updated = descriptor.withState(RuntimeHostDescriptor.HostParticipationState.ACTIVE)
            hostDescriptor = updated
            webSocketClient.setRuntimeHostDescriptor(updated)
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
        // Sync host descriptor participation state to INACTIVE.
        val descriptor = hostDescriptor
        if (descriptor != null) {
            val updated = descriptor.withState(RuntimeHostDescriptor.HostParticipationState.INACTIVE)
            hostDescriptor = updated
            webSocketClient.setRuntimeHostDescriptor(updated)
        }
    }

    // ── Snapshot projection sync ──────────────────────────────────────────────

    /**
     * Recomputes and publishes the [AttachedRuntimeHostSessionSnapshot] on
     * [_hostSessionSnapshot].
     *
     * Called after every mutation of [_attachedSession] or [_currentRuntimeSessionId]
     * to keep [hostSessionSnapshot] in sync with the canonical session truth.  This is
     * the **only** place [_hostSessionSnapshot] is written, ensuring a single update path.
     */
    private fun updateHostSessionSnapshot() {
        _hostSessionSnapshot.value = currentHostSessionSnapshot()
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "GALAXY:RUNTIME"

        /** Default maximum time to wait for a WS connection during [startWithTimeout]. */
        const val DEFAULT_REGISTRATION_TIMEOUT_MS = 15_000L
    }
}
