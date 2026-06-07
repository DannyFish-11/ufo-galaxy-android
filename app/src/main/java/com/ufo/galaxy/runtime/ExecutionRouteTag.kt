package com.ufo.galaxy.runtime

/**
 * PR-29 — Machine-readable tag identifying the execution path that produced a result.
 *
 * Recorded alongside every task outcome so that diagnostics surfaces (see
 * [com.ufo.galaxy.ui.viewmodel.MainUiState.lastExecutionRoute] and
 * [com.ufo.galaxy.ui.viewmodel.MainViewModel.buildDiagnosticsText]) can report which
 * internal path handled the most recent task — without exposing implementation details to
 * the user-facing summary produced by [com.ufo.galaxy.ui.viewmodel.UnifiedResultPresentation].
 *
 * ## Path semantics
 *
 * | [ExecutionRouteTag] | Execution origin                                                          |
 * |---------------------|---------------------------------------------------------------------------|
 * | [LOCAL]             | Device ran the closed-loop [com.ufo.galaxy.loop.LoopController] directly. |
 * | [CROSS_DEVICE]      | Task was submitted to the Gateway; result arrived as a server message.    |
 * | [DELEGATED]         | Device accepted an incoming delegated takeover and ran the task pipeline. |
 * | [FALLBACK]          | A delegated takeover failed; a [com.ufo.galaxy.runtime.TakeoverFallbackEvent] was emitted. |
 *
 * The tag is **not** surfaced in user-facing chat text; it is a structured diagnostic
 * field only.  [com.ufo.galaxy.ui.viewmodel.UnifiedResultPresentation] deliberately omits
 * it from [com.ufo.galaxy.ui.viewmodel.UnifiedResultPresentation.summary].
 *
 * @property wireValue Stable string safe for structured telemetry payloads and diagnostic
 *                     text snapshots.
 */
enum class ExecutionRouteTag(val wireValue: String) {
    /**
     * Local closed-loop execution on this device.
     *
     * Set when [com.ufo.galaxy.local.LocalLoopExecutor.execute] completes and the result
     * is delivered via the [com.ufo.galaxy.input.InputRouter.onLocalResult] callback —
     * regardless of whether cross-device mode was ever enabled.
     */
    LOCAL("local"),

    /**
     * Cross-device execution: task submitted to the Gateway via
     * [com.ufo.galaxy.network.GalaxyWebSocketClient], result arrived as a WebSocket server
     * message and surfaced via [com.ufo.galaxy.ui.viewmodel.UnifiedResultPresentation.fromServerMessage].
     */
    CROSS_DEVICE("cross_device"),

    /**
     * Delegated takeover execution: device accepted an incoming delegated task from the
     * Gateway via [com.ufo.galaxy.agent.DelegatedRuntimeReceiver] and ran the task
     * pipeline to completion (COMPLETED outcome).
     */
    DELEGATED("delegated"),

    /**
     * Fallback after a failed delegated takeover.
     *
     * Set when [com.ufo.galaxy.runtime.RuntimeController.notifyTakeoverFailed] emits a
     * [com.ufo.galaxy.runtime.TakeoverFallbackEvent] with any of the four causes
     * (FAILED / TIMEOUT / CANCELLED / DISCONNECT).  The device did not complete the
     * delegated task; the last known result is the fallback presentation.
     */
    FALLBACK("fallback")
}
