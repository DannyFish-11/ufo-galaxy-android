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
 * ## Android recovery role: participant, not coordinator
 *
 * Android acts as a **recovery participant**.  The V2 center-side runtime
 * (OpenClawd / Galaxy Gateway) is the **recovery coordinator** — it decides
 * whether a session is resumable, generates and manages [CONTINUITY_TOKEN_WIRE_FIELD],
 * and orchestrates the multi-step recovery handshake.  Android MUST NOT attempt
 * to self-coordinate recovery or unilaterally resume interrupted execution.
 *
 * Concretely, the Android participant's duties are limited to the four tasks
 * listed under "Scope" below.  Anything beyond those four tasks belongs to the
 * V2 coordinator.  The [ANDROID_RECOVERY_ROLE] constant encodes this contract
 * as a stable wire value that can be echoed back to V2 in diagnostics or health
 * events.
 *
 * ## Scope
 *
 * [ContinuityRecoveryContext] is a **compatibility and semantic clarity** layer,
 * not a full recovery orchestrator.  Android-side (participant) responsibilities are:
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
 * ## Token boundary: continuity_token vs continuation_token
 *
 * Two opaque tokens coexist in the takeover/handoff protocol and MUST NOT be
 * confused:
 *
 * | Wire field                    | Source     | Scope                                           | Constant                           |
 * |-------------------------------|------------|-------------------------------------------------|------------------------------------|
 * | [CONTINUITY_TOKEN_WIRE_FIELD] | V2 / coordinator | Durable execution continuity across reconnects or handoffs; stable for the lifetime of the durable session era | [CONTINUITY_TOKEN_WIRE_FIELD] |
 * | [CONTINUATION_TOKEN_WIRE_FIELD] | Originating executor | Delegated handoff progress state; captures where execution was when delegation occurred; NOT stable across reconnects | [CONTINUATION_TOKEN_WIRE_FIELD] |
 *
 * The key distinction:
 * - `continuity_token` is **V2-managed** and represents the durable continuity context.
 *   Android echoes it back so V2 can correlate resumed execution with the originating
 *   session, but Android must never generate or mutate this token.
 * - `continuation_token` is **executor-managed** and represents in-progress handoff
 *   state.  It is only meaningful for [REASON_HANDOFF] dispatches and is absent for
 *   pure reconnect recovery dispatches.
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
 * @see DurableSessionContinuityRecord
 */
object ContinuityRecoveryContext {

    // ── Android recovery role ─────────────────────────────────────────────────

    /**
     * Wire value encoding Android's role in the V2 recovery protocol.
     *
     * Android is a **recovery participant**: it accepts, echoes, and preserves
     * recovery metadata, but does NOT coordinate or initiate recovery.  The V2
     * center-side runtime is the recovery coordinator.
     *
     * This constant may be included in V2 lifecycle events or diagnostics payloads
     * to allow the coordinator to confirm that the Android side is correctly
     * participating in the recovery protocol.
     */
    const val ANDROID_RECOVERY_ROLE = "recovery_participant"

    // ── Token wire-field name constants ───────────────────────────────────────

    /**
     * Wire field name for the **durable execution continuity token**.
     *
     * This token is:
     * - **Generated and managed by V2** (the recovery coordinator).
     * - **Stable** for the duration of a durable session era (across reconnects
     *   and handoffs within the same era).
     * - **Echoed back** by Android in result payloads so V2 can correlate resumed
     *   execution with its originating session.
     * - **Never generated or mutated** by Android.
     *
     * Contrast with [CONTINUATION_TOKEN_WIRE_FIELD], which is executor-managed
     * and scoped to a single delegated handoff.
     *
     * Inbound field name in [GoalExecutionPayload], [TaskAssignPayload],
     * [TakeoverRequestEnvelope], and [HandoffEnvelopeV2].
     */
    const val CONTINUITY_TOKEN_WIRE_FIELD = "continuity_token"

    /**
     * Wire field name for the **delegated handoff continuation state token**.
     *
     * This token is:
     * - **Generated by the originating executor** (the device that was running the
     *   task before delegation).
     * - **Scoped to a single handoff**: it captures the executor's in-progress state
     *   at the moment of delegation and is consumed by the receiving executor to
     *   restore its execution context.
     * - **Not stable across reconnects**: unlike [CONTINUITY_TOKEN_WIRE_FIELD], a
     *   continuation token is created per-delegation and is irrelevant for pure
     *   reconnect-recovery dispatches (where [interruption_reason] ==
     *   [REASON_RECONNECT] or [REASON_TRANSPORT_DEGRADED]).
     *
     * Contrast with [CONTINUITY_TOKEN_WIRE_FIELD], which is V2-managed and
     * stable for the durable session era.
     *
     * Inbound field name in [TakeoverRequestEnvelope] (PR-9).
     */
    const val CONTINUATION_TOKEN_WIRE_FIELD = "continuation_token"

    // ── Wire-value constants ──────────────────────────────────────────────────

    /**
     * [interruption_reason] wire value indicating that the interruption was caused
     * by a WebSocket reconnect within the same activation era.
     *
     * After this kind of interruption the durable session identity
     * ([DurableSessionContinuityRecord.durableSessionId]) remains stable; only
     * the per-connection runtime_session_id changes.
     *
     * A [CONTINUATION_TOKEN_WIRE_FIELD] is typically absent for reconnect dispatches;
     * [CONTINUITY_TOKEN_WIRE_FIELD] may be present to anchor correlation.
     */
    const val REASON_RECONNECT = "reconnect"

    /**
     * [interruption_reason] wire value indicating that the interruption was caused
     * by a device handoff — the originating device delegated execution to this
     * Android device mid-task.
     *
     * A [CONTINUATION_TOKEN_WIRE_FIELD] is typically present for handoff dispatches,
     * carrying the originating executor's in-progress state.
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

    // ── Recovery participant role helpers ─────────────────────────────────────

    /**
     * Returns `true` when [role] matches [ANDROID_RECOVERY_ROLE].
     *
     * Use this helper to confirm that a device is correctly self-identifying as a
     * recovery participant in diagnostics or health payloads.
     *
     * @param role The role string to check; typically sourced from a V2 lifecycle
     *             event or a device health snapshot.
     */
    fun isRecoveryParticipant(role: String?): Boolean = role == ANDROID_RECOVERY_ROLE

    // ── Token scope helpers ───────────────────────────────────────────────────

    /**
     * Returns `true` when [reason] is one of the transport-layer interruption reasons
     * for which a [CONTINUATION_TOKEN_WIRE_FIELD] is typically **absent** and only
     * [CONTINUITY_TOKEN_WIRE_FIELD] (V2-managed) is relevant.
     *
     * This is provided as a guard for code that conditionally processes continuation
     * tokens: callers that receive a [REASON_RECONNECT] or [REASON_TRANSPORT_DEGRADED]
     * dispatch should not expect a meaningful [CONTINUATION_TOKEN_WIRE_FIELD] value.
     *
     * @param reason Raw [interruption_reason] string from the inbound envelope.
     */
    fun isContinuityOnlyInterruption(reason: String?): Boolean =
        reason == REASON_RECONNECT || reason == REASON_TRANSPORT_DEGRADED

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
