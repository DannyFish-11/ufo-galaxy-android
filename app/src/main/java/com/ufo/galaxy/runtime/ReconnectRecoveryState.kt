package com.ufo.galaxy.runtime

/**
 * PR-33 — Observable product-grade reconnect recovery state for the Android runtime.
 *
 * Tracks the current phase of the WS reconnect lifecycle from the **user's perspective**,
 * allowing surface layers ([com.ufo.galaxy.ui.viewmodel.MainViewModel],
 * [com.ufo.galaxy.service.EnhancedFloatingService]) to present a meaningful
 * "Recovering…" indicator during short disconnects rather than simply toggling
 * `isConnected=false` and leaving the user uncertain whether the system is self-healing.
 *
 * ## Lifecycle
 *
 *  - [IDLE] — No recovery is in progress.  The runtime is either fully connected or
 *    cleanly stopped.  This is the initial state and the state after [stop][RuntimeController.stop].
 *  - [RECOVERING] — The WS connection dropped while the runtime was in
 *    [RuntimeController.RuntimeState.Active].  The WS client's automatic exponential-backoff
 *    reconnect is in progress.  Surface layers should show a "Recovering…" banner.
 *  - [RECOVERED] — The WS reconnect succeeded after a prior [RECOVERING] transition.
 *    The attached session has been reopened and execution truth is restored.  Surface
 *    layers may briefly show a "Connected" confirmation before returning to normal.
 *  - [FAILED] — All reconnect attempts were exhausted (or the WS emitted a terminal
 *    error) while in [RECOVERING].  User action is required to restore connectivity
 *    (e.g. check network, change gateway settings, or tap "Reconnect").
 *
 * ## Transitions (driven by [RuntimeController])
 *
 *  - Active + WS disconnect                          → [RECOVERING]
 *  - [RECOVERING] + WS reconnect (success)           → [RECOVERED]
 *  - [RECOVERING] + WS terminal error                → [FAILED]
 *  - [stop][RuntimeController.stop] / kill-switch    → [IDLE]   (always)
 *
 * ## V2 field / semantic compatibility
 *
 * The [wireValue] string is the stable, serialisable representation used in
 * [com.ufo.galaxy.observability.GalaxyLogger] structured log entries and any
 * host-facing protocol fields that carry recovery state.  Do **not** change
 * [wireValue] after this PR ships without a corresponding V2 contract update.
 */
enum class ReconnectRecoveryState(val wireValue: String) {

    /** No recovery in progress; runtime is connected or cleanly stopped. */
    IDLE("idle"),

    /** WS disconnected while Active; automatic reconnect in progress. Show "Recovering…". */
    RECOVERING("recovering"),

    /** Successfully reconnected after a short disconnect; attached session resumed. */
    RECOVERED("recovered"),

    /** Reconnect attempts exhausted; user action required to restore connectivity. */
    FAILED("failed")
}
