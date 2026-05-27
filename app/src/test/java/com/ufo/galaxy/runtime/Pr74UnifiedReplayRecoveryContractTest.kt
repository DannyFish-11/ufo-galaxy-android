package com.ufo.galaxy.runtime

import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.network.OfflineTaskQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-74 — Unified replay and continuity execution contract: acceptance and integration tests.
 *
 * Validates the complete offline recovery contract introduced by PR-74, covering:
 *
 *  1. **Session tag at enqueue time** — [GalaxyWebSocketClient.sendJson] must tag every
 *     offline-queued message with the current `durableSessionId` so the authority filter
 *     can detect stale-session messages at replay time.
 *
 *  2. **Authority filter semantics** — [UnifiedReplayRecoveryContract.evaluateMessageAuthority]
 *     must correctly classify messages as REPLAY_ALLOWED, SAME_SESSION, or STALE_SESSION_BLOCKED.
 *
 *  3. **Stale-session blocking** — messages enqueued under an old session must be discarded
 *     before replay when the durable session changes.
 *
 *  4. **Gate enforcement** — replay goes through [GalaxyWebSocketClient.sendJson] (cross-device
 *     gate enforced); replay cannot bypass the gate.
 *
 *  5. **Contract machine-consumable output** — [UnifiedReplayRecoveryContract.buildContractWireMap]
 *     produces a stable wire map with all required contract assertions.
 *
 *  6. **Online / offline contract unified** — both online sends and offline replay use the same
 *     `goal_execution_result` AIP v3 envelope type and the same send path.
 *
 * ## Test matrix
 *
 * ### RecoveryPhase wire values
 *  - CONNECTING wireValue is "connecting"
 *  - RE_REGISTERING wireValue is "re_registering"
 *  - AUTHORITY_FILTERING wireValue is "authority_filtering"
 *  - REPLAYING wireValue is "replaying"
 *  - RECOVERED wireValue is "recovered"
 *  - all five wire values are distinct
 *  - fromWireValue round-trips all five values
 *  - fromWireValue returns null for unknown value
 *  - ALL_WIRE_VALUES has exactly five entries
 *
 * ### canonicalPhaseSequence
 *  - starts with CONNECTING
 *  - ends with RECOVERED
 *  - AUTHORITY_FILTERING precedes REPLAYING
 *  - RE_REGISTERING precedes AUTHORITY_FILTERING
 *  - contains all five phases
 *
 * ### MessageAuthorityDecision wire values and flags
 *  - NO_SESSION_TAG_FORWARDED isReplayAllowed = true
 *  - SAME_SESSION_REPLAY_ALLOWED isReplayAllowed = true
 *  - STALE_SESSION_BLOCKED isReplayAllowed = false
 *  - fromWireValue round-trips all three values
 *
 * ### evaluateMessageAuthority
 *  - null sessionTag → NO_SESSION_TAG_FORWARDED (always forwarded)
 *  - matching sessionTag → SAME_SESSION_REPLAY_ALLOWED
 *  - different sessionTag → STALE_SESSION_BLOCKED
 *  - null currentDurableSessionId + non-null tag → SAME_SESSION_REPLAY_ALLOWED
 *
 * ### Session tag at enqueue time (sendJson integration)
 *  - sendJson with active durableSessionId tags queued message with that sessionTag
 *  - sendJson with null durableSessionId tags queued message with null sessionTag
 *  - null-tagged messages are always forwarded by discardForDifferentSession
 *
 * ### Stale-session recovery path (offline queue integration)
 *  - stale-session messages are discarded before replay when session changes
 *  - current-session messages survive stale filtering
 *  - null-tagged (legacy) messages survive stale filtering
 *
 * ### Gate enforcement
 *  - sendJson cross-device gate blocks replay when crossDeviceEnabled = false
 *
 * ### Contract wire map machine-consumable output
 *  - buildContractWireMap contains all required contract fields
 *  - replay_uses_unified_gate = true
 *  - stale_session_messages_blocked = true
 *  - session_tag_set_at_enqueue = true
 *  - online_offline_contract_unified = true
 *  - authority_filtering_precedes_replay = true
 *  - INTRODUCED_PR = 74
 *  - REQUIRED_SEND_GATE contains "sendJson"
 */
