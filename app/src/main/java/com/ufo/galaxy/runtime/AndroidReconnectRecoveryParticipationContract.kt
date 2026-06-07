package com.ufo.galaxy.runtime

import com.ufo.galaxy.session.DurableParticipantIdentity

/**
 * PR-12 (Android) — Real reconnect, durable identity reuse, replay, and recovery
 * participation contract for cross-repository continuity and recovery closure with V2.
 *
 * Before this PR, Android had all the individual components needed for reconnect and
 * recovery participation (ReconnectRecoveryState, AndroidRecoveryParticipationOwner,
 * UnifiedReplayRecoveryContract, DurableParticipantIdentity) but no single canonical
 * object that:
 *
 *  - Tied them into a coherent, testable participation model
 *  - Produced structured wire output that V2 can act on during recovery closure
 *  - Classified each reconnect scenario into a machine-readable participation kind
 *  - Expressed identity-reuse semantics separately from session continuity
 *  - Declared replay eligibility decisions in wire form
 *
 * This contract closes that gap. It makes Android a **true runtime recovery participant**
 * by:
 *
 *  1. Classifying each reconnect or re-attach scenario into a [ReconnectParticipationKind]
 *     so V2 can apply the correct recovery closure path.
 *  2. Classifying how Android reuses its durable identity ([IdentityReuseDecision]) so
 *     V2 can correlate the recovering participant with its prior state record without
 *     re-issuing a new participant ID.
 *  3. Classifying the offline queue replay eligibility ([ReplayEligibility]) so V2 knows
 *     whether Android is replaying messages from a prior era or starting fresh.
 *  4. Packaging these three decisions into a [ReconnectParticipationSnapshot] with wire
 *     map output for V2 structured log consumption and audit.
 *  5. Exposing [PARTICIPATION_INVARIANTS] — machine-verifiable invariants that must all
 *     be `true`; any `false` value indicates a regression in this contract.
 *
 * ## Reconnect participation kinds
 *
 * | Kind                              | When it applies                                         |
 * |-----------------------------------|---------------------------------------------------------|
 * | TRANSPORT_RECONNECT               | WS dropped + reconnected within the same activation era; durable session ID is preserved; epoch increments. |
 * | PROCESS_RECREATION_WITH_CONTEXT   | Process killed; Android reattaches with a prior persisted flow context; presents context to V2 before continuing. |
 * | PROCESS_RECREATION_WITHOUT_CONTEXT| Process killed; no persisted context available; Android defers entirely to V2 for re-dispatch. |
 * | FRESH_ATTACH                      | No prior session; true first attach; no identity reuse. |
 *
 * ## Identity reuse decisions
 *
 * | Decision                 | When it applies                                              |
 * |--------------------------|--------------------------------------------------------------|
 * | REUSE_DURABLE_PARTICIPANT| Durable participant ID is known and fresh; V2 associates this participant with its prior record. |
 * | REUSE_SESSION_ONLY       | Durable session ID is known but participant ID is missing or stale; V2 can correlate the session but not the participant. |
 * | FRESH_IDENTITY           | No prior durable participant or session; Android starts a new participation era. |
 *
 * ## Replay eligibility decisions
 *
 * | Decision              | When it applies                                                 |
 * |-----------------------|-----------------------------------------------------------------|
 * | ELIGIBLE_FOR_REPLAY   | Offline queue contains messages tagged with the current durable session; they may be flushed to V2 via sendJson. |
 * | STALE_SESSION_BLOCKED | Session changed; offline queue messages carry a prior session tag and must be discarded rather than replayed. |
 * | QUEUE_EMPTY           | No messages are queued; replay phase is a no-op.               |
 *
 * ## Wire fields added to DeviceStateSnapshotPayload
 *
 * | Field                         | Wire key                      |
 * |-------------------------------|-------------------------------|
 * | reconnect_participation_kind  | "reconnect_participation_kind" |
 * | identity_reuse_decision       | "identity_reuse_decision"      |
 * | replay_eligibility            | "replay_eligibility"           |
 *
 * ## V2 consumption model
 *
 * V2's recovery closure logic MUST:
 *  1. Read `reconnect_participation_kind` to select the recovery closure path.
 *  2. Read `identity_reuse_decision` to determine whether to look up a prior participant
 *     record or initialise a new one.
 *  3. Read `replay_eligibility` to decide whether to expect a replay flush from Android
 *     before treating the session as fully recovered.
 *
 * @see ReconnectRecoveryState
 * @see AndroidRecoveryParticipationOwner
 * @see UnifiedReplayRecoveryContract
 * @see DurableParticipantIdentity
 * @see DurableSessionContinuityRecord
 * @see com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
 */
