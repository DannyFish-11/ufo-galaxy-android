package com.ufo.galaxy.agent

import com.ufo.galaxy.runtime.AttachedRuntimeSession
import com.ufo.galaxy.runtime.DelegatedActivationRecord

/**
 * **Canonical Android-side gate for delegated runtime receipt and local activation** (PR-8,
 * post-#533 dual-repo runtime unification master plan — Delegated Runtime
 * Receipt/Activation Foundations, Android side).
 *
 * [DelegatedRuntimeReceiver] is the single authoritative component responsible for
 * deciding whether an inbound [TakeoverRequestEnvelope] (a delegated work unit dispatched
 * by the main-repo OpenClawd host) may be locally activated under the current
 * [AttachedRuntimeSession].
 *
 * ## Design intent
 *
 * Before PR-8, the check for whether Android should accept delegated work was performed
 * ad-hoc inside [com.ufo.galaxy.service.GalaxyConnectionService.handleTakeoverRequest]:
 * the session state was not consulted, and the only gate was device readiness
 * ([TakeoverEligibilityAssessor]).  This means Android could attempt to activate delegated
 * work even when no explicit [AttachedRuntimeSession] was present — conflating ordinary
 * WebSocket connectivity with persistent runtime participation.
 *
 * [DelegatedRuntimeReceiver] closes this gap.  It enforces that:
 *
 *  1. An [AttachedRuntimeSession] **must** exist and be in
 *     [AttachedRuntimeSession.State.ATTACHED] before delegated work is accepted.
 *     A null session or a session in [AttachedRuntimeSession.State.DETACHING] /
 *     [AttachedRuntimeSession.State.DETACHED] results in an immediate
 *     [ReceiptResult.Rejected] with a stable [RejectionOutcome].
 *
 *  2. When the session check passes, the receiver converts the raw
 *     [TakeoverRequestEnvelope] into a [DelegatedRuntimeUnit] (binding it to the
 *     session) and produces a [DelegatedActivationRecord] in
 *     [DelegatedActivationRecord.ActivationStatus.PENDING].
 *
 * ## Relationship to [TakeoverEligibilityAssessor]
 *
 * [TakeoverEligibilityAssessor] checks **device readiness** (cross-device switch,
 * goal-execution flag, accessibility, overlay, concurrency).
 * [DelegatedRuntimeReceiver] checks **session state** (is there an active
 * [AttachedRuntimeSession]?).  Both must pass before delegated work is accepted; they
 * operate at different levels and are composed in
 * [com.ufo.galaxy.service.GalaxyConnectionService.handleTakeoverRequest].
 *
 * ## Typical call site
 * ```kotlin
 * // Inside GalaxyConnectionService.handleTakeoverRequest():
 * val session = UFOGalaxyApplication.runtimeController.attachedSession.value
 * val receiptResult = delegatedRuntimeReceiver.receive(envelope, session)
 * when (receiptResult) {
 *     is DelegatedRuntimeReceiver.ReceiptResult.Accepted -> {
 *         // proceed with local execution using receiptResult.record
 *     }
 *     is DelegatedRuntimeReceiver.ReceiptResult.Rejected -> {
 *         // send rejection back to gateway with receiptResult.reason
 *     }
 * }
 * ```
 */
class DelegatedRuntimeReceiver {

    // ── Result types ──────────────────────────────────────────────────────────

    /**
     * Stable outcome code for a delegated-receipt evaluation.
     *
     * Each variant carries a stable [reason] string that is safe to include in
     * [com.ufo.galaxy.agent.TakeoverResponseEnvelope.rejection_reason] and in
     * structured telemetry.
     */
    enum class RejectionOutcome(val reason: String) {

        /**
         * No [AttachedRuntimeSession] exists.  Android has not established an explicit
         * cross-device runtime participation session with the host; delegated work cannot
         * be accepted until a session is formed.
         */
        NO_ATTACHED_SESSION("no_attached_session"),

        /**
         * The current [AttachedRuntimeSession] is in
         * [AttachedRuntimeSession.State.DETACHING]: graceful shutdown is in progress.
         * No new delegated units may be accepted.
         */
        SESSION_DETACHING("session_detaching"),

        /**
         * The current [AttachedRuntimeSession] is in
         * [AttachedRuntimeSession.State.DETACHED]: the session has fully terminated.
         * A new session must be established before delegated work can be accepted.
         */
        SESSION_DETACHED("session_detached")
    }

