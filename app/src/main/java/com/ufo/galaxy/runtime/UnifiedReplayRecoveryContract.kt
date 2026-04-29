package com.ufo.galaxy.runtime

import com.ufo.galaxy.network.OfflineTaskQueue

/**
 * PR-74 (Android) — Unified replay and continuity execution contract.
 *
 * This object is the single authoritative Android-side definition of the unified
 * reconnect / re-register / replay execution contract.  It formalizes the semantics
 * that were previously scattered across:
 *
 *  - [com.ufo.galaxy.network.GalaxyWebSocketClient.flushOfflineQueue] (replay trigger + drain)
 *  - [OfflineTaskQueue.discardForDifferentSession] (authority filtering)
 *  - [RuntimeController] / [AndroidContinuityIntegration] (reconnect / re-register)
 *
 * ## Problem: Three Parallel Recovery Semantics
 *
 * Before PR-74, the Android offline recovery path had three distinct, uncoordinated semantics:
 *
 *  1. **Reconnect**: [com.ufo.galaxy.network.GalaxyWebSocketClient] handles WS reconnect
 *     with exponential backoff.
 *  2. **Re-register**: [com.ufo.galaxy.network.GalaxyWebSocketClient.sendHandshake] sends
 *     `device_register` + `capability_report` on `onOpen`.
 *  3. **Replay**: `flushOfflineQueue` drains the offline queue.
 *
 * Each operated independently.  Most critically, offline queue messages were never tagged
 * with the current durable session identity at enqueue time, making the authority filter
 * ([OfflineTaskQueue.discardForDifferentSession]) unable to actually block stale messages:
 * all messages carried a `null` sessionTag and null-tagged messages are always forwarded.
 *
 * ## Solution: Unified Contract
 *
 * PR-74 closes all three gaps as a coherent contract:
 *
 *  1. **Session tag at enqueue time** — [com.ufo.galaxy.network.GalaxyWebSocketClient.sendJson]
 *     now tags every offline-queued message with the current `durableSessionId`.  This enables
 *     [OfflineTaskQueue.discardForDifferentSession] to detect and discard stale-session messages
 *     when the session changes before the queue is drained on reconnect.
 *
 *  2. **Sequential recovery phases** — [RecoveryPhase] defines the canonical phase sequence:
 *     CONNECTING → RE_REGISTERING → AUTHORITY_FILTERING → REPLAYING → RECOVERED.
 *     All three components (reconnect / re-register / replay) are unified into one narrative.
 *
 *  3. **Send gate enforcement** — all replay messages are sent via
 *     [com.ufo.galaxy.network.GalaxyWebSocketClient.sendJson], which enforces the cross-device
 *     gate and connection check.  Replay cannot bypass the gate.  This closes the
 *     "online walks one path, offline replay walks another" split.
 *
 *  4. **Authority filter semantics** — [MessageAuthorityDecision] captures exactly which
 *     messages can be replayed and which must be blocked, and [evaluateMessageAuthority]
 *     provides the canonical single-call implementation of the filter.
 *
 * ## Recovery Phase Sequence
 *
 * Recovery MUST follow this phase sequence:
 * ```
 * CONNECTING → RE_REGISTERING → AUTHORITY_FILTERING → REPLAYING → RECOVERED
 * ```
 * Phases may not be skipped (AUTHORITY_FILTERING may produce zero discards but still executes).
 *
 * | Phase               | Android action                                              |
 * |---------------------|-------------------------------------------------------------|
 * | CONNECTING          | WS reconnect with exponential backoff                       |
 * | RE_REGISTERING      | `device_register` + `capability_report` sent via sendJson   |
 * | AUTHORITY_FILTERING | `discardForDifferentSession(currentDurableSessionId)`       |
 * | REPLAYING           | `drainAll()` → `sendJson(msg.json)` for each message        |
 * | RECOVERED           | All messages flushed; device is fully recovered             |
 *
 * ## Send Gate Contract
 *
 * - REPLAY **MUST** go through `GalaxyWebSocketClient.sendJson()`.
 * - REPLAY **MUST NOT** call the underlying WebSocket `send()` directly.
 * - This contract ensures the cross-device gate and connection check are uniformly enforced
 *   for both online sends and offline replay — there is no dual-track send path.
 *
 * ## Authority Filter Contract
 *
 * Every queued message is evaluated against the current durable session:
 *
 * | sessionTag        | currentDurableSessionId | Decision                        |
 * |-------------------|-------------------------|---------------------------------|
 * | `null`            | any                     | NO_SESSION_TAG_FORWARDED        |
 * | `"session-A"`     | `null`                  | SAME_SESSION_REPLAY_ALLOWED     |
 * | `"session-A"`     | `"session-A"`           | SAME_SESSION_REPLAY_ALLOWED     |
 * | `"session-OLD"`   | `"session-NEW"`         | STALE_SESSION_BLOCKED           |
 *
 * ## V2 Contract Alignment
 *
 * This contract aligns with V2 unified result ingress and continuity authority principles:
 *
 * - **Principle P2**: No replay bypasses the session/continuity authority filter.
 * - **Principle P5**: Online sends and offline replay use the same result contract
 *   (`goal_execution_result` AIP v3 envelope via the same `sendJson` gate).
 * - **Principle P1**: Replay results enter the same V2 unified result ingress as online results.
 *
 * @see com.ufo.galaxy.network.GalaxyWebSocketClient
 * @see OfflineTaskQueue
 * @see DurableSessionContinuityRecord
 * @see AndroidContinuityIntegration
 */
