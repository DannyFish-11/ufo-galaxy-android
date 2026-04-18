package com.ufo.galaxy.runtime

/**
 * PR-F — Android-side continuity and recovery context model for durable
 * dispatch-to-session continuity in multi-device runtime execution.
 *
 * V2 introduces durable continuity semantics to preserve execution and session
 * association across reconnects, handoffs, and recoverable interruptions in
 * multi-device runtime flows.  This object declares the Android-side vocabulary
 * and helpers that allow device-targeted execution to participate in
 * reconnect/resume flows without ambiguity.
 *
 * ## Scope
 *
 * [ContinuityRecoveryContext] is a **compatibility and semantic clarity** layer,
 * not a full recovery orchestrator.  Android-side responsibilities are:
 *
 * 1. **Accept** continuity/recovery metadata in inbound execution contracts
 *    without failing.
 * 2. **Preserve** enough context (token, resumability flag) to allow V2 to
 *    correlate resumed execution with its originating session.
 * 3. **Distinguish** resumable interruption from terminal loss so that V2 does
 *    not incorrectly classify a recoverable Android-side state as a permanent
 *    failure.
 * 4. **Remain backward compatible** — all fields are optional; null/absent
 *    values must be treated as equivalent to the legacy contract.
 *
 * ## Wire-value constants
 *
 * [InterruptionReason] carries the recognised [interruption_reason] wire values.
 * Unknown values are always tolerated; callers must NOT reject envelopes that
 * contain unrecognised reasons.
 *
 * ## Resumability semantics
 *
 * | [is_resumable] wire value | Android interpretation                                          |
 * |---------------------------|----------------------------------------------------------------|
 * | `true`                    | V2 considers this a resumable execution; Android MUST NOT       |
 * |                           | translate its own intermediate state into terminal failure.      |
 * | `false`                   | V2 considers this a terminal dispatch; Android may treat        |
 * |                           | cleanup as final.                                               |
 * | `null`                    | Legacy / unspecified; Android applies default behaviour.         |
 *
 * @see GoalExecutionPayload
 * @see TaskAssignPayload
 * @see TakeoverRequestEnvelope
 * @see HandoffEnvelopeV2
 */
object ContinuityRecoveryContext {

    // ── Wire-value constants ──────────────────────────────────────────────────

    /**
     * [interruption_reason] wire value indicating that the interruption was caused
     * by a WebSocket reconnect within the same activation era.
     *
     * After this kind of interruption the durable session identity
     * ([DurableSessionContinuityRecord.durableSessionId]) remains stable; only
     * the per-connection runtime_session_id changes.
     */
    const val REASON_RECONNECT = "reconnect"

    /**
     * [interruption_reason] wire value indicating that the interruption was caused
     * by a device handoff — the originating device delegated execution to this
     * Android device mid-task.
     */
    const val REASON_HANDOFF = "handoff"

    /**
     * [interruption_reason] wire value indicating that the device was temporarily
     * paused (e.g. screen off, app backgrounded) but the session is still valid.
     */
    const val REASON_DEVICE_PAUSE = "device_pause"

    /**
     * [interruption_reason] wire value indicating a transport-level degradation
     * (quality drop) that may or may not resolve.
     */
    const val REASON_TRANSPORT_DEGRADED = "transport_degraded"

    /**
     * Set of all recognised [interruption_reason] wire values.
     *
     * Callers MUST tolerate values outside this set; future V2 versions may
     * introduce new reasons.
     */
    val KNOWN_INTERRUPTION_REASONS: Set<String> = setOf(
        REASON_RECONNECT,
        REASON_HANDOFF,
        REASON_DEVICE_PAUSE,
        REASON_TRANSPORT_DEGRADED
    )

    // ── Resumability helpers ──────────────────────────────────────────────────

    /**
     * Returns `true` when [isResumable] indicates that V2 intends this execution
     * to be resumable (i.e. [isResumable] == `true`).
     *
     * A `null` value (legacy / unspecified) does NOT count as resumable; callers
     * must apply the legacy default behaviour in that case.
     *
     * @param isResumable The [GoalExecutionPayload.is_resumable] or equivalent field value.
     */
    fun isResumableExecution(isResumable: Boolean?): Boolean = isResumable == true

    /**
     * Returns `true` when [isResumable] unambiguously indicates terminal execution
     * (i.e. [isResumable] == `false`).
     *
     * A `null` value (legacy / unspecified) is NOT considered terminal; the caller
     * must treat it as "unknown / apply default".
     *
     * @param isResumable The [GoalExecutionPayload.is_resumable] or equivalent field value.
     */
    fun isTerminalExecution(isResumable: Boolean?): Boolean = isResumable == false

    /**
     * Returns `true` when [reason] is a recognised [interruption_reason] wire value.
     *
     * Unknown values always return `false`; they must still be accepted without
     * failure.
     *
     * @param reason Raw [interruption_reason] string from the inbound envelope.
     */
    fun isKnownInterruptionReason(reason: String?): Boolean =
        reason != null && KNOWN_INTERRUPTION_REASONS.contains(reason)

    /**
     * Returns `true` when [reason] represents a transport-layer interruption that
     * is structurally resumable (reconnect or degradation), as opposed to an
     * intent-driven handoff or device pause.
     *
     * This helper is provided for observability/logging only; the canonical
     * resumability decision is governed by [is_resumable] on the envelope, not
     * by the reason alone.
     *
     * @param reason Raw [interruption_reason] string from the inbound envelope.
     */
    fun isTransportInterruption(reason: String?): Boolean =
        reason == REASON_RECONNECT || reason == REASON_TRANSPORT_DEGRADED
}