    /**
     * Result of a delegated-receipt evaluation.
     *
     * Produced by [receive] for every inbound [TakeoverRequestEnvelope].
     */
    sealed class ReceiptResult {

        /**
         * The delegated unit was accepted for local activation.
         *
         * @param unit   The [DelegatedRuntimeUnit] produced from the inbound envelope,
         *               bound to the active [AttachedRuntimeSession].
         * @param record The initial [DelegatedActivationRecord] in
         *               [DelegatedActivationRecord.ActivationStatus.PENDING]; callers must
         *               advance it through the lifecycle as execution progresses.
         */
        data class Accepted(
            val unit: DelegatedRuntimeUnit,
            val record: DelegatedActivationRecord
        ) : ReceiptResult()

        /**
         * The delegated unit was rejected before local execution could begin.
         *
         * @param outcome Machine-readable rejection outcome code.
         * @param reason  Human-readable rejection reason; mirrors [RejectionOutcome.reason]
         *                and is suitable for
         *                [com.ufo.galaxy.agent.TakeoverResponseEnvelope.rejection_reason].
         */
        data class Rejected(
            val outcome: RejectionOutcome,
            val reason: String
        ) : ReceiptResult()
    }

    // ── Core evaluation ───────────────────────────────────────────────────────

    /**
     * Evaluates whether the inbound [envelope] may be locally activated under [session].
     *
     * ## Decision flow
     * 1. If [session] is `null` → [ReceiptResult.Rejected] with
     *    [RejectionOutcome.NO_ATTACHED_SESSION].
     * 2. If [session.state][AttachedRuntimeSession.state] is
     *    [AttachedRuntimeSession.State.DETACHING] → [ReceiptResult.Rejected] with
     *    [RejectionOutcome.SESSION_DETACHING].
     * 3. If [session.state][AttachedRuntimeSession.state] is
     *    [AttachedRuntimeSession.State.DETACHED] → [ReceiptResult.Rejected] with
     *    [RejectionOutcome.SESSION_DETACHED].
     * 4. Otherwise ([AttachedRuntimeSession.State.ATTACHED]) → produces a
     *    [DelegatedRuntimeUnit] via [DelegatedRuntimeUnit.fromEnvelope], creates an
     *    initial [DelegatedActivationRecord] in [DelegatedActivationRecord.ActivationStatus.PENDING],
     *    and returns [ReceiptResult.Accepted].
     *
     * @param envelope      The inbound [TakeoverRequestEnvelope] dispatched by the host.
     * @param session       The current [AttachedRuntimeSession] from
     *                      [com.ufo.galaxy.runtime.RuntimeController.attachedSession], or
     *                      `null` when no session is active.
     * @param receivedAtMs  Epoch-ms receipt timestamp; defaults to the current time.
     * @return [ReceiptResult.Accepted] when the unit can be locally activated, or
     *         [ReceiptResult.Rejected] with the specific reason when it cannot.
     */
    fun receive(
        envelope: TakeoverRequestEnvelope,
        session: AttachedRuntimeSession?,
        receivedAtMs: Long = System.currentTimeMillis()
    ): ReceiptResult {
        // ── Session existence check ───────────────────────────────────────────
        if (session == null) {
            return ReceiptResult.Rejected(
                outcome = RejectionOutcome.NO_ATTACHED_SESSION,
                reason = RejectionOutcome.NO_ATTACHED_SESSION.reason
            )
        }

        // ── Session state check ───────────────────────────────────────────────
        when (session.state) {
            AttachedRuntimeSession.State.DETACHING -> {
                return ReceiptResult.Rejected(
                    outcome = RejectionOutcome.SESSION_DETACHING,
                    reason = RejectionOutcome.SESSION_DETACHING.reason
                )
            }
            AttachedRuntimeSession.State.DETACHED -> {
                return ReceiptResult.Rejected(
                    outcome = RejectionOutcome.SESSION_DETACHED,
                    reason = RejectionOutcome.SESSION_DETACHED.reason
                )
            }
            AttachedRuntimeSession.State.ATTACHED -> {
                // Session is valid — proceed to produce unit and activation record.
            }
        }

        // ── Acceptance: produce DelegatedRuntimeUnit and DelegatedActivationRecord ─
        val unit = DelegatedRuntimeUnit.fromEnvelope(
            envelope = envelope,
            attachedSessionId = session.sessionId,
            receivedAtMs = receivedAtMs
        )
        val record = DelegatedActivationRecord.create(
            unit = unit,
            activatedAtMs = receivedAtMs
        )
        return ReceiptResult.Accepted(unit = unit, record = record)
    }
}