object UnifiedReplayRecoveryContract {

    // ── PR identifier ─────────────────────────────────────────────────────────

    /** The Android PR number that introduced this unified contract. */
    const val INTRODUCED_PR = 74

    /** Wire schema version for this contract record. */
    const val SCHEMA_VERSION = "1.0"

    /**
     * The send gate identifier that all replay messages MUST pass through.
     *
     * The actual gate is [com.ufo.galaxy.network.GalaxyWebSocketClient.sendJson].
     * Replay MUST NOT call the underlying WebSocket `send()` directly.
     */
    const val REQUIRED_SEND_GATE = "GalaxyWebSocketClient.sendJson"

    // ── RecoveryPhase ─────────────────────────────────────────────────────────

    /**
     * Sequential phases of the unified recovery execution contract.
     *
     * Recovery MUST proceed through these phases in order:
     * CONNECTING → RE_REGISTERING → AUTHORITY_FILTERING → REPLAYING → RECOVERED.
     *
     * @property wireValue Stable string identifier for wire transmission and audit logs.
     */
    enum class RecoveryPhase(val wireValue: String) {
        /** WS reconnect in progress; exponential backoff is active. */
        CONNECTING("connecting"),

        /** WS opened; `device_register` + `capability_report` being sent via sendJson. */
        RE_REGISTERING("re_registering"),

        /** Stale-session messages being purged from the offline queue via
         *  [OfflineTaskQueue.discardForDifferentSession]. */
        AUTHORITY_FILTERING("authority_filtering"),

        /** Remaining messages being drained and forwarded via sendJson. */
        REPLAYING("replaying"),

        /** All offline messages flushed; device is fully recovered. */
        RECOVERED("recovered");