class Pr74UnifiedReplayRecoveryContractTest {

    // ── RecoveryPhase — wire values ──────────────────────────────────────────

    @Test
    fun `RecoveryPhase CONNECTING wireValue is connecting`() {
        assertEquals("connecting", UnifiedReplayRecoveryContract.RecoveryPhase.CONNECTING.wireValue)
    }

    @Test
    fun `RecoveryPhase RE_REGISTERING wireValue is re_registering`() {
        assertEquals("re_registering", UnifiedReplayRecoveryContract.RecoveryPhase.RE_REGISTERING.wireValue)
    }

    @Test
    fun `RecoveryPhase AUTHORITY_FILTERING wireValue is authority_filtering`() {
        assertEquals("authority_filtering", UnifiedReplayRecoveryContract.RecoveryPhase.AUTHORITY_FILTERING.wireValue)
    }

    @Test
    fun `RecoveryPhase REPLAYING wireValue is replaying`() {
        assertEquals("replaying", UnifiedReplayRecoveryContract.RecoveryPhase.REPLAYING.wireValue)
    }

    @Test
    fun `RecoveryPhase RECOVERED wireValue is recovered`() {
        assertEquals("recovered", UnifiedReplayRecoveryContract.RecoveryPhase.RECOVERED.wireValue)
    }

    @Test
    fun `all RecoveryPhase wire values are distinct`() {
        val values = UnifiedReplayRecoveryContract.RecoveryPhase.ALL_WIRE_VALUES
        assertEquals(
            "All five RecoveryPhase wire values must be distinct",
            UnifiedReplayRecoveryContract.RecoveryPhase.entries.size,
            values.size
        )
    }

