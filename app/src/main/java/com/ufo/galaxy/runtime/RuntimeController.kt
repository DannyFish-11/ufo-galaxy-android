package com.ufo.galaxy.runtime

import android.util.Log
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.loop.LoopStatus
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.observability.GalaxyLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the cross-device runtime lifecycle on the Android client.
 *
 * Responsibilities:
 *  - **ON** path: enables the WebSocket client, initiates a connection, and monitors the
 *    connection state. If registration or connection fails, [onFailure] is emitted and the
 *    controller automatically falls back to local-only mode via [LoopController].
 *  - **OFF** path: disconnects the WebSocket, stops any in-flight remote tasks, and restores
 *    the [LoopController] to an idle state so new local sessions can start immediately.
 *  - **Remote handoff**: when a remote `task_assign` or `command` is received the controller
 *    pauses the local [LoopController] so the two execution paths do not conflict.
 *  - **Automatic fallback**: on WS disconnect or error while cross-device is ON, the
 *    controller resumes the local loop so the device stays functional.
 *  - **Model readiness**: exposes [ensureModels] so callers can trigger model downloads
 *    independent of the cross-device state (requirement 6).
 *
 * All public methods are safe to call from any thread.
 *
 * @param settings         Persistent settings. [AppSettings.crossDeviceEnabled] is updated
 *                         when [enable]/[disable] are called.
 * @param webSocketClient  Live WebSocket client that connects to the Galaxy Gateway.
 * @param loopController   Local closed-loop controller; paused during remote execution and
 *                         resumed on remote failure / disconnect.
 * @param modelAssetManager Used by [ensureModels] to verify local model files.
 * @param modelDownloader   Used by [ensureModels] to download missing model files.
 * @param scope            Coroutine scope used for lifecycle jobs. Defaults to an IO scope
 *                         owned by this controller; cancel it to release all resources.
 */