        companion object {
            /** Returns the [RecoveryPhase] for the given [wireValue], or null if unrecognised. */
            fun fromWireValue(value: String?): RecoveryPhase? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this enum. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── MessageAuthorityDecision ──────────────────────────────────────────────

    /**
     * Authority filter decision for a single queued message during the
     * [RecoveryPhase.AUTHORITY_FILTERING] phase.
     *
     * @property wireValue       Stable string identifier for structured log emission.
     * @property isReplayAllowed Whether the message may be forwarded during replay.
     */
    enum class MessageAuthorityDecision(val wireValue: String, val isReplayAllowed: Boolean) {
        /**
         * Message has no session tag (`null`); forwarded regardless of session.
         * This covers legacy callers and pre-PR-74 messages queued without a sessionTag.
         */
        NO_SESSION_TAG_FORWARDED("no_session_tag_forwarded", isReplayAllowed = true),

        /**
         * Message session tag matches (or is compatible with) the current durable session;
         * replay is allowed.
         */
        SAME_SESSION_REPLAY_ALLOWED("same_session_replay_allowed", isReplayAllowed = true),

        /**
         * Message session tag differs from the current durable session; the message is stale
         * and MUST be blocked to prevent cross-session pollution of the new connection.
         */
        STALE_SESSION_BLOCKED("stale_session_blocked", isReplayAllowed = false);

        companion object {
            /** Returns the [MessageAuthorityDecision] for the given [wireValue], or null. */
            fun fromWireValue(value: String?): MessageAuthorityDecision? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── evaluateMessageAuthority ──────────────────────────────────────────────

    /**
     * Evaluates the authority of a queued [message] given the [currentDurableSessionId].
     *
     * This is the canonical single-call implementation of the PR-74 authority filter
     * contract for individual messages.  It encodes the decision table documented in
     * [MessageAuthorityDecision].
     *
     * **Usage**: call this (or the equivalent [OfflineTaskQueue.discardForDifferentSession]
     * batch API) during [RecoveryPhase.AUTHORITY_FILTERING] before draining the queue.
     *
     * @param message                 The queued message to evaluate.
     * @param currentDurableSessionId The active durable session ID (`null` if no session is
     *                                currently active — all tagged messages are allowed through).
     * @return [MessageAuthorityDecision]; check [MessageAuthorityDecision.isReplayAllowed].
     */
    fun evaluateMessageAuthority(
        message: OfflineTaskQueue.QueuedMessage,
        currentDurableSessionId: String?
    ): MessageAuthorityDecision {
        val tag = message.sessionTag
        return when {
            tag == null -> MessageAuthorityDecision.NO_SESSION_TAG_FORWARDED
            currentDurableSessionId == null -> MessageAuthorityDecision.SAME_SESSION_REPLAY_ALLOWED
            tag == currentDurableSessionId -> MessageAuthorityDecision.SAME_SESSION_REPLAY_ALLOWED
            else -> MessageAuthorityDecision.STALE_SESSION_BLOCKED
        }
    }

    // ── Canonical phase sequence ──────────────────────────────────────────────

    /**
     * The ordered list of [RecoveryPhase] values that the unified recovery execution
     * MUST follow.
     *
     * Consumers can use this list to validate that their recovery implementation
     * executes the phases in the correct order.
     */
    val canonicalPhaseSequence: List<RecoveryPhase> = listOf(
        RecoveryPhase.CONNECTING,
        RecoveryPhase.RE_REGISTERING,
        RecoveryPhase.AUTHORITY_FILTERING,
        RecoveryPhase.REPLAYING,
        RecoveryPhase.RECOVERED
    )

    // ── Machine-consumable wire map ───────────────────────────────────────────

    /**
     * Builds a machine-consumable wire map of this contract for V2 consumption
     * and dual-repo audit artifacts.
     *
     * The returned map is stable and suitable for:
     *  - V2 unified result ingress consumption
     *  - Dual-repo recovery audit artifacts
     *  - Structured log emission
     *
     * All boolean contract assertions are `true` — they assert that the contract
     * is in its closed, fully-implemented state as delivered by PR-74.
     */
    fun buildContractWireMap(): Map<String, Any> = mapOf(
        "schema_version" to SCHEMA_VERSION,
        "introduced_pr" to INTRODUCED_PR,
        "required_send_gate" to REQUIRED_SEND_GATE,
        "canonical_phase_sequence" to canonicalPhaseSequence.map { it.wireValue },
        "authority_filter_decisions" to MessageAuthorityDecision.entries.map { it.wireValue },
        // Contract assertions — all true in the closed PR-74 implementation:
        "session_tag_set_at_enqueue" to true,
        "replay_uses_unified_gate" to true,
        "stale_session_messages_blocked" to true,
        "online_offline_contract_unified" to true,
        "authority_filtering_precedes_replay" to true
    )
}