object AndroidReconnectRecoveryParticipationContract {

    /** PR identifier that introduced this contract. */
    const val INTRODUCED_PR = "PR-12"

    /** Wire schema version for this contract record. */
    const val SCHEMA_VERSION = "1.0"

    /** The canonical test class for this contract. */
    const val TEST_CLASS = "Pr12AndroidReconnectRecoveryParticipationTest"

    // ── ReconnectParticipationKind ────────────────────────────────────────────

    /**
     * Classification of the Android-side reconnect or re-attach scenario.
     *
     * Every reconnect or re-attach event falls into exactly one participation kind.
     * V2's recovery closure logic MUST use this classification to select the correct
     * recovery path rather than inferring it from combinations of other fields.
     *
     * @property wireValue Stable string identifier for wire transmission and audit logs.
     *                     Do NOT change after this PR ships without a V2 contract update.
     * @property v2RecoveryPath  Human-readable description of the V2 recovery path to apply.
     */
    enum class ReconnectParticipationKind(val wireValue: String, val v2RecoveryPath: String) {
        /**
         * WS dropped and reconnected within the same activation era.
         *
         * The durable session ID is preserved.  The session continuity epoch is
         * incremented to differentiate this reconnect from previous ones within the
         * same era.  V2 SHOULD associate the participant with its prior session record
         * and expect a replay flush of any offline-queued messages.
         */
        TRANSPORT_RECONNECT(
            wireValue = "transport_reconnect",
            v2RecoveryPath = "Associate with prior session record; epoch identifies reconnect sequence; expect replay flush."
        ),

        /**
         * Android process was killed and the process was recreated with a persisted
         * flow context available.
         *
         * Android rehydrates the prior execution context and presents it to V2 before
         * continuing.  V2 MUST authorise continuation based on the presented context
         * before Android re-enters any execution pipeline.
         */
        PROCESS_RECREATION_WITH_CONTEXT(
            wireValue = "process_recreation_with_context",
            v2RecoveryPath = "Receive rehydrated context from Android; decide whether to replay, resume, or restart."
        ),

        /**
         * Android process was killed and no persisted flow context is available.
         *
         * Android defers entirely to V2 for re-dispatch decisions.  V2 MUST NOT expect
         * Android to self-authorise any continuation; it will wait for a V2 re-dispatch
         * signal before entering any execution pipeline.
         */
        PROCESS_RECREATION_WITHOUT_CONTEXT(
            wireValue = "process_recreation_without_context",
            v2RecoveryPath = "Drive re-dispatch or start-fresh; Android is waiting for V2 decision."
        ),

        /**
         * No prior session exists; this is a true first attach.
         *
         * No identity reuse, no replay, no recovery overhead.  V2 initialises a new
         * participant record for this device.
         */
        FRESH_ATTACH(
            wireValue = "fresh_attach",
            v2RecoveryPath = "Initialise new participant record; no recovery actions required."
        );

