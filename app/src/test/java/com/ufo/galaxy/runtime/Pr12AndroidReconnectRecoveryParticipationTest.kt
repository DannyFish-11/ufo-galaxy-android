package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import com.ufo.galaxy.session.DurableParticipantIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-12 — Regression-protection tests for Android real reconnect and recovery participation.
 *
 * Validates all deliverables of PR-12 (Android reconnect/recovery participation contract):
 *
 * 1. [AndroidReconnectRecoveryParticipationContract] — all three enums are well-formed,
 *    wire values are stable, classifier methods produce correct outputs.
 * 2. [DeviceStateSnapshotPayload] — new wire fields are present and default null.
 * 3. Classifier semantics — classifyParticipationKind / classifyIdentityReuse /
 *    classifyReplayEligibility produce correct outputs for every scenario.
 * 4. PARTICIPATION_INVARIANTS — all invariants pass.
 * 5. [StabilizationBaseline] — "android-reconnect-recovery-participation" is registered
 *    as CANONICAL_STABLE.
 * 6. Gson round-trip stability for the three new wire fields.
 * 7. buildContractWireMap — contains expected keys and the invariants-pass assertion.
 * 8. ReconnectParticipationSnapshot.toWireMap — contains all expected keys.
 *
 * ## Coverage areas
 *
 * ### ReconnectParticipationKind — enum coverage
 *  - has exactly 4 values
 *  - all wire values are stable
 *  - wire values match expected set
 *  - fromWireValue round-trips for all values
 *
 * ### IdentityReuseDecision — enum coverage
 *  - has exactly 3 values
 *  - all wire values are stable
 *  - wire values match expected set
 *  - fromWireValue round-trips for all values
 *
 * ### ReplayEligibility — enum coverage
 *  - has exactly 3 values
 *  - all wire values are stable
 *  - wire values match expected set
 *  - only ELIGIBLE_FOR_REPLAY has isReplayExpected=true
 *  - fromWireValue round-trips for all values
 *
 * ### classifyParticipationKind — classifier correctness
 *  - null decision → FRESH_ATTACH
 *  - NoRecoveryContext → FRESH_ATTACH
 *  - RehydrateThenContinue → PROCESS_RECREATION_WITH_CONTEXT
 *  - WaitForV2ReplayDecision + recovering WS state → TRANSPORT_RECONNECT
 *  - WaitForV2ReplayDecision + recovered WS state → TRANSPORT_RECONNECT
 *  - WaitForV2ReplayDecision + idle WS state → PROCESS_RECREATION_WITHOUT_CONTEXT
 *  - ResumeLocalExecution → TRANSPORT_RECONNECT
 *  - SuppressDuplicateLocalRecovery → FRESH_ATTACH (default)
 *
 * ### classifyIdentityReuse — classifier correctness
 *  - fresh participantId + fresh freshness → REUSE_DURABLE_PARTICIPANT
 *  - fresh participantId + recovered freshness → REUSE_DURABLE_PARTICIPANT
 *  - fresh participantId + stale freshness → REUSE_SESSION_ONLY (participant not fresh)
 *  - null participantId + valid sessionId → REUSE_SESSION_ONLY
 *  - null participantId + null sessionId → FRESH_IDENTITY
 *
 * ### classifyReplayEligibility — classifier correctness
 *  - depth 0 → QUEUE_EMPTY
 *  - depth > 0 + queueSessionTagMatchesCurrent=true → ELIGIBLE_FOR_REPLAY
 *  - depth > 0 + queueSessionTagMatchesCurrent=false → STALE_SESSION_BLOCKED
 *
 * ### Wire field presence
 *  - DeviceStateSnapshotPayload.reconnect_participation_kind defaults null
 *  - DeviceStateSnapshotPayload.identity_reuse_decision defaults null
 *  - DeviceStateSnapshotPayload.replay_eligibility defaults null
 *  - All three fields can be set explicitly
 *  - All three fields are serialised by Gson
 *
 * ### PARTICIPATION_INVARIANTS
 *  - All invariants pass
 *
 * ### StabilizationBaseline
 *  - "android-reconnect-recovery-participation" is registered as CANONICAL_STABLE
 */
