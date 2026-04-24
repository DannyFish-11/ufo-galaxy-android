package com.ufo.galaxy.runtime

/**
 * PR-7 (Android) — Sealed class representing the Android-side canonical execution event
 * emit decision.
 *
 * [CanonicalExecutionEventEmitDecision] is the typed output of
 * [AndroidCanonicalExecutionEventOwner.evaluateEventEmit].  Every canonical execution
 * event emission point on Android must evaluate a decision before forwarding any
 * [CanonicalExecutionEvent] to V2.  Callers must handle all variants and must not emit
 * without inspecting the decision.
 *
 * ## Emit decision semantics
 *
 * Each concrete subtype corresponds to a named Android-side event emit semantic:
 *
 * | Subtype                        | Semantic tag                        | Description                                                                    |
 * |--------------------------------|-------------------------------------|--------------------------------------------------------------------------------|
 * | [EmitEvent]                    | `emit_event`                        | The event may be emitted and forwarded to V2.                                  |
 * | [SuppressDuplicateEvent]       | `suppress_duplicate_event`          | This event ID has already been recorded; suppress the duplicate.               |
 * | [SuppressHighFrequencyNoise]   | `suppress_high_frequency_noise`     | The event would constitute high-frequency noise; suppress to avoid operator surface flooding. |
 * | [SuppressPostTerminal]         | `suppress_post_terminal`            | The flow has reached a terminal state; conflicting execution events are suppressed. |
 * | [HoldForReconnectAlignment]    | `hold_for_reconnect_alignment`      | The event must not be emitted until reconnect/resume alignment with V2 completes. |
 *
 * ## Android canonical event boundary
 *
 * Only [EmitEvent] authorises forwarding of the event to V2.  All suppression and hold
 * decisions must prevent forwarding.  The caller is responsible for optionally buffering
 * [HoldForReconnectAlignment] events for replay once alignment is restored.
 *
 * @see AndroidCanonicalExecutionEventOwner
 * @see CanonicalExecutionEvent
 */
sealed class CanonicalExecutionEventEmitDecision {

    /**
     * Stable wire tag identifying the emit semantic for this decision.
     *
     * Matches one of the [AndroidCanonicalExecutionEventOwner] `DECISION_*` constants.
     */
    abstract val semanticTag: String

    // ── EmitEvent ─────────────────────────────────────────────────────────────

    /**
     * The event may be emitted and forwarded to V2 operator surfaces.
     *
     * All suppression and hold conditions were evaluated and none applied.  The caller
     * must forward [event] to the V2 execution event uplink sink.
     *
     * Android-side semantic:
     * [AndroidCanonicalExecutionEventOwner.DECISION_EMIT_EVENT].
     *
     * @property event  The [CanonicalExecutionEvent] that is cleared for emission.
     */
    data class EmitEvent(
        val event: CanonicalExecutionEvent
    ) : CanonicalExecutionEventEmitDecision() {
        override val semanticTag: String =
            AndroidCanonicalExecutionEventOwner.DECISION_EMIT_EVENT
    }

    // ── SuppressDuplicateEvent ────────────────────────────────────────────────

    /**
     * This event has already been emitted; the current emission must be suppressed.
     *
     * The [eventId] was previously recorded via
     * [AndroidCanonicalExecutionEventOwner.markEventEmitted].  Re-emitting the same event
     * would violate the idempotent canonical projection contract and could cause V2 to
     * process the same event multiple times.
     *
     * Android-side semantic:
     * [AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_DUPLICATE_EVENT].
     *
     * @property flowId   The delegated flow ID.
     * @property eventId  The event ID that was already recorded as emitted.
     */
    data class SuppressDuplicateEvent(
        val flowId: String,
        val eventId: String
    ) : CanonicalExecutionEventEmitDecision() {
        override val semanticTag: String =
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_DUPLICATE_EVENT
    }

    // ── SuppressHighFrequencyNoise ────────────────────────────────────────────

    /**
     * The event would constitute high-frequency noise and must be suppressed.
     *
     * This decision applies when the event is a [CanonicalExecutionEventType.EXECUTION_PROGRESS]
     * or [CanonicalExecutionEventType.PLANNING_PROGRESS] or [CanonicalExecutionEventType.GROUNDING_PROGRESS]
     * event whose [CanonicalExecutionEvent.stepIndex] is lower than the last recorded
     * step index for this flow, or when the high-frequency noise gate is active for the
     * given [flowId].
     *
     * Android-side semantic:
     * [AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_HIGH_FREQUENCY_NOISE].
     *
     * @property flowId  The delegated flow ID.
     * @property reason  Human-readable explanation of why the event is suppressed as noise.
     */
    data class SuppressHighFrequencyNoise(
        val flowId: String,
        val reason: String
    ) : CanonicalExecutionEventEmitDecision() {
        override val semanticTag: String =
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_HIGH_FREQUENCY_NOISE
    }

    // ── SuppressPostTerminal ──────────────────────────────────────────────────

    /**
     * The flow has reached a terminal state; conflicting execution events are suppressed.
     *
     * Once the delegated flow is marked terminal via
     * [AndroidCanonicalExecutionEventOwner.markFlowTerminal], execution events that would
     * contradict or extend the terminal state MUST NOT be forwarded to V2.  This prevents
     * the operator surface from showing stale "still running" events after a flow has
     * already completed, failed, or been cancelled.
     *
     * Android-side semantic:
     * [AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_POST_TERMINAL].
     *
     * @property flowId         The delegated flow ID that is already terminal.
     * @property terminalReason The terminal reason recorded via
     *                          [AndroidCanonicalExecutionEventOwner.markFlowTerminal].
     */
    data class SuppressPostTerminal(
        val flowId: String,
        val terminalReason: String
    ) : CanonicalExecutionEventEmitDecision() {
        override val semanticTag: String =
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_POST_TERMINAL
    }

    // ── HoldForReconnectAlignment ─────────────────────────────────────────────

    /**
     * The event must be held until reconnect / resume alignment with V2 completes.
     *
     * The event emit gate for [flowId] is currently closed
     * ([AndroidCanonicalExecutionEventOwner.isEventGated] returns `true`), indicating
     * that a transport reconnect, session rebind, or recovery replay is in progress and
     * the canonical event stream alignment with V2 is not yet established.  Android must
     * buffer or discard the event until the gate opens.
     *
     * After the gate opens, callers can re-evaluate any buffered events to check whether
     * they should be emitted (reconnect replay path) or discarded (stale events from the
     * pre-reconnect era).
     *
     * Android-side semantic:
     * [AndroidCanonicalExecutionEventOwner.DECISION_HOLD_FOR_RECONNECT_ALIGNMENT].
     *
     * @property flowId  The delegated flow ID whose event emission is held.
     * @property reason  Human-readable explanation of why the hold is in effect.
     */
    data class HoldForReconnectAlignment(
        val flowId: String,
        val reason: String
    ) : CanonicalExecutionEventEmitDecision() {
        override val semanticTag: String =
            AndroidCanonicalExecutionEventOwner.DECISION_HOLD_FOR_RECONNECT_ALIGNMENT
    }
}
