package com.ufo.galaxy.runtime

/**
 * Canonical event emitted by [RuntimeController] when a cross-device takeover execution
 * terminates with a non-completion outcome (PR-23 — cross-device failure and fallback
 * state closure).
 *
 * This event is the **single authority** through which all Android surface layers
 * ([com.ufo.galaxy.ui.viewmodel.MainViewModel],
 * [com.ufo.galaxy.service.EnhancedFloatingService]) learn about takeover-level failures
 * without each needing to independently parse execution outcomes or duplicate fallback
 * decision logic.
 *
 * ## Usage
 *
 * Consumers must subscribe to [RuntimeController.takeoverFailure] and react to this event
 * to clear any stale "in-control" or "active" surface state that was set when the takeover
 * began.  The runtime state ([RuntimeController.RuntimeState]) and
 * [RuntimeController.attachedSession] are **not** modified by a takeover failure alone —
 * the device remains in cross-device mode and the attached session stays valid for the next
 * incoming delegated task.  Surface layers must use this event to align their local state
 * without independently transitioning the runtime.
 *
 * ## Cause semantics
 *
 * | [Cause]            | Description                                                                |
 * |--------------------|----------------------------------------------------------------------------|
 * | [Cause.FAILED]     | Pipeline threw an unclassified exception.                                  |
 * | [Cause.TIMEOUT]    | Execution exceeded its wall-clock budget ([TimeoutCancellationException]). |
 * | [Cause.CANCELLED]  | Execution was cancelled cooperatively ([CancellationException]).           |
 * | [Cause.DISCONNECT] | WS disconnect was detected while an active takeover was in flight.         |
 *
 * @param takeoverId  Stable takeover identifier from the original [TakeoverRequestEnvelope].
 * @param taskId      Task identifier from the original request envelope; may be empty when
 *                    the failure was caused by a disconnect rather than an explicit execution
 *                    outcome.
 * @param traceId     Trace identifier carried through the full delegation chain; used for
 *                    cross-layer correlation in structured logs.
 * @param reason      Human-readable failure description suitable for diagnostics panels and
 *                    log entries.  Not intended for user-facing display; callers should map
 *                    to a localised string if they surface this in UI.
 * @param cause       Machine-readable classification of the failure; determines how surface
 *                    layers should react (e.g. timeout vs. cancellation vs. network loss).
 */
data class TakeoverFallbackEvent(
    val takeoverId: String,
    val taskId: String,
    val traceId: String,
    val reason: String,
    val cause: Cause
) {
    /**
     * Machine-readable classification of a takeover failure outcome.
     *
     * @property wireValue Stable string safe to include in structured telemetry payloads.
     */
    enum class Cause(val wireValue: String) {
        /** Execution pipeline threw an unclassified exception. */
        FAILED("failed"),

        /**
         * Execution timed out — a [kotlinx.coroutines.TimeoutCancellationException] was
         * thrown by the pipeline.
         */
        TIMEOUT("timeout"),

        /**
         * Execution was cooperatively cancelled — a [kotlinx.coroutines.CancellationException]
         * (non-timeout subtype) was thrown by the pipeline.
         */
        CANCELLED("cancelled"),

        /**
         * The WebSocket connection dropped while a takeover was active.  The in-flight
         * execution may or may not have completed before the connection was lost; surface
         * layers must treat the takeover result as unknown and clear any active state.
         */
        DISCONNECT("disconnect")
    }
}