class Pr12AndroidReconnectRecoveryParticipationTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeInput(
        reconnectRecoveryState: String? = null,
        localRecoveryDecision: LocalRecoveryDecision? = null,
        durableSessionId: String? = null,
        durableParticipantId: String? = null,
        participantIdentityFreshness: String? = null,
        sessionContinuityEpoch: Long? = null,
        offlineQueueDepth: Int = 0,
        queueSessionTagMatchesCurrent: Boolean = false,
        recoveryAttemptKey: String? = null
    ) = AndroidReconnectRecoveryParticipationContract.ReconnectClassificationInput(
        reconnectRecoveryState = reconnectRecoveryState,
        localRecoveryDecision = localRecoveryDecision,
        durableSessionId = durableSessionId,
        durableParticipantId = durableParticipantId,
        participantIdentityFreshness = participantIdentityFreshness,
        sessionContinuityEpoch = sessionContinuityEpoch,
        offlineQueueDepth = offlineQueueDepth,
        queueSessionTagMatchesCurrent = queueSessionTagMatchesCurrent,
        recoveryAttemptKey = recoveryAttemptKey
    )

    private fun waitForV2Decision(
        continuityToken: String? = null,
        durableSessionId: String? = "durable-1",
        reason: String = "test reason"
    ) = LocalRecoveryDecision.WaitForV2ReplayDecision(
        continuityToken = continuityToken,
        durableSessionId = durableSessionId,
        reason = reason
    )

    private fun resumeLocalDecision(
        activeSessionId: String = "session-1"
    ) = LocalRecoveryDecision.ResumeLocalExecution(activeSessionId = activeSessionId)

    private fun rehydrateDecision(): LocalRecoveryDecision.RehydrateThenContinue {
        val snapshot = LocalExecutionContextSnapshot(
            flowId = "flow-1",
            flowLineageId = "lineage-1",
            unitId = "unit-1",
            taskId = "task-1",
            traceId = "trace-1",
            durableSessionId = "durable-session-2",
            attachedSessionId = "session-2",
            lastKnownPhase = AndroidFlowExecutionPhase.RECEIVED,
            continuityToken = "token-1",
            continuationToken = null,
            stepCount = 3,
            lastStepAtMs = 5_000L,
            rehydratedAtMs = 10_000L
        )
        val checkpoint = RecoveryActivationCheckpoint(
            flowId = "flow-1",
            attachedSessionId = "session-2",
            durableSessionId = "durable-session-2",
            lastKnownPhase = AndroidFlowExecutionPhase.RECEIVED,
            continuityToken = "token-1",
            stepCount = 3
        )
        return LocalRecoveryDecision.RehydrateThenContinue(
            localContext = snapshot,
            checkpoint = checkpoint
        )
    }

    private fun suppressDecision() = LocalRecoveryDecision.SuppressDuplicateLocalRecovery(
        attemptKey = "attempt-1"
    )

    private fun minimalSnapshotPayload(
        reconnectParticipationKind: String? = null,
        identityReuseDecision: String? = null,
        replayEligibility: String? = null
    ) = DeviceStateSnapshotPayload(
        device_id = "test-device",
        llama_cpp_available = false,
        ncnn_available = false,
        active_runtime_type = "CENTER",
        model_ready = false,
        accessibility_ready = false,
        overlay_ready = false,
        local_loop_ready = false,
        model_id = null,
        runtime_type = null,
        checksum_ok = null,
        mobilevlm_present = false,
        mobilevlm_checksum_ok = false,
        seeclick_present = false,
        pending_first_download = true,
        warmup_result = "unavailable",
        offline_queue_depth = 0,
        current_fallback_tier = null,
        reconnect_participation_kind = reconnectParticipationKind,
        identity_reuse_decision = identityReuseDecision,
        replay_eligibility = replayEligibility
    )

    // ── ReconnectParticipationKind — enum coverage ────────────────────────────

    @Test
    fun `ReconnectParticipationKind has exactly 4 values`() {
        assertEquals(
            4,
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.entries.size
        )
    }

    @Test
    fun `ReconnectParticipationKind ALL_WIRE_VALUES has size 4`() {
        assertEquals(
            4,
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.ALL_WIRE_VALUES.size
        )
    }

    @Test
    fun `ReconnectParticipationKind wire values match expected set`() {
        val expected = setOf(
            "fresh_attach",
            "transport_reconnect",
            "process_recreation_with_context",
            "process_recreation_without_context"
        )
        assertEquals(
            expected,
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.ALL_WIRE_VALUES
        )
    }

    @Test
    fun `FRESH_ATTACH wireValue is fresh_attach`() {
        assertEquals(
            "fresh_attach",
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.FRESH_ATTACH.wireValue
        )
    }

    @Test
    fun `TRANSPORT_RECONNECT wireValue is transport_reconnect`() {
        assertEquals(
            "transport_reconnect",
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.TRANSPORT_RECONNECT.wireValue
        )
    }

    @Test
    fun `PROCESS_RECREATION_WITH_CONTEXT wireValue is process_recreation_with_context`() {
        assertEquals(
            "process_recreation_with_context",
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.PROCESS_RECREATION_WITH_CONTEXT.wireValue
        )
    }

    @Test
    fun `PROCESS_RECREATION_WITHOUT_CONTEXT wireValue is process_recreation_without_context`() {
        assertEquals(
            "process_recreation_without_context",
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.PROCESS_RECREATION_WITHOUT_CONTEXT.wireValue
        )
    }

    @Test
    fun `ReconnectParticipationKind fromWireValue round-trips for all entries`() {
        for (kind in AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.entries) {
            assertEquals(
                kind,
                AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.fromWireValue(kind.wireValue)
            )
        }
    }

    @Test
    fun `ReconnectParticipationKind fromWireValue returns null for unknown value`() {
        assertNull(
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.fromWireValue("not_a_real_kind")
        )
    }

    @Test
    fun `all ReconnectParticipationKind values have non-empty v2RecoveryPath`() {
        for (kind in AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.entries) {
            assertTrue(
                "${kind.name} must have non-empty v2RecoveryPath",
                kind.v2RecoveryPath.isNotEmpty()
            )
        }
    }

    // ── IdentityReuseDecision — enum coverage ─────────────────────────────────

    @Test
    fun `IdentityReuseDecision has exactly 3 values`() {
        assertEquals(
            3,
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.entries.size
        )
    }

    @Test
    fun `IdentityReuseDecision ALL_WIRE_VALUES has size 3`() {
        assertEquals(
            3,
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.ALL_WIRE_VALUES.size
        )
    }

    @Test
    fun `IdentityReuseDecision wire values match expected set`() {
        val expected = setOf(
            "reuse_durable_participant",
            "reuse_session_only",
            "fresh_identity"
        )
        assertEquals(
            expected,
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.ALL_WIRE_VALUES
        )
    }

    @Test
    fun `REUSE_DURABLE_PARTICIPANT wireValue is reuse_durable_participant`() {
        assertEquals(
            "reuse_durable_participant",
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.REUSE_DURABLE_PARTICIPANT.wireValue
        )
    }

    @Test
    fun `REUSE_SESSION_ONLY wireValue is reuse_session_only`() {
        assertEquals(
            "reuse_session_only",
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.REUSE_SESSION_ONLY.wireValue
        )
    }

    @Test
    fun `FRESH_IDENTITY wireValue is fresh_identity`() {
        assertEquals(
            "fresh_identity",
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.FRESH_IDENTITY.wireValue
        )
    }

    @Test
    fun `IdentityReuseDecision fromWireValue round-trips for all entries`() {
        for (decision in AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.entries) {
            assertEquals(
                decision,
                AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.fromWireValue(decision.wireValue)
            )
        }
    }

    @Test
    fun `IdentityReuseDecision fromWireValue returns null for unknown value`() {
        assertNull(
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.fromWireValue("unknown_decision")
        )
    }

    @Test
    fun `all IdentityReuseDecision values have non-empty v2ParticipantAction`() {
        for (decision in AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.entries) {
            assertTrue(
                "${decision.name} must have non-empty v2ParticipantAction",
                decision.v2ParticipantAction.isNotEmpty()
            )
        }
    }

    // ── ReplayEligibility — enum coverage ─────────────────────────────────────

    @Test
    fun `ReplayEligibility has exactly 3 values`() {
        assertEquals(
            3,
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.entries.size
        )
    }

    @Test
    fun `ReplayEligibility ALL_WIRE_VALUES has size 3`() {
        assertEquals(
            3,
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.ALL_WIRE_VALUES.size
        )
    }

    @Test
    fun `ReplayEligibility wire values match expected set`() {
        val expected = setOf("eligible_for_replay", "stale_session_blocked", "queue_empty")
        assertEquals(
            expected,
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.ALL_WIRE_VALUES
        )
    }

    @Test
    fun `ELIGIBLE_FOR_REPLAY wireValue is eligible_for_replay`() {
        assertEquals(
            "eligible_for_replay",
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.ELIGIBLE_FOR_REPLAY.wireValue
        )
    }

    @Test
    fun `STALE_SESSION_BLOCKED wireValue is stale_session_blocked`() {
        assertEquals(
            "stale_session_blocked",
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.STALE_SESSION_BLOCKED.wireValue
        )
    }

    @Test
    fun `QUEUE_EMPTY wireValue is queue_empty`() {
        assertEquals(
            "queue_empty",
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.QUEUE_EMPTY.wireValue
        )
    }

    @Test
    fun `only ELIGIBLE_FOR_REPLAY has isReplayExpected true`() {
        val replayExpected = AndroidReconnectRecoveryParticipationContract.ReplayEligibility.entries
            .filter { it.isReplayExpected }
        assertEquals(1, replayExpected.size)
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.ELIGIBLE_FOR_REPLAY,
            replayExpected.first()
        )
    }

    @Test
    fun `STALE_SESSION_BLOCKED isReplayExpected is false`() {
        assertFalse(
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.STALE_SESSION_BLOCKED.isReplayExpected
        )
    }

    @Test
    fun `QUEUE_EMPTY isReplayExpected is false`() {
        assertFalse(
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.QUEUE_EMPTY.isReplayExpected
        )
    }

    @Test
    fun `ReplayEligibility fromWireValue round-trips for all entries`() {
        for (eligibility in AndroidReconnectRecoveryParticipationContract.ReplayEligibility.entries) {
            assertEquals(
                eligibility,
                AndroidReconnectRecoveryParticipationContract.ReplayEligibility.fromWireValue(eligibility.wireValue)
            )
        }
    }

    @Test
    fun `ReplayEligibility fromWireValue returns null for unknown value`() {
        assertNull(
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.fromWireValue("not_valid")
        )
    }

    // ── classifyParticipationKind — classifier correctness ────────────────────

    @Test
    fun `null localRecoveryDecision yields FRESH_ATTACH`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyParticipationKind(
            makeInput(localRecoveryDecision = null)
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.FRESH_ATTACH,
            result
        )
    }

    @Test
    fun `NoRecoveryContext yields FRESH_ATTACH`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyParticipationKind(
            makeInput(localRecoveryDecision = LocalRecoveryDecision.NoRecoveryContext)
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.FRESH_ATTACH,
            result
        )
    }

    @Test
    fun `RehydrateThenContinue yields PROCESS_RECREATION_WITH_CONTEXT`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyParticipationKind(
            makeInput(localRecoveryDecision = rehydrateDecision())
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.PROCESS_RECREATION_WITH_CONTEXT,
            result
        )
    }

    @Test
    fun `WaitForV2ReplayDecision with WS state recovering and no continuity token yields TRANSPORT_RECONNECT`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyParticipationKind(
            makeInput(
                reconnectRecoveryState = ReconnectRecoveryState.RECOVERING.wireValue,
                localRecoveryDecision = waitForV2Decision(continuityToken = null)
            )
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.TRANSPORT_RECONNECT,
            result
        )
    }

    @Test
    fun `WaitForV2ReplayDecision with WS state recovered and no continuity token yields TRANSPORT_RECONNECT`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyParticipationKind(
            makeInput(
                reconnectRecoveryState = ReconnectRecoveryState.RECOVERED.wireValue,
                localRecoveryDecision = waitForV2Decision(continuityToken = null)
            )
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.TRANSPORT_RECONNECT,
            result
        )
    }

    @Test
    fun `WaitForV2ReplayDecision with WS state idle yields PROCESS_RECREATION_WITHOUT_CONTEXT`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyParticipationKind(
            makeInput(
                reconnectRecoveryState = ReconnectRecoveryState.IDLE.wireValue,
                localRecoveryDecision = waitForV2Decision(continuityToken = null)
            )
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.PROCESS_RECREATION_WITHOUT_CONTEXT,
            result
        )
    }

    @Test
    fun `WaitForV2ReplayDecision with null WS state yields PROCESS_RECREATION_WITHOUT_CONTEXT`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyParticipationKind(
            makeInput(
                reconnectRecoveryState = null,
                localRecoveryDecision = waitForV2Decision(continuityToken = null)
            )
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.PROCESS_RECREATION_WITHOUT_CONTEXT,
            result
        )
    }

    @Test
    fun `ResumeLocalExecution yields TRANSPORT_RECONNECT`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyParticipationKind(
            makeInput(localRecoveryDecision = resumeLocalDecision())
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.TRANSPORT_RECONNECT,
            result
        )
    }

    @Test
    fun `SuppressDuplicateLocalRecovery yields FRESH_ATTACH as default`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyParticipationKind(
            makeInput(localRecoveryDecision = suppressDecision())
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.FRESH_ATTACH,
            result
        )
    }

    // ── classifyIdentityReuse — classifier correctness ────────────────────────

    @Test
    fun `fresh participant id with fresh freshness yields REUSE_DURABLE_PARTICIPANT`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyIdentityReuse(
            makeInput(
                durableSessionId = "session-1",
                durableParticipantId = "participant-1",
                participantIdentityFreshness = DurableParticipantIdentity.IdentityFreshness.FRESH.wireValue
            )
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.REUSE_DURABLE_PARTICIPANT,
            result
        )
    }

    @Test
    fun `participant id with recovered freshness yields REUSE_DURABLE_PARTICIPANT`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyIdentityReuse(
            makeInput(
                durableSessionId = "session-2",
                durableParticipantId = "participant-2",
                participantIdentityFreshness = DurableParticipantIdentity.IdentityFreshness.RECOVERED.wireValue
            )
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.REUSE_DURABLE_PARTICIPANT,
            result
        )
    }

    @Test
    fun `participant id with stale freshness yields REUSE_SESSION_ONLY when session present`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyIdentityReuse(
            makeInput(
                durableSessionId = "session-3",
                durableParticipantId = "participant-3",
                participantIdentityFreshness = DurableParticipantIdentity.IdentityFreshness.STALE.wireValue
            )
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.REUSE_SESSION_ONLY,
            result
        )
    }

    @Test
    fun `null participant id with valid session id yields REUSE_SESSION_ONLY`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyIdentityReuse(
            makeInput(
                durableSessionId = "session-4",
                durableParticipantId = null,
                participantIdentityFreshness = null
            )
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.REUSE_SESSION_ONLY,
            result
        )
    }

    @Test
    fun `null participant id and null session id yields FRESH_IDENTITY`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyIdentityReuse(
            makeInput(
                durableSessionId = null,
                durableParticipantId = null,
                participantIdentityFreshness = null
            )
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.FRESH_IDENTITY,
            result
        )
    }

    @Test
    fun `participant id with null freshness and no session yields FRESH_IDENTITY`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyIdentityReuse(
            makeInput(
                durableSessionId = null,
                durableParticipantId = "participant-5",
                participantIdentityFreshness = null
            )
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.FRESH_IDENTITY,
            result
        )
    }

    // ── classifyReplayEligibility — classifier correctness ────────────────────

    @Test
    fun `empty queue yields QUEUE_EMPTY`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyReplayEligibility(
            makeInput(offlineQueueDepth = 0, queueSessionTagMatchesCurrent = false)
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.QUEUE_EMPTY,
            result
        )
    }

    @Test
    fun `empty queue with session match still yields QUEUE_EMPTY`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyReplayEligibility(
            makeInput(offlineQueueDepth = 0, queueSessionTagMatchesCurrent = true)
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.QUEUE_EMPTY,
            result
        )
    }

    @Test
    fun `non-empty queue with matching session yields ELIGIBLE_FOR_REPLAY`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyReplayEligibility(
            makeInput(offlineQueueDepth = 5, queueSessionTagMatchesCurrent = true)
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.ELIGIBLE_FOR_REPLAY,
            result
        )
    }

    @Test
    fun `non-empty queue with stale session yields STALE_SESSION_BLOCKED`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyReplayEligibility(
            makeInput(offlineQueueDepth = 3, queueSessionTagMatchesCurrent = false)
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.STALE_SESSION_BLOCKED,
            result
        )
    }

    @Test
    fun `single queued message with matching session yields ELIGIBLE_FOR_REPLAY`() {
        val result = AndroidReconnectRecoveryParticipationContract.classifyReplayEligibility(
            makeInput(offlineQueueDepth = 1, queueSessionTagMatchesCurrent = true)
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.ELIGIBLE_FOR_REPLAY,
            result
        )
    }

    // ── classify — end-to-end snapshot production ─────────────────────────────

    @Test
    fun `classify produces ReconnectParticipationSnapshot with all three decisions`() {
        val input = makeInput(
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERING.wireValue,
            localRecoveryDecision = waitForV2Decision(continuityToken = null),
            durableSessionId = "session-a",
            durableParticipantId = "participant-a",
            participantIdentityFreshness = DurableParticipantIdentity.IdentityFreshness.RECOVERED.wireValue,
            sessionContinuityEpoch = 3L,
            offlineQueueDepth = 2,
            queueSessionTagMatchesCurrent = true
        )
        val snapshot = AndroidReconnectRecoveryParticipationContract.classify(input)
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.TRANSPORT_RECONNECT,
            snapshot.participationKind
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.REUSE_DURABLE_PARTICIPANT,
            snapshot.identityReuseDecision
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.ELIGIBLE_FOR_REPLAY,
            snapshot.replayEligibility
        )
        assertEquals("session-a", snapshot.durableSessionId)
        assertEquals("participant-a", snapshot.durableParticipantId)
        assertEquals(3L, snapshot.sessionContinuityEpoch)
    }

    @Test
    fun `classify fresh attach scenario returns FRESH_ATTACH FRESH_IDENTITY QUEUE_EMPTY`() {
        val input = makeInput(
            reconnectRecoveryState = ReconnectRecoveryState.IDLE.wireValue,
            localRecoveryDecision = LocalRecoveryDecision.NoRecoveryContext,
            durableSessionId = null,
            durableParticipantId = null,
            participantIdentityFreshness = null,
            sessionContinuityEpoch = null,
            offlineQueueDepth = 0,
            queueSessionTagMatchesCurrent = false
        )
        val snapshot = AndroidReconnectRecoveryParticipationContract.classify(input)
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.FRESH_ATTACH,
            snapshot.participationKind
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.FRESH_IDENTITY,
            snapshot.identityReuseDecision
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.QUEUE_EMPTY,
            snapshot.replayEligibility
        )
    }

    @Test
    fun `classify process recreation with context and stale queue`() {
        val input = makeInput(
            reconnectRecoveryState = ReconnectRecoveryState.IDLE.wireValue,
            localRecoveryDecision = rehydrateDecision(),
            durableSessionId = "session-b",
            durableParticipantId = null,
            participantIdentityFreshness = null,
            sessionContinuityEpoch = 0L,
            offlineQueueDepth = 4,
            queueSessionTagMatchesCurrent = false
        )
        val snapshot = AndroidReconnectRecoveryParticipationContract.classify(input)
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind.PROCESS_RECREATION_WITH_CONTEXT,
            snapshot.participationKind
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision.REUSE_SESSION_ONLY,
            snapshot.identityReuseDecision
        )
        assertEquals(
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.STALE_SESSION_BLOCKED,
            snapshot.replayEligibility
        )
    }

    @Test
    fun `classify propagates recoveryAttemptKey into snapshot`() {
        val input = makeInput(
            recoveryAttemptKey = "attempt-key-42",
            localRecoveryDecision = null
        )
        val snapshot = AndroidReconnectRecoveryParticipationContract.classify(input)
        assertEquals("attempt-key-42", snapshot.recoveryAttemptKey)
    }

    // ── ReconnectParticipationSnapshot.toWireMap ──────────────────────────────

    @Test
    fun `toWireMap contains reconnect_participation_kind key`() {
        val snapshot = AndroidReconnectRecoveryParticipationContract.classify(makeInput())
        val map = snapshot.toWireMap()
        assertTrue(map.containsKey("reconnect_participation_kind"))
    }

    @Test
    fun `toWireMap contains identity_reuse_decision key`() {
        val snapshot = AndroidReconnectRecoveryParticipationContract.classify(makeInput())
        val map = snapshot.toWireMap()
        assertTrue(map.containsKey("identity_reuse_decision"))
    }

    @Test
    fun `toWireMap contains replay_eligibility key`() {
        val snapshot = AndroidReconnectRecoveryParticipationContract.classify(makeInput())
        val map = snapshot.toWireMap()
        assertTrue(map.containsKey("replay_eligibility"))
    }

    @Test
    fun `toWireMap contains durable_session_id key`() {
        val snapshot = AndroidReconnectRecoveryParticipationContract.classify(makeInput(durableSessionId = "s-1"))
        val map = snapshot.toWireMap()
        assertTrue(map.containsKey("durable_session_id"))
        assertEquals("s-1", map["durable_session_id"])
    }

    @Test
    fun `toWireMap contains durable_participant_id key`() {
        val snapshot = AndroidReconnectRecoveryParticipationContract.classify(
            makeInput(durableParticipantId = "p-1", durableSessionId = "s-1",
                participantIdentityFreshness = DurableParticipantIdentity.IdentityFreshness.FRESH.wireValue)
        )
        val map = snapshot.toWireMap()
        assertTrue(map.containsKey("durable_participant_id"))
        assertEquals("p-1", map["durable_participant_id"])
    }

    @Test
    fun `toWireMap contains session_continuity_epoch key`() {
        val snapshot = AndroidReconnectRecoveryParticipationContract.classify(
            makeInput(sessionContinuityEpoch = 7L)
        )
        val map = snapshot.toWireMap()
        assertTrue(map.containsKey("session_continuity_epoch"))
        assertEquals(7L, map["session_continuity_epoch"])
    }

    @Test
    fun `toWireMap wire value for TRANSPORT_RECONNECT is transport_reconnect`() {
        val input = makeInput(
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERING.wireValue,
            localRecoveryDecision = resumeLocalDecision()
        )
        val snapshot = AndroidReconnectRecoveryParticipationContract.classify(input)
        val map = snapshot.toWireMap()
        assertEquals("transport_reconnect", map["reconnect_participation_kind"])
    }

    // ── Wire field presence in DeviceStateSnapshotPayload ────────────────────

    @Test
    fun `DeviceStateSnapshotPayload reconnect_participation_kind defaults to null`() {
        val payload = minimalSnapshotPayload()
        assertNull(
            "reconnect_participation_kind must default to null; explicit population is required at emission",
            payload.reconnect_participation_kind
        )
    }

    @Test
    fun `DeviceStateSnapshotPayload identity_reuse_decision defaults to null`() {
        val payload = minimalSnapshotPayload()
        assertNull(
            "identity_reuse_decision must default to null; explicit population is required at emission",
            payload.identity_reuse_decision
        )
    }

    @Test
    fun `DeviceStateSnapshotPayload replay_eligibility defaults to null`() {
        val payload = minimalSnapshotPayload()
        assertNull(
            "replay_eligibility must default to null; explicit population is required at emission",
            payload.replay_eligibility
        )
    }

    @Test
    fun `DeviceStateSnapshotPayload reconnect_participation_kind can be set to transport_reconnect`() {
        val payload = minimalSnapshotPayload(reconnectParticipationKind = "transport_reconnect")
        assertEquals("transport_reconnect", payload.reconnect_participation_kind)
    }

    @Test
    fun `DeviceStateSnapshotPayload identity_reuse_decision can be set to reuse_durable_participant`() {
        val payload = minimalSnapshotPayload(identityReuseDecision = "reuse_durable_participant")
        assertEquals("reuse_durable_participant", payload.identity_reuse_decision)
    }

    @Test
    fun `DeviceStateSnapshotPayload replay_eligibility can be set to eligible_for_replay`() {
        val payload = minimalSnapshotPayload(replayEligibility = "eligible_for_replay")
        assertEquals("eligible_for_replay", payload.replay_eligibility)
    }

    @Test
    fun `DeviceStateSnapshotPayload reconnect_participation_kind can be set to fresh_attach`() {
        val payload = minimalSnapshotPayload(reconnectParticipationKind = "fresh_attach")
        assertEquals("fresh_attach", payload.reconnect_participation_kind)
    }

    @Test
    fun `DeviceStateSnapshotPayload identity_reuse_decision can be set to fresh_identity`() {
        val payload = minimalSnapshotPayload(identityReuseDecision = "fresh_identity")
        assertEquals("fresh_identity", payload.identity_reuse_decision)
    }

    @Test
    fun `DeviceStateSnapshotPayload replay_eligibility can be set to stale_session_blocked`() {
        val payload = minimalSnapshotPayload(replayEligibility = "stale_session_blocked")
        assertEquals("stale_session_blocked", payload.replay_eligibility)
    }

    // ── Gson round-trip stability ─────────────────────────────────────────────

    @Test
    fun `DeviceStateSnapshotPayload reconnect_participation_kind is serialised by Gson`() {
        val gson = Gson()
        val payload = minimalSnapshotPayload(reconnectParticipationKind = "transport_reconnect")
        val json = gson.toJson(payload)
        val parsed = gson.fromJson(json, JsonObject::class.java)
        assertTrue(
            "reconnect_participation_kind must be present in serialised JSON",
            parsed.has("reconnect_participation_kind")
        )
        assertEquals("transport_reconnect", parsed.get("reconnect_participation_kind").asString)
    }

    @Test
    fun `DeviceStateSnapshotPayload identity_reuse_decision is serialised by Gson`() {
        val gson = Gson()
        val payload = minimalSnapshotPayload(identityReuseDecision = "reuse_durable_participant")
        val json = gson.toJson(payload)
        val parsed = gson.fromJson(json, JsonObject::class.java)
        assertTrue(
            "identity_reuse_decision must be present in serialised JSON",
            parsed.has("identity_reuse_decision")
        )
        assertEquals("reuse_durable_participant", parsed.get("identity_reuse_decision").asString)
    }

    @Test
    fun `DeviceStateSnapshotPayload replay_eligibility is serialised by Gson`() {
        val gson = Gson()
        val payload = minimalSnapshotPayload(replayEligibility = "eligible_for_replay")
        val json = gson.toJson(payload)
        val parsed = gson.fromJson(json, JsonObject::class.java)
        assertTrue(
            "replay_eligibility must be present in serialised JSON",
            parsed.has("replay_eligibility")
        )
        assertEquals("eligible_for_replay", parsed.get("replay_eligibility").asString)
    }

    @Test
    fun `DeviceStateSnapshotPayload null reconnect_participation_kind serialises as null`() {
        val gson = Gson()
        val payload = minimalSnapshotPayload()
        val json = gson.toJson(payload)
        val parsed = gson.fromJson(json, JsonObject::class.java)
        // Gson omits null fields by default; the field should not be present or be null
        val element = parsed.get("reconnect_participation_kind")
        assertTrue(
            "null reconnect_participation_kind must serialise as absent or null",
            element == null || element.isJsonNull
        )
    }

    @Test
    fun `all three new wire fields round-trip correctly together`() {
        val gson = Gson()
        val payload = minimalSnapshotPayload(
            reconnectParticipationKind = "process_recreation_without_context",
            identityReuseDecision = "reuse_session_only",
            replayEligibility = "stale_session_blocked"
        )
        val json = gson.toJson(payload)
        val restored = gson.fromJson(json, DeviceStateSnapshotPayload::class.java)
        assertEquals("process_recreation_without_context", restored.reconnect_participation_kind)
        assertEquals("reuse_session_only", restored.identity_reuse_decision)
        assertEquals("stale_session_blocked", restored.replay_eligibility)
    }

    // ── PARTICIPATION_INVARIANTS ──────────────────────────────────────────────

    @Test
    fun `all PARTICIPATION_INVARIANTS pass`() {
        val failing = AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS
            .filter { !it.value }
        assertTrue(
            "PARTICIPATION_INVARIANTS with failing assertions: ${failing.keys}",
            failing.isEmpty()
        )
    }

    @Test
    fun `PARTICIPATION_INVARIANT participation_kind_has_4_values passes`() {
        assertTrue(
            AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS["participation_kind_has_4_values"] == true
        )
    }

    @Test
    fun `PARTICIPATION_INVARIANT identity_reuse_has_3_values passes`() {
        assertTrue(
            AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS["identity_reuse_has_3_values"] == true
        )
    }

    @Test
    fun `PARTICIPATION_INVARIANT replay_eligibility_has_3_values passes`() {
        assertTrue(
            AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS["replay_eligibility_has_3_values"] == true
        )
    }

    @Test
    fun `PARTICIPATION_INVARIANT only_eligible_for_replay_expects_replay passes`() {
        assertTrue(
            AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS["only_eligible_for_replay_expects_replay"] == true
        )
    }

    @Test
    fun `PARTICIPATION_INVARIANT classify_fresh_attach_decision_yields_fresh_attach_kind passes`() {
        assertTrue(
            AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS["classify_fresh_attach_decision_yields_fresh_attach_kind"] == true
        )
    }

    @Test
    fun `PARTICIPATION_INVARIANT classify_transport_reconnect_state_yields_transport_reconnect_kind passes`() {
        assertTrue(
            AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS["classify_transport_reconnect_state_yields_transport_reconnect_kind"] == true
        )
    }

    @Test
    fun `PARTICIPATION_INVARIANT classify_null_decision_yields_fresh_attach passes`() {
        assertTrue(
            AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS["classify_null_decision_yields_fresh_attach"] == true
        )
    }

    @Test
    fun `PARTICIPATION_INVARIANT classify_fresh_participant_yields_reuse_durable_participant passes`() {
        assertTrue(
            AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS["classify_fresh_participant_yields_reuse_durable_participant"] == true
        )
    }

    @Test
    fun `PARTICIPATION_INVARIANT classify_no_participant_id_yields_reuse_session_only passes`() {
        assertTrue(
            AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS["classify_no_participant_id_yields_reuse_session_only"] == true
        )
    }

    @Test
    fun `PARTICIPATION_INVARIANT classify_no_identity_yields_fresh_identity passes`() {
        assertTrue(
            AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS["classify_no_identity_yields_fresh_identity"] == true
        )
    }

    @Test
    fun `PARTICIPATION_INVARIANT classify_empty_queue_yields_queue_empty passes`() {
        assertTrue(
            AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS["classify_empty_queue_yields_queue_empty"] == true
        )
    }

    @Test
    fun `PARTICIPATION_INVARIANT classify_matching_queue_yields_eligible_for_replay passes`() {
        assertTrue(
            AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS["classify_matching_queue_yields_eligible_for_replay"] == true
        )
    }

    @Test
    fun `PARTICIPATION_INVARIANT classify_stale_queue_yields_stale_session_blocked passes`() {
        assertTrue(
            AndroidReconnectRecoveryParticipationContract.PARTICIPATION_INVARIANTS["classify_stale_queue_yields_stale_session_blocked"] == true
        )
    }

    // ── buildContractWireMap ──────────────────────────────────────────────────

    @Test
    fun `buildContractWireMap contains schema_version key`() {
        val map = AndroidReconnectRecoveryParticipationContract.buildContractWireMap()
        assertTrue(map.containsKey("schema_version"))
    }

    @Test
    fun `buildContractWireMap contains introduced_pr key with PR-12 value`() {
        val map = AndroidReconnectRecoveryParticipationContract.buildContractWireMap()
        assertEquals("PR-12", map["introduced_pr"])
    }

    @Test
    fun `buildContractWireMap contains test_class key`() {
        val map = AndroidReconnectRecoveryParticipationContract.buildContractWireMap()
        assertEquals("Pr12AndroidReconnectRecoveryParticipationTest", map["test_class"])
    }

    @Test
    fun `buildContractWireMap all_participation_invariants_pass is true`() {
        val map = AndroidReconnectRecoveryParticipationContract.buildContractWireMap()
        assertEquals(true, map["all_participation_invariants_pass"])
    }

    @Test
    fun `buildContractWireMap wire_fields contains all three new field names`() {
        val map = AndroidReconnectRecoveryParticipationContract.buildContractWireMap()
        @Suppress("UNCHECKED_CAST")
        val fields = map["wire_fields"] as List<String>
        assertTrue(fields.contains("reconnect_participation_kind"))
        assertTrue(fields.contains("identity_reuse_decision"))
        assertTrue(fields.contains("replay_eligibility"))
    }

    @Test
    fun `buildContractWireMap participation_kind_wire_values contains all 4 kinds`() {
        val map = AndroidReconnectRecoveryParticipationContract.buildContractWireMap()
        @Suppress("UNCHECKED_CAST")
        val values = map["participation_kind_wire_values"] as List<String>
        assertEquals(4, values.size)
    }

    @Test
    fun `buildContractWireMap identity_reuse_wire_values contains all 3 decisions`() {
        val map = AndroidReconnectRecoveryParticipationContract.buildContractWireMap()
        @Suppress("UNCHECKED_CAST")
        val values = map["identity_reuse_wire_values"] as List<String>
        assertEquals(3, values.size)
    }

    @Test
    fun `buildContractWireMap replay_eligibility_wire_values contains all 3 eligibilities`() {
        val map = AndroidReconnectRecoveryParticipationContract.buildContractWireMap()
        @Suppress("UNCHECKED_CAST")
        val values = map["replay_eligibility_wire_values"] as List<String>
        assertEquals(3, values.size)
    }

    // ── StabilizationBaseline registration ───────────────────────────────────

    @Test
    fun `StabilizationBaseline contains android-reconnect-recovery-participation entry`() {
        assertTrue(
            "'android-reconnect-recovery-participation' must be registered in StabilizationBaseline",
            StabilizationBaseline.isRegistered("android-reconnect-recovery-participation")
        )
    }

    @Test
    fun `StabilizationBaseline android-reconnect-recovery-participation is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-reconnect-recovery-participation")
        assertNotNull(entry)
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
    }

    @Test
    fun `StabilizationBaseline android-reconnect-recovery-participation has correct package path`() {
        val entry = StabilizationBaseline.forId("android-reconnect-recovery-participation")
        assertNotNull(entry)
        assertEquals(
            "com.ufo.galaxy.runtime.AndroidReconnectRecoveryParticipationContract",
            entry!!.packagePath
        )
    }

    @Test
    fun `StabilizationBaseline android-reconnect-recovery-participation is in canonical stable set`() {
        assertTrue(
            "'android-reconnect-recovery-participation' must be in the canonicalSurfaceIds set",
            StabilizationBaseline.canonicalSurfaceIds.contains("android-reconnect-recovery-participation")
        )
    }

    // ── Contract metadata constants ───────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR constant is PR-12`() {
        assertEquals("PR-12", AndroidReconnectRecoveryParticipationContract.INTRODUCED_PR)
    }

    @Test
    fun `SCHEMA_VERSION constant is 1_0`() {
        assertEquals("1.0", AndroidReconnectRecoveryParticipationContract.SCHEMA_VERSION)
    }

    @Test
    fun `TEST_CLASS constant references this test class`() {
        assertEquals(
            "Pr12AndroidReconnectRecoveryParticipationTest",
            AndroidReconnectRecoveryParticipationContract.TEST_CLASS
        )
    }

    // ── V2 semantic safety ────────────────────────────────────────────────────

    @Test
    fun `PROCESS_RECREATION_WITHOUT_CONTEXT v2RecoveryPath mentions V2 decision`() {
        val path = AndroidReconnectRecoveryParticipationContract.ReconnectParticipationKind
            .PROCESS_RECREATION_WITHOUT_CONTEXT.v2RecoveryPath
        assertTrue(
            "PROCESS_RECREATION_WITHOUT_CONTEXT path must mention V2 driving the decision: $path",
            path.contains("V2") || path.contains("re-dispatch") || path.contains("waiting")
        )
    }

    @Test
    fun `REUSE_DURABLE_PARTICIPANT v2ParticipantAction mentions looking up prior record`() {
        val action = AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision
            .REUSE_DURABLE_PARTICIPANT.v2ParticipantAction
        assertTrue(
            "REUSE_DURABLE_PARTICIPANT action must mention participant record lookup: $action",
            action.contains("participant") || action.contains("look up") || action.contains("prior")
        )
    }

    @Test
    fun `FRESH_IDENTITY v2ParticipantAction mentions initialising a new record`() {
        val action = AndroidReconnectRecoveryParticipationContract.IdentityReuseDecision
            .FRESH_IDENTITY.v2ParticipantAction
        assertTrue(
            "FRESH_IDENTITY action must mention new record initialisation: $action",
            action.contains("new") || action.contains("Initialise") || action.contains("initialise")
        )
    }

    @Test
    fun `ELIGIBLE_FOR_REPLAY isReplayExpected is true for V2 replay flush expectation`() {
        assertTrue(
            "ELIGIBLE_FOR_REPLAY.isReplayExpected must be true so V2 waits for the flush before declaring recovery complete",
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.ELIGIBLE_FOR_REPLAY.isReplayExpected
        )
    }

    @Test
    fun `STALE_SESSION_BLOCKED v2RecoveryPath does not expect replay flush`() {
        assertFalse(
            "STALE_SESSION_BLOCKED must not expect a replay flush from Android",
            AndroidReconnectRecoveryParticipationContract.ReplayEligibility.STALE_SESSION_BLOCKED.isReplayExpected
        )
    }
}