    @Test
    fun `RecoveryPhase ALL_WIRE_VALUES has exactly five entries`() {
        assertEquals(5, UnifiedReplayRecoveryContract.RecoveryPhase.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `RecoveryPhase fromWireValue round-trips all values`() {
        for (phase in UnifiedReplayRecoveryContract.RecoveryPhase.entries) {
            assertEquals(phase, UnifiedReplayRecoveryContract.RecoveryPhase.fromWireValue(phase.wireValue))
        }
    }

    @Test
    fun `RecoveryPhase fromWireValue returns null for unknown value`() {
        assertNull(UnifiedReplayRecoveryContract.RecoveryPhase.fromWireValue("unknown_phase"))
    }

    @Test
    fun `RecoveryPhase fromWireValue returns null for null input`() {
        assertNull(UnifiedReplayRecoveryContract.RecoveryPhase.fromWireValue(null))
    }

    // ── canonicalPhaseSequence ────────────────────────────────────────────────

    @Test
    fun `canonicalPhaseSequence starts with CONNECTING`() {
        assertEquals(
            UnifiedReplayRecoveryContract.RecoveryPhase.CONNECTING,
            UnifiedReplayRecoveryContract.canonicalPhaseSequence.first()
        )
    }

    @Test
    fun `canonicalPhaseSequence ends with RECOVERED`() {
        assertEquals(
            UnifiedReplayRecoveryContract.RecoveryPhase.RECOVERED,
            UnifiedReplayRecoveryContract.canonicalPhaseSequence.last()
        )
    }

    @Test
    fun `canonicalPhaseSequence AUTHORITY_FILTERING precedes REPLAYING`() {
        val seq = UnifiedReplayRecoveryContract.canonicalPhaseSequence
        val filterIdx = seq.indexOf(UnifiedReplayRecoveryContract.RecoveryPhase.AUTHORITY_FILTERING)
        val replayIdx = seq.indexOf(UnifiedReplayRecoveryContract.RecoveryPhase.REPLAYING)
        assertTrue(
            "AUTHORITY_FILTERING (idx=$filterIdx) must precede REPLAYING (idx=$replayIdx) " +
                "so stale messages are purged before the queue is drained",
            filterIdx < replayIdx
        )
    }

    @Test
    fun `canonicalPhaseSequence RE_REGISTERING precedes AUTHORITY_FILTERING`() {
        val seq = UnifiedReplayRecoveryContract.canonicalPhaseSequence
        val regIdx = seq.indexOf(UnifiedReplayRecoveryContract.RecoveryPhase.RE_REGISTERING)
        val filterIdx = seq.indexOf(UnifiedReplayRecoveryContract.RecoveryPhase.AUTHORITY_FILTERING)
        assertTrue(
            "RE_REGISTERING (idx=$regIdx) must precede AUTHORITY_FILTERING (idx=$filterIdx)",
            regIdx < filterIdx
        )
    }

    @Test
    fun `canonicalPhaseSequence contains all five phases`() {
        val seq = UnifiedReplayRecoveryContract.canonicalPhaseSequence
        for (phase in UnifiedReplayRecoveryContract.RecoveryPhase.entries) {
            assertTrue("$phase must be in the canonical phase sequence", seq.contains(phase))
        }
    }

    // ── MessageAuthorityDecision ──────────────────────────────────────────────

    @Test
    fun `MessageAuthorityDecision NO_SESSION_TAG_FORWARDED isReplayAllowed is true`() {
        assertTrue(UnifiedReplayRecoveryContract.MessageAuthorityDecision.NO_SESSION_TAG_FORWARDED.isReplayAllowed)
    }

    @Test
    fun `MessageAuthorityDecision NO_SESSION_TAG_AUTHORITY_REPLAY_BLOCKED isReplayAllowed is false`() {
        assertFalse(
            UnifiedReplayRecoveryContract
                .MessageAuthorityDecision
                .NO_SESSION_TAG_AUTHORITY_REPLAY_BLOCKED
                .isReplayAllowed
        )
    }

    @Test
    fun `MessageAuthorityDecision NO_CURRENT_AUTHORITY_BLOCKED isReplayAllowed is false`() {
        assertFalse(UnifiedReplayRecoveryContract.MessageAuthorityDecision.NO_CURRENT_AUTHORITY_BLOCKED.isReplayAllowed)
    }

    @Test
    fun `MessageAuthorityDecision SAME_SESSION_REPLAY_ALLOWED isReplayAllowed is true`() {
        assertTrue(UnifiedReplayRecoveryContract.MessageAuthorityDecision.SAME_SESSION_REPLAY_ALLOWED.isReplayAllowed)
    }

    @Test
    fun `MessageAuthorityDecision STALE_SESSION_BLOCKED isReplayAllowed is false`() {
        assertFalse(UnifiedReplayRecoveryContract.MessageAuthorityDecision.STALE_SESSION_BLOCKED.isReplayAllowed)
    }

    @Test
    fun `MessageAuthorityDecision fromWireValue round-trips all values`() {
        for (decision in UnifiedReplayRecoveryContract.MessageAuthorityDecision.entries) {
            assertEquals(
                decision,
                UnifiedReplayRecoveryContract.MessageAuthorityDecision.fromWireValue(decision.wireValue)
            )
        }
    }

    @Test
    fun `MessageAuthorityDecision fromWireValue returns null for unknown value`() {
        assertNull(UnifiedReplayRecoveryContract.MessageAuthorityDecision.fromWireValue("unknown"))
    }

    // ── evaluateMessageAuthority ──────────────────────────────────────────────

    @Test
    fun `null sessionTag on authority-sensitive type is blocked`() {
        val msg = OfflineTaskQueue.QueuedMessage(
            type = "goal_execution_result",
            json = "{}",
            sessionTag = null
        )
        val decision = UnifiedReplayRecoveryContract.evaluateMessageAuthority(msg, "session-A")
        assertEquals(
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.NO_SESSION_TAG_AUTHORITY_REPLAY_BLOCKED,
            decision
        )
        assertFalse(decision.isReplayAllowed)
    }

    @Test
    fun `null sessionTag on runtime truth replay is blocked`() {
        val msg = OfflineTaskQueue.QueuedMessage(
            type = "device_state_snapshot",
            json = "{}",
            sessionTag = null
        )
        val decision = UnifiedReplayRecoveryContract.evaluateMessageAuthority(msg, "session-A")
        assertEquals(
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.NO_SESSION_TAG_AUTHORITY_REPLAY_BLOCKED,
            decision
        )
        assertFalse(decision.isReplayAllowed)
    }

    @Test
    fun `matching sessionTag produces SAME_SESSION_REPLAY_ALLOWED`() {
        val msg = OfflineTaskQueue.QueuedMessage(
            type = "goal_execution_result",
            json = "{}",
            sessionTag = "session-A"
        )
        val decision = UnifiedReplayRecoveryContract.evaluateMessageAuthority(msg, "session-A")
        assertEquals(
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.SAME_SESSION_REPLAY_ALLOWED,
            decision
        )
        assertTrue(decision.isReplayAllowed)
    }

    @Test
    fun `different sessionTag produces STALE_SESSION_BLOCKED`() {
        val msg = OfflineTaskQueue.QueuedMessage(
            type = "goal_execution_result",
            json = "{}",
            sessionTag = "session-OLD"
        )
        val decision = UnifiedReplayRecoveryContract.evaluateMessageAuthority(msg, "session-CURRENT")
        assertEquals(
            "A message from session-OLD must be blocked when the current session is session-CURRENT",
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.STALE_SESSION_BLOCKED,
            decision
        )
        assertFalse("Stale-session messages must NOT be replayed", decision.isReplayAllowed)
    }

    @Test
    fun `null currentDurableSessionId blocks tagged message replay`() {
        val msg = OfflineTaskQueue.QueuedMessage(
            type = "goal_execution_result",
            json = "{}",
            sessionTag = "some-session"
        )
        val decision = UnifiedReplayRecoveryContract.evaluateMessageAuthority(
            msg,
            currentDurableSessionId = null
        )
        assertEquals(
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.NO_CURRENT_AUTHORITY_BLOCKED,
            decision
        )
        assertFalse(decision.isReplayAllowed)
    }

    @Test
    fun `ownership class distinguishes canonical participant local fallback and rejected divergence`() {
        val canonical = OfflineTaskQueue.QueuedMessage(
            type = "goal_execution_result",
            json =
                """{"type":"goal_execution_result","canonical_ownership_status":"canonical_bound","truth_ingress_class":"canonicalization_candidate","is_canonicalization_ready":true}"""
        )
        val participantLocal = OfflineTaskQueue.QueuedMessage(
            type = "goal_execution_result",
            json =
                """{"type":"goal_execution_result","canonical_ownership_status":"participant_local_only","truth_ingress_class":"participant_local_truth","is_canonicalization_ready":false}"""
        )
        val fallback = OfflineTaskQueue.QueuedMessage(
            type = "goal_execution_result",
            json =
                """{"type":"goal_execution_result","canonical_ownership_status":"diverged_fallback","ownership_divergence_marker":"offline_queue_pending_canonicalization"}"""
        )
        val rejected = OfflineTaskQueue.QueuedMessage(
            type = "goal_execution_result",
            json =
                """{"type":"goal_execution_result","canonical_ownership_status":"diverged_fallback","ownership_divergence_marker":"transport_send_failed"}"""
        )

        assertEquals(
            UnifiedReplayRecoveryContract.ReplayOwnershipClass.CANONICALIZED_TRUTH,
            UnifiedReplayRecoveryContract.classifyReplayOwnership(canonical)
        )
        assertEquals(
            UnifiedReplayRecoveryContract.ReplayOwnershipClass.PARTICIPANT_LOCAL_TRUTH,
            UnifiedReplayRecoveryContract.classifyReplayOwnership(participantLocal)
        )
        assertEquals(
            UnifiedReplayRecoveryContract.ReplayOwnershipClass.FALLBACK_NON_CANONICAL,
            UnifiedReplayRecoveryContract.classifyReplayOwnership(fallback)
        )
        assertEquals(
            UnifiedReplayRecoveryContract.ReplayOwnershipClass.REJECTED_NON_CANONICAL_DIVERGENCE,
            UnifiedReplayRecoveryContract.classifyReplayOwnership(rejected)
        )
    }

    @Test
    fun `same-session participant-local truth is blocked from canonical replay`() {
        val msg = OfflineTaskQueue.QueuedMessage(
            type = "goal_execution_result",
            json =
                """{"type":"goal_execution_result","canonical_ownership_status":"participant_local_only","truth_ingress_class":"participant_local_truth","is_canonicalization_ready":false}""",
            sessionTag = "session-A"
        )
        val decision = UnifiedReplayRecoveryContract.evaluateMessageAuthority(msg, "session-A")
        assertEquals(
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.PARTICIPANT_LOCAL_TRUTH_REPLAY_BLOCKED,
            decision
        )
        assertFalse(decision.isReplayAllowed)
    }

    @Test
    fun `same-session fallback and divergence truths are blocked from canonical replay`() {
        val fallback = OfflineTaskQueue.QueuedMessage(
            type = "goal_execution_result",
            json =
                """{"type":"goal_execution_result","canonical_ownership_status":"diverged_fallback","ownership_divergence_marker":"offline_queue_pending_canonicalization"}""",
            sessionTag = "session-A"
        )
        val rejected = OfflineTaskQueue.QueuedMessage(
            type = "goal_execution_result",
            json =
                """{"type":"goal_execution_result","canonical_ownership_status":"diverged_fallback","ownership_divergence_marker":"transport_send_failed"}""",
            sessionTag = "session-A"
        )

        assertEquals(
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.FALLBACK_NON_CANONICAL_REPLAY_BLOCKED,
            UnifiedReplayRecoveryContract.evaluateMessageAuthority(fallback, "session-A")
        )
        assertEquals(
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.REJECTED_NON_CANONICAL_REPLAY_BLOCKED,
            UnifiedReplayRecoveryContract.evaluateMessageAuthority(rejected, "session-A")
        )
    }

    // ── Session tag at enqueue time (sendJson integration) ────────────────────

    @Test
    fun `sendJson tags offline-queued message with current durableSessionId`() {
        // When a durable session is active, every message queued by sendJson while
        // disconnected must carry the current durableSessionId as its sessionTag.
        // This is the fix that makes the authority filter (discardForDifferentSession)
        // able to block stale-session messages on reconnect.
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = testQueue
        )
        client.updateRuntimeConnectionConfig(durableSessionId = "session-ACTIVE")

        client.sendJson(
            """{"type":"goal_execution_result","payload":{"task_id":"t1","status":"success"}}"""
        )

        val messages = testQueue.drainAll()
        assertEquals("One message must be queued", 1, messages.size)
        assertEquals(
            "The queued message must carry the current durableSessionId as sessionTag",
            "session-ACTIVE",
            messages[0].sessionTag
        )
    }

    @Test
    fun `sendJson with null durableSessionId tags queued message with null sessionTag`() {
        // When no durable session is active, messages are queued with null sessionTag.
        // Null-tagged messages are always forwarded during replay (legacy compatibility).
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = testQueue
        )
        // No durableSessionId set — defaults to null.

        client.sendJson(
            """{"type":"goal_execution_result","payload":{"task_id":"t2","status":"error"}}"""
        )

        val messages = testQueue.drainAll()
        assertEquals(1, messages.size)
        assertNull(
            "Messages queued without an active durable session must have null sessionTag",
            messages[0].sessionTag
        )
    }

