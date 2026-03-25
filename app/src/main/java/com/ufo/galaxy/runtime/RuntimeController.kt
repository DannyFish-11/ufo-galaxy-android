package com.ufo.galaxy.runtime

import android.util.Log
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.observability.GalaxyLogger
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
 */
class RuntimeController(
    private val webSocketClient: GalaxyWebSocketClient,
    private val settings: AppSettings,
    private val loopController: LoopController,
    private val registrationTimeoutMs: Long = DEFAULT_REGISTRATION_TIMEOUT_MS
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
                    }
                }

                override fun onDisconnected() {
                    webSocketClient.removeListener(this)
                    if (_state.value == RuntimeState.Starting) {
                        // Transient failure — fall back to LocalOnly; WS reconnect will retry.
                        _state.value = RuntimeState.LocalOnly
                    }
                }

                override fun onError(error: String) {
                    webSocketClient.removeListener(this)
                    if (_state.value == RuntimeState.Starting) {
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
     * 3. Transitions to [RuntimeState.LocalOnly].
     */
    fun stop() {
        Log.i(TAG, "[RUNTIME] Stopping cross-device runtime")
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_stop"))
        settings.crossDeviceEnabled = false
        webSocketClient.setCrossDeviceEnabled(false)
        if (webSocketClient.isConnected()) {
            webSocketClient.disconnect()
        }
        _state.value = RuntimeState.LocalOnly
    }

    // ── Remote/local task handoff ─────────────────────────────────────────────

    /**
     * Must be called when the Gateway assigns a remote task (`task_assign` or
     * `goal_execution`). Cancels any running local [LoopController] session and marks
     * the loop as blocked while the remote task executes.
     *
     * AIP v3 compliance: only tasks delivered via `task_assign` / `goal_execution`
     * envelopes trigger this; direct user input via [com.ufo.galaxy.network.MessageRouter]
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
     * [RuntimeState.Failed], then falls back to local-only by disabling cross-device
     * in both [settings] and the [webSocketClient].
     */
    private suspend fun handleFailure(reason: String) {
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_failed", "reason" to reason))
        _state.value = RuntimeState.Failed(reason)
        _registrationError.emit(reason)
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

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "GALAXY:RUNTIME"

        /** Default maximum time to wait for a WS connection during [startWithTimeout]. */
        const val DEFAULT_REGISTRATION_TIMEOUT_MS = 15_000L
    }
}