class RuntimeController(
    private val settings: AppSettings,
    private val webSocketClient: GalaxyWebSocketClient,
    private val loopController: LoopController,
    private val modelAssetManager: ModelAssetManager,
    private val modelDownloader: ModelDownloader,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    // ── State ─────────────────────────────────────────────────────────────────

    enum class RuntimeState {
        /** Cross-device is disabled; device operates in local-only mode. */
        LOCAL_ONLY,
        /** Cross-device is enabled and the WS connection is being established. */
        CONNECTING,
        /** Cross-device is enabled and the WS connection is active. */
        CONNECTED,
        /** Cross-device is enabled but the WS connection failed or was lost. */
        DISCONNECTED,
        /** A remote task is being executed; local loop is paused. */
        REMOTE_EXECUTING
    }

    private val _state = MutableStateFlow(
        if (settings.crossDeviceEnabled) RuntimeState.DISCONNECTED else RuntimeState.LOCAL_ONLY
    )

    /** Current runtime state. Collect from UI or services to react to lifecycle changes. */
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    /**
     * Emits a human-readable failure reason whenever cross-device registration or
     * connection fails. Both [com.ufo.galaxy.ui.MainActivity] and
     * [com.ufo.galaxy.service.EnhancedFloatingService] should collect this and surface
     * the message as a dialog or toast.
     */
    private val _onFailure = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val onFailure: SharedFlow<String> = _onFailure.asSharedFlow()

    /** Tracks the WS listener so it can be removed in [disable]. */
    private var wsListener: GalaxyWebSocketClient.Listener? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enables cross-device mode.
     *
     * Persists the setting, enables the WebSocket client, and attempts to connect.
     * If connection fails, emits a failure via [onFailure] and falls back to local mode.
     */
    fun enable() {
        if (_state.value == RuntimeState.CONNECTED || _state.value == RuntimeState.CONNECTING) return
        Log.i(TAG, "[RUNTIME] enable cross-device")
        settings.crossDeviceEnabled = true
        webSocketClient.setCrossDeviceEnabled(true)
        attachWsListener()
        _state.value = RuntimeState.CONNECTING
        webSocketClient.connect()
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_enable"))
    }

    /**
     * Disables cross-device mode.
     *
     * Persists the setting, disconnects the WebSocket, and restores local-only mode.
     * Resumes the [LoopController] if it was paused.
     */
    fun disable() {
        Log.i(TAG, "[RUNTIME] disable cross-device")
        settings.crossDeviceEnabled = false
        webSocketClient.setCrossDeviceEnabled(false)
        detachWsListener()
        if (webSocketClient.isConnected()) {
            webSocketClient.disconnect()
        }
        _state.value = RuntimeState.LOCAL_ONLY
        resumeLocalLoop()
        GalaxyLogger.log(TAG, mapOf("event" to "runtime_disable"))
    }

    /**
     * Called when a remote `task_assign` or `command` arrives from the Gateway.
     *
     * Pauses the local [LoopController] so remote execution has exclusive device access.
     */
    fun onRemoteTaskStarted() {
        Log.i(TAG, "[RUNTIME] remote task started – pausing local loop")
        loopController.pause()
        _state.value = RuntimeState.REMOTE_EXECUTING
        GalaxyLogger.log(TAG, mapOf("event" to "remote_task_started"))
    }

    /**
     * Called when the remote task has completed (success or failure).
     *
     * Resumes the [LoopController] so local sessions can be submitted again.
     */
    fun onRemoteTaskFinished() {
        Log.i(TAG, "[RUNTIME] remote task finished – resuming local loop")
        resumeLocalLoop()
        _state.value = if (webSocketClient.isConnected()) RuntimeState.CONNECTED else RuntimeState.DISCONNECTED
        GalaxyLogger.log(TAG, mapOf("event" to "remote_task_finished"))
    }

    /**
     * Verifies model files and downloads any that are missing or corrupted.
     *
     * This method runs regardless of [AppSettings.crossDeviceEnabled] so that model
     * readiness is guaranteed before any local or remote execution starts.
     * Safe to call from a coroutine; suspends until all downloads complete.
     */
    suspend fun ensureModels() {
        val statuses = modelAssetManager.verifyAll()
        val allPresent = statuses.values.none {
            it == ModelAssetManager.ModelStatus.MISSING ||
                it == ModelAssetManager.ModelStatus.CORRUPTED
        }
        if (allPresent) {
            GalaxyLogger.log(TAG, mapOf("event" to "ensure_models_ok"))
            return
        }
        val specs = modelAssetManager.downloadSpecsForMissing()
        if (specs.isEmpty()) {
            GalaxyLogger.log(TAG, mapOf("event" to "ensure_models_no_urls"))
            return
        }
        GalaxyLogger.log(TAG, mapOf("event" to "ensure_models_downloading", "count" to specs.size))
        for (spec in specs) {
            val ok = modelDownloader.downloadSync(spec) { }
            if (!ok) {
                GalaxyLogger.log(TAG, mapOf("event" to "ensure_models_failed", "model_id" to spec.modelId))
            }
        }
        modelAssetManager.verifyAll()
    }

    /**
     * Releases all resources owned by this controller (coroutine scope, WS listener).
     * Call from [android.app.Application.onTerminate] or a service's [android.app.Service.onDestroy].
     */
    fun destroy() {
        detachWsListener()
        scope.cancel()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun resumeLocalLoop() {
        if (loopController.status.value is LoopStatus.Paused) {
            loopController.resume()
        }
    }

    private fun attachWsListener() {
        if (wsListener != null) return
        val listener = object : GalaxyWebSocketClient.Listener {
            override fun onConnected() {
                Log.i(TAG, "[RUNTIME] WS connected")
                _state.value = RuntimeState.CONNECTED
                GalaxyLogger.log(TAG, mapOf("event" to "ws_connected"))
            }

            override fun onDisconnected() {
                Log.w(TAG, "[RUNTIME] WS disconnected – falling back to local loop")
                val wasRemote = _state.value == RuntimeState.REMOTE_EXECUTING
                _state.value = if (settings.crossDeviceEnabled) RuntimeState.DISCONNECTED else RuntimeState.LOCAL_ONLY
                if (wasRemote) {
                    resumeLocalLoop()
                    GalaxyLogger.log(TAG, mapOf("event" to "remote_fallback_on_disconnect"))
                }
                scope.launch {
                    _onFailure.emit("与 Gateway 的连接已断开，已切换到本地执行模式。")
                }
            }

            override fun onMessage(message: String) = Unit

            override fun onError(error: String) {
                Log.e(TAG, "[RUNTIME] WS error: $error")
                val wasRemote = _state.value == RuntimeState.REMOTE_EXECUTING
                if (settings.crossDeviceEnabled) {
                    _state.value = RuntimeState.DISCONNECTED
                }
                if (wasRemote) {
                    resumeLocalLoop()
                }
                scope.launch {
                    _onFailure.emit("跨设备连接错误：$error")
                }
                GalaxyLogger.log(TAG, mapOf("event" to "ws_error", "error" to error))
            }

            override fun onTaskAssign(taskId: String, taskAssignPayloadJson: String) {
                onRemoteTaskStarted()
            }
        }
        wsListener = listener
        webSocketClient.addListener(listener)
    }

    private fun detachWsListener() {
        wsListener?.let { webSocketClient.removeListener(it) }
        wsListener = null
    }

    companion object {
        private const val TAG = "GALAXY:RUNTIME"
    }
}
