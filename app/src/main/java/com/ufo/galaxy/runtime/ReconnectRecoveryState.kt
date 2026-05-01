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
 * ## Android as recovery participant
 *
 * This enum reflects **Android's participant view** of the recovery lifecycle.
 * Android is a recovery *participant*, not a recovery *coordinator*.  The V2
 * center-side runtime (OpenClawd / Galaxy Gateway) is the recovery coordinator
 * that decides whether a session is resumable, drives the recovery handshake, and
 * manages the durable continuity context.
 *
 * Android's role in recovery is limited to:
 *  - Observing the WS reconnect lifecycle and surfacing it to the UI.
 *  - Reopening the attached session ([AttachedRuntimeSession]) after the WS
 *    reconnects, incrementing the [DurableSessionContinuityRecord.sessionContinuityEpoch].
 *  - Emitting [V2MultiDeviceLifecycleEvent.DeviceReconnected] so the V2 coordinator
 *    can track the participant's re-entry into the session.
 *
 * Android MUST NOT attempt to unilaterally resume interrupted execution or generate
 * continuity tokens.
 *
 * ## Continuity consistency guarantee
 *
 * The [RECOVERED] state is only set by [RuntimeController] **after** the call to
 * `openAttachedSession(SessionOpenSource.RECONNECT_RECOVERY)` completes — which
 * increments [DurableSessionContinuityRecord.sessionContinuityEpoch] and emits
 * [V2MultiDeviceLifecycleEvent.DeviceReconnected] with the updated epoch.
 *
 * Observers of [RuntimeController.reconnectRecoveryState] can therefore rely on the
 * following invariant when they see [RECOVERED]:
 *  - [RuntimeController.durableSessionContinuityRecord] already carries the
 *    incremented epoch for this reconnect cycle.
 *  - [V2MultiDeviceLifecycleEvent.DeviceReconnected] has already been emitted on
 *    [RuntimeController.v2LifecycleEvents] with that same epoch.
 *
 * This ordering guarantee keeps the reconnect state and the continuity output
 * consistently observable without races.
 *
 * ## Lifecycle
 *
 *  - [IDLE] — No recovery is in progress.  The runtime is either fully connected or
 *    cleanly stopped.  This is the initial state and the state after [stop][RuntimeController.stop].
 *  - [RECOVERING] — The WS connection dropped while the runtime was in
 *    [RuntimeController.RuntimeState.Active].  The WS client's automatic exponential-backoff
 *    reconnect is in progress.  Surface layers should show a "Recovering…" banner.
 *  - [RECOVERED] — The WS reconnect succeeded after a prior [RECOVERING] transition.
 *    The attached session has been reopened and the durable continuity epoch has been
 *    incremented.  Surface layers may briefly show a "Connected" confirmation before
 *    returning to normal.
 *  - [FAILED] — All reconnect attempts were exhausted (or the WS emitted a terminal
 *    error) while in [RECOVERING].  User action is required to restore connectivity
 *    (e.g. check network, change gateway settings, or tap "Reconnect").
 *
 * ## Transitions (driven by [RuntimeController])
 *
 *  - Active + WS disconnect                          → [RECOVERING]
 *  - [RECOVERING] + WS reconnect (success)           → [RECOVERED]  (epoch already updated)
 *  - [RECOVERING] + WS terminal error                → [FAILED]
 *  - [FAILED] + watchdog timer fires                 → [RECOVERING]  (perpetual re-entry)
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

    /**
     * Successfully reconnected after a short disconnect; attached session resumed and
     * [DurableSessionContinuityRecord.sessionContinuityEpoch] has been incremented.
     */
    RECOVERED("recovered"),

    /**
     * All reconnect attempts were exhausted (or the WS emitted a terminal error) while in
     * [RECOVERING].  The WS client has entered a perpetual watchdog cycle — it will continue
     * scheduling reconnect attempts at the capped backoff interval indefinitely.  After
     * [RuntimeController.WATCHDOG_RECOVERY_REENTRY_DELAY_MS] the runtime will automatically
     * re-enter [RECOVERING] to reflect the next watchdog attempt cycle.
     *
     * Surface layers should show a temporary "Connection failed — retrying…" indication
     * rather than a permanent "manual rescue required" state.  User action is only needed
     * if the device should be explicitly disconnected (e.g. network is intentionally down).
     */
    FAILED("failed")
}