        companion object {
            /** Returns the [ReconnectParticipationKind] for [wireValue], or null. */
            fun fromWireValue(value: String?): ReconnectParticipationKind? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this enum. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── IdentityReuseDecision ─────────────────────────────────────────────────

    /**
     * Classification of how Android reuses its durable identity during a reconnect.
     *
     * V2's recovery closure MUST use this decision to determine whether to look up
     * a prior participant record or initialise a new one.
     *
     * @property wireValue Stable string identifier for wire transmission.
     * @property v2ParticipantAction Description of the V2 action for participant record management.
     */
    enum class IdentityReuseDecision(val wireValue: String, val v2ParticipantAction: String) {
        /**
         * Durable participant ID is known and its freshness is FRESH or RECOVERED.
         *
         * V2 SHOULD look up the prior participant record by [durable_participant_id]
         * and associate this reconnect with the existing participant.  No new participant
         * record should be created.
         */
        REUSE_DURABLE_PARTICIPANT(
            wireValue = "reuse_durable_participant",
            v2ParticipantAction = "Look up prior participant record by durable_participant_id; associate reconnect."
        ),

        /**
         * Durable session ID is known but the durable participant ID is missing or stale.
         *
         * V2 can correlate the session but should treat the participant registration as
         * needing revalidation.  The prior participant record may be stale or unavailable.
         */
        REUSE_SESSION_ONLY(
            wireValue = "reuse_session_only",
            v2ParticipantAction = "Correlate by durable_session_id; revalidate or reinitialise participant record."
        ),

        /**
         * No prior durable participant or session identity is available.
         *
         * Android starts a new participation era.  V2 MUST initialise a new participant
         * record for this device.
         */
        FRESH_IDENTITY(
            wireValue = "fresh_identity",
            v2ParticipantAction = "Initialise new participant record; no prior identity to reuse."
        );

        companion object {
            /** Returns the [IdentityReuseDecision] for [wireValue], or null. */
            fun fromWireValue(value: String?): IdentityReuseDecision? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this enum. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── ReplayEligibility ─────────────────────────────────────────────────────

    /**
     * Classification of whether offline-queued messages are eligible for replay.
     *
     * This decision aligns with the [UnifiedReplayRecoveryContract.MessageAuthorityDecision]
     * authority filter, but is expressed as a single coarse classification suitable for
     * wire transmission and V2 recovery closure.
     *
     * @property wireValue Stable string identifier for wire transmission.
     * @property isReplayExpected Whether V2 should expect a replay flush from Android.
     */
    enum class ReplayEligibility(val wireValue: String, val isReplayExpected: Boolean) {
        /**
         * The offline queue contains messages tagged with the current durable session;
         * they are eligible for replay and will be flushed to V2 via sendJson during
         * the [UnifiedReplayRecoveryContract.RecoveryPhase.REPLAYING] phase.
         *
         * V2 SHOULD expect a replay flush from Android before treating the session as
         * fully recovered.
         */
        ELIGIBLE_FOR_REPLAY(
            wireValue = "eligible_for_replay",
            isReplayExpected = true
        ),

        /**
         * The session changed between the prior queue enqueue time and the current
         * reconnect.  All queued messages carry a stale session tag and MUST be discarded
         * rather than replayed.
         *
         * V2 SHOULD NOT expect a replay flush; Android will drain the queue by discarding
         * stale-session messages rather than forwarding them.
         */
        STALE_SESSION_BLOCKED(
            wireValue = "stale_session_blocked",
            isReplayExpected = false
        ),

        /**
         * The offline queue is empty; there are no messages to replay.
         *
         * The [UnifiedReplayRecoveryContract.RecoveryPhase.REPLAYING] phase is a no-op.
         * V2 SHOULD NOT expect a replay flush.
         */
        QUEUE_EMPTY(
            wireValue = "queue_empty",
            isReplayExpected = false
        );

        companion object {
            /** Returns the [ReplayEligibility] for [wireValue], or null. */
            fun fromWireValue(value: String?): ReplayEligibility? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this enum. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── ReconnectParticipationSnapshot ────────────────────────────────────────

    /**
     * Structured snapshot of Android's reconnect/recovery participation state at a given
     * instant.
     *
     * Produced by [classify] and suitable for:
     *  - Wire transmission in [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload] fields
     *  - Structured log emission
     *  - V2 recovery closure artifact production
     *  - Dual-repo audit artifacts
     *
     * @property participationKind    How this reconnect is classified.
     * @property identityReuseDecision How Android reuses its durable identity.
     * @property replayEligibility   Whether offline-queued messages are eligible for replay.
     * @property durableSessionId    The durable session ID from [DurableSessionContinuityRecord],
     *                               or `null` when no durable era is active.
     * @property durableParticipantId The durable participant ID from [AppSettings] /
     *                               [DurableParticipantIdentity], or `null` when unavailable.
     * @property sessionContinuityEpoch The monotone reconnect epoch within the durable era,
     *                               or `null` when no durable era is active.
     * @property recoveryAttemptKey  An optional opaque key identifying this recovery attempt
     *                               (typically a flow ID or recovery correlation ID).
     */
    data class ReconnectParticipationSnapshot(
        val participationKind: ReconnectParticipationKind,
        val identityReuseDecision: IdentityReuseDecision,
        val replayEligibility: ReplayEligibility,
        val durableSessionId: String?,
        val durableParticipantId: String?,
        val sessionContinuityEpoch: Long?,
        val recoveryAttemptKey: String? = null
    ) {
        /**
         * Builds a machine-consumable wire map of this snapshot for V2 consumption
         * and dual-repo audit artifacts.
         */
        fun toWireMap(): Map<String, Any?> = mapOf(
            "reconnect_participation_kind" to participationKind.wireValue,
            "identity_reuse_decision" to identityReuseDecision.wireValue,
            "replay_eligibility" to replayEligibility.wireValue,
            "durable_session_id" to durableSessionId,
            "durable_participant_id" to durableParticipantId,
            "session_continuity_epoch" to sessionContinuityEpoch,
            "recovery_attempt_key" to recoveryAttemptKey
        )
    }

    // ── ReconnectClassificationInput ─────────────────────────────────────────

    /**
     * Input bundle for [classify].
     *
     * Callers populate this from the live Android runtime state at the moment of
     * reconnect, re-attach, or fresh attach.
     *
     * @property reconnectRecoveryState  Current [ReconnectRecoveryState] wire value; used
     *                                   to detect transparent transport reconnects.
     * @property localRecoveryDecision   The [LocalRecoveryDecision] produced by
     *                                   [AndroidRecoveryParticipationOwner.evaluateRecovery].
     * @property durableSessionId        The current durable session ID, or `null` when no
     *                                   durable era is active.
     * @property durableParticipantId    The current durable participant ID, or `null` when
     *                                   no stable per-installation ID is available.
     * @property participantIdentityFreshness  Wire value of [DurableParticipantIdentity.IdentityFreshness]
     *                                   for the current participant registration, or `null`.
     * @property sessionContinuityEpoch  Current [DurableSessionContinuityRecord.sessionContinuityEpoch],
     *                                   or `null` when no durable era is active.
     * @property offlineQueueDepth       Number of messages currently in the offline queue;
     *                                   used to determine [ReplayEligibility].
     * @property queueSessionTagMatchesCurrent  `true` when the oldest offline queue message's
     *                                   session tag matches the current durable session ID;
     *                                   used to detect stale-session queue conditions.
     * @property recoveryAttemptKey      Optional opaque key for this recovery attempt.
     */
    data class ReconnectClassificationInput(
        val reconnectRecoveryState: String?,
        val localRecoveryDecision: LocalRecoveryDecision?,
        val durableSessionId: String?,
        val durableParticipantId: String?,
        val participantIdentityFreshness: String?,
        val sessionContinuityEpoch: Long?,
        val offlineQueueDepth: Int,
        val queueSessionTagMatchesCurrent: Boolean,
        val recoveryAttemptKey: String? = null
    )

    // ── classify ──────────────────────────────────────────────────────────────

    /**
     * Classifies the current reconnect/re-attach/attach context into a
     * [ReconnectParticipationSnapshot].
     *
     * This is the single canonical implementation of the PR-12 classification logic.
     * All three dimensions ([ReconnectParticipationKind], [IdentityReuseDecision],
     * [ReplayEligibility]) are derived from the same [input] bundle, ensuring consistency
     * between the three wire fields.
     *
     * @param input The [ReconnectClassificationInput] populated from live Android state.
     * @return A [ReconnectParticipationSnapshot] containing all three decisions.
     */
    fun classify(input: ReconnectClassificationInput): ReconnectParticipationSnapshot {
        val participationKind = classifyParticipationKind(input)
        val identityReuseDecision = classifyIdentityReuse(input)
        val replayEligibility = classifyReplayEligibility(input)

        return ReconnectParticipationSnapshot(
            participationKind = participationKind,
            identityReuseDecision = identityReuseDecision,
            replayEligibility = replayEligibility,
            durableSessionId = input.durableSessionId,
            durableParticipantId = input.durableParticipantId,
            sessionContinuityEpoch = input.sessionContinuityEpoch,
            recoveryAttemptKey = input.recoveryAttemptKey
        )
    }

    /**
     * Derives [ReconnectParticipationKind] from [input].
     *
     * Decision priority:
     *  1. If [LocalRecoveryDecision] is [LocalRecoveryDecision.NoRecoveryContext] →
     *     [ReconnectParticipationKind.FRESH_ATTACH].
     *  2. If reconnect recovery state is `"recovering"` or `"recovered"` and the local
     *     recovery decision is [LocalRecoveryDecision.WaitForV2ReplayDecision] with no
     *     continuity token → [ReconnectParticipationKind.TRANSPORT_RECONNECT].
     *  3. If local recovery decision is [LocalRecoveryDecision.RehydrateThenContinue] →
     *     [ReconnectParticipationKind.PROCESS_RECREATION_WITH_CONTEXT].
     *  4. If local recovery decision is [LocalRecoveryDecision.WaitForV2ReplayDecision]
     *     (with no continuity token, not a transport reconnect) →
     *     [ReconnectParticipationKind.PROCESS_RECREATION_WITHOUT_CONTEXT].
     *  5. Default → [ReconnectParticipationKind.FRESH_ATTACH].
     */
    internal fun classifyParticipationKind(input: ReconnectClassificationInput): ReconnectParticipationKind {
        val decision = input.localRecoveryDecision
        val wsState = input.reconnectRecoveryState

        return when {
            decision == null || decision is LocalRecoveryDecision.NoRecoveryContext ->
                ReconnectParticipationKind.FRESH_ATTACH

            decision is LocalRecoveryDecision.RehydrateThenContinue ->
                ReconnectParticipationKind.PROCESS_RECREATION_WITH_CONTEXT

            decision is LocalRecoveryDecision.WaitForV2ReplayDecision -> {
                val isTransport = wsState == ReconnectRecoveryState.RECOVERING.wireValue ||
                    wsState == ReconnectRecoveryState.RECOVERED.wireValue
                if (isTransport && decision.continuityToken == null) {
                    ReconnectParticipationKind.TRANSPORT_RECONNECT
                } else {
                    ReconnectParticipationKind.PROCESS_RECREATION_WITHOUT_CONTEXT
                }
            }

            decision is LocalRecoveryDecision.ResumeLocalExecution ->
                ReconnectParticipationKind.TRANSPORT_RECONNECT

            else -> ReconnectParticipationKind.FRESH_ATTACH
        }
    }

    /**
     * Derives [IdentityReuseDecision] from [input].
     *
     * Decision table:
     *  - Durable participant ID present AND freshness is "fresh" or "recovered" →
     *    [IdentityReuseDecision.REUSE_DURABLE_PARTICIPANT]
     *  - Durable session ID present but participant ID missing or stale →
     *    [IdentityReuseDecision.REUSE_SESSION_ONLY]
     *  - Neither → [IdentityReuseDecision.FRESH_IDENTITY]
     */
    internal fun classifyIdentityReuse(input: ReconnectClassificationInput): IdentityReuseDecision {
        val participantId = input.durableParticipantId
        val sessionId = input.durableSessionId
        val freshness = input.participantIdentityFreshness

        val participantFreshOrRecovered = freshness == DurableParticipantIdentity.IdentityFreshness.FRESH.wireValue ||
            freshness == DurableParticipantIdentity.IdentityFreshness.RECOVERED.wireValue

        return when {
            participantId != null && participantFreshOrRecovered ->
                IdentityReuseDecision.REUSE_DURABLE_PARTICIPANT

            sessionId != null ->
                IdentityReuseDecision.REUSE_SESSION_ONLY

            else ->
                IdentityReuseDecision.FRESH_IDENTITY
        }
    }

    /**
     * Derives [ReplayEligibility] from [input].
     *
     * Decision table:
     *  - [offlineQueueDepth] == 0 → [ReplayEligibility.QUEUE_EMPTY]
     *  - [offlineQueueDepth] > 0 AND [queueSessionTagMatchesCurrent] →
     *    [ReplayEligibility.ELIGIBLE_FOR_REPLAY]
     *  - [offlineQueueDepth] > 0 AND NOT [queueSessionTagMatchesCurrent] →
     *    [ReplayEligibility.STALE_SESSION_BLOCKED]
     */
    internal fun classifyReplayEligibility(input: ReconnectClassificationInput): ReplayEligibility {
        return when {
            input.offlineQueueDepth == 0 -> ReplayEligibility.QUEUE_EMPTY
            input.queueSessionTagMatchesCurrent -> ReplayEligibility.ELIGIBLE_FOR_REPLAY
            else -> ReplayEligibility.STALE_SESSION_BLOCKED
        }
    }

    // ── PARTICIPATION_INVARIANTS ──────────────────────────────────────────────

    /**
     * Machine-verifiable invariants for the PR-12 reconnect/recovery participation contract.
     *
     * All entries MUST be `true`.  Any `false` value indicates a regression.
     *
     * Verified by [TEST_CLASS].
     */
    val PARTICIPATION_INVARIANTS: Map<String, Boolean> = mapOf(
        // ReconnectParticipationKind: enum completeness
        "participation_kind_has_4_values" to
            (ReconnectParticipationKind.entries.size == 4),
        "participation_kind_wire_values_unique" to
            (ReconnectParticipationKind.ALL_WIRE_VALUES.size == ReconnectParticipationKind.entries.size),
        "participation_kind_fresh_attach_wire_value_stable" to
            (ReconnectParticipationKind.FRESH_ATTACH.wireValue == "fresh_attach"),
        "participation_kind_transport_reconnect_wire_value_stable" to
            (ReconnectParticipationKind.TRANSPORT_RECONNECT.wireValue == "transport_reconnect"),
        "participation_kind_process_recreation_with_context_wire_value_stable" to
            (ReconnectParticipationKind.PROCESS_RECREATION_WITH_CONTEXT.wireValue == "process_recreation_with_context"),
        "participation_kind_process_recreation_without_context_wire_value_stable" to
            (ReconnectParticipationKind.PROCESS_RECREATION_WITHOUT_CONTEXT.wireValue == "process_recreation_without_context"),

        // IdentityReuseDecision: enum completeness
        "identity_reuse_has_3_values" to
            (IdentityReuseDecision.entries.size == 3),
        "identity_reuse_wire_values_unique" to
            (IdentityReuseDecision.ALL_WIRE_VALUES.size == IdentityReuseDecision.entries.size),
        "identity_reuse_fresh_identity_wire_value_stable" to
            (IdentityReuseDecision.FRESH_IDENTITY.wireValue == "fresh_identity"),
        "identity_reuse_reuse_durable_participant_wire_value_stable" to
            (IdentityReuseDecision.REUSE_DURABLE_PARTICIPANT.wireValue == "reuse_durable_participant"),
        "identity_reuse_reuse_session_only_wire_value_stable" to
            (IdentityReuseDecision.REUSE_SESSION_ONLY.wireValue == "reuse_session_only"),

        // ReplayEligibility: enum completeness
        "replay_eligibility_has_3_values" to
            (ReplayEligibility.entries.size == 3),
        "replay_eligibility_wire_values_unique" to
            (ReplayEligibility.ALL_WIRE_VALUES.size == ReplayEligibility.entries.size),
        "replay_eligibility_queue_empty_wire_value_stable" to
            (ReplayEligibility.QUEUE_EMPTY.wireValue == "queue_empty"),
        "replay_eligibility_eligible_for_replay_wire_value_stable" to
            (ReplayEligibility.ELIGIBLE_FOR_REPLAY.wireValue == "eligible_for_replay"),
        "replay_eligibility_stale_session_blocked_wire_value_stable" to
            (ReplayEligibility.STALE_SESSION_BLOCKED.wireValue == "stale_session_blocked"),

        // ReplayEligibility: isReplayExpected semantics
        "only_eligible_for_replay_expects_replay" to
            (ReplayEligibility.entries.filter { it.isReplayExpected } ==
                listOf(ReplayEligibility.ELIGIBLE_FOR_REPLAY)),

        // Classifier: FreshAttach input → FRESH_ATTACH participation kind
        "classify_fresh_attach_decision_yields_fresh_attach_kind" to
            (classifyParticipationKind(
                ReconnectClassificationInput(
                    reconnectRecoveryState = ReconnectRecoveryState.IDLE.wireValue,
                    localRecoveryDecision = LocalRecoveryDecision.NoRecoveryContext,
                    durableSessionId = null,
                    durableParticipantId = null,
                    participantIdentityFreshness = null,
                    sessionContinuityEpoch = null,
                    offlineQueueDepth = 0,
                    queueSessionTagMatchesCurrent = false
                )
            ) == ReconnectParticipationKind.FRESH_ATTACH),

        // Classifier: TransportReconnect + WS recovering → TRANSPORT_RECONNECT kind
        "classify_transport_reconnect_state_yields_transport_reconnect_kind" to
            (classifyParticipationKind(
                ReconnectClassificationInput(
                    reconnectRecoveryState = ReconnectRecoveryState.RECOVERING.wireValue,
                    localRecoveryDecision = LocalRecoveryDecision.WaitForV2ReplayDecision(
                        continuityToken = null,
                        durableSessionId = "durable-session-1",
                        reason = "transport reconnect"
                    ),
                    durableSessionId = "durable-session-1",
                    durableParticipantId = "participant-1",
                    participantIdentityFreshness = DurableParticipantIdentity.IdentityFreshness.RECOVERED.wireValue,
                    sessionContinuityEpoch = 2L,
                    offlineQueueDepth = 0,
                    queueSessionTagMatchesCurrent = false
                )
            ) == ReconnectParticipationKind.TRANSPORT_RECONNECT),

        // Classifier: null input → FRESH_ATTACH
        "classify_null_decision_yields_fresh_attach" to
            (classifyParticipationKind(
                ReconnectClassificationInput(
                    reconnectRecoveryState = null,
                    localRecoveryDecision = null,
                    durableSessionId = null,
                    durableParticipantId = null,
                    participantIdentityFreshness = null,
                    sessionContinuityEpoch = null,
                    offlineQueueDepth = 0,
                    queueSessionTagMatchesCurrent = false
                )
            ) == ReconnectParticipationKind.FRESH_ATTACH),

        // Classifier: identity reuse — fresh participant → REUSE_DURABLE_PARTICIPANT
        "classify_fresh_participant_yields_reuse_durable_participant" to
            (classifyIdentityReuse(
                ReconnectClassificationInput(
                    reconnectRecoveryState = null,
                    localRecoveryDecision = null,
                    durableSessionId = "session-x",
                    durableParticipantId = "participant-x",
                    participantIdentityFreshness = DurableParticipantIdentity.IdentityFreshness.FRESH.wireValue,
                    sessionContinuityEpoch = 0L,
                    offlineQueueDepth = 0,
                    queueSessionTagMatchesCurrent = false
                )
            ) == IdentityReuseDecision.REUSE_DURABLE_PARTICIPANT),

        // Classifier: identity reuse — no participant, session only → REUSE_SESSION_ONLY
        "classify_no_participant_id_yields_reuse_session_only" to
            (classifyIdentityReuse(
                ReconnectClassificationInput(
                    reconnectRecoveryState = null,
                    localRecoveryDecision = null,
                    durableSessionId = "session-y",
                    durableParticipantId = null,
                    participantIdentityFreshness = null,
                    sessionContinuityEpoch = 0L,
                    offlineQueueDepth = 0,
                    queueSessionTagMatchesCurrent = false
                )
            ) == IdentityReuseDecision.REUSE_SESSION_ONLY),

        // Classifier: identity reuse — nothing → FRESH_IDENTITY
        "classify_no_identity_yields_fresh_identity" to
            (classifyIdentityReuse(
                ReconnectClassificationInput(
                    reconnectRecoveryState = null,
                    localRecoveryDecision = null,
                    durableSessionId = null,
                    durableParticipantId = null,
                    participantIdentityFreshness = null,
                    sessionContinuityEpoch = null,
                    offlineQueueDepth = 0,
                    queueSessionTagMatchesCurrent = false
                )
            ) == IdentityReuseDecision.FRESH_IDENTITY),

        // Classifier: replay eligibility — empty queue → QUEUE_EMPTY
        "classify_empty_queue_yields_queue_empty" to
            (classifyReplayEligibility(
                ReconnectClassificationInput(
                    reconnectRecoveryState = null,
                    localRecoveryDecision = null,
                    durableSessionId = null,
                    durableParticipantId = null,
                    participantIdentityFreshness = null,
                    sessionContinuityEpoch = null,
                    offlineQueueDepth = 0,
                    queueSessionTagMatchesCurrent = false
                )
            ) == ReplayEligibility.QUEUE_EMPTY),

        // Classifier: replay eligibility — non-empty queue matching session → ELIGIBLE
        "classify_matching_queue_yields_eligible_for_replay" to
            (classifyReplayEligibility(
                ReconnectClassificationInput(
                    reconnectRecoveryState = null,
                    localRecoveryDecision = null,
                    durableSessionId = "session-z",
                    durableParticipantId = null,
                    participantIdentityFreshness = null,
                    sessionContinuityEpoch = null,
                    offlineQueueDepth = 5,
                    queueSessionTagMatchesCurrent = true
                )
            ) == ReplayEligibility.ELIGIBLE_FOR_REPLAY),

        // Classifier: replay eligibility — non-empty queue stale session → STALE_SESSION_BLOCKED
        "classify_stale_queue_yields_stale_session_blocked" to
            (classifyReplayEligibility(
                ReconnectClassificationInput(
                    reconnectRecoveryState = null,
                    localRecoveryDecision = null,
                    durableSessionId = "session-z",
                    durableParticipantId = null,
                    participantIdentityFreshness = null,
                    sessionContinuityEpoch = null,
                    offlineQueueDepth = 3,
                    queueSessionTagMatchesCurrent = false
                )
            ) == ReplayEligibility.STALE_SESSION_BLOCKED)
    )

    // ── buildContractWireMap ──────────────────────────────────────────────────

    /**
     * Builds a machine-consumable wire map of this contract for V2 consumption
     * and dual-repo audit artifacts.
     *
     * Suitable for structured log emission and cross-repo recovery audit artifacts.
     */
    fun buildContractWireMap(): Map<String, Any> = mapOf(
        "schema_version" to SCHEMA_VERSION,
        "introduced_pr" to INTRODUCED_PR,
        "test_class" to TEST_CLASS,
        "participation_kind_wire_values" to ReconnectParticipationKind.ALL_WIRE_VALUES.sorted(),
        "identity_reuse_wire_values" to IdentityReuseDecision.ALL_WIRE_VALUES.sorted(),
        "replay_eligibility_wire_values" to ReplayEligibility.ALL_WIRE_VALUES.sorted(),
        "wire_fields" to listOf(
            "reconnect_participation_kind",
            "identity_reuse_decision",
            "replay_eligibility"
        ),
        "participation_invariants_count" to PARTICIPATION_INVARIANTS.size,
        "all_participation_invariants_pass" to PARTICIPATION_INVARIANTS.values.all { it }
    )
}