    @Test
    fun `sessionTag is updated when durableSessionId changes between sends`() {
        // If the durable session changes (e.g. session invalidation between sends),
        // each subsequent sendJson enqueue must carry the new session tag.
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = testQueue
        )

        client.updateRuntimeConnectionConfig(durableSessionId = "session-FIRST")
        client.sendJson("""{"type":"goal_execution_result","payload":{"task_id":"t-first"}}""")

        client.updateRuntimeConnectionConfig(durableSessionId = "session-SECOND")
        client.sendJson("""{"type":"goal_execution_result","payload":{"task_id":"t-second"}}""")

        val messages = testQueue.drainAll()
        assertEquals(2, messages.size)
        assertEquals("session-FIRST", messages[0].sessionTag)
        assertEquals("session-SECOND", messages[1].sessionTag)
    }

    // ── Stale-session recovery path (offline queue integration) ──────────────

    @Test
    fun `stale session messages are discarded and current-session messages survive`() {
        // Verifies the full stale-session recovery path used by flushOfflineQueue:
        // 1. Messages are enqueued under different sessions.
        // 2. discardForDifferentSession filters based on the current session.
        // 3. Only current-session messages survive for authority-sensitive replay types.
        val queue = OfflineTaskQueue(prefs = null)
        queue.enqueue(
            "goal_execution_result",
            """{"type":"goal_execution_result","payload":{"task_id":"stale-1"}}""",
            sessionTag = "session-OLD"
        )
        queue.enqueue(
            "goal_execution_result",
            """{"type":"goal_execution_result","payload":{"task_id":"current-1"}}""",
            sessionTag = "session-NEW"
        )
        queue.enqueue(
            "goal_execution_result",
            """{"type":"goal_execution_result","payload":{"task_id":"stale-2"}}""",
            sessionTag = "session-OLD"
        )

        val discarded = queue.discardForDifferentSession("session-NEW")

        assertEquals("Two stale-session messages must be discarded", 2, discarded)
        assertEquals("One current-session message must survive", 1, queue.size)
        val remaining = queue.drainAll()
        assertTrue(remaining[0].json.contains("current-1"))
    }

    @Test
    fun `null-tagged authority-sensitive messages are blocked during stale-session filtering`() {
        val queue = OfflineTaskQueue(prefs = null)
        queue.enqueue(
            "goal_execution_result",
            """{"type":"goal_execution_result","payload":{"task_id":"legacy"}}"""
            // No sessionTag → null
        )
        queue.enqueue(
            "goal_execution_result",
            """{"type":"goal_execution_result","payload":{"task_id":"stale"}}""",
            sessionTag = "session-OLD"
        )

        val discarded = queue.discardForDifferentSession("session-NEW")

        assertEquals("Both legacy-null and stale-tagged messages must be discarded", 2, discarded)
        assertEquals(0, queue.size)
    }

    @Test
    fun `null-tagged runtime truth messages are discarded during stale-session filtering`() {
        val queue = OfflineTaskQueue(prefs = null)
        queue.enqueue(
            "device_state_snapshot",
            """{"type":"device_state_snapshot","payload":{"task_id":"snapshot"}}"""
        )

        val discarded = queue.discardForDifferentSession("session-NEW")

        assertEquals(1, discarded)
        assertEquals(0, queue.size)
    }

    @Test
    fun `non-canonical ownership records are isolated while canonical replay record survives`() {
        val queue = OfflineTaskQueue(prefs = null)
        queue.enqueue(
            "goal_execution_result",
            """{"type":"goal_execution_result","canonical_ownership_status":"canonical_bound","truth_ingress_class":"canonicalization_candidate","is_canonicalization_ready":true}""",
            sessionTag = "session-NEW"
        )
        queue.enqueue(
            "goal_execution_result",
            """{"type":"goal_execution_result","canonical_ownership_status":"participant_local_only","truth_ingress_class":"participant_local_truth","is_canonicalization_ready":false}""",
            sessionTag = "session-NEW"
        )
        queue.enqueue(
            "goal_execution_result",
            """{"type":"goal_execution_result","canonical_ownership_status":"diverged_fallback","ownership_divergence_marker":"transport_send_failed"}""",
            sessionTag = "session-NEW"
        )

        val discarded = queue.discardForDifferentSession("session-NEW")

        assertEquals(2, discarded)
        val surviving = queue.drainAll()
        assertEquals(1, surviving.size)
        assertTrue(surviving[0].json.contains("\"canonical_bound\""))
    }

    // ── Gate enforcement ──────────────────────────────────────────────────────

    @Test
    fun `replay is blocked when crossDeviceEnabled is false — gate enforced`() {
        // The cross-device gate in sendJson must block messages even during replay.
        // This verifies that replay cannot bypass the gate by turning cross-device OFF.
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false,
            offlineQueue = testQueue
        )

        val sent = client.sendJson(
            """{"type":"goal_execution_result","payload":{"task_id":"t-gate","status":"success"}}"""
        )

        assertFalse("Gate must block send when cross-device is OFF", sent)
        assertEquals(
            "Gate must also prevent offline queuing when cross-device is OFF",
            0,
            testQueue.size
        )
    }

    @Test
    fun `sendJson is the exclusive replay gate — task_result also goes through gate`() {
        // All QUEUEABLE_TYPES must be handled by sendJson with the same gate logic.
        // This test verifies task_result (legacy compat type) also respects the gate.
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false,
            offlineQueue = testQueue
        )

        val sent = client.sendJson("""{"type":"task_result","payload":{"task_id":"tr1"}}""")

        assertFalse("task_result must be blocked by the gate when cross-device is OFF", sent)
        assertEquals(0, testQueue.size)
    }

    // ── Contract wire map (machine-consumable output) ─────────────────────────

    @Test
    fun `buildContractWireMap contains schema_version`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        assertTrue(wireMap.containsKey("schema_version"))
        assertEquals("1.0", wireMap["schema_version"])
    }

    @Test
    fun `buildContractWireMap contains introduced_pr = 74`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        assertEquals(74, wireMap["introduced_pr"])
    }

    @Test
    fun `buildContractWireMap contains required_send_gate referencing sendJson`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        assertNotNull(wireMap["required_send_gate"])
        assertTrue(
            "required_send_gate must reference sendJson",
            wireMap["required_send_gate"].toString().contains("sendJson")
        )
    }

    @Test
    fun `buildContractWireMap replay_uses_unified_gate is true`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        assertTrue(wireMap["replay_uses_unified_gate"] as Boolean)
    }

    @Test
    fun `buildContractWireMap stale_session_messages_blocked is true`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        assertTrue(wireMap["stale_session_messages_blocked"] as Boolean)
    }

    @Test
    fun `buildContractWireMap no_current_authority_replay_blocked is true`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        assertTrue(wireMap["no_current_authority_replay_blocked"] as Boolean)
    }

    @Test
    fun `buildContractWireMap authority_sensitive_null_tag_replay_blocked is true`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        assertTrue(wireMap["authority_sensitive_null_tag_replay_blocked"] as Boolean)
    }

    @Test
    fun `buildContractWireMap non_canonical_ownership_replay_blocked is true`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        assertTrue(wireMap["non_canonical_ownership_replay_blocked"] as Boolean)
    }

    @Test
    fun `buildContractWireMap includes replay ownership classes`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        @Suppress("UNCHECKED_CAST")
        val ownershipClasses = wireMap["replay_ownership_classes"] as List<String>
        assertTrue(ownershipClasses.contains("canonicalized_truth"))
        assertTrue(ownershipClasses.contains("participant_local_truth"))
        assertTrue(ownershipClasses.contains("fallback_non_canonical"))
        assertTrue(ownershipClasses.contains("rejected_non_canonical_divergence"))
    }

    @Test
    fun `buildContractWireMap includes authority_sensitive_replay_types`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        @Suppress("UNCHECKED_CAST")
        val types = wireMap["authority_sensitive_replay_types"] as List<String>
        assertTrue(types.contains("goal_execution_result"))
        assertTrue(types.contains("delegated_execution_signal"))
        assertTrue(types.contains("device_execution_event"))
    }

    @Test
    fun `buildContractWireMap session_tag_set_at_enqueue is true`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        assertTrue(wireMap["session_tag_set_at_enqueue"] as Boolean)
    }

    @Test
    fun `buildContractWireMap online_offline_contract_unified is true`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        assertTrue(wireMap["online_offline_contract_unified"] as Boolean)
    }

    @Test
    fun `buildContractWireMap authority_filtering_precedes_replay is true`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        assertTrue(wireMap["authority_filtering_precedes_replay"] as Boolean)
    }

    @Test
    fun `buildContractWireMap canonical_phase_sequence contains all five phases in order`() {
        val wireMap = UnifiedReplayRecoveryContract.buildContractWireMap()
        @Suppress("UNCHECKED_CAST")
        val phases = wireMap["canonical_phase_sequence"] as List<String>
        assertEquals(5, phases.size)
        assertEquals("connecting", phases[0])
        assertEquals("re_registering", phases[1])
        assertEquals("authority_filtering", phases[2])
        assertEquals("replaying", phases[3])
        assertEquals("recovered", phases[4])
    }

    // ── Module-level constants ────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 74`() {
        assertEquals(74, UnifiedReplayRecoveryContract.INTRODUCED_PR)
    }

    @Test
    fun `SCHEMA_VERSION is 1 dot 0`() {
        assertEquals("1.0", UnifiedReplayRecoveryContract.SCHEMA_VERSION)
    }

    @Test
    fun `REQUIRED_SEND_GATE contains sendJson`() {
        assertTrue(
            "REQUIRED_SEND_GATE must reference the sendJson method",
            UnifiedReplayRecoveryContract.REQUIRED_SEND_GATE.contains("sendJson")
        )
    }
}
